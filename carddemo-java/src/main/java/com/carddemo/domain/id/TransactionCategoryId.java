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
package com.carddemo.domain.id;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key of the transaction-category reference entity, which declares this type as its
 * identifier class; the binding lives on the entity, so this type carries no persistence annotation and
 * imports nothing outside {@code java.base}. It is a class rather than a record because an identifier
 * class must offer a no-argument constructor for the provider to instantiate.
 *
 * <p>Derived from {@code app/cpy/CVTRA04Y.cpy}: the transaction type code then the category code, together
 * the 6-byte leading substring of the 60-byte record image, corroborated by the cluster geometry in
 * {@code app/jcl/TRANCATG.jcl} and by the migration's primary-key column order. Component order is
 * contractual. Both components are text even though the category code is digits-only, because leading
 * zeros and external widths are contractual and a numeric type would not reconstruct the key image;
 * nothing here converts, pads, trims or folds case.
 *
 * <p>The legacy key group name is shared with the unrelated 17-byte key of {@code app/cpy/CVTRA01Y.cpy},
 * which leads with an account identifier, so the two align at no offset and share no supertype or helper
 * (decision-log D-37). The transaction-type reference table is a third, distinct thing: its key is a
 * single 2-byte column with no identifier class at all.
 */
public class TransactionCategoryId implements Serializable {

    /**
     * Pinned so that a future field addition cannot change the identity of serialized keys.
     */
    private static final long serialVersionUID = 1L;

    private String tranTypeCd;

    private String tranCatCd;

    /**
     * Required by the persistence provider, which populates both components after construction.
     */
    protected TransactionCategoryId() {
    }

    /**
     * Creates a fully populated key in contractual component order; both values are stored as supplied.
     *
     * @param tranTypeCd transaction type code, as stored
     * @param tranCatCd  transaction category code, as stored
     */
    public TransactionCategoryId(final String tranTypeCd, final String tranCatCd) {
        this.tranTypeCd = tranTypeCd;
        this.tranCatCd = tranCatCd;
    }

    public String getTranTypeCd() {
        return tranTypeCd;
    }

    public String getTranCatCd() {
        return tranCatCd;
    }

    /**
     * Compares both components byte for byte, with no normalization, so that key equality agrees with the
     * database's notion of the same row.
     *
     * @param o the object to compare against
     * @return {@code true} only if {@code o} is a {@code TransactionCategoryId} with equal components
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategoryId other)) {
            return false;
        }
        return Objects.equals(tranTypeCd, other.tranTypeCd)
                && Objects.equals(tranCatCd, other.tranCatCd);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tranTypeCd, tranCatCd);
    }

    @Override
    public String toString() {
        return "TransactionCategoryId[tranTypeCd=" + tranTypeCd
                + ", tranCatCd=" + tranCatCd + "]";
    }
}
