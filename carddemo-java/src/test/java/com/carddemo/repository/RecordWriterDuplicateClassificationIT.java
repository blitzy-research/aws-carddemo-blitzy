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

import com.carddemo.domain.TransactionType;
import com.carddemo.support.AbstractPostgresIT;
import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.dao.PersistenceExceptionTranslationAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Establishes, against a real PostgreSQL 16 server, that the refused-key classification the duplicate
 * response arms share tells the unique violation apart from every other refusal in its family.
 *
 * <h2>Why this needs a real server</h2>
 *
 * <p>{@link RecordWriter#isDuplicateKey(Throwable)} reads the SQL state the store reported. A synthesised
 * {@code SQLException} carrying a state chosen by the test proves only that the method reads the state it
 * was handed; it cannot prove that the state PostgreSQL reports for a foreign-key refusal differs from the
 * one it reports for a duplicate key, nor that the framework's translation preserves it. Every failure
 * below is produced by asking a real server to accept a row it must refuse, so both halves are real: the
 * condition, and the state the condition is reported under.
 *
 * <p>Five conditions are exercised and each is asserted twice - once on the state PostgreSQL actually
 * reported, so the test names a fact rather than an assumption, and once on the verdict the classifier
 * reaches from it. That pairing is what makes a future state change visible instead of silent.
 *
 * <table border="1">
 *   <caption>The five refusals and the arm each must reach</caption>
 *   <tr><th>Condition</th><th>State</th><th>Refused key?</th><th>Arm in the source</th></tr>
 *   <tr><td>duplicate primary key</td><td>{@code 23505}</td><td>yes</td>
 *       <td>the duplicate arm - <em>already exists</em></td></tr>
 *   <tr><td>null in a column that forbids it</td><td>{@code 23502}</td><td>no</td>
 *       <td>the write paragraph's catch-all</td></tr>
 *   <tr><td>foreign key naming an absent row</td><td>{@code 23503}</td><td>no</td>
 *       <td>the write paragraph's catch-all</td></tr>
 *   <tr><td>check constraint refusing the value</td><td>{@code 23514}</td><td>no</td>
 *       <td>the write paragraph's catch-all</td></tr>
 *   <tr><td>value wider than the column</td><td>{@code 22001}</td><td>no</td>
 *       <td>the write paragraph's catch-all</td></tr>
 * </table>
 *
 * <p>The first four states all begin {@code 23}, which is why reading the two-character class routed all
 * four to the duplicate arm and told an operator that an identifier they had chosen was already taken when
 * the identifier was never the problem. The three callers of the classification - the bill-payment,
 * user-add and transaction-add members - each own a catch-all write-failure arm that the misclassification
 * bypassed.
 *
 * <h2>How each failure is produced</h2>
 *
 * <p>The duplicate is produced through the production write primitive itself: {@link RecordWriter#insert}
 * on a reference row whose key the seed already holds, inside a unit of work that is then rolled back. The
 * other four are produced through {@link JdbcTemplate}, because each needs a value the mapped entity would
 * refuse before the server ever saw it - a null in a non-null column, a key of the wrong shape, a value
 * wider than its column - and the point of the assertion is the <em>server's</em> refusal. Every statement
 * is a fixed literal with its values bound; nothing is assembled from a variable, so the raw-SQL count this
 * module holds at zero for production code is unaffected and no statement here is composed from input.
 *
 * <p>Nothing is left behind. Each statement fails, so it inserts nothing, and the one unit of work that
 * reaches the persistence context is rolled back rather than committed. The shared server therefore leaves
 * this class in the state it entered it, and no reset is needed.
 *
 * <p><strong>Provenance.</strong> The legacy estate carries no test harness of any kind. The response arms
 * this classification feeds are read-only reference material at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19; no legacy source text is reproduced here.
 *
 * @see RecordWriter#isDuplicateKey(Throwable)
 */
@SpringBootTest(classes = RecordWriterDuplicateClassificationIT.WriterUnderTest.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Refused-key classification: the five PostgreSQL refusals, told apart on a real server")
final class RecordWriterDuplicateClassificationIT extends AbstractPostgresIT {

    /** The state the store reports when a unique or primary-key constraint refused the row. */
    private static final String UNIQUE_VIOLATION = "23505";

    /** The state the store reports when a column that forbids the absent value received it. */
    private static final String NOT_NULL_VIOLATION = "23502";

    /** The state the store reports when a foreign key named a row that does not exist. */
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    /** The state the store reports when a check constraint refused the value. */
    private static final String CHECK_VIOLATION = "23514";

    /** The state the store reports when a value is wider than the column that must hold it. */
    private static final String STRING_DATA_RIGHT_TRUNCATION = "22001";

    /** A reference type code the seed already holds, so inserting it again is a duplicate key. */
    private static final String SEEDED_TYPE_CODE = "01";

    /** A reference type code the seed does not hold, so a row carrying it collides with nothing. */
    private static final String FREE_TYPE_CODE = "98";

    /** A description short enough for the column, used where the description is not what is refused. */
    private static final String ANY_DESCRIPTION = "Purchase";

    /** An eleven-digit account key no seeded row carries, so a card naming it has no account. */
    private static final String ABSENT_ACCOUNT_ID = "99999999999";

    /** A sixteen-digit card number no seeded row carries. */
    private static final String FREE_CARD_NUMBER = "9999888877776666";

    /** An eleven-character account key that is not eleven digits, which the key-shape check refuses. */
    private static final String NON_NUMERIC_ACCOUNT_ID = "ABCDEFGHIJK";

    /** A three-character code, one wider than the two-character column that must hold it. */
    private static final String OVERWIDE_TYPE_CODE = "980";

    /** Inserts one reference row, bound rather than assembled. */
    private static final String INSERT_REFERENCE_ROW =
            "INSERT INTO transaction_type (tran_type, tran_type_desc) VALUES (?, ?)";

    /** Inserts one card row, bound rather than assembled. */
    private static final String INSERT_CARD_ROW = """
            INSERT INTO card (card_num, card_acct_id, card_cvv_cd, card_embossed_name,
                              card_expiration_date, card_active_status)
            VALUES (?, ?, '123', 'CLASSIFICATION PROBE', '2099-12-31', 'Y')
            """;

    /** Inserts one account row, bound rather than assembled. */
    private static final String INSERT_ACCOUNT_ROW = """
            INSERT INTO account (acct_id, acct_active_status, acct_curr_bal, acct_credit_limit,
                                 acct_cash_credit_limit, acct_open_date, acct_expiration_date,
                                 acct_reissue_date, acct_curr_cyc_credit, acct_curr_cyc_debit,
                                 acct_addr_zip, acct_group_id)
            VALUES (?, 'Y', 0.00, 0.00, 0.00, '2020-01-01', '2099-12-31', '2020-01-01',
                    0.00, 0.00, '12345', 'A         ')
            """;

    /**
     * The production write primitive, wired exactly as the application wires it.
     *
     * <p>Taken from the context rather than constructed here on purpose: the primitive is annotated as a
     * repository, so the container proxies it for persistence-exception translation, and it is that
     * translated failure - not the provider's own - that a calling paragraph catches. Constructing the
     * primitive by hand would bypass the proxy and prove the classification against a failure shape the
     * application never sees.
     */
    @Autowired
    private RecordWriter recordWriter;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Creates the specification. */
    RecordWriterDuplicateClassificationIT() {
        super();
    }

    /**
     * The one condition whose arm is the duplicate arm.
     */
    @Nested
    @DisplayName("the duplicate key, reported as 23505")
    final class TheRefusedKey {

        /** Creates the nest. */
        TheRefusedKey() {
        }

        @Test
        @DisplayName("the production write primitive's own failure on an existing key reports 23505 and "
                + "IS classified as the refused key")
        void theProductionWritePrimitiveReportsTheUniqueViolation() {
            final TransactionTemplate unit = new TransactionTemplate(transactionManager);

            final DataAccessException refusal = assertThatExceptionOfType(DataAccessException.class)
                    .isThrownBy(() -> unit.execute(status -> {
                        // Declared before the write, so the unit is abandoned whichever way it leaves.
                        status.setRollbackOnly();
                        return recordWriter.insert(
                                new TransactionType(SEEDED_TYPE_CODE, ANY_DESCRIPTION));
                    }))
                    .actual();

            assertThat(reportedStateOf(refusal))
                    .as("PostgreSQL reports a primary-key collision as the unique violation")
                    .isEqualTo(UNIQUE_VIOLATION);
            assertThat(refusal)
                    .as("MEASURED, AND THE REASON THE STATE MUST BE READ FIRST: translating a JPA "
                            + "constraint failure yields the BROAD integrity type, not the "
                            + "duplicate-specific one. A classification that trusted the translated "
                            + "type would miss every duplicate this write point raises")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .isNotInstanceOf(DuplicateKeyException.class);
            assertThat(RecordWriter.isDuplicateKey(refusal))
                    .as("the state the store reported carries it regardless, and it is the one refusal "
                            + "the duplicate arm exists for")
                    .isTrue();
        }

        @Test
        @DisplayName("the provider's OWN untranslated failure classifies the same way, so the "
                + "classification does not depend on the translation happening")
        void theProvidersOwnUntranslatedFailureClassifiesTheSameWay() {
            final RecordWriter unproxied = new RecordWriter(entityManagerFactory);
            final TransactionTemplate unit = new TransactionTemplate(transactionManager);

            final ConstraintViolationException refusal =
                    assertThatExceptionOfType(ConstraintViolationException.class)
                            .isThrownBy(() -> unit.execute(status -> {
                                status.setRollbackOnly();
                                return unproxied.insert(
                                        new TransactionType(SEEDED_TYPE_CODE, ANY_DESCRIPTION));
                            }))
                            .actual();

            assertThat(reportedStateOf(refusal)).isEqualTo(UNIQUE_VIOLATION);
            assertThat(RecordWriter.isDuplicateKey(refusal))
                    .as("the state the store reported is what decides, so no provider type is consulted")
                    .isTrue();
        }

        @Test
        @DisplayName("a plain statement refused for the same reason is classified the same way, and the "
                + "framework translates it to the duplicate-specific type")
        void aPlainStatementRefusedForTheSameReasonIsClassifiedTheSameWay() {
            final DataAccessException refusal = refusalFrom(() -> jdbcTemplate.update(
                    INSERT_REFERENCE_ROW, SEEDED_TYPE_CODE, ANY_DESCRIPTION));

            assertThat(reportedStateOf(refusal)).isEqualTo(UNIQUE_VIOLATION);
            assertThat(refusal)
                    .as("the framework's duplicate-specific type, not merely its integrity supertype")
                    .isInstanceOf(DuplicateKeyException.class);
            assertThat(RecordWriter.isDuplicateKey(refusal)).isTrue();
        }
    }

    /**
     * The four conditions that share the duplicate's state class and none of its meaning.
     */
    @Nested
    @DisplayName("the four refusals that are NOT a refused key")
    final class TheRefusalsThatAreNotARefusedKey {

        /** Creates the nest. */
        TheRefusalsThatAreNotARefusedKey() {
        }

        @Test
        @DisplayName("a null in a column that forbids it reports 23502 and is NOT a refused key")
        void aNullInAColumnThatForbidsItIsNotARefusedKey() {
            final DataAccessException refusal = refusalFrom(() ->
                    jdbcTemplate.update(INSERT_REFERENCE_ROW, FREE_TYPE_CODE, null));

            assertThat(reportedStateOf(refusal)).isEqualTo(NOT_NULL_VIOLATION);
            assertThat(refusal).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(RecordWriter.isDuplicateKey(refusal))
                    .as("the state begins 23 and means something else entirely: the identifier was "
                            + "free, so reporting it as taken is both untrue and unactionable")
                    .isFalse();
        }

        @Test
        @DisplayName("a foreign key naming an absent row reports 23503 and is NOT a refused key")
        void aForeignKeyNamingAnAbsentRowIsNotARefusedKey() {
            final DataAccessException refusal = refusalFrom(() ->
                    jdbcTemplate.update(INSERT_CARD_ROW, FREE_CARD_NUMBER, ABSENT_ACCOUNT_ID));

            assertThat(reportedStateOf(refusal)).isEqualTo(FOREIGN_KEY_VIOLATION);
            assertThat(refusal).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(RecordWriter.isDuplicateKey(refusal))
                    .as("the card number was free; what the server refused was the account it names")
                    .isFalse();
        }

        @Test
        @DisplayName("a check constraint refusing the value reports 23514 and is NOT a refused key")
        void aCheckConstraintRefusingTheValueIsNotARefusedKey() {
            final DataAccessException refusal = refusalFrom(() ->
                    jdbcTemplate.update(INSERT_ACCOUNT_ROW, NON_NUMERIC_ACCOUNT_ID));

            assertThat(reportedStateOf(refusal)).isEqualTo(CHECK_VIOLATION);
            assertThat(refusal).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(RecordWriter.isDuplicateKey(refusal))
                    .as("the key shape was refused, not the key's availability")
                    .isFalse();
        }

        @Test
        @DisplayName("a value wider than its column reports 22001 and is NOT a refused key")
        void aValueWiderThanItsColumnIsNotARefusedKey() {
            final DataAccessException refusal = refusalFrom(() -> jdbcTemplate.update(
                    INSERT_REFERENCE_ROW, OVERWIDE_TYPE_CODE, ANY_DESCRIPTION));

            assertThat(reportedStateOf(refusal)).isEqualTo(STRING_DATA_RIGHT_TRUNCATION);
            assertThat(RecordWriter.isDuplicateKey(refusal))
                    .as("class 22 is a data exception: the column cannot hold the value at all, and no "
                            + "other identifier could clear it")
                    .isFalse();
        }
    }

    /**
     * Runs a statement that must be refused and returns the framework's translated failure.
     *
     * @param  refused the statement to run
     * @return the translated failure it raised
     */
    private static DataAccessException refusalFrom(final Runnable refused) {
        return assertThatExceptionOfType(DataAccessException.class).isThrownBy(refused::run).actual();
    }

    /**
     * Reads the SQL state the store itself reported, from wherever in the failure the driver hung it.
     *
     * <p>Deliberately independent of the production traversal: it walks the cause chain and the driver's
     * own next-exception chain here, in this file, so the state it reports is the test's own reading of
     * the failure rather than a value obtained from the code under test.
     *
     * @param  failure the translated failure
     * @return the first non-null SQL state found, or {@code null} when the failure carries none
     */
    private static String reportedStateOf(final Throwable failure) {
        for (Throwable candidate = failure; candidate != null; candidate = candidate.getCause()) {
            if (candidate instanceof SQLException reported) {
                for (SQLException link = reported; link != null; link = link.getNextException()) {
                    if (link.getSQLState() != null) {
                        return link.getSQLState();
                    }
                }
            }
        }
        return null;
    }

    /**
     * The narrowest context this classification needs: a data source, the persistence unit, catalogue
     * access and transaction management, with the reference entity registered over them.
     *
     * <p>Named explicitly rather than discovered by scanning, for the same reason every sibling
     * repository specification names its slice: a scan rooted at the application package reaches the whole
     * test tree during an integration run. The data-source address arrives from the container the base
     * class owns.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class,
        PersistenceExceptionTranslationAutoConfiguration.class})
    @EnableJpaRepositories(basePackageClasses = TransactionTypeRepository.class)
    @EntityScan(basePackageClasses = TransactionType.class)
    static class WriterUnderTest {

        /** Creates the configuration. */
        WriterUnderTest() {
        }

        /**
         * The production write primitive, declared so the container proxies it for exception
         * translation exactly as it does in the application.
         *
         * @param  entityManagerFactory the persistence unit; supplied by the container
         * @return the write primitive
         */
        @Bean
        RecordWriter recordWriter(final EntityManagerFactory entityManagerFactory) {
            return new RecordWriter(entityManagerFactory);
        }
    }
}
