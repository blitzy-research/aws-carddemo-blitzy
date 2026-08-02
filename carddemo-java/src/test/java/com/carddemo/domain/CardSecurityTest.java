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
package com.carddemo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link Card}, the Java realisation of the 150-byte {@code CARD-RECORD} layout declared
 * by copybook member {@code CVACT02Y}.
 *
 * <h2>What is actually at risk in an entity of this shape</h2>
 *
 * <p>Three properties matter, and none of them is "the getter returns what the setter stored", though
 * that is asserted too because six same-typed accessors behind a six-argument constructor make a
 * transposition a real and silent defect.</p>
 *
 * <p>The first, and the reason this class exists, is <strong>diagnostic non-disclosure</strong>. The
 * primary account number is this entity's key, the verification code sits beside it, and so does the
 * embossed cardholder name. An entity reaches a rendered form without its author choosing to disclose
 * anything - a failed assertion message, a provider diagnostic, an interpolated exception message, a
 * structured log event - so the rendering is the one place where withholding is reliable. The
 * assertions below are written negatively: they require that no value appears, rather than checking
 * that a particular substitution was made, because a spot-check would pass while a newly rendered
 * component leaked.</p>
 *
 * <p>The second is <strong>business-key identity</strong>. The card number is the primary key lifted
 * straight out of the record image, never a surrogate, so equality and hashing must follow it and only
 * it. Including a mutable field would let an instance change its own hash while sitting in a set, which
 * is why the mutable fields are excluded and why that exclusion is asserted rather than assumed.</p>
 *
 * <p>The third is <strong>transparency</strong>. The entity stores what it is given: no trimming, no
 * padding, no case folding and no normalisation, because the record fields are fixed-width and
 * space-significant and because the online program's change comparison treats a case-only edit as no
 * change at all. A value that arrives with a leading zero or a trailing space must leave with it.</p>
 *
 * <p>The row version is asserted <em>not</em> to be a client-facing concurrency token. It has no setter,
 * it is provider-owned, and it covers only the interval between loading a row for update and writing
 * it - not the interval during which an operator reads a screen, which is covered by the sealed proof
 * the card-update conversation carries. The distinction is behavioural rather than cosmetic and is
 * asserted here so that a later reader does not mistake the counter for the whole of the protection.</p>
 *
 * <p>No number below belongs to a real card: the primary account numbers are documentation test values
 * that no issuer routes, and the verification codes are invented.</p>
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.</p>
 */
@DisplayName("Card - the 150-byte CVACT02Y card record")
class CardSecurityTest {

    /** The record key, at the full declared width of sixteen. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The owning account identifier, eleven characters with significant leading zeros. */
    private static final String ACCOUNT_ID = "00000000011";

    /** The verification code. Regulated, and never rendered anywhere. */
    private static final String CVV = "742";

    /** An embossed name carrying an embedded space, which the legacy alphabetic rule admits. */
    private static final String EMBOSSED_NAME = "MARY ANN";

    /** The stored ten-character expiry value. */
    private static final String EXPIRY = "2027-12-31";

    /** The one-character active status code. */
    private static final String ACTIVE = "Y";

    /** The fixed stand-in the rendering must emit in place of the card number. */
    private static final String REDACTION_PLACEHOLDER_TEXT = "***REDACTED***";

    /**
     * A realistic card.
     *
     * @return a fresh fixture
     */
    private static Card card() {
        return new Card(CARD_NUMBER, ACCOUNT_ID, CVV, EMBOSSED_NAME, EXPIRY, ACTIVE);
    }

    @Nested
    @DisplayName("The rendering discloses nothing that identifies a card or a cardholder")
    class TheRenderingDisclosesNothingIdentifying {

        @Test
        @DisplayName("the card number does not appear in the rendering, in full or in part, and neither "
                + "does its length")
        void theCardNumberDoesNotAppearInTheRendering() {
            String rendered = card().toString();

            assertThat(rendered).doesNotContain(CARD_NUMBER);
            assertThat(rendered).doesNotContain(CARD_NUMBER.substring(0, 6));
            assertThat(rendered).doesNotContain(CARD_NUMBER.substring(CARD_NUMBER.length() - 4));
            assertThat(rendered).doesNotContain(String.valueOf(CARD_NUMBER.length()));
            assertThat(rendered).contains("cardNum=" + REDACTION_PLACEHOLDER_TEXT);
        }

        @Test
        @DisplayName("no verification code, embossed name, owning account identifier or expiry value "
                + "appears in the rendering")
        void noOtherRegulatedValueAppearsInTheRendering() {
            String rendered = card().toString();

            assertThat(rendered).doesNotContain(CVV, EMBOSSED_NAME, ACCOUNT_ID, EXPIRY);
            assertThat(rendered.toLowerCase(Locale.ROOT))
                    .doesNotContain("cvv", "embossed", "acctid", "expir");
        }

        @Test
        @DisplayName("the rendering is exactly the entity name, the withheld key and the status code, so "
                + "no component can be smuggled in without this assertion failing")
        void theRenderingIsExactlyTheTwoDocumentedComponents() {
            assertThat(card().toString())
                    .isEqualTo("Card[cardNum=" + REDACTION_PLACEHOLDER_TEXT
                            + ", cardActiveStatus='" + ACTIVE + "']");
        }

        @Test
        @DisplayName("the status code is retained, because a diagnostic that cannot say whether a card "
                + "was active explains nothing and the code identifies nobody")
        void theStatusCodeIsRetained() {
            assertThat(card().toString()).contains("cardActiveStatus='Y'");
            assertThat(new Card(CARD_NUMBER, ACCOUNT_ID, CVV, EMBOSSED_NAME, EXPIRY, "N")
                    .toString()).contains("cardActiveStatus='N'");
        }

        @ParameterizedTest
        @ValueSource(strings = {"4111111111111111", "5555555555554444", "0000000000000001",
            "378282246310005"})
        @DisplayName("no card number of any shape reaches the rendering, including a short one and one "
                + "whose significant digits are leading zeros")
        void noCardNumberOfAnyShapeReachesTheRendering(String cardNumber) {
            Card subject = new Card(cardNumber, ACCOUNT_ID, CVV, EMBOSSED_NAME, EXPIRY, ACTIVE);

            assertThat(subject.toString()).doesNotContain(cardNumber);
            assertThat(subject.getCardNum())
                    .as("the accessor still returns the untouched key, which is what code that needs "
                            + "it calls")
                    .isEqualTo(cardNumber);
        }

        @Test
        @DisplayName("two different cards render identically apart from their status, so a rendering "
                + "cannot be used to distinguish one card from another")
        void twoDifferentCardsRenderIdentically() {
            Card first = card();
            Card second = new Card("5555555555554444", "00000000022", "999", "OTHER PERSON",
                    "2030-06-30", ACTIVE);

            assertThat(first.toString()).isEqualTo(second.toString());
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("an unset card renders without throwing, so a diagnostic raised during provider "
                + "instantiation is safe")
        void anUnsetCardRendersWithoutThrowing() {
            assertThat(new Card().toString())
                    .isEqualTo("Card[cardNum=" + REDACTION_PLACEHOLDER_TEXT
                            + ", cardActiveStatus='null']");
        }

        @Test
        @DisplayName("the rendering carries no credential or payment marker of any kind")
        void theRenderingCarriesNoCredentialOrPaymentMarker() {
            String rendered = card().toString();
            String componentList =
                    rendered.substring(rendered.indexOf('[') + 1, rendered.length() - 1);

            assertThat(componentList.toUpperCase(Locale.ROOT))
                    .doesNotContain("PASSWORD", "SECRET", "SSN", "$2A$", "PAN=", "CVV=", "CVC")
                    .doesNotContain("VERSION=");
        }

        @Test
        @DisplayName("the placeholder is a constant and not a transformation, so two cards sharing "
                + "nothing still yield the identical stand-in")
        void thePlaceholderIsAConstantAndNotATransformation() {
            String first = card().toString();
            String second = new Card("0000000000000001", null, null, null, null, ACTIVE).toString();

            assertThat(first).contains(REDACTION_PLACEHOLDER_TEXT);
            assertThat(second).contains(REDACTION_PLACEHOLDER_TEXT);
            assertThat(first.substring(0, first.indexOf(", ")))
                    .isEqualTo(second.substring(0, second.indexOf(", ")));
        }
    }

    @Nested
    @DisplayName("Identity follows the business key alone")
    class IdentityFollowsTheBusinessKeyAlone {

        @Test
        @DisplayName("two cards with the same key are equal and share a hash, whatever else differs")
        void twoCardsWithTheSameKeyAreEqual() {
            Card first = card();
            Card second = new Card(CARD_NUMBER, "00000000099", "111", "SOMEONE ELSE", "2031-01-01",
                    "N");

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("two cards with different keys are unequal even when every other value matches")
        void twoCardsWithDifferentKeysAreUnequal() {
            Card first = card();
            Card second = new Card("4111111111111112", ACCOUNT_ID, CVV, EMBOSSED_NAME, EXPIRY,
                    ACTIVE);

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("mutating a non-key field leaves the hash unchanged, so membership in a hash-based "
                + "collection survives an update")
        void mutatingANonKeyFieldLeavesTheHashUnchanged() {
            Card subject = card();
            Set<Card> members = new HashSet<>();
            members.add(subject);
            int before = subject.hashCode();

            subject.setCardActiveStatus("N");
            subject.setCardEmbossedName("RENAMED PERSON");
            subject.setCardCvvCd("000");

            assertThat(subject.hashCode()).isEqualTo(before);
            assertThat(members).contains(subject);
        }

        @Test
        @DisplayName("the key is compared byte for byte, so a case or space difference is a different "
                + "row exactly as the database sees it")
        void theKeyIsComparedByteForByte() {
            assertThat(new Card(" " + CARD_NUMBER, ACCOUNT_ID, CVV, EMBOSSED_NAME, EXPIRY, ACTIVE))
                    .isNotEqualTo(card());
        }

        @Test
        @DisplayName("a card is unequal to a foreign type and to null, and equal to itself")
        void equalityIsWellBehavedAtItsEdges() {
            Card subject = card();

            assertThat(subject).isEqualTo(subject);
            assertThat(subject).isNotEqualTo(null);
            assertThat(subject).isNotEqualTo("Card[" + CARD_NUMBER + "]");
        }

        @Test
        @DisplayName("two unset cards are equal, because a null key equals a null key, and neither "
                + "renders anything")
        void twoUnsetCardsAreEqual() {
            assertThat(new Card()).isEqualTo(new Card()).hasSameHashCodeAs(new Card());
        }
    }

    @Nested
    @DisplayName("Every value is stored exactly as supplied")
    class EveryValueIsStoredExactlyAsSupplied {

        @Test
        @DisplayName("all six constructor arguments land on their own accessors, so no two same-typed "
                + "fields are transposed")
        void allSixArgumentsLandOnTheirOwnAccessors() {
            Card subject = card();

            assertThat(subject.getCardNum()).isEqualTo(CARD_NUMBER);
            assertThat(subject.getCardAcctId()).isEqualTo(ACCOUNT_ID);
            assertThat(subject.getCardCvvCd()).isEqualTo(CVV);
            assertThat(subject.getCardEmbossedName()).isEqualTo(EMBOSSED_NAME);
            assertThat(subject.getCardExpirationDate()).isEqualTo(EXPIRY);
            assertThat(subject.getCardActiveStatus()).isEqualTo(ACTIVE);
        }

        @Test
        @DisplayName("every setter writes its own field and returns it unchanged, with no value shared "
                + "between two accessors")
        void everySetterWritesItsOwnField() {
            Card subject = new Card();
            subject.setCardNum("1");
            subject.setCardAcctId("2");
            subject.setCardCvvCd("3");
            subject.setCardEmbossedName("4");
            subject.setCardExpirationDate("5");
            subject.setCardActiveStatus("6");

            Map<String, String> readBack = new HashMap<>();
            readBack.put("cardNum", subject.getCardNum());
            readBack.put("cardAcctId", subject.getCardAcctId());
            readBack.put("cardCvvCd", subject.getCardCvvCd());
            readBack.put("cardEmbossedName", subject.getCardEmbossedName());
            readBack.put("cardExpirationDate", subject.getCardExpirationDate());
            readBack.put("cardActiveStatus", subject.getCardActiveStatus());

            assertThat(readBack).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "cardNum", "1", "cardAcctId", "2", "cardCvvCd", "3",
                    "cardEmbossedName", "4", "cardExpirationDate", "5", "cardActiveStatus", "6"));
        }

        @Test
        @DisplayName("a leading zero is never collapsed and a trailing space is never trimmed, because "
                + "the record fields are fixed-width and space-significant")
        void significantPaddingSurvives() {
            Card subject = new Card("0000000000000001", "00000000001", "007", "MARY ANN          ",
                    "2027-12-31", " ");

            assertThat(subject.getCardNum()).isEqualTo("0000000000000001").hasSize(16);
            assertThat(subject.getCardAcctId()).isEqualTo("00000000001").hasSize(11);
            assertThat(subject.getCardCvvCd()).isEqualTo("007");
            assertThat(subject.getCardEmbossedName()).endsWith("          ").hasSize(18);
            assertThat(subject.getCardActiveStatus()).isEqualTo(" ");
        }

        @Test
        @DisplayName("letter case is never folded, because the online program's own comparison folds "
                + "both sides and a stored fold here would hide a real difference")
        void letterCaseIsNeverFolded() {
            Card subject = new Card(CARD_NUMBER, ACCOUNT_ID, CVV, "mary ann", EXPIRY, "y");

            assertThat(subject.getCardEmbossedName()).isEqualTo("mary ann");
            assertThat(subject.getCardActiveStatus()).isEqualTo("y");
        }

        @Test
        @DisplayName("an out-of-vocabulary status code round-trips rather than being rejected, because "
                + "the column carries no check constraint and batch readers take it unchecked")
        void anOutOfVocabularyStatusCodeRoundTrips() {
            Card subject = new Card(CARD_NUMBER, ACCOUNT_ID, CVV, EMBOSSED_NAME, EXPIRY, "X");

            assertThat(subject.getCardActiveStatus()).isEqualTo("X");
        }

        @Test
        @DisplayName("no accessor coerces, parses or normalises the expiry value, which stays the raw "
                + "ten characters and never becomes a date type")
        void theExpiryValueStaysRawCharacters() throws NoSuchMethodException {
            Method accessor = Card.class.getMethod("getCardExpirationDate");

            assertThat(accessor.getReturnType()).isEqualTo(String.class);
            assertThat(new Card(CARD_NUMBER, ACCOUNT_ID, CVV, EMBOSSED_NAME, "2027/12/31", ACTIVE)
                    .getCardExpirationDate()).isEqualTo("2027/12/31");
        }
    }

    @Nested
    @DisplayName("The row version is provider-owned and is not a client-facing concurrency token")
    class TheRowVersionIsProviderOwned {

        @Test
        @DisplayName("the version is readable and has no setter, so no caller can assert a value for it")
        void theVersionIsReadableAndHasNoSetter() {
            assertThat(card().getVersion()).isZero();
            assertThat(List.of(Card.class.getMethods()).stream()
                    .map(Method::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).contains("version"))
                    .toList())
                    .containsExactly("getVersion");
        }

        @Test
        @DisplayName("the version takes no part in identity, so a provider increment cannot move a card "
                + "within a hash-based collection")
        void theVersionTakesNoPartInIdentity() {
            Card first = card();
            Card second = card();

            assertThat(first.getVersion()).isEqualTo(second.getVersion());
            assertThat(first).isEqualTo(second);
            assertThat(first.toString()).doesNotContain("version");
        }
    }
}
