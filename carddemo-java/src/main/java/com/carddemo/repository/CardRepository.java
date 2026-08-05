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
 * <p><strong>Two declared finders, and no more.</strong> Both reproduce {@code CARDAIX} - the
 * {@code NONUNIQUEKEY UPGRADE} alternate index over {@code CARD-ACCT-ID}, key length 11 at offset 16
 * per {@code app/jcl/CARDFILE.jcl} lines 83-88 - and both are served by
 * {@code idx_card_card_acct_id} in {@code V2__create_indexes.sql}. One returns every matching row and
 * one returns a page of them. Everything else a caller needs is inherited from
 * {@link JpaRepository}: keyed access is {@code findById}, an unfiltered browse is
 * {@code findAll(Pageable)}, and a rewrite is {@code save}.
 *
 * <p><strong>Why no first-match finder.</strong> A keyed read of a duplicate-bearing VSAM path
 * returns the first record in ascending base-key order, and the base key of this cluster is the card
 * number. That rule is a property of the legacy READ, not of this index, so it belongs in the service
 * that reproduces the read rather than in the name of a query method. Declaring
 * {@code findFirstBy...} here would push a single-row decision into the persistence contract and hide
 * from the caller that the account may own several cards - which is precisely the situation the
 * non-unique index exists to represent. The services that reproduce a keyed read therefore take the
 * first row of the list themselves, ordering on the card number, and say so where they do it.
 *
 * <p><strong>Why no keyset finder, and why the card-list screen is not routed through here.</strong>
 * The card-list program {@code app/cbl/COCRDLIC.cbl} declares {@code CARDAIX} and then never
 * references it: it browses the <em>base cluster</em> by card number and applies its account filter
 * after each read, so its Java counterpart orders on the card number and never on the account
 * identifier. That browse is served by the inherited {@code findAll(Pageable)} with a card-number
 * sort, and routing it through either finder below would silently reorder the screen. Page size -
 * seven rows on that screen - is the caller's to supply and appears nowhere here.
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
     * One page of the cards of one account, sized and ordered entirely by the supplied
     * {@link Pageable} so a caller can reproduce either legacy browse direction.
     *
     * <p>The two legacy directions differ only in the sort the caller supplies. A backward page is
     * read descending and presented ascending: the rows arrive in read order and the calling service
     * reverses them before building the page, reproducing the legacy bottom-slot-upward fill, so the
     * page the operator sees ascends exactly like a forward page.
     *
     * @param cardAcctId the eleven-character account identifier, matched exactly as supplied
     * @param pageable   the page request carrying the size and the sort
     * @return the requested page, possibly empty, never {@code null}
     */
    Page<Card> findByCardAcctId(String cardAcctId, Pageable pageable);
}
