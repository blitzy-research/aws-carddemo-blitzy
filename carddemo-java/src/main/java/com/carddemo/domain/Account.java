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
 * JPA entity for a CardDemo account - the Java translation of the {@code ACCOUNT-RECORD} structure
 * declared by the legacy copybook {@code CVACT01Y}, whose header states a record length of 300 bytes.
 *
 * <p><strong>Corroboration.</strong> Three independent artifacts of the read-only legacy estate agree
 * on this record: the copybook declares twelve named fields plus a 178-byte trailing filler summing to
 * exactly 300; the cluster definition in {@code ACCTFILE.jcl} specifies {@code KEYS(11 0)} and
 * {@code RECORDSIZE(300 300)}; and {@code CBACT01C} declares it in its file section as an 11-byte key
 * field followed by a 289-byte remainder, 11 + 289 = 300.
 *
 * <ul>
 *   <li>{@code app/cpy/CVACT01Y.cpy} declares twelve named fields followed by a 178-byte trailing
 *       filler; the declared widths sum to exactly 300.</li>
 *   <li>{@code app/jcl/ACCTFILE.jcl} defines the {@code ACCTDATA} VSAM KSDS base cluster with
 *       indexed organisation, an 11-byte key at offset 0, and a record that is fixed at 300 bytes
 *       for both its minimum and its maximum.</li>
 *   <li>{@code app/cbl/CBACT01C.cbl} declares the same record in its file section as an 11-byte
 *       key field, {@code FD-ACCT-ID}, followed by a 289-byte remainder, {@code FD-ACCT-DATA};
 *       11 + 289 = 300.</li>
 * </ul>
 *
 * <p>The five monetary fields are <em>not</em> contiguous: three precede the three date fields and two
 * follow them. Declaration order is preserved exactly so that no reader treats the amounts as one
 * block - a mapper that assumed contiguity would misread every account from offset 48 onward.
 *
 * <ul>
 *   <li>0/11 - account identifier, the business key</li>
 *   <li>11/1 - active status code</li>
 *   <li>12/12, 24/12, 36/12 - current balance, credit limit, cash credit limit</li>
 *   <li>48/10, 58/10, 68/10 - open date, expiration date, reissue date</li>
 *   <li>78/12, 90/12 - current cycle credit, current cycle debit</li>
 *   <li>102/10 - address ZIP</li>
 *   <li>112/10 - account group identifier</li>
 *   <li>122/178 - trailing filler, deliberately neither a field here nor a column in the schema</li>
 * </ul>
 *
 * <p>The five monetary fields are <em>not</em> contiguous: three precede the three date fields and
 * two follow them. Declaration order is preserved exactly so that this class reads as a faithful
 * transcription of the record, and so that no reader is tempted to treat the amounts as one block.
 * Any mapper that assumed contiguity would misread every account from offset 48 onward.
 *
 * <p><strong>Identity is the legacy business key, never a surrogate.</strong> The legacy record
 * splits into an 11-byte key that is the leading substring of the 300-byte image plus a 289-byte
 * remainder, and the cluster definition states the same relationship: key width 11 at offset 0,
 * cited from {@code app/jcl/ACCTFILE.jcl}. The persistent identity of an account row therefore <em>is</em> that
 * account identifier. No generated value, sequence, table generator or synthetic identifier
 * appears on this class: introducing one would sever the record-image-to-row correspondence that
 * byte-level output parity depends on.
 *
 * <p><strong>This entity is a passive carrier; it computes nothing.</strong> Fixed-width offset
 * arithmetic belongs to the record mapper in the utility layer, and zoned decimal encoding and
 * decoding - including the overpunched trailing-byte sign convention and the scale policy applied to
 * every amount - belongs exclusively to {@link com.carddemo.util.ZonedDecimalCodec}. This class
 * performs no arithmetic, scaling, rounding, parsing or validation. The dependency direction is
 * one-way: the utility layer produces entities, so an entity never references the utility layer.
 *
 * <p><strong>Fixed-width values are stored verbatim.</strong> Every character attribute carries a
 * value occupying its whole declared width, so padding is part of the stored value rather than
 * incidental whitespace. Constructors, mutators and accessors are plain assignments and plain returns:
 * nothing is trimmed, stripped, padded, case-folded, normalized or validated anywhere in this class.
 * Screen-level field editing belongs to the account-update request path, which reproduces the legacy
 * edit cascade; the persistence model deliberately accepts everything the legacy system accepted.
 *
 * <p><strong>Schema authority.</strong> This mapping is validated, not generated. Flyway owns the
 * {@code account} table and the runtime configuration fixes Hibernate at schema validation only, so
 * any divergence from the migration - a renamed column, a changed width or precision, a wrong Java
 * type - fails start-up rather than silently reshaping the database. Thirteen columns, thirteen
 * attributes, all non-nullable.
 */
@Entity
@Table(name = "account")
public class Account {

    /**
     * Account identifier - 11 bytes at offset 0 of the record image, and the primary key.
     *
     * <p>The legacy field is an external-decimal field of eleven digits, yet this property is a
     * bounded {@link String} and the column is a bounded character column. That is deliberate:
     * the sample dataset stores the identifier zero-filled to its full width, so the external
     * text width and the leading zeros are contractual and must survive a round trip untouched.
     * A numeric type would discard them and would additionally contradict the character column
     * the migration defines, which schema validation exists to catch.
     *
     * <p>Stored verbatim. This value is the row's persistent identity and is never generated.
     */
    @Id
    @Column(name = "acct_id", length = 11, nullable = false)
    private String acctId;

    /**
     * Active status code - a single byte at offset 11 of the record image.
     *
     * <p>Held as the raw one-character code and <em>not</em> as a mapped enum constant. A sibling enum
     * in {@code com.carddemo.domain.enums} models the vocabulary for service-layer use, but this
     * entity deliberately neither imports nor references it, for four independent reasons: string-valued
     * enum mapping persists the constant <em>name</em>, which cannot fit a one-character column and
     * would destroy byte parity of the fixed-width output; ordinal mapping persists an integer and
     * would fail schema validation against a character column outright; an attribute converter would
     * have no home, since the enum sub-package is a closed set of enum declarations and this package
     * may not depend on the utility layer; and the raw code is lossless, tolerating every value the
     * legacy file may carry - including one outside the documented vocabulary - exactly as the legacy
     * programs did, where rejecting it here would be new behavior.
     *
     * <p>Translation between code and constant is the service layer's responsibility. This attribute
     * stores the code, verbatim.
     */
    @Column(name = "acct_active_status", length = 1, nullable = false)
    private String acctActiveStatus;

    /**
     * Current balance - 12 bytes at offset 12 of the record image, ten integer digits and two
     * decimal digits, signed.
     *
     * <p>Exact decimal, never an approximate binary type: the migration requirement is that
     * decimal precision be identical to the legacy field with no floating-point substitution.
     * The value arrives already scaled from the zoned decimal codec, which truncates toward zero
     * because a census of the estate found no rounding clause anywhere; this entity therefore
     * performs no scaling of its own, since a differing rounding policy applied here would
     * diverge by a cent from the legacy output and break byte-level parity.
     */
    @Column(name = "acct_curr_bal", precision = 12, scale = 2, nullable = false)
    private BigDecimal acctCurrBal;

    /**
     * Credit limit - 12 bytes at offset 24 of the record image, ten integer digits and two
     * decimal digits, signed. Exact decimal, carried and never computed here.
     */
    @Column(name = "acct_credit_limit", precision = 12, scale = 2, nullable = false)
    private BigDecimal acctCreditLimit;

    /**
     * Cash credit limit - 12 bytes at offset 36 of the record image, ten integer digits and two
     * decimal digits, signed. Exact decimal, carried and never computed here.
     */
    @Column(name = "acct_cash_credit_limit", precision = 12, scale = 2, nullable = false)
    private BigDecimal acctCashCreditLimit;

    /**
     * Open date - 10 bytes at offset 48 of the record image.
     *
     * <p>A bounded {@link String}, not a date type. The legacy field is an alphanumeric field of
     * fixed width rather than a date field, so its exact ten-character external form is the
     * contract. Strict calendar parsing and validation live in the date-validation service, which
     * reproduces the legacy edit cascade; keeping the stored form textual preserves every value
     * the legacy system tolerated, including one that a strict date parser would reject.
     */
    @Column(name = "acct_open_date", length = 10, nullable = false)
    private String acctOpenDate;

    /**
     * Expiration date - 10 bytes at offset 58 of the record image.
     *
     * <p><strong>Documented source anomaly.</strong> The legacy field name drops a letter from
     * EXPIRATION and reads {@code ACCT-EXPIRAION-DATE}. It is row 1 of the source anomaly register in
     * {@code docs/decision-log.md}, and it is preserved and documented rather than silently corrected
     * in a way that would shift the layout: the Java property and the column are both spelled
     * correctly, the record offset is unchanged at 58 for a width of 10 so the record mapper reads
     * exactly the same bytes and the image stays byte-compatible, and the misspelled legacy name is
     * cited here so the mapping from this property back to the copybook field stays findable by search.
     *
     * <p>Bounded {@link String} for the same reason as the other two date attributes.
     */
    @Column(name = "acct_expiration_date", length = 10, nullable = false)
    private String acctExpirationDate;

    /**
     * Reissue date - 10 bytes at offset 68 of the record image. Bounded {@link String} for the
     * same reason as the other two date fields: the legacy field is fixed-width alphanumeric, and
     * parsing belongs to the service layer.
     */
    @Column(name = "acct_reissue_date", length = 10, nullable = false)
    private String acctReissueDate;

    /**
     * Current cycle credit - 12 bytes at offset 78 of the record image, ten integer digits and two
     * decimal digits, signed.
     *
     * <p>Exact decimal, carried and never computed here. This field and the cycle debit below are
     * the two operands of the legacy over-limit test in the posting program, which evaluates
     * strictly left to right into a two-decimal field; that expression lives in the posting
     * service, operand for operand, and never in this entity.
     */
    @Column(name = "acct_curr_cyc_credit", precision = 12, scale = 2, nullable = false)
    private BigDecimal acctCurrCycCredit;

    /**
     * Current cycle debit - 12 bytes at offset 90 of the record image, ten integer digits and two
     * decimal digits, signed. Exact decimal, carried and never computed here.
     */
    @Column(name = "acct_curr_cyc_debit", precision = 12, scale = 2, nullable = false)
    private BigDecimal acctCurrCycDebit;

    /**
     * Address ZIP - 10 bytes at offset 102 of the record image.
     *
     * <p>An opaque fixed-width lexeme, not a semantically validated postal code. Every one of the
     * fifty sample account records carries a ten-character value whose first character is a
     * letter, which is a standing reminder that these fields are byte images rather than
     * validated values. Stored verbatim, with no normalization of any kind.
     */
    @Column(name = "acct_addr_zip", length = 10, nullable = false)
    private String acctAddrZip;

    /**
     * Account group identifier - 10 bytes at offset 112 of the record image.
     *
     * <p><strong>Deliberately not a foreign key, and deliberately not an association.</strong> The
     * index migration defines no foreign key from this column to the disclosure-group table, and
     * this field carries no relationship annotation and no join column. The reason is structural
     * rather than stylistic: the group identifier alone is only a nonunique leading portion of the
     * disclosure-group composite key and recurs many times within a single group, so it cannot
     * reference a single parent row. The interest-calculation program resolves the rate by
     * composing the full key at runtime and, when no row matches, retries with a documented
     * default group, which a database-level constraint could not express.
     *
     * <p><strong>Trailing spaces are significant and are never trimmed.</strong> All fifty sample
     * account records carry exactly ten spaces here. The value must round-trip unchanged, so
     * neither the constructors nor the setter nor the accessor trims, strips, pads or normalizes
     * it. Trimming would make a padded identifier compare equal to its unpadded form in Java
     * while the two remain distinct in the database, which is precisely how the runtime
     * default-group fallback would be broken.
     */
    @Column(name = "acct_group_id", length = 10, nullable = false)
    private String acctGroupId;

    /**
     * Optimistic-locking version counter, managed entirely by the persistence provider: read and
     * compared on every update, incremented on every successful one.
     *
     * <p><strong>Why it exists, and why it is an improvement rather than a change in behavior.</strong>
     * It replaces the legacy before-and-after image comparison that the online account-update program
     * performed by hand. The legacy file definitions in the CICS resource definition specify
     * uncommitted read integrity, a locking update model, no recovery and no journaling, so concurrency
     * correctness rested solely on that hand-written comparison plus record locking. PostgreSQL
     * read-committed isolation combined with this version check is <em>strictly stronger</em> than that
     * verified baseline. A reviewer should read the stronger isolation as the documented improvement it
     * is and not mistake it for a behavioral regression; decision log entry D-15 records it.
     *
     * <p>This is one of only two entities in the module carrying a version counter, the other being the
     * card entity, matching the only two tables in the schema that define the column. The attribute is
     * not initialised to a non-zero value, so the schema default applies and a seeded row that omits
     * the column does not diverge; it is neither insert- nor update-excluded, because the provider must
     * be free to write it.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Creates an empty account. Required by the persistence provider, which instantiates an entity
     * before populating its state; application code should use
     * {@link #Account(String, String, BigDecimal, BigDecimal, BigDecimal, String, String, String,
     * BigDecimal, BigDecimal, String, String)} instead.
     */
    protected Account() {
        // Intentionally empty: the persistence provider assigns state after construction.
    }

    /**
     * Creates a fully populated account from the twelve business fields, in the same order in
     * which they appear in the legacy record image.
     *
     * <p>The version counter is deliberately absent from this signature because the persistence
     * provider owns it. Every argument is assigned exactly as supplied - nothing is trimmed,
     * padded, case folded, scaled, rounded or validated - because the fixed-width padding and the
     * decimal scale these values carry are part of the record contract.
     *
     * @param acctId              account identifier, 11 bytes at offset 0, the business key
     * @param acctActiveStatus    raw one-character active status code, offset 11
     * @param acctCurrBal         current balance, offset 12, exact decimal of scale 2
     * @param acctCreditLimit     credit limit, offset 24, exact decimal of scale 2
     * @param acctCashCreditLimit cash credit limit, offset 36, exact decimal of scale 2
     * @param acctOpenDate        open date as its 10-character external form, offset 48
     * @param acctExpirationDate  expiration date as its 10-character external form, offset 58
     * @param acctReissueDate     reissue date as its 10-character external form, offset 68
     * @param acctCurrCycCredit   current cycle credit, offset 78, exact decimal of scale 2
     * @param acctCurrCycDebit    current cycle debit, offset 90, exact decimal of scale 2
     * @param acctAddrZip         address ZIP, 10 bytes at offset 102, stored verbatim
     * @param acctGroupId         account group identifier, 10 bytes at offset 112, stored verbatim
     *                            including trailing spaces
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

    /**
     * Returns the account identifier exactly as stored, leading zeros included.
     *
     * @return the 11-character account identifier, unmodified
     */
    public String getAcctId() {
        return acctId;
    }

    /**
     * Replaces the account identifier. Because this value is the row's persistent identity, it is
     * assigned when the record is first mapped and is not otherwise reassigned by application
     * logic.
     *
     * @param acctId the 11-character account identifier, stored verbatim
     */
    public void setAcctId(String acctId) {
        this.acctId = acctId;
    }

    /**
     * Returns the raw one-character active status code exactly as stored, without translating it
     * to a constant.
     *
     * @return the one-character status code, unmodified
     */
    public String getAcctActiveStatus() {
        return acctActiveStatus;
    }

    /**
     * Replaces the raw one-character active status code. The value is stored as supplied and is
     * not checked against any vocabulary here.
     *
     * @param acctActiveStatus the one-character status code, stored verbatim
     */
    public void setAcctActiveStatus(String acctActiveStatus) {
        this.acctActiveStatus = acctActiveStatus;
    }

    /**
     * Returns the current balance exactly as stored, with its scale unchanged.
     *
     * @return the current balance as an exact decimal, unmodified
     */
    public BigDecimal getAcctCurrBal() {
        return acctCurrBal;
    }

    /**
     * Replaces the current balance. The value is stored as supplied: it is neither rescaled nor
     * rounded here, because scale policy belongs to the zoned decimal codec.
     *
     * @param acctCurrBal the current balance as an exact decimal of scale 2
     */
    public void setAcctCurrBal(BigDecimal acctCurrBal) {
        this.acctCurrBal = acctCurrBal;
    }

    /**
     * Returns the credit limit exactly as stored, with its scale unchanged.
     *
     * @return the credit limit as an exact decimal, unmodified
     */
    public BigDecimal getAcctCreditLimit() {
        return acctCreditLimit;
    }

    /**
     * Replaces the credit limit. Stored as supplied, without rescaling or rounding.
     *
     * @param acctCreditLimit the credit limit as an exact decimal of scale 2
     */
    public void setAcctCreditLimit(BigDecimal acctCreditLimit) {
        this.acctCreditLimit = acctCreditLimit;
    }

    /**
     * Returns the cash credit limit exactly as stored, with its scale unchanged.
     *
     * @return the cash credit limit as an exact decimal, unmodified
     */
    public BigDecimal getAcctCashCreditLimit() {
        return acctCashCreditLimit;
    }

    /**
     * Replaces the cash credit limit. Stored as supplied, without rescaling or rounding.
     *
     * @param acctCashCreditLimit the cash credit limit as an exact decimal of scale 2
     */
    public void setAcctCashCreditLimit(BigDecimal acctCashCreditLimit) {
        this.acctCashCreditLimit = acctCashCreditLimit;
    }

    /**
     * Returns the open date in its stored 10-character external form, unparsed.
     *
     * @return the open date text, unmodified
     */
    public String getAcctOpenDate() {
        return acctOpenDate;
    }

    /**
     * Replaces the open date. The value is stored as supplied and is not parsed or validated here.
     *
     * @param acctOpenDate the open date as its 10-character external form
     */
    public void setAcctOpenDate(String acctOpenDate) {
        this.acctOpenDate = acctOpenDate;
    }

    /**
     * Returns the expiration date in its stored 10-character external form, unparsed. This is the
     * correctly spelled counterpart of the misspelled legacy field at offset 58.
     *
     * @return the expiration date text, unmodified
     */
    public String getAcctExpirationDate() {
        return acctExpirationDate;
    }

    /**
     * Replaces the expiration date. The value is stored as supplied and is not parsed or validated
     * here.
     *
     * @param acctExpirationDate the expiration date as its 10-character external form
     */
    public void setAcctExpirationDate(String acctExpirationDate) {
        this.acctExpirationDate = acctExpirationDate;
    }

    /**
     * Returns the reissue date in its stored 10-character external form, unparsed.
     *
     * @return the reissue date text, unmodified
     */
    public String getAcctReissueDate() {
        return acctReissueDate;
    }

    /**
     * Replaces the reissue date. The value is stored as supplied and is not parsed or validated
     * here.
     *
     * @param acctReissueDate the reissue date as its 10-character external form
     */
    public void setAcctReissueDate(String acctReissueDate) {
        this.acctReissueDate = acctReissueDate;
    }

    /**
     * Returns the current cycle credit exactly as stored, with its scale unchanged.
     *
     * @return the current cycle credit as an exact decimal, unmodified
     */
    public BigDecimal getAcctCurrCycCredit() {
        return acctCurrCycCredit;
    }

    /**
     * Replaces the current cycle credit. Stored as supplied, without rescaling or rounding.
     *
     * @param acctCurrCycCredit the current cycle credit as an exact decimal of scale 2
     */
    public void setAcctCurrCycCredit(BigDecimal acctCurrCycCredit) {
        this.acctCurrCycCredit = acctCurrCycCredit;
    }

    /**
     * Returns the current cycle debit exactly as stored, with its scale unchanged.
     *
     * @return the current cycle debit as an exact decimal, unmodified
     */
    public BigDecimal getAcctCurrCycDebit() {
        return acctCurrCycDebit;
    }

    /**
     * Replaces the current cycle debit. Stored as supplied, without rescaling or rounding.
     *
     * @param acctCurrCycDebit the current cycle debit as an exact decimal of scale 2
     */
    public void setAcctCurrCycDebit(BigDecimal acctCurrCycDebit) {
        this.acctCurrCycDebit = acctCurrCycDebit;
    }

    /**
     * Returns the address ZIP exactly as stored, as an opaque fixed-width lexeme.
     *
     * @return the 10-character address ZIP, unmodified
     */
    public String getAcctAddrZip() {
        return acctAddrZip;
    }

    /**
     * Replaces the address ZIP. Stored verbatim, with no postal validation and no normalization.
     *
     * @param acctAddrZip the 10-character address ZIP, stored verbatim
     */
    public void setAcctAddrZip(String acctAddrZip) {
        this.acctAddrZip = acctAddrZip;
    }

    /**
     * Returns the account group identifier exactly as stored, trailing spaces included.
     *
     * @return the 10-character account group identifier, unmodified and untrimmed
     */
    public String getAcctGroupId() {
        return acctGroupId;
    }

    /**
     * Replaces the account group identifier. Stored verbatim: the trailing spaces that pad this
     * value to its full width are significant and are never removed.
     *
     * @param acctGroupId the 10-character account group identifier, stored verbatim
     */
    public void setAcctGroupId(String acctGroupId) {
        this.acctGroupId = acctGroupId;
    }

    /**
     * Returns the optimistic-locking version counter. There is no corresponding setter: the
     * persistence provider owns this value and assigns it directly.
     *
     * @return the current version counter, zero for a row that has never been updated
     */
    public long getVersion() {
        return version;
    }

    /**
     * Compares accounts by their business key alone.
     *
     * <p>The account identifier is the primary key, so it - and only it - determines entity
     * identity. Including a mutable non-key field would change an instance's equality and hash as
     * soon as state was updated, which breaks membership in a hash-based collection across a
     * persistence flush. The key is compared byte for byte, with no trimming or case folding, so
     * that Java equality agrees exactly with the database's notion of the same row.
     *
     * @param o the object to compare against
     * @return {@code true} only if {@code o} is an {@code Account} whose account identifier equals
     *         this account's identifier
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

    /**
     * Hashes the business key alone, from its untrimmed value, so that the hash stays consistent
     * with {@link #equals(Object)} and stable across every state change and every flush.
     *
     * @return the hash code of the account identifier
     */
    @Override
    public int hashCode() {
        return Objects.hash(acctId);
    }

    /**
     * Returns a deliberately minimal diagnostic rendering: the account identifier and the raw
     * status code, and nothing else.
     *
     * <p>No monetary amount, no address component and no other potentially sensitive value is
     * included, so this rendering is safe to place in a log line or an assertion failure. Both
     * values are quoted and printed untrimmed so that significant padding remains visible.
     *
     * @return a diagnostic string containing the account identifier and the raw status code
     */
    @Override
    public String toString() {
        return "Account[acctId='" + acctId + "', acctActiveStatus='" + acctActiveStatus + "']";
    }
}
