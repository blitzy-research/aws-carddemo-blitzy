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

import com.carddemo.util.SensitiveFieldCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Boundary tests for the authenticated administrative user-list row snapshot.
 */
@DisplayName("UserListPageTokenService - selected rows remain the rows that were displayed")
class UserListPageTokenServiceTest {

    private static final char UNIT_SEPARATOR = '\u001F';

    private static final String BASE64_KEY = Base64.getEncoder().encodeToString(
            "carddemo-user-page-token-key-001".getBytes(StandardCharsets.UTF_8));

    private static final String OTHER_BASE64_KEY = Base64.getEncoder().encodeToString(
            "carddemo-user-page-token-key-999".getBytes(StandardCharsets.UTF_8));

    private final SensitiveFieldEncryptionService encryption =
            new SensitiveFieldEncryptionService(BASE64_KEY);

    private final UserListPageTokenService service =
            new UserListPageTokenService(encryption);

    /**
     * Builds a full page of identifiers in the eight-character shape the legacy security record uses.
     *
     * <p>The locale is pinned, and it has to be. {@code String.formatted} carries no locale parameter and
     * cannot be given one - it resolves {@code Locale.getDefault(Locale.Category.FORMAT)} - so a
     * {@code %04d} written that way emits whatever digits the ambient locale's numbering system names.
     * Under the {@code ar-EG} locale the workflow's locale gate re-runs this tier in, CLDR selects the
     * {@code arab} numbering system and the identifier came out as {@code USER٠٠٠١}: eight characters
     * still, but not the eight ASCII bytes the fixed-width field holds, so every assertion below read a
     * string it could never match. {@code String.format} with {@link Locale#ROOT} is the spelling that
     * accepts a locale, which is why the call is written this way round rather than as the shorthand.
     *
     * @return the page identifiers, always ASCII, in display order
     */
    private static List<String> fullPage() {
        final List<String> userIds = new ArrayList<>();
        for (int index = 1; index <= UserListPageTokenService.MAX_PAGE_ROWS; index++) {
            userIds.add(String.format(Locale.ROOT, "USER%04d", index));
        }
        return List.copyOf(userIds);
    }

    @Test
    @DisplayName("a position resolves from the sealed display order")
    void resolvesTheDisplayedPosition() {
        final String token = service.mint(fullPage());

        assertThat(service.resolve(token, 1)).contains("USER0001");
        assertThat(service.resolve(token, 7)).contains("USER0007");
        assertThat(service.resolve(token, 10)).contains("USER0010");
    }

    @Test
    @DisplayName("an empty page round-trips and has no resolvable row")
    void roundTripsAnEmptyPage() {
        final String token = service.mint(List.of());

        assertThat(SensitiveFieldCodec.hasEnvelopeShape(token)).isTrue();
        assertThat(service.resolve(token, 1)).isEmpty();
    }

    @Test
    @DisplayName("a position beyond a partial page is empty rather than shifted")
    void aPositionBeyondAPartialPageIsEmpty() {
        final String token = service.mint(List.of("USER0001", "USER0002"));

        assertThat(service.resolve(token, 3)).isEmpty();
    }

    @Test
    @DisplayName("the constructor requires the encryption service")
    void requiresTheEncryptionService() {
        assertThatNullPointerException()
                .isThrownBy(() -> new UserListPageTokenService(null))
                .withMessage("fieldEncryption must not be null");
    }

    @Test
    @DisplayName("mint requires a page and exact persisted identifier widths")
    void validatesMintInput() {
        assertThatNullPointerException().isThrownBy(() -> service.mint(null));
        assertThatNullPointerException()
                .isThrownBy(() -> service.mint(List.of("USER0001", null)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.mint(List.of("SHORT")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.mint(List.of("TOO-LONG-ID")));

        final List<String> tooMany = new ArrayList<>(fullPage());
        tooMany.add("USER0011");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.mint(tooMany))
                .withMessageContaining("10");
    }

    @Test
    @DisplayName("a row position is one-based")
    void requiresAOneBasedPosition() {
        final String token = service.mint(List.of("USER0001"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(token, 0))
                .withMessageContaining("at least one");
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
    @DisplayName("a one-character ciphertext change is refused")
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
        final UserListPageTokenService other = new UserListPageTokenService(
                new SensitiveFieldEncryptionService(OTHER_BASE64_KEY));
        final String foreign = other.mint(fullPage());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(foreign, 1))
                .withMessageContaining("could not be authenticated");
    }

    @Test
    @DisplayName("an envelope bound to another protected field is refused")
    void refusesAnEnvelopeBoundToAnotherField() {
        final String foreign = encryption.protect(
                SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, "USER0001");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(foreign, 1))
                .withMessageContaining("could not be authenticated");
    }

    @Test
    @DisplayName("foreign, truncated and nonnumeric payload headers are refused")
    void refusesMalformedHeaders() {
        final String foreignScheme = protectedPayload("CU00P2" + UNIT_SEPARATOR + "0");
        final String truncated = protectedPayload(UserListPageTokenService.PAYLOAD_SCHEME);
        final String nonnumeric = protectedPayload(
                UserListPageTokenService.PAYLOAD_SCHEME + UNIT_SEPARATOR + "many");

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
    @DisplayName("the declared count must match the identifiers carried")
    void refusesAMismatchedCount() {
        final String payload = UserListPageTokenService.PAYLOAD_SCHEME
                + UNIT_SEPARATOR + "2"
                + UNIT_SEPARATOR + encoded("USER0001");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(protectedPayload(payload), 1))
                .withMessageContaining("does not match");
    }

    @Test
    @DisplayName("an excessive forged row count is refused")
    void refusesAnExcessiveForgedCount() {
        final String payload = UserListPageTokenService.PAYLOAD_SCHEME
                + UNIT_SEPARATOR + "11";

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(protectedPayload(payload), 1))
                .withMessageContaining("10");
    }

    @Test
    @DisplayName("unreadable, noncanonical and wrong-width identifiers are refused")
    void refusesMalformedIdentifiers() {
        final String prefix = UserListPageTokenService.PAYLOAD_SCHEME + UNIT_SEPARATOR + "1"
                + UNIT_SEPARATOR;
        final String unreadable = protectedPayload(prefix + "*");
        final String noncanonical = protectedPayload(prefix + encoded("USER0001") + "=");
        final String wrongWidth = protectedPayload(prefix + encoded("SHORT"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(unreadable, 1))
                .withMessageContaining("unreadable identifier");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(noncanonical, 1))
                .withMessageContaining("non-canonical identifier");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.resolve(wrongWidth, 1))
                .withMessageContaining("exactly 8");
    }

    private String protectedPayload(final String payload) {
        return encryption.protect(UserListPageTokenService.PAGE_TOKEN_FIELD, payload);
    }

    private static String encoded(final String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}