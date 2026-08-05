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

/**
 * Complete immutable input for one transaction-report generation.
 *
 * @param dateParameterCard the validated date-range record consumed by the report program
 * @param transactionSource the frozen ordered generation produced by the preceding batch step
 */
public record ReportTransactionInput(String dateParameterCard,
                                     ReportTransactionSource transactionSource) {

    /**
     * Rejects an absent component so the processor cannot accidentally fall back to live data.
     */
    public ReportTransactionInput {
        Objects.requireNonNull(dateParameterCard, "dateParameterCard must not be null");
        Objects.requireNonNull(transactionSource, "transactionSource must not be null");
    }
}