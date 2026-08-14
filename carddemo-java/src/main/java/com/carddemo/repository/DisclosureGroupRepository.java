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
 * Data-access contract for the {@code disclosure_group} interest-rate lookup table, which replaces the
 * disclosure-group indexed base cluster of the legacy estate. Rows are addressed by the three-part
 * business key modelled as {@link DisclosureGroupId}, and the accrual tier reads them to discover the
 * rate that applies to a given account group, transaction type and transaction category. Every access
 * path is inherited from {@link JpaRepository}. The 50-byte record layout and the exact-decimal rate
 * mapping belong to {@link DisclosureGroup}; the key's widths and offsets belong to
 * {@link DisclosureGroupId}. Two parity traps belong here, because both are properties of the lookup
 * itself rather than of either type.
 *
 * <h2>Parity warning 1 - key values are space-padded and are never trimmed</h2>
 *
 * <p>The account group identifier is a fixed-width 10-byte field, so padding is part of the stored key
 * rather than incidental whitespace. Of the three seeded identifiers one is zero-filled to ten
 * characters and two are seven characters padded with three trailing spaces; the seven-character form
 * resolves nothing and only the padded form resolves a seeded row. The padding is not incidental - the
 * accrual program's fallback assigns a seven-character literal into the 10-byte alphanumeric field, and
 * a legacy alphanumeric assignment left-justifies and space-fills to the receiving width - so callers
 * must construct keys at full width.
 *
 * <p>Nothing in this module trims, strips, pads, folds or normalises a key component: not
 * {@link DisclosureGroupId}'s equality and hashing, not the entity's accessors, not this interface. The
 * schema reinforces the same rule, because the group-identifier column is a bounded variable-length
 * character column and deliberately not a blank-padded fixed-length one - blank-padded comparison
 * would silently equate the padded and unpadded forms and erase the distinction that has to survive.
 *
 * <h2>Parity warning 2 - assignment order is not key order</h2>
 *
 * <p>The batch interest-accrual program populates the key with three consecutive assignments whose
 * textual order is group, then <em>category</em>, then <em>type</em>. The key's component order is
 * group, then <strong>type</strong>, then <strong>category</strong>, and it is fixed three ways: by the
 * copybook's declaration order and widths, by the cluster's {@code KEYS(16 0)} taken together with
 * those widths, and by the accrual program's own file-section declaration. The primary-key column order
 * of the table follows the same sequence.
 *
 * <p>{@link DisclosureGroupId}'s all-arguments constructor accordingly takes group, type, category, and
 * <strong>that order must never be "corrected" to match the assignment sequence</strong>. The three
 * assignments target three distinct named fields, so their textual order carries no ordering semantics.
 * Reordering would transpose two components of every lookup and resolve the wrong row - or no row -
 * while still compiling cleanly and still satisfying any test written under the same misunderstanding,
 * and because both components are short character values no type error would expose it.
 *
 * <h2>The lookup-and-fallback cascade lives in the service tier</h2>
 *
 * <p>The legacy rate lookup normalises success and record-not-found as a single non-error outcome and,
 * on record-not-found only, substitutes the padded fallback group identifier and re-reads <em>exactly
 * once</em>; a miss on that single retry is terminal. Interest is computed only when the resolved rate
 * is non-zero. That whole cascade belongs to the interest-calculation service, which owns the ordering
 * and the fallback identifier literal. No fallback, retry, status enumeration or default-resolving
 * convenience method may be added here: it would move business logic into the data-access tier and hide
 * the bounded-retry semantics that parity depends on. This interface's sole contribution is the
 * negative result - a keyed retrieval matching no row yields an empty {@code Optional}, the precise
 * analogue of the legacy record-not-found status that triggers the substitution.
 *
 * <h2>What the seeded reference data exercises</h2>
 *
 * <p>The seed loads exactly 51 rows, three group identifiers at 17 rows each, scoped to the local and
 * test profiles so that neither sample data nor seeded credentials reach a production migration. All 50
 * seeded account rows carry ten spaces as their group identifier, which matches none of the three, so
 * seeded data alone always drives the fallback branch. The direct-hit branch, and the skip branch of
 * the non-zero guard reached through the zero-rate group, are reachable only from a purpose-built
 * fixture that points an account at one of the three real groups.
 *
 * <h2>Access characteristics</h2>
 *
 * <p>This is a batch-only reference table: the legacy resource definitions register only eight files
 * for online transaction access and this cluster is not among them, so the accrual job is its only
 * consumer. It is read-only in normal operation, being reference data written by a migration rather
 * than by application flow, so no locking or transactional directive is declared here and isolation is
 * left to the caller's transaction.
 *
 * <p><strong>No foreign key references this table, by design, and that absence must be documented
 * rather than repaired.</strong> The account table's group identifier corresponds to only the first of
 * three key components and is nonunique on its own, recurring 17 times per group, so a single-column
 * reference to a partial composite prefix is not expressible as a foreign key. Promoting it would
 * fabricate a constraint the legacy design never had and would reject the very unmatched group value
 * that all 50 seeded accounts carry and that the fallback exists to absorb.
 *
 * @see DisclosureGroup
 * @see DisclosureGroupId
 */
public interface DisclosureGroupRepository
        extends JpaRepository<DisclosureGroup, DisclosureGroupId> {
}
