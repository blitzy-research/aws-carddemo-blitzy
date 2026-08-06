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
 * VSAM base cluster and - through {@link #findByXrefAcctId(String)} - the {@code CXACAIX} alternate
 * index defined over its account-identifier field, key length 11 at offset 25 per
 * {@code app/jcl/XREFFILE.jcl} lines 72-77.
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
 * <p><strong>Two declared finders, and no more.</strong> Keyed access to the base cluster is the
 * inherited {@code findById}; a rewrite is the inherited {@code save}. The two finders below are the
 * whole of the alternate-index surface this table needs: one answers which rows an account carries, and
 * one answers which single row a legacy keyed read of the path returns.
 *
 * @see CardCrossReference
 */
public interface CardCrossReferenceRepository extends JpaRepository<CardCrossReference, String> {
    /**
     * Every cross-reference row of one account: the {@code CXACAIX} alternate-index access path.
     *
     * <p>The return type is a list because the alternate key is non-unique and an account may carry
     * several cards. A single-valued derived query would raise an incorrect-result-size failure the
     * moment two rows shared an account identifier, which is a failure the legacy read of a
     * duplicate-bearing path cannot produce.
     *
     * <p><strong>Use this only when every row of the account is genuinely wanted.</strong> A caller
     * reproducing a legacy keyed READ of this path wants one row - the first duplicate in ascending
     * base-key order - and asks for it through
     * {@link #findFirstByXrefAcctIdOrderByXrefCardNumAsc(String)} instead. Five services once
     * materialised every row here and then discarded all but the lowest, five copies of one rule paying
     * five times for rows they threw away; the rule has one home again, and the reasoning is recorded as
     * {@code DL-121} and its restoration as {@code DL-164} in {@code docs/decision-log.md}.
     *
     * <p>No ordering term is declared, because a caller that wants every row and depends on its
     * sequence sorts what it receives, so the sort is visible at the site whose behaviour depends on it.
     *
     * <p>An empty list is the analogue of the legacy not-found response and is not an error here; the
     * service turns absence into a screen message.
     *
     * <p>The reference seed is one-to-one across 50 accounts, 50 cards and 50 cross-reference rows, so
     * it exercises this method without stressing it: demonstrating multi-row retrieval or the
     * first-match rule requires a purpose-built fixture holding two rows that share an account
     * identifier.
     *
     * @param xrefAcctId the eleven-character account identifier, matched exactly as supplied; its
     *                   leading zeros are significant and it is never trimmed
     * @return the matching rows, possibly empty, never {@code null}
     */
    List<CardCrossReference> findByXrefAcctId(String xrefAcctId);

    /**
     * The one cross-reference row of an account that a legacy keyed READ of {@code CXACAIX} returns.
     *
     * <p>A keyed read of a duplicate-bearing alternate index yields the first record in ascending
     * base-key order, and the base key of this cluster is the card number, so the ordering term is the
     * legacy rule itself rather than a preference. The read is bounded to one row, so an account
     * carrying many cards costs no more than one carrying a single card - which is the whole difference
     * between this method and {@link #findByXrefAcctId(String)}.
     *
     * <p>Both verified legacy consumers issue a single keyed READ of this path rather than a browse, so
     * this is the shape those services need; the list form remains for the callers that genuinely want
     * every row. Keeping both is deliberate: one states "which row does a keyed read return", the other
     * "which rows does this account carry", and the non-unique index exists precisely because those are
     * different questions.
     *
     * <p>An empty result is the analogue of the legacy not-found response and is not an error here; the
     * service turns absence into that screen's own message.
     *
     * @param xrefAcctId the eleven-character account identifier, matched exactly as supplied; its
     *                   leading zeros are significant and it is never trimmed
     * @return the row with the lowest card number among the account's rows, or empty when it carries
     *         none
     */
    Optional<CardCrossReference> findFirstByXrefAcctIdOrderByXrefCardNumAsc(String xrefAcctId);
}
