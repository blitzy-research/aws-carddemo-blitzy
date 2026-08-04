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
import static org.junit.jupiter.api.Assertions.assertAll;

import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.exception.AbendException;
import com.carddemo.service.NavigationService.Route;
import com.carddemo.service.NavigationService.Routes;

/**
 * Unit tests for the route-constant navigation service: the whole of the legacy CardDemo dispatch
 * graph, and the one behavioural fact that governs every assertion below.
 *
 * <p><strong>There is no server-side forwarding in the target.</strong> The legacy estate handed
 * control between programs at 25 transfer-control sites and re-armed the next pseudo-conversational
 * turn at 19 return-with-transaction sites, spread across the 17 online programs. Neither survives
 * the migration. A transfer becomes a <em>route constant returned in the response body</em>, and a
 * re-arm becomes the client's next call. That is what makes every endpoint independently testable,
 * so it is what these tests prove: each rule hands back a value, and nothing downstream is entered
 * on the way.
 *
 * <p><strong>The vocabulary is checked against the resource definition, not against itself.</strong>
 * The expected table in {@link #routeVocabulary()} was transcribed by hand from the 18 transaction
 * definitions of {@code app/csd/CARDDEMO.CSD}, row by row, so it is an oracle independent of the
 * code under test. No expected route value, transaction identifier, program name or census figure in
 * this file was obtained by calling the service, and none is produced by any production formatter,
 * codec, template holder or record mapper. Every one is a literal stated here.
 *
 * <p><strong>Why 18 registrations yield 17 destinations.</strong> The resource definition binds
 * transaction {@code CDV1} to {@code PROGRAM(COCRDSEC)} at line 390, and declares that program at
 * line 211, but no source member of that name exists anywhere in the estate. The transaction
 * therefore cannot have been dispatchable, and the vocabulary publishes no destination for either
 * name. Both halves of that decision are asserted as required negatives in
 * {@link TheDanglingProgramDefinition}: no published constant names the dangling program, and every
 * lookup keyed on it or on its transaction yields the empty result rather than a fabricated route.
 *
 * <p><strong>Three routing cases, not two.</strong> Sign-on moves the persisted user type into the
 * communication area and then tests the administrator condition once. What follows is an
 * unconditional alternative rather than a second test, so every value that is not the administrator
 * code &mdash; the standard code, an absent code, and a code outside the two the estate declares
 * &mdash; reaches the user main menu. The third case is the one a switch without a default arm would
 * silently break, so it is driven from a parameterized table alongside the other two.
 *
 * <p><strong>Attention keys are read, never decoded, here.</strong> Turning a raw terminal
 * identifier into an action, including folding program-function keys 13 through 24 back onto 1
 * through 12, belongs to the utility-layer key translator and is tested with it. This file therefore
 * asserts only what the navigation rules do with an action that has already been decoded, and that
 * an absent action is treated as the unmapped case &mdash; which is the shape the legacy mapping
 * forces, because its 28-clause selection carries no catch-all and so leaves an unrecognised key
 * unassigned.
 *
 * <p><strong>Harness.</strong> A surefire unit test over a service with no collaborators and no
 * mutable state: the real instance is exercised throughout, no application context is started, no
 * container is launched, no connection is opened and no port is bound. {@link NoServerSideForwarding}
 * proves the absence of a forwarding path from the delivered type's own shape rather than from a
 * stand-in, and from the state a caller echoes back being unchanged by every rule in turn.
 *
 * <p><strong>Provenance.</strong> Checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec},
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Legacy behaviour is
 * described and cited by member and line; no legacy source statement is reproduced.
 */
@DisplayName("NavigationService: every legacy transfer of control becomes a route constant returned "
        + "to the client, and never a server-side forward")
final class NavigationServiceTest {

    // ----------------------------------------------------------------------------------------
    // The independent oracle: wire values, transcribed by hand and never read from the service.
    // ----------------------------------------------------------------------------------------

    private static final String WIRE_SIGN_ON = "sign-on";

    private static final String WIRE_USER_MENU = "user-menu";

    private static final String WIRE_ADMIN_MENU = "admin-menu";

    private static final String WIRE_ACCOUNT_VIEW = "account-view";

    private static final String WIRE_ACCOUNT_UPDATE = "account-update";

    private static final String WIRE_CARD_LIST = "card-list";

    private static final String WIRE_CARD_DETAIL = "card-detail";

    private static final String WIRE_CARD_UPDATE = "card-update";

    private static final String WIRE_TRANSACTION_LIST = "transaction-list";

    private static final String WIRE_TRANSACTION_VIEW = "transaction-view";

    private static final String WIRE_TRANSACTION_ADD = "transaction-add";

    private static final String WIRE_REPORT_REQUEST = "report-request";

    private static final String WIRE_BILL_PAYMENT = "bill-payment";

    private static final String WIRE_USER_LIST = "user-list";

    private static final String WIRE_USER_ADD = "user-add";

    private static final String WIRE_USER_UPDATE = "user-update";

    private static final String WIRE_USER_DELETE = "user-delete";

    /**
     * Destinations the vocabulary publishes: 17, one per dispatchable transaction definition.
     *
     * <p>Stated as a literal rather than read from the service, so that the service's own published
     * figure has something to be checked against. It is not 18: the definition bound to the dangling
     * program yields nothing dispatchable.
     */
    private static final int EXPECTED_DESTINATION_COUNT = 17;

    /** Transfer-control sites in the legacy estate, counted over non-comment lines of {@code app/cbl}. */
    private static final int EXPECTED_TRANSFER_SITE_COUNT = 25;

    /** Pseudo-conversational re-arm sites in the legacy estate, across the 17 online programs. */
    private static final int EXPECTED_REARM_SITE_COUNT = 19;

    /** Administrative destinations: the administrative menu and the four user-maintenance screens. */
    private static final int EXPECTED_ADMIN_DESTINATION_COUNT = 5;

    /** The five leading characters the legacy menu guard compares a catalogued program name against. */
    private static final String EXPECTED_SUPPRESSION_PREFIX = "DUMMY";

    /** The user-type code a menu catalogue entry carries to mark itself administrator-only. */
    private static final String EXPECTED_ADMIN_OPTION_CODE = "A";

    /** The declared administrator user-type code, from the communication-area condition name. */
    private static final String ADMIN_USER_TYPE_CODE = "A";

    /** The declared standard-user user-type code, from the communication-area condition name. */
    private static final String STANDARD_USER_TYPE_CODE = "U";

    /**
     * A user-type code outside the two the estate declares. It must reach the user main menu, because
     * the alternative in the sign-on split is unconditional.
     */
    private static final String UNDECLARED_USER_TYPE_CODE = "X";

    /** The dangling program definition, declared in the resource definition with no source member. */
    private static final String DANGLING_PROGRAM_DEFINITION = "COCRDSEC";

    /** The transaction bound to the dangling program definition, and so not dispatchable. */
    private static final String DANGLING_TRANSACTION_ID = "CDV1";

    /**
     * The default handed to every nomination test.
     *
     * <p>Deliberately a destination that no program name probed in this file resolves to, so a
     * returned value of this destination always means the caller default was applied and never that a
     * nomination was honoured. A default that could coincide with the nominated destination would make
     * the two outcomes indistinguishable and the assertion worthless.
     */
    private static final Route DISCRIMINATING_CALLER_DEFAULT = Route.CARD_LIST;

    /** The ten catalogued program names of the user menu, from {@code app/cpy/COMEN02Y.cpy}. */
    private static final List<String> USER_MENU_CATALOGUE_PROGRAMS = List.of(
            "COACTVWC", "COACTUPC", "COCRDLIC", "COCRDSLC", "COCRDUPC",
            "COTRN00C", "COTRN01C", "COTRN02C", "CORPT00C", "COBIL00C");

    /** The four catalogued program names of the administrative menu, from {@code app/cpy/COADM02Y.cpy}. */
    private static final List<String> ADMIN_MENU_CATALOGUE_PROGRAMS = List.of(
            "COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C");

    /** The service under test. It declares no collaborator, so the real instance is used throughout. */
    private NavigationService subject;

    /**
     * Asserts that the service holds nothing it could hand control to, which is the only form in which
     * "no server-side forwarding" is a statement about this class rather than about a stand-in.
     *
     * <p>An earlier revision made this claim with a hand-authored interface, mocked and then asserted to
     * record no interaction. That assertion could not fail: the double was handed to nobody, so no
     * implementation of this service &mdash; forwarding or not &mdash; could ever have interacted with
     * it. What actually carries the claim is the service's own shape. A transfer of control needs
     * something to transfer to, and a screen message needs a catalogue to come from; both would arrive
     * as a constructor parameter or an instance field, because that is how this module supplies a
     * collaborator. So the absence of either is checked directly: exactly one constructor, taking no
     * argument, and no instance field of a module type.
     *
     * <p>Reflection is used, and is confined to test code. The module's own budget for reflection is
     * zero in {@code src/main/java}; a test that inspects a delivered type's shape is the intended
     * exception, and it is what makes this a mechanical check rather than a reviewer's reading.</p>
     */
    private static void assertServiceHoldsNothingToForwardTo() {
        assertThat(NavigationService.class.getDeclaredConstructors())
                .as("a collaborator this service could hand control to would arrive as a constructor "
                        + "parameter; a single no-argument constructor is what rules that out")
                .hasSize(1)
                .allSatisfy(constructor -> assertThat(constructor.getParameterCount()).isZero());

        assertThat(NavigationService.class.getDeclaredFields())
                .filteredOn(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .as("and no instance field may carry one either, which is the other way a downstream "
                        + "screen entry point or a message catalogue could have been reached")
                .isEmpty();
    }

    @BeforeEach
    void createService() {
        this.subject = new NavigationService();
    }

    // ----------------------------------------------------------------------------------------
    // Oracle tables and fixtures.
    // ----------------------------------------------------------------------------------------

    /**
     * The whole published vocabulary, transcribed by hand from the resource definition: destination,
     * wire value, legacy transaction identifier, legacy program name and administrative scope.
     *
     * <p>Row order is the declaration order the service publishes, which is the order a user
     * traverses the application. The 17 rows are the 17 dispatchable transaction definitions; the
     * eighteenth registration is absent by design and is asserted separately.
     *
     * @return one row per published destination
     */
    static Stream<Arguments> routeVocabulary() {
        return Stream.of(
                Arguments.of(Route.SIGN_ON, WIRE_SIGN_ON, "CC00", "COSGN00C", false),
                Arguments.of(Route.USER_MENU, WIRE_USER_MENU, "CM00", "COMEN01C", false),
                Arguments.of(Route.ADMIN_MENU, WIRE_ADMIN_MENU, "CA00", "COADM01C", true),
                Arguments.of(Route.ACCOUNT_VIEW, WIRE_ACCOUNT_VIEW, "CAVW", "COACTVWC", false),
                Arguments.of(Route.ACCOUNT_UPDATE, WIRE_ACCOUNT_UPDATE, "CAUP", "COACTUPC", false),
                Arguments.of(Route.CARD_LIST, WIRE_CARD_LIST, "CCLI", "COCRDLIC", false),
                Arguments.of(Route.CARD_DETAIL, WIRE_CARD_DETAIL, "CCDL", "COCRDSLC", false),
                Arguments.of(Route.CARD_UPDATE, WIRE_CARD_UPDATE, "CCUP", "COCRDUPC", false),
                Arguments.of(Route.TRANSACTION_LIST, WIRE_TRANSACTION_LIST, "CT00", "COTRN00C", false),
                Arguments.of(Route.TRANSACTION_VIEW, WIRE_TRANSACTION_VIEW, "CT01", "COTRN01C", false),
                Arguments.of(Route.TRANSACTION_ADD, WIRE_TRANSACTION_ADD, "CT02", "COTRN02C", false),
                Arguments.of(Route.REPORT_REQUEST, WIRE_REPORT_REQUEST, "CR00", "CORPT00C", false),
                Arguments.of(Route.BILL_PAYMENT, WIRE_BILL_PAYMENT, "CB00", "COBIL00C", false),
                Arguments.of(Route.USER_LIST, WIRE_USER_LIST, "CU00", "COUSR00C", true),
                Arguments.of(Route.USER_ADD, WIRE_USER_ADD, "CU01", "COUSR01C", true),
                Arguments.of(Route.USER_UPDATE, WIRE_USER_UPDATE, "CU02", "COUSR02C", true),
                Arguments.of(Route.USER_DELETE, WIRE_USER_DELETE, "CU03", "COUSR03C", true));
    }

    /**
     * The compile-time string constants the holder publishes, paired with the destination each one
     * belongs to.
     *
     * <p>Separate from {@link #routeVocabulary()} because the two are separate published surfaces: a
     * caller may build a response body from either, so a holder constant drifting from its
     * destination's wire value would split the contract in two.
     *
     * @return one row per published constant
     */
    static Stream<Arguments> holderConstants() {
        return Stream.of(
                Arguments.of(Routes.SIGN_ON, WIRE_SIGN_ON, Route.SIGN_ON),
                Arguments.of(Routes.USER_MENU, WIRE_USER_MENU, Route.USER_MENU),
                Arguments.of(Routes.ADMIN_MENU, WIRE_ADMIN_MENU, Route.ADMIN_MENU),
                Arguments.of(Routes.ACCOUNT_VIEW, WIRE_ACCOUNT_VIEW, Route.ACCOUNT_VIEW),
                Arguments.of(Routes.ACCOUNT_UPDATE, WIRE_ACCOUNT_UPDATE, Route.ACCOUNT_UPDATE),
                Arguments.of(Routes.CARD_LIST, WIRE_CARD_LIST, Route.CARD_LIST),
                Arguments.of(Routes.CARD_DETAIL, WIRE_CARD_DETAIL, Route.CARD_DETAIL),
                Arguments.of(Routes.CARD_UPDATE, WIRE_CARD_UPDATE, Route.CARD_UPDATE),
                Arguments.of(Routes.TRANSACTION_LIST, WIRE_TRANSACTION_LIST, Route.TRANSACTION_LIST),
                Arguments.of(Routes.TRANSACTION_VIEW, WIRE_TRANSACTION_VIEW, Route.TRANSACTION_VIEW),
                Arguments.of(Routes.TRANSACTION_ADD, WIRE_TRANSACTION_ADD, Route.TRANSACTION_ADD),
                Arguments.of(Routes.REPORT_REQUEST, WIRE_REPORT_REQUEST, Route.REPORT_REQUEST),
                Arguments.of(Routes.BILL_PAYMENT, WIRE_BILL_PAYMENT, Route.BILL_PAYMENT),
                Arguments.of(Routes.USER_LIST, WIRE_USER_LIST, Route.USER_LIST),
                Arguments.of(Routes.USER_ADD, WIRE_USER_ADD, Route.USER_ADD),
                Arguments.of(Routes.USER_UPDATE, WIRE_USER_UPDATE, Route.USER_UPDATE),
                Arguments.of(Routes.USER_DELETE, WIRE_USER_DELETE, Route.USER_DELETE));
    }

    /**
     * Navigation state nominating an originating program and nothing else, which is the field the
     * back-navigation rule reads.
     *
     * @param fromProgram the originating-program nomination, which may be {@code null}
     * @return state carrying that nomination and no other component
     */
    private static ConversationState nominatingOriginatingProgram(final String fromProgram) {
        return new ConversationState(null, fromProgram, null, null, null);
    }

    /**
     * Navigation state nominating a destination program and nothing else, which is the field the
     * nomination and sign-off rules read.
     *
     * @param toProgram the destination-program nomination, which may be {@code null}
     * @return state carrying that nomination and no other component
     */
    private static ConversationState nominatingDestinationProgram(final String toProgram) {
        return new ConversationState(null, null, null, toProgram, null);
    }

    /**
     * Navigation state carrying every one of the sixteen echoed components, so that a rule dropping
     * one, or reading the wrong one, is visible.
     *
     * <p>The two program nominations name <em>different</em> destinations on purpose: back-navigation
     * must read the originating field and the nomination rule must read the destination field, and a
     * fixture where the two agreed would pass whichever field the code happened to read. All values
     * are synthetic and none of them is sensitive &mdash; the echoed state declares no field of
     * that kind and none may be added to it.
     *
     * @return fully populated navigation state
     */
    private static ConversationState fullyEchoedState() {
        return new ConversationState("CB00", "COBIL00C", "CM00", "COMEN01C", ConversationState.EntryMode.RE_ENTRY);
    }

    /**
     * Folds a value to upper case under the root locale so that a containment check cannot change
     * meaning with the platform default. The module runs its suite under a fixed language and
     * country, and a locale-sensitive fold would still be wrong for a reader who ran one test alone.
     *
     * @param value the value to fold; never {@code null} at any call site here
     * @return the value folded to upper case under the root locale
     */
    private static String upperFolded(final String value) {
        return value.toUpperCase(Locale.ROOT);
    }

    // ----------------------------------------------------------------------------------------
    // Rule: the sign-on role split.
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("sign-on sends only the administrator to the administrative menu, and every other "
            + "user type to the main menu")
    final class TheSignOnRoleSplit {

        @ParameterizedTest(name = "user-type code [{0}] reaches {1}")
        @CsvSource({
            ADMIN_USER_TYPE_CODE + "," + WIRE_ADMIN_MENU,
            STANDARD_USER_TYPE_CODE + "," + WIRE_USER_MENU,
            UNDECLARED_USER_TYPE_CODE + "," + WIRE_USER_MENU,
        })
        @DisplayName("the administrator code reaches the administrative menu and every other code, "
                + "declared or not, reaches the main menu")
        void everyRawCodeRoutesAsTheUnconditionalAlternativeRequires(final String userTypeCode,
                final String expectedWireValue) {
            final Route resolved = subject.resolveSignOnRouteForUserTypeCode(userTypeCode);

            assertThat(resolved.getRouteValue())
                    .as("code [%s] must reach %s", userTypeCode, expectedWireValue)
                    .isEqualTo(expectedWireValue);
        }

        @Test
        @DisplayName("the administrator type reaches the administrative menu")
        void theAdministratorTypeReachesTheAdministrativeMenu() {
            assertThat(subject.resolveSignOnRoute(UserType.ADMIN))
                    .isSameAs(Route.ADMIN_MENU)
                    .extracting(Route::getRouteValue)
                    .isEqualTo(WIRE_ADMIN_MENU);
        }

        @Test
        @DisplayName("the standard type reaches the main menu")
        void theStandardTypeReachesTheMainMenu() {
            assertThat(subject.resolveSignOnRoute(UserType.USER))
                    .isSameAs(Route.USER_MENU)
                    .extracting(Route::getRouteValue)
                    .isEqualTo(WIRE_USER_MENU);
        }

        @Test
        @DisplayName("an absent type reaches the main menu instead of aborting the sign-on")
        void anAbsentTypeReachesTheMainMenu() {
            assertThat(subject.resolveSignOnRoute(null)).isSameAs(Route.USER_MENU);
        }

        @ParameterizedTest(name = "code [{0}] reaches the main menu")
        @ValueSource(strings = {"U", "X", "a", "u", "0", " ", "", "AA", "AU"})
        @DisplayName("a blank, over-long, lower-cased or undeclared code all reach the main menu, and "
                + "none of them raises")
        void everyCodeThatIsNotTheAdministratorCodeReachesTheMainMenu(final String userTypeCode) {
            assertThat(subject.resolveSignOnRouteForUserTypeCode(userTypeCode))
                    .as("code [%s] is not the administrator code", userTypeCode)
                    .isSameAs(Route.USER_MENU);
        }

        @Test
        @DisplayName("an absent raw code reaches the main menu instead of raising")
        void anAbsentRawCodeReachesTheMainMenu() {
            assertThat(subject.resolveSignOnRouteForUserTypeCode(null)).isSameAs(Route.USER_MENU);
        }

        @Test
        @DisplayName("a lower-cased administrator character is not an administrator, because the "
                + "legacy comparison applies no case fold")
        void aLowerCasedAdministratorCharacterIsNotAnAdministrator() {
            assertThat(subject.resolveSignOnRouteForUserTypeCode("a")).isSameAs(Route.USER_MENU);
        }

        @Test
        @DisplayName("the split returns the exact destination each user type selects, and the service "
                + "holds nothing it could hand control to instead")
        void theSplitReturnsAValue() {
            assertAll(
                    () -> assertThat(subject.resolveSignOnRoute(UserType.ADMIN))
                            .as("an administrative type selects the administrative menu and no other "
                                    + "route, which is the whole of the legacy split")
                            .isSameAs(Route.ADMIN_MENU),
                    () -> assertThat(subject.resolveSignOnRoute(UserType.USER))
                            .as("and every other type selects the main menu")
                            .isSameAs(Route.USER_MENU));

            assertServiceHoldsNothingToForwardTo();
        }
    }

    // ----------------------------------------------------------------------------------------
    // Rule: entry carrying no prior navigation state.
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("a turn carrying no prior navigation state goes to sign-on, unconditionally")
    final class EntryCarryingNoPriorNavigationState {

        @Test
        @DisplayName("no state at all is a stateless turn")
        void noStateAtAllIsAStatelessTurn() {
            assertThat(subject.isConversationStateAbsent(null)).isTrue();
        }

        @Test
        @DisplayName("wholly empty state is a stateless turn, because it carries no signed-on user, "
                + "no selection and no previous screen")
        void whollyEmptyStateIsAStatelessTurn() {
            assertThat(subject.isConversationStateAbsent(ConversationState.empty())).isTrue();
        }

        @Test
        @DisplayName("state carrying even one component is not a stateless turn")
        void stateCarryingOneComponentIsNotAStatelessTurn() {
            assertThat(subject.isConversationStateAbsent(nominatingOriginatingProgram("COBIL00C")))
                    .isFalse();
        }

        @Test
        @DisplayName("empty state marked as a first entry is still a stateless turn, because a "
                + "zero-initialised communication area is what first entry means")
        void emptyStateMarkedAsAFirstEntryIsStillAStatelessTurn() {
            // The legacy entry mode is CDEMO-PGM-CONTEXT PIC 9(01) at [app/cpy/COCOM01Y.cpy:L29-L31],
            // whose only two condition names are CDEMO-PGM-ENTER VALUE 0 and CDEMO-PGM-REENTER VALUE 1.
            // A single-digit numeric item cannot be absent, so the zero it holds in a freshly
            // initialised communication area already means first entry - and every program tests only
            // IF NOT CDEMO-PGM-REENTER, as at [app/cbl/COBIL00C.cbl:L112]. Marking an empty state as a
            // first entry therefore changes nothing about it, and the stateless test must still fire.
            //
            // An earlier revision of this rule carried the entry mode as a nullable value, which let an
            // "unset" mode exist alongside first entry and re-entry. That third state has no legacy
            // counterpart, and this assertion previously depended on it. ConversationState normalises a
            // null entry mode to first entry in its compact constructor for exactly that reason.
            assertThat(ConversationState.empty().withFirstEntry())
                    .as("marking an empty state as a first entry is the identity, because that is "
                            + "already what an empty state carries")
                    .isEqualTo(ConversationState.empty());
            assertThat(ConversationState.empty().firstEntry()).isTrue();
            assertThat(subject.isConversationStateAbsent(ConversationState.empty().withFirstEntry()))
                    .as("a zero-length communication area takes the sign-on branch at "
                            + "[app/cbl/COBIL00C.cbl:L107-L108] whatever its entry mode is said to be")
                    .isTrue();
        }

        @Test
        @DisplayName("empty state marked as a re-entry is not a stateless turn, because re-entry is a "
                + "value a zero-initialised communication area cannot hold")
        void emptyStateMarkedAsAReEntryIsNotAStatelessTurn() {
            assertThat(subject.isConversationStateAbsent(ConversationState.empty().withReEntry()))
                    .isFalse();
        }

        @Test
        @DisplayName("fully echoed state is not a stateless turn")
        void fullyEchoedStateIsNotAStatelessTurn() {
            assertThat(subject.isConversationStateAbsent(fullyEchoedState())).isFalse();
        }

        @Test
        @DisplayName("the destination is sign-on, and the rule admits no per-screen override")
        void theDestinationIsSignOnAndAdmitsNoOverride() {
            assertThat(subject.resolveAbsentContextRoute())
                    .isSameAs(Route.SIGN_ON)
                    .extracting(Route::getRouteValue)
                    .isEqualTo(WIRE_SIGN_ON);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Rule: attention-key dispatch, both arms.
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("only the third program-function key produces a destination; every other key "
            + "re-presents the same screen")
    final class AttentionKeyRouting {

        @Test
        @DisplayName("the exit key produces the destination its nomination names")
        void theExitKeyProducesTheNominatedDestination() {
            final Optional<Route> resolved = subject.resolveAttentionKeyRoute(KeyAction.PFK03,
                    nominatingOriginatingProgram("COACTVWC"), DISCRIMINATING_CALLER_DEFAULT);

            assertThat(resolved).contains(Route.ACCOUNT_VIEW);
        }

        @Test
        @DisplayName("the exit key with nothing nominated produces the calling screen's own default, "
                + "which for the payment screen is the main menu")
        void theExitKeyWithNothingNominatedProducesTheCallingScreenDefault() {
            final Optional<Route> resolved = subject.resolveAttentionKeyRoute(KeyAction.PFK03,
                    nominatingOriginatingProgram("        "), Route.USER_MENU);

            assertThat(resolved).contains(Route.USER_MENU);
        }

        @Test
        @DisplayName("the exit key on a menu screen produces sign-on, which is the exit destination")
        void theExitKeyOnAMenuScreenProducesSignOn() {
            assertThat(subject.resolveSignOffRoute(nominatingDestinationProgram("COSGN00C")))
                    .isSameAs(Route.SIGN_ON);
        }

        @ParameterizedTest(name = "{0} produces no destination")
        @EnumSource(value = KeyAction.class, mode = EnumSource.Mode.EXCLUDE, names = "PFK03")
        @DisplayName("an unmapped key produces no destination at all, so the screen is re-presented "
                + "rather than left")
        void anUnmappedKeyProducesNoDestination(final KeyAction keyAction) {
            final Optional<Route> resolved = subject.resolveAttentionKeyRoute(keyAction,
                    nominatingOriginatingProgram("COACTVWC"), DISCRIMINATING_CALLER_DEFAULT);

            assertThat(resolved)
                    .as("%s must not produce a destination", keyAction)
                    .isEmpty();
        }

        @Test
        @DisplayName("an absent key action is the unmapped case, because the legacy key mapping has "
                + "no catch-all clause and so leaves an unrecognised key unassigned")
        void anAbsentKeyActionIsTheUnmappedCase() {
            assertAll(
                    () -> assertThat(subject.isBackNavigationKey(null)).isFalse(),
                    () -> assertThat(subject.resolveAttentionKeyRoute(null,
                            nominatingOriginatingProgram("COACTVWC"), DISCRIMINATING_CALLER_DEFAULT))
                            .isEmpty());
        }

        @Test
        @DisplayName("the third program-function key is the one key that returns to the previous screen")
        void theThirdProgramFunctionKeyIsTheOneThatReturns() {
            assertThat(subject.isBackNavigationKey(KeyAction.PFK03)).isTrue();
        }

        @ParameterizedTest(name = "{0} does not return to the previous screen")
        @EnumSource(value = KeyAction.class, mode = EnumSource.Mode.EXCLUDE, names = "PFK03")
        @DisplayName("no other decoded action returns to the previous screen")
        void noOtherDecodedActionReturnsToThePreviousScreen(final KeyAction keyAction) {
            assertThat(subject.isBackNavigationKey(keyAction))
                    .as("%s is not the exit key", keyAction)
                    .isFalse();
        }

        @Test
        @DisplayName("exactly one action in the whole sixteen-constant vocabulary returns to the "
                + "previous screen")
        void exactlyOneActionReturnsToThePreviousScreen() {
            final List<KeyAction> returning = new ArrayList<>();
            for (final KeyAction keyAction : KeyAction.values()) {
                if (subject.isBackNavigationKey(keyAction)) {
                    returning.add(keyAction);
                }
            }

            assertThat(returning).containsExactly(KeyAction.PFK03);
        }

        @Test
        @DisplayName("the state and the calling screen's default are both required, even for a key "
                + "that produces no destination")
        void theStateAndDefaultAreRequiredEvenForANonNavigatingKey() {
            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> subject.resolveAttentionKeyRoute(KeyAction.ENTER, null,
                                    DISCRIMINATING_CALLER_DEFAULT)),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> subject.resolveAttentionKeyRoute(KeyAction.ENTER,
                                    ConversationState.empty(), null)));
        }
    }


    // ----------------------------------------------------------------------------------------
    // The published route vocabulary: completeness, exact values and stability.
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the route vocabulary mirrors the seventeen dispatchable transaction definitions and "
            + "cannot be disturbed by a caller")
    final class TheRouteVocabulary {

        @ParameterizedTest(name = "{0} publishes [{1}] for transaction {2} bound to program {3}")
        @MethodSource("com.carddemo.service.NavigationServiceTest#routeVocabulary")
        @DisplayName("each destination carries the wire value, the legacy identifiers and the scope "
                + "the resource definition gives it")
        void eachDestinationCarriesWhatTheResourceDefinitionRegisters(final Route destination,
                final String expectedWireValue, final String expectedTransactionId,
                final String expectedProgramName, final boolean expectedAdminScope) {
            assertAll(
                    () -> assertThat(destination.getRouteValue())
                            .as("wire value of %s", destination)
                            .isEqualTo(expectedWireValue),
                    () -> assertThat(destination.getLegacyTransactionId())
                            .as("legacy transaction identifier of %s", destination)
                            .isEqualTo(expectedTransactionId),
                    () -> assertThat(destination.getLegacyProgramName())
                            .as("legacy program name of %s", destination)
                            .isEqualTo(expectedProgramName),
                    () -> assertThat(destination.isAdminScoped())
                            .as("administrative scope of %s", destination)
                            .isEqualTo(expectedAdminScope));
        }

        @ParameterizedTest(name = "the holder constant for {2} is [{1}]")
        @MethodSource("com.carddemo.service.NavigationServiceTest#holderConstants")
        @DisplayName("each published string constant carries the exact wire value of the destination "
                + "it belongs to, so a response body built from either surface reads the same")
        void eachHolderConstantMatchesItsDestination(final String holderConstant,
                final String expectedWireValue, final Route destination) {
            assertAll(
                    () -> assertThat(holderConstant).isEqualTo(expectedWireValue),
                    () -> assertThat(holderConstant).isEqualTo(destination.getRouteValue()));
        }

        @Test
        @DisplayName("the two checked tables carry one row per declared destination, so no destination "
                + "escapes the row-by-row comparison against the resource definition")
        void bothCheckedTablesCarryOneRowPerDestination() {
            assertAll(
                    () -> assertThat(routeVocabulary().count())
                            .as("rows of the destination table")
                            .isEqualTo(EXPECTED_DESTINATION_COUNT),
                    () -> assertThat(holderConstants().count())
                            .as("rows of the published-constant table")
                            .isEqualTo(EXPECTED_DESTINATION_COUNT));
        }

        @Test
        @DisplayName("seventeen destinations are published, matching the service's own census figure")
        void seventeenDestinationsArePublished() {
            assertAll(
                    () -> assertThat(subject.routes()).hasSize(EXPECTED_DESTINATION_COUNT),
                    () -> assertThat(NavigationService.ROUTE_COUNT)
                            .isEqualTo(EXPECTED_DESTINATION_COUNT),
                    () -> assertThat(Route.values()).hasSize(EXPECTED_DESTINATION_COUNT));
        }

        @Test
        @DisplayName("every published wire value is present, non-blank and unique, so a copy-and-paste "
                + "duplicate cannot survive")
        void everyWireValueIsPresentNonBlankAndUnique() {
            final List<String> published = new ArrayList<>();
            for (final Route destination : subject.routes()) {
                published.add(destination.getRouteValue());
            }

            assertAll(
                    () -> assertThat(published).doesNotContainNull(),
                    () -> assertThat(published).allSatisfy(
                            value -> assertThat(value.isBlank()).isFalse()),
                    () -> assertThat(new LinkedHashSet<>(published))
                            .as("distinct wire values")
                            .hasSize(published.size()));
        }

        @Test
        @DisplayName("legacy transaction identifiers and program names are each free of duplicates, so "
                + "no index entry can silently displace another")
        void theLegacyIdentifiersAreFreeOfDuplicates() {
            final List<String> transactionIds = new ArrayList<>();
            final List<String> programNames = new ArrayList<>();
            for (final Route destination : subject.routes()) {
                transactionIds.add(destination.getLegacyTransactionId());
                programNames.add(destination.getLegacyProgramName());
            }

            assertAll(
                    () -> assertThat(new LinkedHashSet<>(transactionIds))
                            .hasSize(transactionIds.size()),
                    () -> assertThat(new LinkedHashSet<>(programNames))
                            .hasSize(programNames.size()));
        }

        @Test
        @DisplayName("declaration order is the traversal order: entry, then the two menus, then the "
                + "screens, then the administrative maintenance destinations")
        void declarationOrderIsTheTraversalOrder() {
            assertThat(subject.routes()).containsExactly(
                    Route.SIGN_ON, Route.USER_MENU, Route.ADMIN_MENU,
                    Route.ACCOUNT_VIEW, Route.ACCOUNT_UPDATE,
                    Route.CARD_LIST, Route.CARD_DETAIL, Route.CARD_UPDATE,
                    Route.TRANSACTION_LIST, Route.TRANSACTION_VIEW, Route.TRANSACTION_ADD,
                    Route.REPORT_REQUEST, Route.BILL_PAYMENT,
                    Route.USER_LIST, Route.USER_ADD, Route.USER_UPDATE, Route.USER_DELETE);
        }

        @Test
        @DisplayName("the published destination list refuses addition, removal and replacement")
        void theDestinationListRefusesMutation() {
            final List<Route> published = subject.routes();

            assertAll(
                    () -> assertThatExceptionOfType(UnsupportedOperationException.class)
                            .isThrownBy(() -> published.add(Route.SIGN_ON)),
                    () -> assertThatExceptionOfType(UnsupportedOperationException.class)
                            .isThrownBy(() -> published.remove(Route.SIGN_ON)),
                    () -> assertThatExceptionOfType(UnsupportedOperationException.class)
                            .isThrownBy(() -> published.set(0, Route.ADMIN_MENU)),
                    () -> assertThatExceptionOfType(UnsupportedOperationException.class)
                            .isThrownBy(published::clear));
        }

        @Test
        @DisplayName("the published administrative set refuses addition, removal and clearing")
        void theAdministrativeSetRefusesMutation() {
            final Set<Route> published = subject.adminScopedRoutes();

            assertAll(
                    () -> assertThatExceptionOfType(UnsupportedOperationException.class)
                            .isThrownBy(() -> published.add(Route.SIGN_ON)),
                    () -> assertThatExceptionOfType(UnsupportedOperationException.class)
                            .isThrownBy(() -> published.remove(Route.ADMIN_MENU)),
                    () -> assertThatExceptionOfType(UnsupportedOperationException.class)
                            .isThrownBy(published::clear));
        }

        @Test
        @DisplayName("both published collections are shared instances a caller may hold without copying")
        void bothPublishedCollectionsAreSharedInstances() {
            assertAll(
                    () -> assertThat(subject.routes()).isSameAs(subject.routes()),
                    () -> assertThat(subject.adminScopedRoutes())
                            .isSameAs(subject.adminScopedRoutes()));
        }

        @Test
        @DisplayName("exactly the administrative menu and the four user-maintenance destinations are "
                + "reported as administrative")
        void exactlyFiveDestinationsAreReportedAdministrative() {
            assertThat(subject.adminScopedRoutes())
                    .hasSize(EXPECTED_ADMIN_DESTINATION_COUNT)
                    .containsExactlyInAnyOrder(Route.ADMIN_MENU, Route.USER_LIST, Route.USER_ADD,
                            Route.USER_UPDATE, Route.USER_DELETE);
        }

        @Test
        @DisplayName("the reported administrative set agrees with each destination's own scope flag")
        void theReportedAdministrativeSetAgreesWithEachOwnFlag() {
            final Set<Route> reported = subject.adminScopedRoutes();

            assertThat(subject.routes()).allSatisfy(destination ->
                    assertThat(reported.contains(destination))
                            .as("scope agreement for %s", destination)
                            .isEqualTo(destination.isAdminScoped()));
        }

        @Test
        @DisplayName("transaction add is not administrative, because the label that would say so is "
                + "commented out in the menu catalogue and therefore inactive")
        void transactionAddIsNotAdministrative() {
            assertAll(
                    () -> assertThat(Route.TRANSACTION_ADD.isAdminScoped()).isFalse(),
                    () -> assertThat(subject.adminScopedRoutes())
                            .doesNotContain(Route.TRANSACTION_ADD));
        }

        @Test
        @DisplayName("the published census figures are the counted legacy figures and the legacy "
                + "gate literals")
        void thePublishedCensusFiguresAreTheCountedLegacyFigures() {
            assertAll(
                    () -> assertThat(NavigationService.LEGACY_DISPATCH_SITE_COUNT)
                            .isEqualTo(EXPECTED_TRANSFER_SITE_COUNT),
                    () -> assertThat(NavigationService.LEGACY_REARM_SITE_COUNT)
                            .isEqualTo(EXPECTED_REARM_SITE_COUNT),
                    () -> assertThat(NavigationService.DUMMY_PROGRAM_PREFIX)
                            .isEqualTo(EXPECTED_SUPPRESSION_PREFIX),
                    () -> assertThat(NavigationService.ADMIN_ONLY_OPTION_USER_TYPE_CODE)
                            .isEqualTo(EXPECTED_ADMIN_OPTION_CODE));
        }

        @ParameterizedTest(name = "transaction {0} resolves to [{1}]")
        @CsvSource({
            "CC00,sign-on", "CM00,user-menu", "CA00,admin-menu",
            "CAVW,account-view", "CAUP,account-update",
            "CCLI,card-list", "CCDL,card-detail", "CCUP,card-update",
            "CT00,transaction-list", "CT01,transaction-view", "CT02,transaction-add",
            "CR00,report-request", "CB00,bill-payment",
            "CU00,user-list", "CU01,user-add", "CU02,user-update", "CU03,user-delete",
        })
        @DisplayName("every registered transaction identifier that has a target route resolves to it")
        void everyRegisteredTransactionIdentifierResolves(final String legacyTransactionId,
                final String expectedWireValue) {
            assertThat(subject.routeForLegacyTransactionId(legacyTransactionId))
                    .as("transaction %s", legacyTransactionId)
                    .map(Route::getRouteValue)
                    .contains(expectedWireValue);
        }

        @ParameterizedTest(name = "program {0} resolves to [{1}]")
        @CsvSource({
            "COSGN00C,sign-on", "COMEN01C,user-menu", "COADM01C,admin-menu",
            "COACTVWC,account-view", "COACTUPC,account-update",
            "COCRDLIC,card-list", "COCRDSLC,card-detail", "COCRDUPC,card-update",
            "COTRN00C,transaction-list", "COTRN01C,transaction-view", "COTRN02C,transaction-add",
            "CORPT00C,report-request", "COBIL00C,bill-payment",
            "COUSR00C,user-list", "COUSR01C,user-add", "COUSR02C,user-update", "COUSR03C,user-delete",
        })
        @DisplayName("every legacy program name that has a target route resolves to it")
        void everyLegacyProgramNameResolves(final String legacyProgramName,
                final String expectedWireValue) {
            assertThat(subject.routeForLegacyProgram(legacyProgramName))
                    .as("program %s", legacyProgramName)
                    .map(Route::getRouteValue)
                    .contains(expectedWireValue);
        }

        @ParameterizedTest(name = "wire value [{0}] reads back")
        @ValueSource(strings = {"sign-on", "user-menu", "admin-menu", "account-view",
            "account-update", "card-list", "card-detail", "card-update", "transaction-list",
            "transaction-view", "transaction-add", "report-request", "bill-payment", "user-list",
            "user-add", "user-update", "user-delete"})
        @DisplayName("every published wire value reads back to its destination, so a client echo "
                + "round-trips")
        void everyPublishedWireValueReadsBack(final String wireValue) {
            assertThat(subject.routeForValue(wireValue))
                    .as("wire value [%s]", wireValue)
                    .map(Route::getRouteValue)
                    .contains(wireValue);
        }

        @ParameterizedTest(name = "padded nomination [{0}] still resolves")
        @ValueSource(strings = {"COBIL00C", "COBIL00C ", "COBIL00C  ", "COBIL00C    "})
        @DisplayName("the trailing space padding of a fixed-width field is tolerated, because the "
                + "legacy transfer ignores it")
        void trailingSpacePaddingIsTolerated(final String nomination) {
            assertThat(subject.routeForLegacyProgram(nomination))
                    .as("nomination [%s]", nomination)
                    .contains(Route.BILL_PAYMENT);
        }

        /**
         * The low value is the second padding character the legacy fields carry, and it is exercised
         * from inside a method body rather than from a parameter table so that a control character
         * never reaches a generated test name or a test report.
         */
        @Test
        @DisplayName("trailing low-value padding is tolerated too, including mixed with spaces, "
                + "because a fixed-width field pads with either")
        void trailingLowValuePaddingIsTolerated() {
            final String lowValue = "\u0000";

            assertAll(
                    () -> assertThat(subject.routeForLegacyProgram("COBIL00C" + lowValue))
                            .contains(Route.BILL_PAYMENT),
                    () -> assertThat(subject.routeForLegacyProgram("COBIL00C " + lowValue))
                            .contains(Route.BILL_PAYMENT),
                    () -> assertThat(subject.routeForLegacyProgram("COBIL00C" + lowValue + " "))
                            .contains(Route.BILL_PAYMENT),
                    () -> assertThat(subject.routeForLegacyProgram(lowValue + "COBIL00C"))
                            .as("leading low-value padding is a difference, not padding")
                            .isEmpty());
        }

        @ParameterizedTest(name = "[{0}] resolves to nothing")
        @ValueSource(strings = {" COBIL00C", "cobil00c", "COBIL00", "COBIL00CC", "NOSUCHPG",
            "COBIL 00C"})
        @DisplayName("leading padding, a case difference, a truncation, an over-long name and a "
                + "misspelling are differences rather than padding, so none of them resolves")
        void onlyTrailingPaddingIsTolerated(final String nomination) {
            assertThat(subject.routeForLegacyProgram(nomination))
                    .as("nomination [%s]", nomination)
                    .isEmpty();
        }

        @Test
        @DisplayName("a tab is not padding, so a name followed by one resolves to nothing")
        void aTabIsNotPadding() {
            assertThat(subject.routeForLegacyProgram("COBIL00C\t")).isEmpty();
        }

        @Test
        @DisplayName("a wire value is matched exactly, so surrounding white space is a difference from "
                + "the published value rather than padding to tolerate")
        void aWireValueIsMatchedExactly() {
            assertAll(
                    () -> assertThat(subject.routeForValue("sign-on ")).isEmpty(),
                    () -> assertThat(subject.routeForValue(" sign-on")).isEmpty(),
                    () -> assertThat(subject.routeForValue("SIGN-ON")).isEmpty(),
                    () -> assertThat(subject.routeForValue("signon")).isEmpty());
        }

        @Test
        @DisplayName("every lookup answers with the empty result for an absent or unrecognised key "
                + "instead of raising or fabricating a destination")
        void everyLookupAnswersEmptyForAnAbsentOrUnrecognisedKey() {
            assertAll(
                    () -> assertThat(subject.routeForLegacyProgram(null)).isEmpty(),
                    () -> assertThat(subject.routeForLegacyTransactionId(null)).isEmpty(),
                    () -> assertThat(subject.routeForValue(null)).isEmpty(),
                    () -> assertThat(subject.routeForLegacyProgram("")).isEmpty(),
                    () -> assertThat(subject.routeForLegacyTransactionId("ZZZZ")).isEmpty(),
                    () -> assertThat(subject.routeForValue("no-such-route")).isEmpty());
        }
    }

    // ----------------------------------------------------------------------------------------
    // The dangling program definition: the required negative assertions.
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the dangling program definition of the resource definition file produces no route "
            + "at all, because no source member of that name exists")
    final class TheDanglingProgramDefinition {

        @Test
        @DisplayName("no published wire value names the dangling program definition")
        void noPublishedWireValueNamesTheDanglingProgramDefinition() {
            final List<String> published = new ArrayList<>();
            for (final Route destination : subject.routes()) {
                published.add(destination.getRouteValue());
            }

            assertThat(published).allSatisfy(wireValue -> assertAll(
                    () -> assertThat(wireValue).isNotEqualTo(DANGLING_PROGRAM_DEFINITION),
                    () -> assertThat(upperFolded(wireValue))
                            .as("wire value [%s] must not name the dangling definition", wireValue)
                            .doesNotContain(DANGLING_PROGRAM_DEFINITION)));
        }

        @Test
        @DisplayName("no destination records the dangling program definition as its legacy program, "
                + "and none records the transaction bound to it")
        void noDestinationRecordsTheDanglingBinding() {
            assertThat(subject.routes()).allSatisfy(destination -> assertAll(
                    () -> assertThat(destination.getLegacyProgramName())
                            .isNotEqualTo(DANGLING_PROGRAM_DEFINITION),
                    () -> assertThat(destination.getLegacyTransactionId())
                            .isNotEqualTo(DANGLING_TRANSACTION_ID)));
        }

        @Test
        @DisplayName("a lookup keyed on the dangling program definition yields the empty result rather "
                + "than a fabricated destination")
        void aLookupOnTheDanglingProgramYieldsTheEmptyResult() {
            assertAll(
                    () -> assertThat(subject.routeForLegacyProgram(DANGLING_PROGRAM_DEFINITION))
                            .isEmpty(),
                    () -> assertThat(subject.routeForLegacyProgram(DANGLING_PROGRAM_DEFINITION + " "))
                            .isEmpty(),
                    () -> assertThat(subject.routeForValue(DANGLING_PROGRAM_DEFINITION)).isEmpty());
        }

        @Test
        @DisplayName("a lookup keyed on the transaction bound to the dangling definition yields the "
                + "empty result, which is why eighteen registrations publish seventeen destinations")
        void aLookupOnTheDanglingTransactionYieldsTheEmptyResult() {
            assertAll(
                    () -> assertThat(subject.routeForLegacyTransactionId(DANGLING_TRANSACTION_ID))
                            .isEmpty(),
                    () -> assertThat(subject.routeForLegacyTransactionId(DANGLING_TRANSACTION_ID + " "))
                            .isEmpty(),
                    () -> assertThat(subject.routes()).hasSize(EXPECTED_DESTINATION_COUNT));
        }

        @Test
        @DisplayName("a menu entry naming the dangling program definition dispatches nowhere rather "
                + "than transferring to an invented destination")
        void aMenuEntryNamingTheDanglingProgramDispatchesNowhere() {
            assertAll(
                    () -> assertThat(subject.resolveAdminMenuDispatch(DANGLING_PROGRAM_DEFINITION))
                            .isEmpty(),
                    () -> assertThat(subject.resolveMenuDispatch(UserType.USER,
                            STANDARD_USER_TYPE_CODE, DANGLING_PROGRAM_DEFINITION)).isEmpty());
        }
    }


    // ----------------------------------------------------------------------------------------
    // Rule: forward dispatch from a menu.
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("a menu selection applies the legacy gates in source order and produces a destination "
            + "only when neither gate fires")
    final class MenuForwardDispatch {

        @ParameterizedTest(name = "the user menu dispatches to [{0}]")
        @ValueSource(strings = {"COACTVWC", "COACTUPC", "COCRDLIC", "COCRDSLC", "COCRDUPC",
            "COTRN00C", "COTRN01C", "COTRN02C", "CORPT00C", "COBIL00C"})
        @DisplayName("every one of the ten catalogued user-menu entries dispatches to a destination")
        void everyUserMenuCatalogueEntryDispatches(final String catalogProgramName) {
            assertThat(subject.resolveMenuDispatch(UserType.USER, STANDARD_USER_TYPE_CODE,
                    catalogProgramName))
                    .as("user-menu entry [%s]", catalogProgramName)
                    .isPresent();
        }

        @ParameterizedTest(name = "the administrative menu dispatches to [{0}]")
        @ValueSource(strings = {"COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C"})
        @DisplayName("every one of the four catalogued administrative-menu entries dispatches to an "
                + "administrative destination")
        void everyAdminMenuCatalogueEntryDispatches(final String catalogProgramName) {
            assertThat(subject.resolveAdminMenuDispatch(catalogProgramName))
                    .as("administrative-menu entry [%s]", catalogProgramName)
                    .isPresent()
                    .get()
                    .satisfies(destination -> assertThat(destination.isAdminScoped()).isTrue());
        }

        @Test
        @DisplayName("the catalogued entries this file names are exactly the ten user and four "
                + "administrative entries the two menu copybooks carry")
        void theCataloguedEntriesAreTheTenAndFourTheCopybooksCarry() {
            assertAll(
                    () -> assertThat(USER_MENU_CATALOGUE_PROGRAMS).hasSize(10),
                    () -> assertThat(ADMIN_MENU_CATALOGUE_PROGRAMS).hasSize(4),
                    () -> assertThat(USER_MENU_CATALOGUE_PROGRAMS).allSatisfy(programName ->
                            assertThat(subject.routeForLegacyProgram(programName)).isPresent()),
                    () -> assertThat(ADMIN_MENU_CATALOGUE_PROGRAMS).allSatisfy(programName ->
                            assertThat(subject.routeForLegacyProgram(programName)).isPresent()));
        }

        @Test
        @DisplayName("a catalogued name beginning with the suppression literal suppresses dispatch, so "
                + "the screen composes a coming-soon message instead of transferring")
        void aSuppressedNameSuppressesDispatch() {
            assertAll(
                    () -> assertThat(subject.isDispatchSuppressed("DUMMY")).isTrue(),
                    () -> assertThat(subject.isDispatchSuppressed("DUMMY001")).isTrue(),
                    () -> assertThat(subject.resolveMenuDispatch(UserType.USER,
                            STANDARD_USER_TYPE_CODE, "DUMMY001")).isEmpty(),
                    () -> assertThat(subject.resolveAdminMenuDispatch("DUMMY001")).isEmpty());
        }

        @Test
        @DisplayName("the guard compares only the leading five characters, so a shorter or differently "
                + "spelled name is not suppressed")
        void theGuardComparesOnlyTheLeadingFiveCharacters() {
            assertAll(
                    () -> assertThat(subject.isDispatchSuppressed("DUMM")).isFalse(),
                    () -> assertThat(subject.isDispatchSuppressed("DUMY0001")).isFalse(),
                    () -> assertThat(subject.isDispatchSuppressed("dummy001")).isFalse(),
                    () -> assertThat(subject.isDispatchSuppressed(" DUMMY")).isFalse());
        }

        @Test
        @DisplayName("an absent or blank catalogued name is not suppressed, because it does not begin "
                + "with the literal, yet it still names no destination")
        void anAbsentOrBlankNameIsNotSuppressedAndStillNamesNoDestination() {
            assertAll(
                    () -> assertThat(subject.isDispatchSuppressed(null)).isFalse(),
                    () -> assertThat(subject.isDispatchSuppressed("")).isFalse(),
                    () -> assertThat(subject.isDispatchSuppressed("   ")).isFalse(),
                    () -> assertThat(subject.resolveMenuDispatch(UserType.USER,
                            STANDARD_USER_TYPE_CODE, null)).isEmpty(),
                    () -> assertThat(subject.resolveAdminMenuDispatch(null)).isEmpty(),
                    () -> assertThat(subject.resolveAdminMenuDispatch("   ")).isEmpty());
        }

        @Test
        @DisplayName("the administrator-only gate denies a standard user selecting an entry marked with "
                + "the administrator code, and it is evaluated before the suppression guard")
        void theAdministratorOnlyGateDeniesAStandardUserAndIsEvaluatedFirst() {
            assertAll(
                    () -> assertThat(subject.isAdminOnlyOptionDenied(UserType.USER,
                            EXPECTED_ADMIN_OPTION_CODE)).isTrue(),
                    () -> assertThat(subject.resolveMenuDispatch(UserType.USER,
                            EXPECTED_ADMIN_OPTION_CODE, "COACTVWC"))
                            .as("the gate short-circuits even though the name resolves")
                            .isEmpty(),
                    () -> assertThat(subject.resolveMenuDispatch(UserType.USER,
                            EXPECTED_ADMIN_OPTION_CODE, "DUMMY001"))
                            .as("the gate is reached before the suppression guard")
                            .isEmpty());
        }

        @Test
        @DisplayName("the gate tests the standard-user condition rather than the absence of the "
                + "administrator condition, so an administrator and an undeclared type both pass it")
        void theGateTestsTheStandardUserConditionRatherThanTheAbsenceOfTheAdministratorCondition() {
            assertAll(
                    () -> assertThat(subject.isAdminOnlyOptionDenied(UserType.ADMIN,
                            EXPECTED_ADMIN_OPTION_CODE)).isFalse(),
                    () -> assertThat(subject.isAdminOnlyOptionDenied(null,
                            EXPECTED_ADMIN_OPTION_CODE)).isFalse(),
                    () -> assertThat(subject.resolveMenuDispatch(UserType.ADMIN,
                            EXPECTED_ADMIN_OPTION_CODE, "COACTVWC"))
                            .contains(Route.ACCOUNT_VIEW),
                    () -> assertThat(subject.resolveMenuDispatch(null,
                            EXPECTED_ADMIN_OPTION_CODE, "COACTVWC"))
                            .contains(Route.ACCOUNT_VIEW));
        }

        @Test
        @DisplayName("the gate fires only on the administrator option code, so a standard-coded, "
                + "absent or lower-cased option code does not deny the selection")
        void theGateFiresOnlyOnTheAdministratorOptionCode() {
            assertAll(
                    () -> assertThat(subject.isAdminOnlyOptionDenied(UserType.USER,
                            STANDARD_USER_TYPE_CODE)).isFalse(),
                    () -> assertThat(subject.isAdminOnlyOptionDenied(UserType.USER, null)).isFalse(),
                    () -> assertThat(subject.isAdminOnlyOptionDenied(UserType.USER, "a")).isFalse(),
                    () -> assertThat(subject.isAdminOnlyOptionDenied(UserType.USER, "")).isFalse());
        }

        @Test
        @DisplayName("the administrative menu applies no user-type gate at all, because its catalogue "
                + "entries carry no user-type field for a gate to test")
        void theAdministrativeMenuAppliesNoUserTypeGate() {
            assertThat(subject.resolveAdminMenuDispatch("COUSR00C")).contains(Route.USER_LIST);
        }

        @Test
        @DisplayName("a catalogued name that resolves to nothing dispatches nowhere rather than "
                + "raising or inventing a destination")
        void aNameResolvingToNothingDispatchesNowhere() {
            assertAll(
                    () -> assertThat(subject.resolveMenuDispatch(UserType.USER,
                            STANDARD_USER_TYPE_CODE, "NOSUCHPG")).isEmpty(),
                    () -> assertThat(subject.resolveAdminMenuDispatch("NOSUCHPG")).isEmpty());
        }
    }

    // ----------------------------------------------------------------------------------------
    // Rules: return to the previous screen, and return to an already nominated destination.
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("a nomination is honoured when it names a reachable destination, falls back to the "
            + "calling screen's own default when it is blank, and fails when it names nothing reachable")
    final class TheNominationRules {

        @ParameterizedTest(name = "originating field [{0}] falls back to the calling screen default")
        @ValueSource(strings = {"", " ", "        "})
        @DisplayName("an originating field of spaces nominates nothing, so the calling screen's own "
                + "default applies")
        void aBlankOriginatingFieldFallsBackToTheCallingScreenDefault(final String field) {
            assertThat(subject.resolveBackNavigation(nominatingOriginatingProgram(field),
                    DISCRIMINATING_CALLER_DEFAULT))
                    .as("originating field [%s]", field)
                    .isSameAs(DISCRIMINATING_CALLER_DEFAULT);
        }

        @Test
        @DisplayName("an absent originating field, and one holding only low values, both nominate "
                + "nothing")
        void anAbsentOrLowValueOriginatingFieldNominatesNothing() {
            assertAll(
                    () -> assertThat(subject.resolveBackNavigation(
                            nominatingOriginatingProgram(null), DISCRIMINATING_CALLER_DEFAULT))
                            .isSameAs(DISCRIMINATING_CALLER_DEFAULT),
                    () -> assertThat(subject.resolveBackNavigation(
                            nominatingOriginatingProgram("\u0000\u0000"),
                            DISCRIMINATING_CALLER_DEFAULT))
                            .isSameAs(DISCRIMINATING_CALLER_DEFAULT));
        }

        @ParameterizedTest(name = "originating field [{0}] is honoured as [{1}]")
        @CsvSource({
            "COSGN00C,sign-on", "COMEN01C,user-menu", "COADM01C,admin-menu",
            "COACTVWC,account-view", "COTRN00C,transaction-list", "COUSR00C,user-list",
        })
        @DisplayName("an originating field naming a reachable destination is honoured in preference to "
                + "the calling screen's default")
        void anOriginatingFieldNamingAReachableDestinationIsHonoured(final String field,
                final String expectedWireValue) {
            final Route resolved = subject.resolveBackNavigation(
                    nominatingOriginatingProgram(field), DISCRIMINATING_CALLER_DEFAULT);

            assertAll(
                    () -> assertThat(resolved.getRouteValue()).isEqualTo(expectedWireValue),
                    () -> assertThat(resolved)
                            .as("the nomination was honoured rather than the default applied")
                            .isNotSameAs(DISCRIMINATING_CALLER_DEFAULT));
        }

        @Test
        @DisplayName("the fallback is the calling screen's own and never a global one, so the payment "
                + "screen falls back to the main menu while a menu falls back to sign-on")
        void theFallbackIsPerScreenAndNeverGlobal() {
            final ConversationState nothingNominated = nominatingOriginatingProgram("        ");

            assertAll(
                    () -> assertThat(subject.resolveBackNavigation(nothingNominated,
                            Route.USER_MENU)).isSameAs(Route.USER_MENU),
                    () -> assertThat(subject.resolveBackNavigation(nothingNominated,
                            Route.SIGN_ON)).isSameAs(Route.SIGN_ON),
                    () -> assertThat(subject.resolveBackNavigation(nothingNominated,
                            Route.CARD_LIST)).isSameAs(Route.CARD_LIST));
        }

        @Test
        @DisplayName("an originating field naming nothing reachable fails the navigation rather than "
                + "quietly substituting the calling screen's default")
        void anUnreachableOriginatingFieldFailsTheNavigation() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.resolveBackNavigation(
                            nominatingOriginatingProgram("NOSUCHPG"), DISCRIMINATING_CALLER_DEFAULT))
                    .satisfies(abend -> assertAll(
                            () -> assertThat(abend.code())
                                    .isEqualTo(AbendException.ONLINE_ABEND_CODE),
                            () -> assertThat(abend.culprit()).isEqualTo("NOSUCHPG"),
                            () -> assertThat(abend.reason()).isNotBlank(),
                            () -> assertThat(abend.getMessage()).isNotBlank()));
        }

        /**
         * The bounded width is measured on encoded bytes rather than on the character count, because
         * the legacy field it reproduces reserves eight <em>bytes</em>, and nothing is trimmed before
         * the measurement.
         */
        @Test
        @DisplayName("an over-long nomination is bounded to the eight bytes the legacy field reserves "
                + "before it travels on the failure, so the failure reports the navigation and not a "
                + "width problem")
        void anOverLongNominationIsBoundedToTheLegacyFieldWidth() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.resolveBackNavigation(
                            nominatingOriginatingProgram("NOSUCHPROGRAMNAME"),
                            DISCRIMINATING_CALLER_DEFAULT))
                    .satisfies(abend -> assertAll(
                            () -> assertThat(abend.culprit()).isEqualTo("NOSUCHPR"),
                            () -> assertThat(abend.culprit()
                                    .getBytes(StandardCharsets.US_ASCII).length)
                                    .as("bounded to the legacy field width in bytes")
                                    .isEqualTo(AbendException.CULPRIT_LENGTH)));
        }

        @Test
        @DisplayName("the dangling program definition names nothing reachable, so nominating it fails "
                + "instead of resolving to an invented destination")
        void nominatingTheDanglingProgramDefinitionFails() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.resolveBackNavigation(
                            nominatingOriginatingProgram(DANGLING_PROGRAM_DEFINITION),
                            DISCRIMINATING_CALLER_DEFAULT))
                    .satisfies(abend -> assertThat(abend.culprit())
                            .isEqualTo(DANGLING_PROGRAM_DEFINITION));
        }

        @Test
        @DisplayName("a tab is not blank in the legacy sense, so a field holding one fails rather than "
                + "falling back to the calling screen's default")
        void aTabIsNotBlankInTheLegacySense() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.resolveBackNavigation(
                            nominatingOriginatingProgram("\t"), DISCRIMINATING_CALLER_DEFAULT));
        }

        @Test
        @DisplayName("the nomination rule reads the destination field, which is a different field from "
                + "the one back-navigation reads")
        void theNominationRuleReadsTheDestinationField() {
            assertAll(
                    () -> assertThat(subject.resolveNominatedDestination(
                            nominatingDestinationProgram("COACTVWC"), DISCRIMINATING_CALLER_DEFAULT))
                            .isSameAs(Route.ACCOUNT_VIEW),
                    () -> assertThat(subject.resolveNominatedDestination(
                            nominatingOriginatingProgram("COACTVWC"), DISCRIMINATING_CALLER_DEFAULT))
                            .as("the originating field is invisible to this rule")
                            .isSameAs(DISCRIMINATING_CALLER_DEFAULT));
        }

        @Test
        @DisplayName("a blank destination field applies the calling screen's default, and an "
                + "unreachable one fails, exactly as on the back-navigation path")
        void theDestinationFieldBehavesLikeTheOriginatingField() {
            assertAll(
                    () -> assertThat(subject.resolveNominatedDestination(
                            nominatingDestinationProgram("        "), DISCRIMINATING_CALLER_DEFAULT))
                            .isSameAs(DISCRIMINATING_CALLER_DEFAULT),
                    () -> assertThat(subject.resolveNominatedDestination(
                            nominatingDestinationProgram(null), DISCRIMINATING_CALLER_DEFAULT))
                            .isSameAs(DISCRIMINATING_CALLER_DEFAULT),
                    () -> assertThatExceptionOfType(AbendException.class).isThrownBy(
                            () -> subject.resolveNominatedDestination(
                                    nominatingDestinationProgram("NOSUCHPG"),
                                    DISCRIMINATING_CALLER_DEFAULT)));
        }

        @Test
        @DisplayName("sign-off fixes its own fallback to sign-on, because the source fixes it for the "
                + "two menu programs and for them only")
        void signOffFixesItsOwnFallbackToSignOn() {
            assertAll(
                    () -> assertThat(subject.resolveSignOffRoute(
                            nominatingDestinationProgram("        "))).isSameAs(Route.SIGN_ON),
                    () -> assertThat(subject.resolveSignOffRoute(
                            nominatingDestinationProgram(null))).isSameAs(Route.SIGN_ON),
                    () -> assertThat(subject.resolveSignOffRoute(
                            nominatingDestinationProgram("COSGN00C"))).isSameAs(Route.SIGN_ON));
        }

        @Test
        @DisplayName("sign-off still honours a destination field naming somewhere else, because it is "
                + "the nomination rule with a fixed fallback and not a rule of its own")
        void signOffStillHonoursADestinationFieldNamingSomewhereElse() {
            assertThat(subject.resolveSignOffRoute(nominatingDestinationProgram("COMEN01C")))
                    .isSameAs(Route.USER_MENU);
        }

        @Test
        @DisplayName("both nomination rules require the navigation state and the calling screen's "
                + "default, and sign-off requires the state")
        void bothNominationRulesRequireTheirArguments() {
            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> subject.resolveBackNavigation(null, DISCRIMINATING_CALLER_DEFAULT)),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> subject.resolveBackNavigation(ConversationState.empty(), null)),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> subject.resolveNominatedDestination(null,
                                    DISCRIMINATING_CALLER_DEFAULT)),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> subject.resolveNominatedDestination(ConversationState.empty(),
                                    null)),
                    () -> assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                            () -> subject.resolveSignOffRoute(null)));
        }
    }


    // ----------------------------------------------------------------------------------------
    // The echoed navigation state: read, never mutated, never carried onward.
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the client-echoed navigation state is read and handed back untouched, because the "
            + "legacy communication area is echoed rather than held on the server")
    final class TheEchoedConversationState {

        @Test
        @DisplayName("the originating field drives back-navigation and the destination field drives "
                + "nomination, so a state where the two differ resolves to two different destinations")
        void theTwoProgramFieldsDriveTwoDifferentRules() {
            final ConversationState echoed = fullyEchoedState();

            assertAll(
                    () -> assertThat(subject.resolveBackNavigation(echoed,
                            DISCRIMINATING_CALLER_DEFAULT))
                            .as("the originating field names the payment screen")
                            .isSameAs(Route.BILL_PAYMENT),
                    () -> assertThat(subject.resolveNominatedDestination(echoed,
                            DISCRIMINATING_CALLER_DEFAULT))
                            .as("the destination field names the main menu")
                            .isSameAs(Route.USER_MENU));
        }

        @Test
        @DisplayName("no rule alters the state it was handed, so every one of the sixteen echoed "
                + "components survives a full pass over the navigation rules")
        void noRuleAltersTheStateItWasHanded() {
            final ConversationState echoed = fullyEchoedState();
            final ConversationState pristine = fullyEchoedState();

            subject.isConversationStateAbsent(echoed);
            subject.resolveBackNavigation(echoed, DISCRIMINATING_CALLER_DEFAULT);
            subject.resolveNominatedDestination(echoed, DISCRIMINATING_CALLER_DEFAULT);
            subject.resolveSignOffRoute(echoed);
            subject.resolveAttentionKeyRoute(KeyAction.PFK03, echoed,
                    DISCRIMINATING_CALLER_DEFAULT);
            subject.resolveAttentionKeyRoute(KeyAction.ENTER, echoed,
                    DISCRIMINATING_CALLER_DEFAULT);

            assertThat(echoed)
                    .as("the state handed in is the state still held afterwards")
                    .isEqualTo(pristine);
        }

        @Test
        @DisplayName("not one carried component is dropped: the state still reports every value it was "
                + "built with after the rules have read it")
        void notOneEchoedComponentIsDropped() {
            final ConversationState echoed = fullyEchoedState();

            subject.resolveBackNavigation(echoed, DISCRIMINATING_CALLER_DEFAULT);

            assertAll(
                    () -> assertThat(echoed.fromTransactionId()).isEqualTo("CB00"),
                    () -> assertThat(echoed.fromProgram()).isEqualTo("COBIL00C"),
                    () -> assertThat(echoed.toTransactionId()).isEqualTo("CM00"),
                    () -> assertThat(echoed.toProgram()).isEqualTo("COMEN01C"),
                    () -> assertThat(echoed.entryMode())
                            .isSameAs(ConversationState.EntryMode.RE_ENTRY),
                    () -> assertThat(echoed.reEntry()).isTrue());

            // The identity and cardholder members of the communication-area contract are not part of
            // the state these rules are handed, so there is nothing here for them to drop. Their
            // survival across a turn is asserted where it now happens, on the API-boundary adapter.
            assertThat(ConversationState.class.getRecordComponents())
                    .as("five routing and entry members, and no identifier or cardholder value")
                    .hasSize(5);
        }

        @Test
        @DisplayName("the re-entry state is carried alongside the routing fields and does not change "
                + "which destination a rule selects, because it gates field errors and not navigation")
        void theReEntryStateDoesNotChangeTheSelectedDestination() {
            final ConversationState onFirstEntry = fullyEchoedState().withFirstEntry();
            final ConversationState onReEntry = fullyEchoedState().withReEntry();

            assertAll(
                    () -> assertThat(onFirstEntry.firstEntry()).isTrue(),
                    () -> assertThat(onReEntry.reEntry()).isTrue(),
                    () -> assertThat(subject.resolveBackNavigation(onFirstEntry,
                            DISCRIMINATING_CALLER_DEFAULT))
                            .isSameAs(subject.resolveBackNavigation(onReEntry,
                                    DISCRIMINATING_CALLER_DEFAULT)),
                    () -> assertThat(subject.resolveNominatedDestination(onFirstEntry,
                            DISCRIMINATING_CALLER_DEFAULT))
                            .isSameAs(subject.resolveNominatedDestination(onReEntry,
                                    DISCRIMINATING_CALLER_DEFAULT)));
        }

        @Test
        @DisplayName("marking the state as a first entry or a re-entry leaves both routing fields "
                + "untouched, so the routing decision cannot drift with the field-error gate")
        void markingTheEntryStateLeavesBothRoutingFieldsUntouched() {
            final ConversationState echoed = fullyEchoedState();

            assertAll(
                    () -> assertThat(echoed.withReEntry().fromProgram())
                            .isEqualTo(echoed.fromProgram()),
                    () -> assertThat(echoed.withReEntry().toProgram()).isEqualTo(echoed.toProgram()),
                    () -> assertThat(echoed.withFirstEntry().fromProgram())
                            .isEqualTo(echoed.fromProgram()),
                    () -> assertThat(echoed.withFirstEntry().toProgram())
                            .isEqualTo(echoed.toProgram()));
        }

        @Test
        @DisplayName("the sign-on split is driven by a user-type code passed in explicitly, never by one "
                + "read out of carried state, so an echoed code cannot choose the administrative menu")
        void theSignOnSplitIsDrivenByAnExplicitUserTypeCode() {
            assertAll(
                    () -> assertThat(subject.resolveSignOnRouteForUserTypeCode(
                            STANDARD_USER_TYPE_CODE)).isSameAs(Route.USER_MENU),
                    () -> assertThat(subject.resolveSignOnRouteForUserTypeCode(
                            ADMIN_USER_TYPE_CODE)).isSameAs(Route.ADMIN_MENU));

            // The code has to be supplied by the caller because the carried state does not carry one.
            // That is the point: a route with administrative reach can only be chosen from an identity
            // the caller established, never from a value a client echoed back.
            assertThat(Arrays.stream(ConversationState.class.getRecordComponents())
                            .map(java.lang.reflect.RecordComponent::getName)
                            .toList())
                    .as("no identity member exists in carried state for a route decision to read")
                    .doesNotContain("userId", "userType");
        }
    }

    // ----------------------------------------------------------------------------------------
    // The central claim: a returned route value, and no server-side forward.
    // ----------------------------------------------------------------------------------------

    /**
     * Holds the claim that a navigation decision is a value and never a transfer of control.
     *
     * <p>Two observable facts carry it, and neither depends on a stand-in. The first is the service's
     * own shape: one no-argument constructor and no instance field, so there is nothing it could hand
     * control to and no catalogue it could compose a message from &mdash; see
     * {@link NavigationServiceTest#assertServiceHoldsNothingToForwardTo()}. The second is that the state
     * a caller echoes back is returned unchanged by every rule, which is where a forwarding
     * implementation would have had to record where it went.</p>
     */
    @Nested
    @DisplayName("a navigation decision is a value returned to the client: the legacy transfer of "
            + "control becomes a route constant in the response body and never a server-side forward")
    final class NoServerSideForwarding {

        @Test
        @DisplayName("a full pass over every navigation rule leaves the echoed state untouched and the "
                + "service holds nothing it could have entered or asked for message text")
        void aFullPassOverEveryRuleEntersNothingDownstream() {
            final ConversationState echoed = fullyEchoedState();
            final ConversationState handedOver = fullyEchoedState();

            subject.resolveSignOnRoute(UserType.ADMIN);
            subject.resolveSignOnRouteForUserTypeCode(STANDARD_USER_TYPE_CODE);
            subject.isConversationStateAbsent(echoed);
            subject.resolveAbsentContextRoute();
            subject.isBackNavigationKey(KeyAction.PFK03);
            subject.resolveBackNavigation(echoed, DISCRIMINATING_CALLER_DEFAULT);
            subject.resolveAttentionKeyRoute(KeyAction.PFK03, echoed, DISCRIMINATING_CALLER_DEFAULT);
            subject.resolveNominatedDestination(echoed, DISCRIMINATING_CALLER_DEFAULT);
            subject.resolveSignOffRoute(echoed);
            subject.isDispatchSuppressed("COACTVWC");
            subject.isAdminOnlyOptionDenied(UserType.USER, STANDARD_USER_TYPE_CODE);
            subject.resolveMenuDispatch(UserType.USER, STANDARD_USER_TYPE_CODE, "COACTVWC");
            subject.resolveAdminMenuDispatch("COUSR00C");
            subject.routeForLegacyProgram("COBIL00C");
            subject.routeForLegacyTransactionId("CB00");
            subject.routeForValue(WIRE_BILL_PAYMENT);
            subject.routes();
            subject.adminScopedRoutes();

            assertThat(echoed)
                    .as("every rule in the estate has now been asked, and the state the client echoed "
                            + "is byte-for-byte what it handed over. A rule that forwarded would have to "
                            + "record where it went, and this state is where it would record it")
                    .isEqualTo(handedOver);
            assertServiceHoldsNothingToForwardTo();
        }

        @Test
        @DisplayName("the exit key hands back a route value rather than transferring, and composes no "
                + "message text here, because the common message catalogue owns that text")
        void theExitKeyHandsBackARouteValueAndComposesNoMessageText() {
            final ConversationState echoed = nominatingOriginatingProgram("COSGN00C");

            final Optional<Route> resolved = subject.resolveAttentionKeyRoute(KeyAction.PFK03,
                    echoed, DISCRIMINATING_CALLER_DEFAULT);

            assertAll(
                    () -> assertThat(resolved).contains(Route.SIGN_ON),
                    () -> assertThat(resolved)
                            .get()
                            .extracting(Route::getRouteValue)
                            .isEqualTo(WIRE_SIGN_ON),
                    () -> assertThat(echoed)
                            .as("the exit key selected a destination and left the echoed state exactly "
                                    + "as it arrived, so the client carries the decision forward")
                            .isEqualTo(nominatingOriginatingProgram("COSGN00C")));
            assertServiceHoldsNothingToForwardTo();
        }

        @Test
        @DisplayName("an unmapped key changes no route and composes no message text here, so the "
                + "screen is re-presented by its owning service and not by this one")
        void anUnmappedKeyChangesNoRouteAndComposesNoMessageText() {
            final ConversationState echoed = nominatingOriginatingProgram("COSGN00C");

            final Optional<Route> resolved = subject.resolveAttentionKeyRoute(KeyAction.PFK07,
                    echoed, DISCRIMINATING_CALLER_DEFAULT);

            assertAll(
                    () -> assertThat(resolved)
                            .as("no destination means no route change")
                            .isEmpty(),
                    () -> assertThat(echoed.fromProgram())
                            .as("the state the client will echo back is unchanged")
                            .isEqualTo("COSGN00C"),
                    () -> assertThat(echoed)
                            .as("and unchanged in every other component too, so an unmapped key is a "
                                    + "no-op rather than a partial mutation")
                            .isEqualTo(nominatingOriginatingProgram("COSGN00C")));
            assertServiceHoldsNothingToForwardTo();
        }

        @Test
        @DisplayName("every rule that selects a destination hands back a value, so a caller places the "
                + "route in its own response payload and the client drives the next call")
        void everySelectingRuleHandsBackAValue() {
            final ConversationState echoed = fullyEchoedState();

            assertAll(
                    () -> assertThat(subject.resolveSignOnRoute(UserType.ADMIN)).isNotNull(),
                    () -> assertThat(subject.resolveSignOnRouteForUserTypeCode(
                            ADMIN_USER_TYPE_CODE)).isNotNull(),
                    () -> assertThat(subject.resolveAbsentContextRoute()).isNotNull(),
                    () -> assertThat(subject.resolveBackNavigation(echoed,
                            DISCRIMINATING_CALLER_DEFAULT)).isNotNull(),
                    () -> assertThat(subject.resolveNominatedDestination(echoed,
                            DISCRIMINATING_CALLER_DEFAULT)).isNotNull(),
                    () -> assertThat(subject.resolveSignOffRoute(echoed)).isNotNull(),
                    () -> assertThat(subject.resolveAttentionKeyRoute(KeyAction.PFK03, echoed,
                            DISCRIMINATING_CALLER_DEFAULT)).isPresent(),
                    () -> assertThat(subject.resolveMenuDispatch(UserType.USER,
                            STANDARD_USER_TYPE_CODE, "COACTVWC")).isPresent(),
                    () -> assertThat(subject.resolveAdminMenuDispatch("COUSR00C")).isPresent());
        }

        @Test
        @DisplayName("the service is constructed with no argument at all, so no collaborator can be "
                + "handed to it and no forward is reachable from it")
        void theServiceIsConstructedWithNoArgumentAtAll() {
            final NavigationService independentInstance = new NavigationService();

            assertAll(
                    () -> assertThat(independentInstance.routes())
                            .as("a second instance publishes the same vocabulary, so no state is held")
                            .isEqualTo(subject.routes()),
                    () -> assertThat(independentInstance.resolveSignOnRoute(UserType.ADMIN))
                            .isSameAs(subject.resolveSignOnRoute(UserType.ADMIN)));
        }

        @Test
        @DisplayName("a published route value is named for its role and carries no legacy program "
                + "name, so nothing on the wire could be mistaken for a program to transfer to")
        void aPublishedRouteValueCarriesNoLegacyProgramName() {
            assertThat(subject.routes()).allSatisfy(destination ->
                    assertThat(upperFolded(destination.getRouteValue()))
                            .as("wire value of %s must not name its legacy program", destination)
                            .doesNotContain(destination.getLegacyProgramName()));
        }
    }

}
