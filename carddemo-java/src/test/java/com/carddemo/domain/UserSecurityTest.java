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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.carddemo.domain.enums.UserType;
import com.carddemo.support.SchemaColumnCatalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies {@link UserSecurity}, the eighty-byte sign-on record.
 *
 * <p><strong>The layout being preserved.</strong> {@code app/cpy/CSUSR01Y.cpy} declares six fields over
 * eighty bytes: an eight-byte identifier, a twenty-byte forename, a twenty-byte surname, an eight-byte
 * password and a one-byte role, followed by a twenty-three-byte filler. The cluster definition at
 * {@code app/jcl/DUSRSECJ.jcl} agrees: {@code KEYS(8,0)} and {@code RECORDSIZE(80,80)}.
 *
 * <p><strong>The ten seeded users need no character-set decoding.</strong> The provisioning job carries
 * all ten records in the job stream itself, at lines 35 through 44, as readable card images: five
 * administrator records and five ordinary-user records, each fifty-seven characters of data that the copy
 * utility pads out to the eighty-byte record length. Those ten identifiers, names and roles are the oracle
 * this suite uses, so nothing here depends on decoding the mainframe-encoded dataset that has no
 * plain-text twin.
 *
 * <p><strong>The one deliberate width departure.</strong> Every other field maps to a column of its own
 * copybook width. The password does not: the source field holds eight characters of cleartext and the
 * column holds sixty, because a password digest is longer than the password it protects and sixty is the
 * length a digest of the chosen kind occupies. This is the single place in the estate where reproducing
 * the legacy exactly would have meant reproducing a defect, so the width is different on purpose and the
 * suite asserts the difference rather than glossing it. An eight-character column could not have held a
 * digest at all, and the suite proves that too.
 *
 * <p><strong>Why the role byte is a security decision.</strong> The sign-on program routes an
 * administrator to the administrative menu and everyone else to the main menu on the strength of one byte
 * at position fifty-seven of the record. The byte is the authorisation, so this suite pins its position,
 * its width and the fact that it resolves to exactly one of the two declared roles.
 *
 * <p><strong>Why there is no diagnostic string.</strong> The entity declares none, so neither the
 * identifier nor the digest can reach a log line by way of a description. The suite asserts the inherited
 * type-and-handle form, which is what makes that guarantee testable rather than merely intended.
 *
 * <p><strong>Deliberately not asserted.</strong> Nothing here hashes or verifies a password; that belongs
 * to the authentication service and its encoder. Nothing asserts that the seed migration stores a digest
 * rather than cleartext; that is the migration's contract and is verified where the migration is applied.
 */
@DisplayName("UserSecurity — the eighty-byte sign-on record")
class UserSecurityTest {

    /** Relational table the entity maps to. */
    private static final String TABLE = "user_security";

    /** {@code RECORDSIZE(80,80)} in the cluster definition. */
    private static final int RECORD_WIDTH = 80;

    /** {@code KEYS(8,0)} — key length. */
    private static final int KEY_WIDTH = 8;

    /** Width of the cleartext password field in the legacy record. */
    private static final int LEGACY_PASSWORD_WIDTH = 8;

    /** Width of the password column, sized for a digest rather than for cleartext. */
    private static final int DIGEST_COLUMN_WIDTH = 60;

    /** Width of the unmapped trailing filler. */
    private static final int FILLER_WIDTH = 23;

    /** Zero-based offset of the role byte. */
    private static final int OFFSET_ROLE = 56;

    /** One-based byte position of the role, as an operator reading the record would count it. */
    private static final int ROLE_BYTE_POSITION = 57;

    /** Populated characters of one in-stream card, before the copy utility pads it. */
    private static final int CARD_DATA_WIDTH = 57;

    /** The six copybook widths, in declaration order. */
    private static final List<Integer> COPYBOOK_WIDTHS = List.of(8, 20, 20, 8, 1, 23);

    /** The password literal every seeded card carries. */
    private static final String SEEDED_PASSWORD_LITERAL = "PASSWORD";

    /** Seeded administrators. */
    private static final int SEEDED_ADMINISTRATORS = 5;

    /** Seeded ordinary users. */
    private static final int SEEDED_ORDINARY_USERS = 5;

    /**
     * The ten seeded identifiers with their forename, surname and role, transcribed from the
     * provisioning job's in-stream cards in card order.
     */
    private static final Map<String, String[]> SEEDED_CARDS = seededCards();

    /** The migration's user-security table, parsed once. */
    private static final SchemaColumnCatalog SCHEMA = SchemaColumnCatalog.load();

    /**
     * Transcribes the ten in-stream cards.
     *
     * <p>The map preserves card order, so an immutable hash-ordered copy is deliberately not used.
     *
     * @return an ordered, unmodifiable view of the ten seeded users
     */
    private static Map<String, String[]> seededCards() {
        final Map<String, String[]> cards = new LinkedHashMap<>();
        cards.put("ADMIN001", new String[] {"MARGARET", "GOLD", "A"});
        cards.put("ADMIN002", new String[] {"RUSSELL", "RUSSELL", "A"});
        cards.put("ADMIN003", new String[] {"RAYMOND", "WHITMORE", "A"});
        cards.put("ADMIN004", new String[] {"EMMANUEL", "CASGRAIN", "A"});
        cards.put("ADMIN005", new String[] {"GRANVILLE", "LACHAPELLE", "A"});
        cards.put("USER0001", new String[] {"LAWRENCE", "THOMAS", "U"});
        cards.put("USER0002", new String[] {"AJITH", "KUMAR", "U"});
        cards.put("USER0003", new String[] {"LAURITZ", "ALME", "U"});
        cards.put("USER0004", new String[] {"AVERARDO", "MAZZI", "U"});
        cards.put("USER0005", new String[] {"LEE", "TING", "U"});
        return Collections.unmodifiableMap(cards);
    }

    /**
     * Builds the entity one seeded card describes, at the copybook's blank-filled widths.
     *
     * <p>The credential is a digest and not the card's literal. The mainframe record holds eight
     * cleartext characters; the migrated row holds a sixty-character digest of them, which is what
     * {@code V4__seed_user_security.sql} writes, and the entity refuses any value that is not
     * structurally a digest. The card's literal remains declared on this class because several
     * assertions below concern the card image rather than the persisted row.
     *
     * @param identifier the eight-character sign-on identifier
     * @return the record the card describes, with a digest in place of the card's cleartext password
     */
    private static UserSecurity userFromSeededCard(final String identifier) {
        final String[] card = SEEDED_CARDS.get(identifier);
        return new UserSecurity(
                identifier,
                pad(card[0], 20),
                pad(card[1], 20),
                digestShapedValue(),
                card[2]);
    }

    /**
     * Blank-fills a value on the right, the way a fixed-width character field is written.
     *
     * @param value the value to fill
     * @param width the target width
     * @return the value followed by enough blanks to reach the target width
     */
    private static String pad(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Returns a canonical digest image of the length the password column is sized for.
     *
     * @return a sixty-character value shaped like a password digest
     */
    private static String digestShapedValue() {
        return "$2a$10$" + "N".repeat(DIGEST_COLUMN_WIDTH - 7);
    }

    // RECORD LAYOUT

    /**
     * Verifies the copybook geometry the entity has to honour.
     */
    @Nested
    @DisplayName("record layout")
    class RecordLayout {

        @Test
        @DisplayName("the six copybook widths sum to the eighty bytes the cluster declares")
        void theWidthsSumToTheRecordSize() {
            assertThat(COPYBOOK_WIDTHS).hasSize(6);
            assertThat(COPYBOOK_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the five mapped fields occupy fifty-seven bytes, exactly what one in-stream card "
                + "carries before the copy utility pads it")
        void theMappedFieldsOccupyOneCardOfData() {
            assertThat(COPYBOOK_WIDTHS.stream().mapToInt(Integer::intValue).sum() - FILLER_WIDTH)
                    .isEqualTo(CARD_DATA_WIDTH);
            assertThat(CARD_DATA_WIDTH + FILLER_WIDTH).isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the key is the leading eight bytes, so a sign-on identifier alone locates a record")
        void theKeyIsTheLeadingEightBytes() {
            assertThat(KEY_WIDTH).isEqualTo(COPYBOOK_WIDTHS.get(0));
            assertThat(SCHEMA.declaredWidth(TABLE, "sec_usr_id")).isEqualTo(KEY_WIDTH);
        }

        @Test
        @DisplayName("the role byte sits at position fifty-seven, the last populated byte of the record")
        void theRoleByteSitsAtPositionFiftySeven() {
            int running = 0;
            for (int index = 0; index < 4; index++) {
                running += COPYBOOK_WIDTHS.get(index);
            }

            assertThat(running).isEqualTo(OFFSET_ROLE);
            assertThat(running + 1).isEqualTo(ROLE_BYTE_POSITION);
            assertThat(ROLE_BYTE_POSITION).isEqualTo(CARD_DATA_WIDTH);
            assertThat(COPYBOOK_WIDTHS.get(4)).isOne();
        }

        @Test
        @DisplayName("the trailing twenty-three bytes are filler and are mapped to no column")
        void theTrailingBytesAreFillerAndUnmapped() {
            assertThat(COPYBOOK_WIDTHS.get(5)).isEqualTo(FILLER_WIDTH);
            assertThat(SCHEMA.columnNames(TABLE)).hasSize(COPYBOOK_WIDTHS.size() - 1);
        }
    }

    // SCHEMA AGREEMENT

    /**
     * Verifies that the deployed migration describes the same layout the copybook does, and departs from
     * it in exactly one place.
     */
    @Nested
    @DisplayName("schema agreement")
    class SchemaAgreement {

        @Test
        @DisplayName("the table declares the five mapped columns in copybook order")
        void theTableDeclaresTheMappedColumnsInCopybookOrder() {
            assertThat(SCHEMA.columnNames(TABLE)).containsExactly(
                    "sec_usr_id", "sec_usr_fname", "sec_usr_lname", "sec_usr_pwd", "sec_usr_type");
        }

        @Test
        @DisplayName("every column except the password matches its copybook width")
        void everyColumnExceptThePasswordMatchesItsCopybookWidth() {
            final List<String> columns = SCHEMA.columnNames(TABLE);

            for (int index = 0; index < columns.size(); index++) {
                final String column = columns.get(index);
                if ("sec_usr_pwd".equals(column)) {
                    continue;
                }
                assertThat(SCHEMA.declaredWidth(TABLE, column))
                        .as("declared width of %s", column)
                        .isEqualTo(COPYBOOK_WIDTHS.get(index));
            }
        }

        @Test
        @DisplayName("the password column is sixty wide against a source field of eight, the one "
                + "deliberate width departure in the record")
        void thePasswordColumnIsWiderThanItsSourceField() {
            assertThat(SCHEMA.declaredType(TABLE, "sec_usr_pwd")).isEqualTo("VARCHAR(60)");
            assertThat(SCHEMA.declaredWidth(TABLE, "sec_usr_pwd"))
                    .isEqualTo(DIGEST_COLUMN_WIDTH)
                    .isGreaterThan(LEGACY_PASSWORD_WIDTH);
            assertThat(COPYBOOK_WIDTHS.get(3)).isEqualTo(LEGACY_PASSWORD_WIDTH);
        }

        @Test
        @DisplayName("the departure is the only one, so no other column drifts from the record layout")
        void theDepartureIsTheOnlyOne() {
            final List<String> columns = SCHEMA.columnNames(TABLE);
            final List<String> departures = new ArrayList<>();

            for (int index = 0; index < columns.size(); index++) {
                if (SCHEMA.declaredWidth(TABLE, columns.get(index))
                        != COPYBOOK_WIDTHS.get(index)) {
                    departures.add(columns.get(index));
                }
            }

            assertThat(departures).containsExactly("sec_usr_pwd");
        }

        @Test
        @DisplayName("the role column is one byte, so the authorisation decision rests on a single "
                + "character")
        void theRoleColumnIsOneByte() {
            assertThat(SCHEMA.declaredType(TABLE, "sec_usr_type")).isEqualTo("VARCHAR(1)");
            assertThat(SCHEMA.declaredWidth(TABLE, "sec_usr_type")).isOne();
        }

        @Test
        @DisplayName("the primary key is the identifier alone, and no surrogate or version column exists")
        void thePrimaryKeyIsTheIdentifierAlone() {
            assertThat(SCHEMA.primaryKeyColumns(TABLE)).containsExactly("sec_usr_id");
            assertThat(SCHEMA.columnNames(TABLE)).doesNotContain("id", "user_id", "version");
        }

        @Test
        @DisplayName("every column is declared not null, including the password, so no record can exist "
                + "without credentials")
        void everyColumnIsDeclaredNotNull() {
            for (final String column : SCHEMA.columnNames(TABLE)) {
                assertThat(SCHEMA.isNullable(TABLE, column))
                        .as("nullability of %s", column)
                        .isFalse();
            }
        }
    }

    // CONSTRUCTION AND ACCESS

    /**
     * Verifies that every field the constructor takes is the field the accessor returns.
     */
    @Nested
    @DisplayName("construction and access")
    class ConstructionAndAccess {

        @Test
        @DisplayName("every constructor argument reaches its own accessor, with no leak between the two "
                + "twenty-byte names")
        void everyConstructorArgumentReachesItsAccessor() {
            final UserSecurity user = new UserSecurity(
                    "ADMIN001", pad("MARGARET", 20), pad("GOLD", 20), digestShapedValue(), "A");

            assertThat(user.getSecUsrId()).isEqualTo("ADMIN001");
            assertThat(user.getSecUsrFname()).isEqualTo(pad("MARGARET", 20));
            assertThat(user.getSecUsrLname()).isEqualTo(pad("GOLD", 20));
            assertThat(user.credentialDigest()).isEqualTo(digestShapedValue());
            assertThat(user.getSecUsrType()).isEqualTo("A");
        }

        @Test
        @DisplayName("the two twenty-byte names do not swap, which the one seeded user whose forename "
                + "and surname are identical could otherwise conceal")
        void theTwoNamesDoNotSwap() {
            final UserSecurity distinct = userFromSeededCard("ADMIN001");
            final UserSecurity identical = userFromSeededCard("ADMIN002");

            assertThat(distinct.getSecUsrFname()).isNotEqualTo(distinct.getSecUsrLname());
            assertThat(identical.getSecUsrFname())
                    .as("this user's forename and surname genuinely match in the seed")
                    .isEqualTo(identical.getSecUsrLname());
        }

        @Test
        @DisplayName("every mutator replaces exactly the field it names")
        void everyMutatorReplacesTheFieldItNames() {
            final UserSecurity user = userFromSeededCard("ADMIN001");

            user.setSecUsrId("USER0009");
            user.setSecUsrFname(pad("NEW", 20));
            user.setSecUsrLname(pad("NAME", 20));
            user.replaceCredentialDigest(digestShapedValue());
            user.setSecUsrType("U");

            assertThat(user.getSecUsrId()).isEqualTo("USER0009");
            assertThat(user.getSecUsrFname()).isEqualTo(pad("NEW", 20));
            assertThat(user.getSecUsrLname()).isEqualTo(pad("NAME", 20));
            assertThat(user.credentialDigest()).isEqualTo(digestShapedValue());
            assertThat(user.getSecUsrType()).isEqualTo("U");
        }

        @Test
        @DisplayName("the persistence constructor leaves every field absent")
        void thePersistenceConstructorLeavesEveryFieldAbsent() {
            final UserSecurity user = new UserSecurity();

            assertThat(user.getSecUsrId()).isNull();
            assertThat(user.getSecUsrFname()).isNull();
            assertThat(user.getSecUsrLname()).isNull();
            assertThat(user.credentialDigest()).isNull();
            assertThat(user.getSecUsrType()).isNull();
        }

        @Test
        @DisplayName("the entity carries a value at every declared width")
        void theEntityCarriesValuesAtTheDeclaredWidths() {
            final UserSecurity user = new UserSecurity(
                    "I".repeat(KEY_WIDTH), "F".repeat(20), "L".repeat(20),
                    digestShapedValue(), "A");

            assertThat(user.getSecUsrId()).hasSize(KEY_WIDTH);
            assertThat(user.getSecUsrFname()).hasSize(20);
            assertThat(user.getSecUsrLname()).hasSize(20);
            assertThat(user.credentialDigest()).hasSize(DIGEST_COLUMN_WIDTH);
            assertThat(user.getSecUsrType()).hasSize(1);
        }
    }

    // THE CREDENTIAL WIDTH DEPARTURE

    /**
     * Verifies that the password field can hold a digest and that the legacy field could not have.
     */
    @Nested
    @DisplayName("the credential width departure")
    class CredentialWidthDeparture {

        @Test
        @DisplayName("a full-length digest survives the entity intact")
        void aFullLengthDigestSurvivesIntact() {
            final UserSecurity user = userFromSeededCard("ADMIN001");
            user.replaceCredentialDigest(digestShapedValue());

            assertThat(user.credentialDigest())
                    .hasSize(DIGEST_COLUMN_WIDTH)
                    .startsWith("$2a$10$");
        }

        @Test
        @DisplayName("a digest does not fit the legacy eight-byte field, which is why the column had to "
                + "widen")
        void aDigestDoesNotFitTheLegacyField() {
            assertThat(digestShapedValue().length())
                    .isEqualTo(DIGEST_COLUMN_WIDTH)
                    .isGreaterThan(LEGACY_PASSWORD_WIDTH);
            assertThat(digestShapedValue().substring(0, LEGACY_PASSWORD_WIDTH))
                    .as("truncating a digest to the legacy width would destroy it")
                    .hasSize(LEGACY_PASSWORD_WIDTH)
                    .isNotEqualTo(digestShapedValue());
        }

        @Test
        @DisplayName("the seeded cleartext literal is exactly the legacy width, which is what it had to "
                + "be to fit the record")
        void theSeededLiteralIsExactlyTheLegacyWidth() {
            assertThat(SEEDED_PASSWORD_LITERAL).hasSize(LEGACY_PASSWORD_WIDTH);
        }

        @Test
        @DisplayName("the column has room for the digest and no more, so it is sized rather than left "
                + "open-ended")
        void theColumnIsSizedForTheDigest() {
            assertThat(SCHEMA.declaredWidth(TABLE, "sec_usr_pwd"))
                    .isEqualTo(digestShapedValue().length());
        }
    }

    // THE ROLE BYTE

    /**
     * Verifies the single byte the authorisation decision rests on.
     */
    @Nested
    @DisplayName("the role byte")
    class RoleByte {

        @Test
        @DisplayName("every seeded role resolves to a declared role")
        void everySeededRoleResolves() {
            for (final String identifier : SEEDED_CARDS.keySet()) {
                assertThat(UserType.fromCode(userFromSeededCard(identifier).getSecUsrType()))
                        .as("role of %s", identifier)
                        .isPresent();
            }
        }

        @Test
        @DisplayName("the five administrator records resolve administrative and the five user records do "
                + "not")
        void theFiveAdministratorsResolveAdministrative() {
            int administrators = 0;
            int ordinary = 0;

            for (final String identifier : SEEDED_CARDS.keySet()) {
                final UserType role = UserType.fromCode(userFromSeededCard(identifier).getSecUsrType())
                        .orElseThrow();
                if (role.isAdmin()) {
                    administrators++;
                    assertThat(identifier).as("only an administrator card carries the admin role")
                            .startsWith("ADMIN");
                } else {
                    ordinary++;
                    assertThat(identifier).startsWith("USER");
                }
            }

            assertThat(administrators).isEqualTo(SEEDED_ADMINISTRATORS);
            assertThat(ordinary).isEqualTo(SEEDED_ORDINARY_USERS);
            assertThat(administrators + ordinary).isEqualTo(SEEDED_CARDS.size());
        }

        @Test
        @DisplayName("a role byte outside the vocabulary resolves to nothing, so an unexpected byte "
                + "cannot be read as administrative")
        void anUnknownRoleByteResolvesToNothing() {
            final UserSecurity user = userFromSeededCard("USER0001");
            user.setSecUsrType("X");

            assertThat(UserType.fromCode(user.getSecUsrType())).isEmpty();

            user.setSecUsrType(" ");
            assertThat(UserType.fromCode(user.getSecUsrType())).isEmpty();

            user.setSecUsrType("a");
            assertThat(UserType.fromCode(user.getSecUsrType()))
                    .as("the byte is not case folded")
                    .isEmpty();
        }

        @Test
        @DisplayName("promoting a record's role byte promotes its authorisation, which is why the byte is "
                + "the decision")
        void promotingTheByteChangesTheAuthorisation() {
            final UserSecurity user = userFromSeededCard("USER0001");

            assertThat(UserType.fromCode(user.getSecUsrType()).orElseThrow().isAdmin()).isFalse();
            user.setSecUsrType("A");
            assertThat(UserType.fromCode(user.getSecUsrType()).orElseThrow().isAdmin()).isTrue();
        }
    }

    // THE TEN SEEDED USERS

    /**
     * Verifies the ten records the provisioning job carries in its own job stream.
     */
    @Nested
    @DisplayName("the ten seeded users")
    class SeededUsers {

        @Test
        @DisplayName("the job stream carries exactly ten cards, five of each role")
        void theJobStreamCarriesTenCards() {
            assertThat(SEEDED_CARDS).hasSize(SEEDED_ADMINISTRATORS + SEEDED_ORDINARY_USERS);
            assertThat(SEEDED_ADMINISTRATORS).isEqualTo(SEEDED_ORDINARY_USERS);
        }

        @Test
        @DisplayName("every identifier is exactly eight characters, so every card fills the key")
        void everyIdentifierFillsTheKey() {
            for (final String identifier : SEEDED_CARDS.keySet()) {
                assertThat(identifier)
                        .as("identifier %s", identifier)
                        .hasSize(KEY_WIDTH);
            }
        }

        @Test
        @DisplayName("every identifier is distinct, so ten cards produce ten records")
        void everyIdentifierIsDistinct() {
            final List<UserSecurity> users = new ArrayList<>();
            for (final String identifier : SEEDED_CARDS.keySet()) {
                users.add(userFromSeededCard(identifier));
            }

            assertThat(users).hasSize(SEEDED_CARDS.size());
            assertThat(users.stream().map(UserSecurity::getSecUsrId).distinct().toList())
                    .hasSize(SEEDED_CARDS.size());
        }

        @Test
        @DisplayName("every card's names blank-fill to twenty, so each card's data is fifty-seven "
                + "characters wide")
        void everyCardsDataIsFiftySevenCharactersWide() {
            for (final String identifier : SEEDED_CARDS.keySet()) {
                final UserSecurity user = userFromSeededCard(identifier);
                final int cardWidth = user.getSecUsrId().length()
                        + user.getSecUsrFname().length()
                        + user.getSecUsrLname().length()
                        + SEEDED_PASSWORD_LITERAL.length()
                        + user.getSecUsrType().length();

                assertThat(cardWidth)
                        .as("card width of %s", identifier)
                        .isEqualTo(CARD_DATA_WIDTH);
                assertThat(SEEDED_PASSWORD_LITERAL.length())
                        .as("the card's credential field is the legacy width; the persisted column"
                                + " is not, which is why the card width is summed from the card")
                        .isEqualTo(LEGACY_PASSWORD_WIDTH);
            }
        }

        @Test
        @DisplayName("every card carries the same cleartext literal, which is why the seed cannot be "
                + "used to distinguish one user's credential from another's")
        void everyCardCarriesTheSameLiteral() {
            assertThat(SEEDED_PASSWORD_LITERAL)
                    .as("one literal serves all ten cards, so the seed carries no per-user secret")
                    .hasSize(LEGACY_PASSWORD_WIDTH);

            for (final String identifier : SEEDED_CARDS.keySet()) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("the card literal of %s cannot be stored as it stands", identifier)
                        .isThrownBy(() -> new UserSecurity(identifier, pad("X", 20), pad("Y", 20),
                                SEEDED_PASSWORD_LITERAL, "U"));
            }
        }

        @Test
        @DisplayName("the entity refuses the card's eight-character literal, so the seed migration has "
                + "to hash before it can store")
        void theEntityRefusesTheCardLiteral() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new UserSecurity("ADMIN001", pad("MARGARET", 20),
                            pad("GOLD", 20), SEEDED_PASSWORD_LITERAL, "A"))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .as("a refusal must not echo the value it refused, because that value is"
                                    + " by definition a credential")
                            .doesNotContain(SEEDED_PASSWORD_LITERAL));

            final UserSecurity stored = userFromSeededCard("ADMIN001");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> stored.replaceCredentialDigest(SEEDED_PASSWORD_LITERAL));
            assertThat(stored.credentialDigest())
                    .as("a refused replacement leaves the stored digest untouched")
                    .isEqualTo(digestShapedValue());
        }
    }

    // BUSINESS-KEY IDENTITY

    /**
     * Verifies that identity is the sign-on identifier and nothing else.
     */
    @Nested
    @DisplayName("business-key identity")
    class BusinessKeyIdentity {

        @Test
        @DisplayName("a record equals itself")
        void aRecordEqualsItself() {
            final UserSecurity user = userFromSeededCard("ADMIN001");

            assertThat(user).isEqualTo(user);
            assertThat(user.hashCode()).isEqualTo(user.hashCode());
        }

        @Test
        @DisplayName("two records with the same identifier are equal even when the role and the "
                + "credential differ, because the key alone decides identity")
        void sameIdentifierMeansEqualEvenWithADifferentRole() {
            final UserSecurity left = userFromSeededCard("ADMIN001");
            final UserSecurity right = new UserSecurity(
                    "ADMIN001", pad("OTHER", 20), pad("OTHER", 20), digestShapedValue(), "U");

            assertThat(left).isEqualTo(right);
            assertThat(right).isEqualTo(left);
            assertThat(left).hasSameHashCodeAs(right);
        }

        @Test
        @DisplayName("two records with different identifiers are unequal")
        void differentIdentifierMeansUnequal() {
            assertThat(userFromSeededCard("ADMIN001")).isNotEqualTo(userFromSeededCard("ADMIN002"));
            assertThat(userFromSeededCard("USER0001")).isNotEqualTo(userFromSeededCard("ADMIN001"));
        }

        @Test
        @DisplayName("equality is transitive across three records sharing an identifier")
        void equalityIsTransitive() {
            final UserSecurity first = userFromSeededCard("USER0003");
            final UserSecurity second = userFromSeededCard("USER0003");
            final UserSecurity third = userFromSeededCard("USER0003");

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(third);
            assertThat(first).isEqualTo(third);
            assertThat(first).hasSameHashCodeAs(third);
        }

        @Test
        @DisplayName("a record is unequal to null and to an unrelated type")
        void aRecordIsUnequalToNullAndToAnotherType() {
            final UserSecurity user = userFromSeededCard("ADMIN001");

            assertThat(user).isNotEqualTo(null);
            assertThat(user.equals("ADMIN001")).isFalse();
            assertThat(user).isNotEqualTo(new Object());
        }

        @Test
        @DisplayName("two records with an absent identifier are equal, because both keys are absent "
                + "rather than generated")
        void twoUnkeyedRecordsAreEqual() {
            assertThat(new UserSecurity()).isEqualTo(new UserSecurity());
            assertThat(new UserSecurity()).hasSameHashCodeAs(new UserSecurity());
            assertThat(new UserSecurity().getSecUsrId()).isNull();
        }
    }

    // CREDENTIAL CONTAINMENT

    /**
     * Verifies that no credential can escape through a diagnostic string.
     */
    @Nested
    @DisplayName("credential containment")
    class CredentialContainment {

        @Test
        @DisplayName("the entity declares a diagnostic string that names the sign-on identifier alone, "
                + "so the credential cannot reach a log line through one")
        void theDiagnosticStringNamesTheKeyAlone() {
            final UserSecurity user = userFromSeededCard("ADMIN001");
            user.replaceCredentialDigest(digestShapedValue());

            // The rendering is stated by the entity rather than inherited. The inherited form would leak
            // nothing either, but only by accident of the base class: a field added later, or a decision
            // to render reflectively, would leak silently. Naming the safe rendering makes the guarantee
            // belong to this class and makes any widening of it visible in review. What must never
            // appear is asserted below, exhaustively.
            assertThat(user.toString())
                    .isEqualTo("UserSecurity[secUsrId=ADMIN001]")
                    .doesNotContain(digestShapedValue())
                    .doesNotContain(SEEDED_PASSWORD_LITERAL)
                    .doesNotContain("$2a$")
                    .doesNotContain("MARGARET")
                    .doesNotContain("secUsrPwd");
        }

        @Test
        @DisplayName("the credential is readable only through its own accessor, so a caller has to ask "
                + "for it explicitly")
        void theCredentialIsReadableOnlyThroughItsAccessor() {
            final UserSecurity user = userFromSeededCard("ADMIN001");

            assertThat(user.credentialDigest()).isEqualTo(digestShapedValue());
            assertThat(user.toString()).doesNotContain(user.credentialDigest());
        }
    }
}
