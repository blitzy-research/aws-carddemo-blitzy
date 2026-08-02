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
package com.carddemo.domain;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.carddemo.support.AbstractPostgresIT;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Settles, against a real seeded database, exactly which accrual rate-lookup branches the reference
 * seed reaches on its own.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The claim "the seed makes the interest branches reachable" had been asserted in prose across
 * more than a dozen files, and two thirds of it was wrong. Prose cannot settle the question, because
 * the answer depends on the interaction of three seeded tables at once. This test resolves the rate
 * the way {@code app/cbl/CBACT04C.cbl} resolves it in {@code 1200-GET-INTEREST-RATE} - probe the
 * disclosure table on the account's own group identifier, and on a not-found status re-probe with the
 * literal {@code DEFAULT} moved into the ten-character key field - and then reports what happens.</p>
 *
 * <h2>What it establishes</h2>
 *
 * <ul>
 *   <li><strong>The default-group fallback is reachable from the seed alone.</strong> All fifty seeded
 *       accounts carry ten spaces in their group identifier, no seeded disclosure key is ten spaces,
 *       so every direct probe misses and every account resolves through the fallback.</li>
 *   <li><strong>The compute branch, not the skip branch, is what a seed-only run takes.</strong> The
 *       fallback lands on a rate of 15.00 at the one type and category all fifty seeded balances
 *       carry, and interest is computed only when the rate is non-zero.</li>
 *   <li><strong>The zero-rate skip and the direct group hit each need a constructed account.</strong>
 *       Both are demonstrated here by constructing one, so the requirement is shown rather than
 *       asserted.</li>
 *   <li><strong>The fallback group is not uniformly non-zero.</strong> Seven of its seventeen rows do
 *       carry a zero rate - just not at the type and category the seeded balances use. That is worth
 *       pinning, so that the corrected documentation is not later "corrected" into a simpler claim
 *       that happens to be false.</li>
 * </ul>
 *
 * <h2>Seed tolerance</h2>
 *
 * <p>Every row this test creates carries an account identifier in a reserved {@code IT} prefixed
 * range, and only that range is removed afterwards. Nothing seeded is inserted, updated or deleted, so
 * the class can run against a database another test has already migrated.</p>
 */
@DisplayName("disclosure rate lookup - which accrual branches the seed actually reaches")
class DisclosureRateLookupReachabilityIT extends AbstractPostgresIT {

    /** The reserved account-identifier prefix, so cleanup can never touch a seeded row. */
    private static final String RESERVED_PREFIX = "IT";

    /** A constructed account naming the zero-rate group, 11 characters as the key requires. */
    private static final String ZERO_RATE_ACCOUNT = "ITZEROAPR01";

    /** A constructed account naming the explicitly keyed group. */
    private static final String DIRECT_HIT_ACCOUNT = "ITDIRECT001";

    /** The fallback key: a seven-character literal moved into a ten-character field. */
    private static final String FALLBACK_GROUP = "DEFAULT   ";

    /** The zero-rate group key, likewise padded to ten characters. */
    private static final String ZERO_RATE_GROUP = "ZEROAPR   ";

    /** The explicitly keyed group, which needs no padding. */
    private static final String DIRECT_HIT_GROUP = "A000000000";

    /** The blank group identifier every seeded account carries. */
    private static final String BLANK_GROUP = "          ";

    /** The single transaction type every seeded category balance carries. */
    private static final String SEEDED_TYPE = "01";

    /** The single transaction category every seeded category balance carries. */
    private static final String SEEDED_CATEGORY = "0001";

    /** Seeded account rows, and therefore seeded category-balance rows. */
    private static final int SEEDED_ACCOUNTS = 50;

    /** Seeded disclosure rows: three complete groups of seventeen. */
    private static final int SEEDED_DISCLOSURE_ROWS = 51;

    @AfterEach
    void removeOnlyTheConstructedRows() throws SQLException {
        try (Connection connection = connect();
             PreparedStatement balances = connection.prepareStatement(
                     "DELETE FROM transaction_category_balance WHERE trancat_acct_id LIKE ?");
             PreparedStatement accounts = connection.prepareStatement(
                     "DELETE FROM account WHERE acct_id LIKE ?")) {
            balances.setString(1, RESERVED_PREFIX + "%");
            balances.executeUpdate();
            accounts.setString(1, RESERVED_PREFIX + "%");
            accounts.executeUpdate();
        }
    }

    @Test
    @DisplayName("the seed is the composition the documentation describes: 51 disclosure rows in three "
            + "17-row groups, 50 accounts all with a blank group identifier, 50 balances all on 01/0001")
    void theSeedHasTheDocumentedComposition() throws SQLException {
        try (Connection connection = connect()) {
            assertThat(countOf(connection, "SELECT count(*) FROM disclosure_group"))
                    .isEqualTo(SEEDED_DISCLOSURE_ROWS);
            assertThat(countOf(connection,
                    "SELECT count(DISTINCT dis_acct_group_id) FROM disclosure_group"))
                    .isEqualTo(3);
            assertThat(countOf(connection,
                    "SELECT count(*) FROM account WHERE acct_group_id = '" + BLANK_GROUP + "'"))
                    .as("every seeded account carries ten spaces, which is what forces the fallback")
                    .isEqualTo(SEEDED_ACCOUNTS);
            assertThat(countOf(connection,
                    "SELECT count(*) FROM transaction_category_balance"
                            + " WHERE trancat_type_cd = '" + SEEDED_TYPE + "'"
                            + " AND trancat_cd = '" + SEEDED_CATEGORY + "'"))
                    .as("all fifty seeded balances sit on one type and category, so one disclosure row "
                            + "per group decides the whole run")
                    .isEqualTo(SEEDED_ACCOUNTS);
        }
    }

    @Test
    @DisplayName("no seeded account can hit the disclosure table directly, because no seeded group key "
            + "is blank - so the fallback is not an edge case but the only seeded path")
    void noSeededAccountHitsTheTableDirectly() throws SQLException {
        try (Connection connection = connect()) {
            assertThat(countOf(connection, "SELECT count(*) FROM disclosure_group"
                    + " WHERE dis_acct_group_id = '" + BLANK_GROUP + "'"))
                    .isZero();

            final List<String> accountIds = seededAccountIds(connection);
            assertThat(accountIds).hasSize(SEEDED_ACCOUNTS);
            for (final String accountId : accountIds) {
                assertThat(directProbe(connection, accountId, SEEDED_TYPE, SEEDED_CATEGORY))
                        .as("direct probe for seeded account %s", accountId)
                        .isEmpty();
            }
        }
    }

    @Test
    @DisplayName("every seeded account resolves through the default fallback to 15.00, so a seed-only "
            + "accrual run takes the compute branch for all fifty and the skip branch for none")
    void everySeededAccountResolvesThroughTheFallbackToANonZeroRate() throws SQLException {
        try (Connection connection = connect()) {
            for (final String accountId : seededAccountIds(connection)) {
                final RateResolution resolved =
                        resolveRate(connection, accountId, SEEDED_TYPE, SEEDED_CATEGORY);

                assertThat(resolved.usedFallback())
                        .as("account %s must reach its rate through the fallback", accountId)
                        .isTrue();
                assertThat(resolved.rate())
                        .as("the fallback rate for account %s", accountId)
                        .isEqualByComparingTo(new BigDecimal("15.00"));
                assertThat(resolved.rate().signum())
                        .as("non-zero, so interest is computed and the skip branch is not taken")
                        .isNotZero();
            }
        }
    }

    @Test
    @DisplayName("the zero-rate skip needs a constructed account: pointing one at the zero-rate group "
            + "resolves directly to 0.00, against the 15.00 a seeded account gets")
    void theZeroRateSkipNeedsAConstructedAccount() throws SQLException {
        try (Connection connection = connect()) {
            insertAccount(connection, ZERO_RATE_ACCOUNT, ZERO_RATE_GROUP);
            insertBalance(connection, ZERO_RATE_ACCOUNT);

            final RateResolution resolved =
                    resolveRate(connection, ZERO_RATE_ACCOUNT, SEEDED_TYPE, SEEDED_CATEGORY);

            assertThat(resolved.usedFallback())
                    .as("the direct probe hits, so no fallback is needed")
                    .isFalse();
            assertThat(resolved.rate()).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(resolved.rate().signum())
                    .as("zero, so this is the run that takes the skip branch")
                    .isZero();
            assertThat(resolved.rate())
                    .as("and it differs from what the seed alone produces, which is the whole point")
                    .isNotEqualByComparingTo(new BigDecimal("15.00"));
        }
    }

    @Test
    @DisplayName("the direct group hit needs a constructed account too: pointing one at the explicitly "
            + "keyed group resolves without any fallback")
    void theDirectGroupHitNeedsAConstructedAccount() throws SQLException {
        try (Connection connection = connect()) {
            insertAccount(connection, DIRECT_HIT_ACCOUNT, DIRECT_HIT_GROUP);
            insertBalance(connection, DIRECT_HIT_ACCOUNT);

            final RateResolution resolved =
                    resolveRate(connection, DIRECT_HIT_ACCOUNT, SEEDED_TYPE, SEEDED_CATEGORY);

            assertThat(resolved.usedFallback()).isFalse();
            assertThat(resolved.rate()).isEqualByComparingTo(new BigDecimal("15.00"));
        }
    }

    @Test
    @DisplayName("the fallback group is NOT uniformly non-zero: seven of its seventeen rows carry a "
            + "zero rate, just not at the type and category the seeded balances use")
    void theFallbackGroupIsNotUniformlyNonZero() throws SQLException {
        try (Connection connection = connect()) {
            assertThat(countOf(connection, "SELECT count(*) FROM disclosure_group"
                    + " WHERE dis_acct_group_id = '" + FALLBACK_GROUP + "'"))
                    .isEqualTo(17);
            assertThat(countOf(connection, "SELECT count(*) FROM disclosure_group"
                    + " WHERE dis_acct_group_id = '" + FALLBACK_GROUP + "' AND dis_int_rate = 0.00"))
                    .as("so a claim that the fallback group is all non-zero would be false")
                    .isEqualTo(7);
            assertThat(countOf(connection, "SELECT count(*) FROM disclosure_group"
                    + " WHERE dis_acct_group_id = '" + ZERO_RATE_GROUP + "' AND dis_int_rate = 0.00"))
                    .as("the zero-rate group, by contrast, is zero throughout")
                    .isEqualTo(17);
            assertThat(countOf(connection,
                    "SELECT count(*) FROM disclosure_group WHERE dis_int_rate = 0.00"))
                    .as("thirty zero rates in all, spread across all three groups")
                    .isEqualTo(30);
        }
    }

    /** The outcome of a rate lookup: the rate found, and whether the fallback was needed to find it. */
    private record RateResolution(BigDecimal rate, boolean usedFallback) {
    }

    /**
     * Resolves a rate the way the accrual program does: probe on the account's own group identifier,
     * and on a not-found outcome re-probe with the reserved fallback key.
     *
     * @param connection the open connection
     * @param accountId  the account whose group identifier drives the first probe
     * @param typeCode   the transaction type component of the key
     * @param categoryCd the transaction category component of the key
     * @return the resolved rate and whether the fallback supplied it
     * @throws SQLException if the database rejects a statement
     */
    private static RateResolution resolveRate(final Connection connection, final String accountId,
                                              final String typeCode, final String categoryCd)
            throws SQLException {
        final Optional<BigDecimal> direct = directProbe(connection, accountId, typeCode, categoryCd);
        if (direct.isPresent()) {
            return new RateResolution(direct.get(), false);
        }
        final Optional<BigDecimal> fallback =
                probeGroup(connection, FALLBACK_GROUP, typeCode, categoryCd);
        assertThat(fallback)
                .as("the fallback key must resolve, or the accrual program would abend")
                .isPresent();
        return new RateResolution(fallback.orElseThrow(), true);
    }

    /** Probes the disclosure table on the group identifier the named account actually holds. */
    private static Optional<BigDecimal> directProbe(final Connection connection, final String accountId,
                                                    final String typeCode, final String categoryCd)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT d.dis_int_rate FROM disclosure_group d"
                        + " JOIN account a ON a.acct_group_id = d.dis_acct_group_id"
                        + " WHERE a.acct_id = ? AND d.dis_tran_type_cd = ? AND d.dis_tran_cat_cd = ?")) {
            statement.setString(1, accountId);
            statement.setString(2, typeCode);
            statement.setString(3, categoryCd);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getBigDecimal(1)) : Optional.empty();
            }
        }
    }

    /** Probes the disclosure table on an explicit group key. */
    private static Optional<BigDecimal> probeGroup(final Connection connection, final String groupId,
                                                   final String typeCode, final String categoryCd)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT dis_int_rate FROM disclosure_group"
                        + " WHERE dis_acct_group_id = ? AND dis_tran_type_cd = ?"
                        + " AND dis_tran_cat_cd = ?")) {
            statement.setString(1, groupId);
            statement.setString(2, typeCode);
            statement.setString(3, categoryCd);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getBigDecimal(1)) : Optional.empty();
            }
        }
    }

    /** Returns the identifiers of the seeded accounts, excluding anything this class constructed. */
    private static List<String> seededAccountIds(final Connection connection) throws SQLException {
        final List<String> identifiers = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT acct_id FROM account WHERE acct_id NOT LIKE ? ORDER BY acct_id")) {
            statement.setString(1, RESERVED_PREFIX + "%");
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    identifiers.add(rows.getString(1));
                }
            }
        }
        return identifiers;
    }

    /** Inserts one constructed account carrying the given group identifier. */
    private static void insertAccount(final Connection connection, final String accountId,
                                      final String groupId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO account (acct_id, acct_active_status, acct_curr_bal, acct_credit_limit,"
                        + " acct_cash_credit_limit, acct_open_date, acct_expiration_date,"
                        + " acct_reissue_date, acct_curr_cyc_credit, acct_curr_cyc_debit,"
                        + " acct_addr_zip, acct_group_id)"
                        + " VALUES (?, 'Y', 100.00, 5000.00, 500.00, '2020-01-01', '2030-01-01',"
                        + " '2025-01-01', 0.00, 0.00, '99999     ', ?)")) {
            statement.setString(1, accountId);
            statement.setString(2, groupId);
            statement.executeUpdate();
        }
    }

    /** Inserts one constructed category balance on the type and category the seed uses. */
    private static void insertBalance(final Connection connection, final String accountId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO transaction_category_balance (trancat_acct_id, trancat_type_cd,"
                        + " trancat_cd, tran_cat_bal) VALUES (?, ?, ?, 0.00)")) {
            statement.setString(1, accountId);
            statement.setString(2, SEEDED_TYPE);
            statement.setString(3, SEEDED_CATEGORY);
            statement.executeUpdate();
        }
    }

    /** Runs a scalar count query. */
    private static int countOf(final Connection connection, final String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as("a count query must return a row").isTrue();
            return rows.getInt(1);
        }
    }
}
