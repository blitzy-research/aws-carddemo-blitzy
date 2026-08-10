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
package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.util.StatementHtmlTemplates;
import com.carddemo.util.StatementTextTemplates;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit specification for {@link StatementLineSummary}, the service-owned twin of the published
 * statement-summary schema.
 *
 * <p>The carrier is intentionally behaviour-light: it normalises nothing, validates nothing and
 * formats nothing. These tests therefore protect carriage rather than business logic. They prove
 * that all thirteen values survive reconstruction unchanged, that null and blank representations
 * remain distinct, that diagnostic rendering withholds the cardholder-bearing values, and that the
 * carried values still compose into the legacy statement writers' exact 80-byte text and 100-byte
 * HTML records.</p>
 *
 * <p>Provenance: {@code app/cpy/COSTM01.CPY} lines 20-36 at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("StatementLineSummary :: service-owned statement values are carried verbatim")
final class StatementLineSummaryTest {

    private static final String BLANK_STAMP = " ".repeat(26);
    private static final String ORIGINATION_STAMP = "2022-06-10 19:27:53.000000";
    private static final String PROCESSING_STAMP = "2022-06-10-19.27.53.000000";

    /**
     * Pads a printable value to a measured fixed width without truncating it.
     *
     * @param value the value to retain
     * @param width the target width
     * @return the value followed by enough spaces to reach the target
     */
    private static String pad(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * A fully populated row whose sensitive values are unique enough to detect in diagnostics.
     *
     * @return one service-owned statement projection
     */
    private static StatementLineSummary populated() {
        return new StatementLineSummary(
                "4111111111111111",
                "0000000000000042",
                "01",
                "0005",
                "POS TERM  ",
                pad("AIRLINE TICKET <PRIVATE>", 100),
                new BigDecimal("42.75"),
                "876543210",
                pad("SECRET MERCHANT", 50),
                pad("PRIVATE CITY", 50),
                "98101-0001",
                ORIGINATION_STAMP,
                PROCESSING_STAMP);
    }

    /**
     * Reconstructs the record exclusively through its accessors.
     *
     * @param value the record to copy
     * @return a component-for-component reconstruction
     */
    private static StatementLineSummary copyOf(final StatementLineSummary value) {
        return new StatementLineSummary(value.cardNumber(), value.transactionId(), value.typeCode(),
                value.categoryCode(), value.source(), value.description(), value.amount(),
                value.merchantId(), value.merchantName(), value.merchantCity(),
                value.merchantZip(), value.originationTimestamp(), value.processingTimestamp());
    }

    @Test
    @DisplayName("all thirteen components round-trip through the canonical accessors unchanged")
    void allComponentsRoundTripUnchanged() {
        final StatementLineSummary original = populated();
        final StatementLineSummary reconstructed = copyOf(original);

        assertThat(reconstructed)
                .as("record reconstruction is the service-layer round trip: no adapter, formatter or "
                        + "normaliser participates")
                .isEqualTo(original)
                .hasSameHashCodeAs(original);
        assertThat(reconstructed.amount())
                .isEqualTo(new BigDecimal("42.75"))
                .hasScaleOf(2);
        assertThat(reconstructed.description()).hasSize(100);
        assertThat(reconstructed.source()).isEqualTo("POS TERM  ").hasSize(10);
        assertThat(reconstructed.originationTimestamp()).isEqualTo(ORIGINATION_STAMP).hasSize(26);
        assertThat(reconstructed.processingTimestamp()).isEqualTo(PROCESSING_STAMP).hasSize(26);
    }

    @Test
    @DisplayName("null components remain null rather than becoming blank or zero")
    void nullComponentsRemainNull() {
        final StatementLineSummary empty = new StatementLineSummary(
                null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(empty.cardNumber()).isNull();
        assertThat(empty.transactionId()).isNull();
        assertThat(empty.typeCode()).isNull();
        assertThat(empty.categoryCode()).isNull();
        assertThat(empty.source()).isNull();
        assertThat(empty.description()).isNull();
        assertThat(empty.amount()).isNull();
        assertThat(empty.merchantId()).isNull();
        assertThat(empty.merchantName()).isNull();
        assertThat(empty.merchantCity()).isNull();
        assertThat(empty.merchantZip()).isNull();
        assertThat(empty.originationTimestamp()).isNull();
        assertThat(empty.processingTimestamp()).isNull();
    }

    @Test
    @DisplayName("empty strings, blank-filled channels and blank stamps remain byte-distinct")
    void blankRepresentationsRemainDistinct() {
        final StatementLineSummary blanks = new StatementLineSummary(
                "", "", "", "", " ".repeat(10), " ".repeat(100), null, " ".repeat(9),
                " ".repeat(50), " ".repeat(50), " ".repeat(10), BLANK_STAMP, BLANK_STAMP);

        final StatementLineSummary reconstructed = copyOf(blanks);

        assertThat(reconstructed).isEqualTo(blanks);
        assertThat(reconstructed.cardNumber()).isEmpty();
        assertThat(reconstructed.source()).isEqualTo(" ".repeat(10)).hasSize(10);
        assertThat(reconstructed.description()).isEqualTo(" ".repeat(100)).hasSize(100);
        assertThat(reconstructed.originationTimestamp()).isEqualTo(BLANK_STAMP).hasSize(26);
        assertThat(reconstructed.processingTimestamp()).isEqualTo(BLANK_STAMP).hasSize(26);
    }

    @Test
    @DisplayName("diagnostic rendering retains identifiers and classification but redacts statement data")
    void diagnosticRenderingIsRedacted() {
        final StatementLineSummary value = populated();

        assertThat(value.toString())
                .isEqualTo("StatementLineSummary[cardNumber=***REDACTED***"
                        + ", transactionId=0000000000000042"
                        + ", typeCode=01"
                        + ", categoryCode=0005"
                        + ", source=POS TERM  "
                        + ", description=***REDACTED***"
                        + ", amount=***REDACTED***"
                        + ", merchantId=***REDACTED***"
                        + ", merchantName=***REDACTED***"
                        + ", merchantCity=***REDACTED***"
                        + ", merchantZip=***REDACTED***"
                        + ", originationTimestamp=" + ORIGINATION_STAMP
                        + ", processingTimestamp=" + PROCESSING_STAMP
                        + "]")
                .doesNotContain(value.cardNumber())
                .doesNotContain(value.description().strip())
                .doesNotContain(value.amount().toPlainString())
                .doesNotContain(value.merchantId())
                .doesNotContain(value.merchantName().strip())
                .doesNotContain(value.merchantCity().strip())
                .doesNotContain(value.merchantZip());
    }

    @Test
    @DisplayName("the carried values still compose into exact 80-byte text and 100-byte HTML records")
    void carriedValuesPreserveStatementRecordWidths() {
        final StatementLineSummary value = populated();

        final String textRecord = StatementTextTemplates.stLine14Transaction(
                value.transactionId(), value.description(), value.amount());
        final String htmlIdRecord =
                StatementHtmlTemplates.transactionWorkLine(value.transactionId());
        final String htmlDescriptionRecord =
                StatementHtmlTemplates.transactionWorkLine(value.description());
        final String htmlAmountRecord = StatementHtmlTemplates.transactionWorkLine(
                StatementTextTemplates.formatAmountMaskWithZeroSuppression(value.amount()));

        assertThat(textRecord.getBytes(StandardCharsets.US_ASCII))
                .hasSize(StatementTextTemplates.STATEMENT_RECORD_LENGTH)
                .hasSize(80);
        assertThat(htmlIdRecord.getBytes(StandardCharsets.US_ASCII))
                .hasSize(StatementHtmlTemplates.HTML_RECORD_LENGTH)
                .hasSize(100);
        assertThat(htmlDescriptionRecord.getBytes(StandardCharsets.US_ASCII))
                .hasSize(StatementHtmlTemplates.HTML_RECORD_LENGTH)
                .hasSize(100);
        assertThat(htmlAmountRecord.getBytes(StandardCharsets.US_ASCII))
                .hasSize(StatementHtmlTemplates.HTML_RECORD_LENGTH)
                .hasSize(100);
        assertThat(copyOf(value))
                .as("composing records must not mutate or normalise the service-owned carrier")
                .isEqualTo(value);
    }
}
