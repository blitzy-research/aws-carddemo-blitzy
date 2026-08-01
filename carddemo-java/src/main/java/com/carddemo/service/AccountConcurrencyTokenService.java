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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.carddemo.domain.Account;
import com.carddemo.domain.Customer;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.util.CobolStringUtils;
import com.carddemo.util.ZonedDecimalCodec;

/**
 * Carries the account-update transaction's old record image across the turn that separates presenting a
 * screen from confirming it, and refuses the confirmation when the stored records no longer match what
 * was presented.
 *
 * <h2>Legacy authority</h2>
 *
 * <p>The account-update program {@code app/cbl/COACTUPC.cbl} does not re-read and compare on a whim: it
 * carries the old image with the conversation and compares under lock. Four regions of that program
 * define the behaviour reproduced here.
 *
 * <p><strong>Line 652</strong> declares a program commarea extension whose first group, from line 669,
 * is the complete old image of the account and the customer as they stood when the screen was built.
 * <strong>Lines 1010 to 1018</strong> append that extension to the shared commarea and return it with
 * the screen; <strong>lines 888 to 892</strong> slice it back off on the following turn. <strong>Line
 * 3894 onward</strong> reads both records for update, taking a lock on each, and only then
 * <strong>paragraph {@code 9700-CHECK-CHANGE-IN-REC}</strong> compares the freshly locked records field
 * by field against that old image, abandoning the write on any single difference.
 *
 * <p>The design matters more than the mechanism. Re-reading the records at the start of the
 * confirmation turn would detect nothing, because the whole purpose of the comparison is to catch a
 * change made <em>after</em> the screen was displayed. The state being compared has to have travelled
 * with the conversation, which is why it travels here too.
 *
 * <h2>Why the state is sealed rather than echoed</h2>
 *
 * <p>In the legacy the old image was safe to carry because the commarea is held by the transaction
 * manager and the 3270 terminal never sees it. A REST client is not a 3270 terminal: anything it echoes
 * back is under its control. An echoed row version or an echoed old image would let a client assert that
 * nothing had changed, which is exactly the check being performed, so the client would be authorising
 * its own overwrite.
 *
 * <p>The token this service mints is therefore <em>opaque</em> and <em>tamper-evident</em> rather than
 * readable. It carries no record content: it carries two digests, sealed inside the module's
 * authenticated-encryption envelope under a binding of its own
 * ({@value #CONCURRENCY_TOKEN_FIELD}), so that a client can return it and cannot read it, edit it,
 * fabricate one, or replay one minted for a different pairing of records. A token minted for a
 * different purpose or a different column cannot be presented here either, because the binding is
 * sealed inside the authenticated payload and is checked after authentication.
 *
 * <h2>What the digests cover</h2>
 *
 * <p>One digest per record, so a conflict can name the record that moved. The field lists are exactly
 * those paragraph {@code 9700-CHECK-CHANGE-IN-REC} compares, in the order it compares them, with the
 * same folding rules:
 *
 * <ul>
 *   <li><strong>Account</strong> - the identifier, the active status, the five monetary amounts, the
 *       three dates by their year, month and day parts, the group identifier folded to upper case
 *       because the legacy compares that field through {@code FUNCTION LOWER-CASE} on both sides, and
 *       finally the postal code.</li>
 *   <li><strong>Customer</strong> - the identifier, the three name parts, the three address lines and
 *       the state and country codes, all folded because the legacy compares them through
 *       {@code FUNCTION UPPER-CASE} on both sides; then the postal code, both telephone numbers, the
 *       national identifier and the government-issued identifier as stored, the date of birth by its
 *       year, month and day parts, the electronic-funds account identifier, the primary-card-holder
 *       indicator and the credit score. Between them these are every data field of the 500-byte
 *       record, so the customer arm has no blind spot to close.</li>
 * </ul>
 *
 * <p><strong>Case folding is applied through the estate's own table fold</strong>
 * ({@link CobolStringUtils#asciiUpperFold(String)}), never through {@link String#toUpperCase()}, so the
 * equivalence classes are the strict 26-letter ones the legacy tables define. Folding to upper where the
 * legacy folded to lower is deliberate and behaviour-preserving: both sides of a comparison are folded,
 * so the direction chosen cannot change which pairs compare equal.
 *
 * <p><strong>Monetary amounts are canonicalised through the codec</strong>
 * ({@link ZonedDecimalCodec#toMonetaryScale(BigDecimal)}) so that a value differing only in trailing
 * zeros is recognised as unchanged. The legacy compares two zoned {@code V99} fields, which is a
 * numeric comparison; comparing {@link BigDecimal} instances by their own equality would have made
 * {@code 5000.0} and {@code 5000.00} look like a change.
 *
 * <p><strong>Dates are compared by their parts, not as strings.</strong> The legacy tests positions 1 to
 * 4, 6 to 7 and 9 to 10 of a {@code CCYY-MM-DD} field and never the separator positions, so a value
 * that differs only in its separators is unchanged. The old-image copy holds the same date compacted to
 * eight characters, which is why the offsets differ between the two sides of the legacy comparison; here
 * both sides are read from the same accessor, so only the omission of the separators has to be
 * reproduced.
 *
 * <h2>Two documented divergences</h2>
 *
 * <p>Decision log entry DL-074 records why the token is a sealed digest pair rather than an echoed
 * version number; the three entries that follow it record the divergences below.
 *
 * <p><strong>The postal code is compared although the legacy omits it.</strong> Paragraph
 * {@code 9700-CHECK-CHANGE-IN-REC} compares ten of the account record's twelve data fields; the
 * identifier is the key, and the postal code is simply absent from the list, so the legacy silently
 * overwrites a concurrent change to that one field. It is included here, because the only reason to
 * compare an old image at all is to avoid overwriting someone else's work, and the direction of the
 * difference is refusing to overwrite rather than overwriting silently (decision log entry DL-076).
 *
 * <p><strong>The two protected identifiers are digested as stored.</strong> The legacy compares the
 * national identifier verbatim and the government-issued identifier through an upper fold, both in
 * cleartext; the migrated columns hold both protected, and this service digests the protected values
 * rather than decrypting them. Any change to either cleartext still reads as a change. Two divergences
 * follow, and both fail towards refusing the write: a case-only change to the government-issued
 * identifier reads as a change where the legacy fold would have seen none, and re-sealing an identical
 * value under a fresh vector also reads as a change. Both are preferable to a concurrency check that
 * handles regulated cleartext, and neither can cause an overwrite (decision log entry DL-077).
 *
 * <p><strong>Where the row version fits.</strong> The account table carries a version column and the
 * persistence provider checks it when the update is flushed. That check and this one answer different
 * questions and neither replaces the other: the provider's check catches a change made between reading
 * the record for update and writing it, while this one catches a change made between presenting the
 * screen and confirming it, which is the window the legacy commarea existed to cover. The version is
 * deliberately <em>not</em> sealed into the token, because it never needs to leave the server to do its
 * job, and a value that never leaves cannot be echoed back wrongly (decision log entry DL-075).
 *
 * <h2>Contract</h2>
 *
 * <p>Callers run the two operations at the two ends of the conversation: {@link #mint(Account, Customer)}
 * when the record is presented, {@link #verify(String, Account, Customer)} after both records have been
 * read for update and before anything is written. Verification is total - an absent, blank, malformed,
 * foreign or stale token all raise {@link OptimisticLockConflictException} with the legacy arm
 * {@link OptimisticLockConflictException.ConflictKind#RECORD_CHANGED_BEFORE_UPDATE}, whose message is
 * the verbatim legacy text - so there is no way for a caller to reach a write without having passed the
 * check.
 *
 * <p>The service is stateless, holds no cache and is safe for concurrent use. Diagnostics name the
 * record type and the reason and never the key, the token, a digest or any field value, which is the same
 * rule decision D-16 applies to the decimal codec.
 *
 * <h2>Provenance</h2>
 *
 * <p>Derived from the AWS CardDemo z/OS mainframe application at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Sealing the old image is a documented
 * divergence from the legacy design rather than a translation of it, and it is recorded as such in
 * {@code docs/decision-log.md}.
 */
@Service
public class AccountConcurrencyTokenService {

    /**
     * Binding name sealed inside every token, so that a token cannot be presented as any other
     * protected value and no other protected value can be presented as a token. Conventionally
     * {@code table.column} elsewhere in the module; here it names the contract rather than a column,
     * because no column stores it.
     */
    public static final String CONCURRENCY_TOKEN_FIELD = "account_update.concurrency_token";

    /**
     * Scheme marker opening every token payload. Present so that a payload produced by a future
     * revision of this contract is recognised as foreign rather than silently misread as stale.
     */
    public static final String PAYLOAD_SCHEME = "ACUP1";

    /**
     * Digest algorithm. Every conforming Java runtime provides it, so its absence is a broken
     * installation rather than a runtime condition a caller can handle.
     */
    private static final String DIGEST_ALGORITHM = "SHA-256";

    /**
     * Separator between the parts of a token payload and between the canonicalised fields of a record.
     * The ASCII unit separator is used because it cannot occur in a hexadecimal digest or in the scheme
     * marker; within a canonical record image every field is additionally length-prefixed, so the
     * encoding stays unambiguous even for a field value that somehow contained the separator.
     */
    private static final char UNIT_SEPARATOR = '\u001F';

    /**
     * Length prefix that marks an absent field, distinguishing it from a field present but empty. The
     * legacy distinguishes those two states throughout, and a canonical form that conflated them would
     * report a change as no change.
     */
    private static final int ABSENT_FIELD = -1;

    /**
     * Descriptive record name carried by a conflict raised on the account arm, matching the naming the
     * conflict type documents.
     */
    private static final String ACCOUNT_ENTITY = "Account";

    /**
     * Descriptive record name carried by a conflict raised on the customer arm.
     */
    private static final String CUSTOMER_ENTITY = "Customer";

    /**
     * Diagnostic channel. It records that a conflict was refused and which record moved; it never
     * records a token, a digest, a key or a field value.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountConcurrencyTokenService.class);

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
    public AccountConcurrencyTokenService(final SensitiveFieldEncryptionService fieldEncryption) {
        this.fieldEncryption = Objects.requireNonNull(fieldEncryption,
                "fieldEncryption must not be null");
    }

    /**
     * Mints the token that describes both records as they stand now, to be returned with the presented
     * screen and echoed back with the confirmation.
     *
     * <p>This is the counterpart of the legacy program filling its commarea extension from the records
     * it has just read ({@code app/cbl/COACTUPC.cbl} line 669 onward, populated before the screen is
     * sent at lines 1010 to 1018).
     *
     * @param account  the account as read for presentation; must not be {@code null}
     * @param customer the customer as read for presentation; must not be {@code null}
     * @return the sealed token, never {@code null} and never empty
     * @throws NullPointerException if either record is {@code null}, which is a programming error
     *                              rather than a conflict
     */
    public String mint(final Account account, final Customer customer) {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(customer, "customer must not be null");
        final String payload = PAYLOAD_SCHEME
                + UNIT_SEPARATOR + digestOf(canonicalAccountImage(account))
                + UNIT_SEPARATOR + digestOf(canonicalCustomerImage(customer));
        return fieldEncryption.protect(CONCURRENCY_TOKEN_FIELD, payload);
    }

    /**
     * Verifies that both records still stand as they did when the token was minted, and refuses the
     * write otherwise.
     *
     * <p>This is paragraph {@code 9700-CHECK-CHANGE-IN-REC}. It is called after both records have been
     * read for update, which is the point the legacy reaches at line 3894 onward, and before any field
     * is written. The records passed in must be the freshly read ones; verifying against the records the
     * screen was built from would compare a value with itself.
     *
     * @param token    the token the client echoed back, or {@code null} when the client sent none
     * @param account  the account as just read for update; must not be {@code null}
     * @param customer the customer as just read for update; must not be {@code null}
     * @throws NullPointerException            if either record is {@code null}
     * @throws OptimisticLockConflictException if the token is absent, blank, malformed, sealed for
     *                                         another purpose, or no longer describes the records
     */
    public void verify(final String token, final Account account, final Customer customer) {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(customer, "customer must not be null");

        if (token == null || token.isBlank()) {
            throw conflict(ACCOUNT_ENTITY, account.getAcctId(),
                    "no concurrency token was presented");
        }

        final String payload;
        try {
            payload = fieldEncryption.reveal(CONCURRENCY_TOKEN_FIELD, token);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            // Authentication, structure and binding failures are indistinguishable to a client on
            // purpose: all three mean the token cannot be trusted, and saying which one it was would
            // tell a caller how close a forgery came.
            throw conflict(ACCOUNT_ENTITY, account.getAcctId(),
                    "the presented concurrency token could not be authenticated");
        }

        final String[] parts = split(payload);
        if (parts.length != 3 || !PAYLOAD_SCHEME.equals(parts[0])) {
            throw conflict(ACCOUNT_ENTITY, account.getAcctId(),
                    "the presented concurrency token carries an unrecognised payload");
        }

        if (!digestsMatch(parts[1], digestOf(canonicalAccountImage(account)))) {
            throw conflict(ACCOUNT_ENTITY, account.getAcctId(),
                    "the account record changed after the screen was presented");
        }
        if (!digestsMatch(parts[2], digestOf(canonicalCustomerImage(customer)))) {
            throw conflict(CUSTOMER_ENTITY, customer.getCustId(),
                    "the customer record changed after the screen was presented");
        }
    }

    /**
     * Builds the canonical account image: the identifier that binds the token to this row, then the ten
     * fields paragraph {@code 9700-CHECK-CHANGE-IN-REC} compares in the order it compares them, then the
     * postal code the legacy omits.
     *
     * @param account the account to canonicalise
     * @return the canonical image, ready to be digested
     */
    private static String canonicalAccountImage(final Account account) {
        final StringBuilder image = new StringBuilder(256);
        appendField(image, account.getAcctId());
        appendField(image, account.getAcctActiveStatus());
        appendField(image, monetary(account.getAcctCurrBal()));
        appendField(image, monetary(account.getAcctCreditLimit()));
        appendField(image, monetary(account.getAcctCashCreditLimit()));
        appendField(image, monetary(account.getAcctCurrCycCredit()));
        appendField(image, monetary(account.getAcctCurrCycDebit()));
        appendDateParts(image, account.getAcctOpenDate());
        appendDateParts(image, account.getAcctExpirationDate());
        appendDateParts(image, account.getAcctReissueDate());
        appendField(image, folded(account.getAcctGroupId()));
        appendField(image, account.getAcctAddrZip());
        return image.toString();
    }

    /**
     * Builds the canonical customer image: the identifier that binds the token to this row, then the
     * seventeen fields paragraph {@code 9700-CHECK-CHANGE-IN-REC} compares in the order it compares them,
     * which between them account for every data field of the 500-byte record.
     *
     * @param customer the customer to canonicalise
     * @return the canonical image, ready to be digested
     */
    private static String canonicalCustomerImage(final Customer customer) {
        final StringBuilder image = new StringBuilder(512);
        appendField(image, customer.getCustId());
        appendField(image, folded(customer.getFirstName()));
        appendField(image, folded(customer.getMiddleName()));
        appendField(image, folded(customer.getLastName()));
        appendField(image, folded(customer.getAddrLine1()));
        appendField(image, folded(customer.getAddrLine2()));
        appendField(image, folded(customer.getAddrLine3()));
        appendField(image, folded(customer.getAddrStateCd()));
        appendField(image, folded(customer.getAddrCountryCd()));
        appendField(image, customer.getAddrZip());
        appendField(image, customer.getPhoneNum1());
        appendField(image, customer.getPhoneNum2());
        appendField(image, customer.getCustSsn());
        appendField(image, customer.getGovtIssuedId());
        appendDateParts(image, customer.getCustDob());
        appendField(image, customer.getEftAccountId());
        appendField(image, customer.getPriCardHolderInd());
        appendField(image, customer.getFicoCreditScore());
        return image.toString();
    }

    /**
     * Appends one field to a canonical image, length-prefixed so that no combination of field values can
     * produce the same image as a different combination.
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
     * Appends a date as the three parts the legacy compares - positions 1 to 4, 6 to 7 and 9 to 10 - and
     * never the separator positions, so a value differing only in its separators is unchanged. Each part
     * is appended as its own length-prefixed field, so a short or malformed value cannot collide with a
     * well-formed one.
     *
     * @param image the image being built
     * @param date  the date value, or {@code null} when the record holds none
     */
    private static void appendDateParts(final StringBuilder image, final String date) {
        if (date == null) {
            appendField(image, null);
            appendField(image, null);
            appendField(image, null);
            return;
        }
        appendField(image, partAt(date, 0, 4));
        appendField(image, partAt(date, 5, 2));
        appendField(image, partAt(date, 8, 2));
    }

    /**
     * Extracts a fixed-width part of a date value without demanding that the value be long enough, so a
     * short or absent date is canonicalised rather than raising from a concurrency check.
     *
     * @param value  the date value
     * @param from   zero-based start position
     * @param length number of characters wanted
     * @return the requested part, shortened or empty when the value does not reach that far
     */
    private static String partAt(final String value, final int from, final int length) {
        if (from >= value.length()) {
            return "";
        }
        return value.substring(from, Math.min(from + length, value.length()));
    }

    /**
     * Canonicalises a monetary amount to the estate's scale so that a value differing only in trailing
     * zeros is recognised as unchanged.
     *
     * @param value the amount, or {@code null} when the record holds none
     * @return the amount at the monetary scale as a plain string, or {@code null}
     */
    private static String monetary(final BigDecimal value) {
        return value == null ? null : ZonedDecimalCodec.toMonetaryScale(value).toPlainString();
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
     * @param entityName descriptive name of the record that moved
     * @param key        the record's business key, carried on the exception and never logged
     * @param reason     developer-facing reason, free of keys, tokens, digests and field values
     * @return the conflict to throw
     */
    private static OptimisticLockConflictException conflict(final String entityName, final String key,
            final String reason) {
        LOGGER.warn("Refusing account-update write on the {} arm: {}", entityName, reason);
        return new OptimisticLockConflictException(
                OptimisticLockConflictException.ConflictKind.RECORD_CHANGED_BEFORE_UPDATE,
                entityName, key);
    }
}
