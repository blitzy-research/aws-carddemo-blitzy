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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The CardDemo 3270 attention-key identifier: one constant per condition name declared beneath the
 * five-character work-area field of {@code app/cpy/CVCRD01Y.cpy}, in the same order, sixteen in total.
 *
 * <p><strong>Every value is exactly five characters and the padding is data.</strong> The two
 * program-attention identifiers are three characters followed by two spaces in the legacy literal itself.
 * {@link #getAid()} returns and {@link #fromAid(String)} matches that exact padded form - no white-space
 * normalization, no case folding - because the value originates in a fixed-width work area where trailing
 * spaces are real; a lookup keyed on a trimmed form would silently fail to resolve those two.
 *
 * <p><strong>There is deliberately no default constant.</strong> The legacy mapping in
 * {@code app/cpy/CSSTRPFY.cpy} has no catch-all: an unrecognised identifier causes no assignment, so the
 * work-area field keeps the value it already held. An unknown or default constant would manufacture a
 * state the legacy system cannot produce; absence is modelled by the empty {@link Optional} from
 * {@link #fromAid(String)} instead (decision-log D-20). For the same reason keys 13 through 24 have no
 * constants: the legacy mapping folds them onto the same twelve flags as keys 1 through 12, and
 * performing that fold belongs to the utility-layer key translator rather than to this enum.
 */
public enum KeyAction {

    ENTER("ENTER"),

    CLEAR("CLEAR"),

    /** Program-attention key 1; the identifier is padded to the five-character field width. */
    PA1("PA1  "),

    /** Program-attention key 2; the identifier is padded to the five-character field width. */
    PA2("PA2  "),

    PFK01("PFK01"),

    PFK02("PFK02"),

    PFK03("PFK03"),

    PFK04("PFK04"),

    PFK05("PFK05"),

    PFK06("PFK06"),

    PFK07("PFK07"),

    PFK08("PFK08"),

    PFK09("PFK09"),

    PFK10("PFK10"),

    PFK11("PFK11"),

    PFK12("PFK12");

    private static final Map<String, KeyAction> BY_AID = indexByAid();

    /** The exact five-character identifier, trailing padding included. */
    private final String aid;

    KeyAction(String aid) {
        this.aid = aid;
    }

    public String getAid() {
        return aid;
    }

    /**
     * Resolves an identifier read from the fixed-width work area to its constant. Matching is exact, so a
     * {@code null} argument, any other width and any undeclared value all yield an empty result - which
     * models the legacy no-default behaviour: on an empty result a caller retains whatever key action was
     * already in effect rather than substituting a placeholder.
     *
     * @param aid the identifier exactly as read from the work area; may be {@code null}
     * @return the matching constant, or an empty {@link Optional}
     */
    public static Optional<KeyAction> fromAid(String aid) {
        if (aid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_AID.get(aid));
    }

    /**
     * Distinguishes the twelve program-function keys from ENTER, CLEAR and the two program-attention keys.
     * The switch covers every constant, so adding one without extending it fails compilation rather than
     * falling through to a silently wrong answer.
     *
     * @return {@code true} for the twelve program-function keys
     */
    public boolean isProgramFunctionKey() {
        return switch (this) {
            case ENTER, CLEAR, PA1, PA2 -> false;
            case PFK01, PFK02, PFK03, PFK04, PFK05, PFK06,
                 PFK07, PFK08, PFK09, PFK10, PFK11, PFK12 -> true;
        };
    }

    private static Map<String, KeyAction> indexByAid() {
        Map<String, KeyAction> index = new LinkedHashMap<>();
        for (KeyAction action : values()) {
            index.put(action.aid, action);
        }
        return Collections.unmodifiableMap(index);
    }
}
