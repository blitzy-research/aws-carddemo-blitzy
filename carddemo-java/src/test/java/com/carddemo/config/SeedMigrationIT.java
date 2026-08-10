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

package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.support.AbstractPostgresIT;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Applies the two seed migrations to a real PostgreSQL server and asserts what they actually put in
 * the tables, then applies the production LOCATION LIST to a second schema and asserts they put nothing
 * there - and are not even resolved there.
 *
 * <h2>Why this cannot be a unit test, and why it cannot share the shared schema</h2>
 *
 * <p>The seeds are static SQL. Nothing in the module reads them, nothing binds them and no Java type
 * describes them, so the only way to observe what they do is to run them against the database they
 * were written for. That rules out a unit test outright: a mocked connection would assert the text of
 * the file, which is the thing under test.
 *
 * <p>It also cannot use the schema the shared base class migrates. {@link AbstractPostgresIT} brings
 * the default schema to the schema-only head deliberately, so every other integration test observes a
 * production-shaped, row-free baseline and can insert exactly the rows it means to. Seeding six hundred
 * and thirty-six rows into that schema would change what every one of those tests sees. So this class
 * reuses the shared <em>server</em> - starting a second container would double the slowest part of the
 * suite - and migrates two schemas of its own:
 *
 * <ul>
 *   <li>{@value #HEAD_SCHEMA}, migrated from BOTH delivered locations with no target, which is what
 *       the local and test profiles resolve;</li>
 *   <li>{@value #PRODUCTION_SHAPED_SCHEMA}, migrated from the SCHEMA LOCATION ALONE, also with no
 *       target, which is what production resolves. The two differ in the location list and in nothing
 *       else - not in the target, not in any other setting - which is precisely what makes the
 *       comparison evidence rather than illustration: a difference in outcome can only be attributed to
 *       the one variable that was changed. See docs/decision-log.md DL-298.</li>
 * </ul>
 *
 * <p>The migration scripts declare no schema of their own, so the schema each one lands in is decided
 * entirely by the migration tool's default-schema setting. That is what makes this two-schema
 * arrangement possible and it is worth naming, because a script that qualified its own object names
 * would silently ignore the setting and write into the shared schema instead.
 *
 * <h2>Where the expected values come from</h2>
 *
 * <p>Every expectation below was derived from the fixture files the seeds transcribe -
 * {@code app/data/ASCII/*.txt} for the reference volumes and {@code app/jcl/DUSRSECJ.jcl} for the ten
 * sign-on identities - by reading the fixed-width records at their documented offsets. No expectation
 * is read out of the migration script, because the script is what is being checked. Volumes and
 * compositions are asserted as aggregates because three hundred rows cannot be hand-typed; two named
 * rows are additionally asserted field by field, so an aggregate that happened to be right for the
 * wrong reason still fails.
 *
 * <h2>The four properties this exists to protect</h2>
 *
 * <ol>
 *   <li><strong>The volumes.</strong> Six hundred and thirty-six rows across nine tables, each count
 *       matching the record count of the fixture it comes from. A truncated seed produces a suite that
 *       passes with less data than it claims.</li>
 *   <li><strong>The compositions that make branches reachable.</strong> Three disclosure groups of
 *       seventeen rows each, one of them carrying nothing but a zero rate, are what make both arms of
 *       the interest calculation's rate lookup reachable from seed data alone. Two hundred and fifty
 *       positive and fifty negative transaction amounts are what make both signed directions of the
 *       balance computation reachable. Neither is an accident of the fixture and neither may be lost.</li>
 *   <li><strong>The blanks that carry meaning.</strong> Display text is stored right-trimmed, with three
 *       documented exceptions where a trailing blank is behaviourally significant. Trimming any of the
 *       three would break a lookup or fabricate a processed timestamp.</li>
 *   <li><strong>No credential in cleartext, and none in production.</strong> Ten seeded identities,
 *       every credential a distinct digest, and the whole seed withheld from a production migration by
 *       the LOCATION LIST alone. The two seeds ship from {@code db/migration/seed} and production
 *       resolves only {@code db/migration/schema}, so a production-shaped run does not resolve them at
 *       all - they appear in NO state, neither applied nor pending nor above a target. The tests below
 *       prove it where it lives, by running the two location lists against the same server and showing
 *       that the production-shaped run creates the whole schema with every seeded table empty and the
 *       two seed versions absent from its migration state entirely. A version ceiling of {@code 2} used
 *       to be the control; it is gone because it also froze the schema at that version, and the last
 *       test in the nest is the one that could not have passed under it.</li>
 * </ol>
 *
 * <h2>Provenance</h2>
 *
 * <p>The seeded data derives from the CardDemo COBOL estate at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced here.
 *
 * @since 1.0.0
 */
@DisplayName("Seed migrations, applied to a real PostgreSQL server")
final class SeedMigrationIT extends AbstractPostgresIT {

    /** Schema migrated from both delivered locations, which is what local and test resolve. */
    private static final String HEAD_SCHEMA = "seed_head";

    /** Schema migrated from the schema location alone, which is what production resolves. */
    private static final String PRODUCTION_SHAPED_SCHEMA = "seed_production_shaped";

    /** The eleven tables the schema migration creates. */
    private static final List<String> ALL_TABLES = List.of(
            "account", "card", "customer", "card_cross_reference", "transaction",
            "daily_transaction", "transaction_category_balance", "disclosure_group",
            "transaction_type", "transaction_category", "user_security");

    /**
     * The nine seeded volumes, each equal to the record count of the fixture it transcribes.
     *
     * <p>Measured from the fixture files: {@code custdata.txt} 50 records of 500 bytes,
     * {@code acctdata.txt} 50 of 300, {@code carddata.txt} 50 of 150, {@code cardxref.txt} 50 of 36,
     * {@code trantype.txt} 7 of 60, {@code trancatg.txt} 18 of 60, {@code discgrp.txt} 51 of 50,
     * {@code tcatbal.txt} 50 of 50 and {@code dailytran.txt} 300 of 350.</p>
     */
    private static final Map<String, Long> EXPECTED_SEEDED_VOLUMES = Map.of(
            "customer", 50L,
            "account", 50L,
            "card", 50L,
            "card_cross_reference", 50L,
            "transaction_type", 7L,
            "transaction_category", 18L,
            "disclosure_group", 51L,
            "transaction_category_balance", 50L,
            "daily_transaction", 300L);

    /**
     * Total rows the reference seed writes: the nine volumes above, summed.
     *
     * <p>50 customers + 50 accounts + 50 cards + 50 cross-references + 7 transaction types +
     * 18 transaction categories + 51 disclosure-group rows + 50 category balances + 300 daily
     * transactions.</p>
     */
    private static final long EXPECTED_SEEDED_TOTAL = 626L;

    /** The three disclosure-group keys, at their full ten characters including trailing blanks. */
    private static final String GROUP_A = "A000000000";

    /** The fallback group, seven characters padded to ten by the legacy key move. */
    private static final String GROUP_DEFAULT = "DEFAULT   ";

    /** The zero-rate group, likewise padded, and the reason the zero-rate branch is reachable. */
    private static final String GROUP_ZERO_APR = "ZEROAPR   ";

    /** Rows per disclosure group: one per type-and-category combination the reference data carries. */
    private static final long ROWS_PER_DISCLOSURE_GROUP = 17L;

    /** The seventeen type-and-category combinations every disclosure group repeats. */
    private static final List<String> DISCLOSURE_TYPE_AND_CATEGORY = List.of(
            "01/0001", "01/0002", "01/0003", "01/0004",
            "02/0001", "02/0002", "02/0003",
            "03/0001", "03/0002", "03/0003",
            "04/0001", "04/0002", "04/0003",
            "05/0001",
            "06/0001", "06/0002",
            "07/0001");

    /** A rate of exactly zero, at the scale the rate column declares. */
    private static final BigDecimal RATE_ZERO = new BigDecimal("0.00");

    /** The lower of the two non-zero rates the reference data carries. */
    private static final BigDecimal RATE_FIFTEEN = new BigDecimal("15.00");

    /** The higher of the two non-zero rates the reference data carries. */
    private static final BigDecimal RATE_TWENTY_FIVE = new BigDecimal("25.00");

    /**
     * Purchases: the transaction source of two hundred and fifty of the three hundred records, at the
     * full {@code PIC X(10)} width the coded field occupies. The trailing blanks are part of the value,
     * not padding the seed is expected to shed - the enumeration that models the column declares its
     * values at this width.
     */
    private static final String SOURCE_POS_TERMINAL = "POS TERM  ";

    /** Returns: the transaction source of the remaining fifty, and the negative amounts, padded alike. */
    private static final String SOURCE_OPERATOR = "OPERATOR  ";

    /** The one origination instant every daily-transaction record carries. */
    private static final String ORIGINATION_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /** A processing timestamp that has not been written: twenty-six spaces, stored as such. */
    private static final String BLANK_PROCESSING_TIMESTAMP = " ".repeat(26);

    /** The blank ten-character account group identifier every fixture account carries. */
    private static final String BLANK_ACCOUNT_GROUP_ID = " ".repeat(10);

    /** The value the fixture puts in the account postal-code field, in all fifty records. */
    private static final String ACCOUNT_POSTAL_CODE = "A000000000";

    /** The credential every seeded identity shares in the legacy provisioning stream. */
    private static final String LEGACY_CREDENTIAL = "PASSWORD";

    /** Characters in a BCrypt digest, which is why the credential column is sixty wide. */
    private static final int DIGEST_LENGTH = 60;

    /** The lowest work factor acceptable for a stored credential. */
    private static final int MINIMUM_DIGEST_COST = 12;

    /** How a BCrypt digest opens: a revision marker and a two-digit work factor. */
    private static final Pattern DIGEST_FORMAT = Pattern.compile("^\\$2[aby]\\$(\\d{2})\\$.{53}$");

    /** Sign-on identities the legacy provisioning stream writes, as identity, names and role code. */
    private static final List<String[]> SEEDED_IDENTITIES = List.of(
            new String[] {"ADMIN001", "MARGARET", "GOLD", "A"},
            new String[] {"ADMIN002", "RUSSELL", "RUSSELL", "A"},
            new String[] {"ADMIN003", "RAYMOND", "WHITMORE", "A"},
            new String[] {"ADMIN004", "EMMANUEL", "CASGRAIN", "A"},
            new String[] {"ADMIN005", "GRANVILLE", "LACHAPELLE", "A"},
            new String[] {"USER0001", "LAWRENCE", "THOMAS", "U"},
            new String[] {"USER0002", "AJITH", "KUMAR", "U"},
            new String[] {"USER0003", "LAURITZ", "ALME", "U"},
            new String[] {"USER0004", "AVERARDO", "MAZZI", "U"},
            new String[] {"USER0005", "LEE", "TING", "U"});

    /**
     * The location list PRODUCTION resolves: the packaged schema location and nothing else, read from
     * the module's own production control so the two cannot drift apart.
     *
     * <p>THIS LIST IS THE EXCLUSION. The two seed scripts ship from the sibling
     * {@link FlywayConfig#SEED_LOCATION}, which this list does not name, so a run configured with it
     * does not resolve them - they are absent from its migration state rather than reported as
     * withheld. The tests below prove it by running this list and {@link #SEEDING_LOCATIONS} against
     * the same server with every other setting identical.</p>
     */
    private static final List<String> PRODUCTION_LOCATIONS =
            List.of(FlywayConfig.SCHEMA_LOCATION);

    /**
     * The location list the two SEEDING profiles resolve: the schema location plus the seed location.
     *
     * <p>The shared parent of the two is deliberately never used, even though it would resolve the same
     * four scripts: Flyway records a script under a name relative to its location, so the parent would
     * write {@code schema/V1__create_schema.sql} into the history where every shipped profile writes
     * {@code V1__create_schema.sql}.</p>
     */
    private static final List<String> SEEDING_LOCATIONS =
            List.of(FlywayConfig.SCHEMA_LOCATION, FlywayConfig.SEED_LOCATION);

    /**
     * The version the production ceiling pins, which is the highest version the schema location
     * delivers. Held here to measure what the ceiling contributes on its own and what it does not.
     */
    private static final String SCHEMA_CEILING = "2";

    /**
     * Migration state of the production-shaped schema, captured once so every assertion reads one
     * answer.
     */
    private static List<MigrationInfo> productionShapedMigrationState;

    /**
     * Brings both schemas to their targets before any test runs.
     *
     * <p>Both migrations are idempotent, so a shared server already carrying either schema from an
     * earlier class in the same fork is left as it is rather than rebuilt.</p>
     */
    @BeforeAll
    static void migrateBothSchemas() {
        flywayFor(HEAD_SCHEMA, SEEDING_LOCATIONS, null).migrate();

        Flyway productionShaped = flywayFor(PRODUCTION_SHAPED_SCHEMA, PRODUCTION_LOCATIONS, null);
        productionShaped.migrate();
        productionShapedMigrationState = List.of(productionShaped.info().all());
    }

    @Nested
    @DisplayName("the reference seed writes the volumes its fixtures carry")
    final class TheReferenceSeedWritesItsFixtureVolumes {

        @Test
        @DisplayName("every seeded table holds exactly as many rows as its fixture holds records")
        void everySeededTableMatchesItsFixtureRecordCount() throws SQLException {
            for (final Map.Entry<String, Long> expected : EXPECTED_SEEDED_VOLUMES.entrySet()) {
                assertThat(count(HEAD_SCHEMA, expected.getKey()))
                        .as("%s must hold one row per fixture record. A short seed leaves a suite "
                                + "passing against less data than it claims to cover",
                                expected.getKey())
                        .isEqualTo(expected.getValue());
            }
        }

        @Test
        @DisplayName("the nine volumes sum to the total the seed reports for itself")
        void theNineVolumesSumToTheReportedTotal() throws SQLException {
            long total = 0L;
            for (final String table : EXPECTED_SEEDED_VOLUMES.keySet()) {
                total += count(HEAD_SCHEMA, table);
            }

            assertThat(total)
                    .as("the seed logs this figure on completion; if the sum here disagreed, one of "
                            + "the two would be describing a different database")
                    .isEqualTo(EXPECTED_SEEDED_TOTAL);
        }

        @Test
        @DisplayName("the posted-transaction table is left empty, because no fixture describes it and "
                + "the posting run is what fills it")
        void thePostedTransactionTableIsLeftEmpty() throws SQLException {
            assertThat(count(HEAD_SCHEMA, "transaction"))
                    .as("seeding a posted transaction would make a posting run's own output "
                            + "indistinguishable from the fixture it started from")
                    .isZero();
        }

        @Test
        @DisplayName("every national identifier is seeded as a sealed envelope, never as the cleartext "
                + "the fixture record holds")
        void everyNationalIdentifierIsSeededSealed() throws SQLException {
            assertThat(count(HEAD_SCHEMA, "customer"))
                    .as("the assertions below are only meaningful if the rows exist")
                    .isEqualTo(50L);

            // THIS ASSERTED THE OPPOSITE, on the ground that sealing would require committing key
            // material. It does not: the seed-bearing profiles already commit the one non-production
            // fixture key as a bare literal, this file adds none, and the cleartext of these nine
            // bytes is already committed twice over - at app/data/ASCII/custdata.txt, the read-only
            // parity baseline, and at src/test/resources/fixtures/input/custdata.txt. What the
            // omission actually cost was the only proof that mattered: no delivered row exercised a
            // stored national identifier, so the reveal path was verified against absence.
            assertThat(scalar("SELECT count(*) FROM " + HEAD_SCHEMA
                    + ".customer WHERE cust_ssn IS NULL"))
                    .as("no delivered row may leave the column empty")
                    .isZero();
            assertThat(scalar("SELECT count(*) FROM " + HEAD_SCHEMA
                    + ".customer WHERE cust_ssn LIKE 'ENC1:%' AND length(cust_ssn) = 81"))
                    .as("every row must hold an envelope of the width a column-bound nine-digit "
                            + "payload produces; a shorter one would mean an unbound or altered literal")
                    .isEqualTo(50L);
            assertThat(scalar("SELECT count(*) FROM " + HEAD_SCHEMA
                    + ".customer WHERE cust_ssn ~ '^[0-9]{1,9}$'"))
                    .as("and no row may hold the cleartext the envelope exists to replace")
                    .isZero();
        }

        @Test
        @DisplayName("every other customer column is seeded, so the unset identifier is a decision and "
                + "not an unmapped field")
        void everyOtherCustomerColumnIsSeeded() throws SQLException {
            assertThat(scalar("SELECT count(*) FROM " + HEAD_SCHEMA
                    + ".customer WHERE first_name IS NULL OR last_name IS NULL"
                    + " OR govt_issued_id IS NULL OR fico_credit_score IS NULL"))
                    .as("a wholesale gap in the customer seed would look identical to the deliberate "
                            + "single-column exemption, which is why both are asserted")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("the disclosure groups keep the composition that makes both rate branches reachable")
    final class TheDisclosureGroupsKeepTheirComposition {

        @Test
        @DisplayName("exactly three groups are seeded, each key ten characters including its trailing "
                + "blanks")
        void exactlyThreeGroupsAreSeededAtFullKeyWidth() throws SQLException {
            assertThat(strings("SELECT DISTINCT dis_acct_group_id FROM " + HEAD_SCHEMA
                    + ".disclosure_group ORDER BY 1"))
                    .as("trimming either padded key, or shortening the first, breaks the rate lookup "
                            + "outright - the group identifier is a key part and its blanks are part "
                            + "of it")
                    .containsExactly(GROUP_A, GROUP_DEFAULT, GROUP_ZERO_APR);
        }

        @ParameterizedTest(name = "group [{0}] carries {1} rows")
        @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
            "A000000000|17",
            "DEFAULT   |17",
            "ZEROAPR   |17"
        })
        @DisplayName("each group carries one row per type-and-category combination")
        void eachGroupCarriesOneRowPerCombination(final String group, final long expected)
                throws SQLException {
            assertThat(scalar("SELECT count(*) FROM " + HEAD_SCHEMA
                    + ".disclosure_group WHERE dis_acct_group_id = '" + group + "'"))
                    .isEqualTo(expected);
            assertThat(expected).isEqualTo(ROWS_PER_DISCLOSURE_GROUP);
        }

        @ParameterizedTest(name = "group [{0}] repeats the same seventeen combinations")
        @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false,
                value = {"A000000000", "DEFAULT   ", "ZEROAPR   "})
        @DisplayName("all three groups repeat the same type-and-category combinations, so a lookup that "
                + "hits one group resolves in either of the others")
        void allThreeGroupsRepeatTheSameCombinations(final String group) throws SQLException {
            assertThat(strings("SELECT dis_tran_type_cd || '/' || dis_tran_cat_cd FROM " + HEAD_SCHEMA
                    + ".disclosure_group WHERE dis_acct_group_id = '" + group + "' ORDER BY 1"))
                    .containsExactlyElementsOf(DISCLOSURE_TYPE_AND_CATEGORY);
        }

        @Test
        @DisplayName("the zero-rate group carries nothing but a zero rate, which is what makes the "
                + "skip branch of the interest calculation reachable from seed data alone")
        void theZeroRateGroupCarriesNothingButZero() throws SQLException {
            assertThat(rateCounts(GROUP_ZERO_APR))
                    .as("if any row in this group carried a rate, the branch that skips a zero rate "
                            + "could not be reached without a constructed fixture")
                    .containsExactly(Map.entry(RATE_ZERO, ROWS_PER_DISCLOSURE_GROUP));
        }

        @Test
        @DisplayName("the fallback group carries the full spread of rates, which is what makes the "
                + "fallback path compute a real amount rather than nothing")
        void theFallbackGroupCarriesTheFullSpreadOfRates() throws SQLException {
            assertThat(rateCounts(GROUP_DEFAULT))
                    .as("every seeded account carries a blank group identifier, so every interest "
                            + "calculation over this seed reaches the fallback group. A fallback "
                            + "carrying only zeros would compute nothing and prove nothing")
                    .containsExactly(
                            Map.entry(RATE_ZERO, 7L),
                            Map.entry(RATE_FIFTEEN, 7L),
                            Map.entry(RATE_TWENTY_FIVE, 3L));
        }

        @Test
        @DisplayName("the named group carries its own spread, distinct from the fallback's, so the two "
                + "are not interchangeable")
        void theNamedGroupCarriesItsOwnSpread() throws SQLException {
            assertThat(rateCounts(GROUP_A))
                    .containsExactly(
                            Map.entry(RATE_ZERO, 6L),
                            Map.entry(RATE_FIFTEEN, 8L),
                            Map.entry(RATE_TWENTY_FIVE, 3L));
        }

        @Test
        @DisplayName("every rate is held at exactly two decimal places, so no rate arrives through a "
                + "floating-point representation")
        void everyRateIsHeldAtTwoDecimalPlaces() throws SQLException {
            List<BigDecimal> rates = decimals("SELECT dis_int_rate FROM " + HEAD_SCHEMA
                    + ".disclosure_group ORDER BY dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd");

            assertThat(rates).hasSize((int) (3 * ROWS_PER_DISCLOSURE_GROUP));
            assertThat(rates).allSatisfy(rate -> assertThat(rate.scale())
                    .as("a rate at any other scale would have been through a conversion this "
                            + "migration is not permitted to perform")
                    .isEqualTo(2));
        }

        @Test
        @DisplayName("every seeded account carries a blank group identifier, at its full ten "
                + "characters, which is the anomaly that drives the fallback path")
        void everySeededAccountCarriesABlankGroupIdentifier() throws SQLException {
            assertThat(strings("SELECT DISTINCT acct_group_id FROM " + HEAD_SCHEMA
                    + ".account"))
                    .as("the fixture puts the postal code where the group identifier belongs and "
                            + "leaves the group identifier blank. Seeding it anywhere else, or "
                            + "trimming the blanks to an empty value, would change which disclosure "
                            + "lookup every interest run performs")
                    .containsExactly(BLANK_ACCOUNT_GROUP_ID);

            assertThat(strings("SELECT DISTINCT acct_addr_zip FROM " + HEAD_SCHEMA + ".account"))
                    .as("the transposition is carried faithfully rather than corrected")
                    .containsExactly(ACCOUNT_POSTAL_CODE);
        }
    }

    @Nested
    @DisplayName("the daily transactions keep the composition that makes both signed directions "
            + "reachable")
    final class TheDailyTransactionsKeepTheirComposition {

        @Test
        @DisplayName("two hundred and fifty purchases and fifty returns are seeded, and no other "
                + "source appears")
        void bothSourcesAreSeededAndNoOther() throws SQLException {
            assertThat(countsBy("SELECT dalytran_source, count(*) FROM " + HEAD_SCHEMA
                    + ".daily_transaction GROUP BY 1 ORDER BY 1"))
                    .as("a coded field is stored at its layout width, so these are the fixture's values "
                            + "with the padding the fixture itself holds - only display text is trimmed")
                    .containsExactly(
                            Map.entry(SOURCE_OPERATOR, 50L),
                            Map.entry(SOURCE_POS_TERMINAL, 250L));
        }

        @Test
        @DisplayName("two hundred and fifty amounts are positive and fifty are negative, so the balance "
                + "computation is exercised in both directions")
        void bothSignedDirectionsAreSeeded() throws SQLException {
            assertThat(scalar("SELECT count(*) FROM " + HEAD_SCHEMA
                    + ".daily_transaction WHERE dalytran_amt > 0"))
                    .isEqualTo(250L);
            assertThat(scalar("SELECT count(*) FROM " + HEAD_SCHEMA
                    + ".daily_transaction WHERE dalytran_amt < 0"))
                    .as("the fifty negatives are the returns; without them the credit side of the "
                            + "balance computation would never run over seeded data")
                    .isEqualTo(50L);
            assertThat(scalar("SELECT count(*) FROM " + HEAD_SCHEMA
                    + ".daily_transaction WHERE dalytran_amt = 0"))
                    .as("a negative-zero image decodes to zero, and none of the fixture's amounts is "
                            + "one; an amount that arrived here as zero would mean a decode dropped "
                            + "its digits")
                    .isZero();
        }

        @Test
        @DisplayName("every amount is held at exactly two decimal places")
        void everyAmountIsHeldAtTwoDecimalPlaces() throws SQLException {
            List<BigDecimal> amounts = decimals("SELECT dalytran_amt FROM " + HEAD_SCHEMA
                    + ".daily_transaction ORDER BY dalytran_id");

            assertThat(amounts).hasSize(300);
            assertThat(amounts).allSatisfy(amount -> assertThat(amount.scale()).isEqualTo(2));
        }

        @Test
        @DisplayName("every processing timestamp is twenty-six spaces, because the posting job writes "
                + "it and this seed consults no clock")
        void everyProcessingTimestampIsTwentySixSpaces() throws SQLException {
            assertThat(strings("SELECT DISTINCT dalytran_proc_ts FROM " + HEAD_SCHEMA
                    + ".daily_transaction"))
                    .as("trimming this field to an empty value, or inventing an instant for it, would "
                            + "make an unposted transaction indistinguishable from a posted one")
                    .containsExactly(BLANK_PROCESSING_TIMESTAMP);
        }

        @Test
        @DisplayName("every origination timestamp is the same instant, which is why a date-window "
                + "report needs a constructed fixture rather than this seed")
        void everyOriginationTimestampIsTheSameInstant() throws SQLException {
            assertThat(strings("SELECT DISTINCT dalytran_orig_ts FROM " + HEAD_SCHEMA
                    + ".daily_transaction"))
                    .containsExactly(ORIGINATION_TIMESTAMP);
        }

        @Test
        @DisplayName("only two type-and-category combinations are seeded, one per source")
        void onlyTwoTypeAndCategoryCombinationsAreSeeded() throws SQLException {
            assertThat(countsBy("SELECT dalytran_type_cd || '/' || dalytran_cat_cd, count(*) FROM "
                    + HEAD_SCHEMA + ".daily_transaction GROUP BY 1 ORDER BY 1"))
                    .containsExactly(Map.entry("01/0001", 250L), Map.entry("03/0001", 50L));
        }

        @Test
        @DisplayName("the first fixture record is seeded field for field, so a correct aggregate cannot "
                + "hide a wrong row")
        void theFirstFixtureRecordIsSeededFieldForField() throws SQLException {
            List<String> row = strings("SELECT dalytran_type_cd || '|' || dalytran_cat_cd || '|'"
                    + " || dalytran_source || '|' || dalytran_amt || '|' || dalytran_card_num"
                    + " || '|' || dalytran_merchant_id || '|' || dalytran_desc"
                    + " || '|' || dalytran_orig_ts FROM " + HEAD_SCHEMA
                    + ".daily_transaction WHERE dalytran_id = '0000000000683580'");

            assertThat(row)
                    .as("the amount is the fixture's zoned image 0000005047G decoded with its "
                            + "overpunched sign and its two implied decimals")
                    .containsExactly("01|0001|" + SOURCE_POS_TERMINAL
                            + "|504.77|4859452612877065|800000000|"
                            + "Purchase at Abshire-Lowe|" + ORIGINATION_TIMESTAMP);
        }

        @Test
        @DisplayName("a second named fixture record is seeded field for field")
        void aSecondNamedFixtureRecordIsSeededFieldForField() throws SQLException {
            List<String> row = strings("SELECT dalytran_type_cd || '|' || dalytran_cat_cd || '|'"
                    + " || dalytran_source || '|' || dalytran_amt || '|' || dalytran_card_num"
                    + " || '|' || dalytran_desc FROM " + HEAD_SCHEMA
                    + ".daily_transaction WHERE dalytran_id = '0000000996722787'");

            assertThat(row)
                    .as("the amount is the fixture's zoned image 0000006032B decoded the same way")
                    .containsExactly("01|0001|" + SOURCE_POS_TERMINAL + "|603.22|3260763612337560|"
                            + "Purchase at Kilback LLC");
        }
    }

    @Nested
    @DisplayName("the sign-on seed writes ten identities and no cleartext credential")
    final class TheSignOnSeedWritesTenIdentities {

        @Test
        @DisplayName("ten identities are seeded, five administrative and five standard")
        void tenIdentitiesAreSeededFiveAndFive() throws SQLException {
            assertThat(count(HEAD_SCHEMA, "user_security")).isEqualTo(10L);
            assertThat(countsBy("SELECT sec_usr_type, count(*) FROM " + HEAD_SCHEMA
                    + ".user_security GROUP BY 1 ORDER BY 1"))
                    .as("the role code is the sole authority for the administrative split, so an "
                            + "uneven seed would leave one of the two menus untestable")
                    .containsExactly(Map.entry("A", 5L), Map.entry("U", 5L));
        }

        @Test
        @DisplayName("each seeded identity carries the name and role the legacy provisioning stream "
                + "gives it")
        void eachIdentityCarriesItsLegacyNameAndRole() throws SQLException {
            List<String> expected = new ArrayList<>();
            for (final String[] identity : SEEDED_IDENTITIES) {
                expected.add(String.join("|", identity));
            }

            assertThat(strings("SELECT sec_usr_id || '|' || sec_usr_fname || '|' || sec_usr_lname"
                    + " || '|' || sec_usr_type FROM " + HEAD_SCHEMA + ".user_security ORDER BY 1"))
                    .as("names are stored right-trimmed; the legacy stream pads them to twenty "
                            + "characters and the fixed-width writers pad again on output")
                    .containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("every credential is a sixty-character digest, and the ten are all distinct")
        void everyCredentialIsADistinctSixtyCharacterDigest() throws SQLException {
            List<String> digests = strings("SELECT sec_usr_pwd FROM " + HEAD_SCHEMA
                    + ".user_security ORDER BY sec_usr_id");

            assertThat(digests).hasSize(10);
            assertThat(digests)
                    .as("ten identical digests would mean one salt, which would let one cracked "
                            + "credential open every seeded account")
                    .doesNotHaveDuplicates();
            assertThat(digests).allSatisfy(digest -> assertThat(digest).hasSize(DIGEST_LENGTH));
        }

        @Test
        @DisplayName("every digest is well formed and carries a work factor no lower than the floor")
        void everyDigestIsWellFormedAtAnAcceptableCost() throws SQLException {
            List<String> digests = strings("SELECT sec_usr_pwd FROM " + HEAD_SCHEMA
                    + ".user_security ORDER BY sec_usr_id");

            for (final String digest : digests) {
                Matcher format = DIGEST_FORMAT.matcher(digest);

                assertThat(format.matches())
                        .as("a stored value that is not a digest at all would satisfy every count "
                                + "assertion above")
                        .isTrue();
                assertThat(Integer.parseInt(format.group(1)))
                        .as("a work factor below the floor makes an offline attack cheap enough to "
                                + "matter even against sample credentials")
                        .isGreaterThanOrEqualTo(MINIMUM_DIGEST_COST);
            }
        }

        @Test
        @DisplayName("every digest verifies against the legacy credential, so the seed reproduces the "
                + "legacy sign-on without storing it")
        void everyDigestVerifiesAgainstTheLegacyCredential() throws SQLException {
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
            List<String> digests = strings("SELECT sec_usr_pwd FROM " + HEAD_SCHEMA
                    + ".user_security ORDER BY sec_usr_id");

            assertThat(digests).allSatisfy(digest -> assertThat(encoder.matches(LEGACY_CREDENTIAL,
                    digest))
                    .as("parity is that these ten identities sign on with the credential the legacy "
                            + "stream gave them; the substitution is how it is stored, not what it is")
                    .isTrue());
        }

        @Test
        @DisplayName("no stored credential is the cleartext value, and no row holds it anywhere")
        void noStoredCredentialIsCleartext() throws SQLException {
            assertThat(scalar("SELECT count(*) FROM " + HEAD_SCHEMA
                    + ".user_security WHERE sec_usr_pwd = '" + LEGACY_CREDENTIAL + "'"))
                    .isZero();
            assertThat(scalar("SELECT count(*) FROM " + HEAD_SCHEMA
                    + ".user_security WHERE length(sec_usr_pwd) = 8"))
                    .as("the legacy record holds eight cleartext characters; a value of that width "
                            + "here would mean the parity exception was not applied")
                    .isZero();
            assertThat(scalar("SELECT count(*) FROM " + HEAD_SCHEMA
                    + ".user_security WHERE user_security::text LIKE '%" + LEGACY_CREDENTIAL + "%'"))
                    .as("the whole row is searched, not one column, because a credential copied into "
                            + "a name field would be just as disclosed")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("the production scope withholds both seeds, and the location list is what does it")
    final class TheProductionScopeWithholdsBothSeeds {

        @Test
        @DisplayName("the schema is fully created under the production scope, so the emptiness below "
                + "is about the seeds and not about a failed migration")
        void theSchemaIsFullyCreatedUnderTheProductionScope() throws SQLException {
            for (final String table : ALL_TABLES) {
                assertThat(scalar("SELECT count(*) FROM information_schema.tables WHERE"
                        + " table_schema = '" + PRODUCTION_SHAPED_SCHEMA + "' AND table_name = '"
                        + table + "'"))
                        .as("%s must exist under the production scope: production applies the whole "
                                + "schema and withholds only the rows", table)
                        .isEqualTo(1L);
            }
        }

        @Test
        @DisplayName("every table is empty under the production scope, including the sign-on table, so "
                + "a production migration receives no sample row and no seeded login")
        void everyTableIsEmptyUnderTheProductionScope() throws SQLException {
            for (final String table : ALL_TABLES) {
                assertThat(count(PRODUCTION_SHAPED_SCHEMA, table))
                        .as("%s must hold nothing. For the sign-on table in particular, a row here "
                                + "would be a known identity in a production deployment", table)
                        .isZero();
            }
        }

        @Test
        @DisplayName("under the production scope the two seeds are not resolved AT ALL - they appear in "
                + "no migration state, not even as withheld")
        void theTwoSeedsAreNotResolvedAtAll() {
            Map<String, MigrationState> states = new LinkedHashMap<>();
            for (final MigrationInfo info : productionShapedMigrationState) {
                if (info.getVersion() != null) {
                    states.put(info.getVersion().getVersion(), info.getState());
                }
            }

            assertThat(states.keySet())
                    .as("a production-shaped scope resolves the schema location alone, so only the two "
                            + "schema versions exist for it. THIS IS STRONGER THAN THE CEILING ON ITS "
                            + "OWN: under a ceiling alone all four scripts are resolved and the seeds "
                            + "are reported ABOVE_TARGET, which means the tool holds a pending "
                            + "instruction to apply them that one property could release. A script in a "
                            + "location the deployment does not resolve is not pending anything, which "
                            + "is why the location list is the primary control and the ceiling is the "
                            + "second one")
                    .containsExactly("1", "2");
            assertThat(states.get("1")).isEqualTo(MigrationState.SUCCESS);
            assertThat(states.get("2")).isEqualTo(MigrationState.SUCCESS);
            assertThat(states)
                    .as("V3 must appear in no state whatsoever; a state of SUCCESS would mean fifty "
                            + "synthetic customer rows of regulated identity data in a production "
                            + "database, and a state of ABOVE_TARGET would mean the same rows one "
                            + "property override away")
                    .doesNotContainKey("3")
                    .doesNotContainKey("4");
        }

        @Test
        @DisplayName("validation succeeds under the production scope, so the arrangement is not one a "
                + "deployment has to suppress an error to live with")
        void validationSucceedsUnderTheProductionScope() {
            assertThat(flywayFor(PRODUCTION_SHAPED_SCHEMA, PRODUCTION_LOCATIONS, null)
                    .validateWithResult().validationSuccessful)
                    .as("if the production scope failed validation, production would have to disable "
                            + "validation to start - and would then also stop noticing a genuinely "
                            + "altered migration")
                    .isTrue();
        }

        @Test
        @DisplayName("adding the seed location to the SAME target-free configuration applies both "
                + "seeds, which is what proves the location list is the control")
        void addingTheSeedLocationAloneAppliesBothSeeds() throws SQLException {
            String schema = "seed_location_added";
            flywayFor(schema, PRODUCTION_LOCATIONS, null).migrate();

            assertThat(count(schema, "user_security"))
                    .as("production-shaped first, so the emptiness is established before the location "
                            + "is added and cannot be mistaken for a migration that never ran")
                    .isZero();
            assertThat(count(schema, "account"))
                    .as("while the schema itself is fully applied, which is what makes the emptiness "
                            + "attributable to the seed location rather than to a failure")
                    .isZero();

            flywayFor(schema, SEEDING_LOCATIONS, null).migrate();

            assertThat(count(schema, "user_security"))
                    .as("adding one location produces ten sign-on identities where the production-"
                            + "shaped run produced none, against the same schema and with the target "
                            + "untouched in both runs. That is the whole claim of the delivered "
                            + "exclusion stated as an experiment rather than as prose: the variable "
                            + "that changed is the list, so the list is the control")
                    .isEqualTo(10L);
            assertThat(count(schema, "customer"))
                    .as("and the reference seed lands with it, which is why the list is enforced in "
                            + "code as well as declared in a document an override can supersede")
                    .isEqualTo(50L);
            assertThat(count(schema, "daily_transaction")).isEqualTo(300L);
        }

        @Test
        @DisplayName("the seed scripts are genuinely resolvable, so the emptiness above is an exclusion "
                + "rather than a pair of scripts that do nothing")
        void theSeedScriptsAreGenuinelyResolvableFromTheirOwnLocation() throws SQLException {
            String schema = "seed_location_only";
            Flyway seedsOnly = flywayFor(schema, SEEDING_LOCATIONS, null);
            seedsOnly.migrate();

            Map<String, MigrationState> states = new LinkedHashMap<>();
            for (final MigrationInfo info : seedsOnly.info().all()) {
                if (info.getVersion() != null) {
                    states.put(info.getVersion().getVersion(), info.getState());
                }
            }

            assertThat(states.keySet())
                    .as("the seeding list resolves all four versions, so the two the production scope "
                            + "cannot see are demonstrably there to be seen")
                    .containsExactly("1", "2", "3", "4");
            assertThat(states.get("3")).isEqualTo(MigrationState.SUCCESS);
            assertThat(states.get("4")).isEqualTo(MigrationState.SUCCESS);
            assertThat(count(schema, "user_security")).isEqualTo(10L);
        }

        @Test
        @DisplayName("a FUTURE schema migration is skipped by the pin and applies without it, so the "
                + "pin's cost is measured here and is answered by asserting it against the delivered "
                + "scripts rather than by removing it")
        void aFutureSchemaMigrationIsSkippedByThePinAndAppliesWithoutIt(@TempDir final Path futureSchema)
                throws IOException, SQLException {
            Files.writeString(futureSchema.resolve("V5__add_future_schema_object.sql"),
                    "CREATE TABLE future_schema_probe (probe_id VARCHAR(4) PRIMARY KEY);\n",
                    StandardCharsets.UTF_8);
            List<String> productionPlusFuture = new ArrayList<>(PRODUCTION_LOCATIONS);
            productionPlusFuture.add("filesystem:" + futureSchema.toAbsolutePath());

            String withCeiling = "seed_future_under_ceiling";
            flywayFor(withCeiling, productionPlusFuture, SCHEMA_CEILING).migrate();

            assertThat(scalar("SELECT count(*) FROM information_schema.tables WHERE table_schema = '"
                    + withCeiling + "' AND table_name = 'future_schema_probe'"))
                    .as("THIS IS THE COST THE CEILING CARRIES, MEASURED RATHER THAN ARGUED. Under a "
                            + "ceiling of %s the V5 schema script is resolved, skipped and reported as a "
                            + "successful migration: nothing here fails, so a deployment would come up "
                            + "on an incomplete schema and say so nowhere. That is why the pin is not "
                            + "merely written down - FlywayConfigTest asserts it EQUALS the highest "
                            + "version the schema location delivers, so shipping a fifth schema script "
                            + "fails the build until the pin and the two documents that declare it are "
                            + "raised together. A loud build failure replaces the silent runtime one "
                            + "this assertion demonstrates", SCHEMA_CEILING)
                    .isZero();

            String withoutCeiling = "seed_future_under_open_target";
            Flyway open = flywayFor(withoutCeiling, productionPlusFuture, null);
            open.migrate();

            assertThat(scalar("SELECT count(*) FROM information_schema.tables WHERE table_schema = '"
                    + withoutCeiling + "' AND table_name = 'future_schema_probe'"))
                    .as("with no ceiling and the same location list, the future schema script APPLIES. "
                            + "That is what makes the location list the PRIMARY control: the seed "
                            + "exclusion does not depend on the ceiling at all, so the ceiling can be a "
                            + "second, narrower check on the schema half rather than the thing the "
                            + "seed exclusion rests on")
                    .isEqualTo(1L);

            Map<String, MigrationState> states = new LinkedHashMap<>();
            for (final MigrationInfo info : open.info().all()) {
                if (info.getVersion() != null) {
                    states.put(info.getVersion().getVersion(), info.getState());
                }
            }
            assertThat(states.keySet())
                    .as("and it applies WITHOUT exposing a seed: the resolved set is the two schema "
                            + "versions plus the future one, and neither seed version appears. The seed "
                            + "exclusion is the location list acting alone, with no ceiling in force at "
                            + "all - which is the measurement that makes the ceiling defence in depth "
                            + "rather than the seed boundary")
                    .containsExactly("1", "2", "5");
            assertThat(states.get("5")).isEqualTo(MigrationState.SUCCESS);
            for (final String table : ALL_TABLES) {
                assertThat(count(withoutCeiling, table))
                        .as("%s must still hold nothing: applying a future schema version must not "
                                + "bring a seeded row with it", table)
                        .isZero();
            }
        }
    }

    // Helpers.

    /**
     * Builds a migration configured to land in one schema, with an explicit location list.
     *
     * <p>The location list is a parameter rather than a constant so that every call site states the
     * locations it resolves from instead of leaving it to be inferred. That is the point: the
     * production-shaped run and the seeding run pass DIFFERENT lists and the same {@code target} - null,
     * meaning apply everything resolved - so the parameter that varies is the one carrying the control.
     * The {@code target} parameter survives for one purpose only, which is to demonstrate in
     * {@link TheProductionScopeWithholdsBothSeeds#aFutureSchemaMigrationIsSkippedByThePinAndAppliesWithoutIt}
     * what the version ceiling costs on its own, and what the location list delivers without it.
     *
     * <p>{@code cleanDisabled} is left at its safe default: nothing here drops a schema, and a
     * configuration that could would be one edit away from dropping the shared one.</p>
     *
     * @param schema    the schema to create and migrate into
     * @param locations the location descriptors to resolve migrations from
     * @param target    the highest version to apply, or {@code null} for the head
     * @return a loaded migration, not yet run
     */
    private static Flyway flywayFor(final String schema, final List<String> locations,
            final String target) {
        var configuration = Flyway.configure()
                .dataSource(jdbcUrl(), databaseUser(), databasePassword())
                .locations(locations.toArray(String[]::new))
                .schemas(schema)
                .defaultSchema(schema);
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }

    /**
     * Counts the rows of one table in one schema.
     *
     * @param schema the schema holding the table
     * @param table  the table to count
     * @return the row count
     * @throws SQLException if the query cannot be issued
     */
    private static long count(final String schema, final String table) throws SQLException {
        return scalar("SELECT count(*) FROM " + schema + "." + table);
    }

    /**
     * Reads a single numeric result.
     *
     * @param sql a query returning one row of one numeric column
     * @return the value
     * @throws SQLException if the query cannot be issued
     */
    private static long scalar(final String sql) throws SQLException {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql)) {
            results.next();
            return results.getLong(1);
        }
    }

    /**
     * Reads a single column of text results, in the order the query returns them.
     *
     * @param sql a query returning one text column
     * @return the values, in query order
     * @throws SQLException if the query cannot be issued
     */
    private static List<String> strings(final String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql)) {
            while (results.next()) {
                values.add(results.getString(1));
            }
        }
        return values;
    }

    /**
     * Reads a single column of exact-decimal results, in the order the query returns them.
     *
     * @param sql a query returning one numeric column
     * @return the values, in query order
     * @throws SQLException if the query cannot be issued
     */
    private static List<BigDecimal> decimals(final String sql) throws SQLException {
        List<BigDecimal> values = new ArrayList<>();
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql)) {
            while (results.next()) {
                values.add(results.getBigDecimal(1));
            }
        }
        return values;
    }

    /**
     * Reads a grouped count as an ordered map of text key to count.
     *
     * @param sql a query returning one text column and one count column
     * @return the counts, in query order
     * @throws SQLException if the query cannot be issued
     */
    private static Map<String, Long> countsBy(final String sql) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql)) {
            while (results.next()) {
                counts.put(results.getString(1), results.getLong(2));
            }
        }
        return counts;
    }

    /**
     * Reads the rate distribution of one disclosure group, ascending by rate.
     *
     * @param group the ten-character group key, trailing blanks included
     * @return rate to row count, ascending by rate
     * @throws SQLException if the query cannot be issued
     */
    private static Map<BigDecimal, Long> rateCounts(final String group) throws SQLException {
        Map<BigDecimal, Long> counts = new LinkedHashMap<>();
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(
                     "SELECT dis_int_rate, count(*) FROM " + HEAD_SCHEMA + ".disclosure_group"
                             + " WHERE dis_acct_group_id = '" + group + "' GROUP BY 1 ORDER BY 1")) {
            while (results.next()) {
                counts.put(results.getBigDecimal(1), results.getLong(2));
            }
        }
        return counts;
    }
}
