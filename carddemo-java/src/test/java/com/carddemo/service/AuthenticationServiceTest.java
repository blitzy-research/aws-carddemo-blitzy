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
import com.carddemo.support.TestDataFactory;
import java.nio.charset.StandardCharsets;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Surefire unit test for {@link AuthenticationService}: transaction {@code CC00}, the sign-on screen,
 * translated from {@code app/cbl/COSGN00C.cbl} - 260 lines and six {@code PROCEDURE DIVISION}
 * paragraphs.
 *
 * <p><strong>The one deliberate parity exception in the estate lives here.</strong> The legacy record
 * carries the operator's credential as eight characters of cleartext and the program compares it
 * directly, field against field. Reproducing that comparison would satisfy parity exactly and violate
 * the credential standard just as exactly, so the target stores a one-way digest and delegates the
 * comparison to {@link CredentialDigestService}. Every other observable property - which credentials
 * are admitted, which are refused, what the operator is told, where each role is sent - is unchanged.
 * The three tests in {@link CredentialVerificationIsADigestCheck} are what make a cleartext comparison
 * impossible to reintroduce: one succeeds although the stored and submitted values differ, one fails
 * although they are identical, and one exercises a real encoder end to end.
 *
 * <p><strong>Where each of the six paragraphs is proved.</strong>
 * <ul>
 *   <li>{@code MAIN-PARA} - {@link MainParaDispatchesOnTheAttentionKey}, covering both the
 *       zero-length-communication-area entry and the attention-key clause order.</li>
 *   <li>{@code PROCESS-ENTER-KEY} - {@link ProcessEnterKeyRunsAnOrderedCascade}, covering the ordered
 *       presence clauses and the unconditional fold that precedes them.</li>
 *   <li>{@code READ-USER-SEC-FILE} - {@link ReadUserSecFileReachesFourOutcomes} and
 *       {@link CredentialVerificationIsADigestCheck}.</li>
 *   <li>{@code SEND-SIGNON-SCREEN} - {@link TheMessageContract}, which is what that paragraph wrote.</li>
 *   <li>{@code SEND-PLAIN-TEXT} - the exit-key assertions in {@link TheMessageContract}.</li>
 *   <li>{@code POPULATE-HEADER-INFO} - {@link TheHeaderAndTheResultInvariant}.</li>
 * </ul>
 *
 * <p><strong>No credential appears in this file.</strong> The shared value the legacy provisioning
 * member seeds is never written here, in any form, in any casing. Every test that needs something to
 * present as a secret invents one at run time or uses an explicitly declared non-credential probe
 * value, and the two tests that need a stored digest use either the support factory's synthetic digest
 * or one produced from a freshly generated throwaway.
 *
 * <p><strong>The service answers with a decision, not with text.</strong> The five message literals are
 * wire contract and are resolved from the decision at the transport boundary, which the service's own
 * documentation records and which keeps one mapping site for each literal. This test therefore holds its
 * own decision-to-text oracle in {@link #WIRE_TEXT_BY_DECISION}, declares every literal itself, and
 * proves two things separately: that each literal is byte-exact and fits the legacy message field, and
 * that the service selects the matching decision for each of the five source conditions. Nothing here
 * asks a production class what the answer should be.
 *
 * <p>Provenance: {@code app/cbl/COSGN00C.cbl}, {@code app/cpy/CSUSR01Y.cpy},
 * {@code app/cpy/CSMSG01Y.cpy} and {@code app/cpy/COCOM01Y.cpy}, read as read-only reference at commit
 * SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthenticationService :: transaction CC00, the sign-on screen, six paragraphs")
class AuthenticationServiceTest {

    // =============================================================================================
    // THE INDEPENDENT ORACLE
    //
    // Every expected value below is declared here and nowhere else. None is obtained by calling the
    // class under test, the message catalogue, the string utility or any other production artefact:
    // an expectation computed by the code it is meant to constrain constrains nothing.
    // =============================================================================================

    /** Width of the legacy sign-on message work field, in character positions. */
    private static final int MESSAGE_FIELD_WIDTH = 80;

    /** Width of a shared common message, in character positions. */
    private static final int COMMON_MESSAGE_WIDTH = 50;

    /** Character length of a BCrypt digest, which is what the credential column now holds. */
    private static final int DIGEST_LENGTH = 60;

    /** Width of the credential-master key, which both the screen field and the record field declare. */
    private static final int USER_ID_WIDTH = 8;

    /** Emitted when the operator supplied no identifier. */
    private static final String MSG_ENTER_USER_ID = "Please enter User ID ...";

    /** Emitted when an identifier was supplied and no secret was. */
    private static final String MSG_ENTER_PASSWORD = "Please enter Password ...";

    /** Emitted when the record was found and the presented secret did not verify. */
    private static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    /** Emitted when the identifier is not on the credential master. */
    private static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /** Emitted when the credential master answered with anything other than found or not-found. */
    private static final String MSG_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    /**
     * The shared farewell, at its full declared width.
     *
     * <p>Forty-three visible positions and seven trailing blanks. The padding is written as a repeat so
     * the count is legible rather than a run of spaces a reformat could silently change.
     */
    private static final String MSG_THANK_YOU =
            "Thank you for using CardDemo application..." + " ".repeat(7);

    /**
     * The shared unmapped-key notice, at its full declared width.
     *
     * <p>Forty visible positions and ten trailing blanks.
     */
    private static final String MSG_INVALID_KEY =
            "Invalid key pressed. Please see below..." + " ".repeat(10);

    /**
     * Every decision that carries wire text, paired with the text it carries.
     *
     * <p>Seven of the nine decisions carry text. The two that do not are the blank first entry, whose
     * clause clears the message field before anything is written to it, and the admitted turn, which
     * transfers control rather than redisplaying a screen. {@link TheMessageContract} asserts that
     * partition explicitly, so this oracle cannot fall out of step with the decision set.
     */
    private static final Map<AuthenticationService.Decision, String> WIRE_TEXT_BY_DECISION =
            wireTextOracle();

    /** A seeded administrator identifier, keyed exactly at the record width. */
    private static final String ADMIN_USER_ID = "ADMIN001";

    /** A seeded standard-authority identifier, keyed exactly at the record width. */
    private static final String ORDINARY_USER_ID = "USER0001";

    /** A lower-case entry for the identifier, written out so the fold has an expectation to meet. */
    private static final String LOWER_CASE_USER_ENTRY = "admin001";

    /** The upper-case form the fold must produce from {@link #LOWER_CASE_USER_ENTRY}. */
    private static final String FOLDED_USER_ENTRY = "ADMIN001";

    /**
     * A lower-case entry for the secret field. Not a credential: an arbitrary probe string chosen only
     * because its fold is unambiguous and can be written out beside it.
     */
    private static final String LOWER_CASE_SECRET_ENTRY = "quiet raven";

    /** The upper-case form the fold must produce from {@link #LOWER_CASE_SECRET_ENTRY}. */
    private static final String FOLDED_SECRET_ENTRY = "QUIET RAVEN";

    /** A probe value for the secret field that differs from every other probe in this class. */
    private static final String OTHER_SECRET_ENTRY = "TILTED LANTERN";

    /**
     * Characters whose fold diverges between the platform intrinsic and the twenty-six letter table,
     * in every locale rather than in a configured one.
     *
     * <p>The first is the sharp s: the intrinsic renders it as two capital letters and therefore changes
     * the value's length, which a fixed-width field cannot absorb. The second is the micro sign, which
     * the intrinsic maps onto a Greek capital. Written as escapes so the assertion is independent of
     * how this file is encoded, and chosen for their locale independence so this test cannot become one
     * of the class of tests that pass only under the default locale.
     */
    private static final String LOCALE_DIVERGENT_CHARACTERS = "\u00df\u00b5";

    /** A short entry, below the key width, used to prove the move into the record's own key. */
    private static final String SHORT_USER_ENTRY = "user1";

    /** The eight-position key {@link #SHORT_USER_ENTRY} must be widened to before the lookup. */
    private static final String WIDENED_SHORT_USER_KEY = "USER1   ";

    /** A raw role code the estate never declared, used to prove the unconditional alternative. */
    private static final String UNDECLARED_ROLE_CODE = "Z";

    // =============================================================================================
    // THE HARNESS
    //
    // A surefire unit test: no container, no application context, no data source, no socket, no
    // filesystem. Every collaborator is mocked. The single exception is the encoder in
    // aRealEncoderBehavesAsTheStoredRepresentationRequires, which is arithmetic in this process and
    // crosses no boundary at all.
    // =============================================================================================

    /** The credential master. Doubled, so no persistence is involved in any assertion here. */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /** The digest verifier. Doubled precisely so its verdict can be divorced from value equality. */
    @Mock
    private CredentialDigestService credentialDigestService;

    /** The destination resolver. Doubled so the role split is proved by what the service asks for. */
    @Mock
    private NavigationService navigationService;

    /**
     * The shared catalogue.
     *
     * <p>Lenient because the service reads both title lines on every single turn, while the tests that
     * assert the result record's own invariant construct that record directly and reach the service not
     * at all. Under strict stubbing a shared stub that some tests do not consume is itself a failure,
     * and the alternative - restating the same two stubs in every service-invoking test - would put the
     * padded values in twenty places instead of one.
     */
    @Mock(strictness = Mock.Strictness.LENIENT)
    private MessageCatalogService messageCatalogService;

    /** Captures the key the credential master is asked for. */
    @Captor
    private ArgumentCaptor<String> lookupKeyCaptor;

    /** Captures the value handed to the digest verifier, which is what must arrive folded. */
    @Captor
    private ArgumentCaptor<CharSequence> presentedSecretCaptor;

    /** Captures the raw role code the destination resolver is asked about. */
    @Captor
    private ArgumentCaptor<String> roleCodeCaptor;

    /**
     * The abuse-resistance governor, at the figures the shipped defaults declare.
     *
     * <p>A real instance rather than a double, and built fresh per test so no specification inherits
     * another's accumulated count. The allowance is far above what any specification here spends, so the
     * governor refuses nothing in this file - which is the point: every assertion below is about the
     * legacy cascade, and the governor's own behaviour is asserted by {@code SignOnAttemptGovernorTest}
     * and by the abuse-resistance slice at the end of this file.
     */
    private SignOnAttemptGovernor attemptGovernor;

    /** Where the governor's counters are registered, so a test can read what it counted. */
    private SimpleMeterRegistry governorMeters;

    /** Subject under test, wired by constructor exactly as the container wires it. */
    private AuthenticationService subject;

    /** Collects this service's own diagnostics so they can be searched for anything sensitive. */
    private ListAppender<ILoggingEvent> logCapture;

    /** The logger the capture is attached to. */
    private Logger serviceLogger;

    /** The level that logger carried before the capture lowered it, restored afterwards. */
    private Level previousLogLevel;

    /**
     * The clock, fixed at the upstream release stamp so the header is a constant rather than a moving
     * target.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:33Z"), ZoneOffset.UTC);

    /** Failures the governor in this file allows, well above what any specification here spends. */
    private static final int GOVERNOR_ALLOWANCE = 10;

    /** The header date the fixed clock renders, in the screen's own two-digit-year form. */
    private static final String EXPECTED_HEADER_DATE = "07/19/22";

    /** The header time the fixed clock renders. */
    private static final String EXPECTED_HEADER_TIME = "23:12:33";

    /**
     * Builds the decision-to-text oracle.
     *
     * <p>An {@link EnumMap} rather than a literal map so the ordering is the declaration order of the
     * decision set, which makes a missing entry obvious when the partition assertion prints.
     *
     * @return the seven message-bearing decisions paired with their frozen text
     */
    private static Map<AuthenticationService.Decision, String> wireTextOracle() {
        final Map<AuthenticationService.Decision, String> oracle =
                new EnumMap<>(AuthenticationService.Decision.class);
        oracle.put(AuthenticationService.Decision.USER_ID_MISSING, MSG_ENTER_USER_ID);
        oracle.put(AuthenticationService.Decision.PASSWORD_MISSING, MSG_ENTER_PASSWORD);
        oracle.put(AuthenticationService.Decision.WRONG_PASSWORD, MSG_WRONG_PASSWORD);
        oracle.put(AuthenticationService.Decision.USER_NOT_FOUND, MSG_USER_NOT_FOUND);
        oracle.put(AuthenticationService.Decision.UNABLE_TO_VERIFY, MSG_UNABLE_TO_VERIFY);
        oracle.put(AuthenticationService.Decision.SIGNED_OFF, MSG_THANK_YOU);
        oracle.put(AuthenticationService.Decision.KEY_NOT_MAPPED, MSG_INVALID_KEY);
        return Map.copyOf(oracle);
    }

    /** Wires the service over its doubles and attaches the diagnostic capture. */
    @BeforeEach
    void wireTheServiceAndCaptureItsDiagnostics() {
        // The two title lines are stubbed with the padded common messages deliberately: they are the
        // only catalogue values that flow through this service, so they are the only place its
        // pass-through fidelity can be observed, and these are the two values whose padding is
        // contractual.
        when(messageCatalogService.screenTitle01()).thenReturn(MSG_THANK_YOU);
        when(messageCatalogService.screenTitle02()).thenReturn(MSG_INVALID_KEY);

        governorMeters = new SimpleMeterRegistry();
        attemptGovernor = new SignOnAttemptGovernor(true, GOVERNOR_ALLOWANCE,
                Duration.ofMinutes(5), Duration.ofMinutes(1), 10_000, FIXED_CLOCK, governorMeters);
        subject = new AuthenticationService(userSecurityRepository, credentialDigestService,
                navigationService, messageCatalogService, FIXED_CLOCK, attemptGovernor);

        serviceLogger = (Logger) LoggerFactory.getLogger(AuthenticationService.class);
        previousLogLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.TRACE);
        logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger.addAppender(logCapture);
    }

    /** Detaches the capture and restores the logger, so no test leaks configuration into the next. */
    @AfterEach
    void releaseTheDiagnosticCapture() {
        serviceLogger.detachAppender(logCapture);
        serviceLogger.setLevel(previousLogLevel);
        logCapture.stop();
    }

    /**
     * Returns every diagnostic the service emitted during the current test, already formatted.
     *
     * @return the captured messages in emission order
     */
    private List<String> capturedDiagnostics() {
        final List<String> formatted = new ArrayList<>(logCapture.list.size());
        for (final ILoggingEvent event : logCapture.list) {
            formatted.add(event.getFormattedMessage());
        }
        return formatted;
    }

    /**
     * Builds a stored identity carrying a digest, at the given key and role code.
     *
     * <p>The digest is the support factory's synthetic one: structurally a digest, so the entity accepts
     * it, and demonstrably not a credential. Whether it verifies is entirely the doubled verifier's
     * decision, which is the property the digest tests turn on.
     *
     * @param userId the eight-position key the record is stored under
     * @param roleCode the raw one-character role code
     * @return the stored identity
     */
    private static UserSecurity storedIdentity(final String userId, final String roleCode) {
        return TestDataFactory.userSecurity()
                .userId(userId)
                .firstName("Test")
                .lastName("Operator")
                .userTypeCode(roleCode)
                .storedDigest(TestDataFactory.SYNTHETIC_BCRYPT_DIGEST)
                .build();
    }

    /**
     * Admits whatever secret is presented against the given stored identity.
     *
     * @param roleCode the raw role code the resolved destination is asked about
     * @param route the destination the resolver answers with
     */
    private void admitAnySecret(final String roleCode, final NavigationService.Route route) {
        when(credentialDigestService.matches(any(), eq(TestDataFactory.SYNTHETIC_BCRYPT_DIGEST)))
                .thenReturn(true);
        when(navigationService.resolveSignOnRouteForUserTypeCode(roleCode)).thenReturn(route);
    }

    /**
     * Produces a credential-shaped value that has never been used before and is discarded immediately.
     *
     * <p>Generated rather than written down. Nothing in this module holds the value the legacy
     * provisioning member seeds, and nothing in this file needs to: a digest either accepts the value it
     * was produced from or it does not, and that property is independent of which value that was.
     *
     * @return a fresh throwaway value
     */
    private static String freshThrowawayValue() {
        return UUID.randomUUID().toString();
    }

    // =============================================================================================
    // MAIN-PARA
    // =============================================================================================

    /** The attention-key dispatch and the entry that precedes it. */
    @Nested
    @DisplayName("MAIN-PARA :: the entry test and the attention-key clauses, in source order")
    class MainParaDispatchesOnTheAttentionKey {

        @Test
        @DisplayName("the zero-length entry answers with the blank screen, the identifier field focused "
                + "and no destination, before any attention key is evaluated")
        void theZeroLengthEntryPrecedesTheKeyDispatch() {
            final AuthenticationService.SignOnScreen screen = subject.initialEntry();

            assertAll(
                    () -> assertThat(screen.decision())
                            .isEqualTo(AuthenticationService.Decision.INITIAL_ENTRY),
                    () -> assertThat(screen.errorFlag()).isFalse(),
                    () -> assertThat(screen.focusScreenFieldId())
                            .isEqualTo(AuthenticationService.FIELD_USER_ID),
                    () -> assertThat(screen.userId()).isNull(),
                    () -> assertThat(screen.displayUserId()).isNull(),
                    () -> assertThat(screen.route()).isNull());
            verifyNoInteractions(userSecurityRepository, credentialDigestService, navigationService);
        }

        @Test
        @DisplayName("the enter key drives an attempt, the exit key ends the turn, and an absent key is "
                + "the unmapped case rather than an attempt with nothing keyed")
        void eachClauseTakesItsOwnKey() {
            assertAll(
                    () -> assertThat(subject.handle(KeyAction.ENTER, null, null).decision())
                            .isEqualTo(AuthenticationService.Decision.USER_ID_MISSING),
                    () -> assertThat(subject.handle(KeyAction.PFK03, ADMIN_USER_ID, OTHER_SECRET_ENTRY)
                            .decision()).isEqualTo(AuthenticationService.Decision.SIGNED_OFF),
                    () -> assertThat(subject.handle(null, ADMIN_USER_ID, OTHER_SECRET_ENTRY).decision())
                            .isEqualTo(AuthenticationService.Decision.KEY_NOT_MAPPED),
                    () -> assertThat(subject.handle(KeyAction.CLEAR, ADMIN_USER_ID, OTHER_SECRET_ENTRY)
                            .decision()).isEqualTo(AuthenticationService.Decision.KEY_NOT_MAPPED));

            // Only the first clause reaches the credential master, so no key this screen does not map
            // can cause a read - which is the security consequence of the clause order, not merely a
            // reproduction of it.
            verifyNoInteractions(userSecurityRepository, credentialDigestService);
        }

        @ParameterizedTest
        @EnumSource(value = KeyAction.class, names = {"ENTER", "PFK03"}, mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("every remaining key in the vocabulary is the unmapped case, so the catch-all holds "
                + "across the whole key set rather than for a sampled few")
        void everyRemainingKeyIsUnmapped(final KeyAction keyAction) {
            final AuthenticationService.SignOnScreen screen =
                    subject.handle(keyAction, ADMIN_USER_ID, OTHER_SECRET_ENTRY);

            assertAll(
                    () -> assertThat(screen.decision())
                            .isEqualTo(AuthenticationService.Decision.KEY_NOT_MAPPED),
                    () -> assertThat(screen.errorFlag())
                            .as("the unmapped-key clause raises the flag")
                            .isTrue(),
                    () -> assertThat(screen.route()).isNull());
        }

        @Test
        @DisplayName("the exit key leaves the flag lowered while the unmapped key raises it, which is the "
                + "second of the two flag asymmetries this program carries")
        void theExitKeyAndTheUnmappedKeyDifferInTheFlag() {
            assertAll(
                    () -> assertThat(subject.handle(KeyAction.PFK03, null, null).errorFlag())
                            .as("the exit clause writes plain text and raises nothing")
                            .isFalse(),
                    () -> assertThat(subject.handle(KeyAction.PFK09, null, null).errorFlag())
                            .as("the catch-all clause redisplays the screen and raises the flag")
                            .isTrue());
        }
    }

    // =============================================================================================
    // PROCESS-ENTER-KEY
    // =============================================================================================

    /** The ordered presence cascade and the unconditional fold that precedes it. */
    @Nested
    @DisplayName("PROCESS-ENTER-KEY :: the ordered presence cascade and the fold before it")
    class ProcessEnterKeyRunsAnOrderedCascade {

        @Test
        @DisplayName("with neither field supplied the identifier is reported, proving the identifier "
                + "clause is evaluated before the secret clause")
        void theIdentifierIsValidatedBeforeTheSecret() {
            final AuthenticationService.SignOnScreen screen = subject.signOn(null, null);

            assertAll(
                    () -> assertThat(screen.decision())
                            .as("the secret is missing too, and is not what is reported")
                            .isEqualTo(AuthenticationService.Decision.USER_ID_MISSING),
                    () -> assertThat(screen.focusScreenFieldId())
                            .isEqualTo(AuthenticationService.FIELD_USER_ID),
                    () -> assertThat(screen.errorFlag()).isTrue());
        }

        @Test
        @DisplayName("a missing identifier never reaches the credential master, matching the guard that "
                + "the read happens only when no rejection was already reached")
        void aMissingIdentifierReachesNoCollaborator() {
            assertThat(subject.signOn("   ", OTHER_SECRET_ENTRY).decision())
                    .isEqualTo(AuthenticationService.Decision.USER_ID_MISSING);

            verifyNoInteractions(userSecurityRepository);
            verifyNoInteractions(credentialDigestService);
        }

        @Test
        @DisplayName("a missing secret never reaches the digest verifier, and reports the secret field "
                + "rather than the identifier field")
        void aMissingSecretReachesNoVerifier() {
            final AuthenticationService.SignOnScreen screen = subject.signOn(ADMIN_USER_ID, "");

            assertAll(
                    () -> assertThat(screen.decision())
                            .isEqualTo(AuthenticationService.Decision.PASSWORD_MISSING),
                    () -> assertThat(screen.focusScreenFieldId())
                            .isEqualTo(AuthenticationService.FIELD_PASSWORD),
                    () -> assertThat(screen.errorFlag()).isTrue());
            verifyNoInteractions(credentialDigestService);
            verifyNoInteractions(userSecurityRepository);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "        ", "\0", "\0\0\0\0\0\0\0\0"})
        @DisplayName("an empty entry, an all-blank entry and an all-low-value entry are one state, "
                + "because a fixed-width field the operator left alone arrives filled")
        void everyFormOfAnUnsuppliedFieldIsTheSameState(final String unsupplied) {
            assertAll(
                    () -> assertThat(subject.signOn(unsupplied, OTHER_SECRET_ENTRY).decision())
                            .isEqualTo(AuthenticationService.Decision.USER_ID_MISSING),
                    () -> assertThat(subject.signOn(ADMIN_USER_ID, unsupplied).decision())
                            .isEqualTo(AuthenticationService.Decision.PASSWORD_MISSING));

            verifyNoInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("a null entry is the unsupplied state and escapes no exception, so an absent field "
                + "and a blank field are indistinguishable as the fixed-width contract requires")
        void aNullEntryIsUnsuppliedAndThrowsNothing() {
            assertAll(
                    () -> assertThatCode(() -> subject.signOn(null, null)).doesNotThrowAnyException(),
                    () -> assertThatCode(() -> subject.signOn(null, OTHER_SECRET_ENTRY))
                            .doesNotThrowAnyException(),
                    () -> assertThatCode(() -> subject.signOn(ADMIN_USER_ID, null))
                            .doesNotThrowAnyException(),
                    () -> assertThat(subject.signOn(ADMIN_USER_ID, null).decision())
                            .isEqualTo(AuthenticationService.Decision.PASSWORD_MISSING));
        }

        @Test
        @DisplayName("a mixture of blanks and low values is a supplied field, because the source tests "
                + "for one figurative constant or the other and not for a blend of the two")
        void aMixtureOfBlanksAndLowValuesIsSupplied() {
            when(userSecurityRepository.findById(any())).thenReturn(Optional.empty());

            assertThat(subject.signOn(" \0 \0 \0 \0", OTHER_SECRET_ENTRY).decision())
                    .as("neither figurative test matches, so the cascade falls through to the read")
                    .isEqualTo(AuthenticationService.Decision.USER_NOT_FOUND);
        }

        @ParameterizedTest
        @ValueSource(strings = {"\t", "\n", "\u2003"})
        @DisplayName("a tab, a line separator and a Unicode space are supplied characters, so none of "
                + "them takes a missing-field arm the legacy field would not have taken")
        void platformWhitespaceIsNotAFigurativeBlank(final String whitespace) {
            when(userSecurityRepository.findById(any())).thenReturn(Optional.empty());

            assertThat(subject.signOn(whitespace, OTHER_SECRET_ENTRY).decision())
                    .isEqualTo(AuthenticationService.Decision.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("a rejected turn echoes the keyed identifier back, folded and moved into the "
                + "record's own eight positions, so a redisplayed screen can restate it")
        void aRejectedTurnEchoesTheKeyedIdentifier() {
            final AuthenticationService.SignOnScreen screen =
                    subject.signOn(SHORT_USER_ENTRY, null);

            assertAll(
                    () -> assertThat(screen.decision())
                            .isEqualTo(AuthenticationService.Decision.PASSWORD_MISSING),
                    () -> assertThat(screen.displayUserId()).isEqualTo(WIDENED_SHORT_USER_KEY),
                    () -> assertThat(screen.displayUserId()
                            .getBytes(StandardCharsets.US_ASCII).length)
                            .as("the echo is the field's own width, measured on encoded bytes")
                            .isEqualTo(USER_ID_WIDTH),
                    () -> assertThat(screen.userId())
                            .as("the echo is not an authenticated identity")
                            .isNull());
        }
    }

    // =============================================================================================
    // READ-USER-SEC-FILE :: the credential comparison, and the parity exception it embodies
    // =============================================================================================

    /**
     * The governing group for this file: the comparison is a digest verification and cannot be an
     * equality test.
     */
    @Nested
    @DisplayName("READ-USER-SEC-FILE :: the comparison is a digest verification, never an equality test")
    class CredentialVerificationIsADigestCheck {

        @Test
        @DisplayName("both the identifier and the secret arrive folded at their collaborators, because "
                + "the source folds both and compares the folded secret")
        void bothFieldsArriveFolded() {
            when(userSecurityRepository.findById(FOLDED_USER_ENTRY))
                    .thenReturn(Optional.of(storedIdentity(FOLDED_USER_ENTRY, UserType.ADMIN.getCode())));
            admitAnySecret(UserType.ADMIN.getCode(), NavigationService.Route.ADMIN_MENU);

            final AuthenticationService.SignOnScreen screen =
                    subject.signOn(LOWER_CASE_USER_ENTRY, LOWER_CASE_SECRET_ENTRY);

            verify(userSecurityRepository).findById(lookupKeyCaptor.capture());
            verify(credentialDigestService).matches(presentedSecretCaptor.capture(), any());
            assertAll(
                    () -> assertThat(lookupKeyCaptor.getValue())
                            .as("a lower-case entry must find the key the record is stored under")
                            .isEqualTo(FOLDED_USER_ENTRY),
                    () -> assertThat(presentedSecretCaptor.getValue().toString())
                            .as("folding only the identifier would refuse a secret the source admits")
                            .isEqualTo(FOLDED_SECRET_ENTRY),
                    () -> assertThat(screen.decision())
                            .isEqualTo(AuthenticationService.Decision.ADMITTED));
        }

        @Test
        @DisplayName("a character the platform's own case conversion would transform is left untouched, "
                + "which is what distinguishes the twenty-six letter table from the intrinsic")
        void aCharacterOutsideTheTableIsLeftUntouched() {
            final String entryWithDivergentCharacters =
                    LOCALE_DIVERGENT_CHARACTERS + LOWER_CASE_SECRET_ENTRY;
            final String expectedFold = LOCALE_DIVERGENT_CHARACTERS + FOLDED_SECRET_ENTRY;

            when(userSecurityRepository.findById(ADMIN_USER_ID))
                    .thenReturn(Optional.of(storedIdentity(ADMIN_USER_ID, UserType.ADMIN.getCode())));
            when(credentialDigestService.matches(any(), any())).thenReturn(false);

            subject.signOn(ADMIN_USER_ID, entryWithDivergentCharacters);

            verify(credentialDigestService).matches(presentedSecretCaptor.capture(), any());
            final String presented = presentedSecretCaptor.getValue().toString();
            assertAll(
                    () -> assertThat(presented)
                            .as("the intrinsic renders one of these as two letters and maps the other "
                                    + "onto a different alphabet; the table passes both through")
                            .isEqualTo(expectedFold),
                    () -> assertThat(presented).startsWith(LOCALE_DIVERGENT_CHARACTERS),
                    () -> assertThat(presented.length())
                            .as("the fold is length preserving, which the intrinsic is not")
                            .isEqualTo(entryWithDivergentCharacters.length()));
        }

        @Test
        @DisplayName("the operator is admitted although the stored value and the submitted value are "
                + "different, so no equality test can be what decided it")
        void admissionFollowsTheVerifierAndNotEquality() {
            final UserSecurity stored = storedIdentity(ADMIN_USER_ID, UserType.ADMIN.getCode());
            when(userSecurityRepository.findById(ADMIN_USER_ID)).thenReturn(Optional.of(stored));
            admitAnySecret(UserType.ADMIN.getCode(), NavigationService.Route.ADMIN_MENU);

            final AuthenticationService.SignOnScreen screen =
                    subject.signOn(ADMIN_USER_ID, FOLDED_SECRET_ENTRY);

            assertAll(
                    () -> assertThat(stored.credentialDigest())
                            .as("the premise of this test: the two values are not equal")
                            .isNotEqualTo(FOLDED_SECRET_ENTRY),
                    () -> assertThat(screen.decision())
                            .as("an implementation comparing the two values directly fails here")
                            .isEqualTo(AuthenticationService.Decision.ADMITTED));
        }

        @Test
        @DisplayName("the operator is refused although the stored value and the submitted value are "
                + "identical, which together with the previous case leaves equality no room at all")
        void refusalFollowsTheVerifierAndNotEquality() {
            // The stored value is stubbed to be, character for character, the folded value that will be
            // submitted. A real stored identity cannot hold such a value - the entity refuses anything
            // that is not structurally a digest, which is itself part of the defence - so the identity is
            // doubled for this one case in order to construct the equality the assertion must defeat.
            final UserSecurity stored = mock(UserSecurity.class);
            when(stored.credentialDigest()).thenReturn(FOLDED_SECRET_ENTRY);
            when(userSecurityRepository.findById(ADMIN_USER_ID)).thenReturn(Optional.of(stored));
            when(credentialDigestService.matches(any(), eq(FOLDED_SECRET_ENTRY))).thenReturn(false);

            final AuthenticationService.SignOnScreen screen =
                    subject.signOn(ADMIN_USER_ID, FOLDED_SECRET_ENTRY);

            assertAll(
                    () -> assertThat(stored.credentialDigest())
                            .as("the premise of this test: the two values are equal")
                            .isEqualTo(FOLDED_SECRET_ENTRY),
                    () -> assertThat(screen.decision())
                            .as("an implementation comparing the two values directly admits here")
                            .isEqualTo(AuthenticationService.Decision.WRONG_PASSWORD),
                    () -> assertThat(screen.userId()).isNull(),
                    () -> assertThat(screen.route()).isNull());
        }

        @Test
        @DisplayName("a real encoder answers for a freshly generated value it has never seen: sixty "
                + "characters, unequal to its input, accepting only that input, and salted per call")
        void aRealEncoderBehavesAsTheStoredRepresentationRequires() {
            final BCryptPasswordEncoder encoder =
                    new BCryptPasswordEncoder(TestDataFactory.BCRYPT_WORK_FACTOR);
            final String throwaway = freshThrowawayValue();
            final String otherThrowaway = freshThrowawayValue();

            final String digest = encoder.encode(throwaway);
            final String secondDigestOfTheSameValue = encoder.encode(throwaway);

            assertAll(
                    () -> assertThat(digest)
                            .as("the column is declared at this width, the only intentional width change "
                                    + "in the eleven-table schema")
                            .hasSize(DIGEST_LENGTH),
                    () -> assertThat(digest)
                            .as("a digest is not its input, which is the whole point of storing one")
                            .isNotEqualTo(throwaway),
                    () -> assertThat(encoder.matches(throwaway, digest))
                            .as("the value it was produced from verifies")
                            .isTrue(),
                    () -> assertThat(encoder.matches(otherThrowaway, digest))
                            .as("any other value does not")
                            .isFalse(),
                    () -> assertThat(secondDigestOfTheSameValue)
                            .as("independent salting, so two records of one value store two digests")
                            .isNotEqualTo(digest),
                    () -> assertThat(encoder.matches(throwaway, secondDigestOfTheSameValue))
                            .as("and both still verify that value")
                            .isTrue());
        }

        @Test
        @DisplayName("the credential master is consulted only by identifier, and no finder taking a "
                + "credential is invoked, because a salted digest cannot be matched by a query")
        void theCredentialMasterIsConsultedOnlyByIdentifier() {
            when(userSecurityRepository.findById(ADMIN_USER_ID))
                    .thenReturn(Optional.of(storedIdentity(ADMIN_USER_ID, UserType.ADMIN.getCode())));
            admitAnySecret(UserType.ADMIN.getCode(), NavigationService.Route.ADMIN_MENU);

            subject.signOn(ADMIN_USER_ID, FOLDED_SECRET_ENTRY);
            subject.signOn(ADMIN_USER_ID, OTHER_SECRET_ENTRY);

            // Enumerated rather than sampled: any second, unkeyed access added later - a projection, a
            // page, a scan - fails this, and so would any finder that accepted a secret.
            verify(userSecurityRepository, times(2)).findById(ADMIN_USER_ID);
            verifyNoMoreInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("the read precedes the comparison and the comparison precedes the destination "
                + "resolution, which is the order the paragraph performs them in")
        void theCollaboratorsAreReachedInTheParagraphsOwnOrder() {
            when(userSecurityRepository.findById(ADMIN_USER_ID))
                    .thenReturn(Optional.of(storedIdentity(ADMIN_USER_ID, UserType.ADMIN.getCode())));
            admitAnySecret(UserType.ADMIN.getCode(), NavigationService.Route.ADMIN_MENU);

            subject.signOn(ADMIN_USER_ID, FOLDED_SECRET_ENTRY);

            final InOrder ordered =
                    inOrder(userSecurityRepository, credentialDigestService, navigationService);
            ordered.verify(userSecurityRepository).findById(ADMIN_USER_ID);
            ordered.verify(credentialDigestService).matches(any(), any());
            ordered.verify(navigationService)
                    .resolveSignOnRouteForUserTypeCode(UserType.ADMIN.getCode());
        }
    }

    // =============================================================================================
    // READ-USER-SEC-FILE :: the four outcomes, and the flag asymmetry between two of them
    // =============================================================================================

    /** The response arms of the credential read, including the asymmetry that must not be tidied up. */
    @Nested
    @DisplayName("READ-USER-SEC-FILE :: four outcomes, and the flag asymmetry that is deliberate")
    class ReadUserSecFileReachesFourOutcomes {

        @Test
        @DisplayName("ASYMMETRY, DO NOT NORMALISE :: the wrong secret leaves the error flag LOWERED "
                + "while an unknown identifier RAISES it - the source arms genuinely differ")
        void theTwoRejectionsDifferInTheFlagAndThatIsCorrect() {
            when(userSecurityRepository.findById(ADMIN_USER_ID))
                    .thenReturn(Optional.of(storedIdentity(ADMIN_USER_ID, UserType.ADMIN.getCode())));
            when(userSecurityRepository.findById(ORDINARY_USER_ID)).thenReturn(Optional.empty());
            when(credentialDigestService.matches(any(), any())).thenReturn(false);

            final AuthenticationService.SignOnScreen wrongSecret =
                    subject.signOn(ADMIN_USER_ID, OTHER_SECRET_ENTRY);
            final AuthenticationService.SignOnScreen unknownIdentifier =
                    subject.signOn(ORDINARY_USER_ID, OTHER_SECRET_ENTRY);

            assertAll(
                    () -> assertThat(wrongSecret.decision())
                            .isEqualTo(AuthenticationService.Decision.WRONG_PASSWORD),
                    () -> assertThat(wrongSecret.errorFlag())
                            .as("the failed comparison composes a message and raises nothing; a "
                                    + "reviewer who 'fixes' this breaks the response contract")
                            .isFalse(),
                    () -> assertThat(wrongSecret.focusScreenFieldId())
                            .isEqualTo(AuthenticationService.FIELD_PASSWORD),
                    () -> assertThat(unknownIdentifier.decision())
                            .isEqualTo(AuthenticationService.Decision.USER_NOT_FOUND),
                    () -> assertThat(unknownIdentifier.errorFlag())
                            .as("the not-found arm does raise it")
                            .isTrue(),
                    () -> assertThat(unknownIdentifier.focusScreenFieldId())
                            .isEqualTo(AuthenticationService.FIELD_USER_ID));
        }

        @Test
        @DisplayName("a credential store that fails with anything other than not-found becomes the "
                + "catch-all screen, raising the flag, and the failure does not escape the turn")
        void aStoreFailureBecomesTheCatchAllScreen() {
            when(userSecurityRepository.findById(ADMIN_USER_ID))
                    .thenThrow(new DataAccessResourceFailureException("store unreachable"));

            final AuthenticationService.SignOnScreen screen =
                    subject.signOn(ADMIN_USER_ID, OTHER_SECRET_ENTRY);

            assertAll(
                    () -> assertThat(screen.decision())
                            .isEqualTo(AuthenticationService.Decision.UNABLE_TO_VERIFY),
                    () -> assertThat(screen.errorFlag()).isTrue(),
                    () -> assertThat(screen.focusScreenFieldId())
                            .isEqualTo(AuthenticationService.FIELD_USER_ID),
                    () -> assertThat(screen.userId()).isNull());
            verifyNoInteractions(credentialDigestService);
        }

        @Test
        @DisplayName("a store failure is translated rather than propagated at BOTH screen entry points, "
                + "so no caller has to handle a persistence exception")
        void aStoreFailureIsTranslatedRatherThanPropagated() {
            when(userSecurityRepository.findById(any()))
                    .thenThrow(new DataAccessResourceFailureException("store unreachable"));

            // The sibling above covers signOn alone. What this one adds is the OTHER entry point: handle
            // delegates the ENTER key to signOn, so the translation has to reach a caller who only ever
            // presses a key. Both are asserted to produce the same screen rather than merely to return.
            final AuthenticationService.SignOnScreen throughSignOn =
                    subject.signOn(ADMIN_USER_ID, OTHER_SECRET_ENTRY);
            final AuthenticationService.SignOnScreen throughKeyAction =
                    subject.handle(KeyAction.ENTER, ADMIN_USER_ID, OTHER_SECRET_ENTRY);

            assertAll(
                    () -> assertThat(throughSignOn.decision())
                            .as("the persistence failure becomes the catch-all decision, not an escape")
                            .isEqualTo(AuthenticationService.Decision.UNABLE_TO_VERIFY),
                    () -> assertThat(throughKeyAction.decision())
                            .as("and the key-driven entry point reaches the same one")
                            .isEqualTo(AuthenticationService.Decision.UNABLE_TO_VERIFY),
                    () -> assertThat(WIRE_TEXT_BY_DECISION.get(throughKeyAction.decision()))
                            .as("which is the decision the frozen wire text is written for")
                            .isEqualTo(MSG_UNABLE_TO_VERIFY),
                    () -> assertThat(throughKeyAction.errorFlag())
                            .as("the catch-all arm raises the flag")
                            .isTrue(),
                    () -> assertThat(throughKeyAction.focusScreenFieldId())
                            .as("and positions the cursor on the identifier field")
                            .isEqualTo(AuthenticationService.FIELD_USER_ID),
                    () -> assertThat(throughKeyAction.userId())
                            .as("a turn that could not verify names no authenticated operator")
                            .isNull(),
                    () -> assertThat(throughKeyAction.userType()).isNull(),
                    () -> assertThat(throughKeyAction.userTypeCode()).isNull(),
                    () -> assertThat(throughKeyAction.route())
                            .as("and offers no destination, because nothing was admitted")
                            .isNull(),
                    () -> assertThat(throughKeyAction)
                            .as("the two entry points do not merely both succeed - they produce the same "
                                    + "screen, which is what makes handle a delegation rather than a "
                                    + "second implementation")
                            .isEqualTo(throughSignOn));
            verifyNoInteractions(credentialDigestService, navigationService);
        }

        @Test
        @DisplayName("an admitted turn names the operator, the resolved authority, the raw role code and "
                + "a destination, and leaves the flag lowered with no field singled out")
        void anAdmittedTurnNamesEverythingTheBoundaryNeeds() {
            when(userSecurityRepository.findById(ORDINARY_USER_ID))
                    .thenReturn(Optional.of(storedIdentity(ORDINARY_USER_ID, UserType.USER.getCode())));
            admitAnySecret(UserType.USER.getCode(), NavigationService.Route.USER_MENU);

            final AuthenticationService.SignOnScreen screen =
                    subject.signOn(ORDINARY_USER_ID, FOLDED_SECRET_ENTRY);

            assertAll(
                    () -> assertThat(screen.decision())
                            .isEqualTo(AuthenticationService.Decision.ADMITTED),
                    () -> assertThat(screen.decision().isAdmitted()).isTrue(),
                    () -> assertThat(screen.userId()).isEqualTo(ORDINARY_USER_ID),
                    () -> assertThat(screen.displayUserId()).isEqualTo(ORDINARY_USER_ID),
                    () -> assertThat(screen.userType()).isEqualTo(UserType.USER),
                    () -> assertThat(screen.userTypeCode()).isEqualTo(UserType.USER.getCode()),
                    () -> assertThat(screen.route()).isEqualTo(NavigationService.Route.USER_MENU),
                    () -> assertThat(screen.errorFlag()).isFalse(),
                    () -> assertThat(screen.focusScreenFieldId()).isNull());
        }

        @Test
        @DisplayName("a short entry is moved into the record's own eight positions before the lookup, and "
                + "the raw short value is never used as a key")
        void aShortEntryIsWidenedToTheRecordKeyBeforeTheLookup() {
            when(userSecurityRepository.findById(any())).thenReturn(Optional.empty());

            assertThat(subject.signOn(SHORT_USER_ENTRY, OTHER_SECRET_ENTRY).decision())
                    .isEqualTo(AuthenticationService.Decision.USER_NOT_FOUND);

            // A terminal delivered eight positions whatever the operator typed. A transport caller
            // delivers what was typed, and against a keyed column a shorter value is a different key.
            verify(userSecurityRepository).findById(WIDENED_SHORT_USER_KEY);
            verify(userSecurityRepository, never()).findById(SHORT_USER_ENTRY);
            verify(userSecurityRepository, never()).findById(FOLDED_USER_ENTRY);
            verifyNoMoreInteractions(userSecurityRepository);
        }

        @Test
        @DisplayName("an entry already at the key width is passed through unchanged, so the move costs "
                + "nothing on the ordinary path")
        void anEntryAtTheKeyWidthIsUnchanged() {
            when(userSecurityRepository.findById(ADMIN_USER_ID)).thenReturn(Optional.empty());

            assertThat(subject.signOn(ADMIN_USER_ID, OTHER_SECRET_ENTRY).decision())
                    .isEqualTo(AuthenticationService.Decision.USER_NOT_FOUND);

            verify(userSecurityRepository).findById(lookupKeyCaptor.capture());
            assertThat(lookupKeyCaptor.getValue())
                    .isEqualTo(ADMIN_USER_ID)
                    .hasSize(USER_ID_WIDTH);
        }

        @Test
        @DisplayName("the fold is applied before the move, so a short lower-case entry is folded and "
                + "then widened, in that order")
        void theFoldPrecedesTheMove() {
            when(userSecurityRepository.findById(any())).thenReturn(Optional.empty());

            subject.signOn(SHORT_USER_ENTRY, OTHER_SECRET_ENTRY);

            verify(userSecurityRepository).findById(lookupKeyCaptor.capture());
            assertThat(lookupKeyCaptor.getValue())
                    .as("folded to upper case first, then space filled to the record width")
                    .isEqualTo(WIDENED_SHORT_USER_KEY);
        }
    }

    // =============================================================================================
    // The role split - the only role decision this transaction makes
    // =============================================================================================

    /** The two-arm role test whose alternative is unconditional. */
    @Nested
    @DisplayName("the role split :: the administrative code alone takes the first arm")
    class TheRoleSplitHasAnUnconditionalAlternative {

        @ParameterizedTest
        @CsvSource({
            "A, ADMIN_MENU, ADMIN",
            "U, USER_MENU,  USER",
            "Z, USER_MENU,  USER",
        })
        @DisplayName("the administrative code reaches the administrative menu and every other code, "
                + "including one the estate never declared, reaches the main menu")
        void everyCodeOtherThanTheAdministrativeOneReachesTheMainMenu(
                final String rawRoleCode,
                final NavigationService.Route expectedRoute,
                final UserType expectedAuthority) {
            when(userSecurityRepository.findById(ORDINARY_USER_ID))
                    .thenReturn(Optional.of(storedIdentity(ORDINARY_USER_ID, rawRoleCode)));
            admitAnySecret(rawRoleCode, expectedRoute);

            final AuthenticationService.SignOnScreen screen =
                    subject.signOn(ORDINARY_USER_ID, FOLDED_SECRET_ENTRY);

            // The destination is asserted through what the resolver was asked for rather than through a
            // route string restated here, so this test cannot drift from the resolver's own table.
            verify(navigationService).resolveSignOnRouteForUserTypeCode(roleCodeCaptor.capture());
            assertAll(
                    () -> assertThat(roleCodeCaptor.getValue())
                            .as("the raw stored code is what the split is decided on")
                            .isEqualTo(rawRoleCode),
                    () -> assertThat(screen.route()).isEqualTo(expectedRoute),
                    () -> assertThat(screen.userType()).isEqualTo(expectedAuthority),
                    () -> assertThat(screen.userTypeCode())
                            .as("the raw code travels, so a session claim can be reconciled against it")
                            .isEqualTo(rawRoleCode));
        }

        @Test
        @DisplayName("an undeclared code resolves to standard authority rather than failing the read, "
                + "because an unrecognised role is still a record that was found")
        void anUndeclaredCodeIsStillASuccessfulRead() {
            when(userSecurityRepository.findById(ORDINARY_USER_ID))
                    .thenReturn(Optional.of(storedIdentity(ORDINARY_USER_ID, UNDECLARED_ROLE_CODE)));
            admitAnySecret(UNDECLARED_ROLE_CODE, NavigationService.Route.USER_MENU);

            final AuthenticationService.SignOnScreen screen =
                    subject.signOn(ORDINARY_USER_ID, FOLDED_SECRET_ENTRY);

            assertAll(
                    () -> assertThat(screen.decision())
                            .isEqualTo(AuthenticationService.Decision.ADMITTED),
                    () -> assertThat(screen.userType()).isEqualTo(UserType.USER),
                    () -> assertThat(screen.route().isAdminScoped())
                            .as("an unrecognised code must not reach an administrative destination")
                            .isFalse());
        }
    }

    // =============================================================================================
    // SEND-SIGNON-SCREEN and SEND-PLAIN-TEXT :: what those two paragraphs wrote
    // =============================================================================================

    /** The seven message texts, their widths, and the decision each one is reached by. */
    @Nested
    @DisplayName("SEND-SIGNON-SCREEN and SEND-PLAIN-TEXT :: the seven message texts and their widths")
    class TheMessageContract {

        @Test
        @DisplayName("the five texts this program composes itself are byte exact, untrimmed, and fit the "
                + "eighty-position message field measured on encoded bytes")
        void theFiveComposedTextsAreByteExactAndFitTheField() {
            final List<String> composed = List.of(MSG_ENTER_USER_ID, MSG_ENTER_PASSWORD,
                    MSG_WRONG_PASSWORD, MSG_USER_NOT_FOUND, MSG_UNABLE_TO_VERIFY);

            assertAll(
                    () -> assertThat(MSG_ENTER_USER_ID).isEqualTo("Please enter User ID ..."),
                    () -> assertThat(MSG_ENTER_PASSWORD).isEqualTo("Please enter Password ..."),
                    () -> assertThat(MSG_WRONG_PASSWORD).isEqualTo("Wrong Password. Try again ..."),
                    () -> assertThat(MSG_USER_NOT_FOUND).isEqualTo("User not found. Try again ..."),
                    () -> assertThat(MSG_UNABLE_TO_VERIFY).isEqualTo("Unable to verify the User ..."),
                    () -> assertThat(composed).doesNotHaveDuplicates().hasSize(5),
                    () -> assertThat(composed).allSatisfy(text -> assertThat(
                            text.getBytes(StandardCharsets.US_ASCII).length)
                            .as("the receiving field is a fixed eighty positions")
                            .isLessThanOrEqualTo(MESSAGE_FIELD_WIDTH)),
                    () -> assertThat(composed).allSatisfy(text -> assertThat(text)
                            .as("these five carry no padding, which is what distinguishes them from the "
                                    + "two shared messages that are declared at a fixed fifty positions")
                            .doesNotEndWith(" ")));
        }

        @Test
        @DisplayName("each of the five conditions selects its own decision, which is what determines "
                + "which of the five texts the boundary emits")
        void eachConditionSelectsTheDecisionItsTextBelongsTo() {
            when(userSecurityRepository.findById(ORDINARY_USER_ID)).thenReturn(Optional.empty());
            when(userSecurityRepository.findById(ADMIN_USER_ID))
                    .thenReturn(Optional.of(storedIdentity(ADMIN_USER_ID, UserType.ADMIN.getCode())))
                    .thenThrow(new DataAccessResourceFailureException("store unreachable"));
            when(credentialDigestService.matches(any(), any())).thenReturn(false);

            assertAll(
                    () -> assertThat(WIRE_TEXT_BY_DECISION.get(subject.signOn(null, null).decision()))
                            .isEqualTo(MSG_ENTER_USER_ID),
                    () -> assertThat(WIRE_TEXT_BY_DECISION.get(
                            subject.signOn(ORDINARY_USER_ID, null).decision()))
                            .isEqualTo(MSG_ENTER_PASSWORD),
                    () -> assertThat(WIRE_TEXT_BY_DECISION.get(
                            subject.signOn(ADMIN_USER_ID, OTHER_SECRET_ENTRY).decision()))
                            .isEqualTo(MSG_WRONG_PASSWORD),
                    () -> assertThat(WIRE_TEXT_BY_DECISION.get(
                            subject.signOn(ORDINARY_USER_ID, OTHER_SECRET_ENTRY).decision()))
                            .isEqualTo(MSG_USER_NOT_FOUND),
                    () -> assertThat(WIRE_TEXT_BY_DECISION.get(
                            subject.signOn(ADMIN_USER_ID, OTHER_SECRET_ENTRY).decision()))
                            .isEqualTo(MSG_UNABLE_TO_VERIFY));
        }

        @Test
        @DisplayName("exactly seven of the nine decisions carry text, and the two that do not are the "
                + "blank first entry and the admitted turn that transfers control")
        void theTextOracleCoversEveryMessageBearingDecisionAndNoOther() {
            final List<AuthenticationService.Decision> withoutText = List.of(
                    AuthenticationService.Decision.INITIAL_ENTRY,
                    AuthenticationService.Decision.ADMITTED);

            assertAll(
                    () -> assertThat(WIRE_TEXT_BY_DECISION).hasSize(7),
                    () -> assertThat(WIRE_TEXT_BY_DECISION.keySet())
                            .doesNotContainAnyElementsOf(withoutText),
                    () -> assertThat(WIRE_TEXT_BY_DECISION.keySet())
                            .containsExactlyInAnyOrderElementsOf(
                                    remainingDecisions(withoutText)),
                    () -> assertThat(WIRE_TEXT_BY_DECISION.values())
                            .as("no two decisions share a text, so the mapping is one to one")
                            .doesNotHaveDuplicates());
        }

        @Test
        @DisplayName("both shared messages pass through the service untrimmed at exactly fifty encoded "
                + "bytes, with their seven and ten trailing blanks intact")
        void bothSharedMessagesPassThroughUntrimmed() {
            // The catalogue is doubled, so what it hands over is exactly what this test hands it. The
            // service's only catalogue path is the pair of title lines it echoes, so that pair is where
            // padding either survives or is quietly discarded.
            final AuthenticationService.SignOnScreen screen = subject.initialEntry();

            assertAll(
                    () -> assertThat(screen.title01())
                            .as("byte identical to what the catalogue supplied")
                            .isEqualTo(MSG_THANK_YOU),
                    () -> assertThat(screen.title01().getBytes(StandardCharsets.US_ASCII).length)
                            .isEqualTo(COMMON_MESSAGE_WIDTH),
                    () -> assertThat(screen.title01())
                            .as("the seven trailing blanks are still there")
                            .endsWith(" ".repeat(7))
                            .isNotEqualTo(MSG_THANK_YOU.stripTrailing()),
                    () -> assertThat(screen.title02()).isEqualTo(MSG_INVALID_KEY),
                    () -> assertThat(screen.title02().getBytes(StandardCharsets.US_ASCII).length)
                            .isEqualTo(COMMON_MESSAGE_WIDTH),
                    () -> assertThat(screen.title02())
                            .as("the ten trailing blanks are still there")
                            .endsWith(" ".repeat(10))
                            .isNotEqualTo(MSG_INVALID_KEY.stripTrailing()));
        }

        @Test
        @DisplayName("the exit key yields the farewell and nominates no destination, because that clause "
                + "writes plain text and ends the turn instead of sending a screen")
        void theExitKeyYieldsTheFarewellAndNominatesNoDestination() {
            final AuthenticationService.SignOnScreen screen =
                    subject.handle(KeyAction.PFK03, ADMIN_USER_ID, FOLDED_SECRET_ENTRY);

            assertAll(
                    () -> assertThat(screen.decision())
                            .isEqualTo(AuthenticationService.Decision.SIGNED_OFF),
                    () -> assertThat(WIRE_TEXT_BY_DECISION.get(screen.decision()))
                            .isEqualTo(MSG_THANK_YOU),
                    () -> assertThat(screen.route())
                            .as("the turn ends rather than transferring anywhere")
                            .isNull(),
                    () -> assertThat(screen.errorFlag()).isFalse(),
                    () -> assertThat(screen.focusScreenFieldId())
                            .as("plain text singles out no field")
                            .isNull(),
                    () -> assertThat(screen.displayUserId())
                            .as("the clause reads no keyed value at all")
                            .isNull());
            verifyNoInteractions(userSecurityRepository, credentialDigestService, navigationService);
        }

        @Test
        @DisplayName("an unmapped key yields the notice, raises the flag and changes no destination, so "
                + "the operator stays on the screen they were already on")
        void anUnmappedKeyYieldsTheNoticeAndChangesNoDestination() {
            final AuthenticationService.SignOnScreen screen =
                    subject.handle(KeyAction.PA1, ADMIN_USER_ID, FOLDED_SECRET_ENTRY);

            assertAll(
                    () -> assertThat(screen.decision())
                            .isEqualTo(AuthenticationService.Decision.KEY_NOT_MAPPED),
                    () -> assertThat(WIRE_TEXT_BY_DECISION.get(screen.decision()))
                            .isEqualTo(MSG_INVALID_KEY),
                    () -> assertThat(screen.route())
                            .as("no destination is nominated, so nothing about where the operator is "
                                    + "changes")
                            .isNull(),
                    () -> assertThat(screen.errorFlag()).isTrue(),
                    () -> assertThat(screen.userId()).isNull());
            verifyNoInteractions(navigationService);
        }

        /**
         * Returns the decisions not named in the given exclusion list.
         *
         * @param excluded the decisions to leave out
         * @return every other decision, in declaration order
         */
        private List<AuthenticationService.Decision> remainingDecisions(
                final List<AuthenticationService.Decision> excluded) {
            final List<AuthenticationService.Decision> remaining = new ArrayList<>();
            for (final AuthenticationService.Decision decision
                    : AuthenticationService.Decision.values()) {
                if (!excluded.contains(decision)) {
                    remaining.add(decision);
                }
            }
            return remaining;
        }
    }

    // =============================================================================================
    // Nothing leaves this service carrying a credential
    // =============================================================================================

    /** The digest and the presented value must reach neither a result nor a diagnostic. */
    @Nested
    @DisplayName("no result, no component and no diagnostic carries the digest or the presented value")
    class NothingLeaksTheDigestOrThePresentedValue {

        @Test
        @DisplayName("neither the stored digest nor the presented value appears in any component, in the "
                + "rendered result, or in any captured diagnostic, on any of the four read outcomes")
        void neitherValueEscapesOnAnyOutcome() {
            final String presented = FOLDED_SECRET_ENTRY;
            final String storedDigest = TestDataFactory.SYNTHETIC_BCRYPT_DIGEST;
            when(userSecurityRepository.findById(ADMIN_USER_ID))
                    .thenReturn(Optional.of(storedIdentity(ADMIN_USER_ID, UserType.ADMIN.getCode())));
            when(userSecurityRepository.findById(ORDINARY_USER_ID)).thenReturn(Optional.empty());
            admitAnySecret(UserType.ADMIN.getCode(), NavigationService.Route.ADMIN_MENU);

            final List<AuthenticationService.SignOnScreen> turns = List.of(
                    subject.signOn(ADMIN_USER_ID, presented),
                    subject.signOn(ORDINARY_USER_ID, presented),
                    subject.signOn(ORDINARY_USER_ID, null),
                    subject.handle(KeyAction.PFK03, ADMIN_USER_ID, presented));

            assertAll(
                    () -> assertThat(turns).allSatisfy(turn -> assertThat(turn.toString())
                            .as("the rendered result")
                            .doesNotContain(storedDigest, presented)),
                    () -> assertThat(turns).allSatisfy(turn -> assertThat(componentsOf(turn))
                            .as("every individual component")
                            .allSatisfy(component -> assertThat(component)
                                    .doesNotContain(storedDigest)
                                    .doesNotContain(presented))),
                    () -> assertThat(capturedDiagnostics())
                            .as("every captured diagnostic, at trace level and above")
                            .isNotEmpty()
                            .allSatisfy(message -> assertThat(message)
                                    .doesNotContain(storedDigest)
                                    .doesNotContain(presented)));
        }

        @Test
        @DisplayName("a rejected identifier is omitted from the fixed outcome diagnostic, so a refused "
                + "attempt cannot be reconstructed from the log")
        void aRejectedIdentifierIsOmittedFromTheDiagnostic() {
            when(userSecurityRepository.findById(ORDINARY_USER_ID)).thenReturn(Optional.empty());

            subject.signOn(ORDINARY_USER_ID, OTHER_SECRET_ENTRY);

            assertThat(capturedDiagnostics())
                    .contains("Sign-on rejected: rule=identifier-not-on-file")
                    .allSatisfy(message -> assertThat(message).doesNotContain(ORDINARY_USER_ID));
        }

        @Test
        @DisplayName("a store failure is reported by its failure chain alone, so neither the identifier "
                + "nor anything the failure text embedded reaches the log")
        void aStoreFailureIsReportedByItsChainAlone() {
            final String failureTextCarryingContext =
                    "lookup failed for " + ORDINARY_USER_ID + " at a private endpoint";
            when(userSecurityRepository.findById(ORDINARY_USER_ID))
                    .thenThrow(new DataAccessResourceFailureException(failureTextCarryingContext));

            subject.signOn(ORDINARY_USER_ID, OTHER_SECRET_ENTRY);

            assertThat(capturedDiagnostics())
                    .anySatisfy(message -> assertThat(message)
                            .contains("failureChain=DataAccessResourceFailureException"))
                    .allSatisfy(message -> assertThat(message)
                            .doesNotContain(failureTextCarryingContext)
                            .doesNotContain(ORDINARY_USER_ID));
        }

        /**
         * Collects every textual component of a turn, so each can be searched independently of the
         * record's own rendering.
         *
         * @param turn the turn to decompose
         * @return the components that carry text, absent ones excluded
         */
        private List<String> componentsOf(final AuthenticationService.SignOnScreen turn) {
            final List<String> components = new ArrayList<>();
            for (final String candidate : List.of(
                    String.valueOf(turn.userId()),
                    String.valueOf(turn.displayUserId()),
                    String.valueOf(turn.userTypeCode()),
                    String.valueOf(turn.focusScreenFieldId()),
                    String.valueOf(turn.title01()),
                    String.valueOf(turn.title02()),
                    String.valueOf(turn.currentDate()),
                    String.valueOf(turn.currentTime()))) {
                components.add(candidate);
            }
            return components;
        }
    }

    // =============================================================================================
    // POPULATE-HEADER-INFO, and the result record's own invariant
    // =============================================================================================

    /** The header every turn carries, and the pairing rule the transport boundary relies on. */
    @Nested
    @DisplayName("POPULATE-HEADER-INFO :: the header on every turn, and the result invariant")
    class TheHeaderAndTheResultInvariant {

        @Test
        @DisplayName("every turn carries both title lines and the header date and time, read from the "
                + "clock once so the two cannot straddle midnight within one response")
        void everyTurnCarriesTheHeader() {
            when(userSecurityRepository.findById(ADMIN_USER_ID))
                    .thenReturn(Optional.of(storedIdentity(ADMIN_USER_ID, UserType.ADMIN.getCode())));
            admitAnySecret(UserType.ADMIN.getCode(), NavigationService.Route.ADMIN_MENU);

            final List<AuthenticationService.SignOnScreen> everyKindOfTurn = List.of(
                    subject.initialEntry(),
                    subject.signOn(ADMIN_USER_ID, FOLDED_SECRET_ENTRY),
                    subject.signOn(null, null),
                    subject.signOn(ADMIN_USER_ID, null),
                    subject.handle(KeyAction.PFK03, null, null),
                    subject.handle(KeyAction.PFK09, null, null));

            assertThat(everyKindOfTurn).allSatisfy(turn -> assertAll(
                    () -> assertThat(turn.title01()).isEqualTo(MSG_THANK_YOU),
                    () -> assertThat(turn.title02()).isEqualTo(MSG_INVALID_KEY),
                    () -> assertThat(turn.currentDate()).isEqualTo(EXPECTED_HEADER_DATE),
                    () -> assertThat(turn.currentTime()).isEqualTo(EXPECTED_HEADER_TIME)));
        }

        @Test
        @DisplayName("no turn that did not admit the operator names an operator, an authority, a role "
                + "code or a destination, so their presence alone decides whether a session is issued")
        void noRefusedTurnNamesAnIdentity() {
            when(userSecurityRepository.findById(ORDINARY_USER_ID))
                    .thenReturn(Optional.of(storedIdentity(ORDINARY_USER_ID, UserType.USER.getCode())));
            when(credentialDigestService.matches(any(), any())).thenReturn(false);

            final List<AuthenticationService.SignOnScreen> refusedTurns = List.of(
                    subject.initialEntry(),
                    subject.signOn(null, null),
                    subject.signOn(ORDINARY_USER_ID, null),
                    subject.signOn(ORDINARY_USER_ID, OTHER_SECRET_ENTRY),
                    subject.handle(KeyAction.PFK03, null, null),
                    subject.handle(KeyAction.PFK09, null, null));

            assertThat(refusedTurns).allSatisfy(turn -> assertAll(
                    () -> assertThat(turn.decision().isAdmitted()).isFalse(),
                    () -> assertThat(turn.userId()).isNull(),
                    () -> assertThat(turn.userType()).isNull(),
                    () -> assertThat(turn.userTypeCode()).isNull(),
                    () -> assertThat(turn.route()).isNull()));
        }

        @Test
        @DisplayName("an admitted turn is refused construction unless it names all four identity "
                + "components, each of which is required independently of the others")
        void anAdmittedTurnMustNameEveryIdentityComponent() {
            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .as("the operator")
                            .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                                    AuthenticationService.Decision.ADMITTED, null, ADMIN_USER_ID,
                                    UserType.ADMIN, UserType.ADMIN.getCode(),
                                    NavigationService.Route.ADMIN_MENU, false, null, null, null, null,
                                    null)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .as("the resolved authority")
                            .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                                    AuthenticationService.Decision.ADMITTED, ADMIN_USER_ID,
                                    ADMIN_USER_ID, null, UserType.ADMIN.getCode(),
                                    NavigationService.Route.ADMIN_MENU, false, null, null, null, null,
                                    null)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .as("the raw role code, which a session claim is reconciled against")
                            .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                                    AuthenticationService.Decision.ADMITTED, ADMIN_USER_ID,
                                    ADMIN_USER_ID, UserType.ADMIN, null,
                                    NavigationService.Route.ADMIN_MENU, false, null, null, null, null,
                                    null)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .as("the destination")
                            .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                                    AuthenticationService.Decision.ADMITTED, ADMIN_USER_ID,
                                    ADMIN_USER_ID, UserType.ADMIN, UserType.ADMIN.getCode(), null,
                                    false, null, null, null, null, null)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .as("a decision is always required")
                            .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                                    null, null, null, null, null, null, false, null, null, null, null,
                                    null)),
                    () -> assertThatCode(() -> new AuthenticationService.SignOnScreen(
                            AuthenticationService.Decision.ADMITTED, ADMIN_USER_ID, ADMIN_USER_ID,
                            UserType.ADMIN, UserType.ADMIN.getCode(),
                            NavigationService.Route.ADMIN_MENU, false, null, MSG_THANK_YOU,
                            MSG_INVALID_KEY, EXPECTED_HEADER_DATE, EXPECTED_HEADER_TIME))
                            .as("a fully named admitted turn is accepted")
                            .doesNotThrowAnyException());
        }

        @Test
        @DisplayName("a refused turn is rejected for naming any single identity component, so no partial "
                + "disclosure of an unverified identity is representable")
        void aRefusedTurnIsRejectedForNamingAnySingleComponent() {
            assertAll(
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .as("an operator alone")
                            .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                                    AuthenticationService.Decision.USER_NOT_FOUND, ADMIN_USER_ID, null,
                                    null, null, null, true, null, null, null, null, null)),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .as("an authority alone")
                            .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                                    AuthenticationService.Decision.USER_NOT_FOUND, null, null,
                                    UserType.ADMIN, null, null, true, null, null, null, null, null)),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .as("a raw role code alone")
                            .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                                    AuthenticationService.Decision.USER_NOT_FOUND, null, null, null,
                                    UserType.ADMIN.getCode(), null, true, null, null, null, null,
                                    null)),
                    () -> assertThatExceptionOfType(IllegalArgumentException.class)
                            .as("a destination alone")
                            .isThrownBy(() -> new AuthenticationService.SignOnScreen(
                                    AuthenticationService.Decision.USER_NOT_FOUND, null, null, null,
                                    null, NavigationService.Route.USER_MENU, true, null, null, null,
                                    null, null)),
                    () -> assertThatCode(() -> new AuthenticationService.SignOnScreen(
                            AuthenticationService.Decision.USER_NOT_FOUND, null, ADMIN_USER_ID, null,
                            null, null, true, AuthenticationService.FIELD_USER_ID, null, null, null,
                            null))
                            .as("the redisplay echo is deliberately outside the rule: it asserts nothing "
                                    + "about who the operator is")
                            .doesNotThrowAnyException());
        }

        @Test
        @DisplayName("only the admitted decision reports itself as admitting, across the whole decision "
                + "set, so the single test the boundary makes cannot be satisfied by another outcome")
        void onlyTheAdmittedDecisionReportsItselfAsAdmitting() {
            assertThat(AuthenticationService.Decision.values())
                    .hasSize(9)
                    .allSatisfy(decision -> assertThat(decision.isAdmitted())
                            .as("%s", decision)
                            .isEqualTo(decision == AuthenticationService.Decision.ADMITTED));
        }

        @Test
        @DisplayName("the two published screen field names are the identifier and the secret field, "
                + "which are the only fields any turn returns the cursor to")
        void thePublishedFieldNamesAreTheOnlyOnesEverReturned() {
            when(userSecurityRepository.findById(ORDINARY_USER_ID)).thenReturn(Optional.empty());

            final List<String> everyFocusEverReturned = List.of(
                    String.valueOf(subject.initialEntry().focusScreenFieldId()),
                    String.valueOf(subject.signOn(null, null).focusScreenFieldId()),
                    String.valueOf(subject.signOn(ORDINARY_USER_ID, null).focusScreenFieldId()),
                    String.valueOf(subject.signOn(ORDINARY_USER_ID, OTHER_SECRET_ENTRY)
                            .focusScreenFieldId()));

            assertAll(
                    () -> assertThat(AuthenticationService.FIELD_USER_ID).isEqualTo("USERID"),
                    () -> assertThat(AuthenticationService.FIELD_PASSWORD).isEqualTo("PASSWD"),
                    () -> assertThat(everyFocusEverReturned).containsOnly(
                            AuthenticationService.FIELD_USER_ID,
                            AuthenticationService.FIELD_PASSWORD));
        }

        @Test
        @DisplayName("every collaborator is required, so a partially wired service cannot be constructed "
                + "and no path can discover a missing dependency at request time")
        void everyCollaboratorIsRequired() {
            assertAll(
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .as("the credential master")
                            .isThrownBy(() -> new AuthenticationService(null, credentialDigestService,
                                    navigationService, messageCatalogService, FIXED_CLOCK,
                                    attemptGovernor)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .as("the digest verifier")
                            .isThrownBy(() -> new AuthenticationService(userSecurityRepository, null,
                                    navigationService, messageCatalogService, FIXED_CLOCK,
                                    attemptGovernor)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .as("the destination resolver")
                            .isThrownBy(() -> new AuthenticationService(userSecurityRepository,
                                    credentialDigestService, null, messageCatalogService, FIXED_CLOCK,
                                    attemptGovernor)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .as("the shared catalogue")
                            .isThrownBy(() -> new AuthenticationService(userSecurityRepository,
                                    credentialDigestService, navigationService, null, FIXED_CLOCK,
                                    attemptGovernor)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .as("the clock behind the header")
                            .isThrownBy(() -> new AuthenticationService(userSecurityRepository,
                                    credentialDigestService, navigationService, messageCatalogService,
                                    null, attemptGovernor)),
                    () -> assertThatExceptionOfType(NullPointerException.class)
                            .as("the abuse-resistance governor, without which the surface has no bound "
                                    + "on how many attempts an unauthenticated caller may spend")
                            .isThrownBy(() -> new AuthenticationService(userSecurityRepository,
                                    credentialDigestService, navigationService, messageCatalogService,
                                    FIXED_CLOCK, null)));
        }
    }
}
