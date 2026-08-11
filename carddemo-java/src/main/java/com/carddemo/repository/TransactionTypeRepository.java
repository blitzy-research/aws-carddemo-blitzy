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
package com.carddemo.repository;

import com.carddemo.domain.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access to the {@code transaction_type} reference lookup, which replaces the
 * {@code TRANTYPE} VSAM key-sequenced base cluster. The interface declares nothing: the keyed read, the
 * ordered and paged listings, and the save, existence and count operations a two-column reference table
 * needs are all inherited from {@link JpaRepository}, and a derived finder would establish a second,
 * independently maintained path to the same row.
 *
 * <h2>The identifier is the legacy business key, held as text</h2>
 *
 * <p>{@link TransactionType} identifies itself by the 2-byte type code in column {@code tran_type},
 * typed {@code String} so that a leading zero survives a round trip and the published width stays
 * exactly 2. The copybook declares the key at offset 0, which makes it the leading substring of the
 * stored record image rather than a stand-in for one. No generated value, sequence or identity column
 * exists for this table or for any other in this module: a surrogate would break the
 * record-image-to-row correspondence that byte-parity verification of the fixed-width outputs depends
 * on.
 *
 * <h2>Why no composite-key type exists for this table</h2>
 *
 * <p>The copybook declares its type-code field bare rather than nested inside a key group, the cluster
 * declares a single 2-byte key at offset 0, and the schema gives the table a single-column primary key.
 * There is consequently no identifier class for this entity and none may be introduced. The key column
 * is named {@code tran_type} and carries <strong>no {@code _cd} suffix</strong>: it is the similarly
 * shaped transaction-category reference table whose key is a nested 6-byte composite and which owns an
 * identifier class. Confusing the two names would not merely mislead a reader - the persistence
 * provider runs in validate mode on every profile, so a mapping naming a column the migration does not
 * define fails start-up outright rather than quietly at first use.
 *
 * <h2>A batch-only reference cluster with one access path</h2>
 *
 * <p>The CICS resource definition registers only eight files and this cluster is not among them, so it
 * was never reachable from an online transaction. Its one legacy reader is the transaction-report
 * program, to which the cataloged reporting procedure supplies the cluster through the
 * {@code TRANTYPE} DD of step {@code STEP10R}. The table is pure reference data whose readers decorate
 * a report or statement line with a type description and whose only writer is a seed.
 *
 * <p>The reference seed inserts exactly 7 rows, matching the 427-byte ASCII fixture - 7 records at the
 * 60-byte record length plus one terminator each. Seed scripts ship from
 * {@code classpath:db/migration/seed}, which only the local and test profiles resolve, while production
 * resolves {@code classpath:db/migration/schema} alone, so a production database receives the schema
 * and indexes without the sample rows; see {@code DL-298} in {@code docs/decision-log.md}. No index and
 * no foreign key in either direction touches this table, so the primary key is the only access path a
 * finder could serve, and no seeded row is held between calls: this module keeps no mutable static or
 * global state, so a description is resolved through the repository each time rather than from a copy
 * that could drift after a reseed.
 *
 * <h2>How a missing row is reported here</h2>
 *
 * <p>The legacy readers normalise a raw two-byte file status into a coarse result and branch on
 * condition names for success and end-of-file before abending on anything else. That normalisation, the
 * status enumeration it feeds and the abend path it ends in all live above this interface, which
 * declares no exception type, no status value and no failure mode of its own. Its entire expression of
 * record-not-found is the empty {@link java.util.Optional} returned by the inherited keyed read.
 *
 * <h2>Registration</h2>
 *
 * <p>No stereotype annotation is present or wanted: Spring Data discovers and implements this interface
 * through the repository scan rooted at the application's base package. Both type arguments are
 * supplied explicitly because the module compiles with all lint warnings promoted to errors, so a raw
 * supertype would fail the build. Schema evolution is owned entirely by the forward-only migration set.
 *
 * @see TransactionType
 */
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {
}
