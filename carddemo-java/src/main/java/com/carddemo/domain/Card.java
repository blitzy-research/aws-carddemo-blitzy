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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Payment card: the 150-byte {@code CARD-RECORD} of copybook {@code CVACT02Y}, stored in the
 * {@code CARDDATA} VSAM base cluster whose definition fixes a 16-byte key at offset 0 and a record
 * size of exactly 150.
 *
 * <p>Mapped offsets and widths, in declaration order: 0/16 card number (the business key), 16/11
 * account identifier, 27/3 verification code, 30/50 embossed name, 80/10 expiration date, 90/1
 * active status, then a 59-byte trailing filler at 91 that is deliberately neither an attribute here
 * nor a column in the schema. The legacy field name at offset 80 is misspelled
 * {@code CARD-EXPIRAION-DATE}; the layout position is preserved and the Java property is spelled
 * correctly, which is recorded in {@code docs/decision-log.md} rather than propagated.
 *
 * <p>Identity is that card number and never a surrogate: the key is the leading substring of the
 * record image, and generating an identifier would sever the record-image-to-row correspondence that
 * byte-level output parity depends on. The account identifier stays a scalar column rather than an
 * association - the legacy access path is a keyed lookup over the non-unique alternate index at
 * offset 16, which the migration reproduces as a B-tree index plus a derived finder, and referential
 * integrity is enforced by the foreign key in {@code V2__create_indexes.sql}.
 *
 * <p>This is the second of only two entities carrying an optimistic-locking version counter, because
 * account update and card update are the only legacy programs that compared a before image against
 * an after image before rewriting.
 *
 * <p>A passive carrier: it computes nothing, and it never trims, pads, folds or validates. Offset
 * arithmetic belongs exclusively to {@code com.carddemo.util.CardRecordMapper}, which re-pads every
 * field to its full width on output, so a right-trimmed column value and a padded record field are
 * the same record. The legacy upper-casing of the embossed name is a character-table fold performed
 * by the card-update service, never a locale-sensitive library conversion.
 *
 * <p>Sensitivity: the card number is the primary account number and the verification code sits
 * beside it. The legacy design applies no field-level encryption, tokenisation or masking to either,
 * and no requirement in scope introduces one, so the gap is carried forward as an explicit unclosed
 * finding (decision D-14) rather than closed by unrequested work. {@link #toString()} redacts the
 * card number; nothing in this package logs, and no caller may log these values.
 */
@Entity
@Table(name = "card")
public class Card {
    /**
     * Width of the card number in characters: 16, from the copybook's own picture clause and from the
     * cluster key definition that keys the base cluster at width 16 and offset 0.
     *
     * <p>Named so that the column declaration and the persistence-time rule read the one figure rather
     * than two copies of it.
     */
    static final int CARD_NUM_WIDTH = 16;

    /**
     * Width of the owning-account identifier in characters: 11, declared by the copybook as eleven digits.
     *
     * <p>Named so that the column declaration and the persistence-time rule read the one figure rather
     * than two copies of it.
     */
    static final int CARD_ACCT_ID_WIDTH = 11;

    /**
     * Fixed stand-in emitted by {@link #toString()} in place of the card number: a constant rather
     * than any transformation of the value, so neither the length nor a fragment nor a digest of the
     * withheld number can be recovered from a rendered instance. It matches the placeholder the
     * request and response contracts use, so a diagnostic reader sees one vocabulary.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    @Id
    @Column(name = "card_num", length = CARD_NUM_WIDTH, nullable = false)
    private String cardNum;

    @Column(name = "card_acct_id", length = CARD_ACCT_ID_WIDTH, nullable = false)
    private String cardAcctId;

    @Column(name = "card_cvv_cd", length = 3, nullable = false)
    private String cardCvvCd;

    @Column(name = "card_embossed_name", length = 50, nullable = false)
    private String cardEmbossedName;

    @Column(name = "card_expiration_date", length = 10, nullable = false)
    private String cardExpirationDate;

    @Column(name = "card_active_status", length = 1, nullable = false)
    private String cardActiveStatus;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Card() {
    }

    public Card(String cardNum,
                String cardAcctId,
                String cardCvvCd,
                String cardEmbossedName,
                String cardExpirationDate,
                String cardActiveStatus) {
        this.cardNum = cardNum;
        this.cardAcctId = cardAcctId;
        this.cardCvvCd = cardCvvCd;
        this.cardEmbossedName = cardEmbossedName;
        this.cardExpirationDate = cardExpirationDate;
        this.cardActiveStatus = cardActiveStatus;
    }

    public String getCardNum() {
        return cardNum;
    }

    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    public String getCardAcctId() {
        return cardAcctId;
    }

    public void setCardAcctId(String cardAcctId) {
        this.cardAcctId = cardAcctId;
    }

    public String getCardCvvCd() {
        return cardCvvCd;
    }

    public void setCardCvvCd(String cardCvvCd) {
        this.cardCvvCd = cardCvvCd;
    }

    public String getCardEmbossedName() {
        return cardEmbossedName;
    }

    public void setCardEmbossedName(String cardEmbossedName) {
        this.cardEmbossedName = cardEmbossedName;
    }

    public String getCardExpirationDate() {
        return cardExpirationDate;
    }

    public void setCardExpirationDate(String cardExpirationDate) {
        this.cardExpirationDate = cardExpirationDate;
    }

    public String getCardActiveStatus() {
        return cardActiveStatus;
    }

    public void setCardActiveStatus(String cardActiveStatus) {
        this.cardActiveStatus = cardActiveStatus;
    }

    public long getVersion() {
        return version;
    }

    /**
     * Refuses a card number that is not exactly sixteen characters, or an owning-account identifier that is
     * not exactly eleven ASCII digits, immediately before the row is inserted or updated.
     *
     * <p><strong>The two checks differ on purpose.</strong> The copybook declares the card number as
     * sixteen <em>alphanumeric</em> characters and the account identifier as eleven <em>digits</em>, so a
     * digit class is contractual on one and not on the other; applying it to both would reject a card
     * number the legacy field could legitimately have held.
     *
     * <p>The card number's width carries a second obligation beyond identity. The legacy statement job
     * sorts these bytes typed as character while the report procedure sorts the same bytes typed as zoned
     * decimal, and the two orderings coincide only while every stored value is exactly sixteen characters.
     * A short value would silently reorder one of those jobs.
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
        StoredValueRules.requireFixedWidth(cardNum, CARD_NUM_WIDTH, "cardNum");
        StoredValueRules.requireFixedWidthDigits(cardAcctId, CARD_ACCT_ID_WIDTH, "cardAcctId");
    }

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

    @Override
    public int hashCode() {
        return Objects.hash(cardNum);
    }

    @Override
    public String toString() {
        return "Card[cardNum=" + REDACTION_PLACEHOLDER + ", cardActiveStatus='" + cardActiveStatus + "']";
    }
}
