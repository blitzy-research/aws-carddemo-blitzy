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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.api.dto.CardUpdateRequest;
import com.carddemo.api.dto.CardUpdateResponse;
import com.carddemo.domain.Card;
import com.carddemo.exception.OptimisticLockConflictException;

/**
 * Unit test for {@link CardConcurrencyTokenService}, which carries the card-update transaction's fetched
 * record image - and the two screen values the 3270 protected - across the turn between presenting a
 * card and confirming the update.
 *
 * <h2>What is being protected</h2>
 *
 * <p>The legacy program keeps the fetched image in a program work area the terminal cannot reach
 * ({@code app/cbl/COCRDUPC.cbl} line 274, filled at line 1344, returned with the screen at line 550 and
 * sliced back off at lines 392 to 400) and compares it against the freshly locked record in paragraph
 * {@code 9300-CHECK-CHANGE-IN-REC} at lines 1503 to 1508, abandoning the write on any difference by
 * jumping back to the write-processing exit from line 1518. Echoed to a REST client, that image would be
 * under the client's control, and a client that can assert "nothing changed" is authorising its own
 * overwrite. Every test below is written against that threat rather than against the happy path: the
 * accept cases are few and the refuse cases are many.
 *
 * <p>The two protected screen values are a second, separate obligation. The mapset
 * {@code app/bms/COCRDUP.bms} declares the owning account identifier protected at line 84 and the expiry
 * day dark, field-set and protected at line 142, and the legacy write path sources neither from the
 * operator: line 1463 writes the account identifier from the work area and line 1471 reassembles the day
 * from the terminal's guaranteed-unaltered dark echo. A REST body has no attribute byte, so the proof
 * carries both values and a verified proof hands them back - which is what the carried-value tests below
 * assert, and what a card-update service must use instead of request JSON.
 *
 * <h2>How the fixtures are built</h2>
 *
 * <p>One card builder produces a realistic card, and every mutation test changes exactly one field and
 * asserts the refusal. No number below belongs to a real card: the primary account number is a
 * documentation test value that no issuer routes, and the verification code is invented.
 *
 * <p>This is a pure unit test: no container, no Spring context, no database, no queue, no network and no
 * file system, and no elapsed-time or throughput assertion.
 */
@DisplayName("CardConcurrencyTokenService - the sealed fetched image of COCRDUPC 9300")
class CardConcurrencyTokenServiceTest {

    /** Key material for this suite. Thirty-two bytes, which is what the codec requires. */
    private static final String BASE64_KEY = Base64.getEncoder().encodeToString(
            "carddemo-card-test-key-012345678".getBytes(StandardCharsets.UTF_8));

    /** A second, different, equally valid key, used to prove a token cannot cross keys. */
    private static final String OTHER_BASE64_KEY = Base64.getEncoder().encodeToString(
            "carddemo-card-test-key-987654321".getBytes(StandardCharsets.UTF_8));

    /** The verbatim legacy text this service's refusals must carry. */
    private static final String LEGACY_CONFLICT_MESSAGE =
            OptimisticLockConflictException.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE;

    /** The card number the fixture card is keyed by, at the full declared width of sixteen. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The owning account identifier, protected on the mapset at line 84. */
    private static final String ACCOUNT_ID = "00000000011";

    /** The verification code. Never rendered, never carried, only digested. */
    private static final String CVV = "742";

    /** An embossed name carrying an embedded space, which the legacy alphabetic rule admits. */
    private static final String EMBOSSED_NAME = "MARY ANN";

    /** The stored ten-character expiry value, whose parts the legacy compares individually. */
    private static final String EXPIRY = "2027-12-31";

    /** The day characters of {@link #EXPIRY}, which the dark protected field carries across. */
    private static final String EXPIRY_DAY = "31";

    /**
     * Mints taken when asking whether a short value is disclosed by the envelope.
     *
     * <p>Sized so the question is answered rather than sampled. A two-character run appears in one
     * envelope in roughly twenty by chance, so the probability that every mint of a run this long
     * contains one without the payload carrying it is far below any rate at which a build could
     * observe it, while a value the payload genuinely carried would appear in every single mint.
     */
    private static final int MINTS_PER_DISCLOSURE_TRIAL = 24;

    /** How many freshly minted tokens the substring scan inspects, each carrying a fresh body. */
    private static final int MINTS_PER_DISCLOSURE_SCAN = 8;

    private final SensitiveFieldEncryptionService encryption =
            new SensitiveFieldEncryptionService(BASE64_KEY);

    private final CardConcurrencyTokenService service =
            new CardConcurrencyTokenService(encryption);

    /**
     * The card as the screen presented it.
     *
     * @return a fresh card fixture
     */
    private static Card card() {
        return new Card(CARD_NUMBER, ACCOUNT_ID, CVV, EMBOSSED_NAME, EXPIRY, "Y");
    }

    /**
     * The complete card-update before-image in its declared component order.
     *
     * @return a fresh carried image fixture
     */
    private static CardUpdateService.CarriedCardImage carriedImage() {
        return new CardUpdateService.CarriedCardImage(
                ACCOUNT_ID, CARD_NUMBER, CVV, EMBOSSED_NAME, "2027", "12", EXPIRY_DAY, "Y");
    }

    /**
     * Rebuilds the fixture with the named field replaced, so a mutation test changes exactly one thing.
     *
     * @param field    which field to replace
     * @param newValue the replacement value, which may be {@code null} or empty
     * @return the mutated card
     */
    private static Card cardWith(CardField field, String newValue) {
        String[] values = {CARD_NUMBER, ACCOUNT_ID, CVV, EMBOSSED_NAME, EXPIRY, "Y"};
        values[field.ordinal()] = newValue;
        return new Card(values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    /** The six values the canonical image covers, in the order the image appends them. */
    private enum CardField {
        CARD_NUM, ACCT_ID, CVV_CD, EMBOSSED_NAME, EXPIRATION_DATE, ACTIVE_STATUS
    }

    /**
     * Asserts that a verification is refused as the legacy concurrency arm, carrying the legacy text and
     * naming the record without disclosing any field value beyond the key it is keyed by.
     *
     * @param token the token to present
     * @param card  the card as freshly read
     */
    private void assertRefused(String token, Card card) {
        assertThatExceptionOfType(OptimisticLockConflictException.class)
                .isThrownBy(() -> service.verify(token, card))
                .withMessage(LEGACY_CONFLICT_MESSAGE)
                .satisfies(conflict -> {
                    assertThat(conflict.conflictKind()).isEqualTo(
                            OptimisticLockConflictException.ConflictKind.RECORD_CHANGED_BEFORE_UPDATE);
                    assertThat(conflict.entityName()).isEqualTo("Card");
                });
    }

    /**
     * Asserts that a conversation continuation is refused through the single conflict arm.
     *
     * @param token the token to open
     */
    private void assertContinuationRefused(String token) {
        assertThatExceptionOfType(OptimisticLockConflictException.class)
                .isThrownBy(() -> service.openContinuation(token))
                .withMessage(LEGACY_CONFLICT_MESSAGE)
                .satisfies(conflict -> {
                    assertThat(conflict.conflictKind()).isEqualTo(
                            OptimisticLockConflictException.ConflictKind
                                    .RECORD_CHANGED_BEFORE_UPDATE);
                    assertThat(conflict.entityName()).isEqualTo("Card");
                    assertThat(conflict.key()).isEmpty();
                });
    }

    /**
     * Seals a deliberately composed continuation payload under the correct binding.
     *
     * @param scheme the payload scheme
     * @param action the change-action name
     * @param fields the already marked carried-image fields
     * @return the protected token
     */
    private String protectedContinuation(String scheme, String action, String... fields) {
        final StringJoiner payload = new StringJoiner(String.valueOf('\u001F'));
        payload.add(scheme).add(action);
        for (String field : fields) {
            payload.add(field);
        }
        return encryption.protect(
                CardConcurrencyTokenService.CONTINUATION_TOKEN_FIELD, payload.toString());
    }

    @Nested
    @DisplayName("An unchanged card is accepted")
    class AnUnchangedCardIsAccepted {

        @Test
        @DisplayName("a token minted from a card verifies against the same card")
        void aTokenMintedFromACardVerifiesAgainstTheSameCard() {
            Card presented = card();
            String token = service.mint(presented);

            assertThatCode(() -> service.verify(token, presented)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a card rebuilt from the same values verifies, because the comparison is of values "
                + "and not of instances")
        void aCardRebuiltFromTheSameValuesVerifies() {
            String token = service.mint(card());

            assertThatCode(() -> service.verify(token, card())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a case-only edit to the embossed name is not a change, because the legacy folds "
                + "both sides of the comparison before comparing them")
        void aCaseOnlyEditToTheEmbossedNameIsNotAChange() {
            String token = service.mint(card());
            Card lowerCased = cardWith(CardField.EMBOSSED_NAME, "mary ann");

            assertThatCode(() -> service.verify(token, lowerCased)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a different expiry separator is not a change, because the legacy tests the year, "
                + "month and day positions and never the separator positions")
        void aDifferentExpirySeparatorIsNotAChange() {
            String token = service.mint(card());
            Card slashSeparated = cardWith(CardField.EXPIRATION_DATE, "2027/12/31");

            assertThatCode(() -> service.verify(token, slashSeparated)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the row version is not sealed into the token, so a version bumped by an unrelated "
                + "write does not by itself refuse the confirmation")
        void theRowVersionIsNotSealedIntoTheToken() {
            Card presented = card();
            String token = service.mint(presented);

            // The provider owns the version and there is no setter; what is asserted is the design
            // property that the token's payload does not depend on it, so two cards agreeing on every
            // mapped value agree here regardless of what the provider has done to the counter.
            assertThat(presented.getVersion()).isZero();
            assertThatCode(() -> service.verify(token, card())).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("A concurrent change to the card refuses the write")
    class AConcurrentChangeToTheCardRefusesTheWrite {

        @Test
        @DisplayName("every one of the six values the image covers refuses the write when it moves")
        void everyCoveredValueRefusesTheWriteWhenItMoves() {
            String token = service.mint(card());

            assertRefused(token, cardWith(CardField.CARD_NUM, "4111111111111112"));
            assertRefused(token, cardWith(CardField.ACCT_ID, "00000000012"));
            assertRefused(token, cardWith(CardField.CVV_CD, "743"));
            assertRefused(token, cardWith(CardField.EMBOSSED_NAME, "MARY ANNE"));
            assertRefused(token, cardWith(CardField.EXPIRATION_DATE, "2028-12-31"));
            assertRefused(token, cardWith(CardField.ACTIVE_STATUS, "N"));
        }

        @Test
        @DisplayName("a change confined to the expiry month refuses the write, and so does one confined "
                + "to the expiry day, because the parts are compared separately")
        void aChangeConfinedToOneExpiryPartRefusesTheWrite() {
            String token = service.mint(card());

            assertRefused(token, cardWith(CardField.EXPIRATION_DATE, "2027-11-31"));
            assertRefused(token, cardWith(CardField.EXPIRATION_DATE, "2027-12-30"));
        }

        @Test
        @DisplayName("a value emptied rather than altered refuses the write, because absent, empty and "
                + "present are three states and not two")
        void aValueEmptiedRatherThanAlteredRefusesTheWrite() {
            String token = service.mint(card());

            assertRefused(token, cardWith(CardField.CVV_CD, ""));
            assertRefused(token, cardWith(CardField.CVV_CD, null));
            assertRefused(token, cardWith(CardField.EMBOSSED_NAME, ""));
            assertRefused(token, cardWith(CardField.EMBOSSED_NAME, null));
            assertRefused(token, cardWith(CardField.ACTIVE_STATUS, null));
        }

        @Test
        @DisplayName("a record holding no embossed name mints and verifies, because the fold tolerates "
                + "an absent value rather than raising inside a concurrency check")
        void aRecordHoldingNoEmbossedNameMintsAndVerifies() {
            Card noName = cardWith(CardField.EMBOSSED_NAME, null);
            String token = service.mint(noName);

            assertThatCode(() -> service.verify(token, cardWith(CardField.EMBOSSED_NAME, null)))
                    .doesNotThrowAnyException();
            assertRefused(token, cardWith(CardField.EMBOSSED_NAME, ""));
        }

        @Test
        @DisplayName("a token minted for one card cannot be presented for another, because both "
                + "identifiers are sealed inside the image")
        void aTokenMintedForOneCardCannotBePresentedForAnother() {
            String tokenForFirst = service.mint(card());
            Card otherCard = new Card("5555555555554444", "00000000022", CVV, EMBOSSED_NAME,
                    EXPIRY, "Y");

            assertRefused(tokenForFirst, otherCard);
        }
    }

    @Nested
    @DisplayName("An untrustworthy token refuses the write")
    class AnUntrustworthyTokenRefusesTheWrite {

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "\t", "\n", "not-a-token", "CCUP1", "AAAAAAAAAAAA"})
        @DisplayName("a blank, malformed or unauthenticated token is refused")
        void aBlankOrMalformedTokenIsRefused(String presented) {
            assertRefused(presented, card());
        }

        @Test
        @DisplayName("an absent token is refused as a conflict rather than raising a different type, so "
                + "a client that simply omits the proof cannot reach a write")
        void anAbsentTokenIsRefusedAsAConflict() {
            assertRefused(null, card());
        }

        @Test
        @DisplayName("a token whose sealed form is altered by one character is refused, because the "
                + "envelope is authenticated rather than merely encrypted")
        void aTokenAlteredByOneCharacterIsRefused() {
            String token = service.mint(card());
            char last = token.charAt(token.length() - 1);
            String altered = token.substring(0, token.length() - 1)
                    + (last == 'A' ? 'B' : 'A');

            assertThat(altered).isNotEqualTo(token).hasSameSizeAs(token);
            assertRefused(altered, card());
        }

        @Test
        @DisplayName("a token sealed under a different key is refused, so a token cannot travel between "
                + "environments")
        void aTokenSealedUnderADifferentKeyIsRefused() {
            CardConcurrencyTokenService otherEnvironment = new CardConcurrencyTokenService(
                    new SensitiveFieldEncryptionService(OTHER_BASE64_KEY));
            String foreignToken = otherEnvironment.mint(card());

            assertRefused(foreignToken, card());
        }

        @Test
        @DisplayName("the account arm's own payload cannot be presented here, because the scheme marker "
                + "is sealed inside the authenticated payload and checked after authentication")
        void theAccountArmsPayloadCannotBePresentedHere() {
            String accountSchemePayload = encryption.protect(
                    CardConcurrencyTokenService.CONCURRENCY_TOKEN_FIELD,
                    AccountConcurrencyTokenService.PAYLOAD_SCHEME + '\u001F' + "0".repeat(64)
                            + '\u001F' + "0".repeat(64));

            assertRefused(accountSchemePayload, card());
        }

        @Test
        @DisplayName("a value sealed under a different binding cannot be presented as a token, because "
                + "the binding is authenticated")
        void aValueSealedUnderADifferentBindingIsRefused() {
            String notAToken = encryption.protect(
                    SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, "999887777");

            assertRefused(notAToken, card());
        }

        @Test
        @DisplayName("a truncated payload is refused rather than accepted on its first part")
        void aTruncatedPayloadIsRefused() {
            String truncated = encryption.protect(
                    CardConcurrencyTokenService.CONCURRENCY_TOKEN_FIELD,
                    CardConcurrencyTokenService.PAYLOAD_SCHEME + '\u001F' + "0".repeat(64));

            assertRefused(truncated, card());
        }

        @Test
        @DisplayName("a payload whose carried protected value uses an unrecognised marker is refused "
                + "rather than read as an absent value")
        void aPayloadWithAnUnreadableCarriedValueIsRefused() {
            // The digest is correct for the fixture card, so the only defect is the carried marker.
            // Reaching this refusal proves the marker is validated by re-encoding rather than trusted.
            String tokenWithGoodDigest = service.mint(card());
            String payload = encryption.reveal(
                    CardConcurrencyTokenService.CONCURRENCY_TOKEN_FIELD, tokenWithGoodDigest);
            String[] parts = payload.split(String.valueOf('\u001F'), -1);
            String corrupted = parts[0] + '\u001F' + parts[1] + '\u001F' + "?" + ACCOUNT_ID
                    + '\u001F' + parts[3];

            assertRefused(encryption.protect(
                    CardConcurrencyTokenService.CONCURRENCY_TOKEN_FIELD, corrupted), card());
        }
    }

    @Nested
    @DisplayName("The proof carries the two values the terminal protected")
    class TheProofCarriesTheTwoProtectedValues {

        @Test
        @DisplayName("a verified proof hands back the owning account identifier and the expiry day, so "
                + "a service never has to read either from request JSON")
        void aVerifiedProofHandsBackBothProtectedValues() {
            String token = service.mint(card());

            CardConcurrencyTokenService.CarriedState carried = service.verify(token, card());

            assertThat(carried.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(carried.expiryDay()).isEqualTo(EXPIRY_DAY);
        }

        @Test
        @DisplayName("the carried values come from the proof and not from anything a client sent, so a "
                + "request naming a different account and day changes neither of them")
        void theCarriedValuesComeFromTheProofAndNotFromTheRequest() {
            String token = service.mint(card());
            CardUpdateRequest clientClaims = new CardUpdateRequest("99999999999", CARD_NUMBER,
                    EMBOSSED_NAME, "Y", "12", "2027", "01", null, null, token);

            CardConcurrencyTokenService.CarriedState carried =
                    service.verify(clientClaims.concurrencyToken(), card());

            assertThat(clientClaims.accountId()).isEqualTo("99999999999");
            assertThat(clientClaims.expiryDay()).isEqualTo("01");
            assertThat(carried.accountId()).isEqualTo(ACCOUNT_ID).isNotEqualTo(
                    clientClaims.accountId());
            assertThat(carried.expiryDay()).isEqualTo(EXPIRY_DAY).isNotEqualTo(
                    clientClaims.expiryDay());
        }

        @Test
        @DisplayName("a record holding no expiry value carries an absent day rather than an empty one, "
                + "and still mints and verifies")
        void aRecordHoldingNoExpiryValueCarriesAnAbsentDay() {
            Card noExpiry = cardWith(CardField.EXPIRATION_DATE, null);
            String token = service.mint(noExpiry);

            CardConcurrencyTokenService.CarriedState carried = service.verify(token, noExpiry);

            assertThat(carried.expiryDay()).isNull();
            assertThat(carried.accountId()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("an expiry value too short to reach the day position carries an empty day, which "
                + "stays distinguishable from an absent one")
        void aShortExpiryValueCarriesAnEmptyDay() {
            Card shortExpiry = cardWith(CardField.EXPIRATION_DATE, "2027-12");
            String token = service.mint(shortExpiry);

            CardConcurrencyTokenService.CarriedState carried = service.verify(token, shortExpiry);

            assertThat(carried.expiryDay()).isEmpty();
        }

        @Test
        @DisplayName("a record holding no owning account identifier carries an absent one, and a record "
                + "that later acquires one refuses the write")
        void anAbsentOwningAccountIdentifierIsCarriedAsAbsent() {
            Card noAccount = cardWith(CardField.ACCT_ID, null);
            String token = service.mint(noAccount);

            assertThat(service.verify(token, cardWith(CardField.ACCT_ID, null)).accountId()).isNull();
            assertRefused(token, card());
        }
    }

    @Nested
    @DisplayName("The token itself discloses nothing")
    class TheTokenItselfDisclosesNothing {

        /*
         * WHY THE TWO SHORTEST VALUES ARE NOT ASSERTED AS SUBSTRINGS.
         *
         * The token is an authenticated-encryption envelope over a fresh vector, so its Base64 body is
         * indistinguishable from random text drawn from a 64-character alphabet. A needle of length n
         * therefore appears in a body of b positions by pure coincidence with probability about
         * b / 64^n. For a body of roughly two hundred positions that is about one run in twenty for a
         * two-character needle and about one run in thirteen hundred for a three-character one - a
         * substring assertion on either measures the random generator, not the contract, and fails
         * intermittently for a reason that has nothing to do with disclosure. (This is not
         * theoretical: the two-character day needle was observed matching inside the body.)
         *
         * Every needle asserted below is at least four characters, where the same arithmetic gives
         * about one run in eighty thousand and falling steeply, and the informative values - the card
         * number, the owning account identifier, the embossed name and the whole expiry - are eight
         * characters or longer, where coincidence is impossible in practice.
         *
         * The two short values are not left unguarded. The verification code and the day characters
         * are covered by the two properties that hold with certainty rather than with probability, and
         * both are asserted below: the body is a protected envelope, so nothing inside it is readable
         * at all; and it is re-randomised on every mint, so any short match is a coincidence of the
         * envelope rather than a leak of the payload. The day characters are additionally the tail of
         * the ten-character expiry value, which IS asserted as a substring.
         */

        @Test
        @DisplayName("no informative field value and no digestible fragment of the card appears in the "
                + "token")
        void noFieldValueAppearsInTheToken() {
            // Scanned over a run of mints rather than one, because the envelope body is a fresh random
            // rendering every time and a single sample would only ever speak for a single body.
            for (int mint = 0; mint < MINTS_PER_DISCLOSURE_SCAN; mint++) {
                final String token = service.mint(card());

                // Values wide enough that a coincidental run in a base64 envelope is not a practical
                // possibility: for these, absence from a mint is decisive on its own.
                assertThat(token)
                        .as("mint %d discloses no field value", mint)
                        .doesNotContain(CARD_NUMBER, ACCOUNT_ID, EMBOSSED_NAME, EXPIRY)
                        .doesNotContain("mary", "MARY", "2027", "4111");

                // Every payload begins with the scheme marker, so if any part of it were carried in
                // the clear the marker would be here too. Five distinctive characters make that a
                // disclosure assertion rather than another coincidence one, and it speaks for the two
                // short values a substring scan cannot speak for.
                assertThat(token)
                        .as("mint %d carries no part of the payload in the clear", mint)
                        .doesNotContain(CardConcurrencyTokenService.PAYLOAD_SCHEME);
                assertThat(encryption.isProtected(token))
                        .as("mint %d is a sealed envelope", mint)
                        .isTrue();
            }

            // The verification code and the expiry day are three and two characters wide, and the
            // envelope is a long run drawn from a 64-symbol alphabet, so a short sequence turns up in
            // it by chance - a two-character one in roughly one envelope in twenty. Asserting its
            // absence from a single mint therefore fails intermittently while proving nothing, which
            // is exactly what it did. Disclosure is separated from coincidence by counting mints
            // instead: a value the payload actually carried would appear in every envelope, whereas a
            // coincidence cannot survive a run of them, so one clean envelope is decisive evidence
            // that the value is not there to be read.
            assertThat(mintsContaining(CVV)).isLessThan(MINTS_PER_DISCLOSURE_TRIAL);
            assertThat(mintsContaining(EXPIRY_DAY)).isLessThan(MINTS_PER_DISCLOSURE_TRIAL);
        }

        /**
         * Counts how many of a run of freshly minted tokens contain the given fragment.
         *
         * @param fragment the value being looked for
         * @return the number of mints in which it appeared, from zero to the trial size
         */
        private int mintsContaining(String fragment) {
            int appearances = 0;
            for (int mint = 0; mint < MINTS_PER_DISCLOSURE_TRIAL; mint++) {
                if (service.mint(card()).contains(fragment)) {
                    appearances++;
                }
            }
            return appearances;
        }

        @Test
        @DisplayName("the two shortest carried values are unreadable with certainty rather than by "
                + "coincidence, because the body is a protected envelope re-randomised on every mint")
        void theTwoShortestCarriedValuesAreUnreadableWithCertainty() {
            String token = service.mint(card());

            assertThat(encryption.isProtected(token))
                    .as("the verification code and the day characters sit inside an authenticated "
                            + "envelope, so no part of the payload is readable from the token")
                    .isTrue();
            assertThat(token)
                    .as("the body is re-randomised on every mint, so a short match is a property of "
                            + "the envelope and never of the payload")
                    .isNotEqualTo(service.mint(card()));
            assertThat(CVV).hasSizeLessThan(4);
            assertThat(EXPIRY_DAY)
                    .hasSizeLessThan(4)
                    .as("the day characters are the tail of the expiry value, which is asserted as a "
                            + "substring above")
                    .isEqualTo(EXPIRY.substring(EXPIRY.length() - EXPIRY_DAY.length()));
        }

        @Test
        @DisplayName("two mints of the same card differ, because the envelope carries a fresh vector, so "
                + "a token cannot be used as a card fingerprint")
        void twoMintsOfTheSameCardDiffer() {
            Set<String> tokens = new LinkedHashSet<>();
            for (int mint = 0; mint < 8; mint++) {
                tokens.add(service.mint(card()));
            }

            assertThat(tokens).hasSize(8);
            for (String token : tokens) {
                assertThatCode(() -> service.verify(token, card())).doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("the token is shaped like every other protected value in the module, so nothing "
                + "distinguishes it in storage or in transit")
        void theTokenIsShapedLikeEveryOtherProtectedValue() {
            String token = service.mint(card());

            assertThat(encryption.isProtected(token)).isTrue();
        }

        @Test
        @DisplayName("the binding names the contract rather than a column, because no column stores it, "
                + "and it is distinct from the account arm's binding")
        void theBindingNamesTheContractAndIsDistinctFromTheAccountArms() {
            assertThat(CardConcurrencyTokenService.CONCURRENCY_TOKEN_FIELD)
                    .isEqualTo("card_update.concurrency_token")
                    .isNotEqualTo(AccountConcurrencyTokenService.CONCURRENCY_TOKEN_FIELD);
            assertThat(CardConcurrencyTokenService.PAYLOAD_SCHEME)
                    .isEqualTo("CCUP1")
                    .isNotEqualTo(AccountConcurrencyTokenService.PAYLOAD_SCHEME);
        }
    }

    @Nested
    @DisplayName("The presenting turn, an intervening change, and the confirming turn")
    class ThePresentingTurnAnInterveningChangeAndTheConfirmingTurn {

        @Test
        @DisplayName("a card changed between the presenting turn and the confirming turn refuses the "
                + "write, which is the window a row version cannot see into")
        void aCardChangedBetweenTheTwoTurnsRefusesTheWrite() {
            // Presenting turn: the screen is built from the card as it stands, and the proof rides out
            // on the response exactly as the legacy work area rode out on the commarea at line 550.
            Card asPresented = card();
            CardUpdateResponse presented = new CardUpdateResponse("CCUP", null, null, "COCRDUPC",
                    null, null, asPresented.getCardAcctId(), asPresented.getCardNum(),
                    asPresented.getCardEmbossedName(), asPresented.getCardActiveStatus(), "12",
                    "2027", EXPIRY_DAY, CardUpdateResponse.Messages.PROMPT_FOR_CHANGES,
                    "/api/cards/update", null, service.mint(asPresented));

            // Someone else commits a change while the operator is reading the screen.
            Card asStoredNow = cardWith(CardField.EMBOSSED_NAME, "SOMEONE ELSE");

            // Confirming turn: the client echoes the proof back on the request.
            CardUpdateRequest confirmation = new CardUpdateRequest(null, CARD_NUMBER, EMBOSSED_NAME,
                    "Y", "12", "2027", null, null, null, presented.concurrencyToken());

            assertRefused(confirmation.concurrencyToken(), asStoredNow);
        }

        @Test
        @DisplayName("a confirming turn that follows the presenting turn with nothing intervening "
                + "proceeds and yields the two protected values")
        void aConfirmingTurnWithNothingInterveningProceeds() {
            String proof = service.mint(card());
            CardUpdateRequest confirmation = new CardUpdateRequest(null, CARD_NUMBER, EMBOSSED_NAME,
                    "N", "12", "2027", null, null, null, proof);

            CardConcurrencyTokenService.CarriedState carried =
                    service.verify(confirmation.concurrencyToken(), card());

            assertThat(carried.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(carried.expiryDay()).isEqualTo(EXPIRY_DAY);
        }

        @Test
        @DisplayName("a second confirming turn replaying the first turn's proof refuses the write, so a "
                + "captured proof cannot be reused once the record has moved on")
        void asecondConfirmingTurnReplayingTheFirstProofIsRefused() {
            String firstTurnProof = service.mint(card());

            // The first confirmation succeeds and the stored record now holds the new status.
            assertThatCode(() -> service.verify(firstTurnProof, card())).doesNotThrowAnyException();
            Card afterFirstUpdate = cardWith(CardField.ACTIVE_STATUS, "N");

            assertRefused(firstTurnProof, afterFirstUpdate);
        }

        @Test
        @DisplayName("the proof the response returns is the proof the request carries, so the round trip "
                + "is the only thing a client has to preserve")
        void theProofTheResponseReturnsIsTheProofTheRequestCarries() {
            String minted = service.mint(card());
            CardUpdateResponse presented = new CardUpdateResponse("CCUP", null, null, "COCRDUPC",
                    null, null, ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME, "Y", "12", "2027",
                    EXPIRY_DAY, null, "/api/cards/update", null, minted);
            CardUpdateRequest echoed = new CardUpdateRequest(null, CARD_NUMBER, EMBOSSED_NAME, "Y",
                    "12", "2027", null, null, null, presented.concurrencyToken());

            assertThat(echoed.concurrencyToken()).isEqualTo(minted);
            assertThatCode(() -> service.verify(echoed.concurrencyToken(), card()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("neither the response nor the request discloses the proof in a diagnostic "
                + "rendering, so the round trip cannot be reconstructed from a log")
        void neitherContractDisclosesTheProofInADiagnosticRendering() {
            String minted = service.mint(card());
            CardUpdateResponse presented = new CardUpdateResponse("CCUP", null, null, "COCRDUPC",
                    null, null, ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME, "Y", "12", "2027",
                    EXPIRY_DAY, null, "/api/cards/update", null, minted);
            CardUpdateRequest echoed = new CardUpdateRequest(null, CARD_NUMBER, EMBOSSED_NAME, "Y",
                    "12", "2027", null, null, null, minted);

            assertThat(presented.toString()).doesNotContain(minted);
            assertThat(echoed.toString()).doesNotContain(minted);
        }
    }

    @Nested
    @DisplayName("The server-sealed conversation continuation")
    class TheServerSealedConversationContinuation {

        @Test
        @DisplayName("every declared change action and all eight image values survive a seal-open round trip")
        void everyStateAndImageValueSurvivesARoundTrip() {
            for (CardUpdateService.ChangeAction action : CardUpdateService.ChangeAction.values()) {
                final String token = service.sealContinuation(action, carriedImage());

                assertThat(service.openContinuation(token))
                        .isEqualTo(new CardConcurrencyTokenService.Continuation(
                                action, carriedImage()));
            }
        }

        @Test
        @DisplayName("the literal image count equals the record shape and the sealed order is pinned")
        void theImageCountAndSealOrderMatchTheRecord() throws ReflectiveOperationException {
            final var countField = CardConcurrencyTokenService.class
                    .getDeclaredField("CONTINUATION_IMAGE_FIELD_COUNT");
            countField.setAccessible(true);

            assertThat(countField.getInt(null))
                    .isEqualTo(CardUpdateService.CarriedCardImage.class.getRecordComponents().length)
                    .isEqualTo(8);

            final String token = service.sealContinuation(
                    CardUpdateService.ChangeAction.SHOW_DETAILS, carriedImage());
            final String payload = encryption.reveal(
                    CardConcurrencyTokenService.CONTINUATION_TOKEN_FIELD, token);

            assertThat(payload.split(String.valueOf('\u001F'), -1)).containsExactly(
                    CardConcurrencyTokenService.CONTINUATION_SCHEME,
                    CardUpdateService.ChangeAction.SHOW_DETAILS.name(),
                    "+" + ACCOUNT_ID,
                    "+" + CARD_NUMBER,
                    "+" + CVV,
                    "+" + EMBOSSED_NAME,
                    "+2027",
                    "+12",
                    "+" + EXPIRY_DAY,
                    "+Y");
        }

        @Test
        @DisplayName("an absent or blank token is exactly the first-entry state")
        void anAbsentOrBlankTokenIsFirstEntry() {
            final CardConcurrencyTokenService.Continuation expected =
                    CardConcurrencyTokenService.Continuation.firstEntry();

            assertThat(service.openContinuation(null)).isEqualTo(expected);
            assertThat(service.openContinuation("")).isEqualTo(expected);
            assertThat(service.openContinuation(" \t")).isEqualTo(expected);
            assertThat(expected.changeAction())
                    .isEqualTo(CardUpdateService.ChangeAction.DETAILS_NOT_FETCHED);
            assertThat(expected.carriedImage()).isEqualTo(CardUpdateService.CarriedCardImage.empty());
        }

        @Test
        @DisplayName("an unauthenticated token and a token sealed under another binding are refused")
        void unauthenticatedAndForeignBoundTokensAreRefused() {
            assertContinuationRefused("not-a-protected-token");
            assertContinuationRefused(encryption.protect(
                    SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, "999887777"));
        }

        @Test
        @DisplayName("a foreign scheme and a payload with the wrong part count are refused")
        void foreignSchemeAndWrongPartCountAreRefused() {
            final String[] fields = {
                "+" + ACCOUNT_ID, "+" + CARD_NUMBER, "+" + CVV, "+" + EMBOSSED_NAME,
                "+2027", "+12", "+" + EXPIRY_DAY, "+Y",
            };
            assertContinuationRefused(protectedContinuation(
                    "FOREIGN", CardUpdateService.ChangeAction.SHOW_DETAILS.name(), fields));
            assertContinuationRefused(protectedContinuation(
                    CardConcurrencyTokenService.CONTINUATION_SCHEME,
                    CardUpdateService.ChangeAction.SHOW_DETAILS.name(),
                    "+" + ACCOUNT_ID, "+" + CARD_NUMBER));
        }

        @Test
        @DisplayName("an unknown action and an unreadable image marker are refused")
        void unknownActionAndUnreadableImageMarkerAreRefused() {
            assertContinuationRefused(protectedContinuation(
                    CardConcurrencyTokenService.CONTINUATION_SCHEME,
                    "CLIENT_CHOSEN_STATE",
                    "+" + ACCOUNT_ID, "+" + CARD_NUMBER, "+" + CVV, "+" + EMBOSSED_NAME,
                    "+2027", "+12", "+" + EXPIRY_DAY, "+Y"));
            assertContinuationRefused(protectedContinuation(
                    CardConcurrencyTokenService.CONTINUATION_SCHEME,
                    CardUpdateService.ChangeAction.SHOW_DETAILS.name(),
                    "?" + ACCOUNT_ID, "+" + CARD_NUMBER, "+" + CVV, "+" + EMBOSSED_NAME,
                    "+2027", "+12", "+" + EXPIRY_DAY, "+Y"));
        }

        @Test
        @DisplayName("a continuation sealed under another environment key is refused")
        void aContinuationCannotCrossEnvironmentKeys() {
            final CardConcurrencyTokenService otherEnvironment =
                    new CardConcurrencyTokenService(
                            new SensitiveFieldEncryptionService(OTHER_BASE64_KEY));
            final String foreign = otherEnvironment.sealContinuation(
                    CardUpdateService.ChangeAction.SHOW_DETAILS, carriedImage());

            assertContinuationRefused(foreign);
        }

        @Test
        @DisplayName("sealing requires both state and image, while rendering reveals neither image nor token")
        void sealingRequiresBothValuesAndRenderingRedactsTheImage() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.sealContinuation(null, carriedImage()))
                    .withMessageContaining("changeAction");
            assertThatNullPointerException()
                    .isThrownBy(() -> service.sealContinuation(
                            CardUpdateService.ChangeAction.SHOW_DETAILS, null))
                    .withMessageContaining("carriedImage");

            final CardConcurrencyTokenService.Continuation continuation =
                    new CardConcurrencyTokenService.Continuation(
                            CardUpdateService.ChangeAction.SHOW_DETAILS, carriedImage());
            assertThat(continuation.toString())
                    .contains("changeAction=SHOW_DETAILS")
                    .contains("carriedImage=***REDACTED***")
                    .doesNotContain(CARD_NUMBER, ACCOUNT_ID, CVV, EMBOSSED_NAME);
        }
    }

    @Nested
    @DisplayName("Programming errors are separated from conflicts")
    class ProgrammingErrorsAreSeparatedFromConflicts {

        @Test
        @DisplayName("minting without a record is a programming error rather than a conflict")
        void mintingWithoutARecordIsAProgrammingError() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.mint(null))
                    .withMessageContaining("card");
        }

        @Test
        @DisplayName("verifying without a record is a programming error rather than a conflict, even "
                + "when the token is itself absent")
        void verifyingWithoutARecordIsAProgrammingError() {
            String token = service.mint(card());

            assertThatNullPointerException()
                    .isThrownBy(() -> service.verify(token, null))
                    .withMessageContaining("card");
            assertThatNullPointerException()
                    .isThrownBy(() -> service.verify(null, null))
                    .withMessageContaining("card");
        }

        @Test
        @DisplayName("constructing the service without the sealing collaborator is refused at once")
        void constructingWithoutTheSealingCollaboratorIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new CardConcurrencyTokenService(null))
                    .withMessageContaining("fieldEncryption");
        }

        @Test
        @DisplayName("the refusal reason never reaches the client, which sees only the legacy text and "
                + "no field name, value, digest or token fragment")
        void theRefusalReasonNeverReachesTheClient() {
            String token = service.mint(card());
            List<String> mustNotAppear = List.of(CARD_NUMBER, ACCOUNT_ID, CVV, EMBOSSED_NAME,
                    EXPIRY, token, "digest", "payload", "scheme");

            assertThatExceptionOfType(OptimisticLockConflictException.class)
                    .isThrownBy(() -> service.verify(token,
                            cardWith(CardField.ACTIVE_STATUS, "N")))
                    .satisfies(conflict -> assertThat(conflict.getMessage())
                            .isEqualTo(LEGACY_CONFLICT_MESSAGE)
                            .doesNotContain(mustNotAppear.toArray(new String[0])));
        }
    }
}
