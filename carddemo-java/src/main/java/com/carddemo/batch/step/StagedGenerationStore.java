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
package com.carddemo.batch.step;

import com.carddemo.util.SecureStagedFiles;

import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes completed batch artifacts to the configured object-store bucket under collision-free
 * generation keys and enforces the measured generation-group retention limits.
 *
 * <h2>One durable boundary for every batch file</h2>
 *
 * <p>The migrated jobs still compose fixed-width records on a local filesystem because their readers,
 * writers and byte-parity tests operate on files. A local temporary path is not the external file-drop
 * contract, however. This store is the boundary between those two concerns: a job writes an
 * execution-scoped working file, atomically closes it into a completed local generation, registers that
 * generation on its own {@link JobExecution}, and the shared job-boundary listener publishes every
 * registered artifact only after all of the job's steps have completed.
 *
 * <p>Publication is one object-store PUT per artifact. The key is
 * {@code <logical-base>/G<generation-number>V00}, rendered with at least ten digits and never reduced
 * modulo a smaller range, so it preserves the recognisable absolute-generation vocabulary without
 * reproducing the four-digit wrap that caused the reviewed collision.
 *
 * <p><strong>The generation number is the durable store's own, not the framework's.</strong> It is
 * allocated at publication time as one more than the highest generation already present beneath the
 * base, which is what a relative {@code (+1)} allocation against a catalogued generation group does.
 * The framework's execution identifier still names the <em>local</em> per-execution file, because two
 * concurrent executions must not compose over each other, but it deliberately does not name the durable
 * object: a re-created batch metadata store restarts execution identifiers at one while the bucket keeps
 * every object, so a key derived from an execution identifier would silently overwrite a generation
 * published by an earlier run. Allocation happens with the base held, so two concurrent publications
 * cannot choose the same number. See {@code docs/decision-log.md} entry DL-210.
 *
 * <h2>An abnormal end discards what it allocated</h2>
 *
 * <p>{@link #discardLocalArtifactsOf(JobExecution, Path)} removes the local files one non-completing execution
 * created: its completed generations, whose publication was skipped, and any working file its steps left
 * half composed. Only files carrying that execution's own generation token are touched, so a concurrently
 * running job's artifacts cannot be caught by it. This is the abnormal disposition of the legacy
 * allocation - a dataset the job stream created and did not end normally with was deleted rather than
 * catalogued - and without it a failed run leaves a local file that nothing will ever publish, read or
 * prune. See {@code docs/decision-log.md} entry DL-211.
 *
 * <h2>Partial files are never publishable, and are not left behind either</h2>
 *
 * <p>Writers target a sibling ending in {@value #WORKING_SUFFIX}. A successful close moves that file
 * onto the completed generation with one same-filesystem atomic rename and only then registers it. The
 * suffix is outside both the publication registry and the retention scan, so another execution cannot
 * upload, prune or consume a file that is still being written.
 *
 * <p>A failure on the way to that rename - either the delegate's own close or the atomic completion -
 * used to leave the working file in place. Because registration happens <em>at</em> completion, such a
 * file belongs to no execution's registry, and the boundary listener's cleanup deletes only registered
 * paths, so nothing could identify it: it remained in the staging root as a partial copy of the step's
 * output until an operator noticed. Both failure paths now discard it, by its own exact path and after
 * re-establishing that the path is still a trusted staged artifact, without raising and therefore without
 * replacing the failure an operator has to read. See {@code docs/decision-log.md} entry DL-289.
 *
 * <h2>A registered path is re-established before its bytes are read</h2>
 *
 * <p>A registration records a <em>name</em>. Validating that name and later opening it are two separate
 * resolutions, and a local actor able to write the staging root can replace the entry between them. The
 * validation pass therefore applies the four-part trusted-child predicate rather than a link-following
 * regular-file test, and the upload re-applies it, opens with {@link LinkOption#NOFOLLOW_LINKS}, and
 * compares the filesystem identity of the path across the open - so the bytes that reach the object store
 * are the bytes of the file that was verified. See {@code docs/decision-log.md} entry DL-288.
 *
 * <h2>Retention is applied to the durable store, one base at a time</h2>
 *
 * <p>The measured default depth is {@value #STANDARD_RETENTION_LIMIT}; the transaction-report base is
 * the one measured exception at {@value #REPORT_RETENTION_LIMIT}. After all artifacts of a completed job
 * have uploaded, matching generations are sorted by their numeric execution identifier and every object
 * beyond the declared depth is scratched. Non-generation objects beneath the same prefix are ignored.
 *
 * <p><strong>Scratched means the bytes stop existing.</strong> The bucket carries object versioning,
 * because versioning is what carries the retained-generation semantics of the legacy definitions, and
 * against a versioned bucket an unqualified delete adds a delete marker and leaves every version
 * fetchable by version identifier. A base at depth five would then still be holding the bytes of every
 * generation it had ever held, and a rolled-back publication would still be holding the bytes of a job
 * that failed. Both of this class's deletion paths - retention roll-off and publication rollback -
 * therefore remove each version and each delete marker of the key by identifier. See
 * {@code docs/decision-log.md} entry DL-287.
 * Local completed files are deliberately not pruned here: another concurrently running job may still be
 * reading one of its own intermediate files, while the durable object store is the retention authority.
 *
 * <p><strong>The whole of upload-then-retention is serialized per base</strong> through
 * {@link GenerationPublicationLock}. Retention is a read-decide-delete sequence, so performing it while
 * another publication is still uploading measures depth against a set that is still changing. That is not
 * hypothetical here: the transaction-backup base is published both by the backup job and by the
 * transaction-report job's unload step, and those are differently named jobs whose launches the
 * per-job launch lock does not serialize against each other. Two such publications could each observe a
 * set missing the other's upload, each conclude nothing had rolled off, and leave the base permanently one
 * generation over its declared depth.
 *
 * <h2>All-or-failed publication, with one commit point</h2>
 *
 * <p>Every registered source is validated before the first upload. Uploads and fixed-name alias
 * replacements then run as a single compensated unit: if any of them fails, every alias this publication
 * advanced is put back, every object key the publication <em>attempted</em> is purged of every version and
 * delete marker, and the exception is returned to the boundary listener, which marks the job failed before
 * the framework persists its terminal status. Compensation deliberately covers the attempted keys rather
 * than the keys whose upload was observed to succeed: an upload whose bytes the store accepted but whose
 * response never reached this process throws without ever being recorded as published, so a
 * succeeded-only compensation would walk past the one durable object the failure actually left behind.
 * <strong>A failed job therefore leaves nothing externally visible</strong> - neither a durable object nor
 * a fixed-name local view naming a generation that was never published.
 *
 * <p>The commit point is the moment every upload and every alias replacement has succeeded. Only two acts
 * follow it, and both are deliberately incapable of failing the job. Clearing the registry marks the
 * publication done so a repeated callback cannot publish twice. Enforcing retention deletes rolled-off
 * objects, which is irreversible and so cannot be part of any compensation; a failure there is reported
 * and leaves the base temporarily over depth, which the next successful publication for that base
 * corrects. Failing the job at that point would mean either marking a job failed whose artifacts are
 * correctly published, or compensating by deleting artifacts that are correct - both worse than a warning.
 *
 * <p>Alias replacement itself goes through a private temporary sibling and an atomic rename, so a reader
 * sees either the previous complete file or the new complete file and never a partly copied one.
 *
 * <p>See {@code docs/decision-log.md} entry DL-180 for the commit boundary, DL-181 for why exclusion is
 * per base rather than per job, and DL-303 for why compensation covers the attempted keys rather than the
 * acknowledged ones.
 */
@Component
public final class StagedGenerationStore {

    /** Measured depth of every generation group except the transaction-report base. */
    public static final int STANDARD_RETENTION_LIMIT = 5;

    /** Later, more specific measured depth of the transaction-report generation base. */
    public static final int REPORT_RETENTION_LIMIT = 10;

    /** Shared configurable local staging root used by every file-producing batch job. */
    public static final String SHARED_STAGING_DIRECTORY_PROPERTY =
            "carddemo.batch.staging-directory";

    /** Object-store bucket that receives completed batch generations. */
    public static final String BATCH_STAGING_BUCKET_PROPERTY =
            "carddemo.aws.s3.batch-staging-bucket";

    /** Suffix carried only by a local file that is still being composed. */
    public static final String WORKING_SUFFIX = ".part";

    /** Prefix of the execution-context entries used to register completed artifacts. */
    private static final String REGISTRY_PREFIX = "carddemo.batch.staged-generation.";

    /** Number of registered artifacts on one job execution. */
    private static final String REGISTRY_COUNT = REGISTRY_PREFIX + "count";

    /** Field names below one indexed registry entry. */
    private static final String FIELD_BASE = "base";
    private static final String FIELD_EXECUTION_ID = "execution-id";
    private static final String FIELD_SOURCE = "source";
    private static final String FIELD_ALIAS = "alias";
    private static final String FIELD_RETENTION = "retention";

    /** Empty persisted alias means the artifact has no fixed-name local view. */
    private static final String NO_ALIAS = "";

    /** The recognisable generation token, widened so a generation number never wraps. */
    private static final String GENERATION_TOKEN_FORMAT = "G%010dV00";

    /** Lowest generation number a base can hold, so a first publication is generation one. */
    private static final long FIRST_GENERATION_NUMBER = 1L;

    /** The separator between a logical base and its generation token in object storage. */
    private static final char OBJECT_KEY_SEPARATOR = '/';

    /** Separator between a logical base and its generation token on the local filesystem. */
    private static final char LOCAL_NAME_SEPARATOR = '.';

    /** Maximum logical-base length below the object-store key ceiling. */
    private static final int LOGICAL_BASE_MAX_LENGTH = 900;

    /** Logger for publication and retention outcomes; configured by the shared batch category. */
    private static final Logger LOGGER = LoggerFactory.getLogger(StagedGenerationStore.class);

    /** Object-store operations supplied by Spring Cloud AWS. */
    private final S3Operations objectStore;

    /**
     * The version-aware client, used for the two operations whose whole purpose is that bytes stop
     * existing.
     *
     * <p>The operations abstraction above expresses an unqualified delete and nothing else. Against a
     * versioned bucket - which is what this module's staging bucket is, because object versioning is the
     * replacement for the legacy retained-generation depth - an unqualified delete does not delete: it adds
     * a delete marker and leaves every previous version retrievable by version identifier. That is wrong
     * for both of the places this store deletes. See {@code docs/decision-log.md} entry DL-287.
     */
    private final S3Client versionedObjectStore;

    /** Validated destination bucket, captured once so every operation addresses the same resource. */
    private final String bucket;

    /** Serializes publication per generation base so two publications cannot interleave. */
    private final GenerationPublicationLock publicationLock;

    /** The registry every outbound object-store call this class makes is observed against. */
    private final ObservationRegistry observationRegistry;

    /**
     * Observation name every outbound object-store call this class makes is recorded under.
     *
     * <h2>Why this class needed its own</h2>
     *
     * <p>{@code service/BatchStagingService} observes every call it makes, but it is a different surface:
     * it serves a step that reads or writes one named staging object. The durable generation boundary is
     * this class, and every call it makes - the listing that allocates the next generation number, the
     * upload that publishes it, the version listing and version-qualified deletes that compensate a failed
     * pass and enforce retention - was unobserved. That is the whole of the object-store work a batch job
     * performs at its most consequential moment, and none of it appeared in a trace.
     *
     * <p>A separate name from the staging family rather than a shared one, because the two answer different
     * questions: one is "how is the staging area behaving", the other is "how is generation publication
     * behaving". Merging them would average a retention prune into a step's read.
     *
     * <p>See {@code docs/decision-log.md} entry DL-305.
     */
    public static final String OBSERVATION_NAME = "carddemo.batch.generation";

    /** Tag naming which object-store operation was made. */
    public static final String TAG_OPERATION = "operation";

    /** Operation tag value for the listing that measures a base. */
    public static final String OPERATION_LIST = "list";

    /** Operation tag value for publishing one generation's bytes. */
    public static final String OPERATION_UPLOAD = "upload";

    /** Operation tag value for the version listing that precedes a version-qualified removal. */
    public static final String OPERATION_LIST_VERSIONS = "listVersions";

    /** Operation tag value for removing one exact version of one key. */
    public static final String OPERATION_DELETE_VERSION = "deleteVersion";

    /**
     * Tag carrying the key or prefix a call named.
     *
     * <p>High cardinality deliberately: a generation key is unique per execution, so it belongs on the
     * span where it identifies the object, and never on a meter dimension where it would create one
     * series per generation.
     */
    public static final String TAG_OBJECT_KEY = "objectKey";

    /**
     * @param objectStore object-store operations; must not be {@code null}
     * @param versionedObjectStore version-aware object-store client, used for the rollback and retention
     *                             deletes that must remove object versions rather than mask them; must not
     *                             be {@code null}
     * @param configuredBucket configured batch-staging bucket; must not be blank
     * @param publicationLock per-base publication lock; must not be {@code null}
     * @param observationRegistry the registry every outbound object-store call is observed against;
     *                            must not be {@code null}. Required rather than optional on purpose: a
     *                            store constructed with the no-op registry would publish, compensate and
     *                            prune with none of it appearing in a trace, which is indistinguishable
     *                            from a store that made no calls at all
     */
    public StagedGenerationStore(final S3Operations objectStore,
            final S3Client versionedObjectStore,
            @Value("${" + BATCH_STAGING_BUCKET_PROPERTY + "}")
            final String configuredBucket,
            final GenerationPublicationLock publicationLock,
            final ObservationRegistry observationRegistry) {
        this.objectStore = Objects.requireNonNull(objectStore, "objectStore");
        this.versionedObjectStore =
                Objects.requireNonNull(versionedObjectStore, "versionedObjectStore");
        this.observationRegistry =
                Objects.requireNonNull(observationRegistry, "observationRegistry");
        final String requiredBucket =
                Objects.requireNonNull(configuredBucket, BATCH_STAGING_BUCKET_PROPERTY);
        if (requiredBucket.isBlank()) {
            throw new IllegalArgumentException(
                    BATCH_STAGING_BUCKET_PROPERTY + " must not be blank");
        }
        this.bucket = requiredBucket;
        this.publicationLock = Objects.requireNonNull(publicationLock, "publicationLock");
    }

    /**
     * Resolves the completed local generation owned by one job execution.
     *
     * @param directory configured staging directory; must not be {@code null}
     * @param logicalBase logical dataset or generation-group base
     * @param executionId framework job-execution identifier; must not be negative
     * @return the completed generation path
     */
    public static Path generationPath(final Path directory, final String logicalBase,
            final long executionId) {
        Objects.requireNonNull(directory, "directory");
        return directory.resolve(localGenerationName(logicalBase, executionId));
    }

    /**
     * Resolves the working sibling of a completed generation.
     *
     * @param completedGeneration completed generation path
     * @return the working path, ending in {@value #WORKING_SUFFIX}
     */
    public static Path workingPath(final Path completedGeneration) {
        Objects.requireNonNull(completedGeneration, "completedGeneration");
        final Path fileName = Objects.requireNonNull(completedGeneration.getFileName(),
                "completedGeneration must name a file");
        return completedGeneration.resolveSibling(fileName + WORKING_SUFFIX);
    }

    /**
     * Atomically completes one working file.
     *
     * @param workingPath working path, normally from {@link #workingPath(Path)}
     * @param completedPath completed generation path
     * @return {@code completedPath}
     * @throws UncheckedIOException if the same-filesystem atomic move cannot be completed
     */
    public static Path completeWorkingFile(final Path workingPath, final Path completedPath) {
        Objects.requireNonNull(workingPath, "workingPath");
        Objects.requireNonNull(completedPath, "completedPath");
        try {
            SecureStagedFiles.prepareContainerOf(completedPath);
            final Path sealed = Files.move(workingPath, completedPath,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // An atomic move carries the working file's owner-only mode with it, so this is a no-op on
            // the ordinary path. It is not a no-op where the destination pre-existed with a wider mode
            // or where the filesystem implements the move as a copy, and either of those would leave a
            // completed generation readable by every account on the host.
            // See docs/decision-log.md entry DL-178.
            SecureStagedFiles.applyOwnerOnly(sealed);
            return sealed;
        } catch (final IOException failure) {
            throw new UncheckedIOException("completed batch artifact could not be atomically sealed",
                    failure);
        }
    }

    /**
     * Registers one completed generation for publication when its job ends.
     *
     * @param stepExecution step that completed the artifact
     * @param logicalBase logical dataset or generation-group base
     * @param completedPath completed local generation
     * @param retentionLimit measured retained depth
     */
    public static void register(final StepExecution stepExecution, final String logicalBase,
            final Path completedPath, final int retentionLimit) {
        register(stepExecution, logicalBase, completedPath, retentionLimit, null);
    }

    /**
     * Registers one completed generation and a fixed-name local alias to replace after durable upload.
     *
     * @param stepExecution step that completed the artifact
     * @param logicalBase logical dataset or generation-group base
     * @param completedPath completed local generation
     * @param retentionLimit measured retained depth
     * @param alias fixed-name local view, or {@code null}
     */
    public static void register(final StepExecution stepExecution, final String logicalBase,
            final Path completedPath, final int retentionLimit, final Path alias) {
        Objects.requireNonNull(stepExecution, "stepExecution");
        final JobExecution jobExecution =
                Objects.requireNonNull(stepExecution.getJobExecution(), "jobExecution");
        final Long executionId = Objects.requireNonNull(stepExecution.getJobExecutionId(),
                "jobExecutionId");
        register(jobExecution, logicalBase, executionId.longValue(), completedPath,
                retentionLimit, alias);
    }

    /**
     * Registers one completed generation from a component that holds the job execution rather than a
     * step execution.
     *
     * <p>Used by an artefact whose lifecycle is the job's rather than a step's - a generation written by
     * one step and read by the next - which reaches this store when its stream closes and therefore has
     * no step execution in hand.
     *
     * @param jobExecution the execution the generation belongs to; must not be {@code null} and must
     *                     already carry the identifier the framework assigned
     * @param logicalBase logical dataset or generation-group base
     * @param completedPath completed local generation
     * @param retentionLimit measured retained depth
     */
    public static void register(final JobExecution jobExecution, final String logicalBase,
            final Path completedPath, final int retentionLimit) {
        Objects.requireNonNull(jobExecution, "jobExecution");
        final Long executionId = Objects.requireNonNull(jobExecution.getId(),
                "the framework must assign a job execution identifier before a generation is"
                        + " registered");
        register(jobExecution, logicalBase, executionId.longValue(), completedPath, retentionLimit,
                null);
    }

    /**
     * Wraps an item-stream writer so a successful close seals and registers its output generation.
     *
     * @param delegate writer that composes the working file
     * @param stepExecution step execution owning the file
     * @param logicalBase logical dataset or generation-group base
     * @param workingPath path the delegate writes
     * @param completedPath path published after the delegate closes
     * @param retentionLimit measured retained depth
     * @param <T> item type
     * @return the completing writer
     */
    public static <T> ItemStreamWriter<T> completingWriter(final ItemStreamWriter<T> delegate,
            final StepExecution stepExecution, final String logicalBase, final Path workingPath,
            final Path completedPath, final int retentionLimit) {
        return new CompletingItemStreamWriter<>(delegate, stepExecution, logicalBase,
                workingPath, completedPath, retentionLimit);
    }

    /**
     * Reports how many completed artifacts one job has registered.
     *
     * <p>The shared boundary listener uses this before resolving the optional store bean. A focused
     * batch test may intentionally create the listener without AWS infrastructure, which is valid while
     * no artifact was registered; a real job that registered output must fail rather than silently skip
     * its durable publication when the store is absent.</p>
     *
     * @param jobExecution job execution to inspect
     * @return registered artifact count
     */
    public static int registeredArtifactCount(final JobExecution jobExecution) {
        Objects.requireNonNull(jobExecution, "jobExecution");
        final ExecutionContext context = jobExecution.getExecutionContext();
        return context == null ? 0 : context.getInt(REGISTRY_COUNT, 0);
    }

    /**
     * Publishes every artifact registered by a successfully completed job.
     *
     * <p>The whole sequence runs with every touched base held, and everything that can fail runs before
     * one commit point. See the class comment for what that boundary guarantees and why retention sits
     * outside it.</p>
     *
     * @param jobExecution completed job execution
     * @return published artifacts in registration order
     */
    public List<PublishedGeneration> publishRegistered(final JobExecution jobExecution) {
        Objects.requireNonNull(jobExecution, "jobExecution");
        if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
            throw new IllegalStateException("registered batch artifacts may be published only after"
                    + " the owning job completed");
        }

        final List<ArtifactRegistration> registrations = registrationsOf(jobExecution);
        if (registrations.isEmpty()) {
            return List.of();
        }
        registrations.forEach(StagedGenerationStore::requireCompletedSource);

        // Filled by the locked body and read back once it returns. The lock takes a body that yields
        // nothing, so that it stays expressible as a lambda at every call site; the publication's result
        // therefore travels out in this list rather than as a return value.
        final List<PublishedGeneration> published = new ArrayList<>();
        this.publicationLock.whileHolding(basesOf(registrations),
                () -> commitPublication(jobExecution, registrations, published));
        return List.copyOf(published);
    }

    /**
     * Uploads, advances aliases, and only then commits - with one compensation path covering the whole
     * of what precedes the commit.
     *
     * <p>Both irreversible acts are deliberately positioned relative to that point. Retention deletion
     * cannot be undone, so it runs after the commit and cannot fail the job. Registry clearing must
     * happen exactly once for a publication that succeeded, so it runs after the commit too, before the
     * retention pass, so that a retention warning cannot be mistaken for an unpublished job.</p>
     *
     * @param jobExecution the completed job whose registrations are being published
     * @param registrations the validated registrations
     * @param published filled with one entry per uploaded object, in registration order
     */
    private void commitPublication(final JobExecution jobExecution,
            final List<ArtifactRegistration> registrations,
            final List<PublishedGeneration> published) {
        final String bucket = this.bucket;
        final List<AliasSnapshot> advancedAliases = new ArrayList<>();
        // One allocation ledger for the whole pass. A base is measured against the durable store once
        // and then advances within the pass, so two artifacts registered under one base receive two
        // generations rather than the same key twice.
        final Map<String, Long> allocatedByBase = new HashMap<>();
        // Every key this pass ATTEMPTS, recorded before its bytes are sent. See rollbackAttemptedKeys
        // for why the attempted set and not the succeeded set is what compensation has to cover.
        final List<String> attemptedKeys = new ArrayList<>();
        try {
            for (final ArtifactRegistration registration : registrations) {
                published.add(upload(bucket, registration, allocatedByBase, attemptedKeys::add));
            }
            for (final ArtifactRegistration registration : registrations) {
                final AliasSnapshot snapshot = replaceAlias(registration);
                if (snapshot != null) {
                    advancedAliases.add(snapshot);
                }
            }
        } catch (final RuntimeException failure) {
            // Compensation spans every step that precedes the commit, not just the uploads. An earlier
            // revision rolled back uploads only, so an alias that failed on the third artifact left the
            // first two objects in the bucket and the first two fixed-name views advanced, while the job
            // was marked FAILED - a failed job with externally visible output.
            restoreAliases(advancedAliases);
            rollbackAttemptedKeys(bucket, attemptedKeys);
            published.clear();
            throw failure;
        }

        // ---- COMMIT POINT. Every artifact is durable and every fixed-name view names it. ----
        discardAliasSnapshots(advancedAliases);
        clearRegistrations(jobExecution, registrations.size());
        LOGGER.info("Published {} completed batch artifact(s) for jobExecutionId={} to bucket {}",
                published.size(), jobExecution.getId(), bucket);
        enforceRetentionQuietly(bucket, retentionPoliciesOf(registrations));
    }

    /** The distinct bases one publication touches, which are the bases it must hold. */
    private static List<String> basesOf(final List<ArtifactRegistration> registrations) {
        final List<String> bases = new ArrayList<>(registrations.size());
        for (final ArtifactRegistration registration : registrations) {
            if (!bases.contains(registration.logicalBase())) {
                bases.add(registration.logicalBase());
            }
        }
        return List.copyOf(bases);
    }

    /** The distinct retention policies one publication must enforce. */
    private static Set<RetentionPolicy> retentionPoliciesOf(
            final List<ArtifactRegistration> registrations) {
        final Set<RetentionPolicy> policies = new HashSet<>();
        for (final ArtifactRegistration registration : registrations) {
            policies.add(new RetentionPolicy(registration.logicalBase(),
                    registration.retentionLimit()));
        }
        return policies;
    }

    /**
     * Immediately publishes one completed local generation, streaming it from the file rather than
     * holding it.
     *
     * <p>The distinction from {@link #publishRegistered(JobExecution)} is <em>when</em>, not how. A
     * registered artifact is published when its job completes, which suits a job whose generation must
     * appear only if the whole submission succeeded. This method publishes at the moment the producing
     * step closes its output, which is what a job stream that allocated and catalogued one new
     * generation per step did: the generation existed from the end of the step that wrote it, and a
     * later bypassed or failed step did not un-catalogue it.
     *
     * <p>Nothing is held in memory. The body is read from the file as it is uploaded, so a generation of
     * any size costs one buffer, and the reported length is the file's own length rather than the size
     * of an array that had to exist first. See {@code docs/decision-log.md} entry DL-176 for why this
     * replaced a byte-array publication.
     *
     * @param logicalBase logical generation-group base
     * @param completedPath completed local generation, which must not be a working file
     * @param retentionLimit measured retained depth
     * @return the published generation, carrying the key the allocation chose
     * @throws IllegalArgumentException if the path names a working file
     * @throws UncheckedIOException if the file cannot be measured or read
     */
    public PublishedGeneration publishFile(final String logicalBase, final Path completedPath,
            final int retentionLimit) {
        final String base = requireLogicalBase(logicalBase);
        requireRetentionLimit(retentionLimit);
        final Path source = Objects.requireNonNull(completedPath, "completedPath");
        if (source.toString().endsWith(WORKING_SUFFIX)) {
            throw new IllegalArgumentException("a working batch artifact cannot be published as"
                    + " complete");
        }
        final String bucket = this.bucket;
        // Held for the whole of allocate-upload-then-retention, exactly as the registered path is, and
        // for the same reason: this base is shared with the transaction-report job's unload step, so a
        // per-job lock would leave precisely this pair concurrent. Allocation is inside the lock because
        // it reads the very set the upload is about to add to.
        final AtomicReference<PublishedGeneration> result = new AtomicReference<>();
        this.publicationLock.whileHolding(List.of(base), () -> {
            final String key = base + OBJECT_KEY_SEPARATOR
                    + allocateGeneration(bucket, base, new HashMap<>());
            final long size;
            try {
                size = Files.size(source);
                try (InputStream body = Files.newInputStream(source)) {
                    observed(OPERATION_UPLOAD, key, () -> this.objectStore.upload(bucket, key, body));
                }
            } catch (final IOException | RuntimeException failure) {
                // The key is reconciled rather than assumed empty. An earlier revision stated that "a
                // failed upload uploaded nothing" and therefore compensated nothing; that is true of
                // every failure except the one that matters, an upload whose bytes the service accepted
                // and whose response was lost, which throws while leaving an ordinary visible current
                // generation of this base behind. The reasoning is developed in full on
                // rollbackAttemptedKeys, and the reconciliation is the same one: empty the key of every
                // version and delete marker, which removes nothing when nothing was written.
                rollbackAttemptedKeys(bucket, List.of(key));
                if (failure instanceof RuntimeException unchecked) {
                    throw unchecked;
                }
                throw new UncheckedIOException("completed batch artifact could not be published for "
                        + base, (IOException) failure);
            }
            // Nothing after this line may fail the step: the object is durable and visible, so a
            // retention problem is an over-depth base to be corrected next run, not an unpublished
            // artifact.
            result.set(new PublishedGeneration(bucket, key, size));
            enforceRetentionQuietly(bucket, Set.of(new RetentionPolicy(base, retentionLimit)));
        });
        return Objects.requireNonNull(result.get(),
                "publication completed without recording its published generation");
    }

    /**
     * Allocates the next durable generation token for one base, advancing a per-pass ledger.
     *
     * <p>The first allocation for a base measures the durable store; later allocations in the same pass
     * advance from what this pass has already handed out, so one publication cannot issue one key twice.
     * The caller must hold the base.
     *
     * @param bucket the destination bucket
     * @param logicalBase the base being published to
     * @param allocatedByBase the ledger of what this pass has already allocated, mutated in place
     * @return the generation token, in the recognisable absolute-generation form
     */
    private String allocateGeneration(final String bucket, final String logicalBase,
            final Map<String, Long> allocatedByBase) {
        final Long alreadyAllocated = allocatedByBase.get(logicalBase);
        final long next = alreadyAllocated == null
                ? highestDurableGeneration(bucket, logicalBase) + 1L
                : alreadyAllocated.longValue() + 1L;
        allocatedByBase.put(logicalBase, Long.valueOf(next));
        return generationToken(next);
    }

    /**
     * Reads the highest generation number the durable store already holds beneath one base.
     *
     * @param bucket the bucket to measure
     * @param logicalBase the base to measure
     * @return the highest generation number present, or one less than the first when the base is empty
     */
    private long highestDurableGeneration(final String bucket, final String logicalBase) {
        final String prefix = logicalBase + OBJECT_KEY_SEPARATOR;
        long highest = FIRST_GENERATION_NUMBER - 1L;
        final List<S3Resource> resources = Objects.requireNonNull(
                observed(OPERATION_LIST, prefix, () -> this.objectStore.listObjects(bucket, prefix)),
                "objectStore.listObjects must not return null");
        for (final S3Resource resource : resources) {
            if (resource == null || resource.getLocation() == null) {
                continue;
            }
            final Long generation =
                    generationNumberFromKey(prefix, resource.getLocation().getObject());
            if (generation != null && generation.longValue() > highest) {
                highest = generation.longValue();
            }
        }
        return highest;
    }

    /**
     * Resolves one logical base's <em>current</em> durable generation, reproducing the legacy relative
     * generation {@code (0)}.
     *
     * <p>A job stream that names {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)} is asking the catalog for
     * whichever generation of that base is newest at the moment the job is submitted. There is no
     * catalog here, so the durable store is the catalog: the highest generation number present beneath
     * the base is the current one. Only keys this class composed are considered, so an unrelated object
     * that happens to share the prefix can never be mistaken for a generation.
     *
     * <p>Answering with the key rather than the number keeps the caller out of key composition
     * entirely, which is the whole reason this class exists.
     *
     * @param  logicalBase the generation base whose current generation is wanted; must be a valid base
     * @return the object key of the current generation, or empty when the base holds none
     * @throws NullPointerException     if {@code logicalBase} is {@code null}
     * @throws IllegalArgumentException if {@code logicalBase} is not a valid base
     */
    public Optional<String> currentGenerationKey(final String logicalBase) {
        final String base = requireLogicalBase(logicalBase);
        final long highest = highestDurableGeneration(this.bucket, base);
        if (highest < FIRST_GENERATION_NUMBER) {
            return Optional.empty();
        }
        return Optional.of(base + OBJECT_KEY_SEPARATOR + generationToken(highest));
    }

    /**
     * Resolves one logical base's current generation on the local staging filesystem.
     *
     * <p>The local counterpart of {@link #currentGenerationKey(String)}, for a deployment whose
     * predecessor jobs have staged their generations locally and not yet published them - which is
     * exactly the state the five-link chain is in while it runs. Local names carry the same generation
     * token after a {@value #LOCAL_NAME_SEPARATOR} separator, so the highest token is the current
     * generation here too. Working files are excluded by construction: they end in
     * {@value #WORKING_SUFFIX} and therefore do not parse as a generation name.
     *
     * <h2>The name alone is not enough, and this is where that is enforced</h2>
     *
     * <p>Resolution here is by <em>name</em>: the highest generation token wins, and the token is part
     * of a filename. On a shared host that makes the answer partly the filesystem's rather than wholly
     * this store's, so a local actor able to write the staging root could plant a file - or a symbolic
     * link - named with a higher generation than any this store has allocated, and the next job to
     * consume that base would read the attacker's records as its own input. Two independent controls
     * stand between the name and the answer:
     * <ul>
     *   <li><strong>The grammar is exact and anchored.</strong> A candidate must be the base, the
     *       separator, and a generation token at the store's own fixed width - not merely a name that
     *       begins or ends the right way.</li>
     *   <li><strong>The candidate must be a trusted staged artefact</strong>, which
     *       {@link SecureStagedFiles#isTrustedStagedArtifact(Path, Path)} decides: a real regular file
     *       inspected without following links, a direct child of this root, owned by this process, and
     *       with no permission granted outside its owner's - including on the root itself, because a
     *       root anybody can write in makes today's ownership no evidence about tomorrow's file.</li>
     * </ul>
     *
     * <p>A candidate that parses but is not trusted is skipped and reported rather than silently
     * ignored: a well-named file in the staging root that this process does not own is exactly the
     * event an operator needs to see, and continuing the scan means a planted higher generation cannot
     * mask the genuine one below it.
     *
     * @param  stagingDirectory the local staging root to search; must not be {@code null}
     * @param  logicalBase      the generation base whose current generation is wanted
     * @return the path of the current local generation, or empty when the directory holds no trusted
     *         generation of that base
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code logicalBase} is not a valid base
     */
    public static Optional<Path> currentLocalGeneration(final Path stagingDirectory,
            final String logicalBase) {
        Objects.requireNonNull(stagingDirectory, "stagingDirectory");
        final String base = requireLogicalBase(logicalBase);
        final String prefix = base + LOCAL_NAME_SEPARATOR;
        if (!Files.isDirectory(stagingDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        Path newest = null;
        long highest = FIRST_GENERATION_NUMBER - 1L;
        try (Stream<Path> entries = Files.list(stagingDirectory)) {
            for (final Path entry : entries.toList()) {
                final Path name = entry.getFileName();
                if (name == null) {
                    continue;
                }
                final Long generation = generationNumberFromKey(prefix, name.toString());
                if (generation == null || generation.longValue() <= highest) {
                    continue;
                }
                if (!SecureStagedFiles.isTrustedStagedArtifact(stagingDirectory, entry)) {
                    // Named like a generation of this base and not one. Reported at warning level
                    // because on a correctly-provisioned root this cannot happen, and named by
                    // filename only - the content is not read and no attribute is echoed.
                    LOGGER.warn("Ignoring {} in the local staging root {}: it is named as a generation"
                                    + " of base {} but is not a regular file this process owns in an"
                                    + " owner-only root, so it is not a staged artifact of this"
                                    + " deployment", name, stagingDirectory, base);
                    continue;
                }
                highest = generation.longValue();
                newest = entry;
            }
        } catch (final IOException unreadableDirectory) {
            LOGGER.warn("The local staging root {} could not be searched for the current generation of"
                            + " base {}; searchFailure={}", stagingDirectory, base,
                    unreadableDirectory.getClass().getSimpleName());
            return Optional.empty();
        }
        return Optional.ofNullable(newest);
    }

    /**
     * Removes the local files one non-completing execution created, and nothing else.
     *
     * <p>Called by the job-boundary listener when a job did not complete. Publication is skipped for such
     * a job, so its completed generations name nothing durable and its working files name nothing at all;
     * both are the abnormal disposition of an allocation the job stream did not end normally with, and
     * leaving them behind accumulates one dead artifact per failed run.
     *
     * <h2>Selection is by registration, never by name</h2>
     *
     * <p>An earlier form of this method swept the staging root and deleted every regular file whose name
     * merely <em>contained</em> this execution's generation token. That is a deletion rule driven by a
     * predictable substring: the token is derived from a monotonically increasing framework identifier,
     * so its value is guessable, and any file whose name happened to contain it - one belonging to
     * something else entirely, or one deliberately named to contain it - was removed on the next failed
     * run. A cleanup path that can be aimed by choosing a filename is a worse problem than the dead
     * artefact it was written to remove.
     *
     * <p>Selection is therefore from the execution's <strong>own registry</strong>. Every completed
     * local generation this store hands out is registered on the job execution's context with its exact
     * absolute path, and this method deletes those paths and nothing else - plus, for each of them, the
     * one working sibling the store itself names, which is the completed path with the store's own
     * working suffix appended and cannot therefore be anything a third party chose. Every path is
     * checked against {@link SecureStagedFiles#isTrustedStagedArtifact(Path, Path)} immediately before
     * the delete, so a registered name whose file has since been replaced by a link or by another
     * account's file is left alone rather than followed.
     *
     * <p>A file this store allocated but never completed has no registration - registration happens at
     * completion - so it is not removed here. That is the safe direction for the omission to fall: an
     * unregistered working file is a nuisance, whereas deleting by guessed name is a vulnerability.
     *
     * <p>Nothing is raised: the job has already failed for its own reason, and that reason is the one an
     * operator must read.
     *
     * @param jobExecution the execution that ended without completing; must not be {@code null}
     * @param stagingDirectory the local staging root the registered paths must lie in; must not be
     *                         {@code null}
     * @return the number of local files removed
     */
    public static int discardLocalArtifactsOf(final JobExecution jobExecution,
            final Path stagingDirectory) {
        Objects.requireNonNull(jobExecution, "jobExecution");
        Objects.requireNonNull(stagingDirectory, "stagingDirectory");
        final Long executionId = jobExecution.getId();
        if (executionId == null || executionId.longValue() < 0L) {
            return 0;
        }
        int discarded = 0;
        for (final ArtifactRegistration registration : registrationsOf(jobExecution)) {
            final Path completed = registration.completedPath();
            discarded += discardRegistered(completed, stagingDirectory, executionId);
            discarded += discardRegistered(workingPath(completed), stagingDirectory, executionId);
        }
        return discarded;
    }

    /**
     * Deletes one path this execution registered, after re-establishing that it is still trustworthy.
     *
     * @param  target           the exact path to remove
     * @param  stagingDirectory the root the path must be a direct child of
     * @param  executionId      the execution the path was registered by, for the diagnostic
     * @return one when the file was removed, zero otherwise
     */
    private static int discardRegistered(final Path target, final Path stagingDirectory,
            final long executionId) {
        if (!SecureStagedFiles.isTrustedStagedArtifact(stagingDirectory, target)) {
            // Either it is already gone - the ordinary case for a working file that completed - or it is
            // no longer the file that was registered. Neither is a reason to delete anything.
            return 0;
        }
        try {
            if (Files.deleteIfExists(target)) {
                LOGGER.info("Discarded local batch artifact {} allocated by jobExecutionId={}, which"
                        + " did not complete", target.getFileName(), executionId);
                return 1;
            }
        } catch (final IOException failure) {
            LOGGER.warn("The local batch artifact {} allocated by jobExecutionId={} could not be"
                            + " removed after the job ended without completing; failureType={}",
                    target.getFileName(), executionId, failure.getClass().getSimpleName());
        }
        return 0;
    }

    /**
     * Registers a completed artifact on the job execution's serializable execution context.
     */
    private static void register(final JobExecution jobExecution, final String logicalBase,
            final long executionId, final Path completedPath, final int retentionLimit,
            final Path alias) {
        Objects.requireNonNull(jobExecution, "jobExecution");
        final String base = requireLogicalBase(logicalBase);
        requireExecutionId(executionId);
        requireRetentionLimit(retentionLimit);
        final Path source = Objects.requireNonNull(completedPath, "completedPath")
                .toAbsolutePath().normalize();
        if (source.toString().endsWith(WORKING_SUFFIX)) {
            throw new IllegalArgumentException("a working batch artifact cannot be registered as"
                    + " complete");
        }
        final String aliasValue = alias == null ? NO_ALIAS
                : alias.toAbsolutePath().normalize().toString();

        final ExecutionContext context =
                Objects.requireNonNull(jobExecution.getExecutionContext(), "executionContext");
        synchronized (context) {
            final int count = context.getInt(REGISTRY_COUNT, 0);
            for (int index = 0; index < count; index++) {
                if (source.toString().equals(context.getString(registryKey(index, FIELD_SOURCE)))
                        && base.equals(context.getString(registryKey(index, FIELD_BASE)))) {
                    return;
                }
            }
            context.putString(registryKey(count, FIELD_BASE), base);
            context.putLong(registryKey(count, FIELD_EXECUTION_ID), executionId);
            context.putString(registryKey(count, FIELD_SOURCE), source.toString());
            context.putString(registryKey(count, FIELD_ALIAS), aliasValue);
            context.putInt(registryKey(count, FIELD_RETENTION), retentionLimit);
            context.putInt(REGISTRY_COUNT, count + 1);
        }
    }

    /** Reads the typed registrations back from a job execution. */
    private static List<ArtifactRegistration> registrationsOf(final JobExecution jobExecution) {
        final ExecutionContext context =
                Objects.requireNonNull(jobExecution.getExecutionContext(), "executionContext");
        final int count = context.getInt(REGISTRY_COUNT, 0);
        final List<ArtifactRegistration> registrations = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            final String alias = context.getString(registryKey(index, FIELD_ALIAS), NO_ALIAS);
            registrations.add(new ArtifactRegistration(
                    context.getString(registryKey(index, FIELD_BASE)),
                    context.getLong(registryKey(index, FIELD_EXECUTION_ID)),
                    Path.of(context.getString(registryKey(index, FIELD_SOURCE))),
                    context.getInt(registryKey(index, FIELD_RETENTION)),
                    alias.isEmpty() ? null : Path.of(alias)));
        }
        return registrations;
    }

    /** Removes successfully published registrations so a repeated callback cannot publish twice. */
    private static void clearRegistrations(final JobExecution jobExecution, final int count) {
        final ExecutionContext context = jobExecution.getExecutionContext();
        for (int index = 0; index < count; index++) {
            context.remove(registryKey(index, FIELD_BASE));
            context.remove(registryKey(index, FIELD_EXECUTION_ID));
            context.remove(registryKey(index, FIELD_SOURCE));
            context.remove(registryKey(index, FIELD_ALIAS));
            context.remove(registryKey(index, FIELD_RETENTION));
        }
        context.remove(REGISTRY_COUNT);
    }

    /**
     * Uploads one validated local artifact under a newly allocated durable generation.
     *
     * <p>The stream handed to the object store comes from {@link #openVerifiedSource(ArtifactRegistration)}
     * rather than from a bare open of the registered path, so the bytes uploaded are the bytes of the file
     * that was verified and not of whatever the name resolves to by the time the upload runs.
     *
     * <p><strong>The key is handed to the ledger before the bytes are sent</strong>, and that ordering
     * is the whole of what {@code attemptedKey} is for. See
     * {@link #rollbackAttemptedKeys(String, List)} for why recording it afterwards leaves a hole that no
     * amount of exception handling closes.</p>
     *
     * @param  bucket          the destination bucket
     * @param  registration    the artifact to publish
     * @param  allocatedByBase the pass's own generation ledger, so two artifacts of one base get two keys
     * @param  attemptedKey    receives the composed key before the upload is attempted
     * @return what was published
     */
    private PublishedGeneration upload(final String bucket,
            final ArtifactRegistration registration, final Map<String, Long> allocatedByBase,
            final Consumer<String> attemptedKey) {
        final String key = registration.logicalBase() + OBJECT_KEY_SEPARATOR
                + allocateGeneration(bucket, registration.logicalBase(), allocatedByBase);
        // BEFORE the send, deliberately. A key recorded after the call returns is a key that is not
        // recorded when the call does not return.
        attemptedKey.accept(key);
        final long size;
        try {
            try (VerifiedSource source = openVerifiedSource(registration)) {
                size = source.byteCount();
                observed(OPERATION_UPLOAD, key,
                        () -> this.objectStore.upload(bucket, key, source.body()));
            }
        } catch (final IOException failure) {
            throw new UncheckedIOException("completed batch artifact could not be published for "
                    + registration.logicalBase(), failure);
        }
        return new PublishedGeneration(bucket, key, size);
    }

    /**
     * Opens one registered source for streaming, and establishes that the handle opened is the file that
     * was verified.
     *
     * <p><strong>The window this closes.</strong> Validating a path and then opening it later are two
     * resolutions of the same name, and between them a local actor able to write the staging root can
     * replace the entry. Nothing about the first resolution constrains the second, so a check that passed
     * says nothing about the bytes that are then read. Three things are therefore done here, in this order,
     * and all three are necessary:
     *
     * <ol>
     *   <li><strong>Re-establish trust immediately before the open</strong>, with the same four-part
     *       predicate the validation pass used - regular file inspected without following links, direct
     *       child of its own root, same owner as that root, and neither writable outside that owner.</li>
     *   <li><strong>Open with {@link LinkOption#NOFOLLOW_LINKS}</strong>, so the open itself refuses a
     *       symbolic link rather than quietly resolving one. Without it the open is a third resolution of
     *       the name and the first two are decoration.</li>
     *   <li><strong>Compare the filesystem identity across the open.</strong> The attributes are read
     *       no-follow before and after, and the two file keys must be the same object. An equal key on both
     *       sides means no replacement happened in the window and the open therefore resolved the verified
     *       inode; an unequal key means one did, and the stream is closed and the publication refused
     *       rather than uploading bytes from a file nobody verified.</li>
     * </ol>
     *
     * <p>The size is taken from the attributes read after the open rather than by a separate
     * {@code Files.size} call on the name, so the length reported for the published object describes the
     * same file whose bytes are streamed.
     *
     * <p>Where the filesystem exposes no file key the identity comparison cannot be made, and it is not
     * silently treated as satisfied: the two structural properties and the no-follow open still apply, and
     * the absence is reported once at debug level. See {@code docs/decision-log.md} entry DL-288.
     *
     * @param  registration the artifact whose bytes are to be streamed
     * @return the open stream together with the verified byte count
     * @throws IOException if the file cannot be inspected or opened
     */
    private static VerifiedSource openVerifiedSource(final ArtifactRegistration registration)
            throws IOException {

        final Path source = registration.completedPath();
        final Path root = source.getParent();
        if (root == null || !SecureStagedFiles.isTrustedStagedArtifact(root, source)) {
            throw new IOException("the completed batch artifact for " + registration.logicalBase()
                    + " is no longer a trusted staged artifact of its staging root, so its bytes are not"
                    + " the bytes this publication validated");
        }
        final BasicFileAttributes before =
                Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

        InputStream body = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
        try {
            final BasicFileAttributes after =
                    Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.fileKey() == null || after.fileKey() == null) {
                LOGGER.debug("The staging filesystem publishes no file key, so the identity of the"
                        + " opened handle for {} is established by the no-follow open and the trusted"
                        + " child check alone", registration.logicalBase());
            } else if (!before.fileKey().equals(after.fileKey())) {
                throw new IOException("the completed batch artifact for " + registration.logicalBase()
                        + " was replaced between being verified and being opened, so the handle does not"
                        + " name the file that was verified and its bytes are not published");
            }
            if (!after.isRegularFile()) {
                throw new IOException("the completed batch artifact for " + registration.logicalBase()
                        + " is not a regular file at the moment its bytes would be read");
            }
            final VerifiedSource verified = new VerifiedSource(body, after.size());
            body = null;
            return verified;
        } finally {
            if (body != null) {
                body.close();
            }
        }
    }

    /**
     * One open, identity-verified source: the stream whose bytes are uploaded and the size of the very file
     * that stream reads.
     *
     * @param body      the open stream, which the caller closes
     * @param byteCount the size read from the attributes of the opened file
     */
    private record VerifiedSource(InputStream body, long byteCount) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            this.body.close();
        }
    }

    /**
     * Runs one outbound object-store call inside an observation, and returns what it returned.
     *
     * <p>{@code observe} opens the scope, records a failure on the span before rethrowing it, and stops
     * the observation, so a refused call is visible as a failure rather than as a gap. The shape is the
     * one {@code service/BatchStagingService} already uses for the staging surface, so the two families
     * read alike even though they answer different questions.
     *
     * @param  <T>       the call's result type
     * @param  operation the operation tag value
     * @param  key       the key or prefix the call names, carried on the span
     * @param  call      the call to make
     * @return whatever the call returned
     */
    private <T> T observed(final String operation, final String key, final Supplier<T> call) {
        return Observation.createNotStarted(OBSERVATION_NAME, this.observationRegistry)
                .lowCardinalityKeyValue(TAG_OPERATION, operation)
                .highCardinalityKeyValue(TAG_OBJECT_KEY, key)
                .observe(call);
    }

    /**
     * Empties every key a failed publication pass <em>attempted</em>, <em>version by version</em>.
     *
     * <h2>Why the attempted set and not the succeeded set</h2>
     *
     * <p>An earlier revision compensated the list of generations the pass had successfully published,
     * built by appending each entry after its upload call returned. That list is exactly one entry short
     * in the case that matters most: an upload whose bytes the object store accepted and whose
     * <em>response</em> was lost. The call throws - a connection reset, a read timeout, an expiring call
     * budget - the entry is never appended, and the compensation that follows walks every key but that
     * one. The failed job then leaves an ordinary, visible, current generation of its base behind, and
     * nothing distinguishes it from real output: the generation-number allocation counts it, so the next
     * run allocates past it, and a reader asking the store for the current generation of that base is
     * handed the artefact of a job that failed.
     *
     * <p>No amount of exception handling closes that hole, because the information the handler needs -
     * whether the service kept the bytes - is precisely what a lost response does not carry. The
     * ordering is the fix: the key is recorded in {@link #upload} <em>before</em> the send, so the set
     * compensated here is the set of keys that <em>might</em> hold bytes rather than the set known to.
     *
     * <p>Emptying a key that holds nothing is harmless and is the normal case: an upload that failed
     * before the service accepted anything leaves the key empty, the listing returns nothing, and the
     * pass over it removes nothing. Reconciling is therefore cheap in the ordinary failure and correct in
     * the ambiguous one, which is the trade a compensation should make.
     *
     * <h2>Why each key is emptied of every version rather than deleted</h2>
     *
     * <p>Developed below in {@link #purgeEveryVersionOf(String, String)}: against a versioned bucket an
     * unqualified delete is not a delete. Every version and every delete marker under the key is
     * removed, which is what makes a failed job leave nothing externally visible rather than nothing
     * visible to a reader who never asks for a version.
     *
     * <p>Removing <em>every</em> version under the key rather than only what this pass wrote is
     * deliberate and safe: a generation number is allocated as one more than the highest the base
     * currently holds, so a key this pass allocated held nothing beforehand, and anything under it now
     * either came from this pass or is residue no correct publication could have left.
     *
     * <p>Best-effort by necessity - the publication is already failing and this must not replace the
     * reason it failed - but each failure is reported rather than absorbed.
     *
     * <p>See {@code docs/decision-log.md} entry DL-303 for the whole of this reasoning, including why no
     * exception handling can substitute for the ordering and how the ambiguous outcome is tested.
     *
     * @param bucket        the bucket the pass uploaded into
     * @param attemptedKeys every key the pass attempted, in attempt order
     */
    private void rollbackAttemptedKeys(final String bucket, final List<String> attemptedKeys) {
        for (final String objectKey : attemptedKeys) {
            try {
                final int removed = purgeEveryVersionOf(bucket, objectKey);
                if (removed > 0) {
                    LOGGER.info("Rolled back object {} after batch artifact publication failed,"
                            + " removing {} version(s) and delete marker(s)", objectKey, removed);
                } else {
                    LOGGER.debug("Attempted object {} held nothing after batch artifact publication"
                            + " failed, so the upload left no bytes to remove", objectKey);
                }
            } catch (final RuntimeException rollbackFailure) {
                LOGGER.warn("Could not roll back object {} after batch artifact publication failed;"
                                + " the object may remain retrievable by version identifier."
                                + " rollbackFailure={}", objectKey,
                        rollbackFailure.getClass().getSimpleName());
            }
        }
    }

    /**
     * Removes every version and every delete marker the store holds under one exact key.
     *
     * <p>Listing is by prefix because that is the only listing the service offers over versions, and the
     * result is then filtered to <strong>exact key equality</strong>: a prefix listing of
     * {@code base/G0000000001V00} would otherwise also match a longer key beginning with those characters,
     * and this method must never touch a key its caller did not name. The listing is followed to
     * exhaustion, because a key that has been rewritten many times can hold more versions than one page
     * returns.
     *
     * <p>Delete markers are versions and are removed the same way. Removing them is what leaves the key
     * genuinely unlisted rather than merely hidden, and it is also what stops an accumulation of markers
     * from confusing the generation-number allocation that lists the base.
     *
     * @param  bucket the bucket to remove from
     * @param  key    the exact object key to empty
     * @return how many versions and delete markers were removed
     */
    private int purgeEveryVersionOf(final String bucket, final String key) {
        int removed = 0;
        String keyMarker = null;
        String versionIdMarker = null;
        do {
            final ListObjectVersionsRequest.Builder request = ListObjectVersionsRequest.builder()
                    .bucket(bucket)
                    .prefix(key);
            if (keyMarker != null) {
                request.keyMarker(keyMarker);
            }
            if (versionIdMarker != null) {
                request.versionIdMarker(versionIdMarker);
            }
            final ListObjectVersionsRequest versionListing = request.build();
            final ListObjectVersionsResponse listed = observed(OPERATION_LIST_VERSIONS, key,
                    () -> this.versionedObjectStore.listObjectVersions(versionListing));

            for (final ObjectVersion version : listed.versions()) {
                if (key.equals(version.key())) {
                    deleteExactVersion(bucket, key, version.versionId());
                    removed++;
                }
            }
            for (final DeleteMarkerEntry marker : listed.deleteMarkers()) {
                if (key.equals(marker.key())) {
                    deleteExactVersion(bucket, key, marker.versionId());
                    removed++;
                }
            }

            keyMarker = Boolean.TRUE.equals(listed.isTruncated()) ? listed.nextKeyMarker() : null;
            versionIdMarker = keyMarker == null ? null : listed.nextVersionIdMarker();
        } while (keyMarker != null);
        return removed;
    }

    /**
     * Deletes one exact version of one exact key.
     *
     * <p>The version identifier is what makes this a deletion rather than a concealment. A request that
     * omitted it would be accepted, would report success, and would add a marker instead of removing
     * anything.
     *
     * @param bucket    the bucket to remove from
     * @param key       the object key
     * @param versionId the version or delete-marker identifier to remove
     */
    private void deleteExactVersion(final String bucket, final String key, final String versionId) {
        final DeleteObjectRequest removal = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .versionId(versionId)
                .build();
        observed(OPERATION_DELETE_VERSION, key,
                () -> this.versionedObjectStore.deleteObject(removal));
    }

    /**
     * Replaces a fixed-name local alias through a private, execution-specific sibling, preserving what
     * the alias named beforehand so the replacement can be undone.
     *
     * <p>Advancing a fixed-name view is a publication act: a consumer that reads the alias reads whatever
     * it last named. If a later step of the same publication fails and the durable objects are rolled
     * back, an alias left naming the new generation would be an externally visible artifact of a job that
     * failed - the local half of exactly the defect the upload rollback closes. So the previous content
     * is set aside first and the caller is handed what it needs to put it back.</p>
     *
     * @param registration the artifact whose alias is being advanced
     * @return a snapshot the caller must either discard on commit or restore on failure, or {@code null}
     *         when this artifact has no alias to advance
     */
    private static AliasSnapshot replaceAlias(final ArtifactRegistration registration) {
        final Path alias = registration.alias();
        if (alias == null || alias.equals(registration.completedPath())) {
            return null;
        }
        final Path aliasContainer = alias.getParent();
        final Path aliasName = Objects.requireNonNull(alias.getFileName(), "alias file name");
        final String generation = generationToken(registration.executionId());
        final Path temporaryAlias = alias.resolveSibling(aliasName + ".publish-" + generation);
        final Path preservedAlias = alias.resolveSibling(aliasName + ".pre-publish-" + generation);
        boolean aliasPreExisted = false;
        try {
            if (aliasContainer != null) {
                SecureStagedFiles.prepareDirectory(aliasContainer);
            }
            // Set the previous view aside by copy rather than by move, so that a failure between here
            // and the atomic replacement below leaves the alias itself untouched and complete.
            if (Files.isRegularFile(alias)) {
                Files.copy(alias, preservedAlias, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                SecureStagedFiles.applyOwnerOnly(preservedAlias);
                aliasPreExisted = true;
            }
            Files.copy(registration.completedPath(), temporaryAlias,
                    StandardCopyOption.REPLACE_EXISTING);
            // A copy without the attribute option creates its target at the process umask rather than
            // at the source's mode, so the alias - which is a byte-for-byte duplicate of the completed
            // generation - would otherwise be the one readable copy of it. Secured before the move,
            // which then carries the mode across.
            SecureStagedFiles.applyOwnerOnly(temporaryAlias);
            Files.move(temporaryAlias, alias, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException failure) {
            deleteQuietly(preservedAlias, registration.logicalBase());
            throw new UncheckedIOException("fixed-name batch artifact alias could not be atomically"
                    + " replaced for " + registration.logicalBase(), failure);
        } finally {
            deleteQuietly(temporaryAlias, registration.logicalBase());
        }
        return new AliasSnapshot(registration.logicalBase(), alias,
                aliasPreExisted ? preservedAlias : null);
    }

    /**
     * Puts every advanced alias back the way the publication found it.
     *
     * <p>Best-effort by necessity - the publication is already failing and this cannot be allowed to
     * replace the reason it failed - but each outcome is reported rather than absorbed silently, because
     * an alias that could not be restored is a local view naming a generation the durable store no
     * longer holds, and an operator has to be able to see that.</p>
     *
     * @param advancedAliases snapshots taken by {@link #replaceAlias(ArtifactRegistration)}
     */
    private static void restoreAliases(final List<AliasSnapshot> advancedAliases) {
        for (final AliasSnapshot snapshot : advancedAliases) {
            try {
                if (snapshot.preservedContent() == null) {
                    // The alias did not exist before this publication created it, so putting it back
                    // means removing it.
                    Files.deleteIfExists(snapshot.alias());
                } else {
                    Files.move(snapshot.preservedContent(), snapshot.alias(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (final IOException restoreFailure) {
                LOGGER.warn("Could not restore the previous fixed-name view of {} after batch artifact"
                                + " publication failed; it still names the unpublished generation."
                                + " restoreFailure={}", snapshot.logicalBase(),
                        restoreFailure.getClass().getSimpleName());
            }
        }
    }

    /** Drops the preserved previous views once the publication has committed. */
    private static void discardAliasSnapshots(final List<AliasSnapshot> advancedAliases) {
        for (final AliasSnapshot snapshot : advancedAliases) {
            if (snapshot.preservedContent() != null) {
                deleteQuietly(snapshot.preservedContent(), snapshot.logicalBase());
            }
        }
    }

    /** Removes one of this class's own temporary siblings, reporting rather than raising on failure. */
    private static void deleteQuietly(final Path temporary, final String logicalBase) {
        try {
            Files.deleteIfExists(temporary);
        } catch (final IOException cleanupFailure) {
            LOGGER.warn("Could not remove temporary alias file for {}; cleanupFailure={}",
                    logicalBase, cleanupFailure.getClass().getSimpleName());
        }
    }

    /**
     * Enforces every policy of a committed publication without being able to fail it.
     *
     * <p>Retention deletion is irreversible, so it cannot participate in the compensation that protects
     * everything before the commit point, and it must therefore run after it. Raising from here would
     * leave only bad choices: mark a job FAILED whose artifacts are correctly published and visible, or
     * compensate by deleting those correct artifacts. A base left one generation over depth is a smaller
     * and self-correcting problem - the next successful publication for that base measures depth from the
     * state it inherits and prunes what this pass could not - so the failure is reported and the
     * publication stands.</p>
     *
     * @param bucket destination bucket
     * @param policies the distinct policies this publication is responsible for
     */
    private void enforceRetentionQuietly(final String bucket, final Set<RetentionPolicy> policies) {
        for (final RetentionPolicy policy : policies) {
            try {
                enforceRetention(bucket, policy);
            } catch (final RuntimeException retentionFailure) {
                LOGGER.warn("Retention could not be enforced for generation base {} at depth {} after a"
                                + " successful publication; the base may hold more than {} generation(s)"
                                + " until the next successful publication. retentionFailure={}",
                        policy.logicalBase(), policy.retentionLimit(), policy.retentionLimit(),
                        retentionFailure.getClass().getSimpleName());
            }
        }
    }

    /** Enforces one generation base's durable retention policy. */
    private void enforceRetention(final String bucket, final RetentionPolicy policy) {
        final String prefix = policy.logicalBase() + OBJECT_KEY_SEPARATOR;
        final List<RemoteGeneration> generations = new ArrayList<>();
        final List<S3Resource> resources = Objects.requireNonNull(
                observed(OPERATION_LIST, prefix, () -> this.objectStore.listObjects(bucket, prefix)),
                "objectStore.listObjects must not return null");
        for (final S3Resource resource : resources) {
            if (resource == null || resource.getLocation() == null) {
                continue;
            }
            final String key = resource.getLocation().getObject();
            final Long generationNumber = generationNumberFromKey(prefix, key);
            if (generationNumber != null) {
                generations.add(new RemoteGeneration(key, generationNumber.longValue()));
            }
        }
        generations.sort(Comparator.comparingLong(RemoteGeneration::generationNumber)
                .reversed()
                .thenComparing(RemoteGeneration::objectKey));

        for (int index = policy.retentionLimit(); index < generations.size(); index++) {
            final RemoteGeneration expired = generations.get(index);
            // Scratched, not hidden. The legacy generation group was defined to SCRATCH a generation that
            // rolled off its declared depth, which deletes the data set rather than cataloguing it
            // elsewhere. An unqualified delete against a versioned bucket writes a delete marker and
            // leaves every version of the rolled-off generation fetchable by version identifier, so a
            // base at depth five would still be holding the bytes of every generation it ever held. See
            // docs/decision-log.md entry DL-287.
            final int removed = purgeEveryVersionOf(bucket, expired.objectKey());
            LOGGER.info("Scratched rolled-off object {} from bucket {} at retention depth {}, removing"
                    + " {} version(s) and delete marker(s)",
                    expired.objectKey(), bucket, policy.retentionLimit(), removed);
        }
    }

    /** Extracts a generation number only from keys produced by this class. */
    private static Long generationNumberFromKey(final String prefix, final String key) {
        if (key == null || !key.startsWith(prefix)) {
            return null;
        }
        final String token = key.substring(prefix.length());
        if (token.length() < 5 || token.charAt(0) != 'G' || !token.endsWith("V00")) {
            return null;
        }
        final String digits = token.substring(1, token.length() - 3);
        if (digits.isEmpty()) {
            return null;
        }
        for (int position = 0; position < digits.length(); position++) {
            final char character = digits.charAt(position);
            if (character < '0' || character > '9') {
                return null;
            }
        }
        final long parsed;
        try {
            parsed = Long.parseLong(digits);
        } catch (final NumberFormatException outsideLongRange) {
            return null;
        }
        // The grammar is anchored by round trip rather than by pattern: the token must be exactly what
        // this store would have written for that number. That refuses every non-canonical spelling of
        // the same value at once - a short form, an over-padded form, a form carrying a sign - without
        // enumerating them, and it cannot drift from the writer because it calls the writer.
        final String canonical;
        try {
            canonical = generationToken(parsed);
        } catch (final IllegalArgumentException outsideAllocatableRange) {
            return null;
        }
        return canonical.equals(token) ? Long.valueOf(parsed) : null;
    }

    /**
     * Confirms one registered source is still a trusted staged artifact of its own staging root.
     *
     * <p><strong>Why {@code Files.isRegularFile} alone was not enough.</strong> At its default that call
     * follows a symbolic link and answers about the <em>target</em>, so a registered name replaced since
     * registration by a link pointing anywhere at all passed this check, and the publication then read the
     * link's target and uploaded it to the object store under the job's own generation key. A registered
     * path is a name, and a name is not the file it named a moment ago.
     *
     * <p>The predicate applied instead is {@link SecureStagedFiles#isTrustedStagedArtifact(Path, Path)},
     * which asks four things together: that the candidate is a real regular file inspected <em>without</em>
     * following links, that its normalised parent is the root it is being trusted as a child of, that it and
     * the root share an owner, and that neither grants write permission outside that owner. The root is the
     * registered path's own normalised parent, which is the staging directory the store itself composed the
     * generation name inside; requiring the file to be a direct child of a directory that is itself
     * owner-trustworthy is what closes the case where the root is somebody else's to write in.
     *
     * <p>This is the check <em>before</em> the uploads begin, and it is deliberately not the last one: the
     * path is re-established immediately before its bytes are streamed, because anything verified here and
     * used later is verified across a window. See {@link #openVerifiedSource(ArtifactRegistration)} and
     * {@code docs/decision-log.md} entry DL-288.
     *
     * @param registration the registration whose source is being validated
     */
    private static void requireCompletedSource(final ArtifactRegistration registration) {
        final Path source = registration.completedPath();
        final Path root = source.getParent();
        if (root == null || !SecureStagedFiles.isTrustedStagedArtifact(root, source)) {
            throw new IllegalStateException("completed batch artifact for "
                    + registration.logicalBase() + " is not a trusted staged artifact of its staging"
                    + " root: it must be a regular file - inspected without following links - that is a"
                    + " direct child of a directory this process owns, with neither the file nor the"
                    + " directory writable outside its owner. A registered name that has since become a"
                    + " link, a directory, or another account's file is refused rather than followed,"
                    + " because publishing it would upload whatever it now points at under this job's"
                    + " own generation key");
        }
    }

    /** Creates the local filename of one completed generation. */
    private static String localGenerationName(final String logicalBase, final long executionId) {
        return requireLogicalBase(logicalBase) + LOCAL_NAME_SEPARATOR
                + generationToken(executionId);
    }

    /**
     * Creates the generation token, which names a local file from an execution identifier and a durable
     * object from an allocated generation number.
     */
    private static String generationToken(final long generationNumber) {
        requireExecutionId(generationNumber);
        return String.format(Locale.ROOT, GENERATION_TOKEN_FORMAT, generationNumber);
    }

    /** Validates a logical base before it is used as a path component and an object-key prefix. */
    private static String requireLogicalBase(final String logicalBase) {
        Objects.requireNonNull(logicalBase, "logicalBase");
        if (logicalBase.isBlank()) {
            throw new IllegalArgumentException("logicalBase must name a batch artifact");
        }
        if (logicalBase.length() > LOGICAL_BASE_MAX_LENGTH) {
            throw new IllegalArgumentException("logicalBase is too long to leave room for a"
                    + " generation token inside an object key");
        }
        for (int position = 0; position < logicalBase.length(); position++) {
            final char character = logicalBase.charAt(position);
            final boolean permitted = character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '.' || character == '-' || character == '_';
            if (!permitted) {
                throw new IllegalArgumentException("logicalBase contains a character outside letters,"
                        + " digits, dots, hyphens and underscores at position " + (position + 1));
            }
        }
        return logicalBase;
    }

    /** Refuses an identifier the framework could not have assigned. */
    private static void requireExecutionId(final long executionId) {
        if (executionId < 0) {
            throw new IllegalArgumentException("executionId must not be negative");
        }
    }

    /** Refuses a retention policy that could retain no generation at all. */
    private static void requireRetentionLimit(final int retentionLimit) {
        if (retentionLimit < 1) {
            throw new IllegalArgumentException("retentionLimit must retain at least one generation");
        }
    }

    /** Builds one indexed execution-context key. */
    private static String registryKey(final int index, final String field) {
        return REGISTRY_PREFIX + index + '.' + field;
    }

    /**
     * One object durably published by this store.
     *
     * @param bucket destination bucket
     * @param objectKey collision-free object key
     * @param contentLength complete external length in bytes
     */
    public record PublishedGeneration(String bucket, String objectKey, long contentLength) {

        /** Validates the store's own result. */
        public PublishedGeneration {
            Objects.requireNonNull(bucket, "bucket");
            Objects.requireNonNull(objectKey, "objectKey");
            if (contentLength < 0) {
                throw new IllegalArgumentException("contentLength must not be negative");
            }
        }
    }

    /** Serializable registration reconstructed from primitive execution-context fields. */
    private record ArtifactRegistration(
            String logicalBase,
            long executionId,
            Path completedPath,
            int retentionLimit,
            Path alias) {
    }

    /** A generation base and the number of its newest objects to retain. */
    private record RetentionPolicy(String logicalBase, int retentionLimit) {
    }

    /**
     * What one fixed-name alias named before a publication advanced it.
     *
     * @param logicalBase the base whose alias was advanced, for diagnostics only
     * @param alias the fixed-name view that now names the new generation
     * @param preservedContent the set-aside previous content, or {@code null} when the alias did not
     *                         exist before this publication created it
     */
    private record AliasSnapshot(String logicalBase, Path alias, Path preservedContent) {
    }

    /** One generation discovered beneath a durable prefix. */
    private record RemoteGeneration(String objectKey, long generationNumber) {
    }

    /**
     * Writer adapter that completes and registers only after its delegate closes successfully.
     *
     * <h2>A step that never opened its writer has no artifact to seal</h2>
     *
     * <p>The framework closes every registered stream of a step whether that step succeeded or failed,
     * including a step that failed while <em>opening</em> a different stream - a strict reader refusing a
     * missing input, for instance. In that case the delegate never created its working file, so sealing
     * would move a file that does not exist and the resulting failure would arrive from this adapter and
     * replace the reader's own diagnostic, which is the one an operator needs. Absence of the working
     * file is therefore treated as "this step produced nothing", reported once at warning level and
     * followed by neither a seal nor a registration.
     *
     * <p>That cannot hide a real artifact. The writers this adapter wraps create their output when the
     * stream opens and are configured not to remove it when nothing was written, so a step that opened
     * its writer always leaves a working file - even one whose dataset is empty, which is itself a
     * result the legacy allocation produced too. A missing working file consequently means the delegate
     * never opened, and never opening is only reachable from a failure the step is already reporting.
     *
     * @param <T> item type
     */
    private static final class CompletingItemStreamWriter<T> implements ItemStreamWriter<T> {

        private final ItemStreamWriter<T> delegate;
        private final StepExecution stepExecution;
        private final String logicalBase;
        private final Path workingPath;
        private final Path completedPath;
        private final int retentionLimit;

        private CompletingItemStreamWriter(final ItemStreamWriter<T> delegate,
                final StepExecution stepExecution, final String logicalBase,
                final Path workingPath, final Path completedPath, final int retentionLimit) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.stepExecution = Objects.requireNonNull(stepExecution, "stepExecution");
            this.logicalBase = requireLogicalBase(logicalBase);
            this.workingPath = Objects.requireNonNull(workingPath, "workingPath");
            this.completedPath = Objects.requireNonNull(completedPath, "completedPath");
            requireRetentionLimit(retentionLimit);
            this.retentionLimit = retentionLimit;
        }

        @Override
        public void open(final ExecutionContext executionContext) {
            this.delegate.open(executionContext);
        }

        @Override
        public void update(final ExecutionContext executionContext) {
            this.delegate.update(executionContext);
        }

        @Override
        public void write(final Chunk<? extends T> chunk) throws Exception {
            this.delegate.write(chunk);
        }

        @Override
        public void close() {
            try {
                this.delegate.close();
            } catch (final RuntimeException closeFailure) {
                // A DELEGATE THAT FAILED TO CLOSE LEAVES A PARTIAL FILE THAT NOTHING OWNS. Registration
                // happens at completion, so a working file abandoned here appears in no execution's
                // registry, and the job-boundary listener's cleanup - which deletes only registered
                // paths - cannot identify it. It is a partial copy of whatever the step was writing,
                // sitting in the staging root at whatever length the failure left it, until an operator
                // notices. Discarded here, where its path is known and trusted, rather than left for a
                // sweep that would have to guess at names. See docs/decision-log.md entry DL-289.
                discardWorkingFile("the writer's delegate failed to close");
                throw closeFailure;
            }
            if (!Files.exists(this.workingPath)) {
                LOGGER.warn("No working file was composed for logical base {} by jobExecutionId={},"
                                + " so nothing is sealed and nothing is registered; the step's own"
                                + " failure is the one to read",
                        this.logicalBase, this.stepExecution.getJobExecutionId());
                return;
            }
            try {
                completeWorkingFile(this.workingPath, this.completedPath);
            } catch (final RuntimeException sealFailure) {
                // Same reasoning as above, for the other failure that leaves a working file behind: the
                // atomic completion itself. The move is what would have made the file a registered
                // generation, so a move that did not happen leaves the same unowned partial file.
                discardWorkingFile("the working file could not be atomically sealed");
                throw sealFailure;
            }
            register(this.stepExecution, this.logicalBase, this.completedPath, this.retentionLimit);
        }

        /**
         * Removes this writer's own working file after a failure, and never raises.
         *
         * <p>The path is not guessed and is not matched by name: it is the exact working path this writer
         * was constructed with, which the store composed from the completed generation name. It is checked
         * against {@link SecureStagedFiles#isTrustedStagedArtifact(Path, Path)} immediately before the
         * delete, so a name that has since become a link, a directory or another account's file is left
         * alone rather than followed - the same rule the registered-path cleanup applies, and for the same
         * reason.
         *
         * <p>Nothing is raised from here under any circumstance. The caller is already propagating the
         * failure an operator has to read, and a cleanup problem must not replace it. It is reported at
         * warning level instead, naming the file so the residue can be found by hand if the delete could
         * not happen.
         *
         * @param reason what failed, for the diagnostic
         */
        private void discardWorkingFile(final String reason) {
            final Path root = this.workingPath.getParent();
            try {
                if (root != null
                        && SecureStagedFiles.isTrustedStagedArtifact(root, this.workingPath)
                        && Files.deleteIfExists(this.workingPath)) {
                    LOGGER.warn("Discarded the partial working file {} for logical base {} because {};"
                                    + " it was never registered, so no later cleanup could have"
                                    + " identified it",
                            this.workingPath.getFileName(), this.logicalBase, reason);
                }
            } catch (final IOException | RuntimeException cleanupFailure) {
                LOGGER.warn("The partial working file {} for logical base {} could not be removed after"
                                + " {}; it remains in the staging root and is registered nowhere."
                                + " cleanupFailure={}",
                        this.workingPath.getFileName(), this.logicalBase, reason,
                        cleanupFailure.getClass().getSimpleName());
            }
        }
    }
}
