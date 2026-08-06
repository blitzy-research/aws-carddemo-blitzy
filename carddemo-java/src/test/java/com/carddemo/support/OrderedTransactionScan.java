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
package com.carddemo.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import com.carddemo.domain.Transaction;
import com.carddemo.repository.TransactionScanRepository;

/**
 * An in-memory {@link TransactionScanRepository} that behaves like an ordered primary-key cluster in
 * either direction, for tests that need the browse contract without a database.
 *
 * <p><strong>Why this exists as a class rather than a lambda.</strong> The scan view publishes four
 * reads - inclusive and exclusive, ascending and descending - so it is no longer a functional
 * interface, and three batch test classes had each written the same one-direction lambda. One
 * implementation of all four means a test cannot accidentally exercise a direction the stub gets
 * wrong, and the bound and limit semantics are stated once: the bound is strict for the two
 * continuation reads and inclusive for the two opening reads, the order is ascending for the forward
 * pair and descending for the backward pair, and every read truncates to the requested limit.
 *
 * <p>The rows are supplied lazily so a caller may back this with a mock whose answer changes between
 * calls; each read re-reads the supplier and re-sorts, which is what a real query does.
 */
public final class OrderedTransactionScan implements TransactionScanRepository {

    /** Supplies the current contents of the simulated cluster on every read. */
    private final Supplier<List<Transaction>> rows;

    /**
     * Creates a scan view over a supplier of rows.
     *
     * @param rows the current rows, in any order; never {@code null} and never supplying {@code null}
     */
    public OrderedTransactionScan(final Supplier<List<Transaction>> rows) {
        this.rows = Objects.requireNonNull(rows, "rows must not be null");
    }

    @Override
    public List<Transaction> findByTranIdGreaterThanOrderByTranIdAsc(final String tranId,
            final org.springframework.data.domain.Limit limit) {
        return window(tranId, limit.max(), true, false);
    }

    @Override
    public List<Transaction> findByTranIdGreaterThanEqualOrderByTranIdAsc(final String tranId,
            final org.springframework.data.domain.Limit limit) {
        return window(tranId, limit.max(), true, true);
    }

    @Override
    public List<Transaction> findByTranIdLessThanOrderByTranIdDesc(final String tranId,
            final org.springframework.data.domain.Limit limit) {
        return window(tranId, limit.max(), false, false);
    }

    @Override
    public List<Transaction> findByTranIdLessThanEqualOrderByTranIdDesc(final String tranId,
            final org.springframework.data.domain.Limit limit) {
        return window(tranId, limit.max(), false, true);
    }

    /**
     * Returns one bounded ordered window.
     *
     * @param bound     the key bound
     * @param limit     the maximum number of rows
     * @param ascending whether the read order is ascending
     * @param inclusive whether a row whose key equals the bound is returned
     * @return the matching rows in read order, at most {@code limit} of them
     */
    private List<Transaction> window(final String bound, final int limit, final boolean ascending,
            final boolean inclusive) {
        final List<Transaction> ordered = new ArrayList<>(this.rows.get());
        ordered.sort(ascending
                ? Comparator.comparing(Transaction::getTranId)
                : Comparator.comparing(Transaction::getTranId).reversed());
        return ordered.stream()
                .filter(record -> retains(record.getTranId(), bound, ascending, inclusive))
                .limit(limit)
                .toList();
    }

    /**
     * Applies one read's key bound to one row.
     *
     * @param key       the row's key
     * @param bound     the bound the read declares
     * @param ascending whether the read order is ascending
     * @param inclusive whether an equal key is retained
     * @return {@code true} when the row falls inside the bound
     */
    private static boolean retains(final String key, final String bound, final boolean ascending,
            final boolean inclusive) {
        final int comparison = key.compareTo(bound);
        if (ascending) {
            return inclusive ? comparison >= 0 : comparison > 0;
        }
        return inclusive ? comparison <= 0 : comparison < 0;
    }
}
