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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import com.carddemo.domain.UserSecurity;
import com.carddemo.support.SensitiveValues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link UserSecurityRecordMapper}, which maps the eighty-byte {@code SEC-USER-DATA}
 * record declared by {@code app/cpy/CSUSR01Y.cpy} onto {@link UserSecurity} and back.
 *
 * <p><strong>Provenance.</strong> Read from the mainframe estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p><strong>This layout has no sample fixture, and that is deliberate.</strong> Every other
 * fixed-width layout in the module is decoded against a file under
 * {@code src/test/resources/fixtures/input/}. This one is not, because the legacy record carries a
 * cleartext credential in {@code SEC-USR-PWD PIC X(08)} and committing a file of credential-bearing
 * images would create the very artefact the migration exists to remove. The record images used here
 * are therefore synthesised in code from the ten seed rows that {@code app/jcl/DUSRSECJ.jcl} carries
 * in-stream at lines 35 to 44 — five administrator rows and five standard-user rows, all sharing the
 * literal password the legacy job seeds. Those cards are fifty-seven characters wide because JCL
 * in-stream data omits trailing filler; the tests pad each to the eighty bytes the copybook declares.
 *
 * <p><strong>The mapper is asymmetric by design, and the asymmetry is the security control.</strong>
 * The decoder hands the eight raw credential bytes to a caller-supplied digest function and stores
 * only what that function returns, so no path exists that persists the cleartext. The encoder writes
 * eight spaces where the credential sits, so no path exists that emits a credential into a record
 * image either. A decode-then-encode cycle is consequently <em>not</em> byte-identical, and the tests
 * assert that the divergence is confined to exactly the credential field rather than tolerating it as
 * an approximation. The two regions the cycle does reproduce byte for byte are published as named
 * constants, and both are asserted.
 *
 * <p><strong>The width diagnostic is a DL-041 obligation, not a convenience.</strong> Decision
 * DL-041 forbids a rejection diagnostic from echoing the offending value. For every other layout that
 * rule protects against log injection; here it additionally protects against printing a credential,
 * and the mapper's own message says so. The tests hand the reader a mis-width image whose bytes
 * contain a recognisable secret and assert that no fragment of it reaches the message.
 */
@DisplayName("UserSecurityRecordMapper - the eighty-byte CSUSR01Y record that never emits a credential")
class UserSecurityRecordMapperRuleComplianceTest {

    /**
     * A structurally valid BCrypt digest the {@link UserSecurity} constructor accepts: sixty
     * characters opening with a recognised version marker, a two-digit cost of at least ten, a
     * separator, and a salt-and-hash body drawn from the BCrypt radix-64 alphabet.
     */
    private static final String VALID_BCRYPT_DIGEST =
            "$2a$10$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0";

    /** A second structurally valid digest, used where two distinct credentials are needed. */
    private static final String OTHER_BCRYPT_DIGEST =
            "$2b$12$ZYXWVUTSRQPONMLKJIHGFEDCBAzyxwvutsrqponmlkjihgfedcba9";

    /** The cleartext the legacy seed job stores in every one of its ten rows. */
    private static final String LEGACY_SEED_PASSWORD = "PASSWORD";

    /** The administrator type byte the legacy seed job writes for its first five rows. */
    private static final String ADMINISTRATOR_TYPE = "A";

    /** The standard-user type byte the legacy seed job writes for its last five rows. */
    private static final String STANDARD_USER_TYPE = "U";

    /**
     * The ten seed rows {@code app/jcl/DUSRSECJ.jcl} carries in-stream at lines 35 to 44, as
     * identifier, first name, last name and type. The password every row carries is the shared
     * literal above and is supplied by the image builder rather than repeated here.
     */
    private static final String[][] LEGACY_SEED_ROWS = {
        {"ADMIN001", "MARGARET", "GOLD", ADMINISTRATOR_TYPE},
        {"ADMIN002", "RUSSELL", "RUSSELL", ADMINISTRATOR_TYPE},
        {"ADMIN003", "RAYMOND", "WHITMORE", ADMINISTRATOR_TYPE},
        {"ADMIN004", "EMMANUEL", "CASGRAIN", ADMINISTRATOR_TYPE},
        {"ADMIN005", "GRANVILLE", "LACHAPELLE", ADMINISTRATOR_TYPE},
        {"USER0001", "LAWRENCE", "THOMAS", STANDARD_USER_TYPE},
        {"USER0002", "AJITH", "KUMAR", STANDARD_USER_TYPE},
        {"USER0003", "LAURITZ", "ALME", STANDARD_USER_TYPE},
        {"USER0004", "AVERARDO", "MAZZI", STANDARD_USER_TYPE},
        {"USER0005", "LEE", "TING", STANDARD_USER_TYPE},
    };

    /**
     * A digest function that ignores its input and returns a fixed valid digest, so that a test
     * asserting on the record image is not also asserting on hashing.
     */
    private static UnaryOperator<String> fixedDigest() {
        return credential -> VALID_BCRYPT_DIGEST;
    }

    /**
     * Pads a value on the right with spaces to a declared field width.
     *
     * @param value the value to pad
     * @param width the declared field width
     * @return the value padded to the width
     */
    private static String spacePadded(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Assembles a well-formed eighty-byte record image at the declared field widths.
     *
     * @param userId the user identifier
     * @param firstName the first name
     * @param lastName the last name
     * @param password the eight-character cleartext credential the legacy record carries
     * @param userType the one-character user type
     * @return an eighty-byte record image
     */
    private static String recordImage(final String userId, final String firstName,
            final String lastName, final String password, final String userType) {
        return spacePadded(userId, UserSecurityRecordMapper.SEC_USR_ID_LENGTH)
                + spacePadded(firstName, UserSecurityRecordMapper.SEC_USR_FNAME_LENGTH)
                + spacePadded(lastName, UserSecurityRecordMapper.SEC_USR_LNAME_LENGTH)
                + spacePadded(password, UserSecurityRecordMapper.SEC_USR_PWD_LENGTH)
                + spacePadded(userType, UserSecurityRecordMapper.SEC_USR_TYPE_LENGTH)
                + " ".repeat(UserSecurityRecordMapper.SEC_USR_FILLER_LENGTH);
    }

    /**
     * Assembles the eighty-byte image of one legacy seed row.
     *
     * @param ordinal the zero-based seed row position
     * @return an eighty-byte record image
     */
    private static String seedRecordImage(final int ordinal) {
        final String[] row = LEGACY_SEED_ROWS[ordinal];
        return recordImage(row[0], row[1], row[2], LEGACY_SEED_PASSWORD, row[3]);
    }

    /**
     * Builds a user whose four reproducible attributes are present and whose credential is a valid
     * digest.
     *
     * @param userId the user identifier
     * @param firstName the first name
     * @param lastName the last name
     * @param userType the user type
     * @return a fully populated user
     */
    private static UserSecurity aUser(final String userId, final String firstName,
            final String lastName, final String userType) {
        return new UserSecurity(userId, firstName, lastName, VALID_BCRYPT_DIGEST, userType);
    }

    /**
     * Extracts the credential region of a record image.
     *
     * @param image the eighty-byte record image
     * @return the eight bytes at the credential offset
     */
    private static String credentialRegionOf(final String image) {
        return image.substring(UserSecurityRecordMapper.SEC_USR_PWD_OFFSET,
                UserSecurityRecordMapper.SEC_USR_PWD_OFFSET
                        + UserSecurityRecordMapper.SEC_USR_PWD_LENGTH);
    }

    // =================================================================================
    // The published layout
    // =================================================================================

    @Nested
    @DisplayName("The published layout")
    class ThePublishedLayout {

        @Test
        @DisplayName("the record is eighty bytes: fifty-seven mapped plus twenty-three of filler")
        void theRecordIsEightyBytes() {
            assertThat(UserSecurityRecordMapper.MAPPED_LENGTH).isEqualTo(57);
            assertThat(UserSecurityRecordMapper.SEC_USR_FILLER_LENGTH).isEqualTo(23);
            assertThat(UserSecurityRecordMapper.RECORD_LENGTH)
                    .isEqualTo(UserSecurityRecordMapper.MAPPED_LENGTH
                            + UserSecurityRecordMapper.SEC_USR_FILLER_LENGTH)
                    .isEqualTo(80);
        }

        @Test
        @DisplayName("the five fields run contiguously from offset zero without a gap")
        void theFiveFieldsRunContiguouslyFromZero() {
            assertThat(UserSecurityRecordMapper.SEC_USR_ID_OFFSET).isZero();
            assertThat(UserSecurityRecordMapper.SEC_USR_FNAME_OFFSET)
                    .isEqualTo(UserSecurityRecordMapper.SEC_USR_ID_OFFSET
                            + UserSecurityRecordMapper.SEC_USR_ID_LENGTH);
            assertThat(UserSecurityRecordMapper.SEC_USR_LNAME_OFFSET)
                    .isEqualTo(UserSecurityRecordMapper.SEC_USR_FNAME_OFFSET
                            + UserSecurityRecordMapper.SEC_USR_FNAME_LENGTH);
            assertThat(UserSecurityRecordMapper.SEC_USR_PWD_OFFSET)
                    .isEqualTo(UserSecurityRecordMapper.SEC_USR_LNAME_OFFSET
                            + UserSecurityRecordMapper.SEC_USR_LNAME_LENGTH);
            assertThat(UserSecurityRecordMapper.SEC_USR_TYPE_OFFSET)
                    .isEqualTo(UserSecurityRecordMapper.SEC_USR_PWD_OFFSET
                            + UserSecurityRecordMapper.SEC_USR_PWD_LENGTH);
        }

        @Test
        @DisplayName("the five declared widths are the ones CSUSR01Y publishes")
        void theFiveDeclaredWidthsAreTheCopybookWidths() {
            assertThat(UserSecurityRecordMapper.SEC_USR_ID_LENGTH).isEqualTo(8);
            assertThat(UserSecurityRecordMapper.SEC_USR_FNAME_LENGTH).isEqualTo(20);
            assertThat(UserSecurityRecordMapper.SEC_USR_LNAME_LENGTH).isEqualTo(20);
            assertThat(UserSecurityRecordMapper.SEC_USR_PWD_LENGTH).isEqualTo(8);
            assertThat(UserSecurityRecordMapper.SEC_USR_TYPE_LENGTH).isEqualTo(1);
        }

        @Test
        @DisplayName("the mapped length is the sum of the five declared widths")
        void theMappedLengthIsTheSumOfTheFiveWidths() {
            assertThat(UserSecurityRecordMapper.MAPPED_LENGTH)
                    .isEqualTo(UserSecurityRecordMapper.SEC_USR_ID_LENGTH
                            + UserSecurityRecordMapper.SEC_USR_FNAME_LENGTH
                            + UserSecurityRecordMapper.SEC_USR_LNAME_LENGTH
                            + UserSecurityRecordMapper.SEC_USR_PWD_LENGTH
                            + UserSecurityRecordMapper.SEC_USR_TYPE_LENGTH);
        }

        @Test
        @DisplayName("filler begins where the mapped fields end")
        void fillerBeginsWhereTheMappedFieldsEnd() {
            assertThat(UserSecurityRecordMapper.SEC_USR_FILLER_OFFSET)
                    .isEqualTo(UserSecurityRecordMapper.MAPPED_LENGTH);
        }

        @Test
        @DisplayName("the reproducible prefix is the identifier and the two names, and stops there")
        void theReproduciblePrefixIsTheIdentifierAndTheTwoNames() {
            assertThat(UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_OFFSET)
                    .isEqualTo(UserSecurityRecordMapper.SEC_USR_ID_OFFSET)
                    .isZero();
            assertThat(UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_LENGTH)
                    .isEqualTo(UserSecurityRecordMapper.SEC_USR_ID_LENGTH
                            + UserSecurityRecordMapper.SEC_USR_FNAME_LENGTH
                            + UserSecurityRecordMapper.SEC_USR_LNAME_LENGTH)
                    .isEqualTo(48);
            assertThat(UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_OFFSET
                    + UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_LENGTH)
                    .isEqualTo(UserSecurityRecordMapper.SEC_USR_PWD_OFFSET);
        }

        @Test
        @DisplayName("the reproducible suffix is the type byte alone, sitting after the credential")
        void theReproducibleSuffixIsTheTypeByteAlone() {
            assertThat(UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_OFFSET)
                    .isEqualTo(UserSecurityRecordMapper.SEC_USR_TYPE_OFFSET);
            assertThat(UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_LENGTH)
                    .isEqualTo(UserSecurityRecordMapper.SEC_USR_TYPE_LENGTH)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the credential field is the only mapped region outside both reproducible ones")
        void theCredentialFieldIsTheOnlyUnreproducedMappedRegion() {
            final int prefixEnd = UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_OFFSET
                    + UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_LENGTH;

            assertThat(prefixEnd).isEqualTo(UserSecurityRecordMapper.SEC_USR_PWD_OFFSET);
            assertThat(UserSecurityRecordMapper.SEC_USR_PWD_OFFSET
                    + UserSecurityRecordMapper.SEC_USR_PWD_LENGTH)
                    .isEqualTo(UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_OFFSET);
        }

        @Test
        @DisplayName("the artefact and copybook names are published for diagnostics")
        void theArtefactAndCopybookNamesArePublished() {
            assertThat(UserSecurityRecordMapper.ARTEFACT).isEqualTo("SEC-USER-DATA");
            assertThat(UserSecurityRecordMapper.COPYBOOK).isEqualTo("CSUSR01Y");
        }

        @Test
        @DisplayName("the mapper is a final class whose private constructor refuses instantiation")
        void theMapperIsFinalAndRefusesInstantiation() throws NoSuchMethodException {
            assertThat(Modifier.isFinal(UserSecurityRecordMapper.class.getModifiers())).isTrue();

            final Constructor<UserSecurityRecordMapper> constructor =
                    UserSecurityRecordMapper.class.getDeclaredConstructor();
            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class)
                    .satisfies(wrapper -> assertThat(wrapper.getCause())
                            .hasMessage("UserSecurityRecordMapper is a static contract and is not"
                                    + " instantiable"));
        }
    }

    // =================================================================================
    // Decoding a synthesised record
    // =================================================================================

    @Nested
    @DisplayName("Decoding a synthesised record")
    class DecodingASynthesisedRecord {

        @Test
        @DisplayName("the synthesiser produces images at exactly the declared record width")
        void theSynthesiserProducesImagesAtTheDeclaredWidth() {
            for (int ordinal = 0; ordinal < LEGACY_SEED_ROWS.length; ordinal++) {
                assertThat(seedRecordImage(ordinal))
                        .hasSize(UserSecurityRecordMapper.RECORD_LENGTH);
            }
        }

        @Test
        @DisplayName("a well-formed image decodes its four plain fields at their declared widths")
        void aWellFormedImageDecodesItsFourPlainFields() {
            final UserSecurity user = UserSecurityRecordMapper
                    .fromRecord(seedRecordImage(0), fixedDigest());

            assertThat(user.getSecUsrId()).isEqualTo("ADMIN001");
            assertThat(user.getSecUsrFname())
                    .isEqualTo(spacePadded("MARGARET",
                            UserSecurityRecordMapper.SEC_USR_FNAME_LENGTH));
            assertThat(user.getSecUsrLname())
                    .isEqualTo(spacePadded("GOLD",
                            UserSecurityRecordMapper.SEC_USR_LNAME_LENGTH));
            assertThat(user.getSecUsrType()).isEqualTo(ADMINISTRATOR_TYPE);
        }

        @Test
        @DisplayName("field values are returned verbatim, so trailing spaces are not trimmed away")
        void fieldValuesAreReturnedVerbatim() {
            final UserSecurity user = UserSecurityRecordMapper
                    .fromRecord(seedRecordImage(9), fixedDigest());

            assertThat(user.getSecUsrFname())
                    .hasSize(UserSecurityRecordMapper.SEC_USR_FNAME_LENGTH)
                    .startsWith("LEE")
                    .endsWith(" ");
            assertThat(user.getSecUsrLname())
                    .hasSize(UserSecurityRecordMapper.SEC_USR_LNAME_LENGTH)
                    .startsWith("TING");
        }

        @Test
        @DisplayName("the digest function receives the eight raw credential bytes verbatim")
        void theDigestFunctionReceivesTheRawCredentialBytes() {
            final List<String> observed = new ArrayList<>();

            UserSecurityRecordMapper.fromRecord(seedRecordImage(0), credential -> {
                observed.add(credential);
                return VALID_BCRYPT_DIGEST;
            });

            assertThat(observed).containsExactly(LEGACY_SEED_PASSWORD);
            assertThat(observed.get(0)).hasSize(UserSecurityRecordMapper.SEC_USR_PWD_LENGTH);
        }

        @Test
        @DisplayName("a credential narrower than its field arrives space-padded, never trimmed")
        void aNarrowCredentialArrivesSpacePadded() {
            final List<String> observed = new ArrayList<>();
            final String image = recordImage("USER0009", "SHORT", "PASSWORD", "abc",
                    STANDARD_USER_TYPE);

            UserSecurityRecordMapper.fromRecord(image, credential -> {
                observed.add(credential);
                return VALID_BCRYPT_DIGEST;
            });

            assertThat(observed).containsExactly("abc     ");
        }

        @Test
        @DisplayName("what the digest function returns becomes the stored credential")
        void whatTheDigestFunctionReturnsBecomesTheStoredCredential() {
            final UserSecurity user = UserSecurityRecordMapper
                    .fromRecord(seedRecordImage(0), credential -> OTHER_BCRYPT_DIGEST);

            assertThat(SensitiveValues.fingerprint(user.credentialDigest())).isEqualTo(SensitiveValues.fingerprint(OTHER_BCRYPT_DIGEST));
        }

        @Test
        @DisplayName("the digest function is invoked exactly once per record")
        void theDigestFunctionIsInvokedExactlyOncePerRecord() {
            final List<String> invocations = new ArrayList<>();

            UserSecurityRecordMapper.fromRecord(seedRecordImage(3), credential -> {
                invocations.add(credential);
                return VALID_BCRYPT_DIGEST;
            });

            assertThat(invocations).hasSize(1);
        }

        @Test
        @DisplayName("decoding from a byte array agrees with decoding from a string")
        void decodingFromBytesAgreesWithDecodingFromAString() {
            for (int ordinal = 0; ordinal < LEGACY_SEED_ROWS.length; ordinal++) {
                final String image = seedRecordImage(ordinal);

                final UserSecurity fromString =
                        UserSecurityRecordMapper.fromRecord(image, fixedDigest());
                final UserSecurity fromBytes = UserSecurityRecordMapper
                        .fromRecord(image.getBytes(StandardCharsets.US_ASCII), fixedDigest());

                assertThat(fromBytes.getSecUsrId()).isEqualTo(fromString.getSecUsrId());
                assertThat(fromBytes.getSecUsrFname()).isEqualTo(fromString.getSecUsrFname());
                assertThat(fromBytes.getSecUsrLname()).isEqualTo(fromString.getSecUsrLname());
                assertThat(fromBytes.getSecUsrType()).isEqualTo(fromString.getSecUsrType());
                assertThat(fromBytes.credentialDigest())
                        .isEqualTo(fromString.credentialDigest());
            }
        }

        @Test
        @DisplayName("decoding a slice of a multi-record buffer agrees with the isolated record")
        void decodingASliceAgreesWithTheIsolatedRecord() {
            final StringBuilder dataset = new StringBuilder();
            for (int ordinal = 0; ordinal < LEGACY_SEED_ROWS.length; ordinal++) {
                dataset.append(seedRecordImage(ordinal));
            }
            final byte[] buffer = dataset.toString().getBytes(StandardCharsets.US_ASCII);

            for (int ordinal = 0; ordinal < LEGACY_SEED_ROWS.length; ordinal++) {
                final UserSecurity sliced = UserSecurityRecordMapper.fromRecord(buffer,
                        ordinal * UserSecurityRecordMapper.RECORD_LENGTH, fixedDigest());
                final UserSecurity isolated = UserSecurityRecordMapper
                        .fromRecord(seedRecordImage(ordinal), fixedDigest());

                assertThat(sliced.getSecUsrId()).isEqualTo(isolated.getSecUsrId());
                assertThat(sliced.getSecUsrFname()).isEqualTo(isolated.getSecUsrFname());
                assertThat(sliced.getSecUsrLname()).isEqualTo(isolated.getSecUsrLname());
                assertThat(sliced.getSecUsrType()).isEqualTo(isolated.getSecUsrType());
            }
        }

        @Test
        @DisplayName("all ten DUSRSECJ seed rows decode, five administrators and five users")
        void allTenSeedRowsDecode() {
            final List<UserSecurity> decoded = new ArrayList<>();
            for (int ordinal = 0; ordinal < LEGACY_SEED_ROWS.length; ordinal++) {
                decoded.add(UserSecurityRecordMapper
                        .fromRecord(seedRecordImage(ordinal), fixedDigest()));
            }

            assertThat(decoded).hasSize(10);
            assertThat(decoded).filteredOn(user ->
                    ADMINISTRATOR_TYPE.equals(user.getSecUsrType())).hasSize(5);
            assertThat(decoded).filteredOn(user ->
                    STANDARD_USER_TYPE.equals(user.getSecUsrType())).hasSize(5);
            final List<String> identifiers = new ArrayList<>();
            for (final UserSecurity user : decoded) {
                identifiers.add(user.getSecUsrId());
            }
            assertThat(identifiers)
                    .doesNotHaveDuplicates()
                    .allSatisfy(id -> assertThat(id)
                            .hasSize(UserSecurityRecordMapper.SEC_USR_ID_LENGTH));
        }

        @ParameterizedTest
        @ValueSource(strings = {ADMINISTRATOR_TYPE, STANDARD_USER_TYPE})
        @DisplayName("both legacy type bytes survive the decode unchanged")
        void bothLegacyTypeBytesSurviveTheDecode(final String typeByte) {
            final String image =
                    recordImage("USER0000", "First", "Last", LEGACY_SEED_PASSWORD, typeByte);

            assertThat(UserSecurityRecordMapper.fromRecord(image, fixedDigest()).getSecUsrType())
                    .isEqualTo(typeByte);
        }

        @Test
        @DisplayName("the decoded entity renders only its identifier, never a name or a credential")
        void theDecodedEntityRendersOnlyItsIdentifier() {
            final UserSecurity user = UserSecurityRecordMapper
                    .fromRecord(seedRecordImage(0), fixedDigest());

            assertThat(user).hasToString("UserSecurity[secUsrId=ADMIN001]");
            assertThat(user.toString())
                    .doesNotContain(VALID_BCRYPT_DIGEST)
                    .doesNotContain(LEGACY_SEED_PASSWORD)
                    .doesNotContain("MARGARET")
                    .doesNotContain("GOLD");
        }
    }

    // =================================================================================
    // The credential is never emitted
    // =================================================================================

    @Nested
    @DisplayName("The credential is never emitted")
    class TheCredentialIsNeverEmitted {

        @Test
        @DisplayName("the emitted record writes eight spaces where the credential field sits")
        void theEmittedRecordWritesSpacesWhereTheCredentialSits() {
            final String emitted = UserSecurityRecordMapper
                    .toRecord(aUser("ADMIN001", "MARGARET", "GOLD", ADMINISTRATOR_TYPE));

            assertThat(credentialRegionOf(emitted))
                    .isEqualTo(" ".repeat(UserSecurityRecordMapper.SEC_USR_PWD_LENGTH))
                    .isBlank();
        }

        @Test
        @DisplayName("the emitted record never contains the stored digest")
        void theEmittedRecordNeverContainsTheStoredDigest() {
            final UserSecurity user = new UserSecurity("ADMIN001", "MARGARET", "GOLD",
                    OTHER_BCRYPT_DIGEST, ADMINISTRATOR_TYPE);

            final String emitted = UserSecurityRecordMapper.toRecord(user);

            assertThat(emitted).doesNotContain(OTHER_BCRYPT_DIGEST);
            assertThat(emitted).doesNotContain("$2b$");
            assertThat(emitted).doesNotContain(OTHER_BCRYPT_DIGEST.substring(0, 12));
        }

        @Test
        @DisplayName("the emitted record never contains the cleartext the record was decoded from")
        void theEmittedRecordNeverContainsTheDecodedCleartext() {
            final UserSecurity user = UserSecurityRecordMapper
                    .fromRecord(seedRecordImage(0), fixedDigest());

            final String emitted = UserSecurityRecordMapper.toRecord(user);

            assertThat(emitted).doesNotContain(LEGACY_SEED_PASSWORD);
            assertThat(emitted).doesNotContain(VALID_BCRYPT_DIGEST);
        }

        @Test
        @DisplayName("the emitted bytes never contain the stored digest either")
        void theEmittedBytesNeverContainTheStoredDigest() {
            final UserSecurity user = new UserSecurity("ADMIN001", "MARGARET", "GOLD",
                    OTHER_BCRYPT_DIGEST, ADMINISTRATOR_TYPE);

            final String rendered = new String(UserSecurityRecordMapper.toRecordBytes(user),
                    StandardCharsets.US_ASCII);

            assertThat(rendered).doesNotContain(OTHER_BCRYPT_DIGEST);
            assertThat(rendered).isEqualTo(UserSecurityRecordMapper.toRecord(user));
        }

        @Test
        @DisplayName("the reproducible prefix survives the round trip byte for byte")
        void theReproduciblePrefixSurvivesTheRoundTrip() {
            for (int ordinal = 0; ordinal < LEGACY_SEED_ROWS.length; ordinal++) {
                final String image = seedRecordImage(ordinal);
                final String emitted = UserSecurityRecordMapper.toRecord(
                        UserSecurityRecordMapper.fromRecord(image, fixedDigest()));

                assertThat(emitted.substring(UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_OFFSET,
                        UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_OFFSET
                                + UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_LENGTH))
                        .isEqualTo(image.substring(
                                UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_OFFSET,
                                UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_OFFSET
                                        + UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_LENGTH));
            }
        }

        @Test
        @DisplayName("the reproducible suffix survives the round trip byte for byte")
        void theReproducibleSuffixSurvivesTheRoundTrip() {
            for (int ordinal = 0; ordinal < LEGACY_SEED_ROWS.length; ordinal++) {
                final String image = seedRecordImage(ordinal);
                final String emitted = UserSecurityRecordMapper.toRecord(
                        UserSecurityRecordMapper.fromRecord(image, fixedDigest()));

                assertThat(emitted.substring(UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_OFFSET,
                        UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_OFFSET
                                + UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_LENGTH))
                        .isEqualTo(image.substring(
                                UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_OFFSET,
                                UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_OFFSET
                                        + UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_LENGTH));
            }
        }

        @Test
        @DisplayName("the round trip is not byte-identical, and the divergence is only the credential")
        void theRoundTripDivergesOnlyInTheCredentialField() {
            final String image = seedRecordImage(0);

            final String emitted = UserSecurityRecordMapper.toRecord(
                    UserSecurityRecordMapper.fromRecord(image, fixedDigest()));

            assertThat(emitted)
                    .hasSize(UserSecurityRecordMapper.RECORD_LENGTH)
                    .isNotEqualTo(image);

            final List<Integer> divergentPositions = new ArrayList<>();
            for (int position = 0; position < UserSecurityRecordMapper.RECORD_LENGTH; position++) {
                if (emitted.charAt(position) != image.charAt(position)) {
                    divergentPositions.add(position);
                }
            }

            assertThat(divergentPositions)
                    .isNotEmpty()
                    .allSatisfy(position -> assertThat(position)
                            .isGreaterThanOrEqualTo(UserSecurityRecordMapper.SEC_USR_PWD_OFFSET)
                            .isLessThan(UserSecurityRecordMapper.SEC_USR_PWD_OFFSET
                                    + UserSecurityRecordMapper.SEC_USR_PWD_LENGTH));
        }

        @Test
        @DisplayName("a re-decode of an emitted image hands the digest function only spaces")
        void aReDecodeOfAnEmittedImageHandsTheDigestFunctionOnlySpaces() {
            final String emitted = UserSecurityRecordMapper.toRecord(
                    UserSecurityRecordMapper.fromRecord(seedRecordImage(0), fixedDigest()));
            final List<String> observed = new ArrayList<>();

            UserSecurityRecordMapper.fromRecord(emitted, credential -> {
                observed.add(credential);
                return VALID_BCRYPT_DIGEST;
            });

            assertThat(observed).containsExactly(
                    " ".repeat(UserSecurityRecordMapper.SEC_USR_PWD_LENGTH));
        }

        @Test
        @DisplayName("a second encode cycle is stable, because the credential region is already blank")
        void aSecondEncodeCycleIsStable() {
            final String firstPass = UserSecurityRecordMapper.toRecord(
                    UserSecurityRecordMapper.fromRecord(seedRecordImage(0), fixedDigest()));

            final String secondPass = UserSecurityRecordMapper.toRecord(
                    UserSecurityRecordMapper.fromRecord(firstPass, fixedDigest()));

            assertThat(secondPass).isEqualTo(firstPass);
        }
    }

    // =================================================================================
    // Encoding the four reproducible fields
    // =================================================================================

    @Nested
    @DisplayName("Encoding the four reproducible fields")
    class EncodingTheFourReproducibleFields {

        @Test
        @DisplayName("the emitted record is the declared width and its trailing filler is spaces")
        void theEmittedRecordIsTheDeclaredWidthWithSpaceFiller() {
            final String emitted = UserSecurityRecordMapper
                    .toRecord(aUser("USER0001", "LAWRENCE", "THOMAS", STANDARD_USER_TYPE));

            assertThat(emitted).hasSize(UserSecurityRecordMapper.RECORD_LENGTH);
            assertThat(emitted.substring(UserSecurityRecordMapper.SEC_USR_FILLER_OFFSET))
                    .isEqualTo(" ".repeat(UserSecurityRecordMapper.SEC_USR_FILLER_LENGTH));
        }

        @Test
        @DisplayName("the byte emitter agrees with the string emitter")
        void theByteEmitterAgreesWithTheStringEmitter() {
            final UserSecurity user =
                    aUser("USER0002", "AJITH", "KUMAR", STANDARD_USER_TYPE);

            assertThat(UserSecurityRecordMapper.toRecordBytes(user))
                    .isEqualTo(UserSecurityRecordMapper.toRecord(user)
                            .getBytes(StandardCharsets.US_ASCII))
                    .hasSize(UserSecurityRecordMapper.RECORD_LENGTH);
        }

        @Test
        @DisplayName("all four reproducible values are left-justified and space-padded")
        void allFourReproducibleValuesAreLeftJustifiedAndSpacePadded() {
            final String emitted =
                    UserSecurityRecordMapper.toRecord(aUser("U1", "A", "B", ADMINISTRATOR_TYPE));

            assertThat(emitted.substring(UserSecurityRecordMapper.SEC_USR_ID_OFFSET,
                    UserSecurityRecordMapper.SEC_USR_FNAME_OFFSET)).isEqualTo("U1      ");
            assertThat(emitted.substring(UserSecurityRecordMapper.SEC_USR_FNAME_OFFSET,
                    UserSecurityRecordMapper.SEC_USR_LNAME_OFFSET))
                    .isEqualTo(spacePadded("A", UserSecurityRecordMapper.SEC_USR_FNAME_LENGTH));
            assertThat(emitted.substring(UserSecurityRecordMapper.SEC_USR_LNAME_OFFSET,
                    UserSecurityRecordMapper.SEC_USR_PWD_OFFSET))
                    .isEqualTo(spacePadded("B", UserSecurityRecordMapper.SEC_USR_LNAME_LENGTH));
            assertThat(emitted.substring(UserSecurityRecordMapper.SEC_USR_TYPE_OFFSET,
                    UserSecurityRecordMapper.SEC_USR_FILLER_OFFSET))
                    .isEqualTo(ADMINISTRATOR_TYPE);
        }

        @Test
        @DisplayName("values at exactly their declared width are emitted with no padding at all")
        void valuesAtTheirDeclaredWidthAreEmittedUnpadded() {
            final String firstName = "X".repeat(UserSecurityRecordMapper.SEC_USR_FNAME_LENGTH);
            final String lastName = "Y".repeat(UserSecurityRecordMapper.SEC_USR_LNAME_LENGTH);
            final String userId = "Z".repeat(UserSecurityRecordMapper.SEC_USR_ID_LENGTH);

            final String emitted = UserSecurityRecordMapper
                    .toRecord(aUser(userId, firstName, lastName, ADMINISTRATOR_TYPE));

            assertThat(emitted.substring(UserSecurityRecordMapper.SEC_USR_ID_OFFSET,
                    UserSecurityRecordMapper.SEC_USR_FNAME_OFFSET)).isEqualTo(userId);
            assertThat(emitted.substring(UserSecurityRecordMapper.SEC_USR_FNAME_OFFSET,
                    UserSecurityRecordMapper.SEC_USR_LNAME_OFFSET)).isEqualTo(firstName);
            assertThat(emitted.substring(UserSecurityRecordMapper.SEC_USR_LNAME_OFFSET,
                    UserSecurityRecordMapper.SEC_USR_PWD_OFFSET)).isEqualTo(lastName);
        }

        @Test
        @DisplayName("a re-decode of an emitted image recovers the four reproducible fields")
        void aReDecodeRecoversTheFourReproducibleFields() {
            final UserSecurity original =
                    aUser("ADMIN005", "GRANVILLE", "LACHAPELLE", ADMINISTRATOR_TYPE);

            final UserSecurity reDecoded = UserSecurityRecordMapper.fromRecord(
                    UserSecurityRecordMapper.toRecord(original), fixedDigest());

            assertThat(reDecoded.getSecUsrId())
                    .isEqualTo(spacePadded("ADMIN005",
                            UserSecurityRecordMapper.SEC_USR_ID_LENGTH));
            assertThat(reDecoded.getSecUsrFname())
                    .isEqualTo(spacePadded("GRANVILLE",
                            UserSecurityRecordMapper.SEC_USR_FNAME_LENGTH));
            assertThat(reDecoded.getSecUsrLname())
                    .isEqualTo(spacePadded("LACHAPELLE",
                            UserSecurityRecordMapper.SEC_USR_LNAME_LENGTH));
            assertThat(reDecoded.getSecUsrType()).isEqualTo(ADMINISTRATOR_TYPE);
        }

        @ParameterizedTest
        @CsvSource({
            "ADMIN001, MARGABCD, GOLD, A",
            "USER0005, LEE, TING, U",
            "U, F, L, A",
        })
        @DisplayName("every emitted image places the type byte at its declared offset")
        void everyEmittedImagePlacesTheTypeByteAtItsOffset(final String userId,
                final String firstName, final String lastName, final String userType) {
            final String emitted =
                    UserSecurityRecordMapper.toRecord(aUser(userId, firstName, lastName, userType));

            assertThat(emitted.charAt(UserSecurityRecordMapper.SEC_USR_TYPE_OFFSET))
                    .isEqualTo(userType.charAt(0));
        }
    }

    // =================================================================================
    // Refusals and their diagnostics
    // =================================================================================

    @Nested
    @DisplayName("Refusals and their diagnostics")
    class RefusalsAndTheirDiagnostics {

        @Test
        @DisplayName("a null record image is refused by both whole-image readers")
        void aNullRecordImageIsRefusedByBothWholeImageReaders() {
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper
                            .fromRecord((String) null, fixedDigest()))
                    .withMessage("recordImage must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper
                            .fromRecord((byte[]) null, fixedDigest()))
                    .withMessage("recordImage must not be null");
        }

        @Test
        @DisplayName("a null buffer is refused by the slicing reader under its own parameter name")
        void aNullBufferIsRefusedByTheSlicingReader() {
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(null, 0, fixedDigest()))
                    .withMessage("buffer must not be null");
        }

        @Test
        @DisplayName("an absent digest function is refused by all three readers, with no fallback")
        void anAbsentDigestFunctionIsRefusedByAllThreeReaders() {
            final String image = seedRecordImage(0);
            final byte[] bytes = image.getBytes(StandardCharsets.US_ASCII);
            final String expected = "credentialDigestFunction must not be null: mapping a "
                    + UserSecurityRecordMapper.ARTEFACT + " record requires the caller's digest"
                    + " function, because this mapper deliberately provides no path that maps a"
                    + " record without hashing its credential";

            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(image, null))
                    .withMessage(expected);
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(bytes, null))
                    .withMessage(expected);
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(bytes, 0, null))
                    .withMessage(expected);
        }

        @Test
        @DisplayName("a digest function that returns null breaks its contract and is refused")
        void aDigestFunctionReturningNullIsRefused() {
            final String image = seedRecordImage(0);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper
                            .fromRecord(image, credential -> null))
                    .withMessageContaining("the supplied credentialDigestFunction returned null")
                    .withMessageContaining("breaking its contract")
                    .withMessageContaining("SEC-USR-PWD")
                    .withMessageContaining("non-nullable digest column")
                    .withMessageContaining("no default, no fallback and no unhashed path");
        }

        @Test
        @DisplayName("the null-digest refusal does not echo the credential it was handed")
        void theNullDigestRefusalDoesNotEchoTheCredential() {
            final String image =
                    recordImage("USER0001", "First", "Last", "S3CR3T!!", STANDARD_USER_TYPE);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper
                            .fromRecord(image, credential -> null))
                    .satisfies(refusal ->
                            assertThat(refusal.getMessage()).doesNotContain("S3CR3T"));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 57, 79, 81, 160})
        @DisplayName("an image of the wrong width is refused rather than padded or truncated")
        void anImageOfTheWrongWidthIsRefused(final int width) {
            final String image = "X".repeat(width);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(image, fixedDigest()))
                    .withMessageContaining(UserSecurityRecordMapper.ARTEFACT)
                    .withMessageContaining(UserSecurityRecordMapper.COPYBOOK)
                    .withMessageContaining("must be exactly "
                            + UserSecurityRecordMapper.RECORD_LENGTH + " encoded bytes")
                    .withMessageContaining("image is " + width + " encoded bytes")
                    .withMessageContaining("never padded or truncated to fit");
        }

        @Test
        @DisplayName("the width refusal names both regions of the record it expected")
        void theWidthRefusalNamesBothRegionsItExpected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord("X", fixedDigest()))
                    .withMessageContaining("(" + UserSecurityRecordMapper.MAPPED_LENGTH
                            + " mapped plus " + UserSecurityRecordMapper.SEC_USR_FILLER_LENGTH
                            + " bytes of named trailing filler)");
        }

        @Test
        @DisplayName("the width refusal states outright that it reports lengths only")
        void theWidthRefusalStatesThatItReportsLengthsOnly() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord("X", fixedDigest()))
                    .withMessageContaining("this diagnostic reports lengths only")
                    .withMessageContaining("echoing the record would print a credential");
        }

        @Test
        @DisplayName("the width refusal never echoes the credential the image carried")
        void theWidthRefusalNeverEchoesTheCredential() {
            final String overlong =
                    seedRecordImage(0).substring(0, UserSecurityRecordMapper.SEC_USR_PWD_OFFSET)
                            + "S3CR3T!!" + ADMINISTRATOR_TYPE
                            + " ".repeat(UserSecurityRecordMapper.SEC_USR_FILLER_LENGTH) + "\n";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(overlong, fixedDigest()))
                    .satisfies(refusal -> {
                        assertThat(refusal.getMessage()).doesNotContain("S3CR3T");
                        assertThat(refusal.getMessage()).doesNotContain("MARGARET");
                        assertThat(refusal.getMessage()).doesNotContain("ADMIN001");
                    });
        }

        @Test
        @DisplayName("the width refusal never echoes injected separators either")
        void theWidthRefusalNeverEchoesInjectedSeparators() {
            final String hostile = "ID\r\nWARN injected\u0000tail";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(hostile, fixedDigest()))
                    .satisfies(refusal -> {
                        assertThat(refusal.getMessage()).doesNotContain("\r");
                        assertThat(refusal.getMessage()).doesNotContain("\n");
                        assertThat(refusal.getMessage()).doesNotContain("\u0000");
                        assertThat(refusal.getMessage()).doesNotContain("injected");
                    });
        }

        @Test
        @DisplayName("a byte array of the wrong length is refused by the same length-only diagnostic")
        void aByteArrayOfTheWrongLengthIsRefused() {
            final byte[] shortImage = new byte[UserSecurityRecordMapper.RECORD_LENGTH - 1];

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper
                            .fromRecord(shortImage, fixedDigest()))
                    .withMessageContaining("image is " + shortImage.length + " encoded bytes")
                    .withMessageContaining("reports lengths only");
        }

        @Test
        @DisplayName("a null user is refused by both emitters")
        void aNullUserIsRefusedByBothEmitters() {
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecord(null))
                    .withMessage("user must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecordBytes(null))
                    .withMessage("user must not be null");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "SEC-USR-ID", "SEC-USR-FNAME", "SEC-USR-LNAME", "SEC-USR-TYPE"})
        @DisplayName("each absent reproducible property is refused by its copybook field name")
        void eachAbsentReproduciblePropertyIsRefusedByName(final String fieldName) {
            final UserSecurity user = new UserSecurity(
                    "SEC-USR-ID".equals(fieldName) ? null : "ADMIN001",
                    "SEC-USR-FNAME".equals(fieldName) ? null : "MARGARET",
                    "SEC-USR-LNAME".equals(fieldName) ? null : "GOLD",
                    VALID_BCRYPT_DIGEST,
                    "SEC-USR-TYPE".equals(fieldName) ? null : ADMINISTRATOR_TYPE);

            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecord(user))
                    .withMessage("SEC-USER-DATA (CSUSR01Y) field '" + fieldName + "' must not be"
                            + " null when a record image is assembled: a fixed-width field is"
                            + " emitted at its declared width, and an absent value has no width, so"
                            + " the record could only be completed by inventing one");
        }

        @Test
        @DisplayName("an absent property is refused without echoing any other field of the record")
        void anAbsentPropertyIsRefusedWithoutEchoingOtherFields() {
            final UserSecurity user = new UserSecurity(null, "MARGARET", "GOLD",
                    VALID_BCRYPT_DIGEST, ADMINISTRATOR_TYPE);

            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecord(user))
                    .satisfies(refusal -> {
                        assertThat(refusal.getMessage()).doesNotContain("MARGARET");
                        assertThat(refusal.getMessage()).doesNotContain(VALID_BCRYPT_DIGEST);
                    });
        }

        @Test
        @DisplayName("a value wider than its field is refused rather than truncated to fit")
        void aValueWiderThanItsFieldIsRefused() {
            final UserSecurity overlong = aUser("NINECHARS", "MARGARET", "GOLD",
                    ADMINISTRATOR_TYPE);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecord(overlong))
                    .withMessageContaining("SEC-USR-ID")
                    .withMessageContaining("field width is "
                            + UserSecurityRecordMapper.SEC_USR_ID_LENGTH + " encoded bytes")
                    .withMessageContaining("never truncated to fit");
        }

        @Test
        @DisplayName("a character US-ASCII cannot represent is refused rather than transcoded")
        void aNonAsciiCharacterIsRefused() {
            final UserSecurity accented =
                    aUser("ADMIN001", "MARG\u00c4RET", "GOLD", ADMINISTRATOR_TYPE);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecord(accented))
                    .withMessageContaining("SEC-USR-FNAME")
                    .withMessageContaining("US-ASCII cannot represent")
                    .withMessageContaining("must never be transcoded silently");
        }

        @Test
        @DisplayName("a slice that runs past the buffer end is refused with indices, not content")
        void aSliceRunningPastTheBufferEndIsRefused() {
            final byte[] buffer =
                    seedRecordImage(0).getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(buffer, 1,
                            fixedDigest()))
                    .withMessageContaining("does not fit inside the supplied buffer")
                    .withMessageContaining("from=1")
                    .withMessageContaining("recordWidth="
                            + UserSecurityRecordMapper.RECORD_LENGTH)
                    .withMessageContaining("buffer length=" + buffer.length)
                    .satisfies(refusal -> {
                        assertThat(refusal.getMessage()).doesNotContain(LEGACY_SEED_PASSWORD);
                        assertThat(refusal.getMessage()).doesNotContain("MARGARET");
                    });
        }

        @Test
        @DisplayName("a negative slice start is refused before any byte is read")
        void aNegativeSliceStartIsRefused() {
            final byte[] buffer = seedRecordImage(0).getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(buffer, -1,
                            fixedDigest()))
                    .withMessageContaining("start index must not be negative")
                    .withMessageContaining("from=-1");
        }
    }
}
