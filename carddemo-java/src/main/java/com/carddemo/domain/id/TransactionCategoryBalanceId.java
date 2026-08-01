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
package com.carddemo.domain.id;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary-key type for the {@code TransactionCategoryBalance} entity, which references
 * this class through its {@code @IdClass} declaration.
 *
 * <p>This class deliberately carries no annotation of any kind. The {@code @IdClass} declaration,
 * the {@code @Id} markers and every column mapping live on the owning entity, which leaves this
 * type a plain serializable value object with no persistence-provider coupling.
 *
 * <p><strong>Legacy provenance.</strong> Derived from copybook {@code CVTRA01Y} of the AWS CardDemo mainframe estate, which describes
 * a 50-byte transaction-category-balance record whose leading key group occupies 17 bytes starting
 * at offset 0. That layout is corroborated twice over: the {@code TCATBALF} VSAM KSDS definition
 * declares {@code KEYS(17 0)} and {@code RECORDSIZE(50 50)} over an indexed cluster, and the file
 * description in batch program {@code CBACT04C} splits the same 50-byte image into a 17-byte key
 * group followed by a 33-byte data area.
 *
 * <p>Because the key sits at offset 0 it <em>is</em> the leading substring of the record image, so
 * the persistent identity of the entity is this legacy business key. No surrogate identifier is
 * introduced anywhere, since a surrogate would break the record-image-to-table-row correspondence
 * that byte-level output parity depends on.
 *
 * <p><strong>Key components.</strong> The key is the ordered concatenation of three fixed-width
 * fields, each documented on its own attribute below: an 11-byte account identifier at offset 0, a
 * 2-byte transaction type code at offset 11 and a 4-byte transaction category code at offset 13. The
 * three widths sum to the declared 17-byte key length, and the declaration order is contractual - fixed
 * by the copybook, by the key offset in the cluster definition and by the primary-key column order of
 * the schema. The two remaining fields of the 50-byte record are intentionally absent from this class:
 * the 11-byte category balance at offset 17 is a non-key attribute of the entity, and the 22-byte
 * trailing filler at offset 28 is not persisted at all.
 *
 * <p><strong>The {@code TRAN-CAT-KEY} name collision.</strong> Copybook {@code CVTRA01Y} and copybook {@code CVTRA04Y} both name their key group
 * {@code TRAN-CAT-KEY}, yet the two keys are entirely unrelated. This key is 17 bytes wide and
 * leads with the account identifier; the transaction-category key described by {@code CVTRA04Y} is
 * 6 bytes wide, is declared with {@code KEYS(6 0)}, carries no account identifier at all and uses
 * different field names. The 6-byte key is therefore <em>not</em> a prefix of this key, the two are
 * backed by different tables, and they are modelled as two deliberately unrelated Java types that
 * share no supertype beyond {@link Object}.
 *
 * <p>The shared legacy group name is a source-level coincidence and must never be taken as licence to
 * merge, reuse, subclass or cross-reference the two key definitions. Decision log entry D-37 records
 * the collision so the distinction remains auditable.
 *
 * <p><strong>Why every component is a {@code String}.</strong> Two of the three components are digit-only fields in the legacy layout, yet all three are
 * modelled as {@link String} and stored in bounded {@code VARCHAR} columns, because their external
 * text representation is contractual rather than incidental. Leading zeros and exact field widths
 * carry meaning: a category code of {@code 0005} must remain four characters and must never
 * collapse to {@code 5}, and an account identifier of {@code 00000000001} must remain eleven
 * characters. Modelling either component as a numeric type would silently discard that padding.
 *
 * <p>For the same reason nothing in this class trims, pads, folds case or otherwise normalises a
 * component value &mdash; not the constructors, not the accessors, and above all not
 * {@link #equals(Object)} or {@link #hashCode()}, where normalisation would make two distinct
 * database rows compare equal and corrupt the persistence-context identity map.
 *
 * <p><strong>Instances are effectively immutable.</strong> The attributes cannot be declared
 * {@code final} because a persistence provider requires a
 * no-argument constructor to instantiate an id class, and such a constructor cannot initialise
 * final fields. Immutability is therefore achieved by exposing accessors only and declaring no
 * mutator, which also makes instances safe to share across threads and safe to use as map keys.
 *
 * @since 1.0.0
 */
public class TransactionCategoryBalanceId implements Serializable {

    /**
     * Explicit serialization version.
     *
     * <p>This declaration is mandatory rather than decorative. The module compiles with
     * {@code -Xlint:all -Werror}, which promotes the missing-{@code serialVersionUID} warning on a
     * serializable class into a build failure, so the field is declared explicitly instead of being
     * suppressed or left to the compiler's default computation.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Account identifier component: 11 bytes at offset 0 of the key, column
     * {@code trancat_acct_id}. Retained as text so leading zeros survive intact.
     */
    private String trancatAcctId;

    /**
     * Transaction type code component: 2 bytes at offset 11 of the key, column
     * {@code trancat_type_cd}.
     */
    private String trancatTypeCd;

    /**
     * Transaction category code component: 4 bytes at offset 13 of the key, column
     * {@code trancat_cd}. Retained as text so leading zeros survive intact.
     */
    private String trancatCd;

    /**
     * Creates an empty key.
     *
     * <p>Required because a JPA provider instantiates an id class reflectively before populating
     * its fields. It is the reason this type is a class rather than a record: a record cannot offer
     * a no-argument constructor. Application code should prefer
     * {@link #TransactionCategoryBalanceId(String, String, String)}.
     */
    protected TransactionCategoryBalanceId() {
        // Intentionally empty: the provider assigns each component after construction.
    }

    /**
     * Creates a fully populated key from its three components.
     *
     * <p>The parameter order matches the contractual key order &mdash; account identifier, then
     * transaction type code, then transaction category code. Values are stored exactly as supplied;
     * no trimming, padding, case folding or validation is applied, because the caller is
     * responsible for presenting values at their legacy widths.
     *
     * @param trancatAcctId account identifier component, 11 bytes in the legacy layout
     * @param trancatTypeCd transaction type code component, 2 bytes in the legacy layout
     * @param trancatCd     transaction category code component, 4 bytes in the legacy layout
     */
    public TransactionCategoryBalanceId(String trancatAcctId, String trancatTypeCd,
            String trancatCd) {
        this.trancatAcctId = trancatAcctId;
        this.trancatTypeCd = trancatTypeCd;
        this.trancatCd = trancatCd;
    }

    /**
     * Returns the account identifier component exactly as stored, without trimming or padding.
     *
     * @return the account identifier component, possibly {@code null}
     */
    public String getTrancatAcctId() {
        return trancatAcctId;
    }

    /**
     * Returns the transaction type code component exactly as stored, without trimming or padding.
     *
     * @return the transaction type code component, possibly {@code null}
     */
    public String getTrancatTypeCd() {
        return trancatTypeCd;
    }

    /**
     * Returns the transaction category code component exactly as stored, without trimming or
     * padding.
     *
     * @return the transaction category code component, possibly {@code null}
     */
    public String getTrancatCd() {
        return trancatCd;
    }

    /**
     * Compares this key with another for equality across all three components.
     *
     * <p>Every component participates. Omitting any one of them would make two distinct rows
     * compare equal and corrupt the persistence-context identity map. Comparison is exact: no
     * component is trimmed, padded or case folded first, so {@code "0005"} and {@code "5"} are
     * different category codes, as the fixed-width legacy layout requires.
     *
     * @param o the object to compare with, which may be {@code null}
     * @return {@code true} only if {@code o} is a {@code TransactionCategoryBalanceId} whose three
     *         components are all equal to this key's components
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategoryBalanceId other)) {
            return false;
        }
        return Objects.equals(this.trancatAcctId, other.trancatAcctId)
                && Objects.equals(this.trancatTypeCd, other.trancatTypeCd)
                && Objects.equals(this.trancatCd, other.trancatCd);
    }

    /**
     * Returns a hash code derived from all three components in their contractual order, keeping
     * this type consistent with {@link #equals(Object)} and usable as a hash-map key.
     *
     * @return the hash code for this key
     */
    @Override
    public int hashCode() {
        return Objects.hash(trancatAcctId, trancatTypeCd, trancatCd);
    }

    /**
     * Returns a diagnostic representation listing the three key components and nothing else.
     *
     * <p>The key holds no credential and no monetary amount, so no value requires redaction.
     *
     * @return a string representation of this key
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalanceId[trancatAcctId=" + trancatAcctId
                + ", trancatTypeCd=" + trancatTypeCd
                + ", trancatCd=" + trancatCd
                + "]";
    }
}
