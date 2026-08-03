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
 * operation.
 *
 * <p><strong>Legacy provenance.</strong> The row is the Java translation of the record declared in
 * copybook {@code CVTRA04Y}, whose stated record length is 60. Of those 60 bytes, the composite key
 * group occupies offset 0 for a width of 6 and comprises a 2-byte transaction type code at offset 0
 * followed by a 4-byte transaction category code at offset 2; a 50-byte description follows at
 * offset 6; and the 4 trailing filler bytes at offset 56 are not persisted, being neither an
 * attribute of the entity nor a column of the schema. The widths reconcile exactly: 2 + 4 = 6 for
 * the key, and 6 + 50 + 4 = 60 for the record. The cluster geometry corroborates the layout
 * independently, the base cluster being defined as indexed with {@code KEYS(6 0)} and
 * {@code RECORDSIZE(60 60)}.
 *
 * <p><strong>Why the identifier is the legacy business key.</strong> Because the declared key offset
 * is 0, the key is the leading substring of the stored record image - the same key-then-data split
 * the sequential batch programs model in their file descriptions, where an 11-digit account key is
 * followed by a 289-byte data remainder summing to a 300-byte account record. The identifier of this
 * entity is therefore the legacy business key itself, and no surrogate, generated or machine-assigned
 * key is introduced anywhere in this module. A surrogate would break the record-image-to-row
 * correspondence on which byte-level parity verification of the migrated output depends.
 *
 * <p><strong>Name-collision warning: the legacy key group name is overloaded.</strong> Copybook
 * {@code CVTRA04Y} and copybook {@code CVTRA01Y} both name their key group identically, yet the two
 * keys are structurally unrelated. The key reached through this repository has <strong>two</strong>
 * components totalling <strong>6 bytes</strong>, corroborated by {@code KEYS(6 0)}. The key of the
 * transaction category balance record in {@code CVTRA01Y} has <strong>three</strong> components
 * totalling <strong>17 bytes</strong>, corroborated by {@code KEYS(17 0)}, and it leads with an
 * 11-byte account identifier that this key does not contain at all. The 6-byte key is consequently
 * <em>not</em> a prefix, sub-key or reusable fragment of the 17-byte one: because an account
 * identifier heads the longer form, the two layouts align at no offset whatsoever. The balance record
 * has its own entity and its own separate identifier class reached through its own repository; the
 * two identifier types are never interchangeable, and no shared supertype, marker interface or helper
 * may be introduced here to "reuse" the components whose names overlap. The two copybooks also spell
 * their component names differently - this one separates the words where the other runs them together
 * - and the schema transcribes both spellings exactly rather than regularizing them, so the column
 * names of the two tables differ as faithfully as their source does. Decision log entry D-37 records
 * the collision.
 *
 * <p><strong>Not to be confused with the transaction type reference table either.</strong> That
 * table, derived from copybook {@code CVTRA03Y}, is a third and distinct thing. Its source field is
 * declared bare rather than nested inside any key group, so its primary key is a single column of
 * width 2 whose name carries none of the suffix this table's first key component carries, its cluster
 * declares {@code KEYS(2 0)}, and it has no composite identifier class at all. Nothing about it is
 * reachable through this repository.
 *
 * <p><strong>Both key components are text, deliberately.</strong> The category code is declared as
 * four digits in the copybook, yet the schema maps it - and every other digits-only lexeme in this
 * database - to a bounded variable-length character column rather than to an integral type, and
 * deliberately not to a blank-padded fixed-length character type either, because blank padding can
 * hide a distinction between two values that differ only in trailing space. Leading zeros and
 * external text widths are contractual: a category code of {@code "0005"} must round-trip as
 * {@code "0005"} and must never narrow to a value that renders as {@code 5}, or the 6-byte key image
 * would no longer reconstruct from its two components. Such a value is present in the seeded
 * reference data, so this is a live concern rather than a theoretical one. Faithful representation
 * beats the more idiomatic numeric choice here, and the decision is recorded as such.
 *
 * <p><strong>Values are never trimmed.</strong> Fixed-width padding is significant throughout this
 * schema, so nothing on this access path removes, adds, folds or otherwise normalizes it. Keys are
 * matched exactly as supplied and attributes are returned exactly as stored. Conversion between the
 * padded record form and the stored column form is the sole responsibility of the record mapper for
 * this layout in the utility layer; fixed-width offset arithmetic, parsing and formatting stay there
 * and appear nowhere in this package.
 *
 * <p><strong>A batch-only reference cluster.</strong> The CICS resource definition registers only
 * eight files, and this cluster is not among them, so the legacy system exposed no online access path
 * to it. Its one consumer is the batch reporting tier: procedure step {@code STEP10R} of
 * {@code TRANREPT} supplies the cluster to the report program through the {@code TRANCATG} DD.
 *
 * <p><strong>The access path is the inherited keyed lookup, and nothing more.</strong> This interface
 * declares no method of its own. Every operation the reference lookup needs is inherited -
 * {@code findById} for the keyed read that replaces the indexed cluster read, together with
 * {@code findAll}, {@code existsById}, {@code count} and the save operations - and the argument to
 * the keyed read is a {@link TransactionCategoryId} whose two components must be supplied in
 * contractual order, type code first and category code second, matching both the cluster key geometry
 * and the primary-key column order of the schema migration. No derived finder, query annotation or
 * specification is added: the index migration defines no index touching this table and no foreign key
 * in either direction, into it or out of it, so no additional access path exists to support. Adding a
 * convenience finder would exceed the specified surface of this package.
 *
 * <p><strong>Absence of a record is not an error here.</strong> The legacy batch programs normalize a
 * raw two-byte file status into a coarser application result and then branch on it, treating
 * end-of-file as an ordinary outcome and any other non-zero status as terminal. That two-level model,
 * and the abend path it ends in, belong to the service and batch tiers. This interface therefore
 * declares no exception type and no status enumeration; the repository-level expression of
 * record-not-found is simply the empty {@code Optional} returned by the inherited keyed lookup.
 *
 * <p><strong>Seeded volume.</strong> The reference seed migration inserts exactly 18 rows, derived
 * from the transaction category reference fixture held under {@code app/data/ASCII}, measured at
 * 1,098 bytes for 18 records at the 60-byte record length. That migration is scoped to the local and
 * test profiles only, so a production deployment receives the schema and its indexes without
 * inheriting sample data.
 *
 * <p><strong>Registration and thread safety.</strong> No stereotype annotation is present: the
 * repository infrastructure discovers this interface through the component scan rooted at the base
 * package and supplies the implementation. The resulting proxy is a stateless singleton and is safe
 * for concurrent use; the entities it returns are not, and are confined to the persistence context or
 * batch chunk that owns them. No result of any operation declared or inherited here is cached in
 * static state, and no caching layer is introduced, the legacy system having had none.
 *
 * <p><strong>Provenance.</strong> Translated from the legacy estate at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy tree is read-only reference and
 * is never read at run time by this module.
 *
 * @see TransactionCategory
 * @see TransactionCategoryId
 */
public interface TransactionCategoryRepository
        extends JpaRepository<TransactionCategory, TransactionCategoryId> {
}
