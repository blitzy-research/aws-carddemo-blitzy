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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * Seals the ten transaction identifiers a transaction-list page displayed, each bound to the screen slot
 * it occupied, and resolves a marked slot against that immutable snapshot.
 *
 * <h2>Why the snapshot exists</h2>
 *
 * <p>{@code app/cbl/COTRN00C.cbl} pairs a marked row with an identifier it does not read from the file.
 * Each of the ten selection clauses at lines 150 to 178 moves the row's selector into the selection flag
 * and the identifier <em>the map is displaying on that same row</em> into the selected-record field, and
 * the transfer at lines 186 to 195 hands that identifier to the transaction-view program. The map on a
 * returning turn is what the terminal transmitted back, and on the mainframe the terminal is a device the
 * region itself painted a moment earlier over a private connection - so the value it returns is the value
 * the program sent.
 *
 * <p>Over HTTP neither substitute for that channel is sound. Accepting an identifier the submission
 * carries would let a caller mark slot three and name any sixteen-character identifier as the row
 * supposedly shown there. Re-deriving the page from the echoed browse cursor - the arrangement this
 * service replaces - reads the store a second time, so an insert, a delete or a posted transaction
 * between the display and the selection shifts the marked slot onto a different row or onto none: a
 * time-of-check to time-of-use gap the legacy screen does not have, because the legacy identifier is the
 * one already on the glass.
 *
 * <p>This service restores the legacy property without server-side session state: the response carries
 * one authenticated, encrypted token over the slot-to-identifier map of the page it just sent, and the
 * next request resolves the marked slot only from that token. The identifier a transfer receives is
 * therefore the identifier that stood in the marked slot when the operator marked it, whatever has
 * happened to the table since.
 *
 * <h2>Why slots, and not a count of rows</h2>
 *
 * <p>A snapshot of "the rows this page filled" would be ambiguous on this screen, because the two paging
 * paragraphs fill in opposite directions. The forward paragraph fills slot one downward, so a short
 * forward page leaves the <em>high</em> slots empty; the backward paragraph fills slot ten upward from
 * line 349, so a short backward page leaves the <em>low</em> slots empty and is bottom-aligned. The
 * payload therefore describes every one of the ten slots, with an empty entry for a slot the fill never
 * reached, and a marked empty slot resolves to nothing - which is exactly what the legacy catch-all at
 * lines 180 to 181 leaves in the field, and what the selection dispatch at line 184 then declines to act
 * upon.
 *
 * <h2>Shape of the payload</h2>
 *
 * <p>The scheme tag and the slot count are explicit, so a future incompatible format and a token minted
 * for another screen both fail closed rather than being read as a short page. Each identifier is URL-safe
 * Base64 inside the protected payload, which keeps every storable character - the payload separator
 * included - unambiguous, and the re-encoding check rejects a non-canonical encoding rather than
 * accepting two spellings of one row. The identifier width is the persisted key width of the estate and
 * is enforced on the way in and on the way out.
 *
 * <p>Confidentiality and integrity both come from {@link SensitiveFieldEncryptionService}, bound to this
 * service's own field name, so a token minted for the card list or the user list cannot be opened here.
 * No token, and no identifier, is ever logged.
 *
 * <p>The service is stateless and thread-safe. It queries no repository, compares no live page and
 * retains nothing after the call returns.
 *
 * <p>Recorded as decision {@code DL-326} in {@code docs/decision-log.md}, which also records what it
 * supersedes.
 *
 * <p>Provenance: {@code app/cbl/COTRN00C.cbl} lines 146 to 204, 290 to 297 and 340 to 360, read as
 * read-only reference at commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL source text is transcribed.
 *
 * @since 1.0.0
 */
@Service
public class TransactionListPageTokenService {

    /** Binding sealed into every transaction-list page token. */
    public static final String PAGE_TOKEN_FIELD = "transaction_list.page_token";

    /** Current payload scheme, so a future incompatible format fails closed. */
    public static final String PAYLOAD_SCHEME = "CT00P1";

    /**
     * The transaction-list screen has exactly ten selectable rows.
     *
     * <p>Declared here against the loop bounds of {@code app/cbl/COTRN00C.cbl} lines 290 and 297 rather
     * than shared with the card-list snapshot, which is a seven-row screen. One constant for two
     * disagreeing screen shapes would be one name for two unrelated numbers.
     */
    public static final int MAX_PAGE_ROWS = 10;

    /** Width of the transaction identifier a displayed row carries: the persisted key width. */
    public static final int TRANSACTION_ID_WIDTH = 16;

    private static final char UNIT_SEPARATOR = '\u001F';
    private static final int HEADER_PART_COUNT = 2;
    private static final int SCHEME_PART = 0;
    private static final int COUNT_PART = 1;
    private static final String EMPTY_SLOT = "";

    private final SensitiveFieldEncryptionService fieldEncryption;

    /**
     * Creates the page-token service.
     *
     * @param fieldEncryption the authenticated-encryption service; must not be {@code null}
     */
    public TransactionListPageTokenService(final SensitiveFieldEncryptionService fieldEncryption) {
        this.fieldEncryption = Objects.requireNonNull(fieldEncryption,
                "fieldEncryption must not be null");
    }

    /**
     * Mints a token over the identifiers a page displayed, slot by slot.
     *
     * @param  slotIdentifiers exactly {@link #MAX_PAGE_ROWS} entries in ascending slot order, each the
     *                         identifier that slot displayed or {@code null} when the fill never reached
     *                         it
     * @return an authenticated token, never {@code null} or blank
     * @throws NullPointerException     if the list itself is {@code null}
     * @throws IllegalArgumentException if the list does not describe exactly the screen's slots, or if a
     *                                  present identifier is not exactly the persisted key width
     */
    public String mint(final List<String> slotIdentifiers) {
        Objects.requireNonNull(slotIdentifiers, "slotIdentifiers must not be null");
        if (slotIdentifiers.size() != MAX_PAGE_ROWS) {
            throw new IllegalArgumentException("slotIdentifiers must describe exactly "
                    + MAX_PAGE_ROWS + " screen slots, including the ones the page left empty");
        }

        final StringBuilder payload = new StringBuilder(32 + MAX_PAGE_ROWS * 32);
        payload.append(PAYLOAD_SCHEME).append(UNIT_SEPARATOR).append(MAX_PAGE_ROWS);
        for (final String identifier : slotIdentifiers) {
            payload.append(UNIT_SEPARATOR);
            if (identifier != null && !identifier.isBlank()) {
                requireWidth(identifier);
                payload.append(encode(identifier));
            }
        }
        return this.fieldEncryption.protect(PAGE_TOKEN_FIELD, payload.toString());
    }

    /**
     * Resolves a one-based screen slot from the protected snapshot.
     *
     * @param  token       the token echoed from the list response
     * @param  oneBasedSlot the marked slot, starting at one
     * @return the identifier that occupied that slot, or empty when the page displayed no row there
     * @throws IllegalArgumentException if the slot is outside the ten the screen has, or if the token is
     *                                  absent, unauthenticated, malformed or foreign
     */
    public Optional<String> resolve(final String token, final int oneBasedSlot) {
        if (oneBasedSlot < 1 || oneBasedSlot > MAX_PAGE_ROWS) {
            throw new IllegalArgumentException("oneBasedSlot must be between one and " + MAX_PAGE_ROWS);
        }
        final List<String> slots = verify(token);
        final String identifier = slots.get(oneBasedSlot - 1);
        return EMPTY_SLOT.equals(identifier) ? Optional.empty() : Optional.of(identifier);
    }

    /**
     * Opens and validates a page token.
     *
     * @param  token the token to verify
     * @return the slot-indexed identifiers, an empty string standing for a slot the page left empty
     */
    private List<String> verify(final String token) {
        if (token == null || token.isBlank()) {
            throw invalidToken("no page token was presented");
        }

        final String payload;
        try {
            payload = this.fieldEncryption.reveal(PAGE_TOKEN_FIELD, token);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            throw invalidToken("the page token could not be authenticated");
        }

        final String[] parts = payload.split(String.valueOf(UNIT_SEPARATOR), -1);
        if (parts.length < HEADER_PART_COUNT || !PAYLOAD_SCHEME.equals(parts[SCHEME_PART])) {
            throw invalidToken("the page token carries an unrecognised payload");
        }

        final int slotCount;
        try {
            slotCount = Integer.parseInt(parts[COUNT_PART]);
        } catch (final NumberFormatException rejected) {
            throw invalidToken("the page token carries an unreadable slot count");
        }
        if (slotCount != MAX_PAGE_ROWS || parts.length != HEADER_PART_COUNT + MAX_PAGE_ROWS) {
            throw invalidToken("the page token does not describe this screen's slots");
        }

        final List<String> slots = new ArrayList<>(MAX_PAGE_ROWS);
        for (int index = HEADER_PART_COUNT; index < parts.length; index++) {
            final String part = parts[index];
            if (part.isEmpty()) {
                slots.add(EMPTY_SLOT);
                continue;
            }
            final String identifier = decodeCanonical(part);
            try {
                requireWidth(identifier);
            } catch (final IllegalArgumentException rejected) {
                // A width the row table could not have held. From the caller's side this is the same
                // event as an unauthenticated token - the snapshot is not one this server minted - so it
                // takes the same closed refusal rather than a distinguishable one.
                throw invalidToken("the page token carries an identifier of the wrong width");
            }
            slots.add(identifier);
        }
        return Collections.unmodifiableList(slots);
    }

    private static void requireWidth(final String identifier) {
        if (identifier.length() != TRANSACTION_ID_WIDTH) {
            throw new IllegalArgumentException("a displayed transaction identifier must be exactly "
                    + TRANSACTION_ID_WIDTH + " characters");
        }
    }

    private static String encode(final String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes one payload part and insists it was canonically encoded.
     *
     * <p>The re-encoding check is what stops two spellings of one identifier being accepted. It refuses
     * closed, exactly as an unauthenticated token does, because from the caller's side the two are the
     * same event: the snapshot presented is not one this server minted.
     *
     * @param  part the payload part to decode
     * @return the decoded identifier
     */
    private static String decodeCanonical(final String part) {
        final String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException rejected) {
            throw invalidToken("the page token carries an unreadable identifier");
        }
        if (!encode(decoded).equals(part)) {
            throw invalidToken("the page token carries a non-canonical identifier");
        }
        return decoded;
    }

    private static IllegalArgumentException invalidToken(final String reason) {
        return new IllegalArgumentException("Invalid transaction-list page token: " + reason);
    }
}
