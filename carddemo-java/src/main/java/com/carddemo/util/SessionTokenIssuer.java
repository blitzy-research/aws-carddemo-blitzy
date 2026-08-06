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
package com.carddemo.util;

import com.carddemo.domain.enums.UserType;

/**
 * The capability of issuing a session to an operator whose credential has verified.
 *
 * <p><strong>Why this interface exists.</strong> The legacy system carried operator identity between
 * pseudo-conversational turns in the communication area, and the replacement for that carriage is a signed
 * token. The component that mints one is configuration - it owns the signing key, the lifetime and the
 * claim shape - while the HTTP boundary also needs to name only the issuing capability. The interface
 * therefore lives in the shared utility layer below both packages: configuration implements it, the API
 * consumes it, and neither package imports the service layer merely to share a one-method contract.
 *
 * <p>The interface is deliberately one method wide. Everything else the token component can do - verifying
 * a presented token, reading a claim out of one, reporting the configured lifetime - belongs to the filter
 * chain and to configuration, and nothing at the boundary has a use for it. Keeping the surface at exactly
 * the issuing operation means the sign-on route cannot reach the verification path by accident, and a test
 * of that route can supply a stub without standing up key material.
 *
 * @since 1.0.0
 */
public interface SessionTokenIssuer {

    /**
     * The scheme prefix a bearer credential is presented with, including its trailing space.
     *
     * <p>Declared here so that the route which issues the credential and any consumer which parses one
     * agree on the spelling without either restating it.
     */
    String BEARER_PREFIX = "Bearer ";

    /**
     * Issues a session for an operator whose credential has verified.
     *
     * <p>Called only after verification, so the caller has already established that the operator exists
     * and that both the stored type code and the authority resolved from it are the ones the credential
     * record yielded.
     *
     * <p><strong>Why the stored code and the resolved authority are two arguments and not one.</strong>
     * The legacy route split tests one condition - is the stored code the administrator letter - and its
     * alternative is unconditional, so <em>every</em> other stored value, including one the estate never
     * declared, resolves to the standard authority. The stored code and the resolved authority therefore
     * genuinely differ for such a record: the code is whatever the record holds and the authority is the
     * standard one. Collapsing them into a single argument forces an implementation to either re-derive
     * the code from the authority - which invents a value the record does not carry - or to compare the
     * two for equality, which refuses exactly the records the legacy admitted. Both are passed so that
     * neither is guessed.
     *
     * <p><strong>An implementation may nevertheless re-check the record, and the delivered one does.</strong>
     * The reason is not distrust of the caller: a session has to carry something that lets a later
     * request find out whether these facts are <em>still</em> true, that something can only be derived
     * from the record, and so the record is read again regardless. Once it has been read, an operator
     * who has been deleted, or whose stored type code is no longer the one being minted, is visible for
     * nothing - and issuing a session in either case would hand out a credential describing a record
     * that does not say what it says. An implementation that finds either condition refuses rather than
     * returning a session, which is why this method is declared to throw. What it must <em>not</em> do is
     * require the stored code to equal the resolved authority's own code, because that is false for every
     * record carrying an undeclared code and those records sign on successfully.
     *
     * @param userId the identifier the credential record is keyed by, never {@code null}
     * @param userType the authority resolved from that record, never {@code null}
     * @param userTypeCode the raw one-character type code exactly as the record holds it, never
     *                     {@code null}; the column is declared not-null, so an admitted operator always
     *                     has one
     * @return the encoded session, without the scheme prefix
     * @throws IllegalStateException if the credential record no longer exists, or no longer carries the
     *                               type code being issued, at the moment the session is minted
     */
    String issue(String userId, UserType userType, String userTypeCode);
}
