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
 * {@code TRANTYPE} VSAM key-sequenced base cluster {@code AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS}.
 *
 * <p>This is the smallest of the eleven repositories in this package, and deliberately so: it declares
 * nothing whatsoever. Everything the two-column reference table needs is already inherited from
 * {@link JpaRepository} - {@code findById(String)} for the type-code lookup, {@code findAll()},
 * {@code findAll(Sort)} and {@code findAll(Pageable)} for an ordered or paged listing, and
 * {@code save}, {@code existsById} and {@code count} for the seed and its verification. A derived
 * finder or query method declared here would be redundant with one of those and would establish a
 * second, independently maintained path to the same row, so none is declared.
 *
 * <h2>Legacy provenance</h2>
 * The row this repository reads is the Java form of the transaction-type record declared in copybook
 * {@code CVTRA03Y}, whose stated record length is 60 bytes: a 2-byte type code at offset 0, a 50-byte
 * description at offset 2, and 8 trailing filler bytes at offset 52 that carry no information and are
 * therefore not persisted as a column. The geometry is corroborated independently by the cluster
 * definition, which declares {@code KEYS(2 0)} with {@code RECORDSIZE(60 60)} on an {@code INDEXED}
 * cluster: a key width of 2 at offset 0, and identical low and high record sizes confirming a
 * fixed-length layout. Translating that {@code INDEXED} key-sequenced cluster into a Spring Data JPA
 * repository, with its key becoming the entity identifier, is exactly what the file-section row of the
 * governing construct-mapping table requires.
 *
 * <h2>Why the identifier type is {@code String}, and why it is the legacy business key</h2>
 * The second type argument above is {@code String} because {@link TransactionType} identifies itself by
 * the 2-byte type code mapped to column {@code tran_type}, and that code is held as text so a leading
 * zero survives a round trip and the published width stays exactly 2. The key offset of 0 is what makes
 * this the business key rather than a stand-in for one: at offset 0 the key <em>is</em> the leading
 * substring of the stored record image, the same discipline the sequential batch programs apply when
 * they split a fixed-length record into a leading key field followed by a data remainder - the account
 * file being the clearest instance, an 11-digit key field followed by a 289-byte remainder that together
 * account for the whole 300-byte record. No generated value, sequence, identity column or other
 * machine-assigned substitute exists for this table or for any other in this module, because a surrogate
 * would break the record-image-to-row correspondence on which byte-parity verification of the
 * fixed-width outputs depends.
 *
 * <h2>Why no composite-key type exists for this table</h2>
 * {@code CVTRA03Y} declares its type-code field bare rather than nested inside a key group, and the
 * cluster declares a single 2-byte key at offset 0; correspondingly the schema migration gives
 * {@code transaction_type} a single-column primary key over {@code tran_type}. There is consequently no
 * identifier class for this entity, this file refers to nothing in the module's identifier-class package
 * beneath the domain layer, and no such class may be introduced for it. The key column is named
 * {@code tran_type} and carries <strong>no {@code _cd} suffix</strong>: the similarly shaped
 * transaction-category reference table, derived from {@code CVTRA04Y}, is the one whose key is a nested
 * group - a 2-byte {@code tran_type_cd} at offset 0 plus a 4-byte category code at offset 2, a 6-byte
 * composite - and it is the one that owns an identifier class. Confusing the two names would not merely
 * mislead a reader: the persistence provider runs in validate mode on every profile, so a mapping that
 * named a column the migration does not define would fail start-up outright rather than quietly at
 * first use.
 *
 * <h2>A batch-only reference cluster</h2>
 * The CICS resource definition {@code CARDDEMO.CSD} registers only eight files and {@code TRANTYPE} is
 * not among them, so this cluster was never reachable from an online transaction. Its one legacy reader
 * is the transaction-report program {@code CBTRN03C}, to which the cataloged procedure
 * {@code TRANREPT.prc} supplies the cluster through the {@code TRANTYPE} DD of step {@code STEP10R};
 * {@code CVTRA03Y} is included by that single program and by no other, the lowest inclusion count among
 * the eleven entity copybooks. The table is therefore pure reference data whose only readers decorate a
 * report or statement line with a type description, and whose only writer is a seed.
 *
 * <h2>Seeded content, and the absence of any alternate access path</h2>
 * The reference-data migration seeds exactly 7 rows, derived from
 * {@code app/data/ASCII/trantype.txt} - 427 bytes, that is 7 records at the 60-byte record length plus
 * one line terminator each. Those rows reach the local and test profiles only: all four migrations are
 * delivered flat from {@code classpath:db/migration}, which every profile resolves, and a production
 * migration stops after the index script, so a production database receives the schema and the indexes
 * without the sample rows.
 * The index migration creates no index touching {@code transaction_type}, and no foreign key anywhere in
 * the schema originates from or targets it, so the primary key is the only access path that exists for a
 * finder to serve. Nor are the seeded rows held anywhere between calls: this module keeps no mutable
 * static or global state, so every caller resolves a description through this repository each time
 * rather than from a copy that could drift from the table after a reseed.
 *
 * <h2>How a missing row is reported here</h2>
 * The file-status row of the construct-mapping table is honoured in the service and batch layers, not in
 * this one. The legacy readers normalise a raw two-byte status into a coarse result and branch on
 * condition names for the success and end-of-file cases before abending on anything else; that
 * normalisation, the status enumeration it feeds and the abend path it ends in all live above this
 * interface. This repository declares no exception type, no status value and no failure mode of its own,
 * and imports nothing from the exception or domain-enumeration packages. Its entire expression of
 * "record not found" is the empty {@link java.util.Optional} that the inherited {@code findById} returns
 * for an unseeded type code.
 *
 * <h2>Registration</h2>
 * No stereotype annotation is present or wanted. Spring Data discovers and implements this interface
 * through the repository scan rooted at the application's own base package, so annotating it would add
 * an import and a redundant declaration without changing the outcome. Both type arguments are supplied
 * explicitly for the same reason the module compiles with all lint warnings promoted to errors: a raw
 * supertype would fail the build rather than merely reading poorly. Schema evolution is owned entirely
 * by the forward-only migration set, and nothing here generates, alters or infers a schema object.
 *
 * <p><strong>Provenance.</strong> Translated from the read-only legacy estate at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy members cited above are reference
 * material only: they are never copied into this module and never read at run time.
 *
 * @see TransactionType
 */
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {
}
