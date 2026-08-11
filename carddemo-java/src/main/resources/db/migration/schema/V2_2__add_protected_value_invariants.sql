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

-- V2_2__add_protected_value_invariants.sql - the three columns that may hold a protected value only.
--
-- THREE CHECK CONSTRAINTS, NO TABLE, NO COLUMN, NO INDEX AND NO ROW. Nothing here creates or alters a
-- column, nothing reads or writes a row, and nothing drops or replaces a constraint V1 declared. The
-- eleven record tables stay eleven, every column keeps its declared type and width, and the byte-repertoire
-- and key-shape constraints V1 attached are left exactly as they are.
--
-- WHAT WAS WRONG. V1__create_schema.sql widens three columns beyond their legacy record widths so they can
-- hold a protected value: customer.cust_ssn and customer.govt_issued_id are VARCHAR(255) for an ENC1
-- envelope where the record image carries 9 and 20 cleartext bytes, and user_security.sec_usr_pwd is
-- VARCHAR(60) for a BCrypt digest where the record image carries 8 cleartext characters. V1 says so at
-- length in prose, and its comments are accurate. Prose is not a constraint. What the three columns
-- actually constrained was width and byte repertoire: ck_customer_single_byte_text and
-- ck_user_security_single_byte_text require octet_length = char_length, which every US-ASCII value
-- satisfies. A nine-digit national identifier is US-ASCII and fits VARCHAR(255); an eight-character
-- password is US-ASCII and fits VARCHAR(60). Both would have been stored without complaint.
--
-- WHY THE APPLICATION'S OWN RULE IS NOT SUFFICIENT ON ITS OWN. The rule exists and is enforced:
-- Customer's two write paths refuse any value that is not envelope-shaped, and UserSecurity's credential
-- setter refuses any value that is not structurally a BCrypt digest. Both run only for a writer that
-- constructs an entity. A bulk load does not. Neither does a repair script, a COPY, a hand-authored seed,
-- an operator at a psql prompt, or a future component reaching the column through a native statement -
-- and neither does the reference seed itself, which the migration tool applies as SQL rather than through
-- the entity. V1 already makes exactly this argument for its key-shape constraints, in these words: "The
-- entities enforce the same rules before the write; these constraints catch a bulk load, a migration
-- script or any future writer that never constructs one." This file extends that argument to the three
-- columns where what is at stake is regulated data rather than the shape of a key.
--
-- WHY THIS ARRIVES AS A NEW VERSION RATHER THAN AS AN EDIT TO V1. Checksum validation is enabled on
-- migrate and V1 is applied wherever it will ever be applied, so no character of it may change - not a
-- constraint, not a comment. A later change of intent arrives as a new version. That is the same rule V1
-- through V2_1 each state in their own headers, and it is why the constraints below are attached by ALTER
-- rather than written into the CREATE TABLE that would have been their natural home.
--
-- WHY THE VERSION IS 2.2. Every schema version sorts below every seed version: 1, 2, 2.1 and 2.2 are
-- structure, 3 and 4 are fixtures. V2_1's own header names 2.2 as the number a further schema script takes,
-- and three separate controls depend on the property - the production ceiling excludes the seeds by their
-- NUMBER as well as by their DIRECTORY; the production seeded-database refusal reads contamination off the
-- history as a successful row at or above version 3; and a database migrated production-shaped and later
-- resolving the seed location would find the seeds pending BELOW an applied schema version, which the tool
-- refuses as out-of-order. Flyway compares version parts numerically, so 2 < 2.1 < 2.2 < 3. The production
-- pin moves from "2.1" to "2.2" in the same commit as this file, and a test asserts the pin against the
-- versions the schema location actually delivers, so the two cannot drift apart. docs/decision-log.md
-- DL-343 records the evidence for the numbering rule; DL-349 records this file.
--
-- THE ENVELOPE RULE, AND WHERE IT COMES FROM. The application's rule is SensitiveFieldCodec's envelope
-- shape check: the value opens with the marker ENC1:, the body after the marker is non-empty, the body
-- decodes as basic Base64, and the decoded body is at least IV_LENGTH_BYTES + TAG_LENGTH_BYTES = 12 + 16 =
-- 28 bytes, which is a 96-bit initialisation vector plus a 128-bit authentication tag before any
-- ciphertext at all. Three of those four are expressible here: the marker, the alphabet the body is drawn
-- from, and a length floor implied by the decoded minimum. The arithmetic of the floor is exact. Basic
-- Base64 carries 3 bytes per 4 characters, and the fewest characters that decode to 28 bytes is 38 - a
-- tail of 2 characters is the shortest valid unpadded tail, and 4*9+2 characters yield 9*3+1 = 28 bytes -
-- so the shortest value the application accepts is 5 + 38 = 43 characters.
--
-- THE FLOOR IS THE READER'S FIGURE, 43, AND NOT THE WRITER'S, 45. This module's own encoder always pads,
-- so every envelope it produces has a body length that is a multiple of 4 and is therefore 45 characters or
-- more; the fifty seeded customer rows carry 81 and 101. Constraining at 45 would have been true of every
-- value this module writes and still wrong, because the application's reader accepts 43 and a constraint
-- that refuses a value the application accepts is a defect rather than a control. The floor states what the
-- rule requires, not what the current writer happens to emit.
--
-- WHY THE PATTERN IS DELIBERATELY LOOSER THAN THE DECODER. A CHECK cannot decode Base64, so padding
-- placement is not policed here: 'QQQ==' matches the character class below and the decoder refuses it, so
-- this constraint admits a handful of values the application would reject. That direction is safe. The
-- reverse direction is the one that would matter and it cannot occur: the basic alphabet is exactly
-- A-Z, a-z, 0-9, + and / plus the pad character, so no character the decoder accepts falls outside the
-- class. What is written below is a NECESSARY condition and never a sufficient one. The application stays
-- the precise gate; this is the floor beneath it, and its job is to make cleartext unstorable rather than
-- to re-implement a codec in SQL.
--
-- NULL. customer.cust_ssn is the one nullable column among the eleven record tables, and V1 records why:
-- the reference seed leaves the national identifier absent, so absence is a state a row is permitted to
-- state. A CHECK evaluates to unknown for a null input and PostgreSQL treats unknown as satisfied, so that
-- exemption would have held silently. The null branch is written out anyway, so the file states the intent
-- rather than resting on a rule the next reader has to recall. The other two columns are NOT NULL, so a
-- null branch there would be unreachable and none is written.
--
-- THE DIGEST RULE, AND WHERE IT COMES FROM. The application's rule is UserSecurity's credential check, and
-- it has four conditions, all of which the pattern below mirrors. The value is exactly 60 characters. It
-- opens with one of three recognised version markers - $2a$, $2b$, $2y$ - and no other, so $2c$ and $2x$
-- are refused here as they are there. The two characters after the marker are digits forming a cost between
-- 10 and 31 inclusive, followed by the separator $. The remaining 53 characters are drawn from BCrypt's
-- radix-64 alphabet, which is . / A-Z a-z 0-9 - deliberately NOT standard Base64: the ordering differs and
-- there is no pad character. The total width of 60 follows from 4 + 2 + 1 + 53 and so is not asserted
-- separately; VARCHAR(60) bounds it from above independently.
--
-- WHY THE COST IS PART OF THE CONSTRAINT RATHER THAN LEFT TO THE ENCODER. A digest at cost 4 is
-- structurally a BCrypt digest and is orders of magnitude cheaper to attack than one at 12 - the work
-- factor is a power of two, so each step down halves the cost of a guess. The application refuses a cost
-- below 10; a column that accepted one would leave the weakest legal digest reachable by any writer that
-- bypassed the application, which is the whole category this file exists to close. The delivered encoder
-- and the ten seeded identities all use 12, comfortably inside the window, so the constraint refuses
-- nothing the module produces.
--
-- WHAT THIS DOES NOT CLAIM. It does not prove a value decrypts, does not identify which key sealed it,
-- does not prove a digest verifies any credential, and does not replace the application's guards or the
-- deployment's key management. It refuses cleartext, and it refuses a value of the wrong family. That is
-- the whole of the claim.
--
-- Validated against PostgreSQL 16.14 using plain ALTER TABLE ... ADD CONSTRAINT DDL and the built-in POSIX
-- regular-expression operator; nothing here depends on an extension. Adding a constraint validates the
-- rows already present, and in a forward migration both tables are still empty at this version; where rows
-- do exist, every one of the hundred seeded envelopes and ten seeded digests satisfies its constraint,
-- which a test asserts against the delivered seed text rather than trusting. Checksum validation is
-- enabled on migrate, so this file is immutable once applied.

-- GLOBAL RULES FOR THIS MIGRATION - each is a deliberate decision, not an omission.
--
--  1. EXACTLY THREE EXECUTABLE STATEMENTS, one per column named in the finding. No table, no column, no
--     index, no trigger, no function and no row. The count is contractual and is asserted by a test that
--     reads this file.
--
--  2. NOTHING V1 DECLARED IS DROPPED OR REPLACED. These constraints sit alongside the byte-repertoire and
--     key-shape constraints rather than superseding them: an ENC1 envelope and a BCrypt digest are both
--     US-ASCII by construction, so the repertoire rule remains true of every value these columns may now
--     hold, and the two rules answer different questions.
--
--  3. ONE CONSTRAINT PER COLUMN, NOT ONE PER TABLE. V1 groups its repertoire rule into a single
--     per-table constraint because the rule is identical for every column. These three rules are not
--     identical - two are envelope rules and one is a digest rule - and a refusal has to name the column
--     that caused it, because the operator reading the refusal needs to know which value to look at.
--
--  4. THE PATTERNS ARE WRITTEN OUT RATHER THAN FACTORED INTO A DOMAIN OR A FUNCTION. A CHECK constraint
--     may not call a non-immutable function, and a schema-level function would be a second place for the
--     rule to live and drift from the code. Two literal patterns that a reader can compare against
--     SensitiveFieldCodec and UserSecurity by eye is the arrangement that stays honest.

-- The nullable one. The null branch is explicit rather than implied; see NULL above.
ALTER TABLE customer
    ADD CONSTRAINT ck_customer_cust_ssn_protected CHECK (
        cust_ssn IS NULL
        OR (cust_ssn ~ '^ENC1:[A-Za-z0-9+/]+={0,2}$' AND char_length(cust_ssn) >= 43)
    );

-- NOT NULL, so absence is not a state this column may hold and no null branch is written.
ALTER TABLE customer
    ADD CONSTRAINT ck_customer_govt_issued_id_protected CHECK (
        govt_issued_id ~ '^ENC1:[A-Za-z0-9+/]+={0,2}$' AND char_length(govt_issued_id) >= 43
    );

-- The four conditions of the application's own credential check, in one pattern. Marker, two-digit cost
-- inside 10..31, separator, then 53 characters of BCrypt radix-64. The total width of 60 follows.
ALTER TABLE user_security
    ADD CONSTRAINT ck_user_security_sec_usr_pwd_digest CHECK (
        sec_usr_pwd ~ '^\$2[aby]\$(1[0-9]|2[0-9]|3[01])\$[./A-Za-z0-9]{53}$'
    );
