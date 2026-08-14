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
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.Optional;
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
 * <p>The object-store setting has no single legacy resource behind it. One bucket replaces the whole set
 * of sequential output datasets and generation-data-group bases the batch jobs wrote to, and the key an
 * object is written under is composed by the job that writes it rather than configured here.
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
 * <p>Three of the four names are namespaced after this module; the queue is not, because it is the one
 * AWS resource the migration plan names. The plan calls for the SQS FIFO queue {@code JOBS}, which with
 * the suffix {@link Sqs} demands is {@code JOBS.fifo}, and it asks for the other three only as "an S3
 * staging bucket", "message-group ordering" and "an SNS topic" - so those three are this module's
 * choices while the queue is prescribed. A revision that namespaced the queue as well, on the reading
 * that all four names were prescribed byte-identically, was withdrawn. Neither decision reaches the
 * legacy name in the operator-visible failure message, which is the text acceptance compares character
 * for character; nothing composes one of the two from the other. The reasoning is recorded in
 * {@code docs/decision-log.md} DL-092.
 *
 * <h2>The region and the endpoint redirection are bound here; the credentials are not</h2>
 *
 * <p><strong>{@link #region()} and {@link #endpointOverride()} belong to this namespace</strong>, and
 * they are the two settings that decide <em>where</em> the four resource names are resolved. The region
 * is supplied by {@code AWS_REGION}, defaulted in the shared baseline to the region the emulator and the
 * container composition use, stated by a production deployment with <strong>no fallback</strong>, and
 * required of that deployment by {@link ProductionConfigurationValidator}. The endpoint override is
 * declared only by the profiles that aim the clients at an emulator; a production deployment declares
 * none, which is why it is the one component here that is optional and the one that must tolerate an
 * absent or blank value rather than refuse it.
 * A production deployment declares no override at all, and {@link ProductionConfigurationValidator}
 * <strong>refuses one</strong> under that profile: a well-formed redirection is still a redirection,
 * and only the deployment knows whether the destination is its own. The credentials are resolved by
 * the software development kit's own provider chain and <strong>no credential is bound, read or logged
 * by this type</strong>, nor may one be added to it.
 *
 * <p>The cloud integration's own properties, under {@code spring.cloud.aws}, are what the framework
 * builds its object-store, queue and notification clients from, and each profile derives them from the
 * two components above rather than restating the values: the production overlay reads its region
 * straight from {@link #REGION_PROPERTY}, and the emulator-facing overlays declare the same endpoint
 * expression this namespace declares. One fact is therefore stated once per profile, which is what stops
 * the two namespaces from drifting into disagreement - and a disagreement between them is not a start-up
 * error but a stack that starts cleanly and addresses the wrong account.
 *
 * <p><strong>The credentials are not here and may never be.</strong> They are resolved by the software
 * development kit's own provider chain, from {@code AWS_ACCESS_KEY_ID} and
 * {@code AWS_SECRET_ACCESS_KEY} among other sources. <strong>No credential is bound, read or logged by
 * this type</strong>, and none may be added to it.
 *
 * <p>An absent endpoint override does not fail closed: a client with no override resolves the region's
 * real public endpoint. That is exactly why {@link #hasEndpointOverride()} and
 * {@link #endpointOverrideUri()} exist rather than a bare accessor - a consumer asks this type whether a
 * redirection was configured and receives an answer it cannot misread, instead of testing a string for
 * emptiness at each of the three client builders and getting one of them wrong.
 *
 * <p><strong>Not declaring the endpoint is not the same as forbidding it, and the two must never be
 * treated as equivalent.</strong> Resting production's safety on the observation that its own profile
 * document declares no endpoint key is unsound: a document cannot see the environment, and an override
 * supplied there - a variable, a command-line property, a co-activated overlay - binds just as well
 * for a key no document mentions. So the endpoint keys are <em>refused</em> under production by
 * {@link ProductionConfigurationValidator} rather than merely omitted, and the same guard holds the
 * queue destination below to a rule about this deployment rather than only about its shape.
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
 * {@code @EnableConfigurationProperties}. <strong>{@link AwsConfig} is that single owner</strong>, and
 * it is the only class in the module that may enable this type. One owner is deliberate: two
 * registrations of one settings type are two bean definitions of it, and the second is discovered as a
 * context failure rather than as a duplicate.
 *
 * <p>The registration is what makes everything below load bearing. Unregistered, this type binds
 * nowhere, the constraints are never evaluated and the suffix check is never reached, so a missing
 * resource name would be discovered at the first publish rather than at start-up. Registered, an
 * absent or blank required value and a queue name that is not a first-in-first-out name each stop the
 * application before it serves a request.
 *
 * <p>Constructor binding needs no annotation. A record has one canonical constructor, so the binder
 * uses it; the type-level annotation that would state it is deprecated on this framework line, and a
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
 * <p>Two compact constructors express the conditions no annotation can state. {@link Sqs} requires that
 * a first-in-first-out queue name carry the suffix the queue service demands, and this type requires
 * that a <em>configured</em> endpoint override be a usable absolute address. Both deliberately tolerate
 * an absent or blank value: for the queue that leaves absence to be reported by {@link NotBlank} rather
 * than as a malformed name, and for the endpoint override absence is not a fault at all. The mechanisms
 * never overlap, so there is never a question of which one fires: presence is the validator's, and shape
 * is the constructor's.
 *
 * <p>There is a <strong>third</strong> mechanism and it is deliberately not here. Well-formedness
 * cannot distinguish a correctly formed destination that names the wrong recipient from one that names
 * the right one, and only a deployment knows which is which. So the question "may <em>this</em>
 * deployment send job cards there" is asked by {@link ProductionConfigurationValidator} under the
 * production profile, where the region and the rest of the deployment's identity are available to
 * compare against. This type stays profile-agnostic, because the emulator destinations the local and
 * test profiles legitimately use would fail the production rule and must not fail here.
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
 * <h2>The six bound key paths, and why they are asserted rather than inferred</h2>
 *
 * <p><strong>The six bound key paths are the migration plan's, and they are not negotiable against the
 * shipped documents.</strong> They are {@link #REGION_PROPERTY}, {@link #ENDPOINT_OVERRIDE_PROPERTY},
 * {@link S3#BATCH_STAGING_BUCKET_PROPERTY}, {@link Sqs#JOB_QUEUE_PROPERTY},
 * {@link Sqs#MESSAGE_GROUP_ID_PROPERTY} and {@link Sns#JOB_NOTIFICATION_TOPIC_PROPERTY}. An earlier
 * revision of this type bound a different set - it omitted the region and the endpoint override, named
 * the bucket key {@code s3.bucket} and the queue key {@code sqs.job-submission-queue}, and added six
 * {@code s3.prefix.*} keys of its own - and reconciled the profile documents to that set, so every
 * binding test passed while the artefact bound a contract the plan does not state. The lesson is
 * recorded rather than merely fixed: <strong>a document that agrees with this type proves nothing about
 * either, because one agent can edit both.</strong> The plan is the authority for the key paths, the
 * documents follow it, and the withdrawn names are asserted absent so a third round trip fails a test
 * instead of passing review. The six unplanned prefix keys are gone with the rest: nothing bound them,
 * no output family reads one, and a key that reads as configuration while nothing consumes it advertises
 * an adjustability that does not exist.
 *
 * @param region           the region every client resolves its endpoints in, and the region the queue,
 *                         bucket and topic are provisioned in
 * @param endpointOverride an address that redirects every client away from the real service and at an
 *                         emulator, or {@code null} or blank when no redirection applies - which is the
 *                         normal state of a production deployment
 * @param s3               object-store settings: the bucket used to stage batch input and output
 * @param sqs              queue settings for the job-submission bridge: the first-in-first-out queue
 *                         cards are published to, and the single message group that preserves their
 *                         order
 * @param sns              notification settings: the topic job-completion and operational messages fan
 *                         out to
 */
@ConfigurationProperties(prefix = AwsProperties.PREFIX)
@Validated
public record AwsProperties(
        @NotBlank(message = "A region must be configured; " + AwsProperties.REGION_PROPERTY
                + " is defaulted in the shared baseline and has no default in production, where the"
                + " deployment supplies it")
        String region,

        String endpointOverride,

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
     * <p>It names the <em>configuration</em> prefix, and nothing else. It is not an object-store key
     * prefix and composes no object key; the two senses of the word are kept apart deliberately, and this
     * namespace now carries only the first of them.</p>
     */
    public static final String PREFIX = "carddemo.aws";

    /**
     * Key path {@link #region()} binds from.
     *
     * <p>Published because more than this type names it: the production overlay derives the cloud
     * integration's own region setting from this key rather than restating the value, and the production
     * guard requires the key of a deployment.</p>
     */
    public static final String REGION_PROPERTY = PREFIX + ".region";

    /**
     * Key path {@link #endpointOverride()} binds from.
     *
     * <p>Published so that a diagnostic naming the offending entry, and a test asserting which profiles
     * declare it, both state the key from one place.</p>
     */
    public static final String ENDPOINT_OVERRIDE_PROPERTY = PREFIX + ".endpoint-override";

    /**
     * Refuses a configured region that is not shaped like one, and an endpoint override that no client
     * could address.
     *
     * <p>Both checks exist for the reason the queue's suffix check exists: these values are handed to
     * client builders, so a value that is well formed as text but unusable as an address fails at the
     * first request rather than at start-up, and the operator sees a failed batch run instead of a failed
     * deployment. Neither rule is stated here - both are stated once in
     * {@link AwsResourceNamingRules}, which is also where the object-store and notification rules live,
     * so that the five AWS values this type binds are held to one authority rather than to five local
     * opinions.</p>
     *
     * <p>The region is checked as a shape rather than against a list of names: a list would refuse a
     * region that comes into existence after this file was written, which is a worse failure than
     * accepting a shape that happens not to name a region yet. The endpoint override is additionally
     * restricted to the two transports these clients speak, and refused if it carries credentials, a
     * query or a fragment - see that class for why each of the three is refused.</p>
     *
     * <p><strong>A configured name is checked exactly as configured, and never stripped first.</strong>
     * The services compare a resource name byte for byte, so a name carrying a leading or trailing space
     * is a different name, and accepting one here would store it padded and hand it to a client builder -
     * the deferred failure these checks exist to prevent. The queue has always been treated this way and
     * the other three now match it. The endpoint override is the one exception, and it is an exception
     * about its grammar rather than about its value: it is an address rather than a name, whitespace
     * around it cannot be represented in a parsed address at all, and it is therefore trimmed before it
     * is read - see {@link AwsResourceNamingRules#requireEndpointOverride(String, String)}.</p>
     *
     * <p><strong>An absent or blank endpoint override is deliberately accepted</strong>, because absence
     * is this component's normal production state and not a fault. That is also why no presence
     * constraint is declared on it: a constraint would refuse the very configuration a production
     * deployment ships. A blank <em>region</em>, by contrast, is refused by the constraint above, so the
     * grammar rule here only ever sees a value that states something.</p>
     *
     * <p>Every diagnostic names the property key and what the key requires, and none repeats the
     * configured value, per {@code docs/decision-log.md} DL-041.</p>
     *
     * @throws IllegalArgumentException if the region is not shaped like a region name, or if a non-blank
     *                                  endpoint override is not a usable plain- or secure-transport
     *                                  address
     */
    public AwsProperties {
        if (region != null && !region.isBlank()) {
            AwsResourceNamingRules.requireRegion(region, REGION_PROPERTY);
        }
        if (endpointOverride != null && !endpointOverride.isBlank()) {
            AwsResourceNamingRules.requireEndpointOverride(endpointOverride, ENDPOINT_OVERRIDE_PROPERTY);
        }
    }

    /**
     * Reports whether a redirection away from the real service was configured.
     *
     * <p>Consumers ask this rather than testing {@link #endpointOverride()} for emptiness, so that the
     * decision is taken once here instead of once per client builder - three chances to get it wrong,
     * where getting it wrong means one client silently addressing a real account.</p>
     *
     * @return {@code true} when a non-blank endpoint override is configured
     */
    public boolean hasEndpointOverride() {
        return this.endpointOverride != null && !this.endpointOverride.isBlank();
    }

    /**
     * Returns the configured endpoint override as an address, or nothing when none is configured.
     *
     * <p>The value is parsed rather than handed over as text because that is the form a client builder
     * takes, and it is parsed by the very rule the compact constructor applied, so an instance that
     * exists cannot fail here: an empty result therefore means "no redirection was configured", never "a
     * redirection was configured and could not be understood".</p>
     *
     * @return the endpoint override, or an empty optional when the deployment configured none
     */
    public Optional<URI> endpointOverrideUri() {
        return hasEndpointOverride()
                ? Optional.of(AwsResourceNamingRules.requireEndpointOverride(
                        this.endpointOverride, ENDPOINT_OVERRIDE_PROPERTY))
                : Optional.empty();
    }

    /**
     * Object-store settings for batch file staging.
     *
     * <p>These replace the sequential output datasets and generation-data-group bases the legacy batch
     * jobs wrote to. One bucket holds every staged object, and the key each object is written under is
     * composed by the job that writes it, because a key names one object of one run while this namespace
     * carries only what a deployment may decide.</p>
     *
     * <p>No {@code s3.prefix.*} key is declared here - one per output family would be six of them - and
     * none may be added, on the principle the withdrawn queue keys were withdrawn on, recorded in
     * {@code docs/decision-log.md} DL-094: nothing would bind them, no writer would read one, and
     * configuration that nothing consumes advertises an adjustability that does not exist. The plan states
     * one object-store key for this module and this is it.</p>
     *
     * @param batchStagingBucket name of the bucket batch input and output are staged in. Supplied by
     *                           {@code CARDDEMO_S3_BUCKET}, defaulted in the shared baseline to the name
     *                           the container composition and the emulator bootstrap script provision,
     *                           and required here so that an explicitly blank value stops start-up rather
     *                           than producing requests against no bucket
     */
    public record S3(
            @NotBlank(message = "An object-store bucket must be configured; "
                    + S3.BATCH_STAGING_BUCKET_PROPERTY
                    + " is defaulted in the shared baseline and must not be blanked")
            String batchStagingBucket) {

        /**
         * Refuses a bucket name that could not name a bucket in any account.
         *
         * <p>Delegated to {@link AwsResourceNamingRules}, so the service's own naming rules are stated
         * once. A blank value is left to the constraint above, which reports the absence for what it is;
         * this check only ever sees a value that states something.</p>
         *
         * @throws IllegalArgumentException if the configured name breaks the bucket naming rules
         */
        public S3 {
            if (batchStagingBucket != null && !batchStagingBucket.isBlank()) {
                AwsResourceNamingRules.requireBucketName(batchStagingBucket,
                        BATCH_STAGING_BUCKET_PROPERTY);
            }
        }

        /**
         * Key path {@link #batchStagingBucket()} binds from.
         *
         * <p>Published so a constraint message, a diagnostic and a test all name it once. The hyphenated
         * key reaches the camel-cased component through the binder's own relaxed matching.</p>
         */
        public static final String BATCH_STAGING_BUCKET_PROPERTY = PREFIX + ".s3.batch-staging-bucket";
    }

    /**
     * Queue settings for the online-to-batch job-submission bridge.
     *
     * <p>This is the typed form of the transient-data queue the legacy estate wrote job cards to. Both
     * components are required, and both are required for a reason the queue definition supplies rather
     * than for tidiness: a submission that reaches no queue is a job that never runs, and a submission
     * whose cards arrive out of order is a job stream that will not parse.</p>
     *
     * @param jobQueue       name, queue locator or resource identifier of the first-in-first-out queue
     *                       cards are published to. Supplied by {@code CARDDEMO_SQS_QUEUE}; the shared
     *                       baseline defaults it to the resource the container composition and the
     *                       emulator bootstrap script provision, and a production deployment supplies it
     *                       with <strong>no fallback</strong>, so a deployment that omits it is refused
     *                       rather than pointed at a placeholder. It must carry
     *                       {@link #FIFO_QUEUE_NAME_SUFFIX}
     * @param messageGroupId the single message group every published card carries. It is one stable
     *                       value, not one per message and not one per submission, because a
     *                       first-in-first-out queue preserves order only within a group and the legacy
     *                       queue appended. Supplied by {@code CARDDEMO_SQS_MESSAGE_GROUP_ID}. It is an
     *                       attribute of a message rather than a resource, which is why it is configured
     *                       wherever the publisher is configured and is absent from the provisioning
     *                       script, where it would provision nothing
     */
    public record Sqs(
            @NotBlank(message = "A job-submission queue must be configured; " + Sqs.JOB_QUEUE_PROPERTY
                    + " has no default in production and must be supplied by the deployment")
            String jobQueue,

            @NotBlank(message = "A message group must be configured; " + Sqs.MESSAGE_GROUP_ID_PROPERTY
                    + " is what preserves the order job-submission cards were appended in")
            String messageGroupId) {

        /**
         * Key path {@link #jobQueue()} binds from.
         *
         * <p>The literal is the published contract, and it is spoken by more than this type: the
         * publisher injects the same key and the production guard requires it of a deployment. Naming
         * it once here keeps the three from drifting apart silently.</p>
         */
        public static final String JOB_QUEUE_PROPERTY = PREFIX + ".sqs.job-queue";

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
         * Refuses a queue destination that is not a well-formed one of the three recognised forms.
         *
         * <p>Well-formedness is the one class of condition on these settings that a constraint
         * annotation cannot state. Enforcing it here converts the easiest misconfigurations in this
         * namespace into start-up failures: without it, a value that is merely wrong reaches the
         * publisher and is reported at the first submission, by which point the operator sees a failed
         * report request rather than a failed deployment.</p>
         *
         * <p><strong>The check is delegated rather than written here, and that is the point.</strong>
         * Testing one condition inline - that the value ends in the first-in-first-out suffix - and nothing
         * else is not sufficient. That test is satisfied by values that are not queue destinations at all,
         * because the suffix can sit at the end of any string: a URL whose last path segment happens to end
         * in it, an ARN with the wrong number of segments, a name carrying characters the queue service
         * refuses. Each of those binds cleanly and fails at the first publish.
         * {@link SqsNamingRules#requireQueueDestination(String, String)} is the module's
         * one statement of the destination grammar, shared with the publisher and with the emulator
         * bootstrap's own contract, so binding delegates to it and the three cannot drift apart.</p>
         *
         * <p>What this constructor deliberately does <em>not</em> decide is whether the destination is
         * one this deployment may send to. That is a question about the deployment rather than about the
         * value, the local and test profiles legitimately name an emulator, and it is asked under the
         * production profile by {@link ProductionConfigurationValidator}.</p>
         *
         * <p>An absent or blank destination is deliberately allowed through. It is a <em>missing</em>
         * value rather than a malformed one, and reporting it is {@link NotBlank}'s job; testing it here
         * as well would replace a message that names the missing key with one describing a form the
         * operator never supplied a value for.</p>
         *
         * <p>Every diagnostic names the property key and the rule it broke and does not repeat the
         * configured value, per {@code docs/decision-log.md} DL-041. The key is the actionable fact - it
         * points at the entry to correct, where the operator can already read the value - and the rule
         * is this module's own statement of what it requires rather than an echo of what it was
         * given.</p>
         *
         * @throws IllegalArgumentException if a non-blank destination is not a recognisable queue name,
         *                                  queue URL or queue ARN, or carries a queue name that breaks
         *                                  the queue-name rule - which includes not ending with
         *                                  {@link #FIFO_QUEUE_NAME_SUFFIX}
         */
        public Sqs {
            if (jobQueue != null && !jobQueue.isBlank()) {
                SqsNamingRules.requireQueueDestination(jobQueue, JOB_QUEUE_PROPERTY);
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

        /**
         * Refuses a notification destination that is neither a legal topic name nor a resource
         * identifier naming one.
         *
         * <p>Delegated to {@link AwsResourceNamingRules}, alongside the object-store and region rules. A
         * blank value is left to the constraint above. The rule refuses a dot in a bare name, because
         * this module's topic is a standard topic and a dot is admitted only in the ordered variant's
         * mandatory suffix - a name carrying one would bind here and be refused by the service.</p>
         *
         * @throws IllegalArgumentException if the configured destination could not name this module's
         *                                  topic
         */
        public Sns {
            if (jobNotificationTopic != null && !jobNotificationTopic.isBlank()) {
                AwsResourceNamingRules.requireTopicDestination(jobNotificationTopic,
                        JOB_NOTIFICATION_TOPIC_PROPERTY);
            }
        }

        /** Key path {@link #jobNotificationTopic()} binds from. */
        public static final String JOB_NOTIFICATION_TOPIC_PROPERTY = PREFIX + ".sns.job-notification-topic";
    }
}
