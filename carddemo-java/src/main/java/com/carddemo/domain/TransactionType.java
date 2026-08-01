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
 * Transaction-type reference data, mapped to the {@code transaction_type} table.
 *
 * <p>The Java translation of the legacy {@code TRAN-TYPE-RECORD} structure declared in copybook
 * {@code CVTRA03Y}, whose record length is 60 bytes. Only 52 of those bytes carry information - a
 * 2-byte type code at offset 0 followed by a 50-byte description at offset 2 - so the entity has
 * exactly two mapped attributes. It is the smallest of the eleven entities in this package: a
 * two-column reference table with a single-column primary key, no composite key, no amount or rate
 * attribute, no version attribute and no association in either direction.
 *
 * <p><strong>Legacy provenance.</strong> The record lives in the {@code TRANTYPE} VSAM key-sequenced
 * base cluster, whose definition declares {@code KEYS(2 0)} with {@code RECORDSIZE(60 60)} on an
 * {@code INDEXED} cluster. A key width of 2 at offset 0 means the key is the leading substring of the
 * stored image, and identical low and high record sizes confirm a fixed-length layout. Every geometric
 * fact this class relies on is corroborated three times independently: by the copybook field
 * declarations, by the cluster definition, and by the schema migration that annotates each column with
 * the offset and width recomputed from the copybook.
 *
 * <p><strong>The identifier is the legacy business key, never a surrogate.</strong> The sequential
 * batch programs model every fixed-length record as a leading key field followed by a data remainder,
 * and this entity follows the same discipline: the 2-byte type code at offset 0 <em>is</em> the
 * identifier. No generated value, sequence, table generator or machine-assigned substitute appears
 * here or anywhere in the schema, because a surrogate would break the record-image-to-row
 * correspondence on which byte-parity verification of the fixed-width output depends.
 *
 * <p><strong>Not to be confused with the transaction-category entity.</strong> The two are the easiest
 * pair in the estate to conflate - both are 60-byte reference records carrying a 50-character
 * description - yet their keys are entirely different and never interchangeable. This entity derives
 * from {@code CVTRA03Y} and takes a <strong>single-column, 2-byte</strong> primary key, corroborated
 * by {@code KEYS(2 0)} on the {@code TRANTYPE} cluster, its key field a bare field rather than one
 * nested inside a key group. The category entity derives from {@code CVTRA04Y} and takes a
 * <strong>two-component, 6-byte composite</strong> key - a 2-byte type code at offset 0 plus a 4-byte
 * category code at offset 2 - corroborated by {@code KEYS(6 0)} on the {@code TRANCATG} cluster, and
 * it is the only one of the two that declares an identifier class. Consequently this class declares
 * exactly one identifier attribute, no identifier class of any kind, and references nothing in the
 * {@code com.carddemo.domain.id} package. The reciprocal warning is recorded on the composite key
 * class, so the distinction is documented from both sides.
 *
 * <p><strong>The primary-key column carries no {@code _cd} suffix</strong>, which is both a
 * faithfulness point and a transcription hazard. Five sibling columns elsewhere in the schema name a
 * transaction type with that suffix - the transaction and daily-transaction type codes, the
 * category-balance type code, the disclosure-group type code, and the first component of the category
 * composite key - but this reference table's own key column does not, because the legacy field it maps
 * does not. It must not be introduced here for the sake of consistency.
 *
 * <p><strong>The trailing filler is not persisted.</strong> The 8 bytes at offset 52 that complete the
 * 60-byte image carry no information, so no attribute and no column represents them; filler is
 * reconstructed on output from the declared record width. Fixed-width knowledge - offsets, widths and
 * the padding of the record image - lives exclusively in the record mapper in the utility layer. This
 * class carries persisted column widths as mapping metadata, performs no slicing, parsing, padding or
 * formatting, and holds no dependency on the utility layer: the mapper produces instances of this
 * entity, so the dependency runs one way only.
 *
 * <p><strong>No foreign key targets or originates from this table, in any migration.</strong> This
 * class therefore models no association whatsoever - no collection attribute, no owning or inverse
 * side, no join column, no cascade - and the reference lookup path is a plain repository lookup by
 * identifier. Decision log entry D-38 records why the reference tables carry no constraints and why
 * the absence on the daily-transaction side in particular is load-bearing rather than incidental.
 *
 * <p><strong>Seed volume.</strong> The reference data holds exactly 7 rows, derived from
 * {@code app/data/ASCII/trantype.txt} - 427 bytes, that is 7 records at the 60-byte record length plus
 * one line terminator each. Each description occupies the full 50-byte field, blank-padded to width,
 * and that padding is contractual: it is part of the external text width the legacy record publishes.
 * Nothing in this class trims, strips, folds, pads or normalizes a value, so a description round-trips
 * exactly as supplied. Any seed script belongs under a profile-scoped location so that a production
 * migration inherits the schema without sample rows.
 *
 * <p><strong>Consumers.</strong> {@code CVTRA03Y} is included by exactly one program in the whole
 * estate, the transaction-report program {@code CBTRN03C} - the lowest inclusion count among the
 * eleven entity copybooks, shared only with the disclosure-group and transaction-category members.
 * This table is consequently pure reference data whose only readers are the type-code lookup and the
 * description joins performed when a report or statement line is formatted, and nothing writes to it
 * outside a seed.
 *
 * <p><strong>Mapping is validated, never generated; instances are mutable and hand-written.</strong>
 * Schema evolution is forward-only and owned by the migration set, and the provider runs in validate
 * mode on every profile, so every name, width and nullability declared below mirrors the migration
 * exactly and any divergence fails start-up outright. A persistent entity must offer a no-argument
 * constructor the provider can instantiate and attributes it can populate, which a compact data
 * carrier cannot supply, so this is a plain class with every member written out in full: no annotation
 * processor, mapping framework, code generator or introspection participates, which keeps the module's
 * zero budget for reflective access intact and the build free of processor-generated warnings under
 * warnings-as-errors.
 */
@Entity
@Table(name = "transaction_type")
public class TransactionType {

    /**
     * Transaction type code - legacy field {@code TRAN-TYPE}, a 2-byte alphanumeric field at
     * offset 0 of the 60-byte record and the whole of the cluster key.
     *
     * <p>Mapped to schema column {@code tran_type}, declared {@code VARCHAR(2) NOT NULL} and named
     * without a code suffix, exactly as the legacy field is named. This is the entity identifier
     * and it is the business key itself; the seed values are the two-character codes {@code 01}
     * through {@code 07}. Held as text rather than as a number so that a leading zero survives a
     * round trip intact and the external width stays exactly 2.
     *
     * <p>Not declared {@code final}, because the no-argument constructor the persistence provider
     * requires cannot initialise a final attribute.
     */
    @Id
    @Column(name = "tran_type", length = 2, nullable = false)
    private String tranType;

    /**
     * Transaction type description - legacy field {@code TRAN-TYPE-DESC}, a 50-byte alphanumeric
     * field at offset 2 of the 60-byte record.
     *
     * <p>Mapped to schema column {@code tran_type_desc}, declared {@code VARCHAR(50) NOT NULL}. In
     * the record image the value is blank padded to the full 50 bytes, and that padding is part of
     * the published external width, so it is stored and returned verbatim. This attribute is
     * mutable descriptive text and therefore takes no part in equality or hashing.
     *
     * <p>Not declared {@code final}, for the same reason as the preceding attribute.
     */
    @Column(name = "tran_type_desc", length = 50, nullable = false)
    private String tranTypeDesc;

    /**
     * Creates an empty instance.
     *
     * <p>Required by the persistence provider, which instantiates a managed entity through its
     * no-argument constructor and populates the attributes afterwards. That instantiation is the
     * provider's own concern; this class introspects nothing itself, so the module's zero budget
     * for low-level reflective access is unaffected.
     *
     * <p>Declared {@code protected} rather than {@code public} because application code should
     * always build a fully populated instance through
     * {@link #TransactionType(String, String)}; the provider reaches this constructor regardless of
     * its access level.
     */
    protected TransactionType() {
        // Intentionally empty: both attributes are populated by the provider after construction.
    }

    /**
     * Creates a fully populated transaction type.
     *
     * <p>Parameter order is the copybook declaration order and must not be changed: the 2-byte type
     * code at offset 0 precedes the 50-byte description at offset 2.
     *
     * <p>Both values are stored exactly as supplied. No trimming, padding, case folding, width
     * enforcement or validation of any kind is applied, because the persisted widths and
     * nullability are enforced by the schema and because silently altering a caller's value would
     * destroy the blank padding that the 50-byte description field publishes as part of its
     * external contract.
     *
     * @param tranType     the 2-byte transaction type code at offset 0, which is the identifier
     * @param tranTypeDesc the 50-byte transaction type description at offset 2, blank padded to
     *                     width in the legacy record image
     */
    public TransactionType(final String tranType, final String tranTypeDesc) {
        this.tranType = tranType;
        this.tranTypeDesc = tranTypeDesc;
    }

    /**
     * Returns the transaction type code, which is this entity's identifier.
     *
     * @return the 2-byte transaction type code at offset 0, exactly as supplied and with any
     *         leading zero preserved, or {@code null} if this instance has not been populated
     */
    public String getTranType() {
        return tranType;
    }

    /**
     * Sets the transaction type code.
     *
     * <p>The value is assigned verbatim; no trimming, padding, case folding or validation is
     * applied. Because this attribute is the identifier, reassigning it on an instance already
     * associated with a persistence context changes that instance's identity, so callers should
     * treat it as write-once and use the two-argument constructor in preference.
     *
     * @param tranType the 2-byte transaction type code at offset 0, stored exactly as supplied
     */
    public void setTranType(final String tranType) {
        this.tranType = tranType;
    }

    /**
     * Returns the transaction type description.
     *
     * @return the 50-byte transaction type description at offset 2, returned exactly as supplied so
     *         that the blank padding of the legacy record image survives intact, or {@code null} if
     *         this instance has not been populated
     */
    public String getTranTypeDesc() {
        return tranTypeDesc;
    }

    /**
     * Sets the transaction type description.
     *
     * <p>The value is assigned verbatim. Trimming here would destroy the blank padding that fills
     * the 50-byte field in the record image and would therefore break the byte parity of any
     * fixed-width output formatted from this description.
     *
     * @param tranTypeDesc the 50-byte transaction type description at offset 2, stored exactly as
     *                     supplied
     */
    public void setTranTypeDesc(final String tranTypeDesc) {
        this.tranTypeDesc = tranTypeDesc;
    }

    /**
     * Compares this transaction type with another by identifier alone.
     *
     * <p>Only the type code participates, because it is the primary key and it alone determines row
     * identity. The description is deliberately excluded: it is mutable, so including it would let
     * an instance's hash code and equality change while the instance is held in a set or used as a
     * map key, which would corrupt those collections across a flush.
     *
     * <p>Comparison is exact, with no normalisation, padding adjustment or case folding, because
     * the legacy width is fixed and every character of the code is significant. A code of
     * {@code "01"} is consequently not equal to one of {@code "1"}.
     *
     * @param o the object to compare with, which may be {@code null}
     * @return {@code true} only if {@code o} is a transaction type whose code matches this one
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionType other)) {
            return false;
        }
        return Objects.equals(tranType, other.tranType);
    }

    /**
     * Returns a hash code derived from the identifier alone, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code for this transaction type
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(tranType);
    }

    /**
     * Returns a diagnostic representation containing both attributes and nothing else.
     *
     * <p>Neither attribute is a credential and neither is a monetary value, so no redaction is
     * required. The description is rendered exactly as stored, blank padding included.
     *
     * @return a diagnostic representation of this transaction type
     */
    @Override
    public String toString() {
        return "TransactionType[tranType=" + tranType
                + ", tranTypeDesc=" + tranTypeDesc + "]";
    }
}
