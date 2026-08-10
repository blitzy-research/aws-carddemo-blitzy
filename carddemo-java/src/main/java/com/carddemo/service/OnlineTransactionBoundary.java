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
 * Owns the durable repository unit of work for one online write operation.
 *
 * <p>Screen services deliberately remain non-transactional so they can translate a persistence
 * failure into the legacy screen outcome after the failed transaction has completed its rollback.
 * Calling through this separate Spring bean guarantees that repository work executes behind a proxy;
 * {@link Propagation#REQUIRES_NEW} also prevents an accidental caller transaction from turning the
 * outer error-mapping code into part of the same rollback-only unit of work.
 *
 * <p>The callback is intentionally generic and contains no screen semantics. Each caller defines the
 * exact repository sequence its COBOL paragraph owns, and any runtime failure leaves this method so
 * Spring rolls back before control returns to the caller's response-mapping arm.
 */
@Service
public class OnlineTransactionBoundary {

    /**
     * Executes one online persistence operation in an independent transaction.
     *
     * @param operation complete repository operation for one screen write
     * @param <T>       committed result type
     * @return the operation result after a successful commit
     * @throws NullPointerException if the operation is absent
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T execute(final Supplier<T> operation) {
        return Objects.requireNonNull(operation, "operation must not be null").get();
    }
}
