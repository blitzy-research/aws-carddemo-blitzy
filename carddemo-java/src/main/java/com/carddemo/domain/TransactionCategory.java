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
 * structure declared in copybook member {@code app/cpy/CVTRA04Y.cpy}, whose record length is 60
 * bytes of which 56 are mapped data.
 *
 * <p>The record describes the descriptive text for a transaction type and category pair. It is pure
 * reference data: the posting, interest and reporting paths read it and never write it.
 *
 * <p><strong>Verified layout.</strong> Offsets are zero based and were recomputed field by field
 * from the copybook rather than inferred:
 * <ul>
 *   <li>{@code TRAN-CAT-KEY} - the composite key group, offset 0, width 6.</li>
 *   <li>{@code TRAN-TYPE-CD} - offset 0, width 2, alphanumeric; key component 1.</li>
 *   <li>{@code TRAN-CAT-CD} - offset 2, width 4, four digits held as text; key component 2.</li>
 *   <li>{@code TRAN-CAT-TYPE-DESC} - offset 6, width 50, alphanumeric description.</li>
 *   <li>{@code FILLER} - offset 56, width 4, trailing padding that is <em>not</em> persisted.</li>
 * </ul>
 * Mapped bytes therefore end at offset 56 and the four filler bytes are neither a field here nor a
 * column in the schema. Fixed-width offset arithmetic is deliberately absent from this class: it
 * lives exclusively in {@code TranCatRecordMapper}, the mapper for this layout in the utility layer,
 * so that record-image knowledge stays in exactly one place and this entity carries nothing but
 * column widths. This entity is <em>produced by</em> that mapper and never refers to it, keeping the
 * layer dependency one-way.
 *
 * <p><strong>VSAM origin.</strong> The record is stored in base cluster {@code TRANCATG}, defined in
 * {@code app/jcl/TRANCATG.jcl} as an {@code INDEXED} KSDS with {@code KEYS(6 0)} and
 * {@code RECORDSIZE(60 60)}. The declared key length of 6 at offset 0 independently confirms that the
 * key has exactly two components, in exactly the order declared below, whose widths sum to 6.
 * Because the key offset is 0, the key is the leading substring of the stored image - the same
 * key-then-data split that the sequential batch programs model in their file descriptions, for
 * instance the account reader in {@code app/cbl/CBACT01C.cbl}, which splits its 300-byte image into
 * an 11-digit leading key field followed by a 289-byte data remainder. The identifier of this entity
 * is consequently the legacy business key itself. No surrogate, generated or machine-assigned key is
 * introduced, because a surrogate would break the record-image-to-row correspondence on which
 * byte-parity verification of the migrated output depends.
 *
 * <p><strong>Name-collision warning - {@code TRAN-CAT-KEY} is an overloaded COBOL name.</strong>
 * This copybook and {@code app/cpy/CVTRA01Y.cpy} both name their key group {@code TRAN-CAT-KEY}, yet
 * the two keys are structurally unrelated:
 * <ul>
 *   <li>Here, in {@code CVTRA04Y}: <strong>two</strong> components, {@code TRAN-TYPE-CD} width 2
 *       plus {@code TRAN-CAT-CD} width 4, total <strong>6 bytes</strong>, corroborated by
 *       {@code KEYS(6 0)} in {@code app/jcl/TRANCATG.jcl}.</li>
 *   <li>There, in {@code CVTRA01Y} - the transaction category balance record mapped by
 *       {@code TransactionCategoryBalance} - <strong>three</strong> components,
 *       {@code TRANCAT-ACCT-ID} width 11 plus {@code TRANCAT-TYPE-CD} width 2 plus
 *       {@code TRANCAT-CD} width 4, total <strong>17 bytes</strong>, corroborated by
 *       {@code KEYS(17 0)} in {@code app/jcl/TCATBALF.jcl}.</li>
 * </ul>
 * The 6-byte key is not a prefix, sub-key or reusable fragment of the 17-byte one: the 17-byte form
 * leads with an account identifier that this key does not contain at all, so the two layouts align
 * at no offset. The identifier class named below is therefore the two-component one and nothing
 * else; the balance key has its own separate identifier class, the two are never interchangeable,
 * and no shared supertype or helper may be introduced to "reuse" the overlapping components. The
 * collision is recorded in the project decision log as a documented source oddity.
 *
 * <p><strong>Prefix divergence, preserved verbatim.</strong> The two copybooks also spell their
 * component names differently. This one separates the words - {@code TRAN-TYPE-CD} and
 * {@code TRAN-CAT-CD} - while {@code CVTRA01Y} runs them together as {@code TRANCAT-TYPE-CD} and
 * {@code TRANCAT-CD}. The schema transcribes both spellings exactly rather than regularising them,
 * so the columns of this table keep the separated {@code tran_} prefix seen below while the balance
 * table's columns keep the run-together spelling of its own copybook. That divergence is faithful
 * transcription, not an inconsistency to be tidied, and it is recorded in the decision log alongside
 * the collision.
 *
 * <p><strong>Not to be confused with the transaction type table either.</strong> The
 * {@code transaction_type} table, from copybook member {@code CVTRA03Y}, is also a 60-byte record
 * with a 50-character description, but its key is a single column of width 2 named {@code tran_type}
 * - without the {@code _cd} suffix this entity's first key component carries - and it has no
 * identifier class at all because its key is not composite. The two tables are distinct and their
 * key columns are named differently by one suffix.
 *
 * <p><strong>Composite key realisation.</strong> The primary key is
 * {@code (tran_type_cd, tran_cat_cd)} in exactly that order, realised with {@code @IdClass} naming
 * {@link TransactionCategoryId} of the {@code domain.id} sub-package, rather than with an embedded
 * identifier. Two separate identifier annotations, one per component, keep both key parts directly
 * queryable as top-level columns and keep all three composite-key entities of this package
 * consistent with one another. Component order is contractual: it is fixed independently by the
 * cluster key geometry and by the primary-key column order of the schema migration, and reordering
 * it would change the key.
 *
 * <p><strong>All three fields are text, deliberately.</strong> The category code is four digits in
 * the copybook yet a bounded variable-length character column in the schema, because leading zeros
 * and external text widths are contractual: a category of {@code "0005"} must round-trip as
 * {@code "0005"} and must never be narrowed to a value that renders as {@code 5}, or the 6-byte key
 * image would no longer reconstruct from its two components. The seeded reference data contains such
 * values. No field here is numeric, temporal or monetary, so this class converts nothing, and its
 * mutators normalise nothing - no trimming, padding, case folding or validation anywhere. Trimming
 * in particular would destroy the space padding that fills the 50-character description to its full
 * external width.
 *
 * <p><strong>No relationships, and no foreign key in either direction.</strong> No migration
 * version - neither the schema migration, which defines primary keys only, nor the index migration,
 * which owns the authoritative foreign-key set - defines any foreign key that targets this table or
 * that originates from it. In particular there is deliberately no constraint from this table's type
 * code to the transaction type table, because no foreign key is created between reference tables;
 * and deliberately none from the transaction, daily transaction or transaction category balance
 * tables into this one. The absence of one from the daily transaction table is load-bearing rather
 * than an oversight: that table is the unvalidated landing area for the posting run, and its
 * reject-with-reason-code paths must stay reachable, which a referential constraint would prevent by
 * rejecting the row at insert time instead. This entity therefore models no association of any kind
 * and holds no collection.
 *
 * <p><strong>Seeded volume.</strong> The reference-data seed migration loads exactly 18 rows into
 * this table, derived from {@code app/data/ASCII/trancatg.txt}, measured at 1,098 bytes for 18
 * records at the 60-byte record length. Description values in that fixture are space padded to the
 * full width of 50 and the column must not trim them.
 *
 * <p><strong>Mutability and thread safety.</strong> This is a mutable persistent entity, as the
 * persistence provider requires, and is therefore not thread safe. Instances are confined to the
 * persistence context or the batch chunk that owns them and are never shared across threads or held
 * in any static cache. No caching layer exists in the legacy system and none is introduced here.
 *
 * <p><strong>Provenance.</strong> Translated from the read-only legacy estate at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19, which appears in the copybook trailer at
 * {@code app/cpy/CVTRA04Y.cpy} line 11. The legacy tree is reference only: member names, field
 * names, widths, offsets and key geometry are cited here, and no source statement is transcribed.
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
     * <p>Values arrive space padded to the full external width of 50 and are stored exactly as
     * supplied, because that padding is part of the fixed-width contract the migrated output must
     * reproduce.
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
     * the schema and because silently altering a caller's value would either corrupt the key or
     * strip the description's contractual space padding.
     *
     * @param tranTypeCd      the 2-character transaction type code, key component 1, from offset 0
     * @param tranCatCd       the 4-character transaction category code held as text so that leading
     *                        zeros survive, key component 2, from offset 2
     * @param tranCatTypeDesc the 50-character category description, space padded to its full width,
     *                        from offset 6
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
     * @return the 50-character description exactly as stored, including any trailing space padding,
     *         or {@code null} if unpopulated
     */
    public String getTranCatTypeDesc() {
        return tranCatTypeDesc;
    }

    /**
     * Replaces the category description.
     *
     * <p>A plain assignment. The value is stored verbatim so that the space padding filling the
     * description to its external width of 50 is preserved.
     *
     * @param tranCatTypeDesc the 50-character description to store
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
     * The description is reproduced as stored, padding included.
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
