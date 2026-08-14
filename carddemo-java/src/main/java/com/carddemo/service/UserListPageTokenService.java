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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * Seals the ordered identifiers displayed by the ten-row administrative user list and resolves a
 * submitted row marker against that immutable snapshot.
 *
 * <p>{@code COUSR00C} receives the identifier embedded in each displayed row back from the terminal.
 * A REST client cannot be trusted to echo those identifiers directly, while deriving them again from
 * a mutable database page allows an intervening insert or delete to move a different identity into
 * the selected position. This service restores the terminal property without server-side session
 * state: the response carries an authenticated token over the ordered row identifiers, and the next
 * request resolves the selected position only from that token.
 *
 * <p>The token is encrypted and authenticated through the module's existing field-bound protection
 * service. Each identifier is URL-safe Base64 inside the protected payload, so every possible stored
 * character - including the payload separator - remains unambiguous. The scheme and row count are
 * explicit, malformed or foreign payloads fail closed, and no token or identifier is logged.
 *
 * <p>The service is stateless and thread-safe. It does not query the repository, compare a live page
 * or retain a token after the call returns.
 */
@Service
public class UserListPageTokenService {

    /** Binding sealed into every user-list page token. */
    public static final String PAGE_TOKEN_FIELD = "user_list.page_token";

    /** Current payload scheme, allowing a future incompatible format to fail closed. */
    public static final String PAYLOAD_SCHEME = "CU00P1";

    /** The administrative list screen has exactly ten selectable rows. */
    public static final int MAX_PAGE_ROWS = 10;

    /** Width of the persisted user-security record key. */
    public static final int USER_ID_WIDTH = 8;

    private static final char UNIT_SEPARATOR = '\u001F';
    private static final int HEADER_PART_COUNT = 2;
    private static final int SCHEME_PART = 0;
    private static final int COUNT_PART = 1;

    private final SensitiveFieldEncryptionService fieldEncryption;

    /**
     * Creates the page-token service.
     *
     * @param fieldEncryption the authenticated-encryption service; must not be {@code null}
     */
    public UserListPageTokenService(final SensitiveFieldEncryptionService fieldEncryption) {
        this.fieldEncryption = Objects.requireNonNull(fieldEncryption,
                "fieldEncryption must not be null");
    }

    /**
     * Mints a token over the identifiers in their displayed row order.
     *
     * @param displayedUserIds displayed identifiers, ordered from row one downward
     * @return an authenticated token, never {@code null} or blank
     * @throws NullPointerException if the list or an identifier is {@code null}
     * @throws IllegalArgumentException if the page is too large or an identifier is not exactly the
     *                                  persisted eight-character key width
     */
    public String mint(final List<String> displayedUserIds) {
        Objects.requireNonNull(displayedUserIds, "displayedUserIds must not be null");
        requirePageSize(displayedUserIds.size());

        final StringBuilder payload = new StringBuilder(32 + displayedUserIds.size() * 16);
        payload.append(PAYLOAD_SCHEME).append(UNIT_SEPARATOR).append(displayedUserIds.size());
        for (final String userId : displayedUserIds) {
            requireUserId(userId);
            payload.append(UNIT_SEPARATOR).append(encode(userId));
        }
        return fieldEncryption.protect(PAGE_TOKEN_FIELD, payload.toString());
    }

    /**
     * Resolves a one-based displayed row position from the protected snapshot.
     *
     * @param token token echoed from the list response
     * @param oneBasedPosition selected row position, starting at one
     * @return the identifier that occupied that displayed row, or empty when the token describes a
     *         shorter page
     * @throws IllegalArgumentException if the position is non-positive or the token is absent,
     *                                  unauthenticated, malformed or foreign
     */
    public Optional<String> resolve(final String token, final int oneBasedPosition) {
        if (oneBasedPosition < 1) {
            throw new IllegalArgumentException("oneBasedPosition must be at least one");
        }
        final List<String> displayedUserIds = verify(token);
        if (oneBasedPosition > displayedUserIds.size()) {
            return Optional.empty();
        }
        return Optional.of(displayedUserIds.get(oneBasedPosition - 1));
    }

    /**
     * Opens and validates a page token.
     *
     * @param token token to verify
     * @return an immutable ordered identifier snapshot
     */
    private List<String> verify(final String token) {
        if (token == null || token.isBlank()) {
            throw invalidToken("no page token was presented");
        }

        final String payload;
        try {
            payload = fieldEncryption.reveal(PAGE_TOKEN_FIELD, token);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            throw invalidToken("the page token could not be authenticated");
        }

        final String[] parts = payload.split(String.valueOf(UNIT_SEPARATOR), -1);
        if (parts.length < HEADER_PART_COUNT || !PAYLOAD_SCHEME.equals(parts[SCHEME_PART])) {
            throw invalidToken("the page token carries an unrecognised payload");
        }

        final int rowCount;
        try {
            rowCount = Integer.parseInt(parts[COUNT_PART]);
        } catch (NumberFormatException rejected) {
            throw invalidToken("the page token carries an unreadable row count");
        }
        requirePageSize(rowCount);
        if (parts.length != HEADER_PART_COUNT + rowCount) {
            throw invalidToken("the page token row count does not match its payload");
        }

        final List<String> displayedUserIds = new ArrayList<>(rowCount);
        for (int index = HEADER_PART_COUNT; index < parts.length; index++) {
            final String userId;
            try {
                userId = decode(parts[index]);
            } catch (IllegalArgumentException rejected) {
                throw invalidToken("the page token carries an unreadable identifier");
            }
            requireUserId(userId);
            if (!encode(userId).equals(parts[index])) {
                throw invalidToken("the page token carries a non-canonical identifier");
            }
            displayedUserIds.add(userId);
        }
        return List.copyOf(displayedUserIds);
    }

    private static void requirePageSize(final int rowCount) {
        if (rowCount < 0 || rowCount > MAX_PAGE_ROWS) {
            throw new IllegalArgumentException("a user-list page must contain between zero and "
                    + MAX_PAGE_ROWS + " rows");
        }
    }

    private static void requireUserId(final String userId) {
        Objects.requireNonNull(userId, "displayed user identifier must not be null");
        if (userId.length() != USER_ID_WIDTH) {
            throw new IllegalArgumentException("a displayed user identifier must be exactly "
                    + USER_ID_WIDTH + " characters");
        }
    }

    private static String encode(final String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(final String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static IllegalArgumentException invalidToken(final String reason) {
        return new IllegalArgumentException("Invalid user-list page token: " + reason);
    }
}
