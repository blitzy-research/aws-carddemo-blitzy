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
import com.carddemo.api.dto.FieldErrorDecorator;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.api.dto.PageMetadata;
import com.carddemo.api.dto.ScreenWorkArea;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.CardDetailService;
import com.carddemo.service.CardListService;
import com.carddemo.service.CardUpdateService;
import com.carddemo.service.NavigationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

    /** The card-list screen, stubbed. */
    private CardListService cardListService;

    /** The card-detail screen, stubbed. */
    private CardDetailService cardDetailService;

    /** The card-update screen, stubbed. */
    private CardUpdateService cardUpdateService;

    /** Registry the turn timers register against. */
    private MeterRegistry meterRegistry;

    /** The boundary under test. */
    private CardController controller;

    /** The boundary driven through the servlet contract. */
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        cardListService = mock(CardListService.class);
        cardDetailService = mock(CardDetailService.class);
        cardUpdateService = mock(CardUpdateService.class);
        meterRegistry = new SimpleMeterRegistry();
        controller = new CardController(cardListService, cardDetailService, cardUpdateService,
                meterRegistry);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
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
                    cardDetailService, cardUpdateService, meterRegistry));
            assertThatNullPointerException().isThrownBy(() -> new CardController(cardListService,
                    null, cardUpdateService, meterRegistry));
            assertThatNullPointerException().isThrownBy(() -> new CardController(cardListService,
                    cardDetailService, null, meterRegistry));
            assertThatNullPointerException().isThrownBy(() -> new CardController(cardListService,
                    cardDetailService, cardUpdateService, null));
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
        @DisplayName("publishes no screen heading the turn did not produce, rather than inventing one")
        void publishesNoFabricatedHeading() throws Exception {
            when(cardListService.processCardList(any())).thenReturn(listResult(rowsFrom(1), false));

            mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody(null, null, "ENTER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title01").doesNotExist())
                    .andExpect(jsonPath("$.title02").doesNotExist())
                    .andExpect(jsonPath("$.currentDate").doesNotExist())
                    .andExpect(jsonPath("$.currentTime").doesNotExist());
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
                + "transmitted, and supplies the paging conclusions as the first-page neutral")
        void stagesTheTransmittedValuesWithoutAlteringThem() throws Exception {
            when(cardListService.processCardList(any())).thenReturn(listResult(List.of(), false));

            mockMvc.perform(post(LIST_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(listBody(" 0000000001", "0000000000000009", "PFK08")))
                    .andExpect(status().isOk());

            final CardListService.CardListScreenInput captured = capturedListInput();
            final ScreenWorkArea workArea = captured.workArea();
            assertThat(workArea.accountId()).isEqualTo(" 0000000001");
            assertThat(workArea.cardNumber()).isEqualTo("0000000000000009");
            assertThat(workArea.keyAction()).isEqualTo(KeyAction.PFK08);
            assertThat(workArea.nextProgram()).isNull();
            assertThat(captured.currentPageNumber()).isEqualTo(1);
            assertThat(captured.lastPageAlreadyShown()).isFalse();
            assertThat(captured.nextPageIndicated()).isFalse();
            assertThat(captured.selections()).hasSize(PageMetadata.CARD_LIST_PAGE_SIZE);
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

            final PageMetadata.PageCursorRequest cursor = capturedListInput().pageCursor();
            assertThat(cursor.previousCursorKey()).isEqualTo("0000000000000001");
            assertThat(cursor.nextCursorKey()).isEqualTo("0000000000000007");
            assertThat(cursor.direction()).isEqualTo(PageMetadata.PagingDirection.FORWARD);
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

            mockMvc.perform(get(DETAIL_ROUTE)
                            .param("accountIdFilter", "00000000011")
                            .param("cardNumberFilter", "0000000000000001")
                            .param("keyAction", "ENTER"))
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
        @DisplayName("publishes no protection, highlight or darkening state, because those are how a "
                + "terminal achieved an effect and not part of the contract")
        void publishesNoPresentationState() throws Exception {
            when(cardDetailService.processCardDetail(any())).thenReturn(detailResult());

            mockMvc.perform(get(DETAIL_ROUTE).param("keyAction", "ENTER"))
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

            mockMvc.perform(get(DETAIL_ROUTE)).andExpect(status().isOk());

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

            mockMvc.perform(get(DETAIL_ROUTE)
                            .param("accountIdFilter", "00000000011")
                            .param("cardNumberFilter", "0000000000000001")
                            .param("keyAction", "ENTER")
                            .param("fromTransactionId", "CCLI")
                            .param("fromProgram", "COCRDLIC")
                            .param("programContext", "REENTER")
                            .param("accountId", "00000000099")
                            .param("cardNumber", "0000000000000099")
                            .param("lastMapset", "COCRDLI"))
                    .andExpect(status().isOk());

            final NavigationContext bound = capturedDetailInput().navigationContext();
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

            mockMvc.perform(get(DETAIL_ROUTE).param("keyAction", "ENTER"))
                    .andExpect(status().isOk());

            assertThat(capturedDetailInput().navigationContext())
                    .isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("times the turn once, tagged by the destination it settled on")
        void timesTheTurnOnce() throws Exception {
            when(cardDetailService.processCardDetail(any())).thenReturn(detailResult());

            mockMvc.perform(get(DETAIL_ROUTE).param("keyAction", "ENTER"))
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
        @DisplayName("echoes the sealed proof back unchanged so the confirming turn can present it")
        void echoesTheSealedProofUnchanged() throws Exception {
            when(cardUpdateService.processCardUpdate(any())).thenReturn(updateResult(List.of()));

            mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("PFK05")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.concurrencyToken").value("sealed-proof-as-presented"));
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
        @DisplayName("leaves the carried change action and before-image for the service to default, "
                + "holding no screen state of its own")
        void leavesTheCarriedStateToTheService() throws Exception {
            when(cardUpdateService.processCardUpdate(any())).thenReturn(updateResult(List.of()));

            mockMvc.perform(post(UPDATE_ROUTE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("ENTER")))
                    .andExpect(status().isOk());

            final CardUpdateService.CardUpdateScreenInput captured = capturedUpdateInput();
            assertThat(captured.changeAction()).isNull();
            assertThat(captured.carriedImage()).isNull();
            assertThat(captured.attentionKeyIdentifier()).isEqualTo("DFHENTER");
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

            mockMvc.perform(get(DETAIL_ROUTE)).andExpect(status().isOk());

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
            mockMvc.perform(get(DETAIL_ROUTE).param("keyAction", keyActionName))
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
        return "{\"cardNumber\":\"0000000000000001\",\"embossedName\":\"MARY ANN\","
                + "\"activeStatus\":\"Y\",\"expiryMonth\":\"07\",\"expiryYear\":\"2027\","
                + "\"keyAction\":\"" + keyActionName + "\","
                + "\"concurrencyToken\":\"sealed-proof-as-presented\"}";
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
        final Boolean[] flags = new Boolean[PageMetadata.CARD_LIST_PAGE_SIZE];
        for (int slot = 1; slot <= PageMetadata.CARD_LIST_PAGE_SIZE; slot++) {
            flags[slot - 1] = markOddSlots && slot % 2 == 1;
        }
        return new CardListService.CardListResult(
                NavigationService.Route.CARD_LIST,
                NavigationContext.empty(),
                "CCLI",
                rows,
                PageMetadata.forward(PageMetadata.CARD_LIST_PAGE_SIZE, "0000000000000001",
                        "0000000000000007", true, false, "1"),
                "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD",
                "",
                Arrays.asList(flags),
                List.of(),
                "ACCTSID",
                false,
                false,
                0);
    }

    /**
     * Builds a settled card-detail turn presenting one card.
     *
     * @return the settled turn
     */
    private static CardDetailService.CardDetailResult detailResult() {
        return new CardDetailService.CardDetailResult(
                NavigationService.Route.CARD_DETAIL,
                NavigationContext.empty(),
                new ScreenWorkArea(KeyAction.ENTER, "COCRDSLC", "COCRDSL", "CCRDSLA", "", "",
                        "00000000011", "0000000000000001", ""),
                "CCDL",
                new CardDetailService.CardProjection("0000000000000001", "00000000011", "MARY ANN",
                        "123", "2027-07-31", "2027", "07", "31", "Y", null),
                "",
                "   Displaying requested details",
                "ACCTSID",
                false,
                false,
                List.of(),
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
                NavigationContext.empty(),
                "CCUP",
                new ScreenWorkArea(KeyAction.PFK05, "COCRDUPC", "COCRDUP", "CCRDUPA", "", "",
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
                FieldErrorDecorator.none(),
                new CardUpdateService.ScreenHeader("Card Update", "Update Card", "CCUP", "COCRDUPC",
                        "07/19/22", "23:12:33"),
                new CardUpdateService.ScreenFields("00000000011", "0000000000000001", "MARY ANN", "Y",
                        "07", "2027", "31", "Changes committed to database", "",
                        CardUpdateService.FieldProtection.CARD_DATA_OPEN, true));
    }
}
