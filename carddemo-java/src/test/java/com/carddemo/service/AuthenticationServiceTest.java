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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.domain.UserSecurity;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

    /** Captures this service's diagnostics so identifiers and failure text can be checked. */
    private ListAppender<ILoggingEvent> logCapture;

    private Logger serviceLogger;

    private Level previousLogLevel;

    /** Assembles the service over a mocked repository and otherwise real collaborators. */
    @BeforeEach
    void setUp() {
        repository = mock(UserSecurityRepository.class);
        credentialDigestService = new CredentialDigestService();
        subject = new AuthenticationService(repository, credentialDigestService,
                new NavigationService(), new MessageCatalogService(), FIXED_CLOCK);

        serviceLogger = (Logger) LoggerFactory.getLogger(AuthenticationService.class);
        previousLogLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.TRACE);
        logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger.addAppender(logCapture);
    }

    @AfterEach
    void detachLogCapture() {
        serviceLogger.detachAppender(logCapture);
        serviceLogger.setLevel(previousLogLevel);
        logCapture.stop();
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

    private List<String> loggedMessages() {
        return logCapture.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("a rejected identifier is omitted from the fixed outcome diagnostic")
    void rejectedIdentifierIsRedacted() {
        when(repository.findById(ORDINARY_USER_ID)).thenReturn(Optional.empty());

        subject.signOn(ORDINARY_USER_ID, SEEDED_SECRET);

        assertThat(loggedMessages())
                .contains("Sign-on rejected: rule=identifier-not-on-file")
                .noneMatch(message -> message.contains(ORDINARY_USER_ID));
    }

    @Test
    @DisplayName("credential-store exception messages and their embedded values never reach the log")
    void credentialStoreFailureTextIsWithheld() {
        final String sensitiveFailureText =
                "lookup failed for " + ORDINARY_USER_ID + " at jdbc:private";
        when(repository.findById(ORDINARY_USER_ID))
                .thenThrow(new DataAccessResourceFailureException(sensitiveFailureText));

        subject.signOn(ORDINARY_USER_ID, SEEDED_SECRET);

        assertThat(loggedMessages())
                .anyMatch(message -> message.contains(
                        "failureChain=DataAccessResourceFailureException"))
                .noneMatch(message -> message.contains(sensitiveFailureText))
                .noneMatch(message -> message.contains(ORDINARY_USER_ID));
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
            assertThat(screen.userTypeCode()).isEqualTo("A");
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

        @Test
        @DisplayName("a short identifier is moved into the record's own eight-position key before the "
                + "lookup, so it finds the row a terminal would have found")
        void aShortIdentifierIsWidenedToTheRecordKey() {
            final String storedKey = "USER1   ";
            when(repository.findById(storedKey))
                    .thenReturn(Optional.of(storedRecord(storedKey, "U")));

            final AuthenticationService.SignOnScreen screen = subject.signOn("user1", SEEDED_SECRET);

            // The screen item and the record field are both PIC X(08), so a terminal delivered eight
            // positions whatever the operator typed. A REST caller delivers five, and against a
            // variable-length key column five characters are a different key: without the move this
            // reads as identifier-not-on-file for a credential that exists.
            verify(repository).findById(storedKey);
            assertThat(screen.decision()).isEqualTo(AuthenticationService.Decision.ADMITTED);
            assertThat(screen.userType()).isEqualTo(UserType.USER);
        }

        @Test
        @DisplayName("the raw short identifier is never used as the key, which is what the defect was")
        void theRawShortIdentifierIsNotUsedAsTheKey() {
            when(repository.findById(any())).thenReturn(Optional.empty());

            subject.signOn("user1", SEEDED_SECRET);

            verify(repository, never()).findById("USER1");
            verify(repository, never()).findById("user1");
            verify(repository).findById("USER1   ");
        }

        @Test
        @DisplayName("an identifier already at the key width is passed through unchanged, so the move "
                + "adds nothing on the ordinary path")
        void anExactWidthIdentifierIsUnchanged() {
            when(repository.findById(ORDINARY_USER_ID))
                    .thenReturn(Optional.of(storedRecord(ORDINARY_USER_ID, "U")));

            assertThat(subject.signOn(ORDINARY_USER_ID, SEEDED_SECRET).decision())
                    .isEqualTo(AuthenticationService.Decision.ADMITTED);

            verify(repository).findById(ORDINARY_USER_ID);
        }

        @Test
        @DisplayName("the fold precedes the move, so a short lower-case entry is both folded and "
                + "widened in the source's order")
        void theFoldPrecedesTheMove() {
            final String storedKey = "AD1     ";
            when(repository.findById(storedKey))
                    .thenReturn(Optional.of(storedRecord(storedKey, "A")));

            assertThat(subject.signOn("ad1", SEEDED_SECRET).decision())
                    .isEqualTo(AuthenticationService.Decision.ADMITTED);

            // Folding after the move would produce the same string here, but folding a value that has
            // already been space-filled is not what the program does and would break the moment a
            // width-changing fold were ever introduced; asserting the key proves the composition.
            verify(repository).findById(storedKey);
        }
    }

    @Nested
    @DisplayName("sign-on diagnostics carry fixed outcomes and no operator identifier")
    class SignOnDiagnostics {

        @Test
        @DisplayName("not-found, unusable, wrong-secret and admitted logs contain no raw identifier")
        void everyCredentialOutcomeLogOmitsTheIdentifier() {
            when(repository.findById(ADMIN_USER_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(storedRecord(ADMIN_USER_ID, "Z")))
                    .thenReturn(Optional.of(storedRecord(ADMIN_USER_ID, "A")))
                    .thenReturn(Optional.of(storedRecord(ADMIN_USER_ID, "A")));

            final Logger logger = (Logger) LoggerFactory.getLogger(AuthenticationService.class);
            final Level previousLevel = logger.getLevel();
            final ListAppender<ILoggingEvent> recorder = new ListAppender<>();
            recorder.start();
            logger.setLevel(Level.INFO);
            logger.addAppender(recorder);
            try {
                subject.signOn(ADMIN_USER_ID, SEEDED_SECRET);
                subject.signOn(ADMIN_USER_ID, SEEDED_SECRET);
                subject.signOn(ADMIN_USER_ID, "WRONGONE");
                subject.signOn(ADMIN_USER_ID, SEEDED_SECRET);
            } finally {
                logger.detachAppender(recorder);
                logger.setLevel(previousLevel);
                recorder.stop();
            }

            final List<String> messages = recorder.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(messages)
                    .contains(
                            "Sign-on rejected: rule=identifier-not-on-file",
                            "Sign-on rejected: rule=secret-does-not-match")
                    .filteredOn(message -> message.startsWith(
                            "Sign-on admitted: outcome=admitted"))
                    .hasSize(2);
            assertThat(messages).allSatisfy(message -> assertThat(message)
                    .doesNotContain(ADMIN_USER_ID, "userId=", "\n", "\r"));
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
        @DisplayName("the not-found response raises the flag and returns the cursor to the identifier")
        void theNotFoundResponseRaisesTheFlag() {
            when(repository.findById(ORDINARY_USER_ID)).thenReturn(Optional.empty());
            final AuthenticationService.SignOnScreen notFound =
                    subject.signOn(ORDINARY_USER_ID, SEEDED_SECRET);

            assertThat(notFound.decision()).isEqualTo(AuthenticationService.Decision.USER_NOT_FOUND);
            assertThat(notFound.errorFlag()).isTrue();
            assertThat(notFound.focusScreenFieldId())
                    .isEqualTo(AuthenticationService.FIELD_USER_ID);
        }

        @Test
        @DisplayName("a credential-store failure becomes the source catch-all screen outside the failed "
                + "repository transaction")
        void aCredentialStoreFailureBecomesUnableToVerify() {
            when(repository.findById(ORDINARY_USER_ID))
                    .thenThrow(new DataAccessResourceFailureException("credential store unavailable"));

            final AuthenticationService.SignOnScreen unable =
                    subject.signOn(ORDINARY_USER_ID, SEEDED_SECRET);

            assertThat(unable.decision())
                    .isEqualTo(AuthenticationService.Decision.UNABLE_TO_VERIFY);
            assertThat(unable.errorFlag()).isTrue();
            assertThat(unable.focusScreenFieldId())
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
        @ValueSource(strings = {"", " ", "        "})
        @DisplayName("an empty and an all-space entry are the same state as an absent one, because a "
                + "fixed-width field the operator left alone arrives as spaces")
        void spacesAreTheSameStateAsAbsent(final String spaces) {
            assertThat(subject.signOn(spaces, SEEDED_SECRET).decision())
                    .isEqualTo(AuthenticationService.Decision.USER_ID_MISSING);
            assertThat(subject.signOn(ADMIN_USER_ID, spaces).decision())
                    .isEqualTo(AuthenticationService.Decision.PASSWORD_MISSING);
        }

        @Test
        @DisplayName("an all-low-values item is blank, while a mixture of spaces and low values is a "
                + "supplied field because neither exact comparison holds")
        void lowValuesAreComparedExactly() {
            final String lowValues = String.valueOf('\0').repeat(8);
            final String mixed = new String(new char[] {' ', '\0'});
            final String widenedMixed = mixed + " ".repeat(6);
            when(repository.findById(widenedMixed)).thenReturn(Optional.empty());

            assertThat(subject.signOn(lowValues, SEEDED_SECRET).decision())
                    .isEqualTo(AuthenticationService.Decision.USER_ID_MISSING);
            assertThat(subject.signOn(ADMIN_USER_ID, lowValues).decision())
                    .isEqualTo(AuthenticationService.Decision.PASSWORD_MISSING);
            assertThat(subject.signOn(mixed, SEEDED_SECRET).decision())
                    .isEqualTo(AuthenticationService.Decision.USER_NOT_FOUND);
            verify(repository).findById(widenedMixed);
        }

        @ParameterizedTest
        @ValueSource(strings = {"\t", "\n", "\u2003"})
        @DisplayName("tabs, line separators and Unicode spaces are supplied characters rather than COBOL "
                + "spaces, so they never take a missing-field arm")
        void javaWhitespaceIsNotCobolSpaces(final String whitespace) {
            when(repository.findById(whitespace)).thenReturn(Optional.empty());
            when(repository.findById(ADMIN_USER_ID))
                    .thenReturn(Optional.of(storedRecord(ADMIN_USER_ID, "A")));

            assertThat(subject.signOn(whitespace, SEEDED_SECRET).decision())
                    .isEqualTo(AuthenticationService.Decision.USER_NOT_FOUND);
            assertThat(subject.signOn(ADMIN_USER_ID, whitespace).decision())
                    .isEqualTo(AuthenticationService.Decision.WRONG_PASSWORD);
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
        @DisplayName("a zero-length first entry returns the blank screen with USERID focused before any "
                + "attention key is evaluated")
        void firstEntryPrecedesKeyEvaluation() {
            final AuthenticationService.SignOnScreen screen = subject.initialEntry();

            assertThat(screen.decision()).isEqualTo(AuthenticationService.Decision.INITIAL_ENTRY);
            assertThat(screen.errorFlag()).isFalse();
            assertThat(screen.focusScreenFieldId()).isEqualTo(AuthenticationService.FIELD_USER_ID);
            assertThat(screen.userId()).isNull();
            assertThat(screen.route()).isNull();
            verify(repository, never()).findById(any());
        }

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

        @ParameterizedTest
        @ValueSource(strings = {"Z", "a", " ", "0"})
        @DisplayName("every raw type code other than A is admitted with standard authority and routed to "
                + "the main menu while the raw code remains available to the response")
        void everyUndeclaredTypeUsesTheUnconditionalUserBranch(final String rawCode) {
            when(repository.findById(ORDINARY_USER_ID))
                    .thenReturn(Optional.of(storedRecord(ORDINARY_USER_ID, rawCode)));

            final AuthenticationService.SignOnScreen screen =
                    subject.signOn(ORDINARY_USER_ID, SEEDED_SECRET);

            assertThat(screen.decision()).isEqualTo(AuthenticationService.Decision.ADMITTED);
            assertThat(screen.userType()).isEqualTo(UserType.USER);
            assertThat(screen.userTypeCode()).isEqualTo(rawCode);
            assertThat(screen.route()).isEqualTo(NavigationService.Route.USER_MENU);
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
                    subject.initialEntry(),
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
                    subject.initialEntry(),
                    subject.signOn(ORDINARY_USER_ID, null),
                    subject.signOn(ORDINARY_USER_ID, "WRONGONE"),
                    subject.handle(KeyAction.PFK03, null, null),
                    subject.handle(KeyAction.PFK09, null, null))) {
                assertThat(screen.decision().isAdmitted()).isFalse();
                assertThat(screen.userId()).isNull();
                assertThat(screen.userType()).isNull();
                assertThat(screen.userTypeCode()).isNull();
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
                            AuthenticationService.Decision.WRONG_PASSWORD, ADMIN_USER_ID, ADMIN_USER_ID,
                            UserType.ADMIN, "A", NavigationService.Route.ADMIN_MENU, false, null, null,
                            null, null, null));
            assertThatNullPointerException()
                    .as("an admitted turn must name the operator")
                    .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                            AuthenticationService.Decision.ADMITTED, null, ADMIN_USER_ID, UserType.ADMIN,
                            "A", NavigationService.Route.ADMIN_MENU, false, null, null, null, null, null));
            assertThatNullPointerException()
                    .as("an admitted turn must nominate a destination")
                    .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                            AuthenticationService.Decision.ADMITTED, ADMIN_USER_ID, ADMIN_USER_ID,
                            UserType.ADMIN, "A", null, false, null, null, null, null, null));
            assertThatNullPointerException()
                    .as("an admitted turn must name the resolved role")
                    .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                            AuthenticationService.Decision.ADMITTED, ADMIN_USER_ID, ADMIN_USER_ID, null,
                            "A", NavigationService.Route.ADMIN_MENU, false, null, null, null, null, null));
            assertThatNullPointerException()
                    .as("an admitted turn must name the stored role code, which the session's own type "
                            + "claim is reconciled against")
                    .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                            AuthenticationService.Decision.ADMITTED, ADMIN_USER_ID, ADMIN_USER_ID,
                            UserType.ADMIN, null, NavigationService.Route.ADMIN_MENU, false, null, null,
                            null, null, null));
            assertThatNullPointerException()
                    .as("a decision is always required")
                    .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                            null, null, null, null, null, null, false, null, null, null, null, null));
        }

        @Test
        @DisplayName("a refused turn is rejected for naming any one of the three on its own, not only for "
                + "naming all three, so no partial leak of an unverified identity is representable")
        void aRefusedTurnIsRejectedForNamingAnyOneOfTheThree() {
            // The check is a disjunction, so each identity component is exercised independently.
            assertThatIllegalArgumentException()
                    .as("an operator alone")
                    .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                            AuthenticationService.Decision.USER_NOT_FOUND, ADMIN_USER_ID, null, null,
                            null, null, true, null, null, null, null, null));
            assertThatIllegalArgumentException()
                    .as("a role alone")
                    .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                            AuthenticationService.Decision.USER_NOT_FOUND, null, null, UserType.ADMIN,
                            null, null, true, null, null, null, null, null));
            assertThatIllegalArgumentException()
                    .as("a raw role code alone")
                    .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                            AuthenticationService.Decision.USER_NOT_FOUND, null, null, null, "A", null,
                            true, null, null, null, null, null));
            assertThatIllegalArgumentException()
                    .as("a destination alone")
                    .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                            AuthenticationService.Decision.USER_NOT_FOUND, null, null, null, null,
                            NavigationService.Route.USER_MENU, true, null, null, null, null, null));
            // The display echo is deliberately OUTSIDE the rule: it is the client's own value handed
            // back for redisplay, it establishes nothing, and forbidding it is what emptied the echo the
            // response contract publishes on every rejected turn.
            assertThatCode(() -> new AuthenticationService.SignOnScreen(
                    AuthenticationService.Decision.USER_NOT_FOUND, null, ADMIN_USER_ID, null, null,
                    null, true, null, null, null, null, null))
                    .as("the display echo alone is permitted on a refused turn")
                    .doesNotThrowAnyException();
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
        @DisplayName("the screen entry points are non-transactional and the service is final, so a caught "
                + "repository failure is translated only after the repository proxy has completed rollback")
        void storeFailuresAreCaughtOutsideAnyServiceTransaction() throws NoSuchMethodException {
            assertThat(java.lang.reflect.Modifier.isFinal(
                    AuthenticationService.class.getModifiers())).isTrue();
            assertThat(AuthenticationService.class.getMethod("initialEntry")
                    .getAnnotation(Transactional.class)).isNull();
            assertThat(AuthenticationService.class.getMethod(
                    "handle", KeyAction.class, String.class, String.class)
                    .getAnnotation(Transactional.class)).isNull();
            assertThat(AuthenticationService.class.getMethod(
                    "signOn", String.class, String.class)
                    .getAnnotation(Transactional.class)).isNull();
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
