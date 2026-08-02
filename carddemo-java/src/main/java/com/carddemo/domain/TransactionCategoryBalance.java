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

import com.carddemo.domain.id.TransactionCategoryBalanceId;

/**
 * Per-account, per-category accumulated balance, mapping the 50-byte record of
 * {@code app/cpy/CVTRA01Y.cpy}. The 22-byte trailing filler is deliberately not persisted: it carries no
 * information and is reconstructed on output from the declared record width. Offset arithmetic lives only
 * in the fixed-width mapper for this layout, and the accrual that consumes the balance belongs to the
 * service layer, so this class computes nothing.
 *
 * <p><strong>Composite identity, and a name collision worth knowing about.</strong> The key is the
 * account identifier, transaction type code and transaction category code in that order, together the
 * 17-byte leading substring of the record image, attested independently by the copybook, by the cluster
 * geometry in {@code app/jcl/TCATBALF.jcl} and by the migration's primary-key order. It is realised with
 * an identifier class the provider matches by field name and type, so a divergence in either fails
 * start-up. The legacy key group name is shared with {@code app/cpy/CVTRA04Y.cpy}, whose key is a
 * structurally unrelated 6-byte pair behind {@link TransactionCategory}; the two align at no offset and
 * share no supertype or helper (decision-log D-37). The copybook's own prefix inconsistency between the
 * key components and the balance is transcribed exactly, because the mapping is validated against the
 * migrated schema at start-up.
 *
 * <p><strong>Verified layout, and what is deliberately absent from it.</strong> Four fields make up
 * the 50-byte image and only three of them plus the balance are persisted: an 11-byte account
 * identifier at offset 0, a 2-byte transaction type code at offset 11, a 4-byte transaction category
 * code at offset 13, an 11-byte signed balance at offset 17, and a 22-byte trailing filler at
 * offset 28. Each attribute below restates its own offset and width. The filler is <em>not</em>
 * persisted - no attribute here, no column in the schema - because trailing filler carries no
 * information and is reconstructed on output from the declared record width.
 *
 * <p>Offset arithmetic appears nowhere in this class. It lives exclusively in the fixed-width record
 * mapper for this layout, {@code TranCatBalRecordMapper} of the utility layer, so record-image
 * knowledge stays in one place and this entity carries nothing but column widths. That mapper is a
 * separate deliverable of the record-mapper boundary and is <em>not present at this checkpoint</em>;
 * the name above is a plain code reference rather than a resolved link, so neither compilation nor
 * Javadoc generation here depends on it. The dependency runs one way once it lands: the mapper
 * <em>produces</em> instances of this class, and this class names no type of the utility, service,
 * repository, interface or batch layers in code - only in this prose, and only to say where a
 * responsibility deliberately does not live.
 *
 * <p><strong>VSAM origin corroborates the component order.</strong> The record is stored in base
 * cluster {@code TCATBALF}, defined in {@code app/jcl/TCATBALF.jcl} as an {@code INDEXED} KSDS with
 * {@code KEYS(17 0)} and {@code RECORDSIZE(50 50)}. A declared key length of 17 beginning at offset 0
 * is decisive twice over: it confirms that the key is the leading substring of the record image, and
 * it confirms that the three components below, in exactly this order, are what sum to that 17-byte
 * substring - 11 plus 2 plus 4. The primary-key column order of the migration agrees, so one ordering
 * is attested by the copybook, by the cluster geometry and by the schema independently.
 *
 * <p>Because the key offset is 0 the key <em>is</em> the leading substring of the stored image, which
 * is the same key-then-data split the sequential batch programs model in their file descriptions -
 * {@code app/cbl/CBACT01C.cbl} splits its own 300-byte account image into an 11-byte key field
 * followed by a 289-byte data field for exactly this reason. The persistent identity of this entity is
 * therefore the legacy business key itself. No surrogate, generated or provider-assigned identifier is
 * introduced anywhere, because one would break the record-image-to-table-row correspondence on which
 * byte-level parity verification of the migrated output depends.
 *
 * <p><strong>Name-collision warning: {@code TRAN-CAT-KEY} is an overloaded legacy name.</strong>
 * Copybook {@code CVTRA01Y} and copybook {@code CVTRA04Y} both name their key group
 * {@code TRAN-CAT-KEY}, yet the two keys are structurally unrelated. Here the key has
 * <strong>three</strong> components totalling <strong>17 bytes</strong>, corroborated by
 * {@code KEYS(17 0)} in {@code app/jcl/TCATBALF.jcl}; in {@code CVTRA04Y} - the transaction-category
 * reference record behind {@link TransactionCategory} - it has <strong>two</strong> components
 * totalling <strong>6 bytes</strong>, corroborated by {@code KEYS(6 0)} in
 * {@code app/jcl/TRANCATG.jcl}. The 6-byte key is not a prefix, sub-key or reusable fragment of this
 * one: this key leads with an account identifier the other does not contain at all, so the two layouts
 * align at no offset.
 *
 * <p>The identifier class named below is consequently {@link TransactionCategoryBalanceId} and nothing
 * else. The 6-byte key has its own separate identifier class, the two are never interchangeable, and no
 * shared supertype or helper may be introduced to "reuse" the components whose names happen to overlap.
 * Decision log entry D-37 records the collision so the distinction stays auditable.
 *
 * <p><strong>Prefix divergence, preserved verbatim.</strong> Within this one copybook the naming is
 * itself inconsistent: the key group and the balance separate the words while the three key components
 * run the first two together. The migration transcribes that inconsistency exactly - the three key
 * columns carry the run-together prefix seen below while the balance column carries the separated one -
 * and this class matches the migration column for column. Regularizing either spelling would be a
 * mapping mismatch, and because the module validates its mapping against the migrated schema at
 * start-up it would be a start-up failure rather than a cosmetic difference.
 *
 * <p><strong>Composite identity.</strong> The primary key is
 * {@code (trancat_acct_id, trancat_type_cd, trancat_cd)} in exactly that order, realised with
 * {@code @IdClass} naming {@link com.carddemo.domain.id.TransactionCategoryBalanceId} rather than with
 * an embedded identifier.
 * Three separate identifier annotations, one per component, keep every key part directly queryable as a
 * top-level column and keep all three composite-key entities of this package consistent with one
 * another. The provider matches an identifier class to its entity by field <em>name</em> and field
 * <em>type</em>, so the three key attributes here are named and typed identically to that class;
 * a divergence in either is a start-up failure rather than a silent defect. Component order is
 * contractual and is preserved in the declaration order below.
 *
 * <p><strong>Every key component is text, deliberately.</strong> Two of the three are digit-only fields
 * in the copybook, yet all three are bounded character columns, because leading zeros and external text
 * widths are contractual rather than incidental. A category code of {@code "0005"} must round-trip as
 * {@code "0005"}: a numeric type would render it as {@code 5} and the 17-byte key image would no longer
 * reconstruct from the row. The same holds for an account identifier such as {@code "00000000001"}.
 * Nothing here trims, pads, folds case, normalizes or validates any value - not the constructors, not
 * the mutators, and above all not {@link #equals(Object)} or {@link #hashCode()}, where normalization
 * would make two distinct database rows compare equal.
 *
 * <p><strong>No association, in either direction.</strong> The account identifier is a plain text
 * attribute and not a mapped relationship. The migration does define a foreign key from this table's
 * account identifier to the account table, but purely as a database constraint; modelling it in Java
 * would additionally require an identifier-mapping layer, because the same column is simultaneously a
 * key component. No foreign key exists from the type or category code to the reference tables even
 * though both read like references, so neither is modelled as an association either - decision log
 * entry D-38 records why. This class therefore holds no association attribute and no collection.
 *
 * <p><strong>Seeded volume, and the accrual path it reaches.</strong> The reference data seeds exactly
 * 50 rows from {@code app/data/ASCII/tcatbal.txt}, measured at 2,550 bytes for 50 records at the
 * 50-byte record length, and <em>every one of them carries a balance of zero</em>. All 50 sit on the
 * same {@code (01, 0001)} type and category. Those 50 rows together with the 51 disclosure-group rows
 * are what make the accrual rate lookup exercisable from seeded data alone, and the arm they reach is
 * the default-group fallback: every seeded account carries ten spaces in its group identifier and no
 * seeded group key is ten spaces, so all fifty accounts miss their first probe, re-probe as
 * {@code "DEFAULT   "}, and find a rate of 15.00 on this type and category. The other two arms need a
 * constructed fixture rather than the seed - a direct group hit needs an account whose group identifier
 * matches a seeded group key, and the zero-rate skip needs that account pointed at the zero-rate group;
 * {@link DisclosureGroup} states both requirements in one place. That every seeded balance is zero is
 * also why the balance attribute is left unset by the no-argument constructor rather than pre-seeded
 * with a zero: an unpopulated instance must stay distinguishable from a genuine zero balance.
 *
 * <p><strong>Carried here, computed elsewhere.</strong> This class performs no arithmetic and applies no
 * scaling. The accrual computation that consumes this balance belongs to
 * {@code InterestCalculationService} of the service layer, and the one truncating scale policy belongs
 * to {@code ZonedDecimalCodec} of the utility layer; the balance attribute below records why that
 * division of responsibility is contractual rather than merely tidy.
 *
 * <p><strong>Mutability and thread safety.</strong> This is a mutable persistent entity, as the provider
 * requires, and is therefore not thread safe. Instances are confined to the persistence context or the
 * batch chunk that owns them and are never shared across threads or held in a static cache. No caching
 * layer exists in the legacy system and none is introduced here.
 *
 * <p>Provenance: derived by inspection from checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19, which the copybook carries in its trailer
 * comment at line 12. The legacy estate is read-only reference: no source text is transcribed into this
 * module, so member names, field names, byte offsets, widths and codes are cited instead.
 *
 * @see TransactionCategoryBalanceId
 */
@Entity
@Table(name = "transaction_category_balance")
@IdClass(TransactionCategoryBalanceId.class)
public class TransactionCategoryBalance {

    /**
     * First component of the composite key, and a foreign key at the database level only. Held as text so
     * that the zero-filled external width survives a round trip and the key image reconstructs.
     */
    @Id
    @Column(name = "trancat_acct_id", length = 11, nullable = false)
    private String trancatAcctId;

    @Id
    @Column(name = "trancat_type_cd", length = 2, nullable = false)
    private String trancatTypeCd;

    @Id
    @Column(name = "trancat_cd", length = 4, nullable = false)
    private String trancatCd;

    /**
     * Accumulated balance as an exact decimal: nine integer digits and two decimals, signed. No
     * approximate binary type may appear here or anywhere in the module. The legacy field is zoned
     * decimal, so its sign is folded into the trailing byte of the record image, and decoding that byte
     * belongs to the record mapper.
     *
     * <p>Carried, never computed and never rescaled. Scaling is the single responsibility of
     * {@link com.carddemo.util.ZonedDecimalCodec}, which applies one truncating policy uniformly; a
     * carrier that rescaled on the way in or out would apply it twice, and could round where the legacy
     * truncates. The accrual this balance feeds is order-sensitive - the balance is multiplied by the
     * disclosure rate before the monthly divisor is applied, and the estate specifies no rounding
     * anywhere - so pre-scaling an operand would move the truncation point and change the resulting cent.
     *
     * <p>Left unset by the no-argument constructor rather than seeded with a zero, so an unpopulated
     * instance stays distinguishable from a genuine zero balance.
     */
    @Column(name = "tran_cat_bal", precision = 11, scale = 2, nullable = false)
    private BigDecimal tranCatBal;

    /**
     * Required by the persistence provider, which assigns state after construction.
     */
    protected TransactionCategoryBalance() {
    }

    /**
     * Creates a fully populated row from its three key components in contractual order plus the balance.
     * Every value is stored exactly as supplied.
     */
    public TransactionCategoryBalance(String trancatAcctId,
                                      String trancatTypeCd,
                                      String trancatCd,
                                      BigDecimal tranCatBal) {
        this.trancatAcctId = trancatAcctId;
        this.trancatTypeCd = trancatTypeCd;
        this.trancatCd = trancatCd;
        this.tranCatBal = tranCatBal;
    }

    public String getTrancatAcctId() {
        return trancatAcctId;
    }

    public void setTrancatAcctId(String trancatAcctId) {
        this.trancatAcctId = trancatAcctId;
    }

    public String getTrancatTypeCd() {
        return trancatTypeCd;
    }

    public void setTrancatTypeCd(String trancatTypeCd) {
        this.trancatTypeCd = trancatTypeCd;
    }

    public String getTrancatCd() {
        return trancatCd;
    }

    public void setTrancatCd(String trancatCd) {
        this.trancatCd = trancatCd;
    }

    public BigDecimal getTranCatBal() {
        return tranCatBal;
    }

    public void setTranCatBal(BigDecimal tranCatBal) {
        this.tranCatBal = tranCatBal;
    }

    /**
     * Returns this row's composite key as one addressable value, in contractual component order.
     *
     * @return a key carrying the three components as stored
     */
    public TransactionCategoryBalanceId toId() {
        return new TransactionCategoryBalanceId(trancatAcctId, trancatTypeCd, trancatCd);
    }

    /**
     * Compares rows by the three key components only, in declaration order and byte for byte, so that
     * Java equality agrees with the database's notion of the same row. The balance is excluded because it
     * is mutable state, not identity.
     *
     * @param o the object to compare against
     * @return {@code true} only if {@code o} is a {@code TransactionCategoryBalance} with an equal key
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategoryBalance other)) {
            return false;
        }
        return Objects.equals(this.trancatAcctId, other.trancatAcctId)
                && Objects.equals(this.trancatTypeCd, other.trancatTypeCd)
                && Objects.equals(this.trancatCd, other.trancatCd);
    }

    @Override
    public int hashCode() {
        return Objects.hash(trancatAcctId, trancatTypeCd, trancatCd);
    }

    /**
     * Renders the three key components and nothing else, each quoted and printed as stored so that a
     * width or padding difference stays visible. The balance is omitted because it is financial data.
     *
     * <p>The first component is the account identifier, so this rendering is account-linked identifying
     * data: it requires the same controlled handling as the identifier itself and must not be emitted to
     * unrestricted logs.
     *
     * @return a diagnostic string containing the three key components
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalance[trancatAcctId='" + trancatAcctId
                + "', trancatTypeCd='" + trancatTypeCd
                + "', trancatCd='" + trancatCd + "']";
    }
}
