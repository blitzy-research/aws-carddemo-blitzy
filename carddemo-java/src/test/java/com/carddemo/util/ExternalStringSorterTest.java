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

    /*
     * ==========================================================================================
     * EQUAL-KEY STABILITY.
     *
     * The combine-transactions job declares a byte-level contract on records that share a key: they
     * keep the order they were added in, so an identical pair of inputs produces an identical output
     * stream on every run. That contract rests on two mechanisms inside this class and on nothing
     * else - a stable in-memory sort per run, and the run index as the merge's tie-break - and the
     * second only ever operates when there are several runs to merge. It follows that a test with a
     * handful of records exercises the first mechanism and NOT the second: removing or reversing the
     * run-index tie-break leaves such a test green while making the delivered output order depend on
     * the heap's internal arrangement.
     *
     * The three cases below therefore cross the two thresholds that bring the merge into play. Each
     * makes every key equal, so the comparator can decide nothing at all and the emitted order is
     * entirely the tie-break's work; each record carries its own ordinal as a payload, so the emitted
     * order is readable; and the assertion is exact sequence equality rather than a sortedness check,
     * because a sortedness check cannot see a permutation of equal keys.
     * ==========================================================================================
     */

    /** Key every stability record shares, so the comparator can distinguish none of them. */
    private static final String SHARED_KEY = "0000000000000007";

    /** Width the ordinal payload is rendered at, wide enough for every count used here. */
    private static final String PAYLOAD_FORMAT = "%06d";

    @Test
    void equalKeysKeepTheirAddedOrderAcrossASpilledRunBoundary() {
        // One more record than the production run size, so the sorter spills a full run and then
        // merges it with a short second run. This is the smallest case in which the tie-break decides
        // anything: within one run the stable sort is enough, across two runs it is not.
        final int recordCount = ExternalStringSorter.DEFAULT_RECORDS_PER_RUN
                + (ExternalStringSorter.DEFAULT_RECORDS_PER_RUN / 2);

        assertThat(emitInAddedOrder(recordCount, ExternalStringSorter.DEFAULT_RECORDS_PER_RUN))
                .as("%d records at the production run size of %d spill into more than one run, and the "
                        + "emitted order must still be the added order", recordCount,
                        ExternalStringSorter.DEFAULT_RECORDS_PER_RUN)
                .containsExactlyElementsOf(addedOrder(recordCount));
    }

    @Test
    void equalKeysKeepTheirAddedOrderAtTheFanInBoundary() {
        // Exactly the fan-in, then one more than the fan-in. The first is merged in a single pass; the
        // second cannot be, so it is merged one level down and then merged again - and the tie-break
        // has to hold through BOTH levels, because level one's output feeds level two as a run whose
        // index says nothing about where its records came from unless the order inside it is right.
        final int atTheBound = ExternalStringSorter.MAX_MERGE_FAN_IN;
        final int pastTheBound = ExternalStringSorter.MAX_MERGE_FAN_IN + 1;

        assertThat(emitInAddedOrder(atTheBound, 1))
                .as("%d runs is exactly the fan-in, so one merge pass emits them", atTheBound)
                .containsExactlyElementsOf(addedOrder(atTheBound));
        assertThat(emitInAddedOrder(pastTheBound, 1))
                .as("%d runs is one more than the fan-in, so a second merge level is introduced and the "
                        + "order must survive being merged, written back out and merged again",
                        pastTheBound)
                .containsExactlyElementsOf(addedOrder(pastTheBound));
    }

    @Test
    void equalKeysKeepTheirAddedOrderThroughEveryMergeLevel() {
        // One record per run and enough records to force three merge levels: 1,100 runs reduce to 35,
        // then to 2, then to the final emission. Nothing here can be decided by the comparator, so an
        // exact match against the added order is a statement about the merge and nothing else.
        final int recordCount = 1_100;
        final int expectedFirstLevelRuns =
                (recordCount + ExternalStringSorter.MAX_MERGE_FAN_IN - 1)
                        / ExternalStringSorter.MAX_MERGE_FAN_IN;

        assertThat(expectedFirstLevelRuns)
                .as("the arithmetic this case relies on: %d runs of one record reduce to %d, which is "
                        + "still more than the fan-in of %d, so a third level is genuinely reached",
                        recordCount, expectedFirstLevelRuns, ExternalStringSorter.MAX_MERGE_FAN_IN)
                .isGreaterThan(ExternalStringSorter.MAX_MERGE_FAN_IN);
        assertThat(emitInAddedOrder(recordCount, 1))
                .containsExactlyElementsOf(addedOrder(recordCount));
    }

    /**
     * Adds the given number of equal-key records in ordinal order and returns what was emitted.
     *
     * @param  recordCount   how many records to add
     * @param  recordsPerRun run size, which decides how many runs are spilled and merged
     * @return the emitted records, in emission order
     */
    private static List<String> emitInAddedOrder(final int recordCount, final int recordsPerRun) {
        final List<String> output = new ArrayList<>(recordCount);
        try (ExternalStringSorter sorter = new ExternalStringSorter(
                Comparator.comparing(ExternalStringSorterTest::keyOf), recordsPerRun)) {
            for (final String record : addedOrder(recordCount)) {
                sorter.add(record);
            }
            assertThat(sorter.writeTo(output::add))
                    .as("every record added must be emitted: equal keys are never deduplicated, "
                            + "summed or dropped")
                    .isEqualTo(recordCount);
        }
        return output;
    }

    /**
     * Builds the records in the order they are added: one shared key, one ascending payload.
     *
     * @param  recordCount how many records to build
     * @return the records, in added order
     */
    private static List<String> addedOrder(final int recordCount) {
        final List<String> records = new ArrayList<>(recordCount);
        for (int ordinal = 0; ordinal < recordCount; ordinal++) {
            records.add(SHARED_KEY + String.format(java.util.Locale.ROOT, PAYLOAD_FORMAT, ordinal));
        }
        return records;
    }

    /**
     * Reads the key a stability record carries, which is the same key for every one of them.
     *
     * <p>The comparator is built on this deliberately: it sees only the shared prefix, so it declares
     * every pair equal and contributes nothing to the emitted order. Comparing the whole record would
     * let the payload order the output and the test would prove nothing about the tie-break.
     *
     * @param  record one stability record
     * @return the shared key portion
     */
    private static String keyOf(final String record) {
        return record.substring(0, SHARED_KEY.length());
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