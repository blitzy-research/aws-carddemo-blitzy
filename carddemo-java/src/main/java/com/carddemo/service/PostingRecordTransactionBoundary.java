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
package com.carddemo.service;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens the durable unit of work for one posted daily-transaction record.
 *
 * <p>The legacy posting member hardens work per record: the transaction-file write, the account
 * rewrite and the category-balance update of lines 440 to 442 either all take effect for a record or
 * none of them does, and the next record starts from whatever the previous one left committed. That is
 * the boundary this collaborator owns.
 *
 * <p><strong>Why a separate bean.</strong> {@link TransactionPostingService} reproduces the mainline
 * loop in its own method, so a per-record annotation on that class could only ever be applied to a
 * call arriving from outside it - a self-invocation reaches the target directly and no proxy is
 * consulted, so the annotation silently does nothing for every record the loop drives. Placing the
 * boundary on a different bean removes the possibility: the loop necessarily calls through this
 * proxy, so exactly one per-record path exists and it is the transactional one.
 *
 * <p><strong>Why {@link Propagation#REQUIRED} rather than {@code REQUIRES_NEW}.</strong> A record is
 * the unit of atomicity, not the unit of isolation. When no caller has opened a transaction - the
 * batch step's normal shape - {@code REQUIRED} starts one per record, which is the legacy boundary
 * exactly. When a caller has deliberately opened one, a record joins it rather than committing
 * independently inside it, so a caller cannot end up with a record hardened while the work it wrapped
 * the run in rolls back. Suspending the caller's transaction, which is what {@code REQUIRES_NEW}
 * would do, would produce that split. Neither shape wraps the whole run in one transaction: nothing
 * here opens a transaction around the loop, and the loop itself stays outside every one of them, so
 * input scanning, the reject sink and Spring Batch metadata remain uncommitted work of the step rather
 * than of a record.
 *
 * <p>The callback carries no posting semantics of its own. Any unchecked failure that escapes it - an
 * abend, a file-status failure, or an optimistic-lock conflict on the account's version attribute -
 * leaves this method, so the framework rolls that record's stages back together before control returns
 * to the loop, which then continues with the next record.
 *
 * <p>Provenance: {@code app/cbl/CBTRN02C.cbl}, the mainline of lines 208 to 230 and the three posting
 * stages of lines 440 to 442; read as read-only reference at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL source text is transcribed.
 *
 * @since 1.0.0
 */
@Service
public class PostingRecordTransactionBoundary {

    /**
     * Executes the complete processing of one daily-transaction record in a transaction.
     *
     * @param operation the reset-validate-then-post-or-reject operation for one record
     * @param <T>       the per-record result type
     * @return the operation's result once the record's transaction has committed, or once it has
     *     joined and returned to a caller-owned transaction
     * @throws NullPointerException if the operation is absent
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public <T> T execute(final Supplier<T> operation) {
        return Objects.requireNonNull(operation, "operation must not be null").get();
    }
}
