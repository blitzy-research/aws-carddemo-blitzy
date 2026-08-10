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
package com.carddemo.api;

import com.carddemo.domain.Customer;
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.SensitiveFieldEncryptionService;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The single boundary at which the account screens' regulated values are sealed on the way into
 * storage and revealed &mdash; or masked &mdash; on the way out to a client.
 *
 * <p><strong>The gap this closes.</strong> Two contracts met here and did not fit. The account screens
 * carry a national identifier and a government-issued identifier as cleartext characters, because that
 * is what the 3270 screen displayed: {@code app/cbl/COACTVWC.cbl:L495-L504} composes the nine stored
 * digits into a dashed twelve-character screen item, and the update screen declares the same digits as
 * three separate positions of three, two and four characters at {@code app/cpy-bms/COACTUP.CPY:L168},
 * {@code L174} and {@code L180}. {@link Customer}, by contrast, refuses cleartext outright: both columns
 * accept only a value carrying the module's protected-value envelope, so a direct mapping either throws
 * on the way in or publishes ciphertext on the way out. Neither is acceptable, and nothing bridged them.
 * This class is that bridge, and it is the only place either direction happens.
 *
 * <p><strong>Why revealing is gated rather than automatic, and why that is a deliberate divergence.</strong>
 * The legacy system showed the full identifier to any signed-on operator who reached the account view
 * transaction; it had no field-level protection at all, and the specification records that gap in
 * &sect;0.7.4 rather than pretending otherwise. Reproducing it would mean publishing a national
 * identifier in a JSON body to every authenticated caller. The specification's own tie-break governs
 * this: a divergence is licensed when an external constraint compels it, and a regulated-data
 * constraint does. So the value is <em>masked by default</em> and revealed only when an authorization
 * this class can check permits it. That is a documented parity exception of exactly the kind already
 * taken for credential hashing, and it is recorded in {@code docs/decision-log.md} rather than left for
 * a reviewer to discover.
 *
 * <p><strong>What "purpose-limited" means concretely.</strong> A caller must name the account operation
 * it is serving, and {@link RevealPurpose} declares only the two that exist &mdash; the account view
 * transaction {@code CAVW} and the account update transaction {@code CAUP}. There is no general-purpose
 * reveal and no way to ask for one: a caller with no account operation to serve has no constant to pass.
 *
 * <p><strong>What "role-gated" means concretely, and what it deliberately does not claim.</strong> An
 * administrator may reveal; any other user type may reveal only for a record whose ownership the caller
 * has already established. Role is a legacy concept &mdash; {@code SEC-USR-TYPE} of
 * {@code app/cpy/CSUSR01Y.cpy} drives the same split at sign-on &mdash; so gating on it invents nothing.
 * Ownership is <strong>not</strong> a legacy concept: the user-security record carries no account
 * linkage, and no program in the estate checks one. It is therefore modelled as a flag the caller
 * asserts rather than as an ownership model this class invents, so that a caller which genuinely
 * establishes ownership can say so, and a caller which cannot gets masked values instead of an
 * assumption. The flag is named for what it is, and this class never derives it.
 *
 * <p><strong>Two rules hold on every path out.</strong> No envelope ever leaves: every revealed value is
 * checked against the envelope shape before it is returned, and a stored value that is not an envelope
 * is refused rather than published, so a cleartext identifier that reached the column by some other
 * route cannot escape through this class either. And no cleartext ever leaves on a denied path: the mask
 * is built without consulting the revealed value at all, except for the last four digits of the national
 * identifier, which are retained deliberately so an operator can confirm an identity without seeing it.
 * The government-issued identifier keeps no digits, because no convention licenses partial exposure of a
 * national identity document number.
 *
 * <p><strong>Scope.</strong> Exactly the values the two findings name are gated: the national
 * identifier, the government-issued identifier, the electronic-funds account identifier and the date of
 * birth. The screens' other content &mdash; names, address, telephone numbers, the credit score and the
 * monetary amounts &mdash; is the ordinary business content of an account-servicing screen and is not
 * masked, because masking it would leave the screen unable to perform the function it exists for.
 *
 * <p>Stateless apart from the injected cipher, holding no mutable field, so the singleton is safe for
 * unsynchronised concurrent use.
 *
 * @since 1.0.0
 */
@Component
public final class AccountProtectedDataAdapter {

    /** Number of digits the stored national identifier carries, from {@code app/cpy/CVCUS01Y.cpy}. */
    public static final int SSN_DIGIT_COUNT = 9;

    /** Width of the first screen position of the national identifier, {@code app/cpy-bms/COACTUP.CPY:L168}. */
    public static final int SSN_PART_1_WIDTH = 3;

    /** Width of the second screen position, {@code app/cpy-bms/COACTUP.CPY:L174}. */
    public static final int SSN_PART_2_WIDTH = 2;

    /** Width of the third screen position, {@code app/cpy-bms/COACTUP.CPY:L180}. */
    public static final int SSN_PART_3_WIDTH = 4;

    /** The separator the view screen's composition writes between the parts. */
    public static final String SSN_PART_SEPARATOR = "-";

    /** The character every masked position is filled with. */
    public static final String MASK_CHARACTER = "*";

    /**
     * Everything of the view screen's composed national identifier that precedes its final four digits,
     * masked: three mask characters, a separator, two mask characters and the second separator.
     *
     * <p>Seven characters, and the four retained digits appended by {@link #maskedComposedSsn(String)}
     * bring the masked item to the eleven the revealed one occupies. The composition at
     * {@code app/cbl/COACTVWC.cbl:L495-L504} writes three digits, a separator, two digits, a separator
     * and four digits into a twelve-character screen item, so a mask of the same width occupies the
     * field identically and no client has to lay the screen out differently for a masked value.
     *
     * <p>A single literal rather than the three pieces assembled at each use. Per-position mask
     * constants existed here and nothing read them: the assembly is only ever performed once, at one
     * call site, so the pieces were a second declaration of the same fact and could have drifted from
     * the literal that was actually emitted.
     */
    private static final String SSN_VIEW_MASK_PREFIX = "***-**-";

    /** The cipher that seals and reveals the two protected columns, injected rather than reimplemented. */
    private final SensitiveFieldEncryptionService encryption;

    /**
     * @param encryption the field-encryption service that owns the envelope scheme and the key
     */
    public AccountProtectedDataAdapter(final SensitiveFieldEncryptionService encryption) {
        this.encryption = Objects.requireNonNull(encryption, "encryption must not be null");
    }

    /**
     * The account operation a reveal is being requested for.
     *
     * <p>Only the two account transactions the estate declares are named. The enumeration exists so
     * that a reveal cannot be requested without naming an operation that legitimately displays these
     * values, which is what makes the reveal purpose-limited rather than merely authenticated.
     */
    public enum RevealPurpose {

        /** Legacy transaction {@code CAVW}, driven by {@code app/cbl/COACTVWC.cbl}. */
        ACCOUNT_VIEW,

        /** Legacy transaction {@code CAUP}, driven by {@code app/cbl/COACTUPC.cbl}. */
        ACCOUNT_UPDATE
    }

    /**
     * One caller's authority to see the regulated values of one record, for one operation.
     *
     * <p>Constructed by the caller and checked here. The record deliberately carries no identifier of
     * its own: it is not a token, it is not carried on the wire, and it must never be built from a
     * client-echoed value. A caller assembles it from the authenticated principal and from an ownership
     * determination it made itself.
     *
     * @param purpose the account operation being served, never {@code null}
     * @param userType the type of the authenticated principal, or {@code null} when none is established
     * @param recordOwnershipVerified whether the caller established that this principal owns this
     *        record; the caller asserts this, and this class never derives it
     */
    public record RevealAuthorization(RevealPurpose purpose,
                                      UserType userType,
                                      boolean recordOwnershipVerified) {

        /**
         * Rejects an authorization that names no operation, since an unnamed purpose is not a purpose.
         */
        public RevealAuthorization {
            Objects.requireNonNull(purpose, "purpose must not be null");
        }

        /**
         * Builds the authority of an administrator, who may reveal without an ownership determination.
         *
         * <p>From the sign-on split at {@code app/cbl/COSGN00C.cbl:L227-L236}, where the administrator
         * type reaches the administrative surface and every other type does not.
         *
         * @param purpose the account operation being served
         * @return an administrator's authority for that operation
         */
        public static RevealAuthorization administrator(final RevealPurpose purpose) {
            return new RevealAuthorization(purpose, UserType.ADMIN, false);
        }

        /**
         * Builds the authority of a non-administrator for a record whose ownership the caller
         * established.
         *
         * @param purpose the account operation being served
         * @param userType the type of the authenticated principal
         * @return an authority that permits revealing this record's values
         */
        public static RevealAuthorization owner(final RevealPurpose purpose,
                                                final UserType userType) {
            return new RevealAuthorization(purpose, userType, true);
        }

        /**
         * Builds the authority of a caller that established neither administrator role nor ownership,
         * which is the default and yields masked values.
         *
         * @param purpose the account operation being served
         * @param userType the type of the authenticated principal, possibly {@code null}
         * @return an authority that permits nothing to be revealed
         */
        public static RevealAuthorization unprivileged(final RevealPurpose purpose,
                                                      final UserType userType) {
            return new RevealAuthorization(purpose, userType, false);
        }

        /**
         * Reports whether this authority permits the regulated values to be revealed.
         *
         * <p>Administrator role or an established ownership determination. An absent user type is not
         * the administrator type and so does not permit a reveal on its own.
         *
         * @return {@code true} when the values may be revealed rather than masked
         */
        public boolean permitsReveal() {
            return userType == UserType.ADMIN || recordOwnershipVerified;
        }
    }

    /**
     * The four regulated values of the account view screen, either revealed or masked.
     *
     * @param ssn the national identifier as the view screen composes it, or its mask
     * @param dateOfBirth the date of birth, or its mask
     * @param governmentIssuedId the government-issued identifier, or its mask
     * @param eftAccountId the electronic-funds account identifier, or its mask
     */
    public record AccountViewProtectedValues(String ssn,
                                             String dateOfBirth,
                                             String governmentIssuedId,
                                             String eftAccountId) {
    }

    /**
     * The eight regulated values of the account update screen, either revealed or masked.
     *
     * <p>The national identifier and the date of birth arrive as separate screen positions on this
     * screen rather than as composed items, which is why there are eight values here and four on the
     * view screen.
     *
     * @param ssnPart1 the first three digits of the national identifier, or their mask
     * @param ssnPart2 the middle two digits, or their mask
     * @param ssnPart3 the final four digits, or their mask
     * @param dateOfBirthYear the birth year, or its mask
     * @param dateOfBirthMonth the birth month, or its mask
     * @param dateOfBirthDay the birth day, or its mask
     * @param governmentIssuedId the government-issued identifier, or its mask
     * @param eftAccountId the electronic-funds account identifier, or its mask
     */
    public record AccountUpdateProtectedValues(String ssnPart1,
                                               String ssnPart2,
                                               String ssnPart3,
                                               String dateOfBirthYear,
                                               String dateOfBirthMonth,
                                               String dateOfBirthDay,
                                               String governmentIssuedId,
                                               String eftAccountId) {
    }

    /**
     * The two sealed identifiers, ready for the columns that accept only envelopes.
     *
     * @param custSsn the sealed national identifier, or {@code null} when none was supplied
     * @param govtIssuedId the sealed government-issued identifier, or {@code null}
     */
    public record SealedCustomerIdentifiers(String custSsn, String govtIssuedId) {
    }

    // ------------------------------------------------------------------------------------------
    // Outbound: reveal under authority, mask otherwise
    // ------------------------------------------------------------------------------------------

    /**
     * Produces the account view screen's four regulated values from a stored customer record.
     *
     * @param customer the stored record, never {@code null}
     * @param authorization the caller's authority for this record and operation, never {@code null}
     * @return the four values, revealed when the authority permits it and masked otherwise
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalStateException if a stored protected column does not carry an envelope, or if a
     *         revealed value still carries one
     */
    public AccountViewProtectedValues revealForView(final Customer customer,
                                                    final RevealAuthorization authorization) {
        Objects.requireNonNull(customer, "customer must not be null");
        Objects.requireNonNull(authorization, "authorization must not be null");

        final String ssnDigits = revealedSsnDigits(customer);
        if (authorization.permitsReveal()) {
            return new AccountViewProtectedValues(
                    composedSsn(ssnDigits),
                    customer.getCustDob(),
                    revealedGovernmentIssuedId(customer),
                    customer.getEftAccountId());
        }
        return new AccountViewProtectedValues(
                maskedComposedSsn(ssnDigits),
                maskOf(customer.getCustDob()),
                maskOf(revealedGovernmentIssuedId(customer)),
                maskOf(customer.getEftAccountId()));
    }

    /**
     * Produces the account update screen's eight regulated values from a stored customer record.
     *
     * @param customer the stored record, never {@code null}
     * @param authorization the caller's authority for this record and operation, never {@code null}
     * @return the eight values, revealed when the authority permits it and masked otherwise
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalStateException if a stored protected column does not carry an envelope, or if a
     *         revealed value still carries one
     */
    public AccountUpdateProtectedValues revealForUpdate(final Customer customer,
                                                        final RevealAuthorization authorization) {
        Objects.requireNonNull(customer, "customer must not be null");
        Objects.requireNonNull(authorization, "authorization must not be null");

        final String ssnDigits = revealedSsnDigits(customer);
        final String birthDate = customer.getCustDob();
        if (authorization.permitsReveal()) {
            return new AccountUpdateProtectedValues(
                    ssnPart1(ssnDigits),
                    ssnPart2(ssnDigits),
                    ssnPart3(ssnDigits),
                    birthDatePart(birthDate, 0, 4),
                    birthDatePart(birthDate, 5, 7),
                    birthDatePart(birthDate, 8, 10),
                    revealedGovernmentIssuedId(customer),
                    customer.getEftAccountId());
        }
        return new AccountUpdateProtectedValues(
                maskOf(ssnPart1(ssnDigits)),
                maskOf(ssnPart2(ssnDigits)),
                ssnPart3(ssnDigits),
                maskOf(birthDatePart(birthDate, 0, 4)),
                maskOf(birthDatePart(birthDate, 5, 7)),
                maskOf(birthDatePart(birthDate, 8, 10)),
                maskOf(revealedGovernmentIssuedId(customer)),
                maskOf(customer.getEftAccountId()));
    }

    /**
     * Applies the same gate to the update screen's eight regulated values when they arrive already
     * revealed rather than as a stored record.
     *
     * <p><strong>Why this form exists alongside {@link #revealForUpdate(Customer, RevealAuthorization)}.</strong>
     * The update transaction is reproduced paragraph for paragraph in the service layer, and that layer
     * legitimately holds the cleartext: it compares every typed field against the stored value to decide
     * whether a change occurred, and the national identifier and the government-issued identifier are two
     * of the fields it compares. The layering direction forbids a service from naming this class, so the
     * service cannot apply the gate itself, and the boundary that can does not receive the record - it
     * receives the screen the service composed. This method is the gate for exactly that shape.
     *
     * <p>The masking is the same masking, produced by the same helper, so the two forms cannot come to
     * disagree about what a masked value looks like. The one asymmetry is deliberate and matches
     * {@link #revealForUpdate}: the final four digits of the national identifier are retained, so an
     * operator can confirm an identity without seeing it, and nothing else keeps any digit.
     *
     * <p><strong>What it does not and cannot check.</strong> It cannot verify that the values handed to it
     * came out of an envelope, because they arrive already revealed. That check belongs to whoever read
     * the column, and the service that does so reads it through the field-encryption service, which
     * refuses a value that is not an envelope. The guarantee this method adds is the other one: on a
     * denied path no cleartext leaves, because every returned value is built from the mask helper rather
     * than from the value it replaces.
     *
     * @param cleartext the eight values as the screen composed them, never {@code null}; individual
     *                  components may be {@code null} when the screen presents no record
     * @param authorization the caller's authority for this record and operation, never {@code null}
     * @return the same eight values when the authority permits a reveal, and their masks otherwise
     * @throws NullPointerException if either argument is {@code null}
     */
    public AccountUpdateProtectedValues gateForUpdate(final AccountUpdateProtectedValues cleartext,
                                                      final RevealAuthorization authorization) {
        Objects.requireNonNull(cleartext, "cleartext must not be null");
        Objects.requireNonNull(authorization, "authorization must not be null");

        if (authorization.permitsReveal()) {
            return cleartext;
        }
        return new AccountUpdateProtectedValues(
                maskOf(cleartext.ssnPart1()),
                maskOf(cleartext.ssnPart2()),
                cleartext.ssnPart3(),
                maskOf(cleartext.dateOfBirthYear()),
                maskOf(cleartext.dateOfBirthMonth()),
                maskOf(cleartext.dateOfBirthDay()),
                maskOf(cleartext.governmentIssuedId()),
                maskOf(cleartext.eftAccountId()));
    }

    // ------------------------------------------------------------------------------------------
    // Inbound: seal cleartext for the columns that accept only envelopes
    // ------------------------------------------------------------------------------------------

    /**
     * Seals the two cleartext identifiers an account update screen submits, so they can be stored.
     *
     * <p>Each is sealed under its own field binding, so a value sealed for one column cannot be
     * revealed as the other. A {@code null} seals to {@code null}, because the national-identifier
     * column is nullable and a {@code null} must round-trip as one.
     *
     * @param clearSsnDigits the nine digits as the screen supplied them, or {@code null}
     * @param clearGovernmentIssuedId the government-issued identifier, or {@code null}
     * @return the two sealed values, ready for {@link Customer}
     * @throws IllegalArgumentException if the national identifier is supplied and is not exactly nine
     *         digits
     */
    public SealedCustomerIdentifiers seal(final String clearSsnDigits,
                                          final String clearGovernmentIssuedId) {
        if (clearSsnDigits != null) {
            requireSsnDigits(clearSsnDigits);
        }
        return new SealedCustomerIdentifiers(
                encryption.protectNullable(
                        SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, clearSsnDigits),
                encryption.protectNullable(
                        SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                        clearGovernmentIssuedId));
    }

    /**
     * Joins the update screen's three national-identifier positions into the nine stored digits.
     *
     * <p>The inverse of the split the update screen performs. Each position must carry exactly its
     * declared width, because the stored field is nine fixed digits and a short position would shift
     * every digit after it.
     *
     * @param part1 the first three digits
     * @param part2 the middle two digits
     * @param part3 the final four digits
     * @return the nine digits as one value
     * @throws IllegalArgumentException if any position is absent or not exactly its declared width of
     *         digits
     */
    public String joinSsnParts(final String part1, final String part2, final String part3) {
        requireDigitsOfWidth(part1, SSN_PART_1_WIDTH, "ssnPart1");
        requireDigitsOfWidth(part2, SSN_PART_2_WIDTH, "ssnPart2");
        requireDigitsOfWidth(part3, SSN_PART_3_WIDTH, "ssnPart3");
        return part1 + part2 + part3;
    }

    // ------------------------------------------------------------------------------------------
    // Composition and splitting, reproducing the screens' own arrangements
    // ------------------------------------------------------------------------------------------

    /**
     * Composes the nine stored digits into the view screen's dashed item.
     *
     * <p>Reproduces the composition at {@code app/cbl/COACTVWC.cbl:L495-L504} that joins the SSN's own
     * part-fields exactly: three digits, a separator, two digits, a
     * separator, four digits, into a field declared twelve characters wide.
     *
     * @param ssnDigits the nine stored digits, or {@code null}
     * @return the composed item, or {@code null} when no identifier is stored
     * @throws IllegalArgumentException if the value is present and is not exactly nine digits
     */
    public String composedSsn(final String ssnDigits) {
        if (ssnDigits == null) {
            return null;
        }
        requireSsnDigits(ssnDigits);
        return ssnPart1(ssnDigits) + SSN_PART_SEPARATOR + ssnPart2(ssnDigits)
                + SSN_PART_SEPARATOR + ssnPart3(ssnDigits);
    }

    /**
     * Returns the first three digits, the update screen's first position.
     *
     * @param ssnDigits the nine stored digits, or {@code null}
     * @return the first three digits, or {@code null}
     */
    public String ssnPart1(final String ssnDigits) {
        return ssnDigits == null ? null : ssnDigits.substring(0, SSN_PART_1_WIDTH);
    }

    /**
     * Returns the middle two digits, the update screen's second position.
     *
     * @param ssnDigits the nine stored digits, or {@code null}
     * @return the middle two digits, or {@code null}
     */
    public String ssnPart2(final String ssnDigits) {
        return ssnDigits == null ? null
                : ssnDigits.substring(SSN_PART_1_WIDTH, SSN_PART_1_WIDTH + SSN_PART_2_WIDTH);
    }

    /**
     * Returns the final four digits, the update screen's third position.
     *
     * @param ssnDigits the nine stored digits, or {@code null}
     * @return the final four digits, or {@code null}
     */
    public String ssnPart3(final String ssnDigits) {
        return ssnDigits == null ? null
                : ssnDigits.substring(SSN_PART_1_WIDTH + SSN_PART_2_WIDTH);
    }

    // ------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------

    /**
     * Reveals the stored national identifier, refusing anything that is not an envelope.
     *
     * @param customer the stored record
     * @return the nine cleartext digits, or {@code null} when the nullable column holds none
     */
    private String revealedSsnDigits(final Customer customer) {
        return revealedOrRefused(customer.getCustSsn(),
                SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, "cust_ssn");
    }

    /**
     * Reveals the stored government-issued identifier, refusing anything that is not an envelope.
     *
     * @param customer the stored record
     * @return the cleartext identifier, or {@code null} when none is stored
     */
    private String revealedGovernmentIssuedId(final Customer customer) {
        return revealedOrRefused(customer.getGovtIssuedId(),
                SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD, "govt_issued_id");
    }

    /**
     * Reveals one stored value, refusing to publish either an unprotected stored value or an envelope.
     *
     * <p>Both refusals matter. A stored value that is not an envelope reached the column by some route
     * other than this class, and publishing it would be publishing cleartext this boundary never
     * approved. A revealed value that still carries the envelope shape means the cipher returned its
     * input, and publishing it would leak ciphertext to a client.
     *
     * @param stored the column value
     * @param fieldBinding the field name the value was sealed under
     * @param columnName the column name, for the refusal message
     * @return the cleartext, or {@code null} when the column holds none
     */
    private String revealedOrRefused(final String stored, final String fieldBinding,
                                     final String columnName) {
        if (stored == null) {
            return null;
        }
        if (!encryption.isProtected(stored)) {
            throw new IllegalStateException(columnName
                    + " does not carry a protected-value envelope; a value that reached this column "
                    + "without being sealed is not published by this boundary");
        }
        final String revealed = encryption.reveal(fieldBinding, stored);
        if (encryption.isProtected(revealed)) {
            throw new IllegalStateException(columnName
                    + " still carries a protected-value envelope after being revealed; no envelope "
                    + "leaves this boundary");
        }
        return revealed;
    }

    /**
     * Masks the view screen's composed identifier, retaining the final four digits.
     *
     * @param ssnDigits the nine cleartext digits, or {@code null}
     * @return the masked item at the same width as the revealed one, or {@code null}
     */
    private static String maskedComposedSsn(final String ssnDigits) {
        if (ssnDigits == null) {
            return null;
        }
        requireSsnDigits(ssnDigits);
        return SSN_VIEW_MASK_PREFIX + ssnDigits.substring(
                SSN_PART_1_WIDTH + SSN_PART_2_WIDTH);
    }

    /**
     * Masks one value, character for character, so the masked form occupies the screen field exactly as
     * the revealed form would.
     *
     * <p>Built from the value's length alone and never from its content, so nothing of the value
     * survives into the mask. An empty value masks to empty rather than to a mask character, because a
     * blank screen field is blank whether or not the caller was authorized.
     *
     * @param value the value being withheld, or {@code null}
     * @return a mask of the same length, or {@code null}
     */
    private static String maskOf(final String value) {
        if (value == null) {
            return null;
        }
        return MASK_CHARACTER.repeat(value.length());
    }

    /**
     * Returns one part of a stored birth date, which the record holds as ten characters.
     *
     * @param birthDate the stored date, or {@code null}
     * @param from the inclusive start index
     * @param to the exclusive end index
     * @return that part of the date, or {@code null} when no date is stored or it is too short
     */
    private static String birthDatePart(final String birthDate, final int from, final int to) {
        if (birthDate == null || birthDate.length() < to) {
            return null;
        }
        return birthDate.substring(from, to);
    }

    /**
     * Rejects a national identifier that is not exactly nine digits.
     *
     * @param candidate the value being checked
     */
    private static void requireSsnDigits(final String candidate) {
        requireDigitsOfWidth(candidate, SSN_DIGIT_COUNT, "ssn");
    }

    /**
     * Rejects a value that is absent, of the wrong width, or not wholly digits.
     *
     * <p>The digit class is checked one character at a time rather than with a pattern, so the check
     * admits only the ten ASCII digits. A locale-aware or Unicode-aware digit test would admit
     * characters the fixed-width stored field cannot hold.
     *
     * @param candidate the value being checked
     * @param width the exact width required
     * @param name the value's name, for the refusal message
     */
    private static void requireDigitsOfWidth(final String candidate, final int width,
                                             final String name) {
        if (candidate == null || candidate.length() != width) {
            throw new IllegalArgumentException(name + " must be exactly " + width
                    + " characters, because the stored field is fixed width and a short value would "
                    + "shift every digit after it");
        }
        for (int index = 0; index < candidate.length(); index++) {
            final char character = candidate.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(name
                        + " must carry only the ten ASCII digits");
            }
        }
    }
}
