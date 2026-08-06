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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.carddemo.domain.Card;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link CardListService}, transaction {@code CCLI}, translated from
 * {@code app/cbl/COCRDLIC.cbl}.
 *
 * <p>Five behaviours carry the contract on this screen, and each is asserted below rather than assumed:
 *
 * <ol>
 *   <li><strong>The page is exactly seven rows.</strong> {@code WS-SCREEN-ROWS OCCURS 7 TIMES} at COBOL
 *       line 255 fixes it, and a page of eight would be a silent presentation change.
 *   <li><strong>One action per page.</strong> The tallying check at COBOL lines 1079 to 1082 counts the
 *       selection flags and refuses a second action, naming every offending row rather than the first.
 *   <li><strong>Arm order is the contract.</strong> The dispatch at lines 418 to 583 is an
 *       {@code EVALUATE TRUE} whose arms overlap, so an input error re-presents the screen before any
 *       paging arm can be considered, and the trailing arm is a genuine catch-all.
 *   <li><strong>Only four keys are meaningful.</strong> Enter, the third, seventh and eighth function
 *       keys; anything else mapped is coerced to enter and carries no message of its own, which is a
 *       different outcome from an identifier the key store could not map at all.
 *   <li><strong>A selection transfers to a named program.</strong> {@code S} reaches the card detail
 *       screen and {@code U} the card update screen, at lines 517 and 545.
 * </ol>
 *
 * <p>A pure unit test: the card repository is a double whose paged finder returns the browse chunks, and
 * the message catalog, navigation service and abend service are real.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @since 1.0.0
 */
@DisplayName("CardListService :: transaction CCLI, the card list screen")
final class CardListServiceTest {

    /** The page size the legacy table fixes at COBOL line 255. */
    private static final int PAGE_SIZE = 7;

    /** A valid eleven-digit account identifier. */
    private static final String ACCOUNT_ID = "00000000011";

    /** This screen's own program name, which the state-adoption rule tests for. */
    private static final String THIS_PROGRAM = "COCRDLIC";

    /** The card detail program a view selection transfers to. */
    private static final String CARD_DETAIL_PROGRAM = "COCRDSLC";

    /** The card update program an update selection transfers to. */
    private static final String CARD_UPDATE_PROGRAM = "COCRDUPC";

    private CardRepository cardRepository;

    private CardListService service;

    /**
     * The instant the header is stamped from, fixed so the two assembled header fields are exact values
     * rather than a moving target. The same instant the card-detail screen's own tests use, because the two
     * screens translate the same header paragraph and a divergence between them would be invisible if each
     * chose its own reading.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2024-03-09T14:25:36Z");

    /** The header date that instant produces, as {@code MM/DD/YY}. */
    private static final String EXPECTED_HEADER_DATE = "03/09/24";

    /** The header time that instant produces, as {@code HH:MM:SS}. */
    private static final String EXPECTED_HEADER_TIME = "14:25:36";

    /** Width of each catalogue screen title, {@code PIC X(40)}. */
    private static final int FORTY_CHARACTER_FIELD = 40;

    @BeforeEach
    void constructService() {
        this.cardRepository = Mockito.mock(CardRepository.class);
        this.service = new CardListService(this.cardRepository, new MessageCatalogService(),
                new NavigationService(), new AbendService(),
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    }

    // ==============================================================================================
    // Fixtures
    // ==============================================================================================

    /**
     * Builds a card row belonging to the fixture account.
     *
     * @param  ordinal a one-based row number, which becomes the low-order digits of the card number
     * @return the row
     */
    private static Card cardRow(final int ordinal) {
        // Locale.ROOT is mandatory, not decorative. The card number is a fixed-width sixteen-byte
        // field that must stay inside US-ASCII, and an unqualified format emits the default locale's
        // digits - under a locale whose numbering system is not Latin those are not ASCII digits at
        // all, and the row would carry a card number no fixed-width image could hold.
        final String cardNumber = String.format(Locale.ROOT, "41111111111111%02d", ordinal);
        return new Card(cardNumber, ACCOUNT_ID, "123", "ALICE SMITH", "2029-12-31", "Y");
    }

    /**
     * Stubs the keyset browse with the supplied number of rows, behaving like an ordered primary-key
     * cluster: the inclusive open is the inherited keyed read and every continuation is a strict,
     * ordered, limit-bounded range read.
     *
     * <p>The bound is applied here exactly as the derived query name declares it. A stub that read
     * inclusively would hide a repeated boundary row, and one that ignored the limit would hide an
     * unbounded read, so both are reproduced rather than approximated.
     *
     * @param rowCount how many card rows the cluster holds
     */
    private void seedCards(final int rowCount) {
        final List<Card> rows = new ArrayList<>();
        for (int ordinal = 1; ordinal <= rowCount; ordinal++) {
            rows.add(cardRow(ordinal));
        }
        rows.sort(Comparator.comparing(Card::getCardNum));
        Mockito.when(cardRepository.findById(ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> rows.stream()
                        .filter(row -> row.getCardNum().equals(invocation.getArgument(0)))
                        .findFirst());
        Mockito.when(cardRepository.findByCardNumGreaterThanOrderByCardNumAsc(
                        ArgumentMatchers.anyString(), ArgumentMatchers.any(Limit.class)))
                .thenAnswer(invocation -> window(rows, invocation, true));
        Mockito.when(cardRepository.findByCardNumLessThanOrderByCardNumDesc(
                        ArgumentMatchers.anyString(), ArgumentMatchers.any(Limit.class)))
                .thenAnswer(invocation -> window(rows, invocation, false));
    }

    /**
     * Returns one strict, ordered, limit-bounded window for a stubbed range read.
     *
     * @param rows       the ordered cluster contents
     * @param invocation the stubbed call, carrying the cursor and the limit
     * @param ascending  whether the read order is ascending
     * @return the rows the read would return, in read order
     */
    private static List<Card> window(final List<Card> rows, final InvocationOnMock invocation,
            final boolean ascending) {
        final String cursor = invocation.getArgument(0);
        final Limit limit = invocation.getArgument(1);
        final List<Card> ordered = new ArrayList<>(rows);
        if (!ascending) {
            ordered.sort(Comparator.comparing(Card::getCardNum).reversed());
        }
        return ordered.stream()
                .filter(row -> ascending
                        ? row.getCardNum().compareTo(cursor) > 0
                        : row.getCardNum().compareTo(cursor) < 0)
                .limit(limit.max())
                .toList();
    }

    /** Stubs the keyset browse with no rows at all. */
    private void seedNoCards() {
        Mockito.when(cardRepository.findById(ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        Mockito.when(cardRepository.findByCardNumGreaterThanOrderByCardNumAsc(
                        ArgumentMatchers.anyString(), ArgumentMatchers.any(Limit.class)))
                .thenReturn(List.of());
        Mockito.when(cardRepository.findByCardNumLessThanOrderByCardNumDesc(
                        ArgumentMatchers.anyString(), ArgumentMatchers.any(Limit.class)))
                .thenReturn(List.of());
    }

    /**
     * Builds the transmitted work area carrying the two filters and the mapped key.
     *
     * @param  accountFilter the account filter as keyed, or {@code null}
     * @param  cardFilter    the card filter as keyed, or {@code null}
     * @return the work area
     */
    private static ScreenInputState workArea(final String accountFilter, final String cardFilter) {
        return new ScreenInputState(KeyAction.ENTER, null, null, null, null, null, accountFilter,
                cardFilter, null);
    }

    /** @return a carried state that reads as this screen submitting to itself */
    private static ScreenNavigationState reSubmission() {
        return new ScreenNavigationState("CCLI", THIS_PROGRAM, "CCLI", THIS_PROGRAM, "USER0001", "U",
                ScreenNavigationState.ProgramContext.REENTER, null, null, null, null, null, null, null,
                "CCRDLIA", "COCRDLI");
    }

    /**
     * Builds a turn: the raw key, the two filters, the selections and the carried state.
     *
     * @param  rawKey     the raw attention identifier
     * @param  selections the seven selection fields, shorter lists being padded
     * @param  context    the echoed navigation state
     * @return the input
     */
    private static CardListService.CardListScreenInput turn(final String rawKey,
            final List<String> selections, final ScreenNavigationState context) {
        return new CardListService.CardListScreenInput(rawKey, workArea(null, null), selections, null,
                1, false, false, context);
    }

    /**
     * Builds the seven selection fields with one row carrying an action.
     *
     * @param  slot   the one-based row the action sits on
     * @param  action the action character
     * @return the seven fields
     */
    private static List<String> selectionAt(final int slot, final String action) {
        final List<String> selections = new ArrayList<>(Collections.nCopies(PAGE_SIZE, ""));
        selections.set(slot - 1, action);
        return selections;
    }

    // ==============================================================================================
    // Construction and argument checking
    // ==============================================================================================

    @Nested
    @DisplayName("construction and argument checking")
    final class Construction {

        @Test
        @DisplayName("each constructor argument is mandatory")
        void everyCollaboratorIsMandatory() {
            final MessageCatalogService catalog = new MessageCatalogService();
            final NavigationService navigation = new NavigationService();
            final AbendService abend = new AbendService();
            final Clock headerClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

            assertThatNullPointerException()
                    .isThrownBy(() -> new CardListService(null, catalog, navigation, abend,
                            headerClock));
            assertThatNullPointerException()
                    .isThrownBy(() -> new CardListService(cardRepository, null, navigation, abend,
                            headerClock));
            assertThatNullPointerException()
                    .isThrownBy(() -> new CardListService(cardRepository, catalog, null, abend,
                            headerClock));
            assertThatNullPointerException()
                    .isThrownBy(() -> new CardListService(
                            cardRepository, catalog, navigation, null, headerClock));
            assertThatNullPointerException()
                    .isThrownBy(() -> new CardListService(
                            cardRepository, catalog, navigation, abend, null));
        }

        @Test
        @DisplayName("a turn with no input at all is refused rather than read as an empty screen")
        void anAbsentTurnIsRefused() {
            assertThatNullPointerException().isThrownBy(() -> service.processCardList(null));
        }

        @Test
        @DisplayName("a selection list longer than the page is refused, because a screen with more "
                + "selections than rows cannot be represented")
        void aSelectionListLongerThanThePageIsRefused() {
            final List<String> tooMany = Collections.nCopies(PAGE_SIZE + 1, "");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> turn("DFHENTER", tooMany, reSubmission()))
                    .withMessageContaining(String.valueOf(PAGE_SIZE));
        }

        @Test
        @DisplayName("an absent selection list is read as seven blank fields, and a short one is padded "
                + "to the page width")
        void anAbsentOrShortSelectionListIsPadded() {
            final CardListService.CardListScreenInput absent =
                    turn("DFHENTER", null, reSubmission());
            final CardListService.CardListScreenInput shortList =
                    turn("DFHENTER", List.of("", "S"), reSubmission());

            assertThat(absent.selections()).hasSize(PAGE_SIZE).containsOnly("");
            assertThat(shortList.selections()).hasSize(PAGE_SIZE);
            assertThat(shortList.selectionAt(2)).isEqualTo("S");
        }

        @Test
        @DisplayName("a selection outside the seven rows cannot be read, so a row index is checked "
                + "rather than trusted")
        void aSelectionOutsideThePageCannotBeRead() {
            final CardListService.CardListScreenInput input =
                    turn("DFHENTER", selectionAt(1, "S"), reSubmission());

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> input.selectionAt(0))
                    .withMessageContaining(String.valueOf(PAGE_SIZE));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> input.selectionAt(PAGE_SIZE + 1))
                    .withMessageContaining(String.valueOf(PAGE_SIZE + 1));
        }
    }

    // ==============================================================================================
    // The browse
    // ==============================================================================================

    @Nested
    @DisplayName("the browse fills exactly seven rows")
    final class Browse {

        @Test
        @DisplayName("a first entry browses forward and fills the page, and the informational field "
                + "carries the action prompt")
        void aFirstEntryFillsThePage() {
            seedCards(PAGE_SIZE);

            final CardListService.CardListResult result =
                    service.processCardList(turn("DFHENTER", null, null));

            assertThat(result.rows()).hasSize(PAGE_SIZE);
            assertThat(result.recordsFound()).isTrue();
            assertThat(result.infoMessage()).isEqualTo("TYPE S FOR DETAIL, U TO UPDATE ANY RECORD");
            assertThat(result.errorFlag()).isFalse();
            assertThat(result.reArmedTransactionId()).isEqualTo("CCLI");
            assertThat(result.rows().get(0).screenSlot()).isOne();
            assertThat(result.rows().get(PAGE_SIZE - 1).screenSlot()).isEqualTo(PAGE_SIZE);
            assertThat(result.selectionErrorFlags()).hasSize(PAGE_SIZE).containsOnly(Boolean.FALSE);
        }

        @Test
        @DisplayName("a cluster holding more than a page still yields seven rows, because the page size "
                + "is the legacy table's own width and not the chunk the reader happened to deliver")
        void moreThanAPageStillYieldsSevenRows() {
            seedCards(PAGE_SIZE * 3);

            final CardListService.CardListResult result =
                    service.processCardList(turn("DFHENTER", null, null));

            assertThat(result.rows()).hasSize(PAGE_SIZE);
            assertThat(result.pageMetadata()).isNotNull();
        }

        @Test
        @DisplayName("an empty cluster reports the no-records text and presents no rows")
        void anEmptyClusterReportsNoRecords() {
            seedNoCards();

            final CardListService.CardListResult result =
                    service.processCardList(turn("DFHENTER", null, null));

            assertThat(result.rows()).isEmpty();
            assertThat(result.recordsFound()).isFalse();
            assertThat(result.errorMessage())
                    .isEqualTo("NO RECORDS FOUND FOR THIS SEARCH CONDITION.");
        }

        @Test
        @DisplayName("fewer rows than a page fills only what the cluster holds, leaving the remaining "
                + "slots unbuilt rather than padded with blank rows")
        void fewerRowsThanAPageFillsOnlyWhatExists() {
            seedCards(3);

            final CardListService.CardListResult result =
                    service.processCardList(turn("DFHENTER", null, null));

            assertThat(result.rows()).hasSize(3);
            assertThat(result.rows()).extracting(CardListService.CardListRow::accountId)
                    .containsOnly(ACCOUNT_ID);
        }

        @Test
        @DisplayName("the echoed last-page state distinguishes the second forward press")
        void theLastPageAlreadyShownStateSurvivesTheTurn() {
            seedCards(PAGE_SIZE);
            final CardListService.CardListScreenInput input =
                    new CardListService.CardListScreenInput(
                            "DFHPF8",
                            workArea(null, null),
                            null,
                            new BrowseWindow.CursorRequest(
                                    null, cardRow(PAGE_SIZE).getCardNum(),
                                    BrowseWindow.PagingDirection.FORWARD),
                            7,
                            true,
                            false,
                            reSubmission());

            final CardListService.CardListResult result = service.processCardList(input);

            assertThat(result.errorMessage()).isEqualTo("NO MORE PAGES TO DISPLAY");
        }

        @Test
        @DisplayName("a forward press raises the echoed page number rather than restarting from one, so "
                + "the third page is reported as the third")
        void aForwardPressRaisesTheEchoedPageNumber() {
            seedCards(PAGE_SIZE * 3);

            final CardListService.CardListResult result = service.processCardList(
                    new CardListService.CardListScreenInput(
                            "DFHPF8",
                            workArea(null, null),
                            null,
                            new BrowseWindow.CursorRequest(
                                    cardRow(1).getCardNum(), cardRow(PAGE_SIZE).getCardNum(),
                                    BrowseWindow.PagingDirection.FORWARD),
                            2,
                            false,
                            true,
                            reSubmission()));

            assertThat(result.pageMetadata().displayedPageNumber())
                    .as("the program holds the page number in its communication area and raises it on "
                            + "a page-down; a boundary that supplied a constant first page would make "
                            + "every forward press report page two")
                    .isEqualTo("3");
        }

        @Test
        @DisplayName("a backward press lowers the echoed page number, which is the same retained value "
                + "read in the other direction")
        void aBackwardPressLowersTheEchoedPageNumber() {
            seedCards(PAGE_SIZE * 3);

            final CardListService.CardListResult result = service.processCardList(
                    new CardListService.CardListScreenInput(
                            "DFHPF7",
                            workArea(null, null),
                            null,
                            new BrowseWindow.CursorRequest(
                                    cardRow(PAGE_SIZE + 1).getCardNum(),
                                    cardRow(PAGE_SIZE * 2).getCardNum(),
                                    BrowseWindow.PagingDirection.FORWARD),
                            3,
                            false,
                            true,
                            reSubmission()));

            assertThat(result.pageMetadata().displayedPageNumber()).isEqualTo("2");
        }

        @Test
        @DisplayName("the retained end-of-data state is answered back, so the next turn can echo the "
                + "value this turn settled on")
        void theRetainedEndOfDataStateIsAnsweredBack() {
            seedCards(PAGE_SIZE);

            final CardListService.CardListResult result = service.processCardList(
                    new CardListService.CardListScreenInput(
                            "DFHPF8",
                            workArea(null, null),
                            null,
                            new BrowseWindow.CursorRequest(
                                    null, cardRow(PAGE_SIZE).getCardNum(),
                                    BrowseWindow.PagingDirection.FORWARD),
                            1,
                            false,
                            false,
                            reSubmission()));

            assertThat(result.lastPageAlreadyShown())
                    .as("a page-down that finds nothing beyond the page on the screen is what sets the "
                            + "retained flag, and the turn has to answer it back for the next one to "
                            + "distinguish an exhausted browse from an unexplored one")
                    .isTrue();
        }
    }

    // ==============================================================================================
    // The filters
    // ==============================================================================================

    @Nested
    @DisplayName("the two key filters are edited before anything is read")
    final class Filters {

        @ParameterizedTest(name = "account filter {0} is refused")
        @ValueSource(strings = {"1", "123456789012", "0000000001A"})
        @DisplayName("an account filter that is not eleven digits is refused with the legacy text, and "
                + "the offending filter is flagged")
        void aMalformedAccountFilterIsRefused(final String keyed) {
            seedCards(PAGE_SIZE);

            final CardListService.CardListResult result = service.processCardList(
                    new CardListService.CardListScreenInput("DFHENTER", workArea(keyed, null), null,
                            null, 1, false, false, reSubmission()));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.errorMessage())
                    .isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
        }

        @ParameterizedTest(name = "card filter {0} is refused")
        @ValueSource(strings = {"4111", "411111111111111111", "411111111111111A"})
        @DisplayName("a card filter that is not sixteen digits is refused with its own legacy text")
        void aMalformedCardFilterIsRefused(final String keyed) {
            seedCards(PAGE_SIZE);

            final CardListService.CardListResult result = service.processCardList(
                    new CardListService.CardListScreenInput("DFHENTER", workArea(null, keyed), null,
                            null, 1, false, false, reSubmission()));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.errorMessage())
                    .isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
        }

        @Test
        @DisplayName("a well-formed account filter is accepted and the browse still runs, so a filter "
                + "that passes its edit does not become an error by accident")
        void aWellFormedAccountFilterIsAccepted() {
            seedCards(PAGE_SIZE);

            final CardListService.CardListResult result = service.processCardList(
                    new CardListService.CardListScreenInput("DFHENTER", workArea(ACCOUNT_ID, null),
                            null, null, 1, false, false, reSubmission()));

            assertThat(result.errorFlag()).isFalse();
            assertThat(result.rows()).isNotEmpty();
        }
    }

    // ==============================================================================================
    // Selections
    // ==============================================================================================

    @Nested
    @DisplayName("the selection column, and the one-action-per-page rule")
    final class Selections {

        @Test
        @DisplayName("a view selection transfers to the card detail screen, carrying the selected row")
        void aViewSelectionTransfersToTheDetailScreen() {
            seedCards(PAGE_SIZE);

            final CardListService.CardListResult result = service.processCardList(
                    turn("DFHENTER", selectionAt(2, "S"), reSubmission()));

            assertThat(result.selectedRowIndex()).isEqualTo(2);
            assertThat(result.navigationContext().toProgram()).isEqualTo(CARD_DETAIL_PROGRAM);
            assertThat(result.errorFlag()).isFalse();
        }

        @Test
        @DisplayName("an update selection transfers to the card update screen instead, which is the "
                + "second of the two arms at lines 517 and 545")
        void anUpdateSelectionTransfersToTheUpdateScreen() {
            seedCards(PAGE_SIZE);

            final CardListService.CardListResult result = service.processCardList(
                    turn("DFHENTER", selectionAt(3, "U"), reSubmission()));

            assertThat(result.selectedRowIndex()).isEqualTo(3);
            assertThat(result.navigationContext().toProgram()).isEqualTo(CARD_UPDATE_PROGRAM);
        }

        @Test
        @DisplayName("an unrecognised action character is refused with the invalid-action text and the "
                + "offending row alone is flagged")
        void anUnrecognisedActionIsRefused() {
            seedCards(PAGE_SIZE);

            final CardListService.CardListResult result = service.processCardList(
                    turn("DFHENTER", selectionAt(4, "X"), reSubmission()));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.errorMessage()).isEqualTo("INVALID ACTION CODE");
            assertThat(result.selectionErrorFlags().get(3)).isTrue();
            assertThat(result.selectionErrorFlags().get(0)).isFalse();
        }

        @Test
        @DisplayName("the faulted row is reported as a state-bearing field finding naming its own screen "
                + "field, so a response layer can decorate exactly the selector the operator typed into")
        void theFaultedRowIsReportedAsAFieldFinding() {
            seedCards(PAGE_SIZE);

            final CardListService.CardListResult result = service.processCardList(
                    turn("DFHENTER", selectionAt(4, "X"), reSubmission()));

            assertThat(result.fieldErrors()).hasSize(1);
            final ValidationException.FieldError finding = result.fieldErrors().get(0);
            assertThat(finding.field()).isEqualTo("selection4");
            assertThat(finding.bmsFieldId()).isEqualTo("CRDSEL4");
            assertThat(finding.state())
                    .as("a character was supplied and is unusable, which the legacy signals by colouring "
                            + "the field rather than by writing the blank-field marker")
                    .isEqualTo(ValidationException.FieldState.INVALID);
            assertThat(finding.message()).isEqualTo("INVALID ACTION CODE");
        }

        @Test
        @DisplayName("both faulted rows are reported as findings in slot order, so the ordered contract "
                + "carries the same pair the positional indicator does")
        void bothFaultedRowsAreReportedInSlotOrder() {
            seedCards(PAGE_SIZE);
            final List<String> twoActions = selectionAt(1, "S");
            twoActions.set(4, "U");

            final CardListService.CardListResult result =
                    service.processCardList(turn("DFHENTER", twoActions, reSubmission()));

            assertThat(result.fieldErrors())
                    .extracting(ValidationException.FieldError::field)
                    .containsExactly("selection1", "selection5");
        }

        @Test
        @DisplayName("two actions on one page are refused and BOTH rows are flagged, which is what the "
                + "tallying check buys over stopping at the first")
        void twoActionsOnOnePageAreBothFlagged() {
            seedCards(PAGE_SIZE);
            final List<String> twoActions = selectionAt(1, "S");
            twoActions.set(4, "U");

            final CardListService.CardListResult result =
                    service.processCardList(turn("DFHENTER", twoActions, reSubmission()));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.selectionErrorFlags().get(0)).isTrue();
            assertThat(result.selectionErrorFlags().get(4)).isTrue();
            assertThat(result.navigationContext().toProgram())
                    .as("a refused page transfers nowhere")
                    .isNotEqualTo(CARD_DETAIL_PROGRAM);
        }

        @Test
        @DisplayName("a lower-case action character is refused, because the condition names at COBOL "
                + "lines 77 to 79 name upper case only and the bitmap replaces upper case only")
        void aLowerCaseActionIsRefused() {
            // app/cbl/COCRDLIC.cbl line 77 declares SELECT-OK as VALUES 'S', 'U', and the bitmap at
            // lines 1090 to 1093 replaces ALL 'S' and ALL 'U' - both upper case. Folding the case here
            // would accept input the screen rejects, so the refusal is the faithful outcome and is
            // pinned rather than left to whichever way a later reader guesses.
            seedCards(PAGE_SIZE);

            final CardListService.CardListResult result = service.processCardList(
                    turn("DFHENTER", selectionAt(5, "s"), reSubmission()));

            assertThat(result.errorFlag()).isTrue();
            assertThat(result.errorMessage()).isEqualTo("INVALID ACTION CODE");
            assertThat(result.selectionErrorFlags().get(4)).isTrue();
            assertThat(result.selectedRowIndex())
                    .as("a refused character selects nothing")
                    .isZero();
        }

        @Test
        @DisplayName("the selected row can be read back off the result, and a page with no selection "
                + "reports none")
        void theSelectedRowIsReadableAndAbsentWhenUnselected() {
            seedCards(PAGE_SIZE);

            final CardListService.CardListResult unselected =
                    service.processCardList(turn("DFHENTER", null, null));

            assertThat(unselected.selectedRowIndex()).isZero();
            assertThat(unselected.selectedRow()).isEmpty();
        }
    }

    // ==============================================================================================
    // Keys
    // ==============================================================================================

    @Nested
    @DisplayName("only four keys are meaningful on this screen")
    final class Keys {

        @Test
        @DisplayName("the third function key from this screen ends the turn at the menu and says so")
        void theThirdFunctionKeyExitsToTheMenu() {
            final CardListService.CardListResult result =
                    service.processCardList(turn("DFHPF3", null, reSubmission()));

            assertThat(result.errorMessage()).isEqualTo("PF03 PRESSED.EXITING");
            assertThat(result.navigationContext().toProgram()).isEqualTo("COMEN01C");
            assertThat(result.route()).isNotNull();
        }

        @Test
        @DisplayName("the backward key on the first page re-reads the same page forward rather than "
                + "walking before the beginning of the cluster")
        void theBackwardKeyOnTheFirstPageRereadsTheSamePage() {
            seedCards(PAGE_SIZE);

            final CardListService.CardListResult result =
                    service.processCardList(turn("DFHPF7", null, reSubmission()));

            assertThat(result.rows()).hasSize(PAGE_SIZE);
            assertThat(result.rows().get(0).screenSlot()).isOne();
        }

        @ParameterizedTest(name = "{0} is coerced to enter and carries no message of its own")
        @ValueSource(strings = {"DFHPF4", "DFHPF5", "DFHCLEAR", "DFHPA1"})
        @DisplayName("any other mapped key is processed as enter, which is a different outcome from an "
                + "identifier the key store could not map")
        void anyOtherMappedKeyIsCoercedToEnter(final String rawKey) {
            seedCards(PAGE_SIZE);

            final CardListService.CardListResult result =
                    service.processCardList(turn(rawKey, null, reSubmission()));

            assertThat(result.rows()).hasSize(PAGE_SIZE);
            assertThat(result.errorMessage()).isNotEqualTo("PF03 PRESSED.EXITING");
        }
    }

    // ==============================================================================================
    // The two diagnostic senders
    // ==============================================================================================

    @Nested
    @DisplayName("the two diagnostic senders render at their declared widths")
    final class DiagnosticSenders {

        @Test
        @DisplayName("plain text and long text hand back the message at the declared width of the field "
                + "the source transmits, space padded and never trimmed")
        void bothSendersRenderAtTheDeclaredWidth() {
            // A machine interface has no terminal to write to, so what remains of the two send
            // paragraphs is the move into the transmitted field - and a move into a fixed field pads on
            // the right. Trimming here would change a transmitted record's width.
            final String plain = service.sendPlainText("ANY TEXT");
            final String longText = service.sendLongText("ANY LONGER TEXT");

            assertThat(plain).startsWith("ANY TEXT").isNotEqualTo("ANY TEXT");
            assertThat(plain.strip()).isEqualTo("ANY TEXT");
            assertThat(longText).startsWith("ANY LONGER TEXT");
            assertThat(longText.strip()).isEqualTo("ANY LONGER TEXT");
            assertThat(longText.length())
                    .as("the long diagnostic field is wider than the message field")
                    .isGreaterThan(plain.length());
            assertThat(service.sendPlainText(null).strip()).isEmpty();
        }
    }

    // ==============================================================================================
    // The paged browse contract
    // ==============================================================================================

    @Nested
    @DisplayName("the browse reads through the repository's bounded keyset finders")
    final class KeysetFinder {

        @Test
        @DisplayName("the browse asks for chunks of the page width and never an offset page, so a page "
                + "is never assembled from a read wider than the screen")
        void theBrowseAsksForChunksOfThePageWidth() {
            seedCards(PAGE_SIZE * 2);

            service.processCardList(turn("DFHENTER", null, null));

            final var captor = org.mockito.ArgumentCaptor.forClass(Limit.class);
            Mockito.verify(cardRepository, Mockito.atLeastOnce())
                    .findByCardNumGreaterThanOrderByCardNumAsc(
                            ArgumentMatchers.anyString(), captor.capture());
            assertThat(captor.getAllValues())
                    .allSatisfy(limit -> assertThat(limit.max()).isEqualTo(PAGE_SIZE));
            Mockito.verify(cardRepository, Mockito.never())
                    .findAll(ArgumentMatchers.any(Pageable.class));
        }

        @Test
        @DisplayName("an exhausted browse is tolerated: the reader returning an empty final chunk ends "
                + "the walk instead of failing it")
        void anExhaustedBrowseIsTolerated() {
            Mockito.when(cardRepository.findById(ArgumentMatchers.anyString()))
                    .thenReturn(Optional.empty());
            Mockito.when(cardRepository.findByCardNumGreaterThanOrderByCardNumAsc(
                            ArgumentMatchers.anyString(), ArgumentMatchers.any(Limit.class)))
                    .thenReturn(List.of(cardRow(1)))
                    .thenReturn(List.of());

            final CardListService.CardListResult result =
                    service.processCardList(turn("DFHENTER", null, null));

            assertThat(result.rows()).hasSize(1);
        }
    }
}
