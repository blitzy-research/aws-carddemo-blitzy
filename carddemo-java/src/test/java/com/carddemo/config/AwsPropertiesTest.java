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

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Asserts that the cloud resource settings bind the key paths the shipped documents publish, resolve
 * them to the resource names the rest of the stack provisions, and refuse anything a submission could
 * not survive.
 *
 * <p>Two classes of assertion carry the weight here, and neither is a restatement of the type.</p>
 *
 * <p>The first resolves the <strong>shipped documents themselves</strong> rather than a set of values
 * invented by this test. The bucket, queue, group id and topic are cross-file contracts: each occurs
 * byte-identically in the shared baseline, the profile overlays, the container composition and the
 * emulator bootstrap script, and a disagreement between any two of them produces a stack that starts
 * cleanly and fails on the first publish with nothing pointing at the cause. A test that supplied its
 * own values would pass while the artefact bound a key no document declares, which is exactly the
 * failure this class exists to make impossible.</p>
 *
 * <p>The second is <strong>negative</strong>. A queue name without the first-in-first-out suffix is
 * refused outright by the queue service, so binding one is a deployment that starts and cannot submit;
 * a blank resource name is the same failure wearing a different mask. Each of those must stop start-up
 * here, and the assertions below require the failure rather than merely tolerating it.</p>
 *
 * <p>Nothing in this class is a credential. Every value below is a published resource identifier that
 * already appears in six files, which is also why the settings type keeps the generated description
 * instead of redacting one.</p>
 */
@DisplayName("Cloud resource settings: the shipped key paths bind, and nothing a submission could not "
        + "survive is accepted")
class AwsPropertiesTest {

    /** Configuration prefix under test, taken from the type rather than restated. */
    private static final String PREFIX = AwsProperties.PREFIX;

    /** Key path of the staging bucket, taken from the type. */
    private static final String KEY_BUCKET = AwsProperties.S3.BUCKET_PROPERTY;

    /** Key path prefixing the object-store key-prefix group, taken from the type. */
    private static final String KEY_PREFIX_GROUP = AwsProperties.S3.KEY_PREFIX_PROPERTY_GROUP;

    /** Key path of the inbound key prefix. */
    private static final String KEY_INBOUND = KEY_PREFIX_GROUP + ".inbound";

    /** Key path of the statement key prefix. */
    private static final String KEY_STATEMENTS = KEY_PREFIX_GROUP + ".statements";

    /** Key path of the markup-statement key prefix, hyphenated as the document writes it. */
    private static final String KEY_STATEMENTS_HTML = KEY_PREFIX_GROUP + ".statements-html";

    /** Key path of the report key prefix. */
    private static final String KEY_REPORTS = KEY_PREFIX_GROUP + ".reports";

    /** Key path of the rejected-record key prefix. */
    private static final String KEY_REJECTS = KEY_PREFIX_GROUP + ".rejects";

    /** Key path of the backup key prefix. */
    private static final String KEY_BACKUPS = KEY_PREFIX_GROUP + ".backups";

    /** Key path of the job-submission queue, taken from the type. */
    private static final String KEY_QUEUE = AwsProperties.Sqs.JOB_SUBMISSION_QUEUE_PROPERTY;

    /** Key path of the message group, taken from the type. */
    private static final String KEY_MESSAGE_GROUP = AwsProperties.Sqs.MESSAGE_GROUP_ID_PROPERTY;

    /** Key path of the notification topic, taken from the type. */
    private static final String KEY_TOPIC = AwsProperties.Sns.JOB_NOTIFICATION_TOPIC_PROPERTY;

    /** Key that activates a profile, so an overlay is resolved rather than assumed. */
    private static final String KEY_ACTIVE_PROFILES = "spring.profiles.active";

    /** Environment variable a production deployment supplies the queue through, with no fallback. */
    private static final String QUEUE_VARIABLE = "CARDDEMO_SQS_QUEUE";

    /** The staging bucket every document names. */
    private static final String BUCKET = "carddemo-batch-staging";

    /** The submission queue every document names, uniform with the other three resource names. */
    private static final String QUEUE = "carddemo-jobs.fifo";

    /** The single message group every document names. */
    private static final String MESSAGE_GROUP = "carddemo-job-submission";

    /** The notification topic every document names. */
    private static final String TOPIC = "carddemo-job-notifications";

    /**
     * Every key this type binds, at the values the shared baseline declares.
     *
     * <p>Used by the tests that must control the environment exactly - dropping one key, or blanking
     * one - which the shipped documents cannot express, since they declare all of them.</p>
     */
    private static final Map<String, String> COMPLETE = Map.ofEntries(
            Map.entry(KEY_BUCKET, BUCKET),
            Map.entry(KEY_INBOUND, "inbound/"),
            Map.entry(KEY_STATEMENTS, "statements/"),
            Map.entry(KEY_STATEMENTS_HTML, "statements-html/"),
            Map.entry(KEY_REPORTS, "reports/"),
            Map.entry(KEY_REJECTS, "rejects/"),
            Map.entry(KEY_BACKUPS, "backups/"),
            Map.entry(KEY_QUEUE, QUEUE),
            Map.entry(KEY_MESSAGE_GROUP, MESSAGE_GROUP),
            Map.entry(KEY_TOPIC, TOPIC));

    /**
     * Runner that registers the settings record and nothing else.
     *
     * <p>It enables the properties through the same annotation an owning configuration class would,
     * rather than constructing the record directly, because what is under test is the <em>binding</em> -
     * validation included - and a directly constructed record bypasses the validator entirely. Nothing
     * that consumes the settings is present, so a failure observed here is a binding or validation
     * failure and can be nothing else.</p>
     */
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(BindingHarness.class);

    /**
     * Registers the settings record for binding, and nothing else.
     *
     * <p>The settings type carries no stereotype and nothing scans for it, so this harness stands in
     * for the single owning configuration class that enables it in the application.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AwsProperties.class)
    static class BindingHarness {
    }

    @Nested
    @DisplayName("The shipped configuration documents")
    class ShippedDocuments {

        @ParameterizedTest(name = "profile = {0}")
        @ValueSource(strings = {"default", "local", "test"})
        @DisplayName("resolve to one set of resource names, so no overlay can drift from the baseline")
        void resolveToOneSetOfResourceNames(final String profile) {
            AwsPropertiesTest.this.shipped(profile).run(context -> {
                assertThat(context)
                        .as("the shipped documents must satisfy this type's own constraints")
                        .hasNotFailed();

                final AwsProperties bound = context.getBean(AwsProperties.class);

                assertThat(bound.s3().bucket())
                        .as("the bucket is a cross-file contract; the emulator bootstrap script and "
                                + "the container composition provision this exact name")
                        .isEqualTo(BUCKET);
                assertThat(bound.sqs().jobSubmissionQueue())
                        .as("the queue carries the name the plan mandates plus the suffix the "
                                + "queue service demands, uniform with the other three resource "
                                + "names; the legacy queue name is carried by the operator-visible "
                                + "failure message instead, which is the contract compared byte for "
                                + "byte (docs/decision-log.md DL-092)")
                        .isEqualTo(QUEUE);
                assertThat(bound.sqs().messageGroupId())
                        .as("one stable group id is what preserves the order cards were appended in")
                        .isEqualTo(MESSAGE_GROUP);
                assertThat(bound.sns().jobNotificationTopic()).isEqualTo(TOPIC);
            });
        }

        @ParameterizedTest(name = "profile = {0}")
        @ValueSource(strings = {"default", "local", "test"})
        @DisplayName("resolve one key prefix per output family, none of them shared, so no family can "
                + "overwrite another")
        void resolveOneKeyPrefixPerOutputFamily(final String profile) {
            AwsPropertiesTest.this.shipped(profile).run(context -> {
                final AwsProperties.S3.KeyPrefixes prefixes =
                        context.getBean(AwsProperties.class).s3().prefix();

                assertThat(prefixes.inbound()).isEqualTo("inbound/");
                assertThat(prefixes.statements()).isEqualTo("statements/");
                assertThat(prefixes.statementsHtml())
                        .as("the hyphenated key statements-html must reach the camel-cased component")
                        .isEqualTo("statements-html/");
                assertThat(prefixes.reports()).isEqualTo("reports/");
                assertThat(prefixes.rejects()).isEqualTo("rejects/");
                assertThat(prefixes.backups()).isEqualTo("backups/");

                assertThat(new String[] {prefixes.inbound(), prefixes.statements(),
                        prefixes.statementsHtml(), prefixes.reports(), prefixes.rejects(),
                        prefixes.backups()})
                        .as("two families sharing a prefix would let one overwrite the other")
                        .doesNotHaveDuplicates();
            });
        }

        @Test
        @DisplayName("let production supply the queue from the environment, since production defaults "
                + "none")
        void letProductionSupplyTheQueueFromTheEnvironment() {
            AwsPropertiesTest.this.shipped("prod", QUEUE_VARIABLE + "=" + QUEUE).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(AwsProperties.class).sqs().jobSubmissionQueue())
                        .as("production binds the queue the deployment names and nothing else")
                        .isEqualTo(QUEUE);
            });
        }

        @Test
        @DisplayName("stop a production start-up whose queue variable was never set, rather than "
                + "publishing to the text of a placeholder")
        void stopAProductionStartUpWhoseQueueVariableWasNeverSet() {
            AwsPropertiesTest.this.shipped("prod").run(context -> assertThat(context)
                    .as("the binder resolves an unset variable leniently, as its own reference text, "
                            + "which carries no suffix and must therefore be refused here too")
                    .hasFailed());
        }
    }

    @Nested
    @DisplayName("A complete configuration")
    class CompleteConfiguration {

        @Test
        @DisplayName("binds every component, each group under the key path the documents publish")
        void bindsEveryComponent() {
            AwsPropertiesTest.this.runner.withPropertyValues(completeConfiguration()).run(context -> {
                final AwsProperties bound = context.getBean(AwsProperties.class);

                assertThat(bound.s3()).isNotNull();
                assertThat(bound.s3().bucket()).isEqualTo(BUCKET);
                assertThat(bound.s3().prefix()).isNotNull();
                assertThat(bound.sqs()).isNotNull();
                assertThat(bound.sqs().jobSubmissionQueue()).isEqualTo(QUEUE);
                assertThat(bound.sqs().messageGroupId()).isEqualTo(MESSAGE_GROUP);
                assertThat(bound.sns()).isNotNull();
                assertThat(bound.sns().jobNotificationTopic()).isEqualTo(TOPIC);
            });
        }

        @Test
        @DisplayName("describes itself with the resource names it bound, because it holds no credential "
                + "to hide")
        void describesItselfWithTheResourceNamesItBound() {
            AwsPropertiesTest.this.runner.withPropertyValues(completeConfiguration()).run(context -> {
                final String description = context.getBean(AwsProperties.class).toString();

                assertThat(description)
                        .as("a description that hid a published identifier would hide nothing and "
                                + "cost a diagnostic")
                        .contains(BUCKET, QUEUE, MESSAGE_GROUP, TOPIC);
            });
        }
    }

    @Nested
    @DisplayName("The mandatory first-in-first-out suffix")
    class MandatoryFifoSuffix {

        @ParameterizedTest(name = "queue = {0}")
        @ValueSource(strings = {"JOBS", "carddemo-jobs", "JOBS.FIFO", "JOBS.fifo.bak", "fifo"})
        @DisplayName("stops start-up for a name the queue service would refuse, rather than letting a "
                + "submission discover it")
        void stopsStartUpForANameTheQueueServiceWouldRefuse(final String queueName) {
            AwsPropertiesTest.this.runner
                    .withPropertyValues(completeConfigurationWith(KEY_QUEUE, queueName))
                    .run(context -> {
                        assertThat(context)
                                .as("a well-formed name that names nothing is the input that makes a "
                                        + "failed submission look like a successful one")
                                .hasFailed();
                        assertThat(context)
                                .getFailure()
                                .as("the refusal must come from binding these very settings")
                                .isInstanceOf(ConfigurationPropertiesBindException.class)
                                .hasMessageContaining(PREFIX);
                    });
        }

        @ParameterizedTest(name = "queue = {0}")
        @ValueSource(strings = {"JOBS.fifo", "carddemo-jobs.fifo", "a.fifo"})
        @DisplayName("accepts any name that carries it, since the suffix is the only condition this "
                + "type imposes on the name")
        void acceptsAnyNameThatCarriesIt(final String queueName) {
            AwsPropertiesTest.this.runner
                    .withPropertyValues(completeConfigurationWith(KEY_QUEUE, queueName))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(AwsProperties.class).sqs().jobSubmissionQueue())
                                .isEqualTo(queueName);
                    });
        }

        @Test
        @DisplayName("names the key and the suffix it requires, and does not echo the value it refused")
        void namesTheKeyAndTheSuffixWithoutEchoingTheValue() {
            final String refused = "not-a-queue-of-this-kind";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new AwsProperties.Sqs(refused, MESSAGE_GROUP))
                    .withMessageContaining(KEY_QUEUE)
                    .withMessageContaining(AwsProperties.Sqs.FIFO_QUEUE_NAME_SUFFIX)
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .as("a diagnostic states its own expectation and does not repeat what it "
                                    + "was given; the operator can read that at the key it names")
                            .doesNotContain(refused));
        }

        @ParameterizedTest(name = "queue = [{0}]")
        @ValueSource(strings = {"", " ", "   ", "\t"})
        @DisplayName("leaves a blank name to the validator, so the report names the missing key rather "
                + "than a suffix nothing was supplied for")
        void leavesABlankNameToTheValidator(final String blank) {
            assertThatNoException()
                    .as("the constructor must not pre-empt the validator's report of a missing value")
                    .isThrownBy(() -> new AwsProperties.Sqs(blank, MESSAGE_GROUP));
        }

        @Test
        @DisplayName("leaves an absent name to the validator for the same reason")
        void leavesAnAbsentNameToTheValidator() {
            final AwsProperties.Sqs constructed = new AwsProperties.Sqs(null, MESSAGE_GROUP);

            assertThat(constructed.jobSubmissionQueue()).isNull();
        }
    }

    @Nested
    @DisplayName("A missing or blank resource name")
    class MissingOrBlankResourceName {

        @ParameterizedTest(name = "omitted = {0}")
        @ValueSource(strings = {KEY_BUCKET, KEY_INBOUND, KEY_STATEMENTS, KEY_STATEMENTS_HTML,
            KEY_REPORTS, KEY_REJECTS, KEY_BACKUPS, KEY_QUEUE, KEY_MESSAGE_GROUP, KEY_TOPIC})
        @DisplayName("stops start-up when the key is absent, so a deployment is refused rather than "
                + "defaulted")
        void stopsStartUpWhenTheKeyIsAbsent(final String omittedKey) {
            AwsPropertiesTest.this.runner
                    .withPropertyValues(completeConfigurationWithout(omittedKey))
                    .run(context -> assertThat(context)
                            .as("%s is required, and nothing here may supply it silently", omittedKey)
                            .hasFailed());
        }

        @ParameterizedTest(name = "blanked = {0}")
        @ValueSource(strings = {KEY_BUCKET, KEY_INBOUND, KEY_STATEMENTS, KEY_STATEMENTS_HTML,
            KEY_REPORTS, KEY_REJECTS, KEY_BACKUPS, KEY_QUEUE, KEY_MESSAGE_GROUP, KEY_TOPIC})
        @DisplayName("stops start-up when the key is present but blank, which is the same absence "
                + "wearing a different mask")
        void stopsStartUpWhenTheKeyIsBlank(final String blankedKey) {
            AwsPropertiesTest.this.runner
                    .withPropertyValues(completeConfigurationWith(blankedKey, ""))
                    .run(context -> assertThat(context)
                            .as("a blank %s is not a configured one", blankedKey)
                            .hasFailed());
        }

        @Test
        @DisplayName("stops start-up when a whole group is absent, reporting the group rather than "
                + "failing on it later")
        void stopsStartUpWhenAWholeGroupIsAbsent() {
            AwsPropertiesTest.this.runner.run(context -> {
                assertThat(context)
                        .as("an environment declaring none of these keys must be refused")
                        .hasFailed();
                assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                        .as("the report must name this namespace, not a null dereference downstream")
                        .hasMessageContaining(PREFIX);
            });
        }
    }

    @Nested
    @DisplayName("The published key paths")
    class PublishedKeyPaths {

        @Test
        @DisplayName("are the ones the publisher and the production guard already name, so the three "
                + "cannot drift apart")
        void areTheOnesTheRestOfTheModuleAlreadyNames() {
            assertThat(PREFIX).isEqualTo("carddemo.aws");
            assertThat(KEY_BUCKET).isEqualTo("carddemo.aws.s3.bucket");
            assertThat(KEY_PREFIX_GROUP).isEqualTo("carddemo.aws.s3.prefix");
            assertThat(KEY_QUEUE)
                    .as("the publisher injects this literal and the production guard requires it")
                    .isEqualTo("carddemo.aws.sqs.job-submission-queue");
            assertThat(KEY_MESSAGE_GROUP).isEqualTo("carddemo.aws.sqs.message-group-id");
            assertThat(KEY_TOPIC).isEqualTo("carddemo.aws.sns.job-notification-topic");
        }

        @Test
        @DisplayName("carry the suffix the queue service requires, stated once for every caller that "
                + "needs it")
        void carryTheSuffixTheQueueServiceRequires() {
            assertThat(AwsProperties.Sqs.FIFO_QUEUE_NAME_SUFFIX).isEqualTo(".fifo");
            assertThat(QUEUE)
                    .as("the shipped queue name must satisfy the constraint this type enforces")
                    .endsWith(AwsProperties.Sqs.FIFO_QUEUE_NAME_SUFFIX);
        }
    }

    /**
     * Builds a runner that resolves the shipped documents rather than values invented here.
     *
     * <p>The configuration-data initialiser performs the framework's own document discovery, profile
     * activation and overlay merging, so what these tests read is what a started application would
     * read. The placeholder auto-configuration is registered because it is what makes value resolution
     * behave as it does in the deployed application.</p>
     *
     * @param profile  profile to activate, or {@code default} for a profile-less resolution
     * @param supplied environment values to make resolvable, as {@code NAME=value} entries
     * @return a runner that has not been started
     */
    private ApplicationContextRunner shipped(final String profile, final String... supplied) {
        ApplicationContextRunner configured = this.runner
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withPropertyValues(supplied);
        return "default".equals(profile)
                ? configured
                : configured.withPropertyValues(KEY_ACTIVE_PROFILES + "=" + profile);
    }

    /**
     * Returns every key this type binds, at the values the shared baseline declares.
     *
     * @return {@code key=value} entries, one per bound key
     */
    private static String[] completeConfiguration() {
        return COMPLETE.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    /**
     * Returns the complete configuration with one key omitted entirely.
     *
     * @param omittedKey the key to leave out
     * @return {@code key=value} entries for every key except {@code omittedKey}
     */
    private static String[] completeConfigurationWithout(final String omittedKey) {
        return COMPLETE.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(omittedKey))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    /**
     * Returns the complete configuration with one key carrying a different value.
     *
     * @param replacedKey the key to re-value
     * @param value       the value to give it, which may be blank
     * @return {@code key=value} entries, one per bound key
     */
    private static String[] completeConfigurationWith(final String replacedKey, final String value) {
        return COMPLETE.entrySet().stream()
                .map(entry -> entry.getKey() + "="
                        + (entry.getKey().equals(replacedKey) ? value : entry.getValue()))
                .toArray(String[]::new);
    }
}
