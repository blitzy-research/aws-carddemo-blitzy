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
import java.util.Objects;

/**
 * Card-to-customer-to-account cross reference: the 50-byte record of {@code app/cpy/CVACT03Y.cpy},
 * of which 36 bytes carry information - a 16-byte card number at offset 0 (the business key), a
 * 9-byte customer identifier at offset 16 and an 11-byte account identifier at offset 25 - followed
 * by a 14-byte filler at offset 36 that is deliberately neither an attribute here nor a column. The
 * geometry is attested three times over: by the copybook declarations, by {@code KEYS(16 0)} with
 * {@code RECORDSIZE(50 50)} in {@code app/jcl/XREFFILE.jcl}, and by the offsets the schema migration
 * annotates on each column.
 *
 * <p>The {@code CXACAIX} alternate index over the account identifier becomes an index plus one finder,
 * not a mapped structure: {@code V2__create_indexes.sql} creates
 * {@code idx_card_cross_reference_xref_acct_id}, and
 * {@code CardCrossReferenceRepository.findByXrefAcctId} provides the access path, returning every row of
 * the account. The non-unique alternate index admits more than one row, so the single-row resolution the
 * legacy positioned read performs is applied by the calling service on the returned list rather than by a
 * second repository method. An index is physical; this class declares a logical mapping only.
 *
 * <p>{@code V2__create_indexes.sql} constrains all three columns with foreign keys to the card,
 * account and customer tables, making this the most heavily constrained table in the schema - and it
 * still declares no JPA association. This table exists so a lookup can go from one key to another
 * without loading anything, an association would defeat that purpose, and under validate-only schema
 * checking each one would add a start-up failure mode restating a column name already declared here.
 * Referential integrity is therefore enforced by the database alone.
 *
 * <p>Every identifier is text because leading zeros are contractual: the sample rows carry
 * nine-digit customer and eleven-digit account values that are almost entirely leading zeros, and a
 * numeric type would return a narrower value than the field it occupies, breaking any byte-level
 * comparison of the fixed-width image. Nothing here trims, pads or folds, and offset arithmetic
 * belongs to the record mapper alone.
 *
 * <p>{@link #toString()} redacts the card number, which is a primary account number; nothing in this
 * package logs.
 */
@Entity
@Table(name = "card_cross_reference")
public class CardCrossReference {

    /**
     * Width of the card number in characters: 16, declared by the copybook as sixteen alphanumeric characters
     * at offset 0.
     *
     * <p>Named so that the column declaration and the persistence-time rule read the one figure rather
     * than two copies of it.
     */
    static final int XREF_CARD_NUM_WIDTH = 16;

    /**
     * Width of the customer identifier in characters: 9, declared by the copybook as nine digits at offset
     * 16.
     *
     * <p>Named so that the column declaration and the persistence-time rule read the one figure rather
     * than two copies of it.
     */
    static final int XREF_CUST_ID_WIDTH = 9;

    /**
     * Width of the account identifier in characters: 11, declared by the copybook as eleven digits at offset
     * 25.
     *
     * <p>Named so that the column declaration and the persistence-time rule read the one figure rather
     * than two copies of it.
     */
    static final int XREF_ACCT_ID_WIDTH = 11;
    @Id
    @Column(name = "xref_card_num", length = XREF_CARD_NUM_WIDTH, nullable = false)
    private String xrefCardNum;

    @Column(name = "xref_cust_id", length = XREF_CUST_ID_WIDTH, nullable = false)
    private String xrefCustId;

    @Column(name = "xref_acct_id", length = XREF_ACCT_ID_WIDTH, nullable = false)
    private String xrefAcctId;

    /**
     * Stand-in printed in place of any populated identifier, matching the placeholder the card entity
     * and the account-view response both use so that one grep finds every redaction in the module.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Refuses an identifier carrying a character that could forge a line in a diagnostic.
     *
     * <p><strong>What this rejects, and only this.</strong> Any character the platform classifies as an
     * ISO control - which is every C0 and C1 code point, and therefore the line feed and carriage
     * return a forged log entry needs, plus the tab, escape and delete an operator's terminal would act
     * on. Nothing else. A {@code null} passes, because the persistence provider assigns attributes
     * after constructing an instance and the schema's own not-null constraints are what require a value
     * to be present.
     *
     * <p><strong>Why nothing else is rejected <em>here</em>.</strong> No width check, no digit-class
     * check, no trimming and no padding on <em>assignment</em>. This guard runs from the constructor and
     * from each setter, which is to say on every instance however it came to exist, including one built
     * for an assertion or an intermediate calculation; a value that will never become a row is not the
     * schema's business. Nothing is trimmed or padded anywhere, on assignment or on write, because
     * either would invent or destroy an identity rather than report a defect.
     *
     * <p><strong>Width and digit class are enforced on the way to a row, by
     * {@link #normalizeAndValidateBeforeWrite()}.</strong> All three attributes are slices of a
     * fixed-width record image, so every value the legacy system stored is exactly the declared width and
     * what that rule rejects is a value no image could have produced. A bounded column is not equivalent:
     * it states a maximum, and it is the <em>short</em> value that splits one record's identity between
     * the relational key and the bytes it is written back into. {@code V1__create_schema.sql} carries the
     * same rules as check constraints for a writer that never constructs an entity at all.
     *
     * <p><strong>Why a control character is the exception.</strong> It is not data in any legacy record:
     * every one of the nine ASCII fixtures is printable fixed-width text, so no legitimate value can
     * carry one. It is, however, exactly what a value needs in order to inject a second line into a
     * structured log event or a provider diagnostic and impersonate a record the application never
     * wrote. {@link #toString()} withholds all three values, so this class's own rendering cannot be the
     * carrier - but a constraint-violation message, an assertion failure or a provider diagnostic
     * interpolates an attribute directly, and refusing the character at the boundary is what closes
     * those without this class having to predict them. The same rule governs every diagnostic in the
     * module under decision {@code DL-041}.
     *
     * @param value     the identifier to check, which may be {@code null}
     * @param attribute the attribute name, used only in the refusal and never itself operator-supplied
     * @return the value, unchanged, so the guard can be used in an assignment
     * @throws IllegalArgumentException when the value carries a control character
     */
    private static String withoutControlCharacters(final String value, final String attribute) {
        if (value != null) {
            for (int index = 0; index < value.length(); index++) {
                if (Character.isISOControl(value.charAt(index))) {
                    // The refusal names the attribute and the offending POSITION, never the offending
                    // value or character: a diagnostic raised over a value suspected of carrying a
                    // forged line must not reproduce it, which is the whole point of refusing it.
                    throw new IllegalArgumentException(attribute
                            + " must not carry a control character; one was supplied at position "
                            + index + ". Identifiers are sliced from fixed-width printable record"
                            + " images, so no legitimate value carries one, and a value that does can"
                            + " forge a line in any diagnostic that interpolates it");
                }
            }
        }
        return value;
    }

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
     * <p>All three values are stored exactly as supplied. No trimming, padding, case folding or width
     * enforcement is applied, because the persisted widths and nullability are enforced by the schema and
     * because silently altering a caller's value would break the byte-level correspondence between a
     * record image and the stored row. One class of value is refused rather than stored: an identifier
     * carrying a control character, which no legitimate fixed-width record image holds and which is what
     * a value needs to forge a line in a diagnostic that interpolates it. See
     * {@link #withoutControlCharacters(String, String)}.
     *
     * @param xrefCardNum the 16-byte card number at offset 0, which is the identifier
     * @param xrefCustId  the 9-digit customer identifier at offset 16, leading zeros included
     * @param xrefAcctId  the 11-digit account identifier at offset 25, leading zeros included
     */
    public CardCrossReference(final String xrefCardNum, final String xrefCustId,
            final String xrefAcctId) {
        this.xrefCardNum = withoutControlCharacters(xrefCardNum, "xrefCardNum");
        this.xrefCustId = withoutControlCharacters(xrefCustId, "xrefCustId");
        this.xrefAcctId = withoutControlCharacters(xrefAcctId, "xrefAcctId");
    }

    public String getXrefCardNum() {
        return xrefCardNum;
    }

    /**
     * Sets the card number.
     *
     * <p>The value is assigned verbatim - no trimming, padding, case folding or width enforcement -
     * with one exception: a value carrying a control character is refused, because such a character is
     * not data in any legacy record and is exactly what a forged diagnostic line needs. See
     * {@link #withoutControlCharacters(String, String)}.
     * Because this attribute is the identifier, reassigning it on an instance already associated with
     * a persistence context changes that instance's identity, so callers should treat it as write-once
     * and use the three-argument constructor in preference.
     *
     * @param xrefCardNum the 16-byte card number at offset 0, stored exactly as supplied
     */
    public void setXrefCardNum(final String xrefCardNum) {
        this.xrefCardNum = withoutControlCharacters(xrefCardNum, "xrefCardNum");
    }

    public String getXrefCustId() {
        return xrefCustId;
    }

    /**
     * Sets the customer identifier this card resolves to.
     *
     * <p>The value is assigned verbatim: whatever leading zeros it carries survive, and none are
     * added. Re-padding a shortened value here would hide a caller's mistake behind a plausible key
     * and turn a constraint violation into a lookup that silently resolves to the wrong customer. The
     * single exception is a control character, which is refused rather than stored; see
     * {@link #withoutControlCharacters(String, String)} for why that one class of value is different.
     *
     * @param xrefCustId the 9-digit customer identifier at offset 16, stored exactly as supplied
     */
    public void setXrefCustId(final String xrefCustId) {
        this.xrefCustId = withoutControlCharacters(xrefCustId, "xrefCustId");
    }

    public String getXrefAcctId() {
        return xrefAcctId;
    }

    /**
     * Sets the account identifier this card resolves to.
     *
     * <p>The value is assigned verbatim, for the same reason as the customer identifier, and with the
     * same single exception for a control character. This is also the column the account-scoped finder
     * queries, so a value normalised here would be a value the finder could no longer match.
     *
     * @param xrefAcctId the 11-digit account identifier at offset 25, stored exactly as supplied
     */
    public void setXrefAcctId(final String xrefAcctId) {
        this.xrefAcctId = withoutControlCharacters(xrefAcctId, "xrefAcctId");
    }

    /**
     * Refuses any of the three identifiers that is not exactly the width its record layout declares, and the
     * two numeric ones that carry a character outside the ASCII digits, immediately before the row is
     * inserted or updated.
     *
     * <p><strong>Why a width rule belongs here at all.</strong> All three fields are slices of a
     * fixed-width record image, so every value the legacy system stored is exactly the declared width, and
     * what a width rule rejects is a value that no record image could have produced. This is the record
     * that resolves a card to an account, so a short identifier here mis-resolves an entire relationship
     * rather than merely mis-keying one row. The control-character rule the constructor applies is a
     * different guard and stays where it is: it screens a value on its way in from anywhere, while this one
     * screens a value on its way to a row.
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
        StoredValueRules.requireFixedWidth(xrefCardNum, XREF_CARD_NUM_WIDTH, "xrefCardNum");
        StoredValueRules.requireFixedWidthDigits(xrefCustId, XREF_CUST_ID_WIDTH, "xrefCustId");
        StoredValueRules.requireFixedWidthDigits(xrefAcctId, XREF_ACCT_ID_WIDTH, "xrefAcctId");
    }

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

    @Override
    public int hashCode() {
        return Objects.hashCode(xrefCardNum);
    }

    /**
     * Returns a diagnostic representation that discloses none of the three identifiers this row holds,
     * reporting only whether each is populated.
     *
     * <p><strong>All three are withheld, and the reason is the same for all three.</strong> A rendering
     * escapes far more easily than an author intends: an entity reaches a failed assertion message, a
     * provider diagnostic, an interpolated exception message or a structured log event without anybody
     * choosing to disclose anything, so the only reliable place to withhold a value is here. The card
     * number is a primary account number, and the other two are withheld on the same ground.
     *
     * <p><strong>Why the customer and account identifiers are not "internal keys that disclose
     * nothing".</strong> This row exists precisely to link them, so a rendering carrying both publishes
     * the association itself - and the association is what turns two opaque numbers into a statement
     * about one cardholder's relationship to one account. The module settles the question the same way at
     * the transport boundary: {@code AccountViewResponse.toString()} renders both its account identifier
     * and its customer identifier as this same placeholder. An entity that printed in full what the
     * response DTO built from it withholds would be a hole in one contract, and the wider of the two,
     * because an entity is what reaches a provider diagnostic.
     *
     * <p><strong>What is reported instead, and why it is enough.</strong> Each attribute renders as the
     * placeholder when populated and as {@code null} when it is not. That distinction is the one a
     * diagnostic actually needs from this type - a partially populated instance is a real defect and
     * stays visible - and it carries no value, no fragment and no length, so nothing about it
     * discriminates between candidate identifiers. A leading or trailing fragment is not an acceptable
     * compromise for any of the three: a fragment of an identifier is still that identifier's data, and a
     * rendered length still narrows the candidate set. Code that genuinely needs a value calls the
     * accessor, which returns it untouched.
     *
     * <p>This is a rendering decision only and changes no stored, mapped or transmitted value. The
     * schema applies no field-level protection to the card number and the migration introduces none,
     * because the legacy design applies none and inventing one would be feature expansion; that
     * residual gap remains decision D-14 in {@code docs/decision-log.md}, recorded as unclosed. What
     * this method settles is only that an unintended rendering cannot be the thing that widens it.
     *
     * @return a diagnostic string reporting the population state of all three identifiers and the value
     *         of none of them
     */
    @Override
    public String toString() {
        return "CardCrossReference[xrefCardNum=" + redacted(xrefCardNum)
                + ", xrefCustId=" + redacted(xrefCustId)
                + ", xrefAcctId=" + redacted(xrefAcctId) + "]";
    }

    /**
     * Renders one attribute's population state without rendering its value.
     *
     * @param value the attribute to describe, which may be {@code null}
     * @return {@link #REDACTION_PLACEHOLDER} when the attribute is populated, and {@code "null"}
     *         when it is not
     */
    private static String redacted(final String value) {
        return value == null ? "null" : REDACTION_PLACEHOLDER;
    }
}
