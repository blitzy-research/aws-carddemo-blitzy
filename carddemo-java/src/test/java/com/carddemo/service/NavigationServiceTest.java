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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import com.carddemo.api.dto.NavigationContext;
import com.carddemo.exception.AbendException;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.NavigationService.Route;
import com.carddemo.service.NavigationService.Routes;

/**
 * Pins the navigation contract: the route vocabulary and every rule that selects a destination.
 *
 * <p><strong>What this test guards.</strong> The legacy estate transfers control between programs with
 * 25 transfer-control commands and re-arms a pseudo-conversational turn with 19 return-with-transaction
 * commands, across 17 online programs. None of that survives the migration as server-side forwarding;
 * it becomes a route value returned in a response body that the client echoes back. This class is the
 * whole of that translation, so every rule it applies is an external contract, and a rule that silently
 * changed would reroute a user without any compilation or wiring failure to reveal it.</p>
 *
 * <p><strong>Why the route table is checked row by row against the resource definition.</strong> The
 * vocabulary is not an arbitrary set of names. It corresponds one-for-one to the 18 transaction
 * definitions registered in {@code app/csd/CARDDEMO.CSD}, and each constant additionally carries the
 * legacy transaction identifier and legacy program name that definition binds. The expected table in
 * {@link TheRouteVocabulary} was transcribed by hand from those 18 definitions, so it is an independent
 * oracle: were a constant given the wrong transaction identifier or the wrong program name, the lookups
 * that the nomination rules rest on would resolve a client-echoed program name to the wrong screen, and
 * only a comparison against the resource definition itself would catch it.</p>
 *
 * <p><strong>One of those 18 definitions deliberately produces no row at all.</strong> The resource
 * definition binds transaction {@code CDV1} to {@code PROGRAM(COCRDSEC)}, and no source member of that
 * name exists anywhere in the estate, so the transaction cannot have been dispatchable. The vocabulary
 * therefore creates no destination for it, which is why a table transcribed from 18 registrations
 * carries 17 rows. Substituting the date-validation subprogram for that row - which an earlier revision
 * did - would have invented a destination the estate does not have: that subprogram is bound to no
 * transaction, named by no transfer-control statement and by no menu catalogue, and is reached only by
 * static call from four sites, so it is an internal subprogram rather than a navigable screen. Both
 * halves of the decision are asserted: no constant names the dangling program or its transaction, and
 * the dangling name resolves to nothing.</p>
 *
 * <p><strong>Why several assertions read the log.</strong> Three of the rules under test have two
 * distinct paths that return the <em>same</em> value, so a test that inspected only return values could
 * not tell them apart and would pass whichever path ran. A nomination that is blank falls back to the
 * caller's default, and a nomination that names no reachable destination also falls back to the caller's
 * default - but the first is ordinary and the second is a client sending something unexpected, and the
 * service distinguishes them by severity. The same is true of the two menu gates, which both yield no
 * destination but for entirely different reasons and in a fixed order. The log is the only place that
 * difference is observable, so it is where those claims are asserted.</p>
 *
 * <p><strong>Why the caller default is never the route under test.</strong> Both nomination rules take
 * the calling screen's own default as a parameter and return it when the nomination yields nothing. If a
 * test supplied a default that happened to equal the destination the nomination names, the assertion
 * would hold whether the nomination was honoured or ignored, and would therefore prove nothing. Every
 * nomination test here passes {@link Route#CARD_LIST} as the default and nominates a program that maps
 * to something else, so honouring and falling back are always distinguishable.</p>
 *
 * <p><strong>Every expected value is hand-authored.</strong> No expectation is obtained by calling the
 * service and recording what it returned. The route table comes from the resource definition, the
 * catalogue program names come from the two menu copybooks, the census figures come from counting the
 * legacy commands, and the padding and blank-test behaviour comes from the fixed-width field semantics
 * the legacy fields carry. Where a value could only sensibly come from one place, it is stated as a
 * literal so that the oracle and the implementation cannot drift together.</p>
 *
 * <p><strong>How the evidence is obtained, and what that does and does not prove.</strong> These are
 * pure unit tests over a service with no collaborators and no state; the real service instance is used
 * rather than a double. They prove the rules and the vocabulary. They do not prove that any controller
 * calls these rules, nor in what order, because at this checkout no controller exists to call them -
 * the module ships one class in its API package. What a unit test can establish about the rules
 * themselves is established here in full; the wiring is a separate concern with no code yet to bind.</p>
 *
 * <p><strong>Provenance.</strong> Checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source statement is
 * reproduced here; the legacy behaviour is described and cited by member and line.</p>
 */
@DisplayName("NavigationService: the route vocabulary and every rule that selects a destination")
final class NavigationServiceTest {

    /**
     * The number of destinations the resource definition registers, restated as a literal.
     *
     * <p>Stated here rather than read from the service so that the service's own published figure can be
     * checked against it.
     *
     * <p>Seventeen, not the eighteen transaction definitions the resource definition registers: the
     * definition bound to the dangling program produces no reachable destination, as the class
     * documentation above sets out.
     */
    private static final int EXPECTED_ROUTE_COUNT = 17;

    /** The number of transfer-control commands in the legacy estate, counted from {@code app/cbl}. */
    private static final int EXPECTED_DISPATCH_SITE_COUNT = 25;

    /** The number of return-with-transaction commands in the legacy estate. */
    private static final int EXPECTED_REARM_SITE_COUNT = 19;

    /** The suppression literal the legacy guard compares the first five characters against. */
    private static final String EXPECTED_DUMMY_PREFIX = "DUMMY";

    /** The administrator code a catalogue entry carries to mark itself administrator-only. */
    private static final String EXPECTED_ADMIN_OPTION_CODE = "A";

    /**
     * The default supplied to every nomination test.
     *
     * <p>Chosen because no program name probed in this class maps to it, so a returned value of this
     * route always means "fell back" and never "honoured the nomination". See the class documentation.
     */
    private static final Route DISCRIMINATING_DEFAULT = Route.CARD_LIST;

    /**
     * The ten program names of the user menu catalogue, transcribed from {@code app/cpy/COMEN02Y.cpy}.
     */
    private static final List<String> USER_CATALOGUE_PROGRAMS = List.of(
            "COACTVWC", "COACTUPC", "COCRDLIC", "COCRDSLC", "COCRDUPC",
            "COTRN00C", "COTRN01C", "COTRN02C", "CORPT00C", "COBIL00C");

    /**
     * The four program names of the administrative menu catalogue, from {@code app/cpy/COADM02Y.cpy}.
     */
    private static final List<String> ADMIN_CATALOGUE_PROGRAMS = List.of(
            "COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C");

    /** The dangling program definition that has no source member and must resolve to no destination. */
    private static final String DANGLING_PROGRAM = "COCRDSEC";

    /**
     * The transaction identifier bound to the dangling program definition. Registered in the resource
     * definition, but not dispatchable, because the program it names does not exist.
     */
    private static final String DANGLING_TRANSACTION = "CDV1";

    /** The service under test. It has no collaborators, so the real instance is used throughout. */
    private NavigationService subject;

    /** Captures what the service logged, so paths that return the same value can be told apart. */
    private ListAppender<ILoggingEvent> logRecorder;

    /** The service's own logger, retained so the appender can be detached again. */
    private Logger serviceLogger;

    /**
     * Builds the service and attaches a log recorder at debug level.
     *
     * <p>Debug level is required because two of the three rules whose paths are distinguished only by
     * their diagnostics report the ordinary path at debug and the unexpected path at warning. Recording
     * only warnings would make the ordinary path invisible and the contrast unassertable.
     */
    @BeforeEach
    void setUp() {
        this.subject = new NavigationService();
        this.serviceLogger = (Logger) LoggerFactory.getLogger(NavigationService.class);
        this.logRecorder = new ListAppender<>();
        this.logRecorder.setContext(this.serviceLogger.getLoggerContext());
        this.logRecorder.start();
        this.serviceLogger.addAppender(this.logRecorder);
        this.serviceLogger.setLevel(Level.DEBUG);
    }

    /** Detaches the recorder so no test leaks an appender into another. */
    @AfterEach
    void tearDown() {
        this.serviceLogger.detachAppender(this.logRecorder);
        this.logRecorder.stop();
    }

    /**
     * Builds a navigation state carrying only the two program-name fields the rules read.
     *
     * <p>The record declares sixteen components and only two of them matter to this class, so they are
     * named here and every other component is absent. Supplying the canonical constructor directly, with
     * the two fields in their declared positions, keeps the intent visible at each call site.
     *
     * @param fromProgram the originating-program field back-navigation reads; may be {@code null}
     * @param toProgram   the destination-program field nomination reads; may be {@code null}
     * @return a navigation state with those two fields and nothing else
     */
    private static NavigationContext contextWith(final String fromProgram, final String toProgram) {
        return new NavigationContext(null, fromProgram, null, toProgram, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * Returns the formatted text of every diagnostic recorded at the given level.
     *
     * @param level the level to select
     * @return the formatted messages, in the order they were emitted
     */
    private List<String> diagnosticsAt(final Level level) {
        final List<String> messages = new ArrayList<>();
        for (final ILoggingEvent event : this.logRecorder.list) {
            if (event.getLevel() == level) {
                messages.add(event.getFormattedMessage());
            }
        }
        return messages;
    }

    // ------------------------------------------------------------------------------------------
    // The vocabulary itself
    // ------------------------------------------------------------------------------------------

    /** The 17 destinations, checked against the resource definition they are derived from. */
    @Nested
    @DisplayName("the route vocabulary mirrors the seventeen dispatchable transaction definitions")
    final class TheRouteVocabulary {

        /**
         * Checks one destination against the transaction definition it was transcribed from.
         *
         * <p>The table is the resource definition's own content, written out by hand. The wire value is
         * additionally checked to be role-named - carrying neither the legacy program name nor the
         * legacy transaction identifier - because a wire value is published to clients and leaking a
         * legacy identifier into it would make the migration's internals part of the external contract.
         *
         * @param constant       the enum constant name
         * @param routeValue     the wire value the destination must publish
         * @param transactionId  the legacy transaction identifier the definition registers
         * @param programName    the legacy program name the definition binds
         * @param administrative whether the destination is administrative
         */
        @ParameterizedTest(name = "{0} -> {1} (txn {2}, program {3})")
        @CsvSource({
            "SIGN_ON,          sign-on,          CC00, COSGN00C, false",
            "USER_MENU,        user-menu,        CM00, COMEN01C, false",
            "ADMIN_MENU,       admin-menu,       CA00, COADM01C, true",
            "ACCOUNT_VIEW,     account-view,     CAVW, COACTVWC, false",
            "ACCOUNT_UPDATE,   account-update,   CAUP, COACTUPC, false",
            "CARD_LIST,        card-list,        CCLI, COCRDLIC, false",
            "CARD_DETAIL,      card-detail,      CCDL, COCRDSLC, false",
            "CARD_UPDATE,      card-update,      CCUP, COCRDUPC, false",
            "TRANSACTION_LIST, transaction-list, CT00, COTRN00C, false",
            "TRANSACTION_VIEW, transaction-view, CT01, COTRN01C, false",
            "TRANSACTION_ADD,  transaction-add,  CT02, COTRN02C, false",
            "REPORT_REQUEST,   report-request,   CR00, CORPT00C, false",
            "BILL_PAYMENT,     bill-payment,     CB00, COBIL00C, false",
            "USER_LIST,        user-list,        CU00, COUSR00C, true",
            "USER_ADD,         user-add,         CU01, COUSR01C, true",
            "USER_UPDATE,      user-update,      CU02, COUSR02C, true",
            "USER_DELETE,      user-delete,      CU03, COUSR03C, true",
        })
        @DisplayName("carries the wire value, legacy identifiers and scope the resource definition gives it")
        void eachDestinationMatchesItsTransactionDefinition(final String constant, final String routeValue,
                final String transactionId, final String programName, final boolean administrative) {
            final Route route = Route.valueOf(constant);

            assertThat(route.getRouteValue())
                    .as("wire value of %s", constant)
                    .isEqualTo(routeValue);
            assertThat(route.getLegacyTransactionId())
                    .as("legacy transaction identifier of %s", constant)
                    .isEqualTo(transactionId);
            assertThat(route.getLegacyProgramName())
                    .as("legacy program name of %s", constant)
                    .isEqualTo(programName);
            assertThat(route.isAdminScoped())
                    .as("administrative scope of %s", constant)
                    .isEqualTo(administrative);

            assertThat(route.getRouteValue())
                    .as("the published wire value must not leak a legacy identifier")
                    .doesNotContain(transactionId)
                    .doesNotContain(programName);
        }

        /**
         * The table above is exhaustive: it names every constant, so no destination escapes checking.
         */
        @Test
        @DisplayName("the checked table covers every declared destination, so none escapes it")
        void theCheckedTableIsExhaustive() {
            assertThat(Route.values())
                    .as("the hand-written table must have one row per constant")
                    .hasSize(EXPECTED_ROUTE_COUNT);
        }

        /**
         * Declaration order is the traversal order the service publishes, and is itself the contract of
         * {@link NavigationService#routes()}.
         */
        @Test
        @DisplayName("declaration order is the traversal order: entry, menus, then the screens")
        void declarationOrderIsTheTraversalOrder() {
            assertThat(subject.routes())
                    .as("the published order must be the traversal order")
                    .containsExactly(
                            Route.SIGN_ON, Route.USER_MENU, Route.ADMIN_MENU,
                            Route.ACCOUNT_VIEW, Route.ACCOUNT_UPDATE,
                            Route.CARD_LIST, Route.CARD_DETAIL, Route.CARD_UPDATE,
                            Route.TRANSACTION_LIST, Route.TRANSACTION_VIEW, Route.TRANSACTION_ADD,
                            Route.REPORT_REQUEST, Route.BILL_PAYMENT,
                            Route.USER_LIST, Route.USER_ADD, Route.USER_UPDATE, Route.USER_DELETE);
        }

        /**
         * Every key the three indexes are built on must be unique, or one destination would silently
         * displace another and a lookup would return the wrong screen.
         */
        @Test
        @DisplayName("wire values, transaction identifiers and program names are each free of duplicates")
        void everyIndexKeyIsUnique() {
            final List<String> values = new ArrayList<>();
            final List<String> transactions = new ArrayList<>();
            final List<String> programs = new ArrayList<>();
            for (final Route route : Route.values()) {
                values.add(route.getRouteValue());
                transactions.add(route.getLegacyTransactionId());
                programs.add(route.getLegacyProgramName());
            }

            assertThat(values).as("wire values").doesNotHaveDuplicates();
            assertThat(transactions).as("legacy transaction identifiers").doesNotHaveDuplicates();
            assertThat(programs).as("legacy program names").doesNotHaveDuplicates();
        }

        /**
         * No destination exists for the dangling program definition, and none may be added.
         */
        @Test
        @DisplayName("no destination exists for the dangling program definition")
        void noDestinationExistsForTheDanglingProgramDefinition() {
            for (final Route route : Route.values()) {
                assertThat(route.getLegacyProgramName())
                        .as("no constant may name the dangling definition")
                        .isNotEqualTo(DANGLING_PROGRAM);
            }
            assertThat(subject.routeForLegacyProgram(DANGLING_PROGRAM))
                    .as("the dangling name must resolve to no destination")
                    .isEmpty();
            for (final Route route : Route.values()) {
                assertThat(route.getLegacyTransactionId())
                        .as("no constant may name the transaction bound to the dangling definition, "
                                + "because substituting some other program for it would invent a "
                                + "destination the estate does not have")
                        .isNotEqualTo(DANGLING_TRANSACTION);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Census figures and published constants
    // ------------------------------------------------------------------------------------------

    /** The figures the service publishes about the estate it was derived from. */
    @Nested
    @DisplayName("the published census figures match the legacy estate they were counted from")
    final class ThePublishedCensus {

        /** The route count the service publishes must equal what it actually exposes. */
        @Test
        @DisplayName("the published route count equals the number of destinations exposed")
        void theRouteCountMatchesTheVocabulary() {
            assertThat(NavigationService.ROUTE_COUNT).isEqualTo(EXPECTED_ROUTE_COUNT);
            assertThat(subject.routes()).hasSize(NavigationService.ROUTE_COUNT);
            assertThat(Route.values()).hasSize(NavigationService.ROUTE_COUNT);
        }

        /**
         * The two legacy site counts are traceability figures counted from the estate: 25 transfer-control
         * commands and 19 return-with-transaction commands, on non-comment lines across the programs.
         */
        @Test
        @DisplayName("the transfer-control and re-arm site counts are the counted legacy figures")
        void theLegacySiteCountsAreTheCountedFigures() {
            assertThat(NavigationService.LEGACY_DISPATCH_SITE_COUNT)
                    .as("transfer-control commands counted across app/cbl")
                    .isEqualTo(EXPECTED_DISPATCH_SITE_COUNT);
            assertThat(NavigationService.LEGACY_REARM_SITE_COUNT)
                    .as("return-with-transaction commands counted across app/cbl")
                    .isEqualTo(EXPECTED_REARM_SITE_COUNT);
        }

        /** The two literals the gates compare against are the legacy literals. */
        @Test
        @DisplayName("the suppression literal and the administrator option code are the legacy literals")
        void theGateLiteralsAreTheLegacyLiterals() {
            assertThat(NavigationService.DUMMY_PROGRAM_PREFIX).isEqualTo(EXPECTED_DUMMY_PREFIX);
            assertThat(NavigationService.DUMMY_PROGRAM_PREFIX)
                    .as("the legacy guard compares the first five characters")
                    .hasSize(5);
            assertThat(NavigationService.ADMIN_ONLY_OPTION_USER_TYPE_CODE)
                    .isEqualTo(EXPECTED_ADMIN_OPTION_CODE)
                    .isEqualTo(UserType.ADMIN.getCode());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Administrative scope
    // ------------------------------------------------------------------------------------------

    /** Which destinations are administrative, and the fact that reporting scope is not enforcing it. */
    @Nested
    @DisplayName("administrative scope covers the administrative menu and the four user-maintenance screens")
    final class TheAdministrativeScope {

        /** Exactly five destinations are administrative, and they are named. */
        @Test
        @DisplayName("exactly the five administrative destinations are in scope")
        void exactlyFiveDestinationsAreAdministrative() {
            assertThat(subject.adminScopedRoutes())
                    .as("the administrative destinations")
                    .containsExactlyInAnyOrder(Route.ADMIN_MENU, Route.USER_LIST,
                            Route.USER_ADD, Route.USER_UPDATE, Route.USER_DELETE);
        }

        /** The remaining thirteen are not administrative, checked as the complement rather than listed. */
        @Test
        @DisplayName("the remaining twelve destinations are not administrative")
        void theRemainingDestinationsAreNotAdministrative() {
            final Set<Route> scoped = subject.adminScopedRoutes();
            final List<Route> unscoped = new ArrayList<>();
            for (final Route route : Route.values()) {
                if (!scoped.contains(route)) {
                    unscoped.add(route);
                }
            }

            assertThat(unscoped)
                    .as("every destination outside the administrative set")
                    .hasSize(EXPECTED_ROUTE_COUNT - 5)
                    .allSatisfy(route -> assertThat(route.isAdminScoped())
                            .as("%s must not report administrative scope", route)
                            .isFalse());
        }

        /**
         * The reported set is derived from the constants' own flags, so the two can never disagree.
         */
        @Test
        @DisplayName("the reported set agrees with each destination's own scope flag")
        void theReportedSetAgreesWithEachOwnFlag() {
            final Set<Route> fromFlags = new LinkedHashSet<>();
            for (final Route route : Route.values()) {
                if (route.isAdminScoped()) {
                    fromFlags.add(route);
                }
            }

            assertThat(subject.adminScopedRoutes())
                    .as("the reported set must be exactly those flagged administrative")
                    .isEqualTo(fromFlags);
        }

        /**
         * Transaction add is deliberately not administrative. The user menu's eighth entry carries a
         * commented-out alternative label marking it administrator-only, and that label is inactive in
         * the shipped estate; treating the destination as administrative would deny access the legacy
         * grants.
         */
        @Test
        @DisplayName("transaction add is not administrative, because the label that says so is inactive")
        void transactionAddIsNotAdministrative() {
            assertThat(Route.TRANSACTION_ADD.isAdminScoped()).isFalse();
            assertThat(subject.adminScopedRoutes()).doesNotContain(Route.TRANSACTION_ADD);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Immutable exposure
    // ------------------------------------------------------------------------------------------

    /** Both published collections are genuinely immutable, not merely wrapped. */
    @Nested
    @DisplayName("the published collections cannot be disturbed by a caller")
    final class ImmutableExposure {

        /** The route list refuses addition. */
        @Test
        @DisplayName("the destination list refuses addition")
        void theDestinationListRefusesAddition() {
            final List<Route> routes = subject.routes();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("the vocabulary must not be extensible by a caller")
                    .isThrownBy(() -> routes.add(Route.SIGN_ON));
        }

        /** The route list refuses removal too, so immutability is not one-sided. */
        @Test
        @DisplayName("the destination list refuses removal")
        void theDestinationListRefusesRemoval() {
            final List<Route> routes = subject.routes();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> routes.remove(Route.SIGN_ON));
        }

        /** The administrative set refuses mutation. */
        @Test
        @DisplayName("the administrative set refuses mutation")
        void theAdministrativeSetRefusesMutation() {
            final Set<Route> scoped = subject.adminScopedRoutes();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> scoped.add(Route.SIGN_ON));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> scoped.remove(Route.ADMIN_MENU));
        }

        /**
         * Both are shared instances rather than fresh copies, which is what makes it safe for a caller to
         * hold them without copying.
         */
        @Test
        @DisplayName("both collections are shared instances a caller can hold without copying")
        void bothCollectionsAreSharedInstances() {
            assertThat(subject.routes()).isSameAs(subject.routes());
            assertThat(subject.adminScopedRoutes()).isSameAs(subject.adminScopedRoutes());
        }
    }

    // ------------------------------------------------------------------------------------------
    // The sign-on role split
    // ------------------------------------------------------------------------------------------

    /**
     * The asymmetric role split, including the unconditional alternative that must not be tidied into a
     * validating branch.
     */
    @Nested
    @DisplayName("sign-on sends only the administrator to the administrative menu, and everyone else to the user menu")
    final class TheSignOnRoleSplit {

        /** The administrator type is the only input that reaches the administrative menu. */
        @Test
        @DisplayName("the administrator type reaches the administrative menu")
        void theAdministratorReachesTheAdministrativeMenu() {
            assertThat(subject.resolveSignOnRoute(UserType.ADMIN)).isEqualTo(Route.ADMIN_MENU);
        }

        /** The standard type reaches the user menu. */
        @Test
        @DisplayName("the standard type reaches the user main menu")
        void theStandardTypeReachesTheUserMenu() {
            assertThat(subject.resolveSignOnRoute(UserType.USER)).isEqualTo(Route.USER_MENU);
        }

        /**
         * An absent type reaches the user menu rather than raising. The legacy branch at
         * {@code app/cbl/COSGN00C.cbl} line 235 is an unconditional alternative with no third arm and no
         * error path, so rejecting an unresolved type would abort a sign-on the legacy completes.
         */
        @Test
        @DisplayName("an absent type reaches the user menu rather than raising")
        void anAbsentTypeReachesTheUserMenu() {
            assertThat(subject.resolveSignOnRoute(null))
                    .as("the unconditional alternative admits an absent type")
                    .isEqualTo(Route.USER_MENU);
        }

        /**
         * Only the exact administrator code reaches the administrative menu; every other raw code, valid
         * or not, takes the unconditional alternative.
         *
         * @param code the raw one-character code
         */
        @ParameterizedTest(name = "code [{0}] reaches the user menu")
        @ValueSource(strings = {"U", "a", "u", "", " ", "  ", "AA", "A ", " A", "X", "0"})
        @DisplayName("every raw code but the administrator code reaches the user menu")
        void everyOtherRawCodeReachesTheUserMenu(final String code) {
            assertThat(subject.resolveSignOnRouteForUserTypeCode(code))
                    .as("code [%s] must take the unconditional alternative", code)
                    .isEqualTo(Route.USER_MENU);
        }

        /** The administrator code, exactly, reaches the administrative menu. */
        @Test
        @DisplayName("the administrator code reaches the administrative menu")
        void theAdministratorCodeReachesTheAdministrativeMenu() {
            assertThat(subject.resolveSignOnRouteForUserTypeCode("A")).isEqualTo(Route.ADMIN_MENU);
        }

        /**
         * No case fold is applied, so a lower-cased administrator character is not an administrator.
         * This is asserted separately from the bulk case above because it is the one that would silently
         * grant administrative access were a fold introduced.
         */
        @Test
        @DisplayName("a lower-cased administrator character is not an administrator")
        void aLowerCasedAdministratorCharacterIsNotAnAdministrator() {
            assertThat(subject.resolveSignOnRouteForUserTypeCode("a"))
                    .as("resolution must apply no case fold")
                    .isEqualTo(Route.USER_MENU)
                    .isNotEqualTo(Route.ADMIN_MENU);
        }

        /** An absent raw code resolves without raising. */
        @Test
        @DisplayName("an absent raw code resolves to the user menu without raising")
        void anAbsentRawCodeResolvesWithoutRaising() {
            assertThat(subject.resolveSignOnRouteForUserTypeCode(null)).isEqualTo(Route.USER_MENU);
        }

        /**
         * The split reports the rule it applied, so an operator can see which decision produced a route.
         */
        @Test
        @DisplayName("the split reports the rule it applied")
        void theSplitReportsTheRuleItApplied() {
            subject.resolveSignOnRoute(UserType.ADMIN);

            assertThat(diagnosticsAt(Level.DEBUG))
                    .as("the applied rule must be identifiable in the log")
                    .anyMatch(text -> text.contains("rule=sign-on-role-split")
                            && text.contains(Routes.ADMIN_MENU));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Entry carrying no prior state
    // ------------------------------------------------------------------------------------------

    /** The zero-length communication-area test that opens every online program. */
    @Nested
    @DisplayName("a turn carrying no prior navigation state goes to sign-on")
    final class EntryCarryingNoPriorState {

        /** No context at all is a stateless turn. */
        @Test
        @DisplayName("no context at all is a stateless turn")
        void noContextAtAllIsStateless() {
            assertThat(subject.isNavigationContextAbsent(null)).isTrue();
        }

        /** The wholly empty context is a stateless turn, matching a zero-length communication area. */
        @Test
        @DisplayName("the wholly empty context is a stateless turn")
        void theWhollyEmptyContextIsStateless() {
            assertThat(subject.isNavigationContextAbsent(NavigationContext.empty())).isTrue();
            assertThat(subject.isNavigationContextAbsent(contextWith(null, null)))
                    .as("an all-absent context built directly is equally stateless")
                    .isTrue();
        }

        /** A context carrying any one field is not stateless, so real state is never discarded. */
        @Test
        @DisplayName("a context carrying even one field is not stateless")
        void aContextCarryingOneFieldIsNotStateless() {
            assertThat(subject.isNavigationContextAbsent(contextWith("COSGN00C", null)))
                    .as("an originating program is state")
                    .isFalse();
            assertThat(subject.isNavigationContextAbsent(contextWith(null, "COSGN00C")))
                    .as("a nominated destination is state")
                    .isFalse();
        }

        /**
         * The destination is sign-on unconditionally, and the rule admits no per-screen override -
         * unlike the back-navigation rule, whose legacy branch does take one.
         */
        @Test
        @DisplayName("the destination is sign-on, unconditionally and with no override")
        void theDestinationIsSignOnUnconditionally() {
            assertThat(subject.resolveAbsentContextRoute()).isEqualTo(Route.SIGN_ON);
            assertThat(subject.resolveAbsentContextRoute())
                    .as("the rule is fixed, so repeated calls agree")
                    .isSameAs(subject.resolveAbsentContextRoute());
        }
    }

    // ------------------------------------------------------------------------------------------
    // The back-navigation key
    // ------------------------------------------------------------------------------------------

    /** Which decoded key means "return to the previous screen". */
    @Nested
    @DisplayName("only the third program-function key returns to the previous screen")
    final class TheBackNavigationKey {

        /** The third program-function key is the back-navigation key. */
        @Test
        @DisplayName("the third program-function key is the back-navigation key")
        void theThirdFunctionKeyIsTheBackNavigationKey() {
            assertThat(subject.isBackNavigationKey(KeyAction.PFK03)).isTrue();
        }

        /**
         * No other decoded action is, checked across the whole vocabulary so a newly added action cannot
         * quietly become a second back-navigation key.
         *
         * @param keyAction the decoded action under test
         */
        @ParameterizedTest(name = "{0} is not the back-navigation key")
        @EnumSource(KeyAction.class)
        @DisplayName("no other decoded action is the back-navigation key")
        void noOtherActionIsTheBackNavigationKey(final KeyAction keyAction) {
            if (keyAction == KeyAction.PFK03) {
                return;
            }
            assertThat(subject.isBackNavigationKey(keyAction))
                    .as("%s must not act as the back-navigation key", keyAction)
                    .isFalse();
        }

        /** An undecoded key answers false rather than raising. */
        @Test
        @DisplayName("an undecoded key answers false rather than raising")
        void anUndecodedKeyAnswersFalse() {
            assertThat(subject.isBackNavigationKey(null)).isFalse();
        }

        /**
         * Exactly one action in the vocabulary qualifies, stated as a count so the claim does not rest
         * on the enumeration above alone.
         */
        @Test
        @DisplayName("exactly one action in the whole vocabulary qualifies")
        void exactlyOneActionQualifies() {
            final long qualifying = Arrays.stream(KeyAction.values())
                    .filter(subject::isBackNavigationKey)
                    .count();

            assertThat(qualifying)
                    .as("only one decoded action may return to the previous screen")
                    .isEqualTo(1L);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Returning to the previous screen
    // ------------------------------------------------------------------------------------------

    /**
     * Back-navigation, which reads the originating-program field and falls back to the calling screen's
     * own default.
     */
    @Nested
    @DisplayName("back-navigation reads the originating program and falls back per screen")
    final class ReturningToThePreviousScreen {

        /**
         * A blank field nominates nothing, so the caller's own default applies. Blank in the legacy sense
         * is entirely spaces or entirely low values, and an absent or empty value names nothing either.
         *
         * @param field the originating-program field value
         */
        @ParameterizedTest(name = "blank field [{0}] falls back to the caller default")
        @ValueSource(strings = {"", " ", "   ", "        ", "\u0000", "\u0000\u0000", " \u0000 "})
        @DisplayName("a field of spaces or low values nominates nothing, so the caller default applies")
        void aBlankFieldFallsBackToTheCallerDefault(final String field) {
            assertThat(subject.resolveBackNavigation(contextWith(field, null), DISCRIMINATING_DEFAULT))
                    .as("a blank field must yield the caller default")
                    .isEqualTo(DISCRIMINATING_DEFAULT);
        }

        /** An absent field is blank too. */
        @Test
        @DisplayName("an absent field nominates nothing")
        void anAbsentFieldNominatesNothing() {
            assertThat(subject.resolveBackNavigation(contextWith(null, null), DISCRIMINATING_DEFAULT))
                    .isEqualTo(DISCRIMINATING_DEFAULT);
        }

        /**
         * A field naming a reachable destination is honoured, and the returned route is the one the name
         * maps to rather than the supplied default - which is what makes the assertion discriminating.
         *
         * @param field    the originating-program field value
         * @param expected the enum constant name the field must resolve to
         */
        @ParameterizedTest(name = "[{0}] is honoured as {1}")
        @CsvSource({
            "COMEN01C, USER_MENU",
            "COADM01C, ADMIN_MENU",
            "COACTVWC, ACCOUNT_VIEW",
            "COSGN00C, SIGN_ON",
            "COTRN00C, TRANSACTION_LIST",
        })
        @DisplayName("a field naming a reachable destination is honoured over the caller default")
        void aFieldNamingAReachableDestinationIsHonoured(final String field, final String expected) {
            final Route resolved =
                    subject.resolveBackNavigation(contextWith(field, null), DISCRIMINATING_DEFAULT);

            assertThat(resolved)
                    .as("[%s] must be honoured", field)
                    .isEqualTo(Route.valueOf(expected))
                    .isNotEqualTo(DISCRIMINATING_DEFAULT);
        }

        /**
         * Trailing padding is tolerated because the legacy field is fixed width and the transfer-control
         * command ignores it. Both padding characters count, including a mixture of the two.
         *
         * @param field the padded field value
         */
        @ParameterizedTest(name = "padded field [{0}] still resolves")
        @ValueSource(strings = {"COMEN01C ", "COMEN01C  ", "COMEN01C\u0000", "COMEN01C \u0000 "})
        @DisplayName("trailing space and low-value padding are tolerated, including a mixture")
        void trailingPaddingIsTolerated(final String field) {
            assertThat(subject.resolveBackNavigation(contextWith(field, null), DISCRIMINATING_DEFAULT))
                    .as("trailing padding must not defeat the lookup")
                    .isEqualTo(Route.USER_MENU);
        }

        /**
         * Only trailing padding is tolerated, and only those two characters. A leading space, a different
         * letter case and a tab are all differences from the name rather than padding, so each nominates
         * a destination that cannot be resolved - and an unresolvable nomination abends rather than
         * silently becoming the caller's default. That is what makes this assertion worth making: were
         * the caller default applied here, a client that mistyped the case of a program name would be
         * routed somewhere it did not ask for and nothing would say so.
         *
         * @param field a value that differs from a reachable name by more than trailing padding
         */
        @ParameterizedTest(name = "[{0}] names nothing reachable")
        @ValueSource(strings = {" COMEN01C", "  COMEN01C", "comen01c", "CoMeN01C",
            "COMEN01C\t", "\tCOMEN01C", "COMEN 01C", "COMEN01", "COMEN01CX"})
        @DisplayName("leading padding, a case difference and a tab are differences, not padding, so "
                + "each abends instead of resolving")
        void onlyTrailingPaddingIsTolerated(final String field) {
            assertThatExceptionOfType(AbendException.class)
                    .as("[%s] must not resolve to a destination", field)
                    .isThrownBy(() -> subject.resolveBackNavigation(contextWith(field, null),
                            DISCRIMINATING_DEFAULT))
                    .withMessageContaining("RULE back-navigation");
        }

        /**
         * A field naming no reachable destination fails, reproducing the legacy: a transfer-control
         * statement naming a program the region cannot resolve attempts the transfer and abends the
         * task.
         *
         * <p>The field is client-echoed and therefore untrusted, which is an argument for bounding the
         * value before it reaches a diagnostic and not an argument for substituting a different
         * destination. Substitution is a silent change of outcome, and a silent change of outcome is
         * worse than a failure because nothing observes it. The untrusted half is handled where it
         * belongs, and the assertions below check both halves: the abend happens, and the diagnostic it
         * produces does not carry the echoed text.</p>
         */
        @Test
        @DisplayName("an unreachable name abends rather than applying the caller default")
        void anUnreachableNameAbends() {
            assertThatExceptionOfType(AbendException.class)
                    .as("a refused navigation is a refusal, not a redirection")
                    .isThrownBy(() -> subject.resolveBackNavigation(contextWith("NOSUCH00", null),
                            DISCRIMINATING_DEFAULT))
                    .withMessageContaining("RULE back-navigation");
        }

        /** The dangling program definition names no reachable destination either, so it too abends. */
        @Test
        @DisplayName("the dangling program definition names no reachable destination, so it abends")
        void theDanglingDefinitionNamesNothingReachable() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.resolveBackNavigation(
                            contextWith(DANGLING_PROGRAM, null), DISCRIMINATING_DEFAULT))
                    .withMessageContaining("RULE back-navigation");
        }

        /**
         * The two paths are not the same event and are not reported alike. A blank field is ordinary: it
         * nominates nothing, the caller's own default applies, and that is reported at debug. A field
         * naming no reachable destination is a failure: it abends, and that is reported at error.
         *
         * <p>The error record describes the nomination rather than echoing it. This is the one branch in
         * the class where an unrecognised, wholly caller-controlled string would otherwise reach a log
         * record, and a value carrying a carriage return and a line feed would end the record early and
         * present its remainder as a second, invented entry. The description is asserted here, and the
         * absence of the echoed text with it, because a diagnostic that can be made to split a log
         * record is a forging primitive. The value itself still travels on the abend, where it is
         * structured data on an exception rather than text a reader parses by line.</p>
         */
        @Test
        @DisplayName("a blank field is reported as ordinary and an unreachable name as a failure that "
                + "does not echo what it was given")
        void theTwoPathsAreReportedDifferently() {
            subject.resolveBackNavigation(contextWith("   ", null), DISCRIMINATING_DEFAULT);

            assertThat(diagnosticsAt(Level.ERROR))
                    .as("an ordinary blank field must not be reported as a failure")
                    .isEmpty();
            assertThat(diagnosticsAt(Level.DEBUG))
                    .as("the blank field must be reported as ordinary, naming the rule")
                    .anyMatch(text -> text.contains("rule=back-navigation"));

            this.clearLog();
            assertThatExceptionOfType(AbendException.class).isThrownBy(() ->
                    subject.resolveBackNavigation(contextWith("NOSUCH00", null),
                            DISCRIMINATING_DEFAULT));

            assertThat(diagnosticsAt(Level.ERROR))
                    .as("the failure must be reported once, naming the rule and describing rather than "
                            + "repeating the nomination")
                    .hasSize(1)
                    .allSatisfy(text -> assertThat(text)
                            .contains("rule=back-navigation")
                            .contains("<unrecognised, length 8>")
                            .doesNotContain("NOSUCH00"));
        }

        /**
         * A tab is not blank in the legacy sense even though a conventional white-space test would call
         * it so. The field is fixed width, and what is blank in a fixed-width field is spaces or low
         * values; every other character is content. The distinction is therefore not a matter of
         * severity but of outcome: a blank field applies the caller's default, while a tab nominates an
         * unresolvable destination and abends.
         *
         * <p>The diagnostic for it describes the character by code point rather than writing it, because
         * a control character written into a log record is exactly the class of value that can reshape
         * one.</p>
         */
        @Test
        @DisplayName("a tab is not blank, so it abends rather than applying the caller default")
        void aTabIsNotBlankInTheLegacySense() {
            assertThatExceptionOfType(AbendException.class)
                    .as("a tab must not be treated as blank")
                    .isThrownBy(() -> subject.resolveBackNavigation(contextWith("\t", null),
                            DISCRIMINATING_DEFAULT));

            assertThat(diagnosticsAt(Level.ERROR))
                    .hasSize(1)
                    .allSatisfy(text -> assertThat(text)
                            .contains("not printable US-ASCII")
                            .contains("code point 9")
                            .doesNotContain("\t"));
        }

        /** Both arguments are required, because neither has a defensible default at this layer. */
        @Test
        @DisplayName("the context and the caller default are both required")
        void bothArgumentsAreRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.resolveBackNavigation(null, DISCRIMINATING_DEFAULT))
                    .withMessage("context must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.resolveBackNavigation(contextWith(null, null), null))
                    .withMessage("callerDefault must not be null");
        }

        /**
         * The default is per screen and never global, so the same blank field yields whatever the
         * calling screen supplied.
         */
        @Test
        @DisplayName("the fallback is the calling screen's own, not a global default")
        void theFallbackIsPerScreen() {
            final NavigationContext blank = contextWith(null, null);

            assertThat(subject.resolveBackNavigation(blank, Route.USER_MENU)).isEqualTo(Route.USER_MENU);
            assertThat(subject.resolveBackNavigation(blank, Route.CARD_LIST)).isEqualTo(Route.CARD_LIST);
            assertThat(subject.resolveBackNavigation(blank, Route.SIGN_ON)).isEqualTo(Route.SIGN_ON);
        }

        /** Empties the recorder so a second act in the same test starts from a known state. */
        private void clearLog() {
            logRecorder.list.clear();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Attention-key dispatch
    // ------------------------------------------------------------------------------------------

    /** Which keys produce a destination at all. */
    @Nested
    @DisplayName("only the back-navigation key produces a destination from an attention key")
    final class AttentionKeyDispatch {

        /** The back-navigation key produces the back-navigation destination. */
        @Test
        @DisplayName("the back-navigation key produces the back-navigation destination")
        void theBackNavigationKeyProducesADestination() {
            final Optional<Route> resolved = subject.resolveAttentionKeyRoute(KeyAction.PFK03,
                    contextWith("COADM01C", null), DISCRIMINATING_DEFAULT);

            assertThat(resolved)
                    .as("the nominated destination must be produced, not the caller default")
                    .contains(Route.ADMIN_MENU);
        }

        /** With a blank nomination it produces the caller's default rather than nothing. */
        @Test
        @DisplayName("with a blank nomination it produces the caller default rather than nothing")
        void withABlankNominationItProducesTheCallerDefault() {
            assertThat(subject.resolveAttentionKeyRoute(KeyAction.PFK03,
                    contextWith(null, null), DISCRIMINATING_DEFAULT))
                    .contains(DISCRIMINATING_DEFAULT);
        }

        /**
         * Every other decoded action produces no destination, because neither the enter key nor an
         * unmapped key transfers control in the legacy evaluation.
         *
         * @param keyAction the decoded action under test
         */
        @ParameterizedTest(name = "{0} produces no destination")
        @EnumSource(KeyAction.class)
        @DisplayName("every other decoded action produces no destination")
        void everyOtherActionProducesNoDestination(final KeyAction keyAction) {
            if (keyAction == KeyAction.PFK03) {
                return;
            }
            assertThat(subject.resolveAttentionKeyRoute(keyAction,
                    contextWith("COADM01C", null), DISCRIMINATING_DEFAULT))
                    .as("%s must not transfer control", keyAction)
                    .isEmpty();
        }

        /** An undecoded key produces no destination rather than raising. */
        @Test
        @DisplayName("an undecoded key produces no destination rather than raising")
        void anUndecodedKeyProducesNoDestination() {
            assertThat(subject.resolveAttentionKeyRoute(null,
                    contextWith("COADM01C", null), DISCRIMINATING_DEFAULT))
                    .isEmpty();
        }

        /** The no-destination outcome is reported, naming the rule and the key. */
        @Test
        @DisplayName("the no-destination outcome is reported, naming the rule")
        void theNoDestinationOutcomeIsReported() {
            subject.resolveAttentionKeyRoute(KeyAction.ENTER,
                    contextWith("COADM01C", null), DISCRIMINATING_DEFAULT);

            assertThat(diagnosticsAt(Level.DEBUG))
                    .anyMatch(text -> text.contains("rule=attention-key-dispatch")
                            && text.contains("ENTER"));
        }

        /**
         * The state and the default are validated even for a key that will produce nothing, so a caller
         * cannot get away with omitting them on one path and not another.
         */
        @Test
        @DisplayName("the context and default are required even for a key that produces nothing")
        void theArgumentsAreRequiredEvenForANonNavigatingKey() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.resolveAttentionKeyRoute(KeyAction.ENTER, null,
                            DISCRIMINATING_DEFAULT))
                    .withMessage("context must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.resolveAttentionKeyRoute(KeyAction.ENTER,
                            contextWith(null, null), null))
                    .withMessage("callerDefault must not be null");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Returning to a nominated destination, and sign-off
    // ------------------------------------------------------------------------------------------

    /** Nomination, which reads the destination-program field, and the sign-off special case. */
    @Nested
    @DisplayName("nomination reads the destination program, and sign-off fixes its own fallback")
    final class ReturningToANominatedDestination {

        /**
         * The decisive difference between the two nomination rules: they read different fields. A state
         * carrying both, each naming a different destination, resolves differently under each rule -
         * which no single-field test could establish.
         */
        @Test
        @DisplayName("the two nomination rules read different fields of the same state")
        void theTwoRulesReadDifferentFields() {
            final NavigationContext both = contextWith("COADM01C", "COTRN00C");

            assertThat(subject.resolveBackNavigation(both, DISCRIMINATING_DEFAULT))
                    .as("back-navigation must read the originating program")
                    .isEqualTo(Route.ADMIN_MENU);
            assertThat(subject.resolveNominatedDestination(both, DISCRIMINATING_DEFAULT))
                    .as("nomination must read the destination program")
                    .isEqualTo(Route.TRANSACTION_LIST);
        }

        /** A blank destination field yields the caller's default. */
        @ParameterizedTest(name = "blank destination [{0}] falls back")
        @ValueSource(strings = {"", " ", "   ", "\u0000"})
        @DisplayName("a blank destination field yields the caller default")
        void aBlankDestinationFieldFallsBack(final String field) {
            assertThat(subject.resolveNominatedDestination(contextWith(null, field),
                    DISCRIMINATING_DEFAULT))
                    .isEqualTo(DISCRIMINATING_DEFAULT);
        }

        /** An absent destination field yields the caller's default. */
        @Test
        @DisplayName("an absent destination field yields the caller default")
        void anAbsentDestinationFieldFallsBack() {
            assertThat(subject.resolveNominatedDestination(contextWith(null, null),
                    DISCRIMINATING_DEFAULT))
                    .isEqualTo(DISCRIMINATING_DEFAULT);
        }

        /**
         * A named destination is honoured, with the same padding tolerance the other rule has.
         *
         * @param field    the destination-program field value
         * @param expected the enum constant name it must resolve to
         */
        @ParameterizedTest(name = "[{0}] is honoured as {1}")
        @CsvSource({
            "COADM01C, ADMIN_MENU",
            "COSGN00C, SIGN_ON",
            "COUSR00C, USER_LIST",
            "'COADM01C  ', ADMIN_MENU",
        })
        @DisplayName("a named destination is honoured, tolerating trailing padding")
        void aNamedDestinationIsHonoured(final String field, final String expected) {
            assertThat(subject.resolveNominatedDestination(contextWith(null, field),
                    DISCRIMINATING_DEFAULT))
                    .as("[%s] must be honoured", field)
                    .isEqualTo(Route.valueOf(expected))
                    .isNotEqualTo(DISCRIMINATING_DEFAULT);
        }

        /**
         * An unreachable name abends, and the failure identifies this rule rather than back-navigation.
         * The two rules share one resolver and differ only in which communication-area field they read,
         * so a diagnostic that did not name the rule would leave a reader unable to tell which field
         * carried the offending value.
         */
        @Test
        @DisplayName("an unreachable name abends and names its own rule")
        void anUnreachableNameAbendsUnderItsOwnRule() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.resolveNominatedDestination(
                            contextWith(null, "NOSUCH00"), DISCRIMINATING_DEFAULT))
                    .withMessageContaining("RULE nominated-destination")
                    .withMessageNotContaining("RULE back-navigation");
            assertThat(diagnosticsAt(Level.ERROR))
                    .as("the failure must identify the nomination rule, not back-navigation")
                    .hasSize(1)
                    .allSatisfy(text -> assertThat(text)
                            .contains("rule=nominated-destination")
                            .doesNotContain("NOSUCH00"));
        }

        /** Both arguments are required. */
        @Test
        @DisplayName("the context and the caller default are both required")
        void bothArgumentsAreRequired() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.resolveNominatedDestination(null, DISCRIMINATING_DEFAULT))
                    .withMessage("context must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.resolveNominatedDestination(contextWith(null, null), null))
                    .withMessage("callerDefault must not be null");
        }

        /**
         * Sign-off fixes its fallback to sign-on, because the source fixes it for the two menu programs
         * and for them only.
         */
        @Test
        @DisplayName("sign-off falls back to sign-on when nothing is nominated")
        void signOffFallsBackToSignOn() {
            assertThat(subject.resolveSignOffRoute(contextWith(null, null))).isEqualTo(Route.SIGN_ON);
            assertThat(subject.resolveSignOffRoute(contextWith(null, "   "))).isEqualTo(Route.SIGN_ON);
        }

        /**
         * Sign-off still honours a nomination, so the fixed fallback is a default and not an override.
         */
        @Test
        @DisplayName("sign-off still honours a nomination, so the fixed fallback is only a default")
        void signOffStillHonoursANomination() {
            assertThat(subject.resolveSignOffRoute(contextWith(null, "COTRN00C")))
                    .as("the fixed fallback must not override a real nomination")
                    .isEqualTo(Route.TRANSACTION_LIST);
        }

        /** Sign-off requires the state, since it has no other source for the field it reads. */
        @Test
        @DisplayName("sign-off requires the navigation state")
        void signOffRequiresTheNavigationState() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.resolveSignOffRoute(null))
                    .withMessage("context must not be null");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Forward dispatch from a menu
    // ------------------------------------------------------------------------------------------

    /** The two menu gates, their order, and the dispatch they guard. */
    @Nested
    @DisplayName("menu dispatch applies the administrator gate, then suppression, then transfers")
    final class ForwardDispatchFromAMenu {

        /**
         * The suppression guard compares the first five characters, so a longer name beginning with the
         * literal is suppressed and a shorter or differently-cased one is not.
         *
         * @param name       the catalogue program name
         * @param suppressed whether dispatch must be suppressed
         */
        @ParameterizedTest(name = "[{0}] suppressed={1}")
        @CsvSource({
            "DUMMY,      true",
            "DUMMY001,   true",
            "DUMMYPGM,   true",
            "'DUMMY   ', true",
            "DUMM,       false",
            "dummy,      false",
            "' DUMMY',   false",
            "DUMY,       false",
            "'',         false",
        })
        @DisplayName("suppression tests the first five characters, exactly and case sensitively")
        void suppressionTestsTheFirstFiveCharacters(final String name, final boolean suppressed) {
            assertThat(subject.isDispatchSuppressed(name))
                    .as("[%s]", name)
                    .isEqualTo(suppressed);
        }

        /**
         * An absent name is not suppressed, faithfully: it does not begin with the literal, so the legacy
         * guard would let dispatch proceed. It simply names nothing reachable.
         */
        @Test
        @DisplayName("an absent name is not suppressed, it merely names nothing")
        void anAbsentNameIsNotSuppressed() {
            assertThat(subject.isDispatchSuppressed(null))
                    .as("suppression is a name test, not a presence test")
                    .isFalse();
            assertThat(subject.resolveMenuDispatch(UserType.USER, "U", null))
                    .as("but it still yields no destination")
                    .isEmpty();
        }

        /**
         * The administrator gate fires only when both its conditions hold together, and the first of them
         * is the standard-user condition rather than "not an administrator". A type outside the declared
         * vocabulary satisfies neither condition name, so it does not trip the gate.
         *
         * @param userType   the signing-on type, or {@code null} for an unresolved one
         * @param optionCode the catalogue entry's own user-type code
         * @param denied     whether the gate must fire
         */
        @ParameterizedTest(name = "type={0} optionCode={1} denied={2}")
        @CsvSource({
            "USER,  A, true",
            "USER,  U, false",
            "ADMIN, A, false",
            "ADMIN, U, false",
            "USER,  a, false",
            "USER,  '', false",
        })
        @DisplayName("the administrator gate fires only for a standard user selecting an administrator entry")
        void theAdministratorGateFiresOnlyForBothConditions(final String userType,
                final String optionCode, final boolean denied) {
            assertThat(subject.isAdminOnlyOptionDenied(UserType.valueOf(userType), optionCode))
                    .as("type=%s optionCode=%s", userType, optionCode)
                    .isEqualTo(denied);
        }

        /**
         * An unresolved type does not trip the gate even though it is equally not an administrator.
         * Widening the first condition to "not an administrator" would deny a selection the legacy
         * permits, so the narrower condition is the contract.
         */
        @Test
        @DisplayName("an unresolved type does not trip the gate, though it is not an administrator either")
        void anUnresolvedTypeDoesNotTripTheGate() {
            assertThat(subject.isAdminOnlyOptionDenied(null, EXPECTED_ADMIN_OPTION_CODE))
                    .as("the first condition is the standard-user condition, not its negation")
                    .isFalse();
        }

        /** An absent option code does not trip the gate. */
        @Test
        @DisplayName("an absent option code does not trip the gate")
        void anAbsentOptionCodeDoesNotTripTheGate() {
            assertThat(subject.isAdminOnlyOptionDenied(UserType.USER, null)).isFalse();
        }

        /**
         * Every one of the ten user catalogue entries dispatches to a destination for a standard user.
         * The entries all carry the standard-user code in the shipped catalogue, which is exactly why the
         * administrator gate is dormant.
         *
         * @param program the catalogue program name
         */
        @ParameterizedTest(name = "user catalogue entry [{0}] dispatches")
        @ValueSource(strings = {"COACTVWC", "COACTUPC", "COCRDLIC", "COCRDSLC", "COCRDUPC",
            "COTRN00C", "COTRN01C", "COTRN02C", "CORPT00C", "COBIL00C"})
        @DisplayName("every user catalogue entry dispatches for a standard user")
        void everyUserCatalogueEntryDispatches(final String program) {
            assertThat(subject.resolveMenuDispatch(UserType.USER, "U", program))
                    .as("catalogue entry [%s] must reach a destination", program)
                    .isPresent()
                    .hasValueSatisfying(route -> assertThat(route.getLegacyProgramName())
                            .isEqualTo(program));
        }

        /** The catalogue transcribed here is the shipped one: ten user entries and four administrative. */
        @Test
        @DisplayName("the transcribed catalogues carry ten user entries and four administrative ones")
        void theTranscribedCataloguesAreTheShippedSizes() {
            assertThat(USER_CATALOGUE_PROGRAMS).as("user menu catalogue").hasSize(10);
            assertThat(ADMIN_CATALOGUE_PROGRAMS).as("administrative menu catalogue").hasSize(4);
            assertThat(USER_CATALOGUE_PROGRAMS)
                    .as("the two catalogues must not overlap")
                    .doesNotContainAnyElementsOf(ADMIN_CATALOGUE_PROGRAMS);
        }

        /**
         * Every administrative catalogue entry dispatches, and each reaches an administrative
         * destination.
         *
         * @param program the catalogue program name
         */
        @ParameterizedTest(name = "administrative catalogue entry [{0}] dispatches")
        @ValueSource(strings = {"COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C"})
        @DisplayName("every administrative catalogue entry dispatches to an administrative destination")
        void everyAdministrativeCatalogueEntryDispatches(final String program) {
            assertThat(subject.resolveAdminMenuDispatch(program))
                    .as("catalogue entry [%s]", program)
                    .isPresent()
                    .hasValueSatisfying(route -> {
                        assertThat(route.getLegacyProgramName()).isEqualTo(program);
                        assertThat(route.isAdminScoped())
                                .as("%s must be administrative", route)
                                .isTrue();
                    });
        }

        /** A suppressed selection yields no destination at all, not a destination to be ignored. */
        @Test
        @DisplayName("a suppressed selection yields no destination at all")
        void aSuppressedSelectionYieldsNoDestination() {
            assertThat(subject.resolveMenuDispatch(UserType.USER, "U", "DUMMY")).isEmpty();
            assertThat(subject.resolveAdminMenuDispatch("DUMMY")).isEmpty();
        }

        /** A denied selection yields no destination. */
        @Test
        @DisplayName("a selection the administrator gate denies yields no destination")
        void aDeniedSelectionYieldsNoDestination() {
            assertThat(subject.resolveMenuDispatch(UserType.USER, EXPECTED_ADMIN_OPTION_CODE,
                    "COACTVWC"))
                    .as("the gate must deny even a perfectly reachable name")
                    .isEmpty();
        }

        /** An entry naming nothing reachable yields no destination, and is reported as a warning. */
        @Test
        @DisplayName("an entry naming nothing reachable yields no destination and warns")
        void anEntryNamingNothingReachableWarns() {
            assertThat(subject.resolveMenuDispatch(UserType.USER, "U", "NOSUCH00")).isEmpty();

            assertThat(diagnosticsAt(Level.WARN))
                    .as("a catalogue entry that names nothing is a configuration fault worth warning about")
                    .hasSize(1)
                    .allSatisfy(text -> assertThat(text)
                            .contains("rule=user-menu-dispatch")
                            .contains("NOSUCH00"));
        }

        /**
         * The gate order is the contract, and it is observable only in the log because both gates return
         * the same empty result. With a selection that would trip both, the administrator gate must be
         * the one that reports, proving it short-circuited before suppression was ever evaluated.
         */
        @Test
        @DisplayName("the administrator gate short-circuits before suppression is evaluated")
        void theAdministratorGateShortCircuitsFirst() {
            final Optional<Route> resolved = subject.resolveMenuDispatch(UserType.USER,
                    EXPECTED_ADMIN_OPTION_CODE, EXPECTED_DUMMY_PREFIX);

            assertThat(resolved).as("either gate yields no destination").isEmpty();
            assertThat(diagnosticsAt(Level.DEBUG))
                    .as("the administrator gate must be the rule that reported")
                    .anyMatch(text -> text.contains("rule=user-menu-admin-only-gate"));
            assertThat(diagnosticsAt(Level.DEBUG))
                    .as("suppression must never have been reached")
                    .noneMatch(text -> text.contains("Menu dispatch suppressed"));
        }

        /**
         * The administrative menu applies no user-type gate at all: its catalogue entries carry no
         * user-type field for a gate to test. The separate method is how that absence is expressed, and
         * it dispatches an administrative destination without any type being supplied.
         */
        @Test
        @DisplayName("the administrative menu applies no user-type gate, because its entries carry no type")
        void theAdministrativeMenuAppliesNoUserTypeGate() {
            assertThat(subject.resolveAdminMenuDispatch("COUSR00C"))
                    .as("no type is supplied and none is needed")
                    .contains(Route.USER_LIST);

            assertThat(diagnosticsAt(Level.DEBUG))
                    .as("no administrator gate may be reported on the administrative path")
                    .noneMatch(text -> text.contains("admin-only-gate"));
        }

        /** The administrative path reports its own rule name, distinct from the user path's. */
        @Test
        @DisplayName("the administrative path reports its own rule name")
        void theAdministrativePathReportsItsOwnRuleName() {
            subject.resolveAdminMenuDispatch("NOSUCH00");

            assertThat(diagnosticsAt(Level.WARN))
                    .hasSize(1)
                    .allSatisfy(text -> assertThat(text).contains("rule=admin-menu-dispatch"));
        }

        /** An absent administrative entry name yields no destination rather than raising. */
        @Test
        @DisplayName("an absent administrative entry name yields no destination rather than raising")
        void anAbsentAdministrativeEntryNameYieldsNothing() {
            assertThat(subject.resolveAdminMenuDispatch(null)).isEmpty();
        }
    }

    // ------------------------------------------------------------------------------------------
    // The vocabulary lookups
    // ------------------------------------------------------------------------------------------

    /** The three lookups, and the deliberate difference in how strictly each matches. */
    @Nested
    @DisplayName("the lookups tolerate fixed-width padding but never a case difference")
    final class TheVocabularyLookups {

        /**
         * Every destination is reachable by its own legacy program name, so the index covers the whole
         * vocabulary and not merely the names this class happens to probe.
         *
         * @param route the destination under test
         */
        @ParameterizedTest(name = "{0} is reachable by its program name")
        @EnumSource(Route.class)
        @DisplayName("every destination is reachable by its own legacy program name")
        void everyDestinationIsReachableByItsProgramName(final Route route) {
            assertThat(subject.routeForLegacyProgram(route.getLegacyProgramName()))
                    .as("%s by program name", route)
                    .contains(route);
        }

        /**
         * Every destination is reachable by its own legacy transaction identifier.
         *
         * @param route the destination under test
         */
        @ParameterizedTest(name = "{0} is reachable by its transaction identifier")
        @EnumSource(Route.class)
        @DisplayName("every destination is reachable by its own legacy transaction identifier")
        void everyDestinationIsReachableByItsTransactionId(final Route route) {
            assertThat(subject.routeForLegacyTransactionId(route.getLegacyTransactionId()))
                    .as("%s by transaction identifier", route)
                    .contains(route);
        }

        /**
         * Every destination is reachable by its own wire value, which makes the lookup a genuine inverse
         * of the accessor.
         *
         * @param route the destination under test
         */
        @ParameterizedTest(name = "{0} is reachable by its wire value")
        @EnumSource(Route.class)
        @DisplayName("every destination is reachable by its own wire value")
        void everyDestinationIsReachableByItsWireValue(final Route route) {
            assertThat(subject.routeForValue(route.getRouteValue()))
                    .as("%s by wire value", route)
                    .contains(route);
        }

        /** The two fixed-width lookups tolerate trailing padding, in both padding characters. */
        @Test
        @DisplayName("the fixed-width lookups tolerate trailing padding")
        void theFixedWidthLookupsToleratePadding() {
            assertThat(subject.routeForLegacyProgram("COSGN00C  ")).contains(Route.SIGN_ON);
            assertThat(subject.routeForLegacyProgram("COSGN00C\u0000")).contains(Route.SIGN_ON);
            assertThat(subject.routeForLegacyTransactionId("CC00  ")).contains(Route.SIGN_ON);
            assertThat(subject.routeForLegacyTransactionId("CC00\u0000")).contains(Route.SIGN_ON);
        }

        /**
         * Neither fixed-width lookup applies a case fold, because program names and transaction
         * identifiers are case sensitive in the resource definition.
         */
        @Test
        @DisplayName("neither fixed-width lookup applies a case fold")
        void neitherFixedWidthLookupFoldsCase() {
            assertThat(subject.routeForLegacyProgram("cosgn00c")).isEmpty();
            assertThat(subject.routeForLegacyTransactionId("cc00")).isEmpty();
        }

        /** Leading padding is a difference rather than padding, in both fixed-width lookups. */
        @Test
        @DisplayName("leading padding is a difference, not padding")
        void leadingPaddingIsADifference() {
            assertThat(subject.routeForLegacyProgram(" COSGN00C")).isEmpty();
            assertThat(subject.routeForLegacyTransactionId(" CC00")).isEmpty();
        }

        /**
         * The wire-value lookup matches exactly, because a wire value is not a fixed-width field: its
         * surrounding white space is a difference from the published token rather than padding to be
         * tolerated. This is the deliberate asymmetry between the three lookups.
         */
        @Test
        @DisplayName("the wire-value lookup matches exactly, unlike the two fixed-width lookups")
        void theWireValueLookupMatchesExactly() {
            assertThat(subject.routeForValue("sign-on")).contains(Route.SIGN_ON);
            assertThat(subject.routeForValue("sign-on "))
                    .as("a wire value carries no padding to tolerate")
                    .isEmpty();
            assertThat(subject.routeForValue(" sign-on")).isEmpty();
            assertThat(subject.routeForValue("SIGN_ON"))
                    .as("the constant name is not the wire value")
                    .isEmpty();
        }

        /** All three lookups admit an absent value and yield nothing rather than raising. */
        @Test
        @DisplayName("all three lookups admit an absent value without raising")
        void allThreeLookupsAdmitAnAbsentValue() {
            assertThat(subject.routeForLegacyProgram(null)).isEmpty();
            assertThat(subject.routeForLegacyTransactionId(null)).isEmpty();
            assertThat(subject.routeForValue(null)).isEmpty();
        }

        /** An all-padding value strips to nothing and matches nothing. */
        @Test
        @DisplayName("an all-padding value strips to nothing and matches nothing")
        void anAllPaddingValueMatchesNothing() {
            assertThat(subject.routeForLegacyProgram("        ")).isEmpty();
            assertThat(subject.routeForLegacyProgram("\u0000\u0000")).isEmpty();
            assertThat(subject.routeForLegacyTransactionId("    ")).isEmpty();
        }

        /** An unrecognised value yields nothing rather than failing the request. */
        @Test
        @DisplayName("an unrecognised value yields nothing rather than failing the request")
        void anUnrecognisedValueYieldsNothing() {
            assertThat(subject.routeForLegacyProgram("NOSUCH00")).isEmpty();
            assertThat(subject.routeForLegacyTransactionId("ZZZZ")).isEmpty();
            assertThat(subject.routeForValue("not-a-route")).isEmpty();
        }
    }

    // ------------------------------------------------------------------------------------------
    // The published constant holder
    // ------------------------------------------------------------------------------------------

    /**
     * The compile-time constant holder a controller or payload names a destination through.
     *
     * <p>This is a published surface in its own right, separate from the typed vocabulary: a caller may
     * reference a constant here without touching the enum at all. Every one of the seventeen fields is
     * therefore pinned against the enum's own wire value, so the two definitions cannot drift, and the
     * holder's shape - final, stateless and not instantiable - is asserted rather than assumed.
     */
    @Nested
    @DisplayName("the published constant holder carries all seventeen wire values and cannot be instantiated")
    final class ThePublishedConstantHolder {

        /**
         * Every constant equals the wire value of the destination it names, checked over all seventeen
         * rather than a sample, so no single field can disagree unnoticed.
         *
         * @param constant   the enum constant name
         * @param routeValue the wire value both surfaces must publish
         */
        @ParameterizedTest(name = "Routes.{0} is {1}")
        @CsvSource({
            "SIGN_ON,          sign-on",
            "USER_MENU,        user-menu",
            "ADMIN_MENU,       admin-menu",
            "ACCOUNT_VIEW,     account-view",
            "ACCOUNT_UPDATE,   account-update",
            "CARD_LIST,        card-list",
            "CARD_DETAIL,      card-detail",
            "CARD_UPDATE,      card-update",
            "TRANSACTION_LIST, transaction-list",
            "TRANSACTION_VIEW, transaction-view",
            "TRANSACTION_ADD,  transaction-add",
            "REPORT_REQUEST,   report-request",
            "BILL_PAYMENT,     bill-payment",
            "USER_LIST,        user-list",
            "USER_ADD,         user-add",
            "USER_UPDATE,      user-update",
            "USER_DELETE,      user-delete",
        })
        @DisplayName("each published constant carries the wire value of the destination it names")
        void eachPublishedConstantCarriesItsWireValue(final String constant, final String routeValue)
                throws ReflectiveOperationException {
            final Field field = Routes.class.getDeclaredField(constant);

            assertThat(field.get(null))
                    .as("Routes.%s", constant)
                    .isEqualTo(routeValue)
                    .isEqualTo(Route.valueOf(constant).getRouteValue());
        }

        /**
         * The holder declares exactly one public constant per destination and nothing else, so the
         * seventeen rows above are exhaustive and no undeclared value hides here.
         */
        @Test
        @DisplayName("the holder declares exactly one constant per destination and nothing else")
        void theHolderDeclaresExactlyOneConstantPerDestination() {
            final List<String> declared = new ArrayList<>();
            for (final Field field : Routes.class.getDeclaredFields()) {
                if (!field.isSynthetic()) {
                    declared.add(field.getName());
                }
            }

            assertThat(declared)
                    .as("every declared field of the holder")
                    .hasSize(EXPECTED_ROUTE_COUNT)
                    .doesNotHaveDuplicates();
            for (final Route route : Route.values()) {
                assertThat(declared)
                        .as("the holder must declare a constant for %s", route)
                        .contains(route.name());
            }
        }

        /** Every constant is public, static and final, so none can be reassigned by a caller. */
        @Test
        @DisplayName("every constant is public, static and final")
        void everyConstantIsPublicStaticAndFinal() {
            for (final Field field : Routes.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                final int modifiers = field.getModifiers();
                assertThat(Modifier.isPublic(modifiers)).as("%s is public", field.getName()).isTrue();
                assertThat(Modifier.isStatic(modifiers)).as("%s is static", field.getName()).isTrue();
                assertThat(Modifier.isFinal(modifiers)).as("%s is final", field.getName()).isTrue();
                assertThat(field.getType()).as("%s is a string", field.getName()).isEqualTo(String.class);
            }
        }

        /** The holder is final and stateless, which is what makes it a holder rather than a type. */
        @Test
        @DisplayName("the holder is final and declares no instance state")
        void theHolderIsFinalAndStateless() {
            assertThat(Modifier.isFinal(Routes.class.getModifiers()))
                    .as("the holder must not be extensible")
                    .isTrue();
            for (final Field field : Routes.class.getDeclaredFields()) {
                if (!field.isSynthetic()) {
                    assertThat(Modifier.isStatic(field.getModifiers()))
                            .as("%s must not be instance state", field.getName())
                            .isTrue();
                }
            }
        }

        /**
         * The single constructor is private and actively refuses instantiation rather than merely being
         * hidden, so reflection cannot produce a meaningless instance either.
         */
        @Test
        @DisplayName("the sole constructor is private and refuses instantiation even reflectively")
        void theSoleConstructorRefusesInstantiation() throws ReflectiveOperationException {
            final Constructor<?>[] constructors = Routes.class.getDeclaredConstructors();

            assertThat(constructors).as("the holder must declare exactly one constructor").hasSize(1);
            final Constructor<?> constructor = constructors[0];
            assertThat(Modifier.isPrivate(constructor.getModifiers()))
                    .as("the constructor must be private")
                    .isTrue();
            assertThat(constructor.getParameterCount()).isZero();

            constructor.setAccessible(true);
            assertThatExceptionOfType(InvocationTargetException.class)
                    .as("the guard must fire rather than yielding an instance")
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class)
                    .satisfies(thrown -> assertThat(thrown.getCause())
                            .hasMessage("Routes is a constant holder and must not be instantiated"));
        }
    }
}
