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
 * Writes this module's own record of which schema versions a start-up applied and which version it reached.
 *
 * <h2>Why the application has to own this record</h2>
 *
 * <p>Local validation reads the application log to establish two things before anything else about a run is
 * believed: that every delivered migration was applied, and that the schema reached its highest delivered
 * version. Until now both were read out of the migration tool's own log output, which made a third party's
 * message text a load-bearing part of this module's validation - a dependency that a library upgrade can
 * break silently, and that offers no way to distinguish "the tool said nothing" from "the tool was
 * configured not to speak".
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
 * how many were applied and the highest version reached. Nothing else: no connection, no URL, no host, no
 * schema-history table name, no statement text and no row content. The version and the description are
 * values this module authored in its own migration filenames, so the record is composed entirely of things
 * this module already ships.
 *
 * <p>When a start-up applies nothing - the ordinary case for every start-up after the first - it says so
 * once, and says so as information rather than as a warning: an already-current schema is the expected
 * state, and a warning there would train an operator to ignore the category.
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
 * <p>Provenance: the migrations this records are the relational translation of the ten
 * {@code DEFINE CLUSTER} provisioning jobs of the legacy estate, at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced.
 *
 * <p>See {@code docs/decision-log.md} entry DL-311.
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
            LOGGER.info("SCHEMA ALREADY CURRENT - no migration was applied by this start-up");
            return;
        }
        LOGGER.info("SCHEMA MIGRATION COMPLETE - applied={} highestVersionApplied={}",
                Integer.valueOf(count), reached == null ? "(none)" : reached.getVersion());
    }
}
