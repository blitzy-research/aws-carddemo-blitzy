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

-- V1_1__create_batch_metadata.sql - Spring Batch job-repository metadata.
--
-- Creates the six framework metadata tables and three sequences that Spring Batch requires in order
-- to persist a job instance, a job execution, its parameters, its step executions and the two
-- execution contexts. Nothing in this file belongs to the application: it is infrastructure the job
-- repository owns, and it is kept in its own migration precisely so that it can never be mistaken
-- for one of the eleven application tables V1 creates.
--
-- WHY THIS FILE EXISTS AT ALL
--
--   Every profile sets spring.batch.jdbc.initialize-schema: never, so the framework does not create
--   its own tables on start-up. That setting is correct - a framework that silently issues DDL
--   against a production database defeats the point of having a forward-only, checksum-validated
--   migration history - but it is only HALF a decision. Turning the framework's initializer off
--   without giving the tables an owner leaves a database on which the first job launch fails with a
--   missing-relation error, which is what a production deployment would have discovered on its first
--   scheduled run. This migration is the other half: it makes the migration history the single owner
--   of every table in the database, framework tables included.
--
-- WHY THE VERSION IS 1.1 AND NOT 5
--
--   Production excludes the two seed scripts with spring.flyway.target: 2, which applies every
--   version up to and including 2 and refuses to look at anything above it. A metadata migration
--   numbered above 2 would therefore reach local and test and never reach production - the one
--   environment that cannot fall back on the framework initializer. 1.1 is the only kind of slot
--   that satisfies both constraints at once: it sorts after 1 and before 2, so target: 2 applies it;
--   and it is a NEW version rather than an edit, so the checksums of V1 and V2 are untouched and
--   their already-applied history stays valid. Verified against Flyway 11.7.2: with target 2, the
--   applied set is exactly {1, 1.1, 2} and version 3 is not resolved.
--
--   A repeatable R__ script would also have reached production, and was rejected. Repeatable scripts
--   re-run whenever their checksum changes, which requires guarded DDL; guarded DDL turns a framework
--   upgrade that changes a column into a silent no-op instead of a loud failure.
--
-- ONE-TIME NOTE FOR AN ALREADY-MIGRATED DATABASE
--
--   Inserting a new version BELOW the current schema version is, by design, something Flyway refuses
--   to apply silently: a database already at version 4 reports "Detected resolved migration not
--   applied to database: 1.1" and fails validation rather than running this file out of order. That
--   is the correct behaviour and it is not a defect in this migration. A long-lived local database
--   created before this file existed must therefore be re-created once. Test databases are created
--   per run and are unaffected, and no production database exists yet.
--
-- The decision to place the framework schema at 1.1, below the production ceiling and beside the
-- application schema rather than after the seeds, is recorded in docs/decision-log.md DL-102.
--
-- PROVENANCE - THIS DDL IS TRANSCRIBED, NOT AUTHORED
--
--   Source of truth: org/springframework/batch/core/schema-postgresql.sql inside
--   spring-batch-core-5.2.6.jar, the exact artifact this module resolves. The statements below are
--   reproduced from it without alteration - same tables, same column names, same widths, same
--   nullability, same constraint names, same sequence bounds - so that a byte comparison against the
--   framework's own file is the whole review, and a future framework upgrade is a visible diff rather
--   than a guess. Identifiers are left unquoted exactly as the framework writes them, which matters:
--   PostgreSQL folds an unquoted identifier to lower case both here and in the framework's own
--   queries, so the created relations are the relations the job repository then addresses. Quoting
--   them, or lower-casing them by hand to match this module's application-table style, would break
--   that agreement.
--
--   Contents, and the count that must not drift: SIX tables - batch_job_instance,
--   batch_job_execution, batch_job_execution_params, batch_step_execution,
--   batch_step_execution_context, batch_job_execution_context - and THREE sequences -
--   batch_step_execution_seq, batch_job_execution_seq, batch_job_seq. Five foreign keys are named
--   (job_inst_exec_fk, job_exec_params_fk, job_exec_step_fk, step_exec_ctx_fk, job_exec_ctx_fk) and
--   one unique constraint is named (job_inst_un).
--
--   No existence guard appears on any statement, matching V1: schema drift fails this migration
--   loudly instead of being absorbed. This file inserts no row.
--
-- Validated against PostgreSQL 16.14 initialized --encoding=UTF8 --locale=C.UTF-8. Recorded in
-- docs/decision-log.md.

CREATE TABLE BATCH_JOB_INSTANCE  (
	JOB_INSTANCE_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT ,
	JOB_NAME VARCHAR(100) NOT NULL,
	JOB_KEY VARCHAR(32) NOT NULL,
	constraint JOB_INST_UN unique (JOB_NAME, JOB_KEY)
) ;

CREATE TABLE BATCH_JOB_EXECUTION  (
	JOB_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT  ,
	JOB_INSTANCE_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL ,
	END_TIME TIMESTAMP DEFAULT NULL ,
	STATUS VARCHAR(10) ,
	EXIT_CODE VARCHAR(2500) ,
	EXIT_MESSAGE VARCHAR(2500) ,
	LAST_UPDATED TIMESTAMP,
	constraint JOB_INST_EXEC_FK foreign key (JOB_INSTANCE_ID)
	references BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
) ;

CREATE TABLE BATCH_JOB_EXECUTION_PARAMS  (
	JOB_EXECUTION_ID BIGINT NOT NULL ,
	PARAMETER_NAME VARCHAR(100) NOT NULL ,
	PARAMETER_TYPE VARCHAR(100) NOT NULL ,
	PARAMETER_VALUE VARCHAR(2500) ,
	IDENTIFYING CHAR(1) NOT NULL ,
	constraint JOB_EXEC_PARAMS_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE BATCH_STEP_EXECUTION  (
	STEP_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT NOT NULL,
	STEP_NAME VARCHAR(100) NOT NULL,
	JOB_EXECUTION_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL ,
	END_TIME TIMESTAMP DEFAULT NULL ,
	STATUS VARCHAR(10) ,
	COMMIT_COUNT BIGINT ,
	READ_COUNT BIGINT ,
	FILTER_COUNT BIGINT ,
	WRITE_COUNT BIGINT ,
	READ_SKIP_COUNT BIGINT ,
	WRITE_SKIP_COUNT BIGINT ,
	PROCESS_SKIP_COUNT BIGINT ,
	ROLLBACK_COUNT BIGINT ,
	EXIT_CODE VARCHAR(2500) ,
	EXIT_MESSAGE VARCHAR(2500) ,
	LAST_UPDATED TIMESTAMP,
	constraint JOB_EXEC_STEP_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT  (
	STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT ,
	constraint STEP_EXEC_CTX_FK foreign key (STEP_EXECUTION_ID)
	references BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
) ;

CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT  (
	JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT ,
	constraint JOB_EXEC_CTX_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE SEQUENCE BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
