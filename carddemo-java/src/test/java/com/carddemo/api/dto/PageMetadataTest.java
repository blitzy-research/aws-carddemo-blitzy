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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

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
 * <p><strong>What this test pins.</strong></p>
 *
 * <p>{@link PageMetadata} is the REST projection of the CICS browse protocol the legacy screens use
 * to walk a key-sequenced cluster: position the browse at a record key, walk forward, walk backward,
 * release. Five properties of that protocol are contractual rather than incidental, and each is
 * asserted below against the legacy member that establishes it.</p>
 * <ol>
 *   <li><strong>Three screen row counts, one per paginated screen.</strong> Each was proven by a
 *       different mechanism in a different member, and each is asserted here against its own
 *       independent arithmetic rather than against either of the other two.</li>
 *   <li><strong>No aggregate row figure of any kind.</strong> The legacy browse never counts a
 *       cluster; it discovers that a further page exists by attempting one more read and observing
 *       the outcome. The two conditions the screens actually know are carried as the separate flags
 *       {@link PageMetadata#hasMorePages()} and {@link PageMetadata#hasPreviousPages()}.</li>
 *   <li><strong>An opaque textual cursor, never a numeric offset.</strong> The legacy programs
 *       retain a record key across a pseudo-conversational turn and restart the browse from it, so
 *       leading zeros, embedded characters and padding all have to survive untouched.</li>
 *   <li><strong>A textual page indicator whose width differs by screen.</strong> The card-list map
 *       declares a three-character alphanumeric field named {@code PAGENO}; the transaction-list and
 *       user-list maps each declare an eight-character alphanumeric field named {@code PAGENUM}.
 *       Neither width is normalised to the other and neither becomes numeric.</li>
 *   <li><strong>Exactly two browse directions.</strong> One per CICS browse verb, with no third
 *       constant and no default, because the legacy programs always branch on an explicit attention
 *       key.</li>
 * </ol>
 *
 * <p><strong>Screen row counts and their independent derivations.</strong></p>
 *
 * <p>The three counts are asserted in {@link ScreenRowCountContract}. Nothing in this file derives
 * one count from another, and the two counts that happen to be equal are asserted against two
 * separate constants using two separate legacy arithmetics:</p>
 * <ul>
 *   <li><strong>Card list, seven rows.</strong> {@code app/cbl/COCRDLIC.cbl} declares a
 *       196-character all-rows screen area at line 253, redefined at line 255 as a table of seven
 *       occurrences whose element is 28 characters wide - an 11-character account identifier, a
 *       16-character card number and a 1-character status indicator at lines 258 to 260. The
 *       product 28 by 7 accounts for all 196 characters, so the count follows from two independently
 *       declared widths. A second witness is the screen-line counter at lines 177 and 178, whose
 *       declared value is 7, and a third is the backward fill counter seeded at that value plus one
 *       at lines 1284 and 1285 and decremented to zero.</li>
 *   <li><strong>Transaction list, ten rows.</strong> {@code app/cbl/COTRN00C.cbl} establishes the
 *       count <em>purely from loop bounds</em>: the row-clearing loop is bounded at ten on line 290,
 *       the row index is reset to one on line 295, and the row-filling loop on line 297 stops once
 *       the index reaches eleven. There is no row table in that program at all - its single
 *       {@code OCCURS} clause, on line 89, is the communication-area redefinition that depends on
 *       the inbound area length and has nothing to do with screen rows. A future reader who looks
 *       for a table and finds none must not conclude the constant is unfounded, which is why the
 *       arithmetic on the two loop bounds is asserted explicitly below.</li>
 *   <li><strong>User list, ten rows.</strong> {@code app/cbl/COUSR00C.cbl} declares the screen row
 *       group {@code USER-REC} as a genuine table of ten occurrences at lines 56 and 57, whose
 *       element sums to 48 characters across its five data fields and two fillers - a different
 *       mechanism from the transaction count above.</li>
 * </ul>
 *
 * <p>Every one of those figures is a legacy screen shape. Changing any of them would put a different
 * number of rows in front of an operator, which is a visible behavioural change and not a
 * configuration adjustment. Not one of them is a knob to be turned: each is the shape of a screen,
 * and each is asserted below as exactly that.</p>
 *
 * <p><strong>Scope of this test.</strong></p>
 *
 * <p>This is a pure in-process unit test. It starts no application context, opens no database
 * connection, provisions no container, reads no file and touches no network: the type under test is
 * an immutable record whose only dependencies are two validation annotations and
 * {@code java.util.Objects}, so a plain JVM is the whole of its required environment. It also
 * performs no introspection of any kind - the immutability, the constant declarations and the
 * absence of a mutator are established by what this source is able to compile and by observable
 * behaviour, never by interrogating class metadata at run time.</p>
 *
 * <p>The type under test names no web, persistence or data-access abstraction, and neither does this
 * test. No paging abstraction from any framework appears in the imports above; the whole paging
 * contract is defined by the legacy screens.</p>
 *
 * <p><strong>Expectations are derived, never echoed.</strong></p>
 *
 * <p>Every expected value below is a literal typed out in this source and traceable to a measured
 * legacy fact. No expectation is produced by calling the type under test, no assertion compares a
 * computed value with a second evaluation of the same computation, and where an equality expectation
 * involves two instances the two instances are constructed independently.</p>
 *
 * <p><strong>Provenance.</strong></p>
 *
 * <p>Legacy estate read at commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The stamp is cited here as
 * prose provenance only: it is never declared as a constant and never asserted against a member,
 * because the estate does not carry it uniformly. No line of legacy source text is reproduced
 * anywhere in this file - only member names, field names, record widths, field counts and line
 * numbers, which are metadata about the estate rather than content from it.</p>
 */
class PageMetadataTest {

    // ---------------------------------------------------------------------------------------------
    // Legacy geometry, declared here so that every assertion below reads against a named figure
    // whose origin is stated. Each constant is a width, a count or a loop bound measured from one
    // named legacy member. None of them is derived from a constant of the type under test, so an
    // assertion that relates the two is a genuine cross-check rather than a restatement.
    // ---------------------------------------------------------------------------------------------

    /**
     * Width of the account identifier inside one card-list screen row: legacy field
     * {@code WS-ROW-ACCTNO}, an 11-character field at {@code app/cbl/COCRDLIC.cbl} line 258.
     */
    private static final int CARD_ROW_ACCOUNT_ID_WIDTH = 11;

    /**
     * Width of the card number inside one card-list screen row: legacy field
     * {@code WS-ROW-CARD-NUM}, a 16-character field at {@code app/cbl/COCRDLIC.cbl} line 259.
     */
    private static final int CARD_ROW_CARD_NUMBER_WIDTH = 16;

    /**
     * Width of the status indicator inside one card-list screen row: legacy field
     * {@code WS-ROW-CARD-STATUS}, a 1-character field at {@code app/cbl/COCRDLIC.cbl} line 260.
     */
    private static final int CARD_ROW_STATUS_WIDTH = 1;

    /**
     * Width of the whole card-list rows area: legacy field {@code WS-ALL-ROWS}, a 196-character
     * field at {@code app/cbl/COCRDLIC.cbl} line 253, which line 255 redefines as the row table.
     * Declared independently of the three row-field widths above so that the two can be reconciled.
     */
    private static final int CARD_ALL_ROWS_AREA_WIDTH = 196;

    /**
     * Value of the card-list screen-line counter: legacy field {@code WS-MAX-SCREEN-LINES}, declared
     * with value 7 at {@code app/cbl/COCRDLIC.cbl} lines 177 and 178. A second, independent witness
     * to the card-list row count, held separately so the two can be reconciled.
     */
    private static final int CARD_DECLARED_SCREEN_LINES = 7;

    /**
     * Seed of the card-list backward fill counter: {@code app/cbl/COCRDLIC.cbl} lines 1284 and 1285
     * compute it as the screen-line counter plus one, so its value is 8. The backward path fills
     * rows from that seed downward, decrementing at lines 1307 and 1346 and stopping at zero, which
     * is how a backward page is filled from the bottom row upward.
     */
    private static final int CARD_BACKWARD_FILL_SEED = 8;

    /**
     * Bound of the transaction-list row-clearing loop: {@code app/cbl/COTRN00C.cbl} line 290 runs
     * the index from one while it is not greater than ten.
     */
    private static final int TRANSACTION_CLEARING_LOOP_BOUND = 10;

    /**
     * Stop value of the transaction-list row-filling loop: {@code app/cbl/COTRN00C.cbl} line 297
     * halts once the index reaches eleven, having reset it to one on line 295. The number of rows
     * filled is therefore this stop value less one, and this is the whole of the evidence for the
     * transaction-list row count - the program declares no row table.
     */
    private static final int TRANSACTION_FILL_LOOP_STOP = 11;

    /**
     * Seed of the transaction-list backward fill index: {@code app/cbl/COTRN00C.cbl} line 349 moves
     * ten into the index inside the backward paragraph that begins at line 333, then the loop at
     * lines 351 to 357 reads backward at line 352 and decrements at line 355 until the index falls
     * to zero, filling slots ten down to one.
     */
    private static final int TRANSACTION_BACKWARD_FILL_SEED = 10;

    /**
     * Widths of the seven fields that make up one user-list screen row group, in declaration order
     * at {@code app/cbl/COUSR00C.cbl} lines 58 to 64: a 1-character selection field, a 2-character
     * filler, an 8-character user identifier, a 2-character filler, a 25-character name, a
     * 2-character filler and an 8-character type. The group is declared with ten occurrences at
     * lines 56 and 57.
     */
    private static final List<Integer> USER_ROW_FIELD_WIDTHS = List.of(1, 2, 8, 2, 25, 2, 8);

    /**
     * Total width of one user-list screen row group: the seven field widths above sum to 48.
     * Declared independently so the sum can be reconciled with it.
     */
    private static final int USER_ROW_GROUP_WIDTH = 48;

    /**
     * Width of the card number component of the card-list retained browse key: legacy field
     * {@code WS-CA-LAST-CARD-NUM}, a 16-character field at {@code app/cbl/COCRDLIC.cbl} line 231,
     * paired with the identically shaped first-key group at line 234.
     */
    private static final int CARD_KEY_CARD_NUMBER_WIDTH = 16;

    /**
     * Width of the account identifier component of the card-list retained browse key: legacy field
     * {@code WS-CA-LAST-CARD-ACCT-ID}, an 11-digit field at {@code app/cbl/COCRDLIC.cbl} line 232,
     * paired with the first-key group at line 235.
     */
    private static final int CARD_KEY_ACCOUNT_ID_WIDTH = 11;

    /**
     * Width of the transaction-list retained browse key: legacy fields
     * {@code CDEMO-CT00-TRNID-FIRST} and {@code CDEMO-CT00-TRNID-LAST}, 16-character fields at
     * {@code app/cbl/COTRN00C.cbl} lines 63 and 64.
     */
    private static final int TRANSACTION_KEY_WIDTH = 16;

    /**
     * Width of the user-list retained browse key: legacy fields {@code CDEMO-CU00-USRID-FIRST} and
     * {@code CDEMO-CU00-USRID-LAST}, 8-character fields at {@code app/cbl/COUSR00C.cbl} lines 68
     * and 69.
     */
    private static final int USER_KEY_WIDTH = 8;

    /**
     * Width of the card-list page indicator field: {@code app/cpy-bms/COCRDLI.CPY} declares
     * {@code PAGENO} as a three-character alphanumeric field, on line 60 for input and line 332 for
     * output.
     */
    private static final int CARD_MAP_INDICATOR_WIDTH = 3;

    /**
     * Width of the transaction-list and user-list page indicator field: both
     * {@code app/cpy-bms/COTRN00.CPY} and {@code app/cpy-bms/COUSR00.CPY} declare {@code PAGENUM} as
     * an eight-character alphanumeric field on line 60. The two maps disagree with the card-list map
     * on this width, and that disagreement is part of the contract.
     */
    private static final int LIST_MAP_INDICATOR_WIDTH = 8;

    // ---------------------------------------------------------------------------------------------
    // Synthetic sample values. Every one is invented for this test and identifies nothing real. Not
    // one of them authenticates anything, and no legacy sign-on literal appears in this file.
    // ---------------------------------------------------------------------------------------------

    /**
     * Card-list style browse key at the widest legacy key width: a 16-character card number of
     * leading zeros followed by an 11-digit account identifier, 27 characters in total. Chosen so
     * that one value exercises the widest key and the leading-zero requirement together.
     */
    private static final String CARD_COMPOSITE_CURSOR = "0000000000000042" + "00000000011";

    /**
     * Transaction-list style browse key: a 16-character identifier carrying fifteen leading zeros.
     * A numeric reading of this value would collapse it to two characters, which is precisely what
     * must not happen.
     */
    private static final String TRANSACTION_CURSOR = "0000000000000042";

    /**
     * User-list style browse key: an 8-character synthetic user identifier. Deliberately not
     * all-numeric, so that a cursor which could not be parsed as a number at all is exercised too.
     */
    private static final String USER_CURSOR = "USRT0001";

    /**
     * Card-list style page indicator at the three-character map width, right-justified with two
     * leading spaces the way a fixed-width alphanumeric screen field carries a single digit.
     */
    private static final String CARD_MAP_INDICATOR = "  1";

    /**
     * Transaction-list and user-list style page indicator at the eight-character map width, zero
     * filled.
     */
    private static final String LIST_MAP_INDICATOR = "00000007";

    /**
     * Second eight-character indicator, this one left-justified with trailing spaces, so that
     * padding on the other side of the value is exercised as well.
     */
    private static final String LIST_MAP_INDICATOR_TRAILING = "1       ";

    /**
     * JSON mapper built to match the module's shared configuration file, which declares non-null
     * property inclusion, ISO-8601 rather than numeric dates, lenient handling of unknown inbound
     * properties, and plain rather than scientific decimal output. It is created here as a plain
     * local mapper rather than obtained from a framework context, because this test starts no
     * context; configuring it from the same four settings the module declares is what makes the wire
     * assertions below representative of the published contract.
     *
     * <p>Property inclusion is applied through the value-and-content form rather than the older
     * single-argument form, because the latter is deprecated and this module compiles with warnings
     * promoted to errors. For a record of six scalar components only the value part is observable.
     * The plain-decimal setting has no observable effect on this record either, since it carries no
     * decimal component; it is configured anyway so that the mapper is a faithful stand-in for the
     * module's own and cannot drift from it.</p>
     */
    private static final ObjectMapper WIRE_MAPPER = wireMapper();

    /** Target shape for reading a serialized instance back as a property map, key order preserved. */
    private static final TypeReference<Map<String, Object>> WIRE_SHAPE =
            new TypeReference<Map<String, Object>>() { };

    /**
     * Builds the mapper described by {@link #WIRE_MAPPER}.
     *
     * @return a mapper configured from the module's four declared JSON settings
     */
    private static ObjectMapper wireMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(
                        JsonInclude.Value.construct(
                                JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    /**
     * Serializes an instance and reads it straight back as a property map, so that assertions can be
     * made about which property names cross the wire and in which order.
     *
     * @param metadata the instance to serialize
     * @return the serialized properties, in the order the serializer emitted them
     * @throws JsonProcessingException if serialization or the read-back fails, which fails the
     *     calling test with the mapper's own diagnostic
     */
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
            // First of three assertions, each naming a different constant. This one is asserted
            // against a literal seven and against nothing else in the type under test; the
            // independent arithmetic behind the seven is reconciled in
            // cardRowWidthsAccountForTheWholeDeclaredRowsArea below.
            assertThat(PageMetadata.CARD_LIST_PAGE_SIZE).isEqualTo(7);
        }

        @Test
        @DisplayName("the transaction-list screen presents ten rows, established by loop bounds and "
                + "by no row table whatsoever")
        void transactionListScreenPresentsTenRows() {
            // Second of three assertions, naming the transaction-list constant only.
            //
            // PROVENANCE, recorded here so that a future reader who goes looking for a row table
            // does not conclude this constant is unfounded: app/cbl/COTRN00C.cbl declares NO table
            // for its screen rows. The ten comes entirely from loop bounds - the clearing loop is
            // bounded at ten on line 290, the index is reset to one on line 295, and the filling
            // loop on line 297 halts once the index reaches eleven. The single OCCURS clause in
            // that program, on line 89, is the inbound communication-area redefinition and has
            // nothing to do with screen rows. The arithmetic on those bounds is asserted in
            // transactionRowCountFollowsFromLoopBoundsAlone below.
            assertThat(PageMetadata.TRANSACTION_LIST_PAGE_SIZE).isEqualTo(10);
        }

        @Test
        @DisplayName("the user-list screen presents ten rows, declared as a genuine ten-occurrence "
                + "table")
        void userListScreenPresentsTenRows() {
            // Third of three assertions, naming the user-list constant only. Unlike the transaction
            // count above, this one rests on a real table declaration at app/cbl/COUSR00C.cbl lines
            // 56 and 57 - a different mechanism, in a different member, for the same figure.
            assertThat(PageMetadata.USER_LIST_PAGE_SIZE).isEqualTo(10);
        }

        @Test
        @DisplayName("the two ten-row screens are two separately named constants, never one shared "
                + "constant used twice")
        void theTwoTenRowScreensAreTwoSeparatelyNamedConstants() {
            // This is the only expression in the file where all three names appear together, and it
            // is here to make one point: three names resolve, and they carry two distinct figures.
            // The two tens coincide by accident of two unrelated screen layouts proven by two
            // unrelated mechanisms, so neither is derived from the other and no assertion above
            // compares one against the other. Were they collapsed into a single shared constant, a
            // future change to one screen would travel silently to the other, which is a
            // behavioural regression on a screen nobody edited.
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
                            CARD_COMPOSITE_CURSOR,
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
                            TRANSACTION_CURSOR,
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
                            USER_CURSOR,
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
                            PageMetadata.CARD_LIST_PAGE_SIZE, CARD_COMPOSITE_CURSOR, true, false, CARD_MAP_INDICATOR);
            PageMetadata transactionListPage =
                    PageMetadata.forward(
                            PageMetadata.TRANSACTION_LIST_PAGE_SIZE, TRANSACTION_CURSOR, true, false, LIST_MAP_INDICATOR);
            PageMetadata userListPage =
                    PageMetadata.forward(
                            PageMetadata.USER_LIST_PAGE_SIZE, USER_CURSOR, true, false, LIST_MAP_INDICATOR);

            assertThat(
                            List.of(
                                    cardListPage.pageSize(),
                                    transactionListPage.pageSize(),
                                    userListPage.pageSize()))
                    .containsExactly(7, 10, 10);

            // The two ten-row screens agree on the row figure and differ on the key they browse,
            // which is exactly the shape the legacy estate has: a 16-character transaction
            // identifier against an 8-character user identifier.
            assertThat(transactionListPage.cursorKey()).hasSize(TRANSACTION_KEY_WIDTH);
            assertThat(userListPage.cursorKey()).hasSize(USER_KEY_WIDTH);
        }

        @Test
        @DisplayName("the component is data and is never defaulted: a caller asking for a single row "
                + "gets a single row")
        void theRowCountComponentIsNeverDefaulted() {
            // Nothing substitutes one of the three screen figures for what the caller supplied, and
            // nothing here is clamped or rounded up to a screen shape.
            PageMetadata singleRowPage =
                    PageMetadata.forward(1, TRANSACTION_CURSOR, true, false, LIST_MAP_INDICATOR);

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
                    PageMetadata.forward(10, TRANSACTION_CURSOR, false, false, LIST_MAP_INDICATOR);
            PageMetadata firstOfSeveral =
                    PageMetadata.forward(10, TRANSACTION_CURSOR, true, false, LIST_MAP_INDICATOR);
            PageMetadata lastOfSeveral =
                    PageMetadata.forward(10, TRANSACTION_CURSOR, false, true, LIST_MAP_INDICATOR);
            PageMetadata middleOfSeveral =
                    PageMetadata.forward(10, TRANSACTION_CURSOR, true, true, LIST_MAP_INDICATOR);

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
                            PageMetadata.CARD_LIST_PAGE_SIZE, CARD_COMPOSITE_CURSOR, true, false, CARD_MAP_INDICATOR);
            PageMetadata somethingPrecedes =
                    PageMetadata.forward(
                            PageMetadata.CARD_LIST_PAGE_SIZE, CARD_COMPOSITE_CURSOR, false, true, CARD_MAP_INDICATOR);

            // Only the two flags differ, and flipping one does not move the other.
            assertThat(moreFollows).isNotEqualTo(somethingPrecedes);
            assertThat(moreFollows.cursorKey()).isEqualTo(somethingPrecedes.cursorKey());
            assertThat(moreFollows.pageSize()).isEqualTo(somethingPrecedes.pageSize());
            assertThat(moreFollows.hasMorePages()).isNotEqualTo(somethingPrecedes.hasMorePages());
            assertThat(moreFollows.hasPreviousPages())
                    .isNotEqualTo(somethingPrecedes.hasPreviousPages());
        }

        @Test
        @DisplayName("the wire shape carries exactly six named properties, none of them an aggregate "
                + "row figure the legacy browse never had")
        void theWireShapeCarriesNoAggregateRowFigure() throws JsonProcessingException {
            Map<String, Object> wire =
                    wireProperties(
                            PageMetadata.forward(
                                    PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                                    TRANSACTION_CURSOR,
                                    true,
                                    true,
                                    LIST_MAP_INDICATOR));

            assertThat(wire).hasSize(6);
            assertThat(wire.keySet())
                    .containsExactly(
                            "pageSize",
                            "cursorKey",
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
    @DisplayName("The cursor is opaque text, never a numeric offset")
    class CursorKeyContract {

        @Test
        @DisplayName("a transaction key of fifteen leading zeros round-trips unchanged, and is not "
                + "collapsed to its numeric value")
        void leadingZeroCursorRoundTripsUnchanged() {
            PageMetadata page =
                    PageMetadata.forward(10, TRANSACTION_CURSOR, true, false, LIST_MAP_INDICATOR);

            assertThat(page.cursorKey())
                    .isEqualTo("0000000000000042")
                    .hasSize(TRANSACTION_KEY_WIDTH)
                    .startsWith("0")
                    .isNotEqualTo("42");
        }

        @Test
        @DisplayName("the card composite key crosses at the widest legacy key width of 27 characters")
        void cardCompositeCursorCrossesAtTwentySevenCharacters() {
            PageMetadata page =
                    PageMetadata.backward(
                            PageMetadata.CARD_LIST_PAGE_SIZE, CARD_COMPOSITE_CURSOR, true, true, CARD_MAP_INDICATOR);

            assertThat(page.cursorKey()).hasSize(27);
            assertThat(CARD_KEY_CARD_NUMBER_WIDTH + CARD_KEY_ACCOUNT_ID_WIDTH).isEqualTo(27);
        }

        @Test
        @DisplayName("the declared cursor bound is the widest of the three legacy keys, so no legal "
                + "cursor is ever refused")
        void cursorBoundIsTheWidestOfTheThreeLegacyKeys() {
            assertThat(PageMetadata.CURSOR_KEY_MAX_LENGTH)
                    .isEqualTo(27)
                    .isEqualTo(CARD_KEY_CARD_NUMBER_WIDTH + CARD_KEY_ACCOUNT_ID_WIDTH)
                    .isGreaterThanOrEqualTo(TRANSACTION_KEY_WIDTH)
                    .isGreaterThanOrEqualTo(USER_KEY_WIDTH);
        }

        @Test
        @DisplayName("an eight-character user key that is not numeric at all round-trips verbatim")
        void nonNumericUserCursorRoundTripsVerbatim() {
            PageMetadata page =
                    PageMetadata.forward(
                            PageMetadata.USER_LIST_PAGE_SIZE,
                            USER_CURSOR,
                            false,
                            true,
                            LIST_MAP_INDICATOR_TRAILING);

            assertThat(page.cursorKey()).isEqualTo("USRT0001").hasSize(USER_KEY_WIDTH);
        }

        @Test
        @DisplayName("padding on either side of a cursor survives, because a fixed-width key carries "
                + "it")
        void cursorPaddingSurvivesOnBothSides() {
            String paddedKey = "  0000042       ";

            PageMetadata page = PageMetadata.forward(10, paddedKey, true, true, LIST_MAP_INDICATOR);

            assertThat(page.cursorKey())
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
                    PageMetadata.forward(PageMetadata.CARD_LIST_PAGE_SIZE, null, true, false, null);

            assertThat(page.cursorKey()).isNull();
            assertThat(page.hasMorePages()).isTrue();
        }

        @Test
        @DisplayName("the accessor hands back the very instance supplied, so nothing is copied, "
                + "padded, trimmed or case folded")
        void accessorHandsBackTheSuppliedInstance() {
            String suppliedKey = "0".repeat(15).concat("7");

            PageMetadata page = PageMetadata.forward(10, suppliedKey, false, false, null);

            assertThat(page.cursorKey()).isSameAs(suppliedKey);
            assertThat(page.cursorKey()).isEqualTo("0000000000000007");
        }

        @Test
        @DisplayName("an over-wide cursor is stored verbatim rather than shortened, because the "
                + "declared bound reports and never alters")
        void overWideCursorIsStoredVerbatim() {
            String overWideKey = "X".repeat(PageMetadata.CURSOR_KEY_MAX_LENGTH + 1);

            PageMetadata page = PageMetadata.forward(10, overWideKey, false, false, null);

            assertThat(page.cursorKey()).isEqualTo(overWideKey).hasSize(28);
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
                            PageMetadata.CARD_LIST_PAGE_SIZE, CARD_COMPOSITE_CURSOR, true, false, CARD_MAP_INDICATOR);

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
                    PageMetadata.forward(10, TRANSACTION_CURSOR, true, false, LIST_MAP_INDICATOR);
            PageMetadata spaceFilled =
                    PageMetadata.forward(
                            10, TRANSACTION_CURSOR, true, false, LIST_MAP_INDICATOR_TRAILING);

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
                            PageMetadata.CARD_LIST_PAGE_SIZE, CARD_COMPOSITE_CURSOR, true, false, CARD_MAP_INDICATOR);
            PageMetadata transactionListPage =
                    PageMetadata.forward(
                            PageMetadata.TRANSACTION_LIST_PAGE_SIZE, TRANSACTION_CURSOR, true, false, LIST_MAP_INDICATOR);

            assertThat(CARD_MAP_INDICATOR_WIDTH).isNotEqualTo(LIST_MAP_INDICATOR_WIDTH);
            assertThat(cardListPage.displayedPageNumber()).hasSize(3);
            assertThat(transactionListPage.displayedPageNumber()).hasSize(8);
        }

        @Test
        @DisplayName("the indicator is text, so a value no numeric field could hold still survives")
        void indicatorIsTextRatherThanANumber() {
            PageMetadata page =
                    PageMetadata.backward(
                            PageMetadata.CARD_LIST_PAGE_SIZE, CARD_COMPOSITE_CURSOR, false, true, "N/A");

            assertThat(page.displayedPageNumber())
                    .isEqualTo("N/A")
                    .hasSize(CARD_MAP_INDICATOR_WIDTH);
        }

        @Test
        @DisplayName("a null indicator is accepted, and navigation still works because the cursor is "
                + "the authoritative state")
        void nullIndicatorIsAcceptedWhileTheCursorRemainsAuthoritative() {
            PageMetadata page = PageMetadata.backward(10, TRANSACTION_CURSOR, true, true, null);

            assertThat(page.displayedPageNumber()).isNull();
            assertThat(page.cursorKey()).isEqualTo("0000000000000042");
        }

        @Test
        @DisplayName("the declared indicator bound is the wider of the two map widths and alters "
                + "nothing that exceeds it")
        void indicatorBoundIsTheWiderOfTheTwoMapWidths() {
            String overWideIndicator = "9".repeat(PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH + 1);

            PageMetadata page =
                    PageMetadata.forward(10, TRANSACTION_CURSOR, false, false, overWideIndicator);

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
                            TRANSACTION_CURSOR,
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
                            TRANSACTION_CURSOR,
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
                    PageMetadata.forward(10, TRANSACTION_CURSOR, true, true, LIST_MAP_INDICATOR);
            PageMetadata backwardPage =
                    PageMetadata.backward(10, TRANSACTION_CURSOR, true, true, LIST_MAP_INDICATOR);

            assertThat(forwardPage.direction()).isNotEqualTo(backwardPage.direction());
            assertThat(forwardPage).isNotEqualTo(backwardPage);

            // Everything except the direction agrees, which is what makes the direction the single
            // distinguishing component rather than a by-product of some other difference.
            assertThat(forwardPage.pageSize()).isEqualTo(backwardPage.pageSize());
            assertThat(forwardPage.cursorKey()).isEqualTo(backwardPage.cursorKey());
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
                            TRANSACTION_CURSOR,
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
                                            TRANSACTION_CURSOR,
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
                                            CARD_COMPOSITE_CURSOR,
                                            true,
                                            false,
                                            CARD_MAP_INDICATOR)))
                    .containsEntry("direction", "FORWARD");
            assertThat(
                            wireProperties(
                                    PageMetadata.backward(
                                            PageMetadata.CARD_LIST_PAGE_SIZE,
                                            CARD_COMPOSITE_CURSOR,
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
                            PageMetadata.PagingDirection.FORWARD,
                            true,
                            false,
                            "00000007");
            PageMetadata second =
                    new PageMetadata(
                            10,
                            "0000000000000042",
                            PageMetadata.PagingDirection.FORWARD,
                            true,
                            false,
                            "00000007");

            assertThat(first).isEqualTo(second).isNotSameAs(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("each of the six components participates in equality on its own")
        void eachOfTheSixComponentsParticipatesInEquality() {
            PageMetadata.PagingDirection forward = PageMetadata.PagingDirection.FORWARD;
            PageMetadata.PagingDirection backward = PageMetadata.PagingDirection.BACKWARD;
            PageMetadata reference =
                    new PageMetadata(10, TRANSACTION_CURSOR, forward, true, false, LIST_MAP_INDICATOR);

            assertThat(reference)
                    .isNotEqualTo(
                            new PageMetadata(
                                    7, TRANSACTION_CURSOR, forward, true, false, LIST_MAP_INDICATOR));
            assertThat(reference)
                    .isNotEqualTo(
                            new PageMetadata(
                                    10,
                                    "0000000000000043",
                                    forward,
                                    true,
                                    false,
                                    LIST_MAP_INDICATOR));
            assertThat(reference)
                    .isNotEqualTo(
                            new PageMetadata(
                                    10, TRANSACTION_CURSOR, backward, true, false, LIST_MAP_INDICATOR));
            assertThat(reference)
                    .isNotEqualTo(
                            new PageMetadata(
                                    10, TRANSACTION_CURSOR, forward, false, false, LIST_MAP_INDICATOR));
            assertThat(reference)
                    .isNotEqualTo(
                            new PageMetadata(
                                    10, TRANSACTION_CURSOR, forward, true, true, LIST_MAP_INDICATOR));
            assertThat(reference)
                    .isNotEqualTo(
                            new PageMetadata(10, TRANSACTION_CURSOR, forward, true, false, "00000008"));
        }

        @Test
        @DisplayName("equality is self-consistent and refuses both null and a foreign type")
        void equalityIsSelfConsistentAndRefusesNullAndForeignTypes() {
            PageMetadata page =
                    PageMetadata.forward(10, TRANSACTION_CURSOR, true, false, LIST_MAP_INDICATOR);

            assertThat(page).isEqualTo(page);
            assertThat(page).isNotEqualTo(null);
            assertThat(page).isNotEqualTo("0000000000000042");
        }

        @Test
        @DisplayName("all six accessors hand back exactly what construction was given")
        void allSixAccessorsHandBackWhatConstructionWasGiven() {
            String cursor = "USRT".concat("0002");
            String indicator = "0000001".concat("2");

            PageMetadata page =
                    new PageMetadata(
                            PageMetadata.USER_LIST_PAGE_SIZE,
                            cursor,
                            PageMetadata.PagingDirection.BACKWARD,
                            false,
                            true,
                            indicator);

            assertThat(page.pageSize()).isEqualTo(10);
            assertThat(page.cursorKey()).isSameAs(cursor).isEqualTo("USRT0002");
            assertThat(page.direction()).isSameAs(PageMetadata.PagingDirection.BACKWARD);
            assertThat(page.hasMorePages()).isFalse();
            assertThat(page.hasPreviousPages()).isTrue();
            assertThat(page.displayedPageNumber()).isSameAs(indicator).isEqualTo("00000012");
        }

        @Test
        @DisplayName("no component is defaulted, normalised, padded, trimmed or case folded")
        void noComponentIsDefaultedNormalisedOrCaseFolded() {
            String mixedCaseCursor = "usrT0003";

            PageMetadata page = PageMetadata.forward(10, mixedCaseCursor, false, false, " 4 ");

            assertThat(page.cursorKey()).isEqualTo("usrT0003").isNotEqualTo("USRT0003");
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
                            PageMetadata.CARD_LIST_PAGE_SIZE, CARD_COMPOSITE_CURSOR, true, false, CARD_MAP_INDICATOR);
            PageMetadata transactionListPage =
                    PageMetadata.forward(
                            PageMetadata.TRANSACTION_LIST_PAGE_SIZE, TRANSACTION_CURSOR, true, false, LIST_MAP_INDICATOR);
            PageMetadata userListPage =
                    PageMetadata.backward(
                            PageMetadata.USER_LIST_PAGE_SIZE, USER_CURSOR, false, true, LIST_MAP_INDICATOR);

            Set<PageMetadata> distinct = Set.of(cardListPage, transactionListPage, userListPage);

            assertThat(distinct).hasSize(3);
            assertThat(distinct)
                    .contains(
                            new PageMetadata(
                                    PageMetadata.USER_LIST_PAGE_SIZE,
                                    "USRT0001",
                                    PageMetadata.PagingDirection.BACKWARD,
                                    false,
                                    true,
                                    "00000007"));
        }

        @Test
        @DisplayName("the diagnostic representation names the type and every component")
        void diagnosticRepresentationNamesTheTypeAndEveryComponent() {
            PageMetadata page =
                    PageMetadata.backward(10, TRANSACTION_CURSOR, false, true, LIST_MAP_INDICATOR);

            assertThat(page.toString())
                    .startsWith("PageMetadata[")
                    .contains("pageSize=10")
                    .contains("cursorKey=0000000000000042")
                    .contains("direction=BACKWARD")
                    .contains("hasMorePages=false")
                    .contains("hasPreviousPages=true")
                    .contains("displayedPageNumber=00000007")
                    .endsWith("]");
        }
    }

    @Nested
    @DisplayName("JSON wire contract, mirroring the module's own serialization settings")
    class JsonWireContract {

        @Test
        @DisplayName("a fully populated forward page serialises to the six properties in declaration "
                + "order, values untouched")
        void fullyPopulatedForwardPageSerialisesToSixOrderedProperties()
                throws JsonProcessingException {
            Map<String, Object> wire =
                    wireProperties(
                            PageMetadata.forward(
                                    PageMetadata.CARD_LIST_PAGE_SIZE,
                                    CARD_COMPOSITE_CURSOR,
                                    true,
                                    false,
                                    CARD_MAP_INDICATOR));

            assertThat(wire.keySet())
                    .containsExactly(
                            "pageSize",
                            "cursorKey",
                            "direction",
                            "hasMorePages",
                            "hasPreviousPages",
                            "displayedPageNumber");
            assertThat(wire)
                    .containsEntry("pageSize", 7)
                    .containsEntry("cursorKey", "000000000000004200000000011")
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
                                    PageMetadata.CARD_LIST_PAGE_SIZE, null, true, false, null));

            assertThat(wire).hasSize(4);
            assertThat(wire.keySet())
                    .containsExactly("pageSize", "direction", "hasMorePages", "hasPreviousPages");
            assertThat(wire).doesNotContainKey("cursorKey").doesNotContainKey("displayedPageNumber");
        }

        @Test
        @DisplayName("an unknown inbound property is tolerated, because a client may echo back a "
                + "field this contract does not consume")
        void unknownInboundPropertyIsTolerated() throws JsonProcessingException {
            String inbound =
                    "{\"pageSize\":10,\"cursorKey\":\"0000000000000042\","
                            + "\"direction\":\"BACKWARD\",\"hasMorePages\":false,"
                            + "\"hasPreviousPages\":true,\"displayedPageNumber\":\"00000007\","
                            + "\"screenTitleEcho\":\"unused\"}";

            PageMetadata page = WIRE_MAPPER.readValue(inbound, PageMetadata.class);

            assertThat(page)
                    .isEqualTo(
                            new PageMetadata(
                                    10,
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
                            CARD_COMPOSITE_CURSOR,
                            true,
                            true,
                            CARD_MAP_INDICATOR);

            PageMetadata restored =
                    WIRE_MAPPER.readValue(WIRE_MAPPER.writeValueAsString(original), PageMetadata.class);

            assertThat(restored).isEqualTo(original).isNotSameAs(original);
            assertThat(restored.pageSize()).isEqualTo(7);
            assertThat(restored.cursorKey())
                    .isEqualTo("000000000000004200000000011")
                    .hasSize(27);
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
                                    CARD_COMPOSITE_CURSOR,
                                    true,
                                    false,
                                    CARD_MAP_INDICATOR));
            Map<String, Object> userListWire =
                    wireProperties(
                            PageMetadata.forward(
                                    PageMetadata.USER_LIST_PAGE_SIZE,
                                    USER_CURSOR,
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
        @DisplayName("both text components may be absent, and construction says nothing about it")
        void bothTextComponentsMayBeAbsent() {
            PageMetadata page =
                    new PageMetadata(
                            PageMetadata.CARD_LIST_PAGE_SIZE,
                            null,
                            PageMetadata.PagingDirection.FORWARD,
                            false,
                            false,
                            null);

            assertThat(page.cursorKey()).isNull();
            assertThat(page.displayedPageNumber()).isNull();
        }

        @Test
        @DisplayName("an empty cursor and an empty indicator cross construction untouched")
        void emptyTextComponentsCrossConstructionUntouched() {
            PageMetadata page = PageMetadata.forward(10, "", true, false, "");

            assertThat(page.cursorKey()).isEmpty();
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
            assertThatCode(() -> PageMetadata.forward(10, "** ?? //", false, false, "* *"))
                    .doesNotThrowAnyException();

            PageMetadata page = PageMetadata.forward(10, "** ?? //", false, false, "* *");

            assertThat(page.cursorKey()).isEqualTo("** ?? //");
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
                                            PageMetadata.PagingDirection.BACKWARD,
                                            false,
                                            false,
                                            null))
                    .doesNotThrowAnyException();

            assertThatNullPointerException()
                    .isThrownBy(() -> new PageMetadata(0, "", null, false, false, ""))
                    .withMessage("direction must be supplied explicitly");
        }
    }
}
