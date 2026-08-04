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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.carddemo.api.dto.MenuResponse;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.ConversationState;
import com.carddemo.service.MenuService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit tests for {@link MenuResponseAdapter}, which maps one menu turn result onto the response the
 * client receives.
 *
 * <p><strong>Why this adapter exists.</strong> {@link MenuService} used to return the transport record
 * directly, which made the service depend upward on the API package and inverted the specification's
 * layering rule. It now returns a result type it owns, and this class is the single place that result
 * becomes a {@link MenuResponse}. Having the mapping in one testable place is what lets the transaction
 * and program identity assertions live here rather than in the service test.
 *
 * <p><strong>The relocated identity assertions.</strong> Two of the tests below assert that the user
 * menu is published as legacy transaction {@code CM00} driven by {@code app/cbl/COMEN01C.cbl} and the
 * administrative menu as {@code CA00} driven by {@code app/cbl/COADM01C.cbl}, with the header items
 * written from the transport's own per-menu constants at {@code app/cbl/COMEN01C.cbl} lines 218 to 219
 * and {@code app/cbl/COADM01C.cbl} lines 208 to 209. Those assertions were made in
 * {@code MenuServiceTest} while the service built the response itself. The service now reports only
 * <em>which</em> menu a turn describes, so the choice of response shape - and with it the pair of names
 * - is this adapter's, and it is asserted where the decision is made.
 *
 * <p><strong>What must not appear.</strong> The adapter chooses a shape and widens rows; it resolves no
 * route, formats no message and reads no identity. The mutually-exclusive option collections are
 * asserted in both directions, because a response carrying both would tell a client to read two shapes
 * at once.
 *
 * <p>A pure unit test: no Spring context, no connection, no container. The conversion for the navigation
 * record is the real {@link ConversationStateAdapter} rather than a double, because the two together are
 * what a response actually carries and a double would only restate this file's own expectations.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @since 1.0.0
 */
@DisplayName("MenuResponseAdapter :: one menu turn result becomes one menu response")
final class MenuResponseAdapterTest {

    /** The identifier of the authenticated principal. */
    private static final String AUTHENTICATED_USER_ID = "ADMIN001";

    /** Subject under test. */
    private MenuResponseAdapter subject;

    /** Sets up the adapter with the real navigation-record conversion. */
    @BeforeEach
    void setUp() {
        subject = new MenuResponseAdapter(new ConversationStateAdapter());
    }

    /**
     * The number of rows the user menu presents, restated from {@code app/cpy/COMEN02Y.cpy}, which
     * declares ten populated entries inside an {@code OCCURS 12} table.
     */
    private static final int USER_ROW_COUNT = 10;

    /**
     * The number of rows the administrative menu presents, restated from {@code app/cpy/COADM02Y.cpy},
     * which declares four populated entries inside an {@code OCCURS 9} table.
     */
    private static final int ADMIN_ROW_COUNT = 4;

    /**
     * Builds the exact number of rows the given menu presents, numbered from one and labelled so that
     * a reordering is visible in the label as well as the number.
     *
     * <p>The count is not arbitrary: {@link MenuResponse} refuses a collection of any other size,
     * because both legacy catalogs are fixed and both programs iterate the whole of theirs. The
     * fixtures therefore fill the catalog rather than a sample of it.
     *
     * @param count how many rows to build
     * @return the rows in catalog order
     */
    private static List<MenuService.MenuRow> rowsOf(final int count) {
        final List<MenuService.MenuRow> rows = new ArrayList<>(count);
        for (int number = 1; number <= count; number++) {
            rows.add(new MenuService.MenuRow(number, "Option " + number));
        }
        return rows;
    }

    /**
     * Returns the number of rows the given menu presents.
     *
     * @param kind which menu the result describes
     * @return the catalog row count for that menu
     */
    private static int rowCountFor(final MenuService.MenuKind kind) {
        return kind == MenuService.MenuKind.ADMIN_MENU ? ADMIN_ROW_COUNT : USER_ROW_COUNT;
    }

    /**
     * Builds a turn result of the given kind with every component populated and its catalog filled.
     *
     * @param kind which menu the result describes
     * @return a fully populated turn result
     */
    private static MenuService.MenuScreen screenOf(final MenuService.MenuKind kind) {
        return new MenuService.MenuScreen(
                kind,
                "CardDemo",
                "Main Menu",
                "07/19/22",
                "14:30:00",
                rowsOf(rowCountFor(kind)),
                "01",
                "Please enter a valid option",
                MenuService.MessageSeverity.ERROR,
                true,
                "OPTION",
                "user-menu",
                new ConversationState("CM00", "COMEN01C", "CAVW", "COACTVWC",
                        ConversationState.EntryMode.RE_ENTRY));
    }

    /**
     * Builds a turn result of the given kind whose catalog is filled but whose other components are
     * absent, for the assertions that are about a single carried component.
     *
     * @param kind which menu the result describes
     * @param message the message to carry, which may be {@code null}
     * @param severity the severity to carry, which may be {@code null}
     * @return a turn result carrying only the rows, the message and the severity
     */
    private static MenuService.MenuScreen bareScreenOf(final MenuService.MenuKind kind,
                                                       final String message,
                                                       final MenuService.MessageSeverity severity) {
        return new MenuService.MenuScreen(kind, null, null, null, null, rowsOf(rowCountFor(kind)),
                null, message, severity, false, null, null, null);
    }

    // ----------------------------------------------------------------------------------------
    // The relocated identity assertions: which menu, and therefore which pair of names
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the menu kind selects the response shape, and with it the transaction and program "
            + "identity the header carries")
    final class TheMenuKindSelectsTheResponseShape {

        @Test
        @DisplayName("a user-menu result publishes transaction CM00 and program COMEN01C, and carries "
                + "the user option collection alone")
        void aUserMenuResultPublishesItsOwnTransactionAndProgram() {
            // Relocated from MenuServiceTest. The service reports only the kind; the pair of names is
            // supplied by the response factory this adapter chooses, so the assertion belongs here.
            final MenuResponse response = subject.toResponse(
                    screenOf(MenuService.MenuKind.USER_MENU), NavigationContext.empty(),
                    AUTHENTICATED_USER_ID, UserType.USER);

            assertThat(response.transactionName())
                    .isEqualTo(MenuResponse.USER_MENU_TRANSACTION_NAME)
                    .isEqualTo("CM00");
            assertThat(response.programName())
                    .isEqualTo(MenuResponse.USER_MENU_PROGRAM_NAME)
                    .isEqualTo("COMEN01C");
            assertThat(response.userMenuOptions()).isNotNull();
            assertThat(response.adminMenuOptions())
                    .as("the two collections are mutually exclusive, so a client reads one shape")
                    .isNull();
        }

        @Test
        @DisplayName("an administrator-menu result publishes transaction CA00 and program COADM01C, "
                + "and carries the administrator option collection alone")
        void anAdministratorMenuResultPublishesItsOwnTransactionAndProgram() {
            final MenuResponse response = subject.toResponse(
                    screenOf(MenuService.MenuKind.ADMIN_MENU), NavigationContext.empty(),
                    AUTHENTICATED_USER_ID, UserType.ADMIN);

            assertThat(response.transactionName())
                    .isEqualTo(MenuResponse.ADMIN_MENU_TRANSACTION_NAME)
                    .isEqualTo("CA00");
            assertThat(response.programName())
                    .isEqualTo(MenuResponse.ADMIN_MENU_PROGRAM_NAME)
                    .isEqualTo("COADM01C");
            assertThat(response.adminMenuOptions()).isNotNull();
            assertThat(response.userMenuOptions())
                    .as("the two collections are mutually exclusive, so a client reads one shape")
                    .isNull();
        }

        @Test
        @DisplayName("the two menus publish different transaction and program names, so the pair is "
                + "genuinely per-menu rather than one constant reused")
        void theTwoMenusPublishDifferentNames() {
            final MenuResponse user = subject.toResponse(
                    screenOf(MenuService.MenuKind.USER_MENU), null, null, null);
            final MenuResponse admin = subject.toResponse(
                    screenOf(MenuService.MenuKind.ADMIN_MENU), null, null, null);

            assertThat(user.transactionName()).isNotEqualTo(admin.transactionName());
            assertThat(user.programName()).isNotEqualTo(admin.programName());
        }

        @ParameterizedTest(name = "{0} produces a response")
        @EnumSource(MenuService.MenuKind.class)
        @DisplayName("both kinds are handled, so no menu falls through the shape choice unanswered")
        void bothKindsAreHandled(final MenuService.MenuKind kind) {
            final MenuResponse response = subject.toResponse(screenOf(kind), null, null, null);

            assertThat(response.transactionName()).isNotBlank();
            assertThat(response.programName()).isNotBlank();
            assertThat(response.carriesUserMenu())
                    .as("exactly one of the two collections is present for %s", kind)
                    .isNotEqualTo(response.carriesAdminMenu());
        }
    }

    // ----------------------------------------------------------------------------------------
    // The rows: widened in order, never reordered and never renumbered
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the rows are widened in catalog order, and neither renumbered nor relabelled")
    final class TheRowsAreWidenedInOrder {

        @Test
        @DisplayName("all ten user rows keep their number, their label and their order")
        void userRowsKeepTheirNumberLabelAndOrder() {
            final MenuResponse response = subject.toResponse(
                    screenOf(MenuService.MenuKind.USER_MENU), null, null, null);

            assertThat(response.userMenuOptions()).hasSize(USER_ROW_COUNT);
            for (int index = 0; index < USER_ROW_COUNT; index++) {
                assertThat(response.userMenuOptions().get(index).number())
                        .as("row %s keeps its own number", index)
                        .isEqualTo(index + 1);
                assertThat(response.userMenuOptions().get(index).label())
                        .as("row %s keeps its own label, so a reordering is visible", index)
                        .isEqualTo("Option " + (index + 1));
            }
        }

        @Test
        @DisplayName("all four administrator rows keep their number, their label and their order")
        void administratorRowsKeepTheirNumberLabelAndOrder() {
            final MenuResponse response = subject.toResponse(
                    screenOf(MenuService.MenuKind.ADMIN_MENU), null, null, null);

            assertThat(response.adminMenuOptions()).hasSize(ADMIN_ROW_COUNT);
            for (int index = 0; index < ADMIN_ROW_COUNT; index++) {
                assertThat(response.adminMenuOptions().get(index).number()).isEqualTo(index + 1);
                assertThat(response.adminMenuOptions().get(index).label())
                        .isEqualTo("Option " + (index + 1));
            }
        }

        @Test
        @DisplayName("an under-filled catalog is refused rather than padded, because both legacy "
                + "catalogs are fixed and a short screen would present fewer options than the "
                + "program offers")
        void anUnderFilledCatalogIsRefusedRatherThanPadded() {
            final MenuService.MenuScreen shortUser = new MenuService.MenuScreen(
                    MenuService.MenuKind.USER_MENU, null, null, null, null,
                    rowsOf(USER_ROW_COUNT - 1), null, null, null, false, null, null, null);
            final MenuService.MenuScreen shortAdmin = new MenuService.MenuScreen(
                    MenuService.MenuKind.ADMIN_MENU, null, null, null, null,
                    rowsOf(ADMIN_ROW_COUNT - 1), null, null, null, false, null, null, null);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> subject.toResponse(shortUser, null, null, null))
                    .withMessageContaining(String.valueOf(USER_ROW_COUNT));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> subject.toResponse(shortAdmin, null, null, null))
                    .withMessageContaining(String.valueOf(ADMIN_ROW_COUNT));
        }

        @Test
        @DisplayName("an over-filled catalog is refused too, so the adapter cannot widen a collection "
                + "the screen has no positions for")
        void anOverFilledCatalogIsRefused() {
            final MenuService.MenuScreen longAdmin = new MenuService.MenuScreen(
                    MenuService.MenuKind.ADMIN_MENU, null, null, null, null,
                    rowsOf(ADMIN_ROW_COUNT + 1), null, null, null, false, null, null, null);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> subject.toResponse(longAdmin, null, null, null))
                    .withMessageContaining(String.valueOf(ADMIN_ROW_COUNT));
        }

        @Test
        @DisplayName("a row label may be absent, because a catalog row the legacy leaves blank is a "
                + "state the screen presents rather than an error")
        void aRowLabelMayBeAbsent() {
            final List<MenuService.MenuRow> withBlank = new ArrayList<>(rowsOf(ADMIN_ROW_COUNT));
            withBlank.set(ADMIN_ROW_COUNT - 1, new MenuService.MenuRow(ADMIN_ROW_COUNT, null));
            final MenuService.MenuScreen blankLabel = new MenuService.MenuScreen(
                    MenuService.MenuKind.ADMIN_MENU, null, null, null, null, withBlank,
                    null, null, null, false, null, null, null);

            final MenuResponse response = subject.toResponse(blankLabel, null, null, null);

            assertThat(response.adminMenuOptions()).hasSize(ADMIN_ROW_COUNT);
            assertThat(response.adminMenuOptions().get(ADMIN_ROW_COUNT - 1).number())
                    .isEqualTo(ADMIN_ROW_COUNT);
            assertThat(response.adminMenuOptions().get(ADMIN_ROW_COUNT - 1).label()).isNull();
        }

        @Test
        @DisplayName("the widened collection is detached from a caller's list, so a later change to "
                + "that list cannot alter a response already produced")
        void theWidenedCollectionIsDetachedFromTheCallersList() {
            final List<MenuService.MenuRow> mutable = new ArrayList<>(rowsOf(USER_ROW_COUNT));
            final MenuService.MenuScreen screen = new MenuService.MenuScreen(
                    MenuService.MenuKind.USER_MENU, null, null, null, null, mutable,
                    null, null, null, false, null, null, null);

            final MenuResponse response = subject.toResponse(screen, null, null, null);
            mutable.clear();

            assertThat(response.userMenuOptions()).hasSize(USER_ROW_COUNT);
            assertThat(response.userMenuOptions().get(0).label()).isEqualTo("Option 1");
        }
    }

    // ----------------------------------------------------------------------------------------
    // Everything else is carried, not decided
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("every other component is carried across rather than decided here")
    final class EveryOtherComponentIsCarriedAcross {

        @Test
        @DisplayName("the header items, the echoed option, the message, the error indicator, the focus "
                + "field and the route all cross unchanged")
        void everyCarriedComponentCrossesUnchanged() {
            final MenuResponse response = subject.toResponse(
                    screenOf(MenuService.MenuKind.USER_MENU), null, null, null);

            assertThat(response.title01()).isEqualTo("CardDemo");
            assertThat(response.title02()).isEqualTo("Main Menu");
            assertThat(response.currentDate()).isEqualTo("07/19/22");
            assertThat(response.currentTime()).isEqualTo("14:30:00");
            assertThat(response.selectedOption()).isEqualTo("01");
            assertThat(response.message()).isEqualTo("Please enter a valid option");
            assertThat(response.errorFlag()).isTrue();
            assertThat(response.focusScreenFieldId()).isEqualTo("OPTION");
            assertThat(response.nextRoute())
                    .as("the route is resolved by the navigation authority and merely carried here")
                    .isEqualTo("user-menu");
        }

        @ParameterizedTest(name = "{0} maps onto the transport severity of the same name")
        @EnumSource(MenuService.MessageSeverity.class)
        @DisplayName("each service severity maps onto the transport severity of the same name, so "
                + "neither vocabulary can drift from the other unnoticed")
        void eachSeverityMapsOntoTheSameName(final MenuService.MessageSeverity severity) {
            final MenuResponse response = subject.toResponse(
                    bareScreenOf(MenuService.MenuKind.USER_MENU, "a message", severity),
                    null, null, null);

            assertThat(response.messageSeverity()).isNotNull();
            assertThat(response.messageSeverity().name()).isEqualTo(severity.name());
        }

        @Test
        @DisplayName("both vocabularies name the same two severities, so a constant added to one and "
                + "not the other fails here rather than at the first response that needs it")
        void bothVocabulariesNameTheSameTwoSeverities() {
            assertThat(Arrays.stream(MenuService.MessageSeverity.values()).map(Enum::name).toList())
                    .containsExactlyElementsOf(
                            Arrays.stream(MenuResponse.MessageSeverity.values())
                                    .map(Enum::name).toList());
        }

        @Test
        @DisplayName("an absent severity stays absent rather than being defaulted, because a turn "
                + "reporting nothing carries no message and therefore no severity")
        void anAbsentSeverityStaysAbsent() {
            assertThat(subject.toResponse(
                    bareScreenOf(MenuService.MenuKind.USER_MENU, null, null), null, null, null)
                    .messageSeverity()).isNull();
        }

        @Test
        @DisplayName("the navigation record on the response is the reconciled one, so the identity it "
                + "carries is the authenticated principal's rather than the client's echo")
        void theNavigationRecordOnTheResponseIsReconciled() {
            final NavigationContext echoed = new NavigationContext(
                    "CB00", "COBIL00C", "CM00", "COMEN01C", "STALEUSR", UserType.USER.getCode(),
                    NavigationContext.ProgramContext.REENTER, "000000123", "MARY", "ANN", "SMITH",
                    "00000000011", "Y", "4111111111111111", "COBIL0A", "COBIL00");

            final MenuResponse response = subject.toResponse(
                    screenOf(MenuService.MenuKind.ADMIN_MENU), echoed, AUTHENTICATED_USER_ID,
                    UserType.ADMIN);

            assertThat(response.navigationContext().userId()).isEqualTo(AUTHENTICATED_USER_ID);
            assertThat(response.navigationContext().userType()).isEqualTo(UserType.ADMIN.getCode());
            assertThat(response.navigationContext().customerLastName())
                    .as("the members the service never saw are still carried through")
                    .isEqualTo("SMITH");
            assertThat(response.navigationContext().fromProgram())
                    .as("the routing the service recorded is what the response publishes")
                    .isEqualTo("COMEN01C");
        }

        @Test
        @DisplayName("an absent echoed record still yields a publishable navigation record, so a first "
                + "turn is answerable")
        void anAbsentEchoedRecordStillYieldsAPublishableRecord() {
            final MenuResponse response = subject.toResponse(
                    screenOf(MenuService.MenuKind.USER_MENU), null, AUTHENTICATED_USER_ID,
                    UserType.ADMIN);

            assertThat(response.navigationContext()).isNotNull();
            assertThat(response.navigationContext().userId()).isEqualTo(AUTHENTICATED_USER_ID);
            assertThat(response.navigationContext().customerId()).isNull();
        }
    }

    // ----------------------------------------------------------------------------------------
    // The adapter itself
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the adapter is a boundary mapping and holds no decision of its own")
    final class TheAdapterIsABoundaryMapping {

        @Test
        @DisplayName("refuses an absent turn result rather than inventing a response for it")
        void refusesAnAbsentTurnResult() {
            assertThatNullPointerException()
                    .isThrownBy(() -> subject.toResponse(null, null, null, null))
                    .withMessageContaining("screen");
        }

        @Test
        @DisplayName("refuses construction without the navigation-record conversion, because a "
                + "response that skipped reconciliation would carry a client-supplied identity")
        void refusesConstructionWithoutTheNavigationConversion() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new MenuResponseAdapter(null))
                    .withMessageContaining("conversationStateAdapter");
        }

        @Test
        @DisplayName("declares exactly the one public conversion, so no route is resolved and no "
                + "message is composed at the boundary")
        void declaresExactlyOnePublicConversion() {
            assertThat(Arrays.stream(MenuResponseAdapter.class.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic())
                    .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                    .map(java.lang.reflect.Method::getName)
                    .toList())
                    .containsExactly("toResponse");
        }

        @Test
        @DisplayName("repeated conversions of the same result are equal, so the boundary carries "
                + "nothing over between turns")
        void repeatedConversionsAreEqual() {
            assertThat(subject.toResponse(screenOf(MenuService.MenuKind.USER_MENU),
                    NavigationContext.empty(), AUTHENTICATED_USER_ID, UserType.ADMIN))
                    .isEqualTo(subject.toResponse(screenOf(MenuService.MenuKind.USER_MENU),
                            NavigationContext.empty(), AUTHENTICATED_USER_ID, UserType.ADMIN));
        }
    }
}
