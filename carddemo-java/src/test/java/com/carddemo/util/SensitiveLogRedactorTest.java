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

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the one approved representation of a sensitive value in a production log.
 */
class SensitiveLogRedactorTest {

    private static final String SENSITIVE_VALUE = "00000000001";

    @Test
    @DisplayName("a present value becomes a fixed marker and a bounded lower-case token")
    void presentValueBecomesMarkerAndToken() {
        final String rendered = SensitiveLogRedactor.redact(SENSITIVE_VALUE);

        assertThat(rendered)
                .matches("\\[REDACTED] ref=[0-9a-f]{24}")
                .doesNotContain(SENSITIVE_VALUE);
    }

    @Test
    @DisplayName("the same value correlates within one process and a different value does not")
    void correlationIsStableAndValueSpecific() {
        final String first = SensitiveLogRedactor.redact(SENSITIVE_VALUE);

        assertThat(SensitiveLogRedactor.redact(SENSITIVE_VALUE)).isEqualTo(first);
        assertThat(SensitiveLogRedactor.redact("00000000002")).isNotEqualTo(first);
    }

    @Test
    @DisplayName("an absent value publishes only the fixed marker")
    void absentValuePublishesOnlyTheMarker() {
        assertThat(SensitiveLogRedactor.redact(null))
                .isEqualTo(SensitiveLogRedactor.REDACTED);
    }

    @Test
    @DisplayName("caller-controlled delimiters and control bytes never survive the token boundary")
    void callerControlledCharactersNeverSurvive() {
        final String rendered = SensitiveLogRedactor.redact("value=\r\n\tsecret");

        assertThat(rendered)
                .matches("\\[REDACTED] ref=[0-9a-f]{24}")
                .doesNotContain("value", "secret", "\r", "\n", "\t");
    }

    @Test
    @DisplayName("concurrent callers receive the same token without sharing a mutable MAC")
    void concurrentCallersReceiveTheSameToken() {
        final List<String> rendered = IntStream.range(0, 64)
                .parallel()
                .mapToObj(ignored -> SensitiveLogRedactor.redact(SENSITIVE_VALUE))
                .toList();

        assertThat(rendered).containsOnly(rendered.getFirst());
    }
}
