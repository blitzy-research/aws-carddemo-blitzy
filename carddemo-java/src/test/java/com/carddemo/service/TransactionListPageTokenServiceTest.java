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
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.carddemo.util.SensitiveFieldCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Boundary tests for the authenticated transaction-list page snapshot.
 *
 * <p>What is being pinned is the property the legacy program gets from its terminal and a REST turn does
 * not: a marked screen slot resolves to the identifier that <em>stood in that slot when the page was
 * sent</em>. {@code app/cbl/COTRN00C.cbl} reads that identifier back off the map it had just painted, at
 * lines 150 to 178, so nothing between the display and the selection can move it. This snapshot is that
 * map, sealed so the holder can neither read it nor forge it.
 *
 * <p>Slots rather than a row count is the load-bearing shape here, and it is asserted directly. The two
 * paging paragraphs fill in opposite directions - forward from slot one downward, backward from slot ten
 * upward at line 349 - so a short page can be empty at either end, and a snapshot that carried only "the
 * rows this page filled" would shift a bottom-aligned page's slots by the number it did not fill.
 *
 * <p>Every refusal below is a closed refusal. There is no arm that accepts a token this server did not
 * mint, none that accepts a token minted for another screen or another deployment key, and none that
 * accepts a shifted, re-counted or re-encoded payload.
 */
@DisplayName("TransactionListPageTokenService - a marked slot stays the row that was displayed in it")
class TransactionListPageTokenServiceTest {

    private static final char UNIT_SEPARATOR = '\u001F';

    private static final String BASE64_KEY = Base64.getEncoder().encodeToString(
            "carddemo-tran-page-token-key-001".getBytes(StandardCharsets.UTF_8));

    private static final String OTHER_BASE64_KEY = Base64.getEncoder().encodeToString(
            "carddemo-tran-page-token-key-999".getBytes(StandardCharsets.UTF_8));

    private final SensitiveFieldEncryptionService encryption =
            new SensitiveFieldEncryptionService(BASE64_KEY);

    private final TransactionListPageTokenService service =
            new TransactionListPageTokenService(encryption);

    /**
     * A full ten-slot page in display order.
     *
     * <p>The locale is pinned on the numeric formatting deliberately. {@code String.format} without a
     * locale resolves the ambient one, and under a locale whose CLDR numbering system is not
     * {@code latn} the digits come out as characters the sixteen-character field could never have held -
     * still sixteen characters, but not sixteen ASCII bytes.
     *
     * @return the ten displayed identifiers, slot one first
     */
    private static List<String> fullPage() {
        final List<String> slots = new ArrayList<>();
        for (int ordinal = 1; ordinal <= TransactionListPageTokenService.MAX_PAGE_ROWS; ordinal++) {
            slots.add(identifier(ordinal));
        }
        return List.copyOf(slots);
    }

    private static String identifier(final int ordinal) {
        return String.format(Locale.ROOT, "%016d", ordinal);
    }

    @Test
    @DisplayName("a slot resolves to the identifier sealed at that slot")
    void resolvesTheDisplayedSlot() {
        final String token = service.mint(fullPage());

        assertThat(service.resolve(token, 1)).contains(identifier(1));
        assertThat(service.resolve(token, 4)).contains(identifier(4));
        assertThat(service.resolve(token, TransactionListPageTokenService.MAX_PAGE_ROWS))
                .contains(identifier(TransactionListPageTokenService.MAX_PAGE_ROWS));
    }

    @Test
    @DisplayName("no identifier is legible in the token, so publishing it to a client discloses nothing")
    void noIdentifierIsLegibleInTheToken() {
        final String token = service.mint(fullPage());

        assertThat(SensitiveFieldCodec.hasEnvelopeShape(token))
                .as("the published value is an authenticated envelope rather than text")
                .isTrue();
        assertThat(token).doesNotContain(identifier(1)).doesNotContain(identifier(10));
    }

    @Test
    @DisplayName("a bottom-aligned page keeps its empty low slots empty, which is the shape a short "
            + "backward fill leaves behind")
    void aBottomAlignedPageKeepsItsEmptyLowSlotsEmpty() {
        final List<String> slots = new ArrayList<>(
                Collections.nCopies(TransactionListPageTokenService.MAX_PAGE_ROWS, null));
        slots.set(6, identifier(1));
        slots.set(7, identifier(2));
        slots.set(8, identifier(3));
        slots.set(9, identifier(4));

        final String token = service.mint(slots);

        assertThat(service.resolve(token, 10)).contains(identifier(4));
        assertThat(service.resolve(token, 7)).contains(identifier(1));
        assertThat(service.resolve(token, 6))
                .as("the reverse fill never reached this slot, so marking it names no row")
                .isEmpty();
        assertThat(service.resolve(token, 1)).isEmpty();
    }

    @Test
    @DisplayName("an all-empty page round-trips and resolves no slot, so a page that displayed nothing "
            + "cannot be marked")
    void roundTripsAnAllEmptyPage() {
        final String token = service.mint(
                Collections.nCopies(TransactionListPageTokenService.MAX_PAGE_ROWS, null));

        for (int slot = 1; slot <= TransactionListPageTokenService.MAX_PAGE_ROWS; slot++) {
            assertThat(service.resolve(token, slot)).isEmpty();
        }
    }

    @Test
    @DisplayName("a blank slot entry is sealed as an empty slot rather than as a blank identifier")
    void aBlankSlotEntryIsSealedAsAnEmptySlot() {
        final List<String> slots = new ArrayList<>(fullPage());
        slots.set(2, "                ");

        final String token = service.mint(slots);

        assertThat(service.resolve(token, 3)).isEmpty();
        assertThat(service.resolve(token, 4)).contains(identifier(4));
    }

    @Test
    @DisplayName("the constructor requires the encryption service")
    void requiresTheEncryptionService() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TransactionListPageTokenService(null))
                .withMessageContaining("fieldEncryption");
    }

    @Test
    @DisplayName("mint requires the screen's own slot count, so a caller cannot seal a page shape this "
            + "screen never rendered")
    void mintRequiresTheScreensSlotCount() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.mint(null))
                .withMessageContaining("slotIdentifiers");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.mint(List.of(identifier(1))))
                .withMessageContaining("exactly");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.mint(
                        Collections.nCopies(TransactionListPageTokenService.MAX_PAGE_ROWS + 1,
                                identifier(1))))
                .withMessageContaining("exactly");
    }

    @Test
    @DisplayName("mint refuses an identifier of a width the row could not have displayed")
    void mintRefusesAnIdentifierOfTheWrongWidth() {
        final List<String> slots = new ArrayList<>(fullPage());
        slots.set(0, "0001");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.mint(slots))
                .withMessageContaining(
                        String.valueOf(TransactionListPageTokenService.TRANSACTION_ID_WIDTH));
    }

    @Test
    @DisplayName("a slot is one-based and bounded by the ten the screen has")
    void requiresASlotInsideTheScreen() {
        final String token = service.mint(fullPage());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(token, 0))
                .withMessageContaining("oneBasedSlot");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(token,
                        TransactionListPageTokenService.MAX_PAGE_ROWS + 1))
                .withMessageContaining("oneBasedSlot");
    }

    @Test
    @DisplayName("an absent or blank token is refused")
    void refusesAnAbsentToken() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(null, 1))
                .withMessageContaining("no page token was presented");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve("   ", 1))
                .withMessageContaining("no page token was presented");
    }

    @Test
    @DisplayName("a one-character ciphertext change is refused, so a marker cannot be steered by editing "
            + "the token it was handed")
    void refusesATamperedEnvelope() {
        final String token = service.mint(fullPage());
        final char[] characters = token.toCharArray();
        final int last = characters.length - 1;
        characters[last] = characters[last] == 'A' ? 'B' : 'A';

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(new String(characters), 1))
                .withMessageContaining("could not be authenticated");
    }

    @Test
    @DisplayName("a token from another deployment key is refused")
    void refusesATokenFromAnotherKey() {
        final TransactionListPageTokenService foreign = new TransactionListPageTokenService(
                new SensitiveFieldEncryptionService(OTHER_BASE64_KEY));
        final String foreignToken = foreign.mint(fullPage());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(foreignToken, 1))
                .withMessageContaining("could not be authenticated");
    }

    @Test
    @DisplayName("a snapshot minted for the card-list screen is refused here, because the field binding "
            + "is part of what is authenticated")
    void refusesASnapshotFromAnotherScreen() {
        final CardListPageTokenService cardList = new CardListPageTokenService(encryption);
        final String cardListToken = cardList.mint(List.of(
                new CardListPageTokenService.DisplayedCard("00000000011", "4111111111111111")));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(cardListToken, 1))
                .withMessageContaining("could not be authenticated");
    }

    @Test
    @DisplayName("foreign, truncated and nonnumeric payload headers are refused")
    void refusesMalformedHeaders() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(sealPayload("CT99P9" + UNIT_SEPARATOR + "10"
                        + repeatedSeparatedSlots()), 1))
                .withMessageContaining("unrecognised payload");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(sealPayload(
                        TransactionListPageTokenService.PAYLOAD_SCHEME), 1))
                .withMessageContaining("unrecognised payload");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(sealPayload(
                        TransactionListPageTokenService.PAYLOAD_SCHEME + UNIT_SEPARATOR + "ten"
                                + repeatedSeparatedSlots()), 1))
                .withMessageContaining("unreadable slot count");
    }

    @Test
    @DisplayName("a payload carrying fewer or more slots than it declares is refused, so it cannot be "
            + "shifted onto a neighbouring row")
    void refusesAPayloadThatDoesNotDescribeTheScreen() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(sealPayload(
                        TransactionListPageTokenService.PAYLOAD_SCHEME + UNIT_SEPARATOR + "10"
                                + UNIT_SEPARATOR + encode(identifier(1))), 1))
                .withMessageContaining("does not describe this screen's slots");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(sealPayload(
                        TransactionListPageTokenService.PAYLOAD_SCHEME + UNIT_SEPARATOR + "9"
                                + repeatedSeparatedSlots()), 1))
                .withMessageContaining("does not describe this screen's slots");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(sealPayload(
                        TransactionListPageTokenService.PAYLOAD_SCHEME + UNIT_SEPARATOR + "11"
                                + repeatedSeparatedSlots() + UNIT_SEPARATOR + encode(identifier(1))),
                        1))
                .withMessageContaining("does not describe this screen's slots");
    }

    @Test
    @DisplayName("unreadable, noncanonical and wrong-width identifiers are refused")
    void refusesMalformedIdentifiers() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(payloadWithFirstSlot("not base64 at all!"), 1))
                .withMessageContaining("unreadable identifier");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(
                        payloadWithFirstSlot(encode(identifier(1)) + "=="), 1))
                .withMessageContaining("identifier");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(payloadWithFirstSlot(encode("0001")), 1))
                .withMessageContaining("wrong width");
    }

    /** Ten empty slot parts, each preceded by the separator. */
    private static String repeatedSeparatedSlots() {
        return String.valueOf(UNIT_SEPARATOR)
                .repeat(TransactionListPageTokenService.MAX_PAGE_ROWS);
    }

    /**
     * A well-formed ten-slot payload whose first slot part is supplied verbatim.
     *
     * @param  firstSlotPart the encoded, or deliberately misencoded, first slot
     * @return the sealed token
     */
    private String payloadWithFirstSlot(final String firstSlotPart) {
        final String[] parts = new String[TransactionListPageTokenService.MAX_PAGE_ROWS];
        Arrays.fill(parts, "");
        parts[0] = firstSlotPart;
        return sealPayload(TransactionListPageTokenService.PAYLOAD_SCHEME + UNIT_SEPARATOR + "10"
                + UNIT_SEPARATOR + String.join(String.valueOf(UNIT_SEPARATOR), parts));
    }

    private String sealPayload(final String payload) {
        return encryption.protect(TransactionListPageTokenService.PAGE_TOKEN_FIELD, payload);
    }

    private static String encode(final String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
