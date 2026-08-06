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
import java.util.Locale;
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
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StatementWorkRecordMapper.key("short"));
    }

    @Test
    @DisplayName("the projected geometry is published here and nowhere else: card plus identifier key, "
            + "328 selected bytes and 22 bytes of fixed-record padding")
    void projectedGeometryIsPublished() {
        assertThat(StatementWorkRecordMapper.RECORD_LENGTH).isEqualTo(350);
        assertThat(StatementWorkRecordMapper.CARD_NUMBER_OFFSET).isZero();
        assertThat(StatementWorkRecordMapper.TRANSACTION_ID_OFFSET).isEqualTo(16);
        assertThat(StatementWorkRecordMapper.KEY_LENGTH).isEqualTo(32);
        assertThat(StatementWorkRecordMapper.TRANSACTION_REST_OFFSET).isEqualTo(32);
        assertThat(StatementWorkRecordMapper.TRANSACTION_REST_LENGTH).isEqualTo(318);
        assertThat(StatementWorkRecordMapper.TIMESTAMP_SEGMENT_OFFSET).isEqualTo(278);
        assertThat(StatementWorkRecordMapper.PROJECTED_CONTENT_LENGTH).isEqualTo(328);
        assertThat(StatementWorkRecordMapper.BLANK_PAD_LENGTH).isEqualTo(22);
        assertThat(StatementWorkRecordMapper.TRUNCATED_PROCESSING_TIMESTAMP_LENGTH).isEqualTo(2);
    }

    @Test
    @DisplayName("the buffer-with-offset overload decodes a record held inside a larger block")
    void bufferWithOffsetOverloadDecodesTheSameRecord() {
        final String work = StatementWorkRecordMapper.fromTransactionRecord(canonicalFixture());
        final byte[] block = ("prefix" + work).getBytes(StandardCharsets.US_ASCII);

        assertThat(StatementWorkRecordMapper.fromRecord(block, "prefix".length()))
                .usingRecursiveComparison()
                .isEqualTo(StatementWorkRecordMapper.fromRecord(work));

        assertThatNullPointerException()
                .isThrownBy(() -> StatementWorkRecordMapper.fromRecord(null, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StatementWorkRecordMapper.fromRecord(block, 1));
    }

    @Test
    @DisplayName("the canonical transaction mapper publishes NO statement-work surface, so the "
            + "projection cannot be re-derived from a second offset authority")
    void theCanonicalMapperCarriesNoSecondProjectionAuthority() {
        // The dual-authority defect this guards against compiled cleanly and produced identical bytes;
        // only an explicit surface check catches a re-introduction before it drifts. Reflection is used
        // deliberately and only here: the unsafe-code audit scopes reflection counting to
        // src/main/java/** precisely because a test may legitimately inspect a surface, and asserting
        // the ABSENCE of a member is not expressible any other way.
        assertThat(TransactionRecordMapper.class.getDeclaredMethods())
                .as("no method of the canonical mapper may name the statement-work layout")
                .noneMatch(method -> method.getName().toLowerCase(Locale.ROOT)
                        .contains("statementwork"));
        assertThat(TransactionRecordMapper.class.getDeclaredFields())
                .as("no constant of the canonical mapper may name the statement-work layout")
                .noneMatch(field -> field.getName().startsWith("STATEMENT_WORK"));
    }
}