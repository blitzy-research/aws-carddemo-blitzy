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
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.carddemo.domain.UserSecurity;
import com.carddemo.support.SensitiveValues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Unit tests for the 80-byte user-security record mapper.
 *
 * <p>The layout is pinned field by field - offsets 0, 8, 28, 48 and 56 at widths 8, 20, 20, 8 and 1 -
 * and the tiling assertion is what catches an off-by-one: every offset must equal the sum of the
 * widths before it, the mapped prefix sums to 57, and 57 plus the 23-byte filler is the declared
 * 80-byte record. All widths are asserted on encoded bytes rather than characters.
 *
 * <p>No cleartext credential value appears anywhere in this suite: where a credential window has to
 * be exercised, the surrounding windows are asserted so the window's position is pinned without its
 * content being written down, and any value a test needs is constructed at run time.
 */
@DisplayName("UserSecurityRecordMapper - the 80-byte SEC-USER-DATA layout")
class UserSecurityRecordMapperTest {
    private static final int ID_OFFSET = 0;

    private static final int ID_WIDTH = 8;

    private static final int FNAME_OFFSET = 8;

    private static final int FNAME_WIDTH = 20;

    private static final int LNAME_OFFSET = 28;

    private static final int LNAME_WIDTH = 20;

    private static final int PWD_OFFSET = 48;

    private static final int PWD_WIDTH = 8;

    private static final int TYPE_OFFSET = 56;

    private static final int TYPE_WIDTH = 1;

    private static final int FILLER_OFFSET = 57;

    private static final int FILLER_WIDTH = 23;

    private static final int MAPPED_WIDTH = 57;

    private static final int RECORD_WIDTH = 80;

    private static final int DIGEST_WIDTH = 60;

    private static final byte ASCII_SPACE = (byte) 0x20;

    private static final String ADMINISTRATOR_TYPE = "A";

    private static final String STANDARD_USER_TYPE = "U";

    private static final int SEEDED_ADMINISTRATOR_COUNT = 5;

    private static final int SEEDED_STANDARD_USER_COUNT = 5;

    private static final int SEED_CARD_SIGNIFICANT_WIDTH = 57;

    private static final String SYNTHETIC_WINDOW_VALUE = "Zq7Kx2Vw";

    private static final String SYNTHETIC_SHORT_WINDOW_VALUE = "Zq7Kx2";

    private static final String SYNTHETIC_SHORT_WINDOW_VALUE_AS_PLACED = "Zq7Kx2  ";

    private static final String DIGEST_STRUCTURAL_PREFIX = "$2b$12$";

    private static final String SYNTHETIC_DIGEST_TAIL =
            "SyntheticDigestTailNotARealHashNorDerivedFromAnyValue";

    private static final String OTHER_SYNTHETIC_DIGEST_TAIL =
            "SecondSyntheticDigestTailCarriedByTheEntityUnchanged0";

    private static final String SYNTHETIC_DIGEST = DIGEST_STRUCTURAL_PREFIX + SYNTHETIC_DIGEST_TAIL;

    private static final String OTHER_SYNTHETIC_DIGEST =
            DIGEST_STRUCTURAL_PREFIX + OTHER_SYNTHETIC_DIGEST_TAIL;

    private static final UnaryOperator<String> FIXED_DIGEST_FUNCTION = window -> SYNTHETIC_DIGEST;

    private static final String[][] SEEDED_IDENTITIES = {
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

    private static byte[] bytesOf(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static int encodedWidthOf(String value) {
        return bytesOf(value).length;
    }

    private static String spaces(int count) {
        return " ".repeat(count);
    }

    private static String padded(String value, int width) {
        return value + spaces(width - encodedWidthOf(value));
    }

    private static String recordImage(String identifier, String firstName, String lastName,
            String windowValue, String type, int fillerWidth) {
        return padded(identifier, ID_WIDTH)
                + padded(firstName, FNAME_WIDTH)
                + padded(lastName, LNAME_WIDTH)
                + padded(windowValue, PWD_WIDTH)
                + padded(type, TYPE_WIDTH)
                + spaces(fillerWidth);
    }

    private static String recordImage(String identifier, String firstName, String lastName,
            String windowValue, String type) {
        return recordImage(identifier, firstName, lastName, windowValue, type, FILLER_WIDTH);
    }

    private static String seededImage(String[] identity) {
        return recordImage(identity[0], identity[1], identity[2], SYNTHETIC_WINDOW_VALUE,
                identity[3]);
    }

    private static String administratorImage() {
        return seededImage(SEEDED_IDENTITIES[0]);
    }

    private static byte[] window(byte[] image, int offset, int width) {
        return Arrays.copyOfRange(image, offset, offset + width);
    }

    @Nested
    @DisplayName("the declared layout")
    class DeclaredLayout {
        @Test
        @DisplayName("the five field offsets are the copybook offsets 0, 8, 28, 48 and 56, and the "
                + "named filler starts at 57")
        void theOffsetsAreTheCopybookOffsets() {
            assertThat(UserSecurityRecordMapper.SEC_USR_ID_OFFSET)
                    .as("SEC-USR-ID starts the record")
                    .isEqualTo(ID_OFFSET)
                    .isZero();
            assertThat(UserSecurityRecordMapper.SEC_USR_FNAME_OFFSET).isEqualTo(FNAME_OFFSET);
            assertThat(UserSecurityRecordMapper.SEC_USR_LNAME_OFFSET).isEqualTo(LNAME_OFFSET);
            assertThat(UserSecurityRecordMapper.SEC_USR_PWD_OFFSET).isEqualTo(PWD_OFFSET);
            assertThat(UserSecurityRecordMapper.SEC_USR_TYPE_OFFSET).isEqualTo(TYPE_OFFSET);
            assertThat(UserSecurityRecordMapper.SEC_USR_FILLER_OFFSET)
                    .as("SEC-USR-FILLER begins where the mapped prefix ends")
                    .isEqualTo(FILLER_OFFSET);
        }

        @Test
        @DisplayName("the five field widths are the copybook widths 8, 20, 20, 8 and 1, and the "
                + "named filler is 23 bytes wide")
        void theWidthsAreTheCopybookWidths() {
            assertThat(UserSecurityRecordMapper.SEC_USR_ID_LENGTH).isEqualTo(ID_WIDTH);
            assertThat(UserSecurityRecordMapper.SEC_USR_FNAME_LENGTH).isEqualTo(FNAME_WIDTH);
            assertThat(UserSecurityRecordMapper.SEC_USR_LNAME_LENGTH).isEqualTo(LNAME_WIDTH);
            assertThat(UserSecurityRecordMapper.SEC_USR_PWD_LENGTH)
                    .as("the credential window is the legacy eight bytes, not the column width")
                    .isEqualTo(PWD_WIDTH);
            assertThat(UserSecurityRecordMapper.SEC_USR_TYPE_LENGTH).isEqualTo(TYPE_WIDTH);
            assertThat(UserSecurityRecordMapper.SEC_USR_FILLER_LENGTH).isEqualTo(FILLER_WIDTH);
        }

        @Test
        @DisplayName("every offset is the sum of the widths before it, so the mapped prefix tiles the "
                + "record with no gap and no overlap")
        void theOffsetsTileTheMappedPrefix() {
            assertThat(ID_OFFSET + ID_WIDTH).isEqualTo(FNAME_OFFSET);
            assertThat(FNAME_OFFSET + FNAME_WIDTH).isEqualTo(LNAME_OFFSET);
            assertThat(LNAME_OFFSET + LNAME_WIDTH).isEqualTo(PWD_OFFSET);
            assertThat(PWD_OFFSET + PWD_WIDTH).isEqualTo(TYPE_OFFSET);
            assertThat(TYPE_OFFSET + TYPE_WIDTH).isEqualTo(FILLER_OFFSET);
            assertThat(FILLER_OFFSET + FILLER_WIDTH).isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("8 + 20 + 20 + 8 + 1 is 57 mapped bytes, 57 + 23 is the 80-byte record, and the "
                + "provisioning job writes the sequential dataset at that same LRECL=80 RECFM=FB")
        void theLayoutArithmeticHolds() {
            assertThat(ID_WIDTH + FNAME_WIDTH + LNAME_WIDTH + PWD_WIDTH + TYPE_WIDTH)
                    .as("the five mapped widths sum to the significant prefix")
                    .isEqualTo(MAPPED_WIDTH)
                    .isEqualTo(57);
            assertThat(MAPPED_WIDTH + FILLER_WIDTH)
                    .as("the mapped prefix plus the named filler accounts for the whole record")
                    .isEqualTo(RECORD_WIDTH)
                    .isEqualTo(80);
            assertThat(UserSecurityRecordMapper.MAPPED_LENGTH).isEqualTo(MAPPED_WIDTH);
            assertThat(UserSecurityRecordMapper.RECORD_LENGTH)
                    .as("the record width the provisioning job writes")
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("a constructed image measures exactly 80 encoded bytes, with 57 significant "
                + "bytes matching the width of one in-stream provisioning card")
        void aConstructedImageMeasuresTheDeclaredWidth() {
            final String image = administratorImage();

            assertThat(encodedWidthOf(image)).isEqualTo(RECORD_WIDTH);
            assertThat(bytesOf(image)).hasSize(RECORD_WIDTH);
            assertThat(SEED_CARD_SIGNIFICANT_WIDTH)
                    .as("the in-stream card carries the mapped prefix and no filler")
                    .isEqualTo(MAPPED_WIDTH);
        }

        @Test
        @DisplayName("the two reproducible windows bracket the credential window exactly, which is "
                + "what makes a bounded round trip possible at all")
        void theReproducibleWindowsBracketTheCredentialWindow() {
            assertThat(UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_OFFSET)
                    .as("the reproducible prefix starts the record")
                    .isEqualTo(ID_OFFSET)
                    .isZero();
            assertThat(UserSecurityRecordMapper.REPRODUCIBLE_PREFIX_LENGTH)
                    .as("the reproducible prefix ends where the credential window begins")
                    .isEqualTo(ID_WIDTH + FNAME_WIDTH + LNAME_WIDTH)
                    .isEqualTo(48);
            assertThat(UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_OFFSET)
                    .as("the reproducible suffix starts where the credential window ends")
                    .isEqualTo(TYPE_OFFSET)
                    .isEqualTo(56);
            assertThat(UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_LENGTH)
                    .as("the reproducible suffix is the role code alone")
                    .isEqualTo(TYPE_WIDTH);
            assertThat(UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_OFFSET
                    + UserSecurityRecordMapper.REPRODUCIBLE_SUFFIX_LENGTH)
                    .as("the reproducible suffix ends where the named filler begins")
                    .isEqualTo(FILLER_OFFSET);
        }

        @Test
        @DisplayName("the layout names itself in diagnostics as the record group and the copybook "
                + "that declares it, which is all a diagnostic is allowed to say about this record")
        void theLayoutNamesItselfForDiagnostics() {
            assertThat(UserSecurityRecordMapper.ARTEFACT).isEqualTo("SEC-USER-DATA");
            assertThat(UserSecurityRecordMapper.COPYBOOK).isEqualTo("CSUSR01Y");
        }

        @Test
        @DisplayName("the identifier is the eight-byte business key at offset 0 and the record "
                + "carries no surrogate key: the sequential dataset is written by a generic copy "
                + "utility at LRECL=80 RECFM=FB, and the mapper derives its width from the layout "
                + "rather than from any catalog attribute")
        void theKeyIsTheLeadingBusinessKeyAndNothingIsGenerated() {
            assertThat(UserSecurityRecordMapper.SEC_USR_ID_OFFSET)
                    .as("the key is the leading substring of the record image")
                    .isZero();
            assertThat(UserSecurityRecordMapper.SEC_USR_ID_LENGTH).isEqualTo(ID_WIDTH);
            assertThat(UserSecurityRecordMapper.RECORD_LENGTH).isEqualTo(RECORD_WIDTH);

            final UserSecurity user =
                    UserSecurityRecordMapper.fromRecord(administratorImage(), FIXED_DIGEST_FUNCTION);

            assertThat(user.getSecUsrId()).isEqualTo("ADMIN001");
            assertThat(bytesOf(user.getSecUsrId())).hasSize(ID_WIDTH);
        }
    }

    @Nested
    @DisplayName("decoding a record image")
    class Decoding {
        @Test
        @DisplayName("a constructed image maps its identifier, both padded names and its role code, "
                + "and stores exactly what the supplied digest function returned")
        void aConstructedImageMapsEveryField() {
            final String image = recordImage("USER0001", "LAWRENCE", "THOMAS",
                    SYNTHETIC_WINDOW_VALUE, STANDARD_USER_TYPE);

            final UserSecurity user =
                    UserSecurityRecordMapper.fromRecord(image, FIXED_DIGEST_FUNCTION);

            assertThat(user.getSecUsrId()).isEqualTo("USER0001");
            assertThat(user.getSecUsrFname()).isEqualTo("LAWRENCE" + spaces(12));
            assertThat(bytesOf(user.getSecUsrFname())).hasSize(FNAME_WIDTH);
            assertThat(user.getSecUsrLname()).isEqualTo("THOMAS" + spaces(14));
            assertThat(bytesOf(user.getSecUsrLname())).hasSize(LNAME_WIDTH);
            assertThat(user.getSecUsrType()).isEqualTo(STANDARD_USER_TYPE);
            assertThat(bytesOf(user.getSecUsrType())).hasSize(TYPE_WIDTH);
            assertThat(user.credentialDigest())
                    .as("the stored value is the injected function's output")
                    .isEqualTo(SYNTHETIC_DIGEST);
        }

        @Test
        @DisplayName("every decoding entry point requires the caller's digest function: the three "
                + "overloads the class declares each take it as their final parameter, and all three "
                + "agree on every mapped field")
        void everyDecodingEntryPointRequiresTheDigestFunctionAndAgrees() {
            final String image = administratorImage();
            final byte[] imageBytes = bytesOf(image);
            final byte[] strided = bytesOf(image + "\n");

            final UserSecurity fromString =
                    UserSecurityRecordMapper.fromRecord(image, FIXED_DIGEST_FUNCTION);
            final UserSecurity fromBytes =
                    UserSecurityRecordMapper.fromRecord(imageBytes, FIXED_DIGEST_FUNCTION);
            final UserSecurity fromRange =
                    UserSecurityRecordMapper.fromRecord(strided, 0, FIXED_DIGEST_FUNCTION);

            for (final UserSecurity user : List.of(fromString, fromBytes, fromRange)) {
                assertThat(user.getSecUsrId()).isEqualTo("ADMIN001");
                assertThat(user.getSecUsrFname()).isEqualTo("MARGARET" + spaces(12));
                assertThat(user.getSecUsrLname()).isEqualTo("GOLD" + spaces(16));
                assertThat(user.getSecUsrType()).isEqualTo(ADMINISTRATOR_TYPE);
                assertThat(SensitiveValues.fingerprint(user.credentialDigest())).isEqualTo(SensitiveValues.fingerprint(SYNTHETIC_DIGEST));
            }
        }

        @Test
        @DisplayName("the byte-range entry point selects one record out of a strided buffer, leaving "
                + "the record separator behind")
        void theByteRangeEntryPointSelectsOneRecordFromAStridedBuffer() {
            final String first = seededImage(SEEDED_IDENTITIES[0]);
            final String second = seededImage(SEEDED_IDENTITIES[5]);
            final byte[] buffer = bytesOf(first + "\n" + second + "\n");
            final int stride = RECORD_WIDTH + 1;

            assertThat(buffer).hasSize(2 * stride);

            final UserSecurity firstUser =
                    UserSecurityRecordMapper.fromRecord(buffer, 0, FIXED_DIGEST_FUNCTION);
            final UserSecurity secondUser =
                    UserSecurityRecordMapper.fromRecord(buffer, stride, FIXED_DIGEST_FUNCTION);

            assertThat(firstUser.getSecUsrId()).isEqualTo("ADMIN001");
            assertThat(firstUser.getSecUsrType()).isEqualTo(ADMINISTRATOR_TYPE);
            assertThat(secondUser.getSecUsrId()).isEqualTo("USER0001");
            assertThat(secondUser.getSecUsrType()).isEqualTo(STANDARD_USER_TYPE);
        }

        @Test
        @DisplayName("the digest function is handed the credential window's eight bytes exactly once, "
                + "unaltered - not trimmed, not case-folded, not normalised and not re-padded")
        void theDigestFunctionReceivesTheWindowExactlyOnceAndUnaltered() {
            final List<String> handedOver = new ArrayList<>();
            final UnaryOperator<String> capturing = window -> {
                handedOver.add(window);
                return SYNTHETIC_DIGEST;
            };
            final String image = recordImage("USER0002", "AJITH", "KUMAR", SYNTHETIC_WINDOW_VALUE,
                    STANDARD_USER_TYPE);

            UserSecurityRecordMapper.fromRecord(image, capturing);

            assertThat(handedOver).as("the window is read once, not twice and not zero times")
                    .hasSize(1);
            assertThat(bytesOf(handedOver.get(0)))
                    .as("the window handed over is the declared eight bytes wide")
                    .hasSize(PWD_WIDTH);
            assertThat(handedOver.get(0))
                    .as("the window arrives exactly as the image carries it")
                    .isEqualTo(SYNTHETIC_WINDOW_VALUE);
        }

        @Test
        @DisplayName("a window narrower than its field arrives with its trailing spaces intact, so no "
                + "trimming or re-padding is applied on the way to the digest function")
        void aNarrowerWindowArrivesWithItsPaddingIntact() {
            final List<String> handedOver = new ArrayList<>();
            final UnaryOperator<String> capturing = window -> {
                handedOver.add(window);
                return SYNTHETIC_DIGEST;
            };
            final String image = recordImage("USER0003", "LAURITZ", "ALME",
                    SYNTHETIC_SHORT_WINDOW_VALUE, STANDARD_USER_TYPE);

            UserSecurityRecordMapper.fromRecord(image, capturing);

            assertThat(handedOver).hasSize(1);
            assertThat(bytesOf(handedOver.get(0))).hasSize(PWD_WIDTH);
            assertThat(handedOver.get(0))
                    .as("the padded window is handed over as placed")
                    .isEqualTo(SYNTHETIC_SHORT_WINDOW_VALUE_AS_PLACED)
                    .endsWith(" ")
                    .as("nothing trimmed the window down to its significant characters")
                    .isNotEqualTo(SYNTHETIC_SHORT_WINDOW_VALUE);
        }

        @Test
        @DisplayName("the entity carries the digest the function returned and never the value the "
                + "image carried in the credential window")
        void theEntityCarriesTheDigestAndNeverTheWindowValue() {
            final UserSecurity user =
                    UserSecurityRecordMapper.fromRecord(administratorImage(), FIXED_DIGEST_FUNCTION);

            assertThat(user.credentialDigest())
                    .isEqualTo(SYNTHETIC_DIGEST)
                    .isNotEqualTo(SYNTHETIC_WINDOW_VALUE);
            assertThat(bytesOf(user.credentialDigest()))
                    .as("the stored value is a digest-width value, not a window-width one")
                    .hasSize(DIGEST_WIDTH)
                    .hasSizeGreaterThan(PWD_WIDTH);
        }

        @Test
        @DisplayName("a digest-shaped value is carried through unchanged at its full 60 bytes, "
                + "whichever shape-valid fixture the function returns")
        void aDigestShapedValueIsCarriedThroughUnchanged() {
            final UnaryOperator<String> returnsOtherFixture = window -> OTHER_SYNTHETIC_DIGEST;

            final UserSecurity user =
                    UserSecurityRecordMapper.fromRecord(administratorImage(), returnsOtherFixture);

            assertThat(SensitiveValues.fingerprint(user.credentialDigest())).isEqualTo(SensitiveValues.fingerprint(OTHER_SYNTHETIC_DIGEST));
            assertThat(bytesOf(user.credentialDigest())).hasSize(DIGEST_WIDTH);
        }

        @Test
        @DisplayName("a digest function that returns nothing fails the mapping with an illegal-state "
                + "report that names the broken function contract and no value of any kind")
        void aDigestFunctionThatReturnsNothingIsRejected() {
            final UnaryOperator<String> returnsNothing = window -> null;
            final String image = administratorImage();

            final Throwable thrown =
                    catchThrowable(() -> UserSecurityRecordMapper.fromRecord(image, returnsNothing));

            assertThat(thrown).isInstanceOf(IllegalStateException.class);
            assertThat(thrown.getMessage())
                    .as("the report names the contract that was broken")
                    .contains("credentialDigestFunction")
                    .contains("contract")
                    .contains("SEC-USR-PWD")
                    .as("the report carries no window value, no digest and no image")
                    .doesNotContain(SYNTHETIC_WINDOW_VALUE)
                    .doesNotContain(SYNTHETIC_DIGEST_TAIL)
                    .doesNotContain(DIGEST_STRUCTURAL_PREFIX)
                    .doesNotContain(image);
        }

        @Test
        @DisplayName("a function that hands its input straight back cannot store a window value: the "
                + "entity refuses it, and the refusal echoes nothing it was handed")
        void aFunctionThatHandsItsInputBackCannotStoreAWindowValue() {
            final String image = administratorImage();

            final Throwable thrown = catchThrowable(
                    () -> UserSecurityRecordMapper.fromRecord(image, UnaryOperator.identity()));

            assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
            assertThat(thrown.getMessage())
                    .doesNotContain(SYNTHETIC_WINDOW_VALUE)
                    .doesNotContain(image);
        }

        @Test
        @DisplayName("a role code outside the seeded pair maps through unchanged, with no exception, "
                + "no default and no normalisation")
        void aRoleCodeOutsideTheSeededPairMapsThroughUnchanged() {
            for (final String code : List.of("X", "9", "u", "a", " ", "#")) {
                final String image = recordImage("USER0009", "GIVEN", "FAMILY",
                        SYNTHETIC_WINDOW_VALUE, code);

                final UserSecurity user =
                        UserSecurityRecordMapper.fromRecord(image, FIXED_DIGEST_FUNCTION);

                assertThat(user.getSecUsrType())
                        .as("an unrecognised role code survives the mapping")
                        .isEqualTo(code);
                assertThat(bytesOf(user.getSecUsrType())).hasSize(TYPE_WIDTH);
            }
        }

        @Test
        @DisplayName("both seeded role codes map as raw one-character values, the administrator code "
                + "and the standard-user code alike")
        void bothSeededRoleCodesMapAsRawCharacters() {
            final UserSecurity administrator = UserSecurityRecordMapper.fromRecord(
                    seededImage(SEEDED_IDENTITIES[0]), FIXED_DIGEST_FUNCTION);
            final UserSecurity standardUser = UserSecurityRecordMapper.fromRecord(
                    seededImage(SEEDED_IDENTITIES[5]), FIXED_DIGEST_FUNCTION);

            assertThat(administrator.getSecUsrType()).isEqualTo(ADMINISTRATOR_TYPE);
            assertThat(bytesOf(administrator.getSecUsrType())).hasSize(TYPE_WIDTH);
            assertThat(standardUser.getSecUsrType()).isEqualTo(STANDARD_USER_TYPE);
            assertThat(bytesOf(standardUser.getSecUsrType())).hasSize(TYPE_WIDTH);
        }

        @Test
        @DisplayName("all ten seeded identities map - five administrator rows and five standard-user "
                + "rows, each carried on one in-stream card of 57 significant bytes")
        void allTenSeededIdentitiesMap() {
            final List<String> identifiers = new ArrayList<>();
            int administrators = 0;
            int standardUsers = 0;

            for (final String[] identity : SEEDED_IDENTITIES) {
                final String image = seededImage(identity);
                assertThat(bytesOf(image)).hasSize(RECORD_WIDTH);

                final UserSecurity user =
                        UserSecurityRecordMapper.fromRecord(image, FIXED_DIGEST_FUNCTION);

                assertThat(user.getSecUsrId()).isEqualTo(identity[0]);
                assertThat(bytesOf(user.getSecUsrId())).hasSize(ID_WIDTH);
                assertThat(user.getSecUsrFname()).isEqualTo(padded(identity[1], FNAME_WIDTH));
                assertThat(bytesOf(user.getSecUsrFname())).hasSize(FNAME_WIDTH);
                assertThat(user.getSecUsrLname()).isEqualTo(padded(identity[2], LNAME_WIDTH));
                assertThat(bytesOf(user.getSecUsrLname())).hasSize(LNAME_WIDTH);
                assertThat(user.getSecUsrType()).isEqualTo(identity[3]);
                assertThat(SensitiveValues.fingerprint(user.credentialDigest())).isEqualTo(SensitiveValues.fingerprint(SYNTHETIC_DIGEST));

                identifiers.add(user.getSecUsrId());
                if (ADMINISTRATOR_TYPE.equals(identity[3])) {
                    administrators++;
                } else {
                    standardUsers++;
                }
            }

            assertThat(identifiers).doesNotHaveDuplicates()
                    .containsExactly("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005",
                            "USER0001", "USER0002", "USER0003", "USER0004", "USER0005");
            assertThat(administrators).isEqualTo(SEEDED_ADMINISTRATOR_COUNT);
            assertThat(standardUsers).isEqualTo(SEEDED_STANDARD_USER_COUNT);
            assertThat(administrators + standardUsers).isEqualTo(SEEDED_IDENTITIES.length);
        }

        @Test
        @DisplayName("both names survive at their full declared width, padding included, so a value "
                + "that arrived padded is not silently shortened")
        void bothNamesSurviveAtTheirDeclaredWidth() {
            final UserSecurity user = UserSecurityRecordMapper.fromRecord(
                    seededImage(SEEDED_IDENTITIES[9]), FIXED_DIGEST_FUNCTION);

            assertThat(bytesOf(user.getSecUsrFname())).hasSize(FNAME_WIDTH);
            assertThat(user.getSecUsrFname())
                    .isEqualTo("LEE" + spaces(17))
                    .endsWith(" ")
                    .as("the given name is not shortened to its significant characters")
                    .isNotEqualTo("LEE");
            assertThat(bytesOf(user.getSecUsrLname())).hasSize(LNAME_WIDTH);
            assertThat(user.getSecUsrLname())
                    .isEqualTo("TING" + spaces(16))
                    .endsWith(" ")
                    .as("the family name is not shortened to its significant characters")
                    .isNotEqualTo("TING");
        }

        @Test
        @DisplayName("a null digest function is rejected on every decoding entry point, naming the "
                + "parameter and stating that no unhashed path exists")
        void aNullDigestFunctionIsRejectedOnEveryEntryPoint() {
            final String image = administratorImage();
            final byte[] imageBytes = bytesOf(image);

            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(image, null))
                    .withMessageContaining("credentialDigestFunction")
                    .withMessageContaining("no path");
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(imageBytes, null))
                    .withMessageContaining("credentialDigestFunction")
                    .withMessageContaining("no path");
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(imageBytes, 0, null))
                    .withMessageContaining("credentialDigestFunction")
                    .withMessageContaining("no path");
        }

        @Test
        @DisplayName("a null image is rejected on every decoding entry point, naming the parameter "
                + "that was absent")
        void aNullImageIsRejectedOnEveryEntryPoint() {
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord((String) null,
                            FIXED_DIGEST_FUNCTION))
                    .withMessageContaining("recordImage");
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord((byte[]) null,
                            FIXED_DIGEST_FUNCTION))
                    .withMessageContaining("recordImage");
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord((byte[]) null, 0,
                            FIXED_DIGEST_FUNCTION))
                    .withMessageContaining("buffer");
        }

        @Test
        @DisplayName("an image one byte short of the declared width is rejected rather than padded, "
                + "and the report states the expected and actual widths and no record content")
        void anImageOneByteShortIsRejected() {
            final String tooShort = recordImage("ADMIN002", "RUSSELL", "RUSSELL",
                    SYNTHETIC_WINDOW_VALUE, ADMINISTRATOR_TYPE, FILLER_WIDTH - 1);
            assertThat(bytesOf(tooShort)).hasSize(RECORD_WIDTH - 1);

            final Throwable thrown = catchThrowable(
                    () -> UserSecurityRecordMapper.fromRecord(tooShort, FIXED_DIGEST_FUNCTION));

            assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
            assertThat(thrown.getMessage())
                    .as("the report names the layout, the expected width and the actual width")
                    .contains("SEC-USER-DATA")
                    .contains("CSUSR01Y")
                    .contains(String.valueOf(RECORD_WIDTH))
                    .contains(String.valueOf(RECORD_WIDTH - 1))
                    .as("the report carries no record content of any kind")
                    .doesNotContain(tooShort)
                    .doesNotContain(SYNTHETIC_WINDOW_VALUE)
                    .doesNotContain("ADMIN002");
        }

        @Test
        @DisplayName("an image one byte longer than the declared width is rejected rather than "
                + "truncated, on the byte entry point as well as the string one")
        void anImageOneByteLongIsRejected() {
            final String tooLong = recordImage("ADMIN003", "RAYMOND", "WHITMORE",
                    SYNTHETIC_WINDOW_VALUE, ADMINISTRATOR_TYPE, FILLER_WIDTH + 1);
            assertThat(bytesOf(tooLong)).hasSize(RECORD_WIDTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(tooLong,
                            FIXED_DIGEST_FUNCTION))
                    .withMessageContaining(String.valueOf(RECORD_WIDTH + 1));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(bytesOf(tooLong),
                            FIXED_DIGEST_FUNCTION))
                    .withMessageContaining(String.valueOf(RECORD_WIDTH + 1));
        }

        @Test
        @DisplayName("an empty image is rejected, reported as zero bytes against the declared width")
        void anEmptyImageIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord("",
                            FIXED_DIGEST_FUNCTION))
                    .withMessageContaining(String.valueOf(RECORD_WIDTH))
                    .withMessageContaining("0");
        }

        @Test
        @DisplayName("a byte range that runs past the end of its buffer is rejected, and so is a "
                + "negative start index")
        void anOutOfBoundsByteRangeIsRejected() {
            final byte[] exactlyOneRecord = bytesOf(administratorImage());

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(exactlyOneRecord, 1,
                            FIXED_DIGEST_FUNCTION))
                    .withMessageContaining("does not fit");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.fromRecord(exactlyOneRecord, -1,
                            FIXED_DIGEST_FUNCTION))
                    .withMessageContaining("negative");
        }
    }

    @Nested
    @DisplayName("emitting a record image")
    class Encoding {
        @Test
        @DisplayName("the credential window is emitted as eight spaces, and the emitted image carries "
                + "neither the stored digest nor any fragment of it")
        void theCredentialWindowIsEmittedAsEightSpaces() {
            final UserSecurity user =
                    UserSecurityRecordMapper.fromRecord(administratorImage(), FIXED_DIGEST_FUNCTION);

            final String emitted = UserSecurityRecordMapper.toRecord(user);
            final byte[] emittedBytes = UserSecurityRecordMapper.toRecordBytes(user);

            assertThat(window(emittedBytes, PWD_OFFSET, PWD_WIDTH))
                    .as("the credential window is a blank run")
                    .hasSize(PWD_WIDTH)
                    .containsOnly(ASCII_SPACE);
            assertThat(emitted)
                    .as("no digest, no fragment of one and no structural marker reaches the image")
                    .doesNotContain(SYNTHETIC_DIGEST)
                    .doesNotContain(SYNTHETIC_DIGEST_TAIL)
                    .doesNotContain(DIGEST_STRUCTURAL_PREFIX);
        }

        @Test
        @DisplayName("the window before the credential is reproduced byte for byte under US-ASCII: "
                + "the identifier and both padded names, bytes 0 through 47")
        void theWindowBeforeTheCredentialIsReproducedByteForByte() {
            final String image = administratorImage();
            final UserSecurity user =
                    UserSecurityRecordMapper.fromRecord(image, FIXED_DIGEST_FUNCTION);

            final byte[] emittedBytes = UserSecurityRecordMapper.toRecordBytes(user);

            final byte[] expectedPrefix = bytesOf(padded("ADMIN001", ID_WIDTH)
                    + padded("MARGARET", FNAME_WIDTH)
                    + padded("GOLD", LNAME_WIDTH));
            assertThat(expectedPrefix).hasSize(ID_WIDTH + FNAME_WIDTH + LNAME_WIDTH);
            assertThat(window(emittedBytes, ID_OFFSET, ID_WIDTH + FNAME_WIDTH + LNAME_WIDTH))
                    .as("the reproducible prefix is emitted exactly as it arrived")
                    .isEqualTo(expectedPrefix)
                    .isEqualTo(window(bytesOf(image), ID_OFFSET,
                            ID_WIDTH + FNAME_WIDTH + LNAME_WIDTH));
        }

        @Test
        @DisplayName("the role code byte at offset 56 is reproduced exactly, which is the whole of the "
                + "window after the credential")
        void theRoleCodeByteIsReproducedExactly() {
            final String image = seededImage(SEEDED_IDENTITIES[5]);
            final UserSecurity user =
                    UserSecurityRecordMapper.fromRecord(image, FIXED_DIGEST_FUNCTION);

            final byte[] emittedBytes = UserSecurityRecordMapper.toRecordBytes(user);

            assertThat(window(emittedBytes, TYPE_OFFSET, TYPE_WIDTH))
                    .hasSize(TYPE_WIDTH)
                    .isEqualTo(bytesOf(STANDARD_USER_TYPE))
                    .isEqualTo(window(bytesOf(image), TYPE_OFFSET, TYPE_WIDTH));
        }

        @Test
        @DisplayName("the named 23-byte trailing filler is emitted as spaces, the module-wide default, "
                + "even though the source names it rather than leaving it anonymous")
        void theNamedTrailingFillerIsEmittedAsSpaces() {
            final UserSecurity user =
                    UserSecurityRecordMapper.fromRecord(administratorImage(), FIXED_DIGEST_FUNCTION);

            final byte[] emittedBytes = UserSecurityRecordMapper.toRecordBytes(user);

            assertThat(window(emittedBytes, FILLER_OFFSET, FILLER_WIDTH))
                    .as("the named filler is a blank run of its declared width")
                    .hasSize(FILLER_WIDTH)
                    .containsOnly(ASCII_SPACE);
        }

        @Test
        @DisplayName("an emitted image measures the declared 80 bytes, and the byte-emitting entry "
                + "point agrees with the string one window by window")
        void anEmittedImageMeasuresTheDeclaredWidthAndBothEntryPointsAgree() {
            final UserSecurity user =
                    UserSecurityRecordMapper.fromRecord(administratorImage(), FIXED_DIGEST_FUNCTION);

            final String emitted = UserSecurityRecordMapper.toRecord(user);
            final byte[] emittedBytes = UserSecurityRecordMapper.toRecordBytes(user);

            assertThat(encodedWidthOf(emitted)).isEqualTo(RECORD_WIDTH);
            assertThat(emittedBytes).hasSize(RECORD_WIDTH);

            final byte[] fromString = bytesOf(emitted);
            assertThat(window(emittedBytes, ID_OFFSET, ID_WIDTH + FNAME_WIDTH + LNAME_WIDTH))
                    .isEqualTo(window(fromString, ID_OFFSET, ID_WIDTH + FNAME_WIDTH + LNAME_WIDTH));
            assertThat(window(emittedBytes, PWD_OFFSET, PWD_WIDTH))
                    .isEqualTo(window(fromString, PWD_OFFSET, PWD_WIDTH));
            assertThat(window(emittedBytes, TYPE_OFFSET, TYPE_WIDTH))
                    .isEqualTo(window(fromString, TYPE_OFFSET, TYPE_WIDTH));
            assertThat(window(emittedBytes, FILLER_OFFSET, FILLER_WIDTH))
                    .isEqualTo(window(fromString, FILLER_OFFSET, FILLER_WIDTH));
        }

        @Test
        @DisplayName("an entity built through the public five-argument constructor places its "
                + "arguments in copybook order - identifier, given name, family name, already-hashed "
                + "credential, role code - at offsets 0, 8, 28, 48 and 56")
        void theFiveArgumentConstructorPlacesItsArgumentsInCopybookOrder() {
            final UserSecurity user = new UserSecurity("ADMIN005", padded("GRANVILLE", FNAME_WIDTH),
                    padded("LACHAPELLE", LNAME_WIDTH), OTHER_SYNTHETIC_DIGEST, ADMINISTRATOR_TYPE);

            final byte[] emittedBytes = UserSecurityRecordMapper.toRecordBytes(user);

            assertThat(emittedBytes).hasSize(RECORD_WIDTH);
            assertThat(window(emittedBytes, ID_OFFSET, ID_WIDTH))
                    .as("the first argument lands in the identifier window")
                    .isEqualTo(bytesOf("ADMIN005"));
            assertThat(window(emittedBytes, FNAME_OFFSET, FNAME_WIDTH))
                    .as("the second argument lands in the given-name window")
                    .isEqualTo(bytesOf("GRANVILLE" + spaces(11)));
            assertThat(window(emittedBytes, LNAME_OFFSET, LNAME_WIDTH))
                    .as("the third argument lands in the family-name window")
                    .isEqualTo(bytesOf("LACHAPELLE" + spaces(10)));
            assertThat(window(emittedBytes, PWD_OFFSET, PWD_WIDTH))
                    .as("the fourth argument is never emitted; its window is blank")
                    .containsOnly(ASCII_SPACE);
            assertThat(window(emittedBytes, TYPE_OFFSET, TYPE_WIDTH))
                    .as("the fifth argument lands in the role-code window")
                    .isEqualTo(bytesOf(ADMINISTRATOR_TYPE));
            assertThat(UserSecurityRecordMapper.toRecord(user))
                    .as("the already-hashed argument reaches no part of the image")
                    .doesNotContain(OTHER_SYNTHETIC_DIGEST)
                    .doesNotContain(OTHER_SYNTHETIC_DIGEST_TAIL)
                    .doesNotContain(DIGEST_STRUCTURAL_PREFIX);
        }

        @Test
        @DisplayName("a shorter value is emitted left-justified and space-padded to its field width, "
                + "so an unpadded property still yields a well-formed record")
        void aShorterValueIsEmittedLeftJustifiedAndPadded() {
            final UserSecurity user = new UserSecurity("USER0005", "LEE", "TING",
                    SYNTHETIC_DIGEST, STANDARD_USER_TYPE);

            final byte[] emittedBytes = UserSecurityRecordMapper.toRecordBytes(user);

            assertThat(emittedBytes).hasSize(RECORD_WIDTH);
            assertThat(window(emittedBytes, FNAME_OFFSET, FNAME_WIDTH))
                    .isEqualTo(bytesOf("LEE" + spaces(17)));
            assertThat(window(emittedBytes, LNAME_OFFSET, LNAME_WIDTH))
                    .isEqualTo(bytesOf("TING" + spaces(16)));
        }

        @Test
        @DisplayName("a null entity is rejected, and so is an absent property, named individually "
                + "because a fixed-width field cannot be completed from an absent value")
        void aNullEntityAndAnAbsentPropertyAreRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecord(null))
                    .withMessageContaining("user");
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecordBytes(null))
                    .withMessageContaining("user");

            final UserSecurity missingIdentifier =
                    UserSecurityRecordMapper.fromRecord(administratorImage(), FIXED_DIGEST_FUNCTION);
            missingIdentifier.setSecUsrId(null);
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecord(missingIdentifier))
                    .withMessageContaining("SEC-USR-ID");

            final UserSecurity missingGivenName =
                    UserSecurityRecordMapper.fromRecord(administratorImage(), FIXED_DIGEST_FUNCTION);
            missingGivenName.setSecUsrFname(null);
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecord(missingGivenName))
                    .withMessageContaining("SEC-USR-FNAME");

            final UserSecurity missingFamilyName =
                    UserSecurityRecordMapper.fromRecord(administratorImage(), FIXED_DIGEST_FUNCTION);
            missingFamilyName.setSecUsrLname(null);
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecord(missingFamilyName))
                    .withMessageContaining("SEC-USR-LNAME");

            final UserSecurity missingRoleCode =
                    UserSecurityRecordMapper.fromRecord(administratorImage(), FIXED_DIGEST_FUNCTION);
            missingRoleCode.setSecUsrType(null);
            assertThatNullPointerException()
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecordBytes(missingRoleCode))
                    .withMessageContaining("SEC-USR-TYPE");
        }

        @Test
        @DisplayName("a value wider than its field is rejected rather than truncated, because a "
                + "truncated field would keep the record the right width while corrupting it")
        void aValueWiderThanItsFieldIsRejected() {
            final UserSecurity overWideIdentifier =
                    UserSecurityRecordMapper.fromRecord(administratorImage(), FIXED_DIGEST_FUNCTION);
            overWideIdentifier.setSecUsrId("ADMIN0011");
            assertThat(bytesOf("ADMIN0011")).hasSize(ID_WIDTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecord(overWideIdentifier))
                    .withMessageContaining("SEC-USR-ID");

            final UserSecurity overWideRoleCode =
                    UserSecurityRecordMapper.fromRecord(administratorImage(), FIXED_DIGEST_FUNCTION);
            overWideRoleCode.setSecUsrType("AU");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserSecurityRecordMapper.toRecordBytes(overWideRoleCode))
                    .withMessageContaining("SEC-USR-TYPE");
        }
    }

    @Nested
    @DisplayName("the documented parity exception")
    class DocumentedParityException {
        @Test
        @DisplayName("the credential is the estate's single documented parity exception: the legacy "
                + "window is eight bytes wide, the stored value is a 60-byte one-way digest, and the "
                + "entity therefore never carries an eight-byte value from the record")
        void theCredentialIsTheSingleDocumentedParityException() {
            final String image = administratorImage();
            final UserSecurity user =
                    UserSecurityRecordMapper.fromRecord(image, FIXED_DIGEST_FUNCTION);

            assertThat(UserSecurityRecordMapper.SEC_USR_PWD_LENGTH)
                    .as("the window in the record keeps its legacy width")
                    .isEqualTo(PWD_WIDTH);
            assertThat(bytesOf(user.credentialDigest()))
                    .as("the stored value is digest-shaped and far wider than the window")
                    .hasSize(DIGEST_WIDTH);
            assertThat(user.credentialDigest())
                    .as("the stored value is what the injected function produced")
                    .isEqualTo(SYNTHETIC_DIGEST)
                    .as("and never the eight bytes the record carried")
                    .isNotEqualTo(SYNTHETIC_WINDOW_VALUE);
            assertThat(encodedWidthOf(user.credentialDigest()))
                    .isNotEqualTo(UserSecurityRecordMapper.SEC_USR_PWD_LENGTH);
        }

        @Test
        @DisplayName("this dataset is the only one of the twelve with no ASCII counterpart, and that "
                + "is immaterial: its ten identities are reconstructed here from the readable "
                + "in-stream provisioning cards, so no encoding conversion is performed anywhere")
        void theAbsentAsciiCounterpartIsImmaterial() {
            assertThat(SEEDED_IDENTITIES).hasNumberOfRows(
                    SEEDED_ADMINISTRATOR_COUNT + SEEDED_STANDARD_USER_COUNT);

            for (final String[] identity : SEEDED_IDENTITIES) {
                final UserSecurity user =
                        UserSecurityRecordMapper.fromRecord(seededImage(identity),
                                FIXED_DIGEST_FUNCTION);
                assertThat(user.getSecUsrId()).isEqualTo(identity[0]);
                assertThat(user.getSecUsrType()).isIn(ADMINISTRATOR_TYPE, STANDARD_USER_TYPE);
            }
        }
    }

    @Nested
    @DisplayName("the bounded round trip")
    class BoundedRoundTrip {
        @Test
        @DisplayName("a whole-record 80-byte equality is meaningless for this layout, because the "
                + "credential window is deliberately not round-trippable, so the three bounded "
                + "windows are asserted instead and no 80-byte equality is written anywhere")
        void theRoundTripIsBoundedToThreeWindows() {
            final String image = administratorImage();
            final byte[] original = bytesOf(image);

            final byte[] emitted = UserSecurityRecordMapper.toRecordBytes(
                    UserSecurityRecordMapper.fromRecord(image, FIXED_DIGEST_FUNCTION));

            assertThat(emitted).hasSize(RECORD_WIDTH);
            assertThat(window(emitted, ID_OFFSET, ID_WIDTH + FNAME_WIDTH + LNAME_WIDTH))
                    .as("window one, bytes 0 through 47, reproduces exactly")
                    .isEqualTo(window(original, ID_OFFSET, ID_WIDTH + FNAME_WIDTH + LNAME_WIDTH));
            assertThat(window(emitted, PWD_OFFSET, PWD_WIDTH))
                    .as("window two, bytes 48 through 55, is blank rather than reproduced")
                    .containsOnly(ASCII_SPACE)
                    .isNotEqualTo(window(original, PWD_OFFSET, PWD_WIDTH));
            assertThat(window(emitted, TYPE_OFFSET, TYPE_WIDTH))
                    .as("window three, byte 56, reproduces exactly")
                    .isEqualTo(window(original, TYPE_OFFSET, TYPE_WIDTH));
            assertThat(window(emitted, FILLER_OFFSET, FILLER_WIDTH))
                    .as("the named filler is reconstructed from its declared width")
                    .containsOnly(ASCII_SPACE);
        }

        @Test
        @DisplayName("the bounded round trip holds for all ten seeded identities, administrator rows "
                + "and standard-user rows alike")
        void theBoundedRoundTripHoldsForEverySeededIdentity() {
            for (final String[] identity : SEEDED_IDENTITIES) {
                final String image = seededImage(identity);
                final byte[] original = bytesOf(image);

                final byte[] emitted = UserSecurityRecordMapper.toRecordBytes(
                        UserSecurityRecordMapper.fromRecord(original, FIXED_DIGEST_FUNCTION));

                assertThat(emitted).hasSize(RECORD_WIDTH);
                assertThat(window(emitted, ID_OFFSET, ID_WIDTH + FNAME_WIDTH + LNAME_WIDTH))
                        .isEqualTo(window(original, ID_OFFSET,
                                ID_WIDTH + FNAME_WIDTH + LNAME_WIDTH));
                assertThat(window(emitted, PWD_OFFSET, PWD_WIDTH)).containsOnly(ASCII_SPACE);
                assertThat(window(emitted, TYPE_OFFSET, TYPE_WIDTH))
                        .isEqualTo(window(original, TYPE_OFFSET, TYPE_WIDTH));
                assertThat(window(emitted, FILLER_OFFSET, FILLER_WIDTH))
                        .containsOnly(ASCII_SPACE);
            }
        }
    }
}
