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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.carddemo.config.AwsProperties;

import io.awspring.cloud.s3.S3Operations;

/**
 * The single object-store boundary for batch datasets staged through the configured S3 bucket.
 *
 * <p>Spring Batch's file writers require a real local file, while the migration contract requires
 * batch inputs and outputs to cross the S3 staging boundary. The two roles are therefore explicit:
 * jobs retain a local path as the writer's execution-scoped buffer, and publish that completed file
 * through this component. Readers prefer {@link #stagedInput(String)} when {@link #holds(String)}
 * confirms that the named object exists, and otherwise retain their existing local or classpath
 * fallback.
 *
 * <p>The bucket is provisioned outside the application. This component never creates, configures or
 * deletes a bucket and implements no retention policy. It only checks for, downloads and uploads
 * objects in the bucket bound by {@code carddemo.aws.s3.batch-staging-bucket}.
 */
@Component
public final class BatchStagingArea {

    /** Object-store operations supplied by Spring Cloud AWS. */
    private final S3Operations objectStore;

    /** Pre-provisioned bucket that holds every staged batch object. */
    private final String bucket;

    /**
     * Creates the staging boundary.
     *
     * @param objectStore the object-store operations; must not be {@code null}
     * @param properties the bound AWS resource names; must not be {@code null}
     */
    public BatchStagingArea(final S3Operations objectStore, final AwsProperties properties) {
        this.objectStore = Objects.requireNonNull(objectStore, "objectStore must not be null");
        final AwsProperties.S3 s3 = Objects.requireNonNull(
                Objects.requireNonNull(properties, "properties must not be null").s3(),
                "properties.s3 must not be null");
        this.bucket = requireObjectKey(s3.batchStagingBucket(), "batch staging bucket");
    }

    /**
     * Returns the pre-provisioned staging bucket.
     *
     * @return the configured bucket name
     */
    public String bucket() {
        return this.bucket;
    }

    /**
     * Reports whether the staging bucket currently holds an object.
     *
     * @param objectKey the object key to test
     * @return {@code true} when the object exists
     */
    public boolean holds(final String objectKey) {
        return this.objectStore.objectExists(this.bucket, requireObjectKey(objectKey, "object key"));
    }

    /**
     * Resolves one staged object as a Spring resource suitable for a fixed-width item reader.
     *
     * <p>The caller decides whether absence is an error or whether a local fallback applies by calling
     * {@link #holds(String)} first. No bucket or object is created as a side effect of resolution.
     *
     * @param objectKey the object key to download
     * @return the staged object resource
     */
    public Resource stagedInput(final String objectKey) {
        return this.objectStore.download(this.bucket, requireObjectKey(objectKey, "object key"));
    }

    /**
     * Publishes a completed local writer buffer under its file name.
     *
     * @param source the completed local file
     * @throws UncheckedIOException if the local file cannot be opened
     */
    public void publish(final Path source) {
        final Path validated = Objects.requireNonNull(source, "source must not be null");
        final Path fileName = Objects.requireNonNull(validated.getFileName(),
                "source must name a file");
        publish(fileName.toString(), validated);
    }

    /**
     * Publishes a completed local writer buffer under an explicit object key.
     *
     * @param objectKey the destination object key
     * @param source the completed local file
     * @throws UncheckedIOException if the local file cannot be opened
     */
    public void publish(final String objectKey, final Path source) {
        final String validatedKey = requireObjectKey(objectKey, "object key");
        final Path validatedSource = Objects.requireNonNull(source, "source must not be null");
        try (InputStream content = Files.newInputStream(validatedSource)) {
            this.objectStore.upload(this.bucket, validatedKey, content);
        } catch (IOException failure) {
            throw new UncheckedIOException("staged batch file could not be read for publication: "
                    + validatedSource, failure);
        }
    }

    /**
     * Publishes an in-memory fixed-width generation.
     *
     * @param objectKey the destination object key
     * @param content the complete object content
     */
    public void publish(final String objectKey, final byte[] content) {
        final String validatedKey = requireObjectKey(objectKey, "object key");
        final byte[] validatedContent = Objects.requireNonNull(content, "content must not be null");
        this.objectStore.upload(this.bucket, validatedKey,
                new ByteArrayInputStream(validatedContent));
    }

    /**
     * Wraps a local file writer so publication occurs only after the delegate has closed successfully.
     *
     * @param <T> the item type written
     * @param delegate the local writer
     * @param source the local file the delegate writes
     * @return a writer preserving the delegate lifecycle and publishing after close
     */
    public <T> ItemStreamWriter<T> publishingWriter(final ItemStreamWriter<T> delegate,
            final Path source) {
        return new PublishingItemStreamWriter<>(this,
                Objects.requireNonNull(delegate, "delegate must not be null"),
                Objects.requireNonNull(source, "source must not be null"));
    }

    /**
     * Validates a configured bucket or object name.
     *
     * @param value the candidate value
     * @param role the value's role in an error message
     * @return the unchanged, non-blank value
     */
    private static String requireObjectKey(final String value, final String role) {
        Objects.requireNonNull(value, role + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(role + " must not be blank");
        }
        return value;
    }

    /**
     * Delegates a Spring Batch writer lifecycle and publishes its local file after a successful close.
     *
     * @param <T> the item type written
     */
    private static final class PublishingItemStreamWriter<T> implements ItemStreamWriter<T> {

        /** Staging boundary that publishes the completed file. */
        private final BatchStagingArea stagingArea;

        /** Local writer whose behavior is preserved. */
        private final ItemStreamWriter<T> delegate;

        /** File written by the delegate. */
        private final Path source;

        /**
         * Creates the publishing writer.
         *
         * @param stagingArea staging boundary
         * @param delegate local writer
         * @param source local output file
         */
        PublishingItemStreamWriter(final BatchStagingArea stagingArea,
                final ItemStreamWriter<T> delegate, final Path source) {
            this.stagingArea = stagingArea;
            this.delegate = delegate;
            this.source = source;
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
            this.stagingArea.publish(this.source);
        }
    }
}