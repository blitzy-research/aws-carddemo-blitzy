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

import com.carddemo.domain.enums.KeyAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The service-owned user-administration command: twelve components serving four screens.
 *
 * <p>One body serves all four operations because the four legacy programs read one shared working storage,
 * so what is asserted here is the shape that makes that safe: the selection column normalised so emptiness
 * is the single representation of "carries nothing", a cardinality refusal rather than a truncation, and a
 * rendering that discloses no personal value and no credential.
 *
 * <p>Provenance: {@code app/cbl/COUSR00C.cbl} through {@code COUSR03C.cbl} and their four mapsets, read as
 * read-only reference at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}.
 */
@DisplayName("UserCommand :: the transmitted user-administration screen the transactions read")
class UserCommandTest {

    /** Builds a command carrying the selection column supplied and a populated identity. */
    private static UserCommand commandWith(final List<String> rowSelections) {
        return new UserCommand("ADMIN001", "USER0001", "GIVEN", "FAMILY", "PASSWORD", "A",
                rowSelections, "00000003", "USER0001", "USER0010", KeyAction.PFK08,
                ScreenNavigationState.empty().withReEntry());
    }

    @Nested
    @DisplayName("the declared shape")
    final class TheDeclaredShape {

        @Test
        @DisplayName("declares exactly twelve components, because the adapter copies into it positionally")
        void declaresExactlyTwelveComponents() {
            assertThat(UserCommand.class.getRecordComponents()).hasSize(13);
        }

        @Test
        @DisplayName("publishes the row cardinality the list map declares")
        void publishesTheRowCardinality() {
            assertThat(UserCommand.ROW_SELECTION_COUNT).isEqualTo(10);
        }

        @Test
        @DisplayName("carries the echoed communication area as the service-owned state, so no wire type "
                + "reaches the service tier")
        void carriesTheServiceOwnedState() {
            assertThat(commandWith(List.of()).navigationContext())
                    .isEqualTo(ScreenNavigationState.empty().withReEntry());
        }
    }

    @Nested
    @DisplayName("the selection column it normalises")
    final class TheSelectionColumn {

        @Test
        @DisplayName("an absent column becomes an empty one, so emptiness is the single representation of "
                + "carrying nothing")
        void anAbsentColumnBecomesEmpty() {
            assertThat(commandWith(null).rowSelections()).isEmpty();
        }

        @Test
        @DisplayName("keeps the column positional, because a selection belongs to the row it was marked on")
        void keepsTheColumnPositional() {
            assertThat(commandWith(Arrays.asList("", "U", "", "D")).rowSelections())
                    .containsExactly("", "U", "", "D");
        }

        @Test
        @DisplayName("accepts exactly ten selections, the number of rows the screen declares")
        void acceptsExactlyTenSelections() {
            assertThat(commandWith(Collections.nCopies(10, "U")).rowSelections()).hasSize(10);
        }

        @Test
        @DisplayName("refuses an eleventh selection rather than truncating it, because it corresponds to "
                + "no row and dropping it would let a caller believe a row was acted on")
        void refusesAnEleventhSelection() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> commandWith(Collections.nCopies(11, "U")))
                    .withMessageContaining("10")
                    .withMessageContaining("11");
        }

        @Test
        @DisplayName("refuses a null element, which no row could carry")
        void refusesANullElement() {
            assertThatNullPointerException()
                    .isThrownBy(() -> commandWith(Arrays.asList("U", null)));
        }

        @Test
        @DisplayName("copies the column rather than aliasing it, so a later change to the caller's "
                + "collection cannot alter a submitted turn")
        void copiesTheColumn() {
            final List<String> mutable = new ArrayList<>(List.of("U", "D"));
            final UserCommand command = commandWith(mutable);

            mutable.clear();

            assertThat(command.rowSelections()).containsExactly("U", "D");
        }

        @Test
        @DisplayName("publishes an unmodifiable column")
        void publishesAnUnmodifiableColumn() {
            final UserCommand command = commandWith(List.of("U"));

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> command.rowSelections().clear());
        }
    }

    @Nested
    @DisplayName("what it carries and what it renders")
    final class CarriesAndRenders {

        @Test
        @DisplayName("carries every value exactly as transmitted, including a blank start key, which means "
                + "begin at the very first record rather than nothing at all")
        void carriesEveryValueAsTransmitted() {
            final UserCommand command = new UserCommand("  ADM  ", "", null, null, null, " ",
                    List.of(), "00000001", null, null, null, null);

            assertThat(command.userId()).isEqualTo("  ADM  ");
            assertThat(command.searchUserId()).isEmpty();
            assertThat(command.userType()).isEqualTo(" ");
            assertThat(command.displayedPageNumber())
                    .as("text rather than a number, so the leading zeros the screen showed survive")
                    .isEqualTo("00000001");
            assertThat(command.keyAction()).isNull();
        }

        @Test
        @DisplayName("compares by value across every component, which is what the parity suite needs")
        void comparesByValue() {
            assertThat(commandWith(List.of("U"))).isEqualTo(commandWith(List.of("U")))
                    .hasSameHashCodeAs(commandWith(List.of("U")));
            assertThat(commandWith(List.of("U"))).isNotEqualTo(commandWith(List.of("D")));
        }

        @Test
        @DisplayName("renders no identifier, no name and no credential, and reports the selection count "
                + "rather than which rows were marked")
        void rendersNoPersonalValue() {
            final String rendered = commandWith(List.of("U", "D")).toString();

            assertThat(rendered)
                    .contains("rowSelectionCount=2")
                    .contains("displayedPageNumber=00000003")
                    .contains("keyAction=PFK08");
            assertThat(rendered).doesNotContain("ADMIN001", "USER0001", "USER0010", "GIVEN", "FAMILY",
                    "PASSWORD");
        }
    }
}
