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

import java.util.Base64;
import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Customer master entity, mapping the 500-byte customer record of {@code app/cpy/CVCUS01Y.cpy} to
 * eighteen persisted attributes in record order; the 9-byte key and the fixed width are corroborated by
 * {@code app/jcl/CUSTFILE.jcl}. The alternate copybook view {@code app/cpy/CUSTREC.cpy} describes the same
 * 500 bytes with one field spelled differently and is therefore the same entity, not a second one. The
 * 168-byte trailing filler is not persisted, and no offset arithmetic happens here - that belongs to the
 * record mapper.
 *
 * <p>Identity is the customer identifier itself, never a surrogate, so the record image and the table row
 * stay in correspondence. Every attribute is a {@link String}, including the digit-only ones, because
 * leading zeros and external widths are contractual. Column names follow the migration exactly, including
 * its non-uniform prefixing, since the mapping is validated against the schema at start-up. Values are
 * stored verbatim: nothing is trimmed, padded, folded, parsed or validated here, and there is no version
 * attribute, no association and no Bean Validation constraint on this class.
 *
 * <p><strong>Eighteen persisted fields, in contractual record order.</strong> The order below is
 * fixed by the record image itself and is not a stylistic choice. Offsets are zero-based and are
 * quoted here only as documentation of provenance; no offset arithmetic is performed anywhere in
 * this class.
 *
 * <ul>
 *   <li>{@code custId} - offset 0, width 9 - business key, external decimal, column
 *       {@code cust_id}</li>
 *   <li>{@code firstName} - offset 9, width 25 - column {@code first_name}</li>
 *   <li>{@code middleName} - offset 34, width 25 - column {@code middle_name}</li>
 *   <li>{@code lastName} - offset 59, width 25 - column {@code last_name}</li>
 *   <li>{@code addrLine1} - offset 84, width 50 - column {@code addr_line_1}</li>
 *   <li>{@code addrLine2} - offset 134, width 50 - column {@code addr_line_2}</li>
 *   <li>{@code addrLine3} - offset 184, width 50 - column {@code addr_line_3}</li>
 *   <li>{@code addrStateCd} - offset 234, width 2 - column {@code addr_state_cd}</li>
 *   <li>{@code addrCountryCd} - offset 236, width 3 - column {@code addr_country_cd}</li>
 *   <li>{@code addrZip} - offset 239, width 10 - column {@code addr_zip}</li>
 *   <li>{@code phoneNum1} - offset 249, width 15 - column {@code phone_num_1}</li>
 *   <li>{@code phoneNum2} - offset 264, width 15 - column {@code phone_num_2}</li>
 *   <li>{@code custSsn} - offset 279, width 9 in the record - column {@code cust_ssn}, widened to
 *       hold ciphertext; see the security note below</li>
 *   <li>{@code govtIssuedId} - offset 288, width 20 in the record - column
 *       {@code govt_issued_id}, widened to hold ciphertext; see the security note below</li>
 *   <li>{@code custDob} - offset 308, width 10 - column {@code cust_dob}</li>
 *   <li>{@code eftAccountId} - offset 318, width 10 - column {@code eft_account_id}</li>
 *   <li>{@code priCardHolderInd} - offset 328, width 1 - column {@code pri_card_holder_ind}</li>
 *   <li>{@code ficoCreditScore} - offset 329, width 3 - column {@code fico_credit_score}</li>
 * </ul>
 *
 * <p>Mapped bytes end at offset 332, where a 168-byte trailing filler begins. That filler is
 * deliberately <em>not</em> a field here and <em>not</em> a column: 332 + 168 = 500 accounts for
 * the whole record, and the padding is consumed only by the fixed-width record mapper in the
 * utility layer, {@code CustomerRecordMapper}. All offset knowledge lives in that mapper; this
 * entity carries column widths only.
 *
 * <p><strong>The alternate copybook view is the same entity, not a second one.</strong> The estate
 * contains a second 500-byte customer copybook, {@code CUSTREC}, which the statement-generation
 * program {@code CBSTM03A} includes instead. Compared field by field the two declare the same record
 * name, the same eighteen fields with the same picture clauses in the same order and the same
 * 168-byte filler, differing only in the spelling of the date-of-birth field - both spellings denote
 * the same 10 bytes at offset 308. One attribute and one column therefore serve both, and no second
 * entity, table, secondary-table mapping or alternate class exists.
 *
 * <p><strong>The identifier is the legacy business key, never a surrogate.</strong> {@code KEYS(9 0)}
 * places the key at offset 0 as the leading substring of the record image, so a customer's
 * persistent identity <em>is</em> its 9-character identifier. No generated value, sequence or table
 * generator is declared: a surrogate would sever the record-image-to-row correspondence that
 * byte-level output parity depends on.
 *
 * <p><strong>Every attribute is a {@link String}, including the digit-only ones.</strong> Three
 * fields are external decimal in the copybook, yet all three map to bounded character columns
 * because the external representation is part of the contract: an identifier must stay
 * {@code "000000001"} rather than collapsing to {@code 1}. Nothing here is trimmed, padded,
 * case-folded, normalized, validated or encrypted, in a constructor or a mutator. Those three carry
 * their leading zeros in the column as well as in the record - a right trim is a no-op on a
 * zero-filled value - so for them the stored form and the record form are the same string.
 *
 * <p><strong>Column names are not uniformly prefixed, and must not be regularized.</strong> Only
 * {@code cust_id}, {@code cust_ssn} and {@code cust_dob} carry a {@code cust_} prefix. The names are
 * transcribed from the schema migration that owns this table; Hibernate validates against that
 * schema rather than generating it, so renaming a column here to look consistent aborts start-up.
 *
 * <p><strong>No version attribute, no associations and no Bean Validation.</strong> Only the account
 * and card tables carry a version column, so declaring one here would name a column the schema does
 * not have. The card cross-reference table's foreign key targets {@code customer.cust_id} and that
 * relationship is enforced by the database rather than modelled as an object graph, because scalar
 * keys mirror the record image and an association would change the column name the provider expects.
 * Validation constraints are absent for the measured reason given on the credit-score attribute, and
 * the two attributes the legacy update path decorates without ever editing - the middle name and the
 * second address line - are likewise unconstrained so that input the legacy system accepts is not
 * rejected here.
 *
 * <p><strong>Values are stored verbatim; nothing is trimmed, padded or folded here.</strong> Neither
 * the constructors nor the mutators trim, strip, pad, case-fold, normalize or reformat anything,
 * because any such transformation would break byte-level parity with the legacy output. The corollary
 * is that padding is decided by the callers on either side of this class and never by this class, and
 * the two callers decide differently on purpose. In the 500-byte record image every field occupies its
 * whole declared span - the given name is twenty-five bytes in every one of the fifty sample records,
 * the primary telephone number fifteen, the postal code ten - and the placement primitive the record
 * mapper writes through guarantees it, left-justifying a character value and then writing the trailing
 * pad to the full field width explicitly. In the relational row, ordinary display text is stored
 * right-trimmed: the reference seed inserts the given name at its own length, the telephone number at
 * thirteen characters and a five-digit postal code as five. Nothing is lost either way, because placing
 * a trimmed value back through the mapper reproduces the identical bytes. What would break parity is
 * this class converting between the two forms behind the caller's back, which is why it does not.
 *
 * <p><strong>Two attributes are the exception, and the exception is a guard rather than a
 * transformation.</strong> The two regulated identifiers - the national identifier and the
 * government-issued identifier - are stored as authenticated ciphertext rather than as the cleartext
 * the legacy record carried, so that a database connection, a backup file or an operational query
 * cannot read them. This entity still transforms nothing: it neither encrypts nor decrypts, because
 * that would require the utility and service layers this layer does not depend on. What it does
 * instead is refuse. Both the constructor and the two mutators verify that the value handed to them
 * already carries the structural shape of the module's protected-value envelope, and reject anything
 * else, so cleartext cannot reach the persistence boundary through application code even by mistake.
 * Producing and reading those envelopes is the responsibility of the field-encryption service, whose
 * key is resolved from the environment with no fallback. This is a deliberate divergence from
 * at-rest faithfulness, it changes no record image and no output byte, and it is recorded in
 * {@code docs/decision-log.md}.
 *
 * <p>Both regulated attributes are treated identically, nullability included: each admits an
 * envelope-shaped value or {@code null}, and nothing else. {@code null} means "no protected value has
 * been supplied", which is the only honest thing static SQL can say about a field it cannot encrypt -
 * a seed migration would have to commit key material to produce an envelope. The reference seed
 * therefore leaves both columns null and the encryption path supplies them at run time; requiring a
 * value instead would not conjure a protected one, it would only invite an unprotected one.
 *
 * <p><strong>No optimistic-locking attribute.</strong> Only the account and card tables carry a
 * version column in this schema; the customer table has none, so no version attribute is declared
 * here. Adding one would introduce a column the schema does not have and would fail validation.
 *
 * <p><strong>No associations.</strong> This table is the referenced parent of the card
 * cross-reference table, whose foreign key targets {@code customer.cust_id}. That relationship is
 * enforced by the database as a constraint created by a schema migration, and is deliberately not
 * modelled as an object graph here: no requirement asks for navigation, an association would alter
 * the column name the provider expects, and scalar keys mirror the record image faithfully.
 *
 * <p><strong>Bean Validation is deliberately absent from this class.</strong> See the note on the
 * credit-score attribute for the measured reason; the two attributes the legacy update path
 * decorates for display without ever editing - the middle name and the second address line - are
 * likewise left unconstrained, so that input the legacy system accepts is not rejected here.
 *
 * <p>Instances are mutable through their mutators because the persistence provider requires
 * property access to a managed entity. Equality is defined on the identifier alone, so an instance
 * remains stable in a hash-based collection across a flush that changes a non-key attribute.
 *
 * <p><strong>Provenance.</strong> Translated from the read-only legacy estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19, which appears in the trailer comment of
 * the source copybook. No legacy source text is reproduced in this module; members, field names,
 * widths, offsets and codes are cited by reference only.
 */
@Entity
@Table(name = "customer")
public class Customer {

    /**
     * Width of the customer identifier in characters: 9, declared by the copybook as nine digits.
     *
     * <p>Named so that the column declaration and the persistence-time rule read the one figure rather
     * than two copies of it.
     */
    static final int CUST_ID_WIDTH = 9;

    /**
     * Structural marker that opens every protected value this entity accepts. Declared authoritatively by
     * the utility-layer codec and duplicated here because the domain layer may not depend on that layer;
     * the duplication is held in step by a unit test that seals a value with the codec and requires this
     * entity to accept it, and seals another under a different marker and requires it to be refused.
     */
    private static final String PROTECTED_VALUE_PREFIX = "ENC1:";

    /**
     * Smallest number of decoded bytes a protected value can carry: initialisation vector plus
     * authentication tag.
     */
    private static final int PROTECTED_VALUE_MINIMUM_BYTES = 28;

    @Id
    @Column(name = "cust_id", length = CUST_ID_WIDTH, nullable = false)
    private String custId;

    /**
     * Given name - 25 bytes at offset 9 of the record image. Stored exactly as handed over, whether
     * that is the record's padded twenty-five characters or the trimmed form the seed inserts.
     */
    @Column(name = "first_name", length = 25, nullable = false)
    private String firstName;

    /**
     * Deliberately unconstrained: the legacy account-update path decorates this field for error display but
     * codes no edit for it, so a validation constraint here would reject input the legacy system accepts.
     */
    @Column(name = "middle_name", length = 25, nullable = false)
    private String middleName;

    @Column(name = "last_name", length = 25, nullable = false)
    private String lastName;

    @Column(name = "addr_line_1", length = 50, nullable = false)
    private String addrLine1;

    /**
     * Deliberately unconstrained for the same reason as the middle name: decorated for error display by the
     * legacy update path, with no edit coded.
     */
    @Column(name = "addr_line_2", length = 50, nullable = false)
    private String addrLine2;

    @Column(name = "addr_line_3", length = 50, nullable = false)
    private String addrLine3;

    /**
     * Membership of the permitted state codes, and of the permitted state-and-postal-prefix combinations, is
     * checked by the validation-lookup service against externalized reference data, not here.
     */
    @Column(name = "addr_state_cd", length = 2, nullable = false)
    private String addrStateCd;

    @Column(name = "addr_country_cd", length = 3, nullable = false)
    private String addrCountryCd;

    @Column(name = "addr_zip", length = 10, nullable = false)
    private String addrZip;

    /**
     * The legacy edit decomposes a number and checks its area code against the permitted numbering-plan
     * set; that belongs to the validation-lookup service, and this attribute simply carries the characters.
     */
    @Column(name = "phone_num_1", length = 15, nullable = false)
    private String phoneNum1;

    @Column(name = "phone_num_2", length = 15, nullable = false)
    private String phoneNum2;

    /**
     * National identifier - 9 bytes at offset 279 of the record image, mapped to a 255-character
     * nullable column.
     *
     * <p><strong>This is one of exactly two nullable columns in the schema, the other being the
     * government-issued identifier below.</strong> Those two are nullable for the same reason - both
     * hold application-produced ciphertext, and no static artifact can produce ciphertext without
     * committing key material - and no other column in any of the eleven application tables permits a
     * null. A {@code null} must round-trip as {@code null} and must never be rewritten to an empty
     * string or to the literal text {@code "null"}.
     *
     * <p><strong>This entity performs no encryption or decryption, and it will not accept
     * cleartext.</strong> The column is far wider than the legacy field because it stores ciphertext
     * rather than the cleartext value. The transformation itself happens outside this package: the
     * domain layer may not depend on the utility or service layers, so no attribute converter is
     * declared here. An automatically applied converter was considered and rejected outright, because
     * auto-application on a string attribute type would silently capture every string attribute in
     * the module rather than this one field. Encryption, decryption and null tolerance are therefore
     * implemented in the utility and service layers, keyed from configuration resolved at run time.
     * No key, salt, algorithm name or cleartext value appears anywhere in this class.
     *
     * <p>What this class does contribute is the fail-closed half of that arrangement. Every write
     * path - the eighteen-argument constructor and the mutator - passes through
     * {@link #requireProtectedValue(String, String)}, which admits only {@code null} or a
     * value carrying the module's protected-value envelope shape. A nine-digit cleartext identifier
     * cannot satisfy that shape, so it is rejected rather than stored. The check is written in terms
     * of the platform library alone, which is why it can live here without the domain layer acquiring
     * a dependency it is not permitted to have.
     */
    @Column(name = "cust_ssn", length = 255, nullable = true)
    private String custSsn;

    /**
     * Government-issued identifier, held as application-produced ciphertext - 20 bytes at offset 288
     * of the record image, mapped to a 255-character column.
     *
     * <p>The legacy record carries this as twenty cleartext characters. It is a national identity
     * document number and is regulated in the same way as the national identifier above, so it is
     * protected in the same way, by the same service, under the same key, and the column is widened
     * for the same reason. The two fields differ in exactly one respect: this column is
     * {@code NOT NULL}, so a stored row always carries a value.
     *
     * <p>{@code NOT NULL} is not itself the guard - a {@code VARCHAR(255)} accepts cleartext as
     * readily as ciphertext. The guard is this boundary. As with the national identifier, this entity
     * neither encrypts nor decrypts - it refuses. Every write path passes through
     * {@link #requireProtectedValue(String, String)}, so only a well-formed protected value can be
     * stored, and twenty cleartext characters do not satisfy it. The reference-data seed therefore
     * carries a sealed envelope for every row rather than the cleartext the record holds, which is
     * what lets a mandatory protected column be seeded at all.
     *
     * <p>Absence remains representable in memory, because a record image read at a boundary may not
     * carry a protected value yet and the mapper renders an absent identifier as the record's twenty
     * blank bytes. Persisting such an instance is refused by the column rather than by this class.
     */
    @Column(name = "govt_issued_id", length = 255, nullable = false)
    private String govtIssuedId;

    /**
     * Alphanumeric in the legacy record, so it is carried as bounded text and returned exactly as stored;
     * parsing, reformatting and calendar validation belong to the date-validation service. One column
     * carries both legacy spellings of the field, which denote the same bytes at the same offset.
     */
    @Column(name = "cust_dob", length = 10, nullable = false)
    private String custDob;

    @Column(name = "eft_account_id", length = 10, nullable = false)
    private String eftAccountId;

    @Column(name = "pri_card_holder_ind", length = 1, nullable = false)
    private String priCardHolderInd;

    /**
     * No numeric range constraint is declared here and none may be added. The 300-to-850 range is
     * screen-level edit validation belonging to the account-update path: 21 of the 50 reference customers
     * carry a score below 300, so a range, digit or pattern constraint would reject them and fail both the
     * seed load and every fixture round trip.
     */
    @Column(name = "fico_credit_score", length = 3, nullable = false)
    private String ficoCreditScore;

    /**
     * Required by the persistence provider, which assigns the attributes after construction.
     */
    protected Customer() {
    }

    /**
     * Creates a fully populated customer from the eighteen persisted attributes, in record order. Every
     * value is stored exactly as supplied, and the two regulated identifiers must already be protected
     * values.
     *
     * <p>Arguments are stored exactly as supplied. Nothing is trimmed, stripped, padded, case-folded,
     * normalized or reformatted, whether the caller is the record mapper handing over a field padded to
     * its full span or a persistence read handing over the trimmed form the column holds. The credit
     * score in particular is accepted at any three-character value the legacy data contains, including
     * values below the range the update screen enforces.
     *
     * <p>The two regulated identifiers are the exception: they are checked, not transformed. Each must
     * already carry the module's protected-value envelope, produced by the field-encryption service.
     * The national identifier may also be {@code null}, which records that no value is held and is
     * stored as a genuine null. Any other value - a cleartext identifier above all - is refused with
     * an exception that names the attribute and never the value.
     *
     * <p>The parameter list is long by design. No builder, parameter object or generated constructor
     * is introduced, because the module admits no code generation and the eighteen-attribute shape is
     * exactly the record contract.
     *
     * @param custId           customer identifier, 9 characters, the primary key
     * @param firstName        given name, 25 characters
     * @param middleName       middle name, 25 characters
     * @param lastName         family name, 25 characters
     * @param addrLine1        first address line, 50 characters
     * @param addrLine2        second address line, 50 characters
     * @param addrLine3        third address line, 50 characters
     * @param addrStateCd      state code, 2 characters
     * @param addrCountryCd    country code, 3 characters
     * @param addrZip          postal code, 10 characters
     * @param phoneNum1        primary telephone number, 15 characters
     * @param phoneNum2        secondary telephone number, 15 characters
     * @param custSsn          national identifier as a protected value, or {@code null} when none is
     *                         held
     * @param govtIssuedId     government-issued identifier as a protected value, never {@code null}
     * @param custDob          date of birth as text, 10 characters
     * @param eftAccountId     electronic-funds-transfer account identifier, 10 characters
     * @param priCardHolderInd primary-cardholder indicator, 1 character
     * @param ficoCreditScore  credit score as text, 3 characters, leading zeros preserved
     * @throws IllegalArgumentException when either regulated identifier is supplied in a form other
     *                                  than a well-formed protected value, or when the
     *                                  government-issued identifier is {@code null}
     */
    public Customer(String custId,
                    String firstName,
                    String middleName,
                    String lastName,
                    String addrLine1,
                    String addrLine2,
                    String addrLine3,
                    String addrStateCd,
                    String addrCountryCd,
                    String addrZip,
                    String phoneNum1,
                    String phoneNum2,
                    String custSsn,
                    String govtIssuedId,
                    String custDob,
                    String eftAccountId,
                    String priCardHolderInd,
                    String ficoCreditScore) {
        this.custId = custId;
        this.firstName = firstName;
        this.middleName = middleName;
        this.lastName = lastName;
        this.addrLine1 = addrLine1;
        this.addrLine2 = addrLine2;
        this.addrLine3 = addrLine3;
        this.addrStateCd = addrStateCd;
        this.addrCountryCd = addrCountryCd;
        this.addrZip = addrZip;
        this.phoneNum1 = phoneNum1;
        this.phoneNum2 = phoneNum2;
        this.custSsn = requireProtectedValue(custSsn, "custSsn");
        this.govtIssuedId = requireProtectedValue(govtIssuedId, "govtIssuedId");
        this.custDob = custDob;
        this.eftAccountId = eftAccountId;
        this.priCardHolderInd = priCardHolderInd;
        this.ficoCreditScore = ficoCreditScore;
    }

    /**
     * Creates a detached field-for-field image for compare-and-set persistence.
     *
     * @param source customer image to copy
     */
    public Customer(final Customer source) {
        this(
                Objects.requireNonNull(source, "source").custId,
                source.firstName,
                source.middleName,
                source.lastName,
                source.addrLine1,
                source.addrLine2,
                source.addrLine3,
                source.addrStateCd,
                source.addrCountryCd,
                source.addrZip,
                source.phoneNum1,
                source.phoneNum2,
                source.custSsn,
                source.govtIssuedId,
                source.custDob,
                source.eftAccountId,
                source.priCardHolderInd,
                source.ficoCreditScore);
    }

    public String getCustId() {
        return custId;
    }

    public void setCustId(String custId) {
        this.custId = custId;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getMiddleName() {
        return middleName;
    }

    public void setMiddleName(String middleName) {
        this.middleName = middleName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getAddrLine1() {
        return addrLine1;
    }

    public void setAddrLine1(String addrLine1) {
        this.addrLine1 = addrLine1;
    }

    public String getAddrLine2() {
        return addrLine2;
    }

    public void setAddrLine2(String addrLine2) {
        this.addrLine2 = addrLine2;
    }

    public String getAddrLine3() {
        return addrLine3;
    }

    public void setAddrLine3(String addrLine3) {
        this.addrLine3 = addrLine3;
    }

    public String getAddrStateCd() {
        return addrStateCd;
    }

    public void setAddrStateCd(String addrStateCd) {
        this.addrStateCd = addrStateCd;
    }

    public String getAddrCountryCd() {
        return addrCountryCd;
    }

    public void setAddrCountryCd(String addrCountryCd) {
        this.addrCountryCd = addrCountryCd;
    }

    public String getAddrZip() {
        return addrZip;
    }

    public void setAddrZip(String addrZip) {
        this.addrZip = addrZip;
    }

    public String getPhoneNum1() {
        return phoneNum1;
    }

    public void setPhoneNum1(String phoneNum1) {
        this.phoneNum1 = phoneNum1;
    }

    public String getPhoneNum2() {
        return phoneNum2;
    }

    public void setPhoneNum2(String phoneNum2) {
        this.phoneNum2 = phoneNum2;
    }

    public String getCustSsn() {
        return custSsn;
    }

    /**
     * @throws IllegalArgumentException if the value is neither {@code null} nor a well-formed protected value
     */
    public void setCustSsn(String custSsn) {
        this.custSsn = requireProtectedValue(custSsn, "custSsn");
    }

    public String getGovtIssuedId() {
        return govtIssuedId;
    }

    /**
     * Replaces the government-issued identifier with a protected value, or clears it.
     *
     * <p>{@code null} is accepted and records that no protected value is held, exactly as it does for
     * the national identifier. Cleartext is refused. That is the distinction that matters here:
     * absence is representable, an unprotected value is not.
     *
     * @param govtIssuedId the government-issued identifier as a protected value, or {@code null} to
     *                     record that none is held
     * @throws IllegalArgumentException when the value is neither {@code null} nor a well-formed
     *                                  protected value
     */
    public void setGovtIssuedId(String govtIssuedId) {
        this.govtIssuedId = requireProtectedValue(govtIssuedId, "govtIssuedId");
    }

    public String getCustDob() {
        return custDob;
    }

    public void setCustDob(String custDob) {
        this.custDob = custDob;
    }

    public String getEftAccountId() {
        return eftAccountId;
    }

    public void setEftAccountId(String eftAccountId) {
        this.eftAccountId = eftAccountId;
    }

    public String getPriCardHolderInd() {
        return priCardHolderInd;
    }

    public void setPriCardHolderInd(String priCardHolderInd) {
        this.priCardHolderInd = priCardHolderInd;
    }

    public String getFicoCreditScore() {
        return ficoCreditScore;
    }

    public void setFicoCreditScore(String ficoCreditScore) {
        this.ficoCreditScore = ficoCreditScore;
    }

    /**
     * Refuses a customer identifier that is not exactly nine ASCII digits, immediately before the row is
     * inserted or updated.
     *
     * <p>Only the identifier is checked. The protected fields this record carries are governed by their
     * own envelope rules, and the remaining fields are not keys: several of them are legitimately blank or
     * short in the reference data, so a width rule over them would reject data the legacy system stored.
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
     * @throws IllegalArgumentException if an identifier is absent or is not exactly the width its layout
     *         declares
     */
    @PrePersist
    @PreUpdate
    void normalizeAndValidateBeforeWrite() {
        StoredValueRules.requireFixedWidthDigits(custId, CUST_ID_WIDTH, "custId");
    }

    /**
     * Compares customers by the identifier alone, byte for byte, so that Java equality agrees with the
     * database's notion of the same row.
     *
     * @param o the object to compare against
     * @return {@code true} only if {@code o} is a {@code Customer} with an equal identifier
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Customer other)) {
            return false;
        }
        return Objects.equals(this.custId, other.custId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(custId);
    }

    /**
     * Rejects any value that is not a well-formed protected value, so that regulated cleartext cannot reach
     * the persistence boundary through this entity. The test is structural and needs no key: scheme marker,
     * Base64 body, and a decoded length long enough for an initialisation vector and an authentication tag.
     * It is written against the platform library alone, which is why it can live in this layer.
     *
     * <p>The rejection message never contains the offending value, since that value is regulated data and an
     * exception message is easily logged or returned; only the attribute name and the failing condition are
     * named.
     *
     * <p>{@code null} is returned unchanged. Both attributes guarded by this method are nullable, for
     * the same reason and by the same decision, so absence is a legitimate stored state for either and
     * there is no third caller for which it would not be. A {@code null} therefore needs no flag to
     * permit it: it is passed through as a genuine null and is never converted to an empty string or to
     * the literal text {@code "null"}.
     *
     * @param value         the candidate value, which may be {@code null}
     * @param attributeName the attribute being written, named in the failure message
     * @return the value, unchanged, when it is acceptable
     * @throws IllegalArgumentException when the value is non-{@code null} and is not a well-formed
     *                                  protected value
     */
    private static String requireProtectedValue(final String value, final String attributeName) {
        if (value == null) {
            return null;
        }
        if (!value.startsWith(PROTECTED_VALUE_PREFIX)) {
            throw new IllegalArgumentException(attributeName
                    + " must be an encrypted value carrying the " + PROTECTED_VALUE_PREFIX
                    + " envelope; storing cleartext in this attribute is not permitted");
        }
        final byte[] body;
        try {
            body = Base64.getDecoder().decode(value.substring(PROTECTED_VALUE_PREFIX.length()));
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException(attributeName + " carries the "
                    + PROTECTED_VALUE_PREFIX + " envelope marker but its body is not valid Base64",
                    notBase64);
        }
        if (body.length < PROTECTED_VALUE_MINIMUM_BYTES) {
            throw new IllegalArgumentException(attributeName + " carries the "
                    + PROTECTED_VALUE_PREFIX
                    + " envelope marker but is too short to be an authenticated ciphertext");
        }
        return value;
    }
}
