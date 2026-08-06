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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.carddemo.domain.UserSecurity;
import com.carddemo.domain.enums.KeyAction;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.RecordWriter;
import com.carddemo.repository.UserSecurityRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

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
 */
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

    private static final String FIELD_PASSWORD = "PASSWD";

    /** Screen row count, from the ten-occurrence table at {@code app/cbl/COUSR00C.cbl} L56-L57. */
    private static final int SCREEN_ROWS = 10;

    /** Width of the common message contract, which the unmapped-key text occupies in full. */
    private static final int COMMON_MESSAGE_WIDTH = 50;

    /** Digest length the credential column is sized for. */
    private static final int DIGEST_LENGTH = 60;

    // ---------------------------------------------------------------- fixtures

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder(12);

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

    @BeforeEach
    void setUp() {
        messageCatalogService = new MessageCatalogService();
        navigationService = new NavigationService();
        final LoggerContext context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        final ch.qos.logback.classic.Logger serviceLogger =
                context.getLogger(UserManagementService.class);
        serviceLogger.setLevel(Level.TRACE);
        logCapture = new ListAppender<>();
        logCapture.setContext(context);
        logCapture.start();
        serviceLogger.addAppender(logCapture);
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
     * <p>The repository declares no method of its own, so everything below is one of
     * {@code JpaRepository}'s. Exactly the five the service uses are implemented - the keyed read, the
     * paged scan, the single write, the single delete and the existence probe - and every other
     * inherited operation <strong>refuses</strong> rather than answering. That refusal is the
     * least-privilege rule expressed where it can actually be enforced: if a future change reaches for
     * an unbounded {@code findAll()}, a bulk delete or a query by example, the test that exercises the
     * new path fails immediately and names the operation, which an interface that simply did not
     * declare the method could never do for a caller of the inherited one.
     */
    private static final class FakeRepository implements UserSecurityRepository {

        private final TreeMap<String, UserSecurity> rows = new TreeMap<>();

        private boolean failOnSave;

        private boolean failOnFind;

        private boolean failOnDelete;

        /**
         * The ordinal of the paged read that should fail, counting from one. A browse positions itself
         * and then reads rows through the same paged call, so this makes the two failure arms
         * separately reachable: {@code 1} fails the positioning read and {@code 2} lets the browse
         * position itself and then fails the first row read.
         */
        private int failProjectionFromCall = Integer.MAX_VALUE;

        private int projectionCalls;

        @Override
        public Optional<UserSecurity> findById(final String secUsrId) {
            if (failOnFind) {
                throw new IllegalStateException("store unavailable");
            }
            return Optional.ofNullable(rows.get(secUsrId));
        }

        /**
         * The paged primary-key scan, which is the browse's only read path.
         *
         * <p>The map is sorted on the identifier, so iteration order is already the ascending key
         * order the browse positions against; the requested window is sliced out of it. Entities are
         * returned rather than a projection, because the repository declares no projection: the rule
         * that a credential is loaded but never rendered is kept by the service, and the suite asserts
         * it by scanning the response and the captured log rather than by the absence of a column.
         */
        @Override
        public Page<UserSecurity> findAll(final Pageable pageable) {
            projectionCalls++;
            if (projectionCalls >= failProjectionFromCall) {
                throw new IllegalStateException("store unavailable");
            }
            final List<UserSecurity> all = List.copyOf(rows.values());
            final int from = (int) Math.min(pageable.getOffset(), all.size());
            final int to = Math.min(from + pageable.getPageSize(), all.size());
            return new PageImpl<>(List.copyOf(all.subList(from, to)), pageable, all.size());
        }

        @Override
        public <S extends UserSecurity> S save(final S identity) {
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

        // ------------------------------------------------------------------------------------------
        // Every other inherited operation refuses. Nothing in the module calls one, and a refusal is
        // what turns "nothing calls it" from a claim into a test failure the moment something does.
        // ------------------------------------------------------------------------------------------

        @Override
        public boolean existsById(final String secUsrId) {
            if (failOnFind) {
                throw new IllegalStateException("store unavailable");
            }
            return rows.containsKey(secUsrId);
        }

        @Override
        public List<UserSecurity> findAll() {
            throw refusal("findAll() would read every credential in the table");
        }

        @Override
        public List<UserSecurity> findAll(final Sort sort) {
            throw refusal("findAll(Sort) would read every credential in the table");
        }

        @Override
        public List<UserSecurity> findAllById(final Iterable<String> ids) {
            throw refusal("findAllById is not one of the five operations the service uses");
        }

        @Override
        public long count() {
            throw refusal("count is not one of the five operations the service uses");
        }

        @Override
        public void delete(final UserSecurity identity) {
            throw refusal("the legacy delete removes one row by key, so deleteById is the only path");
        }

        @Override
        public void deleteAll() {
            throw refusal("deleteAll would remove every sign-on identity in one statement");
        }

        @Override
        public void deleteAll(final Iterable<? extends UserSecurity> identities) {
            throw refusal("bulk delete has no legacy counterpart");
        }

        @Override
        public void deleteAllById(final Iterable<? extends String> ids) {
            throw refusal("bulk delete has no legacy counterpart");
        }

        @Override
        public void deleteAllInBatch() {
            throw refusal("deleteAllInBatch would remove every identity and bypass the context");
        }

        @Override
        public void deleteAllInBatch(final Iterable<UserSecurity> identities) {
            throw refusal("bulk delete has no legacy counterpart");
        }

        @Override
        public void deleteAllByIdInBatch(final Iterable<String> ids) {
            throw refusal("bulk delete has no legacy counterpart");
        }

        @Override
        public <S extends UserSecurity> List<S> saveAll(final Iterable<S> identities) {
            throw refusal("the legacy tier rewrote one record at a time");
        }

        @Override
        public <S extends UserSecurity> List<S> saveAllAndFlush(final Iterable<S> identities) {
            throw refusal("the legacy tier rewrote one record at a time");
        }

        @Override
        public <S extends UserSecurity> S saveAndFlush(final S identity) {
            throw refusal("flush ordering is the transaction's concern, not this service's");
        }

        @Override
        public void flush() {
            throw refusal("flush ordering is the transaction's concern, not this service's");
        }

        @Override
        @Deprecated
        public UserSecurity getById(final String secUsrId) {
            throw refusal("a lazy proxy is a dereference failure this entity cannot otherwise reach");
        }

        @Override
        @Deprecated
        public UserSecurity getOne(final String secUsrId) {
            throw refusal("a lazy proxy is a dereference failure this entity cannot otherwise reach");
        }

        @Override
        public UserSecurity getReferenceById(final String secUsrId) {
            throw refusal("a lazy proxy is a dereference failure this entity cannot otherwise reach");
        }

        @Override
        public <S extends UserSecurity> Optional<S> findOne(final Example<S> example) {
            throw refusal("query by example would query over the credential attribute");
        }

        @Override
        public <S extends UserSecurity> List<S> findAll(final Example<S> example) {
            throw refusal("query by example would query over the credential attribute");
        }

        @Override
        public <S extends UserSecurity> List<S> findAll(final Example<S> example, final Sort sort) {
            throw refusal("query by example would query over the credential attribute");
        }

        @Override
        public <S extends UserSecurity> Page<S> findAll(final Example<S> example,
                final Pageable pageable) {
            throw refusal("query by example would query over the credential attribute");
        }

        @Override
        public <S extends UserSecurity> long count(final Example<S> example) {
            throw refusal("query by example would query over the credential attribute");
        }

        @Override
        public <S extends UserSecurity> boolean exists(final Example<S> example) {
            throw refusal("query by example would query over the credential attribute");
        }

        @Override
        public <S extends UserSecurity, R> R findBy(final Example<S> example,
                final Function<FetchableFluentQuery<S>, R> queryFunction) {
            throw refusal("the fluent query surface would query over the credential attribute");
        }

        /**
         * Builds the refusal an unused inherited operation raises.
         *
         * @param because why the module does not use the operation
         * @return the exception to throw, never {@code null}
         */
        private static UnsupportedOperationException refusal(final String because) {
            return new UnsupportedOperationException("the user-management service uses five"
                    + " repository operations - findById, findAll(Pageable), save, deleteById and"
                    + " existsById - and this is not one of them: " + because);
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
            assertThat(stored.credentialDigest()).hasSize(DIGEST_LENGTH)
                    .isNotEqualTo(submitted)
                    .startsWith("$2");
            assertThat(ENCODER.matches(submitted, stored.credentialDigest())).isTrue();
            assertThat(response.actionSucceeded()).isTrue();
            assertThat(response.generalError()).isFalse();
            assertThat(response.message()).isEqualTo("User NEWUSR01 has been added ...");
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
         * <p>{@code EVALUATE TRUE} at {@code app/cbl/COUSR01C.cbl} L116-L151 evaluates its clauses in
         * order and executes only the first whose condition holds. Each clause raises the flag, moves
         * its own text, moves -1 to its own field's length and performs the send, so a submission with
         * every item empty produces exactly one text, one cursor position and one decorated field - the
         * given name, because that clause is first.
         *
         * <p>An earlier revision of this test asserted five entries, which is a screen the legacy cannot
         * produce: it would decorate four fields the operator was never told about and would have to
         * choose which of five texts to show on the single message line.</p>
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
            final String digestBefore = ENCODER.encode(known);
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
        @DisplayName("a genuinely different credential is hashed and replaces the stored digest")
        void hashesAGenuinelyNewCredential() {
            final FakeRepository repository = new FakeRepository();
            final String previous = throwawayCredential();
            final String digestBefore = ENCODER.encode(previous);
            seed(repository, "UPDUSR03", "BOB", "KING", "U", digestBefore);
            final String replacement = throwawayCredential();

            final UserOutcome response = serviceFor(repository)
                    .updateUser(recordRequest("UPDUSR03", "BOB", "KING", replacement, "U",
                            KeyAction.PFK05));

            final String digestAfter = repository.rows.get("UPDUSR03").credentialDigest();
            assertThat(response.actionSucceeded()).isTrue();
            assertThat(digestAfter).hasSize(DIGEST_LENGTH).isNotEqualTo(digestBefore);
            assertThat(ENCODER.matches(replacement, digestAfter)).isTrue();
            assertThat(ENCODER.matches(previous, digestAfter)).isFalse();
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
            assertThat(response.focusScreenFieldId()).isEqualTo(FIELD_PASSWORD);
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
            repository.failProjectionFromCall = 1;

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
            repository.failProjectionFromCall = 2;

            final UserOutcome response = serviceFor(repository)
                    .listUsers(listRequest(KeyAction.ENTER, null, null, null, List.of()));

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
         * <p>{@code EVALUATE TRUE} at {@code app/cbl/COUSR02C.cbl} L177-L212 stops at its first true
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
            repository.failProjectionFromCall = 1;

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
            repository.failProjectionFromCall = 1;

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
            repository.failProjectionFromCall = 1;

            final UserOutcome response = serviceFor(repository)
                    .listUsers(listRequest(KeyAction.ENTER, null, "USER0001", null,
                            selectionAt(3, "U")));

            assertThat(response.message()).isEqualTo(MSG_LIST_UNABLE_TO_LOOKUP_USER);
            assertThat(response.generalError()).isTrue();
            assertThat(response.nextRoute()).isNotEqualTo(ROUTE_USER_UPDATE);
        }

        @Test
        @DisplayName("a store that refuses a row probe after positioning reports it the same way")
        void reportsARowProbeFailureOnTheForwardKey() {
            final FakeRepository repository = repositoryOf(25);
            repository.failProjectionFromCall = 2;

            final UserOutcome response = serviceFor(repository)
                    .listUsers(listRequest(KeyAction.PFK08, null, null, "USER0010", List.of()));

            assertThat(response.message()).isEqualTo(MSG_LIST_UNABLE_TO_LOOKUP_USER);
            assertThat(response.generalError()).isTrue();
            assertBoundedFailureDiagnostics(1, "User list row probe failed");
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
}
