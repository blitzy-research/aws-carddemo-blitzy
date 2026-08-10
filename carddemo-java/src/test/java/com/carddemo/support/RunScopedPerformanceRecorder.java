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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Measures one batch run end to end and reports the three figures Gate 3 names, each scoped to that run.
 *
 * <h2>Why this exists rather than a dashboard query</h2>
 *
 * <p>Gate 3 asks for elapsed time, peak memory and records per second. The Grafana dashboard shows all
 * three in a form suited to <em>watching</em> a system, and none of them in a form that can be quoted as a
 * run's figure:
 *
 * <ul>
 *   <li><strong>Records per second</strong> on the dashboard is {@code rate(counter[window])} - a rolling
 *       rate that divides by the <em>rate window</em>, not by the run's own elapsed time. A job that
 *       processes three hundred records in four seconds inside a one-minute rate window reads as five
 *       records per second rather than seventy-five. The figure is not wrong as a rate; it is simply not
 *       the quotient Gate 3 asks for.</li>
 *   <li><strong>Peak memory</strong> on the dashboard is {@code max_over_time} over scraped samples, so it
 *       is the largest occupancy Prometheus happened to <em>observe</em>. A peak that rises and falls
 *       between two scrapes is invisible to it, and a batch run short enough to fit between two scrapes
 *       can be invisible entirely. The JVM, by contrast, tracks its own peak continuously, and this class
 *       reads that.</li>
 *   <li><strong>Elapsed time</strong> the dashboard reads from a genuine timer, so that one is sound; it
 *       is measured here too so all three figures come from one run rather than three windows.</li>
 * </ul>
 *
 * <h2>What it measures, and how</h2>
 *
 * <p>Peak heap is read from {@link MemoryPoolMXBean#getPeakUsage()} across every pool of type
 * {@link MemoryType#HEAP}, after {@link MemoryPoolMXBean#resetPeakUsage()} has cleared the accounting
 * immediately before the run. The reading is therefore the peak <em>of this run</em> rather than of the
 * whole test JVM, and it does not depend on any sampling interval. Elapsed time is wall clock across the
 * launch, taken with {@link System#nanoTime()}. Records are supplied by the caller from the run's own
 * evidence - a step execution context or an application counter delta - because only the caller knows
 * which records its job was counting.
 *
 * <h2>What it does NOT do</h2>
 *
 * <p><strong>It asserts no threshold, and it must never be given one.</strong> No numeric latency,
 * throughput, availability or capacity figure exists anywhere in the legacy estate, so there is nothing to
 * compare a measurement against; Gate 3 establishes the first Java baseline rather than testing one. A
 * caller may assert that a figure is well formed - that elapsed time is positive, that the record count is
 * the fixture's own volume - and must not assert that a figure is fast enough.
 *
 * <p>It also does not write into {@code docs/}. A recorded baseline is published by a person who ran it,
 * against a machine they can name; a test that edited the documentation tree would make the repository's
 * content depend on the hardware of whoever last ran the suite. The figures are written to
 * {@code target/gate-evidence/} instead, in the shape {@code docs/gate-evidence.md} carries, to be copied
 * across with the machine and the date named beside them.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
public final class RunScopedPerformanceRecorder {

    /** Directory the measured figures are written to; inside the build output, never inside docs. */
    public static final Path EVIDENCE_DIRECTORY = Path.of("target", "gate-evidence");

    /** Nanoseconds in one second, as a decimal so the quotient never becomes a floating-point value. */
    private static final BigDecimal NANOS_PER_SECOND = BigDecimal.valueOf(1_000_000_000L);

    /** Scale the derived rate is reported at; two places, the same scale the estate's money carries. */
    private static final int RATE_SCALE = 2;

    /** Precision for the intermediate division, wide enough that the scale below is what rounds. */
    private static final MathContext RATE_PRECISION = new MathContext(16, RoundingMode.HALF_UP);

    /** Every measurement taken through this recorder, in the order it was taken. */
    private final List<RunBaseline> baselines = new ArrayList<>();

    /** Creates an empty recorder. */
    public RunScopedPerformanceRecorder() {
        // Nothing to initialise; the heap pools are read from the platform on each measurement.
    }

    /**
     * Runs one measured batch launch and records its three figures.
     *
     * @param label how the run is named in the recorded evidence, normally the job name
     * @param fixtureNote the fixture volumes the run was measured over, which a figure is meaningless
     *                    without; must not be blank
     * @param recordsOf reads the run's own record count from whatever the launch returned
     * @param launch the launch to measure
     * @param <T> what the launch returns, normally a job execution
     * @return the launch's own return value, so a caller can go on asserting behaviour
     * @throws Exception whatever the launch throws, unchanged
     */
    public <T> T measure(final String label, final String fixtureNote,
            final RecordCount<T> recordsOf, final Callable<T> launch) throws Exception {
        Objects.requireNonNull(label, "label must not be null");
        final String fixtures = Objects.requireNonNull(fixtureNote, "fixtureNote must not be null");
        if (fixtures.isBlank()) {
            throw new IllegalArgumentException("a recorded figure must name the fixture volumes it was"
                    + " measured over");
        }
        Objects.requireNonNull(recordsOf, "recordsOf must not be null");
        Objects.requireNonNull(launch, "launch must not be null");

        // Clear the JVM's own peak accounting so the reading below belongs to this run and not to
        // whatever the suite did before it.
        resetHeapPeaks();
        final long startedAt = System.nanoTime();
        final T outcome = launch.call();
        final long elapsedNanos = System.nanoTime() - startedAt;
        final long peakHeapBytes = peakHeapBytes();

        final long records = recordsOf.of(outcome);
        final RunBaseline baseline =
                new RunBaseline(label, fixtures, records, elapsedNanos, peakHeapBytes);
        this.baselines.add(baseline);
        return outcome;
    }

    /**
     * Returns every measurement taken, in order.
     *
     * @return the recorded baselines
     */
    public List<RunBaseline> baselines() {
        return List.copyOf(this.baselines);
    }

    /**
     * Writes the recorded figures into the build output, in the shape the evidence page carries.
     *
     * @param fileName file name beneath {@link #EVIDENCE_DIRECTORY}; must not be blank
     * @return the file written
     */
    public Path publish(final String fileName) {
        Objects.requireNonNull(fileName, "fileName must not be null");
        if (fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must name a file");
        }
        final StringBuilder rendered = new StringBuilder(512);
        rendered.append("<!-- Measured by RunScopedPerformanceRecorder. Copy into docs/gate-evidence.md")
                .append(System.lineSeparator())
                .append("     together with the date and the machine the run was taken on. These are")
                .append(System.lineSeparator())
                .append("     measurements, not thresholds: nothing here may become an assertion. -->")
                .append(System.lineSeparator())
                .append(System.lineSeparator())
                // Which build and which run produced these figures. A Gate 3 row is a property of one
                // run on one machine, so a file of them that cannot be attributed to a build is a file
                // of numbers of unknown origin - the exact thing the evidence page forbids quoting.
                // See docs/decision-log.md DL-315.
                .append(GateEvidenceProvenance.stamp())
                .append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("| Run | Records | Elapsed (ms) | Peak heap (bytes) | Records/second |")
                .append(System.lineSeparator())
                .append("| --- | ---: | ---: | ---: | ---: |")
                .append(System.lineSeparator());
        for (final RunBaseline baseline : this.baselines) {
            rendered.append(baseline.asTableRow()).append(System.lineSeparator());
        }
        rendered.append(System.lineSeparator()).append("Fixture volumes each figure was measured over:")
                .append(System.lineSeparator());
        for (final RunBaseline baseline : this.baselines) {
            rendered.append("- **").append(baseline.label()).append("** - ")
                    .append(baseline.fixtureNote()).append(System.lineSeparator());
        }

        final Path target = EVIDENCE_DIRECTORY.resolve(fileName);
        try {
            Files.createDirectories(EVIDENCE_DIRECTORY);
            Files.writeString(target, rendered.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (final IOException failure) {
            throw new UncheckedIOException("the measured baseline could not be written to " + target,
                    failure);
        }
        return target;
    }

    /** Clears the peak accounting of every heap pool the platform publishes. */
    private static void resetHeapPeaks() {
        for (final MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.isValid()) {
                pool.resetPeakUsage();
            }
        }
    }

    /**
     * Sums the peak occupancy of every heap pool since the last reset.
     *
     * <p>Summed across pools rather than taken from the largest one, because a generational collector
     * divides one heap between several pools and the figure Gate 3 names is the heap's occupancy, not one
     * region's. The sum is an upper bound on simultaneous occupancy - pools do not necessarily peak at the
     * same instant - and reporting the bound is the honest direction to err in for a memory figure.</p>
     *
     * @return summed peak heap bytes
     */
    private static long peakHeapBytes() {
        long peak = 0L;
        for (final MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP || !pool.isValid()) {
                continue;
            }
            final MemoryUsage usage = pool.getPeakUsage();
            if (usage != null) {
                peak += usage.getUsed();
            }
        }
        return peak;
    }

    /**
     * Reads a run's own record count from whatever its launch returned.
     *
     * @param <T> the launch's return type
     */
    @FunctionalInterface
    public interface RecordCount<T> {

        /**
         * Returns the number of records the run processed.
         *
         * @param outcome the launch's return value
         * @return the run's record count
         */
        long of(T outcome);
    }

    /**
     * One run's measured figures, each scoped to that run.
     *
     * @param label how the run is named
     * @param fixtureNote the fixture volumes it was measured over
     * @param records records the run processed
     * @param elapsedNanos wall-clock nanoseconds the run took
     * @param peakHeapBytes summed peak heap occupancy during the run
     */
    public record RunBaseline(String label, String fixtureNote, long records, long elapsedNanos,
            long peakHeapBytes) {

        /** Validates a measurement rather than a threshold: the figures must be readable, not fast. */
        public RunBaseline {
            Objects.requireNonNull(label, "label must not be null");
            Objects.requireNonNull(fixtureNote, "fixtureNote must not be null");
            if (records < 0) {
                throw new IllegalArgumentException("a run cannot process a negative record count");
            }
            if (elapsedNanos <= 0) {
                throw new IllegalArgumentException("a measured run must take a positive time");
            }
            if (peakHeapBytes < 0) {
                throw new IllegalArgumentException("peak heap cannot be negative");
            }
        }

        /**
         * Returns the run's elapsed time in milliseconds.
         *
         * @return elapsed milliseconds
         */
        public long elapsedMillis() {
            return elapsedNanos() / 1_000_000L;
        }

        /**
         * Returns records divided by the run's own elapsed time.
         *
         * <p>This is the quotient Gate 3 names, and it is a quotient of two figures from the same run.
         * {@link BigDecimal} rather than a floating-point type, for the same reason every monetary field
         * in this module is: the value is reported and read by people, and a binary fraction printing as
         * something that is nearly the number is worse than useless in evidence.</p>
         *
         * @return records per second at two decimal places
         */
        public BigDecimal recordsPerSecond() {
            return BigDecimal.valueOf(records())
                    .multiply(NANOS_PER_SECOND)
                    .divide(BigDecimal.valueOf(elapsedNanos()), RATE_PRECISION)
                    .setScale(RATE_SCALE, RoundingMode.HALF_UP);
        }

        /**
         * Renders the run as one row of the evidence table.
         *
         * @return a markdown table row
         */
        public String asTableRow() {
            return String.format(Locale.ROOT, "| %s | %d | %d | %d | %s |",
                    label(), records(), elapsedMillis(), peakHeapBytes(), recordsPerSecond());
        }
    }
}
