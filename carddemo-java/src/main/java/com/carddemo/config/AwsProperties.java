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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The names of the cloud resources that stand in for the legacy estate's batch datasets and for its
 * single online-to-batch bridge, bound from configuration and validated before the container will
 * start.
 *
 * <h2>What the legacy estate contributes</h2>
 *
 * <p>The bridge is one resource. {@code app/csd/CARDDEMO.CSD} defines sixty-four CICS resources and
 * exactly one transient-data queue, at lines 499 to 505, described there as submitting jobs from the
 * online region. One program writes to it and nothing else in the estate does, so that queue is the
 * whole of the path from an online request to a batch job. Its definition fixes four things that
 * matter to this module, and only one of them is a name a deployment may choose:
 *
 * <ul>
 *   <li>{@code RECORDSIZE(80)} with {@code RECORDFORMAT(FIXED)} fixes one eighty-character payload
 *       per message. That width is <strong>not</strong> a property here - see the withdrawn-keys note
 *       below - because a different width would be a different record format rather than a different
 *       deployment.</li>
 *   <li>{@code DISPOSITION(MOD)} appends, so the order cards are written in is the order they must be
 *       read in. Order survives a first-in-first-out queue only within a single message group, which
 *       is why {@link Sqs#messageGroupId()} is bound here and why it must be one stable value rather
 *       than one value per message or per submission.</li>
 *   <li>{@code ERROROPTION(IGNORE)} tolerates a refused write. That tolerance is <strong>not</strong>
 *       a property here either: it lives in the publisher, which reports a failed publish through its
 *       return value instead of rethrowing.</li>
 *   <li>{@code OPENTIME(INITIAL)} means the resource existed before the first write and the writing
 *       program never created it. Provisioning therefore stays outside this application - the
 *       emulator bootstrap script locally, infrastructure in a deployment - which is why the shared
 *       baseline sets the queue-not-found strategy to refuse an unresolvable queue rather than create
 *       one. {@code TYPEFILE(OUTPUT)} completes the picture: this module publishes and consumes
 *       nothing.</li>
 * </ul>
 *
 * <p>The object-store settings have no single legacy resource behind them. They replace the sequential
 * output datasets and generation-data-group bases the batch jobs wrote to, which is why one bucket
 * carries several key prefixes: one per output family, so no family can overwrite another.
 *
 * <h2>Every value here is a cross-file contract</h2>
 *
 * <p>The bucket, the queue, the group id and the topic each occur byte-identically in the shared
 * baseline, the local overlay, the test overlay, the container composition, the emulator bootstrap
 * script and the emulator itself. Changing one of them in one place changes nothing until all of them
 * change together, and a disagreement is not a start-up error - it is a stack that starts cleanly and
 * fails on the first publish with nothing pointing at the cause. Two of the four carry a hard
 * technical constraint on top of that agreement: the queue name must end in the first-in-first-out
 * suffix, which {@link Sqs} refuses to bind without, and the group id must be a single stable value,
 * which is what preserves the append order acceptance drains a real queue to verify.
 *
 * <p>The queue keeps the legacy resource name plus that suffix and is deliberately not namespaced
 * after this module; an earlier namespaced value disagreed with the resource the environment
 * provisions, and the reasoning for reverting it is recorded in {@code docs/decision-log.md} DL-092.
 * The bucket and the topic replace a dataset family and a screen message respectively, have no legacy
 * resource name to carry, and are namespaced.
 *
 * <h2>What is deliberately not bound here</h2>
 *
 * <p><strong>The region, the endpoint redirection and the credentials are not in this namespace.</strong>
 * They belong to the cloud integration's own properties, under {@code spring.cloud.aws}, so that the
 * object-store, queue and notification clients are configured through one mechanism rather than two.
 * The region is supplied by {@code AWS_REGION} and is required of a production deployment by
 * {@link ProductionConfigurationValidator}; the endpoint override exists only in the profiles that aim
 * the clients at an emulator, and production declares none; the credentials are resolved by the
 * software development kit's own provider chain, from {@code AWS_ACCESS_KEY_ID} and
 * {@code AWS_SECRET_ACCESS_KEY} among other sources. <strong>No credential is bound, read or logged
 * by this type</strong>, and none may be added to it.
 *
 * <p>Declaring any of the three here as well would be worse than redundant, and specifically worse
 * for the endpoint. An absent endpoint override does not fail closed: a client with no override
 * resolves the region's real public endpoint. A settings component that was empty in every profile
 * would invite a consumer to ask it whether to redirect a client, be told no, and address a real
 * account - which is exactly the class of silent, expensive misconfiguration the surrounding
 * configuration is arranged to prevent.
 *
 * <p>Two queue facts were removed from configuration for a related reason and must not return as
 * components here: the eighty-character record width and the tolerate-a-failed-publish behaviour.
 * Both are settled by the queue definition rather than by a deployment, nothing bound them, and
 * {@code docs/decision-log.md} DL-094 records the decision to withdraw them on the principle that
 * configuration should express what a deployment may decide.
 *
 * <h2>How this type is registered</h2>
 *
 * <p>It is self-annotated only. It carries no {@code @Component} and no {@code @Configuration}, and
 * nothing scans for it, so it becomes a bean exactly where a configuration class enables it through
 * {@code @EnableConfigurationProperties}. One owner is deliberate: two registrations of one settings
 * type are two bean definitions of it, and the second is discovered as a context failure rather than
 * as a duplicate.
 *
 * <p>Constructor binding needs no annotation. A record has one canonical constructor, so the binder
 * uses it; the type-level annotation that used to say so is deprecated on this framework line, and a
 * deprecation warning fails this build.
 *
 * <h2>How validation is split</h2>
 *
 * <p>The constraint annotations express <em>presence</em>, which is what the binder can check on a
 * value it has just read, and they are what turns a missing resource name into a refusal to start
 * rather than a failure at the first publish. The nested groups additionally carry {@link Valid}, so a
 * constraint on a nested component is reached, and {@link NotNull}, so an entirely absent group is
 * reported by name instead of as a null dereference somewhere downstream.
 *
 * <p>The compact constructor of {@link Sqs} expresses the one condition no annotation can state - that
 * a first-in-first-out queue name must carry the suffix the queue service demands - and it
 * deliberately tolerates an absent or blank value so that absence is still reported by
 * {@link NotBlank} rather than as a malformed name. The two mechanisms never overlap: presence is the
 * validator's, and the suffix is the constructor's.
 *
 * <h2>Two further properties of this type, both deliberate</h2>
 *
 * <p><strong>No component is a credential</strong>, so the generated description is safe to log and is
 * not overridden. Every value is a published resource identifier that already appears in six files;
 * redacting one here would hide nothing and would make a diagnostic less useful.
 *
 * <p><strong>Nothing here is a threshold, a service level or a capacity figure.</strong> There is no
 * time-out, no attempt count, no backoff interval, no pool size and no queue depth, in a component or
 * in a comment. The legacy estate documents no such figure and this module asserts none.
 *
 * <p>One note for a reader comparing this type against an earlier draft of its specification. That
 * draft expected a {@code region} component and an {@code endpoint-override} component under this
 * prefix, and expected the bucket and queue keys to be named {@code batch-staging-bucket} and
 * {@code job-queue}. The shipped configuration declares neither of the first two and names the other
 * two {@code bucket} and {@code job-submission-queue}; those documents publish the key paths this type
 * binds against, in the property-contract block at the head of the local overlay, and they are the
 * authority. The components below match that published contract one for one, which is also what keeps
 * this type in step with the publisher and the production guard, both of which already name
 * {@link Sqs#JOB_SUBMISSION_QUEUE_PROPERTY} literally.
 *
 * @param s3  object-store settings: the bucket used to stage batch input and output, and the key
 *            prefix each output family is written under
 * @param sqs queue settings for the job-submission bridge: the first-in-first-out queue cards are
 *            published to, and the single message group that preserves their order
 * @param sns notification settings: the topic job-completion and operational messages fan out to
 */
@ConfigurationProperties(prefix = AwsProperties.PREFIX)
@Validated
public record AwsProperties(
        @NotNull(message = "Object-store settings must be configured under " + AwsProperties.PREFIX + ".s3")
        @Valid
        S3 s3,

        @NotNull(message = "Queue settings must be configured under " + AwsProperties.PREFIX + ".sqs")
        @Valid
        Sqs sqs,

        @NotNull(message = "Notification settings must be configured under " + AwsProperties.PREFIX + ".sns")
        @Valid
        Sns sns) {

    /**
     * Configuration key prefix these settings bind from.
     *
     * <p>Published as a compile-time constant so that the annotation above, the key paths the nested
     * groups name in their own constants, the messages that report a missing group, and any test
     * asserting the bound contract all refer to one authority rather than repeating the literal.</p>
     *
     * <p>It names the <em>configuration</em> prefix. It is unrelated to {@link S3.KeyPrefixes}, which
     * names the object-store key prefixes; the two senses of the word meet only in this class and
     * nowhere else.</p>
     */
    public static final String PREFIX = "carddemo.aws";

    /**
     * Object-store settings for batch file staging.
     *
     * <p>These replace the sequential output datasets and generation-data-group bases the legacy batch
     * jobs wrote to. One bucket holds every family, and each family is separated by its own key
     * prefix, so the bucket name and the prefixes together carry what a set of dataset names carried
     * before.</p>
     *
     * @param bucket name of the bucket batch input and output are staged in. Supplied by
     *               {@code CARDDEMO_S3_BUCKET}, defaulted in the shared baseline to the name the
     *               container composition and the emulator bootstrap script provision, and required
     *               here so that an explicitly blank value stops start-up rather than producing
     *               requests against no bucket
     * @param prefix the key prefix each output family is written under. The component is named for the
     *               configuration key it binds - {@code prefix} - while its type is named for what it
     *               holds
     */
    public record S3(
            @NotBlank(message = "An object-store bucket must be configured; " + S3.BUCKET_PROPERTY
                    + " is defaulted in the shared baseline and must not be blanked")
            String bucket,

            @NotNull(message = "Object-store key prefixes must be configured under "
                    + S3.KEY_PREFIX_PROPERTY_GROUP)
            @Valid
            KeyPrefixes prefix) {

        /** Key path {@link #bucket()} binds from, published so a message or a test names it once. */
        public static final String BUCKET_PROPERTY = PREFIX + ".s3.bucket";

        /** Key path prefixing the group {@link #prefix()} binds from. */
        public static final String KEY_PREFIX_PROPERTY_GROUP = PREFIX + ".s3.prefix";

        /**
         * One object-store key prefix per output family.
         *
         * <p>Each family is a legacy dataset family: the inbound file a job reads, the two statement
         * renderings, the report, the rejected records and the retained backup. They are separate
         * prefixes rather than one, because two families sharing a prefix would let one overwrite the
         * other, and every one of them is required to be non-blank for the same reason - a blank
         * prefix collapses its family onto the bucket root, where it collides with every other family
         * that was blanked.</p>
         *
         * <p>The whole group is declared once, in the shared baseline, and inherited unchanged by each
         * profile. A profile that restated part of it would silently drop the families it omitted.</p>
         *
         * @param inbound        prefix for batch input staged for a job to read
         * @param statements     prefix for the fixed-width statement rendering
         * @param statementsHtml prefix for the markup statement rendering, bound from the hyphenated
         *                       key {@code statements-html}
         * @param reports        prefix for the fixed-width transaction report
         * @param rejects        prefix for records a posting run refused
         * @param backups        prefix for retained copies, which replace the generation-data-group
         *                       generations the legacy backup job wrote
         */
        public record KeyPrefixes(
                @NotBlank(message = "An object-store key prefix must be configured for inbound batch"
                        + " input; a blank prefix writes to the bucket root, where families collide")
                String inbound,

                @NotBlank(message = "An object-store key prefix must be configured for statements; a"
                        + " blank prefix writes to the bucket root, where families collide")
                String statements,

                @NotBlank(message = "An object-store key prefix must be configured for markup"
                        + " statements; a blank prefix writes to the bucket root, where families"
                        + " collide")
                String statementsHtml,

                @NotBlank(message = "An object-store key prefix must be configured for reports; a"
                        + " blank prefix writes to the bucket root, where families collide")
                String reports,

                @NotBlank(message = "An object-store key prefix must be configured for rejected"
                        + " records; a blank prefix writes to the bucket root, where families collide")
                String rejects,

                @NotBlank(message = "An object-store key prefix must be configured for backups; a"
                        + " blank prefix writes to the bucket root, where families collide")
                String backups) {
        }
    }

    /**
     * Queue settings for the online-to-batch job-submission bridge.
     *
     * <p>This is the typed form of the transient-data queue the legacy estate wrote job cards to. Both
     * components are required, and both are required for a reason the queue definition supplies rather
     * than for tidiness: a submission that reaches no queue is a job that never runs, and a submission
     * whose cards arrive out of order is a job stream that will not parse.</p>
     *
     * @param jobSubmissionQueue name, queue locator or resource identifier of the first-in-first-out
     *                           queue cards are published to. Supplied by {@code CARDDEMO_SQS_QUEUE};
     *                           the shared baseline defaults it to the resource the container
     *                           composition and the emulator bootstrap script provision, and a
     *                           production deployment supplies it with <strong>no fallback</strong>, so
     *                           a deployment that omits it is refused rather than pointed at a
     *                           placeholder. It must carry {@link #FIFO_QUEUE_NAME_SUFFIX}
     * @param messageGroupId     the single message group every published card carries. It is one stable
     *                           value, not one per message and not one per submission, because a
     *                           first-in-first-out queue preserves order only within a group and the
     *                           legacy queue appended. Supplied by
     *                           {@code CARDDEMO_SQS_MESSAGE_GROUP_ID}. It is an attribute of a message
     *                           rather than a resource, which is why it is configured wherever the
     *                           publisher is configured and is absent from the provisioning script,
     *                           where it would provision nothing
     */
    public record Sqs(
            @NotBlank(message = "A job-submission queue must be configured; "
                    + Sqs.JOB_SUBMISSION_QUEUE_PROPERTY
                    + " has no default in production and must be supplied by the deployment")
            String jobSubmissionQueue,

            @NotBlank(message = "A message group must be configured; " + Sqs.MESSAGE_GROUP_ID_PROPERTY
                    + " is what preserves the order job-submission cards were appended in")
            String messageGroupId) {

        /**
         * Key path {@link #jobSubmissionQueue()} binds from.
         *
         * <p>The literal is the published contract, and it is spoken by more than this type: the
         * publisher injects the same key and the production guard requires it of a deployment. Naming
         * it once here keeps the three from drifting apart silently.</p>
         */
        public static final String JOB_SUBMISSION_QUEUE_PROPERTY = PREFIX + ".sqs.job-submission-queue";

        /** Key path {@link #messageGroupId()} binds from. */
        public static final String MESSAGE_GROUP_ID_PROPERTY = PREFIX + ".sqs.message-group-id";

        /**
         * Suffix a first-in-first-out queue name is required to end with.
         *
         * <p>Not decoration and not a style preference: the queue service refuses to create or resolve
         * a first-in-first-out queue whose name omits it. Published so that a caller which must state
         * the same expectation - a diagnostic, a test, a provisioning check - states it from one
         * place.</p>
         */
        public static final String FIFO_QUEUE_NAME_SUFFIX = ".fifo";

        /**
         * Refuses a queue name that does not carry the first-in-first-out suffix.
         *
         * <p>The suffix is the one condition on these settings that a constraint annotation cannot
         * state. Enforcing it here converts the single easiest misconfiguration in this namespace into
         * a start-up failure: without it, a well-formed name that is merely wrong reaches the publisher
         * and is reported at the first submission, by which point the operator sees a failed report
         * request rather than a failed deployment.</p>
         *
         * <p>An absent or blank name is deliberately allowed through. It is a <em>missing</em> value
         * rather than a malformed one, and reporting it is {@link NotBlank}'s job; testing it here as
         * well would replace a message that names the missing key with one that describes a suffix the
         * operator never supplied a value for.</p>
         *
         * <p>The diagnostic names the property key and the suffix expected of it and does not repeat
         * the configured value, per {@code docs/decision-log.md} DL-041. The key is the actionable
         * fact - it points at the entry to correct, where the operator can already read the value - and
         * the suffix is this type's own statement of what it requires rather than an echo of what it
         * was given.</p>
         *
         * @throws IllegalArgumentException if a non-blank queue name does not end with
         *                                  {@link #FIFO_QUEUE_NAME_SUFFIX}
         */
        public Sqs {
            if (jobSubmissionQueue != null && !jobSubmissionQueue.isBlank()
                    && !jobSubmissionQueue.endsWith(FIFO_QUEUE_NAME_SUFFIX)) {
                throw new IllegalArgumentException("property " + JOB_SUBMISSION_QUEUE_PROPERTY
                        + " must name a first-in-first-out queue, whose name ends with '"
                        + FIFO_QUEUE_NAME_SUFFIX + "', because job-submission cards must keep the"
                        + " order they were appended in, but the configured value does not carry that"
                        + " suffix");
            }
        }
    }

    /**
     * Notification settings for job-completion and operational fan-out.
     *
     * <p>The legacy estate had no notification resource; an operator learned a job's outcome from the
     * job's own output. The topic replaces that, and it is namespaced after this module because there
     * is no legacy resource name for it to carry.</p>
     *
     * @param jobNotificationTopic name or resource identifier of the topic job-completion and
     *                             operational messages are published to. Supplied by
     *                             {@code CARDDEMO_SNS_TOPIC} and defaulted in the shared baseline to
     *                             the topic the container composition and the emulator bootstrap script
     *                             provision
     */
    public record Sns(
            @NotBlank(message = "A job-notification topic must be configured; "
                    + Sns.JOB_NOTIFICATION_TOPIC_PROPERTY
                    + " is defaulted in the shared baseline and must not be blanked")
            String jobNotificationTopic) {

        /** Key path {@link #jobNotificationTopic()} binds from. */
        public static final String JOB_NOTIFICATION_TOPIC_PROPERTY = PREFIX + ".sns.job-notification-topic";
    }
}
