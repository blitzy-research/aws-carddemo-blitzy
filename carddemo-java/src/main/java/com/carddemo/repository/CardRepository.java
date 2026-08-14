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
package com.carddemo.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import com.carddemo.domain.Card;

/**
 * Persistence gateway for the {@code card} table - the relational form of the 150-byte
 * {@code CARD-RECORD} of copybook {@code CVACT02Y}, replacing the {@code CARDDATA} VSAM base cluster
 * and the {@code CARDAIX} alternate index defined over its account-identifier field. Identity is the
 * 16-character card number the record carries at offset 0, held as a {@link String} because its
 * leading zeros are contractual; no surrogate key exists anywhere in this module.
 *
 * <p><strong>Four declared finders, and no more.</strong> Two reproduce {@code CARDAIX} - the
 * {@code NONUNIQUEKEY UPGRADE} alternate index over {@code CARD-ACCT-ID}, key length 11 at offset 16,
 * served by {@code idx_card_card_acct_id} in {@code V2__create_indexes.sql} - and two are the bounded
 * keyset reads of the base cluster that the card-list browse walks. Keyed access is the inherited
 * {@code findById} and a rewrite is the inherited {@code save}.
 *
 * <p>The two account finders answer two different questions and must not be collapsed. A keyed read of
 * a duplicate-bearing VSAM path returns the first record in ascending base-key order, and the base key
 * of this cluster is the card number, so {@link #findFirstByCardAcctIdOrderByCardNumAsc(String)} states
 * that rule once - bounded to one row, ordered explicitly - for the four services that reproduce it.
 * {@link #findByCardAcctIdOrderByCardNumAsc(String, Limit)} serves the callers that genuinely need more
 * than one card of an account.
 *
 * <p><strong>The card-list screen is not routed through the account index.</strong> The card-list
 * program declares {@code CARDAIX} and then never references it: it browses the <em>base cluster</em> by
 * card number and applies its account filter after each read, so its Java counterpart orders on the card
 * number and never on the account identifier. Routing it through either account finder would silently
 * reorder the screen. That browse retains a card number and repositions on it, which is a keyset read
 * and not an offset page: {@link #findByCardNumGreaterThanOrderByCardNumAsc(String, Limit)} and
 * {@link #findByCardNumLessThanOrderByCardNumDesc(String, Limit)} are its two directions, and the
 * inclusive first row of a greater-or-equal browse start is the inherited {@code findById}. Page size -
 * seven rows on that screen - is the caller's to supply and appears nowhere here.
 *
 * <p>Neighbouring flows deliberately reach a card differently and must not be rerouted through these
 * finders either: account view and account update resolve a card through the {@code CXACAIX}
 * cross-reference index, and card update reads and rewrites the base cluster by card number.
 *
 * <p>Optimistic locking lives on the entity's version attribute, not in this interface, and values pass
 * through untrimmed and unpadded: fixed-width layout knowledge belongs to the record mapper. An empty
 * result is the analogue of the legacy not-found response and is never an error here; whether absence is
 * an error belongs to the service tier, which reports it with that screen's own message.
 *
 * @see Card
 */
public interface CardRepository extends JpaRepository<Card, String> {
    /**
     * The cards of one account, ascending by card number, limited to the number of rows the caller asks
     * for: the {@code CARDAIX} alternate-index access path.
     *
     * <p>The return type is a list because the alternate key is non-unique: an account may own several
     * cards, and a single-valued derived query would raise an incorrect-result-size failure the moment
     * it owned two - a failure the legacy read of a duplicate-bearing path cannot produce.
     *
     * <p><strong>&#9733; The bound is required, not optional.</strong> Nothing in the schema bounds how
     * many cards an account may own, so an unbounded finder over a non-unique index is a query whose
     * result size is a property of the data - and the reference seed, one card per account across fifty
     * accounts, is precisely the shape that would never reveal it. The caller states what it will
     * accept, so the cost is visible at the call site rather than latent in the table.
     *
     * <p><strong>&#9733; The ordering is the access path's, not the caller's.</strong> A read of a
     * {@code NONUNIQUEKEY} path yields duplicates in ascending base-key order, and the base key of this
     * cluster is the card number, so the ordering term must stay declared here. Without it the rows
     * arrive in whatever order the plan produces and the bound loses its referent, because "the first
     * n" means nothing without an order. Recorded as {@code DL-296} in {@code docs/decision-log.md}.
     *
     * @param cardAcctId the eleven-character account identifier, matched exactly as supplied
     * @param limit      the greatest number of rows to return; never absent
     * @return the matching rows in ascending card-number order, at most {@code limit} of them, possibly
     *         empty, never {@code null}
     */
    List<Card> findByCardAcctIdOrderByCardNumAsc(String cardAcctId, Limit limit);

    /**
     * The one card of an account that a legacy keyed read of the alternate-index path would return.
     *
     * <p>A read of a {@code NONUNIQUEKEY} path yields the first duplicate in ascending base-key order,
     * and the base key here is the card number, so the ordering term is the legacy rule rather than a
     * preference. The read is bounded to one row, so an account owning many cards costs the same as an
     * account owning one. Callers needing more than one use
     * {@link #findByCardAcctIdOrderByCardNumAsc(String, Limit)}: one method answers "which card does a
     * keyed read return", the other "which cards does this account own", and collapsing them would lose
     * the distinction the non-unique index exists to represent.
     *
     * @param cardAcctId the eleven-character account identifier, matched exactly as supplied
     * @return the card with the lowest card number among the account's cards, or empty when it owns
     *         none
     */
    Optional<Card> findFirstByCardAcctIdOrderByCardNumAsc(String cardAcctId);

    /**
     * The cards that follow a card number, ascending, limited to the number of rows the caller asks
     * for: the forward half of the card-list browse.
     *
     * <p>The cursor is the card number of the last row the browse handed out and the comparison is
     * strict so that row is not delivered twice. The card number is the primary key and therefore
     * unique, which makes the single-column cursor total.
     *
     * <p><strong>This is a keyset read and not an offset page, and the difference is
     * behavioural.</strong> An offset page recounts and discards every earlier row on each fetch, so a
     * deep browse costs more the further it goes, and a card inserted or removed between two fetches
     * shifts the window and duplicates or skips a row. The legacy browse has neither problem because it
     * retains the card number and repositions on it, so reading by key is the faithful access path as
     * well as the cheaper one. The inclusive row of a greater-or-equal browse start is obtained by the
     * caller with the inherited keyed read, which then walks strictly onward from it.
     *
     * @param cardNum the exclusive lower bound - the last card number already handed out - matched
     *                exactly as supplied and never trimmed or re-cased
     * @param limit   the maximum number of rows to read
     * @return the matching rows in ascending card-number order, at most {@code limit} of them,
     *         possibly empty and never {@code null}
     */
    List<Card> findByCardNumGreaterThanOrderByCardNumAsc(String cardNum, Limit limit);

    /**
     * The cards that precede a card number, descending, limited to the number of rows the caller asks
     * for: the backward half of the card-list browse.
     *
     * <p><strong>Descending is the read order, not the presentation order.</strong> The legacy backward
     * walk fills its bottom screen slot first and works upward, so the page the operator sees ascends
     * exactly like a forward page; the calling service places these rows into descending slots, which is
     * what makes the assembled page ascend. Returning them ascending here would silently reverse the
     * page.
     *
     * @param cardNum the exclusive upper bound - the first card number already handed out - matched
     *                exactly as supplied and never trimmed or re-cased
     * @param limit   the maximum number of rows to read
     * @return the matching rows in descending card-number order, at most {@code limit} of them,
     *         possibly empty and never {@code null}
     */
    List<Card> findByCardNumLessThanOrderByCardNumDesc(String cardNum, Limit limit);
}
