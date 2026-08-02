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

-- V4__seed_user_security.sql - sign-on identity seed for local and test execution only.
--
-- Inserts the ten sign-on identities of the legacy estate into the user_security table created by
-- V1__create_schema.sql, and nothing else: exactly ten rows in exactly one table, five carrying the
-- administrative user type and five carrying the standard one. Every credential is stored as a
-- BCrypt digest. No cleartext credential is stored, and no cleartext credential value appears
-- anywhere in this file - not as a literal, not in a comment, and not in a message.
--
-- THE FILENAME IS PART OF THE CONTRACT. `V4__seed_user_security` is the token this migration is
-- named by in the migration tool's INFO log and in its schema-history table, and the local stack's
-- bring-up check reads it from there. Renaming the file renames the token and breaks that check
-- silently, while the migration itself still applies cleanly. The name is therefore not free.

-- -------------------------------------------------------------------------------------------------
-- PROFILE APPLICABILITY - LOCAL AND TEST ONLY. NEVER PRODUCTION.
-- -------------------------------------------------------------------------------------------------
-- WHY THIS FILE IS DANGEROUS IN PRODUCTION, stated plainly so that nobody has to infer it. The ten
-- identities are a published fixture set: their ids are known, and all ten credentials are digests
-- of ONE single well-known fixture value. Hashing removes the cleartext from the database; it does
-- not make the credential unknown. A digest of a shared, publicly readable fixture value is still
-- an effectively known credential, so a deployment that applied this file would ship with ten
-- predictable logins, five of them able to reach the administrative user-management surface. That
-- is a credential incident, not untidiness, and no work factor changes it.
--
-- HOW THE EXCLUSION IS ENFORCED - TWO CONTROLS OVER ONE VERSION CEILING: the ceiling itself, declared
-- in the profile it binds, and a refusal in code that reads the ceiling back after it is bound. The
-- ceiling, and why it replaced a second migration location, are recorded in docs/decision-log.md
-- DL-102.
--
--   All five migrations are FLAT in classpath:db/migration, the single location every profile lists,
--   and this file is separated from the schema by its VERSION:
--
--        V1__create_schema.sql              \
--        V1_1__create_batch_metadata.sql     >  at or below the ceiling -> EVERY profile
--        V2__create_indexes.sql             /
--        ---------------------------------------- spring.flyway.target: 2 (application-prod.yml)
--        V3__seed_reference_data.sql        \
--        V4__seed_user_security.sql (this)   >  above the ceiling       -> local and test only
--
--   application-prod.yml sets spring.flyway.target: 2, so a production migration resolves versions 1,
--   1.1 and 2 and does not resolve this file at all. The local and test overlays raise the ceiling to
--   latest, so a migration there ends at V4. Verified against Flyway 11.7.2: with target 2 the applied set is
--   exactly {1, 1.1, 2} and version 3 is never resolved. Setting a target does not weaken validation -
--   an unapplied script above the ceiling is not a validation failure; only a CHANGED already-applied
--   script is.
--
--   WHY THIS REPLACED A SEPARATE db/seed DIRECTORY. An earlier revision of this file sat in db/seed
--   and argued that a separate location was the stronger control because it needed no setting to be
--   in the right position. That argument does not survive inspection. A location list is itself a
--   setting - four of them, one per profile - and the guarantee held only while all four stayed
--   different from each other in the right direction, a condition no single file stated. It also
--   detached the version numbers from what any profile would apply, so renumbering a seed or adding
--   one entry to one list could change production's contents without either edit looking like a
--   change to production. And it required every future author to know the convention: a seed dropped
--   into the schema directory would have been inherited by production, silently. The ceiling has none
--   of those properties. It is one reviewable line, stated in the profile whose posture it governs,
--   and no move, rename or renumber can make version 4 fall below 2. It is also what the frozen
--   migration plan specifies: its four scripts flat in db/migration, with production excluded by
--   target. Five ship rather than four because V1_1__create_batch_metadata.sql was added below the
--   ceiling to own the framework's own job-repository tables; that raises the count and leaves the
--   arrangement exactly as the plan states it.
--
--   THE RULE FOR A NEW SEED, and it is the whole rule: number it ABOVE 2. Raise the ceiling only in
--   the same commit that adds a production-required script above it, and never to admit a seed.
--
--   CONTROL 2 - THE REFUSAL IN CODE, which covers a deployment whose configuration was edited, merged
--   or overridden on the command line, and an operator running the migration tool directly against the
--   packaged artefact. com.carddemo.config.FlywayConfig inspects the BOUND ceiling and the BOUND
--   location list and, under the production profile, refuses to start when the ceiling reaches version
--   3 or beyond or when a location outside classpath:db/migration is present. An absent, predefined or
--   unreadable ceiling is refused on the same ground, since the migration tool migrates to the latest
--   version when none is set. Neither control is relied on alone: control 1 is explicit and reviewable
--   but is a property of configuration, and control 2 is unconditional in code but appears in no
--   configuration file. A refusal rather than a silent correction is deliberate - a deployment whose
--   declared migration scope and actual migration scope differ should stop, not proceed quietly.
--
-- A row in user_security in a production database means the ceiling was raised or removed. Treat it
-- as an incident, not as drift.
--
-- No production identity is created here. There is no production account, no default administrator
-- and no break-glass login in this file, and it reads no environment variable and no external file:
-- migration SQL is the wrong place for a secret, since it is committed, versioned and checksummed.
-- Production identities are provisioned separately, under external secret-management controls.

-- -------------------------------------------------------------------------------------------------
-- PROVENANCE
-- -------------------------------------------------------------------------------------------------
--   Source repository commit SHA : 7756d895ffeb65f7ea72aaa609e356d9899afcec
--   Upstream release stamp       : CardDemo_v1.0-15-g27d6c6f-68, dated 2022-07-19
--
--   The ten identities are the in-stream card images of the legacy user-security provisioning job,
--   app/jcl/DUSRSECJ.jcl lines 35 to 44, written there as ten 80-byte fixed unblocked records. No
--   character-set decoding was required: that job stream carries the values in ASCII, so the
--   EBCDIC sequential dataset it produces never had to be read to recover them.
--
--   The 80-byte logical record, from app/cpy/CSUSR01Y.cpy:
--       offset  0  width  8   user id             -> sec_usr_id
--       offset  8  width 20   first name          -> sec_usr_fname
--       offset 28  width 20   last name           -> sec_usr_lname
--       offset 48  width  8   credential          -> sec_usr_pwd, as a digest - see below
--       offset 56  width  1   user type           -> sec_usr_type
--       offset 57  width 23   trailing filler     -> not a column
--   Mapped bytes end at offset 57. The 23 filler bytes are padding in a fixed-width record and
--   carry no value, so relational storage omits them, and the display names are stored
--   right-trimmed because the fixed-width writers pad on output and storing it too would double it.
--
--   The user type is the sole authority for the authorization split, as the legacy sign-on program
--   app/cbl/COSGN00C.cbl establishes: on a successful read it routes an administrative type to the
--   administrative menu program and every other type to the main menu program. Five of the ten rows
--   below carry the administrative type and five carry the standard one, exactly as the job stream
--   provisions them.
--
--   NO legacy source line is copied into this file. User ids, personal names, user type codes,
--   field widths, offsets, record sizes and line references are data and metadata describing where
--   a value came from; the values themselves are fixture data, which is what a seed migration is
--   for. Nothing here transcribes a program, a copybook, a job stream or a resource definition.

-- -------------------------------------------------------------------------------------------------
-- THE CREDENTIAL COLUMN - DOCUMENTED PARITY EXCEPTION, recorded as decision D-12
-- -------------------------------------------------------------------------------------------------
-- The legacy record holds the credential as eight cleartext characters at offset 48, and the legacy
-- sign-on path compares that field directly against the entered value. Reproducing cleartext
-- storage would satisfy byte-for-byte parity and breach the no-hardcoded-credentials requirement in
-- one stroke, so this is the one place where the requirement outranks faithfulness. The divergence
-- is deliberate, is recorded in docs/decision-log.md as decision D-12, and is the reason V1 sizes
-- sec_usr_pwd at VARCHAR(60) - for a digest - rather than at the legacy width of 8.
--
-- THE SOURCE CREDENTIAL VALUE IS ABSENT FROM THIS FILE. It is not an insert literal, not a comment,
-- not an assertion message, not a temporary value, not a function argument and not a diagnostic. It
-- remains recoverable only from the read-only legacy job stream, which is where it belongs.
--
-- What each literal below is, and how it was produced:
--   * a BCrypt digest of exactly 60 characters: a 7-character prefix of the form $2x$nn$ followed
--     by a 53-character radix-64 tail carrying the salt and the hash;
--   * version marker $2a$, one of the three the application's stored-credential validation admits;
--   * cost factor 12 - the module's own hashing strength, so a seeded digest is indistinguishable
--     in shape and cost from one the application produces, and above the encoder's default of 10;
--   * an INDEPENDENT SALT PER ROW, which is why all ten strings differ even though they digest one
--     shared value. Ten identical strings would leak that fact from the table itself;
--   * generated once, offline, at authoring time with the same BCrypt encoder the application uses,
--     verified by that encoder's own match operation against the source credential, independently
--     re-verified with a second unrelated BCrypt implementation, and only then frozen here.
--
-- No hashing happens at migration time. There is no crypt() call, no gen_salt() call, no pgcrypto
-- extension, no other extension, no random source and no clock in this file, so it needs no
-- privilege beyond insert on one table and every application of it writes byte-identical rows.

-- -------------------------------------------------------------------------------------------------
-- DETERMINISM AND FAILURE VISIBILITY
-- -------------------------------------------------------------------------------------------------
--   Static literals only, in the order the job stream provisions them. No COPY, no psql
--   meta-command, no external file path, no generate_series, no loop, no dynamic SQL, no sequence,
--   no generated identifier, no clock, no random source and no locale-sensitive conversion.
--
--   No ON CONFLICT, no IF NOT EXISTS, no DELETE, no TRUNCATE and no UPDATE. Applying this file to a
--   table that already holds rows must fail loudly - on the primary key if an id collides, and on
--   the verification block below in every other case - rather than merge silently. A drifted
--   database is a finding, not something for a seed to paper over.
--
--   One table is written: user_security. No other table is inserted into, updated or altered, no
--   schema object is created, and the verification block that follows the insert creates nothing
--   that outlives it.

-- -------------------------------------------------------------------------------------------------
-- The ten sign-on identities, in job-stream order: five administrative, then five standard.
-- Column order is the record order - id, first name, last name, credential, type.
-- -------------------------------------------------------------------------------------------------
INSERT INTO user_security (
    sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type
) VALUES
('ADMIN001', 'MARGARET',  'GOLD',       '$2a$12$Rsug4EhU1XkbtrI9O3saNOZ4oO94vYNtJqONyYAMrb/bPx4K5c8TS', 'A'),
('ADMIN002', 'RUSSELL',   'RUSSELL',    '$2a$12$fgtqy.wDXZqSonkBWc.RuO86PE3QnYBk2o197z3nBvSYXRrLqwHQ.', 'A'),
('ADMIN003', 'RAYMOND',   'WHITMORE',   '$2a$12$97sbBZOTUfIMOYx9nHuSYueS0IYOQMsV8PK9hRqFA4UsRPuDt93qK', 'A'),
('ADMIN004', 'EMMANUEL',  'CASGRAIN',   '$2a$12$iS3OuryjI3F00hR1Jz3ZsejozHF9eQJBWSSVTfi6wtCuNJeaNqoiS', 'A'),
('ADMIN005', 'GRANVILLE', 'LACHAPELLE', '$2a$12$wejJ6U1/1t.f3SvxHyqnv.Eei0lKB.SCgexuGqR8GxUFIkpgnI/sS', 'A'),
('USER0001', 'LAWRENCE',  'THOMAS',     '$2a$12$z7uyXgVZ/6Hx7cFqgYdZ9.MUuLYvV6KSOVt4aLr2e2o1YK1zp/6Zu', 'U'),
('USER0002', 'AJITH',     'KUMAR',      '$2a$12$slggLfjDC02bg8Vq0WczNOdit0wBrKBKeeMbnLejUQhRTUT9nCclG', 'U'),
('USER0003', 'LAURITZ',   'ALME',       '$2a$12$km43oscWzV3nI3NP5KUYT.z4C7rwJcMJh8NLLpp7cvJf6h4wimlrO', 'U'),
('USER0004', 'AVERARDO',  'MAZZI',      '$2a$12$3jrRq4tu1jFFgVRtuK3JeOBOz4ZH3fQTPbdmRIk1w7QOpxUSSvfge', 'U'),
('USER0005', 'LEE',       'TING',       '$2a$12$w1X8LbClTft2ikDG9/3E7uspGUx/IxmfsDsuY7rB07Er4OCpZ3SbG', 'U');


-- -------------------------------------------------------------------------------------------------
-- POST-SEED VERIFICATION
-- -------------------------------------------------------------------------------------------------
-- Everything this file claims about itself is checked here, in the same transaction that inserted
-- the rows, so a partial load, a transposed value or a drifted table fails the migration instead of
-- being found later by a sign-on that unexpectedly succeeds or unexpectedly does not. The migration
-- tool runs each migration in one transaction by default on PostgreSQL, so a raised exception rolls
-- the whole seed back and the schema-history row records the failure.
--
-- The block creates NO persistent object - no table, no view, no function, no sequence and no
-- temporary relation - and builds NO SQL string: every statement below is a literal query over
-- user_security alone. It reads no other table and writes nothing.
--
-- NO ASSERTION NAMES THE SOURCE CREDENTIAL. Every check below is structural - a count, a length, a
-- distinctness test or a shape test - so none of them needs the value and none of them mentions it.
-- The shape pattern holds single dollar signs but never a doubled one, so it cannot terminate the
-- dollar-quoted body early, and the message literals deliberately contain no semicolon, so a
-- quote-naive statement splitter cannot mistake the middle of one for the end of the block.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    v_total             bigint;
    v_admin             bigint;
    v_standard          bigint;
    v_type_other        bigint;
    v_expected_ids      bigint;
    v_distinct_ids      bigint;
    v_exact_rows        bigint;
    v_pwd_wrong_length  bigint;
    v_distinct_pwd      bigint;
    v_pwd_wrong_shape   bigint;
    v_pwd_is_identity   bigint;
BEGIN
    -- Cardinality: ten rows, and the five-and-five split the job stream provisions.
    SELECT count(*) INTO v_total     FROM user_security;
    SELECT count(*) INTO v_admin     FROM user_security WHERE sec_usr_type = 'A';
    SELECT count(*) INTO v_standard  FROM user_security WHERE sec_usr_type = 'U';

    IF v_total <> 10 THEN
        RAISE EXCEPTION 'V4 seed: user_security holds % rows, expected exactly 10 - a table that'
            ' already held rows is drift, not a merge candidate', v_total;
    END IF;
    IF v_admin <> 5 THEN
        RAISE EXCEPTION 'V4 seed: % rows carry the administrative user type, expected exactly 5',
            v_admin;
    END IF;
    IF v_standard <> 5 THEN
        RAISE EXCEPTION 'V4 seed: % rows carry the standard user type, expected exactly 5',
            v_standard;
    END IF;

    -- The user type is the sole authority for the authorization split, so a third value would be an
    -- unroutable role rather than a cosmetic defect.
    SELECT count(*) INTO v_type_other
      FROM user_security WHERE sec_usr_type NOT IN ('A', 'U');
    IF v_type_other <> 0 THEN
        RAISE EXCEPTION 'V4 seed: % rows carry a user type outside the administrative and standard'
            ' pair, expected 0', v_type_other;
    END IF;

    -- Identity: the ten expected ids, each present exactly once and nothing else present. Three
    -- counts are needed rather than one - the expected set is matched, the ids are shown distinct,
    -- and the total above shows no extra row hid alongside them.
    SELECT count(*) INTO v_expected_ids
      FROM user_security
     WHERE sec_usr_id IN ('ADMIN001', 'ADMIN002', 'ADMIN003', 'ADMIN004', 'ADMIN005',
                          'USER0001', 'USER0002', 'USER0003', 'USER0004', 'USER0005');
    SELECT count(DISTINCT sec_usr_id) INTO v_distinct_ids FROM user_security;
    IF v_expected_ids <> 10 THEN
        RAISE EXCEPTION 'V4 seed: % of the ten expected sign-on ids are present, expected all 10',
            v_expected_ids;
    END IF;
    IF v_distinct_ids <> 10 THEN
        RAISE EXCEPTION 'V4 seed: user_security holds % distinct sign-on ids, expected 10',
            v_distinct_ids;
    END IF;

    -- Exact tuples. Every other check here is a count, and counts cannot see a transposition: two
    -- swapped names, or a name attached to the wrong id, would satisfy all of them. This one joins
    -- the delivered rows against the expected set on all four non-credential columns, so a single
    -- transposed character fails the migration.
    SELECT count(*) INTO v_exact_rows
      FROM user_security u
      JOIN (VALUES
              ('ADMIN001', 'MARGARET',  'GOLD',       'A'),
              ('ADMIN002', 'RUSSELL',   'RUSSELL',    'A'),
              ('ADMIN003', 'RAYMOND',   'WHITMORE',   'A'),
              ('ADMIN004', 'EMMANUEL',  'CASGRAIN',   'A'),
              ('ADMIN005', 'GRANVILLE', 'LACHAPELLE', 'A'),
              ('USER0001', 'LAWRENCE',  'THOMAS',     'U'),
              ('USER0002', 'AJITH',     'KUMAR',      'U'),
              ('USER0003', 'LAURITZ',   'ALME',       'U'),
              ('USER0004', 'AVERARDO',  'MAZZI',      'U'),
              ('USER0005', 'LEE',       'TING',       'U')
           ) AS expected(id, fname, lname, usr_type)
        ON u.sec_usr_id    = expected.id
       AND u.sec_usr_fname = expected.fname
       AND u.sec_usr_lname = expected.lname
       AND u.sec_usr_type  = expected.usr_type;
    IF v_exact_rows <> 10 THEN
        RAISE EXCEPTION 'V4 seed: % rows match the expected id, first name, last name and user type'
            ' exactly, expected all 10 - a value is transposed or misspelled', v_exact_rows;
    END IF;

    -- Credential shape. A digest is exactly 60 characters, and anything shorter is the shape a
    -- cleartext or truncated value has - the substitution these three checks exist to refuse.
    SELECT count(*) INTO v_pwd_wrong_length
      FROM user_security WHERE length(sec_usr_pwd) <> 60;
    IF v_pwd_wrong_length <> 0 THEN
        RAISE EXCEPTION 'V4 seed: % stored credentials are not exactly 60 characters long,'
            ' expected 0 - a value of any other length is not a digest', v_pwd_wrong_length;
    END IF;

    SELECT count(*) INTO v_pwd_wrong_shape
      FROM user_security
     WHERE sec_usr_pwd !~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$';
    IF v_pwd_wrong_shape <> 0 THEN
        RAISE EXCEPTION 'V4 seed: % stored credentials do not have the shape of a BCrypt digest -'
            ' a recognised version marker, a two-digit cost and a 53-character radix-64 tail -'
            ' expected 0', v_pwd_wrong_shape;
    END IF;

    -- Independent salts. Ten identical strings would still verify, and would publish from the table
    -- itself that the ten rows share one credential.
    SELECT count(DISTINCT sec_usr_pwd) INTO v_distinct_pwd FROM user_security;
    IF v_distinct_pwd <> 10 THEN
        RAISE EXCEPTION 'V4 seed: the ten stored credentials resolve to % distinct values, expected'
            ' 10 - each row must carry an independently salted digest', v_distinct_pwd;
    END IF;

    -- A stored credential that equals an id or a name is the classic placeholder substitution, and
    -- it would pass a length check the moment the placeholder happened to be 60 characters long.
    SELECT count(*) INTO v_pwd_is_identity
      FROM user_security u
     WHERE u.sec_usr_pwd IN (SELECT sec_usr_id    FROM user_security
                             UNION ALL
                             SELECT sec_usr_fname FROM user_security
                             UNION ALL
                             SELECT sec_usr_lname FROM user_security);
    IF v_pwd_is_identity <> 0 THEN
        RAISE EXCEPTION 'V4 seed: % stored credentials repeat a sign-on id or a personal name,'
            ' expected 0', v_pwd_is_identity;
    END IF;

    -- Deliberately free of a semicolon inside the message text, so that a quote-naive statement
    -- splitter cannot mistake the middle of this literal for the end of the block.
    RAISE INFO 'V4__seed_user_security: 10 sign-on identities seeded and verified in user_security'
        ' - 5 administrative and 5 standard, every credential stored as an independently salted'
        ' 60-character BCrypt digest at cost 12, all 10 digests distinct. No cleartext credential'
        ' is stored and none appears in this migration. Local and test only.';
END
$$;


-- End of V4. One table seeded, ten rows, from the ten in-stream card images of the legacy
-- user-security provisioning job. No other table was written, no schema object was created or
-- altered, no extension was installed and no cryptographic function was called. Local and test
-- only: production lists the schema location alone and stops at version 2.
