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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import com.carddemo.domain.id.DisclosureGroupId;

/**
 * Disclosure-group interest rate - the Java translation of the {@code DIS-GROUP-RECORD} layout
 * declared in copybook {@code CVTRA02Y}, whose header states a record length of 50.
 *
 * <p>A disclosure group associates an account group with a transaction type and a transaction
 * category, and carries the one interest rate that the interest-accrual batch program applies to
 * matching category balances. There is exactly one rate per group/type/category triple, so the entire
 * identity of a row is its three-part key and the rate is the only non-key attribute. Four fields of
 * the 50-byte image are persisted; each attribute below documents its own offset and width. The three
 * key parts sum to 10 + 2 + 4 = 16 bytes and occupy the leading portion of the image, and the
 * trailing 28-byte filler beginning at offset 22 is <em>not</em> persisted - no attribute, no column.
 * Offset arithmetic belongs exclusively to the fixed-width record mapper in the utility layer.
 *
 * <p><strong>Cluster provenance corroborates the component order.</strong> The legacy indexed cluster
 * is defined in {@code DISCGRP.jcl} with {@code KEYS(16 0)} and {@code RECORDSIZE(50 50)}. A declared
 * key length of 16 beginning at offset 0 is decisive: it confirms both that the key is the leading
 * substring of the record image and that the three components, in exactly this order, make up that
 * substring. The primary-key column order of the table agrees, giving three independent confirmations
 * of one ordering. Because the key <em>is</em> that leading substring, the persistent identity of a
 * row is its business key; no surrogate or provider-assigned identifier is introduced, since either
 * would sever the record-image-to-row correspondence that byte-level output parity depends on.
 *
 * <p><strong>Composite identity.</strong> The key is realised as {@link DisclosureGroupId}, bound
 * through {@code @IdClass} rather than as an embedded identifier. The provider matches an identifier
 * class to its entity by field <em>name</em> and field <em>type</em>, so the three key attributes here
 * are named and typed identically to that class; a divergence in either is a start-up failure rather
 * than a silent defect, and component order is preserved in the declaration order below.
 *
 * <p>Three distinct identifier classes exist in this module and must never be interchanged: the
 * 16-byte key modelled here, the 17-byte key of the transaction-category-balance entity, and the
 * 6-byte key of the transaction-category entity. Each belongs to exactly one entity.
 *
 * <p><strong>Key values carry significant padding and are never trimmed</strong> - not in a
 * constructor, not in an accessor, and above all not in {@link #equals(Object)} or
 * {@link #hashCode()}. Components are compared byte for byte, so a padded identifier is deliberately
 * unequal to its shortened form, exactly as the two are distinct in the database. The account group
 * identifier documents why this is load-bearing rather than stylistic.
 *
 * <p><strong>Seeded data, and exactly which accrual branches it reaches.</strong> The reference data
 * holds 51 rows measuring 2,601 bytes at a 50-byte record length, forming three complete 17-row groups
 * keyed {@code "A000000000"}, {@code "DEFAULT   "} and {@code "ZEROAPR   "} - the latter two padded to
 * the full ten characters. Every rate in the zero-rate group is exactly zero, and the other two groups
 * carry 15.00 on the {@code (01, 0001)} type and category that every seeded balance uses. Which
 * branches that composition actually reaches follows from one further seeded fact, which belongs to the
 * account table rather than to this one: all 50 seeded account rows carry exactly ten spaces in their
 * account group identifier, and no seeded group key is ten spaces.
 *
 * <ul>
 *   <li><strong>The status-{@code 23} default fallback is reachable from seeded data alone.</strong>
 *       Every seeded account misses its first probe and re-probes as {@code "DEFAULT   "}, so the
 *       fallback is the path a seed-only accrual run takes for all fifty of them.</li>
 *   <li><strong>The direct group hit is not reachable from seeded data alone.</strong> It needs an
 *       account constructed with {@code "A000000000"} or {@code "ZEROAPR   "} in its group identifier,
 *       together with a category balance on the matching type and category.</li>
 *   <li><strong>The accrual skip branch is not reachable from seeded data alone either</strong>, even
 *       though the zero-rate rows are genuinely seeded and genuinely zero. Interest is computed only
 *       when the rate is non-zero, but the fallback lands on {@code "DEFAULT   "}, whose rate on this
 *       type and category is 15.00, so a seed-only run always computes. Reaching the skip needs that
 *       same constructed account, pointed at the zero-rate group.</li>
 * </ul>
 *
 * <p><strong>No foreign key references this table, by design.</strong> The account table's group
 * identifier is deliberately not a foreign key here, and cannot be one: the group identifier is only
 * the first of three key components and is nonunique on its own, recurring once per type/category
 * combination - 17 times per group in the verified reference data. A single-column reference to a
 * partial composite prefix is not expressible as a foreign key, and promoting it to one would
 * fabricate a constraint the legacy design never had and would reject the very unmatched-group case
 * the fallback exists to absorb. Resolution is a runtime lookup with a fallback, not referential
 * integrity, so this class models no association in either direction and holds no collection.
 *
 * <p>The rate is carried here and computed elsewhere: this class performs no arithmetic and applies
 * no scaling, both of which belong to the accrual service and to the zoned-decimal codec in the
 * utility layer. The rate attribute records why that division of responsibility is contractual rather
 * than merely tidy.
 *
 * @see DisclosureGroupId
 */
@Entity
@Table(name = "disclosure_group")
@IdClass(DisclosureGroupId.class)
public class DisclosureGroup {

    /**
     * Total digit count of the rate column: 6, being four digits before the implied decimal point and two
     * after, from the copybook's own picture clause.
     *
     * <p>Named so that the column declaration and the persistence-time rule read the one figure rather
     * than two copies of it.
     */
    static final int DIS_INT_RATE_PRECISION = 6;

    /**
     * Width of the group identifier: 10, from {@code DIS-ACCT-GROUP-ID PIC X(10)}.
     *
     * <p><strong>This is the width the whole rate lookup turns on.</strong> Two of the three seeded
     * groups are seven characters followed by three spaces, so the padding is part of the key rather
     * than decoration of it, and a shorter value here does not fail - it matches no row and sends the
     * lookup to its default-group fallback. Named so that the column declaration, the persistence-time
     * rule and the check constraint in {@code V1__create_schema.sql} read the one figure. Recorded as
     * {@code DL-297} in {@code docs/decision-log.md}.
     */
    static final int DIS_ACCT_GROUP_ID_WIDTH = 10;

    /** Width of the type code: 2, from {@code DIS-TRAN-TYPE-CD PIC X(02)}. */
    static final int DIS_TRAN_TYPE_CD_WIDTH = 2;

    /**
     * Width of the category code: 4, from {@code DIS-TRAN-CAT-CD PIC 9(04)}.
     *
     * <p><strong>Width only, with no digit class, even though the picture clause is numeric.</strong> The
     * interest run composes this key from the category-balance row's own {@code TRANCAT-CD}, which
     * {@code app/jcl/PRTCATBL.jcl} declares as {@code ZD} - zoned decimal, whose sign occupies the final
     * byte, so a legitimately signed value ends in a brace or a letter. Refusing that here would not make
     * such a row invalid; it would make it permanently <em>unmatchable</em>, which sends the rate lookup
     * to its default-group fallback and is the same class of silent defect this entry exists to close.
     */
    static final int DIS_TRAN_CAT_CD_WIDTH = 4;

    /**
     * Account group identifier - key part 1, 10 bytes at offset 0 of the record image, from
     * {@code DIS-ACCT-GROUP-ID}.
     *
     * <p><strong>Stored verbatim; never trimmed.</strong> Values occupy the full ten characters and
     * their trailing spaces are part of the key, not incidental whitespace. Two of the three seeded
     * group identifiers are padded, and the padded ten-character {@code "DEFAULT   "} form - the
     * seven characters {@code DEFAULT} followed by three spaces - is precisely what the
     * interest-accrual program matches on when it retries a lookup after file status {@code 23},
     * because a legacy alphanumeric move into a longer field left-justifies and space-fills.
     * Shortening this value would break that fallback silently.
     *
     * <p>The column is a bounded variable-length character type rather than a blank-padded one, so
     * that the padding this field carries is stored and compared exactly as written instead of being
     * hidden by implicit padding semantics.
     */
    @Id
    @Column(name = "dis_acct_group_id", length = DIS_ACCT_GROUP_ID_WIDTH, nullable = false)
    private String disAcctGroupId;

    /**
     * Transaction type code - key part 2, 2 bytes at offset 10 of the record image, from
     * {@code DIS-TRAN-TYPE-CD}. Stored verbatim, with no trimming and no normalization.
     *
     * <p>This is the type code as it appears in the record image. It is not modelled as an
     * association to the transaction-type reference table: the legacy design carries no such
     * constraint, and the value's role here is as a key component of this composite key.
     */
    @Id
    @Column(name = "dis_tran_type_cd", length = DIS_TRAN_TYPE_CD_WIDTH, nullable = false)
    private String disTranTypeCd;

    /**
     * Transaction category code - key part 3, 4 bytes at offset 12 of the record image, from
     * {@code DIS-TRAN-CAT-CD}. Stored verbatim, with no trimming and no normalization.
     *
     * <p>The legacy field is a zero-filled external-decimal field, yet it is carried as text and
     * mapped to a bounded character column because its external width and its leading zeros are
     * contractual. A category code of {@code "0005"} must remain {@code "0005"}: a numeric type
     * would render it as {@code 5}, and the sixteen-byte key image would then no longer reconstruct
     * from the row. As with the type code, this is a key component and not an association to the
     * transaction-category reference table.
     */
    @Id
    @Column(name = "dis_tran_cat_cd", length = DIS_TRAN_CAT_CD_WIDTH, nullable = false)
    private String disTranCatCd;

    /**
     * Disclosure interest rate - 6 bytes at offset 16 of the record image, from
     * {@code DIS-INT-RATE}, a signed external-decimal field of four integer digits and two decimal
     * digits, mapped to an exact numeric column of precision six and scale two.
     *
     * <p>This is the only exact numeric column in the schema declared with a precision of six. Every
     * other amount column in the module is wider, so this precision must not be copied from a
     * sibling entity, and an exact decimal type is mandatory: an approximate binary numeric type
     * would not reproduce the legacy decimal representation and is prohibited throughout the
     * migration.
     *
     * <p><strong>Carried, never computed.</strong> This class applies no scaling and performs no
     * arithmetic on the rate. Scaling is the sole responsibility of the zoned-decimal codec in the
     * utility layer, which applies one truncating policy uniformly so that no other component can
     * introduce a different one. The field is left unset by the no-argument constructor rather than
     * seeded with a zero, so an unpopulated instance is distinguishable from a genuine zero rate -
     * and genuine zero rates do occur, in the seeded zero-rate group.
     *
     * <p><strong>The arithmetic this rate feeds is order-sensitive.</strong> The accrual program
     * multiplies the category balance by this rate first and only then divides the product by the
     * twelve-hundred monthly divisor, storing into a two-decimal field. Because the legacy estate
     * declares no rounding clause anywhere, that store truncates toward zero. Pre-scaling the rate
     * before the multiplication is algebraically identical in exact arithmetic but moves the
     * truncation point and changes the resulting cent, so the operand order is contractual and is
     * reproduced literally by the accrual service that owns the computation. Interest is computed
     * only when this rate is non-zero. No fee logic may be invented: the fee routine invoked
     * alongside accrual is empty in the legacy program and is preserved as a documented no-op.
     */
    @Column(name = "dis_int_rate", precision = DIS_INT_RATE_PRECISION, scale = 2, nullable = false)
    private BigDecimal disIntRate;

    /**
     * Whether the field image this instance was mapped from carried a <em>negative</em> overpunch on an
     * all-zero {@code DIS-INT-RATE}.
     *
     * <p><strong>Not persisted, and it cannot be.</strong> A zoned-decimal image distinguishes a negative
     * zero from a positive one by its final byte - {@code '}'} against {@code '{'} - while neither
     * {@link java.math.BigDecimal} nor a numeric column has a negative zero at all. The bit therefore has
     * nowhere to live except beside the amount, and it is declared {@link jakarta.persistence.Transient}
     * because inventing a column for it would put a representation artefact into the schema.
     *
     * <p>What it buys is byte parity on the paths that matter: a record read from a fixed-width resource
     * and written back out re-emits the byte it arrived with rather than silently normalising
     * {@code '}'} to {@code '{'}. It is meaningful only while every digit is zero, and the record mapper
     * that owns this layout is its only producer and its only consumer.
     *
     * <p>It is deliberately absent from {@link #equals(Object)} and {@link #hashCode()}: two rows holding
     * the same amount are the same row, and a sign carried on a zero is a property of an image rather than
     * of the value.
     */
    @Transient
    private boolean disIntRateNegativeZero;

    /**
     * Creates an empty instance. This constructor exists for the persistence provider, which
     * requires a no-argument constructor in order to materialise an entity before populating its
     * state; it is also why this type is a plain mutable class rather than a record. Application and
     * test code should use {@link #DisclosureGroup(String, String, String, BigDecimal)}.
     */
    protected DisclosureGroup() {
        // Intentionally empty: the provider assigns state after construction.
    }

    /**
     * Creates a fully populated disclosure group from its three key components, in the contractual
     * order in which they occupy the record image, followed by the rate.
     *
     * <p>Every argument is stored exactly as supplied. Nothing is trimmed, padded, folded, scaled or
     * validated here, because the fixed-width padding on the key components and the scale on the
     * rate are both part of the persisted contract. In particular the account group identifier is
     * expected at its full ten characters, including any trailing spaces - the padded default group
     * identifier used by the accrual fallback is exactly such a value.
     *
     * <p>Fields are assigned directly rather than through the accessors below, so that no overridable
     * method is invoked while construction is still in progress.
     *
     * @param disAcctGroupId account group identifier, key part 1, 10 bytes at offset 0, stored
     *                       verbatim including trailing spaces
     * @param disTranTypeCd  transaction type code, key part 2, 2 bytes at offset 10, stored verbatim
     * @param disTranCatCd   transaction category code, key part 3, 4 bytes at offset 12, stored
     *                       verbatim including leading zeros
     * @param disIntRate     disclosure interest rate at scale two, stored verbatim with no rescaling
     */
    public DisclosureGroup(String disAcctGroupId,
                           String disTranTypeCd,
                           String disTranCatCd,
                           BigDecimal disIntRate) {
        this.disAcctGroupId = disAcctGroupId;
        this.disTranTypeCd = disTranTypeCd;
        this.disTranCatCd = disTranCatCd;
        this.disIntRate = disIntRate;
    }

    /**
     * Returns the account group identifier exactly as stored, at its full declared width and with
     * any trailing spaces intact.
     *
     * @return the 10-byte account group identifier, unmodified
     */
    public String getDisAcctGroupId() {
        return disAcctGroupId;
    }

    /**
     * Replaces the account group identifier. The argument is assigned as supplied: the value is not
     * trimmed, padded, folded or validated, because its padding is part of the key.
     *
     * @param disAcctGroupId the 10-byte account group identifier to store verbatim
     */
    public void setDisAcctGroupId(String disAcctGroupId) {
        this.disAcctGroupId = disAcctGroupId;
    }

    /**
     * Returns the transaction type code exactly as stored.
     *
     * @return the 2-byte transaction type code, unmodified
     */
    public String getDisTranTypeCd() {
        return disTranTypeCd;
    }

    /**
     * Replaces the transaction type code. The argument is assigned as supplied, with no
     * normalization of any kind.
     *
     * @param disTranTypeCd the 2-byte transaction type code to store verbatim
     */
    public void setDisTranTypeCd(String disTranTypeCd) {
        this.disTranTypeCd = disTranTypeCd;
    }

    /**
     * Returns the transaction category code exactly as stored, with its leading zeros intact.
     *
     * @return the 4-byte transaction category code, unmodified
     */
    public String getDisTranCatCd() {
        return disTranCatCd;
    }

    /**
     * Replaces the transaction category code. The argument is assigned as supplied, so leading zeros
     * survive and the external width is preserved.
     *
     * @param disTranCatCd the 4-byte transaction category code to store verbatim
     */
    public void setDisTranCatCd(String disTranCatCd) {
        this.disTranCatCd = disTranCatCd;
    }

    /**
     * Returns the disclosure interest rate exactly as stored, at the scale it was given. The value is
     * not rescaled on the way out, so a genuine zero rate is returned at its stored scale rather than
     * normalized.
     *
     * @return the disclosure interest rate, unmodified, or {@code null} on an unpopulated instance
     */
    public BigDecimal getDisIntRate() {
        return disIntRate;
    }

    /**
     * Replaces the disclosure interest rate. The argument is assigned as supplied: it is not
     * rescaled, rounded, clamped or range-checked here. Scaling is applied once, in the
     * zoned-decimal codec of the utility layer, and no range constraint is imposed because genuine
     * zero rates occur in the reference data and the accrual program depends on them loading.
     *
     * @param disIntRate the disclosure interest rate to store verbatim at scale two
     */
    public void setDisIntRate(BigDecimal disIntRate) {
        this.disIntRate = disIntRate;
    }

    /**
     * Whether the mapped image carried a negative overpunch on an all-zero {@code DIS-INT-RATE}.
     *
     * @return {@code true} only when the amount is zero and its image was negatively signed
     */
    public boolean isDisIntRateNegativeZero() {
        return disIntRateNegativeZero;
    }

    /**
     * Records whether the mapped image carried a negative overpunch on an all-zero {@code DIS-INT-RATE}.
     *
     * <p>Set by the record mapper that owns this layout, from the sign the image actually carried. It is
     * never derived from the amount, because the amount cannot express it.
     *
     * @param disIntRateNegativeZero the negative-zero bit the image carried
     */
    public void setDisIntRateNegativeZero(boolean disIntRateNegativeZero) {
        this.disIntRateNegativeZero = disIntRateNegativeZero;
    }

    /**
     * Returns this row's composite key as a single addressable value, built from the three key
     * components in their contractual order.
     *
     * <p>A new key is produced on each call and the components are copied by reference exactly as
     * stored, so padding and leading zeros are carried through unchanged. No instance is retained or
     * shared between calls.
     *
     * @return a key equal to the identity of this row
     */
    public DisclosureGroupId toId() {
        return new DisclosureGroupId(disAcctGroupId, disTranTypeCd, disTranCatCd);
    }

    /**
     * Normalises the interest rate to scale two, truncating toward zero, immediately before the row is
     * inserted or updated.
     *
     * <p>No rule is applied to the group identifier. It is declared as ten <em>alphanumeric</em>
     * characters and is legitimately ten spaces in every seeded account row, so a digit class would reject
     * the reference data outright; and the group key is not a key of this row alone - the interest run
     * falls back to a default group when a lookup misses, which is behaviour a refused insert would
     * remove.
     *
     * <p>An amount is <strong>normalised</strong> rather than refused: a value computed in a service,
     * parsed from a request or left over from a division carries whatever scale the arithmetic produced,
     * and an entity that stored it verbatim would let a repository write bypass the truncation policy the
     * whole estate depends on. {@code StoredValueRules} records why that policy truncates toward zero
     * rather than rounding, and why the constants it uses are restated there rather than imported from the
     * fixed-width codec.
     *
     * @throws IllegalArgumentException if an amount is absent or beyond the declared precision
     */
    @PrePersist
    @PreUpdate
    void normalizeAndValidateBeforeWrite() {
        StoredValueRules.requireFixedWidth(disAcctGroupId, DIS_ACCT_GROUP_ID_WIDTH, "disAcctGroupId");
        StoredValueRules.requireFixedWidth(disTranTypeCd, DIS_TRAN_TYPE_CD_WIDTH, "disTranTypeCd");
        StoredValueRules.requireFixedWidth(disTranCatCd, DIS_TRAN_CAT_CD_WIDTH, "disTranCatCd");
        this.disIntRate =
                StoredValueRules.normalizedAmount(disIntRate, DIS_INT_RATE_PRECISION, "disIntRate");
    }

    /**
     * Compares this row with another by its three key components only, in declaration order, byte
     * for byte.
     *
     * <p>The rate is deliberately excluded. Equality follows persistent identity, and the rate is
     * mutable state: including it would let an instance change its own equality and hash across a
     * flush, which would corrupt any set or map that had already stored it.
     *
     * <p>No component is trimmed, padded or case folded before comparison. That is intentional: a
     * padded ten-character group identifier is a different key from its shortened form and must
     * compare unequal in Java exactly as the two remain distinct rows in the database. Collapsing
     * them would also make the status-{@code 23} fallback appear to resolve keys that it does not.
     *
     * @param o the object to compare against
     * @return {@code true} only if {@code o} is a {@code DisclosureGroup} whose three key components
     *         each equal this row's corresponding component
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DisclosureGroup other)) {
            return false;
        }
        return Objects.equals(this.disAcctGroupId, other.disAcctGroupId)
                && Objects.equals(this.disTranTypeCd, other.disTranTypeCd)
                && Objects.equals(this.disTranCatCd, other.disTranCatCd);
    }

    /**
     * Hashes the three key components, in declaration order, from their stored values so that the
     * hash agrees with {@link #equals(Object)} for padded and shortened values alike. The rate is
     * excluded for the same reason it is excluded from equality.
     *
     * @return the hash code of this row's key
     */
    @Override
    public int hashCode() {
        return Objects.hash(disAcctGroupId, disTranTypeCd, disTranCatCd);
    }

    /**
     * Returns a diagnostic rendering of the three key components and nothing else. Each value is
     * quoted and printed as stored, so significant trailing spaces stay visible in logs and in
     * assertion failures.
     *
     * <p>The rate is deliberately omitted: it is financial data and has no place in an incidental
     * diagnostic rendering.
     *
     * @return a diagnostic string containing the three key components
     */
    @Override
    public String toString() {
        return "DisclosureGroup[disAcctGroupId='" + disAcctGroupId
                + "', disTranTypeCd='" + disTranTypeCd
                + "', disTranCatCd='" + disTranCatCd + "']";
    }
}
