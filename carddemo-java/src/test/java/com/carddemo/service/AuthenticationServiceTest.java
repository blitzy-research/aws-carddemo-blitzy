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

import com.carddemo.domain.UserSecurity;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link AuthenticationService}, the sign-on transaction and the module's first
 * repository-backed path.
 *
 * <p><strong>What this proves.</strong> Three properties, of which the first is the finding this class
 * closes and the other two are the parity traps that make the translation easy to get wrong.
 * <ul>
 *   <li><strong>The transaction actually reads the credential master.</strong> Not a stub, not a
 *       constant: the repository is consulted with the folded identifier, and the decision follows from
 *       what it returned. Before this class existed nothing in the module reached a repository at all.</li>
 *   <li><strong>Both fields are folded to upper case.</strong> The legacy program folds the identifier
 *       <em>and</em> the secret, and compares the folded secret, so a lower-case secret authenticates. A
 *       translation that folded only the identifier would reject credentials the mainframe accepts, and no
 *       test of the happy path alone would notice.</li>
 *   <li><strong>The failed comparison leaves the error flag lowered.</strong> Four of the five rejections
 *       raise it and the wrong-secret one does not, which is not an oversight in the source but observable
 *       state the response contract documents explicitly.</li>
 * </ul>
 *
 * <p>Provenance: {@code app/cbl/COSGN00C.cbl}, read as read-only reference at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.
 */
@DisplayName("AuthenticationService :: transaction CC00, the sign-on screen")
class AuthenticationServiceTest {

    /** The clock is fixed at the upstream release stamp so the header is deterministic. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:33Z"), ZoneOffset.UTC);

    /** The header the fixed clock renders, in the screen's own two-digit-year form. */
    private static final String EXPECTED_DATE = "07/19/22";

    /** The header time the fixed clock renders. */
    private static final String EXPECTED_TIME = "23:12:33";

    /** A seeded administrator identifier, from {@code app/jcl/DUSRSECJ.jcl}. */
    private static final String ADMIN_USER_ID = "ADMIN001";

    /** A seeded ordinary identifier, from the same in-stream card images. */
    private static final String ORDINARY_USER_ID = "USER0001";

    /** The literal secret those seed records carry. */
    private static final String SEEDED_SECRET = "PASSWORD";

    /** The credential master. */
    private UserSecurityRepository repository;

    /** The digest verifier, real rather than doubled so the fold is genuinely exercised. */
    private CredentialDigestService credentialDigestService;

    /** Subject under test. */
    private AuthenticationService subject;

    /** Assembles the service over a mocked repository and otherwise real collaborators. */
    @BeforeEach
    void setUp() {
        repository = mock(UserSecurityRepository.class);
        credentialDigestService = new CredentialDigestService();
        subject = new AuthenticationService(repository, credentialDigestService,
                new NavigationService(), new MessageCatalogService(), FIXED_CLOCK);
    }

    /**
     * Builds a stored credential record whose digest is of the seeded secret.
     *
     * @param userId the identifier to key it by
     * @param typeCode the one-character role code
     * @return the record
     */
    private UserSecurity storedRecord(final String userId, final String typeCode) {
        return new UserSecurity(userId, "Test", "Operator",
                credentialDigestService.encode(SEEDED_SECRET), typeCode);
    }

    // ------------------------------------------------------------------------------------------
    // The finding: the transaction reaches a repository
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the credential master is genuinely read")
    class TheCredentialMasterIsRead {

        @Test
        @DisplayName("an admitted sign-on consults the repository with the folded identifier and answers "
                + "from the record it returned")
        void anAdmittedSignOnReadsTheRepository() {
            when(repository.findById(ADMIN_USER_ID))
                    .thenReturn(Optional.of(storedRecord(ADMIN_USER_ID, "A")));

            final AuthenticationService.SignOnScreen screen =
                    subject.signOn(ADMIN_USER_ID, SEEDED_SECRET);

            verify(repository).findById(ADMIN_USER_ID);
            assertThat(screen.decision()).isEqualTo(AuthenticationService.Decision.ADMITTED);
            assertThat(screen.userId()).isEqualTo(ADMIN_USER_ID);
            assertThat(screen.userType()).isEqualTo(UserType.ADMIN);
        }

        @Test
        @DisplayName("the identifier is folded before the lookup, so a lower-case entry finds the "
                + "upper-case key the record is stored under")
        void theIdentifierIsFoldedBeforeTheLookup() {
            when(repository.findById(ADMIN_USER_ID))
                    .thenReturn(Optional.of(storedRecord(ADMIN_USER_ID, "A")));

            final AuthenticationService.SignOnScreen screen =
                    subject.signOn(ADMIN_USER_ID.toLowerCase(java.util.Locale.ROOT), SEEDED_SECRET);

            verify(repository).findById(ADMIN_USER_ID);
            assertThat(screen.decision()).isEqualTo(AuthenticationService.Decision.ADMITTED);
        }

        @Test
        @DisplayName("neither presence rejection reads the repository at all, matching the program's own "
                + "guard that the read happens only when no error was already raised")
        void aPresenceRejectionDoesNotReadTheRepository() {
            assertThat(subject.signOn(null, SEEDED_SECRET).decision())
                    .isEqualTo(AuthenticationService.Decision.USER_ID_MISSING);
            assertThat(subject.signOn(ADMIN_USER_ID, null).decision())
                    .isEqualTo(AuthenticationService.Decision.PASSWORD_MISSING);

            verify(repository, never()).findById(any());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Parity trap one: the secret is folded too
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("both fields are folded, not just the identifier")
    class BothFieldsAreFolded {

        @Test
        @DisplayName("a lower-case secret authenticates, because the program compares the folded value")
        void aLowerCaseSecretAuthenticates() {
            when(repository.findById(ORDINARY_USER_ID))
                    .thenReturn(Optional.of(storedRecord(ORDINARY_USER_ID, "U")));

            final AuthenticationService.SignOnScreen screen = subject.signOn(ORDINARY_USER_ID,
                    SEEDED_SECRET.toLowerCase(java.util.Locale.ROOT));

            assertThat(screen.decision())
                    .as("folding only the identifier would reject a credential the mainframe accepts")
                    .isEqualTo(AuthenticationService.Decision.ADMITTED);
        }

        @Test
        @DisplayName("a mixed-case secret authenticates too, so the fold is applied to the whole value "
                + "rather than to its first character")
        void aMixedCaseSecretAuthenticates() {
            when(repository.findById(ORDINARY_USER_ID))
                    .thenReturn(Optional.of(storedRecord(ORDINARY_USER_ID, "U")));

            assertThat(subject.signOn(ORDINARY_USER_ID, "PassWord").decision())
                    .isEqualTo(AuthenticationService.Decision.ADMITTED);
        }

        @Test
        @DisplayName("a genuinely different secret is still refused, so the fold widens the accepted set "
                + "by case alone and not by anything else")
        void aDifferentSecretIsStillRefused() {
            when(repository.findById(ORDINARY_USER_ID))
                    .thenReturn(Optional.of(storedRecord(ORDINARY_USER_ID, "U")));

            assertThat(subject.signOn(ORDINARY_USER_ID, "PASSW0RD").decision())
                    .as("without this the fold assertions above would pass for a service that admitted "
                            + "everyone")
                    .isEqualTo(AuthenticationService.Decision.WRONG_PASSWORD);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Parity trap two: the error flag
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the error flag follows the program, which does not raise it on every rejection")
    class TheErrorFlagFollowsTheProgram {

        @Test
        @DisplayName("the failed comparison leaves the flag lowered while every other rejection raises it")
        void theFailedComparisonLeavesTheFlagLowered() {
            when(repository.findById(ORDINARY_USER_ID))
                    .thenReturn(Optional.of(storedRecord(ORDINARY_USER_ID, "U")));

            assertThat(subject.signOn(ORDINARY_USER_ID, "WRONGONE").errorFlag())
                    .as("lines 240 to 245 compose a message without moving the flag")
                    .isFalse();

            assertThat(subject.signOn(null, SEEDED_SECRET).errorFlag()).isTrue();
            assertThat(subject.signOn(ORDINARY_USER_ID, "  ").errorFlag()).isTrue();
        }

        @Test
        @DisplayName("the exit key leaves the flag lowered and the unmapped key raises it, which is the "
                + "only difference between those two turns")
        void theExitKeyAndTheUnmappedKeyDifferOnlyInTheFlag() {
            final AuthenticationService.SignOnScreen exit = subject.handle(KeyAction.PFK03, null, null);
            final AuthenticationService.SignOnScreen unmapped =
                    subject.handle(KeyAction.PFK09, null, null);

            assertThat(exit.decision()).isEqualTo(AuthenticationService.Decision.SIGNED_OFF);
            assertThat(exit.errorFlag()).isFalse();
            assertThat(unmapped.decision()).isEqualTo(AuthenticationService.Decision.KEY_NOT_MAPPED);
            assertThat(unmapped.errorFlag()).isTrue();
            assertThat(exit.focusScreenFieldId()).isNull();
            assertThat(unmapped.focusScreenFieldId()).isNull();
        }

        @Test
        @DisplayName("the not-found and unclassifiable outcomes both raise the flag and both return the "
                + "cursor to the identifier")
        void theRecordLevelRejectionsRaiseTheFlag() {
            when(repository.findById(ORDINARY_USER_ID)).thenReturn(Optional.empty());
            final AuthenticationService.SignOnScreen notFound =
                    subject.signOn(ORDINARY_USER_ID, SEEDED_SECRET);

            when(repository.findById(ADMIN_USER_ID))
                    .thenReturn(Optional.of(storedRecord(ADMIN_USER_ID, "Z")));
            final AuthenticationService.SignOnScreen unclassifiable =
                    subject.signOn(ADMIN_USER_ID, SEEDED_SECRET);

            assertThat(notFound.decision()).isEqualTo(AuthenticationService.Decision.USER_NOT_FOUND);
            assertThat(notFound.errorFlag()).isTrue();
            assertThat(notFound.focusScreenFieldId())
                    .isEqualTo(AuthenticationService.FIELD_USER_ID);
            assertThat(unclassifiable.decision())
                    .isEqualTo(AuthenticationService.Decision.UNABLE_TO_VERIFY);
            assertThat(unclassifiable.errorFlag()).isTrue();
            assertThat(unclassifiable.focusScreenFieldId())
                    .isEqualTo(AuthenticationService.FIELD_USER_ID);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Evaluation order and the blank test
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("evaluation order and the blank test reproduce the screen's own clauses")
    class EvaluationOrderAndTheBlankTest {

        @Test
        @DisplayName("with neither field supplied the identifier is reported, because its clause is "
                + "evaluated first and the evaluation stops at the first match")
        void theIdentifierIsReportedFirst() {
            final AuthenticationService.SignOnScreen screen = subject.signOn(null, null);

            assertThat(screen.decision()).isEqualTo(AuthenticationService.Decision.USER_ID_MISSING);
            assertThat(screen.focusScreenFieldId()).isEqualTo(AuthenticationService.FIELD_USER_ID);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "        ", "\t"})
        @DisplayName("an empty and an all-blank entry are the same state as an absent one, because a "
                + "fixed-width field the operator left alone arrives as spaces")
        void blankIsTheSameStateAsAbsent(final String blank) {
            assertThat(subject.signOn(blank, SEEDED_SECRET).decision())
                    .isEqualTo(AuthenticationService.Decision.USER_ID_MISSING);
            assertThat(subject.signOn(ADMIN_USER_ID, blank).decision())
                    .isEqualTo(AuthenticationService.Decision.PASSWORD_MISSING);
        }

        @Test
        @DisplayName("the secret rejection returns the cursor to the secret field, not to the identifier")
        void theSecretRejectionFocusesTheSecretField() {
            when(repository.findById(ORDINARY_USER_ID))
                    .thenReturn(Optional.of(storedRecord(ORDINARY_USER_ID, "U")));

            assertThat(subject.signOn(ADMIN_USER_ID, null).focusScreenFieldId())
                    .isEqualTo(AuthenticationService.FIELD_PASSWORD);
            assertThat(subject.signOn(ORDINARY_USER_ID, "WRONGONE").focusScreenFieldId())
                    .isEqualTo(AuthenticationService.FIELD_PASSWORD);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Routing and attention-key dispatch
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the role split and the attention-key dispatch")
    class TheRoleSplitAndTheKeyDispatch {

        @Test
        @DisplayName("an administrator is routed to the administrative menu and an ordinary operator to "
                + "the main menu, which is the whole of the program's role split")
        void theRoleSplitSendsEachTypeToItsOwnMenu() {
            when(repository.findById(ADMIN_USER_ID))
                    .thenReturn(Optional.of(storedRecord(ADMIN_USER_ID, "A")));
            when(repository.findById(ORDINARY_USER_ID))
                    .thenReturn(Optional.of(storedRecord(ORDINARY_USER_ID, "U")));

            assertThat(subject.signOn(ADMIN_USER_ID, SEEDED_SECRET).route())
                    .isEqualTo(NavigationService.Route.ADMIN_MENU);
            assertThat(subject.signOn(ORDINARY_USER_ID, SEEDED_SECRET).route())
                    .isEqualTo(NavigationService.Route.USER_MENU);
        }

        @Test
        @DisplayName("the enter key drives a sign-on attempt while every other key and an absent key are "
                + "handled without one, so no unmapped key can reach the credential master")
        void onlyTheEnterKeyDrivesAnAttempt() {
            assertThat(subject.handle(KeyAction.ENTER, null, null).decision())
                    .isEqualTo(AuthenticationService.Decision.USER_ID_MISSING);
            assertThat(subject.handle(null, ADMIN_USER_ID, SEEDED_SECRET).decision())
                    .isEqualTo(AuthenticationService.Decision.KEY_NOT_MAPPED);
            assertThat(subject.handle(KeyAction.CLEAR, ADMIN_USER_ID, SEEDED_SECRET).decision())
                    .isEqualTo(AuthenticationService.Decision.KEY_NOT_MAPPED);

            verify(repository, never()).findById(any());
        }

        @ParameterizedTest
        @EnumSource(value = KeyAction.class, names = {"ENTER", "PFK03"}, mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("every key other than enter and the exit key is the unmapped case, so the clause "
                + "ordering holds across the whole key vocabulary rather than for a sampled few")
        void everyOtherKeyIsUnmapped(final KeyAction keyAction) {
            assertThat(subject.handle(keyAction, ADMIN_USER_ID, SEEDED_SECRET).decision())
                    .isEqualTo(AuthenticationService.Decision.KEY_NOT_MAPPED);
        }
    }

    // ------------------------------------------------------------------------------------------
    // The header, and the result's own invariant
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the header and the result invariant")
    class TheHeaderAndTheResultInvariant {

        @Test
        @DisplayName("every turn carries both titles and the header date and time, read from the clock "
                + "once so the two cannot straddle midnight")
        void everyTurnCarriesTheHeader() {
            when(repository.findById(ADMIN_USER_ID))
                    .thenReturn(Optional.of(storedRecord(ADMIN_USER_ID, "A")));

            for (final AuthenticationService.SignOnScreen screen : java.util.List.of(
                    subject.signOn(ADMIN_USER_ID, SEEDED_SECRET),
                    subject.signOn(null, null),
                    subject.handle(KeyAction.PFK03, null, null),
                    subject.handle(KeyAction.PFK09, null, null))) {
                assertThat(screen.title01()).isEqualTo(MessageCatalogService.CCDA_TITLE01);
                assertThat(screen.title02()).isEqualTo(MessageCatalogService.CCDA_TITLE02);
                assertThat(screen.currentDate()).isEqualTo(EXPECTED_DATE);
                assertThat(screen.currentTime()).isEqualTo(EXPECTED_TIME);
            }
        }

        @Test
        @DisplayName("no rejected turn names the operator, the role or a destination, so the boundary can "
                + "decide from their presence alone whether to issue a session")
        void noRejectedTurnNamesAnOperator() {
            when(repository.findById(ORDINARY_USER_ID))
                    .thenReturn(Optional.of(storedRecord(ORDINARY_USER_ID, "U")));

            for (final AuthenticationService.SignOnScreen screen : java.util.List.of(
                    subject.signOn(null, null),
                    subject.signOn(ORDINARY_USER_ID, null),
                    subject.signOn(ORDINARY_USER_ID, "WRONGONE"),
                    subject.handle(KeyAction.PFK03, null, null),
                    subject.handle(KeyAction.PFK09, null, null))) {
                assertThat(screen.decision().isAdmitted()).isFalse();
                assertThat(screen.userId()).isNull();
                assertThat(screen.userType()).isNull();
                assertThat(screen.route()).isNull();
            }
        }

        @Test
        @DisplayName("the result refuses an inconsistent pairing in either direction, so the boundary's "
                + "reliance on that pairing is enforced rather than assumed")
        void theResultRefusesAnInconsistentPairing() {
            assertThatIllegalArgumentException()
                    .as("a turn that did not admit must name nothing")
                    .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                            AuthenticationService.Decision.WRONG_PASSWORD, ADMIN_USER_ID, UserType.ADMIN,
                            NavigationService.Route.ADMIN_MENU, false, null, null, null, null, null));
            assertThatNullPointerException()
                    .as("an admitted turn must name the operator")
                    .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                            AuthenticationService.Decision.ADMITTED, null, UserType.ADMIN,
                            NavigationService.Route.ADMIN_MENU, false, null, null, null, null, null));
            assertThatNullPointerException()
                    .as("an admitted turn must nominate a destination")
                    .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                            AuthenticationService.Decision.ADMITTED, ADMIN_USER_ID, UserType.ADMIN,
                            null, false, null, null, null, null, null));
            assertThatNullPointerException()
                    .as("an admitted turn must name the resolved role")
                    .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                            AuthenticationService.Decision.ADMITTED, ADMIN_USER_ID, null,
                            NavigationService.Route.ADMIN_MENU, false, null, null, null, null, null));
            assertThatNullPointerException()
                    .as("a decision is always required")
                    .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                            null, null, null, null, false, null, null, null, null, null));
        }

        @Test
        @DisplayName("a refused turn is rejected for naming any one of the three on its own, not only for "
                + "naming all three, so no partial leak of an unverified identity is representable")
        void aRefusedTurnIsRejectedForNamingAnyOneOfTheThree() {
            // The check is a disjunction, so a test that only ever supplies all three would leave two of
            // its arms unexercised and a later edit could drop one without failing anything.
            assertThatIllegalArgumentException()
                    .as("an operator alone")
                    .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                            AuthenticationService.Decision.USER_NOT_FOUND, ADMIN_USER_ID, null, null,
                            true, null, null, null, null, null));
            assertThatIllegalArgumentException()
                    .as("a role alone")
                    .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                            AuthenticationService.Decision.USER_NOT_FOUND, null, UserType.ADMIN, null,
                            true, null, null, null, null, null));
            assertThatIllegalArgumentException()
                    .as("a destination alone")
                    .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                            AuthenticationService.Decision.USER_NOT_FOUND, null, null,
                            NavigationService.Route.USER_MENU, true, null, null, null, null, null));
        }

        @Test
        @DisplayName("the only record ever read is the one keyed by the identifier whose secret was "
                + "presented, so this operation cannot be used to reach another operator's record")
        void theOnlyRecordReadIsTheOneWhoseSecretWasPresented() {
            // This is the ownership-aware-lookup property for this operation, and for sign-on it holds by
            // construction rather than by an ownership model: the key of the record read IS the identity
            // being asserted, and it is only admitted if the secret stored against that same key verifies.
            // Asserted as an interaction rather than an outcome because the risk is a second, unkeyed read
            // - a list, a projection, a scan - being added later and returning something the caller never
            // proved a claim to. No such call exists, and this fails if one appears.
            when(repository.findById(ADMIN_USER_ID))
                    .thenReturn(Optional.of(storedRecord(ADMIN_USER_ID, "A")));

            subject.signOn(ADMIN_USER_ID, SEEDED_SECRET);
            subject.signOn(ADMIN_USER_ID, "WRONGONE");

            verify(repository, org.mockito.Mockito.times(2)).findById(ADMIN_USER_ID);
            org.mockito.Mockito.verifyNoMoreInteractions(repository);
        }

        @Test
        @DisplayName("every collaborator is required, so a partially wired service cannot be constructed")
        void everyCollaboratorIsRequired() {
            assertThatNullPointerException().isThrownBy(() -> new AuthenticationService(
                    null, credentialDigestService, new NavigationService(),
                    new MessageCatalogService(), FIXED_CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new AuthenticationService(
                    repository, null, new NavigationService(),
                    new MessageCatalogService(), FIXED_CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new AuthenticationService(
                    repository, credentialDigestService, null,
                    new MessageCatalogService(), FIXED_CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new AuthenticationService(
                    repository, credentialDigestService, new NavigationService(), null, FIXED_CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new AuthenticationService(
                    repository, credentialDigestService, new NavigationService(),
                    new MessageCatalogService(), null));
        }

        @Test
        @DisplayName("only the admitted decision reports itself as admitting, so the boundary's one test "
                + "for whether to issue a session cannot be satisfied by any other outcome")
        void onlyTheAdmittedDecisionAdmits() {
            for (final AuthenticationService.Decision decision
                    : AuthenticationService.Decision.values()) {
                assertThat(decision.isAdmitted())
                        .as("%s", decision)
                        .isEqualTo(decision == AuthenticationService.Decision.ADMITTED);
            }
        }
    }
}
