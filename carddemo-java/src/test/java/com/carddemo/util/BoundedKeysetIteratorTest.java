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
package com.carddemo.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

final class BoundedKeysetIteratorTest {

    @Test
    void loadsOnlyBoundedStrictlyAdvancingPages() {
        final List<String> source = List.of("01", "02", "03", "04", "05");
        final List<String> cursors = new ArrayList<>();
        final List<Integer> requestedLimits = new ArrayList<>();
        final BoundedKeysetIterator<String, String> iterator = new BoundedKeysetIterator<>(
                "", 2,
                (cursor, limit) -> {
                    cursors.add(cursor);
                    requestedLimits.add(limit);
                    return source.stream()
                            .filter(value -> value.compareTo(cursor) > 0)
                            .limit(limit.longValue())
                            .toList();
                },
                value -> value,
                Comparator.naturalOrder());

        final List<String> delivered = new ArrayList<>();
        iterator.forEachRemaining(delivered::add);

        assertThat(delivered).containsExactlyElementsOf(source);
        assertThat(cursors).containsExactly("", "02", "04", "05");
        assertThat(requestedLimits).containsOnly(2);
    }

    @Test
    void constructionAndRepeatedHasNextDoNotReadAheadUnboundedly() {
        final AtomicInteger loads = new AtomicInteger();
        final BoundedKeysetIterator<String, String> iterator = new BoundedKeysetIterator<>(
                "", 1,
                (cursor, limit) -> loads.getAndIncrement() == 0 ? List.of("01") : List.of(),
                value -> value,
                Comparator.naturalOrder());

        assertThat(loads).hasValue(0);
        assertThat(iterator.hasNext()).isTrue();
        assertThat(iterator.hasNext()).isTrue();
        assertThat(loads).hasValue(1);
        assertThat(iterator.next()).isEqualTo("01");
        assertThat(iterator.hasNext()).isFalse();
        assertThat(loads).hasValue(2);
    }

    @Test
    void rejectsInvalidLoaderContracts() {
        assertThatThrownBy(() -> new BoundedKeysetIterator<String, String>(
                "", 0, (cursor, limit) -> List.of(), value -> value,
                Comparator.<String>naturalOrder()))
                .isInstanceOf(IllegalArgumentException.class);

        final BoundedKeysetIterator<String, String> overSized = new BoundedKeysetIterator<>(
                "", 1, (cursor, limit) -> List.of("01", "02"), value -> value,
                Comparator.naturalOrder());
        assertThatThrownBy(overSized::hasNext).isInstanceOf(IllegalStateException.class);

        final BoundedKeysetIterator<String, String> nonAdvancing = new BoundedKeysetIterator<>(
                "01", 1, (cursor, limit) -> List.of("01"), value -> value,
                Comparator.naturalOrder());
        assertThatThrownBy(nonAdvancing::next).isInstanceOf(IllegalStateException.class);
    }
}
