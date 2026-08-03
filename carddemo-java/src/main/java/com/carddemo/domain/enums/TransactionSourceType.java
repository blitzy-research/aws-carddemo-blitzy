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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Origination channel of a transaction: the migrated vocabulary of {@code TRAN-SOURCE}, declared
 * {@code PIC X(10)} in the 350-byte transaction record.
 *
 * <p>The stored values are space-padded to exactly {@value #VALUE_LENGTH} characters and the
 * constants carry that padding, because it is part of the record image rather than an artefact of
 * it: {@link #fromValue(String)} therefore matches the padded form, and trimming here would both
 * break byte-level output parity and accept values the legacy field cannot hold. The mixed case of
 * the system value is equally deliberate - each literal is reproduced exactly as the legacy
 * programs move it, so no value may be derived from a constant name.
 *
 * <p>An unrecognised value yields an empty {@link Optional} and never throws, because the posting
 * tier takes this field straight from the input record without validating it.
 */
public enum TransactionSourceType {
    /** Point-of-sale terminal: the source of the 250 purchases in the daily-transaction fixture. */
    POS_TERM("POS TERM  "),

    /** Operator-originated: the source of the 50 returns in the daily-transaction fixture. */
    OPERATOR("OPERATOR  "),

    /** System-generated: the source the interest run writes on the transactions it synthesises. */
    SYSTEM("System    ");

    /** Width of the legacy field, and therefore of every value above, in characters. */
    public static final int VALUE_LENGTH = 10;

    private static final Map<String, TransactionSourceType> BY_VALUE = buildIndex();

    private final String value;

    TransactionSourceType(final String value) {
        this.value = value;
    }

    private static Map<String, TransactionSourceType> buildIndex() {
        final Map<String, TransactionSourceType> index = new LinkedHashMap<>();
        for (final TransactionSourceType sourceType : values()) {
            index.put(sourceType.value, sourceType);
        }
        return Map.copyOf(index);
    }

    /**
     * @return the padded value as it is stored in the record and the column
     */
    public String getValue() {
        return value;
    }

    /**
     * Resolves a stored value, which must carry its padding to match.
     *
     * @param value the raw field value, which may be {@code null}
     * @return the matching constant, or empty when the value is absent or outside the vocabulary
     */
    public static Optional<TransactionSourceType> fromValue(final String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_VALUE.get(value));
    }

    /**
     * @return {@code true} only for {@link #SYSTEM}
     */
    public boolean isSystemGenerated() {
        return switch (this) {
            case POS_TERM, OPERATOR -> false;
            case SYSTEM -> true;
        };
    }
}
