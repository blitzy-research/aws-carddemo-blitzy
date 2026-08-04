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

import com.carddemo.domain.enums.UserType;

/**
 * The capability of issuing a session to an operator whose credential has verified.
 *
 * <p><strong>Why this interface exists.</strong> The legacy system carried operator identity between
 * pseudo-conversational turns in the communication area, and the replacement for that carriage is a signed
 * token. The component that mints one is configuration - it owns the signing key, the lifetime and the
 * claim shape - and configuration sits above the boundary in this module's layering, so the boundary
 * cannot name it. Rather than invert the layering or move key material downwards, the boundary declares
 * the narrow capability it actually needs and configuration supplies it.
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
     * <p>Called only after verification. An implementation is entitled to assume the operator exists and
     * the role is the one resolved from the credential record, and is not required to re-check either.
     *
     * @param userId the identifier the credential record is keyed by, never {@code null}
     * @param userType the role resolved from that record, never {@code null}
     * @return the encoded session, without the scheme prefix
     */
    String issue(String userId, UserType userType);
}
