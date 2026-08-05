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
 * <p><strong>One declared finder, and no more.</strong> Keyed access to the base cluster is the
 * inherited {@code findById}; a rewrite is the inherited {@code save}. The single finder below is the
 * whole of the alternate-index surface this table needs.
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
     * <p><strong>The legacy first-match rule is the service's to apply, not this method's.</strong>
     * Both verified legacy consumers issue a single keyed READ of this path rather than a browse, and a
     * keyed read of a duplicate-bearing alternate index returns the first record in ascending base-key
     * order - the base key here being the card number. That is a property of the READ being reproduced
     * rather than of this index, so the services that reproduce it select the row with the lowest card
     * number themselves and say so where they do it. Declaring {@code findFirstBy...} here would move a
     * single-row decision into the persistence contract and conceal from every caller that the account
     * may carry more than one row - which is exactly what the non-unique index exists to represent. The
     * decision is recorded as {@code DL-121} in {@code docs/decision-log.md}.
     *
     * <p>No ordering term is declared for the same reason: a caller that depends on sequence sorts what
     * it receives, so the sort is visible at the site whose behaviour depends on it.
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
}
