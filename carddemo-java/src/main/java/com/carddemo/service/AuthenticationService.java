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
import com.carddemo.util.CobolStringUtils;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction {@code CC00}, the sign-on screen: the translation of {@code app/cbl/COSGN00C.cbl}.
 *
 * <p><strong>What this class is for.</strong> It is the module's first repository-backed path. Every
 * other service reaches its data through a collaborator or works on values handed to it; this one reads
 * the credential master directly, because the legacy program does exactly one file access and the whole
 * transaction is that access plus the decision it drives. It is therefore also the smallest honest place
 * to establish the entity-to-result conversion the rest of the online tier will follow.
 *
 * <p><strong>The six paragraphs and where they went.</strong> {@code MAIN-PARA} becomes
 * {@link #handle(KeyAction, String, String)}, whose attention-key {@code EVALUATE} is reproduced clause
 * for clause and in source order. {@code PROCESS-ENTER-KEY} becomes {@link #signOn(String, String)}.
 * {@code READ-USER-SEC-FILE} becomes {@link #verifyCredential(String, String)}. {@code SEND-SIGNON-SCREEN}
 * and {@code SEND-PLAIN-TEXT} have no counterpart here at all: they are terminal-write operations, and
 * what they wrote is the result this method returns. {@code POPULATE-HEADER-INFO} becomes
 * {@link #screenHeader()}.
 *
 * <p><strong>Why the result carries no message text.</strong> The five sign-on message literals are part
 * of the wire contract and are declared on the response record in {@code com.carddemo.api.dto}, which
 * this package may not depend on. The service therefore answers with a {@link Decision} and the boundary
 * resolves that decision to its frozen literal. That is not indirection for its own sake: it keeps one
 * mapping site for the text, and it means a decision cannot be produced here without the boundary having
 * a literal for it, because {@link Decision} is exhaustively switched there.
 *
 * <p><strong>Two parity points that a reasonable implementation would get wrong.</strong>
 * <ul>
 *   <li><strong>Both fields are folded to upper case, not just the identifier.</strong> Line 130 folds the
 *       identifier and line 134 folds the <em>password</em>, and the comparison on line 223 is against the
 *       folded value. A lower-case password therefore authenticates on the mainframe, and it has to
 *       authenticate here, so the presented secret is folded before verification. Folding uses
 *       {@link CobolStringUtils#asciiUpperFold(String)} rather than {@code String.toUpperCase()} for the
 *       reason recorded against every other fold in the estate: the intrinsic is locale-sensitive and
 *       would transform characters a fixed-width field cannot hold.</li>
 *   <li><strong>The failed comparison does not raise the error flag.</strong> Every other rejection moves
 *       {@code 'Y'} into {@code WS-ERR-FLG}; the wrong-password branch at lines 240 to 245 does not, and
 *       neither does the exit key at lines 88 to 90. The flag guards only whether the credential read is
 *       attempted, so omitting it changes nothing on that turn - but it is externally visible state, and
 *       the response contract documents the two {@code false} cases explicitly. They are reproduced.</li>
 * </ul>
 *
 * <p><strong>The credential comparison is a digest verification, not an equality test.</strong> The
 * legacy record holds an eight-character cleartext password and line 223 compares it directly. Storing a
 * cleartext credential is prohibited, so the seeded records hold digests and the comparison is delegated
 * to {@link CredentialDigestService}. This is a deliberate, documented parity exception: the observable
 * behaviour - which credentials are admitted, and what the operator is told when one is not - is
 * unchanged, and only the stored representation differs.
 *
 * <p>Provenance: {@code app/cbl/COSGN00C.cbl} and {@code app/cpy/CSUSR01Y.cpy}, read as read-only
 * reference at commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.
 *
 * @since 1.0.0
 */
@Service
public class AuthenticationService {

    /** Diagnostic channel, replacing the program's console writes. */
    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationService.class);

    /**
     * Symbolic name of the identifier field, from {@code USERIDI} of {@code app/cpy-bms/COSGN00.CPY}.
     *
     * <p>The cursor placements at lines 121, 250 and 255 all name this field. Only its identity travels;
     * the legacy sentinel that performed the placement does not.
     */
    public static final String FIELD_USER_ID = "USERID";

    /**
     * Symbolic name of the secret field, from {@code PASSWDI} of {@code app/cpy-bms/COSGN00.CPY}.
     *
     * <p>Named by the cursor placements at lines 126 and 244.
     */
    public static final String FIELD_PASSWORD = "PASSWD";

    /** Header date format, matching the two-digit year the screen renders. */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/uu", Locale.ROOT);

    /** Header time format, matching the nine-character field the sign-on screen alone declares. */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /** The credential master, the one file this transaction reads. */
    private final UserSecurityRepository userSecurityRepository;

    /** Verifies a presented secret against a stored digest. */
    private final CredentialDigestService credentialDigestService;

    /** Resolves the destination a completed sign-on leads to. */
    private final NavigationService navigationService;

    /** Supplies the two title lines the screen echoes. */
    private final MessageCatalogService messageCatalogService;

    /** Clock behind the header date and time, injected so a test can fix them. */
    private final Clock clock;

    /**
     * Creates the service over its collaborators.
     *
     * @param userSecurityRepository the credential master
     * @param credentialDigestService the digest verifier
     * @param navigationService the route resolver
     * @param messageCatalogService the shared screen-title catalog
     * @param clock the clock behind the header date and time
     */
    public AuthenticationService(final UserSecurityRepository userSecurityRepository,
                                 final CredentialDigestService credentialDigestService,
                                 final NavigationService navigationService,
                                 final MessageCatalogService messageCatalogService,
                                 final Clock clock) {
        this.userSecurityRepository = Objects.requireNonNull(userSecurityRepository,
                "userSecurityRepository must not be null");
        this.credentialDigestService = Objects.requireNonNull(credentialDigestService,
                "credentialDigestService must not be null");
        this.navigationService = Objects.requireNonNull(navigationService,
                "navigationService must not be null");
        this.messageCatalogService = Objects.requireNonNull(messageCatalogService,
                "messageCatalogService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Handles one turn of transaction {@code CC00}, dispatching on the attention key.
     *
     * <p>Reproduces the {@code EVALUATE EIBAID} of {@code MAIN-PARA} clause for clause and in source
     * order: the enter key drives the sign-on attempt, the third function key signs off, and every other
     * key - including an absent one, which is the state a client that names no key arrives in - is the
     * unmapped-key case. COBOL evaluates top to bottom and stops at the first match, so the order here is
     * the contract and not a preference.
     *
     * @param keyAction the attention key the client pressed, or {@code null} for none
     * @param presentedUserId the identifier the operator typed, possibly absent
     * @param presentedPassword the secret the operator typed, possibly absent
     * @return the screen the turn produces
     */
    @Transactional(readOnly = true)
    public SignOnScreen handle(final KeyAction keyAction,
                              final String presentedUserId,
                              final String presentedPassword) {
        if (keyAction == KeyAction.ENTER) {
            return signOn(presentedUserId, presentedPassword);
        }
        if (keyAction == KeyAction.PFK03) {
            return signOff();
        }
        return unmappedKey();
    }

    /**
     * Drives one sign-on attempt: the translation of {@code PROCESS-ENTER-KEY}.
     *
     * <p>The two presence tests are an {@code EVALUATE TRUE} whose clauses are evaluated in order, so the
     * identifier is reported before the secret and never both at once. The blank test is the legacy
     * {@code = SPACES OR LOW-VALUES}: an absent value, an empty one and one made only of spaces are the
     * same state, because a fixed-width screen field that the operator left alone arrives as spaces.
     *
     * @param presentedUserId the identifier the operator typed, possibly absent
     * @param presentedPassword the secret the operator typed, possibly absent
     * @return the screen the attempt produces
     */
    @Transactional(readOnly = true)
    public SignOnScreen signOn(final String presentedUserId, final String presentedPassword) {
        if (isBlank(presentedUserId)) {
            LOG.debug("Sign-on rejected: rule=identifier-required");
            return rejection(Decision.USER_ID_MISSING, true, FIELD_USER_ID);
        }
        if (isBlank(presentedPassword)) {
            LOG.debug("Sign-on rejected: rule=secret-required");
            return rejection(Decision.PASSWORD_MISSING, true, FIELD_PASSWORD);
        }
        // Lines 130 and 134: both fields are folded, and the comparison is against the folded secret.
        return verifyCredential(CobolStringUtils.asciiUpperFold(presentedUserId),
                CobolStringUtils.asciiUpperFold(presentedPassword));
    }

    /**
     * Reads the credential master and decides the turn: the translation of {@code READ-USER-SEC-FILE}.
     *
     * <p>The legacy {@code EVALUATE WS-RESP-CD} has three arms. Response zero is a record that was found,
     * and the secret is then compared. Response thirteen is the not-found condition, which the repository
     * expresses as an empty result. Every other response is the catch-all, which reaches this translation
     * as a stored user type that cannot be classified - the one remaining way for a record to be present
     * and yet unusable, now that the transport-level failures the legacy arm also covered are raised as
     * exceptions rather than returned as codes.
     *
     * @param userId the folded identifier
     * @param password the folded secret
     * @return the screen the read produces
     */
    private SignOnScreen verifyCredential(final String userId, final String password) {
        final Optional<UserSecurity> stored = userSecurityRepository.findById(userId);
        if (stored.isEmpty()) {
            LOG.info("Sign-on rejected: rule=identifier-not-on-file userId={}", userId);
            return rejection(Decision.USER_NOT_FOUND, true, FIELD_USER_ID);
        }

        final UserSecurity record = stored.orElseThrow();
        final Optional<UserType> userType = UserType.fromCode(record.getSecUsrType());
        if (userType.isEmpty()) {
            // A stored type outside the declared two. The legacy catch-all arm answers this way, and
            // admitting the operator with an unresolvable role would be the one genuinely unsafe outcome.
            LOG.warn("Sign-on rejected: rule=stored-user-type-unclassifiable userId={}", userId);
            return rejection(Decision.UNABLE_TO_VERIFY, true, FIELD_USER_ID);
        }

        if (!credentialDigestService.matches(password, record.credentialDigest())) {
            // Line 240: this branch composes a message and leaves the error flag lowered.
            LOG.info("Sign-on rejected: rule=secret-does-not-match userId={}", userId);
            return rejection(Decision.WRONG_PASSWORD, false, FIELD_PASSWORD);
        }

        final NavigationService.Route route = navigationService.resolveSignOnRoute(userType.orElseThrow());
        final ScreenHeader header = screenHeader();
        LOG.info("Sign-on admitted: userId={} userType={} route={}", userId,
                userType.orElseThrow().getCode(), route.getRouteValue());
        return new SignOnScreen(Decision.ADMITTED, userId, userType.orElseThrow(), route, false, null,
                messageCatalogService.screenTitle01(), messageCatalogService.screenTitle02(),
                header.currentDate(), header.currentTime());
    }

    /**
     * Produces the turn the third function key drives: the translation of the exit-key clause.
     *
     * <p>The legacy clause composes the shared farewell and performs a plain-text write rather than a
     * screen send, so no destination is nominated and no field is singled out. The error flag stays
     * lowered, which the response contract records explicitly.
     *
     * @return the sign-off screen
     */
    private SignOnScreen signOff() {
        LOG.debug("Sign-on turn ended: rule=exit-key");
        return terminalTurn(Decision.SIGNED_OFF, false);
    }

    /**
     * Produces the turn any other attention key drives: the translation of the {@code WHEN OTHER} clause.
     *
     * <p>Unlike the exit key this clause does raise the error flag, and it redisplays the screen rather
     * than writing plain text - so the header travels, which is why both cases are built from the same
     * helper but differ in the flag.
     *
     * @return the unmapped-key screen
     */
    private SignOnScreen unmappedKey() {
        LOG.debug("Sign-on turn rejected: rule=key-not-mapped");
        return terminalTurn(Decision.KEY_NOT_MAPPED, true);
    }

    /**
     * Assembles a turn that names no operator and nominates no destination.
     *
     * @param decision the decision reached
     * @param errorFlag whether the program raised its error flag
     * @return the screen
     */
    private SignOnScreen terminalTurn(final Decision decision, final boolean errorFlag) {
        final ScreenHeader header = screenHeader();
        return new SignOnScreen(decision, null, null, null, errorFlag, null,
                messageCatalogService.screenTitle01(), messageCatalogService.screenTitle02(),
                header.currentDate(), header.currentTime());
    }

    /**
     * Assembles a rejected turn, which redisplays the screen with the cursor on one field.
     *
     * <p>No identifier is carried back even when one was supplied and found. The legacy program leaves the
     * operator's entry in the map for redisplay, but carrying it in the result would place a supplied
     * identifier in a response the boundary may render before the operator is authenticated, and nothing
     * in the contract needs it: the client still holds what it sent.
     *
     * @param decision the decision reached
     * @param errorFlag whether the program raised its error flag
     * @param focusScreenFieldId the field the cursor returns to
     * @return the screen
     */
    private SignOnScreen rejection(final Decision decision,
                                   final boolean errorFlag,
                                   final String focusScreenFieldId) {
        final ScreenHeader header = screenHeader();
        return new SignOnScreen(decision, null, null, null, errorFlag, focusScreenFieldId,
                messageCatalogService.screenTitle01(), messageCatalogService.screenTitle02(),
                header.currentDate(), header.currentTime());
    }

    /**
     * Reads the clock once for a turn: the translation of {@code POPULATE-HEADER-INFO}.
     *
     * <p>Read once rather than per field so the date and the time cannot straddle midnight within one
     * response, which the legacy program's single {@code ASKTIME} likewise prevented.
     *
     * @return the rendered header date and time
     */
    private ScreenHeader screenHeader() {
        final LocalDateTime taken = LocalDateTime.now(clock);
        return new ScreenHeader(HEADER_DATE_FORMAT.format(taken), HEADER_TIME_FORMAT.format(taken));
    }

    /**
     * Reproduces the legacy {@code = SPACES OR LOW-VALUES} test.
     *
     * @param value the transmitted field value
     * @return {@code true} when the operator supplied nothing
     */
    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }

    /** The rendered header date and time of one turn. */
    private record ScreenHeader(String currentDate, String currentTime) {
    }

    /**
     * The outcome of one sign-on turn, in the terms the program itself reaches.
     *
     * <p>Exhaustive by construction: the boundary switches over these constants without a default arm, so
     * a new decision cannot be added here without the wire text for it being added there.
     */
    public enum Decision {

        /** The credential verified. A destination and a user type are carried. */
        ADMITTED,

        /** No identifier was supplied. */
        USER_ID_MISSING,

        /** An identifier was supplied but no secret was. */
        PASSWORD_MISSING,

        /** The identifier is not on file - the legacy not-found response. */
        USER_NOT_FOUND,

        /** The record was found and the secret did not verify. The error flag stays lowered. */
        WRONG_PASSWORD,

        /** The record was found and could not be used - the legacy catch-all response. */
        UNABLE_TO_VERIFY,

        /** The operator pressed the exit key. The error flag stays lowered. */
        SIGNED_OFF,

        /** The operator pressed a key this screen does not map. */
        KEY_NOT_MAPPED;

        /**
         * Whether this decision admits the operator.
         *
         * @return {@code true} only for {@link #ADMITTED}
         */
        public boolean isAdmitted() {
            return this == ADMITTED;
        }
    }

    /**
     * One turn of the sign-on screen, owned by this package rather than by the transport.
     *
     * @param decision the decision the program reached
     * @param userId the folded identifier, present only when the operator was admitted
     * @param userType the resolved role, present only when the operator was admitted
     * @param route the destination, present only when the operator was admitted
     * @param errorFlag whether the program raised its error flag
     * @param focusScreenFieldId the field the cursor returns to, absent when none is singled out
     * @param title01 the first title line the screen echoes
     * @param title02 the second title line the screen echoes
     * @param currentDate the header date as the screen renders it
     * @param currentTime the header time as the screen renders it
     */
    public record SignOnScreen(Decision decision,
                               String userId,
                               UserType userType,
                               NavigationService.Route route,
                               boolean errorFlag,
                               String focusScreenFieldId,
                               String title01,
                               String title02,
                               String currentDate,
                               String currentTime) {

        /**
         * Rejects an internally inconsistent turn.
         *
         * <p>Only an admitted turn may name an operator, a role or a destination, and an admitted turn
         * must name all three. Asserting it here rather than trusting each producer means the boundary can
         * rely on the pairing when it decides whether to issue a session.
         */
        public SignOnScreen {
            Objects.requireNonNull(decision, "decision must not be null");
            if (decision.isAdmitted()) {
                Objects.requireNonNull(userId, "an admitted turn must name the operator");
                Objects.requireNonNull(userType, "an admitted turn must name the resolved role");
                Objects.requireNonNull(route, "an admitted turn must nominate a destination");
            } else if (userId != null || userType != null || route != null) {
                throw new IllegalArgumentException(
                        "a turn that did not admit the operator must name no operator, no role and no "
                                + "destination, because the boundary decides whether to issue a session "
                                + "from those components being present");
            }
        }
    }
}
