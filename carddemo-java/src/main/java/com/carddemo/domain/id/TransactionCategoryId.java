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
 * Composite primary-key class for the {@code TransactionCategory} entity, which declares this type
 * as its identifier class.
 *
 * <p>The declaration that binds this class to its entity lives on the entity and never here.
 * Consequently this type carries no persistence annotation of any kind, depends on nothing above
 * itself in the layering, and imports nothing outside {@code java.base}. It is a plain serializable
 * value holder, and deliberately a class rather than a compact data carrier: an identifier class
 * must offer a no-argument constructor for the persistence provider to instantiate, which a compact
 * data carrier cannot supply.
 *
 * <p><strong>Legacy provenance.</strong> Derived from copybook member {@code CVTRA04Y}, the
 * transaction-category structure, whose record length is 60 and whose key group occupies 6 bytes at
 * offset 0. That key geometry is corroborated independently by the {@code TRANCATG} VSAM KSDS
 * cluster definition in the batch job library, which declares {@code KEYS(6 0)} together with
 * {@code RECORDSIZE(60 60)} on an {@code INDEXED} cluster, and a third time by the primary-key
 * definition of the {@code transaction_category} table in the schema migration. Because the key
 * offset is 0, the key is the leading substring of the stored image - the same layout the
 * sequential batch programs model when they split a fixed-length image into a leading key field
 * followed by a data remainder. The identifier therefore <em>is</em> the legacy business key: no
 * surrogate and no machine-assigned substitute of any kind is introduced, because a surrogate would
 * break the image-to-row correspondence on which byte-parity verification of the migrated output
 * depends.
 *
 * <p><strong>Components, in contractual declaration order.</strong>
 * <ul>
 *   <li>{@link #getTranTypeCd() tranTypeCd} - legacy field {@code TRAN-TYPE-CD}, a 2-byte
 *       alphanumeric field at offset 0, mapped to schema column {@code tran_type_cd} declared
 *       {@code VARCHAR(2) NOT NULL}.</li>
 *   <li>{@link #getTranCatCd() tranCatCd} - legacy field {@code TRAN-CAT-CD}, a 4-digit numeric
 *       field held as text at offset 2, mapped to schema column {@code tran_cat_cd} declared
 *       {@code VARCHAR(4) NOT NULL}.</li>
 * </ul>
 * The two widths sum to the declared key length of 6. Declaration order is contractual - it is
 * fixed independently by the cluster definition's key geometry and by the primary-key column order
 * in the schema migration - and must not be reordered. The 50-byte description field and the 4-byte
 * trailing filler that complete the 60-byte layout are not part of the key; they belong to the
 * entity, not to this class.
 *
 * <p><strong>Both components are text, deliberately.</strong> The schema maps every digit-only
 * legacy field to a bounded {@code VARCHAR(n)} rather than to a numeric column, because leading
 * zeros and external text widths are contractual. The 4-digit category component is the clearest
 * case: a category of {@code "0005"} must round-trip as {@code "0005"} and must never be narrowed to
 * a value that would render as {@code 5}. Neither component is therefore an integral or a decimal
 * numeric type, and this class performs no numeric conversion, no whitespace normalisation, no
 * padding adjustment and no case folding anywhere - values are stored, compared and returned exactly
 * as supplied.
 *
 * <p><strong>Name-collision warning.</strong> The key group of {@code CVTRA04Y} shares its COBOL
 * group name, {@code TRAN-CAT-KEY}, with the key group of the separate transaction-category-balance
 * member {@code CVTRA01Y}, whose key is 17 bytes long and whose cluster definition declares
 * {@code KEYS(17 0)}. The two are entirely unrelated keys that merely happen to carry the same group
 * name in the legacy source. This 6-byte key is <em>not</em> a prefix, sub-key or reusable fragment
 * of the 17-byte one: the 17-byte form leads with an 11-digit account identifier, as the
 * interest-calculation batch program's file description confirms, so the two layouts do not align at
 * any offset and their component name prefixes differ as well. The category-balance key is modelled
 * by its own separate composite-key class in this package; the two classes are deliberately
 * unrelated, share no supertype beyond {@link Object}, and neither references the other. No shared
 * base class, interface or helper exists or may be introduced to "reuse" the overlapping components.
 * This collision is also recorded in the project decision log.
 *
 * <p><strong>Not to be confused with the transaction-type reference table.</strong> The
 * {@code transaction_type} table is a different table whose primary key is a single 2-byte column,
 * and it has no composite-key class at all - not in this package and not anywhere. Its copybook
 * member {@code CVTRA03Y} declares that key as a bare 2-byte field which is not nested inside any
 * key group, and its cluster definition declares {@code KEYS(2 0)}. Its single-column primary key is
 * also named differently from this key's 2-byte component, so the two must never be conflated. Of
 * the two reference tables, only the transaction-category table takes a composite key, and this
 * class is that key.
 *
 * <p><strong>Provenance.</strong> Translated from the read-only legacy estate at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
public class TransactionCategoryId implements Serializable {

    /**
     * Explicit serialization version identifier.
     *
     * <p>Declaring it is a compile requirement here rather than optional hygiene: the module builds
     * under {@code -Xlint:all} together with {@code -Werror} at language release 25, which promotes
     * the serialization warning that javac emits for a serializable class lacking an explicit
     * version identifier into an outright build failure. It is therefore never omitted and never
     * suppressed.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Transaction type code - legacy field {@code TRAN-TYPE-CD}, a 2-byte alphanumeric field at
     * offset 0 of the 6-byte key, mapped to schema column {@code tran_type_cd}.
     *
     * <p>Not declared {@code final}, because the no-argument constructor the persistence provider
     * requires cannot initialise a final field. Immutability is preserved instead by exposing a
     * getter and deliberately no setter.
     */
    private String tranTypeCd;

    /**
     * Transaction category code - legacy field {@code TRAN-CAT-CD}, a 4-digit numeric field held as
     * text at offset 2 of the 6-byte key, mapped to schema column {@code tran_cat_cd}. Held as text
     * so that leading zeros survive intact.
     *
     * <p>Not declared {@code final}, for the same reason as the preceding component.
     */
    private String tranCatCd;

    /**
     * Creates an empty key.
     *
     * <p>Required by the persistence provider, which instantiates an identifier class through its
     * no-argument constructor and then populates the component fields. That instantiation is the
     * provider's own concern; this class introspects nothing itself, so the module's zero budget for
     * low-level introspection is unaffected.
     *
     * <p>Declared {@code protected} rather than {@code public} because application code should
     * always build a fully populated key through {@link #TransactionCategoryId(String, String)}; the
     * provider reaches this constructor regardless of its access level.
     */
    protected TransactionCategoryId() {
        // Intentionally empty: both components are populated by the provider after construction.
    }

    /**
     * Creates a fully populated key.
     *
     * <p>Parameter order is the contractual key order and must not be changed: the 2-byte type code
     * precedes the 4-digit category code, as fixed independently by the legacy key geometry and by
     * the primary-key column order in the schema migration.
     *
     * <p>Both values are stored exactly as supplied. No validation, normalisation, padding or width
     * enforcement is applied, because the persisted widths are enforced by the schema and because
     * silently altering a caller's value would change lookup semantics.
     *
     * @param tranTypeCd the 2-byte transaction type code, at offset 0 of the key
     * @param tranCatCd  the 4-digit transaction category code held as text, at offset 2 of the key
     */
    public TransactionCategoryId(final String tranTypeCd, final String tranCatCd) {
        this.tranTypeCd = tranTypeCd;
        this.tranCatCd = tranCatCd;
    }

    /**
     * Returns the transaction type code component.
     *
     * @return the 2-byte transaction type code at offset 0 of the key, exactly as supplied, or
     *         {@code null} if this key has not been populated
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * Returns the transaction category code component.
     *
     * @return the 4-digit transaction category code at offset 2 of the key, held as text and
     *         returned exactly as supplied so that leading zeros are preserved, or {@code null} if
     *         this key has not been populated
     */
    public String getTranCatCd() {
        return tranCatCd;
    }

    /**
     * Compares both key components for equality.
     *
     * <p>Both components participate. Omitting either would let two distinct table rows compare
     * equal in Java and would corrupt the persistence context's identity map.
     *
     * <p>Comparison is exact: no normalisation, padding adjustment or case folding is applied,
     * because the legacy widths are fixed and every character of a component is significant. A
     * category component of {@code "0005"} is consequently not equal to one of {@code "5"}.
     *
     * @param o the object to compare with, which may be {@code null}
     * @return {@code true} only if {@code o} is a key of this type whose components both match
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategoryId other)) {
            return false;
        }
        return Objects.equals(tranTypeCd, other.tranTypeCd)
                && Objects.equals(tranCatCd, other.tranCatCd);
    }

    /**
     * Returns a hash code derived from both components in contractual declaration order, consistent
     * with {@link #equals(Object)}.
     *
     * @return the hash code for this key
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranTypeCd, tranCatCd);
    }

    /**
     * Returns a diagnostic representation containing both key components and nothing else.
     *
     * <p>Neither a credential nor a monetary value forms part of this key, so no component requires
     * redaction.
     *
     * @return a diagnostic representation of this key
     */
    @Override
    public String toString() {
        return "TransactionCategoryId[tranTypeCd=" + tranTypeCd
                + ", tranCatCd=" + tranCatCd + "]";
    }
}
