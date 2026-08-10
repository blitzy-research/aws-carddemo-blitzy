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

-- V2__create_indexes.sql - relational integrity and index layer.
--
-- Adds the complete integrity layer over the eleven tables V1__create_schema.sql creates, and
-- exactly three nonunique B-tree secondary indexes standing in for the three legacy alternate
-- indexes, then exactly six foreign keys. NOTHING ELSE.
--
-- ELEVEN TABLES AND NO TWELFTH. The estate defines eleven record layouts and V1 creates eleven
-- tables; this migration creates none. An earlier revision added an operational outbox table so the
-- online-to-batch queue bridge could resume a partial card stream. That was feature expansion: the
-- legacy queue definition carries ERROROPTION(IGNORE) and the emitting program abandons a refused
-- write rather than deferring it, so there is no delivery state to persist. The table and its
-- replay behaviour are removed - see docs/decision-log.md DL-148.
--
-- APPLIES TO ALL PROFILES. V1 and V2 are resolved from classpath:db/migration/schema, the Flyway
-- location EVERY profile configures, so every profile receives an identical schema and integrity layer.
-- Sample rows and sign-on identities are V3 and V4, and they ship from the SIBLING
-- classpath:db/migration/seed, which only the local and test overlays declare - so only those two
-- profiles resolve them at all. A VERSION CEILING SITS BESIDE THAT LIST: the shared baseline and prod
-- declare spring.flyway.target: "2" - the version THIS script carries, and the highest the schema
-- location delivers - while local and test lift it to latest. FlywayConfig refuses any other production
-- value in either direction, the head marker included, and corrects silence to the pin; a ceiling of 1
-- would stop before these nine integrity statements and still report success. The shared parent
-- classpath:db/migration holds no script and is refused as a location under every profile: a location is
-- scanned recursively, so the parent reaches both children and records each script under a name relative
-- to itself. See docs/decision-log.md DL-298 for the location split and DL-334 for the ceiling.
--
-- V1 IS A REQUIRED PREDECESSOR. The nine relational-integrity statements name tables, columns and
-- primary keys that V1 creates and none is guarded, so applying V2 without V1 fails immediately and
-- visibly. Flyway applies versions in ascending order across ALL configured locations - 1, then 2,
-- then the seeds where a profile resolves them - so any later seed lands against a schema whose
-- foreign keys are already in force and is constraint-checked as it is written.
--
-- Validated against PostgreSQL 16.14 using plain schema, table, index and constraint DDL; nothing
-- here depends on an extension. Checksum validation is enabled on
-- migrate, so this file is immutable once applied: a later change of intent must arrive as a new
-- version, never as an edit here.
--
-- Every key width, key offset, reject code, count and record length cited below was read from the
-- legacy checkout and re-derived independently by summing the field widths of the record layout
-- concerned. No legacy source text is copied here.

-- GLOBAL RULES FOR THIS MIGRATION - each is a deliberate decision, not an omission.
--
--  1. EXACTLY NINE EXECUTABLE STATEMENTS: three index creations and six constraint additions. No
--     table creation of any kind. Both counts are contractual and are asserted by tests that read
--     PostgreSQL's catalogs.
--
--  2. FAILURE-VISIBLE DDL. No existence guard, no conditional block, no exception handler and no
--     conflict-tolerant clause anywhere, so schema drift fails the migration loudly. An integrity
--     layer that may or may not have been applied is worse than none, because nothing downstream
--     could rely on it.
--
--  3. NOTHING FROM V1 IS ALTERED - no column type, nullability, default, primary key or table name
--     is touched. V2 only adds integrity, never structure, so the eleven business tables remain
--     exactly the V1 contract. No table, schema, view, routine, trigger, extension or row of data is
--     created.
--
--  4. THE THREE SECONDARY INDEXES ARE NONUNIQUE, DELIBERATELY. All three legacy alternate indexes
--     were declared nonunique and upgraded synchronously with their base cluster, and a nonunique
--     B-tree is the faithful equivalent on both counts: PostgreSQL maintains it within the statement
--     that changes the row, and it constrains nothing. Promoting any of them to unique would reject
--     data the legacy system accepted - many cards share one account, many cross-reference rows
--     share one account, many transactions share one processing timestamp - and would break the
--     verified reference data rather than merely tighten the schema.
--
--  5. NO CASCADING BEHAVIOUR. None of the six constraints declares a cascade, set-null or
--     set-default action, so all six carry PostgreSQL's default NO ACTION semantics and refuse to
--     remove or re-key a parent row that still has children. No source contract authorizes
--     convenience behaviour that would silently propagate a change, and the record-at-a-time legacy
--     code had no such mechanism to reproduce.
--
--  6. ONLY THE THREE SOURCE-DERIVED INDEXES ARE EXPLICIT. No index is added merely because a column
--     participates in a foreign key. Of the six constraints, two are backed on their referencing
--     side by an index created here, two by a primary key V1 declared, and two are intentionally
--     unbacked: PostgreSQL never requires an index on the referencing side, the check it would
--     accelerate fires only when a parent row is removed or re-keyed, and no legacy performance
--     baseline exists against which a speculative index could be justified.
--
--  7. PRIMARY-KEY INDEXES ARE NOT REPEATED. PostgreSQL builds a unique index behind each of the
--     eleven primary keys V1 declares; restating any of them here would create a redundant
--     duplicate that costs write throughput and buys nothing.
--
--  8. ISOLATION CONTEXT - A STRICT IMPROVEMENT, NOT A REGRESSION. The legacy application file
--     definitions specified uncommitted read integrity, no recovery and no journaling, with
--     correctness resting on a locking update model plus each program's own before/after image
--     comparison. READ COMMITTED, the constraints below and the version columns V1 places on account
--     and card are together strictly stronger; the migrated business logic is unchanged.


-- #################################################################################################
-- SECTION 1 OF 2 - SECONDARY INDEXES. Exactly three, each nonunique, each a B-tree.
--
-- The legacy data layer carried three alternate indexes over three of its ten indexed base clusters,
-- and the mapping is one-for-one in both directions. The access method is stated explicitly on every
-- statement so the index type is part of the migration text and cannot drift with a server default.
-- #################################################################################################

-- -------------------------------------------------------------------------------------------------
-- INDEX 1 OF 3 - card, by account identifier.
--
-- Source           : app/jcl/CARDFILE.jcl, the alternate index over the card cluster, related to the
--                    base cluster through a path.
-- Source key       : width 11 at 0-based offset 16 within the 150-byte card record. Re-derived from
--                    the layout: the 16-byte card number occupies 0..15, so the account identifier
--                    begins at 16; widths 16 + 11 + 3 + 50 + 10 + 1 plus 59 filler bytes = 150.
-- Cardinality      : nonunique - many cards may be issued against one account, which is the point of
--                    the access path.
-- Serves           : card-by-account browse and lookup - the online path behind the seven-row card
--                    list and the card detail and update entry points reached from an account. The
--                    legacy resource definition registers this path as an online file in its own
--                    right, so it carries online traffic and not only batch.
-- Also serves      : the referencing side of fk_card_account, which is why it is created first.
-- -------------------------------------------------------------------------------------------------
CREATE INDEX idx_card_card_acct_id
    ON card USING btree (card_acct_id, card_num);

-- -------------------------------------------------------------------------------------------------
-- INDEX 2 OF 3 - card cross-reference, by account identifier.
--
-- Source           : app/jcl/XREFFILE.jcl, the alternate index over the cross-reference cluster.
-- Source key       : width 11 at 0-based offset 25 within the 50-byte record. Confirmed by the two
--                    preceding fields - a 16-byte card number at 0 and a 9-byte customer identifier
--                    at 16, and 16 + 9 = 25. The 36 mapped bytes plus 14 filler bytes = 50.
-- Cardinality      : nonunique - one account may be cross-referenced by more than one card.
-- Serves           : the resolution step that turns an account into the cards and customer bound to
--                    it. Registered as an online file too, so it is not batch-only.
-- Also serves      : the referencing side of fk_card_xref_account.
-- -------------------------------------------------------------------------------------------------
CREATE INDEX idx_card_cross_reference_xref_acct_id
    ON card_cross_reference USING btree (xref_acct_id, xref_card_num);

-- -------------------------------------------------------------------------------------------------
-- INDEX 3 OF 3 - transaction, by processing timestamp. BATCH ONLY.
--
-- Source           : app/jcl/TRANFILE.jcl and app/jcl/TRANIDX.jcl define an alternate index of THE
--                    SAME NAME over THE SAME cluster with THE SAME key width and offset. That is ONE
--                    LOGICAL INDEX DESCRIBED IN TWO MEMBERS, not two indexes, so it is emitted here
--                    EXACTLY ONCE - emitting it twice fails on a duplicate name, and renaming the
--                    second copy would leave a permanent redundant index behind.
-- Source key       : width 26 at 0-based offset 304 within the 350-byte transaction record. Prefix
--                    sums land exactly there: 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 = 262
--                    where the card number begins, + 16 = 278 origination timestamp, + 26 = 304
--                    processing timestamp, + 26 + 20 filler bytes = 350. The batch report's external
--                    sort addressed the same fields as 1-based 263 and 305, confirming 262 and 304
--                    independently.
-- Cardinality      : nonunique, emphatically - the verified daily input carries 300 records sharing
--                    one processing timestamp, so a unique index would reject the very fixture the
--                    end-to-end gate depends on.
-- Serves           : the batch transaction report's inclusive processing-date range filter, which
--                    would otherwise degrade to a full scan of the transaction master.
-- Batch only       : the legacy resource definition registers the card and cross-reference paths as
--                    online files and ZERO paths for this one - its transaction file entry addresses
--                    the base cluster directly - so no online request ever reached this index.
-- -------------------------------------------------------------------------------------------------
CREATE INDEX idx_transaction_tran_proc_ts
    ON transaction USING btree (tran_proc_ts);


-- #################################################################################################
-- SECTION 2 OF 2 - FOREIGN KEYS. Exactly six, and this set is exhaustive.
--
-- The legacy data layer enforced no referential integrity of its own: each cluster stood alone and
-- every cross-record relationship was checked, or not checked, by program logic. The six below move
-- the checks the programs genuinely make into the database, where they cannot be skipped. They do
-- NOT invent relationships the programs never checked; the three decisions after this section state
-- which candidate relationships were rejected and why each rejection is required.
--
-- Each references a PRIMARY KEY of its parent, so each resolves against a constraint PostgreSQL
-- already knows to be unique, and each referencing column has exactly the type and width V1 gave
-- that key. No referential action is declared, so all six carry default NO ACTION semantics.
-- #################################################################################################

-- -------------------------------------------------------------------------------------------------
-- FOREIGN KEY 1 OF 6 - every card belongs to an existing account.
-- card.card_acct_id (offset 16) -> account.acct_id. The relationship index 1 traverses and the one
-- the online card list, detail and update paths assume. Referencing side backed by
-- idx_card_card_acct_id.
-- -------------------------------------------------------------------------------------------------
ALTER TABLE card
    ADD CONSTRAINT fk_card_account
    FOREIGN KEY (card_acct_id) REFERENCES account (acct_id);

-- -------------------------------------------------------------------------------------------------
-- FOREIGN KEY 2 OF 6 - every cross-reference row describes an existing card.
-- card_cross_reference.xref_card_num (offset 0) -> card.card_num. This column is simultaneously the
-- cross-reference table's own primary key, so the referencing side is already backed by the primary
-- key index V1 built and no additional index is added.
-- -------------------------------------------------------------------------------------------------
ALTER TABLE card_cross_reference
    ADD CONSTRAINT fk_card_xref_card
    FOREIGN KEY (xref_card_num) REFERENCES card (card_num);

-- -------------------------------------------------------------------------------------------------
-- FOREIGN KEY 3 OF 6 - every cross-reference row names an existing account.
-- card_cross_reference.xref_acct_id (offset 25) -> account.acct_id. This is the hop the posting job
-- takes first: read the cross-reference by card number, then read the account by the identifier
-- found here. Referencing side backed by idx_card_cross_reference_xref_acct_id.
-- -------------------------------------------------------------------------------------------------
ALTER TABLE card_cross_reference
    ADD CONSTRAINT fk_card_xref_account
    FOREIGN KEY (xref_acct_id) REFERENCES account (acct_id);

-- -------------------------------------------------------------------------------------------------
-- FOREIGN KEY 4 OF 6 - every cross-reference row names an existing customer.
-- card_cross_reference.xref_cust_id (offset 16) -> customer.cust_id. The join that reaches customer
-- detail from a card or an account. Referencing side deliberately unbacked, per global rule 6.
-- -------------------------------------------------------------------------------------------------
ALTER TABLE card_cross_reference
    ADD CONSTRAINT fk_card_xref_customer
    FOREIGN KEY (xref_cust_id) REFERENCES customer (cust_id);

-- -------------------------------------------------------------------------------------------------
-- FOREIGN KEY 5 OF 6 - every POSTED transaction carries an existing card number.
-- transaction.tran_card_num (offset 262) -> card.card_num. Placed on the VALIDATED master only,
-- never on the raw landing table - see DECISION A below. A row reaches this table only after the
-- posting job has resolved its card through the cross-reference, so the constraint makes a guarantee
-- that is already procedural structural instead. Referencing side deliberately unbacked, per global
-- rule 6. Note that the only explicit index on this table keys the PROCESSING TIMESTAMP at offset
-- 304, not this column at offset 262: the legacy estate defined an alternate index on the former and
-- none on the latter, and that asymmetry is reproduced rather than smoothed over.
-- -------------------------------------------------------------------------------------------------
ALTER TABLE transaction
    ADD CONSTRAINT fk_transaction_card
    FOREIGN KEY (tran_card_num) REFERENCES card (card_num);

-- -------------------------------------------------------------------------------------------------
-- FOREIGN KEY 6 OF 6 - every category balance belongs to an existing account.
-- transaction_category_balance.trancat_acct_id (offset 0) -> account.acct_id. The category balance is
-- keyed by a three-part composite and this constraint governs the FIRST part only, which is legitimate
-- because the PARENT side is a single-column primary key. No new index is needed: this column leads
-- that table's own composite primary key. Constraining it keeps an interest run from computing
-- against an orphaned balance.
-- -------------------------------------------------------------------------------------------------
ALTER TABLE transaction_category_balance
    ADD CONSTRAINT fk_trancat_balance_account
    FOREIGN KEY (trancat_acct_id) REFERENCES account (acct_id);


-- #################################################################################################
-- DECISIONS NOT TO CONSTRAIN. Three relationships a reader will expect above are absent on purpose,
-- each proved from the source. Every one of the three would break something that currently works.
-- #################################################################################################

-- -------------------------------------------------------------------------------------------------
-- DECISION A - daily_transaction HAS ZERO FOREIGN KEYS, AND MUST KEEP ZERO.
--
-- daily_transaction is the RAW, UNVALIDATED LANDING SURFACE for the sequential daily input. Rows
-- arrive exactly as the upstream file presents them, and the posting job - not the database - decides
-- whether each is acceptable. That job deliberately accepts a row whose lookup fails and turns it
-- into a rejection outcome:
--
--   * reason code 100 - the card cross-reference lookup found no match for the row's card number;
--   * reason code 101 - the account read found no match for the identifier the cross-reference gave;
--   * reason code 109 - the account rewrite found no match at the end of posting. This code carries
--     the SAME description text as 101 yet is a DISTINCT CODE, raised on a different operation at a
--     different point in the run. The two must never be folded together: the four-digit code is what
--     the reject record carries, and a reader distinguishes a failed read from a failed rewrite by
--     the code alone.
--
-- A foreign key here would refuse precisely the rows that exercise those paths. They would never be
-- stored, the codes would become unreachable, and the REJECT RECORD - a contractual output, 430 bytes
-- = the 350-byte source image plus an 80-byte trailer of a 4-digit reason code and a 76-character
-- description - could not be produced, so the byte-parity gate could not be run. It is not 500 bytes;
-- no 500-byte reject layout exists in the estate.
--
-- Seeded daily rows happen to reference cards that exist, which is why this absence must be stated
-- rather than inferred from the data: tests MUST be able to construct a landing row whose card or
-- account reference does not resolve, and can only do so while this table is unconstrained.
-- -------------------------------------------------------------------------------------------------

-- -------------------------------------------------------------------------------------------------
-- DECISION B - account.acct_group_id DOES NOT REFERENCE disclosure_group, AND CANNOT.
--
-- disclosure_group has a three-column composite primary key - group identifier at offset 0 width 10,
-- type code at offset 10 width 2, category code at offset 12 width 4 - and the account's group
-- identifier corresponds to the FIRST COMPONENT ONLY. That component is NOT UNIQUE within
-- disclosure_group: it recurs once per type-and-category combination, seventeen times per group in
-- the verified reference data. A PostgreSQL foreign key must reference columns covered by a unique
-- constraint, so this reference is not expressible - and must not be made expressible by adding a
-- uniqueness rule, which would forbid the seventeen-row groups the rate table is built from.
--
-- The relationship is resolved where the source resolves it: the interest calculation looks up the
-- rate by the full three-part key and FALLS BACK TO THE DEFAULT GROUP on a not-found status. That
-- fallback is a real, exercised branch reachable from the seeded reference data, and a constraint on
-- the group identifier would make it unreachable by rejecting the very condition it handles.
-- -------------------------------------------------------------------------------------------------

-- -------------------------------------------------------------------------------------------------
-- DECISION C - NO REFERENCE-DATA FOREIGN KEYS. The six above are exhaustive.
--
-- No constraint links transaction, daily_transaction or transaction_category_balance to
-- transaction_type or transaction_category, and none is invented toward disclosure_group either.
-- The source treats type and category codes as classification lexemes carried on the record,
-- validated - where at all - by program logic, and the reference tables exist to supply a description
-- for display and reporting rather than to gate what may be stored. Constraining them would change
-- which rows the system accepts and would break the same rejection paths DECISION A protects,
-- because the landing table carries the codes too. Any seventh constraint is feature expansion.
-- -------------------------------------------------------------------------------------------------


-- End of V2. Nine statements applied: three nonunique B-tree secondary indexes -
-- idx_card_card_acct_id (width 11 at offset 16), idx_card_cross_reference_xref_acct_id (width 11 at
-- offset 25) and idx_transaction_tran_proc_ts (width 26 at offset 304, batch only, one logical index
-- described in two source members and emitted once); and six foreign keys: fk_card_account,
-- fk_card_xref_card, fk_card_xref_account, fk_card_xref_customer, fk_transaction_card and
-- fk_trancat_balance_account, all with default NO ACTION semantics.
--
-- Zero tables of any kind, zero schemas, views, routines, triggers, extensions or rows were created;
-- the eleven tables V1 defines remain the whole of the schema. Zero foreign keys touch
-- daily_transaction, zero use account.acct_group_id, and zero target a reference-data table. Nothing
-- V1 defined was altered.
