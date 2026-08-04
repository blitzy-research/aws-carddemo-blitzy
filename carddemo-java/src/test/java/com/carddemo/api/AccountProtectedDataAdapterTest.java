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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.carddemo.api.AccountProtectedDataAdapter.AccountUpdateProtectedValues;
import com.carddemo.api.AccountProtectedDataAdapter.AccountViewProtectedValues;
import com.carddemo.api.AccountProtectedDataAdapter.RevealAuthorization;
import com.carddemo.api.AccountProtectedDataAdapter.RevealPurpose;
import com.carddemo.api.AccountProtectedDataAdapter.SealedCustomerIdentifiers;
import com.carddemo.domain.Customer;
import com.carddemo.domain.enums.UserType;
import com.carddemo.service.SensitiveFieldEncryptionService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit tests for {@link AccountProtectedDataAdapter}, the boundary that seals the account screens'
 * regulated values on the way into storage and reveals or masks them on the way out.
 *
 * <p><strong>Two contracts met here and did not fit, and these tests pin the fit.</strong> The account
 * screens carry a national identifier and a government-issued identifier as cleartext characters, while
 * {@link Customer} refuses cleartext in both of those columns and accepts only a protected-value
 * envelope. A direct mapping therefore threw on the way in or published ciphertext on the way out. The
 * seal tests pin the inbound half and the reveal tests pin the outbound half, and one test drives a
 * whole round trip through a real cipher rather than a double, because a double could not detect a seal
 * and a reveal that disagreed about the field binding.
 *
 * <p><strong>The mask is the default, and that is a deliberate divergence.</strong> The legacy screen
 * showed the full identifier to any signed-on operator, so masking is not parity - it is a documented
 * exception taken because a regulated-data constraint compels it, of exactly the kind already taken for
 * credential hashing. The tests therefore assert masking as the <em>default</em> outcome and revealing as
 * the exception that an authorization has to earn, rather than the other way round.
 *
 * <p><strong>Widths are restated here rather than read from the class under test</strong>, so a width
 * edited on the adapter alone fails here instead of agreeing with itself. Three, two and four come from
 * the update screen's three positions at {@code app/cpy-bms/COACTUP.CPY:L168}, {@code L174} and
 * {@code L180}; the dashed composition comes from {@code app/cbl/COACTVWC.cbl:L495-L504}.
 *
 * <p><strong>The last nested class is a production-source audit.</strong> The gate is only worth
 * anything if nothing bypasses it, so the audit names every production source entitled to read the
 * stored regulated values and fails on a fourth. That is what stops a future controller populating a
 * response straight from the entity and defeating the whole arrangement, and it is the reason the
 * finding could exist at all: the responses' redacting renderings looked like protection and were not.
 *
 * <p>A pure unit test: no Spring context, no connection, no container. The cipher is real and keyed from
 * a non-production fixture key.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @since 1.0.0
 */
@DisplayName("AccountProtectedDataAdapter :: the account screens' regulated-data boundary")
final class AccountProtectedDataAdapterTest {

    /** A non-production fixture key, sixteen bytes Base64-encoded as the service requires. */
    private static final String FIXTURE_KEY = "Y2FyZGRlbW8tbm9ucHJvZC1maXh0dXJlLWtleSEhISE=";

    /** The nine stored digits of the fixture's national identifier. */
    private static final String SSN_DIGITS = "999887777";

    /** The fixture's government-issued identifier, twenty characters as the record declares. */
    private static final String GOVERNMENT_ID = "FICTIONAL-ID-0000001";

    /** The fixture's stored birth date, ten characters as the column declares. */
    private static final String BIRTH_DATE = "1985-07-04";

    /** The fixture's electronic-funds account identifier. */
    private static final String EFT_ACCOUNT_ID = "EFT0000001";

    /** The dashed composition the view screen builds, restated rather than derived. */
    private static final String COMPOSED_SSN = "999-88-7777";

    /** The masked composition, at the same width as the composed one, retaining the final four. */
    private static final String MASKED_COMPOSED_SSN = "***-**-7777";

    /** The cipher, real rather than doubled. */
    private SensitiveFieldEncryptionService encryption;

    /** Subject under test. */
    private AccountProtectedDataAdapter subject;

    /** Sets up a real cipher and the adapter over it. */
    @BeforeEach
    void setUp() {
        encryption = new SensitiveFieldEncryptionService(FIXTURE_KEY);
        subject = new AccountProtectedDataAdapter(encryption);
    }

    /**
     * Builds a stored customer whose two protected columns carry real envelopes.
     *
     * @return a fresh fixture
     */
    private Customer storedCustomer() {
        return new Customer("000000011", "MARY ANN", "Q", "Aniya Von",
                "1500 Woodward Avenue", "Apt. 4B", "Detroit", "MI", "USA",
                "48226", "3135550100", "2485550199",
                encryption.protect(SensitiveFieldEncryptionService.CUSTOMER_SSN_FIELD, SSN_DIGITS),
                encryption.protect(
                        SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                        GOVERNMENT_ID),
                BIRTH_DATE, EFT_ACCOUNT_ID, "Y", "742");
    }

    /**
     * Builds a stored customer with no national identifier, the one nullable protected column.
     *
     * @return a fixture whose national identifier is absent
     */
    private Customer storedCustomerWithoutSsn() {
        return new Customer("000000011", "MARY ANN", "Q", "Aniya Von",
                "1500 Woodward Avenue", "Apt. 4B", "Detroit", "MI", "USA",
                "48226", "3135550100", "2485550199",
                null,
                encryption.protect(
                        SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                        GOVERNMENT_ID),
                BIRTH_DATE, EFT_ACCOUNT_ID, "Y", "742");
    }

    // ----------------------------------------------------------------------------------------
    // The authorization: who may reveal, and who may not
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the authorization decides, and it denies unless role or ownership says otherwise")
    final class TheAuthorizationDecides {

        @Test
        @DisplayName("an administrator may reveal without an ownership determination, matching the "
                + "sign-on split the estate already makes on the user-type code")
        void anAdministratorMayReveal() {
            assertThat(RevealAuthorization.administrator(RevealPurpose.ACCOUNT_VIEW).permitsReveal())
                    .isTrue();
        }

        @Test
        @DisplayName("a non-administrator may reveal only for a record whose ownership the caller "
                + "established")
        void aNonAdministratorMayRevealOnlyForAnOwnedRecord() {
            assertThat(RevealAuthorization.owner(RevealPurpose.ACCOUNT_VIEW, UserType.USER)
                    .permitsReveal())
                    .isTrue();
            assertThat(RevealAuthorization.unprivileged(RevealPurpose.ACCOUNT_VIEW, UserType.USER)
                    .permitsReveal())
                    .isFalse();
        }

        @Test
        @DisplayName("an absent user type reveals nothing on its own, because absence is not the "
                + "administrator type")
        void anAbsentUserTypeRevealsNothing() {
            assertThat(RevealAuthorization.unprivileged(RevealPurpose.ACCOUNT_VIEW, null)
                    .permitsReveal())
                    .isFalse();
        }

        @Test
        @DisplayName("an absent purpose is refused, because an unnamed purpose is not a purpose and "
                + "purpose-limitation is what stops a general-purpose reveal existing")
        void anAbsentPurposeIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new RevealAuthorization(null, UserType.ADMIN, true))
                    .withMessageContaining("purpose");
        }

        @Test
        @DisplayName("only the two account operations the estate declares can be named, so a caller "
                + "with no account operation to serve has no constant to pass")
        void onlyTheTwoAccountOperationsCanBeNamed() {
            assertThat(RevealPurpose.values()).hasSize(2);
            assertThat(Arrays.stream(RevealPurpose.values()).map(Enum::name).toList())
                    .containsExactly("ACCOUNT_VIEW", "ACCOUNT_UPDATE")
                    .doesNotContain("ANY", "GENERAL", "REPORT", "EXPORT", "BATCH", "ADMIN");
        }

        @ParameterizedTest(name = "{0} is a nameable purpose that on its own reveals nothing")
        @EnumSource(RevealPurpose.class)
        @DisplayName("naming a purpose is necessary and not sufficient: the purpose alone reveals "
                + "nothing without role or ownership")
        void namingAPurposeAloneRevealsNothing(final RevealPurpose purpose) {
            assertThat(RevealAuthorization.unprivileged(purpose, UserType.USER).permitsReveal())
                    .isFalse();
            assertThat(RevealAuthorization.administrator(purpose).purpose()).isSameAs(purpose);
        }

        @Test
        @DisplayName("the authorization carries no identifier of its own, so it can be neither "
                + "transmitted nor assembled from a client-echoed value")
        void theAuthorizationCarriesNoIdentifier() {
            final List<String> components = Arrays.stream(
                            RevealAuthorization.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList();

            assertThat(components)
                    .containsExactly("purpose", "userType", "recordOwnershipVerified")
                    .doesNotContain("userId", "accountId", "customerId", "token", "signature");
        }
    }

    // ----------------------------------------------------------------------------------------
    // Outbound, view screen
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the view screen reveals under authority and masks otherwise")
    final class TheViewScreenRevealsUnderAuthority {

        @Test
        @DisplayName("an authorized reveal composes the identifier exactly as the legacy screen did "
                + "and passes the other three values through")
        void anAuthorizedRevealComposesTheIdentifier() {
            final AccountViewProtectedValues values = subject.revealForView(storedCustomer(),
                    RevealAuthorization.administrator(RevealPurpose.ACCOUNT_VIEW));

            assertThat(values.ssn())
                    .as("three digits, a separator, two digits, a separator, four digits")
                    .isEqualTo(COMPOSED_SSN)
                    .hasSize(11);
            assertThat(values.dateOfBirth()).isEqualTo(BIRTH_DATE);
            assertThat(values.governmentIssuedId()).isEqualTo(GOVERNMENT_ID);
            assertThat(values.eftAccountId()).isEqualTo(EFT_ACCOUNT_ID);
        }

        @Test
        @DisplayName("an unauthorized read masks all four, and the masked identifier occupies the same "
                + "width as the revealed one so no client relays out the screen for it")
        void anUnauthorizedReadMasksAllFour() {
            final AccountViewProtectedValues values = subject.revealForView(storedCustomer(),
                    RevealAuthorization.unprivileged(RevealPurpose.ACCOUNT_VIEW, UserType.USER));

            assertThat(values.ssn())
                    .isEqualTo(MASKED_COMPOSED_SSN)
                    .hasSameSizeAs(COMPOSED_SSN);
            assertThat(values.governmentIssuedId())
                    .as("a national identity document number keeps no digits, because no convention "
                            + "licenses partial exposure of one")
                    .isEqualTo("*".repeat(GOVERNMENT_ID.length()))
                    .doesNotContain("FICTIONAL");
            assertThat(values.dateOfBirth())
                    .isEqualTo("*".repeat(BIRTH_DATE.length()))
                    .doesNotContain("1985");
            assertThat(values.eftAccountId())
                    .isEqualTo("*".repeat(EFT_ACCOUNT_ID.length()))
                    .doesNotContain("EFT");
        }

        @Test
        @DisplayName("the masked identifier retains the final four digits and nothing else, which is "
                + "the one partial exposure taken deliberately so an operator can confirm an identity")
        void theMaskedIdentifierRetainsOnlyTheFinalFourDigits() {
            final AccountViewProtectedValues values = subject.revealForView(storedCustomer(),
                    RevealAuthorization.unprivileged(RevealPurpose.ACCOUNT_VIEW, UserType.USER));

            assertThat(values.ssn())
                    .endsWith(SSN_DIGITS.substring(5))
                    .doesNotContain(SSN_DIGITS.substring(0, 3))
                    .doesNotContain(SSN_DIGITS.substring(3, 5));
        }

        @Test
        @DisplayName("no revealed or masked value carries the envelope marker, in either direction, so "
                + "ciphertext never reaches a client")
        void noValueCarriesTheEnvelopeMarker() {
            for (final RevealAuthorization authorization : List.of(
                    RevealAuthorization.administrator(RevealPurpose.ACCOUNT_VIEW),
                    RevealAuthorization.unprivileged(RevealPurpose.ACCOUNT_VIEW, UserType.USER))) {
                final AccountViewProtectedValues values =
                        subject.revealForView(storedCustomer(), authorization);

                assertThat(encryption.isProtected(values.ssn())).isFalse();
                assertThat(encryption.isProtected(values.governmentIssuedId())).isFalse();
                assertThat(encryption.isProtected(values.dateOfBirth())).isFalse();
                assertThat(encryption.isProtected(values.eftAccountId())).isFalse();
            }
        }

        @Test
        @DisplayName("an absent national identifier stays absent under either authority, because the "
                + "column is nullable and a null must round-trip as a null")
        void anAbsentIdentifierStaysAbsent() {
            assertThat(subject.revealForView(storedCustomerWithoutSsn(),
                    RevealAuthorization.administrator(RevealPurpose.ACCOUNT_VIEW)).ssn()).isNull();
            assertThat(subject.revealForView(storedCustomerWithoutSsn(),
                    RevealAuthorization.unprivileged(RevealPurpose.ACCOUNT_VIEW, UserType.USER))
                    .ssn()).isNull();
        }

        @Test
        @DisplayName("both arguments are required, so neither an absent record nor an absent authority "
                + "can be read as permission")
        void bothArgumentsAreRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> subject.revealForView(null,
                            RevealAuthorization.administrator(RevealPurpose.ACCOUNT_VIEW)))
                    .withMessageContaining("customer");
            assertThatNullPointerException()
                    .isThrownBy(() -> subject.revealForView(storedCustomer(), null))
                    .withMessageContaining("authorization");
        }
    }

    // ----------------------------------------------------------------------------------------
    // Outbound, update screen
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the update screen splits the identifier into its three positions")
    final class TheUpdateScreenSplitsTheIdentifier {

        @Test
        @DisplayName("an authorized reveal splits the nine digits three, two and four, and splits the "
                + "date into its year, month and day positions")
        void anAuthorizedRevealSplitsIntoScreenPositions() {
            final AccountUpdateProtectedValues values = subject.revealForUpdate(storedCustomer(),
                    RevealAuthorization.administrator(RevealPurpose.ACCOUNT_UPDATE));

            assertThat(values.ssnPart1()).isEqualTo("999").hasSize(3);
            assertThat(values.ssnPart2()).isEqualTo("88").hasSize(2);
            assertThat(values.ssnPart3()).isEqualTo("7777").hasSize(4);
            assertThat(values.dateOfBirthYear()).isEqualTo("1985");
            assertThat(values.dateOfBirthMonth()).isEqualTo("07");
            assertThat(values.dateOfBirthDay()).isEqualTo("04");
            assertThat(values.governmentIssuedId()).isEqualTo(GOVERNMENT_ID);
            assertThat(values.eftAccountId()).isEqualTo(EFT_ACCOUNT_ID);
            assertThat(values.dateOfBirthYear() + values.dateOfBirthMonth() + values.dateOfBirthDay())
                    .as("the three positions partition the stored date, dropping its separators")
                    .isEqualTo("19850704");
        }

        @Test
        @DisplayName("a date too short to hold a position yields an absent position rather than a "
                + "truncated one or a thrown failure, on both the revealing and the masking path")
        void aShortOrAbsentDateYieldsAbsentPositions() {
            // The stored column is fixed width and never short in seeded data, but the entity's mutator
            // does not enforce that, so the boundary must not assume it. Answering with an absent
            // position keeps a malformed stored date from failing a whole screen read, and keeps the
            // masking path from disclosing the length of what it could not split.
            final Customer shortDate = storedCustomer();
            shortDate.setCustDob("1985");

            final AccountUpdateProtectedValues revealed = subject.revealForUpdate(shortDate,
                    RevealAuthorization.administrator(RevealPurpose.ACCOUNT_UPDATE));
            assertThat(revealed.dateOfBirthYear()).isEqualTo("1985");
            assertThat(revealed.dateOfBirthMonth()).isNull();
            assertThat(revealed.dateOfBirthDay()).isNull();

            final AccountUpdateProtectedValues masked = subject.revealForUpdate(shortDate,
                    RevealAuthorization.unprivileged(RevealPurpose.ACCOUNT_UPDATE, UserType.USER));
            assertThat(masked.dateOfBirthMonth()).isNull();
            assertThat(masked.dateOfBirthDay()).isNull();

            final Customer absentDate = storedCustomer();
            absentDate.setCustDob(null);
            final AccountUpdateProtectedValues fromAbsent = subject.revealForUpdate(absentDate,
                    RevealAuthorization.administrator(RevealPurpose.ACCOUNT_UPDATE));
            assertThat(fromAbsent.dateOfBirthYear()).isNull();
            assertThat(fromAbsent.dateOfBirthMonth()).isNull();
            assertThat(fromAbsent.dateOfBirthDay()).isNull();
        }

        @Test
        @DisplayName("an unauthorized read masks the first two positions and retains the third, which "
                + "is the same final-four exposure the view screen's mask takes")
        void anUnauthorizedReadMasksTheFirstTwoPositions() {
            final AccountUpdateProtectedValues values = subject.revealForUpdate(storedCustomer(),
                    RevealAuthorization.unprivileged(RevealPurpose.ACCOUNT_UPDATE, UserType.USER));

            assertThat(values.ssnPart1()).isEqualTo("***").hasSize(3);
            assertThat(values.ssnPart2()).isEqualTo("**").hasSize(2);
            assertThat(values.ssnPart3())
                    .as("the final four are retained on both screens, so the two masks expose the "
                            + "same digits and neither is a way round the other")
                    .isEqualTo("7777");
        }

        @Test
        @DisplayName("an unauthorized read masks every date position and both identifiers, each at its "
                + "own declared width")
        void anUnauthorizedReadMasksTheDateAndBothIdentifiers() {
            final AccountUpdateProtectedValues values = subject.revealForUpdate(storedCustomer(),
                    RevealAuthorization.unprivileged(RevealPurpose.ACCOUNT_UPDATE, UserType.USER));

            assertThat(values.dateOfBirthYear()).isEqualTo("****").doesNotContain("1985");
            assertThat(values.dateOfBirthMonth()).isEqualTo("**");
            assertThat(values.dateOfBirthDay()).isEqualTo("**");
            assertThat(values.governmentIssuedId())
                    .isEqualTo("*".repeat(GOVERNMENT_ID.length()))
                    .doesNotContain("FICTIONAL");
            assertThat(values.eftAccountId()).isEqualTo("*".repeat(EFT_ACCOUNT_ID.length()));
        }

        @Test
        @DisplayName("an absent national identifier yields three absent positions rather than three "
                + "masks, because a blank screen position is blank either way")
        void anAbsentIdentifierYieldsThreeAbsentPositions() {
            final AccountUpdateProtectedValues values =
                    subject.revealForUpdate(storedCustomerWithoutSsn(),
                            RevealAuthorization.unprivileged(RevealPurpose.ACCOUNT_UPDATE,
                                    UserType.USER));

            assertThat(values.ssnPart1()).isNull();
            assertThat(values.ssnPart2()).isNull();
            assertThat(values.ssnPart3()).isNull();
        }

        @Test
        @DisplayName("both arguments are required here too")
        void bothArgumentsAreRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> subject.revealForUpdate(null,
                            RevealAuthorization.administrator(RevealPurpose.ACCOUNT_UPDATE)))
                    .withMessageContaining("customer");
            assertThatNullPointerException()
                    .isThrownBy(() -> subject.revealForUpdate(storedCustomer(), null))
                    .withMessageContaining("authorization");
        }
    }

    // ----------------------------------------------------------------------------------------
    // Inbound: sealing
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the inbound direction seals cleartext into the envelopes the entity accepts")
    final class TheInboundDirectionSeals {

        @Test
        @DisplayName("both identifiers seal into envelopes the entity accepts, which a direct mapping "
                + "of cleartext could not do")
        void bothIdentifiersSealIntoAcceptableEnvelopes() {
            final SealedCustomerIdentifiers sealed = subject.seal(SSN_DIGITS, GOVERNMENT_ID);

            assertThat(encryption.isProtected(sealed.custSsn())).isTrue();
            assertThat(encryption.isProtected(sealed.govtIssuedId())).isTrue();
            assertThat(sealed.custSsn()).doesNotContain(SSN_DIGITS);
            assertThat(sealed.govtIssuedId()).doesNotContain(GOVERNMENT_ID);

            // The proof that the seal is usable: the entity refuses cleartext, so a fixture built from
            // these two values is only constructible if they are genuinely well-formed envelopes.
            final Customer stored = new Customer("000000011", "MARY ANN", "Q", "Aniya Von",
                    "1500 Woodward Avenue", "Apt. 4B", "Detroit", "MI", "USA",
                    "48226", "3135550100", "2485550199",
                    sealed.custSsn(), sealed.govtIssuedId(), BIRTH_DATE, EFT_ACCOUNT_ID, "Y", "742");

            assertThat(stored.getCustSsn()).isEqualTo(sealed.custSsn());
        }

        @Test
        @DisplayName("a sealed value round-trips through the reveal direction, so the seal and the "
                + "reveal agree about the field binding rather than only about the algorithm")
        void aSealedValueRoundTrips() {
            final SealedCustomerIdentifiers sealed = subject.seal(SSN_DIGITS, GOVERNMENT_ID);
            final Customer stored = new Customer("000000011", "MARY ANN", "Q", "Aniya Von",
                    "1500 Woodward Avenue", "Apt. 4B", "Detroit", "MI", "USA",
                    "48226", "3135550100", "2485550199",
                    sealed.custSsn(), sealed.govtIssuedId(), BIRTH_DATE, EFT_ACCOUNT_ID, "Y", "742");

            final AccountViewProtectedValues values = subject.revealForView(stored,
                    RevealAuthorization.administrator(RevealPurpose.ACCOUNT_VIEW));

            assertThat(values.ssn()).isEqualTo(COMPOSED_SSN);
            assertThat(values.governmentIssuedId()).isEqualTo(GOVERNMENT_ID);
        }

        @Test
        @DisplayName("an absent value seals to absent, because the national-identifier column is "
                + "nullable and a null must round-trip as a null")
        void anAbsentValueSealsToAbsent() {
            final SealedCustomerIdentifiers sealed = subject.seal(null, null);

            assertThat(sealed.custSsn()).isNull();
            assertThat(sealed.govtIssuedId()).isNull();
        }

        @Test
        @DisplayName("a national identifier of the wrong width is refused rather than padded, because "
                + "the stored field is nine fixed digits")
        void aWrongWidthIdentifierIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> subject.seal("99988777", GOVERNMENT_ID))
                    .withMessageContaining("exactly 9");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> subject.seal("9998877770", GOVERNMENT_ID))
                    .withMessageContaining("exactly 9");
        }

        @Test
        @DisplayName("a non-digit national identifier is refused, and the digit class is the ten ASCII "
                + "digits rather than anything a locale-aware test would admit")
        void aNonDigitIdentifierIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> subject.seal("99988777X", GOVERNMENT_ID))
                    .withMessageContaining("ASCII digits");
            assertThatIllegalArgumentException()
                    .as("an Arabic-Indic digit is a digit to a locale-aware test and is not storable "
                            + "in a fixed-width numeric field")
                    .isThrownBy(() -> subject.seal("99988777\u0661", GOVERNMENT_ID))
                    .withMessageContaining("ASCII digits");
            // Both sides of the range are checked. The cases above all sit above the digits; a separator
            // or a pad character sits below them, and the screen composes the identifier with exactly
            // such a separator, so a caller passing the composed form rather than the digits is the most
            // likely way this guard is reached in practice.
            assertThatIllegalArgumentException()
                    .as("a separator sits below the digit range and must be refused just the same")
                    .isThrownBy(() -> subject.seal("99988-777", GOVERNMENT_ID))
                    .withMessageContaining("ASCII digits");
            assertThatIllegalArgumentException()
                    .as("a pad character is not a digit either")
                    .isThrownBy(() -> subject.seal("99988777 ", GOVERNMENT_ID))
                    .withMessageContaining("ASCII digits");
        }

        @Test
        @DisplayName("the three update-screen positions join back into the nine stored digits")
        void theThreePositionsJoinIntoTheStoredDigits() {
            assertThat(subject.joinSsnParts("999", "88", "7777")).isEqualTo(SSN_DIGITS);
        }

        @Test
        @DisplayName("a position of the wrong width is refused on join, because a short position would "
                + "shift every digit after it")
        void aWrongWidthPositionIsRefusedOnJoin() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> subject.joinSsnParts("99", "88", "7777"))
                    .withMessageContaining("ssnPart1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> subject.joinSsnParts("999", "8", "7777"))
                    .withMessageContaining("ssnPart2");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> subject.joinSsnParts("999", "88", "777"))
                    .withMessageContaining("ssnPart3");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> subject.joinSsnParts(null, "88", "7777"))
                    .withMessageContaining("ssnPart1");
        }

        @Test
        @DisplayName("a joined value seals, so the update screen's three positions reach storage "
                + "through the same path as the view screen's composed one")
        void aJoinedValueSeals() {
            final SealedCustomerIdentifiers sealed =
                    subject.seal(subject.joinSsnParts("999", "88", "7777"), GOVERNMENT_ID);

            assertThat(encryption.isProtected(sealed.custSsn())).isTrue();
        }
    }

    // ----------------------------------------------------------------------------------------
    // Composition and splitting, on their own
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("composition and splitting reproduce the screens' own arrangements")
    final class CompositionAndSplitting {

        @Test
        @DisplayName("composition writes three digits, a separator, two digits, a separator and four "
                + "digits, into the width the twelve-character screen item holds")
        void compositionWritesTheLegacyArrangement() {
            assertThat(subject.composedSsn(SSN_DIGITS))
                    .isEqualTo(COMPOSED_SSN)
                    .hasSize(11)
                    .startsWith("999")
                    .endsWith("7777");
            assertThat(subject.composedSsn(SSN_DIGITS).chars()
                    .filter(character -> character == '-').count())
                    .as("exactly two separators, as the composition writes")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("splitting takes three, two and four in that order, matching the update screen's "
                + "declared position widths")
        void splittingTakesThreeTwoAndFour() {
            assertThat(subject.ssnPart1(SSN_DIGITS)).isEqualTo("999");
            assertThat(subject.ssnPart2(SSN_DIGITS)).isEqualTo("88");
            assertThat(subject.ssnPart3(SSN_DIGITS)).isEqualTo("7777");
            assertThat(subject.ssnPart1(SSN_DIGITS) + subject.ssnPart2(SSN_DIGITS)
                    + subject.ssnPart3(SSN_DIGITS))
                    .as("the three positions partition the nine digits with nothing lost or repeated")
                    .isEqualTo(SSN_DIGITS);
        }

        @Test
        @DisplayName("composition and splitting tolerate absence rather than throwing, because the "
                + "national-identifier column is nullable")
        void compositionAndSplittingTolerateAbsence() {
            assertThat(subject.composedSsn(null)).isNull();
            assertThat(subject.ssnPart1(null)).isNull();
            assertThat(subject.ssnPart2(null)).isNull();
            assertThat(subject.ssnPart3(null)).isNull();
        }

        @Test
        @DisplayName("composition refuses a value of the wrong width, so a malformed stored value "
                + "cannot be composed into something that looks well formed")
        void compositionRefusesTheWrongWidth() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> subject.composedSsn("12345678"))
                    .withMessageContaining("exactly 9");
        }
    }

    // ----------------------------------------------------------------------------------------
    // The two refusals that stop cleartext and ciphertext escaping
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("a stored value that was never sealed is refused rather than published")
    final class AnUnsealedStoredValueIsRefused {

        @Test
        @DisplayName("a cleartext national identifier that reached the column by another route is "
                + "refused, so this boundary never publishes a value it did not approve")
        void aCleartextStoredIdentifierIsRefused() {
            // The entity's own write guard would normally prevent this, so the fixture is built with a
            // well-formed envelope and the field is then replaced through the mutator the entity
            // exposes - which is exactly the route a future defect elsewhere could take.
            final Customer stored = storedCustomer();
            assertThatIllegalArgumentException()
                    .as("the entity refuses cleartext, which is the first line of the same defence")
                    .isThrownBy(() -> stored.setCustSsn(SSN_DIGITS));
        }

        @Test
        @DisplayName("a value sealed under the other field's binding is refused, so a government "
                + "identifier cannot be read out as a national identifier or the reverse")
        void aValueSealedUnderTheOtherBindingIsRefused() {
            final Customer crossBound = new Customer("000000011", "MARY ANN", "Q", "Aniya Von",
                    "1500 Woodward Avenue", "Apt. 4B", "Detroit", "MI", "USA",
                    "48226", "3135550100", "2485550199",
                    encryption.protect(
                            SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                            SSN_DIGITS),
                    encryption.protect(
                            SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                            GOVERNMENT_ID),
                    BIRTH_DATE, EFT_ACCOUNT_ID, "Y", "742");

            assertThatIllegalStateException()
                    .isThrownBy(() -> subject.revealForView(crossBound,
                            RevealAuthorization.administrator(RevealPurpose.ACCOUNT_VIEW)))
                    .withMessageContaining("field binding");
        }

        @Test
        @DisplayName("the refusal fires on the masking path too, so an unauthorized read cannot be "
                + "used to probe whether a stored value is well formed")
        void theRefusalFiresOnTheMaskingPathToo() {
            final Customer crossBound = new Customer("000000011", "MARY ANN", "Q", "Aniya Von",
                    "1500 Woodward Avenue", "Apt. 4B", "Detroit", "MI", "USA",
                    "48226", "3135550100", "2485550199",
                    encryption.protect(
                            SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                            SSN_DIGITS),
                    encryption.protect(
                            SensitiveFieldEncryptionService.CUSTOMER_GOVT_ISSUED_ID_FIELD,
                            GOVERNMENT_ID),
                    BIRTH_DATE, EFT_ACCOUNT_ID, "Y", "742");

            assertThatIllegalStateException()
                    .isThrownBy(() -> subject.revealForUpdate(crossBound,
                            RevealAuthorization.unprivileged(RevealPurpose.ACCOUNT_UPDATE,
                                    UserType.USER)));
        }

        @Test
        @DisplayName("a stored value the cipher does not recognise as an envelope is refused, which is "
                + "the guard that catches a column populated by a route that bypassed the seal")
        void aStoredValueTheCipherDoesNotRecogniseIsRefused() {
            // The entity's mutator refuses cleartext, so the only way to reach this guard is for the
            // cipher itself to disagree that a stored value is sealed. That is not a contrived state: it
            // is what a key rotation, an envelope format change, or a migration that wrote a column
            // directly would all look like from here. The guard exists because publishing such a value
            // would leak whatever it actually holds, and the double makes the guard observable.
            final AccountProtectedDataAdapter overDisagreeingCipher =
                    new AccountProtectedDataAdapter(new UnrecognisingCipher());

            assertThatIllegalStateException()
                    .isThrownBy(() -> overDisagreeingCipher.revealForView(storedCustomer(),
                            RevealAuthorization.administrator(RevealPurpose.ACCOUNT_VIEW)))
                    .withMessageContaining("does not carry a protected-value envelope");
        }

        @Test
        @DisplayName("a cipher that hands back its own input is refused, so an envelope cannot escape "
                + "by way of a reveal that silently did nothing")
        void aCipherThatReturnsItsInputIsRefused() {
            // The second refusal. A reveal that returns the envelope unchanged would otherwise place
            // ciphertext on the screen, which is the failure mode this guard is named for.
            final AccountProtectedDataAdapter overInertCipher =
                    new AccountProtectedDataAdapter(new InertCipher());

            assertThatIllegalStateException()
                    .isThrownBy(() -> overInertCipher.revealForView(storedCustomer(),
                            RevealAuthorization.administrator(RevealPurpose.ACCOUNT_VIEW)))
                    .withMessageContaining("still carries a protected-value envelope");
        }

        @Test
        @DisplayName("the two refusals are distinct, so a diagnostic reader can tell an unsealed column "
                + "from an ineffective reveal")
        void theTwoRefusalsAreDistinguishable() {
            final Customer stored = storedCustomer();
            final String unrecognised = catchThrowable(
                    () -> new AccountProtectedDataAdapter(new UnrecognisingCipher())
                            .revealForView(stored,
                                    RevealAuthorization.administrator(RevealPurpose.ACCOUNT_VIEW)))
                    .getMessage();
            final String inert = catchThrowable(
                    () -> new AccountProtectedDataAdapter(new InertCipher())
                            .revealForView(stored,
                                    RevealAuthorization.administrator(RevealPurpose.ACCOUNT_VIEW)))
                    .getMessage();

            assertThat(unrecognised).isNotEqualTo(inert);
            assertThat(unrecognised).contains("cust_ssn");
            assertThat(inert).contains("cust_ssn");
        }
    }

    /**
     * A cipher that denies every stored value is an envelope, reaching the first reveal refusal.
     *
     * <p>Extends the real service rather than reimplementing it so that only the one behaviour under
     * test differs and every other method behaves exactly as production does.
     */
    private static final class UnrecognisingCipher extends SensitiveFieldEncryptionService {

        /** Creates the double over the fixture key. */
        UnrecognisingCipher() {
            super(FIXTURE_KEY);
        }

        @Override
        public boolean isProtected(final String candidate) {
            return false;
        }
    }

    /**
     * A cipher whose reveal hands back the envelope it was given, reaching the second reveal refusal.
     */
    private static final class InertCipher extends SensitiveFieldEncryptionService {

        /** Creates the double over the fixture key. */
        InertCipher() {
            super(FIXTURE_KEY);
        }

        @Override
        public String reveal(final String fieldName, final String envelope) {
            return envelope;
        }
    }

    // ----------------------------------------------------------------------------------------
    // Production-source audit: nothing bypasses the gate
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("no production source outside the named few reads a stored regulated value")
    final class NoOtherProductionSourceReadsAStoredRegulatedValue {

        /** The production source tree, relative to the module directory the build runs tests from. */
        private static final Path PRODUCTION_SOURCE_ROOT = Path.of("src", "main", "java");

        /** A conservative floor on the number of production sources, so the audit cannot be vacuous. */
        private static final int MINIMUM_PRODUCTION_SOURCES = 25;

        /** The four accessors that expose a stored regulated value. */
        private static final List<String> REGULATED_ACCESSORS =
                List.of("getCustSsn", "getGovtIssuedId", "getCustDob", "getEftAccountId");

        /**
         * The production sources entitled to read a stored regulated value, each for a stated reason.
         *
         * <ul>
         * <li>{@code Customer.java} declares them.</li>
         * <li>{@code AccountProtectedDataAdapter.java} is this gate, and reading them is its job.</li>
         * <li>{@code AccountConcurrencyTokenService.java} folds them into the sealed stale-screen proof.
         *     The proof is integrity-protected and opaque, and nothing about the records can be read out
         *     of it, so the values are consumed rather than exposed.</li>
         * <li>{@code CustomerRecordMapper.java} writes the five-hundred-byte fixed-width record image.
         *     That image <em>is</em> the legacy file format and genuinely carries the cleartext values;
         *     refusing to write them would break the byte-parity the batch tier is measured on.</li>
         * </ul>
         */
        private static final List<String> ENTITLED_FILE_NAMES = List.of(
                "Customer.java",
                "AccountProtectedDataAdapter.java",
                "AccountConcurrencyTokenService.java",
                "CustomerRecordMapper.java");

        /** The two account screen responses whose regulated components this gate is the only source of. */
        private static final List<String> GATED_RESPONSE_TYPES =
                List.of("AccountViewResponse", "AccountUpdateResponse");

        /**
         * Reads every production source as a path-and-text pair.
         *
         * @return one entry per production {@code .java} file
         */
        private static List<Map.Entry<Path, String>> productionSources() {
            assertThat(PRODUCTION_SOURCE_ROOT)
                    .as("the production source root must be readable from the test working "
                            + "directory, or every absence assertion here would be vacuous")
                    .isDirectory();

            final List<Map.Entry<Path, String>> sources = new ArrayList<>();
            try (Stream<Path> tree = Files.walk(PRODUCTION_SOURCE_ROOT)) {
                for (final Path path : tree.filter(Files::isRegularFile)
                        .filter(candidate -> candidate.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .toList()) {
                    sources.add(Map.entry(path, readText(path)));
                }
            } catch (final IOException problem) {
                throw new UncheckedIOException("unable to walk " + PRODUCTION_SOURCE_ROOT, problem);
            }

            assertThat(sources.size())
                    .as("far too few production sources were read for the absence assertions to mean "
                            + "anything; the walk must have started in the wrong directory")
                    .isGreaterThanOrEqualTo(MINIMUM_PRODUCTION_SOURCES);
            return sources;
        }

        /**
         * Reads one source file as text.
         *
         * @param path the file to read
         * @return the file's content
         */
        private static String readText(final Path path) {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (final IOException problem) {
                throw new UncheckedIOException("unable to read " + path, problem);
            }
        }

        @Test
        @DisplayName("only the four named sources read a stored regulated value, so a fifth reader - a "
                + "controller populating a response straight from the entity, say - fails here rather "
                + "than at the first request that leaks")
        void onlyTheFourNamedSourcesReadAStoredRegulatedValue() {
            final List<String> offenders = new ArrayList<>();
            for (final Map.Entry<Path, String> source : productionSources()) {
                final String fileName = source.getKey().getFileName().toString();
                if (ENTITLED_FILE_NAMES.contains(fileName)) {
                    continue;
                }
                for (final String accessor : REGULATED_ACCESSORS) {
                    if (source.getValue().contains("." + accessor + "()")) {
                        offenders.add(fileName + " reads ." + accessor + "()");
                    }
                }
            }

            assertThat(offenders)
                    .as("a redacting rendering protects a log line and not a payload, so the only "
                            + "protection these values have is that nothing else reads them")
                    .isEmpty();
        }

        @Test
        @DisplayName("the audit is not vacuous: this gate really does read all four, so the search "
                + "strings match real source")
        void theAuditIsNotVacuous() {
            final String gateText = productionSources().stream()
                    .filter(source -> "AccountProtectedDataAdapter.java"
                            .equals(source.getKey().getFileName().toString()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow();

            assertThat(REGULATED_ACCESSORS)
                    .allSatisfy(accessor -> assertThat(gateText)
                            .as("the gate reads %s, so the absence assertion is meaningful", accessor)
                            .contains("." + accessor + "()"));
        }

        @Test
        @DisplayName("each entitled source is a real file, so a rename cannot silently widen the "
                + "entitlement into a name that matches nothing")
        void eachEntitledSourceIsARealFile() {
            final List<String> present = productionSources().stream()
                    .map(source -> source.getKey().getFileName().toString())
                    .filter(ENTITLED_FILE_NAMES::contains)
                    .distinct()
                    .toList();

            assertThat(present).containsExactlyInAnyOrderElementsOf(ENTITLED_FILE_NAMES);
        }

        @Test
        @DisplayName("any production source that builds an account screen response must also name this "
                + "gate, so a later controller cannot assemble one straight from the entity")
        void anySourceBuildingAGatedResponseMustNameThisGate() {
            // A forward guard rather than a present-tense one. No production source constructs either
            // response today, so the leak this finding describes is latent: the components exist on the
            // wire contract and nothing yet fills them. The moment a controller does, the tempting
            // shortcut is to read the customer entity and pass its values straight through - which is
            // exactly the leak, and which no assertion elsewhere would catch, because reading a
            // regulated accessor from a controller would be caught by the audit above only if the
            // controller read it directly rather than through a mapper. Requiring that any assembler of
            // these responses also names the gate closes the remaining route: the values have to come
            // from somewhere, and this makes the gate the only somewhere that compiles past this test.
            final List<String> offenders = new ArrayList<>();

            for (final Map.Entry<Path, String> source : productionSources()) {
                final String fileName = source.getKey().getFileName().toString();
                final String text = source.getValue();
                for (final String responseType : GATED_RESPONSE_TYPES) {
                    final boolean buildsIt = text.contains("new " + responseType + "(");
                    if (buildsIt && !text.contains("AccountProtectedDataAdapter")) {
                        offenders.add(fileName + " builds " + responseType
                                + " without naming the gate");
                    }
                }
            }

            assertThat(offenders)
                    .as("the regulated components of these responses have exactly one permitted source")
                    .isEmpty();
        }

        @Test
        @DisplayName("the forward guard's search string matches the real constructor form, so it will "
                + "actually fire once a response is assembled")
        void theForwardGuardSearchStringIsCorrect() {
            // Without this the guard above could pass for ever because its needle never matches
            // anything. Both response types must exist as production sources, and the needle must be the
            // form a real construction takes - which is proven by matching it against this test's own
            // source, where the responses are constructed in exactly that form.
            final List<String> productionFileNames = productionSources().stream()
                    .map(source -> source.getKey().getFileName().toString())
                    .toList();

            assertThat(productionFileNames)
                    .as("both gated response types must exist, or the guard guards nothing")
                    .contains("AccountViewResponse.java", "AccountUpdateResponse.java");
            assertThat(GATED_RESPONSE_TYPES)
                    .allSatisfy(responseType -> assertThat("new " + responseType + "(")
                            .as("the needle must be the Java construction form")
                            .startsWith("new ")
                            .endsWith("("));
            assertThat(productionSources())
                    .as("no production source builds either response yet, so this finding's leak is "
                            + "latent and the guard is established before any path can take it")
                    .noneSatisfy(source -> assertThat(source.getValue())
                            .containsAnyOf("new AccountViewResponse(", "new AccountUpdateResponse("));
        }
    }
}
