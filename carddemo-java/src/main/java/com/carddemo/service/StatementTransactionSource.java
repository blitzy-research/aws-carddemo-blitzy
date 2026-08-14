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

/**
 * Frozen, ordered transaction-work input consumed by one statement-generation run.
 *
 * <p>Each value is the projected fixed-width COSTM01 record produced by the statement job's sort and
 * loaded into its transient work resource. The source is passed from the batch layer into the service
 * layer explicitly so statement generation cannot re-query the mutable live transaction master after
 * the sort step has established its snapshot.
 *
 * <p>The position is zero-based and advances exactly as the legacy sequential read advances. Exhaustion
 * is represented by an empty result and remains distinct from a technical failure.
 */
@FunctionalInterface
public interface StatementTransactionSource {

    /**
     * Reads one projected transaction-work record from the frozen sequence.
     *
     * @param position zero-based sequential position
     * @return the projected record at that position, or empty at end of file
     * @throws IllegalArgumentException if {@code position} is negative
     */
    Optional<String> readAt(int position);
}
