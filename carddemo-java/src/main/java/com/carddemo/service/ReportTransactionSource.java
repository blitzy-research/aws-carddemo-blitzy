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

import java.util.Optional;

import com.carddemo.domain.Transaction;

/**
 * Frozen, ordered transaction input consumed by one transaction-report run.
 *
 * <p>The batch job creates this source from the filtered and card-number-ordered generation produced
 * by its preceding sort step. Passing that snapshot into the service explicitly prevents report
 * generation from re-querying the mutable live transaction master after the sort boundary has fixed
 * the run's input.
 *
 * <p>The position is zero-based and advances exactly as the legacy sequential read advances.
 * Exhaustion is represented by an empty result and remains distinct from a technical failure.
 */
@FunctionalInterface
public interface ReportTransactionSource {

    /**
     * Reads one transaction from the frozen ordered sequence.
     *
     * @param position zero-based sequential position
     * @return the transaction at that position, or empty at end of file
     * @throws IllegalArgumentException if {@code position} is negative
     */
    Optional<Transaction> readAt(int position);
}