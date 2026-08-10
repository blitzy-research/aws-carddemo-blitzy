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
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.carddemo.service.CardListPageTokenService.DisplayedCard;
import com.carddemo.util.SensitiveFieldCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Boundary tests for the authenticated card-list row snapshot.
 *
 * <p>What is being pinned is the property the legacy program gets for free and a REST turn does not: a
 * marked screen position resolves to the identity that <em>stood in that position when the page was
 * sent</em>. {@code app/cbl/COCRDLIC.cbl} holds its seven displayed rows in its own communication area at
 * lines 250 to 260 and returns them behind the shared area at lines 604 to 619, so its two selection
 * transfers at lines 531 to 534 and 559 to 562 read the marked row out of storage that nothing else can
 * have moved. This snapshot is that storage, sealed so that the holder cannot read it and cannot forge it.
 *
 * <p>Every refusal below is a closed refusal. There is no arm that accepts a token this server did not
 * mint, none that accepts a token minted for another screen or another deployment key, and none that
 * accepts a shifted, re-counted or re-encoded payload: the card-update screen is one of the two
 * destinations a marker reaches, so a marker that resolves to the wrong record is a write against the
 * wrong card.
 */
@DisplayName("CardListPageTokenService - a marked row stays the row that was displayed")
class CardListPageTokenServiceTest {

    private static final char UNIT_SEPARATOR = '\u001F';

    private static final String BASE64_KEY = Base64.getEncoder().encodeToString(
            "carddemo-card-page-token-key-001".getBytes(StandardCharsets.UTF_8));

    private static final String OTHER_BASE64_KEY = Base64.getEncoder().encodeToString(
            "carddemo-card-page-token-key-999".getBytes(StandardCharsets.UTF_8));

    /** An eleven-character account identifier, the width line 258 declares. */
    private static final String ACCOUNT = "00000000011";

    /** The fourteen leading characters every card number below shares. */
    private static final String CARD_STEM = "41111111111111";

    private final SensitiveFieldEncryptionService encryption =
            new SensitiveFieldEncryptionService(BASE64_KEY);

    private final CardListPageTokenService service = new CardListPageTokenService(encryption);

    /**
     * A full seven-row page in display order.
     *
     * <p>The locale is pinned on the numeric formatting deliberately. {@code String.format} without a
     * locale resolves the ambient one, and under a locale whose CLDR numbering system is not
     * {@code latn} a two-digit ordinal comes out as characters the sixteen-character field could never
     * have held - still two characters, but not two ASCII bytes.
     *
     * @return the seven displayed identities, row one first
     */
    private static List<DisplayedCard> fullPage() {
        final List<DisplayedCard> rows = new ArrayList<>();
        for (int ordinal = 1; ordinal <= CardListPageTokenService.MAX_PAGE_ROWS; ordinal++) {
            rows.add(new DisplayedCard(ACCOUNT, cardNumber(ordinal)));
        }
        return List.copyOf(rows);
    }

    private static String cardNumber(final int ordinal) {
        return CARD_STEM + String.format(Locale.ROOT, "%02d", ordinal);
    }

    @Test
    @DisplayName("a position resolves to the pair sealed at that display position")
    void resolvesTheDisplayedPosition() {
        final String token = service.mint(fullPage());

        assertThat(service.resolve(token, 1)).contains(new DisplayedCard(ACCOUNT, cardNumber(1)));
        assertThat(service.resolve(token, 4)).contains(new DisplayedCard(ACCOUNT, cardNumber(4)));
        assertThat(service.resolve(token, 7)).contains(new DisplayedCard(ACCOUNT, cardNumber(7)));
    }

    @Test
    @DisplayName("neither identifier is legible in the token, so publishing it discloses nothing")
    void neitherIdentifierIsLegibleInTheToken() {
        final String token = service.mint(fullPage());

        assertThat(SensitiveFieldCodec.hasEnvelopeShape(token)).isTrue();
        assertThat(token)
                .doesNotContain(ACCOUNT)
                .doesNotContain(cardNumber(1))
                .doesNotContain(CARD_STEM);
    }

    @Test
    @DisplayName("an empty page round-trips and has no resolvable row")
    void roundTripsAnEmptyPage() {
        final String token = service.mint(List.of());

        assertThat(SensitiveFieldCodec.hasEnvelopeShape(token)).isTrue();
        assertThat(service.resolve(token, 1)).isEmpty();
    }

    @Test
    @DisplayName("a position beyond a partial page is empty rather than shifted onto a neighbouring row")
    void aPositionBeyondAPartialPageIsEmpty() {
        final String token = service.mint(List.of(
                new DisplayedCard(ACCOUNT, cardNumber(1)),
                new DisplayedCard(ACCOUNT, cardNumber(2))));

        assertThat(service.resolve(token, 3)).isEmpty();
    }

    @Test
    @DisplayName("the constructor requires the encryption service")
    void requiresTheEncryptionService() {
        assertThatNullPointerException()
                .isThrownBy(() -> new CardListPageTokenService(null))
                .withMessage("fieldEncryption must not be null");
    }

    @Test
    @DisplayName("a displayed identity must carry both persisted key widths, so a value no screen row "
            + "could have held cannot be sealed")
    void validatesTheDisplayedIdentity() {
        assertThatNullPointerException()
                .isThrownBy(() -> new DisplayedCard(null, cardNumber(1)))
                .withMessageContaining("account identifier");
        assertThatNullPointerException()
                .isThrownBy(() -> new DisplayedCard(ACCOUNT, null))
                .withMessageContaining("card number");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DisplayedCard("0000001", cardNumber(1)))
                .withMessageContaining("exactly 11");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DisplayedCard(ACCOUNT, "4111"))
                .withMessageContaining("exactly 16");
    }

    @Test
    @DisplayName("a displayed identity renders both identifiers as the fixed stand-in, because a "
            + "snapshot of seven pairs is the densest disclosure this class could produce")
    void theDisplayedIdentityWithholdsBothIdentifiers() {
        final String rendered = new DisplayedCard(ACCOUNT, cardNumber(1)).toString();

        assertThat(rendered)
                .isEqualTo("DisplayedCard[accountId=***REDACTED***, cardNumber=***REDACTED***]")
                .doesNotContain(ACCOUNT)
                .doesNotContain(cardNumber(1));
    }

    @Test
    @DisplayName("mint requires a page and refuses one larger than the screen")
    void validatesMintInput() {
        assertThatNullPointerException().isThrownBy(() -> service.mint(null));
        assertThatNullPointerException().isThrownBy(() -> service.mint(
                java.util.Arrays.asList(new DisplayedCard(ACCOUNT, cardNumber(1)), null)));

        final List<DisplayedCard> tooMany = new ArrayList<>(fullPage());
        tooMany.add(new DisplayedCard(ACCOUNT, cardNumber(8)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.mint(tooMany))
                .withMessageContaining("7");
    }

    @Test
    @DisplayName("a row position is one-based and bounded by the seven the screen has")
    void requiresAPositionInsideTheScreen() {
        final String token = service.mint(List.of(new DisplayedCard(ACCOUNT, cardNumber(1))));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(token, 0))
                .withMessageContaining("between one and 7");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(token, 8))
                .withMessageContaining("between one and 7");
    }

    @Test
    @DisplayName("an absent or blank token is refused")
    void refusesAnAbsentToken() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(null, 1))
                .withMessageContaining("no page token");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve("   ", 1))
                .withMessageContaining("no page token");
    }

    @Test
    @DisplayName("a one-character ciphertext change is refused, so a marker cannot be steered by "
            + "editing the token")
    void refusesATamperedEnvelope() {
        final String token = service.mint(fullPage());
        final int changedIndex = token.length() / 2;
        final char replacement = token.charAt(changedIndex) == 'A' ? 'B' : 'A';
        final String tampered = token.substring(0, changedIndex) + replacement
                + token.substring(changedIndex + 1);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(tampered, 1))
                .withMessageContaining("could not be authenticated");
    }

    @Test
    @DisplayName("a token from another deployment key is refused")
    void refusesATokenFromAnotherKey() {
        final CardListPageTokenService other = new CardListPageTokenService(
                new SensitiveFieldEncryptionService(OTHER_BASE64_KEY));
        final String foreign = other.mint(fullPage());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(foreign, 1))
                .withMessageContaining("could not be authenticated");
    }

    @Test
    @DisplayName("a snapshot minted for the user-list screen is refused here, because the field binding "
            + "is part of what is authenticated")
    void refusesASnapshotFromAnotherScreen() {
        final String foreign = new UserListPageTokenService(encryption).mint(List.of("USER0001"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(foreign, 1))
                .withMessageContaining("could not be authenticated");
    }

    @Test
    @DisplayName("foreign, truncated and nonnumeric payload headers are refused")
    void refusesMalformedHeaders() {
        final String foreignScheme = protectedPayload("CCLIP2" + UNIT_SEPARATOR + "0");
        final String truncated = protectedPayload(CardListPageTokenService.PAYLOAD_SCHEME);
        final String nonnumeric = protectedPayload(
                CardListPageTokenService.PAYLOAD_SCHEME + UNIT_SEPARATOR + "many");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(foreignScheme, 1))
                .withMessageContaining("unrecognised payload");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(truncated, 1))
                .withMessageContaining("unrecognised payload");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(nonnumeric, 1))
                .withMessageContaining("row count");
    }

    @Test
    @DisplayName("the declared count must match the pairs carried, so a payload cannot be shortened or "
            + "padded to shift a position")
    void refusesAMismatchedCount() {
        final String payload = CardListPageTokenService.PAYLOAD_SCHEME
                + UNIT_SEPARATOR + "2"
                + UNIT_SEPARATOR + encoded(ACCOUNT)
                + UNIT_SEPARATOR + encoded(cardNumber(1));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(protectedPayload(payload), 1))
                .withMessageContaining("does not match");
    }

    @Test
    @DisplayName("an excessive forged row count is refused")
    void refusesAnExcessiveForgedCount() {
        final String payload = CardListPageTokenService.PAYLOAD_SCHEME + UNIT_SEPARATOR + "8";

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(protectedPayload(payload), 1))
                .withMessageContaining("7");
    }

    @Test
    @DisplayName("unreadable, noncanonical and wrong-width identifiers are refused")
    void refusesMalformedIdentifiers() {
        final String prefix = CardListPageTokenService.PAYLOAD_SCHEME + UNIT_SEPARATOR + "1"
                + UNIT_SEPARATOR;
        final String unreadable = protectedPayload(prefix + "*" + UNIT_SEPARATOR
                + encoded(cardNumber(1)));
        final String noncanonical = protectedPayload(prefix + encoded(ACCOUNT) + "=" + UNIT_SEPARATOR
                + encoded(cardNumber(1)));
        final String wrongWidth = protectedPayload(prefix + encoded(ACCOUNT) + UNIT_SEPARATOR
                + encoded("4111"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(unreadable, 1))
                .withMessageContaining("unreadable identifier");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(noncanonical, 1))
                .withMessageContaining("non-canonical identifier");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(wrongWidth, 1))
                .withMessageContaining("wrong width");
    }

    private String protectedPayload(final String payload) {
        return encryption.protect(CardListPageTokenService.PAGE_TOKEN_FIELD, payload);
    }

    private static String encoded(final String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
