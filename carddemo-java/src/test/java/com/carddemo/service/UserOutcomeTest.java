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

import com.carddemo.exception.ValidationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The service-owned user-administration outcome: twenty-one components and the four screens' own texts.
 *
 * <p>Two cardinality refusals carry the weight here. A page longer than the screen has rows corresponds to
 * slots that do not exist, and a page longer than the accompanying browse window declares would contradict
 * the window the same turn assembled - so both are refusals rather than truncations. The rendering discloses
 * no identifier and no name, and reports counts instead of contents.
 *
 * <p>Provenance: {@code app/cbl/COUSR00C.cbl} through {@code COUSR03C.cbl} and their four mapsets, read as
 * read-only reference at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}.
 */
@DisplayName("UserOutcome :: the settled user-administration turn the transactions produce")
class UserOutcomeTest {

    /** One presented row. */
    private static UserOutcome.UserRow row(final String userId) {
        return new UserOutcome.UserRow("", userId, "GIVEN", "FAMILY", "A");
    }

    /** Builds an outcome over the page, window and findings supplied. */
    private static UserOutcome outcomeWith(final List<UserOutcome.UserRow> rows,
            final BrowseWindow window, final List<ValidationException.FieldError> fieldErrors) {
        return new UserOutcome(rows, window, null, "ADMIN001", "GIVEN", "FAMILY", "A", "CU00", "TITLE ONE",
                "07/19/22", "COUSR00C", "TITLE TWO", "23:12:33", UserOutcome.MSG_LIST_AT_TOP,
                fieldErrors, false, false, false, "USRIDIN", "admin-user-list",
                ScreenNavigationState.empty());
    }

    @Nested
    @DisplayName("the declared shape")
    final class TheDeclaredShape {

        @Test
        @DisplayName("declares exactly twenty-one components, because the adapter copies out of it "
                + "positionally")
        void declaresExactlyTwentyOneComponents() {
            assertThat(UserOutcome.class.getRecordComponents()).hasSize(21);
        }

        @Test
        @DisplayName("declares exactly five per-row components, the five items the list map carries")
        void declaresFivePerRowComponents() {
            assertThat(UserOutcome.UserRow.class.getRecordComponents()).hasSize(5);
        }

        @Test
        @DisplayName("carries the browse window and the communication area as the service-owned types, so "
                + "no wire type reaches the service tier")
        void carriesOnlyServiceOwnedCarriers() {
            final BrowseWindow window = BrowseWindow.forward(1, "USER0001", "USER0001", false, false,
                    "00000001");

            assertThat(outcomeWith(List.of(row("USER0001")), window, List.of()).pageMetadata())
                    .isEqualTo(window);
            assertThat(outcomeWith(List.of(), null, List.of()).navigationContext())
                    .isEqualTo(ScreenNavigationState.empty());
        }

        @Test
        @DisplayName("publishes the four screens' own message texts, each exactly as its program emits it")
        void publishesTheScreenTexts() {
            assertThat(UserOutcome.MSG_LIST_INVALID_SELECTION)
                    .isEqualTo("Invalid selection. Valid values are U and D");
            assertThat(UserOutcome.MSG_LIST_AT_TOP).isEqualTo("You are at the top of the page...");
            assertThat(UserOutcome.MSG_ADD_SUCCESS_PREFIX).isEqualTo("User ");
            assertThat(UserOutcome.MSG_ADD_SUCCESS_SUFFIX).isEqualTo(" has been added ...");
            assertThat(UserOutcome.MSG_UPDATE_SUCCESS_SUFFIX).isEqualTo(" has been updated ...");
            assertThat(UserOutcome.MSG_DELETE_SUCCESS_SUFFIX).isEqualTo(" has been deleted ...");
            assertThat(UserOutcome.MSG_UPDATE_PRESS_PF5)
                    .isEqualTo("Press PF5 key to save your updates ...");
            assertThat(UserOutcome.MSG_DELETE_PRESS_PF5)
                    .isEqualTo("Press PF5 key to delete this user ...");
        }

        @Test
        @DisplayName("declares the three same-text identifier messages separately, because each belongs to "
                + "a different program and collapsing them would lose the correspondence the matrix records")
        void declaresTheThreeSameTextMessagesSeparately() {
            assertThat(UserOutcome.MSG_ADD_USER_ID_EMPTY).isEqualTo("User ID can NOT be empty...");
            assertThat(UserOutcome.MSG_UPDATE_USER_ID_EMPTY)
                    .isEqualTo(UserOutcome.MSG_ADD_USER_ID_EMPTY);
            assertThat(UserOutcome.MSG_DELETE_USER_ID_EMPTY)
                    .isEqualTo(UserOutcome.MSG_ADD_USER_ID_EMPTY);
        }
    }

    @Nested
    @DisplayName("the page it normalises and bounds")
    final class ThePage {

        @Test
        @DisplayName("an absent page and an absent finding list both become empty ones")
        void absentCollectionsBecomeEmpty() {
            final UserOutcome outcome = outcomeWith(null, null, null);

            assertThat(outcome.rows()).isEmpty();
            assertThat(outcome.fieldErrors()).isEmpty();
            assertThat(outcome.hasRows()).isFalse();
            assertThat(outcome.hasFieldErrors()).isFalse();
        }

        @Test
        @DisplayName("keeps the page in the order the browse settled it, which for a backward page is the "
                + "order the screen presented after filling its rows downward")
        void keepsThePageInBrowseOrder() {
            final UserOutcome outcome = outcomeWith(
                    List.of(row("USER0007"), row("USER0004"), row("USER0001")), null, List.of());

            assertThat(outcome.rows()).extracting(UserOutcome.UserRow::userId)
                    .containsExactly("USER0007", "USER0004", "USER0001");
            assertThat(outcome.hasRows()).isTrue();
        }

        @Test
        @DisplayName("accepts exactly ten rows, the number of slots the list screen presents")
        void acceptsExactlyTenRows() {
            assertThat(outcomeWith(Collections.nCopies(10, row("USER0001")), null, List.of()).rows())
                    .hasSize(10);
        }

        @Test
        @DisplayName("refuses an eleventh row, which corresponds to no slot on the screen")
        void refusesAnEleventhRow() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> outcomeWith(Collections.nCopies(11, row("USER0001")), null,
                            List.of()))
                    .withMessageContaining("10")
                    .withMessageContaining("11");
        }

        @Test
        @DisplayName("refuses a page longer than the accompanying window declares, because that would "
                + "contradict the window the same turn assembled")
        void refusesAPageLongerThanTheWindowDeclares() {
            final BrowseWindow twoRowWindow = BrowseWindow.forward(2, "USER0001", "USER0002", true,
                    false, "00000001");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> outcomeWith(
                            List.of(row("USER0001"), row("USER0002"), row("USER0003")), twoRowWindow,
                            List.of()))
                    .withMessageContaining("2")
                    .withMessageContaining("3");
        }

        @Test
        @DisplayName("copies both collections rather than aliasing them, and publishes them unmodifiable")
        void copiesAndPublishesUnmodifiable() {
            final List<UserOutcome.UserRow> mutable = new ArrayList<>(List.of(row("USER0001")));
            final UserOutcome outcome = outcomeWith(mutable, null, List.of());

            mutable.clear();

            assertThat(outcome.rows()).hasSize(1);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> outcome.rows().clear());
        }
    }

    @Nested
    @DisplayName("rendering")
    final class Rendering {

        @Test
        @DisplayName("renders counts and control state, and no identifier, name or message text")
        void rendersCountsAndControlStateOnly() {
            final String rendered = outcomeWith(List.of(row("USER0001"), row("USER0002")), null,
                    List.of(new ValidationException.FieldError("userId", "USRIDIN",
                            ValidationException.FieldState.MISSING,
                            UserOutcome.MSG_UPDATE_USER_ID_EMPTY))).toString();

            assertThat(rendered)
                    .startsWith("UserOutcome[rowCount=2")
                    .contains("fieldErrorCount=1")
                    .contains("transactionName=CU00")
                    .contains("programName=COUSR00C")
                    .contains("focusScreenFieldId=USRIDIN")
                    .contains("nextRoute=admin-user-list")
                    .contains("message=***REDACTED***");
            assertThat(rendered).doesNotContain("ADMIN001", "USER0001", "USER0002", "GIVEN", "FAMILY");
        }

        @Test
        @DisplayName("a row renders its selection character and none of the four values it displays")
        void aRowRendersItsSelectionOnly() {
            final String rendered = new UserOutcome.UserRow("U", "USER0001", "GIVEN", "FAMILY", "A")
                    .toString();

            assertThat(rendered).startsWith("UserRow[selector=U");
            assertThat(rendered).doesNotContain("USER0001", "GIVEN", "FAMILY");
        }
    }
}
