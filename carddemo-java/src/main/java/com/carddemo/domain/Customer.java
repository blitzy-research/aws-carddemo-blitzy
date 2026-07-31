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

import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Customer master entity - the Java translation of the {@code CUSTOMER-RECORD} structure declared
 * in the copybook {@code CVCUS01Y}, whose own header states a record length of 500 bytes.
 *
 * <p><strong>Legacy provenance.</strong> The record was held in the indexed VSAM cluster
 * {@code CUSTDATA}, registered to the online region under the file name {@code CUSTDAT}. Its
 * cluster definition in {@code CUSTFILE.jcl} specifies {@code KEYS(9 0)} and
 * {@code RECORDSIZE(500 500)} on an {@code INDEXED} cluster, which corroborates both the 9-byte
 * key and the 500-byte fixed record width independently of the copybook. The copybook is included
 * by six programs: {@code CBCUS01C}, {@code CBTRN01C}, {@code COACTUPC}, {@code COACTVWC},
 * {@code COCRDSLC} and {@code COCRDUPC}.
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
 *   <li>{@code govtIssuedId} - offset 288, width 20 - column {@code govt_issued_id}</li>
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
 * program {@code CBSTM03A} includes in place of {@code CVCUS01Y}. The two members were compared
 * field by field: they declare the same record name, the same eighteen fields with the same
 * picture clauses in the same order, and the same 168-byte trailing filler. They differ in exactly
 * one respect - the date-of-birth field is spelled {@code CUST-DOB-YYYYMMDD} in {@code CUSTREC}
 * and {@code CUST-DOB-YYYY-MM-DD} in {@code CVCUS01Y} - and both spellings denote the same 10
 * bytes at the same offset 308. Two COBOL spellings of one field are therefore mapped to the
 * single column {@code cust_dob}. No second entity, no second table, no secondary-table mapping
 * and no alternate class exists for {@code CUSTREC}; the alternate spelling is handled by the
 * mapper, not by the model.
 *
 * <p><strong>The identifier is the legacy business key, never a surrogate.</strong> Batch file
 * records in this estate are declared as a key substring followed by a data remainder - the
 * account reader {@code CBACT01C} declares an 11-byte identifier followed by a 289-byte data
 * area - and {@code KEYS(9 0)} places the customer key at offset 0 as the leading substring of the
 * record image. The persistent identity of a customer row therefore <em>is</em> its 9-character
 * identifier. No generated value, sequence or table generator is declared here, because a
 * surrogate key would sever the record-image-to-row correspondence that byte-level output parity
 * depends on.
 *
 * <p><strong>Every attribute is a {@link String}, including the digit-only ones.</strong> Three
 * fields are external decimal in the copybook - the identifier, the national identifier and the
 * credit score - yet all three map to bounded character columns, because external representation
 * is part of the contract. Seeded identifiers are zero-filled to their full nine characters, so
 * a value must stay {@code "000000001"} rather than collapsing to {@code 1}; a numeric attribute
 * would strip exactly the leading zeros that fixed-width output parity depends on, and mapping a
 * numeric type onto a character column is precisely the mismatch that schema validation exists to
 * reject. No decimal, integral or wrapper type appears in this class, which holds no monetary
 * field of any kind.
 *
 * <p><strong>Date of birth is a bounded string, not a date type.</strong> The field is alphanumeric
 * and 10 bytes wide in the record, and the seeded values are hyphenated text. It is stored and
 * returned verbatim; interpreting or reformatting it is the responsibility of the date-validation
 * service and the fixed-width mapper, not of this entity.
 *
 * <p><strong>Column names are not uniformly prefixed, and that is intentional.</strong> Only
 * {@code cust_id}, {@code cust_ssn} and {@code cust_dob} carry a {@code cust_}-style prefix; the
 * remaining fifteen columns do not. The names below are transcribed from the schema migration that
 * owns this table and must not be regularized: the schema is authoritative, Hibernate validates
 * against it rather than generating it, and renaming a column here to look more consistent would
 * abort application startup.
 *
 * <p><strong>Values are stored verbatim; nothing is trimmed, padded or folded.</strong> Every
 * attribute originates in a fixed-width field where padding is part of the value rather than
 * incidental whitespace. Neither the constructors nor the mutators trim, strip, pad, case-fold,
 * normalize, validate or encrypt anything, because any such transformation would break byte-level
 * parity with the legacy output.
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
     * Customer identifier - 9 bytes at offset 0 of the record image, and the primary key.
     *
     * <p>Held as text so that the zero-filled external-decimal form survives the round trip
     * intact. This is the legacy business key: no generated value is declared, and the value is
     * assigned by the caller rather than by the database.
     */
    @Id
    @Column(name = "cust_id", length = 9, nullable = false)
    private String custId;

    /**
     * Given name - 25 bytes at offset 9 of the record image. Stored verbatim, padding included.
     */
    @Column(name = "first_name", length = 25, nullable = false)
    private String firstName;

    /**
     * Middle name - 25 bytes at offset 34 of the record image. Stored verbatim.
     *
     * <p>The legacy account-update path decorates this field for error display but codes no edit
     * for it, so it is deliberately left unconstrained here. Attaching a validation constraint
     * would reject input the legacy system accepts.
     */
    @Column(name = "middle_name", length = 25, nullable = false)
    private String middleName;

    /**
     * Family name - 25 bytes at offset 59 of the record image. Stored verbatim.
     */
    @Column(name = "last_name", length = 25, nullable = false)
    private String lastName;

    /**
     * First address line - 50 bytes at offset 84 of the record image. Stored verbatim.
     */
    @Column(name = "addr_line_1", length = 50, nullable = false)
    private String addrLine1;

    /**
     * Second address line - 50 bytes at offset 134 of the record image. Stored verbatim.
     *
     * <p>Like the middle name, this field is decorated for error display by the legacy update path
     * but has no edit coded, so no validation constraint is attached here.
     */
    @Column(name = "addr_line_2", length = 50, nullable = false)
    private String addrLine2;

    /**
     * Third address line - 50 bytes at offset 184 of the record image. Stored verbatim.
     */
    @Column(name = "addr_line_3", length = 50, nullable = false)
    private String addrLine3;

    /**
     * State code - 2 bytes at offset 234 of the record image. Stored verbatim.
     *
     * <p>Membership of the permitted state-code set, and of the permitted state-and-postal-prefix
     * combinations, is checked by the validation-lookup service against externalized reference
     * data. It is not constrained here.
     */
    @Column(name = "addr_state_cd", length = 2, nullable = false)
    private String addrStateCd;

    /**
     * Country code - 3 bytes at offset 236 of the record image. Stored verbatim.
     */
    @Column(name = "addr_country_cd", length = 3, nullable = false)
    private String addrCountryCd;

    /**
     * Postal code - 10 bytes at offset 239 of the record image. Stored verbatim, leading zeros
     * included, because postal codes are text rather than numbers.
     */
    @Column(name = "addr_zip", length = 10, nullable = false)
    private String addrZip;

    /**
     * Primary telephone number - 15 bytes at offset 249 of the record image. Stored verbatim.
     *
     * <p>The legacy edit decomposes a number into area code, prefix and line number and checks the
     * area code against the permitted numbering-plan set. That check belongs to the
     * validation-lookup service; this attribute simply carries the stored characters.
     */
    @Column(name = "phone_num_1", length = 15, nullable = false)
    private String phoneNum1;

    /**
     * Secondary telephone number - 15 bytes at offset 264 of the record image. Stored verbatim.
     */
    @Column(name = "phone_num_2", length = 15, nullable = false)
    private String phoneNum2;

    /**
     * National identifier, held as application-produced ciphertext - 9 bytes at offset 279 of the
     * record image, mapped to a 255-character column.
     *
     * <p><strong>This is the only nullable column in the entire schema.</strong> The reference-data
     * seed migration leaves it null for every seeded row rather than embedding personal
     * identifiers in a checked-in artifact, so {@code null} is a legitimate, expected value. It
     * must round-trip as {@code null} and must never be rewritten to an empty string or to the
     * literal text {@code "null"}.
     *
     * <p><strong>This entity performs no encryption or decryption.</strong> The column is far wider
     * than the legacy field because it stores ciphertext rather than the cleartext value, but the
     * transformation happens outside this package: the domain layer may not depend on the utility
     * or service layers, so no attribute converter is declared here. An automatically applied
     * converter was considered and rejected outright, because auto-application on a string
     * attribute type would silently capture every string attribute in the module rather than this
     * one field. Encryption, decryption and null tolerance are therefore implemented in the
     * utility and service layers, keyed from configuration resolved at run time. No key, salt,
     * algorithm name or cleartext value appears anywhere in this class.
     *
     * <p>This attribute is a plain carrier: whatever the caller supplies is stored, and whatever is
     * stored is returned.
     */
    @Column(name = "cust_ssn", length = 255, nullable = true)
    private String custSsn;

    /**
     * Government-issued identifier - 20 bytes at offset 288 of the record image. Stored verbatim.
     */
    @Column(name = "govt_issued_id", length = 20, nullable = false)
    private String govtIssuedId;

    /**
     * Date of birth as text - 10 bytes at offset 308 of the record image.
     *
     * <p>The field is alphanumeric rather than a date type in the legacy record, and the seeded
     * values are hyphenated text, so it is mapped to a bounded character column and returned
     * exactly as stored. Parsing, reformatting and calendar validation belong to the
     * date-validation service.
     *
     * <p>This single column carries both legacy spellings of the field: the copybook
     * {@code CVCUS01Y} names it {@code CUST-DOB-YYYY-MM-DD} while the alternate copybook
     * {@code CUSTREC}, used by the statement-generation program {@code CBSTM03A}, names it
     * {@code CUST-DOB-YYYYMMDD}. Both denote the same 10 bytes at the same offset 308, so one
     * attribute and one column serve both.
     */
    @Column(name = "cust_dob", length = 10, nullable = false)
    private String custDob;

    /**
     * Electronic-funds-transfer account identifier - 10 bytes at offset 318 of the record image.
     * Stored verbatim.
     */
    @Column(name = "eft_account_id", length = 10, nullable = false)
    private String eftAccountId;

    /**
     * Primary-cardholder indicator - a single byte at offset 328 of the record image. Stored
     * verbatim as text, preserving the legacy single-character domain.
     */
    @Column(name = "pri_card_holder_ind", length = 1, nullable = false)
    private String priCardHolderInd;

    /**
     * Credit score - 3 bytes at offset 329 of the record image, held as text so that leading zeros
     * survive the round trip.
     *
     * <p><strong>No numeric range constraint is declared on this attribute, deliberately.</strong>
     * The 300-to-850 range that appears in the requirements is screen-level edit validation
     * belonging to the account-update service, not a property of the stored record, and it is
     * enforced at the service and request-object layer instead.
     *
     * <p>The reason is measured rather than stylistic. In the seeded customer reference data,
     * 21 of the 50 rows carry a score below 300, the lowest being {@code "001"}. A minimum,
     * maximum, digit or pattern constraint here - or a check constraint in the schema - would
     * reject 21 of 50 rows and would fail both the reference-data seed load and every fixture
     * round trip. Do not add one.
     */
    @Column(name = "fico_credit_score", length = 3, nullable = false)
    private String ficoCreditScore;

    /**
     * Creates an empty customer. Required because the persistence provider instantiates a managed
     * entity before populating its state; application code should use
     * {@link #Customer(String, String, String, String, String, String, String, String, String,
     * String, String, String, String, String, String, String, String, String)} or the mutators
     * instead.
     */
    protected Customer() {
        // Intentionally empty: the provider assigns the attributes after construction.
    }

    /**
     * Creates a fully populated customer from the eighteen persisted attributes, in the
     * contractual order in which they appear in the 500-byte record image.
     *
     * <p>Arguments are stored exactly as supplied. Nothing is trimmed, stripped, padded,
     * case-folded, normalized, validated or encrypted, because the fixed-width padding and the
     * leading zeros carried by these values are part of the stored record rather than incidental
     * formatting. In particular the national identifier is stored as received - ciphertext when the
     * caller has encrypted it, and {@code null} where no value is held - and the credit score is
     * accepted at any three-character value the legacy data contains, including values below the
     * range the update screen enforces.
     *
     * <p>The parameter list is long by design. A builder, a parameter object or a generated
     * constructor was not introduced, because the module admits no code generation and no
     * annotation processor, and because the eighteen-attribute shape is exactly the record
     * contract.
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
     * @param custSsn          national identifier as ciphertext, or {@code null} when none is held
     * @param govtIssuedId     government-issued identifier, 20 characters
     * @param custDob          date of birth as text, 10 characters
     * @param eftAccountId     electronic-funds-transfer account identifier, 10 characters
     * @param priCardHolderInd primary-cardholder indicator, 1 character
     * @param ficoCreditScore  credit score as text, 3 characters, leading zeros preserved
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
        this.custSsn = custSsn;
        this.govtIssuedId = govtIssuedId;
        this.custDob = custDob;
        this.eftAccountId = eftAccountId;
        this.priCardHolderInd = priCardHolderInd;
        this.ficoCreditScore = ficoCreditScore;
    }

    /**
     * Returns the customer identifier exactly as stored, zero filling included.
     *
     * @return the 9-character business key, unmodified
     */
    public String getCustId() {
        return custId;
    }

    /**
     * Replaces the customer identifier. The value is assigned verbatim.
     *
     * <p>The identifier is the primary key, so it is set when a record is first constructed and is
     * not reassigned on a managed instance.
     *
     * @param custId the 9-character business key, stored verbatim
     */
    public void setCustId(String custId) {
        this.custId = custId;
    }

    /**
     * Returns the given name exactly as stored, trailing spaces included.
     *
     * @return the 25-character given name, unmodified
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Replaces the given name. The value is assigned verbatim.
     *
     * @param firstName the 25-character given name, stored verbatim
     */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /**
     * Returns the middle name exactly as stored, trailing spaces included.
     *
     * @return the 25-character middle name, unmodified
     */
    public String getMiddleName() {
        return middleName;
    }

    /**
     * Replaces the middle name. The value is assigned verbatim and is not validated, matching the
     * legacy update path, which decorates this field for display but codes no edit for it.
     *
     * @param middleName the 25-character middle name, stored verbatim
     */
    public void setMiddleName(String middleName) {
        this.middleName = middleName;
    }

    /**
     * Returns the family name exactly as stored, trailing spaces included.
     *
     * @return the 25-character family name, unmodified
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Replaces the family name. The value is assigned verbatim.
     *
     * @param lastName the 25-character family name, stored verbatim
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /**
     * Returns the first address line exactly as stored, trailing spaces included.
     *
     * @return the 50-character first address line, unmodified
     */
    public String getAddrLine1() {
        return addrLine1;
    }

    /**
     * Replaces the first address line. The value is assigned verbatim.
     *
     * @param addrLine1 the 50-character first address line, stored verbatim
     */
    public void setAddrLine1(String addrLine1) {
        this.addrLine1 = addrLine1;
    }

    /**
     * Returns the second address line exactly as stored, trailing spaces included.
     *
     * @return the 50-character second address line, unmodified
     */
    public String getAddrLine2() {
        return addrLine2;
    }

    /**
     * Replaces the second address line. The value is assigned verbatim and is not validated,
     * matching the legacy update path, which decorates this field for display but codes no edit
     * for it.
     *
     * @param addrLine2 the 50-character second address line, stored verbatim
     */
    public void setAddrLine2(String addrLine2) {
        this.addrLine2 = addrLine2;
    }

    /**
     * Returns the third address line exactly as stored, trailing spaces included.
     *
     * @return the 50-character third address line, unmodified
     */
    public String getAddrLine3() {
        return addrLine3;
    }

    /**
     * Replaces the third address line. The value is assigned verbatim.
     *
     * @param addrLine3 the 50-character third address line, stored verbatim
     */
    public void setAddrLine3(String addrLine3) {
        this.addrLine3 = addrLine3;
    }

    /**
     * Returns the state code exactly as stored.
     *
     * @return the 2-character state code, unmodified
     */
    public String getAddrStateCd() {
        return addrStateCd;
    }

    /**
     * Replaces the state code. The value is assigned verbatim; membership of the permitted
     * state-code set is checked by the validation-lookup service, not here.
     *
     * @param addrStateCd the 2-character state code, stored verbatim
     */
    public void setAddrStateCd(String addrStateCd) {
        this.addrStateCd = addrStateCd;
    }

    /**
     * Returns the country code exactly as stored.
     *
     * @return the 3-character country code, unmodified
     */
    public String getAddrCountryCd() {
        return addrCountryCd;
    }

    /**
     * Replaces the country code. The value is assigned verbatim.
     *
     * @param addrCountryCd the 3-character country code, stored verbatim
     */
    public void setAddrCountryCd(String addrCountryCd) {
        this.addrCountryCd = addrCountryCd;
    }

    /**
     * Returns the postal code exactly as stored, leading zeros included.
     *
     * @return the 10-character postal code, unmodified
     */
    public String getAddrZip() {
        return addrZip;
    }

    /**
     * Replaces the postal code. The value is assigned verbatim, so leading zeros are retained.
     *
     * @param addrZip the 10-character postal code, stored verbatim
     */
    public void setAddrZip(String addrZip) {
        this.addrZip = addrZip;
    }

    /**
     * Returns the primary telephone number exactly as stored.
     *
     * @return the 15-character primary telephone number, unmodified
     */
    public String getPhoneNum1() {
        return phoneNum1;
    }

    /**
     * Replaces the primary telephone number. The value is assigned verbatim; area-code membership
     * of the permitted numbering-plan set is checked by the validation-lookup service, not here.
     *
     * @param phoneNum1 the 15-character primary telephone number, stored verbatim
     */
    public void setPhoneNum1(String phoneNum1) {
        this.phoneNum1 = phoneNum1;
    }

    /**
     * Returns the secondary telephone number exactly as stored.
     *
     * @return the 15-character secondary telephone number, unmodified
     */
    public String getPhoneNum2() {
        return phoneNum2;
    }

    /**
     * Replaces the secondary telephone number. The value is assigned verbatim.
     *
     * @param phoneNum2 the 15-character secondary telephone number, stored verbatim
     */
    public void setPhoneNum2(String phoneNum2) {
        this.phoneNum2 = phoneNum2;
    }

    /**
     * Returns the stored national identifier exactly as held, or {@code null} when no value is
     * held.
     *
     * <p>The returned value is ciphertext when the caller stored ciphertext. This accessor performs
     * no decryption: that is the responsibility of the utility and service layers, which the domain
     * layer does not depend on. A {@code null} result is a legitimate, expected outcome and is
     * returned as {@code null} rather than as an empty string.
     *
     * @return the stored national identifier as held, or {@code null} when none is held
     */
    public String getCustSsn() {
        return custSsn;
    }

    /**
     * Replaces the stored national identifier. The value is assigned verbatim.
     *
     * <p>No encryption, decryption, padding or null substitution is performed here. Callers pass
     * ciphertext, or {@code null} to record that no value is held; a {@code null} argument is
     * stored as a genuine null and is never converted to an empty string or to the literal text
     * {@code "null"}.
     *
     * @param custSsn the national identifier as ciphertext, or {@code null} when none is held
     */
    public void setCustSsn(String custSsn) {
        this.custSsn = custSsn;
    }

    /**
     * Returns the government-issued identifier exactly as stored.
     *
     * @return the 20-character government-issued identifier, unmodified
     */
    public String getGovtIssuedId() {
        return govtIssuedId;
    }

    /**
     * Replaces the government-issued identifier. The value is assigned verbatim.
     *
     * @param govtIssuedId the 20-character government-issued identifier, stored verbatim
     */
    public void setGovtIssuedId(String govtIssuedId) {
        this.govtIssuedId = govtIssuedId;
    }

    /**
     * Returns the date of birth as stored text, unparsed and unreformatted.
     *
     * @return the 10-character date of birth, unmodified
     */
    public String getCustDob() {
        return custDob;
    }

    /**
     * Replaces the date of birth. The value is assigned verbatim as text; it is neither parsed nor
     * reformatted, and calendar validity is checked by the date-validation service.
     *
     * @param custDob the 10-character date of birth as text, stored verbatim
     */
    public void setCustDob(String custDob) {
        this.custDob = custDob;
    }

    /**
     * Returns the electronic-funds-transfer account identifier exactly as stored.
     *
     * @return the 10-character transfer account identifier, unmodified
     */
    public String getEftAccountId() {
        return eftAccountId;
    }

    /**
     * Replaces the electronic-funds-transfer account identifier. The value is assigned verbatim.
     *
     * @param eftAccountId the 10-character transfer account identifier, stored verbatim
     */
    public void setEftAccountId(String eftAccountId) {
        this.eftAccountId = eftAccountId;
    }

    /**
     * Returns the primary-cardholder indicator exactly as stored.
     *
     * @return the single-character primary-cardholder indicator, unmodified
     */
    public String getPriCardHolderInd() {
        return priCardHolderInd;
    }

    /**
     * Replaces the primary-cardholder indicator. The value is assigned verbatim, with no case
     * folding, so the legacy single-character domain is preserved exactly.
     *
     * @param priCardHolderInd the single-character primary-cardholder indicator, stored verbatim
     */
    public void setPriCardHolderInd(String priCardHolderInd) {
        this.priCardHolderInd = priCardHolderInd;
    }

    /**
     * Returns the credit score as stored text, leading zeros included.
     *
     * @return the 3-character credit score, unmodified
     */
    public String getFicoCreditScore() {
        return ficoCreditScore;
    }

    /**
     * Replaces the credit score. The value is assigned verbatim and is deliberately not
     * range-checked here: the update screen's 300-to-850 range is enforced at the service and
     * request-object layer, and 21 of the 50 seeded customer rows hold a value below 300, the
     * lowest being {@code "001"}. Rejecting those values here would fail the reference-data seed
     * load.
     *
     * @param ficoCreditScore the 3-character credit score as text, stored verbatim
     */
    public void setFicoCreditScore(String ficoCreditScore) {
        this.ficoCreditScore = ficoCreditScore;
    }

    /**
     * Compares two customers by their identifier alone, which is the primary key and therefore the
     * entity's persistent identity.
     *
     * <p>No other attribute participates. Every remaining attribute is mutable, and including one
     * would change an instance's equality and hash after a flush that updates it, which would
     * corrupt hash-based collections and the persistence identity map. The identifier is compared
     * verbatim, without trimming or case folding, so two differently padded identifiers are
     * deliberately not equal - they are distinct keys in the database as well.
     *
     * @param o the object to compare against
     * @return {@code true} only if {@code o} is a {@code Customer} whose identifier equals this
     *         customer's identifier
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

    /**
     * Hashes the identifier alone, from its untrimmed value, so that the hash agrees with
     * {@link #equals(Object)} and remains stable across any update to a non-key attribute.
     *
     * @return the hash code of this customer's identifier
     */
    @Override
    public int hashCode() {
        return Objects.hash(custId);
    }
}
