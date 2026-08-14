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

import com.carddemo.domain.Customer;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence gateway for the {@code customer} table - the relational form of the 500-byte
 * {@code CUSTOMER-RECORD} of copybook {@code CVCUS01Y}, keyed on the 9-character customer
 * identifier the record carries at offset 0.
 *
 * <p>The keyed read the account and statement paths need and the sequential scan the customer extract
 * needs are inherited from {@link JpaRepository}. The account-update path adds one operation:
 * {@link #compareAndSet(Customer, Customer)}, an atomic rewrite that updates the unversioned customer
 * only while every field still equals the held before-image. Values are otherwise stored and returned
 * exactly as supplied, so an identifier keeps the leading zeros the record image published.
 *
 * <p>{@code cust_ssn} and {@code govt_issued_id} are protected at rest and are consequently wider
 * than their legacy fields. This interface performs no encryption, masking or redaction of its own
 * and logs nothing about these rows.
 *
 * @see Customer
 */
public interface CustomerRepository extends JpaRepository<Customer, String> {

    /**
     * Reads one bounded primary-key page after the supplied customer identifier.
     *
     * @param custId exclusive lower key bound
     * @param limit maximum rows returned
     * @return rows in ascending customer-key order
     */
    List<Customer> findByCustIdGreaterThanOrderByCustIdAsc(String custId, Limit limit);

    /**
     * Atomically rewrites one customer only while the row still matches the held before-image.
     *
     * <p>The customer deliberately has no {@code @Version}: the AAP assigns version columns to Account
     * and Card only. The legacy account-update program nevertheless compares the complete customer
     * before-image under its update hold, after the account rewrite and immediately before the customer
     * rewrite. A repository {@code save} cannot reproduce that second check; it merges by identifier and
     * silently overwrites a customer-only change committed after the service's token verification.
     *
     * <p>This method keeps the public call site readable and delegates to the fully parameterised JPQL
     * update below. No reflection, expression-language property access, native SQL or string assembly is
     * involved. The key may not change, because the source rewrites the record addressed by the key it
     * read and never moves a new key into it.
     *
     * @param  before the row image held before applying the operator's changes
     * @param  after  the replacement image, carrying the same key
     * @return exactly {@code 1} when the row matched and was rewritten; {@code 0} when it moved
     * @throws NullPointerException     if either image is absent
     * @throws IllegalArgumentException if the replacement carries a different key
     */
    default int compareAndSet(final Customer before, final Customer after) {
        Objects.requireNonNull(before, "before customer must not be null");
        Objects.requireNonNull(after, "after customer must not be null");
        if (!Objects.equals(before.getCustId(), after.getCustId())) {
            throw new IllegalArgumentException("customer compare-and-set cannot change the key");
        }
        return compareAndSetValues(
                before.getCustId(),
                before.getFirstName(),
                before.getMiddleName(),
                before.getLastName(),
                before.getAddrLine1(),
                before.getAddrLine2(),
                before.getAddrLine3(),
                before.getAddrStateCd(),
                before.getAddrCountryCd(),
                before.getAddrZip(),
                before.getPhoneNum1(),
                before.getPhoneNum2(),
                before.getCustSsn(),
                before.getGovtIssuedId(),
                before.getCustDob(),
                before.getEftAccountId(),
                before.getPriCardHolderInd(),
                before.getFicoCreditScore(),
                after.getFirstName(),
                after.getMiddleName(),
                after.getLastName(),
                after.getAddrLine1(),
                after.getAddrLine2(),
                after.getAddrLine3(),
                after.getAddrStateCd(),
                after.getAddrCountryCd(),
                after.getAddrZip(),
                after.getPhoneNum1(),
                after.getPhoneNum2(),
                after.getCustSsn(),
                after.getGovtIssuedId(),
                after.getCustDob(),
                after.getEftAccountId(),
                after.getPriCardHolderInd(),
                after.getFicoCreditScore());
    }

    /**
     * Parameter-level form of {@link #compareAndSet(Customer, Customer)}.
     *
     * <p>{@code custSsn} is the table's only nullable field in this comparison, so its predicate uses
     * explicit null equality. Every other column is not-null in the shipped schema and can use ordinary
     * equality. Flushing first guarantees earlier account work reaches the provider before this update;
     * clearing afterwards prevents the stale managed customer read earlier in the turn from being
     * auto-flushed over the compare-and-set result.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE Customer customer
               SET customer.firstName = :newFirstName,
                   customer.middleName = :newMiddleName,
                   customer.lastName = :newLastName,
                   customer.addrLine1 = :newAddrLine1,
                   customer.addrLine2 = :newAddrLine2,
                   customer.addrLine3 = :newAddrLine3,
                   customer.addrStateCd = :newAddrStateCd,
                   customer.addrCountryCd = :newAddrCountryCd,
                   customer.addrZip = :newAddrZip,
                   customer.phoneNum1 = :newPhoneNum1,
                   customer.phoneNum2 = :newPhoneNum2,
                   customer.custSsn = :newCustSsn,
                   customer.govtIssuedId = :newGovtIssuedId,
                   customer.custDob = :newCustDob,
                   customer.eftAccountId = :newEftAccountId,
                   customer.priCardHolderInd = :newPriCardHolderInd,
                   customer.ficoCreditScore = :newFicoCreditScore
             WHERE customer.custId = :custId
               AND customer.firstName = :oldFirstName
               AND customer.middleName = :oldMiddleName
               AND customer.lastName = :oldLastName
               AND customer.addrLine1 = :oldAddrLine1
               AND customer.addrLine2 = :oldAddrLine2
               AND customer.addrLine3 = :oldAddrLine3
               AND customer.addrStateCd = :oldAddrStateCd
               AND customer.addrCountryCd = :oldAddrCountryCd
               AND customer.addrZip = :oldAddrZip
               AND customer.phoneNum1 = :oldPhoneNum1
               AND customer.phoneNum2 = :oldPhoneNum2
               AND (customer.custSsn = :oldCustSsn
                    OR (customer.custSsn IS NULL AND :oldCustSsn IS NULL))
               AND customer.govtIssuedId = :oldGovtIssuedId
               AND customer.custDob = :oldCustDob
               AND customer.eftAccountId = :oldEftAccountId
               AND customer.priCardHolderInd = :oldPriCardHolderInd
               AND customer.ficoCreditScore = :oldFicoCreditScore
            """)
    int compareAndSetValues(
            @Param("custId") String custId,
            @Param("oldFirstName") String oldFirstName,
            @Param("oldMiddleName") String oldMiddleName,
            @Param("oldLastName") String oldLastName,
            @Param("oldAddrLine1") String oldAddrLine1,
            @Param("oldAddrLine2") String oldAddrLine2,
            @Param("oldAddrLine3") String oldAddrLine3,
            @Param("oldAddrStateCd") String oldAddrStateCd,
            @Param("oldAddrCountryCd") String oldAddrCountryCd,
            @Param("oldAddrZip") String oldAddrZip,
            @Param("oldPhoneNum1") String oldPhoneNum1,
            @Param("oldPhoneNum2") String oldPhoneNum2,
            @Param("oldCustSsn") String oldCustSsn,
            @Param("oldGovtIssuedId") String oldGovtIssuedId,
            @Param("oldCustDob") String oldCustDob,
            @Param("oldEftAccountId") String oldEftAccountId,
            @Param("oldPriCardHolderInd") String oldPriCardHolderInd,
            @Param("oldFicoCreditScore") String oldFicoCreditScore,
            @Param("newFirstName") String newFirstName,
            @Param("newMiddleName") String newMiddleName,
            @Param("newLastName") String newLastName,
            @Param("newAddrLine1") String newAddrLine1,
            @Param("newAddrLine2") String newAddrLine2,
            @Param("newAddrLine3") String newAddrLine3,
            @Param("newAddrStateCd") String newAddrStateCd,
            @Param("newAddrCountryCd") String newAddrCountryCd,
            @Param("newAddrZip") String newAddrZip,
            @Param("newPhoneNum1") String newPhoneNum1,
            @Param("newPhoneNum2") String newPhoneNum2,
            @Param("newCustSsn") String newCustSsn,
            @Param("newGovtIssuedId") String newGovtIssuedId,
            @Param("newCustDob") String newCustDob,
            @Param("newEftAccountId") String newEftAccountId,
            @Param("newPriCardHolderInd") String newPriCardHolderInd,
            @Param("newFicoCreditScore") String newFicoCreditScore);
}
