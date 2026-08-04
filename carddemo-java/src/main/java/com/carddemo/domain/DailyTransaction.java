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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Persistent daily-transaction record - the raw, deliberately unvalidated landing surface for the
 * sequential daily input that the posting job consumes.
 *
 * <p>This is the Java translation of the {@code DALYTRAN-RECORD} layout described by copybook
 * {@code CVTRA06Y}, whose declared record length is 350 bytes. Thirteen of its fourteen fields become
 * the thirteen columns of table {@code daily_transaction}; the fourteenth is a 20-byte trailing filler
 * at offset 330 that carries no information, so it is neither an attribute here nor a column in the
 * schema, and is reconstructed on output from the declared record width rather than stored. Each
 * attribute below documents its own offset, width and column; the widths sum to exactly 350.
 *
 * <p>The legacy field-name prefix is spelled {@code DALYTRAN} rather than {@code DAILYTRAN}. The Java
 * type name corrects the spelling while every column name and attribute name preserves the legacy
 * prefix verbatim, because the schema is the contract and renaming a column would break it.
 *
 * <p><strong>Deliberately separate from the posted-transaction entity.</strong> Copybook
 * {@code CVTRA05Y} describes the posted record and its layout is byte-for-byte parallel with this one -
 * the same fourteen fields, in the same order, at the same offsets, with the same widths, differing
 * only in field-name prefix. The two are nevertheless modelled as independent entities over
 * independent tables, because they are two distinct datasets with two distinct lifecycles: the posted
 * record lived in a keyed indexed cluster, whereas this one has no cluster definition at all and
 * arrives as a sequential dataset. That sequential, pre-validation character is what makes this table
 * a landing area. Consequently this class shares no supertype with its sibling beyond {@link Object} -
 * no inheritance, no mapped superclass, no embeddable, no reference in either direction. The
 * duplication of thirteen attributes across the two classes is intentional and required: a shared base
 * class would impose one set of column names on both tables and would silently destroy the
 * merchant-column asymmetry described next.
 *
 * <p><strong>Merchant-column asymmetry - do not regularize.</strong> The two tables agree on the naming
 * pattern for every column except the merchant block: on the posted-transaction table the four
 * merchant columns are <em>unprefixed</em>, while on this table all four carry the {@code dalytran_}
 * prefix. The asymmetry is a property of the migrated schema, recorded as decision log entry D-39, and
 * must not be tidied in either direction - the provider validates every mapping against that schema,
 * so dropping the prefix here, or adding one to the sibling, turns start-up into four mapping failures.
 *
 * <p><strong>Zero foreign keys, on purpose, and load-bearing.</strong> No migration defines a foreign
 * key on this table: none from the card number to the card table, none from the type or category code
 * to the reference tables, none anywhere else. This class therefore declares no association and every
 * attribute is a plain scalar. Rows land here unvalidated and the posting program validates each one
 * itself, emitting a reject record carrying a numeric reason code when a check fails. Three of its
 * five reason codes are exactly the referential failures a database constraint would pre-empt: 100 for
 * an invalid card number, 101 for an account not found on read, and 109 for an account not found when
 * the posted balance is written back. A foreign key would reject such a row at insert time, those code
 * paths would become unreachable, and the reject output - a 430-byte record formed from the 350-byte
 * source image followed by an 80-byte trailer of a 4-digit reason code plus a 76-character description
 * - could never be produced. That output is contractual and verified byte for byte, so adding a
 * constraint later would break it.
 *
 * <p>For the same reason this class carries no Bean Validation annotation: a declarative constraint
 * that refused a malformed record before persistence would short-circuit the reject cascade and make
 * the reject file unreproducible. Validation of this data belongs to the batch validation step, which
 * must apply the checks in legacy source order and stop at the first failure, and the balance and
 * overlimit arithmetic belongs to the posting service.
 *
 * <p><strong>Measured composition of the reference input.</strong> The file
 * {@code app/data/ASCII/dailytran.txt} measures 105,300 bytes: 300 records of 350 bytes plus one line
 * terminator each. Its composition is what this entity must tolerate without normalization. The source
 * code reads {@code POS TERM} on 250 records and {@code OPERATOR} on 50, each space-padded to the full
 * width of 10, so trimming would corrupt both. The amount is positive on 250 records and negative on
 * 50, exercising both the debit and the credit posting paths, and a negative amount is ordinary data
 * that must round-trip with its sign intact. All 300 origination timestamps carry the identical
 * 26-character value {@code 2022-06-10 19:27:53.000000}, and all 300 processing timestamps are 26
 * spaces.
 *
 * <p>Those last two measurements are why the whole module models record timestamps as bounded
 * 26-character strings rather than as a temporal type: no temporal type can hold 26 spaces, and the
 * blank value must persist and reload as exactly 26 spaces - not {@code null}, not empty, not trimmed.
 * Because every record shares one origination timestamp, a date-window filtering test cannot be
 * written against this input and needs a separately constructed fixture.
 *
 * <p><strong>Layering.</strong> This class knows column names, widths and nullability, and nothing
 * else. Fixed-width offset slicing belongs to the record mapper in the utility layer, and
 * zoned-decimal encoding and decoding - including the overpunched sign in the trailing byte of an
 * amount and the truncating scale policy - belongs to {@link com.carddemo.util.ZonedDecimalCodec}.
 * This entity performs no parsing, formatting, scaling or arithmetic, and does not reference the
 * utility layer: entities are produced by mappers, never the reverse.
 *
 * <p><strong>A second legacy reader of this layout.</strong> Batch program {@code CBTRN01C} also reads
 * this record. It is a complete 491-line program that no job member, cataloged procedure or online
 * resource definition invokes anywhere in the estate. It is migrated all the same, behind a batch job
 * that is defined but excluded from the default pipeline and exercised only by tests; the orphan
 * wiring is a documented source anomaly, not dead code to be dropped.
 *
 * @since 1.0.0
 */
@Entity
@Table(name = "daily_transaction")
public class DailyTransaction {

    /**
     * Total digit count of the amount column: 11, being nine digits before the implied decimal point and two
     * after, from the copybook's own picture clause.
     *
     * <p>Named so that the column declaration and the persistence-time rule read the one figure rather
     * than two copies of it.
     */
    static final int DALYTRAN_AMT_PRECISION = 11;

    /**
     * Transaction identifier: 16 bytes at offset 0, column {@code dalytran_id}.
     *
     * <p>This is the persistent identity of the record and it is the legacy business key, taken
     * verbatim from the input image. No generated, sequence-backed or surrogate identifier is
     * declared anywhere in this class. The file description of the batch readers splits each record
     * into a leading key group followed by a data area, which makes the key the leading substring of
     * the record image; a surrogate would break that record-image-to-table-row correspondence, and
     * on this table specifically it would make it impossible to echo the original 350-byte source
     * image into a reject record.
     *
     * <p>Retained as text so that leading zeros survive: the reference input carries values such as
     * {@code 0000000000683580}, which must never collapse to a shorter numeric form.
     */
    @Id
    @Column(name = "dalytran_id", length = 16, nullable = false)
    private String dalytranId;

    /**
     * Transaction type code: 2 bytes at offset 16, column {@code dalytran_type_cd}.
     *
     * <p>A two-character lexeme such as {@code 01}. Deliberately not constrained by a foreign key to
     * the transaction-type reference table, so that an unknown code reaches application validation
     * instead of being refused at insert time.
     */
    @Column(name = "dalytran_type_cd", length = 2, nullable = false)
    private String dalytranTypeCd;

    /**
     * Transaction category code: 4 bytes at offset 18, column {@code dalytran_cat_cd}.
     *
     * <p>Digit-only in the legacy layout, yet modelled as text and stored in a bounded character
     * column, because the external width is contractual: a category code of {@code 0001} must remain
     * four characters and must never collapse to {@code 1}. A numeric property type would silently
     * discard the padding, so this is a {@link String} and not an integral type.
     */
    @Column(name = "dalytran_cat_cd", length = 4, nullable = false)
    private String dalytranCatCd;

    /**
     * Origination source code: 10 bytes at offset 22, column {@code dalytran_source}.
     *
     * <p>Held as the raw, space-padded ten-character code and never as an enum constant. A sibling
     * enumeration of source types exists elsewhere in the domain package tree, and this field
     * deliberately neither imports nor references it, for four independent reasons:
     *
     * <ul>
     *   <li>Persisting an enum by name would store the constant name, which does not match the
     *       padded legacy lexeme and would destroy byte parity of the fixed-width output.</li>
     *   <li>Persisting an enum by ordinal would store an integer and would fail schema validation
     *       against a character column.</li>
     *   <li>An attribute converter cannot be introduced here: the enum sub-package is capped at its
     *       existing membership, and the domain layer may not depend on the utility layer.</li>
     *   <li>The reference values are padded to the full width &mdash; {@code POS TERM} in 250 records
     *       and {@code OPERATOR} in 50, each followed by trailing spaces to width 10. Only a raw
     *       string preserves that padding.</li>
     * </ul>
     *
     * <p>Translating between a code and a typed constant is the service layer's responsibility, not
     * this entity's.
     */
    @Column(name = "dalytran_source", length = 10, nullable = false)
    private String dalytranSource;

    /**
     * Transaction description: 100 bytes at offset 32, column {@code dalytran_desc}.
     *
     * <p>Free text at its legacy width, stored exactly as supplied including any trailing spaces.
     */
    @Column(name = "dalytran_desc", length = 100, nullable = false)
    private String dalytranDesc;

    /**
     * Transaction amount: 11 bytes at offset 132, column {@code dalytran_amt}, mapped to an exact
     * decimal of precision 11 and scale 2 &mdash; 9 integer digits plus 2 decimal digits, signed.
     *
     * <p>{@link BigDecimal} is mandatory and no approximate binary type may ever appear here or
     * anywhere in this class: decimal precision must be identical to the legacy representation, with
     * no floating-point substitution.
     *
     * <p><strong>The entity performs no arithmetic and applies no scaling.</strong> It neither
     * rescales, rounds, negates nor takes the magnitude of a value; it is a passive carrier. A census
     * of the entire legacy estate found no rounding clause on any arithmetic statement, which means
     * every store into a two-decimal field truncates toward zero, so all scaling is funnelled
     * through {@link com.carddemo.util.ZonedDecimalCodec}, which applies that truncating policy
     * uniformly. Letting an entity scale independently is exactly how an inconsistent rounding policy
     * creeps in.
     *
     * <p>The same discipline applies to expression order downstream. This amount is the final operand
     * of the overlimit check, which the posting program evaluates strictly left to right as the
     * cycle credit less the cycle debit plus this amount, storing the result into a nine-integer,
     * two-decimal field. Because truncation makes that arithmetic non-associative, any algebraic
     * rearrangement changes which records receive reject reason code 102, so the computation belongs to
     * the posting service and must be reproduced there operand for operand.
     *
     * <p>Negative values are ordinary data: 50 of the 300 reference records are operator-originated
     * returns carrying a negative amount, encoded in the input with an overpunched sign in the
     * trailing byte. Nothing in this class rejects, normalizes or absolutizes a negative amount, and
     * the field is intentionally left uninitialized rather than defaulted to zero, so that a missing
     * value surfaces as a mapping fault instead of being masked by a plausible-looking zero.
     */
    @Column(name = "dalytran_amt", precision = DALYTRAN_AMT_PRECISION, scale = 2, nullable = false)
    private BigDecimal dalytranAmt;

    /**
     * Merchant identifier: 9 bytes at offset 143, column {@code dalytran_merchant_id}.
     *
     * <p>Digit-only in the legacy layout and modelled as text for the same width-preservation reason
     * as the category code, so this is a {@link String} and not an integral type.
     *
     * <p><strong>Prefix asymmetry.</strong> This field opens the four-column merchant block, and it
     * is the one block where this table and the posted-transaction table disagree: here all four
     * columns carry the {@code dalytran_} prefix, whereas on the sibling table the same four are
     * unprefixed. The divergence is intentional and is recorded in the project decision log; do not
     * regularize it in either direction, because the mapping is validated against the migrated
     * schema at startup.
     */
    @Column(name = "dalytran_merchant_id", length = 9, nullable = false)
    private String dalytranMerchantId;

    /**
     * Merchant name: 50 bytes at offset 152, column {@code dalytran_merchant_name}. Second column of
     * the prefixed merchant block described on {@link #dalytranMerchantId}.
     */
    @Column(name = "dalytran_merchant_name", length = 50, nullable = false)
    private String dalytranMerchantName;

    /**
     * Merchant city: 50 bytes at offset 202, column {@code dalytran_merchant_city}. Third column of
     * the prefixed merchant block described on {@link #dalytranMerchantId}.
     */
    @Column(name = "dalytran_merchant_city", length = 50, nullable = false)
    private String dalytranMerchantCity;

    /**
     * Merchant postal code: 10 bytes at offset 252, column {@code dalytran_merchant_zip}. Fourth and
     * last column of the prefixed merchant block described on {@link #dalytranMerchantId}. Held as
     * text at its legacy width, padded exactly as the input presents it.
     */
    @Column(name = "dalytran_merchant_zip", length = 10, nullable = false)
    private String dalytranMerchantZip;

    /**
     * Card number: 16 bytes at offset 262, column {@code dalytran_card_num}.
     *
     * <p>A primary account number, and therefore never emitted by {@link #toString()}.
     *
     * <p>This is the field a reviewer is most tempted to constrain, and it must stay unconstrained.
     * There is no association mapping and no foreign key to the card table in any migration version,
     * precisely so that a card number absent from the card table is accepted on insert and is
     * refused later by application validation with reject reason code 100. Adding a relationship or a
     * constraint here would make that reason code unreachable.
     */
    @Column(name = "dalytran_card_num", length = 16, nullable = false)
    private String dalytranCardNum;

    /**
     * Origination timestamp: 26 bytes at offset 278, column {@code dalytran_orig_ts}.
     *
     * <p>A bounded 26-character lexeme, not a temporal type. All 300 reference records carry the
     * identical value {@code 2022-06-10 19:27:53.000000}; because the whole input shares one
     * origination instant, any date-window filtering test must be built on a separately constructed
     * fixture rather than on this input.
     *
     * <p>Strict calendar parsing and validation belong to the service layer. This field stores and
     * returns the lexeme untouched.
     */
    @Column(name = "dalytran_orig_ts", length = 26, nullable = false)
    private String dalytranOrigTs;

    /**
     * Processing timestamp: 26 bytes at offset 304, column {@code dalytran_proc_ts}.
     *
     * <p><strong>26 spaces is a legitimate value and must never be trimmed, emptied or nulled.</strong>
     * All 300 reference records are blank in this field, because the input is staged before the
     * posting run has stamped it. That single measurement is what forces the whole module to model
     * record timestamps as bounded 26-character strings: no temporal type can represent a blank
     * timestamp, so a temporal mapping here would fail on the first record of the reference input.
     *
     * <p>The value must persist and reload as exactly 26 spaces. Nothing in this class trims, strips
     * or otherwise normalizes it, and no caller should either; the round trip of this field is the
     * defining behavior of this entity.
     */
    @Column(name = "dalytran_proc_ts", length = 26, nullable = false)
    private String dalytranProcTs;

    /**
     * Creates an empty record.
     *
     * <p>Required because the persistence provider instantiates an entity with no arguments before
     * populating its state. It is also the reason this type is a class rather than a record: a record
     * offers no such constructor and cannot be mapped as an entity. Application code should prefer
     * the all-arguments constructor.
     */
    protected DailyTransaction() {
        // Intentionally empty: the persistence provider assigns each property after construction.
    }

    /**
     * Creates a fully populated record from all thirteen persistent values.
     *
     * <p>The parameter order is the copybook declaration order, which is also the record image order
     * and the column order of the migrated table, so a caller reading a fixed-width image left to
     * right supplies arguments in the order it encounters them. Values are stored exactly as
     * supplied: nothing is trimmed, padded, case folded, rescaled or validated here, because the
     * caller is responsible for presenting each value at its legacy width and the record is expected
     * to land unvalidated.
     *
     * @param dalytranId           transaction identifier, 16 bytes in the legacy layout
     * @param dalytranTypeCd       transaction type code, 2 bytes in the legacy layout
     * @param dalytranCatCd        transaction category code, 4 bytes in the legacy layout
     * @param dalytranSource       origination source code, 10 bytes, space-padded
     * @param dalytranDesc         transaction description, 100 bytes in the legacy layout
     * @param dalytranAmt          transaction amount at scale 2, which may be negative
     * @param dalytranMerchantId   merchant identifier, 9 bytes in the legacy layout
     * @param dalytranMerchantName merchant name, 50 bytes in the legacy layout
     * @param dalytranMerchantCity merchant city, 50 bytes in the legacy layout
     * @param dalytranMerchantZip  merchant postal code, 10 bytes in the legacy layout
     * @param dalytranCardNum      card number, 16 bytes in the legacy layout
     * @param dalytranOrigTs       origination timestamp lexeme, 26 characters
     * @param dalytranProcTs       processing timestamp lexeme, 26 characters, legitimately blank on
     *                             input
     */
    public DailyTransaction(String dalytranId, String dalytranTypeCd, String dalytranCatCd,
            String dalytranSource, String dalytranDesc, BigDecimal dalytranAmt,
            String dalytranMerchantId, String dalytranMerchantName, String dalytranMerchantCity,
            String dalytranMerchantZip, String dalytranCardNum, String dalytranOrigTs,
            String dalytranProcTs) {
        this.dalytranId = dalytranId;
        this.dalytranTypeCd = dalytranTypeCd;
        this.dalytranCatCd = dalytranCatCd;
        this.dalytranSource = dalytranSource;
        this.dalytranDesc = dalytranDesc;
        this.dalytranAmt = dalytranAmt;
        this.dalytranMerchantId = dalytranMerchantId;
        this.dalytranMerchantName = dalytranMerchantName;
        this.dalytranMerchantCity = dalytranMerchantCity;
        this.dalytranMerchantZip = dalytranMerchantZip;
        this.dalytranCardNum = dalytranCardNum;
        this.dalytranOrigTs = dalytranOrigTs;
        this.dalytranProcTs = dalytranProcTs;
    }

    /**
     * Returns the transaction identifier exactly as stored, without trimming or padding.
     *
     * @return the transaction identifier, possibly {@code null} on an unpopulated instance
     */
    public String getDalytranId() {
        return dalytranId;
    }

    /**
     * Replaces the transaction identifier with the supplied value, stored verbatim.
     *
     * @param dalytranId the transaction identifier at its legacy width of 16
     */
    public void setDalytranId(String dalytranId) {
        this.dalytranId = dalytranId;
    }

    /**
     * Returns the transaction type code exactly as stored, without trimming or padding.
     *
     * @return the transaction type code, possibly {@code null} on an unpopulated instance
     */
    public String getDalytranTypeCd() {
        return dalytranTypeCd;
    }

    /**
     * Replaces the transaction type code with the supplied value, stored verbatim.
     *
     * @param dalytranTypeCd the transaction type code at its legacy width of 2
     */
    public void setDalytranTypeCd(String dalytranTypeCd) {
        this.dalytranTypeCd = dalytranTypeCd;
    }

    /**
     * Returns the transaction category code exactly as stored, with any leading zeros intact.
     *
     * @return the transaction category code, possibly {@code null} on an unpopulated instance
     */
    public String getDalytranCatCd() {
        return dalytranCatCd;
    }

    /**
     * Replaces the transaction category code with the supplied value, stored verbatim so that
     * leading zeros survive.
     *
     * @param dalytranCatCd the transaction category code at its legacy width of 4
     */
    public void setDalytranCatCd(String dalytranCatCd) {
        this.dalytranCatCd = dalytranCatCd;
    }

    /**
     * Returns the origination source code exactly as stored, with its trailing spaces intact.
     *
     * @return the origination source code, possibly {@code null} on an unpopulated instance
     */
    public String getDalytranSource() {
        return dalytranSource;
    }

    /**
     * Replaces the origination source code with the supplied value, stored verbatim. The padding is
     * preserved deliberately: the reference values occupy the full width of 10.
     *
     * @param dalytranSource the origination source code at its legacy width of 10
     */
    public void setDalytranSource(String dalytranSource) {
        this.dalytranSource = dalytranSource;
    }

    /**
     * Returns the transaction description exactly as stored, without trimming or padding.
     *
     * @return the transaction description, possibly {@code null} on an unpopulated instance
     */
    public String getDalytranDesc() {
        return dalytranDesc;
    }

    /**
     * Replaces the transaction description with the supplied value, stored verbatim.
     *
     * @param dalytranDesc the transaction description at its legacy width of 100
     */
    public void setDalytranDesc(String dalytranDesc) {
        this.dalytranDesc = dalytranDesc;
    }

    /**
     * Returns the transaction amount exactly as stored, at whatever scale and sign it was assigned.
     * No rescaling, rounding or sign adjustment is applied on the way out.
     *
     * @return the transaction amount, which may be negative, or {@code null} on an unpopulated
     *         instance
     */
    public BigDecimal getDalytranAmt() {
        return dalytranAmt;
    }

    /**
     * Replaces the transaction amount with the supplied value, stored verbatim.
     *
     * <p>The value is not rescaled, rounded, negated or absolutized here. Scaling is the codec's
     * responsibility and monetary arithmetic is the service layer's; callers must present an amount
     * already at scale 2, and a negative amount is legitimate.
     *
     * @param dalytranAmt the transaction amount at scale 2, which may be negative
     */
    public void setDalytranAmt(BigDecimal dalytranAmt) {
        this.dalytranAmt = dalytranAmt;
    }

    /**
     * Returns the merchant identifier exactly as stored, with any leading zeros intact.
     *
     * @return the merchant identifier, possibly {@code null} on an unpopulated instance
     */
    public String getDalytranMerchantId() {
        return dalytranMerchantId;
    }

    /**
     * Replaces the merchant identifier with the supplied value, stored verbatim so that leading
     * zeros survive.
     *
     * @param dalytranMerchantId the merchant identifier at its legacy width of 9
     */
    public void setDalytranMerchantId(String dalytranMerchantId) {
        this.dalytranMerchantId = dalytranMerchantId;
    }

    /**
     * Returns the merchant name exactly as stored, without trimming or padding.
     *
     * @return the merchant name, possibly {@code null} on an unpopulated instance
     */
    public String getDalytranMerchantName() {
        return dalytranMerchantName;
    }

    /**
     * Replaces the merchant name with the supplied value, stored verbatim.
     *
     * @param dalytranMerchantName the merchant name at its legacy width of 50
     */
    public void setDalytranMerchantName(String dalytranMerchantName) {
        this.dalytranMerchantName = dalytranMerchantName;
    }

    /**
     * Returns the merchant city exactly as stored, without trimming or padding.
     *
     * @return the merchant city, possibly {@code null} on an unpopulated instance
     */
    public String getDalytranMerchantCity() {
        return dalytranMerchantCity;
    }

    /**
     * Replaces the merchant city with the supplied value, stored verbatim.
     *
     * @param dalytranMerchantCity the merchant city at its legacy width of 50
     */
    public void setDalytranMerchantCity(String dalytranMerchantCity) {
        this.dalytranMerchantCity = dalytranMerchantCity;
    }

    /**
     * Returns the merchant postal code exactly as stored, without trimming or padding.
     *
     * @return the merchant postal code, possibly {@code null} on an unpopulated instance
     */
    public String getDalytranMerchantZip() {
        return dalytranMerchantZip;
    }

    /**
     * Replaces the merchant postal code with the supplied value, stored verbatim.
     *
     * @param dalytranMerchantZip the merchant postal code at its legacy width of 10
     */
    public void setDalytranMerchantZip(String dalytranMerchantZip) {
        this.dalytranMerchantZip = dalytranMerchantZip;
    }

    /**
     * Returns the card number exactly as stored, without trimming or padding.
     *
     * @return the card number, possibly {@code null} on an unpopulated instance
     */
    public String getDalytranCardNum() {
        return dalytranCardNum;
    }

    /**
     * Replaces the card number with the supplied value, stored verbatim and unvalidated. A card
     * number that exists in no other table is accepted here by design.
     *
     * @param dalytranCardNum the card number at its legacy width of 16
     */
    public void setDalytranCardNum(String dalytranCardNum) {
        this.dalytranCardNum = dalytranCardNum;
    }

    /**
     * Returns the origination timestamp lexeme exactly as stored, unparsed.
     *
     * @return the origination timestamp lexeme, possibly {@code null} on an unpopulated instance
     */
    public String getDalytranOrigTs() {
        return dalytranOrigTs;
    }

    /**
     * Replaces the origination timestamp lexeme with the supplied value, stored verbatim and
     * unparsed.
     *
     * @param dalytranOrigTs the origination timestamp lexeme at its legacy width of 26
     */
    public void setDalytranOrigTs(String dalytranOrigTs) {
        this.dalytranOrigTs = dalytranOrigTs;
    }

    /**
     * Returns the processing timestamp lexeme exactly as stored, unparsed and untrimmed. A value of
     * 26 spaces is returned as 26 spaces.
     *
     * @return the processing timestamp lexeme, possibly {@code null} on an unpopulated instance
     */
    public String getDalytranProcTs() {
        return dalytranProcTs;
    }

    /**
     * Replaces the processing timestamp lexeme with the supplied value, stored verbatim.
     *
     * <p>A blank value of 26 spaces is legitimate and is stored as given; it is never converted to
     * {@code null}, to an empty string or to a trimmed form.
     *
     * @param dalytranProcTs the processing timestamp lexeme at its legacy width of 26, which may be
     *                       entirely blank
     */
    public void setDalytranProcTs(String dalytranProcTs) {
        this.dalytranProcTs = dalytranProcTs;
    }

    /**
     * Normalises the amount to scale two, truncating toward zero, immediately before the row is inserted or
     * updated.
     *
     * <p>No identifier rule is applied here. This is the raw landing surface for the sequential daily
     * input: a row arrives <em>before</em> validation, the posting job is what judges it, and a malformed
     * identifier is a reject record carrying a reason code rather than a refused insert. Refusing it here
     * would delete the very case the posting job exists to report. The amount is different in kind - it is
     * normalised rather than judged, and the normalisation is a representation policy that applies to
     * every stored amount whatever its provenance.
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
        this.dalytranAmt =
                StoredValueRules.normalizedAmount(dalytranAmt, DALYTRAN_AMT_PRECISION, "dalytranAmt");
    }

    /**
     * Compares this record with another on the primary key alone.
     *
     * <p>Only the transaction identifier participates. Every other property is mutable, and including
     * a mutable property would let an instance change its own equality across a flush, which corrupts
     * set and map membership and the persistence-context identity map. Comparison is exact: the
     * identifier is not trimmed, padded or case folded first, as the fixed-width layout requires.
     *
     * @param o the object to compare with, which may be {@code null}
     * @return {@code true} only if {@code o} is a {@code DailyTransaction} with an equal transaction
     *         identifier
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DailyTransaction other)) {
            return false;
        }
        return Objects.equals(this.dalytranId, other.dalytranId);
    }

    /**
     * Returns a hash code derived from the primary key alone, keeping this type consistent with
     * {@link #equals(Object)} and stable across a flush.
     *
     * @return the hash code for this record
     */
    @Override
    public int hashCode() {
        return Objects.hash(dalytranId);
    }

    /**
     * Returns a diagnostic representation carrying only the transaction identifier, the type code and
     * the category code.
     *
     * <p>The amount, the card number and every merchant value are deliberately omitted: the card
     * number is a primary account number and the amount is financial data, and neither belongs in a
     * log line or an exception message.
     *
     * @return a redacted string representation of this record
     */
    @Override
    public String toString() {
        return "DailyTransaction[dalytranId=" + dalytranId
                + ", dalytranTypeCd=" + dalytranTypeCd
                + ", dalytranCatCd=" + dalytranCatCd
                + "]";
    }
}
