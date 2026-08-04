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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.carddemo.domain.UserSecurity;
import com.carddemo.support.AbstractPostgresIT;

/**
 * Drives the credential repository against a real migrated PostgreSQL server and asserts both halves of
 * its least-privilege contract: that the four operations it declares actually work, and that the
 * operations it deliberately does not declare do not exist.
 *
 * <h2>Why this has to boot a real context</h2>
 *
 * <p>The interface extends the bare {@code Repository} marker rather than {@code JpaRepository}, which
 * means every one of its four methods is resolved by the framework at bootstrap - two against the
 * store's own CRUD implementation, one as a criteria-less derived query returning a projection. A
 * signature the framework cannot resolve is not a compilation error. It is a bootstrap failure, and a
 * narrowing that produced one would replace an over-broad repository with an application that does not
 * start. Only assembling the context proves otherwise, and only a real server proves the projection
 * query is valid SQL against the real schema.
 *
 * <h2>What the projection assertion is actually for</h2>
 *
 * <p>{@code AdminEntry} exists so that listing users cannot hydrate a BCrypt digest. Asserting that the
 * returned objects "do not contain a digest" by reading their four accessors would be circular - a
 * closed projection has no fifth accessor to read. So the assertion is made where it can fail: the
 * projection type is checked to declare <em>no</em> accessor that could bind to the credential column,
 * and the entity is checked to keep its digest behind a non-bean-property accessor, which is the
 * property that makes a conventionally-spelled accessor unable to resolve. Together those two say the
 * digest is unreachable through this path by construction rather than by convention.
 *
 * <h2>Provenance</h2>
 *
 * <p>The table is the relational form of the 80-byte {@code SEC-USER-DATA} record in copybook
 * {@code CSUSR01Y}; the ten seeded identities - five administrative, five standard - are the ones
 * {@code app/jcl/DUSRSECJ.jcl} supplies in stream. Legacy estate at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced here.
 */
@DisplayName("Credential repository: the four operations it grants, and the ones it withholds")
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

    @Nested
    @DisplayName("the four operations it grants work against the real schema")
    final class TheFourOperationsItGrants {

        /** Creates the nest. */
        TheFourOperationsItGrants() {
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

                final Page<UserSecurityRepository.AdminEntry> ascending =
                        repository.findAllProjectedBy(PageRequest.of(0, ADMIN_PAGE_SIZE,
                                Sort.by(Sort.Direction.ASC, SORT_ATTRIBUTE)));
                final Page<UserSecurityRepository.AdminEntry> descending =
                        repository.findAllProjectedBy(PageRequest.of(0, ADMIN_PAGE_SIZE,
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
        @DisplayName("resumes the browse strictly after a cursor and strictly before it, in the direction "
                + "each attention key asks for, and still projects no credential")
        void resumesTheBrowseFromEitherBoundaryCursor() {
            runner().run(context -> {
                final UserSecurityRepository repository = context.getBean(UserSecurityRepository.class);

                final List<String> firstPage = identifiersOf(repository.findAllProjectedBy(
                        PageRequest.of(0, ADMIN_PAGE_SIZE, Sort.by(Sort.Direction.ASC, SORT_ATTRIBUTE))));
                final String lastOnPage = firstPage.get(firstPage.size() - 1);
                final String firstOnPage = firstPage.get(0);

                assertThat(repository.findBySecUsrIdGreaterThanOrderBySecUsrIdAsc(
                        lastOnPage, Limit.of(ADMIN_PAGE_SIZE + 1)))
                        .as("strictly after the last identifier displayed, so it is not listed twice")
                        .extracting(UserSecurityRepository.AdminEntry::getSecUsrId)
                        .allSatisfy(identifier -> assertThat(identifier).isGreaterThan(lastOnPage));

                final List<UserSecurityRepository.AdminEntry> backward =
                        repository.findBySecUsrIdLessThanOrderBySecUsrIdDesc(
                                lastOnPage, Limit.of(ADMIN_PAGE_SIZE));

                assertThat(backward)
                        .as("strictly before the cursor, descending, which is the read order the legacy "
                                + "backward path uses before the service reverses it")
                        .extracting(UserSecurityRepository.AdminEntry::getSecUsrId)
                        .isSortedAccordingTo(java.util.Comparator.reverseOrder())
                        .doesNotContain(lastOnPage);
                assertThat(backward.reversed())
                        .extracting(UserSecurityRepository.AdminEntry::getSecUsrId)
                        .as("reversing recovers the ascending page the operator sees")
                        .startsWith(firstOnPage);
                assertThat(backward.get(0).getSecUsrLname())
                        .as("the keyset forms return the same closed projection, so the credential column "
                                + "is not selected on this path either")
                        .isNotNull();

                assertThat(repository.findBySecUsrIdLessThanOrderBySecUsrIdDesc(
                        firstOnPage, Limit.of(ADMIN_PAGE_SIZE)))
                        .as("nothing precedes the lowest identifier, which is how the first page is "
                                + "recognised without a count")
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("populates every projected column, so the projection is a narrowing rather than an "
                + "emptying")
        void populatesEveryProjectedColumn() {
            runner().run(context -> {
                final UserSecurityRepository.AdminEntry entry =
                        context.getBean(UserSecurityRepository.class)
                                .findAllProjectedBy(PageRequest.of(0, 1,
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

                repository.save(new UserSecurity(UNSEEDED_ID, "TESTFIRST", "TESTLAST", DIGEST, "U"));
                assertThat(repository.findById(UNSEEDED_ID))
                        .as("save covers insert through the provider's merge semantics")
                        .isPresent();

                repository.save(new UserSecurity(UNSEEDED_ID, "CHANGED", "TESTLAST", DIGEST, "A"));
                assertThat(repository.findById(UNSEEDED_ID).orElseThrow().getSecUsrFname())
                        .as("and update through the same one method, one record at a time")
                        .isEqualTo("CHANGED");

                repository.deleteById(UNSEEDED_ID);
                assertThat(repository.findById(UNSEEDED_ID))
                        .as("the delete removes the one row it is given")
                        .isEmpty();
                assertThat(repository.findAllProjectedBy(
                        PageRequest.of(0, ADMIN_PAGE_SIZE)).getTotalElements())
                        .as("AND LEAVES THE SEEDED ROWS ALONE. There is no bulk delete on this "
                                + "interface, and this is the assertion that would notice one")
                        .isGreaterThanOrEqualTo(SEEDED_IDENTITIES);
            });
        }
    }

    @Nested
    @DisplayName("the operations it withholds do not exist")
    final class TheOperationsItWithholds {

        /** Creates the nest. */
        TheOperationsItWithholds() {
        }

        @Test
        @DisplayName("does not extend the store-specific repository, which is what would grant every "
                + "withheld operation in one line")
        void doesNotExtendTheStoreSpecificRepository() {
            assertThat(UserSecurityRepository.class.getInterfaces())
                    .as("only the bare marker; extending JpaRepository or CrudRepository here would "
                            + "hand an unbounded read of every BCrypt digest to every injector")
                    .containsExactly(org.springframework.data.repository.Repository.class);
        }

        @ParameterizedTest(name = "no [{0}] operation")
        @ValueSource(strings = {
            "findAll", "findAllById", "saveAll", "saveAllAndFlush", "saveAndFlush", "flush",
            "delete", "deleteAll", "deleteAllById", "deleteAllInBatch", "deleteAllByIdInBatch",
            "deleteInBatch", "getReferenceById", "getById", "getOne", "existsById", "count",
            "findBy", "findOne"
        })
        @DisplayName("grants none of the operations the store-specific repository would have, each of "
                + "which is a real capability over a table of credentials")
        void grantsNoneOfTheWithheldOperations(final String operation) {
            assertThat(operationNames())
                    .as("%s must not be reachable from a bean that injects this repository", operation)
                    .doesNotContain(operation);
        }

        @Test
        @DisplayName("declares exactly the six operations it needs, so a seventh added later is a "
                + "deliberate widening rather than an inherited one")
        void declaresExactlySixOperations() {
            assertThat(operationNames())
                    .containsExactlyInAnyOrder("findById", "findAllProjectedBy", "save", "deleteById",
                            "findBySecUsrIdGreaterThanOrderBySecUsrIdAsc",
                            "findBySecUsrIdLessThanOrderBySecUsrIdDesc");
        }

        @Test
        @DisplayName("its projection declares no accessor that could bind to the credential column")
        void theProjectionDeclaresNoCredentialAccessor() {
            final List<String> accessors = List.of(
                    UserSecurityRepository.AdminEntry.class.getDeclaredMethods()).stream()
                    .map(Method::getName)
                    .toList();

            assertThat(accessors)
                    .as("a closed projection selects exactly the columns its accessors name, so the "
                            + "absence of this one is what keeps sec_usr_pwd out of the select")
                    .doesNotContain(FORBIDDEN_ACCESSOR)
                    .containsExactlyInAnyOrder("getSecUsrId", "getSecUsrFname", "getSecUsrLname",
                            "getSecUsrType");
        }

        @Test
        @DisplayName("and the entity keeps its digest behind a non-bean-property accessor, which is "
                + "what makes a conventionally-spelled projection accessor unable to resolve to it")
        void theEntityKeepsItsDigestBehindANonBeanPropertyAccessor() {
            final List<String> entityMethods = List.of(UserSecurity.class.getDeclaredMethods()).stream()
                    .map(Method::getName)
                    .toList();

            assertThat(entityMethods)
                    .as("were the digest exposed as %s, a projection could reach it by writing the "
                            + "conventional accessor and nothing would object", FORBIDDEN_ACCESSOR)
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
     * Returns the names of every operation reachable through this repository interface.
     *
     * <p>Reads the interface's own declarations and those of everything it extends, because an
     * inherited operation is reachable from a call site exactly as a declared one is - which is the
     * entire point of the finding this class covers.
     *
     * @return the reachable operation names, lower-cased nowhere and de-duplicated
     */
    private static List<String> operationNames() {
        return List.of(UserSecurityRepository.class.getMethods()).stream()
                .map(Method::getName)
                .distinct()
                .toList();
    }

    /**
     * Returns the identifier of the first row the ascending browse reports.
     *
     * @param repository the repository to read through
     * @return a seeded identifier
     */
    private static String anySeededIdentifier(final UserSecurityRepository repository) {
        return repository.findAllProjectedBy(
                        PageRequest.of(0, 1, Sort.by(Sort.Direction.ASC, SORT_ATTRIBUTE)))
                .getContent().get(0).getSecUsrId();
    }

    /**
     * Returns one page's identifiers in page order.
     *
     * @param page the page to read
     * @return the identifiers, in the order the page presents them
     */
    private static List<String> identifiersOf(final Page<UserSecurityRepository.AdminEntry> page) {
        return page.getContent().stream()
                .map(UserSecurityRepository.AdminEntry::getSecUsrId)
                .map(identifier -> identifier.toUpperCase(Locale.ROOT))
                .toList();
    }
}
