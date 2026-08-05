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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.repository;

import com.carddemo.domain.Account;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.repository.Repository;

/**
 * Bounded sequential-read view of the account master, kept separate from the frozen online
 * {@link AccountRepository} contract.
 */
public interface AccountScanRepository extends Repository<Account, String> {

    /**
     * Reads one primary-key page strictly after the supplied account identifier.
     *
     * @param acctId exclusive lower key bound
     * @param limit maximum rows returned
     * @return rows in ascending account-key order
     */
    List<Account> findByAcctIdGreaterThanOrderByAcctIdAsc(String acctId, Limit limit);
}