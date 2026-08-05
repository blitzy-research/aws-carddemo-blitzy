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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.carddemo.domain.Transaction;
import com.carddemo.support.SeededRecordFixture;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the 350-byte statement work record and its 328-byte JCL projection.
 */
@DisplayName("StatementWorkRecordMapper - card, id, 318-byte remainder over the 328-byte projection")
final class StatementWorkRecordMapperTest {

    private static String canonicalFixture() {
        return SeededRecordFixture.load("dailytran.txt", TransactionRecordMapper.RECORD_LENGTH)
                .record(1);
    }

    @Test
    @DisplayName("the three projected segments and the 22-byte pad reproduce CREASTMT exactly")
    void projectionMatchesTheJclSegments() {
        final String canonical = canonicalFixture();
        final String work = StatementWorkRecordMapper.fromTransactionRecord(canonical);

        assertThat(work).hasSize(StatementWorkRecordMapper.RECORD_LENGTH);
        assertThat(work.substring(0, 16)).isEqualTo(canonical.substring(262, 278));
        assertThat(work.substring(16, 278)).isEqualTo(canonical.substring(0, 262));
        assertThat(work.substring(278, 328)).isEqualTo(canonical.substring(278, 328));
        assertThat(work.substring(328)).isEqualTo(" ".repeat(22));
        assertThat(StatementWorkRecordMapper.key(work))
                .isEqualTo(canonical.substring(262, 278) + canonical.substring(0, 16));
    }

    @Test
    @DisplayName("decoding consumes the work layout and preserves the two-byte timestamp truncation")
    void decodingPreservesTheIntentionalTruncation() {
        final Transaction transaction = new Transaction(
                "0000000000000001", "01", "0005", "System    ",
                "statement projection".repeat(5), new BigDecimal("12.34"), "000000000",
                " ".repeat(50), " ".repeat(50), " ".repeat(10), "4111111111111111",
                "2026-08-05-12.34.56.780000", "2026-08-05-23.45.01.1299AB");

        final String work = StatementWorkRecordMapper.toRecord(transaction);
        final Transaction decoded = StatementWorkRecordMapper.fromRecord(work);

        assertThat(decoded.getTranId()).isEqualTo(transaction.getTranId());
        assertThat(decoded.getTranCardNum()).isEqualTo(transaction.getTranCardNum());
        assertThat(decoded.getTranOrigTs()).isEqualTo(transaction.getTranOrigTs());
        assertThat(decoded.getTranProcTs())
                .isEqualTo(transaction.getTranProcTs().substring(0, 24) + "  ")
                .isNotEqualTo(transaction.getTranProcTs());
    }

    @Test
    @DisplayName("the byte-array overload decodes the same staged record")
    void byteArrayOverloadAgrees() {
        final String work = StatementWorkRecordMapper.fromTransactionRecord(canonicalFixture());

        assertThat(StatementWorkRecordMapper.fromRecord(
                work.getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo(StatementWorkRecordMapper.fromRecord(work));
    }

    @Test
    @DisplayName("absent and mis-sized records are refused at the mapper boundary")
    void invalidRecordsAreRefused() {
        assertThatNullPointerException()
                .isThrownBy(() -> StatementWorkRecordMapper.fromRecord((String) null));
        assertThatNullPointerException()
                .isThrownBy(() -> StatementWorkRecordMapper.fromRecord((byte[]) null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StatementWorkRecordMapper.fromRecord("short"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StatementWorkRecordMapper.fromTransactionRecord("short"));
    }
}