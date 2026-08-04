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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.exception.AbendException;
import com.carddemo.service.NavigationService.Route;
import com.carddemo.service.NavigationService.Routes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Verifies {@link NavigationService}, the owner of the CardDemo route vocabulary and of every legacy
 * navigation rule that selects a destination, together with the diagnostic discipline those rules are
 * reported under.
 *
 * <h2>What the expectations are read from</h2>
 *
 * <p>Every value asserted here comes from the mainframe estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, whose every COBOL and job-control member carries
 * the trailer release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The vocabulary is
 * the 18 transaction definitions registered in {@code app/csd/CARDDEMO.CSD}; the four rule families
 * are the sign-on role split at {@code app/cbl/COSGN00C.cbl} lines 227 to 240, the zero-length
 * communication-area branch at {@code app/cbl/COBIL00C.cbl} lines 107 and 108, the originating- and
 * destination-program tests at {@code app/cbl/COBIL00C.cbl} lines 129 to 134 and
 * {@code app/cbl/COMEN01C.cbl} lines 170 to 177, and the two menu gates at
 * {@code app/cbl/COMEN01C.cbl} lines 136 to 146.
 *
 * <h2>Nothing is mocked, deliberately</h2>
 *
 * <p>The class under test injects nothing and holds no state: its four lookup tables are immutable
 * static members built during class initialization from the declared constants themselves. There is
 * therefore no collaborator to substitute and no seam at which a stub would be more informative than
 * the real thing. Every test below drives the real instance.
 *
 * <h2>The three obligations the file schema fixes</h2>
 *
 * <p>Three expectations are named as obligations rather than chosen, and each has a test that fails
 * loudly if the behaviour drifts:
 *
 * <ul>
 *   <li>the sign-on split must be asserted for the administrator code, for the standard-user code
 *       <strong>and for a value outside both</strong>, because the legacy alternative at line 235 is
 *       unconditional and routes an undeclared type to the main menu rather than rejecting it;</li>
 *   <li>both arms of the back-navigation key must be asserted - the arm that yields a destination and
 *       the arm that yields none - because only one of the sixteen decoded actions transfers control;
 *       and</li>
 *   <li>no route constant may exist for the dangling program definition {@code COCRDSEC}, which the
 *       resource definition file registers and for which no source member exists anywhere in
 *       {@code app/cbl}.</li>
 * </ul>
 *
 * <h2>Why the diagnostic group exists</h2>
 *
 * <p>The two nomination rules read a program name out of client-echoed navigation state whose
 * published contract bounds it by width alone. A caller can therefore place a line feed, a carriage
 * return, a null or any other control character in it, and the class's blank test and padding strip
 * recognise only the space and the low value as padding, so such a character survives to the point
 * where a diagnostic would mention it. The final group asserts that no such character is ever
 * reproduced into a log record and that the reported position and code point locate it exactly.
 *
 * <h2>Three outcomes, not two, and the middle one is a failure</h2>
 *
 * <p>The nomination helper has three arms and they must not be conflated. A field that is absent,
 * empty, all spaces or all low values <strong>nominates nothing</strong>, so the caller's own default
 * applies and the outcome is reported at debug. A field that resolves reaches its destination, reported
 * at debug, and the record publishes the <em>canonical</em> program name read from the destination
 * table rather than the caller's text - the two agree up to the padding the lookup tolerates, and the
 * canonical one is the better field to publish because this class owns it. A field that is neither
 * blank nor resolvable is a <strong>failure</strong>: the legacy transfer-control command would have
 * been attempted and would have abended on a program name the region cannot resolve, so this class
 * abends too, reporting at error first. Substituting the caller's default there would invent a
 * destination the estate does not have and would mask a client sending a name no screen ever sends.
 *
 * <p>The client-echoed field is therefore never reproduced on the failing arm, printable or not: a
 * printable but unrecognised name is described by its length and an unprintable one by the position and
 * code point of its first offending character. The bounded value still travels on the abend, where it
 * is structured data on an exception rather than text a reader parses by line.
 *
 * @see NavigationService
 */
@DisplayName("NavigationService - the 17-destination route vocabulary and the rules that select one")
class NavigationServiceRuleComplianceTest {

    // ---------------------------------------------------------------------------------------------
    // Values read from the estate
    // ---------------------------------------------------------------------------------------------

    /** Legacy program name of the bill-payment screen, {@code app/cbl/COBIL00C.cbl}. */
    private static final String BILL_PAYMENT_PROGRAM = "COBIL00C";

    /**
     * Transaction definitions registered in {@code app/csd/CARDDEMO.CSD}: 18.
     *
     * <p>One more than the destination count, and the difference is the point rather than a
     * discrepancy: the developer transaction {@code CDV1} is registered but is bound to the dangling
     * program definition, so it has no reachable destination to publish.
     */
    private static final int REGISTERED_TRANSACTION_DEFINITIONS = 18;

    /** The developer transaction that is registered but not dispatchable. */
    private static final String UNDISPATCHABLE_TRANSACTION = "CDV1";

    /**
     * The date-validation subprogram the undispatchable transaction was once substituted with.
     *
     * <p>Reached only by static {@code CALL} from four sites, bound to no transaction and named by no
     * transfer-control statement, so it is an internal subprogram rather than a navigable screen.
     */
    private static final String DATE_VALIDATION_SUBPROGRAM = "CSUTLDTC";

    /** Legacy program name of the user main menu, {@code app/cbl/COMEN01C.cbl}. */
    private static final String USER_MENU_PROGRAM = "COMEN01C";

    /** Legacy program name of the sign-on screen, {@code app/cbl/COSGN00C.cbl}. */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /** Legacy program name of the account-view screen, {@code app/cbl/COACTVWC.cbl}. */
    private static final String ACCOUNT_VIEW_PROGRAM = "COACTVWC";

    /** Legacy program name of the user-list screen, {@code app/cbl/COUSR00C.cbl}. */
    private static final String USER_LIST_PROGRAM = "COUSR00C";

    /**
     * The dangling program definition the resource definition file registers and for which no source
     * member exists. No route may resolve from it.
     */
    private static final String DANGLING_PROGRAM_DEFINITION = "COCRDSEC";

    /** A well-formed eight-character name that matches no registered program. */
    private static final String UNREGISTERED_PROGRAM = "NOSUCHPG";

    /** Declared width of a legacy program name, {@code app/cpy/COCOM01Y.cpy} lines 22 and 24. */
    private static final int PROGRAM_NAME_WIDTH = 8;

    /** Declared width of a legacy transaction identifier, {@code app/cpy/COCOM01Y.cpy} lines 21 and 23. */
    private static final int TRANSACTION_ID_WIDTH = 4;

    /** Lowest code point a diagnostic may reproduce: the space. */
    private static final int FIRST_PRINTABLE = 0x20;

    /** Highest code point a diagnostic may reproduce: the tilde. */
    private static final int LAST_PRINTABLE = 0x7E;

    /** Returned by {@link #firstUnprintablePosition(String)} when every character is printable. */
    private static final int NONE_UNPRINTABLE = -1;

    // ---------------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------------

    /** The instance under test. Stateless, so a fresh one per test costs nothing and isolates nothing. */
    private NavigationService service;

    @BeforeEach
    void createService() {
        this.service = new NavigationService();
    }

    // ---------------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds a navigation state carrying only an originating-program name, the field back-navigation
     * reads.
     *
     * @param fromProgram the originating-program name; may be {@code null}
     * @return a context whose other fifteen components are absent
     */
    private static ConversationState contextFromProgram(final String fromProgram) {
        return new ConversationState(null, fromProgram, null, null, null);
    }

    /**
     * Builds a navigation state carrying only a destination-program name, the field nominated-destination
     * resolution reads.
     *
     * @param toProgram the destination-program name; may be {@code null}
     * @return a context whose other fifteen components are absent
     */
    private static ConversationState contextToProgram(final String toProgram) {
        return new ConversationState(null, null, null, toProgram, null);
    }

    /**
     * Builds a navigation state carrying both program-name fields, so a test can prove that each rule
     * reads its own field and not the other.
     *
     * @param fromProgram the originating-program name; may be {@code null}
     * @param toProgram   the destination-program name; may be {@code null}
     * @return a context whose other fourteen components are absent
     */
    private static ConversationState contextWithBothPrograms(final String fromProgram,
            final String toProgram) {
        return new ConversationState(null, fromProgram, null, toProgram, null);
    }

    /**
     * Returns the zero-based position of the first character of a text that a log record must not
     * carry.
     *
     * @param text the text to scan; never {@code null}
     * @return the position of the first character outside printable US-ASCII, or
     *         {@link #NONE_UNPRINTABLE}
     */
    private static int firstUnprintablePosition(final String text) {
        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);
            if (character < FIRST_PRINTABLE || character > LAST_PRINTABLE) {
                return index;
            }
        }
        return NONE_UNPRINTABLE;
    }

    /**
     * Asserts that a text carries nothing a log record must not carry.
     *
     * <p>Structural rather than enumerated on purpose: it inspects every character rather than looking
     * for the specific characters a test happened to inject, so a diagnostic added later is covered
     * without this assertion being rewritten.
     *
     * @param text        the text to inspect; never {@code null}
     * @param description what the text is, for the failure message
     */
    private static void assertSafeToLog(final String text, final String description) {
        final int offending = firstUnprintablePosition(text);
        assertThat(offending)
                .as("%s must carry only printable US-ASCII; position %d carries code point %d",
                        description, offending,
                        offending == NONE_UNPRINTABLE ? NONE_UNPRINTABLE : (int) text.charAt(offending))
                .isEqualTo(NONE_UNPRINTABLE);
    }

    /**
     * Asserts that a diagnostic localised an offending character rather than reproducing it.
     *
     * @param text      the diagnostic to inspect; never {@code null}
     * @param position  the expected zero-based position
     * @param codePoint the expected code point
     */
    private static void assertDegradedAt(final String text, final int position, final int codePoint) {
        assertThat(text)
                .as("the diagnostic must describe the offending character instead of reproducing it")
                .contains("not printable US-ASCII")
                .contains("zero-based position " + position)
                .contains("code point " + codePoint);
    }

    // =============================================================================================

    @Nested
    @DisplayName("construction - stateless, dependency-free and interchangeable")
    class Construction {

        @Test
        @DisplayName("the service constructs with no argument, because it collaborates with nothing")
        void theServiceConstructsWithNoArgument() {
            assertThatNoException().isThrownBy(NavigationService::new);
        }

        @Test
        @DisplayName("two instances expose the same immutable vocabulary, which is what makes a single "
                + "framework-managed instance safe")
        void twoInstancesExposeTheSameVocabulary() {
            final NavigationService other = new NavigationService();

            assertThat(other.routes())
                    .as("the destination list is a static immutable member, so instances share it")
                    .isSameAs(NavigationServiceRuleComplianceTest.this.service.routes());
            assertThat(other.adminScopedRoutes())
                    .isSameAs(NavigationServiceRuleComplianceTest.this.service.adminScopedRoutes());
        }

        @Test
        @DisplayName("the class declares no mutable field, which is what the thread-safety claim rests on")
        void theClassDeclaresNoMutableField() {
            for (final Field field : NavigationService.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
                assertThat(Modifier.isStatic(field.getModifiers()))
                        .as("field %s must be static, so no instance carries state", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the class is final, so no subclass can weaken a resolution rule")
        void theClassIsFinal() {
            assertThat(Modifier.isFinal(NavigationService.class.getModifiers())).isTrue();
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the route vocabulary - 17 destinations, one per dispatchable transaction definition")
    class TheRouteVocabulary {

        @Test
        @DisplayName("the published destination count is 17 and the enumeration agrees with it")
        void thePublishedDestinationCountIsSeventeen() {
            assertThat(NavigationService.ROUTE_COUNT).isEqualTo(17);
            assertThat(Route.values()).hasSize(NavigationService.ROUTE_COUNT);
            assertThat(NavigationServiceRuleComplianceTest.this.service.routes())
                    .hasSize(NavigationService.ROUTE_COUNT);
        }

        @Test
        @DisplayName("the destination count is one short of the registration count, because a "
                + "transaction bound to a program that does not exist has no destination to publish")
        void theDestinationCountIsOneShortOfTheRegistrationCount() {
            assertThat(NavigationService.ROUTE_COUNT)
                    .isEqualTo(REGISTERED_TRANSACTION_DEFINITIONS - 1);
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .routeForLegacyTransactionId(UNDISPATCHABLE_TRANSACTION))
                    .as("the registered but undispatchable transaction resolves to nothing")
                    .isEmpty();
        }

        @Test
        @DisplayName("the published legacy site counts are the mechanically verified 25 and 19")
        void thePublishedLegacySiteCountsAreTheVerifiedFigures() {
            assertThat(NavigationService.LEGACY_DISPATCH_SITE_COUNT)
                    .as("transfer-control dispatch sites over non-comment lines of app/cbl")
                    .isEqualTo(25);
            assertThat(NavigationService.LEGACY_REARM_SITE_COUNT)
                    .as("pseudo-conversational re-arm sites across the 17 online programs")
                    .isEqualTo(19);
        }

        @Test
        @DisplayName("the suppression literal is DUMMY and the administrator-only option code is A")
        void theGateLiteralsAreTheLegacyOnes() {
            assertThat(NavigationService.DUMMY_PROGRAM_PREFIX).isEqualTo("DUMMY");
            assertThat(NavigationService.DUMMY_PROGRAM_PREFIX)
                    .as("the legacy guard compares the first five characters")
                    .hasSize(5);
            assertThat(NavigationService.ADMIN_ONLY_OPTION_USER_TYPE_CODE).isEqualTo("A");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.service.NavigationServiceRuleComplianceTest#theDestinationStateTable")
        @DisplayName("each destination carries the wire value, legacy identifiers and administrative "
                + "scope the resource definition file fixes")
        void eachDestinationCarriesItsRegisteredAttributes(final Route route, final String routeValue,
                final String legacyTransactionId, final String legacyProgramName,
                final boolean adminScoped) {

            assertThat(route.getRouteValue()).isEqualTo(routeValue);
            assertThat(route.getLegacyTransactionId()).isEqualTo(legacyTransactionId);
            assertThat(route.getLegacyProgramName()).isEqualTo(legacyProgramName);
            assertThat(route.isAdminScoped()).isEqualTo(adminScoped);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.service.NavigationServiceRuleComplianceTest#theDestinationStateTable")
        @DisplayName("every legacy identifier is at its declared fixed width")
        void everyLegacyIdentifierIsAtItsDeclaredWidth(final Route route, final String routeValue,
                final String legacyTransactionId, final String legacyProgramName,
                final boolean adminScoped) {

            assertThat(legacyTransactionId).hasSize(TRANSACTION_ID_WIDTH);
            assertThat(legacyProgramName).hasSize(PROGRAM_NAME_WIDTH);
            assertThat(route.isAdminScoped()).isEqualTo(adminScoped);
            assertThat(route.getRouteValue()).isEqualTo(routeValue);
        }

        @Test
        @DisplayName("the state table above covers every declared constant, so no destination escapes it")
        void theStateTableCoversEveryDeclaredConstant() {
            final Set<Route> tabulated = new LinkedHashSet<>();
            theDestinationStateTable()
                    .forEach(row -> tabulated.add((Route) row.get()[0]));

            assertThat(tabulated).containsExactlyElementsOf(NavigationServiceRuleComplianceTest.this.service.routes());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.service.NavigationServiceRuleComplianceTest#theDestinationStateTable")
        @DisplayName("no wire value carries a mainframe token: each is lower case with hyphen separators")
        void noWireValueCarriesAMainframeToken(final Route route, final String routeValue,
                final String legacyTransactionId, final String legacyProgramName,
                final boolean adminScoped) {

            assertThat(routeValue)
                    .as("the published form is role-named, lower case and hyphen separated")
                    .matches("[a-z]+(-[a-z]+)*");
            assertThat(routeValue.toUpperCase(Locale.ROOT))
                    .as("no wire value may contain the legacy program name it replaces")
                    .doesNotContain(legacyProgramName)
                    .doesNotContain(legacyTransactionId);
            assertThat(route.isAdminScoped()).isEqualTo(adminScoped);
        }

        @Test
        @DisplayName("every wire value, legacy identifier and legacy program name is distinct, so no "
                + "index entry can silently displace another")
        void everyKeyIsDistinct() {
            final List<Route> routes = NavigationServiceRuleComplianceTest.this.service.routes();

            assertThat(routes.stream().map(Route::getRouteValue).distinct().count())
                    .isEqualTo(routes.size());
            assertThat(routes.stream().map(Route::getLegacyTransactionId).distinct().count())
                    .isEqualTo(routes.size());
            assertThat(routes.stream().map(Route::getLegacyProgramName).distinct().count())
                    .isEqualTo(routes.size());
        }

        @Test
        @DisplayName("the destination list is in user-traversal declaration order and is immutable")
        void theDestinationListIsOrderedAndImmutable() {
            final List<Route> routes = NavigationServiceRuleComplianceTest.this.service.routes();

            assertThat(routes).containsExactly(Route.values());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> routes.add(Route.SIGN_ON));
        }

        @Test
        @DisplayName("the administrative set is exactly the administrative menu plus the four "
                + "user-maintenance destinations, and is immutable")
        void theAdministrativeSetIsTheFiveAdministrativeDestinations() {
            final Set<Route> adminScoped = NavigationServiceRuleComplianceTest.this.service.adminScopedRoutes();

            assertThat(adminScoped).containsExactlyInAnyOrder(Route.ADMIN_MENU, Route.USER_LIST,
                    Route.USER_ADD, Route.USER_UPDATE, Route.USER_DELETE);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> adminScoped.add(Route.SIGN_ON));
        }

        @Test
        @DisplayName("the administrative set is derived from the flag each constant declares, so the two "
                + "cannot disagree")
        void theAdministrativeSetAgreesWithEveryDeclaredFlag() {
            final Set<Route> adminScoped = NavigationServiceRuleComplianceTest.this.service.adminScopedRoutes();

            for (final Route route : Route.values()) {
                assertThat(adminScoped.contains(route))
                        .as("%s declares adminScoped=%s", route, route.isAdminScoped())
                        .isEqualTo(route.isAdminScoped());
            }
        }

        @Test
        @DisplayName("no destination exists for the dangling program definition COCRDSEC, and none may "
                + "be added")
        void noDestinationExistsForTheDanglingProgramDefinition() {
            for (final Route route : Route.values()) {
                assertThat(route.getLegacyProgramName())
                        .as("%s must not name the dangling definition", route)
                        .isNotEqualTo(DANGLING_PROGRAM_DEFINITION);
                assertThat(route.name())
                        .as("no constant may be named after the dangling definition")
                        .isNotEqualTo(DANGLING_PROGRAM_DEFINITION);
            }
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .routeForLegacyProgram(DANGLING_PROGRAM_DEFINITION))
                    .as("the dangling definition resolves to nothing")
                    .isEmpty();
        }

        @Test
        @DisplayName("no destination exists for the developer transaction CDV1 either, because the "
                + "program definition it is bound to is the dangling one")
        void noDestinationExistsForTheUndispatchableTransaction() {
            for (final Route route : Route.values()) {
                assertThat(route.getLegacyTransactionId())
                        .as("%s must not claim the undispatchable transaction", route)
                        .isNotEqualTo(UNDISPATCHABLE_TRANSACTION);
            }
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .routeForLegacyTransactionId(UNDISPATCHABLE_TRANSACTION)).isEmpty();
            assertThat(Arrays.stream(Route.values()).map(Route::name).toList())
                    .doesNotContain("DATE_VALIDATION");
        }

        @Test
        @DisplayName("the date-validation subprogram is not substituted in as the missing destination, "
                + "because it is bound to no transaction and reached only by static call")
        void theDateValidationSubprogramIsNotSubstitutedIn() {
            for (final Route route : Route.values()) {
                assertThat(route.getLegacyProgramName())
                        .as("%s must not name the internal subprogram", route)
                        .isNotEqualTo(DATE_VALIDATION_SUBPROGRAM);
            }
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .routeForLegacyProgram(DATE_VALIDATION_SUBPROGRAM))
                    .as("an internal subprogram is not a navigable destination")
                    .isEmpty();
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .routeForValue("date-validation"))
                    .as("and no wire value is published for it")
                    .isEmpty();
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the wire-value constant holder")
    class TheWireValueConstantHolder {

        @Test
        @DisplayName("the holder declares exactly 17 public constants, one per destination")
        void theHolderDeclaresOneConstantPerDestination() throws IllegalAccessException {
            final Set<String> declared = new LinkedHashSet<>();
            for (final Field field : Routes.class.getDeclaredFields()) {
                if (field.getType() == String.class && Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isPublic(field.getModifiers()))
                            .as("constant %s must be public", field.getName())
                            .isTrue();
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("constant %s must be final", field.getName())
                            .isTrue();
                    declared.add((String) field.get(null));
                }
            }

            assertThat(declared).hasSize(NavigationService.ROUTE_COUNT);
            assertThat(declared).containsExactlyInAnyOrderElementsOf(
                    NavigationServiceRuleComplianceTest.this.service.routes().stream()
                            .map(Route::getRouteValue).toList());
        }

        @Test
        @DisplayName("each typed destination is initialized from the matching constant, so there is one "
                + "definition of every value")
        void eachTypedDestinationIsInitializedFromTheMatchingConstant() {
            assertThat(Route.SIGN_ON.getRouteValue()).isSameAs(Routes.SIGN_ON);
            assertThat(Route.USER_MENU.getRouteValue()).isSameAs(Routes.USER_MENU);
            assertThat(Route.ADMIN_MENU.getRouteValue()).isSameAs(Routes.ADMIN_MENU);
            assertThat(Route.ACCOUNT_VIEW.getRouteValue()).isSameAs(Routes.ACCOUNT_VIEW);
            assertThat(Route.ACCOUNT_UPDATE.getRouteValue()).isSameAs(Routes.ACCOUNT_UPDATE);
            assertThat(Route.CARD_LIST.getRouteValue()).isSameAs(Routes.CARD_LIST);
            assertThat(Route.CARD_DETAIL.getRouteValue()).isSameAs(Routes.CARD_DETAIL);
            assertThat(Route.CARD_UPDATE.getRouteValue()).isSameAs(Routes.CARD_UPDATE);
            assertThat(Route.TRANSACTION_LIST.getRouteValue()).isSameAs(Routes.TRANSACTION_LIST);
            assertThat(Route.TRANSACTION_VIEW.getRouteValue()).isSameAs(Routes.TRANSACTION_VIEW);
            assertThat(Route.TRANSACTION_ADD.getRouteValue()).isSameAs(Routes.TRANSACTION_ADD);
            assertThat(Route.REPORT_REQUEST.getRouteValue()).isSameAs(Routes.REPORT_REQUEST);
            assertThat(Route.BILL_PAYMENT.getRouteValue()).isSameAs(Routes.BILL_PAYMENT);
            assertThat(Route.USER_LIST.getRouteValue()).isSameAs(Routes.USER_LIST);
            assertThat(Route.USER_ADD.getRouteValue()).isSameAs(Routes.USER_ADD);
            assertThat(Route.USER_UPDATE.getRouteValue()).isSameAs(Routes.USER_UPDATE);
            assertThat(Route.USER_DELETE.getRouteValue()).isSameAs(Routes.USER_DELETE);
        }

        @Test
        @DisplayName("the holder declares no constant for the date-validation subprogram, so the wire "
                + "vocabulary cannot offer a destination the estate does not have")
        void theHolderDeclaresNoDateValidationConstant() {
            assertThat(Arrays.stream(Routes.class.getDeclaredFields()).map(Field::getName).toList())
                    .doesNotContain("DATE_VALIDATION");
        }

        @Test
        @DisplayName("the holder is final, declares no instance member and cannot be instantiated")
        void theHolderCannotBeInstantiated() throws NoSuchMethodException {
            assertThat(Modifier.isFinal(Routes.class.getModifiers())).isTrue();
            for (final Field field : Routes.class.getDeclaredFields()) {
                assertThat(Modifier.isStatic(field.getModifiers()))
                        .as("field %s must be static: a holder declares no instance member",
                                field.getName())
                        .isTrue();
            }

            final Constructor<Routes> constructor = Routes.class.getDeclaredConstructor();
            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
            constructor.setAccessible(true);
            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the sign-on role split - one test and an unconditional alternative, never two tests")
    class TheSignOnRoleSplit {

        @Test
        @DisplayName("the administrator type reaches the administrative menu")
        void theAdministratorTypeReachesTheAdministrativeMenu() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveSignOnRoute(UserType.ADMIN))
                    .isEqualTo(Route.ADMIN_MENU);
        }

        @Test
        @DisplayName("the standard user type reaches the user main menu")
        void theStandardUserTypeReachesTheUserMainMenu() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveSignOnRoute(UserType.USER))
                    .isEqualTo(Route.USER_MENU);
        }

        @Test
        @DisplayName("an absent type reaches the user main menu and never throws, because the legacy "
                + "alternative is unconditional and has no error path")
        void anAbsentTypeReachesTheUserMainMenu() {
            assertThatNoException().isThrownBy(
                    () -> NavigationServiceRuleComplianceTest.this.service.resolveSignOnRoute(null));
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveSignOnRoute(null))
                    .isEqualTo(Route.USER_MENU);
        }

        @Test
        @DisplayName("exactly one of the two declared types reaches the administrative menu")
        void exactlyOneDeclaredTypeReachesTheAdministrativeMenu() {
            final List<UserType> administrative = new ArrayList<>();
            for (final UserType userType : EnumSet.allOf(UserType.class)) {
                if (NavigationServiceRuleComplianceTest.this.service.resolveSignOnRoute(userType)
                        == Route.ADMIN_MENU) {
                    administrative.add(userType);
                }
            }

            assertThat(administrative).containsExactly(UserType.ADMIN);
        }

        @Test
        @DisplayName("the administrator code reaches the administrative menu")
        void theAdministratorCodeReachesTheAdministrativeMenu() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveSignOnRouteForUserTypeCode("A"))
                    .isEqualTo(Route.ADMIN_MENU);
        }

        @Test
        @DisplayName("the standard user code reaches the user main menu")
        void theStandardUserCodeReachesTheUserMainMenu() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveSignOnRouteForUserTypeCode("U"))
                    .isEqualTo(Route.USER_MENU);
        }

        @ParameterizedTest(name = "code [{0}]")
        @ValueSource(strings = {"X", "Z", "1", "a", "u", "AA", "AU", " ", "  ", "", "\u0000"})
        @DisplayName("a code outside the two declared values reaches the user main menu rather than "
                + "being rejected - the schema-fixed third case, and the one a conventional Java shape "
                + "would get wrong")
        void aCodeOutsideTheDeclaredValuesReachesTheUserMainMenu(final String userTypeCode) {
            assertThatNoException().isThrownBy(() -> NavigationServiceRuleComplianceTest.this.service
                    .resolveSignOnRouteForUserTypeCode(userTypeCode));
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveSignOnRouteForUserTypeCode(userTypeCode))
                    .as("only the administrator code may reach the administrative menu")
                    .isEqualTo(Route.USER_MENU);
        }

        @Test
        @DisplayName("an absent code reaches the user main menu")
        void anAbsentCodeReachesTheUserMainMenu() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveSignOnRouteForUserTypeCode(null))
                    .isEqualTo(Route.USER_MENU);
        }

        @Test
        @DisplayName("no case fold is applied, so a lower-cased administrator code is not an administrator")
        void noCaseFoldIsApplied() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveSignOnRouteForUserTypeCode("a"))
                    .as("the legacy path moves the credential character without inspecting it")
                    .isEqualTo(Route.USER_MENU);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("entry carrying no prior navigation state")
    class EntryCarryingNoPriorState {

        @Test
        @DisplayName("an absent context carries no prior state")
        void anAbsentContextCarriesNoPriorState() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.isConversationStateAbsent(null)).isTrue();
        }

        @Test
        @DisplayName("the wholly empty context carries no prior state, because that is what a zero-length "
                + "communication area describes")
        void theWhollyEmptyContextCarriesNoPriorState() {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .isConversationStateAbsent(ConversationState.empty())).isTrue();
        }

        @Test
        @DisplayName("a context carrying any component does carry prior state")
        void aContextCarryingAnyComponentCarriesPriorState() {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .isConversationStateAbsent(contextFromProgram(BILL_PAYMENT_PROGRAM))).isFalse();
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .isConversationStateAbsent(contextToProgram(SIGN_ON_PROGRAM))).isFalse();
        }

        @Test
        @DisplayName("a context whose fields are empty strings rather than absent does carry prior state, "
                + "because it is not the empty instance")
        void aContextOfEmptyStringsCarriesPriorState() {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .isConversationStateAbsent(contextFromProgram(""))).isFalse();
        }

        @Test
        @DisplayName("entry with no prior state leads to sign-on, unconditionally and with no override")
        void entryWithNoPriorStateLeadsToSignOn() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveAbsentContextRoute())
                    .isEqualTo(Route.SIGN_ON);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("return to the previous screen - the originating-program field")
    class ReturnToThePreviousScreen {

        @ParameterizedTest(name = "{0}")
        @EnumSource(KeyAction.class)
        @DisplayName("the third program-function key is the back-navigation key and no other decoded "
                + "action is - both arms, as the schema fixes")
        void onlyTheThirdProgramFunctionKeyIsTheBackNavigationKey(final KeyAction keyAction) {
            assertThat(NavigationServiceRuleComplianceTest.this.service.isBackNavigationKey(keyAction))
                    .as("%s", keyAction)
                    .isEqualTo(keyAction == KeyAction.PFK03);
        }

        @Test
        @DisplayName("an undecoded action is not the back-navigation key and does not throw")
        void anUndecodedActionIsNotTheBackNavigationKey() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.isBackNavigationKey(null)).isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.service.NavigationServiceRuleComplianceTest#theDestinationStateTable")
        @DisplayName("every destination is reachable from the legacy program name its own field carries")
        void everyDestinationIsReachableFromItsOwnProgramName(final Route route, final String routeValue,
                final String legacyTransactionId, final String legacyProgramName,
                final boolean adminScoped) {

            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                    contextFromProgram(legacyProgramName), Route.SIGN_ON))
                    .as("the field names %s", routeValue)
                    .isEqualTo(route);
            assertThat(legacyTransactionId).hasSize(TRANSACTION_ID_WIDTH);
            assertThat(route.isAdminScoped()).isEqualTo(adminScoped);
        }

        @ParameterizedTest(name = "field [{0}]")
        @ValueSource(strings = {"", " ", "        ", "\u0000", "\u0000\u0000\u0000\u0000",
            " \u0000 \u0000"})
        @DisplayName("a field holding only spaces or only low values nominates nothing, so the caller's "
                + "own default applies")
        void aBlankFieldAppliesTheCallerDefault(final String fromProgram) {
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                    contextFromProgram(fromProgram), Route.USER_MENU))
                    .isEqualTo(Route.USER_MENU);
        }

        @Test
        @DisplayName("an absent field nominates nothing, so the caller's own default applies")
        void anAbsentFieldAppliesTheCallerDefault() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                    contextFromProgram(null), Route.CARD_LIST))
                    .isEqualTo(Route.CARD_LIST);
        }

        /**
         * A tab is not blank in the legacy sense, and the difference is observable.
         *
         * <p>The field is fixed width, and what is blank in a fixed-width field is spaces or low values;
         * every other character is content. A conventional white-space test would call a tab blank and
         * would apply the caller's default. The legacy test does not, so a field holding a tab nominates
         * a name, that name resolves to nothing, and the outcome is the failing arm. The distinction is
         * therefore not a matter of severity but of outcome, which is exactly why it is asserted through
         * the outcome rather than through the blank test itself.</p>
         */
        @Test
        @DisplayName("the blank test is the legacy one: a tab is not blank, so a field holding one "
                + "nominates a name that resolves to nothing and abends")
        void aTabIsNotBlankUnderTheLegacyTest() {
            assertThatExceptionOfType(AbendException.class)
                    .as("a tab must not be treated as blank")
                    .isThrownBy(() -> NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                            contextFromProgram("\t"), Route.USER_MENU))
                    .withMessageContaining("RULE back-navigation");
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                    contextFromProgram(" "), Route.USER_MENU))
                    .as("a space, by contrast, is blank and applies the caller's default")
                    .isEqualTo(Route.USER_MENU);
        }

        @Test
        @DisplayName("the default is per screen and never global: the same unresolvable field yields a "
                + "different destination for a different caller")
        void theDefaultIsPerScreenAndNeverGlobal() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                    contextFromProgram(null), Route.USER_MENU)).isEqualTo(Route.USER_MENU);
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                    contextFromProgram(null), Route.SIGN_ON)).isEqualTo(Route.SIGN_ON);
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                    contextFromProgram(null), Route.ADMIN_MENU)).isEqualTo(Route.ADMIN_MENU);
        }

        @Test
        @DisplayName("the bill-payment screen's verified default is the user main menu")
        void theBillPaymentDefaultIsTheUserMainMenu() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                    ConversationState.empty(), Route.USER_MENU))
                    .as("app/cbl/COBIL00C.cbl line 130")
                    .isEqualTo(Route.USER_MENU);
        }

        @ParameterizedTest(name = "field [{0}]")
        @ValueSource(strings = {"COBIL00C ", "COBIL00C   ", "COBIL00C\u0000",
            "COBIL00C\u0000\u0000", "COBIL00C \u0000 "})
        @DisplayName("trailing padding is tolerated, because the legacy field is fixed width and the "
                + "transfer-control command ignores it")
        void trailingPaddingIsTolerated(final String fromProgram) {
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                    contextFromProgram(fromProgram), Route.SIGN_ON))
                    .isEqualTo(Route.BILL_PAYMENT);
        }

        /**
         * Only trailing padding is tolerated, and everything else is an unresolvable name.
         *
         * <p>Leading padding, an interior alteration and a different letter case all fail to match, and
         * a name that fails to match is not blank: it names something, and what it names does not exist.
         * The outcome is therefore the failing arm and not the caller's default. Asserting the abend
         * rather than the default is what makes this test say something about matching - a test that
         * accepted the caller's default would pass equally if the lookup had matched nothing at all,
         * including for the five values the previous test proves <em>do</em> match.</p>
         */
        @ParameterizedTest(name = "field [{0}]")
        @ValueSource(strings = {" COBIL00C", "  COBIL00C", "\u0000COBIL00C", "COB IL00C",
            "cobil00c", "Cobil00c", UNREGISTERED_PROGRAM, DANGLING_PROGRAM_DEFINITION})
        @DisplayName("only trailing padding is tolerated: leading padding, interior alteration and a "
                + "different letter case all fail to match, and a failed match abends")
        void onlyTrailingPaddingIsTolerated(final String fromProgram) {
            assertThatExceptionOfType(AbendException.class)
                    .as("field [%s] must not match", fromProgram)
                    .isThrownBy(() -> NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                            contextFromProgram(fromProgram), Route.TRANSACTION_LIST))
                    .withMessageContaining("RULE back-navigation");
        }

        /**
         * An unrecognised name abends; the caller's default belongs to the blank arm alone.
         *
         * <p>The field is client-echoed and therefore untrusted, and it is tempting to read that as an
         * argument for degrading to the caller's default. It is the opposite. The legacy program moves
         * this field into the transfer-control command's program operand, and the command abends when
         * the region cannot resolve the name; degrading here would silently route a client that sent a
         * name no screen ever sends to a screen it did not ask for. What the untrusted provenance
         * argues for is that the <em>diagnostic</em> must not reproduce the value, which the
         * diagnostic group asserts separately.</p>
         */
        @Test
        @DisplayName("an unrecognised name abends rather than applying the caller's default, because the "
                + "legacy transfer-control command would have abended on it")
        void anUnrecognisedNameAbendsRatherThanApplyingTheCallerDefault() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> NavigationServiceRuleComplianceTest.this.service
                            .resolveBackNavigation(contextFromProgram(UNREGISTERED_PROGRAM),
                                    Route.USER_MENU))
                    .withMessageContaining("RULE back-navigation");
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                    ConversationState.empty(), Route.USER_MENU))
                    .as("the caller's default is reached by the blank arm, and only by it")
                    .isEqualTo(Route.USER_MENU);
        }

        @Test
        @DisplayName("an absent context is rejected, because entry with no state at all is a different "
                + "rule resolved before this one")
        void anAbsentContextIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> NavigationServiceRuleComplianceTest.this.service
                            .resolveBackNavigation(null, Route.USER_MENU))
                    .withMessage("context must not be null");
        }

        @Test
        @DisplayName("an absent caller default is rejected, because the fallback is never assumed")
        void anAbsentCallerDefaultIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> NavigationServiceRuleComplianceTest.this.service
                            .resolveBackNavigation(ConversationState.empty(), null))
                    .withMessage("callerDefault must not be null");
        }

        @Test
        @DisplayName("back navigation reads the originating-program field and never the destination one")
        void backNavigationReadsTheOriginatingProgramField() {
            final ConversationState context =
                    contextWithBothPrograms(BILL_PAYMENT_PROGRAM, USER_LIST_PROGRAM);

            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveBackNavigation(context, Route.SIGN_ON))
                    .isEqualTo(Route.BILL_PAYMENT);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the attention-key arms - only one of the sixteen actions transfers control")
    class TheAttentionKeyArms {

        @Test
        @DisplayName("the third program-function key yields the back-navigation destination")
        void theThirdProgramFunctionKeyYieldsTheBackNavigationDestination() {
            final Optional<Route> resolved = NavigationServiceRuleComplianceTest.this.service
                    .resolveAttentionKeyRoute(KeyAction.PFK03,
                            contextFromProgram(BILL_PAYMENT_PROGRAM), Route.SIGN_ON);

            assertThat(resolved).contains(Route.BILL_PAYMENT);
        }

        @Test
        @DisplayName("the third program-function key yields the caller's default when the field nominates "
                + "nothing")
        void theThirdProgramFunctionKeyYieldsTheCallerDefaultWhenNothingIsNominated() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveAttentionKeyRoute(
                    KeyAction.PFK03, ConversationState.empty(), Route.USER_MENU))
                    .contains(Route.USER_MENU);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = KeyAction.class, names = "PFK03", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("every other decoded action yields no destination, because neither the enter key nor "
                + "an unmapped key transfers control")
        void everyOtherDecodedActionYieldsNoDestination(final KeyAction keyAction) {
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveAttentionKeyRoute(
                    keyAction, contextFromProgram(BILL_PAYMENT_PROGRAM), Route.SIGN_ON))
                    .as("%s does not transfer control", keyAction)
                    .isEmpty();
        }

        @Test
        @DisplayName("an undecoded action yields no destination")
        void anUndecodedActionYieldsNoDestination() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveAttentionKeyRoute(
                    null, ConversationState.empty(), Route.SIGN_ON))
                    .isEmpty();
        }

        @Test
        @DisplayName("the context and the caller default are validated before the key is examined, so a "
                + "non-navigating key does not mask a missing argument")
        void theArgumentsAreValidatedBeforeTheKeyIsExamined() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> NavigationServiceRuleComplianceTest.this.service
                            .resolveAttentionKeyRoute(KeyAction.ENTER, null, Route.SIGN_ON))
                    .withMessage("context must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> NavigationServiceRuleComplianceTest.this.service
                            .resolveAttentionKeyRoute(KeyAction.ENTER, ConversationState.empty(), null))
                    .withMessage("callerDefault must not be null");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("return to an already nominated destination - the destination-program field")
    class ReturnToANominatedDestination {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.service.NavigationServiceRuleComplianceTest#theDestinationStateTable")
        @DisplayName("every destination is reachable from the legacy program name the destination field "
                + "carries")
        void everyDestinationIsReachableFromTheDestinationField(final Route route,
                final String routeValue, final String legacyTransactionId,
                final String legacyProgramName, final boolean adminScoped) {

            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveNominatedDestination(
                    contextToProgram(legacyProgramName), Route.SIGN_ON))
                    .as("the field names %s", routeValue)
                    .isEqualTo(route);
            assertThat(legacyTransactionId).hasSize(TRANSACTION_ID_WIDTH);
            assertThat(route.isAdminScoped()).isEqualTo(adminScoped);
        }

        @ParameterizedTest(name = "field [{0}]")
        @ValueSource(strings = {"", " ", "        ", "\u0000", "\u0000\u0000"})
        @DisplayName("a blank destination field substitutes the caller's own default")
        void aBlankDestinationFieldSubstitutesTheCallerDefault(final String toProgram) {
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveNominatedDestination(
                    contextToProgram(toProgram), Route.ADMIN_MENU))
                    .isEqualTo(Route.ADMIN_MENU);
        }

        /**
         * An unresolvable destination field abends; only a blank one substitutes the caller's default.
         *
         * <p>The two arms are different outcomes of the same rule and the distinction is the legacy
         * one. A blank field nominates nothing, so there is nothing to fail on and the caller's own
         * default applies. A field naming a program the region cannot resolve is what the legacy
         * transfer-control command would have been given, and it would have abended on it; substituting
         * the caller's default here would invent a destination the estate does not have.</p>
         */
        @Test
        @DisplayName("an unrecognised destination field abends rather than substituting the caller's "
                + "default, because the transfer it stands for would have failed")
        void anUnrecognisedDestinationFieldAbends() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> NavigationServiceRuleComplianceTest.this.service.resolveNominatedDestination(
                            contextToProgram(UNREGISTERED_PROGRAM), Route.CARD_DETAIL))
                    .withMessageContaining("RULE nominated-destination");
        }

        @Test
        @DisplayName("the dangling program definition is unresolvable on the destination field too, so "
                + "the transaction bound to it could not have dispatched")
        void theDanglingProgramDefinitionIsUnresolvableOnTheDestinationField() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> NavigationServiceRuleComplianceTest.this.service.resolveNominatedDestination(
                            contextToProgram(DANGLING_PROGRAM_DEFINITION), Route.CARD_DETAIL));
        }

        @Test
        @DisplayName("trailing padding is tolerated on the destination field too")
        void trailingPaddingIsToleratedOnTheDestinationField() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveNominatedDestination(
                    contextToProgram(USER_MENU_PROGRAM + "  "), Route.SIGN_ON))
                    .isEqualTo(Route.USER_MENU);
        }

        @Test
        @DisplayName("nominated-destination resolution reads the destination field and never the "
                + "originating one")
        void nominatedResolutionReadsTheDestinationField() {
            final ConversationState context =
                    contextWithBothPrograms(BILL_PAYMENT_PROGRAM, USER_LIST_PROGRAM);

            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveNominatedDestination(context, Route.SIGN_ON))
                    .isEqualTo(Route.USER_LIST);
        }

        @Test
        @DisplayName("the two nomination rules read different fields of the same context, which is the "
                + "whole reason they are separate rules")
        void theTwoNominationRulesReadDifferentFields() {
            final ConversationState context =
                    contextWithBothPrograms(BILL_PAYMENT_PROGRAM, ACCOUNT_VIEW_PROGRAM);

            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveBackNavigation(context, Route.SIGN_ON))
                    .isEqualTo(Route.BILL_PAYMENT);
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveNominatedDestination(context, Route.SIGN_ON))
                    .isEqualTo(Route.ACCOUNT_VIEW);
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveSignOffRoute(context))
                    .as("sign-off is the destination-field rule with a fixed fallback")
                    .isEqualTo(Route.ACCOUNT_VIEW);
        }

        @Test
        @DisplayName("an absent context is rejected and an absent caller default is rejected")
        void absentArgumentsAreRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> NavigationServiceRuleComplianceTest.this.service
                            .resolveNominatedDestination(null, Route.SIGN_ON))
                    .withMessage("context must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> NavigationServiceRuleComplianceTest.this.service
                            .resolveNominatedDestination(ConversationState.empty(), null))
                    .withMessage("callerDefault must not be null");
        }

        @Test
        @DisplayName("sign-off honours a nomination when the destination field names one")
        void signOffHonoursANomination() {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveSignOffRoute(contextToProgram(USER_MENU_PROGRAM)))
                    .isEqualTo(Route.USER_MENU);
        }

        @Test
        @DisplayName("sign-off nominating the sign-on program reaches sign-on, the verified menu path")
        void signOffNominatingSignOnReachesSignOn() {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveSignOffRoute(contextToProgram(SIGN_ON_PROGRAM)))
                    .as("app/cbl/COMEN01C.cbl lines 97 and 173, app/cbl/COADM01C.cbl lines 97 and 163")
                    .isEqualTo(Route.SIGN_ON);
        }

        @ParameterizedTest(name = "field [{0}]")
        @ValueSource(strings = {"", " ", "        ", "\u0000"})
        @DisplayName("sign-off falls back to sign-on when the destination field nominates nothing, a "
                + "fallback fixed for the two menu programs and for them only")
        void signOffFallsBackToSignOn(final String toProgram) {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveSignOffRoute(contextToProgram(toProgram)))
                    .isEqualTo(Route.SIGN_ON);
        }

        @Test
        @DisplayName("sign-off abends on an unresolvable destination field rather than falling back, "
                + "because the fixed fallback belongs to the blank arm and not to the failing one")
        void signOffAbendsOnAnUnresolvableDestinationField() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> NavigationServiceRuleComplianceTest.this.service
                            .resolveSignOffRoute(contextToProgram(UNREGISTERED_PROGRAM)))
                    .as("sign-off delegates to the nominated-destination rule, so that is the rule the "
                            + "abend names - there is no separate sign-off rule to name")
                    .withMessageContaining("RULE nominated-destination");
        }

        @Test
        @DisplayName("sign-off with an absent destination field falls back to sign-on")
        void signOffWithAnAbsentFieldFallsBackToSignOn() {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveSignOffRoute(ConversationState.empty()))
                    .isEqualTo(Route.SIGN_ON);
        }

        @Test
        @DisplayName("sign-off rejects an absent context")
        void signOffRejectsAnAbsentContext() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> NavigationServiceRuleComplianceTest.this.service.resolveSignOffRoute(null))
                    .withMessage("context must not be null");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("forward dispatch from a menu - two gates, in source order")
    class ForwardDispatchFromAMenu {

        @ParameterizedTest(name = "name [{0}]")
        @ValueSource(strings = {"DUMMY", "DUMMY000", "DUMMYPGM", "DUMMYXYZ", "DUMMY   "})
        @DisplayName("a catalogued name whose first five characters are the suppression literal "
                + "suppresses dispatch")
        void theSuppressionLiteralSuppressesDispatch(final String catalogProgramName) {
            assertThat(NavigationServiceRuleComplianceTest.this.service.isDispatchSuppressed(catalogProgramName))
                    .isTrue();
        }

        @ParameterizedTest(name = "name [{0}]")
        @ValueSource(strings = {"DUMM", "DUM", "dummy", "Dummy", "DUMMy", " DUMMY", "", " ",
            "COMEN01C", "XDUMMY"})
        @DisplayName("anything else does not suppress dispatch, including a blank name and a lower-cased "
                + "literal, because the legacy comparison applies no case fold")
        void anythingElseDoesNotSuppressDispatch(final String catalogProgramName) {
            assertThat(NavigationServiceRuleComplianceTest.this.service.isDispatchSuppressed(catalogProgramName))
                    .isFalse();
        }

        @Test
        @DisplayName("an absent catalogued name does not suppress dispatch, faithfully: it does not begin "
                + "with the literal, so the legacy guard would let dispatch proceed")
        void anAbsentNameDoesNotSuppressDispatch() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.isDispatchSuppressed(null)).isFalse();
        }

        @Test
        @DisplayName("a standard user selecting an administrator-only entry trips the gate")
        void aStandardUserSelectingAnAdministratorOnlyEntryTripsTheGate() {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .isAdminOnlyOptionDenied(UserType.USER, "A")).isTrue();
        }

        @Test
        @DisplayName("an administrator selecting an administrator-only entry does not trip the gate")
        void anAdministratorSelectingAnAdministratorOnlyEntryDoesNotTripTheGate() {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .isAdminOnlyOptionDenied(UserType.ADMIN, "A")).isFalse();
        }

        @ParameterizedTest(name = "option code [{0}]")
        @ValueSource(strings = {"U", "a", "X", "", " ", "AA"})
        @DisplayName("a standard user selecting anything other than the administrator code does not trip "
                + "the gate")
        void aStandardUserSelectingAnyOtherCodeDoesNotTripTheGate(final String optionUserTypeCode) {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .isAdminOnlyOptionDenied(UserType.USER, optionUserTypeCode)).isFalse();
        }

        @Test
        @DisplayName("an absent option code trips nothing and an absent selecting type trips nothing")
        void absentArgumentsTripNothing() {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .isAdminOnlyOptionDenied(UserType.USER, null)).isFalse();
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .isAdminOnlyOptionDenied(null, "A")).isFalse();
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .isAdminOnlyOptionDenied(null, null)).isFalse();
        }

        @Test
        @DisplayName("the first gate condition is the standard-user condition and not the negation of the "
                + "administrator one, so an undeclared type does not trip it")
        void theFirstGateConditionIsTheStandardUserCondition() {
            for (final UserType userType : EnumSet.allOf(UserType.class)) {
                assertThat(NavigationServiceRuleComplianceTest.this.service
                        .isAdminOnlyOptionDenied(userType, "A"))
                        .as("%s selecting an administrator-only entry", userType)
                        .isEqualTo(userType == UserType.USER);
            }
            assertThat(NavigationServiceRuleComplianceTest.this.service.isAdminOnlyOptionDenied(null, "A"))
                    .as("an undeclared type satisfies neither condition name, so it is not denied")
                    .isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.service.NavigationServiceRuleComplianceTest#theDestinationStateTable")
        @DisplayName("a standard user dispatches to every destination a catalogued name reaches")
        void aStandardUserDispatchesToEveryCataloguedDestination(final Route route,
                final String routeValue, final String legacyTransactionId,
                final String legacyProgramName, final boolean adminScoped) {

            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveMenuDispatch(UserType.USER, "U", legacyProgramName))
                    .as("the entry names %s", routeValue)
                    .contains(route);
            assertThat(legacyTransactionId).hasSize(TRANSACTION_ID_WIDTH);
            assertThat(route.isAdminScoped()).isEqualTo(adminScoped);
        }

        @Test
        @DisplayName("the administrator-only gate is evaluated first and short-circuits, so a valid "
                + "program name is never reached")
        void theAdministratorOnlyGateIsEvaluatedFirst() {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveMenuDispatch(UserType.USER, "A", ACCOUNT_VIEW_PROGRAM))
                    .as("the name resolves, but the gate denied the selection before the lookup")
                    .isEmpty();
        }

        @Test
        @DisplayName("the administrator-only gate precedes the suppression guard in source order")
        void theAdministratorOnlyGatePrecedesTheSuppressionGuard() {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveMenuDispatch(UserType.USER, "A", "DUMMYXYZ"))
                    .isEmpty();
        }

        @Test
        @DisplayName("suppression yields no destination, because the legacy program re-presents the menu "
                + "instead of transferring control")
        void suppressionYieldsNoDestination() {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveMenuDispatch(UserType.USER, "U", "DUMMYXYZ"))
                    .isEmpty();
        }

        @Test
        @DisplayName("an unrecognised catalogued name yields no destination")
        void anUnrecognisedCataloguedNameYieldsNoDestination() {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveMenuDispatch(UserType.USER, "U", UNREGISTERED_PROGRAM))
                    .isEmpty();
        }

        @Test
        @DisplayName("an absent catalogued name yields no destination and does not throw")
        void anAbsentCataloguedNameYieldsNoDestination() {
            assertThatNoException().isThrownBy(() -> NavigationServiceRuleComplianceTest.this.service
                    .resolveMenuDispatch(UserType.USER, "U", null));
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveMenuDispatch(UserType.USER, "U", null))
                    .isEmpty();
        }

        @Test
        @DisplayName("an administrator dispatches through the same path, since the gate does not trip for "
                + "the administrator type")
        void anAdministratorDispatchesThroughTheSamePath() {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveMenuDispatch(UserType.ADMIN, "A", USER_LIST_PROGRAM))
                    .contains(Route.USER_LIST);
        }

        @Test
        @DisplayName("an undeclared selecting type dispatches, because the gate tests the standard-user "
                + "condition and not the negation of the administrator one")
        void anUndeclaredSelectingTypeDispatches() {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveMenuDispatch(null, "A", ACCOUNT_VIEW_PROGRAM))
                    .as("widening the gate to not-an-administrator would deny what the legacy permits")
                    .contains(Route.ACCOUNT_VIEW);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.service.NavigationServiceRuleComplianceTest#theDestinationStateTable")
        @DisplayName("the administrative menu dispatches to every destination a catalogued name reaches, "
                + "applying no user-type gate at all")
        void theAdministrativeMenuDispatchesWithNoUserTypeGate(final Route route,
                final String routeValue, final String legacyTransactionId,
                final String legacyProgramName, final boolean adminScoped) {

            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveAdminMenuDispatch(legacyProgramName))
                    .as("the entry names %s", routeValue)
                    .contains(route);
            assertThat(legacyTransactionId).hasSize(TRANSACTION_ID_WIDTH);
            assertThat(route.isAdminScoped()).isEqualTo(adminScoped);
        }

        @Test
        @DisplayName("the administrative menu applies the suppression guard")
        void theAdministrativeMenuAppliesTheSuppressionGuard() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveAdminMenuDispatch("DUMMYXYZ"))
                    .isEmpty();
        }

        @Test
        @DisplayName("the administrative menu yields no destination for an unrecognised or absent name")
        void theAdministrativeMenuYieldsNoDestinationForAnUnusableName() {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveAdminMenuDispatch(UNREGISTERED_PROGRAM)).isEmpty();
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveAdminMenuDispatch(null)).isEmpty();
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveAdminMenuDispatch(DANGLING_PROGRAM_DEFINITION)).isEmpty();
        }

        @Test
        @DisplayName("the administrative menu tolerates the trailing padding of a catalogued name")
        void theAdministrativeMenuToleratesTrailingPadding() {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveAdminMenuDispatch(USER_LIST_PROGRAM + " "))
                    .contains(Route.USER_LIST);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the four route lookups")
    class TheRouteLookups {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.service.NavigationServiceRuleComplianceTest#theDestinationStateTable")
        @DisplayName("each legacy program name, transaction identifier and wire value resolves to its own "
                + "destination")
        void eachIdentifierResolvesToItsOwnDestination(final Route route, final String routeValue,
                final String legacyTransactionId, final String legacyProgramName,
                final boolean adminScoped) {

            assertThat(NavigationServiceRuleComplianceTest.this.service.routeForLegacyProgram(legacyProgramName))
                    .contains(route);
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .routeForLegacyTransactionId(legacyTransactionId))
                    .contains(route);
            assertThat(NavigationServiceRuleComplianceTest.this.service.routeForValue(routeValue)).contains(route);
            assertThat(route.isAdminScoped()).isEqualTo(adminScoped);
        }

        @Test
        @DisplayName("a legacy program name resolves with its trailing padding tolerated")
        void aLegacyProgramNameResolvesWithPaddingTolerated() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.routeForLegacyProgram("COSGN00C  "))
                    .contains(Route.SIGN_ON);
            assertThat(NavigationServiceRuleComplianceTest.this.service.routeForLegacyProgram("COSGN00C\u0000"))
                    .contains(Route.SIGN_ON);
        }

        @Test
        @DisplayName("a legacy transaction identifier resolves with its trailing padding tolerated")
        void aLegacyTransactionIdentifierResolvesWithPaddingTolerated() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.routeForLegacyTransactionId("CC00  "))
                    .contains(Route.SIGN_ON);
            assertThat(NavigationServiceRuleComplianceTest.this.service.routeForLegacyTransactionId("CU03\u0000"))
                    .contains(Route.USER_DELETE);
        }

        @ParameterizedTest(name = "value [{0}]")
        @ValueSource(strings = {"sign-on ", " sign-on", "sign-on\u0000", "SIGN-ON", "sign_on",
            "signon", ""})
        @DisplayName("a wire value is matched exactly: its surrounding white space is a difference from "
                + "the published token and not padding to tolerate")
        void aWireValueIsMatchedExactly(final String routeValue) {
            assertThat(NavigationServiceRuleComplianceTest.this.service.routeForValue(routeValue)).isEmpty();
        }

        @Test
        @DisplayName("every lookup answers empty for an absent argument rather than throwing")
        void everyLookupAnswersEmptyForAnAbsentArgument() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.routeForLegacyProgram(null)).isEmpty();
            assertThat(NavigationServiceRuleComplianceTest.this.service.routeForLegacyTransactionId(null)).isEmpty();
            assertThat(NavigationServiceRuleComplianceTest.this.service.routeForValue(null)).isEmpty();
        }

        @Test
        @DisplayName("every lookup answers empty for a blank argument")
        void everyLookupAnswersEmptyForABlankArgument() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.routeForLegacyProgram("        ")).isEmpty();
            assertThat(NavigationServiceRuleComplianceTest.this.service.routeForLegacyProgram("")).isEmpty();
            assertThat(NavigationServiceRuleComplianceTest.this.service.routeForLegacyTransactionId("    ")).isEmpty();
            assertThat(NavigationServiceRuleComplianceTest.this.service.routeForValue("")).isEmpty();
        }

        @ParameterizedTest(name = "name [{0}]")
        @ValueSource(strings = {UNREGISTERED_PROGRAM, DANGLING_PROGRAM_DEFINITION, "cosgn00c",
            "COSGN00", "COSGN00CX", " COSGN00C"})
        @DisplayName("a program name that differs other than by trailing padding resolves to nothing, "
                + "which is the intended outcome")
        void aProgramNameThatDiffersResolvesToNothing(final String legacyProgramName) {
            assertThat(NavigationServiceRuleComplianceTest.this.service.routeForLegacyProgram(legacyProgramName))
                    .isEmpty();
        }

        @ParameterizedTest(name = "identifier [{0}]")
        @ValueSource(strings = {"cc00", "CC0", "CC000", "XX00", " CC00"})
        @DisplayName("a transaction identifier that differs other than by trailing padding resolves to "
                + "nothing")
        void aTransactionIdentifierThatDiffersResolvesToNothing(final String legacyTransactionId) {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .routeForLegacyTransactionId(legacyTransactionId)).isEmpty();
        }

        @Test
        @DisplayName("the two legacy lookups are independent: a program name is not a transaction "
                + "identifier and vice versa")
        void theTwoLegacyLookupsAreIndependent() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.routeForLegacyTransactionId(SIGN_ON_PROGRAM))
                    .isEmpty();
            assertThat(NavigationServiceRuleComplianceTest.this.service.routeForLegacyProgram("CC00")).isEmpty();
        }

        @Test
        @DisplayName("the wire-value lookup is the exact inverse of the published wire value")
        void theWireValueLookupIsTheExactInverse() {
            for (final Route route : Route.values()) {
                assertThat(NavigationServiceRuleComplianceTest.this.service.routeForValue(route.getRouteValue()))
                        .as("%s", route)
                        .contains(route);
            }
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("diagnostic integrity - no client-echoed value reaches a log record unrenderable")
    class DiagnosticIntegrity {

        /** The logger of the class under test, captured so its diagnostics can be inspected. */
        private Logger subjectLogger;

        /** Records every diagnostic the class under test emits while a test runs. */
        private ListAppender<ILoggingEvent> logRecorder;

        /** The logger's level before this test pinned it, restored afterwards. */
        private Level originalLevel;

        @BeforeEach
        void attachLogRecorder() {
            // The honoured, empty and suppressed paths all log at debug, so capture must not depend on
            // whatever level the ambient logging configuration happens to set. The level is pinned for
            // the duration of the test and restored in the matching teardown.
            this.subjectLogger = (Logger) LoggerFactory.getLogger(NavigationService.class);
            this.originalLevel = this.subjectLogger.getLevel();
            this.logRecorder = new ListAppender<>();
            this.logRecorder.setContext(this.subjectLogger.getLoggerContext());
            this.logRecorder.start();
            this.subjectLogger.addAppender(this.logRecorder);
            this.subjectLogger.setLevel(Level.TRACE);
        }

        @AfterEach
        void detachLogRecorder() {
            this.subjectLogger.detachAppender(this.logRecorder);
            this.logRecorder.stop();
            this.subjectLogger.setLevel(this.originalLevel);
        }

        /**
         * Asserts that nothing the class logged carries a character a log record must not carry.
         *
         * @param context what was exercised, for the failure message
         */
        private void assertEveryLogRecordIsSafe(final String context) {
            assertThat(this.logRecorder.list)
                    .as("%s must have produced at least one diagnostic to inspect", context)
                    .isNotEmpty();
            for (final ILoggingEvent event : this.logRecorder.list) {
                assertSafeToLog(event.getFormattedMessage(), context + " log record");
            }
        }

        /**
         * Returns the single log record matching a marker, failing when none or several do.
         *
         * @param marker text the wanted record contains
         * @return the formatted message of the matching record
         */
        private String theRecordContaining(final String marker) {
            final List<String> matching = new ArrayList<>();
            for (final ILoggingEvent event : this.logRecorder.list) {
                if (event.getFormattedMessage().contains(marker)) {
                    matching.add(event.getFormattedMessage());
                }
            }

            assertThat(matching)
                    .as("exactly one diagnostic should have reported %s", marker)
                    .hasSize(1);
            return matching.get(0);
        }

        @ParameterizedTest(name = "code point {1} at position {2}")
        @MethodSource("com.carddemo.service.NavigationServiceRuleComplianceTest#hostileProgramNames")
        @DisplayName("a control character in the originating-program field is described rather than "
                + "reproduced by the abending unrecognised-name arm")
        void aControlCharacterInTheOriginatingFieldIsNeverReproduced(final String fromProgram,
                final int codePoint, final int position) {

            assertThatExceptionOfType(AbendException.class)
                    .as("a name that is neither blank nor resolvable is a failure, not a fallback")
                    .isThrownBy(() -> NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                            contextFromProgram(fromProgram), Route.USER_MENU))
                    .withMessageContaining("RULE back-navigation");

            final String record = theRecordContaining("Nominated program names no reachable destination");
            assertDegradedAt(record, position, codePoint);
            assertThat(record)
                    .as("the raw value must not survive into the log record")
                    .doesNotContain(fromProgram);
            assertThat(record).contains("rule=back-navigation");
            assertEveryLogRecordIsSafe("back navigation");
        }

        @ParameterizedTest(name = "code point {1} at position {2}")
        @MethodSource("com.carddemo.service.NavigationServiceRuleComplianceTest#hostileProgramNames")
        @DisplayName("a control character in the destination-program field is described rather than "
                + "reproduced by the abending unrecognised-name arm")
        void aControlCharacterInTheDestinationFieldIsNeverReproduced(final String toProgram,
                final int codePoint, final int position) {

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> NavigationServiceRuleComplianceTest.this.service.resolveNominatedDestination(
                            contextToProgram(toProgram), Route.CARD_LIST))
                    .withMessageContaining("RULE nominated-destination");

            final String record = theRecordContaining("Nominated program names no reachable destination");
            assertDegradedAt(record, position, codePoint);
            assertThat(record).doesNotContain(toProgram);
            assertThat(record).contains("rule=nominated-destination");
            assertEveryLogRecordIsSafe("nominated destination");
        }

        @ParameterizedTest(name = "code point {1} at position {2}")
        @MethodSource("com.carddemo.service.NavigationServiceRuleComplianceTest#hostileProgramNames")
        @DisplayName("a control character in a catalogued name is described rather than reproduced when "
                + "the user menu reports that it reaches nothing")
        void aControlCharacterInACataloguedNameIsNeverReproduced(final String catalogProgramName,
                final int codePoint, final int position) {

            final Optional<Route> resolved = NavigationServiceRuleComplianceTest.this.service
                    .resolveMenuDispatch(UserType.USER, "U", catalogProgramName);

            assertThat(resolved).isEmpty();
            final String record = theRecordContaining("Menu entry names no reachable destination");
            assertDegradedAt(record, position, codePoint);
            assertThat(record).doesNotContain(catalogProgramName);
            assertThat(record).contains("rule=user-menu-dispatch");
            assertEveryLogRecordIsSafe("user menu dispatch");
        }

        @ParameterizedTest(name = "code point {1} at position {2}")
        @MethodSource("com.carddemo.service.NavigationServiceRuleComplianceTest#hostileProgramNames")
        @DisplayName("a control character in a catalogued name is described rather than reproduced when "
                + "the administrative menu reports that it reaches nothing")
        void aControlCharacterIsNeverReproducedByTheAdministrativeMenu(final String catalogProgramName,
                final int codePoint, final int position) {

            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveAdminMenuDispatch(catalogProgramName)).isEmpty();

            final String record = theRecordContaining("Menu entry names no reachable destination");
            assertDegradedAt(record, position, codePoint);
            assertThat(record).doesNotContain(catalogProgramName);
            assertThat(record).contains("rule=admin-menu-dispatch");
            assertEveryLogRecordIsSafe("administrative menu dispatch");
        }

        @ParameterizedTest(name = "code point {1} at position {2}")
        @MethodSource("com.carddemo.service.NavigationServiceRuleComplianceTest#hostileSuppressedNames")
        @DisplayName("a control character after the suppression literal is described rather than "
                + "reproduced when the suppressed arm reports it")
        void aControlCharacterAfterTheSuppressionLiteralIsNeverReproduced(
                final String catalogProgramName, final int codePoint, final int position) {

            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveMenuDispatch(UserType.USER, "U", catalogProgramName))
                    .as("suppression is unaffected by the rendering rule")
                    .isEmpty();

            final String record = theRecordContaining("Menu dispatch suppressed");
            assertDegradedAt(record, position, codePoint);
            assertThat(record).doesNotContain(catalogProgramName);
            assertEveryLogRecordIsSafe("suppressed dispatch");
        }

        /**
         * The honoured arm publishes the destination table's own name, not the caller's text.
         *
         * <p>The only way a resolvable name can still carry an unrenderable character is a trailing low
         * value, which the padding strip removes before the lookup but which is still present in the
         * value a naive diagnostic would mention. The published contract bounds this field at eight
         * characters and does not enforce the bound at construction, so the service must be safe for a
         * longer value too.</p>
         *
         * <p>It is safe by a stronger mechanism than describing the offender. Reaching the honoured arm
         * means the nomination matched an entry of the fixed destination table, so the canonical name
         * from that table is available and is published in place of the caller's text. Nothing of the
         * caller's value reaches the record at all, which is why no description of an offending
         * character appears either: there is no offending character left to describe.</p>
         */
        @Test
        @DisplayName("the honoured arm publishes the canonical table name rather than the caller's text, "
                + "so a trailing low value never reaches the record in any form")
        void theHonouredArmPublishesTheCanonicalNameRatherThanTheCallersText() {
            final String nominated = BILL_PAYMENT_PROGRAM + "\u0000";

            final Route resolved = NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                    contextFromProgram(nominated), Route.SIGN_ON);

            assertThat(resolved)
                    .as("the trailing low value is padding, so the name still resolves")
                    .isEqualTo(Route.BILL_PAYMENT);
            final String record = theRecordContaining("Nomination honoured");
            assertThat(record)
                    .as("the canonical name is published, and it is inherently printable")
                    .contains("nominatedProgram=" + BILL_PAYMENT_PROGRAM)
                    .contains("route=bill-payment")
                    .doesNotContain(nominated)
                    .doesNotContain("not printable US-ASCII");
            assertEveryLogRecordIsSafe("the honoured arm");
        }

        @Test
        @DisplayName("rendering never changes resolution: the same name with and without a trailing low "
                + "value reaches the same destination")
        void renderingNeverChangesResolution() {
            assertThat(NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                    contextFromProgram(BILL_PAYMENT_PROGRAM + "\u0000"), Route.SIGN_ON))
                    .isEqualTo(NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                            contextFromProgram(BILL_PAYMENT_PROGRAM), Route.SIGN_ON));
        }

        /**
         * A printable but unrecognised name is not reproduced either, and the reason is provenance.
         *
         * <p>It would be tempting to reproduce a value every character of which is printable, on the
         * grounds that such a value cannot split a log record. The failing arm declines to, because the
         * value is wholly caller-controlled and names no destination this estate has: reproducing it
         * would put an attacker-chosen token into a durable record on the one path a client can reach by
         * sending something no screen ever sends. What is published instead is the length, which
         * distinguishes a truncated or padded field from a misspelled one - enough to act on, and
         * nothing an attacker chooses.</p>
         *
         * <p>The bounded value itself still travels on the abend, where it is structured data on an
         * exception rather than text a reader parses by line.</p>
         */
        @Test
        @DisplayName("a printable but unrecognised value is described by its length rather than "
                + "reproduced, because the failing arm is wholly caller-controlled")
        void aPrintableUnrecognisedValueIsDescribedByItsLength() {
            assertThatExceptionOfType(AbendException.class).isThrownBy(() ->
                    NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                            contextFromProgram(UNREGISTERED_PROGRAM), Route.USER_MENU));

            final String record = theRecordContaining("Nominated program names no reachable destination");
            assertThat(record)
                    .contains("<unrecognised, length " + UNREGISTERED_PROGRAM.length() + ">")
                    .doesNotContain(UNREGISTERED_PROGRAM)
                    .doesNotContain("not printable US-ASCII");
            assertEveryLogRecordIsSafe("a printable unrecognised name");
        }

        @Test
        @DisplayName("the bounded nomination travels on the abend as structured data, so an operator can "
                + "still read what was sent without it having been written into a log line")
        void theBoundedNominationTravelsOnTheAbend() {
            assertThatExceptionOfType(AbendException.class).isThrownBy(() ->
                    NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                            contextFromProgram(UNREGISTERED_PROGRAM), Route.USER_MENU))
                    .satisfies(abend -> {
                        assertThat(abend.culprit()).isEqualTo(UNREGISTERED_PROGRAM);
                        assertThat(abend.reason()).contains("UNRESOLVABLE PROGRAM NAME");
                    });
        }

        @Test
        @DisplayName("a printable value on the honoured arm is rendered verbatim too")
        void aPrintableValueOnTheHonouredArmIsRenderedVerbatim() {
            NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                    contextFromProgram(BILL_PAYMENT_PROGRAM), Route.SIGN_ON);

            assertThat(theRecordContaining("Nomination honoured"))
                    .contains("nominatedProgram=" + BILL_PAYMENT_PROGRAM)
                    .contains("route=bill-payment")
                    .doesNotContain("not printable US-ASCII");
        }

        @Test
        @DisplayName("an absent catalogued name renders as a fixed stand-in that no real name can be "
                + "mistaken for")
        void anAbsentCataloguedNameRendersAsAFixedStandIn() {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveMenuDispatch(UserType.USER, "U", null)).isEmpty();

            assertThat(theRecordContaining("Menu entry names no reachable destination"))
                    .contains("catalogProgramName=(absent)")
                    .doesNotContain("catalogProgramName=null");
            assertEveryLogRecordIsSafe("an absent catalogued name");
        }

        @Test
        @DisplayName("the administrator-only gate describes the option code it denied, which the gate "
                + "itself confines to the administrator character")
        void theAdministratorOnlyGateDescribesTheOptionCode() {
            assertThat(NavigationServiceRuleComplianceTest.this.service
                    .resolveMenuDispatch(UserType.USER, "A", ACCOUNT_VIEW_PROGRAM)).isEmpty();

            assertThat(theRecordContaining("Menu dispatch denied"))
                    .contains("rule=user-menu-admin-only-gate")
                    .contains("userType=USER")
                    .contains("optionUserType=A");
            assertEveryLogRecordIsSafe("the administrator-only gate");
        }

        @Test
        @DisplayName("the blank-nomination arm names the rule and the destination and mentions no value "
                + "at all, because there is none to mention")
        void theBlankNominationArmMentionsNoValue() {
            NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                    ConversationState.empty(), Route.USER_MENU);

            assertThat(theRecordContaining("Nomination empty"))
                    .contains("rule=back-navigation")
                    .contains("route=user-menu")
                    .doesNotContain("nominatedProgram");
            assertEveryLogRecordIsSafe("the blank-nomination arm");
        }

        @Test
        @DisplayName("the two rules that share the nomination helper are distinguishable in the record "
                + "they produce")
        void theTwoNominationRulesAreDistinguishableInTheRecord() {
            toleratingAbend(() -> NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                    contextFromProgram(UNREGISTERED_PROGRAM), Route.USER_MENU));
            toleratingAbend(() -> NavigationServiceRuleComplianceTest.this.service.resolveNominatedDestination(
                    contextToProgram(UNREGISTERED_PROGRAM), Route.SIGN_ON));

            final List<String> rules = new ArrayList<>();
            for (final ILoggingEvent event : this.logRecorder.list) {
                if (event.getFormattedMessage().contains("rule=back-navigation")) {
                    rules.add("back-navigation");
                }
                if (event.getFormattedMessage().contains("rule=nominated-destination")) {
                    rules.add("nominated-destination");
                }
            }

            assertThat(rules).containsExactly("back-navigation", "nominated-destination");
        }

        @Test
        @DisplayName("the whole public surface driven with hostile input produces no log record carrying "
                + "an unrenderable character")
        void thePublicSurfaceProducesNoUnrenderableLogRecord() {
            for (final String hostile : hostileValueBattery()) {
                NavigationServiceRuleComplianceTest.this.service.resolveSignOnRouteForUserTypeCode(hostile);
                toleratingAbend(() -> NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                        contextFromProgram(hostile), Route.USER_MENU));
                toleratingAbend(() -> NavigationServiceRuleComplianceTest.this.service.resolveAttentionKeyRoute(
                        KeyAction.PFK03, contextFromProgram(hostile), Route.USER_MENU));
                NavigationServiceRuleComplianceTest.this.service.resolveAttentionKeyRoute(
                        KeyAction.ENTER, contextFromProgram(hostile), Route.USER_MENU);
                toleratingAbend(() -> NavigationServiceRuleComplianceTest.this.service.resolveNominatedDestination(
                        contextToProgram(hostile), Route.SIGN_ON));
                toleratingAbend(() -> NavigationServiceRuleComplianceTest.this.service.resolveSignOffRoute(contextToProgram(hostile)));
                NavigationServiceRuleComplianceTest.this.service.resolveMenuDispatch(UserType.USER, "U", hostile);
                NavigationServiceRuleComplianceTest.this.service.resolveMenuDispatch(UserType.USER, "A", hostile);
                NavigationServiceRuleComplianceTest.this.service.resolveMenuDispatch(UserType.ADMIN, hostile, hostile);
                NavigationServiceRuleComplianceTest.this.service.resolveAdminMenuDispatch(hostile);
                NavigationServiceRuleComplianceTest.this.service.resolveMenuDispatch(
                        UserType.USER, "U", NavigationService.DUMMY_PROGRAM_PREFIX + hostile);
                NavigationServiceRuleComplianceTest.this.service.routeForLegacyProgram(hostile);
                NavigationServiceRuleComplianceTest.this.service.routeForLegacyTransactionId(hostile);
                NavigationServiceRuleComplianceTest.this.service.routeForValue(hostile);
            }

            assertEveryLogRecordIsSafe("the whole public surface");
        }

        /**
         * Runs a call that may legitimately abend, discarding the abend and nothing else.
         *
         * <p>The structural sweep is about what reaches a log record, not about which arm was taken, and
         * two of the arms it drives abend by design. Any other throwable propagates, so the sweep still
         * fails on an unexpected failure.</p>
         *
         * @param call the call to run
         */
        private void toleratingAbend(final Runnable call) {
            try {
                call.run();
            } catch (final AbendException expected) {
                // The unresolvable-nomination arm abends by design; this sweep asserts about the record
                // it wrote on the way out, which the caller inspects afterwards.
            }
        }

        /**
         * Hostile input divides the surface in two, and the division is the legacy one.
         *
         * <p>The menu rules resolve a catalogued entry name and yield no destination when it names
         * nothing, because the legacy menu programs test the entry and redisplay the screen rather than
         * transferring; those paths must therefore tolerate anything. The two nomination rules stand for
         * a transfer-control command that would have been attempted, so a name the region cannot resolve
         * is a failure on the mainframe and is a failure here. Asserting both halves in one test would
         * have required one of them to be wrong.</p>
         */
        @Test
        @DisplayName("the menu and lookup surfaces driven with hostile input never fail, because those "
                + "legacy paths redisplay a screen rather than transferring control")
        void theMenuAndLookupSurfacesNeverFailOnHostileInput() {
            for (final String hostile : hostileValueBattery()) {
                assertThatNoException()
                        .as("hostile value at code point %d", (int) hostile.charAt(0))
                        .isThrownBy(() -> {
                            NavigationServiceRuleComplianceTest.this.service.resolveSignOnRouteForUserTypeCode(hostile);
                            NavigationServiceRuleComplianceTest.this.service.resolveMenuDispatch(
                                    UserType.USER, "U", hostile);
                            NavigationServiceRuleComplianceTest.this.service.resolveMenuDispatch(
                                    UserType.ADMIN, hostile, hostile);
                            NavigationServiceRuleComplianceTest.this.service.resolveAdminMenuDispatch(hostile);
                            NavigationServiceRuleComplianceTest.this.service.routeForLegacyProgram(hostile);
                            NavigationServiceRuleComplianceTest.this.service.routeForLegacyTransactionId(hostile);
                            NavigationServiceRuleComplianceTest.this.service.routeForValue(hostile);
                        });
            }
        }

        @Test
        @DisplayName("the two nomination surfaces abend on hostile input, and the abend message names "
                + "the rule and carries nothing a log record must not carry")
        void theTwoNominationSurfacesAbendOnHostileInput() {
            for (final String hostile : hostileValueBattery()) {
                assertThatExceptionOfType(AbendException.class)
                        .as("hostile value at code point %d", (int) hostile.charAt(0))
                        .isThrownBy(() -> NavigationServiceRuleComplianceTest.this.service.resolveBackNavigation(
                                contextFromProgram(hostile), Route.USER_MENU))
                        .satisfies(abend -> assertSafeToLog(abend.getMessage(), "the abend message"))
                        .withMessageContaining("RULE back-navigation");
                assertThatExceptionOfType(AbendException.class)
                        .isThrownBy(() -> NavigationServiceRuleComplianceTest.this.service.resolveSignOffRoute(
                                contextToProgram(hostile)))
                        .satisfies(abend -> assertSafeToLog(abend.getMessage(), "the abend message"))
                        .withMessageContaining("RULE nominated-destination");
            }
        }
    }

    // =============================================================================================

    /**
     * Hostile program names that reach the unrecognised-name arm, with the position and code point the
     * diagnostic must report.
     *
     * <p>Supplied through a method source rather than a comma-separated literal source because a
     * comma-separated source mangles control characters, which is precisely what these rows carry. Each
     * name survives the blank test - it holds at least one character that is neither a space nor a low
     * value - and survives the padding strip, so it reaches the lookup intact and fails to match.
     *
     * @return one row per hostile name: the name, its offending code point and its zero-based position
     */
    static Stream<Arguments> hostileProgramNames() {
        return Stream.of(
                Arguments.of("COBIL0\n0", 10, 6),
                Arguments.of("CO\rBIL00", 13, 2),
                Arguments.of("AB\u0000CD", 0, 2),
                Arguments.of("COBIL00\t", 9, 7),
                Arguments.of("\u001bCOBIL0", 27, 0),
                Arguments.of("COBIL0\u007f", 127, 6),
                Arguments.of("COBIL0\u00e9", 233, 6),
                Arguments.of("\nCOBIL00", 10, 0),
                Arguments.of("COBIL00\n", 10, 7));
    }

    /**
     * Hostile catalogued names that begin with the suppression literal, so the suppressed arm reports
     * them.
     *
     * @return one row per hostile name: the name, its offending code point and its zero-based position
     */
    static Stream<Arguments> hostileSuppressedNames() {
        return Stream.of(
                Arguments.of("DUMMY\nX", 10, 5),
                Arguments.of("DUMMY\r", 13, 5),
                Arguments.of("DUMMY\u0000A", 0, 5),
                Arguments.of("DUMMY\u007f", 127, 5),
                Arguments.of("DUMMY\u00e9", 233, 5));
    }

    /**
     * Every hostile value the structural sweep drives through the whole public surface.
     *
     * <p>Each begins with a character that is neither a space nor a low value, so none is treated as
     * blank and every one reaches a diagnostic.
     *
     * @return the hostile battery
     */
    static List<String> hostileValueBattery() {
        return List.of("A\n", "A\r", "A\u0000B", "A\t", "A\u001b", "A\u007f", "A\u00e9",
                "A\u0007", "A\u000b", "A\u001f", "A\u0080", "A\u00ff", "A\u2028");
    }

    /**
     * The complete destination state table: one row per dispatchable transaction definition.
     *
     * <p>Hand-written from {@code app/csd/CARDDEMO.CSD} rather than derived from the enumeration, so a
     * change to any wire value, legacy identifier or administrative flag fails a test instead of
     * silently agreeing with itself.
     *
     * <p>Seventeen rows for eighteen registrations. The registration that has no row is {@code CDV1},
     * which is bound to the program definition that has no source member, so there is no destination
     * to tabulate; that absence is asserted directly rather than left as a missing row.
     *
     * @return one argument row per destination
     */
    static Stream<Arguments> theDestinationStateTable() {
        return Stream.of(
                Arguments.of(Route.SIGN_ON, "sign-on", "CC00", "COSGN00C", false),
                Arguments.of(Route.USER_MENU, "user-menu", "CM00", "COMEN01C", false),
                Arguments.of(Route.ADMIN_MENU, "admin-menu", "CA00", "COADM01C", true),
                Arguments.of(Route.ACCOUNT_VIEW, "account-view", "CAVW", "COACTVWC", false),
                Arguments.of(Route.ACCOUNT_UPDATE, "account-update", "CAUP", "COACTUPC", false),
                Arguments.of(Route.CARD_LIST, "card-list", "CCLI", "COCRDLIC", false),
                Arguments.of(Route.CARD_DETAIL, "card-detail", "CCDL", "COCRDSLC", false),
                Arguments.of(Route.CARD_UPDATE, "card-update", "CCUP", "COCRDUPC", false),
                Arguments.of(Route.TRANSACTION_LIST, "transaction-list", "CT00", "COTRN00C", false),
                Arguments.of(Route.TRANSACTION_VIEW, "transaction-view", "CT01", "COTRN01C", false),
                Arguments.of(Route.TRANSACTION_ADD, "transaction-add", "CT02", "COTRN02C", false),
                Arguments.of(Route.REPORT_REQUEST, "report-request", "CR00", "CORPT00C", false),
                Arguments.of(Route.BILL_PAYMENT, "bill-payment", "CB00", "COBIL00C", false),
                Arguments.of(Route.USER_LIST, "user-list", "CU00", "COUSR00C", true),
                Arguments.of(Route.USER_ADD, "user-add", "CU01", "COUSR01C", true),
                Arguments.of(Route.USER_UPDATE, "user-update", "CU02", "COUSR02C", true),
                Arguments.of(Route.USER_DELETE, "user-delete", "CU03", "COUSR03C", true));
    }
}
