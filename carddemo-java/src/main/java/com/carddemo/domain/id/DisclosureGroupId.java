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
 * Composite primary-key type for the disclosure-group entity, bound to it through
 * {@code @IdClass}. This class deliberately carries no persistence annotation of its own:
 * all mapping metadata lives on the owning entity, and this type exists only to represent
 * the three-part business key as a single addressable value for repository lookups.
 *
 * <p><strong>Legacy provenance.</strong> Derived from the disclosure-group copybook
 * {@code CVTRA02Y}, whose header declares a record length of 50 and whose key group
 * {@code DIS-GROUP-KEY} spans 16 bytes beginning at offset 0. The key length and offset are
 * corroborated independently by the disclosure-group VSAM KSDS cluster definition in
 * {@code DISCGRP.jcl}, which specifies {@code KEYS(16 0)} and {@code RECORDSIZE(50 50)} on an
 * {@code INDEXED} cluster, and by the file-section layout of the interest-calculation program
 * {@code CBACT04C} at lines 79 to 81, where the same three components appear in the same order
 * with the same widths.
 *
 * <p><strong>Key components, in contractual declaration order.</strong> The order below is not
 * a stylistic choice: it is fixed by the record image itself, and is confirmed three ways - by
 * the copybook, by {@code KEYS(16 0)} naming the key as the leading substring of the record, and
 * by the primary-key column order of the {@code disclosure_group} table.
 *
 * <ul>
 *   <li>{@code disAcctGroupId} - account group identifier, alphanumeric,
 *       10 bytes at offset 0, from {@code DIS-ACCT-GROUP-ID}</li>
 *   <li>{@code disTranTypeCd} - transaction type code, alphanumeric,
 *       2 bytes at offset 10, from {@code DIS-TRAN-TYPE-CD}</li>
 *   <li>{@code disTranCatCd} - transaction category code, external decimal,
 *       4 bytes at offset 12, from {@code DIS-TRAN-CAT-CD}</li>
 * </ul>
 *
 * <p>10 + 2 + 4 = 16, matching the declared key length exactly. The two remaining fields of the
 * record are deliberately absent from this type: the interest rate occupies 6 bytes at offset 16
 * and belongs to the entity as a scaled decimal, and the trailing 28-byte filler at offset 22 is
 * not persisted at all.
 *
 * <p>Because the key is the leading substring of the record image, the persistent identity of a
 * disclosure-group row <em>is</em> this business key. No surrogate identifier is introduced here
 * or on the owning entity; a generated key would sever the record-image-to-row correspondence
 * that byte-level output parity depends on.
 *
 * <p><strong>Fixed-width values: trailing spaces are significant and are never trimmed.</strong>
 * Every component is a fixed-width field taken verbatim from the legacy record image, so a value
 * occupies the whole field width and any padding is part of the stored key rather than incidental
 * whitespace. The account group identifier is the case that makes this load-bearing. The
 * interest-calculation program reacts to a missing disclosure-group record - file status
 * {@code 23} - by substituting a 7-character default group literal into the 10-byte alphanumeric
 * group-identifier field and re-reading the file with the mutated key. A legacy alphanumeric move
 * left-justifies and space-pads to the width of the receiving field, so the retry key is
 * {@code "DEFAULT   "} at the full ten characters and never the 7-character {@code "DEFAULT"}.
 * The seeded reference data agrees: the three group identifiers present all occupy exactly ten
 * characters, two of them padded with trailing spaces.
 *
 * <p>Consequently this type performs <em>no</em> trimming, stripping, padding, case folding,
 * normalization or validation - not in its constructors, not in its accessors, and above all not
 * in {@link #equals(Object)} or {@link #hashCode()}. Components are compared byte for byte.
 * Trimming here would make {@code "DEFAULT"} and {@code "DEFAULT   "} compare equal in Java while
 * they remain distinct rows in the database, which would corrupt the persistence identity map and
 * would silently prevent the status-{@code 23} fallback from ever resolving a row.
 *
 * <p><strong>Why every component is a {@link String}.</strong> External representation is part of
 * the contract, so each component is mapped to a bounded character column rather than to a
 * numeric type: the category code is a zero-filled 4-byte external-decimal field, and a value of
 * {@code "0005"} must stay {@code "0005"} rather than collapsing to {@code 5}. Numeric components
 * would additionally discard the width and padding that the fallback path above depends on. No
 * decimal type appears in this class; the disclosure rate is a scaled decimal on the entity.
 *
 * <p>Instances are effectively immutable: the fields are assigned once and only accessors are
 * exposed. The no-argument constructor exists solely because the persistence provider
 * instantiates an {@code @IdClass} reflectively, which is also why this type is a plain class
 * rather than a record.
 */
public class DisclosureGroupId implements Serializable {

    /**
     * Explicit serialization identity. This is not optional hygiene: the module compiles with
     * {@code -Xlint:all -Werror}, which promotes a missing serial version identifier on a
     * serializable class to a build failure.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Account group identifier - 10 bytes at offset 0 of the record image. Stored verbatim,
     * including any trailing spaces that pad the value to the full field width.
     */
    private String disAcctGroupId;

    /**
     * Transaction type code - 2 bytes at offset 10 of the record image. Stored verbatim.
     */
    private String disTranTypeCd;

    /**
     * Transaction category code - 4 bytes at offset 12 of the record image. Retained as text so
     * that its leading zeros survive the round trip. Stored verbatim.
     */
    private String disTranCatCd;

    /**
     * Creates an empty key. Required because the persistence provider instantiates an
     * {@code @IdClass} reflectively before populating it; application code should use
     * {@link #DisclosureGroupId(String, String, String)} instead.
     */
    protected DisclosureGroupId() {
        // Intentionally empty: the provider assigns the components after construction.
    }

    /**
     * Creates a fully populated key from the three components, in the contractual order in which
     * they appear in the record image.
     *
     * <p>Arguments are stored exactly as supplied. Nothing is trimmed, padded, folded or
     * validated, because the fixed-width padding carried by these values is part of the stored
     * key - most notably the ten-character, space-padded default account group identifier
     * produced by the interest-calculation fallback path.
     *
     * @param disAcctGroupId account group identifier, 10 bytes at offset 0, stored verbatim
     * @param disTranTypeCd  transaction type code, 2 bytes at offset 10, stored verbatim
     * @param disTranCatCd   transaction category code, 4 bytes at offset 12, stored verbatim
     */
    public DisclosureGroupId(String disAcctGroupId, String disTranTypeCd, String disTranCatCd) {
        this.disAcctGroupId = disAcctGroupId;
        this.disTranTypeCd = disTranTypeCd;
        this.disTranCatCd = disTranCatCd;
    }

    /**
     * Returns the account group identifier exactly as stored, trailing spaces included.
     *
     * @return the 10-byte account group identifier, unmodified
     */
    public String getDisAcctGroupId() {
        return disAcctGroupId;
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
     * Returns the transaction category code exactly as stored, leading zeros included.
     *
     * @return the 4-byte transaction category code, unmodified
     */
    public String getDisTranCatCd() {
        return disTranCatCd;
    }

    /**
     * Compares all three key components byte for byte. Every component participates: omitting one
     * would make two distinct rows indistinguishable in Java and corrupt the persistence identity
     * map.
     *
     * <p>No component is trimmed, padded or case folded before comparison, so a ten-character
     * space-padded group identifier is deliberately <em>not</em> equal to its unpadded form.
     *
     * @param o the object to compare against
     * @return {@code true} only if {@code o} is a {@code DisclosureGroupId} whose three components
     *         are each equal to this key's corresponding component
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DisclosureGroupId other)) {
            return false;
        }
        return Objects.equals(this.disAcctGroupId, other.disAcctGroupId)
                && Objects.equals(this.disTranTypeCd, other.disTranTypeCd)
                && Objects.equals(this.disTranCatCd, other.disTranCatCd);
    }

    /**
     * Hashes all three components, in declaration order, from their untrimmed values so that the
     * hash agrees with {@link #equals(Object)} for padded and unpadded values alike.
     *
     * @return the hash code of this key
     */
    @Override
    public int hashCode() {
        return Objects.hash(disAcctGroupId, disTranTypeCd, disTranCatCd);
    }

    /**
     * Returns a diagnostic rendering of the three components and nothing else. Each value is
     * quoted and printed untrimmed so that significant trailing spaces remain visible in logs and
     * assertion failures. No credential and no monetary value is carried by this key, so nothing
     * requires redaction.
     *
     * @return a diagnostic string containing the three key components
     */
    @Override
    public String toString() {
        return "DisclosureGroupId[disAcctGroupId='" + disAcctGroupId
                + "', disTranTypeCd='" + disTranTypeCd
                + "', disTranCatCd='" + disTranCatCd + "']";
    }
}
