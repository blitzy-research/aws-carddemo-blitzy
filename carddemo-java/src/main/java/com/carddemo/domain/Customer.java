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
import jakarta.persistence.Table;

/**
 * Customer master entity - the Java translation of the {@code CUSTOMER-RECORD} structure declared in
 * copybook {@code CVCUS01Y}, whose header states a record length of 500 bytes.
 *
 * <p><strong>Legacy provenance.</strong> The record was held in the indexed VSAM cluster
 * {@code CUSTDATA}, registered online as {@code CUSTDAT}. Its cluster definition in
 * {@code CUSTFILE.jcl} specifies {@code KEYS(9 0)} and {@code RECORDSIZE(500 500)}, corroborating
 * both the 9-byte key and the 500-byte fixed width independently of the copybook. Six programs
 * include it: {@code CBCUS01C}, {@code CBTRN01C}, {@code COACTUPC}, {@code COACTVWC},
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
 * case-folded, normalized, validated or encrypted, in a constructor or a mutator, because that
 * padding and those leading zeros are the stored value rather than incidental formatting.
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
 * <p><strong>Values are stored verbatim; nothing is trimmed, padded or folded.</strong> Every
 * attribute originates in a fixed-width field where padding is part of the value rather than
 * incidental whitespace. Neither the constructors nor the mutators trim, strip, pad, case-fold,
 * normalize or reformat anything, because any such transformation would break byte-level parity
 * with the legacy output.
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
     * Structural marker that opens every protected value this entity will accept.
     *
     * <p><strong>This literal is deliberately duplicated.</strong> It is declared authoritatively by
     * the module's protected-value codec in the utility layer, and is repeated here rather than
     * imported, because the domain layer is not permitted to depend on the utility layer. The
     * duplication is held in step behaviourally rather than by inspection: a unit test seals a value
     * with the codec and requires this entity to accept it, and seals another under a different marker
     * and requires this entity to refuse it. Editing either side alone therefore fails the build. A
     * five-character literal repeated once, pinned by a test, was judged a smaller cost than either a
     * layer violation or an unguarded column.
     */
    private static final String PROTECTED_VALUE_PREFIX = "ENC1:";

    /**
     * Smallest number of decoded bytes a protected value can carry: a 96-bit initialisation vector
     * plus a 128-bit authentication tag, with no ciphertext between them. Anything shorter cannot
     * have been produced by the codec and is refused without a key being consulted.
     */
    private static final int PROTECTED_VALUE_MINIMUM_BYTES = 28;

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
     * National identifier - 9 bytes at offset 279 of the record image, mapped to a 255-character
     * nullable column.
     *
     * <p><strong>This is the only nullable column in the schema, and the only attribute that may be
     * absent.</strong> A {@code null} must round-trip as {@code null} and must never be rewritten to
     * an empty string or to the literal text {@code "null"}.
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
     * {@link #requireProtectedValue(String, String, boolean)}, which admits only {@code null} or a
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
     * protected in the same way and by the same service, and the column is widened for the same
     * reason. The two fields differ in exactly one respect: this column is {@code NOT NULL}, so a
     * value is always required and {@code null} is rejected as well as cleartext.
     *
     * <p>As with the national identifier, this entity neither encrypts nor decrypts. It refuses:
     * every write path passes through {@link #requireProtectedValue(String, String, boolean)}, so
     * only a well-formed protected value can be stored.
     */
    @Column(name = "govt_issued_id", length = 255, nullable = false)
    private String govtIssuedId;

    /**
     * Date of birth as text - 10 bytes at offset 308 of the record image.
     *
     * <p>The field is alphanumeric rather than a date type in the legacy record, so it is mapped to a
     * bounded character column and returned exactly as stored; parsing, reformatting and calendar
     * validation belong to the date-validation service. This single column carries both legacy
     * spellings of the field - {@code CVCUS01Y} hyphenates it and {@code CUSTREC} does not - because
     * both denote the same 10 bytes at the same offset.
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
     * Credit score - 3 bytes at offset 329 of the record image, held as text so leading zeros survive
     * the round trip.
     *
     * <p><strong>No numeric range constraint is declared here, and none may be added.</strong> The
     * 300-to-850 range is screen-level edit validation belonging to the account-update path, not a
     * property of the stored record. The reason is measured: 21 of the 50 rows in the customer
     * reference data carry a score below 300, the lowest being {@code "001"}, so a minimum, maximum,
     * digit or pattern constraint - here or as a check constraint - would reject 21 of 50 rows and
     * fail both the seed load and every fixture round trip.
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
     * Creates a fully populated customer from the eighteen persisted attributes, in the contractual
     * order in which they appear in the 500-byte record image.
     *
     * <p>Arguments are stored exactly as supplied. Nothing is trimmed, stripped, padded, case-folded,
     * normalized or reformatted, because the fixed-width padding and the leading zeros carried by
     * these values are part of the stored record rather than incidental formatting. The credit score
     * in particular is accepted at any three-character value the legacy data contains, including
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
        this.custSsn = requireProtectedValue(custSsn, "custSsn", true);
        this.govtIssuedId = requireProtectedValue(govtIssuedId, "govtIssuedId", false);
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
     * Returns the stored national identifier exactly as held, or {@code null} when none is held.
     *
     * <p>The returned value is the protected form, because that is the only form this entity accepts.
     * This accessor performs no decryption: reading the cleartext is the field-encryption service's
     * responsibility, and the domain layer does not depend on it. A {@code null} result is a
     * legitimate, expected outcome and is returned as {@code null} rather than as an empty string.
     *
     * @return the stored national identifier as held, or {@code null} when none is held
     */
    public String getCustSsn() {
        return custSsn;
    }

    /**
     * Replaces the stored national identifier with a protected value.
     *
     * <p>No encryption, decryption, padding or null substitution is performed here, and none is
     * accepted in cleartext either. Callers pass a value already carrying the module's
     * protected-value envelope, or {@code null} to record that no value is held; a {@code null}
     * argument is stored as a genuine null and is never converted to an empty string or to the literal
     * text {@code "null"}. Anything else - in particular a nine-digit cleartext identifier - is
     * refused.
     *
     * @param custSsn the national identifier as a protected value, or {@code null} when none is held
     * @throws IllegalArgumentException when the value is neither {@code null} nor a well-formed
     *                                  protected value
     */
    public void setCustSsn(String custSsn) {
        this.custSsn = requireProtectedValue(custSsn, "custSsn", true);
    }

    /**
     * Returns the stored government-issued identifier exactly as held.
     *
     * <p>The returned value is the protected form. This accessor performs no decryption: reading the
     * cleartext is the field-encryption service's responsibility, and the domain layer does not depend
     * on it.
     *
     * @return the stored government-issued identifier as held, unmodified
     */
    public String getGovtIssuedId() {
        return govtIssuedId;
    }

    /**
     * Replaces the government-issued identifier with a protected value.
     *
     * <p>The column is not nullable, so {@code null} is refused as well as cleartext.
     *
     * @param govtIssuedId the government-issued identifier as a protected value
     * @throws IllegalArgumentException when the value is {@code null} or is not a well-formed
     *                                  protected value
     */
    public void setGovtIssuedId(String govtIssuedId) {
        this.govtIssuedId = requireProtectedValue(govtIssuedId, "govtIssuedId", false);
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
     * <p>No other attribute participates: every one of them is mutable, and including one would
     * change an instance's equality and hash after a flush that updates it. The identifier is
     * compared verbatim, so two differently padded identifiers are deliberately not equal - they are
     * distinct keys in the database too.
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

    /**
     * Rejects any value that is not a well-formed protected value, so that regulated cleartext cannot
     * reach the persistence boundary through this entity.
     *
     * <p>The test is structural and needs no key: the value must open with the scheme marker, the
     * remainder must decode as basic Base64, and the decoded body must be long enough to hold an
     * initialisation vector and an authentication tag. Nine cleartext digits, twenty cleartext
     * characters, an empty string, a whitespace-only string and the literal text {@code "null"} all
     * fail it; a correctly produced envelope passes it. The check is written against the platform
     * library alone, which is why it can live in this layer at all.
     *
     * <p><strong>The rejection message never contains the offending value.</strong> That value is by
     * definition regulated data, and an exception message is one of the surfaces most likely to be
     * logged, wrapped into a response body, or attached to a monitoring event. Only the attribute name
     * and the failing condition are named.
     *
     * @param value         the candidate value
     * @param attributeName the attribute being written, named in the failure message
     * @param nullPermitted whether {@code null} is a legitimate stored state for this attribute
     * @return the value, unchanged, when it is acceptable
     * @throws IllegalArgumentException when the value is {@code null} for a non-nullable attribute, or
     *                                  is not a well-formed protected value
     */
    private static String requireProtectedValue(final String value,
                                                final String attributeName,
                                                final boolean nullPermitted) {
        if (value == null) {
            if (nullPermitted) {
                return null;
            }
            throw new IllegalArgumentException(attributeName
                    + " is a protected attribute and must not be null");
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
