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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Specification of the migration record this module writes for itself.
 *
 * <h2>What is actually being asserted here</h2>
 *
 * <p>Local validation establishes two facts about a start-up before it believes anything else: that every
 * delivered migration was applied on a first start-up, and that the highest version applied is the highest
 * one the resolved locations deliver. Those two facts used to be read out of the migration tool's own log
 * text. The tool's opening announcement publishes
 * the JDBC URL, the driver and the database type - the host, the port, the database name and an exact
 * server-and-driver version pair - so that category is now held above the level it speaks at, and this
 * callback exists so the validation need not depend on a third party's message text at all.
 *
 * <p>Each group below therefore asserts one property of that replacement: that the two record lines carry
 * the count and the version the validation reads; that the record is composed only of values this module
 * authored in its own migration filenames, and names no connection, host, URL or history table; that a
 * second migration operation against one context does not inherit the first one's totals; and that a
 * diagnostic can never be the reason a start-up fails.
 *
 * @since 1.0.0
 */
@DisplayName("Migration version record: what a start-up says it applied, and what it never says")
class MigrationVersionRecordCallbackTest {

    /** A description this module authored, in the shape its migration filenames use. */
    private static final String FIRST_DESCRIPTION = "create schema";

    /** A later description, from the highest delivered migration. */
    private static final String LAST_DESCRIPTION = "seed user security";

    /**
     * The line an already-current database produces, in full.
     *
     * <p>Asserted verbatim rather than by prefix, because the substance of the narrowed contract is the
     * second half of the sentence: this invocation applied nothing, so it names no version and points at
     * the authority that does. A prefix match would pass over a wording that quietly implied one.
     */
    private static final String NOTHING_APPLIED_LINE =
            "SCHEMA ALREADY CURRENT - this start-up applied no migration, so it names no version;"
                    + " the schema history table is the authority for the version in force";

    /** The callback under test. */
    private MigrationVersionRecordCallback callback;

    /** Where the record lands. */
    private ListAppender<ILoggingEvent> recorder;

    /** The category's own logger, so the level can be lowered and put back. */
    private Logger recordLogger;

    /** The level the category held before this test touched it. */
    private Level originalLevel;

    @BeforeEach
    void captureTheRecord() {
        this.callback = new MigrationVersionRecordCallback();
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        this.recordLogger = context.getLogger(MigrationVersionRecordCallback.class);
        this.originalLevel = this.recordLogger.getLevel();
        this.recordLogger.setLevel(Level.INFO);
        this.recorder = new ListAppender<>();
        this.recorder.setContext(context);
        this.recorder.start();
        this.recordLogger.addAppender(this.recorder);
    }

    @AfterEach
    void releaseTheRecord() {
        this.recordLogger.detachAppender(this.recorder);
        this.recorder.stop();
        this.recordLogger.setLevel(this.originalLevel);
    }

    /**
     * Drives one whole migration operation.
     *
     * @param versionsAndDescriptions the version and description of each migration applied, in order
     */
    private void migrate(final List<MigrationInfo> versionsAndDescriptions) {
        this.callback.handle(Event.BEFORE_MIGRATE, mock(Context.class));
        for (final MigrationInfo applied : versionsAndDescriptions) {
            final Context context = mock(Context.class);
            when(context.getMigrationInfo()).thenReturn(applied);
            this.callback.handle(Event.AFTER_EACH_MIGRATE, context);
        }
        this.callback.handle(Event.AFTER_MIGRATE, mock(Context.class));
    }

    /**
     * Builds the migration information the tool would hand over for one applied migration.
     *
     * @param  version     the version, or {@code null} to model information without one
     * @param  description the description, or {@code null} to model information without one
     * @return the stubbed information
     */
    private static MigrationInfo appliedMigration(final String version, final String description) {
        final MigrationInfo info = mock(MigrationInfo.class);
        when(info.getVersion()).thenReturn(version == null ? null : MigrationVersion.fromVersion(version));
        when(info.getDescription()).thenReturn(description);
        return info;
    }

    /** The record lines, in order. */
    private List<String> recorded() {
        final List<String> lines = new ArrayList<>();
        for (final ILoggingEvent event : this.recorder.list) {
            lines.add(event.getFormattedMessage());
        }
        return lines;
    }

    @Nested
    @DisplayName("the two facts local validation reads")
    class TheTwoFacts {

        @Test
        @DisplayName("names every applied migration once, with its version and its description")
        void namesEveryAppliedMigration() {
            migrate(List.of(appliedMigration("1", FIRST_DESCRIPTION),
                    appliedMigration("4", LAST_DESCRIPTION)));

            assertThat(recorded())
                    .contains("APPLIED MIGRATION version=1 description=" + FIRST_DESCRIPTION)
                    .contains("APPLIED MIGRATION version=4 description=" + LAST_DESCRIPTION);
        }

        @Test
        @DisplayName("closes with the count this invocation applied and the highest version among them, "
                + "which is the pair the validation asserts")
        void closesWithTheCountAndTheHighestVersion() {
            migrate(List.of(appliedMigration("1", FIRST_DESCRIPTION),
                    appliedMigration("2", "create indexes"),
                    appliedMigration("3", "seed reference data"),
                    appliedMigration("4", LAST_DESCRIPTION)));

            assertThat(recorded())
                    .last()
                    .isEqualTo("SCHEMA MIGRATION COMPLETE - applied=4 highestVersionApplied=4");
        }

        @Test
        @DisplayName("reports the highest version applied and not the last one applied, because the tool's "
                + "ordering is its own affair")
        void reportsTheHighestAndNotTheLast() {
            migrate(List.of(appliedMigration("4", LAST_DESCRIPTION),
                    appliedMigration("2", "create indexes")));

            assertThat(recorded())
                    .last()
                    .isEqualTo("SCHEMA MIGRATION COMPLETE - applied=2 highestVersionApplied=4");
        }

        @Test
        @DisplayName("says so as information, not as a warning, when a start-up applies nothing - which is "
                + "every start-up after the first - and names no version, because it read none")
        void saysSoWhenNothingWasApplied() {
            migrate(List.of());

            assertThat(recorded())
                    .containsExactly(NOTHING_APPLIED_LINE);
            assertThat(recorder.list.get(0).getLevel())
                    .as("a warning on the expected state trains an operator to ignore the category")
                    .isEqualTo(Level.INFO);
        }
    }

    @Nested
    @DisplayName("what the record never contains")
    class WhatItNeverContains {

        @Test
        @DisplayName("no connection, URL, host, port, database name, driver, server version or history "
                + "table - the whole reason the tool's own announcement was raised")
        void namesNothingAboutTheEstate() {
            migrate(List.of(appliedMigration("1", FIRST_DESCRIPTION),
                    appliedMigration("4", LAST_DESCRIPTION)));

            assertThat(recorded()).allSatisfy(line -> assertThat(line)
                    .doesNotContain("jdbc:")
                    .doesNotContain("postgres")
                    .doesNotContain("PostgreSQL")
                    .doesNotContain("5432")
                    .doesNotContain("Driver")
                    .doesNotContain("flyway_schema_history")
                    .doesNotContain("@"));
        }

        @Test
        @DisplayName("nothing at all beyond the version and the description, both of which this module "
                + "authored in its own migration filenames")
        void carriesOnlyValuesThisModuleAuthored() {
            migrate(List.of(appliedMigration("4", LAST_DESCRIPTION)));

            assertThat(recorded()).containsExactly(
                    "APPLIED MIGRATION version=4 description=" + LAST_DESCRIPTION,
                    "SCHEMA MIGRATION COMPLETE - applied=1 highestVersionApplied=4");
        }
    }

    @Nested
    @DisplayName("a second operation against one context")
    class ASecondOperation {

        @Test
        @DisplayName("does not inherit the first operation's totals, which is why the two counters are "
                + "reset when the operation begins rather than at construction")
        void doesNotInheritTheFirstOperationsTotals() {
            migrate(List.of(appliedMigration("1", FIRST_DESCRIPTION),
                    appliedMigration("4", LAST_DESCRIPTION)));
            recorder.list.clear();

            migrate(List.of());

            assertThat(recorded())
                    .containsExactly(NOTHING_APPLIED_LINE);
        }

        @Test
        @DisplayName("counts only its own migrations when it applies some")
        void countsOnlyItsOwnMigrations() {
            migrate(List.of(appliedMigration("1", FIRST_DESCRIPTION),
                    appliedMigration("2", "create indexes"),
                    appliedMigration("4", LAST_DESCRIPTION)));
            recorder.list.clear();

            migrate(List.of(appliedMigration("3", "seed reference data")));

            assertThat(recorded())
                    .last()
                    .isEqualTo("SCHEMA MIGRATION COMPLETE - applied=1 highestVersionApplied=3");
        }
    }

    @Nested
    @DisplayName("a diagnostic is never the reason a start-up fails")
    class NeverTheReasonAStartUpFails {

        @Test
        @DisplayName("migration information without a version or a description is recorded as unnamed "
                + "rather than allowed to interrupt the operation")
        void recordsIncompleteInformationAsUnnamed() {
            assertThatCode(() -> migrate(List.of(appliedMigration(null, null))))
                    .doesNotThrowAnyException();

            assertThat(recorded()).containsExactly(
                    "APPLIED MIGRATION version=(none) description=(unnamed)",
                    "SCHEMA MIGRATION COMPLETE - applied=1 highestVersionApplied=(none)");
        }

        @Test
        @DisplayName("a context that carries no migration information at all is recorded, not raised")
        void recordsAContextWithoutInformation() {
            final Context empty = mock(Context.class);

            assertThatCode(() -> {
                callback.handle(Event.BEFORE_MIGRATE, empty);
                callback.handle(Event.AFTER_EACH_MIGRATE, empty);
                callback.handle(Event.AFTER_MIGRATE, empty);
            }).doesNotThrowAnyException();

            assertThat(recorded()).first()
                    .isEqualTo("APPLIED MIGRATION version=(none) description=(unnamed)");
        }

        @Test
        @DisplayName("an absent context is recorded, not raised, because a callback contract this module "
                + "does not own is not a promise it should rely on")
        void recordsAnAbsentContext() {
            assertThatCode(() -> {
                callback.handle(Event.BEFORE_MIGRATE, null);
                callback.handle(Event.AFTER_EACH_MIGRATE, null);
                callback.handle(Event.AFTER_MIGRATE, null);
            }).doesNotThrowAnyException();

            assertThat(recorded())
                    .last()
                    .isEqualTo("SCHEMA MIGRATION COMPLETE - applied=1 highestVersionApplied=(none)");
        }
    }

    @Nested
    @DisplayName("the contract the migration tool sees")
    class TheContractTheToolSees {

        @Test
        @DisplayName("elects exactly the three events the record is composed from, and no other")
        void electsExactlyThreeEvents() {
            final List<Event> elected = new ArrayList<>();
            for (final Event event : Event.values()) {
                if (callback.supports(event, mock(Context.class))) {
                    elected.add(event);
                }
            }

            assertThat(elected).containsExactlyInAnyOrder(
                    Event.BEFORE_MIGRATE, Event.AFTER_EACH_MIGRATE, Event.AFTER_MIGRATE);
        }

        @Test
        @DisplayName("accepts the ambient transaction, because it issues no statement of any kind")
        void acceptsTheAmbientTransaction() {
            assertThat(callback.canHandleInTransaction(Event.AFTER_EACH_MIGRATE, mock(Context.class)))
                    .isTrue();
        }

        @Test
        @DisplayName("names itself stably, so the tool's own bookkeeping identifies it")
        void namesItselfStably() {
            assertThat(callback.getCallbackName()).isEqualTo("carddemo-migration-version-record");
        }
    }

    @Nested
    @DisplayName("how it is wired")
    class HowItIsWired {

        /** The configuration that registers the callback. */
        private static final Path CONFIGURATION =
                Path.of("src", "main", "java", "com", "carddemo", "config", "FlywayConfig.java");

        @Test
        @DisplayName("registered unconditionally, because the evidence a start-up applied its schema is "
                + "wanted in every profile that migrates - not only in the one that validates")
        void registeredUnconditionally() throws IOException {
            final String configuration = Files.readString(CONFIGURATION);
            final int declaration = configuration.indexOf("Callback migrationVersionRecordCallback()");

            assertThat(declaration)
                    .as("the callback must be declared as a bean, or nothing registers it")
                    .isNotNegative();
            assertThat(configuration.substring(Math.max(0, declaration - 400), declaration))
                    .as("a profile or property condition here would silence the record in exactly the "
                            + "deployment whose topology the raise was protecting")
                    .doesNotContain("@Profile")
                    .doesNotContain("@ConditionalOn");
        }
    }
}
