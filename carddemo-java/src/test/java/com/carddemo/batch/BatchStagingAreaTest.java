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
package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.config.AwsProperties;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamWriter;

/**
 * Verifies the one S3-backed staging boundary shared by every dataset-producing batch job.
 *
 * <p>The adapter deliberately keeps Spring Batch writers on local files because their execution
 * state requires {@link java.io.File} access. These tests therefore pin both sides of the boundary:
 * staged reads resolve through {@link S3Operations}, while local or in-memory outputs are uploaded
 * only after their producing writer has closed successfully.
 */
@DisplayName("BatchStagingArea - the single S3 boundary for staged batch datasets")
final class BatchStagingAreaTest {

    /** Configured bucket used to prove that no operation substitutes a hardcoded destination. */
    private static final String BUCKET = "unit-test-batch-staging";

    /** Explicit key used by read and write assertions. */
    private static final String OBJECT_KEY = "jobs/42/output.dat";

    /** Content written through each publication form. */
    private static final byte[] CONTENT = "fixed-width-content".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    private Path temporaryDirectory;

    /**
     * Builds valid settings with a nominated bucket.
     *
     * @param bucket configured staging bucket
     * @return settings accepted by the staging adapter
     */
    private static AwsProperties properties(final String bucket) {
        return new AwsProperties("us-west-2", null,
                new AwsProperties.S3(bucket),
                new AwsProperties.Sqs("carddemo-jobs.fifo", "carddemo-jobs"),
                new AwsProperties.Sns("carddemo-job-notifications"));
    }

    /**
     * Builds an adapter over a mocked object store.
     *
     * @param objectStore object-store collaborator
     * @return adapter under test
     */
    private static BatchStagingArea stagingArea(final S3Operations objectStore) {
        return new BatchStagingArea(objectStore, properties(BUCKET));
    }

    /**
     * Captures an upload body while the stream is open.
     *
     * @param objectStore object-store mock to prepare
     * @param objectKey expected destination key
     * @return holder populated when an upload occurs
     * @throws IOException if the invocation body cannot be read
     */
    private static AtomicReference<byte[]> captureUpload(final S3Operations objectStore,
            final String objectKey) throws IOException {
        final AtomicReference<byte[]> uploaded = new AtomicReference<>();
        when(objectStore.upload(eq(BUCKET), eq(objectKey), any(InputStream.class)))
                .thenAnswer(invocation -> {
                    final InputStream body = invocation.getArgument(2, InputStream.class);
                    uploaded.set(body.readAllBytes());
                    return mock(S3Resource.class);
                });
        return uploaded;
    }

    @Nested
    @DisplayName("Configuration and staged reads")
    final class ConfigurationAndReads {

        @Test
        @DisplayName("the configured bucket is exposed unchanged")
        void configuredBucketIsExposedUnchanged() {
            final BatchStagingArea staging = stagingArea(mock(S3Operations.class));

            assertThat(staging.bucket()).isEqualTo(BUCKET);
        }

        @Test
        @DisplayName("existence checks and downloads always address the configured bucket")
        void existenceChecksAndDownloadsUseTheConfiguredBucket() {
            final S3Operations objectStore = mock(S3Operations.class);
            final S3Resource resource = mock(S3Resource.class);
            when(objectStore.objectExists(BUCKET, OBJECT_KEY)).thenReturn(true);
            when(objectStore.download(BUCKET, OBJECT_KEY)).thenReturn(resource);
            final BatchStagingArea staging = stagingArea(objectStore);

            assertThat(staging.holds(OBJECT_KEY)).isTrue();
            assertThat(staging.stagedInput(OBJECT_KEY)).isSameAs(resource);

            verify(objectStore).objectExists(BUCKET, OBJECT_KEY);
            verify(objectStore).download(BUCKET, OBJECT_KEY);
        }
    }

    @Nested
    @DisplayName("Publication forms")
    final class PublicationForms {

        @Test
        @DisplayName("a path-only publication uses the local file name as its object key")
        void pathOnlyPublicationUsesTheFileName() throws IOException {
            final Path source = temporaryDirectory.resolve("generation.dat");
            Files.write(source, CONTENT);
            final S3Operations objectStore = mock(S3Operations.class);
            final AtomicReference<byte[]> uploaded = captureUpload(objectStore, "generation.dat");

            stagingArea(objectStore).publish(source);

            assertThat(uploaded.get()).containsExactly(CONTENT);
            verify(objectStore).upload(eq(BUCKET), eq("generation.dat"), any(InputStream.class));
        }

        @Test
        @DisplayName("an explicit-key publication streams the complete local file")
        void explicitKeyPublicationStreamsTheCompleteLocalFile() throws IOException {
            final Path source = temporaryDirectory.resolve("local-buffer.dat");
            Files.write(source, CONTENT);
            final S3Operations objectStore = mock(S3Operations.class);
            final AtomicReference<byte[]> uploaded = captureUpload(objectStore, OBJECT_KEY);

            stagingArea(objectStore).publish(OBJECT_KEY, source);

            assertThat(uploaded.get()).containsExactly(CONTENT);
            verify(objectStore).upload(eq(BUCKET), eq(OBJECT_KEY), any(InputStream.class));
        }

        @Test
        @DisplayName("an in-memory publication uploads the complete byte generation")
        void inMemoryPublicationUploadsTheCompleteGeneration() throws IOException {
            final S3Operations objectStore = mock(S3Operations.class);
            final AtomicReference<byte[]> uploaded = captureUpload(objectStore, OBJECT_KEY);

            stagingArea(objectStore).publish(OBJECT_KEY, CONTENT);

            assertThat(uploaded.get()).containsExactly(CONTENT);
            verify(objectStore).upload(eq(BUCKET), eq(OBJECT_KEY), any(InputStream.class));
        }

        @Test
        @DisplayName("a local read failure retains the source path and cause")
        void localReadFailureRetainsItsContext() {
            final Path absent = temporaryDirectory.resolve("absent.dat");

            assertThatThrownBy(() -> stagingArea(mock(S3Operations.class))
                    .publish(OBJECT_KEY, absent))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining(absent.toString())
                    .hasCauseInstanceOf(IOException.class);
        }
    }

    @Nested
    @DisplayName("Publishing writer lifecycle")
    final class PublishingWriterLifecycle {

        @Test
        @DisplayName("open, update and write pass through before one publication follows close")
        void delegateLifecycleCompletesBeforeOnePublication() throws Exception {
            final Path source = temporaryDirectory.resolve("writer-output.dat");
            Files.write(source, CONTENT);
            final List<String> events = new ArrayList<>();
            final RecordingWriter delegate = new RecordingWriter(events, null);
            final S3Operations objectStore = mock(S3Operations.class);
            when(objectStore.upload(eq(BUCKET), eq("writer-output.dat"), any(InputStream.class)))
                    .thenAnswer(invocation -> {
                        events.add("publish");
                        return mock(S3Resource.class);
                    });
            final ItemStreamWriter<String> writer = stagingArea(objectStore)
                    .publishingWriter(delegate, source);
            final ExecutionContext context = new ExecutionContext();
            final Chunk<String> chunk = Chunk.of("first", "second");

            writer.open(context);
            writer.update(context);
            writer.write(chunk);
            writer.close();

            assertThat(delegate.openContext()).isSameAs(context);
            assertThat(delegate.updateContext()).isSameAs(context);
            assertThat(delegate.written()).isSameAs(chunk);
            assertThat(events).containsExactly("open", "update", "write", "close", "publish");
            verify(objectStore, times(1))
                    .upload(eq(BUCKET), eq("writer-output.dat"), any(InputStream.class));
        }

        @Test
        @DisplayName("a delegate close failure prevents publication")
        void delegateCloseFailurePreventsPublication() {
            final Path source = temporaryDirectory.resolve("writer-output.dat");
            final RuntimeException failure = new IllegalStateException("delegate close failed");
            final RecordingWriter delegate = new RecordingWriter(new ArrayList<>(), failure);
            final S3Operations objectStore = mock(S3Operations.class);
            final ItemStreamWriter<String> writer = stagingArea(objectStore)
                    .publishingWriter(delegate, source);

            assertThatThrownBy(writer::close).isSameAs(failure);
            verify(objectStore, never())
                    .upload(any(String.class), any(String.class), any(InputStream.class));
        }
    }

    @Nested
    @DisplayName("Boundary validation")
    final class BoundaryValidation {

        @Test
        @DisplayName("the constructor refuses missing collaborators and bucket settings")
        void constructorRefusesMissingCollaboratorsAndBucketSettings() {
            final S3Operations objectStore = mock(S3Operations.class);

            assertThatNullPointerException()
                    .isThrownBy(() -> new BatchStagingArea(null, properties(BUCKET)))
                    .withMessage("objectStore must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> new BatchStagingArea(objectStore, null))
                    .withMessage("properties must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> new BatchStagingArea(objectStore,
                            new AwsProperties("us-west-2", null, null,
                                    new AwsProperties.Sqs("carddemo-jobs.fifo", "carddemo-jobs"),
                                    new AwsProperties.Sns("carddemo-job-notifications"))))
                    .withMessage("properties.s3 must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> new BatchStagingArea(objectStore, properties(null)))
                    .withMessage("batch staging bucket must not be null");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BatchStagingArea(objectStore, properties(" \t")))
                    .withMessage("batch staging bucket must not be blank");
        }

        @Test
        @DisplayName("read and write operations refuse missing or blank object keys")
        void operationsRefuseMissingOrBlankObjectKeys() {
            final BatchStagingArea staging = stagingArea(mock(S3Operations.class));

            assertThatNullPointerException().isThrownBy(() -> staging.holds(null))
                    .withMessage("object key must not be null");
            assertThatIllegalArgumentException().isThrownBy(() -> staging.holds("  "))
                    .withMessage("object key must not be blank");
            assertThatNullPointerException().isThrownBy(() -> staging.stagedInput(null))
                    .withMessage("object key must not be null");
            assertThatIllegalArgumentException().isThrownBy(() -> staging.stagedInput("\n"))
                    .withMessage("object key must not be blank");
            assertThatNullPointerException()
                    .isThrownBy(() -> staging.publish(null, temporaryDirectory.resolve("file")))
                    .withMessage("object key must not be null");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> staging.publish("\t", temporaryDirectory.resolve("file")))
                    .withMessage("object key must not be blank");
            assertThatNullPointerException()
                    .isThrownBy(() -> staging.publish(null, CONTENT))
                    .withMessage("object key must not be null");
        }

        @Test
        @DisplayName("publication forms and writer creation refuse missing sources or content")
        void publicationRefusesMissingSourcesContentAndDelegate() {
            final BatchStagingArea staging = stagingArea(mock(S3Operations.class));
            final RecordingWriter delegate = new RecordingWriter(new ArrayList<>(), null);

            assertThatNullPointerException().isThrownBy(() -> staging.publish((Path) null))
                    .withMessage("source must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> staging.publish(OBJECT_KEY, (Path) null))
                    .withMessage("source must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> staging.publish(OBJECT_KEY, (byte[]) null))
                    .withMessage("content must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> staging.publishingWriter(null, temporaryDirectory))
                    .withMessage("delegate must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> staging.publishingWriter(delegate, null))
                    .withMessage("source must not be null");
        }
    }

    /**
     * Test writer that records the lifecycle without unchecked generic mocks.
     */
    private static final class RecordingWriter implements ItemStreamWriter<String> {

        /** Ordered lifecycle events shared with the object-store answer. */
        private final List<String> events;

        /** Optional failure raised by close. */
        private final RuntimeException closeFailure;

        /** Context received by open. */
        private ExecutionContext openContext;

        /** Context received by update. */
        private ExecutionContext updateContext;

        /** Chunk received by write. */
        private Chunk<? extends String> written;

        /**
         * Creates a recording writer.
         *
         * @param events ordered event sink
         * @param closeFailure optional close failure
         */
        RecordingWriter(final List<String> events, final RuntimeException closeFailure) {
            this.events = events;
            this.closeFailure = closeFailure;
        }

        @Override
        public void open(final ExecutionContext executionContext) {
            this.openContext = executionContext;
            this.events.add("open");
        }

        @Override
        public void update(final ExecutionContext executionContext) {
            this.updateContext = executionContext;
            this.events.add("update");
        }

        @Override
        public void write(final Chunk<? extends String> chunk) {
            this.written = chunk;
            this.events.add("write");
        }

        @Override
        public void close() {
            this.events.add("close");
            if (this.closeFailure != null) {
                throw this.closeFailure;
            }
        }

        /**
         * Returns the open context.
         *
         * @return received context
         */
        ExecutionContext openContext() {
            return this.openContext;
        }

        /**
         * Returns the update context.
         *
         * @return received context
         */
        ExecutionContext updateContext() {
            return this.updateContext;
        }

        /**
         * Returns the written chunk.
         *
         * @return received chunk
         */
        Chunk<? extends String> written() {
            return this.written;
        }
    }
}