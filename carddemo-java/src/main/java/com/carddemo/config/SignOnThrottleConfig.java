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

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.carddemo.service.InMemorySignOnAttemptLedger;
import com.carddemo.service.PostgresSignOnAttemptLedger;
import com.carddemo.service.SignOnAttemptLedger;

/**
 * Chooses where the sign-on attempt allowance is counted, and refuses a production choice that would not
 * count it once.
 *
 * <h2>Why this is a choice at all</h2>
 *
 * <p>The allowance itself is not in question: it is delivered, enabled by default, and recorded in
 * {@code docs/decision-log.md} entry DL-268. What was wrong is where the count lived. Held in a process's
 * own memory the allowance is counted once <em>per process</em>, so a deployment running two replicas grants
 * every caller twice the attempts before a refusal, ten replicas grant ten times, and every restart - a
 * release, a crash, an autoscaler reclaiming an instance - returns every allowance to full without anyone
 * authenticating. A caller able to provoke a restart, or simply patient enough to wait for one, resets the
 * bound at will. None of that is visible from inside a replica, which is what let it stand.
 *
 * <h2>The two scopes, and which deployment each is right for</h2>
 *
 * <ul>
 *   <li>{@value #INSTANCE_SCOPE} - {@link InMemorySignOnAttemptLedger}. Correct, and the default, for a
 *       deployment that runs exactly one instance: a developer machine, the local Compose stack, a test. It
 *       needs no schema, no round trip and no clean-up, so a fresh clone runs with the protection on and
 *       nothing to provision.</li>
 *   <li>{@value #DEPLOYMENT_SCOPE} - {@link PostgresSignOnAttemptLedger}. Required for every deployment
 *       that runs more than one instance, and required unconditionally under the production profile. The
 *       state goes in the database every instance already shares, so one allowance is one allowance however
 *       many instances there are, and it survives all of them restarting.</li>
 * </ul>
 *
 * <h2>Two refusals rather than a default that quietly does the wrong thing</h2>
 *
 * <p>An unrecognised scope is refused rather than treated as the default. A misspelling is the likeliest way
 * this setting goes wrong, and the shape of the mistake matters: silently falling back to
 * {@value #INSTANCE_SCOPE} would turn a typo in a deployment document into a per-process allowance nobody
 * looks for again, and it would do so on exactly the deployment that had tried to ask for the shared one.
 *
 * <p>Production with {@value #INSTANCE_SCOPE} is refused too, and it is refused here - at bean creation,
 * before the context finishes refreshing - rather than being checked somewhere a property source could be
 * ordered around. The refusal names the property and the value it must carry and nothing else; it cannot
 * echo a credential because this setting has none.
 *
 * <p>Recorded in {@code docs/decision-log.md} entry DL-343.
 *
 * @since 1.0.0
 */
@Configuration
public class SignOnThrottleConfig {

    /** Property naming where the sign-on attempt state lives. */
    public static final String STATE_SCOPE_PROPERTY = "carddemo.security.sign-on.state-scope";

    /** Scope of a store held in one process's memory, which is the default. */
    public static final String INSTANCE_SCOPE = "instance";

    /** Scope of a store shared by every instance of the deployment. */
    public static final String DEPLOYMENT_SCOPE = "deployment";

    /** The profile under which {@value #INSTANCE_SCOPE} is refused. */
    public static final String PRODUCTION_PROFILE = "prod";

    /** Reports which scope was chosen, so an operator can read it back from a start-up log. */
    private static final Logger LOGGER = LoggerFactory.getLogger(SignOnThrottleConfig.class);

    /** Creates the configuration. */
    public SignOnThrottleConfig() {
        // Intentionally empty: the class holds no state and exists to publish one bean.
    }

    /**
     * Publishes the ledger the sign-on governor counts into.
     *
     * <p>The database collaborators are taken as providers rather than as beans, because
     * {@value #INSTANCE_SCOPE} must work in a context that publishes neither - a service slice test, or any
     * context assembled without a data source. Resolving them lazily means the default scope never asks for
     * a data source it does not need, while {@value #DEPLOYMENT_SCOPE} fails loudly and immediately if one
     * is absent rather than degrading to a per-process allowance.
     *
     * @param  stateScope          the configured scope, defaulting to {@value #INSTANCE_SCOPE}
     * @param  environment         the environment, read only for its active profiles
     * @param  jdbcTemplates       the shared database, resolved only for {@value #DEPLOYMENT_SCOPE}
     * @param  transactionManagers the transaction manager, resolved only for
     *                             {@value #DEPLOYMENT_SCOPE}
     * @return the ledger matching the configured scope, never {@code null}
     * @throws IllegalStateException if the scope is unrecognised, if production asks for
     *                               {@value #INSTANCE_SCOPE}, or if {@value #DEPLOYMENT_SCOPE} is asked
     *                               for in a context that publishes no shared database
     */
    @Bean
    public SignOnAttemptLedger signOnAttemptLedger(
            @Value("${" + STATE_SCOPE_PROPERTY + ":" + INSTANCE_SCOPE + "}") final String stateScope,
            final Environment environment,
            final ObjectProvider<JdbcTemplate> jdbcTemplates,
            final ObjectProvider<PlatformTransactionManager> transactionManagers) {
        Objects.requireNonNull(environment, "environment must not be null");
        final String requested = stateScope == null ? "" : stateScope.strip().toLowerCase(Locale.ROOT);
        final boolean production = List.of(environment.getActiveProfiles()).contains(PRODUCTION_PROFILE);
        if (DEPLOYMENT_SCOPE.equals(requested)) {
            return deploymentWideLedger(jdbcTemplates, transactionManagers);
        }
        if (INSTANCE_SCOPE.equals(requested)) {
            if (production) {
                throw new IllegalStateException(productionScopeRefusal());
            }
            LOGGER.info("Sign-on attempt state is held in this instance's memory ({}={}). Correct for a"
                            + " single-instance deployment; every deployment running more than one"
                            + " instance must set {} instead, or each instance grants its own full"
                            + " allowance.",
                    STATE_SCOPE_PROPERTY, INSTANCE_SCOPE, DEPLOYMENT_SCOPE);
            return new InMemorySignOnAttemptLedger();
        }
        throw new IllegalStateException(unrecognisedScopeRefusal(requested));
    }

    /**
     * Builds the shared store, refusing to substitute anything for it.
     *
     * @param  jdbcTemplates       the shared database
     * @param  transactionManagers the transaction manager
     * @return the deployment-wide ledger
     * @throws IllegalStateException when the context publishes no shared database, because the alternative
     *                               is a silently per-process allowance
     */
    private static SignOnAttemptLedger deploymentWideLedger(
            final ObjectProvider<JdbcTemplate> jdbcTemplates,
            final ObjectProvider<PlatformTransactionManager> transactionManagers) {
        final JdbcTemplate jdbcTemplate = jdbcTemplates.getIfAvailable();
        final PlatformTransactionManager transactionManager = transactionManagers.getIfAvailable();
        if (jdbcTemplate == null || transactionManager == null) {
            throw new IllegalStateException("The sign-on attempt state was configured as '"
                    + DEPLOYMENT_SCOPE + "' by " + STATE_SCOPE_PROPERTY + ", but this context publishes"
                    + " no shared database to hold it in"
                    + (jdbcTemplate == null ? " (no JdbcTemplate)" : "")
                    + (transactionManager == null ? " (no PlatformTransactionManager)" : "")
                    + ". Falling back to a per-instance store is refused: it would grant every instance"
                    + " its own full sign-on allowance and reset every allowance on every restart, which"
                    + " is the defect this scope exists to close. Configure a data source, or state '"
                    + INSTANCE_SCOPE + "' deliberately if this deployment really does run one instance."
                    + " See docs/decision-log.md DL-343.");
        }
        LOGGER.info("Sign-on attempt state is held in the shared database ({}={}), so one allowance is"
                        + " one allowance across every instance and survives a restart.",
                STATE_SCOPE_PROPERTY, DEPLOYMENT_SCOPE);
        return new PostgresSignOnAttemptLedger(jdbcTemplate, transactionManager);
    }

    /**
     * Assembles the refusal for a production deployment that asked for a per-instance store.
     *
     * @return a message stating what to set and why the alternative is not offered
     */
    private static String productionScopeRefusal() {
        return "The '" + PRODUCTION_PROFILE + "' profile cannot start: " + STATE_SCOPE_PROPERTY
                + " is '" + INSTANCE_SCOPE + "', which holds the sign-on attempt allowance in one"
                + " process's memory." + System.lineSeparator()
                + "  A production deployment runs more than one instance and restarts, and both"
                + " multiply the allowance: N instances grant N times the attempts before a refusal,"
                + " and every restart returns every allowance to full without anyone authenticating."
                + System.lineSeparator()
                + "  Set " + STATE_SCOPE_PROPERTY + "=" + DEPLOYMENT_SCOPE + ", which holds the state"
                + " in the shared database and needs no further configuration - the schema ships as a"
                + " migration and the table is correct empty." + System.lineSeparator()
                + "See docs/decision-log.md DL-343.";
    }

    /**
     * Assembles the refusal for a scope nobody publishes.
     *
     * @param  requested the value as configured, already folded; safe to echo because this setting carries
     *                   no credential and its permitted values are public
     * @return a message naming both permitted values and why no default was applied
     */
    private static String unrecognisedScopeRefusal(final String requested) {
        final Collection<String> permitted = List.of(INSTANCE_SCOPE, DEPLOYMENT_SCOPE);
        return STATE_SCOPE_PROPERTY + " is '" + requested + "', which is not one of " + permitted + "."
                + System.lineSeparator()
                + "  No default is applied to an unrecognised value on purpose. A misspelling is the"
                + " likeliest way this setting goes wrong, and falling back to '" + INSTANCE_SCOPE
                + "' would turn a typo into a per-process sign-on allowance on exactly the deployment"
                + " that had tried to ask for the shared one." + System.lineSeparator()
                + "See docs/decision-log.md DL-343.";
    }
}
