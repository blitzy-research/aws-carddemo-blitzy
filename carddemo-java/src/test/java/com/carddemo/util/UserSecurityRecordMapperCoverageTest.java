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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.carddemo.domain.UserSecurity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Byte-parity acceptance for the 80-byte user-security record layout.
 *
 * <h2>What is under test</h2>
 * {@link UserSecurityRecordMapper} is the only one of the eleven fixed-width mappers that reads a
 * credential, and it is therefore the only one whose contract is partly a security contract. Three
 * properties are asserted here that no other mapper needs. The mapper never stores the eight bytes it
 * reads out of the credential window - it hands them to a caller-supplied digest function and stores
 * only what comes back, which is what keeps hashing outside this package. It never <em>writes</em>
 * those eight bytes either: the encoder declares the credential window as a blank run rather than as a
 * field, so no digest fragment can reach a record image no matter how the encoder is called. And no
 * diagnostic it raises echoes the record, because echoing an eighty-byte image of this layout would
 * print a credential.
 *
 * <h2>Where the expectations come from</h2>
 * The geometry is stated as literal integers rather than read from the class under test. The ten
 * identities are transcribed from a byte-level reading of the provisioning job that writes the legacy
 * dataset {@code [app/jcl/DUSRSECJ.jcl:L35-L44]}, which carries them in stream as readable ASCII
 * card images; the dataset itself exists only in EBCDIC and no decode of it is required. Each card is
 * fifty-seven bytes, exactly the mapped length, and the record's declared eighty bytes are reached by
 * the twenty-three bytes of named trailing filler the dataset's record length supplies.
 *
 * <h2>Why the round trip is asserted over two windows rather than one</h2>
 * A decode followed by an encode reproduces the identifier and both names, at {@code [0, 48)}, and the
 * role code, at {@code [56, 57)}. It cannot reproduce the credential window between them, and that is
 * the point rather than a limitation: the mapper holds nothing that could go there. The two windows are
 * therefore asserted separately, and the gap between them is asserted to be blank.
 *
 * <p>A pure in-process unit test: no application context, no database, no container, no mocking
 * framework, and no reflection. The digest function is a plain lambda, which is precisely what makes
 * that possible.</p>
 *
 * <p>No legacy source text is reproduced.</p>
 */
@DisplayName("UserSecurityRecordMapper - the 80-byte user-security record")
class UserSecurityRecordMapperCoverageTest {

    /** Declared record width, stated here rather than read from the class under test. */
    private static final int EXPECTED_RECORD_WIDTH = 80;

    /** Declared width of the prefix that carries information. */
    private static final int EXPECTED_MAPPED_WIDTH = 57;

    /** Declared placement of the eight-character sign-on identifier. */
    private static final int EXPECTED_ID_OFFSET = 0;

    /** Declared width of the eight-character sign-on identifier. */
    private static final int EXPECTED_ID_WIDTH = 8;

    /** Declared placement of the twenty-character first name. */
    private static final int EXPECTED_FIRST_NAME_OFFSET = 8;

    /** Declared width of the twenty-character first name. */
    private static final int EXPECTED_FIRST_NAME_WIDTH = 20;

    /** Declared placement of the twenty-character last name. */
    private static final int EXPECTED_LAST_NAME_OFFSET = 28;

    /** Declared width of the twenty-character last name. */
    private static final int EXPECTED_LAST_NAME_WIDTH = 20;

    /** Declared placement of the eight-byte credential window. */
    private static final int EXPECTED_CREDENTIAL_OFFSET = 48;

    /** Declared width of the eight-byte credential window. */
    private static final int EXPECTED_CREDENTIAL_WIDTH = 8;

    /** Declared placement of the one-character role code. */
    private static final int EXPECTED_TYPE_OFFSET = 56;

    /** Declared width of the one-character role code. */
    private static final int EXPECTED_TYPE_WIDTH = 1;

    /** Declared placement of the named trailing filler. */
    private static final int EXPECTED_FILLER_OFFSET = 57;

    /** Declared width of the named trailing filler. */
    private static final int EXPECTED_FILLER_WIDTH = 23;

    /** The eight-character credential every legacy card carries. */
    private static final String LEGACY_CREDENTIAL = "PASSWORD";

    /** Number of identities the provisioning job writes. */
    private static final int EXPECTED_IDENTITIES = 10;

    /** Number of administrator identities among them. */
    private static final int EXPECTED_ADMINISTRATORS = 5;

    /**
     * A structurally valid BCrypt digest, hand-assembled from the version marker, a cost above the
     * entity's floor and fifty-three characters of the BCrypt radix-64 alphabet.
     *
     * <p>It hashes nothing and verifies nothing. It exists so that the digest function used throughout
     * this class can be a deterministic constant rather than a call into an encoder, which keeps every
     * assertion here about the mapper rather than about a hashing implementation.</p>
     */
    private static final String CANNED_DIGEST =
            "$2a$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.";

    /** A second, different structurally valid digest, used to prove the stored value is the returned one. */
    private static final String OTHER_CANNED_DIGEST =
            "$2b$14$zyxwvutsrqponmlkjihgfedcbaZYXWVUTSRQPONMLKJIHGFEDCBA/";

    /** The exact length a BCrypt digest must have, stated here so the canned fixtures are self-checking. */
    private static final int BCRYPT_DIGEST_LENGTH = 60;

    /**
     * A digest function that ignores its argument and returns a constant.
     *
     * <p>Ignoring the argument is deliberate: a function that derived its answer from the credential
     * would make it impossible to tell whether the entity stored the function's return or the raw
     * window.</p>
     */
    private static final UnaryOperator<String> CONSTANT_DIGEST = credential -> CANNED_DIGEST;

    /**
     * Supplies the ten legacy identities as identifier, first name, last name and role code, exactly as
     * the provisioning job's card images carry them.
     *
     * @return one argument quadruple per identity
     */
    static Stream<Arguments> legacyIdentities() {
        return Stream.of(
                Arguments.of("ADMIN001", "MARGARET", "GOLD", "A"),
                Arguments.of("ADMIN002", "RUSSELL", "RUSSELL", "A"),
                Arguments.of("ADMIN003", "RAYMOND", "WHITMORE", "A"),
                Arguments.of("ADMIN004", "EMMANUEL", "CASGRAIN", "A"),
                Arguments.of("ADMIN005", "GRANVILLE", "LACHAPELLE", "A"),
                Arguments.of("USER0001", "LAWRENCE", "THOMAS", "U"),
                Arguments.of("USER0002", "AJITH", "KUMAR", "U"),
                Arguments.of("USER0003", "LAURITZ", "ALME", "U"),
                Arguments.of("USER0004", "AVERARDO", "MAZZI", "U"),
                Arguments.of("USER0005", "LEE", "TING", "U"));
    }

    /**
     * Right-pads a value with spaces to a declared field width.
     *
     * @param value the value to pad
     * @param width the declared field width
     * @return the value padded on the right to exactly {@code width} characters
     */
    private static String padded(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Repeats a character into a run of a given width.
     *
     * @param character the character to repeat
     * @param width     the number of repetitions
     * @return a string of exactly {@code width} copies of {@code character}
     */
    private static String run(final char character, final int width) {
        return String.valueOf(character).repeat(width);
    }

    /**
     * Assembles a complete 80-byte record image from its five fields plus the named trailing filler.
     *
     * @param id         the sign-on identifier, right-padded here to its declared width
     * @param firstName  the first name, right-padded here
     * @param lastName   the last name, right-padded here
     * @param credential the credential, right-padded here into the eight-byte window
     * @param type       the one-character role code
     * @return a record image of exactly {@link #EXPECTED_RECORD_WIDTH} characters
     */
    private static String image(final String id, final String firstName, final String lastName,
            final String credential, final String type) {
        return padded(id, EXPECTED_ID_WIDTH)
                + padded(firstName, EXPECTED_FIRST_NAME_WIDTH)
                + padded(lastName, EXPECTED_LAST_NAME_WIDTH)
                + padded(credential, EXPECTED_CREDENTIAL_WIDTH)
                + type
                + run(' ', EXPECTED_FILLER_WIDTH);
    }

    /**
     * Assembles the record image of one legacy identity, credential included.
     *
     * @param id        the sign-on identifier
     * @param firstName the first name
     * @param lastName  the last name
     * @param type      the one-character role code
     * @return the identity's complete record image
     */
    private static String legacyImage(final String id, final String firstName,
            final String lastName, final String type) {
        return image(id, firstName, lastName, LEGACY_CREDENTIAL, type);
    }

    /**
     * A digest function that records every value handed to it and returns a constant.
     */
    private static final class RecordingDigestFunction implements UnaryOperator<String> {

        /** Every argument the mapper handed to this function, in call order. */
        private final List<String> observedArguments = new ArrayList<>();

        /** The value this function returns for every argument. */
        private final String answer;

        /**
         * Creates a recording function with a fixed answer.
         *
         * @param answer the value to return for every argument
         */
        RecordingDigestFunction(final String answer) {
            this.answer = answer;
        }

        @Override
        public String apply(final String credential) {
            observedArguments.add(credential);
            return answer;
        }

        /**
         * Returns every argument observed, in call order.
         *
         * @return the observed arguments
         */
        List<String> observedArguments() {
            return List.copyOf(observedArguments);
        }
    }

    @Nested
    @DisplayName("the declared geometry reproduces the copybook exactly")
    class DeclaredGeometry {

        @Test
        @DisplayName("the record is 80 bytes and the mapped prefix is 57")
        void theWidthsAreTheDeclaredWidths() {
            assertThat(UserSecurityRecordMapper.RECORD_LENGTH)
                    .as("the user-security cluster declares an 80-byte record")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
            assertThat(UserSecurityRecordMapper.MAPPED_LENGTH)
                    .as("the five mapped fields occupy the first 57 bytes")
                    .isEqualTo(EXPECTED_MAPPED_WIDTH);
        }

        @Test
        @DisplayName("all five fields sit at their declared offsets and widths")
        void allFiveFieldsSitWhereTheCopybookPutsThem() {
            assertThat(UserSecurityRecordMapper.SEC_USR_ID_OFFSET).isEqualTo(EXPECTED_ID_OFFSET);
            assertThat(UserSecurityRecordMapper.SEC_USR_ID_LENGTH).isEqualTo(EXPECTED_ID_WIDTH);
            assertThat(UserSecurityRecordMapper.SEC_USR_FNAME_OFFSET)
                    .isEqualTo(EXPECTED_FIRST_NAME_OFFSET);
            assertThat(UserSecurityRecordMapper.SEC_USR_FNAME_LENGTH)
                    .isEqualTo(EXPECTED_FIRST_NAME_WIDTH);
            assertThat(UserSecurityRecordMapper.SEC_USR_LNAME_OFFSET)
                    .isEqualTo(EXPECTED_LAST_NAME_OFFSET);
            assertThat(UserSecurityRecordMapper.SEC_USR_LNAME_LENGTH)
                    .isEqualTo(EXPECTED_LAST_NAME_WIDTH);
            assertThat(UserSecurityRecordMapper.SEC_USR_PWD_OFFSET)
                    .as("the credential window sits between the names and the role code")
                    .isEqualTo(EXPECTED_CREDENTIAL_OFFSET);
            assertThat(UserSecurityRecordMapper.SEC_USR_PWD_LENGTH)
                    .as("the legacy credential field is eight characters")
                    .isEqualTo(EXPECTED_CREDENTIAL_WIDTH);
            assertThat(UserSecurityRecordMapper.SEC_USR_TYPE_OFFSET)
                    .isEqualTo(EXPECTED_TYPE_OFFSET);
            assertThat(UserSecurityRecordMapper.SEC_USR_TYPE_LENGTH).isEqualTo(EXPECTED_TYPE_WIDTH);
        }

        @Test
        @DisplayName("the filler occupies the whole remainder, so every byte is accounted for")
        void theFillerAccountsForTheRemainder() {
            assertThat(UserSecurityRecordMapper.SEC_USR_FILLER_OFFSET)
                    .isEqualTo(EXPECTED_FILLER_OFFSET)
                    .isEqualTo(UserSecurityRecordMapper.MAPPED_LENGTH);
            assertThat(UserSecurityRecordMapper.SEC_USR_FILLER_LENGTH)
                    .isEqualTo(EXPECTED_FILLER_WIDTH);
            assertThat(UserSecurityRecordMapper.SEC_USR_FILLER_OFFSET
                    + UserSecurityRecordMapper.SEC_USR_FILLER_LENGTH)
                    .as("the five fields and the filler must tile the record exactly")
                    .isEqualTo(EXPECTED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the two reproducible windows are exactly the record minus its credential window")
        void theReproducibleWindowsExcludeTheCredential() {
            assertThat(UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_OFFSET)
                    .as("the first window starts the record")
                    .isZero();
            assertThat(UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_LENGTH)
                    .as("and ends exactly where the credential window begins")
                    .isEqualTo(EXPECTED_CREDENTIAL_OFFSET)
                    .isEqualTo(UserSecurityRecordMapper.SEC_USR_PWD_OFFSET);
            assertThat(UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_OFFSET)
                    .as("the second window starts where the credential window ends")
                    .isEqualTo(UserSecurityRecordMapper.SEC_USR_PWD_OFFSET
                            + UserSecurityRecordMapper.SEC_USR_PWD_LENGTH);
            assertThat(UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_OFFSET
                    + UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_LENGTH)
                    .as("and ends exactly where the named filler begins")
                    .isEqualTo(UserSecurityRecordMapper.SEC_USR_FILLER_OFFSET);
            assertThat(UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_LENGTH
                    + UserSecurityRecordMapper.SEC_USR_PWD_LENGTH
                    + UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_LENGTH)
                    .as("the two windows plus the credential window are the whole mapped prefix")
                    .isEqualTo(UserSecurityRecordMapper.MAPPED_LENGTH);
        }

        @Test
        @DisplayName("the layout names itself and its copybook, so a diagnostic can identify it")
        void theLayoutNamesItself() {
            assertThat(UserSecurityRecordMapper.ARTEFACT).isNotBlank();
            assertThat(UserSecurityRecordMapper.COPYBOOK).isNotBlank();
        }
    }

    @Nested
    @DisplayName("decoding a record image")
    class Decoding {

        @Test
        @DisplayName("all four non-credential fields arrive verbatim, untrimmed")
        void theNonCredentialFieldsArriveVerbatim() {
            UserSecurity decoded = UserSecurityRecordMapper.fromRecord(
                    legacyImage("ADMIN001", "MARGARET", "GOLD", "A"), CONSTANT_DIGEST);

            assertThat(decoded.getSecUsrId())
                    .as("the identifier is the primary key and is stored untrimmed")
                    .isEqualTo("ADMIN001")
                    .hasSize(EXPECTED_ID_WIDTH);
            assertThat(decoded.getSecUsrFname())
                    .as("the first name keeps every one of its trailing spaces")
                    .isEqualTo(padded("MARGARET", EXPECTED_FIRST_NAME_WIDTH));
            assertThat(decoded.getSecUsrLname())
                    .isEqualTo(padded("GOLD", EXPECTED_LAST_NAME_WIDTH));
            assertThat(decoded.getSecUsrType())
                    .as("the role code is one character and is not mapped here")
                    .isEqualTo("A")
                    .hasSize(EXPECTED_TYPE_WIDTH);
        }

        @Test
        @DisplayName("the digest function receives the credential window exactly, and exactly once")
        void theDigestFunctionReceivesTheWindowExactlyOnce() {
            RecordingDigestFunction recording = new RecordingDigestFunction(CANNED_DIGEST);

            UserSecurityRecordMapper.fromRecord(
                    legacyImage("USER0001", "LAWRENCE", "THOMAS", "U"), recording);

            assertThat(recording.observedArguments())
                    .as("one record must produce exactly one hashing call, of the eight raw bytes")
                    .containsExactly(LEGACY_CREDENTIAL);
        }

        @Test
        @DisplayName("a credential shorter than its field arrives padded, because the field has a width")
        void aShortCredentialArrivesPadded() {
            RecordingDigestFunction recording = new RecordingDigestFunction(CANNED_DIGEST);

            UserSecurityRecordMapper.fromRecord(
                    image("USER0002", "AJITH", "KUMAR", "abc", "U"), recording);

            assertThat(recording.observedArguments())
                    .as("the window is eight bytes wide whatever the credential's length")
                    .containsExactly(padded("abc", EXPECTED_CREDENTIAL_WIDTH));
        }

        @Test
        @DisplayName("a blank credential window is handed over as eight spaces rather than skipped")
        void aBlankCredentialWindowIsHandedOver() {
            RecordingDigestFunction recording = new RecordingDigestFunction(CANNED_DIGEST);

            UserSecurityRecordMapper.fromRecord(
                    image("USER0003", "LAURITZ", "ALME", "", "U"), recording);

            assertThat(recording.observedArguments())
                    .as("deciding a blank credential needs no hashing is the caller's decision, not this"
                            + " layer's")
                    .containsExactly(run(' ', EXPECTED_CREDENTIAL_WIDTH));
        }

        @Test
        @DisplayName("the entity stores the function's return, not the credential it was derived from")
        void theEntityStoresTheFunctionsReturn() {
            UserSecurity decoded = UserSecurityRecordMapper.fromRecord(
                    legacyImage("ADMIN002", "RUSSELL", "RUSSELL", "A"),
                    credential -> OTHER_CANNED_DIGEST);

            assertThat(decoded.credentialDigest())
                    .as("a function that ignores its argument proves the return is what is stored")
                    .isEqualTo(OTHER_CANNED_DIGEST)
                    .isNotEqualTo(CANNED_DIGEST);
        }

        @Test
        @DisplayName("the raw credential reaches no readable part of the decoded entity")
        void theRawCredentialReachesNoReadablePart() {
            UserSecurity decoded = UserSecurityRecordMapper.fromRecord(
                    legacyImage("ADMIN003", "RAYMOND", "WHITMORE", "A"), CONSTANT_DIGEST);

            assertThat(decoded.credentialDigest())
                    .as("the stored value must be the digest, never the cleartext it came from")
                    .doesNotContain(LEGACY_CREDENTIAL);
            assertThat(List.of(decoded.getSecUsrId(), decoded.getSecUsrFname(),
                            decoded.getSecUsrLname(), decoded.getSecUsrType(), decoded.toString()))
                    .as("no readable rendering of the entity may carry the cleartext credential")
                    .allSatisfy(rendering ->
                            assertThat(rendering).doesNotContain(LEGACY_CREDENTIAL));
        }

        @Test
        @DisplayName("the byte overload agrees with the text overload byte for byte")
        void theByteOverloadAgreesWithTheTextOverload() {
            String text = legacyImage("ADMIN004", "EMMANUEL", "CASGRAIN", "A");
            RecordingDigestFunction fromTextRecording = new RecordingDigestFunction(CANNED_DIGEST);
            RecordingDigestFunction fromBytesRecording = new RecordingDigestFunction(CANNED_DIGEST);

            UserSecurity fromText =
                    UserSecurityRecordMapper.fromRecord(text, fromTextRecording);
            UserSecurity fromBytes = UserSecurityRecordMapper.fromRecord(
                    text.getBytes(StandardCharsets.US_ASCII), fromBytesRecording);

            assertThat(fromBytes.getSecUsrId()).isEqualTo(fromText.getSecUsrId());
            assertThat(fromBytes.getSecUsrFname()).isEqualTo(fromText.getSecUsrFname());
            assertThat(fromBytes.getSecUsrLname()).isEqualTo(fromText.getSecUsrLname());
            assertThat(fromBytes.getSecUsrType()).isEqualTo(fromText.getSecUsrType());
            assertThat(fromBytesRecording.observedArguments())
                    .as("choosing bytes over text must not change the value handed to the function")
                    .isEqualTo(fromTextRecording.observedArguments());
        }
    }

    @Nested
    @DisplayName("the digest function's contract is enforced rather than trusted")
    class DigestFunctionContract {

        @Test
        @DisplayName("a null function is refused before any byte is read, on every entry point")
        void aNullFunctionIsRefused() {
            String record = legacyImage("ADMIN005", "GRANVILLE", "LACHAPELLE", "A");
            byte[] bytes = record.getBytes(StandardCharsets.US_ASCII);

            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(record, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(bytes, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(bytes, 0, null));
        }

        @Test
        @DisplayName("a function that returns null is refused, and the refusal explains why there is no fallback")
        void aFunctionReturningNullIsRefused() {
            String record = legacyImage("USER0004", "AVERARDO", "MAZZI", "U");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(
                            record, credential -> null))
                    .withMessageContaining("credentialDigestFunction returned null")
                    .withMessageContaining("no unhashed path");
        }

        @Test
        @DisplayName("a refusal caused by the function does not echo the credential it was given")
        void aRefusalDoesNotEchoTheCredential() {
            String record = legacyImage("USER0005", "LEE", "TING", "U");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(
                            record, credential -> null))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .as("a diagnostic that echoed this field would print a credential")
                            .doesNotContain(LEGACY_CREDENTIAL)
                            .doesNotContain(record));
        }

        @Test
        @DisplayName("the canned digests this class uses are themselves acceptable, so the fixture is honest")
        void theCannedDigestsAreThemselvesAcceptable() {
            assertThat(CANNED_DIGEST).hasSize(BCRYPT_DIGEST_LENGTH);
            assertThat(OTHER_CANNED_DIGEST).hasSize(BCRYPT_DIGEST_LENGTH);
            assertThat(UserSecurityRecordMapper.fromRecord(
                    legacyImage("ADMIN001", "MARGARET", "GOLD", "A"), CONSTANT_DIGEST)
                    .credentialDigest())
                    .as("a fixture the entity would reject would make every other assertion vacuous")
                    .isEqualTo(CANNED_DIGEST);
        }

        @ParameterizedTest(name = "return \"{0}\"")
        @ValueSource(strings = {
            "",
            "PASSWORD",
            "$2a$12$abcdefghij",
            "$2c$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.",
            "$2a$09$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.",
            "$2a$1x$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.",
            "$2a$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!"})
        @DisplayName("a function that returns something other than a digest is refused by the entity")
        void aFunctionReturningANonDigestIsRefused(final String returned) {
            String record = legacyImage("ADMIN001", "MARGARET", "GOLD", "A");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the credential column holds digests, and nothing else may be written into it")
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(
                            record, credential -> returned));
        }

        @ParameterizedTest(name = "return \"{0}\"")
        @ValueSource(strings = {
            "PASSWORD",
            "$2c$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.",
            "$2a$09$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ."})
        @DisplayName("the refusal of a non-digest never echoes the value it rejected")
        void theRefusalOfANonDigestDoesNotEchoIt(final String returned) {
            String record = legacyImage("ADMIN001", "MARGARET", "GOLD", "A");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(
                            record, credential -> returned))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .as("a rejected credential is the value least safe to print")
                            .doesNotContain(returned)
                            .doesNotContain(LEGACY_CREDENTIAL));
        }

        @Test
        @DisplayName("the function is not called at all when the image is the wrong width")
        void theFunctionIsNotCalledWhenTheImageIsRejected() {
            RecordingDigestFunction recording = new RecordingDigestFunction(CANNED_DIGEST);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(
                            run(' ', EXPECTED_RECORD_WIDTH - 1), recording));

            assertThat(recording.observedArguments())
                    .as("a record that is not a record must not reach a hashing function")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("decoding one record out of a larger buffer")
    class BufferDecoding {

        /** Stride of a fixed-length dataset with no terminator: the record width itself. */
        private static final int STRIDE = EXPECTED_RECORD_WIDTH;

        /**
         * Assembles the ten legacy identities into one unterminated buffer.
         *
         * @return the ten record images concatenated
         */
        private static byte[] tenRecordBuffer() {
            String concatenated = legacyIdentities()
                    .map(arguments -> legacyImage((String) arguments.get()[0],
                            (String) arguments.get()[1], (String) arguments.get()[2],
                            (String) arguments.get()[3]))
                    .collect(Collectors.joining());
            return concatenated.getBytes(StandardCharsets.US_ASCII);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.util.UserSecurityRecordMapperCoverageTest#legacyIdentities")
        @DisplayName("stride arithmetic selects each record out of a ten-record buffer")
        void strideArithmeticSelectsEachRecord(final String id, final String firstName,
                final String lastName, final String type) {
            byte[] buffer = tenRecordBuffer();
            int ordinal = legacyIdentities().map(arguments -> (String) arguments.get()[0])
                    .toList().indexOf(id);

            UserSecurity decoded = UserSecurityRecordMapper.fromRecord(
                    buffer, ordinal * STRIDE, CONSTANT_DIGEST);

            assertThat(decoded.getSecUsrId())
                    .as("record %d of the buffer must be selected, not its neighbour", ordinal)
                    .isEqualTo(id);
            assertThat(decoded.getSecUsrFname())
                    .isEqualTo(padded(firstName, EXPECTED_FIRST_NAME_WIDTH));
            assertThat(decoded.getSecUsrLname())
                    .isEqualTo(padded(lastName, EXPECTED_LAST_NAME_WIDTH));
            assertThat(decoded.getSecUsrType()).isEqualTo(type);
        }

        @Test
        @DisplayName("a negative start index is refused rather than wrapped")
        void aNegativeStartIndexIsRefused() {
            byte[] buffer = new byte[EXPECTED_RECORD_WIDTH];

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(
                            buffer, -1, CONSTANT_DIGEST))
                    .withMessageContaining("must not be negative");
        }

        @Test
        @DisplayName("a record that would run past the end of the buffer is refused, not short-read")
        void anOverrunningRecordIsRefused() {
            byte[] buffer = new byte[EXPECTED_RECORD_WIDTH];

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(
                            buffer, 1, CONSTANT_DIGEST))
                    .withMessageContaining("does not fit inside the supplied buffer");
        }
    }

    @Nested
    @DisplayName("a malformed image is refused, and the refusal never prints a credential")
    class MalformedInput {

        @ParameterizedTest(name = "width {0}")
        @ValueSource(ints = {0, 1, 57, 79, 81, 160})
        @DisplayName("any width other than 80 is refused, in both directions")
        void anyOtherWidthIsRefused(final int width) {
            String wrongWidth = run(' ', width);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(
                            wrongWidth, CONSTANT_DIGEST))
                    .withMessageContaining("must be exactly " + EXPECTED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the width refusal reports lengths only, and states why it reports nothing else")
        void theWidthRefusalReportsLengthsOnly() {
            String overlong =
                    legacyImage("ADMIN001", "MARGARET", "GOLD", "A") + " ";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(
                            overlong, CONSTANT_DIGEST))
                    .satisfies(refusal -> {
                        assertThat(refusal.getMessage())
                                .as("the diagnostic must state the geometry it expected")
                                .contains(String.valueOf(EXPECTED_RECORD_WIDTH))
                                .contains(String.valueOf(EXPECTED_MAPPED_WIDTH))
                                .contains(String.valueOf(EXPECTED_FILLER_WIDTH))
                                .contains("echoing the record would print a credential");
                        assertThat(refusal.getMessage())
                                .as("and must carry neither the image nor the credential inside it")
                                .doesNotContain(LEGACY_CREDENTIAL)
                                .doesNotContain("ADMIN001")
                                .doesNotContain("MARGARET");
                    });
        }

        @Test
        @DisplayName("a byte-array width refusal is equally silent about the record")
        void aByteArrayWidthRefusalIsEquallySilent() {
            byte[] tooShort = legacyImage("ADMIN002", "RUSSELL", "RUSSELL", "A")
                    .substring(0, EXPECTED_RECORD_WIDTH - 1)
                    .getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(
                            tooShort, CONSTANT_DIGEST))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .doesNotContain(LEGACY_CREDENTIAL)
                            .doesNotContain("RUSSELL"));
        }

        @Test
        @DisplayName("a non-ASCII character is refused rather than transcoded or measured as one byte")
        void aNonAsciiCharacterIsRefused() {
            String withAccent =
                    image("ADMIN003", "RAYM\u00d3ND", "WHITMORE", LEGACY_CREDENTIAL, "A");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("measuring by encoding would let a substitution report a plausible width")
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(
                            withAccent, CONSTANT_DIGEST))
                    .withMessageContaining("US-ASCII cannot represent");
        }

        @Test
        @DisplayName("a byte above 0x7F is refused, because it means another encoding")
        void aHighByteIsRefused() {
            byte[] record = legacyImage("ADMIN004", "EMMANUEL", "CASGRAIN", "A")
                    .getBytes(StandardCharsets.US_ASCII);
            record[EXPECTED_FIRST_NAME_OFFSET] = (byte) 0xD3;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(
                            record, CONSTANT_DIGEST))
                    .withMessageContaining("non-ASCII byte");
        }

        @Test
        @DisplayName("a null image is refused on every decode entry point")
        void aNullImageIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(
                            (String) null, CONSTANT_DIGEST));
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(
                            (byte[]) null, CONSTANT_DIGEST));
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(
                            null, 0, CONSTANT_DIGEST));
        }
    }

    @Nested
    @DisplayName("encoding an entity, which can never write a credential")
    class Encoding {

        /**
         * Builds an entity carrying the canned digest.
         *
         * @param id        the sign-on identifier
         * @param firstName the first name
         * @param lastName  the last name
         * @param type      the role code
         * @return the entity
         */
        private static UserSecurity entity(final String id, final String firstName,
                final String lastName, final String type) {
            return new UserSecurity(id, firstName, lastName, CANNED_DIGEST, type);
        }

        @Test
        @DisplayName("the image is exactly 80 bytes and carries no terminator")
        void theImageIsExactlyTheDeclaredWidth() {
            String encoded = UserSecurityRecordMapper.toRecord(
                    entity("ADMIN001", "MARGARET", "GOLD", "A"));

            assertThat(encoded).hasSize(EXPECTED_RECORD_WIDTH);
            assertThat(encoded)
                    .as("a terminator is a record separator and never record content")
                    .doesNotContain("\n")
                    .doesNotContain("\r");
        }

        @Test
        @DisplayName("all four writable fields are left-justified and space-padded")
        void theWritableFieldsAreLeftJustifiedAndSpacePadded() {
            String encoded = UserSecurityRecordMapper.toRecord(
                    entity("USER0005", "LEE", "TING", "U"));

            assertThat(encoded.substring(EXPECTED_ID_OFFSET,
                    EXPECTED_ID_OFFSET + EXPECTED_ID_WIDTH)).isEqualTo("USER0005");
            assertThat(encoded.substring(EXPECTED_FIRST_NAME_OFFSET,
                    EXPECTED_FIRST_NAME_OFFSET + EXPECTED_FIRST_NAME_WIDTH))
                    .isEqualTo(padded("LEE", EXPECTED_FIRST_NAME_WIDTH));
            assertThat(encoded.substring(EXPECTED_LAST_NAME_OFFSET,
                    EXPECTED_LAST_NAME_OFFSET + EXPECTED_LAST_NAME_WIDTH))
                    .isEqualTo(padded("TING", EXPECTED_LAST_NAME_WIDTH));
            assertThat(encoded.substring(EXPECTED_TYPE_OFFSET,
                    EXPECTED_TYPE_OFFSET + EXPECTED_TYPE_WIDTH)).isEqualTo("U");
        }

        @Test
        @DisplayName("the credential window is eight spaces, and no fragment of the digest reaches it")
        void theCredentialWindowIsBlank() {
            String encoded = UserSecurityRecordMapper.toRecord(
                    entity("ADMIN005", "GRANVILLE", "LACHAPELLE", "A"));

            assertThat(encoded.substring(EXPECTED_CREDENTIAL_OFFSET,
                    EXPECTED_CREDENTIAL_OFFSET + EXPECTED_CREDENTIAL_WIDTH))
                    .as("the encoder declares this window as a blank run, not as a field")
                    .isEqualTo(run(' ', EXPECTED_CREDENTIAL_WIDTH));
            assertThat(encoded)
                    .as("no substring of the stored digest may appear anywhere in the image")
                    .doesNotContain("$2a$")
                    .doesNotContain(CANNED_DIGEST.substring(7, 20));
        }

        @Test
        @DisplayName("a differently digested entity produces a byte-identical image")
        void theDigestCannotInfluenceTheImage() {
            String withOneDigest = UserSecurityRecordMapper.toRecord(
                    new UserSecurity("USER0001", "LAWRENCE", "THOMAS", CANNED_DIGEST, "U"));
            String withAnother = UserSecurityRecordMapper.toRecord(
                    new UserSecurity("USER0001", "LAWRENCE", "THOMAS", OTHER_CANNED_DIGEST, "U"));

            assertThat(withAnother)
                    .as("if the digest could change the image, the image could disclose the digest")
                    .isEqualTo(withOneDigest);
        }

        @Test
        @DisplayName("the named trailing filler is twenty-three spaces")
        void theTrailingFillerIsSpaces() {
            String encoded = UserSecurityRecordMapper.toRecord(
                    entity("USER0002", "AJITH", "KUMAR", "U"));

            assertThat(encoded.substring(EXPECTED_FILLER_OFFSET))
                    .isEqualTo(run(' ', EXPECTED_FILLER_WIDTH));
        }

        @Test
        @DisplayName("the byte encoder returns the same image, in a fresh array the caller owns")
        void theByteEncoderReturnsTheSameImage() {
            UserSecurity user = entity("USER0003", "LAURITZ", "ALME", "U");

            byte[] first = UserSecurityRecordMapper.toRecordBytes(user);
            byte[] second = UserSecurityRecordMapper.toRecordBytes(user);

            assertThat(new String(first, StandardCharsets.US_ASCII))
                    .isEqualTo(UserSecurityRecordMapper.toRecord(user));
            assertThat(first)
                    .as("each call must return a fresh array the caller may mutate")
                    .isNotSameAs(second)
                    .isEqualTo(second);
        }

        @Test
        @DisplayName("an over-wide value is refused rather than truncated to fit")
        void anOverWideValueIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecord(
                            entity("ADMIN0001", "MARGARET", "GOLD", "A")))
                    .withMessageContaining("never truncated to fit");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecord(entity("ADMIN001",
                            run('X', EXPECTED_FIRST_NAME_WIDTH + 1), "GOLD", "A")))
                    .withMessageContaining("never truncated to fit");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecord(
                            entity("ADMIN001", "MARGARET", "GOLD", "AA")))
                    .withMessageContaining("never truncated to fit");
        }

        @Test
        @DisplayName("a null entity or a null attribute is refused, and the refusal names the field")
        void aNullEntityOrAttributeIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecord(null))
                    .withMessageContaining("user");
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecord(
                            entity(null, "MARGARET", "GOLD", "A")))
                    .withMessageContaining("SEC-USR-ID");
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecord(
                            entity("ADMIN001", null, "GOLD", "A")))
                    .withMessageContaining("SEC-USR-FNAME");
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecord(
                            entity("ADMIN001", "MARGARET", null, "A")))
                    .withMessageContaining("SEC-USR-LNAME");
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecordBytes(
                            entity("ADMIN001", "MARGARET", "GOLD", null)))
                    .withMessageContaining("SEC-USR-TYPE");
        }

        @Test
        @DisplayName("no refusal raised while encoding echoes the stored digest")
        void noEncodingRefusalEchoesTheDigest() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecord(
                            entity("ADMIN0001", "MARGARET", "GOLD", "A")))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .as("a width diagnostic must not carry credential material")
                            .doesNotContain(CANNED_DIGEST)
                            .doesNotContain("$2a$"));
        }
    }

    @Nested
    @DisplayName("the round trip is exact over both reproducible windows")
    class RoundTrip {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.util.UserSecurityRecordMapperCoverageTest#legacyIdentities")
        @DisplayName("the identifier and both names survive a decode and re-encode unchanged")
        void thePrefixWindowSurvivesUnchanged(final String id, final String firstName,
                final String lastName, final String type) {
            String original = legacyImage(id, firstName, lastName, type);

            String reEncoded = UserSecurityRecordMapper.toRecord(
                    UserSecurityRecordMapper.fromRecord(original, CONSTANT_DIGEST));

            assertThat(reEncoded.substring(UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_OFFSET,
                    UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_OFFSET
                            + UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_LENGTH))
                    .as("%s must survive the first forty-eight bytes byte for byte", id)
                    .isEqualTo(original.substring(0, EXPECTED_CREDENTIAL_OFFSET));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.util.UserSecurityRecordMapperCoverageTest#legacyIdentities")
        @DisplayName("the role code survives a decode and re-encode unchanged")
        void theSuffixWindowSurvivesUnchanged(final String id, final String firstName,
                final String lastName, final String type) {
            String original = legacyImage(id, firstName, lastName, type);

            String reEncoded = UserSecurityRecordMapper.toRecord(
                    UserSecurityRecordMapper.fromRecord(original, CONSTANT_DIGEST));

            assertThat(reEncoded.substring(UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_OFFSET,
                    UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_OFFSET
                            + UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_LENGTH))
                    .as("%s must keep its role code across the round trip", id)
                    .isEqualTo(type);
        }

        @Test
        @DisplayName("the credential window is the only place the re-encoded image differs")
        void theCredentialWindowIsTheOnlyDifference() {
            String original = legacyImage("ADMIN001", "MARGARET", "GOLD", "A");

            String reEncoded = UserSecurityRecordMapper.toRecord(
                    UserSecurityRecordMapper.fromRecord(original, CONSTANT_DIGEST));

            assertThat(reEncoded)
                    .as("the mapper holds nothing that could go into the credential window")
                    .isNotEqualTo(original)
                    .isEqualTo(original.substring(0, EXPECTED_CREDENTIAL_OFFSET)
                            + run(' ', EXPECTED_CREDENTIAL_WIDTH)
                            + original.substring(EXPECTED_TYPE_OFFSET));
        }

        @Test
        @DisplayName("a second round trip is a fixed point, so encoding is idempotent")
        void aSecondRoundTripIsAFixedPoint() {
            String once = UserSecurityRecordMapper.toRecord(UserSecurityRecordMapper.fromRecord(
                    legacyImage("USER0001", "LAWRENCE", "THOMAS", "U"), CONSTANT_DIGEST));

            String twice = UserSecurityRecordMapper.toRecord(
                    UserSecurityRecordMapper.fromRecord(once, CONSTANT_DIGEST));

            assertThat(twice)
                    .as("an emitted image must decode and re-encode to itself exactly")
                    .isEqualTo(once);
        }

        @Test
        @DisplayName("an emitted image hands a blank credential window to the digest function")
        void anEmittedImageCarriesABlankCredential() {
            String emitted = UserSecurityRecordMapper.toRecord(new UserSecurity(
                    "USER0002", "AJITH", "KUMAR", CANNED_DIGEST, "U"));
            RecordingDigestFunction recording = new RecordingDigestFunction(CANNED_DIGEST);

            UserSecurityRecordMapper.fromRecord(emitted, recording);

            assertThat(recording.observedArguments())
                    .as("re-reading an emitted record cannot recover a credential that was never written")
                    .containsExactly(run(' ', EXPECTED_CREDENTIAL_WIDTH));
        }
    }

    @Nested
    @DisplayName("the ten identities the provisioning job writes")
    class LegacyIdentities {

        @ParameterizedTest(name = "{0} is {1} {2} of type {3}")
        @MethodSource("com.carddemo.util.UserSecurityRecordMapperCoverageTest#legacyIdentities")
        @DisplayName("each card image decodes to the identity transcribed from the job stream")
        void eachCardDecodesToItsTranscribedIdentity(final String id, final String firstName,
                final String lastName, final String type) {
            UserSecurity decoded = UserSecurityRecordMapper.fromRecord(
                    legacyImage(id, firstName, lastName, type), CONSTANT_DIGEST);

            assertThat(decoded.getSecUsrId()).isEqualTo(id);
            assertThat(decoded.getSecUsrFname())
                    .isEqualTo(padded(firstName, EXPECTED_FIRST_NAME_WIDTH));
            assertThat(decoded.getSecUsrLname())
                    .isEqualTo(padded(lastName, EXPECTED_LAST_NAME_WIDTH));
            assertThat(decoded.getSecUsrType()).isEqualTo(type);
        }

        @Test
        @DisplayName("there are ten of them, split five administrators to five standard users")
        void thereAreTenSplitFiveAndFive() {
            List<UserSecurity> decoded = legacyIdentities()
                    .map(arguments -> UserSecurityRecordMapper.fromRecord(
                            legacyImage((String) arguments.get()[0], (String) arguments.get()[1],
                                    (String) arguments.get()[2], (String) arguments.get()[3]),
                            CONSTANT_DIGEST))
                    .toList();

            assertThat(decoded).hasSize(EXPECTED_IDENTITIES);
            assertThat(decoded.stream().filter(user -> "A".equals(user.getSecUsrType())).count())
                    .as("five administrator identities")
                    .isEqualTo(EXPECTED_ADMINISTRATORS);
            assertThat(decoded.stream().filter(user -> "U".equals(user.getSecUsrType())).count())
                    .as("five standard identities")
                    .isEqualTo(EXPECTED_IDENTITIES - EXPECTED_ADMINISTRATORS);
        }

        @Test
        @DisplayName("their identifiers are distinct, so the primary key identifies a record")
        void theirIdentifiersAreDistinct() {
            List<String> identifiers = legacyIdentities()
                    .map(arguments -> (String) arguments.get()[0])
                    .toList();

            assertThat(identifiers)
                    .hasSize(EXPECTED_IDENTITIES)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("each card is exactly the mapped length, and the filler carries it to eighty")
        void eachCardIsExactlyTheMappedLength() {
            legacyIdentities().forEach(arguments -> {
                String card = padded((String) arguments.get()[0], EXPECTED_ID_WIDTH)
                        + padded((String) arguments.get()[1], EXPECTED_FIRST_NAME_WIDTH)
                        + padded((String) arguments.get()[2], EXPECTED_LAST_NAME_WIDTH)
                        + LEGACY_CREDENTIAL
                        + arguments.get()[3];

                assertThat(card)
                        .as("the job stream's card is the mapped prefix and nothing more")
                        .hasSize(EXPECTED_MAPPED_WIDTH);
            });
        }

        @Test
        @DisplayName("all ten share one credential, which is why hashing each one separately matters")
        void allTenShareOneCredential() {
            RecordingDigestFunction recording = new RecordingDigestFunction(CANNED_DIGEST);

            legacyIdentities().forEach(arguments -> UserSecurityRecordMapper.fromRecord(
                    legacyImage((String) arguments.get()[0], (String) arguments.get()[1],
                            (String) arguments.get()[2], (String) arguments.get()[3]),
                    recording));

            assertThat(recording.observedArguments())
                    .as("ten records must produce ten hashing calls, one per record")
                    .hasSize(EXPECTED_IDENTITIES)
                    .containsOnly(LEGACY_CREDENTIAL);
        }
    }
}
