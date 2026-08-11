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

import java.util.List;

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
 * Every ordinary business address is named here because the chain now names each of them individually
 * rather than covering them with one rule over the root - so each is shared, and each is declared once and
 * read by both the controller that serves it and the rule that admits it.
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
     * The root beneath which every business route of this module lives.
     *
     * <p><strong>Why the root is named and not merely implied.</strong> Every address below is spelled
     * from it, so the three prefixes cannot drift onto different roots, and - more importantly - the
     * security chain can state one closing rule over the whole business surface instead of relying on a
     * rule that asks only whether the caller has <em>some</em> identity. Before this constant existed
     * there was no way to write that rule without inventing the literal in the configuration package,
     * and a rule written from an invented literal is a rule that can silently name a surface no
     * controller serves.
     *
     * <p>Every delivered route is beneath it: the sign-on route, the administrative prefix, the batch
     * control prefix, and the eleven ordinary screen surfaces of {@link #ORDINARY_ROUTE_PATHS}. Nothing
     * outside it is served by this module except the container's own error path, which the chain treats
     * separately because it is reached by an internal dispatch rather than by a client. The root is what
     * lets the chain refuse everything beneath it that is not one of those named addresses, rather than
     * admitting an address no controller claims to any caller that merely holds a token.
     */
    public static final String API_PATH_PREFIX = "/api";

    /**
     * Address of the sign-on route, the one route reachable without presenting a credential.
     *
     * <p>It is the route that issues credentials, so requiring one would make it unreachable. The
     * controller that serves it and the rule that exempts it read this one constant: a controller mapped
     * to some other path beneath the root would be refused by the chain's closing refusal and would fail
     * closed, which is the safe direction for that mistake to fail in.
     */
    public static final String SIGN_ON_PATH = API_PATH_PREFIX + "/auth/signon";

    /** Sub-path of a list turn, shared by every screen family that publishes one. */
    public static final String LIST_SUBPATH = "/list";

    /** Sub-path of a view turn. */
    public static final String VIEW_SUBPATH = "/view";

    /** Sub-path of a detail turn. */
    public static final String DETAIL_SUBPATH = "/detail";

    /** Sub-path of an add turn. */
    public static final String ADD_SUBPATH = "/add";

    /** Sub-path of an update turn. */
    public static final String UPDATE_SUBPATH = "/update";

    /** Sub-path of a delete turn. */
    public static final String DELETE_SUBPATH = "/delete";

    /** Address of the main menu, legacy transaction {@code CM00}. */
    public static final String MENU_PATH = API_PATH_PREFIX + "/menu";

    /** Common prefix of the two account routes. */
    public static final String ACCOUNTS_PATH_PREFIX = API_PATH_PREFIX + "/accounts";

    /** Address of the account-view turn, legacy transaction {@code CAVW}. */
    public static final String ACCOUNT_VIEW_PATH = ACCOUNTS_PATH_PREFIX + VIEW_SUBPATH;

    /** Address of the account-update turn, legacy transaction {@code CAUP}. */
    public static final String ACCOUNT_UPDATE_PATH = ACCOUNTS_PATH_PREFIX + UPDATE_SUBPATH;

    /** Common prefix of the three card routes. */
    public static final String CARDS_PATH_PREFIX = API_PATH_PREFIX + "/cards";

    /** Address of the card-list turn, legacy transaction {@code CCLI}. */
    public static final String CARD_LIST_PATH = CARDS_PATH_PREFIX + LIST_SUBPATH;

    /** Address of the card-detail turn, legacy transaction {@code CCDL}. */
    public static final String CARD_DETAIL_PATH = CARDS_PATH_PREFIX + DETAIL_SUBPATH;

    /** Address of the card-update turn, legacy transaction {@code CCUP}. */
    public static final String CARD_UPDATE_PATH = CARDS_PATH_PREFIX + UPDATE_SUBPATH;

    /** Common prefix of the three transaction routes. */
    public static final String TRANSACTIONS_PATH_PREFIX = API_PATH_PREFIX + "/transactions";

    /** Address of the transaction-list turn, legacy transaction {@code CT00}. */
    public static final String TRANSACTION_LIST_PATH = TRANSACTIONS_PATH_PREFIX + LIST_SUBPATH;

    /** Address of the transaction-view turn, legacy transaction {@code CT01}. */
    public static final String TRANSACTION_VIEW_PATH = TRANSACTIONS_PATH_PREFIX + VIEW_SUBPATH;

    /** Address of the transaction-add turn, legacy transaction {@code CT02}. */
    public static final String TRANSACTION_ADD_PATH = TRANSACTIONS_PATH_PREFIX + ADD_SUBPATH;

    /** Address of the report-request turn, legacy transaction {@code CR00}. */
    public static final String REPORT_REQUEST_PATH = API_PATH_PREFIX + "/reports/request";

    /** Address of the bill-payment turn, legacy transaction {@code CB00}. */
    public static final String BILL_PAYMENT_PATH = API_PATH_PREFIX + "/bill-payment";

    /**
     * Every ordinary business address, and nothing else: the eleven routes a caller holding either
     * sign-on authority may reach.
     *
     * <p><strong>Why the addresses are enumerated rather than covered by the root.</strong> The security
     * chain used to admit either sign-on authority to {@link #API_PATH_PREFIX} and everything beneath it.
     * That rule is broader than the surface it protects in two ways that matter: it admits an address no
     * controller serves - so a caller holding an ordinary token was authorized for every path under the
     * root and told apart from a real one only by the dispatcher's own not-found answer - and it admits
     * whatever is mapped beneath the root next, before anybody has decided who should reach it. Naming
     * the addresses instead makes the grant exactly as wide as the delivered surface, and the chain
     * closes with a refusal over the root so that anything not named here is refused rather than
     * admitted.
     *
     * <p><strong>Why enumerating is not the fragile choice it looks like.</strong> The obvious objection
     * is that a route added later would be left out of this list and would then be unreachable. That is
     * the direction an authorization mistake must fail in - unreachable rather than open - and the
     * omission cannot survive a build: the delivered-surface oracle enumerates the router's own mappings,
     * requires them to equal an independently written literal table, and requires the ordinary entries of
     * that table to equal this list exactly. A route added beneath the root and not classified therefore
     * fails the build rather than either silently opening or silently disappearing.
     *
     * <p>Unmodifiable, and ordered as the addresses are declared above rather than by any rule a reader
     * would have to infer. It carries no entitlement: which authority may reach these addresses is
     * decided entirely by the security chain, as it is for every other address in this type.
     */
    public static final List<String> ORDINARY_ROUTE_PATHS = List.of(
            MENU_PATH,
            ACCOUNT_VIEW_PATH,
            ACCOUNT_UPDATE_PATH,
            CARD_LIST_PATH,
            CARD_DETAIL_PATH,
            CARD_UPDATE_PATH,
            TRANSACTION_LIST_PATH,
            TRANSACTION_VIEW_PATH,
            TRANSACTION_ADD_PATH,
            REPORT_REQUEST_PATH,
            BILL_PAYMENT_PATH);

    /**
     * Path prefix beneath which every administrator-only route lives.
     *
     * <p>The legacy resource definitions bind eighteen transactions to programs, and five of those are
     * administrative: the administrative menu, and the four user-maintenance transactions that list, add,
     * update and delete a sign-on record. Rather than restate five route patterns, this module gives the
     * administrative surface one prefix and gates the prefix, so a route added beneath it is
     * administrator-only from the moment it exists. A route placed outside it but still beneath the API
     * root is refused by the chain's closing refusal, because it would be neither an administrative
     * address nor one of the eleven named ordinary ones - so the failure mode of forgetting the prefix is
     * a route nobody reaches rather than one every signed-on caller reaches.
     */
    public static final String ADMIN_PATH_PREFIX = API_PATH_PREFIX + "/admin";

    /**
     * Address of the administrative user-maintenance routes, which sit beneath
     * {@link #ADMIN_PATH_PREFIX} and are therefore gated by the administrative rule.
     */
    public static final String ADMIN_USERS_PATH = ADMIN_PATH_PREFIX + "/users";

    /**
     * Prefix beneath which the batch control surface lives.
     */
    public static final String BATCH_CONTROL_PATH_PREFIX = API_PATH_PREFIX + "/batch";

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
