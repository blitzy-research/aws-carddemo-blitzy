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
 * Seals the ordered account and card identifiers displayed by the seven-row card list and resolves a
 * marked row against that immutable snapshot.
 *
 * <h2>Why the snapshot exists</h2>
 *
 * <p>{@code app/cbl/COCRDLIC.cbl} keeps the seven displayed rows in its own program communication area,
 * not in a screen buffer it forgets. Lines 250 to 260 declare a 196-character row table - 28 characters
 * by seven rows - inside {@code WS-THIS-PROGCOMMAREA}, each row carrying an 11-character account number,
 * a 16-character card number and a 1-character status, and {@code COMMON-RETURN} at lines 604 to 619
 * concatenates that whole private area behind the shared one before returning both. The next turn
 * therefore begins holding the very rows the operator was looking at, which is why the two selection
 * transfers at lines 520 to 541 and 548 to 569 can move {@code WS-ROW-ACCTNO(I-SELECTED)} and
 * {@code WS-ROW-CARD-NUM(I-SELECTED)} straight into the shared account and card fields at lines 531 to
 * 534 and 559 to 562 <strong>without reading the file again</strong>.
 *
 * <p>A REST turn has no equivalent private area, and the two available substitutes are both wrong. Asking
 * the client to echo eleven-character account numbers and sixteen-character card numbers back would put
 * cardholder data on the request side of the contract and let a caller nominate a row that was never
 * displayed to it. Re-deriving the page from the echoed browse cursor instead would reintroduce the race
 * the private area does not have: the marker names a <em>position</em> on a page the operator is looking
 * at, and an insert or delete anywhere at or before that page's anchor moves every later row up or down
 * by one, so the re-read would hand the following screen a different card than the one standing in the
 * marked row.
 *
 * <p>This service restores the legacy property without server-side session state and without disclosing a
 * row identity to the caller: the response carries one authenticated, encrypted token over the ordered
 * row identities, and the next request resolves the marked position only from that token.
 *
 * <h2>Shape of the payload</h2>
 *
 * <p>The scheme tag and the row count are explicit, so a future incompatible format and a token minted
 * for another screen both fail closed rather than being read as a short page. Each identifier is URL-safe
 * Base64 inside the protected payload, which keeps every storable character - the payload separator
 * included - unambiguous, and the re-encoding check rejects a non-canonical encoding rather than
 * accepting two spellings of one row. Both widths are the persisted key widths of the estate and are
 * enforced on the way in and on the way out.
 *
 * <p>Confidentiality and integrity both come from {@link SensitiveFieldEncryptionService}, bound to this
 * service's own field name, so a token minted for the user list cannot be opened here and the identities
 * inside a card-list token are never legible to the holder. No token, and no identifier, is ever logged.
 *
 * <p>The service is stateless and thread-safe. It queries no repository, compares no live page and
 * retains nothing after the call returns.
 *
 * <p>Recorded as decision {@code DL-325} in {@code docs/decision-log.md}.
 *
 * @since 1.0.0
 */
@Service
public class CardListPageTokenService {

    /** Binding sealed into every card-list page token. */
    public static final String PAGE_TOKEN_FIELD = "card_list.page_token";

    /** Current payload scheme, so a future incompatible format fails closed. */
    public static final String PAYLOAD_SCHEME = "CCLIP1";

    /**
     * The card-list screen has exactly seven selectable rows.
     *
     * <p>Declared here against the row table of {@code app/cbl/COCRDLIC.cbl} lines 250 to 260 rather than
     * shared with the transaction-list and user-list snapshots, which are ten-row screens. One constant
     * for two disagreeing screen shapes would be one name for two unrelated numbers.
     */
    public static final int MAX_PAGE_ROWS = 7;

    /** Width of the account identifier a displayed row carries, from line 258. */
    public static final int ACCOUNT_ID_WIDTH = 11;

    /** Width of the card number a displayed row carries, from line 259. */
    public static final int CARD_NUMBER_WIDTH = 16;

    private static final char UNIT_SEPARATOR = '\u001F';
    private static final int HEADER_PART_COUNT = 2;
    private static final int SCHEME_PART = 0;
    private static final int COUNT_PART = 1;
    private static final int PARTS_PER_ROW = 2;

    private final SensitiveFieldEncryptionService fieldEncryption;

    /**
     * Creates the page-token service.
     *
     * @param fieldEncryption the authenticated-encryption service; must not be {@code null}
     */
    public CardListPageTokenService(final SensitiveFieldEncryptionService fieldEncryption) {
        this.fieldEncryption = Objects.requireNonNull(fieldEncryption,
                "fieldEncryption must not be null");
    }

    /**
     * One displayed row's identity: the pair the two selection transfers move into the shared area.
     *
     * <p>The status character of the third row item is deliberately absent. The transfers move the account
     * number and the card number and nothing else, so carrying the status would seal a value no consumer
     * of this snapshot reads.
     *
     * @param accountId  the eleven-character account identifier the row displayed
     * @param cardNumber the sixteen-character card number the row displayed
     */
    public record DisplayedCard(String accountId, String cardNumber) {

        /**
         * Rejects an identity that could not have occupied a displayed row.
         *
         * @throws NullPointerException     if either identifier is absent
         * @throws IllegalArgumentException if either identifier is not exactly its persisted key width
         */
        public DisplayedCard {
            requireWidth(accountId, ACCOUNT_ID_WIDTH, "account identifier");
            requireWidth(cardNumber, CARD_NUMBER_WIDTH, "card number");
        }

        /**
         * Returns a diagnostic representation that discloses neither identifier.
         *
         * <p>The generated record rendering would print both, and both are regulated: the card number is a
         * primary account number and the account identifier joins straight to a cardholder. A snapshot
         * holds up to seven of these pairs, so one stringified instance is the densest disclosure this
         * class could produce. Withholding is confined to this method - both accessors return their
         * component untouched.
         *
         * @return the row layout with both identifiers replaced by a fixed placeholder
         */
        @Override
        public String toString() {
            return "DisplayedCard[accountId=***REDACTED***, cardNumber=***REDACTED***]";
        }
    }

    /**
     * Mints a token over the displayed identities in their screen-row order.
     *
     * @param displayedRows the displayed identities, ordered from row one downward
     * @return an authenticated token, never {@code null} or blank
     * @throws NullPointerException     if the list or one of its entries is {@code null}
     * @throws IllegalArgumentException if the page carries more rows than the screen has slots
     */
    public String mint(final List<DisplayedCard> displayedRows) {
        Objects.requireNonNull(displayedRows, "displayedRows must not be null");
        requirePageSize(displayedRows.size());

        final StringBuilder payload = new StringBuilder(32 + displayedRows.size() * 48);
        payload.append(PAYLOAD_SCHEME).append(UNIT_SEPARATOR).append(displayedRows.size());
        for (final DisplayedCard row : displayedRows) {
            Objects.requireNonNull(row, "a displayed row must not be null");
            payload.append(UNIT_SEPARATOR).append(encode(row.accountId()))
                    .append(UNIT_SEPARATOR).append(encode(row.cardNumber()));
        }
        return this.fieldEncryption.protect(PAGE_TOKEN_FIELD, payload.toString());
    }

    /**
     * Resolves a one-based screen-row position from the protected snapshot.
     *
     * @param token            the token echoed from the list response
     * @param oneBasedPosition the marked row position, starting at one
     * @return the identity that occupied that displayed row, or empty when the token describes a shorter
     *         page
     * @throws IllegalArgumentException if the position is non-positive or outside the seven the screen
     *                                  has, or if the token is absent, unauthenticated, malformed or
     *                                  foreign
     */
    public Optional<DisplayedCard> resolve(final String token, final int oneBasedPosition) {
        if (oneBasedPosition < 1 || oneBasedPosition > MAX_PAGE_ROWS) {
            throw new IllegalArgumentException("oneBasedPosition must be between one and "
                    + MAX_PAGE_ROWS);
        }
        final List<DisplayedCard> displayedRows = verify(token);
        if (oneBasedPosition > displayedRows.size()) {
            return Optional.empty();
        }
        return Optional.of(displayedRows.get(oneBasedPosition - 1));
    }

    /**
     * Opens and validates a page token.
     *
     * @param  token the token to verify
     * @return an immutable ordered snapshot of the displayed identities
     */
    private List<DisplayedCard> verify(final String token) {
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

        final int rowCount;
        try {
            rowCount = Integer.parseInt(parts[COUNT_PART]);
        } catch (NumberFormatException rejected) {
            throw invalidToken("the page token carries an unreadable row count");
        }
        requirePageSize(rowCount);
        if (parts.length != HEADER_PART_COUNT + rowCount * PARTS_PER_ROW) {
            throw invalidToken("the page token row count does not match its payload");
        }

        final List<DisplayedCard> displayedRows = new ArrayList<>(rowCount);
        for (int index = HEADER_PART_COUNT; index < parts.length; index += PARTS_PER_ROW) {
            final String accountId = decodeCanonical(parts[index]);
            final String cardNumber = decodeCanonical(parts[index + 1]);
            try {
                displayedRows.add(new DisplayedCard(accountId, cardNumber));
            } catch (NullPointerException | IllegalArgumentException rejected) {
                // A width the row table could not have held. From the caller's side this is the same
                // event as an unauthenticated token - the snapshot is not one this server minted - so it
                // takes the same closed refusal rather than a distinguishable one.
                throw invalidToken("the page token carries an identifier of the wrong width");
            }
        }
        return List.copyOf(displayedRows);
    }

    private static void requirePageSize(final int rowCount) {
        if (rowCount < 0 || rowCount > MAX_PAGE_ROWS) {
            throw new IllegalArgumentException("a card-list page must contain between zero and "
                    + MAX_PAGE_ROWS + " rows");
        }
    }

    private static void requireWidth(final String value, final int width, final String what) {
        Objects.requireNonNull(value, "a displayed " + what + " must not be null");
        if (value.length() != width) {
            throw new IllegalArgumentException("a displayed " + what + " must be exactly " + width
                    + " characters");
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
        return new IllegalArgumentException("Invalid card-list page token: " + reason);
    }
}
