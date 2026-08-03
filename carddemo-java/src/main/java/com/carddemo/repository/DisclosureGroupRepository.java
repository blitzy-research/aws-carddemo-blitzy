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

import com.carddemo.domain.DisclosureGroup;
import com.carddemo.domain.id.DisclosureGroupId;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data-access contract for the {@code disclosure_group} interest-rate lookup table, which replaces
 * the disclosure-group indexed base cluster of the legacy estate. Rows are addressed by the
 * three-part business key modelled as {@link DisclosureGroupId}, and the accrual tier reads them to
 * discover the rate that applies to a given account group, transaction type and transaction
 * category.
 *
 * <p>This interface declares no members. Every access path it needs is inherited: single-row
 * retrieval by composite key, existence checks, counting, unsorted and sorted enumeration, paged
 * enumeration, and persistence of a row. No derived finder, no declared query and no projection is
 * added, because the legacy design exposes exactly one access path to this cluster - a keyed read -
 * and the schema carries no secondary index over this table for anything else to exploit.
 * Consequently the interface also carries no stereotype annotation: the repository infrastructure
 * discovers and implements it from the component scan rooted at the application's base package.
 *
 * <h2>Legacy provenance</h2>
 *
 * <p>The record layout comes from the disclosure-group copybook {@code CVTRA02Y}, whose header
 * declares a record length of 50. Its key group is named {@code DIS-GROUP-KEY} - not the
 * {@code TRAN-CAT-KEY} of the two neighbouring reference copybooks - and spans 16 bytes beginning at
 * offset 0, composed of three components:
 *
 * <ul>
 *   <li>{@code DIS-ACCT-GROUP-ID} - account group identifier, alphanumeric, 10 bytes at offset 0</li>
 *   <li>{@code DIS-TRAN-TYPE-CD} - transaction type code, alphanumeric, 2 bytes at offset 10</li>
 *   <li>{@code DIS-TRAN-CAT-CD} - transaction category code, external decimal, 4 bytes at
 *       offset 12</li>
 * </ul>
 *
 * <p>{@code DIS-INT-RATE} follows as a signed external-decimal rate of four integer and two decimal
 * digits, occupying 6 bytes at offset 16, and a 28-byte trailing filler at offset 22 completes the
 * record without being persisted. The arithmetic closes exactly: 10 + 2 + 4 = 16 for the key, and
 * 16 + 6 + 28 = 50 for the record.
 *
 * <p>Two independent sources corroborate that layout rather than merely restating it. The cluster
 * definition for the disclosure-group dataset specifies {@code KEYS(16 0)} and
 * {@code RECORDSIZE(50 50)} on an {@code INDEXED} cluster, and the file-section declaration inside
 * the batch interest-accrual program lists the same three components in the same order at the same
 * widths, followed by a 34-byte remainder that is precisely the 6-byte rate plus the 28-byte filler.
 *
 * <p><strong>Why the identifier is the legacy business key.</strong> The key offset of 0 makes the
 * key the leading substring of the record image, so the identity of a row <em>is</em> the 16-byte
 * key rather than something derived from it. No surrogate identifier exists here or on the owning
 * entity, and none may be introduced: a generated key would sever the record-image-to-row
 * correspondence that byte-level output parity depends on. This is the same structural reason every
 * key offset in this schema is 0.
 *
 * <h2>Parity warning 1 - key values are space-padded and are never trimmed</h2>
 *
 * <p>The account group identifier is a fixed-width 10-byte field, so its values occupy the whole
 * field and any padding is part of the stored key rather than incidental whitespace. All three group
 * identifiers present in the reference data are exactly ten characters wide, and two of them carry
 * trailing spaces: the zero-filled identifier {@code A000000000} needs no padding, whereas the
 * seven-character fallback-group identifier and the seven-character zero-rate-group identifier are
 * each padded with three trailing spaces to reach the full ten.
 *
 * <p>This matters because the seven-character form of either identifier resolves nothing at all,
 * while its ten-character padded form resolves a seeded row. The legacy behaviour that creates the
 * padded value is not incidental: the accrual program's fallback assigns a seven-character literal
 * into the 10-byte alphanumeric group-identifier field, and a legacy alphanumeric assignment
 * left-justifies and space-fills to the width of the receiving field. The padded value is therefore
 * the real lookup key, and callers must construct keys at full width.
 *
 * <p>Nothing anywhere in this module trims, strips, pads, folds or normalises a key component -
 * not the composite key type's equality and hashing, not the owning entity's accessors, and not
 * this interface. The schema reinforces the same rule from the database side: the group-identifier
 * column is a bounded variable-length character column and deliberately not a blank-padded
 * fixed-length one, because a blank-padded column compares its values with implicit padding and
 * would silently make the padded and unpadded forms equal - erasing exactly the distinction that
 * has to survive.
 *
 * <h2>Parity warning 2 - assignment order is not key order</h2>
 *
 * <p>The batch interest-accrual program populates the lookup key with three consecutive assignment
 * statements whose textual order is account group identifier, then transaction <em>category</em>
 * code, then transaction <em>type</em> code. That is category-before-type, and it is <em>not</em>
 * the order of the key.
 *
 * <p>The key's component order is group, then <strong>type</strong>, then <strong>category</strong>,
 * and it is fixed three ways: by the copybook's declaration order and widths, by the cluster's
 * {@code KEYS(16 0)} taken together with those widths, and by the accrual program's own
 * file-section declaration. The primary-key column order of the {@code disclosure_group} table
 * follows the same sequence.
 *
 * <p>{@link DisclosureGroupId}'s all-arguments constructor accordingly takes group, type, category,
 * and <strong>that order must never be "corrected" to match the assignment sequence</strong>. The
 * three assignments target three distinct named fields, so their textual order carries no ordering
 * semantics whatsoever. Reordering the constructor to follow them would transpose two components of
 * every lookup and would resolve the wrong row - or no row - while still compiling cleanly and
 * while still satisfying any test written under the same misunderstanding. Both components are
 * short character values, so no type error would expose the transposition. This is the exact class
 * of defect that the migration's faithful-over-idiomatic tie-break exists to prevent.
 *
 * <h2>The lookup-and-fallback cascade lives in the service tier</h2>
 *
 * <p>The legacy rate lookup normalises two file statuses as a single non-error outcome - success
 * and record-not-found alike - and only on record-not-found substitutes the padded fallback group
 * identifier into the group-identifier component and re-reads <em>exactly once</em>. A miss on that
 * single retry is terminal. Interest is then computed only when the resolved rate is non-zero, and
 * the fee routine invoked alongside it is empty in the legacy program and is preserved as a
 * documented no-op rather than filled in.
 *
 * <p>That entire cascade - the status normalisation, the substitution, the single bounded retry, the
 * non-zero guard and the terminal failure - belongs to the interest-calculation service, which owns
 * the ordering and owns the fallback group identifier as a literal. None of it appears here. This
 * interface declares no fallback, no retry, no status enumeration and no exception type, and no
 * convenience method that resolves a default on the caller's behalf may be added to it: doing so
 * would move business logic into the data-access tier and would hide the bounded-retry semantics
 * that parity depends on.
 *
 * <p>The repository's sole contribution to that cascade is a negative result. A keyed retrieval that
 * matches no row yields an empty {@code Optional}, which is the precise analogue of the legacy
 * record-not-found file status that triggers the substitution. The two-level status model the batch
 * tier layers over raw file statuses is likewise a service and batch concern, not a repository one.
 *
 * <h2>What the seeded reference data does and does not exercise</h2>
 *
 * <p>The reference seed loads exactly 51 rows, being three distinct group identifiers at 17 rows
 * each, and it is scoped to the local and test profiles so that neither sample data nor seeded
 * credentials reach a production migration.
 *
 * <p>An honest consequence follows, and it is recorded here rather than glossed over: every one of
 * the 50 seeded account rows carries ten spaces as its group identifier. Ten spaces match none of
 * the three seeded group identifiers, so seeded data alone always drives the fallback path. That is
 * genuinely useful - it makes the fallback branch provably reachable from the seed - but it also
 * means the direct-hit branch is reachable only from a purpose-built fixture that points an account
 * at one of the three real groups. The zero-rate group is seeded with a zero rate on all 17 of its
 * rows, so the non-zero guard's skip branch is reachable the same way.
 *
 * <h2>Why every key component is a character type</h2>
 *
 * <p>External representation is part of the contract, so each component maps to a bounded character
 * column rather than to a numeric one, and the composite key type declares all three as character
 * values. The category code is the decisive case: it is a zero-filled 4-byte external-decimal field,
 * and a value of {@code "0005"} must remain {@code "0005"} rather than collapsing to {@code 5}. A
 * numeric mapping would discard both the leading zeros and the field width, after which the 16-byte
 * key image could no longer be reconstructed from the row. The group identifier would additionally
 * lose the trailing padding that the fallback path above depends on.
 *
 * <h2>The rate is carried as an exact decimal and is never computed here</h2>
 *
 * <p>The owning entity maps the rate to a {@code BigDecimal} at scale 2 over an exact numeric column
 * of precision 6 and scale 2. That precision is unique in the eleven-table schema - every other
 * amount column in the module is wider - so it must not be copied from a sibling entity. An
 * approximate binary numeric type is prohibited throughout this migration and would not reproduce
 * the legacy decimal representation, whose sign is carried by an overpunch on the final byte of the
 * field.
 *
 * <p>No arithmetic, scaling, rounding, parsing or formatting occurs in this file, and no byte-offset
 * knowledge is applied here. Fixed-width decoding and scaling are the sole responsibility of the
 * zoned-decimal codec in the utility tier, which applies one truncating policy uniformly so that no
 * other component can introduce a different one; the legacy estate declares no rounding clause
 * anywhere, so every store into a two-decimal field truncates toward zero. The accrual computation
 * that consumes the rate is order-sensitive - the balance is multiplied by the rate first and the
 * product divided by the monthly divisor second - and reproducing that operand order is the accrual
 * service's obligation, not this interface's.
 *
 * <h2>Access characteristics</h2>
 *
 * <p>This is a batch-only reference table. The legacy resource definitions register only eight
 * files for online transaction access and the disclosure-group cluster is not among them, so no
 * interactive endpoint reaches this data; the accrual job is its only consumer.
 *
 * <p><strong>No foreign key references this table, by design, and that absence must be documented
 * rather than repaired.</strong> The account table's group identifier is not a foreign key here and
 * cannot usefully be one: it corresponds to only the first of three key components and is nonunique
 * on its own, recurring once per type-and-category combination - 17 times per group in the verified
 * reference data. A single-column reference to a partial composite prefix is not expressible as a
 * foreign key, and promoting it to one would fabricate a constraint the legacy design never had and
 * would reject the very unmatched group value that all 50 seeded accounts carry and that the
 * fallback exists to absorb. Resolution is a runtime lookup with a bounded retry, not referential
 * integrity. The index migration creates six foreign keys and none of them touches this table.
 *
 * <p>Isolation and concurrency are left to the transaction the caller runs in. This table is
 * read-only in normal operation - it is reference data written by a migration, not by application
 * flow - so no optimistic-locking attribute is mapped on the owning entity and no locking or
 * transactional directive is declared here.
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the legacy estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy tree is read-only reference and
 * is never copied, transcribed or reached at run time: no legacy artefact appears on the classpath
 * and nothing here resolves a path or resource into it.
 *
 * @see DisclosureGroup
 * @see DisclosureGroupId
 */
public interface DisclosureGroupRepository
        extends JpaRepository<DisclosureGroup, DisclosureGroupId> {
}
