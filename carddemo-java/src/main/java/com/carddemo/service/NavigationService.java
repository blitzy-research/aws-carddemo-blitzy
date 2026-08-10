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

import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.AbendException;
import com.carddemo.domain.enums.UserType;

/**
 * The CardDemo navigation graph, expressed as a route vocabulary plus the rules that select a route.
 * Translated from the transfer-control dispatch sites and pseudo-conversational re-arm sites of the 17
 * online programs in {@code app/cbl}, the transaction-to-program bindings of
 * {@code app/csd/CARDDEMO.CSD}, and the navigation state of {@code app/cpy/COCOM01Y.cpy}.
 *
 * <p><strong>There is no server-side forwarding, and that is the central design decision of this
 * class.</strong> In the legacy system control was handed to another program inside the same task,
 * carrying the communication area with it, and the next pseudo-conversational turn was re-armed on the
 * same terminal. Neither is reproduced: an endpoint <em>returns a route constant in its response body</em>
 * and the <strong>client drives the next call</strong>. That is what makes every endpoint independently
 * testable &mdash; no endpoint is reachable only as the continuation of another, and no test has to drive
 * a conversation to reach a screen. This class accordingly performs no redirect, no {@code forward:}, no
 * request-dispatcher call, no response-entity construction and no web-framework operation of any kind.
 *
 * <p>It has exactly two responsibilities: it owns the route vocabulary, one constant per reachable
 * destination, as compile-time strings in {@link Routes} and in typed form in {@link Route}; and it owns
 * the route-resolution rules, one named method per legacy rule. It returns values, and callers place
 * those values in their own response payloads.
 *
 * <p><strong>Destinations are named for their role, never for a COBOL program.</strong> No route value on
 * the wire contains a legacy program name; each legacy identifier is recorded on the constant it belongs
 * to instead, which keeps the published contract free of mainframe vocabulary while leaving every route
 * traceable. The 18 reachable destinations correspond exactly to the 18 transaction definitions the
 * resource definition file registers.
 *
 * <p><strong>Two source anomalies are recorded and neither produces a target.</strong> The resource
 * definition file registers a program definition {@code COCRDSEC} for which no source member exists, so
 * <em>no route constant is created for it</em>. The developer transaction {@code CDV1} is itself bound to
 * that dangling definition, which means it cannot have been dispatchable in the shipped estate; the
 * date-validation destination therefore records {@code CSUTLDTC}, the subprogram that does exist, and the
 * binding anomaly is documented rather than reproduced.
 *
 * <p><strong>Destinations are named for their role, never for a COBOL program.</strong> No route
 * value on the wire contains a legacy program name; each legacy program and transaction identifier
 * is recorded in the documentation of its constant instead, which keeps the published contract free
 * of mainframe vocabulary while leaving every route traceable back to its origin. The resource
 * definition file registers 18 transaction definitions, and 17 of them have a reachable destination:
 * the eighteenth is bound to a program definition that has no source member, so it is recorded as an
 * anomaly below rather than published as a route.
 *
 * <p><strong>Two source anomalies are recorded here and neither produces a target.</strong> The
 * resource definition file registers a program definition named {@code COCRDSEC} for which no source
 * member exists anywhere in {@code app/cbl}; it is a dangling definition, so <em>no route constant is
 * created for it</em>. Verification of that finding turned up a second one: the developer transaction
 * {@code CDV1} at {@code [app/csd/CARDDEMO.CSD:L388]} is itself bound to that dangling program
 * definition at {@code [app/csd/CARDDEMO.CSD:L211]}, which means the transaction cannot have been
 * dispatchable in the shipped estate. <em>No route constant is created for {@code CDV1} either</em>, and
 * the reason is worth stating because an earlier revision of this class did create one.
 *
 * <p>That revision reasoned that {@code CDV1} is one of the eighteen registered transaction definitions
 * and that omitting it would leave the vocabulary short of the resource definition it derives from, so
 * it substituted the date-validation subprogram {@code CSUTLDTC} as the destination's implementation.
 * The substitution invents a destination the estate does not have. {@code CSUTLDTC} is bound to no
 * transaction anywhere in the resource definition file, is named by no transfer-control statement and by
 * no menu catalogue, and is reached only by static {@code CALL} from four sites - two in
 * {@code app/cbl/COTRN02C.cbl} and two in {@code app/cbl/CORPT00C.cbl}. It is an internal subprogram,
 * not a navigable screen, and it stays internal: it is modelled by the date-validation service and is
 * absent from this vocabulary. A transaction whose only binding is to a program that does not exist has
 * no reachable destination to record, so the honest vocabulary is seventeen destinations and an anomaly
 * note, not eighteen destinations one of which is fabricated. The dangling binding is anomaly 3 of the
 * register in {@code docs/decision-log.md}, and both this correction and the unresolvable-transfer
 * correction below are reasoned in {@code docs/decision-log.md} DL-107.
 *
 * <p><strong>Rules, and where each comes from.</strong> Every method below is one legacy navigation
 * rule, named so it can be cited:
 *
 * <ul>
 *   <li>the sign-on role split - {@link #resolveSignOnRoute(UserType)} and
 *       {@link #resolveSignOnRouteForUserTypeCode(String)};</li>
 *   <li>entry with no prior state - {@link #isConversationStateAbsent(ConversationState)} and
 *       {@link #resolveAbsentContextRoute()};</li>
 *   <li>return to the previous screen - {@link #resolveBackNavigation(ConversationState, Route)},
 *       reached through {@link #resolveAttentionKeyRoute(KeyAction, ConversationState, Route)};</li>
 *   <li>return to an already nominated destination -
 *       {@link #resolveNominatedDestination(ConversationState, Route)} and its sign-off special case
 *       {@link #resolveSignOffRoute(ConversationState)};</li>
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
 * not here. It makes no authorisation decision: {@link #adminScopedRoutes()} and
 * {@link Route#isAdminScoped()} merely <em>report</em> that a destination is administrative.
 * It touches no database, no repository, no monetary value and no fixed-width record offset.
 *
 * <p><strong>Administrative access is enforced outside this class.</strong> The security chain requires
 * the administrator authority for every request beneath {@code /api/admin/**}. The delivered boundary
 * maps the administrative menu at {@code /api/admin/menu} and the four user-maintenance transactions
 * beneath {@code /api/admin/users}; route-security tests exercise those mappings with a
 * non-administrative principal. This class therefore does not duplicate the authority decision. Its
 * route values - {@code admin-menu}, {@code user-list}, and their peers - remain logical response
 * labels rather than URLs, while {@link #adminScopedRoutes()} remains descriptive metadata rather than
 * the mechanism that enforces the HTTP rule.
 *
 * <p><strong>Immutability and thread safety.</strong> Every lookup table is built once during class
 * initialization and published only through an unmodifiable view, and the class declares no mutable
 * field of any kind. Instances are therefore stateless and safe to share across threads, which is
 * what allows the framework to manage a single one. Constructor injection is the convention
 * throughout this layer; this class injects nothing because it collaborates with nothing.
 *
 * <p><strong>What a diagnostic is allowed to say about a program name.</strong> The two nomination
 * rules read their program name out of the navigation state a <em>client</em> echoes, and the
 * published contract bounds those fields by width alone - eight characters - and by nothing else. A
 * caller may therefore supply a name containing a line feed, a carriage return, a null or any other
 * control character, and every one of them survives this class's blank test and padding strip, which
 * recognise the space and the low value as padding and nothing more. Reproducing such a value inside
 * a log record would let the caller choose where a record ends and the next begins, so
 * <strong>no diagnostic here reproduces a caller-supplied value that is not printable US-ASCII</strong>;
 * a renderer substitutes the offending character's zero-based position and code point instead, which
 * localises the fault exactly without echoing it. Because the framework's parameter substitution
 * escapes nothing, this is a correctness requirement of the diagnostic and not a matter of taste.
 *
 * <p>Three renderers exist rather than one, and they differ by the provenance of what they screen -
 * {@link #describeNomination(String)} for a nomination a client echoed,
 * {@link #renderCatalogName(String)} for a program name the server's own option catalogue supplied,
 * and {@link #describeForLog(String)} for a menu option's user-type code. The distinction is
 * deliberate: a nomination is never reproduced even when it is printable, because an unrecognised
 * client-chosen name is itself the finding and its length is all a reader needs, whereas a catalogue
 * name is worth naming in full because an operator confirming that a menu entry is deliberately
 * inactive needs to see which entry it was.
 *
 * <p>What is single is the scan. All three delegate to {@link #firstNonPrintableIndex(String)}, which
 * reads the one printable bound this class declares, so the three cannot come to disagree about which
 * values offend - only about what to say once one does. No caller-supplied value reaches this class's
 * logger by any other path, so a diagnostic added later cannot reopen the hole by interpolating a raw
 * value. Route tokens, rule names, transaction identifiers and enum constants are not caller-supplied
 * and are logged directly - each is a value this class or the vocabulary itself declares. Prose that
 * states this class's own expectation of a value is not an echo of one.
 *
 * <p><strong>Rendering never changes resolution.</strong> The printable bound governs what a
 * diagnostic may say and never what this class accepts: a name outside the range resolves, or fails
 * to resolve, exactly as it did before, and the caller's default still applies on the same terms.
 * Nothing on any resolution path consults the bound.
 *
 * <p><strong>Faithful beats idiomatic.</strong> Where the legacy semantics and the natural Java shape
 * diverge, the legacy wins and the divergence is documented at the member concerned. The three that
 * matter most are the unconditional alternative in the sign-on split, which routes an undeclared user
 * type to the main menu rather than rejecting it; the two documented-unreachable menu branches, which
 * are preserved rather than deleted or corrected; and the treatment of an unrecognised program name,
 * described on {@link #resolveBackNavigation(ConversationState, Route)}.
 */
@Service
public final class NavigationService {

    /**
     * Diagnostic channel for this class, replacing the console-display statements that were the
     * legacy system's only instrumentation.
     *
     * <p>The logger name is the fully qualified class name, which places it under the
     * {@code com.carddemo.service} logger declared in the logging configuration. Only route tokens,
     * rule names and program-name renderings are ever logged from here; no navigation context is
     * logged as a whole, and nothing regulated passes through this class at all.
     *
     * <p><strong>Every caller-supplied value reaching this logger passes through one of the three
     * renderers first</strong>, and all three screen against the single scan the class declares. The
     * program names this class resolves arrive in client-echoed navigation state, so they are untrusted
     * text and not the eight-character mainframe tokens their legacy width suggests; see the class
     * documentation for which renderer screens which provenance, and for why that makes the screening a
     * correctness requirement rather than a precaution.
     */
    private static final Logger LOG = LoggerFactory.getLogger(NavigationService.class);

    /**
     * Sentinel returned by {@link #firstNonPrintableIndex(String)} when every character of a value is
     * printable US-ASCII: -1, which is no valid index.
     */
    private static final int NO_NON_PRINTABLE_INDEX = -1;

    /**
     * Fixed stand-in a diagnostic uses for an absent value: {@code (absent)}.
     *
     * <p>Preferred over the framework's rendering of an absent reference because that rendering is the
     * four-character text {@code null}, which is indistinguishable from a program name of that exact
     * text. The parenthesised form cannot be confused with any value, since a value that reaches a
     * diagnostic verbatim is printable US-ASCII and this substitute is only ever produced for an
     * absent one.
     */
    private static final String ABSENT_VALUE_SUBSTITUTE = "(absent)";
    /* Used by all three diagnostic renderers - describeNomination, renderCatalogName and
     * describeForLog - so one absent value cannot render two different ways depending on which
     * renderer happened to see it. */

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
     * Number of reachable destinations: 17, one per online program that both exists in {@code app/cbl}
     * and is bound to a transaction definition in {@code app/csd/CARDDEMO.CSD}.
     *
     * <p>The resource definition file registers 18 transaction definitions and 18 program definitions,
     * and neither figure is this one. The two sets are not in correspondence: one program definition,
     * {@code COCRDSEC}, is dangling, and the one transaction bound to it, {@code CDV1}, is therefore not
     * dispatchable. Both anomalies are described on the class documentation and neither produces a
     * destination, which is why a vocabulary derived from 18 registrations numbers 17.</p>
     */
    public static final int ROUTE_COUNT = 17;

    /**
     * Abend reason for a nomination naming a destination that cannot be resolved. Within the fifty
     * characters {@code ABEND-REASON} reserves.
     */
    private static final String UNRESOLVABLE_PROGRAM_REASON = "XCTL TO UNRESOLVABLE PROGRAM NAME";

    /**
     * The five leading characters that suppress menu dispatch. The legacy guard compares only the first
     * five characters of the catalogued program name against this literal, at
     * {@code app/cbl/COMEN01C.cbl:L146} and {@code app/cbl/COADM01C.cbl:L138}, so both the literal and the
     * five-character width are part of the rule. See {@link #isDispatchSuppressed(String)}.
     */
    public static final String DUMMY_PROGRAM_PREFIX = "DUMMY";

    /**
     * The catalogued option user-type code that the user menu's administrator-only gate tests. Declared
     * here rather than borrowed from the menu catalogue, because that catalogue lives in the configuration
     * layer and this layer must not depend on it.
     */
    public static final String ADMIN_ONLY_OPTION_USER_TYPE_CODE = "A";

    /**
     * Lowest character this class will place inside a log record: the ASCII space.
     *
     * <p>Everything below it is a C0 control code, and this class's records are the only place a
     * client-echoed program nomination reaches a log file. A line feed ends the record, so the text
     * after it appears as a separate entry that the service never emitted; a carriage return returns the
     * cursor so the remainder overwrites what preceded it. The bound is the same one
     * {@link com.carddemo.util.FixedWidthFieldReader} and {@code JobSubmissionService} apply to
     * fragments they did not author, so that "printable" has one meaning across the module.
     *
     * <p>It governs <strong>rendering</strong> only and never acceptance. A program name carrying a
     * character outside the range still resolves, or still fails to resolve, exactly as it did before -
     * it is merely described rather than reproduced when a diagnostic mentions it. Conflating the two
     * would change which navigations this class permits.
     *
     * <p>It is the single bound behind all three of this class's renderers. Two of them screen a
     * program name and the third screens a menu option's user-type code; each delegates the scan to
     * {@link #firstNonPrintableIndex(String)}, so the three cannot come to disagree about what
     * "printable" means.
     */
    private static final char FIRST_PRINTABLE_US_ASCII = 0x20;

    /**
     * Highest character this class will place inside a log record: the tilde.
     *
     * <p>The delete control sits immediately above it and is refused for the same reason as the C0
     * range below the space, as is every character that is not representable in a single US-ASCII byte.
     */
    private static final char LAST_PRINTABLE_US_ASCII = 0x7E;

    /**
     * The route values as compile-time string constants - the vocabulary that travels on the wire.
     *
     * <p>Every value is named for the destination's role and contains no mainframe token. The lower-case
     * hyphenated form is deliberately independent of the endpoint paths the API layer chooses: this layer
     * does not know those paths and must not encode them.
     */
    public static final class Routes {

        public static final String SIGN_ON = "sign-on";

        public static final String USER_MENU = "user-menu";

        public static final String ADMIN_MENU = "admin-menu";

        public static final String ACCOUNT_VIEW = "account-view";

        public static final String ACCOUNT_UPDATE = "account-update";

        public static final String CARD_LIST = "card-list";

        public static final String CARD_DETAIL = "card-detail";

        public static final String CARD_UPDATE = "card-update";

        public static final String TRANSACTION_LIST = "transaction-list";

        public static final String TRANSACTION_VIEW = "transaction-view";

        public static final String TRANSACTION_ADD = "transaction-add";

        public static final String REPORT_REQUEST = "report-request";

        public static final String BILL_PAYMENT = "bill-payment";

        public static final String USER_LIST = "user-list";

        public static final String USER_ADD = "user-add";

        public static final String USER_UPDATE = "user-update";

        public static final String USER_DELETE = "user-delete";

        /**
         * Not instantiable. This type is a holder of compile-time constants and has no behaviour and
         * no state, so an instance would be meaningless.
         */
        private Routes() {
            throw new AssertionError("Routes is a constant holder and must not be instantiated");
        }
    }

    /**
     * The typed route vocabulary - one constant per reachable destination, 17 in all.
     *
     * <p>The destinations correspond to the 17 dispatchable transaction definitions among the 18
     * registered in {@code app/csd/CARDDEMO.CSD}. Each constant carries four things: the wire value,
     * taken directly
     * from the matching field of {@link Routes} so the two can never diverge; the legacy transaction
     * identifier and legacy program name, held for traceability and never published as the route value
     * itself; and whether the destination is administrative.
     *
     * <p>Declaration order is the order a user traverses the application - sign-on, then the two
     * menus, then the account, card, transaction, reporting and payment destinations, then the
     * administrative user-maintenance destinations. The order ends there: the date-validation
     * subprogram is internal and has no constant here, for the reason the class documentation gives.
     * That order is what {@link #routes()} exposes.
     *
     * <p><strong>No constant exists for {@code COCRDSEC}</strong>, the dangling program definition
     * described in the class documentation, and none may be added.
     *
     * <p>This is a typed vocabulary and nothing more: it holds no route table, resolves nothing and
     * decides nothing. Selection is the enclosing service's responsibility.
     */
    public enum Route {

        /**
         * Sign-on: the entry point, and the destination of every sign-off and every entry carrying no
         * prior navigation state. Both menu programs nominate it on their sign-off path,
         * {@code app/cbl/COMEN01C.cbl:L173} and {@code app/cbl/COADM01C.cbl:L163}.
         */
        SIGN_ON(Routes.SIGN_ON, "CC00", "COSGN00C", false),

        /**
         * User main menu, carrying 10 options. The destination of the unconditional alternative in the
         * sign-on role split, {@code app/cbl/COSGN00C.cbl:L235}, and the verified back-navigation default
         * of the bill-payment program, {@code app/cbl/COBIL00C.cbl:L130}.
         */
        USER_MENU(Routes.USER_MENU, "CM00", "COMEN01C", false),

        /**
         * Administrative menu, carrying 4 options. Reached only when the administrator condition holds at
         * {@code app/cbl/COSGN00C.cbl:L230}.
         */
        ADMIN_MENU(Routes.ADMIN_MENU, "CA00", "COADM01C", true),

        ACCOUNT_VIEW(Routes.ACCOUNT_VIEW, "CAVW", "COACTVWC", false),

        ACCOUNT_UPDATE(Routes.ACCOUNT_UPDATE, "CAUP", "COACTUPC", false),

        CARD_LIST(Routes.CARD_LIST, "CCLI", "COCRDLIC", false),

        CARD_DETAIL(Routes.CARD_DETAIL, "CCDL", "COCRDSLC", false),

        CARD_UPDATE(Routes.CARD_UPDATE, "CCUP", "COCRDUPC", false),

        TRANSACTION_LIST(Routes.TRANSACTION_LIST, "CT00", "COTRN00C", false),

        TRANSACTION_VIEW(Routes.TRANSACTION_VIEW, "CT01", "COTRN01C", false),

        /**
         * Transaction add, and <strong>not</strong> administrative. The user menu's eighth option carries a
         * commented-out alternative label marking this destination administrator-only, and that label is
         * inactive in the shipped estate; the active label and the option's user-type code both make it
         * available to a standard user, so treating it as administrative would deny access the legacy
         * grants.
         */
        TRANSACTION_ADD(Routes.TRANSACTION_ADD, "CT02", "COTRN02C", false),

        /**
         * Report request &mdash; the only destination whose own processing crosses into the batch tier, by
         * way of the estate's single transient-data-queue write. That bridge belongs to the job-submission
         * service; nothing about it is navigation and nothing about it appears here.
         */
        REPORT_REQUEST(Routes.REPORT_REQUEST, "CR00", "CORPT00C", false),

        BILL_PAYMENT(Routes.BILL_PAYMENT, "CB00", "COBIL00C", false),

        USER_LIST(Routes.USER_LIST, "CU00", "COUSR00C", true),

        USER_ADD(Routes.USER_ADD, "CU01", "COUSR01C", true),

        USER_UPDATE(Routes.USER_UPDATE, "CU02", "COUSR02C", true),

        /** User delete. Legacy transaction {@code CU03}, legacy program {@code COUSR03C}. Administrative. */
        USER_DELETE(Routes.USER_DELETE, "CU03", "COUSR03C", true);

        private final String routeValue;

        private final String legacyTransactionId;

        private final String legacyProgramName;

        private final boolean adminScoped;

        Route(String routeValue, String legacyTransactionId, String legacyProgramName,
                boolean adminScoped) {
            this.routeValue = routeValue;
            this.legacyTransactionId = legacyTransactionId;
            this.legacyProgramName = legacyProgramName;
            this.adminScoped = adminScoped;
        }

        /**
         * @return the wire value a response body carries and a client echoes back; role-named and free of
         *         any legacy program name or transaction identifier
         */
        public String getRouteValue() {
            return routeValue;
        }

        /**
         * @return the four-character legacy transaction identifier, as registered in
         *         {@code app/csd/CARDDEMO.CSD}; traceability only, and never the wire value
         */
        public String getLegacyTransactionId() {
            return legacyTransactionId;
        }

        /**
         * @return the eight-character legacy program name; traceability only as a published value, but
         *         also the value the communication area carries in its originating- and
         *         destination-program fields, which is what lets the enclosing service resolve them
         */
        public String getLegacyProgramName() {
            return legacyProgramName;
        }

        /**
         * <strong>Reports</strong> scope and <strong>decides</strong> nothing; enforcement belongs to the
         * security configuration and the controller.
         *
         * @return {@code true} for the administrative menu and the four user-maintenance destinations
         */
        public boolean isAdminScoped() {
            return adminScoped;
        }
    }

    /**
     * Every destination in declaration order, built from the declared constants themselves rather than
     * from a second hand-written list, so it cannot fall out of step with them.
     */
    private static final List<Route> ALL_ROUTES = List.of(Route.values());

    /**
     * The five administrative destinations, derived by filtering the declared constants on their own
     * administrative flag, so adding one marked administrative extends this set automatically.
     */
    private static final Set<Route> ADMIN_SCOPED_ROUTES = indexAdminScoped();

    /**
     * Index from legacy program name to destination. This is what makes back-navigation possible at all:
     * the communication-area fields carry an eight-character program name, not a route.
     */
    private static final Map<String, Route> ROUTES_BY_LEGACY_PROGRAM = indexBy(Route::getLegacyProgramName);

    /** Index from legacy transaction identifier to destination, mirroring {@code app/csd/CARDDEMO.CSD}. */
    private static final Map<String, Route> ROUTES_BY_LEGACY_TRANSACTION =
            indexBy(Route::getLegacyTransactionId);

    /** Index from wire value to destination, used to read back a route a client echoed. */
    private static final Map<String, Route> ROUTES_BY_VALUE = indexBy(Route::getRouteValue);

    /**
     * Creates the navigation service. Explicit and empty because this class is the base of the service
     * layer and collaborates with nothing, so there is no dependency to receive; constructor injection
     * remains the convention for every service that does have collaborators.
     */
    public NavigationService() {
    }

    // ------------------------------------------------------------------------------------------
    // Rule: the sign-on role split
    // ------------------------------------------------------------------------------------------

    /**
     * Resolves the destination a completed sign-on leads to, from the signed-on user's type.
     *
     * <p><strong>Reproduces an asymmetry that must not be tidied up.</strong> The legacy program tests the
     * administrator condition at {@code app/cbl/COSGN00C.cbl:L230} and hands control to the administrative
     * menu when it holds. What follows at {@code L235} is an <strong>unconditional alternative</strong>: it
     * is <em>not</em> a second test of the standard-user condition, there is no third branch, and the value
     * reaching the test was moved straight out of the credential record at {@code L227} without inspection.
     *
     * <p>Two consequences are therefore contractual. Only the administrator type reaches the administrative
     * menu, and <em>every</em> other input &mdash; the standard user type, an absent type, or one outside
     * the two-value vocabulary the estate declares &mdash; reaches the user main menu. This method
     * consequently accepts {@code null} and <strong>never throws</strong>: rejecting an unrecognised type
     * would abort a sign-on the legacy program completes.
     */
    public Route resolveSignOnRoute(final UserType userType) {
        final Route route;
        if (userType != null && userType.isAdmin()) {
            route = Route.ADMIN_MENU;
        } else {
            // The unconditional alternative: the standard type, an absent type and any undeclared type all
            // arrive here, with no further test and no error path.
            route = Route.USER_MENU;
        }
        LOG.debug("Sign-on route resolved: rule=sign-on-role-split userType={} route={}",
                userType, route.getRouteValue());
        return route;
    }

    /**
     * Resolves the destination a completed sign-on leads to, from the raw one-character user-type code
     * &mdash; the whole legacy path in one call. The code is resolved to a type exactly as the credential
     * record's value is moved into the communication area at {@code app/cbl/COSGN00C.cbl:L227}, and the
     * resolved type then drives the split described on {@link #resolveSignOnRoute(UserType)}.
     *
     * <p>Resolution never throws and applies no case fold, so a code that is absent, blank, over-long,
     * lower-cased or simply undeclared resolves to nothing &mdash; and nothing, not being the administrator
     * type, reaches the user main menu. That is the unconditional alternative faithfully reproduced, and it
     * is why this method has no error path.
     */
    public Route resolveSignOnRouteForUserTypeCode(final String userTypeCode) {
        final Optional<UserType> resolved = UserType.fromCode(userTypeCode);
        return resolveSignOnRoute(resolved.orElse(null));
    }

    // ------------------------------------------------------------------------------------------
    // Rule: entry carrying no prior navigation state
    // ------------------------------------------------------------------------------------------

    /**
     * Reports whether an online turn carries no prior navigation state: the equivalent of the zero-length
     * communication-area test that opens every online program's main paragraph,
     * {@code app/cbl/COBIL00C.cbl:L107} and its sixteen peers. A turn is stateless when no context was
     * supplied and equally when the supplied context is wholly empty, since an empty context carries no
     * signed-on user, no selection and no previous screen &mdash; exactly what a zero-length communication
     * area describes.
     */
    public boolean isConversationStateAbsent(final ConversationState context) {
        return context == null || context.absent();
    }

    /**
     * Returns the destination an online turn carrying no prior navigation state leads to: sign-on,
     * unconditionally. From the zero-length branch at {@code app/cbl/COBIL00C.cbl:L107-L108}, which
     * nominates the sign-on program without consulting anything else; the administrative menu takes the
     * same branch at {@code app/cbl/COADM01C.cbl:L82-L84}.
     *
     * <p>Named and exposed rather than left to each caller so the rule is stated once. It admits no
     * override because the legacy branch takes none: there is no per-program default on this path, unlike
     * the back-navigation path.
     */
    public Route resolveAbsentContextRoute() {
        return Route.SIGN_ON;
    }

    // ------------------------------------------------------------------------------------------
    // Rule: return to the previous screen
    // ------------------------------------------------------------------------------------------

    /**
     * Reports whether an attention-key action is the one that returns to the previous screen: the third
     * program-function key alone, which all seventeen online programs treat that way,
     * {@code app/cbl/COBIL00C.cbl:L128}.
     *
     * <p>This class performs <strong>no</strong> attention-key decoding &mdash; turning a raw terminal
     * identifier into an action, including the fold of program-function keys 13 through 24 back onto 1
     * through 12, belongs to the utility-layer key translator. A {@code null} action means no key was
     * decoded, which is not the back-navigation key, so it answers {@code false} rather than throwing.
     */
    public boolean isBackNavigationKey(final KeyAction keyAction) {
        return keyAction == KeyAction.PFK03;
    }

    /**
     * Resolves the destination that returning to the previous screen leads to, given the navigation state
     * and the calling screen's own default.
     *
     * <p><strong>Both arms of the legacy rule are modelled.</strong> At
     * {@code app/cbl/COBIL00C.cbl:L129-L134} the program tests the <em>originating</em>-program field of the
     * communication area: when it holds spaces or low values it nominates the calling screen's own default,
     * and otherwise it nominates the destination that field names. The default is <strong>per screen and
     * not global</strong> &mdash; the bill-payment program's is the user main menu at {@code L130}, while
     * both menu programs default their sign-off path to sign-on &mdash; which is why the fallback is a
     * parameter here and is never assumed.
     *
     * <p>The blank test is the legacy test, not the conventional Java one: the field is fixed width, so a
     * value consisting entirely of spaces or of low values is blank, while one containing other white space
     * is not.
     *
     * <p><strong>An unresolvable nomination fails.</strong> When the field is present but names no
     * reachable destination, this raises {@link AbendException} rather than quietly applying the caller's
     * default. The legacy transfer-control statement would have attempted the transfer and abended on an
     * unresolvable program name, and that is the behaviour reproduced here.
     *
     * <p>An earlier revision applied the caller's default and logged a warning instead, reasoning that
     * the field is client-echoed and therefore untrusted, so honouring it blindly would let a client
     * provoke a server failure. The premise is right and the conclusion does not follow. Substituting a
     * different destination is not the safe response to untrusted input; it is a silent change of
     * outcome, which is worse than a failure because it is invisible. Untrusted input is handled where it
     * belongs - the name is bounded to the legacy field width before it reaches a diagnostic, exactly as a
     * move into {@code PIC X(8)} bounds it - and the outcome is left alone. A refused navigation is a
     * refusal, not a redirection.
     *
     * <p>In the shipped estate the field can only ever hold one of the seventeen online program names, so
     * a legitimate client never reaches this path.
     *
     * @param context       the navigation state echoed by the client; must not be {@code null}. Entry
     *                      carrying no state at all is a different rule - see
     *                      {@link #isConversationStateAbsent(ConversationState)} - and is resolved
     *                      before this one is reached
     * @param callerDefault the calling screen's own default destination, applied when the originating
     *                      program field nominates nothing usable; must not be {@code null}
     * @return the destination the originating-program field names, or {@code callerDefault} when that
     *         field is blank; never {@code null}
     * @throws NullPointerException if {@code context} or {@code callerDefault} is {@code null}
     * @throws AbendException       if the originating-program field names a destination that cannot be
     *                              resolved, reproducing the abend the legacy transfer would have raised
     */
    public Route resolveBackNavigation(final ConversationState context, final Route callerDefault) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(callerDefault, "callerDefault must not be null");
        return resolveNominatedProgram(context.fromProgram(), callerDefault, "back-navigation");
    }

    /**
     * Resolves the destination an attention key leads to, producing one only for the key that returns to the
     * previous screen. The navigation-bearing arm of the attention-key evaluation at
     * {@code app/cbl/COBIL00C.cbl:L125-L142}: only the third program-function key transfers control there,
     * while the enter key is processed by the screen itself and every other key produces an invalid-key
     * message and re-presents the same screen. Neither of those transfers control, so neither yields a
     * destination and both are reported as an empty result.
     *
     * <p>The message text accompanying an unmapped key belongs to the common message catalogue and is
     * emitted by the owning online service.
     */
    public Optional<Route> resolveAttentionKeyRoute(final KeyAction keyAction,
            final ConversationState context, final Route callerDefault) {
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
     * Resolves the destination already nominated in the navigation state, falling back to the caller's own
     * default when nothing is nominated.
     *
     * <p>A rule distinct from back-navigation, and distinct in the source too: back-navigation inspects the
     * <em>originating</em>-program field, while this inspects the <em>destination</em>-program field, which
     * a screen sets before handing off. The legacy shape is the sign-off paragraph of the two menu
     * programs, {@code app/cbl/COMEN01C.cbl:L170-L177} and {@code app/cbl/COADM01C.cbl:L160-L167}.
     *
     * <p>The fallback is a parameter for the same reason it is on back-navigation: the default is per
     * screen. The unresolvable-nomination failure documented on
     * {@link #resolveBackNavigation(ConversationState, Route)} applies identically here.
     *
     * @param context       the navigation state echoed by the client; must not be {@code null}
     * @param callerDefault the destination to use when nothing is nominated; must not be {@code null}
     * @return the nominated destination, or {@code callerDefault} when the destination-program field is
     *         blank; never {@code null}
     * @throws NullPointerException if {@code context} or {@code callerDefault} is {@code null}
     * @throws AbendException       if the destination-program field names a destination that cannot be
     *                              resolved, reproducing the abend the legacy transfer would have raised
     */
    public Route resolveNominatedDestination(final ConversationState context, final Route callerDefault) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(callerDefault, "callerDefault must not be null");
        return resolveNominatedProgram(context.toProgram(), callerDefault, "nominated-destination");
    }

    /**
     * Resolves the destination a sign-off leads to, defaulting to sign-on &mdash; the verified special case
     * of {@link #resolveNominatedDestination(ConversationState, Route)} for the two menu programs, each of
     * which nominates the sign-on program on its exit key and defaults to sign-on when nothing is
     * nominated, at {@code app/cbl/COMEN01C.cbl:L97, L173} and {@code app/cbl/COADM01C.cbl:L97, L163}.
     *
     * <p>This method fixes the fallback because the source fixes it for these two programs and for them
     * only. It is <strong>not</strong> a global default: any other screen must supply its own through
     * {@link #resolveNominatedDestination(ConversationState, Route)}.
     */
    public Route resolveSignOffRoute(final ConversationState context) {
        return resolveNominatedDestination(context, Route.SIGN_ON);
    }

    // ------------------------------------------------------------------------------------------
    // Rule: forward dispatch from a menu
    // ------------------------------------------------------------------------------------------

    /**
     * Reports whether a catalogued program name suppresses dispatch. The legacy guard compares the
     * <strong>first five characters</strong> of the name against the suppression literal and dispatches only
     * when they differ, at {@code app/cbl/COMEN01C.cbl:L146} and {@code app/cbl/COADM01C.cbl:L138}. When
     * they match, control never transfers and the program falls through to compose a "coming soon" message
     * instead &mdash; so a suppressed selection produces <strong>no route at all</strong>, which is why the
     * dispatch methods return an empty result rather than a destination.
     *
     * <p><strong>Documented unreachable, and deliberately preserved.</strong> No entry in either menu
     * catalogue names a program beginning with the literal, so the branch cannot fire in the shipped estate.
     * It is reproduced anyway, because deleting it would discard a documented behaviour of the source and
     * because the guard is what makes a catalogue entry safely extensible.
     *
     * <p>A blank or absent name is <em>not</em> suppressed, faithfully: it does not begin with the literal,
     * so the legacy guard would let dispatch proceed. Such a name simply names no reachable destination,
     * which the dispatch methods report as an empty result.
     */
    public boolean isDispatchSuppressed(final String catalogProgramName) {
        return catalogProgramName != null && catalogProgramName.startsWith(DUMMY_PROGRAM_PREFIX);
    }

    /**
     * Reports whether the user menu's administrator-only gate denies a selection. The gate at
     * {@code app/cbl/COMEN01C.cbl:L136-L143} fires when two conditions hold together: the signed-on type
     * satisfies the <em>standard-user</em> condition, and the selected catalogue entry's own user-type code
     * is the administrator code. When it fires an error is raised, the menu is re-presented and no control
     * transfer occurs, so no route is produced.
     *
     * <p><strong>The first condition is the standard-user condition, not "not an administrator".</strong>
     * That distinction is behavioural and is preserved: a type outside the declared two-value vocabulary
     * satisfies neither condition name, so it does <em>not</em> trip the gate even though it is equally not
     * an administrator. Widening the test would deny a selection the legacy permits.
     *
     * <p><strong>Documented unreachable, and deliberately preserved.</strong> All ten entries of the user
     * menu catalogue carry the standard-user code, so the second condition can never hold. The eighth entry
     * does carry a commented-out alternative label marking it administrator-only, and that label is inactive
     * &mdash; which is precisely why the gate exists and equally why it is dormant. The denial message text
     * belongs to the owning menu service.
     */
    public boolean isAdminOnlyOptionDenied(final UserType selectingUserType,
            final String optionUserTypeCode) {
        return selectingUserType == UserType.USER
                && ADMIN_ONLY_OPTION_USER_TYPE_CODE.equals(optionUserTypeCode);
    }

    /**
     * Resolves the destination a user-menu selection dispatches to, applying the legacy gates in source
     * order. <strong>The order is the contract:</strong> the administrator-only gate at
     * {@code app/cbl/COMEN01C.cbl:L136-L143} is evaluated first and short-circuits, then the suppression
     * guard at {@code L146}, and only then does control transfer at {@code L152-L155}. Either gate yields an
     * empty result, because in both cases the legacy program re-presents the menu instead of transferring
     * control.
     *
     * <p>The option's program name and user-type code are passed in as plain values rather than read from
     * the menu catalogue, because the catalogue is a configuration-layer component and this layer must not
     * depend on it. Both gates are documented-unreachable; see
     * {@link #isAdminOnlyOptionDenied(UserType, String)} and {@link #isDispatchSuppressed(String)}.
     */
    public Optional<Route> resolveMenuDispatch(final UserType selectingUserType,
            final String optionUserTypeCode, final String catalogProgramName) {
        if (isAdminOnlyOptionDenied(selectingUserType, optionUserTypeCode)) {
            // The option code is caller-supplied, so it is described rather than reproduced. The gate
            // that guards this branch admits only the administrator code, so the description is in
            // practice that one character - but the funnel is applied here too, because a value's
            // safety must not rest on a guard that a later change could widen.
            LOG.debug("Menu dispatch denied: rule=user-menu-admin-only-gate userType={} optionUserType={}",
                    selectingUserType, describeForLog(optionUserTypeCode));
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
     * <p>Access to the administrative menu itself is what restricts these destinations - never anything
     * this method does. That upstream restriction is delivered: the administrative menu is mapped at
     * {@code /api/admin/menu}, the user-maintenance operations are mapped beneath
     * {@code /api/admin/users}, and the security chain requires the administrator authority for every
     * request beneath {@code /api/admin/**}. A caller reaching this method through the published HTTP
     * surface has therefore crossed the legacy-equivalent menu gate already. Direct Java callers remain
     * responsible for satisfying that precondition; this method intentionally accepts no principal and
     * performs no second, potentially divergent authorization decision.
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
     * Resolves a legacy program name to its destination &mdash; the lookup the two nomination rules rest on,
     * because the communication-area fields they read carry an eight-character program name rather than a
     * route. It also serves menu dispatch, whose catalogue entries carry the same kind of value.
     *
     * <p>Matching tolerates the trailing padding of a fixed-width field, because the legacy transfer-control
     * command ignores trailing blanks, but applies no case fold and no other normalisation, because program
     * names are case-sensitive in the resource definition. The value inspected is never altered; only the
     * lookup key is. Never throws, and no name resolves to the dangling program definition.
     */
    public Optional<Route> routeForLegacyProgram(final String legacyProgramName) {
        return lookupFixedWidth(ROUTES_BY_LEGACY_PROGRAM, legacyProgramName);
    }

    /**
     * Resolves a legacy transaction identifier to its destination, mirroring the 17 dispatchable
     * transaction definitions among the 18 registered in {@code app/csd/CARDDEMO.CSD}. The
     * eighteenth, {@code CDV1}, resolves to nothing, because the program definition it is bound to
     * has no source member.
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
     * Resolves a wire value back to its destination, the inverse of {@link Route#getRouteValue()}. Matching
     * is <strong>exact</strong>: unlike the two legacy-identifier lookups, a wire value is not a fixed-width
     * field, so surrounding white space is not padding to be tolerated but a difference from the published
     * token. An unrecognised value yields an empty result rather than an error, so a malformed echo cannot
     * fail a request here.
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
     * @return an immutable list of all 17 destinations
     */
    public List<Route> routes() {
        return ALL_ROUTES;
    }

    /**
     * Returns the administrative destinations - the administrative menu and the four user-maintenance
     * destinations.
     *
     * <p>This <strong>reports</strong> scope; it enforces nothing. This class performs no access check
     * and holds no dependency on the configuration layer.
     *
     * <p>No consumer needs to turn this set into an authorization table. The delivered HTTP surfaces are
     * mapped beneath {@code /api/admin/**}, where the security chain enforces the administrator
     * authority independently of these logical route labels. A caller must therefore not treat
     * membership as the control itself; membership records the legacy scope, while the controller
     * mappings and security rule provide the runtime guarantee.
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
     * Bounds a client-echoed program name to the width the legacy communication-area field reserves.
     *
     * <p>{@code CDEMO-FROM-PROGRAM} and {@code CDEMO-TO-PROGRAM} are {@code PIC X(8)}, so on the mainframe
     * a longer value could not survive the move into either field - it would be truncated on the right.
     * A REST client echoes the field and is not bound by that width, so the truncation is applied here
     * instead, at the point the value first leaves this class. Doing so keeps an overlong name from
     * turning a navigation failure into a width-validation failure raised from inside the abend, which
     * would report the wrong problem.
     *
     * <p>This bounds the value a diagnostic carries. It does not change the outcome: a name that resolves
     * to no destination fails whether or not it was overlong.
     *
     * @param nominatedProgram the program name as echoed; never {@code null} by the time this is reached
     * @return the name, truncated on the right to the legacy field width if it exceeded it
     */
    private static String boundToLegacyProgramWidth(final String nominatedProgram) {
        if (nominatedProgram.length() <= AbendException.CULPRIT_LENGTH) {
            return nominatedProgram;
        }
        return nominatedProgram.substring(0, AbendException.CULPRIT_LENGTH);
    }

    /**
     * Applies the shared nomination rule: a blank field yields the fallback, a field naming a reachable
     * destination yields that destination, and a field naming an unreachable one fails.
     *
     * <p>Shared by back-navigation and by nominated-destination resolution because the two rules differ
     * only in which communication-area field they read. The rule name is passed in so that a diagnostic
     * identifies which of the two produced it.
     *
     * <p>The blank case is the legacy's own: a communication-area field holding spaces or low values
     * nominates nothing, and the calling screen's default applies. The unresolvable case is the legacy's
     * too, and it is a failure - a transfer-control statement naming a program the region cannot resolve
     * abends the task. Only the reachable case produces a destination.
     *
     * @param nominatedProgram the program name read from the communication area; may be {@code null}
     * @param fallback         the caller's own default; never {@code null} by the time this is reached
     * @param rule             the rule name, for diagnostics
     * @return the effective destination, never {@code null}
     * @throws AbendException if the field names a destination that cannot be resolved, reproducing the
     *                        abend an unresolvable legacy transfer would have raised
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
            // The legacy transfer would have been attempted and would have abended on a program name the
            // region cannot resolve. The name is bounded to the legacy field width first, because it is
            // client-echoed and a fixed-width move is what bounds it on the mainframe.
            //
            // The diagnostic describes the nomination rather than echoing it. This branch is the one
            // place in the class where an unrecognised, wholly caller-controlled string would otherwise
            // reach a log record, and a value such as "A\r\nFORGED" would end the record early and
            // present the remainder as a second, invented entry. The bounded culprit still travels on
            // the abend, where it is structured data on an exception rather than text in a log record.
            //
            // Routed through the shared online abend diagnostic rather than logged here and raised
            // separately. This site's own record was correct but was a third shape on a surface that should
            // have one: it named its fields differently from every other abend record, under a different
            // category, so a reader who searched the abend vocabulary would not find the abend. The rule
            // being applied travels as the operation, so nothing this record used to say is lost.
            //
            // The described-culprit form is used because the culprit here is the one abend culprit in the
            // estate that a caller chooses. The description travels into the record; the bounded value
            // travels on the exception. See docs/decision-log.md entry DL-312.
            final String culprit = boundToLegacyProgramWidth(nominatedProgram);
            throw AbendService.onlineAbendWithDescribedCulprit(culprit,
                    describeNomination(nominatedProgram),
                    UNRESOLVABLE_PROGRAM_REASON,
                    "NAVIGATION TRANSFER FAILED, RULE " + rule,
                    "XCTL RULE " + rule);
        }
        final Route route = nominated.get();
        // Reaching this line means the nomination matched an entry of the fixed destination table, so
        // the canonical name from that table is available and is logged in place of the client's text.
        // The two are equal up to the fixed-width padding the lookup tolerates, which is precisely why
        // the canonical one is the better field to publish: it is a value this class owns.
        LOG.debug("Nomination honoured: rule={} nominatedProgram={} route={}",
                rule, route.getLegacyProgramName(), route.getRouteValue());
        return route;
    }

    /**
     * Describes a nominated program name for a diagnostic without reproducing it.
     *
     * <p>Returns a fixed-shape description in every case, and caller-supplied text in none. That is
     * stricter than the printable-US-ASCII substitution this module applies to fragments it partly
     * authors, and the extra strictness is deliberate here. The nominated program is echoed to the
     * client in the navigation context and returned by it unaltered, so it is wholly attacker-chosen on
     * this path; and this class's log records are read as space-separated {@code key=value} pairs, which
     * an eight-character attacker-chosen value is long enough to imitate - {@code route=CA} fits inside
     * the legacy field width. Refusing control characters alone would close the record-splitting attack
     * and leave the field-forging one open, so no caller text is published at all.</p>
     *
     * <p>What is published is still enough to act on. The length distinguishes a truncated or padded
     * field from a misspelled one, and the position and code point of an offending character identify
     * exactly which byte to remove. An operator correlating this record with the request that produced
     * it can read the value itself there, where it is data rather than log syntax.</p>
     *
     * <p>It degrades rather than throwing. The caller is already on a fallback path, having decided to
     * apply the caller default; raising here would convert a handled navigation fallback into an
     * unhandled failure, which is a behavioural change and not a security improvement.</p>
     *
     * @param nominatedProgram the caller-supplied nomination, which may be {@code null}
     * @return a fixed-shape description that never contains caller-supplied text, and
     *         {@link #ABSENT_VALUE_SUBSTITUTE} when the nomination is {@code null}
     */
    private static String describeNomination(final String nominatedProgram) {
        if (nominatedProgram == null) {
            return ABSENT_VALUE_SUBSTITUTE;
        }
        final int offendingIndex = firstNonPrintableIndex(nominatedProgram);
        if (offendingIndex != NO_NON_PRINTABLE_INDEX) {
            return "<not printable US-ASCII: zero-based position " + offendingIndex
                    + " is code point " + (int) nominatedProgram.charAt(offendingIndex) + ">";
        }
        return "<unrecognised, length " + nominatedProgram.length() + ">";
    }

    /**
     * Renders a catalogue-supplied program name for a diagnostic, substituting a description when it is
     * not printable US-ASCII.
     *
     * <p>Deliberately less strict than {@link #describeNomination(String)}, because the value has a
     * different provenance. A catalogue program name comes from the server's own menu option catalogue,
     * not from the request, so publishing it discloses nothing a client chose and its legibility is
     * worth keeping: the suppression record in particular is most useful when it names the sentinel it
     * suppressed on, which is how an operator confirms that a menu entry is deliberately inactive
     * rather than misconfigured.</p>
     *
     * <p>The printable scan remains, because provenance is an argument about likelihood and not a
     * guarantee. The catalogue is configuration, configuration is edited, and a diagnostic that can be
     * made to split a log record is a forging primitive whatever supplied the text - the same reasoning
     * {@code FixedWidthFieldReader} applies to the field names its callers pass it.</p>
     *
     * @param catalogProgramName the catalogue-supplied program name, which may be {@code null}
     * @return the name unchanged when it is printable US-ASCII, {@link #ABSENT_VALUE_SUBSTITUTE}
     *         when it is {@code null}, and a description of the offending character otherwise
     */
    private static String renderCatalogName(final String catalogProgramName) {
        if (catalogProgramName == null) {
            return ABSENT_VALUE_SUBSTITUTE;
        }
        final int offendingIndex = firstNonPrintableIndex(catalogProgramName);
        if (offendingIndex != NO_NON_PRINTABLE_INDEX) {
            return "<not printable US-ASCII: zero-based position " + offendingIndex
                    + " is code point " + (int) catalogProgramName.charAt(offendingIndex) + ">";
        }
        return catalogProgramName;
    }

    /**
     * Applies the suppression guard and then the program-name lookup, shared by both menu dispatch
     * rules.
     *
     * <p>The catalogued name is supplied by the caller rather than read from the menu catalogue - the
     * catalogue lives in the configuration layer, which this layer must not depend on - so it is
     * screened through {@link #renderCatalogName(String)} before any diagnostic mentions it. Both the
     * suppression guard and the lookup receive the value unaltered.
     *
     * @param catalogProgramName the program name of the selected catalogue entry; may be {@code null}
     * @param rule               the rule name, for diagnostics
     * @return the destination to dispatch to, or an empty result
     */
    private Optional<Route> resolveCatalogDispatch(final String catalogProgramName, final String rule) {
        if (isDispatchSuppressed(catalogProgramName)) {
            LOG.debug("Menu dispatch suppressed: rule={} catalogProgramName={}",
                    rule, renderCatalogName(catalogProgramName));
            return Optional.empty();
        }
        final Optional<Route> target = routeForLegacyProgram(catalogProgramName);
        if (target.isEmpty()) {
            LOG.warn("Menu entry names no reachable destination: rule={} catalogProgramName={}",
                    rule, renderCatalogName(catalogProgramName));
        }
        return target;
    }

    /**
     * Reports whether a fixed-width field value is blank in the legacy sense: entirely spaces or entirely
     * low values. Deliberately narrower than the conventional Java blank test &mdash; the legacy comparison
     * is against those two figurative constants only, so a tab or a line feed is not blank even though a
     * general white-space test would call it so. An absent value and an empty one are both blank.
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

    /** Looks a fixed-width field value up in an index, tolerating the field's trailing padding. */
    private static Optional<Route> lookupFixedWidth(final Map<String, Route> index,
            final String rawValue) {
        if (rawValue == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(index.get(stripTrailingPadding(rawValue)));
    }

    /**
     * Removes the trailing space and low-value padding of a fixed-width field value. Only trailing padding
     * is removed, and only those two characters; leading characters, interior characters and letter case are
     * never altered, so a name differing other than by padding still fails to match, which is intended.
     */
    private static String stripTrailingPadding(final String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\u0000')) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * Builds an immutable index of every destination keyed by one of its own attributes. Built from the
     * declared constants themselves, so an index can never disagree with the vocabulary, and copied into a
     * genuinely immutable map rather than merely wrapped.
     *
     * <p>The iteration order of the returned map is unspecified, which is harmless because an index is only
     * ever probed by key; order-bearing exposure is {@link #routes()}. Every key is distinct, so no entry
     * can silently displace another.
     */
    private static Map<String, Route> indexBy(final Function<Route, String> keyExtractor) {
        final Map<String, Route> index = new LinkedHashMap<>();
        for (final Route route : Route.values()) {
            index.put(keyExtractor.apply(route), route);
        }
        return Map.copyOf(index);
    }

    /**
     * Renders a caller-supplied value for a diagnostic, substituting a description when the value is
     * not safe to reproduce.
     *
     * <p>The single funnel every caller-supplied value passes through before it reaches this class's
     * logger. Three outcomes, in this order:
     *
     * <ul>
     *   <li>an absent value renders as {@link #ABSENT_VALUE_SUBSTITUTE}, which no real value can be
     *       mistaken for;</li>
     *   <li>a value carrying any character outside printable US-ASCII renders as the zero-based
     *       position and code point of its <em>first</em> such character and nothing else, so the
     *       fault is located exactly without any part of the value being reproduced; and</li>
     *   <li>any other value renders verbatim, because every one of its characters is printable and it
     *       can therefore neither terminate a log record early nor forge a new one.</li>
     * </ul>
     *
     * <p><strong>This method degrades and never throws</strong>, which is the deliberate choice
     * between the two shapes the module's diagnostic rule admits. Its callers are already reporting
     * something else - that a nomination named no reachable destination, that dispatch was suppressed,
     * that a gate denied a selection - and throwing here would replace the diagnostic being composed
     * with an unrelated failure, and would additionally convert a client-echoed value into a server
     * error on a path whose whole documented purpose is that it must not fail. It returns a substitute
     * of the same shape instead: a string a message can concatenate.
     *
     * <p>Only the first offending character is reported. A second would say nothing the first does not
     * about whether the value is safe, and reporting every one would leak the value's shape by
     * enumeration.
     *
     * @param value the caller-supplied value; may be {@code null}
     * @return a rendering that is always printable US-ASCII when the value is, and a description of
     *         the first offending character otherwise; never {@code null}
     */
    private static String describeForLog(final String value) {
        if (value == null) {
            return ABSENT_VALUE_SUBSTITUTE;
        }
        final int offendingIndex = firstNonPrintableIndex(value);
        if (offendingIndex != NO_NON_PRINTABLE_INDEX) {
            return "(not printable US-ASCII: the character at zero-based position " + offendingIndex
                    + " is code point " + (int) value.charAt(offendingIndex) + ")";
        }
        return value;
    }

    /**
     * Returns the zero-based index of the first character of a value that is not printable US-ASCII.
     *
     * <p>Scans forward and stops at the first offender, so the index it reports is the lowest one. A
     * character is printable when its code point lies between
     * {@link #FIRST_PRINTABLE_US_ASCII} and {@link #LAST_PRINTABLE_US_ASCII}
     * inclusive; everything below that is a control character and everything above is either the
     * delete control character or outside single-byte US-ASCII entirely.
     *
     * <p>This is the only scan in the class. All three renderers delegate to it -
     * {@link #describeNomination(String)} for a client-echoed nomination,
     * {@link #renderCatalogName(String)} for a catalogue-supplied program name and
     * {@link #describeForLog(String)} for a menu option's user-type code - so the three differ only in
     * what they say about an offending value and never in which values offend. They report the same
     * two facts in three different delimiter styles, because the styles are what let a reader of a log
     * record tell a described nomination from a described catalogue entry without the record having to
     * name its own provenance.
     *
     * <p>Scanning by character rather than by code point is correct here and not an oversight: a
     * character outside the basic multilingual plane is encoded as a surrogate pair, and each
     * surrogate on its own already lies above the printable ceiling, so the first half of the pair is
     * reported and the value is described rather than reproduced - which is the required outcome.
     *
     * @param value the value to scan; never {@code null} by the time this is reached
     * @return the index of the first character outside printable US-ASCII, or
     *         {@link #NO_NON_PRINTABLE_INDEX} when there is none
     */
    private static int firstNonPrintableIndex(final String value) {
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character < FIRST_PRINTABLE_US_ASCII || character > LAST_PRINTABLE_US_ASCII) {
                return index;
            }
        }
        return NO_NON_PRINTABLE_INDEX;
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
