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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.carddemo.domain.TransactionCategoryBalance;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the single transaction-category-balance composite-key authority.
 *
 * <p>Four call sites previously each carried their own copy of this slicing arithmetic. These tests
 * exist so that the one surviving copy is pinned: an offset changed here fails immediately, whereas
 * an offset changed in one of four private copies used to fail nowhere and simply resumed a keyset
 * cursor in the wrong place.
 */
@DisplayName("TransactionCategoryBalanceKeyCodec - the 11/2/4 composite key as one key image")
final class TransactionCategoryBalanceKeyCodecTest {

    private static final String ACCOUNT_ID = "00000000011";
    private static final String TYPE_CODE = "01";
    private static final String CATEGORY_CODE = "0005";
    private static final String FULL_IMAGE = ACCOUNT_ID + TYPE_CODE + CATEGORY_CODE;

    @Test
    @DisplayName("the declared geometry is the cluster's contiguous 11 + 2 + 4 key run from offset zero")
    void geometryMatchesTheClusterDefinition() {
        assertThat(TransactionCategoryBalanceKeyCodec.ACCOUNT_ID_OFFSET).isZero();
        assertThat(TransactionCategoryBalanceKeyCodec.ACCOUNT_ID_LENGTH).isEqualTo(11);
        assertThat(TransactionCategoryBalanceKeyCodec.TYPE_CODE_OFFSET).isEqualTo(11);
        assertThat(TransactionCategoryBalanceKeyCodec.TYPE_CODE_LENGTH).isEqualTo(2);
        assertThat(TransactionCategoryBalanceKeyCodec.CATEGORY_CODE_OFFSET).isEqualTo(13);
        assertThat(TransactionCategoryBalanceKeyCodec.CATEGORY_CODE_LENGTH).isEqualTo(4);
        assertThat(TransactionCategoryBalanceKeyCodec.KEY_LENGTH).isEqualTo(17);
        assertThat(TransactionCategoryBalanceKeyCodec.LOW_VALUES).isEmpty();
    }

    @Test
    @DisplayName("a row and its three parts render the same key image, concatenated in cluster-key order")
    void imageConcatenatesInClusterKeyOrder() {
        final TransactionCategoryBalance row = new TransactionCategoryBalance(
                ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE, new BigDecimal("123.45"));

        assertThat(TransactionCategoryBalanceKeyCodec.image(row))
                .isEqualTo(FULL_IMAGE)
                .hasSize(TransactionCategoryBalanceKeyCodec.KEY_LENGTH);
        assertThat(TransactionCategoryBalanceKeyCodec.image(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE))
                .isEqualTo(FULL_IMAGE);
    }

    @Test
    @DisplayName("a whole key image splits back into exactly the three parts it was built from")
    void aWholeImageRoundTrips() {
        assertThat(TransactionCategoryBalanceKeyCodec.accountIdOf(FULL_IMAGE)).isEqualTo(ACCOUNT_ID);
        assertThat(TransactionCategoryBalanceKeyCodec.typeCodeOf(FULL_IMAGE)).isEqualTo(TYPE_CODE);
        assertThat(TransactionCategoryBalanceKeyCodec.categoryCodeOf(FULL_IMAGE))
                .isEqualTo(CATEGORY_CODE);
    }

    @Test
    @DisplayName("the low-value cursor leaves every part unconstrained, which is what starts a scan")
    void theLowValueCursorConstrainsNothing() {
        final String low = TransactionCategoryBalanceKeyCodec.LOW_VALUES;

        assertThat(TransactionCategoryBalanceKeyCodec.accountIdOf(low)).isEmpty();
        assertThat(TransactionCategoryBalanceKeyCodec.typeCodeOf(low)).isEmpty();
        assertThat(TransactionCategoryBalanceKeyCodec.categoryCodeOf(low)).isEmpty();
    }

    @Test
    @DisplayName("a partial image constrains only the parts it reaches, and never pads the rest")
    void aPartialImageConstrainsOnlyWhatItReaches() {
        assertThat(TransactionCategoryBalanceKeyCodec.accountIdOf(ACCOUNT_ID))
                .isEqualTo(ACCOUNT_ID);
        assertThat(TransactionCategoryBalanceKeyCodec.typeCodeOf(ACCOUNT_ID)).isEmpty();
        assertThat(TransactionCategoryBalanceKeyCodec.categoryCodeOf(ACCOUNT_ID)).isEmpty();

        final String throughType = ACCOUNT_ID + TYPE_CODE;
        assertThat(TransactionCategoryBalanceKeyCodec.typeCodeOf(throughType)).isEqualTo(TYPE_CODE);
        assertThat(TransactionCategoryBalanceKeyCodec.categoryCodeOf(throughType)).isEmpty();

        assertThat(TransactionCategoryBalanceKeyCodec.accountIdOf("0000"))
                .as("a short account part is returned as supplied rather than padded to eleven")
                .isEqualTo("0000");
    }

    @Test
    @DisplayName("the category part carries whatever the image holds beyond the type code, including an "
            + "overpunched sign byte, and is never truncated to its declared width")
    void theCategoryPartIsNotTruncated() {
        assertThat(TransactionCategoryBalanceKeyCodec.categoryCodeOf(ACCOUNT_ID + TYPE_CODE + "000J"))
                .as("an overpunched negative category code survives whole")
                .isEqualTo("000J");
        assertThat(TransactionCategoryBalanceKeyCodec
                .categoryCodeOf(ACCOUNT_ID + TYPE_CODE + "0005EXTRA"))
                .as("a longer-than-declared tail is handed back rather than silently cut")
                .isEqualTo("0005EXTRA");
    }

    @Test
    @DisplayName("no argument may be absent: a null row, part or image is refused rather than treated "
            + "as a low value")
    void absentArgumentsAreRefused() {
        assertThatNullPointerException()
                .isThrownBy(() -> TransactionCategoryBalanceKeyCodec.image(
                        (TransactionCategoryBalance) null));
        assertThatNullPointerException()
                .isThrownBy(() -> TransactionCategoryBalanceKeyCodec.image(
                        null, TYPE_CODE, CATEGORY_CODE));
        assertThatNullPointerException()
                .isThrownBy(() -> TransactionCategoryBalanceKeyCodec.image(
                        ACCOUNT_ID, null, CATEGORY_CODE));
        assertThatNullPointerException()
                .isThrownBy(() -> TransactionCategoryBalanceKeyCodec.image(
                        ACCOUNT_ID, TYPE_CODE, null));
        assertThatNullPointerException()
                .isThrownBy(() -> TransactionCategoryBalanceKeyCodec.accountIdOf(null));
        assertThatNullPointerException()
                .isThrownBy(() -> TransactionCategoryBalanceKeyCodec.typeCodeOf(null));
        assertThatNullPointerException()
                .isThrownBy(() -> TransactionCategoryBalanceKeyCodec.categoryCodeOf(null));
    }
}
