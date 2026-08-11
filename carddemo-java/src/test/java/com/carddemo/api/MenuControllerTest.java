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

import com.carddemo.api.dto.MenuResponse;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.config.MenuOptionCatalog;
import com.carddemo.config.SecurityConfig;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.MenuService;
import com.carddemo.service.MessageCatalogService;
import com.carddemo.service.NavigationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test for {@link MenuController}, the REST surface of transactions {@code CM00} and
 * {@code CA00}.
 *
 * <p><strong>What this proves.</strong> Two documented operations are mapped, the administrative one
 * beneath the prefix the filter chain gates and the ordinary one outside it; each binds the three
 * inputs a legacy menu turn was driven by, delegates once, and publishes the service's answer
 * unaltered. The behaviour the boundary must not distort is asserted through it rather than around it:
 * ten user rows and four administrative rows with no unused table slot serialised, the active label of
 * the eighth user option rather than the commented alternative, blank-to-zero normalisation of the
 * option field, the two rejection texts, the administrator-only text including its trailing space, and
 * the two deliberately different suppressed-dispatch texts.
 *
 * <p><strong>Why the alignment assertion lives here.</strong> The controller may not import the
 * configuration layer - the module's layering forbids it and {@code PackageLayeringTest} asserts the
 * absence - so the administrative address is a literal in the controller. This test is free to read
 * both, and it pins them together, which is what makes that literal safe: were the address to drift out
 * from under the gated prefix the route would silently admit any signed-on caller, and
 * {@link TheDeliveredOperationInventory#theAdministrativeRouteSitsBeneathTheGatedPrefix()} fails
 * instead.
 *
 * <p>The controller is driven through a standalone {@code MockMvc} where the assertion is about
 * binding, status or payload shape, and invoked directly where the assertion is about a value the
 * payload carries. The service beneath it is real over the delivered option catalogue, because a mocked
 * service could not show that the counts, the labels and the messages reach the response intact; the
 * catalogue is replaced by a stub only for the two branches the delivered tables cannot reach - a
 * suppressed dispatch and the administrator-only option - which exist in the source and must still be
 * shown to travel.
 *
 * <p>No COBOL statement is transcribed.
 */
@DisplayName("MenuController :: the delivered menu operations")
class MenuControllerTest {

    /** Fixed clock at the upstream release stamp, so the header date and time are deterministic. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:33Z"), ZoneOffset.UTC);

    /** A seeded administrator identifier. */
    private static final String ADMIN_USER_ID = "ADMIN001";

    /** A seeded ordinary-user identifier. */
    private static final String ORDINARY_USER_ID = "USER0001";

    /** The program a turn arriving from sign-on names as its origin. */
    private static final String SIGN_ON_PROGRAM = "COSGN00C";

    /** Serialises the echoed navigation record for the request body. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Registry the turn timer is registered against. */
    private MeterRegistry meterRegistry;

    /** The controller under test, over the delivered option catalogue. */
    private MenuController controller;

    /** Standalone servlet harness over that controller. */
    private MockMvc mockMvc;

    /** Assembles the controller over a real service reading the delivered catalogue. */
    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        controller = controllerOver(new MenuOptionCatalog(), meterRegistry);
        mockMvc = harnessOver(controller);
    }

    /**
     * Builds a controller whose service reads the supplied option catalogue.
     *
     * @param catalog the option catalogue the service is to read
     * @param registry the registry the turn timer is registered against
     * @return the assembled controller
     */
    private static MenuController controllerOver(final MenuOptionCatalog catalog,
                                                 final MeterRegistry registry) {
        final MenuService menuService = new MenuService(new NavigationService(),
                new MessageCatalogService(), catalog, FIXED_CLOCK);
        final ConversationStateAdapter conversationStateAdapter = new ConversationStateAdapter(new NavigationService());
        return new MenuController(menuService, conversationStateAdapter,
                new MenuResponseAdapter(conversationStateAdapter), registry);
    }

    /**
     * Builds a standalone servlet harness over a controller, with the shared failure surface installed
     * so that a rejected request is answered the way the running module answers it.
     *
     * @param subject the controller to drive
     * @return the harness
     */
    private static MockMvc harnessOver(final MenuController subject) {
        return MockMvcBuilders.standaloneSetup(subject)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * Builds an established identity carrying the authority the filter chain grants for a user type.
     *
     * @param userId the identifier the credential names
     * @param userType the type the credential carries
     * @return the authentication a handler receives
     */
    private static Authentication authenticated(final String userId, final UserType userType) {
        return new PreAuthenticatedAuthenticationToken(userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + userType.name())));
    }

    /**
     * Builds an echoed navigation record standing at a first entry into the menu.
     *
     * @return the record
     */
    private static NavigationContext firstEntry() {
        return new NavigationContext(null, SIGN_ON_PROGRAM, null, null, null, null,
                NavigationContext.ProgramContext.ENTER, null, null, null, null, null, null, null,
                null, null);
    }

    /**
     * Builds an echoed navigation record standing at a continuation turn, which is the state in which
     * the legacy program reads the option field.
     *
     * @return the record
     */
    private static NavigationContext reEntry() {
        return firstEntry().withReEntry();
    }

    /**
     * Renders a navigation record as the request body.
     *
     * @param context the record to render
     * @return its JSON form
     * @throws Exception if rendering fails, which fails the test
     */
    private static String body(final NavigationContext context) throws Exception {
        return JSON.writeValueAsString(context);
    }

    /**
     * Builds a user catalogue of the delivered size whose first option names the suppressed-dispatch
     * marker and carries a chosen user-type code.
     *
     * <p>The full ten entries are supplied because the transport contract refuses a user menu of any
     * other size, which is itself the guarantee that no row count may drift. Only the first entry
     * differs from a delivered one, and it is the only entry any test here selects.
     *
     * @param userTypeCode the one-character user-type code the first entry carries
     * @return a stubbed catalogue of ten entries
     */
    private static MenuOptionCatalog userCatalogueWithSuppressedDispatch(final String userTypeCode) {
        final MenuOptionCatalog.UserMenuOption suppressed = new MenuOptionCatalog.UserMenuOption(1,
                "Transaction Add", NavigationService.DUMMY_PROGRAM_PREFIX + "PGM", userTypeCode);
        final List<MenuOptionCatalog.UserMenuOption> options = new ArrayList<>();
        options.add(suppressed);
        for (int number = 2; number <= MenuOptionCatalog.USER_MENU_OPTION_COUNT; number++) {
            options.add(new MenuOptionCatalog.UserMenuOption(number, "Account View", "COACTVWC",
                    MenuOptionCatalog.STANDARD_USER_TYPE_CODE));
        }

        final MenuOptionCatalog catalog = mock(MenuOptionCatalog.class);
        when(catalog.userMenuOptionCount())
                .thenReturn(MenuOptionCatalog.USER_MENU_OPTION_COUNT);
        when(catalog.userMenuOptions()).thenReturn(List.copyOf(options));
        when(catalog.findUserOption(1)).thenReturn(Optional.of(suppressed));
        return catalog;
    }

    /**
     * Builds an administrative catalogue of the delivered size whose first option names the
     * suppressed-dispatch marker, for the same reason the user catalogue above carries all ten.
     *
     * @return a stubbed catalogue of four entries
     */
    private static MenuOptionCatalog adminCatalogueWithSuppressedDispatch() {
        final MenuOptionCatalog.AdminMenuOption suppressed = new MenuOptionCatalog.AdminMenuOption(1,
                "User List (Security)", NavigationService.DUMMY_PROGRAM_PREFIX + "PGM");
        final List<MenuOptionCatalog.AdminMenuOption> options = new ArrayList<>();
        options.add(suppressed);
        for (int number = 2; number <= MenuOptionCatalog.ADMIN_MENU_OPTION_COUNT; number++) {
            options.add(new MenuOptionCatalog.AdminMenuOption(number, "User Add (Security)",
                    "COUSR01C"));
        }

        final MenuOptionCatalog catalog = mock(MenuOptionCatalog.class);
        when(catalog.adminMenuOptionCount())
                .thenReturn(MenuOptionCatalog.ADMIN_MENU_OPTION_COUNT);
        when(catalog.adminMenuOptions()).thenReturn(List.copyOf(options));
        when(catalog.findAdminOption(1)).thenReturn(Optional.of(suppressed));
        return catalog;
    }

    /**
     * Reads the handler methods the class publishes.
     *
     * @return every method carrying a POST mapping, in declaration order
     */
    private static List<Method> handlers() {
        return Arrays.stream(MenuController.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(PostMapping.class) != null)
                .toList();
    }

    /**
     * Reads the single POST mapping of a named handler.
     *
     * @param name the handler's method name
     * @return its mapping
     */
    private static PostMapping mappingOf(final String name) {
        return handlers().stream()
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no POST handler named " + name))
                .getAnnotation(PostMapping.class);
    }

    /** The two operations the class publishes, and where they are published. */
    @Nested
    @DisplayName("the delivered operation inventory")
    class TheDeliveredOperationInventory {

        @Test
        @DisplayName("the class is a REST controller and maps each menu at its own address rather than "
                + "at a shared prefix, because the two addresses are gated differently")
        void theClassIsARestControllerMappingEachMenuSeparately() {
            assertThat(MenuController.class.getAnnotation(RestController.class))
                    .as("without this the class is not scanned and publishes nothing")
                    .isNotNull();
            assertThat(MenuController.class.getAnnotation(RequestMapping.class))
                    .as("a class-level prefix common to both routes would put the administrative one "
                            + "outside the gated prefix or the ordinary one inside it")
                    .isNull();
        }

        @Test
        @DisplayName("exactly two documented JSON handlers are published, one per menu transaction, so "
                + "no third surface has been added to a contract that has two")
        void twoDocumentedJsonHandlersArePublished() {
            assertThat(handlers()).hasSize(2);
            assertThat(handlers().stream().map(Method::getName).toList())
                    .containsExactlyInAnyOrder("userMenu", "adminMenu");

            for (final Method handler : handlers()) {
                final PostMapping post = handler.getAnnotation(PostMapping.class);
                assertThat(post.consumes()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
                assertThat(post.produces()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
                assertThat(handler.getAnnotation(Operation.class))
                        .as("an undocumented operation publishes a path with no description")
                        .isNotNull();
                assertThat(handler.getAnnotation(ApiResponses.class)).isNotNull();
                assertThat(handler.getReturnType()).isEqualTo(MenuResponse.class);
            }
            assertThat(mappingOf("userMenu").path())
                    .containsExactly(MenuController.USER_MENU_PATH);
            assertThat(mappingOf("adminMenu").path())
                    .containsExactly(MenuController.ADMIN_MENU_PATH);
        }

        @Test
        @DisplayName("the administrative route sits beneath the prefix the filter chain gates, and the "
                + "ordinary route sits outside it, so neither entitlement is granted by accident")
        void theAdministrativeRouteSitsBeneathTheGatedPrefix() {
            // The controller may not import the configuration layer, so the address is a literal there
            // and this is what holds it in place. A drift out from under the prefix would leave the
            // administrative menu answered by the chain's catch-all, which admits any signed-on caller.
            assertThat(MenuController.ADMIN_MENU_PATH)
                    .startsWith(SecurityConfig.ADMIN_PATH_PREFIX + "/");
            assertThat(MenuController.USER_MENU_PATH)
                    .doesNotStartWith(SecurityConfig.ADMIN_PATH_PREFIX);
            assertThat(MenuController.USER_MENU_PATH).startsWith("/api/");
        }

        @Test
        @DisplayName("neither route is the anonymous sign-on route and neither shadows the operational "
                + "surfaces, so a menu turn can never be reached without a credential")
        void neitherRouteIsAnonymousAndNeitherShadowsTheOperationalSurfaces() {
            assertThat(List.of(MenuController.USER_MENU_PATH, MenuController.ADMIN_MENU_PATH))
                    .doesNotContain(SecurityConfig.SIGN_ON_PATH)
                    .allSatisfy(path -> assertThat(path).doesNotStartWith("/actuator"));
        }

        @Test
        @DisplayName("both turns answer at their literal addresses /api/menu and /api/admin/menu, so the "
                + "documented addresses are the served ones and not merely the same constant twice")
        void bothTurnsAnswerAtTheirLiteralAddresses() throws Exception {
            // Written out rather than assembled from the constants the mappings use: an expectation built
            // from the mapping's own constant moves with it and can never disagree with it, which is how
            // a wrong verb and a missing operation survived in the published route inventory. The whole
            // surface is compared against one independent literal oracle in DeliveredApiSurfaceOracleTest;
            // these are the two menu addresses, asserted where the turns' behaviour is specified.
            mockMvc.perform(post("/api/menu")
                            .principal(authenticated(ORDINARY_USER_ID, UserType.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(firstEntry())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionName")
                            .value(MenuService.USER_MENU_TRANSACTION_ID));
            mockMvc.perform(post("/api/admin/menu")
                            .principal(authenticated(ADMIN_USER_ID, UserType.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(firstEntry())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionName")
                            .value(MenuService.ADMIN_MENU_TRANSACTION_ID));
        }

        @Test
        @DisplayName("the controller holds no rule of its own: its only state is its collaborators, so "
                + "there is nowhere for a catalogue, a route table or a message literal to be kept")
        void theControllerHoldsNoRuleOfItsOwn() {
            assertThat(Arrays.stream(MenuController.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .map(field -> field.getType().getSimpleName())
                    .toList())
                    .containsExactlyInAnyOrder("MenuService", "ConversationStateAdapter",
                            "MenuResponseAdapter", "MeterRegistry");
        }

        @Test
        @DisplayName("every collaborator is required, so a part-wired controller cannot be constructed")
        void everyCollaboratorIsRequired() {
            final MenuService menuService = new MenuService(new NavigationService(),
                    new MessageCatalogService(), new MenuOptionCatalog(), FIXED_CLOCK);
            final ConversationStateAdapter stateAdapter = new ConversationStateAdapter(new NavigationService());
            final MenuResponseAdapter responseAdapter = new MenuResponseAdapter(stateAdapter);

            assertThatNullPointerException().isThrownBy(() ->
                    new MenuController(null, stateAdapter, responseAdapter, meterRegistry));
            assertThatNullPointerException().isThrownBy(() ->
                    new MenuController(menuService, null, responseAdapter, meterRegistry));
            assertThatNullPointerException().isThrownBy(() ->
                    new MenuController(menuService, stateAdapter, null, meterRegistry));
            assertThatNullPointerException().isThrownBy(() ->
                    new MenuController(menuService, stateAdapter, responseAdapter, null));
        }
    }

    /** One turn of the main menu, legacy transaction {@code CM00}. */
    @Nested
    @DisplayName("one turn of the main menu")
    class OneTurnOfTheMainMenu {

        @Test
        @DisplayName("a first entry renders the ten delivered rows, the screen header and the identity "
                + "header items, and carries no administrative collection")
        void aFirstEntryRendersTheTenDeliveredRows() throws Exception {
            mockMvc.perform(post(MenuController.USER_MENU_PATH)
                            .principal(authenticated(ORDINARY_USER_ID, UserType.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(firstEntry())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionName")
                            .value(MenuService.USER_MENU_TRANSACTION_ID))
                    .andExpect(jsonPath("$.programName").value(MenuService.USER_MENU_PROGRAM_NAME))
                    .andExpect(jsonPath("$.title01").value(MessageCatalogService.CCDA_TITLE01))
                    .andExpect(jsonPath("$.title02").value(MessageCatalogService.CCDA_TITLE02))
                    .andExpect(jsonPath("$.currentDate").value("07/19/22"))
                    .andExpect(jsonPath("$.currentTime").value("23:12:33"))
                    .andExpect(jsonPath("$.focusScreenFieldId")
                            .value(MenuService.OPTION_SCREEN_FIELD_ID))
                    .andExpect(jsonPath("$.userMenuOptions.length()")
                            .value(MenuOptionCatalog.USER_MENU_OPTION_COUNT))
                    .andExpect(jsonPath("$.adminMenuOptions").isEmpty())
                    .andExpect(jsonPath("$.errorFlag").value(false));
        }

        @Test
        @DisplayName("the rows are the ten populated table entries numbered one to ten, so none of the "
                + "twelve declared storage slots is serialised as an empty row")
        void theRowsAreTheTenPopulatedEntriesAndNoUnusedSlot() {
            final MenuResponse response = controller.userMenu(firstEntry(), null, null,
                    authenticated(ORDINARY_USER_ID, UserType.USER));

            assertThat(response.userMenuOptions())
                    .hasSize(MenuOptionCatalog.USER_MENU_OPTION_COUNT)
                    .extracting(MenuResponse.UserMenuOption::number)
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            assertThat(response.userMenuOptions())
                    .extracting(MenuResponse.UserMenuOption::label)
                    .allSatisfy(label -> assertThat(label).isNotBlank());
        }

        @Test
        @DisplayName("the eighth row carries the active label and not the commented alternative, which "
                + "stays inactive")
        void theEighthRowCarriesTheActiveLabel() {
            final MenuResponse response = controller.userMenu(firstEntry(), null, null,
                    authenticated(ORDINARY_USER_ID, UserType.USER));

            assertThat(response.userMenuOptions().get(7).label()).isEqualTo("Transaction Add");
            assertThat(response.userMenuOptions())
                    .extracting(MenuResponse.UserMenuOption::label)
                    .allSatisfy(label -> assertThat(label).doesNotContain("(Admin Only)"));
        }

        @Test
        @DisplayName("a blank option entry normalises to zero before the range test, so it is rejected "
                + "by number and echoed as two zero digits")
        void aBlankOptionEntryNormalisesToZeroAndIsRejected() {
            final MenuResponse response = controller.userMenu(reEntry(), KeyAction.ENTER, "  ",
                    authenticated(ORDINARY_USER_ID, UserType.USER));

            assertThat(response.message()).isEqualTo(MenuService.INVALID_OPTION_MESSAGE);
            assertThat(response.messageSeverity()).isEqualTo(MenuResponse.MessageSeverity.ERROR);
            assertThat(response.errorFlag()).isTrue();
            assertThat(response.selectedOption()).isEqualTo("00");
        }

        @Test
        @DisplayName("an absent option entry is treated as the blank field the terminal would have "
                + "transmitted, and is rejected the same way")
        void anAbsentOptionEntryIsTreatedAsABlankField() {
            final MenuResponse response = controller.userMenu(reEntry(), KeyAction.ENTER, null,
                    authenticated(ORDINARY_USER_ID, UserType.USER));

            assertThat(response.message()).isEqualTo(MenuService.INVALID_OPTION_MESSAGE);
            assertThat(response.selectedOption()).isEqualTo("00");
        }

        @Test
        @DisplayName("a one-character entry is right-justified and zero-filled, so the character lands "
                + "in the rightmost position of the two-character field")
        void aOneCharacterEntryIsRightJustifiedAndZeroFilled() {
            final MenuResponse response = controller.userMenu(reEntry(), KeyAction.ENTER, "z",
                    authenticated(ORDINARY_USER_ID, UserType.USER));

            assertThat(response.selectedOption()).isEqualTo("0z");
            assertThat(response.message()).isEqualTo(MenuService.INVALID_OPTION_MESSAGE);
        }

        @Test
        @DisplayName("a single digit selects the option of that number, so the first option dispatches "
                + "to the account-view route rather than being rejected")
        void aSingleDigitSelectsThatOption() {
            final MenuResponse response = controller.userMenu(reEntry(), KeyAction.ENTER, "1",
                    authenticated(ORDINARY_USER_ID, UserType.USER));

            assertThat(response.nextRoute()).isEqualTo(NavigationService.Routes.ACCOUNT_VIEW);
            assertThat(response.message()).isNull();
            assertThat(response.errorFlag()).isFalse();
        }

        @Test
        @DisplayName("an entry beyond the delivered count is rejected and echoed unchanged")
        void anEntryBeyondTheDeliveredCountIsRejected() {
            final MenuResponse response = controller.userMenu(reEntry(), KeyAction.ENTER, "11",
                    authenticated(ORDINARY_USER_ID, UserType.USER));

            assertThat(response.message()).isEqualTo(MenuService.INVALID_OPTION_MESSAGE);
            assertThat(response.selectedOption()).isEqualTo("11");
        }

        @Test
        @DisplayName("a turn carrying no navigation state at all returns to sign-on, which is the "
                + "zero-length communication area branch, and a body-less request reaches it")
        void aTurnCarryingNoNavigationStateReturnsToSignOn() throws Exception {
            mockMvc.perform(post(MenuController.USER_MENU_PATH)
                            .principal(authenticated(ORDINARY_USER_ID, UserType.USER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextRoute").value(NavigationService.Routes.SIGN_ON));
        }

        @Test
        @DisplayName("an unmapped attention key emits the common notice at its declared width, "
                + "untrimmed, and raises the error indicator")
        void anUnmappedAttentionKeyEmitsTheCommonNotice() {
            final MenuResponse response = controller.userMenu(reEntry(), KeyAction.PFK05, null,
                    authenticated(ORDINARY_USER_ID, UserType.USER));

            assertThat(response.message()).isEqualTo(MessageCatalogService.CCDA_MSG_INVALID_KEY);
            assertThat(response.message()).hasSize(MessageCatalogService.COMMON_MESSAGE_WIDTH);
            assertThat(response.errorFlag()).isTrue();
        }

        @Test
        @DisplayName("the third program-function key leaves the menu for sign-on")
        void theThirdProgramFunctionKeyLeavesTheMenu() {
            final MenuResponse response = controller.userMenu(reEntry(), KeyAction.PFK03, null,
                    authenticated(ORDINARY_USER_ID, UserType.USER));

            assertThat(response.nextRoute()).isEqualTo(NavigationService.Routes.SIGN_ON);
        }

        @Test
        @DisplayName("an echoed field wider than the communication area declares is refused before the "
                + "service runs")
        void anEchoedFieldWiderThanTheCommunicationAreaIsRefused() throws Exception {
            final NavigationContext overWide = new NavigationContext("CM000", SIGN_ON_PROGRAM, null,
                    null, null, null, NavigationContext.ProgramContext.ENTER, null, null, null, null,
                    null, null, null, null, null);

            mockMvc.perform(post(MenuController.USER_MENU_PATH)
                            .principal(authenticated(ORDINARY_USER_ID, UserType.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(overWide)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("an attention key outside the declared vocabulary is refused rather than silently "
                + "ignored")
        void anAttentionKeyOutsideTheVocabularyIsRefused() throws Exception {
            mockMvc.perform(post(MenuController.USER_MENU_PATH)
                            .principal(authenticated(ORDINARY_USER_ID, UserType.USER))
                            .param(MenuController.KEY_ACTION_PARAMETER, "NOT-A-KEY")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(reEntry())))
                    .andExpect(status().isBadRequest());
        }
    }

    /** One turn of the administrative menu, legacy transaction {@code CA00}. */
    @Nested
    @DisplayName("one turn of the administrative menu")
    class OneTurnOfTheAdministrativeMenu {

        @Test
        @DisplayName("a first entry renders the four delivered rows under its own transaction and "
                + "program names, and carries no user collection")
        void aFirstEntryRendersTheFourDeliveredRows() throws Exception {
            mockMvc.perform(post(MenuController.ADMIN_MENU_PATH)
                            .principal(authenticated(ADMIN_USER_ID, UserType.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(firstEntry())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionName")
                            .value(MenuService.ADMIN_MENU_TRANSACTION_ID))
                    .andExpect(jsonPath("$.programName").value(MenuService.ADMIN_MENU_PROGRAM_NAME))
                    .andExpect(jsonPath("$.adminMenuOptions.length()")
                            .value(MenuOptionCatalog.ADMIN_MENU_OPTION_COUNT))
                    .andExpect(jsonPath("$.userMenuOptions").isEmpty())
                    .andExpect(jsonPath("$.title01").value(MessageCatalogService.CCDA_TITLE01));
        }

        @Test
        @DisplayName("the rows are the four populated table entries numbered one to four, so none of "
                + "the nine declared storage slots is serialised as an empty row")
        void theRowsAreTheFourPopulatedEntriesAndNoUnusedSlot() {
            final MenuResponse response = controller.adminMenu(firstEntry(), null, null,
                    authenticated(ADMIN_USER_ID, UserType.ADMIN));

            assertThat(response.adminMenuOptions())
                    .hasSize(MenuOptionCatalog.ADMIN_MENU_OPTION_COUNT)
                    .extracting(MenuResponse.AdminMenuOption::number)
                    .containsExactly(1, 2, 3, 4);
            assertThat(response.userMenuOptions()).isNull();
        }

        @Test
        @DisplayName("the first option dispatches to the user-list route")
        void theFirstOptionDispatchesToTheUserList() {
            final MenuResponse response = controller.adminMenu(reEntry(), KeyAction.ENTER, "1",
                    authenticated(ADMIN_USER_ID, UserType.ADMIN));

            assertThat(response.nextRoute()).isEqualTo(NavigationService.Routes.USER_LIST);
            assertThat(response.message()).isNull();
        }

        @Test
        @DisplayName("an entry beyond the delivered count is rejected with the same text the main menu "
                + "uses, because both programs carry the same literal")
        void anEntryBeyondTheDeliveredCountIsRejected() {
            final MenuResponse response = controller.adminMenu(reEntry(), KeyAction.ENTER, "5",
                    authenticated(ADMIN_USER_ID, UserType.ADMIN));

            assertThat(response.message()).isEqualTo(MenuService.INVALID_OPTION_MESSAGE);
            assertThat(response.selectedOption()).isEqualTo("05");
            assertThat(response.errorFlag()).isTrue();
        }

        @Test
        @DisplayName("the handler performs no entitlement check of its own, which is why the gate has "
                + "to be the filter chain's rule over the administrative prefix")
        void theHandlerPerformsNoEntitlementCheckOfItsOwn() {
            // Invoked directly with an ordinary identity, which the chain would never have admitted to
            // this address. The turn is still served, and that is the point being recorded: the
            // entitlement lives in one place, and it is not here. The assertion above -
            // theAdministrativeRouteSitsBeneathTheGatedPrefix - is what keeps that one place effective.
            final MenuResponse response = controller.adminMenu(firstEntry(), null, null,
                    authenticated(ORDINARY_USER_ID, UserType.USER));

            assertThat(response.transactionName()).isEqualTo(MenuService.ADMIN_MENU_TRANSACTION_ID);
            assertThat(response.adminMenuOptions())
                    .hasSize(MenuOptionCatalog.ADMIN_MENU_OPTION_COUNT);
        }
    }

    /**
     * The two source branches the delivered option tables cannot reach: a suppressed dispatch, which
     * needs an option naming the dummy-program marker, and the administrator-only refusal, which needs
     * a user-catalogue entry carrying the administrative code. Both exist in the source and both must
     * still be shown to travel out through this boundary unaltered.
     */
    @Nested
    @DisplayName("the branches the delivered tables cannot reach")
    class TheBranchesTheDeliveredTablesCannotReach {

        @Test
        @DisplayName("the main menu's suppressed-dispatch text names the option, following the source "
                + "delimiter semantics, and reports informationally rather than as an error")
        void theMainMenuSuppressedDispatchTextNamesTheOption() {
            final MenuController subject = controllerOver(
                    userCatalogueWithSuppressedDispatch(MenuOptionCatalog.STANDARD_USER_TYPE_CODE),
                    new SimpleMeterRegistry());

            final MenuResponse response = subject.userMenu(reEntry(), KeyAction.ENTER, "1",
                    authenticated(ORDINARY_USER_ID, UserType.USER));

            assertThat(response.message()).isEqualTo("This option Transactionis coming soon ...");
            assertThat(response.messageSeverity())
                    .isEqualTo(MenuResponse.MessageSeverity.INFORMATIONAL);
            assertThat(response.errorFlag()).isFalse();
        }

        @Test
        @DisplayName("the administrative menu's suppressed-dispatch text omits the option name, "
                + "because that operand is commented out in its own program")
        void theAdministrativeSuppressedDispatchTextOmitsTheOptionName() {
            final MenuController subject = controllerOver(adminCatalogueWithSuppressedDispatch(),
                    new SimpleMeterRegistry());

            final MenuResponse response = subject.adminMenu(reEntry(), KeyAction.ENTER, "1",
                    authenticated(ADMIN_USER_ID, UserType.ADMIN));

            assertThat(response.message()).isEqualTo("This option is coming soon ...");
            assertThat(response.messageSeverity())
                    .isEqualTo(MenuResponse.MessageSeverity.INFORMATIONAL);
        }

        @Test
        @DisplayName("the two suppressed-dispatch texts are not the same text, so the two behaviours "
                + "have not been merged into one")
        void theTwoSuppressedDispatchTextsAreNotTheSame() {
            final MenuResponse fromUserMenu = controllerOver(
                    userCatalogueWithSuppressedDispatch(MenuOptionCatalog.STANDARD_USER_TYPE_CODE),
                    new SimpleMeterRegistry())
                    .userMenu(reEntry(), KeyAction.ENTER, "1",
                            authenticated(ORDINARY_USER_ID, UserType.USER));
            final MenuResponse fromAdminMenu =
                    controllerOver(adminCatalogueWithSuppressedDispatch(), new SimpleMeterRegistry())
                            .adminMenu(reEntry(), KeyAction.ENTER, "1",
                                    authenticated(ADMIN_USER_ID, UserType.ADMIN));

            assertThat(fromUserMenu.message()).isNotEqualTo(fromAdminMenu.message());
        }

        @Test
        @DisplayName("an ordinary identity selecting an administrator-only option is refused with the "
                + "source text, trailing space included, which is never trimmed")
        void anOrdinaryIdentityIsRefusedAnAdministratorOnlyOption() {
            final MenuController subject = controllerOver(
                    userCatalogueWithSuppressedDispatch(UserType.ADMIN.getCode()),
                    new SimpleMeterRegistry());

            final MenuResponse response = subject.userMenu(reEntry(), KeyAction.ENTER, "1",
                    authenticated(ORDINARY_USER_ID, UserType.USER));

            assertThat(response.message()).isEqualTo(MenuService.ADMIN_ONLY_OPTION_MESSAGE);
            assertThat(response.message()).endsWith(" ");
            assertThat(response.errorFlag()).isTrue();
        }

        @Test
        @DisplayName("an administrative identity reaching the same option is not refused, so the gate "
                + "reads the authenticated type this boundary supplies")
        void anAdministrativeIdentityIsNotRefusedTheSameOption() {
            final MenuController subject = controllerOver(
                    userCatalogueWithSuppressedDispatch(UserType.ADMIN.getCode()),
                    new SimpleMeterRegistry());

            final MenuResponse response = subject.userMenu(reEntry(), KeyAction.ENTER, "1",
                    authenticated(ADMIN_USER_ID, UserType.ADMIN));

            assertThat(response.message()).isNotEqualTo(MenuService.ADMIN_ONLY_OPTION_MESSAGE);
            assertThat(response.errorFlag()).isFalse();
        }
    }

    /** What the returned navigation state says about who was acting. */
    @Nested
    @DisplayName("the identity the returned navigation state carries")
    class TheIdentityTheReturnedNavigationStateCarries {

        @Test
        @DisplayName("an echoed identity is replaced by the authenticated one, so a client-supplied "
                + "identity cannot survive a turn")
        void anEchoedIdentityIsReplacedByTheAuthenticatedOne() {
            final NavigationContext echoed = new NavigationContext(null, SIGN_ON_PROGRAM, null, null,
                    "IMPOSTOR", UserType.ADMIN.getCode(), NavigationContext.ProgramContext.ENTER, null,
                    null, null, null, null, null, null, null, null);

            final MenuResponse response = controller.userMenu(echoed, null, null,
                    authenticated(ORDINARY_USER_ID, UserType.USER));

            assertThat(response.navigationContext().userId()).isEqualTo(ORDINARY_USER_ID);
            assertThat(response.navigationContext().userType()).isEqualTo(UserType.USER.getCode());
            assertThat(response.navigationContext().echoesAdministratorCode()).isFalse();
        }

        @Test
        @DisplayName("a turn with no established identity publishes none, rather than inventing one")
        void aTurnWithNoEstablishedIdentityPublishesNone() {
            final MenuResponse response = controller.userMenu(firstEntry(), null, null, null);

            assertThat(response.navigationContext().userId()).isNull();
            assertThat(response.navigationContext().userType()).isNull();
            assertThat(response.userMenuOptions())
                    .hasSize(MenuOptionCatalog.USER_MENU_OPTION_COUNT);
        }

        @Test
        @DisplayName("an identity carrying no declared authority yields no type, which the service "
                + "treats as no type at all rather than as one it has to guess")
        void anIdentityCarryingNoDeclaredAuthorityYieldsNoType() {
            final Authentication unrecognised = new PreAuthenticatedAuthenticationToken(
                    ORDINARY_USER_ID, null, List.of(new SimpleGrantedAuthority("ROLE_SOMETHING")));

            final MenuResponse response =
                    controller.userMenu(firstEntry(), null, null, unrecognised);

            assertThat(response.navigationContext().userId()).isEqualTo(ORDINARY_USER_ID);
            assertThat(response.navigationContext().userType()).isNull();
        }

        @Test
        @DisplayName("an identity that grants no authority collection at all yields no type rather than "
                + "failing the turn, so a menu cannot be taken down by a malformed principal")
        void anIdentityGrantingNoAuthorityCollectionYieldsNoType() {
            // No framework token behaves this way; the guard exists because a turn is a screen the
            // operator is waiting for, and answering it with a server failure over an authority
            // collection nobody reads would be the wrong trade. Asserted rather than assumed.
            final Authentication malformed = mock(Authentication.class);
            when(malformed.getName()).thenReturn(ORDINARY_USER_ID);
            when(malformed.getAuthorities()).thenReturn(null);

            final MenuResponse response = controller.userMenu(firstEntry(), null, null, malformed);

            assertThat(response.navigationContext().userId()).isEqualTo(ORDINARY_USER_ID);
            assertThat(response.navigationContext().userType()).isNull();
            assertThat(response.userMenuOptions())
                    .hasSize(MenuOptionCatalog.USER_MENU_OPTION_COUNT);
        }
    }

    /** What the turn contributes to the metrics surface. */
    @Nested
    @DisplayName("the turn is timed")
    class TheTurnIsTimed {

        /** Name of the timer both endpoints record against. */
        private static final String TIMER = "carddemo.online.menu.turn";

        @Test
        @DisplayName("a main-menu turn is timed under its own transaction, reporting no message")
        void aMainMenuTurnIsTimedUnderItsOwnTransaction() {
            controller.userMenu(firstEntry(), null, null,
                    authenticated(ORDINARY_USER_ID, UserType.USER));

            assertThat(meterRegistry.get(TIMER)
                    .tag("transaction", MenuService.USER_MENU_TRANSACTION_ID)
                    .tag("outcome", "NONE")
                    .timer().count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("an administrative turn is timed under its own transaction, so the two menus do "
                + "not accumulate into one series")
        void anAdministrativeTurnIsTimedUnderItsOwnTransaction() {
            controller.adminMenu(firstEntry(), null, null,
                    authenticated(ADMIN_USER_ID, UserType.ADMIN));

            assertThat(meterRegistry.get(TIMER)
                    .tag("transaction", MenuService.ADMIN_MENU_TRANSACTION_ID)
                    .tag("outcome", "NONE")
                    .timer().count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a rejected entry is timed as an error outcome, so the mix of outcomes is "
                + "readable without reading the bodies")
        void aRejectedEntryIsTimedAsAnErrorOutcome() {
            controller.userMenu(reEntry(), KeyAction.ENTER, "11",
                    authenticated(ORDINARY_USER_ID, UserType.USER));

            assertThat(meterRegistry.get(TIMER)
                    .tag("transaction", MenuService.USER_MENU_TRANSACTION_ID)
                    .tag("outcome", MenuService.MessageSeverity.ERROR.name())
                    .timer().count()).isEqualTo(1L);
        }
    }
}
