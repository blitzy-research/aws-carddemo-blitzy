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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.service.InMemorySignOnAttemptLedger;
import com.carddemo.service.PostgresSignOnAttemptLedger;
import com.carddemo.service.SignOnAttemptLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Specifies which sign-on attempt store a deployment gets, and the two choices that are refused outright.
 *
 * <h2>What is under specification</h2>
 *
 * <p>{@link SignOnThrottleConfig} answers one question - where is the sign-on attempt allowance counted -
 * and the answer is a security property rather than a preference. Counted in a process's own memory the
 * allowance is counted once <em>per process</em>, so N replicas grant N times the attempts and every
 * restart returns every allowance to full. This file asserts that the deployment-wide store is what
 * production gets, that asking for the per-process one under production is refused, and that a
 * misspelling is refused rather than quietly resolved to the weaker option.
 *
 * <h2>Why the bean method is called directly</h2>
 *
 * <p>The two refusals happen at bean creation, and calling the method is the most direct way to observe
 * them: it needs no context refresh, no data source and no property source ordering, so a failing case
 * fails for the reason under specification and not because a context could not be assembled. The
 * collaborators arrive as {@link ObjectProvider} exactly as the framework supplies them, and the doubles
 * below report presence and absence without constructing a connection - which is what lets the absent-
 * database refusal be specified at all.
 *
 * <p>Recorded in {@code docs/decision-log.md} entry DL-343.
 *
 * @since 1.0.0
 */
@DisplayName("choosing where the sign-on attempt allowance is counted")
class SignOnThrottleConfigTest {

    /** The configuration under specification; it holds no state, so one instance serves every case. */
    private final SignOnThrottleConfig config = new SignOnThrottleConfig();

    /** Creates the specification. */
    SignOnThrottleConfigTest() {
        // Intentionally empty: each case supplies its own scope, environment and collaborators.
    }

    /**
     * Resolves the ledger for a scope under a non-production environment with a database available.
     *
     * @param  scope the configured scope, passed through unchanged
     * @return the ledger the configuration publishes
     */
    private SignOnAttemptLedger ledgerFor(final String scope) {
        return this.config.signOnAttemptLedger(scope, new MockEnvironment(), availableDatabase(),
                availableTransactions());
    }

    /**
     * Resolves the ledger for a scope under the production profile with a database available.
     *
     * @param  scope the configured scope, passed through unchanged
     * @return the ledger the configuration publishes
     */
    private SignOnAttemptLedger productionLedgerFor(final String scope) {
        final MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles(SignOnThrottleConfig.PRODUCTION_PROFILE);
        return this.config.signOnAttemptLedger(scope, production, availableDatabase(),
                availableTransactions());
    }

    /**
     * A provider reporting that a shared database is available.
     *
     * @return a provider holding a template over no data source, which is never used by these cases
     */
    private static ObjectProvider<JdbcTemplate> availableDatabase() {
        return new FixedProvider<>(new JdbcTemplate());
    }

    /**
     * A provider reporting that a transaction manager is available.
     *
     * @return a provider holding a manager over no data source, which is never used by these cases
     */
    private static ObjectProvider<PlatformTransactionManager> availableTransactions() {
        return new FixedProvider<>(new DataSourceTransactionManager());
    }

    @Nested
    @DisplayName("the per-process store, which is the default")
    class ThePerProcessStore {

        @Test
        @DisplayName("is what an unset scope resolves to, so a fresh clone runs with the protection on and "
                + "nothing to provision")
        void isWhatAnUnsetScopeResolvesTo() {
            final SignOnAttemptLedger ledger = ledgerFor(SignOnThrottleConfig.INSTANCE_SCOPE);

            assertThat(ledger).isInstanceOf(InMemorySignOnAttemptLedger.class);
            assertThat(ledger.isDeploymentWide())
                    .as("the default needs no schema, no round trip and no clean-up, which is what keeps "
                            + "the throttle enabled on a developer machine rather than switched off there")
                    .isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"instance", "INSTANCE", "Instance", "  instance  ", "\tinstance\n"})
        @DisplayName("is selected however the value is cased or padded, so a deployment document's "
                + "whitespace is not a configuration change")
        void isSelectedHoweverTheValueIsCasedOrPadded(final String configured) {
            assertThat(ledgerFor(configured)).isInstanceOf(InMemorySignOnAttemptLedger.class);
        }
    }

    @Nested
    @DisplayName("the deployment-wide store")
    class TheDeploymentWideStore {

        @ParameterizedTest
        @ValueSource(strings = {"deployment", "DEPLOYMENT", "  Deployment  "})
        @DisplayName("is selected however the value is cased or padded, and declares itself shared")
        void isSelectedHoweverTheValueIsCasedOrPadded(final String configured) {
            final SignOnAttemptLedger ledger = ledgerFor(configured);

            assertThat(ledger).isInstanceOf(PostgresSignOnAttemptLedger.class);
            assertThat(ledger.isDeploymentWide())
                    .as("one allowance is one allowance across every instance, and it survives all of "
                            + "them restarting")
                    .isTrue();
        }

        @Test
        @DisplayName("is refused rather than substituted when the context publishes no database, because "
                + "the substitute would be the per-process allowance this scope exists to close")
        void isRefusedRatherThanSubstitutedWhenThereIsNoDatabase() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> config.signOnAttemptLedger(
                            SignOnThrottleConfig.DEPLOYMENT_SCOPE, new MockEnvironment(),
                            new FixedProvider<>(null), availableTransactions()))
                    .withMessageContaining(SignOnThrottleConfig.STATE_SCOPE_PROPERTY)
                    .withMessageContaining("no JdbcTemplate")
                    .withMessageContaining("refused");
        }

        @Test
        @DisplayName("is refused when the context publishes no transaction manager, because a transition "
                + "that is not atomic is not a throttle")
        void isRefusedWhenThereIsNoTransactionManager() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> config.signOnAttemptLedger(
                            SignOnThrottleConfig.DEPLOYMENT_SCOPE, new MockEnvironment(),
                            availableDatabase(), new FixedProvider<>(null)))
                    .withMessageContaining("no PlatformTransactionManager");
        }

        @Test
        @DisplayName("is what production gets, and production start-up completes with it")
        void isWhatProductionGets() {
            final SignOnAttemptLedger ledger =
                    productionLedgerFor(SignOnThrottleConfig.DEPLOYMENT_SCOPE);

            assertThat(ledger).isInstanceOf(PostgresSignOnAttemptLedger.class);
            assertThat(ledger.isDeploymentWide()).isTrue();
        }
    }

    @Nested
    @DisplayName("what production refuses")
    class WhatProductionRefuses {

        @Test
        @DisplayName("refuses a per-process allowance even with a database available, so the refusal is "
                + "about the scope and not about what happens to be wired")
        void refusesAPerProcessAllowanceEvenWithADatabaseAvailable() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> productionLedgerFor(SignOnThrottleConfig.INSTANCE_SCOPE))
                    .withMessageContaining(SignOnThrottleConfig.PRODUCTION_PROFILE)
                    .withMessageContaining(SignOnThrottleConfig.STATE_SCOPE_PROPERTY)
                    .withMessageContaining(SignOnThrottleConfig.DEPLOYMENT_SCOPE);
        }

        @Test
        @DisplayName("names the setting to apply rather than only the fault, so the refusal is actionable "
                + "from the log line alone")
        void namesTheSettingToApply() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> productionLedgerFor(SignOnThrottleConfig.INSTANCE_SCOPE))
                    .withMessageContaining(SignOnThrottleConfig.STATE_SCOPE_PROPERTY + "="
                            + SignOnThrottleConfig.DEPLOYMENT_SCOPE)
                    .withMessageContaining("DL-343");
        }

        @Test
        @DisplayName("refuses a per-process allowance when production is one of several active profiles, "
                + "so the check is not defeated by activating another alongside it")
        void refusesWhenProductionIsOneOfSeveralProfiles() {
            final MockEnvironment production = new MockEnvironment();
            production.setActiveProfiles("observability", SignOnThrottleConfig.PRODUCTION_PROFILE,
                    "eu-west");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> config.signOnAttemptLedger(SignOnThrottleConfig.INSTANCE_SCOPE,
                            production, availableDatabase(), availableTransactions()))
                    .withMessageContaining(SignOnThrottleConfig.PRODUCTION_PROFILE);
        }
    }

    @Nested
    @DisplayName("a value nobody publishes")
    class AValueNobodyPublishes {

        @ParameterizedTest
        @ValueSource(strings = {"deploment", "shared", "postgres", "redis", "true", "none"})
        @DisplayName("is refused rather than resolved to the default, because falling back would turn a "
                + "typo into a per-process allowance on the deployment that asked for the shared one")
        void isRefusedRatherThanResolvedToTheDefault(final String misspelt) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ledgerFor(misspelt))
                    .withMessageContaining(SignOnThrottleConfig.STATE_SCOPE_PROPERTY)
                    .withMessageContaining(SignOnThrottleConfig.INSTANCE_SCOPE)
                    .withMessageContaining(SignOnThrottleConfig.DEPLOYMENT_SCOPE);
        }

        @Test
        @DisplayName("includes an empty value, which is a mistake rather than a request for the default")
        void includesAnEmptyValue() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ledgerFor("   "))
                    .withMessageContaining(SignOnThrottleConfig.STATE_SCOPE_PROPERTY);
        }

        @Test
        @DisplayName("includes an absent value, so a property source that resolves to nothing is refused "
                + "rather than silently weakening the throttle")
        void includesAnAbsentValue() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ledgerFor(null))
                    .withMessageContaining(SignOnThrottleConfig.STATE_SCOPE_PROPERTY);
        }

        @Test
        @DisplayName("is refused under production too, and never resolves to the store production "
                + "forbids")
        void isRefusedUnderProductionToo() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> productionLedgerFor("deploment"));
        }
    }

    @Nested
    @DisplayName("the published contract")
    class ThePublishedContract {

        @Test
        @DisplayName("names the property and both scopes as constants, so configuration, refusal text and "
                + "these specifications read one roster")
        void namesThePropertyAndBothScopes() {
            assertThat(SignOnThrottleConfig.STATE_SCOPE_PROPERTY)
                    .isEqualTo("carddemo.security.sign-on.state-scope");
            assertThat(SignOnThrottleConfig.INSTANCE_SCOPE).isEqualTo("instance");
            assertThat(SignOnThrottleConfig.DEPLOYMENT_SCOPE).isEqualTo("deployment");
            assertThat(SignOnThrottleConfig.PRODUCTION_PROFILE).isEqualTo("prod");
        }

        @Test
        @DisplayName("refuses an absent environment rather than assuming the deployment is not production")
        void refusesAnAbsentEnvironment() {
            assertThatExceptionOfType(NullPointerException.class)
                    .as("assuming non-production would make the production refusal depend on a "
                            + "collaborator being wired, which is the kind of silent skip this check "
                            + "exists to prevent")
                    .isThrownBy(() -> config.signOnAttemptLedger(SignOnThrottleConfig.INSTANCE_SCOPE,
                            null, availableDatabase(), availableTransactions()));
        }
    }

    /**
     * Reports a fixed collaborator, or none.
     *
     * <p>Private and static so it cannot capture an enclosing instance, which this module's compiler
     * settings would report.
     *
     * @param <T> the collaborator type
     */
    private static final class FixedProvider<T> implements ObjectProvider<T> {

        /** The value to report, or {@code null} to report that none is available. */
        private final T value;

        /**
         * Creates a provider reporting the supplied value.
         *
         * @param value the value to report, or {@code null} for none
         */
        FixedProvider(final T value) {
            this.value = value;
        }

        @Override
        public T getIfAvailable() {
            return this.value;
        }
    }
}
