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

import com.carddemo.domain.Transaction;
import jakarta.persistence.EntityManagerFactory;
import com.carddemo.support.AbstractPostgresIT;
import com.carddemo.support.SensitiveValues;
import com.carddemo.support.TestDataFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies the transaction master against a real PostgreSQL 16 server carrying the shipped
 * migrations: the maximum-plus-one identifier rule, the identifier shape that rule depends on, the
 * insert-only assigned-key write, the bounded keyset browse, the inherited page-of-ten browse, the
 * decimal and string fidelity of the stored record, and the schema contract behind the batch-only
 * processing-timestamp access path.
 *
 * <h2>Why a real server, and not an in-memory one</h2>
 *
 * <p>Three of the assertions below have no meaning against a substituted engine. The identifier
 * maximum is taken over a bounded character column, so it depends on real string collation; the
 * inclusive upper bound of the reporting window depends on real {@code SUBSTRING} semantics over that
 * same character data; and the access path behind the window depends on a real planner choosing a real
 * index. An in-memory engine can differ on all three, which would leave the two most important
 * assertions here passing or failing for a reason unrelated to what they test. The container is
 * therefore the one {@link AbstractPostgresIT} owns, and this class declares no container, no
 * container lifecycle annotation and no data-source property of its own.
 *
 * <h2>The context this class boots, and why it is scoped rather than whole</h2>
 *
 * <p>The maximum-plus-one assertions need genuine commit and rollback boundaries and therefore a real
 * transaction manager, so this is a {@link SpringBootTest} and deliberately not a sliced persistence
 * test: a persistence slice replaces the data source with an in-memory engine unless told otherwise,
 * and wraps every method in a rollback-only transaction, which would defeat both the
 * single-transaction assertion and the rollback-gap assertion outright.
 *
 * <p>It names its own configuration class rather than letting the annotation discover the
 * application's. That is a requirement of this module rather than a preference. Discovering the
 * application class would component-scan the base package, and the base package on the test class path
 * contains the nested configuration classes of the other repository tests, several of which enable JPA
 * repositories in their own right; the scan then registers the same repository twice and the context
 * fails to refresh with a bean-definition override. Naming a configuration suppresses the search and
 * the scan together, and what remains is exactly what these tests need: the real data source pointing
 * at the shared server, Hibernate validating the shipped mapping against the migrated schema, a real
 * transaction manager and template, a template for catalogue and plan inspection, and the repository
 * proxies themselves. Recorded in {@code docs/decision-log.md} DL-202.
 *
 * <h2>What the repository declares, and the range query that no longer lives on it</h2>
 *
 * <p>{@link TransactionRepository} declares exactly one query of its own, {@code findMaxId()}, and
 * mixes in the insert-and-lock fragment; the bounded ordered reads live on
 * {@link TransactionScanRepository} and everything else used here is inherited. An unbounded
 * range-selecting query over the processing timestamp did once live on the interface and was removed
 * upstream as an unreferenced second contract for a selection the reporting tier already performs -
 * the report applies the inclusive window itself, in character comparisons, over the frozen ordered
 * generation its job step hands it. This class does not reinstate that query, because reinstating it
 * would add production code no caller reaches.
 *
 * <p>What remains genuinely owned here is the part of that contract the schema carries, and it is
 * asserted in full: that the index exists under its shipped name and is non-unique, that the bare
 * lower bound is the predicate able to drive it, and that the character semantics which force the
 * upper bound to be taken over a ten-character prefix are the semantics this server actually has. The
 * reconciliation is recorded in {@code docs/decision-log.md} DL-199, which upholds DL-122's predicate
 * reasoning in full and records only that the predicate moved.
 *
 * <h2>The window contract these tests pin, and the trap inside it</h2>
 *
 * <p>The reporting procedure's sort specification addresses two fields of the record image by
 * position: the card number at one-based 263 for 16 bytes, and the processing date at one-based 305
 * for 10 bytes. It orders by card number ascending and admits a record when its processing date is at
 * or after the start bound and at or before the end bound, so <strong>both bounds are
 * inclusive</strong>, and the report line it produces is 133 bytes wide.
 *
 * <p>The trap is the width. The sort addresses ten bytes of a twenty-six character field, so it
 * compares only the date prefix. Comparing the whole column against a ten-character end bound
 * excludes every record processed <em>on</em> the end date, because a longer string whose prefix
 * equals a shorter one sorts above it. The tests below prove that on this server, in both the
 * blank-filled and the time-bearing form, and prove that taking the prefix admits the record the
 * whole-column comparison drops. Nothing here converts either column to a temporal type: they are
 * bounded character columns of width 26 and they stay character data end to end.
 *
 * <p>One ascending card-number ordering serves both consumers of that ordering. The reporting
 * procedure types those bytes as zoned decimal while the statement job types the very same bytes as
 * character, and for an unsigned zero-padded sixteen-digit lexeme the two orderings coincide, so a
 * single ascending order is faithful to both rather than a compromise between them. That, and the
 * decision to keep both timestamp columns as character data end to end, are recorded in
 * {@code docs/decision-log.md} DL-201.
 *
 * <h2>Why the identifier rule is a maximum plus one and never a generated key</h2>
 *
 * <p>The online bill-payment program mints an identifier by seeding the key with high values, opening
 * a browse, reading backward once to land on the highest identifier present, closing the browse,
 * moving that value into a sixteen-digit numeric work field initialised to zeros, adding one, and
 * moving the result back into the sixteen-character key. Its end-of-file arm moves zeros, so an empty
 * file yields one - and because the destination is a sixteen-character field, the first identifier is
 * the sixteen-character string {@code 0000000000000001} and never a bare one.
 *
 * <p>An empty {@link java.util.Optional} is the exact analogue of that end-of-file arm, which is why
 * the maximum is returned wrapped. Taking the maximum over character data is sound only because every
 * stored identifier is exactly sixteen zero-padded digits with no sign overpunch; under that invariant
 * the character and numeric orderings coincide and the result is independent of collation, since all
 * sixteen characters lie in the digit range. The invariant is enforced twice - by the entity before a
 * write and by a check constraint for a writer that bypasses the entity - and both enforcements are
 * asserted here.
 *
 * <p><strong>A generated key is forbidden as a substitute, and the reason is behavioural rather than
 * stylistic:</strong> a generated key never reissues a value it has handed out, whereas this rule
 * always reuses a gap. The first time an allocation rolls back, a generated key would step permanently
 * past the value it consumed and diverge from the legacy numbering for the remaining life of the
 * table, and gaps are not hypothetical - the first rollback guarantees one. The rollback-gap test
 * below is the proof that no generated key is in use, and the rule and its lock are recorded in
 * {@code docs/decision-log.md} DL-018 and DL-149: it consumes an identifier inside a transaction
 * that rolls back and then asserts that the next allocation takes that same value again. The shipped
 * schema accordingly declares no generated column of any kind.
 *
 * <p>Sharing one transaction is necessary but not sufficient, because an uncommitted insert is
 * invisible under the isolation this module runs at. Closing that window is the allocating service's
 * concurrency policy, expressed as the advisory lock the insert fragment publishes; the tests here
 * prove the lock is exclusive across sessions, that two allocators genuinely collide without it, and
 * that the loser waits and then reads a maximum already including the winner's row.
 *
 * <h2>The four merchant columns on this table carry no prefix</h2>
 *
 * <p>Every other column carries the regular field-name prefix, but on exactly four the shipped
 * migration drops it and names the merchant attribute alone. The parallel landing table, whose legacy
 * record is byte-for-byte identical, does prefix its own four. That asymmetry is the single most likely
 * mapping error in this package, and because the mapping is validated at start-up rather than
 * generated, getting it wrong fails the context outright instead of quietly creating a second column.
 * A clean refresh is therefore itself an assertion, and the round-trip below names all four
 * explicitly. Recorded in {@code docs/decision-log.md} D-39.
 *
 * <h2>The card number is stored unprotected, and that is a recorded gap rather than an oversight</h2>
 *
 * <p>The legacy design applies neither masking nor encryption to the card primary account number, and
 * none is added here, so the column round-trips verbatim like every other character field. That gap is
 * deliberately left open and is recorded in {@code docs/decision-log.md} DL-010 and D-14. What this
 * class does do is refuse to widen it: the seeded card numbers it must name to satisfy the foreign key
 * are held in a private constant and are never written into a display name, an assertion message, a
 * comment or a log line, and every assertion about one compares against that constant or checks only
 * its shape and width.
 *
 * <h2>This class leaves the table as it found it</h2>
 *
 * <p>The reference seed inserts no row into this table, and the server is shared by every integration
 * test in the run, so every test here writes only identifiers in a reserved range no fixture uses and
 * removes them in a {@code finally} block. That is what lets the emptiness assertions hold whatever
 * order the tests run in, and it is the convention the rest of this tier already follows in preference
 * to discarding the context.
 *
 * <h2>Who consumes this table</h2>
 *
 * <p>It is the most widely consumed table in the module: the transaction list, view and add services;
 * the bill-payment service, which is the maximum-plus-one consumer; the posting and interest-accrual
 * runs; the reporting service, which is the window consumer; and both statement services. The base
 * cluster is registered to the online region, but its processing-timestamp alternate index is not -
 * only the card-side paths are - which is what makes the window access path batch-only.
 *
 * <p>Provenance: the behaviour asserted here is that of {@code app/cpy/CVTRA05Y.cpy} (the 350-byte
 * record layout), {@code app/jcl/TRANFILE.jcl} and {@code app/jcl/TRANIDX.jcl} (the base cluster and
 * the one logical alternate index described in both), {@code app/proc/TRANREPT.prc} with
 * {@code app/jcl/TRANREPT.jcl} (the inclusive window, the sort positions and the 133-byte line),
 * {@code app/jcl/CREASTMT.JCL} (the statement job that types the same card-number bytes as character),
 * {@code app/cbl/COBIL00C.cbl} (identifier minting), {@code app/cbl/COTRN00C.cbl} (the list browse and
 * its page size), {@code app/cbl/CBTRN03C.cbl} (the report that performs no arithmetic) and
 * {@code app/csd/CARDDEMO.CSD} (which registers the base cluster online and this index nowhere). All
 * are read-only reference at commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. That stamp is a provenance
 * string for the traceability matrix header only and is not asserted against any member. Members are
 * cited by name, width, offset and row count only: <strong>no legacy source statement is transcribed
 * anywhere in this file.</strong>
 *
 * @see TransactionRepository
 * @see TransactionScanRepository
 * @see AbstractPostgresIT
 */
@SpringBootTest(classes = TransactionRepositoryIT.PersistenceSlice.class,
        properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@DisplayName("Transaction repository: identifier allocation, record fidelity, browse and window "
        + "access path")
final class TransactionRepositoryIT extends AbstractPostgresIT {

    /**
     * The screen row count of the transaction list.
     *
     * <p>Uniquely among the estate's paginated screens this figure comes from no screen-row table at
     * all: the list program declares none, and the size is established purely by its loop bounds - a
     * forward loop running while the index does not exceed ten, and a fill loop running until the
     * index reaches eleven.
     */
    private static final int SCREEN_ROWS = 10;

    /** One more row than a page holds, so a page-of-ten assertion has a second page to report. */
    private static final int ROWS_EXCEEDING_ONE_PAGE = SCREEN_ROWS + 1;

    /** The width of the identifier, the card number, and therefore of every key assertion here. */
    private static final int KEY_WIDTH = 16;

    /** The width of the two timestamp columns, which are character data and stay character data. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** The width of the date prefix the reporting sort addresses inside that timestamp. */
    private static final int DATE_PREFIX_WIDTH = 10;

    /**
     * Identifiers reserved for this class, chosen above every value any fixture or seed uses so that
     * the maximum this class inserts is always the table maximum while its rows exist.
     */
    private static final List<String> RESERVED_IDS = List.of(
            "9900000000000010", "9900000000000020", "9900000000000030", "9900000000000040",
            "9900000000000050", "9900000000000060", "9900000000000070", "9900000000000080",
            "9900000000000090", "9900000000000100", "9900000000000110", "9900000000000120");

    /**
     * The three identifiers the concurrency nest can create: the base row it seeds so that the table
     * maximum is known, and the two successors two allocators derive from it.
     *
     * <p>They are declared literally rather than computed so that the cleanup removes exactly what the
     * nest can have created, whatever an individual test managed to commit before it failed.
     */
    private static final List<String> CONCURRENCY_IDS = List.of(
            "9900000000000050", "9900000000000051", "9900000000000052");

    /** Identifiers reserved for the window nest, kept clear of every other nest's range. */
    private static final List<String> WINDOW_IDS = List.of(
            "9900000000000210", "9900000000000220", "9900000000000230", "9900000000000240",
            "9900000000000250", "9900000000000260");

    /**
     * The identifier the window nest's upper-boundary row carries: midnight on the day after the window.
     *
     * <p>Held apart from {@code WINDOW_IDS} because only one test writes it, and its cleanup must be able
     * to run without disturbing the six rows the rest of the nest shares.
     */
    private static final String BOUNDARY_ID = "9900000000000270";

    /** The identifier the rollback-gap test consumes, releases and then expects to see reissued. */
    private static final String GAP_BASE_ID = "9900000000000310";

    /**
     * How long a test waits before concluding that a blocked allocator really is blocked, in
     * milliseconds.
     *
     * <p>A bound is needed because a lock wait has no completion to observe. It cannot produce a false
     * failure: the assertion is that the wait <em>times out</em>, so a slow machine only makes the
     * conclusion safer, while a lock that failed to serialise completes immediately and fails the test.
     */
    private static final long LOCK_WAIT_PROBE_MILLIS = 400L;

    /** How long a test waits for a released lock to be handed over, in seconds. */
    private static final long LOCK_HANDOVER_TIMEOUT_SECONDS = 20L;

    /**
     * Card numbers the reference seed carries, in ascending order.
     *
     * <p>Every row inserted here names one of them, because the shipped migration declares a foreign
     * key from this table's card number to the card master and an invented value is refused. Holding
     * them in ascending order is what lets the window nest assert the ordering the reporting sort
     * requires without computing an expected order from the data it just wrote. They are referenced
     * only through these constants and are never placed in a message or a display name.
     */
    private static final List<String> SEEDED_CARDS = List.of(
            "0500024453765740", "0683586198171516", "0923877193247330", "0927987108636232",
            "0982496213629795");

    /** The first seeded card number, used wherever a row needs a valid parent and nothing more. */
    private static final String SEEDED_CARD = SEEDED_CARDS.get(0);

    /** The inclusive lower bound of the reporting window, ten characters as the sort addresses it. */
    private static final String WINDOW_START = "2022-06-01";

    /** The inclusive upper bound of the reporting window, ten characters as the sort addresses it. */
    private static final String WINDOW_END = "2022-06-30";

    /** One day below the lower bound, which an inclusive window must reject. */
    private static final String DAY_BEFORE_WINDOW_START = "2022-05-31";

    /** One day above the upper bound, which an inclusive window must reject. */
    private static final String DAY_AFTER_WINDOW_END = "2022-07-01";

    /** A date inside the window, so the selection is proved to admit more than its boundaries. */
    private static final String WITHIN_WINDOW = "2022-06-15";

    /**
     * The upper-bound row's timestamp: the end date followed by a real time component.
     *
     * <p>This is the value the whole contract turns on. Its first ten characters are exactly the end
     * bound, so the reporting sort admits it, while as a whole string it sorts above that bound and a
     * comparison over the whole column drops it.
     */
    private static final String END_DATE_WITH_TIME = WINDOW_END + " 23:59:59.999999";

    /** A fixed original timestamp, so no test derives a value from a running clock. */
    private static final String FIXED_ORIGINAL_TIMESTAMP = TestDataFactory.SEEDED_ORIGINAL_TIMESTAMP;

    /** The description every fixture row carries, so a merge would be visible as a changed value. */
    private static final String FIXTURE_DESCRIPTION = "repository integration fixture";

    /**
     * The source code every fixture row carries: a padded ten-character value.
     *
     * <p>Padded on purpose. The column is bounded variable-length rather than blank-padded
     * fixed-length precisely so that the stored padding survives a round trip, and the round-trip test
     * asserts this value back exactly - never a shortened form - because the source is a raw character
     * field and never an enumerated type.
     */
    private static final String FIXTURE_SOURCE = "System    ";

    /**
     * The amount every fully populated fixture carries: the widest value the column admits.
     *
     * <p>Nine digits before the point and two after, which is the full declared width, so the round
     * trip proves the widest legitimate value survives rather than only a convenient small one.
     */
    private static final BigDecimal FIXTURE_AMOUNT = new BigDecimal("123456789.12");

    /** A nine-character merchant identifier, zero-padded, which is character data and not a number. */
    private static final String FIXTURE_MERCHANT_ID = "000000001";

    /** A fifty-character merchant name, padded to the full column width. */
    private static final String FIXTURE_MERCHANT_NAME = padded("ACME MERCHANT", 50);

    /** A fifty-character merchant city, padded to the full column width. */
    private static final String FIXTURE_MERCHANT_CITY = padded("SEATTLE", 50);

    /** A ten-character merchant postal code, padded to the full column width. */
    private static final String FIXTURE_MERCHANT_ZIP = padded("98109", 10);

    /**
     * The sixteen-zero seed the legacy end-of-file arm supplies when the file holds no record.
     *
     * <p>Held as a constant because it is a contract value and not an arbitrary starting point: adding
     * one to it is what produces the first identifier.
     */
    private static final String ZERO_SEED = "0000000000000000";

    /** Creates the test class. */
    TransactionRepositoryIT() {
    }

    @Autowired
    private TransactionRepository repository;

    @Autowired
    private TransactionScanRepository scanRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The provider's own statement counter, which is how a specification observes how many statements a
     * finder actually caused rather than inferring it from the finder's shape.
     *
     * <p>Enabled for this class by a property on the annotation above. It is the only way to prove an
     * absence: a finder that does not issue a count query is indistinguishable from one that does, unless
     * the statements are counted.
     */
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /**
     * The configuration this test boots, named explicitly so that no component scan of the base
     * package takes place.
     *
     * <p>Each imported auto-configuration is present for a reason these tests depend on: the data
     * source binds the shared server's address, which the base class publishes; the JPA
     * auto-configuration validates the shipped mapping against the migrated schema, which is what makes
     * a clean refresh an assertion about the merchant columns; the transaction auto-configuration
     * supplies the real manager and template the allocation tests need; and the template
     * auto-configuration supplies the plain access used for catalogue and plan inspection.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = TransactionRepository.class)
    @EntityScan(basePackageClasses = Transaction.class)
    @ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        TransactionAutoConfiguration.class})
    static class PersistenceSlice {

        /** Creates the configuration. */
        PersistenceSlice() {
        }
    }

    /**
     * Proves the shipped mapping and the shipped schema agree, field by field.
     *
     * <p>A refreshed context is the first assertion in its own right: the mapping is validated against
     * the migrated schema rather than generating it, so a column named wrongly - above all one of the
     * four unprefixed merchant columns - stops the context instead of creating a second column. The
     * round trip then proves every one of the thirteen persisted fields survives storage and retrieval
     * unchanged, including the padding, which is significant.
     */
    @Nested
    @DisplayName("the shipped mapping against the migrated schema")
    final class MappingAndBootstrap {

        /** Creates the nest. */
        MappingAndBootstrap() {
        }

        @Test
        @DisplayName("the context refreshes and both the declared query and the derived reads resolve, "
                + "so a malformed query or a mismapped column could not have got this far")
        void theContextRefreshesWithEveryQueryResolved() {
            assertThat(repository)
                    .as("the repository proxy exists, so its declared query parsed at bootstrap")
                    .isNotNull();
            assertThat(scanRepository)
                    .as("the bounded reads are derived from their method names, so only a started "
                            + "context proves they resolve against the mapped attributes")
                    .isNotNull();
            assertThat(transactionTemplate)
                    .as("a real transaction template, which is what the allocation tests commit and "
                            + "roll back through")
                    .isNotNull();

            // Exercising the declared query is what proves it parsed rather than merely that a proxy
            // was created for the interface.
            assertThat(repository.findMaxId()).isNotNull();
        }

        @Test
        @DisplayName("the reference seed inserts no row here, so the table starts empty and every "
                + "identifier assertion that follows rests on that")
        void theTableStartsEmpty() {
            assertThat(repository.count())
                    .as("the reference-data seed loads no transaction at all; rows arrive only from "
                            + "posting, accrual and the online add path")
                    .isZero();
        }

        @Test
        @DisplayName("all thirteen persisted fields round-trip unchanged, including the four merchant "
                + "columns the migration leaves unprefixed")
        void allThirteenPersistedFieldsRoundTrip() {
            final String reservedId = RESERVED_IDS.get(0);
            final Transaction stored = fullyPopulatedFixture(reservedId);
            try {
                repository.insertAndFlush(stored);

                final Transaction reloaded = repository.findById(reservedId).orElseThrow();

                // 1 of 13 - the business key at offset 0, width 16.
                assertThat(reloaded.getTranId()).isEqualTo(reservedId).hasSize(KEY_WIDTH);
                // 2 of 13 - type code at offset 16, width 2.
                assertThat(reloaded.getTranTypeCd()).isEqualTo("01");
                // 3 of 13 - category code at offset 18, width 4, zero-padded and not a number.
                assertThat(reloaded.getTranCatCd()).isEqualTo("0005");
                // 4 of 13 - source at offset 22, width 10: a raw padded string, never an enumerated
                // type, and returned exactly as stored.
                assertThat(reloaded.getTranSource()).isEqualTo(FIXTURE_SOURCE);
                // 5 of 13 - description at offset 32, width 100.
                assertThat(reloaded.getTranDesc()).isEqualTo(FIXTURE_DESCRIPTION);
                // 6 of 13 - amount at offset 132, width 11, nine digits then two.
                assertThat(reloaded.getTranAmt()).isEqualTo(FIXTURE_AMOUNT);
                // 7 of 13 - merchant identifier at offset 143, width 9. UNPREFIXED column.
                assertThat(reloaded.getMerchantId()).isEqualTo(FIXTURE_MERCHANT_ID);
                // 8 of 13 - merchant name at offset 152, width 50. UNPREFIXED column.
                assertThat(reloaded.getMerchantName()).isEqualTo(FIXTURE_MERCHANT_NAME);
                // 9 of 13 - merchant city at offset 202, width 50. UNPREFIXED column.
                assertThat(reloaded.getMerchantCity()).isEqualTo(FIXTURE_MERCHANT_CITY);
                // 10 of 13 - merchant postal code at offset 252, width 10. UNPREFIXED column.
                assertThat(reloaded.getMerchantZip()).isEqualTo(FIXTURE_MERCHANT_ZIP);
                // 11 of 13 - card number at offset 262, one-based 263, width 16.
                assertThat(SensitiveValues.fingerprint(reloaded.getTranCardNum())).isEqualTo(SensitiveValues.fingerprint(SEEDED_CARD));
                assertThat(reloaded.getTranCardNum().length()).isEqualTo(KEY_WIDTH);
                // 12 of 13 - origination timestamp at offset 278, character data of width 26.
                assertThat(reloaded.getTranOrigTs())
                        .isEqualTo(FIXED_ORIGINAL_TIMESTAMP)
                        .hasSize(TIMESTAMP_WIDTH);
                // 13 of 13 - processing timestamp at offset 304, one-based 305, character data of
                // width 26.
                assertThat(reloaded.getTranProcTs())
                        .isEqualTo(END_DATE_WITH_TIME)
                        .hasSize(TIMESTAMP_WIDTH);
            } finally {
                removeReservedRows();
            }
        }

        @Test
        @DisplayName("the four unprefixed merchant columns resolve under exactly those names, which is "
                + "the asymmetry with the parallel landing table")
        void theFourMerchantColumnsCarryNoPrefix() {
            final List<String> merchantColumns = jdbcTemplate.queryForList("""
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_name = 'transaction'
                      AND column_name LIKE 'merchant%'
                    ORDER BY column_name
                    """, String.class);

            assertThat(merchantColumns)
                    .as("the migration names the merchant attribute alone on exactly these four")
                    .containsExactly("merchant_city", "merchant_id", "merchant_name", "merchant_zip");
        }
    }

    /**
     * Proves the read half of the legacy identifier rule, and the shape of the value it derives.
     *
     * <p>The increment itself belongs to the bill-payment service; what is proved here is that the
     * repository primitive reports absence over an empty table and the true maximum once rows exist,
     * which is what makes the successor well defined in both cases.
     */
    @Nested
    @DisplayName("identifier allocation: the maximum and its successor")
    final class IdentifierAllocation {

        /** Creates the nest. */
        IdentifierAllocation() {
        }

        @Test
        @DisplayName("the maximum over an empty table is absent rather than blank or zero, which is "
                + "the analogue of the legacy end-of-file arm")
        void theMaximumOverAnEmptyTableIsAbsent() {
            assertThat(repository.findMaxId())
                    .as("the aggregate over zero rows is null, which the infrastructure reports as an "
                            + "absent value rather than as a blank or a zero")
                    .isEmpty();
        }

        @Test
        @DisplayName("the first identifier derived on an empty table is the sixteen-character "
                + "zero-padded one and never a bare one")
        void theFirstIdentifierIsSixteenCharactersWide() {
            assertThat(repository.findMaxId()).isEmpty();

            // The legacy end-of-file arm moves zeros into the numeric work field, so the seed is zero
            // and the successor is one - written back into a sixteen-character field.
            final String firstIdentifier = successorOf(repository.findMaxId().orElse(ZERO_SEED));

            assertThat(firstIdentifier)
                    .as("the destination field is sixteen characters wide, so the move zero-pads")
                    .isEqualTo("0000000000000001");
            assertThat(firstIdentifier.length())
                    .as("a bare one would be one character and would sort below every stored value, "
                            + "which would silently freeze allocation")
                    .isEqualTo(KEY_WIDTH);
        }

        @Test
        @DisplayName("with several rows stored the maximum is the highest of them and the successor is "
                + "that value plus one, still sixteen wide and still zero-padded")
        void theMaximumIsTheHighestStoredIdentifier() {
            final List<String> inserted = RESERVED_IDS.subList(0, 3);
            try {
                inserted.forEach(id -> repository.insertAndFlush(fixture(id)));

                final String maximum = repository.findMaxId().orElseThrow();

                assertThat(maximum)
                        .as("the highest stored value, which under the digits-only invariant is both "
                                + "the character maximum and the numeric maximum")
                        .isEqualTo(inserted.get(inserted.size() - 1));

                final String successor = successorOf(maximum);
                assertThat(successor).isEqualTo("9900000000000031");
                assertThat(successor.length()).isEqualTo(KEY_WIDTH);
                assertThat(successor)
                        .as("still sixteen ASCII digits, which is the invariant the maximum depends on")
                        .containsOnlyDigits();
            } finally {
                removeReservedRows();
            }

            assertThat(repository.findMaxId())
                    .as("this class leaves the table as it found it")
                    .isEmpty();
        }
    }

    /**
     * Proves the allocation is a read-modify-write inside one transaction, and that it reuses a gap.
     *
     * <p>These are the two assertions that distinguish the legacy rule from a generated key, and
     * neither can be made without genuine commit and rollback boundaries - which is why this class
     * boots a real transaction manager rather than relying on a wrapper that rolls everything back.
     */
    @Nested
    @DisplayName("allocation is one transaction, and it reuses a released value")
    final class SingleTransactionAllocation {

        /** Creates the nest. */
        SingleTransactionAllocation() {
        }

        @Test
        @DisplayName("the maximum is read and the successor inserted inside ONE transaction, which is "
                + "the read-modify-write the legacy held browse performed")
        void theReadAndTheInsertShareOneTransaction() {
            final String base = RESERVED_IDS.get(4);
            try {
                repository.insertAndFlush(fixture(base));

                // One boundary, opened once: the read, the increment and the write all happen inside
                // it. Splitting them is what would let another allocator interleave; the increment
                // itself is the bill-payment service's, and this proves the primitive supports the
                // pattern rather than performing it here.
                final String minted = transactionTemplate.execute(status -> {
                    final String maximum = repository.findMaxId().orElse(ZERO_SEED);
                    final String successor = successorOf(maximum);
                    repository.insertAndFlush(fixture(successor));
                    return successor;
                });

                assertThat(minted)
                        .as("the successor of the row seeded above")
                        .isEqualTo(successorOf(base));
                assertThat(repository.findById(minted))
                        .as("the write committed with the boundary rather than needing a second one")
                        .isPresent();
                assertThat(repository.findMaxId())
                        .as("the committed row is now the table maximum, so the next allocator sees it")
                        .contains(minted);
            } finally {
                repository.deleteById(successorOf(base));
                removeReservedRows();
            }
        }

        @Test
        @DisplayName("an identifier consumed by a transaction that then ROLLS BACK is reissued to the "
                + "next allocator, which a generated key would never do")
        void aRolledBackAllocationReleasesItsIdentifierForReuse() {
            try {
                repository.insertAndFlush(fixture(GAP_BASE_ID));
                final String expected = successorOf(GAP_BASE_ID);

                // First allocator: consumes the successor and then abandons the transaction. This is
                // the gap, and the first rollback of the table's life guarantees one.
                final String consumedThenReleased = transactionTemplate.execute(status -> {
                    final String successor = successorOf(repository.findMaxId().orElse(ZERO_SEED));
                    repository.insertAndFlush(fixture(successor));
                    status.setRollbackOnly();
                    return successor;
                });

                assertThat(consumedThenReleased).isEqualTo(expected);
                assertThat(repository.findById(expected))
                        .as("the rollback removed the row, so the value is unused again")
                        .isEmpty();
                assertThat(repository.findMaxId())
                        .as("the maximum fell back to the surviving row, which is what makes the value "
                                + "available a second time")
                        .contains(GAP_BASE_ID);

                // Second allocator: derives from the same maximum and therefore takes the same value.
                final String reissued = transactionTemplate.execute(status ->
                        successorOf(repository.findMaxId().orElse(ZERO_SEED)));

                assertThat(reissued)
                        .as("the gap is REUSED. A generated key never reissues a value it has handed "
                                + "out, so it would have stepped permanently past this one and diverged "
                                + "from the legacy numbering for the remaining life of the table")
                        .isEqualTo(consumedThenReleased);
            } finally {
                repository.deleteById(successorOf(GAP_BASE_ID));
                repository.deleteById(GAP_BASE_ID);
            }
        }

        @Test
        @DisplayName("the shipped schema declares no generated key of any kind for this table, so the "
                + "maximum-plus-one rule is the only allocator")
        void theSchemaDeclaresNoGeneratedKey() {
            final List<String> generatedColumns = jdbcTemplate.queryForList("""
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_name = 'transaction'
                      AND (is_identity = 'YES'
                           OR is_generated <> 'NEVER'
                           OR column_default IS NOT NULL)
                    ORDER BY column_name
                    """, String.class);

            assertThat(generatedColumns)
                    .as("no identity column, no generated column and no default expression: the "
                            + "identifier arrives already populated or not at all")
                    .isEmpty();
        }
    }

    /**
     * Proves why one transaction is necessary but not sufficient, and what closes the remaining window.
     *
     * <p>Under the isolation this module runs at, an insert another transaction has not committed is
     * invisible, so two allocators sharing nothing but a transaction each can read the same maximum and
     * collide. The advisory lock the insert fragment publishes is the concurrency policy that admits one
     * allocator at a time.
     *
     * <p><strong>Which tests here call the production fragment, and which deliberately do not.</strong> The
     * two tests that assert the lock's behaviour call {@code repository.lockIdentifierAllocation} - the
     * shipped method, through the repository proxy, over a pooled connection enlisted in a real Spring
     * transaction - because the property under test is a property of the shipped operation and of nothing
     * else. The remaining test is the <em>counterfactual</em>: it shows two allocators genuinely colliding
     * when no lock is taken at all, and it necessarily uses raw sessions, since there is no production
     * method that reads the maximum without locking first. A hand-written statement is the right instrument
     * for demonstrating the absence of the shipped one and the wrong instrument for demonstrating its
     * presence.
     *
     * <p>The service-level counterpart lives in {@code service/BillPaymentConcurrencyIT}, which runs whole
     * turns of the shipped bill-payment service from two threads.
     */
    @Nested
    @DisplayName("the allocation lock under genuine concurrency")
    final class ConcurrentAllocation {

        /** Creates the nest. */
        ConcurrentAllocation() {
        }

        @Test
        @DisplayName("the PRODUCTION fragment's lock is EXCLUSIVE across sessions: while the shipped method "
                + "holds it a second session cannot take it, and the shipped unit's commit releases it")
        void theProductionFragmentsLockIsExclusiveAcrossSessions() throws Exception {
            final ExecutorService prober = Executors.newSingleThreadExecutor();
            try (Connection probe = connect()) {
                probe.setAutoCommit(false);
                try {
                    // The shipped method, called through the repository proxy inside a real Spring
                    // transaction. That is what proves the production connection is enlisted: an
                    // unenlisted call would run in its own implicit transaction and the lock would already
                    // have been released by the time the probe below ran.
                    final boolean refusedWhileHeld = transactionTemplate.execute(status -> {
                        repository.lockIdentifierAllocation(
                                TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY);
                        try {
                            return !prober.submit(() -> tryTakeAllocationLock(probe))
                                    .get(LOCK_HANDOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        } catch (final InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("the probe was interrupted", interrupted);
                        } catch (final ExecutionException | TimeoutException problem) {
                            throw new IllegalStateException("the probe did not report", problem);
                        }
                    });

                    assertThat(refusedWhileHeld)
                            .as("a second session is refused while the shipped method's transaction holds "
                                    + "the lock, which is what admits one allocator at a time")
                            .isTrue();

                    // Releasing is the commit of the unit the shipped method ran in; there is no explicit
                    // unlock to forget, and by here that unit has completed.
                    assertThat(tryTakeAllocationLock(probe))
                            .as("the lock is released by the commit, so the next allocator is admitted")
                            .isTrue();
                } finally {
                    probe.rollback();
                }
            } finally {
                prober.shutdownNow();
                assertThat(prober.awaitTermination(LOCK_HANDOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("no probing thread outlives this test")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a ROLLED-BACK shipped unit releases the lock too, so a failed allocation does not "
                + "wedge every later one")
        void aRolledBackShippedUnitReleasesTheLock() throws SQLException {
            transactionTemplate.execute(status -> {
                repository.lockIdentifierAllocation(
                        TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY);
                status.setRollbackOnly();
                return null;
            });

            try (Connection probe = connect()) {
                probe.setAutoCommit(false);
                try {
                    assertThat(tryTakeAllocationLock(probe))
                            .as("the lock is transaction scoped, so a rollback releases it exactly as a "
                                    + "commit does - a lock a failed allocator kept would stop every "
                                    + "later one")
                            .isTrue();
                } finally {
                    probe.rollback();
                }
            }
        }

        @Test
        @DisplayName("WITHOUT the lock two allocators genuinely collide: both read the same maximum, "
                + "both derive the same successor, and the second insert is refused on the primary key")
        void withoutTheLockTwoAllocatorsCollide() throws SQLException {
            try (Connection first = connect(); Connection second = connect()) {
                first.setAutoCommit(false);
                second.setAutoCommit(false);
                try {
                    seedConcurrencyBase();

                    // Both read before either writes, which is exactly what this isolation permits: an
                    // uncommitted insert is invisible, so a shared transaction is not enough on its own.
                    final String firstSeen = highestIdentifier(first);
                    final String secondSeen = highestIdentifier(second);
                    assertThat(firstSeen)
                            .as("the base row this nest seeded is the table maximum, which is what makes "
                                    + "every identifier it derives predictable and removable")
                            .isEqualTo(CONCURRENCY_IDS.get(0));
                    assertThat(secondSeen)
                            .as("neither allocator can see the other's pending work")
                            .isEqualTo(firstSeen);

                    insertTransaction(first, successorOf(firstSeen));
                    first.commit();

                    assertThatExceptionOfType(SQLException.class)
                            .as("the loser is refused for a reason that has nothing to do with its own "
                                    + "work, which is the defect the advisory lock closes")
                            .isThrownBy(() -> {
                                insertTransaction(second, successorOf(secondSeen));
                                second.commit();
                            })
                            .withMessageContaining("pk_transaction");
                } finally {
                    second.rollback();
                    first.rollback();
                    removeConcurrencyRows();
                }
            }
        }

        @Test
        @DisplayName("UNDER the PRODUCTION lock the second allocator waits for the first shipped unit to "
                + "commit and then reads a maximum that already includes the first row, so neither collides")
        void underTheLockTheSecondAllocatorWaitsAndSeesTheFirstRow() throws Exception {
            final ExecutorService waiter = Executors.newSingleThreadExecutor();
            final ExecutorService contender = Executors.newSingleThreadExecutor();
            final CountDownLatch firstHoldsTheLock = new CountDownLatch(1);
            final CountDownLatch firstMayCommit = new CountDownLatch(1);
            final String firstIdentifier = successorOf(CONCURRENCY_IDS.get(0));
            try {
                seedConcurrencyBase();

                // Every step of both allocators is a shipped method: the lock, the maximum, the insert.
                // Nothing here is a lookalike statement, so what is observed is the production allocation
                // span under real contention.
                final Future<String> firstAllocation = waiter.submit(() -> transactionTemplate
                        .execute(status -> {
                            repository.lockIdentifierAllocation(
                                    TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY);
                            final String minted =
                                    successorOf(repository.findMaxId().orElse(ZERO_SEED));
                            repository.insertAndFlush(fixture(minted));
                            firstHoldsTheLock.countDown();
                            try {
                                firstMayCommit.await(LOCK_HANDOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            } catch (final InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            return minted;
                        }));

                assertThat(firstHoldsTheLock.await(LOCK_HANDOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("the first allocator must hold the lock before the second one starts")
                        .isTrue();

                // The second allocator runs on a thread of its own, so the timeout probe below observes
                // the shipped method actually waiting on the lock.
                final Future<String> secondAllocation = contender
                        .submit(() -> transactionTemplate.execute(status -> {
                            repository.lockIdentifierAllocation(
                                    TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY);
                            final String minted =
                                    successorOf(repository.findMaxId().orElse(ZERO_SEED));
                            repository.insertAndFlush(fixture(minted));
                            return minted;
                        }));

                assertThatExceptionOfType(TimeoutException.class)
                        .as("the second allocator is still waiting on the lock the shipped method took, "
                                + "which is the serialisation the legacy region obtained from its held "
                                + "browse")
                        .isThrownBy(() -> secondAllocation.get(LOCK_WAIT_PROBE_MILLIS,
                                TimeUnit.MILLISECONDS));

                firstMayCommit.countDown();

                assertThat(firstAllocation.get(LOCK_HANDOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("the first allocator minted the successor of the seeded base")
                        .isEqualTo(firstIdentifier);
                assertThat(secondAllocation.get(LOCK_HANDOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("the re-read under the lock sees the committed row, so the successor is the "
                                + "next value and not a duplicate")
                        .isEqualTo(successorOf(firstIdentifier));
            } finally {
                waiter.shutdownNow();
                contender.shutdownNow();
                assertThat(waiter.awaitTermination(LOCK_HANDOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("no waiting thread outlives this test")
                        .isTrue();
                assertThat(contender.awaitTermination(LOCK_HANDOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("no contending thread outlives this test either")
                        .isTrue();
                repository.deleteById(successorOf(firstIdentifier));
                repository.deleteById(firstIdentifier);
                repository.flush();
                removeConcurrencyRows();
            }
        }
    }

    /**
     * Proves the write is an insert and never a merge.
     *
     * <p>The identifier is an assigned business key, so the ordinary save operation would treat a
     * populated identifier as a possibly existing row and merge it - overwriting a row where the legacy
     * keyed write reported a duplicate instead. The narrower operation the write paragraphs require
     * keeps that duplicate observable at the boundary that owns the legacy response.
     */
    @Nested
    @DisplayName("insert-only assigned-key persistence")
    final class InsertOnlyPersistence {

        /** Creates the nest. */
        InsertOnlyPersistence() {
        }

        @Test
        @DisplayName("a duplicate assigned identifier is refused and the existing row is NOT merged")
        void aDuplicateAssignedIdentifierIsRefusedWithoutOverwriting() {
            final String reservedId = RESERVED_IDS.get(0);
            final Transaction original = fixture(reservedId);
            final Transaction replacement = fixture(reservedId);
            replacement.setTranDesc("replacement that must never be merged");
            try {
                assertThat(repository.insertAndFlush(original))
                        .as("the operation returns the same managed instance it was given")
                        .isSameAs(original);

                assertThatExceptionOfType(DataIntegrityViolationException.class)
                        .as("the duplicate reaches the database and is refused there, which is what "
                                + "keeps the legacy already-exists response reachable")
                        .isThrownBy(() -> repository.insertAndFlush(replacement));

                assertThat(repository.findById(reservedId))
                        .get()
                        .extracting(Transaction::getTranDesc)
                        .as("the stored row is untouched: no upsert and no merge fallback exists")
                        .isEqualTo(FIXTURE_DESCRIPTION);
            } finally {
                removeReservedRows();
            }
        }
    }

    /**
     * Proves the digits-only invariant the character maximum depends on, at both enforcement points.
     *
     * <p>A single stored value of any other shape would sort above every well-formed identifier and
     * silently freeze allocation without breaking compilation, so the invariant is enforced twice: by
     * the entity before a statement is issued, and by a check constraint for a writer that never
     * constructs an entity at all.
     */
    @Nested
    @DisplayName("the identifier shape the maximum depends on")
    final class IdentifierShape {

        /** Creates the nest. */
        IdentifierShape() {
        }

        @Test
        @DisplayName("the entity refuses a short identifier before any statement reaches the server")
        void theEntityRefusesAShortIdentifier() {
            assertThatExceptionOfType(InvalidDataAccessApiUsageException.class)
                    .as("the provider raises the guard's own failure and the framework translates it, "
                            + "so a service sees a data-access failure carrying the original cause")
                    .isThrownBy(() -> {
                        repository.save(fixture("123"));
                        repository.flush();
                    })
                    .withRootCauseInstanceOf(IllegalArgumentException.class)
                    .withMessageContaining("tranId")
                    .withMessageContaining("exactly 16 characters");
        }

        @Test
        @DisplayName("the check constraint refuses a short identifier that arrives without an entity, "
                + "which is the case the entity callback cannot cover")
        void theCheckConstraintRefusesAShortIdentifier() throws SQLException {
            try (Connection connection = connect();
                    PreparedStatement insert = connection.prepareStatement(INSERT_ROW_SQL)) {
                insert.setString(1, "9");
                insert.setString(2, SEEDED_CARD);
                insert.setString(3, TestDataFactory.BLANK_PROCESSING_TIMESTAMP);

                assertThatExceptionOfType(SQLException.class)
                        .as("a bulk load or a migration script bypassing the entity is refused too")
                        .isThrownBy(insert::executeUpdate)
                        .withMessageContaining("ck_transaction_tran_id_digits");
            }
        }
    }

    /**
     * Proves the bounded ordered reads the list browse and the batch archive scan share.
     *
     * <p>Four reads are published - inclusive and exclusive, ascending and descending - and no offset
     * page at all. The pairing is the point: the inclusive form is the positioning command, whose first
     * row is the record the browse positions on, and the exclusive form is the continuation, which must
     * not repeat the row already handed out. All four are derived from their method names, so only a
     * started context proves they resolve, and only real SQL proves the bound is applied by the store
     * rather than by the caller.
     */
    @Nested
    @DisplayName("the bounded keyset reads of the list browse and the archive scan")
    final class KeysetBrowse {

        /** Creates the nest. */
        KeysetBrowse() {
        }

        @Test
        @DisplayName("the inclusive forward read opens on the boundary row and the exclusive read "
                + "continues past it, so successive windows neither repeat nor skip a row")
        void inclusiveOpenAndExclusiveContinuationTileTheSequence() {
            try {
                seedReservedRows();
                final Limit pair = Limit.of(2);

                final List<Transaction> opening = scanRepository
                        .findByTranIdGreaterThanEqualOrderByTranIdAsc(RESERVED_IDS.get(0), pair);
                assertThat(identifiersOf(opening))
                        .as("the opening read is inclusive, so its first row is the one positioned on")
                        .containsExactly(RESERVED_IDS.get(0), RESERVED_IDS.get(1));

                final String lastHandedOut = RESERVED_IDS.get(1);
                final List<Transaction> continuation = scanRepository
                        .findByTranIdGreaterThanOrderByTranIdAsc(lastHandedOut, pair);
                assertThat(identifiersOf(continuation))
                        .as("the continuation is strict, so the row already handed out is not repeated")
                        .containsExactly(RESERVED_IDS.get(2), RESERVED_IDS.get(3));
            } finally {
                removeReservedRows();
            }
        }

        @Test
        @DisplayName("the backward reads are descending, and reversing the read order yields the "
                + "ascending page the legacy backward fill presented")
        void theBackwardReadsAreDescendingAndReverseIntoScreenOrder() {
            try {
                seedReservedRows();
                final Limit pair = Limit.of(2);

                final List<Transaction> openingBackwards = scanRepository
                        .findByTranIdLessThanEqualOrderByTranIdDesc(RESERVED_IDS.get(3), pair);
                assertThat(identifiersOf(openingBackwards))
                        .as("descending is the READ order: the highest identifier at or below the cursor "
                                + "comes back first, because the legacy fill placed it in the bottom slot")
                        .containsExactly(RESERVED_IDS.get(3), RESERVED_IDS.get(2));

                assertThat(identifiersOf(openingBackwards).reversed())
                        .as("reversing yields the order the operator saw, which ascends exactly like a "
                                + "forward page; presenting the read order would invert the screen")
                        .containsExactly(RESERVED_IDS.get(2), RESERVED_IDS.get(3));

                final List<Transaction> continuationBackwards = scanRepository
                        .findByTranIdLessThanOrderByTranIdDesc(RESERVED_IDS.get(2), pair);
                assertThat(identifiersOf(continuationBackwards))
                        .as("the strict backward form does not repeat the boundary row either")
                        .containsExactly(RESERVED_IDS.get(1), RESERVED_IDS.get(0));
            } finally {
                removeReservedRows();
            }
        }

        @Test
        @DisplayName("every read honours its limit, and a blank bound opens the sequence forwards while "
                + "yielding nothing backwards")
        void boundsAndLimitsBehaveAtTheEndsOfTheSequence() {
            try {
                seedReservedRows();

                assertThat(scanRepository.findByTranIdGreaterThanOrderByTranIdAsc(
                                ZERO_SEED, Limit.of(3)))
                        .as("the limit is applied by the store, not by the caller")
                        .hasSize(3);

                final String blankBound = " ".repeat(KEY_WIDTH);
                assertThat(identifiersOf(scanRepository
                        .findByTranIdGreaterThanEqualOrderByTranIdAsc(blankBound, Limit.of(1))))
                        .as("a blank bound is below every stored identifier, so it opens at the first row")
                        .containsExactly(RESERVED_IDS.get(0));

                assertThat(scanRepository
                        .findByTranIdLessThanEqualOrderByTranIdDesc(blankBound, Limit.of(1)))
                        .as("read backwards the same blank bound is below everything, so it yields "
                                + "nothing at all - an empty list and never null")
                        .isNotNull()
                        .isEmpty();
            } finally {
                removeReservedRows();
            }
        }
    }

    /**
     * Proves the inherited page-of-ten browse the list screen is assembled from.
     *
     * <p>The page size is the screen's and not this table's, so no size is declared on the repository:
     * the sort travels with the request, which is exactly what leaves a descending sort available to the
     * service that needs one. A page is asserted as an ordered list rather than as a set, because a
     * page presented in the wrong sequence is a defect the legacy screen would have shown.
     */
    @Nested
    @DisplayName("the inherited page-of-ten browse")
    final class PageableBrowse {

        /** Creates the nest. */
        PageableBrowse() {
        }

        @Test
        @DisplayName("an ascending page of ten returns exactly ten identifier-ordered rows and reports a "
                + "second page when eleven rows exist")
        void anAscendingPageOfTenReportsItsTotalsCorrectly() {
            try {
                seedReservedRows(ROWS_EXCEEDING_ONE_PAGE);

                final Page<Transaction> firstPage = repository.findAll(
                        PageRequest.of(0, SCREEN_ROWS, Sort.by("tranId")));

                assertThat(firstPage.getContent())
                        .as("a full page is exactly the screen's row count")
                        .hasSize(SCREEN_ROWS);
                assertThat(identifiersOf(firstPage.getContent()))
                        .as("ordered by identifier ascending, asserted as a LIST because the sequence is "
                                + "what the screen presented")
                        .containsExactlyElementsOf(RESERVED_IDS.subList(0, SCREEN_ROWS));
                assertThat(firstPage.getTotalElements()).isEqualTo(ROWS_EXCEEDING_ONE_PAGE);
                assertThat(firstPage.getTotalPages())
                        .as("eleven rows over a page of ten is two pages")
                        .isEqualTo(2);

                final Page<Transaction> secondPage = repository.findAll(
                        PageRequest.of(1, SCREEN_ROWS, Sort.by("tranId")));
                assertThat(identifiersOf(secondPage.getContent()))
                        .as("the remainder, and no row repeated from the first page")
                        .containsExactly(RESERVED_IDS.get(SCREEN_ROWS));
            } finally {
                removeReservedRows();
            }
        }

        @Test
        @DisplayName("the equivalent descending sort returns the rows in descending order, which is the "
                + "order a backward fill reads them in")
        void aDescendingSortReturnsRowsInDescendingOrder() {
            try {
                seedReservedRows(ROWS_EXCEEDING_ONE_PAGE);

                final Page<Transaction> descending = repository.findAll(
                        PageRequest.of(0, SCREEN_ROWS, Sort.by(Sort.Direction.DESC, "tranId")));

                assertThat(identifiersOf(descending.getContent()))
                        .as("descending, asserted as an ORDERED list: the highest identifier first, "
                                + "which is the order the legacy backward path read them in")
                        .containsExactlyElementsOf(
                                RESERVED_IDS.subList(1, ROWS_EXCEEDING_ONE_PAGE).reversed());
            } finally {
                removeReservedRows();
            }
        }
    }

    /**
     * Proves the amount is exact decimal data at a fixed scale, and that storage truncates.
     *
     * <p>The estate contains no rounding clause on any arithmetic statement, so every store into a
     * two-decimal field discards the excess rather than rounding it. That is reproduced here rather than
     * improved: the conventional half-even choice would differ by one unit of the last place on about
     * half of all values, which is a parity defect invisible to a test written under the same wrong
     * assumption.
     *
     * <p>Nothing here aggregates the amount, and no aggregate over it exists to assert. The report
     * program performs no arithmetic at all and accumulates its breaks line by line at the fixed
     * 133-character width, so a set-based total would both relocate that accumulation and move the point
     * at which truncation happens. Recorded in {@code docs/decision-log.md} DL-200.
     */
    @Nested
    @DisplayName("decimal fidelity of the stored amount")
    final class DecimalFidelity {

        /** Creates the nest. */
        DecimalFidelity() {
        }

        @Test
        @DisplayName("the amount round-trips at scale 2 with its full nine integer digits and no "
                + "exponent notation")
        void theAmountRoundTripsAtScaleTwo() {
            final String reservedId = RESERVED_IDS.get(0);
            try {
                repository.insertAndFlush(fullyPopulatedFixture(reservedId));

                final BigDecimal reloaded = repository.findById(reservedId)
                        .orElseThrow()
                        .getTranAmt();

                assertThat(reloaded.scale())
                        .as("the column is fixed-precision with two decimal places, and the scale is "
                                + "part of the value rather than a display choice")
                        .isEqualTo(2);
                assertThat(reloaded)
                        .as("isEqualTo compares scale as well as magnitude, which is the comparison that "
                                + "proves no scale drift occurred")
                        .isEqualTo(FIXTURE_AMOUNT);
                assertThat(reloaded.toPlainString())
                        .as("the plain form carries no exponent, so the widest legitimate value is not "
                                + "reformatted on the way back")
                        .isEqualTo("123456789.12");
                assertThat(reloaded.precision() - reloaded.scale())
                        .as("nine digits before the decimal point, which is the declared field width")
                        .isEqualTo(9);
            } finally {
                removeReservedRows();
            }
        }

        @Test
        @DisplayName("a negative amount keeps its sign, which the return direction of the posting rules "
                + "depends on")
        void aNegativeAmountKeepsItsSign() {
            final String reservedId = RESERVED_IDS.get(1);
            final BigDecimal negative = new BigDecimal("-987654321.99");
            try {
                final Transaction row = fullyPopulatedFixture(reservedId);
                row.setTranAmt(negative);
                repository.insertAndFlush(row);

                final BigDecimal reloaded = repository.findById(reservedId)
                        .orElseThrow()
                        .getTranAmt();

                assertThat(reloaded).isEqualTo(negative);
                assertThat(reloaded.scale()).isEqualTo(2);
                assertThat(reloaded.signum())
                        .as("the legacy field is signed, and the operator-originated return direction "
                                + "is the negative one")
                        .isNegative();
                assertThat(reloaded.toPlainString()).isEqualTo("-987654321.99");
            } finally {
                removeReservedRows();
            }
        }

        @Test
        @DisplayName("an amount carrying more than two decimal places is TRUNCATED toward zero on the "
                + "way in, never rounded up")
        void anOverScaledAmountIsTruncatedTowardZero() {
            final String reservedId = RESERVED_IDS.get(2);
            try {
                // The excess digits would round UP under the conventional choice; the estate declares no
                // rounding anywhere, so the store discards them instead.
                final Transaction row = fullyPopulatedFixture(reservedId);
                row.setTranAmt(new BigDecimal("1.999"));
                repository.insertAndFlush(row);

                final BigDecimal reloaded = repository.findById(reservedId)
                        .orElseThrow()
                        .getTranAmt();

                assertThat(reloaded)
                        .as("truncation toward zero: the excess is discarded, so this is 1.99 and not "
                                + "2.00. Half-even or half-up would have produced the latter")
                        .isEqualTo(new BigDecimal("1.99"));
                assertThat(reloaded.scale()).isEqualTo(2);

                final Transaction negativeRow = repository.findById(reservedId).orElseThrow();
                negativeRow.setTranAmt(new BigDecimal("-1.999"));
                repository.saveAndFlush(negativeRow);

                assertThat(repository.findById(reservedId).orElseThrow().getTranAmt())
                        .as("toward zero in the negative direction too, so this is -1.99 and not -2.00")
                        .isEqualTo(new BigDecimal("-1.99"));
            } finally {
                removeReservedRows();
            }
        }
    }

    /**
     * Proves the stored character values are returned exactly as supplied, padding and all.
     *
     * <p>Leading zeros and trailing padding are contractual rather than cosmetic: the external sorts and
     * the record image address these fields by position and width, so a value that lost a zero or a
     * space would no longer be the value the legacy contract names. Nothing in this nest shortens a
     * value before comparing it.
     */
    @Nested
    @DisplayName("string and key fidelity")
    final class StringFidelity {

        /** Creates the nest. */
        StringFidelity() {
        }

        @Test
        @DisplayName("a stored identifier is found by its exact sixteen characters, and an absent one "
                + "yields an empty result rather than a failure")
        void lookupByKeyIsExactAndAbsenceIsAValue() {
            final String reservedId = RESERVED_IDS.get(0);
            try {
                repository.insertAndFlush(fixture(reservedId));

                assertThat(repository.findById(reservedId)).isPresent();
                assertThat(repository.findById("9999999999999999"))
                        .as("absence is a return value here and never an exception")
                        .isEmpty();
            } finally {
                removeReservedRows();
            }
        }

        @Test
        @DisplayName("a leading-zero identifier round-trips unchanged and does NOT match its unpadded "
                + "form, and the same holds for the category code and the merchant identifier")
        void leadingZerosAreSignificantAcrossEveryPaddedField() {
            final String leadingZeroId = "0000000000000042";
            try {
                final Transaction row = fullyPopulatedFixture(leadingZeroId);
                repository.insertAndFlush(row);

                final Transaction reloaded = repository.findById(leadingZeroId).orElseThrow();

                assertThat(reloaded.getTranId())
                        .as("stored verbatim, leading zeros included")
                        .isEqualTo(leadingZeroId)
                        .hasSize(KEY_WIDTH);
                assertThat(repository.findById("42"))
                        .as("the unpadded form is a different key and matches nothing")
                        .isEmpty();

                assertThat(reloaded.getTranCatCd())
                        .as("a four-character zero-padded category code is character data, so it is not "
                                + "equal to its unpadded form")
                        .isEqualTo("0005")
                        .isNotEqualTo("5");
                assertThat(reloaded.getMerchantId())
                        .as("a nine-character zero-padded merchant identifier, likewise character data")
                        .isEqualTo(FIXTURE_MERCHANT_ID)
                        .hasSize(9)
                        .isNotEqualTo("1");
            } finally {
                repository.deleteById(leadingZeroId);
            }
        }

        @Test
        @DisplayName("the padded source and the padded merchant fields come back with their padding "
                + "intact, because the columns are bounded rather than blank-padded")
        void paddingSurvivesTheRoundTrip() {
            final String reservedId = RESERVED_IDS.get(3);
            try {
                repository.insertAndFlush(fullyPopulatedFixture(reservedId));

                final Transaction reloaded = repository.findById(reservedId).orElseThrow();

                assertThat(reloaded.getTranSource())
                        .as("ten characters including the padding, and never an enumerated type")
                        .isEqualTo(FIXTURE_SOURCE)
                        .hasSize(10);
                assertThat(reloaded.getMerchantName()).isEqualTo(FIXTURE_MERCHANT_NAME).hasSize(50);
                assertThat(reloaded.getMerchantCity()).isEqualTo(FIXTURE_MERCHANT_CITY).hasSize(50);
                assertThat(reloaded.getMerchantZip()).isEqualTo(FIXTURE_MERCHANT_ZIP).hasSize(10);
            } finally {
                removeReservedRows();
            }
        }
    }

    /**
     * Proves the shipped schema carries exactly the constraints and the one access path the estate had.
     *
     * <p>Both objects are asserted by name, because a name is what a later migration can rename without
     * breaking compilation. The index is the third and last alternate-index equivalent in the module and
     * is deliberately non-unique, since the legacy path it replaces admitted duplicate keys.
     */
    @Nested
    @DisplayName("the shipped constraints and the one access path")
    final class SchemaContract {

        /** Creates the nest. */
        SchemaContract() {
        }

        @Test
        @DisplayName("the card foreign key exists under its shipped name, references the card master, "
                + "and is ENFORCED against a card that no row backs")
        void theCardForeignKeyExistsAndIsEnforced() {
            final List<String> referenced = jdbcTemplate.queryForList("""
                    SELECT confrelid::regclass::text
                    FROM pg_constraint
                    WHERE conname = ?
                      AND contype = 'f'
                      AND conrelid = 'transaction'::regclass
                    """, String.class, "fk_transaction_card");

            assertThat(referenced)
                    .as("declared on this table, of the foreign-key kind, and pointing at the card master")
                    .containsExactly("card");

            final String reservedId = RESERVED_IDS.get(0);
            final Transaction orphan = fixture(reservedId);
            orphan.setTranCardNum(TestDataFactory.UNKNOWN_CARD_NUMBER);
            try {
                assertThatExceptionOfType(DataIntegrityViolationException.class)
                        .as("a card number no seeded row backs is refused by the database, which is what "
                                + "obliges every fixture here to name a seeded one")
                        .isThrownBy(() -> repository.insertAndFlush(orphan))
                        .withMessageContaining("fk_transaction_card");
            } finally {
                repository.deleteById(reservedId);
            }
        }

        @Test
        @DisplayName("the processing-timestamp index exists under its shipped name and is NON-UNIQUE, "
                + "and no index is created over the card number")
        void theProcessingTimestampIndexIsNonUniqueAndTheCardColumnHasNone() {
            final List<Boolean> unique = jdbcTemplate.queryForList("""
                    SELECT x.indisunique
                    FROM pg_index x
                    JOIN pg_class i ON i.oid = x.indexrelid
                    WHERE i.relname = ?
                    """, Boolean.class, "idx_transaction_tran_proc_ts");

            assertThat(unique)
                    .as("emitted exactly once even though two job members describe it, and non-unique "
                            + "because the legacy path it replaces admitted duplicate keys")
                    .containsExactly(Boolean.FALSE);

            final List<String> cardColumnIndexes = jdbcTemplate.queryForList("""
                    SELECT indexname
                    FROM pg_indexes
                    WHERE tablename = 'transaction'
                      AND indexdef LIKE '%tran_card_num%'
                    ORDER BY indexname
                    """, String.class);

            assertThat(cardColumnIndexes)
                    .as("the legacy estate defined an alternate index over the processing timestamp and "
                            + "none over the card number, and that asymmetry is reproduced rather than "
                            + "smoothed over")
                    .isEmpty();
        }
    }

    /**
     * Proves the highest-key read is a single-row read and issues no count.
     *
     * <p>The add screen positions its browse at the end of the key sequence and reads backward once
     * [app/cbl/COTRN02C.cbl:L444, L475], which lands on the highest key present. An earlier revision
     * expressed that as page zero of a descending sort at size one, and read only the page's content. A
     * page carries a total, so the provider issued a second statement counting every row of the transaction
     * master - on a screen that displays one row, on every copy-last and every add, with nothing ever
     * reading the figure.
     *
     * <p>The difference is invisible in the result and visible only in the statement count, which is why
     * this group counts statements rather than asserting a shape. Recorded as {@code DL-296}.
     */
    @Nested
    @DisplayName("the highest-key read: one statement, and no count of a table nothing counts")
    final class HighestKeyRead {

        /** Creates the nest. */
        HighestKeyRead() {
        }

        @Test
        @DisplayName("the highest-key finder issues exactly ONE statement, while the descending page it "
                + "replaced issues TWO - the second being a count nothing reads")
        void theHighestKeyFinderIssuesOneStatementAndThePageIssuedTwo() {
            try {
                seedReservedRows();

                final long forTheFinder = statementsIssuedBy(
                        () -> assertThat(repository.findFirstByOrderByTranIdDesc()).isPresent());
                final long forThePage = statementsIssuedBy(() -> assertThat(repository
                        .findAll(PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "tranId")))
                        .getContent()).hasSize(1));

                assertThat(forTheFinder)
                        .as("one statement: the row, and nothing else")
                        .isEqualTo(1L);
                assertThat(forThePage)
                        .as("THE MEASUREMENT THAT MATTERS. The shape it replaced cost a second statement "
                                + "over the same window - a count of the whole master that nothing reads. "
                                + "Were these equal, this specification would be vacuous and the finder "
                                + "could be changed back without any test noticing.")
                        .isEqualTo(2L)
                        .isGreaterThan(forTheFinder);
            } finally {
                removeReservedRows();
            }
        }

        @Test
        @DisplayName("it returns the row with the highest identifier, and an empty result on an empty "
                + "table - which is the end-of-file response that seeds zero")
        void itReturnsTheHighestRowAndEmptyOnAnEmptyTable() {
            try {
                seedReservedRows();

                assertThat(repository.findFirstByOrderByTranIdDesc())
                        .get()
                        .extracting(Transaction::getTranId)
                        .as("the reserved identifiers sort above every seeded row, so the highest of them "
                                + "is the table maximum")
                        .isEqualTo(RESERVED_IDS.get(3));
                assertThat(repository.findFirstByOrderByTranIdDesc()
                        .map(Transaction::getTranId))
                        .as("and it agrees with the identifier-only form the allocator uses, which is what "
                                + "keeps two answers to one question from diverging")
                        .isEqualTo(repository.findMaxId());
            } finally {
                removeReservedRows();
            }

            assertThat(repository.findFirstByOrderByTranIdDesc().map(Transaction::getTranId))
                    .as("with the reserved rows removed, no reserved identifier is the maximum any more")
                    .isNotPresent()
                    .isNotEqualTo(Optional.of(RESERVED_IDS.get(3)));
        }

        @Test
        @DisplayName("on a table holding exactly one row it returns that row, still in one statement - the "
                + "boundary between the empty response and a browse")
        void itReadsTheSoleRowOfAOneRowTableInOneStatement() {
            try {
                seedReservedRows(1);

                final long forTheFinder = statementsIssuedBy(() -> assertThat(repository
                        .findFirstByOrderByTranIdDesc()
                        .map(Transaction::getTranId))
                        .as("the sole row is both the first and the last of the descending sequence, so a "
                                + "bound applied one row early would return nothing here and a bound "
                                + "applied one row late would still return it - only the exact bound "
                                + "yields this row from this table")
                        .contains(RESERVED_IDS.get(0)));

                assertThat(forTheFinder)
                        .as("and the statement count does not depend on how many rows exist, which is the "
                                + "whole of what a bound in the finder's name buys")
                        .isEqualTo(1L);
                assertThat(repository.findMaxId())
                        .as("the identifier-only form agrees at this boundary too")
                        .contains(RESERVED_IDS.get(0));
            } finally {
                removeReservedRows();
            }
        }
    }

    /**
     * Counts the statements the provider issued while running an action.
     *
     * <p>Statistics are enabled for this class by a property on its annotation. The counter is cleared
     * first, so the figure is this action's and not the accumulation of every specification before it.
     *
     * @param  action the action to run
     * @return how many statements the provider prepared while it ran
     */
    private long statementsIssuedBy(final Runnable action) {
        final Statistics statistics = entityManagerFactory
                .unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        action.run();
        return statistics.getPrepareStatementCount();
    }

    /**
     * Proves the inclusive date window over the processing timestamp, and the two traps inside it.
     *
     * <p>This is the most subtle contract the table carries. The stored field is twenty-six characters and
     * a date bound is ten, so an inclusive upper bound written against the whole column drops every record
     * processed on the end date, because a longer string whose leading characters equal a shorter one sorts
     * above it. That is the first trap, and it is a wrong-answer trap.
     *
     * <p>The second trap is the obvious repair. Wrapping the column in a ten-character prefix admits the
     * end-date record and is <strong>unindexable</strong>, because a function of a column cannot drive an
     * index over that column; the upper bound survives only as a filter applied to rows the server has
     * already read. It gives the right answer by the wrong access path, which is why it is invisible in a
     * result set and visible only in a plan.
     *
     * <p>The shipped contract avoids both by taking an <strong>exclusive bound at the day after</strong>
     * the inclusive end date. Any instant on the end date compares below the next day's date; midnight on
     * the next day compares above it. So the admitted set is exactly the inclusive one, and both bounds are
     * bare column comparisons that one index range serves.
     *
     * <p>That predicate is a <strong>declared repository contract</strong>, and every assertion below calls
     * it:
     * {@link TransactionRepository#findByProcessingDateWindow(String, String,
     * org.springframework.data.domain.Limit)} states the window in inclusive terms and derives the
     * exclusive bound itself, so no caller performs that arithmetic. It is the module's only declared
     * consumer of the processing-timestamp alternate index. What is proved here is therefore the callable
     * contract and not a statement this class invented: that the end-date record is admitted while a
     * whole-column bound drops it, that both dates are inclusive and neighbouring days are not, that an
     * unprocessed record is excluded by the lower bound alone with no emptiness test anywhere, that the
     * ordering is the processing timestamp's with the base key as tie-break, that the result is bounded by
     * the caller's limit, that a window matching nothing yields an empty list, that BOTH bounds become
     * index conditions, and that the superseded prefix form - which selects the same rows - does not.
     *
     * <p>Every row here is constructed. The table is seeded with no rows at all, and the delivered daily
     * fixture carries a blank processing timestamp on every one of its records, so no seeded data can
     * exercise a date window.
     */
    @Nested
    @DisplayName("the inclusive date window over the processing timestamp, and its access path")
    final class ProcessingTimestampWindow {

        /** Creates the nest. */
        ProcessingTimestampWindow() {
        }

        @Test
        @DisplayName("a record processed ON the end date IS selected by the exclusive next-day bound, "
                + "while an inclusive whole-column comparison against the same date DROPS it")
        void theEndDateRecordIsAdmittedByTheExclusiveNextDayBound() {
            try {
                seedWindowRows();

                // The shipped contract, stated in the inclusive terms the legacy condition states.
                assertThat(selectWindow(WINDOW_START, WINDOW_END))
                        .as("the end-date record is admitted, which is the whole reason the upper bound "
                                + "is the day after rather than the end date itself")
                        .contains(WINDOW_IDS.get(3));

                // The same selection written naively, which is the wrong-answer defect.
                assertThat(selectWindowNaively(WINDOW_START, WINDOW_END))
                        .as("an inclusive whole-column upper bound silently drops the end-date record, "
                                + "and would have under-reported every window ending on a day with "
                                + "activity")
                        .doesNotContain(WINDOW_IDS.get(3));

                // The same facts at the character level, independent of any server.
                assertThat(END_DATE_WITH_TIME.compareTo(WINDOW_END) <= 0)
                        .as("as a whole string the stored value sorts ABOVE the ten-character end date")
                        .isFalse();
                assertThat(END_DATE_WITH_TIME
                        .compareTo(TransactionRepository.exclusiveUpperBoundOf(WINDOW_END)) < 0)
                        .as("and BELOW the day after it, because the two differ within the first ten "
                                + "characters - which is what makes the bare comparison correct")
                        .isTrue();
                assertThat(END_DATE_WITH_TIME.substring(0, DATE_PREFIX_WIDTH).compareTo(WINDOW_END) <= 0)
                        .as("the superseded prefix form was correct too, which is why it survived review "
                                + "on its result set alone")
                        .isTrue();
                assertThat(END_DATE_WITH_TIME)
                        .as("the stored value really does carry a time component beyond the date")
                        .hasSize(TIMESTAMP_WIDTH)
                        .startsWith(WINDOW_END)
                        .isNotEqualTo(WINDOW_END);
            } finally {
                removeWindowRows();
            }
        }

        @Test
        @DisplayName("midnight on the day AFTER the window is excluded, so the exclusive bound admits "
                + "the end date and nothing beyond it")
        void midnightOnTheDayAfterTheWindowIsExcluded() {
            final String midnightAfter = DAY_AFTER_WINDOW_END + " 00:00:00.000000";
            try {
                seedWindowRows();
                repository.insertAndFlush(windowFixture(BOUNDARY_ID, midnightAfter,
                        SEEDED_CARDS.get(0)));

                assertThat(selectWindow(WINDOW_START, WINDOW_END))
                        .as("the very first instant outside the window is outside it, which is the other "
                                + "half of what makes an exclusive next-day bound equivalent to an "
                                + "inclusive end date")
                        .doesNotContain(BOUNDARY_ID);

                assertThat(midnightAfter
                        .compareTo(TransactionRepository.exclusiveUpperBoundOf(WINDOW_END)) < 0)
                        .as("its leading ten characters EQUAL the bound and it is longer, so as a whole "
                                + "string it sorts above the bound")
                        .isFalse();
            } finally {
                repository.deleteById(BOUNDARY_ID);
                removeWindowRows();
            }
        }

        @Test
        @DisplayName("the derived bound is the day after, across a month end, a year end and a leap "
                + "day, and an impossible date is refused rather than shifted")
        void theDerivedBoundIsTheDayAfter() {
            assertThat(TransactionRepository.exclusiveUpperBoundOf("2022-06-30"))
                    .as("a month end rolls into the next month")
                    .isEqualTo("2022-07-01");
            assertThat(TransactionRepository.exclusiveUpperBoundOf("2022-12-31"))
                    .as("a year end rolls into the next year")
                    .isEqualTo("2023-01-01");
            assertThat(TransactionRepository.exclusiveUpperBoundOf("2024-02-28"))
                    .as("a leap year has a twenty-ninth of February")
                    .isEqualTo("2024-02-29");
            assertThat(TransactionRepository.exclusiveUpperBoundOf("2023-02-28"))
                    .as("a common year does not")
                    .isEqualTo("2023-03-01");
            assertThat(TransactionRepository.exclusiveUpperBoundOf("2022-06-01"))
                    .as("and every result is exactly ten characters, which is what keeps the comparison "
                            + "a like-for-like character comparison")
                    .hasSize(DATE_PREFIX_WIDTH);

            assertThatExceptionOfType(DateTimeParseException.class)
                    .as("a date that does not exist is refused, never normalised into a neighbour - a "
                            + "silently shifted bound would silently shift the window")
                    .isThrownBy(() -> TransactionRepository.exclusiveUpperBoundOf("2023-02-29"));
            assertThatExceptionOfType(DateTimeParseException.class)
                    .isThrownBy(() -> TransactionRepository.exclusiveUpperBoundOf("2022-13-01"));
        }

        @Test
        @DisplayName("both bounds are inclusive: a record processed on the start date is selected, and "
                + "records one day either side of the window are not")
        void bothBoundsAreInclusiveAndNeighbouringDaysAreExcluded() {
            try {
                seedWindowRows();

                final List<String> selected = selectWindow(WINDOW_START, WINDOW_END);

                assertThat(selected)
                        .as("the lower bound is inclusive, so a record processed on the start date is in")
                        .contains(WINDOW_IDS.get(1));
                assertThat(selected)
                        .as("a record inside the window is in, so the selection admits more than its "
                                + "boundaries")
                        .contains(WINDOW_IDS.get(2));
                assertThat(selected)
                        .as("one day before the start bound is out")
                        .doesNotContain(WINDOW_IDS.get(0));
                assertThat(selected)
                        .as("one day after the end bound is out")
                        .doesNotContain(WINDOW_IDS.get(4));
            } finally {
                removeWindowRows();
            }
        }

        @Test
        @DisplayName("an unprocessed record carrying twenty-six blanks is excluded by the LOWER bound "
                + "alone, with no null test and no blank test anywhere")
        void anUnprocessedRecordIsExcludedByTheLowerBoundAlone() {
            try {
                seedWindowRows();

                assertThat(selectWindow(WINDOW_START, WINDOW_END))
                        .as("its blank prefix sorts below every date bound, so the lower bound already "
                                + "rejects it - the selection needs no emptiness guard of its own")
                        .doesNotContain(WINDOW_IDS.get(5));

                // The reason, stated at the character level: a blank prefix is below any date literal.
                assertThat(TestDataFactory.BLANK_PROCESSING_TIMESTAMP.compareTo(WINDOW_START) >= 0)
                        .as("blanks sort below the digits of any date, so the bare lower bound suffices")
                        .isFalse();
                assertThat(TestDataFactory.BLANK_PROCESSING_TIMESTAMP)
                        .as("the unprocessed value really is twenty-six blanks and not an absent value, "
                                + "which is why no emptiness test is involved")
                        .hasSize(TIMESTAMP_WIDTH)
                        .isBlank();
            } finally {
                removeWindowRows();
            }
        }

        @Test
        @DisplayName("the selected records are ordered by PROCESSING TIMESTAMP ascending, which is the "
                + "index this query exists to reach and not the card-number order the report applies")
        void theSelectionIsOrderedByProcessingTimestampAscending() {
            try {
                seedWindowRows();

                final List<String> selected = selectWindow(WINDOW_START, WINDOW_END);

                // The three admitted rows carry card numbers deliberately out of step with their dates,
                // so a card-number ordering and a timestamp ordering produce DIFFERENT sequences and the
                // assertion below can only pass for one of them. Asserted as an ORDERED list, never a set.
                assertThat(selected)
                        .as("ascending by processing timestamp: start date, then mid-window, then the "
                                + "end-date row")
                        .containsExactly(WINDOW_IDS.get(1), WINDOW_IDS.get(2), WINDOW_IDS.get(3));
                assertThat(selected)
                        .as("the card-number order would have been a different sequence, so the ordering "
                                + "really is the timestamp's - the report's card ordering is applied by "
                                + "the job over its own unloaded generation, not by this query")
                        .isNotEqualTo(List.of(WINDOW_IDS.get(2), WINDOW_IDS.get(1), WINDOW_IDS.get(3)));
            } finally {
                removeWindowRows();
            }
        }

        @Test
        @DisplayName("records sharing a processing timestamp are ordered by IDENTIFIER, so the result is "
                + "deterministic rather than dependent on the plan the server chose")
        void aSharedTimestampIsBrokenByTheBaseKey() {
            final String sharedTimestamp = processingTimestampFor(WITHIN_WINDOW);
            try {
                removeWindowRows();
                // Written in DESCENDING identifier order, so returning them ascending cannot be an
                // accident of insertion sequence.
                repository.insertAndFlush(windowFixture(WINDOW_IDS.get(2), sharedTimestamp,
                        SEEDED_CARDS.get(4)));
                repository.insertAndFlush(windowFixture(WINDOW_IDS.get(1), sharedTimestamp,
                        SEEDED_CARDS.get(0)));

                assertThat(selectWindow(WINDOW_START, WINDOW_END))
                        .as("the tie-break is the unique base cluster key, ascending")
                        .containsExactly(WINDOW_IDS.get(1), WINDOW_IDS.get(2));
            } finally {
                removeWindowRows();
            }
        }

        @Test
        @DisplayName("the result is BOUNDED by the caller's limit, and the rows returned are the first "
                + "ones in the ordering rather than an arbitrary subset")
        void theResultIsBoundedByTheCallersLimit() {
            try {
                seedWindowRows();

                assertThat(selectWindow(WINDOW_START, WINDOW_END, Limit.of(2)))
                        .as("a window over a growing table has no safe unbounded shape, so the caller "
                                + "states what it will accept and receives the leading rows of the "
                                + "ordering")
                        .containsExactly(WINDOW_IDS.get(1), WINDOW_IDS.get(2));
                assertThat(selectWindow(WINDOW_START, WINDOW_END, Limit.of(1)))
                        .containsExactly(WINDOW_IDS.get(1));
                assertThat(selectWindow(WINDOW_START, WINDOW_END, Limit.of(3)))
                        .as("a limit at or above the admitted count returns them all")
                        .containsExactly(WINDOW_IDS.get(1), WINDOW_IDS.get(2), WINDOW_IDS.get(3));

                assertThatExceptionOfType(NullPointerException.class)
                        .as("an absent bound is refused rather than treated as unbounded")
                        .isThrownBy(() ->
                                repository.findByProcessingDateWindow(WINDOW_START, WINDOW_END, null));
            } finally {
                removeWindowRows();
            }
        }

        @Test
        @DisplayName("a window matching nothing returns an EMPTY list and never a null")
        void aWindowMatchingNothingReturnsAnEmptyList() {
            try {
                seedWindowRows();

                assertThat(selectWindow("1990-01-01", "1990-01-31"))
                        .as("a window over a period with no activity selects nothing, and reports that "
                                + "as an empty list")
                        .isNotNull()
                        .isEmpty();
            } finally {
                removeWindowRows();
            }
        }

        @Test
        @DisplayName("BOTH bounds become index conditions and NO predicate survives as a filter, which "
                + "is the whole point of the exclusive bound")
        void bothBoundsDriveTheShippedIndex() {
            try {
                seedWindowRows();

                assertThat(selectWindowByPlannedStatement(WINDOW_START,
                        TransactionRepository.exclusiveUpperBoundOf(WINDOW_END)))
                        .as("the statement whose plan is read below selects exactly what the production "
                                + "query selects, in exactly the same order, so the plan is evidence "
                                + "about the shipped predicate and not about a lookalike")
                        .isEqualTo(selectWindow(WINDOW_START, WINDOW_END));

                final String plan = String.join(System.lineSeparator(), explainWindowSelection());

                assertThat(plan)
                        .as("the shipped index is chosen for this selection")
                        .contains("idx_transaction_tran_proc_ts");
                assertThat(plan)
                        .as("reached by an index scan of some shape rather than by reading every row")
                        .containsPattern("(Index Scan|Index Only Scan|Bitmap Index Scan)");
                assertThat(plan)
                        .as("both bounds are bare column comparisons, so both appear in the index "
                                + "condition and the server reads one range instead of a wider one")
                        .contains("Index Cond")
                        .contains(WINDOW_START)
                        .contains(TransactionRepository.exclusiveUpperBoundOf(WINDOW_END));
                assertThat(plan)
                        .as("no function of the column appears anywhere in the plan, so nothing is left "
                                + "to be evaluated per row")
                        .doesNotContain("substring");
                assertThat(plan.lines().filter(line -> line.contains("Filter:")).toList())
                        .as("and no predicate survives as a row filter at all")
                        .isEmpty();
            } finally {
                removeWindowRows();
            }
        }

        @Test
        @DisplayName("the SUPERSEDED prefix form selects the same rows and leaves its upper bound as a "
                + "row FILTER, which is why the result set could never have revealed the defect")
        void theSupersededPrefixFormLeavesItsUpperBoundAsAFilter() {
            try {
                seedWindowRows();

                assertThat(selectWindowByPrefixBound(WINDOW_START, WINDOW_END))
                        .as("the superseded form is not WRONG - it selects exactly the same rows in the "
                                + "same order, and that is precisely why a review of results rather than "
                                + "of plans passed it")
                        .isEqualTo(selectWindow(WINDOW_START, WINDOW_END));

                final String plan =
                        String.join(System.lineSeparator(), explainPrefixWindowSelection());

                assertThat(plan)
                        .as("the bare LOWER bound still reaches the index, so the defect was never a "
                                + "whole-table scan - it was a wider index range than necessary")
                        .contains("Index Cond")
                        .contains(WINDOW_START);
                assertThat(plan)
                        .as("but the wrapped UPPER bound cannot be an index condition, so it is applied "
                                + "to rows the server has already read")
                        .contains("substring");
                assertThat(plan.lines().filter(line -> line.contains("Filter:")).toList())
                        .as("evidenced as a filter line in the plan, which the shipped form has none of")
                        .isNotEmpty();
            } finally {
                removeWindowRows();
            }
        }
    }

    // =================================================================================================
    // FIXTURES AND HELPERS
    //
    // Every statement below is a fixed literal with bound parameters. No value is ever assembled into
    // SQL text, and no query is built by joining fragments together.
    // =================================================================================================

    /**
     * The selection the window query performs, written exactly as the contract requires.
     *
     * <p><strong>Both bounds are bare column comparisons.</strong> The lower bound is inclusive and the
     * upper bound is exclusive, and the upper bound is the day <em>after</em> the window's inclusive end
     * date - which admits exactly the records an inclusive end date was meant to admit, because any
     * instant on the end date compares below the next day's date while midnight on the next day compares
     * above it. Neither operand is wrapped in a function, so the whole predicate is one index range.
     *
     * <p>The ordering is the processing timestamp ascending with the unique base key as the tie-break,
     * which is the index this query exists to reach.
     */
    private static final String WINDOW_SELECT_SQL = """
            SELECT t.tran_id
            FROM transaction t
            WHERE t.tran_proc_ts >= ?
              AND t.tran_proc_ts < ?
            ORDER BY t.tran_proc_ts ASC, t.tran_id ASC
            """;

    /**
     * The same selection with an inclusive whole-column upper bound taken against a ten-character date,
     * which is the first defect the exclusive bound avoids.
     *
     * <p>Retained deliberately and used in exactly one assertion: to demonstrate on this server that this
     * form drops every record processed on the end date. It is never the contract.
     */
    private static final String NAIVE_WINDOW_SELECT_SQL = """
            SELECT t.tran_id
            FROM transaction t
            WHERE t.tran_proc_ts >= ?
              AND t.tran_proc_ts <= ?
            ORDER BY t.tran_proc_ts ASC
            """;

    /**
     * The superseded form: correct row set, unindexable upper bound.
     *
     * <p>Retained deliberately and used in exactly one assertion, which compares its access plan against
     * the shipped one. It admits the same rows as the contract - that is why it was written - but because
     * the column is wrapped in a function the upper bound cannot be an index condition, so it survives
     * only as a filter applied to rows the server has already read. That difference is the whole of the
     * finding, and it is invisible in the result set and visible only in the plan.
     */
    private static final String PREFIX_WINDOW_SELECT_SQL = """
            SELECT t.tran_id
            FROM transaction t
            WHERE t.tran_proc_ts >= ?
              AND SUBSTRING(t.tran_proc_ts, 1, 10) <= ?
            ORDER BY t.tran_proc_ts ASC, t.tran_id ASC
            """;

    /** The access plan for the window selection, taken as shape only and never as cost or duration. */
    private static final String EXPLAIN_WINDOW_SELECT_SQL = "EXPLAIN " + WINDOW_SELECT_SQL;

    /** The access plan for the superseded prefix form, read only to contrast it with the shipped one. */
    private static final String EXPLAIN_PREFIX_WINDOW_SELECT_SQL =
            "EXPLAIN " + PREFIX_WINDOW_SELECT_SQL;

    /**
     * Inserts one row without an entity, so that a database-side constraint can be exercised directly.
     *
     * <p>Three values are bound - the identifier, the card number and the processing timestamp - and
     * every other column is a fixed literal, because no test varies them through this path.
     */
    private static final String INSERT_ROW_SQL = """
            INSERT INTO transaction (
                tran_id, tran_type_cd, tran_cat_cd, tran_source, tran_desc, tran_amt,
                merchant_id, merchant_name, merchant_city, merchant_zip, tran_card_num,
                tran_orig_ts, tran_proc_ts)
            VALUES (?, '01', '0005', 'System    ', 'concurrency fixture', 1.00,
                '000000001', 'merchant', 'city', 'zip', ?,
                '2022-06-10 19:27:53.000000', ?)
            """;

    /** Removes one row by identifier, used by the cleanup that keeps this class idempotent. */
    private static final String DELETE_ROW_SQL = "DELETE FROM transaction WHERE tran_id = ?";

    /** The read half of the allocation rule, expressed directly so a raw session can perform it. */
    private static final String MAX_IDENTIFIER_SQL = "SELECT MAX(tran_id) FROM transaction";

    /**
     * Attempts the shipped allocation lock without waiting, reporting whether it was granted.
     *
     * <p>This is the one lock statement this class still writes by hand, and it is written by hand because
     * there is no production method for it: the shipped fragment WAITS, which is exactly the behaviour under
     * test, so a probe that reports whether the lock is currently held has to be a non-waiting attempt. It
     * is only ever used by an observer session, never by an allocator.
     */
    private static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_xact_lock(?)";

    /**
     * Pads a value with blanks to the declared width of its column.
     *
     * <p>Used to build fixtures whose padding is significant. Deliberately not a formatting call: a
     * format would take its behaviour from the ambient locale, and these are fixed-width character
     * fields whose content must be identical on every host.
     *
     * @param value the value to pad, which must not already exceed the width
     * @param width the declared column width
     * @return the value followed by enough blanks to reach the width
     */
    private static String padded(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Projects the identifiers of a window of rows, preserving their order.
     *
     * @param window the rows to project
     * @return their identifiers in the order the rows were returned
     */
    private static List<String> identifiersOf(final List<Transaction> window) {
        return window.stream().map(Transaction::getTranId).toList();
    }

    /**
     * Builds one transaction carrying the supplied identifier and otherwise legitimate values.
     *
     * <p>The processing timestamp is left blank, which is the unprocessed shape, so a row built here
     * never accidentally satisfies a date window. Every row names a seeded card number, because the
     * shipped foreign key refuses anything else.
     *
     * @param tranId the identifier to carry, which may deliberately be malformed
     * @return a transaction ready to be stored
     */
    private static Transaction fixture(final String tranId) {
        return TestDataFactory.transaction()
                .id(tranId)
                .typeCode("01")
                .categoryCode("0005")
                .source(FIXTURE_SOURCE)
                .description(FIXTURE_DESCRIPTION)
                .amount(new BigDecimal("1.00"))
                .merchantId(FIXTURE_MERCHANT_ID)
                .cardNumber(SEEDED_CARD)
                .originalTimestamp(FIXED_ORIGINAL_TIMESTAMP)
                .processingTimestamp(TestDataFactory.BLANK_PROCESSING_TIMESTAMP)
                .build();
    }

    /**
     * Builds one transaction with an explicit value in every one of the thirteen persisted fields.
     *
     * <p>Each value is at the full declared width of its column and carries its padding, so a round trip
     * over this row proves the mapping rather than merely exercising it.
     *
     * @param tranId the identifier to carry
     * @return a fully populated transaction ready to be stored
     */
    private static Transaction fullyPopulatedFixture(final String tranId) {
        return TestDataFactory.transaction()
                .id(tranId)
                .typeCode("01")
                .categoryCode("0005")
                .source(FIXTURE_SOURCE)
                .description(FIXTURE_DESCRIPTION)
                .amount(FIXTURE_AMOUNT)
                .merchantId(FIXTURE_MERCHANT_ID)
                .merchantName(FIXTURE_MERCHANT_NAME)
                .merchantCity(FIXTURE_MERCHANT_CITY)
                .merchantZip(FIXTURE_MERCHANT_ZIP)
                .cardNumber(SEEDED_CARD)
                .originalTimestamp(FIXED_ORIGINAL_TIMESTAMP)
                .processingTimestamp(END_DATE_WITH_TIME)
                .build();
    }

    /**
     * Builds one window fixture: a row carrying a given processing timestamp and card number.
     *
     * @param tranId            the reserved identifier to carry
     * @param processingTs      the twenty-six character processing timestamp, supplied as text
     * @param cardNumber        a seeded card number, which the foreign key requires
     * @return a transaction ready to be stored
     */
    private static Transaction windowFixture(final String tranId, final String processingTs,
                                             final String cardNumber) {
        return TestDataFactory.transaction()
                .id(tranId)
                .typeCode("01")
                .categoryCode("0005")
                .source(FIXTURE_SOURCE)
                .description(FIXTURE_DESCRIPTION)
                .amount(new BigDecimal("1.00"))
                .merchantId(FIXTURE_MERCHANT_ID)
                .cardNumber(cardNumber)
                .originalTimestamp(FIXED_ORIGINAL_TIMESTAMP)
                .processingTimestamp(processingTs)
                .build();
    }

    /**
     * Renders a ten-character date as a twenty-six character processing timestamp.
     *
     * <p>Blank-filled rather than time-bearing, which is the shape a date-only fixture carries. It is
     * still a value whose whole-column comparison against a ten-character bound fails, which is why the
     * end-date fixture uses the time-bearing form instead: either shape demonstrates the trap, and the
     * time-bearing one is the more realistic of the two.
     *
     * @param date the ten-character date
     * @return the date followed by blanks to twenty-six characters
     */
    private static String processingTimestampFor(final String date) {
        return padded(date, TIMESTAMP_WIDTH);
    }

    /**
     * Reproduces the legacy increment: read the maximum as a number, add one, and move it back into a
     * sixteen-character field, which zero-pads it.
     *
     * <p>The locale is pinned for the same reason the production encoder pins it: the field is sixteen
     * ASCII digits, and an unqualified format would have taken its digits from the ambient formatting
     * locale. A default locale whose numbering system is not Latin would have produced sixteen
     * characters that are digits to a reader and not digits to the column.
     *
     * @param maximum the current maximum, or the sixteen-zero seed when the table is empty
     * @return the next identifier, always sixteen ASCII digits
     */
    private static String successorOf(final String maximum) {
        return String.format(Locale.ROOT, "%016d", Long.parseLong(maximum) + 1L);
    }

    /** The bound every window assertion passes when it is not measuring the bound itself. */
    private static final Limit AMPLE_WINDOW_LIMIT = Limit.of(100);

    /**
     * Selects the date window through the PRODUCTION repository contract, stated in inclusive terms.
     *
     * <p>Every window assertion in this class calls the shipped default method rather than a statement
     * written here, so what is proved is the callable contract including its derivation of the exclusive
     * upper bound.
     *
     * @param startBound the inclusive ten-character lower bound
     * @param endBound   the inclusive ten-character upper bound
     * @return the selected identifiers, ordered by processing timestamp then identifier, both ascending
     */
    private List<String> selectWindow(final String startBound, final String endBound) {
        return selectWindow(startBound, endBound, AMPLE_WINDOW_LIMIT);
    }

    /**
     * Selects the date window through the production contract under an explicit bound.
     *
     * @param startBound the inclusive ten-character lower bound
     * @param endBound   the inclusive ten-character upper bound
     * @param limit      the greatest number of records the caller will accept
     * @return the selected identifiers, ordered by processing timestamp then identifier, both ascending
     */
    private List<String> selectWindow(final String startBound, final String endBound,
            final Limit limit) {
        return identifiersOf(repository.findByProcessingDateWindow(startBound, endBound, limit));
    }

    /**
     * Selects the window through the statement whose access plan is asserted.
     *
     * <p>It exists so the plan assertion is about a statement provably equivalent to the production
     * query: the equivalence is asserted, row for row and in order, rather than assumed. It is given the
     * DERIVED exclusive bound, exactly as the production query receives it.
     *
     * @param startBound        the inclusive ten-character lower bound
     * @param endExclusiveBound the exclusive ten-character upper bound
     * @return the selected identifiers, ordered by processing timestamp then identifier
     */
    private List<String> selectWindowByPlannedStatement(final String startBound,
            final String endExclusiveBound) {
        return jdbcTemplate.queryForList(WINDOW_SELECT_SQL, String.class, startBound,
                endExclusiveBound);
    }

    /**
     * Selects the window through an inclusive whole-column upper bound, which is the first defect.
     *
     * @param startBound the inclusive ten-character lower bound
     * @param endBound   the ten-character upper bound, compared against the whole column
     * @return the selected identifiers
     */
    private List<String> selectWindowNaively(final String startBound, final String endBound) {
        return jdbcTemplate.queryForList(NAIVE_WINDOW_SELECT_SQL, String.class, startBound, endBound);
    }

    /**
     * Selects the window through the superseded ten-character prefix upper bound.
     *
     * @param startBound the inclusive ten-character lower bound
     * @param endBound   the inclusive ten-character upper bound, compared against the date prefix
     * @return the selected identifiers, ordered by processing timestamp then identifier
     */
    private List<String> selectWindowByPrefixBound(final String startBound, final String endBound) {
        return jdbcTemplate.queryForList(PREFIX_WINDOW_SELECT_SQL, String.class, startBound, endBound);
    }

    /**
     * Returns the access plan for the window selection, as lines of plan text.
     *
     * <p>The plan is taken with sequential scanning disabled for the enclosing transaction, and that is
     * a correctness measure rather than a thumb on the scale. This table holds only the handful of rows
     * a test has just written, and at that size the planner will read every row whatever indexes exist,
     * so an unconstrained plan would report the table's current size rather than whether the predicate
     * can use the index at all. Disabling the alternative asks the question the assertion is actually
     * about - <em>is the bare lower bound indexable</em> - and gives the same answer whatever the row
     * count happens to be. The setting is scoped to the transaction and is discarded with it.
     *
     * <p>Only the SHAPE of the plan is ever asserted: which index is chosen, and which predicate becomes
     * the index condition. No cost, no row estimate and no timing is read, and the plan is not requested
     * with execution.
     *
     * @return the plan text, one element per line
     */
    private List<String> explainWindowSelection() {
        return explainWith(EXPLAIN_WINDOW_SELECT_SQL, WINDOW_START,
                TransactionRepository.exclusiveUpperBoundOf(WINDOW_END));
    }

    /**
     * Returns the access plan for the superseded prefix form, as lines of plan text.
     *
     * @return the plan text, one element per line
     */
    private List<String> explainPrefixWindowSelection() {
        return explainWith(EXPLAIN_PREFIX_WINDOW_SELECT_SQL, WINDOW_START, WINDOW_END);
    }

    /**
     * Takes an access plan for a two-parameter window statement under identical planner conditions.
     *
     * @param explainStatement the {@code EXPLAIN} statement to run
     * @param lowerBound       the lower bound to bind
     * @param upperBound       the upper bound to bind
     * @return the plan text, one element per line
     */
    private List<String> explainWith(final String explainStatement, final String lowerBound,
            final String upperBound) {

        return transactionTemplate.execute(status -> {
            status.setRollbackOnly();
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            return jdbcTemplate.queryForList(explainStatement, String.class, lowerBound, upperBound);
        });
    }

    /**
     * Stores the first four reserved rows, which is what the keyset assertions tile over.
     */
    private void seedReservedRows() {
        seedReservedRows(4);
    }

    /**
     * Stores the first {@code count} reserved rows in ascending identifier order.
     *
     * @param count how many reserved identifiers to store
     */
    private void seedReservedRows(final int count) {
        RESERVED_IDS.subList(0, count).forEach(id -> repository.insertAndFlush(fixture(id)));
    }

    /**
     * Removes every reserved row, restoring the empty table the seed leaves behind.
     *
     * <p>Deletion is unconditional per identifier rather than conditional on presence, so a test that
     * failed part-way still leaves the table clean for the next one.
     */
    private void removeReservedRows() {
        RESERVED_IDS.forEach(id -> repository.deleteById(id));
        repository.flush();
    }

    /**
     * Stores the six window rows: one before the start bound, one on it, one inside the window, one on
     * the end bound carrying a time component, one after the end bound, and one left unprocessed.
     *
     * <p>The card numbers are assigned deliberately out of step with both the identifiers and the dates,
     * so that only an ordering by card number can produce the sequence the ordering assertion expects.
     */
    private void seedWindowRows() {
        removeWindowRows();
        repository.insertAndFlush(windowFixture(WINDOW_IDS.get(0),
                processingTimestampFor(DAY_BEFORE_WINDOW_START), SEEDED_CARDS.get(1)));
        repository.insertAndFlush(windowFixture(WINDOW_IDS.get(1),
                processingTimestampFor(WINDOW_START), SEEDED_CARDS.get(2)));
        repository.insertAndFlush(windowFixture(WINDOW_IDS.get(2),
                processingTimestampFor(WITHIN_WINDOW), SEEDED_CARDS.get(0)));
        repository.insertAndFlush(windowFixture(WINDOW_IDS.get(3),
                END_DATE_WITH_TIME, SEEDED_CARDS.get(4)));
        repository.insertAndFlush(windowFixture(WINDOW_IDS.get(4),
                processingTimestampFor(DAY_AFTER_WINDOW_END), SEEDED_CARDS.get(3)));
        repository.insertAndFlush(windowFixture(WINDOW_IDS.get(5),
                TestDataFactory.BLANK_PROCESSING_TIMESTAMP, SEEDED_CARDS.get(1)));
    }

    /** Removes every window row, restoring the empty table the seed leaves behind. */
    private void removeWindowRows() {
        WINDOW_IDS.forEach(id -> repository.deleteById(id));
        repository.flush();
    }

    /**
     * Attempts the allocation lock on the supplied session without waiting, which is how a second
     * session can report whether the lock is currently held elsewhere.
     *
     * @param connection the session to attempt the lock on, with autocommit already disabled
     * @return {@code true} when the lock was granted
     * @throws SQLException if the statement fails
     */
    private static boolean tryTakeAllocationLock(final Connection connection) throws SQLException {
        try (PreparedStatement attempt = connection.prepareStatement(TRY_LOCK_SQL)) {
            attempt.setLong(1, TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY);
            try (ResultSet granted = attempt.executeQuery()) {
                assertThat(granted.next()).isTrue();
                return granted.getBoolean(1);
            }
        }
    }

    /**
     * Reads the highest stored identifier on the supplied session, which is the read half of the legacy
     * allocation rule expressed over the key column.
     *
     * @param connection the session to read on
     * @return the highest stored identifier, or the sixteen-zero seed when the table holds no rows
     * @throws SQLException if the statement fails
     */
    private static String highestIdentifier(final Connection connection) throws SQLException {
        try (Statement maximum = connection.createStatement();
                ResultSet row = maximum.executeQuery(MAX_IDENTIFIER_SQL)) {
            assertThat(row.next()).isTrue();
            final String highest = row.getString(1);
            return highest == null ? ZERO_SEED : highest;
        }
    }

    /**
     * Inserts one transaction on the supplied session without committing, so a caller controls when the
     * row becomes visible to another session.
     *
     * @param connection the session to insert on
     * @param tranId     the identifier to store
     * @throws SQLException if the insert fails, which the collision test relies on
     */
    private static void insertTransaction(final Connection connection, final String tranId)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(INSERT_ROW_SQL)) {
            insert.setString(1, tranId);
            insert.setString(2, SEEDED_CARD);
            insert.setString(3, TestDataFactory.BLANK_PROCESSING_TIMESTAMP);
            insert.executeUpdate();
        }
    }

    /**
     * Commits the base row the concurrency nest derives its identifiers from, so that the table maximum
     * is a known value rather than whatever the run happens to have left behind.
     *
     * @throws SQLException if the insert fails
     */
    private static void seedConcurrencyBase() throws SQLException {
        removeConcurrencyRows();
        try (Connection connection = connect()) {
            insertTransaction(connection, CONCURRENCY_IDS.get(0));
        }
    }

    /**
     * Removes every row the concurrency nest can have created, restoring the empty table the seed leaves
     * behind so the emptiness assertions elsewhere still hold.
     *
     * @throws SQLException if the delete fails
     */
    private static void removeConcurrencyRows() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement delete = connection.prepareStatement(DELETE_ROW_SQL)) {
            for (final String tranId : CONCURRENCY_IDS) {
                delete.setString(1, tranId);
                delete.executeUpdate();
            }
        }
    }
}
