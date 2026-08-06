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
package com.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import jakarta.persistence.TransactionRequiredException;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.carddemo.domain.UserSecurity;
import com.carddemo.support.AbstractPostgresIT;

/**
 * Drives the credential repository against a real migrated PostgreSQL server and verifies its frozen
 * shape: a <strong>closed</strong> interface extending only the marker {@code Repository}, publishing
 * exactly the nine operations the module needs and exactly one nested projection that cannot carry a
 * credential.
 *
 * <h2>Why the interface is closed and why this test guards it</h2>
 *
 * <p>Extending {@code JpaRepository} would publish, on a table whose every row holds a BCrypt digest,
 * an unbounded {@code findAll()}, a {@code deleteAll()}, a {@code saveAll()}, a lazy
 * {@code getReferenceById} and the whole query-by-example surface - none of which any caller in this
 * module uses and none of which the legacy tier has a counterpart for. A closed interface makes each of
 * those a compilation failure at the call site rather than something a reviewer has to notice, so the
 * assertions in {@link FrozenInterfaceContract} are the enforcement mechanism and not documentation.
 *
 * <h2>Why the browse reads a projection and pages by key</h2>
 *
 * <p>The administrative list screen shows four fields per row and no credential, so its reads go through
 * {@link UserSecurityRepository.AdminEntry}, whose generated select does not name {@code sec_usr_pwd} at
 * all. Only the opening page is an offset read, because at page zero there is nothing to count and
 * discard; every page after it is a bounded keyset read on the primary key, which is what the legacy
 * screen does when it repositions on the first or last identifier it displayed. This test proves both
 * halves against real SQL: the bound is strict, the order is the one the derived name declares, and the
 * limit truncates.
 *
 * <h2>Why a real context</h2>
 *
 * <p>Only assembling the persistence context proves that every derived method name resolves - a
 * misspelled property in a derived name is a startup failure, not a compile failure - and that the five
 * columns, including the 60-character digest column, match the migrated schema under
 * {@code ddl-auto=validate}.
 *
 * <h2>Provenance</h2>
 *
 * <p>The table is the relational form of the 80-byte {@code SEC-USER-DATA} record in copybook
 * {@code CSUSR01Y}; the ten seeded identities - five administrative, five standard - are the ones
 * {@code app/jcl/DUSRSECJ.jcl} supplies in stream at L35-L44. Legacy estate at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced here.
 */
@DisplayName("Credential repository: closed surface, projected reads and keyset paging")
final class UserSecurityRepositoryIT extends AbstractPostgresIT {

    /** The number of identities the sign-on seed inserts. */
    private static final int SEEDED_IDENTITIES = 10;

    /** The page size the legacy administrative browse presents, from a screen table occurring 10 times. */
    private static final int ADMIN_PAGE_SIZE = 10;

    /** The attribute the browse sorts on, which is the primary key. */
    private static final String SORT_ATTRIBUTE = "secUsrId";

    /** An identifier no seeded row carries, used for the absence and write assertions. */
    private static final String UNSEEDED_ID = "ZZTEST01";

    /**
     * A key above every identifier the estate uses, so a strictly-less count over it is the total.
     *
     * <p>The closed interface deliberately declares no {@code count()}, and this is how the write
     * assertions live without one: one bounded range aggregate over a key nothing reaches.
     *
     * <p><strong>The sentinel stays inside the identifier's own character domain, and that is not
     * cosmetic.</strong> This column is compared by the server's collation, and the two collations
     * this schema is deployed under are not the same one: the container this test starts inherits
     * {@code en_US.utf8}, while the local compose server runs {@code C.UTF-8}. The two agree exactly
     * on strings drawn from upper-case letters and digits - digits below letters, letters
     * alphabetical, verified by sorting a mixed sample under both - and disagree on punctuation,
     * which {@code en_US.utf8} collates below every alphanumeric and byte order collates above the
     * letters. A punctuation sentinel would therefore count every row under one deployment and no row
     * under the other. {@code ZZZZZZZZ} is above every seeded identifier and above
     * {@link #UNSEEDED_ID} in both.
     */
    private static final String ABOVE_EVERY_IDENTIFIER = "ZZZZZZZZ";

    /** A BCrypt digest of the expected form, so the entity accepts it. */
    private static final String DIGEST =
            "$2a$10$SYNTHETICDIGESTFORREPOSITORYITONLYNOTACREDENTIAL00001";

    /** The bean-property accessor a credential column would bind to, which must exist nowhere. */
    private static final String FORBIDDEN_ACCESSOR = "getSecUsrPwd";

    /** The nine operations the closed interface publishes, and the only ones it may publish. */
    private static final List<String> PUBLISHED_OPERATIONS = List.of(
            "countBySecUsrIdLessThan",
            "deleteById",
            "findAllProjectedBy",
            "findById",
            "findByIdForUpdate",
            "findBySecUsrIdGreaterThanOrderBySecUsrIdAsc",
            "findBySecUsrIdLessThanOrderBySecUsrIdDesc",
            "findProjectedBySecUsrId",
            "save");

    /** The four accessors the list projection publishes, and the only ones it may publish. */
    private static final List<String> PROJECTED_ACCESSORS = List.of(
            "getSecUsrFname",
            "getSecUsrId",
            "getSecUsrLname",
            "getSecUsrType");

    /** Creates the test class. */
    UserSecurityRepositoryIT() {
    }

    /**
     * Registers the repository and the entity for a runner-assembled context.
     *
     * <p>Declared here rather than reusing the application class so the context carries this one
     * repository and nothing else: a failure then names this interface rather than whichever bean
     * happened to be constructed first.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = UserSecurityRepository.class)
    @EntityScan(basePackageClasses = UserSecurity.class)
    static class RepositoryUnderTest {

        /** Creates the configuration. */
        RepositoryUnderTest() {
        }
    }

    /** Verifies the keyed reads and the write surface against the real schema. */
    @Nested
    @DisplayName("the keyed read and the write surface work against the real schema")
    final class KeyedAccess {

        /** Creates the nest. */
        KeyedAccess() {
        }

        @Test
        @DisplayName("bootstraps at all, which a derived name the framework could not resolve would "
                + "prevent - a narrowed repository that does not start is worse than a broad one")
        void bootstrapsAtAll() {
            runner().run(context -> assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(UserSecurityRepository.class));
        }

        @Test
        @DisplayName("reads one seeded identity by its identifier, digest included, because sign-on has "
                + "to verify a credential")
        void readsOneSeededIdentityByItsIdentifier() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);
                final String seededId = firstSeededIdentifier(repository);

                final Optional<UserSecurity> found = repository.findById(seededId);

                assertThat(found).isPresent();
                assertThat(found.orElseThrow().getSecUsrId()).isEqualTo(seededId);
                assertThat(found.orElseThrow().credentialDigest())
                        .as("the entity read is the one path a digest legitimately reaches memory by")
                        .isNotBlank()
                        .hasSize(60);
            });
        }

        @Test
        @DisplayName("answers empty for an identifier no row carries, rather than raising")
        void answersEmptyForAnAbsentIdentifier() {
            runner().run(context -> assertThat(
                    context.getBean(UserSecurityRepository.class).findById(UNSEEDED_ID))
                    .isEmpty());
        }

        @Test
        @DisplayName("stores one identity and removes it again, which is the whole write surface the "
                + "four administrative transactions need")
        void storesOneIdentityAndRemovesItAgain() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);
                final long seededCount = totalIdentities(repository);
                try {
                    repository.save(new UserSecurity(
                            UNSEEDED_ID, "TESTFIRST", "TESTLAST", DIGEST, "X"));
                    assertThat(repository.findById(UNSEEDED_ID))
                            .as("save covers insert through the provider's merge semantics")
                            .get()
                            .extracting(UserSecurity::getSecUsrType)
                            .as("the raw role column accepts the legacy unconditional-alternative case")
                            .isEqualTo("X");
                    assertThat(totalIdentities(repository)).isEqualTo(seededCount + 1);

                    repository.save(new UserSecurity(
                            UNSEEDED_ID, "CHANGED", "TESTLAST", DIGEST, "A"));
                    assertThat(repository.findById(UNSEEDED_ID).orElseThrow().getSecUsrFname())
                            .as("and update through the same one method, one record at a time")
                            .isEqualTo("CHANGED");
                    assertThat(totalIdentities(repository))
                            .as("updating the business-key row does not insert another identity")
                            .isEqualTo(seededCount + 1);
                } finally {
                    repository.deleteById(UNSEEDED_ID);
                }
                assertThat(repository.findById(UNSEEDED_ID))
                        .as("the delete removes the one row it is given")
                        .isEmpty();
                assertThat(totalIdentities(repository))
                        .as("AND LEAVES THE SEEDED ROWS ALONE. There is no bulk delete on this "
                                + "interface, and this is the assertion that would notice one")
                        .isEqualTo(seededCount);
            });
        }
    }

    /**
     * Verifies the held keyed read the two maintenance transactions use.
     *
     * <p>{@code app/cbl/COUSR02C.cbl} L322-L331 and {@code app/cbl/COUSR03C.cbl} L269-L278 both issue
     * {@code EXEC CICS READ ... UPDATE}, which holds the record until the rewrite at L360 or the delete
     * at L307 - and that delete names no record identifier at all, so the held record is the only one it
     * can mean. These tests prove the relational form really takes the hold and really keeps it for the
     * length of the unit of work, because a lock annotation that silently did nothing would leave the
     * lost update it exists to prevent, and no unit test can tell the difference.
     */
    @Nested
    @DisplayName("the held keyed read locks the row for the length of its unit of work")
    final class HeldKeyedRead {

        /** How long a competing holder is given to prove it is blocked. */
        private static final long BLOCKED_WINDOW_MILLIS = 750L;

        /** How long the competing holder is given to complete once the first one releases. */
        private static final long RELEASED_WINDOW_SECONDS = 30L;

        /** Creates the nest. */
        HeldKeyedRead() {
        }

        @Test
        @DisplayName("reads the row inside a unit of work, digest included, exactly as the unheld read "
                + "does - the lock is the only difference")
        void readsTheRowInsideAUnitOfWork() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);
                final String seededId = firstSeededIdentifier(repository);

                final Optional<UserSecurity> held = transactionTemplate(context)
                        .execute(status -> repository.findByIdForUpdate(seededId));

                assertThat(held).isPresent();
                assertThat(held.orElseThrow().getSecUsrId()).isEqualTo(seededId);
                assertThat(held.orElseThrow().credentialDigest()).hasSize(60);
            });
        }

        @Test
        @DisplayName("answers empty for an identifier no row carries, so the legacy not-found arm is "
                + "still an empty result and not a raised failure")
        void answersEmptyForAnAbsentIdentifier() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);

                final Optional<UserSecurity> absent = transactionTemplate(context)
                        .execute(status -> repository.findByIdForUpdate(UNSEEDED_ID));

                assertThat(absent).isEmpty();
            });
        }

        @Test
        @DisplayName("REFUSES to run outside a unit of work rather than reading without the lock, which "
                + "is the failure direction to prefer: a silent downgrade restores the lost update")
        void refusesToRunOutsideAUnitOfWork() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);
                final String seededId = firstSeededIdentifier(repository);

                assertThatExceptionOfType(InvalidDataAccessApiUsageException.class)
                        .isThrownBy(() -> repository.findByIdForUpdate(seededId))
                        .withRootCauseInstanceOf(TransactionRequiredException.class);
            });
        }

        @Test
        @DisplayName("HOLDS the row against a second holder until its unit of work ends, which is the "
                + "whole behaviour the legacy read-for-update had and the plain read does not")
        void holdsTheRowAgainstASecondHolder() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);
                final TransactionTemplate template = transactionTemplate(context);
                final String seededId = firstSeededIdentifier(repository);

                final CountDownLatch firstHolderHasTheRow = new CountDownLatch(1);
                final CountDownLatch firstHolderMayRelease = new CountDownLatch(1);
                final CountDownLatch secondHolderIsAboutToRead = new CountDownLatch(1);
                final ExecutorService threads = Executors.newFixedThreadPool(2);
                try {
                    final Future<Boolean> firstHolder = threads.submit(() -> template.execute(status -> {
                        repository.findByIdForUpdate(seededId).orElseThrow();
                        firstHolderHasTheRow.countDown();
                        awaitOrFail(firstHolderMayRelease);
                        return Boolean.TRUE;
                    }));
                    assertThat(firstHolderHasTheRow.await(RELEASED_WINDOW_SECONDS, TimeUnit.SECONDS))
                            .as("the first holder must reach the row before the second one competes")
                            .isTrue();

                    final Future<String> secondHolder = threads.submit(() -> template.execute(status -> {
                        secondHolderIsAboutToRead.countDown();
                        return repository.findByIdForUpdate(seededId).orElseThrow().getSecUsrId();
                    }));
                    assertThat(secondHolderIsAboutToRead.await(RELEASED_WINDOW_SECONDS,
                            TimeUnit.SECONDS))
                            .as("the second holder must actually have issued its read, or a timeout "
                                    + "below would pass for the wrong reason")
                            .isTrue();

                    assertThatExceptionOfType(TimeoutException.class)
                            .as("the second holder cannot obtain the row while the first unit of work "
                                    + "is open; if this read completed, the lock is not being taken")
                            .isThrownBy(() -> secondHolder.get(BLOCKED_WINDOW_MILLIS,
                                    TimeUnit.MILLISECONDS));

                    firstHolderMayRelease.countDown();
                    assertThat(firstHolder.get(RELEASED_WINDOW_SECONDS, TimeUnit.SECONDS)).isTrue();
                    assertThat(secondHolder.get(RELEASED_WINDOW_SECONDS, TimeUnit.SECONDS))
                            .as("and obtains it as soon as that unit of work ends, so the hold is "
                                    + "released by the boundary rather than held indefinitely")
                            .isEqualTo(seededId);
                } finally {
                    firstHolderMayRelease.countDown();
                    threads.shutdownNow();
                    assertThat(threads.awaitTermination(RELEASED_WINDOW_SECONDS, TimeUnit.SECONDS))
                            .as("no locking thread may outlive this test")
                            .isTrue();
                }
            });
        }

        /**
         * Waits for a latch, failing the calling thread rather than returning early on interruption.
         *
         * @param latch the latch to wait on
         */
        private static void awaitOrFail(final CountDownLatch latch) {
            try {
                if (!latch.await(RELEASED_WINDOW_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the release signal never arrived");
                }
            } catch (final InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while holding the row", interruption);
            }
        }
    }

    /** Verifies the projected opening page and the two bounded keyset reads. */
    @Nested
    @DisplayName("the browse pages by key over a projection that carries no credential")
    final class ProjectedKeysetBrowse {

        /** Creates the nest. */
        ProjectedKeysetBrowse() {
        }

        @Test
        @DisplayName("the opening page is the legacy page size, ascending, and carries the four screen "
                + "fields")
        void opensTheBrowseAtTheLegacyPageSize() {
            runner().run(context -> {
                final List<UserSecurityRepository.AdminEntry> opening =
                        openingPage(context.getBean(UserSecurityRepository.class), ADMIN_PAGE_SIZE);

                assertThat(opening).hasSize(ADMIN_PAGE_SIZE);
                assertThat(identifiersOf(opening)).isSorted();
                final UserSecurityRepository.AdminEntry first = opening.get(0);
                assertThat(first.getSecUsrId()).isNotBlank().hasSize(8);
                assertThat(first.getSecUsrFname()).isNotBlank();
                assertThat(first.getSecUsrLname()).isNotBlank();
                assertThat(first.getSecUsrType())
                        .as("the role code is returned raw, because the service applies the legacy "
                                + "unconditional alternative to whatever it holds")
                        .isNotBlank();
            });
        }

        @Test
        @DisplayName("the forward keyset read is strictly greater, ascending, and honours its limit")
        void readsForwardStrictlyAfterTheCursor() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);
                final List<String> everyIdentifier =
                        identifiersOf(openingPage(repository, SEEDED_IDENTITIES));
                final String cursor = everyIdentifier.get(0);

                final List<String> after = identifiersOf(
                        repository.findBySecUsrIdGreaterThanOrderBySecUsrIdAsc(cursor, Limit.of(3)));

                assertThat(after)
                        .as("the cursor row is the one already displayed and must not repeat")
                        .doesNotContain(cursor)
                        .hasSize(3)
                        .isSorted()
                        .containsExactlyElementsOf(everyIdentifier.subList(1, 4));
            });
        }

        @Test
        @DisplayName("the backward keyset read is strictly less and descending, because the legacy "
                + "backward page fills its bottom slot first")
        void readsBackwardStrictlyBeforeTheCursorInDescendingOrder() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);
                final List<String> everyIdentifier =
                        identifiersOf(openingPage(repository, SEEDED_IDENTITIES));
                final String cursor = everyIdentifier.get(4);

                final List<String> before = identifiersOf(
                        repository.findBySecUsrIdLessThanOrderBySecUsrIdDesc(cursor, Limit.of(3)));

                assertThat(before)
                        .doesNotContain(cursor)
                        .hasSize(3)
                        .isSortedAccordingTo(Comparator.reverseOrder());
                assertThat(before.reversed())
                        .as("reading the rows in reverse of the read order is what makes the assembled "
                                + "page ascend, which is the whole reason the read order is descending")
                        .containsExactlyElementsOf(everyIdentifier.subList(1, 4));
            });
        }

        @Test
        @DisplayName("the inclusive boundary seek finds the positioned row without loading a digest, "
                + "and answers empty above the last key")
        void seeksTheInclusiveBoundaryWithoutADigest() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);
                final String seededId = firstSeededIdentifier(repository);

                assertThat(repository.findProjectedBySecUsrId(seededId))
                        .get()
                        .extracting(UserSecurityRepository.AdminEntry::getSecUsrId)
                        .isEqualTo(seededId);
                assertThat(repository.findProjectedBySecUsrId(UNSEEDED_ID))
                        .as("a key matching nothing positions nowhere, which the forward read then "
                                + "resolves to the next higher key")
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("the range count is the position of a key, so the page counter costs one aggregate "
                + "rather than an offset rescan")
        void countsTheRowsPrecedingAKey() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);
                final List<String> everyIdentifier =
                        identifiersOf(openingPage(repository, SEEDED_IDENTITIES));

                assertThat(repository.countBySecUsrIdLessThan(everyIdentifier.get(0)))
                        .as("the first row has nothing before it, which is page one")
                        .isZero();
                assertThat(repository.countBySecUsrIdLessThan(everyIdentifier.get(4)))
                        .isEqualTo(4L);
                assertThat(repository.countBySecUsrIdLessThan(ABOVE_EVERY_IDENTIFIER))
                        .isGreaterThanOrEqualTo(SEEDED_IDENTITIES);
            });
        }

        @Test
        @DisplayName("an opening page and its keyset successor equal one wider window, with no gap and "
                + "no duplicate at the boundary")
        void joinsTheOpeningPageToItsKeysetSuccessorWithoutAGapOrDuplicate() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);
                final long originalCount = totalIdentities(repository);
                try {
                    repository.save(new UserSecurity(
                            UNSEEDED_ID, "TESTFIRST", "TESTLAST", DIGEST, "U"));

                    final List<String> firstPage = identifiersOf(openingPage(repository, ADMIN_PAGE_SIZE));
                    final List<String> secondPage = identifiersOf(
                            repository.findBySecUsrIdGreaterThanOrderBySecUsrIdAsc(
                                    firstPage.get(firstPage.size() - 1), Limit.of(ADMIN_PAGE_SIZE)));
                    final List<String> completeWindow =
                            identifiersOf(openingPage(repository, ADMIN_PAGE_SIZE + 1));
                    final List<String> observed =
                            Stream.concat(firstPage.stream(), secondPage.stream()).toList();

                    assertThat(firstPage).hasSize(ADMIN_PAGE_SIZE);
                    assertThat(secondPage).containsExactly(UNSEEDED_ID);
                    assertThat(observed)
                            .as("one ten-row page plus its keyset successor equals one eleven-row window")
                            .containsExactlyElementsOf(completeWindow);
                    assertThat(repository.findBySecUsrIdLessThanOrderBySecUsrIdDesc(
                                    UNSEEDED_ID, Limit.of(ADMIN_PAGE_SIZE)))
                            .as("and walking back from the added row reproduces the page it followed")
                            .extracting(UserSecurityRepository.AdminEntry::getSecUsrId)
                            .containsExactlyElementsOf(firstPage.reversed());
                } finally {
                    repository.deleteById(UNSEEDED_ID);
                }
                assertThat(totalIdentities(repository)).isEqualTo(originalCount);
            });
        }
    }

    /** Locks down the closed surface of the frozen repository interface. */
    @Nested
    @DisplayName("the interface publishes nine operations and one credential-free projection")
    final class FrozenInterfaceContract {

        /** Creates the nest. */
        FrozenInterfaceContract() {
        }

        @Test
        @DisplayName("extends only the marker Repository, so no store operation is inherited")
        void extendsOnlyTheMarkerRepository() {
            assertThat(UserSecurityRepository.class.getInterfaces())
                    .as("inheriting JpaRepository would publish findAll(), deleteAll() and the "
                            + "query-by-example surface over a table of credential digests")
                    .containsExactly(Repository.class);
        }

        @Test
        @DisplayName("declares exactly the nine operations the module uses, and no other")
        void declaresExactlyTheNinePublishedOperations() {
            assertThat(declaredMethodNames(UserSecurityRepository.class))
                    .containsExactlyElementsOf(PUBLISHED_OPERATIONS);
        }

        @Test
        @DisplayName("declares exactly one nested type, the closed list projection")
        void declaresExactlyOneNestedProjection() {
            assertThat(UserSecurityRepository.class.getDeclaredClasses())
                    .containsExactly(UserSecurityRepository.AdminEntry.class);
        }

        @Test
        @DisplayName("the projection publishes the four screen fields and no credential accessor")
        void theProjectionPublishesNoCredentialAccessor() {
            assertThat(declaredMethodNames(UserSecurityRepository.AdminEntry.class))
                    .as("a projection is closed by what it declares: an accessor named %s would make "
                            + "the provider select the digest column on the browse path",
                            FORBIDDEN_ACCESSOR)
                    .containsExactlyElementsOf(PROJECTED_ACCESSORS)
                    .doesNotContain(FORBIDDEN_ACCESSOR);
        }

        @Test
        @DisplayName("the entity keeps its digest behind a non-bean-property accessor")
        void theEntityKeepsItsDigestBehindANonBeanPropertyAccessor() {
            final List<String> entityMethods = declaredMethodNames(UserSecurity.class);

            assertThat(entityMethods)
                    .as("the administrative caller must not acquire a conventional bean getter named %s",
                            FORBIDDEN_ACCESSOR)
                    .doesNotContain(FORBIDDEN_ACCESSOR)
                    .contains("credentialDigest");
        }
    }

    // Helpers.

    /**
     * Builds a runner carrying a data source, the persistence provider and this one repository.
     *
     * @return a runner that has not been started
     */
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(RepositoryUnderTest.class)
                .withPropertyValues(
                        "spring.datasource.url=" + jdbcUrl(),
                        "spring.datasource.username=" + databaseUser(),
                        "spring.datasource.password=" + databasePassword(),
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.jpa.open-in-view=false");
    }

    /**
     * Wraps the context's transaction manager so a held read can be issued inside a unit of work.
     *
     * @param context the assembled context
     * @return a template over the context's transaction manager
     */
    private static TransactionTemplate transactionTemplate(
            final org.springframework.context.ApplicationContext context) {
        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    /**
     * Reads the opening page of the browse, ascending on the primary key.
     *
     * @param repository the repository to read through
     * @param rows       how many rows the page holds
     * @return the projected rows in ascending identifier order
     */
    private static List<UserSecurityRepository.AdminEntry> openingPage(
            final UserSecurityRepository repository, final int rows) {
        return repository.findAllProjectedBy(
                        PageRequest.of(0, rows, Sort.by(Sort.Direction.ASC, SORT_ATTRIBUTE)))
                .getContent();
    }

    /**
     * Returns the identifier of the first row the ascending browse reports.
     *
     * @param repository the repository to read through
     * @return a seeded identifier
     */
    private static String firstSeededIdentifier(final UserSecurityRepository repository) {
        return openingPage(repository, 1).get(0).getSecUsrId();
    }

    /**
     * Counts every identity, using the one range aggregate the closed interface publishes.
     *
     * @param repository the repository to read through
     * @return how many identities the table holds
     */
    private static long totalIdentities(final UserSecurityRepository repository) {
        return repository.countBySecUsrIdLessThan(ABOVE_EVERY_IDENTIFIER);
    }

    /**
     * Returns the identifiers of a projected window in window order.
     *
     * @param window the rows to read
     * @return the identifiers, in the order the window presents them
     */
    private static List<String> identifiersOf(
            final List<UserSecurityRepository.AdminEntry> window) {
        return window.stream()
                .map(UserSecurityRepository.AdminEntry::getSecUsrId)
                .toList();
    }

    /**
     * Returns the names a type declares, sorted so the assertion is order-independent.
     *
     * <p>Reflection is used here and nowhere in production code. Gate 6 scopes its reflection count to
     * {@code src/main/java/**}, and a contract that is expressed as "declares exactly these members"
     * can only be enforced by reading the declarations.
     *
     * @param type the type to inspect
     * @return the declared method names, sorted and deduplicated
     */
    private static List<String> declaredMethodNames(final Class<?> type) {
        return Stream.of(type.getDeclaredMethods())
                .map(Method::getName)
                .distinct()
                .sorted()
                .toList();
    }
}
