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

-- V1__create_schema.sql - foundational application schema.
--
-- Creates exactly eleven application tables and nothing else: the relational realization of the
-- eleven verified fixed-width record layouts of the legacy estate (ten indexed base clusters plus
-- the sequential daily-transaction input). Each primary key is the natural business key taken from
-- the corresponding cluster key definition, whose width and offset are recorded per table below.
--
-- APPLIES TO ALL PROFILES. V1 is resolved from classpath:db/migration, the only Flyway location
-- configured on the base profile, so no profile can start without it. Sample rows and sign-on
-- identities belong to a separate, profile-scoped classpath:db/seed location that production does
-- not list, so a production migration inherits schema and indexes and nothing else. This file
-- inserts no row of any kind.
--
-- Forward-only and in order: no schema.sql, no data.sql, no framework SQL initialization, no
-- container init mount, no repeatable migration and no undo migration exists in this module.
--
-- Validated against PostgreSQL 16.14 initialized --encoding=UTF8 --locale=C.UTF-8, so ordering
-- cannot drift between hosts. Flyway 11 no longer bundles database support, so the module declares
-- flyway-database-postgresql alongside flyway-core; without that separate artifact this migration
-- resolves no dialect and does not run. The DDL below is driver-version independent.
--
-- Translation decisions and the source anomalies cited below are recorded in docs/decision-log.md.
-- No legacy source text is copied here: only names, widths, offsets, record sizes, counts and
-- anomaly descriptions appear.

-- GLOBAL SCHEMA RULES - each is a deliberate decision, not an omission.
--
--  1. Eleven tables exactly, all lower case: account, card, customer, card_cross_reference,
--     transaction, daily_transaction, transaction_category_balance, disclosure_group,
--     transaction_type, transaction_category, user_security.
--
--  2. No existence guard on any statement, so schema drift fails this migration loudly instead of
--     being silently absorbed.
--
--  3. NOTHING ELSE IS CREATED - no twelfth table, no additional schema, no sequence, no surrogate
--     key column, no view, no routine, no trigger, no extension and no lookup table. The
--     transaction-report copybook is print formatting, not a table. The alternate 500-byte customer
--     copybook restates the same nineteen fields at the same offsets under one differently spelled
--     date field name, so it is an alternate projection of customer and not a second table. Spring
--     Batch owns its own metadata tables and provisions them from its bundled schema. The validation
--     lookup data - 490 North American area codes as an exact partition of 410 general-purpose plus
--     80 easily-recognizable, 56 state codes and 240 state-with-ZIP-prefix combinations - is loaded
--     from src/main/resources/lookup/*.json.
--
--  4. Primary keys are the natural business keys, exactly as the legacy cluster key definitions
--     state them. No number-issuing database object is created: the online transaction identifier
--     stays highest-existing-key-plus-one computed inside the posting transaction, because a
--     database-issued number diverges permanently after the first gap and a rollback guarantees one.
--
--  5. V1 defines primary keys only. The authoritative foreign-key set and the three nonunique
--     indexes that stand in for the legacy alternate indexes belong to V2__create_indexes.sql.
--
--  6. Bounded VARCHAR(n) for every fixed-width alphanumeric and digit-only lexeme, because leading
--     zeros and external text widths are contractual and must survive a round trip. A blank-padded
--     fixed-length type is deliberately not used: its implicit padding and padded comparison can
--     hide a distinction the record image makes. Date and timestamp record fields stay bounded
--     strings - parsing and strict calendar validation belong in Java, and the raw daily processing
--     timestamp is legitimately blank on input.
--
--  7. Every amount and rate column is exact NUMERIC(p,2), sized from its record field: five account
--     amounts NUMERIC(12,2); transaction amount, daily-transaction amount and category balance
--     NUMERIC(11,2); disclosure interest rate NUMERIC(6,2). An estate census found zero rounding
--     clauses, so every store into a two-decimal field truncates toward zero and Java scales with
--     RoundingMode.DOWN; an approximate binary type would break byte parity of the fixed-width
--     output at 80, 100, 133 and 430 bytes.
--
--  8. Every mapped fixed-width field is NOT NULL, with the single intentional exception of
--     customer.cust_ssn, documented at that table. No filler column is created: trailing filler
--     carries no information and is reconstructed on output from the declared record width.
--
--  9. account and card each carry a version column for JPA optimistic locking; the legacy baseline
--     is documented at those tables.
--
-- 10. The unsafe and low-level code audit - raw SQL string concatenation, process invocation,
--     reflection, unchecked casts, suppressed warnings - is scoped to src/main/java/** only. The
--     versioned SQL artifacts of this module are declarative schema definitions, not application
--     code assembling SQL from strings; without that scoping rule an auditor would report phantom
--     raw-SQL violations that are in fact the versioned schema this module requires.


-- -------------------------------------------------------------------------------------------------
-- account - 300-byte record, key width 11 at offset 0.
--
-- Column order mirrors the record layout, and the three date fields SPLIT the monetary fields
-- three-before / two-after: the five amounts are NOT contiguous, and a mapper that assumes
-- otherwise misreads every account from offset 48 onward.
--
-- ANOMALY: the legacy field at offset 58 is misspelled - the source name drops a letter from
-- EXPIRATION. The correctly spelled column acct_expiration_date is used while the mapper keeps
-- reading offset 58 for width 10, so the record image stays byte-compatible and only the Java-side
-- spelling is corrected.
--
-- OPTIMISTIC LOCKING: version backs a JPA @Version check replacing the legacy before-and-after
-- image comparison. Every online file definition in the legacy resource definition specified
-- uncommitted read integrity, locking update model, no recovery and no journaling, so correctness
-- rested solely on that comparison. READ COMMITTED plus this column is STRICTLY STRONGER than the
-- verified baseline - an improvement, not a behavioral regression.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE account (
    acct_id                     VARCHAR(11)     NOT NULL,   -- offset   0, width 11, business key
    acct_active_status          VARCHAR(1)      NOT NULL,   -- offset  11, width  1
    acct_curr_bal               NUMERIC(12,2)   NOT NULL,   -- offset  12, width 12 = 10 int + 2 dec
    acct_credit_limit           NUMERIC(12,2)   NOT NULL,   -- offset  24, width 12 = 10 int + 2 dec
    acct_cash_credit_limit      NUMERIC(12,2)   NOT NULL,   -- offset  36, width 12 = 10 int + 2 dec
    acct_open_date              VARCHAR(10)     NOT NULL,   -- offset  48, width 10
    acct_expiration_date        VARCHAR(10)     NOT NULL,   -- offset  58, width 10, see ANOMALY
    acct_reissue_date           VARCHAR(10)     NOT NULL,   -- offset  68, width 10
    acct_curr_cyc_credit        NUMERIC(12,2)   NOT NULL,   -- offset  78, width 12 = 10 int + 2 dec
    acct_curr_cyc_debit         NUMERIC(12,2)   NOT NULL,   -- offset  90, width 12 = 10 int + 2 dec
    acct_addr_zip               VARCHAR(10)     NOT NULL,   -- offset 102, width 10
    acct_group_id               VARCHAR(10)     NOT NULL,   -- offset 112, width 10
    version                     BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_account PRIMARY KEY (acct_id)
);
-- Mapped bytes end at offset 122; the remaining 178 bytes are trailing filler and are not columns.


-- -------------------------------------------------------------------------------------------------
-- card - 150-byte record, key width 16 at offset 0.
--
-- ANOMALY: the expiry field at offset 80 is misspelled in the same way; the corrected spelling
-- card_expiration_date is used and the mapper position is unchanged.
--
-- PRIMARY ACCOUNT NUMBER AND VERIFICATION CODE: the legacy design applies no field-level
-- encryption, tokenization or masking to either value, and no requirement in scope introduces one.
-- None is invented here, because that would be feature expansion. The gap is UNCLOSED and is carried
-- forward as an explicit finding: decision D-14.
--
-- version: same optimistic-locking rationale as account.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE card (
    card_num                    VARCHAR(16)     NOT NULL,   -- offset   0, width 16, business key
    card_acct_id                VARCHAR(11)     NOT NULL,   -- offset  16, width 11
    card_cvv_cd                 VARCHAR(3)      NOT NULL,   -- offset  27, width  3
    card_embossed_name          VARCHAR(50)     NOT NULL,   -- offset  30, width 50
    card_expiration_date        VARCHAR(10)     NOT NULL,   -- offset  80, width 10, see ANOMALY
    card_active_status          VARCHAR(1)      NOT NULL,   -- offset  90, width  1
    version                     BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_card PRIMARY KEY (card_num)
);
-- Mapped bytes end at offset 91; the remaining 59 bytes are trailing filler and are not columns.


-- -------------------------------------------------------------------------------------------------
-- customer - 500-byte record, key width 9 at offset 0.
--
-- Mapped offsets, in column order: 0, 9, 34, 59, 84, 134, 184, 234, 236, 239, 249, 264, 279, 288,
-- 308, 318, 328, 329. Mapped bytes end at offset 332, where 168 bytes of trailing filler begin.
--
-- cust_ssn and govt_issued_id - DOCUMENTED SECURITY EXCEPTION, not unmapped fields.
--   The legacy record holds the national identifier as nine cleartext digits at offset 279 and the
--   government-issued identifier as twenty cleartext characters at offset 288. Both are regulated
--   identity data and neither is persisted in that form here. Both columns are therefore deliberately
--   far wider than their legacy widths, because each stores an application-produced authenticated
--   ciphertext envelope rather than the cleartext value.
--
--   HOW THE PROHIBITION IS ENFORCED, rather than merely asserted:
--     * com.carddemo.util.SensitiveFieldCodec produces and reads the envelope. It is AES-256 in
--       Galois/Counter Mode, so the stored value is authenticated as well as encrypted and a single
--       altered byte makes a read fail loudly instead of yielding corrupted cleartext. The envelope is
--       versioned - it opens with the marker ENC1: - and self-describing, carrying its own 96-bit
--       initialisation vector, so no side table and no schema change is needed to read it back or to
--       introduce a successor scheme beside it.
--     * com.carddemo.service.SensitiveFieldEncryptionService holds the key. The key is bound from
--       carddemo.security.field-encryption.key as Base64 and must decode to exactly 32 bytes. No
--       default is declared in the shared configuration; the local and test overlays bind a throwaway
--       development value and the production overlay binds an environment reference with NO fallback,
--       so a deployment that omits it fails to start rather than encrypting under something
--       accidental. No key material appears in this schema, in any migration, or in any source file.
--     * com.carddemo.domain.Customer refuses. Its constructor and both mutators admit only a value
--       carrying the envelope shape - and, for cust_ssn alone, NULL - so cleartext cannot reach this
--       boundary through application code. Nine cleartext digits cannot satisfy that shape.
--
--   Encryption is randomised, so these columns cannot be searched by equality. That costs this estate
--   nothing: the legacy design defines no alternate index, no browse and no screen lookup over either
--   identifier, so no access path is lost. The divergence from at-rest faithfulness is recorded in
--   docs/decision-log.md; it changes no record image and no output byte.
--
--   cust_ssn is the one intentional nullable field in V1 because V3__seed_reference_data.sql leaves it
--   NULL in static SQL rather than embedding raw national identifiers, or a hardcoded encryption key,
--   in a checked-in artifact. govt_issued_id is NOT NULL, so any row a seed migration inserts must
--   carry an envelope produced under the deployment's own key - never a cleartext identifier.
--
-- middle_name and addr_line_2 are mapped and stored but must NOT be validated anywhere downstream:
-- the legacy update path decorates them for error display while coding no edit for either, so a
-- constraint here would reject input the legacy system accepts.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE customer (
    cust_id                     VARCHAR(9)      NOT NULL,   -- offset   0, width  9, business key
    first_name                  VARCHAR(25)     NOT NULL,   -- offset   9, width 25
    middle_name                 VARCHAR(25)     NOT NULL,   -- offset  34, width 25, no edit coded
    last_name                   VARCHAR(25)     NOT NULL,   -- offset  59, width 25
    addr_line_1                 VARCHAR(50)     NOT NULL,   -- offset  84, width 50
    addr_line_2                 VARCHAR(50)     NOT NULL,   -- offset 134, width 50, no edit coded
    addr_line_3                 VARCHAR(50)     NOT NULL,   -- offset 184, width 50
    addr_state_cd               VARCHAR(2)      NOT NULL,   -- offset 234, width  2
    addr_country_cd             VARCHAR(3)      NOT NULL,   -- offset 236, width  3
    addr_zip                    VARCHAR(10)     NOT NULL,   -- offset 239, width 10
    phone_num_1                 VARCHAR(15)     NOT NULL,   -- offset 249, width 15
    phone_num_2                 VARCHAR(15)     NOT NULL,   -- offset 264, width 15
    cust_ssn                    VARCHAR(255)    NULL,       -- offset 279, width  9 cleartext in the
                                                            -- record; ciphertext here, see above
    govt_issued_id              VARCHAR(255)    NOT NULL,   -- offset 288, width 20 cleartext in the
                                                            -- record; ciphertext here, see above
    cust_dob                    VARCHAR(10)     NOT NULL,   -- offset 308, width 10
    eft_account_id              VARCHAR(10)     NOT NULL,   -- offset 318, width 10
    pri_card_holder_ind         VARCHAR(1)      NOT NULL,   -- offset 328, width  1
    fico_credit_score           VARCHAR(3)      NOT NULL,   -- offset 329, width  3
    CONSTRAINT pk_customer PRIMARY KEY (cust_id)
);
-- Mapped bytes end at offset 332; the remaining 168 bytes are trailing filler and are not columns.


-- -------------------------------------------------------------------------------------------------
-- card_cross_reference - 50-byte physical record carrying only 36 data bytes, key width 16 at
-- offset 0. Every card lookup traverses this card-to-customer-to-account resolution table.
--
-- The trailing 14 bytes are filler and deliberately not a column. That 36-versus-50 split is why
-- the sample ASCII fixture measures 1,850 bytes for 50 newline-terminated rows - 50 x (36 + 1) -
-- while the fixed-length 50-byte dataset of the same 50 records measures 2,500. Both are correct;
-- a loader expecting 50 data bytes per ASCII row misparses every record.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE card_cross_reference (
    xref_card_num               VARCHAR(16)     NOT NULL,   -- offset   0, width 16, business key
    xref_cust_id                VARCHAR(9)      NOT NULL,   -- offset  16, width  9
    xref_acct_id                VARCHAR(11)     NOT NULL,   -- offset  25, width 11
    CONSTRAINT pk_card_cross_reference PRIMARY KEY (xref_card_num)
);
-- Mapped bytes end at offset 36; the remaining 14 bytes are trailing filler and are not columns.


-- -------------------------------------------------------------------------------------------------
-- transaction - 350-byte record, key width 16 at offset 0. Posted transaction master.
--
-- The table name is required verbatim by the target contract. TRANSACTION is a non-reserved key word
-- in PostgreSQL 16, so the unquoted identifier is legal and this migration applying cleanly is the
-- proof; it is used unquoted everywhere so no part of the module has to remember to quote it.
--
-- Two offsets are load-bearing beyond this table: tran_card_num at offset 262 and tran_proc_ts at
-- offset 304 are the fields the legacy report sort addressed (1-based positions 263 and 305) and the
-- timestamp alternate index keyed (width 26 at offset 304).
-- -------------------------------------------------------------------------------------------------
CREATE TABLE transaction (
    tran_id                     VARCHAR(16)     NOT NULL,   -- offset   0, width 16, business key
    tran_type_cd                VARCHAR(2)      NOT NULL,   -- offset  16, width  2
    tran_cat_cd                 VARCHAR(4)      NOT NULL,   -- offset  18, width  4
    tran_source                 VARCHAR(10)     NOT NULL,   -- offset  22, width 10
    tran_desc                   VARCHAR(100)    NOT NULL,   -- offset  32, width 100
    tran_amt                    NUMERIC(11,2)   NOT NULL,   -- offset 132, width 11 = 9 int + 2 dec
    merchant_id                 VARCHAR(9)      NOT NULL,   -- offset 143, width  9
    merchant_name               VARCHAR(50)     NOT NULL,   -- offset 152, width 50
    merchant_city               VARCHAR(50)     NOT NULL,   -- offset 202, width 50
    merchant_zip                VARCHAR(10)     NOT NULL,   -- offset 252, width 10
    tran_card_num               VARCHAR(16)     NOT NULL,   -- offset 262, width 16, sort key field
    tran_orig_ts                VARCHAR(26)     NOT NULL,   -- offset 278, width 26
    tran_proc_ts                VARCHAR(26)     NOT NULL,   -- offset 304, width 26, index key field
    CONSTRAINT pk_transaction PRIMARY KEY (tran_id)
);
-- Mapped bytes end at offset 330; the remaining 20 bytes are trailing filler and are not columns.


-- -------------------------------------------------------------------------------------------------
-- daily_transaction - 350-byte record, byte-for-byte the same shape as transaction under its own
-- field-name prefix. Business key width 16 at offset 0.
--
-- A SEPARATE table on purpose: it is the raw landing surface for the sequential daily input and has
-- a distinct lifecycle. Rows arrive unvalidated, the posting job validates each one, and a failure
-- produces a 430-byte reject record - the 350-byte source image plus an 80-byte trailer of a 4-digit
-- reason code and a 76-character description. V2 therefore gives this table NO foreign key:
-- constraining it would reject invalid input at the database boundary, so the input would never
-- reach application validation and the reject file - a contractual output - could never be produced.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE daily_transaction (
    dalytran_id                 VARCHAR(16)     NOT NULL,   -- offset   0, width 16, business key
    dalytran_type_cd            VARCHAR(2)      NOT NULL,   -- offset  16, width  2
    dalytran_cat_cd             VARCHAR(4)      NOT NULL,   -- offset  18, width  4
    dalytran_source             VARCHAR(10)     NOT NULL,   -- offset  22, width 10
    dalytran_desc               VARCHAR(100)    NOT NULL,   -- offset  32, width 100
    dalytran_amt                NUMERIC(11,2)   NOT NULL,   -- offset 132, width 11 = 9 int + 2 dec
    dalytran_merchant_id        VARCHAR(9)      NOT NULL,   -- offset 143, width  9
    dalytran_merchant_name      VARCHAR(50)     NOT NULL,   -- offset 152, width 50
    dalytran_merchant_city      VARCHAR(50)     NOT NULL,   -- offset 202, width 50
    dalytran_merchant_zip       VARCHAR(10)     NOT NULL,   -- offset 252, width 10
    dalytran_card_num           VARCHAR(16)     NOT NULL,   -- offset 262, width 16
    dalytran_orig_ts            VARCHAR(26)     NOT NULL,   -- offset 278, width 26
    dalytran_proc_ts            VARCHAR(26)     NOT NULL,   -- offset 304, width 26, blank on input
    CONSTRAINT pk_daily_transaction PRIMARY KEY (dalytran_id)
);
-- Mapped bytes end at offset 330; the remaining 20 bytes are trailing filler and are not columns.


-- -------------------------------------------------------------------------------------------------
-- transaction_category_balance - 50-byte record, composite key of width 17 at offset 0: account
-- identifier (offset 0, width 11) + type code (offset 11, width 2) + category code (offset 13,
-- width 4), which is exactly the cluster key width. This is the per-account, per-category balance
-- the interest run multiplies by the disclosure rate.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE transaction_category_balance (
    trancat_acct_id             VARCHAR(11)     NOT NULL,   -- offset   0, width 11, key part 1
    trancat_type_cd             VARCHAR(2)      NOT NULL,   -- offset  11, width  2, key part 2
    trancat_cd                  VARCHAR(4)      NOT NULL,   -- offset  13, width  4, key part 3
    tran_cat_bal                NUMERIC(11,2)   NOT NULL,   -- offset  17, width 11 = 9 int + 2 dec
    CONSTRAINT pk_transaction_category_balance
        PRIMARY KEY (trancat_acct_id, trancat_type_cd, trancat_cd)
);
-- Mapped bytes end at offset 28; the remaining 22 bytes are trailing filler and are not columns.


-- -------------------------------------------------------------------------------------------------
-- disclosure_group - 50-byte record, composite key of width 16 at offset 0: account group identifier
-- (offset 0, width 10) + type code (offset 10, width 2) + category code (offset 12, width 4).
--
-- GROUP IDENTIFIERS ARE FIXED 10-CHARACTER VALUES and the reference data carries meaningful TRAILING
-- SPACES inside that width. Those spaces are part of the key: trimming them changes the key and
-- breaks the rate lookup, which is one more reason this column is bounded variable-length rather
-- than blank-padded.
--
-- NO FOREIGN KEY is defined from account.acct_group_id to this table, in V1 or V2. The group
-- identifier alone is a nonunique prefix of this composite key - it recurs once per type/category
-- combination, seventeen times per group in the verified reference data - so it cannot reference this
-- key, and promoting it to one would fabricate a constraint the source never had.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE disclosure_group (
    dis_acct_group_id           VARCHAR(10)     NOT NULL,   -- offset   0, width 10, key part 1
    dis_tran_type_cd            VARCHAR(2)      NOT NULL,   -- offset  10, width  2, key part 2
    dis_tran_cat_cd             VARCHAR(4)      NOT NULL,   -- offset  12, width  4, key part 3
    dis_int_rate                NUMERIC(6,2)    NOT NULL,   -- offset  16, width  6 = 4 int + 2 dec
    CONSTRAINT pk_disclosure_group
        PRIMARY KEY (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd)
);
-- Mapped bytes end at offset 22; the remaining 28 bytes are trailing filler and are not columns.


-- -------------------------------------------------------------------------------------------------
-- transaction_type - 60-byte record, key width 2 at offset 0. Reference table, 7 rows when seeded.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE transaction_type (
    tran_type                   VARCHAR(2)      NOT NULL,   -- offset   0, width  2, business key
    tran_type_desc              VARCHAR(50)     NOT NULL,   -- offset   2, width 50
    CONSTRAINT pk_transaction_type PRIMARY KEY (tran_type)
);
-- Mapped bytes end at offset 52; the remaining 8 bytes are trailing filler and are not a column.


-- -------------------------------------------------------------------------------------------------
-- transaction_category - 60-byte record, composite key of width 6 at offset 0. Reference table,
-- 18 rows when seeded.
--
-- CAUTION: this 6-byte composite key - type code (offset 0, width 2) plus category code (offset 2,
-- width 4) - is a DIFFERENT key from the 17-byte composite key of transaction_category_balance, even
-- though the two legacy structures name their key group identically. This one has two parts and no
-- account identifier; that one has three parts and leads with the account identifier. Never reuse,
-- share or conflate them, here, in the entity identifier classes or in any query.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE transaction_category (
    tran_type_cd                VARCHAR(2)      NOT NULL,   -- offset   0, width  2, key part 1
    tran_cat_cd                 VARCHAR(4)      NOT NULL,   -- offset   2, width  4, key part 2
    tran_cat_type_desc          VARCHAR(50)     NOT NULL,   -- offset   6, width 50
    CONSTRAINT pk_transaction_category PRIMARY KEY (tran_type_cd, tran_cat_cd)
);
-- Mapped bytes end at offset 56; the remaining 4 bytes are trailing filler and are not a column.


-- -------------------------------------------------------------------------------------------------
-- user_security - 80-byte record, key width 8 at offset 0. Sign-on identities and role source.
--
-- sec_usr_pwd - DOCUMENTED PARITY EXCEPTION, recorded as decision D-12.
--   The legacy record stores the credential as eight cleartext characters at offset 48 and the
--   sign-on path compares it directly against the entered value. Reproducing cleartext storage would
--   satisfy parity and violate the no-hardcoded-credentials constraint at the same time, so this
--   column is sized 60 to hold a BCrypt digest - never the legacy width of 8 and never a cleartext
--   value. The digest format is what this schema fixes; the encoder and the verifying sign-on path
--   are the obligation of whatever component later reads this table, and no component may store or
--   compare a cleartext credential.
--
-- sec_usr_type is the single character that selects the administrative or standard role and is the
-- sole authority for that split.
--
-- NO ROWS ARE INSERTED HERE and no credential literal appears in this file, in DDL or in comment.
-- Any seed script belongs under db/seed, profile-scoped to local and test, so no production
-- deployment receives a seeded login.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE user_security (
    sec_usr_id                  VARCHAR(8)      NOT NULL,   -- offset   0, width  8, business key
    sec_usr_fname               VARCHAR(20)     NOT NULL,   -- offset   8, width 20
    sec_usr_lname               VARCHAR(20)     NOT NULL,   -- offset  28, width 20
    sec_usr_pwd                 VARCHAR(60)     NOT NULL,   -- offset  48, width  8 in the record;
                                                            -- 60 here for a digest, see above
    sec_usr_type                VARCHAR(1)      NOT NULL,   -- offset  56, width  1
    CONSTRAINT pk_user_security PRIMARY KEY (sec_usr_id)
);
-- Mapped bytes end at offset 57; the remaining 23 bytes are trailing filler and are not a column.


-- End of V1. Eleven application tables, eleven primary keys - eight single column and three
-- composite (transaction_category_balance, disclosure_group, transaction_category). Zero foreign
-- keys, zero explicit indexes, zero other persistent objects. V2__create_indexes.sql adds the
-- foreign-key set and the three nonunique indexes that replace the legacy alternate indexes on card
-- account identifier, cross-reference account identifier and transaction processing timestamp.
