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

import com.carddemo.domain.enums.KeyAction;
import java.util.List;

/**
 * One transmitted user-administration screen, as a type the service layer owns.
 *
 * <p>One record body serves all four operations, exactly as the wire request does, because the four maps
 * {@code app/cpy-bms/COUSR00.CPY} through {@code COUSR03.CPY} declare overlapping but different input
 * items and the four legacy programs read them from one shared working storage. A component an operation's
 * map does not declare is simply absent on that operation.
 *
 * <p><strong>Why this exists beside {@code api.dto.UserRequest}.</strong> The module's layering runs one
 * way - the API layer may depend on the service layer, and nothing may depend upward - so a service that
 * named the wire record as a parameter inverted that direction. This is the same twelve components under
 * the same names, with two differences and only two: the echoed communication area arrives as
 * {@link ScreenNavigationState} rather than as the wire record, and no width bound or operation-group
 * constraint is declared here, because the wire record already applied its own and the ordered emptiness
 * cascade of the four legacy programs applies the rest. {@code api.UserContractAdapter} is the only place
 * the two are converted.
 *
 * <p><strong>Widths are not enforced here, and that is the legacy behaviour rather than an
 * omission.</strong> A 3270 field transmits whatever the operator typed, so a blank or part-typed
 * identifier is a value this type must be able to carry: the legacy programs answer it with a field-level
 * screen message rather than refusing the transmission. Exact width is enforced where a short value could
 * do damage - {@code com.carddemo.domain.UserSecurity} refuses anything but eight characters before an
 * insert or an update, and {@code V1__create_schema.sql} carries the same rule as a check constraint.
 *
 * @param userId the eight-character user-identifier item of the add, update and delete maps
 * @param searchUserId the eight-character browse start key of the list map, where a blank value is
 *     meaningful and is therefore kept distinct from {@code userId}
 * @param firstName the twenty-character first-name item
 * @param lastName the twenty-character last-name item
 * @param password the eight-character credential item, which only the add and update maps declare
 * @param userType the one-character type item, carried as transmitted because no program tests its value
 * @param rowSelections the ten positional per-row selection items of the list map, in row order;
 *     normalised to an unmodifiable list, empty when the operation carries none
 * @param displayedPageNumber the eight-character displayed page number, text rather than a number so the
 *     leading zeros the screen showed survive
 * @param firstUserIdOnPage the retained first key of the displayed page, which positions a backward page
 * @param lastUserIdOnPage the retained last key of the displayed page, which positions a forward page
 * @param rowSnapshotToken authenticated snapshot of the identifiers displayed on the submitted page
 * @param keyAction the resolved attention key, or {@code null} when the transmitted identifier resolved to
 *     none, which is a reachable state because the legacy key mapping declares no catch-all
 * @param navigationContext the echoed communication area, or {@code null} when the turn echoed none
 * @since 1.0.0
 */
public record UserCommand(
        String userId,
        String searchUserId,
        String firstName,
        String lastName,
        String password,
        String userType,
        List<String> rowSelections,
        String displayedPageNumber,
        String firstUserIdOnPage,
        String lastUserIdOnPage,
        String rowSnapshotToken,
        KeyAction keyAction,
        ScreenNavigationState navigationContext) {

    /**
     * The number of per-row selection items the list map declares, from the ten row groups of
     * {@code app/cpy-bms/COUSR00.CPY}.
     */
    public static final int ROW_SELECTION_COUNT = 10;

    /** Fixed stand-in this command's rendering uses in place of every personal and credential value. */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Normalises the selection column and refuses one longer than the screen has rows.
     *
     * <p>An absent collection becomes an empty one, so emptiness is the single representation of "carries
     * nothing" and a caller never has to distinguish it from absence. The cardinality check is a refusal
     * rather than a truncation: an eleventh selection corresponds to no row on a screen that declares ten,
     * so silently dropping it would let a caller believe a row was acted on.
     *
     * @throws IllegalArgumentException if the selection column carries more than
     *     {@value #ROW_SELECTION_COUNT} entries
     * @throws NullPointerException if the selection column holds a {@code null} element
     */
    public UserCommand {
        rowSelections = (rowSelections == null) ? List.of() : List.copyOf(rowSelections);
        if (rowSelections.size() > ROW_SELECTION_COUNT) {
            throw new IllegalArgumentException("rowSelections must carry at most "
                    + ROW_SELECTION_COUNT
                    + " entries, because the list screen declares that many rows, but it carries "
                    + rowSelections.size());
        }
    }

    /**
     * Compatibility constructor for non-list operations and tests that predate the displayed-row token.
     */
    public UserCommand(final String userId, final String searchUserId, final String firstName,
            final String lastName, final String password, final String userType,
            final List<String> rowSelections, final String displayedPageNumber,
            final String firstUserIdOnPage, final String lastUserIdOnPage,
            final KeyAction keyAction, final ScreenNavigationState navigationContext) {
        this(userId, searchUserId, firstName, lastName, password, userType, rowSelections,
                displayedPageNumber, firstUserIdOnPage, lastUserIdOnPage, null, keyAction,
                navigationContext);
    }

    /**
     * Renders the command without any personal or credential value.
     *
     * <p>Six of the twelve components are personal data or a credential, and three more are identifiers,
     * so every one of them is replaced. The selection count is rendered rather than the selections, which
     * discloses the shape of the submission without disclosing which rows an operator marked.
     *
     * <p>{@code equals} and {@code hashCode} are left as the record contract generates them: they compare
     * every component by value, which is what a parity comparison needs, and neither emits anything.
     *
     * @return the type name, the control state, and a fixed placeholder in place of every value
     */
    @Override
    public String toString() {
        return "UserCommand["
                + "userId=" + REDACTION_PLACEHOLDER
                + ", searchUserId=" + REDACTION_PLACEHOLDER
                + ", firstName=" + REDACTION_PLACEHOLDER
                + ", lastName=" + REDACTION_PLACEHOLDER
                + ", password=" + REDACTION_PLACEHOLDER
                + ", userType=" + REDACTION_PLACEHOLDER
                + ", rowSelectionCount=" + rowSelections.size()
                + ", displayedPageNumber=" + displayedPageNumber
                + ", firstUserIdOnPage=" + REDACTION_PLACEHOLDER
                + ", lastUserIdOnPage=" + REDACTION_PLACEHOLDER
                + ", rowSnapshotToken=" + REDACTION_PLACEHOLDER
                + ", keyAction=" + keyAction
                + ", navigationContext=" + navigationContext
                + "]";
    }
}
