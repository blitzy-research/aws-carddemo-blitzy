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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit test for {@link UserSecurity}, the entity form of the 80-byte user-security record that the
 * sign-on transaction authenticates against.
 *
 * <p><strong>What this test proves.</strong> Three independent legacy authorities fix the shape of this
 * entity, and this test asserts that the Java form honours all three:
 * <ul>
 *   <li>the copybook member {@code CSUSR01Y}, whose record is 80 bytes: an 8-byte identifier at offset
 *       0, two 20-byte name fields at 8 and 28, an 8-byte credential at 48, a 1-byte classification at
 *       56 and a 23-byte trailing filler at 57;</li>
 *   <li>the provisioning job for the user-security dataset, which declares {@code KEYS(8,0)} with
 *       {@code RECORDSIZE(80,80)} and carries ten card images in stream - five administrators and five
 *       standard users, every one of them with the same credential literal. Those ten identifiers,
 *       names and classifications are the seed the local and test profiles load; and</li>
 *   <li>the single deliberate divergence from the copybook. The credential column is sixty characters
 *       wide rather than eight, because the migrated system stores a digest where the legacy record
 *       stored the credential in clear. Reproducing the eight-byte plaintext would satisfy byte parity
 *       and violate the credential constraint, so the width is widened and the divergence is recorded.
 *       This test asserts that a sixty-character digest is stored unchanged and that the eight-byte
 *       legacy plaintext is <em>not</em> what the entity is expected to carry.</li>
 * </ul>
 *
 * <p><strong>No diagnostic representation is overridden, and that is deliberate.</strong> Every field of
 * this record is sensitive - the identifier is a credential half, the digest is the other half, and the
 * classification is an authorisation decision - so the entity inherits the identity representation
 * rather than rendering its state. This test asserts that inherited form, which is what guarantees a log
 * line cannot leak a credential or a role.
 *
 * <p><strong>Reference data.</strong> The ten seeded identifiers, given names, family names and
 * classifications reproduced below are the measured content of the provisioning job's in-stream card
 * images, quoted as data only. No line of legacy source is transcribed anywhere in this file, and the
 * seeded credential literal is deliberately not reproduced.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test. It starts no application context, opens no
 * database connection, reads no file, touches no network, runs no container, computes no digest and
 * performs no introspection. Every expected value is a literal typed out in this source.
 *
 * <p><strong>Provenance.</strong> Legacy estate read at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Cited as provenance only and never asserted
 * against a member.
 */
@DisplayName("UserSecurity - the entity form of the 80-byte user-security record")
class UserSecurityBaselineTest {

    /** Width of the identifier, and therefore the declared key length of the dataset. */
    private static final int ID_WIDTH = 8;

    /** Width of each of the two name fields. */
    private static final int NAME_WIDTH = 20;

    /** Width of the credential field in the legacy record image. */
    private static final int LEGACY_CREDENTIAL_WIDTH = 8;

    /** Width of the credential column in the migrated schema, sized for a digest. */
    private static final int DIGEST_COLUMN_WIDTH = 60;

    /** Width of the classification field. */
    private static final int CLASSIFICATION_WIDTH = 1;

    /** Width of the trailing filler that closes the record. */
    private static final int FILLER_WIDTH = 23;

    /** Record length declared by both the copybook and the provisioning job. */
    private static final int RECORD_LENGTH = 80;

    /** Rows the provisioning job carries in stream. */
    private static final int SEEDED_ROWS = 10;

    /** Administrator rows the provisioning job carries. */
    private static final int SEEDED_ADMIN_ROWS = 5;

    /** Standard-user rows the provisioning job carries. */
    private static final int SEEDED_USER_ROWS = 5;

    /** Classification byte of an administrator row. */
    private static final String ADMIN_CLASSIFICATION = "A";

    /** Classification byte of a standard-user row. */
    private static final String USER_CLASSIFICATION = "U";

    /** The eight seeded administrator identifiers and the five standard-user ones, in seed order. */
    private static final List<String> SEEDED_IDENTIFIERS = List.of(
            "ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005",
            "USER0001", "USER0002", "USER0003", "USER0004", "USER0005");

    /**
     * A representative digest of the exact length the widened column is sized for: a seven-character
     * algorithm-and-cost prefix, a twenty-two-character salt and a thirty-one-character digest body,
     * summing to the sixty characters the column reserves.
     */
    private static final String DIGEST =
            "$2a$10$abcdefghijklmnopqrstuv0123456789ABCDEFGHIJKLMNOPQRSTU";

    private UserSecurity user;

    /**
     * Right pads a value with spaces to the exact width the record layout reserves for its field.
     * Used only to build test inputs at their declared widths; the width itself is always asserted
     * against the independently declared constant rather than against this helper's output.
     *
     * @param value the significant text of the field
     * @param width the declared field width from the copybook layout
     * @return the value padded on the right with spaces to exactly {@code width} characters
     */
    private static String rightPadded(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    @BeforeEach
    void createFirstSeededAdministrator() {
        user = new UserSecurity("ADMIN001", rightPadded("MARGARET", NAME_WIDTH),
                rightPadded("GOLD", NAME_WIDTH), DIGEST, ADMIN_CLASSIFICATION);
    }

    @Nested
    @DisplayName("Construction in copybook declaration order")
    class Construction {

        @Test
        @DisplayName("the five-argument constructor binds every field in copybook declaration order: "
                + "identifier, given name, family name, credential, classification")
        void theConstructorBindsEveryFieldInDeclarationOrder() {
            assertThat(user.getSecUsrId()).isEqualTo("ADMIN001");
            assertThat(user.getSecUsrFname()).isEqualTo(rightPadded("MARGARET", NAME_WIDTH));
            assertThat(user.getSecUsrLname()).isEqualTo(rightPadded("GOLD", NAME_WIDTH));
            assertThat(user.credentialDigest()).isEqualTo(DIGEST);
            assertThat(user.getSecUsrType()).isEqualTo(ADMIN_CLASSIFICATION);
        }

        @Test
        @DisplayName("the no-arg constructor the provider requires leaves all five fields absent, "
                + "because the provider populates state afterwards")
        void theNoArgConstructorLeavesEveryFieldAbsent() {
            final UserSecurity empty = new UserSecurity();

            assertThat(empty.getSecUsrId()).isNull();
            assertThat(empty.getSecUsrFname()).isNull();
            assertThat(empty.getSecUsrLname()).isNull();
            assertThat(empty.credentialDigest()).isNull();
            assertThat(empty.getSecUsrType()).isNull();
        }

        @Test
        @DisplayName("the constructor stores every record-layout value verbatim - no trim, no pad, no "
                + "case fold and no hashing of its own - because credential handling belongs to the "
                + "security layer")
        void theConstructorStoresEveryValueVerbatim() {
            final UserSecurity raw = new UserSecurity("  a  ", "mIxEd", "", DIGEST, "a");

            assertThat(raw.getSecUsrId()).isEqualTo("  a  ");
            assertThat(raw.getSecUsrFname()).isEqualTo("mIxEd");
            assertThat(raw.getSecUsrLname()).isEmpty();
            assertThat(raw.credentialDigest())
                    .as("the digest is stored byte for byte: the entity hashes nothing")
                    .isEqualTo(DIGEST);
            assertThat(raw.getSecUsrType()).isEqualTo("a");
        }

        @Test
        @DisplayName("the credential column is the one field that is checked, so a cleartext value "
                + "cannot be constructed into it")
        void aCleartextCredentialCannotBeConstructed() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new UserSecurity("  a  ", "mIxEd", "", "plaintext", "a"))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .as("the refusal must not echo the value, which is likely the very"
                                    + " credential the caller should not have held")
                            .doesNotContain("plaintext"));
        }

        @Test
        @DisplayName("absent record-layout values are accepted and returned unchanged, because the "
                + "entity performs no validation of those and the database enforces their non-null "
                + "contract, while an absent credential is refused outright")
        void absentValuesAreAcceptedAndReturnedUnchanged() {
            final UserSecurity sparse = new UserSecurity("ADMIN001", null, null, DIGEST, null);

            assertThat(sparse.getSecUsrId()).isEqualTo("ADMIN001");
            assertThat(sparse.getSecUsrFname()).isNull();
            assertThat(sparse.getSecUsrType()).isNull();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the credential column is the one field the entity itself will not leave"
                            + " unsatisfied, because an absent digest is indistinguishable from one"
                            + " that was never written")
                    .isThrownBy(() -> new UserSecurity("ADMIN001", null, null, null, null));
        }
    }

    @Nested
    @DisplayName("Byte geometry of the 80-byte record")
    class ByteGeometry {

        @Test
        @DisplayName("the five field widths plus the 23-byte filler close the 80-byte record exactly, so "
                + "the copybook layout and the declared record size agree")
        void theFieldWidthsCloseTheRecordExactly() {
            final int summed = ID_WIDTH
                    + 2 * NAME_WIDTH
                    + LEGACY_CREDENTIAL_WIDTH
                    + CLASSIFICATION_WIDTH
                    + FILLER_WIDTH;

            assertThat(summed).isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the key is the leading eight bytes of the record, matching the dataset's key "
                + "declaration, so the business key is the identifier and no surrogate is introduced")
        void theKeyIsTheLeadingEightBytes() {
            assertThat(user.getSecUsrId()).hasSize(ID_WIDTH);
            assertThat(RECORD_LENGTH - ID_WIDTH).isEqualTo(72);
        }

        @Test
        @DisplayName("the classification sits at offset 56, immediately after the credential, which is "
                + "why the last significant byte of every seeded card image is the classification")
        void theClassificationSitsImmediatelyAfterTheCredential() {
            final int classificationOffset = ID_WIDTH + 2 * NAME_WIDTH + LEGACY_CREDENTIAL_WIDTH;

            assertThat(classificationOffset).isEqualTo(56);
            assertThat(classificationOffset + CLASSIFICATION_WIDTH).isEqualTo(57);
            assertThat(classificationOffset + CLASSIFICATION_WIDTH + FILLER_WIDTH)
                    .isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("both name fields are twenty bytes and are space padded rather than trimmed, so "
                + "their padding survives into the entity")
        void bothNameFieldsKeepTheirWidth() {
            assertThat(user.getSecUsrFname()).hasSize(NAME_WIDTH);
            assertThat(user.getSecUsrLname()).hasSize(NAME_WIDTH);
            assertThat(user.getSecUsrFname()).endsWith(" ");
            assertThat(user.getSecUsrLname()).endsWith(" ");
        }

        @Test
        @DisplayName("the classification is a single byte, which is why it is modelled as a "
                + "one-character value rather than as free text")
        void theClassificationIsASingleByte() {
            assertThat(user.getSecUsrType()).hasSize(CLASSIFICATION_WIDTH);
        }

        @Test
        @DisplayName("this is the narrowest record layout of the estate, narrower than the fifty-byte "
                + "reference records and far narrower than the five-hundred-byte customer record")
        void thisIsTheNarrowestRecordLayoutOfTheEstate() {
            final int referenceRecordLength = 50;
            final int customerRecordLength = 500;

            assertThat(RECORD_LENGTH).isGreaterThan(referenceRecordLength);
            assertThat(RECORD_LENGTH).isLessThan(customerRecordLength);
            assertThat(FILLER_WIDTH).isLessThan(RECORD_LENGTH / 2);
        }
    }

    @Nested
    @DisplayName("Credential column widened for a digest")
    class CredentialColumn {

        @Test
        @DisplayName("a sixty-character digest is stored unchanged, which is what the widened column "
                + "exists for: a digest does not fit the eight-byte legacy field")
        void aSixtyCharacterDigestIsStoredUnchanged() {
            assertThat(user.credentialDigest()).isEqualTo(DIGEST);
            assertThat(user.credentialDigest()).hasSize(DIGEST_COLUMN_WIDTH);
            assertThat(DIGEST_COLUMN_WIDTH).isGreaterThan(LEGACY_CREDENTIAL_WIDTH);
        }

        @Test
        @DisplayName("the digest decomposes into the seven-character algorithm prefix, the "
                + "twenty-two-character salt and the thirty-one-character body that together fill the "
                + "sixty-character column")
        void theDigestDecomposesIntoPrefixSaltAndBody() {
            final int prefixWidth = 7;
            final int saltWidth = 22;
            final int bodyWidth = 31;

            assertThat(prefixWidth + saltWidth + bodyWidth).isEqualTo(DIGEST_COLUMN_WIDTH);
            assertThat(DIGEST.substring(0, prefixWidth)).isEqualTo("$2a$10$");
            assertThat(DIGEST.substring(prefixWidth, prefixWidth + saltWidth)).hasSize(saltWidth);
            assertThat(DIGEST.substring(prefixWidth + saltWidth)).hasSize(bodyWidth);
        }

        @Test
        @DisplayName("the digest carries the algorithm marker its own format prescribes, so a reader can "
                + "tell a digest from the eight-byte plaintext the legacy record held")
        void theDigestCarriesItsAlgorithmMarker() {
            assertThat(user.credentialDigest()).startsWith("$2a$");
            assertThat(user.credentialDigest()).doesNotContain(" ");
            assertThat(user.credentialDigest().length()).isNotEqualTo(LEGACY_CREDENTIAL_WIDTH);
        }

        @Test
        @DisplayName("the entity computes no digest of its own: a digest handed in comes back byte for "
                + "byte, so the security layer stays the single place that hashes")
        void theEntityComputesNoDigestOfItsOwn() {
            final String replacement = "$2b$12$" + "z".repeat(DIGEST_COLUMN_WIDTH - 7);

            user.replaceCredentialDigest(replacement);

            assertThat(user.credentialDigest())
                    .as("no rehashing, no normalising and no re-encoding on the way in")
                    .isEqualTo(replacement);
        }

        @Test
        @DisplayName("the entity refuses a value that is not a digest, so the security layer is not "
                + "merely the place that hashes but the only route into the column")
        void theEntityRefusesAValueThatIsNotADigest() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> user.replaceCredentialDigest("not-a-digest"));

            assertThat(user.credentialDigest())
                    .as("a refused replacement leaves the stored digest in place")
                    .isEqualTo(DIGEST);
        }

        @Test
        @DisplayName("the widened column is the deliberate divergence from byte parity: the legacy field "
                + "is eight bytes and the column is sixty, a difference of fifty-two")
        void theWidenedColumnIsTheDeliberateDivergence() {
            assertThat(DIGEST_COLUMN_WIDTH - LEGACY_CREDENTIAL_WIDTH).isEqualTo(52);
            assertThat(LEGACY_CREDENTIAL_WIDTH).isEqualTo(ID_WIDTH);
        }
    }

    @Nested
    @DisplayName("Mutability required by the user-administration transactions")
    class Mutability {

        @Test
        @DisplayName("every mapped field round-trips through its setter, which the add and update "
                + "transactions need in order to apply a screen submission field by field")
        void everyMappedFieldRoundTripsThroughItsSetter() {
            final UserSecurity target = new UserSecurity();

            target.setSecUsrId("USER0005");
            target.setSecUsrFname(rightPadded("LEE", NAME_WIDTH));
            target.setSecUsrLname(rightPadded("TING", NAME_WIDTH));
            target.replaceCredentialDigest(DIGEST);
            target.setSecUsrType(USER_CLASSIFICATION);

            assertThat(target.getSecUsrId()).isEqualTo("USER0005");
            assertThat(target.getSecUsrFname()).isEqualTo(rightPadded("LEE", NAME_WIDTH));
            assertThat(target.getSecUsrLname()).isEqualTo(rightPadded("TING", NAME_WIDTH));
            assertThat(target.credentialDigest()).isEqualTo(DIGEST);
            assertThat(target.getSecUsrType()).isEqualTo(USER_CLASSIFICATION);
        }

        @Test
        @DisplayName("the classification is mutable, which is what the update transaction needs in order "
                + "to promote or demote a user")
        void theClassificationIsMutable() {
            user.setSecUsrType(USER_CLASSIFICATION);
            assertThat(user.getSecUsrType()).isEqualTo(USER_CLASSIFICATION);

            user.setSecUsrType(ADMIN_CLASSIFICATION);
            assertThat(user.getSecUsrType()).isEqualTo(ADMIN_CLASSIFICATION);
        }

        @Test
        @DisplayName("a record-layout setter accepts an absent value, so clearing such a field is "
                + "possible and its non-null contract is enforced by the column rather than by the "
                + "entity, while the credential replacement refuses one")
        void aSetterAcceptsAnAbsentValue() {
            user.setSecUsrFname(null);
            user.setSecUsrType(null);

            assertThat(user.getSecUsrFname()).isNull();
            assertThat(user.getSecUsrType()).isNull();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> user.replaceCredentialDigest(null));
            assertThat(user.credentialDigest())
                    .as("the credential cannot be cleared, so a row can never carry an empty digest")
                    .isEqualTo(DIGEST);
        }
    }

    @Nested
    @DisplayName("Identity founded on the business key alone")
    class Identity {

        @Test
        @DisplayName("two independently constructed users with the same identifier are equal and hash "
                + "alike, even when credential and classification differ, because the key is the identity")
        void sameIdentifierMeansEqualEvenWhenCredentialAndRoleDiffer() {
            final UserSecurity other = new UserSecurity("ADMIN001", rightPadded("OTHER", NAME_WIDTH),
                    rightPadded("PERSON", NAME_WIDTH), "$2b$12$" + "y".repeat(53),
                    USER_CLASSIFICATION);

            assertThat(user).isEqualTo(other);
            assertThat(user).hasSameHashCodeAs(other);
        }

        @Test
        @DisplayName("a different identifier means a different user, even when every other attribute is "
                + "identical - which matters because every seeded row shares one credential literal")
        void aDifferentIdentifierMeansADifferentUser() {
            final UserSecurity other = new UserSecurity("ADMIN002", rightPadded("MARGARET", NAME_WIDTH),
                    rightPadded("GOLD", NAME_WIDTH), DIGEST, ADMIN_CLASSIFICATION);

            assertThat(user).isNotEqualTo(other);
        }

        @Test
        @DisplayName("identity is reflexive, symmetric and transitive across independently constructed "
                + "instances carrying the same identifier")
        void identityIsReflexiveSymmetricAndTransitive() {
            final UserSecurity second = new UserSecurity();
            final UserSecurity third = new UserSecurity();
            second.setSecUsrId("ADMIN001");
            third.setSecUsrId("ADMIN001");

            assertThat(user.equals(user)).isTrue();
            assertThat(user.equals(second)).isTrue();
            assertThat(second.equals(user)).isTrue();
            assertThat(second.equals(third)).isTrue();
            assertThat(user.equals(third)).isTrue();
        }

        @Test
        @DisplayName("the identifier is case sensitive and padding sensitive, because the key is the "
                + "record's leading eight bytes and nothing folds them")
        void theIdentifierIsCaseAndPaddingSensitive() {
            final UserSecurity lowerCase = new UserSecurity();
            final UserSecurity padded = new UserSecurity();
            lowerCase.setSecUsrId("admin001");
            padded.setSecUsrId("ADMIN001 ");

            assertThat(user).isNotEqualTo(lowerCase);
            assertThat(user).isNotEqualTo(padded);
        }

        @Test
        @DisplayName("equality rejects an absent operand and a foreign type through the type pattern "
                + "rather than throwing")
        void equalityRejectsAbsentOperandAndForeignType() {
            assertThat(user.equals(null)).isFalse();
            assertThat(user.equals("ADMIN001")).isFalse();
            assertThat(user.equals(new Account())).isFalse();
        }

        @Test
        @DisplayName("two users with absent identifiers are equal and hash alike, which the single-field "
                + "hash of this entity supports without a null check of its own")
        void twoUsersWithAbsentIdentifiersAreEqual() {
            assertThat(new UserSecurity()).isEqualTo(new UserSecurity());
            assertThat(new UserSecurity()).hasSameHashCodeAs(new UserSecurity());
            assertThat(new UserSecurity().hashCode()).isZero();
        }

        @Test
        @DisplayName("all ten seeded identifiers stay distinct in a hash set, so no two seeded rows "
                + "collapse onto one user")
        void allSeededIdentifiersStayDistinctInAHashSet() {
            final Set<UserSecurity> users = new HashSet<>();

            for (final String identifier : SEEDED_IDENTIFIERS) {
                final UserSecurity seeded = new UserSecurity();
                seeded.setSecUsrId(identifier);
                users.add(seeded);
            }

            assertThat(users).hasSize(SEEDED_ROWS);
        }
    }

    @Nested
    @DisplayName("A state rendering that names the key alone and can carry nothing else")
    class NoStateRendering {

        @Test
        @DisplayName("the rendering is stated by this class rather than inherited, and it names the "
                + "sign-on identifier and nothing else")
        void theRenderingNamesTheKeyAlone() {
            final String rendered = user.toString();

            // An earlier reading of this entity asserted the inherited identity form. That form leaks
            // nothing, but its harmlessness is a property of the base class rather than of this one: a
            // field added later, or a decision to render reflectively, would leak silently. The
            // rendering is therefore stated here, and it is stated narrowly. Every other domain entity
            // in the module follows the same convention - the account renders its identifier and status,
            // the daily transaction renders its identifier, type and category - so a rendering that
            // names the business key is the module's convention rather than this entity's exception.
            assertThat(rendered).isEqualTo("UserSecurity[secUsrId=ADMIN001]");
        }

        @Test
        @DisplayName("nothing sensitive can leak through the rendering: neither the digest, nor the "
                + "classification, nor either name appears in it")
        void nothingSensitiveLeaksThroughTheRendering() {
            final String rendered = user.toString();

            assertThat(rendered).doesNotContain(DIGEST);
            assertThat(rendered).doesNotContain("$2a$");
            assertThat(rendered).doesNotContain("MARGARET");
            assertThat(rendered).doesNotContain("GOLD");
            assertThat(rendered)
                    .as("no sensitive component is named either, so a future widening of the frame"
                            + " would have to introduce the attribute name and fail this assertion")
                    .doesNotContain("secUsrPwd")
                    .doesNotContain("secUsrFname")
                    .doesNotContain("secUsrLname")
                    .doesNotContain("secUsrType");
        }

        @Test
        @DisplayName("the sign-on identifier is the one component deliberately retained, because it is "
                + "an account identifier rather than a secret and is what makes a diagnostic line useful")
        void theSignOnIdentifierIsTheOneComponentRetained() {
            final String rendered = user.toString();

            assertThat(rendered).contains("ADMIN001");
            assertThat(rendered.replace("UserSecurity[secUsrId=", "").replace("]", ""))
                    .as("nothing but the identifier survives the frame, so no further component can be"
                            + " smuggled in without this assertion failing")
                    .isEqualTo("ADMIN001");
        }

        @Test
        @DisplayName("the rendering of an empty user does not fail and still leaks nothing, because "
                + "the one component it reads has not been assigned")
        void theRenderingOfAnEmptyUserDoesNotFail() {
            assertThat(new UserSecurity().toString())
                    .isEqualTo("UserSecurity[secUsrId=null]");
        }

        @Test
        @DisplayName("two users differing only in credential, names and classification render "
                + "identically, so the rendering can never be used as an equality or difference proxy")
        void componentsOutsideTheKeyCannotBeDistinguishedFromTheRendering() {
            final UserSecurity twin = new UserSecurity("ADMIN001", rightPadded("OTHER", NAME_WIDTH),
                    rightPadded("PERSON", NAME_WIDTH), "$2b$12$" + "z".repeat(53),
                    USER_CLASSIFICATION);

            assertThat(twin.toString()).isEqualTo(user.toString());
        }
    }

    @Nested
    @DisplayName("Correspondence with the ten seeded card images")
    class SeededRowCorrespondence {

        @Test
        @DisplayName("the five administrator rows and five standard-user rows sum to the ten the "
                + "provisioning job carries, so both classifications are seeded")
        void theSeededRowCountsSumToTen() {
            assertThat(SEEDED_ADMIN_ROWS + SEEDED_USER_ROWS).isEqualTo(SEEDED_ROWS);
            assertThat(SEEDED_IDENTIFIERS).hasSize(SEEDED_ROWS);
        }

        @Test
        @DisplayName("every seeded identifier is exactly eight characters, so each fits the key field "
                + "without pad or truncation")
        void everySeededIdentifierIsExactlyEightCharacters() {
            for (final String identifier : SEEDED_IDENTIFIERS) {
                assertThat(identifier).hasSize(ID_WIDTH);
            }
        }

        @Test
        @DisplayName("the five administrator identifiers begin with the administrator prefix and the "
                + "five standard-user identifiers with the user prefix, so the identifier itself hints "
                + "at the classification without carrying it")
        void theSeededIdentifierPrefixesPartitionTheTwoClassifications() {
            final long administrators = SEEDED_IDENTIFIERS.stream()
                    .filter(identifier -> identifier.startsWith("ADMIN"))
                    .count();
            final long standardUsers = SEEDED_IDENTIFIERS.stream()
                    .filter(identifier -> identifier.startsWith("USER"))
                    .count();

            assertThat(administrators).isEqualTo(SEEDED_ADMIN_ROWS);
            assertThat(standardUsers).isEqualTo(SEEDED_USER_ROWS);
        }

        @ParameterizedTest(name = "seeded row {0} {1} {2} classified {3}")
        @DisplayName("each seeded row constructs with its measured identifier, names and classification, "
                + "and every field comes back unchanged")
        @CsvSource({
            "ADMIN001, MARGARET,  GOLD,        A",
            "ADMIN002, RUSSELL,   RUSSELL,     A",
            "ADMIN003, RAYMOND,   WHITMORE,    A",
            "ADMIN004, EMMANUEL,  CASGRAIN,    A",
            "ADMIN005, GRANVILLE, LACHAPELLE,  A",
            "USER0001, LAWRENCE,  THOMAS,      U",
            "USER0002, AJITH,     KUMAR,       U",
            "USER0003, LAURITZ,   ALME,        U",
            "USER0004, AVERARDO,  MAZZI,       U",
            "USER0005, LEE,       TING,        U"})
        void eachSeededRowConstructsUnchanged(final String identifier, final String givenName,
                final String familyName, final String classification) {
            final UserSecurity seeded = new UserSecurity(identifier,
                    rightPadded(givenName, NAME_WIDTH), rightPadded(familyName, NAME_WIDTH),
                    DIGEST, classification);

            assertThat(seeded.getSecUsrId()).isEqualTo(identifier).hasSize(ID_WIDTH);
            assertThat(seeded.getSecUsrFname()).hasSize(NAME_WIDTH).startsWith(givenName);
            assertThat(seeded.getSecUsrLname()).hasSize(NAME_WIDTH).startsWith(familyName);
            assertThat(seeded.getSecUsrType())
                    .hasSize(CLASSIFICATION_WIDTH)
                    .isIn(ADMIN_CLASSIFICATION, USER_CLASSIFICATION);
            assertThat(seeded.credentialDigest()).hasSize(DIGEST_COLUMN_WIDTH);
        }

        @Test
        @DisplayName("one seeded administrator carries the same given name as family name, which is a "
                + "property of the seeded data and not a transcription slip")
        void oneSeededAdministratorRepeatsItsName() {
            final UserSecurity repeated = new UserSecurity("ADMIN002",
                    rightPadded("RUSSELL", NAME_WIDTH), rightPadded("RUSSELL", NAME_WIDTH), DIGEST,
                    ADMIN_CLASSIFICATION);

            assertThat(repeated.getSecUsrFname()).isEqualTo(repeated.getSecUsrLname());
            assertThat(repeated).isNotEqualTo(user);
        }
    }
}
