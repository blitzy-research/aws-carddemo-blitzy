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

import com.carddemo.exception.ValidationException;
import java.util.List;

/**
 * One settled user-administration turn, as a type the service layer owns.
 *
 * <p>The page of rows and its browse window, then the four scalar record values the add, update and delete
 * screens present, then the six protected screen-metadata items, then the message, the findings and the
 * three control components. Nineteen components, in the order the wire response declares them.
 *
 * <p><strong>Why this exists beside {@code api.dto.UserResponse}.</strong> The module's layering runs one
 * way - the API layer may depend on the service layer, and nothing may depend upward - so a service that
 * returned the wire record inverted that direction. This is the same nineteen components under the same
 * names, with three differences and only three: the browse window is {@link BrowseWindow} rather than the
 * wire paging record, the echoed communication area is {@link ScreenNavigationState} rather than the wire
 * record, and the per-field findings are {@link ValidationException.FieldError} - the module's own
 * field-error vocabulary, which the exception layer owns and every other screen service already produces.
 * {@code api.UserContractAdapter} is the only place the two are converted.
 *
 * <p><strong>Why the message texts are declared here as well as on the wire record.</strong> They are the
 * legacy programs' own screen text, and this service is the only thing in the module that emits any of
 * them; the wire record declares them because they are part of its published contract, which a client and
 * its contract tests read. Neither declaration can reference the other without creating an edge the
 * layering forbids - {@code api.dto} may name nothing but {@code domain.enums} - so both declare, and
 * {@code CrossLayerConstantAgreementTest} asserts every pair is character-identical, which makes drift a
 * build failure rather than a latent parity defect.
 *
 * <p>Several texts are deliberately identical across operations and are still declared separately, because
 * each is a distinct message in a distinct legacy program: {@code MSG_ADD_USER_ID_EMPTY},
 * {@code MSG_UPDATE_USER_ID_EMPTY} and {@code MSG_DELETE_USER_ID_EMPTY} carry the same characters but come
 * from three programs, and collapsing them would lose the correspondence the traceability matrix records.
 *
 * <p>Provenance: {@code app/cbl/COUSR00C.cbl}, {@code COUSR01C.cbl}, {@code COUSR02C.cbl},
 * {@code COUSR03C.cbl} and their four mapsets, read as read-only reference at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No copybook or program text is transcribed.
 *
 * @param rows the page of rows in the order the browse settled them, at most
 *     {@link BrowseWindow#USER_LIST_PAGE_SIZE}; normalised to an unmodifiable list
 * @param pageMetadata the browse window the turn assembled, or {@code null} when the turn presented no page
 * @param rowSnapshotToken authenticated snapshot of the displayed row identifiers, or {@code null}
 * @param userId the eight-character user-identifier item the scalar screens present
 * @param firstName the twenty-character first-name item
 * @param lastName the twenty-character last-name item
 * @param userType the one-character type item
 * @param transactionName the four-character transaction-name item
 * @param title01 the forty-character first title item
 * @param currentDate the eight-character current-date item
 * @param programName the eight-character program-name item
 * @param title02 the forty-character second title item
 * @param currentTime the eight-character current-time item
 * @param message the seventy-eight-character screen message slot
 * @param fieldErrors the independent per-field findings, in the order the cascade reported them;
 *     normalised to an unmodifiable list, empty when the turn flagged no field
 * @param generalError the turn's own error switch, recorded rather than derived
 * @param actionSucceeded whether the turn completed the operation it was asked to perform
 * @param focusScreenFieldId the identity of the field the cursor returns to, at most seven characters
 * @param nextRoute the declarative next route, whose vocabulary the navigation service owns
 * @param navigationContext the communication area as the turn leaves it
 * @since 1.0.0
 */
public record UserOutcome(
        List<UserRow> rows,
        BrowseWindow pageMetadata,
        String rowSnapshotToken,
        String userId,
        String firstName,
        String lastName,
        String userType,
        String transactionName,
        String title01,
        String currentDate,
        String programName,
        String title02,
        String currentTime,
        String message,
        List<ValidationException.FieldError> fieldErrors,
        boolean generalError,
        boolean actionSucceeded,

        /* Whether the turn asks the operator's displayed page to be kept rather than rebuilt.
         *
         * The positive form of the legacy erase flag. SEND-ERASE-NO is set on exactly two arms of the
         * list program - already at the top at COUSR00C L250-L254 and already at the bottom at
         * L272-L276 - and both arms release the browse and return no rows. A response that carried
         * neither the rows nor this instruction would look identical to an empty page, so a client
         * would blank a screen the legacy overwrites in place and the operator would lose the ten rows
         * they were looking at. Every other arm rebuilds, and reports false. */
        boolean preserveDisplayedPage,

        String focusScreenFieldId,
        String nextRoute,
        ScreenNavigationState navigationContext) {

    /**
     * The screen message texts, one per legacy emission site, declared here and on the wire record alike.
     *
     * <p>Each is the exact text its legacy program moves into the message field. Several texts repeat
     * across operations and are still declared separately, because each belongs to a distinct program.
     */
    public static final String MSG_LIST_INVALID_SELECTION =
            "Invalid selection. Valid values are U and D";

    public static final String MSG_LIST_ALREADY_AT_TOP =
            "You are already at the top of the page...";

    public static final String MSG_LIST_ALREADY_AT_BOTTOM =
            "You are already at the bottom of the page...";

    public static final String MSG_LIST_AT_TOP =
            "You are at the top of the page...";

    public static final String MSG_LIST_REACHED_BOTTOM =
            "You have reached the bottom of the page...";

    public static final String MSG_LIST_REACHED_TOP =
            "You have reached the top of the page...";

    public static final String MSG_LIST_UNABLE_TO_LOOKUP_USER = "Unable to lookup User...";

    public static final String MSG_ADD_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    public static final String MSG_ADD_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    public static final String MSG_ADD_USER_ID_EMPTY = "User ID can NOT be empty...";

    public static final String MSG_ADD_CREDENTIAL_FIELD_EMPTY = "Password can NOT be empty...";

    public static final String MSG_ADD_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    public static final String MSG_ADD_SUCCESS_PREFIX = "User ";

    public static final String MSG_ADD_SUCCESS_SUFFIX = " has been added ...";

    public static final String MSG_ADD_USER_ID_ALREADY_EXIST = "User ID already exist...";

    public static final String MSG_ADD_UNABLE_TO_ADD_USER = "Unable to Add User...";

    public static final String MSG_UPDATE_USER_ID_EMPTY = "User ID can NOT be empty...";

    public static final String MSG_UPDATE_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    public static final String MSG_UPDATE_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    public static final String MSG_UPDATE_CREDENTIAL_FIELD_EMPTY = "Password can NOT be empty...";

    public static final String MSG_UPDATE_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    public static final String MSG_UPDATE_NO_CHANGE = "Please modify to update ...";

    public static final String MSG_UPDATE_PRESS_PF5 = "Press PF5 key to save your updates ...";

    public static final String MSG_UPDATE_USER_ID_NOT_FOUND = "User ID NOT found...";

    public static final String MSG_UPDATE_UNABLE_TO_LOOKUP_USER = "Unable to lookup User...";

    public static final String MSG_UPDATE_SUCCESS_PREFIX = "User ";

    public static final String MSG_UPDATE_SUCCESS_SUFFIX = " has been updated ...";

    public static final String MSG_UPDATE_UNABLE_TO_UPDATE_USER = "Unable to Update User...";

    public static final String MSG_DELETE_USER_ID_EMPTY = "User ID can NOT be empty...";

    public static final String MSG_DELETE_PRESS_PF5 = "Press PF5 key to delete this user ...";

    public static final String MSG_DELETE_USER_ID_NOT_FOUND = "User ID NOT found...";

    public static final String MSG_DELETE_UNABLE_TO_LOOKUP_USER = "Unable to lookup User...";

    public static final String MSG_DELETE_SUCCESS_PREFIX = "User ";

    public static final String MSG_DELETE_SUCCESS_SUFFIX = " has been deleted ...";

    public static final String MSG_DELETE_UNABLE_TO_UPDATE_USER = "Unable to Update User...";

    /** Fixed stand-in this outcome's rendering uses in place of every personal value. */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Normalises the two collections and refuses a page longer than the list screen can present.
     *
     * <p>Both collections become unmodifiable copies, and an absent collection becomes an empty one, so
     * emptiness is the single representation of "carries nothing". The two cardinality checks are refusals
     * rather than truncations: a row beyond the tenth corresponds to no slot on a screen that declares ten,
     * and a row beyond what the accompanying window declares as its page size would contradict the window
     * the same turn assembled.
     *
     * @throws IllegalArgumentException if the row list holds more entries than the list screen has rows, or
     *     more than the accompanying browse window declares as its page size
     * @throws NullPointerException if either collection holds a {@code null} element
     */
    public UserOutcome {
        rows = (rows == null) ? List.of() : List.copyOf(rows);
        fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
        if (rows.size() > BrowseWindow.USER_LIST_PAGE_SIZE) {
            throw new IllegalArgumentException("rows may hold at most "
                    + BrowseWindow.USER_LIST_PAGE_SIZE + " entries, because that is how many rows the"
                    + " user-list screen presents, but it holds " + rows.size());
        }
        if (pageMetadata != null && rows.size() > pageMetadata.pageSize()) {
            throw new IllegalArgumentException("rows may hold at most the " + pageMetadata.pageSize()
                    + " entries the accompanying browse window declares as its page size, but it"
                    + " holds " + rows.size());
        }
    }

    /**
     * Reports whether the turn presented a page of rows.
     *
     * @return {@code true} when at least one row is present
     */
    public boolean hasRows() {
        return !rows.isEmpty();
    }

    /**
     * Reports whether the turn flagged at least one field.
     *
     * <p>Separate from {@link #generalError()} on purpose: the error switch is the turn's own summary state
     * and is set on paths that flag no individual field at all, so neither answer implies the other.
     *
     * @return {@code true} when at least one field finding is present
     */
    public boolean hasFieldErrors() {
        return !fieldErrors.isEmpty();
    }

    /**
     * One row of the user-list page, as a type the service layer owns.
     *
     * <p>The five per-row items of {@code app/cpy-bms/COUSR00.CPY}: the operator's selection character
     * followed by the four values the row displays. The counterpart of {@code api.dto.UserResponse.UserRow},
     * component for component and name for name, without the width bounds the wire row declares.
     *
     * @param selector the one-character selection item as the turn leaves it
     * @param userId the eight-character identifier the row displays
     * @param firstName the twenty-character first name the row displays
     * @param lastName the twenty-character last name the row displays
     * @param userType the one-character type the row displays
     */
    public record UserRow(String selector, String userId, String firstName, String lastName,
                          String userType) {

        /**
         * Renders the row without any personal value.
         *
         * <p>The selection character is rendered because it is the operator's own action rather than data
         * about a person; the four displayed values are not.
         *
         * @return the row's selection state with a fixed placeholder in place of every displayed value
         */
        @Override
        public String toString() {
            return "UserRow["
                    + "selector=" + selector
                    + ", userId=" + REDACTION_PLACEHOLDER
                    + ", firstName=" + REDACTION_PLACEHOLDER
                    + ", lastName=" + REDACTION_PLACEHOLDER
                    + ", userType=" + REDACTION_PLACEHOLDER
                    + "]";
        }
    }

    /**
     * Renders the outcome's control state and none of its personal values.
     *
     * <p>The row count is rendered rather than the rows, and the finding count rather than the findings,
     * because a finding names a field and quotes a message and a message on these screens can carry the
     * identifier an operator typed. The message slot itself is rendered: every one of its texts is a fixed
     * literal declared on this type, except the two success texts, which surround an identifier - and those
     * two are the reason the message is rendered through the placeholder rather than directly.
     *
     * <p>{@code equals} and {@code hashCode} are left as the record contract generates them: they compare
     * every component by value, which is what a parity comparison needs, and neither emits anything.
     *
     * @return the type name, the control state, and a fixed placeholder in place of every value
     */
    @Override
    public String toString() {
        return "UserOutcome["
                + "rowCount=" + rows.size()
                + ", rows=" + REDACTION_PLACEHOLDER
                + ", pageMetadata=" + REDACTION_PLACEHOLDER
                + ", userId=" + REDACTION_PLACEHOLDER
                + ", firstName=" + REDACTION_PLACEHOLDER
                + ", lastName=" + REDACTION_PLACEHOLDER
                + ", userType=" + REDACTION_PLACEHOLDER
                + ", transactionName=" + transactionName
                + ", programName=" + programName
                + ", message=" + REDACTION_PLACEHOLDER
                + ", fieldErrorCount=" + fieldErrors.size()
                + ", generalError=" + generalError
                + ", actionSucceeded=" + actionSucceeded
                + ", focusScreenFieldId=" + focusScreenFieldId
                + ", nextRoute=" + nextRoute
                + ", navigationContext=" + navigationContext
                + "]";
    }
}
