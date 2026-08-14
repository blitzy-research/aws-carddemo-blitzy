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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.api.AccountController;
import com.carddemo.api.AdminUserController;
import com.carddemo.api.AuthController;
import com.carddemo.api.BatchJobController;
import com.carddemo.api.BillPaymentController;
import com.carddemo.api.CardController;
import com.carddemo.api.MenuController;
import com.carddemo.api.ReportController;
import com.carddemo.api.TransactionController;
import com.carddemo.config.SecurityConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the neutral route contract, which exists so that a route the security chain gates and a route
 * a controller claims are the same string by construction rather than by coincidence.
 *
 * <p>The assertions that matter here are the agreement assertions: this test is the one place that names
 * both the boundary constant and the configuration constant, and it fails if either stops reading the
 * contract. A test is the right place for that, because a test may depend on every layer while the
 * production packages may not depend on each other.
 */
@DisplayName("the neutral route contract both the boundary and the security chain read")
class ApiRoutePathsTest {

    @Nested
    @DisplayName("the addresses are the ones the estate's surfaces claim")
    class TheAddresses {

        @Test
        @DisplayName("the API root is the region the security chain closes with a refusal, and every other "
                + "address here is spelled from it")
        void theApiRootIsTheRegionEveryAddressIsSpelledFrom() {
            assertThat(ApiRoutePaths.API_PATH_PREFIX).isEqualTo("/api");
            // Containment is the property that matters, and it is asserted rather than trusted to the
            // spelling: an address that fell outside the root would be answered by the chain's closing rule
            // instead of by a rule of its own, and would therefore be reachable by an identity that is not a
            // sign-on record at all.
            assertThat(ApiRoutePaths.SIGN_ON_PATH)
                    .startsWith(ApiRoutePaths.API_PATH_PREFIX + "/");
            assertThat(ApiRoutePaths.ADMIN_PATH_PREFIX)
                    .startsWith(ApiRoutePaths.API_PATH_PREFIX + "/");
            assertThat(ApiRoutePaths.BATCH_CONTROL_PATH_PREFIX)
                    .startsWith(ApiRoutePaths.API_PATH_PREFIX + "/");
            assertThat(ApiRoutePaths.ADMIN_USERS_PATH)
                    .startsWith(ApiRoutePaths.API_PATH_PREFIX + "/");
            assertThat(ApiRoutePaths.BATCH_JOBS_PATH)
                    .startsWith(ApiRoutePaths.API_PATH_PREFIX + "/");
        }

        @Test
        @DisplayName("the sign-on address is the anonymous route, spelled once")
        void theSignOnAddressIsSpelledOnce() {
            assertThat(ApiRoutePaths.SIGN_ON_PATH).isEqualTo("/api/auth/signon");
        }

        @Test
        @DisplayName("the administrative prefix is the one gate the five administrative transactions share")
        void theAdministrativePrefixIsOneGate() {
            assertThat(ApiRoutePaths.ADMIN_PATH_PREFIX).isEqualTo("/api/admin");
        }

        @Test
        @DisplayName("the administrative user routes sit beneath the administrative prefix, so the one "
                + "gate covers them")
        void theAdministrativeUserRoutesSitBeneathThePrefix() {
            assertThat(ApiRoutePaths.ADMIN_USERS_PATH)
                    .isEqualTo("/api/admin/users")
                    .startsWith(ApiRoutePaths.ADMIN_PATH_PREFIX + "/");
        }

        @Test
        @DisplayName("the batch control address is outside the administrative prefix, because batch "
                + "control translates no legacy transaction and carries its own rule")
        void theBatchControlAddressIsOutsideTheAdministrativePrefix() {
            assertThat(ApiRoutePaths.BATCH_JOBS_PATH).isEqualTo("/api/batch/jobs");
            assertThat(ApiRoutePaths.BATCH_JOBS_PATH)
                    .doesNotStartWith(ApiRoutePaths.ADMIN_PATH_PREFIX);
        }

        @Test
        @DisplayName("the descendant suffix is the pattern a prefix rule appends")
        void theDescendantSuffixIsThePatternAPrefixRuleAppends() {
            assertThat(ApiRoutePaths.ANY_DESCENDANT).isEqualTo("/**");
        }

        @Test
        @DisplayName("the ordinary roster names the eleven delivered addresses as exact addresses, so the "
                + "grant built from it is exactly as wide as the surface it protects")
        void theOrdinaryRosterNamesElevenExactAddresses() {
            assertThat(ApiRoutePaths.ORDINARY_ROUTE_PATHS)
                    .as("eleven ordinary screen surfaces: one menu, two account, three card, three "
                            + "transaction, one report request and one bill payment")
                    .containsExactly(
                            "/api/menu",
                            "/api/accounts/view",
                            "/api/accounts/update",
                            "/api/cards/list",
                            "/api/cards/detail",
                            "/api/cards/update",
                            "/api/transactions/list",
                            "/api/transactions/view",
                            "/api/transactions/add",
                            "/api/reports/request",
                            "/api/bill-payment");
            assertThat(ApiRoutePaths.ORDINARY_ROUTE_PATHS)
                    .as("a repeated address would install one rule twice and make the census wrong")
                    .doesNotHaveDuplicates()
                    .allSatisfy(path -> assertThat(path)
                            .as("an exact address, because a pattern would grant whatever is mapped "
                                    + "beneath it next")
                            .doesNotContain("*")
                            .startsWith(ApiRoutePaths.API_PATH_PREFIX + "/"));
        }

        @Test
        @DisplayName("and the roster holds no address that is gated differently, so the three surfaces "
                + "stay separately decided")
        void theOrdinaryRosterHoldsNothingGatedDifferently() {
            assertThat(ApiRoutePaths.ORDINARY_ROUTE_PATHS)
                    .as("the anonymous route issues credentials and is permitted by its own rule")
                    .doesNotContain(ApiRoutePaths.SIGN_ON_PATH)
                    .allSatisfy(path -> assertThat(path)
                            .as("the administrative and batch-control surfaces require the "
                                    + "administrative authority alone")
                            .doesNotStartWith(ApiRoutePaths.ADMIN_PATH_PREFIX)
                            .doesNotStartWith(ApiRoutePaths.BATCH_CONTROL_PATH_PREFIX));
        }

        @Test
        @DisplayName("the roster is unmodifiable, because a rule set a caller could add to is not a rule "
                + "set")
        void theRosterIsUnmodifiable() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> ApiRoutePaths.ORDINARY_ROUTE_PATHS.add("/api/anything"));
        }
    }

    @Nested
    @DisplayName("both sides read the contract rather than a literal of their own")
    class BothSidesReadTheContract {

        @Test
        @DisplayName("the sign-on controller and the security chain name the same address")
        void theSignOnControllerAndTheChainAgree() {
            assertThat(AuthController.SIGN_ON_PATH).isEqualTo(ApiRoutePaths.SIGN_ON_PATH);
            assertThat(SecurityConfig.SIGN_ON_PATH).isEqualTo(ApiRoutePaths.SIGN_ON_PATH);
        }

        @Test
        @DisplayName("the administrative controller sits beneath the prefix the chain gates")
        void theAdministrativeControllerSitsBeneathTheGatedPrefix() {
            assertThat(AdminUserController.USERS_PATH).isEqualTo(ApiRoutePaths.ADMIN_USERS_PATH);
            assertThat(SecurityConfig.ADMIN_PATH_PREFIX).isEqualTo(ApiRoutePaths.ADMIN_PATH_PREFIX);
            assertThat(AdminUserController.USERS_PATH).startsWith(SecurityConfig.ADMIN_PATH_PREFIX);
        }

        @Test
        @DisplayName("the batch control surface names the contract address")
        void theBatchControlSurfaceNamesTheContractAddress() {
            assertThat(BatchJobController.BATCH_JOBS_PATH).isEqualTo(ApiRoutePaths.BATCH_JOBS_PATH);
        }

        @Test
        @DisplayName("every ordinary controller composes its mapping from the contract, so each address "
                + "has one home and the rule that grants it cannot name a different string")
        void everyOrdinaryControllerComposesItsMappingFromTheContract() {
            assertThat(MenuController.USER_MENU_PATH).isEqualTo(ApiRoutePaths.MENU_PATH);
            assertThat(AccountController.ACCOUNTS_PATH)
                    .isEqualTo(ApiRoutePaths.ACCOUNTS_PATH_PREFIX);
            assertThat(AccountController.ACCOUNT_VIEW_PATH)
                    .isEqualTo(ApiRoutePaths.ACCOUNT_VIEW_PATH);
            assertThat(AccountController.ACCOUNT_UPDATE_PATH)
                    .isEqualTo(ApiRoutePaths.ACCOUNT_UPDATE_PATH);
            assertThat(CardController.CARDS_BASE_PATH + CardController.CARD_LIST_PATH)
                    .isEqualTo(ApiRoutePaths.CARD_LIST_PATH);
            assertThat(CardController.CARDS_BASE_PATH + CardController.CARD_DETAIL_PATH)
                    .isEqualTo(ApiRoutePaths.CARD_DETAIL_PATH);
            assertThat(CardController.CARDS_BASE_PATH + CardController.CARD_UPDATE_PATH)
                    .isEqualTo(ApiRoutePaths.CARD_UPDATE_PATH);
            assertThat(TransactionController.TRANSACTION_PATH + TransactionController.LIST_PATH)
                    .isEqualTo(ApiRoutePaths.TRANSACTION_LIST_PATH);
            assertThat(TransactionController.TRANSACTION_PATH + TransactionController.VIEW_PATH)
                    .isEqualTo(ApiRoutePaths.TRANSACTION_VIEW_PATH);
            assertThat(TransactionController.TRANSACTION_PATH + TransactionController.ADD_PATH)
                    .isEqualTo(ApiRoutePaths.TRANSACTION_ADD_PATH);
            assertThat(ReportController.REPORT_REQUEST_PATH)
                    .isEqualTo(ApiRoutePaths.REPORT_REQUEST_PATH);
            assertThat(BillPaymentController.BILL_PAYMENT_PATH)
                    .isEqualTo(ApiRoutePaths.BILL_PAYMENT_PATH);
        }

        @Test
        @DisplayName("and the roster the chain installs its ordinary rules from is the entitlement's own "
                + "patterns, so neither can be changed alone")
        void theRosterIsTheEntitlementsOwnPatterns() {
            assertThat(SecurityConfig.Gating.AUTHENTICATED.enforcementPatterns())
                    .isEqualTo(ApiRoutePaths.ORDINARY_ROUTE_PATHS);
            assertThat(SecurityConfig.TransactionRoute.enforcementPatternsFor(
                    SecurityConfig.Gating.AUTHENTICATED))
                    .isEqualTo(ApiRoutePaths.ORDINARY_ROUTE_PATHS);
        }
    }

    @Nested
    @DisplayName("the type is a constant contract and never an instance")
    class TheTypeIsAConstantContract {

        @Test
        @DisplayName("the class is final and its sole constructor is private and refuses")
        void theClassIsFinalAndItsConstructorRefuses() throws NoSuchMethodException {
            assertThat(Modifier.isFinal(ApiRoutePaths.class.getModifiers())).isTrue();

            final Constructor<?>[] constructors = ApiRoutePaths.class.getDeclaredConstructors();
            assertThat(constructors).hasSize(1);
            assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();

            final Constructor<ApiRoutePaths> constructor = ApiRoutePaths.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }
    }
}
