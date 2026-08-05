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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.batch.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.WritableResource;

import com.carddemo.batch.BatchStagingStore;

/**
 * Explicit filesystem adapter for tests that exercise batch file contracts without S3.
 *
 * <p>This type is test-only and is never selected by a runtime profile. It uses the production object-key
 * composition under a caller-owned temporary root, so a test proves the same namespace while remaining
 * independent of LocalStack when the object-store boundary is not itself under test.
 */
public final class LocalFileSystemBatchStagingStore implements BatchStagingStore {

    /** Normalized root that contains every resolved test resource. */
    private final Path root;

    /**
     * Creates an adapter rooted at one test directory.
     *
     * @param root the caller-owned staging root; must not be {@code null}
     */
    public LocalFileSystemBatchStagingStore(final Path root) {
        this.root = Objects.requireNonNull(root, "root must not be null")
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (final IOException failure) {
            throw new UncheckedIOException("the test staging root could not be created", failure);
        }
    }

    @Override
    public WritableResource resource(final String jobName, final String logicalName) {
        final Path target = path(jobName, logicalName);
        try {
            Files.createDirectories(Objects.requireNonNull(target.getParent(),
                    "a staged test resource must have a containing directory"));
        } catch (final IOException failure) {
            throw new UncheckedIOException("the test staging resource container could not be created",
                    failure);
        }
        return new FileSystemResource(target);
    }

    @Override
    public boolean delete(final String jobName, final String logicalName) {
        try {
            return Files.deleteIfExists(path(jobName, logicalName));
        } catch (final IOException failure) {
            throw new UncheckedIOException("the staged test resource could not be deleted", failure);
        }
    }

    /**
     * Returns the contained filesystem path for assertions that must inspect emitted bytes.
     *
     * @param jobName     the stable job name
     * @param logicalName the logical dataset or generation name
     * @return a normalized path below this adapter's root
     */
    public Path path(final String jobName, final String logicalName) {
        final Path target = this.root.resolve(BatchStagingStore.objectKey(jobName, logicalName))
                .normalize();
        if (!target.startsWith(this.root)) {
            throw new IllegalArgumentException("the staged test resource escaped its configured root");
        }
        return target;
    }
}