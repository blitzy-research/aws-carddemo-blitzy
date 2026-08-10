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

import com.carddemo.util.AwsResourceNamingRules;
import com.carddemo.util.SqsNamingRules;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.profiles.ProfileFileSystemSetting;

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
     * Diagnostic channel for the one condition this class observes without refusing: a shared
     * configuration file the SDK itself could not read. Logged at debug rather than raised, because a
     * file the SDK cannot read redirects nothing.
     */
    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProductionConfigurationValidator.class);

    /**
     * Opening of the placeholder syntax. A resolved value that still contains this has an environment
     * reference in it that nothing satisfied.
     */
    private static final String PLACEHOLDER_PREFIX = "${";

    /** Closing of the placeholder syntax. */
    private static final String PLACEHOLDER_SUFFIX = "}";

    /**
     * The setting naming the collector every span is posted to.
     *
     * <p>Declared as a constant because two independent checks read it - the required-settings sweep, which
     * establishes that a deployment supplied one at all, and {@link #validateTraceCollectorAddress} which
     * establishes that what it supplied is a collector this deployment can authenticate. Two spellings of
     * one key is one spelling waiting to be corrected in only one place.
     */
    static final String TRACE_COLLECTOR_ENDPOINT_KEY = "management.otlp.tracing.endpoint";

    /** The only transport a production collector may be addressed over. */
    static final String TRACE_COLLECTOR_REQUIRED_SCHEME = "https";

    /**
     * The request path an OTLP traces receiver serves.
     *
     * <p>Fixed by the OTLP over HTTP specification rather than chosen here, which is exactly what makes it
     * checkable: an address ending anywhere else is not addressing a traces receiver.
     */
    static final String TRACE_COLLECTOR_REQUIRED_PATH = "/v1/traces";

    /**
     * How many distinct characters a production management credential must contain.
     *
     * <p>Length alone does not make a shared secret unguessable: a credential of the required length made
     * of one repeated character has the search space of that character. This floor is deliberately far
     * below what random material of the required length yields - thirty-two random printable bytes hold
     * close to thirty distinct values - so it refuses a hand-typed pattern without ever refusing a
     * generated credential.
     */
    static final int MANAGEMENT_TOKEN_MINIMUM_DISTINCT_CHARACTERS = 16;

    /**
     * Words a credential must not contain, matched without regard to case.
     *
     * <p>Every entry here is a word that appears in a value somebody typed rather than generated. The list
     * is short on purpose: it exists to catch the credential that was left as the example, not to score
     * entropy, and a long list of banned substrings would eventually refuse a random value that happened
     * to contain one of them.
     */
    private static final List<String> MANAGEMENT_TOKEN_FORBIDDEN_WORDS = List.of(
            "changeme", "change-me", "placeholder", "example", "sample", "default",
            "password", "secret", "token", "carddemo", "0123456789", "abcdefgh");

    /** Key the job-submission queue destination is bound from, held to the production rule below. */
    static final String QUEUE_DESTINATION_KEY = AwsProperties.Sqs.JOB_QUEUE_PROPERTY;

    /**
     * The fewest characters a production operator credential may carry.
     *
     * <h2>Why a length rule exists here and nowhere else</h2>
     *
     * <p>Every other required value in the list below is refused only for being absent, unresolved or
     * blank, and that is right for all of them: a data source location, a key store format or a region
     * is a value a deployment either knows or does not, and its content is checked by whatever consumes
     * it. The operator credential is different in kind. It is the module's one <em>guessable</em>
     * secret: {@code SecurityConfig}'s management filter accepts it as a bearer token on any request
     * beneath the management base path, there is no sign-on, no account, no lockout and no attempt
     * counter behind it, and a caller who presents the right bytes holds
     * {@link SecurityConfig#MANAGEMENT_AUTHORITY} for that request. So its only defence is that it
     * cannot be enumerated.
     *
     * <p>Before this rule, nothing enforced that. The comparison is constant-time and the token is
     * stripped, both of which are necessary and neither of which helps against a short value: a
     * one-character credential is accepted by the sweep below - it is set, it resolves, it is not blank
     * - and then falls to at most a few hundred guesses. That is CWE-521, weak password requirements,
     * and it was reachable in production with no other misconfiguration.
     *
     * <p><strong>Thirty-two characters</strong> is the floor because that is the width of the two
     * generators a deployment would reasonably use - {@code openssl rand -hex 16} produces exactly 32
     * characters, {@code openssl rand -base64 24} produces 32 - and it is stated in characters rather
     * than in bits because characters are what the value arrives as. It is a floor on LENGTH and
     * therefore a proxy for unpredictability rather than a measure of it: thirty-two repetitions of one
     * letter passes, which no rule of this kind can prevent. Refusing what is provably too short is the
     * part that can be enforced mechanically, and the paragraph at
     * {@code application-prod.yml}'s head names the generators for the part that cannot.
     *
     * @see #MINIMUM_LENGTH_BY_KEY
     */
    static final int MINIMUM_MANAGEMENT_TOKEN_LENGTH = 32;

    /**
     * The keys whose value is refused for being too short as well as for being absent.
     *
     * <p>A map rather than a field on {@link RequiredSetting} because the rule applies to exactly one of
     * the thirteen and inventing a per-setting minimum would invite a length rule onto values that have
     * no business carrying one - a region and a key store alias are as long as they are. Adding a
     * second entry here is the supported way to extend it.
     *
     * <p>The signing secret is deliberately absent: {@link JwtProperties} already refuses a secret
     * shorter than the signature algorithm's own key-length floor, and duplicating that here would
     * create a second place for the number to drift from the algorithm that dictates it.
     */
    static final Map<String, Integer> MINIMUM_LENGTH_BY_KEY = Map.of(
            SecurityConfig.MANAGEMENT_TOKEN_PROPERTY, MINIMUM_MANAGEMENT_TOKEN_LENGTH);

    /**
     * Key carrying the notification destination, read by the ownership check below.
     *
     * <p>Named from the settings type rather than restated, for the reason the queue key is: two spellings
     * of one key are two answers waiting to disagree.</p>
     */
    static final String TOPIC_DESTINATION_KEY = AwsProperties.Sns.JOB_NOTIFICATION_TOPIC_PROPERTY;

    /**
     * Prefix that distinguishes a resource identifier from a bare name.
     *
     * <p>A value carrying it names an account, so its account can be - and is - compared against the one
     * this deployment declares it owns. A value without it carries no account and needs no check.</p>
     */
    private static final String RESOURCE_ID_PREFIX = "arn:";

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
     * <p>This list is the thirteen values that are written as a bare environment reference with no
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
     * so a fourteenth bare reference added to the profile without a matching entry here fails the build
     * rather than going unguarded. That check is what carried the operator credential into this list: the
     * management surface's machine credential is presented on every scrape and must no more be defaulted
     * than the signing secret is.
     */
    static final List<RequiredSetting> REQUIRED_SETTINGS = List.of(
            new RequiredSetting("spring.datasource.url", "CARDDEMO_DB_URL"),
            new RequiredSetting("spring.datasource.username", "CARDDEMO_DB_USERNAME"),
            new RequiredSetting("spring.datasource.password", "CARDDEMO_DB_PASSWORD"),
            new RequiredSetting("server.ssl.key-store", "CARDDEMO_TLS_KEYSTORE"),
            new RequiredSetting("server.ssl.key-store-password", "CARDDEMO_TLS_KEYSTORE_PASSWORD"),
            new RequiredSetting("server.ssl.key-store-type", "CARDDEMO_TLS_KEYSTORE_TYPE"),
            new RequiredSetting("server.ssl.key-alias", "CARDDEMO_TLS_KEY_ALIAS"),
            new RequiredSetting(TRACE_COLLECTOR_ENDPOINT_KEY, "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"),
            new RequiredSetting(REGION_KEY, "AWS_REGION"),
            new RequiredSetting(QUEUE_DESTINATION_KEY, "CARDDEMO_SQS_QUEUE"),
            new RequiredSetting("carddemo.security.jwt.secret", "CARDDEMO_JWT_SECRET"),
            new RequiredSetting("carddemo.security.field-encryption.key", "CARDDEMO_FIELD_ENCRYPTION_KEY"),
            new RequiredSetting(SecurityConfig.MANAGEMENT_TOKEN_PROPERTY,
                    "CARDDEMO_MANAGEMENT_TOKEN"),
            new RequiredSetting(AwsResourceTrustVerifier.EXPECTED_ACCOUNT_ID_PROPERTY,
                    "CARDDEMO_AWS_ACCOUNT_ID"));

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
     * Every environment variable the AWS SDK itself reads an endpoint redirection from.
     *
     * <h2>Why this list exists separately from {@link #FORBIDDEN_PRODUCTION_KEYS}</h2>
     *
     * <p>The list above closes the keys that reach a client <em>through this application</em> - the
     * module's own setting and the cloud integration's four. Closing them is necessary and it is
     * <strong>not sufficient</strong>, because the SDK resolves an endpoint from a chain of its own that
     * no Spring property source participates in. Declaring no override, and validating that no override
     * is declared, therefore does not mean no override is in force: with none of the five keys above
     * present, {@link AwsConfig} calls no {@code endpointOverride(...)} at all, logs that the client is
     * "resolving the endpoint of region ...", and the SDK then quietly applies the redirection it found
     * in its own chain. The log line asserts a posture the code was not enforcing.
     *
     * <p>The variables below are that chain's environment half, as resolved by the
     * {@code software.amazon.awssdk} 2.31.78 artifacts this module builds against: one global variable
     * and one per service identifier, where the service half of the name is the SDK's own service
     * identifier upper-cased. Only the three services this module uses are listed, because only three
     * clients exist to redirect.
     *
     * <p>What a redirection costs here is specific rather than theoretical, and it is the same cost the
     * list above records: the queue carries the eighty-column job-control cards of every batch
     * submission, and the object store carries statements, reports and rejected records bearing account
     * identifiers, card numbers and monetary balances.
     *
     * @see #FORBIDDEN_SDK_SYSTEM_PROPERTIES
     */
    static final List<String> FORBIDDEN_SDK_ENVIRONMENT_VARIABLES = List.of(
            "AWS_ENDPOINT_URL",
            "AWS_ENDPOINT_URL_S3",
            "AWS_ENDPOINT_URL_SQS",
            "AWS_ENDPOINT_URL_SNS");

    /**
     * Every JVM system property the AWS SDK itself reads an endpoint redirection from.
     *
     * <p>The system-property half of the same chain described on
     * {@link #FORBIDDEN_SDK_ENVIRONMENT_VARIABLES}. It is listed separately from the environment half
     * because the two are read from different places and a deployment can supply either: a container
     * image sets variables, an orchestrator's launch arguments set properties, and a
     * {@code JAVA_TOOL_OPTIONS} value sets properties without appearing on the command line at all.
     * Refusing one half would leave the other open.
     *
     * <p>These are the camel-case forms the SDK's own setting definitions declare, not the
     * upper-snake-case variable names above, so neither list can be derived from the other and both are
     * stated literally.
     */
    static final List<String> FORBIDDEN_SDK_SYSTEM_PROPERTIES = List.of(
            "aws.endpointUrl",
            "aws.endpointUrlS3",
            "aws.endpointUrlSqs",
            "aws.endpointUrlSns");

    /**
     * The shared-configuration property that redirects every client, and the same property inside a
     * per-service subsection.
     *
     * <p>The third arm of the SDK's chain is the shared configuration file - {@code ~/.aws/config} by
     * default, relocatable by {@code AWS_CONFIG_FILE}. A deployment that mounts a configuration file
     * carrying {@code endpoint_url} redirects every client without setting a single variable or
     * property, which is why detecting the file's content is part of closing the channel rather than an
     * optional extra.
     *
     * <p>The file is read through the SDK's own profile reader rather than parsed here, so the section
     * selection, the profile precedence and the {@code services} indirection are resolved exactly as the
     * client that would honour them resolves them. A hand-written scan would have to reimplement those
     * rules and would then be wrong in a different way than the SDK.
     */
    static final String SDK_SHARED_CONFIG_ENDPOINT_PROPERTY = "endpoint_url";

    /**
     * The SDK switch that makes the shared configuration file's endpoint settings inert.
     *
     * <p>Recognised so the check can <em>stand down</em> rather than refuse: a deployment that has
     * already told the SDK to ignore configured endpoint URLs has closed the file channel by the SDK's
     * own mechanism, and refusing it as well would reject a correct posture. The variable and property
     * forms are both honoured because the SDK honours both.
     */
    static final String SDK_IGNORE_ENDPOINTS_VARIABLE = "AWS_IGNORE_CONFIGURED_ENDPOINT_URLS";

    /** System-property form of {@link #SDK_IGNORE_ENDPOINTS_VARIABLE}. */
    static final String SDK_IGNORE_ENDPOINTS_PROPERTY = "aws.ignoreConfiguredEndpointUrls";

    /** Variable relocating the shared configuration file the SDK reads. */
    static final String SDK_CONFIG_FILE_VARIABLE = "AWS_CONFIG_FILE";

    /** Variable selecting which section of the shared configuration file applies. */
    static final String SDK_PROFILE_VARIABLE = "AWS_PROFILE";

    /** Section name used when no profile is selected. */
    static final String SDK_DEFAULT_PROFILE = "default";

    /** Shared-configuration key whose value names the per-service subsection to consult. */
    static final String SDK_SERVICES_PROPERTY = "services";

    /**
     * The SDK service identifiers this module operates a client for, lower-case as the file uses them.
     *
     * <p>Used only to look inside a {@code services} subsection. A redirection declared for a service
     * this module never calls redirects nothing and is not this deployment's business to refuse.
     */
    static final List<String> SDK_SERVICE_IDENTIFIERS = List.of("s3", "sqs", "sns");

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
     * <p>Three checks run, in this order and for a reason. The required settings are validated first,
     * because the outbound-trust check compares a destination against the configured region and a
     * missing region should be reported as a missing region rather than as an uncheckable destination.
     * The native-channel check runs last because it is the one check that reads outside the environment
     * abstraction, and a deployment with a missing region or an untrusted queue should learn that first.
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
            validateConfiguredResourceOwnership(environment);
            validateTraceCollectorAddress(environment);
            validateManagementCredentialQuality(environment);
            validateNativeSdkEndpointChannels(System::getenv, System::getProperty,
                    ProductionConfigurationValidator::readSharedConfiguration);
        };
    }

    /**
     * Refuses a production deployment in which the AWS SDK's own endpoint chain would redirect a client.
     *
     * <h2>The gap this closes</h2>
     *
     * <p>{@link #validateOutboundTrust(Environment)} closes the five configuration keys that reach a
     * client through this application, and {@link AwsConfig} calls {@code endpointOverride(...)} only
     * when one of them is present. Neither fact constrains the SDK, which resolves an endpoint from a
     * chain of its own - environment variables, JVM system properties and the shared configuration file
     * - that no Spring property source participates in. So with every key above absent, the previous
     * posture was: no override applied by this module, a log line stating the client resolves its
     * region's endpoint, and the SDK silently honouring a redirection from its own chain. The claim in
     * the log was not the behaviour of the process.
     *
     * <p>All three arms are refused here, and refused rather than merely logged, because a redirected
     * client is indistinguishable from a working one from inside the application: the queue is defined
     * ignore-on-error, so a redirected submission reports success and no job ever runs, and a redirected
     * object store accepts statements, reports and rejected records bearing account identifiers, card
     * numbers and monetary balances. A deployment that cannot address its own account must not start.
     *
     * <h2>What is deliberately NOT refused</h2>
     *
     * <p>The SDK's regional-variant settings - the FIPS and dual-stack endpoint selectors - are absent
     * from every list. They select a variant <em>of the region's own endpoint</em> rather than replacing
     * it with an arbitrary host, so a deployment obliged to use validated cryptography or a dual-stack
     * network is expressing a legitimate posture. Refusing them would reject correct deployments while
     * closing nothing.
     *
     * <p>{@link #SDK_IGNORE_ENDPOINTS_VARIABLE} is likewise not a fault: it is the SDK's own switch for
     * making the shared configuration file inert, so a deployment that sets it has closed the file
     * channel by the supported mechanism and the file is not read at all.
     *
     * <h2>Why the readers are parameters</h2>
     *
     * <p>Process environment variables cannot be set from within a JVM, so a check that called
     * {@link System#getenv()} directly could not be exercised for the variable half of the chain at all
     * - which is the half a container image supplies. The three readers are therefore parameters, bound
     * to the real sources by the guard above and to fakes by the tests, so every channel is proven
     * closed rather than assumed closed.
     *
     * @param environmentReader     reads one process environment variable by name
     * @param systemPropertyReader  reads one JVM system property by name
     * @param sharedConfiguration   supplies the shared configuration file's endpoint declarations, given
     *                              the resolved configuration file location and profile name
     * @throws IllegalStateException when any arm of the SDK's endpoint chain supplies a redirection; the
     *                               message names each offending channel and never repeats its value
     * @throws NullPointerException  when any reader is {@code null}
     */
    static void validateNativeSdkEndpointChannels(
            final UnaryOperator<String> environmentReader,
            final UnaryOperator<String> systemPropertyReader,
            final SharedConfigurationReader sharedConfiguration) {
        Objects.requireNonNull(environmentReader, "environmentReader");
        Objects.requireNonNull(systemPropertyReader, "systemPropertyReader");
        Objects.requireNonNull(sharedConfiguration, "sharedConfiguration");

        final List<String> faults = new ArrayList<>();
        for (final String variable : FORBIDDEN_SDK_ENVIRONMENT_VARIABLES) {
            if (isSuppliedExternalValue(environmentReader.apply(variable))) {
                faults.add("  " + variable + " (process environment): the AWS SDK reads an endpoint"
                        + " redirection from this variable directly, so declaring no application"
                        + " endpoint setting does not stop it. Unset the variable");
            }
        }
        for (final String property : FORBIDDEN_SDK_SYSTEM_PROPERTIES) {
            if (isSuppliedExternalValue(systemPropertyReader.apply(property))) {
                faults.add("  " + property + " (JVM system property): the AWS SDK reads an endpoint"
                        + " redirection from this property directly, including when it arrives through"
                        + " JAVA_TOOL_OPTIONS rather than the command line. Remove the property");
            }
        }
        faults.addAll(sharedConfigurationFaults(environmentReader, systemPropertyReader,
                sharedConfiguration));

        if (!faults.isEmpty()) {
            throw new IllegalStateException(nativeChannelFailureMessage(faults));
        }
    }

    /**
     * Reports the shared-configuration file's endpoint declarations, or nothing when the SDK is already
     * told to ignore them.
     *
     * @param environmentReader    reads one process environment variable by name
     * @param systemPropertyReader reads one JVM system property by name
     * @param sharedConfiguration  supplies the file's endpoint declarations
     * @return one indented fault line per declaration found, in a stable order
     */
    private static List<String> sharedConfigurationFaults(
            final UnaryOperator<String> environmentReader,
            final UnaryOperator<String> systemPropertyReader,
            final SharedConfigurationReader sharedConfiguration) {

        if (isIgnoringConfiguredEndpoints(environmentReader, systemPropertyReader)) {
            return List.of();
        }
        final String configuredLocation = firstSupplied(
                environmentReader.apply(SDK_CONFIG_FILE_VARIABLE),
                systemPropertyReader.apply(ProfileFileSystemSetting.AWS_CONFIG_FILE.property()));
        final String profileName = firstSupplied(
                environmentReader.apply(SDK_PROFILE_VARIABLE),
                systemPropertyReader.apply(ProfileFileSystemSetting.AWS_PROFILE.property()));
        final String resolvedProfile = profileName == null ? SDK_DEFAULT_PROFILE : profileName;

        final List<String> declarations =
                sharedConfiguration.endpointDeclarations(configuredLocation, resolvedProfile);
        final List<String> faults = new ArrayList<>(declarations.size());
        for (final String declaration : declarations) {
            faults.add("  " + declaration + " (AWS shared configuration, profile '" + resolvedProfile
                    + "'): the AWS SDK reads an endpoint redirection from the shared configuration"
                    + " file. Remove the declaration, or set " + SDK_IGNORE_ENDPOINTS_VARIABLE
                    + "=true so the SDK ignores the file's endpoint settings");
        }
        return List.copyOf(faults);
    }

    /**
     * Reports whether the deployment has told the SDK to ignore configured endpoint URLs.
     *
     * @param environmentReader    reads one process environment variable by name
     * @param systemPropertyReader reads one JVM system property by name
     * @return {@code true} when either form of the switch is set to a true value
     */
    private static boolean isIgnoringConfiguredEndpoints(
            final UnaryOperator<String> environmentReader,
            final UnaryOperator<String> systemPropertyReader) {
        return isTrueValue(environmentReader.apply(SDK_IGNORE_ENDPOINTS_VARIABLE))
                || isTrueValue(systemPropertyReader.apply(SDK_IGNORE_ENDPOINTS_PROPERTY));
    }

    /**
     * Recognises the SDK's boolean spelling without importing a parser for one word.
     *
     * <p>Compared without regard to case and after trimming, because a deployment writes {@code TRUE},
     * {@code True} or {@code true} and all three mean the same thing to the SDK.
     *
     * @param value the raw text, which may be {@code null}
     * @return {@code true} only for the literal {@code true}
     */
    private static boolean isTrueValue(final String value) {
        return value != null && "true".equalsIgnoreCase(value.strip());
    }

    /**
     * Returns the first of two external readings that a deployment actually supplied.
     *
     * @param preferred the reading that wins when both are present
     * @param fallback  the reading consulted when the preferred one is absent
     * @return the stripped winning value, or {@code null} when neither was supplied
     */
    private static String firstSupplied(final String preferred, final String fallback) {
        if (isSuppliedExternalValue(preferred)) {
            return preferred.strip();
        }
        if (isSuppliedExternalValue(fallback)) {
            return fallback.strip();
        }
        return null;
    }

    /**
     * Reports whether an environment variable or system property carries a value at all.
     *
     * <p>Distinct from {@link #isSuppliedValue(String, String)}, which additionally recognises Spring's
     * placeholder syntax. A variable read straight from the process environment never contains a Spring
     * placeholder, so only absence and blankness are non-values here - and an empty variable must count
     * as absent, because {@code AWS_ENDPOINT_URL=} redirects nothing.
     *
     * @param value the raw reading, which may be {@code null}
     * @return {@code true} when the value is present and not blank
     */
    private static boolean isSuppliedExternalValue(final String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Reads the AWS shared configuration file through the SDK's own profile reader.
     *
     * <p>Using the SDK's reader rather than parsing the file here is the point: the profile precedence,
     * the section naming and the {@code services} indirection are resolved exactly as the client that
     * would honour them resolves them, so this check cannot disagree with the SDK about what the file
     * says. A missing or unreadable file declares nothing, which is the ordinary case and not a fault.
     *
     * @param configuredLocation the location named by the deployment, or {@code null} for the default
     * @param profileName        the resolved profile section name
     * @return one description per endpoint declaration found, empty when the file declares none
     */
    private static List<String> readSharedConfiguration(final String configuredLocation,
            final String profileName) {
        final ProfileFile profileFile;
        try {
            profileFile = configuredLocation == null
                    ? ProfileFile.defaultProfileFile()
                    : ProfileFile.builder()
                            .type(ProfileFile.Type.CONFIGURATION)
                            .content(Path.of(configuredLocation))
                            .build();
        } catch (final RuntimeException unreadable) {
            // A file the SDK cannot read declares nothing to the SDK either, so it redirects nothing.
            // The type is recorded rather than the path or the parser's message, so a diagnostic can
            // never echo a mounted location.
            LOGGER.debug("The AWS shared configuration file was not readable and declares no endpoint;"
                    + " failureType={}", unreadable.getClass().getSimpleName());
            return List.of();
        }
        // Fully qualified because the simple name Profile is already taken in this file by the Spring
        // annotation that confines the whole class to the production profile.
        final Optional<software.amazon.awssdk.profiles.Profile> profile =
                profileFile.profile(profileName);
        if (profile.isEmpty()) {
            return List.of();
        }
        final software.amazon.awssdk.profiles.Profile resolved = profile.get();
        final List<String> declarations = new ArrayList<>();
        resolved.property(SDK_SHARED_CONFIG_ENDPOINT_PROPERTY)
                .ifPresent(ignored -> declarations.add(SDK_SHARED_CONFIG_ENDPOINT_PROPERTY));
        resolved.property(SDK_SERVICES_PROPERTY).ifPresent(section ->
                declarations.addAll(serviceSectionDeclarations(profileFile, section)));
        return List.copyOf(declarations);
    }

    /**
     * Reports the per-service endpoint declarations of one {@code services} subsection.
     *
     * @param profileFile the parsed shared configuration
     * @param sectionName the subsection named by the profile's {@code services} key
     * @return one description per service identifier this module operates a client for
     */
    private static List<String> serviceSectionDeclarations(final ProfileFile profileFile,
            final String sectionName) {
        final Optional<software.amazon.awssdk.profiles.Profile> section =
                profileFile.getSection(SDK_SERVICES_PROPERTY, sectionName);
        if (section.isEmpty()) {
            return List.of();
        }
        final software.amazon.awssdk.profiles.Profile services = section.get();
        final List<String> declarations = new ArrayList<>();
        for (final String service : SDK_SERVICE_IDENTIFIERS) {
            final String key = service + '.' + SDK_SHARED_CONFIG_ENDPOINT_PROPERTY;
            if (services.property(key).isPresent()) {
                declarations.add(SDK_SERVICES_PROPERTY + '.' + sectionName + '.' + key);
            }
        }
        return List.copyOf(declarations);
    }

    /**
     * Assembles the native-channel failure text.
     *
     * @param faults one line per refusal, already indented
     * @return a message that states the count, explains why the application's own settings could not
     *         have caught it, lists every channel and points at the recorded decision
     */
    private static String nativeChannelFailureMessage(final List<String> faults) {
        return "The '" + PRODUCTION_PROFILE + "' profile cannot start: " + faults.size()
                + " AWS SDK endpoint redirection channel(s) outside this application's configuration"
                + " would send data somewhere this deployment does not own." + System.lineSeparator()
                + "These channels are read by the SDK itself, not by any Spring property source, so"
                + " declaring no endpoint setting in a profile document does not close them and no"
                + " property-based check can see them." + System.lineSeparator()
                + String.join(System.lineSeparator(), faults)
                + System.lineSeparator()
                + "The FIPS and dual-stack selectors are NOT refused: they select a variant of the"
                + " region's own endpoint rather than replacing it with another host."
                + System.lineSeparator()
                + "See docs/decision-log.md DL-105 and the variable list at the head of "
                + "application-prod.yml.";
    }

    /**
     * Supplies the AWS shared configuration file's endpoint declarations.
     *
     * <p>Named rather than expressed as a two-argument function so the parameters have names at the call
     * site and a test double reads as what it stands in for.
     */
    @FunctionalInterface
    interface SharedConfigurationReader {

        /**
         * Reports every endpoint declaration one profile of the shared configuration carries.
         *
         * @param configuredLocation the location named by the deployment, or {@code null} for the
         *                           SDK's default location
         * @param profileName        the resolved profile section name, never {@code null}
         * @return one description per declaration, empty when the profile declares none
         */
        List<String> endpointDeclarations(String configuredLocation, String profileName);
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
     * Refuses a production deployment whose configured queue or topic <em>locator</em> names an account
     * other than the one it declares it owns.
     *
     * <h2>What this adds to the two checks either side of it</h2>
     *
     * <p>{@link #validateOutboundTrust(Environment)} establishes that the queue destination is
     * well-formed and belongs to the configured region, and {@link AwsResourceTrustVerifier} establishes
     * - by asking the services themselves - that the <em>resolved</em> queue and topic are owned by the
     * declared account. This check sits between them and is neither redundant nor a substitute for
     * either: it compares the account carried by the value a deployer typed, which means a
     * cross-account locator is refused <strong>before a single bean is created</strong> rather than when
     * the verifier's bean is initialised. For a fault that a deployer fixes by editing one variable,
     * being told at the earliest possible moment is the difference between a failed deployment and a
     * partially started one.
     *
     * <p><strong>A bare name is not checked, and that is not a gap.</strong> A bare queue or topic name
     * carries no account: the client resolves it against this deployment's own credentials, so it cannot
     * name another account's resource at all. The preferred form is therefore the form that needs no
     * check, and requiring a resource identifier instead - which is what "require exact identifiers"
     * would mean - would be strictly weaker, because it would replace a value that cannot be
     * cross-account with one that can and then check it.
     *
     * <p>Only the two <em>outbound</em> resources are checked. The bucket is deliberately absent: a
     * bucket name carries no account either, and its ownership is established by the object store on
     * every call through the expected-owner comparison the verifier makes.
     *
     * @param environment environment to read the settings from
     * @throws IllegalStateException when a configured locator names an account other than the declared
     *                               one, or is not a locator of the service it is configured as; the
     *                               message names each offending key and never repeats a configured
     *                               value
     * @throws NullPointerException  when {@code environment} is {@code null}
     */
    static void validateConfiguredResourceOwnership(final Environment environment) {
        Objects.requireNonNull(environment, "environment");

        final String declaredAccount = resolveLeniently(environment,
                AwsResourceTrustVerifier.EXPECTED_ACCOUNT_ID_PROPERTY);
        if (!isSuppliedValue(AwsResourceTrustVerifier.EXPECTED_ACCOUNT_ID_PROPERTY, declaredAccount)) {
            // Already reported, in full and by variable name, by the required-settings sweep that runs
            // first. Reporting it a second time here would say the same thing in weaker terms.
            return;
        }
        final String region = resolveLeniently(environment, REGION_KEY);
        if (!isSuppliedValue(REGION_KEY, region)) {
            // Same reasoning: a locator can only be judged against the deployment it belongs to, and a
            // missing region is reported as a missing region by the sweep above.
            return;
        }

        final List<String> faults = new ArrayList<>();
        requireOwnedLocator(environment, faults, QUEUE_DESTINATION_KEY,
                AwsResourceTrustVerifier.QUEUE_SERVICE, region.strip(), declaredAccount.strip());
        requireOwnedLocator(environment, faults, TOPIC_DESTINATION_KEY,
                AwsResourceTrustVerifier.TOPIC_SERVICE, region.strip(), declaredAccount.strip());

        if (!faults.isEmpty()) {
            throw new IllegalStateException(resourceOwnershipFailureMessage(faults));
        }
    }

    /**
     * Adds a fault when one configured locator is a resource identifier naming another account.
     *
     * @param environment      environment to read the locator from
     * @param faults           collector of refusal lines, each already indented
     * @param propertyKey      the key carrying the locator
     * @param service          the service segment the locator must carry when it is a resource identifier
     * @param region           the region this deployment configured
     * @param declaredAccount  the account this deployment declares it owns
     */
    private static void requireOwnedLocator(final Environment environment, final List<String> faults,
            final String propertyKey, final String service, final String region,
            final String declaredAccount) {
        final String locator = resolveLeniently(environment, propertyKey);
        if (!isSuppliedValue(propertyKey, locator) || !locator.strip().startsWith(RESOURCE_ID_PREFIX)) {
            return;
        }
        try {
            AwsResourceNamingRules.requireResourceOwnedByAccount(locator.strip(), service, region,
                    declaredAccount, AwsResourceTrustVerifier.EXPECTED_ACCOUNT_ID_PROPERTY);
        } catch (final IllegalArgumentException refused) {
            faults.add("  " + propertyKey + ": " + refused.getMessage());
        }
    }

    /**
     * Assembles the resource-ownership failure text.
     *
     * @param  faults one line per refusal, already indented
     * @return a message that states what class of fault it is, lists every fault and points at the
     *         recorded decision
     */
    private static String resourceOwnershipFailureMessage(final List<String> faults) {
        return "Production start-up refused: " + faults.size() + " configured resource locator(s) name"
                + " an account other than the one "
                + AwsResourceTrustVerifier.EXPECTED_ACCOUNT_ID_PROPERTY + " declares this deployment"
                + " owns. A well-formed locator in another account resolves and is addressed, and both"
                + " outbound channels here tolerate a failure rather than raise, so the deployment would"
                + " report success while its job cards or completion notices landed elsewhere."
                + System.lineSeparator()
                + String.join(System.lineSeparator(), faults)
                + System.lineSeparator()
                + "Use a bare name, which carries no account and cannot be redirected, or a locator in"
                + " the declared account. See docs/decision-log.md DL-302.";
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
     * Refuses a production deployment whose trace collector is not a host this deployment can authenticate.
     *
     * <h2>What presence alone did not establish</h2>
     *
     * <p>The collector address is a required setting, so a production deployment cannot start without one -
     * and until now that was the whole of the check. Presence is a weak property for an address every span
     * of every request is posted to. {@code http://} was accepted, which puts the trace of an
     * authenticated banking request - its route, its timing, its span names and every attribute the
     * instrumentation attached - on the wire in clear text and gives whatever answers on that port the
     * ability to impersonate the collector. So was a locator carrying user information, which places a
     * credential in a value that configuration dumps, process listings and any library printing an endpoint
     * will happily reproduce. So was a query string or a fragment, neither of which an OTLP receiver has
     * any use for, so their presence means the value was assembled out of something that was not an OTLP
     * endpoint. And so was a loopback address, which in production means the variable was never really set
     * and every span is being posted into a socket nothing is listening on.
     *
     * <h2>The five rules, and why each is the enforceable form of the requirement</h2>
     *
     * <ol>
     *   <li><strong>The scheme must be {@code https}.</strong> This is what "authenticated TLS" reduces to
     *       in a configuration check: the exporter's client verifies the collector's certificate against the
     *       platform trust store, so the address names a host that can prove it is that host. Client
     *       certificate authentication would be stronger still and is <em>not</em> asserted here, because
     *       the framework's OTLP tracing properties expose no client key material and inventing a second
     *       transport to carry it would put the export decision back into this module - see the recorded
     *       decision for that divergence rather than a silent claim.</li>
     *   <li><strong>No user information.</strong> A credential in a locator is a credential in every place
     *       a locator is written down.</li>
     *   <li><strong>No query and no fragment.</strong> The OTLP over HTTP specification fixes the request
     *       shape; a receiver reads neither. Their presence is evidence about where the value came from.</li>
     *   <li><strong>The path must be the OTLP traces path.</strong> Also fixed by that specification, which
     *       is what makes it checkable at all: an address ending anywhere else is not pointing at a traces
     *       receiver, however well formed it is. This is the "approved path" in a form that needs no
     *       deployment to maintain an allow list.</li>
     *   <li><strong>The host must not be loopback.</strong> A production collector is not in this process,
     *       so a loopback host is an unset variable that happens to have a fallback somewhere, and every
     *       span is being dropped into nothing while the deployment believes it is exporting.</li>
     * </ol>
     *
     * <p><strong>Why a host allow list is deliberately not required.</strong> It would be a further
     * required variable for every deployment to maintain, and it would be enforced against the same value
     * it is derived from - a deployer able to set the endpoint is able to set the list. The five rules
     * above hold without a list and cannot be satisfied by a value that is wrong in any of the ways the
     * finding names.
     *
     * <p>Refused here rather than at first export, and that ordering is the point. This runs as a
     * bean-factory post-processor, before any singleton is created, so a deployment addressing the wrong
     * collector never starts - it does not start, run, and quietly post traces somewhere for a while.
     *
     * @param environment environment to read the setting from
     * @throws IllegalStateException when the configured address is not an authenticated OTLP traces
     *                               endpoint; the message names the property and the rule it broke and
     *                               never repeats the configured value
     * @throws NullPointerException  when {@code environment} is {@code null}
     */
    static void validateTraceCollectorAddress(final Environment environment) {
        Objects.requireNonNull(environment, "environment");

        final String configured = resolveLeniently(environment, TRACE_COLLECTOR_ENDPOINT_KEY);
        if (!isSuppliedValue(TRACE_COLLECTOR_ENDPOINT_KEY, configured)) {
            // An absent value is already the required-settings sweep's to report, and reporting it twice
            // would send a deployer looking for two faults where there is one.
            return;
        }

        final List<String> faults = new ArrayList<>();
        final URI address;
        try {
            address = new URI(configured.strip());
        } catch (final URISyntaxException malformed) {
            // The configured text is never echoed, here or anywhere below: it is the one production value
            // whose typical failure mode - a credential pasted into the locator - is the thing a refusal
            // must not repeat into a log.
            throw new IllegalStateException(traceCollectorFailureMessage(
                    List.of("  " + TRACE_COLLECTOR_ENDPOINT_KEY + ": is not a well-formed address")));
        }

        final String scheme = address.getScheme();
        if (scheme == null || !TRACE_COLLECTOR_REQUIRED_SCHEME.equalsIgnoreCase(scheme)) {
            faults.add("  " + TRACE_COLLECTOR_ENDPOINT_KEY + ": must use the "
                    + TRACE_COLLECTOR_REQUIRED_SCHEME + " scheme, so the exporter authenticates the"
                    + " collector and the spans of authenticated requests are not carried in clear text");
        }
        if (address.getUserInfo() != null) {
            faults.add("  " + TRACE_COLLECTOR_ENDPOINT_KEY + ": must carry no user information; a"
                    + " credential in a locator is a credential in every place a locator is written down");
        }
        if (address.getQuery() != null || address.getFragment() != null) {
            faults.add("  " + TRACE_COLLECTOR_ENDPOINT_KEY + ": must carry neither a query nor a"
                    + " fragment; an OTLP receiver reads neither, so their presence says the value was"
                    + " assembled from something that is not an OTLP endpoint");
        }
        final String host = address.getHost();
        if (host == null || host.isBlank()) {
            faults.add("  " + TRACE_COLLECTOR_ENDPOINT_KEY + ": must name a host");
        } else if (isLoopbackHost(host)) {
            faults.add("  " + TRACE_COLLECTOR_ENDPOINT_KEY + ": must not name a loopback host in"
                    + " production; the collector is not in this process, so a loopback address means the"
                    + " variable was never set and every span is being discarded");
        }
        final String path = address.getPath();
        if (path == null || !path.equals(TRACE_COLLECTOR_REQUIRED_PATH)) {
            faults.add("  " + TRACE_COLLECTOR_ENDPOINT_KEY + ": must end at the OTLP traces path "
                    + TRACE_COLLECTOR_REQUIRED_PATH + ", which the OTLP over HTTP specification fixes;"
                    + " any other path is not a traces receiver");
        }

        if (!faults.isEmpty()) {
            throw new IllegalStateException(traceCollectorFailureMessage(faults));
        }
    }

    /**
     * Reports whether a host names this machine.
     *
     * <p>Decided on the text rather than by resolving the name, because resolution would make a
     * configuration check depend on a name server and would let a deployment pass or fail according to what
     * DNS answered at start-up. The three forms a fallback or a typo actually produces are the three
     * recognised: the conventional name, the IPv4 loopback range, and the IPv6 loopback literal.
     *
     * @param  host the host component of the configured address
     * @return {@code true} when the host names this machine
     */
    private static boolean isLoopbackHost(final String host) {
        final String candidate = host.strip().toLowerCase(Locale.ROOT);
        return candidate.equals("localhost")
                || candidate.equals("[::1]")
                || candidate.equals("::1")
                || candidate.startsWith("127.");
    }

    /**
     * Holds the production management credential to a quality floor, not merely to being present.
     *
     * <h2>What was wrong with presence</h2>
     *
     * <p>The management surface accepts one shared machine credential, compared in constant time, and every
     * metrics endpoint, every exposition scrape and every non-probe management path is reachable with it
     * and with nothing else. Until this check existed the only requirement was that the value be non-blank,
     * so a single character was a valid production credential. A one-character shared secret over an
     * unthrottled comparison is not a credential; it is an invitation, and it is presented on every scrape,
     * which means it is also long-lived by construction.
     *
     * <h2>Why a length floor, and why this floor</h2>
     *
     * <p>Thirty-two bytes is the same floor this module already applies to its signing material, for the
     * same reason: it is the point at which guessing stops being an attack and becomes arithmetic. It is
     * stated once, on the class that consumes the credential, and read here, so the rule and its use cannot
     * disagree.
     *
     * <p>Length is necessary and not sufficient, so two shape rules accompany it. A credential must contain
     * at least {@link #MANAGEMENT_TOKEN_MINIMUM_DISTINCT_CHARACTERS} distinct characters, which refuses a
     * long run of one symbol while never approaching what generated material produces. And it must not
     * contain one of a short list of words that only appear in values a person typed - the credential left
     * as the example is the failure this catches, and it is the common one.
     *
     * <h2>What this check deliberately does not attempt</h2>
     *
     * <p>It does not measure entropy and does not claim the value is random. Randomness is not a property of
     * a string, and a check that pretended otherwise would refuse legitimate credentials while passing
     * crafted ones. What it guarantees is that a credential which is obviously not random is refused before
     * a single bean is created, and the rotation expectation is documented where the variable is.
     *
     * <p>An absent value is not reported here. {@link #validateRequiredSettings} already reports it, and one
     * missing variable producing two messages teaches a deployer to fix one of them and stop reading.
     *
     * <p>See {@code docs/decision-log.md} entry DL-312.
     *
     * @param environment the environment to read, never {@code null}
     * @throws IllegalStateException if a supplied credential breaks any rule
     */
    static void validateManagementCredentialQuality(final Environment environment) {
        Objects.requireNonNull(environment, "environment");

        final String configured =
                resolveLeniently(environment, SecurityConfig.MANAGEMENT_TOKEN_PROPERTY);
        if (!isSuppliedValue(SecurityConfig.MANAGEMENT_TOKEN_PROPERTY, configured)) {
            return;
        }

        // Stripped before measuring, because the consumer strips before comparing: measuring the
        // unstripped text would accept a credential whose length is mostly whitespace the filter discards.
        final String credential = configured.strip();
        final List<String> faults = new ArrayList<>();
        final int suppliedBytes = credential.getBytes(StandardCharsets.UTF_8).length;
        if (suppliedBytes < SecurityConfig.MANAGEMENT_TOKEN_MINIMUM_BYTES) {
            faults.add("  " + SecurityConfig.MANAGEMENT_TOKEN_PROPERTY + ": must be at least "
                    + SecurityConfig.MANAGEMENT_TOKEN_MINIMUM_BYTES + " bytes of generated material,"
                    + " which is the floor at which guessing a shared secret stops being feasible");
        }
        if (credential.chars().distinct().count() < MANAGEMENT_TOKEN_MINIMUM_DISTINCT_CHARACTERS) {
            faults.add("  " + SecurityConfig.MANAGEMENT_TOKEN_PROPERTY + ": must contain at least "
                    + MANAGEMENT_TOKEN_MINIMUM_DISTINCT_CHARACTERS + " distinct characters; a value of"
                    + " the required length built from a few repeated symbols has the search space of"
                    + " those symbols");
        }
        final String folded = credential.toLowerCase(Locale.ROOT);
        for (final String forbidden : MANAGEMENT_TOKEN_FORBIDDEN_WORDS) {
            if (folded.contains(forbidden)) {
                faults.add("  " + SecurityConfig.MANAGEMENT_TOKEN_PROPERTY + ": must not contain the word"
                        + " \"" + forbidden + "\", which appears in values that were typed rather than"
                        + " generated");
                break;
            }
        }

        if (!faults.isEmpty()) {
            throw new IllegalStateException(managementCredentialFailureMessage(faults));
        }
    }

    /**
     * Composes the management credential refusal.
     *
     * <p>The configured value is never repeated, for a reason narrower than the general one: this value is a
     * live credential for the surface that publishes this deployment's metrics, so a refusal that echoed it
     * would write a working credential into the log of the deployment that rejected it, where it would
     * outlive the correction. The rule that was broken is named instead, which is what a deployer needs.
     *
     * @param  faults one line per broken rule
     * @return the message the refusal carries
     */
    private static String managementCredentialFailureMessage(final List<String> faults) {
        return "The \'" + PRODUCTION_PROFILE + "\' profile cannot start: the configured management"
                + " credential breaks " + faults.size() + " rule(s)." + System.lineSeparator()
                + "This one shared secret reaches every metrics endpoint and every exposition scrape,"
                + " it is presented on every scrape, and it is compared and not derived."
                + System.lineSeparator()
                + String.join(System.lineSeparator(), faults)
                + System.lineSeparator()
                + "The configured value is deliberately not repeated here. Generate a new one, for"
                + " example with 32 bytes from a cryptographic source rendered as text, and start again. "
                + "See docs/decision-log.md DL-312 and the variable list at the head of "
                + "application-prod.yml.";
    }

    /**
     * Assembles the trace-collector failure text.
     *
     * @param  faults one line per refusal, already indented
     * @return a message that states what class of fault it is, lists every fault and never repeats the
     *         configured value
     */
    private static String traceCollectorFailureMessage(final List<String> faults) {
        return "The \'" + PRODUCTION_PROFILE + "\' profile cannot start: the configured trace collector"
                + " address breaks " + faults.size() + " rule(s)." + System.lineSeparator()
                + "Every span of every authenticated request is posted to this address, and the collector"
                + " is a remote party this deployment must be able to authenticate."
                + System.lineSeparator()
                + String.join(System.lineSeparator(), faults)
                + System.lineSeparator()
                + "The configured value is deliberately not repeated here. Correct it and start again. "
                + "See docs/decision-log.md DL-311 and the variable list at the head of "
                + "application-prod.yml.";
    }

    /**
     * Checks every required setting and reports all of the unusable ones together.
     *
     * <p>Reporting every fault in one message rather than the first one is deliberate: a deployment
     * missing four variables should learn that in one attempt, not in four.
     *
     * @param environment environment to read the settings from
     * @throws IllegalStateException when at least one required setting is undeclared, unresolved,
     *                               blank, or - for a setting carrying a minimum in
     *                               {@link #MINIMUM_LENGTH_BY_KEY} - shorter than that minimum; the
     *                               message names each offending property, the environment variable
     *                               that supplies it and why it was rejected
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
     * <p>The length rule is applied last, and only to the keys {@link #MINIMUM_LENGTH_BY_KEY} names.
     * Order matters here for the same reason it does above: a value that is absent is not also "too
     * short", and reporting it that way would send a deployer to generate a credential for a variable
     * they have not set yet.
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
        final Integer minimumLength = MINIMUM_LENGTH_BY_KEY.get(setting.propertyKey());
        if (minimumLength != null && resolved.strip().length() < minimumLength) {
            // Measured on the STRIPPED value, because that is the credential: SecurityConfig strips the
            // configured token before comparing, so surrounding whitespace is not part of what an
            // attacker has to guess and must not be allowed to count towards the floor. The report says
            // what was wrong and how to produce a value that is not, and it never echoes the value -
            // a start-up log carrying a rejected credential is a credential in a log.
            return "the value is " + resolved.strip().length() + " characters and this credential "
                    + "requires at least " + minimumLength + ". It is presented as a bearer token on "
                    + "the management surface, with no sign-on and no attempt limit behind it, so its "
                    + "only defence is that it cannot be guessed. Generate one with "
                    + "`openssl rand -hex 16` or `openssl rand -base64 24`";
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
