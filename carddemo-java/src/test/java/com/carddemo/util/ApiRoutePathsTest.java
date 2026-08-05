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

import com.carddemo.api.AdminUserController;
import com.carddemo.api.AuthController;
import com.carddemo.api.BatchJobController;
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
