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
package com.carddemo.support;

import java.util.List;
import java.util.Map;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Shared base for an integration test that needs <strong>both</strong> a real database and a real
 * object store, because the component it exercises reads or writes both.
 *
 * <h2>Why this type exists</h2>
 * A batch job in this module loads through the repository and stages through the object store, so the
 * two are not separable concerns for it: a test that provided only the database would have to stand in
 * for the store, and a stand-in cannot prove that a staged generation was read from a store at all. The
 * migration plan replaces sequential-dataset staging with object storage, so the store is part of the
 * contract rather than an implementation detail behind it.
 *
 * <h2>How it composes the two, and why by composition rather than inheritance</h2>
 * Java admits one superclass, and both container owners are classes rather than interfaces, so one of
 * the two must be reached by delegation. This type extends {@link AbstractPostgresIT} - inheriting its
 * server, its published data-source properties and its reset contract unchanged - and reaches the
 * emulator by delegating to {@link AbstractLocalStackIT}, which is possible because both live in this
 * package.
 *
 * <p>Delegating to {@link AbstractLocalStackIT#registerAwsProperties(DynamicPropertyRegistry)} is also
 * what <em>starts</em> the emulator: that class holds its container in a static field initialised in a
 * static initialiser, and calling any static member of it initialises the class. So one registration
 * call both starts the emulator and publishes its ephemeral address, and neither can happen without the
 * other.
 *
 * <p>Both containers therefore remain single per-JVM instances shared by every subclass of either base.
 * Extending this type adds no third container and no second server: a subclass of this type and a
 * subclass of {@link AbstractPostgresIT} address the same database, and a subclass of this type and a
 * subclass of {@link AbstractLocalStackIT} address the same emulator.
 *
 * <h2>THE SUBCLASS CONTRACT</h2>
 * Everything {@link AbstractPostgresIT} forbids a subclass is still forbidden here, for the same
 * reasons, and two prohibitions are added:
 *
 * <ul>
 *   <li>a subclass declares no {@code @DynamicPropertySource} for the AWS settings - the keys published
 *       by {@link #registerEmulatorProperties(DynamicPropertyRegistry)} are the contract, and a second
 *       registration would decide by ordering which endpoint a client received;</li>
 *   <li>a subclass builds no client of its own - {@link #s3Client()} is bound to the container this
 *       process started, and a client built from configuration could address a real account.</li>
 * </ul>
 *
 * <p>A subclass declares only what is specific to itself: its own {@code @SpringBootTest} and profile,
 * its own fixtures, and its own restoration of whatever it staged.
 *
 * <h2>Restoring the shared state</h2>
 * The emulator is shared, so an object a subclass stages outlives its test unless the subclass removes
 * it. {@link #deleteStagedObject(String, String)} is provided for exactly that, and a subclass calls it
 * from a callback that runs whatever its test's outcome - a half-staged bucket disrupts a neighbouring
 * specification as surely as a fully staged one, and the failure then belongs to a test that already
 * passed.
 */
public abstract class AbstractPostgresAndLocalStackIT extends AbstractPostgresIT {

    /**
     * Restricts construction to subclasses. A test class extends this type; nothing instantiates it
     * directly.
     */
    protected AbstractPostgresAndLocalStackIT() {
        super();
    }

    /**
     * Publishes the running emulator's endpoint, region and credentials into a subclass's context,
     * alongside the data-source properties this type inherits.
     *
     * <p>Delegates rather than restating the keys, so the emulator's address is described in exactly one
     * place for the whole module and a subclass of either base receives the same settings.
     *
     * @param registry the registry the Spring TestContext Framework supplies; must not be null
     */
    @DynamicPropertySource
    protected static void registerEmulatorProperties(final DynamicPropertyRegistry registry) {
        AbstractLocalStackIT.registerAwsProperties(registry);
    }

    /**
     * Returns the object-store client bound to the running emulator.
     *
     * @return the object-store client
     */
    protected static S3Client s3Client() {
        return AbstractLocalStackIT.s3Client();
    }

    /**
     * Creates the staging bucket a subclass needs, treating an already-existing one as success.
     *
     * @param bucket the bucket name; must not be null
     */
    protected static void createStagingBucket(final String bucket) {
        AbstractLocalStackIT.createBucketIfAbsent(bucket);
    }

    /**
     * Stages one object, replacing any object already held under that key.
     *
     * @param bucket  the bucket to stage into; must not be null
     * @param key     the object key; must not be null
     * @param content the exact bytes to store; must not be null
     */
    protected static void stageObject(final String bucket, final String key, final byte[] content) {
        AbstractLocalStackIT.putObject(bucket, key, content);
    }

    /**
     * Reads one staged object back as the exact bytes the store holds.
     *
     * @param  bucket the bucket holding the object; must not be null
     * @param  key    the object key; must not be null
     * @return the object's exact bytes
     */
    protected static byte[] stagedObjectBytes(final String bucket, final String key) {
        return AbstractLocalStackIT.objectBytes(bucket, key);
    }

    /**
     * Reports whether the store holds one staged object, without reading it.
     *
     * @param  bucket the bucket to look in; must not be null
     * @param  key    the object key; must not be null
     * @return {@code true} when the key holds an object
     */
    protected static boolean stagedObjectExists(final String bucket, final String key) {
        return AbstractLocalStackIT.objectExists(bucket, key);
    }

    /**
     * Removes one staged object, treating an absent object as success.
     *
     * @param bucket the bucket the object was staged into; must not be null
     * @param key    the object key; must not be null
     */
    protected static void deleteStagedObject(final String bucket, final String key) {
        AbstractLocalStackIT.deleteObject(bucket, key);
    }

    /**
     * Lists the staged object keys beneath one prefix.
     *
     * <p>A durable generation number is allocated by the store at publication time, against what the
     * base already holds (DL-210), so a specification names a published generation by asking the store
     * what it published rather than by predicting a key from an execution identifier.
     *
     * @param  bucket the bucket to list; must not be null
     * @param  prefix the key prefix; must not be null
     * @return the matching keys, ascending
     */
    protected static List<String> stagedObjectKeysUnder(final String bucket, final String prefix) {
        return AbstractLocalStackIT.objectKeysUnder(bucket, prefix);
    }

    /**
     * Returns the versioning state the staging bucket reports, so a subclass can assert the
     * retained-generation posture rather than assume it.
     *
     * @param  bucket the bucket to inspect; must not be null
     * @return the state the service reports
     */
    protected static BucketVersioningStatus stagingBucketVersioningStatus(final String bucket) {
        return AbstractLocalStackIT.bucketVersioningStatus(bucket);
    }

    // ---------------------------------------------------------------------------------------------
    // THE SUBMISSION QUEUE
    //
    // The emulator this type composes serves the submission queue as well as the object store, and a
    // specification of the online-to-batch bridge needs both at once: the identities it signs on with
    // live on the database, and the cards its request publishes land on the queue. The queue helpers
    // are therefore delegated on exactly the terms the object-store helpers above already are - one
    // emulator, one queue, described in one place - so that a subclass never builds a client and never
    // starts a container of its own.
    //
    // These are additions rather than changes: every delegate below forwards to a method
    // AbstractLocalStackIT already published, so a subclass that used only the object store is
    // unaffected, and a subclass of AbstractLocalStackIT addresses the very same queue.
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns the URL of the submission queue the emulator provisioned.
     *
     * <p>Asked of the emulator rather than composed from a name, because the URL carries the
     * ephemeral host and port of the container this process started.</p>
     *
     * @return the queue's URL
     */
    protected static String jobSubmissionQueueUrl() {
        return AbstractLocalStackIT.jobSubmissionQueueUrl();
    }

    /**
     * Returns the notification topic's ARN, as the emulator allocated it.
     *
     * @return the topic ARN
     */
    protected static String jobNotificationTopicArn() {
        return AbstractLocalStackIT.jobNotificationTopicArn();
    }

    /**
     * Reads messages back off the submission queue in delivery order, deleting each as it is read so
     * that the next messages of the same group become visible.
     *
     * <p>Bounded and deterministic: it stops at the expected count, at the first empty response, or at
     * the shared deadline. Stopping on an empty response is what lets a caller assert that
     * <em>fewer</em> messages than requested were published - which is how a specification proves that
     * a blocked confirmation published nothing at all.</p>
     *
     * @param  expectedMessageCount how many messages to stop after; must not be negative
     * @return the messages read, in the order the service delivered them
     */
    protected static List<Message> drainJobSubmissionQueue(final int expectedMessageCount) {
        return AbstractLocalStackIT.drainQueue(AbstractLocalStackIT.jobSubmissionQueueUrl(),
                expectedMessageCount);
    }

    /**
     * Empties the shared submission queue by receiving and deleting, and reports how many messages it
     * removed.
     *
     * <p>A subclass calls this from a callback that runs whatever its test's outcome. A message one
     * test leaves behind is a message the next test drains and cannot explain, and a half-drained queue
     * disrupts a neighbouring specification as surely as a full one.</p>
     *
     * @return the number of messages removed, which is zero when the queue was already empty
     */
    protected static int resetJobSubmissionQueue() {
        return AbstractLocalStackIT.resetJobSubmissionQueue();
    }

    /**
     * Reads back the attributes the submission queue reports, so a specification can assert its shape
     * rather than assume it.
     *
     * @return the attributes the service reports, keyed as the service names them
     */
    protected static Map<QueueAttributeName, String> jobSubmissionQueueAttributes() {
        return AbstractLocalStackIT.queueAttributes(AbstractLocalStackIT.jobSubmissionQueueUrl());
    }

    /**
     * Reports whether the submission queue is a first-in-first-out queue, as the service itself sees
     * it.
     *
     * <p>That property is what preserves the append ordering of the legacy transient-data queue, so a
     * specification of the bridge asserts it against the service rather than against configuration.</p>
     *
     * @return {@code true} when the service reports the first-in-first-out attribute as set
     */
    protected static boolean jobSubmissionQueueIsFifo() {
        return AbstractLocalStackIT.isFifoQueue(AbstractLocalStackIT.jobSubmissionQueueUrl());
    }
}
