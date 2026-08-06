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

import com.carddemo.api.dto.NavigationContext;
import com.carddemo.api.dto.SignOnResponse;
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.AuthenticationService;
import com.carddemo.service.MessageCatalogService;
import com.carddemo.service.NavigationService;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit test for {@link SignOnContractAdapter}, the one place a sign-on decision becomes wire text.
 *
 * <p><strong>What this proves.</strong> That all seven message-bearing outcomes carry their frozen
 * literal exactly, that the eighth carries none, and - the property the trust boundary rests on - that the
 * navigation state an admitted turn establishes is built from what the service resolved and from nothing
 * the client sent. Sign-on is the turn that establishes identity, so there is no inbound identity here to
 * echo; this test pins that there is no path by which one could appear.
 *
 * <p>Provenance: {@code app/cbl/COSGN00C.cbl}, read as read-only reference at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.
 */
@DisplayName("SignOnContractAdapter :: a decision becomes the screen's own text")
class SignOnContractAdapterTest {

    /** A folded identifier an admitted turn carries. */
    private static final String USER_ID = "ADMIN001";

    /** The header the service rendered, carried through unchanged. */
    private static final String DATE = "07/19/22";

    /** The header time the service rendered. */
    private static final String TIME = "23:12:33";

    /** The shared catalog, real rather than doubled so the two shared literals are the real ones. */
    private MessageCatalogService messageCatalogService;

    /** Subject under test. */
    private SignOnContractAdapter subject;

    /** Assembles the adapter over the real catalog. */
    @BeforeEach
    void setUp() {
        messageCatalogService = new MessageCatalogService();
        subject = new SignOnContractAdapter(messageCatalogService);
    }

    /**
     * Builds an admitted turn for the given role.
     *
     * @param userType the resolved role
     * @param route the destination
     * @return the turn
     */
    private static AuthenticationService.SignOnScreen admitted(final UserType userType,
                                                               final NavigationService.Route route) {
        return admitted(userType, userType.getCode(), route);
    }

    /**
     * Builds an admitted turn whose effective authority and raw stored code are deliberately distinct.
     *
     * @param userType effective security authority
     * @param rawUserTypeCode raw code read from the credential record
     * @param route destination
     * @return the turn
     */
    private static AuthenticationService.SignOnScreen admitted(final UserType userType,
            final String rawUserTypeCode, final NavigationService.Route route) {
        return new AuthenticationService.SignOnScreen(AuthenticationService.Decision.ADMITTED,
                USER_ID, USER_ID, userType, rawUserTypeCode, route, false, null,
                MessageCatalogService.CCDA_TITLE01, MessageCatalogService.CCDA_TITLE02, DATE, TIME);
    }

    /**
     * Builds a turn that did not admit the operator.
     *
     * @param decision the decision reached
     * @param errorFlag whether the program raised its flag
     * @param focusScreenFieldId the field the cursor returns to
     * @return the turn
     */
    private static AuthenticationService.SignOnScreen refused(
            final AuthenticationService.Decision decision,
            final boolean errorFlag,
            final String focusScreenFieldId) {
        return new AuthenticationService.SignOnScreen(decision, null, USER_ID, null, null, null,
                errorFlag, focusScreenFieldId, MessageCatalogService.CCDA_TITLE01,
                MessageCatalogService.CCDA_TITLE02, DATE, TIME);
    }

    @Nested
    @DisplayName("every outcome carries its frozen literal")
    class EveryOutcomeCarriesItsLiteral {

        @Test
        @DisplayName("the five sign-on literals are carried character for character, because operators "
                + "read them and downstream tooling matches on them")
        void theFiveSignOnLiteralsAreVerbatim() {
            assertThat(subject.toResponse(refused(
                    AuthenticationService.Decision.USER_ID_MISSING, true, "USERID")).message())
                    .isEqualTo("Please enter User ID ...");
            assertThat(subject.toResponse(refused(
                    AuthenticationService.Decision.PASSWORD_MISSING, true, "PASSWD")).message())
                    .isEqualTo("Please enter Password ...");
            assertThat(subject.toResponse(refused(
                    AuthenticationService.Decision.WRONG_PASSWORD, false, "PASSWD")).message())
                    .isEqualTo("Wrong Password. Try again ...");
            assertThat(subject.toResponse(refused(
                    AuthenticationService.Decision.USER_NOT_FOUND, true, "USERID")).message())
                    .isEqualTo("User not found. Try again ...");
            assertThat(subject.toResponse(refused(
                    AuthenticationService.Decision.UNABLE_TO_VERIFY, true, "USERID")).message())
                    .isEqualTo("Unable to verify the User ...");
        }

        @Test
        @DisplayName("the literals are the response contract's own constants, so the two cannot drift "
                + "apart without this failing")
        void theLiteralsAreTheContractsOwnConstants() {
            assertThat(subject.toResponse(refused(
                    AuthenticationService.Decision.USER_ID_MISSING, true, null)).message())
                    .isEqualTo(SignOnResponse.MSG_PROMPT_USERID);
            assertThat(subject.toResponse(refused(
                    AuthenticationService.Decision.PASSWORD_MISSING, true, null)).message())
                    .isEqualTo(SignOnResponse.MSG_PROMPT_PASSWD);
            assertThat(subject.toResponse(refused(
                    AuthenticationService.Decision.WRONG_PASSWORD, false, null)).message())
                    .isEqualTo(SignOnResponse.MSG_WRONG_PASSWD);
            assertThat(subject.toResponse(refused(
                    AuthenticationService.Decision.USER_NOT_FOUND, true, null)).message())
                    .isEqualTo(SignOnResponse.MSG_USER_NOT_FOUND);
            assertThat(subject.toResponse(refused(
                    AuthenticationService.Decision.UNABLE_TO_VERIFY, true, null)).message())
                    .isEqualTo(SignOnResponse.MSG_UNABLE_TO_VERIFY);
        }

        @Test
        @DisplayName("the two shared literals come from the common catalog rather than being restated, so "
                + "the sign-on screen says what every other screen says")
        void theTwoSharedLiteralsComeFromTheCatalog() {
            assertThat(subject.toResponse(refused(
                    AuthenticationService.Decision.SIGNED_OFF, false, null)).message())
                    .isEqualTo(messageCatalogService.thankYouMessage());
            assertThat(subject.toResponse(refused(
                    AuthenticationService.Decision.KEY_NOT_MAPPED, true, null)).message())
                    .isEqualTo(messageCatalogService.invalidKeyMessage());
        }

        @Test
        @DisplayName("an admitted turn carries no message, matching the program: control transfers and the "
                + "operator sees the menu rather than a confirmation of the screen they left")
        void anAdmittedTurnCarriesNoMessage() {
            assertThat(subject.toResponse(admitted(UserType.ADMIN, NavigationService.Route.ADMIN_MENU))
                    .message())
                    .isNull();
        }

        @Test
        @DisplayName("the first-entry turn carries no message, because the source clears the output map "
                + "before it focuses USERID")
        void theFirstEntryCarriesNoMessage() {
            assertThat(subject.toResponse(refused(
                    AuthenticationService.Decision.INITIAL_ENTRY, false, "USERID")).message())
                    .isNull();
        }

        @ParameterizedTest
        @EnumSource(AuthenticationService.Decision.class)
        @DisplayName("every decision the service can reach is mapped, and no two message-bearing outcomes "
                + "share a literal, so an operator can tell them apart")
        void everyDecisionIsMappedAndTheLiteralsAreDistinct(
                final AuthenticationService.Decision decision) {
            final AuthenticationService.SignOnScreen screen =
                    decision.isAdmitted()
                            ? admitted(UserType.USER, NavigationService.Route.USER_MENU)
                            : refused(decision, true, null);

            final String message = subject.toResponse(screen).message();
            if (decision.isAdmitted() || decision == AuthenticationService.Decision.INITIAL_ENTRY) {
                assertThat(message).isNull();
            } else {
                assertThat(message).isNotNull().isNotBlank();
            }
        }

        @Test
        @DisplayName("the seven message-bearing outcomes produce seven distinct literals")
        void theSevenLiteralsAreDistinct() {
            final Map<AuthenticationService.Decision, String> byDecision =
                    new EnumMap<>(AuthenticationService.Decision.class);
            for (final AuthenticationService.Decision decision
                    : AuthenticationService.Decision.values()) {
                if (decision.isAdmitted()
                        || decision == AuthenticationService.Decision.INITIAL_ENTRY) {
                    continue;
                }
                byDecision.put(decision, subject.toResponse(refused(decision, true, null)).message());
            }

            final Set<String> distinct = new HashSet<>(byDecision.values());
            assertThat(byDecision).hasSize(7);
            assertThat(distinct)
                    .as("two outcomes sharing text would make them indistinguishable to an operator")
                    .hasSize(7);
        }
    }

    @Nested
    @DisplayName("the navigation state is derived, never echoed")
    class TheNavigationStateIsDerived {

        @Test
        @DisplayName("an admitted turn establishes state naming the identifier and role the service "
                + "resolved, plus the originating and destination transactions")
        void anAdmittedTurnEstablishesDerivedState() {
            final SignOnResponse response =
                    subject.toResponse(admitted(UserType.ADMIN, NavigationService.Route.ADMIN_MENU));
            final NavigationContext context = response.navigationContext();

            assertThat(context).isNotNull();
            assertThat(context.userId()).isEqualTo(USER_ID);
            assertThat(context.userType()).isEqualTo(UserType.ADMIN.getCode());
            assertThat(context.fromTransactionId()).isEqualTo(SignOnResponse.TRANSACTION_NAME);
            assertThat(context.fromProgram()).isEqualTo(SignOnResponse.PROGRAM_NAME);
            assertThat(context.toTransactionId())
                    .isEqualTo(NavigationService.Route.ADMIN_MENU.getLegacyTransactionId());
            assertThat(context.toProgram())
                    .isEqualTo(NavigationService.Route.ADMIN_MENU.getLegacyProgramName());
        }

        @Test
        @DisplayName("the established state carries no customer, account or card detail, because sign-on "
                + "has selected no record and inventing one would be state the program never held")
        void theEstablishedStateCarriesNoRecordDetail() {
            final NavigationContext context =
                    subject.toResponse(admitted(UserType.USER, NavigationService.Route.USER_MENU))
                            .navigationContext();

            assertThat(context).isNotNull();
            assertThat(context.customerId()).isNull();
            assertThat(context.customerFirstName()).isNull();
            assertThat(context.customerMiddleName()).isNull();
            assertThat(context.customerLastName()).isNull();
            assertThat(context.accountId()).isNull();
            assertThat(context.accountStatus()).isNull();
            assertThat(context.cardNumber()).isNull();
            assertThat(context.lastMap()).isNull();
            assertThat(context.lastMapset()).isNull();
        }

        @Test
        @DisplayName("the program context is written explicitly as the entering state, because that flag "
                + "gates field-level error decoration on the destination screen")
        void theProgramContextIsWrittenExplicitly() {
            final NavigationContext context =
                    subject.toResponse(admitted(UserType.USER, NavigationService.Route.USER_MENU))
                            .navigationContext();

            assertThat(context).isNotNull();
            assertThat(context.programContext())
                    .as("left absent, the destination would have to infer it")
                    .isEqualTo(NavigationContext.ProgramContext.ENTER);
        }

        @ParameterizedTest
        @EnumSource(value = AuthenticationService.Decision.class, names = "ADMITTED",
                mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("no turn that failed to admit establishes any state at all, so a context carrying an "
                + "unauthenticated identifier cannot exist")
        void noRefusedTurnEstablishesState(final AuthenticationService.Decision decision) {
            final SignOnResponse response = subject.toResponse(refused(decision, true, "USERID"));

            assertThat(response.navigationContext()).isNull();
            assertThat(response.userType()).isNull();
            assertThat(response.nextRoute()).isNull();
        }

        @ParameterizedTest
        @EnumSource(value = AuthenticationService.Decision.class, names = "ADMITTED",
                mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("a turn that failed to admit still echoes the keyed identifier, because seven of the "
                + "nine outcomes redisplay this screen and a client that cannot restate it makes the "
                + "operator retype a value that was never in question")
        void aRefusedTurnStillEchoesTheKeyedIdentifier(
                final AuthenticationService.Decision decision) {
            final SignOnResponse response = subject.toResponse(refused(decision, true, "USERID"));

            // The echo comes from the turn's display component. It establishes nothing: the state, the
            // role and the destination are all still absent, which the sibling case above asserts.
            assertThat(response.userId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("the role decides the destination the state names, so the two cannot disagree")
        void theRoleAndTheDestinationAgree() {
            final SignOnResponse asAdmin =
                    subject.toResponse(admitted(UserType.ADMIN, NavigationService.Route.ADMIN_MENU));
            final SignOnResponse asUser =
                    subject.toResponse(admitted(UserType.USER, NavigationService.Route.USER_MENU));

            assertThat(asAdmin.nextRoute())
                    .isEqualTo(NavigationService.Route.ADMIN_MENU.getRouteValue());
            assertThat(asUser.nextRoute())
                    .isEqualTo(NavigationService.Route.USER_MENU.getRouteValue());
            assertThat(asAdmin.userType()).isEqualTo("A");
            assertThat(asUser.userType()).isEqualTo("U");
        }

        @Test
        @DisplayName("an undeclared raw role code is echoed into the response and navigation state while "
                + "the effective authority remains the standard-user one")
        void theRawRoleCodeSurvivesTheBoundary() {
            final SignOnResponse response = subject.toResponse(
                    admitted(UserType.USER, "X", NavigationService.Route.USER_MENU));

            assertThat(response.userType()).isEqualTo("X");
            assertThat(response.navigationContext()).isNotNull();
            assertThat(response.navigationContext().userType()).isEqualTo("X");
            assertThat(response.nextRoute())
                    .isEqualTo(NavigationService.Route.USER_MENU.getRouteValue());
        }
    }

    @Nested
    @DisplayName("the header and the screen identity travel unchanged")
    class TheHeaderTravelsUnchanged {

        @Test
        @DisplayName("both titles, the date and the time are carried exactly as the service rendered "
                + "them, and the transaction and program are this screen's own")
        void theHeaderIsCarriedThrough() {
            final SignOnResponse response =
                    subject.toResponse(admitted(UserType.USER, NavigationService.Route.USER_MENU));

            assertThat(response.title01()).isEqualTo(MessageCatalogService.CCDA_TITLE01);
            assertThat(response.title02()).isEqualTo(MessageCatalogService.CCDA_TITLE02);
            assertThat(response.currentDate()).isEqualTo(DATE);
            assertThat(response.currentTime()).isEqualTo(TIME);
            assertThat(response.transactionName()).isEqualTo(SignOnResponse.TRANSACTION_NAME);
            assertThat(response.programName()).isEqualTo(SignOnResponse.PROGRAM_NAME);
        }

        @Test
        @DisplayName("the error flag and the focus field are carried through rather than re-derived, so "
                + "the flag the program actually raised is the flag the client sees")
        void theFlagAndFocusAreCarriedThrough() {
            assertThat(subject.toResponse(refused(
                    AuthenticationService.Decision.WRONG_PASSWORD, false, "PASSWD")))
                    .satisfies(response -> {
                        assertThat(response.generalError()).isFalse();
                        assertThat(response.focusScreenFieldId()).isEqualTo("PASSWD");
                    });
            assertThat(subject.toResponse(refused(
                    AuthenticationService.Decision.USER_NOT_FOUND, true, "USERID")))
                    .satisfies(response -> {
                        assertThat(response.generalError()).isTrue();
                        assertThat(response.focusScreenFieldId()).isEqualTo("USERID");
                    });
        }

        @Test
        @DisplayName("the region identifiers are absent, because nothing outside a mainframe region "
                + "supplies them and inventing a value would be a fabricated echo")
        void theRegionIdentifiersAreAbsent() {
            final SignOnResponse response =
                    subject.toResponse(admitted(UserType.USER, NavigationService.Route.USER_MENU));

            assertThat(response.applicationId()).isNull();
            assertThat(response.systemId()).isNull();
        }

        @Test
        @DisplayName("both the catalog and the turn are required, so the adapter cannot be part-wired or "
                + "asked to project nothing")
        void bothArgumentsAreRequired() {
            assertThatNullPointerException().isThrownBy(() -> new SignOnContractAdapter(null));
            assertThatNullPointerException().isThrownBy(() -> subject.toResponse(null));
        }
    }
}
