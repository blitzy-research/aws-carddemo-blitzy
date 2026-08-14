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

import com.carddemo.domain.TransactionCategoryBalance;
import com.carddemo.domain.id.TransactionCategoryBalanceId;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence gateway for the {@code transaction_category_balance} table - the relational form of
 * the 50-byte record of {@code app/cpy/CVTRA01Y.cpy}, replacing the {@code TCATBALF} VSAM base
 * cluster.
 *
 * <p>Identity is the legacy three-part business key modelled by
 * {@link TransactionCategoryBalanceId}. Its component order - account identifier, then transaction
 * type, then transaction category - is contractual, because that is the order the key occupies in
 * the record image; the entity's column prefixes reproduce the copybook's own naming rather than
 * being regularised.
 *
 * <p>Nothing is declared here. The schema supports only the full-key read and the sequential or
 * paged scan, both inherited, so a derived finder would describe an access path no index serves. A
 * key that matches no row yields an empty {@link java.util.Optional} rather than an exception,
 * because the accrual tier turns that miss into its own documented fallback. The balance is carried
 * as an exact decimal and no arithmetic happens in this package.
 *
 * @see TransactionCategoryBalance
 */
public interface TransactionCategoryBalanceRepository
        extends JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId> {

    /**
     * Reads one bounded page after a complete composite-key cursor.
     *
     * <p>The disjunction is the lexicographic form of
     * {@code (account, type, category) > (:account, :type, :category)}. Callers always supply page
     * zero with a bounded size; the key predicate, rather than an offset, advances the scan.
     *
     * @param accountId account component of the exclusive lower bound
     * @param typeCode transaction-type component of the exclusive lower bound
     * @param categoryCode transaction-category component of the exclusive lower bound
     * @param page bounded page-zero request
     * @return rows in complete record-key order
     */
    @Query("""
            SELECT balance
            FROM TransactionCategoryBalance balance
            WHERE balance.trancatAcctId > :accountId
               OR (balance.trancatAcctId = :accountId
                   AND balance.trancatTypeCd > :typeCode)
               OR (balance.trancatAcctId = :accountId
                   AND balance.trancatTypeCd = :typeCode
                   AND balance.trancatCd > :categoryCode)
            ORDER BY balance.trancatAcctId ASC,
                     balance.trancatTypeCd ASC,
                     balance.trancatCd ASC
            """)
    List<TransactionCategoryBalance> findAfterKey(
            @Param("accountId") String accountId,
            @Param("typeCode") String typeCode,
            @Param("categoryCode") String categoryCode,
            Pageable page);
}
