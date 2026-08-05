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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BrowseWindow}, one assembled page of a cursor-based browse as a type the service
 * layer owns.
 *
 * <p><strong>What this file is really guarding.</strong> That both boundary cursors are carried
 * independently, because a page reached in one direction can still be walked out of in the other and only
 * the service that assembled the page knows either key; that the direction is explicit with no default,
 * because the legacy programs always branch on an attention key; that the page size is accepted as given so
 * one carrier serves screens of seven and ten rows and a partial page stays representable; that the three
 * legacy row counts stay separately declared even where two coincide; and that the diagnostic rendering
 * withholds both cursors, because on the card-list browse the record key <em>is</em> the card number.
 *
 * <p>A pure unit test: no Spring context, no connection, no container.
 *
 * <p>Provenance: {@code app/cbl/COCRDLIC.cbl}, {@code app/cbl/COTRN00C.cbl} and
 * {@code app/cbl/COUSR00C.cbl}, read as read-only reference at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @since 1.0.0
 */
@DisplayName("BrowseWindow :: one assembled page, owned by the service layer")
final class BrowseWindowTest {

    /** The seven components, in declaration order. */
    private static final List<String> COMPONENT_NAMES = List.of(
            "pageSize", "previousCursorKey", "nextCursorKey", "direction", "hasMorePages",
            "hasPreviousPages", "displayedPageNumber");

    /** A first-row cursor: on the card-list browse the record key is the card number in full. */
    private static final String FIRST_KEY = "4111111111111111";

    /** A last-row cursor. */
    private static final String LAST_KEY = "4111111111111199";

    @Nested
    @DisplayName("The carried shape")
    class TheCarriedShape {

        @Test
        @DisplayName("is exactly the seven paging components, in declaration order")
        void isExactlyTheSevenComponentsInOrder() {
            final List<String> declared = Arrays.stream(BrowseWindow.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(COMPONENT_NAMES);
        }

        @Test
        @DisplayName("publishes the three legacy row counts separately, and a ceiling and two widths, so no "
                + "screen inherits another screen's shape")
        void publishesTheThreeRowCountsSeparately() {
            assertThat(BrowseWindow.CARD_LIST_PAGE_SIZE).isEqualTo(7);
            assertThat(BrowseWindow.TRANSACTION_LIST_PAGE_SIZE).isEqualTo(10);
            assertThat(BrowseWindow.USER_LIST_PAGE_SIZE).isEqualTo(10);
            assertThat(BrowseWindow.LARGEST_SCREEN_PAGE_SIZE).isEqualTo(10);
            assertThat(BrowseWindow.CURSOR_KEY_MAX_LENGTH).isEqualTo(16);
            assertThat(BrowseWindow.DISPLAYED_PAGE_NUMBER_MAX_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("refuses an absent direction, because the legacy always branches on an explicit "
                + "attention key and this carrier has no default")
        void refusesAnAbsentDirection() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new BrowseWindow(7, FIRST_KEY, LAST_KEY, null, true, false, "1"))
                    .withMessageContaining("direction");
        }

        @Test
        @DisplayName("accepts a page size as given, so a partial page and a single-row page stay "
                + "representable and one carrier serves seven and ten rows alike")
        void acceptsAPageSizeAsGiven() {
            assertThat(BrowseWindow.forward(1, FIRST_KEY, FIRST_KEY, false, false, "1").pageSize())
                    .isEqualTo(1);
            assertThat(BrowseWindow.forward(0, null, null, false, false, null).pageSize()).isZero();
            assertThat(BrowseWindow.forward(7, FIRST_KEY, LAST_KEY, true, false, "1").pageSize())
                    .isEqualTo(BrowseWindow.CARD_LIST_PAGE_SIZE);
        }

        @Test
        @DisplayName("carries both cursors and the page indicator exactly as supplied, padding and leading "
                + "zeros included, because the indicator is what the screen displayed")
        void carriesCursorsAndIndicatorExactly() {
            final BrowseWindow window =
                    BrowseWindow.forward(10, " " + FIRST_KEY, LAST_KEY + " ", true, true, "00000002");

            assertThat(window.previousCursorKey()).isEqualTo(" " + FIRST_KEY);
            assertThat(window.nextCursorKey()).isEqualTo(LAST_KEY + " ");
            assertThat(window.displayedPageNumber()).isEqualTo("00000002");
        }
    }

    @Nested
    @DisplayName("The two direction factories")
    class TheTwoDirectionFactories {

        @Test
        @DisplayName("differ in the direction they set and in nothing else, so a page's own values are "
                + "never re-derived from its direction")
        void differOnlyInTheDirectionTheySet() {
            final BrowseWindow forward =
                    BrowseWindow.forward(10, FIRST_KEY, LAST_KEY, true, true, "00000002");
            final BrowseWindow backward =
                    BrowseWindow.backward(10, FIRST_KEY, LAST_KEY, true, true, "00000002");

            assertThat(forward.direction()).isEqualTo(BrowseWindow.PagingDirection.FORWARD);
            assertThat(backward.direction()).isEqualTo(BrowseWindow.PagingDirection.BACKWARD);
            assertThat(forward.pageSize()).isEqualTo(backward.pageSize());
            assertThat(forward.previousCursorKey()).isEqualTo(backward.previousCursorKey());
            assertThat(forward.nextCursorKey()).isEqualTo(backward.nextCursorKey());
            assertThat(forward.hasMorePages()).isEqualTo(backward.hasMorePages());
            assertThat(forward.hasPreviousPages()).isEqualTo(backward.hasPreviousPages());
            assertThat(forward.displayedPageNumber()).isEqualTo(backward.displayedPageNumber());
        }

        @Test
        @DisplayName("carry both boundary cursors in each direction, because a page reached one way can "
                + "still be walked out of the other")
        void carryBothCursorsInEachDirection() {
            assertThat(BrowseWindow.backward(7, FIRST_KEY, LAST_KEY, false, true, null))
                    .satisfies(window -> {
                        assertThat(window.previousCursorKey()).isEqualTo(FIRST_KEY);
                        assertThat(window.nextCursorKey()).isEqualTo(LAST_KEY);
                    });
        }

        @Test
        @DisplayName("declare exactly two directions, one per legacy browse verb, with no unknown constant")
        void declareExactlyTwoDirections() {
            assertThat(BrowseWindow.PagingDirection.values())
                    .containsExactly(BrowseWindow.PagingDirection.FORWARD,
                            BrowseWindow.PagingDirection.BACKWARD);
        }
    }

    @Nested
    @DisplayName("The inbound cursor request")
    class TheInboundCursorRequest {

        @Test
        @DisplayName("carries strictly less than an assembled page - two keys and a direction - because a "
                + "submission cannot predict a row count or what lies beyond the page")
        void carriesStrictlyLessThanAnAssembledPage() {
            final List<String> declared =
                    Arrays.stream(BrowseWindow.CursorRequest.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared)
                    .containsExactly("previousCursorKey", "nextCursorKey", "direction");
        }

        @Test
        @DisplayName("permits both keys and the direction to be absent, because the first turn of a browse "
                + "has nothing to resume from")
        void permitsEverythingToBeAbsent() {
            final BrowseWindow.CursorRequest empty =
                    new BrowseWindow.CursorRequest(null, null, null);

            assertThat(empty.previousCursorKey()).isNull();
            assertThat(empty.nextCursorKey()).isNull();
            assertThat(empty.direction()).isNull();
        }

        @Test
        @DisplayName("withholds both keys from its rendering and keeps the direction, which is not "
                + "sensitive")
        void withholdsBothKeysFromItsRendering() {
            final String rendered =
                    new BrowseWindow.CursorRequest(FIRST_KEY, LAST_KEY,
                            BrowseWindow.PagingDirection.BACKWARD).toString();

            assertThat(rendered)
                    .doesNotContain(FIRST_KEY)
                    .doesNotContain(LAST_KEY)
                    .contains("CursorRequest[")
                    .contains("direction=BACKWARD");
        }
    }

    @Nested
    @DisplayName("The diagnostic rendering")
    class TheDiagnosticRendering {

        @Test
        @DisplayName("withholds both boundary cursors and keeps the paging state, which discloses nothing "
                + "about a cardholder")
        void withholdsBothBoundaryCursors() {
            final String rendered =
                    BrowseWindow.forward(7, FIRST_KEY, LAST_KEY, true, false, "00000001").toString();

            assertThat(rendered)
                    .doesNotContain(FIRST_KEY)
                    .doesNotContain(LAST_KEY)
                    .contains("BrowseWindow[")
                    .contains("pageSize=7")
                    .contains("direction=FORWARD")
                    .contains("hasMorePages=true")
                    .contains("hasPreviousPages=false")
                    .contains("displayedPageNumber=00000001");
        }

        @Test
        @DisplayName("keeps equality and hashing over both cursors, because a browse cannot be resumed "
                + "without them")
        void keepsEqualityOverBothCursors() {
            final BrowseWindow first = BrowseWindow.forward(7, FIRST_KEY, LAST_KEY, true, false, "1");
            final BrowseWindow same = BrowseWindow.forward(7, FIRST_KEY, LAST_KEY, true, false, "1");
            final BrowseWindow differentKey =
                    BrowseWindow.forward(7, FIRST_KEY, FIRST_KEY, true, false, "1");

            assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(first).isNotEqualTo(differentKey);
        }
    }
}
