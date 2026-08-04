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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
 * <p>Three declared finders reproduce {@code CARDAIX}, backed by {@code idx_card_card_acct_id} in
 * {@code V2__create_indexes.sql}: a first-match form for the keyed read every verified legacy consumer
 * issues, an ordered list form for a caller that genuinely needs every card of the account, and a paged
 * form for a caller that browses them. The one legacy consumer verified to read that path is the
 * card-detail program {@code app/cbl/COCRDSLC.cbl}, which reads it keyed on the account identifier.
 * Neighbouring flows deliberately reach a card differently and must not be rerouted through these
 * finders: account view and account update resolve a card through the {@code CXACAIX} cross-reference
 * index, and card update reads and rewrites the base cluster by card number, which is inherited keyed
 * access.
 *
 * <p>The card-list screen is the case most easily got wrong. It browses the <em>base cluster</em> by
 * card number and applies its account filter after each read, so its Java counterpart orders on the
 * card number and never on the account identifier. Routing it through the alternate-index finders
 * below would silently reorder the screen. Its four keyset methods are declared separately for that
 * reason: {@link #findByCardNumGreaterThanOrderByCardNumAsc(String, Limit)} and
 * {@link #findByCardNumLessThanOrderByCardNumDesc(String, Limit)} serve an unfiltered browse, and the
 * two account-filtered forms beside them serve the same browse once the operator has typed an account
 * identifier into the filter field. Page size - seven rows on that screen - is the caller's to supply
 * and appears nowhere here.
 *
 * <p><strong>The browse is keyset paged, because the legacy browse has no row number.</strong> It
 * retains the card number of the first and the last row it displayed and repositions on one of them.
 * Both of the screen's retained keys are therefore sixteen characters wide, and the account identifier
 * is <em>not</em> part of the resumption key: the screen declares a composite work field of a card
 * number followed by an account identifier, but at every one of its four repositioning sites only the
 * card-number half is moved into the browse key and the companion move of the account half is
 * commented out. Only the opening page of a browse, which has no cursor to resume from, uses the
 * inherited paged {@code findAll} at page zero, where an offset costs nothing.
 *
 * <p><strong>A backward page is read descending and presented ascending.</strong> The rows the
 * descending methods return are handed to the calling service in read order, and the service reverses
 * them before building the page, reproducing the legacy bottom-slot-upward fill; the page the operator
 * sees ascends exactly like a forward page.
 *
 * <p>Optimistic locking lives on the entity's version attribute, not in this interface, and values
 * pass through untrimmed and unpadded: fixed-width layout knowledge belongs to the record mapper.
 *
 * @see Card
 */
public interface CardRepository extends JpaRepository<Card, String> {
    /**
     * The <em>first</em> card of one account in ascending card-number order, or empty when the account
     * owns none.
     *
     * <p><strong>One row by intent, and the name says so.</strong> This reproduces a single keyed read
     * of the non-unique alternate index, which is what every verified legacy consumer of that path
     * issues - a keyed read and never a browse. A keyed read of a duplicate-bearing index returns the
     * first record in ascending base-key order, and the base key of this cluster is the card number, so
     * the ordering term is not a refinement of the legacy behaviour but a statement of it. The decision
     * is recorded as {@code DL-121} in {@code docs/decision-log.md}.
     *
     * <p>An empty result is the analogue of the legacy not-found response and is not an error here; the
     * decision whether absence is an error belongs to the service tier.
     *
     * <p><strong>Use {@link #findByCardAcctIdOrderByCardNumAsc(String)} when every card of the account
     * is wanted.</strong> This method deliberately discards the remaining rows, so a caller that needs
     * them and calls this one would silently process one card of several.
     *
     * @param cardAcctId the eleven-character account identifier, matched exactly as supplied
     * @return the first matching row in ascending card-number order, or {@link Optional#empty()} when
     *         the account owns no card
     */
    Optional<Card> findFirstByCardAcctIdOrderByCardNumAsc(String cardAcctId);

    /**
     * Every card of one account, in ascending card-number order.
     *
     * <p>The return type is a list because the alternate key is non-unique: an account may own several
     * cards, and a single-valued derived query would raise an incorrect-result-size failure the moment
     * it owned two - a failure the legacy read of a duplicate-bearing path cannot produce. The ordering
     * is declared rather than left to the engine so that two calls over unchanged data return the same
     * sequence, and it is the base-cluster order, which is the order the alternate index itself yields
     * within one duplicate group.
     *
     * <p>An empty list is the analogue of the legacy not-found response and is not an error here.
     *
     * @param cardAcctId the eleven-character account identifier, matched exactly as supplied
     * @return the matching rows in ascending card-number order, possibly empty, never {@code null}
     */
    List<Card> findByCardAcctIdOrderByCardNumAsc(String cardAcctId);

    /**
     * Reads the cards that follow a boundary card number, in ascending card-number order, limited to the
     * number of rows the caller asks for.
     *
     * <p>The forward half of the unfiltered card-list keyset browse. The cursor is the card number of
     * the last row the previous page displayed, and the comparison is strict so that row is not shown
     * twice. The card number is the primary key and therefore unique, which makes the single-column
     * cursor total.
     *
     * <p><strong>Ask for one row more than the screen holds.</strong> The legacy program learns that a
     * further page exists by attempting one more read, never by counting the cluster, so requesting
     * eight rows for a seven-row screen reproduces that exactly: eight returned means a further page
     * follows and the eighth row is discarded. Availability is the caller's to derive; this method
     * neither counts nor reports it.
     *
     * @param cardNum the exclusive lower bound - the last card number already displayed - matched
     *                exactly as supplied and never trimmed or padded
     * @param limit   the maximum number of rows to read, which the caller sets to the screen's row count
     *                plus one
     * @return the matching rows in ascending card-number order, at most {@code limit} of them, possibly
     *         empty and never {@code null}
     */
    List<Card> findByCardNumGreaterThanOrderByCardNumAsc(String cardNum, Limit limit);

    /**
     * Reads the cards that precede a boundary card number, in descending card-number order, limited to
     * the number of rows the caller asks for.
     *
     * <p>The backward half of the unfiltered card-list keyset browse. The cursor is the card number of
     * the first row the previous page displayed, and the comparison is strict so that row is not
     * repeated.
     *
     * <p><strong>Descending is the read order and not the presentation order.</strong> The legacy
     * backward path fills its bottom screen slot first and works upward, so the page the operator sees
     * ascends; the calling service reverses these rows before building the response.
     *
     * @param cardNum the exclusive upper bound - the first card number already displayed - matched
     *                exactly as supplied and never trimmed or padded
     * @param limit   the maximum number of rows to read, which the caller sets to the screen's row count
     *                plus one
     * @return the matching rows in descending card-number order, at most {@code limit} of them, possibly
     *         empty and never {@code null}
     */
    List<Card> findByCardNumLessThanOrderByCardNumDesc(String cardNum, Limit limit);

    /**
     * Reads the cards of one account that follow a boundary card number, in ascending card-number order,
     * limited to the number of rows the caller asks for.
     *
     * <p>The forward half of the card-list keyset browse once the operator has typed an account
     * identifier into the screen's filter field. The legacy program applies that filter after each read
     * of the base cluster rather than by switching to the alternate index, so the ordering stays on the
     * card number: the filter narrows the sequence without reordering it, and a filtered page therefore
     * carries the same cursor semantics as an unfiltered one.
     *
     * <p>The one-extra-row convention of {@link #findByCardNumGreaterThanOrderByCardNumAsc(String,
     * Limit)} applies unchanged.
     *
     * @param cardAcctId the eleven-character account identifier the operator filtered on, matched
     *                   exactly as supplied
     * @param cardNum    the exclusive lower bound - the last card number already displayed
     * @param limit      the maximum number of rows to read, the screen's row count plus one
     * @return the matching rows in ascending card-number order, at most {@code limit} of them, possibly
     *         empty and never {@code null}
     */
    List<Card> findByCardAcctIdAndCardNumGreaterThanOrderByCardNumAsc(String cardAcctId, String cardNum,
                                                                      Limit limit);

    /**
     * Reads the cards of one account that precede a boundary card number, in descending card-number
     * order, limited to the number of rows the caller asks for.
     *
     * <p>The backward half of the account-filtered card-list keyset browse, mirroring
     * {@link #findByCardNumLessThanOrderByCardNumDesc(String, Limit)} - descending is the read order
     * only, and the calling service reverses the rows before building the page.
     *
     * @param cardAcctId the eleven-character account identifier the operator filtered on, matched
     *                   exactly as supplied
     * @param cardNum    the exclusive upper bound - the first card number already displayed
     * @param limit      the maximum number of rows to read, the screen's row count plus one
     * @return the matching rows in descending card-number order, at most {@code limit} of them, possibly
     *         empty and never {@code null}
     */
    List<Card> findByCardAcctIdAndCardNumLessThanOrderByCardNumDesc(String cardAcctId, String cardNum,
                                                                    Limit limit);

    /**
     * One page of the cards of one account, sized and ordered entirely by the supplied
     * {@link Pageable} so a caller can reproduce either legacy browse direction.
     *
     * @param cardAcctId the eleven-character account identifier, matched exactly as supplied
     * @param pageable the page request carrying the size and the sort
     * @return the requested page, possibly empty, never {@code null}
     */
    Page<Card> findByCardAcctId(String cardAcctId, Pageable pageable);
}
