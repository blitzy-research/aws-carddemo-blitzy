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
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.api.dto.PageMetadata;
import com.carddemo.api.dto.UserRequest;
import com.carddemo.api.dto.UserResponse;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.BrowseWindow;
import com.carddemo.service.NavigationService;
import com.carddemo.service.ScreenNavigationState;
import com.carddemo.service.UserCommand;
import com.carddemo.service.UserOutcome;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The user-administration boundary: twelve components in, nineteen out, every one a positional copy.
 *
 * <p>Three details are asserted more closely than the rest because each is a parity trap. The displayed page
 * number is text, so its leading zeros must survive the crossing. The ten selection characters are
 * positional, so their order is the row order they were marked in. And the page of rows must not be
 * re-ordered, because a backward page arrives in the order the legacy screen presented after filling its
 * rows downward from the last slot.
 *
 * <p>Provenance: {@code app/cbl/COUSR00C.cbl} through {@code COUSR03C.cbl} and their four mapsets, read as
 * read-only reference at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}.
 */
@DisplayName("UserContractAdapter :: the one crossing between the user wire contract and the service-owned "
        + "pair")
class UserContractAdapterTest {

    /** A populated communication area, so the nested crossing is exercised rather than defaulted. */
    private static final NavigationContext ECHOED = new NavigationContext("CU00", "COADM01C", "CU00",
            "COUSR00C", "ADMIN001", "A", NavigationContext.ProgramContext.REENTER, "000000456",
            "ANN", "B", "SMITH", "00000000011", "Y", "4111111111111111", "CUSRLSTA", "COUSR00");

    /**
     * The identity the filter chain establishes for these turns, deliberately naming the same principal the
     * echoed record names so the positional assertions compare the crossing rather than the reconciliation.
     */
    private static final Authentication IDENTITY = identityOf("ADMIN001", UserType.ADMIN);

    /** The adapter under test, over the real carrier seam it delegates to. */
    private UserContractAdapter adapter;

    /**
     * Builds an established identity carrying the single authority the chain grants for a user type.
     *
     * @param userId the principal name
     * @param userType the type whose declared authority is granted
     * @return an authenticated token the adapter can read identity from
     */
    private static Authentication identityOf(final String userId, final UserType userType) {
        return new TestingAuthenticationToken(userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + userType.name())));
    }

    @BeforeEach
    void setUp() {
        adapter = new UserContractAdapter(new ScreenStateAdapter(new NavigationService()));
    }

    /** A request whose every text component carries a distinct, padding-bearing marker. */
    private static UserRequest request() {
        return new UserRequest("ADMIN001", " USER000", "GIVEN ", " FAMILY", "PASSWORD", "A",
                List.of("", "U", "", "D", "", "", "", "", "", ""), "00000003", "USER0001",
                "USER0010", KeyAction.PFK08, ECHOED);
    }

    /** Builds an outcome over the page, window and findings supplied. */
    private static UserOutcome outcome(final List<UserOutcome.UserRow> rows,
            final BrowseWindow window, final List<ValidationException.FieldError> findings) {
        return new UserOutcome(rows, window, null, "ADMIN001", "GIVEN ", " FAMILY", "A", "CU00",
                "TITLE ONE ", "07/19/22", "COUSR00C", " TITLE TWO", "23:12:33",
                UserOutcome.MSG_LIST_AT_TOP, findings, false, true, false, "USRIDIN", "admin-user-list",
                ScreenNavigationState.empty().withReEntry());
    }

    @Nested
    @DisplayName("the adapter itself")
    final class TheAdapterItself {

        @Test
        @DisplayName("is final and refuses an absent carrier seam, so a half-built boundary cannot exist")
        void isFinalAndRefusesAnAbsentSeam() {
            assertThat(UserContractAdapter.class).isFinal();
            assertThatNullPointerException().isThrownBy(() -> new UserContractAdapter(null))
                    .withMessageContaining("screenStateAdapter");
        }

        @Test
        @DisplayName("refuses an absent request and an absent outcome, because neither is a reachable state")
        void refusesAnAbsentRequestOrOutcome() {
            assertThatNullPointerException().isThrownBy(() -> adapter.toCommand(null, IDENTITY))
                    .withMessageContaining("request");
            assertThatNullPointerException().isThrownBy(() -> adapter.toResponse(null, IDENTITY))
                    .withMessageContaining("outcome");
        }
    }

    @Nested
    @DisplayName("inbound - the transmitted screen becomes the command")
    final class Inbound {

        @Test
        @DisplayName("copies all eleven scalar components positionally, padding and blanks included")
        void copiesEveryScalarComponent() {
            final UserCommand command = adapter.toCommand(request(), IDENTITY);

            assertThat(command.userId()).isEqualTo("ADMIN001");
            assertThat(command.searchUserId()).isEqualTo(" USER000");
            assertThat(command.firstName()).isEqualTo("GIVEN ");
            assertThat(command.lastName()).isEqualTo(" FAMILY");
            assertThat(command.password()).isEqualTo("PASSWORD");
            assertThat(command.userType()).isEqualTo("A");
            assertThat(command.displayedPageNumber())
                    .as("text rather than a number, so the eight leading-zero characters survive")
                    .isEqualTo("00000003");
            assertThat(command.firstUserIdOnPage()).isEqualTo("USER0001");
            assertThat(command.lastUserIdOnPage()).isEqualTo("USER0010");
            assertThat(command.keyAction()).isEqualTo(KeyAction.PFK08);
        }

        @Test
        @DisplayName("keeps the ten selection characters in row order, because a selection belongs to the "
                + "row it was marked on")
        void keepsTheSelectionColumnPositional() {
            assertThat(adapter.toCommand(request(), IDENTITY).rowSelections())
                    .containsExactly("", "U", "", "D", "", "", "", "", "", "");
        }

        @Test
        @DisplayName("carries the echoed communication area across as the service-owned state, all sixteen "
                + "fields of it")
        void carriesTheCommunicationAreaAcross() {
            assertThat(adapter.toCommand(request(), IDENTITY).navigationContext())
                    .isEqualTo(new ScreenNavigationState("CU00", "COADM01C", "CU00", "COUSR00C",
                            "ADMIN001", "A", ScreenNavigationState.ProgramContext.REENTER,
                            "000000456", "ANN", "B", "SMITH", "00000000011", "Y",
                            "4111111111111111", "CUSRLSTA", "COUSR00"));
        }

        @Test
        @DisplayName("hands the authenticated identity to the screen rather than the echoed one, so an "
                + "administrative route cannot be driven under a name the caller typed")
        void handsTheAuthenticatedIdentityToTheScreen() {
            final ScreenNavigationState carried =
                    adapter.toCommand(request(), identityOf("ADMIN002", UserType.ADMIN))
                            .navigationContext();

            assertThat(carried.userId()).isEqualTo("ADMIN002");
            assertThat(carried.userType()).isEqualTo(UserType.ADMIN.getCode());
            assertThat(carried.lastMap()).isEqualTo("CUSRLSTA");
        }

        @Test
        @DisplayName("an absent communication area becomes the empty carried state, and an absent selection "
                + "column an empty one, so a screen never has to null-check what it was handed")
        void absencesBecomeEmptyRatherThanNull() {
            final UserRequest bare = new UserRequest(null, null, null, null, null, null, null, null,
                    null, null, null, null);

            final UserCommand command = adapter.toCommand(bare, IDENTITY);

            // Empty in every member the client could have echoed, and reconciled in the two it could
            // not: an absent record still cannot leave the screen without the identity that is acting.
            assertThat(command.navigationContext())
                    .isEqualTo(ScreenNavigationState.empty()
                            .reconciledWith("ADMIN001", UserType.ADMIN));
            assertThat(command.rowSelections()).isEmpty();
        }
    }

    @Nested
    @DisplayName("outbound - the settled turn becomes the response")
    final class Outbound {

        @Test
        @DisplayName("copies all fifteen scalar and control components positionally, padding included")
        void copiesEveryScalarComponent() {
            final UserResponse response = adapter.toResponse(outcome(List.of(), null, List.of()), IDENTITY);

            assertThat(response.userId()).isEqualTo("ADMIN001");
            assertThat(response.firstName()).isEqualTo("GIVEN ");
            assertThat(response.lastName()).isEqualTo(" FAMILY");
            assertThat(response.userType()).isEqualTo("A");
            assertThat(response.transactionName()).isEqualTo("CU00");
            assertThat(response.title01()).isEqualTo("TITLE ONE ");
            assertThat(response.currentDate()).isEqualTo("07/19/22");
            assertThat(response.programName()).isEqualTo("COUSR00C");
            assertThat(response.title02()).isEqualTo(" TITLE TWO");
            assertThat(response.currentTime()).isEqualTo("23:12:33");
            assertThat(response.message()).isEqualTo(UserResponse.MSG_LIST_AT_TOP);
            assertThat(response.generalError()).isFalse();
            assertThat(response.actionSucceeded()).isTrue();
            assertThat(response.focusScreenFieldId()).isEqualTo("USRIDIN");
            assertThat(response.nextRoute()).isEqualTo("admin-user-list");
        }

        @Test
        @DisplayName("publishes the page in the order the browse settled it, five components per row, "
                + "without re-ordering a backward page into ascending order")
        void publishesThePageInBrowseOrder() {
            final List<UserOutcome.UserRow> descending = List.of(
                    new UserOutcome.UserRow("", "USER0007", "GIVEN7", "FAMILY7", "U"),
                    new UserOutcome.UserRow("U", "USER0004", "GIVEN4", "FAMILY4", "A"),
                    new UserOutcome.UserRow("", "USER0001", "GIVEN1", "FAMILY1", "U"));

            final List<UserResponse.UserRow> published =
                    adapter.toResponse(outcome(descending, null, List.of()), IDENTITY).rows();

            assertThat(published).extracting(UserResponse.UserRow::userId)
                    .containsExactly("USER0007", "USER0004", "USER0001");
            assertThat(published.get(1).selector()).isEqualTo("U");
            assertThat(published.get(1).firstName()).isEqualTo("GIVEN4");
            assertThat(published.get(1).lastName()).isEqualTo("FAMILY4");
            assertThat(published.get(1).userType()).isEqualTo("A");
        }

        @Test
        @DisplayName("carries the browse window back as the wire paging record, keeping the direction the "
                + "browse walked and the operator-facing page indicator's leading zeros")
        void carriesTheBrowseWindowBack() {
            final BrowseWindow window = BrowseWindow.backward(3, "USER0001", "USER0007", true, true,
                    "00000002");

            final PageMetadata published =
                    adapter.toResponse(outcome(List.of(), window, List.of()), IDENTITY).pageMetadata();

            assertThat(published).isNotNull();
            assertThat(published.pageSize()).isEqualTo(3);
            assertThat(published.previousCursorKey()).isEqualTo("USER0001");
            assertThat(published.nextCursorKey()).isEqualTo("USER0007");
            assertThat(published.direction()).isEqualTo(PageMetadata.PagingDirection.BACKWARD);
            assertThat(published.hasMorePages()).isTrue();
            assertThat(published.hasPreviousPages()).isTrue();
            assertThat(published.displayedPageNumber()).isEqualTo("00000002");
        }

        @Test
        @DisplayName("an absent browse window becomes nothing on the wire, so a turn that presented no page "
                + "does not publish an empty one")
        void anAbsentWindowBecomesNothing() {
            assertThat(adapter.toResponse(outcome(List.of(), null, List.of()), IDENTITY).pageMetadata()).isNull();
        }

        @Test
        @DisplayName("translates each finding entry for entry, mapping the two states onto the transport "
                + "vocabulary and keeping the message verbatim")
        void translatesTheFindings() {
            final List<ErrorResponse.FieldError> translated = adapter.toResponse(outcome(List.of(),
                    null, List.of(
                            new ValidationException.FieldError("userId", "USRIDIN",
                                    ValidationException.FieldState.MISSING,
                                    UserOutcome.MSG_ADD_USER_ID_EMPTY),
                            new ValidationException.FieldError("firstName", "FNAME",
                                    ValidationException.FieldState.INVALID,
                                    UserOutcome.MSG_ADD_FIRST_NAME_EMPTY))), IDENTITY).fieldErrors();

            assertThat(translated).hasSize(2);
            assertThat(translated.get(0).fieldName()).isEqualTo("userId");
            assertThat(translated.get(0).screenFieldId()).isEqualTo("USRIDIN");
            assertThat(translated.get(0).state()).isEqualTo(ErrorResponse.FieldState.MISSING);
            assertThat(translated.get(0).message()).isEqualTo(UserResponse.MSG_ADD_USER_ID_EMPTY);
            assertThat(translated.get(1).state()).isEqualTo(ErrorResponse.FieldState.INVALID);
            assertThat(translated.get(1).message()).isEqualTo(UserResponse.MSG_ADD_FIRST_NAME_EMPTY);
        }

        @Test
        @DisplayName("substitutes the empty string for an absent field name or screen identifier, because "
                + "the transport entry refuses a null for either")
        void substitutesTheEmptyStringForAnAbsentIdentity() {
            final List<ErrorResponse.FieldError> translated = adapter.toResponse(outcome(List.of(),
                    null, List.of(new ValidationException.FieldError(null, null,
                            ValidationException.FieldState.INVALID, null))), IDENTITY).fieldErrors();

            assertThat(translated).singleElement().satisfies(entry -> {
                assertThat(entry.fieldName()).isEmpty();
                assertThat(entry.screenFieldId()).isEmpty();
                assertThat(entry.message()).isNull();
            });
        }

        @Test
        @DisplayName("carries the communication area back as the wire record")
        void carriesTheCommunicationAreaBack() {
            assertThat(adapter.toResponse(outcome(List.of(), null, List.of()), IDENTITY).navigationContext())
                    .isEqualTo(new ScreenStateAdapter(new NavigationService())
                            .toNavigationContext(ScreenNavigationState.empty().withReEntry(),
                                    IDENTITY));
        }
    }
}
