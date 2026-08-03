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
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * The card-to-customer-to-account cross reference, mapped to the {@code card_cross_reference} table.
 *
 * <p>The Java translation of the legacy {@code CARD-XREF-RECORD} structure declared in copybook
 * {@code app/cpy/CVACT03Y.cpy}, whose header names a record length of 50. Only 36 of those bytes carry
 * information - a 16-byte card number at offset 0, a 9-byte customer identifier at offset 16 and an
 * 11-byte account identifier at offset 25 - so this entity has exactly three mapped attributes. It is
 * the narrowest of the eleven entity translations in this package: three columns, a single-column
 * primary key, no composite key, no amount or rate attribute, no version attribute and no association
 * in either direction.
 *
 * <p><strong>The trailing filler is not persisted, and that is the one fact this class exists to make
 * discoverable.</strong> The copybook's fourth and last item, an unnamed {@code FILLER} of width 14 at
 * offset 36, completes the 50-byte image and carries no information, so no attribute and no column
 * represents it and this class declares exactly three of the record's four items. That 36-versus-50
 * split is exactly what explains an otherwise baffling pair of measurements among the validation
 * artefacts: the sample file {@code app/data/ASCII/cardxref.txt} measures 1,850 bytes, which is 50 rows
 * of 36 data bytes plus one line terminator each, because the text form writes only the mapped prefix;
 * the corresponding fixed-length dataset {@code app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS} measures
 * 2,500 bytes, which is the same 50 rows at the full 50-byte cluster record length. Both describe the
 * identical 50 rows and both are correct. A reader that advances through the text file at a 50-byte
 * stride is 14 bytes out of step from the second record onward and misparses every row after the first,
 * which is a defect that produces plausible-looking garbage rather than an error. The stride difference
 * is recorded in {@code docs/decision-log.md} as decision D-10 - a documented layout fact, never a
 * defect to repair.
 *
 * <p><strong>Legacy provenance.</strong> The record lives in the {@code CARDXREF} VSAM key-sequenced
 * base cluster, whose definition in {@code app/jcl/XREFFILE.jcl} declares {@code KEYS(16 0)} with
 * {@code RECORDSIZE(50 50)} on an {@code INDEXED} cluster. A key width of 16 at offset 0 means the key
 * is the leading substring of the stored image, and identical low and high record sizes confirm a
 * fixed-length layout. The same job stream defines the {@code CXACAIX} alternate index over
 * {@code XREF-ACCT-ID} with {@code KEYS(11 25)}, {@code NONUNIQUEKEY} and {@code UPGRADE}: a key width
 * of 11 at offset 25 is precisely the account identifier's position in this layout, non-unique because
 * many cards resolve to one account, and upgraded because the index is maintained in step with the base
 * cluster. Every geometric fact this class relies on is therefore corroborated independently three
 * times - by the copybook field declarations, by the cluster and alternate-index definitions, and by
 * the schema migration that annotates each column with the offset and width recomputed from the
 * copybook.
 *
 * <p><strong>The alternate index becomes an index plus a finder, not a mapped structure.</strong> In
 * the target the {@code CXACAIX} browse path is reproduced by two things working together: a B-tree
 * index named {@code idx_card_cross_reference_xref_acct_id} created by
 * {@code V2__create_indexes.sql}, and a derived repository finder over the scalar account-identifier
 * column, {@code CardCrossReferenceRepository.findByXrefAcctId}. Nothing about the index appears on
 * this class, because an index is a physical access path and this class declares a logical mapping.
 *
 * <p><strong>Three foreign keys exist in the database and no association exists in Java.</strong>
 * {@code V2__create_indexes.sql} constrains all three columns of this table:
 * {@code fk_card_xref_card} points the card number at {@code card.card_num},
 * {@code fk_card_xref_account} points the account identifier at {@code account.acct_id}, and
 * {@code fk_card_xref_customer} points the customer identifier at {@code customer.cust_id}. That makes
 * this the most heavily constrained table in the schema and, to an eye trained on object models, the
 * obvious place for three many-to-one associations. It deliberately declares none, for four reasons.
 * First, nothing in scope asks for an object graph: this table exists so that a lookup can go from one
 * key to another without loading anything, and an association would defeat the purpose of the table.
 * Second, the provider runs in validate mode on every profile, so each association would need a join
 * column declaration that restates a column name this class already declares, adding three ways to
 * fail start-up in exchange for nothing. Third, every legacy access is either a keyed read on the base
 * cluster or a browse on the alternate index, and both forms translate to repository methods over
 * scalar columns. Fourth, a faithful translation prefers the scalar keys that mirror the record image
 * over the shape idiomatic Java would reach for first. Referential integrity is consequently enforced
 * where it belongs, by the database, and is verified by inserting an orphan row and observing the
 * constraint reject it.
 *
 * <p><strong>Every identifier is text, and that is not an oversight.</strong> The customer and account
 * identifiers are declared as digit-only fields in the copybook, and both are mapped to bounded
 * character columns rather than to any numeric type. The reason is that a leading zero is contractual
 * here: the sample rows carry identifiers such as a nine-digit customer value and an eleven-digit
 * account value that are almost entirely leading zeros, and the external width the record publishes is
 * fixed. Held as a number, a value would come back narrower than the field it occupies, the stored key
 * would no longer be the bytes the record publishes, and a byte-level comparison of any fixed-width
 * output would fail. No numeric or wrapper type appears anywhere in this class for that reason.
 *
 * <p><strong>The identifier is the legacy business key, never a surrogate.</strong> The sequential
 * batch programs model every fixed-length record as a leading key field followed by a data remainder -
 * the canonical example being the account file description in {@code app/cbl/CBACT01C.cbl}, which
 * splits its record into an eleven-digit identifier followed by a 289-byte remainder - and this entity
 * follows the same discipline: the 16-byte card number at offset 0 <em>is</em> the identifier, exactly
 * as {@code KEYS(16 0)} declares. No generated value, sequence, table generator or machine-assigned
 * substitute appears here or anywhere in the schema, because a surrogate would sever the
 * correspondence between a record image and its row on which byte-level output parity depends.
 *
 * <p><strong>Where the fixed-width knowledge lives.</strong> Offsets, widths and the padding of the
 * 50-byte image - including the 14 filler bytes - are the exclusive concern of the record mapper in the
 * utility layer, {@code com.carddemo.util.CardXrefRecordMapper}, which slices 0/16, 16/9 and 25/11 and
 * ignores 36/14. This class carries persisted column widths as mapping metadata, performs no slicing,
 * parsing, padding, trimming or formatting, and holds no dependency on the utility layer: the mapper
 * produces instances of this entity, so the dependency runs one way only.
 *
 * <p><strong>Consumers.</strong> Copybook {@code CVACT03Y} is included by ten programs, the
 * third-highest inclusion count of any data copybook in the estate, and the reason is structural rather
 * than incidental: a card number is what the online screens and the posting job are given, while an
 * account or a customer is what they need, so this table is the resolution point for every
 * card-to-account and card-to-customer hop in the system. The reference data holds exactly 50 rows,
 * seeded after the customer, account and card tables so that all three constraints resolve.
 *
 * <p><strong>Mapping is validated, never generated; instances are mutable and hand-written.</strong>
 * Schema evolution is forward-only and owned by the migration set, and the provider runs in validate
 * mode on every profile, so every name, width and nullability declared below mirrors the migration
 * exactly and any divergence fails start-up outright. No declarative validation constraint appears
 * either: nullability and width are expressed once, through the column declarations, and enforced by
 * the database, because a constraint added here would reject input the legacy system accepts. A
 * persistent entity must offer a no-argument constructor the provider can instantiate and attributes it
 * can populate, which a compact data carrier cannot supply, so this is a plain class with every member
 * written out in full: no annotation processor, mapping framework, code generator or introspection
 * participates, which keeps the module's zero budget for reflective access intact and the build free of
 * processor-generated warnings under warnings-as-errors.
 *
 * <p><strong>Provenance.</strong> Every width, offset and count cited above was recomputed from the
 * estate at commit {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, whose members carry the upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19; that stamp appears in the
 * trailer of the copybook this class translates. No legacy source text is reproduced here - member
 * names, field names, widths, offsets and codes are cited only, and the legacy tree is read-only
 * reference that is never copied into this module.
 */
@Entity
@Table(name = "card_cross_reference")
public class CardCrossReference {

    /**
     * Card number - legacy field {@code XREF-CARD-NUM}, a 16-byte alphanumeric field at offset 0 of
     * the 50-byte record and the whole of the cluster key.
     *
     * <p>Mapped to schema column {@code xref_card_num}, declared {@code VARCHAR(16) NOT NULL} and
     * constrained by {@code fk_card_xref_card} against the card table's own key. This is the entity
     * identifier and it is the business key itself, exactly as {@code KEYS(16 0)} declares. Held as
     * text rather than as a number so that every leading zero survives a round trip intact and the
     * external width stays exactly 16; the sample rows begin with a zero, so a numeric type would
     * lose a byte on the first row it read.
     *
     * <p>This value is a primary account number. It is stored and returned untouched, but it is
     * deliberately withheld from {@link #toString()}; see that method for why.
     *
     * <p>Not declared {@code final}, because the no-argument constructor the persistence provider
     * requires cannot initialise a final attribute.
     */
    @Id
    @Column(name = "xref_card_num", length = 16, nullable = false)
    private String xrefCardNum;

    /**
     * Customer identifier - legacy field {@code XREF-CUST-ID}, a 9-digit field at offset 16 of the
     * 50-byte record.
     *
     * <p>Mapped to schema column {@code xref_cust_id}, declared {@code VARCHAR(9) NOT NULL} and
     * constrained by {@code fk_card_xref_customer} against the customer table's own key. Declared as
     * digits in the copybook and mapped to a bounded character column all the same, because the
     * sample values are nine characters of which all but the last one or two are leading zeros and
     * that width is the external contract. No association is declared towards the customer entity;
     * the constraint is the database's and the join, when one is needed, is a repository lookup by
     * this key.
     *
     * <p>Not declared {@code final}, for the same reason as the preceding attribute.
     */
    @Column(name = "xref_cust_id", length = 9, nullable = false)
    private String xrefCustId;

    /**
     * Account identifier - legacy field {@code XREF-ACCT-ID}, an 11-digit field at offset 25 of the
     * 50-byte record.
     *
     * <p>Mapped to schema column {@code xref_acct_id}, declared {@code VARCHAR(11) NOT NULL} and
     * constrained by {@code fk_card_xref_account} against the account table's own key. Its offset and
     * width are the two numbers the {@code CXACAIX} alternate index is keyed on, {@code KEYS(11 25)},
     * which is why this column and no other carries the B-tree index that reproduces that access path
     * and backs the account-scoped finder. Declared as digits in the copybook and mapped to a bounded
     * character column for the same leading-zero reason as the customer identifier.
     *
     * <p>Not declared {@code final}, for the same reason as the preceding attributes.
     */
    @Column(name = "xref_acct_id", length = 11, nullable = false)
    private String xrefAcctId;

    /**
     * Creates an empty instance.
     *
     * <p>Required by the persistence provider, which instantiates a managed entity through its
     * no-argument constructor and populates the attributes afterwards. That instantiation is the
     * provider's own concern; this class introspects nothing itself, so the module's zero budget for
     * low-level reflective access is unaffected.
     *
     * <p>Declared {@code protected} rather than {@code public} because application code should always
     * build a fully populated instance through {@link #CardCrossReference(String, String, String)};
     * the provider reaches this constructor regardless of its access level.
     */
    protected CardCrossReference() {
        // Intentionally empty: all three attributes are populated by the provider after construction.
    }

    /**
     * Creates a fully populated cross-reference row.
     *
     * <p>Parameter order is the copybook declaration order and must not be changed: the 16-byte card
     * number at offset 0 precedes the 9-byte customer identifier at offset 16, which precedes the
     * 11-byte account identifier at offset 25. The three arguments are all strings of similar shape,
     * so a transposition would compile silently and be caught only by a constraint violation or a
     * failed lookup; keeping the parameter order identical to the record order is what makes a call
     * site checkable against the layout.
     *
     * <p>All three values are stored exactly as supplied. No trimming, padding, case folding, width
     * enforcement or validation of any kind is applied, because the persisted widths and nullability
     * are enforced by the schema and because silently altering a caller's value would break the
     * byte-level correspondence between a record image and the stored row.
     *
     * @param xrefCardNum the 16-byte card number at offset 0, which is the identifier
     * @param xrefCustId  the 9-digit customer identifier at offset 16, leading zeros included
     * @param xrefAcctId  the 11-digit account identifier at offset 25, leading zeros included
     */
    public CardCrossReference(final String xrefCardNum, final String xrefCustId,
            final String xrefAcctId) {
        this.xrefCardNum = xrefCardNum;
        this.xrefCustId = xrefCustId;
        this.xrefAcctId = xrefAcctId;
    }

    /**
     * Returns the card number, which is this entity's identifier.
     *
     * @return the 16-byte card number at offset 0, exactly as supplied and with any leading zero
     *         preserved, or {@code null} if this instance has not been populated
     */
    public String getXrefCardNum() {
        return xrefCardNum;
    }

    /**
     * Sets the card number.
     *
     * <p>The value is assigned verbatim; no trimming, padding, case folding or validation is applied.
     * Because this attribute is the identifier, reassigning it on an instance already associated with
     * a persistence context changes that instance's identity, so callers should treat it as write-once
     * and use the three-argument constructor in preference.
     *
     * @param xrefCardNum the 16-byte card number at offset 0, stored exactly as supplied
     */
    public void setXrefCardNum(final String xrefCardNum) {
        this.xrefCardNum = xrefCardNum;
    }

    /**
     * Returns the customer identifier this card resolves to.
     *
     * @return the 9-digit customer identifier at offset 16, returned exactly as supplied so that its
     *         leading zeros survive intact, or {@code null} if this instance has not been populated
     */
    public String getXrefCustId() {
        return xrefCustId;
    }

    /**
     * Sets the customer identifier this card resolves to.
     *
     * <p>The value is assigned verbatim: whatever leading zeros it carries survive, and none are
     * added. Re-padding a shortened value here would hide a caller's mistake behind a plausible key
     * and turn a constraint violation into a lookup that silently resolves to the wrong customer.
     *
     * @param xrefCustId the 9-digit customer identifier at offset 16, stored exactly as supplied
     */
    public void setXrefCustId(final String xrefCustId) {
        this.xrefCustId = xrefCustId;
    }

    /**
     * Returns the account identifier this card resolves to.
     *
     * @return the 11-digit account identifier at offset 25, returned exactly as supplied so that its
     *         leading zeros survive intact, or {@code null} if this instance has not been populated
     */
    public String getXrefAcctId() {
        return xrefAcctId;
    }

    /**
     * Sets the account identifier this card resolves to.
     *
     * <p>The value is assigned verbatim, for the same reason as the customer identifier. This is also
     * the column the account-scoped finder queries, so a value normalised here would be a value the
     * finder could no longer match.
     *
     * @param xrefAcctId the 11-digit account identifier at offset 25, stored exactly as supplied
     */
    public void setXrefAcctId(final String xrefAcctId) {
        this.xrefAcctId = xrefAcctId;
    }

    /**
     * Compares this cross reference with another by identifier alone.
     *
     * <p>Only the card number participates, because it is the primary key and it alone determines row
     * identity. The customer and account identifiers are deliberately excluded even though they look
     * like identity: they are mutable, so including them would let an instance's hash code and
     * equality change while the instance is held in a set or used as a map key, which would corrupt
     * those collections across a flush. They are also not unique - many cards resolve to one account
     * and one customer, which is exactly why the alternate index over the account identifier is
     * declared non-unique.
     *
     * <p>Comparison is exact, with no normalisation, padding adjustment or case folding, because the
     * legacy width is fixed and every character of the key is significant. A card number written
     * without its leading zero is consequently not equal to the same card number written with it.
     *
     * @param o the object to compare with, which may be {@code null}
     * @return {@code true} only if {@code o} is a cross reference whose card number matches this one
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CardCrossReference other)) {
            return false;
        }
        return Objects.equals(xrefCardNum, other.xrefCardNum);
    }

    /**
     * Returns a hash code derived from the identifier alone, consistent with {@link #equals(Object)}.
     *
     * <p>Because it is derived from the key and from nothing else, the hash stays stable across every
     * state change and every flush, which is what makes an instance safe to hold in a hashed
     * collection while it is managed.
     *
     * @return the hash code for this cross reference
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(xrefCardNum);
    }

    /**
     * Returns a diagnostic representation that names the two identifiers this row resolves to and
     * discloses no card number.
     *
     * <p><strong>The card number is withheld even though it is this row's identity.</strong> It is a
     * primary account number, and a rendering escapes far more easily than an author intends: an
     * entity reaches a failed assertion message, a provider diagnostic, an interpolated exception
     * message or a structured log event without anybody choosing to disclose anything, so the only
     * reliable place to withhold it is here. Withholding it costs nothing that matters, because
     * {@link #getXrefCardNum()} still returns the untouched value and is what code that genuinely
     * needs the key calls. A leading or trailing fragment was rejected as a compromise: a fragment of
     * a card number is still card data, and a rendered length still discriminates between candidate
     * values. The same decision is taken, on the same terms, by the card entity itself.
     *
     * <p>The customer and account identifiers are retained because they are internal keys that name no
     * cardholder and reveal no instrument, and because a diagnostic that cannot say which account a
     * cross reference points at explains nothing at all. They are quoted and printed untrimmed so that
     * significant leading zeros stay visible.
     *
     * <p>This is a rendering decision only and changes no stored, mapped or transmitted value. The
     * schema applies no field-level protection to the card number and the migration introduces none,
     * because the legacy design applies none and inventing one would be feature expansion; that
     * residual gap remains decision D-14 in {@code docs/decision-log.md}, recorded as unclosed. What
     * this method settles is only that an unintended rendering cannot be the thing that widens it.
     *
     * @return a diagnostic string carrying both resolved identifiers and a fixed stand-in for the card
     *         number
     */
    @Override
    public String toString() {
        return "CardCrossReference[xrefCardNum=***REDACTED***"
                + ", xrefCustId='" + xrefCustId
                + "', xrefAcctId='" + xrefAcctId + "']";
    }
}
