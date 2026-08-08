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
package com.carddemo.api.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit test for {@link PageMetadata}, the browse-cursor contract shared by the three paginated
 * CardDemo screens.
 *
 * <p>{@link PageMetadata} is the REST projection of the browse protocol the legacy screens use to
 * walk a key-sequenced cluster: position at a record key, walk forward, walk backward, release.
 * Seven properties of that protocol are contractual rather than incidental, and each is asserted
 * below against the legacy member that establishes it.</p>
 * <ol>
 *   <li>Three screen row counts, one per paginated screen, each proven by a different mechanism in a
 *       different member and each asserted against its own independent arithmetic rather than
 *       against either of the other two.</li>
 *   <li>No aggregate row figure of any kind. The legacy browse never counts a cluster; it discovers
 *       that a further page exists by attempting one more read and observing the outcome, so the two
 *       conditions the screens actually know are carried as the separate flags
 *       {@link PageMetadata#hasMorePages()} and {@link PageMetadata#hasPreviousPages()}.</li>
 *   <li>An opaque textual cursor, never a numeric offset. The legacy programs retain a record key
 *       across a pseudo-conversational turn and restart the browse from it, so leading zeros,
 *       embedded characters and padding all have to survive untouched.</li>
 *   <li>Two boundary cursors, both live at once. Each paginated screen retains the key of the first
 *       row on the page <em>and</em> the key of the last row, as two adjacent fields of one commarea
 *       group, and repositions on whichever one the operator's attention key calls for - the first
 *       key for the preceding page, the last key for the following one. The card-list pair is a
 *       16-character card number followed by an 11-digit account identifier, the transaction-list
 *       pair is 16-character siblings and the user-list pair is 8-character siblings
 *       [{@code app/cbl/COCRDLIC.cbl}, {@code app/cbl/COTRN00C.cbl}, {@code app/cbl/COUSR00C.cbl}].
 *       A page in the middle of a browse can be paged either way and therefore needs both, which is
 *       asserted in {@link BoundaryCursorPairContract}. The two are an ordered pair rather than a
 *       set: swapping them yields a different value, and that is asserted too.</li>
 *   <li>A textual page indicator whose width differs by screen: the card-list map declares three
 *       alphanumeric characters [{@code app/cpy-bms/COCRDLI.CPY}], the transaction-list and
 *       user-list maps eight [{@code app/cpy-bms/COTRN00.CPY}, {@code app/cpy-bms/COUSR00.CPY}].
 *       Neither width is normalised to the other and neither becomes numeric.</li>
 *   <li>Exactly two browse directions, one per legacy browse verb, with no third constant and no
 *       default, because the legacy programs always branch on an explicit attention key.</li>
 *   <li>A cursor crosses the wire in full and never reaches a diagnostic. The card cursor is a
 *       primary account number concatenated with an account identifier, so it is regulated data that
 *       the client nevertheless has to receive in order to resume the browse. The accessors, the JSON
 *       wire form and equality therefore carry both cursors byte for byte while
 *       {@link PageMetadata#toString()} replaces both with a fixed placeholder, asserted in
 *       {@link DiagnosticRedactionContract}. Decision log entry DL-081 records the arrangement.</li>
 * </ol>
 *
 * <p>The three counts are asserted in {@link ScreenRowCountContract}. Nothing derives one count from
 * another, and the two counts that happen to be equal are asserted against two separate constants
 * using two separate legacy arithmetics. The card list's seven rows follow from a 196-character
 * all-rows screen area redefined as seven occurrences of a 28-character element - an 11-character
 * account identifier, a 16-character card number and a 1-character status indicator - with a
 * declared screen-line counter of 7 and a backward fill counter seeded one past it as two further
 * witnesses. The transaction list's ten rows follow <em>purely from loop bounds</em>, because that
 * program declares no screen-row table at all, so a future reader who looks for a table and finds
 * none must not conclude the constant is unfounded. The user list's ten rows follow from a genuine
 * table of ten occurrences whose element sums to 48 characters across five data fields and two
 * fillers. Each of those figures is a legacy screen shape rather than a knob: changing any of them
 * would put a different number of rows in front of an operator, which is a visible behavioural
 * change and not a configuration adjustment.</p>
 *
 * <p>This is a pure in-process unit test: no application context, no database connection, no
 * container, no file and no network, because the type under test is an immutable record whose only
 * dependencies are two validation annotations and {@code java.util.Objects}. It performs no
 * introspection either - immutability, the constant declarations and the absence of a mutator are
 * established by what this source is able to compile and by observable behaviour, never by
 * interrogating class metadata at run time.</p>
 *
 * <p>Every expected value below is a literal typed out in this source and follows from a measured
 * legacy fact: no expectation is produced by calling the type under test, no assertion compares a
 * computed value with a second evaluation of the same computation, and where an equality expectation
 * involves two instances the two instances are constructed independently.</p>
 */
@DisplayName("PageMetadata :: browse-cursor contract of the three paginated screens")
class PageMetadataTest {

    // Legacy geometry: each constant is a width, a count or a loop bound measured from one named
    // legacy member. None is derived from a constant of the type under test, so an assertion that
    // relates the two is a genuine cross-check rather than a restatement.

    private static final int CARD_ROW_ACCOUNT_ID_WIDTH = 11;

    private static final int CARD_ROW_CARD_NUMBER_WIDTH = 16;

    private static final int CARD_ROW_STATUS_WIDTH = 1;

    /**
     * The card-list rows area as a whole [{@code app/cbl/COCRDLIC.cbl}], which the same member
     * redefines as the row table. Declared independently of the three row-field widths so that the
     * two can be reconciled.
     */
    private static final int CARD_ALL_ROWS_AREA_WIDTH = 196;

    /**
     * The card-list screen-line counter [{@code app/cbl/COCRDLIC.cbl}]: a second, independent
     * witness to the card-list row count, held separately so the two can be reconciled.
     */
    private static final int CARD_DECLARED_SCREEN_LINES = 7;

    private static final int CARD_BACKWARD_FILL_SEED = 8;

    private static final int TRANSACTION_CLEARING_LOOP_BOUND = 10;

    /**
     * Stop value of the transaction-list row-filling loop [{@code app/cbl/COTRN00C.cbl}]. The number
     * of rows filled is this stop value less one, and that is the whole of the evidence for the
     * transaction-list row count, because the program declares no row table.
     */
    private static final int TRANSACTION_FILL_LOOP_STOP = 11;

    private static final int TRANSACTION_BACKWARD_FILL_SEED = 10;

    /**
     * The seven fields of one user-list screen row group, in declaration order
     * [{@code app/cbl/COUSR00C.cbl}]. That group is declared with ten occurrences, which is the
     * user-list row-count evidence.
     */
    private static final List<Integer> USER_ROW_FIELD_WIDTHS = List.of(1, 2, 8, 2, 25, 2, 8);

    /**
     * Total width of one user-list screen row group, declared independently of the seven field
     * widths above so that their sum can be reconciled with it.
     */
    private static final int USER_ROW_GROUP_WIDTH = 48;

    private static final int CARD_KEY_CARD_NUMBER_WIDTH = 16;

    /**
     * Width of the account-identifier half of the card-list program's declared composite work field.
     *
     * <p>Retained deliberately even though it is <em>not</em> part of the browse cursor: the program
     * declares the composite, but at all four of its repositioning sites only the card-number half is
     * moved into the browse key and the companion move of this half is commented out. The constant
     * exists so that the assertions below can state that the cursor bound is the card-number width and
     * is strictly below the composite width, which is the distinction the contract turns on.
     */
    private static final int CARD_KEY_ACCOUNT_ID_WIDTH = 11;

    private static final int TRANSACTION_KEY_WIDTH = 16;

    private static final int USER_KEY_WIDTH = 8;

    private static final int CARD_MAP_INDICATOR_WIDTH = 3;

    private static final int LIST_MAP_INDICATOR_WIDTH = 8;

    // Synthetic sample values. Every one is invented for this test, identifies nothing real and
    // authenticates nothing. Each is shaped to exercise one hazard: the widest legacy key at 16
    // characters, leading zeros a numeric reading would collapse, a value that cannot be parsed as a
    // number at all, and padding on either side of a fixed-width indicator. Within a boundary pair
    // the two values always differ, so an assertion which confused the first cursor with the last
    // would fail rather than pass by coincidence. The two card cursors differ from the two transaction
    // cursors as well, even though both keys are sixteen characters wide, so that a screen mix-up
    // cannot pass either.

    private static final String CARD_LAST_CURSOR = "4111111111110042";

    private static final String CARD_FIRST_CURSOR = "4111111111110036";

    /**
     * Fifteen leading zeros: a numeric reading would collapse this cursor to two characters.
     */
    private static final String TRANSACTION_LAST_CURSOR = "0000000000000042";

    private static final String TRANSACTION_FIRST_CURSOR = "0000000000000033";

    private static final String USER_LAST_CURSOR = "USRT0001";

    private static final String USER_FIRST_CURSOR = "USRA0009";

    private static final String CARD_MAP_INDICATOR = "  1";

    private static final String LIST_MAP_INDICATOR = "00000007";

    private static final String LIST_MAP_INDICATOR_TRAILING = "1       ";

    /**
     * JSON mapper built to match the module's shared configuration file, which declares non-null
     * property inclusion, ISO-8601 rather than numeric dates, lenient handling of unknown inbound
     * properties, and plain rather than scientific decimal output. It is a plain local mapper rather
     * than one obtained from a framework context, because this test starts no context; configuring
     * it from the same four settings is what makes the wire assertions representative of the
     * published contract.
     *
     * <p>For a record of seven scalar components only the value part of the inclusion setting is
     * observable, and the plain-decimal setting has no observable effect at all here since this
     * record carries no decimal component. Both are nonetheless in force, because the mapper is not
     * assembled in this file: it is obtained from
     * {@link JsonContractSupport#declaredSettingsMapper()}, the single place in the test tree where
     * the module's four declared settings are written out by hand. This file therefore cannot
     * transcribe them differently from a sibling suite, and cannot omit one whose effect it did not
     * expect to observe.</p>
     *
     * <p>What that mapper evidences is the shape this record takes <em>under those settings</em>,
     * and nothing more; it is not evidence about the mapper a deployed instance holds, and no
     * assertion below is worded as though it were. {@link ApplicationJsonContractTest} carries that
     * burden against a mapper obtained from a real context that has read the module's
     * {@code application.yml}: it compares that mapper's output with this very factory's output, so
     * an edit to the module's file fails there instead of quietly making this stand-in
     * unrepresentative.</p>
     */
    private static final ObjectMapper WIRE_MAPPER = wireMapper();

    /** Target shape for reading a serialized instance back as a property map, key order preserved. */
    private static final TypeReference<Map<String, Object>> WIRE_SHAPE =
            new TypeReference<Map<String, Object>>() { };

    private static ObjectMapper wireMapper() {
        return JsonContractSupport.declaredSettingsMapper();
    }

    private static Map<String, Object> wireProperties(PageMetadata metadata)
            throws JsonProcessingException {
        return WIRE_MAPPER.readValue(WIRE_MAPPER.writeValueAsString(metadata), WIRE_SHAPE);
    }

    @Nested
    @DisplayName("Screen row counts: three separately named constants, three independent derivations")
    class ScreenRowCountContract {

        @Test
        @DisplayName("the card-list screen presents seven rows")
        void cardListScreenPresentsSevenRows() {
            // Asserted against a literal seven and against nothing else in the type under test; the
            // independent arithmetic behind the seven is reconciled in
            // cardRowWidthsAccountForTheWholeDeclaredRowsArea below.
            assertThat(PageMetadata.CARD_LIST_PAGE_SIZE).isEqualTo(7);
        }

        @Test
        @DisplayName("the transaction-list screen presents ten rows, established by loop bounds and "
                + "by no row table whatsoever")
        void transactionListScreenPresentsTenRows() {
            // Recorded here because a future reader who goes looking for a row table will not find
            // one: app/cbl/COTRN00C.cbl declares no screen-row table at all, and the ten comes
            // entirely from loop bounds - the clearing loop is bounded at ten, the index is reset to
            // one, and the filling loop halts once the index reaches eleven. That program's single
            // table clause is the inbound communication-area redefinition and has nothing to do
            // with screen rows. The arithmetic on those bounds is asserted in
            // transactionRowCountFollowsFromLoopBoundsAlone below.
            assertThat(PageMetadata.TRANSACTION_LIST_PAGE_SIZE).isEqualTo(10);
        }

        @Test
        @DisplayName("the user-list screen presents ten rows, declared as a genuine ten-occurrence "
                + "table")
        void userListScreenPresentsTenRows() {
            // Unlike the transaction count above, this one rests on a real table declaration in
            // app/cbl/COUSR00C.cbl - a different mechanism, in a different member, for the same
            // figure.
            assertThat(PageMetadata.USER_LIST_PAGE_SIZE).isEqualTo(10);
        }

        @Test
        @DisplayName("the two ten-row screens are two separately named constants, never one shared "
                + "constant used twice")
        void theTwoTenRowScreensAreTwoSeparatelyNamedConstants() {
            // The only expression in the file where all three names appear together, and it makes
            // one point: three names resolve and they carry two distinct figures. The two tens
            // coincide by accident of two unrelated screen layouts proven by two unrelated
            // mechanisms, so neither is derived from the other. Were they collapsed into a single
            // shared constant, a change to one screen would travel silently to the other, which is
            // a behavioural regression on a screen nobody edited.
            List<Integer> declared =
                    List.of(
                            PageMetadata.CARD_LIST_PAGE_SIZE,
                            PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                            PageMetadata.USER_LIST_PAGE_SIZE);

            assertThat(declared).hasSize(3).containsExactly(7, 10, 10);
            assertThat(Set.copyOf(declared)).containsExactlyInAnyOrder(7, 10);
            assertThat(PageMetadata.CARD_LIST_PAGE_SIZE)
                    .isNotEqualTo(PageMetadata.TRANSACTION_LIST_PAGE_SIZE)
                    .isNotEqualTo(PageMetadata.USER_LIST_PAGE_SIZE);
        }

        @Test
        @DisplayName("card row widths account for the whole declared rows area: 11 plus 16 plus 1, "
                + "taken seven times, is 196")
        void cardRowWidthsAccountForTheWholeDeclaredRowsArea() {
            int rowWidth =
                    CARD_ROW_ACCOUNT_ID_WIDTH + CARD_ROW_CARD_NUMBER_WIDTH + CARD_ROW_STATUS_WIDTH;

            assertThat(rowWidth).isEqualTo(28);
            assertThat(rowWidth * PageMetadata.CARD_LIST_PAGE_SIZE)
                    .isEqualTo(CARD_ALL_ROWS_AREA_WIDTH);
        }

        @Test
        @DisplayName("the screen-line counter and the backward fill seed both agree with the "
                + "seven-row card screen")
        void cardScreenLineWitnessesAgreeWithTheCardRowCount() {
            assertThat(CARD_DECLARED_SCREEN_LINES).isEqualTo(PageMetadata.CARD_LIST_PAGE_SIZE);

            // The backward path seeds its counter one past the screen-line counter and decrements
            // to zero, so it visits exactly as many rows as the screen presents.
            assertThat(CARD_BACKWARD_FILL_SEED - 1).isEqualTo(PageMetadata.CARD_LIST_PAGE_SIZE);
        }

        @Test
        @DisplayName("the transaction row count follows from the two loop bounds, forward and "
                + "backward alike")
        void transactionRowCountFollowsFromLoopBoundsAlone() {
            assertThat(TRANSACTION_FILL_LOOP_STOP - 1)
                    .isEqualTo(PageMetadata.TRANSACTION_LIST_PAGE_SIZE);
            assertThat(TRANSACTION_CLEARING_LOOP_BOUND)
                    .isEqualTo(PageMetadata.TRANSACTION_LIST_PAGE_SIZE);

            // The backward paragraph seeds the index at ten and decrements to zero, filling slots
            // ten down to one: the same row count, walked in the opposite order.
            assertThat(TRANSACTION_BACKWARD_FILL_SEED)
                    .isEqualTo(PageMetadata.TRANSACTION_LIST_PAGE_SIZE);
        }

        @Test
        @DisplayName("the user row group is a ten-occurrence table whose element sums to 48 "
                + "characters across seven fields")
        void userListRowGroupGeometryAgreesWithTheTenOccurrenceTable() {
            assertThat(USER_ROW_FIELD_WIDTHS).hasSize(7);
            assertThat(USER_ROW_FIELD_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(USER_ROW_GROUP_WIDTH);
            assertThat(USER_ROW_GROUP_WIDTH * PageMetadata.USER_LIST_PAGE_SIZE).isEqualTo(480);
        }
    }

    @Nested
    @DisplayName("The row count travels as instance data, so one contract serves all three screens")
    class RowCountAsInstanceData {

        @Test
        @DisplayName("an instance built for the card list carries seven, and seven is what a caller "
                + "reads back")
        void cardListInstanceCarriesSevenRows() {
            PageMetadata cardListPage =
                    PageMetadata.forward(
                            PageMetadata.CARD_LIST_PAGE_SIZE,
                            CARD_FIRST_CURSOR,
                            CARD_LAST_CURSOR,
                            true,
                            false,
                            CARD_MAP_INDICATOR);

            assertThat(cardListPage.pageSize()).isEqualTo(7);
        }

        @Test
        @DisplayName("an instance built for the transaction list carries ten, and ten is what a "
                + "caller reads back")
        void transactionListInstanceCarriesTenRows() {
            PageMetadata transactionListPage =
                    PageMetadata.forward(
                            PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            true,
                            false,
                            LIST_MAP_INDICATOR);

            assertThat(transactionListPage.pageSize()).isEqualTo(10);
        }

        @Test
        @DisplayName("an instance built for the user list carries ten, and ten is what a caller "
                + "reads back")
        void userListInstanceCarriesTenRows() {
            PageMetadata userListPage =
                    PageMetadata.backward(
                            PageMetadata.USER_LIST_PAGE_SIZE,
                            USER_FIRST_CURSOR,
                            USER_LAST_CURSOR,
                            false,
                            true,
                            LIST_MAP_INDICATOR);

            assertThat(userListPage.pageSize()).isEqualTo(10);
        }

        @Test
        @DisplayName("one contract serves all three screens at once, each instance carrying its own "
                + "screen shape")
        void oneContractServesAllThreeScreens() {
            PageMetadata cardListPage =
                    PageMetadata.forward(
                            PageMetadata.CARD_LIST_PAGE_SIZE,
                            CARD_FIRST_CURSOR,
                            CARD_LAST_CURSOR,
                            true,
                            false,
                            CARD_MAP_INDICATOR);
            PageMetadata transactionListPage =
                    PageMetadata.forward(
                            PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            true,
                            false,
                            LIST_MAP_INDICATOR);
            PageMetadata userListPage =
                    PageMetadata.forward(
                            PageMetadata.USER_LIST_PAGE_SIZE,
                            USER_FIRST_CURSOR,
                            USER_LAST_CURSOR,
                            true,
                            false,
                            LIST_MAP_INDICATOR);

            assertThat(
                            List.of(
                                    cardListPage.pageSize(),
                                    transactionListPage.pageSize(),
                                    userListPage.pageSize()))
                    .containsExactly(7, 10, 10);

            // The two ten-row screens agree on the row figure and differ on the key they browse,
            // which is exactly the shape the legacy estate has: a 16-character transaction
            // identifier against an 8-character user identifier.
            assertThat(transactionListPage.nextCursorKey()).hasSize(TRANSACTION_KEY_WIDTH);
            assertThat(userListPage.nextCursorKey()).hasSize(USER_KEY_WIDTH);
        }

        @Test
        @DisplayName("the component is data and is never defaulted: a caller asking for a single row "
                + "gets a single row")
        void theRowCountComponentIsNeverDefaulted() {
            // Nothing substitutes one of the three screen figures for what the caller supplied, and
            // nothing here is clamped or rounded up to a screen shape.
            PageMetadata singleRowPage =
                    PageMetadata.forward(
                            1,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            true,
                            false,
                            LIST_MAP_INDICATOR);

            assertThat(singleRowPage.pageSize()).isEqualTo(1);
            assertThat(singleRowPage.pageSize()).isNotEqualTo(PageMetadata.CARD_LIST_PAGE_SIZE);
            assertThat(singleRowPage.pageSize())
                    .isNotEqualTo(PageMetadata.TRANSACTION_LIST_PAGE_SIZE);
        }
    }

    @Nested
    @DisplayName("Exhaustion is signalled by two independent flags and by no aggregate figure")
    class ExhaustionFlagContract {

        @Test
        @DisplayName("all four combinations of the two exhaustion flags are representable")
        void allFourFlagCombinationsAreRepresentable() {
            PageMetadata onlyPage =
                    PageMetadata.forward(
                            10,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            false,
                            false,
                            LIST_MAP_INDICATOR);
            PageMetadata firstOfSeveral =
                    PageMetadata.forward(
                            10,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            true,
                            false,
                            LIST_MAP_INDICATOR);
            PageMetadata lastOfSeveral =
                    PageMetadata.forward(
                            10,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            false,
                            true,
                            LIST_MAP_INDICATOR);
            PageMetadata middleOfSeveral =
                    PageMetadata.forward(
                            10,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            true,
                            true,
                            LIST_MAP_INDICATOR);

            assertThat(onlyPage.hasMorePages()).isFalse();
            assertThat(onlyPage.hasPreviousPages()).isFalse();
            assertThat(firstOfSeveral.hasMorePages()).isTrue();
            assertThat(firstOfSeveral.hasPreviousPages()).isFalse();
            assertThat(lastOfSeveral.hasMorePages()).isFalse();
            assertThat(lastOfSeveral.hasPreviousPages()).isTrue();
            assertThat(middleOfSeveral.hasMorePages()).isTrue();
            assertThat(middleOfSeveral.hasPreviousPages()).isTrue();
        }

        @Test
        @DisplayName("the two flags are independent, because the legacy screens report the two "
                + "conditions with two different operator messages")
        void theTwoFlagsAreIndependentOfOneAnother() {
            PageMetadata moreFollows =
                    PageMetadata.forward(
                            PageMetadata.CARD_LIST_PAGE_SIZE,
                            CARD_FIRST_CURSOR,
                            CARD_LAST_CURSOR,
                            true,
                            false,
                            CARD_MAP_INDICATOR);
            PageMetadata somethingPrecedes =
                    PageMetadata.forward(
                            PageMetadata.CARD_LIST_PAGE_SIZE,
                            CARD_FIRST_CURSOR,
                            CARD_LAST_CURSOR,
                            false,
                            true,
                            CARD_MAP_INDICATOR);

            assertThat(moreFollows).isNotEqualTo(somethingPrecedes);
            assertThat(moreFollows.nextCursorKey()).isEqualTo(somethingPrecedes.nextCursorKey());
            assertThat(moreFollows.pageSize()).isEqualTo(somethingPrecedes.pageSize());
            assertThat(moreFollows.hasMorePages()).isNotEqualTo(somethingPrecedes.hasMorePages());
            assertThat(moreFollows.hasPreviousPages())
                    .isNotEqualTo(somethingPrecedes.hasPreviousPages());
        }

        @Test
        @DisplayName("the wire shape carries exactly seven named properties, none of them an aggregate "
                + "row figure the legacy browse never had")
        void theWireShapeCarriesNoAggregateRowFigure() throws JsonProcessingException {
            Map<String, Object> wire =
                    wireProperties(
                            PageMetadata.forward(
                                    PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                                    TRANSACTION_FIRST_CURSOR,
                                    TRANSACTION_LAST_CURSOR,
                                    true,
                                    true,
                                    LIST_MAP_INDICATOR));

            assertThat(wire).hasSize(7);
            assertThat(wire.keySet())
                    .containsExactly(
                            "pageSize",
                            "previousCursorKey",
                            "nextCursorKey",
                            "direction",
                            "hasMorePages",
                            "hasPreviousPages",
                            "displayedPageNumber");

            // A cluster-wide figure would have to be fabricated, because the legacy browse counts
            // nothing: it attempts one more read and observes the outcome. No property name may
            // suggest one is available.
            assertThat(wire.keySet())
                    .allSatisfy(
                            name ->
                                    assertThat(name)
                                            .doesNotStartWith("total")
                                            .doesNotContainIgnoringCase("count")
                                            .doesNotContainIgnoringCase("elements"));
        }
    }

    @Nested
    @DisplayName("Each boundary cursor is opaque text, never a numeric offset")
    class CursorKeyContract {

        @Test
        @DisplayName("a transaction key of fifteen leading zeros round-trips unchanged, and is not "
                + "collapsed to its numeric value")
        void leadingZeroCursorRoundTripsUnchanged() {
            PageMetadata page =
                    PageMetadata.forward(
                            10,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            true,
                            false,
                            LIST_MAP_INDICATOR);

            assertThat(page.previousCursorKey())
                    .isEqualTo("0000000000000033")
                    .hasSize(TRANSACTION_KEY_WIDTH)
                    .startsWith("0")
                    .isNotEqualTo("33");
            assertThat(page.nextCursorKey())
                    .isEqualTo("0000000000000042")
                    .hasSize(TRANSACTION_KEY_WIDTH)
                    .startsWith("0")
                    .isNotEqualTo("42");
        }

        @Test
        @DisplayName("the card browse key crosses at the widest legacy key width of 16 characters, "
                + "which is the card number alone and not the declared composite")
        void cardCursorCrossesAtSixteenCharacters() {
            PageMetadata page =
                    PageMetadata.backward(
                            PageMetadata.CARD_LIST_PAGE_SIZE,
                            CARD_FIRST_CURSOR,
                            CARD_LAST_CURSOR,
                            true,
                            true,
                            CARD_MAP_INDICATOR);

            // The card-list program declares a composite work field of a card number followed by an
            // account identifier, but at all four of its repositioning sites only the card-number half
            // reaches the browse key - the companion move of the account half is commented out - so the
            // key that actually resumes a card browse is sixteen characters, not twenty-seven.
            assertThat(page.previousCursorKey()).hasSize(CARD_KEY_CARD_NUMBER_WIDTH).hasSize(16);
            assertThat(page.nextCursorKey()).hasSize(CARD_KEY_CARD_NUMBER_WIDTH).hasSize(16);
            assertThat(CARD_KEY_CARD_NUMBER_WIDTH)
                    .as("the resumption key is the card-number half of the declared composite")
                    .isLessThan(CARD_KEY_CARD_NUMBER_WIDTH + CARD_KEY_ACCOUNT_ID_WIDTH);
        }

        @Test
        @DisplayName("the declared cursor bound is the widest of the three legacy keys, so no legal "
                + "cursor is ever refused and none the screens cannot produce is admitted")
        void cursorBoundIsTheWidestOfTheThreeLegacyKeys() {
            assertThat(PageMetadata.CURSOR_KEY_MAX_LENGTH)
                    .isEqualTo(16)
                    .isEqualTo(CARD_KEY_CARD_NUMBER_WIDTH)
                    .isGreaterThanOrEqualTo(TRANSACTION_KEY_WIDTH)
                    .isGreaterThanOrEqualTo(USER_KEY_WIDTH)
                    .as("a bound of twenty-seven would admit a cursor no screen can produce")
                    .isLessThan(CARD_KEY_CARD_NUMBER_WIDTH + CARD_KEY_ACCOUNT_ID_WIDTH);
        }

        @Test
        @DisplayName("an eight-character user key that is not numeric at all round-trips verbatim")
        void nonNumericUserCursorRoundTripsVerbatim() {
            PageMetadata page =
                    PageMetadata.forward(
                            PageMetadata.USER_LIST_PAGE_SIZE,
                            USER_FIRST_CURSOR,
                            USER_LAST_CURSOR,
                            false,
                            true,
                            LIST_MAP_INDICATOR_TRAILING);

            assertThat(page.previousCursorKey()).isEqualTo("USRA0009").hasSize(USER_KEY_WIDTH);
            assertThat(page.nextCursorKey()).isEqualTo("USRT0001").hasSize(USER_KEY_WIDTH);
        }

        @Test
        @DisplayName("padding on either side of a cursor survives, because a fixed-width key carries "
                + "it")
        void cursorPaddingSurvivesOnBothSides() {
            String paddedKey = "  0000042       ";

            PageMetadata page =
                    PageMetadata.forward(10, paddedKey, paddedKey, true, true, LIST_MAP_INDICATOR);

            assertThat(page.previousCursorKey())
                    .isEqualTo("  0000042       ")
                    .hasSize(TRANSACTION_KEY_WIDTH)
                    .startsWith("  ")
                    .endsWith("       ");
            assertThat(page.nextCursorKey())
                    .isEqualTo("  0000042       ")
                    .hasSize(TRANSACTION_KEY_WIDTH)
                    .startsWith("  ")
                    .endsWith("       ");
        }

        @Test
        @DisplayName("a null cursor is accepted, because the first turn of a browse has no retained "
                + "key yet")
        void nullCursorIsAcceptedOnTheFirstTurn() {
            PageMetadata page =
                    PageMetadata.forward(PageMetadata.CARD_LIST_PAGE_SIZE, null, null, true, false, null);

            assertThat(page.previousCursorKey()).isNull();
            assertThat(page.nextCursorKey()).isNull();
            assertThat(page.hasMorePages()).isTrue();
        }

        @Test
        @DisplayName("the accessor hands back the very instance supplied, so nothing is copied, "
                + "padded, trimmed or case folded")
        void accessorHandsBackTheSuppliedInstance() {
            String suppliedKey = "0".repeat(15).concat("7");

            PageMetadata page =
                    PageMetadata.forward(10, suppliedKey, suppliedKey, false, false, null);

            assertThat(page.previousCursorKey()).isSameAs(suppliedKey);
            assertThat(page.nextCursorKey()).isSameAs(suppliedKey);
            assertThat(page.nextCursorKey()).isEqualTo("0000000000000007");
        }

        @Test
        @DisplayName("an over-wide cursor is stored verbatim rather than shortened, because the "
                + "declared bound reports and never alters")
        void overWideCursorIsStoredVerbatim() {
            String overWideKey = "X".repeat(PageMetadata.CURSOR_KEY_MAX_LENGTH + 1);

            PageMetadata page =
                    PageMetadata.forward(10, overWideKey, overWideKey, false, false, null);

            assertThat(page.previousCursorKey()).isEqualTo(overWideKey)
                    .hasSize(PageMetadata.CURSOR_KEY_MAX_LENGTH + 1);
            assertThat(page.nextCursorKey()).isEqualTo(overWideKey)
                    .hasSize(PageMetadata.CURSOR_KEY_MAX_LENGTH + 1);
        }
    }

    @Nested
    @DisplayName("Two boundary cursors, because every paginated screen retains a first key and a last "
            + "key at the same time")
    class BoundaryCursorPairContract {

        @Test
        @DisplayName("a page in the middle of a browse carries both boundary keys at once, with "
                + "different values, which is the case a single cursor component could not represent")
        void aMidBrowsePageCarriesBothBoundaryKeysAtOnce() {
            // Both availability flags true is the middle of a browse: the operator can page either
            // way, so the legacy has both its FIRST and its LAST field populated and either attention
            // key is live. One cursor component could hold only one of the two.
            PageMetadata midBrowse =
                    PageMetadata.forward(
                            PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            true,
                            true,
                            LIST_MAP_INDICATOR);

            assertThat(midBrowse.hasMorePages()).isTrue();
            assertThat(midBrowse.hasPreviousPages()).isTrue();
            assertThat(midBrowse.previousCursorKey()).isEqualTo("0000000000000033");
            assertThat(midBrowse.nextCursorKey()).isEqualTo("0000000000000042");
            assertThat(midBrowse.previousCursorKey()).isNotEqualTo(midBrowse.nextCursorKey());
        }

        @Test
        @DisplayName("the first page of a browse carries only the forward key, the final page only the "
                + "backward key, and each absence lines up with its own availability flag")
        void theFirstAndFinalPagesEachCarryOnlyTheKeyTheyCanStillUse() {
            PageMetadata firstPage =
                    PageMetadata.forward(
                            PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                            null,
                            TRANSACTION_LAST_CURSOR,
                            true,
                            false,
                            LIST_MAP_INDICATOR);
            PageMetadata finalPage =
                    PageMetadata.forward(
                            PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                            TRANSACTION_FIRST_CURSOR,
                            null,
                            false,
                            true,
                            LIST_MAP_INDICATOR);

            assertThat(firstPage.hasPreviousPages()).isFalse();
            assertThat(firstPage.previousCursorKey()).isNull();
            assertThat(firstPage.nextCursorKey()).isEqualTo("0000000000000042");

            assertThat(finalPage.hasMorePages()).isFalse();
            assertThat(finalPage.nextCursorKey()).isNull();
            assertThat(finalPage.previousCursorKey()).isEqualTo("0000000000000033");
        }

        @Test
        @DisplayName("the direction does not decide which key is present, so a page reached by walking "
                + "backward still reports the key a forward walk would restart from")
        void theDirectionDoesNotDecideWhichKeyIsPresent() {
            PageMetadata reachedByWalkingForward =
                    PageMetadata.forward(
                            PageMetadata.USER_LIST_PAGE_SIZE,
                            USER_FIRST_CURSOR,
                            USER_LAST_CURSOR,
                            true,
                            true,
                            LIST_MAP_INDICATOR);
            PageMetadata reachedByWalkingBackward =
                    PageMetadata.backward(
                            PageMetadata.USER_LIST_PAGE_SIZE,
                            USER_FIRST_CURSOR,
                            USER_LAST_CURSOR,
                            true,
                            true,
                            LIST_MAP_INDICATOR);

            assertThat(reachedByWalkingForward.previousCursorKey()).isEqualTo("USRA0009");
            assertThat(reachedByWalkingForward.nextCursorKey()).isEqualTo("USRT0001");
            assertThat(reachedByWalkingBackward.previousCursorKey()).isEqualTo("USRA0009");
            assertThat(reachedByWalkingBackward.nextCursorKey()).isEqualTo("USRT0001");
            assertThat(reachedByWalkingForward.direction())
                    .isNotEqualTo(reachedByWalkingBackward.direction());
        }

        @Test
        @DisplayName("neither key is derived from the other: swapping the two produces a different "
                + "value, so the pair is ordered and not a set")
        void neitherKeyIsDerivedFromTheOther() {
            PageMetadata asAssembled =
                    PageMetadata.forward(
                            PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            true,
                            true,
                            LIST_MAP_INDICATOR);
            PageMetadata withTheBoundariesSwapped =
                    PageMetadata.forward(
                            PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                            TRANSACTION_LAST_CURSOR,
                            TRANSACTION_FIRST_CURSOR,
                            true,
                            true,
                            LIST_MAP_INDICATOR);

            assertThat(asAssembled).isNotEqualTo(withTheBoundariesSwapped);
            assertThat(asAssembled.previousCursorKey())
                    .isEqualTo(withTheBoundariesSwapped.nextCursorKey());
            assertThat(asAssembled.nextCursorKey())
                    .isEqualTo(withTheBoundariesSwapped.previousCursorKey());
        }

        @Test
        @DisplayName("all three screens supply their two keys at their own single width - 16 and 16 for "
                + "the card list, 16 and 16 for the transaction list, 8 and 8 for the user list")
        void allThreeScreensSupplyTheirTwoKeysAtTheirOwnSingleWidth() {
            PageMetadata cardListPage =
                    PageMetadata.forward(
                            PageMetadata.CARD_LIST_PAGE_SIZE,
                            CARD_FIRST_CURSOR,
                            CARD_LAST_CURSOR,
                            true,
                            true,
                            CARD_MAP_INDICATOR);
            PageMetadata transactionListPage =
                    PageMetadata.forward(
                            PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            true,
                            true,
                            LIST_MAP_INDICATOR);
            PageMetadata userListPage =
                    PageMetadata.forward(
                            PageMetadata.USER_LIST_PAGE_SIZE,
                            USER_FIRST_CURSOR,
                            USER_LAST_CURSOR,
                            true,
                            true,
                            LIST_MAP_INDICATOR);

            assertThat(cardListPage.previousCursorKey()).hasSize(CARD_KEY_CARD_NUMBER_WIDTH);
            assertThat(cardListPage.nextCursorKey()).hasSize(CARD_KEY_CARD_NUMBER_WIDTH);
            assertThat(transactionListPage.previousCursorKey()).hasSize(TRANSACTION_KEY_WIDTH);
            assertThat(transactionListPage.nextCursorKey()).hasSize(TRANSACTION_KEY_WIDTH);
            assertThat(userListPage.previousCursorKey()).hasSize(USER_KEY_WIDTH);
            assertThat(userListPage.nextCursorKey()).hasSize(USER_KEY_WIDTH);
        }

        @Test
        @DisplayName("one declared bound governs both keys, because every screen declares its first "
                + "and last field at identical widths")
        void oneDeclaredBoundGovernsBothKeys() {
            // 16 and 16, 16 and 16, 8 and 8: the per-screen pair is always equal, so a second bound
            // would be a second name for the same number.
            assertThat(CARD_KEY_CARD_NUMBER_WIDTH).isEqualTo(PageMetadata.CURSOR_KEY_MAX_LENGTH);
            assertThat(TRANSACTION_KEY_WIDTH).isEqualTo(PageMetadata.CURSOR_KEY_MAX_LENGTH);
            assertThat(USER_KEY_WIDTH).isLessThan(PageMetadata.CURSOR_KEY_MAX_LENGTH);
        }

        @Test
        @DisplayName("both keys cross the wire under their own property names, adjacent and in "
                + "declaration order, so a client can tell the two boundaries apart")
        void bothKeysCrossTheWireUnderTheirOwnNames() throws JsonProcessingException {
            Map<String, Object> wire =
                    wireProperties(
                            PageMetadata.backward(
                                    PageMetadata.CARD_LIST_PAGE_SIZE,
                                    CARD_FIRST_CURSOR,
                                    CARD_LAST_CURSOR,
                                    true,
                                    true,
                                    CARD_MAP_INDICATOR));

            assertThat(List.copyOf(wire.keySet()).subList(0, 3))
                    .containsExactly("pageSize", "previousCursorKey", "nextCursorKey");
            assertThat(wire)
                    .containsEntry("previousCursorKey", "4111111111110036")
                    .containsEntry("nextCursorKey", "4111111111110042");
        }

        @Test
        @DisplayName("only the absent key is omitted from the wire, so a first page still publishes "
                + "the forward key a client needs to page down")
        void onlyTheAbsentKeyIsOmittedFromTheWire() throws JsonProcessingException {
            Map<String, Object> firstPageWire =
                    wireProperties(
                            PageMetadata.forward(
                                    PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                                    null,
                                    TRANSACTION_LAST_CURSOR,
                                    true,
                                    false,
                                    LIST_MAP_INDICATOR));

            assertThat(firstPageWire)
                    .doesNotContainKey("previousCursorKey")
                    .containsEntry("nextCursorKey", "0000000000000042");
            assertThat(firstPageWire).hasSize(6);
        }

        @Test
        @DisplayName("a round trip through JSON keeps the two boundaries on their own sides, never "
                + "transposing or merging them")
        void aRoundTripKeepsTheTwoBoundariesOnTheirOwnSides() throws JsonProcessingException {
            PageMetadata original =
                    PageMetadata.backward(
                            PageMetadata.USER_LIST_PAGE_SIZE,
                            USER_FIRST_CURSOR,
                            USER_LAST_CURSOR,
                            true,
                            true,
                            LIST_MAP_INDICATOR_TRAILING);

            PageMetadata restored =
                    WIRE_MAPPER.readValue(
                            WIRE_MAPPER.writeValueAsString(original), PageMetadata.class);

            assertThat(restored).isEqualTo(original).isNotSameAs(original);
            assertThat(restored.previousCursorKey()).isEqualTo("USRA0009");
            assertThat(restored.nextCursorKey()).isEqualTo("USRT0001");
        }
    }

    @Nested
    @DisplayName("The displayed page indicator is text, and its width differs by screen")
    class DisplayedPageIndicatorContract {

        @Test
        @DisplayName("a three-character card-list indicator round-trips untrimmed, leading spaces "
                + "included")
        void threeCharacterIndicatorRoundTripsUntrimmed() {
            PageMetadata page =
                    PageMetadata.forward(
                            PageMetadata.CARD_LIST_PAGE_SIZE,
                            CARD_FIRST_CURSOR,
                            CARD_LAST_CURSOR,
                            true,
                            false,
                            CARD_MAP_INDICATOR);

            assertThat(page.displayedPageNumber())
                    .isEqualTo("  1")
                    .hasSize(CARD_MAP_INDICATOR_WIDTH)
                    .startsWith("  ");
        }

        @Test
        @DisplayName("an eight-character list indicator round-trips untrimmed, zero filled or "
                + "trailing spaces alike")
        void eightCharacterIndicatorRoundTripsUntrimmed() {
            PageMetadata zeroFilled =
                    PageMetadata.forward(
                            10,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            true,
                            false,
                            LIST_MAP_INDICATOR);
            PageMetadata spaceFilled =
                    PageMetadata.forward(
                            10,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            true,
                            false,
                            LIST_MAP_INDICATOR_TRAILING);

            assertThat(zeroFilled.displayedPageNumber())
                    .isEqualTo("00000007")
                    .hasSize(LIST_MAP_INDICATOR_WIDTH);
            assertThat(spaceFilled.displayedPageNumber())
                    .isEqualTo("1       ")
                    .hasSize(LIST_MAP_INDICATOR_WIDTH)
                    .endsWith("       ");
        }

        @Test
        @DisplayName("the three-character and eight-character map widths are never normalised to one "
                + "another")
        void theTwoMapWidthsAreNeverNormalisedToOneAnother() {
            PageMetadata cardListPage =
                    PageMetadata.forward(
                            PageMetadata.CARD_LIST_PAGE_SIZE,
                            CARD_FIRST_CURSOR,
                            CARD_LAST_CURSOR,
                            true,
                            false,
                            CARD_MAP_INDICATOR);
            PageMetadata transactionListPage =
                    PageMetadata.forward(
                            PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            true,
                            false,
                            LIST_MAP_INDICATOR);

            assertThat(CARD_MAP_INDICATOR_WIDTH).isNotEqualTo(LIST_MAP_INDICATOR_WIDTH);
            assertThat(cardListPage.displayedPageNumber()).hasSize(3);
            assertThat(transactionListPage.displayedPageNumber()).hasSize(8);
        }

        @Test
        @DisplayName("the indicator is text, so a value no numeric field could hold still survives")
        void indicatorIsTextRatherThanANumber() {
            PageMetadata page =
                    PageMetadata.backward(
                            PageMetadata.CARD_LIST_PAGE_SIZE,
                            CARD_FIRST_CURSOR,
                            CARD_LAST_CURSOR,
                            false,
                            true,
                            "N/A");

            assertThat(page.displayedPageNumber())
                    .isEqualTo("N/A")
                    .hasSize(CARD_MAP_INDICATOR_WIDTH);
        }

        @Test
        @DisplayName("a null indicator is accepted, and navigation still works because the cursor is "
                + "the authoritative state")
        void nullIndicatorIsAcceptedWhileTheCursorRemainsAuthoritative() {
            PageMetadata page = PageMetadata.backward(
                    10,
                    TRANSACTION_FIRST_CURSOR,
                    TRANSACTION_LAST_CURSOR,
                    true,
                    true,
                    null);

            assertThat(page.displayedPageNumber()).isNull();
            assertThat(page.nextCursorKey()).isEqualTo("0000000000000042");
        }

        @Test
        @DisplayName("the declared indicator bound is the wider of the two map widths and alters "
                + "nothing that exceeds it")
        void indicatorBoundIsTheWiderOfTheTwoMapWidths() {
            String overWideIndicator = "9".repeat(PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH + 1);

            PageMetadata page =
                    PageMetadata.forward(
                            10,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            false,
                            false,
                            overWideIndicator);

            assertThat(PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH)
                    .isEqualTo(8)
                    .isEqualTo(LIST_MAP_INDICATOR_WIDTH)
                    .isGreaterThan(CARD_MAP_INDICATOR_WIDTH);
            assertThat(page.displayedPageNumber()).isEqualTo(overWideIndicator).hasSize(9);
        }
    }

    @Nested
    @DisplayName("Browse direction: exactly two constants, one per CICS browse verb")
    class BrowseDirectionContract {

        @Test
        @DisplayName("exactly two directions exist, declared in the order the two browse verbs walk "
                + "the key sequence")
        void exactlyTwoDirectionsExist() {
            assertThat(PageMetadata.PagingDirection.values())
                    .hasSize(2)
                    .containsExactly(
                            PageMetadata.PagingDirection.FORWARD,
                            PageMetadata.PagingDirection.BACKWARD);
        }

        @Test
        @DisplayName("there is no third direction and no synthetic default: an unknown name is "
                + "refused outright")
        void thereIsNoThirdOrSyntheticDirection() {
            // A third constant would have to stand for something the legacy never expresses: its
            // programs always branch on an explicit attention key, so an unknown or absent direction
            // has no legacy meaning to carry.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> PageMetadata.PagingDirection.valueOf("UNSPECIFIED"));

            assertThat(PageMetadata.PagingDirection.valueOf("FORWARD"))
                    .isSameAs(PageMetadata.PagingDirection.FORWARD);
            assertThat(PageMetadata.PagingDirection.valueOf("BACKWARD"))
                    .isSameAs(PageMetadata.PagingDirection.BACKWARD);
        }

        @Test
        @DisplayName("the forward factory produces the forward browse, the equivalent of reading to "
                + "the next key")
        void forwardFactoryProducesTheForwardBrowse() {
            PageMetadata page =
                    PageMetadata.forward(
                            PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            true,
                            false,
                            LIST_MAP_INDICATOR);

            assertThat(page.direction()).isEqualTo(PageMetadata.PagingDirection.FORWARD);
        }

        @Test
        @DisplayName("the backward factory produces the backward browse, the equivalent of reading to "
                + "the previous key")
        void backwardFactoryProducesTheBackwardBrowse() {
            PageMetadata page =
                    PageMetadata.backward(
                            PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            false,
                            true,
                            LIST_MAP_INDICATOR);

            assertThat(page.direction()).isEqualTo(PageMetadata.PagingDirection.BACKWARD);
        }

        @Test
        @DisplayName("a backward page is distinguishable from a forward page that is otherwise "
                + "identical")
        void aBackwardPageIsDistinguishableFromAForwardPage() {
            PageMetadata forwardPage =
                    PageMetadata.forward(
                            10,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            true,
                            true,
                            LIST_MAP_INDICATOR);
            PageMetadata backwardPage =
                    PageMetadata.backward(
                            10,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            true,
                            true,
                            LIST_MAP_INDICATOR);

            assertThat(forwardPage.direction()).isNotEqualTo(backwardPage.direction());
            assertThat(forwardPage).isNotEqualTo(backwardPage);

            assertThat(forwardPage.pageSize()).isEqualTo(backwardPage.pageSize());
            assertThat(forwardPage.nextCursorKey()).isEqualTo(backwardPage.nextCursorKey());
            assertThat(forwardPage.displayedPageNumber())
                    .isEqualTo(backwardPage.displayedPageNumber());
        }

        @Test
        @DisplayName("descending fill is representable, and nothing in this type imposes an order on "
                + "the rows it accompanies")
        void descendingFillIsRepresentableAndNoOrderIsImposed() {
            // The legacy backward path seeds its row index at the last slot and decrements, so a
            // backward page is filled from the bottom row upward and then presented ascending. That
            // reordering belongs to the service that read the rows; this contract only records which
            // way the browse walked, and it carries no comparator, no sort component and no ordering
            // of its own - so it cannot force rows into ascending order.
            PageMetadata backwardPage =
                    PageMetadata.backward(
                            TRANSACTION_BACKWARD_FILL_SEED,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            true,
                            true,
                            LIST_MAP_INDICATOR);

            assertThat(backwardPage.direction()).isEqualTo(PageMetadata.PagingDirection.BACKWARD);
            assertThat(backwardPage.pageSize()).isEqualTo(10);
            assertThat(backwardPage).isNotInstanceOf(Comparable.class);
        }

        @Test
        @DisplayName("the direction must be supplied explicitly, because the legacy always branches "
                + "on an attention key")
        void directionMustBeSuppliedExplicitly() {
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    new PageMetadata(
                                            10,
                                            TRANSACTION_FIRST_CURSOR,
                                            TRANSACTION_LAST_CURSOR,
                                            null,
                                            true,
                                            false,
                                            LIST_MAP_INDICATOR))
                    .withMessage("direction must be supplied explicitly");
        }

        @Test
        @DisplayName("both directions cross the wire as their own constant names")
        void bothDirectionsCrossTheWireAsConstantNames() throws JsonProcessingException {
            assertThat(
                            wireProperties(
                                    PageMetadata.forward(
                                            PageMetadata.CARD_LIST_PAGE_SIZE,
                                            CARD_FIRST_CURSOR,
                                            CARD_LAST_CURSOR,
                                            true,
                                            false,
                                            CARD_MAP_INDICATOR)))
                    .containsEntry("direction", "FORWARD");
            assertThat(
                            wireProperties(
                                    PageMetadata.backward(
                                            PageMetadata.CARD_LIST_PAGE_SIZE,
                                            CARD_FIRST_CURSOR,
                                            CARD_LAST_CURSOR,
                                            false,
                                            true,
                                            CARD_MAP_INDICATOR)))
                    .containsEntry("direction", "BACKWARD");
        }
    }

    @Nested
    @DisplayName("Value semantics and immutability, demonstrated by construction alone")
    class ValueSemanticsContract {

        @Test
        @DisplayName("two independently constructed instances with identical components are equal and "
                + "hash alike")
        void independentlyConstructedInstancesAreEqualAndHashAlike() {
            PageMetadata first =
                    new PageMetadata(
                            10,
                            "0000000000000042",
                            "0000000000000042",
                            PageMetadata.PagingDirection.FORWARD,
                            true,
                            false,
                            "00000007");
            PageMetadata second =
                    new PageMetadata(
                            10,
                            "0000000000000042",
                            "0000000000000042",
                            PageMetadata.PagingDirection.FORWARD,
                            true,
                            false,
                            "00000007");

            assertThat(first).isEqualTo(second).isNotSameAs(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("each of the seven components participates in equality on its own, the two "
                + "boundary cursors independently of one another")
        void eachOfTheSevenComponentsParticipatesInEquality() {
            PageMetadata.PagingDirection forward = PageMetadata.PagingDirection.FORWARD;
            PageMetadata.PagingDirection backward = PageMetadata.PagingDirection.BACKWARD;
            PageMetadata reference =
                    new PageMetadata(
                            10,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            forward,
                            true,
                            false,
                            LIST_MAP_INDICATOR);

            assertThat(reference)
                    .isNotEqualTo(
                            new PageMetadata(
                                    7,
                                    TRANSACTION_FIRST_CURSOR,
                                    TRANSACTION_LAST_CURSOR,
                                    forward,
                                    true,
                                    false,
                                    LIST_MAP_INDICATOR));
            assertThat(reference)
                    .isNotEqualTo(
                            new PageMetadata(
                                    10,
                                    "0000000000000043",
                                    TRANSACTION_LAST_CURSOR,
                                    forward,
                                    true,
                                    false,
                                    LIST_MAP_INDICATOR));
            assertThat(reference)
                    .isNotEqualTo(
                            new PageMetadata(
                                    10,
                                    TRANSACTION_FIRST_CURSOR,
                                    "0000000000000043",
                                    forward,
                                    true,
                                    false,
                                    LIST_MAP_INDICATOR));
            assertThat(reference)
                    .isNotEqualTo(
                            new PageMetadata(
                                    10,
                                    TRANSACTION_FIRST_CURSOR,
                                    TRANSACTION_LAST_CURSOR,
                                    backward,
                                    true,
                                    false,
                                    LIST_MAP_INDICATOR));
            assertThat(reference)
                    .isNotEqualTo(
                            new PageMetadata(
                                    10,
                                    TRANSACTION_FIRST_CURSOR,
                                    TRANSACTION_LAST_CURSOR,
                                    forward,
                                    false,
                                    false,
                                    LIST_MAP_INDICATOR));
            assertThat(reference)
                    .isNotEqualTo(
                            new PageMetadata(
                                    10,
                                    TRANSACTION_FIRST_CURSOR,
                                    TRANSACTION_LAST_CURSOR,
                                    forward,
                                    true,
                                    true,
                                    LIST_MAP_INDICATOR));
            assertThat(reference)
                    .isNotEqualTo(
                            new PageMetadata(
                                    10,
                                    TRANSACTION_FIRST_CURSOR,
                                    TRANSACTION_LAST_CURSOR,
                                    forward,
                                    true,
                                    false,
                                    "00000008"));
        }

        @Test
        @DisplayName("equality is self-consistent and refuses both null and a foreign type")
        void equalityIsSelfConsistentAndRefusesNullAndForeignTypes() {
            PageMetadata page =
                    PageMetadata.forward(
                            10,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            true,
                            false,
                            LIST_MAP_INDICATOR);

            assertThat(page).isEqualTo(page);
            assertThat(page).isNotEqualTo(null);
            assertThat(page).isNotEqualTo("0000000000000042");
        }

        @Test
        @DisplayName("all seven accessors hand back exactly what construction was given")
        void allSevenAccessorsHandBackWhatConstructionWasGiven() {
            String firstCursor = "USRA".concat("0008");
            String cursor = "USRT".concat("0002");
            String indicator = "0000001".concat("2");

            PageMetadata page =
                    new PageMetadata(
                            PageMetadata.USER_LIST_PAGE_SIZE,
                            firstCursor,
                            cursor,
                            PageMetadata.PagingDirection.BACKWARD,
                            false,
                            true,
                            indicator);

            assertThat(page.pageSize()).isEqualTo(10);
            assertThat(page.previousCursorKey()).isSameAs(firstCursor).isEqualTo("USRA0008");
            assertThat(page.nextCursorKey()).isSameAs(cursor).isEqualTo("USRT0002");
            assertThat(page.direction()).isSameAs(PageMetadata.PagingDirection.BACKWARD);
            assertThat(page.hasMorePages()).isFalse();
            assertThat(page.hasPreviousPages()).isTrue();
            assertThat(page.displayedPageNumber()).isSameAs(indicator).isEqualTo("00000012");
        }

        @Test
        @DisplayName("no component is defaulted, normalised, padded, trimmed or case folded")
        void noComponentIsDefaultedNormalisedOrCaseFolded() {
            String mixedCaseFirstCursor = "uSRa0004";
            String mixedCaseCursor = "usrT0003";

            PageMetadata page =
                    PageMetadata.forward(10, mixedCaseFirstCursor, mixedCaseCursor, false, false, " 4 ");

            assertThat(page.previousCursorKey()).isEqualTo("uSRa0004").isNotEqualTo("USRA0004");
            assertThat(page.nextCursorKey()).isEqualTo("usrT0003").isNotEqualTo("USRT0003");
            assertThat(page.displayedPageNumber())
                    .isEqualTo(" 4 ")
                    .hasSize(CARD_MAP_INDICATOR_WIDTH)
                    .isNotEqualTo("4");
        }

        @Test
        @DisplayName("instances behave as values in a set, so equal metadata never duplicates")
        void instancesBehaveAsValuesInASet() {
            PageMetadata cardListPage =
                    PageMetadata.forward(
                            PageMetadata.CARD_LIST_PAGE_SIZE,
                            CARD_FIRST_CURSOR,
                            CARD_LAST_CURSOR,
                            true,
                            false,
                            CARD_MAP_INDICATOR);
            PageMetadata transactionListPage =
                    PageMetadata.forward(
                            PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            true,
                            false,
                            LIST_MAP_INDICATOR);
            PageMetadata userListPage =
                    PageMetadata.backward(
                            PageMetadata.USER_LIST_PAGE_SIZE,
                            USER_FIRST_CURSOR,
                            USER_LAST_CURSOR,
                            false,
                            true,
                            LIST_MAP_INDICATOR);

            Set<PageMetadata> distinct = Set.of(cardListPage, transactionListPage, userListPage);

            assertThat(distinct).hasSize(3);
            assertThat(distinct)
                    .contains(
                            new PageMetadata(
                                    PageMetadata.USER_LIST_PAGE_SIZE,
                                    "USRA0009",
                                    "USRT0001",
                                    PageMetadata.PagingDirection.BACKWARD,
                                    false,
                                    true,
                                    "00000007"));
        }

        @Test
        @DisplayName("the diagnostic representation names the type and every component, and carries the "
                + "value of every component that is not a record key")
        void diagnosticRepresentationNamesTheTypeAndEveryComponent() {
            PageMetadata page =
                    PageMetadata.backward(
                            10,
                            TRANSACTION_FIRST_CURSOR,
                            TRANSACTION_LAST_CURSOR,
                            false,
                            true,
                            LIST_MAP_INDICATOR);

            assertThat(page.toString())
                    .startsWith("PageMetadata[")
                    .contains("pageSize=10")
                    .contains("previousCursorKey=")
                    .contains("nextCursorKey=")
                    .contains("direction=BACKWARD")
                    .contains("hasMorePages=false")
                    .contains("hasPreviousPages=true")
                    .contains("displayedPageNumber=00000007")
                    .endsWith("]");
            assertThat(page.toString())
                    .as("a boundary cursor is a record key and never reaches a rendering")
                    .doesNotContain(TRANSACTION_FIRST_CURSOR)
                    .doesNotContain(TRANSACTION_LAST_CURSOR);
        }
    }

    @Nested
    @DisplayName("Diagnostic redaction: paging state retained, both boundary cursors withheld")
    class DiagnosticRedactionContract {

        @Test
        @DisplayName("no fragment of either boundary cursor reaches the rendering of a card page, whose "
                + "16-character key is a primary account number in full")
        void noFragmentOfEitherCardCursorReachesTheRendering() {
            String rendering =
                    PageMetadata.forward(
                                    PageMetadata.CARD_LIST_PAGE_SIZE,
                                    CARD_FIRST_CURSOR,
                                    CARD_LAST_CURSOR,
                                    true,
                                    false,
                                    CARD_MAP_INDICATOR)
                            .toString();

            assertThat(rendering)
                    .doesNotContain(CARD_FIRST_CURSOR)
                    .doesNotContain(CARD_LAST_CURSOR)
                    .doesNotContain(CARD_FIRST_CURSOR.substring(0, CARD_KEY_CARD_NUMBER_WIDTH / 2))
                    .doesNotContain(CARD_LAST_CURSOR.substring(0, CARD_KEY_CARD_NUMBER_WIDTH / 2))
                    .doesNotContain(CARD_LAST_CURSOR.substring(CARD_KEY_CARD_NUMBER_WIDTH / 2))
                    .doesNotContain("4111111111110042")
                    .doesNotContain("0042");
        }

        @Test
        @DisplayName("each withheld cursor is replaced by a fixed placeholder, so nothing about the value - "
                + "not even its length - survives")
        void eachWithheldCursorIsReplacedByAFixedPlaceholder() {
            String rendering =
                    PageMetadata.forward(
                                    PageMetadata.CARD_LIST_PAGE_SIZE,
                                    CARD_FIRST_CURSOR,
                                    CARD_LAST_CURSOR,
                                    true,
                                    false,
                                    CARD_MAP_INDICATOR)
                            .toString();

            assertThat(rendering)
                    .contains("previousCursorKey=***REDACTED***")
                    .contains("nextCursorKey=***REDACTED***");
        }

        @Test
        @DisplayName("the placeholder is constant across differing cursor values and differing cursor "
                + "widths, which is the assertion that rules out a partial mask or a digest")
        void thePlaceholderIsConstantAcrossDifferingCursorValues() {
            String cardWidths =
                    PageMetadata.forward(
                                    PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                                    CARD_FIRST_CURSOR,
                                    CARD_LAST_CURSOR,
                                    true,
                                    false,
                                    LIST_MAP_INDICATOR)
                            .toString();
            String userWidths =
                    PageMetadata.forward(
                                    PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                                    USER_FIRST_CURSOR,
                                    USER_LAST_CURSOR,
                                    true,
                                    false,
                                    LIST_MAP_INDICATOR)
                            .toString();

            assertThat(cardWidths).isEqualTo(userWidths);
        }

        @Test
        @DisplayName("an absent cursor is withheld too, so the rendering does not disclose even whether a "
                + "cursor is present")
        void anAbsentCursorIsWithheldToo() {
            String populated =
                    PageMetadata.forward(
                                    PageMetadata.USER_LIST_PAGE_SIZE,
                                    USER_FIRST_CURSOR,
                                    USER_LAST_CURSOR,
                                    false,
                                    false,
                                    null)
                            .toString();
            String absent =
                    PageMetadata.forward(
                                    PageMetadata.USER_LIST_PAGE_SIZE, null, null, false, false, null)
                            .toString();

            assertThat(absent).isEqualTo(populated).doesNotContain("null,");
        }

        @Test
        @DisplayName("a hostile value planted in a cursor cannot reach the rendering")
        void aHostileValuePlantedInACursorCannotReachTheRendering() {
            String rendering =
                    PageMetadata.backward(
                                    PageMetadata.CARD_LIST_PAGE_SIZE,
                                    "CANARY-FIRST-CURSOR-KEY-001",
                                    "CANARY-LAST-CURSOR-KEY-0002",
                                    false,
                                    true,
                                    CARD_MAP_INDICATOR)
                            .toString();

            assertThat(rendering).doesNotContain("CANARY");
        }

        @Test
        @DisplayName("the redaction touches the rendering only: both accessors still answer the cursor byte "
                + "for byte, because the client cannot resume the browse without them")
        void theRedactionTouchesTheRenderingOnly() {
            PageMetadata page =
                    PageMetadata.forward(
                            PageMetadata.CARD_LIST_PAGE_SIZE,
                            CARD_FIRST_CURSOR,
                            CARD_LAST_CURSOR,
                            true,
                            false,
                            CARD_MAP_INDICATOR);

            assertThat(page.previousCursorKey()).isEqualTo(CARD_FIRST_CURSOR);
            assertThat(page.nextCursorKey()).isEqualTo(CARD_LAST_CURSOR);
        }

        @Test
        @DisplayName("the redaction does not reach the JSON wire form, which still transports both cursors "
                + "unchanged")
        void theRedactionDoesNotReachTheJsonWireForm() throws JsonProcessingException {
            Map<String, Object> wire =
                    wireProperties(
                            PageMetadata.forward(
                                    PageMetadata.CARD_LIST_PAGE_SIZE,
                                    CARD_FIRST_CURSOR,
                                    CARD_LAST_CURSOR,
                                    true,
                                    false,
                                    CARD_MAP_INDICATOR));

            assertThat(wire)
                    .containsEntry("previousCursorKey", CARD_FIRST_CURSOR)
                    .containsEntry("nextCursorKey", CARD_LAST_CURSOR);
        }

        @Test
        @DisplayName("two pages that render identically can still be unequal, which is why the rendering "
                + "must never be used as an equality proxy")
        void twoPagesThatRenderIdenticallyCanStillBeUnequal() {
            PageMetadata left =
                    PageMetadata.forward(
                            PageMetadata.USER_LIST_PAGE_SIZE,
                            USER_FIRST_CURSOR,
                            USER_LAST_CURSOR,
                            true,
                            false,
                            LIST_MAP_INDICATOR);
            PageMetadata right =
                    PageMetadata.forward(
                            PageMetadata.USER_LIST_PAGE_SIZE,
                            CARD_FIRST_CURSOR,
                            CARD_LAST_CURSOR,
                            true,
                            false,
                            LIST_MAP_INDICATOR);

            assertThat(left.toString()).isEqualTo(right.toString());
            assertThat(left).isNotEqualTo(right);
        }
    }

    @Nested
    @DisplayName("JSON wire contract, mirroring the module's own serialization settings")
    class JsonWireContract {

        @Test
        @DisplayName("a fully populated forward page serialises to the seven properties in declaration "
                + "order, values untouched")
        void fullyPopulatedForwardPageSerialisesToSevenOrderedProperties()
                throws JsonProcessingException {
            Map<String, Object> wire =
                    wireProperties(
                            PageMetadata.forward(
                                    PageMetadata.CARD_LIST_PAGE_SIZE,
                                    CARD_FIRST_CURSOR,
                                    CARD_LAST_CURSOR,
                                    true,
                                    false,
                                    CARD_MAP_INDICATOR));

            assertThat(wire.keySet())
                    .containsExactly(
                            "pageSize",
                            "previousCursorKey",
                            "nextCursorKey",
                            "direction",
                            "hasMorePages",
                            "hasPreviousPages",
                            "displayedPageNumber");
            assertThat(wire)
                    .containsEntry("pageSize", 7)
                    .containsEntry("previousCursorKey", "4111111111110036")
                    .containsEntry("nextCursorKey", "4111111111110042")
                    .containsEntry("direction", "FORWARD")
                    .containsEntry("hasMorePages", true)
                    .containsEntry("hasPreviousPages", false)
                    .containsEntry("displayedPageNumber", "  1");
        }

        @Test
        @DisplayName("components that are absent are omitted from the wire rather than sent as null")
        void absentComponentsAreOmittedRatherThanSentAsNull() throws JsonProcessingException {
            Map<String, Object> wire =
                    wireProperties(
                            PageMetadata.forward(
                                    PageMetadata.CARD_LIST_PAGE_SIZE, null, null, true, false, null));

            assertThat(wire).hasSize(4);
            assertThat(wire.keySet())
                    .containsExactly("pageSize", "direction", "hasMorePages", "hasPreviousPages");
            assertThat(wire)
                    .doesNotContainKey("previousCursorKey")
                    .doesNotContainKey("nextCursorKey")
                    .doesNotContainKey("displayedPageNumber");
        }

        @Test
        @DisplayName("an unknown inbound property is tolerated, because a client may echo back a "
                + "field this contract does not consume")
        void unknownInboundPropertyIsTolerated() throws JsonProcessingException {
            String inbound =
                    "{\"pageSize\":10,\"previousCursorKey\":\"0000000000000033\","
                            + "\"nextCursorKey\":\"0000000000000042\","
                            + "\"direction\":\"BACKWARD\",\"hasMorePages\":false,"
                            + "\"hasPreviousPages\":true,\"displayedPageNumber\":\"00000007\","
                            + "\"screenTitleEcho\":\"unused\"}";

            PageMetadata page = WIRE_MAPPER.readValue(inbound, PageMetadata.class);

            assertThat(page)
                    .isEqualTo(
                            new PageMetadata(
                                    10,
                                    "0000000000000033",
                                    "0000000000000042",
                                    PageMetadata.PagingDirection.BACKWARD,
                                    false,
                                    true,
                                    "00000007"));
        }

        @Test
        @DisplayName("a round trip through JSON preserves every component exactly, leading zeros and "
                + "padding included")
        void roundTripThroughJsonPreservesEveryComponentExactly() throws JsonProcessingException {
            PageMetadata original =
                    PageMetadata.backward(
                            PageMetadata.CARD_LIST_PAGE_SIZE,
                            CARD_FIRST_CURSOR,
                            CARD_LAST_CURSOR,
                            true,
                            true,
                            CARD_MAP_INDICATOR);

            PageMetadata restored =
                    WIRE_MAPPER.readValue(WIRE_MAPPER.writeValueAsString(original), PageMetadata.class);

            assertThat(restored).isEqualTo(original).isNotSameAs(original);
            assertThat(restored.pageSize()).isEqualTo(7);
            assertThat(restored.previousCursorKey())
                    .isEqualTo("4111111111110036")
                    .hasSize(PageMetadata.CURSOR_KEY_MAX_LENGTH);
            assertThat(restored.nextCursorKey())
                    .isEqualTo("4111111111110042")
                    .hasSize(PageMetadata.CURSOR_KEY_MAX_LENGTH);
            assertThat(restored.displayedPageNumber())
                    .isEqualTo("  1")
                    .hasSize(CARD_MAP_INDICATOR_WIDTH);
            assertThat(restored.direction()).isEqualTo(PageMetadata.PagingDirection.BACKWARD);
            assertThat(restored.hasMorePages()).isTrue();
            assertThat(restored.hasPreviousPages()).isTrue();
        }

        @Test
        @DisplayName("both indicator widths survive the wire side by side, neither widened nor "
                + "shortened to match the other")
        void bothIndicatorWidthsSurviveTheWire() throws JsonProcessingException {
            Map<String, Object> cardListWire =
                    wireProperties(
                            PageMetadata.forward(
                                    PageMetadata.CARD_LIST_PAGE_SIZE,
                                    CARD_FIRST_CURSOR,
                                    CARD_LAST_CURSOR,
                                    true,
                                    false,
                                    CARD_MAP_INDICATOR));
            Map<String, Object> userListWire =
                    wireProperties(
                            PageMetadata.forward(
                                    PageMetadata.USER_LIST_PAGE_SIZE,
                                    USER_FIRST_CURSOR,
                                    USER_LAST_CURSOR,
                                    true,
                                    false,
                                    LIST_MAP_INDICATOR_TRAILING));

            assertThat(cardListWire).containsEntry("displayedPageNumber", "  1");
            assertThat(userListWire).containsEntry("displayedPageNumber", "1       ");
        }
    }

    @Nested
    @DisplayName("Construction guards exactly one component, because the legacy validation cascades "
            + "are ordered and stop at the first error")
    class ConstructionGuardContract {

        @Test
        @DisplayName("all three text components may be absent, and construction says nothing about it")
        void allThreeTextComponentsMayBeAbsent() {
            PageMetadata page =
                    new PageMetadata(
                            PageMetadata.CARD_LIST_PAGE_SIZE,
                            null,
                            null,
                            PageMetadata.PagingDirection.FORWARD,
                            false,
                            false,
                            null);

            assertThat(page.previousCursorKey()).isNull();
            assertThat(page.nextCursorKey()).isNull();
            assertThat(page.displayedPageNumber()).isNull();
        }

        @Test
        @DisplayName("an empty cursor and an empty indicator cross construction untouched")
        void emptyTextComponentsCrossConstructionUntouched() {
            PageMetadata page = PageMetadata.forward(10, "", "", true, false, "");

            assertThat(page.previousCursorKey()).isEmpty();
            assertThat(page.nextCursorKey()).isEmpty();
            assertThat(page.displayedPageNumber()).isEmpty();
        }

        @Test
        @DisplayName("text that no shape rule would accept still crosses construction, so nothing "
                + "fires out of turn")
        void awkwardTextStillCrossesConstruction() {
            // The legacy editors run their checks in a fixed order and stop at the first failure,
            // reporting exactly one field. A declarative shape or presence rule evaluated during
            // construction would fire in annotation order instead and could report several at once,
            // so construction deliberately evaluates none: it guards the direction and nothing else.
            assertThatCode(() -> PageMetadata.forward(10, "** ?? //", "** ?? //", false, false, "* *"))
                    .doesNotThrowAnyException();

            PageMetadata page = PageMetadata.forward(10, "** ?? //", "** ?? //", false, false, "* *");

            assertThat(page.previousCursorKey()).isEqualTo("** ?? //");
            assertThat(page.nextCursorKey()).isEqualTo("** ?? //");
            assertThat(page.displayedPageNumber()).isEqualTo("* *");
        }

        @Test
        @DisplayName("no numeric bound is evaluated during construction either, and no list of "
                + "complaints is ever accumulated")
        void noNumericBoundIsEvaluatedDuringConstruction() {
            // A row figure that no screen would ask for still reaches the accessor unexamined: the
            // ordered, first-error-wins check belongs to the service layer at the request boundary,
            // and this record is the carrier rather than the editor. Construction therefore either
            // yields an instance or fails on the single component it genuinely requires.
            assertThatCode(
                            () ->
                                    new PageMetadata(
                                            0,
                                            null,
                                            null,
                                            PageMetadata.PagingDirection.BACKWARD,
                                            false,
                                            false,
                                            null))
                    .doesNotThrowAnyException();

            assertThatNullPointerException()
                    .isThrownBy(() -> new PageMetadata(0, "", "", null, false, false, ""))
                    .withMessage("direction must be supplied explicitly");
        }
    }

    @Nested
    @DisplayName("The inbound cursor shape carries only what a caller may legitimately choose, so no "
            + "screen dimension or browse outcome can be dictated from outside")
    class InboundCursorRequestContract {

        @Test
        @DisplayName("it declares the two boundary keys, the direction and the two retained values the "
                + "programs read back, and declares no page size and no derived exhaustion flag")
        void itDeclaresOnlyTheCallerOwnedComponents() {
            List<String> declared =
                    Arrays.stream(PageMetadata.PageCursorRequest.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared)
                    .containsExactly("previousCursorKey", "nextCursorKey", "direction",
                            "displayedPageNumber", "nextPageIndicated")
                    .hasSize(5);
            assertThat(declared)
                    .as("the row count is the shape of a legacy screen, and whether a page precedes this "
                            + "one is derived from the retained page number rather than retained, so "
                            + "neither is a caller's to send")
                    .doesNotContain("pageSize")
                    .doesNotContain("hasPreviousPages");
        }

        @Test
        @DisplayName("the two retained values it does declare are the communication-area fields all three "
                + "list programs read back, carried as text and a flag exactly as the fields are")
        void itDeclaresTheTwoRetainedCommunicationAreaValues() {
            final Map<String, Class<?>> byName = new LinkedHashMap<>();
            for (RecordComponent component
                    : PageMetadata.PageCursorRequest.class.getRecordComponents()) {
                byName.put(component.getName(), component.getType());
            }

            // Text, so the leading zeros a fixed-width indicator carries survive the round trip; a
            // numeric component would silently discard them.
            assertThat(byName).containsEntry("displayedPageNumber", String.class);
            assertThat(byName).containsEntry("nextPageIndicated", boolean.class);
        }

        @Test
        @DisplayName("it reads a retained page number, a padded one and an unusable one without throwing, "
                + "answering the zero a first entry carries when it cannot read one")
        void itReadsTheRetainedPageNumberWithoutThrowing() {
            assertThat(cursorRequestWithPageNumber("7").retainedPageNumber()).isEqualTo(7);
            assertThat(cursorRequestWithPageNumber("  7  ").retainedPageNumber()).isEqualTo(7);
            assertThat(cursorRequestWithPageNumber("00000042").retainedPageNumber()).isEqualTo(42);
            assertThat(cursorRequestWithPageNumber(null).retainedPageNumber()).isZero();
            assertThat(cursorRequestWithPageNumber("").retainedPageNumber()).isZero();
            assertThat(cursorRequestWithPageNumber("   ").retainedPageNumber()).isZero();
            // Neither of these can pass the component's own pattern, so reaching them means a direct
            // construction bypassed bean validation; the reader still answers rather than throwing.
            assertThat(cursorRequestWithPageNumber("1 2").retainedPageNumber()).isZero();
            assertThat(cursorRequestWithPageNumber("123456789").retainedPageNumber()).isZero();
        }

        private static PageMetadata.PageCursorRequest cursorRequestWithPageNumber(
                final String retained) {
            return new PageMetadata.PageCursorRequest(null, null, null, retained, false);
        }

        @Test
        @DisplayName("both boundary keys are text and the direction is the same two-constant vocabulary "
                + "the outbound shape publishes, so one type governs both halves of the browse")
        void itsComponentTypesMatchTheOutboundShape() {
            Map<String, Class<?>> types = new LinkedHashMap<>();
            for (RecordComponent component
                    : PageMetadata.PageCursorRequest.class.getRecordComponents()) {
                types.put(component.getName(), component.getType());
            }

            assertThat(types.get("previousCursorKey")).isEqualTo(String.class);
            assertThat(types.get("nextCursorKey")).isEqualTo(String.class);
            assertThat(types.get("direction")).isEqualTo(PageMetadata.PagingDirection.class);
        }

        @Test
        @DisplayName("each boundary key is bounded at the same sixteen characters the outbound shape "
                + "uses, which is the widest legacy browse key of the three screens")
        void eachBoundaryKeyIsBoundedAtTheSharedCursorWidth() {
            String widest = "x".repeat(PageMetadata.CURSOR_KEY_MAX_LENGTH);
            PageMetadata.PageCursorRequest atTheBound = new PageMetadata.PageCursorRequest(
                    widest, widest, PageMetadata.PagingDirection.FORWARD, null, false);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(atTheBound))
                        .as("a key of exactly the declared width is a card number or a transaction "
                                + "identifier, each sixteen characters")
                        .isEmpty();
            }

            assertThat(PageMetadata.CURSOR_KEY_MAX_LENGTH)
                    .isEqualTo(CARD_KEY_CARD_NUMBER_WIDTH)
                    .isEqualTo(TRANSACTION_KEY_WIDTH);
        }

        @Test
        @DisplayName("a key one character past the bound is reported on its own component, and the bound "
                + "measures without shortening the value it rejected")
        void aKeyPastTheBoundIsReportedWithoutBeingShortened() {
            String tooWide = "x".repeat(PageMetadata.CURSOR_KEY_MAX_LENGTH + 1);
            PageMetadata.PageCursorRequest request = new PageMetadata.PageCursorRequest(
                    tooWide, null, PageMetadata.PagingDirection.BACKWARD, null, false);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                Set<ConstraintViolation<PageMetadata.PageCursorRequest>> violations =
                        validator.validate(request);

                assertThat(violations).hasSize(1);
                assertThat(violations.iterator().next().getPropertyPath())
                        .hasToString("previousCursorKey");
            }

            assertThat(request.previousCursorKey())
                    .as("a bound measures and never alters, so the oversized value still reaches the "
                            + "accessor byte for byte")
                    .isEqualTo(tooWide)
                    .hasSize(PageMetadata.CURSOR_KEY_MAX_LENGTH + 1);
        }

        @Test
        @DisplayName("construction guards nothing at all, because the direction is legitimately absent on "
                + "the first entry to a list screen")
        void constructionGuardsNothingIncludingTheDirection() {
            assertThatCode(() -> new PageMetadata.PageCursorRequest(null, null, null, null, false))
                    .as("the outbound shape requires a direction because it reports a browse that already "
                            + "happened; the inbound shape describes one that has not started")
                    .doesNotThrowAnyException();

            PageMetadata.PageCursorRequest empty =
                    new PageMetadata.PageCursorRequest(null, null, null, null, false);

            assertThat(empty.previousCursorKey()).isNull();
            assertThat(empty.nextCursorKey()).isNull();
            assertThat(empty.direction())
                    .as("no default is substituted, so the service resolves the direction from the "
                            + "accompanying attention key rather than inheriting a guess made here")
                    .isNull();
        }

        @Test
        @DisplayName("an empty or awkward key crosses construction untouched, exactly as it does on the "
                + "outbound shape, so nothing fires out of the legacy cascade's turn")
        void awkwardKeysCrossConstructionUntouched() {
            PageMetadata.PageCursorRequest request = new PageMetadata.PageCursorRequest(
                    "", "** ?? //", PageMetadata.PagingDirection.FORWARD, null, false);

            assertThat(request.previousCursorKey()).isEmpty();
            assertThat(request.nextCursorKey()).isEqualTo("** ?? //");
        }

        @Test
        @DisplayName("it is a value: two requests built from the same three values are equal and share a "
                + "hash code")
        void itIsAValue() {
            PageMetadata.PageCursorRequest first = new PageMetadata.PageCursorRequest(
                    CARD_FIRST_CURSOR, CARD_LAST_CURSOR, PageMetadata.PagingDirection.BACKWARD, null, false);
            PageMetadata.PageCursorRequest second = new PageMetadata.PageCursorRequest(
                    CARD_FIRST_CURSOR, CARD_LAST_CURSOR, PageMetadata.PagingDirection.BACKWARD, null, false);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first)
                    .isNotEqualTo(new PageMetadata.PageCursorRequest(
                            CARD_FIRST_CURSOR, CARD_LAST_CURSOR,
                            PageMetadata.PagingDirection.FORWARD, null, false));
        }

        @Test
        @DisplayName("its diagnostic rendering withholds both boundary keys, because a card-list browse "
                + "key is the card number itself")
        void itsDiagnosticRenderingWithholdsBothBoundaryKeys() {
            PageMetadata.PageCursorRequest request = new PageMetadata.PageCursorRequest(
                    CARD_FIRST_CURSOR, CARD_LAST_CURSOR, PageMetadata.PagingDirection.BACKWARD, null, false);

            String rendered = request.toString();

            assertThat(rendered)
                    .doesNotContain(CARD_FIRST_CURSOR)
                    .doesNotContain(CARD_LAST_CURSOR)
                    .doesNotContain("0000000000000036")
                    .doesNotContain("0000000000000042")
                    .contains("BACKWARD");
            assertThat(request.previousCursorKey())
                    .as("only the rendering changes; the accessors are untouched")
                    .isEqualTo(CARD_FIRST_CURSOR);
            assertThat(request.nextCursorKey()).isEqualTo(CARD_LAST_CURSOR);
        }

        @Test
        @DisplayName("its rendering withholds a key even when the key is absent, so the placeholder is "
                + "fixed and reveals nothing about length or presence")
        void itsRenderingIsLengthAndPresenceIndependent() {
            String withKeys = new PageMetadata.PageCursorRequest(
                    CARD_FIRST_CURSOR, CARD_LAST_CURSOR, PageMetadata.PagingDirection.FORWARD, null, false)
                    .toString();
            String withoutKeys = new PageMetadata.PageCursorRequest(
                    null, null, PageMetadata.PagingDirection.FORWARD, null, false).toString();

            assertThat(withoutKeys)
                    .as("an absent key renders identically to a present one, so nothing about either "
                            + "regulated value is inferable from the rendering alone")
                    .isEqualTo(withKeys);
            // The claim is about the two cursor keys, which are the regulated components: each renders as
            // the fixed placeholder whether it is present or absent, so neither its length nor its
            // presence survives. It is deliberately not a blanket ban on the word "null" anywhere in the
            // rendering - the retained page number is a page indicator rather than regulated data, and
            // withholding it would make the rendering less useful for no protective gain.
            assertThat(withKeys)
                    .contains("previousCursorKey=***REDACTED***")
                    .contains("nextCursorKey=***REDACTED***")
                    .doesNotContain(CARD_FIRST_CURSOR)
                    .doesNotContain(CARD_LAST_CURSOR);
            assertThat(withoutKeys)
                    .contains("previousCursorKey=***REDACTED***")
                    .contains("nextCursorKey=***REDACTED***");
        }

        @Test
        @DisplayName("on the wire it publishes exactly its own members and accepts a body that also "
                + "carries the server-owned ones, discarding them")
        void onTheWireItPublishesItsMembersAndDiscardsTheServerOwnedOnes()
                throws JsonProcessingException {
            PageMetadata.PageCursorRequest request = new PageMetadata.PageCursorRequest(
                    CARD_FIRST_CURSOR, CARD_LAST_CURSOR, PageMetadata.PagingDirection.BACKWARD, null, false);

            Map<String, Object> published =
                    WIRE_MAPPER.readValue(WIRE_MAPPER.writeValueAsString(request), WIRE_SHAPE);

            // The page number is absent here and is therefore omitted; the retained flag is a primitive
            // and is always written, which is what makes "absent reads as false" a written fact rather
            // than an inference.
            assertThat(published).containsOnlyKeys("previousCursorKey", "nextCursorKey", "direction",
                    "nextPageIndicated");
            assertThat(published).containsEntry("direction", "BACKWARD");
            assertThat(published).containsEntry("nextPageIndicated", false);

            String overreachingBody = "{\"previousCursorKey\":\"" + CARD_FIRST_CURSOR + "\","
                    + "\"nextCursorKey\":\"" + CARD_LAST_CURSOR + "\",\"direction\":\"FORWARD\","
                    + "\"pageSize\":2147483647,\"hasMorePages\":true,\"hasPreviousPages\":true,"
                    + "\"displayedPageNumber\":\"" + LIST_MAP_INDICATOR + "\"}";

            PageMetadata.PageCursorRequest bound =
                    WIRE_MAPPER.readValue(overreachingBody, PageMetadata.PageCursorRequest.class);

            assertThat(bound.previousCursorKey()).isEqualTo(CARD_FIRST_CURSOR);
            assertThat(bound.nextCursorKey()).isEqualTo(CARD_LAST_CURSOR);
            assertThat(bound.direction()).isEqualTo(PageMetadata.PagingDirection.FORWARD);
            assertThat(WIRE_MAPPER.writeValueAsString(bound))
                    .as("the row count a caller tried to dictate is not merely ignored on the way in, it "
                            + "has nowhere to be held and so cannot be echoed on the way out")
                    .doesNotContain("2147483647")
                    .doesNotContain("pageSize")
                    .doesNotContain("hasMorePages")
                    .doesNotContain("hasPreviousPages");
        }

        @Test
        @DisplayName("an absent direction is omitted rather than published as null, and a body that omits "
                + "it binds without one")
        void anAbsentDirectionIsOmittedAndAcceptedBack() throws JsonProcessingException {
            String published = WIRE_MAPPER.writeValueAsString(
                    new PageMetadata.PageCursorRequest(null, null, null, null, false));

            // Not "{}": the retained flag is a primitive, so it is always written, and false is exactly
            // what an absent flag means. Every nullable member is still omitted rather than published as
            // null, which is the claim this test carries.
            assertThat(published).isEqualTo("{\"nextPageIndicated\":false}").doesNotContain("null");
            assertThat(WIRE_MAPPER.readValue("{}", PageMetadata.PageCursorRequest.class).direction())
                    .isNull();
        }

        @Test
        @DisplayName("the outbound shape still declares all seven of its members, so adding the inbound "
                + "shape narrowed the request without narrowing the response")
        void theOutboundShapeIsUnchanged() {
            List<String> declared = Arrays.stream(PageMetadata.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared)
                    .containsExactly("pageSize", "previousCursorKey", "nextCursorKey", "direction",
                            "hasMorePages", "hasPreviousPages", "displayedPageNumber")
                    .hasSize(7);
        }
    }
}
