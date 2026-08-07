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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.batch.item.ExecutionContext;
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
    private StagedGenerationStore store;
    private Map<String, byte[]> uploaded;
    private RecordingPublicationLock publicationLock;

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
        this.uploaded = new LinkedHashMap<>();
        this.publicationLock = new RecordingPublicationLock();
        when(this.objectStore.listObjects(any(String.class), any(String.class)))
                .thenReturn(List.of());
        doAnswer(invocation -> {
            final String key = invocation.getArgument(1, String.class);
            try (InputStream body = invocation.getArgument(2, InputStream.class)) {
                this.uploaded.put(key, body.readAllBytes());
            }
            return null;
        }).when(this.objectStore).upload(eq(BUCKET), any(String.class), any(InputStream.class));
        this.store = new StagedGenerationStore(this.objectStore, BUCKET, this.publicationLock);
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
        @DisplayName("a failed delegate close leaves a working file and registers nothing")
        void aFailedCloseCannotPublishAPartialFile() {
            final JobExecution execution = completedJob(3);
            final StepExecution step = stepOf(execution);
            final Path completed =
                    StagedGenerationStore.generationPath(stagingDirectory, BASE, 3);
            final Path working = StagedGenerationStore.workingPath(completed);
            assertThatNoException().isThrownBy(() ->
                    Files.writeString(working, "partial", StandardCharsets.US_ASCII));
            final ItemStreamWriter<String> delegate = mock();
            doThrow(new IllegalStateException("close failed")).when(delegate).close();
            final ItemStreamWriter<String> writer = StagedGenerationStore.completingWriter(
                    delegate, step, BASE, working, completed,
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(writer::close);

            assertThat(working).exists();
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

            verify(objectStore).deleteObject(BUCKET, BASE + "/G0000000001V00");
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

            // Both uploads had already succeeded when the alias failed, so both must be scratched.
            verify(objectStore).deleteObject(BUCKET, BASE + "/G0000000001V00");
            verify(objectStore).deleteObject(BUCKET, OTHER_BASE + "/G0000000001V00");
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
                    resource(BASE + "/G0000000001V00"), resource(BASE + "/G0000000002V00"),
                    resource(BASE + "/G0000000003V00"), resource(BASE + "/G0000000004V00"),
                    resource(BASE + "/G0000000005V00"), resource(BASE + "/G0000000006V00"));
            when(objectStore.listObjects(BUCKET, BASE + "/")).thenReturn(present);
            doThrow(new IllegalStateException("the rolled-off object could not be scratched"))
                    .when(objectStore).deleteObject(any(String.class), any(String.class));

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
                    new StagedGenerationStore(objectStore, BUCKET, refusingLock);

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
                    new StagedGenerationStore(objectStore, BUCKET, refusingLock);

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
                    resource(BASE + "/G0000000001V00"), resource(BASE + "/G0000000002V00"),
                    resource(BASE + "/G0000000003V00"), resource(BASE + "/G0000000004V00"),
                    resource(BASE + "/G0000000005V00"), resource(BASE + "/G0000000006V00"));
            when(objectStore.listObjects(BUCKET, BASE + "/")).thenReturn(present);
            doThrow(new IllegalStateException("the rolled-off object could not be scratched"))
                    .when(objectStore).deleteObject(any(String.class), any(String.class));

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
                    resource(BASE + "/G0000000011V00"),
                    resource(BASE + "/G0000000001V00"),
                    resource(BASE + "/G0000000010V00"),
                    resource(BASE + "/G0000000002V00"),
                    resource(BASE + "/G0000000009V00"),
                    resource(BASE + "/G0000000008V00"),
                    resource(BASE + "/G0000000007V00"),
                    resource(BASE + "/not-a-generation"));
            when(objectStore.listObjects(BUCKET, BASE + "/")).thenReturn(generations);

            store.publishFile(BASE, completedFileHolding(new byte[] {1}),
                    StagedGenerationStore.STANDARD_RETENTION_LIMIT);

            verify(objectStore).deleteObject(BUCKET, BASE + "/G0000000002V00");
            verify(objectStore).deleteObject(BUCKET, BASE + "/G0000000001V00");
            verify(objectStore, times(2)).deleteObject(eq(BUCKET), any(String.class));
            verify(objectStore, never()).deleteObject(BUCKET, BASE + "/not-a-generation");
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
        @DisplayName("removes this execution's sealed generations and working files, and nothing else")
        void removesOnlyThisExecutionsOwnArtifacts() throws Exception {
            final JobExecution failed = failedJob(4);
            final Path ownGeneration =
                    StagedGenerationStore.generationPath(stagingDirectory, BASE, 4);
            final Path ownWorking = StagedGenerationStore.workingPath(
                    StagedGenerationStore.generationPath(stagingDirectory, OTHER_BASE, 4));
            final Path anotherExecutionsGeneration =
                    StagedGenerationStore.generationPath(stagingDirectory, BASE, 5);
            final Path unrelatedInput = stagingDirectory.resolve("AWS.M2.CARDDEMO.DALYTRAN.PS");
            Files.writeString(ownGeneration, "sealed", StandardCharsets.US_ASCII);
            Files.writeString(ownWorking, "half composed", StandardCharsets.US_ASCII);
            Files.writeString(anotherExecutionsGeneration, "not mine", StandardCharsets.US_ASCII);
            Files.writeString(unrelatedInput, "an input", StandardCharsets.US_ASCII);

            assertThat(StagedGenerationStore.discardLocalArtifactsOf(failed, stagingDirectory))
                    .isEqualTo(2);

            assertThat(ownGeneration).doesNotExist();
            assertThat(ownWorking).doesNotExist();
            assertThat(anotherExecutionsGeneration)
                    .as("a concurrently running execution's generation carries a different token and"
                            + " must survive")
                    .exists();
            assertThat(unrelatedInput)
                    .as("a staged input carries no generation token at all and is not this sweep's"
                            + " business")
                    .exists();
        }

        @Test
        @DisplayName("an execution with nothing of its own to discard removes nothing and reports so")
        void anExecutionWithNothingToDiscardRemovesNothing() throws Exception {
            Files.writeString(stagingDirectory.resolve("AWS.M2.CARDDEMO.DALYTRAN.PS"), "input",
                    StandardCharsets.US_ASCII);

            assertThat(StagedGenerationStore.discardLocalArtifactsOf(failedJob(88),
                    stagingDirectory)).isZero();
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
}