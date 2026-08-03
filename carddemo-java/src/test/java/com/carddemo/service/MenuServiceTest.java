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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import com.carddemo.api.dto.MenuResponse;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.config.MenuOptionCatalog;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.exception.AbendException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for the two CardDemo menu transactions.
 *
 * <p>The class under test is the migrated form of {@code app/cbl/COMEN01C.cbl} (transaction
 * {@code CM00}, 7 paragraphs) and {@code app/cbl/COADM01C.cbl} (transaction {@code CA00}, 7
 * paragraphs), reading the option tables of {@code app/cpy/COMEN02Y.cpy} and
 * {@code app/cpy/COADM02Y.cpy}. Every expected message, route token and rendered header item below is a
 * literal declared in this test class, so the oracle is independent of the code it judges: no expected
 * value is obtained by calling the service, the message catalog, the navigation service or the option
 * catalog. Fixed-width padding is written as an explicit repeat count so the count is visible to a
 * reviewer and cannot be stripped by an editor, and no fixed-width value is trimmed before
 * comparison.</p>
 *
 * <p>Three behaviours carry parity traps and are asserted deliberately rather than incidentally.</p>
 *
 * <ol>
 *   <li><em>Option normalisation is right-justified before it is zero-filled.</em> A single typed digit
 *       must become a zero-filled two-digit value, and an entry wider than the two-character field must
 *       lose its excess on the right, because the field cannot hold it.</li>
 *   <li><em>The rejection message carries no space before its three full stops.</em> That distinguishes
 *       it from the sign-on messages, which do carry one.</li>
 *   <li><em>Two branches are unreachable in the shipped estate and are covered here anyway.</em> The
 *       administrator-only gate cannot fire because every entry of the user table carries the
 *       standard-user code, and the placeholder message cannot be composed because no entry names a
 *       program that suppresses dispatch. Both are driven directly, through a stubbed catalog, so the
 *       exact text of each is pinned even though the legacy can never present it.</li>
 * </ol>
 *
 * <p>The two placeholder texts are <strong>deliberately different</strong>, because their source members
 * differ. The user menu delimits the option name by space and supplies no separating space, so its text
 * runs the name's first word into the trailing literal. The administrator menu has the name commented
 * out of the composition altogether, so its text carries no name and reads correctly. Neither is
 * corrected towards the other.</p>
 *
 * <p>The clock is fixed so the two rendered header items are exact rather than approximate. No test here
 * touches a database, a queue, a container or the file system.</p>
 */
@DisplayName("Menu service: the user and administrator menu transactions")
class MenuServiceTest {

    /**
     * A fixed instant, so the rendered header is an exact value. Chosen as the release timestamp carried
     * in the trailer of both legacy members.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:33Z"), ZoneOffset.UTC);

    /** The rendered date the fixed instant produces, in the two-digit month, day and year form. */
    private static final String EXPECTED_HEADER_DATE = "07/19/22";

    /** The rendered time the fixed instant produces. */
    private static final String EXPECTED_HEADER_TIME = "23:12:33";

    /** The rejection message, declared here rather than read from the service. */
    private static final String EXPECTED_RANGE_MESSAGE = "Please enter a valid option number...";

    /** The administrator-only denial message, whose trailing space is part of the literal. */
    private static final String EXPECTED_ADMIN_ONLY_MESSAGE = "No access - Admin Only option... ";

    /** The malformed user-menu placeholder text for option 1, first word only and no separating space. */
    private static final String EXPECTED_USER_PLACEHOLDER = "This option Accountis coming soon ...";

    /** The administrator-menu placeholder text, which carries no option name at all. */
    private static final String EXPECTED_ADMIN_PLACEHOLDER = "This option is coming soon ...";

    /** Declared width of the shared common-message items. */
    private static final int COMMON_MESSAGE_WIDTH = 50;

    /** Visible portion of the shared invalid-key message. */
    private static final String INVALID_KEY_TEXT = "Invalid key pressed. Please see below...";

    /** The shared invalid-key message at its declared width, padded by an explicit repeat count. */
    private static final String EXPECTED_INVALID_KEY_MESSAGE =
            INVALID_KEY_TEXT + " ".repeat(COMMON_MESSAGE_WIDTH - INVALID_KEY_TEXT.length());

    /** The ten user-menu route tokens, in table order. */
    private static final List<String> USER_ROUTES = List.of("account-view", "account-update",
            "card-list", "card-detail", "card-update", "transaction-list", "transaction-view",
            "transaction-add", "report-request", "bill-payment");

    /** The four administrator-menu route tokens, in table order. */
    private static final List<String> ADMIN_ROUTES =
            List.of("user-list", "user-add", "user-update", "user-delete");

    private final MenuOptionCatalog catalog = new MenuOptionCatalog();

    private final MenuService service = new MenuService(new NavigationService(),
            new MessageCatalogService(), catalog, FIXED_CLOCK);

    /** A state marked as a re-entry, which is what makes a turn consult the attention key. */
    private static NavigationContext reEntry() {
        return NavigationContext.empty().withReEntry();
    }

    /** A signed-on state carrying every component the communication area declares. */
    private static NavigationContext signedOnState(NavigationContext.ProgramContext programContext) {
        return new NavigationContext("CC00", "COSGN00C", null, null, "USER0001", "U", programContext,
                "000000042", "MARY", null, "SMITH", "00000000042", "Y", "4111111111111111",
                "COMEN1A", "COMEN01");
    }

    /** A catalog stub that renders the real rows but answers a single lookup with {@code entry}. */
    private static MenuOptionCatalog userCatalogAnswering(MenuOptionCatalog real, int number,
            MenuOptionCatalog.UserMenuOption entry) {
        MenuOptionCatalog stub = Mockito.mock(MenuOptionCatalog.class);
        Mockito.when(stub.userMenuOptionCount()).thenReturn(real.userMenuOptions().size());
        Mockito.when(stub.userMenuOptions()).thenReturn(real.userMenuOptions());
        Mockito.when(stub.findUserOption(number)).thenReturn(Optional.of(entry));
        return stub;
    }

    private MenuService serviceWith(MenuOptionCatalog stub) {
        return new MenuService(new NavigationService(), new MessageCatalogService(), stub, FIXED_CLOCK);
    }

    @Nested
    @DisplayName("Option normalisation right-justifies before it zero-fills")
    class OptionNormalisation {

        @Test
        @DisplayName("a single typed digit becomes a zero-filled two-digit value")
        void singleDigitBecomesZeroFilledTwoDigitValue() {
            assertThat(service.userMenu(reEntry(), KeyAction.ENTER, "5", UserType.USER).nextRoute())
                    .isEqualTo("card-update");
            assertThat(service.userMenu(reEntry(), KeyAction.ENTER, "1", UserType.USER).nextRoute())
                    .isEqualTo("account-view");
            // The echo is observable on a re-sent screen, and it carries the normalised two-digit form.
            assertThat(service.userMenu(reEntry(), KeyAction.ENTER, "0", UserType.USER).selectedOption())
                    .isEqualTo("00");
        }

        @Test
        @DisplayName("a leading blank, a trailing blank and a full two-digit entry all agree")
        void leadingAndTrailingBlanksNormaliseIdentically() {
            assertThat(service.userMenu(reEntry(), KeyAction.ENTER, "1 ", UserType.USER).nextRoute())
                    .isEqualTo("account-view");
            assertThat(service.userMenu(reEntry(), KeyAction.ENTER, " 1", UserType.USER).nextRoute())
                    .isEqualTo("account-view");
            assertThat(service.userMenu(reEntry(), KeyAction.ENTER, "10", UserType.USER).nextRoute())
                    .isEqualTo("bill-payment");
        }

        @Test
        @DisplayName("an entry wider than the field loses its excess on the right")
        void overLongEntryIsBoundedToTheFieldWidth() {
            MenuResponse reply = service.userMenu(reEntry(), KeyAction.ENTER, "123", UserType.USER);
            assertThat(reply.selectedOption()).isEqualTo("12");
            assertThat(reply.message()).isEqualTo(EXPECTED_RANGE_MESSAGE);
        }

        @Test
        @DisplayName("a blank field normalises to the zero option rather than to a non-numeric one")
        void blankFieldNormalisesToTheZeroOption() {
            for (String blank : List.of("", " ", "  ")) {
                MenuResponse reply = service.userMenu(reEntry(), KeyAction.ENTER, blank, UserType.USER);
                assertThat(reply.selectedOption()).as("entry '%s'", blank).isEqualTo("00");
                assertThat(reply.message()).isEqualTo(EXPECTED_RANGE_MESSAGE);
            }
            assertThat(service.userMenu(reEntry(), KeyAction.ENTER, null, UserType.USER).selectedOption())
                    .isEqualTo("00");
        }
    }

    @Nested
    @DisplayName("The range check rejects before it indexes, with one exact message")
    class RangeCheck {

        @ParameterizedTest
        @ValueSource(strings = {"0", "00", "11", "12", "99", "A", "1X", "-1", "", "  "})
        @DisplayName("zero, above the count and non-numeric all produce the same exact text")
        void rejectedEntriesProduceTheExactMessage(String entry) {
            MenuResponse reply = service.userMenu(reEntry(), KeyAction.ENTER, entry, UserType.USER);
            assertThat(reply.message()).isEqualTo(EXPECTED_RANGE_MESSAGE);
            assertThat(reply.errorFlag()).isTrue();
            assertThat(reply.messageSeverity()).isEqualTo(MenuResponse.MessageSeverity.ERROR);
            assertThat(reply.nextRoute()).isEqualTo("user-menu");
            assertThat(reply.focusScreenFieldId()).isEqualTo("OPTION");
            assertThat(reply.userMenuOptions()).hasSize(USER_ROUTES.size());
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "5", "6", "9", "10", "B"})
        @DisplayName("the administrator menu rejects on its own smaller count")
        void administratorRejectionsUseTheAdministratorCount(String entry) {
            MenuResponse reply = service.adminMenu(reEntry(), KeyAction.ENTER, entry);
            assertThat(reply.message()).isEqualTo(EXPECTED_RANGE_MESSAGE);
            assertThat(reply.errorFlag()).isTrue();
            assertThat(reply.nextRoute()).isEqualTo("admin-menu");
            assertThat(reply.adminMenuOptions()).hasSize(ADMIN_ROUTES.size());
        }

        @Test
        @DisplayName("the message carries no space before its three full stops")
        void messageCarriesNoSpaceBeforeTheThreeFullStops() {
            assertThat(MenuService.INVALID_OPTION_MESSAGE).isEqualTo(EXPECTED_RANGE_MESSAGE);
            assertThat(EXPECTED_RANGE_MESSAGE).doesNotContain(" ...");
            assertThat(EXPECTED_RANGE_MESSAGE).endsWith("number...");
        }

        @Test
        @DisplayName("a rejected entry never reaches the option table")
        void rejectedEntryNeverIndexesTheTable() {
            MenuOptionCatalog watched = Mockito.mock(MenuOptionCatalog.class);
            Mockito.when(watched.userMenuOptionCount()).thenReturn(USER_ROUTES.size());
            Mockito.when(watched.userMenuOptions()).thenReturn(catalog.userMenuOptions());
            Mockito.when(watched.findUserOption(ArgumentMatchers.anyInt())).thenThrow(
                    new AssertionError("the option table must not be indexed for a rejected entry"));
            MenuService guarded = serviceWith(watched);
            for (String entry : List.of("11", "12", "0", "99", "A")) {
                assertThat(guarded.userMenu(reEntry(), KeyAction.ENTER, entry, UserType.USER).message())
                        .as("entry '%s'", entry)
                        .isEqualTo(EXPECTED_RANGE_MESSAGE);
            }

            MenuOptionCatalog watchedAdmin = Mockito.mock(MenuOptionCatalog.class);
            Mockito.when(watchedAdmin.adminMenuOptionCount()).thenReturn(ADMIN_ROUTES.size());
            Mockito.when(watchedAdmin.adminMenuOptions()).thenReturn(catalog.adminMenuOptions());
            Mockito.when(watchedAdmin.findAdminOption(ArgumentMatchers.anyInt())).thenThrow(
                    new AssertionError("the option table must not be indexed for a rejected entry"));
            MenuService guardedAdmin = serviceWith(watchedAdmin);
            for (String entry : List.of("5", "6", "9", "0")) {
                assertThat(guardedAdmin.adminMenu(reEntry(), KeyAction.ENTER, entry).message())
                        .as("entry '%s'", entry)
                        .isEqualTo(EXPECTED_RANGE_MESSAGE);
            }
        }
    }

    @Nested
    @DisplayName("Dispatch reaches every catalogued option and hands off the originating identity")
    class Dispatch {

        @Test
        @DisplayName("each of the ten user options reaches its own destination")
        void everyUserOptionReachesItsOwnDestination() {
            for (int option = 1; option <= USER_ROUTES.size(); option++) {
                MenuResponse reply = service.userMenu(reEntry(), KeyAction.ENTER,
                        String.valueOf(option), UserType.USER);
                assertThat(reply.nextRoute()).as("option %d", option)
                        .isEqualTo(USER_ROUTES.get(option - 1));
                assertThat(reply.errorFlag()).isFalse();
                assertThat(reply.message()).isNull();
                assertThat(reply.messageSeverity()).isNull();
            }
        }

        @Test
        @DisplayName("each of the four administrator options reaches its own destination")
        void everyAdministratorOptionReachesItsOwnDestination() {
            for (int option = 1; option <= ADMIN_ROUTES.size(); option++) {
                MenuResponse reply =
                        service.adminMenu(reEntry(), KeyAction.ENTER, String.valueOf(option));
                assertThat(reply.nextRoute()).as("option %d", option)
                        .isEqualTo(ADMIN_ROUTES.get(option - 1));
                assertThat(reply.errorFlag()).isFalse();
                assertThat(reply.message()).isNull();
            }
        }

        @Test
        @DisplayName("the originating identity is saved and the destination opens on a first entry")
        void originatingIdentityIsSavedAndDestinationOpensOnFirstEntry() {
            MenuResponse reply = service.userMenu(signedOnState(
                    NavigationContext.ProgramContext.REENTER), KeyAction.ENTER, "9", UserType.USER);
            NavigationContext handOff = reply.navigationContext();

            assertThat(reply.nextRoute()).isEqualTo("report-request");
            assertThat(handOff.fromTransactionId()).isEqualTo("CM00");
            assertThat(handOff.fromProgram()).isEqualTo("COMEN01C");
            assertThat(handOff.firstEntry()).isTrue();
            // The two identity moves are commented out in the source, so both components survive.
            assertThat(handOff.userId()).isEqualTo("USER0001");
            assertThat(handOff.userType()).isEqualTo("U");
            // Nothing outside the routing components is disturbed.
            assertThat(handOff.customerId()).isEqualTo("000000042");
            assertThat(handOff.customerFirstName()).isEqualTo("MARY");
            assertThat(handOff.customerLastName()).isEqualTo("SMITH");
            assertThat(handOff.accountId()).isEqualTo("00000000042");
            assertThat(handOff.accountStatus()).isEqualTo("Y");
            assertThat(handOff.cardNumber()).isEqualTo("4111111111111111");
            assertThat(handOff.lastMap()).isEqualTo("COMEN1A");
            assertThat(handOff.lastMapset()).isEqualTo("COMEN01");
        }

        @Test
        @DisplayName("the administrator menu hands off its own transaction and program identity")
        void administratorDispatchSavesItsOwnIdentity() {
            MenuResponse reply = service.adminMenu(
                    signedOnState(NavigationContext.ProgramContext.REENTER), KeyAction.ENTER, "3");
            assertThat(reply.nextRoute()).isEqualTo("user-update");
            assertThat(reply.navigationContext().fromTransactionId()).isEqualTo("CA00");
            assertThat(reply.navigationContext().fromProgram()).isEqualTo("COADM01C");
            assertThat(reply.navigationContext().firstEntry()).isTrue();
        }

        @Test
        @DisplayName("a transfer renders no header and hints no focus, yet still carries its rows")
        void transferRendersNoHeaderAndHintsNoFocus() {
            MenuResponse reply = service.userMenu(reEntry(), KeyAction.ENTER, "1", UserType.USER);
            assertThat(reply.currentDate()).isNull();
            assertThat(reply.currentTime()).isNull();
            assertThat(reply.focusScreenFieldId()).isNull();
            assertThat(reply.userMenuOptions()).hasSize(USER_ROUTES.size());
        }
    }

    @Nested
    @DisplayName("The administrator-only gate: unreachable in the estate, covered here")
    class AdministratorOnlyGate {

        @Test
        @DisplayName("it fires for a standard user and keeps the literal's trailing space")
        void firesForAStandardUserAndKeepsTheTrailingSpace() {
            MenuService gated = serviceWith(userCatalogAnswering(catalog, 8,
                    new MenuOptionCatalog.UserMenuOption(8, "Transaction Add", "COTRN02C", "A")));
            MenuResponse reply = gated.userMenu(reEntry(), KeyAction.ENTER, "8", UserType.USER);

            assertThat(reply.message()).isEqualTo(EXPECTED_ADMIN_ONLY_MESSAGE);
            assertThat(reply.message()).endsWith("... ");
            assertThat(reply.errorFlag()).isTrue();
            assertThat(reply.messageSeverity()).isEqualTo(MenuResponse.MessageSeverity.ERROR);
            assertThat(reply.nextRoute()).isEqualTo("user-menu");
            assertThat(reply.selectedOption()).isEqualTo("08");
            assertThat(MenuService.ADMIN_ONLY_OPTION_MESSAGE).isEqualTo(EXPECTED_ADMIN_ONLY_MESSAGE);
        }

        @Test
        @DisplayName("an administrator and an absent type satisfy neither condition, so neither trips it")
        void neitherAnAdministratorNorAnAbsentTypeTripsTheGate() {
            MenuService gated = serviceWith(userCatalogAnswering(catalog, 8,
                    new MenuOptionCatalog.UserMenuOption(8, "Transaction Add", "COTRN02C", "A")));
            assertThat(gated.userMenu(reEntry(), KeyAction.ENTER, "8", UserType.ADMIN).nextRoute())
                    .isEqualTo("transaction-add");
            assertThat(gated.userMenu(reEntry(), KeyAction.ENTER, "8", null).nextRoute())
                    .isEqualTo("transaction-add");
        }

        @Test
        @DisplayName("no shipped user option can trip it, and option 8 is not role-gated")
        void noShippedOptionCanTripTheGate() {
            for (MenuOptionCatalog.UserMenuOption option : catalog.userMenuOptions()) {
                assertThat(option.userType()).as("option %d", option.number()).isEqualTo("U");
            }
            assertThat(service.userMenu(reEntry(), KeyAction.ENTER, "8", UserType.USER).nextRoute())
                    .isEqualTo("transaction-add");
        }

        @Test
        @DisplayName("the administrator menu has no such gate at all")
        void administratorMenuHasNoSuchGate() {
            for (int option = 1; option <= ADMIN_ROUTES.size(); option++) {
                assertThat(service.adminMenu(reEntry(), KeyAction.ENTER, String.valueOf(option))
                        .errorFlag()).as("option %d", option).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("The placeholder message: malformed in one member, name-free in the other")
    class PlaceholderMessage {

        @Test
        @DisplayName("the user menu renders the first word only, with no separating space")
        void userMenuRendersFirstWordOnlyWithNoSeparatingSpace() {
            MenuService withDummy = serviceWith(userCatalogAnswering(catalog, 1,
                    new MenuOptionCatalog.UserMenuOption(1, "Account View", "DUMMYPGM", "U")));
            MenuResponse reply = withDummy.userMenu(reEntry(), KeyAction.ENTER, "1", UserType.USER);

            assertThat(reply.message()).isEqualTo(EXPECTED_USER_PLACEHOLDER);
            assertThat(reply.message()).doesNotContain("Account is");
            assertThat(reply.message()).doesNotContain("Account View");
            assertThat(reply.messageSeverity()).isEqualTo(MenuResponse.MessageSeverity.INFORMATIONAL);
            assertThat(reply.errorFlag()).isFalse();
            assertThat(reply.nextRoute()).isEqualTo("user-menu");
            assertThat(reply.focusScreenFieldId()).isEqualTo("OPTION");
            assertThat(reply.selectedOption()).isEqualTo("01");
        }

        @Test
        @DisplayName("a multi-word name is truncated at its first space, whichever option it is")
        void aMultiWordNameIsTruncatedAtItsFirstSpace() {
            MenuService withDummy = serviceWith(userCatalogAnswering(catalog, 6,
                    new MenuOptionCatalog.UserMenuOption(6, "Transaction List", "DUMMY001", "U")));
            assertThat(withDummy.userMenu(reEntry(), KeyAction.ENTER, "6", UserType.USER).message())
                    .isEqualTo("This option Transactionis coming soon ...");
        }

        @Test
        @DisplayName("a name filling the whole field with no space is transferred entire")
        void aNameWithNoSpaceAtAllIsTransferredEntire() {
            // Every shipped label is shorter than its 35-character field and is therefore space-filled,
            // so the space delimiter always stops inside the field. A label that fills the field with no
            // space leaves nothing to stop on, and the whole field is transferred instead.
            String unbrokenName = "A".repeat(35);
            MenuService withDummy = serviceWith(userCatalogAnswering(catalog, 4,
                    new MenuOptionCatalog.UserMenuOption(4, unbrokenName, "DUMMY002", "U")));
            assertThat(withDummy.userMenu(reEntry(), KeyAction.ENTER, "4", UserType.USER).message())
                    .isEqualTo("This option " + unbrokenName + "is coming soon ...");
        }

        @Test
        @DisplayName("the administrator menu carries no option name, because the source comments it out")
        void administratorMenuCarriesNoOptionName() {
            MenuOptionCatalog stub = Mockito.mock(MenuOptionCatalog.class);
            Mockito.when(stub.adminMenuOptionCount()).thenReturn(ADMIN_ROUTES.size());
            Mockito.when(stub.adminMenuOptions()).thenReturn(catalog.adminMenuOptions());
            Mockito.when(stub.findAdminOption(2)).thenReturn(Optional.of(
                    new MenuOptionCatalog.AdminMenuOption(2, "User Add (Security)", "DUMMYPGM")));

            MenuResponse reply = serviceWith(stub).adminMenu(reEntry(), KeyAction.ENTER, "2");
            assertThat(reply.message()).isEqualTo(EXPECTED_ADMIN_PLACEHOLDER);
            assertThat(reply.message()).doesNotContain("User");
            assertThat(reply.messageSeverity()).isEqualTo(MenuResponse.MessageSeverity.INFORMATIONAL);
            assertThat(reply.errorFlag()).isFalse();
        }

        @Test
        @DisplayName("no shipped option names a suppressing program, so neither text can be presented")
        void noShippedOptionNamesASuppressingProgram() {
            for (MenuOptionCatalog.UserMenuOption option : catalog.userMenuOptions()) {
                assertThat(option.programName()).as("option %d", option.number())
                        .doesNotStartWith("DUMMY");
            }
            for (MenuOptionCatalog.AdminMenuOption option : catalog.adminMenuOptions()) {
                assertThat(option.programName()).as("option %d", option.number())
                        .doesNotStartWith("DUMMY");
            }
        }
    }

    @Nested
    @DisplayName("The main paragraph's four arms")
    class MainParagraph {

        @Test
        @DisplayName("a turn carrying no prior state returns to sign-on and carries nothing forward")
        void noPriorStateReturnsToSignOnCarryingNothingForward() {
            for (NavigationContext absent : List.of(NavigationContext.empty())) {
                MenuResponse reply = service.userMenu(absent, null, null, null);
                assertThat(reply.nextRoute()).isEqualTo("sign-on");
                assertThat(reply.navigationContext()).isEqualTo(NavigationContext.empty());
                assertThat(reply.errorFlag()).isFalse();
                assertThat(reply.message()).isNull();
                assertThat(reply.userMenuOptions()).hasSize(USER_ROUTES.size());
            }
            assertThat(service.userMenu(null, null, null, null).nextRoute()).isEqualTo("sign-on");

            MenuResponse adminReply = service.adminMenu(null, null, null);
            assertThat(adminReply.nextRoute()).isEqualTo("sign-on");
            assertThat(adminReply.navigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(adminReply.adminMenuOptions()).hasSize(ADMIN_ROUTES.size());
        }

        @Test
        @DisplayName("a first entry sends the screen, marks the state as a re-entry and echoes nothing")
        void firstEntrySendsTheScreenAndMarksTheStateAsReEntry() {
            MenuResponse reply = service.userMenu(
                    signedOnState(NavigationContext.ProgramContext.ENTER), null, null, UserType.USER);

            assertThat(reply.navigationContext().reEntry()).isTrue();
            assertThat(reply.selectedOption()).isNull();
            assertThat(reply.message()).isNull();
            assertThat(reply.messageSeverity()).isNull();
            assertThat(reply.errorFlag()).isFalse();
            assertThat(reply.nextRoute()).isEqualTo("user-menu");
            assertThat(reply.focusScreenFieldId()).isEqualTo("OPTION");
            assertThat(reply.transactionName()).isEqualTo("CM00");
            assertThat(reply.programName()).isEqualTo("COMEN01C");
            assertThat(reply.currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(reply.currentTime()).isEqualTo(EXPECTED_HEADER_TIME);
            assertThat(reply.userMenuOptions()).hasSize(USER_ROUTES.size());
            assertThat(reply.userMenuOptions().get(0).number()).isEqualTo(1);
            assertThat(reply.userMenuOptions().get(0).label()).isEqualTo("Account View");
            assertThat(reply.userMenuOptions().get(9).number()).isEqualTo(10);
            assertThat(reply.userMenuOptions().get(9).label()).isEqualTo("Bill Payment");
        }

        @Test
        @DisplayName("a first entry on the administrator menu identifies its own transaction")
        void firstEntryOnTheAdministratorMenuIdentifiesItself() {
            MenuResponse reply = service.adminMenu(
                    signedOnState(NavigationContext.ProgramContext.ENTER), null, null);
            assertThat(reply.transactionName()).isEqualTo("CA00");
            assertThat(reply.programName()).isEqualTo("COADM01C");
            assertThat(reply.currentDate()).isEqualTo(EXPECTED_HEADER_DATE);
            assertThat(reply.currentTime()).isEqualTo(EXPECTED_HEADER_TIME);
            assertThat(reply.nextRoute()).isEqualTo("admin-menu");
            assertThat(reply.adminMenuOptions()).hasSize(ADMIN_ROUTES.size());
            assertThat(reply.adminMenuOptions().get(0).label()).isEqualTo("User List (Security)");
            assertThat(reply.adminMenuOptions().get(3).label()).isEqualTo("User Delete (Security)");
        }

        @Test
        @DisplayName("the exit key returns to sign-on even when the client nominated somewhere else")
        void exitKeyReturnsToSignOnOverAnyClientNomination() {
            NavigationContext nominatingElsewhere = new NavigationContext(null, "COACTVWC", null,
                    "COBIL00C", "USER0001", "U", NavigationContext.ProgramContext.REENTER, null,
                    null, null, null, null, null, null, null, null);
            MenuResponse reply =
                    service.userMenu(nominatingElsewhere, KeyAction.PFK03, null, UserType.USER);
            assertThat(reply.nextRoute()).isEqualTo("sign-on");
            assertThat(reply.errorFlag()).isFalse();
            assertThat(reply.navigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(service.adminMenu(nominatingElsewhere, KeyAction.PFK03, null).nextRoute())
                    .isEqualTo("sign-on");
        }

        @ParameterizedTest
        @ValueSource(strings = {"PFK01", "PFK04", "PFK07", "PFK12", "CLEAR", "PA1", "PA2"})
        @DisplayName("every other key raises the error switch and carries the untrimmed common message")
        void everyOtherKeyRaisesTheErrorSwitch(String keyName) {
            KeyAction key = KeyAction.valueOf(keyName);
            MenuResponse reply = service.userMenu(reEntry(), key, "1", UserType.USER);
            assertThat(reply.message()).isEqualTo(EXPECTED_INVALID_KEY_MESSAGE);
            assertThat(reply.message()).hasSize(COMMON_MESSAGE_WIDTH);
            assertThat(reply.errorFlag()).isTrue();
            assertThat(reply.messageSeverity()).isEqualTo(MenuResponse.MessageSeverity.ERROR);
            assertThat(reply.selectedOption()).isNull();
            assertThat(reply.nextRoute()).isEqualTo("user-menu");
            assertThat(service.adminMenu(reEntry(), key, "1").message())
                    .isEqualTo(EXPECTED_INVALID_KEY_MESSAGE);
        }

        @Test
        @DisplayName("an absent key takes the same arm as any unmapped one")
        void anAbsentKeyTakesTheUnmappedArm() {
            MenuResponse reply = service.userMenu(reEntry(), null, "1", UserType.USER);
            assertThat(reply.message()).isEqualTo(EXPECTED_INVALID_KEY_MESSAGE);
            assertThat(reply.errorFlag()).isTrue();
            assertThat(service.adminMenu(reEntry(), null, "1").message())
                    .isEqualTo(EXPECTED_INVALID_KEY_MESSAGE);
        }
    }

    @Nested
    @DisplayName("The two failure paths, both unreachable while the catalog and the routes agree")
    class FailurePaths {

        @Test
        @DisplayName("a table that disagrees with its own count terminates rather than showing a screen")
        void aTableDisagreeingWithItsCountTerminates() {
            MenuOptionCatalog inconsistent = Mockito.mock(MenuOptionCatalog.class);
            Mockito.when(inconsistent.userMenuOptionCount()).thenReturn(USER_ROUTES.size());
            Mockito.when(inconsistent.findUserOption(3)).thenReturn(Optional.empty());
            MenuService broken = serviceWith(inconsistent);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> broken.userMenu(reEntry(), KeyAction.ENTER, "3", UserType.USER))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo("9999");
                        assertThat(abend.culprit()).isEqualTo("COMEN01C");
                        assertThat(abend.reason()).hasSizeLessThanOrEqualTo(50);
                        assertThat(abend.getMessage()).contains("MENU OPTION 3");
                    });

            MenuOptionCatalog inconsistentAdmin = Mockito.mock(MenuOptionCatalog.class);
            Mockito.when(inconsistentAdmin.adminMenuOptionCount()).thenReturn(ADMIN_ROUTES.size());
            Mockito.when(inconsistentAdmin.findAdminOption(2)).thenReturn(Optional.empty());
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> serviceWith(inconsistentAdmin)
                            .adminMenu(reEntry(), KeyAction.ENTER, "2"))
                    .satisfies(abend -> assertThat(abend.culprit()).isEqualTo("COADM01C"));
        }

        @Test
        @DisplayName("an option naming an unreachable program terminates as the legacy transfer would")
        void anOptionNamingAnUnreachableProgramTerminates() {
            MenuService broken = serviceWith(userCatalogAnswering(catalog, 3,
                    new MenuOptionCatalog.UserMenuOption(3, "Credit Card List", "COZZZZZZ", "U")));
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> broken.userMenu(reEntry(), KeyAction.ENTER, "3", UserType.USER))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo("9999");
                        assertThat(abend.culprit()).isEqualTo("COMEN01C");
                        assertThat(abend.reason()).isEqualTo("XCTL TO UNRESOLVABLE PROGRAM NAME");
                    });

            MenuOptionCatalog stub = Mockito.mock(MenuOptionCatalog.class);
            Mockito.when(stub.adminMenuOptionCount()).thenReturn(ADMIN_ROUTES.size());
            Mockito.when(stub.adminMenuOptions()).thenReturn(catalog.adminMenuOptions());
            Mockito.when(stub.findAdminOption(1)).thenReturn(Optional.of(
                    new MenuOptionCatalog.AdminMenuOption(1, "User List (Security)", "COZZZZZZ")));
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> serviceWith(stub).adminMenu(reEntry(), KeyAction.ENTER, "1"))
                    .satisfies(abend -> assertThat(abend.culprit()).isEqualTo("COADM01C"));
        }
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("every collaborator is required")
        void everyCollaboratorIsRequired() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> new MenuService(
                    null, new MessageCatalogService(), catalog, FIXED_CLOCK));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> new MenuService(
                    new NavigationService(), null, catalog, FIXED_CLOCK));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> new MenuService(
                    new NavigationService(), new MessageCatalogService(), null, FIXED_CLOCK));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> new MenuService(
                    new NavigationService(), new MessageCatalogService(), catalog, null));
        }

        @Test
        @DisplayName("the published identifiers match the legacy work-storage values")
        void publishedIdentifiersMatchTheLegacyValues() {
            assertThat(MenuService.USER_MENU_TRANSACTION_ID).isEqualTo("CM00");
            assertThat(MenuService.USER_MENU_PROGRAM_NAME).isEqualTo("COMEN01C");
            assertThat(MenuService.ADMIN_MENU_TRANSACTION_ID).isEqualTo("CA00");
            assertThat(MenuService.ADMIN_MENU_PROGRAM_NAME).isEqualTo("COADM01C");
            assertThat(MenuService.SIGN_ON_PROGRAM_NAME).isEqualTo("COSGN00C");
            assertThat(MenuService.OPTION_FIELD_WIDTH).isEqualTo(2);
            assertThat(MenuService.OPTION_SCREEN_FIELD_ID).isEqualTo("OPTION");
        }
    }
}
