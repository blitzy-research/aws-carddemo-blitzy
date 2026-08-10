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
package com.carddemo.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.Consumer;

/**
 * Bounded external sorter for fixed-width record images.
 *
 * <p>At most one input chunk and {@value #MAX_MERGE_FAN_IN} merge heads are resident at once. Sorted
 * runs are length-prefixed binary files in a private temporary directory, so trailing spaces and the
 * absence or presence of record separators cannot alter a record image. Large run sets are merged in
 * bounded fan-in passes before the final consumer is invoked.
 *
 * <p>The sorter owns no output destination and emits no path or record content. Callers supply the
 * final consumer, which lets line-terminated and fixed-unblocked datasets share the same ordering
 * implementation without changing either external contract.
 *
 * <h2>Why the work area carries a search bit and its run files do not</h2>
 *
 * <p>Both the directory and the files inside it are private to the owner, but a directory needs one
 * more bit than a file does: on POSIX the execute bit on a directory is the <em>search</em> permission,
 * and without it the owner cannot resolve any name inside the directory at all. A work area created
 * {@code rw-------} therefore accepts no run file, and the first spill fails with an access denial
 * rather than with anything that names a permission.
 *
 * <p>That distinction is load-bearing here rather than academic, because the delivered runtime is a
 * container running as an unprivileged account on a read-only root filesystem with only a temporary
 * filesystem writable. A process running as {@code root} carries {@code CAP_DAC_OVERRIDE} and resolves
 * names inside a directory that grants no search permission anyway, so a test bed that runs as root
 * cannot observe the fault at all - which is exactly how it reached a delivered image. The directory
 * permissions are asserted directly by {@code ExternalStringSorterTest}, and a spill is executed as the
 * unprivileged container account by {@code ExternalSortNonRootRuntimeIT}, so neither half can regress
 * silently again.
 */
public final class ExternalStringSorter implements AutoCloseable {

    /** Default number of complete records retained for one in-memory sort run. */
    public static final int DEFAULT_RECORDS_PER_RUN = 512;

    /** Maximum run files opened by one merge operation. */
    public static final int MAX_MERGE_FAN_IN = 32;

    /** Name prefix of the per-execution work area the runs are spilled into. */
    private static final String WORK_AREA_PREFIX = "carddemo-sort-";

    private final Comparator<String> comparator;
    private final int recordsPerRun;
    private final Path workDirectory;
    private final List<String> pendingRecords;

    private int levelZeroRunCount;
    private boolean emitted;
    private boolean closed;

    /**
     * Creates one per-execution sorter.
     *
     * @param comparator record ordering
     * @param recordsPerRun maximum records held before a run is spilled
     */
    public ExternalStringSorter(final Comparator<String> comparator, final int recordsPerRun) {
        this.comparator = Objects.requireNonNull(comparator, "comparator must not be null");
        if (recordsPerRun < 1) {
            throw new IllegalArgumentException(
                    "recordsPerRun must be at least one: " + recordsPerRun);
        }
        this.recordsPerRun = recordsPerRun;
        this.pendingRecords = new ArrayList<>(recordsPerRun);
        try {
            // Owner-only from creation rather than tightened afterwards: a run file holds complete
            // record images, and a descriptor opened in the window between creation and a later chmod
            // keeps its access after the mode changes.
            // See docs/decision-log.md entry DL-178.
            this.workDirectory = SecureStagedFiles.newTemporaryDirectory(WORK_AREA_PREFIX);
        } catch (final IOException failure) {
            throw new UncheckedIOException("unable to allocate the bounded external-sort work area",
                    failure);
        }
    }

    /**
     * Reports the private work area this sorter allocated.
     *
     * <p>Package-visible and free of side effects, so the permission contract documented above can be
     * asserted directly rather than by scanning the temporary directory for a name that looks like one
     * of ours. Nothing outside this package needs the path, and no caller is given a way to write into
     * it: the sorter owns every file it creates there and removes the whole area when it closes.
     *
     * @return the work-area path, never {@code null}
     */
    Path workArea() {
        return this.workDirectory;
    }

    /**
     * Adds one complete record image.
     *
     * @param record record image; must not be {@code null}
     */
    public void add(final String record) {
        requireOpenForInput();
        this.pendingRecords.add(Objects.requireNonNull(record, "record must not be null"));
        if (this.pendingRecords.size() == this.recordsPerRun) {
            spillPendingRun();
        }
    }

    /**
     * Emits every record in comparator order and releases all work files.
     *
     * @param consumer final ordered-record sink
     * @return number of records emitted
     */
    public long writeTo(final Consumer<String> consumer) {
        requireOpenForInput();
        Objects.requireNonNull(consumer, "consumer must not be null");
        spillPendingRun();
        this.emitted = true;

        long recordsWritten = 0L;
        try {
            int currentLevel = 0;
            int runCount = this.levelZeroRunCount;
            while (runCount > MAX_MERGE_FAN_IN) {
                final int nextRunCount = mergeOneLevel(currentLevel, runCount);
                currentLevel++;
                runCount = nextRunCount;
            }
            if (runCount > 0) {
                recordsWritten = mergeRuns(currentLevel, 0, runCount, consumer);
                deleteRuns(currentLevel, 0, runCount);
            }
            return recordsWritten;
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.pendingRecords.clear();
        try {
            if (Files.exists(this.workDirectory)) {
                try (var entries = Files.walk(this.workDirectory)) {
                    entries.sorted(Comparator.reverseOrder()).forEach(ExternalStringSorter::delete);
                }
            }
        } catch (final IOException failure) {
            throw new UncheckedIOException("unable to release the external-sort work area", failure);
        }
    }

    private void requireOpenForInput() {
        if (this.closed) {
            throw new IllegalStateException("the external sorter is closed");
        }
        if (this.emitted) {
            throw new IllegalStateException("the external sorter has already emitted its records");
        }
    }

    private void spillPendingRun() {
        if (this.pendingRecords.isEmpty()) {
            return;
        }
        this.pendingRecords.sort(this.comparator);
        final Path run = runPath(0, this.levelZeroRunCount);
        writeRecords(run, this.pendingRecords);
        this.levelZeroRunCount++;
        this.pendingRecords.clear();
    }

    private int mergeOneLevel(final int currentLevel, final int runCount) {
        int nextRun = 0;
        for (int first = 0; first < runCount; first += MAX_MERGE_FAN_IN) {
            final int count = Math.min(MAX_MERGE_FAN_IN, runCount - first);
            final Path output = runPath(currentLevel + 1, nextRun);
            try (DataOutputStream writer = openWriter(output)) {
                mergeRuns(currentLevel, first, count, record -> writeRecord(writer, record));
            } catch (final IOException failure) {
                throw new UncheckedIOException("unable to merge an external-sort run", failure);
            }
            deleteRuns(currentLevel, first, count);
            nextRun++;
        }
        return nextRun;
    }

    private long mergeRuns(final int level, final int firstRun, final int runCount,
            final Consumer<String> consumer) {

        final List<RunReader> readers = new ArrayList<>(runCount);
        final PriorityQueue<RunHead> heads = new PriorityQueue<>(
                Comparator.comparing(RunHead::record, this.comparator)
                        .thenComparingInt(RunHead::readerIndex));
        Throwable primary = null;
        try {
            for (int offset = 0; offset < runCount; offset++) {
                final RunReader reader = new RunReader(runPath(level, firstRun + offset));
                readers.add(reader);
                reader.readNext().ifPresent(record ->
                        heads.add(new RunHead(record, readers.size() - 1)));
            }

            long emittedCount = 0L;
            while (!heads.isEmpty()) {
                final RunHead head = heads.remove();
                consumer.accept(head.record());
                emittedCount++;
                readers.get(head.readerIndex()).readNext().ifPresent(record ->
                        heads.add(new RunHead(record, head.readerIndex())));
            }
            return emittedCount;
        } catch (final RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            closeReaders(readers, primary);
        }
    }

    private void deleteRuns(final int level, final int firstRun, final int runCount) {
        for (int offset = 0; offset < runCount; offset++) {
            delete(runPath(level, firstRun + offset));
        }
    }

    private Path runPath(final int level, final int index) {
        return this.workDirectory.resolve("run-" + level + "-" + index + ".bin");
    }

    private static void writeRecords(final Path target, final List<String> records) {
        try (DataOutputStream writer = openWriter(target)) {
            for (final String record : records) {
                writeRecord(writer, record);
            }
        } catch (final IOException failure) {
            throw new UncheckedIOException("unable to spill an external-sort run", failure);
        }
    }

    private static DataOutputStream openWriter(final Path target) throws IOException {
        return new DataOutputStream(
                new BufferedOutputStream(SecureStagedFiles.newOutputStream(target)));
    }

    private static void writeRecord(final DataOutputStream writer, final String record) {
        final byte[] encoded = record.getBytes(StandardCharsets.US_ASCII);
        try {
            writer.writeInt(encoded.length);
            writer.write(encoded);
        } catch (final IOException failure) {
            throw new UncheckedIOException("unable to write an external-sort record", failure);
        }
    }

    private static void closeReaders(final List<RunReader> readers, final Throwable primary) {
        RuntimeException closeFailure = null;
        for (final RunReader reader : readers) {
            try {
                reader.close();
            } catch (final RuntimeException failure) {
                if (primary != null) {
                    primary.addSuppressed(failure);
                } else if (closeFailure == null) {
                    closeFailure = failure;
                } else {
                    closeFailure.addSuppressed(failure);
                }
            }
        }
        if (primary == null && closeFailure != null) {
            throw closeFailure;
        }
    }

    private static void delete(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (final IOException failure) {
            throw new UncheckedIOException("unable to delete an external-sort work file", failure);
        }
    }

    private record RunHead(String record, int readerIndex) {
    }

    private static final class RunReader implements AutoCloseable {
        private final DataInputStream input;

        RunReader(final Path source) {
            try {
                this.input = new DataInputStream(
                        new BufferedInputStream(Files.newInputStream(source)));
            } catch (final IOException failure) {
                throw new UncheckedIOException("unable to open an external-sort run", failure);
            }
        }

        java.util.Optional<String> readNext() {
            try {
                final int length = this.input.readInt();
                if (length < 0) {
                    throw new IllegalStateException(
                            "an external-sort run carried a negative record length");
                }
                return java.util.Optional.of(
                        new String(this.input.readNBytes(length), StandardCharsets.US_ASCII));
            } catch (final EOFException endOfRun) {
                return java.util.Optional.empty();
            } catch (final IOException failure) {
                throw new UncheckedIOException("unable to read an external-sort run", failure);
            }
        }

        @Override
        public void close() {
            try {
                this.input.close();
            } catch (final IOException failure) {
                throw new UncheckedIOException("unable to close an external-sort run", failure);
            }
        }
    }
}
