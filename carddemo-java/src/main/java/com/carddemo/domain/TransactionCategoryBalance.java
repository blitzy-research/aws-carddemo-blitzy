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

import java.math.BigDecimal;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import com.carddemo.domain.id.TransactionCategoryBalanceId;

/**
 * Per-account, per-category transaction balance - the Java translation of the
 * {@code TRAN-CAT-BAL-RECORD} layout declared in copybook {@code app/cpy/CVTRA01Y.cpy}, whose header
 * states a record length of 50.
 *
 * <p>One row holds the accumulated balance of a single account within a single transaction
 * type-and-category combination. It is the value the interest-accrual run multiplies by the matching
 * disclosure rate, which makes this the most arithmetically load-bearing table in the estate even
 * though the class itself computes nothing.
 *
 * <p><strong>Verified layout, and what is deliberately absent from it.</strong> Four fields make up
 * the 50-byte image and only three of them plus the balance are persisted: an 11-byte account
 * identifier at offset 0, a 2-byte transaction type code at offset 11, a 4-byte transaction category
 * code at offset 13, an 11-byte signed balance at offset 17, and a 22-byte trailing filler at
 * offset 28. Each attribute below restates its own offset and width. The filler is <em>not</em>
 * persisted - no attribute here, no column in the schema - because trailing filler carries no
 * information and is reconstructed on output from the declared record width.
 *
 * <p>Offset arithmetic appears nowhere in this class. It lives exclusively in the fixed-width record
 * mapper for this layout, {@code TranCatBalRecordMapper} of the utility layer, so record-image
 * knowledge stays in one place and this entity carries nothing but column widths. The dependency is
 * one-way: the mapper <em>produces</em> instances of this class, and this class names no type of the
 * utility, service, repository, interface or batch layers in code - only in this prose, and only to say
 * where a responsibility deliberately does not live.
 *
 * <p><strong>VSAM origin corroborates the component order.</strong> The record is stored in base
 * cluster {@code TCATBALF}, defined in {@code app/jcl/TCATBALF.jcl} as an {@code INDEXED} KSDS with
 * {@code KEYS(17 0)} and {@code RECORDSIZE(50 50)}. A declared key length of 17 beginning at offset 0
 * is decisive twice over: it confirms that the key is the leading substring of the record image, and
 * it confirms that the three components below, in exactly this order, are what sum to that 17-byte
 * substring - 11 plus 2 plus 4. The primary-key column order of the migration agrees, so one ordering
 * is attested by the copybook, by the cluster geometry and by the schema independently.
 *
 * <p>Because the key offset is 0 the key <em>is</em> the leading substring of the stored image, which
 * is the same key-then-data split the sequential batch programs model in their file descriptions -
 * {@code app/cbl/CBACT01C.cbl} splits its own 300-byte account image into an 11-byte key field
 * followed by a 289-byte data field for exactly this reason. The persistent identity of this entity is
 * therefore the legacy business key itself. No surrogate, generated or provider-assigned identifier is
 * introduced anywhere, because one would break the record-image-to-table-row correspondence on which
 * byte-level parity verification of the migrated output depends.
 *
 * <p><strong>Name-collision warning: {@code TRAN-CAT-KEY} is an overloaded legacy name.</strong>
 * Copybook {@code CVTRA01Y} and copybook {@code CVTRA04Y} both name their key group
 * {@code TRAN-CAT-KEY}, yet the two keys are structurally unrelated. Here the key has
 * <strong>three</strong> components totalling <strong>17 bytes</strong>, corroborated by
 * {@code KEYS(17 0)} in {@code app/jcl/TCATBALF.jcl}; in {@code CVTRA04Y} - the transaction-category
 * reference record behind {@link TransactionCategory} - it has <strong>two</strong> components
 * totalling <strong>6 bytes</strong>, corroborated by {@code KEYS(6 0)} in
 * {@code app/jcl/TRANCATG.jcl}. The 6-byte key is not a prefix, sub-key or reusable fragment of this
 * one: this key leads with an account identifier the other does not contain at all, so the two layouts
 * align at no offset.
 *
 * <p>The identifier class named below is consequently {@link TransactionCategoryBalanceId} and nothing
 * else. The 6-byte key has its own separate identifier class, the two are never interchangeable, and no
 * shared supertype or helper may be introduced to "reuse" the components whose names happen to overlap.
 * Decision log entry D-37 records the collision so the distinction stays auditable.
 *
 * <p><strong>Prefix divergence, preserved verbatim.</strong> Within this one copybook the naming is
 * itself inconsistent: the key group and the balance separate the words while the three key components
 * run the first two together. The migration transcribes that inconsistency exactly - the three key
 * columns carry the run-together prefix seen below while the balance column carries the separated one -
 * and this class matches the migration column for column. Regularizing either spelling would be a
 * mapping mismatch, and because the module validates its mapping against the migrated schema at
 * start-up it would be a start-up failure rather than a cosmetic difference.
 *
 * <p><strong>Composite identity.</strong> The primary key is
 * {@code (trancat_acct_id, trancat_type_cd, trancat_cd)} in exactly that order, realised with
 * {@code @IdClass} naming {@link com.carddemo.domain.id.TransactionCategoryBalanceId} rather than with
 * an embedded identifier.
 * Three separate identifier annotations, one per component, keep every key part directly queryable as a
 * top-level column and keep all three composite-key entities of this package consistent with one
 * another. The provider matches an identifier class to its entity by field <em>name</em> and field
 * <em>type</em>, so the three key attributes here are named and typed identically to that class;
 * a divergence in either is a start-up failure rather than a silent defect. Component order is
 * contractual and is preserved in the declaration order below.
 *
 * <p><strong>Every key component is text, deliberately.</strong> Two of the three are digit-only fields
 * in the copybook, yet all three are bounded character columns, because leading zeros and external text
 * widths are contractual rather than incidental. A category code of {@code "0005"} must round-trip as
 * {@code "0005"}: a numeric type would render it as {@code 5} and the 17-byte key image would no longer
 * reconstruct from the row. The same holds for an account identifier such as {@code "00000000001"}.
 * Nothing here trims, pads, folds case, normalizes or validates any value - not the constructors, not
 * the mutators, and above all not {@link #equals(Object)} or {@link #hashCode()}, where normalization
 * would make two distinct database rows compare equal.
 *
 * <p><strong>No association, in either direction.</strong> The account identifier is a plain text
 * attribute and not a mapped relationship. The migration does define a foreign key from this table's
 * account identifier to the account table, but purely as a database constraint; modelling it in Java
 * would additionally require an identifier-mapping layer, because the same column is simultaneously a
 * key component. No foreign key exists from the type or category code to the reference tables even
 * though both read like references, so neither is modelled as an association either - decision log
 * entry D-38 records why. This class therefore holds no association attribute and no collection.
 *
 * <p><strong>Seeded volume, and the branches it makes reachable.</strong> The reference data seeds
 * exactly 50 rows from {@code app/data/ASCII/tcatbal.txt}, measured at 2,550 bytes for 50 records at
 * the 50-byte record length, and <em>every one of them carries a balance of zero</em>. Those 50 rows
 * together with the 51 disclosure-group rows are what make both arms of the accrual rate lookup
 * reachable from seeded data alone - the direct group hit and the default-group fallback - and what
 * make the zero-rate skip branch reachable, without any synthetic fixture. That every seeded balance is
 * zero is also why the balance attribute is left unset by the no-argument constructor rather than
 * pre-seeded with a zero: an unpopulated instance must stay distinguishable from a genuine zero balance.
 *
 * <p><strong>Carried here, computed elsewhere.</strong> This class performs no arithmetic and applies no
 * scaling. The accrual computation that consumes this balance belongs to
 * {@code InterestCalculationService} of the service layer, and the one truncating scale policy belongs
 * to {@code ZonedDecimalCodec} of the utility layer; the balance attribute below records why that
 * division of responsibility is contractual rather than merely tidy.
 *
 * <p><strong>Mutability and thread safety.</strong> This is a mutable persistent entity, as the provider
 * requires, and is therefore not thread safe. Instances are confined to the persistence context or the
 * batch chunk that owns them and are never shared across threads or held in a static cache. No caching
 * layer exists in the legacy system and none is introduced here.
 *
 * <p>Provenance: derived by inspection from checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19, which the copybook carries in its trailer
 * comment at line 12. The legacy estate is read-only reference: no source text is transcribed into this
 * module, so member names, field names, byte offsets, widths and codes are cited instead.
 *
 * @see TransactionCategoryBalanceId
 */
@Entity
@Table(name = "transaction_category_balance")
@IdClass(TransactionCategoryBalanceId.class)
public class TransactionCategoryBalance {

    /**
     * Account identifier - legacy field {@code TRANCAT-ACCT-ID}, offset 0, width 11, and the first
     * component of the 17-byte composite key. Mapped to column {@code trancat_acct_id}, declared
     * {@code VARCHAR(11) NOT NULL} by the schema migration.
     *
     * <p>The legacy field is a zero-filled external-decimal field, yet it is carried as text because
     * its external width and its leading zeros are contractual: the seeded data identifies accounts
     * with values such as {@code "00000000001"}, and a numeric attribute would discard the padding
     * that the 17-byte key image is assembled from. Values are stored exactly as supplied, with no
     * trimming and no normalization.
     *
     * <p>The migration constrains this column with a foreign key to the account table. That
     * constraint is enforced by the database and is deliberately <em>not</em> mirrored as a mapped
     * relationship here: the column is simultaneously a key component under the composite-identifier
     * mapping, so an association would drag in an identifier-mapping layer that nothing upstream
     * requires. The attribute stays a plain string and the referential rule stays in the schema.
     */
    @Id
    @Column(name = "trancat_acct_id", length = 11, nullable = false)
    private String trancatAcctId;

    /**
     * Transaction type code - legacy field {@code TRANCAT-TYPE-CD}, offset 11, width 2, and the second
     * component of the 17-byte composite key. Mapped to column {@code trancat_type_cd}, declared
     * {@code VARCHAR(2) NOT NULL} by the schema migration. Stored verbatim, with no trimming and no
     * normalization.
     *
     * <p>This is the type code as it appears in the record image. Although the estate also holds a
     * transaction-type reference table, no foreign key joins the two, so this is a classification
     * lexeme carried on the record and a component of this key - not an association.
     */
    @Id
    @Column(name = "trancat_type_cd", length = 2, nullable = false)
    private String trancatTypeCd;

    /**
     * Transaction category code - legacy field {@code TRANCAT-CD}, offset 13, width 4, and the third
     * component of the 17-byte composite key. Mapped to column {@code trancat_cd}, declared
     * {@code VARCHAR(4) NOT NULL} by the schema migration. Stored verbatim, so leading zeros survive.
     *
     * <p>As with the account identifier the legacy field is digit-only and is nonetheless carried as
     * text, because the four-character external width is part of the key. A category of {@code "0005"}
     * must remain {@code "0005"} and must never narrow to a value that renders as {@code 5}; the
     * seeded data contains exactly such values. No foreign key joins this column to the
     * transaction-category reference table, so this too is a key component rather than an association.
     */
    @Id
    @Column(name = "trancat_cd", length = 4, nullable = false)
    private String trancatCd;

    /**
     * Accumulated category balance - legacy field {@code TRAN-CAT-BAL}, offset 17, width 11, a signed
     * external-decimal field of nine integer digits and two decimal digits. Mapped to column
     * {@code tran_cat_bal}, declared {@code NUMERIC(11,2) NOT NULL} by the schema migration, hence a
     * declared precision of 11 and a scale of 2.
     *
     * <p>An exact decimal type is mandatory. The mandated construct mapping requires decimal precision
     * identical to the legacy field with no floating-point substitution, so no binary approximate type
     * appears anywhere in this module. The legacy field is zoned decimal under display usage, not
     * packed, so the sign is folded into the trailing byte of the image rather than into a packed
     * nibble; decoding that overpunched byte is the record mapper's responsibility and never this
     * class's.
     *
     * <p><strong>Carried, never computed, never rescaled.</strong> This class applies no scaling and
     * performs no arithmetic on the balance - it adds nothing, subtracts nothing, multiplies nothing,
     * divides nothing, negates nothing and rounds nothing. Scaling is the single responsibility of
     * {@code ZonedDecimalCodec} in the utility layer, which applies one truncating policy uniformly so
     * that no other component can introduce a different one. A passive carrier that rescaled on the way
     * in or out would apply the policy twice, and could round where the legacy truncates.
     *
     * <p><strong>The arithmetic this balance feeds is order-sensitive, which is why it is not done
     * here.</strong> The accrual program multiplies this balance by the disclosure rate <em>first</em>
     * and only then divides the product by the twelve-hundred monthly divisor, storing the result into
     * a field of the same nine-integer, two-decimal shape. A census of the estate found no rounding
     * clause anywhere, so that store truncates toward zero. Pre-scaling either operand before the
     * multiplication is algebraically identical in exact arithmetic but moves the truncation point and
     * changes the resulting cent, so the operand order is contractual and is reproduced literally by
     * {@code InterestCalculationService}, which owns the computation. Interest is computed only where
     * the matching rate is non-zero, and no fee logic may be invented: the fee routine invoked
     * alongside accrual is empty in the legacy program and is preserved as a documented no-op.
     *
     * <p>The attribute is deliberately left unset by the no-argument constructor rather than seeded
     * with a zero, so an unpopulated instance stays distinguishable from a genuine zero balance - and
     * genuine zero balances are the norm, since every seeded row carries one.
     */
    @Column(name = "tran_cat_bal", precision = 11, scale = 2, nullable = false)
    private BigDecimal tranCatBal;

    /**
     * Creates an empty instance. This constructor exists for the persistence provider, which requires a
     * no-argument constructor in order to materialise an entity before populating its state; it is also
     * why this type is a plain mutable class rather than a record. Application and test code should use
     * {@link #TransactionCategoryBalance(String, String, String, BigDecimal)}.
     */
    protected TransactionCategoryBalance() {
        // Intentionally empty: the provider assigns state after construction.
    }

    /**
     * Creates a fully populated category balance from its three key components, in the contractual order
     * in which they occupy the record image, followed by the balance.
     *
     * <p>Every argument is stored exactly as supplied. Nothing is trimmed, padded, folded, scaled,
     * rounded or validated here, because the fixed-width padding on the key components and the scale on
     * the balance are both part of the persisted contract.
     *
     * <p>Fields are assigned directly rather than through the mutators below, so that no overridable
     * method is invoked while construction is still in progress.
     *
     * @param trancatAcctId account identifier, key part 1, 11 bytes at offset 0, stored verbatim
     *                      including leading zeros
     * @param trancatTypeCd transaction type code, key part 2, 2 bytes at offset 11, stored verbatim
     * @param trancatCd     transaction category code, key part 3, 4 bytes at offset 13, stored verbatim
     *                      including leading zeros
     * @param tranCatBal    accumulated category balance at scale two, stored verbatim with no rescaling
     */
    public TransactionCategoryBalance(String trancatAcctId,
                                      String trancatTypeCd,
                                      String trancatCd,
                                      BigDecimal tranCatBal) {
        this.trancatAcctId = trancatAcctId;
        this.trancatTypeCd = trancatTypeCd;
        this.trancatCd = trancatCd;
        this.tranCatBal = tranCatBal;
    }

    /**
     * Returns the account identifier exactly as stored, at its full declared width and with its leading
     * zeros intact.
     *
     * @return the 11-byte account identifier, unmodified
     */
    public String getTrancatAcctId() {
        return trancatAcctId;
    }

    /**
     * Replaces the account identifier. The argument is assigned as supplied: the value is not trimmed,
     * padded, folded or validated, because its external width is part of the key.
     *
     * @param trancatAcctId the 11-byte account identifier to store verbatim
     */
    public void setTrancatAcctId(String trancatAcctId) {
        this.trancatAcctId = trancatAcctId;
    }

    /**
     * Returns the transaction type code exactly as stored.
     *
     * @return the 2-byte transaction type code, unmodified
     */
    public String getTrancatTypeCd() {
        return trancatTypeCd;
    }

    /**
     * Replaces the transaction type code. The argument is assigned as supplied, with no normalization of
     * any kind.
     *
     * @param trancatTypeCd the 2-byte transaction type code to store verbatim
     */
    public void setTrancatTypeCd(String trancatTypeCd) {
        this.trancatTypeCd = trancatTypeCd;
    }

    /**
     * Returns the transaction category code exactly as stored, with its leading zeros intact.
     *
     * @return the 4-byte transaction category code, unmodified
     */
    public String getTrancatCd() {
        return trancatCd;
    }

    /**
     * Replaces the transaction category code. The argument is assigned as supplied, so leading zeros
     * survive and the external width is preserved.
     *
     * @param trancatCd the 4-byte transaction category code to store verbatim
     */
    public void setTrancatCd(String trancatCd) {
        this.trancatCd = trancatCd;
    }

    /**
     * Returns the accumulated category balance exactly as stored, at the scale it was given. The value
     * is not rescaled on the way out, so a genuine zero balance is returned at its stored scale rather
     * than normalized - and every seeded row carries such a zero.
     *
     * @return the accumulated category balance, unmodified, or {@code null} on an unpopulated instance
     */
    public BigDecimal getTranCatBal() {
        return tranCatBal;
    }

    /**
     * Replaces the accumulated category balance. The argument is assigned as supplied: it is not
     * rescaled, rounded, clamped or range-checked here. Scaling is applied once, in the zoned-decimal
     * codec of the utility layer, and no range constraint is imposed because the legacy field is signed
     * and both a zero and a negative balance are legitimate values the accrual run must be able to read.
     *
     * @param tranCatBal the accumulated category balance to store verbatim at scale two
     */
    public void setTranCatBal(BigDecimal tranCatBal) {
        this.tranCatBal = tranCatBal;
    }

    /**
     * Returns this row's composite key as a single addressable value, built from the three key
     * components in their contractual order.
     *
     * <p>A new key is produced on each call and the components are copied by reference exactly as
     * stored, so leading zeros are carried through unchanged. No instance is retained, cached or shared
     * between calls.
     *
     * @return a key equal to the identity of this row
     */
    public TransactionCategoryBalanceId toId() {
        return new TransactionCategoryBalanceId(trancatAcctId, trancatTypeCd, trancatCd);
    }

    /**
     * Compares this row with another by its three key components only, in declaration order, byte for
     * byte.
     *
     * <p>The balance is deliberately excluded. Equality follows persistent identity, and the balance is
     * mutable state that the accrual run rewrites: including it would let an instance change its own
     * equality and hash across a flush, which would corrupt any set or map that had already stored it.
     *
     * <p>No component is trimmed, padded or case folded before comparison. That is intentional - a
     * zero-filled identifier is a different key from its shortened form and must compare unequal in Java
     * exactly as the two remain distinct rows in the database.
     *
     * @param o the object to compare against
     * @return {@code true} only if {@code o} is a {@code TransactionCategoryBalance} whose three key
     *         components each equal this row's corresponding component
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategoryBalance other)) {
            return false;
        }
        return Objects.equals(this.trancatAcctId, other.trancatAcctId)
                && Objects.equals(this.trancatTypeCd, other.trancatTypeCd)
                && Objects.equals(this.trancatCd, other.trancatCd);
    }

    /**
     * Hashes the three key components, in declaration order, from their stored values so that the hash
     * agrees with {@link #equals(Object)} for zero-filled and shortened values alike. The balance is
     * excluded for the same reason it is excluded from equality.
     *
     * @return the hash code of this row's key
     */
    @Override
    public int hashCode() {
        return Objects.hash(trancatAcctId, trancatTypeCd, trancatCd);
    }

    /**
     * Returns a diagnostic rendering of the three key components and nothing else. Each value is quoted
     * and printed as stored, so a width or padding difference stays visible in logs and in assertion
     * failures.
     *
     * <p>The balance is deliberately omitted: it is financial data and has no place in an incidental
     * diagnostic rendering.
     *
     * @return a diagnostic string containing the three key components
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalance[trancatAcctId='" + trancatAcctId
                + "', trancatTypeCd='" + trancatTypeCd
                + "', trancatCd='" + trancatCd + "']";
    }
}
