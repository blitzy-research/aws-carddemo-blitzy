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

import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.api.dto.PageMetadata;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.exception.ValidationException;
import com.carddemo.domain.Card;
import com.carddemo.service.BrowseWindow;
import com.carddemo.service.CardConcurrencyTokenService;
import com.carddemo.service.CardDetailService;
import com.carddemo.service.CardListService;
import com.carddemo.service.CardUpdateService;
import com.carddemo.service.FieldErrorMarks;
import com.carddemo.service.NavigationService;
import com.carddemo.service.ScreenInputState;
import com.carddemo.service.ScreenNavigationState;
import com.carddemo.service.SensitiveFieldEncryptionService;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Holds {@link CardController} to the three card contracts it publishes and to the boundary discipline
 * it is required to keep.
 *
 * <p>The three screens themselves are covered by their own service tests. What is verified here is only
 * what the boundary is responsible for: that exactly three operations exist and no fourth, that each
 * delegates once and adds no rule of its own, that the transmitted values arrive at the service in the
 * shape it accepts - including the attention-key projection and the paging neutral - and that a settled
 * turn is projected onto the published contract without a screen artefact, without a fabricated value
 * and without breaking the positional invariant the list contract enforces.
 *
 * @since 1.0.0
 */
@DisplayName("CardController :: the delivered card list, detail and update operations")
class CardControllerTest {

    /** Route of the list operation. */
    private static final String LIST_ROUTE = CardController.CARDS_BASE_PATH
            + CardController.CARD_LIST_PATH;

    /** Route of the detail operation. */
    private static final String DETAIL_ROUTE = CardController.CARDS_BASE_PATH
            + CardController.CARD_DETAIL_PATH;

    /** Route of the update operation. */
    private static final String UPDATE_ROUTE = CardController.CARDS_BASE_PATH
            + CardController.CARD_UPDATE_PATH;

    /**
     * The first catalogue title the card-list turn stamps into its header, at the catalogue's own width.
     *
     * <p>Distinct, recognisable values rather than realistic ones, because what these assertions guard is
     * that the boundary publishes what the turn stamped: a header component published from a constant of
     * this class, or left absent, would pass an assertion written against a realistic string.
     */
    private static final String SCREEN_TITLE_01 = "STAMPED-TITLE-ONE";

    /** The second catalogue title the card-list turn stamps into its header. */
    private static final String SCREEN_TITLE_02 = "STAMPED-TITLE-TWO";

    /** The header date the card-list turn stamps, as {@code MM/DD/YY}. */
    private static final String HEADER_DATE = "03/09/24";

    /** The header time the card-list turn stamps, as {@code HH:MM:SS}. */
    private static final String HEADER_TIME = "14:25:36";

    /**
     * The one non-production fixture key, declared identically by {@code application-local.yml} and both
     * {@code application-test.yml} files: Base64 of exactly thirty-two bytes.
     */
    private static final String FIELD_ENCRYPTION_KEY = "Y2FyZGRlbW8tbm9ucHJvZC1maXh0dXJlLWtleSEhISE=";

    /**
     * The real sealer over the real cipher, shared because the key is a constant.
     *
     * <p>Real rather than stubbed on purpose. The conversation token exists so that a state a client did
     * not receive from this server cannot be opened, and a stub told to return a state would evidence
     * nothing about that. Sealing a fixture here and letting the boundary open it is the only way a suite
     * can show the round trip actually holds.
     */
    private static final CardConcurrencyTokenService TOKEN_SERVICE = new CardConcurrencyTokenService(
            new SensitiveFieldEncryptionService(FIELD_ENCRYPTION_KEY));

    /** The protected expiry day the fetched image carries; never bindable from the wire. */
    private static final String CARRIED_EXPIRY_DAY = "31";

    /** The image a turn that has fetched the card carries forward, day included. */
    private static final CardUpdateService.CarriedCardImage CARRIED_IMAGE =
            new CardUpdateService.CarriedCardImage("00000000011", "0000000000000001", "123",
                    "MARY ANN", "2027", "07", CARRIED_EXPIRY_DAY, "Y");

    /** The card-list screen, stubbed. */
    private CardListService cardListService;

    /** The card-detail screen, stubbed. */
    private CardDetailService cardDetailService;

    /** The card-update screen, stubbed. */
    private CardUpdateService cardUpdateService;

    /** The converter between the wire screen carriers and the service-owned ones. */
    private ScreenStateAdapter screenStateAdapter;

    /** The factory behind {@link #validator}, closed after each test so no provider resource leaks. */
    private ValidatorFactory validatorFactory;

    /** The real constraint evaluator, so the confirming submission's group is genuinely evaluated. */
    private Validator validator;

    /** Registry the turn timers register against. */
    private MeterRegistry meterRegistry;

    /** The boundary under test. */
    private CardController controller;

    /** The boundary driven through the servlet contract. */
    private MockMvc mockMvc;

    /**
     * Builds an established identity carrying the single authority the chain grants for a user type.
     *
     * <p>Supplied as the request principal rather than through a security filter, because these routes are
     * exercised standalone: the framework resolves an {@code Authentication} parameter from the request's own
     * user principal, so the boundary sees exactly what the chain would have handed it.
     *
     * @param userId the principal name
     * @param userType the type whose declared authority is granted
     * @return an authenticated token the boundary can read identity from
     */
    private static Authentication identityOf(final String userId, final UserType userType) {
        return new TestingAuthenticationToken(userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + userType.name())));
    }

    @BeforeEach
    void setUp() {
        cardListService = mock(CardListService.class);
        cardDetailService = mock(CardDetailService.class);
        cardUpdateService = mock(CardUpdateService.class);
        // The real converter rather than a mock: it holds no state and performs a positional copy, so
        // stubbing it would measure the stub instead of the crossing.
        screenStateAdapter = new ScreenStateAdapter(new NavigationService());
        // The real sealer over the real cipher, not a mock. The whole point of the conversation token is
        // that a state a client did not receive from this server cannot be opened, and a stubbed sealer
        // would return whatever the stub was told to and would evidence nothing about that.
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
        meterRegistry = new SimpleMeterRegistry();
        controller = new CardController(cardListService, cardDetailService, cardUpdateService,
                screenStateAdapter, TOKEN_SERVICE, validator, meterRegistry);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        validatorFactory.close();
    }

    // ==================================================================================================
    // Construction
    // ==================================================================================================

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("refuses every absent collaborator, so a half-built boundary cannot exist")
        void refusesAnAbsentCollaborator() {
            assertThatNullPointerException().isThrownBy(() -> new CardController(null,
                    cardDetailService, cardUpdateService, screenStateAdapter,
                    TOKEN_SERVICE, validator, meterRegistry));
            assertThatNullPointerException().isThrownBy(() -> new CardController(cardListService,
                    null, cardUpdateService, screenStateAdapter,
                    TOKEN_SERVICE, validator, meterRegistry));
            assertThatNullPointerException().isThrownBy(() -> new CardController(cardListService,
                    cardDetailService, null, screenStateAdapter,
                    TOKEN_SERVICE, validator, meterRegistry));
            assertThatNullPointerException().isThrownBy(() -> new CardController(cardListService,
                    cardDetailService, cardUpdateService, null,
                    TOKEN_SERVICE, validator, meterRegistry));
            assertThatNullPointerException().isThrownBy(() -> new CardController(cardListService,
                    cardDetailService, cardUpdateService, screenStateAdapter,
                    null, validator, meterRegistry));
            assertThatNullPointerException().isThrownBy(() -> new CardController(cardListService,
                    cardDetailService, cardUpdateService, screenStateAdapter,
                    TOKEN_SERVICE, null, meterRegistry));
            assertThatNullPointerException().isThrownBy(() -> new CardController(cardListService,
                    cardDetailService, cardUpdateService, screenStateAdapter,
                    TOKEN_SERVICE, validator, null));
        }

        @Test
        @DisplayName("publishes the three transaction identifiers the resource definition binds")
        void publishesTheThreeTransactionIdentifiers() {
            assertThat(CardController.TRANSACTION_CARD_LIST).isEqualTo("CCLI");
            assertThat(CardController.TRANSACTION_CARD_DETAIL).isEqualTo("CCDL");
            assertThat(CardController.TRANSACTION_CARD_UPDATE).isEqualTo("CCUP");
            assertThat(CardController.PROGRAM_CARD_LIST).isEqualTo("COCRDLIC");
        }

        @Test
        @DisplayName("maps beneath a base path that is neither anonymous nor administrative, so the "
                + "catch-all rule requires a credential for all three routes")
        void mapsBeneathAnAuthenticatedBasePath() {
            assertThat(CardController.CARDS_BASE_PATH).isEqualTo("/api/cards");
            assertThat(CardController.CARDS_BASE_PATH).doesNotStartWith("/api/admin");
            assertThat(CardController.class.getAnnotation(RequestMapping.class).value())
                    .containsExactly(CardController.CARDS_BASE_PATH);
        }
    }

    // ==================================================================================================
    // The surface is exactly three operations
    // ==================================================================================================

    @Nested
    @DisplayName("the published surface")
    class ThePublishedSurface {

        @Test
        @DisplayName("carries exactly three request-mapped operations, so the dangling developer "
                + "transaction has no surface here")
        void carriesExactlyThreeOperations() {
            final List<String> mapped = new ArrayList<>();
            for (final Method method : CardController.class.getDeclaredMethods()) {
                if (method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(GetMapping.class)) {
                    mapped.add(method.getName());
                }
            }

            assertThat(mapped).containsExactlyInAnyOrder("listCards", "viewCardDetail", "updateCard");
        }

        @Test
        @DisplayName("names no route, constant or program for the developer transaction whose bound "
                + "program has no source member")
        void namesNoDeveloperTransactionRoute() {
            final List<String> constants = new ArrayList<>();
            for (final java.lang.reflect.Field field : CardController.class.getDeclaredFields()) {
                constants.add(field.getName());
            }

            assertThat(constants).noneMatch(name -> name.contains("CDV1") || name.contains("SEC"));
        }

        @Test
        @DisplayName("answers nothing on a fourth card route")
        void answersNothingOnAFourthRoute() throws Exception {
            mockMvc.perform(get(CardController.CARDS_BASE_PATH + "/security"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("answers a POST at the literal address /api/cards/detail, and refuses a GET there, "
                + "because the detail turn is addressed by a body and not by a path segment")
        void answersAPostAtTheLiteralDetailAddress() throws Exception {
            // Driven at the written-out address rather than through the controller's own constants. The
            // route inventory recorded this operation as a GET while the mapping was a POST, and every
            // route test in the module assembled its expectation from the same constant the mapping used,
            // so both sides agreed with each other and neither agreed with the published contract. The
            // whole surface is compared against one independent literal oracle in
            // DeliveredApiSurfaceOracleTest; this is the same claim for the one operation it concerned,
            // asserted here where the turn's behaviour is specified.
            when(cardDetailService.processCardDetail(any())).thenReturn(detailResult());

            mockMvc.perform(post("/api/cards/detail")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(detailBody("00000000011", "0000000000000001", "ENTER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionName").value("CCDL"));
            mockMvc.perform(get("/api/cards/detail"))
                    .andExpect(status().isMethodNotAllowed());
        }
    }

    // ==================================================================================================
    // CCLI
    // ==================================================================================================

    @Nested
    @DisplayName("the card-list operation")
    class TheCardListOperation {

        @Test
        @DisplayName("delegates once and publishes the settled page, its cursors and the route the "
                + "client calls next")
        void delegatesOnceAndPublishesTheSettledPage() throws Exception {
            when(cardListService.processCardList(any()))
                    .thenReturn(listResult(rowsFrom(1, 2, 3, 4, 5, 6, 7), false));

            mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody("00000000011", null, "ENTER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionName").value("CCLI"))
                    .andExpect(jsonPath("$.programName").value("COCRDLIC"))
                    .andExpect(jsonPath("$.accountFilter").value("00000000011"))
                    .andExpect(jsonPath("$.rows.length()").value(7))
                    .andExpect(jsonPath("$.rows[0].cardNumber").value("0000000000000001"))
                    .andExpect(jsonPath("$.selectionErrorFlags.length()").value(7))
                    .andExpect(jsonPath("$.pageMetadata.pageSize")
                            .value(PageMetadata.CARD_LIST_PAGE_SIZE))
                    .andExpect(jsonPath("$.nextRoute").value("card-list"))
                    .andExpect(jsonPath("$.infoMessage")
                            .value("TYPE S FOR DETAIL, U TO UPDATE ANY RECORD"));

            verify(cardListService, times(1)).processCardList(any());
        }

        @Test
        @DisplayName("publishes the heading the turn stamped rather than inventing one or omitting it, "
                + "because the whole of that legacy paragraph's output belongs to the turn")
        void publishesTheStampedHeading() throws Exception {
            when(cardListService.processCardList(any())).thenReturn(listResult(rowsFrom(1), false));

            // Each expected value is the one the stubbed turn stamped, so a component the boundary
            // fabricated from its own constants, or left absent, fails here.
            mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody(null, null, "ENTER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title01").value(SCREEN_TITLE_01))
                    .andExpect(jsonPath("$.title02").value(SCREEN_TITLE_02))
                    .andExpect(jsonPath("$.currentDate").value(HEADER_DATE))
                    .andExpect(jsonPath("$.currentTime").value(HEADER_TIME))
                    .andExpect(jsonPath("$.transactionName").value("CCLI"))
                    .andExpect(jsonPath("$.programName").value("COCRDLIC"));
        }

        @Test
        @DisplayName("publishes every row's screen slot, so a selector addresses the row the operator saw "
                + "rather than the position it happened to occupy in the list")
        void publishesEveryRowScreenSlot() throws Exception {
            // Slots five, six and seven populated and one to four empty is the shape a partial backward
            // page leaves, and it is the shape on which a position-derived slot would be wrong.
            when(cardListService.processCardList(any()))
                    .thenReturn(listResult(rowsFrom(5, 6, 7), false));

            mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody(null, null, "PFK07")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows.length()").value(3))
                    .andExpect(jsonPath("$.rows[0].screenSlot").value(5))
                    .andExpect(jsonPath("$.rows[1].screenSlot").value(6))
                    .andExpect(jsonPath("$.rows[2].screenSlot").value(7));
        }

        @Test
        @DisplayName("realigns the positional selection indicator onto the rows a partial page "
                + "carries, which the published contract requires")
        void realignsThePositionalIndicatorForAPartialPage() throws Exception {
            when(cardListService.processCardList(any())).thenReturn(listResult(rowsFrom(1, 2, 3), true));

            mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody(null, null, "ENTER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows.length()").value(3))
                    .andExpect(jsonPath("$.selectionErrorFlags.length()").value(3))
                    .andExpect(jsonPath("$.selectionErrorFlags[0]").value(true))
                    .andExpect(jsonPath("$.selectionErrorFlags[2]").value(true));
        }

        @Test
        @DisplayName("publishes an empty page and no indicator when the browse found nothing")
        void publishesAnEmptyPage() throws Exception {
            when(cardListService.processCardList(any())).thenReturn(listResult(List.of(), false));

            mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody(null, null, "ENTER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows.length()").value(0))
                    .andExpect(jsonPath("$.selectionErrorFlags.length()").value(0));
        }

        @Test
        @DisplayName("stages both filters and the resolved key into the work area exactly as "
                + "transmitted, and reads the retained paging state as the program's own first-entry "
                + "values when the caller carried none")
        void stagesTheTransmittedValuesWithoutAlteringThem() throws Exception {
            when(cardListService.processCardList(any())).thenReturn(listResult(List.of(), false));

            mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody(" 0000000001", "0000000000000009", "PFK08")))
                    .andExpect(status().isOk());

            final CardListService.CardListScreenInput captured = capturedListInput();
            final ScreenInputState workArea = captured.workArea();
            assertThat(workArea.accountId()).isEqualTo(" 0000000001");
            assertThat(workArea.cardNumber()).isEqualTo("0000000000000009");
            assertThat(workArea.keyAction()).isEqualTo(KeyAction.PFK08);
            assertThat(workArea.nextProgram()).isNull();
            // Zero, not one: WS-CA-SCREEN-NUM starts at zero and the program's own test raises it to one,
            // so asserting one here would skip that test.
            assertThat(captured.currentPageNumber()).isZero();
            assertThat(captured.lastPageAlreadyShown()).isFalse();
            assertThat(captured.nextPageIndicated()).isFalse();
            assertThat(captured.selections()).hasSize(BrowseWindow.CARD_LIST_PAGE_SIZE);
        }

        @Test
        @DisplayName("reads the retained page number and both retained flags back out of what the caller "
                + "echoed, because the program carries all three in its communication area rather than "
                + "recomputing them")
        void readsTheRetainedPagingStateBack() throws Exception {
            when(cardListService.processCardList(any())).thenReturn(listResult(List.of(), false));

            mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"keyAction":"PFK08",
                                     "lastPageAlreadyShown":true,
                                     "pageMetadata":{"previousCursorKey":"0000000000000001",
                                                     "nextCursorKey":"0000000000000007",
                                                     "direction":"FORWARD",
                                                     "displayedPageNumber":"3",
                                                     "nextPageIndicated":true}}"""))
                    .andExpect(status().isOk());

            final CardListService.CardListScreenInput captured = capturedListInput();
            assertThat(captured.currentPageNumber()).isEqualTo(3);
            assertThat(captured.lastPageAlreadyShown()).isTrue();
            assertThat(captured.nextPageIndicated()).isTrue();
        }

        @Test
        @DisplayName("reads a padded retained page number as the number it displays, because a "
                + "fixed-width indicator arrives with the padding its field carries")
        void readsAPaddedRetainedPageNumber() throws Exception {
            when(cardListService.processCardList(any())).thenReturn(listResult(List.of(), false));

            mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"keyAction":"PFK08",
                                     "pageMetadata":{"direction":"FORWARD",
                                                     "displayedPageNumber":"  4  "}}"""))
                    .andExpect(status().isOk());

            assertThat(capturedListInput().currentPageNumber()).isEqualTo(4);
        }

        @Test
        @DisplayName("reads an all-blank retained page number as the zero a first entry carries, so an "
                + "echoed indicator can never fail a turn")
        void readsABlankRetainedPageNumberAsZero() throws Exception {
            when(cardListService.processCardList(any())).thenReturn(listResult(List.of(), false));

            mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"keyAction":"PFK08",
                                     "pageMetadata":{"direction":"FORWARD",
                                                     "displayedPageNumber":"     "}}"""))
                    .andExpect(status().isOk());

            assertThat(capturedListInput().currentPageNumber()).isZero();
        }

        @Test
        @DisplayName("projects the turn's field findings onto the two-state error contract, in order "
                + "and keeping an unsupplied filter distinct from an unusable one")
        void projectsTheListFieldFindings() throws Exception {
            when(cardListService.processCardList(any())).thenReturn(listResult(List.of(), false, false,
                    List.of(
                            new ValidationException.FieldError("accountIdFilter", "ACCTSID",
                                    ValidationException.FieldState.MISSING,
                                    "Account number not provided"),
                            new ValidationException.FieldError("cardNumberFilter", "CARDSID",
                                    ValidationException.FieldState.INVALID,
                                    "Card number must be a 16 digit number"))));

            mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody(null, "ABC", "ENTER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                    .andExpect(jsonPath("$.fieldErrors[0].fieldName").value("accountIdFilter"))
                    .andExpect(jsonPath("$.fieldErrors[0].screenFieldId").value("ACCTSID"))
                    .andExpect(jsonPath("$.fieldErrors[0].state")
                            .value(ErrorResponse.FieldState.MISSING.name()))
                    .andExpect(jsonPath("$.fieldErrors[0].message")
                            .value("Account number not provided"))
                    .andExpect(jsonPath("$.fieldErrors[1].fieldName").value("cardNumberFilter"))
                    .andExpect(jsonPath("$.fieldErrors[1].screenFieldId").value("CARDSID"))
                    .andExpect(jsonPath("$.fieldErrors[1].state")
                            .value(ErrorResponse.FieldState.INVALID.name()))
                    .andExpect(jsonPath("$.fieldErrors[1].message")
                            .value("Card number must be a 16 digit number"));
        }

        @Test
        @DisplayName("publishes an empty finding list when the turn reported none, so a clean page is "
                + "distinguishable from a page whose findings were never established")
        void publishesAnEmptyListFindingListWhenTheTurnReportedNone() throws Exception {
            when(cardListService.processCardList(any())).thenReturn(listResult(rowsFrom(1), false));

            mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody("00000000011", null, "ENTER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fieldErrors").isArray())
                    .andExpect(jsonPath("$.fieldErrors.length()").value(0));
        }

        @Test
        @DisplayName("publishes the retained end-of-data flag so the next turn can echo it, which is the "
                + "one retained paging value the paging state cannot express")
        void publishesTheRetainedEndOfDataFlag() throws Exception {
            when(cardListService.processCardList(any()))
                    .thenReturn(listResult(List.of(), false, true));

            mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody(null, null, "PFK08")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lastPageAlreadyShown").value(true));
        }

        @Test
        @DisplayName("passes the browse cursors through untouched, because the contract declares them "
                + "authoritative")
        void passesTheBrowseCursorsThroughUntouched() throws Exception {
            when(cardListService.processCardList(any())).thenReturn(listResult(List.of(), false));

            mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"keyAction":"PFK08",
                                     "pageMetadata":{"previousCursorKey":"0000000000000001",
                                                     "nextCursorKey":"0000000000000007",
                                                     "direction":"FORWARD"}}"""))
                    .andExpect(status().isOk());

            final BrowseWindow.CursorRequest cursor = capturedListInput().pageCursor();
            assertThat(cursor.previousCursorKey()).isEqualTo("0000000000000001");
            assertThat(cursor.nextCursorKey()).isEqualTo("0000000000000007");
            assertThat(cursor.direction()).isEqualTo(BrowseWindow.PagingDirection.FORWARD);
        }

        @Test
        @DisplayName("carries the seven action selectors positionally, so a selection stays on the row "
                + "it was marked on")
        void carriesTheSevenSelectorsPositionally() throws Exception {
            when(cardListService.processCardList(any())).thenReturn(listResult(List.of(), false));

            mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"keyAction\":\"ENTER\",\"selection3\":\"S\","
                                    + "\"selection6\":\"U\"}"))
                    .andExpect(status().isOk());

            assertThat(capturedListInput().selections())
                    .containsExactly("", "", "S", "", "", "U", "");
        }

        @Test
        @DisplayName("rejects a filter wider than the legacy screen field could hold")
        void rejectsAnOverWideFilter() throws Exception {
            mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody("000000000012", null, "ENTER")))
                    .andExpect(status().isBadRequest());

            verify(cardListService, times(0)).processCardList(any());
        }

        @Test
        @DisplayName("times the turn once, tagged by the destination it settled on")
        void timesTheTurnOnce() throws Exception {
            when(cardListService.processCardList(any())).thenReturn(listResult(List.of(), false));

            mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody(null, null, "ENTER")))
                    .andExpect(status().isOk());

            assertThat(meterRegistry.find("carddemo.online.cardlist.turn")
                    .tag("outcome", "card-list").timer())
                    .isNotNull();
            assertThat(meterRegistry.find("carddemo.online.cardlist.turn")
                    .tag("outcome", "card-list").timer().count()).isEqualTo(1L);
        }
    }

    // ==================================================================================================
    // CCDL
    // ==================================================================================================

    @Nested
    @DisplayName("the card-detail operation")
    class TheCardDetailOperation {

        @Test
        @DisplayName("delegates once and publishes the heading, the card and the screen message")
        void delegatesOnceAndPublishesTheCard() throws Exception {
            when(cardDetailService.processCardDetail(any())).thenReturn(detailResult());

            mockMvc.perform(post(DETAIL_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(detailBody("00000000011", "0000000000000001", "ENTER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionName").value("CCDL"))
                    .andExpect(jsonPath("$.programName").value("COCRDSLC"))
                    .andExpect(jsonPath("$.accountId").value("00000000011"))
                    .andExpect(jsonPath("$.cardNumber").value("0000000000000001"))
                    .andExpect(jsonPath("$.embossedName").value("MARY ANN"))
                    .andExpect(jsonPath("$.cardActiveStatus").value("Y"))
                    .andExpect(jsonPath("$.expiryMonth").value("07"))
                    .andExpect(jsonPath("$.expiryYear").value("2027"))
                    .andExpect(jsonPath("$.infoMessage").value("   Displaying requested details"))
                    .andExpect(jsonPath("$.nextRoute").value("card-detail"));

            verify(cardDetailService, times(1)).processCardDetail(any());
        }

        @Test
        @DisplayName("projects the turn's field findings onto the two-state error contract, in order "
                + "and keeping an unsupplied key distinct from an unusable one")
        void projectsTheDetailFieldFindings() throws Exception {
            // The legacy screen distinguishes the two states with two different devices - it writes a
            // star beside a key that was left blank and only colours one that was supplied but is
            // unusable - so a single boolean per field could not carry both.
            when(cardDetailService.processCardDetail(any())).thenReturn(detailResult(List.of(
                    new ValidationException.FieldError("accountIdFilter", "ACCTSID",
                            ValidationException.FieldState.MISSING,
                            "Account number not provided"),
                    new ValidationException.FieldError("cardNumberFilter", "CARDSID",
                            ValidationException.FieldState.INVALID,
                            "Card number must be a 16 digit number"))));

            mockMvc.perform(post(DETAIL_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(detailBody(null, "ABC", "ENTER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                    .andExpect(jsonPath("$.fieldErrors[0].fieldName").value("accountIdFilter"))
                    .andExpect(jsonPath("$.fieldErrors[0].screenFieldId").value("ACCTSID"))
                    .andExpect(jsonPath("$.fieldErrors[0].state")
                            .value(ErrorResponse.FieldState.MISSING.name()))
                    .andExpect(jsonPath("$.fieldErrors[0].message")
                            .value("Account number not provided"))
                    .andExpect(jsonPath("$.fieldErrors[1].fieldName").value("cardNumberFilter"))
                    .andExpect(jsonPath("$.fieldErrors[1].screenFieldId").value("CARDSID"))
                    .andExpect(jsonPath("$.fieldErrors[1].state")
                            .value(ErrorResponse.FieldState.INVALID.name()))
                    .andExpect(jsonPath("$.fieldErrors[1].message")
                            .value("Card number must be a 16 digit number"));
        }

        @Test
        @DisplayName("publishes an empty finding list when the turn reported none, so a clean screen is "
                + "distinguishable from a screen whose findings were never established")
        void publishesAnEmptyFindingListWhenTheTurnReportedNone() throws Exception {
            when(cardDetailService.processCardDetail(any())).thenReturn(detailResult());

            mockMvc.perform(post(DETAIL_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(detailBody("00000000011", "0000000000000001", "ENTER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fieldErrors").isArray())
                    .andExpect(jsonPath("$.fieldErrors.length()").value(0));
        }

        @Test
        @DisplayName("publishes no protection, highlight or darkening state, because those are how a "
                + "terminal achieved an effect and not part of the contract")
        void publishesNoPresentationState() throws Exception {
            when(cardDetailService.processCardDetail(any())).thenReturn(detailResult());

            mockMvc.perform(post(DETAIL_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(detailBody(null, null, "ENTER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountIdProtected").doesNotExist())
                    .andExpect(jsonPath("$.cardNumberProtected").doesNotExist())
                    .andExpect(jsonPath("$.accountIdHighlighted").doesNotExist())
                    .andExpect(jsonPath("$.infoMessageDarkened").doesNotExist());
        }

        @Test
        @DisplayName("accepts both search keys absent, so the no-input outcome the legacy screen has "
                + "stays reachable")
        void acceptsBothSearchKeysAbsent() throws Exception {
            when(cardDetailService.processCardDetail(any())).thenReturn(detailResult());

            mockMvc.perform(post(DETAIL_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());

            final CardDetailService.CardDetailScreenInput captured = capturedDetailInput();
            assertThat(captured.accountIdFilter()).isNull();
            assertThat(captured.cardNumberFilter()).isNull();
            assertThat(captured.attentionKeyIdentifier()).isEmpty();
        }

        @Test
        @DisplayName("binds the echoed navigation state from its own component names, so a carried key "
                + "is never confused with a typed filter")
        void bindsTheEchoedNavigationState() throws Exception {
            when(cardDetailService.processCardDetail(any())).thenReturn(detailResult());

            mockMvc.perform(post(DETAIL_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"accountIdFilter":"00000000011",
                                     "cardNumberFilter":"0000000000000001",
                                     "keyAction":"ENTER",
                                     "navigationContext":{
                                       "fromTransactionId":"CCLI",
                                       "fromProgram":"COCRDLIC",
                                       "programContext":"REENTER",
                                       "accountId":"00000000099",
                                       "cardNumber":"0000000000000099",
                                       "lastMapset":"COCRDLI"}}"""))
                    .andExpect(status().isOk());

            final ScreenNavigationState bound = capturedDetailInput().navigationContext();
            assertThat(bound).isNotNull();
            assertThat(bound.fromProgram()).isEqualTo("COCRDLIC");
            assertThat(bound.fromTransactionId()).isEqualTo("CCLI");
            assertThat(bound.reEntry()).isTrue();
            assertThat(bound.accountId()).isEqualTo("00000000099");
            assertThat(bound.cardNumber()).isEqualTo("0000000000000099");
            assertThat(bound.lastMapset()).isEqualTo("COCRDLI");
        }

        @Test
        @DisplayName("reads an entirely absent navigation state as no carry-over at all")
        void readsAnAbsentNavigationStateAsNoCarryOver() throws Exception {
            when(cardDetailService.processCardDetail(any())).thenReturn(detailResult());

            mockMvc.perform(post(DETAIL_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(detailBody(null, null, "ENTER")))
                    .andExpect(status().isOk());

            assertThat(capturedDetailInput().navigationContext())
                    .isEqualTo(ScreenNavigationState.empty());
        }

        @Test
        @DisplayName("times the turn once, tagged by the destination it settled on")
        void timesTheTurnOnce() throws Exception {
            when(cardDetailService.processCardDetail(any())).thenReturn(detailResult());

            mockMvc.perform(post(DETAIL_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(detailBody(null, null, "ENTER")))
                    .andExpect(status().isOk());

            assertThat(meterRegistry.find("carddemo.online.carddetail.turn")
                    .tag("outcome", "card-detail").timer()).isNotNull();
        }
    }

    // ==================================================================================================
    // CCUP
    // ==================================================================================================

    @Nested
    @DisplayName("the card-update operation")
    class TheCardUpdateOperation {

        @Test
        @DisplayName("delegates once and publishes the values to redisplay, including the hidden "
                + "protected expiry day")
        void delegatesOnceAndPublishesTheHiddenExpiryDay() throws Exception {
            when(cardUpdateService.processCardUpdate(any())).thenReturn(updateResult(List.of()));

            mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("PFK05")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionName").value("CCUP"))
                    .andExpect(jsonPath("$.programName").value("COCRDUPC"))
                    .andExpect(jsonPath("$.accountId").value("00000000011"))
                    .andExpect(jsonPath("$.cardNumber").value("0000000000000001"))
                    .andExpect(jsonPath("$.embossedName").value("MARY ANN"))
                    .andExpect(jsonPath("$.activeStatus").value("Y"))
                    .andExpect(jsonPath("$.expiryMonth").value("07"))
                    .andExpect(jsonPath("$.expiryYear").value("2027"))
                    .andExpect(jsonPath("$.expiryDay").value("31"))
                    .andExpect(jsonPath("$.informationMessage")
                            .value("Changes committed to database"))
                    .andExpect(jsonPath("$.nextRoute").value("card-update"));

            verify(cardUpdateService, times(1)).processCardUpdate(any());
        }

        @Test
        @DisplayName("seals the state the turn settled on rather than echoing back the state it was given, "
                + "because echoing would leave the conversation unable to advance")
        void sealsTheSettledStateRatherThanEchoingTheGivenOne() throws Exception {
            final String presented = TOKEN_SERVICE.sealContinuation(
                    CardUpdateService.ChangeAction.SHOW_DETAILS, CARRIED_IMAGE);
            when(cardUpdateService.processCardUpdate(any())).thenReturn(updateResult(List.of()));

            final String published = JsonPath.read(mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("PFK05", presented)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(), "$.concurrencyToken");

            assertThat(published)
                    .as("a boundary that echoed the presented token would freeze the state machine at "
                            + "whatever state the client last held")
                    .isNotEqualTo(presented);
            assertThat(TOKEN_SERVICE.openContinuation(published).changeAction())
                    .as("the published token names the state this turn settled on")
                    .isEqualTo(CardUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE);
        }

        @Test
        @DisplayName("hands the service the state the previous turn sealed, so the machine can advance past "
                + "the not-fetched state a client cannot be trusted to name")
        void handsTheServiceTheSealedState() throws Exception {
            when(cardUpdateService.processCardUpdate(any())).thenReturn(updateResult(List.of()));

            mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("ENTER",
                                    TOKEN_SERVICE.sealContinuation(
                                            CardUpdateService.ChangeAction.CHANGES_OK_NOT_CONFIRMED,
                                            CARRIED_IMAGE))))
                    .andExpect(status().isOk());

            final CardUpdateService.CardUpdateScreenInput captured = capturedUpdateInput();
            assertThat(captured.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.CHANGES_OK_NOT_CONFIRMED);
            assertThat(captured.carriedImage()).isEqualTo(CARRIED_IMAGE);
        }

        @Test
        @DisplayName("supplies the protected expiry day from the sealed image, because the contract "
                + "declines to bind it and the change comparison reads it")
        void suppliesTheProtectedExpiryDayFromTheSealedImage() throws Exception {
            // COCRDUPC line 1123 writes the fetched day back into the protected field before sending, so
            // the terminal always transmitted it back and the comparison at lines 680 to 681 read a real
            // value. The wire cannot carry it here, so the boundary has to restore it from the sealed
            // image; leaving it null makes every comparison see a change and assembles "2027-07-null".
            when(cardUpdateService.processCardUpdate(any())).thenReturn(updateResult(List.of()));

            mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("ENTER")))
                    .andExpect(status().isOk());

            assertThat(capturedUpdateInput().expiryDay()).isEqualTo(CARRIED_EXPIRY_DAY);
        }

        @Test
        @DisplayName("opens an absent sealed state as the genuine first turn, which is the zero-length "
                + "communication area the legacy answers at line 388")
        void opensAnAbsentSealedStateAsAFirstTurn() throws Exception {
            when(cardUpdateService.processCardUpdate(any())).thenReturn(updateResult(List.of()));

            mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("ENTER", null)))
                    .andExpect(status().isOk());

            final CardUpdateService.CardUpdateScreenInput captured = capturedUpdateInput();
            assertThat(captured.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED);
            assertThat(captured.carriedImage())
                    .isEqualTo(CardUpdateService.CarriedCardImage.empty());
            assertThat(captured.expiryDay())
                    .as("a first turn has fetched nothing, so there is no protected day to restore")
                    .isNull();
        }

        @Test
        @DisplayName("refuses a sealed state this server did not mint rather than downgrading it to a "
                + "first turn, because downgrading would let a client discard a state it disliked")
        void refusesASealedStateThisServerDidNotMint() throws Exception {
            mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("PFK05", "not-a-token-this-server-sealed")))
                    .andExpect(status().isConflict());

            verify(cardUpdateService, never()).processCardUpdate(any());
        }

        @Test
        @DisplayName("refuses a sealed state minted for the other purpose, so the record proof and the "
                + "conversation state cannot be substituted for one another")
        void refusesASealedStateMintedForTheOtherPurpose() throws Exception {
            final Card presented = new Card("0000000000000001", "00000000011", "123", "MARY ANN",
                    "2027-07-31", "Y");

            mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("PFK05", TOKEN_SERVICE.mint(presented))))
                    .andExpect(status().isConflict());

            verify(cardUpdateService, never()).processCardUpdate(any());
        }

        @Test
        @DisplayName("refuses a confirming submission that supplies a value that state protects, which is "
                + "a submission the terminal could not physically have transmitted")
        void refusesAConfirmingSubmissionThatSuppliesAProtectedValue() throws Exception {
            // Clause five of the dispatch at COCRDUPC lines 988 to 1001 is the only writing combination,
            // and the attribute branch at line 1193 has the account identifier protected in that state.
            final String confirming = TOKEN_SERVICE.sealContinuation(
                    CardUpdateService.ChangeAction.CHANGES_OK_NOT_CONFIRMED, CARRIED_IMAGE);

            mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"accountId\":\"00000000011\","
                                    + "\"cardNumber\":\"0000000000000001\","
                                    + "\"keyAction\":\"PFK05\","
                                    + "\"concurrencyToken\":\"" + confirming + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].fieldName").value("accountId"));

            verify(cardUpdateService, never()).processCardUpdate(any());
        }

        @Test
        @DisplayName("admits the same confirming submission once the protected value is left out, so the "
                + "group refuses a supplied value rather than the turn itself")
        void admitsAConfirmingSubmissionWithoutTheProtectedValue() throws Exception {
            when(cardUpdateService.processCardUpdate(any())).thenReturn(updateResult(List.of()));

            mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("PFK05",
                                    TOKEN_SERVICE.sealContinuation(
                                            CardUpdateService.ChangeAction.CHANGES_OK_NOT_CONFIRMED,
                                            CARRIED_IMAGE))))
                    .andExpect(status().isOk());

            verify(cardUpdateService, times(1)).processCardUpdate(any());
        }

        @Test
        @DisplayName("leaves the protected value unpoliced on a turn the group does not name, because the "
                + "operator types it on the searching turn")
        void leavesTheProtectedValueUnpolicedOnASearchingTurn() throws Exception {
            when(cardUpdateService.processCardUpdate(any())).thenReturn(updateResult(List.of()));

            mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"accountId\":\"00000000011\","
                                    + "\"cardNumber\":\"0000000000000001\","
                                    + "\"keyAction\":\"ENTER\"}"))
                    .andExpect(status().isOk());

            assertThat(capturedUpdateInput().accountId()).isEqualTo("00000000011");
        }

        @Test
        @DisplayName("declines to bind the hidden expiry day inbound, so the wire cannot reach the "
                + "stored expiry date")
        void declinesToBindTheHiddenExpiryDayInbound() throws Exception {
            when(cardUpdateService.processCardUpdate(any())).thenReturn(updateResult(List.of()));

            mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"keyAction\":\"ENTER\",\"cardNumber\":"
                                    + "\"0000000000000001\",\"expiryDay\":\"01\"}"))
                    .andExpect(status().isOk());

            assertThat(capturedUpdateInput().expiryDay()).isNull();
        }

        @Test
        @DisplayName("holds no screen state of its own: two turns sealed at different states reach the "
                + "transaction as those two states through one boundary instance")
        void holdsNoScreenStateOfItsOwn() throws Exception {
            when(cardUpdateService.processCardUpdate(any())).thenReturn(updateResult(List.of()));

            mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("ENTER",
                                    TOKEN_SERVICE.sealContinuation(
                                            CardUpdateService.ChangeAction.SHOW_DETAILS,
                                            CARRIED_IMAGE))))
                    .andExpect(status().isOk());
            final CardUpdateService.ChangeAction first = capturedUpdateInput().changeAction();

            mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("ENTER",
                                    TOKEN_SERVICE.sealContinuation(
                                            CardUpdateService.ChangeAction.CHANGES_NOT_OK,
                                            CARRIED_IMAGE))))
                    .andExpect(status().isOk());

            assertThat(first).isEqualTo(CardUpdateService.ChangeAction.SHOW_DETAILS);
            assertThat(lastCapturedUpdateInput().changeAction())
                    .as("nothing about the first turn survived into the second, because the boundary "
                            + "keeps no field and the state travels sealed")
                    .isEqualTo(CardUpdateService.ChangeAction.CHANGES_NOT_OK);
            assertThat(lastCapturedUpdateInput().attentionKeyIdentifier()).isEqualTo("DFHENTER");
        }

        @Test
        @DisplayName("a submission echoing no navigation record reaches the transaction as no record at "
                + "all rather than as an all-blank one, because this screen's reset condition asks "
                + "whether a communication area arrived")
        void anAbsentNavigationRecordReachesTheTransactionAsAbsent() throws Exception {
            when(cardUpdateService.processCardUpdate(any())).thenReturn(updateResult(List.of()));

            mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("ENTER")))
                    .andExpect(status().isOk());

            // Null and not the empty carrier. The list and detail screens ask whether the state they were
            // handed is absent OR all-blank and treat the two identically; this screen does not, and
            // filling the absence in would turn the first turn of a conversation into a continuation of
            // one, losing the first-entry gate the reset raises.
            assertThat(capturedUpdateInput().navigationContext()).isNull();
            assertThat(capturedUpdateInput().carriesNoNavigationState()).isTrue();
        }

        @Test
        @DisplayName("an echoed navigation record crosses component for component, except the two identity "
                + "members, which the authenticated principal supplies instead of the caller")
        void anEchoedNavigationRecordCrossesComponentForComponent() throws Exception {
            when(cardUpdateService.processCardUpdate(any())).thenReturn(updateResult(List.of()));

            mockMvc.perform(post(UPDATE_ROUTE)
                            .principal(identityOf("USER0001", UserType.USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cardNumber\":\"0000000000000001\","
                                    + "\"embossedName\":\"MARY ANN\",\"activeStatus\":\"Y\","
                                    + "\"expiryMonth\":\"07\",\"expiryYear\":\"2027\","
                                    + "\"keyAction\":\"ENTER\","
                                    + "\"concurrencyToken\":\"" + TOKEN_SERVICE.sealContinuation(
                                            CardUpdateService.ChangeAction.SHOW_DETAILS, CARRIED_IMAGE)
                                    + "\","
                                    + "\"navigationContext\":{"
                                    + "\"fromTransactionId\":\"CCLI\","
                                    + "\"fromProgram\":\"COCRDLIC\","
                                    + "\"toTransactionId\":\"CCUP\","
                                    + "\"toProgram\":\"COCRDUPC\","
                                    + "\"userId\":\"USER0001\",\"userType\":\"U\","
                                    + "\"programContext\":\"REENTER\","
                                    + "\"customerId\":\"000000123\","
                                    + "\"customerFirstName\":\"MARY\","
                                    + "\"customerMiddleName\":\"A\","
                                    + "\"customerLastName\":\"SMITH\","
                                    + "\"accountId\":\"00000000011\","
                                    + "\"accountStatus\":\"Y\","
                                    + "\"cardNumber\":\"0000000000000001\","
                                    + "\"lastMap\":\"CCRDUPA\",\"lastMapset\":\"COCRDUP\"}}"))
                    .andExpect(status().isOk());

            assertThat(capturedUpdateInput().navigationContext())
                    .isEqualTo(new ScreenNavigationState("CCLI", "COCRDLIC", "CCUP", "COCRDUPC",
                            "USER0001", "U", ScreenNavigationState.ProgramContext.REENTER,
                            "000000123", "MARY", "A", "SMITH", "00000000011", "Y",
                            "0000000000000001", "CCRDUPA", "COCRDUP"));
        }

        @Test
        @DisplayName("projects the turn's field findings onto the two-state error contract, in order "
                + "and without rewording")
        void projectsTheFieldFindings() throws Exception {
            when(cardUpdateService.processCardUpdate(any())).thenReturn(updateResult(List.of(
                    new ValidationException.FieldError("embossedName", "CRDNAME",
                            ValidationException.FieldState.MISSING, "Card name not provided"),
                    new ValidationException.FieldError("expiryMonth", "EXPMON",
                            ValidationException.FieldState.INVALID,
                            "Card expiry month must be between 1 and 12"))));

            mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("ENTER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                    .andExpect(jsonPath("$.fieldErrors[0].fieldName").value("embossedName"))
                    .andExpect(jsonPath("$.fieldErrors[0].screenFieldId").value("CRDNAME"))
                    .andExpect(jsonPath("$.fieldErrors[0].state")
                            .value(ErrorResponse.FieldState.MISSING.name()))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value("Card name not provided"))
                    .andExpect(jsonPath("$.fieldErrors[1].state")
                            .value(ErrorResponse.FieldState.INVALID.name()))
                    .andExpect(jsonPath("$.fieldErrors[1].message")
                            .value("Card expiry month must be between 1 and 12"));
        }

        @Test
        @DisplayName("publishes no field protection or confirmation emphasis state")
        void publishesNoProtectionState() throws Exception {
            when(cardUpdateService.processCardUpdate(any())).thenReturn(updateResult(List.of()));

            mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("ENTER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fieldProtection").doesNotExist())
                    .andExpect(jsonPath("$.confirmationKeysHighlighted").doesNotExist());
        }

        @Test
        @DisplayName("rejects a value wider than the legacy screen field could hold")
        void rejectsAnOverWideValue() throws Exception {
            mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"keyAction\":\"ENTER\",\"activeStatus\":\"YN\"}"))
                    .andExpect(status().isBadRequest());

            verify(cardUpdateService, times(0)).processCardUpdate(any());
        }

        @Test
        @DisplayName("times the turn once, tagged by the destination it settled on")
        void timesTheTurnOnce() throws Exception {
            when(cardUpdateService.processCardUpdate(any())).thenReturn(updateResult(List.of()));

            mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("ENTER")))
                    .andExpect(status().isOk());

            assertThat(meterRegistry.find("carddemo.online.cardupdate.turn")
                    .tag("outcome", "card-update").timer()).isNotNull();
        }
    }

    // ==================================================================================================
    // The attention-key projection
    // ==================================================================================================

    @Nested
    @DisplayName("the attention-key projection")
    class TheAttentionKeyProjection {

        @Test
        @DisplayName("projects each resolved key back onto the low terminal identifier the key store "
                + "recognises, so the high-key fold holds in both directions")
        void projectsEachKeyOntoItsLowIdentifier() throws Exception {
            when(cardDetailService.processCardDetail(any())).thenReturn(detailResult());

            assertThat(identifierFor("ENTER")).isEqualTo("DFHENTER");
            assertThat(identifierFor("CLEAR")).isEqualTo("DFHCLEAR");
            assertThat(identifierFor("PA1")).isEqualTo("DFHPA1");
            assertThat(identifierFor("PFK01")).isEqualTo("DFHPF1");
            assertThat(identifierFor("PFK03")).isEqualTo("DFHPF3");
            assertThat(identifierFor("PFK05")).isEqualTo("DFHPF5");
            assertThat(identifierFor("PFK07")).isEqualTo("DFHPF7");
            assertThat(identifierFor("PFK08")).isEqualTo("DFHPF8");
            assertThat(identifierFor("PFK12")).isEqualTo("DFHPF12");
        }

        @Test
        @DisplayName("supplies an identifier that matches no arm when the key resolved to nothing, "
                + "rather than an absent reference or a substitute action")
        void suppliesAnUnmatchedIdentifierForAnAbsentKey() throws Exception {
            when(cardDetailService.processCardDetail(any())).thenReturn(detailResult());

            mockMvc.perform(post(DETAIL_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());

            assertThat(capturedDetailInput().attentionKeyIdentifier()).isEmpty();
        }

        /**
         * Drives one detail turn with the named key and reports the identifier the service received.
         *
         * @param keyActionName the key the caller pressed
         * @return the raw identifier handed to the service
         * @throws Exception if the request could not be performed
         */
        private String identifierFor(final String keyActionName) throws Exception {
            mockMvc.perform(post(DETAIL_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(detailBody(null, null, keyActionName)))
                    .andExpect(status().isOk());
            final ArgumentCaptor<CardDetailService.CardDetailScreenInput> captor =
                    ArgumentCaptor.forClass(CardDetailService.CardDetailScreenInput.class);
            verify(cardDetailService, org.mockito.Mockito.atLeastOnce())
                    .processCardDetail(captor.capture());
            return captor.getValue().attentionKeyIdentifier();
        }
    }

    // ==================================================================================================
    // Fixtures
    // ==================================================================================================

    private static String detailBody(final String accountFilter, final String cardFilter,
            final String keyActionName) {
        final StringBuilder body = new StringBuilder(128).append('{');
        if (accountFilter != null) {
            body.append("\"accountIdFilter\":\"").append(accountFilter).append("\",");
        }
        if (cardFilter != null) {
            body.append("\"cardNumberFilter\":\"").append(cardFilter).append("\",");
        }
        return body.append("\"keyAction\":\"").append(keyActionName).append("\"}").toString();
    }

    /**
     * Reads the input the card-list service was handed.
     *
     * @return the captured input
     */
    private CardListService.CardListScreenInput capturedListInput() {
        final ArgumentCaptor<CardListService.CardListScreenInput> captor =
                ArgumentCaptor.forClass(CardListService.CardListScreenInput.class);
        verify(cardListService).processCardList(captor.capture());
        return captor.getValue();
    }

    /**
     * Reads the input the card-detail service was handed.
     *
     * @return the captured input
     */
    private CardDetailService.CardDetailScreenInput capturedDetailInput() {
        final ArgumentCaptor<CardDetailService.CardDetailScreenInput> captor =
                ArgumentCaptor.forClass(CardDetailService.CardDetailScreenInput.class);
        verify(cardDetailService).processCardDetail(captor.capture());
        return captor.getValue();
    }

    /**
     * Reads the input the card-update service was handed.
     *
     * @return the captured input
     */
    private CardUpdateService.CardUpdateScreenInput capturedUpdateInput() {
        final ArgumentCaptor<CardUpdateService.CardUpdateScreenInput> captor =
                ArgumentCaptor.forClass(CardUpdateService.CardUpdateScreenInput.class);
        verify(cardUpdateService).processCardUpdate(captor.capture());
        return captor.getValue();
    }

    /**
     * Reads the input the card-update service was handed most recently, across however many turns ran.
     *
     * <p>Separate from {@link #capturedUpdateInput()} because that one asserts a single invocation, which
     * is the right assertion for a single-turn test and the wrong one for a test whose whole subject is
     * that two turns do not contaminate each other.
     *
     * @return the last captured input
     */
    private CardUpdateService.CardUpdateScreenInput lastCapturedUpdateInput() {
        final ArgumentCaptor<CardUpdateService.CardUpdateScreenInput> captor =
                ArgumentCaptor.forClass(CardUpdateService.CardUpdateScreenInput.class);
        verify(cardUpdateService, atLeastOnce()).processCardUpdate(captor.capture());
        return captor.getValue();
    }

    /**
     * Renders a card-list request body.
     *
     * @param accountFilter the account filter, or {@code null} to omit it
     * @param cardFilter the card filter, or {@code null} to omit it
     * @param keyActionName the attention key
     * @return the JSON body
     */
    private static String listBody(final String accountFilter, final String cardFilter,
            final String keyActionName) {
        final StringBuilder body = new StringBuilder(160).append('{');
        if (accountFilter != null) {
            body.append("\"accountIdFilter\":\"").append(accountFilter).append("\",");
        }
        if (cardFilter != null) {
            body.append("\"cardNumberFilter\":\"").append(cardFilter).append("\",");
        }
        return body.append("\"keyAction\":\"").append(keyActionName).append("\"}").toString();
    }

    /**
     * Renders a card-update request body carrying a sealed proof.
     *
     * @param keyActionName the attention key
     * @return the JSON body
     */
    private static String updateBody(final String keyActionName) {
        return updateBody(keyActionName,
                TOKEN_SERVICE.sealContinuation(
                        CardUpdateService.ChangeAction.SHOW_DETAILS, CARRIED_IMAGE));
    }

    /**
     * Builds a card-update submission carrying the given attention key and the given sealed state.
     *
     * <p>The sealed state is supplied rather than fabricated because the boundary opens it: a literal
     * stand-in cannot be opened and would be refused as a forgery, which is the whole behaviour the token
     * exists to produce.
     *
     * @param keyActionName the attention key the operator pressed
     * @param sealedContinuation the sealed conversation state to echo, or {@code null} for a first turn
     * @return the request body
     */
    private static String updateBody(final String keyActionName, final String sealedContinuation) {
        return "{\"cardNumber\":\"0000000000000001\",\"embossedName\":\"MARY ANN\","
                + "\"activeStatus\":\"Y\",\"expiryMonth\":\"07\",\"expiryYear\":\"2027\","
                + "\"keyAction\":\"" + keyActionName + "\""
                + (sealedContinuation == null
                        ? ""
                        : ",\"concurrencyToken\":\"" + sealedContinuation + "\"")
                + "}";
    }

    /**
     * Builds card-list rows on the named screen slots.
     *
     * @param slots the one-based screen slots to populate
     * @return the rows in slot order
     */
    private static List<CardListService.CardListRow> rowsFrom(final int... slots) {
        final List<CardListService.CardListRow> rows = new ArrayList<>(slots.length);
        for (final int slot : slots) {
            rows.add(new CardListService.CardListRow(slot, "0000000001" + slot,
                    "000000000000000" + slot, "Y", ""));
        }
        return rows;
    }

    /**
     * Builds a settled card-list turn.
     *
     * @param rows the rows the browse settled on
     * @param markOddSlots whether the odd screen slots carry a selection error
     * @return the settled turn
     */
    private static CardListService.CardListResult listResult(
            final List<CardListService.CardListRow> rows, final boolean markOddSlots) {
        return listResult(rows, markOddSlots, false);
    }

    /**
     * Builds a settled card-list turn, stating the retained end-of-data flag it leaves.
     *
     * @param rows the rows the browse settled on
     * @param markOddSlots whether the odd screen slots carry a selection error
     * @param lastPageAlreadyShown the retained {@code WS-CA-LAST-PAGE-DISPLAYED} the turn leaves
     * @return the settled turn
     */
    private static CardListService.CardListResult listResult(
            final List<CardListService.CardListRow> rows, final boolean markOddSlots,
            final boolean lastPageAlreadyShown) {
        return listResult(rows, markOddSlots, lastPageAlreadyShown, List.of());
    }

    /**
     * Builds a settled card-list turn, stating both the retained end-of-data flag it leaves and the
     * field findings it reported.
     *
     * @param rows the rows the browse settled on
     * @param markOddSlots whether the odd screen slots carry a selection error
     * @param lastPageAlreadyShown the retained {@code WS-CA-LAST-PAGE-DISPLAYED} the turn leaves
     * @param fieldErrors the field findings the turn reported, in the order it reported them
     * @return the settled turn
     */
    private static CardListService.CardListResult listResult(
            final List<CardListService.CardListRow> rows, final boolean markOddSlots,
            final boolean lastPageAlreadyShown,
            final List<ValidationException.FieldError> fieldErrors) {
        final Boolean[] flags = new Boolean[BrowseWindow.CARD_LIST_PAGE_SIZE];
        for (int slot = 1; slot <= BrowseWindow.CARD_LIST_PAGE_SIZE; slot++) {
            flags[slot - 1] = markOddSlots && slot % 2 == 1;
        }
        return new CardListService.CardListResult(
                NavigationService.Route.CARD_LIST,
                ScreenNavigationState.empty(),
                "CCLI",
                new CardListService.ScreenHeader(SCREEN_TITLE_01, SCREEN_TITLE_02, "CCLI",
                        "COCRDLIC", HEADER_DATE, HEADER_TIME),
                rows,
                BrowseWindow.forward(BrowseWindow.CARD_LIST_PAGE_SIZE, "0000000000000001",
                        "0000000000000007", true, false, "1"),
                "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD",
                "",
                Arrays.asList(flags),
                fieldErrors,
                "ACCTSID",
                false,
                false,
                0,
                lastPageAlreadyShown, null);
    }

    /**
     * Builds a settled card-detail turn presenting one card.
     *
     * @return the settled turn
     */
    private static CardDetailService.CardDetailResult detailResult() {
        return detailResult(List.of());
    }

    /**
     * Builds a settled card-detail turn presenting one card and reporting the given field findings.
     *
     * @param fieldErrors the field findings the turn reported, in the order it reported them
     * @return the settled turn
     */
    private static CardDetailService.CardDetailResult detailResult(
            final List<ValidationException.FieldError> fieldErrors) {
        return new CardDetailService.CardDetailResult(
                NavigationService.Route.CARD_DETAIL,
                ScreenNavigationState.empty(),
                new ScreenInputState(KeyAction.ENTER, "COCRDSLC", "COCRDSL", "CCRDSLA", "", "",
                        "00000000011", "0000000000000001", ""),
                "CCDL",
                new CardDetailService.CardProjection("0000000000000001", "00000000011", "MARY ANN",
                        "123", "2027-07-31", "2027", "07", "31", "Y", null),
                "",
                "   Displaying requested details",
                "ACCTSID",
                false,
                false,
                fieldErrors,
                new CardDetailService.ScreenHeader("Card Detail", "View Card", "CCDL", "COCRDSLC",
                        "07/19/22", "23:12:33"),
                new CardDetailService.ScreenFields("00000000011", "0000000000000001", "MARY ANN", "Y",
                        "07", "2027", "   Displaying requested details", "", true, true, false, false,
                        false));
    }

    /**
     * Builds a settled card-update turn reporting a committed write.
     *
     * @param fieldErrors the field findings the turn reported
     * @return the settled turn
     */
    private static CardUpdateService.CardUpdateResult updateResult(
            final List<ValidationException.FieldError> fieldErrors) {
        return new CardUpdateService.CardUpdateResult(
                NavigationService.Route.CARD_UPDATE,
                ScreenNavigationState.empty(),
                "CCUP",
                new ScreenInputState(KeyAction.PFK05, "COCRDUPC", "COCRDUP", "CCRDUPA", "", "",
                        "00000000011", "0000000000000001", ""),
                CardUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE,
                null,
                CardUpdateService.CarriedCardImage.empty(),
                "",
                "Changes committed to database",
                "CRDNAME",
                false,
                false,
                false,
                false,
                CardUpdateService.WriteOutcome.COMMITTED,
                fieldErrors,
                FieldErrorMarks.none(),
                new CardUpdateService.ScreenHeader("Card Update", "Update Card", "CCUP", "COCRDUPC",
                        "07/19/22", "23:12:33"),
                new CardUpdateService.ScreenFields("00000000011", "0000000000000001", "MARY ANN", "Y",
                        "07", "2027", "31", "Changes committed to database", "",
                        CardUpdateService.FieldProtection.CARD_DATA_OPEN, true));
    }
}
