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
 * Persistent posted-transaction record - the Java translation of the {@code TRAN-RECORD} structure
 * declared by the legacy copybook {@code CVTRA05Y}, whose header states a record length of 350 bytes.
 *
 * <p>This is the <em>validated</em> transaction master. It is the settled side of the posting
 * boundary: {@link DailyTransaction} is the raw landing surface for the sequential daily input, and a
 * row reaches this table only once the posting job has accepted it. The two record layouts are
 * byte-for-byte parallel, which makes the column-naming difference described below the single most
 * important thing to get right about this class.
 *
 * <h2>Corroboration</h2>
 *
 * <p>Three independent artifacts of the read-only legacy estate agree on this record, and a fourth
 * supplies the identity pattern:
 *
 * <ul>
 *   <li>{@code app/cpy/CVTRA05Y.cpy} declares thirteen named fields followed by a 20-byte trailing
 *       filler; the declared widths sum to exactly 350.</li>
 *   <li>{@code app/jcl/TRANFILE.jcl} defines the {@code TRANSACT} VSAM KSDS base cluster with indexed
 *       organisation, {@code KEYS(16 0)} - a 16-byte key at offset 0 - and
 *       {@code RECORDSIZE(350 350)}, fixing the record at 350 bytes for both its minimum and its
 *       maximum.</li>
 *   <li>{@code app/jcl/TRANIDX.jcl} defines a second access path over the same cluster: an alternate
 *       index keyed {@code KEYS(26 304)}, declared {@code NONUNIQUEKEY} and {@code UPGRADE}, so it is
 *       maintained in step with the base cluster. See the offsets section below.</li>
 *   <li>{@code app/cbl/CBACT01C.cbl} demonstrates the estate-wide identity pattern in its file
 *       section, where a keyed record is declared as a leading key field followed by a remainder
 *       field. Every keyed file in the estate is declared this way.</li>
 * </ul>
 *
 * <p>Mapped offsets and widths, in declaration order:
 *
 * <ul>
 *   <li>0/16 - transaction identifier, the business key</li>
 *   <li>16/2 - transaction type code</li>
 *   <li>18/4 - transaction category code</li>
 *   <li>22/10 - source code</li>
 *   <li>32/100 - description</li>
 *   <li>132/11 - amount, nine digits before the decimal point and two after</li>
 *   <li>143/9 - merchant identifier</li>
 *   <li>152/50 - merchant name</li>
 *   <li>202/50 - merchant city</li>
 *   <li>252/10 - merchant postal code</li>
 *   <li>262/16 - card number</li>
 *   <li>278/26 - origination timestamp</li>
 *   <li>304/26 - processing timestamp</li>
 *   <li>330/20 - trailing filler, deliberately neither an attribute here nor a column in the
 *       schema</li>
 * </ul>
 *
 * <h2>Three offsets are load-bearing beyond this record</h2>
 *
 * <p>Most of the offsets above matter only to the record mapper. Three are addressed by absolute
 * position from <em>outside</em> the program that owns the record, which makes them contractual, and
 * they are recorded here because a field reordering that left this class compiling would still break
 * a job that never mentions Java:
 *
 * <ul>
 *   <li><strong>Card number, one-based position 263</strong> - zero-based 262, width 16. The
 *       cataloged procedure {@code app/proc/TRANREPT.prc} declares this field to the external sort as
 *       {@code TRAN-CARD-NUM,263,16,ZD} and orders the report by it ascending.</li>
 *   <li><strong>Origination timestamp, zero-based offset 278</strong> - width 26. Addressed by the
 *       statement job's record reprojection.</li>
 *   <li><strong>Processing timestamp, zero-based offset 304</strong> - width 26. This is the
 *       alternate-index key of {@code app/jcl/TRANIDX.jcl}. The migration reproduces it as a B-tree
 *       index rather than as a second access path, and the report's inclusive processing-date range
 *       filter - declared to the sort as a character field at one-based position 305 - is served by
 *       an explicit range query rather than by a derived finder, because a range predicate is not
 *       expressible as one.</li>
 * </ul>
 *
 * <p><strong>The same sixteen bytes carry two different sort typings.</strong> The copybook declares
 * the card number as character data, the report procedure types it as zoned decimal, and the
 * statement job types the identical bytes as character. Nothing reconciles the two, and nothing
 * should: each job sorted for its own purpose. The consequence for this class is that the card number
 * is held as the raw fixed-width character value and never as a numeric type, because a numeric type
 * would silently pick one job's interpretation and discard the other. The consequence for the batch
 * tier is that ordering is defined per job rather than shared.
 *
 * <h2>The merchant columns here carry no prefix, and that is deliberate</h2>
 *
 * <p>{@link DailyTransaction} maps the parallel legacy record whose field names carry a distinct
 * prefix, and its columns follow that prefix throughout, its merchant columns included. This table
 * does not follow the same rule for all thirteen of its columns. On exactly four of them - and only
 * these four - the schema drops the {@code tran_} prefix entirely, so the column is named for the
 * merchant attribute alone:
 *
 * <ul>
 *   <li>{@code merchant_id}</li>
 *   <li>{@code merchant_name}</li>
 *   <li>{@code merchant_city}</li>
 *   <li>{@code merchant_zip}</li>
 * </ul>
 *
 * <p>Every other column on this table does carry the regular {@code tran_} prefix. Prepending it to
 * any of the four names above therefore produces an identifier the migration never declares, and the
 * same is true of the corresponding attribute names, which follow their columns. The asymmetry is a
 * property of the shipped migration and this class maps what the migration declares. Because the
 * mapping is validated at start-up rather than generated, regularising any of the four fails the
 * context outright instead of quietly creating a second column - so the mistake is loud, but it is
 * also an easy one to make by tidying the names or by copying the sibling entity, which is why the
 * rule is written down here rather than left to be inferred. The decision is recorded in
 * {@code docs/decision-log.md}.
 *
 * <h2>Identity is the legacy business key, never a surrogate</h2>
 *
 * <p>The cluster definition states the relationship directly: key width 16 at offset 0, the leading
 * substring of the 350-byte record image. The persistent identity of a transaction row therefore
 * <em>is</em> that transaction identifier. No generated value, sequence, table generator or synthetic
 * identifier appears on this class.
 *
 * <p><strong>A database sequence would be actively wrong here, not merely redundant.</strong> The
 * online bill-payment program {@code app/cbl/COBIL00C.cbl} mints a new identifier by browsing the
 * cluster backwards from its highest possible key to find the largest identifier in use, adding one to
 * it, and seeding to one when the file is empty. The migrated bill-payment service reproduces that
 * highest-key-plus-one derivation inside the same transaction, using a maximum-identifier query. A
 * sequence would diverge from it permanently at the first rollback, because a sequence does not
 * reclaim the value it handed out and the legacy derivation always would. Any identifier-generation
 * strategy declared on this class would compete with that logic, so none is.
 *
 * <h2>The card number is a scalar column, not an association</h2>
 *
 * <p>Referential integrity to the card master is a database constraint declared by the index
 * migration, not a Java relationship: no association, join column or navigable reference appears
 * here. The legacy access paths this replaces are keyed reads and browse cursors, which map to
 * repository methods over the scalar column and to paging, so an object graph would add a
 * navigation the migrated logic never performs while changing the column the provider expects.
 *
 * <p>One paging behaviour is worth recording even though it constrains the repository rather than this
 * class: the online transaction list program {@code app/cbl/COTRN00C.cbl} pages this data ten rows at
 * a time, filling rows one through ten in ascending order when paging forward but filling row ten down
 * to row one when paging backward. The backward page therefore has to preserve descending fill order
 * to present the same sequence the legacy screen did.
 *
 * <h2>This entity is a passive carrier; it computes nothing</h2>
 *
 * <p>Fixed-width offset arithmetic belongs exclusively to the transaction record mapper of the utility
 * layer, {@code com.carddemo.util.TransactionRecordMapper}, whose responsibility is to slice the byte
 * image at the offsets listed above, and zoned-decimal decoding belongs exclusively to
 * {@code com.carddemo.util.ZonedDecimalCodec}. Those are separate deliverables of the record-mapper
 * boundary and are <em>not necessarily present at this checkpoint</em>; the names above are therefore
 * plain code references rather than resolved links, so neither compilation nor documentation
 * generation here depends on them, and this class cites them only to say where that knowledge belongs.
 * This class holds column widths, never offsets, and performs no parsing, no scaling, no case folding,
 * no padding and no validation. The dependency direction is one-way: the utility layer produces
 * entities, so an entity never references the utility layer.
 *
 * <p>Values are stored verbatim, and constructors, accessors and mutators are plain assignments and
 * plain returns. Nothing here trims, pads or normalises, because two of these columns carry trailing
 * blanks that are behaviourally significant - see {@link #getTranSource()} and
 * {@link #getTranProcTs()} - and a helpful normalisation would destroy them.
 *
 * <h2>Schema authority</h2>
 *
 * <p>This mapping is validated, not generated. Flyway owns the {@code transaction} table and the
 * runtime configuration fixes the provider at schema validation only, so any divergence from the
 * migration - a renamed column, a changed width, a wrong Java type - fails start-up rather than
 * silently reshaping the database. Thirteen columns, thirteen attributes, none of them nullable. The
 * table carries no optimistic-lock counter, because only the two records updated through the online
 * screens do; a posted transaction is inserted and read, never edited in place.
 *
 * <p>The table is also <strong>empty after the reference-data seed</strong>. Unlike the account, card
 * and customer masters, no sample file populates it: rows arrive only from the posting job, the
 * interest run and the online bill-payment path. A query against a freshly migrated database
 * legitimately returns nothing.
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the estate at commit {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}. The
 * copybook's trailer records the upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68}, dated
 * 2022-07-19. The legacy tree is read-only reference: no source text from it is copied into this
 * module, so the traceability record cites member names, field names, widths, offsets and codes only.
 */
@Entity
@Table(name = "transaction")
public class Transaction {

    /**
     * Width of the transaction identifier in characters: 16, from the copybook's own field declaration
     * and from the cluster key definition that keys the base cluster at width 16 and offset 0.
     *
     * <p>Named so that the column length, the persistence-time check and any assertion all read the one
     * figure rather than three copies of it.
     */
    static final int TRAN_ID_WIDTH = 16;

    /**
     * Total digit count of the amount column: 11, being nine digits before the implied decimal point and
     * two after, from the copybook's own picture clause for this field.
     *
     * <p>Named so that the column declaration and the persistence-time normalisation read the one figure.
     */
    static final int TRAN_AMT_PRECISION = 11;

    /**
     * Transaction identifier - 16 characters at offset 0, and the business key.
     *
     * <p>Stored verbatim, leading zeros included. Although the legacy bill-payment path derives a new
     * identifier by treating the highest existing value as a number and adding one, the stored form is
     * the fixed-width character lexeme, because that is what the cluster key and every external sort
     * address.
     */
    @Id
    @Column(name = "tran_id", length = TRAN_ID_WIDTH, nullable = false)
    private String tranId;

    /** Transaction type code - 2 characters at offset 16, the key of the transaction type reference. */
    @Column(name = "tran_type_cd", length = 2, nullable = false)
    private String tranTypeCd;

    /**
     * Transaction category code - 4 characters at offset 18.
     *
     * <p>Declared in the copybook as a digit-only field, and held here as text rather than as a number
     * because its width and its leading zeros are contractual: a category of {@code 0005} is the
     * four-character lexeme {@code "0005"}, and a numeric attribute would render it as {@code 5} and
     * break both the fixed-width record image and the composite reference lookup that matches on it.
     */
    @Column(name = "tran_cat_cd", length = 4, nullable = false)
    private String tranCatCd;

    /**
     * Source code - 10 characters at offset 22.
     *
     * <p><strong>Deliberately the raw code, not an enumerated constant.</strong> A source-type
     * enumeration exists elsewhere in this package tree, and mapping this attribute onto it was
     * rejected on three separate grounds:
     *
     * <ul>
     *   <li>Persisting the constant <em>name</em> would write the Java identifier rather than the
     *       legacy lexeme, which is a different string and so a different record.</li>
     *   <li>Persisting the constant <em>ordinal</em> would write a whole number into a character
     *       column and fail schema validation at start-up.</li>
     *   <li>A converter would have to live outside this package tree, and an entity may not reach into
     *       the utility layer, so there is nowhere for one to go that respects the layering.</li>
     * </ul>
     *
     * <p>The decisive reason is simpler than any of them: <strong>the legacy values are padded to the
     * full ten characters</strong>, and the padding is part of the value. The two codes carried by the
     * sample daily input are eight characters of text followed by two blanks each, and the interest run
     * writes a shorter code of its own. Only the raw character value preserves that, so translating
     * between code and constant is the service layer's job and never this class's.
     */
    @Column(name = "tran_source", length = 10, nullable = false)
    private String tranSource;

    /** Description - 100 characters at offset 32, stored verbatim including any trailing blanks. */
    @Column(name = "tran_desc", length = 100, nullable = false)
    private String tranDesc;

    /**
     * Transaction amount - 11 digits at offset 132, being nine before the implied decimal point and
     * two after it, signed.
     *
     * <p>An exact decimal, never a binary floating-point type. The construct-mapping requirement this
     * migration is held to is explicit that decimal precision must be identical and that no
     * floating-point substitution is permitted, and the column is declared to match at precision
     * eleven, scale two.
     *
     * <p><strong>This attribute is a carrier, and the carrier never rounds.</strong> No arithmetic and
     * no rescaling happens on this class: every value that reaches it has already been scaled by
     * {@code com.carddemo.util.ZonedDecimalCodec}, which is the module's single rounding authority and
     * which truncates toward zero rather than rounding half-even. That is not a stylistic preference.
     * The legacy estate contains no rounding directive on any arithmetic statement anywhere, and a
     * store into a two-decimal field without one truncates, so half-even rounding - the conventional
     * choice in Java, and the wrong one here - would differ by a cent on roughly half of all interest
     * computations and fail byte-level output parity. Concentrating the policy in one codec is what
     * stops a second, divergent policy from appearing.
     *
     * <p>Negative amounts are ordinary and must survive a round trip unchanged: the parallel daily
     * input carries returns alongside purchases, signed in the legacy encoding by an overpunch in the
     * final byte. Decoding that is the codec's responsibility; this attribute simply neither rejects
     * nor normalises a negative value. It is also left unset rather than initialised, so an
     * uninitialised amount is visibly absent instead of silently zero.
     */
    @Column(name = "tran_amt", precision = TRAN_AMT_PRECISION, scale = 2, nullable = false)
    private BigDecimal tranAmt;

    /**
     * Merchant identifier - 9 characters at offset 143.
     *
     * <p><strong>Column {@code merchant_id}, with no prefix.</strong> This attribute and the three
     * that follow are the four the schema spells without the {@code tran_} prefix the rest of this
     * table carries; the prefixed spelling names no column. See the class documentation for why the
     * asymmetry exists and why it must not be regularised.
     *
     * <p>Declared in the copybook as a digit-only field and held here as text, for the same reason as
     * the category code: the width and the leading zeros are contractual.
     */
    @Column(name = "merchant_id", length = 9, nullable = false)
    private String merchantId;

    /** Merchant name - 50 characters at offset 152. Column {@code merchant_name}, unprefixed. */
    @Column(name = "merchant_name", length = 50, nullable = false)
    private String merchantName;

    /** Merchant city - 50 characters at offset 202. Column {@code merchant_city}, unprefixed. */
    @Column(name = "merchant_city", length = 50, nullable = false)
    private String merchantCity;

    /** Merchant postal code - 10 characters at offset 252. Column {@code merchant_zip}, unprefixed. */
    @Column(name = "merchant_zip", length = 10, nullable = false)
    private String merchantZip;

    /**
     * Card number - 16 characters at offset 262, one-based position 263.
     *
     * <p>A scalar value, not a navigable reference to the card master: the foreign key to it is a
     * database constraint declared by the index migration. This is also the field the report sort
     * addresses by absolute position and types as zoned decimal while the statement job types the same
     * bytes as character, which is why it is held as the raw character value.
     */
    @Column(name = "tran_card_num", length = 16, nullable = false)
    private String tranCardNum;

    /**
     * Origination timestamp - 26 characters at offset 278.
     *
     * <p>Held as its bounded external character form rather than as a date-time value. The sample data
     * carries a 26-character lexeme of date, time and fractional seconds, and preserving it exactly is
     * what keeps the record image reproducible; parsing it, where a caller needs a date, belongs above
     * this class.
     */
    @Column(name = "tran_orig_ts", length = 26, nullable = false)
    private String tranOrigTs;

    /**
     * Processing timestamp - 26 characters at offset 304, and the alternate-index key.
     *
     * <p>Held as its bounded external character form for a reason stronger than symmetry with the
     * origination timestamp: <strong>in the parallel daily input every one of the three hundred
     * seeded processing timestamps is 26 blanks</strong>, an unposted marker that no date-time column
     * and no temporal Java type can represent. A blank timestamp is a legitimate, meaningful value
     * here, so the column is character and the attribute is text, and an all-blank value must survive
     * a round trip as 26 blanks rather than coming back trimmed or absent.
     */
    @Column(name = "tran_proc_ts", length = 26, nullable = false)
    private String tranProcTs;

    /**
     * Creates an empty transaction. Required by the persistence provider, which instantiates an entity
     * before populating its state; application code should use the all-arguments constructor instead.
     */
    protected Transaction() {
    // Intentionally empty: the persistence provider assigns state after construction.
    }

    /**
     * Creates a fully populated transaction from the thirteen business fields, in the same order in
     * which they appear in the legacy record image.
     *
     * <p>Every argument is assigned exactly as supplied - nothing is trimmed, padded, case folded,
     * rescaled or validated - because the fixed-width padding these values carry, and the scale the
     * amount arrives with, are part of the record contract.
     *
     * @param tranId       transaction identifier, 16 characters at offset 0, the business key
     * @param tranTypeCd   transaction type code, 2 characters at offset 16
     * @param tranCatCd    transaction category code, 4 characters at offset 18, stored verbatim
     *                     including leading zeros
     * @param tranSource   source code, 10 characters at offset 22, stored verbatim including trailing
     *                     blanks, which are significant
     * @param tranDesc     description, 100 characters at offset 32
     * @param tranAmt      amount, offset 132, already at scale two as the codec produced it
     * @param merchantId   merchant identifier, 9 characters at offset 143, column {@code merchant_id}
     * @param merchantName merchant name, 50 characters at offset 152
     * @param merchantCity merchant city, 50 characters at offset 202
     * @param merchantZip  merchant postal code, 10 characters at offset 252
     * @param tranCardNum  card number, 16 characters at offset 262, a scalar key and not a reference
     * @param tranOrigTs   origination timestamp, 26 characters at offset 278
     * @param tranProcTs   processing timestamp, 26 characters at offset 304, which may be all blanks
     */
    public Transaction(String tranId,
                       String tranTypeCd,
                       String tranCatCd,
                       String tranSource,
                       String tranDesc,
                       BigDecimal tranAmt,
                       String merchantId,
                       String merchantName,
                       String merchantCity,
                       String merchantZip,
                       String tranCardNum,
                       String tranOrigTs,
                       String tranProcTs) {
        // Direct field assignment on purpose: calling an overridable setter from a constructor
        // would publish a partially built instance.
        this.tranId = tranId;
        this.tranTypeCd = tranTypeCd;
        this.tranCatCd = tranCatCd;
        this.tranSource = tranSource;
        this.tranDesc = tranDesc;
        this.tranAmt = tranAmt;
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.merchantCity = merchantCity;
        this.merchantZip = merchantZip;
        this.tranCardNum = tranCardNum;
        this.tranOrigTs = tranOrigTs;
        this.tranProcTs = tranProcTs;
    }

    /**
     * Returns the transaction identifier exactly as stored, its full sixteen characters unmodified.
     *
     * @return the 16-character transaction identifier, unmodified
     */
    public String getTranId() {
        return tranId;
    }

    /**
     * Replaces the transaction identifier. Because this value is the row's persistent identity, it is
     * assigned when the record is first mapped or minted and is not otherwise reassigned.
     *
     * @param tranId the 16-character transaction identifier, stored verbatim
     */
    public void setTranId(String tranId) {
        this.tranId = tranId;
    }

    /**
     * Returns the transaction type code exactly as stored.
     *
     * @return the 2-character type code, unmodified
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * Replaces the transaction type code.
     *
     * @param tranTypeCd the 2-character type code, stored verbatim
     */
    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    /**
     * Returns the transaction category code exactly as stored, leading zeros included.
     *
     * @return the 4-character category code, unmodified
     */
    public String getTranCatCd() {
        return tranCatCd;
    }

    /**
     * Replaces the transaction category code.
     *
     * @param tranCatCd the 4-character category code, stored verbatim including leading zeros
     */
    public void setTranCatCd(String tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    /**
     * Returns the source code exactly as stored, <strong>trailing blanks included</strong>. Those
     * blanks are part of the legacy ten-character value, so a caller comparing this against a code
     * must account for them rather than assuming a trimmed string.
     *
     * @return the 10-character source code, unmodified and untrimmed
     */
    public String getTranSource() {
        return tranSource;
    }

    /**
     * Replaces the source code. The value is stored exactly as supplied, so a caller passing a padded
     * legacy code keeps its padding and a caller passing a shorter code keeps that.
     *
     * @param tranSource the 10-character source code, stored verbatim including trailing blanks
     */
    public void setTranSource(String tranSource) {
        this.tranSource = tranSource;
    }

    /**
     * Returns the description exactly as stored.
     *
     * @return the 100-character description, unmodified
     */
    public String getTranDesc() {
        return tranDesc;
    }

    /**
     * Replaces the description.
     *
     * @param tranDesc the 100-character description, stored verbatim
     */
    public void setTranDesc(String tranDesc) {
        this.tranDesc = tranDesc;
    }

    /**
     * Returns the amount exactly as stored, at whatever scale it was given, with no rescaling and no
     * rounding applied on the way out.
     *
     * @return the transaction amount, unmodified, or {@code null} if it has never been set
     */
    public BigDecimal getTranAmt() {
        return tranAmt;
    }

    /**
     * Replaces the amount. The value is stored exactly as supplied: this method does not rescale,
     * round, normalise or reject anything, including a negative value, because the module's single
     * rounding authority has already applied the truncating policy before the value arrives here.
     *
     * @param tranAmt the transaction amount, expected at scale two, stored verbatim
     */
    public void setTranAmt(BigDecimal tranAmt) {
        this.tranAmt = tranAmt;
    }

    /**
     * Returns the merchant identifier exactly as stored, leading zeros included.
     *
     * @return the 9-character merchant identifier, unmodified
     */
    public String getMerchantId() {
        return merchantId;
    }

    /**
     * Replaces the merchant identifier.
     *
     * @param merchantId the 9-character merchant identifier, stored verbatim including leading zeros
     */
    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    /**
     * Returns the merchant name exactly as stored.
     *
     * @return the 50-character merchant name, unmodified
     */
    public String getMerchantName() {
        return merchantName;
    }

    /**
     * Replaces the merchant name.
     *
     * @param merchantName the 50-character merchant name, stored verbatim
     */
    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    /**
     * Returns the merchant city exactly as stored.
     *
     * @return the 50-character merchant city, unmodified
     */
    public String getMerchantCity() {
        return merchantCity;
    }

    /**
     * Replaces the merchant city.
     *
     * @param merchantCity the 50-character merchant city, stored verbatim
     */
    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    /**
     * Returns the merchant postal code exactly as stored.
     *
     * @return the 10-character merchant postal code, unmodified
     */
    public String getMerchantZip() {
        return merchantZip;
    }

    /**
     * Replaces the merchant postal code.
     *
     * @param merchantZip the 10-character merchant postal code, stored verbatim
     */
    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }

    /**
     * Returns the card number exactly as stored, its full sixteen characters unmodified. This is the
     * scalar value a transaction-by-card finder matches on; no card object is navigated to from here.
     *
     * @return the 16-character card number, unmodified
     */
    public String getTranCardNum() {
        return tranCardNum;
    }

    /**
     * Replaces the card number.
     *
     * @param tranCardNum the 16-character card number, stored verbatim
     */
    public void setTranCardNum(String tranCardNum) {
        this.tranCardNum = tranCardNum;
    }

    /**
     * Returns the origination timestamp exactly as stored, in its 26-character external form.
     *
     * @return the 26-character origination timestamp, unmodified
     */
    public String getTranOrigTs() {
        return tranOrigTs;
    }

    /**
     * Replaces the origination timestamp.
     *
     * @param tranOrigTs the 26-character origination timestamp, stored verbatim
     */
    public void setTranOrigTs(String tranOrigTs) {
        this.tranOrigTs = tranOrigTs;
    }

    /**
     * Returns the processing timestamp exactly as stored, in its 26-character external form and
     * <strong>untrimmed</strong>. An all-blank value is meaningful rather than empty, so it is
     * returned as the 26 blanks it is.
     *
     * @return the 26-character processing timestamp, unmodified and untrimmed
     */
    public String getTranProcTs() {
        return tranProcTs;
    }

    /**
     * Replaces the processing timestamp. An all-blank value is stored as given, because blanks are how
     * the legacy record marks a transaction that has not been processed.
     *
     * @param tranProcTs the 26-character processing timestamp, stored verbatim including all blanks
     */
    public void setTranProcTs(String tranProcTs) {
        this.tranProcTs = tranProcTs;
    }

    /**
     * Refuses a transaction identifier that is not exactly sixteen ASCII digits, immediately before the
     * row is inserted or updated.
     *
     * <p><strong>Why the width and the digit class are both contractual here.</strong> The identifier is
     * the cluster key and the leading sixteen bytes of the record image, so a shorter value could not
     * have come from a valid image and would split one record's identity across two rows. The digit
     * class matters for a second, sharper reason: identifiers are minted by taking the current
     * <em>character</em> maximum and adding one, and a character maximum coincides with a numeric maximum
     * only while every stored value is sixteen zero-padded digits. A single value of any other shape -
     * {@code "9"}, or an identifier carrying a letter - would sort above every well-formed identifier and
     * silently freeze allocation for the remaining life of the table, without breaking compilation and
     * without failing any test that was not looking for it. Both legacy writers satisfy the rule: the
     * online payment path moves a sixteen-digit numeric work field into the key, and the interest run
     * concatenates a ten-character all-digit run date with a six-digit sequence counter.
     *
     * <p><strong>Why a callback rather than the constructor or the setter.</strong> The persistence
     * provider hydrates a row by instantiating the entity and assigning its fields directly, so a
     * constructor guard is bypassed on every read while a callback sits on the one path every insert and
     * every update must take. It also leaves an instance built for an assertion, a fixture or an
     * intermediate calculation entirely unrestricted - only one about to become a row is checked.
     *
     * <p>The database enforces the same rule independently through the check constraint
     * {@code ck_transaction_tran_id_digits} in {@code V1__create_schema.sql}, so a bulk load or a
     * migration script that never constructs an entity is refused as well.
     *
     * <p>The amount is normalised in the same callback rather than in its setter, for the same reason and
     * against the same hazard: a value computed in a service, parsed from a request or left over from an
     * interest division carries whatever scale the arithmetic produced, and an entity that stored it
     * verbatim would let a repository write bypass the truncation policy the whole estate depends on.
     * {@code StoredValueRules} records why that policy truncates rather than rounds.
     *
     * @throws IllegalArgumentException if the identifier is absent, is not sixteen characters long, or
     *                                  carries a character outside {@code 0}-{@code 9}, or if the amount
     *                                  is absent or beyond the declared precision
     */
    @PrePersist
    @PreUpdate
    void normalizeAndValidateBeforeWrite() {
        StoredValueRules.requireFixedWidthDigits(tranId, TRAN_ID_WIDTH, "tranId");
        this.tranAmt = StoredValueRules.normalizedAmount(tranAmt, TRAN_AMT_PRECISION, "tranAmt");
    }

    /**
     * Compares transactions by their business key alone.
     *
     * <p>The transaction identifier is the primary key, so it - and only it - determines entity
     * identity. Including a mutable non-key attribute would change an instance's equality and hash as
     * soon as state was updated, which breaks membership in a hash-based collection across a
     * persistence flush. The key is compared character for character, with no trimming or case
     * folding, so that Java equality agrees exactly with the database's notion of the same row.
     *
     * @param o the object to compare against
     * @return {@code true} only if {@code o} is a {@code Transaction} whose transaction identifier
     *         equals this transaction's identifier
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Transaction other)) {
            return false;
        }
        return Objects.equals(this.tranId, other.tranId);
    }

    /**
     * Hashes the business key alone, from its untrimmed value, so that the hash stays consistent with
     * {@link #equals(Object)} and stable across every state change and every flush.
     *
     * @return the hash code of the transaction identifier
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranId);
    }

    /**
     * Returns a diagnostic rendering carrying only the transaction identifier, the type code and the
     * category code.
     *
     * <p>The amount, the card number and every merchant value are deliberately omitted. The card
     * number is a primary account number and the amount is financial data, and neither belongs in a log
     * line, an assertion message or an interpolated exception message - all of which can render an
     * entity without its author choosing to disclose anything, which is precisely why the withholding
     * has to happen here rather than at each call site. Every accessor still returns the untouched
     * value, so code that genuinely needs one asks for it explicitly.
     *
     * <p>This is a rendering decision only, and changes no stored, mapped or transmitted value.
     *
     * @return a diagnostic string carrying the identifier, the type code and the category code
     */
    @Override
    public String toString() {
        return "Transaction[tranId=" + tranId
                + ", tranTypeCd=" + tranTypeCd
                + ", tranCatCd=" + tranCatCd
                + "]";
    }
}
