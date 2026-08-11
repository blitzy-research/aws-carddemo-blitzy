-- Copyright Amazon.com, Inc. or its affiliates.
-- All Rights Reserved.
--
-- Licensed under the Apache License, Version 2.0 (the "License").
-- You may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--    http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
-- either express or implied. See the License for the specific
-- language governing permissions and limitations under the License

-- V2_1__create_sign_on_attempt_ledger.sql - the deployment-wide sign-on attempt ledger.
--
-- ONE TABLE, AND IT IS NOT A TWELFTH RECORD TABLE. V1__create_schema.sql creates the eleven tables the
-- estate's eleven record layouts define, and that count is contractual and unchanged: this table is not one
-- of them and is not derived from a copybook, a dataset or a VSAM cluster. It holds no business record, no
-- customer datum and no money. It holds the running count of failed sign-on attempts per subject - one
-- operator identifier, or one caller address - and nothing else.
--
-- WHY IT IS NOT THE FEATURE EXPANSION DL-148 REFUSED. An earlier revision added an operational outbox table
-- so a partially published card stream could be resumed, and that was removed because the legacy queue
-- definition carries ERROROPTION(IGNORE) and the emitting program abandons a refused write rather than
-- deferring it - the legacy behaviour needed no delivery state, so persisting delivery state added
-- behaviour the estate did not have. This table adds no behaviour at all. The attempt allowance it stores
-- was already delivered and already enforced (DL-268); what was wrong was WHERE the count lived. Held in
-- one process's memory the allowance is counted once per process, so two replicas grant twice the attempts,
-- ten grant ten times, and every restart returns every allowance to full without anyone authenticating.
-- This table moves the same count to the one place every replica already looks and that outlives all of
-- them. See docs/decision-log.md DL-343, and DL-148 for the distinction just drawn.
--
-- SCHEMA LOCATION, SO EVERY PROFILE RECEIVES IT. Resolved from classpath:db/migration/schema alongside V1
-- and V2, because production needs this table more than any other profile does - it is the only profile
-- that runs more than one instance. It is NOT a seed: it ships no row, and the ledger is correct empty.
--
-- THE VERSION SITS BETWEEN THE INDEXES AND THE SEEDS, AND THAT PLACEMENT IS LOAD-BEARING. Versions 3 and 4
-- are the fixture and identity seeds in the sibling seed location. This script is 2.1 - after V2 and before
-- both seeds - because THREE separate controls depend on every schema version sorting below every seed
-- version. An earlier revision of this script was numbered 5, above the seeds, and each of the three broke:
--
--   1. THE PRODUCTION CEILING. spring.flyway.target stays below 3, so the seeds are excluded by their
--      NUMBER as well as by their DIRECTORY, exactly as V1 through V4 state in their own headers. A pin at
--      or above 3 leaves the tool holding a pending instruction to apply them, which one misconfigured
--      property would then release.
--   2. THE PRODUCTION SEEDED-DATABASE REFUSAL. That control reads seed contamination off the history: a
--      successful row at or above version 3 means seed data reached this database. A SCHEMA script
--      numbered 5 satisfies that test, so a correctly migrated production database - 1, 2 and 5 - refused
--      to start, reporting that fifty synthetic customer rows had been inserted into it. The control was
--      right and the numbering was wrong.
--   3. ADDING THE SEED LOCATION TO AN ALREADY-MIGRATED DATABASE. Local and test resolve both locations,
--      and a database migrated production-shaped first and seeded afterwards is an arrangement this module
--      ships rather than a hypothetical. With a schema version 5 applied, versions 3 and 4 are pending
--      BELOW it, which the tool refuses as out-of-order and which no shipped profile should override.
--
-- Flyway compares version parts numerically, so 2 < 2.1 < 3 and a decimal version is ordinary here rather
-- than exceptional. A further schema script takes 2.2, and so on; the seeds keep 3 and 4. The pin is
-- asserted against the versions the schema location actually delivers, so this script and that pin move in
-- one commit or the build fails. docs/decision-log.md DL-343 records the reversal and its evidence.
--
-- WHAT V1 THROUGH V4 SAY ABOUT THE CEILING STILL HOLDS. Each states in its own header that the production
-- ceiling excludes the seeds by their number as well as by their directory. The pin moves from "2" to
-- "2.1" because one more schema version now exists to reach, and the property those headers assert is
-- untouched: 2.1 is still below 3. Those four files are already applied wherever they will ever be applied
-- and checksum validation is on, so none of them could have been edited in place - and none needed to be.
--
-- Validated against PostgreSQL 16.14 using plain table, constraint and index DDL; nothing here depends on
-- an extension. Checksum validation is enabled on migrate, so this file is immutable once applied: a later
-- change of intent must arrive as a new version, never as an edit here.

-- GLOBAL RULES FOR THIS MIGRATION - each is a deliberate decision, not an omission.
--
--  1. EXACTLY TWO EXECUTABLE STATEMENTS: one table creation and one index creation. No seed row, no
--     trigger, no scheduled job, no function. Both counts are contractual and are asserted by a test that
--     reads this file.
--
--  2. NO CLEAN-UP JOB, AND NONE IS NEEDED. A spent entry is removed by the application, inside the same
--     serialized transition that would otherwise have to refuse a new subject: the ledger sweeps only when
--     it is at its ceiling and only entries that are neither refusing nor inside their window. That makes
--     the table self-limiting without a scheduler, without a background thread and without an operator
--     runbook step - and it means an unswept table is bounded rather than merely usually small.
--
--  3. THE SUBJECT IS THE WHOLE KEY, AND IT IS BOUNDED. A subject is a two-character namespace prefix - i:
--     for an operator identity, s: for a caller source - followed by the value. The value is partly
--     caller-supplied, so the whole prefixed subject is bounded in the application at
--     SignOnAttemptLedger.MAX_SUBJECT_LENGTH, which is 64, and again here by the column width, which is
--     the same figure. The delivered sign-on contract bounds an identifier at eight characters, so an
--     ordinary subject occupies ten of the sixty-four; the rest exists for a caller reaching the service
--     below that contract, because without a bound a caller chooses the size of a stored key.
--
--  4. NO SUBJECT IS EVER SELECT-ED FOR A DIAGNOSTIC. The application never logs a subject and never
--     reports one to a caller: it is an operator identifier or a network address, and naming one would
--     disclose which identifiers exist. Nothing in this schema encourages otherwise - there is no
--     descriptive column to log and no audit column to correlate.
--
--  5. TIMESTAMPS ARE timestamptz AND NOTHING ELSE. Both instants are absolute points in time compared
--     across replicas that need not share a local zone, so a zone-less type would make the comparison
--     depend on which instance wrote the row. The application truncates to microseconds before writing,
--     which is this type's own resolution, so an instant written and read back is the instant that went
--     in.
--
--  6. NO FOREIGN KEY TO user_security, ON PURPOSE. Half the subjects are caller addresses rather than
--     identities, and of the identity subjects the interesting ones are precisely those that do NOT
--     resolve to a user - an enumeration sweep names identifiers that do not exist. A reference would
--     refuse to record exactly the attempts most worth recording.

CREATE TABLE sign_on_attempt (
    subject                     VARCHAR(64)     NOT NULL,   -- namespace prefix + value, bounded together
    failures                    INTEGER         NOT NULL,   -- failures inside the current window
    window_started_at           TIMESTAMPTZ     NOT NULL,   -- when the current window began
    refused_until               TIMESTAMPTZ,                -- when a refusal lifts; NULL when not refused
    CONSTRAINT pk_sign_on_attempt PRIMARY KEY (subject),
    -- The prefix is what keeps the two namespaces from colliding. Without it an operator identifier and a
    -- caller address that happened to read the same would share one allowance, and a caller could exhaust
    -- another party's identity allowance by arriving from an address spelled like it.
    CONSTRAINT ck_sign_on_attempt_namespaced CHECK (subject LIKE 'i:%' OR subject LIKE 's:%'),
    -- A prefix and at least one character of value. An empty subject is not a subject, and a row holding
    -- one would accumulate every unattributable attempt into a single allowance.
    CONSTRAINT ck_sign_on_attempt_subject_width CHECK (
        char_length(subject) >= 3 AND char_length(subject) <= 64
    ),
    -- A negative count would make the allowance arithmetic run backwards and could never be reached by any
    -- transition the application performs, so it is refused at the column rather than trusted.
    CONSTRAINT ck_sign_on_attempt_failures_not_negative CHECK (failures >= 0),
    -- A refusal deadline is in the future of the window it was engaged in, because the transition that
    -- engages one restarts the window at the same instant. Equality is permitted only because a refusal
    -- period is configurable and a deployment may in principle configure a very short one; a deadline
    -- BEFORE its own window start could only be a clock defect or a hand-written row.
    CONSTRAINT ck_sign_on_attempt_refusal_after_window CHECK (
        refused_until IS NULL OR refused_until >= window_started_at
    )
);

-- The sweep's predicate, and the only query in the ledger that is not a primary-key lookup. Without it the
-- sweep scans the table, and the sweep runs at exactly the moment the table is at its largest - when the
-- ceiling has been reached - which is the worst moment to scan. window_started_at leads because it is the
-- predicate that selects: every row has one and the sweep always constrains it, whereas refused_until is
-- NULL for most rows and only narrows what the first column already found.
CREATE INDEX ix_sign_on_attempt_sweep ON sign_on_attempt (window_started_at, refused_until);
