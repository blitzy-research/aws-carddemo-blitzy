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

import com.carddemo.domain.DailyTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for table {@code daily_transaction} &mdash; the landing surface holding the raw,
 * deliberately unvalidated daily input that the posting pipeline consumes.
 *
 * <p>Rows arrive here exactly as the sequential input presented them. The posting tier then reads each
 * one, validates it against reference data, and either posts it to the ledger or rejects it with a
 * numbered reason. This interface is the whole persistence contract for that surface, and it
 * deliberately declares nothing of its own: every operation it offers is inherited.
 *
 * <p><strong>Legacy provenance.</strong> The persisted shape is copybook {@code CVTRA06Y}, a 350-byte
 * record whose layout is byte-for-byte identical to the posted-transaction record &mdash; the same
 * fields, in the same order, at the same offsets, with the same widths &mdash; differing only in that
 * every field here carries the {@code dalytran} prefix. The positions a reader of this file needs are
 * the 16-byte business key at offset 0, the signed two-decimal amount at offset 132 for 11, the card
 * number at offset 262 for 16, the origination stamp at offset 278 for 26, and the processing stamp at
 * offset 304 for 26. A 20-byte trailing filler at offset 330 completes the 350 bytes; it carries no
 * information, so it is neither an attribute of the entity nor a column of the table, and is
 * reconstructed from the declared record width whenever a fixed-width image has to be emitted. Slicing
 * a record image at those offsets is the record mapper's work in the utility layer, never this
 * interface's: no offset arithmetic, parsing or formatting appears here.
 *
 * <p><strong>This layout has no cluster definition, and that fact determines its access pattern.</strong>
 * Alone among the eleven migrated record layouts, it was never defined as a keyed indexed cluster. It
 * arrived as a sequential dataset staged for the posting job, which is what makes it a landing area
 * rather than a master file. It is absent from the online resource definition too, which registers eight
 * files and not this one, so the legacy system offered no online access path to this data at all &mdash;
 * only batch readers reached it. That is precisely why nothing resembling a keyed lookup beyond identity
 * is offered below.
 *
 * <p><strong>Identity is the legacy business key, carried as text.</strong> The identifier type argument
 * is {@link String} because the key column is a bounded 16-character value whose zero padding is
 * contractual: the reference input is right-aligned and zero-filled to the full width, so every one of
 * those leading zeros has to survive a round trip and the value must never collapse to a shorter numeric
 * form. No generated, sequence-backed or surrogate identifier is declared anywhere in this module. The
 * batch file descriptions split each record into a leading key group followed by a data area, which
 * makes the key the leading portion of the record image, and on this table specifically
 * a surrogate would make it impossible to echo the original 350-byte source image back into a reject
 * record.
 *
 * <p><strong>Merchant-column prefix asymmetry &mdash; the likeliest mapping error in this package.</strong>
 * Because the two record layouts are byte-for-byte identical and every non-merchant column differs
 * between the two tables only by prefix, it is tempting to assume one column-name set serves both. It
 * does not. On <em>this</em> table all thirteen columns carry the {@code dalytran} prefix, the four
 * merchant columns included, whereas the posted-transaction table leaves its four merchant columns
 * unprefixed. The divergence is a property of the migrated schema, is recorded in the project decision
 * log, and must not be regularized in either direction. Schema validation is active, so a mismatch fails
 * the application context at startup rather than corrupting data later &mdash; the good outcome, but only
 * because the mapping was made correctly the first time.
 *
 * <p><strong>Two entities, two tables, no shared supertype.</strong> The landing record and the posted
 * record are modelled as independent entities over independent tables with independent lifecycles: one
 * is unvalidated input, the other is the settled ledger. Nothing is shared between them &mdash; no mapped
 * superclass, no shared abstract entity, no common interface, no generic base repository, and no
 * reference in either direction. Introducing any of those would impose one set of column names on both
 * tables, silently destroying the asymmetry described above, and would couple two deliberately
 * independent lifecycles.
 *
 * <p><strong>Zero foreign keys, in every migration version, on purpose &mdash; and load-bearing.</strong>
 * No migration defines a foreign key on this table: none from the card number to the card table, none
 * from the type or category code to the reference tables, none anywhere else. No secondary index is
 * created on it either. Both omissions are deliberate. The posting program validates every record
 * itself, in source order, stopping at the first failure, and emits a numbered reject reason: reason 100
 * for an invalid card number, 101 for an account not found on read, 102 for an overlimit transaction,
 * 103 for a transaction received after account expiration, and 109 for an account not found when the
 * updated balance is written back. Three of those five are exactly the referential failures a database
 * constraint would pre-empt. A foreign key would refuse such a row at insert time, those code paths
 * would become unreachable, and the reject output could never be produced for them &mdash; a 430-byte
 * record formed from the 350-byte source image followed by an 80-byte trailer of a four-digit reason code
 * plus a 76-character description. That output is contractual and is verified byte for byte, so adding a
 * constraint here, or a unique constraint beyond the primary key, or a declarative validation
 * constraint on the entity, would break parity rather than improve integrity.
 *
 * <p>For the same reason this interface carries no validation of any kind, no status enumeration, no
 * exception type and no reason-code constant. Validation, reason-code assignment and reject-record
 * assembly all belong to the batch step classes; the balance and overlimit arithmetic belongs to the
 * posting service. The only failure this layer expresses is absence, as the empty result of an inherited
 * lookup by identity.
 *
 * <p><strong>Money is exact, and it is not scaled here.</strong> The amount is an exact decimal of
 * precision 11 and scale 2, and no approximate binary numeric type may ever stand in for it. A census of
 * the legacy estate found no rounding clause on any arithmetic statement, so every store into a
 * two-decimal field truncates toward zero; all scaling is therefore funnelled through the utility
 * layer's zoned-decimal codec, which applies that truncating policy uniformly ({@code RoundingMode.DOWN})
 * rather than a half-even one. The overlimit check that yields reason 102 is evaluated strictly left to
 * right &mdash; cycle credit, less cycle debit, plus this amount &mdash; and because truncation makes
 * that arithmetic non-associative, algebraic rearrangement would change which records are rejected. None
 * of that computation occurs in this file: no arithmetic, no scaling and no aggregation over money is
 * performed or offered here.
 *
 * <p><strong>Codes stay raw, and nothing is trimmed on the way through.</strong> The origination-source
 * column carries the legacy ten-byte code as a raw string, never an enumeration constant. Every
 * character column here is a bounded variable-length one rather than a blank-padded fixed-length one,
 * and that choice is deliberate: the column stores exactly the value it is given and returns exactly
 * that value, neither re-padding it to the declared width nor stripping padding it already carries. Any
 * right-padding needed to fill a legacy field is therefore applied at the fixed-width boundary by the
 * record mapper, not silently by the database, and no value is ever trimmed on read. That property is
 * load-bearing for the processing stamp, where 26 spaces is meaningful data rather than a missing value
 * and must reload as exactly 26 spaces &mdash; not null, not empty, not shortened.
 *
 * <p><strong>Measured composition of the seeded reference input, and what it can prove.</strong> The
 * reference-data migration, applied under the local and test profiles only, seeds exactly 300 rows from
 * the daily-input fixture, which measures 105,300 bytes: 300 records of 350 bytes plus one line
 * terminator each, whose source field is ten bytes wide. Of those, 250 carry the point-of-sale source
 * code with positive amounts and 50 carry the operator source code with negative amounts, so both signed
 * directions of the balance computation are reachable from seed data alone, and a negative amount is
 * ordinary data that must round-trip with its sign intact. All 300 origination stamps carry the identical
 * 26-character value {@code 2022-06-10 19:27:53.000000}, and all 300 processing stamps are 26 spaces,
 * because by definition these records have not been processed yet. Consequently date-window filtering
 * cannot be exercised from this fixture at all: a purpose-built fixture is required for that, and the
 * report-side date-range query belongs to the posted-transaction repository rather than to this one.
 *
 * <p><strong>Sequential reading is inherited, and ordering is the caller's.</strong> The batch read path
 * is the inherited sorted and paged {@code findAll} pair, taking a caller-supplied {@code Sort} or
 * {@code Pageable}. The consolidation job orders its merged inputs by transaction identifier ascending
 * and supplies that ordering itself; this interface declares no method for it, imposes no ordering of its
 * own, and names no page size, so a descending order is equally available to a caller that needs one.
 * Lookup by identity, existence checks, counting and persistence are likewise inherited unchanged. No
 * derived query method, no hand-written or native query, no specification or example executor, and no
 * bulk modification is declared: the legacy tier processes one record at a time, and a bulk update or
 * delete would be behaviour the legacy job never performed. Registration is automatic through the
 * component scan rooted at the application's base package, so no stereotype annotation is applied.
 *
 * <p><strong>A second, orphaned legacy reader of this layout.</strong> Besides the posting program, a
 * complete 491-line batch program in the estate also reads this record, and no job member, cataloged
 * procedure or online resource definition anywhere invokes it. It is migrated all the same, behind a
 * batch job that is defined but held out of the default pipeline and exercised only by tests. The orphan
 * wiring is a recorded source anomaly rather than dead code to be dropped, and it changes nothing about
 * this interface: both readers reach the data through the inherited operations described above.
 *
 * <p>Provenance: legacy sources are read-only reference at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @see DailyTransaction
 * @see JpaRepository
 * @since 1.0.0
 */
public interface DailyTransactionRepository extends JpaRepository<DailyTransaction, String> {
}
