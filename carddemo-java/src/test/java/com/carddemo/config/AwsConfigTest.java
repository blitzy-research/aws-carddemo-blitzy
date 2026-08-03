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
import static org.mockito.Mockito.mock;

import com.carddemo.service.JobSubmissionService;
import io.awspring.cloud.autoconfigure.sqs.SqsAsyncClientCustomizer;
import io.awspring.cloud.sqs.operations.SqsOperations;
import java.util.Arrays;
import java.util.stream.Stream;
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

    /** The staging bucket the configuration documents declare. */
    private static final String BUCKET = "carddemo-batch-staging";

    /** The job-submission queue the configuration documents declare. */
    private static final String QUEUE = "carddemo-jobs.fifo";

    /** The single message group the configuration documents declare. */
    private static final String MESSAGE_GROUP = "carddemo-job-submission";

    /** The notification topic the configuration documents declare. */
    private static final String TOPIC = "carddemo-job-notifications";

    /** The region the configuration documents declare. */
    private static final String REGION = "us-east-1";

    /**
     * Every key path the registered settings require, at the values the shared baseline declares.
     *
     * <p>Each entry names its key path through the settings type's own published constant rather than
     * through a literal, so a key path that moved would fail to compile here instead of leaving this
     * runner quietly supplying a path nothing binds.</p>
     */
    private static final String[] REQUIRED_SETTINGS = {
        AwsProperties.REGION_PROPERTY + "=" + REGION,
        AwsProperties.S3.BATCH_STAGING_BUCKET_PROPERTY + "=" + BUCKET,
        AwsProperties.Sqs.JOB_QUEUE_PROPERTY + "=" + QUEUE,
        AwsProperties.Sqs.MESSAGE_GROUP_ID_PROPERTY + "=" + MESSAGE_GROUP,
        AwsProperties.Sns.JOB_NOTIFICATION_TOPIC_PROPERTY + "=" + TOPIC,
    };

    /**
     * Runner over the class under test, carrying the settings its registration now binds.
     *
     * <p>The values are supplied because this class registers the settings type, so an empty
     * environment is no longer a neutral one: it is a deployment missing every resource name, and the
     * context is expected to refuse it. That refusal is itself asserted below; here the complete set
     * is supplied so the customizer assertions observe a context that started for the right reason.</p>
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AwsConfig.class)
            .withPropertyValues(REQUIRED_SETTINGS);

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
        @DisplayName("registers the settings type itself, so the bound settings have a production "
                + "owner rather than only a test one")
        void ownsTheRegistrationOfTheSettingsType() {
            runner.run(context -> assertThat(context)
                    .as("without a production registration the settings type binds only where a "
                            + "test enables it, so a deployed application would resolve none of the "
                            + "bucket, queue, group or topic names it publishes against")
                    .hasNotFailed()
                    .hasSingleBean(AwsProperties.class));
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

    @Nested
    @DisplayName("The bridge settings this class owns")
    class BridgeSettingsRegistration {

        @Test
        @DisplayName("are registered here, exactly once, so their binding is a live start-up gate "
                + "rather than an unreachable declaration")
        void areRegisteredHereExactlyOnce() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context)
                        .as("the settings type carries no stereotype and nothing scans for it, so "
                                + "unregistered it binds nowhere and its constraints never run")
                        .hasSingleBean(AwsProperties.class);

                final AwsProperties bound = context.getBean(AwsProperties.class);
                assertThat(bound.s3().batchStagingBucket()).as("bound staging bucket").isEqualTo(BUCKET);
                assertThat(bound.sqs().jobQueue()).as("bound submission queue")
                        .isEqualTo(QUEUE);
                assertThat(bound.sqs().messageGroupId()).as("bound message group")
                        .isEqualTo(MESSAGE_GROUP);
                assertThat(bound.sns().jobNotificationTopic()).as("bound notification topic")
                        .isEqualTo(TOPIC);
            });
        }

        @Test
        @DisplayName("refuse a deployment that supplies no resource name at all, which is what "
                + "registration buys and what an unregistered declaration could not do")
        void refuseADeploymentThatSuppliesNoResourceName() {
            new ApplicationContextRunner().withUserConfiguration(AwsConfig.class)
                    .run(context -> assertThat(context)
                            .as("a missing queue, bucket, group or topic must stop start-up rather "
                                    + "than surface at the first publish")
                            .hasFailed());
        }

        @Test
        @DisplayName("refuse a queue name that is not a first-in-first-out name, which is the single "
                + "easiest misconfiguration in this namespace")
        void refuseAQueueNameThatIsNotFirstInFirstOut() {
            runner.withPropertyValues(
                    AwsProperties.Sqs.JOB_QUEUE_PROPERTY + "=carddemo-jobs")
                    .run(context -> assertThat(context)
                            .as("append ordering is only preserved by a first-in-first-out queue, so "
                                    + "a plain name must be refused before a card is published")
                            .hasFailed());
        }

        @Test
        @DisplayName("refuse a blanked resource name, so a deployment cannot empty a value the "
                + "baseline defaults")
        void refuseABlankedResourceName() {
            runner.withPropertyValues(AwsProperties.S3.BATCH_STAGING_BUCKET_PROPERTY + "=")
                    .run(context -> assertThat(context)
                            .as("a blank bucket would produce requests against no bucket")
                            .hasFailed());
        }

        @Test
        @DisplayName("are the same key paths the publisher binds: the publisher starts against an "
                + "environment carrying only the paths these settings own")
        void areTheSameKeyPathsThePublisherBinds() {
            // The publisher may not depend on this configuration layer, so it binds the two queue key
            // paths itself. That leaves one thing worth proving: that the paths it binds are the paths
            // these settings own. Supplying ONLY the settings' own published paths and then requiring
            // the publisher to be constructible from that environment proves it - a publisher reading
            // any other path would receive an unresolved placeholder and refuse it at construction.
            runner.withUserConfiguration(JobSubmissionService.class)
                    .withBean(SqsOperations.class, () -> mock(SqsOperations.class))
                    .run(context -> {
                        assertThat(context)
                                .as("the publisher must be satisfied by the very key paths the "
                                        + "settings type publishes, with nothing else supplied")
                                .hasNotFailed();
                        assertThat(context).hasSingleBean(JobSubmissionService.class);
                        assertThat(context).hasSingleBean(AwsProperties.class);
                    });
        }

        @Test
        @DisplayName("and no other name will do: renaming either queue path leaves the publisher "
                + "unsatisfied, so the previous assertion is not vacuous")
        void andNoOtherNameWillDo() {
            for (final String withheld : new String[] {
                AwsProperties.Sqs.JOB_QUEUE_PROPERTY,
                AwsProperties.Sqs.MESSAGE_GROUP_ID_PROPERTY, }) {
                new ApplicationContextRunner().withUserConfiguration(JobSubmissionService.class)
                        .withBean(SqsOperations.class, () -> mock(SqsOperations.class))
                        .withPropertyValues(settingsWithPathRenamed(withheld))
                        .run(context -> assertThat(context)
                                .as("supplying " + withheld + " under any other path must leave the "
                                        + "publisher unsatisfied")
                                .hasFailed());
            }
        }

        /**
         * Returns the required settings with one key path moved to a near-miss name.
         *
         * <p>The value is still present and still correct; only the path it is published under
         * changes, which is exactly the drift this pair of tests is built to detect.</p>
         *
         * @param renamedPath the key path to publish under a different name
         * @return {@code key=value} entries, one per required setting
         */
        private String[] settingsWithPathRenamed(final String renamedPath) {
            return Stream.concat(
                    Arrays.stream(REQUIRED_SETTINGS)
                            .filter(entry -> !entry.startsWith(renamedPath + "=")),
                    Stream.of(renamedPath + "-renamed=" + QUEUE))
                    .toArray(String[]::new);
        }
    }
}
