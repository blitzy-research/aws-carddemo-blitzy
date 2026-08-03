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

import com.carddemo.util.SqsNamingRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Refuses to let the production profile start when a required deployment value is missing, empty or
 * still standing as its own placeholder text.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>{@code application-prod.yml} writes every required value as a bare {@code ${VARIABLE}} with no
 * fallback tail, because a defaulted secret puts a usable value in the repository exactly as a literal
 * one does. Writing it that way is necessary and it is <strong>not sufficient</strong>: the bare form
 * expresses an intention, and on its own it does not enforce one.
 *
 * <p>Three behaviours of the framework, each confirmed by direct observation against the resolved
 * Spring Boot 3.5.16 and Spring Framework 6.2.19 artifacts this module builds against, are the reason:
 *
 * <ol>
 *   <li><strong>Settings bound as configuration properties tolerate an unresolved placeholder.</strong>
 *       The binder resolves placeholders leniently, so with {@code CARDDEMO_JWT_SECRET} unset the
 *       signing secret binds as the sixteen-character literal {@code ${CARDDEMO_JWT_SECRET}}. That
 *       literal is not blank, so the {@code @NotBlank} constraint already declared on
 *       {@link JwtProperties#secret()} records no violation and the application starts and signs
 *       tokens with the text of its own placeholder. The data source location, the data source user,
 *       the key store location, the key store format, the key entry alias and the region are bound the
 *       same way and behave the same way.</li>
 *   <li><strong>Settings read through a value expression fail, but late and obscurely.</strong> That
 *       path resolves strictly, so it raises a placeholder-resolution failure - at the moment the one
 *       bean that happens to read it is created, in terms that describe a placeholder rather than
 *       naming a variable a deployer must set, and only for whichever such bean is reached first.</li>
 *   <li><strong>An empty variable is silent on every path.</strong> {@code CARDDEMO_DB_PASSWORD=}
 *       exported as an empty string resolves to an empty string, binds as an empty string, and is then
 *       offered to the database server as a credential. Whether that is refused is the server's
 *       decision, not this module's.</li>
 * </ol>
 *
 * <p>The requirement being enforced is that a missing secret aborts start-up rather than binding a
 * placeholder. This class is the mechanism that makes that true, and the reasoning is recorded in
 * {@code docs/decision-log.md} DL-105.
 *
 * <h2>When the check runs, and why that point was chosen</h2>
 *
 * <p>The check is published as a {@link BeanFactoryPostProcessor} from a {@code static} factory method.
 * Post-processors of that kind are instantiated and invoked while the context is still processing bean
 * definitions - before the singleton phase creates the data source, before the migration runner opens a
 * connection, before the embedded server reads the key store, and before any configuration-properties
 * object is bound. So a deployment that is missing a variable stops with this message and never touches
 * infrastructure with a placeholder in its hand. {@code ProductionInfrastructureIsUntouchedTest} proves
 * that ordering by observing that a marker bean is never instantiated when the check fails.
 *
 * <p>Declaring the factory method {@code static} matters: a non-static one would force the enclosing
 * configuration class to be instantiated before the container is ready to enhance it, which the
 * framework reports and which serves no purpose here.
 *
 * <h2>What the check does and does not judge</h2>
 *
 * <p>It judges <em>usability</em>: for each required key the resolved text must exist, must not be
 * blank, and must not still contain placeholder syntax. It deliberately does not judge <em>shape</em> -
 * it does not test whether a JDBC location parses, whether a key decodes to thirty-two bytes or whether
 * a queue name ends in the ordered-queue suffix. Those belong to the components that consume them, and
 * three of the twelve keys already carry such a check:
 * {@link JwtProperties} constrains the signing secret,
 * {@code SensitiveFieldEncryptionService} decodes and length-checks the field-encryption key, and
 * {@code JobSubmissionService} refuses a queue name that omits the ordered suffix. Duplicating those
 * here would put the same rule in two places and let them drift.
 *
 * <p>It also does not judge whether the document was <em>written</em> without a fallback. That is a
 * property of the source text rather than of a running application, and
 * {@code ConfigurationProfileBaselineTest} asserts it directly against the document.
 *
 * <h2>Scope</h2>
 *
 * <p>{@link Profile} confines the whole class to the production profile. No other profile registers it,
 * so the local profile keeps its developer defaults, the test profile keeps its fixture values, and a
 * profile-less start is unaffected. Nothing here reads a value that is only present in production, so
 * the confinement is a deliberate policy boundary rather than a technical necessity.
 *
 * @see JwtProperties
 * @see <a href="https://docs.spring.io/spring-boot/reference/features/external-config.html">External
 *      configuration</a>
 */
@Configuration(proxyBeanMethods = false)
@Profile(ProductionConfigurationValidator.PRODUCTION_PROFILE)
public final class ProductionConfigurationValidator {

    /**
     * Name of the profile this check is confined to. Held as a constant so the annotation above, the
     * failure message below and every test naming the profile all read the same token.
     */
    public static final String PRODUCTION_PROFILE = "prod";

    /**
     * Opening of the placeholder syntax. A resolved value that still contains this has an environment
     * reference in it that nothing satisfied.
     */
    private static final String PLACEHOLDER_PREFIX = "${";

    /** Closing of the placeholder syntax. */
    private static final String PLACEHOLDER_SUFFIX = "}";

    /** Key the job-submission queue destination is bound from, held to the production rule below. */
    static final String QUEUE_DESTINATION_KEY = AwsProperties.Sqs.JOB_QUEUE_PROPERTY;

    /**
     * Key carrying the region every destination is checked against.
     *
     * <p>The region is guarded at {@code carddemo.aws.region}, which is where the production document
     * writes the bare reference, and not at the cloud integration's own
     * {@code spring.cloud.aws.region.static}: that key derives its value from this one, so the region is
     * stated once per profile and the two namespaces cannot disagree.
     */
    static final String REGION_KEY = AwsProperties.REGION_PROPERTY;

    /**
     * Every setting the production profile requires from its environment, in the order they appear in
     * {@code application-prod.yml} so that a failure message reads down the document.
     *
     * <p>This list is the twelve values that are written as a bare environment reference with no
     * fallback. The five that carry a fallback - the tracing sample rate, the staging bucket, the
     * message group, the notification topic and the token lifetime - are deliberately absent, because a
     * value that is allowed to default is by definition not required from the environment.
     *
     * <p>The region is guarded at {@code carddemo.aws.region}, which is where the production document
     * writes the bare reference, and <strong>not</strong> at the cloud integration's own
     * {@code spring.cloud.aws.region.static}: that key derives its value from this one rather than
     * restating it, so the region is stated once per profile and the two namespaces cannot disagree.
     * Guarding both would name one environment variable twice, which is a duplicate this list forbids
     * and a test asserts against.
     *
     * <p>{@code ProductionConfigurationValidatorTest} compares this list against the document itself,
     * so a thirteenth bare reference added to the profile without a matching entry here fails the build
     * rather than going unguarded.
     */
    static final List<RequiredSetting> REQUIRED_SETTINGS = List.of(
            new RequiredSetting("spring.datasource.url", "CARDDEMO_DB_URL"),
            new RequiredSetting("spring.datasource.username", "CARDDEMO_DB_USERNAME"),
            new RequiredSetting("spring.datasource.password", "CARDDEMO_DB_PASSWORD"),
            new RequiredSetting("server.ssl.key-store", "CARDDEMO_TLS_KEYSTORE"),
            new RequiredSetting("server.ssl.key-store-password", "CARDDEMO_TLS_KEYSTORE_PASSWORD"),
            new RequiredSetting("server.ssl.key-store-type", "CARDDEMO_TLS_KEYSTORE_TYPE"),
            new RequiredSetting("server.ssl.key-alias", "CARDDEMO_TLS_KEY_ALIAS"),
            new RequiredSetting("management.otlp.tracing.endpoint", "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"),
            new RequiredSetting(REGION_KEY, "AWS_REGION"),
            new RequiredSetting(QUEUE_DESTINATION_KEY, "CARDDEMO_SQS_QUEUE"),
            new RequiredSetting("carddemo.security.jwt.secret", "CARDDEMO_JWT_SECRET"),
            new RequiredSetting("carddemo.security.field-encryption.key", "CARDDEMO_FIELD_ENCRYPTION_KEY"));

    /**
     * Every configuration key that redirects a cloud client away from the endpoint its region resolves
     * to, global first and then one per client.
     *
     * <p><strong>Production may declare none of them, and the difference between "declares none" and
     * "may declare none" is this list.</strong> The production profile document happens to declare no
     * endpoint key, and an earlier revision treated that as the control. A document cannot see the
     * environment: an override supplied as {@code SPRING_CLOUD_AWS_SQS_ENDPOINT}, as a command-line
     * property, or by a co-activated overlay binds perfectly well for a key no document mentions, and
     * every client then addresses whatever host it names.
     *
     * <p>What that costs is specific rather than theoretical. The queue is the estate's single
     * online-to-batch bridge, and every message on it is an eighty-column job-control card naming the
     * job, the procedure library, the step and the reporting period. A redirected queue client sends
     * those cards to the named host and reports success, because the queue is defined ignore-on-error;
     * the operator sees report requests completing and no job ever running. A redirected object-store
     * client writes statements, reports and rejected records - which carry account identifiers, card
     * numbers and monetary balances - to the named host just as quietly.
     *
     * <p>The per-client keys are listed alongside the global one because either alone redirects that
     * client, so refusing only the global key would leave three ways to do the same thing.
     */
    static final List<String> FORBIDDEN_PRODUCTION_KEYS = List.of(
            AwsProperties.ENDPOINT_OVERRIDE_PROPERTY,
            "spring.cloud.aws.endpoint",
            "spring.cloud.aws.s3.endpoint",
            "spring.cloud.aws.sqs.endpoint",
            "spring.cloud.aws.sns.endpoint");

    /**
     * Creates the configuration class.
     *
     * <p>The container instantiates it in order to read the factory method below, even though that
     * method is {@code static} and holds no state of its own.
     */
    public ProductionConfigurationValidator() {
    }

    /**
     * Publishes the start-up check.
     *
     * <p>The returned post-processor performs the validation when the container invokes it, which is
     * before the singleton phase begins. The check is placed in the post-processor rather than in this
     * method's body on purpose: a failure raised while a bean is being <em>created</em> arrives wrapped
     * in a bean-creation failure, whereas one raised from the post-processor arrives as itself, which is
     * what a deployer reads in the log and what a test asserts on.
     *
     * <p>Two checks run, in this order and for a reason. The required settings are validated first,
     * because the outbound-trust check compares a destination against the configured region and a
     * missing region should be reported as a missing region rather than as an uncheckable destination.
     *
     * @param environment resolved environment for the active profiles, supplied by the container
     * @return a post-processor that validates the production posture and changes no bean definition
     */
    @Bean
    static BeanFactoryPostProcessor productionConfigurationGuard(final Environment environment) {
        Objects.requireNonNull(environment, "environment");
        return beanFactory -> {
            validateRequiredSettings(environment);
            validateOutboundTrust(environment);
        };
    }

    /**
     * Refuses a production deployment that redirects a cloud client, or that aims the job-submission
     * queue at a destination this deployment cannot have meant.
     *
     * <h2>The gap this closes</h2>
     *
     * <p>Two configuration facts were previously left to a document's silence rather than enforced. The
     * first is the endpoint override, addressed on {@link #FORBIDDEN_PRODUCTION_KEYS}. The second is
     * the destination itself: the queue property was checked for shape - non-blank, a recognisable form,
     * a first-in-first-out name - and shape cannot distinguish a destination that names this
     * deployment's own queue from one that names somebody else's host. The messaging client accepts any
     * syntactically valid locator, so a plain-transport URL on an unrelated host whose last path
     * segment ends in the required suffix passed every check and then received the job cards.
     *
     * <p>Both are refused here rather than in the settings type, because both are questions about the
     * <em>deployment</em>. The local and test profiles legitimately declare an emulator endpoint and
     * name an emulator queue, and a rule that refused those would refuse the two profiles the
     * acceptance gates run in. Confining the rule to this class, which
     * {@link Profile} already confines to production, is what lets it be strict without being wrong
     * anywhere else.
     *
     * @param environment environment to read the settings from
     * @throws IllegalStateException when an endpoint override is declared, or the queue destination is
     *                              not one this deployment may send to; the message names each
     *                              offending key and why it was refused, and never repeats a configured
     *                              value
     * @throws NullPointerException when {@code environment} is {@code null}
     */
    static void validateOutboundTrust(final Environment environment) {
        Objects.requireNonNull(environment, "environment");

        final List<String> faults = new ArrayList<>();
        for (final String forbidden : FORBIDDEN_PRODUCTION_KEYS) {
            final String declared = resolveLeniently(environment, forbidden);
            if (isSuppliedValue(forbidden, declared)) {
                faults.add("  " + forbidden + ": an endpoint override redirects a cloud client away"
                        + " from the endpoint its region resolves to. A deployment may not declare one:"
                        + " the queue carries the job-control cards of every batch submission and the"
                        + " object store carries statements, reports and rejected records. Remove the"
                        + " key and every variable that supplies it");
            }
        }

        final String destination = resolveLeniently(environment, QUEUE_DESTINATION_KEY);
        if (isSuppliedValue(QUEUE_DESTINATION_KEY, destination)) {
            final String region = resolveLeniently(environment, REGION_KEY);
            try {
                SqsNamingRules.requireProductionQueueDestination(destination.strip(),
                        QUEUE_DESTINATION_KEY,
                        isSuppliedValue(REGION_KEY, region) ? region.strip() : null);
            } catch (final IllegalArgumentException refused) {
                // The rule composes its own diagnostic and never echoes the configured value, so the
                // message is carried through as the explanation rather than re-derived here.
                faults.add("  " + refused.getMessage());
            }
        }

        if (!faults.isEmpty()) {
            throw new IllegalStateException(outboundTrustFailureMessage(faults));
        }
    }

    /**
     * Reports whether a key resolved to a value a deployment actually supplied.
     *
     * <p>Three non-values are recognised and all three mean "not supplied": the key's own placeholder,
     * which is what an undeclared key resolves to; text still containing placeholder syntax, which is a
     * declared key whose variable nothing set; and blank text. The distinction matters in both
     * directions here - an unset endpoint variable must not be reported as an override, and a declared
     * destination whose variable is unset is already reported by the required-settings sweep.
     *
     * @param propertyKey the key that was read
     * @param resolved    the text {@link #resolveLeniently} produced
     * @return {@code true} when the value was genuinely supplied
     */
    private static boolean isSuppliedValue(final String propertyKey, final String resolved) {
        return resolved != null
                && !resolved.equals(PLACEHOLDER_PREFIX + propertyKey + PLACEHOLDER_SUFFIX)
                && !resolved.contains(PLACEHOLDER_PREFIX)
                && !resolved.isBlank();
    }

    /**
     * Assembles the outbound-trust failure text.
     *
     * @param faults one line per refusal, already indented
     * @return a message that states the count, explains what class of fault it is, lists every fault
     *         and points at the recorded decision
     */
    private static String outboundTrustFailureMessage(final List<String> faults) {
        return "The '" + PRODUCTION_PROFILE + "' profile cannot start: " + faults.size()
                + " outbound-destination setting(s) would send data somewhere this deployment does not"
                + " own." + System.lineSeparator()
                + "A destination is not validated by its shape alone, because a correctly formed value"
                + " can name any host at all, and the job-submission queue carries the job-control"
                + " cards of every batch submission." + System.lineSeparator()
                + String.join(System.lineSeparator(), faults)
                + System.lineSeparator()
                + "Correct the settings named above and start again. "
                + "See docs/decision-log.md DL-105 and the variable list at the head of "
                + "application-prod.yml.";
    }

    /**
     * Checks every required setting and reports all of the unusable ones together.
     *
     * <p>Reporting every fault in one message rather than the first one is deliberate: a deployment
     * missing four variables should learn that in one attempt, not in four.
     *
     * @param environment environment to read the settings from
     * @throws IllegalStateException when at least one required setting is undeclared, unresolved or
     *                               blank; the message names each offending property, the environment
     *                               variable that supplies it and why it was rejected
     * @throws NullPointerException  when {@code environment} is {@code null}
     */
    static void validateRequiredSettings(final Environment environment) {
        Objects.requireNonNull(environment, "environment");

        final List<String> faults = new ArrayList<>();
        for (final RequiredSetting setting : REQUIRED_SETTINGS) {
            final String resolved = resolveLeniently(environment, setting.propertyKey());
            final String reason = rejectionReason(setting, resolved);
            if (reason != null) {
                faults.add("  " + setting.propertyKey() + " <- " + setting.environmentVariable()
                        + ": " + reason);
            }
        }

        if (!faults.isEmpty()) {
            throw new IllegalStateException(failureMessage(faults));
        }
    }

    /**
     * Reads a property while leaving an environment reference nothing satisfied as visible text.
     *
     * <p>The ordinary read is strict about a nested reference and raises a placeholder-resolution
     * failure on the first unsatisfied one, which would end the sweep at the first fault and would
     * describe the placeholder instead of naming the variable. Resolving an expression built from the
     * key is lenient, so an unsatisfied reference survives into the returned text and can be reported
     * against the setting that carries it.
     *
     * @param environment environment to read from
     * @param propertyKey key to read
     * @return the resolved text; the key's own placeholder when the key is not declared at all, and the
     *         inner environment reference when the key is declared but nothing supplies it
     */
    private static String resolveLeniently(final Environment environment, final String propertyKey) {
        return environment.resolvePlaceholders(PLACEHOLDER_PREFIX + propertyKey + PLACEHOLDER_SUFFIX);
    }

    /**
     * Decides whether a resolved value is usable, and says why when it is not.
     *
     * <p>The undeclared case is tested before the unresolved case because the text of an undeclared key
     * also contains placeholder syntax, and reporting it as merely unresolved would send a deployer
     * looking for a variable when the property itself is the thing that is missing.
     *
     * @param setting setting the value belongs to
     * @param resolved text produced by {@link #resolveLeniently}
     * @return a sentence explaining the rejection, or {@code null} when the value is usable
     */
    private static String rejectionReason(final RequiredSetting setting, final String resolved) {
        if (resolved == null) {
            return "the property is not declared by any active profile";
        }
        if (resolved.equals(PLACEHOLDER_PREFIX + setting.propertyKey() + PLACEHOLDER_SUFFIX)) {
            return "the property is not declared by any active profile, so nothing referenced "
                    + setting.environmentVariable() + " at all";
        }
        if (resolved.contains(PLACEHOLDER_PREFIX)) {
            return "the variable is not set, so the value is still the unresolved reference "
                    + resolved;
        }
        if (resolved.isBlank()) {
            return resolved.isEmpty()
                    ? "the variable is set but empty, and an empty value is not a value"
                    : "the variable is set to whitespace only, and whitespace is not a value";
        }
        return null;
    }

    /**
     * Assembles the failure text.
     *
     * @param faults one line per rejected setting, already indented
     * @return a message that states the count, explains that no fallback exists by design, lists every
     *         fault and points at the recorded decision
     */
    private static String failureMessage(final List<String> faults) {
        return "The '" + PRODUCTION_PROFILE + "' profile cannot start: " + faults.size() + " of "
                + REQUIRED_SETTINGS.size() + " required settings are unusable."
                + System.lineSeparator()
                + "Each is supplied by an environment variable and carries no fallback by design, so "
                + "start-up stops here rather than continuing with a placeholder or an empty value."
                + System.lineSeparator()
                + String.join(System.lineSeparator(), faults)
                + System.lineSeparator()
                + "Set the variables named above and start again. "
                + "See docs/decision-log.md DL-105 and the variable list at the head of "
                + "application-prod.yml.";
    }

    /**
     * One required production setting: the property the application reads and the environment variable
     * a deployment sets.
     *
     * <p>Both halves are carried because a failure message needs both. The property key is what appears
     * in the profile document and in a framework message; the variable name is the only part a deployer
     * can act on.
     *
     * @param propertyKey         key as declared in {@code application-prod.yml}
     * @param environmentVariable variable the profile references for that key, with no fallback
     */
    record RequiredSetting(String propertyKey, String environmentVariable) {

        /**
         * Rejects a half-built setting.
         *
         * @throws NullPointerException     when either half is {@code null}
         * @throws IllegalArgumentException when either half is blank
         */
        RequiredSetting {
            Objects.requireNonNull(propertyKey, "propertyKey");
            Objects.requireNonNull(environmentVariable, "environmentVariable");
            if (propertyKey.isBlank() || environmentVariable.isBlank()) {
                throw new IllegalArgumentException(
                        "A required setting needs both a property key and a variable name, but was "
                                + "propertyKey='" + propertyKey + "' environmentVariable='"
                                + environmentVariable + '\'');
            }
        }
    }
}
