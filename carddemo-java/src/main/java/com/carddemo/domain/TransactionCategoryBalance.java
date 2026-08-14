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

import com.carddemo.domain.id.TransactionCategoryBalanceId;

/**
 * Per-account, per-category accumulated balance: the 50-byte record of
 * {@code app/cpy/CVTRA01Y.cpy}. Four fields make up the image - an 11-byte account identifier at
 * offset 0, a 2-byte transaction type code at offset 11, a 4-byte transaction category code at
 * offset 13 and the signed balance at offset 17 - followed by a 22-byte filler at offset 28 that is
 * deliberately not persisted, because trailing filler carries no information and is reconstructed on
 * output from the declared record width.
 *
 * <p>Identity is the legacy three-part business key in that order, together the 17-byte leading
 * substring of the image, attested independently by the copybook, by {@code KEYS(17 0)} with
 * {@code RECORDSIZE(50 50)} in {@code app/jcl/TCATBALF.jcl} and by the migration's primary-key
 * column order. It is realised through {@link TransactionCategoryBalanceId}, which the provider
 * matches by field name and type, so any divergence fails start-up. No surrogate identifier exists.
 *
 * <p><strong>Name-collision warning.</strong> Copybook {@code CVTRA04Y} names its key group
 * identically while declaring a structurally unrelated two-component 6-byte key - corroborated by
 * {@code KEYS(6 0)} in {@code app/jcl/TRANCATG.jcl} - behind {@link TransactionCategory}. That key
 * is not a prefix or reusable fragment of this one: this key leads with an account identifier the
 * other does not contain, so the two layouts align at no offset, they are never interchangeable, and
 * no shared supertype or helper may be introduced to "reuse" the overlapping names (decision D-37).
 *
 * <p>The copybook's own prefix inconsistency between the key components and the balance is
 * transcribed exactly, because the mapping is validated against the migrated schema at start-up.
 *
 * <p>The balance is an exact decimal at scale 2, matching {@code PIC S9(09)V99}; no floating-point
 * form is admitted anywhere. This class computes nothing - the accrual that consumes the balance
 * belongs to the service layer and truncates rather than rounds - and offset arithmetic lives only
 * in {@code com.carddemo.util.TranCatBalRecordMapper}.
 */
@Entity
@Table(name = "transaction_category_balance")
@IdClass(TransactionCategoryBalanceId.class)
public class TransactionCategoryBalance {

    /**
     * Total digit count of the balance column: 11, being nine digits before the implied decimal point and
     * two after, from the copybook's own picture clause.
     *
     * <p>Named so that the column declaration and the persistence-time rule read the one figure rather
     * than two copies of it.
     */
    static final int TRAN_CAT_BAL_PRECISION = 11;

    /**
     * Width of the account identifier part of the key: 11, from {@code TRANCAT-ACCT-ID PIC 9(11)}.
     *
     * <p><strong>Width is checked and the digit class is not, and that is deliberate for both numeric
     * parts of this key.</strong> {@code app/jcl/PRTCATBL.jcl} declares the sort typing of this very key
     * as {@code TRANCAT-ACCT-ID,1,11,ZD}, {@code TRANCAT-TYPE-CD,12,2,CH} and
     * {@code TRANCAT-CD,14,4,ZD}. Zoned decimal folds the sign into the <em>final byte</em>, so a
     * legitimately signed value ends in a brace or a letter, and the category-balance report's comparator
     * decodes both zoned fields to signed values rather than comparing them as text. A digit class would
     * refuse the byte forms that specification is declared to compare. The digit rule belongs where
     * digits are genuinely required - on the account master this key references by foreign key.
     *
     * <p>Named so that the column declaration, the persistence-time rule and the check constraint in
     * {@code V1__create_schema.sql} read the one figure. The same holds for the two widths that follow.
     * Recorded as {@code DL-297} in {@code docs/decision-log.md}.
     */
    static final int TRANCAT_ACCT_ID_WIDTH = 11;

    /** Width of the type code part of the key: 2, from {@code TRANCAT-TYPE-CD PIC X(02)}. */
    static final int TRANCAT_TYPE_CD_WIDTH = 2;

    /** Width of the category code part of the key: 4, from {@code TRANCAT-CD PIC 9(04)}. */
    static final int TRANCAT_CD_WIDTH = 4;
    @Id
    @Column(name = "trancat_acct_id", length = TRANCAT_ACCT_ID_WIDTH, nullable = false)
    private String trancatAcctId;

    @Id
    @Column(name = "trancat_type_cd", length = TRANCAT_TYPE_CD_WIDTH, nullable = false)
    private String trancatTypeCd;

    @Id
    @Column(name = "trancat_cd", length = TRANCAT_CD_WIDTH, nullable = false)
    private String trancatCd;

    @Column(name = "tran_cat_bal", precision = TRAN_CAT_BAL_PRECISION, scale = 2, nullable = false)
    private BigDecimal tranCatBal;

    /**
     * Whether the field image this instance was mapped from carried a <em>negative</em> overpunch on an
     * all-zero {@code TRAN-CAT-BAL}.
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
    private boolean tranCatBalNegativeZero;

    /** Required by the persistence provider; application code uses the all-arguments constructor. */
    protected TransactionCategoryBalance() {
    }

    public TransactionCategoryBalance(String trancatAcctId,
                                      String trancatTypeCd,
                                      String trancatCd,
                                      BigDecimal tranCatBal) {
        this.trancatAcctId = trancatAcctId;
        this.trancatTypeCd = trancatTypeCd;
        this.trancatCd = trancatCd;
        this.tranCatBal = tranCatBal;
    }

    public String getTrancatAcctId() {
        return trancatAcctId;
    }

    public void setTrancatAcctId(String trancatAcctId) {
        this.trancatAcctId = trancatAcctId;
    }

    public String getTrancatTypeCd() {
        return trancatTypeCd;
    }

    public void setTrancatTypeCd(String trancatTypeCd) {
        this.trancatTypeCd = trancatTypeCd;
    }

    public String getTrancatCd() {
        return trancatCd;
    }

    public void setTrancatCd(String trancatCd) {
        this.trancatCd = trancatCd;
    }

    public BigDecimal getTranCatBal() {
        return tranCatBal;
    }

    public void setTranCatBal(BigDecimal tranCatBal) {
        this.tranCatBal = tranCatBal;
    }

    /**
     * Whether the mapped image carried a negative overpunch on an all-zero {@code TRAN-CAT-BAL}.
     *
     * @return {@code true} only when the amount is zero and its image was negatively signed
     */
    public boolean isTranCatBalNegativeZero() {
        return tranCatBalNegativeZero;
    }

    /**
     * Records whether the mapped image carried a negative overpunch on an all-zero {@code TRAN-CAT-BAL}.
     *
     * <p>Set by the record mapper that owns this layout, from the sign the image actually carried. It is
     * never derived from the amount, because the amount cannot express it.
     *
     * @param tranCatBalNegativeZero the negative-zero bit the image carried
     */
    public void setTranCatBalNegativeZero(boolean tranCatBalNegativeZero) {
        this.tranCatBalNegativeZero = tranCatBalNegativeZero;
    }

    public TransactionCategoryBalanceId toId() {
        return new TransactionCategoryBalanceId(trancatAcctId, trancatTypeCd, trancatCd);
    }

    /**
     * Normalises the category balance to scale two, truncating toward zero, immediately before the row is
     * inserted or updated.
     *
     * <p>This balance is the left operand of the interest computation, which multiplies it by a rate and
     * divides by twelve hundred into a two-decimal field. That division is exactly the route by which a
     * longer scale reaches an entity, which is why the normalisation matters more here than anywhere else
     * in the module.
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
        StoredValueRules.requireFixedWidth(trancatAcctId, TRANCAT_ACCT_ID_WIDTH, "trancatAcctId");
        StoredValueRules.requireFixedWidth(trancatTypeCd, TRANCAT_TYPE_CD_WIDTH, "trancatTypeCd");
        StoredValueRules.requireFixedWidth(trancatCd, TRANCAT_CD_WIDTH, "trancatCd");
        this.tranCatBal =
                StoredValueRules.normalizedAmount(tranCatBal, TRAN_CAT_BAL_PRECISION, "tranCatBal");
    }

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

    @Override
    public int hashCode() {
        return Objects.hash(trancatAcctId, trancatTypeCd, trancatCd);
    }

    @Override
    public String toString() {
        return "TransactionCategoryBalance[trancatAcctId='" + trancatAcctId
                + "', trancatTypeCd='" + trancatTypeCd
                + "', trancatCd='" + trancatCd + "']";
    }
}
