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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @BeforeEach
    void setUp() {
        this.objectStore = mock(S3Operations.class);
        this.uploaded = new LinkedHashMap<>();
        when(this.objectStore.listObjects(any(String.class), any(String.class)))
                .thenReturn(List.of());
        doAnswer(invocation -> {
            final String key = invocation.getArgument(1, String.class);
            try (InputStream body = invocation.getArgument(2, InputStream.class)) {
                this.uploaded.put(key, body.readAllBytes());
            }
            return null;
        }).when(this.objectStore).upload(eq(BUCKET), any(String.class), any(InputStream.class));
        this.store = new StagedGenerationStore(this.objectStore, BUCKET);
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
        @DisplayName("uses a ten-digit minimum width and never wraps at the former four-digit boundary")
        void executionIdentifiersNeverWrap() {
            assertThat(StagedGenerationStore.objectKey(BASE, 7))
                    .isEqualTo(BASE + "/G0000000007V00");
            assertThat(StagedGenerationStore.objectKey(BASE, 10_000))
                    .isEqualTo(BASE + "/G0000010000V00");
            assertThat(StagedGenerationStore.objectKey(BASE, 10_007))
                    .isEqualTo(BASE + "/G0000010007V00")
                    .isNotEqualTo(StagedGenerationStore.objectKey(BASE, 7));
        }

        @Test
        @DisplayName("uses the same generation token for the local completed file")
        void localAndDurableNamesShareOneGenerationToken() {
            assertThat(StagedGenerationStore.generationPath(
                    stagingDirectory, BASE, 42).getFileName().toString())
                    .isEqualTo(BASE + ".G0000000042V00");
        }

        @Test
        @DisplayName("refuses path separators and negative identifiers before composing a key")
        void hostileIdentityPartsAreRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> StagedGenerationStore.objectKey("../outside", 1));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> StagedGenerationStore.objectKey(BASE, -1));
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

            assertThat(result).singleElement().satisfies(generation -> {
                assertThat(generation.bucket()).isEqualTo(BUCKET);
                assertThat(generation.objectKey()).isEqualTo(BASE + "/G0000000011V00");
                assertThat(generation.contentLength()).isEqualTo("new generation".length());
            });
            assertThat(uploaded).containsOnlyKeys(BASE + "/G0000000011V00");
            assertThat(uploaded.get(BASE + "/G0000000011V00"))
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
                            eq(OTHER_BASE + "/G0000000014V00"), any(InputStream.class));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> store.publishRegistered(execution));

            verify(objectStore).deleteObject(BUCKET, BASE + "/G0000000014V00");
            assertThat(StagedGenerationStore.registeredArtifactCount(execution)).isEqualTo(2);
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

            store.publishBytes(BASE, 11, new byte[] {1},
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
}