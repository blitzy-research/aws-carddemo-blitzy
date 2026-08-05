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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

final class ExternalStringSorterTest {

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