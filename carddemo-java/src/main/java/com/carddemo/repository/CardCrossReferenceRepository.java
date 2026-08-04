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
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence gateway for the {@code card_cross_reference} table, replacing the {@code CARDXREF}
 * VSAM base cluster and - through
 * {@link #findFirstByXrefAcctIdOrderByXrefCardNumAsc(String)} and
 * {@link #findByXrefAcctIdOrderByXrefCardNumAsc(String)} - the {@code CXACAIX} alternate index
 * defined over its account-identifier field.
 *
 * <p>The record declared by {@code app/cpy/CVACT03Y.cpy} is 50 bytes wide and carries information
 * in 36 of them: a 16-byte card number at offset 0, a 9-byte customer identifier at offset 16 and
 * an 11-byte account identifier at offset 25, followed by a 14-byte filler that is deliberately not
 * persisted. That split reconciles two validation artefacts describing the same 50 records at
 * different widths, and neither is truncated: {@code app/data/ASCII/cardxref.txt} measures 1,850
 * bytes, being 50 rows of the 36 mapped bytes plus one line terminator each, while
 * {@code app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS} measures 2,500 bytes, being the same rows at
 * the full cluster record length. The two file sizes differ by 650 bytes; the record payloads
 * differ by the 700 filler bytes, the 50 line terminators of the text form accounting for the
 * remainder.
 *
 * <p>Identity is the 16-character card number at offset 0, held as a {@link String} because its
 * leading zeros are contractual. Values are matched and returned exactly as supplied - nothing here
 * trims, pads or folds - and fixed-width layout knowledge belongs to the record mapper.
 *
 * @see CardCrossReference
 */
public interface CardCrossReferenceRepository extends JpaRepository<CardCrossReference, String> {
    /**
     * The <em>first</em> cross-reference row of one account in ascending card-number order: the
     * {@code CXACAIX} alternate-index access path as its legacy consumers actually use it.
     *
     * <p><strong>One row by intent, and the name says so.</strong> Both verified legacy consumers issue
     * a single keyed read of that path rather than a browse, and a keyed read of a duplicate-bearing
     * alternate index returns the first record in ascending base-key order - the base key here being the
     * card number, which is why the ordering term states the legacy behaviour rather than refining it.
     * The decision is recorded as {@code DL-121} in {@code docs/decision-log.md}. A single-valued derived
     * query <em>without</em> the first-match qualifier would instead have raised an incorrect-result-size
     * failure the moment two rows shared an account identifier, which is a failure the legacy read
     * cannot produce.
     *
     * <p>An empty result is the analogue of the legacy not-found response, which the service turns into
     * a screen message.
     *
     * <p><strong>Use {@link #findByXrefAcctIdOrderByXrefCardNumAsc(String)} when every row of the
     * account is wanted</strong>, because this method discards the remaining rows silently.
     *
     * <p>The reference seed is one-to-one across 50 accounts, 50 cards and 50 cross-reference rows, so
     * it exercises this method without stressing it: demonstrating multi-row retrieval or first-match
     * ordering requires a purpose-built fixture holding two rows that share an account identifier.
     *
     * @param xrefAcctId the eleven-character account identifier, matched exactly as supplied; its
     *                   leading zeros are significant and it is never trimmed
     * @return the first matching row in ascending card-number order, or {@link Optional#empty()} when the
     *         account has no cross-reference row
     */
    Optional<CardCrossReference> findFirstByXrefAcctIdOrderByXrefCardNumAsc(String xrefAcctId);

    /**
     * Every cross-reference row of one account, in ascending card-number order: the multi-row form of
     * the {@code CXACAIX} alternate-index access path.
     *
     * <p>The return type is a list because the alternate key is non-unique and an account may carry
     * several cards. The ordering is declared rather than left to the engine so that two calls over
     * unchanged data return the same sequence, and it is the base-cluster order - the order the
     * alternate index itself yields within one duplicate group - so the first element of this list is
     * always the row {@link #findFirstByXrefAcctIdOrderByXrefCardNumAsc(String)} returns. That
     * correspondence is what lets a caller move between the two forms without changing which row it
     * treats as primary.
     *
     * <p>An empty list is the analogue of the legacy not-found response and is not an error here.
     *
     * @param xrefAcctId the eleven-character account identifier, matched exactly as supplied; its
     *                   leading zeros are significant and it is never trimmed
     * @return the matching rows in ascending card-number order, possibly empty, never {@code null}
     */
    List<CardCrossReference> findByXrefAcctIdOrderByXrefCardNumAsc(String xrefAcctId);
}
