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
 * {@code CARD-RECORD} of copybook {@code CVACT02Y}, replacing the {@code CARDDATA} VSAM base
 * cluster and the {@code CARDAIX} alternate index defined over its account-identifier field.
 *
 * <p>Identity is the 16-character card number the record carries at offset 0, held as a
 * {@link String} because its leading zeros are contractual; no surrogate key exists anywhere in
 * this module.
 *
 * <p><strong>Four declared finders, and no more.</strong> Two reproduce {@code CARDAIX} - the
 * {@code NONUNIQUEKEY UPGRADE} alternate index over {@code CARD-ACCT-ID}, key length 11 at offset 16
 * per {@code app/jcl/CARDFILE.jcl} lines 83-88, served by {@code idx_card_card_acct_id} in
 * {@code V2__create_indexes.sql} - and two are the bounded keyset reads of the base cluster that the
 * card-list browse walks. Everything else a caller needs is inherited from {@link JpaRepository}:
 * keyed access is {@code findById} and a rewrite is {@code save}.
 *
 * <p><strong>Why there is a first-match finder, and why the list form remains beside it.</strong> A
 * keyed read of a duplicate-bearing VSAM path returns the first record in ascending base-key order,
 * and the base key of this cluster is the card number. Four services reproduce exactly that read, and
 * each of them was materialising every card of the account and then discarding all but the lowest -
 * four copies of one selection rule, each paying for rows it threw away, over an index whose whole
 * point is that the account may own several cards. The rule is now stated once, here, in the name of
 * {@link #findFirstByCardAcctIdOrderByCardNumAsc(String)}: bounded to one row, ordered explicitly, and
 * impossible for a caller to get subtly wrong. {@link #findByCardAcctId(String)} stays for the callers
 * that genuinely need every card of an account, so the two contracts describe two different questions
 * rather than competing answers to one.
 *
 * <p><strong>Why the keyset finders exist, and why the card-list screen is not routed through the
 * account index.</strong> The card-list program {@code app/cbl/COCRDLIC.cbl} declares {@code CARDAIX}
 * and then never references it: it browses the <em>base cluster</em> by card number and applies its
 * account filter after each read, so its Java counterpart orders on the card number and never on the
 * account identifier. Routing it through either account finder would silently reorder the screen.
 * That browse retains a card number and repositions on it, which is a keyset read and not an offset
 * page: {@link #findByCardNumGreaterThanOrderByCardNumAsc(String, Limit)} and
 * {@link #findByCardNumLessThanOrderByCardNumDesc(String, Limit)} are the two directions of it, and
 * the inclusive first row of a greater-or-equal browse start is the inherited {@code findById}. Page
 * size - seven rows on that screen - is the caller's to supply and appears nowhere here.
 *
 * <p>Neighbouring flows deliberately reach a card differently and must not be rerouted through these
 * finders either: account view and account update resolve a card through the {@code CXACAIX}
 * cross-reference index, and card update reads and rewrites the base cluster by card number, which is
 * inherited keyed access.
 *
 * <p>Optimistic locking lives on the entity's version attribute, not in this interface, and values
 * pass through untrimmed and unpadded: fixed-width layout knowledge belongs to the record mapper.
 *
 * @see Card
 */
public interface CardRepository extends JpaRepository<Card, String> {
    /**
     * Every card of one account.
     *
     * <p>The return type is a list because the alternate key is non-unique: an account may own several
     * cards, and a single-valued derived query would raise an incorrect-result-size failure the moment
     * it owned two - a failure the legacy read of a duplicate-bearing path cannot produce.
     *
     * <p><strong>Order is the caller's to impose.</strong> No ordering term is declared, so a caller
     * that depends on sequence sorts what it receives. A service reproducing the legacy keyed read
     * takes the row with the lowest card number, which is the record that read would have returned;
     * a service that genuinely needs every card in order sorts on the card number before using them.
     * Declaring the order here would read as though the sequence were a property of the index rather
     * than of the legacy access path being reproduced.
     *
     * <p>An empty list is the analogue of the legacy not-found response and is not an error here; the
     * decision whether absence is an error belongs to the service tier.
     *
     * @param cardAcctId the eleven-character account identifier, matched exactly as supplied
     * @return the matching rows, possibly empty, never {@code null}
     */
    List<Card> findByCardAcctId(String cardAcctId);

    /**
     * The one card of an account that a legacy keyed read of the alternate-index path would return.
     *
     * <p>A read of a {@code NONUNIQUEKEY} path yields the first duplicate in ascending base-key order,
     * and the base key here is the card number, so the ordering term is not a preference: it is the
     * legacy rule, stated once in a name rather than four times in four services. The read is bounded
     * to one row, so an account owning many cards costs the same as an account owning one.
     *
     * <p>An empty result is the analogue of the legacy not-found response and is not an error here;
     * whether absence is an error belongs to the service tier, which reports it with that screen's own
     * message.
     *
     * <p>Callers needing every card of the account use {@link #findByCardAcctId(String)} instead. The
     * two exist side by side deliberately: one answers "which card does a keyed read return", the
     * other "which cards does this account own", and collapsing them would lose the distinction the
     * non-unique index exists to represent.
     *
     * @param cardAcctId the eleven-character account identifier, matched exactly as supplied
     * @return the card with the lowest card number among the account's cards, or empty when it owns
     *         none
     */
    Optional<Card> findFirstByCardAcctIdOrderByCardNumAsc(String cardAcctId);

    /**
     * The cards that follow a card number, ascending, limited to the number of rows the caller asks
     * for.
     *
     * <p>The forward half of the card-list browse. The cursor is the card number of the last row the
     * browse handed out, and the comparison is strict so that row is not delivered twice. The card
     * number is the primary key and therefore unique, which makes the single-column cursor total.
     *
     * <p><strong>This is a keyset read and not an offset page, and the difference is behavioural.</strong>
     * An offset page recounts and discards every earlier row on each fetch, so a browse deep into the
     * cluster costs more the further it goes; worse, a card inserted or removed between two fetches
     * shifts the window, which duplicates or skips a row. The legacy browse has neither problem
     * because it retains a record identifier - the card number at {@code app/cbl/COCRDLIC.cbl} lines
     * 1212 to 1214 - and repositions on it. Reading by key is therefore the faithful access path as
     * well as the cheaper one.
     *
     * <p>The inclusive row of a greater-or-equal browse start is not this method's concern: the caller
     * obtains it with the inherited keyed read and then walks strictly onward from it.
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
     * for.
     *
     * <p>The backward half of the card-list browse, and the direction that carries a contract of its
     * own. <strong>Descending is the read order, not the presentation order.</strong> The legacy
     * backward walk fills its bottom screen slot first and works upward, so the page the operator sees
     * ascends exactly like a forward page; the calling service places these rows into descending slots,
     * which is what makes the assembled page ascend. Returning them ascending here would silently
     * reverse the page.
     *
     * @param cardNum the exclusive upper bound - the first card number already handed out - matched
     *                exactly as supplied and never trimmed or re-cased
     * @param limit   the maximum number of rows to read
     * @return the matching rows in descending card-number order, at most {@code limit} of them,
     *         possibly empty and never {@code null}
     */
    List<Card> findByCardNumLessThanOrderByCardNumDesc(String cardNum, Limit limit);
}
