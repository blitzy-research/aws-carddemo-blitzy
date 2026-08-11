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
package com.carddemo.util;

import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Sequential cursor over repository pages addressed by the last business key already delivered.
 *
 * <p>The cursor retains one caller-sized page and one key. It never computes an offset, requests a
 * count, or accumulates earlier pages, so memory and query cost remain bounded as the table grows.
 * Every page loader must implement a strict {@code key > cursor} predicate in the same total order
 * represented by {@code keyOrder}. The iterator verifies that contract at the boundary: a null key,
 * an over-sized page, or a key that does not advance fails immediately instead of looping forever or
 * silently duplicating a record.
 *
 * <p>The loader is invoked lazily. Constructing a cursor performs no query, which lets callers retain
 * the legacy distinction between opening a resource and attempting its first read.
 *
 * @param <T> the record type
 * @param <K> the total business-key type
 */
public final class BoundedKeysetIterator<T, K> implements Iterator<T> {

    /** Default maximum rows retained by one batch scan. */
    public static final int DEFAULT_PAGE_SIZE = 256;

    private final int pageSize;
    private final BiFunction<K, Integer, List<T>> pageLoader;
    private final Function<T, K> keyExtractor;
    private final Comparator<? super K> keyOrder;

    private Iterator<T> currentPage = Collections.emptyIterator();
    private K cursor;
    private boolean exhausted;
    private T pending;
    private boolean pendingAvailable;

    /**
     * Creates a cursor positioned immediately after {@code initialCursor}.
     *
     * @param initialCursor exclusive lower bound for the first page
     * @param pageSize maximum rows one loader invocation may return
     * @param pageLoader loads at most {@code pageSize} rows strictly after its cursor
     * @param keyExtractor extracts the total key from one row
     * @param keyOrder compares keys in the loader's declared order
     */
    public BoundedKeysetIterator(final K initialCursor, final int pageSize,
            final BiFunction<K, Integer, List<T>> pageLoader,
            final Function<T, K> keyExtractor,
            final Comparator<? super K> keyOrder) {

        this.cursor = Objects.requireNonNull(initialCursor, "initialCursor must not be null");
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be at least one: " + pageSize);
        }
        this.pageSize = pageSize;
        this.pageLoader = Objects.requireNonNull(pageLoader, "pageLoader must not be null");
        this.keyExtractor = Objects.requireNonNull(keyExtractor, "keyExtractor must not be null");
        this.keyOrder = Objects.requireNonNull(keyOrder, "keyOrder must not be null");
    }

    @Override
    public boolean hasNext() {
        if (this.pendingAvailable) {
            return true;
        }
        while (!this.exhausted) {
            if (this.currentPage.hasNext()) {
                this.pending = Objects.requireNonNull(this.currentPage.next(),
                        "a keyset page must not contain a null record");
                this.pendingAvailable = true;
                return true;
            }
            loadNextPage();
        }
        return false;
    }

    @Override
    public T next() {
        if (!hasNext()) {
            throw new NoSuchElementException("the bounded keyset cursor is exhausted");
        }
        final T delivered = this.pending;
        final K deliveredKey = Objects.requireNonNull(this.keyExtractor.apply(delivered),
                "a keyset record must carry a non-null key");
        if (this.keyOrder.compare(deliveredKey, this.cursor) <= 0) {
            throw new IllegalStateException(
                    "a keyset page returned a key that did not advance its exclusive cursor");
        }
        this.cursor = deliveredKey;
        this.pending = null;
        this.pendingAvailable = false;
        return delivered;
    }

    private void loadNextPage() {
        final List<T> loaded = Objects.requireNonNull(
                this.pageLoader.apply(this.cursor, Integer.valueOf(this.pageSize)),
                "pageLoader must return a list, never null");
        if (loaded.size() > this.pageSize) {
            throw new IllegalStateException("pageLoader returned " + loaded.size()
                    + " rows for a page bounded to " + this.pageSize);
        }
        if (loaded.isEmpty()) {
            this.exhausted = true;
            this.currentPage = Collections.emptyIterator();
            return;
        }
        this.currentPage = loaded.iterator();
    }
}
