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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Set;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Asserts the parts of the production seeded-database refusal that do not need a database: which
 * events it acts on, which history-table configuration it accepts, how it treats a version it cannot
 * read, and what a diagnostic raised over an unreadable database is allowed to say.
 *
 * <h2>What is deliberately NOT asserted here</h2>
 *
 * <p>The three signals themselves - an applied seed version, a reserved sign-on identity and the
 * seeded reference volumes - are asserted in {@code ProductionSeedRejectionCallbackIT} against a real
 * PostgreSQL server carrying real migrated state. That division is not a convenience: every one of
 * those signals is a statement about what a database contains, and a mocked result set would answer
 * with whatever this file chose, which is the thing under test. A unit test can only prove the parts
 * whose answer does not come from the database.
 *
 * <h2>Fail-closed, asserted rather than assumed</h2>
 *
 * <p>Two guards exist so that the control cannot be silently disabled, and both are asserted here
 * because neither is reachable through a real migration: the migration tool refuses to load a history
 * row whose version it cannot parse, and it never presents a renamed history table to a run that did
 * not ask for one. Reaching either through a database would require breaking the tool first.
 *
 * <p>Provenance: this test has no legacy antecedent; the legacy estate carries no test harness. No legacy
 * source text appears here.
 */
@DisplayName("Production seeded-database refusal: the parts that need no database")
final class ProductionSeedRejectionCallbackTest {

    /** A marker no diagnostic may repeat, standing in for anything an operator supplied. */
    private static final String HOSTILE_MARKER = "forged-log-record";

    /** The callback under test. It holds no state, so one instance serves every assertion. */
    private final ProductionSeedRejectionCallback callback = new ProductionSeedRejectionCallback();

    @Nested
    @DisplayName("the events it acts on")
    final class TheEventsItActsOn {

        @ParameterizedTest(name = "acts on {0}")
        @ValueSource(strings = {"BEFORE_VALIDATE", "BEFORE_MIGRATE"})
        @DisplayName("acts on both events that fire before a script is executed, so its own diagnosis "
                + "reaches the operator whichever of the two the tool raises first")
        void actsOnBothPreExecutionEvents(final String eventName) {
            assertThat(callback.supports(Event.valueOf(eventName), null))
                    .as("%s fires before the first script runs. Handling only one of the two would let "
                            + "the tool's general validation message arrive instead of the message that "
                            + "names seeded credentials as the problem", eventName)
                    .isTrue();
        }

        @Test
        @DisplayName("acts on no other event, so it cannot run after a migration has already written "
                + "to a database it was supposed to refuse")
        void actsOnNoOtherEvent() {
            Set<Event> handled = EnumSet.of(Event.BEFORE_VALIDATE, Event.BEFORE_MIGRATE);

            for (final Event event : Event.values()) {
                if (handled.contains(event)) {
                    continue;
                }
                assertThat(callback.supports(event, null))
                        .as("%s must not be handled: a refusal raised after execution would be a "
                                + "refusal raised over a database this control had already allowed to "
                                + "be written to", event)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("accepts the ambient transaction, because every statement it issues is a read")
        void acceptsTheAmbientTransaction() {
            assertThat(callback.canHandleInTransaction(Event.BEFORE_MIGRATE, null))
                    .as("declining would open a second connection during start-up for no benefit; the "
                            + "control writes nothing and so needs no boundary of its own")
                    .isTrue();
        }

        @Test
        @DisplayName("reports a name the migration tool can log")
        void reportsALoggableName() {
            assertThat(callback.getCallbackName())
                    .isEqualTo(ProductionSeedRejectionCallback.CALLBACK_NAME)
                    .isNotBlank();
        }
    }

    @Nested
    @DisplayName("the history table it insists on")
    final class TheHistoryTableItInsistsOn {

        @ParameterizedTest(name = "accepts the configured table spelled [{0}]")
        @ValueSource(strings = {"flyway_schema_history", "FLYWAY_SCHEMA_HISTORY", " flyway_schema_history "})
        @DisplayName("accepts the delivered history table however it is cased or padded, so a "
                + "legitimate configuration is not refused on a formatting difference")
        void acceptsTheDeliveredHistoryTable(final String configured) throws SQLException {
            Connection connection = mock(Connection.class);
            when(connection.prepareStatement(anyString()))
                    .thenThrow(new SQLException("read refused by this test"));

            assertThatExceptionOfType(FlywayException.class)
                    .as("the table name is accepted, so the failure that follows is the read failing "
                            + "and not the name being rejected")
                    .isThrownBy(() -> callback.handle(Event.BEFORE_MIGRATE,
                            contextWith(configured, connection)))
                    .withMessageContaining("unable to establish");
        }

        @ParameterizedTest(name = "refuses the configured table [{0}]")
        @ValueSource(strings = {"schema_history", "carddemo_flyway_history", "  "})
        @DisplayName("refuses a renamed history table outright, because the control reads it by name "
                + "and a renamed one would leave the most authoritative signal unevaluable")
        void refusesARenamedHistoryTable(final String configured) {
            assertThatExceptionOfType(FlywayException.class)
                    .isThrownBy(() -> callback.handle(Event.BEFORE_MIGRATE,
                            contextWith(configured, mock(Connection.class))))
                    .withMessageContaining(ProductionSeedRejectionCallback.HISTORY_TABLE)
                    .withMessageContaining("spring.flyway.table");
        }

        @Test
        @DisplayName("refuses an absent history-table setting for the same reason, because silence is "
                + "not evidence that the delivered name is in force")
        void refusesAnAbsentHistoryTableSetting() {
            assertThatExceptionOfType(FlywayException.class)
                    .isThrownBy(() -> callback.handle(Event.BEFORE_VALIDATE,
                            contextWith(null, mock(Connection.class))))
                    .withMessageContaining(ProductionSeedRejectionCallback.HISTORY_TABLE);
        }
    }

    @Nested
    @DisplayName("a version it cannot read")
    final class AVersionItCannotRead {

        @ParameterizedTest(name = "[{0}] reaches the seeds")
        @ValueSource(strings = {"3", "3.1", "4", "10", "99.99"})
        @DisplayName("treats every version at or above the first seed version as reaching the seeds")
        void treatsASeedVersionAsReachingTheSeeds(final String version) {
            assertThat(callback.reachesSeedVersions(version)).isTrue();
        }

        @ParameterizedTest(name = "[{0}] does not reach the seeds")
        @ValueSource(strings = {"1", "1.1", "2", "2.99", "-1"})
        @DisplayName("treats every readable version below the first seed version as not reaching them, "
                + "so a correctly migrated production database is not refused")
        void treatsASchemaVersionAsNotReachingTheSeeds(final String version) {
            assertThat(callback.reachesSeedVersions(version)).isFalse();
        }

        @ParameterizedTest(name = "[{0}] is treated as reaching the seeds")
        @ValueSource(strings = {"not-a-version", "3-preview", "..", "1.x", "v3", "3a"})
        @DisplayName("treats a version it cannot parse as reaching the seeds, because a history row "
                + "nobody can read is not something to continue past on a production database")
        void treatsAnUnreadableVersionAsReachingTheSeeds(final String version) {
            assertThat(callback.reachesSeedVersions(version))
                    .as("fail-closed is the only safe answer: the alternative is to decide that an "
                            + "unreadable history row is harmless and start over a database whose "
                            + "contents nothing has established")
                    .isTrue();
        }

        @Test
        @DisplayName("treats an absent or blank version as a repeatable migration rather than a seed, "
                + "because the history column is legitimately null for one")
        void treatsAnAbsentVersionAsNotASeed() {
            assertThat(callback.reachesSeedVersions(null)).isFalse();
            assertThat(callback.reachesSeedVersions("   ")).isFalse();
        }
    }

    @Nested
    @DisplayName("what a diagnostic is allowed to say")
    final class WhatADiagnosticIsAllowedToSay {

        @Test
        @DisplayName("wraps an unreadable database in a refusal that names the inspected tables and "
                + "never the underlying message, per decision DL-041")
        void wrapsAnUnreadableDatabaseWithoutRepeatingItsMessage() throws SQLException {
            Connection connection = mock(Connection.class);
            when(connection.prepareStatement(anyString()))
                    .thenThrow(new SQLException("relation \"" + HOSTILE_MARKER + "\" does not exist"));

            assertThatExceptionOfType(FlywayException.class)
                    .isThrownBy(() -> callback.handle(Event.BEFORE_MIGRATE, contextWith(
                            ProductionSeedRejectionCallback.HISTORY_TABLE, connection)))
                    .withMessageContaining(ProductionSeedRejectionCallback.HISTORY_TABLE)
                    .withMessageContaining(ProductionSeedRejectionCallback.SIGN_ON_TABLE)
                    .withMessageNotContaining(HOSTILE_MARKER)
                    .satisfies(refusal -> assertThat(refusal.getCause())
                            .as("the underlying failure is carried as a cause, so it reaches a "
                                    + "structured log without being interpolated into a message an "
                                    + "operator's value could shape")
                            .isInstanceOf(SQLException.class));
        }

        @Test
        @DisplayName("refuses rather than continuing when the database cannot be inspected, so an "
                + "unreadable production database is never treated as an unseeded one")
        void refusesRatherThanContinuingWhenTheDatabaseCannotBeInspected() throws SQLException {
            Connection connection = mock(Connection.class);
            when(connection.prepareStatement(anyString()))
                    .thenThrow(new SQLException("connection reset"));

            assertThatExceptionOfType(FlywayException.class)
                    .as("continuing here would convert a failed inspection into an implicit "
                            + "certificate of cleanliness, which is exactly the reasoning this whole "
                            + "control exists to remove")
                    .isThrownBy(() -> callback.handle(Event.BEFORE_VALIDATE, contextWith(
                            ProductionSeedRejectionCallback.HISTORY_TABLE, connection)))
                    .withMessageContaining("refused");
        }
    }

    @Nested
    @DisplayName("the literals the control is built on")
    final class TheLiteralsTheControlIsBuiltOn {

        @Test
        @DisplayName("names the ten reserved sign-on identifiers, five administrative and five "
                + "standard, exactly as the sign-on seed inserts them")
        void namesTheTenReservedSignOnIdentifiers() {
            assertThat(ProductionSeedRejectionCallback.SEEDED_SIGN_ON_IDENTIFIERS)
                    .as("the count and the split are the reason this signal matters: five of the ten "
                            + "carry the administrative user type")
                    .hasSize(10)
                    .containsExactly("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005",
                            "USER0001", "USER0002", "USER0003", "USER0004", "USER0005");
        }

        @Test
        @DisplayName("requires five simultaneous volume matches, which is what makes that signal "
                + "precise rather than merely suspicious")
        void requiresFiveSimultaneousVolumeMatches() {
            assertThat(ProductionSeedRejectionCallback.SEEDED_VOLUMES)
                    .as("any one of these counts could occur in a production database - the "
                            + "transaction types and categories are real legacy reference data - so "
                            + "the conjunction is the control and shortening the list would weaken it")
                    .hasSize(5)
                    .extracting(ProductionSeedRejectionCallback.SeededVolume::table)
                    .containsExactly("customer", "disclosure_group", "transaction_category",
                            "transaction_type", "daily_transaction");
            assertThat(ProductionSeedRejectionCallback.SEEDED_VOLUMES)
                    .extracting(ProductionSeedRejectionCallback.SeededVolume::rowCount)
                    .as("the volumes the reference seed documents: fifty customers, three complete "
                            + "disclosure groups of seventeen, eighteen categories, seven types and "
                            + "three hundred unposted daily transactions")
                    .containsExactly(50L, 51L, 18L, 7L, 300L);
            assertThat(ProductionSeedRejectionCallback.SEEDED_VOLUMES)
                    .as("each entry carries the statement that counts its own table, so a reordering "
                            + "cannot pair one table's name with another table's count or query")
                    .allSatisfy(volume -> assertThat(volume.countStatement())
                            .isEqualTo("SELECT count(*) FROM " + volume.table()));
        }

        @Test
        @DisplayName("draws its seed boundary from the same version the configuration controls use, so "
                + "the three controls cannot disagree about where the seeds begin")
        void drawsItsSeedBoundaryFromTheSameVersion() {
            assertThat(ProductionSeedRejectionCallback.FIRST_SEED_VERSION)
                    .as("the configuration ceiling is %s, so the first excluded version is the one "
                            + "above it; a different boundary here would leave one control refusing "
                            + "what another allowed", FlywayConfig.ALL_RESOLVED_VERSIONS_TARGET)
                    .isEqualTo("3");
        }
    }

    /**
     * Builds a migration context over a configured history-table name and a connection.
     *
     * @param historyTable the value the configuration reports for the history table, possibly
     *                     {@code null}
     * @param connection   the connection the context hands out
     * @return a context suitable for handing to the callback
     */
    private static Context contextWith(final String historyTable, final Connection connection) {
        Configuration configuration = mock(Configuration.class);
        when(configuration.getTable()).thenReturn(historyTable);
        Context context = mock(Context.class);
        when(context.getConfiguration()).thenReturn(configuration);
        when(context.getConnection()).thenReturn(connection);
        return context;
    }
}
