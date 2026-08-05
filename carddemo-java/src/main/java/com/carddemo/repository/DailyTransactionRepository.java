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

import com.carddemo.domain.DailyTransaction;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence gateway for the {@code daily_transaction} table - the landing surface holding the
 * raw, deliberately unvalidated daily input the posting pipeline consumes, mapped from the 350-byte
 * record of {@code app/cpy/CVTRA06Y.cpy}.
 *
 * <p>Nothing is declared here: the batch reader walks the table through the inherited sorted and
 * paged operations and the keyed read is inherited too. Validation, the five reject reason codes
 * and the 430-byte reject record all belong to the batch tier, so this interface neither filters
 * nor judges a row - a record the legacy job would have rejected must still load unchanged.
 *
 * @see DailyTransaction
 */
public interface DailyTransactionRepository extends JpaRepository<DailyTransaction, String> {

    /**
     * Reads one bounded primary-key page after the supplied daily-transaction identifier.
     *
     * @param dalytranId exclusive lower key bound
     * @param limit maximum rows returned
     * @return rows in ascending daily-transaction-key order
     */
    List<DailyTransaction> findByDalytranIdGreaterThanOrderByDalytranIdAsc(
            String dalytranId, Limit limit);
}
