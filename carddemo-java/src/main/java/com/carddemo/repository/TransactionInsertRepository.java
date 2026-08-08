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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Insert-only persistence for assigned-key transaction records.
 *
 * <p>The transaction identifier is the legacy sixteen-character business key, so every new record
 * reaches persistence with its identifier already populated. The ordinary Spring Data
 * {@code save} operation treats an assigned identifier as a possible existing entity and may merge
 * it, which can overwrite a row where the legacy keyed-file write would instead report a duplicate.
 * This fragment publishes the narrower operation the translated write paragraphs require: insert
 * one record and force the database response before returning.
 *
 * <p>The operation intentionally returns the same managed instance it receives. No identifier is
 * generated, no database sequence is consulted, and no upsert or merge fallback exists. A duplicate
 * primary key therefore remains observable to the caller at the exact write boundary that owns the
 * legacy duplicate response.
 *
 * <p>Legacy sources are read-only reference at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
public interface TransactionInsertRepository {

    /** Transaction-scoped PostgreSQL advisory-lock key shared by every max-plus-one allocator. */
    long IDENTIFIER_ALLOCATION_LOCK_KEY = 350_016L;

    /**
     * Serialises the maximum-read and insert sequence for an assigned transaction identifier.
     *
     * <p>One application-wide lock, keyed by {@link #IDENTIFIER_ALLOCATION_LOCK_KEY}, taken before the
     * maximum is read and released when the calling transaction ends - by commit and by rollback alike. It
     * is the relational form of the position the legacy region held on the keyed file across its own
     * backward read, increment and write, and it is re-entrant within a session, so one acquisition covers
     * every attempt a caller makes inside the same unit of work.
     *
     * <p><strong>A transaction is mandatory, and that is a correctness requirement rather than a
     * convention.</strong> The lock is transaction-scoped: taken with no transaction in progress, the
     * statement is its own transaction and the lock is released the instant it completes, so two allocators
     * would both be granted it, both read the same maximum and collide - the exact outcome the lock exists
     * to prevent, reached with no error and no diagnostic. Requiring an existing transaction makes that
     * mistake a loud failure at the call site instead of an intermittent duplicate key in production. The
     * implementation asserts the same condition itself, so a caller that reaches the fragment without going
     * through the repository proxy is refused too.
     *
     * @param  lockKey the stable application lock key
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is in
     *         progress
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void lockIdentifierAllocation(long lockKey);

    /**
     * Persists one new transaction and flushes it immediately.
     *
     * @param transaction the assigned-key record to insert; must not be {@code null}
     * @return the same managed transaction instance
     * @throws NullPointerException if {@code transaction} is {@code null}
     * @throws RuntimeException if the database refuses the insert, including a duplicate key
     */
    @Transactional
    Transaction insertAndFlush(Transaction transaction);
}