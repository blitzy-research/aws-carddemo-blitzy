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
 * Composite primary-key type for the disclosure-group entity, bound to it through
 * {@code @IdClass}. It carries no persistence annotation of its own: all mapping metadata lives on
 * the owning entity, and this type exists only to address the three-part business key as a single
 * value. Every component is text because the legacy key components are space-padded fixed-width
 * fields whose padding is significant, and no value is trimmed, folded or reformatted here.
 */
public class DisclosureGroupId implements Serializable {
    private static final long serialVersionUID = 1L;

    private String disAcctGroupId;

    private String disTranTypeCd;

    private String disTranCatCd;

    /** Required by the persistence provider; application code uses the three-argument constructor. */
    protected DisclosureGroupId() {
    }

    /**
     * Builds a key from its three components in the contractual order they occupy in the record
     * image: account group, then transaction type, then transaction category. The batch accrual
     * program populates its lookup key with assignments ordered group, category, type - the textual
     * order of three assignments to three distinct named fields carries no ordering semantics - so
     * "correcting" this signature to follow that sequence would transpose two short character
     * components, resolve the wrong row or none, and still compile.
     *
     * @param disAcctGroupId the account group identifier, space-padded as stored
     * @param disTranTypeCd the transaction type code, space-padded as stored
     * @param disTranCatCd the transaction category code, space-padded as stored
     */
    public DisclosureGroupId(String disAcctGroupId, String disTranTypeCd, String disTranCatCd) {
        this.disAcctGroupId = disAcctGroupId;
        this.disTranTypeCd = disTranTypeCd;
        this.disTranCatCd = disTranCatCd;
    }

    public String getDisAcctGroupId() {
        return disAcctGroupId;
    }

    public String getDisTranTypeCd() {
        return disTranTypeCd;
    }

    public String getDisTranCatCd() {
        return disTranCatCd;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DisclosureGroupId other)) {
            return false;
        }
        return Objects.equals(this.disAcctGroupId, other.disAcctGroupId)
                && Objects.equals(this.disTranTypeCd, other.disTranTypeCd)
                && Objects.equals(this.disTranCatCd, other.disTranCatCd);
    }

    @Override
    public int hashCode() {
        return Objects.hash(disAcctGroupId, disTranTypeCd, disTranCatCd);
    }

    @Override
    public String toString() {
        return "DisclosureGroupId[disAcctGroupId='" + disAcctGroupId
                + "', disTranTypeCd='" + disTranTypeCd
                + "', disTranCatCd='" + disTranCatCd + "']";
    }
}
