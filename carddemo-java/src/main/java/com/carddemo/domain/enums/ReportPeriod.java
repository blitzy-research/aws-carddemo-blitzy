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
 * The transaction-report period selector - {@code Monthly}, {@code Yearly} or {@code Custom} -
 * translated from the CardDemo report-request program {@code CORPT00C} (legacy CICS transaction
 * {@code CR00}). That program assigns one of these three literals to its report-name work field
 * {@code WS-REPORT-NAME} according to which report type the operator selected, and the selected
 * literal then appears in the text the operator reads back.
 *
 * <p><strong>Why these literals are bare rather than space padded.</strong> The legacy receiving
 * field is declared {@code PIC X(10)} and initialised to spaces (member {@code CORPT00C}, line
 * 58). On its own that would argue for padding every value out to ten characters, and three
 * sibling enums in this package do pad for exactly that reason. This enum deliberately differs,
 * and the divergence is the conclusion of tracing every one of the six references the program
 * makes to that field:
 * <ul>
 *   <li>line 58 - the declaration, ten characters wide, initialised to spaces;</li>
 *   <li>lines 214, 240 and 433 - the only three writes, assigning the three literals below;</li>
 *   <li>line 449 - a read that assembles the submission acknowledgement shown to the operator,
 *       consuming the field delimited by space;</li>
 *   <li>line 468 - the only other read, inside the job-submission paragraph, assembling the
 *       confirmation prompt and likewise consuming the field delimited by space.</li>
 * </ul>
 * Two facts follow, and together they settle the question. First, the field is write-only as a
 * discriminator: the program never compares it against a literal, so no branch anywhere depends
 * on its width. Second, both of its two read sites consume it delimited by space, so assembly
 * stops at the first space and the trailing padding is discarded before it can reach any output.
 * The padding is therefore an artefact of the fixed-width work field rather than part of the
 * contract, and the bare literal - seven, six and six characters - is the semantically
 * meaningful form. Do not "correct" these values by padding them to ten characters: that would
 * emit trailing spaces the legacy program never produced.
 *
 * <p><strong>The mixed casing is equally load bearing.</strong> A capital initial letter followed
 * by a lowercase remainder is exactly what the legacy program moves into the work field, and
 * therefore exactly what the operator sees. Deriving the value from the enum constant name - the
 * natural Java shortcut - would yield all-uppercase text and change observable output, so each
 * constant carries its literal explicitly and no case folding and no whitespace normalisation
 * are applied anywhere in this type.
 *
 * <p><strong>Scope.</strong> This enum names the three periods and stops there. Which period
 * applies is decided by the report-request service layer, whose ordered evaluation of the screen
 * options - monthly tested first, yearly second, custom third, followed by a catch-all for the
 * case where no report type was supplied - is that layer's contract and not this type's. The
 * custom period's start-and-end date pair, the error-flag guard that precedes submission, the
 * operator confirmation gate and the job submission itself all likewise belong to the service and
 * utility layers. The catch-all branch is an input-validation outcome that yields an error
 * message rather than a fourth report type, which is why {@link #fromValue(String)} models an
 * unrecognised value as an empty result instead of as a synthetic constant.
 *
 * <p>This is a pure value type: no framework annotation, no persistence mapping and no schema
 * binding. It models a transient request selector, not stored data. Instances are immutable and
 * the type is therefore safe to share across threads.
 */
public enum ReportPeriod {

    /**
     * Month-to-date report period. Carries the literal {@code Monthly} (seven characters),
     * assigned at member {@code CORPT00C} line 214.
     */
    MONTHLY("Monthly"),

    /**
     * Year-to-date report period. Carries the literal {@code Yearly} (six characters), assigned
     * at member {@code CORPT00C} line 240.
     */
    YEARLY("Yearly"),

    /**
     * Operator-supplied date-range report period. Carries the literal {@code Custom} (six
     * characters), assigned at member {@code CORPT00C} line 433. The date range that accompanies
     * this period in the legacy program is not modelled here; it belongs to the service layer
     * that builds the submitted job stream.
     */
    CUSTOM("Custom");

    /**
     * Immutable reverse index from the legacy literal to the constant that carries it, built once
     * during class initialisation. Enum constants are initialised before any subsequent static
     * initialiser runs, so each constant's own literal is the key and no string is duplicated
     * here - a duplicated literal could drift away from the constant it indexes.
     */
    private static final Map<String, ReportPeriod> BY_VALUE = Map.of(
            MONTHLY.value, MONTHLY,
            YEARLY.value, YEARLY,
            CUSTOM.value, CUSTOM);

    /** The legacy literal exactly as the report-request program writes it, bare and unpadded. */
    private final String value;

    /**
     * Binds a constant to the legacy literal it carries.
     *
     * @param value the exact legacy literal, supplied bare and already in its contractual casing
     */
    ReportPeriod(String value) {
        this.value = value;
    }

    /**
     * Returns the legacy literal this period is represented by, exactly as the report-request
     * program writes it into its report-name work field: mixed case, with a capital initial
     * letter, and bare rather than padded to the ten characters of the legacy field.
     *
     * @return the exact legacy literal, never {@code null} and never blank
     */
    public String getValue() {
        return value;
    }

    /**
     * Resolves a raw legacy literal to its period, tolerating an unmapped value rather than
     * failing on one.
     *
     * <p>An unrecognised or absent value is a legitimate input state: the legacy program reaches
     * its catch-all branch when the operator selected no report type at all, and that outcome is
     * an error message produced by the service layer rather than a report type in its own right.
     * This method therefore reports absence explicitly and never throws, so callers decide how to
     * handle a miss. Matching is exact - the argument is compared as supplied, with no case
     * folding and no whitespace normalisation - because the mixed casing of each literal is part
     * of the contract.
     *
     * @param value the raw literal to resolve; may be {@code null}
     * @return the matching period, or an empty {@link Optional} if {@code value} is {@code null}
     *         or matches none of the three literals
     */
    public static Optional<ReportPeriod> fromValue(String value) {
        // The reverse index rejects a null probe, so the null case is answered before the lookup.
        if (value == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_VALUE.get(value));
    }
}
