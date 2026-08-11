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

import com.carddemo.domain.CardCrossReference;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence gateway for the {@code card_cross_reference} table, replacing the {@code CARDXREF} VSAM
 * base cluster and - through {@link #findByXrefAcctIdOrderByXrefCardNumAsc(String, Limit)} - the
 * {@code CXACAIX} alternate index defined over its account-identifier field, key length 11 at
 * offset 25.
 *
 * <p>The 50-byte record carries information in 36 of them: a 16-byte card number at offset 0, a 9-byte
 * customer identifier at offset 16 and an 11-byte account identifier at offset 25, followed by a
 * 14-byte filler that is deliberately not persisted. That split is why the two validation artefacts
 * describing the same 50 rows measure 1,850 and 2,500 bytes without either being truncated; the record
 * mapper owns the offsets.
 *
 * <p>Identity is the 16-character card number at offset 0, held as a {@link String} because its leading
 * zeros are contractual. Values are matched and returned exactly as supplied - nothing here trims, pads
 * or folds - and an empty result is the analogue of the legacy not-found response rather than an error,
 * the service turning absence into that screen's own message.
 *
 * <p><strong>Two declared finders, and no more.</strong> Keyed access to the base cluster is the
 * inherited {@code findById} and a rewrite is the inherited {@code save}. The two finders below are the
 * whole of the alternate-index surface this table needs: one answers which rows an account carries, and
 * one answers which single row a legacy keyed read of the path returns. The non-unique index exists
 * precisely because those are different questions, so the pair must not be collapsed.
 *
 * @see CardCrossReference
 */
public interface CardCrossReferenceRepository extends JpaRepository<CardCrossReference, String> {
    /**
     * The cross-reference rows of one account, ascending by card number, limited to the number of rows
     * the caller asks for: the {@code CXACAIX} alternate-index access path.
     *
     * <p>The return type is a list because the alternate key is non-unique and an account may carry
     * several cards. A single-valued derived query would raise an incorrect-result-size failure the
     * moment two rows shared an account identifier, which is a failure the legacy read of a
     * duplicate-bearing path cannot produce.
     *
     * <p><strong>Use this only when more than one row of the account is genuinely wanted.</strong> A
     * caller reproducing a legacy keyed READ of this path wants one row - the first duplicate in
     * ascending base-key order - and asks for it through
     * {@link #findFirstByXrefAcctIdOrderByXrefCardNumAsc(String)} instead. Materialising every row here
     * and discarding all but the lowest duplicates that rule at the call site and pays for rows it
     * throws away; the reasoning is recorded as {@code DL-121} and {@code DL-164} in
     * {@code docs/decision-log.md}.
     *
     * <p><strong>&#9733; Bounded and ordered, both required.</strong> Nothing bounds how many cards an
     * account may carry - there is no unique constraint on the account identifier - so the caller states
     * what it will accept rather than discovering it. The ordering is the path's own: a read of a
     * duplicate-bearing index yields rows in ascending base-key order, and the base key here is the card
     * number. Declaring neither would leave "the first n rows" without a referent and leave the result
     * size a property of the data. Recorded as {@code DL-296} in {@code docs/decision-log.md}.
     *
     * <p>The reference seed is one-to-one across 50 accounts, 50 cards and 50 cross-reference rows, so
     * exercising multi-row retrieval, the ordering or the first-match rule requires a purpose-built
     * fixture holding two rows that share an account identifier.
     *
     * @param xrefAcctId the eleven-character account identifier, matched exactly as supplied; its
     *                   leading zeros are significant and it is never trimmed
     * @param limit      the greatest number of rows to return; never absent
     * @return the matching rows in ascending card-number order, at most {@code limit} of them, possibly
     *         empty, never {@code null}
     */
    List<CardCrossReference> findByXrefAcctIdOrderByXrefCardNumAsc(String xrefAcctId, Limit limit);

    /**
     * The one cross-reference row of an account that a legacy keyed READ of {@code CXACAIX} returns.
     *
     * <p>A keyed read of a duplicate-bearing alternate index yields the first record in ascending
     * base-key order, and the base key of this cluster is the card number, so the ordering term is the
     * legacy rule itself rather than a preference. The read is bounded to one row, so an account
     * carrying many cards costs no more than one carrying a single card - which is the whole difference
     * between this method and {@link #findByXrefAcctIdOrderByXrefCardNumAsc(String, Limit)}. Both
     * verified legacy consumers issue a single keyed READ of this path rather than a browse, so this is
     * the shape those services need.
     *
     * @param xrefAcctId the eleven-character account identifier, matched exactly as supplied; its
     *                   leading zeros are significant and it is never trimmed
     * @return the row with the lowest card number among the account's rows, or empty when it carries
     *         none
     */
    Optional<CardCrossReference> findFirstByXrefAcctIdOrderByXrefCardNumAsc(String xrefAcctId);
}
