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
package com.carddemo.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Acceptance for the protected-value codec that closes the cleartext-national-identifier gap.
 *
 * <p>The obligation this class discharges is not "encryption happens" but the four properties the
 * migration comment on the customer table promises: the envelope is <em>versioned</em>, it is
 * <em>self-describing</em>, it is <em>authenticated</em> so that tampering fails loudly rather than
 * yielding corrupted cleartext, and it is <em>text</em> that a bounded {@code VARCHAR} column can
 * hold. Each is asserted directly, and the last one is asserted arithmetically against the declared
 * column widths so a future widening of a legacy field cannot silently overflow the column.</p>
 *
 * <p>A pure in-process unit test: no application context, no database, no container, no mocking
 * framework. The codec takes its key as an argument, which is precisely what makes that possible.</p>
 */
@DisplayName("SensitiveFieldCodec - authenticated envelopes for regulated customer fields")
class SensitiveFieldCodecTest {

    /**
     * A 32-byte key. Readable ASCII rather than random bytes so that a failure message is
     * intelligible; it is test material and protects nothing.
     */
    private static final byte[] KEY =
            "carddemo-unit-test-key-0123456!!".getBytes(StandardCharsets.UTF_8);

    /**
     * A second, different 32-byte key, used to prove that a value sealed under one key cannot be read
     * under another.
     */
    private static final byte[] OTHER_KEY =
            "carddemo-unit-test-key-9876543??".getBytes(StandardCharsets.UTF_8);

    /**
     * A nine-digit value of the shape the legacy national-identifier field carries. Synthetic: it is
     * not drawn from any fixture in the estate.
     */
    private static final String NINE_DIGIT_IDENTIFIER = "123456789";

    /** A twenty-character value of the shape the government-issued identifier field carries. */
    private static final String TWENTY_CHARACTER_IDENTIFIER = "GOVTID00000000000001";

    /** Declared width of both protected columns in {@code V1__create_schema.sql}. */
    private static final int PROTECTED_COLUMN_WIDTH = 255;

    @Nested
    @DisplayName("the round trip is exact")
    class RoundTrip {

        @Test
        @DisplayName("a nine-digit identifier survives sealing and opening unchanged")
        void aNineDigitIdentifierSurvivesUnchanged() {
            String envelope = SensitiveFieldCodec.protect(NINE_DIGIT_IDENTIFIER, KEY);

            assertThat(SensitiveFieldCodec.reveal(envelope, KEY)).isEqualTo(NINE_DIGIT_IDENTIFIER);
        }

        @Test
        @DisplayName("a twenty-character identifier survives sealing and opening unchanged")
        void aTwentyCharacterIdentifierSurvivesUnchanged() {
            String envelope = SensitiveFieldCodec.protect(TWENTY_CHARACTER_IDENTIFIER, KEY);

            assertThat(SensitiveFieldCodec.reveal(envelope, KEY))
                    .isEqualTo(TWENTY_CHARACTER_IDENTIFIER);
        }

        @Test
        @DisplayName("an empty value is a legitimate input and produces a readable minimal envelope")
        void anEmptyValueRoundTrips() {
            String envelope = SensitiveFieldCodec.protect("", KEY);

            assertThat(SensitiveFieldCodec.reveal(envelope, KEY)).isEmpty();
        }

        @Test
        @DisplayName("leading zeros, spaces and padding survive, because nothing is normalised")
        void paddingAndLeadingZerosSurvive() {
            String padded = "  000123  ";

            String envelope = SensitiveFieldCodec.protect(padded, KEY);

            assertThat(SensitiveFieldCodec.reveal(envelope, KEY)).isEqualTo(padded);
        }

        @Test
        @DisplayName("a non-ASCII value survives, because the cleartext is encoded as UTF-8")
        void aNonAsciiValueSurvives() {
            String accented = "Jos\u00e9 M\u00fcller";

            String envelope = SensitiveFieldCodec.protect(accented, KEY);

            assertThat(SensitiveFieldCodec.reveal(envelope, KEY)).isEqualTo(accented);
        }
    }

    @Nested
    @DisplayName("the envelope is versioned, self-describing and text")
    class EnvelopeShape {

        @Test
        @DisplayName("every envelope opens with the scheme marker")
        void everyEnvelopeOpensWithTheSchemeMarker() {
            String envelope = SensitiveFieldCodec.protect(NINE_DIGIT_IDENTIFIER, KEY);

            assertThat(envelope).startsWith(SensitiveFieldCodec.ENVELOPE_PREFIX);
            assertThat(SensitiveFieldCodec.ENVELOPE_PREFIX).isEqualTo("ENC1:");
        }

        @Test
        @DisplayName("the body decodes to the vector, the ciphertext and the tag, and nothing more")
        void theBodyDecodesToVectorCiphertextAndTag() {
            String envelope = SensitiveFieldCodec.protect(NINE_DIGIT_IDENTIFIER, KEY);

            byte[] body = Base64.getDecoder()
                    .decode(envelope.substring(SensitiveFieldCodec.ENVELOPE_PREFIX.length()));

            assertThat(body).hasSize(SensitiveFieldCodec.IV_LENGTH_BYTES
                    + NINE_DIGIT_IDENTIFIER.length() + SensitiveFieldCodec.TAG_LENGTH_BYTES);
        }

        @Test
        @DisplayName("the whole envelope is printable single-byte text a VARCHAR column can hold")
        void theWholeEnvelopeIsPrintableSingleByteText() {
            String envelope = SensitiveFieldCodec.protect(TWENTY_CHARACTER_IDENTIFIER, KEY);

            assertThat(envelope.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(envelope.length());
            for (int index = 0; index < envelope.length(); index++) {
                char character = envelope.charAt(index);
                assertThat(character)
                        .as("character at %d must be printable ASCII", index)
                        .isBetween('!', '~');
            }
        }

        @Test
        @DisplayName("the cleartext does not appear anywhere in the envelope")
        void theCleartextDoesNotAppearInTheEnvelope() {
            String envelope = SensitiveFieldCodec.protect(NINE_DIGIT_IDENTIFIER, KEY);

            assertThat(envelope).doesNotContain(NINE_DIGIT_IDENTIFIER);
        }

        @Test
        @DisplayName("sealing the same value twice yields two different envelopes")
        void sealingTheSameValueTwiceYieldsDifferentEnvelopes() {
            Set<String> envelopes = new HashSet<>();
            for (int attempt = 0; attempt < 32; attempt++) {
                envelopes.add(SensitiveFieldCodec.protect(NINE_DIGIT_IDENTIFIER, KEY));
            }

            assertThat(envelopes)
                    .as("a fresh initialisation vector per call is a requirement of the mode, not an "
                            + "inconvenience: reusing one under a single key destroys confidentiality")
                    .hasSize(32);
        }

        @Test
        @DisplayName("both column widths are wide enough for the identifiers they must hold")
        void bothColumnWidthsAreWideEnough() {
            assertThat(SensitiveFieldCodec.envelopeLengthFor(NINE_DIGIT_IDENTIFIER.length()))
                    .isLessThanOrEqualTo(PROTECTED_COLUMN_WIDTH);
            assertThat(SensitiveFieldCodec.envelopeLengthFor(TWENTY_CHARACTER_IDENTIFIER.length()))
                    .isLessThanOrEqualTo(PROTECTED_COLUMN_WIDTH);
        }

        @Test
        @DisplayName("the declared envelope length is exact, not an estimate")
        void theDeclaredEnvelopeLengthIsExact() {
            for (int cleartextLength = 0; cleartextLength <= 40; cleartextLength++) {
                String cleartext = "X".repeat(cleartextLength);

                assertThat(SensitiveFieldCodec.protect(cleartext, KEY))
                        .as("a %d-character cleartext must produce the declared envelope length",
                                cleartextLength)
                        .hasSize(SensitiveFieldCodec.envelopeLengthFor(cleartextLength));
            }
        }

        @Test
        @DisplayName("a negative cleartext length is refused rather than answered")
        void aNegativeCleartextLengthIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SensitiveFieldCodec.envelopeLengthFor(-1))
                    .withMessageContaining("must not be negative");
        }
    }

    @Nested
    @DisplayName("the envelope is authenticated, so tampering fails loudly")
    class Authentication {

        @Test
        @DisplayName("a single altered ciphertext byte makes opening fail rather than corrupt")
        void anAlteredCiphertextByteMakesOpeningFail() {
            String envelope = SensitiveFieldCodec.protect(NINE_DIGIT_IDENTIFIER, KEY);
            byte[] body = Base64.getDecoder()
                    .decode(envelope.substring(SensitiveFieldCodec.ENVELOPE_PREFIX.length()));
            body[SensitiveFieldCodec.IV_LENGTH_BYTES] ^= (byte) 0x01;
            String tampered = SensitiveFieldCodec.ENVELOPE_PREFIX
                    + Base64.getEncoder().encodeToString(body);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> SensitiveFieldCodec.reveal(tampered, KEY))
                    .withMessageContaining("authenticated decryption failed");
        }

        @Test
        @DisplayName("a single altered vector byte makes opening fail too")
        void anAlteredVectorByteMakesOpeningFail() {
            String envelope = SensitiveFieldCodec.protect(NINE_DIGIT_IDENTIFIER, KEY);
            byte[] body = Base64.getDecoder()
                    .decode(envelope.substring(SensitiveFieldCodec.ENVELOPE_PREFIX.length()));
            body[0] ^= (byte) 0x01;
            String tampered = SensitiveFieldCodec.ENVELOPE_PREFIX
                    + Base64.getEncoder().encodeToString(body);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> SensitiveFieldCodec.reveal(tampered, KEY));
        }

        @Test
        @DisplayName("a single altered tag byte makes opening fail as well")
        void anAlteredTagByteMakesOpeningFail() {
            String envelope = SensitiveFieldCodec.protect(NINE_DIGIT_IDENTIFIER, KEY);
            byte[] body = Base64.getDecoder()
                    .decode(envelope.substring(SensitiveFieldCodec.ENVELOPE_PREFIX.length()));
            body[body.length - 1] ^= (byte) 0x01;
            String tampered = SensitiveFieldCodec.ENVELOPE_PREFIX
                    + Base64.getEncoder().encodeToString(body);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> SensitiveFieldCodec.reveal(tampered, KEY));
        }

        @Test
        @DisplayName("a value sealed under one key cannot be opened under another")
        void aValueSealedUnderOneKeyCannotBeOpenedUnderAnother() {
            String envelope = SensitiveFieldCodec.protect(NINE_DIGIT_IDENTIFIER, KEY);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> SensitiveFieldCodec.reveal(envelope, OTHER_KEY))
                    .withMessageContaining("the key does not match");
        }

        @Test
        @DisplayName("no failure message quotes the value it refused")
        void noFailureMessageQuotesTheValueItRefused() {
            String envelope = SensitiveFieldCodec.protect(NINE_DIGIT_IDENTIFIER, KEY);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> SensitiveFieldCodec.reveal(envelope, OTHER_KEY))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .doesNotContain(NINE_DIGIT_IDENTIFIER)
                            .doesNotContain(envelope));
        }
    }

    @Nested
    @DisplayName("the structural check is total and needs no key")
    class ShapeCheck {

        @ParameterizedTest(name = "\"{0}\" is not an envelope")
        @ValueSource(strings = {
            "123456789",
            "GOVTID00000000000001",
            "",
            "   ",
            "null",
            "ENC1:",
            "ENC1:not base64 at all!!",
            "ENC1:QQ==",
            "ENC2:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "enc1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
        @DisplayName("cleartext, blanks, wrong markers and short bodies all fail the check")
        void unprotectedValuesFailTheCheck(String candidate) {
            assertThat(SensitiveFieldCodec.hasEnvelopeShape(candidate)).isFalse();
        }

        @Test
        @DisplayName("an absent value is answered false rather than refused")
        void anAbsentValueIsAnsweredFalse() {
            assertThat(SensitiveFieldCodec.hasEnvelopeShape(null)).isFalse();
        }

        @Test
        @DisplayName("a value the codec produced passes the check")
        void aValueTheCodecProducedPassesTheCheck() {
            assertThat(SensitiveFieldCodec.hasEnvelopeShape(
                    SensitiveFieldCodec.protect(NINE_DIGIT_IDENTIFIER, KEY))).isTrue();
        }

        @Test
        @DisplayName("a minimal envelope, from an empty cleartext, passes the check")
        void aMinimalEnvelopePassesTheCheck() {
            assertThat(SensitiveFieldCodec.hasEnvelopeShape(
                    SensitiveFieldCodec.protect("", KEY))).isTrue();
        }

        @Test
        @DisplayName("opening a value that is not an envelope is a different failure from tampering")
        void openingANonEnvelopeIsADifferentFailureFromTampering() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SensitiveFieldCodec.reveal(NINE_DIGIT_IDENTIFIER, KEY))
                    .withMessageContaining("is not an ENC1: envelope");
        }
    }

    @Nested
    @DisplayName("key material is validated before the cipher sees it")
    class KeyValidation {

        @Test
        @DisplayName("the required key length is 32 bytes, which is AES-256")
        void theRequiredKeyLengthIsThirtyTwoBytes() {
            assertThat(SensitiveFieldCodec.KEY_LENGTH_BYTES).isEqualTo(32);
            assertThat(KEY).hasSize(SensitiveFieldCodec.KEY_LENGTH_BYTES);
        }

        @ParameterizedTest(name = "a key of {0} bytes is refused")
        @ValueSource(ints = {0, 1, 15, 16, 24, 31, 33, 64})
        @DisplayName("a key of any other length is refused with the observed length named")
        void aKeyOfAnyOtherLengthIsRefused(int length) {
            byte[] wrongLength = new byte[length];

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> SensitiveFieldCodec.protect(NINE_DIGIT_IDENTIFIER, wrongLength))
                    .withMessageContaining("exactly 32 bytes")
                    .withMessageContaining(String.valueOf(length));
        }

        @Test
        @DisplayName("an absent key is refused on both operations")
        void anAbsentKeyIsRefusedOnBothOperations() {
            String envelope = SensitiveFieldCodec.protect(NINE_DIGIT_IDENTIFIER, KEY);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> SensitiveFieldCodec.protect(NINE_DIGIT_IDENTIFIER, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> SensitiveFieldCodec.reveal(envelope, null));
        }

        @Test
        @DisplayName("an absent cleartext or envelope is refused rather than treated as empty")
        void anAbsentValueIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> SensitiveFieldCodec.protect(null, KEY))
                    .withMessageContaining("cleartext must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> SensitiveFieldCodec.reveal(null, KEY))
                    .withMessageContaining("envelope must not be null");
        }

        @Test
        @DisplayName("the caller's key array is neither retained nor mutated")
        void theCallersKeyArrayIsNeitherRetainedNorMutated() {
            byte[] caller = KEY.clone();

            String envelope = SensitiveFieldCodec.protect(NINE_DIGIT_IDENTIFIER, caller);

            assertThat(caller).isEqualTo(KEY);
            assertThat(SensitiveFieldCodec.reveal(envelope, KEY)).isEqualTo(NINE_DIGIT_IDENTIFIER);
        }
    }
}
