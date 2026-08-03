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
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence gateway for the {@code customer} table - the relational form of the 500-byte
 * {@code CUSTOMER-RECORD} of copybook {@code CVCUS01Y}, keyed on the 9-character customer
 * identifier the record carries at offset 0.
 *
 * <p>Nothing is declared here. The keyed read the account and statement paths need, the sequential
 * scan the customer extract needs and both write paths are inherited from {@link JpaRepository},
 * and {@code V2__create_indexes.sql} creates no secondary index over this table for a derived
 * finder to serve. Values are stored and returned exactly as supplied, so an identifier keeps the
 * leading zeros the record image published.
 *
 * <p>{@code cust_ssn} and {@code govt_issued_id} are protected at rest and are consequently wider
 * than their legacy fields. This interface performs no encryption, masking or redaction of its own
 * and logs nothing about these rows.
 *
 * @see Customer
 */
public interface CustomerRepository extends JpaRepository<Customer, String> {
}
