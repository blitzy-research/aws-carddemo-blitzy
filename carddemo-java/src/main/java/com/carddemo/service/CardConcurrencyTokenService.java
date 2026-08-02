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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.carddemo.domain.Card;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.util.CobolStringUtils;

/**
 * Carries the card-update transaction's fetched record image, and the two screen values the terminal
 * protected, across the turn that separates presenting a card for update from confirming it - and
 * refuses the confirmation when the stored record no longer matches what was presented.
 *
 * <h2>Legacy authority</h2>
 *
 * <p>The card-update program {@code app/cbl/COCRDUPC.cbl} does not trust the screen and does not
 * re-derive its comparison state on the confirming turn. Four regions of that program define the
 * behaviour reproduced here.
 *
 * <p><strong>Line 274</strong> declares a program work area whose second group, from line 291, is the
 * fetched image of the card as it stood when the screen was built. <strong>Line 550</strong> moves that
 * work area into the shared communication area returned with the screen, and <strong>lines 392 to
 * 400</strong> slice it back off on the following turn. <strong>Paragraph {@code 9000-READ-DATA}</strong>
 * at line 1344 fills the image from the record it has just fetched. <strong>Paragraph
 * {@code 9300-CHECK-CHANGE-IN-REC}</strong> at line 1498 - reached from {@code 9200-WRITE-PROCESSING}
 * only after the record has been read under lock - compares the freshly locked record against that
 * image and, on any single difference, reports a concurrent change and abandons the write by jumping
 * back to the write-processing exit at line 1518.
 *
 * <p>The design matters more than the mechanism. Re-reading the record at the start of the confirming
 * turn would detect nothing, because the whole purpose of the comparison is to catch a change made
 * <em>after</em> the screen was displayed. The state being compared has to have travelled with the
 * conversation, which is why it travels here too.
 *
 * <h2>Why a row version cannot do this job on its own</h2>
 *
 * <p>The card table carries a version column and the persistence provider checks it when an update is
 * flushed. That check and this one answer different questions and neither replaces the other. The
 * provider's check catches a change made between reading the record for update and writing it. This one
 * catches a change made between <em>presenting</em> the screen and <em>confirming</em> it - the window
 * the legacy work area existed to cover - and a version check cannot see into that window at all,
 * because a confirming request that begins by loading the current row loads the current version with
 * it and then agrees with itself. The version is deliberately <em>not</em> sealed into the token: it
 * never needs to leave the server to do its job, and a value that never leaves cannot be echoed back
 * wrongly. Decision log entry DL-075 records the same reasoning on the account arm.
 *
 * <h2>Why the state is sealed rather than echoed</h2>
 *
 * <p>In the legacy the fetched image was safe to carry because the communication area is held by the
 * transaction manager and the 3270 terminal never sees it. A REST client is not a 3270 terminal:
 * anything it echoes back is under its control. An echoed row version, an echoed entity tag or an
 * echoed fetched image would let a client assert that nothing had changed - which is exactly the check
 * being performed - so the client would be authorising its own overwrite.
 *
 * <p>The token this service mints is therefore <em>opaque</em> and <em>tamper-evident</em> rather than
 * readable. It is sealed inside the module's authenticated-encryption envelope under a binding of its
 * own ({@value #CONCURRENCY_TOKEN_FIELD}), so a client can return it and cannot read it, edit it,
 * fabricate one, or replay one minted for a different card. A token minted for a different purpose or
 * a different column cannot be presented here either, because the binding is sealed inside the
 * authenticated payload and is checked after authentication.
 *
 * <h2>What the digest covers</h2>
 *
 * <p>One digest over the card record. The field list is exactly what paragraph
 * {@code 9300-CHECK-CHANGE-IN-REC} compares at lines 1503 to 1508, in the order it compares them, with
 * the same folding rule, preceded by the two identifiers that bind the token to one row:
 *
 * <ol>
 *   <li>the card number, which is the record key - not compared by the legacy because it is the key it
 *       read by, and included here so that a token minted for one card cannot be presented for
 *       another;</li>
 *   <li>the owning account identifier, which the legacy likewise held in its fetched image (line 1346)
 *       rather than comparing, and which is included for the same binding reason;</li>
 *   <li>the card verification code;</li>
 *   <li>the embossed cardholder name, upper-folded;</li>
 *   <li>the expiry year, being the first four characters of the stored ten-character expiry value;</li>
 *   <li>the expiry month, being characters six and seven;</li>
 *   <li>the expiry day, being characters nine and ten;</li>
 *   <li>the active status code.</li>
 * </ol>
 *
 * <p><strong>Case folding is applied through the estate's own table fold</strong>
 * ({@link CobolStringUtils#asciiUpperFold(String)}), never through {@link String#toUpperCase()}. The
 * legacy folds the embossed name in place at line 1499, immediately before the comparison, having
 * already folded it at line 1357 before capturing the image - so both sides of the comparison are
 * folded and <strong>an edit differing from the fetched value only in letter case is not a change at
 * all</strong>. Reproducing that requires the same 26-character table the legacy declares at lines 261
 * and 263; the platform's own case conversion is locale-sensitive and Unicode-aware and would fold
 * characters the legacy table leaves untouched.
 *
 * <p><strong>The expiry value is compared by its parts, not as a string.</strong> The legacy tests
 * positions 1 to 4, 6 to 7 and 9 to 10 and never the separator positions, so a stored value differing
 * only in its separators is unchanged. No calendar type appears here: nothing is parsed, assembled or
 * checked against a calendar, because none of that is this service's work.
 *
 * <h2>The two protected values the proof carries in full</h2>
 *
 * <p>Two of the screen's fields are protected on the mapset and are pure carry-through, and the legacy
 * write path sources both of them from somewhere other than the operator's keystrokes:
 *
 * <ul>
 *   <li><strong>The owning account identifier.</strong> {@code app/bms/COCRDUP.bms} line 84 declares
 *       the field field-set, insert-cursor, normal-intensity and protected. Paragraph
 *       {@code 9200-WRITE-PROCESSING} at line 1463 writes the account identifier from the work area
 *       value the previous screen established - <em>not</em> from the screen field - so an altered
 *       screen field could never re-point the write at another account.</li>
 *   <li><strong>The expiry day.</strong> {@code app/bms/COCRDUP.bms} line 142 declares the field dark,
 *       field-set and protected: invisible to the operator, impossible to type into, and returned by
 *       the terminal on every submission regardless. The write path at line 1471 assembles the stored
 *       day back into the expiry value from that guaranteed-unaltered echo.</li>
 * </ul>
 *
 * <p>A REST client has neither a work area nor a terminal that enforces a protected attribute, so both
 * values are sealed into this proof and handed back by {@link #verify(String, Card)}. A card-update
 * service therefore takes them from the proof, never from request JSON, which is what restores the
 * property the 3270 attribute byte used to provide. Both are additionally covered by the digest, so a
 * proof whose carried values disagree with the stored record is refused before they are ever returned.
 *
 * <h2>Contract</h2>
 *
 * <p>Callers run the two operations at the two ends of the conversation: {@link #mint(Card)} when the
 * card is presented, {@link #verify(String, Card)} after the card has been read for update and before
 * anything is written. Verification is total - an absent, blank, malformed, foreign or stale token all
 * raise {@link OptimisticLockConflictException} with the legacy arm
 * {@link OptimisticLockConflictException.ConflictKind#RECORD_CHANGED_BEFORE_UPDATE}, whose message is
 * the verbatim legacy text - so there is no way for a caller to obtain the carried values, or to reach
 * a write, without having passed the check.
 *
 * <p>The service is stateless, holds no cache and is safe for concurrent use. Diagnostics name the
 * record type and the reason and never the key, the token, the digest or any field value.
 *
 * <h2>Provenance</h2>
 *
 * <p>Derived from the AWS CardDemo z/OS mainframe application at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Sealing the fetched image is a documented
 * divergence from the legacy design rather than a translation of it, and it is recorded as such by
 * decision {@code DL-109} in {@code docs/decision-log.md}, which also records that the two contract
 * files which formerly declared no concurrency component are overruled by the stale-update parity
 * requirement rather than the other way about. No COBOL statement and no picture clause is
 * reproduced here.
 */
@Service
public class CardConcurrencyTokenService {

    /**
     * Binding name sealed inside every token, so that a token cannot be presented as any other
     * protected value and no other protected value can be presented as a token. Conventionally
     * {@code table.column} elsewhere in the module; here it names the contract rather than a column,
     * because no column stores it.
     */
    public static final String CONCURRENCY_TOKEN_FIELD = "card_update.concurrency_token";

    /**
     * Scheme marker opening every token payload. Present so that a payload produced by a future
     * revision of this contract, or by the account arm's contract, is recognised as foreign rather
     * than silently misread as stale.
     */
    public static final String PAYLOAD_SCHEME = "CCUP1";

    /**
     * Digest algorithm. Every conforming Java runtime provides it, so its absence is a broken
     * installation rather than a runtime condition a caller can handle.
     */
    private static final String DIGEST_ALGORITHM = "SHA-256";

    /**
     * Separator between the parts of a token payload and between the canonicalised fields of the
     * record image. The ASCII unit separator is used because it cannot occur in a hexadecimal digest or
     * in the scheme marker; within the canonical image every field is additionally length-prefixed, so
     * the encoding stays unambiguous even for a field value that somehow contained the separator.
     */
    private static final char UNIT_SEPARATOR = '\u001F';

    /**
     * Length prefix that marks an absent field inside the canonical image, distinguishing it from a
     * field present but empty. The legacy distinguishes those two states throughout, and a canonical
     * form that conflated them would report a change as no change.
     */
    private static final int ABSENT_FIELD = -1;

    /** Marker prefixed to a carried value that is present, including when it is empty. */
    private static final String CARRIED_PRESENT = "+";

    /** Marker standing alone in place of a carried value the record did not hold. */
    private static final String CARRIED_ABSENT = "-";

    /** Number of parts a well-formed payload splits into: scheme, digest, and the two carried values. */
    private static final int PAYLOAD_PART_COUNT = 4;

    /** Index of the scheme marker within a split payload. */
    private static final int SCHEME_PART = 0;

    /** Index of the record digest within a split payload. */
    private static final int DIGEST_PART = 1;

    /** Index of the carried owning-account identifier within a split payload. */
    private static final int ACCOUNT_ID_PART = 2;

    /** Index of the carried expiry day within a split payload. */
    private static final int EXPIRY_DAY_PART = 3;

    /** Zero-based position at which the stored expiry value's year part begins. */
    private static final int EXPIRY_YEAR_OFFSET = 0;

    /** Width of the stored expiry value's year part. */
    private static final int EXPIRY_YEAR_WIDTH = 4;

    /** Zero-based position at which the stored expiry value's month part begins. */
    private static final int EXPIRY_MONTH_OFFSET = 5;

    /** Zero-based position at which the stored expiry value's day part begins. */
    private static final int EXPIRY_DAY_OFFSET = 8;

    /** Width of the stored expiry value's month and day parts, which are equal. */
    private static final int EXPIRY_PART_WIDTH = 2;

    /**
     * Descriptive record name carried by a conflict raised here, matching the naming the conflict type
     * documents.
     */
    private static final String CARD_ENTITY = "Card";

    /**
     * Diagnostic channel. It records that a conflict was refused; it never records a token, a digest, a
     * key or a field value.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(CardConcurrencyTokenService.class);

    /**
     * Seals and opens token payloads. The token is not a stored field, but the sealing requirement is
     * identical - authenticated encryption under a server-held key with an explicit binding - so the
     * module's one implementation of that is reused rather than duplicated.
     */
    private final SensitiveFieldEncryptionService fieldEncryption;

    /**
     * Creates the service.
     *
     * @param fieldEncryption the module's authenticated-encryption service, used to seal and open token
     *                        payloads under {@value #CONCURRENCY_TOKEN_FIELD}; must not be {@code null}
     */
    public CardConcurrencyTokenService(final SensitiveFieldEncryptionService fieldEncryption) {
        this.fieldEncryption = Objects.requireNonNull(fieldEncryption,
                "fieldEncryption must not be null");
    }

    /**
     * Mints the token that describes the card as it stands now, to be returned with the presented
     * screen and echoed back with the confirmation.
     *
     * <p>This is the counterpart of paragraph {@code 9000-READ-DATA} filling the program work area from
     * the record it has just fetched ({@code app/cbl/COCRDUPC.cbl} line 1344 onward), and of line 550
     * moving that work area into the communication area returned with the screen.
     *
     * @param card the card as read for presentation; must not be {@code null}
     * @return the sealed token, never {@code null} and never empty
     * @throws NullPointerException if the card is {@code null}, which is a programming error rather
     *                              than a conflict
     */
    public String mint(final Card card) {
        Objects.requireNonNull(card, "card must not be null");
        final String payload = PAYLOAD_SCHEME
                + UNIT_SEPARATOR + digestOf(canonicalCardImage(card))
                + UNIT_SEPARATOR + carried(card.getCardAcctId())
                + UNIT_SEPARATOR + carried(expiryDayOf(card));
        return fieldEncryption.protect(CONCURRENCY_TOKEN_FIELD, payload);
    }

    /**
     * Verifies that the card still stands as it did when the token was minted, refuses the write
     * otherwise, and returns the two protected values the proof carries.
     *
     * <p>This is paragraph {@code 9300-CHECK-CHANGE-IN-REC}. It is called after the card has been read
     * for update - the point the legacy reaches inside {@code 9200-WRITE-PROCESSING} before line 1453 -
     * and before any field is written. The card passed in must be the freshly read one; verifying
     * against the card the screen was built from would compare a value with itself.
     *
     * @param token the token the client echoed back, or {@code null} when the client sent none
     * @param card  the card as just read for update; must not be {@code null}
     * @return the protected values the proof carries, for the caller to write in place of anything a
     *         client supplied for them; never {@code null}
     * @throws NullPointerException            if the card is {@code null}
     * @throws OptimisticLockConflictException if the token is absent, blank, malformed, sealed for
     *                                         another purpose, or no longer describes the stored card
     */
    public CarriedState verify(final String token, final Card card) {
        Objects.requireNonNull(card, "card must not be null");

        if (token == null || token.isBlank()) {
            throw conflict(card, "no concurrency token was presented");
        }

        final String payload;
        try {
            payload = fieldEncryption.reveal(CONCURRENCY_TOKEN_FIELD, token);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            // Authentication, structure and binding failures are indistinguishable to a client on
            // purpose: all three mean the token cannot be trusted, and saying which one it was would
            // tell a caller how close a forgery came.
            throw conflict(card, "the presented concurrency token could not be authenticated");
        }

        final String[] parts = split(payload);
        if (parts.length != PAYLOAD_PART_COUNT || !PAYLOAD_SCHEME.equals(parts[SCHEME_PART])) {
            throw conflict(card, "the presented concurrency token carries an unrecognised payload");
        }

        if (!digestsMatch(parts[DIGEST_PART], digestOf(canonicalCardImage(card)))) {
            throw conflict(card, "the card record changed after the screen was presented");
        }

        final String accountId = uncarried(parts[ACCOUNT_ID_PART]);
        final String expiryDay = uncarried(parts[EXPIRY_DAY_PART]);
        if (!carried(accountId).equals(parts[ACCOUNT_ID_PART])
                || !carried(expiryDay).equals(parts[EXPIRY_DAY_PART])) {
            throw conflict(card, "the presented concurrency token carries an unreadable protected"
                    + " value");
        }
        return new CarriedState(accountId, expiryDay);
    }

    /**
     * Builds the canonical card image: the two identifiers that bind the token to this row, then the six
     * fields paragraph {@code 9300-CHECK-CHANGE-IN-REC} compares in the order it compares them.
     *
     * @param card the card to canonicalise
     * @return the canonical image, ready to be digested
     */
    private static String canonicalCardImage(final Card card) {
        final StringBuilder image = new StringBuilder(192);
        appendField(image, card.getCardNum());
        appendField(image, card.getCardAcctId());
        appendField(image, card.getCardCvvCd());
        appendField(image, folded(card.getCardEmbossedName()));
        appendField(image, partAt(card.getCardExpirationDate(), EXPIRY_YEAR_OFFSET,
                EXPIRY_YEAR_WIDTH));
        appendField(image, partAt(card.getCardExpirationDate(), EXPIRY_MONTH_OFFSET,
                EXPIRY_PART_WIDTH));
        appendField(image, partAt(card.getCardExpirationDate(), EXPIRY_DAY_OFFSET,
                EXPIRY_PART_WIDTH));
        appendField(image, card.getCardActiveStatus());
        return image.toString();
    }

    /**
     * Extracts the day part of a stored expiry value, which is the value the mapset's dark protected
     * field carries across the turn.
     *
     * @param card the card whose expiry value is read
     * @return the day characters, or {@code null} when the record holds no expiry value
     */
    private static String expiryDayOf(final Card card) {
        return partAt(card.getCardExpirationDate(), EXPIRY_DAY_OFFSET, EXPIRY_PART_WIDTH);
    }

    /**
     * Appends one field to the canonical image, length-prefixed so that no combination of field values
     * can produce the same image as a different combination.
     *
     * @param image the image being built
     * @param value the field value, or {@code null} when the record holds none
     */
    private static void appendField(final StringBuilder image, final String value) {
        image.append(value == null ? ABSENT_FIELD : value.length()).append(UNIT_SEPARATOR);
        if (value != null) {
            image.append(value);
        }
    }

    /**
     * Extracts a fixed-width part of the stored expiry value without demanding that the value be long
     * enough, so a short or absent value is canonicalised rather than raising from a concurrency check.
     *
     * @param value  the stored expiry value, or {@code null}
     * @param from   zero-based start position
     * @param length number of characters wanted
     * @return the requested part, shortened or empty when the value does not reach that far, or
     *         {@code null} when the value itself is absent
     */
    private static String partAt(final String value, final int from, final int length) {
        if (value == null) {
            return null;
        }
        if (from >= value.length()) {
            return "";
        }
        return value.substring(from, Math.min(from + length, value.length()));
    }

    /**
     * Applies the estate's table fold, tolerating an absent value.
     *
     * @param value the field value, or {@code null} when the record holds none
     * @return the folded value, or {@code null}
     */
    private static String folded(final String value) {
        return value == null ? null : CobolStringUtils.asciiUpperFold(value);
    }

    /**
     * Encodes a carried protected value so that an absent value stays distinguishable from an empty
     * one after the payload is split.
     *
     * @param value the value to carry, or {@code null}
     * @return the encoded form, never {@code null}
     */
    private static String carried(final String value) {
        return value == null ? CARRIED_ABSENT : CARRIED_PRESENT + value;
    }

    /**
     * Decodes a carried protected value.
     *
     * <p>An unrecognised marker yields {@code null}, which {@link #verify(String, Card)} detects by
     * re-encoding and comparing rather than by inspecting the marker, so a malformed payload is refused
     * instead of being read as an absent value.
     *
     * @param carried the encoded form recovered from the payload
     * @return the carried value, or {@code null} when the payload marked it absent or is malformed
     */
    private static String uncarried(final String carried) {
        if (CARRIED_ABSENT.equals(carried)) {
            return null;
        }
        if (carried.startsWith(CARRIED_PRESENT)) {
            return carried.substring(CARRIED_PRESENT.length());
        }
        return null;
    }

    /**
     * Digests a canonical image.
     *
     * @param canonicalImage the image to digest
     * @return the digest as lower-case hexadecimal
     * @throws IllegalStateException if the runtime does not provide the digest algorithm, which is a
     *                               broken installation rather than a handleable condition
     */
    private static String digestOf(final String canonicalImage) {
        try {
            final MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            return HexFormat.of()
                    .formatHex(digest.digest(canonicalImage.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(
                    DIGEST_ALGORITHM + " is required and is not available in this runtime",
                    unavailable);
        }
    }

    /**
     * Compares two hexadecimal digests without leaking how far the comparison got.
     *
     * @param presented  the digest recovered from the token
     * @param recomputed the digest of the record as it stands now
     * @return {@code true} when the two are identical
     */
    private static boolean digestsMatch(final String presented, final String recomputed) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                recomputed.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Splits a recovered payload on the unit separator, keeping empty parts so that a truncated payload
     * is recognised as malformed rather than silently accepted.
     *
     * @param payload the recovered payload
     * @return its parts
     */
    private static String[] split(final String payload) {
        return payload.split(String.valueOf(UNIT_SEPARATOR), -1);
    }

    /**
     * Builds the conflict, logging the reason and returning the exception for the caller to throw so
     * that the throw site stays visible.
     *
     * @param card   the card the confirmation was refused for; its key is carried on the exception and
     *               never logged
     * @param reason developer-facing reason, free of keys, tokens, digests and field values
     * @return the conflict to throw
     */
    private static OptimisticLockConflictException conflict(final Card card, final String reason) {
        LOGGER.warn("Refusing card-update write: {}", reason);
        return new OptimisticLockConflictException(
                OptimisticLockConflictException.ConflictKind.RECORD_CHANGED_BEFORE_UPDATE,
                CARD_ENTITY, card.getCardNum());
    }

    /**
     * The two protected screen values a verified proof carries, for a card-update service to write in
     * place of anything a client supplied for them.
     *
     * <p>Both were guaranteed unaltered on a 3270 by an attribute byte the mapset declares - the owning
     * account identifier by {@code PROT} at {@code app/bms/COCRDUP.bms} line 84, the expiry day by
     * {@code DRK,FSET,PROT} at line 142 - and a REST client has no equivalent guarantee, so the values
     * are taken from the sealed proof instead. Because the digest also covers both, a verified proof's
     * carried values necessarily agree with the stored record.
     *
     * @param accountId the owning account identifier the presented card carried, or {@code null} when
     *                  the record held none
     * @param expiryDay the day characters of the presented card's stored expiry value, or {@code null}
     *                  when the record held no expiry value
     */
    public record CarriedState(String accountId, String expiryDay) {
    }
}
