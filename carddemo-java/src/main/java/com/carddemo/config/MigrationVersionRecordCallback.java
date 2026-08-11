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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes this module's own record of which schema versions <strong>this invocation</strong> applied, and
 * the highest version among them.
 *
 * <h2>What the record is, stated as narrowly as the mechanism can support</h2>
 *
 * <p>The contract is deliberately about the <em>effect of this start-up</em> and not about the state of the
 * database, and the distinction is not pedantry: on every start-up after the first there is nothing to
 * apply, so there is no version to name, and a promise of "the version it reached" would describe
 * something this callback cannot see. A Flyway callback receives a
 * {@link org.flywaydb.core.api.callback.Context}, which offers the migration currently being applied and
 * nothing about migrations applied by anyone else; establishing the version an already-current database
 * stands at means querying the schema-history table, which is the one thing this record is written to
 * avoid naming. So the promise is narrowed to what the events establish, and the already-current case says
 * plainly that this invocation applied no version rather than implying a version it did not read.
 *
 * <h2>Why the application has to own this record</h2>
 *
 * <p>Local validation reads the application log to establish two things before anything else about a run is
 * believed: that every delivered migration was applied on a first start-up, and that the highest version
 * applied is the highest one the resolved locations deliver. Until now both were read out of the migration
 * tool's own log output, which made a third party's message text a load-bearing part of this module's
 * validation - a dependency that a library upgrade can break silently, and that offers no way to
 * distinguish "the tool said nothing" from "the tool was configured not to speak".
 *
 * <p>The immediate reason it moved is narrower. The migration tool's executor announces three lines before
 * any migration runs - the JDBC URL with the host, the port and the database name, the driver and its
 * version, and the database type - and that category is now held above the level at which they are emitted,
 * because topology is the reconnaissance a reader needs before a credential is worth anything and a version
 * pair is a vulnerability lookup. The lines the validation actually depends on come from a different
 * category and survive the raise untouched; this callback exists so that they need not be depended on at
 * all.
 *
 * <h2>What it records, and what it deliberately does not</h2>
 *
 * <p>One line per applied migration, naming the version and the description, and one summary line naming
 * how many this invocation applied and the highest version among them. Nothing else: no connection, no
 * URL, no host, no schema-history table name, no statement text and no row content. The version and the
 * description are values this module authored in its own migration filenames, so the record is composed
 * entirely of things this module already ships.
 *
 * <p>When a start-up applies nothing - the ordinary case for every start-up after the first - it says so
 * once, and says so as information rather than as a warning: an already-current schema is the expected
 * state, and a warning there would train an operator to ignore the category. It also says so without
 * naming a version, because this invocation applied none and the version the database stands at is not a
 * fact this callback is in a position to read. A reader who needs that figure reads the schema-history
 * table, which is the authority for it.
 *
 * <h2>Why the count is held rather than read back</h2>
 *
 * <p>The alternative was to query the schema-history table after the migration finished. That would have
 * put a table name this module does not own into the diagnostic, would have needed a second connection or a
 * borrowed one, and would have reported the state of the database rather than the effect of this start-up -
 * which is the thing being validated. Counting the events the tool raises reports exactly what this
 * process did.
 *
 * <p>The two counters are atomic and are reset when the migration operation begins. A migration operation
 * is single-threaded, so the atomics are not there for contention; they are there because the same callback
 * instance serves the whole context lifetime and a second migration operation - a test that migrates twice
 * against one context - must not inherit the first one's totals.
 *
 * <p>See {@code docs/decision-log.md} entries DL-311 and DL-335, the latter recording why the contract is
 * the effect of one invocation rather than the state of the database.
 *
 * @since 1.0.0
 */
final class MigrationVersionRecordCallback implements Callback {

    /** The record's channel. Named for this class so it can be raised or lowered on its own. */
    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationVersionRecordCallback.class);

    /** How many migrations this start-up applied. */
    private final AtomicInteger applied = new AtomicInteger();

    /** The highest version this start-up applied, or {@code null} when it applied none. */
    private final AtomicReference<MigrationVersion> highest = new AtomicReference<>();

    /** Creates the callback. */
    MigrationVersionRecordCallback() {
        // Intentionally empty: the two counters are initialised at their declarations and reset per run.
    }

    /**
     * Elects the three events this record is composed from.
     *
     * @param  event   the event being offered
     * @param  context the migration context, unused in this decision
     * @return {@code true} for the start of the operation, each applied migration, and the end
     */
    @Override
    public boolean supports(final Event event, final Context context) {
        return event == Event.BEFORE_MIGRATE
                || event == Event.AFTER_EACH_MIGRATE
                || event == Event.AFTER_MIGRATE;
    }

    /**
     * Accepts the ambient transaction, because this callback issues no statement of any kind.
     *
     * @param  event   the event being handled, unused in this decision
     * @param  context the migration context, unused in this decision
     * @return {@code true} always
     */
    @Override
    public boolean canHandleInTransaction(final Event event, final Context context) {
        return true;
    }

    /**
     * Names this callback for the migration tool's own bookkeeping.
     *
     * @return a stable name
     */
    @Override
    public String getCallbackName() {
        return "carddemo-migration-version-record";
    }

    /**
     * Records one event.
     *
     * <p>Never raises. A diagnostic that could fail a start-up would make the record more dangerous than
     * the absence it replaces, so a migration whose information cannot be read is recorded as unnamed
     * rather than allowed to interrupt the operation.
     *
     * @param event   the event being handled
     * @param context the migration context, read only for the migration being applied
     */
    @Override
    public void handle(final Event event, final Context context) {
        if (event == Event.BEFORE_MIGRATE) {
            this.applied.set(0);
            this.highest.set(null);
            return;
        }
        if (event == Event.AFTER_EACH_MIGRATE) {
            recordApplied(context);
            return;
        }
        recordOutcome();
    }

    /**
     * Records one applied migration and advances the highest version seen.
     *
     * @param context the migration context carrying the migration just applied
     */
    private void recordApplied(final Context context) {
        final MigrationInfo info = context == null ? null : context.getMigrationInfo();
        final MigrationVersion version = info == null ? null : info.getVersion();
        final String description = info == null || info.getDescription() == null
                ? "(unnamed)" : info.getDescription();
        this.applied.incrementAndGet();
        if (version != null) {
            this.highest.accumulateAndGet(version,
                    (current, candidate) -> current == null || current.compareTo(candidate) < 0
                            ? candidate : current);
        }
        LOGGER.info("APPLIED MIGRATION version={} description={}",
                version == null ? "(none)" : version.getVersion(), description);
    }

    /**
     * Records what the whole operation achieved.
     */
    private void recordOutcome() {
        final int count = this.applied.get();
        final MigrationVersion reached = this.highest.get();
        if (count == 0) {
            LOGGER.info("SCHEMA ALREADY CURRENT - this start-up applied no migration, so it names no"
                    + " version; the schema history table is the authority for the version in force");
            return;
        }
        LOGGER.info("SCHEMA MIGRATION COMPLETE - applied={} highestVersionApplied={}",
                Integer.valueOf(count), reached == null ? "(none)" : reached.getVersion());
    }
}
