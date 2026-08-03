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

import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.id.TransactionCategoryBalanceId;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository over the {@code transaction_category_balance} table, which replaces the
 * {@code TCATBALF} VSAM KSDS base cluster of the AWS CardDemo mainframe estate.
 *
 * <p>The declaration below is deliberately empty. Every access path this table actually has is
 * already inherited, so adding a method here could only duplicate an inherited one or invent an
 * access path the schema cannot serve. That emptiness is a designed outcome rather than an
 * unfinished one, and the reasoning behind it is recorded in the sections that follow.
 *
 * <h2>Legacy provenance</h2>
 *
 * <p>The persisted shape derives from copybook {@code CVTRA01Y}, whose header declares a record
 * length of 50. Its leading key group occupies 17 bytes beginning at offset 0 and is the ordered
 * concatenation of an eleven-digit account identifier at offset 0, a two-character transaction type
 * code at offset 11 and a four-digit transaction category code at offset 13. A signed two-decimal
 * category balance follows at offset 17 for 11 bytes, and a 22-byte trailing filler occupies
 * offset 28 to the end of the record. The three key widths sum to the declared 17-byte key, and
 * 17 plus 11 plus 22 accounts for all 50 bytes.
 *
 * <p>That layout is corroborated twice over, independently of the copybook. The {@code TCATBALF}
 * cluster definition declares an {@code INDEXED} KSDS with {@code KEYS(17 0)} and
 * {@code RECORDSIZE(50 50)}, and the file description in batch program {@code CBACT04C} splits the
 * same 50-byte image into a 17-byte key group followed by a 33-byte data area, the 33 bytes being
 * the 11-byte balance plus the 22-byte filler. Three sources therefore agree on one key geometry.
 *
 * <p>The trailing filler is not persisted at all: it has no attribute on the entity and no column in
 * the schema, because trailing filler carries no information and is reconstructed on output from the
 * declared record width.
 *
 * <h2>Why the identity is the legacy business key</h2>
 *
 * <p>Because the declared key offset is 0, the key <em>is</em> the leading 17 bytes of the stored
 * record image rather than a separate structure beside it. This is the same key-then-data split the
 * sequential batch programs model in their own file descriptions: {@code CBACT01C} divides its
 * 300-byte account image into an eleven-digit key field followed by a 289-byte data field for
 * exactly this reason. The persistent identity of the
 * entity is consequently the legacy business key itself. No surrogate, generated or
 * provider-assigned identifier exists anywhere in this module, because one would break the
 * record-image-to-table-row correspondence on which byte-level output parity verification depends.
 *
 * <h2>Name-collision warning: {@code TRAN-CAT-KEY} is an overloaded legacy name</h2>
 *
 * <p>Copybook {@code CVTRA01Y} and copybook {@code CVTRA04Y} both name their key group
 * {@code TRAN-CAT-KEY}, yet the two keys are structurally unrelated and the shared name is a
 * source-level coincidence. The key reached through this repository has three components totalling
 * 17 bytes, attested by {@code KEYS(17 0)}. The transaction-category reference key described by
 * {@code CVTRA04Y} has two components totalling 6 bytes, is attested by {@code KEYS(6 0)}, sits
 * behind a different table and a different entity, is reached through a different repository, and
 * uses different column names over a 60-byte record.
 *
 * <p>The 6-byte key is emphatically <em>not</em> a prefix, sub-key or reusable fragment of this one.
 * This key leads with an eleven-digit account identifier that the other does not contain at all, a
 * point proven by the file description in {@code CBACT04C}, so the two layouts align at no offset
 * and share no leading byte. A third table again is different: its reference record carries a
 * single two-character key and no composite identifier type exists for it.
 *
 * <p>Accordingly this repository names {@link TransactionCategoryBalanceId} and nothing else as its
 * identifier type. The two key definitions are never interchangeable, and no shared supertype,
 * marker interface or helper may be introduced to reuse the components whose names happen to
 * overlap. Decision log entry D-37 records the collision so the distinction stays auditable.
 *
 * <h2>Component order is contractual</h2>
 *
 * <p>The key components are the account identifier, then the transaction type code, then the
 * transaction category code, in exactly that order. That order is fixed three times over: by the
 * record layout, by the cluster key definition beginning at offset 0, and by the primary-key column
 * order of the migration. The posting program's own write path agrees, moving the account identifier
 * into the key before the type code. The constructor order of {@link TransactionCategoryBalanceId}
 * mirrors it and must never be reordered, and callers must never assemble the key from a differently
 * ordered tuple. A transposition of the type and category arguments would still compile, and would
 * still satisfy any test written under the same misunderstanding, while silently resolving the wrong
 * row or no row at all.
 *
 * <h2>Column-prefix divergence, preserved verbatim</h2>
 *
 * <p>Within this one copybook the naming is itself inconsistent, and the migration transcribes that
 * inconsistency exactly rather than tidying it. The three key columns run the first two words
 * together as {@code trancat_acct_id}, {@code trancat_type_cd} and {@code trancat_cd}, while the
 * balance column separates them as {@code tran_cat_bal}. Regularising either spelling would be a
 * mapping mismatch, and because the module validates its object-relational mapping against the
 * Flyway-migrated schema at start-up, such a mismatch would surface as a start-up failure rather
 * than as a cosmetic difference. That is the intended safety net, but it only helps if the mapping
 * is faithful in the first place.
 *
 * <h2>A miss is not an error</h2>
 *
 * <p>This is the behavioural fact that determines what this interface does <em>not</em> declare. The
 * daily-transaction posting program {@code CBTRN02C} opens this file for input and output, holds a
 * one-character working-storage flag initialised to the negative value, and clears that flag before
 * reading the category-balance record. On the record-not-found path it merely sets the flag to the
 * affirmative value and continues. It then treats the success status and the not-found status
 * <em>alike</em> as a non-error outcome, and branches on the flag into one of two sibling
 * paragraphs: one that writes a brand-new record, and one that adds the transaction amount to the
 * existing balance and rewrites the record in place.
 *
 * <p>Three consequences follow, and all three are load-bearing:
 *
 * <ul>
 *   <li>The repository-level expression of record-not-found is the empty {@link java.util.Optional}
 *       returned by the inherited {@code findById}. Nothing is thrown here, and this interface
 *       declares no exception type and no status enum. The two-level status model of the legacy tier
 *       &mdash; the raw two-byte file status normalised into a success, end-of-file or error outcome
 *       before any branch is taken, as {@code CBACT01C} illustrates &mdash; belongs to the service
 *       and batch layers, not to this one.</li>
 *   <li>The inherited {@code findById} and the inherited {@code save} together cover both legacy
 *       branches, because provider merge semantics insert the row when its key is absent and update
 *       it when the key is present. That is the legacy write-or-rewrite split expressed once.</li>
 *   <li>The decision of which branch to take belongs to the service layer, where it stays traceable
 *       back to the flag-and-branch paragraph structure it was translated from. It is deliberately
 *       not encoded here as a combined convenience operation, because collapsing it would move a
 *       documented control-flow decision out of the layer whose paragraph mapping is audited.</li>
 * </ul>
 *
 * <p>The legacy tier is record-at-a-time and rewrites in place, so no set-based bulk update or
 * delete is declared anywhere in this package either; a set-based rewrite would be a behavioural
 * change rather than an optimisation.
 *
 * <h2>The balance is exact decimal, and no arithmetic happens here</h2>
 *
 * <p>The category balance is a signed two-decimal quantity, mapped to a fixed-precision decimal
 * column and carried in Java as {@link java.math.BigDecimal} at scale 2. It is never represented as
 * an inexact binary numeric type, because decimal precision must be identical to the legacy
 * representation rather than merely close to it. In the legacy estate the field is zoned decimal
 * held as display characters with the sign overpunched into the final byte, and decoding that
 * encoding is the exclusive responsibility of the codec in the utility layer.
 *
 * <p>The single most consequential arithmetic finding of this migration applies to every value read
 * through this repository: the legacy estate contains no rounding clause anywhere, so every store
 * into a two-decimal field truncates toward zero rather than rounding. All scaling is therefore
 * performed by that same utility-layer codec, which truncates rather than rounding half-even. The
 * interest expression that consumes this balance multiplies the balance by the rate before dividing,
 * and must never be algebraically rearranged, because truncation makes the arithmetic
 * non-associative and moving the truncation point changes the result.
 *
 * <p>None of that arithmetic occurs in this file. This repository's contribution to decimal fidelity
 * is entirely negative: it computes nothing, aggregates nothing and rescales nothing, so it cannot
 * introduce a divergent rounding policy no matter how it is called.
 *
 * <h2>Why every key component is text</h2>
 *
 * <p>Two of the three key components are digit-only fields in the legacy layout, yet all three are
 * modelled as {@link String} over bounded variable-length character columns. The bound is deliberate
 * and so is the variability: fixed-length character columns were rejected because blank padding
 * would hide distinctions between values. Leading zeros and exact external widths are contractual
 * rather than incidental, so a four-character category code such as {@code "0005"} must round-trip
 * as {@code "0005"} and must never collapse to {@code 5} or match {@code "5"}, and an eleven-digit
 * account identifier must retain its leading zeros. Modelling either component numerically would
 * silently discard that padding and the reassembled key image would no longer be 17 bytes wide.
 *
 * <p>For the same reason no value reaching or leaving this repository is ever trimmed, padded, folded
 * in case or otherwise normalised. Normalisation in key equality would make two distinct rows
 * compare equal and corrupt the persistence-context identity map.
 *
 * <h2>Access paths, and why no finder is declared</h2>
 *
 * <p>The migration creates exactly one constraint touching this table, a foreign key from the
 * account-identifier component to the account table, and <em>no</em> secondary index over it. There
 * is consequently no index a derived finder could exploit: the only access paths this table
 * genuinely has are lookup by the full composite key and an ordered sequential scan, and both are
 * inherited. Lookup by full key serves the posting and interest paths, while the sorted and paged
 * variants of the inherited scan serve the category-balance listing job, which reads the cluster
 * sequentially in the legacy design.
 *
 * <p>No stereotype annotation is declared on this interface, because repository interfaces are
 * discovered automatically by the component scan rooted at the base package.
 *
 * <p>The entity declares no association to any other entity despite that foreign key existing in the
 * schema. Nothing lazily initialised is therefore ever traversed through this repository, which makes
 * the module's disabled view-scoped persistence context trivially satisfied and means no
 * entity-graph hint or fetch-join is needed, or permitted, anywhere in the module. This table also
 * carries no optimistic-locking attribute; only the account and card tables do.
 *
 * <h2>Operational notes</h2>
 *
 * <p>This is a batch-only cluster. The CICS resource definition registers eight files and
 * {@code TCATBALF} is not among them, so the legacy design exposed no online transaction path to it
 * and neither does this module: its consumers are the transaction-posting and interest-calculation
 * services, their corresponding batch step processors, and the file-maintenance listing job.
 *
 * <p>Reference seeding loads exactly 50 rows for this table, derived from the fixed-width sample
 * dataset and applied after the account rows because of the foreign key, under the local and test
 * profiles only. Every one of those 50 seeded balances is zero. That is worth stating explicitly, so
 * that a reader who runs the interest job against seed data alone and observes no accrued interest
 * does not conclude the job is defective: a non-zero balance fixture is required to exercise a
 * non-zero interest outcome.
 *
 * <p>Provenance: migrated from the AWS CardDemo mainframe estate at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy tree is read-only reference and
 * is never accessed at run time by any production class in this module.
 *
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceId
 * @since 1.0.0
 */
public interface TransactionCategoryBalanceRepository
        extends JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId> {
}
