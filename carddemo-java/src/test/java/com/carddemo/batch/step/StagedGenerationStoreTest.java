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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.awspring.cloud.s3.Location;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.ItemStreamWriter;

/**
 * Verifies the durable batch-artifact boundary independently of every job that registers with it.
 *
 * <p>The oracle never asks the production store to calculate its own expected key or retention result.
 * Expected generation tokens are written explicitly, and the object-store operation is mocked only at
 * the network edge so the registration, file sealing, upload, rollback, alias and retention behaviour
 * all execute as production code.
 */
@DisplayName("StagedGenerationStore: completed files, collision-free keys and measured retention")
class StagedGenerationStoreTest {

    private static final String BUCKET = "carddemo-batch-staging";
    private static final String BASE = "AWS.M2.CARDDEMO.TRANSACT.DALY";
    private static final String OTHER_BASE = "AWS.M2.CARDDEMO.TRANREPT";

    @TempDir
    private Path stagingDirectory;

    private S3Operations objectStore;

    /**
     * The version-aware client the store performs its two deletions through.
     *
     * <p>Stubbed as a small in-memory <strong>versioned</strong> store rather than merely recorded, because
     * the property under test is not "a delete was called" but "the bytes stopped existing". An unqualified
     * delete against a versioned bucket is accepted, reports success, and leaves every version retrievable
     * by identifier - so a test that verified the call would have passed against exactly the defect this
     * suite now has to catch. Every key here therefore holds a list of version identifiers, a delete removes
     * one of them, and the assertions read what is left.
     */
    private S3Client versionedObjectStore;

    private StagedGenerationStore store;
    private Map<String, byte[]> uploaded;

    /** Version identifiers held per object key, newest last, as a versioned bucket would hold them. */
    private Map<String, List<String>> versionsByKey;

    private RecordingPublicationLock publicationLock;

    /** The registry every outbound object-store call is observed against. */
    private ObservationRegistry observationRegistry;

    /** Every observation the store stopped, in stop order. */
    private final List<Observation.Context> observed = new ArrayList<>();

    /**
     * A publication lock that runs the publication directly and records what it was asked to hold.
     *
     * <p>Recording rather than merely delegating is the point: the store's obligation is to name every
     * base it touches, and a lock that only ran the body would let a store that named none of them pass.
     * The order the bases arrive in is preserved so a test can assert what was requested; putting them
     * into a deterministic acquisition order is the production lock's job, asserted in its own test.</p>
     */
    private static final class RecordingPublicationLock implements GenerationPublicationLock {

        private final List<List<String>> requestedBases = new ArrayList<>();
        private int publicationsRun;

        @Override
        public void whileHolding(final List<String> logicalBases, final Runnable publication) {
            this.requestedBases.add(List.copyOf(logicalBases));
            this.publicationsRun++;
            publication.run();
        }

        private List<String> onlyRequest() {
            assertThat(this.requestedBases).hasSize(1);
            return this.requestedBases.get(0);
        }
    }

    @BeforeEach
    void setUp() {
        this.objectStore = mock(S3Operations.class);
        this.versionedObjectStore = mock(S3Client.class);
        this.uploaded = new LinkedHashMap<>();
        this.versionsByKey = new LinkedHashMap<>();
        this.publicationLock = new RecordingPublicationLock();
        when(this.objectStore.listObjects(any(String.class), any(String.class)))
                .thenReturn(List.of());
        doAnswer(invocation -> {
            final String key = invocation.getArgument(1, String.class);
            try (InputStream body = invocation.getArgument(2, InputStream.class)) {
                this.uploaded.put(key, body.readAllBytes());
            }
            // An upload against a versioned bucket adds a version rather than replacing one.
            addVersion(key);
            return null;
        }).when(this.objectStore).upload(eq(BUCKET), any(String.class), any(InputStream.class));

        // The versioned listing, followed by prefix exactly as the service offers it.
        when(this.versionedObjectStore.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenAnswer(invocation -> {
                    final ListObjectVersionsRequest request =
                            invocation.getArgument(0, ListObjectVersionsRequest.class);
                    final String prefix = request.prefix() == null ? "" : request.prefix();
                    final List<ObjectVersion> versions = new ArrayList<>();
                    for (final Map.Entry<String, List<String>> held : this.versionsByKey.entrySet()) {
                        if (!held.getKey().startsWith(prefix)) {
                            continue;
                        }
                        for (final String versionId : held.getValue()) {
                            versions.add(ObjectVersion.builder()
                                    .key(held.getKey())
                                    .versionId(versionId)
                                    .build());
                        }
                    }
                    return ListObjectVersionsResponse.builder()
                            .versions(versions)
                            .isTruncated(Boolean.FALSE)
                            .build();
                });

        // A version-qualified delete removes that one version. A request without a version identifier is
        // refused outright here, because on a versioned bucket it would silently add a delete marker and
        // this suite exists to prove that never happens.
        doAnswer(invocation -> {
            final DeleteObjectRequest request =
                    invocation.getArgument(0, DeleteObjectRequest.class);
            if (request.versionId() == null || request.versionId().isBlank()) {
                throw new AssertionError("the store deleted " + request.key() + " without naming a"
                        + " version identifier. Against the versioned staging bucket that adds a delete"
                        + " marker and leaves every version of the object retrievable, which is the"
                        + " defect this stub exists to make impossible to pass");
            }
            removeVersion(request.key(), request.versionId());
            return null;
        }).when(this.versionedObjectStore).deleteObject(any(DeleteObjectRequest.class));

        this.observed.clear();
        final ObservationRegistry recording = ObservationRegistry.create();
        recording.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public void onStop(final Observation.Context context) {
                observed.add(context);
            }

            @Override
            public boolean supportsContext(final Observation.Context context) {
                return true;
            }
        });
        this.observationRegistry = recording;
        this.store = new StagedGenerationStore(this.objectStore, this.versionedObjectStore, BUCKET,
                this.publicationLock, this.observationRegistry);
    }

    /**
     * Records one further version of one key, as an upload against a versioned bucket would.
     *
     * @param  key the object key
     * @return the identifier of the version added
     */
    private String addVersion(final String key) {
        final List<String> held = this.versionsByKey.computeIfAbsent(key, absent -> new ArrayList<>());
        final String versionId = "v" + (held.size() + 1);
        held.add(versionId);
        return versionId;
    }

    /**
     * Removes one exact version of one key, and forgets the key entirely once it holds none.
     *
     * @param key       the object key
     * @param versionId the version identifier to remove
     */
    private void removeVersion(final String key, final String versionId) {
        final List<String> held = this.versionsByKey.get(key);
        if (held == null) {
            return;
        }
        held.remove(versionId);
        if (held.isEmpty()) {
            this.versionsByKey.remove(key);
            this.uploaded.remove(key);
        }
    }

    /**
     * Declares one generation as already present in the durable store, with a version behind it.
     *
     * <p>Two facts have to be arranged together for a retention assertion to mean anything: the ordinary
     * listing the store measures depth with must report the key, and the versioned listing its scratch pass
     * reads must report a version to remove. Arranging only the first is what let an unqualified delete
     * appear to work.
     *
     * @param  key the object key of the pre-existing generation
     * @return the resource the ordinary listing reports for it
     */
    private S3Resource presentGeneration(final String key) {
        addVersion(key);
        return resource(key);
    }

    private static JobExecution completedJob(final long executionId) {
        final JobExecution execution = new JobExecution(
                new JobInstance(executionId + 100, "testJob"),
                executionId,
                new JobParameters());
        execution.setStatus(BatchStatus.COMPLETED);
        return execution;
    }

    private static StepExecution stepOf(final JobExecution jobExecution) {
        final StepExecution step = new StepExecution("testStep", jobExecution, 17L);
        jobExecution.addStepExecutions(List.of(step));
        return step;
    }

    private static S3Resource resource(final String key) {
        final S3Resource resource = mock(S3Resource.class);
        when(resource.getLocation()).thenReturn(Location.of(BUCKET, key));
        return resource;
    }

    @Nested
    @DisplayName("generation identity")
    class GenerationIdentity {

        @Test
        @DisplayName("uses a ten-digit minimum width for the local file and never wraps at the former "
                + "four-digit boundary")
        void executionIdentifiersNeverWrap() {
            assertThat(localName(7)).isEqualTo(BASE + ".G0000000007V00");
            assertThat(localName(10_000)).isEqualTo(BASE + ".G0000010000V00");
            assertThat(localName(10_007))
                    .isEqualTo(BASE + ".G0000010007V00")
                    .isNotEqualTo(localName(7));
        }

        @Test
        @DisplayName("names the local file from the execution identifier, so two concurrent executions "
                + "cannot compose over each other")
        void theLocalNameCarriesTheExecutionIdentifier() {
            assertThat(localName(42)).isEqualTo(BASE + ".G0000000042V00");
        }

        @Test
        @DisplayName("refuses path separators and negative identifiers before composing a name")
        void hostileIdentityPartsAreRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> StagedGenerationStore.generationPath(
                            stagingDirectory, "../outside", 1));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> StagedGenerationStore.generationPath(
                            stagingDirectory, BASE, -1));
        }

        /**
         * The local file name one execution's generation occupies.
         *
         * @param  executionId the framework execution identifier
         * @return the file name, without its directory
         */
        private String localName(final long executionId) {
            return StagedGenerationStore.generationPath(stagingDirectory, BASE, executionId)
                    .getFileName().toString();
        }
    }

    @Nested
    @DisplayName("local completion and registration")
    class LocalCompletionAndRegistration {

        @Test
        @DisplayName("moves a working file atomically onto the completed generation")
        void aWorkingFileIsSealed() throws Exception {
            final Path completed =
                    StagedGenerationStore.generationPath(stagingDirectory, BASE, 1);
            final Path working = StagedGenerationStore.workingPath(completed);
            Files.writeString(working, "complete bytes", StandardCharsets.US_ASCII);

            assertThat(StagedGenerationStore.completeWorkingFile(working, completed))
                    .isEqualTo(completed);
            assertThat(working).doesNotExist();
            assertThat(completed).hasContent("complete bytes");
        }

        @Test
        @DisplayName("a completing writer registers only after its delegate closes successfully")
        void theWriterAdapterSealsAndRegistersAfterClose() throws Exception {
            final JobExecution execution = completedJob(2);
            final StepExecution step = stepOf(execution);
            final Path completed =
                    StagedGenerationStore.generationPath(stagingDirectory, BASE, 2);
            final Path working = StagedGenerationStore.workingPath(completed);
            Files.writeString(working, "writer bytes", StandardCharsets.US_ASCII);
            final ItemStreamWriter<String> delegate = mock();
            final ItemStreamWriter<String> writer = StagedGenerationStore.completingWriter(
                    delegate, step, BASE, working, completed,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);

            writer.close();

            verify(delegate).close();
            assertThat(completed).hasContent("writer bytes");
            assertThat(StagedGenerationStore.registeredArtifactCount(execution)).isOne();
        }

        @Test
        @DisplayName("a failed delegate close publishes nothing, registers nothing AND leaves no partial "
                + "working file behind")
        void aFailedCloseCannotPublishAPartialFile() {
            // This test asserted the opposite until the working file was recognised for what it is. A
            // partial file abandoned here is owned by nobody: registration happens at completion, so it
            // appears in no execution's registry, and the boundary listener's cleanup deletes only
            // registered paths. It would have sat in the staging root at whatever length the failure left
            // it - truncated batch output under a name that reads like real output - accumulating one
            // copy per failed run until an operator noticed. See docs/decision-log.md entry DL-289.
            final JobExecution execution = completedJob(3);
            final StepExecution step = stepOf(execution);
            final Path completed =
                    StagedGenerationStore.generationPath(stagingDirectory, BASE, 3);
            final Path working = StagedGenerationStore.workingPath(completed);
            assertThatNoException().isThrownBy(() ->
                    Files.writeString(working, "partial", StandardCharsets.US_ASCII));
            assertThat(working)
                    .as("the partial file exists before the close, so its absence afterwards is the "
                            + "adapter's doing and not an artefact of it never having been written")
                    .exists();
            final ItemStreamWriter<String> delegate = mock();
            doThrow(new IllegalStateException("close failed")).when(delegate).close();
            final ItemStreamWriter<String> writer = StagedGenerationStore.completingWriter(
                    delegate, step, BASE, working, completed,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("the delegate's own failure is what propagates; the cleanup must not replace it")
                    .isThrownBy(writer::close)
                    .withMessage("close failed");

            assertThat(working)
                    .as("the unowned partial file is discarded where its path is known and trusted, "
                            + "rather than left for a sweep that would have to guess at names")
                    .doesNotExist();
            assertThat(completed).doesNotExist();
            assertThat(StagedGenerationStore.registeredArtifactCount(execution)).isZero();
        }

        @Test
        @DisplayName("a working file that cannot be sealed is discarded too, because a move that did not "
                + "happen leaves the same unowned partial file")
        void aFailedSealAlsoDiscardsTheWorkingFile() throws Exception {
            // The other failure that leaves a working file behind. The delegate closes cleanly and the
            // file is complete, but the atomic move onto the completed name is what would have made it a
            // registered generation, so a move that fails leaves it in exactly the same unowned state.
            // The container is blocked by a regular file occupying the directory the completed name would
            // need, which is what makes the seal - not the close - the operation that fails.
            final JobExecution execution = completedJob(5);
            final StepExecution step = stepOf(execution);
            final Path blockedContainer = stagingDirectory.resolve("occupied-container");
            Files.writeString(blockedContainer, "not a directory", StandardCharsets.US_ASCII);
            final Path completed = blockedContainer.resolve(BASE + ".G0000000005V00");
            final Path working =
                    StagedGenerationStore.workingPath(
                            StagedGenerationStore.generationPath(stagingDirectory, BASE, 5));
            Files.writeString(working, "sealable bytes", StandardCharsets.US_ASCII);
            final ItemStreamWriter<String> delegate = mock();
            final ItemStreamWriter<String> writer = StagedGenerationStore.completingWriter(
                    delegate, step, BASE, working, completed,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);

            assertThatExceptionOfType(UncheckedIOException.class)
                    .as("the sealing failure is the one an operator reads")
                    .isThrownBy(writer::close);

            verify(delegate).close();
            assertThat(working)
                    .as("and the file that would have become a generation is gone rather than abandoned")
                    .doesNotExist();
            assertThat(completed).doesNotExist();
            assertThat(StagedGenerationStore.registeredArtifactCount(execution)).isZero();
        }

        @Test
        @DisplayName("a step that never composed a working file is closed without a secondary failure "
                + "that would replace its own")
        void aStepThatProducedNothingIsClosedQuietly() {
            // Written after a posting run whose strict reader refused a missing input reported a
            // NoSuchFileException raised while sealing a reject file that had never been created. That
            // secondary failure arrived from this adapter and displaced the reader's own diagnostic,
            // which is the only one an operator can act on.
            final JobExecution execution = completedJob(4);
            final StepExecution step = stepOf(execution);
            final Path completed =
                    StagedGenerationStore.generationPath(stagingDirectory, BASE, 4);
            final Path working = StagedGenerationStore.workingPath(completed);
            final ItemStreamWriter<String> delegate = mock();
            final ItemStreamWriter<String> writer = StagedGenerationStore.completingWriter(
                    delegate, step, BASE, working, completed,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);

            assertThat(working).doesNotExist();
            assertThatNoException().isThrownBy(writer::close);

            verify(delegate).close();
            assertThat(completed)
                    .as("nothing was composed, so nothing may be published under a completed name")
                    .doesNotExist();
            assertThat(StagedGenerationStore.registeredArtifactCount(execution)).isZero();
        }
    }

    @Nested
    @DisplayName("completed-job publication")
    class CompletedJobPublication {

        @Test
        @DisplayName("uploads exact bytes, replaces the fixed alias and clears the registry")
        void aCompletedJobPublishesAndAliasesAtomically() throws Exception {
            final JobExecution execution = completedJob(11);
            final StepExecution step = stepOf(execution);
            final Path completed =
                    StagedGenerationStore.generationPath(stagingDirectory, BASE, 11);
            final Path alias = stagingDirectory.resolve("latest-output.txt");
            Files.writeString(completed, "new generation", StandardCharsets.US_ASCII);
            Files.writeString(alias, "old generation", StandardCharsets.US_ASCII);
            StagedGenerationStore.register(step, BASE, completed,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT, alias);

            final List<StagedGenerationStore.PublishedGeneration> result =
                    store.publishRegistered(execution);

            // Generation ONE, although the execution identifier is eleven: the durable generation number
            // is allocated against what the bucket already holds - nothing, here - and not against the
            // framework's identifier, which a re-created metadata store would restart (DL-210).
            assertThat(result).singleElement().satisfies(generation -> {
                assertThat(generation.bucket()).isEqualTo(BUCKET);
                assertThat(generation.objectKey()).isEqualTo(BASE + "/G0000000001V00");
                assertThat(generation.contentLength()).isEqualTo("new generation".length());
            });
            assertThat(uploaded).containsOnlyKeys(BASE + "/G0000000001V00");
            assertThat(uploaded.get(BASE + "/G0000000001V00"))
                    .isEqualTo("new generation".getBytes(StandardCharsets.US_ASCII));
            assertThat(alias).hasContent("new generation");
            assertThat(StagedGenerationStore.registeredArtifactCount(execution)).isZero();
        }

        @Test
        @DisplayName("refuses publication before the job has completed")
        void anUnsuccessfulJobCannotPublish() throws Exception {
            final JobExecution execution = completedJob(12);
            final StepExecution step = stepOf(execution);
            final Path completed =
                    StagedGenerationStore.generationPath(stagingDirectory, BASE, 12);
            Files.writeString(completed, "bytes", StandardCharsets.US_ASCII);
            StagedGenerationStore.register(step, BASE, completed,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            execution.setStatus(BatchStatus.FAILED);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> store.publishRegistered(execution));

            verify(objectStore, never())
                    .upload(any(String.class), any(String.class), any(InputStream.class));
        }

        @Test
        @DisplayName("validates every source before uploading the first artifact")
        void aMissingSourcePreventsEveryUpload() throws Exception {
            final JobExecution execution = completedJob(13);
            final StepExecution step = stepOf(execution);
            final Path present =
                    StagedGenerationStore.generationPath(stagingDirectory, BASE, 13);
            Files.writeString(present, "present", StandardCharsets.US_ASCII);
            final Path absent =
                    StagedGenerationStore.generationPath(stagingDirectory, OTHER_BASE, 13);
            StagedGenerationStore.register(step, BASE, present,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            StagedGenerationStore.register(step, OTHER_BASE, absent,
                    StagedGenerationStore.REPORT_RETENTION_LIMIT);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> store.publishRegistered(execution));

            verify(objectStore, never())
                    .upload(any(String.class), any(String.class), any(InputStream.class));
        }

        @Test
        @DisplayName("refuses a registered path that has become a symbolic link, without following it, "
                + "and without uploading anything at all")
        void aRegisteredSourceThatBecameALinkIsRefusedBeforeAnyUpload() throws Exception {
            // Registration records a path; publication reads that path later. Between the two, the name
            // can stop being the file that was registered. Validating with an ordinary regular-file test
            // FOLLOWS a link, so the check would pass and the upload would then stream whatever the link
            // now points at - into this job's own generation key, under this job's own base, indexed as
            // this job's output. The trust re-establishment refuses the name instead of following it.
            // See docs/decision-log.md entry DL-288.
            final JobExecution execution = completedJob(15);
            final StepExecution step = stepOf(execution);
            final Path registered =
                    StagedGenerationStore.generationPath(stagingDirectory, BASE, 15);
            Files.writeString(registered, "the bytes that were staged", StandardCharsets.US_ASCII);
            StagedGenerationStore.register(step, BASE, registered,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            final Path substituted = stagingDirectory.resolve("not-this-jobs-output");
            Files.writeString(substituted, "MUST NOT BE UPLOADED", StandardCharsets.US_ASCII);
            Files.delete(registered);
            try {
                Files.createSymbolicLink(registered, substituted);
            } catch (final UnsupportedOperationException | IOException linksUnavailable) {
                return;
            }

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> store.publishRegistered(execution));

            verify(objectStore, never())
                    .upload(any(String.class), any(String.class), any(InputStream.class));
            assertThat(uploaded)
                    .as("nothing reached the durable store, so the substituted content was never "
                            + "published under this job's generation key")
                    .isEmpty();
            assertThat(substituted)
                    .as("and the link's target is untouched - it was neither read nor removed")
                    .hasContent("MUST NOT BE UPLOADED");
        }

        @Test
        @DisplayName("rolls back an earlier upload when a later artifact fails")
        void aPartialPublicationIsRolledBack() throws Exception {
            final JobExecution execution = completedJob(14);
            final StepExecution step = stepOf(execution);
            final Path first =
                    StagedGenerationStore.generationPath(stagingDirectory, BASE, 14);
            final Path second =
                    StagedGenerationStore.generationPath(stagingDirectory, OTHER_BASE, 14);
            Files.writeString(first, "first", StandardCharsets.US_ASCII);
            Files.writeString(second, "second", StandardCharsets.US_ASCII);
            StagedGenerationStore.register(step, BASE, first,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            StagedGenerationStore.register(step, OTHER_BASE, second,
                    StagedGenerationStore.REPORT_RETENTION_LIMIT);
            doThrow(new IllegalStateException("second upload failed"))
                    .when(objectStore).upload(eq(BUCKET),
                            eq(OTHER_BASE + "/G0000000001V00"), any(InputStream.class));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> store.publishRegistered(execution));

            // The rolled-back object has to STOP EXISTING, not merely stop being listed. An unqualified
            // delete against the versioned staging bucket would have added a delete marker and left the
            // uploaded bytes of a job that failed retrievable by version identifier - externally visible
            // output of a failed job, which is the property this compensation exists to deny.
            assertThat(versionsByKey)
                    .as("every version of the rolled-back key is gone, and the store never issued an "
                            + "unqualified delete")
                    .doesNotContainKey(BASE + "/G0000000001V00");
            assertThat(uploaded).doesNotContainKey(BASE + "/G0000000001V00");
            verify(objectStore, never()).deleteObject(any(String.class), any(String.class));
            assertThat(StagedGenerationStore.registeredArtifactCount(execution)).isEqualTo(2);
        }
    }

    /**
     * The commit boundary: what survives a publication that fails after it has already uploaded.
     *
     * <p>Each test here fails the publication at a point <em>after</em> the last upload succeeded, which
     * is the region the earlier compensation did not cover. The assertion in every case is the same
     * property stated three ways: a job the boundary listener will mark FAILED leaves nothing externally
     * visible - no durable object, and no fixed-name local view naming a generation that was rolled
     * back.</p>
     */
    @Nested
    @DisplayName("the commit boundary: a failed publication leaves nothing externally visible")
    class TheCommitBoundary {

        @Test
        @DisplayName("an alias failure after every upload succeeded still deletes every uploaded object")
        void anAliasFailureRollsBackTheUploadsItFollows() throws Exception {
            final JobExecution execution = completedJob(21);
            final StepExecution step = stepOf(execution);
            final Path first = StagedGenerationStore.generationPath(stagingDirectory, BASE, 21);
            final Path second =
                    StagedGenerationStore.generationPath(stagingDirectory, OTHER_BASE, 21);
            Files.writeString(first, "first", StandardCharsets.US_ASCII);
            Files.writeString(second, "second", StandardCharsets.US_ASCII);
            // A directory standing where the second alias file must be written. The alias replacement
            // moves a temporary sibling onto this name, which cannot succeed against a directory, so the
            // publication fails at its last step rather than during upload.
            final Path blockedAlias = stagingDirectory.resolve("blocked-alias");
            Files.createDirectory(blockedAlias);
            Files.createFile(blockedAlias.resolve("occupant"));
            StagedGenerationStore.register(step, BASE, first,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            StagedGenerationStore.register(step, OTHER_BASE, second,
                    StagedGenerationStore.REPORT_RETENTION_LIMIT, blockedAlias);

            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> store.publishRegistered(execution));

            // Both uploads had already succeeded when the alias failed, so both must be scratched - and
            // scratched by version, so that neither remains retrievable behind a delete marker.
            assertThat(versionsByKey)
                    .as("neither key holds any version afterwards")
                    .doesNotContainKey(BASE + "/G0000000001V00")
                    .doesNotContainKey(OTHER_BASE + "/G0000000001V00");
            verify(objectStore, never()).deleteObject(any(String.class), any(String.class));
            // And the registry is intact, so the failure is visible as an unpublished job rather than as
            // a published one.
            assertThat(StagedGenerationStore.registeredArtifactCount(execution)).isEqualTo(2);
        }

        @Test
        @DisplayName("an alias advanced before the failure is put back to what it named before")
        void anAdvancedAliasIsRestored() throws Exception {
            final JobExecution execution = completedJob(22);
            final StepExecution step = stepOf(execution);
            final Path first = StagedGenerationStore.generationPath(stagingDirectory, BASE, 22);
            final Path second =
                    StagedGenerationStore.generationPath(stagingDirectory, OTHER_BASE, 22);
            Files.writeString(first, "new first generation", StandardCharsets.US_ASCII);
            Files.writeString(second, "new second generation", StandardCharsets.US_ASCII);
            final Path advancedAlias = stagingDirectory.resolve("first-latest.txt");
            Files.writeString(advancedAlias, "previous first generation", StandardCharsets.US_ASCII);
            final Path blockedAlias = stagingDirectory.resolve("second-latest");
            Files.createDirectory(blockedAlias);
            Files.createFile(blockedAlias.resolve("occupant"));
            StagedGenerationStore.register(step, BASE, first,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT, advancedAlias);
            StagedGenerationStore.register(step, OTHER_BASE, second,
                    StagedGenerationStore.REPORT_RETENTION_LIMIT, blockedAlias);

            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> store.publishRegistered(execution));

            assertThat(advancedAlias).hasContent("previous first generation");
        }

        @Test
        @DisplayName("an alias this publication created is removed rather than left behind")
        void anAliasCreatedByTheFailedPublicationIsRemoved() throws Exception {
            final JobExecution execution = completedJob(23);
            final StepExecution step = stepOf(execution);
            final Path first = StagedGenerationStore.generationPath(stagingDirectory, BASE, 23);
            final Path second =
                    StagedGenerationStore.generationPath(stagingDirectory, OTHER_BASE, 23);
            Files.writeString(first, "new first generation", StandardCharsets.US_ASCII);
            Files.writeString(second, "new second generation", StandardCharsets.US_ASCII);
            final Path createdAlias = stagingDirectory.resolve("did-not-exist-before.txt");
            final Path blockedAlias = stagingDirectory.resolve("second-latest");
            Files.createDirectory(blockedAlias);
            Files.createFile(blockedAlias.resolve("occupant"));
            StagedGenerationStore.register(step, BASE, first,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT, createdAlias);
            StagedGenerationStore.register(step, OTHER_BASE, second,
                    StagedGenerationStore.REPORT_RETENTION_LIMIT, blockedAlias);

            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> store.publishRegistered(execution));

            assertThat(createdAlias).doesNotExist();
        }

        @Test
        @DisplayName("a committed publication leaves no set-aside alias content on disk")
        void aCommittedPublicationLeavesNoTemporaryFiles() throws Exception {
            final JobExecution execution = completedJob(24);
            final StepExecution step = stepOf(execution);
            final Path completed = StagedGenerationStore.generationPath(stagingDirectory, BASE, 24);
            final Path alias = stagingDirectory.resolve("latest-output.txt");
            Files.writeString(completed, "new generation", StandardCharsets.US_ASCII);
            Files.writeString(alias, "old generation", StandardCharsets.US_ASCII);
            StagedGenerationStore.register(step, BASE, completed,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT, alias);

            store.publishRegistered(execution);

            assertThat(alias).hasContent("new generation");
            try (var entries = Files.list(stagingDirectory)) {
                assertThat(entries.map(entry -> entry.getFileName().toString()))
                        .noneMatch(name -> name.contains(".pre-publish-"))
                        .noneMatch(name -> name.contains(".publish-"));
            }
        }

        @Test
        @DisplayName("a retention failure after the commit point does NOT fail the published job")
        void retentionCannotFailACommittedPublication() throws Exception {
            final JobExecution execution = completedJob(25);
            final StepExecution step = stepOf(execution);
            final Path completed = StagedGenerationStore.generationPath(stagingDirectory, BASE, 25);
            Files.writeString(completed, "published bytes", StandardCharsets.US_ASCII);
            StagedGenerationStore.register(step, BASE, completed,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            // Six generations already present, so the retention pass has one to scratch - and scratching
            // is what fails. The lever is the DELETE rather than the listing, because the listing is now
            // read before the upload too, to allocate the generation number (DL-210); failing that would
            // fail the publication itself, which is a different property and has its own test below.
            final List<S3Resource> present = List.of(
                    presentGeneration(BASE + "/G0000000001V00"),
                    presentGeneration(BASE + "/G0000000002V00"),
                    presentGeneration(BASE + "/G0000000003V00"),
                    presentGeneration(BASE + "/G0000000004V00"),
                    presentGeneration(BASE + "/G0000000005V00"),
                    presentGeneration(BASE + "/G0000000006V00"));
            when(objectStore.listObjects(BUCKET, BASE + "/")).thenReturn(present);
            doThrow(new IllegalStateException("the rolled-off object could not be scratched"))
                    .when(versionedObjectStore).deleteObject(any(DeleteObjectRequest.class));

            final List<StagedGenerationStore.PublishedGeneration> result =
                    store.publishRegistered(execution);

            // The artifact is published, the result is reported, and the registry is cleared: a base left
            // over depth is corrected by the next publication and is not a reason to fail a job whose
            // output is durable and visible.
            assertThat(result).singleElement().satisfies(generation ->
                    assertThat(generation.objectKey()).isEqualTo(BASE + "/G0000000007V00"));
            assertThat(uploaded).containsOnlyKeys(BASE + "/G0000000007V00");
            assertThat(StagedGenerationStore.registeredArtifactCount(execution)).isZero();
        }
    }

    /**
     * Every outbound object-store call this store makes is observed.
     *
     * <p>The durable generation boundary is the most consequential object-store work a batch job performs,
     * and none of it appeared in a trace: not the listing that decides the next generation number, not the
     * upload that publishes it, not the version listing and version-qualified deletes that compensate a
     * failed pass and enforce retention. A failed publication in particular showed nothing at all - the
     * compensation is entirely object-store work, so an untraced compensation is indistinguishable from no
     * compensation.</p>
     */
    @Nested
    @DisplayName("every outbound object-store call is observed")
    class ObservedCalls {

        @Test
        @DisplayName("a committed publication observes its listing and its upload")
        void aCommittedPublicationObservesItsCalls() throws Exception {
            final JobExecution execution = completedJob(51);
            final StepExecution step = stepOf(execution);
            final Path completed = StagedGenerationStore.generationPath(stagingDirectory, BASE, 51);
            Files.writeString(completed, "published", StandardCharsets.US_ASCII);
            StagedGenerationStore.register(step, BASE, completed,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);

            store.publishRegistered(execution);

            assertThat(observed)
                    .as("an empty list is exactly what the no-op registry produced before this store was "
                            + "given a real one")
                    .isNotEmpty();
            assertThat(observed)
                    .allSatisfy(context -> assertThat(context.getName())
                            .isEqualTo(StagedGenerationStore.OBSERVATION_NAME));
            assertThat(observed)
                    .extracting(context -> lowTag(context, StagedGenerationStore.TAG_OPERATION))
                    .as("the allocation lists the base before the upload publishes into it, in that order")
                    .startsWith(StagedGenerationStore.OPERATION_LIST,
                            StagedGenerationStore.OPERATION_UPLOAD);
            assertThat(observed)
                    .filteredOn(context -> StagedGenerationStore.OPERATION_UPLOAD
                            .equals(lowTag(context, StagedGenerationStore.TAG_OPERATION)))
                    .singleElement()
                    .satisfies(context -> assertThat(
                            highTag(context, StagedGenerationStore.TAG_OBJECT_KEY))
                            .as("the key belongs on the span, where it identifies the object, and never "
                                    + "on a meter dimension where it would fork a series per generation")
                            .isEqualTo(BASE + "/G0000000001V00"));
        }

        @Test
        @DisplayName("compensation observes its version listing and every version-qualified removal")
        void compensationObservesItsRemovals() throws Exception {
            final JobExecution execution = completedJob(52);
            final StepExecution step = stepOf(execution);
            final Path completed = StagedGenerationStore.generationPath(stagingDirectory, BASE, 52);
            Files.writeString(completed, "kept by the service", StandardCharsets.US_ASCII);
            StagedGenerationStore.register(step, BASE, completed,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            final String key = BASE + "/G0000000001V00";
            // The ambiguous upload: the bytes are stored, then the call throws. So compensation has real
            // work to do, and the removal it performs is what must be visible.
            doAnswer(invocation -> {
                try (InputStream body = invocation.getArgument(2, InputStream.class)) {
                    uploaded.put(key, body.readAllBytes());
                }
                addVersion(key);
                throw new IllegalStateException("the response never arrived");
            }).when(objectStore).upload(eq(BUCKET), eq(key), any(InputStream.class));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> store.publishRegistered(execution));

            assertThat(observed)
                    .extracting(context -> lowTag(context, StagedGenerationStore.TAG_OPERATION))
                    .as("the failed upload, then the version listing that reconciles its key, then the "
                            + "removal - a compensation nobody can see is indistinguishable from none")
                    .containsSubsequence(StagedGenerationStore.OPERATION_UPLOAD,
                            StagedGenerationStore.OPERATION_LIST_VERSIONS,
                            StagedGenerationStore.OPERATION_DELETE_VERSION);
            assertThat(observed)
                    .filteredOn(context -> StagedGenerationStore.OPERATION_UPLOAD
                            .equals(lowTag(context, StagedGenerationStore.TAG_OPERATION)))
                    .singleElement()
                    .satisfies(context -> assertThat(context.getError())
                            .as("the refused call is a failure on its span rather than a gap in the trace")
                            .isInstanceOf(IllegalStateException.class));
        }

        @Test
        @DisplayName("an immediate publication observes its own upload")
        void anImmediatePublicationObservesItsUpload() {
            store.publishFile(BASE, completedFileHolding(new byte[] {1, 2, 3}),
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);

            assertThat(observed)
                    .extracting(context -> lowTag(context, StagedGenerationStore.TAG_OPERATION))
                    .contains(StagedGenerationStore.OPERATION_UPLOAD);
        }

        @Test
        @DisplayName("every call is a child of whatever observation encloses the publication, which is "
                + "the actual defect: a present-but-parentless span is a one-span trace nobody can "
                + "follow back to the job that produced it")
        void everyCallIsAChildOfTheEnclosingObservation() {
            // Presence is not the property at stake. An unparented observation is recorded, timed and
            // tagged exactly like a parented one; the only difference is that its trace begins and ends
            // at the object-store call, which is why an untraced durable boundary looked healthy.
            final Observation boundary =
                    Observation.start("test.durable.boundary", observationRegistry);
            final Observation.Scope scope = boundary.openScope();
            try {
                store.publishFile(BASE, completedFileHolding(new byte[] {4, 5, 6}),
                        StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            } finally {
                scope.close();
                boundary.stop();
            }

            assertThat(observed)
                    .filteredOn(context -> StagedGenerationStore.OBSERVATION_NAME
                            .equals(context.getName()))
                    .isNotEmpty()
                    .allSatisfy(context -> assertThat(context.getParentObservation())
                            .as("a store call made inside the boundary must hang off it")
                            .isSameAs(boundary));
        }
    }

    /**
     * Reads one low-cardinality tag off an observation, or {@code null} when it carries none.
     *
     * @param  context the observation context
     * @param  name    the tag key
     * @return the tag value, or {@code null}
     */
    private static String lowTag(final Observation.Context context, final String name) {
        for (final KeyValue keyValue : context.getLowCardinalityKeyValues()) {
            if (name.equals(keyValue.getKey())) {
                return keyValue.getValue();
            }
        }
        return null;
    }

    /**
     * Reads one high-cardinality tag off an observation, or {@code null} when it carries none.
     *
     * @param  context the observation context
     * @param  name    the tag key
     * @return the tag value, or {@code null}
     */
    private static String highTag(final Observation.Context context, final String name) {
        for (final KeyValue keyValue : context.getHighCardinalityKeyValues()) {
            if (name.equals(keyValue.getKey())) {
                return keyValue.getValue();
            }
        }
        return null;
    }

    /**
     * The upload whose bytes were kept and whose answer was lost.
     *
     * <h2>Why this class exists separately from {@link TheCommitBoundary}</h2>
     *
     * <p>Every test above fails the publication at a point where the store <em>knows</em> what reached
     * the bucket: an upload that threw is treated as an upload that wrote nothing, and an upload that
     * returned is treated as an upload that wrote everything. Real object storage has a third outcome,
     * and it is the only one a compensation can get wrong: the service accepted and durably stored the
     * body, and the response never arrived. The call throws - a reset connection, a read timeout, the
     * expiring per-call budget this module now sets - so the caller learns nothing, while a complete,
     * current, ordinary generation of the base sits in the bucket.
     *
     * <p>Every fixture here therefore drives the failure through the object store's own behaviour rather
     * than around it: {@link #acceptTheBytesThenLoseTheResponse(String)} reads the body, records the
     * version exactly as a successful upload against a versioned bucket would, and only then throws. A
     * compensation built from the keys whose upload was <em>observed to succeed</em> passes every test in
     * the class above and fails every test in this one, because the entry for the ambiguous upload is
     * never appended to that list. The property asserted is the same one stated for a failed job
     * throughout - it leaves nothing externally visible - held against the case where the store cannot
     * tell whether it published.</p>
     */
    @Nested
    @DisplayName("an upload whose bytes were kept and whose response was lost")
    class TheAmbiguousUpload {

        @Test
        @DisplayName("the key of the only artifact is emptied even though its upload reported failure")
        void theKeyOfALostResponseIsEmptied() throws Exception {
            final JobExecution execution = completedJob(31);
            final StepExecution step = stepOf(execution);
            final Path completed = StagedGenerationStore.generationPath(stagingDirectory, BASE, 31);
            Files.writeString(completed, "kept by the service", StandardCharsets.US_ASCII);
            StagedGenerationStore.register(step, BASE, completed,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            final String key = BASE + "/G0000000001V00";
            acceptTheBytesThenLoseTheResponse(key);

            assertThatExceptionOfType(ApiCallTimeoutException.class)
                    .isThrownBy(() -> store.publishRegistered(execution));

            // The bytes WERE stored, so this is a removal and not a no-op: the version the upload
            // created has to be gone, and gone by identifier rather than hidden behind a marker.
            assertThat(versionsByKey)
                    .as("the version the accepted-but-unacknowledged upload created is gone, so the "
                            + "failed job left no current generation of this base behind")
                    .doesNotContainKey(key);
            verify(versionedObjectStore, times(1)).deleteObject(any(DeleteObjectRequest.class));
            verify(objectStore, never()).deleteObject(any(String.class), any(String.class));
            // And the registry survives, so the boundary reports an unpublished job rather than a
            // published one, which is what makes the failure visible at all.
            assertThat(StagedGenerationStore.registeredArtifactCount(execution)).isEqualTo(1);
        }

        @Test
        @DisplayName("a lost response on the second artifact also scratches the first, which succeeded")
        void aLostResponseScratchesTheArtifactsThatPrecededIt() throws Exception {
            final JobExecution execution = completedJob(32);
            final StepExecution step = stepOf(execution);
            final Path first = StagedGenerationStore.generationPath(stagingDirectory, BASE, 32);
            final Path second =
                    StagedGenerationStore.generationPath(stagingDirectory, OTHER_BASE, 32);
            Files.writeString(first, "first", StandardCharsets.US_ASCII);
            Files.writeString(second, "second", StandardCharsets.US_ASCII);
            StagedGenerationStore.register(step, BASE, first,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            StagedGenerationStore.register(step, OTHER_BASE, second,
                    StagedGenerationStore.REPORT_RETENTION_LIMIT);
            acceptTheBytesThenLoseTheResponse(OTHER_BASE + "/G0000000001V00");

            assertThatExceptionOfType(ApiCallTimeoutException.class)
                    .isThrownBy(() -> store.publishRegistered(execution));

            // One key from the succeeded set and one from the attempted-but-unacknowledged set. Both
            // have to be emptied, which is why compensation walks the attempted set: it is a superset.
            assertThat(versionsByKey)
                    .as("both the acknowledged upload and the ambiguous one are gone")
                    .doesNotContainKey(BASE + "/G0000000001V00")
                    .doesNotContainKey(OTHER_BASE + "/G0000000001V00");
            assertThat(uploaded)
                    .doesNotContainKey(BASE + "/G0000000001V00")
                    .doesNotContainKey(OTHER_BASE + "/G0000000001V00");
            verify(objectStore, never()).deleteObject(any(String.class), any(String.class));
        }

        @Test
        @DisplayName("reconciling a key that holds nothing removes nothing")
        void anUploadThatLeftNothingIsReconciledWithoutDeleting() throws Exception {
            final JobExecution execution = completedJob(33);
            final StepExecution step = stepOf(execution);
            final Path completed = StagedGenerationStore.generationPath(stagingDirectory, BASE, 33);
            Files.writeString(completed, "never accepted", StandardCharsets.US_ASCII);
            StagedGenerationStore.register(step, BASE, completed,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            // This upload refuses before it reads a byte, which is the ordinary failure.
            doThrow(new IllegalStateException("the connection was refused"))
                    .when(objectStore).upload(eq(BUCKET), eq(BASE + "/G0000000001V00"),
                            any(InputStream.class));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> store.publishRegistered(execution));

            // Reconciliation still runs - it has to, because the store cannot distinguish this case
            // from the ambiguous one - and finds the key empty, so it deletes nothing. That is what
            // makes covering the attempted set cheap rather than destructive.
            verify(versionedObjectStore).listObjectVersions(any(ListObjectVersionsRequest.class));
            verify(versionedObjectStore, never()).deleteObject(any(DeleteObjectRequest.class));
            assertThat(versionsByKey).isEmpty();
            assertThat(uploaded).isEmpty();
        }

        @Test
        @DisplayName("an immediate publication empties the key of its own lost response")
        void immediatePublicationEmptiesTheKeyOfALostResponse() {
            final Path completed =
                    completedFileHolding("kept by the service".getBytes(StandardCharsets.US_ASCII));
            final String key = BASE + "/G0000000001V00";
            acceptTheBytesThenLoseTheResponse(key);

            assertThatExceptionOfType(ApiCallTimeoutException.class)
                    .isThrownBy(() -> store.publishFile(BASE, completed,
                            StagedGenerationStore.STANDARD_RETENTION_LIMIT));

            // The immediate path publishes at the end of the producing step rather than at the end of
            // the job, so it has no later listener to compensate for it: the compensation is its own or
            // it does not happen. An earlier revision asserted in a comment that "a failed upload
            // uploaded nothing" and so did nothing here, which left exactly this object behind.
            assertThat(versionsByKey)
                    .as("the ambiguous object is gone, so the step that failed published nothing")
                    .doesNotContainKey(key);
            verify(versionedObjectStore, times(1)).deleteObject(any(DeleteObjectRequest.class));
            verify(objectStore, never()).deleteObject(any(String.class), any(String.class));
        }

        @Test
        @DisplayName("compensation reports the failure it compensated, not a failure of its own")
        void theOriginalFailureSurvivesTheCompensation() {
            final Path absent = stagingDirectory.resolve("was-removed-before-publication.dat");

            // The file cannot be measured, so the failure is an IOException raised after the key was
            // allocated and recorded. The compensation runs against an empty key and must leave the
            // reported cause alone: the caller has to learn why publication failed, not that a rollback
            // ran.
            assertThatExceptionOfType(UncheckedIOException.class)
                    .isThrownBy(() -> store.publishFile(BASE, absent,
                            StagedGenerationStore.STANDARD_RETENTION_LIMIT))
                    .withMessageContaining(BASE)
                    .withCauseInstanceOf(IOException.class);

            verify(versionedObjectStore, never()).deleteObject(any(DeleteObjectRequest.class));
            assertThat(uploaded).isEmpty();
        }

        /**
         * Makes one key's upload behave as the outcome no caller can observe: stored, then unanswered.
         *
         * <p>The order is the whole point. The body is read and the version recorded first, exactly as a
         * successful upload against a versioned bucket leaves them, and the throw comes after - so the
         * store's view is "the upload failed" while the bucket's view is "the object exists". The
         * exception is the one the per-call budget on the object-store client actually raises when a call
         * outlives it, so the fixture is the real shape of this failure rather than a stand-in.</p>
         *
         * @param key the object key whose upload keeps the bytes and loses the answer
         */
        private void acceptTheBytesThenLoseTheResponse(final String key) {
            doAnswer(invocation -> {
                try (InputStream body = invocation.getArgument(2, InputStream.class)) {
                    uploaded.put(key, body.readAllBytes());
                }
                addVersion(key);
                throw ApiCallTimeoutException.create(120_000L);
            }).when(objectStore).upload(eq(BUCKET), eq(key), any(InputStream.class));
        }
    }

    /**
     * Serialization: the store must name every base it touches and must publish inside the lock.
     *
     * <p>These assertions are about the store's side of the contract. That the named bases are then
     * actually held across replicas is the production lock's responsibility and is asserted in
     * {@code AdvisoryGenerationPublicationLockTest}.</p>
     */
    @Nested
    @DisplayName("per-base serialization")
    class PerBaseSerialization {

        @Test
        @DisplayName("names every distinct base a registered publication touches, and each one once")
        void everyTouchedBaseIsNamedOnce() throws Exception {
            final JobExecution execution = completedJob(31);
            final StepExecution step = stepOf(execution);
            final Path first = StagedGenerationStore.generationPath(stagingDirectory, BASE, 31);
            final Path second =
                    StagedGenerationStore.generationPath(stagingDirectory, OTHER_BASE, 31);
            final Path alsoFirstBase =
                    stagingDirectory.resolve(BASE + ".second-artifact-of-the-same-base");
            Files.writeString(first, "first", StandardCharsets.US_ASCII);
            Files.writeString(second, "second", StandardCharsets.US_ASCII);
            Files.writeString(alsoFirstBase, "third", StandardCharsets.US_ASCII);
            StagedGenerationStore.register(step, BASE, first,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            StagedGenerationStore.register(step, OTHER_BASE, second,
                    StagedGenerationStore.REPORT_RETENTION_LIMIT);
            StagedGenerationStore.register(step, BASE, alsoFirstBase,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);

            store.publishRegistered(execution);

            assertThat(publicationLock.onlyRequest()).containsExactly(BASE, OTHER_BASE);
        }

        @Test
        @DisplayName("uploads nothing outside the lock, so retention never measures a changing set")
        void everyUploadHappensInsideTheLock() throws Exception {
            final JobExecution execution = completedJob(32);
            final StepExecution step = stepOf(execution);
            final Path completed = StagedGenerationStore.generationPath(stagingDirectory, BASE, 32);
            Files.writeString(completed, "bytes", StandardCharsets.US_ASCII);
            StagedGenerationStore.register(step, BASE, completed,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            final GenerationPublicationLock refusingLock = (bases, publication) -> {
                throw new IllegalStateException("the base could not be acquired");
            };
            final StagedGenerationStore refusedStore =
                    new StagedGenerationStore(objectStore, versionedObjectStore, BUCKET,
                            refusingLock, observationRegistry);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> refusedStore.publishRegistered(execution));

            // Failure to acquire is failure to publish. Nothing was uploaded, nothing was pruned, and the
            // registry is intact, so the boundary listener fails the job rather than the store publishing
            // unserialized.
            verify(objectStore, never())
                    .upload(any(String.class), any(String.class), any(InputStream.class));
            verify(objectStore, never()).listObjects(any(String.class), any(String.class));
            assertThat(StagedGenerationStore.registeredArtifactCount(execution)).isEqualTo(1);
        }

        @Test
        @DisplayName("names the single base of an immediate publication and holds it over its retention")
        void immediatePublicationNamesItsOwnBase() {
            final Path completed =
                    completedFileHolding("bytes".getBytes(StandardCharsets.US_ASCII));

            store.publishFile(BASE, completed,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);

            assertThat(publicationLock.onlyRequest()).containsExactly(BASE);
            assertThat(publicationLock.publicationsRun).isEqualTo(1);
        }

        @Test
        @DisplayName("an immediate publication that cannot acquire its base uploads nothing")
        void immediatePublicationRefusedTheLockUploadsNothing() {
            final Path completed =
                    completedFileHolding("bytes".getBytes(StandardCharsets.US_ASCII));
            final GenerationPublicationLock refusingLock = (bases, publication) -> {
                throw new IllegalStateException("the base could not be acquired");
            };
            final StagedGenerationStore refusedStore =
                    new StagedGenerationStore(objectStore, versionedObjectStore, BUCKET,
                            refusingLock, observationRegistry);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> refusedStore.publishFile(BASE, completed,
                            StagedGenerationStore.STANDARD_RETENTION_LIMIT));

            verify(objectStore, never())
                    .upload(any(String.class), any(String.class), any(InputStream.class));
        }

        @Test
        @DisplayName("a retention failure cannot fail an immediate publication either")
        void immediatePublicationSurvivesARetentionFailure() {
            final Path completed =
                    completedFileHolding("bytes".getBytes(StandardCharsets.US_ASCII));
            final List<S3Resource> present = List.of(
                    presentGeneration(BASE + "/G0000000001V00"),
                    presentGeneration(BASE + "/G0000000002V00"),
                    presentGeneration(BASE + "/G0000000003V00"),
                    presentGeneration(BASE + "/G0000000004V00"),
                    presentGeneration(BASE + "/G0000000005V00"),
                    presentGeneration(BASE + "/G0000000006V00"));
            when(objectStore.listObjects(BUCKET, BASE + "/")).thenReturn(present);
            doThrow(new IllegalStateException("the rolled-off object could not be scratched"))
                    .when(versionedObjectStore).deleteObject(any(DeleteObjectRequest.class));

            final StagedGenerationStore.PublishedGeneration published = store.publishFile(
                    BASE, completed, StagedGenerationStore.STANDARD_RETENTION_LIMIT);

            assertThat(published.objectKey()).isEqualTo(BASE + "/G0000000007V00");
            assertThat(published.contentLength()).isEqualTo("bytes".length());
        }

        @Test
        @DisplayName("a publication that cannot measure the base uploads nothing, because it cannot "
                + "name a generation without overwriting one")
        void aPublicationThatCannotMeasureTheBaseUploadsNothing() {
            final Path completed =
                    completedFileHolding("bytes".getBytes(StandardCharsets.US_ASCII));
            when(objectStore.listObjects(any(String.class), any(String.class)))
                    .thenThrow(new IllegalStateException("the object store could not be listed"));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> store.publishFile(BASE, completed,
                            StagedGenerationStore.STANDARD_RETENTION_LIMIT));

            verify(objectStore, never())
                    .upload(any(String.class), any(String.class), any(InputStream.class));
        }
    }

    /**
     * Writes one completed generation file with the supplied bytes.
     *
     * @param  content              the bytes the file holds
     * @return the completed path, which is not a working file
     * @throws UncheckedIOException if it cannot be written
     */
    private Path completedFileHolding(final byte[] content) {
        final Path completed = this.stagingDirectory.resolve("published-generation.dat");
        try {
            Files.write(completed, content);
        } catch (final IOException failure) {
            throw new UncheckedIOException("the test generation could not be written", failure);
        }
        return completed;
    }

    @Nested
    @DisplayName("immediate publication of one completed generation")
    class ImmediatePublication {

        @Test
        @DisplayName("streams the body from the file and reports the file's own length")
        void streamsTheBodyFromTheFile() {
            final byte[] content = "0123456789".getBytes(StandardCharsets.US_ASCII);

            final StagedGenerationStore.PublishedGeneration published =
                    store.publishFile(BASE, completedFileHolding(content),
                            StagedGenerationStore.STANDARD_RETENTION_LIMIT);

            assertThat(published.objectKey()).isEqualTo(BASE + "/G0000000001V00");
            assertThat(published.contentLength()).isEqualTo(content.length);
            verify(objectStore).upload(eq(BUCKET), eq(BASE + "/G0000000001V00"),
                    any(InputStream.class));
        }

        @Test
        @DisplayName("refuses a working file, because a working file is not a generation")
        void refusesAWorkingFile() {
            final Path working = StagedGenerationStore.workingPath(
                    completedFileHolding(new byte[] {1}));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> store.publishFile(BASE, working,
                            StagedGenerationStore.STANDARD_RETENTION_LIMIT))
                    .withMessageContaining("working");
        }
    }

    @Nested
    @DisplayName("retention")
    class Retention {

        @Test
        @DisplayName("keeps the five numerically newest standard generations and scratches the rest")
        void standardRetentionUsesNumericGenerationOrder() {
            final List<S3Resource> generations = List.of(
                    presentGeneration(BASE + "/G0000000011V00"),
                    presentGeneration(BASE + "/G0000000001V00"),
                    presentGeneration(BASE + "/G0000000010V00"),
                    presentGeneration(BASE + "/G0000000002V00"),
                    presentGeneration(BASE + "/G0000000009V00"),
                    presentGeneration(BASE + "/G0000000008V00"),
                    presentGeneration(BASE + "/G0000000007V00"),
                    presentGeneration(BASE + "/not-a-generation"));
            when(objectStore.listObjects(BUCKET, BASE + "/")).thenReturn(generations);

            store.publishFile(BASE, completedFileHolding(new byte[] {1}),
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);

            // SCRATCHED MEANS GONE. The legacy generation group scratched what rolled off its declared
            // depth, which deletes the data set. On a versioned bucket an unqualified delete leaves every
            // version of the rolled-off generation fetchable, so a base "at depth five" would still hold
            // the bytes of every generation it had ever held. The two that rolled off must therefore hold
            // no version at all, while the five kept and the non-generation object are untouched.
            assertThat(versionsByKey)
                    .doesNotContainKey(BASE + "/G0000000002V00")
                    .doesNotContainKey(BASE + "/G0000000001V00")
                    .containsKeys(BASE + "/G0000000011V00", BASE + "/G0000000010V00",
                            BASE + "/G0000000009V00", BASE + "/G0000000008V00",
                            BASE + "/G0000000007V00")
                    .as("a name that is not a generation of this base is not this pass's to scratch")
                    .containsKey(BASE + "/not-a-generation");
            verify(versionedObjectStore, times(2)).deleteObject(any(DeleteObjectRequest.class));
            verify(objectStore, never()).deleteObject(any(String.class), any(String.class));
        }

        @Test
        @DisplayName("the report base may retain ten while the standard depth remains five")
        void theTwoMeasuredDepthsAreDistinct() {
            assertThat(StagedGenerationStore.STANDARD_RETENTION_LIMIT).isEqualTo(5);
            assertThat(StagedGenerationStore.REPORT_RETENTION_LIMIT).isEqualTo(10);
        }
    }

    /**
     * The durable generation number: allocated against the store, never against the framework.
     *
     * <p>These are the tests of the property a key derived from the execution identifier did not have.
     * Execution identifiers restart at one when the batch metadata store is re-created while the bucket
     * keeps every object, so such a key silently replaced a generation an earlier run had published -
     * observed as a hundred-thousand-byte archive becoming a zero-byte latest version. See
     * {@code docs/decision-log.md} entry DL-210.
     */
    @Nested
    @DisplayName("the durable generation number is the store's own")
    class TheDurableGenerationNumber {

        @Test
        @DisplayName("a first publication is generation one, whatever the execution identifier is")
        void aFirstPublicationIsGenerationOne() throws Exception {
            assertThat(publishOneRegisteredArtifact(9_999L))
                    .isEqualTo(BASE + "/G0000000001V00");
        }

        @Test
        @DisplayName("a publication onto a base that already holds generations takes the next one, so a "
                + "re-created metadata store cannot overwrite what an earlier run published")
        void aLaterPublicationTakesTheNextGeneration() throws Exception {
            final List<S3Resource> present = List.of(
                    resource(BASE + "/G0000000001V00"),
                    resource(BASE + "/G0000000003V00"),
                    resource(BASE + "/G0000000002V00"),
                    resource(BASE + "/not-a-generation"));
            when(objectStore.listObjects(BUCKET, BASE + "/")).thenReturn(present);

            // Execution identifier ONE, as a re-created metadata store would hand out, against a base that
            // already holds three generations. The former key shape would have named G0000000001V00 and
            // replaced the first run's object.
            assertThat(publishOneRegisteredArtifact(1L))
                    .isEqualTo(BASE + "/G0000000004V00");
            assertThat(uploaded).doesNotContainKey(BASE + "/G0000000001V00");
        }

        @Test
        @DisplayName("two artifacts registered under one base in one publication take two generations "
                + "rather than one key twice")
        void twoArtifactsUnderOneBaseTakeTwoGenerations() throws Exception {
            final JobExecution execution = completedJob(5);
            final StepExecution step = stepOf(execution);
            final Path first = StagedGenerationStore.generationPath(stagingDirectory, BASE, 5);
            final Path second = stagingDirectory.resolve(BASE + ".second");
            Files.writeString(first, "first", StandardCharsets.US_ASCII);
            Files.writeString(second, "second", StandardCharsets.US_ASCII);
            StagedGenerationStore.register(step, BASE, first,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            StagedGenerationStore.register(step, BASE, second,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);

            store.publishRegistered(execution);

            assertThat(uploaded).containsOnlyKeys(BASE + "/G0000000001V00",
                    BASE + "/G0000000002V00");
        }

        @Test
        @DisplayName("a non-generation object beneath the base does not influence the allocation")
        void aNonGenerationObjectDoesNotInfluenceTheAllocation() throws Exception {
            final List<S3Resource> present = List.of(
                    resource(BASE + "/latest"), resource(BASE + "/G000000000XV00"),
                    resource(BASE + "/G0000000002V00"));
            when(objectStore.listObjects(BUCKET, BASE + "/")).thenReturn(present);

            assertThat(publishOneRegisteredArtifact(77L))
                    .isEqualTo(BASE + "/G0000000003V00");
        }

        /**
         * Registers and publishes one artifact for the given execution identifier.
         *
         * @param  executionId the framework identifier the execution carries
         * @return the key the publication allocated
         * @throws IOException if the local generation cannot be written
         */
        private String publishOneRegisteredArtifact(final long executionId) throws IOException {
            final JobExecution execution = completedJob(executionId);
            final StepExecution step = stepOf(execution);
            final Path completed =
                    StagedGenerationStore.generationPath(stagingDirectory, BASE, executionId);
            Files.writeString(completed, "generation bytes", StandardCharsets.US_ASCII);
            StagedGenerationStore.register(step, BASE, completed,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            return store.publishRegistered(execution).getFirst().objectKey();
        }
    }

    /**
     * The abnormal disposition: an execution that did not complete discards what it allocated.
     *
     * <p>Publication is skipped for such an execution, so its sealed generations name nothing durable and
     * its working files name nothing at all. See {@code docs/decision-log.md} entry DL-211.
     */
    @Nested
    @DisplayName("an execution that did not complete discards its own local artifacts")
    class TheAbnormalDisposition {

        @Test
        @DisplayName("removes the sealed generations and working files THIS EXECUTION REGISTERED, and "
                + "nothing else")
        void removesOnlyThisExecutionsOwnRegisteredArtifacts() throws Exception {
            final JobExecution failed = failedJob(4);
            final Path ownGeneration =
                    StagedGenerationStore.generationPath(stagingDirectory, BASE, 4);
            final Path secondGeneration =
                    StagedGenerationStore.generationPath(stagingDirectory, OTHER_BASE, 4);
            final Path ownWorking = StagedGenerationStore.workingPath(secondGeneration);
            final Path anotherExecutionsGeneration =
                    StagedGenerationStore.generationPath(stagingDirectory, BASE, 5);
            final Path unrelatedInput = stagingDirectory.resolve("AWS.M2.CARDDEMO.DALYTRAN.PS");
            Files.writeString(ownGeneration, "sealed", StandardCharsets.US_ASCII);
            Files.writeString(secondGeneration, "sealed too", StandardCharsets.US_ASCII);
            Files.writeString(ownWorking, "half composed", StandardCharsets.US_ASCII);
            Files.writeString(anotherExecutionsGeneration, "not mine", StandardCharsets.US_ASCII);
            Files.writeString(unrelatedInput, "an input", StandardCharsets.US_ASCII);
            StagedGenerationStore.register(failed, BASE, ownGeneration,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            StagedGenerationStore.register(failed, OTHER_BASE, secondGeneration,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);

            assertThat(StagedGenerationStore.discardLocalArtifactsOf(failed, stagingDirectory))
                    .as("two registered generations and the one working sibling the store names for "
                            + "the second of them")
                    .isEqualTo(3);

            assertThat(ownGeneration).doesNotExist();
            assertThat(secondGeneration).doesNotExist();
            assertThat(ownWorking)
                    .as("the working sibling is named by the store from the registered path, so it is "
                            + "removed without ever being matched by name")
                    .doesNotExist();
            assertThat(anotherExecutionsGeneration)
                    .as("a concurrently running execution registered its own generation on its own "
                            + "context, so this sweep cannot see it")
                    .exists();
            assertThat(unrelatedInput)
                    .as("a staged input was never registered and is not this sweep's business")
                    .exists();
        }

        @Test
        @DisplayName("A PLANTED DECOY IS NOT DELETED: a file whose name merely CONTAINS this execution's "
                + "token survives, because selection is by registration and never by substring")
        void aFileMerelyContainingTheTokenIsNotDeleted() throws Exception {
            final JobExecution failed = failedJob(4);
            final Path registered =
                    StagedGenerationStore.generationPath(stagingDirectory, BASE, 4);
            Files.writeString(registered, "sealed", StandardCharsets.US_ASCII);
            StagedGenerationStore.register(failed, BASE, registered,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            // Three shapes that the withdrawn substring rule would each have deleted. The token is
            // derived from a monotonically increasing framework identifier, so its value is guessable
            // and a name carrying it is trivial to construct.
            final Path prefixDecoy =
                    stagingDirectory.resolve("something-else.G0000000004V00");
            final Path suffixDecoy =
                    stagingDirectory.resolve(BASE + ".G0000000004V00.keep-this");
            final Path embeddedDecoy =
                    stagingDirectory.resolve("audit-G0000000004V00-report.txt");
            for (final Path decoy : List.of(prefixDecoy, suffixDecoy, embeddedDecoy)) {
                Files.writeString(decoy, "not the store's to delete", StandardCharsets.US_ASCII);
            }

            assertThat(StagedGenerationStore.discardLocalArtifactsOf(failed, stagingDirectory))
                    .as("exactly the one registered path")
                    .isEqualTo(1);

            assertThat(registered).doesNotExist();
            assertThat(prefixDecoy)
                    .as("THE DEFECT THIS PINS. The withdrawn rule deleted every regular file whose name "
                            + "contained the token, so a cleanup path could be aimed by choosing a "
                            + "filename")
                    .exists();
            assertThat(suffixDecoy).exists();
            assertThat(embeddedDecoy).exists();
        }

        @Test
        @DisplayName("an execution that registered nothing removes nothing, even when a well-named file "
                + "carrying its exact token is sitting in the root")
        void anExecutionWithNoRegistrationsRemovesNothing() throws Exception {
            final Path wellNamed = StagedGenerationStore.generationPath(stagingDirectory, BASE, 88);
            Files.writeString(wellNamed, "allocated by something else", StandardCharsets.US_ASCII);
            Files.writeString(stagingDirectory.resolve("AWS.M2.CARDDEMO.DALYTRAN.PS"), "input",
                    StandardCharsets.US_ASCII);

            assertThat(StagedGenerationStore.discardLocalArtifactsOf(failedJob(88),
                    stagingDirectory)).isZero();
            assertThat(wellNamed)
                    .as("registration happens at completion, so an execution with no registrations has "
                            + "no claim on any file whatever it is called")
                    .exists();
            assertThat(stagingDirectory.resolve("AWS.M2.CARDDEMO.DALYTRAN.PS")).exists();
        }

        @Test
        @DisplayName("a directory carrying the token is left alone, because a directory is not an "
                + "artifact this store hands out")
        void aDirectoryCarryingTheTokenIsLeftAlone() throws Exception {
            final Path directory = stagingDirectory.resolve(BASE + ".G0000000006V00");
            Files.createDirectory(directory);
            Files.writeString(directory.resolve("occupant"), "x", StandardCharsets.US_ASCII);

            assertThat(StagedGenerationStore.discardLocalArtifactsOf(failedJob(6), stagingDirectory))
                    .isZero();
            assertThat(directory).exists();
        }

        @Test
        @DisplayName("a registered path replaced by a symbolic link since registration is not followed, "
                + "so the deletion cannot be redirected onto the link's target")
        void aRegisteredPathReplacedByALinkIsNotFollowed() throws Exception {
            final JobExecution failed = failedJob(9);
            final Path registered =
                    StagedGenerationStore.generationPath(stagingDirectory, BASE, 9);
            Files.writeString(registered, "sealed", StandardCharsets.US_ASCII);
            StagedGenerationStore.register(failed, BASE, registered,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);
            final Path elsewhere = stagingDirectory.resolve("valuable-unrelated-file");
            Files.writeString(elsewhere, "must survive", StandardCharsets.US_ASCII);
            Files.delete(registered);
            try {
                Files.createSymbolicLink(registered, elsewhere);
            } catch (final UnsupportedOperationException | IOException linksUnavailable) {
                return;
            }

            assertThat(StagedGenerationStore.discardLocalArtifactsOf(failed, stagingDirectory))
                    .as("the registered name is no longer a regular file this process owns, so nothing "
                            + "is removed under it")
                    .isZero();

            assertThat(elsewhere)
                    .as("and the link's target is untouched, which is the property that matters: a "
                            + "delete that followed the link would have removed it")
                    .exists();
        }

        @Test
        @DisplayName("an unreadable staging root is reported rather than raised, because the execution "
                + "has already failed for its own reason")
        void anUnreadableStagingRootIsReportedRatherThanRaised() {
            assertThat(StagedGenerationStore.discardLocalArtifactsOf(failedJob(7),
                    stagingDirectory.resolve("no-such-directory"))).isZero();
        }

        /**
         * A job execution that ended without completing.
         *
         * @param  executionId the framework identifier it carries
         * @return the failed execution
         */
        private JobExecution failedJob(final long executionId) {
            final JobExecution execution = new JobExecution(
                    new JobInstance(executionId + 500, "testJob"), executionId,
                    new JobParameters());
            execution.setStatus(BatchStatus.FAILED);
            return execution;
        }
    }

    /**
     * Resolving the current local generation of a base, which is resolution <em>by name</em> and is
     * therefore the point where the filesystem could otherwise decide the answer.
     *
     * <p>The highest generation token wins, and a token is part of a filename. On a shared host that
     * makes a name a thing an attacker can choose: a local actor able to write the staging root can
     * plant a file, or a symbolic link, named with a generation higher than any this store has
     * allocated, and the next job to consume that base then reads the planted records as its own input.
     * These are the tests of the two controls that stand between the name and the answer - the anchored
     * grammar, and the trust predicate that asks whether the entry is really a staged artefact of this
     * deployment.
     */
    @Nested
    @DisplayName("the current local generation is resolved by name, so the name is not enough")
    class TheCurrentLocalGeneration {

        @Test
        @DisplayName("the highest trusted generation of the base is the answer, and a generation of a "
                + "different base is not a candidate for it")
        void theHighestTrustedGenerationWins() throws Exception {
            Files.writeString(StagedGenerationStore.generationPath(stagingDirectory, BASE, 3),
                    "third", StandardCharsets.US_ASCII);
            Files.writeString(StagedGenerationStore.generationPath(stagingDirectory, BASE, 11),
                    "eleventh", StandardCharsets.US_ASCII);
            Files.writeString(StagedGenerationStore.generationPath(stagingDirectory, OTHER_BASE, 99),
                    "another base entirely", StandardCharsets.US_ASCII);

            assertThat(StagedGenerationStore.currentLocalGeneration(stagingDirectory, BASE))
                    .get()
                    .extracting(path -> path.getFileName().toString())
                    .isEqualTo(BASE + ".G0000000011V00");
        }

        @Test
        @DisplayName("A PLANTED SYMBOLIC LINK WITH A HIGHER GENERATION IS NOT THE ANSWER: the genuine "
                + "generation below it is, so the link cannot substitute the job's input")
        void aPlantedLinkWithAHigherGenerationIsRefused() throws Exception {
            final Path genuine = StagedGenerationStore.generationPath(stagingDirectory, BASE, 7);
            Files.writeString(genuine, "the job's own records", StandardCharsets.US_ASCII);
            final Path attackerContent = stagingDirectory.resolve("attacker-records");
            Files.writeString(attackerContent, "records the attacker chose",
                    StandardCharsets.US_ASCII);
            final Path plantedLink =
                    StagedGenerationStore.generationPath(stagingDirectory, BASE, 9_999);
            try {
                Files.createSymbolicLink(plantedLink, attackerContent);
            } catch (final UnsupportedOperationException | IOException linksUnavailable) {
                return;
            }

            assertThat(StagedGenerationStore.currentLocalGeneration(stagingDirectory, BASE))
                    .as("THE DEFECT THIS PINS. The withdrawn check followed the link and reported it as "
                            + "a regular file, so the highest-numbered name won and the job read the "
                            + "attacker's records")
                    .get()
                    .isEqualTo(genuine);
        }

        @Test
        @DisplayName("a planted directory with a higher generation is not the answer either, because a "
                + "directory is not an artifact this store hands out")
        void aPlantedDirectoryWithAHigherGenerationIsRefused() throws Exception {
            final Path genuine = StagedGenerationStore.generationPath(stagingDirectory, BASE, 2);
            Files.writeString(genuine, "the job's own records", StandardCharsets.US_ASCII);
            Files.createDirectory(
                    StagedGenerationStore.generationPath(stagingDirectory, BASE, 4_242));

            assertThat(StagedGenerationStore.currentLocalGeneration(stagingDirectory, BASE))
                    .get()
                    .isEqualTo(genuine);
        }

        @Test
        @DisplayName("a candidate anybody may write is refused however well it is named, because a file "
                + "that can be rewritten in place after it is checked is not evidence of what the job "
                + "produced")
        void aWorldWritableCandidateIsRefused() throws Exception {
            final Path genuine = StagedGenerationStore.generationPath(stagingDirectory, BASE, 5);
            Files.writeString(genuine, "the job's own records", StandardCharsets.US_ASCII);
            final Path loose = StagedGenerationStore.generationPath(stagingDirectory, BASE, 6);
            Files.writeString(loose, "anybody could have written this", StandardCharsets.US_ASCII);
            try {
                Files.setPosixFilePermissions(loose,
                        PosixFilePermissions.fromString("rw-rw-rw-"));
            } catch (final UnsupportedOperationException noPosixView) {
                return;
            }

            assertThat(StagedGenerationStore.currentLocalGeneration(stagingDirectory, BASE))
                    .get()
                    .isEqualTo(genuine);
        }

        @Test
        @DisplayName("a name that is not the store's own canonical spelling of a generation is not a "
                + "candidate, so a short or over-padded token cannot outrank a real one")
        void onlyTheCanonicalGenerationSpellingIsACandidate() throws Exception {
            final Path genuine = StagedGenerationStore.generationPath(stagingDirectory, BASE, 8);
            Files.writeString(genuine, "the job's own records", StandardCharsets.US_ASCII);
            for (final String nonCanonical : List.of(
                    // Over-padded: eleven digits for a value the store pads to ten.
                    BASE + ".G00000000009V00",
                    // Under-padded: the same value with no padding at all.
                    BASE + ".G9V00",
                    // A version suffix this store never writes.
                    BASE + ".G0000000009V01",
                    // The working suffix, which names a file that was never completed.
                    BASE + ".G0000000009V00.part",
                    // A separator this store never writes between the base and the token.
                    BASE + "-G0000000009V00")) {
                Files.writeString(stagingDirectory.resolve(nonCanonical), "not a generation",
                        StandardCharsets.US_ASCII);
            }

            assertThat(StagedGenerationStore.currentLocalGeneration(stagingDirectory, BASE))
                    .as("each of the five spells a higher number than the genuine generation and none is "
                            + "the spelling this store would have written for it, so none is a "
                            + "candidate: the grammar is anchored by round trip through the writer "
                            + "rather than by a pattern that could drift from it")
                    .get()
                    .isEqualTo(genuine);
        }

        @Test
        @DisplayName("a root holding no generation of the base answers empty, which is what makes the "
                + "consuming job refuse rather than read half its input")
        void aRootHoldingNoGenerationAnswersEmpty() throws Exception {
            Files.writeString(stagingDirectory.resolve("AWS.M2.CARDDEMO.DALYTRAN.PS"), "an input",
                    StandardCharsets.US_ASCII);

            assertThat(StagedGenerationStore.currentLocalGeneration(stagingDirectory, BASE))
                    .isEmpty();
        }

        @Test
        @DisplayName("a staging root that is not a directory answers empty rather than raising")
        void aMissingRootAnswersEmpty() {
            assertThat(StagedGenerationStore.currentLocalGeneration(
                    stagingDirectory.resolve("no-such-directory"), BASE))
                    .isEmpty();
        }
    }
}
