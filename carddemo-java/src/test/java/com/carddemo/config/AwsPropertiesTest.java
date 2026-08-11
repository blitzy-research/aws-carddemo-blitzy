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

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Asserts that the cloud resource settings bind the six key paths the migration plan states, resolve
 * them to the resource names the rest of the stack provisions, and refuse anything a submission could
 * not survive.
 *
 * <h2>The six key paths this class owns</h2>
 *
 * <p>{@code carddemo.aws.region}, {@code carddemo.aws.endpoint-override},
 * {@code carddemo.aws.s3.batch-staging-bucket}, {@code carddemo.aws.sqs.job-queue},
 * {@code carddemo.aws.sqs.message-group-id} and {@code carddemo.aws.sns.job-notification-topic}. They
 * are written out below as literals rather than read from {@link AwsProperties}, and the type's own
 * published constants are then asserted <em>against</em> those literals. The direction matters: a test
 * that took its expectations from the type under test would agree with any key path the type happened
 * to bind, which is precisely how {@code s3.bucket} and {@code sqs.job-submission-queue} come to be
 * bound with every binding test passing.</p>
 *
 * <h2>Why the queue name is a contract rather than a preference</h2>
 *
 * <p>{@link AwsProperties.Sqs#jobQueue()} replaces one legacy resource. The CICS resource definition
 * at {@code app/csd/CARDDEMO.CSD} lines 499 to 503 declares a single transient-data queue named
 * {@code JOBS} for submitting jobs from the online region, with {@code RECORDSIZE(80)},
 * {@code RECORDFORMAT(FIXED)}, {@code BLOCKFORMAT(UNBLOCKED)}, {@code DISPOSITION(MOD)},
 * {@code ERROROPTION(IGNORE)}, {@code OPENTIME(INITIAL)} and {@code TYPEFILE(OUTPUT)}. Exactly one
 * program writes to it - the report-request program at {@code app/cbl/CORPT00C.cbl} lines 515 to 535,
 * the only such write in the estate's 19,254 lines of COBOL - so that queue is the whole of the path
 * from an online request to a batch job.</p>
 *
 * <p>{@code DISPOSITION(MOD)} appends, so the order cards were written in is the order they must be
 * read in. A queue service preserves order only on a first-in-first-out queue and only within one
 * message group, and it requires the {@code .fifo} name suffix to treat a queue as first-in-first-out
 * at all. A name without that suffix therefore does not fail loudly: it produces a deployment that
 * starts cleanly, accepts a submission, and delivers a job stream whose cards may be reordered into
 * something that will not parse. That is why {@link AwsProperties.Sqs} refuses such a name at binding
 * and why the refusal is asserted here in both directions - refused without the suffix, accepted with
 * it - together with the single stable message group that keeps the append order.</p>
 *
 * <h2>What this class deliberately does not assert</h2>
 *
 * <p>Client construction - the region and endpoint redirection being applied to each builder, and no
 * cloud resource being created at start-up - belongs to {@code AwsConfigTest}, which owns
 * {@link AwsConfig}. The job-submission card image, its terminating sentinel card and a drained queue
 * belong to the integration and end-to-end suites, which have an emulator. The whole-document
 * placeholder sweep of the production profile belongs to {@code JwtPropertiesTest}. This class asserts
 * the binding contract of one settings type, and reads the shipped documents only for the six paths it
 * owns plus the production facts named in the next paragraph.</p>
 *
 * <p>One divergence is recorded rather than hidden. The instruction for this file names the production
 * region and credential entries as {@code CARDDEMO_AWS_}-prefixed references. The shipped production
 * document declares neither name: it derives the region from an environment reference carrying no
 * fallback, and it declares <strong>no credential entry at all</strong>, because the software
 * development kit's own provider chain resolves credentials and {@link AwsProperties} is forbidden from
 * binding one. Asserting the named literals would either fail against the shipped document or force an
 * edit to a dependency this class does not own, so what is asserted below is the requirement those
 * names exist to express: every production value that a deployment must supply is an environment
 * reference with no fallback default, no credential is declared, and no emulator drift default
 * survives into production.</p>
 *
 * <p>Nothing in this class is a credential. Every value below is a published resource identifier that
 * already appears in the shared baseline, the profile overlays, the container composition and the
 * emulator bootstrap script, which is also why the settings type keeps its generated description
 * instead of redacting one.</p>
 */
@DisplayName("Cloud resource settings: the shipped key paths bind, and nothing a submission could not "
        + "survive is accepted")
class AwsPropertiesTest {

    /**
     * Configuration prefix these settings bind from, written out rather than read from the type.
     *
     * <p>Every literal in this block is an independent statement of the migration plan's contract. The
     * type's own constants are compared against them by {@link PublishedKeyPaths}, so a rename on
     * either side is a failure here rather than a silent agreement between a document and its
     * binder.</p>
     */
    private static final String PREFIX = "carddemo.aws";

    /** Key path the region binds from. */
    private static final String KEY_REGION = "carddemo.aws.region";

    /** Key path the endpoint redirection binds from - the one optional key under this prefix. */
    private static final String KEY_ENDPOINT_OVERRIDE = "carddemo.aws.endpoint-override";

    /** Key path the batch staging bucket binds from. */
    private static final String KEY_BUCKET = "carddemo.aws.s3.batch-staging-bucket";

    /** Key path the job-submission queue binds from. */
    private static final String KEY_QUEUE = "carddemo.aws.sqs.job-queue";

    /** Key path the single message group binds from. */
    private static final String KEY_MESSAGE_GROUP = "carddemo.aws.sqs.message-group-id";

    /** Key path the job-notification topic binds from. */
    private static final String KEY_TOPIC = "carddemo.aws.sns.job-notification-topic";

    /** Suffix a first-in-first-out queue name must carry, written out rather than read from the type. */
    private static final String FIFO_SUFFIX = ".fifo";

    /** Key that activates a profile, so an overlay is resolved rather than assumed. */
    private static final String KEY_ACTIVE_PROFILES = "spring.profiles.active";

    /** Environment variable a production deployment supplies the queue through, with no fallback. */
    private static final String QUEUE_VARIABLE = "CARDDEMO_SQS_QUEUE";

    /** Environment variable a production deployment supplies the region through, with no fallback. */
    private static final String REGION_VARIABLE = "AWS_REGION";

    /** The region every shipped document names. */
    private static final String REGION = "us-east-1";

    /** The emulator address the local and test documents aim every client at. */
    private static final String ENDPOINT_OVERRIDE = "http://localhost:4566";

    /** The staging bucket every shipped document names. */
    private static final String BUCKET = "carddemo-batch-staging";

    /** The submission queue every shipped document names, uniform with the other three resources. */
    private static final String QUEUE = "JOBS.fifo";

    /** The single message group every shipped document names. */
    private static final String MESSAGE_GROUP = "carddemo-job-submission";

    /** The notification topic every shipped document names. */
    private static final String TOPIC = "carddemo-job-notifications";

    /** The shared baseline document, read for its declared entries rather than for its resolved ones. */
    private static final String BASELINE_DOCUMENT = "application.yml";

    /** The local overlay document. */
    private static final String LOCAL_DOCUMENT = "application-local.yml";

    /** The suite overlay document, resolved from the test class path copy. */
    private static final String TEST_DOCUMENT = "application-test.yml";

    /** The production overlay document. */
    private static final String PRODUCTION_DOCUMENT = "application-prod.yml";

    /** Key path the framework's own client credentials would bind from, were any declared. */
    private static final String KEY_CLIENT_ACCESS = "spring.cloud.aws.credentials.access-key";

    /** Key path the framework's own client secret would bind from, were any declared. */
    private static final String KEY_CLIENT_SECRET = "spring.cloud.aws.credentials.secret-key";

    /**
     * The settings annotation, named as text rather than imported.
     *
     * <p>The four annotation lookups in {@link RegistrationHygiene} are performed by name for one
     * reason: the constructor-binding annotation below is deprecated on this framework line, and this
     * build treats a deprecation warning as an error, so importing it to write a class literal would
     * fail the build the assertion exists to protect. Naming all four the same way keeps the group
     * uniform rather than mixing two styles for no reason a reader could infer.</p>
     */
    private static final String CONFIGURATION_PROPERTIES_ANNOTATION =
            "org.springframework.boot.context.properties.ConfigurationProperties";

    /** The stereotype that would register this type a second time. */
    private static final String COMPONENT_ANNOTATION = "org.springframework.stereotype.Component";

    /** The scan that would register this type without an owning configuration class. */
    private static final String PROPERTIES_SCAN_ANNOTATION =
            "org.springframework.boot.context.properties.ConfigurationPropertiesScan";

    /** The deprecated constructor-binding marker a record does not need. */
    private static final String CONSTRUCTOR_BINDING_ANNOTATION =
            "org.springframework.boot.context.properties.bind.ConstructorBinding";

    /** Opening delimiter of an environment reference in a configuration document. */
    private static final String REFERENCE_OPEN = "${";

    /** Closing delimiter of an environment reference in a configuration document. */
    private static final String REFERENCE_CLOSE = "}";

    /** Separator between an environment reference and its fallback default. */
    private static final String FALLBACK_SEPARATOR = ":";

    /**
     * Every key this type binds, at the values the shared baseline declares.
     *
     * <p>Used by the tests that must control the environment exactly - dropping one key, or blanking
     * one - which the shipped documents cannot express, since they declare all of them.</p>
     */
    private static final Map<String, String> COMPLETE = Map.ofEntries(
            Map.entry(KEY_REGION, REGION),
            Map.entry(KEY_ENDPOINT_OVERRIDE, ENDPOINT_OVERRIDE),
            Map.entry(KEY_BUCKET, BUCKET),
            Map.entry(KEY_QUEUE, QUEUE),
            Map.entry(KEY_MESSAGE_GROUP, MESSAGE_GROUP),
            Map.entry(KEY_TOPIC, TOPIC));

    /**
     * The five keys a deployment must supply, which is every bound key except the endpoint override.
     *
     * <p>The override is deliberately absent from this set: production ships without it, so a test that
     * required it would require the one configuration the production profile does not carry.</p>
     */
    private static final List<String> REQUIRED_KEYS =
            List.of(KEY_REGION, KEY_BUCKET, KEY_QUEUE, KEY_MESSAGE_GROUP, KEY_TOPIC);

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
     * The same runner with the framework's validation support registered explicitly.
     *
     * <p>Constraint evaluation on a settings type does not depend on this registration - the binder
     * supplies its own validator when the type carries the validation marker and the constraint API is
     * on the class path - so the presence assertions below would hold without it. It is registered
     * anyway, and used by every constraint test, so that what those tests exercise is the arrangement a
     * started application has rather than a fallback that happens to behave the same way.</p>
     */
    private final ApplicationContextRunner validatingRunner = this.runner
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class));

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
    @DisplayName("The published key paths")
    class PublishedKeyPaths {

        @Test
        @DisplayName("are the six the migration plan states, compared against literals written here "
                + "rather than read from the type, because a document and its binder can drift together")
        void areTheSixTheMigrationPlanStates() {
            assertThat(AwsProperties.PREFIX)
                    .as("the prefix the settings bind under")
                    .isEqualTo(PREFIX);
            assertThat(AwsProperties.REGION_PROPERTY)
                    .as("the production guard requires this key of a deployment, and the framework's "
                            + "own region setting derives from it rather than restating the value")
                    .isEqualTo(KEY_REGION);
            assertThat(AwsProperties.ENDPOINT_OVERRIDE_PROPERTY)
                    .as("the one optional key under this prefix")
                    .isEqualTo(KEY_ENDPOINT_OVERRIDE);
            assertThat(AwsProperties.S3.BATCH_STAGING_BUCKET_PROPERTY)
                    .as("the bucket key, hyphenated in the document and camel-cased in the record")
                    .isEqualTo(KEY_BUCKET);
            assertThat(AwsProperties.Sqs.JOB_QUEUE_PROPERTY)
                    .as("the publisher injects this literal and the production guard requires it")
                    .isEqualTo(KEY_QUEUE);
            assertThat(AwsProperties.Sqs.MESSAGE_GROUP_ID_PROPERTY)
                    .as("the group id that preserves the append order")
                    .isEqualTo(KEY_MESSAGE_GROUP);
            assertThat(AwsProperties.Sns.JOB_NOTIFICATION_TOPIC_PROPERTY)
                    .as("the notification topic key")
                    .isEqualTo(KEY_TOPIC);
        }

        @Test
        @DisplayName("are six and only six, so a seventh key cannot be introduced without this "
                + "assertion being reconsidered")
        void areSixAndOnlySix() {
            assertThat(COMPLETE.keySet())
                    .as("the plan states exactly six key paths under this prefix; the six s3.prefix.* "
                            + "keys an earlier revision added bound nothing and are withdrawn")
                    .hasSize(6)
                    .containsExactlyInAnyOrder(KEY_REGION, KEY_ENDPOINT_OVERRIDE, KEY_BUCKET,
                            KEY_QUEUE, KEY_MESSAGE_GROUP, KEY_TOPIC);
            assertThat(REQUIRED_KEYS)
                    .as("five of the six are required; the endpoint override is the one a production "
                            + "deployment ships without")
                    .hasSize(5)
                    .doesNotContain(KEY_ENDPOINT_OVERRIDE);
        }

        @Test
        @DisplayName("all sit under the one prefix, so nothing this type binds can be reached from "
                + "another namespace")
        void allSitUnderTheOnePrefix() {
            assertThat(COMPLETE.keySet())
                    .allSatisfy(key -> assertThat(key).startsWith(PREFIX + "."));
        }

        @Test
        @DisplayName("carry the suffix the queue service requires, stated once for every caller that "
                + "needs it")
        void carryTheSuffixTheQueueServiceRequires() {
            assertThat(AwsProperties.Sqs.FIFO_QUEUE_NAME_SUFFIX)
                    .as("the queue service refuses to resolve a first-in-first-out queue whose name "
                            + "omits this suffix, so it is a contract rather than a convention")
                    .isEqualTo(FIFO_SUFFIX);
            assertThat(QUEUE)
                    .as("the shipped queue name must satisfy the constraint this type enforces")
                    .endsWith(FIFO_SUFFIX);
        }

        @Test
        @DisplayName("withhold the withdrawn key names, so the contract cannot drift back to them")
        void withholdTheWithdrawnKeyNames() {
            assertThat(COMPLETE.keySet())
                    .as("an earlier revision bound s3.bucket, sqs.job-submission-queue and six "
                            + "s3.prefix.* keys, and reconciled the documents to that set - which is "
                            + "why the plan's key paths are asserted here rather than inferred from a "
                            + "document")
                    .doesNotContain(PREFIX + ".s3.bucket",
                            PREFIX + ".sqs.job-submission-queue",
                            PREFIX + ".sqs.record-length",
                            PREFIX + ".sqs.fail-on-error");
        }
    }

    @Nested
    @DisplayName("A complete configuration")
    class CompleteConfiguration {

        @Test
        @DisplayName("binds every component, each group under the key path the documents publish")
        void bindsEveryComponent() {
            AwsPropertiesTest.this.validatingRunner.withPropertyValues(completeConfiguration())
                    .run(context -> {
                        assertThat(context)
                                .as("a configuration supplying every key must start cleanly")
                                .hasNotFailed();
                        final AwsProperties bound = context.getBean(AwsProperties.class);

                        assertThat(bound.region()).isEqualTo(REGION);
                        assertThat(bound.endpointOverride()).isEqualTo(ENDPOINT_OVERRIDE);
                        assertThat(bound.s3().batchStagingBucket()).isEqualTo(BUCKET);
                        assertThat(bound.sqs().jobQueue()).isEqualTo(QUEUE);
                        assertThat(bound.sqs().messageGroupId()).isEqualTo(MESSAGE_GROUP);
                        assertThat(bound.sns().jobNotificationTopic()).isEqualTo(TOPIC);
                    });
        }

        @Test
        @DisplayName("keeps the three service groups as nested values rather than flattening them into "
                + "components of the outer settings")
        void keepsTheThreeServiceGroupsAsNestedValues() {
            AwsPropertiesTest.this.runner.withPropertyValues(completeConfiguration()).run(context -> {
                final AwsProperties bound = context.getBean(AwsProperties.class);

                assertThat(bound.s3())
                        .as("the object-store group is one value, so a consumer receives the group "
                                + "rather than a bag of loose strings")
                        .isEqualTo(new AwsProperties.S3(BUCKET))
                        .isNotEqualTo(new AwsProperties.S3(BUCKET + "-other"));
                assertThat(bound.sqs())
                        .as("the queue group carries both of its components together, which is what "
                                + "lets the destination and its message group be validated as a pair")
                        .isEqualTo(new AwsProperties.Sqs(QUEUE, MESSAGE_GROUP))
                        .isNotEqualTo(new AwsProperties.Sqs(QUEUE, MESSAGE_GROUP + "-other"));
                assertThat(bound.sns())
                        .isEqualTo(new AwsProperties.Sns(TOPIC));
                assertThat(bound)
                        .as("and the whole settings value equals one assembled here from the same "
                                + "literals, which holds only if the shape is region, endpoint "
                                + "override and the three nested groups - a flattened shape could not "
                                + "be assembled this way")
                        .isEqualTo(new AwsProperties(REGION, ENDPOINT_OVERRIDE,
                                new AwsProperties.S3(BUCKET),
                                new AwsProperties.Sqs(QUEUE, MESSAGE_GROUP),
                                new AwsProperties.Sns(TOPIC)));
            });
        }

        @Test
        @DisplayName("binds the hyphenated bucket key to its camel-cased component, which is the one "
                + "key path a binder could plausibly fail to reach")
        void bindsTheHyphenatedBucketKey() {
            AwsPropertiesTest.this.runner
                    .withPropertyValues(completeConfigurationWithout(KEY_ENDPOINT_OVERRIDE))
                    .run(context -> assertThat(context.getBean(AwsProperties.class)
                            .s3().batchStagingBucket())
                            .as("%s must reach batchStagingBucket through the binder's relaxed matching",
                                    KEY_BUCKET)
                            .isEqualTo(BUCKET));
        }

        @Test
        @DisplayName("ignores a flattened spelling of a nested key, so a value written at the wrong "
                + "depth cannot quietly take effect")
        void ignoresAFlattenedSpellingOfANestedKey() {
            AwsPropertiesTest.this.runner
                    .withPropertyValues(completeConfiguration())
                    .withPropertyValues(PREFIX + ".batch-staging-bucket=flattened-not-nested",
                            PREFIX + ".job-queue=flattened-not-nested.fifo",
                            PREFIX + ".message-group-id=flattened-not-nested",
                            PREFIX + ".job-notification-topic=flattened-not-nested")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        final AwsProperties bound = context.getBean(AwsProperties.class);

                        assertThat(bound.s3().batchStagingBucket())
                                .as("a bucket written one level up is not this bucket")
                                .isEqualTo(BUCKET);
                        assertThat(bound.sqs().jobQueue()).isEqualTo(QUEUE);
                        assertThat(bound.sqs().messageGroupId()).isEqualTo(MESSAGE_GROUP);
                        assertThat(bound.sns().jobNotificationTopic()).isEqualTo(TOPIC);
                    });
        }

        @Test
        @DisplayName("absorbs nothing from outside its own prefix, so a neighbouring namespace cannot "
                + "redirect a submission")
        void absorbsNothingFromOutsideItsOwnPrefix() {
            AwsPropertiesTest.this.runner
                    .withPropertyValues(completeConfiguration())
                    .withPropertyValues("aws.region=eu-west-1",
                            "cloud.aws.region=eu-west-1",
                            "carddemo.awscloud.region=eu-west-1",
                            "carddemo.aws-legacy.s3.batch-staging-bucket=someone-elses-bucket",
                            "carddemo.aws-legacy.sqs.job-queue=someone-elses-queue.fifo",
                            PREFIX + ".s3.bucket=withdrawn-key-bucket",
                            PREFIX + ".sqs.job-submission-queue=withdrawn-key-queue.fifo")
                    .run(context -> {
                        assertThat(context)
                                .as("a key this type does not bind is not a binding failure either; it "
                                        + "must simply have no effect")
                                .hasNotFailed();
                        final AwsProperties bound = context.getBean(AwsProperties.class);

                        assertThat(bound.region())
                                .as("a region declared under any other namespace is somebody else's "
                                        + "setting; publishing into the wrong region is exactly the "
                                        + "failure this keeps out")
                                .isEqualTo(REGION);
                        assertThat(bound.s3().batchStagingBucket())
                                .as("neither a neighbouring namespace nor the withdrawn s3.bucket path "
                                        + "may supply this value")
                                .isEqualTo(BUCKET);
                        assertThat(bound.sqs().jobQueue())
                                .as("nor the withdrawn sqs.job-submission-queue path")
                                .isEqualTo(QUEUE);
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
        @ValueSource(strings = {"JOBS", "submission-queue", "JOBS.fifo.bak", "fifo", "JOBS.fif"})
        @DisplayName("stops start-up for a name the queue service would refuse, rather than letting a "
                + "submission discover it")
        void stopsStartUpForANameTheQueueServiceWouldRefuse(final String queueName) {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(completeConfigurationWith(KEY_QUEUE, queueName))
                    .run(context -> {
                        assertThat(context)
                                .as("a well-formed name that names nothing is the input that makes a "
                                        + "failed submission look like a successful one")
                                .hasFailed();
                        assertThat(context)
                                .getFailure()
                                .as("the refusal must come from binding these very settings")
                                .isInstanceOf(ConfigurationPropertiesBindException.class);
                        assertThat(rootCauseOf(context))
                                .as("and it must name the key an operator has to correct")
                                .hasMessageContaining(KEY_QUEUE)
                                .hasMessageContaining(FIFO_SUFFIX);
                    });
        }

        @ParameterizedTest(name = "queue = {0}")
        @ValueSource(strings = {"JOBS.fifo", "submission-queue.fifo", "a.fifo", "CardDemo-Jobs.fifo"})
        @DisplayName("accepts any otherwise valid name that carries it, since the suffix and the queue "
                + "service's own character rule are the only conditions this type imposes")
        void acceptsAnyOtherwiseValidNameThatCarriesIt(final String queueName) {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(completeConfigurationWith(KEY_QUEUE, queueName))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(AwsProperties.class).sqs().jobQueue())
                                .as("the configured destination is bound unchanged, so recognising a "
                                        + "form never rewrites it")
                                .isEqualTo(queueName);
                    });
        }

        @Test
        @DisplayName("is read exactly as written, so an upper-cased spelling of it is refused rather "
                + "than quietly accepted as equivalent")
        void isReadExactlyAsWritten() {
            final String upperCasedSuffix = "JOBS.FIFO";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the queue service compares the suffix byte for byte, so this type must too; "
                            + "treating the two spellings as equal here would accept a name the "
                            + "service then refuses to resolve")
                    .isThrownBy(() -> new AwsProperties.Sqs(upperCasedSuffix, MESSAGE_GROUP))
                    .withMessageContaining(KEY_QUEUE)
                    .withMessageContaining(FIFO_SUFFIX);

            assertThatNoException()
                    .as("only the suffix's own spelling is fixed; the name before it may carry any "
                            + "case the character rule permits")
                    .isThrownBy(() -> new AwsProperties.Sqs("CardDemo-Jobs.fifo", MESSAGE_GROUP));
        }

        @Test
        @DisplayName("names the key and the suffix it requires, and does not echo the value it refused")
        void namesTheKeyAndTheSuffixWithoutEchoingTheValue() {
            final String refused = "not-a-queue-of-this-kind";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new AwsProperties.Sqs(refused, MESSAGE_GROUP))
                    .withMessageContaining(KEY_QUEUE)
                    .withMessageContaining(FIFO_SUFFIX)
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

            assertThat(constructed.jobQueue()).isNull();
            assertThat(constructed.messageGroupId()).isEqualTo(MESSAGE_GROUP);
        }
    }

    @Nested
    @DisplayName("★ A hostile or malformed resource value, bound through the binder")
    class HostileResourceValues {

        @ParameterizedTest(name = "region = [{0}]")
        @ValueSource(strings = {"US-EAST-1", "us_east_1", "useast1", "us-east", "us-east-x",
            "us-east-1a", "us-east-123", "u-east-1", "region-that-is-far-too-long-to-be-one-1",
            "us--1", "us-east-\u0661"})
        @DisplayName("a region that is not shaped like a region stops start-up, rather than reaching "
                + "three client builders that then fail on their first request")
        void aMalformedRegionStopsStartUp(final String region) {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(completeConfigurationWith(KEY_REGION, region))
                    .run(context -> {
                        assertThat(context)
                                .as("a region is resolved into an endpoint per client, so an unusable "
                                        + "one is three deferred failures rather than one")
                                .hasFailed();
                        assertThat(rootCauseOf(context))
                                .as("and the refusal names the key an operator has to correct")
                                .hasMessageContaining(KEY_REGION);
                    });
        }

        @ParameterizedTest(name = "region = {0}")
        @ValueSource(strings = {"us-east-1", "us-east-2", "eu-west-2", "ap-southeast-3",
            "us-gov-west-1", "cn-north-1", "il-central-1", "us-iso-east-1", "eusc-de-east-1"})
        @DisplayName("every published region shape is accepted, including partitions and sovereign "
                + "areas, because a rule that refused a future region would be worse than one that "
                + "accepts an unused shape")
        void everyPublishedRegionShapeIsAccepted(final String region) {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(completeConfigurationWith(KEY_REGION, region))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(AwsProperties.class).region()).isEqualTo(region);
                    });
        }

        @ParameterizedTest(name = "bucket = [{0}]")
        @ValueSource(strings = {"Carddemo-Batch-Staging", "carddemo_batch_staging", "ab",
            "-carddemo-batch-staging", "carddemo-batch-staging-", "carddemo..staging",
            "192.168.5.4", "xn--carddemo", "carddemo-s3alias", ".carddemo", "carddemo staging",
            "carddemo/staging",
            "a-bucket-name-that-is-definitely-longer-than-sixty-three-characters-in-total"})
        @DisplayName("a bucket name the object store could never create stops start-up, rather than "
                + "letting a batch run finish its work and fail on the write that stores it")
        void aMalformedBucketStopsStartUp(final String bucket) {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(completeConfigurationWith(KEY_BUCKET, bucket))
                    .run(context -> {
                        assertThat(context)
                                .as("the object store refuses this name, so accepting it here only "
                                        + "moves the refusal to the end of a completed run")
                                .hasFailed();
                        assertThat(rootCauseOf(context))
                                .as("and the refusal names the key an operator has to correct")
                                .hasMessageContaining(KEY_BUCKET);
                    });
        }

        @ParameterizedTest(name = "bucket = {0}")
        @ValueSource(strings = {"carddemo-batch-staging", "abc", "a.b.c", "carddemo.batch.staging",
            "1carddemo2", "unit-test-staging-bucket"})
        @DisplayName("every otherwise legal bucket name is accepted, dots included, since a dotted name "
                + "is legal for a general-purpose bucket")
        void everyLegalBucketNameIsAccepted(final String bucket) {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(completeConfigurationWith(KEY_BUCKET, bucket))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(AwsProperties.class).s3().batchStagingBucket())
                                .isEqualTo(bucket);
                    });
        }

        @ParameterizedTest(name = "topic = [{0}]")
        @ValueSource(strings = {"carddemo.job.notifications", "carddemo job notifications",
            "carddemo/job-notifications", "carddemo:job-notifications",
            "arn:aws:sqs:us-east-1:000000000000:carddemo-job-notifications",
            "arn:aws:sns:us-east-1:00000000000:carddemo-job-notifications",
            "arn:aws:sns:us-east-1:00000000000a:carddemo-job-notifications",
            "arn:not-a-partition:sns:us-east-1:000000000000:carddemo-job-notifications",
            "arn:aws:sns:not-a-region:000000000000:carddemo-job-notifications",
            "arn:aws:sns:us-east-1:000000000000", "arn:aws:sns:us-east-1:000000000000:"})
        @DisplayName("a notification destination the topic service could not resolve stops start-up, so "
                + "a job that finished does not lose its completion notice to a name that names nothing")
        void aMalformedTopicStopsStartUp(final String topic) {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(completeConfigurationWith(KEY_TOPIC, topic))
                    .run(context -> {
                        assertThat(context)
                                .as("a standard topic name admits no dot and no separator, and an "
                                        + "identifier must name this service in a real partition")
                                .hasFailed();
                        assertThat(rootCauseOf(context))
                                .as("and the refusal names the key an operator has to correct")
                                .hasMessageContaining(KEY_TOPIC);
                    });
        }

        @ParameterizedTest(name = "topic = {0}")
        @ValueSource(strings = {"carddemo-job-notifications", "CardDemo_Job_Notifications", "t",
            "arn:aws:sns:us-east-1:000000000000:carddemo-job-notifications",
            "arn:aws-us-gov:sns:us-gov-west-1:000000000000:carddemo-job-notifications"})
        @DisplayName("both accepted forms bind unchanged: a bare name and an identifier naming this "
                + "service, because the publishing template resolves either")
        void bothAcceptedTopicFormsBindUnchanged(final String topic) {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(completeConfigurationWith(KEY_TOPIC, topic))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(AwsProperties.class).sns().jobNotificationTopic())
                                .as("the configured destination is bound unchanged, so recognising a "
                                        + "form never rewrites it")
                                .isEqualTo(topic);
                    });
        }

        @ParameterizedTest(name = "endpoint = [{0}]")
        @ValueSource(strings = {"ftp://localstack:4566", "file:///tmp/localstack",
            "s3://carddemo-batch-staging", "jar:file:///tmp/a.jar!/", "classpath:/localstack",
            "gopher://localstack:4566", "HTTP+SSL://localstack:4566"})
        @DisplayName("an endpoint on a transport these clients cannot speak stops start-up, because the "
                + "value is handed to three client builders and each would fail on its first request")
        void anEndpointOnAnUnspeakableTransportStopsStartUp(final String endpoint) {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(completeConfigurationWith(KEY_ENDPOINT_OVERRIDE, endpoint))
                    .run(context -> {
                        assertThat(context)
                                .as("only plain and secure transport reach these services")
                                .hasFailed();
                        assertThat(rootCauseOf(context))
                                .as("and the refusal names the key an operator has to correct")
                                .hasMessageContaining(KEY_ENDPOINT_OVERRIDE);
                    });
        }

        @ParameterizedTest(name = "endpoint = [{0}]")
        @ValueSource(strings = {"http://operator:hunter2@localstack:4566",
            "https://token@localstack:4566"})
        @DisplayName("★ an endpoint carrying credentials stops start-up, because that is a secret "
                + "written into configuration in the one field of these settings that could hold one")
        void anEndpointCarryingCredentialsStopsStartUp(final String endpoint) {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(completeConfigurationWith(KEY_ENDPOINT_OVERRIDE, endpoint))
                    .run(context -> {
                        assertThat(context)
                                .as("credentials belong in the credential chain, not in an address "
                                        + "that every endpoint diagnostic would then carry")
                                .hasFailed();
                        assertThat(rootCauseOf(context))
                                .as("the refusal names the key, and does not repeat the value it "
                                        + "refused - which for this value would publish the secret")
                                .hasMessageContaining(KEY_ENDPOINT_OVERRIDE);
                        assertThat(rootCauseOf(context).getMessage())
                                .doesNotContain("hunter2")
                                .doesNotContain("token");
                    });
        }

        @ParameterizedTest(name = "endpoint = [{0}]")
        @ValueSource(strings = {"http://localstack:4566?x=1", "http://localstack:4566#fragment",
            "http://localstack:4566/path?x=1"})
        @DisplayName("an endpoint that is a request rather than a base address stops start-up, because a "
                + "client builder composes every subsequent path onto whatever it is given")
        void anEndpointThatIsARequestStopsStartUp(final String endpoint) {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(completeConfigurationWith(KEY_ENDPOINT_OVERRIDE, endpoint))
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(rootCauseOf(context)).hasMessageContaining(KEY_ENDPOINT_OVERRIDE);
                    });
        }

        @ParameterizedTest(name = "endpoint = {0}")
        @ValueSource(strings = {"http://localhost:4566", "https://emulator.internal",
            "HTTP://localstack:4566", "http://localstack:4566/edge"})
        @DisplayName("a base address on either speakable transport is accepted, in any case and with a "
                + "path prefix, because a deployment may legitimately front the emulator")
        void aBaseAddressOnASpeakableTransportIsAccepted(final String endpoint) {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(completeConfigurationWith(KEY_ENDPOINT_OVERRIDE, endpoint))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(AwsProperties.class).endpointOverrideUri())
                                .contains(URI.create(endpoint));
                    });
        }

        @ParameterizedTest(name = "padded value = [{0}]")
        @ValueSource(strings = {"us-east-1 ", " us-east-1", "\tus-east-1"})
        @DisplayName("\u2605 a padded resource name is refused at construction, because a service "
                + "compares a name byte for byte and a padded name is a different name")
        void aPaddedResourceNameIsRefusedAtConstruction(final String padded) {
            // Asserted here rather than through the binder deliberately: the test property harness trims
            // both ends of every value it applies - measured, not assumed - so a padded value cannot reach
            // the binder from this suite at all. It can reach the record in production, where an
            // environment variable carries exactly the bytes the deployment exported, which is why the
            // rule refuses it and why the refusal is asserted at the boundary that can observe it.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a padded region names no region")
                    .isThrownBy(() -> new AwsProperties(padded, ENDPOINT_OVERRIDE,
                            new AwsProperties.S3(BUCKET), new AwsProperties.Sqs(QUEUE, MESSAGE_GROUP),
                            new AwsProperties.Sns(TOPIC)))
                    .withMessageContaining(KEY_REGION);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a padded bucket name names no bucket")
                    .isThrownBy(() -> new AwsProperties.S3(padded.replace("us-east-1", BUCKET)))
                    .withMessageContaining(KEY_BUCKET);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a padded topic name names no topic")
                    .isThrownBy(() -> new AwsProperties.Sns(padded.replace("us-east-1", TOPIC)))
                    .withMessageContaining(KEY_TOPIC);
        }

        @Test
        @DisplayName("every shipped document's own values pass every rule above, so the rules cannot "
                + "have been written tighter than the configuration they govern")
        void everyShippedValuePassesEveryRule() {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(COMPLETE.entrySet().stream()
                            .map(entry -> entry.getKey() + "=" + entry.getValue())
                            .toArray(String[]::new))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        final AwsProperties bound = context.getBean(AwsProperties.class);
                        assertThat(bound.region()).isEqualTo(REGION);
                        assertThat(bound.s3().batchStagingBucket()).isEqualTo(BUCKET);
                        assertThat(bound.sqs().jobQueue()).isEqualTo(QUEUE);
                        assertThat(bound.sns().jobNotificationTopic()).isEqualTo(TOPIC);
                        assertThat(bound.endpointOverride()).isEqualTo(ENDPOINT_OVERRIDE);
                    });
        }
    }

    @Nested
    @DisplayName("A missing or blank resource name")
    class MissingOrBlankResourceName {

        @Test
        @DisplayName("is not what a complete configuration is, so the constraint tests below are not "
                + "passing on an environment that could never start")
        void isNotWhatACompleteConfigurationIs() {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(completeConfigurationWithout(KEY_ENDPOINT_OVERRIDE))
                    .run(context -> {
                        assertThat(context)
                                .as("the five required values, with the optional one absent, is the "
                                        + "shape a production deployment ships")
                                .hasNotFailed();
                        assertThat(context).hasSingleBean(AwsProperties.class);
                    });
        }

        @ParameterizedTest(name = "omitted = {0}")
        @MethodSource("com.carddemo.config.AwsPropertiesTest#requiredKeys")
        @DisplayName("stops start-up when the key is absent, so a deployment is refused rather than "
                + "defaulted")
        void stopsStartUpWhenTheKeyIsAbsent(final String omittedKey) {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(completeConfigurationWithout(omittedKey))
                    .run(context -> {
                        assertThat(context)
                                .as("%s is required, and nothing here may supply it silently", omittedKey)
                                .hasFailed();
                        assertThat(context)
                                .getFailure()
                                .isInstanceOf(ConfigurationPropertiesBindException.class);
                        assertThat(rootCauseOf(context))
                                .as("the report must name the namespace the missing value belongs to. "
                                        + "Omitting the only key of a group leaves that whole group "
                                        + "absent, and the group is then what the report can name; the "
                                        + "blanked case below pins the full key for all five")
                                .hasMessageContaining(namespaceOf(omittedKey));
                    });
        }

        @ParameterizedTest(name = "blanked = {0}")
        @MethodSource("com.carddemo.config.AwsPropertiesTest#requiredKeys")
        @DisplayName("stops start-up when the key is present but blank, which is the same absence "
                + "wearing a different mask, and names the exact key it rejected")
        void stopsStartUpWhenTheKeyIsBlank(final String blankedKey) {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(completeConfigurationWith(blankedKey, ""))
                    .run(context -> {
                        assertThat(context)
                                .as("a blank %s is not a configured one", blankedKey)
                                .hasFailed();
                        assertThat(context)
                                .getFailure()
                                .isInstanceOf(ConfigurationPropertiesBindException.class);
                        assertThat(rootCauseOf(context))
                                .as("a blank value keeps its group present, so the report can and must "
                                        + "name %s itself", blankedKey)
                                .hasMessageContaining(blankedKey);
                    });
        }

        @Test
        @DisplayName("stops start-up when a whole group is absent, reporting the group rather than "
                + "failing on it later")
        void stopsStartUpWhenAWholeGroupIsAbsent() {
            AwsPropertiesTest.this.validatingRunner.run(context -> {
                assertThat(context)
                        .as("an environment declaring none of these keys must be refused")
                        .hasFailed();
                assertThat(rootCauseOf(context))
                        .as("the report must name this namespace, not a null dereference downstream")
                        .hasMessageContaining(PREFIX);
            });
        }
    }

    @Nested
    @DisplayName("The optional endpoint redirection")
    class OptionalEndpointOverride {

        @Test
        @DisplayName("binds absent, because that is the configuration a production deployment ships")
        void bindsAbsent() {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(completeConfigurationWithout(KEY_ENDPOINT_OVERRIDE))
                    .run(context -> {
                        assertThat(context)
                                .as("a presence constraint here would refuse the production profile")
                                .hasNotFailed();
                        final AwsProperties bound = context.getBean(AwsProperties.class);

                        assertThat(bound.endpointOverride()).isNull();
                        assertThat(bound.hasEndpointOverride()).isFalse();
                        assertThat(bound.endpointOverrideUri()).isEmpty();
                    });
        }

        @ParameterizedTest(name = "configured = [{0}]")
        @ValueSource(strings = {"", " ", "   ", "\t"})
        @DisplayName("treats a blank value as no redirection at all, rather than as an address of zero "
                + "length")
        void treatsABlankValueAsNoRedirection(final String blank) {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(completeConfigurationWith(KEY_ENDPOINT_OVERRIDE, blank))
                    .run(context -> {
                        assertThat(context)
                                .as("a blank override is the same absence wearing a different mask, and "
                                        + "absence is not a fault for this one component")
                                .hasNotFailed();
                        final AwsProperties bound = context.getBean(AwsProperties.class);

                        assertThat(bound.hasEndpointOverride()).isFalse();
                        assertThat(bound.endpointOverrideUri()).isEmpty();
                    });
        }

        @ParameterizedTest(name = "configured = {0}")
        @ValueSource(strings = {"http://localhost:4566", "http://localstack:4566",
            "https://emulator.internal:4566", "http://127.0.0.1:4566"})
        @DisplayName("parses a configured address into the form a client builder takes")
        void parsesAConfiguredAddress(final String configured) {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(completeConfigurationWith(KEY_ENDPOINT_OVERRIDE, configured))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        final AwsProperties bound = context.getBean(AwsProperties.class);

                        assertThat(bound.hasEndpointOverride()).isTrue();
                        assertThat(bound.endpointOverrideUri())
                                .as("a consumer receives the parsed address rather than testing a "
                                        + "string for emptiness at each client builder")
                                .contains(URI.create(configured));
                        assertThat(bound.endpointOverride())
                                .as("the configured text is returned unchanged, so recognising a form "
                                        + "never rewrites it")
                                .isEqualTo(configured);
                    });
        }

        @ParameterizedTest(name = "configured = {0}")
        @ValueSource(strings = {"localhost:4566", "not an address", "/actuator", "http://",
            "${LOCALSTACK_ENDPOINT}"})
        @DisplayName("stops start-up for a value no client could address, rather than letting the first "
                + "request discover it")
        void stopsStartUpForAValueNoClientCouldAddress(final String configured) {
            AwsPropertiesTest.this.validatingRunner
                    .withPropertyValues(completeConfigurationWith(KEY_ENDPOINT_OVERRIDE, configured))
                    .run(context -> {
                        assertThat(context)
                                .as("an override is handed to a client builder, so an unusable one fails "
                                        + "at the first request unless it is refused here")
                                .hasFailed();
                        assertThat(rootCauseOf(context))
                                .as("and the refusal names the key an operator has to correct")
                                .hasMessageContaining(KEY_ENDPOINT_OVERRIDE);
                    });
        }

        @Test
        @DisplayName("names the key and what the key requires, and does not echo the value it refused")
        void namesTheKeyWithoutEchoingTheValue() {
            final String refused = "definitely-not-an-endpoint";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new AwsProperties(REGION, refused,
                            new AwsProperties.S3(BUCKET),
                            new AwsProperties.Sqs(QUEUE, MESSAGE_GROUP),
                            new AwsProperties.Sns(TOPIC)))
                    .withMessageContaining(KEY_ENDPOINT_OVERRIDE)
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .as("a diagnostic states its own expectation and does not repeat what it "
                                    + "was given; the operator can read that at the key it names")
                            .doesNotContain(refused));
        }

        @Test
        @DisplayName("is not a credential and is not treated as one, so the description remains a "
                + "diagnostic")
        void isNotACredential() {
            final AwsProperties constructed = new AwsProperties(REGION, ENDPOINT_OVERRIDE,
                    new AwsProperties.S3(BUCKET),
                    new AwsProperties.Sqs(QUEUE, MESSAGE_GROUP),
                    new AwsProperties.Sns(TOPIC));

            assertThat(constructed.endpointOverrideUri())
                    .as("an emulator address is a published locator, not a secret")
                    .contains(URI.create(ENDPOINT_OVERRIDE));
            assertThat(constructed.toString())
                    .as("redacting a published locator would hide nothing and cost a diagnostic")
                    .contains(ENDPOINT_OVERRIDE);
        }
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

                assertThat(bound.region())
                        .as("every resource below is provisioned in this region, and the framework's "
                                + "own region setting derives from this key rather than restating the "
                                + "value")
                        .isEqualTo(REGION);
                assertThat(bound.s3().batchStagingBucket())
                        .as("the bucket is a cross-file contract; the emulator bootstrap script and "
                                + "the container composition provision this exact name")
                        .isEqualTo(BUCKET);
                assertThat(bound.sqs().jobQueue())
                        .as("the queue carries the name the plan prescribes for this one resource "
                                + "plus the suffix the queue service demands; the other three names are "
                                + "module-namespaced because the plan names no value for them "
                                + "(docs/decision-log.md DL-092)")
                        .isEqualTo(QUEUE);
                assertThat(bound.sqs().messageGroupId())
                        .as("one stable group id is what preserves the order cards were appended in, "
                                + "which the legacy queue's append disposition established")
                        .isEqualTo(MESSAGE_GROUP);
                assertThat(bound.sns().jobNotificationTopic()).isEqualTo(TOPIC);
            });
        }

        @ParameterizedTest(name = "profile = {0}")
        @ValueSource(strings = {"local", "test"})
        @DisplayName("aim every client at the emulator, because an absent endpoint override resolves "
                + "the region's real public endpoint rather than failing")
        void aimEveryClientAtTheEmulator(final String profile) {
            AwsPropertiesTest.this.shipped(profile).run(context -> {
                final AwsProperties bound = context.getBean(AwsProperties.class);

                assertThat(bound.hasEndpointOverride())
                        .as("%s must declare an override: a client without one addresses the real "
                                + "service on whatever credentials the default chain finds", profile)
                        .isTrue();
                assertThat(bound.endpointOverrideUri())
                        .as("the parsed form is what a client builder takes")
                        .isPresent()
                        .get()
                        .satisfies(endpoint -> {
                            assertThat(endpoint.getHost()).isIn("localhost", "127.0.0.1");
                            assertThat(endpoint.getPort()).isEqualTo(4566);
                        });
            });
        }

        @Test
        @DisplayName("leave production with no endpoint override at all, so each client resolves its "
                + "own region's endpoint")
        void leaveProductionWithNoEndpointOverride() {
            AwsPropertiesTest.this.shipped("prod",
                    QUEUE_VARIABLE + "=" + QUEUE, REGION_VARIABLE + "=" + REGION).run(context -> {
                        assertThat(context).hasNotFailed();
                        final AwsProperties bound = context.getBean(AwsProperties.class);

                        assertThat(bound.hasEndpointOverride())
                                .as("an override exists only to aim a client at an emulator, and an "
                                        + "inherited one would have to be blanked to undo")
                                .isFalse();
                        assertThat(bound.endpointOverrideUri()).isEmpty();
                    });
        }

        @Test
        @DisplayName("declare the four resource names to one canonical value in the baseline and in "
                + "every emulator-facing overlay, whether written literally or defaulted")
        void declareTheFourResourceNamesToOneCanonicalValue() throws IOException {
            for (final String document
                    : List.of(BASELINE_DOCUMENT, LOCAL_DOCUMENT, TEST_DOCUMENT)) {
                final Map<String, Object> declared = declaredEntriesOf(document);

                assertDeclaresCanonically(declared, document, KEY_BUCKET, BUCKET);
                assertDeclaresCanonically(declared, document, KEY_QUEUE, QUEUE);
                assertDeclaresCanonically(declared, document, KEY_MESSAGE_GROUP, MESSAGE_GROUP);
                assertDeclaresCanonically(declared, document, KEY_TOPIC, TOPIC);
            }
            assertDeclaresCanonically(declaredEntriesOf(BASELINE_DOCUMENT), BASELINE_DOCUMENT,
                    KEY_REGION, REGION);
            assertDeclaresCanonically(declaredEntriesOf(TEST_DOCUMENT), TEST_DOCUMENT,
                    KEY_REGION, REGION);
        }

        @Test
        @DisplayName("declare the endpoint redirection only where an emulator is addressed, which is "
                + "the whole of the switch between a deployment and a developer's machine")
        void declareTheEndpointRedirectionOnlyWhereAnEmulatorIsAddressed() throws IOException {
            assertThat(declaredEntriesOf(BASELINE_DOCUMENT))
                    .as("the shared baseline must leave %s undeclared, so a profile that aims at no "
                            + "emulator inherits no redirection to undo", KEY_ENDPOINT_OVERRIDE)
                    .doesNotContainKey(KEY_ENDPOINT_OVERRIDE);

            for (final String document : List.of(LOCAL_DOCUMENT, TEST_DOCUMENT)) {
                assertThat(declaredValueOf(declaredEntriesOf(document), KEY_ENDPOINT_OVERRIDE))
                        .as("%s must aim every client at the emulator's edge endpoint; without it the "
                                + "clients address the real service on whatever credentials the "
                                + "default chain finds", document)
                        .startsWith("http://")
                        .contains("4566");
            }
        }

        @Test
        @DisplayName("declare no endpoint override in the production document itself, which is the "
                + "fact the resolved binding above depends on")
        void declareNoEndpointOverrideInTheProductionDocument() throws IOException {
            assertThat(declaredEntriesOf(PRODUCTION_DOCUMENT))
                    .as("%s must leave %s undeclared: the software development kit then resolves each "
                            + "service's own public endpoint in the configured region",
                            PRODUCTION_DOCUMENT, KEY_ENDPOINT_OVERRIDE)
                    .doesNotContainKey(KEY_ENDPOINT_OVERRIDE);
        }

        @Test
        @DisplayName("let production supply the queue and the region from the environment, since "
                + "production defaults neither")
        void letProductionSupplyTheQueueAndRegionFromTheEnvironment() {
            AwsPropertiesTest.this.shipped("prod",
                    QUEUE_VARIABLE + "=" + QUEUE, REGION_VARIABLE + "=" + REGION).run(context -> {
                        assertThat(context).hasNotFailed();
                        final AwsProperties bound = context.getBean(AwsProperties.class);

                        assertThat(bound.sqs().jobQueue())
                                .as("production binds the queue the deployment names and nothing else")
                                .isEqualTo(QUEUE);
                        assertThat(bound.region())
                                .as("and the region likewise; a deployment landing in whichever region "
                                        + "a fallback named is the failure the removed fallback "
                                        + "prevents")
                                .isEqualTo(REGION);
                    });
        }

        @Test
        @DisplayName("stop a production start-up whose queue variable was never set, rather than "
                + "publishing to the text of a placeholder")
        void stopAProductionStartUpWhoseQueueVariableWasNeverSet() {
            AwsPropertiesTest.this.shipped("prod", REGION_VARIABLE + "=" + REGION)
                    .run(context -> assertThat(context)
                            .as("the binder resolves an unset variable leniently, as its own reference "
                                    + "text, which carries no suffix and must therefore be refused here "
                                    + "too")
                            .hasFailed());
        }

        @Test
        @DisplayName("state the production region and queue as environment references carrying no "
                + "fallback default, so a deployment that omits one is refused rather than guessed for")
        void stateTheProductionRegionAndQueueWithNoFallbackDefault() throws IOException {
            final Map<String, Object> production = declaredEntriesOf(PRODUCTION_DOCUMENT);

            final String region = declaredValueOf(production, KEY_REGION);
            assertReferencesTheEnvironmentWithoutFallback(region, KEY_REGION);
            assertThat(region)
                    .as("a literal region in a production document is one image that can only ever be "
                            + "deployed to one region")
                    .isNotEqualTo(REGION);
            assertThat(referencedVariableOf(region))
                    .as("the reference must name a region variable")
                    .contains("REGION");

            final String queue = declaredValueOf(production, KEY_QUEUE);
            assertReferencesTheEnvironmentWithoutFallback(queue, KEY_QUEUE);
            assertThat(queue)
                    .as("a defaulted queue is a deployment publishing job cards at whatever "
                            + "destination the default names")
                    .isNotEqualTo(QUEUE);
            assertThat(referencedVariableOf(queue))
                    .as("the reference must name a queue variable")
                    .contains("QUEUE");
        }

        @Test
        @DisplayName("declare no credential of any kind in the production document, because the "
                + "software development kit's own provider chain resolves them")
        void declareNoCredentialInTheProductionDocument() throws IOException {
            final Map<String, Object> production = declaredEntriesOf(PRODUCTION_DOCUMENT);

            assertThat(production)
                    .as("an access key in a document is a long-lived credential in a repository, and "
                            + "no key path of this module may bind one")
                    .doesNotContainKey(KEY_CLIENT_ACCESS)
                    .doesNotContainKey(KEY_CLIENT_SECRET);
            assertThat(production.keySet())
                    .as("nor under any other spelling: nothing whose key names an access or secret key "
                            + "may be declared for production")
                    .noneMatch(key -> key.endsWith(".access-key") || key.endsWith(".secret-key"));
        }

        @Test
        @DisplayName("withhold the emulator's throwaway credential and the developer database default, "
                + "so neither drifts into a production deployment")
        void withholdTheBannedDriftDefaults() throws IOException {
            final List<String> production = declaredValuesOf(PRODUCTION_DOCUMENT);

            assertThat(production)
                    .as("the one-word credential literal the emulator historically accepted must "
                            + "appear nowhere in a production document")
                    .doesNotContain("test");
            assertThat(production)
                    .as("the developer database variable and its fallback belong to the local profile; "
                            + "a production deployment resolving a password from a fallback has a "
                            + "published password")
                    .noneMatch(value -> value.contains("POSTGRES_PASSWORD"));

            for (final String document : List.of(LOCAL_DOCUMENT, TEST_DOCUMENT)) {
                final Map<String, Object> declared = declaredEntriesOf(document);

                for (final String credentialKey : List.of(KEY_CLIENT_ACCESS, KEY_CLIENT_SECRET)) {
                    assertThat(declaredValueOf(declared, credentialKey))
                            .as("%s declares %s for the emulator, and its fallback must be obviously "
                                    + "unusable rather than a one-word literal a real service might "
                                    + "accept", document, credentialKey)
                            .isNotEqualTo("test")
                            .doesNotContain(":test}")
                            .startsWith("${");
                }
            }
        }
    }

    @Nested
    @DisplayName("The registration of this settings type")
    class RegistrationHygiene {

        @Test
        @DisplayName("produces exactly one bound bean, so the constraints run once and a second "
                + "registration would be a context failure rather than a silent duplicate")
        void producesExactlyOneBoundBean() {
            AwsPropertiesTest.this.validatingRunner.withPropertyValues(completeConfiguration())
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(AwsProperties.class);
                        assertThat(context.getBeanNamesForType(AwsProperties.class))
                                .as("one enabling declaration must produce one bean definition")
                                .hasSize(1);
                    });
        }

        @Test
        @DisplayName("comes entirely from the owning configuration class: unregistered, the type binds "
                + "nowhere, and it carries no stereotype or scan of its own that could register it "
                + "a second time")
        void comesEntirelyFromTheOwningConfigurationClass() {
            new ApplicationContextRunner()
                    .withPropertyValues(completeConfiguration())
                    .run(context -> {
                        assertThat(context)
                                .as("an environment declaring every key is not by itself a registration")
                                .hasNotFailed();
                        assertThat(context)
                                .as("unregistered, the type binds nowhere, its constraints never run "
                                        + "and the suffix check is never reached - which is what makes "
                                        + "the single enabling declaration load bearing")
                                .doesNotHaveBean(AwsProperties.class);
                    });

            assertThat(MergedAnnotations.from(AwsProperties.class)
                    .isPresent(CONFIGURATION_PROPERTIES_ANNOTATION))
                    .as("the settings annotation must be present on the outer type, so the absence "
                            + "checks below are read against a type this lookup can actually see")
                    .isTrue();

            for (final Class<?> type : List.<Class<?>>of(AwsProperties.class, AwsProperties.S3.class,
                    AwsProperties.Sqs.class, AwsProperties.Sns.class)) {
                final MergedAnnotations annotations = MergedAnnotations.from(type);

                assertThat(annotations.isPresent(COMPONENT_ANNOTATION))
                        .as("%s must carry no stereotype: a stereotype plus the enabling declaration "
                                + "is two bean definitions of one settings type",
                                type.getSimpleName())
                        .isFalse();
                assertThat(annotations.isPresent(PROPERTIES_SCAN_ANNOTATION))
                        .as("%s must not scan for settings types either, for the same reason",
                                type.getSimpleName())
                        .isFalse();
                assertThat(annotations.isPresent(CONSTRUCTOR_BINDING_ANNOTATION))
                        .as("%s must not declare constructor binding: a record has one canonical "
                                + "constructor, so the binder uses it, and the annotation that used to "
                                + "say so is deprecated on this framework line - which this build "
                                + "treats as an error", type.getSimpleName())
                        .isFalse();
            }
        }
    }

    /**
     * Builds a runner that resolves the shipped documents rather than values invented here.
     *
     * <p>The configuration-data initialiser performs the framework's own document discovery, profile
     * activation and overlay merging, so what these tests read is what a started application would
     * read. The placeholder auto-configuration is registered because it is what makes value resolution
     * behave as it does in the deployed application, and the validation support comes with the runner
     * this builds on, so a shipped document is held to the same constraints a deployment is.</p>
     *
     * @param profile  profile to activate, or {@code default} for a profile-less resolution
     * @param supplied environment values to make resolvable, as {@code NAME=value} entries
     * @return a runner that has not been started
     */
    private ApplicationContextRunner shipped(final String profile, final String... supplied) {
        final ApplicationContextRunner configured = this.validatingRunner
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withPropertyValues(supplied);
        return "default".equals(profile)
                ? configured
                : configured.withPropertyValues(KEY_ACTIVE_PROFILES + "=" + profile);
    }

    /**
     * The five keys a deployment must supply, as parameterized-test arguments.
     *
     * @return one argument per required key
     */
    static Stream<Arguments> requiredKeys() {
        return REQUIRED_KEYS.stream().map(Arguments::of);
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

    /**
     * Returns the most specific cause of a failed start-up, which is where a refusal states its reason.
     *
     * <p>The outermost failure reports that binding this namespace failed; the innermost states which
     * value was refused and why. Assertions on a diagnostic therefore belong here rather than on the
     * wrapper, and asserting the wrapper's type as well - which the callers do - keeps the failure
     * attributable to binding these settings rather than to anything else in a context.</p>
     *
     * @param context a context whose start-up has been asserted to have failed
     * @return the innermost cause of the start-up failure
     */
    private static Throwable rootCauseOf(final AssertableApplicationContext context) {
        final Throwable failure = context.getStartupFailure();
        assertThat(failure)
                .as("a diagnostic can only be read from a context that actually failed to start")
                .isNotNull();
        return NestedExceptionUtils.getMostSpecificCause(failure);
    }

    /**
     * Returns the namespace a key belongs to, which is the key without its final segment.
     *
     * @param key a fully qualified configuration key
     * @return the key's parent namespace
     */
    private static String namespaceOf(final String key) {
        return key.substring(0, key.lastIndexOf('.'));
    }

    /**
     * Reads one shipped document's declared entries, before any value is resolved.
     *
     * <p>Resolution is deliberately not performed. What a production document must be asserted on is
     * the <em>text</em> it declares - whether a value is an environment reference and whether that
     * reference carries a fallback - and a resolved view cannot show either: it shows the substituted
     * result, or the reference text itself when nothing satisfied it, and the two are
     * indistinguishable from a value somebody typed.</p>
     *
     * @param document class-path name of the document to read
     * @return every declared key, at its declared text, with the first declaration winning
     * @throws IOException if the document cannot be read
     */
    private static Map<String, Object> declaredEntriesOf(final String document) throws IOException {
        final List<PropertySource<?>> loaded =
                new YamlPropertySourceLoader().load(document, new ClassPathResource(document));

        assertThat(loaded)
                .as("%s must be a readable document on the class path", document)
                .isNotEmpty();

        final Map<String, Object> declared = new LinkedHashMap<>();
        for (final PropertySource<?> source : loaded) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (final String name : enumerable.getPropertyNames()) {
                    declared.putIfAbsent(name, enumerable.getProperty(name));
                }
            }
        }
        assertThat(declared)
                .as("%s must declare something; an empty document would make every assertion over it "
                        + "vacuous", document)
                .isNotEmpty();
        return declared;
    }

    /**
     * Reads one shipped document's declared values as text.
     *
     * @param document class-path name of the document to read
     * @return every declared value, rendered as text
     * @throws IOException if the document cannot be read
     */
    private static List<String> declaredValuesOf(final String document) throws IOException {
        return declaredEntriesOf(document).values().stream().map(String::valueOf).toList();
    }

    /**
     * Returns one declared value as text, requiring it to be declared at all.
     *
     * @param declared a document's declared entries
     * @param key      the key to read
     * @return the declared text
     */
    private static String declaredValueOf(final Map<String, Object> declared, final String key) {
        assertThat(declared)
                .as("%s must be declared for this assertion to mean anything", key)
                .containsKey(key);
        return String.valueOf(declared.get(key));
    }

    /**
     * Requires a declared value to be a single environment reference carrying no fallback default.
     *
     * <p>A fallback is what turns an unset variable into a silently chosen value. For a value a
     * deployment must decide - the region it runs in, the destination it publishes job cards to - that
     * is worse than a refusal to start, because it is a deployment that works and is wrong.</p>
     *
     * @param value the declared text
     * @param key   the key it was declared at, named in every diagnostic
     */
    private static void assertReferencesTheEnvironmentWithoutFallback(final String value,
            final String key) {
        assertThat(value)
                .as("%s must be supplied by the environment rather than written into the document", key)
                .startsWith(REFERENCE_OPEN)
                .endsWith(REFERENCE_CLOSE);
        assertThat(referencedVariableOf(value))
                .as("%s must name exactly one variable and must carry no fallback default", key)
                .isNotBlank()
                .doesNotContain(FALLBACK_SEPARATOR)
                .doesNotContain(REFERENCE_OPEN);
    }

    /**
     * Returns the text inside a single environment reference, which is the variable name and, if the
     * document declared one, its fallback default.
     *
     * @param value a declared value that has been asserted to be a single reference
     * @return the text between the reference delimiters
     */
    private static String referencedVariableOf(final String value) {
        return value.substring(REFERENCE_OPEN.length(), value.length() - REFERENCE_CLOSE.length());
    }

    /**
     * Requires a document to declare one resource name as the canonical value, however it spells it.
     *
     * <p>Two spellings are legitimate and both appear in the shipped documents: the shared baseline and
     * the local overlay declare a resource name as an environment reference whose fallback default is
     * the canonical name, so a developer can start without preparing an environment, while the suite
     * overlay writes the canonical name literally, so a run cannot be redirected by a variable that
     * happens to be set. What may not differ is the name the two arrive at: the bucket, the queue, the
     * group id and the topic each occur in the container composition and the emulator bootstrap script
     * as well, and a disagreement between any two of them is a stack that starts cleanly and fails on
     * the first publish with nothing pointing at the cause.</p>
     *
     * @param declared  the document's declared entries
     * @param document  the document's name, named in every diagnostic
     * @param key       the key to read
     * @param canonical the value the key must arrive at
     */
    private static void assertDeclaresCanonically(final Map<String, Object> declared,
            final String document, final String key, final String canonical) {
        final String value = declaredValueOf(declared, key);

        if (value.startsWith(REFERENCE_OPEN) && value.endsWith(REFERENCE_CLOSE)) {
            final String reference = referencedVariableOf(value);

            assertThat(reference)
                    .as("%s declares %s as an environment reference, so it must carry the canonical "
                            + "name as its fallback default rather than leaving a developer to supply "
                            + "one", document, key)
                    .contains(FALLBACK_SEPARATOR);
            assertThat(reference.substring(reference.indexOf(FALLBACK_SEPARATOR)
                    + FALLBACK_SEPARATOR.length()))
                    .as("%s must default %s to the name every other file names", document, key)
                    .isEqualTo(canonical);
        } else {
            assertThat(value)
                    .as("%s writes %s literally, so the literal must be the canonical name",
                            document, key)
                    .isEqualTo(canonical);
        }
    }
}
