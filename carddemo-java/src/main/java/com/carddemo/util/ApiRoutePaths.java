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

/**
 * The neutral contract for every HTTP address that both the boundary and the security chain must name.
 *
 * <p><strong>Why this exists at all.</strong> Three addresses are needed in two places at once: the
 * request mapping that claims them, and the authorization rule that decides them. Declaring them on the
 * controller made the configuration package import the boundary package, which the specification's
 * layering rule forbids; declaring them twice would give one value two homes that can disagree, and a
 * disagreement here is silent - an authorization rule that matches nothing still looks like a rule. This
 * type is the third option, and it is the only one that satisfies both requirements: it sits in the base
 * layer that every package above may depend on and that depends on nothing itself, so the controller and
 * the chain read one authority and neither imports the other.
 *
 * <p><strong>What may be added here, and what may not.</strong> Only an address that is genuinely shared
 * between the boundary and the security chain. A route no rule names belongs on its own controller, where
 * it stays local to the class that serves it; adding it here would suggest something else depends on it.
 * No message text, no role name, no entitlement, no schema and no behaviour lives here: this type
 * carries addresses only, and it makes no authorization decision - which route requires what is decided
 * entirely by the security chain.
 *
 * <p>Provenance: the eighteen transaction definitions of {@code app/csd/CARDDEMO.CSD}, five of which are
 * administrative and one of which is anonymous, read as read-only reference at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @since 1.0.0
 */
public final class ApiRoutePaths {

    /**
     * Address of the sign-on route, the one route reachable without presenting a credential.
     *
     * <p>It is the route that issues credentials, so requiring one would make it unreachable. The
     * controller that serves it and the rule that exempts it read this one constant: a controller mapped
     * to some other path would be answered by the chain's closing catch-all and would fail closed, which
     * is the safe direction for that mistake to fail in.
     */
    public static final String SIGN_ON_PATH = "/api/auth/signon";

    /**
     * Path prefix beneath which every administrator-only route lives.
     *
     * <p>The legacy resource definitions bind eighteen transactions to programs, and five of those are
     * administrative: the administrative menu, and the four user-maintenance transactions that list, add,
     * update and delete a sign-on record. Rather than restate five route patterns, this module gives the
     * administrative surface one prefix and gates the prefix, so a route added beneath it is
     * administrator-only from the moment it exists. A route placed outside it is still authenticated by
     * the catch-all rule, so the failure mode of forgetting the prefix is a route that admits any
     * signed-on user rather than one that admits anybody.
     */
    public static final String ADMIN_PATH_PREFIX = "/api/admin";

    /**
     * Address of the administrative user-maintenance routes, which sit beneath
     * {@link #ADMIN_PATH_PREFIX} and are therefore gated by the administrative rule.
     */
    public static final String ADMIN_USERS_PATH = ADMIN_PATH_PREFIX + "/users";

    /**
     * Prefix beneath which the batch control surface lives.
     */
    public static final String BATCH_CONTROL_PATH_PREFIX = "/api/batch";

    /**
     * Base address of the batch job control surface.
     *
     * <p>Batch control translates no legacy transaction - the resource definitions register eighteen
     * transactions and not one of them starts a job - so this address is deliberately absent from the
     * route-to-role table derived from those definitions, and the operations entitlement the chain
     * requires for it is stated as its own rule. It shadows neither the management base path nor the
     * interface-description path, both of which the chain treats separately.
     */
    public static final String BATCH_JOBS_PATH = BATCH_CONTROL_PATH_PREFIX + "/jobs";

    /**
     * Pattern suffix matching a path and everything beneath it.
     *
     * <p>Named once so that a rule reads as a prefix plus its descendants rather than as a literal a
     * reader has to recognise.
     */
    public static final String ANY_DESCENDANT = "/**";

    /**
     * Not instantiable: this type is a constant contract and holds no state and no behaviour.
     */
    private ApiRoutePaths() {
        throw new AssertionError("ApiRoutePaths is a constant contract and is never instantiated");
    }
}
