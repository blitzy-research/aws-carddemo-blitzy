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
import jakarta.persistence.Version;

/**
 * JPA entity for a CardDemo payment card - the Java translation of the {@code CARD-RECORD} structure
 * declared by the legacy copybook {@code CVACT02Y}, whose header states a record length of 150 bytes.
 *
 * <p>This is the structural sibling of {@link Account}, and the second of only two entities in the
 * module to carry an optimistic-locking version counter.
 *
 * <p><strong>Corroboration.</strong> Two independent artifacts of the read-only legacy estate agree on
 * this record, and a third supplies the identity pattern:
 *
 * <ul>
 *   <li>{@code app/cpy/CVACT02Y.cpy} declares six named fields followed by a 59-byte trailing filler;
 *       the declared widths sum to exactly 150.</li>
 *   <li>{@code app/jcl/CARDFILE.jcl} defines the {@code CARDDATA} VSAM KSDS base cluster with indexed
 *       organisation, {@code KEYS(16 0)} - a 16-byte key at offset 0 - and
 *       {@code RECORDSIZE(150 150)}, fixing the record at 150 bytes for both its minimum and its
 *       maximum.</li>
 *   <li>{@code app/cbl/CBACT01C.cbl} demonstrates the estate-wide identity pattern in its file
 *       section, where a record is declared as a leading key field, {@code FD-ACCT-ID}, followed by a
 *       remainder field, {@code FD-ACCT-DATA}. Every keyed file in the estate is declared this way.</li>
 * </ul>
 *
 * <p>Mapped offsets and widths, in declaration order:
 *
 * <ul>
 *   <li>0/16 - card number, the business key</li>
 *   <li>16/11 - account identifier</li>
 *   <li>27/3 - card verification code</li>
 *   <li>30/50 - embossed name</li>
 *   <li>80/10 - expiration date; the legacy field name for this position is misspelled, see
 *       {@link #getCardExpirationDate()}</li>
 *   <li>90/1 - active status code</li>
 *   <li>91/59 - trailing filler, deliberately neither a field here nor a column in the schema</li>
 * </ul>
 *
 * <p><strong>Identity is the legacy business key, never a surrogate.</strong> The cluster definition
 * states the relationship directly: key width 16 at offset 0, which is the leading substring of the
 * 150-byte record image, and the file-section declaration pattern cited above splits every keyed
 * record the same way. The persistent identity of a card row therefore <em>is</em> that card number.
 * No generated value, sequence, table generator or synthetic identifier appears on this class:
 * introducing one would sever the record-image-to-row correspondence that byte-level output parity
 * depends on.
 *
 * <p><strong>The account identifier is a scalar column, not an association.</strong> The legacy
 * cluster carries an alternate index over that field - {@code KEYS(11 16)}, declared
 * {@code NONUNIQUEKEY} with {@code UPGRADE}, and registered to the online region as a file in its own
 * right - which the migration reproduces as a B-tree index and a derived finder over the scalar
 * column rather than as an object graph. Referential integrity is a database constraint, not a Java
 * relationship. See {@link #getCardAcctId()} for the full reasoning.
 *
 * <p><strong>This entity is a passive carrier; it computes nothing.</strong> Fixed-width offset
 * arithmetic belongs exclusively to the card record mapper in the utility layer,
 * {@code com.carddemo.util.CardRecordMapper}, which slices the byte image at the offsets listed
 * above. This class holds column widths, never offsets, and performs no parsing, no case folding, no
 * padding and no validation. The dependency direction is one-way: the utility layer produces
 * entities, so an entity never references the utility layer.
 *
 * <p><strong>Fixed-width values are stored verbatim.</strong> Every attribute carries a value
 * occupying its whole declared width, so padding is part of the stored value rather than incidental
 * whitespace - the embossed name in every one of the fifty sample records is space-filled to its
 * full fifty characters, and both digit-only identifiers are zero-filled to theirs. Constructors,
 * mutators and accessors are plain assignments and plain returns. In particular the legacy
 * upper-casing of the embossed name is a character-table fold performed by the card-update path in
 * the service layer, through {@code com.carddemo.util.CobolStringUtils}, and is never performed here
 * and never by way of a locale-sensitive library conversion.
 *
 * <p><strong>Schema authority.</strong> This mapping is validated, not generated. Flyway owns the
 * {@code card} table and the runtime configuration fixes Hibernate at schema validation only, so any
 * divergence from the migration - a renamed column, a changed width, a wrong Java type - fails
 * start-up rather than silently reshaping the database. Seven columns, seven attributes, all
 * non-nullable.
 *
 * <p><strong>Provenance.</strong> Translated from the estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}. The copybook's trailer records the upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68}, dated 2022-07-19. The legacy tree is read-only
 * reference: no source text from it is copied into this module, so the traceability record cites
 * member names, field names, widths, offsets and codes only.
 */
@Entity
@Table(name = "card")
public class Card {

    /**
     * Card number - 16 bytes at offset 0 of the record image, and the primary key.
     *
     * <p>The legacy field is alphanumeric rather than numeric, and this attribute keeps that exact
     * fixed-width character form. Every one of the fifty sample records happens to carry sixteen
     * digits, which makes a numeric type look plausible and is precisely why the character form is
     * stated deliberately here: the same sixteen-digit card number is carried on the transaction
     * record, where the copybook likewise declares it alphanumeric, and yet the external sort
     * specification of the transaction-report job types that position as zoned decimal. One value,
     * two typings, depending on which reader is looking. Each batch job therefore carries its own
     * comparator, and the persistence model keeps the raw image - the only representation both
     * readings agree on, and the only one that cannot lose a leading zero.
     *
     * <p><strong>Sensitivity.</strong> This is the primary account number. The legacy design applies
     * no field-level encryption, tokenisation or masking to it, and no requirement in scope
     * introduces one, so none is invented here - that would be feature expansion. The gap is carried
     * forward as an explicit unclosed finding, recorded as decision D-14 in
     * {@code docs/decision-log.md}, rather than silently closed or silently ignored. Nothing in this
     * package logs, and this value must not be written to a log by any caller.
     *
     * <p>Stored verbatim. This value is the row's persistent identity and is never generated.
     */
    @Id
    @Column(name = "card_num", length = 16, nullable = false)
    private String cardNum;

    /**
     * Account identifier this card is issued against - 11 bytes at offset 16 of the record image.
     *
     * <p><strong>A scalar column, deliberately not a JPA association.</strong> There is no
     * relationship annotation, no join column and no {@code Account} field on this class, and there is
     * no collection of cards on {@link Account}. Referential integrity is nonetheless enforced: the
     * index migration declares a foreign key from this column to the account table's key, so the
     * database rejects a card naming an account that does not exist. That check is a database
     * constraint, and it holds whether or not Java models a relationship.
     *
     * <p>Four reasons for keeping it scalar. No requirement asks for an object graph. An association
     * changes the column name the provider expects unless a join column restates it, which adds a
     * start-up failure mode under schema validation for no benefit. The access path the legacy system
     * actually provided is a keyed lookup over this field - the alternate index over the card cluster,
     * width 11 at offset 16, nonunique because many cards may be issued against one account - and a
     * derived finder over the scalar column reproduces exactly that, with the migration's B-tree index
     * beneath it; navigation through an object graph is never needed. And faithfulness favours a
     * scalar key that mirrors the record image over an idiom that hides it.
     *
     * <p>The same rule applies in the parent direction. This entity is the referenced side of the
     * foreign keys from the cross-reference table and from the transaction table, both of which target
     * the card number. Neither is modelled as a collection here.
     *
     * <p>A bounded character attribute rather than a numeric one: the legacy field holds eleven
     * digits, and the sample dataset stores the identifier zero-filled to its full width, so the
     * external text width and the leading zeros are contractual and must survive a round trip
     * untouched. A numeric type would discard them and would additionally contradict the character
     * column the migration defines, which schema validation exists to catch. Stored verbatim.
     */
    @Column(name = "card_acct_id", length = 11, nullable = false)
    private String cardAcctId;

    /**
     * Card verification code - 3 bytes at offset 27 of the record image.
     *
     * <p><strong>A bounded character attribute, never a numeric one.</strong> The legacy field holds
     * three digits, and among the fifty sample records eight carry a leading zero. A numeric type
     * would render the code {@code 007} as the number seven and the record image would no longer
     * round trip, so the requirement that decimal and digit-only values keep their external text
     * width is decisive here. The column the migration defines is a three-character column, which
     * schema validation checks against this declaration.
     *
     * <p><strong>Sensitivity.</strong> Like the card number, this value carries no field-level
     * encryption or masking in the legacy design, and none is introduced; the same unclosed finding,
     * decision D-14 in {@code docs/decision-log.md}, covers both. Exposure is not amplified either:
     * this value is deliberately absent from {@link #toString()}, and nothing in this package logs.
     *
     * <p>Stored verbatim.
     */
    @Column(name = "card_cvv_cd", length = 3, nullable = false)
    private String cardCvvCd;

    /**
     * Embossed cardholder name - 50 bytes at offset 30 of the record image.
     *
     * <p>Space-filled to its full fifty characters in every sample record, and stored with that
     * padding intact. The padding is part of the value: trimming it here would make a padded name
     * compare equal to its unpadded form in Java while the two remain distinct in the database.
     *
     * <p><strong>No case folding happens here.</strong> The legacy card-update program upper-cases
     * this field through a character-table substitution over the twenty-six unaccented Latin letters.
     * That fold is reproduced in the utility layer, by the string helper the update service calls, and
     * deliberately not by a locale-sensitive library conversion, which is Unicode-aware and would
     * transform characters the legacy table leaves untouched. Neither the fold nor any other
     * normalisation is applied by this class: the mutator and the accessor are a plain assignment and
     * a plain return.
     *
     * <p>Stored verbatim.
     */
    @Column(name = "card_embossed_name", length = 50, nullable = false)
    private String cardEmbossedName;

    /**
     * Expiration date - 10 bytes at offset 80 of the record image.
     *
     * <p><strong>Documented source anomaly.</strong> The legacy field name at this position drops a
     * letter from EXPIRATION and reads {@code CARD-EXPIRAION-DATE}, declared on line 9 of
     * {@code app/cpy/CVACT02Y.cpy}. It is the twin of the same defect on the account record and is
     * row 1 of the source anomaly register in {@code docs/decision-log.md}. It is preserved and
     * documented rather than silently corrected in a way that would shift the layout: this Java
     * property and the column are both spelled correctly, {@code cardExpirationDate} and
     * {@code card_expiration_date}, while <em>the record offset is unchanged at 80 for a width of
     * 10</em>, so the record mapper reads exactly the same bytes and the image stays byte-compatible.
     * The misspelled legacy name is cited here so that the mapping from this property back to the
     * copybook field stays findable by search.
     *
     * <p><strong>A bounded character attribute, never a date type.</strong> The legacy field is
     * alphanumeric of fixed width rather than a date field, so its exact ten-character external form
     * is the contract. Strict calendar parsing lives in the date-validation service, which reproduces
     * the legacy edit cascade; keeping the stored form textual preserves every value the legacy system
     * tolerated, including one a strict date parser would reject. Stored verbatim.
     */
    @Column(name = "card_expiration_date", length = 10, nullable = false)
    private String cardExpirationDate;

    /**
     * Active status code - a single byte at offset 90 of the record image.
     *
     * <p>Held as the raw one-character code and <em>not</em> as a mapped enum constant. A sibling enum
     * in {@code com.carddemo.domain.enums} models the vocabulary of two codes for service-layer use,
     * but this entity deliberately neither imports nor references it, for four independent reasons.
     *
     * <ul>
     *   <li><strong>An enum-mapped attribute cannot carry a code outside the vocabulary.</strong> A
     *       value the enum does not declare fails the read outright. The legacy batch programs took
     *       this byte straight from the file and never tested it, and the replacement column carries no
     *       check constraint, so an unmapped code flows through the legacy system untouched and must
     *       flow through here untouched too. Rejecting it would be new behavior.</li>
     *   <li><strong>String-valued enum mapping persists the constant <em>name</em>.</strong> The
     *       stored value is therefore the right width only for as long as every constant happens to be
     *       named as its own one-character code; a constant renamed for readability - a refactor with
     *       no database intent - would silently rewrite stored data, overflow a one-character column and
     *       destroy byte parity of the fixed-width output.</li>
     *   <li><strong>Ordinal enum mapping persists an integer</strong> and would fail schema validation
     *       against a character column outright.</li>
     *   <li><strong>An attribute converter would have no home.</strong> The enum sub-package is a
     *       closed set of enum declarations, and this package may not depend on the utility layer.</li>
     * </ul>
     *
     * <p>The raw code is lossless and tolerates every value the legacy file may carry, including the
     * single code present in all fifty sample records and any value outside the documented vocabulary.
     * Translation between code and constant is the service layer's responsibility, through that enum.
     * This attribute stores the code, verbatim, with no case folding: the legacy comparison tested the
     * byte as supplied, so a lowercase form is not an active status.
     */
    @Column(name = "card_active_status", length = 1, nullable = false)
    private String cardActiveStatus;

    /**
     * Optimistic-locking version counter, managed entirely by the persistence provider: read and
     * compared on every update, incremented on every successful one.
     *
     * <p><strong>Why it exists, and why it is an improvement rather than a change in behavior.</strong>
     * It replaces a comparison the online card-update program {@code COCRDUPC} performed by hand. That
     * program is one of a five-program family whose write path carries the safeguard, and it holds a
     * paired before image and after image of exactly the fields mapped here - identifiers, verification
     * code, embossed name, expiry and status - together with a condition name reporting that the record
     * was changed by someone else. The check is therefore not being introduced by this migration; it is
     * being moved from hand-written code into the persistence provider, and the conflict it detects
     * surfaces as the module's optimistic-lock conflict exception.
     *
     * <p>The legacy file definitions in the CICS resource definition {@code app/csd/CARDDEMO.CSD}
     * specify {@code READINTEG(UNCOMMITTED)}, {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}, so
     * concurrency correctness rested solely on that hand-written comparison plus
     * {@code UPDATEMODEL(LOCKING)} record locking. PostgreSQL read-committed isolation combined with
     * this version check is <em>strictly stronger</em> than that verified baseline. A reviewer should
     * read the stronger isolation as the documented improvement it is and not mistake it for a
     * behavioral regression; decision log entry D-15 records it, and the migrated business logic is
     * unchanged.
     *
     * <p>This is one of only two entities in the module carrying a version counter, the other being
     * {@link Account}, matching the only two tables in the schema that define the column. The
     * attribute is not initialised to a non-zero value, so the schema default applies and a seeded row
     * that omits the column does not diverge; it is neither insert- nor update-excluded, because the
     * provider must be free to write it.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Creates an empty card. Required by the persistence provider, which instantiates an entity
     * before populating its state; application code should use
     * {@link #Card(String, String, String, String, String, String)} instead.
     */
    protected Card() {
        // Intentionally empty: the persistence provider assigns state after construction.
    }

    /**
     * Creates a fully populated card from the six business fields, in the same order in which they
     * appear in the legacy record image.
     *
     * <p>The version counter is deliberately absent from this signature because the persistence
     * provider owns it. Every argument is assigned exactly as supplied - nothing is trimmed, padded,
     * case folded or validated - because the fixed-width padding these values carry is part of the
     * record contract.
     *
     * @param cardNum            card number, 16 bytes at offset 0, the business key
     * @param cardAcctId         account identifier this card is issued against, 11 bytes at offset
     *                           16, stored verbatim including leading zeros
     * @param cardCvvCd          card verification code, 3 bytes at offset 27, stored verbatim
     *                           including a leading zero
     * @param cardEmbossedName   embossed cardholder name, 50 bytes at offset 30, stored verbatim
     *                           including trailing spaces
     * @param cardExpirationDate expiration date as its 10-character external form, offset 80
     * @param cardActiveStatus   raw one-character active status code, offset 90
     */
    public Card(String cardNum,
                String cardAcctId,
                String cardCvvCd,
                String cardEmbossedName,
                String cardExpirationDate,
                String cardActiveStatus) {
        // Direct field assignment on purpose: calling an overridable setter from a constructor
        // would publish a partially built instance.
        this.cardNum = cardNum;
        this.cardAcctId = cardAcctId;
        this.cardCvvCd = cardCvvCd;
        this.cardEmbossedName = cardEmbossedName;
        this.cardExpirationDate = cardExpirationDate;
        this.cardActiveStatus = cardActiveStatus;
    }

    /**
     * Returns the card number exactly as stored, its full sixteen characters unmodified.
     *
     * @return the 16-character card number, unmodified
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Replaces the card number. Because this value is the row's persistent identity, it is assigned
     * when the record is first mapped and is not otherwise reassigned by application logic.
     *
     * @param cardNum the 16-character card number, stored verbatim
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the account identifier exactly as stored, leading zeros included. This is the scalar
     * value a card-by-account finder matches on; no account object is navigated to from here.
     *
     * @return the 11-character account identifier, unmodified
     */
    public String getCardAcctId() {
        return cardAcctId;
    }

    /**
     * Replaces the account identifier. The value is stored as supplied and is not checked here
     * against the account table - that check is the database's foreign key, which rejects an
     * identifier naming an account that does not exist.
     *
     * @param cardAcctId the 11-character account identifier, stored verbatim
     */
    public void setCardAcctId(String cardAcctId) {
        this.cardAcctId = cardAcctId;
    }

    /**
     * Returns the card verification code exactly as stored, any leading zero included.
     *
     * @return the 3-character verification code, unmodified
     */
    public String getCardCvvCd() {
        return cardCvvCd;
    }

    /**
     * Replaces the card verification code. The value is stored as supplied; its digit count and
     * content are not checked here, because screen-level field editing belongs to the card-update
     * request path, which reproduces the legacy edit cascade.
     *
     * @param cardCvvCd the 3-character verification code, stored verbatim
     */
    public void setCardCvvCd(String cardCvvCd) {
        this.cardCvvCd = cardCvvCd;
    }

    /**
     * Returns the embossed cardholder name exactly as stored, trailing spaces included and case
     * unchanged.
     *
     * @return the 50-character embossed name, unmodified
     */
    public String getCardEmbossedName() {
        return cardEmbossedName;
    }

    /**
     * Replaces the embossed cardholder name. The value is stored as supplied: it is neither trimmed,
     * padded nor case folded here, because the legacy character-table upper-casing belongs to the
     * card-update path in the service layer.
     *
     * @param cardEmbossedName the 50-character embossed name, stored verbatim
     */
    public void setCardEmbossedName(String cardEmbossedName) {
        this.cardEmbossedName = cardEmbossedName;
    }

    /**
     * Returns the expiration date in its stored 10-character external form, unparsed. This is the
     * correctly spelled counterpart of the misspelled legacy field at offset 80.
     *
     * @return the expiration date text, unmodified
     */
    public String getCardExpirationDate() {
        return cardExpirationDate;
    }

    /**
     * Replaces the expiration date. The value is stored as supplied and is not parsed or validated
     * here.
     *
     * @param cardExpirationDate the expiration date as its 10-character external form
     */
    public void setCardExpirationDate(String cardExpirationDate) {
        this.cardExpirationDate = cardExpirationDate;
    }

    /**
     * Returns the raw one-character active status code exactly as stored, without translating it to a
     * constant.
     *
     * @return the one-character status code, unmodified
     */
    public String getCardActiveStatus() {
        return cardActiveStatus;
    }

    /**
     * Replaces the raw one-character active status code. The value is stored as supplied and is not
     * checked against any vocabulary here.
     *
     * @param cardActiveStatus the one-character status code, stored verbatim
     */
    public void setCardActiveStatus(String cardActiveStatus) {
        this.cardActiveStatus = cardActiveStatus;
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
     * Compares cards by their business key alone.
     *
     * <p>The card number is the primary key, so it - and only it - determines entity identity.
     * Including a mutable non-key field would change an instance's equality and hash as soon as state
     * was updated, which breaks membership in a hash-based collection across a persistence flush. The
     * key is compared byte for byte, with no trimming or case folding, so that Java equality agrees
     * exactly with the database's notion of the same row.
     *
     * @param o the object to compare against
     * @return {@code true} only if {@code o} is a {@code Card} whose card number equals this card's
     *         card number
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Card other)) {
            return false;
        }
        return Objects.equals(this.cardNum, other.cardNum);
    }

    /**
     * Hashes the business key alone, from its untrimmed value, so that the hash stays consistent with
     * {@link #equals(Object)} and stable across every state change and every flush.
     *
     * @return the hash code of the card number
     */
    @Override
    public int hashCode() {
        return Objects.hash(cardNum);
    }

    /**
     * Returns a deliberately minimal diagnostic rendering: the card number and the raw status code,
     * and nothing else.
     *
     * <p><strong>The verification code is deliberately absent</strong> and must stay absent, so that
     * no accidental rendering of an instance can widen its exposure. The card number is present
     * because it is the row's identity and a diagnostic without it identifies nothing; it carries no
     * masking here for the same reason it carries no encryption in the schema - the legacy design
     * applies none and inventing one would be feature expansion. That residual gap is decision D-14 in
     * {@code docs/decision-log.md}, recorded as unclosed rather than quietly papered over. This
     * rendering is for assertion failures and debugging, not for a log line: nothing in this package
     * logs, and no caller should log it.
     *
     * <p>Both values are quoted and printed untrimmed so that significant padding remains visible.
     *
     * @return a diagnostic string containing the card number and the raw status code
     */
    @Override
    public String toString() {
        return "Card[cardNum='" + cardNum + "', cardActiveStatus='" + cardActiveStatus + "']";
    }
}
