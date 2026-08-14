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

import com.carddemo.domain.TransactionCategory;
import com.carddemo.domain.id.TransactionCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for the {@code transaction_category} reference lookup, replacing the {@code TRANCATG}
 * VSAM KSDS base cluster. The table supplies the descriptive text for a transaction type and category
 * pair; it is pure reference data that the reporting path reads and no path writes during normal
 * operation. {@link TransactionCategoryId} owns the key geometry - a 2-byte type code at offset 0 and a
 * 4-byte category code at offset 2, a 6-byte composite at offset 0 of a 60-byte record. Because the key
 * offset is 0 the key is the leading substring of the stored record image, so the identifier is the
 * legacy business key itself; no surrogate, generated or machine-assigned key is introduced anywhere in
 * this module, because a surrogate would break the record-image-to-row correspondence that byte-level
 * parity verification depends on.
 *
 * <h2>Name-collision warning: the legacy key group name is overloaded</h2>
 *
 * <p>This copybook and the transaction-category-balance copybook name their key groups identically, yet
 * the two keys are structurally unrelated. The key reached through this repository has <strong>two</strong>
 * components totalling <strong>6 bytes</strong>, corroborated by {@code KEYS(6 0)}. The balance record's
 * key has <strong>three</strong> components totalling <strong>17 bytes</strong>, corroborated by
 * {@code KEYS(17 0)}, and it leads with an 11-byte account identifier this key does not contain at all.
 * The 6-byte key is therefore <em>not</em> a prefix, sub-key or reusable fragment of the 17-byte one:
 * with an account identifier heading the longer form the two layouts align at no offset whatsoever. The
 * balance record has its own entity, identifier class and repository, the two identifier types are
 * never interchangeable, and no shared supertype, marker interface or helper may be introduced here to
 * "reuse" the components whose names overlap. The two copybooks also spell their component names
 * differently - this one separates the words where the other runs them together - and the schema
 * transcribes both spellings exactly rather than regularising them, so the column names differ as
 * faithfully as their source does. Decision log entry D-37 records the collision. The transaction-type
 * reference table is a third and distinct thing again: a single 2-byte key whose column name carries
 * none of the suffix this table's first component carries, and no composite identifier class at all.
 *
 * <h2>Both key components are text, deliberately</h2>
 *
 * <p>The category code is declared as four digits, yet the schema maps it - and every other digits-only
 * lexeme in this database - to a bounded variable-length character column rather than to an integral
 * type, and deliberately not to a blank-padded fixed-length character type either, because blank
 * padding can hide a distinction between two values differing only in trailing space. Leading zeros and
 * external widths are contractual: a category code of {@code "0005"} must round-trip as {@code "0005"}
 * and must never narrow to a value rendering as {@code 5}, or the 6-byte key image would no longer
 * reconstruct from its two components. Such a value is present in the seeded reference data, so this is
 * a live concern rather than a theoretical one.
 *
 * <p>Fixed-width padding is significant throughout this schema, so nothing on this access path removes,
 * adds, folds or otherwise normalises it: keys are matched exactly as supplied and attributes returned
 * exactly as stored. Conversion between the padded record form and the stored column form is the sole
 * responsibility of this layout's record mapper in the utility layer, where the offset arithmetic,
 * parsing and formatting stay.
 *
 * <h2>Access path, absence, and seeded volume</h2>
 *
 * <p>The CICS resource definition registers only eight files and this cluster is not among them, so the
 * legacy system exposed no online path to it; its one consumer is the batch reporting tier, which
 * receives the cluster through the {@code TRANCATG} DD of step {@code STEP10R} of the reporting
 * procedure. This interface declares no method of its own - the inherited keyed read replaces the
 * indexed cluster read, and its argument is a {@link TransactionCategoryId} whose components must be
 * supplied in contractual order, type code first and category code second, matching both the cluster
 * key geometry and the primary-key column order. The index migration defines no index touching this
 * table and no foreign key in either direction, so no additional access path exists to support.
 *
 * <p>The legacy batch programs normalise a raw two-byte file status into a coarser application result,
 * treating end-of-file as an ordinary outcome and any other non-zero status as terminal. That two-level
 * model and the abend path it ends in belong to the service and batch tiers, so this interface declares
 * no exception type and no status enumeration; record-not-found is expressed as the empty
 * {@code Optional} returned by the inherited keyed read. The reference seed inserts exactly 18 rows
 * matching the 1,098-byte ASCII fixture, scoped to the local and test profiles so that a production
 * deployment receives the schema and its indexes without inheriting sample data.
 *
 * <h2>Registration and thread safety</h2>
 *
 * <p>No stereotype annotation is present: the repository infrastructure discovers this interface through
 * the component scan rooted at the base package. The resulting proxy is a stateless singleton and safe
 * for concurrent use; the entities it returns are not, and are confined to the persistence context or
 * batch chunk that owns them. No result is cached in static state and no caching layer is introduced,
 * the legacy system having had none.
 *
 * @see TransactionCategory
 * @see TransactionCategoryId
 */
public interface TransactionCategoryRepository
        extends JpaRepository<TransactionCategory, TransactionCategoryId> {
}
