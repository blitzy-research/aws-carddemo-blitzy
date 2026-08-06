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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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
 * {@code <logical-base>/G<execution-id>V00}, where the execution identifier is rendered with at least
 * ten digits and is never reduced modulo a smaller range. The result is stable, sortable and collision
 * free for the lifetime of the batch repository. It also preserves the recognisable absolute-generation
 * vocabulary without reproducing the four-digit wrap that caused the reviewed collision.
 *
 * <h2>Partial files are never publishable</h2>
 *
 * <p>Writers target a sibling ending in {@value #WORKING_SUFFIX}. A successful close moves that file
 * onto the completed generation with one same-filesystem atomic rename and only then registers it. A
 * failed writer leaves at most a working file, whose suffix is outside both the publication registry and
 * the retention scan. Another execution therefore cannot upload, prune or consume a file that is still
 * being written.
 *
 * <h2>Retention is applied to the durable store, one base at a time</h2>
 *
 * <p>The measured default depth is {@value #STANDARD_RETENTION_LIMIT}; the transaction-report base is
 * the one measured exception at {@value #REPORT_RETENTION_LIMIT}. After all artifacts of a completed job
 * have uploaded, matching generations are sorted by their numeric execution identifier and every object
 * beyond the declared depth is scratched. Non-generation objects beneath the same prefix are ignored.
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
 * advanced is put back, every object it uploaded is deleted, and the exception is returned to the boundary
 * listener, which marks the job failed before the framework persists its terminal status. <strong>A failed
 * job therefore leaves nothing externally visible</strong> - neither a durable object nor a fixed-name
 * local view naming a generation that was never published.
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
 * <p>See {@code docs/decision-log.md} entry DL-180 for the commit boundary and DL-181 for why exclusion is
 * per base rather than per job.
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

    /** The recognisable generation token, widened so execution identifiers never wrap. */
    private static final String GENERATION_TOKEN_FORMAT = "G%010dV00";

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

    /** Validated destination bucket, captured once so every operation addresses the same resource. */
    private final String bucket;

    /** Serializes publication per generation base so two publications cannot interleave. */
    private final GenerationPublicationLock publicationLock;

    /**
     * @param objectStore object-store operations; must not be {@code null}
     * @param configuredBucket configured batch-staging bucket; must not be blank
     * @param publicationLock per-base publication lock; must not be {@code null}
     */
    public StagedGenerationStore(final S3Operations objectStore,
            @Value("${" + BATCH_STAGING_BUCKET_PROPERTY + "}")
            final String configuredBucket,
            final GenerationPublicationLock publicationLock) {
        this.objectStore = Objects.requireNonNull(objectStore, "objectStore");
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
        try {
            for (final ArtifactRegistration registration : registrations) {
                published.add(upload(bucket, registration));
            }
            for (final ArtifactRegistration registration : registrations) {
                final AliasSnapshot snapshot = replaceAlias(registration);
                if (snapshot != null) {
                    advancedAliases.add(snapshot);
                }
            }
        } catch (final RuntimeException failure) {
            // Compensation now spans every step that precedes the commit, not just the uploads. An
            // earlier revision rolled back uploads only, so an alias that failed on the third artifact
            // left the first two objects in the bucket and the first two fixed-name views advanced,
            // while the job was marked FAILED - a failed job with externally visible output.
            restoreAliases(advancedAliases);
            rollbackUploads(bucket, published);
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
     * @param executionId framework execution identifier
     * @param completedPath completed local generation, which must not be a working file
     * @param retentionLimit measured retained depth
     * @return the published generation
     * @throws IllegalArgumentException if the path names a working file
     * @throws UncheckedIOException if the file cannot be measured or read
     */
    public PublishedGeneration publishFile(final String logicalBase, final long executionId,
            final Path completedPath, final int retentionLimit) {
        final String base = requireLogicalBase(logicalBase);
        requireExecutionId(executionId);
        requireRetentionLimit(retentionLimit);
        final Path source = Objects.requireNonNull(completedPath, "completedPath");
        if (source.toString().endsWith(WORKING_SUFFIX)) {
            throw new IllegalArgumentException("a working batch artifact cannot be published as"
                    + " complete");
        }
        final String bucket = this.bucket;
        final String key = objectKey(base, executionId);
        // Held for the whole of upload-then-retention, exactly as the registered path is, and for the
        // same reason: this base is shared with the transaction-report job's unload step, so a per-job
        // lock would leave precisely this pair concurrent.
        final AtomicReference<PublishedGeneration> result = new AtomicReference<>();
        this.publicationLock.whileHolding(List.of(base), () -> {
            final long size;
            try {
                size = Files.size(source);
                try (InputStream body = Files.newInputStream(source)) {
                    this.objectStore.upload(bucket, key, body);
                }
            } catch (final IOException failure) {
                throw new UncheckedIOException("completed batch artifact could not be published for "
                        + base, failure);
            }
            // Nothing after this line may fail the step: the object is durable and visible, so a
            // retention problem is an over-depth base to be corrected next run, not an unpublished
            // artifact. There is no compensation to perform because a failed upload uploaded nothing.
            result.set(new PublishedGeneration(bucket, key, size));
            enforceRetentionQuietly(bucket, Set.of(new RetentionPolicy(base, retentionLimit)));
        });
        return Objects.requireNonNull(result.get(),
                "publication completed without recording its published generation");
    }

    /**
     * Returns the durable key of one execution's generation.
     *
     * @param logicalBase logical generation-group or dataset base
     * @param executionId framework execution identifier
     * @return object key
     */
    public static String objectKey(final String logicalBase, final long executionId) {
        return requireLogicalBase(logicalBase) + OBJECT_KEY_SEPARATOR + generationToken(executionId);
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

    /** Uploads one validated local artifact. */
    private PublishedGeneration upload(final String bucket,
            final ArtifactRegistration registration) {
        final String key = objectKey(registration.logicalBase(), registration.executionId());
        final long size;
        try {
            size = Files.size(registration.completedPath());
            try (InputStream body = Files.newInputStream(registration.completedPath())) {
                this.objectStore.upload(bucket, key, body);
            }
        } catch (final IOException failure) {
            throw new UncheckedIOException("completed batch artifact could not be published for "
                    + registration.logicalBase(), failure);
        }
        return new PublishedGeneration(bucket, key, size);
    }

    /** Deletes uploads made earlier in the same failed publication pass. */
    private void rollbackUploads(final String bucket,
            final List<PublishedGeneration> published) {
        for (final PublishedGeneration generation : published) {
            try {
                this.objectStore.deleteObject(bucket, generation.objectKey());
            } catch (final RuntimeException rollbackFailure) {
                LOGGER.warn("Could not roll back object {} after batch artifact publication failed;"
                        + " rollbackFailure={}", generation.objectKey(),
                        rollbackFailure.getClass().getSimpleName());
            }
        }
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
                this.objectStore.listObjects(bucket, prefix),
                "objectStore.listObjects must not return null");
        for (final S3Resource resource : resources) {
            if (resource == null || resource.getLocation() == null) {
                continue;
            }
            final String key = resource.getLocation().getObject();
            final Long executionId = executionIdFromKey(prefix, key);
            if (executionId != null) {
                generations.add(new RemoteGeneration(key, executionId.longValue()));
            }
        }
        generations.sort(Comparator.comparingLong(RemoteGeneration::executionId)
                .reversed()
                .thenComparing(RemoteGeneration::objectKey));

        for (int index = policy.retentionLimit(); index < generations.size(); index++) {
            final RemoteGeneration expired = generations.get(index);
            this.objectStore.deleteObject(bucket, expired.objectKey());
            LOGGER.info("Scratched rolled-off object {} from bucket {} at retention depth {}",
                    expired.objectKey(), bucket, policy.retentionLimit());
        }
    }

    /** Extracts an execution identifier only from keys produced by this class. */
    private static Long executionIdFromKey(final String prefix, final String key) {
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
        try {
            return Long.valueOf(digits);
        } catch (final NumberFormatException outsideLongRange) {
            return null;
        }
    }

    /** Confirms every registered source exists and has been atomically completed. */
    private static void requireCompletedSource(final ArtifactRegistration registration) {
        if (!Files.isRegularFile(registration.completedPath())) {
            throw new IllegalStateException("completed batch artifact for "
                    + registration.logicalBase() + " is not a regular file");
        }
    }

    /** Creates the local filename of one completed generation. */
    private static String localGenerationName(final String logicalBase, final long executionId) {
        return requireLogicalBase(logicalBase) + LOCAL_NAME_SEPARATOR
                + generationToken(executionId);
    }

    /** Creates the generation token shared by local names and durable keys. */
    private static String generationToken(final long executionId) {
        requireExecutionId(executionId);
        return String.format(Locale.ROOT, GENERATION_TOKEN_FORMAT, executionId);
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
    private record RemoteGeneration(String objectKey, long executionId) {
    }

    /**
     * Writer adapter that completes and registers only after its delegate closes successfully.
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
            this.delegate.close();
            completeWorkingFile(this.workingPath, this.completedPath);
            register(this.stepExecution, this.logicalBase, this.completedPath, this.retentionLimit);
        }
    }
}