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

import com.carddemo.domain.Transaction;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.repository.Repository;

/**
 * Bounded ordered-read view of the transaction master, kept separate from the
 * {@link TransactionRepository} write-and-lock contract. Both the batch archive scan and the online
 * transaction-list browse read through it, because they are the same access pattern - an ordered walk of
 * the primary key - and one contract keeps one statement of the ordering rather than two that could
 * drift apart.
 *
 * <p>This interface extends the marker {@link Repository} rather than a store interface, so the four
 * reads below are its whole surface: there is no {@code findAll()}, no {@code deleteAll()} and no offset
 * {@code Pageable} anywhere on it. Every read is ordered on the primary key and bounded by an explicit
 * {@link Limit}, which is what makes the callers' cost independent of how large the table grows.
 *
 * <p><strong>Two directions, each in an inclusive and an exclusive form, and the pairing is the
 * point.</strong> The legacy browse verbs are a positioning command followed by record-at-a-time reads:
 * the positioning command is greater-or-equal (or less-or-equal, read backwards) and the first read
 * after it returns the record positioned on, while every read after that moves strictly past the last
 * record handed out. The inclusive forms therefore serve the open and the exclusive forms the
 * continuation; collapsing the pair would either repeat the boundary row on every refill or skip the row
 * the browse positioned on.
 *
 * <p><strong>Why keyset and never an offset page.</strong> An offset page recounts and discards every
 * earlier row on each fetch and is not stable, because a row inserted or removed between two fetches
 * shifts the window and a row is delivered twice or missed. The legacy browse retains a transaction
 * identifier and repositions on that value, so reading by key is the faithful translation as well as the
 * bounded one - and it is why no method here accepts a page number.
 *
 * <p>Ordering is the store's, on a column whose stored values are sixteen zero-padded digit characters,
 * so character order and numeric order coincide. That is a precondition on the data rather than a
 * property of the column - the same precondition {@link TransactionRepository#findMaxId()} depends on -
 * and every writer in this module preserves it.
 */
public interface TransactionScanRepository extends Repository<Transaction, String> {

    /**
     * Reads one bounded window strictly after a transaction identifier, ascending.
     *
     * <p>The continuation read of a forward walk. The cursor is the identifier of the last row already
     * handed out and the comparison is strict, so that row is never delivered twice. The identifier is
     * the primary key and therefore unique, which makes the single-column cursor total.
     *
     * @param tranId exclusive lower key bound - the last identifier already handed out, matched exactly
     *               as supplied and never trimmed
     * @param limit  maximum rows returned
     * @return rows in ascending transaction-key order, at most {@code limit} of them, possibly empty
     *         and never {@code null}
     */
    List<Transaction> findByTranIdGreaterThanOrderByTranIdAsc(String tranId, Limit limit);

    /**
     * Reads one bounded window at or after a transaction identifier, ascending.
     *
     * <p>The opening read of a forward walk, which is greater-or-<em>equal</em> because that is what a
     * browse-start command does by default: a key that matches a stored row positions on it, and a key
     * that matches nothing positions on the next higher one. Its first row is therefore the row the
     * browse positioned on, and the rest of the window is the walk's read-ahead.
     *
     * @param tranId inclusive lower key bound, matched exactly as supplied and never trimmed; a blank
     *               value is below every stored identifier and therefore opens at the first row
     * @param limit  maximum rows returned
     * @return rows in ascending transaction-key order, at most {@code limit} of them, possibly empty
     *         and never {@code null}
     */
    List<Transaction> findByTranIdGreaterThanEqualOrderByTranIdAsc(String tranId, Limit limit);

    /**
     * Reads one bounded window strictly before a transaction identifier, descending.
     *
     * <p>The continuation read of a backward walk. <strong>Descending is the read order and not the
     * presentation order:</strong> the legacy backward path fills its bottom screen slot first and
     * works upward, so the assembled page ascends exactly like a forward page. Returning these rows
     * ascending would silently reverse the page.
     *
     * @param tranId exclusive upper key bound - the first identifier already handed out, matched
     *               exactly as supplied and never trimmed
     * @param limit  maximum rows returned
     * @return rows in descending transaction-key order, at most {@code limit} of them, possibly empty
     *         and never {@code null}
     */
    List<Transaction> findByTranIdLessThanOrderByTranIdDesc(String tranId, Limit limit);

    /**
     * Reads one bounded window at or before a transaction identifier, descending.
     *
     * <p>The opening read of a backward walk, inclusive for the same reason its forward counterpart is:
     * a browse-start positions on the key when it exists and the first read after it returns that row.
     * Read backwards, "at or after" becomes "at or before", so this is the less-or-equal form.
     *
     * @param tranId inclusive upper key bound, matched exactly as supplied and never trimmed; a blank
     *               value is below every stored identifier, so a backward walk opened on one yields
     *               nothing at all
     * @param limit  maximum rows returned
     * @return rows in descending transaction-key order, at most {@code limit} of them, possibly empty
     *         and never {@code null}
     */
    List<Transaction> findByTranIdLessThanEqualOrderByTranIdDesc(String tranId, Limit limit);
}
