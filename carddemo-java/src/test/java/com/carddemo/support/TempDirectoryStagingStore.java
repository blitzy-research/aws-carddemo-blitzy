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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.carddemo.service.BatchStagingService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import org.springframework.core.io.FileSystemResource;

/**
 * A staging store for a unit test, backed by one temporary directory instead of an object store.
 *
 * <h2>What this is for, and what it deliberately is not</h2>
 *
 * <p>A batch job configuration reads and writes every staged dataset through
 * {@link BatchStagingService}. Most of what a unit test of such a configuration asserts is about the
 * <em>bytes</em>: that a record is exactly its declared width, that a generation's length is a record
 * count multiplied by that width, that a separator is or is not present, that a reprojection places a
 * field at the offset the sort specification declares. Those assertions need somewhere real to write and
 * read back, and they need it to be cheap enough to use in every test of every layout.
 *
 * <p>This provides exactly that: a store whose keys become file names under one temporary directory, so a
 * test writes through the same interface production writes through and then reads the result back with
 * ordinary file access. It is <strong>not</strong> a claim that a real object store behaves this way. That
 * claim belongs to {@code BatchStagingServiceTest}, which asserts what the service asks the store to do,
 * and to the container-backed integration tier, which asserts that a real store accepts it.
 *
 * <h2>The name rule is still the real one</h2>
 *
 * <p>Every method here calls {@link BatchStagingService#requireStagingKey(String)} before it touches the
 * directory, so a test cannot stage under a name production would refuse. That matters: the rule is what
 * confines a job to its staging area, and a stand-in that quietly accepted an absolute name or a parent
 * segment would let a configuration under test pass a check it would fail in production - which is the
 * defect this whole arrangement replaced.
 *
 * <p>Because the rule admits {@code /} as a segment separator, a key with separators becomes nested
 * directories here. Those are created on demand for a write, exactly as an object store creates nothing
 * and simply accepts the key.
 *
 * <h2>Why stubs rather than a subclass</h2>
 *
 * <p>{@link BatchStagingService} is final, because it is the one place a caller-supplied name becomes an
 * addressed object and a subclass could weaken that. So the stand-in is a stubbed instance rather than a
 * subclass. Every stub is lenient: a test of one job exercises the two or three operations that job
 * performs and not the other six, and a strict stub would fail such a test for the operations it correctly
 * never called.
 */
public final class TempDirectoryStagingStore {

    /** The bucket name the stand-in reports, matching the name the test profile configures. */
    public static final String BUCKET = "carddemo-batch-staging";

    /** Utility holder; never instantiated. */
    private TempDirectoryStagingStore() {
        throw new AssertionError("TempDirectoryStagingStore is a factory and is never instantiated");
    }

    /**
     * Creates a staging store whose objects are files under one directory.
     *
     * @param  directory the directory every staged key resolves within; must not be {@code null}
     * @return a staging store backed by that directory
     */
    public static BatchStagingService backedBy(final Path directory) {
        Objects.requireNonNull(directory, "directory must not be null");
        final BatchStagingService store = mock(BatchStagingService.class);

        lenient().when(store.bucket()).thenReturn(BUCKET);
        lenient().when(store.describe(anyString())).thenAnswer(call ->
                "s3//" + BUCKET + "/" + BatchStagingService.requireStagingKey(key(call)));

        lenient().when(store.readable(anyString())).thenAnswer(call ->
                new FileSystemResource(resolve(directory, key(call))));
        lenient().when(store.writable(anyString())).thenAnswer(call ->
                new FileSystemResource(created(resolve(directory, key(call)))));

        lenient().when(store.inputStream(anyString())).thenAnswer(call ->
                Files.newInputStream(resolve(directory, key(call))));
        lenient().when(store.outputStream(anyString())).thenAnswer(call ->
                Files.newOutputStream(created(resolve(directory, key(call))),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE));

        lenient().doAnswer(call -> {
            Files.write(created(resolve(directory, key(call))), call.getArgument(1, byte[].class),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            return null;
        }).when(store).write(anyString(), any(byte[].class));

        lenient().when(store.deleteIfPresent(anyString())).thenAnswer(call ->
                Files.deleteIfExists(resolve(directory, key(call))));
        lenient().when(store.exists(anyString())).thenAnswer(call ->
                Files.exists(resolve(directory, key(call))));
        lenient().when(store.contentLength(anyString())).thenAnswer(call ->
                Files.size(resolve(directory, key(call))));

        return store;
    }

    /**
     * Resolves the file one staged key becomes, refusing any key production would refuse.
     *
     * @param  directory the backing directory
     * @param  logicalName the staged key
     * @return the file the key resolves to
     */
    private static Path resolve(final Path directory, final String logicalName) {
        return directory.resolve(BatchStagingService.requireStagingKey(logicalName));
    }

    /**
     * Creates the containing directory of a file about to be written, and answers the file.
     *
     * @param  target the file about to be written
     * @return {@code target}, unchanged
     */
    private static Path created(final Path target) {
        final Path container = target.getParent();
        if (container == null) {
            return target;
        }
        try {
            Files.createDirectories(container);
        } catch (final IOException failure) {
            throw new UncheckedIOException(
                    "the backing directory of the staged object could not be created", failure);
        }
        return target;
    }

    /**
     * Reads the staged key out of a stubbed invocation.
     *
     * @param  call the invocation
     * @return the first argument, which every stubbed operation declares as the staged key
     */
    private static String key(final org.mockito.invocation.InvocationOnMock call) {
        return call.getArgument(0, String.class);
    }
}
