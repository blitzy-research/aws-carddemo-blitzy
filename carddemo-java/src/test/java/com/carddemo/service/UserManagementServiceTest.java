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

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.carddemo.domain.UserSecurity;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.UserType;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.RecordWriter;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.support.TestDataFactory;
import com.carddemo.util.CobolStringUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.support.SensitiveValues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the four administrative user transactions.
 *
 * <p>The class under test is the migrated form of {@code app/cbl/COUSR00C.cbl} (transaction
 * {@code CU00}, 695 lines, 16 paragraphs), {@code app/cbl/COUSR01C.cbl} ({@code CU01}, 299 lines,
 * 9 paragraphs), {@code app/cbl/COUSR02C.cbl} ({@code CU02}, 414 lines, 11 paragraphs) and
 * {@code app/cbl/COUSR03C.cbl} ({@code CU03}, 359 lines, 11 paragraphs), all four operating on the
 * eighty-byte record of {@code app/cpy/CSUSR01Y.cpy}.</p>
 *
 * <p><strong>The oracle is independent of the code it judges.</strong> Every expected message,
 * route token, screen field identifier, page size and rendered header item below is a literal
 * declared in this class. No expected value is obtained by calling the service, the response
 * contract's published message constants, the message catalogue or the navigation service, so a
 * message that drifted in the production constant would fail here rather than agree with itself. The
 * two exceptions are stated where they occur and are both cases where the contract under test
 * <em>is</em> the catalogue's own width.</p>
 *
 * <p><strong>No credential literal from the legacy estate appears anywhere in this class.</strong>
 * Every test builds its own throwaway credential at run time from a random identifier, so nothing
 * here can be a stored secret and no assertion message can disclose one. The digest assertions are
 * the point of several tests and are always made against a value the test itself produced.</p>
 *
 * <p>Six behaviours carry parity traps and are asserted deliberately rather than incidentally.</p>
 *
 * <ol>
 *   <li><em>A credential is hashed on write and never read back.</em> A created identity must hold a
 *       sixty-character digest that is not the submitted value.</li>
 *   <li><em>An unchanged credential is carried forward, never re-hashed.</em> Updating without
 *       supplying one must leave the stored digest byte-identical. This is the assertion that catches
 *       a digest-of-a-digest, which would lock the identity out permanently and which no other
 *       assertion detects.</li>
 *   <li><em>The list page holds exactly ten rows, and a backward page presents ascending.</em> The
 *       legacy fills its slots from the bottom upward on a backward walk, so a translation that
 *       returned the read order would present the page inverted.</li>
 *   <li><em>The type field is accepted, not policed.</em> A stored code outside the declared pair
 *       must load and round-trip rather than raise.</li>
 *   <li><em>No alphabetic edit exists on these screens.</em> A given name with an embedded space must
 *       be accepted, because inventing an edit would reject input the legacy stores.</li>
 *   <li><em>The unmapped-key message is fixed width and never trimmed.</em> Fifty encoded bytes, on
 *       every one of the four screens.</li>
 * </ol>
 *
 * <p><strong>Two harnesses, deliberately.</strong> Most behaviour is asserted against an in-memory
 * stand-in for the repository, because a page walk is a sequence of positioned reads whose answers must
 * be mutually consistent and a hand-written store is the honest way to supply that. A second set of
 * groups drives the same service through Mockito doubles instead, because three contracts can only be
 * proven by <em>observing the collaborator</em> rather than its answers: that the paged read asks for
 * the window it says it asks for, that a credential-free save never reaches the encoder at all, and
 * that no operation reaches the store on a path the legacy does not read on. Those groups are the ones
 * that capture arguments and bound interactions.</p>
 *
 * <p><strong>Paragraph coverage.</strong> All 47 paragraph units of the four members are exercised
 * here. {@code app/cbl/COUSR00C.cbl} contributes 16: {@code MAIN-PARA}, {@code PROCESS-ENTER-KEY},
 * {@code PROCESS-PF7-KEY}, {@code PROCESS-PF8-KEY}, {@code PROCESS-PAGE-FORWARD},
 * {@code PROCESS-PAGE-BACKWARD}, {@code POPULATE-USER-DATA}, {@code INITIALIZE-USER-DATA},
 * {@code RETURN-TO-PREV-SCREEN}, {@code SEND-USRLST-SCREEN}, {@code RECEIVE-USRLST-SCREEN},
 * {@code POPULATE-HEADER-INFO}, {@code STARTBR-USER-SEC-FILE}, {@code READNEXT-USER-SEC-FILE},
 * {@code READPREV-USER-SEC-FILE} and {@code ENDBR-USER-SEC-FILE}. {@code app/cbl/COUSR01C.cbl}
 * contributes 9: {@code MAIN-PARA}, {@code PROCESS-ENTER-KEY}, {@code RETURN-TO-PREV-SCREEN},
 * {@code SEND-USRADD-SCREEN}, {@code RECEIVE-USRADD-SCREEN}, {@code POPULATE-HEADER-INFO},
 * {@code WRITE-USER-SEC-FILE}, {@code CLEAR-CURRENT-SCREEN} and {@code INITIALIZE-ALL-FIELDS}.
 * {@code app/cbl/COUSR02C.cbl} contributes 11: those nine with {@code UPDATE-USER-INFO},
 * {@code READ-USER-SEC-FILE} and {@code UPDATE-USER-SEC-FILE} in place of the add screen's write, and
 * its own send and receive. {@code app/cbl/COUSR03C.cbl} contributes 11 in the same shape with
 * {@code DELETE-USER-INFO} and {@code DELETE-USER-SEC-FILE}. Each nested group names the member it
 * derives from, so a row of the traceability matrix resolves to a group and a method.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("User management service: the four administrative user transactions")
class UserManagementServiceTest {

    // ---------------------------------------------------------------- independent literal oracle

    private static final String MSG_LIST_INVALID_SELECTION =
            "Invalid selection. Valid values are U and D";

    private static final String MSG_LIST_ALREADY_AT_TOP = "You are already at the top of the page...";

    private static final String MSG_LIST_ALREADY_AT_BOTTOM =
            "You are already at the bottom of the page...";

    private static final String MSG_LIST_AT_TOP = "You are at the top of the page...";

    private static final String MSG_LIST_REACHED_BOTTOM = "You have reached the bottom of the page...";

    private static final String MSG_LIST_REACHED_TOP = "You have reached the top of the page...";

    private static final String MSG_LIST_UNABLE_TO_LOOKUP_USER = "Unable to lookup User...";

    private static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    private static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    private static final String MSG_CREDENTIAL_EMPTY = "Password can NOT be empty...";

    private static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    private static final String MSG_ADD_USER_ID_ALREADY_EXIST = "User ID already exist...";

    private static final String MSG_ADD_UNABLE_TO_ADD_USER = "Unable to Add User...";

    private static final String MSG_UPDATE_NO_CHANGE = "Please modify to update ...";

    private static final String MSG_UPDATE_PRESS_PF5 = "Press PF5 key to save your updates ...";

    private static final String MSG_USER_ID_NOT_FOUND = "User ID NOT found...";

    private static final String MSG_UPDATE_UNABLE_TO_UPDATE_USER = "Unable to Update User...";

    private static final String MSG_DELETE_PRESS_PF5 = "Press PF5 key to delete this user ...";

    private static final String ROUTE_SIGN_ON = "sign-on";

    private static final String ROUTE_ADMIN_MENU = "admin-menu";

    private static final String ROUTE_USER_UPDATE = "user-update";

    private static final String ROUTE_USER_DELETE = "user-delete";

    private static final String FIELD_LIST_USER_ID = "USRIDIN";

    private static final String FIELD_FIRST_NAME = "FNAME";

    private static final String FIELD_ADD_USER_ID = "USERID";

    private static final String FIELD_CREDENTIAL_ITEM = "PASSWD";

    /** Screen row count, from the ten-occurrence table at {@code app/cbl/COUSR00C.cbl} L56-L57. */
    private static final int SCREEN_ROWS = 10;

    /**
     * Rows one positioning read asks for: the screen's own ten, plus the single extra row the source
     * reads past the page at {@code app/cbl/COUSR00C.cbl} L308-L316 to answer whether a further page
     * follows.
     *
     * <p>Declared as {@link #SCREEN_ROWS} plus one rather than as eleven, so that the assertion on the
     * captured window states <em>why</em> it is eleven. It is not the page size: the page size is what
     * the response carries and is asserted separately as exactly ten.</p>
     */
    private static final int POSITIONING_WINDOW_ROWS = SCREEN_ROWS + 1;

    /** Width of the common message contract, which the unmapped-key text occupies in full. */
    private static final int COMMON_MESSAGE_WIDTH = 50;

    /** Digest length the credential column is sized for. */
    private static final int DIGEST_LENGTH = 60;

    /**
     * Visible portion of the shared unmapped-key text, declared here rather than read from the
     * catalogue, so a text that drifted in the catalogue would fail this class instead of agreeing with
     * it.
     */
    private static final String INVALID_KEY_TEXT_VISIBLE = "Invalid key pressed. Please see below...";

    /** Space-fill the fifty-character field carries once the forty visible characters are placed. */
    private static final int INVALID_KEY_TRAILING_SPACES =
            COMMON_MESSAGE_WIDTH - INVALID_KEY_TEXT_VISIBLE.length();

    /**
     * The whole field content of the unmapped-key message, with its padding written as an explicit
     * repeat count so that no trailing space can be lost to an editor or miscounted by a reader.
     */
    private static final String MSG_INVALID_KEY_PADDED =
            INVALID_KEY_TEXT_VISIBLE + " ".repeat(INVALID_KEY_TRAILING_SPACES);

    /** Property name the identifier findings carry, as the response contract publishes it. */
    private static final String PROPERTY_USER_ID = "userId";

    /** Property name the given-name findings carry. */
    private static final String PROPERTY_FIRST_NAME = "firstName";

    /** Route token of the list screen, declared here rather than read from the navigation service. */
    private static final String ROUTE_USER_LIST = "user-list";

    /**
     * Legacy program name of the one CICS program definition in {@code app/csd/CARDDEMO.CSD} that has
     * no source member: declared at L211 and bound at L390, with nothing to translate. No route may
     * name it.
     */
    private static final String DANGLING_CSD_PROGRAM = "COCRDSEC";

    /** Identifier of the first seeded administrative identity; non-secret reference data. */
    private static final String SEEDED_ADMIN_ID = "ADMIN001";

    /** Given name of the first seeded administrative identity; non-secret reference data. */
    private static final String SEEDED_ADMIN_FIRST_NAME = "MARGARET";

    /** Family name of the first seeded administrative identity; non-secret reference data. */
    private static final String SEEDED_ADMIN_LAST_NAME = "GOLD";

    /** Role code of the seeded administrative identities. */
    private static final String ROLE_CODE_ADMIN = "A";

    /** Role code of the seeded standard identities. */
    private static final String ROLE_CODE_USER = "U";

    /**
     * The ten identifiers a full first page presents, ascending, written out rather than generated so
     * the expected order is visible at the assertion.
     */
    private static final List<String> FIRST_PAGE_IDS = List.of(
            "USER0001", "USER0002", "USER0003", "USER0004", "USER0005",
            "USER0006", "USER0007", "USER0008", "USER0009", "USER0010");

    /**
     * The ten identifiers a backward page presents, ascending, written out separately from the
     * descending order they arrive in so that the reversal is stated rather than computed.
     */
    private static final List<String> BACKWARD_PAGE_IDS_ASCENDING = List.of(
            "USER0002", "USER0003", "USER0004", "USER0005", "USER0006",
            "USER0007", "USER0008", "USER0009", "USER0010", "USER0011");

    /**
     * The eleven identifiers the descending range read answers with below anchor {@code USER0012}, in
     * the order the read returns them. The first ten fill slots ten down to one and the eleventh is the
     * read past the page.
     */
    private static final List<String> BACKWARD_READ_IDS_DESCENDING = List.of(
            "USER0011", "USER0010", "USER0009", "USER0008", "USER0007", "USER0006",
            "USER0005", "USER0004", "USER0003", "USER0002", "USER0001");

    /** Anchor the backward page is walked from: the first identifier the operator's page displayed. */
    private static final String BACKWARD_ANCHOR_ID = "USER0012";

    /** The page indicator a first page displays, zero-filled to the eight positions it declares. */
    private static final String DISPLAYED_PAGE_ONE = "00000001";

    /** Property the browse orders on, which is the record's own key. */
    private static final String BROWSE_SORT_PROPERTY = "secUsrId";

    /** Lower-case half of the estate's twenty-six character fold table, written out here. */
    private static final String FOLD_TABLE_FROM = "abcdefghijklmnopqrstuvwxyz";

    /** Upper-case half of the same table, in the same positions. */
    private static final String FOLD_TABLE_TO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /**
     * Alphabet a throwaway credential is drawn from: upper-case letters and digits only, so the value
     * is its own fold and no production folding routine is needed to predict what the encoder receives.
     */
    private static final String THROWAWAY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** Length of a throwaway credential, matching the legacy field's eight character positions. */
    private static final int THROWAWAY_LENGTH = 8;

    /** Source of throwaway credential material. Never seeded, never recorded, never asserted upon. */
    private static final SecureRandom THROWAWAY_SOURCE = new SecureRandom();

    // ---------------------------------------------------------------- fixtures

    /**
     * An encoder used only as this class's own verifier, never as the service's collaborator.
     *
     * <p>Kept separate from the injected spy so that a verification made <em>by an assertion</em>
     * cannot be miscounted as an interaction the service performed. Configured at the module's own
     * cost factor, because a digest produced at any other cost would not be the shape the storage
     * column and the entity both require.
     */
    private static final PasswordEncoder ENCODER =
            new BCryptPasswordEncoder(TestDataFactory.BCRYPT_WORK_FACTOR);

    /**
     * The page-snapshot sealer, keyed with an obviously fake, human-readable test key.
     *
     * <p>The value is a plain sentence about what it is, so it cannot be mistaken for a credential and
     * cannot match any provider's key format. No deployed key, and no default a deployment could
     * inherit, appears anywhere in this class: the production profile resolves its key from the
     * environment with no fallback.
     */
    private static final UserListPageTokenService PAGE_TOKEN_SERVICE =
            new UserListPageTokenService(new SensitiveFieldEncryptionService(
                    Base64.getEncoder().encodeToString(
                            "carddemo-user-page-token-key-001"
                                    .getBytes(StandardCharsets.UTF_8))));

    /**
     * One digest reused by the bulk list fixtures. Produced from a throwaway value at class load, so
     * it is a real digest the entity accepts without paying for twenty-five separate hashes in a test
     * whose assertions never verify a credential.
     */
    private static final String FILLER_DIGEST = ENCODER.encode(UUID.randomUUID().toString());

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2024-03-07T14:25:36Z"), ZoneOffset.UTC);

    private MessageCatalogService messageCatalogService;

    private NavigationService navigationService;

    private ListAppender<ILoggingEvent> logCapture;

    /** The logger the capture is attached to, retained so the attachment can be undone. */
    private ch.qos.logback.classic.Logger capturedLogger;

    /** The level that logger carried before the capture lowered it, restored on the way out. */
    private Level capturedLoggerLevel;

    /**
     * The store, as a double whose calls can be counted. Declared on the enclosing class so every
     * nested group shares one instance per test; the extension supplies a fresh one for each.
     */
    @Mock
    private UserSecurityRepository mockRepository;

    /** The catalogue, as a double, so a fixed-width text can be handed in and traced through. */
    @Mock
    private MessageCatalogService mockCatalog;

    /** The navigation rules, as a double, so a route can be proven to come from them. */
    @Mock
    private NavigationService mockNavigation;

    /** The insert-and-flush collaborator, as a double, so the written record can be captured. */
    @Mock
    private RecordWriter mockRecordWriter;

    /**
     * The encoder, as a spy over a real one at the module's cost factor.
     *
     * <p>A spy and not a stub, because both halves matter. The real implementation runs, so a digest
     * this class inspects is a genuine sixty-character digest and {@code matches} answers genuinely -
     * a stub returning a fixed string would make every shape assertion vacuous. And the calls are
     * recorded, so the assertion that a credential-free save never reaches {@code encode} at all is
     * provable rather than inferred from the digest happening to be unchanged.</p>
     */
    private PasswordEncoder encoderSpy;

    @BeforeEach
    void setUp() {
        messageCatalogService = new MessageCatalogService();
        navigationService = new NavigationService();
        encoderSpy = Mockito.spy(new BCryptPasswordEncoder(TestDataFactory.BCRYPT_WORK_FACTOR));
        final LoggerContext context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        capturedLogger = context.getLogger(UserManagementService.class);
        capturedLoggerLevel = capturedLogger.getLevel();
        capturedLogger.setLevel(Level.TRACE);
        logCapture = new ListAppender<>();
        logCapture.setContext(context);
        logCapture.start();
        capturedLogger.addAppender(logCapture);
    }

    /**
     * Detaches the capture and restores the level.
     *
     * <p>The logger is a process-wide singleton, so an attachment left behind would accumulate one
     * appender per test and every later test would see every earlier test's events. The assertions that
     * no captured event carries credential material are only meaningful over the events of the test that
     * made them.
     */
    @AfterEach
    void tearDown() {
        capturedLogger.detachAppender(logCapture);
        logCapture.stop();
        capturedLogger.setLevel(capturedLoggerLevel);
    }

    /**
     * Builds the service over the doubles, with the two collaborators that must genuinely run kept
     * real.
     *
     * <p>The unit-of-work boundary is real because a double would not invoke the operation it is given,
     * and every maintenance assertion in this class is about what happens inside that unit. The page
     * token service is real because it mints and reopens the sealed page snapshot the selection paths
     * resolve against, and a double would have to reimplement it to be useful.
     *
     * @return the service under test, wired to the doubles declared on this class
     */
    private UserManagementService serviceWithMocks() {
        return new UserManagementService(mockRepository, mockCatalog, mockNavigation,
                PAGE_TOKEN_SERVICE, new OnlineTransactionBoundary(), mockRecordWriter, encoderSpy,
                FIXED_CLOCK);
    }

    private UserManagementService serviceFor(final FakeRepository repository) {
        return new UserManagementService(repository, messageCatalogService, navigationService,
                PAGE_TOKEN_SERVICE, new OnlineTransactionBoundary(), recordWriterFor(repository),
                ENCODER, FIXED_CLOCK);
    }

    private static RecordWriter recordWriterFor(final FakeRepository repository) {
        return Mockito.mock(RecordWriter.class, invocation -> {
            if (invocation.getMethod().getName().equals("insertIndependently")) {
                final UserSecurity record = invocation.getArgument(0);
                if (repository.findById(record.getSecUsrId()).isPresent()) {
                    throw new DuplicateKeyException("duplicate test identifier");
                }
                return repository.save(record);
            }
            return null;
        });
    }

    /** A fresh throwaway credential. No legacy value is ever referenced. */
    private static String throwawayCredential() {
        return "T" + UUID.randomUUID().toString().substring(0, 7);
    }

    /**
     * A fresh throwaway credential drawn only from characters that are their own upper-case fold.
     *
     * <p>Fold-stable on purpose. The write paths fold a submitted credential before hashing it, so a
     * test that must predict what the encoder received would otherwise have to fold the value itself -
     * and the only fold available would be the production one, which is the artefact under test. A value
     * that cannot change under the fold removes the question.
     *
     * @return eight upper-case characters, never the legacy value and never recorded anywhere
     */
    private static String foldStableThrowawayCredential() {
        final StringBuilder value = new StringBuilder(THROWAWAY_LENGTH);
        for (int position = 0; position < THROWAWAY_LENGTH; position++) {
            value.append(THROWAWAY_ALPHABET.charAt(
                    THROWAWAY_SOURCE.nextInt(THROWAWAY_ALPHABET.length())));
        }
        return value.toString();
    }

    /**
     * This class's own upper fold, written independently of the production one.
     *
     * <p>The estate's fold is a strict twenty-six character table substitution: a character present in
     * the lower-case table is replaced by the character in the same position of the upper-case table, and
     * every other character is emitted untouched. Reproduced here from the two tables so that an expected
     * value is never obtained from the production utility, which is itself covered elsewhere. Not
     * {@code String.toUpperCase}, in either form: both are locale-sensitive or Unicode-aware and would
     * transform characters this table leaves alone.
     *
     * @param value the value to fold
     * @return the value with every lower-case ASCII letter replaced by its upper-case counterpart
     */
    private static String foldedByThisClass(final String value) {
        final char[] folded = value.toCharArray();
        for (int index = 0; index < folded.length; index++) {
            final int position = FOLD_TABLE_FROM.indexOf(folded[index]);
            if (position >= 0) {
                folded[index] = FOLD_TABLE_TO.charAt(position);
            }
        }
        return new String(folded);
    }

    /**
     * A stored digest in the form both write paths produce and the sign-on path verifies: taken over
     * the credential folded to upper case, exactly as the terminal delivered it to the mainframe.
     *
     * @param credential the credential as an operator would key it
     * @return the digest a stored row carries for that credential
     */
    private static String storedDigestOf(final String credential) {
        return ENCODER.encode(foldedByThisClass(credential));
    }

    /**
     * A projected row for one ordinal position of the synthetic key sequence.
     *
     * @param ordinal the one-based ordinal, which becomes the eight-character identifier
     * @return the four screen columns of that row
     */
    private static UserSecurityRepository.AdminEntry projectionFor(final int ordinal) {
        // Locale.ROOT is mandatory: the identifier is a fixed-width US-ASCII field, and an unqualified
        // format emits the default locale's digits, which under a non-Latin numbering system are not
        // ASCII digits at all.
        return new ProjectedEntry(String.format(Locale.ROOT, "USER%04d", ordinal),
                "GIVEN" + ordinal, "FAMILY" + ordinal,
                ordinal % 2 == 0 ? ROLE_CODE_ADMIN : ROLE_CODE_USER);
    }

    /**
     * Wraps projections as the window shape the contract returns.
     *
     * <p>A {@link SliceImpl} rather than a paged implementation, because the contract carries no total and
     * a double that carried one could satisfy a specification the real store cannot (DL-296).
     *
     * @param  rows the projected rows the window holds
     * @return the window
     */
    private static Slice<UserSecurityRepository.AdminEntry> sliceOf(
            final List<UserSecurityRepository.AdminEntry> rows) {
        return new SliceImpl<>(rows);
    }

    /**
     * Projected rows for a run of ordinals, in the order the identifiers are listed.
     *
     * @param identifiers the identifiers to project, in read order
     * @return one projected row per identifier, in the same order
     */
    private static List<UserSecurityRepository.AdminEntry> projectionsOf(
            final List<String> identifiers) {
        final List<UserSecurityRepository.AdminEntry> rows = new ArrayList<>(identifiers.size());
        for (final String identifier : identifiers) {
            rows.add(new ProjectedEntry(identifier, "GIVEN" + identifier, "FAMILY" + identifier,
                    ROLE_CODE_USER));
        }
        return List.copyOf(rows);
    }

    /**
     * An identity carrying a stored digest, built through the shared factory so no credential literal
     * is needed.
     *
     * @param userId   the eight-character identifier
     * @param digest   the stored digest the row carries
     * @param roleCode the one-character role code, which may be one the estate never declared
     * @return the identity, ready to be answered from a stubbed read
     */
    private static UserSecurity identityHolding(final String userId, final String digest,
                                                final String roleCode) {
        return TestDataFactory.userSecurity()
                .userId(userId)
                .firstName(SEEDED_ADMIN_FIRST_NAME)
                .lastName(SEEDED_ADMIN_LAST_NAME)
                .userTypeCode(roleCode)
                .storedDigest(digest)
                .build();
    }

    /**
     * An identity whose stored digest hashes nothing at all.
     *
     * <p>For the many tests whose subject is not the credential. The shared factory's structurally valid
     * placeholder satisfies the entity's own refusal to hold anything but a digest, and costs no hash -
     * which matters, because the module's cost factor is a security parameter and a test that hashed
     * needlessly would pay it needlessly. A test whose subject <em>is</em> the credential uses
     * {@link #identityHolding(String, String, String)} with a real digest instead.
     *
     * @param userId   the eight-character identifier
     * @param roleCode the one-character role code, which may be one the estate never declared
     * @return the identity, ready to be answered from a stubbed read
     */
    private static UserSecurity identityFor(final String userId, final String roleCode) {
        return identityHolding(userId, TestDataFactory.SYNTHETIC_BCRYPT_DIGEST, roleCode);
    }

    private static ScreenNavigationState reEntered() {
        return ScreenNavigationState.empty().withReEntry();
    }

    private static ScreenNavigationState firstEntry() {
        return ScreenNavigationState.empty().withFirstEntry();
    }

    private static UserCommand recordRequest(final String userId,
                                             final String firstName,
                                             final String lastName,
                                             final String credential,
                                             final String userType,
                                             final KeyAction keyAction,
                                             final ScreenNavigationState context) {
        return new UserCommand(userId, null, firstName, lastName, credential, userType, List.of(),
                null, null, null, keyAction, context);
    }

    private static UserCommand recordRequest(final String userId,
                                             final String firstName,
                                             final String lastName,
                                             final String credential,
                                             final String userType,
                                             final KeyAction keyAction) {
        return recordRequest(userId, firstName, lastName, credential, userType, keyAction, reEntered());
    }

    private static UserCommand listRequest(final KeyAction keyAction,
                                           final String searchUserId,
                                           final String firstOnPage,
                                           final String lastOnPage,
                                           final List<String> rowSelections) {
        return listRequest(keyAction, searchUserId, firstOnPage, lastOnPage, rowSelections, null);
    }

    private static UserCommand listRequest(final KeyAction keyAction,
                                           final String searchUserId,
                                           final String firstOnPage,
                                           final String lastOnPage,
                                           final List<String> rowSelections,
                                           final String rowSnapshotToken) {
        return new UserCommand(null, searchUserId, null, null, null, null, rowSelections, null,
                firstOnPage, lastOnPage, rowSnapshotToken, keyAction, reEntered());
    }

    private UserOutcome submitSelection(final FakeRepository repository,
                                        final String searchUserId,
                                        final List<String> selections) {
        final UserManagementService service = serviceFor(repository);
        final UserOutcome displayed = service.listUsers(
                listRequest(KeyAction.ENTER, searchUserId, null, null, List.of()));
        final BrowseWindow page = displayed.pageMetadata();
        return service.listUsers(listRequest(KeyAction.ENTER, null,
                page.previousCursorKey(), page.nextCursorKey(), selections,
                displayed.rowSnapshotToken()));
    }

    private static UserSecurity seed(final FakeRepository repository,
                                     final String userId,
                                     final String firstName,
                                     final String lastName,
                                     final String userType,
                                     final String digest) {
        final UserSecurity identity =
                new UserSecurity(userId, firstName, lastName, digest, userType);
        repository.rows.put(userId, identity);
        return identity;
    }

    private static FakeRepository repositoryOf(final int rowCount) {
        final FakeRepository repository = new FakeRepository();
        for (int index = 1; index <= rowCount; index++) {
            // Locale.ROOT is mandatory, not decorative. The identifier is a fixed-width field whose
            // eight bytes must stay inside US-ASCII, and an unqualified format emits the default
            // locale's digits - under a locale whose numbering system is not Latin those are not ASCII
            // digits at all, and every identifier assertion in this class would compare against a
            // value it could never equal.
            seed(repository, String.format(Locale.ROOT, "USER%04d", index), "GIVEN" + index,
                    "FAMILY" + index, index % 2 == 0 ? "A" : "U", FILLER_DIGEST);
        }
        return repository;
    }

    private boolean noCapturedLogContains(final String forbidden) {
        for (final ILoggingEvent event : logCapture.list) {
            if (event.getFormattedMessage().contains(forbidden)) {
                return false;
            }
            final Object[] arguments = event.getArgumentArray();
            if (arguments != null) {
                for (final Object argument : arguments) {
                    if (argument != null && String.valueOf(argument).contains(forbidden)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void assertBoundedFailureDiagnostics(
            final int expectedCount, final String... operationFragments) {
        final List<ILoggingEvent> failures = logCapture.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("User "))
                .filter(event -> event.getFormattedMessage().contains(" failed:"))
                .toList();
        assertThat(failures).hasSize(expectedCount).allSatisfy(event -> {
            assertThat(event.getFormattedMessage())
                    .contains("failureChain=IllegalStateException")
                    .doesNotContain("store unavailable", "row locked");
            assertThat(event.getThrowableProxy()).isNull();
        });
        for (final String operationFragment : operationFragments) {
            assertThat(failures)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message).contains(operationFragment));
        }
    }

    private static List<String> userIdsOf(final UserOutcome response) {
        return response.rows().stream().map(UserOutcome.UserRow::userId).toList();
    }

    /** Positional selection markers: ten blank positions with one marked. */
    private static List<String> selectionAt(final int oneBasedPosition, final String marker) {
        final List<String> selections = new ArrayList<>(Collections.nCopies(SCREEN_ROWS, " "));
        selections.set(oneBasedPosition - 1, marker);
        return selections;
    }

    /**
     * An in-memory stand-in for the repository.
     *
     * <p>The repository is a <strong>closed</strong> interface: it extends the marker
     * {@code Repository} rather than {@code JpaRepository}, so the nine operations below are the
     * whole surface and there is no inherited operation to refuse. That is a stronger arrangement
     * than a refusal list on this fake, because an unbounded {@code findAll()} or a bulk
     * delete is a compilation failure at the call site rather than a test failure at run time.
     *
     * <p>Reads answer from a sorted map, so iteration order already is the ascending key order the
     * browse positions against, and each read applies exactly the bound and the limit the derived
     * name declares - strictly greater or strictly less, ordered, and truncated to the limit. Getting
     * any of those wrong here would let the service pass a test it should fail, so they are
     * reproduced literally rather than approximated.
     *
     * <p>Reads may be made to refuse by naming the operation, which is what makes each of the
     * service's browse failure arms separately reachable: positioning, the row read, the anchor
     * probe, the page-counter probe and the successor probe all fail through different operations.
     */
    private static final class FakeRepository implements UserSecurityRepository {

        private final TreeMap<String, UserSecurity> rows = new TreeMap<>();

        /** Operations that raise instead of answering, named exactly as the interface declares them. */
        private final Set<String> refusedOperations = new HashSet<>();

        private boolean failOnSave;

        private boolean failOnFind;

        private boolean failOnDelete;

        /** How many times the maintenance paths read the record through its held form. */
        private int heldReads;

        /**
         * Makes one named read operation refuse.
         *
         * @param operation the interface method name that should raise
         */
        private void refuse(final String operation) {
            refusedOperations.add(operation);
        }

        /**
         * Raises when the named operation has been marked as refusing.
         *
         * @param operation the interface method name being invoked
         */
        private void guard(final String operation) {
            if (refusedOperations.contains(operation)) {
                throw new IllegalStateException("store unavailable");
            }
        }

        @Override
        public Optional<UserSecurity> findById(final String secUsrId) {
            if (failOnFind) {
                throw new IllegalStateException("store unavailable");
            }
            return Optional.ofNullable(rows.get(secUsrId));
        }

        /**
         * The held form of the keyed read, which the two maintenance paths use.
         *
         * <p>Answers exactly as the unheld read does, and fails on exactly the same flag, because a
         * unit test cannot observe a row lock and the arms under test do not depend on one. What it
         * <em>does</em> let the tests observe is that the maintenance paths read through this method and
         * the display paths do not, which is the whole substance of the change.
         */
        @Override
        public Optional<UserSecurity> findByIdForUpdate(final String secUsrId) {
            heldReads++;
            if (failOnFind) {
                throw new IllegalStateException("store unavailable");
            }
            return Optional.ofNullable(rows.get(secUsrId));
        }

        /**
         * The opening window of the browse, projected so no credential column is selected.
         *
         * <p>Only window zero is ever asked for, and the sort the service supplies is ascending on the
         * identifier, which the map already provides. The window is sliced rather than filtered so a
         * size larger than the table yields a short window exactly as the store would.
         *
         * <p>Returns a {@link Slice} because the contract does: nothing reads a total, so the store is
         * never asked to produce one (DL-296). The fake carries no total either, which is what keeps it
         * incapable of satisfying a specification the real store could not.
         */
        @Override
        public Slice<AdminEntry> findAllProjectedBy(final Pageable pageable) {
            guard("findAllProjectedBy");
            final List<AdminEntry> all = rows.values().stream().map(FakeRepository::project).toList();
            final int from = (int) Math.min(pageable.getOffset(), all.size());
            final int to = Math.min(from + pageable.getPageSize(), all.size());
            return new SliceImpl<>(List.copyOf(all.subList(from, to)), pageable, to < all.size());
        }

        @Override
        public Optional<AdminEntry> findProjectedBySecUsrId(final String secUsrId) {
            guard("findProjectedBySecUsrId");
            return Optional.ofNullable(rows.get(secUsrId)).map(FakeRepository::project);
        }

        @Override
        public List<AdminEntry> findBySecUsrIdGreaterThanOrderBySecUsrIdAsc(final String secUsrId,
                final Limit limit) {
            guard("findBySecUsrIdGreaterThanOrderBySecUsrIdAsc");
            return rows.tailMap(secUsrId, false).values().stream()
                    .map(FakeRepository::project)
                    .limit(limit.max())
                    .toList();
        }

        @Override
        public List<AdminEntry> findBySecUsrIdLessThanOrderBySecUsrIdDesc(final String secUsrId,
                final Limit limit) {
            guard("findBySecUsrIdLessThanOrderBySecUsrIdDesc");
            return rows.headMap(secUsrId, false).descendingMap().values().stream()
                    .map(FakeRepository::project)
                    .limit(limit.max())
                    .toList();
        }

        @Override
        public long countBySecUsrIdLessThan(final String secUsrId) {
            guard("countBySecUsrIdLessThan");
            return rows.headMap(secUsrId, false).size();
        }

        @Override
        public UserSecurity save(final UserSecurity identity) {
            if (failOnSave) {
                throw new IllegalStateException("store unavailable");
            }
            rows.put(identity.getSecUsrId(), identity);
            return identity;
        }

        @Override
        public void deleteById(final String secUsrId) {
            if (failOnDelete) {
                throw new IllegalStateException("row locked");
            }
            rows.remove(secUsrId);
        }

        /**
         * Projects one stored entity onto the credential-free list projection.
         *
         * <p>The projection is built field by field from the four screen columns, so the digest is
         * unreachable from the returned object exactly as it is unreachable from the generated select.
         *
         * @param identity the stored entity
         * @return the projected row, never {@code null}
         */
        private static AdminEntry project(final UserSecurity identity) {
            return new ProjectedEntry(identity.getSecUsrId(), identity.getSecUsrFname(),
                    identity.getSecUsrLname(), identity.getSecUsrType());
        }
    }

    /**
     * The four screen columns of one sign-on identity, and deliberately nothing else.
     *
     * @param secUsrId    the eight-character identifier
     * @param secUsrFname the given name
     * @param secUsrLname the family name
     * @param secUsrType  the one-character type
     */
    private record ProjectedEntry(String secUsrId, String secUsrFname, String secUsrLname,
            String secUsrType) implements UserSecurityRepository.AdminEntry {

        @Override
        public String getSecUsrId() {
            return secUsrId;
        }

        @Override
        public String getSecUsrFname() {
            return secUsrFname;
        }

        @Override
        public String getSecUsrLname() {
            return secUsrLname;
        }

        @Override
        public String getSecUsrType() {
            return secUsrType;
        }
    }

    // ==============================================================================================

    @Nested
    @DisplayName("CU01 add: a credential is hashed on write and never read back")
    class AddCredentialHandling {

        @Test
        @DisplayName("the stored credential is a sixty-character digest that is not the submitted value")
        void storesADigestRatherThanTheSubmittedValue() {
            final FakeRepository repository = new FakeRepository();
            final String submitted = throwawayCredential();

            final UserOutcome response = serviceFor(repository)
                    .addUser(recordRequest("NEWUSR01", "MARY", "JONES", submitted, "U",
                            KeyAction.ENTER));

            final UserSecurity stored = repository.rows.get("NEWUSR01");
            assertThat(stored).isNotNull();
            assertThat(stored.credentialDigest().length()).isEqualTo(DIGEST_LENGTH);
            assertThat(SensitiveValues.fingerprint(stored.credentialDigest())).isNotEqualTo(SensitiveValues.fingerprint(submitted));
            assertThat(stored.credentialDigest().startsWith("$2"))
                    .as("a recognised digest version marker, not the submitted value")
                    .isTrue();
            assertThat(ENCODER.matches(foldedByThisClass(submitted),
                    stored.credentialDigest()))
                    .as("the sign-on path verifies the folded credential, so that is what is stored")
                    .isTrue();
            assertThat(response.actionSucceeded()).isTrue();
            assertThat(response.generalError()).isFalse();
            assertThat(response.message()).isEqualTo("User NEWUSR01 has been added ...");
        }

        @Test
        @DisplayName("a credential keyed in lower case is admitted by the sign-on verifier afterwards, "
                + "which is the only thing that makes a created identity usable")
        void aMixedCaseCredentialIsUsableAtSignOn() {
            // The defect this pins: the terminal folded every keystroke before the mainframe program
            // saw it, and the sign-on program folds the submitted secret and compares the folded value.
            // A digest taken over the value as typed is therefore unverifiable, and the screen still
            // reports success - so the identity is silently locked out. The verifier used here is the
            // module's own, driven exactly as the sign-on transaction drives it.
            final FakeRepository repository = new FakeRepository();
            final String keyed = "Qa" + throwawayCredential().substring(1);
            assertThat(keyed).containsPattern("[a-z]");
            final CredentialDigestService verifier = new CredentialDigestService();

            serviceFor(repository)
                    .addUser(recordRequest("MIXEDCS1", "NOELLE", "PARK", keyed, "U",
                            KeyAction.ENTER));

            final String stored = repository.rows.get("MIXEDCS1").credentialDigest();
            assertThat(verifier.matches(foldedByThisClass(keyed), stored))
                    .as("the sign-on transaction folds the presented secret before verifying it")
                    .isTrue();
            assertThat(verifier.matches(keyed, stored))
                    .as("an unfolded secret never reaches the verifier from the sign-on transaction")
                    .isFalse();
        }

        @Test
        @DisplayName("AN IDENTIFIER KEYED IN LOWER CASE IS STORED UNDER THE KEY SIGN-ON WILL LOOK IT UP "
                + "BY: without the fold the record existed and READ-USER-SEC-FILE could never find it")
        void aMixedCaseIdentifierIsStoredUnderTheFoldedKey() {
            final FakeRepository repository = new FakeRepository();

            final UserOutcome response = serviceFor(repository)
                    .addUser(recordRequest("lower001", "NOELLE", "PARK", throwawayCredential(), "U",
                            KeyAction.ENTER));

            assertThat(response.actionSucceeded()).isTrue();
            assertThat(repository.rows.keySet())
                    .as("the sign-on transaction folds the submitted identifier before it reads, so a "
                            + "record stored under the unfolded spelling is a record it cannot reach")
                    .containsExactly("LOWER001");
            assertThat(response.userId())
                    .as("and the successful add clears the identifier field, exactly as the source's "
                            + "normal arm does, so the echo is absence rather than either spelling")
                    .isNull();
        }

        @Test
        @DisplayName("a lower-case identifier is refused as a duplicate of the upper-case record it "
                + "would collide with, because the two spellings are one key")
        void aMixedCaseIdentifierCollidesWithItsFoldedRecord() {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "LOWER002", "OWEN", "HALL", "U", storedDigestOf(throwawayCredential()));

            final UserOutcome response = serviceFor(repository)
                    .addUser(recordRequest("lower002", "NOELLE", "PARK", throwawayCredential(), "U",
                            KeyAction.ENTER));

            assertThat(response.actionSucceeded())
                    .as("an unfolded key would have created a second record for the same identity")
                    .isFalse();
            assertThat(repository.rows.keySet()).containsExactly("LOWER002");
        }

        @Test
        @DisplayName("neither the digest nor the submitted value reaches the response or a log line")
        void neverDisclosesCredentialMaterial() {
            final FakeRepository repository = new FakeRepository();
            final String submitted = throwawayCredential();

            final UserOutcome response = serviceFor(repository)
                    .addUser(recordRequest("SECRETU1", "ANNA", "BLAKE", submitted, "A",
                            KeyAction.ENTER));

            final String digest = repository.rows.get("SECRETU1").credentialDigest();
            assertThat(String.valueOf(response)).doesNotContain(digest).doesNotContain(submitted);
            assertThat(response.toString()).doesNotContain("$2a$");
            assertThat(noCapturedLogContains(digest)).isTrue();
            assertThat(noCapturedLogContains(submitted)).isTrue();
            assertThat(noCapturedLogContains("$2a$")).isTrue();
        }

        @Test
        @DisplayName("the success text is built from the identifier up to its first space")
        void buildsTheSuccessTextFromTheDelimitedIdentifier() {
            final FakeRepository repository = new FakeRepository();

            final UserOutcome response = serviceFor(repository)
                    .addUser(recordRequest("SHORT   ", "LEE", "WU", throwawayCredential(), "U",
                            KeyAction.ENTER));

            assertThat(response.message()).isEqualTo("User SHORT has been added ...");
        }

        @Test
        @DisplayName("the fields are blanked before the success text is reported")
        void blanksTheFieldsOnSuccess() {
            final FakeRepository repository = new FakeRepository();

            final UserOutcome response = serviceFor(repository)
                    .addUser(recordRequest("BLANKED1", "OTTO", "VANCE", throwawayCredential(), "U",
                            KeyAction.ENTER));

            assertThat(response.userId()).isNull();
            assertThat(response.firstName()).isNull();
            assertThat(response.lastName()).isNull();
            assertThat(response.userType()).isNull();
            assertThat(response.focusScreenFieldId()).isEqualTo(FIELD_FIRST_NAME);
        }
    }

    @Nested
    @DisplayName("CU01 add: validation is emptiness only, in the source's order")
    class AddValidation {

        @Test
        @DisplayName("a given name with an embedded space is accepted, because no alphabetic edit exists")
        void acceptsAnEmbeddedSpaceInAName() {
            final FakeRepository repository = new FakeRepository();

            final UserOutcome response = serviceFor(repository)
                    .addUser(recordRequest("SPACEUSR", "MARY ANN", "O BRIEN", throwawayCredential(),
                            "U", KeyAction.ENTER));

            assertThat(response.actionSucceeded()).isTrue();
            assertThat(response.fieldErrors()).isEmpty();
            assertThat(repository.rows.get("SPACEUSR").getSecUsrFname()).isEqualTo("MARY ANN");
            assertThat(repository.rows.get("SPACEUSR").getSecUsrLname()).isEqualTo("O BRIEN");
        }

        /**
         * Five empty items report as one, because the legacy cascade stops at its first true clause.
         *
         * <p>The multi-way selection at {@code app/cbl/COUSR01C.cbl} L116-L151 evaluates its clauses in
         * order and executes only the first whose condition holds. Each clause raises the flag, moves
         * its own text, moves -1 to its own field's length and performs the send, so a submission with
         * every item empty produces exactly one text, one cursor position and one decorated field - the
         * given name, because that clause is first.
         *
         * <p>ASSERTING FIVE ENTRIES describes a screen the legacy cannot produce: it would
         * decorate four fields the operator was never told about and would have to choose which
         * of five texts to show on the single message line.</p>
         */
        @Test
        @DisplayName("the first empty item is the only one reported, and it owns the summary and the "
                + "cursor")
        void reportsOnlyTheFirstEmptyItem() {
            final UserOutcome response = serviceFor(new FakeRepository())
                    .addUser(recordRequest("", "", "", "", "", KeyAction.ENTER));

            assertThat(response.message()).isEqualTo(MSG_FIRST_NAME_EMPTY);
            assertThat(response.focusScreenFieldId()).isEqualTo(FIELD_FIRST_NAME);
            assertThat(response.generalError()).isTrue();
            assertThat(response.actionSucceeded()).isFalse();
            assertThat(response.fieldErrors())
                    .as("one clause fired, so one field is decorated")
                    .singleElement()
                    .satisfies(error -> {
                        assertThat(error.field()).isEqualTo("firstName");
                        assertThat(error.bmsFieldId()).isEqualTo(FIELD_FIRST_NAME);
                        assertThat(error.state()).isEqualTo(ValidationException.FieldState.MISSING);
                        assertThat(error.message()).isEqualTo(MSG_FIRST_NAME_EMPTY);
                    });
        }

        /**
         * Each later clause becomes reachable only once every earlier one is satisfied.
         *
         * <p>This is the positive counterpart of the test above: it walks the cascade one clause at a
         * time, supplying every earlier item so that exactly one clause can fire, and checks that the
         * single entry names the field that clause owns. Together the two pin the cascade's shape -
         * first-match selection and one entry per submission - rather than merely its first case.</p>
         */
        @Test
        @DisplayName("each clause in turn is the single reported item once the earlier ones are "
                + "supplied")
        void eachClauseInTurnIsTheSingleReportedItem() {
            final String credential = throwawayCredential();

            assertThat(serviceFor(new FakeRepository())
                    .addUser(recordRequest("A1", "GIVEN", "", credential, "U", KeyAction.ENTER))
                    .fieldErrors()).singleElement()
                    .satisfies(error -> assertThat(error.field()).isEqualTo("lastName"));
            assertThat(serviceFor(new FakeRepository())
                    .addUser(recordRequest("", "GIVEN", "FAMILY", credential, "U", KeyAction.ENTER))
                    .fieldErrors()).singleElement()
                    .satisfies(error -> assertThat(error.field()).isEqualTo("userId"));
            assertThat(serviceFor(new FakeRepository())
                    .addUser(recordRequest("A2", "GIVEN", "FAMILY", "", "U", KeyAction.ENTER))
                    .fieldErrors()).singleElement()
                    .satisfies(error -> assertThat(error.field()).isEqualTo("password"));
            assertThat(serviceFor(new FakeRepository())
                    .addUser(recordRequest("A3", "GIVEN", "FAMILY", credential, "", KeyAction.ENTER))
                    .fieldErrors()).singleElement()
                    .satisfies(error -> assertThat(error.field()).isEqualTo("userType"));
        }

        @Test
        @DisplayName("each item in the cascade produces its own exact text when it is the first empty one")
        void producesEachCascadeTextInTurn() {
            final String credential = throwawayCredential();
            assertThat(serviceFor(new FakeRepository())
                    .addUser(recordRequest("A1", "GIVEN", "", credential, "U", KeyAction.ENTER))
                    .message()).isEqualTo(MSG_LAST_NAME_EMPTY);
            assertThat(serviceFor(new FakeRepository())
                    .addUser(recordRequest("", "GIVEN", "FAMILY", credential, "U", KeyAction.ENTER))
                    .message()).isEqualTo(MSG_USER_ID_EMPTY);
            assertThat(serviceFor(new FakeRepository())
                    .addUser(recordRequest("A2", "GIVEN", "FAMILY", "", "U", KeyAction.ENTER))
                    .message()).isEqualTo(MSG_CREDENTIAL_EMPTY);
            assertThat(serviceFor(new FakeRepository())
                    .addUser(recordRequest("A3", "GIVEN", "FAMILY", credential, "", KeyAction.ENTER))
                    .message()).isEqualTo(MSG_USER_TYPE_EMPTY);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "   "})
        @DisplayName("an absent, empty or all-space item is blank in the legacy sense")
        void treatsSpacesAsBlank(final String candidate) {
            final UserOutcome response = serviceFor(new FakeRepository())
                    .addUser(recordRequest("B1", candidate, "FAMILY", throwawayCredential(), "U",
                            KeyAction.ENTER));

            assertThat(response.message()).isEqualTo(MSG_FIRST_NAME_EMPTY);
        }

        @Test
        @DisplayName("a first presentation validates nothing and decorates nothing")
        void appliesTheReEntryGate() {
            final UserOutcome response = serviceFor(new FakeRepository())
                    .addUser(recordRequest(null, null, null, null, null, KeyAction.ENTER,
                            firstEntry()));

            assertThat(response.fieldErrors()).isEmpty();
            assertThat(response.message()).isNull();
            assertThat(response.generalError()).isFalse();
            assertThat(response.focusScreenFieldId()).isEqualTo(FIELD_FIRST_NAME);
            assertThat(response.navigationContext().reEntry()).isTrue();
        }

        @Test
        @DisplayName("an existing identifier is refused with the duplicate text and never overwritten")
        void refusesADuplicateIdentifier() {
            final FakeRepository repository = new FakeRepository();
            final UserSecurity original =
                    seed(repository, "DUPUSR01", "FIRST", "OWNER", "U", FILLER_DIGEST);

            final UserOutcome response = serviceFor(repository)
                    .addUser(recordRequest("DUPUSR01", "OTHER", "PERSON", throwawayCredential(), "A",
                            KeyAction.ENTER));

            assertThat(response.message()).isEqualTo(MSG_ADD_USER_ID_ALREADY_EXIST);
            assertThat(response.generalError()).isTrue();
            assertThat(response.actionSucceeded()).isFalse();
            assertThat(response.focusScreenFieldId()).isEqualTo(FIELD_ADD_USER_ID);
            assertThat(response.fieldErrors()).singleElement()
                    .satisfies(error -> assertThat(error.state())
                            .isEqualTo(ValidationException.FieldState.INVALID));
            assertThat(repository.rows.get("DUPUSR01").getSecUsrFname()).isEqualTo("FIRST");
            assertThat(repository.rows.get("DUPUSR01").credentialDigest())
                    .isEqualTo(original.credentialDigest());
        }

        @Test
        @DisplayName("a failed write takes the default arm with the unable-to-add text")
        void reportsAFailedWrite() {
            final FakeRepository repository = new FakeRepository();
            repository.failOnSave = true;

            final UserOutcome response = serviceFor(repository)
                    .addUser(recordRequest("BOOMUSR1", "GIVEN", "FAMILY", throwawayCredential(), "U",
                            KeyAction.ENTER));

            assertThat(response.message()).isEqualTo(MSG_ADD_UNABLE_TO_ADD_USER);
            assertThat(response.generalError()).isTrue();
            assertThat(response.focusScreenFieldId()).isEqualTo(FIELD_FIRST_NAME);
            assertBoundedFailureDiagnostics(1, "User add write failed");
        }

        @Test
        @DisplayName("the fourth key clears the screen and the third routes to the administrative menu")
        void handlesTheAddScreenAttentionKeys() {
            final UserOutcome cleared = serviceFor(new FakeRepository())
                    .addUser(recordRequest("Z1", "GIVEN", "FAMILY", throwawayCredential(), "U",
                            KeyAction.PFK04));
            assertThat(cleared.message()).isNull();
            assertThat(cleared.userId()).isNull();
            assertThat(cleared.focusScreenFieldId()).isEqualTo(FIELD_FIRST_NAME);

            final UserOutcome back = serviceFor(new FakeRepository())
                    .addUser(recordRequest("Z2", "GIVEN", "FAMILY", null, "U", KeyAction.PFK03));
            assertThat(back.nextRoute()).isEqualTo(ROUTE_ADMIN_MENU);
        }
    }

    @Nested
    @DisplayName("CU02 update: an unchanged credential is carried forward, never re-hashed")
    class UpdateCredentialHandling {

        @Test
        @DisplayName("updating without supplying a credential leaves the stored digest byte-identical")
        void carriesTheStoredDigestForwardUntouched() {
            final FakeRepository repository = new FakeRepository();
            final String digestBefore = ENCODER.encode(throwawayCredential());
            seed(repository, "UPDUSR01", "JOHN", "SMITH", "U", digestBefore);

            final UserOutcome response = serviceFor(repository)
                    .updateUser(recordRequest("UPDUSR01", "JOHNNY", "SMITH", null, "U",
                            KeyAction.PFK05));

            assertThat(response.actionSucceeded()).isTrue();
            assertThat(response.message()).isEqualTo("User UPDUSR01 has been updated ...");
            assertThat(repository.rows.get("UPDUSR01").getSecUsrFname()).isEqualTo("JOHNNY");
            assertThat(repository.rows.get("UPDUSR01").credentialDigest())
                    .as("a re-hashed digest would be a digest of a digest and would lock the user out")
                    .isEqualTo(digestBefore);
        }

        @Test
        @DisplayName("re-typing the same credential is not a change and the digest is left alone")
        void treatsAReTypedCredentialAsUnchanged() {
            final FakeRepository repository = new FakeRepository();
            final String known = throwawayCredential();
            final String digestBefore = storedDigestOf(known);
            seed(repository, "UPDUSR02", "ANNE", "LEE", "U", digestBefore);

            final UserOutcome response = serviceFor(repository)
                    .updateUser(recordRequest("UPDUSR02", "ANNE", "LEE", known, "U",
                            KeyAction.PFK05));

            assertThat(response.message()).isEqualTo(MSG_UPDATE_NO_CHANGE);
            assertThat(response.generalError())
                    .as("the source emits this text without raising the error flag")
                    .isFalse();
            assertThat(response.actionSucceeded()).isFalse();
            assertThat(repository.rows.get("UPDUSR02").credentialDigest()).isEqualTo(digestBefore);
        }

        @Test
        @DisplayName("re-keying the same credential in a different case is not a change either, because "
                + "the terminal could not have transmitted the difference")
        void treatsAReCasedCredentialAsUnchanged() {
            final FakeRepository repository = new FakeRepository();
            final String known = "Qa" + throwawayCredential().substring(1);
            final String digestBefore = storedDigestOf(known);
            seed(repository, "UPDUSR07", "IVY", "NOLAN", "U", digestBefore);

            final UserOutcome response = serviceFor(repository)
                    .updateUser(recordRequest("UPDUSR07", "IVY", "NOLAN",
                            foldedByThisClass(known), "U", KeyAction.PFK05));

            assertThat(response.message()).isEqualTo(MSG_UPDATE_NO_CHANGE);
            assertThat(repository.rows.get("UPDUSR07").credentialDigest())
                    .as("a re-hash here would be a new digest for a credential nobody changed")
                    .isEqualTo(digestBefore);
        }

        @Test
        @DisplayName("a replacement credential keyed in lower case is stored in the form the sign-on "
                + "verifier will be given")
        void aMixedCaseReplacementIsStoredFolded() {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "UPDUSR08", "OWEN", "HALL", "U", storedDigestOf(throwawayCredential()));
            final String replacement = "Qa" + throwawayCredential().substring(1);
            final CredentialDigestService verifier = new CredentialDigestService();

            final UserOutcome response = serviceFor(repository)
                    .updateUser(recordRequest("UPDUSR08", "OWEN", "HALL", replacement, "U",
                            KeyAction.PFK05));

            assertThat(response.actionSucceeded()).isTrue();
            final String digestAfter = repository.rows.get("UPDUSR08").credentialDigest();
            assertThat(verifier.matches(foldedByThisClass(replacement), digestAfter))
                    .isTrue();
            assertThat(verifier.matches(replacement, digestAfter)).isFalse();
        }

        @Test
        @DisplayName("AN UPDATE ADDRESSED IN LOWER CASE REACHES THE RECORD SIGN-ON READS: the key it "
                + "resolves is the folded one, so the identity is not silently left unmaintained")
        void aMixedCaseIdentifierUpdatesTheFoldedRecord() {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "LOWER003", "OWEN", "HALL", "U", storedDigestOf(throwawayCredential()));

            final UserOutcome response = serviceFor(repository)
                    .updateUser(recordRequest("lower003", "OWEN", "HOLT", null, "U",
                            KeyAction.PFK05));

            assertThat(response.actionSucceeded())
                    .as("an unfolded key would have reported the record as not on file")
                    .isTrue();
            assertThat(repository.rows.get("LOWER003").getSecUsrLname()).isEqualTo("HOLT");
            assertThat(repository.rows.keySet())
                    .as("and no second row was created under the unfolded spelling")
                    .containsExactly("LOWER003");
        }

        @Test
        @DisplayName("a delete addressed in lower case removes the folded record, so the two spellings "
                + "cannot leave an identity that is neither maintainable nor removable")
        void aMixedCaseIdentifierDeletesTheFoldedRecord() {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "LOWER004", "OWEN", "HALL", "U", storedDigestOf(throwawayCredential()));

            final UserOutcome response = serviceFor(repository)
                    .deleteUser(recordRequest("lower004", "OWEN", "HALL", null, "U",
                            KeyAction.PFK05));

            assertThat(response.actionSucceeded()).isTrue();
            assertThat(repository.rows).isEmpty();
        }

        @Test
        @DisplayName("a genuinely different credential is hashed and replaces the stored digest")
        void hashesAGenuinelyNewCredential() {
            final FakeRepository repository = new FakeRepository();
            final String previous = throwawayCredential();
            final String digestBefore = storedDigestOf(previous);
            seed(repository, "UPDUSR03", "BOB", "KING", "U", digestBefore);
            final String replacement = throwawayCredential();

            final UserOutcome response = serviceFor(repository)
                    .updateUser(recordRequest("UPDUSR03", "BOB", "KING", replacement, "U",
                            KeyAction.PFK05));

            final String digestAfter = repository.rows.get("UPDUSR03").credentialDigest();
            assertThat(response.actionSucceeded()).isTrue();
            assertThat(digestAfter).hasSize(DIGEST_LENGTH).isNotEqualTo(digestBefore);
            assertThat(ENCODER.matches(foldedByThisClass(replacement), digestAfter))
                    .isTrue();
            assertThat(ENCODER.matches(foldedByThisClass(previous), digestAfter))
                    .isFalse();
            assertThat(String.valueOf(response)).doesNotContain(digestBefore)
                    .doesNotContain(digestAfter).doesNotContain(replacement);
            assertThat(noCapturedLogContains(digestAfter)).isTrue();
        }

        @Test
        @DisplayName("an emptied credential item is the emptiness arm, not an unchanged one")
        void distinguishesAnEmptiedItemFromAnAbsentOne() {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "UPDUSR04", "CARL", "WEST", "U", FILLER_DIGEST);

            final UserOutcome response = serviceFor(repository)
                    .updateUser(recordRequest("UPDUSR04", "CARL", "WEST", "", "U", KeyAction.PFK05));

            assertThat(response.message()).isEqualTo(MSG_CREDENTIAL_EMPTY);
            assertThat(response.generalError()).isTrue();
            assertThat(response.focusScreenFieldId()).isEqualTo(FIELD_CREDENTIAL_ITEM);
            assertThat(repository.rows.get("UPDUSR04").credentialDigest()).isEqualTo(FILLER_DIGEST);
        }

        @Test
        @DisplayName("loading an identity emits the press-to-save prompt and never the stored digest")
        void loadsWithoutEchoingTheCredential() {
            final FakeRepository repository = new FakeRepository();
            final String digest = ENCODER.encode(throwawayCredential());
            seed(repository, "LOADUSR1", "DANA", "REED", "A", digest);

            final UserOutcome response = serviceFor(repository)
                    .updateUser(recordRequest("LOADUSR1", null, null, null, null, KeyAction.ENTER));

            assertThat(response.message()).isEqualTo(MSG_UPDATE_PRESS_PF5);
            assertThat(response.firstName()).isEqualTo("DANA");
            assertThat(response.lastName()).isEqualTo("REED");
            assertThat(response.userType()).isEqualTo("A");
            assertThat(response.generalError()).isFalse();
            assertThat(String.valueOf(response)).doesNotContain(digest);
            assertThat(noCapturedLogContains(digest)).isTrue();
        }
    }

    @Nested
    @DisplayName("CU02 update: change detection, not-found and the attention keys")
    class UpdateBehaviour {

        @Test
        @DisplayName("an absent identity is the legacy not-found path rather than a raised failure")
        void reportsAnAbsentIdentity() {
            final UserOutcome response = serviceFor(new FakeRepository())
                    .updateUser(recordRequest("NOSUCHID", "GIVEN", "FAMILY", null, "U",
                            KeyAction.PFK05));

            assertThat(response.message()).isEqualTo(MSG_USER_ID_NOT_FOUND);
            assertThat(response.generalError()).isTrue();
            assertThat(response.actionSucceeded()).isFalse();
            assertThat(response.focusScreenFieldId()).isEqualTo(FIELD_LIST_USER_ID);
            assertThat(response.fieldErrors()).singleElement()
                    .satisfies(error -> assertThat(error.state())
                            .isEqualTo(ValidationException.FieldState.INVALID));
        }

        @Test
        @DisplayName("each editable field independently marks the record as modified")
        void detectsAChangeInEachField() {
            final FakeRepository givenName = new FakeRepository();
            seed(givenName, "CHG00001", "OLD", "FAMILY", "U", FILLER_DIGEST);
            assertThat(serviceFor(givenName).updateUser(recordRequest("CHG00001", "NEW", "FAMILY",
                    null, "U", KeyAction.PFK05)).actionSucceeded()).isTrue();

            final FakeRepository familyName = new FakeRepository();
            seed(familyName, "CHG00002", "GIVEN", "OLD", "U", FILLER_DIGEST);
            assertThat(serviceFor(familyName).updateUser(recordRequest("CHG00002", "GIVEN", "NEW",
                    null, "U", KeyAction.PFK05)).actionSucceeded()).isTrue();

            final FakeRepository type = new FakeRepository();
            seed(type, "CHG00003", "GIVEN", "FAMILY", "U", FILLER_DIGEST);
            assertThat(serviceFor(type).updateUser(recordRequest("CHG00003", "GIVEN", "FAMILY", null,
                    "A", KeyAction.PFK05)).actionSucceeded()).isTrue();
            assertThat(type.rows.get("CHG00003").getSecUsrType()).isEqualTo("A");
        }

        @Test
        @DisplayName("the exit key saves before it returns, and the twelfth key returns without saving")
        void distinguishesTheTwoReturningKeys() {
            final FakeRepository saving = new FakeRepository();
            seed(saving, "PF3USR01", "EVE", "HALL", "U", FILLER_DIGEST);
            final UserOutcome saved = serviceFor(saving)
                    .updateUser(recordRequest("PF3USR01", "EVELYN", "HALL", null, "U",
                            KeyAction.PFK03));
            assertThat(saving.rows.get("PF3USR01").getSecUsrFname()).isEqualTo("EVELYN");
            assertThat(saved.nextRoute()).isEqualTo(ROUTE_ADMIN_MENU);

            final FakeRepository abandoning = new FakeRepository();
            seed(abandoning, "PF12USR1", "FRED", "NASH", "U", FILLER_DIGEST);
            final UserOutcome abandoned = serviceFor(abandoning)
                    .updateUser(recordRequest("PF12USR1", "FREDERICK", "NASH", null, "U",
                            KeyAction.PFK12));
            assertThat(abandoning.rows.get("PF12USR1").getSecUsrFname()).isEqualTo("FRED");
            assertThat(abandoned.nextRoute()).isEqualTo(ROUTE_ADMIN_MENU);
        }

        @Test
        @DisplayName("the fourth key clears the screen and places the cursor on the identifier")
        void clearsTheUpdateScreen() {
            final UserOutcome response = serviceFor(new FakeRepository())
                    .updateUser(recordRequest("Y1", "GIVEN", "FAMILY", null, "U", KeyAction.PFK04));

            assertThat(response.message()).isNull();
            assertThat(response.userId()).isNull();
            assertThat(response.focusScreenFieldId()).isEqualTo(FIELD_LIST_USER_ID);
        }

        @Test
        @DisplayName("an empty identifier is refused before any read is attempted")
        void refusesAnEmptyIdentifier() {
            final UserOutcome response = serviceFor(new FakeRepository())
                    .updateUser(recordRequest("", "GIVEN", "FAMILY", null, "U", KeyAction.ENTER));

            assertThat(response.message()).isEqualTo(MSG_USER_ID_EMPTY);
            assertThat(response.generalError()).isTrue();
        }

        @Test
        @DisplayName("a handed-over selection is loaded on the first entry")
        void loadsAHandedOverSelection() {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "HANDOVR1", "LUKE", "OTIS", "U", FILLER_DIGEST);

            final UserOutcome response = serviceFor(repository)
                    .updateUser(recordRequest("HANDOVR1", null, null, null, null, KeyAction.ENTER,
                            firstEntry()));

            assertThat(response.firstName()).isEqualTo("LUKE");
            assertThat(response.message()).isEqualTo(MSG_UPDATE_PRESS_PF5);
        }

        @Test
        @DisplayName("a failed save and a failed read take their own default arms")
        void reportsStoreFailures() {
            final FakeRepository saveFails = new FakeRepository();
            seed(saveFails, "SAVEFAIL", "HUGH", "BELL", "U", FILLER_DIGEST);
            saveFails.failOnSave = true;
            assertThat(serviceFor(saveFails).updateUser(recordRequest("SAVEFAIL", "HUGHIE", "BELL",
                    null, "U", KeyAction.PFK05)).message())
                    .isEqualTo(MSG_UPDATE_UNABLE_TO_UPDATE_USER);

            final FakeRepository readFails = new FakeRepository();
            readFails.failOnFind = true;
            assertThat(serviceFor(readFails).updateUser(recordRequest("READFAIL", "GIVEN", "FAMILY",
                    null, "U", KeyAction.PFK05)).message())
                    .isEqualTo(MSG_LIST_UNABLE_TO_LOOKUP_USER);
            assertBoundedFailureDiagnostics(
                    2, "User update save failed", "User update read failed");
        }
    }

    @Nested
    @DisplayName("The type field is accepted rather than policed")
    class RawTypeCode {

        @ParameterizedTest
        @ValueSource(strings = {"Z", "a", "u", "1", "*"})
        @DisplayName("a stored code outside the declared pair loads and round-trips without raising")
        void loadsAnUnrecognisedCode(final String rawCode) {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "ODDTYPE1", "GINA", "PARK", rawCode, FILLER_DIGEST);

            final UserOutcome loaded = serviceFor(repository)
                    .updateUser(recordRequest("ODDTYPE1", null, null, null, null, KeyAction.ENTER));

            assertThat(loaded.userType()).isEqualTo(rawCode);
            assertThat(loaded.generalError()).isFalse();
            assertThat(loaded.message()).isEqualTo(MSG_UPDATE_PRESS_PF5);
        }

        @ParameterizedTest
        @ValueSource(strings = {"Q", "a", "9"})
        @DisplayName("an unrecognised code is stored verbatim on a save")
        void storesAnUnrecognisedCode(final String rawCode) {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "ODDTYPE2", "GINA", "PARK", "U", FILLER_DIGEST);

            final UserOutcome saved = serviceFor(repository)
                    .updateUser(recordRequest("ODDTYPE2", "GINA", "PARK", null, rawCode,
                            KeyAction.PFK05));

            assertThat(saved.actionSucceeded()).isTrue();
            assertThat(repository.rows.get("ODDTYPE2").getSecUsrType()).isEqualTo(rawCode);
        }

        @Test
        @DisplayName("an unrecognised code is accepted on an add as well")
        void acceptsAnUnrecognisedCodeOnAdd() {
            final FakeRepository repository = new FakeRepository();

            final UserOutcome response = serviceFor(repository)
                    .addUser(recordRequest("ODDTYPE3", "GIVEN", "FAMILY", throwawayCredential(), "X",
                            KeyAction.ENTER));

            assertThat(response.actionSucceeded()).isTrue();
            assertThat(repository.rows.get("ODDTYPE3").getSecUsrType()).isEqualTo("X");
        }
    }

    @Nested
    @DisplayName("CU03 delete: confirmation, removal and the source's reused failure text")
    class Delete {

        @Test
        @DisplayName("the enter key shows the record with the confirmation prompt and removes nothing")
        void showsTheRecordBeforeRemoving() {
            final FakeRepository repository = new FakeRepository();
            final String digest = ENCODER.encode(throwawayCredential());
            seed(repository, "DELUSR01", "IVAN", "ROSS", "A", digest);

            final UserOutcome response = serviceFor(repository)
                    .deleteUser(recordRequest("DELUSR01", null, null, null, null, KeyAction.ENTER));

            assertThat(response.message()).isEqualTo(MSG_DELETE_PRESS_PF5);
            assertThat(response.firstName()).isEqualTo("IVAN");
            assertThat(response.lastName()).isEqualTo("ROSS");
            assertThat(response.userType()).isEqualTo("A");
            assertThat(repository.rows).containsKey("DELUSR01");
            assertThat(String.valueOf(response)).doesNotContain(digest);
        }

        @Test
        @DisplayName("the exit key returns without deleting, unlike the update screen's exit key")
        void doesNotDeleteOnTheExitKey() {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "DELUSR02", "IVAN", "ROSS", "A", FILLER_DIGEST);

            final UserOutcome response = serviceFor(repository)
                    .deleteUser(recordRequest("DELUSR02", "IVAN", "ROSS", null, "A",
                            KeyAction.PFK03));

            assertThat(repository.rows).containsKey("DELUSR02");
            assertThat(response.nextRoute()).isEqualTo(ROUTE_ADMIN_MENU);
        }

        @Test
        @DisplayName("the confirming key removes the row and reports the delete text")
        void removesOnConfirmation() {
            final FakeRepository repository = new FakeRepository();
            final String digest = ENCODER.encode(throwawayCredential());
            seed(repository, "DELUSR03", "IVAN", "ROSS", "A", digest);

            final UserOutcome response = serviceFor(repository)
                    .deleteUser(recordRequest("DELUSR03", "IVAN", "ROSS", null, "A",
                            KeyAction.PFK05));

            assertThat(response.message()).isEqualTo("User DELUSR03 has been deleted ...");
            assertThat(response.actionSucceeded()).isTrue();
            assertThat(response.userId()).isNull();
            assertThat(repository.rows).doesNotContainKey("DELUSR03");
            assertThat(String.valueOf(response)).doesNotContain(digest);
            assertThat(noCapturedLogContains(digest)).isTrue();
        }

        @Test
        @DisplayName("an absent identifier and an empty one each report their own text")
        void reportsAbsentAndEmptyIdentifiers() {
            assertThat(serviceFor(new FakeRepository()).deleteUser(recordRequest("NOSUCHID", null,
                    null, null, null, KeyAction.PFK05)).message()).isEqualTo(MSG_USER_ID_NOT_FOUND);
            assertThat(serviceFor(new FakeRepository()).deleteUser(recordRequest("", null, null,
                    null, null, KeyAction.PFK05)).message()).isEqualTo(MSG_USER_ID_EMPTY);
        }

        @Test
        @DisplayName("source oddity preserved: a failed removal emits the UPDATE text, not a delete text")
        void preservesTheReusedFailureText() {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "DELFAIL1", "JUNE", "KERR", "U", FILLER_DIGEST);
            repository.failOnDelete = true;

            final UserOutcome response = serviceFor(repository)
                    .deleteUser(recordRequest("DELFAIL1", null, null, null, null, KeyAction.PFK05));

            assertThat(response.message())
                    .as("app/cbl/COUSR03C.cbl L332 names the update operation in the delete failure arm")
                    .isEqualTo(MSG_UPDATE_UNABLE_TO_UPDATE_USER);
            assertThat(response.generalError()).isTrue();
            assertBoundedFailureDiagnostics(1, "User delete removal failed");
        }

        @Test
        @DisplayName("the fourth key clears the screen")
        void clearsTheDeleteScreen() {
            final UserOutcome response = serviceFor(new FakeRepository())
                    .deleteUser(recordRequest("X1", "GIVEN", "FAMILY", null, "U", KeyAction.PFK04));

            assertThat(response.message()).isNull();
            assertThat(response.userId()).isNull();
            assertThat(response.focusScreenFieldId()).isEqualTo(FIELD_LIST_USER_ID);
        }

        @Test
        @DisplayName("the twelfth key returns to the administrative menu")
        void returnsOnTheTwelfthKey() {
            final UserOutcome response = serviceFor(new FakeRepository())
                    .deleteUser(recordRequest("X2", "GIVEN", "FAMILY", null, "U", KeyAction.PFK12));

            assertThat(response.nextRoute()).isEqualTo(ROUTE_ADMIN_MENU);
        }

        @Test
        @DisplayName("a handed-over selection is loaded on the first entry")
        void loadsAHandedOverSelection() {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "DELHAND1", "NORA", "PEEL", "U", FILLER_DIGEST);

            final UserOutcome response = serviceFor(repository)
                    .deleteUser(recordRequest("DELHAND1", null, null, null, null, KeyAction.ENTER,
                            firstEntry()));

            assertThat(response.firstName()).isEqualTo("NORA");
            assertThat(response.message()).isEqualTo(MSG_DELETE_PRESS_PF5);
        }

        @Test
        @DisplayName("a failed read on the enter key reports the lookup-failure text")
        void reportsAFailedReadOnLoad() {
            final FakeRepository repository = new FakeRepository();
            repository.failOnFind = true;

            assertThat(serviceFor(repository).deleteUser(recordRequest("READFAIL", null, null, null,
                    null, KeyAction.ENTER)).message()).isEqualTo(MSG_LIST_UNABLE_TO_LOOKUP_USER);
            assertBoundedFailureDiagnostics(1, "User delete read failed");
        }

        @Test
        @DisplayName("a failed read on the confirming key is not reported as a missing row")
        void distinguishesAFailedReadFromAMissingRow() {
            final FakeRepository failing = new FakeRepository();
            failing.failOnFind = true;

            // The source performs the removal after the read with no test between the two, so the
            // removal reports through an arm of its own. A hard read failure leaves no position, which
            // is the catch-all arm and not the not-found arm.
            assertThat(serviceFor(failing).deleteUser(recordRequest("READFAIL", null, null, null,
                    null, KeyAction.PFK05)).message())
                    .isEqualTo(MSG_UPDATE_UNABLE_TO_UPDATE_USER);

            // A read that simply found nothing is the not-found arm, with its own text.
            assertThat(serviceFor(new FakeRepository()).deleteUser(recordRequest("ABSENTID", null,
                    null, null, null, KeyAction.PFK05)).message())
                    .isEqualTo(MSG_USER_ID_NOT_FOUND);
        }
    }

    // ==============================================================================================
    // One unit of work per maintenance step
    //
    // Both maintenance transactions read for update and then write in the SAME task:
    // COUSR02C L322-L331 before its rewrite at L360, and COUSR03C L269-L278 before its delete at L307 -
    // a delete that names no record identifier at all, so the only record it can remove is the one that
    // read is holding. Reading in one unit and writing in a later one lets another administrator change
    // or remove the same identity in between, and both statements still succeed, so the operator is
    // never told.
    //
    // The display paths perform the same paragraph but write nothing, so they read without the hold: a
    // lock held for the duration of a screen display buys nothing and serialises every other reader.
    // ==============================================================================================

    @Nested
    @DisplayName("One unit of work per maintenance step, COUSR02C L215-L243 and COUSR03C L189-L191")
    class MaintenanceUnitOfWork {

        @Test
        @DisplayName("the update SAVE path reads the record through the held form, so the rewrite "
                + "cannot be interleaved with another administrator's")
        void theUpdateSavePathReadsThroughTheHeldForm() {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "HOLD0001", "OLD", "FAMILY", "U", FILLER_DIGEST);

            final UserOutcome response = serviceFor(repository)
                    .updateUser(recordRequest("HOLD0001", "NEW", "FAMILY", null, "U",
                            KeyAction.PFK05));

            assertThat(response.actionSucceeded()).isTrue();
            assertThat(repository.heldReads)
                    .as("exactly one held read, taken by the save path before the rewrite")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the update DISPLAY path takes no hold, because a row locked for the length of a "
                + "screen display serialises every other reader and changes nothing")
        void theUpdateDisplayPathTakesNoHold() {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "HOLD0002", "GIVEN", "FAMILY", "U", FILLER_DIGEST);

            final UserOutcome response = serviceFor(repository)
                    .updateUser(recordRequest("HOLD0002", null, null, null, null, KeyAction.ENTER));

            assertThat(response.message()).isEqualTo(MSG_UPDATE_PRESS_PF5);
            assertThat(response.firstName()).isEqualTo("GIVEN");
            assertThat(repository.heldReads).isZero();
        }

        @Test
        @DisplayName("the delete CONFIRM path reads the record through the held form, because the "
                + "legacy delete verb can only mean the record the read is holding")
        void theDeleteConfirmPathReadsThroughTheHeldForm() {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "HOLD0003", "GIVEN", "FAMILY", "U", FILLER_DIGEST);

            final UserOutcome response = serviceFor(repository)
                    .deleteUser(recordRequest("HOLD0003", null, null, null, null, KeyAction.PFK05));

            assertThat(response.actionSucceeded()).isTrue();
            assertThat(repository.rows).doesNotContainKey("HOLD0003");
            assertThat(repository.heldReads).isEqualTo(1);
        }

        @Test
        @DisplayName("the delete DISPLAY path takes no hold")
        void theDeleteDisplayPathTakesNoHold() {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "HOLD0004", "GIVEN", "FAMILY", "U", FILLER_DIGEST);

            final UserOutcome response = serviceFor(repository)
                    .deleteUser(recordRequest("HOLD0004", null, null, null, null, KeyAction.ENTER));

            assertThat(response.message()).isEqualTo(MSG_DELETE_PRESS_PF5);
            assertThat(repository.rows).containsKey("HOLD0004");
            assertThat(repository.heldReads).isZero();
        }

        @Test
        @DisplayName("a rewrite that fails when its unit COMMITS reports the failure arm and leaves no "
                + "success behind, which is only possible because the unit completes outside the service")
        void aCommitFailureOnUpdateReportsTheFailureArm() {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "COMMIT01", "OLD", "FAMILY", "U", FILLER_DIGEST);

            final UserOutcome response = serviceWithBoundary(repository,
                    new UnitFailingAtCommit(new IllegalStateException("commit refused")))
                    .updateUser(recordRequest("COMMIT01", "NEW", "FAMILY", null, "U",
                            KeyAction.PFK05));

            assertThat(response.message()).isEqualTo(MSG_UPDATE_UNABLE_TO_UPDATE_USER);
            assertThat(response.generalError()).isTrue();
            assertThat(response.actionSucceeded())
                    .as("the unit rolled back, so no success may be reported")
                    .isFalse();
        }

        @Test
        @DisplayName("a removal that fails when its unit COMMITS reports the source's reused "
                + "unable-to-update text and leaves no success behind")
        void aCommitFailureOnDeleteReportsTheFailureArm() {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "COMMIT02", "GIVEN", "FAMILY", "U", FILLER_DIGEST);

            final UserOutcome response = serviceWithBoundary(repository,
                    new UnitFailingAtCommit(new IllegalStateException("commit refused")))
                    .deleteUser(recordRequest("COMMIT02", null, null, null, null, KeyAction.PFK05));

            assertThat(response.message()).isEqualTo(MSG_UPDATE_UNABLE_TO_UPDATE_USER);
            assertThat(response.generalError()).isTrue();
            assertThat(response.actionSucceeded()).isFalse();
        }

        @Test
        @DisplayName("a read that fails inside the update unit still reports the LOOKUP text, not the "
                + "rewrite text, because the two statements of one unit report differently")
        void aHeldReadFailureOnUpdateReportsTheLookupArm() {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "RDFAIL01", "OLD", "FAMILY", "U", FILLER_DIGEST);
            repository.failOnFind = true;

            final UserOutcome response = serviceFor(repository)
                    .updateUser(recordRequest("RDFAIL01", "NEW", "FAMILY", null, "U",
                            KeyAction.PFK05));

            assertThat(response.message()).isEqualTo(MSG_LIST_UNABLE_TO_LOOKUP_USER);
            assertThat(response.generalError()).isTrue();
            assertThat(response.actionSucceeded()).isFalse();
            assertThat(repository.rows.get("RDFAIL01").getSecUsrFname())
                    .as("nothing was rewritten")
                    .isEqualTo("OLD");
        }

        @Test
        @DisplayName("an unchanged record writes nothing at all, and the please-modify text survives "
                + "the unit of work that read it")
        void anUnchangedRecordWritesNothing() {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "NOCHNG01", "GIVEN", "FAMILY", "U", FILLER_DIGEST);

            final UserOutcome response = serviceFor(repository)
                    .updateUser(recordRequest("NOCHNG01", "GIVEN", "FAMILY", null, "U",
                            KeyAction.PFK05));

            assertThat(response.message()).isEqualTo(MSG_UPDATE_NO_CHANGE);
            assertThat(response.actionSucceeded()).isFalse();
            assertThat(repository.heldReads)
                    .as("the record is still read under the hold: the comparison is what decides")
                    .isEqualTo(1);
        }

        private UserManagementService serviceWithBoundary(final FakeRepository repository,
                final OnlineTransactionBoundary boundary) {
            return new UserManagementService(repository, messageCatalogService, navigationService,
                    PAGE_TOKEN_SERVICE, boundary, recordWriterFor(repository), ENCODER, FIXED_CLOCK);
        }
    }

    /**
     * A unit of work that runs its operation and then fails, standing in for a failure raised when the
     * unit commits rather than when a statement executes.
     *
     * <p>Only a unit that completes outside the service can fail this way, which is exactly the property
     * under test: the response arm has to be chosen after the unit has rolled back, so no success text
     * and no raised success flag can survive a refused commit.
     */
    private static final class UnitFailingAtCommit extends OnlineTransactionBoundary {

        private final RuntimeException failure;

        UnitFailingAtCommit(final RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public <T> T execute(final Supplier<T> operation) {
            super.execute(operation);
            throw this.failure;
        }
    }

    @Nested
    @DisplayName("CU00 list: a page of exactly ten rows, ascending in both directions")
    class ListPaging {

        @Test
        @DisplayName("a full page carries exactly ten rows, ascending, with the page number zero-filled")
        void fillsAFullPage() {
            final UserOutcome response = serviceFor(repositoryOf(25))
                    .listUsers(listRequest(KeyAction.ENTER, null, null, null, List.of()));

            assertThat(response.rows()).hasSize(SCREEN_ROWS);
            assertThat(userIdsOf(response)).containsExactly("USER0001", "USER0002", "USER0003",
                    "USER0004", "USER0005", "USER0006", "USER0007", "USER0008", "USER0009",
                    "USER0010");
            assertThat(response.pageMetadata().pageSize()).isEqualTo(SCREEN_ROWS);
            assertThat(response.pageMetadata().direction())
                    .isEqualTo(BrowseWindow.PagingDirection.FORWARD);
            assertThat(response.pageMetadata().displayedPageNumber()).isEqualTo("00000001");
            assertThat(response.pageMetadata().hasMorePages()).isTrue();
            assertThat(response.pageMetadata().hasPreviousPages()).isFalse();
            assertThat(response.pageMetadata().previousCursorKey()).isEqualTo("USER0001");
            assertThat(response.pageMetadata().nextCursorKey()).isEqualTo("USER0010");
        }

        @Test
        @DisplayName("walking forward reaches the following pages and the short final page")
        void walksForward() {
            final FakeRepository repository = repositoryOf(25);

            final UserOutcome second = serviceFor(repository)
                    .listUsers(listRequest(KeyAction.PFK08, null, "USER0001", "USER0010",
                            List.of()));
            assertThat(userIdsOf(second)).containsExactly("USER0011", "USER0012", "USER0013",
                    "USER0014", "USER0015", "USER0016", "USER0017", "USER0018", "USER0019",
                    "USER0020");
            assertThat(second.pageMetadata().displayedPageNumber()).isEqualTo("00000002");
            assertThat(second.pageMetadata().hasPreviousPages()).isTrue();

            final UserOutcome third = serviceFor(repository)
                    .listUsers(listRequest(KeyAction.PFK08, null, "USER0011", "USER0020",
                            List.of()));
            assertThat(userIdsOf(third)).containsExactly("USER0021", "USER0022", "USER0023",
                    "USER0024", "USER0025");
            assertThat(third.pageMetadata().hasMorePages()).isFalse();
            assertThat(third.message()).isEqualTo(MSG_LIST_REACHED_BOTTOM);
        }

        @Test
        @DisplayName("a page walked backward presents ascending, not in the order it was read")
        void presentsABackwardPageAscending() {
            final FakeRepository repository = repositoryOf(25);

            final UserOutcome fromSecond = serviceFor(repository)
                    .listUsers(listRequest(KeyAction.PFK07, null, "USER0011", "USER0020",
                            List.of()));

            assertThat(fromSecond.rows()).hasSize(SCREEN_ROWS);
            assertThat(userIdsOf(fromSecond))
                    .as("the legacy fills its slots from the bottom upward, so the page still ascends")
                    .containsExactly("USER0001", "USER0002", "USER0003", "USER0004", "USER0005",
                            "USER0006", "USER0007", "USER0008", "USER0009", "USER0010");
            assertThat(fromSecond.pageMetadata().direction())
                    .isEqualTo(BrowseWindow.PagingDirection.BACKWARD);

            final UserOutcome fromThird = serviceFor(repository)
                    .listUsers(listRequest(KeyAction.PFK07, null, "USER0021", "USER0025",
                            List.of()));
            assertThat(userIdsOf(fromThird)).containsExactly("USER0011", "USER0012", "USER0013",
                    "USER0014", "USER0015", "USER0016", "USER0017", "USER0018", "USER0019",
                    "USER0020");
        }

        @Test
        @DisplayName("a short backward page keeps its rows ascending and leaves the earlier slots blank")
        void presentsAShortBackwardPageAscending() {
            final FakeRepository repository = repositoryOf(13);

            final UserOutcome response = serviceFor(repository)
                    .listUsers(listRequest(KeyAction.PFK07, null, "USER0011", "USER0013",
                            List.of()));

            assertThat(userIdsOf(response)).containsExactly("USER0001", "USER0002", "USER0003",
                    "USER0004", "USER0005", "USER0006", "USER0007", "USER0008", "USER0009",
                    "USER0010");
        }

        @Test
        @DisplayName("the paging keys are refused at each end with their own texts")
        void refusesPagingBeyondEitherEnd() {
            final FakeRepository repository = repositoryOf(25);

            assertThat(serviceFor(repository).listUsers(listRequest(KeyAction.PFK07, null,
                    "USER0001", "USER0010", List.of())).message())
                    .isEqualTo(MSG_LIST_ALREADY_AT_TOP);
            assertThat(serviceFor(repository).listUsers(listRequest(KeyAction.PFK08, null,
                    "USER0021", "USER0025", List.of())).message())
                    .isEqualTo(MSG_LIST_ALREADY_AT_BOTTOM);
        }

        /**
         * Both boundary refusals ask the operator's page to be kept rather than rebuilt.
         *
         * <p>The two arms clear {@code SEND-ERASE-NO} - already at the top at
         * {@code app/cbl/COUSR00C.cbl} L250-L254 and already at the bottom at L272-L276 - and both
         * release the browse and return no rows, so the legacy overwrites the screen in place and the
         * ten rows the operator was looking at remain. The outcome now says so, which is what stops a
         * client blanking a page the legacy keeps: without the instruction, this reply is byte for byte
         * indistinguishable from an empty page.</p>
         */
        @Test
        @DisplayName("both boundary refusals ask for the displayed page to be retained, and return no "
                + "rows, so the instruction is the only thing telling them apart from an empty page")
        void bothBoundaryRefusalsRetainTheDisplayedPage() {
            final FakeRepository repository = repositoryOf(25);

            final UserOutcome atTop = serviceFor(repository).listUsers(
                    listRequest(KeyAction.PFK07, null, "USER0001", "USER0010", List.of()));
            final UserOutcome atBottom = serviceFor(repository).listUsers(
                    listRequest(KeyAction.PFK08, null, "USER0021", "USER0025", List.of()));

            assertThat(atTop.preserveDisplayedPage()).isTrue();
            assertThat(atTop.rows()).isEmpty();
            assertThat(atBottom.preserveDisplayedPage()).isTrue();
            assertThat(atBottom.rows()).isEmpty();
        }

        /**
         * Every other arm rebuilds, so the instruction distinguishes rather than merely being present.
         */
        @Test
        @DisplayName("a turn that fills a page asks for a rebuild, not a retention")
        void aTurnThatFillsAPageAsksForARebuild() {
            final UserOutcome filled = serviceFor(repositoryOf(25)).listUsers(
                    listRequest(KeyAction.ENTER, null, null, null, List.of()));

            assertThat(filled.rows()).hasSize(SCREEN_ROWS);
            assertThat(filled.preserveDisplayedPage())
                    .as("rows were returned, so the client replaces what it is showing")
                    .isFalse();
        }

        /**
         * A genuine page move also rebuilds, which is the case most easily confused with a refusal.
         */
        @Test
        @DisplayName("a backward key that actually moves a page rebuilds, unlike one refused at the top")
        void aBackwardKeyThatMovesAPageRebuilds() {
            final FakeRepository repository = repositoryOf(25);
            final UserManagementService service = serviceFor(repository);
            final UserOutcome secondPage = service.listUsers(
                    listRequest(KeyAction.PFK08, null, "USER0001", "USER0010", List.of()));

            final UserOutcome movedBack = service.listUsers(listRequest(KeyAction.PFK07, null,
                    secondPage.pageMetadata().previousCursorKey(),
                    secondPage.pageMetadata().nextCursorKey(), List.of()));

            assertThat(movedBack.rows()).isNotEmpty();
            assertThat(movedBack.preserveDisplayedPage()).isFalse();
        }

        /**
         * The three single-record screens have one always-erasing send, so none of them ever retains.
         */
        @Test
        @DisplayName("the single-record screens never ask for a retention, because their one send verb "
                + "always erases")
        void theSingleRecordScreensNeverRetain() {
            final FakeRepository repository = repositoryOf(3);

            assertThat(serviceFor(repository)
                    .addUser(recordRequest("", "", "", "", "", KeyAction.ENTER))
                    .preserveDisplayedPage()).isFalse();
            assertThat(serviceFor(repository)
                    .updateUser(recordRequest("", "", "", null, "", KeyAction.PFK05))
                    .preserveDisplayedPage()).isFalse();
            assertThat(serviceFor(repository)
                    .deleteUser(recordRequest("   ", null, null, null, null, KeyAction.ENTER))
                    .preserveDisplayedPage()).isFalse();
        }

        @Test
        @DisplayName("positioning is greater-or-equal, so a key that matches nothing lands on the next")
        void positionsGreaterOrEqual() {
            final FakeRepository repository = repositoryOf(25);

            assertThat(userIdsOf(serviceFor(repository).listUsers(listRequest(KeyAction.ENTER,
                    "USER0015", null, null, List.of())))).startsWith("USER0015");
            assertThat(userIdsOf(serviceFor(repository).listUsers(listRequest(KeyAction.ENTER,
                    "USER0014X", null, null, List.of())))).startsWith("USER0015");
        }

        @Test
        @DisplayName("a key above every row positions nowhere and reports the at-the-top text")
        void reportsAKeyPastTheEnd() {
            final UserOutcome response = serviceFor(repositoryOf(25))
                    .listUsers(listRequest(KeyAction.ENTER, "ZZZZZZZZ", null, null, List.of()));

            assertThat(response.rows()).isEmpty();
            assertThat(response.message()).isEqualTo(MSG_LIST_AT_TOP);
        }

        @Test
        @DisplayName("an empty table yields no rows and the at-the-top text")
        void handlesAnEmptyTable() {
            final UserOutcome response = serviceFor(new FakeRepository())
                    .listUsers(listRequest(KeyAction.ENTER, null, null, null, List.of()));

            assertThat(response.rows()).isEmpty();
            assertThat(response.message()).isEqualTo(MSG_LIST_AT_TOP);
        }

        @Test
        @DisplayName("a partial single page reports no successor and carries no last-row cursor")
        void handlesAPartialFirstPage() {
            final UserOutcome response = serviceFor(repositoryOf(4))
                    .listUsers(listRequest(KeyAction.ENTER, null, null, null, List.of()));

            assertThat(response.rows()).hasSize(4);
            assertThat(response.pageMetadata().hasMorePages()).isFalse();
            assertThat(response.pageMetadata().nextCursorKey()).isNull();
            assertThat(response.pageMetadata().previousCursorKey()).isEqualTo("USER0001");
        }

        @Test
        @DisplayName("no list row carries a selector and no list response carries a credential")
        void listRowsCarryNoCredential() {
            final FakeRepository repository = repositoryOf(25);

            final UserOutcome response = serviceFor(repository)
                    .listUsers(listRequest(KeyAction.ENTER, null, null, null, List.of()));

            assertThat(response.rows()).allMatch(row -> row.selector() == null);
            assertThat(String.valueOf(response)).doesNotContain(FILLER_DIGEST);
            assertThat(String.valueOf(response.rows())).doesNotContain(FILLER_DIGEST);
            assertThat(noCapturedLogContains(FILLER_DIGEST)).isTrue();
        }
    }

    @Nested
    @DisplayName("CU00 list: positional row selection, first non-blank wins")
    class ListSelection {

        @Test
        @DisplayName("the update marker dispatches to the update screen with the marked row's identifier")
        void dispatchesTheUpdateMarker() {
            final UserOutcome response =
                    submitSelection(repositoryOf(25), null, selectionAt(3, "U"));

            assertThat(response.nextRoute()).isEqualTo(ROUTE_USER_UPDATE);
            assertThat(response.userId()).isEqualTo("USER0003");
        }

        @Test
        @DisplayName("a lower-case delete marker on the last row dispatches to the delete screen")
        void dispatchesTheLowerCaseDeleteMarker() {
            final UserOutcome response =
                    submitSelection(repositoryOf(25), null, selectionAt(SCREEN_ROWS, "d"));

            assertThat(response.nextRoute()).isEqualTo(ROUTE_USER_DELETE);
            assertThat(response.userId()).isEqualTo("USER0010");
        }

        @ParameterizedTest
        @ValueSource(strings = {"U", "u"})
        @DisplayName("both cases of the update marker are accepted")
        void acceptsBothCasesOfTheUpdateMarker(final String marker) {
            assertThat(submitSelection(repositoryOf(25), null, selectionAt(1, marker)).nextRoute())
                    .isEqualTo(ROUTE_USER_UPDATE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"D", "d"})
        @DisplayName("both cases of the delete marker are accepted")
        void acceptsBothCasesOfTheDeleteMarker(final String marker) {
            assertThat(submitSelection(repositoryOf(25), null, selectionAt(1, marker)).nextRoute())
                    .isEqualTo(ROUTE_USER_DELETE);
        }

        @Test
        @DisplayName("with several rows marked, the earliest wins and the later ones are not examined")
        void takesTheFirstMarkedRow() {
            final List<String> selections = new ArrayList<>(Collections.nCopies(SCREEN_ROWS, " "));
            selections.set(1, "U");
            selections.set(5, "D");

            final UserOutcome response = submitSelection(repositoryOf(25), null, selections);

            assertThat(response.nextRoute()).isEqualTo(ROUTE_USER_UPDATE);
            assertThat(response.userId()).isEqualTo("USER0002");
        }

        @Test
        @DisplayName("empty positions survive, so a marker keeps the row it was typed against")
        void preservesEmptyPositions() {
            final List<String> selections = new ArrayList<>(Collections.nCopies(SCREEN_ROWS, ""));
            selections.set(6, "U");

            final UserOutcome response = submitSelection(repositoryOf(25), null, selections);

            assertThat(response.userId()).isEqualTo("USER0007");
        }

        @Test
        @DisplayName("an unrecognised marker reports the invalid-selection text and rebuilds the page")
        void rejectsAnUnrecognisedMarker() {
            final UserOutcome response =
                    submitSelection(repositoryOf(25), null, selectionAt(1, "X"));

            assertThat(response.message()).isEqualTo(MSG_LIST_INVALID_SELECTION);
            assertThat(response.generalError())
                    .as("the source emits this text without raising the error flag")
                    .isFalse();
            assertThat(response.nextRoute()).isNull();
            assertThat(response.rows()).hasSize(SCREEN_ROWS);
        }

        @Test
        @DisplayName("a selection beyond the screen's row count never reaches this service, because "
                + "the request boundary refuses to represent one")
        void aSelectionBeyondTheScreenIsUnrepresentable() {
            // The legacy screen declares exactly ten selectable rows, so an eleventh selection is not
            // a value the terminal could ever have submitted. The request record refuses it at
            // construction rather than leaving each service to skip the surplus, which is why this
            // asserts the refusal instead of asserting that the surplus is ignored: under this contract
            // there is no reachable path on which a service sees one.
            final List<String> selections =
                    new ArrayList<>(Collections.nCopies(SCREEN_ROWS + 2, " "));
            selections.set(SCREEN_ROWS + 1, "U");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> listRequest(KeyAction.ENTER, null, "USER0001", "USER0010",
                            selections))
                    .withMessageContaining(String.valueOf(SCREEN_ROWS))
                    .withMessageContaining(String.valueOf(SCREEN_ROWS + 2));
        }

        @Test
        @DisplayName("a full page of selections that names none of them simply rebuilds the page")
        void aFullPageOfBlankSelectionsRebuildsThePage() {
            // The boundary condition the withdrawn case was reaching for, expressed at the width the
            // screen actually has: ten entries, every one of them blank.
            final List<String> selections =
                    new ArrayList<>(Collections.nCopies(SCREEN_ROWS, " "));

            final UserOutcome response = serviceFor(repositoryOf(25))
                    .listUsers(listRequest(KeyAction.ENTER, null, "USER0001", "USER0010",
                            selections));

            assertThat(response.nextRoute()).isNull();
            assertThat(response.rows()).hasSize(SCREEN_ROWS);
        }

        @Test
        @DisplayName("no selection at all simply rebuilds the page")
        void rebuildsThePageWithNoSelection() {
            final UserOutcome response = serviceFor(repositoryOf(25))
                    .listUsers(listRequest(KeyAction.ENTER, null, "USER0001", "USER0010",
                            List.of()));

            assertThat(response.nextRoute()).isNull();
            assertThat(response.rows()).hasSize(SCREEN_ROWS);
        }
    }

    @Nested
    @DisplayName("Cross-cutting contracts: the unmapped key, routing and the header")
    class CrossCuttingContracts {

        @Test
        @DisplayName("the unmapped-key text is fifty encoded bytes and is never trimmed, on all four screens")
        void emitsTheFixedWidthUnmappedKeyText() {
            final FakeRepository repository = new FakeRepository();

            final UserOutcome list = serviceFor(repository)
                    .listUsers(listRequest(KeyAction.PFK09, null, null, null, List.of()));
            final UserOutcome add = serviceFor(repository)
                    .addUser(recordRequest("A", "B", "C", null, "U", KeyAction.PFK11));
            final UserOutcome update = serviceFor(repository)
                    .updateUser(recordRequest("A", "B", "C", null, "U", KeyAction.CLEAR));
            final UserOutcome delete = serviceFor(repository)
                    .deleteUser(recordRequest("A", "B", "C", null, "U", KeyAction.PA1));

            for (final UserOutcome response : List.of(list, add, update, delete)) {
                assertThat(response.message().getBytes(StandardCharsets.UTF_8))
                        .hasSize(COMMON_MESSAGE_WIDTH);
                assertThat(response.message()).endsWith(" ");
                assertThat(response.generalError()).isTrue();
            }
            assertThat(list.focusScreenFieldId()).isEqualTo(FIELD_LIST_USER_ID);
            // The one place the catalogue is the contract under test rather than the oracle: the
            // service must emit the catalogue value itself, untouched.
            assertThat(list.message()).isEqualTo(messageCatalogService.invalidKeyMessage());
        }

        @Test
        @DisplayName("a turn carrying no prior navigation state routes to sign-on on all four operations")
        void routesToSignOnWithoutPriorState() {
            final FakeRepository repository = new FakeRepository();
            final UserCommand bare = new UserCommand(null, null, null, null, null, null, List.of(),
                    null, null, null, KeyAction.ENTER, ScreenNavigationState.empty());

            assertThat(serviceFor(repository).listUsers(bare).nextRoute()).isEqualTo(ROUTE_SIGN_ON);
            assertThat(serviceFor(repository).addUser(bare).nextRoute()).isEqualTo(ROUTE_SIGN_ON);
            assertThat(serviceFor(repository).updateUser(bare).nextRoute()).isEqualTo(ROUTE_SIGN_ON);
            assertThat(serviceFor(repository).deleteUser(bare).nextRoute()).isEqualTo(ROUTE_SIGN_ON);
        }

        @Test
        @DisplayName("the exit key on the list screen routes to the administrative menu")
        void routesToTheAdministrativeMenu() {
            assertThat(serviceFor(repositoryOf(3)).listUsers(listRequest(KeyAction.PFK03, null, null,
                    null, List.of())).nextRoute()).isEqualTo(ROUTE_ADMIN_MENU);
        }

        @Test
        @DisplayName("the header is rendered from the injected clock and names the screen's own identity")
        void populatesTheHeader() {
            final FakeRepository repository = new FakeRepository();
            seed(repository, "HDRUSR01", "KATE", "LYNN", "U", FILLER_DIGEST);

            final UserOutcome update = serviceFor(repository)
                    .updateUser(recordRequest("HDRUSR01", null, null, null, null, KeyAction.ENTER));
            assertThat(update.currentDate()).isEqualTo("03/07/24");
            assertThat(update.currentTime()).isEqualTo("14:25:36");
            assertThat(update.transactionName()).isEqualTo("CU02");
            assertThat(update.programName()).isEqualTo("COUSR02C");
            assertThat(update.title01()).hasSize(40);
            assertThat(update.title02()).hasSize(40);

            final UserOutcome list = serviceFor(repository)
                    .listUsers(listRequest(KeyAction.ENTER, null, null, null, List.of()));
            assertThat(list.transactionName()).isEqualTo("CU00");
            assertThat(list.programName()).isEqualTo("COUSR00C");

            final UserOutcome add = serviceFor(repository)
                    .addUser(recordRequest("HDRUSR02", "GIVEN", "FAMILY", throwawayCredential(), "U",
                            KeyAction.ENTER));
            assertThat(add.transactionName()).isEqualTo("CU01");
            assertThat(add.programName()).isEqualTo("COUSR01C");

            final UserOutcome delete = serviceFor(repository)
                    .deleteUser(recordRequest("HDRUSR01", null, null, null, null, KeyAction.ENTER));
            assertThat(delete.transactionName()).isEqualTo("CU03");
            assertThat(delete.programName()).isEqualTo("COUSR03C");
        }

        @Test
        @DisplayName("every operation rejects an absent request rather than acting on one")
        void rejectsAnAbsentRequest() {
            final UserManagementService service = serviceFor(new FakeRepository());

            assertThatNullPointerException().isThrownBy(() -> service.listUsers(null));
            assertThatNullPointerException().isThrownBy(() -> service.addUser(null));
            assertThatNullPointerException().isThrownBy(() -> service.updateUser(null));
            assertThatNullPointerException().isThrownBy(() -> service.deleteUser(null));
        }

        @Test
        @DisplayName("every collaborator is mandatory, so no partially wired instance can exist")
        void requiresEveryCollaborator() {
            final FakeRepository repository = new FakeRepository();
            final RecordWriter recordWriter = recordWriterFor(repository);

            assertThatNullPointerException().isThrownBy(() -> new UserManagementService(null,
                    messageCatalogService, navigationService, PAGE_TOKEN_SERVICE,
                    new OnlineTransactionBoundary(), recordWriter, ENCODER,
                    FIXED_CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new UserManagementService(repository,
                    null, navigationService, PAGE_TOKEN_SERVICE, new OnlineTransactionBoundary(),
                    recordWriter, ENCODER, FIXED_CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new UserManagementService(repository,
                    messageCatalogService, null, PAGE_TOKEN_SERVICE,
                    new OnlineTransactionBoundary(), recordWriter, ENCODER, FIXED_CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new UserManagementService(repository,
                    messageCatalogService, navigationService, null,
                    new OnlineTransactionBoundary(), recordWriter, ENCODER, FIXED_CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new UserManagementService(repository,
                    messageCatalogService, navigationService, PAGE_TOKEN_SERVICE, null,
                    recordWriter, ENCODER, FIXED_CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new UserManagementService(repository,
                    messageCatalogService, navigationService, PAGE_TOKEN_SERVICE,
                    new OnlineTransactionBoundary(), null, ENCODER, FIXED_CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new UserManagementService(repository,
                    messageCatalogService, navigationService, PAGE_TOKEN_SERVICE,
                    new OnlineTransactionBoundary(), recordWriter, null, FIXED_CLOCK));
            assertThatNullPointerException().isThrownBy(() -> new UserManagementService(repository,
                    messageCatalogService, navigationService, PAGE_TOKEN_SERVICE,
                    new OnlineTransactionBoundary(), recordWriter, ENCODER, null));
        }

        @Test
        @DisplayName("a first presentation of the list screen still builds its first page")
        void buildsTheFirstPageOnAFirstEntry() {
            final UserOutcome response = serviceFor(repositoryOf(12))
                    .listUsers(new UserCommand(null, null, null, null, null, null, List.of(), null,
                            null, null, KeyAction.ENTER, firstEntry()));

            assertThat(response.rows()).hasSize(SCREEN_ROWS);
            assertThat(response.navigationContext().reEntry()).isTrue();
        }
    }

    /**
     * The arms the four members take when the store refuses them, when an anchor points nowhere, and
     * when the decoration gate is shut. Each of these is a real path through a legacy failure clause
     * rather than a defensive afterthought, so each is driven through the public operation that
     * reaches it.
     */
    @Nested
    @DisplayName("Legacy failure clauses and anchor boundaries")
    class FailureArmTests {

        @Test
        @DisplayName("a blank bottom anchor on the forward key is past the end, so the page stands")
        void treatsABlankBottomAnchorAsPastTheEnd() {
            final UserOutcome response = serviceFor(repositoryOf(25))
                    .listUsers(listRequest(KeyAction.PFK08, null, null, "  ", List.of()));

            assertThat(response.message()).isEqualTo(MSG_LIST_ALREADY_AT_BOTTOM);
            assertThat(response.generalError()).isFalse();
            assertThat(response.rows()).isEmpty();
        }

        @Test
        @DisplayName("a store that refuses the positioning read reports the lookup failure")
        void reportsAPositioningFailure() {
            final FakeRepository repository = repositoryOf(25);
            repository.refuse("findAllProjectedBy");

            final UserOutcome response = serviceFor(repository)
                    .listUsers(listRequest(KeyAction.ENTER, null, null, null, List.of()));

            assertThat(response.generalError()).isTrue();
            assertThat(response.message()).isEqualTo(MSG_LIST_UNABLE_TO_LOOKUP_USER);
            assertThat(response.rows()).isEmpty();
            assertBoundedFailureDiagnostics(1, "User list positioning failed");
        }

        @Test
        @DisplayName("a store that refuses a row read after positioning reports the same failure")
        void reportsAReadFailureAfterPositioning() {
            final FakeRepository repository = repositoryOf(25);
            // The inclusive primary-key seek positions the browse and the forward range read supplies
            // every row after it, so refusing the range read alone fails a row read rather than the
            // positioning that preceded it.
            repository.refuse("findBySecUsrIdGreaterThanOrderBySecUsrIdAsc");

            final UserOutcome response = serviceFor(repository)
                    .listUsers(listRequest(KeyAction.ENTER, "USER0001", null, null, List.of()));

            assertThat(response.generalError()).isTrue();
            assertThat(response.message()).isEqualTo(MSG_LIST_UNABLE_TO_LOOKUP_USER);
            assertBoundedFailureDiagnostics(1, "User list read failed");
        }

        @Test
        @DisplayName("a selection whose page anchor no longer exists rebuilds instead of dispatching")
        void ignoresASelectionWhoseAnchorHasGone() {
            final UserOutcome response = serviceFor(repositoryOf(12))
                    .listUsers(listRequest(KeyAction.ENTER, null, "ZZZZZZZZ", null,
                            selectionAt(1, "U")));

            assertThat(response.generalError()).isTrue();
            assertThat(response.message()).isEqualTo(MSG_LIST_UNABLE_TO_LOOKUP_USER);
            assertThat(response.nextRoute()).isNull();
        }

        /**
         * The save key reports one empty item, not every empty item.
         *
         * <p>The multi-way selection at {@code app/cbl/COUSR02C.cbl} L177-L212 stops at its first true
         * clause exactly as the add screen's does, and the identifier is the first clause here rather
         * than the given name. So a submission with four items blank reports the identifier alone.
         *
         * <p>The absent credential is deliberately passed as {@code null} rather than as spaces: on this
         * screen an absent item means unchanged and only a supplied-but-empty one is a fault, so a null
         * cannot fire its clause at all and would not fire it even if it were reached.</p>
         */
        @Test
        @DisplayName("the save key reports only the first empty item, which is the identifier")
        void reportsOnlyTheFirstEmptyItemOnTheSaveKey() {
            final UserOutcome response = serviceFor(repositoryOf(3))
                    .updateUser(recordRequest("  ", "  ", "  ", null, "  ", KeyAction.PFK05));

            assertThat(response.message()).isEqualTo(MSG_USER_ID_EMPTY);
            assertThat(response.generalError()).isTrue();
            assertThat(response.fieldErrors()).singleElement()
                    .satisfies(error -> {
                        assertThat(error.message()).isEqualTo(MSG_USER_ID_EMPTY);
                        assertThat(error.state())
                                .isEqualTo(ValidationException.FieldState.MISSING);
                    });
        }

        /**
         * A supplied-but-empty credential is a fault, and it is reported alone once the earlier clauses
         * are satisfied.
         */
        @Test
        @DisplayName("a supplied but empty credential is the single reported item once the identifier "
                + "and the names are given")
        void aSuppliedButEmptyCredentialIsTheSingleReportedItem() {
            final UserOutcome response = serviceFor(repositoryOf(3))
                    .updateUser(recordRequest("USER0001", "GIVEN", "FAMILY", "  ", "U",
                            KeyAction.PFK05));

            assertThat(response.generalError()).isTrue();
            assertThat(response.fieldErrors()).singleElement()
                    .satisfies(error -> {
                        assertThat(error.field()).isEqualTo("password");
                        assertThat(error.state())
                                .isEqualTo(ValidationException.FieldState.MISSING);
                    });
        }

        @Test
        @DisplayName("an empty identifier on the delete screen is reported before any read")
        void reportsAnEmptyIdentifierOnTheDeleteScreen() {
            final FakeRepository repository = repositoryOf(3);

            final UserOutcome response = serviceFor(repository)
                    .deleteUser(recordRequest("   ", null, null, null, null, KeyAction.ENTER));

            assertThat(response.message()).isEqualTo(MSG_USER_ID_EMPTY);
            assertThat(response.generalError()).isTrue();
            assertThat(repository.rows).hasSize(3);
        }

        /**
         * The snapshot alone resolves the selection, so the cursor echoes are not needed for it.
         *
         * <p>The two cursor keys position a browse for paging; the marked row is resolved from the sealed
         * snapshot and from nothing else. Passing neither cursor demonstrates that: the selection still
         * resolves, which it could not do if the identifier were being recovered by re-reading the page
         * the cursors describe.</p>
         */
        @Test
        @DisplayName("the page token alone resolves a selection, with neither cursor echoed")
        void resolvesASelectionWithNoEchoedAnchor() {
            final FakeRepository repository = repositoryOf(12);
            final UserManagementService service = serviceFor(repository);
            final UserOutcome displayed = service.listUsers(
                    listRequest(KeyAction.ENTER, null, null, null, List.of()));

            final UserOutcome response = service.listUsers(listRequest(KeyAction.ENTER, null,
                    null, null, selectionAt(2, "U"), displayed.rowSnapshotToken()));

            assertThat(response.nextRoute()).isEqualTo(ROUTE_USER_UPDATE);
            assertThat(response.userId()).isEqualTo("USER0002");
        }

        /**
         * A marked row with no snapshot is refused rather than resolved by re-reading the page.
         *
         * <p>This is the closure of the selection race. The marker names a <em>position</em> on the page
         * the operator is looking at, and any insert or delete at or before that page's anchor shifts
         * every later row by one. Recovering the identifier by re-reading would therefore return whoever
         * now stands in the marked position, and the turn would open the update or delete screen against
         * them - and a delete is not recoverable.
         *
         * <p>So an absent snapshot takes the same arm an unopenable one takes: no dispatch, the page is
         * rebuilt, and the operator marks again against rows this server has just published. A client
         * that echoes the token it was given never sees this.</p>
         */
        @Test
        @DisplayName("a marked row with no page snapshot is refused and never dispatches")
        void refusesASelectionCarryingNoPageSnapshot() {
            final UserOutcome response = serviceFor(repositoryOf(12))
                    .listUsers(listRequest(KeyAction.ENTER, null, "USER0001", "USER0010",
                            selectionAt(2, "U")));

            assertThat(response.nextRoute())
                    .as("no dispatch: the marked position could not be resolved safely")
                    .isNull();
            assertThat(response.userId())
                    .as("no identifier was resolved, so none is carried to a next screen")
                    .isNotEqualTo("USER0002");
            assertThat(response.generalError())
                    .as("the operator is told the selection could not be resolved")
                    .isTrue();
            assertThat(response.message())
                    .as("and told it through the same text a tampered snapshot produces, because the "
                            + "two are the same failure: a marked row this server cannot vouch for")
                    .isEqualTo(MSG_LIST_UNABLE_TO_LOOKUP_USER);
        }

        /**
         * The refusal covers a blank token as well as a missing one, since neither seals anything.
         */
        @ParameterizedTest(name = "a snapshot of [{0}] is refused")
        @ValueSource(strings = {"", " ", "   "})
        @DisplayName("a blank page snapshot is refused on the same terms as an absent one")
        void refusesABlankPageSnapshot(final String blankToken) {
            final UserOutcome response = serviceFor(repositoryOf(12))
                    .listUsers(listRequest(KeyAction.ENTER, null, "USER0001", "USER0010",
                            selectionAt(2, "U"), blankToken));

            assertThat(response.nextRoute()).isNull();
        }

        /**
         * A turn that marks nothing needs no snapshot, so requiring one must not break ordinary paging.
         */
        @Test
        @DisplayName("a turn that marks no row needs no snapshot and pages normally")
        void aTurnMarkingNoRowNeedsNoSnapshot() {
            final UserOutcome response = serviceFor(repositoryOf(25))
                    .listUsers(listRequest(KeyAction.ENTER, null, null, null, List.of()));

            assertThat(response.rows()).hasSize(SCREEN_ROWS);
            assertThat(response.generalError()).isFalse();
            assertThat(response.rowSnapshotToken())
                    .as("and the page it returns carries the snapshot a later selection will need")
                    .isNotBlank();
        }

        @Test
        @DisplayName("an intervening insertion cannot move another user into the selected row")
        void resolvesTheSelectedIdentityFromTheFrozenSnapshot() {
            final FakeRepository repository = repositoryOf(12);
            final UserManagementService service = serviceFor(repository);
            final UserOutcome displayed = service.listUsers(
                    listRequest(KeyAction.ENTER, null, null, null, List.of()));
            seed(repository, "AAAA0001", "NEW", "EARLIER", "U", FILLER_DIGEST);

            final UserOutcome response = service.listUsers(listRequest(KeyAction.ENTER, null,
                    displayed.pageMetadata().previousCursorKey(),
                    displayed.pageMetadata().nextCursorKey(), selectionAt(2, "U"),
                    displayed.rowSnapshotToken()));

            assertThat(response.nextRoute()).isEqualTo(ROUTE_USER_UPDATE);
            assertThat(response.userId())
                    .as("row two must remain the identifier displayed before the insertion")
                    .isEqualTo("USER0002")
                    .isNotEqualTo("USER0001");
        }

        @Test
        @DisplayName("a tampered page token is refused without dispatching")
        void refusesATamperedPageToken() {
            final FakeRepository repository = repositoryOf(12);
            final UserManagementService service = serviceFor(repository);
            final UserOutcome displayed = service.listUsers(
                    listRequest(KeyAction.ENTER, null, null, null, List.of()));
            final String token = displayed.rowSnapshotToken();
            final int changedIndex = token.length() / 2;
            final char replacement = token.charAt(changedIndex) == 'A' ? 'B' : 'A';
            final String tampered = token.substring(0, changedIndex) + replacement
                    + token.substring(changedIndex + 1);

            final UserOutcome response = service.listUsers(listRequest(KeyAction.ENTER, null,
                    displayed.pageMetadata().previousCursorKey(),
                    displayed.pageMetadata().nextCursorKey(), selectionAt(1, "D"), tampered));

            assertThat(response.generalError()).isTrue();
            assertThat(response.message()).isEqualTo(MSG_LIST_UNABLE_TO_LOOKUP_USER);
            assertThat(response.nextRoute()).isNull();
        }

        @Test
        @DisplayName("a store that refuses the backward probe reports it instead of escaping")
        void reportsAProbeFailureOnTheBackwardKey() {
            final FakeRepository repository = repositoryOf(25);
            repository.refuse("findProjectedBySecUsrId");

            final UserOutcome response = serviceFor(repository)
                    .listUsers(listRequest(KeyAction.PFK07, null, "USER0011", null, List.of()));

            assertThat(response.message()).isEqualTo(MSG_LIST_UNABLE_TO_LOOKUP_USER);
            assertThat(response.generalError()).isTrue();
            assertThat(response.rows()).isEmpty();
            assertBoundedFailureDiagnostics(1, "User list anchor probe failed");
        }

        @Test
        @DisplayName("a store that refuses the forward probe reports it instead of escaping")
        void reportsAProbeFailureOnTheForwardKey() {
            final FakeRepository repository = repositoryOf(25);
            repository.refuse("findProjectedBySecUsrId");

            final UserOutcome response = serviceFor(repository)
                    .listUsers(listRequest(KeyAction.PFK08, null, null, "USER0010", List.of()));

            assertThat(response.message()).isEqualTo(MSG_LIST_UNABLE_TO_LOOKUP_USER);
            assertThat(response.generalError()).isTrue();
            assertBoundedFailureDiagnostics(1, "User list anchor probe failed");
        }

        @Test
        @DisplayName("a store that refuses the selection probe reports it instead of escaping")
        void reportsAProbeFailureWhileResolvingASelection() {
            final FakeRepository repository = repositoryOf(25);
            repository.refuse("findProjectedBySecUsrId");
            repository.refuse("findAllProjectedBy");

            final UserOutcome response = serviceFor(repository)
                    .listUsers(listRequest(KeyAction.ENTER, null, "USER0001", null,
                            selectionAt(3, "U")));

            assertThat(response.message()).isEqualTo(MSG_LIST_UNABLE_TO_LOOKUP_USER);
            assertThat(response.generalError()).isTrue();
            assertThat(response.nextRoute()).isNotEqualTo(ROUTE_USER_UPDATE);
        }

        @Test
        @DisplayName("a store that refuses the successor probe after positioning reports it the same way")
        void reportsARowProbeFailureOnTheForwardKey() {
            final FakeRepository repository = repositoryOf(25);
            // The anchor is found and its position counted; only the one-row read that asks whether a
            // further row follows refuses, which is the successor probe's own arm.
            repository.refuse("findBySecUsrIdGreaterThanOrderBySecUsrIdAsc");

            final UserOutcome response = serviceFor(repository)
                    .listUsers(listRequest(KeyAction.PFK08, null, null, "USER0010", List.of()));

            assertThat(response.message()).isEqualTo(MSG_LIST_UNABLE_TO_LOOKUP_USER);
            assertThat(response.generalError()).isTrue();
            assertBoundedFailureDiagnostics(1, "User list successor probe failed");
        }

        @Test
        @DisplayName("a store that refuses the page-counter aggregate reports it rather than paging "
                + "from a counter it could not derive")
        void reportsAPageCounterFailure() {
            final FakeRepository repository = repositoryOf(25);
            // The anchor is found; only the range count that turns its key into a page number refuses.
            // The counter is what decides whether there is a page before this one at all, so a refusal
            // here cannot be allowed to look like page one.
            repository.refuse("countBySecUsrIdLessThan");

            final UserOutcome response = serviceFor(repository)
                    .listUsers(listRequest(KeyAction.PFK07, null, "USER0011", null, List.of()));

            assertThat(response.message()).isEqualTo(MSG_LIST_UNABLE_TO_LOOKUP_USER);
            assertThat(response.generalError()).isTrue();
            assertThat(response.rows()).isEmpty();
            assertBoundedFailureDiagnostics(1, "User list page-counter probe failed");
        }

        @Test
        @DisplayName("a store that refuses the backward range read reports a read failure, not an "
                + "end of sequence")
        void reportsABackwardReadFailure() {
            final FakeRepository repository = repositoryOf(25);
            // The anchor is found and counted, so the walk starts; the descending range read that
            // supplies every row below the anchor is the one that refuses. The distinction matters:
            // end of sequence emits the reached-the-top text and leaves the error flag clear, while a
            // refusal raises it, and a browse that confused the two would present a truncated page as
            // if it were the top of the table.
            repository.refuse("findBySecUsrIdLessThanOrderBySecUsrIdDesc");

            final UserOutcome response = serviceFor(repository)
                    .listUsers(listRequest(KeyAction.PFK07, null, "USER0011", null, List.of()));

            assertThat(response.message()).isEqualTo(MSG_LIST_UNABLE_TO_LOOKUP_USER);
            assertThat(response.generalError()).isTrue();
            assertBoundedFailureDiagnostics(1, "User list read failed");
        }

        @Test
        @DisplayName("a handed-over selection whose row has since gone reports it as not found")
        void reportsAHandedOverSelectionThatHasGone() {
            final UserOutcome response = serviceFor(new FakeRepository()).updateUser(
                    recordRequest("GONE0001", null, null, null, null, KeyAction.ENTER, firstEntry()));

            assertThat(response.message()).isEqualTo(MSG_USER_ID_NOT_FOUND);
            assertThat(response.generalError()).isTrue();
            assertThat(response.fieldErrors())
                    .extracting(ValidationException.FieldError::message)
                    .containsExactly(MSG_USER_ID_NOT_FOUND);
        }

        @Test
        @DisplayName("the same handover on the delete screen reports it the same way")
        void reportsAHandedOverDeleteSelectionThatHasGone() {
            final UserOutcome response = serviceFor(new FakeRepository()).deleteUser(
                    recordRequest("GONE0002", null, null, null, null, KeyAction.ENTER, firstEntry()));

            assertThat(response.message()).isEqualTo(MSG_USER_ID_NOT_FOUND);
            assertThat(response.generalError()).isTrue();
        }
    }

    // ==============================================================================================
    // The groups below drive the same service through Mockito doubles, because three contracts can
    // only be proven by observing a collaborator rather than its answers: what window the paged read
    // asks for, that a credential-free save never reaches the encoder at all, and that no operation
    // touches the store on a path the legacy does not read on.
    // ==============================================================================================

    @Nested
    @DisplayName("CU01 add, observed at the writer: what is handed to the store is a digest")
    class AddObservedAtTheWriter {

        @Test
        @DisplayName("the record handed to the writer carries a sixty-character digest that is not the "
                + "submitted value, and the store is never read or written")
        void handsTheWriterADigestAndNeverTouchesTheStore() {
            final String submitted = foldStableThrowawayCredential();
            final String different = foldStableThrowawayCredential() + "Z";
            when(mockRecordWriter.insertIndependently(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserOutcome response = serviceWithMocks().addUser(
                    recordRequest("NEWUSR11", "GIVEN", "FAMILY", submitted, ROLE_CODE_USER,
                            KeyAction.ENTER));

            final ArgumentCaptor<UserSecurity> written =
                    ArgumentCaptor.forClass(UserSecurity.class);
            verify(mockRecordWriter).insertIndependently(written.capture());
            final String stored = written.getValue().credentialDigest();
            assertThat(stored).hasSize(DIGEST_LENGTH);
            assertThat(TestDataFactory.hasStoredDigestShape(stored))
                    .as("the digest must carry the module's own cost factor, not merely be long enough")
                    .isTrue();
            // Cleartext comparison is impossible by construction, asserted under every comparison a
            // careless implementation might reach for.
            assertThat(stored.equals(submitted)).isFalse();
            assertThat(stored.equalsIgnoreCase(submitted)).isFalse();
            assertThat(stored.contains(submitted)).isFalse();
            // The permitted oracle: a digest cannot be predicted, so verification is the only test of
            // whether it corresponds to an input. The credential is fold-stable, so the value submitted
            // is the value the encoder received and no production fold is needed to know that.
            assertThat(ENCODER.matches(submitted, stored)).isTrue();
            assertThat(ENCODER.matches(different, stored))
                    .as("a digest that accepted anything would make the acceptance check worthless")
                    .isFalse();
            assertThat(response.actionSucceeded()).isTrue();
            assertThat(response.message()).isEqualTo("User NEWUSR11 has been added ...");
            assertThat(String.valueOf(response)).doesNotContain(stored).doesNotContain(submitted);
            assertThat(noCapturedLogContains(stored)).isTrue();
            assertThat(noCapturedLogContains(submitted)).isTrue();
            verifyNoInteractions(mockRepository);
            verifyNoMoreInteractions(mockRecordWriter);
        }

        @Test
        @DisplayName("an existing identifier is refused with the exact duplicate text, nothing is "
                + "overwritten and the store is never consulted")
        void refusesADuplicateWithoutConsultingTheStore() {
            when(mockRecordWriter.insertIndependently(any(UserSecurity.class)))
                    .thenThrow(new DuplicateKeyException("duplicate test identifier"));

            final UserOutcome response = serviceWithMocks().addUser(
                    recordRequest("DUPUSR11", "GIVEN", "FAMILY", foldStableThrowawayCredential(),
                            ROLE_CODE_USER, KeyAction.ENTER));

            assertThat(response.message()).isEqualTo(MSG_ADD_USER_ID_ALREADY_EXIST);
            assertThat(response.message()).isEqualTo(response.message().stripTrailing());
            assertThat(response.generalError()).isTrue();
            assertThat(response.actionSucceeded()).isFalse();
            assertThat(response.focusScreenFieldId()).isEqualTo(FIELD_ADD_USER_ID);
            assertThat(response.fieldErrors()).singleElement()
                    .satisfies(finding -> {
                        assertThat(finding.state())
                                .isEqualTo(ValidationException.FieldState.INVALID);
                        assertThat(finding.field()).isEqualTo(PROPERTY_USER_ID);
                        assertThat(finding.bmsFieldId()).isEqualTo(FIELD_ADD_USER_ID);
                        assertThat(finding.message()).isEqualTo(MSG_ADD_USER_ID_ALREADY_EXIST);
                    });
            verify(mockRecordWriter).insertIndependently(any(UserSecurity.class));
            verifyNoInteractions(mockRepository);
            verifyNoMoreInteractions(mockRecordWriter);
        }

        @Test
        @DisplayName("a writer that refuses for any other reason takes the default arm and stores "
                + "nothing")
        void reportsAFailedWriteWithoutStoringAnything() {
            when(mockRecordWriter.insertIndependently(any(UserSecurity.class)))
                    .thenThrow(new IllegalStateException("store unavailable"));

            final UserOutcome response = serviceWithMocks().addUser(
                    recordRequest("FAILUS11", "GIVEN", "FAMILY", foldStableThrowawayCredential(),
                            ROLE_CODE_USER, KeyAction.ENTER));

            assertThat(response.message()).isEqualTo(MSG_ADD_UNABLE_TO_ADD_USER);
            assertThat(response.generalError()).isTrue();
            assertThat(response.focusScreenFieldId()).isEqualTo(FIELD_FIRST_NAME);
            verify(mockRecordWriter).insertIndependently(any(UserSecurity.class));
            verifyNoInteractions(mockRepository);
            verifyNoMoreInteractions(mockRecordWriter);
        }
    }

    @Nested
    @DisplayName("CU02 update, observed at the encoder: an unchanged credential is never re-hashed")
    class UpdateObservedAtTheEncoder {

        @Test
        @DisplayName("updating without supplying a credential leaves the stored digest byte-identical "
                + "and never reaches the encoder at all")
        void carriesTheDigestForwardWithoutReachingTheEncoder() {
            // THE DECISIVE ASSERTION OF THIS CLASS. A digest-of-a-digest would be sixty characters
            // long, structurally valid, and would lock the identity out of every future sign-on. No
            // other assertion here detects it: the record saves, the screen reports success, and the
            // stored value still looks exactly like a credential digest.
            final String seededDigest = TestDataFactory.digestOfFixtureCredentialWindow();
            final UserSecurity held = identityHolding("UPDMCK01", seededDigest, ROLE_CODE_USER);
            when(mockRepository.findByIdForUpdate("UPDMCK01")).thenReturn(Optional.of(held));
            when(mockRepository.save(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserOutcome response = serviceWithMocks().updateUser(
                    recordRequest("UPDMCK01", "CHANGED", "ALTERED", null, ROLE_CODE_ADMIN,
                            KeyAction.PFK05));

            final ArgumentCaptor<UserSecurity> saved = ArgumentCaptor.forClass(UserSecurity.class);
            verify(mockRepository).save(saved.capture());
            assertThat(saved.getValue().credentialDigest())
                    .as("byte-identical, not merely still valid: a re-hash would also be valid")
                    .isEqualTo(seededDigest);
            verify(encoderSpy, never()).encode(any());
            verify(encoderSpy, never()).matches(any(), any());
            assertThat(saved.getValue().getSecUsrFname()).isEqualTo("CHANGED");
            assertThat(saved.getValue().getSecUsrLname()).isEqualTo("ALTERED");
            assertThat(saved.getValue().getSecUsrType()).isEqualTo(ROLE_CODE_ADMIN);
            assertThat(response.actionSucceeded()).isTrue();
            assertThat(response.message()).isEqualTo("User UPDMCK01 has been updated ...");
            assertThat(noCapturedLogContains(seededDigest)).isTrue();
            verify(mockRepository).findByIdForUpdate("UPDMCK01");
            verifyNoMoreInteractions(mockRepository);
            verify(mockRecordWriter).flush();
            verifyNoMoreInteractions(mockRecordWriter);
            // The complement of the byte-identity assertion: the carried digest is still the one the
            // seeded value opens, so carrying it forward preserved a working credential rather than an
            // arbitrary sixty characters.
            assertThat(TestDataFactory.digestAcceptsFixtureCredentialWindow(ENCODER,
                    saved.getValue().credentialDigest())).isTrue();
        }

        @Test
        @DisplayName("supplying a genuinely different credential produces a different sixty-character "
                + "digest, hashed exactly once")
        void hashesAReplacementExactlyOnce() {
            final String previous = foldStableThrowawayCredential();
            final String replacement = foldStableThrowawayCredential() + "Q";
            final String digestBefore = ENCODER.encode(previous);
            final UserSecurity held = identityHolding("UPDMCK02", digestBefore, ROLE_CODE_USER);
            when(mockRepository.findByIdForUpdate("UPDMCK02")).thenReturn(Optional.of(held));
            when(mockRepository.save(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserOutcome response = serviceWithMocks().updateUser(
                    recordRequest("UPDMCK02", SEEDED_ADMIN_FIRST_NAME, SEEDED_ADMIN_LAST_NAME,
                            replacement, ROLE_CODE_USER, KeyAction.PFK05));

            final ArgumentCaptor<UserSecurity> saved = ArgumentCaptor.forClass(UserSecurity.class);
            verify(mockRepository).save(saved.capture());
            final String digestAfter = saved.getValue().credentialDigest();
            assertThat(digestAfter).hasSize(DIGEST_LENGTH).isNotEqualTo(digestBefore);
            assertThat(ENCODER.matches(replacement, digestAfter)).isTrue();
            assertThat(ENCODER.matches(previous, digestAfter))
                    .as("the previous credential must stop working the moment it is replaced")
                    .isFalse();
            verify(encoderSpy, times(1)).encode(any());
            verify(encoderSpy, times(1)).matches(any(), any());
            assertThat(response.actionSucceeded()).isTrue();
            assertThat(String.valueOf(response)).doesNotContain(digestAfter)
                    .doesNotContain(replacement);
            assertThat(noCapturedLogContains(digestAfter)).isTrue();
            assertThat(noCapturedLogContains(replacement)).isTrue();
            verify(mockRepository).findByIdForUpdate("UPDMCK02");
            verifyNoMoreInteractions(mockRepository);
            verify(mockRecordWriter).flush();
        }

        @Test
        @DisplayName("re-typing the credential already stored is not a change, so nothing is hashed "
                + "and nothing is saved")
        void treatsAReTypedCredentialAsUnchanged() {
            final String credential = foldStableThrowawayCredential();
            final String digest = ENCODER.encode(credential);
            final UserSecurity held = identityHolding("UPDMCK03", digest, ROLE_CODE_ADMIN);
            when(mockRepository.findByIdForUpdate("UPDMCK03")).thenReturn(Optional.of(held));

            final UserOutcome response = serviceWithMocks().updateUser(
                    recordRequest("UPDMCK03", SEEDED_ADMIN_FIRST_NAME, SEEDED_ADMIN_LAST_NAME,
                            credential, ROLE_CODE_ADMIN, KeyAction.PFK05));

            assertThat(response.message()).isEqualTo(MSG_UPDATE_NO_CHANGE);
            assertThat(response.actionSucceeded()).isFalse();
            assertThat(response.generalError())
                    .as("the source emits this text without raising the error flag")
                    .isFalse();
            assertThat(SensitiveValues.fingerprint(held.credentialDigest())).isEqualTo(SensitiveValues.fingerprint(digest));
            verify(encoderSpy, never()).encode(any());
            verify(encoderSpy, times(1)).matches(any(), any());
            verify(mockRepository).findByIdForUpdate("UPDMCK03");
            verify(mockRepository, never()).save(any(UserSecurity.class));
            verifyNoMoreInteractions(mockRepository);
            verifyNoInteractions(mockRecordWriter);
        }
    }

    @Nested
    @DisplayName("The seeded credential is exercised at run time and never written down")
    class SeededCredentialHandling {

        @Test
        @DisplayName("the seeded credential, read from the class-path fixture at run time, is "
                + "recognised as unchanged and no assertion here discloses it")
        void recognisesTheSeededCredentialWithoutRevealingIt() {
            // The value arrives by offset out of a known column of a known record, is used, and is
            // overwritten. It is never compared against a literal, never logged and never named. The
            // stored digest is taken over the value as the terminal would have delivered it, because
            // that is the form the write paths store and the sign-on path verifies.
            final char[] window = TestDataFactory.fixtureCredentialWindow();
            final String asAnOperatorKeysIt;
            try {
                asAnOperatorKeysIt = new String(window);
            } finally {
                Arrays.fill(window, ' ');
            }
            final String asTheTerminalDelivers = foldedByThisClass(asAnOperatorKeysIt);
            final String seededDigest = ENCODER.encode(asTheTerminalDelivers);
            final UserSecurity held =
                    identityHolding(SEEDED_ADMIN_ID, seededDigest, ROLE_CODE_ADMIN);
            when(mockRepository.findByIdForUpdate(SEEDED_ADMIN_ID)).thenReturn(Optional.of(held));

            final UserOutcome response = serviceWithMocks().updateUser(
                    recordRequest(SEEDED_ADMIN_ID, SEEDED_ADMIN_FIRST_NAME, SEEDED_ADMIN_LAST_NAME,
                            asAnOperatorKeysIt, ROLE_CODE_ADMIN, KeyAction.PFK05));

            // Boolean outcomes only, every one of them.
            assertThat(response.message()).isEqualTo(MSG_UPDATE_NO_CHANGE);
            assertThat(SensitiveValues.fingerprint(held.credentialDigest())).isEqualTo(SensitiveValues.fingerprint(seededDigest));
            verify(encoderSpy, never()).encode(any());
            verify(mockRepository).findByIdForUpdate(SEEDED_ADMIN_ID);
            verify(mockRepository, never()).save(any(UserSecurity.class));
            verifyNoMoreInteractions(mockRepository);
            verifyNoInteractions(mockRecordWriter);
            assertThat(ENCODER.matches(asTheTerminalDelivers, seededDigest))
                    .as("the digest the identity holds is genuinely the seeded credential's")
                    .isTrue();
            assertThat(TestDataFactory.digestRefusesOtherValues(ENCODER, seededDigest))
                    .as("and it refuses a value it was not derived from, so the check discriminates")
                    .isTrue();
            assertThat(noCapturedLogContains(seededDigest)).isTrue();
            assertThat(noCapturedLogContains(asAnOperatorKeysIt)).isTrue();
            assertThat(noCapturedLogContains(asTheTerminalDelivers)).isTrue();
            assertThat(String.valueOf(response)).doesNotContain(seededDigest)
                    .doesNotContain(asAnOperatorKeysIt);
        }

        @Test
        @DisplayName("a digest of the seeded credential is sixty characters at the module's cost "
                + "factor, and two of them differ because each carries its own salt")
        void seededDigestsAreDistinctDespiteOneSharedInput() {
            // The property the credential migration relies on: ten records of one shared value carry
            // ten different digests, so replacing any one of them is observable. Two are enough to
            // prove it and cost two hashes rather than ten.
            final String first = TestDataFactory.digestOfFixtureCredentialWindow();
            final String second = TestDataFactory.digestOfFixtureCredentialWindow();

            assertThat(first).hasSize(TestDataFactory.BCRYPT_DIGEST_LENGTH);
            assertThat(second).hasSize(TestDataFactory.BCRYPT_DIGEST_LENGTH);
            assertThat(first).isNotEqualTo(second);
            assertThat(TestDataFactory.hasStoredDigestShape(first)).isTrue();
            assertThat(TestDataFactory.hasStoredDigestShape(second)).isTrue();
            assertThat(TestDataFactory.digestAcceptsFixtureCredentialWindow(ENCODER, first)).isTrue();
            assertThat(TestDataFactory.digestAcceptsFixtureCredentialWindow(ENCODER, second)).isTrue();
            assertThat(TestDataFactory.digestRefusesOtherValues(ENCODER, first)).isTrue();
            assertThat(TestDataFactory.digestRefusesOtherValues(ENCODER, second)).isTrue();
            // The stored column is sixty characters wide for exactly this reason, and it is the one
            // intentional width change in the whole schema: the legacy field held eight.
            assertThat(TestDataFactory.BCRYPT_DIGEST_LENGTH).isEqualTo(DIGEST_LENGTH);
        }
    }

    @Nested
    @DisplayName("CU00 list, observed at the paged read: the window asked for and the order presented")
    class ListObservedAtThePagedRead {

        @Test
        @DisplayName("a full page presents exactly ten rows ascending, from one positioning read that "
                + "asks for the ten plus the one the source reads past the page")
        void asksForTenPlusOneAndPresentsExactlyTen() {
            final List<String> window = new ArrayList<>(FIRST_PAGE_IDS);
            window.add("USER0011");
            when(mockRepository.findAllProjectedBy(any(Pageable.class)))
                    .thenReturn(sliceOf(projectionsOf(window)));

            final UserOutcome response = serviceWithMocks().listUsers(
                    listRequest(KeyAction.ENTER, null, null, null, List.of()));

            final ArgumentCaptor<Pageable> asked = ArgumentCaptor.forClass(Pageable.class);
            verify(mockRepository).findAllProjectedBy(asked.capture());
            assertThat(asked.getValue().getPageNumber()).isZero();
            assertThat(asked.getValue().getPageSize())
                    .as("ten screen rows plus the one extra read that answers the next-page question")
                    .isEqualTo(POSITIONING_WINDOW_ROWS);
            final Sort.Order order = asked.getValue().getSort().getOrderFor(BROWSE_SORT_PROPERTY);
            assertThat(order).isNotNull();
            assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
            assertThat(userIdsOf(response)).hasSize(SCREEN_ROWS)
                    .containsExactlyElementsOf(FIRST_PAGE_IDS);
            assertThat(response.rows()).doesNotContainNull();
            assertThat(response.pageMetadata().pageSize()).isEqualTo(SCREEN_ROWS);
            assertThat(response.pageMetadata().direction())
                    .isEqualTo(BrowseWindow.PagingDirection.FORWARD);
            assertThat(response.pageMetadata().hasMorePages()).isTrue();
            assertThat(response.pageMetadata().hasPreviousPages()).isFalse();
            assertThat(response.pageMetadata().previousCursorKey()).isEqualTo("USER0001");
            assertThat(response.pageMetadata().nextCursorKey()).isEqualTo("USER0010");
            assertThat(response.pageMetadata().displayedPageNumber()).isEqualTo(DISPLAYED_PAGE_ONE);
            verifyNoMoreInteractions(mockRepository);
            verifyNoInteractions(mockRecordWriter);
            verifyNoInteractions(mockNavigation);
        }

        @Test
        @DisplayName("a partial page presents only the rows that exist, with no blank filler and no "
                + "absent entry")
        void presentsAPartialPageWithoutFiller() {
            final List<String> present = List.of("USER0001", "USER0002", "USER0003", "USER0004");
            when(mockRepository.findAllProjectedBy(any(Pageable.class)))
                    .thenReturn(sliceOf(projectionsOf(present)));

            final UserOutcome response = serviceWithMocks().listUsers(
                    listRequest(KeyAction.ENTER, null, null, null, List.of()));

            assertThat(userIdsOf(response)).hasSize(present.size())
                    .containsExactlyElementsOf(present);
            assertThat(response.rows()).doesNotContainNull();
            assertThat(response.pageMetadata().pageSize())
                    .as("the window is the screen's ten rows even on a short page - the map declares ten "
                            + "row families and the clearing paragraph blanks the six that did not fill, "
                            + "so reporting four would describe the records rather than the screen and "
                            + "would give this member a different meaning from the one the card and "
                            + "transaction lists publish (DL-363)")
                    .isEqualTo(SCREEN_ROWS)
                    .isNotEqualTo(present.size());
            assertThat(response.rows())
                    .as("and the count of rows that did arrive is the row list's own length, which is "
                            + "where a client reads it")
                    .hasSize(present.size());
            assertThat(response.pageMetadata().hasMorePages()).isFalse();
            assertThat(response.pageMetadata().hasPreviousPages()).isFalse();
            assertThat(response.pageMetadata().nextCursorKey())
                    .as("the tenth slot never filled, so the page retains no last-row cursor")
                    .isNull();
            assertThat(response.pageMetadata().displayedPageNumber()).isEqualTo(DISPLAYED_PAGE_ONE);
            assertThat(response.message()).isEqualTo(MSG_LIST_REACHED_BOTTOM);
            verify(mockRepository).findAllProjectedBy(any(Pageable.class));
            verifyNoMoreInteractions(mockRepository);
        }

        @Test
        @DisplayName("an empty table - the genuine state before the credential seed is applied - "
                + "presents no rows, reports no further page and raises nothing")
        void presentsAnEmptyTableWithoutRaising() {
            when(mockRepository.findAllProjectedBy(any(Pageable.class)))
                    .thenReturn(sliceOf(List.of()));

            final UserOutcome response = serviceWithMocks().listUsers(
                    listRequest(KeyAction.ENTER, null, null, null, List.of()));

            assertThat(response.rows()).isEmpty();
            assertThat(response.hasRows()).isFalse();
            assertThat(response.rowSnapshotToken()).isNull();
            assertThat(response.pageMetadata().pageSize())
                    .as("an empty page is still the ten-row screen, and reporting nought would both "
                            + "contradict the positive bound the paging contract declares and describe a "
                            + "window no screen has (DL-363)")
                    .isEqualTo(SCREEN_ROWS)
                    .isPositive();
            assertThat(response.pageMetadata().hasMorePages()).isFalse();
            assertThat(response.pageMetadata().hasPreviousPages()).isFalse();
            assertThat(response.message()).isEqualTo(MSG_LIST_AT_TOP);
            assertThat(response.generalError())
                    .as("the end-of-sequence arm emits its text without raising the flag")
                    .isFalse();
            verify(mockRepository).findAllProjectedBy(any(Pageable.class));
            verifyNoMoreInteractions(mockRepository);
        }

        @Test
        @DisplayName("a backward page reads descending and presents ascending, in that exact order")
        void readsDescendingAndPresentsAscending() {
            when(mockRepository.findProjectedBySecUsrId(BACKWARD_ANCHOR_ID))
                    .thenReturn(Optional.of(projectionFor(12)));
            when(mockRepository.countBySecUsrIdLessThan(BACKWARD_ANCHOR_ID)).thenReturn(11L);
            when(mockRepository.findBySecUsrIdLessThanOrderBySecUsrIdDesc(
                    eq(BACKWARD_ANCHOR_ID), any(Limit.class)))
                    .thenReturn(projectionsOf(BACKWARD_READ_IDS_DESCENDING));

            final UserOutcome response = serviceWithMocks().listUsers(
                    listRequest(KeyAction.PFK07, null, BACKWARD_ANCHOR_ID, null, List.of()));

            // Ordered, never membership. The legacy fills its slots from the bottom upward on a
            // backward walk, so a translation that returned the read order would present the page
            // inverted and a set comparison would call that correct.
            assertThat(userIdsOf(response)).containsExactlyElementsOf(BACKWARD_PAGE_IDS_ASCENDING);
            assertThat(userIdsOf(response))
                    .as("and it is not the order the rows arrived in")
                    .isNotEqualTo(BACKWARD_READ_IDS_DESCENDING);
            final ArgumentCaptor<Limit> asked = ArgumentCaptor.forClass(Limit.class);
            verify(mockRepository).findBySecUsrIdLessThanOrderBySecUsrIdDesc(
                    eq(BACKWARD_ANCHOR_ID), asked.capture());
            assertThat(asked.getValue().max()).isEqualTo(POSITIONING_WINDOW_ROWS);
            // The direction is carried by which finder is asked, because the ordering is declared in
            // the derived name rather than passed as a sort. So the descending finder answering and the
            // ascending one never being asked is the whole of the direction contract.
            verify(mockRepository, never()).findBySecUsrIdGreaterThanOrderBySecUsrIdAsc(
                    any(), any(Limit.class));
            verify(mockRepository, never()).findAllProjectedBy(any(Pageable.class));
            verify(mockRepository, times(2)).findProjectedBySecUsrId(BACKWARD_ANCHOR_ID);
            verify(mockRepository).countBySecUsrIdLessThan(BACKWARD_ANCHOR_ID);
            verifyNoMoreInteractions(mockRepository);
            assertThat(response.pageMetadata().direction())
                    .isEqualTo(BrowseWindow.PagingDirection.BACKWARD);
            assertThat(response.pageMetadata().pageSize()).isEqualTo(SCREEN_ROWS);
            assertThat(response.pageMetadata().previousCursorKey()).isEqualTo("USER0002");
            assertThat(response.pageMetadata().nextCursorKey()).isEqualTo("USER0011");
            assertThat(response.pageMetadata().hasMorePages()).isTrue();
            assertThat(response.pageMetadata().displayedPageNumber()).isEqualTo(DISPLAYED_PAGE_ONE);
            assertThat(response.preserveDisplayedPage())
                    .as("a key that actually moved a page asks for a rebuild, not a retention")
                    .isFalse();
        }

        @Test
        @DisplayName("a backward walk that finds fewer rows than the counter promised still presents "
                + "ascending and leaves the earlier slots empty rather than padding them")
        void presentsAShortBackwardPageAscendingWithoutPadding() {
            // A store may legitimately answer this way: the count is taken first and rows below the
            // anchor may be removed before the range read runs. The slot rule is what is under test -
            // rows land in the highest slots and the page is read in slot order.
            final List<String> arriving = List.of("USER0004", "USER0003", "USER0002", "USER0001");
            when(mockRepository.findProjectedBySecUsrId(BACKWARD_ANCHOR_ID))
                    .thenReturn(Optional.of(projectionFor(12)));
            when(mockRepository.countBySecUsrIdLessThan(BACKWARD_ANCHOR_ID)).thenReturn(11L);
            when(mockRepository.findBySecUsrIdLessThanOrderBySecUsrIdDesc(
                    eq(BACKWARD_ANCHOR_ID), any(Limit.class)))
                    .thenReturn(projectionsOf(arriving));

            final UserOutcome response = serviceWithMocks().listUsers(
                    listRequest(KeyAction.PFK07, null, BACKWARD_ANCHOR_ID, null, List.of()));

            assertThat(userIdsOf(response))
                    .containsExactly("USER0001", "USER0002", "USER0003", "USER0004");
            assertThat(response.rows()).hasSize(arriving.size()).doesNotContainNull();
            assertThat(response.pageMetadata().pageSize())
                    .as("a short backward page reports the same ten-row window as a full one (DL-363)")
                    .isEqualTo(SCREEN_ROWS);
            assertThat(response.message()).isEqualTo(MSG_LIST_REACHED_TOP);
            verifyNoMoreInteractions(mockRepository);
        }

        @Test
        @DisplayName("a submitted page indicator is never trusted: the store is asked for the same "
                + "window and the indicator the response carries is the one the walk derived")
        void ignoresASubmittedPageIndicator() {
            when(mockRepository.findAllProjectedBy(any(Pageable.class)))
                    .thenReturn(sliceOf(projectionsOf(
                            List.of("USER0001", "USER0002", "USER0003", "USER0004"))));

            final UserOutcome zero = serviceWithMocks().listUsers(new UserCommand(null, null, null,
                    null, null, null, List.of(), "0", null, null, null, KeyAction.ENTER, reEntered()));

            assertThat(zero.pageMetadata().displayedPageNumber()).isEqualTo(DISPLAYED_PAGE_ONE);
            assertThat(zero.rows()).hasSize(4);

            final UserOutcome negative = serviceWithMocks().listUsers(new UserCommand(null, null,
                    null, null, null, null, List.of(), "-1", null, null, null, KeyAction.ENTER,
                    reEntered()));

            assertThat(negative.pageMetadata().displayedPageNumber()).isEqualTo(DISPLAYED_PAGE_ONE);
            assertThat(negative.rows()).hasSize(4);
            verify(mockRepository, times(2)).findAllProjectedBy(any(Pageable.class));
            verifyNoMoreInteractions(mockRepository);
        }
    }

    @Nested
    @DisplayName("CU03 delete, observed at the store: removed once, by key, and only on confirmation")
    class DeleteObservedAtTheStore {

        @Test
        @DisplayName("the confirming key removes the row exactly once, naming the key the record "
                + "carries")
        void removesExactlyOnceByKey() {
            when(mockRepository.findByIdForUpdate("DELMCK01"))
                    .thenReturn(Optional.of(identityFor("DELMCK01", ROLE_CODE_USER)));

            final UserOutcome response = serviceWithMocks().deleteUser(
                    recordRequest("DELMCK01", null, null, null, null, KeyAction.PFK05));

            final ArgumentCaptor<String> removed = ArgumentCaptor.forClass(String.class);
            verify(mockRepository, times(1)).deleteById(removed.capture());
            assertThat(removed.getValue()).isEqualTo("DELMCK01");
            assertThat(response.actionSucceeded()).isTrue();
            assertThat(response.message()).isEqualTo("User DELMCK01 has been deleted ...");
            verify(mockRepository).findByIdForUpdate("DELMCK01");
            verifyNoMoreInteractions(mockRepository);
            verify(mockRecordWriter).flush();
            verifyNoMoreInteractions(mockRecordWriter);
        }

        @Test
        @DisplayName("an absent identifier reports the not-found text and no removal is attempted")
        void removesNothingWhenTheIdentifierIsAbsent() {
            when(mockRepository.findByIdForUpdate("GONEMCK1")).thenReturn(Optional.empty());

            final UserOutcome response = serviceWithMocks().deleteUser(
                    recordRequest("GONEMCK1", null, null, null, null, KeyAction.PFK05));

            assertThat(response.message()).isEqualTo(MSG_USER_ID_NOT_FOUND);
            assertThat(response.generalError()).isTrue();
            assertThat(response.actionSucceeded()).isFalse();
            assertThat(response.fieldErrors()).singleElement()
                    .satisfies(finding -> {
                        assertThat(finding.state())
                                .isEqualTo(ValidationException.FieldState.INVALID);
                        assertThat(finding.bmsFieldId()).isEqualTo(FIELD_LIST_USER_ID);
                    });
            verify(mockRepository).findByIdForUpdate("GONEMCK1");
            verify(mockRepository, never()).deleteById(any());
            verifyNoMoreInteractions(mockRepository);
            verifyNoInteractions(mockRecordWriter);
        }

        @Test
        @DisplayName("the enter key displays the record with the confirmation prompt and removes "
                + "nothing, which is the clause order the source performs")
        void displaysBeforeRemoving() {
            when(mockRepository.findById("DELMCK02"))
                    .thenReturn(Optional.of(identityFor("DELMCK02", ROLE_CODE_ADMIN)));

            final UserOutcome response = serviceWithMocks().deleteUser(
                    recordRequest("DELMCK02", null, null, null, null, KeyAction.ENTER));

            assertThat(response.message()).isEqualTo(MSG_DELETE_PRESS_PF5);
            assertThat(response.generalError()).isFalse();
            assertThat(response.actionSucceeded()).isFalse();
            assertThat(response.firstName()).isEqualTo(SEEDED_ADMIN_FIRST_NAME);
            assertThat(response.lastName()).isEqualTo(SEEDED_ADMIN_LAST_NAME);
            assertThat(response.userType()).isEqualTo(ROLE_CODE_ADMIN);
            verify(mockRepository).findById("DELMCK02");
            verify(mockRepository, never()).findByIdForUpdate(any());
            verify(mockRepository, never()).deleteById(any());
            verifyNoMoreInteractions(mockRepository);
            verifyNoInteractions(mockRecordWriter);
        }

        @Test
        @DisplayName("a blank identifier is refused before the store is asked anything at all")
        void refusesABlankIdentifierBeforeReading() {
            final UserOutcome response = serviceWithMocks().deleteUser(
                    recordRequest("   ", null, null, null, null, KeyAction.PFK05));

            assertThat(response.message()).isEqualTo(MSG_USER_ID_EMPTY);
            assertThat(response.generalError()).isTrue();
            assertThat(response.fieldErrors()).singleElement()
                    .satisfies(finding -> assertThat(finding.state())
                            .isEqualTo(ValidationException.FieldState.MISSING));
            verifyNoInteractions(mockRepository);
            verifyNoInteractions(mockRecordWriter);
        }
    }

    @Nested
    @DisplayName("The catalogue's fixed-width text crosses all four screens byte-identically")
    class FixedWidthCatalogueText {

        @Test
        @DisplayName("the unmapped-key text is fifty encoded bytes on every one of the four screens, "
                + "with its ten trailing spaces intact and never trimmed")
        void carriesFiftyEncodedBytesThroughAllFourScreens() {
            when(mockCatalog.invalidKeyMessage()).thenReturn(MSG_INVALID_KEY_PADDED);
            final UserManagementService service = serviceWithMocks();

            final List<UserOutcome> responses = List.of(
                    service.listUsers(listRequest(KeyAction.PFK09, null, null, null, List.of())),
                    service.addUser(recordRequest("ANYUSR01", "GIVEN", "FAMILY",
                            foldStableThrowawayCredential(), ROLE_CODE_USER, KeyAction.PFK06)),
                    service.updateUser(recordRequest("ANYUSR02", "GIVEN", "FAMILY", null,
                            ROLE_CODE_USER, KeyAction.PFK09)),
                    service.deleteUser(recordRequest("ANYUSR03", null, null, null, null,
                            KeyAction.PFK09)));

            assertThat(INVALID_KEY_TEXT_VISIBLE.getBytes(StandardCharsets.US_ASCII).length)
                    .as("forty visible characters, before the field's own space fill")
                    .isEqualTo(COMMON_MESSAGE_WIDTH - INVALID_KEY_TRAILING_SPACES);
            assertThat(INVALID_KEY_TRAILING_SPACES).isEqualTo(10);
            for (final UserOutcome response : responses) {
                assertThat(response.message()).isEqualTo(MSG_INVALID_KEY_PADDED);
                // Measured on encoded bytes, which is what the fixed-width contract is expressed in,
                // and never on the character count of a Java string.
                assertThat(response.message().getBytes(StandardCharsets.US_ASCII).length)
                        .isEqualTo(COMMON_MESSAGE_WIDTH);
                assertThat(response.message()).endsWith(" ".repeat(INVALID_KEY_TRAILING_SPACES));
                assertThat(response.message())
                        .as("passed through untrimmed: the space fill is contract, not whitespace")
                        .isNotEqualTo(MSG_INVALID_KEY_PADDED.trim());
                assertThat(response.generalError()).isTrue();
                assertThat(response.actionSucceeded()).isFalse();
            }
            verify(mockCatalog, times(4)).invalidKeyMessage();
            verifyNoInteractions(mockRepository);
            verifyNoInteractions(mockRecordWriter);
            verifyNoInteractions(mockNavigation);
        }
    }

    @Nested
    @DisplayName("Every destination comes from the navigation service, and none names a program "
            + "without a source member")
    class RoutingThroughTheNavigationService {

        @Test
        @DisplayName("all four operations publish the destination the navigation service resolves, "
                + "not the default they asked with")
        void publishTheResolvedDestinationRatherThanTheDefault() {
            // The stub deliberately answers with a route that is NOT the caller's default, because
            // that is the only way to tell a resolved destination apart from a hardcoded one: a service
            // that emitted its own default would pass an assertion made against that default.
            when(mockNavigation.resolveNominatedDestination(any(), any()))
                    .thenReturn(NavigationService.Route.USER_LIST);
            final UserManagementService service = serviceWithMocks();

            final List<UserOutcome> responses = List.of(
                    service.listUsers(new UserCommand(null, null, null, null, null, null, List.of(),
                            null, null, null, null, KeyAction.ENTER, null)),
                    service.addUser(new UserCommand(null, null, null, null, null, null, List.of(),
                            null, null, null, null, KeyAction.ENTER, null)),
                    service.updateUser(new UserCommand(null, null, null, null, null, null, List.of(),
                            null, null, null, null, KeyAction.ENTER, null)),
                    service.deleteUser(new UserCommand(null, null, null, null, null, null, List.of(),
                            null, null, null, null, KeyAction.ENTER, null)));

            for (final UserOutcome response : responses) {
                assertThat(response.nextRoute()).isEqualTo(ROUTE_USER_LIST);
            }
            verify(mockNavigation, times(4)).resolveNominatedDestination(any(),
                    eq(NavigationService.Route.SIGN_ON));
            verifyNoMoreInteractions(mockNavigation);
            // A route is a value in the response and nothing is forwarded on the server: no operation
            // touched the store on the way out, so nothing was dispatched behind the response.
            verifyNoInteractions(mockRepository);
            verifyNoInteractions(mockRecordWriter);
        }

        @Test
        @DisplayName("the update screen's exit key saves first and then leaves by the back navigation "
                + "the service resolved")
        void savesBeforeLeavingByTheResolvedBackNavigation() {
            when(mockNavigation.resolveBackNavigation(any(),
                    eq(NavigationService.Route.ADMIN_MENU)))
                    .thenReturn(NavigationService.Route.USER_LIST);
            when(mockNavigation.resolveNominatedDestination(any(),
                    eq(NavigationService.Route.USER_LIST)))
                    .thenReturn(NavigationService.Route.USER_LIST);
            when(mockRepository.findByIdForUpdate("UPDMCK04"))
                    .thenReturn(Optional.of(identityFor("UPDMCK04", ROLE_CODE_USER)));
            when(mockRepository.save(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            final UserOutcome response = serviceWithMocks().updateUser(
                    recordRequest("UPDMCK04", "CHANGED", "ALTERED", null, ROLE_CODE_ADMIN,
                            KeyAction.PFK03));

            assertThat(response.nextRoute()).isEqualTo(ROUTE_USER_LIST);
            verify(mockRepository).save(any(UserSecurity.class));
            verify(mockNavigation).resolveBackNavigation(any(),
                    eq(NavigationService.Route.ADMIN_MENU));
            verify(mockNavigation).resolveNominatedDestination(any(),
                    eq(NavigationService.Route.USER_LIST));
            verifyNoMoreInteractions(mockNavigation);
        }

        @Test
        @DisplayName("the delete screen's exit key leaves by the same resolved navigation and deletes "
                + "nothing on the way out")
        void leavesTheDeleteScreenWithoutRemovingAnything() {
            when(mockNavigation.resolveBackNavigation(any(),
                    eq(NavigationService.Route.ADMIN_MENU)))
                    .thenReturn(NavigationService.Route.ADMIN_MENU);
            when(mockNavigation.resolveNominatedDestination(any(),
                    eq(NavigationService.Route.ADMIN_MENU)))
                    .thenReturn(NavigationService.Route.ADMIN_MENU);

            final UserOutcome response = serviceWithMocks().deleteUser(
                    recordRequest("DELMCK03", null, null, null, null, KeyAction.PFK03));

            assertThat(response.nextRoute()).isEqualTo(ROUTE_ADMIN_MENU);
            verifyNoInteractions(mockRepository);
            verifyNoInteractions(mockRecordWriter);
        }

        @Test
        @DisplayName("no route in the table names the one CICS program definition that has no source "
                + "member, and the four user routes name the four members that do")
        void noRouteNamesTheProgramWithoutASourceMember() {
            assertThat(NavigationService.Route.values())
                    .extracting(NavigationService.Route::getLegacyProgramName)
                    .as("app/csd/CARDDEMO.CSD declares this program at L211 and binds it at L390, "
                            + "with nothing to translate, so no route may resolve to it")
                    .doesNotContain(DANGLING_CSD_PROGRAM);
            assertThat(NavigationService.Route.USER_LIST.getLegacyProgramName())
                    .isEqualTo("COUSR00C");
            assertThat(NavigationService.Route.USER_ADD.getLegacyProgramName())
                    .isEqualTo("COUSR01C");
            assertThat(NavigationService.Route.USER_UPDATE.getLegacyProgramName())
                    .isEqualTo("COUSR02C");
            assertThat(NavigationService.Route.USER_DELETE.getLegacyProgramName())
                    .isEqualTo("COUSR03C");
            assertThat(NavigationService.Route.USER_LIST.getLegacyTransactionId()).isEqualTo("CU00");
            assertThat(NavigationService.Route.USER_ADD.getLegacyTransactionId()).isEqualTo("CU01");
            assertThat(NavigationService.Route.USER_UPDATE.getLegacyTransactionId()).isEqualTo("CU02");
            assertThat(NavigationService.Route.USER_DELETE.getLegacyTransactionId()).isEqualTo("CU03");
            assertThat(NavigationService.Route.USER_LIST.getRouteValue()).isEqualTo(ROUTE_USER_LIST);
            assertThat(NavigationService.Route.USER_UPDATE.getRouteValue())
                    .isEqualTo(ROUTE_USER_UPDATE);
            assertThat(NavigationService.Route.USER_DELETE.getRouteValue())
                    .isEqualTo(ROUTE_USER_DELETE);
            assertThat(NavigationService.Route.ADMIN_MENU.getRouteValue())
                    .isEqualTo(ROUTE_ADMIN_MENU);
            assertThat(NavigationService.Route.SIGN_ON.getRouteValue()).isEqualTo(ROUTE_SIGN_ON);
        }
    }

    @Nested
    @DisplayName("The two-state field-error contract: missing for blank, invalid for refused")
    class TwoStateFieldErrorContract {

        @Test
        @DisplayName("the state contract carries exactly two constants and no third")
        void carriesExactlyTwoStates() {
            assertThat(ValidationException.FieldState.values()).hasSize(2)
                    .containsExactly(ValidationException.FieldState.MISSING,
                            ValidationException.FieldState.INVALID);
        }

        @Test
        @DisplayName("a blank required item is reported as missing, naming its property and its screen "
                + "field")
        void reportsABlankItemAsMissing() {
            final UserOutcome response = serviceWithMocks().addUser(
                    recordRequest("NEWUSR12", "   ", "FAMILY", foldStableThrowawayCredential(),
                            ROLE_CODE_USER, KeyAction.ENTER));

            assertThat(response.fieldErrors()).singleElement().satisfies(finding -> {
                assertThat(finding.state()).isEqualTo(ValidationException.FieldState.MISSING);
                assertThat(finding.field()).isEqualTo(PROPERTY_FIRST_NAME);
                assertThat(finding.bmsFieldId()).isEqualTo(FIELD_FIRST_NAME);
                assertThat(finding.message()).isEqualTo(MSG_FIRST_NAME_EMPTY);
            });
            assertThat(response.hasFieldErrors()).isTrue();
            verifyNoInteractions(mockRepository);
            verifyNoInteractions(mockRecordWriter);
        }

        @Test
        @DisplayName("a value the store refuses is reported as invalid, which is a different state and "
                + "a different instruction to the operator")
        void reportsARefusedValueAsInvalid() {
            when(mockRecordWriter.insertIndependently(any(UserSecurity.class)))
                    .thenThrow(new DuplicateKeyException("duplicate test identifier"));

            final UserOutcome response = serviceWithMocks().addUser(
                    recordRequest("DUPUSR12", "GIVEN", "FAMILY", foldStableThrowawayCredential(),
                            ROLE_CODE_USER, KeyAction.ENTER));

            assertThat(response.fieldErrors()).singleElement().satisfies(finding -> {
                assertThat(finding.state()).isEqualTo(ValidationException.FieldState.INVALID);
                assertThat(finding.field()).isEqualTo(PROPERTY_USER_ID);
            });
        }

        @Test
        @DisplayName("the finding list is never absent and cannot be mutated, whether it is empty or "
                + "populated")
        void publishesAnUnmodifiableFindingList() {
            final ValidationException.FieldError intruder = new ValidationException.FieldError(
                    PROPERTY_USER_ID, FIELD_LIST_USER_ID,
                    ValidationException.FieldState.MISSING, MSG_USER_ID_EMPTY);

            final UserOutcome populated = serviceWithMocks().addUser(
                    recordRequest("NEWUSR13", "   ", "FAMILY", foldStableThrowawayCredential(),
                            ROLE_CODE_USER, KeyAction.ENTER));
            final List<ValidationException.FieldError> populatedFindings = populated.fieldErrors();
            assertThat(populatedFindings).isNotNull().hasSize(1);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> populatedFindings.add(intruder));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(populatedFindings::clear);

            final UserOutcome clean = serviceWithMocks().addUser(
                    recordRequest("NEWUSR14", "GIVEN", "FAMILY", foldStableThrowawayCredential(),
                            ROLE_CODE_USER, KeyAction.PFK04));
            final List<ValidationException.FieldError> emptyFindings = clean.fieldErrors();
            assertThat(emptyFindings).isNotNull().isEmpty();
            assertThat(clean.hasFieldErrors()).isFalse();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> emptyFindings.add(intruder));

            // The same guarantee on the exception the boundary raises from those findings.
            assertThat(new ValidationException(MSG_USER_ID_EMPTY).fieldErrors())
                    .isNotNull().isEmpty();
            final ValidationException raised = new ValidationException(PROPERTY_USER_ID,
                    FIELD_LIST_USER_ID, ValidationException.FieldState.MISSING, MSG_USER_ID_EMPTY);
            assertThat(raised.fieldErrors()).isNotNull().hasSize(1);
            assertThat(raised.hasFieldErrors()).isTrue();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> raised.fieldErrors().add(intruder));
            verifyNoInteractions(mockRepository);
        }
    }

    @Nested
    @DisplayName("Type codes, alphabetic fidelity and boundary input, observed at the store")
    class TypeCodesAndBoundaryInput {

        @ParameterizedTest(name = "stored code [{0}]")
        @ValueSource(strings = {"X", "Z", "1", "a"})
        @DisplayName("a stored code outside the declared pair loads and is surfaced verbatim, with no "
                + "enum conversion attempted and nothing raised")
        void loadsAnUndeclaredCodeVerbatim(final String rawCode) {
            when(mockRepository.findById("TYPMCK01"))
                    .thenReturn(Optional.of(identityFor("TYPMCK01", rawCode)));

            final UserOutcome response = serviceWithMocks().updateUser(
                    recordRequest("TYPMCK01", null, null, null, null, KeyAction.ENTER));

            assertThat(UserType.fromCode(rawCode))
                    .as("the code really is one the estate never declared")
                    .isEmpty();
            assertThat(response.userType()).isEqualTo(rawCode);
            assertThat(response.message()).isEqualTo(MSG_UPDATE_PRESS_PF5);
            assertThat(response.generalError()).isFalse();
            verify(mockRepository).findById("TYPMCK01");
            verifyNoMoreInteractions(mockRepository);
        }

        @Test
        @DisplayName("the two declared codes map to their enum constants, and only the "
                + "administrative one is administrative")
        void mapsTheTwoDeclaredCodes() {
            assertThat(UserType.fromCode(ROLE_CODE_ADMIN)).contains(UserType.ADMIN);
            assertThat(UserType.fromCode(ROLE_CODE_USER)).contains(UserType.USER);
            assertThat(UserType.ADMIN.getCode()).isEqualTo(ROLE_CODE_ADMIN);
            assertThat(UserType.USER.getCode()).isEqualTo(ROLE_CODE_USER);
            assertThat(UserType.ADMIN.isAdmin()).isTrue();
            assertThat(UserType.USER.isAdmin()).isFalse();
            assertThat(UserType.values()).hasSize(2);
        }

        @Test
        @DisplayName("the administrative code drives the administrative role class, the standard code "
                + "the standard one, and an undeclared code the standard one as well")
        void driveTheRoleClassFromTheStoredCode() {
            when(mockRecordWriter.insertIndependently(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            final UserManagementService service = serviceWithMocks();

            service.addUser(recordRequest("ROLMCK01", "GIVEN", "FAMILY",
                    foldStableThrowawayCredential(), ROLE_CODE_ADMIN, KeyAction.ENTER));
            service.addUser(recordRequest("ROLMCK02", "GIVEN", "FAMILY",
                    foldStableThrowawayCredential(), ROLE_CODE_USER, KeyAction.ENTER));
            service.addUser(recordRequest("ROLMCK03", "GIVEN", "FAMILY",
                    foldStableThrowawayCredential(), "Q", KeyAction.ENTER));

            final List<String> added = logCapture.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("User added:"))
                    .toList();
            assertThat(added).hasSize(3);
            assertThat(added.get(0)).contains("roleClass=administrative");
            assertThat(added.get(1)).contains("roleClass=standard");
            assertThat(added.get(2))
                    .as("an undeclared code takes the unconditional alternative, exactly as the "
                            + "sign-on program's own role split does, and raises nothing")
                    .contains("roleClass=standard");
            assertThat(added).allSatisfy(message ->
                    assertThat(message).doesNotContain("ROLMCK"));
            verify(mockRecordWriter, times(3)).insertIndependently(any(UserSecurity.class));
            verifyNoInteractions(mockRepository);
        }

        @Test
        @DisplayName("a name with an embedded space, and one carrying a digit, are both stored "
                + "verbatim, because these four screens apply no alphabetic edit")
        void storesNamesVerbatimBecauseNoAlphabeticEditExists() {
            when(mockRecordWriter.insertIndependently(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            final UserManagementService service = serviceWithMocks();

            service.addUser(recordRequest("NAMMCK01", "MARY ANN", "Aniya Von",
                    foldStableThrowawayCredential(), ROLE_CODE_USER, KeyAction.ENTER));
            service.addUser(recordRequest("NAMMCK02", "GIVEN1", "FAMILY2",
                    foldStableThrowawayCredential(), ROLE_CODE_USER, KeyAction.ENTER));

            final ArgumentCaptor<UserSecurity> written =
                    ArgumentCaptor.forClass(UserSecurity.class);
            verify(mockRecordWriter, times(2)).insertIndependently(written.capture());
            assertThat(written.getAllValues().get(0).getSecUsrFname()).isEqualTo("MARY ANN");
            assertThat(written.getAllValues().get(0).getSecUsrLname()).isEqualTo("Aniya Von");
            assertThat(written.getAllValues().get(1).getSecUsrFname())
                    .as("a digit-bearing name is accepted here, because inventing an edit would "
                            + "reject input the legacy stores")
                    .isEqualTo("GIVEN1");
            verifyNoInteractions(mockRepository);
        }

        @Test
        @DisplayName("the estate's alphabetic predicate does discriminate, even though none of these "
                + "four screens applies it")
        void theEstateAlphabeticPredicateDiscriminates() {
            // The predicate is the subject here rather than an oracle: the point is that a blank-and-trim
            // alphabetic edit admits embedded spaces and refuses a digit, so a translation that reached
            // for an every-character-is-a-letter test would be wrong wherever the edit IS applied.
            assertThat(CobolStringUtils.isAlphaOrSpace("MARY ANN")).isTrue();
            assertThat(CobolStringUtils.isAlphaOrSpace("Aniya Von")).isTrue();
            assertThat(CobolStringUtils.isAlphaOrSpace("GIVEN1")).isFalse();
        }

        @Test
        @DisplayName("a short identifier is space-filled to the record's eight positions before the "
                + "store is asked")
        void spaceFillsAShortIdentifier() {
            when(mockRepository.findById(any())).thenReturn(Optional.empty());

            serviceWithMocks().updateUser(
                    recordRequest("AB", null, null, null, null, KeyAction.ENTER));

            final ArgumentCaptor<String> asked = ArgumentCaptor.forClass(String.class);
            verify(mockRepository).findById(asked.capture());
            assertThat(asked.getValue()).hasSize(8).isEqualTo("AB" + " ".repeat(6));
            verifyNoMoreInteractions(mockRepository);
        }

        @Test
        @DisplayName("an over-long identifier loses its rightmost characters, which is what a move "
                + "into a narrower field does")
        void truncatesAnOverLongIdentifier() {
            when(mockRepository.findById(any())).thenReturn(Optional.empty());

            serviceWithMocks().deleteUser(
                    recordRequest("USER00012345", null, null, null, null, KeyAction.ENTER));

            final ArgumentCaptor<String> asked = ArgumentCaptor.forClass(String.class);
            verify(mockRepository).findById(asked.capture());
            assertThat(asked.getValue()).hasSize(8).isEqualTo("USER0001");
            verifyNoMoreInteractions(mockRepository);
        }

        @ParameterizedTest(name = "identifier [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "        "})
        @DisplayName("an absent, empty or all-space identifier is refused on both maintenance screens "
                + "before any read, with nothing escaping")
        void refusesAnUnusableIdentifierWithoutReading(final String identifier) {
            final UserManagementService service = serviceWithMocks();

            final UserOutcome update = service.updateUser(
                    recordRequest(identifier, "GIVEN", "FAMILY", null, ROLE_CODE_USER,
                            KeyAction.PFK05));
            final UserOutcome delete = service.deleteUser(
                    recordRequest(identifier, null, null, null, null, KeyAction.PFK05));

            assertThat(update.message()).isEqualTo(MSG_USER_ID_EMPTY);
            assertThat(delete.message()).isEqualTo(MSG_USER_ID_EMPTY);
            assertThat(update.generalError()).isTrue();
            assertThat(delete.generalError()).isTrue();
            verifyNoInteractions(mockRepository);
            verifyNoInteractions(mockRecordWriter);
        }

        @Test
        @DisplayName("no returned representation and no captured log line carries a credential or a "
                + "digest, on the load, the save or the list")
        void keepsCredentialMaterialOutOfEveryProjectionAndLogLine() {
            final String credential = foldStableThrowawayCredential();
            final String digest = ENCODER.encode(credential);
            final UserSecurity held = identityHolding("PRVMCK01", digest, ROLE_CODE_USER);
            when(mockRepository.findById("PRVMCK01")).thenReturn(Optional.of(held));
            when(mockRepository.findByIdForUpdate("PRVMCK01")).thenReturn(Optional.of(held));
            when(mockRepository.save(any(UserSecurity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(mockRepository.findAllProjectedBy(any(Pageable.class)))
                    .thenReturn(sliceOf(projectionsOf(FIRST_PAGE_IDS)));
            final UserManagementService service = serviceWithMocks();

            final UserOutcome loaded = service.updateUser(
                    recordRequest("PRVMCK01", null, null, null, null, KeyAction.ENTER));
            final UserOutcome saved = service.updateUser(
                    recordRequest("PRVMCK01", "CHANGED", "ALTERED", null, ROLE_CODE_ADMIN,
                            KeyAction.PFK05));
            final UserOutcome listed = service.listUsers(
                    listRequest(KeyAction.ENTER, null, null, null, List.of()));

            // The response contract declares no credential component at all, so the assertion is that
            // nothing rendered from it carries one either - the rendering is where a leak would surface.
            for (final UserOutcome response : List.of(loaded, saved, listed)) {
                assertThat(String.valueOf(response)).doesNotContain(digest)
                        .doesNotContain(credential);
            }
            assertThat(listed.rows()).isNotEmpty()
                    .allSatisfy(row -> assertThat(String.valueOf(row)).doesNotContain(digest)
                            .doesNotContain(credential));
            assertThat(noCapturedLogContains(digest)).isTrue();
            assertThat(noCapturedLogContains(credential)).isTrue();
            assertThat(loaded.message()).isEqualTo(MSG_UPDATE_PRESS_PF5);
            assertThat(saved.actionSucceeded()).isTrue();
            verify(encoderSpy, never()).encode(any());
        }
    }

}
