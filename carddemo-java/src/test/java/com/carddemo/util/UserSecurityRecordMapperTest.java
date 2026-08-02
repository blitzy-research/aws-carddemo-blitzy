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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.carddemo.domain.UserSecurity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Verifies {@link UserSecurityRecordMapper} against constructed 80-byte {@code SEC-USER-DATA}
 * images.
 *
 * <p>Unlike every other record layout in the estate, user security ships <strong>no ASCII
 * fixture</strong>: the nine files under {@code app/data/ASCII} cover accounts, cards, the card
 * cross-reference, customers, daily transactions, disclosure groups, category balances, transaction
 * categories and transaction types, and the credential dataset exists only as EBCDIC. Its content is
 * nevertheless fully recoverable without decoding anything, because the provisioning job carries all
 * ten records in-stream as readable card images. The record images this test builds are therefore
 * reconstructions of those cards - five administrator records and five standard-user records, each
 * an eight-character identifier, a first and last name, the literal password the job supplies, and a
 * one-character type - assembled here rather than read from a file.</p>
 *
 * <p>Two properties of this mapper have no counterpart elsewhere and are the reason it needs its own
 * tests rather than sharing another mapper's. First, the credential window at {@code [48, 56)} is
 * one-way: on the way in the mapper hands those eight bytes to a caller-supplied digest function and
 * stores only what comes back, and on the way out it writes the window <em>blank</em>, because it
 * never reads the stored digest and so holds nothing that could reach those bytes. An emitted record
 * is consequently <strong>not</strong> byte-identical to the legacy record it came from, and that is
 * deliberate rather than a defect. Second, because a whole-record comparison could therefore never
 * hold, the mapper publishes the two windows that <em>are</em> reproducible - the prefix before the
 * credential window and the type byte after it - and this test rounds trips exactly those.</p>
 */
@DisplayName("UserSecurityRecordMapper - the 80-byte CSUSR01Y record layout")
class UserSecurityRecordMapperTest {

    /**
     * A BCrypt digest that satisfies every rule the entity enforces: sixty characters, a recognised
     * version marker, a cost at or above the module minimum, and a tail drawn entirely from the
     * BCrypt radix-64 alphabet. It is a shape-valid literal rather than a hash of anything, which is
     * exactly what a mapper test needs - the mapper's contract is that it stores whatever the digest
     * function returns, not that it can hash.
     */
    private static final String VALID_DIGEST =
            "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXY01";

    /** A second shape-valid digest, used to prove a replacement actually replaces. */
    private static final String OTHER_VALID_DIGEST =
            "$2a$14$ZYXWVUTSRQPONMLKJIHGFEDCBAzyxwvutsrqponmlkjihgfedcba9";

    /** The cleartext credential the provisioning job carries for all ten seed records. */
    private static final String SEED_CREDENTIAL = "PASSWORD";

    /** Stands in for the caller's one-way digest function. */
    private static final UnaryOperator<String> DIGEST_FUNCTION = cleartext -> VALID_DIGEST;

    private static String spaces(int count) {
        return " ".repeat(count);
    }

    /**
     * Assembles one 80-byte record image the way the provisioning job's card images are laid out.
     *
     * @param identifier the eight-byte user identifier
     * @param firstName  the first name, space-padded to twenty bytes
     * @param lastName   the last name, space-padded to twenty bytes
     * @param credential the eight-byte cleartext credential
     * @param type       the one-byte user type
     * @return the complete 80-byte image, trailing filler included
     */
    private static String image(String identifier, String firstName, String lastName,
            String credential, String type) {
        StringBuilder builder = new StringBuilder(UserSecurityRecordMapper.RECORD_LENGTH);
        builder.append(pad(identifier, UserSecurityRecordMapper.SEC_USR_ID_LENGTH));
        builder.append(pad(firstName, UserSecurityRecordMapper.SEC_USR_FNAME_LENGTH));
        builder.append(pad(lastName, UserSecurityRecordMapper.SEC_USR_LNAME_LENGTH));
        builder.append(pad(credential, UserSecurityRecordMapper.SEC_USR_PWD_LENGTH));
        builder.append(pad(type, UserSecurityRecordMapper.SEC_USR_TYPE_LENGTH));
        builder.append(spaces(UserSecurityRecordMapper.SEC_USR_FILLER_LENGTH));
        return builder.toString();
    }

    private static String pad(String value, int width) {
        return value + spaces(width - value.length());
    }

    /** The first administrator record the provisioning job carries. */
    private static String administratorImage() {
        return image("ADMIN001", "MARGARET", "GOLD", SEED_CREDENTIAL, "A");
    }

    /** The ten seed records, in the order the provisioning job writes them. */
    private static List<String> seedImages() {
        List<String> images = new ArrayList<>();
        images.add(image("ADMIN001", "MARGARET", "GOLD", SEED_CREDENTIAL, "A"));
        images.add(image("ADMIN002", "RUSSELL", "RUSSELL", SEED_CREDENTIAL, "A"));
        images.add(image("ADMIN003", "RAYMOND", "WHITMORE", SEED_CREDENTIAL, "A"));
        images.add(image("ADMIN004", "EMMANUEL", "CASGRAIN", SEED_CREDENTIAL, "A"));
        images.add(image("ADMIN005", "GRANVILLE", "LACHAPELLE", SEED_CREDENTIAL, "A"));
        images.add(image("USER0001", "LAWRENCE", "THOMAS", SEED_CREDENTIAL, "U"));
        images.add(image("USER0002", "AJITH", "KUMAR", SEED_CREDENTIAL, "U"));
        images.add(image("USER0003", "LAURITZ", "ALME", SEED_CREDENTIAL, "U"));
        images.add(image("USER0004", "AVERARDO", "MAZZI", SEED_CREDENTIAL, "U"));
        images.add(image("USER0005", "LEE", "TING", SEED_CREDENTIAL, "U"));
        return images;
    }

    @Nested
    @DisplayName("the declared geometry")
    class DeclaredGeometry {

        @Test
        @DisplayName("every field offset is the sum of the widths that precede it, so the mapped "
                + "prefix tiles the record with no gap and no overlap")
        void offsetsTileTheMappedPrefix() {
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
            assertThat(UserSecurityRecordMapper.SEC_USR_TYPE_OFFSET
                    + UserSecurityRecordMapper.SEC_USR_TYPE_LENGTH)
                    .isEqualTo(UserSecurityRecordMapper.MAPPED_LENGTH);
        }

        @Test
        @DisplayName("the offsets and widths are the CSUSR01Y offsets and widths, byte for byte")
        void widthsAreTheCopybookWidths() {
            assertThat(UserSecurityRecordMapper.SEC_USR_ID_OFFSET).isEqualTo(0);
            assertThat(UserSecurityRecordMapper.SEC_USR_ID_LENGTH).isEqualTo(8);
            assertThat(UserSecurityRecordMapper.SEC_USR_FNAME_OFFSET).isEqualTo(8);
            assertThat(UserSecurityRecordMapper.SEC_USR_FNAME_LENGTH).isEqualTo(20);
            assertThat(UserSecurityRecordMapper.SEC_USR_LNAME_OFFSET).isEqualTo(28);
            assertThat(UserSecurityRecordMapper.SEC_USR_LNAME_LENGTH).isEqualTo(20);
            assertThat(UserSecurityRecordMapper.SEC_USR_PWD_OFFSET).isEqualTo(48);
            assertThat(UserSecurityRecordMapper.SEC_USR_PWD_LENGTH).isEqualTo(8);
            assertThat(UserSecurityRecordMapper.SEC_USR_TYPE_OFFSET).isEqualTo(56);
            assertThat(UserSecurityRecordMapper.SEC_USR_TYPE_LENGTH).isEqualTo(1);
            assertThat(UserSecurityRecordMapper.MAPPED_LENGTH).isEqualTo(57);
        }

        @Test
        @DisplayName("the filler occupies the record tail, so the mapped data and the filler "
                + "together account for all 80 bytes")
        void fillerOccupiesTheRecordTail() {
            assertThat(UserSecurityRecordMapper.SEC_USR_FILLER_OFFSET)
                    .isEqualTo(UserSecurityRecordMapper.MAPPED_LENGTH)
                    .isEqualTo(57);
            assertThat(UserSecurityRecordMapper.SEC_USR_FILLER_LENGTH).isEqualTo(23);
            assertThat(UserSecurityRecordMapper.SEC_USR_FILLER_OFFSET
                    + UserSecurityRecordMapper.SEC_USR_FILLER_LENGTH)
                    .isEqualTo(UserSecurityRecordMapper.RECORD_LENGTH);
            assertThat(UserSecurityRecordMapper.RECORD_LENGTH).isEqualTo(80);
        }

        @Test
        @DisplayName("the two reproducible windows bracket the credential window exactly, which is "
                + "what makes a bounded round trip possible at all")
        void theReproducibleWindowsBracketTheCredentialWindow() {
            assertThat(UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_OFFSET)
                    .as("the reproducible prefix starts at the record start")
                    .isEqualTo(UserSecurityRecordMapper.SEC_USR_ID_OFFSET)
                    .isZero();
            assertThat(UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_LENGTH)
                    .as("the reproducible prefix ends exactly where the credential window begins")
                    .isEqualTo(UserSecurityRecordMapper.SEC_USR_PWD_OFFSET)
                    .isEqualTo(48);
            assertThat(UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_OFFSET)
                    .as("the reproducible suffix starts exactly where the credential window ends")
                    .isEqualTo(UserSecurityRecordMapper.SEC_USR_PWD_OFFSET
                            + UserSecurityRecordMapper.SEC_USR_PWD_LENGTH)
                    .isEqualTo(56);
            assertThat(UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_LENGTH)
                    .isEqualTo(UserSecurityRecordMapper.SEC_USR_TYPE_LENGTH)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the artefact and copybook are named, so a diagnostic identifies the layout it "
                + "was reading")
        void theArtefactAndCopybookAreNamed() {
            assertThat(UserSecurityRecordMapper.ARTEFACT).isEqualTo("SEC-USER-DATA");
            assertThat(UserSecurityRecordMapper.COPYBOOK).isEqualTo("CSUSR01Y");
        }
    }

    @Nested
    @DisplayName("parsing a constructed record")
    class ParsingAConstructedRecord {

        @Test
        @DisplayName("every field of the first administrator record equals the slice the image "
                + "carries at that offset")
        void everyFieldEqualsItsSlice() {
            String record = administratorImage();

            UserSecurity user = UserSecurityRecordMapper.fromRecord(record, DIGEST_FUNCTION);

            assertThat(record).hasSize(UserSecurityRecordMapper.RECORD_LENGTH);
            assertThat(user.getSecUsrId()).isEqualTo(record.substring(0, 8)).isEqualTo("ADMIN001");
            assertThat(user.getSecUsrFname()).isEqualTo(record.substring(8, 28));
            assertThat(user.getSecUsrLname()).isEqualTo(record.substring(28, 48));
            assertThat(user.getSecUsrType()).isEqualTo(record.substring(56, 57)).isEqualTo("A");
        }

        @Test
        @DisplayName("a display name keeps the trailing blanks the record carries, because the "
                + "mapper reads a fixed-width slice and never trims it")
        void displayNamesKeepTheirTrailingBlanks() {
            UserSecurity user = UserSecurityRecordMapper.fromRecord(administratorImage(),
                    DIGEST_FUNCTION);

            assertThat(user.getSecUsrFname())
                    .hasSize(UserSecurityRecordMapper.SEC_USR_FNAME_LENGTH)
                    .isEqualTo("MARGARET" + spaces(12));
            assertThat(user.getSecUsrLname())
                    .hasSize(UserSecurityRecordMapper.SEC_USR_LNAME_LENGTH)
                    .isEqualTo("GOLD" + spaces(16));
        }

        @Test
        @DisplayName("the digest function receives the credential window's eight bytes exactly once, "
                + "and receives no other field")
        void theDigestFunctionReceivesOnlyTheCredentialWindow() {
            String record = administratorImage();
            List<String> handedOver = new ArrayList<>();
            UnaryOperator<String> recordingFunction = cleartext -> {
                handedOver.add(cleartext);
                return VALID_DIGEST;
            };

            UserSecurityRecordMapper.fromRecord(record, recordingFunction);

            assertThat(handedOver).containsExactly(SEED_CREDENTIAL);
            assertThat(handedOver.get(0))
                    .hasSize(UserSecurityRecordMapper.SEC_USR_PWD_LENGTH)
                    .isEqualTo(record.substring(48, 56));
        }

        @Test
        @DisplayName("the entity holds the digest the function returned and never the cleartext the "
                + "record carried")
        void theEntityHoldsTheDigestRatherThanTheCleartext() {
            UserSecurity user = UserSecurityRecordMapper.fromRecord(administratorImage(),
                    DIGEST_FUNCTION);

            assertThat(user.credentialDigest())
                    .isEqualTo(VALID_DIGEST)
                    .isNotEqualTo(SEED_CREDENTIAL)
                    .doesNotContain(SEED_CREDENTIAL);
        }

        @Test
        @DisplayName("all ten provisioning records parse, and the five administrator records carry "
                + "the administrator type while the five user records carry the user type")
        void allTenProvisioningRecordsParse() {
            List<String> identifiers = new ArrayList<>();
            List<String> types = new ArrayList<>();

            for (String record : seedImages()) {
                UserSecurity user = UserSecurityRecordMapper.fromRecord(record, DIGEST_FUNCTION);
                identifiers.add(user.getSecUsrId());
                types.add(user.getSecUsrType());
            }

            assertThat(identifiers).hasSize(10).doesNotHaveDuplicates()
                    .startsWith("ADMIN001").endsWith("USER0005");
            assertThat(types).containsExactly("A", "A", "A", "A", "A", "U", "U", "U", "U", "U");
        }

        @Test
        @DisplayName("the byte-array and byte-range entry points agree with the String entry point")
        void everyEntryPointAgrees() {
            String record = administratorImage();
            byte[] bytes = record.getBytes(StandardCharsets.US_ASCII);
            byte[] offsetBuffer = new byte[bytes.length + 5];
            System.arraycopy(bytes, 0, offsetBuffer, 5, bytes.length);

            UserSecurity fromString = UserSecurityRecordMapper.fromRecord(record, DIGEST_FUNCTION);
            UserSecurity fromBytes = UserSecurityRecordMapper.fromRecord(bytes, DIGEST_FUNCTION);
            UserSecurity fromRange = UserSecurityRecordMapper.fromRecord(offsetBuffer, 5,
                    DIGEST_FUNCTION);

            assertThat(fromBytes.getSecUsrId()).isEqualTo(fromString.getSecUsrId());
            assertThat(fromBytes.getSecUsrFname()).isEqualTo(fromString.getSecUsrFname());
            assertThat(fromRange.getSecUsrId()).isEqualTo(fromString.getSecUsrId());
            assertThat(fromRange.getSecUsrLname()).isEqualTo(fromString.getSecUsrLname());
            assertThat(fromRange.getSecUsrType()).isEqualTo(fromString.getSecUsrType());
        }
    }

    @Nested
    @DisplayName("emitting a record")
    class EmittingARecord {

        @Test
        @DisplayName("the emitted image measures the declared eighty bytes")
        void theEmittedImageMeasuresTheDeclaredWidth() {
            UserSecurity user = UserSecurityRecordMapper.fromRecord(administratorImage(),
                    DIGEST_FUNCTION);

            assertThat(UserSecurityRecordMapper.toRecord(user))
                    .hasSize(UserSecurityRecordMapper.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the credential window is emitted blank, carrying neither the digest nor the "
                + "cleartext, because this mapper never reads the stored digest")
        void theCredentialWindowIsEmittedBlank() {
            UserSecurity user = UserSecurityRecordMapper.fromRecord(administratorImage(),
                    DIGEST_FUNCTION);

            String emitted = UserSecurityRecordMapper.toRecord(user);

            assertThat(emitted.substring(UserSecurityRecordMapper.SEC_USR_PWD_OFFSET,
                    UserSecurityRecordMapper.SEC_USR_PWD_OFFSET
                            + UserSecurityRecordMapper.SEC_USR_PWD_LENGTH))
                    .isEqualTo(spaces(UserSecurityRecordMapper.SEC_USR_PWD_LENGTH));
            assertThat(emitted)
                    .doesNotContain(SEED_CREDENTIAL)
                    .doesNotContain(VALID_DIGEST)
                    .doesNotContain(VALID_DIGEST.substring(0, 12));
        }

        @Test
        @DisplayName("an emitted record is deliberately not byte-identical to the record it came "
                + "from, and differs only inside the credential window")
        void anEmittedRecordDiffersOnlyInsideTheCredentialWindow() {
            String original = administratorImage();
            UserSecurity user = UserSecurityRecordMapper.fromRecord(original, DIGEST_FUNCTION);

            String emitted = UserSecurityRecordMapper.toRecord(user);

            assertThat(emitted).isNotEqualTo(original);
            for (int index = 0; index < UserSecurityRecordMapper.RECORD_LENGTH; index++) {
                boolean insideCredentialWindow =
                        index >= UserSecurityRecordMapper.SEC_USR_PWD_OFFSET
                                && index < UserSecurityRecordMapper.SEC_USR_PWD_OFFSET
                                        + UserSecurityRecordMapper.SEC_USR_PWD_LENGTH;
                if (!insideCredentialWindow) {
                    assertThat(emitted.charAt(index))
                            .as("byte %d sits outside the credential window and must be reproduced",
                                    index)
                            .isEqualTo(original.charAt(index));
                }
            }
        }

        @Test
        @DisplayName("both published reproducible windows round trip exactly, for all ten "
                + "provisioning records")
        void bothReproducibleWindowsRoundTrip() {
            for (String original : seedImages()) {
                UserSecurity user = UserSecurityRecordMapper.fromRecord(original, DIGEST_FUNCTION);

                String emitted = UserSecurityRecordMapper.toRecord(user);

                int prefixEnd = UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_OFFSET
                        + UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_LENGTH;
                int suffixEnd = UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_OFFSET
                        + UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_LENGTH;
                assertThat(emitted.substring(
                        UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_OFFSET, prefixEnd))
                        .as("the reproducible prefix of %s", original.substring(0, 8))
                        .isEqualTo(original.substring(
                                UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_OFFSET, prefixEnd));
                assertThat(emitted.substring(
                        UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_OFFSET, suffixEnd))
                        .as("the reproducible suffix of %s", original.substring(0, 8))
                        .isEqualTo(original.substring(
                                UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_OFFSET, suffixEnd));
            }
        }

        @Test
        @DisplayName("the trailing filler is emitted as spaces, matching the module default")
        void theTrailingFillerIsEmittedAsSpaces() {
            UserSecurity user = UserSecurityRecordMapper.fromRecord(administratorImage(),
                    DIGEST_FUNCTION);

            String emitted = UserSecurityRecordMapper.toRecord(user);

            assertThat(emitted.substring(UserSecurityRecordMapper.SEC_USR_FILLER_OFFSET))
                    .isEqualTo(spaces(UserSecurityRecordMapper.SEC_USR_FILLER_LENGTH));
        }

        @Test
        @DisplayName("the byte-emitting entry point agrees with the String one")
        void theByteEmittingEntryPointAgrees() {
            UserSecurity user = UserSecurityRecordMapper.fromRecord(administratorImage(),
                    DIGEST_FUNCTION);

            byte[] emitted = UserSecurityRecordMapper.toRecordBytes(user);

            assertThat(emitted).hasSize(UserSecurityRecordMapper.RECORD_LENGTH);
            assertThat(new String(emitted, StandardCharsets.US_ASCII))
                    .isEqualTo(UserSecurityRecordMapper.toRecord(user));
        }
    }

    @Nested
    @DisplayName("the credential guard")
    class TheCredentialGuard {

        @Test
        @DisplayName("a digest function that returns nothing is rejected, naming the field, because "
                + "the mapper has no unhashed path to substitute")
        void aDigestFunctionThatReturnsNothingIsRejected() {
            String record = administratorImage();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(record,
                            cleartext -> null))
                    .withMessageContaining("SEC-USR-PWD")
                    .withMessageContaining("credentialDigestFunction");
        }

        @Test
        @DisplayName("a digest function that returns the cleartext is rejected by the entity, so "
                + "the record's plaintext credential can never be stored")
        void aDigestFunctionThatReturnsCleartextIsRejected() {
            String record = administratorImage();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(record,
                            cleartext -> cleartext));
        }

        @Test
        @DisplayName("a digest carrying an unrecognised version marker is rejected even at the "
                + "correct length")
        void anUnrecognisedVersionMarkerIsRejected() {
            String wrongMarker = "$2x$" + VALID_DIGEST.substring(4);
            String record = administratorImage();

            assertThat(wrongMarker).hasSameSizeAs(VALID_DIGEST);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(record,
                            cleartext -> wrongMarker));
        }

        @Test
        @DisplayName("a digest whose cost is below the module minimum is rejected")
        void aCostBelowTheMinimumIsRejected() {
            String weakCost = "$2b$09$" + VALID_DIGEST.substring(7);
            String record = administratorImage();

            assertThat(weakCost).hasSameSizeAs(VALID_DIGEST);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(record,
                            cleartext -> weakCost));
        }

        @Test
        @DisplayName("a digest carrying a character outside the BCrypt radix-64 alphabet is rejected")
        void aCharacterOutsideTheAlphabetIsRejected() {
            String outsideAlphabet = VALID_DIGEST.substring(0, 10) + "!"
                    + VALID_DIGEST.substring(11);
            String record = administratorImage();

            assertThat(outsideAlphabet).hasSameSizeAs(VALID_DIGEST);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(record,
                            cleartext -> outsideAlphabet));
        }

        @Test
        @DisplayName("the digest is reachable and replaceable through the two accessors that sit "
                + "outside the JavaBean convention, which is what keeps it off the bean surface")
        void theDigestIsReachableAndReplaceableOutsideTheBeanConvention() {
            UserSecurity user = UserSecurityRecordMapper.fromRecord(administratorImage(),
                    DIGEST_FUNCTION);
            assertThat(user.credentialDigest()).isEqualTo(VALID_DIGEST);

            user.replaceCredentialDigest(OTHER_VALID_DIGEST);

            assertThat(user.credentialDigest()).isEqualTo(OTHER_VALID_DIGEST);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> user.replaceCredentialDigest(SEED_CREDENTIAL));
            assertThat(user.credentialDigest())
                    .as("a rejected replacement must leave the held digest untouched")
                    .isEqualTo(OTHER_VALID_DIGEST);
        }
    }

    @Nested
    @DisplayName("rejecting malformed input")
    class RejectingMalformedInput {

        @Test
        @DisplayName("an image shorter than the declared record length is rejected, and the message "
                + "names the length the layout requires")
        void aShortImageIsRejected() {
            String shortImage = "A".repeat(UserSecurityRecordMapper.RECORD_LENGTH - 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(shortImage,
                            DIGEST_FUNCTION))
                    .withMessageContaining(String.valueOf(UserSecurityRecordMapper.RECORD_LENGTH));
        }

        @Test
        @DisplayName("an image longer than the declared record length is rejected rather than "
                + "silently truncated")
        void aLongImageIsRejected() {
            String longImage = "A".repeat(UserSecurityRecordMapper.RECORD_LENGTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(longImage,
                            DIGEST_FUNCTION))
                    .withMessageContaining(String.valueOf(UserSecurityRecordMapper.RECORD_LENGTH));
        }

        @Test
        @DisplayName("an empty image is rejected")
        void anEmptyImageIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord("", DIGEST_FUNCTION))
                    .withMessageContaining(String.valueOf(UserSecurityRecordMapper.RECORD_LENGTH));
        }

        @Test
        @DisplayName("a null image, a null digest function and a null entity are each rejected by "
                + "name")
        void nullArgumentsAreRejectedByName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord((String) null,
                            DIGEST_FUNCTION))
                    .withMessageContaining("recordImage");
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord((byte[]) null,
                            DIGEST_FUNCTION))
                    .withMessageContaining("recordImage");
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(administratorImage(),
                            null))
                    .withMessageContaining("credentialDigestFunction");
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecord(null))
                    .withMessageContaining("user");
        }

        @Test
        @DisplayName("a byte range that runs past the end of its buffer is rejected")
        void aByteRangePastTheBufferEndIsRejected() {
            byte[] bytes = administratorImage().getBytes(StandardCharsets.US_ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(bytes, 1,
                            DIGEST_FUNCTION));
        }
    }
}
