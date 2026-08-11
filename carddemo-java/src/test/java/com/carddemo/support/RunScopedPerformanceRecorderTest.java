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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.support.RunScopedPerformanceRecorder.RunBaseline;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Gate 3 measurement primitive: what it computes, what it refuses, and what it will not do.
 *
 * <p>The figures a measured run produces are properties of the machine that ran it and cannot be asserted.
 * What can be asserted, and is asserted here, is that the recorder computes the quotient Gate 3 names
 * rather than some other quotient, that it reads a peak the platform reports rather than a sample, that it
 * refuses a figure with no fixture volumes beside it, and that it writes its output into the build tree and
 * never into the documentation tree.
 */
@DisplayName("Gate 3 measurement: run-scoped figures, and nothing that could become a threshold")
class RunScopedPerformanceRecorderTest {

    @Nested
    @DisplayName("the arithmetic")
    class TheArithmetic {

        @Test
        @DisplayName("divides records by the RUN's elapsed time, not by any window")
        void recordsPerSecondIsRecordsOverElapsed() {
            // Three hundred records in four seconds is seventy-five per second. The dashboard's rolling
            // rate over a one-minute window would report five, which is the defect this replaces: the
            // rate is not wrong, it simply divides by the window instead of by the run.
            final RunBaseline baseline =
                    new RunBaseline("posting", "300 daily transactions", 300L, 4_000_000_000L, 1L);

            assertThat(baseline.recordsPerSecond()).isEqualByComparingTo(new BigDecimal("75.00"));
            assertThat(baseline.elapsedMillis()).isEqualTo(4_000L);
        }

        @Test
        @DisplayName("reports the rate as a decimal at two places, never as a binary fraction")
        void theRateIsDecimalAndScaled() {
            // Evidence is read by people. A binary fraction printing as something that is nearly the
            // number is worse than useless in a document someone quotes from, so the same BigDecimal
            // discipline the estate's money carries applies to the derived figure too.
            //
            // 12505 records in exactly 1000 seconds is exactly 12.505 per second, a quotient that lands
            // on the rounding boundary, so this also pins the direction: a reported figure rounds to
            // nearest and up on a tie. Note the deliberate difference from the estate's ARITHMETIC, which
            // truncates because no COBOL statement asks for rounding - that rule governs a stored money
            // value, and this is a derived measurement being presented, not a value being stored.
            final RunBaseline baseline =
                    new RunBaseline("boundary", "12505 synthetic records", 12_505L, 1_000_000_000_000L, 1L);

            assertThat(baseline.recordsPerSecond().scale()).isEqualTo(2);
            assertThat(baseline.recordsPerSecond()).isEqualByComparingTo(new BigDecimal("12.51"));
        }

        @Test
        @DisplayName("a rate with a long expansion is reported at the same two places")
        void aRepeatingQuotientIsStillReportable() {
            // One record in three seconds does not terminate. A division without a precision would throw
            // here rather than report anything, which is why the intermediate division carries one.
            final RunBaseline baseline =
                    new RunBaseline("repeating", "1 synthetic record", 1L, 3_000_000_000L, 1L);

            assertThat(baseline.recordsPerSecond()).isEqualByComparingTo(new BigDecimal("0.33"));
        }

        @Test
        @DisplayName("a run that processed nothing still yields a readable rate of zero")
        void anEmptyRunIsStillMeasurable() {
            final RunBaseline baseline = new RunBaseline("probe", "empty input", 0L, 1_000_000L, 1L);

            assertThat(baseline.recordsPerSecond()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("what a baseline refuses to be")
    class WhatABaselineRefusesToBe {

        @Test
        @DisplayName("refuses a run with no elapsed time, because the quotient would not exist")
        void zeroElapsedIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new RunBaseline("x", "fixtures", 1L, 0L, 1L))
                    .withMessageContaining("positive time");
        }

        @Test
        @DisplayName("refuses a negative record count and a negative peak")
        void negativeFiguresAreRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new RunBaseline("x", "fixtures", -1L, 1L, 1L));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new RunBaseline("x", "fixtures", 1L, 1L, -1L));
        }

        @Test
        @DisplayName("refuses a measurement with no fixture volumes named beside it")
        void aFigureWithoutItsFixturesIsRefused() throws Exception {
            // A number without the volumes it was measured over is not a baseline, so the recorder will
            // not take one. This is the one rule it enforces about the MEANING of a figure rather than
            // about its shape.
            final RunScopedPerformanceRecorder recorder = new RunScopedPerformanceRecorder();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> recorder.measure("run", "   ", outcome -> 1L, () -> "done"))
                    .withMessageContaining("fixture volumes");
        }
    }

    @Nested
    @DisplayName("measuring a run")
    class MeasuringARun {

        @Test
        @DisplayName("returns the launch's own value, so a caller goes on asserting behaviour")
        void theLaunchValueIsReturned() throws Exception {
            final RunScopedPerformanceRecorder recorder = new RunScopedPerformanceRecorder();

            final String outcome = recorder.measure("run", "one synthetic record",
                    measured -> 1L, () -> "the launch value");

            assertThat(outcome).isEqualTo("the launch value");
        }

        @Test
        @DisplayName("records a positive elapsed time and a peak the platform reported")
        void aMeasuredRunCarriesAllThreeFigures() throws Exception {
            final RunScopedPerformanceRecorder recorder = new RunScopedPerformanceRecorder();
            final List<byte[]> retained = new ArrayList<>();

            recorder.measure("allocating run", "one megabyte of synthetic allocation",
                    measured -> 1L, () -> {
                        // Something has to occupy the heap, or the peak would be whatever the JVM was
                        // already holding and the reading would prove nothing about the reset.
                        retained.add(new byte[1_000_000]);
                        return "done";
                    });

            assertThat(recorder.baselines()).singleElement().satisfies(baseline -> {
                assertThat(baseline.elapsedNanos()).isPositive();
                assertThat(baseline.peakHeapBytes())
                        .as("the platform publishes a peak per heap pool; summed it must exceed the "
                                + "allocation the run made")
                        .isGreaterThan(1_000_000L);
                assertThat(baseline.records()).isEqualTo(1L);
            });
            assertThat(retained).hasSize(1);
        }

        @Test
        @DisplayName("propagates a failed launch unchanged and records nothing for it")
        void aFailedLaunchIsNotABaseline() {
            final RunScopedPerformanceRecorder recorder = new RunScopedPerformanceRecorder();
            final IllegalStateException launchFailure = new IllegalStateException("the job failed");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> recorder.measure("failing run", "fixtures", measured -> 1L, () -> {
                        throw launchFailure;
                    }))
                    .isSameAs(launchFailure);

            assertThat(recorder.baselines())
                    .as("a run that did not complete has no baseline to report")
                    .isEmpty();
        }

        @Test
        @DisplayName("keeps several measured runs in the order they were taken")
        void severalRunsAreKeptInOrder() throws Exception {
            final RunScopedPerformanceRecorder recorder = new RunScopedPerformanceRecorder();

            recorder.measure("first", "fixtures", measured -> 1L, () -> "a");
            recorder.measure("second", "fixtures", measured -> 2L, () -> "b");

            assertThat(recorder.baselines()).extracting(RunBaseline::label)
                    .containsExactly("first", "second");
        }
    }

    @Nested
    @DisplayName("publishing the figures")
    class PublishingTheFigures {

        @Test
        @DisplayName("writes into the build output and never into the documentation tree")
        void theEvidenceGoesToTheBuildOutput() {
            // A test that edited docs/ would make the repository's content depend on the hardware of
            // whoever last ran the suite. The recorded figures belong in the documentation, but a person
            // puts them there, beside the date and the machine.
            assertThat(RunScopedPerformanceRecorder.EVIDENCE_DIRECTORY)
                    .isEqualTo(Path.of("target", "gate-evidence"));
            assertThat(RunScopedPerformanceRecorder.EVIDENCE_DIRECTORY.toString())
                    .doesNotContain("docs");
        }

        @Test
        @DisplayName("renders every figure, the fixture volumes, and a warning against reading it as a "
                + "threshold")
        void theRenderedEvidenceCarriesTheFixturesAndTheCaveat() throws Exception {
            final RunScopedPerformanceRecorder recorder = new RunScopedPerformanceRecorder();
            recorder.measure("renderedRun", "42 synthetic records", measured -> 42L, () -> "done");

            final Path published = recorder.publish("blitzy_adhoc_test_recorder_evidence.md");

            final String rendered = Files.readString(published, StandardCharsets.UTF_8);
            assertThat(rendered)
                    .contains("| Run | Records | Elapsed (ms) | Peak heap (bytes) | Records/second |")
                    .contains("| renderedRun |")
                    .contains("42 synthetic records")
                    .contains("measurements, not thresholds");
            Files.deleteIfExists(published);
        }

        @Test
        @DisplayName("refuses a blank file name rather than writing to the directory itself")
        void aBlankFileNameIsRefused() {
            final RunScopedPerformanceRecorder recorder = new RunScopedPerformanceRecorder();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> recorder.publish("  "));
        }
    }
}
