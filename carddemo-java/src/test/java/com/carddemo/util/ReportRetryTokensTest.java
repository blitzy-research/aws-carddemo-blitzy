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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Specification of the self-dating report retry token.
 *
 * <p>Four properties carry the whole of it and each has a nest below. A token this holder minted is
 * recognised under the same key and yields the instant it was stamped with. A token it did not mint - a
 * foreign value, a value of the right shape with a moved instant, a value under another key - is not
 * recognised, and not recognised silently rather than by an exception, because the caller of these methods
 * is handling arbitrary caller text. The window is closed at both ends, so neither an old token nor a
 * pre-dated one is fresh. And the key is derived deterministically from the deployment's signing material
 * under a purpose label, so two instances of one deployment honour each other's tokens while a deployment
 * with different material does not.
 *
 * <p>The tamper cases are the reason this class exists rather than a bare timestamp in a header. Each of
 * them is a value a caller could construct by hand from a token it legitimately holds, and each has to be
 * refused for the window to be a bound rather than a note.
 */
@DisplayName("ReportRetryTokens - the self-dating token behind the report idempotency window")
class ReportRetryTokensTest {

    /** A stable signing secret, long enough to look like real material. */
    private static final String SECRET = "unit-test-signing-secret-material-0123456789abcdef";

    /** A different one, for the cross-deployment cases. */
    private static final String OTHER_SECRET = "a-different-deployments-signing-secret-fedcba9876543210";

    /** A fixed instant, so nothing here depends on when it runs. */
    private static final Instant MINTED_AT = Instant.parse("2022-07-19T14:11:12Z");

    /** URL-safe unpadded encoder, matching the scheme under test. */
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** The key every case that does not vary the secret uses. */
    private final byte[] key = ReportRetryTokens.deriveKey(SECRET);

    @Nested
    @DisplayName("A token this holder minted is recognised, and yields the instant it carries")
    class AMintedTokenIsRecognised {

        @Test
        @DisplayName("round-trips the mint instant at second resolution, which is what the window is "
                + "expressed in")
        void roundTripsTheMintInstant() {
            final String token = ReportRetryTokens.mint(MINTED_AT, key);

            assertThat(ReportRetryTokens.issuedAt(token, key)).contains(MINTED_AT);
        }

        @Test
        @DisplayName("truncates a sub-second instant rather than refusing it, because the window has no "
                + "sub-second meaning")
        void truncatesASubSecondInstant() {
            final Instant withNanos = MINTED_AT.plusNanos(987_654_321L);

            final String token = ReportRetryTokens.mint(withNanos, key);

            assertThat(ReportRetryTokens.issuedAt(token, key))
                    .as("the second is kept and the remainder is dropped")
                    .contains(MINTED_AT);
        }

        @Test
        @DisplayName("opens with the scheme marker, so a later scheme is told apart by inspection rather "
                + "than by guessing at lengths")
        void opensWithTheSchemeMarker() {
            final String token = ReportRetryTokens.mint(MINTED_AT, key);

            assertThat(token).startsWith(ReportRetryTokens.SCHEME + ".");
        }

        @Test
        @DisplayName("carries only characters an HTTP header may hold, so nothing has to be escaped on the "
                + "way out or unescaped on the way back")
        void carriesOnlyHeaderSafeCharacters() {
            final String token = ReportRetryTokens.mint(MINTED_AT, key);

            assertThat(token).matches("[A-Za-z0-9._-]+");
        }

        @Test
        @DisplayName("mints a distinct token for every submission inside one second, so two submissions "
                + "cannot share a submission identity")
        void mintsADistinctTokenWithinOneSecond() {
            final Set<String> minted = new HashSet<>();
            for (int attempt = 0; attempt < 200; attempt++) {
                minted.add(ReportRetryTokens.mint(MINTED_AT, key));
            }

            assertThat(minted)
                    .as("the random component is what separates them; the instant is identical")
                    .hasSize(200);
        }

        @Test
        @DisplayName("refuses a null instant and a key of the wrong width, because neither could produce a "
                + "token this holder would later recognise")
        void refusesUnusableArguments() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportRetryTokens.mint(null, key));
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportRetryTokens.mint(MINTED_AT, null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportRetryTokens.mint(MINTED_AT, new byte[16]))
                    .withMessageContaining("deriveKey");
        }
    }

    @Nested
    @DisplayName("A token this holder did not mint is not recognised, and is refused silently")
    class AForeignOrTamperedTokenIsNot {

        @Test
        @DisplayName("answers empty for an absent or blank value rather than throwing, because the caller "
                + "is handling arbitrary caller text")
        void answersEmptyForAnAbsentValue() {
            assertThat(ReportRetryTokens.issuedAt(null, key)).isEmpty();
            assertThat(ReportRetryTokens.issuedAt("", key)).isEmpty();
            assertThat(ReportRetryTokens.issuedAt("   ", key)).isEmpty();
        }

        @Test
        @DisplayName("answers empty for a value a caller chose for itself, which is the shape the contract "
                + "used to accept without limit")
        void answersEmptyForACallerChosenValue() {
            assertThat(ReportRetryTokens.issuedAt("report-request-retry-001", key)).isEmpty();
            assertThat(ReportRetryTokens.issuedAt("2022-07-monthly", key)).isEmpty();
        }

        @Test
        @DisplayName("answers empty when the scheme marker is not this scheme's")
        void answersEmptyForAnotherScheme() {
            final String token = ReportRetryTokens.mint(MINTED_AT, key);
            final String reScheme = "crt2" + token.substring(ReportRetryTokens.SCHEME.length());

            assertThat(ReportRetryTokens.issuedAt(reScheme, key)).isEmpty();
        }

        @Test
        @DisplayName("answers empty when a field is added or removed, so a token cannot be padded into "
                + "one this holder would read differently")
        void answersEmptyWhenTheFieldCountIsWrong() {
            final String token = ReportRetryTokens.mint(MINTED_AT, key);

            assertThat(ReportRetryTokens.issuedAt(token + ".extra", key)).isEmpty();
            assertThat(ReportRetryTokens.issuedAt(
                    token.substring(0, token.lastIndexOf('.')), key)).isEmpty();
        }

        @Test
        @DisplayName("answers empty when the instant has been moved forward, which is the tamper the "
                + "authentication code exists for")
        void answersEmptyWhenTheInstantIsMovedForward() {
            final String token = ReportRetryTokens.mint(MINTED_AT, key);
            final String[] fields = token.split("\\.");
            final String movedForward = ENCODER.encodeToString(
                    bigEndian(MINTED_AT.plusSeconds(3_600L).getEpochSecond()));
            final String forged = fields[0] + "." + movedForward + "." + fields[2] + "." + fields[3];

            assertThat(ReportRetryTokens.issuedAt(forged, key))
                    .as("a caller that could re-date its own token could lift the window entirely")
                    .isEmpty();
        }

        @Test
        @DisplayName("answers empty when the random component has been changed, so one token cannot be "
                + "spread into a family of submission identities")
        void answersEmptyWhenTheNonceIsChanged() {
            final String token = ReportRetryTokens.mint(MINTED_AT, key);
            final String[] fields = token.split("\\.");
            final String otherNonce = ENCODER.encodeToString(bigEndian(0x0102030405060708L));
            final String forged = fields[0] + "." + fields[1] + "." + otherNonce + "." + fields[3];

            assertThat(ReportRetryTokens.issuedAt(forged, key)).isEmpty();
        }

        @Test
        @DisplayName("answers empty for a truncated authentication code, so a shorter tag is not a weaker "
                + "check")
        void answersEmptyForATruncatedTag() {
            final String token = ReportRetryTokens.mint(MINTED_AT, key);
            final int lastSeparator = token.lastIndexOf('.');
            final String shortened = token.substring(0, lastSeparator + 4);

            assertThat(ReportRetryTokens.issuedAt(shortened, key)).isEmpty();
        }

        @Test
        @DisplayName("answers empty for a field that is not URL-safe Base64 at all")
        void answersEmptyForAnUndecodableField() {
            final String token = ReportRetryTokens.mint(MINTED_AT, key);
            final String[] fields = token.split("\\.");

            assertThat(ReportRetryTokens.issuedAt(
                    fields[0] + ".not+base64/url." + fields[2] + "." + fields[3], key)).isEmpty();
        }

        @Test
        @DisplayName("answers empty under another deployment's key, so a token does not travel between "
                + "deployments")
        void answersEmptyUnderAnotherKey() {
            final String token = ReportRetryTokens.mint(MINTED_AT, key);

            assertThat(ReportRetryTokens.issuedAt(token, ReportRetryTokens.deriveKey(OTHER_SECRET)))
                    .isEmpty();
        }

        @Test
        @DisplayName("refuses a key of the wrong width rather than reading a token under it")
        void refusesAKeyOfTheWrongWidth() {
            final String token = ReportRetryTokens.mint(MINTED_AT, key);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportRetryTokens.issuedAt(token, new byte[8]));
        }
    }

    @Nested
    @DisplayName("The window is closed at both ends")
    class TheWindowIsClosedAtBothEnds {

        @Test
        @DisplayName("a token minted now is within it")
        void aTokenMintedNowIsWithin() {
            assertThat(ReportRetryTokens.isWithinValidity(MINTED_AT, MINTED_AT)).isTrue();
        }

        @Test
        @DisplayName("a token exactly one window old is still within it, so the boundary second is not "
                + "refused")
        void theBoundarySecondIsWithin() {
            final Instant now = MINTED_AT.plus(ReportRetryTokens.VALIDITY);

            assertThat(ReportRetryTokens.isWithinValidity(MINTED_AT, now)).isTrue();
        }

        @Test
        @DisplayName("one second past the window it is not, which is what refuses a retry the queue would "
                + "no longer collapse")
        void oneSecondPastTheWindowIsNot() {
            final Instant now = MINTED_AT.plus(ReportRetryTokens.VALIDITY).plusSeconds(1L);

            assertThat(ReportRetryTokens.isWithinValidity(MINTED_AT, now)).isFalse();
        }

        @Test
        @DisplayName("a token from the future is refused as firmly as an expired one, because a pre-dated "
                + "mint instant would extend the window by exactly the amount it was moved")
        void aTokenFromTheFutureIsRefused() {
            assertThat(ReportRetryTokens.isWithinValidity(MINTED_AT.plusSeconds(1L), MINTED_AT))
                    .isFalse();
        }

        @Test
        @DisplayName("the window is the queue service's five minutes, stated once and derived once")
        void theWindowIsFiveMinutes() {
            assertThat(ReportRetryTokens.VALIDITY_MINUTES).isEqualTo(5);
            assertThat(ReportRetryTokens.VALIDITY.toMinutes())
                    .as("the duration is derived from the figure, so the two cannot disagree")
                    .isEqualTo(ReportRetryTokens.VALIDITY_MINUTES);
        }

        @Test
        @DisplayName("refuses a null on either side rather than treating an absence as fresh")
        void refusesANullOnEitherSide() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportRetryTokens.isWithinValidity(null, MINTED_AT));
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportRetryTokens.isWithinValidity(MINTED_AT, null));
        }
    }

    @Nested
    @DisplayName("The key is derived from the deployment's signing material")
    class TheKeyIsDerived {

        @Test
        @DisplayName("is deterministic, so two instances of one deployment honour each other's tokens and "
                + "a token survives a restart")
        void isDeterministic() {
            final String token = ReportRetryTokens.mint(MINTED_AT, ReportRetryTokens.deriveKey(SECRET));

            assertThat(ReportRetryTokens.issuedAt(token, ReportRetryTokens.deriveKey(SECRET)))
                    .contains(MINTED_AT);
        }

        @Test
        @DisplayName("differs per secret, so one deployment's token is not another's")
        void differsPerSecret() {
            assertThat(ReportRetryTokens.deriveKey(SECRET))
                    .isNotEqualTo(ReportRetryTokens.deriveKey(OTHER_SECRET));
        }

        @Test
        @DisplayName("is not the signing material itself, which is what keeps the two uses apart")
        void isNotTheSigningMaterialItself() {
            assertThat(ReportRetryTokens.deriveKey(SECRET))
                    .isNotEqualTo(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("falls back to material that lives only as long as the process when no secret is "
                + "configured, rather than to a fixed key or a refusal to start")
        void fallsBackToProcessScopedMaterial() {
            final byte[] first = ReportRetryTokens.deriveKey(null);
            final byte[] second = ReportRetryTokens.deriveKey("  ");

            assertThat(first).hasSize(32);
            assertThat(second).hasSize(32);
            assertThat(first)
                    .as("a fixed key here would fabricate a deployment-wide authority that does not exist")
                    .isNotEqualTo(second);
        }

        @Test
        @DisplayName("a token minted under process-scoped material is still readable inside that process, "
                + "so a single-instance context works with no signing secret at all")
        void aProcessScopedTokenWorksWithinTheProcess() {
            final byte[] ephemeral = ReportRetryTokens.deriveKey(null);

            final Optional<Instant> read =
                    ReportRetryTokens.issuedAt(ReportRetryTokens.mint(MINTED_AT, ephemeral), ephemeral);

            assertThat(read).contains(MINTED_AT);
        }
    }

    @Nested
    @DisplayName("The holder itself")
    class TheHolderItself {

        @Test
        @DisplayName("is never instantiated, so the scheme has exactly one form and no state")
        void isNeverInstantiated() throws Exception {
            final Constructor<ReportRetryTokens> constructor =
                    ReportRetryTokens.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("publishes the tag width it commits to, so a reader can see it is 128 bits")
        void publishesTheTagWidth() {
            assertThat(ReportRetryTokens.TAG_LENGTH_BYTES).isEqualTo(16);
        }
    }

    /**
     * Renders a {@code long} as eight big-endian bytes, matching the scheme under test.
     *
     * @param  value the value to render
     * @return exactly eight bytes
     */
    private static byte[] bigEndian(final long value) {
        final byte[] rendered = new byte[8];
        for (int index = 0; index < 8; index++) {
            rendered[index] = (byte) (value >>> (Byte.SIZE * (7 - index)));
        }
        return rendered;
    }
}
