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
import com.carddemo.api.dto.UserRequest;
import com.carddemo.api.dto.UserResponse;
import com.carddemo.domain.enums.UserType;
import com.carddemo.exception.ValidationException;
import com.carddemo.service.NavigationService;
import com.carddemo.service.ScreenNavigationState;
import com.carddemo.service.UserCommand;
import com.carddemo.service.UserManagementService;
import com.carddemo.service.UserOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The administrative sign-on-record boundary: the four routes that carry legacy transactions CU00,
 * CU01, CU02 and CU03.
 *
 * <p>This class is a transport boundary and nothing else, so what is asserted here is exactly what a
 * boundary owes: that each route hands the submitted record to the one service method that owns the
 * screen, that it answers the body that service composed without editing it, that it times the turn
 * under the route's own metric so four screens do not accumulate into one indistinguishable series,
 * and that the outcome tag it attaches classifies the turn the way the legacy screen would be read.
 * No screen rule is asserted here, because none lives here - {@link UserManagementService} owns them
 * and its own tests hold them.
 *
 * <p>The outcome vocabulary is the part worth guarding closely. The legacy screens answer a
 * successful action, a rejected one and a merely-presented one over the same terminal path, so the
 * tag is the only thing that separates them in a metric, and the ordering of the three tests matters:
 * a body that both succeeded and carries field errors is a success, because the controller reads the
 * success switch first.
 */
@DisplayName("AdminUserController - the administrative user routes CU00 to CU03")
class AdminUserControllerTest {

    /** A well-formed administrative identifier, eight characters as the record declares. */
    private static final String USER_ID = "ADMIN001";

    /**
     * The identity the filter chain establishes for these turns. All four routes are administrative, so the
     * chain has already granted the administrative authority by the time a turn reaches the boundary, and it
     * is that principal - not a field the caller echoed - that the carried state names.
     */
    private static final Authentication IDENTITY = identityOf("ADMIN001", UserType.ADMIN);

    /**
     * Builds an established identity carrying the single authority the chain grants for a user type.
     *
     * @param userId the principal name
     * @param userType the type whose declared authority is granted
     * @return an authenticated token the boundary can read identity from
     */
    private static Authentication identityOf(final String userId, final UserType userType) {
        return new TestingAuthenticationToken(userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + userType.name())));
    }

    /** The collaborator that owns every screen rule these routes expose. */
    private UserManagementService userManagementService;

    /**
     * The real converter between the wire contract and the service-owned command and outcome.
     *
     * <p>The real one rather than a mock, because it is the crossing under test here: it holds no mutable
     * state, performs a positional copy of twelve components inbound and nineteen outbound, and stubbing it
     * would leave the delegation tests measuring a stub instead of the conversion.
     */
    private UserContractAdapter userContractAdapter;

    /** The registry the four turn timers are registered against. */
    private MeterRegistry meterRegistry;

    /** The controller under test. */
    private AdminUserController controller;

    @BeforeEach
    void setUp() {
        userManagementService = mock(UserManagementService.class);
        userContractAdapter = new UserContractAdapter(new ScreenStateAdapter(new NavigationService()));
        meterRegistry = new SimpleMeterRegistry();
        controller = new AdminUserController(userManagementService, userContractAdapter,
                meterRegistry);
    }

    /**
     * Builds a request carrying only the identifier, which is all three of the non-list screens need.
     *
     * @return the request
     */
    private static UserRequest request() {
        return new UserRequest(USER_ID, null, "GIVEN", "FAMILY", "PASSWORD", "A", List.of(), null,
                null, null, null, NavigationContext.empty());
    }

    /**
     * Builds a response with the two outcome switches set as asked and no field errors.
     *
     * @param actionSucceeded whether the action half of the screen reports success
     * @param generalError whether the screen raised its general error switch
     * @return the response
     */
    private static UserOutcome response(final boolean actionSucceeded, final boolean generalError) {
        return new UserOutcome(null, null, null, USER_ID, "GIVEN", "FAMILY", "A", "CU01", "TITLE ONE",
                "07/19/22", "COUSR01C", "TITLE TWO", "14:23:07", null, null, generalError,
                actionSucceeded, false, "USRIDIN", "admin-user-list", ScreenNavigationState.empty());
    }

    /**
     * Builds a response that carries one field error and neither outcome switch.
     *
     * @return the response
     */
    private static UserOutcome responseWithFieldError() {
        return new UserOutcome(null, null, null, USER_ID, null, null, null, "CU01", null, null, null, null,
                null, null,
                List.of(new ValidationException.FieldError("firstName", "FNAME",
                        ValidationException.FieldState.MISSING, "First Name can NOT be empty...")),
                false, false, false, "FNAME", null, ScreenNavigationState.empty());
    }

    /**
     * Reads the count of a turn timer carrying the given outcome tag.
     *
     * @param metric the timer name
     * @param outcome the expected outcome tag
     * @return how many turns were recorded under that pairing
     */
    private long timed(final String metric, final String outcome) {
        return meterRegistry.get(metric).tag("outcome", outcome).timer().count();
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("no collaborator may be absent, so a misassembled context fails at "
                + "construction rather than on the first request")
        void noCollaboratorMayBeAbsent() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new AdminUserController(null, userContractAdapter,
                            meterRegistry))
                    .withMessageContaining("userManagementService");
            assertThatNullPointerException()
                    .isThrownBy(() -> new AdminUserController(userManagementService, null,
                            meterRegistry))
                    .withMessageContaining("userContractAdapter");
            assertThatNullPointerException()
                    .isThrownBy(() -> new AdminUserController(userManagementService,
                            userContractAdapter, null))
                    .withMessageContaining("meterRegistry");
        }
    }

    @Nested
    @DisplayName("Delegation - each route reaches exactly one screen")
    class Delegation {

        @Test
        @DisplayName("the list route hands the submitted record to the list screen and answers its body")
        void theListRouteReachesTheListScreen() {
            UserRequest submitted = request();
            UserOutcome composed = response(false, false);
            when(userManagementService.listUsers(any())).thenReturn(composed);

            ResponseEntity<UserResponse> answer = controller.listUsers(submitted, IDENTITY);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(answer.getBody()).isEqualTo(userContractAdapter.toResponse(composed, IDENTITY));
            // The command the screen received is the submitted record component for component, so the
            // crossing carried everything and altered nothing on the way in.
            ArgumentCaptor<UserCommand> captor = ArgumentCaptor.forClass(UserCommand.class);
            verify(userManagementService).listUsers(captor.capture());
            assertThat(captor.getValue()).isEqualTo(userContractAdapter.toCommand(submitted, IDENTITY));
            verifyNoMoreInteractions(userManagementService);
        }

        @Test
        @DisplayName("the add route hands the submitted record to the add screen and answers its body")
        void theAddRouteReachesTheAddScreen() {
            UserRequest submitted = request();
            UserOutcome composed = response(true, false);
            when(userManagementService.addUser(any())).thenReturn(composed);

            ResponseEntity<UserResponse> answer = controller.addUser(submitted, IDENTITY);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(answer.getBody()).isEqualTo(userContractAdapter.toResponse(composed, IDENTITY));
            // The command the screen received is the submitted record component for component, so the
            // crossing carried everything and altered nothing on the way in.
            ArgumentCaptor<UserCommand> captor = ArgumentCaptor.forClass(UserCommand.class);
            verify(userManagementService).addUser(captor.capture());
            assertThat(captor.getValue()).isEqualTo(userContractAdapter.toCommand(submitted, IDENTITY));
            verifyNoMoreInteractions(userManagementService);
        }

        @Test
        @DisplayName("the update route hands the submitted record to the update screen and answers "
                + "its body")
        void theUpdateRouteReachesTheUpdateScreen() {
            UserRequest submitted = request();
            UserOutcome composed = response(true, false);
            when(userManagementService.updateUser(any())).thenReturn(composed);

            ResponseEntity<UserResponse> answer = controller.updateUser(submitted, IDENTITY);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(answer.getBody()).isEqualTo(userContractAdapter.toResponse(composed, IDENTITY));
            // The command the screen received is the submitted record component for component, so the
            // crossing carried everything and altered nothing on the way in.
            ArgumentCaptor<UserCommand> captor = ArgumentCaptor.forClass(UserCommand.class);
            verify(userManagementService).updateUser(captor.capture());
            assertThat(captor.getValue()).isEqualTo(userContractAdapter.toCommand(submitted, IDENTITY));
            verifyNoMoreInteractions(userManagementService);
        }

        @Test
        @DisplayName("the delete route hands the submitted record to the delete screen and answers "
                + "its body")
        void theDeleteRouteReachesTheDeleteScreen() {
            UserRequest submitted = request();
            UserOutcome composed = response(true, false);
            when(userManagementService.deleteUser(any())).thenReturn(composed);

            ResponseEntity<UserResponse> answer = controller.deleteUser(submitted, IDENTITY);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(answer.getBody()).isEqualTo(userContractAdapter.toResponse(composed, IDENTITY));
            // The command the screen received is the submitted record component for component, so the
            // crossing carried everything and altered nothing on the way in.
            ArgumentCaptor<UserCommand> captor = ArgumentCaptor.forClass(UserCommand.class);
            verify(userManagementService).deleteUser(captor.capture());
            assertThat(captor.getValue()).isEqualTo(userContractAdapter.toCommand(submitted, IDENTITY));
            verifyNoMoreInteractions(userManagementService);
        }

        @Test
        @DisplayName("the boundary edits nothing: every component of the body answered is the value the "
                + "screen composed, so no screen value can be rewritten here")
        void theBoundaryEditsNothing() {
            UserOutcome composed = responseWithFieldError();
            when(userManagementService.addUser(any())).thenReturn(composed);

            UserResponse answered = controller.addUser(request(), IDENTITY).getBody();

            assertThat(answered).isNotNull();
            // Equality across the whole conversion rather than instance identity, which is the stronger
            // claim now that a crossing exists: identity would be preserved by a conversion that dropped
            // a component, whereas this compares all nineteen.
            assertThat(answered).isEqualTo(userContractAdapter.toResponse(composed, IDENTITY));
            assertThat(answered.focusScreenFieldId()).isEqualTo("FNAME");
            assertThat(answered.fieldErrors()).singleElement().satisfies(error -> {
                assertThat(error.fieldName()).isEqualTo("firstName");
                assertThat(error.screenFieldId()).isEqualTo("FNAME");
                assertThat(error.state()).isEqualTo(ErrorResponse.FieldState.MISSING);
                assertThat(error.message()).isEqualTo("First Name can NOT be empty...");
            });
        }
    }

    @Nested
    @DisplayName("Turn timing - four screens, four series")
    class TurnTiming {

        @Test
        @DisplayName("each route records its turn under its own metric, so the four screens never "
                + "accumulate into one series")
        void eachRouteRecordsUnderItsOwnMetric() {
            when(userManagementService.listUsers(any())).thenReturn(response(false, false));
            when(userManagementService.addUser(any())).thenReturn(response(true, false));
            when(userManagementService.updateUser(any())).thenReturn(response(true, false));
            when(userManagementService.deleteUser(any())).thenReturn(response(true, false));

            controller.listUsers(request(), IDENTITY);
            controller.addUser(request(), IDENTITY);
            controller.updateUser(request(), IDENTITY);
            controller.deleteUser(request(), IDENTITY);

            assertThat(timed("carddemo.online.userlist.turn", "presented")).isEqualTo(1L);
            assertThat(timed("carddemo.online.useradd.turn", "succeeded")).isEqualTo(1L);
            assertThat(timed("carddemo.online.userupdate.turn", "succeeded")).isEqualTo(1L);
            assertThat(timed("carddemo.online.userdelete.turn", "succeeded")).isEqualTo(1L);
        }

        @Test
        @DisplayName("a completed action is tagged as having succeeded")
        void aCompletedActionIsTaggedAsSucceeded() {
            when(userManagementService.addUser(any())).thenReturn(response(true, false));

            controller.addUser(request(), IDENTITY);

            assertThat(timed("carddemo.online.useradd.turn", "succeeded")).isEqualTo(1L);
        }

        @Test
        @DisplayName("a turn that raised the general error switch is tagged as rejected")
        void aGeneralErrorIsTaggedAsRejected() {
            when(userManagementService.addUser(any())).thenReturn(response(false, true));

            controller.addUser(request(), IDENTITY);

            assertThat(timed("carddemo.online.useradd.turn", "rejected")).isEqualTo(1L);
        }

        @Test
        @DisplayName("a turn carrying only field errors is tagged as rejected, so a field-level "
                + "rejection is not read as a plain presentation")
        void fieldErrorsAloneAreTaggedAsRejected() {
            when(userManagementService.updateUser(any())).thenReturn(responseWithFieldError());

            controller.updateUser(request(), IDENTITY);

            assertThat(timed("carddemo.online.userupdate.turn", "rejected")).isEqualTo(1L);
        }

        @Test
        @DisplayName("a turn that neither succeeded nor failed is tagged as merely presented, which "
                + "is what a first entry on the list screen is")
        void aPlainTurnIsTaggedAsPresented() {
            when(userManagementService.listUsers(any())).thenReturn(response(false, false));

            controller.listUsers(request(), IDENTITY);

            assertThat(timed("carddemo.online.userlist.turn", "presented")).isEqualTo(1L);
        }

        @Test
        @DisplayName("success is read before the error switches, so a body reporting both is timed "
                + "as a success")
        void successIsReadBeforeTheErrorSwitches() {
            when(userManagementService.deleteUser(any())).thenReturn(response(true, true));

            controller.deleteUser(request(), IDENTITY);

            assertThat(timed("carddemo.online.userdelete.turn", "succeeded")).isEqualTo(1L);
        }

        @Test
        @DisplayName("repeated turns accumulate in one series rather than replacing each other")
        void repeatedTurnsAccumulate() {
            when(userManagementService.listUsers(any())).thenReturn(response(false, false));

            controller.listUsers(request(), IDENTITY);
            controller.listUsers(request(), IDENTITY);
            controller.listUsers(request(), IDENTITY);

            assertThat(timed("carddemo.online.userlist.turn", "presented")).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("The published HTTP contract")
    class PublishedContract {

        @Test
        @DisplayName("the four subpaths hang off one administrative prefix")
        void theFourSubpathsHangOffOneAdministrativePrefix() {
            assertThat(AdminUserController.USERS_PATH).isEqualTo("/api/admin/users");
            assertThat(AdminUserController.LIST_SUBPATH).isEqualTo("/list");
            assertThat(AdminUserController.ADD_SUBPATH).isEqualTo("/add");
            assertThat(AdminUserController.UPDATE_SUBPATH).isEqualTo("/update");
            assertThat(AdminUserController.DELETE_SUBPATH).isEqualTo("/delete");
            assertThat(AdminUserController.class.getAnnotation(RequestMapping.class).value())
                    .containsExactly(AdminUserController.USERS_PATH);
        }

        /**
         * The published description states the credential's three states on the update operation.
         *
         * <p>The service distinguishes three cases where the fixed-width map could express only two: an
         * omitted component leaves the credential unchanged, a present-but-blank one is the legacy's own
         * empty-item fault, and a populated one is hashed. Each of the three is already proved as
         * behaviour elsewhere in this module; what this asserts is that the <em>contract a client reads</em>
         * says so, because a description that calls the credential simply "required" tells an
         * administrator they must retype a credential they may not know in order to correct a surname.
         */
        @Test
        @DisplayName("the update operation documents the credential's three states rather than calling "
                + "it required")
        void theUpdateOperationDocumentsTheThreeStateCredentialContract() throws Exception {
            Method handler = AdminUserController.class.getDeclaredMethod("updateUser",
                    UserRequest.class, Authentication.class);
            String description = handler.getAnnotation(Operation.class).description();

            assertThat(description)
                    .as("the state that a bare \"required\" would misdescribe")
                    .contains("CONDITIONALLY required");
            assertThat(description)
                    .as("omitted leaves the stored credential alone")
                    .contains("omit the component entirely to leave the credential unchanged");
            assertThat(description)
                    .as("present but blank is the legacy empty-item fault, not an unchanged credential")
                    .contains("present but blank");
            assertThat(description)
                    .as("populated replaces the stored digest")
                    .contains("populated and it is hashed");
            assertThat(description)
                    .as("and the add operation is contrasted, since it genuinely does require one")
                    .contains("add operation differs and requires it outright");
        }

        /**
         * The add operation is unaffected: a record being created has no digest to carry forward.
         */
        @Test
        @DisplayName("the add operation still documents the credential as required outright")
        void theAddOperationDocumentsTheCredentialAsRequired() throws Exception {
            Method handler = AdminUserController.class.getDeclaredMethod("addUser",
                    UserRequest.class, Authentication.class);

            assertThat(handler.getAnnotation(Operation.class)).isNotNull();
            assertThat(handler.getAnnotation(Operation.class).description())
                    .doesNotContain("CONDITIONALLY required");
        }

        @Test
        @DisplayName("every route is a JSON POST, because each carries a submitted screen record "
                + "rather than a query")
        void everyRouteIsAJsonPost() throws Exception {
            for (String method : List.of("listUsers", "addUser", "updateUser", "deleteUser")) {
                Method handler = AdminUserController.class.getDeclaredMethod(method,
                        UserRequest.class, Authentication.class);
                PostMapping mapping = handler.getAnnotation(PostMapping.class);
                assertThat(mapping).as("%s must be a POST route", method).isNotNull();
                assertThat(mapping.consumes()).as("%s consumes", method)
                        .containsExactly(MediaType.APPLICATION_JSON_VALUE);
                assertThat(mapping.produces()).as("%s produces", method)
                        .containsExactly(MediaType.APPLICATION_JSON_VALUE);
            }
        }

        @Test
        @DisplayName("a submitted record travels the real servlet path and answers the composed body")
        void aSubmittedRecordTravelsTheRealServletPath() throws Exception {
            when(userManagementService.listUsers(any())).thenReturn(response(false, false));
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();

            mockMvc.perform(post(AdminUserController.USERS_PATH + AdminUserController.LIST_SUBPATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"searchUserId\":\"ADMIN001\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(USER_ID))
                    .andExpect(jsonPath("$.transactionName").value("CU01"));

            assertThat(timed("carddemo.online.userlist.turn", "presented")).isEqualTo(1L);
        }
    }
}
