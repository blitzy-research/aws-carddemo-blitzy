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

-- V3__seed_reference_data.sql - reference fixture seed for local and test execution only.
--
-- Loads the nine measured ASCII reference datasets of the legacy estate into the schema created by
-- V1__create_schema.sql and constrained by V2__create_indexes.sql. Exactly 626 rows across exactly
-- nine tables, and nothing else. This file lives in db/migration/seed, a location that ONLY the local
-- and test profiles resolve and that production is refused outright; the schema scripts live beside it
-- in db/migration/schema, which every profile resolves. Production ALSO enforces
-- spring.flyway.target=2, so the location and the ceiling are belt and braces rather than alternatives.
-- Do not place a script in the shared parent db/migration: a location is scanned recursively, so a
-- script there - or a profile that listed the parent - would reach across the split. See PROFILE
-- APPLICABILITY below:
--
--     customer                      50      account                       50
--     card                          50      card_cross_reference          50
--     transaction_type               7      transaction_category          18
--     disclosure_group              51      transaction_category_balance  50
--     daily_transaction            300      ---------------------------------
--                                           total                        626
--
-- -------------------------------------------------------------------------------------------------
-- PROFILE APPLICABILITY - LOCAL AND TEST ONLY. NEVER PRODUCTION.
-- -------------------------------------------------------------------------------------------------
-- These rows are sample fixtures shaped like personal data: names, street addresses, telephone
-- numbers, dates of birth, government-issued identifiers, card numbers and card verification codes.
-- They exist so that the eight validation gates can be executed locally against a real database with
-- no mainframe and no production system in the loop. They are deliberately unavailable to production.
--
-- HOW THAT IS ENFORCED - A PROFILE-SCOPED LOCATION FIRST, A VERSION CEILING BEHIND IT, AND REFUSALS
-- IN CODE BEHIND BOTH.
--
--   The separation is by LOCATION, which is what AAP 0.3.1 and 0.4.2 require - "FlywayConfig resolves
--   V3 and V4 from profile-scoped locations so a production deployment migrates schema and indexes
--   without inheriting sample data or seeded credentials" - and the version ceiling is retained behind
--   it:
--
--       classpath:db/migration/schema  -> resolved by EVERY profile
--           V1__create_schema.sql             \
--           V2__create_indexes.sql             >  APPLIED everywhere
--       -------------------------------------------
--       classpath:db/migration/seed    -> resolved by LOCAL and TEST ONLY; production is REFUSED it
--           V3__seed_reference_data.sql       \  not resolved under production, therefore never
--           V4__seed_user_security.sql         >  reported and never applied there
--       ------------------------------------------- and, behind that, spring.flyway.target: 2
--                                                   (application.yml, application-prod.yml)
--
--   The shared parent db/migration holds NO script. That is what makes the location list a boundary at
--   all: a location is scanned recursively, so a parent listing would reach the seeds through the child
--   directory. FlywayConfig therefore refuses the parent under production exactly as it refuses the seed
--   location itself.
--
--   application.yml carries target 2 as the SHARED DEFAULT, so a profile that stays silent inherits a
--   schema-only migration rather than an unnoticed seeding run, and application-prod.yml re-states it so
--   the production posture is legible in the file that governs it. The local and test overlays raise the
--   ceiling to latest, so they apply all four. Verified against Flyway 11.7.2: with target 2 the applied
--   set is exactly {1, 2}, versions 3 and 4 are reported above target, and the migration still validates
--   successfully - so a pending above-target script is not a condition a deployment has to suppress.
--
--   WHY BOTH, AND WHY THE LOCATION COMES FIRST. A script that is never resolved cannot be applied by
--   any ceiling, and a location a profile never listed is not a value an operator can widen by mistake -
--   whereas a ceiling is exactly that. A location is scanned recursively, so the split holds only while
--   the shared parent stays empty; with the parent empty, the recursion has nothing to cross. The ceiling
--   is kept behind the location because it is the control that still holds if a seed is ever renumbered
--   or a seed location is ever added back by an override. See docs/decision-log.md DL-127.
--
--   WHAT MUST NOT CHANGE. Do not move this file out of db/migration/seed, do not place any script in the
--   shared parent, do not add the seed location to the base or production profile, do not raise or remove
--   target on the base or production profile, and do not renumber this file to a version at or below 2 -
--   checksum validation is on, so that attempt stops a deployment rather than passing quietly. Any
--   further seed script belongs in this directory and must carry a version above 2.
--
--   AND THE CEILING IS CHECKED IN CODE, WHICH IS WHY A CONFIGURATION EDIT ALONE CANNOT REACH THIS FILE.
--   com.carddemo.config.FlywayConfig inspects the ceiling AFTER it is bound, and under the production
--   profile it REFUSES to start when that ceiling reaches version 3 or beyond. An absent, predefined or
--   unreadable ceiling counts as reaching version 3, because the migration tool migrates to the latest
--   version when no ceiling is set: silence is the dangerous case. The same class refuses any resolved
--   location outside classpath:db/migration/schema - which refuses this file's own location by name, and
--   refuses the shared parent too, because a location above the split reaches this file recursively. The controls fail in the same direction and none is relied on
--   alone: the setting is visible in the profile documents and invisible in code, and the refusals are
--   unconditional in code and invisible in the documents.
--
--   THE ONE CASE NO CONFIGURATION VALUE CAN REACH is a database that was seeded under local or test
--   and is later opened by a production deployment: the rows and the history entries are already there
--   before the process starts, so nothing this file or those profiles declare can undo it.
--   com.carddemo.config.ProductionSeedRejectionCallback closes that: registered for the production
--   profile alone, it refuses start-up when the migration history records a seed version or when the
--   tables already hold the seeded identities. That control is the ONLY one that sees this state:
--   validate-on-migrate does NOT refuse such a database, because version 11 of the migration tool
--   ignores future migrations by default and an applied version 3 the schema location cannot resolve
--   is therefore not a validation failure. Measured, and asserted by
--   ProductionSeedRejectionCallbackIT.
--
--   A deployment that applies this file has seeded sample personal data, and one that also applies
--   V4__seed_user_security.sql has seeded ten known logins. Treat a V3 row in production as an
--   incident, not as a configuration preference.
--
-- -------------------------------------------------------------------------------------------------
-- PROVENANCE
-- -------------------------------------------------------------------------------------------------
--   Source repository commit SHA : 7756d895ffeb65f7ea72aaa609e356d9899afcec
--   Upstream release stamp       : CardDemo_v1.0-15-g27d6c6f-68, dated 2022-07-19
--
--   Fixture datasets read, with their measured byte counts, record counts and record lengths:
--       app/data/ASCII/custdata.txt    25,050 bytes    50 records   500 bytes
--       app/data/ASCII/acctdata.txt    15,050 bytes    50 records   300 bytes
--       app/data/ASCII/carddata.txt     7,550 bytes    50 records   150 bytes
--       app/data/ASCII/cardxref.txt     1,850 bytes    50 records    36 data bytes
--       app/data/ASCII/trantype.txt       427 bytes     7 records    60 bytes
--       app/data/ASCII/trancatg.txt     1,098 bytes    18 records    60 bytes
--       app/data/ASCII/discgrp.txt      2,601 bytes    51 records    50 bytes
--       app/data/ASCII/tcatbal.txt      2,550 bytes    50 records    50 bytes
--       app/data/ASCII/dailytran.txt  105,300 bytes   300 records   350 bytes
--
--   NO legacy source line is copied into this file. Field names, widths, offsets and record counts
--   are metadata describing where a value came from; the values themselves are fixture data, which
--   is what a seed migration is for. Nothing here transcribes a program, a copybook, a job stream or
--   a resource definition.
--
-- -------------------------------------------------------------------------------------------------
-- WHAT THIS FILE DOES NOT SEED
-- -------------------------------------------------------------------------------------------------
--   * transaction - stays empty. It is populated only by the posting job from validated daily rows,
--     so seeding it would make a posting run unverifiable against its own output.
--   * user_security - stays empty here. V4__seed_user_security.sql owns those ten logins.
--   * validation lookup data - not seeded, and no lookup table is created. The 490 North American
--     numbering-plan area codes (410 general purpose plus 80 easily recognisable), the 56 state codes
--     and the 240 state-and-ZIP-prefix combinations are resources, not rows:
--         src/main/resources/lookup/nanpa-area-codes.json
--         src/main/resources/lookup/us-state-codes.json
--         src/main/resources/lookup/state-zip-prefixes.json
--     Duplicating them here would create a second source of truth that could drift from the first.
--   * the duplicate account dataset - the EBCDIC estate carries the account data twice under two
--     names with byte-identical content, and no job stream references the second name. Accounts are
--     seeded once, from the single ASCII account fixture.
--
-- -------------------------------------------------------------------------------------------------
-- DETERMINISM
-- -------------------------------------------------------------------------------------------------
--   Static literals only, in fixture record order. No COPY, no psql meta-command, no external file
--   path, no generate_series, no loop, no sequence, no generated identifier, no clock, no random
--   source and no locale-sensitive conversion. Re-running the generator over the same fixtures
--   reproduces this file byte for byte.
--
--   No ON CONFLICT, no IF NOT EXISTS, no DELETE and no TRUNCATE. Applying this file to a schema that
--   already holds rows must fail loudly rather than merge silently: a drifted database is a finding,
--   not something for a seed to paper over.
--
--   `version` is deliberately omitted from the account and card column lists so that V1's
--   `NOT NULL DEFAULT 0` supplies the optimistic-locking seed value.
--
--   Display text is right-trimmed for relational storage - the fixed-width writers pad on output, so
--   storing the padding as well would double it. Trailing blanks are preserved in exactly three
--   places, each of which is behaviourally significant and documented at its table below:
--   account.acct_group_id, disclosure_group.dis_acct_group_id and daily_transaction.dalytran_proc_ts.
--
-- -------------------------------------------------------------------------------------------------
-- ZONED DECIMAL
-- -------------------------------------------------------------------------------------------------
--   Every monetary and rate field in the estate is zoned decimal held as display characters, not
--   packed decimal, and the trailing byte carries both the low-order digit and the sign:
--
--       positive   { = 0    A B C D E F G H I = 1 2 3 4 5 6 7 8 9
--       negative   } = 0    J K L M N O P Q R = 1 2 3 4 5 6 7 8 9
--
--   Each value below was decoded by replacing that final character with its digit, applying the sign
--   and placing the decimal point two digits from the right, then rendered as an exact scale-2
--   numeric literal. No exponent notation appears anywhere in this file, no value is rounded, and no
--   floating-point type is involved at any step - the legacy estate contains no ROUNDED clause, so a
--   store into a two-decimal field truncates, and a literal that has already been truncated to the
--   fixture's own two decimals cannot reintroduce the difference. A negative-zero image decodes to
--   0.00, which is the only zero a numeric column holds.
--
--   The same tables and the same truncating scale live in com.carddemo.util.ZonedDecimalCodec, which
--   is what reads these fields at run time; the two must agree byte for byte.
--
-- -------------------------------------------------------------------------------------------------
-- ANOMALIES CARRIED FAITHFULLY, AND DECISIONS TAKEN - see docs/decision-log.md
-- -------------------------------------------------------------------------------------------------
--   1. account.acct_addr_zip holds 'A000000000' and account.acct_group_id holds ten spaces in all
--      fifty fixture records. That looks like a transposition and it is not corrected: the values are
--      seeded exactly where the record layout puts them. The consequence is behavioural and wanted -
--      an all-blank group identifier misses the direct disclosure-group read, which is what drives
--      the interest calculation down its DEFAULT-group fallback path. A direct-hit test against group
--      'A000000000' needs a separately constructed fixture; it cannot come from this seed.
--   2. customer.cust_ssn is seeded as SQL NULL for all fifty rows, and it is the one nullable column
--      in the schema for exactly this reason. The nine bytes the legacy record holds at offset 279
--      are shaped like real national identifiers, and the correct handling of a value like that in a
--      checked-in artifact is not to carry it at all - not as cleartext, not as ciphertext, and not
--      in a comment. Sealing them would not help: every envelope in this file is sealed under the one
--      non-production fixture key, that key is itself committed as a profile default, and anything
--      sealed under a committed key is recoverable by anyone holding the repository. So they are not
--      carried. NULL here means "deliberately not seeded", never "unmapped field": every other
--      customer column is seeded. A test that needs a stored identifier persists one through the
--      application encryption path under its own key, which is what CustomerSsnEncryptionIT does.
--   3. customer.govt_issued_id is NOT NULL, and it is seeded as an ENC1 envelope in every row - never
--      as the cleartext the fixture record holds at offset 288. All fifty envelopes were produced by
--      com.carddemo.service.SensitiveFieldEncryptionService itself, over the twenty characters at
--      that offset of app/data/ASCII/custdata.txt, under the one non-production fixture key that
--      application-local.yml and both copies of application-test.yml declare. Each is 69 characters:
--      the ENC1: marker, then Base64 of a 96-bit initialisation vector, the ciphertext and the
--      128-bit authentication tag. So V1's contract for this column holds here too - the schema, the
--      Customer entity and CustomerRecordMapper all require an envelope, and a seeded row satisfies
--      every one of them rather than being an exception to all three.
--
--      Two properties make that safe in a checked-in artifact, and both are needed. The value is
--      invented: a twenty-digit fixture identifier with no subject behind it, unlike the national
--      identifiers in 2. And the key is worthless: a self-describing throwaway that seals nothing
--      outside a database rebuilt from these migrations. No key material is added to the repository
--      by this file - the key was already a committed profile default, and what is stored below is
--      ciphertext, not key material.
--
--      Because AES-GCM draws a fresh initialisation vector per call, the envelopes cannot be
--      regenerated identically and are therefore fixed literals. A literal cannot be re-keyed, so a
--      process configured with any other key holds fifty unreadable rows rather than rotated ones.
--      THREE THINGS KEEP THAT FROM HAPPENING QUIETLY, and the ordering matters. First, the two
--      seed-bearing profiles declare this key as a BARE LITERAL rather than as an environment-variable
--      default, so the ordinary route to a mismatch - exporting CARDDEMO_FIELD_ENCRYPTION_KEY - is not
--      available; only production resolves that variable, and it has no fallback there because it owns
--      real data. Second, if a mismatch arises some other way, the after-migrate callback opens every
--      stored value and fails start-up on the first that will not open, so the state is refused rather
--      than carried. Third, SeededProtectedIdentifierIT opens all fifty through the application service
--      and compares each recovered value against the fixture record it came from, so an edited literal
--      or a re-ordered row fails the build as well. Production never applies this file at all -
--      spring.flyway.target caps it at version 2 - so no production row is ever sealed under a fixture
--      key. Recorded in docs/decision-log.md DL-103.
--
--      One more component watches this column, and only one of its two jobs is redundant here.
--      com.carddemo.config.SeededIdentifierSealingCallback runs on the after-migrate event of a
--      seed-bearing migration and does two things. It SEALS any cleartext left in this column - or any
--      non-null cust_ssn - into the envelope V1 requires; because every value below already carries the
--      ENC1 marker and cust_ssn is NULL in every row, that half converts nothing, and it is retained as
--      defence in depth against a future edit to this file rather than as the mechanism that produces
--      what is stored here. It then OPENS every stored value under the key the running process actually
--      holds, and THAT half is not redundant at all: it is the only check that can tell a readable
--      envelope from an unreadable one. An envelope sealed under a different key still carries the ENC1
--      marker, so the seal check passes it; the fifty literals below cannot be re-keyed by anything; and
--      the consequence of a mismatch - fifty rows of regulated data this process cannot read - would
--      otherwise surface only when something happened to decrypt one. A value that will not open fails
--      the migration. Both halves are idempotent: an already-sealed value is left exactly as it is, no
--      envelope is ever wrapped inside another, and opening a value changes nothing.
--
--      ON THE TRANSACTION BOUNDARY, stated precisely because the opposite was once claimed here: the
--      after-migrate event is raised AFTER this migration's own transaction has committed, so the
--      callback's work is a transaction of its own and is NOT atomic with the rows inserted below. What
--      that costs is bounded - a failure aborts the start-up, so no application ever reads a database
--      whose identity columns are unsealed or unreadable, while this migration and the recorded history
--      stay consistent with each other - and both halves being idempotent means the corrected start-up
--      simply runs them again. Production is unaffected either way: it applies V1 and V2 only, receives
--      no row from this file, and does not carry the callback at all, which
--      com.carddemo.config.FlywayConfig registers for the local and test profiles alone.
--   4. disclosure_group.dis_acct_group_id keys are ten characters INCLUDING trailing blanks:
--      'A000000000', 'DEFAULT   ' and 'ZEROAPR   ', seventeen rows each. The last two carry exactly
--      three trailing spaces because the legacy fallback moves a seven-character literal into a
--      ten-character key field, which pads it. Trimming either key, or shortening the first to 'A',
--      breaks the lookup outright. The zero rates of the ZEROAPR group are genuinely seeded, but they
--      do NOT make the accrual zero-rate branch reachable from this seed: as anomaly 1 explains, every
--      account misses its first probe and re-probes as 'DEFAULT   ', whose rate on the (01, 0001) type
--      and category every seeded balance uses is 15.00, so a seed-only run always computes. Covering
--      the skip needs an account constructed with 'ZEROAPR   ' plus a matching category balance.
--   5. daily_transaction.dalytran_orig_ts is the same instant in all three hundred records and
--      dalytran_proc_ts is blank in all three hundred - twenty-six spaces, seeded as twenty-six
--      spaces. The processing timestamp is written by the posting job, not by the fixture, so no
--      clock is consulted and no instant is invented here. A report test that needs a spread of
--      processing dates therefore needs a constructed fixture; this seed cannot provide one.
--   6. daily_transaction carries no foreign key by design, even though every reference in this
--      fixture resolves. That is what lets separately constructed invalid input exercise the reject
--      reason codes and produce the 430-byte reject record.
--   7. Card numbers and card verification codes are seeded exactly as the fixture holds them. The
--      legacy design defines no masking, tokenization or encryption for either, and inventing one
--      here would be unrequested feature work; the gap is recorded in docs/decision-log.md instead.
-- -------------------------------------------------------------------------------------------------

-- -------------------------------------------------------------------------------------------------
-- 1 of 9 - customer: 50 rows from app/data/ASCII/custdata.txt, 500-byte records
-- -------------------------------------------------------------------------------------------------
-- Seeded first because three of the six foreign keys added by V2 point at this table or at
-- tables that point at it: card_cross_reference references customer, account and card.
--
-- cust_ssn is inserted as NULL in every row - see anomaly 2 in the header. The nine bytes
-- the record holds at offset 279 are read by nothing in this file and appear nowhere in it.
--
-- govt_issued_id is inserted as an ENC1 envelope in every row, never as the cleartext the
-- record holds at offset 288 - see anomaly 3 in the header. Each literal below is 69
-- characters and was produced by the application's own encryption service under the one
-- non-production fixture key; each one occupies its own continuation line so that the fifty
-- sealed values read as a column and an unsealed row is visible at a glance.
--
-- middle_name and addr_line_2 are stored but must never be validated downstream: the legacy
-- update path decorates both for error display while coding no edit for either, so any
-- constraint would reject input the legacy system accepts.
--
--   Record slices used, as zero-based [start:end) byte ranges:
--       [0:9] cust_id            [9:34] first_name        [34:59] middle_name
--       [59:84] last_name        [84:134] addr_line_1      [134:184] addr_line_2
--       [184:234] addr_line_3    [234:236] addr_state_cd   [236:239] addr_country_cd
--       [239:249] addr_zip       [249:264] phone_num_1     [264:279] phone_num_2
--       [308:318] cust_dob       [318:328] eft_account_id
--       [328:329] pri_card_holder_ind                      [329:332] fico_credit_score
--       [279:288] cust_ssn and [288:308] govt_issued_id are NOT read - both columns are seeded
--                 NULL, so the two regulated spans are skipped entirely
--       [332:500] trailing filler, not a column
--   The govt_issued_id slice is the SOURCE of the sealed literal, not the stored value: the
--   twenty characters at [288:308] are what each envelope below opens to.
-- -------------------------------------------------------------------------------------------------
INSERT INTO customer (
    cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3,
    addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, cust_ssn, govt_issued_id,
    cust_dob, eft_account_id, pri_card_holder_ind, fico_credit_score
) VALUES
('000000001', 'Immanuel', 'Madeline', 'Kessler', '618 Deshaun Route', 'Apt. 802', 'Altenwerthshire', 'NC', 'USA', '12546', '(908)119-8310', '(373)693-8684', NULL,
    'ENC1:MyItVNBPfsSTXnF2DidQ5qEEOla5O7h+Ri0HFj/Q2SSybYo5U+7By9x8ESkDWA4Q', '1961-06-08', '0053581756', 'Y', '274'),
('000000002', 'Enrico', 'April', 'Rosenbaum', '4917 Myrna Flats', 'Apt. 453', 'West Bernita', 'IN', 'USA', '22770', '(429)706-9510', '(744)950-5272', NULL,
    'ENC1:geVujDCceJOwUREntn2VB/JDkKRCMtWM8DikCRalWNru9vQrLquRQJvyfnFp/OsQ', '1961-10-08', '0069194009', 'Y', '268'),
('000000003', 'Larry', 'Cody', 'Homenick', '362 Esta Parks', 'Apt. 390', 'New Gladys', 'GA', 'USA', '19852-6716', '(950)396-9024', '(685)168-8826', NULL,
    'ENC1:RyL+q813MDma45dkjl2zt0wCTcPfTC8txtzzD2pMtq+iAdrA7yOP/NLQu1cm99yr', '1987-11-30', '0006465789', 'Y', '616'),
('000000004', 'Delbert', 'Kaia', 'Parisian', '638 Blanda Gateway', 'Apt. 076', 'Lake Virginie', 'MI', 'USA', '39035-0455', '(801)603-4121', '(156)074-6837', NULL,
    'ENC1:cnaHLktX6p6pyLn9uC6xPnqX+mkyR8UYroV4naJZ3JS0kL0aiOUwjVZKSa8bgUeT', '1985-01-13', '0040802739', 'Y', '776'),
('000000005', 'Treva', 'Manley', 'Schowalter', '5653 Legros Plaza', 'Apt. 968', 'Alvinaport', 'MI', 'USA', '02251-1698', '(978)775-4633', '(439)943-7644', NULL,
    'ENC1:HnJB8vFQYPxO0ET5+Wzn2pgUxV0gSLlap86fUFcOZIWrx0+/+dIhzSC33tEGuxyp', '1971-09-29', '0006365573', 'Y', '529'),
('000000006', 'Ignacio', 'Emery', 'Douglas', '3963 Yasmin Port', 'Suite 756', 'Port Josephstad', 'VI', 'USA', '46713-5148', '(277)743-4266', '(519)010-8739', NULL,
    'ENC1:GLzlRENTFUvllG/vy86SuymSuMUKBoXhcTnycoKWCDG7WKdAnDOyTKosZAfzhLr8', '1994-11-29', '0067163009', 'Y', '753'),
('000000007', 'Cooper', 'Dennis', 'Mayert', '6490 Zakary Locks', 'Apt. 765', 'Madieport', 'AL', 'USA', '34206-2974', '(698)282-4096', '(458)199-0016', NULL,
    'ENC1:DLcVsPzghN9uxCZrdQ0PRpg8diSulIhF37Y/gIuZc609Jx+YwL/UuFB/POX+SZvv', '1977-05-06', '0024571415', 'Y', '499'),
('000000008', 'Kelsie', 'Jordyn', 'Dicki', '0925 Welch Streets', 'Apt. 152', 'North Nanniestad', 'SC', 'USA', '27610', '(345)563-7159', '(443)197-1271', NULL,
    'ENC1:0x6iFuu5AVUb2q1e2a+KuaRx0t9bxMudjQmqOAsLRPN4wr4qh4RZmIhottv5e0vY', '1964-03-25', '0033132723', 'Y', '051'),
('000000009', 'Melvin', 'Regan', 'Ondricka', '87893 Samson Flats', 'Apt. 135', 'New Braden', 'VI', 'USA', '21113', '(035)456-1404', '(412)440-3130', NULL,
    'ENC1:OzPlUiZY5zktXbqd5bXOyiNmdVDwQzBjRQfsV5zVitlxaFn5YtrejeaVgLfLumeJ', '1975-11-07', '0039446039', 'Y', '699'),
('000000010', 'Maybell', 'Creola', 'Mann', '77933 Adah Dale', 'Suite 343', 'Andersonfurt', 'CT', 'USA', '44803-4279', '(614)594-2619', '(667)057-0235', NULL,
    'ENC1:rncGPlRagOUTijXFqSyfcUt1bavkKV5y9E8A0mNMZvOEAdzYutksqWWvnwBgpTP5', '1980-06-11', '0093803568', 'Y', '476'),
('000000011', 'Hayden', 'Ressie', 'Pfannerstill', '14895 Everette Ridges', 'Apt. 443', 'Julianneburgh', 'WA', 'USA', '24984', '(002)533-6980', '(553)586-7718', NULL,
    'ENC1:qqBv4l4NnioX+s01emzyH35HhkpIOLFj4QDahuqxN3nfFpW63HzmBl/ZIfvterid', '1986-11-03', '0002650577', 'Y', '209'),
('000000012', 'Maci', 'Alan', 'Robel', '80501 Isac Cliffs', 'Suite 623', 'Predovicton', 'MN', 'USA', '78861', '(584)045-5200', '(610)244-0407', NULL,
    'ENC1:J+T9S/X98khlvq8O3yWhCm6oavFJz4sih01lvr95lTqwMXm+dH5jZ8wvmfLxVuKj', '1984-02-18', '0061317348', 'Y', '688'),
('000000013', 'Mariane', 'Oma', 'Fadel', '2689 Derick Mission', 'Suite 055', 'Bruenfurt', 'OR', 'USA', '02322', '(875)943-7287', '(075)550-6435', NULL,
    'ENC1:YgDa+1deNI/26ZjkBbAIeIPhPOoha16cIFNrgthjLs9P4LwAGp6LR8kPvkR6Ex7Q', '1999-03-09', '0044807431', 'Y', '053'),
('000000014', 'Chelsea', 'Ignacio', 'Marks', '747 Dino Lodge', 'Apt. 850', 'West Chase', 'RI', 'USA', '12914-8465', '(141)807-6571', '(284)088-9052', NULL,
    'ENC1:PUfyls6a1TKAvv+HNauSMqSWW4I+diW61OcWtxpS/HElcC30rNS0xsQZjvrI1LX6', '1974-11-29', '0048306401', 'Y', '243'),
('000000015', 'Aubree', 'Elliot', 'Hermann', '36365 Ledner Drives', 'Suite 882', 'Port Efrainland', 'DE', 'USA', '63205-7014', '(769)100-7971', '(366)310-2061', NULL,
    'ENC1:m+rxEmIlsSRBowUA+1HvEnibfnarfTWQELjlDd7Iif9G9hItMN1hzbREPnxyXWsf', '1964-12-06', '0000634612', 'Y', '681'),
('000000016', 'Carroll', 'Cicero', 'Bergstrom', '06988 Thiel Falls', 'Suite 148', 'Concepcionland', 'VT', 'USA', '84390', '(631)343-8667', '(938)648-3716', NULL,
    'ENC1:gxK00AmgSHesWuKbpQUE5KClVlsxhoKVq2twSpL5hFbYzigMcNUwO1Z7u9n/pTVt', '1983-04-27', '0012556599', 'Y', '326'),
('000000017', 'Sigrid', 'Angeline', 'Mann', '95666 Dare Isle', 'Suite 286', 'New Presley', 'FM', 'USA', '56181-0584', '(087)314-2070', '(541)003-6606', NULL,
    'ENC1:/y3Sf9uMTMxJO16XH+5SSgXOeboj2hOhqZXXqKChTNOgrtvtfgYgrNpNc+T1WXs2', '1979-01-26', '0052356071', 'Y', '054'),
('000000018', 'Emile', 'Jairo', 'White', '133 Bergnaum Square', 'Apt. 328', 'Hansenville', 'AP', 'USA', '96003-5867', '(303)654-3323', '(520)186-2176', NULL,
    'ENC1:z/w4trbX8/SJzfCwHGktZyK6GeQSn5Awab2qvjGSzJ+FJoOngAOJO6BzOSAmeb/J', '1987-03-25', '0086459831', 'Y', '340'),
('000000019', 'Hadley', 'Sigrid', 'Hamill', '6273 Ondricka Meadows', 'Apt. 130', 'New Arturoshire', 'RI', 'USA', '48161', '(817)452-4986', '(724)901-6019', NULL,
    'ENC1:LegFfRIG1h6ssvkF/ubHh3nnW/tn9aLTOwkrpg/V/o67QA134NxTqYlwops4PuVr', '1991-01-07', '0036492057', 'Y', '259'),
('000000020', 'Carter', 'Oren', 'Veum', '5845 Allison Valleys', 'Suite 934', 'Mitchellmouth', 'MH', 'USA', '72362', '(618)994-0531', '(571)695-4136', NULL,
    'ENC1:B1cnOy3AIR9dAn3XQP92Z9zLxyME0/wFUroA6Oh6ul4psEbo+nXBKdf1a6J8r9T2', '1996-04-14', '0036749754', 'Y', '493'),
('000000021', 'Jerrold', 'Adolphus', 'Maggio', '401 Haylie Crest', 'Apt. 320', 'North Myrnaton', 'CA', 'USA', '72407', '(399)526-3254', '(326)193-1118', NULL,
    'ENC1:SAsPO3UnQXAdb7vnd7BP/afpD3sWnW7iqOf1TAUQSVsRTGIM/FnazlPWTA3jmBje', '1977-11-15', '0011744660', 'Y', '163'),
('000000022', 'Allene', 'Icie', 'Brown', '4467 Donnie Crossroad', 'Apt. 437', 'Anabelton', 'MD', 'USA', '01993-9116', '(231)251-5792', '(494)652-0009', NULL,
    'ENC1:NtLEXWQ9ybaq4NhhhHYAS4cPBPk5Kvtse3pOcjk0QvIF3TSdQww0KOynbdXy8K4g', '1994-02-20', '0024791470', 'Y', '597'),
('000000023', 'Johnson', 'Blanca', 'Ruecker', '2433 Jacobi Forks', 'Apt. 845', 'Hendersonbury', 'KS', 'USA', '78239-9466', '(981)873-1589', '(131)638-5974', NULL,
    'ENC1:QM5LRvz0TgbhN5mXNzBngqG4fOTRZI2LOohLFbvQHTogMhP9ic6zyDaEu7g7fqWs', '1998-12-07', '0075158529', 'Y', '337'),
('000000024', 'Stefanie', 'Verla', 'Dickinson', '6367 Stracke River', 'Apt. 444', 'East Otho', 'KS', 'USA', '15414', '(617)348-9142', '(330)116-5634', NULL,
    'ENC1:i0dEK9eg/G7pq1WGjOIs0aY9GjydKW4SNL3WJnuRv6Hl/bSuEQMPI2rDyw0REtPD', '1996-01-24', '0005459662', 'Y', '711'),
('000000025', 'Elliott', 'Fermin', 'Howell', '9524 McKenzie Lakes', 'Suite 245', 'West Alexa', 'NH', 'USA', '75721-7382', '(092)336-8599', '(311)969-1460', NULL,
    'ENC1:CxalVDZnZWsBhxiFMW2BtOOycWsWZW50lMbJOno6tECa8ISIES7zZ99kyDzRIOPv', '1989-03-27', '0032297533', 'Y', '355'),
('000000026', 'Marjory', 'Damien', 'Stracke', '30161 Bogan Canyon', 'Suite 916', 'Walshberg', 'IL', 'USA', '59945', '(584)772-2867', '(819)733-9809', NULL,
    'ENC1:2ShaPf6HQ8IhjsZ/SHbjuSgSBUB4hdrdJ/T1YjTAlkvSiC2NJqt88HVTzvZz7zBg', '1990-03-17', '0060808858', 'Y', '001'),
('000000027', 'Ward', 'Henri', 'Jones', '210 Amaya Turnpike', 'Suite 180', 'Port Dwight', 'GU', 'USA', '07923-8822', '(935)027-1145', '(103)537-5007', NULL,
    'ENC1:X4nXIrF2cPTx8Xs9ssF/+qhKde7EOnk+eaW0Jt0IXs/kJVo2NfJOynBHj5/7+K5/', '1986-11-08', '0050024139', 'Y', '078'),
('000000028', 'Hester', 'Vesta', 'Hane', '06816 Ursula Meadows', 'Suite 605', 'South Aurore', 'AS', 'USA', '77442-7954', '(122)357-7257', '(050)352-6579', NULL,
    'ENC1:lEjg1httS2cV94oYflfWnIDweUi4RzloiFy7KfwJVZ4DqH6d1kQgToHwgRl6Az3X', '1991-06-05', '0026946180', 'Y', '114'),
('000000029', 'Rickie', 'Otho', 'Daugherty', '676 Funk Curve', 'Apt. 375', 'Hayesstad', 'NH', 'USA', '01226', '(418)291-9023', '(795)634-7776', NULL,
    'ENC1:oAbIbvj/pZPjeNSk0H0PqrrSJ3DNbj//nnW88rBfga1MGB8X6jmCTO8j+JjmwDJ0', '1973-04-05', '0067736493', 'Y', '552'),
('000000030', 'Layla', 'Dannie', 'Ullrich', '269 Eleazar Circle', 'Apt. 817', 'Kutchland', 'AK', 'USA', '64266', '(330)408-6966', '(413)347-7306', NULL,
    'ENC1:RrpaWPi5XLNH5Jhrr2vhXDwmK4guwko+5CkrCkwGCdMQIB+9tvbQ/Fv0OL5G578C', '1965-11-28', '0050520060', 'Y', '133'),
('000000031', 'Lucious', 'Otto', 'O''Connell', '919 Swift Valleys', 'Suite 548', 'Hermanborough', 'MS', 'USA', '56133-5636', '(259)414-9625', '(118)946-9264', NULL,
    'ENC1:vfSpSpBG91YUQ/Snvc6L7tnVGbJ6SILaD2ZMc0i2M2HyVZG4Lyrt6YCQY8CGlequ', '1976-08-03', '0092999757', 'Y', '058'),
('000000032', 'Stephany', 'Meda', 'Fisher', '63452 Kenny Streets', 'Apt. 116', 'Predovicburgh', 'AK', 'USA', '85943-7605', '(202)436-5156', '(246)296-3533', NULL,
    'ENC1:tJBOmowWGGUjqOx+r1mUX1pffCooa0IVMjHOEyp5ZZ+BaQVEey0VzW+ecBNgjzIQ', '1980-11-19', '0035970593', 'Y', '221'),
('000000033', 'Bernice', 'Norbert', 'Herman', '877 Kassandra Ranch', 'Suite 956', 'Haleyport', 'AR', 'USA', '19113-4329', '(836)743-5487', '(640)208-1176', NULL,
    'ENC1:++FvppWHqiuUNmMEozQ/ndQCm5wmIWxjsjy4urYplOb2su8EDpoAoBfI3qYY/KhH', '1988-05-19', '0065245171', 'Y', '469'),
('000000034', 'Faustino', 'Jess', 'Schmidt', '44132 Michel Square', 'Suite 007', 'South Margarettaburgh', 'ME', 'USA', '49544-2869', '(179)036-5135', '(986)905-0112', NULL,
    'ENC1:EOpBeNvwVrjgeoVpLCF4UL4rBcnUaQzo7H5nawt9HEEfpDcD4TG6LhmR4zbeuhrF', '1994-03-21', '0067445089', 'Y', '104'),
('000000035', 'Angelica', 'Damaris', 'Dach', '396 Pearl Loop', 'Suite 383', 'Pfefferhaven', 'LA', 'USA', '46142', '(303)480-9098', '(637)710-7367', NULL,
    'ENC1:PQ0biP/4DdIfYu/bF2Ucz9AcDcOK7+ITprweZemrYBIlQBUl+j/EhUBATkbKCyEn', '1987-06-23', '0047435332', 'Y', '793'),
('000000036', 'Toney', 'Emerald', 'Gerhold', '35943 Raleigh Harbor', 'Apt. 116', 'Lake Derekburgh', 'AL', 'USA', '10932-0480', '(034)271-9180', '(507)529-4523', NULL,
    'ENC1:HJcSZ3DfXYBy8MCYdRbliwQNHo96qjODX0L4KWTBtSEahgcEIme5MYPrBfRcevMz', '1991-03-31', '0066461979', 'Y', '266'),
('000000037', 'Shany', 'Darby', 'Walker', '91196 Heaney Turnpike', 'Suite 814', 'Lubowitzberg', 'NV', 'USA', '11857-8177', '(052)759-5167', '(706)896-1282', NULL,
    'ENC1:1eI1sLctE0OOI9kQ7z1DTjmnB0drucb279MgCDwMXFzHjvBj4X0AQcQVEGGmRJXQ', '1984-12-09', '0066111704', 'Y', '653'),
('000000038', 'Angela', 'Ceasar', 'Ankunding', '65482 Zoila Skyway', 'Apt. 054', 'East Malachi', 'VA', 'USA', '63928-0008', '(316)640-2650', '(148)111-1148', NULL,
    'ENC1:4YxjeZlJRgJBeHfSd5qEmsDu1uBDfTPjXVY0fmxU04pKlRBcgOL2bQseF8ZTO0ux', '1990-05-28', '0018048939', 'Y', '446'),
('000000039', 'Aliyah', 'Horace', 'Berge', '5761 Pasquale Trail', 'Apt. 616', 'New Sabryna', 'IA', 'USA', '74267', '(089)096-3287', '(768)959-4733', NULL,
    'ENC1:WL3optPCa+qjl+dNb3/Pzhf64CrFTvmztYWoxQlKPuEsZdJOTgW1aIJhwLSNbbRz', '1972-08-26', '0061869530', 'Y', '475'),
('000000040', 'Davon', 'Demond', 'Emmerich', '23499 Beer Views', 'Suite 816', 'Erniechester', 'TX', 'USA', '87156-8689', '(463)762-3017', '(419)414-2177', NULL,
    'ENC1:5fIld1Ph1zBHn5NLBS2aZkhss8RcHIsUdgdia9+wpMet1Yv4CRkSlmd3CeqqRmv5', '1992-01-26', '0087069976', 'Y', '284'),
('000000041', 'Lucinda', 'Kiana', 'Dach', '3220 Yolanda Corner', 'Suite 649', 'East Harmonystad', 'VT', 'USA', '72971-7481', '(284)052-5831', '(091)234-2144', NULL,
    'ENC1:gxdplRxy2VjOCOOCjdPFwOSgpR4MvN80To0B9Km5UqfZYo6mWJBAaNK/QFHwUFdw', '1967-02-20', '0007315287', 'Y', '725'),
('000000042', 'Heather', 'Ericka', 'Nienow', '5523 Archibald Club', 'Apt. 358', 'Reillyland', 'FM', 'USA', '83589', '(640)954-4538', '(565)873-6897', NULL,
    'ENC1:Tm1BD315Qv9VmCwc0jK1VALzJwN+c1wf9FVguFUyYopv6jwUmA4OPE8dr1uqMaob', '1964-11-03', '0079262985', 'Y', '044'),
('000000043', 'Britney', 'Jermain', 'Waters', '97765 Bernhard Fort', 'Apt. 666', 'South Marisaview', 'OK', 'USA', '10050-7980', '(407)042-6952', '(438)659-6397', NULL,
    'ENC1:oeuhSAzYXqJFqocIlaDw4GQ3QjqkeRvzxXU0T4A0uMJ8y5dI5xKp+f238Bup+zcH', '1966-10-16', '0053043599', 'Y', '558'),
('000000044', 'Irving', 'Kiera', 'Emard', '978 Fatima Stream', 'Apt. 110', 'Lake King', 'ID', 'USA', '05704-0501', '(703)484-5840', '(537)392-5569', NULL,
    'ENC1:CR4QS4Gq383l87FyiGadxzRzqiiUyj0+j4gtU05t+FAW8HEsiXQfF7DLQroFTCl7', '1984-04-04', '0032076778', 'Y', '145'),
('000000045', 'Dixie', 'Norris', 'Beier', '441 Levi Prairie', 'Suite 749', 'Abbottshire', 'NV', 'USA', '09048', '(697)143-3221', '(499)287-7255', NULL,
    'ENC1:i9XPZ24LnLoGEr7sDj5hZFeUH6CnAi58pu+VPdEujSlvnVuLQ3ixGL0GP+pzkygk', '2001-12-12', '0027833000', 'Y', '629'),
('000000046', 'Cindy', 'Kira', 'Cremin', '494 Lang Avenue', 'Apt. 937', 'Alexandroview', 'PW', 'USA', '63082-4520', '(358)349-2574', '(077)525-9966', NULL,
    'ENC1:pqWpBEMOn3HfUpcOCV1Nh9pd0lwXd5rEdMDafPviFGWKugUVvWymKCLdGKXUPLGs', '1987-12-14', '0017535749', 'Y', '514'),
('000000047', 'Rigoberto', 'Savanna', 'Hoeger', '00097 Gleichner Spur', 'Apt. 932', 'Port Aidanborough', 'GU', 'USA', '31329-6973', '(946)322-6160', '(973)443-8438', NULL,
    'ENC1:GEjkUX77Ce2hvFadxP45NMeLoC0ty8ugwktKnnmW/QyEqh9lX3rBv+/581ANWPEc', '1979-02-25', '0022102472', 'Y', '722'),
('000000048', 'Lyric', 'Mackenzie', 'Pacocha', '453 Rosina Mountain', 'Apt. 011', 'Albertville', 'OR', 'USA', '83985-4937', '(950)497-1005', '(004)244-7955', NULL,
    'ENC1:HeXvqbFpN6plExNtGbU4qJid8k6GjOx3IP+z7CQsUKiY9k44EpXFU/gjmddbf3N1', '1986-08-17', '0046317382', 'Y', '746'),
('000000049', 'Immanuel', 'Ellie', 'Bednar', '5423 Esther Locks', 'Apt. 142', 'Langoshstad', 'GA', 'USA', '12288-3495', '(843)095-2553', '(615)988-9038', NULL,
    'ENC1:zs1pKoneQ44fOkDC04jMs8yILiKs+4WZkiwF9Vrk1UcPZcc6AQJtwnJqFQX1ZASv', '2000-01-05', '0058726120', 'Y', '148'),
('000000050', 'Aniya', 'Alba', 'Von', '1588 Nienow Cape', 'Suite 187', 'New Aricchester', 'OR', 'USA', '04257', '(325)301-0827', '(493)985-9283', NULL,
    'ENC1:o1BTQf/8MxUznsdA20VG7SfZ8VqrY9inQlJEOiqiR3d+u4dGkhP/Ihz8ZgyOzo5S', '1960-12-01', '0074883577', 'Y', '623');

-- -------------------------------------------------------------------------------------------------
-- 2 of 9 - account: 50 rows from app/data/ASCII/acctdata.txt, 300-byte records
-- -------------------------------------------------------------------------------------------------
-- Parent of card, card_cross_reference and transaction_category_balance.
--
-- Five zoned-decimal money fields, each decoded to an exact scale-2 literal. All five
-- trailing bytes in this fixture are the positive-zero overpunch, so every amount here is
-- positive or zero; the decoder handles the negative table identically and is exercised by
-- the daily-transaction returns further down.
--
-- acct_addr_zip is 'A000000000' and acct_group_id is ten spaces in all fifty rows. That is
-- the fixture, carried faithfully - see anomaly 1 in the header. The ten-space group
-- identifier is the value, not padding to be trimmed.
--
-- `version` is omitted so V1's NOT NULL DEFAULT 0 seeds the optimistic-locking column.
--
--   Record slices used, as zero-based [start:end) byte ranges:
--       [0:11] acct_id                  [11:12] acct_active_status
--       [12:24] acct_curr_bal           [24:36] acct_credit_limit
--       [36:48] acct_cash_credit_limit  [48:58] acct_open_date
--       [58:68] acct_expiration_date    [68:78] acct_reissue_date
--       [78:90] acct_curr_cyc_credit    [90:102] acct_curr_cyc_debit
--       [102:112] acct_addr_zip         [112:122] acct_group_id
--       [122:300] trailing filler, not a column
-- -------------------------------------------------------------------------------------------------
INSERT INTO account (
    acct_id, acct_active_status, acct_curr_bal, acct_credit_limit, acct_cash_credit_limit,
    acct_open_date, acct_expiration_date, acct_reissue_date, acct_curr_cyc_credit,
    acct_curr_cyc_debit, acct_addr_zip, acct_group_id
) VALUES
('00000000001', 'Y', 194.00, 2020.00, 1020.00, '2014-11-20', '2025-05-20', '2025-05-20', 0.00, 0.00, 'A000000000', '          '),
('00000000002', 'Y', 158.00, 6130.00, 5448.00, '2013-06-19', '2024-08-11', '2024-08-11', 0.00, 0.00, 'A000000000', '          '),
('00000000003', 'Y', 147.00, 4909.00, 538.00, '2013-08-23', '2024-01-10', '2024-01-10', 0.00, 0.00, 'A000000000', '          '),
('00000000004', 'Y', 40.00, 3503.00, 2789.00, '2012-11-17', '2023-12-16', '2023-12-16', 0.00, 0.00, 'A000000000', '          '),
('00000000005', 'Y', 345.00, 3819.00, 2430.00, '2012-10-03', '2025-03-09', '2025-03-09', 0.00, 0.00, 'A000000000', '          '),
('00000000006', 'Y', 218.00, 3584.00, 2948.00, '2017-12-23', '2025-10-08', '2025-10-08', 0.00, 0.00, 'A000000000', '          '),
('00000000007', 'Y', 193.00, 2065.00, 264.00, '2012-10-12', '2024-12-13', '2024-12-13', 0.00, 0.00, 'A000000000', '          '),
('00000000008', 'Y', 605.00, 6104.00, 1318.00, '2012-01-04', '2024-05-20', '2024-05-20', 0.00, 0.00, 'A000000000', '          '),
('00000000009', 'Y', 560.00, 8201.00, 2065.00, '2016-08-27', '2024-12-27', '2024-12-27', 0.00, 0.00, 'A000000000', '          '),
('00000000010', 'Y', 159.00, 5401.00, 4442.00, '2015-09-13', '2023-01-27', '2023-01-27', 0.00, 0.00, 'A000000000', '          '),
('00000000011', 'Y', 212.00, 4998.00, 3175.00, '2014-09-12', '2025-03-12', '2025-03-12', 0.00, 0.00, 'A000000000', '          '),
('00000000012', 'Y', 176.00, 4636.00, 388.00, '2009-06-17', '2023-07-07', '2023-07-07', 0.00, 0.00, 'A000000000', '          '),
('00000000013', 'Y', 41.00, 7542.00, 4922.00, '2017-10-01', '2024-08-04', '2024-08-04', 0.00, 0.00, 'A000000000', '          '),
('00000000014', 'Y', 15.00, 2254.00, 212.00, '2010-12-04', '2025-12-11', '2025-12-11', 0.00, 0.00, 'A000000000', '          '),
('00000000015', 'Y', 489.00, 8441.00, 3833.00, '2009-10-06', '2025-06-09', '2025-06-09', 0.00, 0.00, 'A000000000', '          '),
('00000000016', 'Y', 733.00, 8922.00, 2632.00, '2014-09-11', '2024-01-25', '2024-01-25', 0.00, 0.00, 'A000000000', '          '),
('00000000017', 'Y', 33.00, 568.00, 510.00, '2014-05-17', '2025-03-01', '2025-03-01', 0.00, 0.00, 'A000000000', '          '),
('00000000018', 'Y', 144.00, 2903.00, 1496.00, '2018-11-15', '2023-09-10', '2023-09-10', 0.00, 0.00, 'A000000000', '          '),
('00000000019', 'Y', 480.00, 6986.00, 3723.00, '2011-12-14', '2025-07-23', '2025-07-23', 0.00, 0.00, 'A000000000', '          '),
('00000000020', 'Y', 369.00, 3767.00, 1040.00, '2014-02-27', '2024-03-13', '2024-03-13', 0.00, 0.00, 'A000000000', '          '),
('00000000021', 'Y', 112.00, 1264.00, 180.00, '2011-10-19', '2023-01-06', '2023-01-06', 0.00, 0.00, 'A000000000', '          '),
('00000000022', 'Y', 55.00, 8599.00, 4712.00, '2016-11-21', '2025-12-28', '2025-12-28', 0.00, 0.00, 'A000000000', '          '),
('00000000023', 'Y', 104.00, 3377.00, 2904.00, '2012-03-15', '2025-03-18', '2025-03-18', 0.00, 0.00, 'A000000000', '          '),
('00000000024', 'Y', 400.00, 5174.00, 4129.00, '2015-08-08', '2025-02-11', '2025-02-11', 0.00, 0.00, 'A000000000', '          '),
('00000000025', 'Y', 61.00, 8194.00, 6582.00, '2012-10-26', '2025-07-10', '2025-07-10', 0.00, 0.00, 'A000000000', '          '),
('00000000026', 'Y', 46.00, 2181.00, 1375.00, '2009-04-20', '2024-12-19', '2024-12-19', 0.00, 0.00, 'A000000000', '          '),
('00000000027', 'Y', 284.00, 5572.00, 2075.00, '2012-09-30', '2025-07-13', '2025-07-13', 0.00, 0.00, 'A000000000', '          '),
('00000000028', 'Y', 68.00, 868.00, 547.00, '2015-05-20', '2024-05-09', '2024-05-09', 0.00, 0.00, 'A000000000', '          '),
('00000000029', 'Y', 339.00, 5511.00, 4361.00, '2015-11-03', '2024-06-04', '2024-06-04', 0.00, 0.00, 'A000000000', '          '),
('00000000030', 'Y', 2.00, 120.00, 93.00, '2011-08-26', '2024-06-27', '2024-06-27', 0.00, 0.00, 'A000000000', '          '),
('00000000031', 'Y', 31.00, 1140.00, 1077.00, '2017-02-25', '2025-06-08', '2025-06-08', 0.00, 0.00, 'A000000000', '          '),
('00000000032', 'Y', 30.00, 1175.00, 846.00, '2013-11-10', '2025-05-19', '2025-05-19', 0.00, 0.00, 'A000000000', '          '),
('00000000033', 'Y', 410.00, 6404.00, 951.00, '2012-10-11', '2025-10-07', '2025-10-07', 0.00, 0.00, 'A000000000', '          '),
('00000000034', 'Y', 253.00, 3642.00, 2770.00, '2009-05-10', '2025-10-06', '2025-10-06', 0.00, 0.00, 'A000000000', '          '),
('00000000035', 'Y', 166.00, 1947.00, 1525.00, '2018-02-02', '2025-09-23', '2025-09-23', 0.00, 0.00, 'A000000000', '          '),
('00000000036', 'Y', 110.00, 3328.00, 839.00, '2018-07-18', '2024-12-23', '2024-12-23', 0.00, 0.00, 'A000000000', '          '),
('00000000037', 'Y', 7.00, 446.00, 166.00, '2016-09-10', '2023-10-24', '2023-10-24', 0.00, 0.00, 'A000000000', '          '),
('00000000038', 'Y', 612.00, 6505.00, 3476.00, '2010-08-12', '2023-07-23', '2023-07-23', 0.00, 0.00, 'A000000000', '          '),
('00000000039', 'Y', 843.00, 9750.00, 6212.00, '2018-08-26', '2025-09-08', '2025-09-08', 0.00, 0.00, 'A000000000', '          '),
('00000000040', 'Y', 43.00, 5823.00, 1674.00, '2010-02-13', '2023-10-27', '2023-10-27', 0.00, 0.00, 'A000000000', '          '),
('00000000041', 'Y', 375.00, 6721.00, 3429.00, '2015-02-07', '2023-04-24', '2023-04-24', 0.00, 0.00, 'A000000000', '          '),
('00000000042', 'Y', 302.00, 6563.00, 5103.00, '2016-09-19', '2025-09-19', '2025-09-19', 0.00, 0.00, 'A000000000', '          '),
('00000000043', 'Y', 610.00, 6168.00, 1206.00, '2012-04-09', '2025-08-29', '2025-08-29', 0.00, 0.00, 'A000000000', '          '),
('00000000044', 'Y', 263.00, 6899.00, 4432.00, '2018-12-01', '2024-01-17', '2024-01-17', 0.00, 0.00, 'A000000000', '          '),
('00000000045', 'Y', 186.00, 2719.00, 688.00, '2010-12-31', '2025-07-09', '2025-07-09', 0.00, 0.00, 'A000000000', '          '),
('00000000046', 'Y', 396.00, 7007.00, 5438.00, '2013-09-06', '2025-06-20', '2025-06-20', 0.00, 0.00, 'A000000000', '          '),
('00000000047', 'Y', 32.00, 2338.00, 159.00, '2014-04-03', '2025-08-23', '2025-08-23', 0.00, 0.00, 'A000000000', '          '),
('00000000048', 'Y', 226.00, 2306.00, 612.00, '2017-03-18', '2025-02-06', '2025-02-06', 0.00, 0.00, 'A000000000', '          '),
('00000000049', 'Y', 100.00, 9048.00, 4807.00, '2019-04-06', '2023-09-17', '2023-09-17', 0.00, 0.00, 'A000000000', '          '),
('00000000050', 'Y', 492.00, 6169.00, 4587.00, '2011-04-22', '2023-03-09', '2023-03-09', 0.00, 0.00, 'A000000000', '          ');

-- -------------------------------------------------------------------------------------------------
-- 3 of 9 - card: 50 rows from app/data/ASCII/carddata.txt, 150-byte records
-- -------------------------------------------------------------------------------------------------
-- Seeded after account so fk_card_account resolves, and before card_cross_reference so
-- fk_card_xref_card resolves.
--
-- card_num and card_cvv_cd are seeded exactly as the fixture holds them - see anomaly 7 in
-- the header. card_embossed_name is right-trimmed display text; the legacy update path
-- upper-cases it through a twenty-six-character ASCII table rather than a locale-aware
-- fold, which is the application's concern and not the seed's.
--
-- `version` is omitted so V1's NOT NULL DEFAULT 0 seeds the optimistic-locking column.
--
--   Record slices used, as zero-based [start:end) byte ranges:
--       [0:16] card_num           [16:27] card_acct_id      [27:30] card_cvv_cd
--       [30:80] card_embossed_name (right-trimmed)          [80:90] card_expiration_date
--       [90:91] card_active_status [91:150] trailing filler, not a column
-- -------------------------------------------------------------------------------------------------
INSERT INTO card (
    card_num, card_acct_id, card_cvv_cd, card_embossed_name, card_expiration_date,
    card_active_status
) VALUES
('0500024453765740', '00000000050', '747', 'Aniya Von', '2023-03-09', 'Y'),
('0683586198171516', '00000000027', '567', 'Ward Jones', '2025-07-13', 'Y'),
('0923877193247330', '00000000002', '028', 'Enrico Rosenbaum', '2024-08-11', 'Y'),
('0927987108636232', '00000000020', '003', 'Carter Veum', '2024-03-13', 'Y'),
('0982496213629795', '00000000012', '075', 'Maci Robel', '2023-07-07', 'Y'),
('1014086565224350', '00000000044', '640', 'Irving Emard', '2024-01-17', 'Y'),
('1142167692878931', '00000000037', '625', 'Shany Walker', '2023-10-24', 'Y'),
('1561409106491600', '00000000035', '031', 'Angelica Dach', '2025-09-23', 'Y'),
('2745303720002090', '00000000039', '033', 'Aliyah Berge', '2025-09-08', 'Y'),
('2760836797107565', '00000000024', '859', 'Stefanie Dickinson', '2025-02-11', 'Y'),
('2871968252812490', '00000000006', '775', 'Ignacio Douglas', '2025-10-08', 'Y'),
('2940139362300449', '00000000022', '876', 'Allene Brown', '2025-12-28', 'Y'),
('2988091353094312', '00000000004', '795', 'Delbert Parisian', '2023-12-16', 'Y'),
('3260763612337560', '00000000010', '342', 'Maybell Mann', '2023-01-27', 'Y'),
('3766281984155154', '00000000041', '622', 'Lucinda Dach', '2023-04-24', 'Y'),
('3940246016141489', '00000000019', '375', 'Hadley Hamill', '2025-07-23', 'Y'),
('3999169246375885', '00000000003', '317', 'Larry Homenick', '2024-01-10', 'Y'),
('4011500891777367', '00000000013', '390', 'Mariane Fadel', '2024-08-04', 'Y'),
('4385271476627819', '00000000034', '709', 'Faustino Schmidt', '2025-10-06', 'Y'),
('4534784102713951', '00000000036', '644', 'Toney Gerhold', '2024-12-23', 'Y'),
('4859452612877065', '00000000007', '321', 'Cooper Mayert', '2024-12-13', 'Y'),
('5407099850479866', '00000000021', '524', 'Jerrold Maggio', '2023-01-06', 'Y'),
('5656830544981216', '00000000046', '196', 'Cindy Cremin', '2025-06-20', 'Y'),
('5671184478505844', '00000000018', '137', 'Emile White', '2023-09-10', 'Y'),
('5787351228879339', '00000000047', '067', 'Rigoberto Hoeger', '2025-08-23', 'Y'),
('5975117516616077', '00000000042', '426', 'Heather Nienow', '2025-09-19', 'Y'),
('6009619150674526', '00000000005', '021', 'Treva Schowalter', '2025-03-09', 'Y'),
('6349250331648509', '00000000015', '735', 'Aubree Hermann', '2025-06-09', 'Y'),
('6503535181795992', '00000000048', '413', 'Lyric Pacocha', '2025-02-06', 'Y'),
('6509230362553816', '00000000030', '236', 'Layla Ullrich', '2024-06-27', 'Y'),
('6723000463207764', '00000000028', '486', 'Hester Hane', '2024-05-09', 'Y'),
('6727055190616014', '00000000016', '641', 'Carroll Bergstrom', '2024-01-25', 'Y'),
('6832676047698087', '00000000033', '983', 'Bernice Herman', '2025-10-07', 'Y'),
('7026637615032277', '00000000031', '920', 'Lucious O''Connell', '2025-06-08', 'Y'),
('7058267261837752', '00000000043', '401', 'Britney Waters', '2025-08-29', 'Y'),
('7094142751055551', '00000000032', '659', 'Stephany Fisher', '2025-05-19', 'Y'),
('7251508149188883', '00000000029', '717', 'Rickie Daugherty', '2024-06-04', 'Y'),
('7379335634661142', '00000000045', '134', 'Dixie Beier', '2025-07-09', 'Y'),
('7427684863423209', '00000000011', '892', 'Hayden Pfannerstill', '2025-03-12', 'Y'),
('7443870988897530', '00000000038', '708', 'Angela Ankunding', '2023-07-23', 'Y'),
('8040580410348680', '00000000026', '971', 'Marjory Stracke', '2024-12-19', 'Y'),
('8112545834239735', '00000000023', '440', 'Johnson Ruecker', '2025-03-18', 'Y'),
('8262593602473076', '00000000049', '457', 'Immanuel Bednar', '2023-09-17', 'Y'),
('8517866958206008', '00000000014', '955', 'Chelsea Marks', '2025-12-11', 'Y'),
('8931369351894783', '00000000008', '230', 'Kelsie Dicki', '2024-05-20', 'Y'),
('9056297931664011', '00000000025', '931', 'Elliott Howell', '2025-07-10', 'Y'),
('9349107475869214', '00000000017', '218', 'Sigrid Mann', '2025-03-01', 'Y'),
('9501733721429893', '00000000009', '725', 'Melvin Ondricka', '2024-12-27', 'Y'),
('9680294154603697', '00000000001', '045', 'Immanuel Kessler', '2025-05-20', 'Y'),
('9805583408996588', '00000000040', '908', 'Davon Emmerich', '2023-10-27', 'Y');

-- -------------------------------------------------------------------------------------------------
-- 4 of 9 - card_cross_reference: 50 rows from app/data/ASCII/cardxref.txt, 36 data bytes
-- -------------------------------------------------------------------------------------------------
-- Every card lookup in the estate traverses this card-to-customer-to-account resolution
-- table, so it carries three of the six foreign keys and must be seeded last of the four.
--
-- The physical record is 50 bytes of which only 36 carry data; the remaining 14 are filler
-- and are neither a column nor a value. The ASCII fixture holds just the 36 data bytes per
-- row - 50 x (36 + 1) = 1,850 bytes - so nothing is invented to fill the difference.
--
--   Record slices used, as zero-based [start:end) byte ranges:
--       [0:16] xref_card_num      [16:25] xref_cust_id      [25:36] xref_acct_id
-- -------------------------------------------------------------------------------------------------
INSERT INTO card_cross_reference (
    xref_card_num, xref_cust_id, xref_acct_id
) VALUES
('0500024453765740', '000000050', '00000000050'),
('0683586198171516', '000000027', '00000000027'),
('0923877193247330', '000000002', '00000000002'),
('0927987108636232', '000000020', '00000000020'),
('0982496213629795', '000000012', '00000000012'),
('1014086565224350', '000000044', '00000000044'),
('1142167692878931', '000000037', '00000000037'),
('1561409106491600', '000000035', '00000000035'),
('2745303720002090', '000000039', '00000000039'),
('2760836797107565', '000000024', '00000000024'),
('2871968252812490', '000000006', '00000000006'),
('2940139362300449', '000000022', '00000000022'),
('2988091353094312', '000000004', '00000000004'),
('3260763612337560', '000000010', '00000000010'),
('3766281984155154', '000000041', '00000000041'),
('3940246016141489', '000000019', '00000000019'),
('3999169246375885', '000000003', '00000000003'),
('4011500891777367', '000000013', '00000000013'),
('4385271476627819', '000000034', '00000000034'),
('4534784102713951', '000000036', '00000000036'),
('4859452612877065', '000000007', '00000000007'),
('5407099850479866', '000000021', '00000000021'),
('5656830544981216', '000000046', '00000000046'),
('5671184478505844', '000000018', '00000000018'),
('5787351228879339', '000000047', '00000000047'),
('5975117516616077', '000000042', '00000000042'),
('6009619150674526', '000000005', '00000000005'),
('6349250331648509', '000000015', '00000000015'),
('6503535181795992', '000000048', '00000000048'),
('6509230362553816', '000000030', '00000000030'),
('6723000463207764', '000000028', '00000000028'),
('6727055190616014', '000000016', '00000000016'),
('6832676047698087', '000000033', '00000000033'),
('7026637615032277', '000000031', '00000000031'),
('7058267261837752', '000000043', '00000000043'),
('7094142751055551', '000000032', '00000000032'),
('7251508149188883', '000000029', '00000000029'),
('7379335634661142', '000000045', '00000000045'),
('7427684863423209', '000000011', '00000000011'),
('7443870988897530', '000000038', '00000000038'),
('8040580410348680', '000000026', '00000000026'),
('8112545834239735', '000000023', '00000000023'),
('8262593602473076', '000000049', '00000000049'),
('8517866958206008', '000000014', '00000000014'),
('8931369351894783', '000000008', '00000000008'),
('9056297931664011', '000000025', '00000000025'),
('9349107475869214', '000000017', '00000000017'),
('9501733721429893', '000000009', '00000000009'),
('9680294154603697', '000000001', '00000000001'),
('9805583408996588', '000000040', '00000000040');

-- -------------------------------------------------------------------------------------------------
-- 5 of 9 - transaction_type: 7 rows from app/data/ASCII/trantype.txt, 60-byte records
-- -------------------------------------------------------------------------------------------------
-- Reference table with no foreign key in either direction. Descriptions are right-trimmed
-- display text.
--
--   Record slices used, as zero-based [start:end) byte ranges:
--       [0:2] tran_type           [2:52] tran_type_desc (right-trimmed)
--       [52:60] trailing filler, not a column
-- -------------------------------------------------------------------------------------------------
INSERT INTO transaction_type (
    tran_type, tran_type_desc
) VALUES
('01', 'Purchase'),
('02', 'Payment'),
('03', 'Credit'),
('04', 'Authorization'),
('05', 'Refund'),
('06', 'Reversal'),
('07', 'Adjustment');

-- -------------------------------------------------------------------------------------------------
-- 6 of 9 - transaction_category: 18 rows from app/data/ASCII/trancatg.txt, 60-byte records
-- -------------------------------------------------------------------------------------------------
-- Composite key of transaction type code and four-digit category code, both kept as strings
-- so the leading zeros of the category code survive.
--
--   Record slices used, as zero-based [start:end) byte ranges:
--       [0:2] tran_type_cd        [2:6] tran_cat_cd        [6:56] tran_cat_type_desc
--       [56:60] trailing filler, not a column
-- -------------------------------------------------------------------------------------------------
INSERT INTO transaction_category (
    tran_type_cd, tran_cat_cd, tran_cat_type_desc
) VALUES
('01', '0001', 'Regular Sales Draft'),
('01', '0002', 'Regular Cash Advance'),
('01', '0003', 'Convenience Check Debit'),
('01', '0004', 'ATM Cash Advance'),
('01', '0005', 'Interest Amount'),
('02', '0001', 'Cash payment'),
('02', '0002', 'Electronic payment'),
('02', '0003', 'Check payment'),
('03', '0001', 'Credit to Account'),
('03', '0002', 'Credit to Purchase balance'),
('03', '0003', 'Credit to Cash balance'),
('04', '0001', 'Zero dollar authorization'),
('04', '0002', 'Online purchase authorization'),
('04', '0003', 'Travel booking authorization'),
('05', '0001', 'Refund credit'),
('06', '0001', 'Fraud reversal'),
('06', '0002', 'Non-fraud reversal'),
('07', '0001', 'Sales draft credit adjustment');

-- -------------------------------------------------------------------------------------------------
-- 7 of 9 - disclosure_group: 51 rows from app/data/ASCII/discgrp.txt, 50-byte records
-- -------------------------------------------------------------------------------------------------
-- Three complete groups of seventeen rows each, keyed 'A000000000', 'DEFAULT   ' and
-- 'ZEROAPR   '. The two padded keys carry exactly three trailing spaces and are NOT trimmed
-- - see anomaly 4 in the header.
--
-- Why the composition matters, stated exactly: the seeded accounts all carry a blank group
-- identifier and none of these three keys is blank, so every direct read misses and the interest
-- calculation falls back to the DEFAULT group. That fallback path IS reachable from seed data alone
-- and needs no fixture. The zero-rate skip is NOT, even though the ZEROAPR group genuinely supplies
-- zero rates: the fallback lands on DEFAULT, whose rate on the (01, 0001) type and category every
-- seeded balance carries is 15.00, so a seed-only run always computes. Exercising the skip - and
-- likewise the direct group hit - needs an account constructed with one of these keys plus a
-- category balance on the matching type and category. The rows below make that fixture possible;
-- they do not make it unnecessary.
--
--   Record slices used, as zero-based [start:end) byte ranges:
--       [0:10] dis_acct_group_id (trailing blanks preserved)
--       [10:12] dis_tran_type_cd  [12:16] dis_tran_cat_cd  [16:22] dis_int_rate
--       [22:50] trailing filler, not a column
-- -------------------------------------------------------------------------------------------------
INSERT INTO disclosure_group (
    dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate
) VALUES
('A000000000', '01', '0001', 15.00),
('A000000000', '01', '0002', 25.00),
('A000000000', '01', '0003', 25.00),
('A000000000', '01', '0004', 25.00),
('A000000000', '02', '0001', 0.00),
('A000000000', '02', '0002', 0.00),
('A000000000', '02', '0003', 0.00),
('A000000000', '03', '0001', 0.00),
('A000000000', '03', '0002', 0.00),
('A000000000', '03', '0003', 0.00),
('A000000000', '04', '0001', 15.00),
('A000000000', '04', '0002', 15.00),
('A000000000', '04', '0003', 15.00),
('A000000000', '05', '0001', 15.00),
('A000000000', '06', '0001', 15.00),
('A000000000', '06', '0002', 15.00),
('A000000000', '07', '0001', 15.00),
('DEFAULT   ', '01', '0001', 15.00),
('DEFAULT   ', '01', '0002', 25.00),
('DEFAULT   ', '01', '0003', 25.00),
('DEFAULT   ', '01', '0004', 25.00),
('DEFAULT   ', '02', '0001', 0.00),
('DEFAULT   ', '02', '0002', 0.00),
('DEFAULT   ', '02', '0003', 0.00),
('DEFAULT   ', '03', '0001', 0.00),
('DEFAULT   ', '03', '0002', 0.00),
('DEFAULT   ', '03', '0003', 0.00),
('DEFAULT   ', '04', '0001', 15.00),
('DEFAULT   ', '04', '0002', 15.00),
('DEFAULT   ', '04', '0003', 15.00),
('DEFAULT   ', '05', '0001', 15.00),
('DEFAULT   ', '06', '0001', 15.00),
('DEFAULT   ', '06', '0002', 15.00),
('DEFAULT   ', '07', '0001', 0.00),
('ZEROAPR   ', '01', '0001', 0.00),
('ZEROAPR   ', '01', '0002', 0.00),
('ZEROAPR   ', '01', '0003', 0.00),
('ZEROAPR   ', '01', '0004', 0.00),
('ZEROAPR   ', '02', '0001', 0.00),
('ZEROAPR   ', '02', '0002', 0.00),
('ZEROAPR   ', '02', '0003', 0.00),
('ZEROAPR   ', '03', '0001', 0.00),
('ZEROAPR   ', '03', '0002', 0.00),
('ZEROAPR   ', '03', '0003', 0.00),
('ZEROAPR   ', '04', '0001', 0.00),
('ZEROAPR   ', '04', '0002', 0.00),
('ZEROAPR   ', '04', '0003', 0.00),
('ZEROAPR   ', '05', '0001', 0.00),
('ZEROAPR   ', '06', '0001', 0.00),
('ZEROAPR   ', '06', '0002', 0.00),
('ZEROAPR   ', '07', '0001', 0.00);

-- -------------------------------------------------------------------------------------------------
-- 8 of 9 - transaction_category_balance: 50 rows from app/data/ASCII/tcatbal.txt, 50-byte records
-- -------------------------------------------------------------------------------------------------
-- Seeded after account so fk_trancat_balance_account resolves. Composite key of account
-- identifier, transaction type code and category code; all fifty keys are distinct.
--
-- Every balance in this fixture decodes to 0.00, which is the correct starting state for an
-- interest run: the rate lookup and the fallback are exercised, and the computed interest
-- is a function of the seeded rate rather than of a pre-existing balance.
--
--   Record slices used, as zero-based [start:end) byte ranges:
--       [0:11] trancat_acct_id    [11:13] trancat_type_cd  [13:17] trancat_cd
--       [17:28] tran_cat_bal      [28:50] trailing filler, not a column
-- -------------------------------------------------------------------------------------------------
INSERT INTO transaction_category_balance (
    trancat_acct_id, trancat_type_cd, trancat_cd, tran_cat_bal
) VALUES
('00000000001', '01', '0001', 0.00),
('00000000002', '01', '0001', 0.00),
('00000000003', '01', '0001', 0.00),
('00000000004', '01', '0001', 0.00),
('00000000005', '01', '0001', 0.00),
('00000000006', '01', '0001', 0.00),
('00000000007', '01', '0001', 0.00),
('00000000008', '01', '0001', 0.00),
('00000000009', '01', '0001', 0.00),
('00000000010', '01', '0001', 0.00),
('00000000011', '01', '0001', 0.00),
('00000000012', '01', '0001', 0.00),
('00000000013', '01', '0001', 0.00),
('00000000014', '01', '0001', 0.00),
('00000000015', '01', '0001', 0.00),
('00000000016', '01', '0001', 0.00),
('00000000017', '01', '0001', 0.00),
('00000000018', '01', '0001', 0.00),
('00000000019', '01', '0001', 0.00),
('00000000020', '01', '0001', 0.00),
('00000000021', '01', '0001', 0.00),
('00000000022', '01', '0001', 0.00),
('00000000023', '01', '0001', 0.00),
('00000000024', '01', '0001', 0.00),
('00000000025', '01', '0001', 0.00),
('00000000026', '01', '0001', 0.00),
('00000000027', '01', '0001', 0.00),
('00000000028', '01', '0001', 0.00),
('00000000029', '01', '0001', 0.00),
('00000000030', '01', '0001', 0.00),
('00000000031', '01', '0001', 0.00),
('00000000032', '01', '0001', 0.00),
('00000000033', '01', '0001', 0.00),
('00000000034', '01', '0001', 0.00),
('00000000035', '01', '0001', 0.00),
('00000000036', '01', '0001', 0.00),
('00000000037', '01', '0001', 0.00),
('00000000038', '01', '0001', 0.00),
('00000000039', '01', '0001', 0.00),
('00000000040', '01', '0001', 0.00),
('00000000041', '01', '0001', 0.00),
('00000000042', '01', '0001', 0.00),
('00000000043', '01', '0001', 0.00),
('00000000044', '01', '0001', 0.00),
('00000000045', '01', '0001', 0.00),
('00000000046', '01', '0001', 0.00),
('00000000047', '01', '0001', 0.00),
('00000000048', '01', '0001', 0.00),
('00000000049', '01', '0001', 0.00),
('00000000050', '01', '0001', 0.00);

-- -------------------------------------------------------------------------------------------------
-- 9 of 9 - daily_transaction: 300 rows from app/data/ASCII/dailytran.txt, 350-byte records
-- -------------------------------------------------------------------------------------------------
-- The primary posting input, and the reason this seed exists: 250 point-of-sale purchases
-- with positive amounts and 50 operator-originated returns with negative amounts, so both
-- signed directions of the balance computation are exercised by real fixture data.
--
-- The negative amounts are where the negative overpunch table earns its place: a trailing
-- '}' through 'R' encodes the low-order digit and the minus sign together, and each one is
-- decoded to a signed scale-2 literal here.
--
-- dalytran_proc_ts is twenty-six spaces in every row, seeded as twenty-six spaces - see
-- anomaly 5 in the header. dalytran_orig_ts is the same instant in every row.
--
-- No foreign key constrains this table - see anomaly 6 in the header.
--
--   Record slices used, as zero-based [start:end) byte ranges:
--       [0:16] dalytran_id             [16:18] dalytran_type_cd
--       [18:22] dalytran_cat_cd        [22:32] dalytran_source (right-trimmed)
--       [32:132] dalytran_desc         [132:143] dalytran_amt
--       [143:152] dalytran_merchant_id [152:202] dalytran_merchant_name
--       [202:252] dalytran_merchant_city                      [252:262] dalytran_merchant_zip
--       [262:278] dalytran_card_num    [278:304] dalytran_orig_ts
--       [304:330] dalytran_proc_ts (blank on input, preserved)
--       [330:350] trailing filler, not a column
-- -------------------------------------------------------------------------------------------------
INSERT INTO daily_transaction (
    dalytran_id, dalytran_type_cd, dalytran_cat_cd, dalytran_source, dalytran_desc, dalytran_amt,
    dalytran_merchant_id, dalytran_merchant_name, dalytran_merchant_city, dalytran_merchant_zip,
    dalytran_card_num, dalytran_orig_ts, dalytran_proc_ts
) VALUES
('0000000000683580', '01', '0001', 'POS TERM', 'Purchase at Abshire-Lowe', 504.77, '800000000', 'Abshire-Lowe', 'North Enoshaven', '72112', '4859452612877065', '2022-06-10 19:27:53.000000', '                          '),
('0000000001774260', '03', '0001', 'OPERATOR', 'Return item at Nitzsche, Nicolas and Lowe', -919.00, '800000000', 'Nitzsche, Nicolas and Lowe', 'Fidelshire', '53378', '0927987108636232', '2022-06-10 19:27:53.000000', '                          '),
('0000000006292564', '01', '0001', 'POS TERM', 'Purchase at Ernser, Roob and Gleason', 67.88, '800000000', 'Ernser, Roob and Gleason', 'North Makenziemouth', '78487-7965', '6009619150674526', '2022-06-10 19:27:53.000000', '                          '),
('0000000009101861', '01', '0001', 'POS TERM', 'Purchase at Guann LLC', 281.77, '800000000', 'Guann LLC', 'South Lynn', '51508-9166', '8040580410348680', '2022-06-10 19:27:53.000000', '                          '),
('0000000010142252', '01', '0001', 'POS TERM', 'Purchase at Kertzmann-Schoen', 454.66, '800000000', 'Kertzmann-Schoen', 'East Eulahstad', '98754-1089', '5656830544981216', '2022-06-10 19:27:53.000000', '                          '),
('0000000010229018', '01', '0001', 'POS TERM', 'Purchase at Gislason-Medhurst', 849.99, '800000000', 'Gislason-Medhurst', 'Colleenburgh', '23712-2080', '7379335634661142', '2022-06-10 19:27:53.000000', '                          '),
('0000000016259484', '03', '0001', 'OPERATOR', 'Return item at Sipes Inc', -56.77, '800000000', 'Sipes Inc', 'Emilioside', '93329', '4011500891777367', '2022-06-10 19:27:53.000000', '                          '),
('0000000017874199', '01', '0001', 'POS TERM', 'Purchase at Legros Group', 373.66, '800000000', 'Legros Group', 'Carmeloborough', '34849-5127', '8040580410348680', '2022-06-10 19:27:53.000000', '                          '),
('0000000019065428', '03', '0001', 'OPERATOR', 'Return item at Turcotte Group', -535.88, '800000000', 'Turcotte Group', 'Andrewfurt', '41346-3789', '6503535181795992', '2022-06-10 19:27:53.000000', '                          '),
('0000000021711604', '01', '0001', 'POS TERM', 'Purchase at Gleason, Shanahan and Reynolds', 416.11, '800000000', 'Gleason, Shanahan and Reynolds', 'Myrticeport', '21768-0823', '9501733721429893', '2022-06-10 19:27:53.000000', '                          '),
('0000000025430891', '01', '0001', 'POS TERM', 'Purchase at Beatty-Hessel', 94.33, '800000000', 'Beatty-Hessel', 'Simonisport', '52595', '3260763612337560', '2022-06-10 19:27:53.000000', '                          '),
('0000000028097268', '01', '0001', 'POS TERM', 'Purchase at Wolf, Cruickshank and Bode', 250.22, '800000000', 'Wolf, Cruickshank and Bode', 'Fritzchester', '20195-5156', '7094142751055551', '2022-06-10 19:27:53.000000', '                          '),
('0000000030755266', '01', '0001', 'POS TERM', 'Purchase at Ratke LLC', 829.55, '800000000', 'Ratke LLC', 'Brendenfort', '35302-6495', '3766281984155154', '2022-06-10 19:27:53.000000', '                          '),
('0000000032979555', '01', '0001', 'POS TERM', 'Purchase at Treutel-Leffler', 29.44, '800000000', 'Treutel-Leffler', 'New Nicolette', '65014-0045', '6509230362553816', '2022-06-10 19:27:53.000000', '                          '),
('0000000033688127', '01', '0001', 'POS TERM', 'Purchase at Schinner-Steuber', 958.99, '800000000', 'Schinner-Steuber', 'Schmittchester', '50777-5535', '3766281984155154', '2022-06-10 19:27:53.000000', '                          '),
('0000000040455859', '01', '0001', 'POS TERM', 'Purchase at Brekke, Bradtke and Weimann', 715.44, '800000000', 'Brekke, Bradtke and Weimann', 'Veummouth', '18481-5013', '1142167692878931', '2022-06-10 19:27:53.000000', '                          '),
('0000000043636099', '03', '0001', 'OPERATOR', 'Return item at Nader-Bayer', -945.66, '800000000', 'Nader-Bayer', 'Goyetteville', '35324', '2940139362300449', '2022-06-10 19:27:53.000000', '                          '),
('0000000051205286', '01', '0001', 'POS TERM', 'Purchase at Goodwin, Von and Krajcik', 649.33, '800000000', 'Goodwin, Von and Krajcik', 'Ericmouth', '03874', '7094142751055551', '2022-06-10 19:27:53.000000', '                          '),
('0000000054288996', '01', '0001', 'POS TERM', 'Purchase at Cremin and Sons', 502.66, '800000000', 'Cremin and Sons', 'Bartonside', '08677', '4534784102713951', '2022-06-10 19:27:53.000000', '                          '),
('0000000054727064', '01', '0001', 'POS TERM', 'Purchase at McDermott, Lockman and Weimann', 303.11, '800000000', 'McDermott, Lockman and Weimann', 'West Nedra', '05293', '1014086565224350', '2022-06-10 19:27:53.000000', '                          '),
('0000000058866561', '01', '0001', 'POS TERM', 'Purchase at Blick-Rippin', 183.88, '800000000', 'Blick-Rippin', 'East Julien', '87157', '0500024453765740', '2022-06-10 19:27:53.000000', '                          '),
('0000000060921254', '01', '0001', 'POS TERM', 'Purchase at Kihn-Quigley', 779.33, '800000000', 'Kihn-Quigley', 'New Katrine', '42756-0584', '5787351228879339', '2022-06-10 19:27:53.000000', '                          '),
('0000000061394789', '03', '0001', 'OPERATOR', 'Return item at Heaney-Raynor', -70.99, '800000000', 'Heaney-Raynor', 'North Daisy', '28696', '2745303720002090', '2022-06-10 19:27:53.000000', '                          '),
('0000000070754800', '01', '0001', 'POS TERM', 'Purchase at Blick, Kris and Gerlach', 355.11, '800000000', 'Blick, Kris and Gerlach', 'Lake Shawnabury', '65183-0963', '0923877193247330', '2022-06-10 19:27:53.000000', '                          '),
('0000000072220498', '01', '0001', 'POS TERM', 'Purchase at Graham LLC', 660.11, '800000000', 'Graham LLC', 'Ozellaside', '89313-0747', '6349250331648509', '2022-06-10 19:27:53.000000', '                          '),
('0000000084515950', '01', '0001', 'POS TERM', 'Purchase at Bradtke Group', 325.00, '800000000', 'Bradtke Group', 'Gerardland', '63873', '8931369351894783', '2022-06-10 19:27:53.000000', '                          '),
('0000000085824369', '01', '0001', 'POS TERM', 'Purchase at Pollich-Mosciski', 999.77, '800000000', 'Pollich-Mosciski', 'Georgettemouth', '85890', '3999169246375885', '2022-06-10 19:27:53.000000', '                          '),
('0000000095706092', '01', '0001', 'POS TERM', 'Purchase at Swift, Wolf and Goldner', 482.44, '800000000', 'Swift, Wolf and Goldner', 'Keeblerborough', '31923-4503', '8931369351894783', '2022-06-10 19:27:53.000000', '                          '),
('0000000099965527', '01', '0001', 'POS TERM', 'Purchase at Jaskolski-Rolfson', 555.22, '800000000', 'Jaskolski-Rolfson', 'Lake Arjuntown', '90924-2951', '0927987108636232', '2022-06-10 19:27:53.000000', '                          '),
('0000000100915314', '01', '0001', 'POS TERM', 'Purchase at Gislason and Daughters', 356.22, '800000000', 'Gislason and Daughters', 'Torphyville', '09737', '9805583408996588', '2022-06-10 19:27:53.000000', '                          '),
('0000000107748365', '01', '0001', 'POS TERM', 'Purchase at Waelchi and Daughters', 274.00, '800000000', 'Waelchi and Daughters', 'Dickensborough', '86052-1154', '7094142751055551', '2022-06-10 19:27:53.000000', '                          '),
('0000000108402349', '01', '0001', 'POS TERM', 'Purchase at Lynch-Bode', 633.00, '800000000', 'Lynch-Bode', 'New Cieloberg', '85766', '9349107475869214', '2022-06-10 19:27:53.000000', '                          '),
('0000000109340521', '01', '0001', 'POS TERM', 'Purchase at Runte and Sons', 840.55, '800000000', 'Runte and Sons', 'Lake Chesleyfurt', '94215', '7379335634661142', '2022-06-10 19:27:53.000000', '                          '),
('0000000109506921', '01', '0001', 'POS TERM', 'Purchase at Will, Frami and Lynch', 769.55, '800000000', 'Will, Frami and Lynch', 'South Cadefort', '47040-3550', '6832676047698087', '2022-06-10 19:27:53.000000', '                          '),
('0000000111054243', '01', '0001', 'POS TERM', 'Purchase at Pollich and Sons', 948.44, '800000000', 'Pollich and Sons', 'West Burdetteburgh', '51061-7710', '6723000463207764', '2022-06-10 19:27:53.000000', '                          '),
('0000000115716061', '01', '0001', 'POS TERM', 'Purchase at Bednar, Marvin and Kozey', 401.22, '800000000', 'Bednar, Marvin and Kozey', 'Port Marisolshire', '89976-0867', '2940139362300449', '2022-06-10 19:27:53.000000', '                          '),
('0000000130111733', '01', '0001', 'POS TERM', 'Purchase at Rogahn Group', 777.33, '800000000', 'Rogahn Group', 'Keltonton', '18842', '0683586198171516', '2022-06-10 19:27:53.000000', '                          '),
('0000000132831571', '03', '0001', 'OPERATOR', 'Return item at Boehm-Sanford', -215.33, '800000000', 'Boehm-Sanford', 'Winifredville', '93238-7169', '1014086565224350', '2022-06-10 19:27:53.000000', '                          '),
('0000000137879630', '01', '0001', 'POS TERM', 'Purchase at Wiza-Langworth', 46.66, '800000000', 'Wiza-Langworth', 'South Jayson', '83135', '5671184478505844', '2022-06-10 19:27:53.000000', '                          '),
('0000000139910093', '01', '0001', 'POS TERM', 'Purchase at Harris, Johnston and Harris', 570.66, '800000000', 'Harris, Johnston and Harris', 'New Aurelia', '81068', '7251508149188883', '2022-06-10 19:27:53.000000', '                          '),
('0000000142315472', '01', '0001', 'POS TERM', 'Purchase at Kutch-Farrell', 843.66, '800000000', 'Kutch-Farrell', 'Letatown', '39869-9537', '2871968252812490', '2022-06-10 19:27:53.000000', '                          '),
('0000000143386237', '01', '0001', 'POS TERM', 'Purchase at Blanda, Nienow and Hilpert', 559.88, '800000000', 'Blanda, Nienow and Hilpert', 'Leuschkestad', '24074-5513', '8262593602473076', '2022-06-10 19:27:53.000000', '                          '),
('0000000148803688', '01', '0001', 'POS TERM', 'Purchase at Crist Inc', 203.44, '800000000', 'Crist Inc', 'Spencerchester', '18577', '6509230362553816', '2022-06-10 19:27:53.000000', '                          '),
('0000000152467982', '01', '0001', 'POS TERM', 'Purchase at Kreiger and Sons', 168.33, '800000000', 'Kreiger and Sons', 'North Lue', '30616-5176', '2745303720002090', '2022-06-10 19:27:53.000000', '                          '),
('0000000160469204', '01', '0001', 'POS TERM', 'Purchase at Greenfelder-Larson', 864.44, '800000000', 'Greenfelder-Larson', 'New Mertie', '06860', '8040580410348680', '2022-06-10 19:27:53.000000', '                          '),
('0000000166444519', '01', '0001', 'POS TERM', 'Purchase at Wyman, Feest and Moen', 183.22, '800000000', 'Wyman, Feest and Moen', 'Haleyborough', '83262-3068', '9056297931664011', '2022-06-10 19:27:53.000000', '                          '),
('0000000169879332', '01', '0001', 'POS TERM', 'Purchase at Buckridge, Fisher and Schroeder', 256.00, '800000000', 'Buckridge, Fisher and Schroeder', 'Port Kiraport', '29568', '6723000463207764', '2022-06-10 19:27:53.000000', '                          '),
('0000000173069364', '01', '0001', 'POS TERM', 'Purchase at Runte-Schmidt', 985.33, '800000000', 'Runte-Schmidt', 'Krajcikshire', '03491-5716', '8517866958206008', '2022-06-10 19:27:53.000000', '                          '),
('0000000174748684', '01', '0001', 'POS TERM', 'Purchase at McLaughlin-Reichel', 19.99, '800000000', 'McLaughlin-Reichel', 'Rippinville', '32264-6952', '6727055190616014', '2022-06-10 19:27:53.000000', '                          '),
('0000000183226769', '01', '0001', 'POS TERM', 'Purchase at Conroy and Daughters', 907.55, '800000000', 'Conroy and Daughters', 'Greenholtborough', '24059-8704', '8040580410348680', '2022-06-10 19:27:53.000000', '                          '),
('0000000184933166', '01', '0001', 'POS TERM', 'Purchase at Walker LLC', 989.77, '800000000', 'Walker LLC', 'East Tavares', '25508', '7251508149188883', '2022-06-10 19:27:53.000000', '                          '),
('0000000187573156', '01', '0001', 'POS TERM', 'Purchase at Cruickshank and Daughters', 579.77, '800000000', 'Cruickshank and Daughters', 'Bobbieberg', '45382', '0683586198171516', '2022-06-10 19:27:53.000000', '                          '),
('0000000189414937', '03', '0001', 'OPERATOR', 'Return item at Treutel-Douglas', -358.44, '800000000', 'Treutel-Douglas', 'Port Mittiestad', '12880-0185', '6349250331648509', '2022-06-10 19:27:53.000000', '                          '),
('0000000191360674', '01', '0001', 'POS TERM', 'Purchase at Wyman, Breitenberg and Gusikowski', 848.33, '800000000', 'Wyman, Breitenberg and Gusikowski', 'Rosettaberg', '51594-3147', '2745303720002090', '2022-06-10 19:27:53.000000', '                          '),
('0000000192039153', '03', '0001', 'OPERATOR', 'Return item at Smith-Upton', -243.00, '800000000', 'Smith-Upton', 'Vandervortburgh', '15012-1007', '7058267261837752', '2022-06-10 19:27:53.000000', '                          '),
('0000000194189303', '01', '0001', 'POS TERM', 'Purchase at Dickinson and Sons', 59.33, '800000000', 'Dickinson and Sons', 'Port Hunter', '93555-8843', '6727055190616014', '2022-06-10 19:27:53.000000', '                          '),
('0000000196728331', '03', '0001', 'OPERATOR', 'Return item at Hane and Sons', -744.77, '800000000', 'Hane and Sons', 'Erdmanberg', '80151', '7427684863423209', '2022-06-10 19:27:53.000000', '                          '),
('0000000198494663', '01', '0001', 'POS TERM', 'Purchase at Dietrich-Ledner', 385.77, '800000000', 'Dietrich-Ledner', 'Lilastad', '79844-4976', '2745303720002090', '2022-06-10 19:27:53.000000', '                          '),
('0000000201032783', '01', '0001', 'POS TERM', 'Purchase at Heidenreich-Feil', 326.44, '800000000', 'Heidenreich-Feil', 'North Christybury', '32759', '4011500891777367', '2022-06-10 19:27:53.000000', '                          '),
('0000000202217428', '01', '0001', 'POS TERM', 'Purchase at Simonis and Sons', 299.33, '800000000', 'Simonis and Sons', 'Joanieview', '81755-5489', '4385271476627819', '2022-06-10 19:27:53.000000', '                          '),
('0000000202886897', '01', '0001', 'POS TERM', 'Purchase at Ryan-Homenick', 175.88, '800000000', 'Ryan-Homenick', 'North Franciscaside', '14400', '2871968252812490', '2022-06-10 19:27:53.000000', '                          '),
('0000000203305494', '01', '0001', 'POS TERM', 'Purchase at Kunze, Koss and Erdman', 479.22, '800000000', 'Kunze, Koss and Erdman', 'West Lempi', '60316-4620', '5787351228879339', '2022-06-10 19:27:53.000000', '                          '),
('0000000204143988', '01', '0001', 'POS TERM', 'Purchase at Buckridge-Stiedemann', 76.33, '800000000', 'Buckridge-Stiedemann', 'Kuvalishaven', '15327', '3766281984155154', '2022-06-10 19:27:53.000000', '                          '),
('0000000211219588', '01', '0001', 'POS TERM', 'Purchase at Cummings, Nitzsche and Bosco', 553.00, '800000000', 'Cummings, Nitzsche and Bosco', 'Cordeliamouth', '55811', '6723000463207764', '2022-06-10 19:27:53.000000', '                          '),
('0000000218186931', '03', '0001', 'OPERATOR', 'Return item at Reichert and Daughters', -835.11, '800000000', 'Reichert and Daughters', 'Amaliafort', '31060-9178', '8931369351894783', '2022-06-10 19:27:53.000000', '                          '),
('0000000220001505', '01', '0001', 'POS TERM', 'Purchase at Schmeler Group', 929.77, '800000000', 'Schmeler Group', 'New Kennediburgh', '39202-2380', '6509230362553816', '2022-06-10 19:27:53.000000', '                          '),
('0000000220745261', '01', '0001', 'POS TERM', 'Purchase at Swaniawski, Torphy and Bruen', 495.66, '800000000', 'Swaniawski, Torphy and Bruen', 'East Devenborough', '70124', '7026637615032277', '2022-06-10 19:27:53.000000', '                          '),
('0000000223138231', '01', '0001', 'POS TERM', 'Purchase at Prohaska, Grant and Hirthe', 851.33, '800000000', 'Prohaska, Grant and Hirthe', 'Kennyview', '79664', '9501733721429893', '2022-06-10 19:27:53.000000', '                          '),
('0000000224060323', '01', '0001', 'POS TERM', 'Purchase at Kunze and Sons', 343.77, '800000000', 'Kunze and Sons', 'Port Genoveva', '96001', '9349107475869214', '2022-06-10 19:27:53.000000', '                          '),
('0000000226849749', '03', '0001', 'OPERATOR', 'Return item at Smith, Cummings and Medhurst', -428.99, '800000000', 'Smith, Cummings and Medhurst', 'South Adriannaland', '54229-7459', '6009619150674526', '2022-06-10 19:27:53.000000', '                          '),
('0000000232640164', '01', '0001', 'POS TERM', 'Purchase at Blick LLC', 160.99, '800000000', 'Blick LLC', 'East Ali', '23808', '7427684863423209', '2022-06-10 19:27:53.000000', '                          '),
('0000000238329981', '03', '0001', 'OPERATOR', 'Return item at Effertz, Ortiz and Gusikowski', -930.33, '800000000', 'Effertz, Ortiz and Gusikowski', 'Harrisonfurt', '89418-4999', '6509230362553816', '2022-06-10 19:27:53.000000', '                          '),
('0000000241121967', '03', '0001', 'OPERATOR', 'Return item at Kulas and Daughters', -445.55, '800000000', 'Kulas and Daughters', 'Billybury', '68626-4996', '6832676047698087', '2022-06-10 19:27:53.000000', '                          '),
('0000000246307558', '01', '0001', 'POS TERM', 'Purchase at Jacobi and Sons', 816.77, '800000000', 'Jacobi and Sons', 'Lake Hoseaside', '45822', '7443870988897530', '2022-06-10 19:27:53.000000', '                          '),
('0000000246312084', '01', '0001', 'POS TERM', 'Purchase at Weimann-Graham', 848.77, '800000000', 'Weimann-Graham', 'Thielburgh', '41063-5412', '2940139362300449', '2022-06-10 19:27:53.000000', '                          '),
('0000000246549911', '01', '0001', 'POS TERM', 'Purchase at Kulas, Reichert and O''Conner', 339.55, '800000000', 'Kulas, Reichert and O''Conner', 'Travishaven', '59094-4283', '5787351228879339', '2022-06-10 19:27:53.000000', '                          '),
('0000000248557079', '01', '0001', 'POS TERM', 'Purchase at Strosin-Fadel', 905.00, '800000000', 'Strosin-Fadel', 'Krajcikmouth', '25843', '5975117516616077', '2022-06-10 19:27:53.000000', '                          '),
('0000000250062442', '01', '0001', 'POS TERM', 'Purchase at Willms, Abshire and Daugherty', 346.99, '800000000', 'Willms, Abshire and Daugherty', 'Shieldston', '97909-1233', '6009619150674526', '2022-06-10 19:27:53.000000', '                          '),
('0000000252891459', '01', '0001', 'POS TERM', 'Purchase at Nitzsche, Feil and Bergstrom', 944.99, '800000000', 'Nitzsche, Feil and Bergstrom', 'Carriebury', '40432-2594', '6723000463207764', '2022-06-10 19:27:53.000000', '                          '),
('0000000253579636', '01', '0001', 'POS TERM', 'Purchase at D''Amore-Batz', 257.55, '800000000', 'D''Amore-Batz', 'Collierview', '97716', '7443870988897530', '2022-06-10 19:27:53.000000', '                          '),
('0000000253685514', '01', '0001', 'POS TERM', 'Purchase at Von-Schmeler', 496.33, '800000000', 'Von-Schmeler', 'Lake Maximillian', '85711', '4534784102713951', '2022-06-10 19:27:53.000000', '                          '),
('0000000263663553', '01', '0001', 'POS TERM', 'Purchase at Wehner, Turcotte and Nikolaus', 526.11, '800000000', 'Wehner, Turcotte and Nikolaus', 'Fritschfort', '75845-0688', '6503535181795992', '2022-06-10 19:27:53.000000', '                          '),
('0000000263926805', '01', '0001', 'POS TERM', 'Purchase at Batz-Gaylord', 210.33, '800000000', 'Batz-Gaylord', 'Beahanhaven', '00022', '0982496213629795', '2022-06-10 19:27:53.000000', '                          '),
('0000000265935610', '01', '0001', 'POS TERM', 'Purchase at Morar-Cartwright', 461.99, '800000000', 'Morar-Cartwright', 'Lake Sanfordmouth', '93080-1107', '7443870988897530', '2022-06-10 19:27:53.000000', '                          '),
('0000000272698228', '01', '0001', 'POS TERM', 'Purchase at Schultz-Morissette', 457.55, '800000000', 'Schultz-Morissette', 'East Jakaylashire', '84498-8609', '3260763612337560', '2022-06-10 19:27:53.000000', '                          '),
('0000000273137276', '01', '0001', 'POS TERM', 'Purchase at Lowe-Blick', 764.22, '800000000', 'Lowe-Blick', 'Boyerchester', '15468-8924', '6503535181795992', '2022-06-10 19:27:53.000000', '                          '),
('0000000274427056', '03', '0001', 'OPERATOR', 'Return item at Gibson-Maggio', -763.00, '800000000', 'Gibson-Maggio', 'Port Genevieveberg', '92794-6457', '0923877193247330', '2022-06-10 19:27:53.000000', '                          '),
('0000000274596018', '01', '0001', 'POS TERM', 'Purchase at Renner LLC', 40.00, '800000000', 'Renner LLC', 'Sengerport', '73531', '5407099850479866', '2022-06-10 19:27:53.000000', '                          '),
('0000000277916619', '01', '0001', 'POS TERM', 'Purchase at Champlin and Sons', 996.88, '800000000', 'Champlin and Sons', 'North Dale', '85808-4638', '7094142751055551', '2022-06-10 19:27:53.000000', '                          '),
('0000000283702177', '01', '0001', 'POS TERM', 'Purchase at Bradtke-Considine', 89.99, '800000000', 'Bradtke-Considine', 'Geovannyville', '39499-2169', '5975117516616077', '2022-06-10 19:27:53.000000', '                          '),
('0000000292939458', '01', '0001', 'POS TERM', 'Purchase at O''Hara, Ledner and Runte', 49.55, '800000000', 'O''Hara, Ledner and Runte', 'Port Fleta', '42362-4038', '8262593602473076', '2022-06-10 19:27:53.000000', '                          '),
('0000000298764221', '01', '0001', 'POS TERM', 'Purchase at Zboncak, Kohler and Ziemann', 706.11, '800000000', 'Zboncak, Kohler and Ziemann', 'Gilesmouth', '93998-8946', '7443870988897530', '2022-06-10 19:27:53.000000', '                          '),
('0000000298806396', '01', '0001', 'POS TERM', 'Purchase at Powlowski-Greenholt', 936.22, '800000000', 'Powlowski-Greenholt', 'Naderfort', '19262-4706', '7058267261837752', '2022-06-10 19:27:53.000000', '                          '),
('0000000307523903', '03', '0001', 'OPERATOR', 'Return item at Hamill, Sawayn and O''Conner', -585.44, '800000000', 'Hamill, Sawayn and O''Conner', 'Vonview', '83262', '5787351228879339', '2022-06-10 19:27:53.000000', '                          '),
('0000000312054308', '01', '0001', 'POS TERM', 'Purchase at Bogan LLC', 717.00, '800000000', 'Bogan LLC', 'Josiahhaven', '59167', '7379335634661142', '2022-06-10 19:27:53.000000', '                          '),
('0000000312439873', '01', '0001', 'POS TERM', 'Purchase at Rowe and Daughters', 745.33, '800000000', 'Rowe and Daughters', 'New Adriannamouth', '89172-7486', '7443870988897530', '2022-06-10 19:27:53.000000', '                          '),
('0000000312675172', '01', '0001', 'POS TERM', 'Purchase at Hermiston Inc', 728.77, '800000000', 'Hermiston Inc', 'Port Bennyburgh', '34656', '8931369351894783', '2022-06-10 19:27:53.000000', '                          '),
('0000000313527007', '01', '0001', 'POS TERM', 'Purchase at Ebert-Grimes', 948.22, '800000000', 'Ebert-Grimes', 'New Kelleyton', '51492-3272', '8112545834239735', '2022-06-10 19:27:53.000000', '                          '),
('0000000325686503', '01', '0001', 'POS TERM', 'Purchase at Cruickshank-Marvin', 569.99, '800000000', 'Cruickshank-Marvin', 'Russelshire', '66858', '0923877193247330', '2022-06-10 19:27:53.000000', '                          '),
('0000000328781772', '01', '0001', 'POS TERM', 'Purchase at Hayes and Daughters', 859.44, '800000000', 'Hayes and Daughters', 'Beahanville', '08781', '9056297931664011', '2022-06-10 19:27:53.000000', '                          '),
('0000000329446511', '01', '0001', 'POS TERM', 'Purchase at Ernser, Ward and Lehner', 667.55, '800000000', 'Ernser, Ward and Lehner', 'Lake Rita', '78140-9470', '8040580410348680', '2022-06-10 19:27:53.000000', '                          '),
('0000000329724245', '01', '0001', 'POS TERM', 'Purchase at Reichel Group', 14.00, '800000000', 'Reichel Group', 'Port Romanfort', '95843', '0500024453765740', '2022-06-10 19:27:53.000000', '                          '),
('0000000338128146', '03', '0001', 'OPERATOR', 'Return item at Terry-Mertz', -742.99, '800000000', 'Terry-Mertz', 'Enidview', '31259', '7094142751055551', '2022-06-10 19:27:53.000000', '                          '),
('0000000341155503', '01', '0001', 'POS TERM', 'Purchase at Parker-Erdman', 990.88, '800000000', 'Parker-Erdman', 'New Khalid', '72240', '8517866958206008', '2022-06-10 19:27:53.000000', '                          '),
('0000000341634875', '01', '0001', 'POS TERM', 'Purchase at Medhurst, Bogisich and Schmeler', 997.88, '800000000', 'Medhurst, Bogisich and Schmeler', 'Dickensport', '29931-9313', '2760836797107565', '2022-06-10 19:27:53.000000', '                          '),
('0000000357518499', '03', '0001', 'OPERATOR', 'Return item at Willms-Beier', -852.33, '800000000', 'Willms-Beier', 'Nathanfurt', '70715-7333', '7251508149188883', '2022-06-10 19:27:53.000000', '                          '),
('0000000358543876', '01', '0001', 'POS TERM', 'Purchase at Zulauf Group', 552.77, '800000000', 'Zulauf Group', 'Schowalterland', '26981', '5975117516616077', '2022-06-10 19:27:53.000000', '                          '),
('0000000361866674', '01', '0001', 'POS TERM', 'Purchase at Mayer and Daughters', 446.00, '800000000', 'Mayer and Daughters', 'North Keeley', '40519', '7427684863423209', '2022-06-10 19:27:53.000000', '                          '),
('0000000366513257', '01', '0001', 'POS TERM', 'Purchase at Klein, Buckridge and Johnson', 965.55, '800000000', 'Klein, Buckridge and Johnson', 'Shieldsbury', '79412-9462', '0927987108636232', '2022-06-10 19:27:53.000000', '                          '),
('0000000373973344', '01', '0001', 'POS TERM', 'Purchase at Wehner LLC', 958.33, '800000000', 'Wehner LLC', 'South Harmonmouth', '92575', '5671184478505844', '2022-06-10 19:27:53.000000', '                          '),
('0000000375247552', '01', '0001', 'POS TERM', 'Purchase at Guann Group', 115.11, '800000000', 'Guann Group', 'Port Grant', '76360-6457', '3999169246375885', '2022-06-10 19:27:53.000000', '                          '),
('0000000378710702', '03', '0001', 'OPERATOR', 'Return item at Wilderman, Koepp and Ledner', -344.77, '800000000', 'Wilderman, Koepp and Ledner', 'Wuckerthaven', '29965', '7379335634661142', '2022-06-10 19:27:53.000000', '                          '),
('0000000379084859', '03', '0001', 'OPERATOR', 'Return item at Lebsack-Treutel', -75.22, '800000000', 'Lebsack-Treutel', 'Kennedyside', '66077-1463', '6723000463207764', '2022-06-10 19:27:53.000000', '                          '),
('0000000380632461', '01', '0001', 'POS TERM', 'Purchase at Beahan, Little and Sanford', 428.33, '800000000', 'Beahan, Little and Sanford', 'East Ebonyville', '17826-0999', '4859452612877065', '2022-06-10 19:27:53.000000', '                          '),
('0000000382018782', '01', '0001', 'POS TERM', 'Purchase at Hackett-Kautzer', 884.99, '800000000', 'Hackett-Kautzer', 'East Cristopherfurt', '10894-9358', '5656830544981216', '2022-06-10 19:27:53.000000', '                          '),
('0000000382291356', '01', '0001', 'POS TERM', 'Purchase at Jacobi and Daughters', 860.77, '800000000', 'Jacobi and Daughters', 'Carterland', '70592-5640', '5671184478505844', '2022-06-10 19:27:53.000000', '                          '),
('0000000392772234', '01', '0001', 'POS TERM', 'Purchase at Williamson LLC', 351.66, '800000000', 'Williamson LLC', 'Runteville', '18400-6845', '4385271476627819', '2022-06-10 19:27:53.000000', '                          '),
('0000000397282953', '03', '0001', 'OPERATOR', 'Return item at Ankunding Group', -396.22, '800000000', 'Ankunding Group', 'Adrainton', '59712-6451', '2871968252812490', '2022-06-10 19:27:53.000000', '                          '),
('0000000399296572', '01', '0001', 'POS TERM', 'Purchase at McGlynn Inc', 254.77, '800000000', 'McGlynn Inc', 'New Berenice', '76608', '6009619150674526', '2022-06-10 19:27:53.000000', '                          '),
('0000000400022505', '01', '0001', 'POS TERM', 'Purchase at Klocko LLC', 385.44, '800000000', 'Klocko LLC', 'Taniatown', '25662', '1014086565224350', '2022-06-10 19:27:53.000000', '                          '),
('0000000400762013', '01', '0001', 'POS TERM', 'Purchase at Will-Murazik', 617.00, '800000000', 'Will-Murazik', 'New Estefania', '36903-3350', '2760836797107565', '2022-06-10 19:27:53.000000', '                          '),
('0000000402032668', '03', '0001', 'OPERATOR', 'Return item at Torphy, Collins and Witting', -756.66, '800000000', 'Torphy, Collins and Witting', 'Lake Augusttown', '06644', '3766281984155154', '2022-06-10 19:27:53.000000', '                          '),
('0000000407178785', '01', '0001', 'POS TERM', 'Purchase at Cole-Wyman', 949.77, '800000000', 'Cole-Wyman', 'Olenmouth', '47296', '3940246016141489', '2022-06-10 19:27:53.000000', '                          '),
('0000000407637739', '03', '0001', 'OPERATOR', 'Return item at Price LLC', -501.44, '800000000', 'Price LLC', 'New Annabell', '91216', '5975117516616077', '2022-06-10 19:27:53.000000', '                          '),
('0000000412515011', '01', '0001', 'POS TERM', 'Purchase at Wehner-Ebert', 214.77, '800000000', 'Wehner-Ebert', 'Ednaville', '70885', '5975117516616077', '2022-06-10 19:27:53.000000', '                          '),
('0000000415671623', '01', '0001', 'POS TERM', 'Purchase at Kshlerin, Schulist and Oberbrunner', 0.99, '800000000', 'Kshlerin, Schulist and Oberbrunner', 'Dallinmouth', '19897-9097', '1561409106491600', '2022-06-10 19:27:53.000000', '                          '),
('0000000416848414', '01', '0001', 'POS TERM', 'Purchase at Medhurst-Feeney', 995.22, '800000000', 'Medhurst-Feeney', 'New Terrance', '87377', '6832676047698087', '2022-06-10 19:27:53.000000', '                          '),
('0000000420768809', '01', '0001', 'POS TERM', 'Purchase at Bartell-Rempel', 674.99, '800000000', 'Bartell-Rempel', 'Alexanderport', '08405', '7058267261837752', '2022-06-10 19:27:53.000000', '                          '),
('0000000424689871', '01', '0001', 'POS TERM', 'Purchase at Rempel and Daughters', 648.11, '800000000', 'Rempel and Daughters', 'Aliyachester', '08642', '5787351228879339', '2022-06-10 19:27:53.000000', '                          '),
('0000000429557611', '01', '0001', 'POS TERM', 'Purchase at Pagac, Funk and Kiehn', 545.66, '800000000', 'Pagac, Funk and Kiehn', 'Krajcikshire', '32088-0940', '0982496213629795', '2022-06-10 19:27:53.000000', '                          '),
('0000000432231260', '03', '0001', 'OPERATOR', 'Return item at Schuster-Bashirian', -962.77, '800000000', 'Schuster-Bashirian', 'New Gageton', '47405-2362', '8262593602473076', '2022-06-10 19:27:53.000000', '                          '),
('0000000433607101', '01', '0001', 'POS TERM', 'Purchase at VonRueden Inc', 851.22, '800000000', 'VonRueden Inc', 'Lake Gailland', '82720-3055', '9501733721429893', '2022-06-10 19:27:53.000000', '                          '),
('0000000434343718', '01', '0001', 'POS TERM', 'Purchase at Vandervort-McClure', 793.22, '800000000', 'Vandervort-McClure', 'Kaydenborough', '73288-4151', '6832676047698087', '2022-06-10 19:27:53.000000', '                          '),
('0000000435144487', '01', '0001', 'POS TERM', 'Purchase at Pacocha, Goyette and Leuschke', 408.88, '800000000', 'Pacocha, Goyette and Leuschke', 'Gutkowskiport', '64919-4953', '8112545834239735', '2022-06-10 19:27:53.000000', '                          '),
('0000000445507761', '01', '0001', 'POS TERM', 'Purchase at Rice, Luettgen and Aufderhar', 129.33, '800000000', 'Rice, Luettgen and Aufderhar', 'O''Reillychester', '75844', '3940246016141489', '2022-06-10 19:27:53.000000', '                          '),
('0000000446945803', '01', '0001', 'POS TERM', 'Purchase at Yost and Daughters', 241.22, '800000000', 'Yost and Daughters', 'Lake Manley', '52896-0448', '3940246016141489', '2022-06-10 19:27:53.000000', '                          '),
('0000000450622695', '01', '0001', 'POS TERM', 'Purchase at Schmitt, Kohler and Skiles', 28.33, '800000000', 'Schmitt, Kohler and Skiles', 'Farrellhaven', '00796', '2988091353094312', '2022-06-10 19:27:53.000000', '                          '),
('0000000458331136', '01', '0001', 'POS TERM', 'Purchase at Howe, Rippin and Watsica', 437.11, '800000000', 'Howe, Rippin and Watsica', 'West Kianachester', '75201', '2988091353094312', '2022-06-10 19:27:53.000000', '                          '),
('0000000462765346', '01', '0001', 'POS TERM', 'Purchase at Marquardt, Ward and Brekke', 587.11, '800000000', 'Marquardt, Ward and Brekke', 'Lake Nataliastad', '61706-7915', '0982496213629795', '2022-06-10 19:27:53.000000', '                          '),
('0000000474475283', '01', '0001', 'POS TERM', 'Purchase at Pouros Inc', 174.66, '800000000', 'Pouros Inc', 'East Jerald', '35802', '5407099850479866', '2022-06-10 19:27:53.000000', '                          '),
('0000000475609951', '01', '0001', 'POS TERM', 'Purchase at Predovic-Deckow', 913.88, '800000000', 'Predovic-Deckow', 'West Gunnar', '46493-9443', '5656830544981216', '2022-06-10 19:27:53.000000', '                          '),
('0000000475746885', '01', '0001', 'POS TERM', 'Purchase at Adams-Watsica', 967.44, '800000000', 'Adams-Watsica', 'Ratkemouth', '55474-0373', '0500024453765740', '2022-06-10 19:27:53.000000', '                          '),
('0000000482016448', '01', '0001', 'POS TERM', 'Purchase at Becker Group', 310.00, '800000000', 'Becker Group', 'Sanfordhaven', '50166', '2760836797107565', '2022-06-10 19:27:53.000000', '                          '),
('0000000482116790', '01', '0001', 'POS TERM', 'Purchase at Erdman-Cartwright', 820.11, '800000000', 'Erdman-Cartwright', 'Lake Lavonne', '06930', '4385271476627819', '2022-06-10 19:27:53.000000', '                          '),
('0000000486159054', '01', '0001', 'POS TERM', 'Purchase at Christiansen-Jacobi', 319.88, '800000000', 'Christiansen-Jacobi', 'West Conor', '53124', '0683586198171516', '2022-06-10 19:27:53.000000', '                          '),
('0000000490043047', '01', '0001', 'POS TERM', 'Purchase at Yost-Kertzmann', 584.88, '800000000', 'Yost-Kertzmann', 'Lake Josh', '59545', '5407099850479866', '2022-06-10 19:27:53.000000', '                          '),
('0000000496711357', '01', '0001', 'POS TERM', 'Purchase at Koepp-Wiegand', 161.99, '800000000', 'Koepp-Wiegand', 'Cristianstad', '23187-0329', '2760836797107565', '2022-06-10 19:27:53.000000', '                          '),
('0000000497995808', '01', '0001', 'POS TERM', 'Purchase at Beier and Daughters', 649.00, '800000000', 'Beier and Daughters', 'Norbertstad', '48162-5331', '9501733721429893', '2022-06-10 19:27:53.000000', '                          '),
('0000000498548061', '01', '0001', 'POS TERM', 'Purchase at Bernier and Daughters', 209.00, '800000000', 'Bernier and Daughters', 'Lake Rosefurt', '83724-5529', '6727055190616014', '2022-06-10 19:27:53.000000', '                          '),
('0000000498615524', '03', '0001', 'OPERATOR', 'Return item at Powlowski LLC', -907.00, '800000000', 'Powlowski LLC', 'New Aprilstad', '57040-5493', '9056297931664011', '2022-06-10 19:27:53.000000', '                          '),
('0000000498857207', '01', '0001', 'POS TERM', 'Purchase at Friesen, Murphy and Beier', 293.44, '800000000', 'Friesen, Murphy and Beier', 'Dallasberg', '02275', '3260763612337560', '2022-06-10 19:27:53.000000', '                          '),
('0000000499424514', '01', '0001', 'POS TERM', 'Purchase at Schumm-Stamm', 952.55, '800000000', 'Schumm-Stamm', 'Imogeneburgh', '12605', '4534784102713951', '2022-06-10 19:27:53.000000', '                          '),
('0000000500479019', '01', '0001', 'POS TERM', 'Purchase at Hilpert, Purdy and Kilback', 654.99, '800000000', 'Hilpert, Purdy and Kilback', 'Schummshire', '49771-2616', '1014086565224350', '2022-06-10 19:27:53.000000', '                          '),
('0000000500885895', '03', '0001', 'OPERATOR', 'Return item at Klein-Stark', -579.88, '800000000', 'Klein-Stark', 'West Arlo', '35478', '4859452612877065', '2022-06-10 19:27:53.000000', '                          '),
('0000000502617711', '01', '0001', 'POS TERM', 'Purchase at Klocko LLC', 955.11, '800000000', 'Klocko LLC', 'Winonaland', '07626', '4859452612877065', '2022-06-10 19:27:53.000000', '                          '),
('0000000503557384', '01', '0001', 'POS TERM', 'Purchase at Casper Group', 81.44, '800000000', 'Casper Group', 'Millsborough', '57690', '9680294154603697', '2022-06-10 19:27:53.000000', '                          '),
('0000000504099546', '01', '0001', 'POS TERM', 'Purchase at Jewess, Sauer and Runolfsson', 655.11, '800000000', 'Jewess, Sauer and Runolfsson', 'Parkermouth', '00391', '0923877193247330', '2022-06-10 19:27:53.000000', '                          '),
('0000000508429766', '01', '0001', 'POS TERM', 'Purchase at Kassulke, Reynolds and Runolfsson', 534.11, '800000000', 'Kassulke, Reynolds and Runolfsson', 'Seamuston', '13633-3156', '7094142751055551', '2022-06-10 19:27:53.000000', '                          '),
('0000000519771423', '01', '0001', 'POS TERM', 'Purchase at Herman, Swift and Nikolaus', 670.00, '800000000', 'Herman, Swift and Nikolaus', 'Durganport', '31302', '6723000463207764', '2022-06-10 19:27:53.000000', '                          '),
('0000000519935575', '01', '0001', 'POS TERM', 'Purchase at Gaylord, Kuhlman and Reichert', 164.99, '800000000', 'Gaylord, Kuhlman and Reichert', 'West Reillymouth', '05765', '0923877193247330', '2022-06-10 19:27:53.000000', '                          '),
('0000000522130011', '01', '0001', 'POS TERM', 'Purchase at D''Amore, Conroy and Wilkinson', 699.44, '800000000', 'D''Amore, Conroy and Wilkinson', 'East Larissatown', '12025-5362', '7251508149188883', '2022-06-10 19:27:53.000000', '                          '),
('0000000523110055', '01', '0001', 'POS TERM', 'Purchase at Purdy-King', 736.88, '800000000', 'Purdy-King', 'Port Maximusshire', '95835', '7379335634661142', '2022-06-10 19:27:53.000000', '                          '),
('0000000528007115', '03', '0001', 'OPERATOR', 'Return item at Trantow-Sipes', -113.11, '800000000', 'Trantow-Sipes', 'Reubentown', '12694', '5671184478505844', '2022-06-10 19:27:53.000000', '                          '),
('0000000532120892', '03', '0001', 'OPERATOR', 'Return item at Frami-Hyatt', -70.77, '800000000', 'Frami-Hyatt', 'Jamilside', '14372-1790', '9680294154603697', '2022-06-10 19:27:53.000000', '                          '),
('0000000539225404', '03', '0001', 'OPERATOR', 'Return item at Hamill, Blick and Kling', -372.00, '800000000', 'Hamill, Blick and Kling', 'Sporerview', '52731', '8040580410348680', '2022-06-10 19:27:53.000000', '                          '),
('0000000540034453', '01', '0001', 'POS TERM', 'Purchase at Baumbach-Mohr', 202.44, '800000000', 'Baumbach-Mohr', 'Kovacekhaven', '88690-1442', '6832676047698087', '2022-06-10 19:27:53.000000', '                          '),
('0000000540260014', '03', '0001', 'OPERATOR', 'Return item at McCullough-Gottlieb', -880.22, '800000000', 'McCullough-Gottlieb', 'Clarissaside', '80982-4072', '3999169246375885', '2022-06-10 19:27:53.000000', '                          '),
('0000000544248006', '01', '0001', 'POS TERM', 'Purchase at Mann Inc', 659.44, '800000000', 'Mann Inc', 'Koeppton', '40246-5957', '1561409106491600', '2022-06-10 19:27:53.000000', '                          '),
('0000000547488622', '01', '0001', 'POS TERM', 'Purchase at Reichert, Kemmer and Funk', 403.66, '800000000', 'Reichert, Kemmer and Funk', 'North Destinibury', '84879', '4011500891777367', '2022-06-10 19:27:53.000000', '                          '),
('0000000549364593', '01', '0001', 'POS TERM', 'Purchase at Waters, Considine and Borer', 195.44, '800000000', 'Waters, Considine and Borer', 'Lake Lillianaville', '60590-4967', '8517866958206008', '2022-06-10 19:27:53.000000', '                          '),
('0000000550089732', '03', '0001', 'OPERATOR', 'Return item at Sanford-Gleichner', -537.44, '800000000', 'Sanford-Gleichner', 'Dorisberg', '29319', '3260763612337560', '2022-06-10 19:27:53.000000', '                          '),
('0000000550860982', '01', '0001', 'POS TERM', 'Purchase at Bogan LLC', 850.22, '800000000', 'Bogan LLC', 'Lilyberg', '56494', '6349250331648509', '2022-06-10 19:27:53.000000', '                          '),
('0000000554096178', '01', '0001', 'POS TERM', 'Purchase at Turner, Dickinson and Grant', 722.33, '800000000', 'Turner, Dickinson and Grant', 'Lucianofort', '91006-9381', '6503535181795992', '2022-06-10 19:27:53.000000', '                          '),
('0000000555363230', '01', '0001', 'POS TERM', 'Purchase at Metz, Blanda and Homenick', 484.99, '800000000', 'Metz, Blanda and Homenick', 'North Linwood', '41398', '9680294154603697', '2022-06-10 19:27:53.000000', '                          '),
('0000000561673599', '01', '0001', 'POS TERM', 'Purchase at Bergnaum and Sons', 430.55, '800000000', 'Bergnaum and Sons', 'Leuschkeberg', '87213-5400', '5656830544981216', '2022-06-10 19:27:53.000000', '                          '),
('0000000564257675', '01', '0001', 'POS TERM', 'Purchase at Jast LLC', 623.11, '800000000', 'Jast LLC', 'Lednermouth', '82698', '6503535181795992', '2022-06-10 19:27:53.000000', '                          '),
('0000000569807281', '03', '0001', 'OPERATOR', 'Return item at Kiehn, Russel and Schaefer', -998.33, '800000000', 'Kiehn, Russel and Schaefer', 'New Loren', '41813', '9349107475869214', '2022-06-10 19:27:53.000000', '                          '),
('0000000570013846', '01', '0001', 'POS TERM', 'Purchase at Parker, Pfannerstill and Donnelly', 352.33, '800000000', 'Parker, Pfannerstill and Donnelly', 'Mohrport', '18642-6726', '9349107475869214', '2022-06-10 19:27:53.000000', '                          '),
('0000000570880433', '01', '0001', 'POS TERM', 'Purchase at Kuvalis-Leffler', 161.77, '800000000', 'Kuvalis-Leffler', 'East Tiffany', '09856-1749', '6009619150674526', '2022-06-10 19:27:53.000000', '                          '),
('0000000573732499', '01', '0001', 'POS TERM', 'Purchase at Ortiz, Langworth and Feeney', 237.44, '800000000', 'Ortiz, Langworth and Feeney', 'New Deonte', '32314', '9805583408996588', '2022-06-10 19:27:53.000000', '                          '),
('0000000575834812', '01', '0001', 'POS TERM', 'Purchase at Ritchie and Sons', 689.88, '800000000', 'Ritchie and Sons', 'O''Haraberg', '45500-2911', '7379335634661142', '2022-06-10 19:27:53.000000', '                          '),
('0000000576344938', '01', '0001', 'POS TERM', 'Purchase at Smith and Sons', 425.11, '800000000', 'Smith and Sons', 'Lake Dallinfurt', '26352-2649', '2745303720002090', '2022-06-10 19:27:53.000000', '                          '),
('0000000577165878', '01', '0001', 'POS TERM', 'Purchase at Macejkovic-Mohr', 621.22, '800000000', 'Macejkovic-Mohr', 'Trantowberg', '59291', '7427684863423209', '2022-06-10 19:27:53.000000', '                          '),
('0000000577826814', '03', '0001', 'OPERATOR', 'Return item at DuBuque, Wuckert and Mraz', -47.88, '800000000', 'DuBuque, Wuckert and Mraz', 'South Lurline', '37081', '0500024453765740', '2022-06-10 19:27:53.000000', '                          '),
('0000000585883106', '01', '0001', 'POS TERM', 'Purchase at Heathcote Inc', 435.44, '800000000', 'Heathcote Inc', 'Marlenemouth', '72239-5071', '6009619150674526', '2022-06-10 19:27:53.000000', '                          '),
('0000000588642606', '01', '0001', 'POS TERM', 'Purchase at Marks and Daughters', 568.88, '800000000', 'Marks and Daughters', 'New Berryton', '84059-0476', '7058267261837752', '2022-06-10 19:27:53.000000', '                          '),
('0000000598262041', '01', '0001', 'POS TERM', 'Purchase at Terry-Rohan', 39.55, '800000000', 'Terry-Rohan', 'Rempelview', '34789-4591', '1561409106491600', '2022-06-10 19:27:53.000000', '                          '),
('0000000600564499', '01', '0001', 'POS TERM', 'Purchase at Schmeler, Crooks and Barton', 736.33, '800000000', 'Schmeler, Crooks and Barton', 'Hodkiewiczville', '09147-9690', '2988091353094312', '2022-06-10 19:27:53.000000', '                          '),
('0000000601274842', '01', '0001', 'POS TERM', 'Purchase at Yost, Hoppe and Heathcote', 744.11, '800000000', 'Yost, Hoppe and Heathcote', 'Heathermouth', '15216-7718', '7251508149188883', '2022-06-10 19:27:53.000000', '                          '),
('0000000601496057', '01', '0001', 'POS TERM', 'Purchase at Ortiz-Douglas', 900.22, '800000000', 'Ortiz-Douglas', 'Rosaleemouth', '64903', '6509230362553816', '2022-06-10 19:27:53.000000', '                          '),
('0000000603071214', '01', '0001', 'POS TERM', 'Purchase at Schinner-Feeney', 166.99, '800000000', 'Schinner-Feeney', 'North Wilfred', '36776-9392', '7251508149188883', '2022-06-10 19:27:53.000000', '                          '),
('0000000605048564', '01', '0001', 'POS TERM', 'Purchase at Schamberger, O''Reilly and Wintheiser', 95.99, '800000000', 'Schamberger, O''Reilly and Wintheiser', 'West Bernadineland', '74526', '8931369351894783', '2022-06-10 19:27:53.000000', '                          '),
('0000000606140907', '01', '0001', 'POS TERM', 'Purchase at Yost-Schaefer', 598.33, '800000000', 'Yost-Schaefer', 'Barrowsfurt', '88050', '2940139362300449', '2022-06-10 19:27:53.000000', '                          '),
('0000000606716618', '01', '0001', 'POS TERM', 'Purchase at Barton, Schmidt and Hodkiewicz', 715.66, '800000000', 'Barton, Schmidt and Hodkiewicz', 'Sethtown', '63152', '0982496213629795', '2022-06-10 19:27:53.000000', '                          '),
('0000000606830191', '03', '0001', 'OPERATOR', 'Return item at Bogisich-O''Connell', -71.66, '800000000', 'Bogisich-O''Connell', 'New Bennie', '00871', '3940246016141489', '2022-06-10 19:27:53.000000', '                          '),
('0000000614267358', '03', '0001', 'OPERATOR', 'Return item at Gibson-Abbott', -132.88, '800000000', 'Gibson-Abbott', 'New Kodyton', '82751', '1142167692878931', '2022-06-10 19:27:53.000000', '                          '),
('0000000618712102', '01', '0001', 'POS TERM', 'Purchase at Hahn-Lueilwitz', 21.11, '800000000', 'Hahn-Lueilwitz', 'Deondreville', '55366-2298', '8112545834239735', '2022-06-10 19:27:53.000000', '                          '),
('0000000621178666', '01', '0001', 'POS TERM', 'Purchase at Orn-Dach', 639.22, '800000000', 'Orn-Dach', 'Maxineville', '39263-8392', '1561409106491600', '2022-06-10 19:27:53.000000', '                          '),
('0000000624335286', '01', '0001', 'POS TERM', 'Purchase at Crona, Turner and Hane', 598.44, '800000000', 'Crona, Turner and Hane', 'Glenton', '32966-6359', '9680294154603697', '2022-06-10 19:27:53.000000', '                          '),
('0000000627601011', '03', '0001', 'OPERATOR', 'Return item at Stokes Inc', -538.22, '800000000', 'Stokes Inc', 'Koeppfurt', '91991', '7443870988897530', '2022-06-10 19:27:53.000000', '                          '),
('0000000628524597', '01', '0001', 'POS TERM', 'Purchase at Wiegand-Weimann', 269.22, '800000000', 'Wiegand-Weimann', 'East Arnomouth', '21317', '1014086565224350', '2022-06-10 19:27:53.000000', '                          '),
('0000000634329004', '01', '0001', 'POS TERM', 'Purchase at Durgan-Nader', 89.11, '800000000', 'Durgan-Nader', 'Robynmouth', '39869', '5407099850479866', '2022-06-10 19:27:53.000000', '                          '),
('0000000635121182', '01', '0001', 'POS TERM', 'Purchase at Douglas and Daughters', 629.55, '800000000', 'Douglas and Daughters', 'Yvettetown', '03935', '6832676047698087', '2022-06-10 19:27:53.000000', '                          '),
('0000000641694180', '01', '0001', 'POS TERM', 'Purchase at Schumm-Reinger', 554.22, '800000000', 'Schumm-Reinger', 'Antoniatown', '52581', '3999169246375885', '2022-06-10 19:27:53.000000', '                          '),
('0000000643758597', '01', '0001', 'POS TERM', 'Purchase at Bins Inc', 744.22, '800000000', 'Bins Inc', 'Port Georgianaside', '24098-5082', '7058267261837752', '2022-06-10 19:27:53.000000', '                          '),
('0000000647754915', '01', '0001', 'POS TERM', 'Purchase at Gerlach-Jaskolski', 718.55, '800000000', 'Gerlach-Jaskolski', 'New Kalistad', '51103-7932', '2871968252812490', '2022-06-10 19:27:53.000000', '                          '),
('0000000652713581', '01', '0001', 'POS TERM', 'Purchase at Pagac-Hackett', 633.55, '800000000', 'Pagac-Hackett', 'New Hans', '35901', '6727055190616014', '2022-06-10 19:27:53.000000', '                          '),
('0000000667157384', '01', '0001', 'POS TERM', 'Purchase at Stamm and Sons', 952.77, '800000000', 'Stamm and Sons', 'Hayleybury', '33611', '5671184478505844', '2022-06-10 19:27:53.000000', '                          '),
('0000000669905307', '01', '0001', 'POS TERM', 'Purchase at Schneider and Daughters', 425.00, '800000000', 'Schneider and Daughters', 'Blandafurt', '74767-7107', '6727055190616014', '2022-06-10 19:27:53.000000', '                          '),
('0000000672061881', '03', '0001', 'OPERATOR', 'Return item at Rippin-Gibson', -435.00, '800000000', 'Rippin-Gibson', 'Hansenstad', '16980-8789', '5656830544981216', '2022-06-10 19:27:53.000000', '                          '),
('0000000672573296', '03', '0001', 'OPERATOR', 'Return item at Veum-Treutel', -710.66, '800000000', 'Veum-Treutel', 'Amelybury', '60686', '8517866958206008', '2022-06-10 19:27:53.000000', '                          '),
('0000000673250360', '01', '0001', 'POS TERM', 'Purchase at Ebert-Gleason', 191.00, '800000000', 'Ebert-Gleason', 'Altenwerthbury', '89085', '7427684863423209', '2022-06-10 19:27:53.000000', '                          '),
('0000000676149118', '01', '0001', 'POS TERM', 'Purchase at Sauer-Ruecker', 697.44, '800000000', 'Sauer-Ruecker', 'Port Nestor', '24148-9894', '4385271476627819', '2022-06-10 19:27:53.000000', '                          '),
('0000000685488982', '01', '0001', 'POS TERM', 'Purchase at Williamson Group', 94.77, '800000000', 'Williamson Group', 'Lake Bradyport', '32996', '0500024453765740', '2022-06-10 19:27:53.000000', '                          '),
('0000000686167627', '01', '0001', 'POS TERM', 'Purchase at Gibson, Beahan and Reichert', 81.44, '800000000', 'Gibson, Beahan and Reichert', 'Maeveland', '51385-6031', '9805583408996588', '2022-06-10 19:27:53.000000', '                          '),
('0000000689276136', '01', '0001', 'POS TERM', 'Purchase at Bins Group', 192.00, '800000000', 'Bins Group', 'North Anabellehaven', '61914-3232', '9056297931664011', '2022-06-10 19:27:53.000000', '                          '),
('0000000700096853', '01', '0001', 'POS TERM', 'Purchase at Pollich Group', 329.99, '800000000', 'Pollich Group', 'Nikolausburgh', '88031', '3940246016141489', '2022-06-10 19:27:53.000000', '                          '),
('0000000703553020', '01', '0001', 'POS TERM', 'Purchase at Gleason-Streich', 77.00, '800000000', 'Gleason-Streich', 'New Huntermouth', '60103-7370', '2760836797107565', '2022-06-10 19:27:53.000000', '                          '),
('0000000717135758', '03', '0001', 'OPERATOR', 'Return item at Crona, Veum and D''Amore', -762.44, '800000000', 'Crona, Veum and D''Amore', 'South Nashland', '13804-5608', '0982496213629795', '2022-06-10 19:27:53.000000', '                          '),
('0000000727152111', '01', '0001', 'POS TERM', 'Purchase at Von, Klein and Cremin', 983.55, '800000000', 'Von, Klein and Cremin', 'Evansfurt', '36814-9049', '6349250331648509', '2022-06-10 19:27:53.000000', '                          '),
('0000000731515153', '03', '0001', 'OPERATOR', 'Return item at Gleichner, Mitchell and Schmidt', -25.99, '800000000', 'Gleichner, Mitchell and Schmidt', 'North Vincent', '89467-9263', '9805583408996588', '2022-06-10 19:27:53.000000', '                          '),
('0000000734452614', '01', '0001', 'POS TERM', 'Purchase at Stokes-Mueller', 358.22, '800000000', 'Stokes-Mueller', 'Ambroseland', '19819-9298', '4534784102713951', '2022-06-10 19:27:53.000000', '                          '),
('0000000735823935', '01', '0001', 'POS TERM', 'Purchase at Johnston and Daughters', 910.11, '800000000', 'Johnston and Daughters', 'Delaneymouth', '49269-2667', '5407099850479866', '2022-06-10 19:27:53.000000', '                          '),
('0000000741999667', '01', '0001', 'POS TERM', 'Purchase at Corkery, Boehm and Hudson', 643.44, '800000000', 'Corkery, Boehm and Hudson', 'Walkermouth', '83831', '9349107475869214', '2022-06-10 19:27:53.000000', '                          '),
('0000000742447110', '01', '0001', 'POS TERM', 'Purchase at Hauck Inc', 746.77, '800000000', 'Hauck Inc', 'Estellville', '11000', '8517866958206008', '2022-06-10 19:27:53.000000', '                          '),
('0000000747646163', '03', '0001', 'OPERATOR', 'Return item at Watsica LLC', -499.99, '800000000', 'Watsica LLC', 'Durgantown', '74690-6183', '1561409106491600', '2022-06-10 19:27:53.000000', '                          '),
('0000000749066680', '01', '0001', 'POS TERM', 'Purchase at O''Reilly LLC', 805.77, '800000000', 'O''Reilly LLC', 'Jerelport', '39298-3605', '6509230362553816', '2022-06-10 19:27:53.000000', '                          '),
('0000000749493129', '01', '0001', 'POS TERM', 'Purchase at Pollich-Kuhn', 13.88, '800000000', 'Pollich-Kuhn', 'Kelliview', '98624-6791', '7058267261837752', '2022-06-10 19:27:53.000000', '                          '),
('0000000751145919', '01', '0001', 'POS TERM', 'Purchase at Rohan-Jacobson', 140.00, '800000000', 'Rohan-Jacobson', 'East Delmer', '37476', '9501733721429893', '2022-06-10 19:27:53.000000', '                          '),
('0000000751696292', '01', '0001', 'POS TERM', 'Purchase at Smith, Hansen and Waelchi', 653.55, '800000000', 'Smith, Hansen and Waelchi', 'Jerrodport', '37182-0090', '7427684863423209', '2022-06-10 19:27:53.000000', '                          '),
('0000000755736377', '01', '0001', 'POS TERM', 'Purchase at Torp-Stark', 906.44, '800000000', 'Torp-Stark', 'North Edison', '41040-7099', '9680294154603697', '2022-06-10 19:27:53.000000', '                          '),
('0000000760577632', '01', '0001', 'POS TERM', 'Purchase at Kulas-Hayes', 735.99, '800000000', 'Kulas-Hayes', 'Prohaskaview', '38756', '8931369351894783', '2022-06-10 19:27:53.000000', '                          '),
('0000000762269241', '01', '0001', 'POS TERM', 'Purchase at Kuvalis Group', 988.88, '800000000', 'Kuvalis Group', 'Lake Cierrashire', '92525', '3940246016141489', '2022-06-10 19:27:53.000000', '                          '),
('0000000767081090', '01', '0001', 'POS TERM', 'Purchase at Bergnaum, Effertz and Wilkinson', 671.11, '800000000', 'Bergnaum, Effertz and Wilkinson', 'Lake Twila', '39210-3581', '1142167692878931', '2022-06-10 19:27:53.000000', '                          '),
('0000000767308626', '01', '0001', 'POS TERM', 'Purchase at Shields, DuBuque and Wyman', 856.44, '800000000', 'Shields, DuBuque and Wyman', 'South Christelle', '94060-3050', '1142167692878931', '2022-06-10 19:27:53.000000', '                          '),
('0000000767314476', '01', '0001', 'POS TERM', 'Purchase at Renner Inc', 273.11, '800000000', 'Renner Inc', 'Lednerberg', '11838', '0927987108636232', '2022-06-10 19:27:53.000000', '                          '),
('0000000768119840', '01', '0001', 'POS TERM', 'Purchase at Towne, Hickle and Orn', 65.44, '800000000', 'Towne, Hickle and Orn', 'Toybury', '15228', '8112545834239735', '2022-06-10 19:27:53.000000', '                          '),
('0000000770707563', '01', '0001', 'POS TERM', 'Purchase at Leffler-Hilll', 309.44, '800000000', 'Leffler-Hilll', 'Lake Samantha', '94910', '9056297931664011', '2022-06-10 19:27:53.000000', '                          '),
('0000000772421231', '01', '0001', 'POS TERM', 'Purchase at Pfeffer, Rogahn and Hessel', 405.33, '800000000', 'Pfeffer, Rogahn and Hessel', 'Christborough', '21176-4420', '4385271476627819', '2022-06-10 19:27:53.000000', '                          '),
('0000000776200014', '01', '0001', 'POS TERM', 'Purchase at Lebsack and Sons', 985.22, '800000000', 'Lebsack and Sons', 'Otisbury', '32545', '9680294154603697', '2022-06-10 19:27:53.000000', '                          '),
('0000000778829157', '01', '0001', 'POS TERM', 'Purchase at Renner, Mertz and Ondricka', 270.55, '800000000', 'Renner, Mertz and Ondricka', 'South Emeliatown', '37065-2088', '8262593602473076', '2022-06-10 19:27:53.000000', '                          '),
('0000000781205695', '01', '0001', 'POS TERM', 'Purchase at Beer, Goldner and Armstrong', 489.66, '800000000', 'Beer, Goldner and Armstrong', 'South Madelynnland', '21570', '5787351228879339', '2022-06-10 19:27:53.000000', '                          '),
('0000000781512834', '01', '0001', 'POS TERM', 'Purchase at Koch-Pouros', 319.00, '800000000', 'Koch-Pouros', 'Daytonstad', '13199-2463', '4859452612877065', '2022-06-10 19:27:53.000000', '                          '),
('0000000784621975', '01', '0001', 'POS TERM', 'Purchase at Kunde-Howe', 227.22, '800000000', 'Kunde-Howe', 'New Darylberg', '34409', '3999169246375885', '2022-06-10 19:27:53.000000', '                          '),
('0000000793409700', '01', '0001', 'POS TERM', 'Purchase at Douglas Inc', 168.66, '800000000', 'Douglas Inc', 'South Keyshawnton', '15099', '7026637615032277', '2022-06-10 19:27:53.000000', '                          '),
('0000000796832699', '01', '0001', 'POS TERM', 'Purchase at Schroeder, Bergnaum and Waters', 803.99, '800000000', 'Schroeder, Bergnaum and Waters', 'Jaskolskimouth', '83332-8357', '6349250331648509', '2022-06-10 19:27:53.000000', '                          '),
('0000000802663079', '01', '0001', 'POS TERM', 'Purchase at Zulauf-O''Keefe', 975.11, '800000000', 'Zulauf-O''Keefe', 'Rauview', '52467-2350', '0683586198171516', '2022-06-10 19:27:53.000000', '                          '),
('0000000803014982', '01', '0001', 'POS TERM', 'Purchase at Effertz-Abbott', 53.55, '800000000', 'Effertz-Abbott', 'Claudiechester', '95970-2683', '2871968252812490', '2022-06-10 19:27:53.000000', '                          '),
('0000000806255008', '03', '0001', 'OPERATOR', 'Return item at Wisoky, Jacobs and Sanford', -439.33, '800000000', 'Wisoky, Jacobs and Sanford', 'New Alanaview', '05488-3195', '4534784102713951', '2022-06-10 19:27:53.000000', '                          '),
('0000000812607213', '03', '0001', 'OPERATOR', 'Return item at Volkman-Goodwin', -641.77, '800000000', 'Volkman-Goodwin', 'Gulgowskifort', '59834-6801', '2760836797107565', '2022-06-10 19:27:53.000000', '                          '),
('0000000821287727', '01', '0001', 'POS TERM', 'Purchase at Russel LLC', 34.99, '800000000', 'Russel LLC', 'New Sarah', '49041', '4859452612877065', '2022-06-10 19:27:53.000000', '                          '),
('0000000822135100', '01', '0001', 'POS TERM', 'Purchase at Larkin, Hills and Becker', 796.00, '800000000', 'Larkin, Hills and Becker', 'Coleton', '54392-1073', '5671184478505844', '2022-06-10 19:27:53.000000', '                          '),
('0000000823157599', '01', '0001', 'POS TERM', 'Purchase at Miller, Hudson and Ziemann', 111.11, '800000000', 'Miller, Hudson and Ziemann', 'West Jasmin', '72736', '9349107475869214', '2022-06-10 19:27:53.000000', '                          '),
('0000000824152956', '01', '0001', 'POS TERM', 'Purchase at Weissnat-Sanford', 594.77, '800000000', 'Weissnat-Sanford', 'Schuppeton', '25158-3242', '0923877193247330', '2022-06-10 19:27:53.000000', '                          '),
('0000000828072981', '03', '0001', 'OPERATOR', 'Return item at Hayes Inc', -362.22, '800000000', 'Hayes Inc', 'Dinoville', '72795-6502', '7026637615032277', '2022-06-10 19:27:53.000000', '                          '),
('0000000835855923', '03', '0001', 'OPERATOR', 'Return item at Pouros and Sons', -41.77, '800000000', 'Pouros and Sons', 'Kerlukechester', '32347', '5407099850479866', '2022-06-10 19:27:53.000000', '                          '),
('0000000838587312', '01', '0001', 'POS TERM', 'Purchase at Abbott-Gerlach', 241.66, '800000000', 'Abbott-Gerlach', 'McClureburgh', '95049', '0500024453765740', '2022-06-10 19:27:53.000000', '                          '),
('0000000838796166', '03', '0001', 'OPERATOR', 'Return item at Bauch-Crooks', -457.55, '800000000', 'Bauch-Crooks', 'Stokesberg', '30306', '4385271476627819', '2022-06-10 19:27:53.000000', '                          '),
('0000000840146978', '01', '0001', 'POS TERM', 'Purchase at Gutkowski-Bayer', 702.22, '800000000', 'Gutkowski-Bayer', 'Baileyville', '48332-1913', '4011500891777367', '2022-06-10 19:27:53.000000', '                          '),
('0000000841555701', '01', '0001', 'POS TERM', 'Purchase at Kub, Gislason and Haraann', 281.55, '800000000', 'Kub, Gislason and Haraann', 'Port Bryonfurt', '16314-3731', '2988091353094312', '2022-06-10 19:27:53.000000', '                          '),
('0000000845039454', '01', '0001', 'POS TERM', 'Purchase at Borer, Farrell and Doyle', 45.66, '800000000', 'Borer, Farrell and Doyle', 'Evelineborough', '36781', '9056297931664011', '2022-06-10 19:27:53.000000', '                          '),
('0000000855260493', '03', '0001', 'OPERATOR', 'Return item at Cassin, Huel and Conroy', -270.99, '800000000', 'Cassin, Huel and Conroy', 'East Kurtborough', '83037', '6727055190616014', '2022-06-10 19:27:53.000000', '                          '),
('0000000858238426', '01', '0001', 'POS TERM', 'Purchase at Thiel Group', 80.66, '800000000', 'Thiel Group', 'New Martineberg', '27981', '3260763612337560', '2022-06-10 19:27:53.000000', '                          '),
('0000000858501945', '01', '0001', 'POS TERM', 'Purchase at Braun, Schulist and Kreiger', 198.66, '800000000', 'Braun, Schulist and Kreiger', 'Port Tamiamouth', '52536', '8262593602473076', '2022-06-10 19:27:53.000000', '                          '),
('0000000865987685', '03', '0001', 'OPERATOR', 'Return item at Kovacek-Beatty', -322.99, '800000000', 'Kovacek-Beatty', 'Cecilemouth', '18917', '2988091353094312', '2022-06-10 19:27:53.000000', '                          '),
('0000000869383367', '01', '0001', 'POS TERM', 'Purchase at Thompson-Streich', 768.88, '800000000', 'Thompson-Streich', 'Port Estrella', '21832-3751', '2940139362300449', '2022-06-10 19:27:53.000000', '                          '),
('0000000873232405', '01', '0001', 'POS TERM', 'Purchase at Bins, Boehm and Casper', 720.99, '800000000', 'Bins, Boehm and Casper', 'South Lon', '87054', '4534784102713951', '2022-06-10 19:27:53.000000', '                          '),
('0000000874953803', '01', '0001', 'POS TERM', 'Purchase at McLaughlin-Blick', 399.44, '800000000', 'McLaughlin-Blick', 'Wintheisermouth', '03064', '1014086565224350', '2022-06-10 19:27:53.000000', '                          '),
('0000000882360848', '01', '0001', 'POS TERM', 'Purchase at Tromp-Kuhlman', 298.33, '800000000', 'Tromp-Kuhlman', 'Jerdeshire', '78699', '0982496213629795', '2022-06-10 19:27:53.000000', '                          '),
('0000000884070277', '01', '0001', 'POS TERM', 'Purchase at Bayer-O''Reilly', 194.33, '800000000', 'Bayer-O''Reilly', 'Stammmouth', '54961-5499', '9805583408996588', '2022-06-10 19:27:53.000000', '                          '),
('0000000885437581', '01', '0001', 'POS TERM', 'Purchase at Marquardt-Deckow', 818.00, '800000000', 'Marquardt-Deckow', 'Schmittport', '16465', '7026637615032277', '2022-06-10 19:27:53.000000', '                          '),
('0000000887771179', '01', '0001', 'POS TERM', 'Purchase at Jakubowski and Sons', 242.22, '800000000', 'Jakubowski and Sons', 'Port Tyramouth', '68202-7796', '7026637615032277', '2022-06-10 19:27:53.000000', '                          '),
('0000000889986293', '01', '0001', 'POS TERM', 'Purchase at Littel-Jacobson', 973.11, '800000000', 'Littel-Jacobson', 'Lestertown', '36198', '1561409106491600', '2022-06-10 19:27:53.000000', '                          '),
('0000000898285002', '01', '0001', 'POS TERM', 'Purchase at Gislason-Price', 958.77, '800000000', 'Gislason-Price', 'North Maverickbury', '09515-7261', '2871968252812490', '2022-06-10 19:27:53.000000', '                          '),
('0000000899241176', '01', '0001', 'POS TERM', 'Purchase at Beier, Larson and Schultz', 462.33, '800000000', 'Beier, Larson and Schultz', 'North Gudrunville', '59436-8470', '2988091353094312', '2022-06-10 19:27:53.000000', '                          '),
('0000000900382063', '01', '0001', 'POS TERM', 'Purchase at Bechtelar Group', 86.00, '800000000', 'Bechtelar Group', 'Mandybury', '49970-7370', '0927987108636232', '2022-06-10 19:27:53.000000', '                          '),
('0000000903281896', '01', '0001', 'POS TERM', 'Purchase at Schmitt, Mills and Yundt', 932.55, '800000000', 'Schmitt, Mills and Yundt', 'West Marlin', '92662-1169', '0683586198171516', '2022-06-10 19:27:53.000000', '                          '),
('0000000909001545', '01', '0001', 'POS TERM', 'Purchase at Crist Group', 893.33, '800000000', 'Crist Group', 'South Creola', '20922-4303', '5656830544981216', '2022-06-10 19:27:53.000000', '                          '),
('0000000909315074', '01', '0001', 'POS TERM', 'Purchase at Abbott and Sons', 759.22, '800000000', 'Abbott and Sons', 'East Cydney', '03808-7468', '4011500891777367', '2022-06-10 19:27:53.000000', '                          '),
('0000000910081354', '01', '0001', 'POS TERM', 'Purchase at Gerlach Group', 130.11, '800000000', 'Gerlach Group', 'Tannerburgh', '30389-8741', '7026637615032277', '2022-06-10 19:27:53.000000', '                          '),
('0000000925687557', '03', '0001', 'OPERATOR', 'Return item at Zboncak-Franecki', -372.99, '800000000', 'Zboncak-Franecki', 'Aldenport', '24426-3401', '0683586198171516', '2022-06-10 19:27:53.000000', '                          '),
('0000000926624843', '01', '0001', 'POS TERM', 'Purchase at Schowalter, Pagac and Welch', 684.33, '800000000', 'Schowalter, Pagac and Welch', 'West Isacton', '46573-2355', '4011500891777367', '2022-06-10 19:27:53.000000', '                          '),
('0000000929059536', '01', '0001', 'POS TERM', 'Purchase at Glover, Block and Huel', 920.11, '800000000', 'Glover, Block and Huel', 'Lake Dasiabury', '92661', '8517866958206008', '2022-06-10 19:27:53.000000', '                          '),
('0000000934798061', '03', '0001', 'OPERATOR', 'Return item at Gottlieb, VonRueden and Raynor', -260.11, '800000000', 'Gottlieb, VonRueden and Raynor', 'East Darryl', '94703', '9501733721429893', '2022-06-10 19:27:53.000000', '                          '),
('0000000934945079', '01', '0001', 'POS TERM', 'Purchase at Boyle, O''Conner and Gorczany', 222.44, '800000000', 'Boyle, O''Conner and Gorczany', 'South Kirstin', '23487', '6503535181795992', '2022-06-10 19:27:53.000000', '                          '),
('0000000942960329', '01', '0001', 'POS TERM', 'Purchase at Bartoletti, Lehner and Johnston', 711.66, '800000000', 'Bartoletti, Lehner and Johnston', 'North Virginie', '63690', '2940139362300449', '2022-06-10 19:27:53.000000', '                          '),
('0000000943918566', '01', '0001', 'POS TERM', 'Purchase at Walker, Mohr and Wyman', 437.77, '800000000', 'Walker, Mohr and Wyman', 'Kamronville', '93454', '8112545834239735', '2022-06-10 19:27:53.000000', '                          '),
('0000000946277676', '01', '0001', 'POS TERM', 'Purchase at Kemmer, Wyman and Ondricka', 220.00, '800000000', 'Kemmer, Wyman and Ondricka', 'Marcellechester', '28632', '3766281984155154', '2022-06-10 19:27:53.000000', '                          '),
('0000000956474921', '01', '0001', 'POS TERM', 'Purchase at Gottlieb, Turner and Ruecker', 223.33, '800000000', 'Gottlieb, Turner and Ruecker', 'Brettland', '98831-6582', '2745303720002090', '2022-06-10 19:27:53.000000', '                          '),
('0000000957695517', '01', '0001', 'POS TERM', 'Purchase at Schoen-Marvin', 573.22, '800000000', 'Schoen-Marvin', 'West Anastacio', '10111-5026', '0927987108636232', '2022-06-10 19:27:53.000000', '                          '),
('0000000957864065', '01', '0001', 'POS TERM', 'Purchase at Prohaska-Douglas', 314.66, '800000000', 'Prohaska-Douglas', 'North Leathahaven', '92680-2418', '3766281984155154', '2022-06-10 19:27:53.000000', '                          '),
('0000000961186055', '01', '0001', 'POS TERM', 'Purchase at Bartell-Fadel', 548.33, '800000000', 'Bartell-Fadel', 'Lebsackchester', '88382-6538', '1142167692878931', '2022-06-10 19:27:53.000000', '                          '),
('0000000961714986', '01', '0001', 'POS TERM', 'Purchase at West and Sons', 694.55, '800000000', 'West and Sons', 'Lawrencefort', '06664-6090', '3999169246375885', '2022-06-10 19:27:53.000000', '                          '),
('0000000971342087', '03', '0001', 'OPERATOR', 'Return item at Johnston Inc', -835.44, '800000000', 'Johnston Inc', 'Bergstromchester', '69737', '8112545834239735', '2022-06-10 19:27:53.000000', '                          '),
('0000000973907278', '01', '0001', 'POS TERM', 'Purchase at Klocko-Rice', 784.11, '800000000', 'Klocko-Rice', 'Shayneville', '50038-5154', '6349250331648509', '2022-06-10 19:27:53.000000', '                          '),
('0000000974167587', '01', '0001', 'POS TERM', 'Purchase at Moore and Sons', 402.22, '800000000', 'Moore and Sons', 'Parkerchester', '69137', '5975117516616077', '2022-06-10 19:27:53.000000', '                          '),
('0000000976770816', '01', '0001', 'POS TERM', 'Purchase at Corkery-Barton', 917.44, '800000000', 'Corkery-Barton', 'North Walterchester', '08815-3649', '8262593602473076', '2022-06-10 19:27:53.000000', '                          '),
('0000000982241353', '01', '0001', 'POS TERM', 'Purchase at Bins, Gorczany and Denesik', 765.66, '800000000', 'Bins, Gorczany and Denesik', 'Elveraville', '52528', '9805583408996588', '2022-06-10 19:27:53.000000', '                          '),
('0000000992103545', '01', '0001', 'POS TERM', 'Purchase at Dickens, Bartoletti and Ferry', 635.99, '800000000', 'Dickens, Bartoletti and Ferry', 'Lesleyville', '89308-8479', '1142167692878931', '2022-06-10 19:27:53.000000', '                          '),
('0000000996722787', '01', '0001', 'POS TERM', 'Purchase at Kilback LLC', 603.22, '800000000', 'Kilback LLC', 'Cummeratamouth', '53200-7529', '3260763612337560', '2022-06-10 19:27:53.000000', '                          ');

-- -------------------------------------------------------------------------------------------------
-- Post-seed verification.
-- -------------------------------------------------------------------------------------------------
-- Everything the seed claims about itself is checked here, in the same transaction that inserted the
-- rows, so a partial or drifted load fails the migration instead of being discovered later by a test.
-- The block creates no persistent object - no table, no view, no function, no sequence - and builds
-- no SQL string: every statement below is a literal query. Flyway runs each migration in one
-- transaction by default on PostgreSQL, so a raised exception rolls the whole seed back and the
-- schema-history row records the failure.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    v_customer          bigint;
    v_account           bigint;
    v_card              bigint;
    v_xref              bigint;
    v_tran_type         bigint;
    v_tran_cat          bigint;
    v_disclosure        bigint;
    v_cat_balance       bigint;
    v_daily             bigint;
    v_transaction       bigint;
    v_user_security     bigint;
    v_total             bigint;
    v_ssn_null          bigint;
    v_govt_sealed       bigint;
    v_govt_width        bigint;
    v_govt_distinct     bigint;
    v_govt_cleartext    bigint;
    v_anomaly_zip       bigint;
    v_anomaly_group     bigint;
    v_group_a           bigint;
    v_group_default     bigint;
    v_group_zeroapr     bigint;
    v_group_width       bigint;
    v_rate_zero         bigint;
    v_rate_positive     bigint;
    v_zero_balances     bigint;
    v_source_pos_term   bigint;
    v_source_operator   bigint;
    v_amount_positive   bigint;
    v_amount_negative   bigint;
    v_orig_ts           bigint;
    v_proc_ts           bigint;
BEGIN
    -- Row counts, table by table, then the total across the nine seeded tables.
    SELECT count(*) INTO v_customer     FROM customer;
    SELECT count(*) INTO v_account      FROM account;
    SELECT count(*) INTO v_card         FROM card;
    SELECT count(*) INTO v_xref         FROM card_cross_reference;
    SELECT count(*) INTO v_tran_type    FROM transaction_type;
    SELECT count(*) INTO v_tran_cat     FROM transaction_category;
    SELECT count(*) INTO v_disclosure   FROM disclosure_group;
    SELECT count(*) INTO v_cat_balance  FROM transaction_category_balance;
    SELECT count(*) INTO v_daily        FROM daily_transaction;

    IF v_customer <> 50 THEN
        RAISE EXCEPTION 'V3 seed: customer holds % rows, expected 50', v_customer;
    END IF;
    IF v_account <> 50 THEN
        RAISE EXCEPTION 'V3 seed: account holds % rows, expected 50', v_account;
    END IF;
    IF v_card <> 50 THEN
        RAISE EXCEPTION 'V3 seed: card holds % rows, expected 50', v_card;
    END IF;
    IF v_xref <> 50 THEN
        RAISE EXCEPTION 'V3 seed: card_cross_reference holds % rows, expected 50', v_xref;
    END IF;
    IF v_tran_type <> 7 THEN
        RAISE EXCEPTION 'V3 seed: transaction_type holds % rows, expected 7', v_tran_type;
    END IF;
    IF v_tran_cat <> 18 THEN
        RAISE EXCEPTION 'V3 seed: transaction_category holds % rows, expected 18', v_tran_cat;
    END IF;
    IF v_disclosure <> 51 THEN
        RAISE EXCEPTION 'V3 seed: disclosure_group holds % rows, expected 51', v_disclosure;
    END IF;
    IF v_cat_balance <> 50 THEN
        RAISE EXCEPTION 'V3 seed: transaction_category_balance holds % rows, expected 50',
            v_cat_balance;
    END IF;
    IF v_daily <> 300 THEN
        RAISE EXCEPTION 'V3 seed: daily_transaction holds % rows, expected 300', v_daily;
    END IF;

    v_total := v_customer + v_account + v_card + v_xref + v_tran_type + v_tran_cat
             + v_disclosure + v_cat_balance + v_daily;
    IF v_total <> 626 THEN
        RAISE EXCEPTION 'V3 seed: nine seeded tables hold % rows in total, expected 626', v_total;
    END IF;

    -- The two tables this file must leave alone. transaction is filled by the posting job; the ten
    -- logins belong to V4, which has not run at this point in the migration order.
    SELECT count(*) INTO v_transaction   FROM transaction;
    SELECT count(*) INTO v_user_security FROM user_security;
    IF v_transaction <> 0 THEN
        RAISE EXCEPTION 'V3 seed: transaction holds % rows, expected 0 - this file must not seed it',
            v_transaction;
    END IF;
    IF v_user_security <> 0 THEN
        RAISE EXCEPTION 'V3 seed: user_security holds % rows, expected 0 - V4 owns those rows',
            v_user_security;
    END IF;

    -- Anomaly 2: the national identifier is not seeded, in any row. The fixture record carries one in
    -- cleartext and this file declines to load it, so the column is null in all fifty rows and the
    -- assertion below is what stops a future revision from quietly reintroducing the cleartext value.
    -- The government-issued identifier is handled the other way round - it IS seeded, as a sealed
    -- envelope - and is checked by the block immediately following rather than here. The two columns
    -- are therefore asserted by opposite tests, and neither test may be applied to the other column:
    -- a null check over the sealed column would fail on every row, and an envelope check over the
    -- national identifier would fail on every row.
    SELECT count(*) INTO v_ssn_null  FROM customer WHERE cust_ssn      IS NULL;
    IF v_ssn_null <> 50 THEN
        RAISE EXCEPTION 'V3 seed: % customer rows hold a null national identifier, expected 50',
            v_ssn_null;
    END IF;

    -- Anomaly 3: the government-issued identifier IS seeded, and every row must carry an envelope
    -- rather than the cleartext the fixture record holds. Four separate things are checked, because
    -- each catches a different way an edit could reintroduce cleartext:
    --   sealed    - the scheme marker is present, so nothing was pasted in unsealed;
    --   width     - every value is exactly the 69 characters a twenty-character payload produces,
    --               so no literal was truncated by a wrapped line or a stray quote;
    --   distinct  - fifty different envelopes, so no row was filled by copying its neighbour, which
    --               would silently give two customers the same identifier;
    --   cleartext - no value is a bare run of digits of the legacy width, which is exactly what the
    --               defect this check exists to prevent looked like.
    -- This block cannot decrypt - it holds no key and must not - so it verifies shape here and leaves
    -- recovery to SeededProtectedIdentifierIT, which opens all fifty through the application service.
    SELECT count(*) INTO v_govt_sealed
      FROM customer WHERE govt_issued_id LIKE 'ENC1:%';
    SELECT count(*) INTO v_govt_width
      FROM customer WHERE length(govt_issued_id) = 69;
    SELECT count(DISTINCT govt_issued_id) INTO v_govt_distinct FROM customer;
    SELECT count(*) INTO v_govt_cleartext
      FROM customer WHERE govt_issued_id ~ '^[0-9]{1,20}$';
    IF v_govt_sealed <> 50 THEN
        RAISE EXCEPTION 'V3 seed: % customer rows carry a sealed government-issued identifier,'
            ' expected 50 - every row must hold an ENC1 envelope, never cleartext', v_govt_sealed;
    END IF;
    IF v_govt_width <> 50 THEN
        RAISE EXCEPTION 'V3 seed: % sealed identifiers measure 69 characters, expected 50 - a'
            ' different width means a literal was altered or truncated', v_govt_width;
    END IF;
    IF v_govt_distinct <> 50 THEN
        RAISE EXCEPTION 'V3 seed: the fifty customer rows hold only % distinct sealed identifiers,'
            ' expected 50', v_govt_distinct;
    END IF;
    IF v_govt_cleartext <> 0 THEN
        RAISE EXCEPTION 'V3 seed: % customer rows hold a cleartext-shaped government-issued'
            ' identifier, expected 0', v_govt_cleartext;
    END IF;

    -- Anomaly 1: the account address-ZIP and group-identifier fields as the fixture holds them.
    SELECT count(*) INTO v_anomaly_zip
      FROM account WHERE acct_addr_zip = 'A000000000';
    SELECT count(*) INTO v_anomaly_group
      FROM account WHERE length(acct_group_id) = 10 AND btrim(acct_group_id, ' ') = '';
    IF v_anomaly_zip <> 50 THEN
        RAISE EXCEPTION 'V3 seed: % account rows carry the fixture address-ZIP value, expected 50',
            v_anomaly_zip;
    END IF;
    IF v_anomaly_group <> 50 THEN
        RAISE EXCEPTION 'V3 seed: % account rows carry a ten-space group identifier, expected 50',
            v_anomaly_group;
    END IF;

    -- Anomaly 4: three groups of seventeen under keys that are ten characters wide, blanks included.
    SELECT count(*) INTO v_group_a
      FROM disclosure_group WHERE dis_acct_group_id = 'A000000000';
    SELECT count(*) INTO v_group_default
      FROM disclosure_group WHERE dis_acct_group_id = 'DEFAULT   ';
    SELECT count(*) INTO v_group_zeroapr
      FROM disclosure_group WHERE dis_acct_group_id = 'ZEROAPR   ';
    SELECT count(*) INTO v_group_width
      FROM disclosure_group WHERE length(dis_acct_group_id) = 10;
    IF v_group_a <> 17 OR v_group_default <> 17 OR v_group_zeroapr <> 17 THEN
        RAISE EXCEPTION 'V3 seed: disclosure groups hold %/%/% rows, expected 17/17/17 for the'
            ' padded keys A000000000, DEFAULT and ZEROAPR', v_group_a, v_group_default,
            v_group_zeroapr;
    END IF;
    IF v_group_width <> 51 THEN
        RAISE EXCEPTION 'V3 seed: % disclosure keys are ten characters wide, expected 51',
            v_group_width;
    END IF;

    -- Both rate kinds must be present so that a constructed account can reach either interest
    -- branch: a zero rate to skip and a non-zero rate to compute. Presence is what is asserted
    -- here; which branch a seed-only run actually takes is stated in the section preamble above.
    SELECT count(*) INTO v_rate_zero     FROM disclosure_group WHERE dis_int_rate = 0.00;
    SELECT count(*) INTO v_rate_positive FROM disclosure_group WHERE dis_int_rate > 0.00;
    IF v_rate_zero = 0 OR v_rate_positive = 0 THEN
        RAISE EXCEPTION 'V3 seed: disclosure rates hold % zero and % positive values, expected at'
            ' least one of each', v_rate_zero, v_rate_positive;
    END IF;

    -- Every seeded category balance opens at zero.
    SELECT count(*) INTO v_zero_balances
      FROM transaction_category_balance WHERE tran_cat_bal = 0.00;
    IF v_zero_balances <> 50 THEN
        RAISE EXCEPTION 'V3 seed: % category balances open at zero, expected 50', v_zero_balances;
    END IF;

    -- Daily composition: 250 purchases and 50 returns, matching sign for matching source.
    SELECT count(*) INTO v_source_pos_term FROM daily_transaction WHERE dalytran_source = 'POS TERM';
    SELECT count(*) INTO v_source_operator FROM daily_transaction WHERE dalytran_source = 'OPERATOR';
    SELECT count(*) INTO v_amount_positive FROM daily_transaction WHERE dalytran_amt > 0.00;
    SELECT count(*) INTO v_amount_negative FROM daily_transaction WHERE dalytran_amt < 0.00;
    IF v_source_pos_term <> 250 OR v_source_operator <> 50 THEN
        RAISE EXCEPTION 'V3 seed: daily sources hold % point-of-sale and % operator rows, expected'
            ' 250 and 50', v_source_pos_term, v_source_operator;
    END IF;
    IF v_amount_positive <> 250 OR v_amount_negative <> 50 THEN
        RAISE EXCEPTION 'V3 seed: daily amounts hold % positive and % negative values, expected'
            ' 250 and 50', v_amount_positive, v_amount_negative;
    END IF;

    -- Anomaly 5: one original instant throughout, and a processing field left blank at exactly the
    -- record width. A clock reading here would be the defect this check exists to catch.
    SELECT count(*) INTO v_orig_ts
      FROM daily_transaction WHERE dalytran_orig_ts = '2022-06-10 19:27:53.000000';
    SELECT count(*) INTO v_proc_ts
      FROM daily_transaction
     WHERE length(dalytran_proc_ts) = 26 AND btrim(dalytran_proc_ts, ' ') = '';
    IF v_orig_ts <> 300 THEN
        RAISE EXCEPTION 'V3 seed: % daily rows carry the fixture original timestamp, expected 300',
            v_orig_ts;
    END IF;
    IF v_proc_ts <> 300 THEN
        RAISE EXCEPTION 'V3 seed: % daily rows carry a twenty-six-space processing timestamp,'
            ' expected 300', v_proc_ts;
    END IF;

    -- Deliberately free of a semicolon inside the message text, so that a quote-naive statement
    -- splitter cannot mistake the middle of this literal for the end of the block.
    RAISE INFO 'V3__seed_reference_data: 626 rows seeded and verified across nine tables -'
        ' customer 50, account 50, card 50, card_cross_reference 50, transaction_type 7,'
        ' transaction_category 18, disclosure_group 51, transaction_category_balance 50,'
        ' daily_transaction 300. transaction and user_security left empty.';
END
$$;


-- End of V3. Nine tables seeded, 626 rows, from nine ASCII reference datasets. Fifty of those rows
-- carry a sealed government-issued identifier and none carries a cleartext one. No row was written to
-- transaction or user_security, no lookup table was created, and no schema object was added or
-- altered. Local and test only: production stops at version 2.
