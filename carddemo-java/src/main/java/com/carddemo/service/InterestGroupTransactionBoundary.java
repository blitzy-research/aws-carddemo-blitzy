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
 * Opens the durable transaction for one closed CBACT04C account group.
 *
 * <p>The legacy interest run hardens work at its account control break rather than at the end of the
 * complete input file. This collaborator is deliberately separate from
 * {@link InterestCalculationService}: calls through its Spring proxy therefore establish a real
 * transaction even when the service reaches the boundary from its own whole-file driver. A transaction
 * already owned by a caller is suspended so a later account failure cannot roll back an earlier
 * account that completed successfully.
 *
 * <p>The callback contains only the group operation. Input scanning, output-generation I/O and Spring
 * Batch metadata remain outside this transaction, so the boundary cannot accidentally grow into a
 * whole-step transaction.
 */
@Service
public class InterestGroupTransactionBoundary {

    /**
     * Executes one closed account group in its own transaction.
     *
     * @param operation the complete read-rate-compute-account-rewrite operation for one account group
     * @param <T>       the group result type
     * @return the operation's result after the group transaction commits
     * @throws NullPointerException if the operation is absent
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T execute(final Supplier<T> operation) {
        return Objects.requireNonNull(operation, "operation must not be null").get();
    }
}