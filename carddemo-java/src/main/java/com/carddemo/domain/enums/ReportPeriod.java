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
package com.carddemo.domain.enums;

import java.util.Map;
import java.util.Optional;

/**
 * Transaction-report period selector, carried as the literal the legacy report-request program
 * {@code CORPT00C} (transaction {@code CR00}) moves into its report-name work field and echoes back
 * to the operator.
 *
 * <p>The literals are bare rather than padded to the ten-character width of that work field. Both
 * of the program's read sites consume the field delimited by space, so the padding never reaches
 * output, and the field is write-only as a discriminator - no branch anywhere compares it - so no
 * behaviour depends on its width. Padding these values would emit trailing spaces the legacy
 * program never produced. The mixed casing is what the operator sees and is therefore reproduced
 * literally rather than derived from the constant names; no folding or normalisation is applied.
 *
 * <p>Constant order is the order the legacy program tests the screen options in - monthly, then
 * yearly, then custom - and it stops at the first that is set. Which period applies, the custom
 * period's date pair and the submission gate all belong to the report-request service; the
 * program's catch-all branch is an input-validation outcome rather than a fourth period, which is
 * why {@link #fromValue(String)} models an unrecognised value as an empty {@link Optional} instead
 * of a synthetic constant.
 */
public enum ReportPeriod {
    MONTHLY("Monthly"),

    YEARLY("Yearly"),

    CUSTOM("Custom");

    private static final Map<String, ReportPeriod> BY_VALUE = Map.of(
            MONTHLY.value, MONTHLY,
            YEARLY.value, YEARLY,
            CUSTOM.value, CUSTOM);

    private final String value;

    ReportPeriod(String value) {
        this.value = value;
    }

    /**
     * @return the literal the operator reads back, exactly as the legacy program emits it
     */
    public String getValue() {
        return value;
    }

    /**
     * Resolves a literal exactly as stored, applying no folding or trimming.
     *
     * @param value the report-name value, which may be {@code null}
     * @return the matching constant, or empty when the value is absent or unrecognised
     */
    public static Optional<ReportPeriod> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_VALUE.get(value));
    }
}
