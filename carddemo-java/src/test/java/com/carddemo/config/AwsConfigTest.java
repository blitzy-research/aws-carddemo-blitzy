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

import io.awspring.cloud.autoconfigure.sqs.SqsAsyncClientCustomizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder;

/**
 * Verifies that the job-submission bridge publishes with the legacy write's attempt count.
 *
 * <h2>What is being protected</h2>
 *
 * <p>The legacy reporting program writes one card with one transient-data-queue write and inspects
 * that write's response immediately. There is no retry around it. The messaging client this module
 * publishes through does retry by default, treating a refused transport as transient, so without the
 * configuration under test one logical card write could become several attempts on the wire - turning
 * a failure the legacy program reports into a success it never had, and delivering a card after the
 * publisher had already been told the write failed.
 *
 * <h2>Why the preservation test matters as much as the attempt-count test</h2>
 *
 * <p>The obvious way to set a retry strategy on a client builder is the convenience form that takes a
 * consumer of a configuration builder. It reads as though it amends the existing configuration and it
 * does not: it constructs a fresh one, applies the consumer to that, and then replaces the builder's
 * configuration wholesale. Because the messaging auto-configuration installs its own override
 * configuration before customizers run, the convenience form would discard what the library had
 * already set while still passing an attempt-count assertion.
 *
 * <p>The second test below therefore seeds an advanced option, applies the customizer, and requires
 * both that the attempt count became one and that the seeded option survived. It fails if the
 * implementation is ever simplified to the replacing form, which is the whole reason it exists.
 */
@DisplayName("AWS configuration: one card write is one attempt, as the legacy queue write was")
class AwsConfigTest {

    /**
     * The attempt count the legacy write performs: the write itself, and nothing after it.
     */
    private static final int LEGACY_ATTEMPTS_PER_WRITE = 1;

    /** A recognisable advanced option, standing in for whatever the library sets for itself. */
    private static final String SEEDED_USER_AGENT = "carddemo-preserved-user-agent";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(AwsConfig.class);

    @Nested
    @DisplayName("The publish attempt count")
    class PublishAttemptCount {

        @Test
        @DisplayName("is reduced to the single attempt the legacy queue write made")
        void isReducedToASingleAttempt() {
            SqsAsyncClientBuilder builder = SqsAsyncClient.builder();

            new AwsConfig().singleAttemptSqsClientCustomizer().customize(builder);

            assertThat(builder.overrideConfiguration().retryStrategy())
                    .as("an unset strategy leaves the client on its own default, which retries")
                    .isPresent()
                    .get()
                    .extracting(strategy -> strategy.maxAttempts())
                    .as("more than one attempt would let a refused publish succeed on a later try, "
                            + "which the legacy write had no way of doing")
                    .isEqualTo(LEGACY_ATTEMPTS_PER_WRITE);
        }

        @Test
        @DisplayName("is set by extending the configuration already applied, not by replacing it")
        void isSetByExtendingTheExistingConfiguration() {
            SqsAsyncClientBuilder builder = SqsAsyncClient.builder()
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .putAdvancedOption(SdkAdvancedClientOption.USER_AGENT_PREFIX,
                                    SEEDED_USER_AGENT)
                            .build());

            new AwsConfig().singleAttemptSqsClientCustomizer().customize(builder);

            ClientOverrideConfiguration configuration = builder.overrideConfiguration();
            assertThat(configuration.advancedOption(SdkAdvancedClientOption.USER_AGENT_PREFIX))
                    .as("the messaging library installs its own override configuration before "
                            + "customizers run, so a customizer that built a fresh configuration "
                            + "would silently discard it while still passing the attempt assertion")
                    .contains(SEEDED_USER_AGENT);
            assertThat(configuration.retryStrategy())
                    .isPresent()
                    .get()
                    .extracting(strategy -> strategy.maxAttempts())
                    .isEqualTo(LEGACY_ATTEMPTS_PER_WRITE);
        }

        @Test
        @DisplayName("is applied idempotently, so ordering among customizers cannot change it")
        void isAppliedIdempotently() {
            SqsAsyncClientBuilder builder = SqsAsyncClient.builder();
            SqsAsyncClientCustomizer customizer = new AwsConfig().singleAttemptSqsClientCustomizer();

            customizer.customize(builder);
            customizer.customize(builder);

            assertThat(builder.overrideConfiguration().retryStrategy())
                    .isPresent()
                    .get()
                    .extracting(strategy -> strategy.maxAttempts())
                    .isEqualTo(LEGACY_ATTEMPTS_PER_WRITE);
        }
    }

    @Nested
    @DisplayName("Container wiring")
    class ContainerWiring {

        @Test
        @DisplayName("contributes the customizer under the queue-client customizer type, which is how "
                + "the auto-configuration finds it")
        void contributesTheCustomizerUnderTheTypeTheAutoConfigurationCollects() {
            runner.run(context -> assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(SqsAsyncClientCustomizer.class));
        }

        @Test
        @DisplayName("builds no client, resolves no region and reads no credential, leaving the "
                + "client itself auto-configured")
        void buildsNoClientOfItsOwn() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansOfType(SqsAsyncClient.class))
                        .as("this class customizes the auto-configured client; publishing one of its "
                                + "own would take over region, endpoint and credential resolution")
                        .isEmpty();
                assertThat(context.getBeanDefinitionNames())
                        .as("exactly one contribution, so nothing else has crept into this class")
                        .containsOnlyOnce("singleAttemptSqsClientCustomizer");
            });
        }
    }
}
