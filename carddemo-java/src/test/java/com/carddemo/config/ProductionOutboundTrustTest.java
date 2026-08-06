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
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/**
 * Holds the production profile's outbound-destination posture: no cloud client may be redirected, and
 * the job-submission queue must be a destination this deployment can have meant.
 *
 * <h2>The defect this exists to hold closed</h2>
 *
 * <p>Production's safety previously rested on two silences. Its own profile document declares no
 * endpoint-override key, and the queue property was checked only for <em>shape</em> - non-blank, a
 * recognisable form, a name ending in the first-in-first-out suffix.
 *
 * <p>Neither is a control. A document cannot see the environment, so an override supplied as a
 * variable, a command-line property or a co-activated overlay binds perfectly well for a key no
 * document mentions, and every client then addresses whatever host it names. And shape cannot
 * distinguish a destination that names this deployment's own queue from one that names somebody else's
 * host: the messaging client accepts any syntactically valid locator, so a plain-transport URL on an
 * unrelated host whose last path segment merely ends in the required suffix passed every check and then
 * received the job cards.
 *
 * <p>What that costs is specific. Every message on that queue is an eighty-column job-control image
 * naming the job, the procedure library, the step and the reporting period, so a redirected queue both
 * discloses the batch topology and silently prevents every requested job from running - while each
 * request still answers successfully, because the queue is defined ignore-on-error.
 *
 * <h2>How the guard is exercised</h2>
 *
 * <p>Through the environment, because that is the surface the defect lived on. Each test builds an
 * otherwise-valid production environment and then changes exactly one thing, so a refusal is
 * attributable to that one thing and an acceptance is not accidental. Every fixture value below is
 * synthetic: no real account identifier, host or credential appears anywhere in this file.
 */
@DisplayName("the production profile's outbound-destination posture")
class ProductionOutboundTrustTest {

    /** The region every fixture deployment declares, and the one a destination must agree with. */
    private static final String REGION = "eu-west-2";

    /** A synthetic twelve-digit account identifier. */
    private static final String ACCOUNT = "000000000000";

    /** The canonical bare queue name, which is the preferred and safest form. */
    private static final String BARE_QUEUE = "JOBS.fifo";

    /** Constructs the fixture. */
    ProductionOutboundTrustTest() {
    }

    /**
     * Builds an environment carrying a complete, acceptable production posture.
     *
     * <p>Only the settings this guard reads are populated. The required-settings sweep is a separate
     * check with its own test, and populating its twelve entries here would couple the two.
     *
     * @return the environment
     */
    private static MockEnvironment acceptableProduction() {
        final MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(ProductionConfigurationValidator.PRODUCTION_PROFILE);
        environment.setProperty(ProductionConfigurationValidator.REGION_KEY, REGION);
        environment.setProperty(ProductionConfigurationValidator.QUEUE_DESTINATION_KEY, BARE_QUEUE);
        return environment;
    }

    /**
     * Builds an environment whose queue destination is the supplied value.
     *
     * @param destination the destination to configure
     * @return the environment
     */
    private static Environment withDestination(final String destination) {
        final MockEnvironment environment = acceptableProduction();
        environment.setProperty(ProductionConfigurationValidator.QUEUE_DESTINATION_KEY, destination);
        return environment;
    }

    /**
     * Composes a queue URL from its parts, so a test can vary one part at a time.
     *
     * @param scheme    the scheme, with its separator
     * @param host      the authority
     * @param account   the account segment
     * @param queueName the queue segment
     * @return the composed URL
     */
    private static String queueUrl(final String scheme, final String host, final String account,
            final String queueName) {
        return scheme + host + "/" + account + "/" + queueName;
    }

    @Nested
    @DisplayName("an endpoint override is refused rather than merely undeclared")
    class EndpointOverrideRefused {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"spring.cloud.aws.endpoint", "spring.cloud.aws.s3.endpoint",
            "spring.cloud.aws.sqs.endpoint", "spring.cloud.aws.sns.endpoint"})
        @DisplayName("every override key is refused on its own, so refusing only the global one would "
                + "have left three ways to do the same thing")
        void eachOverrideKeyIsRefusedOnItsOwn(final String overrideKey) {
            final MockEnvironment environment = acceptableProduction();
            environment.setProperty(overrideKey, "http://redirected.invalid:4566");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            ProductionConfigurationValidator.validateOutboundTrust(environment))
                    .withMessageContaining(overrideKey);
        }

        @Test
        @DisplayName("the guard names every override present rather than only the first, so a "
                + "deployment learns all of them in one attempt")
        void everyOverridePresentIsNamed() {
            final MockEnvironment environment = acceptableProduction();
            for (final String key : ProductionConfigurationValidator.FORBIDDEN_PRODUCTION_KEYS) {
                environment.setProperty(key, "https://redirected.invalid");
            }

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            ProductionConfigurationValidator.validateOutboundTrust(environment))
                    .satisfies(refused -> {
                        for (final String key
                                : ProductionConfigurationValidator.FORBIDDEN_PRODUCTION_KEYS) {
                            assertThat(refused.getMessage()).contains(key);
                        }
                    });
        }

        @Test
        @DisplayName("an override key declared but whose variable nothing sets is not reported as an "
                + "override, because an unresolved reference is not a value")
        void anUnresolvedOverrideIsNotAnOverride() {
            final MockEnvironment environment = acceptableProduction();
            environment.setProperty("spring.cloud.aws.sqs.endpoint", "${LOCALSTACK_ENDPOINT}");

            assertThatNoException().isThrownBy(() ->
                    ProductionConfigurationValidator.validateOutboundTrust(environment));
        }

        @ParameterizedTest(name = "override = [{0}]")
        @ValueSource(strings = {"", " ", "   "})
        @DisplayName("a blank override is not an override either, since a blank value redirects nothing")
        void aBlankOverrideIsNotAnOverride(final String blank) {
            final MockEnvironment environment = acceptableProduction();
            environment.setProperty("spring.cloud.aws.endpoint", blank);

            assertThatNoException().isThrownBy(() ->
                    ProductionConfigurationValidator.validateOutboundTrust(environment));
        }
    }

    @Nested
    @DisplayName("the queue destination must be one this deployment can have meant")
    class QueueDestinationTrust {

        @Test
        @DisplayName("a bare queue name is accepted, and it is the preferred form because a name "
                + "carries no destination at all")
        void aBareQueueNameIsAccepted() {
            assertThatNoException().isThrownBy(() -> ProductionConfigurationValidator
                    .validateOutboundTrust(withDestination(BARE_QUEUE)));
        }

        @Test
        @DisplayName("a plain-transport URL is refused, because an eighty-column job-control card on "
                + "an unencrypted connection is readable and rewritable in flight")
        void plainTransportIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator.validateOutboundTrust(
                            withDestination(queueUrl("http://", "sqs." + REGION + ".amazonaws.com",
                                    ACCOUNT, BARE_QUEUE))));
        }

        @Test
        @DisplayName("the exploit the review reported - a plain-transport URL on an unrelated host "
                + "whose last path segment ends in the required suffix - is refused")
        void theReportedExploitIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator.validateOutboundTrust(
                            withDestination("http://attacker.internal/JOBS.fifo")));
        }

        @Test
        @DisplayName("a secure URL on an unrelated host is refused too, so transport security alone is "
                + "not mistaken for identity")
        void aForeignHostIsRefusedEvenOverSecureTransport() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator.validateOutboundTrust(
                            withDestination(queueUrl("https://", "attacker.internal", ACCOUNT,
                                    BARE_QUEUE))));
        }

        @ParameterizedTest(name = "host = {0}")
        @ValueSource(strings = {
            "sqs.eu-west-2.amazonaws.com.attacker.invalid",
            "attacker.invalid.sqs.eu-west-2.amazonaws.com",
            "notsqs.eu-west-2.amazonaws.com",
            "sqs.eu-west-2.amazonaws.com:8443",
            "sqs.eu-west-2.amazonaws.com@attacker.invalid",
            "attacker.amazonaws.com"})
        @DisplayName("a host that merely resembles the endpoint is refused, because the check is a "
                + "whole-authority match rather than a search for a familiar substring")
        void aResemblingHostIsRefused(final String host) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator.validateOutboundTrust(
                            withDestination(queueUrl("https://", host, ACCOUNT, BARE_QUEUE))));
        }

        @Test
        @DisplayName("a genuine queue URL for this deployment's own region is accepted")
        void aGenuineQueueUrlIsAccepted() {
            assertThatNoException().isThrownBy(() -> ProductionConfigurationValidator
                    .validateOutboundTrust(withDestination(queueUrl("https://",
                            "sqs." + REGION + ".amazonaws.com", ACCOUNT, BARE_QUEUE))));
        }

        @Test
        @DisplayName("the validated-cryptography endpoint is accepted, because refusing it would push "
                + "a deployment towards the ordinary one")
        void theFipsEndpointIsAccepted() {
            assertThatNoException().isThrownBy(() -> ProductionConfigurationValidator
                    .validateOutboundTrust(withDestination(queueUrl("https://",
                            "sqs-fips." + REGION + ".amazonaws.com", ACCOUNT, BARE_QUEUE))));
        }

        @Test
        @DisplayName("a genuine queue URL in a DIFFERENT region is refused, which is what makes the "
                + "rule about this deployment rather than about the cloud in general")
        void aDifferentRegionIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator.validateOutboundTrust(
                            withDestination(queueUrl("https://", "sqs.us-east-1.amazonaws.com",
                                    ACCOUNT, BARE_QUEUE))));
        }

        @ParameterizedTest(name = "account = {0}")
        @ValueSource(strings = {"1", "00000000000", "0000000000000", "00000000000a", "notanaccount"})
        @DisplayName("an account segment that is not twelve digits is refused, since that segment is "
                + "the only part shape alone can hold")
        void aMalformedAccountIsRefused(final String account) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator.validateOutboundTrust(
                            withDestination(queueUrl("https://", "sqs." + REGION + ".amazonaws.com",
                                    account, BARE_QUEUE))));
        }

        @Test
        @DisplayName("a URL carrying more path than an account and a queue is refused")
        void anOverlongPathIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator.validateOutboundTrust(
                            withDestination("https://sqs." + REGION + ".amazonaws.com/" + ACCOUNT
                                    + "/extra/" + BARE_QUEUE)));
        }

        @Test
        @DisplayName("a queue ARN naming this deployment's partition, service, region and a well-formed "
                + "account is accepted")
        void aGenuineArnIsAccepted() {
            assertThatNoException().isThrownBy(() -> ProductionConfigurationValidator
                    .validateOutboundTrust(withDestination(
                            "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":" + BARE_QUEUE)));
        }

        @ParameterizedTest(name = "arn = {0}")
        @ValueSource(strings = {
            "arn:not-a-partition:sqs:eu-west-2:000000000000:JOBS.fifo",
            "arn:aws:sns:eu-west-2:000000000000:JOBS.fifo",
            "arn:aws:sqs:us-east-1:000000000000:JOBS.fifo",
            "arn:aws:sqs:eu-west-2:0000:JOBS.fifo",
            "arn:aws:sqs:eu-west-2:000000000000:JOBS",
            "arn:aws:sqs:eu-west-2:000000000000"})
        @DisplayName("an ARN whose partition, service, region, account or queue name this deployment "
                + "cannot have meant is refused")
        void aMistrustedArnIsRefused(final String arn) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            ProductionConfigurationValidator.validateOutboundTrust(
                                    withDestination(arn)));
        }

        @Test
        @DisplayName("a destination whose variable nothing sets is left to the required-settings sweep "
                + "rather than reported here as untrusted")
        void anUnresolvedDestinationIsLeftToTheOtherCheck() {
            assertThatNoException().isThrownBy(() -> ProductionConfigurationValidator
                    .validateOutboundTrust(withDestination("${CARDDEMO_SQS_QUEUE}")));
        }

        @Test
        @DisplayName("a destination cannot be checked at all without a region, and that is reported as "
                + "the missing region rather than passed over")
        void aMissingRegionIsReported() {
            final MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(ProductionConfigurationValidator.PRODUCTION_PROFILE);
            environment.setProperty(ProductionConfigurationValidator.QUEUE_DESTINATION_KEY,
                    "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":" + BARE_QUEUE);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            ProductionConfigurationValidator.validateOutboundTrust(environment))
                    .withMessageContaining("region");
        }
    }

    @Nested
    @DisplayName("the diagnostic a refusal produces")
    class RefusalDiagnostic {

        @Test
        @DisplayName("names the offending key and never repeats the configured value, so composing a "
                + "destination cannot place chosen text in the start-up log")
        void namesTheKeyAndNotTheValue() {
            final String chosenText = "attacker-chosen-log-line.invalid";

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator.validateOutboundTrust(
                            withDestination("https://" + chosenText + "/" + ACCOUNT + "/"
                                    + BARE_QUEUE)))
                    .satisfies(refused -> {
                        assertThat(refused.getMessage())
                                .contains(ProductionConfigurationValidator.QUEUE_DESTINATION_KEY);
                        assertThat(refused.getMessage())
                                .as("a diagnostic states its own expectation and does not echo what it "
                                        + "was given")
                                .doesNotContain(chosenText);
                    });
        }

        @Test
        @DisplayName("explains that shape alone is not the test, so a reader does not answer it by "
                + "making the value better formed")
        void explainsWhyShapeIsNotEnough() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator.validateOutboundTrust(
                            withDestination("http://attacker.internal/JOBS.fifo")))
                    .withMessageContaining("shape");
        }
    }

    @Nested
    @DisplayName("the guard's own inputs")
    class GuardInputs {

        @Test
        @DisplayName("a null environment is refused by name rather than dereferenced")
        void aNullEnvironmentIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator.validateOutboundTrust(null))
                    .withMessageContaining("environment");
        }

        @Test
        @DisplayName("the forbidden-key list is the module's own override key plus the four cloud "
                + "override keys and nothing else, so a sixth cannot be added without this "
                + "expectation being revisited")
        void theForbiddenKeyListIsExactlyTheFiveOverrides() {
            assertThat(ProductionConfigurationValidator.FORBIDDEN_PRODUCTION_KEYS)
                    .containsExactly("carddemo.aws.endpoint-override", "spring.cloud.aws.endpoint",
                            "spring.cloud.aws.s3.endpoint", "spring.cloud.aws.sqs.endpoint",
                            "spring.cloud.aws.sns.endpoint");
        }

        @Test
        @DisplayName("an acceptable production posture is accepted, so every refusal above is "
                + "attributable to the one thing it changed")
        void anAcceptablePostureIsAccepted() {
            final Map<String, String> baseline = new HashMap<>();
            baseline.put(ProductionConfigurationValidator.REGION_KEY, REGION);
            baseline.put(ProductionConfigurationValidator.QUEUE_DESTINATION_KEY, BARE_QUEUE);
            final MockEnvironment environment = new MockEnvironment();
            baseline.forEach(environment::setProperty);

            assertThatNoException().isThrownBy(() ->
                    ProductionConfigurationValidator.validateOutboundTrust(environment));
        }
    }

    @Nested
    @DisplayName("the AWS SDK's own endpoint chain, which no property source can see")
    class NativeSdkEndpointChannels {

        /** A destination that is plainly not this deployment's, used for every redirection fixture. */
        private static final String FOREIGN_ENDPOINT = "http://attacker.internal:4566";

        /** Nothing is supplied by either external source unless a test supplies it. */
        private static final UnaryOperator<String> NOTHING_SET = name -> null;

        /** The shared configuration declares nothing unless a test says it does. */
        private static final ProductionConfigurationValidator.SharedConfigurationReader NO_FILE =
                (location, profile) -> List.of();

        @ParameterizedTest(name = "{0} in the process environment is refused")
        @MethodSource("environmentVariables")
        @DisplayName("every environment variable the SDK reads an endpoint from is refused")
        void everyEnvironmentVariableIsRefused(final String variable) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateNativeSdkEndpointChannels(
                                    onlySets(variable), NOTHING_SET, NO_FILE))
                    .withMessageContaining(variable)
                    .withMessageContaining("process environment");
        }

        static Stream<String> environmentVariables() {
            return ProductionConfigurationValidator.FORBIDDEN_SDK_ENVIRONMENT_VARIABLES.stream();
        }

        @ParameterizedTest(name = "{0} as a JVM system property is refused")
        @MethodSource("systemProperties")
        @DisplayName("every system property the SDK reads an endpoint from is refused")
        void everySystemPropertyIsRefused(final String property) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateNativeSdkEndpointChannels(
                                    NOTHING_SET, onlySets(property), NO_FILE))
                    .withMessageContaining(property)
                    .withMessageContaining("system property");
        }

        static Stream<String> systemProperties() {
            return ProductionConfigurationValidator.FORBIDDEN_SDK_SYSTEM_PROPERTIES.stream();
        }

        @Test
        @DisplayName("a shared-configuration endpoint declaration is refused, and the profile it was "
                + "read from is named")
        void aSharedConfigurationDeclarationIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateNativeSdkEndpointChannels(NOTHING_SET, NOTHING_SET,
                                    (location, profile) -> List.of("endpoint_url")))
                    .withMessageContaining("endpoint_url")
                    .withMessageContaining("shared configuration")
                    .withMessageContaining(
                            ProductionConfigurationValidator.SDK_DEFAULT_PROFILE);
        }

        @Test
        @DisplayName("the selected profile and the relocated configuration file are both passed to the "
                + "reader, so a redirection is not missed by looking in the wrong section or file")
        void theSelectedProfileAndFileReachTheReader() {
            final List<String> observed = new ArrayList<>();

            ProductionConfigurationValidator.validateNativeSdkEndpointChannels(
                    name -> switch (name) {
                        case ProductionConfigurationValidator.SDK_PROFILE_VARIABLE -> "deployment";
                        case ProductionConfigurationValidator.SDK_CONFIG_FILE_VARIABLE ->
                                "/mounted/aws/config";
                        default -> null;
                    },
                    NOTHING_SET,
                    (location, profile) -> {
                        observed.add(location);
                        observed.add(profile);
                        return List.of();
                    });

            assertThat(observed).containsExactly("/mounted/aws/config", "deployment");
        }

        @Test
        @DisplayName("the SDK's own ignore switch stands the file check down rather than being treated "
                + "as a fault, because it closes the channel by the supported mechanism")
        void theIgnoreSwitchStandsTheFileCheckDown() {
            final List<String> readerCalls = new ArrayList<>();
            final ProductionConfigurationValidator.SharedConfigurationReader recording =
                    (location, profile) -> {
                        readerCalls.add(profile);
                        return List.of("endpoint_url");
                    };

            assertThatNoException().isThrownBy(() -> ProductionConfigurationValidator
                    .validateNativeSdkEndpointChannels(
                            onlySets(ProductionConfigurationValidator.SDK_IGNORE_ENDPOINTS_VARIABLE,
                                    "TRUE"),
                            NOTHING_SET, recording));
            assertThatNoException().isThrownBy(() -> ProductionConfigurationValidator
                    .validateNativeSdkEndpointChannels(NOTHING_SET,
                            onlySets(ProductionConfigurationValidator.SDK_IGNORE_ENDPOINTS_PROPERTY,
                                    "true"),
                            recording));

            assertThat(readerCalls)
                    .as("the file must not even be read once the SDK has been told to ignore it")
                    .isEmpty();
        }

        @Test
        @DisplayName("every arm of the chain is reported in one refusal rather than one arm per attempt")
        void everyArmIsReportedTogether() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateNativeSdkEndpointChannels(
                                    name -> FOREIGN_ENDPOINT,
                                    name -> ProductionConfigurationValidator
                                            .SDK_IGNORE_ENDPOINTS_PROPERTY.equals(name)
                                            ? null : FOREIGN_ENDPOINT,
                                    (location, profile) -> List.of("endpoint_url")))
                    .satisfies(refusal -> {
                        final String message = refusal.getMessage();
                        ProductionConfigurationValidator.FORBIDDEN_SDK_ENVIRONMENT_VARIABLES
                                .forEach(variable -> assertThat(message).contains(variable));
                        ProductionConfigurationValidator.FORBIDDEN_SDK_SYSTEM_PROPERTIES
                                .forEach(property -> assertThat(message).contains(property));
                        assertThat(message).contains("endpoint_url");
                    });
        }

        @Test
        @DisplayName("the refusal never repeats the configured destination, so a diagnostic cannot "
                + "disclose the host a deployment was being redirected to")
        void theRefusalNeverRepeatsTheValue() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateNativeSdkEndpointChannels(
                                    onlySets("AWS_ENDPOINT_URL"), NOTHING_SET, NO_FILE))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .doesNotContain(FOREIGN_ENDPOINT)
                            .doesNotContain("attacker.internal"));
        }

        @ParameterizedTest(name = "{0} selects a variant of the region's endpoint and is accepted")
        @ValueSource(strings = {
            "AWS_USE_FIPS_ENDPOINT", "AWS_USE_DUALSTACK_ENDPOINT",
            "aws.useFipsEndpoint", "aws.useDualstackEndpoint"})
        @DisplayName("the regional-variant selectors are NOT refused, because refusing them would "
                + "reject a legitimate posture while closing nothing")
        void theRegionalVariantSelectorsAreAccepted(final String setting) {
            assertThatNoException().isThrownBy(() -> ProductionConfigurationValidator
                    .validateNativeSdkEndpointChannels(
                            onlySets(setting, "true"), onlySets(setting, "true"), NO_FILE));
        }

        @Test
        @DisplayName("an empty variable redirects nothing and is therefore not a fault")
        void anEmptyVariableIsNotARedirection() {
            assertThatNoException().isThrownBy(() -> ProductionConfigurationValidator
                    .validateNativeSdkEndpointChannels(
                            onlySets("AWS_ENDPOINT_URL", ""), onlySets("aws.endpointUrl", "   "),
                            NO_FILE));
        }

        @Test
        @DisplayName("a service this module operates no client for is not this deployment's business "
                + "to refuse, so only the three used identifiers are consulted")
        void onlyTheOperatedServicesAreConsulted() {
            assertThat(ProductionConfigurationValidator.SDK_SERVICE_IDENTIFIERS)
                    .containsExactly("s3", "sqs", "sns");
        }

        @Test
        @DisplayName("the two channel lists cover the global setting and one entry per operated "
                + "client, so a fourth client cannot be added without revisiting them")
        void theChannelListsAreExactlyTheGlobalPlusThreeClients() {
            assertThat(ProductionConfigurationValidator.FORBIDDEN_SDK_ENVIRONMENT_VARIABLES)
                    .containsExactly("AWS_ENDPOINT_URL", "AWS_ENDPOINT_URL_S3",
                            "AWS_ENDPOINT_URL_SQS", "AWS_ENDPOINT_URL_SNS");
            assertThat(ProductionConfigurationValidator.FORBIDDEN_SDK_SYSTEM_PROPERTIES)
                    .containsExactly("aws.endpointUrl", "aws.endpointUrlS3", "aws.endpointUrlSqs",
                            "aws.endpointUrlSns");
        }

        @ParameterizedTest(name = "a null {0} reader is refused by name")
        @ValueSource(strings = {"environmentReader", "systemPropertyReader", "sharedConfiguration"})
        @DisplayName("each of the guard's three readers is refused by name rather than dereferenced")
        void eachNullReaderIsRefusedByName(final String reader) {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ProductionConfigurationValidator
                            .validateNativeSdkEndpointChannels(
                                    "environmentReader".equals(reader) ? null : NOTHING_SET,
                                    "systemPropertyReader".equals(reader) ? null : NOTHING_SET,
                                    "sharedConfiguration".equals(reader) ? null : NO_FILE))
                    .withMessageContaining(reader);
        }

        @Test
        @DisplayName("a posture with none of the channels present is accepted, so every refusal above "
                + "is attributable to the one channel it set")
        void aCleanPostureIsAccepted() {
            assertThatNoException().isThrownBy(() -> ProductionConfigurationValidator
                    .validateNativeSdkEndpointChannels(NOTHING_SET, NOTHING_SET, NO_FILE));
        }

        /**
         * Builds a reader that answers the foreign endpoint for exactly one name and nothing otherwise.
         *
         * @param name the only name that resolves
         * @return a reader over that single name
         */
        private static UnaryOperator<String> onlySets(final String name) {
            return onlySets(name, FOREIGN_ENDPOINT);
        }

        /**
         * Builds a reader that answers one value for exactly one name and nothing otherwise.
         *
         * @param name  the only name that resolves
         * @param value the value it resolves to
         * @return a reader over that single name
         */
        private static UnaryOperator<String> onlySets(final String name, final String value) {
            return candidate -> name.equals(candidate) ? value : null;
        }
    }
}
