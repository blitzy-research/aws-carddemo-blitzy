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
package com.carddemo.config;

import com.carddemo.api.AccountController;
import com.carddemo.api.AdminUserController;
import com.carddemo.api.AuthController;
import com.carddemo.api.BatchJobController;
import com.carddemo.api.BillPaymentController;
import com.carddemo.api.CardController;
import com.carddemo.api.MenuController;
import com.carddemo.api.ReportController;
import com.carddemo.api.TransactionController;
import com.carddemo.service.NavigationService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Asserts that every route this module actually delivers sits on the side of the filter chain its legacy
 * transaction sat on.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>Two things had been true at once and were easy to mistake for one. The chain does declare an
 * entitlement rule - it requires the administrator authority beneath one path prefix - and
 * {@link SecurityConfigTest} drives that rule through a real chain and proves a standard principal is
 * refused. But the rule is stated in terms of a PATH, while {@link NavigationService} classifies
 * destinations by legacy transaction, and nothing connected the two. A destination that
 * {@code NavigationService} reports as administrative is protected only if the endpoint serving it happens
 * to be mapped beneath the gated prefix; map it anywhere else and it is answered by the chain's closing
 * {@code anyRequest().authenticated()} rule, which admits every signed-on operator. That failure needs no
 * change to the security configuration and produces no error - it is one path constant written in the
 * wrong shape - and the source comments describing the arrangement said in terms that closing it was an
 * obligation on code that did not yet exist.
 *
 * <p>The code exists now, and this class is that obligation discharged. It reads the delivered route
 * constants themselves, classifies each one, and requires the classification to agree with the regions
 * {@link SecurityConfig} publishes. It is deliberately a constant-level test rather than a second
 * chain-driving harness: the chain's behaviour per region is already proved by real requests in
 * {@code SecurityConfigTest}, and what was unproved is which region each delivered route lands in.
 *
 * <h2>Why it counts as well as classifies</h2>
 *
 * <p>A table of routes asserted against prefixes passes forever if a new route is simply never added to
 * the table. So the table's completeness is asserted against the sources: the number of request-mapped
 * operations in the API package must equal the number of entries here. A new endpoint therefore fails this
 * test until it has been classified, which is the point - the classification is a decision someone must
 * make, not a default someone can inherit.
 *
 * <p>Provenance: the route-to-entitlement split reproduces the transaction definitions in
 * {@code app/csd/CARDDEMO.CSD} at checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No definition text is copied.
 */
@DisplayName("Every delivered route sits in the security region its legacy transaction sat in")
class DeliveredRouteSecurityStateTest {

    /** What the filter chain does with a request for a given path. */
    private enum SecurityState {

        /** Reachable with no credential at all, because it is the route that issues them. */
        PUBLIC,

        /** Reachable by any signed-on operator, answered by the chain's closing rule. */
        AUTHENTICATED,

        /** Requires the administrator authority, answered by a region rule before the closing one. */
        ADMIN
    }

    /**
     * One delivered operation.
     *
     * @param path        the full path the controller maps
     * @param method      the HTTP method it maps
     * @param state       the region the chain must place it in
     * @param transaction the legacy transaction identifier it reproduces, or {@code null} where the
     *                    operation has no legacy counterpart
     */
    private record DeliveredRoute(String path, String method, SecurityState state, String transaction) {
    }

    /**
     * Every request-mapped operation this module delivers, with the region each must fall in.
     *
     * <p>Written out by hand from the controllers' own published constants - referenced, never retyped, so
     * a renamed constant is a compile error here rather than a silently stale expectation. The legacy
     * transaction identifier is carried alongside because it is what decides the region: the estate's
     * resource definitions bind five transactions to the administrative flow and the rest to the ordinary
     * one, and this table is where that decision meets a URL.
     *
     * <p>{@code BATCH_JOBS_PATH} carries no legacy transaction. The batch-control surface has no
     * counterpart in the estate at all - the legacy path from an operator to a batch job was a queue write
     * read by a scheduler, not a transaction - so its administrative region is a decision this migration
     * makes rather than one it reproduces, recorded as such in the decision log.
     */
    private static final List<DeliveredRoute> DELIVERED_ROUTES = List.of(
            new DeliveredRoute(AuthController.SIGN_ON_PATH, "POST", SecurityState.PUBLIC, "CC00"),
            new DeliveredRoute(MenuController.USER_MENU_PATH, "POST", SecurityState.AUTHENTICATED, "CM00"),
            new DeliveredRoute(MenuController.ADMIN_MENU_PATH, "POST", SecurityState.ADMIN, "CA00"),
            new DeliveredRoute(AccountController.ACCOUNT_VIEW_PATH, "POST",
                    SecurityState.AUTHENTICATED, "CAVW"),
            new DeliveredRoute(AccountController.ACCOUNT_UPDATE_PATH, "POST",
                    SecurityState.AUTHENTICATED, "CAUP"),
            new DeliveredRoute(CardController.CARDS_BASE_PATH + CardController.CARD_LIST_PATH, "POST",
                    SecurityState.AUTHENTICATED, "CCLI"),
            new DeliveredRoute(CardController.CARDS_BASE_PATH + CardController.CARD_DETAIL_PATH, "GET",
                    SecurityState.AUTHENTICATED, "CCDL"),
            new DeliveredRoute(CardController.CARDS_BASE_PATH + CardController.CARD_UPDATE_PATH, "POST",
                    SecurityState.AUTHENTICATED, "CCUP"),
            new DeliveredRoute(TransactionController.TRANSACTION_PATH + TransactionController.LIST_PATH,
                    "POST", SecurityState.AUTHENTICATED, "CT00"),
            new DeliveredRoute(TransactionController.TRANSACTION_PATH + TransactionController.VIEW_PATH,
                    "POST", SecurityState.AUTHENTICATED, "CT01"),
            new DeliveredRoute(TransactionController.TRANSACTION_PATH + TransactionController.ADD_PATH,
                    "POST", SecurityState.AUTHENTICATED, "CT02"),
            new DeliveredRoute(ReportController.REPORT_REQUEST_PATH, "POST",
                    SecurityState.AUTHENTICATED, "CR00"),
            new DeliveredRoute(BillPaymentController.BILL_PAYMENT_PATH, "POST",
                    SecurityState.AUTHENTICATED, "CB00"),
            new DeliveredRoute(AdminUserController.USERS_PATH + AdminUserController.LIST_SUBPATH, "POST",
                    SecurityState.ADMIN, "CU00"),
            new DeliveredRoute(AdminUserController.USERS_PATH + AdminUserController.ADD_SUBPATH, "POST",
                    SecurityState.ADMIN, "CU01"),
            new DeliveredRoute(AdminUserController.USERS_PATH + AdminUserController.UPDATE_SUBPATH, "POST",
                    SecurityState.ADMIN, "CU02"),
            new DeliveredRoute(AdminUserController.USERS_PATH + AdminUserController.DELETE_SUBPATH, "POST",
                    SecurityState.ADMIN, "CU03"),
            new DeliveredRoute(BatchJobController.BATCH_JOBS_PATH + BatchJobController.LAUNCH_SUBPATH,
                    "POST", SecurityState.ADMIN, null),
            new DeliveredRoute(BatchJobController.BATCH_JOBS_PATH + BatchJobController.EXECUTION_SUBPATH,
                    "GET", SecurityState.ADMIN, null));

    /** Where the delivered controllers live, for the completeness count. */
    private static final Path API_SOURCE_ROOT = Path.of("src", "main", "java", "com", "carddemo", "api");

    /** A method-level request mapping, which is what makes an operation reachable. */
    private static final Pattern OPERATION_MAPPING =
            Pattern.compile("^\\s+@(Get|Post|Put|Patch|Delete)Mapping", Pattern.MULTILINE);

    /**
     * Counts the request-mapped operations the delivered controllers declare.
     *
     * <p>Method-level mappings only. A class-level {@code @RequestMapping} contributes a base path and no
     * operation, so counting it would inflate the figure and let the table stay one entry short.
     *
     * @return how many operations are mapped
     */
    private static long mappedOperationCount() {
        try (Stream<Path> tree = Files.walk(API_SOURCE_ROOT)) {
            long total = 0;
            for (Path file : tree.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.getFileName().toString().endsWith("Controller.java"))
                    .toList()) {
                final Matcher mapping =
                        OPERATION_MAPPING.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (mapping.find()) {
                    total++;
                }
            }
            return total;
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("the API sources could not be read", unreadable);
        }
    }

    /**
     * Classifies a path by the regions the security configuration publishes.
     *
     * <p>This is the chain's own precedence expressed once: the anonymous exemption is declared before the
     * region rules, the region rules before the closing rule, and the closing rule admits any authenticated
     * principal. Deriving the expected state this way rather than restating it per route is what makes a
     * disagreement between the table and the configuration visible.
     *
     * @param path the full path
     * @return the region the chain places it in
     */
    private static SecurityState regionOf(final String path) {
        if (AuthController.SIGN_ON_PATH.equals(path)) {
            return SecurityState.PUBLIC;
        }
        if (path.startsWith(SecurityConfig.ADMIN_PATH_PREFIX)
                || path.startsWith(SecurityConfig.BATCH_CONTROL_PATH_PREFIX)) {
            return SecurityState.ADMIN;
        }
        return SecurityState.AUTHENTICATED;
    }

    @Nested
    @DisplayName("the inventory is complete, so a new endpoint cannot skip classification")
    class TheInventoryIsComplete {

        @Test
        @DisplayName("the table holds one entry per request-mapped operation in the API package")
        void theTableHoldsOneEntryPerMappedOperation() {
            assertThat((long) DELIVERED_ROUTES.size())
                    .as("an endpoint absent from this table would be unclassified, and an unclassified "
                            + "endpoint is one whose entitlement nobody decided")
                    .isEqualTo(mappedOperationCount());
        }

        @Test
        @DisplayName("and the count is the nineteen operations the module delivers, which is the figure "
                + "the published interface description also reports")
        void andTheCountIsNineteen() {
            assertThat(DELIVERED_ROUTES).hasSize(19);
            assertThat(mappedOperationCount())
                    .as("read from the sources, so this is a measurement rather than a restatement")
                    .isEqualTo(19L);
        }

        @Test
        @DisplayName("and no path appears twice, because two operations on one path would make the "
                + "classification ambiguous")
        void andNoPathAppearsTwice() {
            assertThat(DELIVERED_ROUTES.stream().map(route -> route.method() + " " + route.path()).toList())
                    .doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("every route's region agrees with the configuration that gates it")
    class EveryRegionAgrees {

        @Test
        @DisplayName("so no delivered route is on a side of the chain its table entry does not name")
        void soNoRouteIsOnTheWrongSide() {
            final List<String> disagreements = new ArrayList<>();
            for (final DeliveredRoute route : DELIVERED_ROUTES) {
                final SecurityState actual = regionOf(route.path());
                if (actual != route.state()) {
                    disagreements.add(route.method() + " " + route.path() + " is classified "
                            + route.state() + " but the configuration places it in " + actual);
                }
            }

            assertThat(disagreements)
                    .as("map an administrative operation beneath %s, or beneath %s for batch control; "
                            + "anywhere else it is answered by the closing authenticated rule and every "
                            + "signed-on operator reaches it",
                            SecurityConfig.ADMIN_PATH_PREFIX, SecurityConfig.BATCH_CONTROL_PATH_PREFIX)
                    .isEmpty();
        }

        @Test
        @DisplayName("and exactly one route is reachable without a credential, because it is the one that "
                + "issues them")
        void andExactlyOneRouteIsPublic() {
            assertThat(DELIVERED_ROUTES.stream()
                    .filter(route -> route.state() == SecurityState.PUBLIC)
                    .map(DeliveredRoute::path)
                    .toList())
                    .containsExactly(AuthController.SIGN_ON_PATH);
        }

        @Test
        @DisplayName("and no ordinary route is nested beneath a gated prefix, which would refuse a caller "
                + "the estate admits")
        void andNoOrdinaryRouteIsNestedBeneathAGatedPrefix() {
            assertThat(DELIVERED_ROUTES.stream()
                    .filter(route -> route.state() != SecurityState.ADMIN)
                    .map(DeliveredRoute::path)
                    .toList())
                    .allSatisfy(path -> assertAll(
                            () -> assertThat(path).doesNotStartWith(SecurityConfig.ADMIN_PATH_PREFIX),
                            () -> assertThat(path)
                                    .doesNotStartWith(SecurityConfig.BATCH_CONTROL_PATH_PREFIX)));
        }
    }

    @Nested
    @DisplayName("the five administrative destinations are gated by the routes that serve them")
    class TheAdministrativeDestinationsAreGated {

        @Test
        @DisplayName("so the navigation service's administrative marking is now an access control and no "
                + "longer only a description of the legacy transaction")
        void soTheMarkingIsNowAnAccessControl() {
            final List<String> markedAdministrative = new NavigationService().adminScopedRoutes().stream()
                    .map(NavigationService.Route::getLegacyTransactionId)
                    .sorted()
                    .toList();
            final List<String> gatedTransactions = DELIVERED_ROUTES.stream()
                    .filter(route -> route.state() == SecurityState.ADMIN)
                    .map(DeliveredRoute::transaction)
                    .filter(transaction -> transaction != null)
                    .sorted()
                    .toList();

            assertThat(markedAdministrative)
                    .as("the administrative menu and the four sign-on-record maintenance transactions")
                    .containsExactly("CA00", "CU00", "CU01", "CU02", "CU03");
            assertThat(gatedTransactions)
                    .as("every destination the navigation service marks administrative must be served by "
                            + "a route the chain gates, or the marking reports a protection that is not "
                            + "there")
                    .isEqualTo(markedAdministrative);
        }

        @Test
        @DisplayName("and every destination it does not mark administrative is served by a route the "
                + "chain does not gate")
        void andEveryOrdinaryDestinationIsUngated() {
            final List<String> gated = DELIVERED_ROUTES.stream()
                    .filter(route -> route.state() == SecurityState.ADMIN)
                    .map(DeliveredRoute::transaction)
                    .toList();

            assertThat(new NavigationService().routes().stream()
                    .filter(route -> !route.isAdminScoped())
                    .map(NavigationService.Route::getLegacyTransactionId)
                    .toList())
                    .as("gating a transaction the estate admits to any operator would refuse a caller "
                            + "the legacy system served")
                    .allSatisfy(transaction -> assertThat(gated).doesNotContain(transaction));
        }

        @Test
        @DisplayName("and the batch-control surface is gated although it reproduces no transaction, which "
                + "is a decision this migration makes rather than one it inherits")
        void andTheBatchControlSurfaceIsGated() {
            assertThat(DELIVERED_ROUTES.stream()
                    .filter(route -> route.transaction() == null)
                    .toList())
                    .as("the two batch-control operations, and only those, have no legacy counterpart")
                    .hasSize(2)
                    .allSatisfy(route -> assertAll(
                            () -> assertThat(route.state()).isEqualTo(SecurityState.ADMIN),
                            () -> assertThat(route.path())
                                    .startsWith(SecurityConfig.BATCH_CONTROL_PATH_PREFIX)));
        }
    }
}
