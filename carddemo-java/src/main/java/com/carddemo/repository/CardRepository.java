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

import java.util.Optional;

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
 * <p>The two declared finders reproduce {@code CARDAIX}, backed by
 * {@code idx_card_card_acct_id} in {@code V2__create_indexes.sql}. The one legacy consumer verified
 * to read that path is the card-detail program {@code app/cbl/COCRDSLC.cbl}, which reads it keyed
 * on the account identifier. Neighbouring flows deliberately reach a card differently and must not
 * be rerouted through these finders: account view and account update resolve a card through the
 * {@code CXACAIX} cross-reference index, and card update reads and rewrites the base cluster by
 * card number, which is inherited keyed access.
 *
 * <p>The card-list screen is the case most easily got wrong. It browses the <em>base cluster</em>
 * by card number and applies its account filter after each read, so its Java counterpart is the
 * inherited paged {@code findAll} sorted on the card number. Routing it through the finders below
 * would silently reorder the screen from card number to account identifier. Page size - seven rows
 * on that screen - and sort direction are the caller's to supply and appear nowhere here.
 *
 * <p>Optimistic locking lives on the entity's version attribute, not in this interface, and values
 * pass through untrimmed and unpadded: fixed-width layout knowledge belongs to the record mapper.
 *
 * @see Card
 */
public interface CardRepository extends JpaRepository<Card, String> {
    /**
     * Every card of one account, in no guaranteed order.
     *
     * <p>The return type is a list because the alternate key is non-unique. A single-valued derived
     * query would raise an incorrect-result-size failure the moment an account owned two cards, and
     * that is a failure the legacy read of a duplicate-bearing path cannot produce. An empty list is
     * the analogue of the legacy not-found response and is not an error here.
     *
     * @param cardAcctId the eleven-character account identifier, matched exactly as supplied
     * @return the matching rows, possibly empty, never {@code null}
     */
    Optional<Card> findFirstByCardAcctIdOrderByCardNumAsc(String cardAcctId);

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
