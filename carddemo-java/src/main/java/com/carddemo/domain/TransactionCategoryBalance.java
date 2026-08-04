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
     * Total digit count of the balance column: 11, being nine digits before the implied decimal point and two
     * after, from the copybook's own picture clause.
     *
     * <p>Named so that the column declaration and the persistence-time rule read the one figure rather
     * than two copies of it.
     */
    static final int TRAN_CAT_BAL_PRECISION = 11;
    @Id
    @Column(name = "trancat_acct_id", length = 11, nullable = false)
    private String trancatAcctId;

    @Id
    @Column(name = "trancat_type_cd", length = 2, nullable = false)
    private String trancatTypeCd;

    @Id
    @Column(name = "trancat_cd", length = 4, nullable = false)
    private String trancatCd;

    @Column(name = "tran_cat_bal", precision = TRAN_CAT_BAL_PRECISION, scale = 2, nullable = false)
    private BigDecimal tranCatBal;

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
