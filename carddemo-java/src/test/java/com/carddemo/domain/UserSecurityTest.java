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

import com.carddemo.support.SensitiveValues;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit test for {@link UserSecurity}, the entity form of the legacy 80-byte sign-on credential record.
 *
 * <p><strong>What is being proved.</strong> The record declares six fields over 80 bytes: an 8-byte
 * identifier at offset 0, a 20-byte given name at offset 8, a 20-byte family name at offset 28, an
 * 8-byte credential at offset 48, a 1-byte role code at offset 56, and a 23-byte trailing filler at
 * offset 57. Five of the six become columns; the filler does not. This test pins the widths, the
 * absence of any normalisation on the way in or out, the business-key identity, and the one place
 * where the target column is deliberately wider than the field it replaces.
 *
 * <p><strong>The credential property carries an already-hashed value, and this test never handles a
 * cleartext credential.</strong> Every credential argument used below is an obviously synthetic
 * stand-in that is not, and never was, anyone's credential. The legacy provisioning job carries a
 * single shared cleartext literal on all ten of its seeded cards; that literal appears nowhere in this
 * file, in any form. Only the non-secret parts of those cards - the identifiers, the given and family
 * names, and the role codes - are used as fixture data, because only those are not secret.
 *
 * <p><strong>Scope.</strong> This is a pure unit test over one entity. It starts no container, builds
 * no application context, touches no database, no network and no filesystem, and uses no reflection -
 * the latter because this tier's subject is the entity's behaviour rather than its metadata, not because
 * a test may not reflect: the module's unsafe-code audit scopes its zero reflection count to production
 * sources under {@code src/main/java}, so a suite that reflected would not undermine it.
 * Column names, nullability and the physical schema are deliberately not verified here - they are
 * asserted by {@code EntityPersistenceMappingTest}, which compares the mapping the provider computes
 * against the shipped migration {@code V1__create_schema.sql} and against an independent copybook-width
 * oracle, and which pins this record's credential column at the digest width it deliberately carries
 * instead of its 8-byte legacy field width. The module additionally runs with Hibernate schema
 * validation against a real database, which fails start-up on any mismatch in a deployed environment,
 * though that is a property of a deployment rather than a check this build performs. Neither is the record
 * image assembled here: laying the five properties back out across 80 bytes belongs to the
 * fixed-width mapper in the utility layer.
 *
 * <p><strong>Every expectation below is hand-derived</strong> from the copybook layout and the
 * provisioning job. No expected value is computed by calling the class under test.
 *
 * <p><strong>Divergences from the entity contract as originally described</strong>, each confirmed by
 * reading the production class and each commented again at the point of use:
 * <ol>
 *   <li>The credential accessors are deliberately outside the JavaBean naming convention -
 *       {@link UserSecurity#credentialDigest()} reads and
 *       {@link UserSecurity#replaceCredentialDigest(String)} writes - so that the stored value is not
 *       a discoverable property and cannot be emitted by serialization, a repository projection or a
 *       property-walking renderer.</li>
 *   <li>Both credential write paths <em>refuse</em> a value that is not structurally a hash, which
 *       makes the fixtures here necessarily hash-shaped rather than arbitrary markers. The refusal is
 *       structural only: the entity still performs no hashing, no verification and no comparison, and
 *       needs no encoder to apply it.</li>
 *   <li>The four non-credential setters are plain assignment, exactly as described - no trimming,
 *       padding, case folding, normalising or validating of any kind.</li>
 *   <li>A diagnostic rendering exists on the entity, so the single permitted assertion about it is
 *       made: that it does not carry the stored credential value.</li>
 * </ol>
 *
 * <p><strong>The credential column is the module's flagship documented parity exception.</strong>
 * Legacy sign-on compares the stored eight-character cleartext credential directly against what was
 * keyed at the terminal, at {@code app/cbl/COSGN00C.cbl} line 223. Reproducing that comparison would
 * satisfy byte-for-byte parity and breach the binding no-hardcoded-credentials requirement at the same
 * time, so hashing is applied instead as a deliberate, documented exception recorded in
 * {@code docs/decision-log.md}. It is an improvement over the legacy posture, not a regression from
 * it, and it is the only column in the schema whose width exceeds its legacy picture width.
 *
 * <p>Provenance: legacy checkout SHA 7756d895ffeb65f7ea72aaa609e356d9899afcec, upstream release stamp
 * CardDemo_v1.0-15-g27d6c6f-68 (2022-07-19). Recorded here as a header string only; no assertion is
 * made about it. Layout authority {@code app/cpy/CSUSR01Y.cpy}, seed authority
 * {@code app/jcl/DUSRSECJ.jcl}, routing authority {@code app/cbl/COSGN00C.cbl}. No source text from
 * the legacy estate is reproduced in this file: only member names, field names, widths, offsets, line
 * references and the non-secret seeded identities appear.
 */
@DisplayName("UserSecurity - the entity form of the 80-byte sign-on credential record")
class UserSecurityTest {

    // LAYOUT CONSTANTS - hand-derived from app/cpy/CSUSR01Y.cpy, not read back from the entity

    private static final int KEY_WIDTH = 8;

    private static final int GIVEN_NAME_WIDTH = 20;

    private static final int FAMILY_NAME_WIDTH = 20;

    private static final int LEGACY_CREDENTIAL_WIDTH = 8;

    private static final int ROLE_CODE_WIDTH = 1;

    private static final int UNMAPPED_FILLER_WIDTH = 23;

    private static final int RECORD_WIDTH = 80;

    private static final int SIGNIFICANT_WIDTH = 57;

    /**
     * Width of the target credential column. Wider than the legacy field because the column holds a
     * hash rather than the eight cleartext characters the legacy field held.
     */
    private static final int CREDENTIAL_COLUMN_WIDTH = 60;

    // SEEDED IDENTITY FIXTURES - the non-secret columns of the provisioning job's in-stream cards

    private static final String ROLE_ADMINISTRATOR = "A";

    private static final String ROLE_STANDARD = "U";

    private static final String ADMIN_IDENTIFIER = "ADMIN001";

    private static final String ADMIN_GIVEN_NAME = "MARGARET";

    private static final String ADMIN_FAMILY_NAME = "GOLD";

    private static final String OTHER_ADMIN_IDENTIFIER = "ADMIN002";

    // SYNTHETIC CREDENTIAL FIXTURES
    //
    // These are NOT credentials and NOT hashes of any credential. Each is a fabricated marker that
    // spells out what it is, and neither corresponds to any real or legacy value. They are shaped like
    // a stored hash for one mechanical reason only: both credential write paths on the entity refuse a
    // value that is not structurally shaped like one, so an arbitrary marker such as a short label
    // would be rejected before any property could be exercised. The shape is therefore dictated by the
    // production guard rather than chosen, and no security parameter of it is asserted, described or
    // relied upon anywhere in this file. Two distinct values exist because business-key identity has to
    // be proved to hold across a change of stored credential.

    private static final String SYNTHETIC_STORED_CREDENTIAL =
            "$2a$10$SYNTHETICDIGESTFORUNITTESTONLYNOTAREALCREDENTIAL00001";

    private static final String OTHER_SYNTHETIC_STORED_CREDENTIAL =
            "$2a$10$SYNTHETICDIGESTFORUNITTESTONLYNOTAREALCREDENTIAL00002";

    /**
     * A value that is deliberately not shaped like a stored hash, used to prove that the entity's write
     * paths refuse anything a cleartext credential could be. It is a label, not a credential.
     */
    private static final String NOT_A_STORED_CREDENTIAL = "NOT-A-STORED-HASH";

    /**
     * Blank-fills a value on the right to a fixed width, reproducing how the provisioning job's cards
     * carry the two name fields. Test-side only: the entity itself never pads.
     *
     * @param value the value to fill
     * @param width the fixed field width to fill it to
     * @return the value followed by enough spaces to reach {@code width}
     */
    private static String blankFill(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Counts encoded bytes rather than characters, because a field width in the legacy record is a byte
     * count. {@link String#length()} is deliberately not used for width assertions anywhere here.
     *
     * @param value the value to measure
     * @return the number of bytes {@code value} occupies when encoded
     */
    private static int encodedWidth(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Builds the first seeded administrator with both names blank-filled to their field widths and an
     * obviously synthetic stored credential.
     *
     * @return a fully populated instance
     */
    private static UserSecurity seededAdministrator() {
        return new UserSecurity(
                ADMIN_IDENTIFIER,
                blankFill(ADMIN_GIVEN_NAME, GIVEN_NAME_WIDTH),
                blankFill(ADMIN_FAMILY_NAME, FAMILY_NAME_WIDTH),
                SYNTHETIC_STORED_CREDENTIAL,
                ROLE_ADMINISTRATOR);
    }

    /**
     * Verifies the two routes by which an instance comes into existence: the all-argument constructor
     * used by application code, and the no-argument constructor the persistence provider uses.
     */
    @Nested
    @DisplayName("construction follows the copybook field order")
    class ConstructionAndHydration {

        @Test
        @DisplayName("the all-argument constructor takes the five mapped fields in copybook order and "
                + "returns each one unchanged")
        void theAllArgumentConstructorRoundTripsEveryMappedField() {
            final String givenName = blankFill(ADMIN_GIVEN_NAME, GIVEN_NAME_WIDTH);
            final String familyName = blankFill(ADMIN_FAMILY_NAME, FAMILY_NAME_WIDTH);

            final UserSecurity user = new UserSecurity(
                    ADMIN_IDENTIFIER, givenName, familyName, SYNTHETIC_STORED_CREDENTIAL,
                    ROLE_ADMINISTRATOR);

            assertThat(user.getSecUsrId()).isEqualTo(ADMIN_IDENTIFIER);
            assertThat(user.getSecUsrFname()).isEqualTo(givenName);
            assertThat(user.getSecUsrLname()).isEqualTo(familyName);
            // Read through the entity's non-bean accessor: the stored value is deliberately not exposed
            // as a discoverable property, so there is no getter following the bean convention to call.
            assertThat(SensitiveValues.fingerprint(user.credentialDigest()))
                    .isEqualTo(SensitiveValues.fingerprint(SYNTHETIC_STORED_CREDENTIAL));
            assertThat(user.getSecUsrType()).isEqualTo(ROLE_ADMINISTRATOR);
        }

        @Test
        @DisplayName("all five mapped fields can be replaced after construction and each returns exactly "
                + "what was written")
        void everyMappedFieldRoundTripsThroughItsWriter() {
            final UserSecurity user = seededAdministrator();
            final String replacementGivenName = blankFill("LAWRENCE", GIVEN_NAME_WIDTH);
            final String replacementFamilyName = blankFill("THOMAS", FAMILY_NAME_WIDTH);

            user.setSecUsrId("USER0001");
            user.setSecUsrFname(replacementGivenName);
            user.setSecUsrLname(replacementFamilyName);
            // The credential writer is likewise outside the bean convention, so that credential material
            // can never be bound into this entity automatically from an inbound request.
            user.replaceCredentialDigest(OTHER_SYNTHETIC_STORED_CREDENTIAL);
            user.setSecUsrType(ROLE_STANDARD);

            assertThat(user.getSecUsrId()).isEqualTo("USER0001");
            assertThat(user.getSecUsrFname()).isEqualTo(replacementGivenName);
            assertThat(user.getSecUsrLname()).isEqualTo(replacementFamilyName);
            assertThat(SensitiveValues.fingerprint(user.credentialDigest()))
                    .isEqualTo(SensitiveValues.fingerprint(OTHER_SYNTHETIC_STORED_CREDENTIAL));
            assertThat(user.getSecUsrType()).isEqualTo(ROLE_STANDARD);
        }

        @Test
        @DisplayName("the persistence provider's no-argument constructor yields an instance with every "
                + "mapped field absent, because no field carries a default")
        void theNoArgumentConstructorYieldsAnEmptyInstance() {
            // This test class sits in the same package as the entity, so ordinary Java package access
            // reaches the protected no-argument constructor directly. This is same-package visibility
            // and explicitly NOT reflection: no reflective call of any kind is made here. Package
            // access is simply the direct route rather than a way of staying inside a budget - the
            // module's unsafe-code audit scopes its zero reflection count to production sources under
            // src/main/java, so a test that reflected would not breach it.
            final UserSecurity hydrating = new UserSecurity();

            assertThat(hydrating.getSecUsrId()).isNull();
            assertThat(hydrating.getSecUsrFname()).isNull();
            assertThat(hydrating.getSecUsrLname()).isNull();
            // Left absent on purpose: no default, fallback or placeholder credential exists in source.
            assertThat(hydrating.credentialDigest()).isNull();
            assertThat(hydrating.getSecUsrType()).isNull();
        }
    }

    /**
     * Verifies the byte widths the record layout fixes, and the single width the target deliberately
     * departs from.
     */
    @Nested
    @DisplayName("the 80-byte record geometry")
    class RecordGeometry {

        @Test
        @DisplayName("the identifier, the two names and the role code occupy exactly 8, 20, 20 and 1 "
                + "encoded bytes")
        void theMappedFieldsOccupyTheirDeclaredByteWidths() {
            final UserSecurity user = seededAdministrator();

            // Byte counts, not character counts: a legacy field width is a byte width.
            assertThat(encodedWidth(user.getSecUsrId())).isEqualTo(KEY_WIDTH);
            assertThat(encodedWidth(user.getSecUsrFname())).isEqualTo(GIVEN_NAME_WIDTH);
            assertThat(encodedWidth(user.getSecUsrLname())).isEqualTo(FAMILY_NAME_WIDTH);
            assertThat(encodedWidth(user.getSecUsrType())).isEqualTo(ROLE_CODE_WIDTH);
        }

        @Test
        @DisplayName("the credential window widens from the legacy 8 bytes to a 60-character column, "
                + "which is the one target width that exceeds its legacy picture width")
        void theCredentialWindowWidensFromTheLegacyWidth() {
            // The legacy field held eight cleartext characters. The target column holds a hash instead,
            // and a hash does not fit in eight characters, so the two widths differ on purpose. Nothing
            // about the internal structure of a real hash is asserted here - only that the window the
            // entity can hold is wider than the window the legacy record had.
            assertThat(LEGACY_CREDENTIAL_WIDTH).isNotEqualTo(CREDENTIAL_COLUMN_WIDTH);
            assertThat(LEGACY_CREDENTIAL_WIDTH).isLessThan(CREDENTIAL_COLUMN_WIDTH);

            final UserSecurity user = seededAdministrator();
            assertThat(encodedWidth(user.credentialDigest())).isEqualTo(CREDENTIAL_COLUMN_WIDTH);
            assertThat(encodedWidth(user.credentialDigest())).isGreaterThan(LEGACY_CREDENTIAL_WIDTH);
        }

        @Test
        @DisplayName("the five mapped fields sum to 57 significant bytes of the 80-byte record, leaving "
                + "a 23-byte trailing filler that is uniquely named in the estate yet still unpersisted")
        void theMappedFieldsSumToFiftySevenOfEightyBytes() {
            final int mapped = KEY_WIDTH
                    + GIVEN_NAME_WIDTH
                    + FAMILY_NAME_WIDTH
                    + LEGACY_CREDENTIAL_WIDTH
                    + ROLE_CODE_WIDTH;

            assertThat(mapped).isEqualTo(SIGNIFICANT_WIDTH);

            // The remainder is the trailing filler. Alone among the record layouts in the estate this
            // filler carries a name of its own rather than being an anonymous one, and that changes
            // nothing: it becomes no property and no column. Five fields are mapped, not six.
            assertThat(RECORD_WIDTH - mapped).isEqualTo(UNMAPPED_FILLER_WIDTH);
            assertThat(mapped + UNMAPPED_FILLER_WIDTH).isEqualTo(RECORD_WIDTH);
        }
    }

    /**
     * Verifies that a value handed to the entity comes back out byte-for-byte identical. A fixed-width
     * record is blank-filled by construction, so silently trimming a stored name would change the bytes
     * the mapper later lays back out and would break the record image.
     */
    @Nested
    @DisplayName("stored values are never normalised")
    class AbsenceOfNormalisation {

        @Test
        @DisplayName("a name blank-filled to its 20-byte field width keeps its trailing padding, proving "
                + "the entity does not trim")
        void aBlankFilledNameKeepsItsTrailingPadding() {
            final String paddedGivenName = blankFill(ADMIN_GIVEN_NAME, GIVEN_NAME_WIDTH);
            assertThat(encodedWidth(paddedGivenName)).isEqualTo(GIVEN_NAME_WIDTH);
            assertThat(encodedWidth(ADMIN_GIVEN_NAME)).isLessThan(GIVEN_NAME_WIDTH);

            final UserSecurity user = new UserSecurity(
                    ADMIN_IDENTIFIER, paddedGivenName, blankFill(ADMIN_FAMILY_NAME, FAMILY_NAME_WIDTH),
                    SYNTHETIC_STORED_CREDENTIAL, ROLE_ADMINISTRATOR);

            // Byte equality against the padded input, so a trimmed or re-padded value cannot pass.
            assertThat(user.getSecUsrFname().getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(paddedGivenName.getBytes(StandardCharsets.US_ASCII));
            assertThat(encodedWidth(user.getSecUsrFname())).isEqualTo(GIVEN_NAME_WIDTH);
            // And explicitly not the unpadded value, which is what a trimming accessor would return.
            assertThat(user.getSecUsrFname()).isNotEqualTo(ADMIN_GIVEN_NAME);
        }

        @Test
        @DisplayName("a name blank-filled to its field width survives replacement through the setter too")
        void aBlankFilledNameSurvivesReplacement() {
            final String paddedFamilyName = blankFill("LACHAPELLE", FAMILY_NAME_WIDTH);
            final UserSecurity user = seededAdministrator();

            user.setSecUsrLname(paddedFamilyName);

            assertThat(user.getSecUsrLname().getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(paddedFamilyName.getBytes(StandardCharsets.US_ASCII));
            assertThat(user.getSecUsrLname()).isNotEqualTo("LACHAPELLE");
        }

        @ParameterizedTest(name = "[{index}] {0} {1} {2} role {3}")
        @CsvSource({
            "ADMIN001, MARGARET,  GOLD,       A",
            "ADMIN002, RUSSELL,   RUSSELL,    A",
            "ADMIN003, RAYMOND,   WHITMORE,   A",
            "ADMIN004, EMMANUEL,  CASGRAIN,   A",
            "ADMIN005, GRANVILLE, LACHAPELLE, A",
            "USER0001, LAWRENCE,  THOMAS,     U",
            "USER0002, AJITH,     KUMAR,      U",
            "USER0003, LAURITZ,   ALME,       U",
            "USER0004, AVERARDO,  MAZZI,      U",
            "USER0005, LEE,       TING,       U",
        })
        @DisplayName("every one of the ten identities the provisioning job carries in its own job stream "
                + "round-trips unchanged")
        void everySeededIdentityRoundTrips(String identifier,
                                           String givenName,
                                           String familyName,
                                           String roleCode) {
            final String paddedGivenName = blankFill(givenName, GIVEN_NAME_WIDTH);
            final String paddedFamilyName = blankFill(familyName, FAMILY_NAME_WIDTH);

            // The credential column of every card is replaced by an obviously synthetic stand-in. The
            // job's own cards all share one cleartext literal, which is a secret and is therefore not
            // reproduced here in any form.
            final UserSecurity user = new UserSecurity(
                    identifier, paddedGivenName, paddedFamilyName, SYNTHETIC_STORED_CREDENTIAL, roleCode);

            assertThat(user.getSecUsrId()).isEqualTo(identifier);
            assertThat(user.getSecUsrFname()).isEqualTo(paddedGivenName);
            assertThat(user.getSecUsrLname()).isEqualTo(paddedFamilyName);
            assertThat(user.getSecUsrType()).isEqualTo(roleCode);

            assertThat(encodedWidth(user.getSecUsrId())).isEqualTo(KEY_WIDTH);
            assertThat(encodedWidth(user.getSecUsrFname())).isEqualTo(GIVEN_NAME_WIDTH);
            assertThat(encodedWidth(user.getSecUsrLname())).isEqualTo(FAMILY_NAME_WIDTH);
            assertThat(encodedWidth(user.getSecUsrType())).isEqualTo(ROLE_CODE_WIDTH);
        }
    }

    /**
     * Verifies that the one byte the authorisation split rests on is stored raw, with no vocabulary
     * enforced at the entity boundary.
     */
    @Nested
    @DisplayName("the role code is stored raw")
    class RoleCode {

        @Test
        @DisplayName("the two declared role codes round-trip unchanged")
        void theTwoDeclaredRoleCodesRoundTrip() {
            final UserSecurity user = seededAdministrator();

            assertThat(user.getSecUsrType()).isEqualTo(ROLE_ADMINISTRATOR);

            user.setSecUsrType(ROLE_STANDARD);
            assertThat(user.getSecUsrType()).isEqualTo(ROLE_STANDARD);

            user.setSecUsrType(ROLE_ADMINISTRATOR);
            assertThat(user.getSecUsrType()).isEqualTo(ROLE_ADMINISTRATOR);
        }

        @Test
        @DisplayName("a role code outside the declared pair is accepted and returned unchanged, because "
                + "legacy sign-on tests only the administrator condition and reaches the main menu "
                + "through an unconditional alternative with no third branch")
        void anUndeclaredRoleCodeIsAcceptedUnchanged() {
            // Sign-on moves the stored code into the communication area at app/cbl/COSGN00C.cbl line 227,
            // tests the administrator condition at line 230, and falls through an unconditional ELSE at
            // line 235 that routes to the main menu, closing at line 240. There is no third branch and no
            // error path for a code the estate never declared, so every non-administrator value -
            // an unrecognised one included - routes to the main menu without raising anything. Rejecting
            // such a code at the persistence boundary would refuse data the legacy system silently
            // accepted, so the entity must not validate it.
            final UserSecurity user = seededAdministrator();

            user.setSecUsrType("Z");

            assertThat(user.getSecUsrType())
                    .as("an undeclared role code is stored verbatim")
                    .isEqualTo("Z");
            assertThat(encodedWidth(user.getSecUsrType())).isEqualTo(ROLE_CODE_WIDTH);

            // No exception, no default substitution and no normalisation: constructing with the same
            // undeclared code succeeds identically.
            final UserSecurity constructed = new UserSecurity(
                    "USER0001", blankFill("LAWRENCE", GIVEN_NAME_WIDTH),
                    blankFill("THOMAS", FAMILY_NAME_WIDTH), SYNTHETIC_STORED_CREDENTIAL, "Z");
            assertThat(constructed.getSecUsrType()).isEqualTo("Z");
        }

        @Test
        @DisplayName("a lower-case role code is not folded to upper case, so the stored byte is exactly "
                + "the byte supplied")
        void aLowerCaseRoleCodeIsNotFolded() {
            final UserSecurity user = seededAdministrator();

            user.setSecUsrType("a");

            assertThat(user.getSecUsrType()).isEqualTo("a");
            assertThat(user.getSecUsrType()).isNotEqualTo(ROLE_ADMINISTRATOR);
            assertThat(encodedWidth(user.getSecUsrType())).isEqualTo(ROLE_CODE_WIDTH);
        }

        @Test
        @DisplayName("a blank role code is stored as a blank rather than being defaulted, keeping the "
                + "one-byte field width intact")
        void aBlankRoleCodeIsStoredAsABlank() {
            final UserSecurity user = seededAdministrator();

            user.setSecUsrType(" ");

            assertThat(user.getSecUsrType()).isEqualTo(" ");
            assertThat(encodedWidth(user.getSecUsrType())).isEqualTo(ROLE_CODE_WIDTH);
        }
    }

    /**
     * Verifies that identity rests on the 8-byte sign-on identifier alone. The key is the leading
     * substring of the record image and is assigned rather than generated, so it is stable from
     * construction onward and key equality is well-defined even before an instance is persisted.
     */
    @Nested
    @DisplayName("identity rests on the sign-on identifier alone")
    class BusinessKeyIdentity {

        @Test
        @DisplayName("two records sharing an identifier are equal and share a hash code even when every "
                + "other mapped field differs, including the stored credential")
        void sameIdentifierMeansEqualEvenWhenEveryOtherFieldDiffers() {
            final UserSecurity left = new UserSecurity(
                    ADMIN_IDENTIFIER,
                    blankFill(ADMIN_GIVEN_NAME, GIVEN_NAME_WIDTH),
                    blankFill(ADMIN_FAMILY_NAME, FAMILY_NAME_WIDTH),
                    SYNTHETIC_STORED_CREDENTIAL,
                    ROLE_ADMINISTRATOR);
            final UserSecurity right = new UserSecurity(
                    ADMIN_IDENTIFIER,
                    blankFill("LAWRENCE", GIVEN_NAME_WIDTH),
                    blankFill("THOMAS", FAMILY_NAME_WIDTH),
                    OTHER_SYNTHETIC_STORED_CREDENTIAL,
                    ROLE_STANDARD);

            // Every field except the key differs, the stored credential included. Were the credential
            // part of identity, a credential change would move a record between hash buckets even though
            // the identity it denotes had not changed at all. Each side is checked against the fixture it
            // was built from rather than against the other side's accessor.
            assertThat(left.getSecUsrFname()).isEqualTo(blankFill(ADMIN_GIVEN_NAME, GIVEN_NAME_WIDTH));
            assertThat(right.getSecUsrFname()).isEqualTo(blankFill("LAWRENCE", GIVEN_NAME_WIDTH));
            assertThat(SensitiveValues.fingerprint(left.credentialDigest()))
                    .isEqualTo(SensitiveValues.fingerprint(SYNTHETIC_STORED_CREDENTIAL));
            assertThat(SensitiveValues.fingerprint(right.credentialDigest()))
                    .isEqualTo(SensitiveValues.fingerprint(OTHER_SYNTHETIC_STORED_CREDENTIAL));
            assertThat(SensitiveValues.fingerprint(SYNTHETIC_STORED_CREDENTIAL))
                    .isNotEqualTo(SensitiveValues.fingerprint(OTHER_SYNTHETIC_STORED_CREDENTIAL));
            assertThat(left.getSecUsrType()).isEqualTo(ROLE_ADMINISTRATOR);
            assertThat(right.getSecUsrType()).isEqualTo(ROLE_STANDARD);

            assertThat(left).isEqualTo(right);
            assertThat(right).isEqualTo(left);
            assertThat(left).hasSameHashCodeAs(right);
        }

        @Test
        @DisplayName("two records with different identifiers are unequal even when every other mapped "
                + "field matches")
        void differentIdentifiersMeanUnequal() {
            final UserSecurity left = seededAdministrator();
            final UserSecurity right = new UserSecurity(
                    OTHER_ADMIN_IDENTIFIER,
                    blankFill(ADMIN_GIVEN_NAME, GIVEN_NAME_WIDTH),
                    blankFill(ADMIN_FAMILY_NAME, FAMILY_NAME_WIDTH),
                    SYNTHETIC_STORED_CREDENTIAL,
                    ROLE_ADMINISTRATOR);

            assertThat(left).isNotEqualTo(right);
            assertThat(right).isNotEqualTo(left);
        }

        @Test
        @DisplayName("a record equals itself")
        void aRecordEqualsItself() {
            final UserSecurity user = seededAdministrator();

            assertThat(user).isEqualTo(user);
            assertThat(user.equals(user)).isTrue();
        }

        @Test
        @DisplayName("the hash code is the hash of the sign-on identifier alone, so no other mapped "
                + "field - the stored credential least of all - enters a hash-bucket computation")
        void theHashCodeIsTheHashOfTheIdentifierAlone() {
            // Independently derived from the stated contract rather than from the entity: hashing the key
            // alone means the result must equal the identifier's own hash. Two records that differ in
            // every field but the key therefore cannot land in different buckets.
            assertThat(seededAdministrator().hashCode()).isEqualTo(ADMIN_IDENTIFIER.hashCode());

            final UserSecurity other = new UserSecurity(
                    OTHER_ADMIN_IDENTIFIER,
                    blankFill("RUSSELL", GIVEN_NAME_WIDTH),
                    blankFill("RUSSELL", FAMILY_NAME_WIDTH),
                    OTHER_SYNTHETIC_STORED_CREDENTIAL,
                    ROLE_ADMINISTRATOR);
            assertThat(other.hashCode()).isEqualTo(OTHER_ADMIN_IDENTIFIER.hashCode());

            // Null-safe for an instance whose key has not been assigned yet: hashing an absent key
            // yields zero rather than failing.
            assertThat(new UserSecurity().hashCode()).isZero();
        }

        @Test
        @DisplayName("a record is unequal to null and to an unrelated type, so neither can be mistaken "
                + "for a sign-on identity")
        void aRecordIsUnequalToNullAndToAnUnrelatedType() {
            final UserSecurity user = seededAdministrator();

            assertThat(user.equals(null)).isFalse();
            assertThat(user.equals(ADMIN_IDENTIFIER)).isFalse();
            assertThat(user.equals(new Object())).isFalse();
        }

        @Test
        @DisplayName("two records whose identifier has not been assigned yet are equal, because the key "
                + "is absent rather than generated")
        void twoUnkeyedRecordsAreEqual() {
            final UserSecurity first = new UserSecurity();
            final UserSecurity second = new UserSecurity();

            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
        }
    }

    /**
     * Documents and verifies what the entity does and does not do with the stored credential.
     */
    @Nested
    @DisplayName("the credential contract")
    class CredentialContract {

        @Test
        @DisplayName("the primary key is the 8-byte sign-on identifier from the record image, so no "
                + "generated surrogate identifier exists on this entity")
        void theKeyIsTheBusinessIdentifierWithNoSurrogate() {
            // Proved by compile-time absence. This file names no getId, no setId and no
            // generated-identifier accessor of any kind, and it could not compile if it did, because the
            // entity declares none. Reflection is deliberately not used to demonstrate that: an absent
            // member is established by the code that does not reference it, which is the stronger
            // evidence. It is not withheld to protect a budget - the module's unsafe-code audit scopes
            // its zero reflection count to production sources under src/main/java, and test sources
            // fall outside it. Across the estate a record key is the leading
            // substring of the record image, and a surrogate key would break the record-image-to-row
            // correspondence that byte-parity verification depends on.
            final UserSecurity user = seededAdministrator();

            assertThat(user.getSecUsrId()).isEqualTo(ADMIN_IDENTIFIER);
            assertThat(encodedWidth(user.getSecUsrId())).isEqualTo(KEY_WIDTH);

            // A caller-assigned key is absent until it is assigned; a generated one never would be.
            assertThat(new UserSecurity().getSecUsrId()).isNull();
        }

        @Test
        @DisplayName("the entity stores the already-hashed credential and neither produces nor checks "
                + "it, so no hashing, verification or comparison happens on this record")
        void theEntityNeitherHashesNorVerifiesNorCompares() {
            // The entity exposes no encoder, no digest type, no cryptography package and no matches,
            // verify or check-credential member, so what is asserted here is the positive consequence:
            // the stored value passes through untouched. Reflection is deliberately not used.
            final UserSecurity user = seededAdministrator();

            // The value read back is the very reference that was handed in: nothing was hashed,
            // re-derived, salted, copied or otherwise transformed on the way through.
            assertThat(SensitiveValues.fingerprint(user.credentialDigest()))
                    .isEqualTo(SensitiveValues.fingerprint(SYNTHETIC_STORED_CREDENTIAL));
            // Reference identity asserted as a boolean, because isSameAs prints both operands and both
            // operands are stored credential material.
            assertThat(user.credentialDigest() == SYNTHETIC_STORED_CREDENTIAL)
                    .as("the very reference handed in is the one read back: nothing was hashed, "
                            + "re-derived, salted or copied on the way through")
                    .isTrue();

            // Replacement stores the new value verbatim as well; there is no accumulation and no
            // re-hashing of what was already there.
            user.replaceCredentialDigest(OTHER_SYNTHETIC_STORED_CREDENTIAL);
            assertThat(user.credentialDigest() == OTHER_SYNTHETIC_STORED_CREDENTIAL)
                    .as("replacement stores the new reference verbatim as well")
                    .isTrue();
        }

        @Test
        @DisplayName("both credential write paths refuse a value that is not shaped like a stored hash, "
                + "which is what stops an eight-character cleartext value reaching a 60-character column")
        void bothCredentialWritePathsRefuseAnUnhashedValue() {
            // A divergence from the entity contract as originally described, and the reason the fixtures
            // in this file are hash-shaped: the credential paths are not plain assignment. The check is
            // purely structural - it does not hash and does not verify - but it does refuse, so a value
            // of the legacy eight-character width cannot be stored in a column wide enough to hold it.
            // The value used here is a label rather than a credential.
            assertThat(encodedWidth(NOT_A_STORED_CREDENTIAL)).isNotEqualTo(CREDENTIAL_COLUMN_WIDTH);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new UserSecurity(
                            ADMIN_IDENTIFIER,
                            blankFill(ADMIN_GIVEN_NAME, GIVEN_NAME_WIDTH),
                            blankFill(ADMIN_FAMILY_NAME, FAMILY_NAME_WIDTH),
                            NOT_A_STORED_CREDENTIAL,
                            ROLE_ADMINISTRATOR))
                    // A refusal must not echo what it refused, since a refused value is likely the very
                    // thing that should not have been supplied, and a message is prone to reach a log.
                    .withMessageNotContaining(NOT_A_STORED_CREDENTIAL);

            final UserSecurity user = seededAdministrator();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> user.replaceCredentialDigest(NOT_A_STORED_CREDENTIAL));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> user.replaceCredentialDigest(null));

            assertThat(SensitiveValues.fingerprint(user.credentialDigest()))
                    .as("a refused replacement leaves the stored value untouched")
                    .isEqualTo(SensitiveValues.fingerprint(SYNTHETIC_STORED_CREDENTIAL));
        }

        @Test
        @DisplayName("the diagnostic rendering does not carry the stored credential, so it cannot reach "
                + "a log line by way of a description")
        void theDiagnosticRenderingExcludesTheStoredCredential() {
            // A rendering exists on the entity, so the one permitted assertion about it is made here and
            // nothing further: that the stored credential does not appear in it.
            final UserSecurity user = seededAdministrator();

            assertThat(user.toString()).doesNotContain(SYNTHETIC_STORED_CREDENTIAL);
        }
    }
}
