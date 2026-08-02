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
package com.carddemo.domain;

import com.carddemo.domain.id.TransactionCategoryId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * Transaction category reference entity - the Java translation of the {@code TRAN-CAT-RECORD}
 * structure declared in copybook {@code CVTRA04Y}, whose record length is 60 bytes of which 56 are
 * mapped data. The record describes the descriptive text for a transaction type and category pair, and
 * is pure reference data: the posting, interest and reporting paths read it and never write it.
 *
 * <p><strong>Verified layout.</strong> The composite key group {@code TRAN-CAT-KEY} occupies offset 0
 * for a width of 6, comprising a 2-byte alphanumeric type code at offset 0 and a 4-byte category code
 * held as text at offset 2; a 50-byte description follows at offset 6; and 4 trailing filler bytes at
 * offset 56 are <em>not</em> persisted, being neither an attribute here nor a column in the schema.
 * Fixed-width offset arithmetic is deliberately absent from this class - it lives exclusively in the
 * record mapper for this layout in the utility layer, so record-image knowledge stays in one place and
 * this entity carries nothing but column widths. The entity is <em>produced by</em> that mapper and
 * never refers to it, keeping the layer dependency one-way.
 *
 * <p><strong>VSAM origin.</strong> The record is stored in base cluster {@code TRANCATG}, defined as an
 * {@code INDEXED} KSDS with {@code KEYS(6 0)} and {@code RECORDSIZE(60 60)}. The declared key length of
 * 6 at offset 0 independently confirms that the key has exactly two components, in exactly the order
 * declared below, whose widths sum to 6. Because the key offset is 0 the key is the leading substring
 * of the stored image - the same key-then-data split the sequential batch programs model in their file
 * descriptions - so the identifier of this entity is the legacy business key itself. No surrogate,
 * generated or machine-assigned key is introduced, because one would break the record-image-to-row
 * correspondence on which byte-parity verification of the migrated output depends.
 *
 * <p><strong>Name-collision warning: {@code TRAN-CAT-KEY} is an overloaded legacy name.</strong> This
 * copybook and {@code CVTRA01Y} both name their key group {@code TRAN-CAT-KEY}, yet the two keys are
 * structurally unrelated. Here the key has <strong>two</strong> components totalling
 * <strong>6 bytes</strong>, corroborated by {@code KEYS(6 0)}; in {@code CVTRA01Y} - the
 * transaction-category-balance record - it has <strong>three</strong> components, leading with an
 * 11-byte account identifier, totalling <strong>17 bytes</strong> and corroborated by
 * {@code KEYS(17 0)}. The 6-byte key is not a prefix, sub-key or reusable fragment of the 17-byte one:
 * the 17-byte form leads with an account identifier this key does not contain at all, so the two
 * layouts align at no offset. The identifier class named below is therefore the two-component one and
 * nothing else; the balance key has its own separate identifier class, the two are never
 * interchangeable, and no shared supertype or helper may be introduced to "reuse" the overlapping
 * components. Decision log entry D-37 records the collision.
 *
 * <p><strong>Prefix divergence, preserved verbatim.</strong> The two copybooks also spell their
 * component names differently: this one separates the words while {@code CVTRA01Y} runs them together.
 * The schema transcribes both spellings exactly rather than regularizing them, so this table's columns
 * keep the separated prefix seen below while the balance table's keep the run-together spelling of its
 * own copybook. That divergence is faithful transcription, not an inconsistency to be tidied.
 *
 * <p><strong>Not to be confused with the transaction-type table either.</strong> That table, from
 * {@code CVTRA03Y}, is also a 60-byte record with a 50-character description, but its key is a single
 * column of width 2 named without the {@code _cd} suffix this entity's first key component carries,
 * and it has no identifier class at all because its key is not composite.
 *
 * <p><strong>Composite key realisation.</strong> The primary key is
 * {@code (tran_type_cd, tran_cat_cd)} in exactly that order, realised with {@code @IdClass} naming
 * {@link TransactionCategoryId} rather than with an embedded identifier. Two separate identifier
 * annotations, one per component, keep both key parts directly queryable as top-level columns and keep
 * all three composite-key entities of this package consistent. Component order is contractual - fixed
 * independently by the cluster key geometry and by the primary-key column order of the migration - and
 * reordering it would change the key.
 *
 * <p><strong>All three attributes are text, deliberately.</strong> The category code is four digits in
 * the copybook yet a bounded character column in the schema, because leading zeros and external text
 * widths are contractual: a category of {@code "0005"} must round-trip as {@code "0005"} and must never
 * narrow to a value that renders as {@code 5}, or the 6-byte key image would no longer reconstruct from
 * its two components - and the seeded reference data contains such values. No attribute here is
 * numeric, temporal or monetary, so this class converts nothing and its mutators normalize nothing; no
 * trimming, padding, case folding or validation appears anywhere.
 *
 * <p><strong>Where the description's padding lives.</strong> Because this class neither adds nor removes
 * padding, the two callers on either side of it decide, and they decide differently on purpose. In the
 * 60-byte record image the description occupies its whole 50-byte span - every one of the eighteen
 * fixture records is space-filled to fifty - and the placement primitive the record mapper writes
 * through guarantees it by writing the trailing pad explicitly. In the relational row the description is
 * stored right-trimmed, at the twelve to twenty-nine characters the reference seed inserts. Neither form
 * loses anything, because placing the trimmed form back through the mapper reproduces the identical
 * fifty bytes. What would break the contract is this class silently converting between the two, so it
 * stores whichever it is handed and compares it unchanged. The description is not part of the key, so no
 * lookup turns on the choice; the two key components are fixed-shape and zero-filled, on which a trim is
 * a no-op.
 *
 * <p><strong>No relationships, and no foreign key in either direction.</strong> No migration defines
 * any foreign key that targets this table or originates from it - in particular none from this table's
 * type code to the transaction-type table, and none from the transaction, daily-transaction or
 * category-balance tables into this one. This entity therefore models no association of any kind and
 * holds no collection. Decision log entry D-38 records why, and why the absence on the
 * daily-transaction side is load-bearing rather than an oversight.
 *
 * <p><strong>Seeded volume.</strong> The reference data holds exactly 18 rows, derived from
 * {@code app/data/ASCII/trancatg.txt}, measured at 1,098 bytes for 18 records at the 60-byte record
 * length. Description values in that fixture are space-padded to the full width of 50 in the record and
 * are seeded right-trimmed into the column, which the record writer re-pads on output.
 *
 * <p><strong>Mutability and thread safety.</strong> This is a mutable persistent entity, as the
 * provider requires, and is therefore not thread safe. Instances are confined to the persistence
 * context or the batch chunk that owns them and are never shared across threads or held in a static
 * cache. No caching layer exists in the legacy system and none is introduced here.
 *
 *
 * @see TransactionCategoryId
 */
@Entity
@Table(name = "transaction_category")
@IdClass(TransactionCategoryId.class)
public class TransactionCategory {

    /**
     * Transaction type code - legacy field {@code TRAN-TYPE-CD}, offset 0, width 2, and the first
     * component of the 6-byte composite key. Mapped to column {@code tran_type_cd}, declared
     * {@code VARCHAR(2) NOT NULL} by the schema migration.
     *
     * <p>Declared first because key component order is contractual. The field name and type match
     * the corresponding component of {@link TransactionCategoryId} exactly, which is what lets the
     * provider bind the identifier class to this entity; a difference in either would abort context
     * startup.
     */
    @Id
    @Column(name = "tran_type_cd", length = 2, nullable = false)
    private String tranTypeCd;

    /**
     * Transaction category code - legacy field {@code TRAN-CAT-CD}, offset 2, width 4, and the
     * second component of the 6-byte composite key. Mapped to column {@code tran_cat_cd}, declared
     * {@code VARCHAR(4) NOT NULL} by the schema migration.
     *
     * <p>Text rather than an integral type even though the copybook declares four digits, so that a
     * value such as {@code "0005"} keeps its leading zeros and the key image stays reconstructible
     * from its two components.
     */
    @Id
    @Column(name = "tran_cat_cd", length = 4, nullable = false)
    private String tranCatCd;

    /**
     * Transaction category description - legacy field {@code TRAN-CAT-TYPE-DESC}, offset 6, width
     * 50. Mapped to column {@code tran_cat_type_desc}, declared {@code VARCHAR(50) NOT NULL} by the
     * schema migration. Not part of the key.
     *
     * <p>A value arriving from the record mapper is space padded to the full external width of 50; a
     * value arriving from the seeded column is right-trimmed. Either is stored exactly as supplied,
     * because converting between them here would move a decision that belongs to the record writer,
     * which re-pads to fifty on output in both cases.
     */
    @Column(name = "tran_cat_type_desc", length = 50, nullable = false)
    private String tranCatTypeDesc;

    /**
     * Creates an empty instance.
     *
     * <p>Present for the persistence provider, which instantiates an entity through its no-argument
     * constructor and then populates the mapped fields. It is declared {@code protected} rather than
     * {@code public} because application code should always build a fully populated row through
     * {@link #TransactionCategory(String, String, String)}; the provider reaches this constructor
     * regardless of its access level.
     */
    protected TransactionCategory() {
        // Intentionally empty: every mapped field is populated by the provider after construction.
    }

    /**
     * Creates a fully populated transaction category row.
     *
     * <p>Parameter order follows the record layout: the two key components first, in contractual key
     * order, then the description. All three values are stored exactly as supplied - no trimming,
     * padding, case folding or validation is applied, because the persisted widths are enforced by
     * the schema and because silently altering a caller's value would either corrupt the key or move
     * the description's padding decision away from the record writer that owns it.
     *
     * @param tranTypeCd      the 2-character transaction type code, key component 1, from offset 0
     * @param tranCatCd       the 4-character transaction category code held as text so that leading
     *                        zeros survive, key component 2, from offset 2
     * @param tranCatTypeDesc the category description from offset 6, padded to its full width of 50
     *                        when it comes from a record image and right-trimmed when it comes from
     *                        the column; stored either way
     */
    public TransactionCategory(final String tranTypeCd, final String tranCatCd,
            final String tranCatTypeDesc) {
        this.tranTypeCd = tranTypeCd;
        this.tranCatCd = tranCatCd;
        this.tranCatTypeDesc = tranCatTypeDesc;
    }

    /**
     * Returns the transaction type code, key component 1.
     *
     * @return the 2-character type code exactly as stored, or {@code null} if unpopulated
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * Replaces the transaction type code, key component 1.
     *
     * <p>A plain assignment. The value is stored verbatim; nothing is trimmed, padded, folded or
     * validated here.
     *
     * @param tranTypeCd the 2-character type code to store
     */
    public void setTranTypeCd(final String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    /**
     * Returns the transaction category code, key component 2.
     *
     * @return the 4-character category code exactly as stored, with any leading zeros intact, or
     *         {@code null} if unpopulated
     */
    public String getTranCatCd() {
        return tranCatCd;
    }

    /**
     * Replaces the transaction category code, key component 2.
     *
     * <p>A plain assignment. The value is stored verbatim, which is what keeps a code such as
     * {@code "0005"} from losing its leading zeros.
     *
     * @param tranCatCd the 4-character category code to store
     */
    public void setTranCatCd(final String tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    /**
     * Returns the category description.
     *
     * @return the description exactly as stored, including any trailing space padding it was given,
     *         or {@code null} if unpopulated
     */
    public String getTranCatTypeDesc() {
        return tranCatTypeDesc;
    }

    /**
     * Replaces the category description.
     *
     * <p>A plain assignment. The value is stored verbatim, so whatever padding it carries survives and
     * none is added - the record writer owns the external width of 50, not this mutator.
     *
     * @param tranCatTypeDesc the description to store, padded or trimmed as the caller holds it
     */
    public void setTranCatTypeDesc(final String tranCatTypeDesc) {
        this.tranCatTypeDesc = tranCatTypeDesc;
    }

    /**
     * Returns this row's composite identifier, assembled from the two key components in contractual
     * order.
     *
     * <p>A convenience for callers that need the identifier value - a repository lookup, for
     * instance - without reassembling it by hand and risking the component order. A new identifier is
     * built on every call; nothing is cached and no static state is involved.
     *
     * @return a new identifier carrying this row's type code and category code
     */
    public TransactionCategoryId toId() {
        return new TransactionCategoryId(tranTypeCd, tranCatCd);
    }

    /**
     * Compares the two key components for equality.
     *
     * <p>Only the key participates. The description is deliberately excluded: it is mutable, so
     * including it would let an instance's hash change while it sits in a hash-based collection and
     * would break set and map behaviour across a flush.
     *
     * <p>Comparison is exact - no normalisation, padding adjustment or case folding - because every
     * character of a fixed-width key component is significant. A category code of {@code "0005"} is
     * consequently not equal to one of {@code "5"}.
     *
     * @param o the object to compare with, which may be {@code null}
     * @return {@code true} only if {@code o} is a transaction category whose two key components both
     *         match
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategory other)) {
            return false;
        }
        return Objects.equals(tranTypeCd, other.tranTypeCd)
                && Objects.equals(tranCatCd, other.tranCatCd);
    }

    /**
     * Returns a hash code derived from the two key components in declaration order, consistent with
     * {@link #equals(Object)} and likewise excluding the mutable description.
     *
     * @return the hash code for this row
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranTypeCd, tranCatCd);
    }

    /**
     * Returns a diagnostic representation of all three mapped fields.
     *
     * <p>No field of this record is a credential or a monetary value, so none requires redaction.
     * The description is reproduced as stored, whatever padding it carries.
     *
     * @return a diagnostic representation of this row
     */
    @Override
    public String toString() {
        return "TransactionCategory[tranTypeCd=" + tranTypeCd
                + ", tranCatCd=" + tranCatCd
                + ", tranCatTypeDesc=" + tranCatTypeDesc + "]";
    }
}
