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

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.carddemo.domain.UserSecurity;
import com.carddemo.support.AbstractPostgresIT;

/**
 * Drives the credential repository against a real migrated PostgreSQL server and verifies its frozen
 * shape: an empty interface extending {@code JpaRepository<UserSecurity, String>}, with every operation
 * inherited and no custom finder or projection.
 *
 * <h2>Why this has to boot a real context</h2>
 *
 * <p>Only assembling the persistence context proves that the inherited store operations bind to the
 * entity and that all five columns - including the intentional 60-character credential-digest column -
 * match the real PostgreSQL schema under {@code ddl-auto=validate}. The test therefore exercises the
 * inherited CRUD and pageable methods rather than replacing them with mocks or test-only finders.
 *
 * <h2>Why the administrative browse now uses entities</h2>
 *
 * <p>The frozen contract deliberately declares no projection. {@code UserManagementService} requests a
 * bounded ten-row page through inherited {@code findAll(Pageable)} and reads only identity and role
 * fields. This test proves that pageable contract and keeps the digest behind the entity's deliberately
 * non-bean-property accessor; rendering policy remains in the service, where it belongs.
 *
 * <h2>Provenance</h2>
 *
 * <p>The table is the relational form of the 80-byte {@code SEC-USER-DATA} record in copybook
 * {@code CSUSR01Y}; the ten seeded identities - five administrative, five standard - are the ones
 * {@code app/jcl/DUSRSECJ.jcl} supplies in stream. Legacy estate at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced here.
 */
@DisplayName("Credential repository: inherited JPA operations and zero custom methods")
final class UserSecurityRepositoryIT extends AbstractPostgresIT {

    /** The number of identities the sign-on seed inserts. */
    private static final int SEEDED_IDENTITIES = 10;

    /** The page size the legacy administrative browse presents, from a screen table occurring 10 times. */
    private static final int ADMIN_PAGE_SIZE = 10;

    /** The attribute the browse sorts on. */
    private static final String SORT_ATTRIBUTE = "secUsrId";

    /** An identifier no seeded row carries, used for the absence and write assertions. */
    private static final String UNSEEDED_ID = "ZZTEST01";

    /** A BCrypt digest of the expected form, so the entity accepts it. */
    private static final String DIGEST =
            "$2a$10$SYNTHETICDIGESTFORREPOSITORYITONLYNOTACREDENTIAL00001";

    /** The bean-property accessor a credential column would bind to, which must exist nowhere. */
    private static final String FORBIDDEN_ACCESSOR = "getSecUsrPwd";

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

    /** Verifies the inherited operations used by authentication and user administration. */
    @Nested
    @DisplayName("the inherited operations work against the real schema")
    final class InheritedOperations {

        /** Creates the nest. */
        InheritedOperations() {
        }

        @Test
        @DisplayName("bootstraps at all, which a signature the framework could not resolve would "
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
                final String seededId = anySeededIdentifier(repository);

                final Optional<UserSecurity> found = repository.findById(seededId);

                assertThat(found).isPresent();
                assertThat(found.orElseThrow().getSecUsrId()).isEqualTo(seededId);
                assertThat(found.orElseThrow().credentialDigest())
                        .as("the keyed read is the one path a digest legitimately reaches memory by")
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
        @DisplayName("pages the administrative browse at the legacy page size, in both directions, "
                + "because the legacy screen fills backwards as well as forwards")
        void pagesTheAdministrativeBrowseInBothDirections() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);

                final Page<UserSecurity> ascending =
                        repository.findAll(PageRequest.of(0, ADMIN_PAGE_SIZE,
                                Sort.by(Sort.Direction.ASC, SORT_ATTRIBUTE)));
                final Page<UserSecurity> descending =
                        repository.findAll(PageRequest.of(0, ADMIN_PAGE_SIZE,
                                Sort.by(Sort.Direction.DESC, SORT_ATTRIBUTE)));

                assertThat(ascending.getTotalElements())
                        .as("the sign-on seed inserts %d identities", SEEDED_IDENTITIES)
                        .isGreaterThanOrEqualTo(SEEDED_IDENTITIES);
                assertThat(ascending.getContent()).hasSize(ADMIN_PAGE_SIZE);
                assertThat(identifiersOf(ascending)).isSorted();
                assertThat(identifiersOf(descending))
                        .as("the sort the caller asks for is the sort applied, because this interface "
                                + "imposes none of its own")
                        .isSortedAccordingTo(java.util.Comparator.reverseOrder());
            });
        }

        @Test
        @DisplayName("successive offset pages preserve the explicit identifier order without a gap or "
                + "duplicate, replacing the former test-only keyset finders")
        void successivePagesPreserveTheExplicitIdentifierOrder() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);
                final long originalCount = repository.count();
                try {
                    repository.save(new UserSecurity(
                            UNSEEDED_ID, "TESTFIRST", "TESTLAST", DIGEST, "U"));

                    final Sort ascending = Sort.by(Sort.Direction.ASC, SORT_ATTRIBUTE);
                    final Page<UserSecurity> firstPage =
                            repository.findAll(PageRequest.of(0, ADMIN_PAGE_SIZE, ascending));
                    final Page<UserSecurity> secondPage =
                            repository.findAll(PageRequest.of(1, ADMIN_PAGE_SIZE, ascending));
                    final Page<UserSecurity> completeWindow =
                            repository.findAll(PageRequest.of(0, ADMIN_PAGE_SIZE + 1, ascending));
                    final List<String> observed = java.util.stream.Stream.concat(
                                    firstPage.stream(), secondPage.stream())
                            .map(UserSecurity::getSecUsrId)
                            .toList();

                    assertThat(firstPage.getContent()).hasSize(ADMIN_PAGE_SIZE);
                    assertThat(firstPage.hasNext()).isTrue();
                    assertThat(secondPage.getContent())
                            .extracting(UserSecurity::getSecUsrId)
                            .containsExactly(UNSEEDED_ID);
                    assertThat(observed)
                            .as("one ten-row page plus its successor equals one eleven-row window")
                            .containsExactlyElementsOf(completeWindow.stream()
                                    .map(UserSecurity::getSecUsrId)
                                    .toList());
                } finally {
                    repository.deleteById(UNSEEDED_ID);
                }
                assertThat(repository.count()).isEqualTo(originalCount);
            });
        }

        @Test
        @DisplayName("populates every non-credential field used by the bounded administrative page")
        void populatesEveryAdministrativeField() {
            runner().run(context -> {
                final UserSecurity entry =
                        context.getBean(UserSecurityRepository.class)
                                .findAll(PageRequest.of(0, 1,
                                        Sort.by(Sort.Direction.ASC, SORT_ATTRIBUTE)))
                                .getContent().get(0);

                assertThat(entry.getSecUsrId()).isNotBlank();
                assertThat(entry.getSecUsrFname()).isNotBlank();
                assertThat(entry.getSecUsrLname()).isNotBlank();
                assertThat(entry.getSecUsrType())
                        .as("the role code is returned raw, because the service applies the legacy "
                                + "unconditional alternative to whatever it holds")
                        .isNotBlank();
            });
        }

        @Test
        @DisplayName("stores one identity and removes it again, which is the whole write surface the "
                + "four administrative transactions need")
        void storesOneIdentityAndRemovesItAgain() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);
                final long seededCount = repository.count();
                try {
                    repository.save(new UserSecurity(
                            UNSEEDED_ID, "TESTFIRST", "TESTLAST", DIGEST, "X"));
                    assertThat(repository.findById(UNSEEDED_ID))
                            .as("save covers insert through the provider's merge semantics")
                            .get()
                            .extracting(UserSecurity::getSecUsrType)
                            .as("the raw role column accepts the legacy unconditional-alternative case")
                            .isEqualTo("X");
                    assertThat(repository.count()).isEqualTo(seededCount + 1);

                    repository.save(new UserSecurity(
                            UNSEEDED_ID, "CHANGED", "TESTLAST", DIGEST, "A"));
                    assertThat(repository.findById(UNSEEDED_ID).orElseThrow().getSecUsrFname())
                            .as("and update through the same one method, one record at a time")
                            .isEqualTo("CHANGED");
                    assertThat(repository.count())
                            .as("updating the business-key row does not insert another identity")
                            .isEqualTo(seededCount + 1);
                } finally {
                    repository.deleteById(UNSEEDED_ID);
                }
                assertThat(repository.findById(UNSEEDED_ID))
                        .as("the delete removes the one row it is given")
                        .isEmpty();
                assertThat(repository.count())
                        .as("AND LEAVES THE SEEDED ROWS ALONE. There is no bulk delete on this "
                                + "interface, and this is the assertion that would notice one")
                        .isEqualTo(seededCount);
            });
        }
    }

    /** Locks down the empty custom surface of the frozen repository interface. */
    @Nested
    @DisplayName("the interface declares no custom persistence operation")
    final class FrozenInterfaceContract {

        /** Creates the nest. */
        FrozenInterfaceContract() {
        }

        @Test
        @DisplayName("extends JpaRepository directly so every supported operation is inherited")
        void extendsJpaRepositoryDirectly() {
            assertThat(UserSecurityRepository.class.getInterfaces())
                    .containsExactly(org.springframework.data.jpa.repository.JpaRepository.class);
        }

        @Test
        @DisplayName("declares zero methods and zero nested projection types")
        void declaresNoCustomMethodOrProjection() {
            assertThat(UserSecurityRepository.class.getDeclaredMethods()).isEmpty();
            assertThat(UserSecurityRepository.class.getDeclaredClasses()).isEmpty();
        }

        @Test
        @DisplayName("the entity keeps its digest behind a non-bean-property accessor")
        void theEntityKeepsItsDigestBehindANonBeanPropertyAccessor() {
            final List<String> entityMethods = List.of(UserSecurity.class.getDeclaredMethods()).stream()
                    .map(Method::getName)
                    .toList();

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
     * Returns the identifier of the first row the ascending browse reports.
     *
     * @param repository the repository to read through
     * @return a seeded identifier
     */
    private static String anySeededIdentifier(final UserSecurityRepository repository) {
        return repository.findAll(
                        PageRequest.of(0, 1, Sort.by(Sort.Direction.ASC, SORT_ATTRIBUTE)))
                .getContent().get(0).getSecUsrId();
    }

    /**
     * Returns one page's identifiers in page order.
     *
     * @param page the page to read
     * @return the identifiers, in the order the page presents them
     */
    private static List<String> identifiersOf(final Page<UserSecurity> page) {
        return page.getContent().stream()
                .map(UserSecurity::getSecUsrId)
                .map(identifier -> identifier.toUpperCase(Locale.ROOT))
                .toList();
    }
}
