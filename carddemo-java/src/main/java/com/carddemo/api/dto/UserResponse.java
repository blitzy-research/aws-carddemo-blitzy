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
package com.carddemo.api.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Immutable response shared by the four administrative user transactions, reproducing the field-level
 * and message-level contract of their four legacy screens as one payload. One type serves all four
 * because all four are views of the same eighty-byte user-security record
 * ({@code app/cpy/CSUSR01Y.cpy}) and differ only in the subset they present; only the list screen
 * returns rows, so the row list is empty for the other three. Authorisation is not modelled here - the
 * route-to-role table belongs to the security configuration.
 *
 * <p>Two failure arms deliberately carry a message without raising the error flag, because the legacy
 * programs emit the text and leave the switch clear; a caller must therefore treat message and flag as
 * independent rather than inferring one from the other.
 *
 * <p>Success-path field clearing is asymmetric: the legacy screens blank some echoed values after a
 * successful operation and retain others, so no echoed component may be declared mandatory here.
 *
 * <p>The user type crosses this boundary as raw text rather than an enum, and identifiers and page
 * indicators are text rather than numbers, so leading zeros and blank padding survive exactly as the
 * screens carried them. Paging is cursor-based and this type performs no paging arithmetic.
 *
 * <p>Field-level errors reuse the shared two-state error contract - missing versus invalid - and no
 * diagnostic, stack trace or internal identifier ever reaches this payload. The credential is never a
 * component of any response in this package.
 */
public record UserResponse(
        List<UserRow> rows,
        PageMetadata pageMetadata,
        @Size(max = UserResponse.USER_ID_LENGTH) String userId,
        @Size(max = UserResponse.FIRST_NAME_LENGTH) String firstName,
        @Size(max = UserResponse.LAST_NAME_LENGTH) String lastName,
        @Size(max = UserResponse.USER_TYPE_LENGTH) String userType,
        @Size(max = UserResponse.TRANSACTION_NAME_LENGTH) String transactionName,
        @Size(max = UserResponse.SCREEN_TITLE_LENGTH) String title01,
        @Size(max = UserResponse.CURRENT_DATE_LENGTH) String currentDate,
        @Size(max = UserResponse.PROGRAM_NAME_LENGTH) String programName,
        @Size(max = UserResponse.SCREEN_TITLE_LENGTH) String title02,
        @Size(max = UserResponse.CURRENT_TIME_LENGTH) String currentTime,
        @Size(max = UserResponse.MESSAGE_LENGTH) String message,
        List<ErrorResponse.FieldError> fieldErrors,
        boolean generalError,
        boolean actionSucceeded,
        @Size(max = UserResponse.SCREEN_FIELD_ID_LENGTH) String focusScreenFieldId,
        String nextRoute,
        NavigationContext navigationContext) {
    public static final int SELECTOR_LENGTH = 1;

    /**
     * Fixed text substituted for every withheld value in {@link #toString()}.
     *
     * <p>A constant rather than a computed mask, so a rendering never varies with the value it hides.
     * A mask derived from the value - its length, its first character, a hash - would leak exactly the
     * attribute the redaction exists to remove, and a variable-length mask would let a reader infer a
     * field's width from the rendering.</p>
     *
     * <p>Private because it is a diagnostic detail of this type rather than part of the wire contract.
     * It never appears in a serialized payload: {@link #toString()} is not a serialization path, and
     * no component ever holds this text.</p>
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    public static final int USER_ID_LENGTH = 8;

    public static final int FIRST_NAME_LENGTH = 20;

    public static final int LAST_NAME_LENGTH = 20;

    public static final int USER_TYPE_LENGTH = 1;

    public static final int TRANSACTION_NAME_LENGTH = 4;

    public static final int SCREEN_TITLE_LENGTH = 40;

    public static final int CURRENT_DATE_LENGTH = 8;

    public static final int PROGRAM_NAME_LENGTH = 8;

    public static final int CURRENT_TIME_LENGTH = 8;

    public static final int MESSAGE_LENGTH = 78;

    public static final int SCREEN_FIELD_ID_LENGTH = 7;

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

    public UserResponse {
        rows = (rows == null) ? List.of() : List.copyOf(rows);
        fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
    }

    public boolean hasRows() {
        return !rows.isEmpty();
    }

    public boolean hasFieldErrors() {
        return !fieldErrors.isEmpty();
    }

    public record UserRow(
            @Size(max = UserResponse.SELECTOR_LENGTH) String selector,
            @Size(max = UserResponse.USER_ID_LENGTH) String userId,
            @Size(max = UserResponse.FIRST_NAME_LENGTH) String firstName,
            @Size(max = UserResponse.LAST_NAME_LENGTH) String lastName,
            @Size(max = UserResponse.USER_TYPE_LENGTH) String userType) {
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

    @Override
    public String toString() {
        return "UserResponse["
                + "rowCount=" + rows.size()
                + ", rows=" + REDACTION_PLACEHOLDER
                + ", pageMetadata=" + REDACTION_PLACEHOLDER
                + ", userId=" + REDACTION_PLACEHOLDER
                + ", firstName=" + REDACTION_PLACEHOLDER
                + ", lastName=" + REDACTION_PLACEHOLDER
                + ", userType=" + REDACTION_PLACEHOLDER
                + ", transactionName=" + transactionName
                + ", title01=" + title01
                + ", currentDate=" + currentDate
                + ", programName=" + programName
                + ", title02=" + title02
                + ", currentTime=" + currentTime
                + ", message=" + message
                + ", fieldErrors=" + fieldErrors
                + ", generalError=" + generalError
                + ", actionSucceeded=" + actionSucceeded
                + ", focusScreenFieldId=" + focusScreenFieldId
                + ", nextRoute=" + nextRoute
                + ", navigationContext=" + navigationContext
                + "]";
    }
}
