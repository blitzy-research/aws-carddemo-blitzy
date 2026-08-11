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
package com.carddemo.api;

import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.domain.Card;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.repository.CardRepository;
import com.carddemo.support.AbstractPostgresIT;
import jakarta.persistence.OptimisticLockException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import org.hibernate.StaleStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Integration test proving that the failure a genuine {@code @Version} conflict actually produces is one
 * the boundary advice declares, and that the advice answers it with a conflict.
 *
 * <p><strong>Why a unit test of the advice is not sufficient on its own.</strong> The unit test asserts
 * that each type the advice <em>declares</em> resolves to the conflict arm and answers {@code 409}. That
 * is a statement about the advice, and it is true regardless of what the persistence provider does. The
 * thing it cannot establish is the premise: that the provider raises one of those types at all. If
 * Hibernate or Spring Data raised a fourth shape, every unit assertion would still pass while a real
 * stale screen still produced a {@code 500}. The declared set has to be checked against observed
 * behaviour, and observing it requires a real server, a real schema, a real version column and a real
 * flush - which is why this is an integration test and not a mock.
 *
 * <p><strong>How the conflict is produced.</strong> A reserved card is written and its version observed.
 * The row is then advanced out of band, over a separate JDBC connection, exactly as a second operator's
 * committed update would advance it. The first entity is then modified and flushed while still carrying
 * the version it read. That is precisely the condition the legacy write paths detected by re-reading the
 * record and comparing it against the image they had presented, so the provider's response to it is the
 * behaviour under test.
 *
 * <p><strong>What is asserted.</strong> Three things. The conflict is raised rather than silently lost -
 * a lost update would be a far worse defect than a wrong status. The type raised is covered by one of the
 * three the advice declares. And the advice, handed the exception the provider actually threw, answers
 * {@code 409} with the verbatim legacy record-changed text and discloses nothing about the entity.
 *
 * <p>No COBOL statement is transcribed.
 */
@DisplayName("A real @Version conflict: what the provider raises, and what the boundary answers")
final class ProviderOptimisticLockConflictIT extends AbstractPostgresIT {

    /**
     * The three failure types the boundary advice declares for a provider-detected conflict.
     *
     * <p>Held here as the specification this test checks the provider against. It is deliberately a
     * copy of the advice's declaration rather than a reading of it: reading the annotation would make
     * the test agree with the advice by construction, which is the one thing it must not do.
     */
    private static final List<Class<?>> DECLARED_CONFLICT_TYPES = List.of(
            OptimisticLockingFailureException.class,
            OptimisticLockException.class,
            StaleStateException.class);

    /** A card number reserved for this class, outside the seeded range. */
    private static final String RESERVED_CARD = "9900000000000042";

    /** A seeded account identifier, from {@code app/data/ASCII/carddata.txt}. */
    private static final String SEEDED_ACCOUNT = "00000000050";

    /** Advances the version of one card row, standing in for another operator's committed update. */
    private static final String ADVANCE_VERSION_SQL =
            "UPDATE card SET version = version + 1 WHERE card_num = ?";

    /** Removes the reserved row, so the class leaves the shared server as it found it. */
    private static final String DELETE_RESERVED_SQL = "DELETE FROM card WHERE card_num = ?";

    /** Creates the test class. */
    ProviderOptimisticLockConflictIT() {
    }

    /**
     * Registers the card repository and its entity for a runner-assembled context.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = CardRepository.class)
    @EntityScan(basePackageClasses = Card.class)
    static class RepositoriesUnderTest {

        /** Creates the configuration. */
        RepositoriesUnderTest() {
        }
    }

    @Test
    @DisplayName("a stale write raises a conflict the advice declares, and the advice answers 409 with the "
            + "legacy record-changed text rather than the abend literal")
    void aRealStaleWriteIsAnsweredAsAConflict() {
        runner().run(context -> {
            final CardRepository repository = context.getBean(CardRepository.class);
            try {
                final Card stored = repository.saveAndFlush(reservedCard());
                final long observedVersion = stored.getVersion();

                // Another operator commits first. Done over a separate connection so the entity in hand
                // keeps the version it read, which is what makes the following flush stale.
                advanceStoredVersion();

                // The instance to write back is the one already in hand, not a re-read. Each repository
                // call runs in its own transaction, so a re-read would open a fresh persistence context
                // and observe the advanced row - which is exactly the conflict this test needs to avoid
                // resolving. The detached instance still carries the version it was written with, and
                // that is the stale screen the legacy paths detected.
                assertThat(reReadVersion(repository))
                        .as("the row must have moved on, or there is nothing to conflict with")
                        .isGreaterThan(observedVersion);
                assertThat(stored.getVersion())
                        .as("the entity in hand must still carry the pre-advance version, or the "
                                + "conflict this test depends on would not arise")
                        .isEqualTo(observedVersion);

                stored.setCardEmbossedName("STALE WRITE ATTEMPT");
                final Throwable raised = catchThrowable(() -> repository.saveAndFlush(stored));

                assertThat(raised)
                        .as("a stale write must fail rather than silently overwrite the committed update")
                        .isNotNull();
                assertThat(DECLARED_CONFLICT_TYPES)
                        .as("the provider raised %s, which the advice does not declare, so a real stale "
                                + "screen would answer 500", raised.getClass().getName())
                        .anyMatch(declared -> declared.isAssignableFrom(raised.getClass()));

                // Hand the advice the exception the provider actually threw, not a constructed stand-in.
                final ResponseEntity<ErrorResponse> response =
                        new GlobalExceptionHandler()
                                .handleProviderOptimisticLockFailure((Exception) raised);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                final ErrorResponse body = response.getBody();
                assertThat(body).isNotNull();
                assertThat(body.message())
                        .isEqualTo(OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE);
                assertThat(body.fieldErrors()).isEmpty();
                assertThat(body.message())
                        .as("the entity and the provider's own type are diagnostics, not disclosures")
                        .doesNotContain(RESERVED_CARD, "Card", "com.carddemo", "Exception");
            } finally {
                removeReservedRow();
            }
        });
    }

    /**
     * Reads the version the server currently holds for the reserved card, over its own context.
     *
     * @param repository the card repository
     * @return the stored version
     */
    private static long reReadVersion(final CardRepository repository) {
        final Optional<Card> current = repository.findById(RESERVED_CARD);
        assertThat(current).as("the reserved row must exist").isPresent();
        return current.orElseThrow().getVersion();
    }

    /**
     * Builds the reserved card, valid in every field so that the entity's own pre-write guard passes and
     * the only failure this test can observe is the version conflict.
     *
     * @return a card that satisfies every stored-value rule
     */
    private static Card reservedCard() {
        return new Card(RESERVED_CARD, SEEDED_ACCOUNT, "123", "CONFLICT IT FIXTURE", "2099-12-31", "Y");
    }

    /**
     * Advances the stored version of the reserved card over its own connection.
     *
     * @throws java.sql.SQLException when the update cannot be applied
     */
    private static void advanceStoredVersion() throws java.sql.SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(ADVANCE_VERSION_SQL)) {
            statement.setString(1, RESERVED_CARD);
            assertThat(statement.executeUpdate())
                    .as("the out-of-band update must affect exactly the reserved row")
                    .isEqualTo(1);
        }
    }

    /**
     * Removes the reserved card, so the shared server is left as it was found.
     *
     * @throws java.sql.SQLException when the delete cannot be applied
     */
    private static void removeReservedRow() throws java.sql.SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(DELETE_RESERVED_SQL)) {
            statement.setString(1, RESERVED_CARD);
            statement.executeUpdate();
        }
    }

    /**
     * Assembles a context carrying the card repository, against the shared server.
     *
     * @return the configured runner
     */
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(RepositoriesUnderTest.class)
                .withPropertyValues(
                        "spring.datasource.url=" + jdbcUrl(),
                        "spring.datasource.username=" + databaseUser(),
                        "spring.datasource.password=" + databasePassword(),
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.jpa.open-in-view=false");
    }
}
