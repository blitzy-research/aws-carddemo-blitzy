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
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import com.carddemo.domain.id.DisclosureGroupId;

/**
 * Disclosure-group interest rate: the Java translation of the {@code DIS-GROUP-RECORD} layout
 * declared in copybook {@code CVTRA02Y} of the read-only legacy estate, whose own header states a
 * record length of 50.
 *
 * <p>A disclosure group associates an account group with a transaction type and a transaction
 * category, and carries the one interest rate that the interest-accrual batch program applies to
 * matching category balances. There is exactly one rate per group/type/category triple, so the
 * entire identity of a row is its three-part key and the rate is the only non-key attribute.
 *
 * <p><strong>Record layout and the sixteen-byte key.</strong> Four fields of the 50-byte record
 * image are persisted. Zero-based offset and width, in the order they occupy the image:
 *
 * <ul>
 *   <li>{@code DIS-ACCT-GROUP-ID} - alphanumeric, 10 bytes at offset 0, key part 1, mapped to
 *       {@link #getDisAcctGroupId()}</li>
 *   <li>{@code DIS-TRAN-TYPE-CD} - alphanumeric, 2 bytes at offset 10, key part 2, mapped to
 *       {@link #getDisTranTypeCd()}</li>
 *   <li>{@code DIS-TRAN-CAT-CD} - external decimal, 4 bytes at offset 12, key part 3, mapped to
 *       {@link #getDisTranCatCd()}</li>
 *   <li>{@code DIS-INT-RATE} - signed external decimal, 6 bytes at offset 16, four integer digits
 *       and two decimal digits, mapped to {@link #getDisIntRate()}</li>
 * </ul>
 *
 * <p>The three key parts sum to 10 + 2 + 4 = 16 bytes and occupy the leading portion of the record.
 * The record's trailing 28-byte filler begins at offset 22 and is <em>not</em> persisted: it has no
 * field on this class and no column in the schema. Offset arithmetic over the record image belongs
 * exclusively to the fixed-width record mapper {@code DisclosureGroupRecordMapper} in the utility
 * layer; this class carries declared column widths and performs no parsing of its own.
 *
 * <p><strong>Cluster provenance corroborates the component order.</strong> The legacy indexed
 * cluster for this file is defined in job {@code DISCGRP.jcl} with {@code KEYS(16 0)} and
 * {@code RECORDSIZE(50 50)}. A declared key length of 16 beginning at offset 0 is decisive: it
 * confirms both that the key is the leading substring of the record image and that the three
 * components above, in exactly this order, are what make up that substring. The primary-key column
 * order of the {@code disclosure_group} table agrees, giving three independent confirmations of one
 * ordering.
 *
 * <p>Because the key <em>is</em> the leading substring of the record image - the same key-then-data
 * split visible in the file-section layout of the sequential account reader {@code CBACT01C} - the
 * persistent identity of a row is its business key. No surrogate identifier and no provider-assigned
 * identifier is introduced: either would sever the record-image-to-row correspondence that
 * byte-level output parity depends on.
 *
 * <p><strong>Composite identity.</strong> The key is realised as an identifier class,
 * {@link DisclosureGroupId}, bound through {@code @IdClass} rather than as an embedded identifier.
 * The provider matches an identifier class to its entity by field <em>name</em> and field
 * <em>type</em>, so the three key fields declared here are named and typed identically to that
 * class; a divergence in either is a startup failure rather than a silent defect. Component order is
 * contractual and is preserved in the field declaration order below.
 *
 * <p>Three distinct identifier classes exist in this module and they must never be interchanged: the
 * 16-byte key modelled here, the 17-byte key of {@code TransactionCategoryBalance}, and the 6-byte
 * key of {@code TransactionCategory}. Each belongs to exactly one entity.
 *
 * <p><strong>Key values carry significant padding and are never trimmed.</strong> Every key
 * component is a fixed-width field lifted verbatim from the record image, so a value fills its whole
 * declared width and any padding is part of the stored key rather than incidental whitespace. The
 * account group identifier is the case that makes this load-bearing. The seeded reference data
 * contains exactly three group identifiers, each occupying the full ten characters and each present
 * 17 times:
 *
 * <ul>
 *   <li>{@code "A000000000"} - 17 rows, ten characters with no padding</li>
 *   <li>{@code "DEFAULT   "} - 17 rows, the seven characters {@code DEFAULT} followed by three
 *       trailing spaces</li>
 *   <li>{@code "ZEROAPR   "} - 17 rows, the seven characters {@code ZEROAPR} followed by three
 *       trailing spaces</li>
 * </ul>
 *
 * <p>The padded form is the actual key, and a runtime path depends on it. The interest-accrual
 * program {@code CBACT04C} treats a missing disclosure-group record - file status {@code 23} - as
 * recoverable: it substitutes the seven-character default group literal into the ten-byte
 * alphanumeric group-identifier field and re-reads the file with the mutated key. A legacy
 * alphanumeric move into a longer receiving field left-justifies and space-fills, so the retry key
 * is necessarily {@code "DEFAULT   "} at the full ten characters and never the seven-character
 * {@code "DEFAULT"}. Were this class to shorten its values, the retry would compare a seven-
 * character value against a ten-character stored key, match nothing, and the fallback would
 * silently never resolve a rate.
 *
 * <p>Nothing in this class therefore trims, pads, folds case, normalizes or validates a key
 * component - not in a constructor, not in an accessor, and above all not in
 * {@link #equals(Object)} or {@link #hashCode()}. Components are compared byte for byte, so a padded
 * identifier is deliberately unequal to its shortened form, exactly as the two are distinct in the
 * database.
 *
 * <p><strong>Seeded data and the branches it makes reachable.</strong> The reference-data seed loads
 * 51 rows measuring 2,601 bytes at a 50-byte record length, forming the three complete 17-row groups
 * listed above. That composition is what makes both arms of the rate lookup reachable from seeded
 * data alone - the direct group hit and the status-{@code 23} default fallback - without any
 * synthetic fixture. Two further seeded facts matter to the accrual tests: every one of the 50 seeded
 * account rows carries exactly ten spaces in its account group identifier, so seeded data exercises
 * the default-fallback path only; and every rate in the zero-rate group is exactly zero, which makes
 * the accrual program's skip branch reachable, since it computes interest only when the rate is
 * non-zero.
 *
 * <p><strong>No foreign key references this table, by design.</strong> The account table's group
 * identifier is deliberately <em>not</em> a foreign key to this table. It cannot be one: the group
 * identifier is only the first of three key components and is nonunique on its own, recurring once
 * per type/category combination - 17 times per group in the verified reference data. A single-column
 * reference to a partial composite prefix is not expressible as a foreign key, and promoting it to
 * one would fabricate a constraint the legacy design never had and would reject the very
 * unmatched-group case the fallback exists to absorb. Resolution is a runtime lookup with a
 * fallback, not referential integrity. Accordingly this class models no association of any kind, in
 * either direction, and holds no collection.
 *
 * <p><strong>The rate is carried here and computed elsewhere.</strong> The rate column is the only
 * exact numeric column in the schema declared with a precision of six; every other amount column is
 * wider, so its precision must not be copied from a sibling entity. This class is a passive carrier:
 * it performs no arithmetic and applies no scaling. Both belong to the accrual service and to the
 * zoned-decimal codec in the utility layer, which is the single point at which a scale and a
 * truncating policy are applied.
 *
 * <p>That division of responsibility is deliberate, because the arithmetic this rate feeds is
 * order-sensitive. The accrual program derives monthly interest by multiplying the category balance
 * by this rate <em>first</em> and only then dividing the product by the twelve-hundred monthly
 * divisor, storing the result into a two-decimal field. The legacy estate contains no rounding
 * clause anywhere, so that store truncates toward zero rather than rounding. Simplifying the
 * expression by scaling the rate before the multiplication is algebraically identical in exact
 * arithmetic yet moves the point at which truncation occurs, and so changes the resulting cent. The
 * operand order is therefore contractual and is reproduced literally by the service that owns it.
 * For the same reason no fee logic may be invented anywhere in this module: the fee routine invoked
 * alongside interest accrual is an empty routine in the legacy program and is preserved as a
 * documented no-op.
 *
 * <p><strong>Provenance.</strong> Translated from the read-only legacy estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19, which appears in the trailer of the source
 * copybook at line 12. Only member names, field names, widths, offsets and status codes are cited
 * here; no legacy source text is transcribed, and the legacy tree is never copied into this module.
 *
 * @see DisclosureGroupId
 */
@Entity
@Table(name = "disclosure_group")
@IdClass(DisclosureGroupId.class)
public class DisclosureGroup {

    /**
     * Account group identifier - key part 1, 10 bytes at offset 0 of the record image, from
     * {@code DIS-ACCT-GROUP-ID}.
     *
     * <p><strong>Stored verbatim; never trimmed.</strong> Values occupy the full ten characters and
     * their trailing spaces are part of the key, not incidental whitespace. Two of the three seeded
     * group identifiers are padded, and the padded ten-character {@code "DEFAULT   "} form - the
     * seven characters {@code DEFAULT} followed by three spaces - is precisely what the
     * interest-accrual program matches on when it retries a lookup after file status {@code 23},
     * because a legacy alphanumeric move into a longer field left-justifies and space-fills.
     * Shortening this value would break that fallback silently.
     *
     * <p>The column is a bounded variable-length character type rather than a blank-padded one, so
     * that the padding this field carries is stored and compared exactly as written instead of being
     * hidden by implicit padding semantics.
     */
    @Id
    @Column(name = "dis_acct_group_id", length = 10, nullable = false)
    private String disAcctGroupId;

    /**
     * Transaction type code - key part 2, 2 bytes at offset 10 of the record image, from
     * {@code DIS-TRAN-TYPE-CD}. Stored verbatim, with no trimming and no normalization.
     *
     * <p>This is the type code as it appears in the record image. It is not modelled as an
     * association to the transaction-type reference table: the legacy design carries no such
     * constraint, and the value's role here is as a key component of this composite key.
     */
    @Id
    @Column(name = "dis_tran_type_cd", length = 2, nullable = false)
    private String disTranTypeCd;

    /**
     * Transaction category code - key part 3, 4 bytes at offset 12 of the record image, from
     * {@code DIS-TRAN-CAT-CD}. Stored verbatim, with no trimming and no normalization.
     *
     * <p>The legacy field is a zero-filled external-decimal field, yet it is carried as text and
     * mapped to a bounded character column because its external width and its leading zeros are
     * contractual. A category code of {@code "0005"} must remain {@code "0005"}: a numeric type
     * would render it as {@code 5}, and the sixteen-byte key image would then no longer reconstruct
     * from the row. As with the type code, this is a key component and not an association to the
     * transaction-category reference table.
     */
    @Id
    @Column(name = "dis_tran_cat_cd", length = 4, nullable = false)
    private String disTranCatCd;

    /**
     * Disclosure interest rate - 6 bytes at offset 16 of the record image, from
     * {@code DIS-INT-RATE}, a signed external-decimal field of four integer digits and two decimal
     * digits, mapped to an exact numeric column of precision six and scale two.
     *
     * <p>This is the only exact numeric column in the schema declared with a precision of six. Every
     * other amount column in the module is wider, so this precision must not be copied from a
     * sibling entity, and an exact decimal type is mandatory: an approximate binary numeric type
     * would not reproduce the legacy decimal representation and is prohibited throughout the
     * migration.
     *
     * <p><strong>Carried, never computed.</strong> This class applies no scaling and performs no
     * arithmetic on the rate. Scaling is the sole responsibility of the zoned-decimal codec in the
     * utility layer, which applies one truncating policy uniformly so that no other component can
     * introduce a different one. The field is left unset by the no-argument constructor rather than
     * seeded with a zero, so an unpopulated instance is distinguishable from a genuine zero rate -
     * and genuine zero rates do occur, in the seeded zero-rate group.
     *
     * <p><strong>The arithmetic this rate feeds is order-sensitive.</strong> The accrual program
     * multiplies the category balance by this rate first and only then divides the product by the
     * twelve-hundred monthly divisor, storing into a two-decimal field. Because the legacy estate
     * declares no rounding clause anywhere, that store truncates toward zero. Pre-scaling the rate
     * before the multiplication is algebraically identical in exact arithmetic but moves the
     * truncation point and changes the resulting cent, so the operand order is contractual and is
     * reproduced literally by the accrual service that owns the computation. Interest is computed
     * only when this rate is non-zero. No fee logic may be invented: the fee routine invoked
     * alongside accrual is empty in the legacy program and is preserved as a documented no-op.
     */
    @Column(name = "dis_int_rate", precision = 6, scale = 2, nullable = false)
    private BigDecimal disIntRate;

    /**
     * Creates an empty instance. This constructor exists for the persistence provider, which
     * requires a no-argument constructor in order to materialise an entity before populating its
     * state; it is also why this type is a plain mutable class rather than a record. Application and
     * test code should use {@link #DisclosureGroup(String, String, String, BigDecimal)}.
     */
    protected DisclosureGroup() {
        // Intentionally empty: the provider assigns state after construction.
    }

    /**
     * Creates a fully populated disclosure group from its three key components, in the contractual
     * order in which they occupy the record image, followed by the rate.
     *
     * <p>Every argument is stored exactly as supplied. Nothing is trimmed, padded, folded, scaled or
     * validated here, because the fixed-width padding on the key components and the scale on the
     * rate are both part of the persisted contract. In particular the account group identifier is
     * expected at its full ten characters, including any trailing spaces - the padded default group
     * identifier used by the accrual fallback is exactly such a value.
     *
     * <p>Fields are assigned directly rather than through the accessors below, so that no overridable
     * method is invoked while construction is still in progress.
     *
     * @param disAcctGroupId account group identifier, key part 1, 10 bytes at offset 0, stored
     *                       verbatim including trailing spaces
     * @param disTranTypeCd  transaction type code, key part 2, 2 bytes at offset 10, stored verbatim
     * @param disTranCatCd   transaction category code, key part 3, 4 bytes at offset 12, stored
     *                       verbatim including leading zeros
     * @param disIntRate     disclosure interest rate at scale two, stored verbatim with no rescaling
     */
    public DisclosureGroup(String disAcctGroupId,
                           String disTranTypeCd,
                           String disTranCatCd,
                           BigDecimal disIntRate) {
        this.disAcctGroupId = disAcctGroupId;
        this.disTranTypeCd = disTranTypeCd;
        this.disTranCatCd = disTranCatCd;
        this.disIntRate = disIntRate;
    }

    /**
     * Returns the account group identifier exactly as stored, at its full declared width and with
     * any trailing spaces intact.
     *
     * @return the 10-byte account group identifier, unmodified
     */
    public String getDisAcctGroupId() {
        return disAcctGroupId;
    }

    /**
     * Replaces the account group identifier. The argument is assigned as supplied: the value is not
     * trimmed, padded, folded or validated, because its padding is part of the key.
     *
     * @param disAcctGroupId the 10-byte account group identifier to store verbatim
     */
    public void setDisAcctGroupId(String disAcctGroupId) {
        this.disAcctGroupId = disAcctGroupId;
    }

    /**
     * Returns the transaction type code exactly as stored.
     *
     * @return the 2-byte transaction type code, unmodified
     */
    public String getDisTranTypeCd() {
        return disTranTypeCd;
    }

    /**
     * Replaces the transaction type code. The argument is assigned as supplied, with no
     * normalization of any kind.
     *
     * @param disTranTypeCd the 2-byte transaction type code to store verbatim
     */
    public void setDisTranTypeCd(String disTranTypeCd) {
        this.disTranTypeCd = disTranTypeCd;
    }

    /**
     * Returns the transaction category code exactly as stored, with its leading zeros intact.
     *
     * @return the 4-byte transaction category code, unmodified
     */
    public String getDisTranCatCd() {
        return disTranCatCd;
    }

    /**
     * Replaces the transaction category code. The argument is assigned as supplied, so leading zeros
     * survive and the external width is preserved.
     *
     * @param disTranCatCd the 4-byte transaction category code to store verbatim
     */
    public void setDisTranCatCd(String disTranCatCd) {
        this.disTranCatCd = disTranCatCd;
    }

    /**
     * Returns the disclosure interest rate exactly as stored, at the scale it was given. The value is
     * not rescaled on the way out, so a genuine zero rate is returned at its stored scale rather than
     * normalized.
     *
     * @return the disclosure interest rate, unmodified, or {@code null} on an unpopulated instance
     */
    public BigDecimal getDisIntRate() {
        return disIntRate;
    }

    /**
     * Replaces the disclosure interest rate. The argument is assigned as supplied: it is not
     * rescaled, rounded, clamped or range-checked here. Scaling is applied once, in the
     * zoned-decimal codec of the utility layer, and no range constraint is imposed because genuine
     * zero rates occur in the reference data and the accrual program depends on them loading.
     *
     * @param disIntRate the disclosure interest rate to store verbatim at scale two
     */
    public void setDisIntRate(BigDecimal disIntRate) {
        this.disIntRate = disIntRate;
    }

    /**
     * Returns this row's composite key as a single addressable value, built from the three key
     * components in their contractual order.
     *
     * <p>A new key is produced on each call and the components are copied by reference exactly as
     * stored, so padding and leading zeros are carried through unchanged. No instance is retained or
     * shared between calls.
     *
     * @return a key equal to the identity of this row
     */
    public DisclosureGroupId toId() {
        return new DisclosureGroupId(disAcctGroupId, disTranTypeCd, disTranCatCd);
    }

    /**
     * Compares this row with another by its three key components only, in declaration order, byte
     * for byte.
     *
     * <p>The rate is deliberately excluded. Equality follows persistent identity, and the rate is
     * mutable state: including it would let an instance change its own equality and hash across a
     * flush, which would corrupt any set or map that had already stored it.
     *
     * <p>No component is trimmed, padded or case folded before comparison. That is intentional: a
     * padded ten-character group identifier is a different key from its shortened form and must
     * compare unequal in Java exactly as the two remain distinct rows in the database. Collapsing
     * them would also make the status-{@code 23} fallback appear to resolve keys that it does not.
     *
     * @param o the object to compare against
     * @return {@code true} only if {@code o} is a {@code DisclosureGroup} whose three key components
     *         each equal this row's corresponding component
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DisclosureGroup other)) {
            return false;
        }
        return Objects.equals(this.disAcctGroupId, other.disAcctGroupId)
                && Objects.equals(this.disTranTypeCd, other.disTranTypeCd)
                && Objects.equals(this.disTranCatCd, other.disTranCatCd);
    }

    /**
     * Hashes the three key components, in declaration order, from their stored values so that the
     * hash agrees with {@link #equals(Object)} for padded and shortened values alike. The rate is
     * excluded for the same reason it is excluded from equality.
     *
     * @return the hash code of this row's key
     */
    @Override
    public int hashCode() {
        return Objects.hash(disAcctGroupId, disTranTypeCd, disTranCatCd);
    }

    /**
     * Returns a diagnostic rendering of the three key components and nothing else. Each value is
     * quoted and printed as stored, so significant trailing spaces stay visible in logs and in
     * assertion failures.
     *
     * <p>The rate is deliberately omitted: it is financial data and has no place in an incidental
     * diagnostic rendering.
     *
     * @return a diagnostic string containing the three key components
     */
    @Override
    public String toString() {
        return "DisclosureGroup[disAcctGroupId='" + disAcctGroupId
                + "', disTranTypeCd='" + disTranTypeCd
                + "', disTranCatCd='" + disTranCatCd + "']";
    }
}
