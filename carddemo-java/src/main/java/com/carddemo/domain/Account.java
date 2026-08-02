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
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * JPA entity for a CardDemo account, mapping the 300-byte account record of
 * {@code app/cpy/CVACT01Y.cpy} one attribute per field. The width, the 11-byte key at offset 0 and the
 * 178-byte trailing filler that this class deliberately does not model are corroborated by
 * {@code app/jcl/ACCTFILE.jcl} and by the file section of {@code app/cbl/CBACT01C.cbl}.
 *
 * <p>Three properties of the mapping are load-bearing and easy to undo by accident.
 * <ul>
 *   <li>Identity is the account identifier itself. A generated surrogate key would sever the
 *       record-image-to-row correspondence that byte-level output parity depends on.</li>
 *   <li>Declaration order follows the record image, in which three monetary fields precede the three
 *       date fields and two follow them, so nothing may treat the amounts as one contiguous block.</li>
 *   <li>Values are carried verbatim: constructors, mutators and accessors assign and return without
 *       trimming, padding, case folding, rescaling, rounding, parsing or validating, because
 *       fixed-width padding and decimal scale are part of the record contract. Offset arithmetic
 *       belongs to the record mapper and zoned-decimal conversion to
 *       {@link com.carddemo.util.ZonedDecimalCodec}; this entity computes nothing.</li>
 * </ul>
 *
 * <p>Flyway owns the {@code account} table and Hibernate runs in schema-validation mode, so any
 * divergence between this mapping and the migration fails start-up rather than reshaping the database.
 */
@Entity
@Table(name = "account")
public class Account {

    /**
     * Primary key, and the row's persistent identity: never generated. The sample dataset zero-fills
     * the identifier to its full width, so the leading zeros are contractual and a numeric type would
     * discard them.
     */
    @Id
    @Column(name = "acct_id", length = 11, nullable = false)
    private String acctId;

    /**
     * Held as the raw one-character code rather than a mapped enum. Enum mapping would persist the
     * constant name or an ordinal, neither of which fits a one-character column, and the raw code
     * tolerates a value outside the documented vocabulary exactly as the legacy programs did.
     * Code-to-constant translation belongs to the service layer.
     */
    @Column(name = "acct_active_status", length = 1, nullable = false)
    private String acctActiveStatus;

    @Column(name = "acct_curr_bal", precision = 12, scale = 2, nullable = false)
    private BigDecimal acctCurrBal;

    @Column(name = "acct_credit_limit", precision = 12, scale = 2, nullable = false)
    private BigDecimal acctCreditLimit;

    @Column(name = "acct_cash_credit_limit", precision = 12, scale = 2, nullable = false)
    private BigDecimal acctCashCreditLimit;

    @Column(name = "acct_open_date", length = 10, nullable = false)
    private String acctOpenDate;

    /**
     * The legacy field name drops a letter and reads EXPIRAION. The property and the column are spelled
     * correctly while the offset and width are unchanged, so the record image stays byte-compatible;
     * row 1 of the source anomaly register in {@code docs/decision-log.md}.
     */
    @Column(name = "acct_expiration_date", length = 10, nullable = false)
    private String acctExpirationDate;

    @Column(name = "acct_reissue_date", length = 10, nullable = false)
    private String acctReissueDate;

    @Column(name = "acct_curr_cyc_credit", precision = 12, scale = 2, nullable = false)
    private BigDecimal acctCurrCycCredit;

    @Column(name = "acct_curr_cyc_debit", precision = 12, scale = 2, nullable = false)
    private BigDecimal acctCurrCycDebit;

    @Column(name = "acct_addr_zip", length = 10, nullable = false)
    private String acctAddrZip;

    /**
     * Deliberately neither a foreign key nor an association: the group identifier is only a nonunique
     * leading portion of the disclosure-group composite key, and the interest calculation composes the
     * full key at runtime and falls back to a documented default group when no row matches, which a
     * database constraint could not express. Its trailing spaces are significant and are never trimmed,
     * or a padded identifier would compare equal to its unpadded form in Java while staying distinct in
     * the database.
     */
    @Column(name = "acct_group_id", length = 10, nullable = false)
    private String acctGroupId;

    /**
     * Optimistic-locking counter owned by the persistence provider. It replaces the hand-written
     * before-and-after image comparison of the legacy online update path, and combined with
     * read-committed isolation is strictly stronger than the uncommitted-read, no-recovery baseline the
     * legacy file definitions specified - an improvement rather than a behavioural change
     * (decision-log D-15). Only this entity and the card entity carry one, matching the schema.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Required by the persistence provider, which assigns state after construction.
     */
    protected Account() {
        // Intentionally empty: the persistence provider assigns state after construction.
    }

    /**
     * Creates a fully populated account from the twelve business fields, in record order. Every value is
     * stored exactly as supplied, and the version counter is absent because the provider owns it.
     */
    public Account(String acctId,
                   String acctActiveStatus,
                   BigDecimal acctCurrBal,
                   BigDecimal acctCreditLimit,
                   BigDecimal acctCashCreditLimit,
                   String acctOpenDate,
                   String acctExpirationDate,
                   String acctReissueDate,
                   BigDecimal acctCurrCycCredit,
                   BigDecimal acctCurrCycDebit,
                   String acctAddrZip,
                   String acctGroupId) {
        // Direct field assignment on purpose: calling an overridable setter from a constructor
        // would publish a partially built instance.
        this.acctId = acctId;
        this.acctActiveStatus = acctActiveStatus;
        this.acctCurrBal = acctCurrBal;
        this.acctCreditLimit = acctCreditLimit;
        this.acctCashCreditLimit = acctCashCreditLimit;
        this.acctOpenDate = acctOpenDate;
        this.acctExpirationDate = acctExpirationDate;
        this.acctReissueDate = acctReissueDate;
        this.acctCurrCycCredit = acctCurrCycCredit;
        this.acctCurrCycDebit = acctCurrCycDebit;
        this.acctAddrZip = acctAddrZip;
        this.acctGroupId = acctGroupId;
    }

    public String getAcctId() {
        return acctId;
    }

    public void setAcctId(String acctId) {
        this.acctId = acctId;
    }

    public String getAcctActiveStatus() {
        return acctActiveStatus;
    }

    public void setAcctActiveStatus(String acctActiveStatus) {
        this.acctActiveStatus = acctActiveStatus;
    }

    public BigDecimal getAcctCurrBal() {
        return acctCurrBal;
    }

    public void setAcctCurrBal(BigDecimal acctCurrBal) {
        this.acctCurrBal = acctCurrBal;
    }

    public BigDecimal getAcctCreditLimit() {
        return acctCreditLimit;
    }

    public void setAcctCreditLimit(BigDecimal acctCreditLimit) {
        this.acctCreditLimit = acctCreditLimit;
    }

    public BigDecimal getAcctCashCreditLimit() {
        return acctCashCreditLimit;
    }

    public void setAcctCashCreditLimit(BigDecimal acctCashCreditLimit) {
        this.acctCashCreditLimit = acctCashCreditLimit;
    }

    public String getAcctOpenDate() {
        return acctOpenDate;
    }

    public void setAcctOpenDate(String acctOpenDate) {
        this.acctOpenDate = acctOpenDate;
    }

    public String getAcctExpirationDate() {
        return acctExpirationDate;
    }

    public void setAcctExpirationDate(String acctExpirationDate) {
        this.acctExpirationDate = acctExpirationDate;
    }

    public String getAcctReissueDate() {
        return acctReissueDate;
    }

    public void setAcctReissueDate(String acctReissueDate) {
        this.acctReissueDate = acctReissueDate;
    }

    public BigDecimal getAcctCurrCycCredit() {
        return acctCurrCycCredit;
    }

    public void setAcctCurrCycCredit(BigDecimal acctCurrCycCredit) {
        this.acctCurrCycCredit = acctCurrCycCredit;
    }

    public BigDecimal getAcctCurrCycDebit() {
        return acctCurrCycDebit;
    }

    public void setAcctCurrCycDebit(BigDecimal acctCurrCycDebit) {
        this.acctCurrCycDebit = acctCurrCycDebit;
    }

    public String getAcctAddrZip() {
        return acctAddrZip;
    }

    public void setAcctAddrZip(String acctAddrZip) {
        this.acctAddrZip = acctAddrZip;
    }

    public String getAcctGroupId() {
        return acctGroupId;
    }

    public void setAcctGroupId(String acctGroupId) {
        this.acctGroupId = acctGroupId;
    }

    public long getVersion() {
        return version;
    }

    /**
     * Compares accounts by the account identifier alone, byte for byte with no trimming or case folding,
     * so that Java equality agrees with the database's notion of the same row. Including a mutable
     * non-key field would change an instance's equality and hash across a persistence flush.
     *
     * @param o the object to compare against
     * @return {@code true} only if {@code o} is an {@code Account} with an equal account identifier
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Account other)) {
            return false;
        }
        return Objects.equals(this.acctId, other.acctId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(acctId);
    }

    /**
     * Renders the account identifier and the raw status code, each quoted and printed untrimmed so that
     * significant padding stays visible. No monetary amount, date or address component is included.
     *
     * <p>The identifier is the account business key, so this rendering is identifying data: it belongs
     * in diagnostics with controlled access and must not be emitted to unrestricted logs.
     *
     * @return a diagnostic string containing the account identifier and the raw status code
     */
    @Override
    public String toString() {
        return "Account[acctId='" + acctId + "', acctActiveStatus='" + acctActiveStatus + "']";
    }
}
