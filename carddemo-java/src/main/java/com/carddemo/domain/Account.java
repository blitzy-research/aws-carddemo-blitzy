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
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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
     * Width of the account identifier in characters: 11, from the copybook's own picture clause, which
     * declares it as eleven digits.
     *
     * <p>Named so that the column declaration and the persistence-time rule read the one figure rather
     * than two copies of it.
     */
    static final int ACCT_ID_WIDTH = 11;

    /**
     * Total digit count of every money column on this table: 12, being ten digits before the implied
     * decimal point and two after, from the five identical picture clauses the copybook declares.
     *
     * <p>Named so that the column declaration and the persistence-time rule read the one figure rather
     * than two copies of it.
     */
    static final int MONEY_PRECISION = 12;

    /**
     * Width of the account group identifier in characters: 10, from {@code ACCT-GROUP-ID PIC X(10)}.
     *
     * <p>Named so that the column declaration, the persistence-time rule and the check constraint in
     * {@code V1__create_schema.sql} read the one figure rather than three copies of it.
     */
    static final int ACCT_GROUP_ID_WIDTH = 10;

    /**
     * Primary key, and the row's persistent identity: never generated. The sample dataset zero-fills
     * the identifier to its full width, so the leading zeros are contractual and a numeric type would
     * discard them.
     */
    @Id
    @Column(name = "acct_id", length = ACCT_ID_WIDTH, nullable = false)
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

    /**
     * Whether the field image this instance was mapped from carried a <em>negative</em> overpunch on an
     * all-zero {@code ACCT-CURR-BAL}.
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
    private boolean acctCurrBalNegativeZero;

    @Column(name = "acct_credit_limit", precision = 12, scale = 2, nullable = false)
    private BigDecimal acctCreditLimit;

    /**
     * Whether the field image this instance was mapped from carried a <em>negative</em> overpunch on an
     * all-zero {@code ACCT-CREDIT-LIMIT}. Transient, and excluded from equality, for the reasons
     * {@link #acctCurrBalNegativeZero} records.
     */
    @Transient
    private boolean acctCreditLimitNegativeZero;

    @Column(name = "acct_cash_credit_limit", precision = 12, scale = 2, nullable = false)
    private BigDecimal acctCashCreditLimit;

    /**
     * Whether the field image this instance was mapped from carried a <em>negative</em> overpunch on an
     * all-zero {@code ACCT-CASH-CREDIT-LIMIT}. Transient, and excluded from equality, for the reasons
     * {@link #acctCurrBalNegativeZero} records.
     */
    @Transient
    private boolean acctCashCreditLimitNegativeZero;

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

    /**
     * Whether the field image this instance was mapped from carried a <em>negative</em> overpunch on an
     * all-zero {@code ACCT-CURR-CYC-CREDIT}. Transient, and excluded from equality, for the reasons
     * {@link #acctCurrBalNegativeZero} records.
     */
    @Transient
    private boolean acctCurrCycCreditNegativeZero;

    @Column(name = "acct_curr_cyc_debit", precision = 12, scale = 2, nullable = false)
    private BigDecimal acctCurrCycDebit;

    /**
     * Whether the field image this instance was mapped from carried a <em>negative</em> overpunch on an
     * all-zero {@code ACCT-CURR-CYC-DEBIT}. Transient, and excluded from equality, for the reasons
     * {@link #acctCurrBalNegativeZero} records.
     */
    @Transient
    private boolean acctCurrCycDebitNegativeZero;

    @Column(name = "acct_addr_zip", length = 10, nullable = false)
    private String acctAddrZip;

    /**
     * Deliberately neither a foreign key nor an association: the group identifier is only a nonunique
     * leading portion of the disclosure-group composite key, and the interest calculation composes the
     * full key at runtime and falls back to a documented default group when no row matches, which a
     * database constraint could not express. Its trailing spaces are significant and are never trimmed,
     * or a padded identifier would compare equal to its unpadded form in Java while staying distinct in
     * the database.
     *
     * <p><strong>Exactly ten characters, enforced before the write.</strong> Not being a foreign key is
     * what makes the width matter: nothing else would catch a short value, and a short value does not
     * fail - it silently resolves the wrong rate. Two of the three seeded groups are seven characters
     * followed by three spaces, so a nine-or-fewer-character value matches no group row at all, the
     * lookup takes its documented not-found fallback, and an account whose own group carries a zero rate
     * accrues interest at the default group's rate instead. Recorded as {@code DL-297} in
     * {@code docs/decision-log.md}.
     */
    @Column(name = "acct_group_id", length = ACCT_GROUP_ID_WIDTH, nullable = false)
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

    /**
     * Whether the mapped image carried a negative overpunch on an all-zero {@code ACCT-CURR-BAL}.
     *
     * @return {@code true} only when the amount is zero and its image was negatively signed
     */
    public boolean isAcctCurrBalNegativeZero() {
        return acctCurrBalNegativeZero;
    }

    /**
     * Records whether the mapped image carried a negative overpunch on an all-zero {@code ACCT-CURR-BAL}.
     *
     * <p>Set by the record mapper that owns this layout, from the sign the image actually carried. It is
     * never derived from the amount, because the amount cannot express it.
     *
     * @param acctCurrBalNegativeZero the negative-zero bit the image carried
     */
    public void setAcctCurrBalNegativeZero(boolean acctCurrBalNegativeZero) {
        this.acctCurrBalNegativeZero = acctCurrBalNegativeZero;
    }

    public BigDecimal getAcctCreditLimit() {
        return acctCreditLimit;
    }

    public void setAcctCreditLimit(BigDecimal acctCreditLimit) {
        this.acctCreditLimit = acctCreditLimit;
    }

    /**
     * Whether the mapped image carried a negative overpunch on an all-zero {@code ACCT-CREDIT-LIMIT}.
     *
     * @return {@code true} only when the amount is zero and its image was negatively signed
     */
    public boolean isAcctCreditLimitNegativeZero() {
        return acctCreditLimitNegativeZero;
    }

    /**
     * Records whether the mapped image carried a negative overpunch on an all-zero {@code ACCT-CREDIT-LIMIT}.
     *
     * <p>Set by the record mapper that owns this layout, from the sign the image actually carried. It is
     * never derived from the amount, because the amount cannot express it.
     *
     * @param acctCreditLimitNegativeZero the negative-zero bit the image carried
     */
    public void setAcctCreditLimitNegativeZero(boolean acctCreditLimitNegativeZero) {
        this.acctCreditLimitNegativeZero = acctCreditLimitNegativeZero;
    }

    public BigDecimal getAcctCashCreditLimit() {
        return acctCashCreditLimit;
    }

    public void setAcctCashCreditLimit(BigDecimal acctCashCreditLimit) {
        this.acctCashCreditLimit = acctCashCreditLimit;
    }

    /**
     * Whether the mapped image carried a negative overpunch on an all-zero {@code ACCT-CASH-CREDIT-LIMIT}.
     *
     * @return {@code true} only when the amount is zero and its image was negatively signed
     */
    public boolean isAcctCashCreditLimitNegativeZero() {
        return acctCashCreditLimitNegativeZero;
    }

    /**
     * Records whether the mapped image carried a negative overpunch on an all-zero {@code ACCT-CASH-CREDIT-LIMIT}.
     *
     * <p>Set by the record mapper that owns this layout, from the sign the image actually carried. It is
     * never derived from the amount, because the amount cannot express it.
     *
     * @param acctCashCreditLimitNegativeZero the negative-zero bit the image carried
     */
    public void setAcctCashCreditLimitNegativeZero(boolean acctCashCreditLimitNegativeZero) {
        this.acctCashCreditLimitNegativeZero = acctCashCreditLimitNegativeZero;
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

    /**
     * Whether the mapped image carried a negative overpunch on an all-zero {@code ACCT-CURR-CYC-CREDIT}.
     *
     * @return {@code true} only when the amount is zero and its image was negatively signed
     */
    public boolean isAcctCurrCycCreditNegativeZero() {
        return acctCurrCycCreditNegativeZero;
    }

    /**
     * Records whether the mapped image carried a negative overpunch on an all-zero {@code ACCT-CURR-CYC-CREDIT}.
     *
     * <p>Set by the record mapper that owns this layout, from the sign the image actually carried. It is
     * never derived from the amount, because the amount cannot express it.
     *
     * @param acctCurrCycCreditNegativeZero the negative-zero bit the image carried
     */
    public void setAcctCurrCycCreditNegativeZero(boolean acctCurrCycCreditNegativeZero) {
        this.acctCurrCycCreditNegativeZero = acctCurrCycCreditNegativeZero;
    }

    public BigDecimal getAcctCurrCycDebit() {
        return acctCurrCycDebit;
    }

    public void setAcctCurrCycDebit(BigDecimal acctCurrCycDebit) {
        this.acctCurrCycDebit = acctCurrCycDebit;
    }

    /**
     * Whether the mapped image carried a negative overpunch on an all-zero {@code ACCT-CURR-CYC-DEBIT}.
     *
     * @return {@code true} only when the amount is zero and its image was negatively signed
     */
    public boolean isAcctCurrCycDebitNegativeZero() {
        return acctCurrCycDebitNegativeZero;
    }

    /**
     * Records whether the mapped image carried a negative overpunch on an all-zero {@code ACCT-CURR-CYC-DEBIT}.
     *
     * <p>Set by the record mapper that owns this layout, from the sign the image actually carried. It is
     * never derived from the amount, because the amount cannot express it.
     *
     * @param acctCurrCycDebitNegativeZero the negative-zero bit the image carried
     */
    public void setAcctCurrCycDebitNegativeZero(boolean acctCurrCycDebitNegativeZero) {
        this.acctCurrCycDebitNegativeZero = acctCurrCycDebitNegativeZero;
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
     * Refuses an account identifier that is not exactly eleven ASCII digits and normalises all five money
     * fields to scale two, immediately before the row is inserted or updated.
     *
     * <p>The identifier's digit class is contractual because the copybook declares the field as eleven
     * <em>digits</em>, and because a short value would split one account's identity between the relational
     * key and the eleven bytes of the record image it is written back into.
     *
     * <p><strong>Why a callback rather than the constructor or the setter.</strong> The persistence
     * provider hydrates a row by instantiating the entity and assigning its fields directly, so a
     * constructor guard is bypassed on every read while a callback sits on the one path every insert and
     * every update must take. It also leaves an instance built for an assertion, a fixture or an
     * intermediate calculation unrestricted - only one about to become a row is checked.
     *
     * <p>{@code V1__create_schema.sql} carries the same key rules a second time as check constraints, so
     * a bulk load or a migration script that never constructs an entity is refused as well.
     *
     * <p>An amount is <strong>normalised</strong> rather than refused: a value computed in a service,
     * parsed from a request or left over from a division carries whatever scale the arithmetic produced,
     * and an entity that stored it verbatim would let a repository write bypass the truncation policy the
     * whole estate depends on. {@code StoredValueRules} records why that policy truncates toward zero
     * rather than rounding, and why the constants it uses are restated there rather than imported from the
     * fixed-width codec.
     *
     * @throws IllegalArgumentException if an identifier is absent or is not exactly the width its layout
     *         declares, or if an amount is absent or beyond the declared precision
     */
    @PrePersist
    @PreUpdate
    void normalizeAndValidateBeforeWrite() {
        StoredValueRules.requireFixedWidthDigits(acctId, ACCT_ID_WIDTH, "acctId");
        StoredValueRules.requireFixedWidth(acctGroupId, ACCT_GROUP_ID_WIDTH, "acctGroupId");
        this.acctCurrBal = StoredValueRules.normalizedAmount(acctCurrBal, MONEY_PRECISION, "acctCurrBal");
        this.acctCreditLimit =
                StoredValueRules.normalizedAmount(acctCreditLimit, MONEY_PRECISION, "acctCreditLimit");
        this.acctCashCreditLimit = StoredValueRules.normalizedAmount(acctCashCreditLimit, MONEY_PRECISION,
                "acctCashCreditLimit");
        this.acctCurrCycCredit = StoredValueRules.normalizedAmount(acctCurrCycCredit, MONEY_PRECISION,
                "acctCurrCycCredit");
        this.acctCurrCycDebit = StoredValueRules.normalizedAmount(acctCurrCycDebit, MONEY_PRECISION,
                "acctCurrCycDebit");
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
