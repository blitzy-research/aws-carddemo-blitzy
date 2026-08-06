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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

final class ExternalStringSorterTest {

    /** Prefix the sorter gives every work area it allocates. */
    private static final String WORK_AREA_PREFIX = "carddemo-sort-";

    /** Suffix every spilled run file carries. */
    private static final String RUN_FILE_SUFFIX = ".bin";

    /**
     * Reports whether this filesystem carries POSIX permissions.
     *
     * <p>Referenced by {@link EnabledIf} rather than asserted, because the sorter deliberately tolerates
     * a filesystem that has no permission model and there is nothing to assert about one.
     *
     * @return {@code true} when permissions can be read back
     */
    static boolean posixPermissionsAreSupported() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    @Test
    void sortsAcrossMultipleRunsWithoutChangingFixedWidthImages() {
        final List<String> output = new ArrayList<>();
        try (ExternalStringSorter sorter =
                new ExternalStringSorter(Comparator.naturalOrder(), 2)) {
            sorter.add("03   ");
            sorter.add("01   ");
            sorter.add("04   ");
            sorter.add("02   ");

            assertThat(sorter.writeTo(output::add)).isEqualTo(4L);
        }

        assertThat(output).containsExactly("01   ", "02   ", "03   ", "04   ");
        assertThat(output).allSatisfy(record -> assertThat(record).hasSize(5));
    }

    @Test
    void performsBoundedFanInPasses() {
        final int recordCount = ExternalStringSorter.MAX_MERGE_FAN_IN + 3;
        final List<String> output = new ArrayList<>();
        try (ExternalStringSorter sorter =
                new ExternalStringSorter(Comparator.reverseOrder(), 1)) {
            for (int value = 0; value < recordCount; value++) {
                sorter.add(String.format(java.util.Locale.ROOT, "%03d", value));
            }
            assertThat(sorter.writeTo(output::add)).isEqualTo(recordCount);
        }

        assertThat(output).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(output).hasSize(recordCount);
    }

    @Test
    @EnabledIf("posixPermissionsAreSupported")
    void theWorkAreaIsPrivateAndSearchableSoAnUnprivilegedOwnerCanResolveItsRunFiles()
            throws IOException {
        // Written after a delivered image could not run four batch jobs: the work area was created
        // rw------- and the owning container account, which is not root and therefore carries no
        // CAP_DAC_OVERRIDE, could not resolve any name inside it. The search bit is the fix, and this
        // asserts it on the directory itself so a root-only test bed cannot hide its removal again.
        final Path workArea;
        try (ExternalStringSorter sorter =
                new ExternalStringSorter(Comparator.naturalOrder(), 1)) {
            workArea = sorter.workArea();

            assertThat(workArea).isDirectory();
            assertThat(workArea.getFileName()).asString().startsWith(WORK_AREA_PREFIX);
            assertThat(Files.getPosixFilePermissions(workArea))
                    .containsExactlyInAnyOrder(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE);
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(workArea)))
                    .isEqualTo("rwx------");
        }

        assertThat(workArea).doesNotExist();
    }

    @Test
    @EnabledIf("posixPermissionsAreSupported")
    void everySpilledRunFileStaysOwnerReadWriteWithoutAnExecuteBit() throws IOException {
        try (ExternalStringSorter sorter =
                new ExternalStringSorter(Comparator.naturalOrder(), 1)) {
            sorter.add("02   ");
            sorter.add("01   ");

            final List<Path> runFiles = runFilesIn(sorter.workArea());
            assertThat(runFiles)
                    .as("one record per run means one spilled run file per record")
                    .hasSize(2);
            assertThat(runFiles).allSatisfy(runFile -> {
                assertThat(Files.getPosixFilePermissions(runFile))
                        .containsExactlyInAnyOrder(
                                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(runFile)))
                        .isEqualTo("rw-------");
            });

            final List<String> output = new ArrayList<>();
            assertThat(sorter.writeTo(output::add)).isEqualTo(2L);
            assertThat(output).containsExactly("01   ", "02   ");
        }
    }

    /**
     * Lists the run files the sorter has spilled into its work area.
     *
     * @param workArea the sorter's private work area
     * @return spilled run files in directory order
     * @throws IOException if the work area cannot be listed
     */
    private static List<Path> runFilesIn(final Path workArea) throws IOException {
        try (Stream<Path> entries = Files.list(workArea)) {
            return entries
                    .filter(entry -> entry.getFileName().toString().endsWith(RUN_FILE_SUFFIX))
                    .toList();
        }
    }

    @Test
    void rejectsInvalidLifecycleUse() {
        assertThatThrownBy(() -> new ExternalStringSorter(Comparator.naturalOrder(), 0))
                .isInstanceOf(IllegalArgumentException.class);

        final ExternalStringSorter sorter =
                new ExternalStringSorter(Comparator.naturalOrder(), 1);
        sorter.close();
        assertThatThrownBy(() -> sorter.add("record"))
                .isInstanceOf(IllegalStateException.class);
    }
}