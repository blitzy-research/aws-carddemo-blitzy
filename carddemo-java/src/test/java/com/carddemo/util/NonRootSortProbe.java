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

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Executes one bounded external sort and reports the outcome on standard output.
 *
 * <p>This is the payload of {@link ExternalSortNonRootRuntimeIT}: it is launched <em>inside</em> a
 * container that reproduces the delivered runtime policy - an unprivileged account, an immutable root
 * filesystem and a temporary filesystem as the only writable location - so that the sorter's work-area
 * permissions are exercised by a process that does not carry {@code CAP_DAC_OVERRIDE}. A process running
 * as {@code root} resolves names inside a directory that grants no search permission regardless of the
 * permission bits, which is why this cannot be asserted from the ordinary test process.
 *
 * <p>It writes a machine-readable line rather than throwing: the integration test asserts on the exact
 * tokens, and a failure that reaches the caller as text keeps the container's own diagnostic - the
 * effective user, the work-area permissions and the stack trace - in the log the assertion prints.
 *
 * <p>No production code path invokes this class. Surefire selects {@code **}{@code /*Test.java} and
 * Failsafe selects {@code **}{@code /*IT.java}, so a probe named like neither is never auto-executed.
 */
public final class NonRootSortProbe {

    /** Token printed once the sort has completed and every assertion inside the container held. */
    public static final String SUCCESS_TOKEN = "NON-ROOT-SORT-OK";

    /** Token printed when the sort, or one of the checks around it, failed. */
    public static final String FAILURE_TOKEN = "NON-ROOT-SORT-FAILED";

    /** Records fed through the sorter; deliberately more than one run's worth. */
    private static final int RECORD_COUNT = 5;

    /** Records held in memory before a run is spilled, forced low so every record spills. */
    private static final int RECORDS_PER_RUN = 1;

    /** Width of each fixed-width record image the probe sorts. */
    private static final int RECORD_WIDTH = 5;

    /** Not instantiable. */
    private NonRootSortProbe() {
    }

    /**
     * Sorts a small fixed-width set through spilled runs and prints the outcome.
     *
     * @param arguments ignored; the probe takes no parameters so the container command stays fixed
     */
    public static void main(final String[] arguments) {
        final PrintStream out = System.out;
        out.printf(Locale.ROOT, "tmpdir=%s%n", System.getProperty("java.io.tmpdir", "unknown"));
        try {
            final List<String> sorted = sortDescendingImages(out);
            if (sorted.size() != RECORD_COUNT) {
                throw new IllegalStateException(
                        "expected " + RECORD_COUNT + " records but emitted " + sorted.size());
            }
            if (!sorted.equals(expectedDescendingImages())) {
                throw new IllegalStateException("emitted order was " + sorted);
            }
            out.printf(Locale.ROOT, "%s records=%d%n", SUCCESS_TOKEN, sorted.size());
        } catch (final RuntimeException failure) {
            out.println(FAILURE_TOKEN);
            failure.printStackTrace(out);
        }
    }

    /**
     * Runs the sorter, reporting the work-area permissions the unprivileged account actually observes.
     *
     * @param out stream the observed permissions are reported on
     * @return the emitted records, in the order the sorter produced them
     */
    private static List<String> sortDescendingImages(final PrintStream out) {
        final List<String> emitted = new ArrayList<>();
        try (ExternalStringSorter sorter =
                new ExternalStringSorter(Comparator.reverseOrder(), RECORDS_PER_RUN)) {
            reportWorkArea(out, sorter.workArea());
            for (int value = 0; value < RECORD_COUNT; value++) {
                sorter.add(paddedImage(value));
            }
            sorter.writeTo(emitted::add);
        }
        return emitted;
    }

    /**
     * Prints the work-area permissions and owner as the container sees them.
     *
     * <p>The owner is reported because the account this runs as has no entry in the container's password
     * database, so the owner of a file it just created is the only reliable statement of which account
     * the run belongs to.
     *
     * @param out      stream to report on
     * @param workArea the sorter's private work area
     */
    private static void reportWorkArea(final PrintStream out, final Path workArea) {
        try {
            out.printf(Locale.ROOT, "work-area-permissions=%s work-area-owner=%s%n",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(workArea)),
                    Files.getOwner(workArea).getName());
        } catch (final IOException | UnsupportedOperationException unavailable) {
            out.printf(Locale.ROOT, "work-area-permissions=unavailable (%s)%n",
                    unavailable.getClass().getSimpleName());
        }
    }

    /**
     * Renders one fixed-width record image.
     *
     * @param value ordinal to render
     * @return the record image, exactly {@value #RECORD_WIDTH} characters wide
     */
    private static String paddedImage(final int value) {
        return String.format(Locale.ROOT, "%0" + (RECORD_WIDTH - 1) + "d ", value);
    }

    /**
     * Builds the descending order the sorter must produce.
     *
     * @return the expected record images, highest first
     */
    private static List<String> expectedDescendingImages() {
        final List<String> expected = new ArrayList<>(RECORD_COUNT);
        for (int value = RECORD_COUNT - 1; value >= 0; value--) {
            expected.add(paddedImage(value));
        }
        return expected;
    }
}
