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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.carddemo.domain.UserSecurity;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.TestDataFactory;
import com.carddemo.support.TestDataFactory.SeededIdentity;

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
 * <h2>Why a real context, and why it is assembled by a runner rather than by {@code @SpringBootTest}</h2>
 *
 * <p>Only assembling the persistence context proves that every derived method name resolves - a
 * misspelled property in a derived name is a startup failure, not a compile failure - and that the five
 * columns, including the 60-character digest column, match the migrated schema under
 * {@code ddl-auto=validate}. A context that mapped a column at the wrong width would abort during
 * refresh rather than pass quietly, so {@link KeyedAccess#bootstrapsAtAll()} is the assertion that the
 * whole mapping is honoured.
 *
 * <p>The context is assembled by {@link ApplicationContextRunner} over three auto-configurations and
 * this one repository, which is what all six repository integration tests in this package do. Two
 * reasons, either sufficient. It registers <em>only</em> the repository and the entity, so this test's
 * dependency surface is {@code repository} plus {@code domain} - exactly the production package's own
 * one-way surface - whereas booting the application class would pull the api, service and batch tiers
 * into a test of a repository. And a failure then names this interface rather than whichever unrelated
 * bean happened to be constructed first. What matters for the no-mocked-input rule is unaffected: the
 * server is the real PostgreSQL 16 container the base class owns, every migration has been applied to
 * it, and nothing here substitutes an in-memory engine or a mock.
 *
 * <h2>What this table's own seed migration guarantees, and what this test re-establishes</h2>
 *
 * <p>This is the only one of the eleven tables seeded by {@code V4__seed_user_security.sql} rather than
 * by {@code V3__seed_reference_data.sql}: after V3 the table is <strong>empty</strong>, and V3 raises
 * rather than continues if it is not. V4 then loads exactly ten identities, five carrying the
 * administrative role code and five the standard one. Both seeds sit above the production version
 * ceiling, so no production deployment receives a seeded login. V4 verifies its own content in SQL;
 * {@link SeedContract} verifies the same facts a second time <em>through the repository</em>, which is
 * the surface every caller actually uses and the only one that can show a mapping defect turning a
 * correct row into a wrong object.
 *
 * <h2>The credential column is the migration's flagship parity exception</h2>
 *
 * <p>The legacy record holds the credential as eight cleartext characters at record offset 48 and the
 * legacy sign-on path compares it for direct equality. Storing cleartext would satisfy byte parity and
 * breach the binding no-hardcoded-credentials requirement at the same time, so {@code sec_usr_pwd} is
 * {@code VARCHAR(60)} - sized for a BCrypt digest rather than the legacy width of 8. It is the one
 * column in the eleven-table schema widened <em>for a credential</em>; the migrated schema widens two
 * others, {@code customer.cust_ssn} and {@code customer.govt_issued_id}, and both of those are widened
 * to hold a value protected at rest rather than a digest. Every remaining column matches its legacy
 * byte width. That divergence is a deliberate, documented improvement rather than a behavioural
 * regression, and it is recorded as such in {@code docs/decision-log.md}.
 *
 * <p>Two consequences shape this test. No credential-matching finder exists or can exist - an
 * independently salted digest cannot be matched by equality, and the legacy flow reads by key and only
 * then compares - so nothing here queries by credential. And the legacy cleartext value appears nowhere
 * in this file, in any form: the only credential-shaped values used are produced by
 * {@link TestDataFactory}, which owns credential handling for the whole test estate, and the assertions
 * about a stored digest are confined to its shape, its length, its distinctness and its unlikeness to
 * the row's own visible fields.
 *
 * <h2>The role code is stored unconstrained, and that tolerance is asserted rather than trusted</h2>
 *
 * <p>{@code sec_usr_type} is a raw one-character string. It carries no enumerated mapping, no converter,
 * no check constraint and no validation pattern, because the legacy sign-on path tests <em>only</em> the
 * administrative code and reaches the main menu through an <strong>unconditional</strong> alternative -
 * there is no third branch and no error path for a code the estate never declared, so every other value
 * routes without raising anything. Constraining the column would refuse a row the legacy system stored
 * and routed, which is a regression rather than a hardening. The enumerated form lives in the service
 * tier and is deliberately not referenced from this package. Recorded as {@code docs/decision-log.md}
 * DL-194.
 *
 * <p>Tolerance nothing exercises is tolerance a later change can remove silently, because every seeded
 * row carries one of the two declared codes and no assertion over seeded data would notice a new
 * constraint. So {@link KeyedAccess#storesOneIdentityAndRemovesItAgain()} writes a purpose-built row
 * whose code is neither declared value and proves it persists and loads intact, while
 * {@link SeedContract#splitsTheTenIdentitiesFiveAndFiveByRoleCode()} proves the delivered data
 * nonetheless contains only the two. The column <em>accepts</em> a third code; the seed <em>contains</em>
 * none.
 *
 * <h2>Nothing about this table is logged</h2>
 *
 * <p>No identifier, no name, no role code and no digest is written to a log by this test, by the
 * repository or by the entity, whose rendering carries the identifier alone. The package
 * {@code com.carddemo.repository} is deliberately absent from the loggers pinned in
 * {@code logback-spring.xml}, so nothing raises this package's level by configuration either.
 *
 * <h2>What this test does not assert, because it belongs to another layer</h2>
 *
 * <p>The sign-on flow publishes seven externally observable message texts - two entry prompts, a
 * wrong-credential message, an unknown-identifier message, an unable-to-verify message and two common
 * messages for the exit key and an unmapped key - and every one of them is produced by the service
 * tier. This repository's whole contribution to that contract is the <em>empty result</em> for an
 * identifier no row carries, which {@link KeyedAccess#answersEmptyForAnAbsentIdentifier()} asserts. No
 * message text is asserted here. Hashing, verification and role resolution are likewise elsewhere: the
 * enumerated role type is a service-tier concern and is deliberately not referenced from this package.
 *
 * <h2>Provenance</h2>
 *
 * <p>The table is the relational form of the 80-byte {@code SEC-USER-DATA} record in copybook
 * {@code CSUSR01Y}, whose five mapped fields end at offset 57 where a named 23-byte filler begins;
 * that filler is reconstructed from the declared record width on output and is neither an attribute nor
 * a column. The record layout has the fifth-highest fan-out in the estate - twelve programs include
 * the copybook - and the data set behind it is one of the file resources registered to the online
 * transaction manager. Its Java consumers are the authentication service backing the sign-on
 * transaction, the user-management service backing the four administrative user transactions, and the
 * security configuration that derives the administrative gate from the role code.
 *
 * <p>The ten seeded identities - five administrative, five standard - are the ones
 * {@code app/jcl/DUSRSECJ.jcl} supplies in stream at L35-L44 as <strong>ASCII card images</strong>,
 * written to a physical sequential data set at a fixed record length of 80 with no indexed cluster of
 * its own on that step. Because the content originates in stream in ASCII rather than in the mainframe
 * encoding, no EBCDIC decode is needed to reproduce it - which is why the mainframe user-security data
 * set having no ASCII twin costs nothing, its 800 bytes being exactly ten records of 80.
 *
 * <p>Legacy estate at checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19; the stamp is a provenance string for the
 * traceability matrix header and is not asserted against any individual member. No legacy source text
 * is reproduced here: member names, field names, byte offsets, widths, record lengths, line references
 * and row counts are metadata describing where a mapping came from.
 */
@DisplayName("Credential repository: closed surface, projected reads and keyset paging")
final class UserSecurityRepositoryIT extends AbstractPostgresIT {

    /**
     * The number of identities the sign-on seed inserts.
     *
     * <p>Read from {@link TestDataFactory#SEEDED_USER_COUNT} rather than restated, so this test and the
     * shared fixture contract cannot disagree about how many rows the seed owns.
     */
    private static final int SEEDED_IDENTITIES = TestDataFactory.SEEDED_USER_COUNT;

    /** How many of the ten seeded identities carry the administrative role code. */
    private static final int SEEDED_ADMINISTRATORS = 5;

    /** How many of the ten seeded identities carry the standard role code. */
    private static final int SEEDED_STANDARD_USERS = 5;

    /** The administrative role code, the one value the legacy sign-on path tests for. */
    private static final String ADMINISTRATIVE_ROLE_CODE = "A";

    /** The standard role code, reached through the legacy unconditional alternative. */
    private static final String STANDARD_ROLE_CODE = "U";

    /** The page size the legacy administrative browse presents, from a screen table occurring 10 times. */
    private static final int ADMIN_PAGE_SIZE = 10;

    /** The attribute the browse sorts on, which is the primary key. */
    private static final String SORT_ATTRIBUTE = "secUsrId";

    /** An identifier no seeded row carries, used for the absence and write assertions. */
    private static final String UNSEEDED_ID = "ZZTEST01";

    /**
     * A second reserved identifier, so a string-fidelity fixture can coexist with the write fixture.
     *
     * <p>Eight characters, because the entity refuses any other width immediately before the write, and
     * inside the same upper-case-and-digit domain as {@link #UNSEEDED_ID} for the collation reason
     * recorded on {@link #ABOVE_EVERY_IDENTIFIER}.
     */
    private static final String UNSEEDED_ID_SECOND = "ZZTEST02";

    /** Width of both name columns, from the record layout: 20 characters each. */
    private static final int NAME_COLUMN_WIDTH = 20;

    /** The table this repository maps, named once for the catalogue queries. */
    private static final String TABLE_NAME = "user_security";

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

    /**
     * A value shaped like a stored digest, so the entity accepts it, that hashes nothing whatever.
     *
     * <p>Taken from {@link TestDataFactory#SYNTHETIC_BCRYPT_DIGEST} rather than written out here, for
     * three reasons. The shared fixture owns credential handling for the whole test estate, so a value
     * standing in for a credential belongs there and not in a test file. Its body spells out what it is,
     * it is derived from no value at all, and a match against it always fails - so it can be read in a
     * diff without anyone having to establish whether it is a real credential. And it carries the
     * module's own cost factor, which is what lets {@link TestDataFactory#hasStoredDigestShape(String)}
     * be applied to a row this test writes as well as to a row the seed wrote.
     */
    private static final String DIGEST = TestDataFactory.SYNTHETIC_BCRYPT_DIGEST;

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
     * <p>{@code app/cbl/COUSR02C.cbl} L322-L331 and {@code app/cbl/COUSR03C.cbl} L269-L278 both take a
     * keyed read <em>for update</em>, which holds the record until the rewrite at L360 or the delete at
     * L307 - and that delete names no record identifier at all, so the held record is the only one it
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
        @DisplayName("the same opening page requested descending comes back descending, because the "
                + "sort is the caller's and this method imposes none of its own")
        void opensTheBrowseDescendingWhenTheCallerAsksForIt() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);
                final List<String> everyIdentifierAscending = identifiersOf(
                        openingPage(repository, (int) totalIdentities(repository)));

                final List<String> ascending = identifiersOf(repository.findAllProjectedBy(
                                PageRequest.of(0, ADMIN_PAGE_SIZE, Sort.by(SORT_ATTRIBUTE)))
                        .getContent());
                final List<String> descending = identifiersOf(repository.findAllProjectedBy(
                                PageRequest.of(0, ADMIN_PAGE_SIZE,
                                        Sort.by(SORT_ATTRIBUTE).descending()))
                        .getContent());

                assertThat(ascending)
                        .as("an ORDERED list and never a set: the sequence IS the property under test, "
                                + "and a set comparison would discard exactly the thing being checked")
                        .hasSize(ADMIN_PAGE_SIZE)
                        .isSorted()
                        .containsExactlyElementsOf(
                                everyIdentifierAscending.subList(0, ADMIN_PAGE_SIZE));
                assertThat(descending)
                        .as("the descending page is the OTHER END of the same table, still an ordered "
                                + "list, which is what proves the repository imposes no order of its "
                                + "own - the legacy browse fills backwards as well as forwards, and an "
                                + "imposed order would break one of the two directions")
                        .hasSize(ADMIN_PAGE_SIZE)
                        .isSortedAccordingTo(Comparator.reverseOrder())
                        .containsExactlyElementsOf(everyIdentifierAscending
                                .subList(everyIdentifierAscending.size() - ADMIN_PAGE_SIZE,
                                        everyIdentifierAscending.size())
                                .reversed());
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

    /**
     * Verifies, through the repository, exactly what the sign-on seed put in this table.
     *
     * <p>This is the one table of the eleven whose rows arrive from {@code V4__seed_user_security.sql}
     * rather than from {@code V3__seed_reference_data.sql}: V3 leaves it <strong>empty</strong> and
     * raises if it is not, and V4 then loads the ten identities. Both sit above the production version
     * ceiling, so a production deployment receives neither.
     *
     * <p>V4 already checks its own content in SQL, and these assertions are not that check repeated.
     * They read the same rows <em>through the mapping every caller uses</em>, which is the only surface
     * on which a correct row can still become a wrong object - a column bound to the wrong attribute, a
     * width silently truncating, a projection widened to select the credential. The identity data is
     * taken from {@link TestDataFactory#SEEDED_IDENTITIES} so that this file and the shared fixture
     * contract are one statement of the ten identities rather than two that can drift.
     */
    @Nested
    @DisplayName("the sign-on seed delivers ten identities, five administrative and five standard")
    final class SeedContract {

        /** Creates the nest. */
        SeedContract() {
        }

        @Test
        @DisplayName("holds exactly ten rows once all four migrations have been applied, which is the "
                + "count V4 owns and V3 deliberately leaves at zero")
        void holdsExactlyTenIdentities() {
            runner().run(context -> assertThat(
                    totalIdentities(context.getBean(UserSecurityRepository.class)))
                    .as("V3 leaves this table empty and raises if it is not; V4 is the migration that "
                            + "owns these ten rows, and the closed interface publishes no count() of "
                            + "its own, so the total is one bounded range aggregate over a sentinel key")
                    .isEqualTo(SEEDED_IDENTITIES));
        }

        @Test
        @DisplayName("splits those ten exactly five and five between the administrative role code and "
                + "the standard one, because the role code is the sole authority for the split")
        void splitsTheTenIdentitiesFiveAndFiveByRoleCode() {
            runner().run(context -> {
                final List<String> roleCodes = everyIdentity(
                        context.getBean(UserSecurityRepository.class))
                        .stream()
                        .map(UserSecurityRepository.AdminEntry::getSecUsrType)
                        .toList();

                assertThat(roleCodes).hasSize(SEEDED_IDENTITIES);
                assertThat(roleCodes.stream().filter(ADMINISTRATIVE_ROLE_CODE::equals).count())
                        .as("a count that stayed at ten while a role code flipped would move an "
                                + "operator to the other side of the authorization gate unnoticed")
                        .isEqualTo(SEEDED_ADMINISTRATORS);
                assertThat(roleCodes.stream().filter(STANDARD_ROLE_CODE::equals).count())
                        .isEqualTo(SEEDED_STANDARD_USERS);
                assertThat(roleCodes)
                        .as("and no seeded row carries a third code, even though the column would "
                                + "accept one - see the unexpected-code assertion for why it must")
                        .containsOnly(ADMINISTRATIVE_ROLE_CODE, STANDARD_ROLE_CODE);
            });
        }

        @Test
        @DisplayName("carries each of the ten identities under its own identifier, given name, family "
                + "name and role code - identity only, never a credential")
        void carriesEachSeededIdentityByNameAndRoleCode() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);

                for (final SeededIdentity expected : TestDataFactory.SEEDED_IDENTITIES) {
                    final Optional<UserSecurityRepository.AdminEntry> row =
                            repository.findProjectedBySecUsrId(expected.userId());

                    assertThat(row)
                            .as("every seeded identifier resolves, and the projected read is the one "
                                    + "the administrative list uses, so it reaches no digest at all")
                            .isPresent();
                    final UserSecurityRepository.AdminEntry entry = row.orElseThrow();
                    assertThat(entry.getSecUsrId()).isEqualTo(expected.userId());
                    assertThat(entry.getSecUsrFname()).isEqualTo(expected.firstName());
                    assertThat(entry.getSecUsrLname()).isEqualTo(expected.lastName());
                    assertThat(entry.getSecUsrType()).isEqualTo(expected.userTypeCode());
                }

                assertThat(identifiersOf(everyIdentity(repository)))
                        .as("and the table holds those ten and no eleventh, in identifier order")
                        .containsExactlyElementsOf(TestDataFactory.SEEDED_IDENTITIES.stream()
                                .map(SeededIdentity::userId)
                                .sorted()
                                .toList());
            });
        }

        @Test
        @DisplayName("stores every one of the ten credentials as a full-length digest carrying a "
                + "recognised version marker, and stores ten DISTINCT digests")
        void storesTenDistinctFullLengthDigests() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);
                final List<String> digests = seededDigests(repository);

                assertThat(digests).hasSize(SEEDED_IDENTITIES);
                for (final String digest : digests) {
                    assertThat(digest.length())
                            .as("the column is 60 wide for a digest and a stored value shorter than "
                                    + "that is the shape a cleartext credential would have")
                            .isEqualTo(TestDataFactory.BCRYPT_DIGEST_LENGTH);
                    assertThat(TestDataFactory.BCRYPT_VERSION_MARKERS)
                            .as("a stored value must open with a recognised version marker")
                            .anySatisfy(marker -> assertThat(digest).startsWith(marker));
                    assertThat(TestDataFactory.hasStoredDigestShape(digest))
                            .as("length, marker and the module's own cost factor together, checked by "
                                    + "the shared fixture that owns credential handling")
                            .isTrue();
                }
                assertThat(digests)
                        .as("TEN DISTINCT VALUES from one shared input is the observable consequence of "
                                + "ten independent salts; a repeated digest would mean an unsalted or "
                                + "copied value, and replacing any one of them would stop being "
                                + "detectable")
                        .doesNotHaveDuplicates()
                        .hasSize(TestDataFactory.SEEDED_DIGEST_COUNT);
            });
        }

        @Test
        @DisplayName("stores NO cleartext credential: no row's stored value is any of that row's own "
                + "four visible fields, and none of them is short enough to be one")
        void storesNoCleartextCredential() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);

                for (final SeededIdentity identity : TestDataFactory.SEEDED_IDENTITIES) {
                    final UserSecurity row = repository.findById(identity.userId()).orElseThrow();
                    final String stored = row.credentialDigest();

                    assertThat(stored)
                            .as("a stored value equal to a field of its own row would be a value in "
                                    + "clear, whatever else it was")
                            .isNotEqualTo(row.getSecUsrId())
                            .isNotEqualTo(row.getSecUsrFname())
                            .isNotEqualTo(row.getSecUsrLname())
                            .isNotEqualTo(row.getSecUsrType());
                    assertThat(stored.length())
                            .as("and it is 60 characters, which the legacy field's 8 bytes cannot be")
                            .isEqualTo(TestDataFactory.BCRYPT_DIGEST_LENGTH)
                            .isNotEqualTo(UserSecurity.SEC_USR_ID_WIDTH);
                }
            });
        }

        @Test
        @DisplayName("round-trips all sixty characters of a genuinely produced digest, so the ONE "
                + "deliberate width divergence in the schema is honoured end to end")
        void roundTripsAllSixtyCharactersOfADigest() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);
                final String freshDigest = TestDataFactory.digestOfFixtureCredentialWindow();
                try {
                    repository.save(new UserSecurity(UNSEEDED_ID, "TESTFIRST", "TESTLAST",
                            freshDigest, STANDARD_ROLE_CODE));

                    final String reloaded =
                            repository.findById(UNSEEDED_ID).orElseThrow().credentialDigest();

                    assertThat(reloaded.length())
                            .as("a column left at the legacy width of 8 would truncate or refuse this "
                                    + "value; 60 is the deliberate divergence, and this is what proves "
                                    + "the storage honours it end to end rather than only declaring it")
                            .isEqualTo(TestDataFactory.BCRYPT_DIGEST_LENGTH);
                    assertThat(reloaded)
                            .as("byte for byte, because a digest truncated by even one character "
                                    + "verifies nothing and fails silently rather than loudly")
                            .isEqualTo(freshDigest);
                    assertThat(TestDataFactory.hasStoredDigestShape(reloaded)).isTrue();
                } finally {
                    repository.deleteById(UNSEEDED_ID);
                }
                assertThat(repository.findById(UNSEEDED_ID)).isEmpty();
                assertThat(totalIdentities(repository)).isEqualTo(SEEDED_IDENTITIES);
            });
        }
    }

    /**
     * Verifies that a stored string comes back exactly as it went in.
     *
     * <p>Two properties, and they are not the same one. The seeded names are stored <em>unpadded</em> -
     * the migration writes them at their natural length - so the mapping must return them at that
     * length and must not pad them out to the column width. And a value that genuinely carries trailing
     * blanks must keep them, because both name columns are bounded {@code VARCHAR} rather than
     * {@code CHAR}: the server neither pads a short value nor strips a padded one, and nothing in the
     * mapping trims either. Both directions matter to a caller reproducing a fixed-width record image,
     * where the padding is part of the layout rather than noise.
     *
     * <p>No assertion in this class trims or strips the value it is asserting on. Doing so would make
     * the two properties above indistinguishable and would let a mapping that quietly normalised
     * whitespace pass.
     */
    @Nested
    @DisplayName("a stored name comes back exactly as stored, neither padded nor trimmed")
    final class StoredStringFidelity {

        /** Creates the nest. */
        StoredStringFidelity() {
        }

        @Test
        @DisplayName("returns each seeded name at its own stored length, because the migration seeds it "
                + "unpadded and a bounded VARCHAR pads nothing")
        void returnsEachSeededNameAtItsStoredLength() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);

                for (final SeededIdentity identity : TestDataFactory.SEEDED_IDENTITIES) {
                    final UserSecurity row = repository.findById(identity.userId()).orElseThrow();

                    assertThat(row.getSecUsrFname())
                            .as("exactly what the seed wrote - not widened to the column width, and "
                                    + "not narrowed by a normalisation the mapping must not perform")
                            .isEqualTo(identity.firstName())
                            .hasSize(identity.firstName().length());
                    assertThat(row.getSecUsrLname())
                            .isEqualTo(identity.lastName())
                            .hasSize(identity.lastName().length());
                    assertThat(row.getSecUsrId())
                            .as("and the key stays at the layout's own width")
                            .hasSize(UserSecurity.SEC_USR_ID_WIDTH);
                }
            });
        }

        @Test
        @DisplayName("keeps every trailing blank of a name written at the full column width, which is "
                + "what a caller reproducing a fixed-width record image depends on")
        void keepsTheTrailingBlanksOfAFullWidthName() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);
                final String paddedFirstName =
                        TestDataFactory.padded("PADDEDFIRST", NAME_COLUMN_WIDTH, ' ', "secUsrFname");
                final String paddedLastName =
                        TestDataFactory.padded("PADDEDLAST", NAME_COLUMN_WIDTH, ' ', "secUsrLname");
                try {
                    repository.save(new UserSecurity(UNSEEDED_ID_SECOND, paddedFirstName,
                            paddedLastName, DIGEST, STANDARD_ROLE_CODE));

                    final UserSecurity reloaded =
                            repository.findById(UNSEEDED_ID_SECOND).orElseThrow();

                    assertThat(reloaded.getSecUsrFname())
                            .as("a CHAR column would have padded a short value and a trimming mapping "
                                    + "would have stripped this one; the layout needs neither")
                            .isEqualTo(paddedFirstName)
                            .hasSize(NAME_COLUMN_WIDTH);
                    assertThat(reloaded.getSecUsrLname())
                            .isEqualTo(paddedLastName)
                            .hasSize(NAME_COLUMN_WIDTH);
                } finally {
                    repository.deleteById(UNSEEDED_ID_SECOND);
                }
                assertThat(repository.findById(UNSEEDED_ID_SECOND)).isEmpty();
                assertThat(totalIdentities(repository)).isEqualTo(SEEDED_IDENTITIES);
            });
        }
    }

    /**
     * Verifies the shape of the migrated table this repository reads, straight from the catalogue.
     *
     * <p>Three absences are asserted, and each is load-bearing rather than cosmetic. The table carries
     * <strong>no foreign key</strong> in either direction, which is why the entity declares no
     * association and why the repository publishes no join; the six foreign keys the index migration
     * creates all lie between other tables. It carries <strong>no secondary index</strong>: the three
     * B-tree indexes that migration creates stand in for alternate indexes on the card and transaction
     * tables, and the only index here is the one the primary key implies. And it carries <strong>no
     * sequence and no generated column</strong>, because the key is the eight-character business
     * identifier taken from the leading bytes of the record image, and a surrogate key would break the
     * record-image-to-row correspondence the byte-equivalence gate depends on.
     *
     * <p>Every query below is a complete literal with its one value bound as a parameter, so nothing is
     * assembled from a string.
     */
    @Nested
    @DisplayName("the migrated table carries no foreign key, no secondary index and no sequence")
    final class SchemaShape {

        /** Creates the nest. */
        SchemaShape() {
        }

        @Test
        @DisplayName("carries no foreign key in either direction, which is why no association is mapped")
        void carriesNoForeignKeyInEitherDirection() {
            runner().run(context -> {
                final JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM information_schema.table_constraints
                         WHERE table_schema = 'public'
                           AND constraint_type = 'FOREIGN KEY'
                           AND table_name = ?
                        """, Integer.class, TABLE_NAME))
                        .as("an outgoing foreign key would make this table depend on another, and the "
                                + "sign-on read would then be a join")
                        .isZero();
                assertThat(jdbc.queryForObject("""
                        SELECT count(*)
                          FROM information_schema.referential_constraints rc
                          JOIN information_schema.constraint_column_usage ccu
                            ON ccu.constraint_name = rc.unique_constraint_name
                           AND ccu.constraint_schema = rc.unique_constraint_schema
                         WHERE ccu.table_schema = 'public'
                           AND ccu.table_name = ?
                        """, Integer.class, TABLE_NAME))
                        .as("and an incoming one would stop a delete of an identity from being the "
                                + "single-row removal the administrative transaction issues")
                        .isZero();
            });
        }

        @Test
        @DisplayName("carries no secondary index - the primary key's own unique index is the only one, "
                + "because the three the index migration adds are on other tables")
        void carriesNoSecondaryIndex() {
            runner().run(context -> {
                final JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

                final List<String> indexNames = jdbc.queryForList("""
                        SELECT indexname FROM pg_indexes
                         WHERE schemaname = 'public'
                           AND tablename = ?
                         ORDER BY indexname
                        """, String.class, TABLE_NAME);

                assertThat(indexNames)
                        .as("exactly one index, and it is the one the primary key constraint implies - "
                                + "the legacy data set behind this table had no alternate index either")
                        .containsExactly("pk_" + TABLE_NAME);
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM information_schema.table_constraints
                         WHERE table_schema = 'public'
                           AND constraint_type = 'PRIMARY KEY'
                           AND table_name = ?
                        """, Integer.class, TABLE_NAME))
                        .as("and that one index really is a primary key rather than a bare unique index")
                        .isEqualTo(1);
            });
        }

        @Test
        @DisplayName("carries no sequence and no generated column, because the key is the business "
                + "identifier and never a surrogate")
        void carriesNoSequenceAndNoGeneratedColumn() {
            runner().run(context -> {
                final JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM information_schema.columns
                         WHERE table_schema = 'public'
                           AND table_name = ?
                           AND (is_identity = 'YES'
                                OR is_generated <> 'NEVER'
                                OR column_default IS NOT NULL)
                        """, Integer.class, TABLE_NAME))
                        .as("a generated, identity or defaulted key column would replace the business "
                                + "identifier the record image carries in its leading eight bytes")
                        .isZero();
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM information_schema.sequences
                         WHERE sequence_schema = 'public'
                           AND sequence_name LIKE ?
                        """, Integer.class, TABLE_NAME + "%"))
                        .as("and no sequence exists for this table under any name")
                        .isZero();
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
     * Reads every identity the table holds, ascending, as the credential-free projection.
     *
     * <p>Sized from {@link #totalIdentities(UserSecurityRepository)} rather than from a fixed number, so
     * a table holding more rows than expected produces a failing count assertion rather than a silently
     * truncated window that satisfies one.
     *
     * @param repository the repository to read through
     * @return every projected row, in ascending identifier order
     */
    private static List<UserSecurityRepository.AdminEntry> everyIdentity(
            final UserSecurityRepository repository) {
        return openingPage(repository, (int) totalIdentities(repository));
    }

    /**
     * Reads the stored digest of each seeded identity, in identifier order.
     *
     * <p>The keyed entity read is the one operation that brings a digest into memory at all, so the
     * values are collected here, compared for length, marker and distinctness by the caller, and never
     * logged, printed or rendered into an assertion message.
     *
     * @param repository the repository to read through
     * @return the ten stored digests
     */
    private static List<String> seededDigests(final UserSecurityRepository repository) {
        return TestDataFactory.SEEDED_IDENTITIES.stream()
                .map(SeededIdentity::userId)
                .sorted()
                .map(userId -> repository.findById(userId).orElseThrow())
                .map(UserSecurity::credentialDigest)
                .toList();
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
