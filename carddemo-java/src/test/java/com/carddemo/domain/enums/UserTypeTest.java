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
package com.carddemo.domain.enums;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link UserType}, the typed replacement for the CardDemo sign-on user-type
 * vocabulary.
 *
 * <p><strong>What the legacy authority is.</strong> Two authorities describe the same single byte
 * and this class holds the type to both. The declared vocabulary is the pair of level-88 condition
 * names attached to the one-character communication-area field {@code CDEMO-USER-TYPE}
 * ({@code app/cpy/COCOM01Y.cpy}): {@code CDEMO-USRTYP-ADMIN} valued {@code A} and
 * {@code CDEMO-USRTYP-USER} valued {@code U}. No third condition name is declared anywhere.
 * The persisted origin is the one-character {@code SEC-USR-TYPE} ({@code app/cpy/CSUSR01Y.cpy}),
 * the fifth of six fields in the eighty-byte {@code SEC-USER-DATA} record: an eight-byte sign-on
 * identifier, a twenty-byte given name, a twenty-byte family name, the eight-byte
 * {@code SEC-USR-PWD} field, this one-byte type code, and a twenty-three byte
 * {@code SEC-USR-FILLER}. Unlike the anonymous trailing filler of the account and card records,
 * that filler is named. The record geometry is declared a second time and independently by the
 * provisioning job {@code app/jcl/DUSRSECJ.jcl}, whose sequential dataset carries a fixed
 * eighty-byte record and whose cluster definition states an eight-byte key at offset zero over
 * that same eighty-byte record.
 *
 * <p><strong>The load-bearing fact: routing tolerance, proved by an unconditional alternative.</strong>
 * Sign-on reads the user record, moves the persisted type byte straight into the communication-area
 * field ({@code app/cbl/COSGN00C.cbl} L227) and then tests the administrator condition name at L230,
 * transferring control to the administrative menu program when it holds. The alternative at L235 is
 * <strong>unconditional</strong>. It is not a second test of the standard-user condition name, and
 * the dispatch closes at L240 with no third branch and no error branch: the enclosing evaluation
 * reserves its remaining cases for a record that was not found (L247) and for a read that could not
 * be verified (L252), neither of which is an unrecognised type byte.
 *
 * <p>Three consequences follow, and every assertion in the lookup and predicate clusters below is a
 * direct executable form of one of them. Only the exact code {@code A} is an administrator, so no
 * other byte can reach the administrative menu. Any other byte, including an unrecognised one, a
 * lower-case {@code a}, a blank or a digit, reaches the main menu instead. And an unrecognised byte
 * is therefore not an error condition at all, which is why {@link UserType#fromCode(String)} answers
 * {@link Optional#empty()} rather than throwing, and why an absent result must answer {@code false}
 * to the administrator question rather than failing the caller. A throwing lookup would be the more
 * conventional Java shape and it would abort a sign-on that the legacy program completes. Faithful
 * beats idiomatic.
 *
 * <p><strong>Why an out-of-vocabulary byte has to survive the round trip.</strong> The user-security
 * entity keeps this column as a raw one-character string carrying no check constraint, and the
 * enumeration is deliberately not a persistence attribute type, so a byte outside the two declared
 * codes <em>persists successfully</em> at the database layer and arrives in the Java layer intact.
 * Absorbing it is the contract; rejecting it would be a new behaviour. This class therefore asserts
 * nothing whatever about persistence mapping - no annotation, no column name, no length and no
 * nullability - because the type under test carries none of that and the correspondence between
 * entity and schema is asserted by {@code EntityPersistenceMappingTest}, which compares the mapping
 * the persistence provider computes against the shipped migration {@code V1__create_schema.sql}.
 *
 * <p><strong>Deliberately absent.</strong> The credential field of the same record is referred to
 * here only by its name {@code SEC-USR-PWD} and its declared eight-byte width, which is all the
 * offset arithmetic needs; no credential value appears anywhere in this file, and the plaintext
 * comparison the legacy program performs is neither reproduced nor tested. The migration replaces
 * that comparison with a hashed credential, a deliberate parity exception recorded in
 * {@code docs/decision-log.md} rather than here. Screen-flow state is out of scope too: the
 * program-context condition names that sit beside the user type in the same copybook belong to the
 * navigation-context transfer object, and the dispatch targets, the sign-on message texts and the
 * session-token concerns all belong to their own layers. This file tests a value type.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test. It starts no application context, opens no
 * database, queue or socket, reads no file, consults no clock or random source, and uses no mocking
 * framework, because the type under test is a value type with no collaborator.
 *
 * <p>Provenance of the legacy authorities cited above: checkout commit SHA
 * 7756d895ffeb65f7ea72aaa609e356d9899afcec, upstream release stamp CardDemo_v1.0-15-g27d6c6f-68
 * dated 2022-07-19. Recorded for traceability only; nothing below asserts it.
 *
 * @see UserType
 */
@DisplayName("UserType :: the one-byte user-type vocabulary behind the sign-on menu split")
class UserTypeTest {

    /*
     * THE INDEPENDENT ORACLE.
     *
     * Every expected value in this class is a literal hand-derived from the legacy artefacts named
     * in the class documentation. Nothing here asks the type under test to supply its own expected
     * value, nothing snapshots its output, and no assertion feeds one production call into another.
     * Where a production call appears inside an assertion it is the subject under test and the
     * expected side is always a literal declared below.
     *
     * The two places where faithful translation and idiomatic Java diverge are asserted rather than
     * assumed, and both are recorded in docs/decision-log.md rather than settled by taste here: the
     * constant set stops at two with no synthetic default for an unrecognised byte, and an
     * unrecognised byte yields an empty result instead of the exception an idiomatic lookup would
     * raise.
     *
     * No user-specified rules govern this engagement - the project rules document records a
     * verified absence rather than a partial read - so this file is held to enterprise-standard
     * best practice instead: an independent oracle, a stated provenance, zero-warning
     * compilation, no credential material of any kind, and faithful-over-idiomatic as the
     * tie-break.
     *
     * The width assertions encode at an explicit US-ASCII boundary and pin the resulting code
     * point, because a one-character string is inherently one unit wide and measuring its character
     * count alone says nothing about the byte written to the record.
     */

    /**
     * Raw code of the administrator type.
     *
     * <p>The value of the level-88 condition name {@code CDEMO-USRTYP-ADMIN}, and the only code that
     * reaches the administrative menu program.</p>
     */
    private static final String ADMINISTRATOR_CODE = "A";

    /**
     * Raw code of the standard-user type.
     *
     * <p>The value of the level-88 condition name {@code CDEMO-USRTYP-USER}. It reaches the main
     * menu program, but so does every byte that is not the administrator code, because the
     * alternative that routes there is unconditional.</p>
     */
    private static final String STANDARD_USER_CODE = "U";

    /**
     * ASCII code point of the administrator code.
     *
     * <p>Taken from the ASCII table, not from the type under test, so the byte written into the
     * record is pinned by an independent authority.</p>
     */
    private static final byte ADMINISTRATOR_CODE_ASCII_BYTE = (byte) 0x41;

    /** ASCII code point of the standard-user code, likewise taken from the ASCII table. */
    private static final byte STANDARD_USER_CODE_ASCII_BYTE = (byte) 0x55;

    /**
     * Number of constants the vocabulary admits.
     *
     * <p>Two, and only two, because the copybook attaches exactly two level-88 condition names to
     * the field and the sign-on dispatch branches exactly two ways.</p>
     */
    private static final int EXPECTED_CONSTANT_COUNT = 2;

    /** Declared width of {@code SEC-USR-TYPE} in bytes. */
    private static final int TYPE_CODE_WIDTH = 1;

    /**
     * The six declared field widths of {@code SEC-USER-DATA}, in declaration order.
     *
     * <p>Eight-byte sign-on identifier; twenty-byte given name; twenty-byte family name; eight-byte
     * {@code SEC-USR-PWD}; one-byte type code; twenty-three byte named filler.</p>
     */
    private static final int[] USER_RECORD_FIELD_WIDTHS = {8, 20, 20, 8, 1, 23};

    /** Fields the user-security copybook declares, counting the named trailing filler. */
    private static final int USER_RECORD_FIELD_COUNT = 6;

    /** Record width, declared by the copybook layout and again by the cluster definition. */
    private static final int DECLARED_RECORD_WIDTH = 80;

    /** Key width: the leading sign-on identifier and nothing else. */
    private static final int SIGN_ON_KEY_WIDTH = 8;

    /**
     * How many fields the copybook declares ahead of the type code.
     *
     * <p>Four, so their summed widths are exactly the bytes preceding it.</p>
     */
    private static final int FIELDS_DECLARED_AHEAD_OF_TYPE_CODE = 4;

    /**
     * Documented one-based byte offset of {@code SEC-USR-TYPE} within the eighty-byte record.
     *
     * <p>The layout cluster below derives this figure by summation rather than trusting it, so the
     * constant is the claim being checked and the summation is the evidence.</p>
     */
    private static final int DOCUMENTED_ONE_BASED_TYPE_CODE_OFFSET = 57;

    /** Users the provisioning job seeds in stream. */
    private static final int SEEDED_USER_COUNT = 10;

    /** Seeded users carrying the administrator code. */
    private static final int SEEDED_ADMINISTRATOR_COUNT = 5;

    /** Seeded users carrying the standard-user code. */
    private static final int SEEDED_STANDARD_USER_COUNT = 5;

    /**
     * The type codes the ten seeded users carry, in the order the provisioning job writes them.
     *
     * <p>Read off the last populated column of each in-stream card image, five administrators
     * followed by five standard users.</p>
     */
    private static final List<String> SEEDED_TYPE_CODES =
            List.of("A", "A", "A", "A", "A", "U", "U", "U", "U", "U");

    /** Sign-on identifiers of the five seeded administrators, in provisioning order. */
    private static final List<String> SEEDED_ADMINISTRATOR_IDS =
            List.of("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005");

    /** Sign-on identifiers of the five seeded standard users, in provisioning order. */
    private static final List<String> SEEDED_STANDARD_USER_IDS =
            List.of("USER0001", "USER0002", "USER0003", "USER0004", "USER0005");

    /** The two declared constants, named in the order the level-88 list names their codes. */
    private static final List<UserType> DECLARED_CONSTANTS_IN_ORDER =
            List.of(UserType.ADMIN, UserType.USER);

    /** The two declared codes as literals, positionally paired with the constants above. */
    private static final List<String> DECLARED_CODES_IN_ORDER = List.of("A", "U");

    /**
     * Spellings a synthetic default constant would plausibly carry.
     *
     * <p>None of them is a constant of this enumeration and none of them is a code the record can
     * hold. They appear here only as rejected lookup inputs, which is how the absence cluster proves
     * absence from the outside and without reflection.</p>
     */
    private static final List<String> SYNTHETIC_DEFAULT_SPELLINGS =
            List.of("UNKNOWN", "NONE", "OTHER", "INVALID", "UNMAPPED", "DEFAULT");

    /**
     * The two-value vocabulary the level-88 condition names declare.
     */
    @Nested
    @DisplayName("Vocabulary declared by the two level-88 condition names")
    class Vocabulary {

        @Test
        @DisplayName("the administrator constant carries the raw code A and the standard-user constant carries U, "
                + "the two values the level-88 condition names declare")
        void bothConstantsCarryTheirDeclaredRawCode() {
            assertThat(UserType.ADMIN.getCode())
                    .as("raw code stored in SEC-USR-TYPE for an administrator")
                    .isEqualTo(ADMINISTRATOR_CODE);
            assertThat(UserType.USER.getCode())
                    .as("raw code stored in SEC-USR-TYPE for a standard user")
                    .isEqualTo(STANDARD_USER_CODE);
        }

        @Test
        @DisplayName("the vocabulary admits exactly two constants, because the copybook attaches exactly two "
                + "level-88 condition names to CDEMO-USER-TYPE and declares no third")
        void theVocabularyAdmitsExactlyTwoConstants() {
            final UserType[] constants = UserType.values();

            assertThat(constants)
                    .as("constants translated from the two level-88 condition names")
                    .hasSize(EXPECTED_CONSTANT_COUNT)
                    .containsExactly(UserType.ADMIN, UserType.USER);
        }

        @Test
        @DisplayName("each raw code is exactly one byte wide at the US-ASCII record boundary and encodes to its "
                + "ASCII code point, matching SEC-USR-TYPE PIC X(01)")
        void eachRawCodeIsExactlyOneByteWideAndEncodesToItsAsciiCodePoint() {
            for (final UserType userType : UserType.values()) {
                assertThat(userType.getCode().getBytes(StandardCharsets.US_ASCII))
                        .as("US-ASCII record encoding of the code carried by %s", userType.name())
                        .hasSize(TYPE_CODE_WIDTH);
            }

            final byte[] administratorEncoded =
                    UserType.ADMIN.getCode().getBytes(StandardCharsets.US_ASCII);
            final byte[] standardUserEncoded =
                    UserType.USER.getCode().getBytes(StandardCharsets.US_ASCII);

            assertThat(administratorEncoded[0])
                    .as("ASCII code point written into the type byte for an administrator")
                    .isEqualTo(ADMINISTRATOR_CODE_ASCII_BYTE);
            assertThat(standardUserEncoded[0])
                    .as("ASCII code point written into the type byte for a standard user")
                    .isEqualTo(STANDARD_USER_CODE_ASCII_BYTE);
        }

        @Test
        @DisplayName("the constants are declared in the order the level-88 list names them, administrator before "
                + "standard user, which is also the order the provisioning job writes its records")
        void theConstantsAreDeclaredInLevel88ListOrder() {
            assertThat(UserType.ADMIN.ordinal())
                    .as("position of the code the level-88 list names first")
                    .isZero();
            assertThat(UserType.USER.ordinal())
                    .as("position of the code the level-88 list names second")
                    .isEqualTo(1);

            assertThat(UserType.ADMIN.name())
                    .as("stable identifier the route table and the documentation refer to")
                    .isEqualTo("ADMIN");
            assertThat(UserType.USER.name())
                    .as("stable identifier the route table and the documentation refer to")
                    .isEqualTo("USER");
        }

        @Test
        @DisplayName("the two declared codes are distinct literals, so a single stored byte can never identify "
                + "both types at once")
        void theTwoDeclaredCodesAreDistinct() {
            assertThat(ADMINISTRATOR_CODE)
                    .as("the two codes the level-88 list declares, compared as literals rather than as "
                            + "two answers from the type under test")
                    .isNotEqualTo(STANDARD_USER_CODE);

            assertThat(UserType.ADMIN.getCode())
                    .as("administrator code must not collide with the standard-user literal")
                    .isNotEqualTo(STANDARD_USER_CODE);
            assertThat(UserType.USER.getCode())
                    .as("standard-user code must not collide with the administrator literal")
                    .isNotEqualTo(ADMINISTRATOR_CODE);
        }
    }

    /**
     * Position of the type code inside the eighty-byte user-security record.
     */
    @Nested
    @DisplayName("Position of the type code inside the 80-byte SEC-USER-DATA record")
    class RecordLayout {

        @Test
        @DisplayName("SEC-USR-TYPE begins at byte 57 of the 80-byte SEC-USER-DATA record, immediately after the "
                + "four fields declared ahead of it")
        void theTypeCodeBeginsAtByteFiftySevenOfTheEightyByteRecord() {
            // The four fields declared ahead of the type code are the eight-byte
            // sign-on identifier, the twenty-byte given name, the twenty-byte
            // family name and the eight-byte SEC-USR-PWD field, referred to here
            // by name and width alone. Their widths sum to the bytes preceding
            // the type code, so the one-based offset is that sum plus one and the
            // zero-based offset is that sum unchanged. The documented figure is
            // the claim under check; the summation is the evidence.
            int bytesPrecedingTypeCode = 0;
            for (int field = 0; field < FIELDS_DECLARED_AHEAD_OF_TYPE_CODE; field++) {
                bytesPrecedingTypeCode += USER_RECORD_FIELD_WIDTHS[field];
            }

            assertThat(bytesPrecedingTypeCode)
                    .as("summed widths of the four fields declared ahead of the type code")
                    .isEqualTo(56);

            final int derivedOneBasedOffset = bytesPrecedingTypeCode + 1;

            assertThat(derivedOneBasedOffset)
                    .as("one-based byte offset derived by summation")
                    .isEqualTo(DOCUMENTED_ONE_BASED_TYPE_CODE_OFFSET);
            assertThat(bytesPrecedingTypeCode)
                    .as("zero-based byte offset of the type code")
                    .isEqualTo(DOCUMENTED_ONE_BASED_TYPE_CODE_OFFSET - 1);

            assertThat(USER_RECORD_FIELD_WIDTHS[FIELDS_DECLARED_AHEAD_OF_TYPE_CODE])
                    .as("declared width of the type code itself")
                    .isEqualTo(TYPE_CODE_WIDTH);

            final int lastByteOfTypeCode = derivedOneBasedOffset + TYPE_CODE_WIDTH - 1;

            assertThat(lastByteOfTypeCode)
                    .as("the one-byte type code neither straddles a boundary nor overruns the record")
                    .isEqualTo(DOCUMENTED_ONE_BASED_TYPE_CODE_OFFSET)
                    .isLessThanOrEqualTo(DECLARED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the six declared field widths sum to the 80 bytes the record carries, so the type byte sits "
                + "inside a fully accounted-for layout that RECORDSIZE(80,80) confirms independently")
        void theSixDeclaredFieldWidthsSumToTheEightyByteRecord() {
            int summedWidth = 0;
            for (final int fieldWidth : USER_RECORD_FIELD_WIDTHS) {
                summedWidth += fieldWidth;
            }

            assertThat(USER_RECORD_FIELD_WIDTHS)
                    .as("every field the user-security copybook declares, including the named filler")
                    .hasSize(USER_RECORD_FIELD_COUNT);
            assertThat(summedWidth)
                    .as("8 + 20 + 20 + 8 + 1 + 23 against the documented record width")
                    .isEqualTo(DECLARED_RECORD_WIDTH);

            // Ties the layout back to the type under test: whichever constant a
            // user carries, its code fills the fifth declared field exactly, so
            // no constant can overrun into the named filler that closes the
            // record and none can leave the field short.
            for (final UserType userType : UserType.values()) {
                assertThat(userType.getCode().getBytes(StandardCharsets.US_ASCII))
                        .as("the code carried by %s fills the fifth declared field exactly", userType.name())
                        .hasSize(USER_RECORD_FIELD_WIDTHS[FIELDS_DECLARED_AHEAD_OF_TYPE_CODE]);
            }
        }

        @Test
        @DisplayName("the populated part of each in-stream provisioning card is 57 characters and the type code is "
                + "its final one, which is why the seed carries the type at column 57")
        void thePopulatedProvisioningCardIsFiftySevenCharacters() {
            int populatedWidth = 0;
            for (int field = 0; field <= FIELDS_DECLARED_AHEAD_OF_TYPE_CODE; field++) {
                populatedWidth += USER_RECORD_FIELD_WIDTHS[field];
            }

            assertThat(populatedWidth)
                    .as("populated card width, whose last column is the type code")
                    .isEqualTo(DOCUMENTED_ONE_BASED_TYPE_CODE_OFFSET);
            assertThat(populatedWidth + USER_RECORD_FIELD_WIDTHS[USER_RECORD_FIELD_COUNT - 1])
                    .as("populated data plus the named filler closes the 80-byte record")
                    .isEqualTo(DECLARED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the sign-on key is the leading 8 bytes per KEYS(8,0), so the type code is never part of the "
                + "key and changing a user's type never relocates the record")
        void theTypeCodeIsNeverPartOfTheKey() {
            assertThat(USER_RECORD_FIELD_WIDTHS[0])
                    .as("the key covers only the leading sign-on identifier")
                    .isEqualTo(SIGN_ON_KEY_WIDTH);
            assertThat(DOCUMENTED_ONE_BASED_TYPE_CODE_OFFSET)
                    .as("the type code begins well beyond the last key byte")
                    .isGreaterThan(SIGN_ON_KEY_WIDTH);
        }
    }

    /**
     * The administrator predicate, which is the whole of the sign-on authorisation decision.
     */
    @Nested
    @DisplayName("Administrator predicate translated from the level-88 condition name")
    class AdministratorPredicate {

        @Test
        @DisplayName("only the administrator constant is an administrator, and the standard-user constant is not, "
                + "mirroring the condition tested before control transfers to the administrative menu")
        void onlyTheAdministratorConstantIsAnAdministrator() {
            assertThat(UserType.ADMIN.isAdmin())
                    .as("user whose record carries the administrator code at byte 57")
                    .isTrue();
            assertThat(UserType.USER.isAdmin())
                    .as("user whose record carries the standard-user code at byte 57")
                    .isFalse();
        }

        @Test
        @DisplayName("exactly one of the two constants reports itself an administrator, so the predicate partitions "
                + "the whole vocabulary rather than merely answering for one constant")
        void thePredicatePartitionsTheWholeVocabulary() {
            int administratorCount = 0;
            for (final UserType userType : UserType.values()) {
                if (userType.isAdmin()) {
                    administratorCount++;
                }
            }

            assertThat(administratorCount)
                    .as("constants for which the administrator predicate holds")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the stored byte A answers the administrator question and the stored byte U does not, composed "
                + "the way sign-on composes it from the byte the record holds")
        void theStoredCodesAnswerThePredicateThroughTheDocumentedComposition() {
            assertThat(UserType.fromCode(ADMINISTRATOR_CODE).map(UserType::isAdmin).orElse(false))
                    .as("administrator claim for the stored byte A")
                    .isTrue();
            assertThat(UserType.fromCode(STANDARD_USER_CODE).map(UserType::isAdmin).orElse(false))
                    .as("administrator claim for the stored byte U")
                    .isFalse();
        }
    }

    /**
     * The tolerant lookup from a raw stored byte to a declared constant.
     */
    @Nested
    @DisplayName("Tolerant lookup from the raw stored byte")
    class TolerantLookup {

        @Test
        @DisplayName("both codes the level-88 list declares resolve to their constant, so a byte taken straight "
                + "from SEC-USR-TYPE needs no conversion at the call site")
        void bothDeclaredCodesResolveToTheirConstant() {
            assertThat(UserType.fromCode(ADMINISTRATOR_CODE))
                    .as("byte read from SEC-USR-TYPE of an administrator record")
                    .contains(UserType.ADMIN);
            assertThat(UserType.fromCode(STANDARD_USER_CODE))
                    .as("byte read from SEC-USR-TYPE of a standard-user record")
                    .contains(UserType.USER);
        }

        @Test
        @DisplayName("the code index covers every declared constant, proved by pairing each constant with its "
                + "hand-written code literal rather than by asking the type for its own code first")
        void theCodeIndexCoversEveryDeclaredConstant() {
            assertThat(DECLARED_CODES_IN_ORDER)
                    .as("one hand-written code literal for each declared constant")
                    .hasSameSizeAs(DECLARED_CONSTANTS_IN_ORDER);
            assertThat(UserType.values())
                    .as("the complete constant set the pairing has to account for")
                    .hasSize(DECLARED_CONSTANTS_IN_ORDER.size());

            for (int position = 0; position < DECLARED_CONSTANTS_IN_ORDER.size(); position++) {
                final UserType expectedConstant = DECLARED_CONSTANTS_IN_ORDER.get(position);
                final String declaredCode = DECLARED_CODES_IN_ORDER.get(position);

                assertThat(UserType.fromCode(declaredCode))
                        .as("the literal code [%s] resolves to %s", declaredCode, expectedConstant.name())
                        .contains(expectedConstant);
                assertThat(expectedConstant.getCode())
                        .as("%s carries the literal code [%s]", expectedConstant.name(), declaredCode)
                        .isEqualTo(declaredCode);
            }
        }

        @ParameterizedTest
        @NullSource
        @EmptySource
        @ValueSource(strings = {"a", "u", " ", "0", "9", "X", "S", "Y", "N", "*", "A ", " A", "AU", "ADMIN"})
        @DisplayName("a byte outside the two declared codes yields an empty result and never an exception, because "
                + "an unrecognised user type is not an error condition in the legacy dispatch: the administrator "
                + "test simply fails and the unconditional alternative routes the session to the main menu")
        void bytesOutsideTheDeclaredCodesYieldAnEmptyResult(final String storedCode) {
            assertThat(UserType.fromCode(storedCode))
                    .as("resolution of the stored byte [%s]", storedCode)
                    .isEmpty();
        }

        @Test
        @DisplayName("lookup never throws for any value the column can hold, which is what lets an unvalidated byte "
                + "flow through the Java layer exactly as it flows through the unconditional legacy alternative")
        void lookupNeverThrowsForAnyValueTheColumnCanHold() {
            final String absentCode = null;

            assertThatCode(() -> UserType.fromCode(ADMINISTRATOR_CODE)).doesNotThrowAnyException();
            assertThatCode(() -> UserType.fromCode(STANDARD_USER_CODE)).doesNotThrowAnyException();
            assertThatCode(() -> UserType.fromCode("a")).doesNotThrowAnyException();
            assertThatCode(() -> UserType.fromCode("u")).doesNotThrowAnyException();
            assertThatCode(() -> UserType.fromCode(" ")).doesNotThrowAnyException();
            assertThatCode(() -> UserType.fromCode("0")).doesNotThrowAnyException();
            assertThatCode(() -> UserType.fromCode("")).doesNotThrowAnyException();
            assertThatCode(() -> UserType.fromCode("A ")).doesNotThrowAnyException();
            assertThatCode(() -> UserType.fromCode("an unmapped value")).doesNotThrowAnyException();
            assertThatCode(() -> UserType.fromCode(absentCode)).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @NullSource
        @EmptySource
        @ValueSource(strings = {"a", "u", " ", "0", "X", "A ", " A", "ADMIN"})
        @DisplayName("an absent or unrecognised byte answers false to the administrator question, the direct "
                + "executable form of the unconditional alternative: only the exact byte A reaches the "
                + "administrative menu and every other byte reaches the main menu instead")
        void anAbsentOrUnrecognisedByteIsNotAnAdministrator(final String storedCode) {
            assertThat(UserType.fromCode(storedCode).map(UserType::isAdmin).orElse(false))
                    .as("administrator claim for the stored byte [%s]", storedCode)
                    .isFalse();
        }

        @Test
        @DisplayName("lookup applies no case folding, so a lower-case a is not the administrator constant, does not "
                + "resolve to it and cannot be coaxed into claiming administrator rights")
        void lookupAppliesNoCaseFolding() {
            final Optional<UserType> lowerCaseAdministratorByte = UserType.fromCode("a");

            assertThat(lowerCaseAdministratorByte)
                    .as("lower-case form of the administrator code")
                    .isEmpty()
                    .isNotEqualTo(Optional.of(UserType.ADMIN));
            assertThat(lowerCaseAdministratorByte.map(UserType::isAdmin).orElse(false))
                    .as("administrator claim for the lower-case form of the administrator code")
                    .isFalse();

            assertThat(UserType.fromCode("u"))
                    .as("lower-case form of the standard-user code")
                    .isEmpty()
                    .isNotEqualTo(Optional.of(UserType.USER));
        }

        @Test
        @DisplayName("a padded or over-length value is rejected rather than trimmed or truncated into a match, "
                + "because the legacy system performs neither on a one-byte field")
        void aPaddedOrOverLengthValueIsRejectedRatherThanTrimmedOrTruncated() {
            assertThat(UserType.fromCode("A "))
                    .as("administrator code followed by a pad byte")
                    .isEmpty();
            assertThat(UserType.fromCode(" A"))
                    .as("administrator code preceded by a pad byte")
                    .isEmpty();
            assertThat(UserType.fromCode("AU"))
                    .as("both declared codes in one over-length value")
                    .isEmpty();
            assertThat(UserType.fromCode("ADMIN"))
                    .as("constant name rather than the code the record stores")
                    .isEmpty();
        }
    }

    /**
     * The deliberate absence of a synthetic default constant.
     */
    @Nested
    @DisplayName("Absence of a synthetic default constant")
    class SyntheticDefaultAbsence {

        @Test
        @DisplayName("the enumeration exposes no synthetic default constant for an unrecognised byte, because the "
                + "unconditional alternative gives the legacy dispatch no third state to represent")
        void theEnumerationExposesNoSyntheticDefaultConstant() {
            // Absence is documented here rather than merely left implicit,
            // because a synthetic default is the single most tempting addition to
            // a two-value vocabulary with a tolerant lookup. There is no UNKNOWN,
            // no NONE, no OTHER, no INVALID, no UNMAPPED and no DEFAULT constant,
            // and none may be added: the legacy dispatch tests the administrator
            // condition and closes with an unconditional alternative, so it has
            // exactly two outcomes and can represent no third state. A synthetic
            // constant would invent one and would let a caller branch on
            // something the legacy program never branched on. Absence of a value
            // is therefore modelled by an empty result at the lookup boundary
            // instead.
            //
            // The proof is made from the outside and without reflection: the
            // constant set is enumerated exhaustively, so a third constant would
            // fail the first assertion, and each plausible spelling appears only
            // as a rejected lookup input. No constant of this enumeration is ever
            // named here beyond the two the estate declares.
            assertThat(UserType.values())
                    .as("the complete constant set, which a synthetic default would enlarge")
                    .hasSize(EXPECTED_CONSTANT_COUNT)
                    .containsExactly(UserType.ADMIN, UserType.USER);

            for (final String spelling : SYNTHETIC_DEFAULT_SPELLINGS) {
                assertThat(UserType.fromCode(spelling))
                        .as("the spelling [%s] is neither a constant nor a code the record can hold", spelling)
                        .isEmpty();
            }
        }
    }

    /**
     * Correspondence with the ten users the provisioning job seeds in stream.
     */
    @Nested
    @DisplayName("The ten seeded user-security records")
    class SeededUsers {

        @Test
        @DisplayName("ten users are seeded in an exact five-and-five split, five carrying the administrator byte and "
                + "five carrying the standard-user byte")
        void tenUsersAreSeededInAFiveAndFiveSplit() {
            assertThat(SEEDED_TYPE_CODES)
                    .as("type bytes read from the last populated column of each in-stream card")
                    .hasSize(SEEDED_USER_COUNT);
            assertThat(SEEDED_TYPE_CODES.stream().filter(ADMINISTRATOR_CODE::equals).count())
                    .as("seeded records carrying the administrator byte")
                    .isEqualTo(SEEDED_ADMINISTRATOR_COUNT);
            assertThat(SEEDED_TYPE_CODES.stream().filter(STANDARD_USER_CODE::equals).count())
                    .as("seeded records carrying the standard-user byte")
                    .isEqualTo(SEEDED_STANDARD_USER_COUNT);
            assertThat(SEEDED_ADMINISTRATOR_COUNT + SEEDED_STANDARD_USER_COUNT)
                    .as("the two groups account for every seeded record")
                    .isEqualTo(SEEDED_USER_COUNT);
        }

        @Test
        @DisplayName("the seeded sign-on identifiers are five administrators and five standard users, each eight "
                + "bytes wide so that every one fills the key the cluster declares")
        void theSeededIdentifiersAreFiveAdministratorsAndFiveStandardUsers() {
            assertThat(SEEDED_ADMINISTRATOR_IDS)
                    .as("sign-on identifiers of the seeded administrators")
                    .hasSize(SEEDED_ADMINISTRATOR_COUNT)
                    .doesNotHaveDuplicates();
            assertThat(SEEDED_STANDARD_USER_IDS)
                    .as("sign-on identifiers of the seeded standard users")
                    .hasSize(SEEDED_STANDARD_USER_COUNT)
                    .doesNotHaveDuplicates();

            for (final String identifier : SEEDED_ADMINISTRATOR_IDS) {
                assertThat(identifier.getBytes(StandardCharsets.US_ASCII))
                        .as("US-ASCII width of the seeded identifier [%s]", identifier)
                        .hasSize(SIGN_ON_KEY_WIDTH);
            }
            for (final String identifier : SEEDED_STANDARD_USER_IDS) {
                assertThat(identifier.getBytes(StandardCharsets.US_ASCII))
                        .as("US-ASCII width of the seeded identifier [%s]", identifier)
                        .hasSize(SIGN_ON_KEY_WIDTH);
            }
        }

        @Test
        @DisplayName("every type byte the seed writes resolves to a declared constant, so the provisioning job "
                + "introduces no value the vocabulary cannot represent")
        void everySeededTypeByteResolvesToADeclaredConstant() {
            for (int card = 0; card < SEEDED_TYPE_CODES.size(); card++) {
                final String seededTypeCode = SEEDED_TYPE_CODES.get(card);

                assertThat(UserType.fromCode(seededTypeCode))
                        .as("type byte [%s] of seeded card %d of %d",
                                seededTypeCode, card + 1, SEEDED_USER_COUNT)
                        .isPresent();
            }
        }

        @Test
        @DisplayName("the seed grants administrator rights to exactly five of the ten users, so the seeded estate "
                + "reaches the administrative menu five times and the main menu five times")
        void theSeedGrantsAdministratorRightsToExactlyFiveOfTheTenUsers() {
            int administratorSessions = 0;
            int mainMenuSessions = 0;
            for (final String seededTypeCode : SEEDED_TYPE_CODES) {
                if (UserType.fromCode(seededTypeCode).map(UserType::isAdmin).orElse(false)) {
                    administratorSessions++;
                } else {
                    mainMenuSessions++;
                }
            }

            assertThat(administratorSessions)
                    .as("seeded users routed to the administrative menu")
                    .isEqualTo(SEEDED_ADMINISTRATOR_COUNT);
            assertThat(mainMenuSessions)
                    .as("seeded users routed to the main menu by the unconditional alternative")
                    .isEqualTo(SEEDED_STANDARD_USER_COUNT);
        }

        @Test
        @DisplayName("the administrator cards precede the standard-user cards in the seed, matching the order the "
                + "provisioning job lists its in-stream card images")
        void theAdministratorCardsPrecedeTheStandardUserCards() {
            assertThat(SEEDED_TYPE_CODES.subList(0, SEEDED_ADMINISTRATOR_COUNT))
                    .as("type bytes of the first five in-stream cards")
                    .containsOnly(ADMINISTRATOR_CODE);
            assertThat(SEEDED_TYPE_CODES.subList(SEEDED_ADMINISTRATOR_COUNT, SEEDED_USER_COUNT))
                    .as("type bytes of the last five in-stream cards")
                    .containsOnly(STANDARD_USER_CODE);
        }
    }
}
