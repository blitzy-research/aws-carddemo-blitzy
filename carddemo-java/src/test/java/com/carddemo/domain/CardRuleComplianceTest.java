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
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link Card}, the Java carrier of the 150-byte legacy card record.
 *
 * <p>Recorded here as a plain identifier string for the traceability matrix. No assertion is made about
 * that stamp, because it is not carried uniformly by every legacy member and testing for it would test the
 * estate rather than this class.
 *
 * <p><strong>Every expected value in this file was hand-derived, never computed by the code under
 * test.</strong> Widths, offsets and the total record length come from three mutually independent
 * legacy artifacts, each read directly:
 * <ul>
 *   <li>the copybook {@code app/cpy/CVACT02Y.cpy}, whose header states {@code RECLN 150} and which
 *       declares six named fields followed by a 59-byte trailing filler;</li>
 *   <li>the cluster definition {@code app/jcl/CARDFILE.jcl}, which independently states
 *       {@code KEYS(16 0)} and {@code RECORDSIZE(150 150)} for the {@code CARDDATA} base cluster,
 *       and which additionally defines an alternate index over {@code CARD-ACCT-ID} as
 *       {@code KEYS(11 16)};</li>
 *   <li>the sequential reader {@code app/cbl/CBACT02C.cbl}, whose file section splits the same
 *       record into a 16-byte key field {@code FD-CARD-NUM} and a 134-byte remainder
 *       {@code FD-CARD-DATA}.</li>
 * </ul>
 * Because those three descriptions were written independently of one another, cross-checking the
 * width arithmetic against all three is a real test rather than a restatement. In particular the
 * alternate index is a genuinely independent witness to a single field's placement: a key of length
 * 11 at offset 16 can only be {@code CARD-ACCT-ID}, so the copybook's second field is confirmed by
 * an artifact that never mentions the copybook.
 *
 * <p>The field values below are hand-decoded from the first record of the named Gate 4 fixture
 * {@code app/data/ASCII/carddata.txt} and appear as literals. <strong>Nothing in this file decodes a
 * record image, reads a file, or references a production mapper or codec.</strong> Making another
 * class the oracle would leave both classes free to be wrong together, so the fixture arithmetic is
 * asserted as arithmetic and the field values as literals.
 *
 * <p><strong>Documented source anomaly (row 1 of the register in {@code docs/decision-log.md}).</strong>
 * The legacy field at offset 80 drops a letter from EXPIRATION and reads
 * {@code CARD-EXPIRAION-DATE} on line 9 of {@code app/cpy/CVACT02Y.cpy}. It is the twin of the same
 * defect on the account record. The resolution is preserved here rather than re-litigated: the Java
 * property and the column are both spelled correctly while the record offset is unchanged at 80 for
 * a width of 10, so a record image stays byte-compatible. This test asserts the layout consequence -
 * that the field still begins at 70 + 10 == 80 - and cites the misspelling so the mapping from
 * property back to copybook field stays findable by search.
 *
 * <p><strong>A note on what is deliberately not asserted.</strong> The card primary account number
 * and the CVV code carry no field-level encryption or masking anywhere in the legacy design, and no
 * requirement introduces one; that gap is recorded in the decision log rather than closed by
 * unrequested feature work. This test therefore asserts the diagnostic behaviour that actually
 * exists - that {@code toString} exposes the identifier and the status and, importantly, does
 * <em>not</em> expose the CVV - instead of asserting a redaction the class does not implement.
 */
@DisplayName("Card — the 150-byte CVACT02Y card record")
class CardRuleComplianceTest {

    /** Record length stated by the copybook header and by {@code RECORDSIZE(150 150)}. */
    private static final int RECORD_LENGTH = 150;

    /** Offset and width of {@code CARD-NUM PIC X(16)}, the primary key. */
    private static final int CARD_NUM_OFFSET = 0;
    private static final int CARD_NUM_LENGTH = 16;

    /** Offset and width of {@code CARD-ACCT-ID PIC 9(11)}, the alternate-index key. */
    private static final int CARD_ACCT_ID_OFFSET = 16;
    private static final int CARD_ACCT_ID_LENGTH = 11;

    /** Offset and width of {@code CARD-CVV-CD PIC 9(03)}. */
    private static final int CARD_CVV_CD_OFFSET = 27;
    private static final int CARD_CVV_CD_LENGTH = 3;

    /** Offset and width of {@code CARD-EMBOSSED-NAME PIC X(50)}. */
    private static final int CARD_EMBOSSED_NAME_OFFSET = 30;
    private static final int CARD_EMBOSSED_NAME_LENGTH = 50;

    /** Offset and width of the misspelled {@code CARD-EXPIRAION-DATE PIC X(10)}. */
    private static final int CARD_EXPIRATION_DATE_OFFSET = 80;
    private static final int CARD_EXPIRATION_DATE_LENGTH = 10;

    /** Offset and width of {@code CARD-ACTIVE-STATUS PIC X(01)}. */
    private static final int CARD_ACTIVE_STATUS_OFFSET = 90;
    private static final int CARD_ACTIVE_STATUS_LENGTH = 1;

    /** Offset and width of the trailing {@code FILLER PIC X(59)}. */
    private static final int FILLER_OFFSET = 91;
    private static final int FILLER_LENGTH = 59;

    /** Remainder width of the {@code FD-CARD-DATA} field in {@code app/cbl/CBACT02C.cbl}. */
    private static final int FD_CARD_DATA_LENGTH = 134;

    /** Record count of the named fixture {@code app/data/ASCII/carddata.txt}. */
    private static final int FIXTURE_RECORD_COUNT = 50;

    /** Byte size of the named fixture, being 50 records of 150 bytes each plus one terminator. */
    private static final int FIXTURE_BYTE_SIZE = 7550;

    /** Hand-decoded {@code CARD-NUM} of the fixture's first record. */
    private static final String FIRST_CARD_NUM = "0500024453765740";

    /** The placeholder the entity's rendering substitutes for the primary account number. */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /** Hand-decoded {@code CARD-ACCT-ID} of the fixture's first record, zero-filled to 11. */
    private static final String FIRST_CARD_ACCT_ID = "00000000050";

    /** Hand-decoded {@code CARD-CVV-CD} of the fixture's first record. */
    private static final String FIRST_CARD_CVV_CD = "747";

    /** Hand-decoded {@code CARD-EMBOSSED-NAME} of the fixture's first record, before padding. */
    private static final String FIRST_EMBOSSED_NAME = "Aniya Von";

    /** Hand-decoded {@code CARD-EXPIRAION-DATE} of the fixture's first record. */
    private static final String FIRST_EXPIRATION_DATE = "2023-03-09";

    /** Hand-decoded {@code CARD-ACTIVE-STATUS} of the fixture's first record. */
    private static final String FIRST_ACTIVE_STATUS = "Y";

    /**
     * Builds the entity form of the fixture's first record, with the embossed name padded to its
     * declared width exactly as the record image carries it.
     *
     * @return the first fixture record as an entity
     */
    private static Card firstFixtureCard() {
        return new Card(FIRST_CARD_NUM,
                FIRST_CARD_ACCT_ID,
                FIRST_CARD_CVV_CD,
                paddedEmbossedName(),
                FIRST_EXPIRATION_DATE,
                FIRST_ACTIVE_STATUS);
    }

    /**
     * Returns the fixture's embossed name at its declared width of 50, space-padded on the right as
     * a fixed-width alphanumeric field is.
     *
     * @return the padded embossed name
     */
    private static String paddedEmbossedName() {
        return FIRST_EMBOSSED_NAME
                + " ".repeat(CARD_EMBOSSED_NAME_LENGTH - FIRST_EMBOSSED_NAME.length());
    }

    /**
     * Reads the version counter, which the entity exposes for reading but deliberately does not
     * expose for writing, because the persistence provider owns it.
     *
     * @param card  the entity whose counter is being set
     * @param value the value to install
     * @throws ReflectiveOperationException if the field cannot be reached
     */
    private static void installVersion(final Card card, final long value)
            throws ReflectiveOperationException {
        final Field field = Card.class.getDeclaredField("version");
        field.setAccessible(true);
        field.setLong(card, value);
    }

    // ================================================================================
    // Record layout
    // ================================================================================

    @Nested
    @DisplayName("record layout")
    class RecordLayout {

        @Test
        @DisplayName("the six mapped widths sum to 91, and 91 + 59 filler bytes is the 150 bytes "
                + "the copybook header states")
        void theSixMappedWidthsSumToNinetyOneAndTheFillerCompletesOneHundredAndFifty() {
            final int mapped = CARD_NUM_LENGTH
                    + CARD_ACCT_ID_LENGTH
                    + CARD_CVV_CD_LENGTH
                    + CARD_EMBOSSED_NAME_LENGTH
                    + CARD_EXPIRATION_DATE_LENGTH
                    + CARD_ACTIVE_STATUS_LENGTH;

            assertThat(mapped).isEqualTo(91);
            assertThat(mapped).isEqualTo(FILLER_OFFSET);
            assertThat(mapped + FILLER_LENGTH).isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("each declared field begins where the preceding widths leave off, so the six "
                + "offsets are 0, 16, 27, 30, 80 and 90")
        void eachDeclaredFieldBeginsWhereThePrecedingWidthsLeaveOff() {
            final int[][] fields = {
                {CARD_NUM_OFFSET, CARD_NUM_LENGTH},
                {CARD_ACCT_ID_OFFSET, CARD_ACCT_ID_LENGTH},
                {CARD_CVV_CD_OFFSET, CARD_CVV_CD_LENGTH},
                {CARD_EMBOSSED_NAME_OFFSET, CARD_EMBOSSED_NAME_LENGTH},
                {CARD_EXPIRATION_DATE_OFFSET, CARD_EXPIRATION_DATE_LENGTH},
                {CARD_ACTIVE_STATUS_OFFSET, CARD_ACTIVE_STATUS_LENGTH},
                {FILLER_OFFSET, FILLER_LENGTH},
            };

            int runningOffset = 0;
            for (final int[] field : fields) {
                assertThat(field[0]).isEqualTo(runningOffset);
                runningOffset += field[1];
            }
            assertThat(runningOffset).isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the expiry field sits at 70 + 10 == 80: the copybook misspells it "
                + "CARD-EXPIRAION-DATE, and the layout position is preserved regardless")
        void theExpiryFieldSitsAtEightyDespiteTheMisspelling() {
            assertThat(CARD_EMBOSSED_NAME_OFFSET + CARD_EMBOSSED_NAME_LENGTH)
                    .isEqualTo(CARD_EXPIRATION_DATE_OFFSET)
                    .isEqualTo(80);
            assertThat(CARD_EXPIRATION_DATE_LENGTH).isEqualTo(10);
            assertThat(CARD_EXPIRATION_DATE_OFFSET + CARD_EXPIRATION_DATE_LENGTH)
                    .isEqualTo(CARD_ACTIVE_STATUS_OFFSET);
        }

        @Test
        @DisplayName("the correctly spelled Java property carries the misspelled copybook field's "
                + "value, so the anomaly is documented and not propagated")
        void theCorrectlySpelledPropertyCarriesTheMisspelledFieldsValue() {
            final Card card = firstFixtureCard();

            assertThat(card.getCardExpirationDate()).isEqualTo(FIRST_EXPIRATION_DATE);
            assertThat(Card.class.getDeclaredFields())
                    .extracting(Field::getName)
                    .contains("cardExpirationDate")
                    .doesNotContain("cardExpiraionDate");
        }

        @Test
        @DisplayName("the key is the leading 16 bytes at offset 0, exactly as KEYS(16 0) states")
        void theKeyIsTheLeadingSixteenBytesAtOffsetZero() {
            assertThat(CARD_NUM_OFFSET).isZero();
            assertThat(CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(FIRST_CARD_NUM).hasSize(CARD_NUM_LENGTH);
        }

        @Test
        @DisplayName("the alternate index KEYS(11 16) is an independent witness that CARD-ACCT-ID "
                + "is 11 bytes wide at offset 16")
        void theAlternateIndexIsAnIndependentWitnessToTheAccountIdPlacement() {
            final int alternateIndexKeyLength = 11;
            final int alternateIndexKeyOffset = 16;

            assertThat(alternateIndexKeyLength).isEqualTo(CARD_ACCT_ID_LENGTH);
            assertThat(alternateIndexKeyOffset).isEqualTo(CARD_ACCT_ID_OFFSET);
            assertThat(CARD_NUM_OFFSET + CARD_NUM_LENGTH).isEqualTo(alternateIndexKeyOffset);
            assertThat(FIRST_CARD_ACCT_ID).hasSize(alternateIndexKeyLength);
        }

        @Test
        @DisplayName("the sequential reader splits the record as 16 + 134 == 150, which is why the "
                + "identifier is the business key and never a surrogate")
        void theSequentialReaderSplitsTheRecordAsSixteenPlusOneHundredAndThirtyFour() {
            assertThat(CARD_NUM_LENGTH + FD_CARD_DATA_LENGTH).isEqualTo(RECORD_LENGTH);
            assertThat(FD_CARD_DATA_LENGTH).isEqualTo(RECORD_LENGTH - CARD_NUM_LENGTH);
        }

        @Test
        @DisplayName("the named fixture holds 50 records of 150 bytes, so 50 x 151 == 7,550 bytes "
                + "once each record's terminator is counted")
        void theNamedFixtureArithmeticAgreesWithTheRecordLength() {
            final int stride = RECORD_LENGTH + 1;

            assertThat(stride).isEqualTo(151);
            assertThat(FIXTURE_RECORD_COUNT * stride).isEqualTo(FIXTURE_BYTE_SIZE);
            assertThat(FIXTURE_RECORD_COUNT * RECORD_LENGTH)
                    .as("the record content alone, terminators excluded")
                    .isEqualTo(7500);
        }

        @Test
        @DisplayName("the card record is 150 bytes, which is neither the 300 of an account nor the "
                + "350 of a transaction, so a reader cannot confuse the three")
        void theCardRecordLengthIsDistinctFromItsSiblings() {
            assertThat(RECORD_LENGTH).isEqualTo(150).isNotEqualTo(300).isNotEqualTo(350);
        }

        /**
         * The mapped state is the six business fields plus the counter; the constant is not state.
         *
         * <p>Every declared field is enumerated rather than only the mapped ones, because the point of
         * the census is that a seventh <em>column</em> cannot be added without this test noticing. The
         * class also declares one static constant - the placeholder its rendering substitutes for the
         * card number - and a static constant is not per-instance state and is mapped to nothing, so it
         * is separated out by its modifier rather than by its name. Filtering it by name would have made
         * the census pass for a mapped field that happened to be spelled the same way.</p>
         */
        @Test
        @DisplayName("the entity declares exactly the six business fields plus the version counter as "
                + "instance state, and one static constant that is not state at all")
        void theEntityDeclaresExactlyTheSixBusinessFieldsPlusTheVersionCounter() {
            final Set<String> names = new LinkedHashSet<>();
            final Set<String> constants = new LinkedHashSet<>();
            for (final Field field : Card.class.getDeclaredFields()) {
                if (!field.isSynthetic()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        constants.add(field.getName());
                    } else {
                        names.add(field.getName());
                    }
                }
            }

            assertThat(constants)
                    .as("the statics the entity declares are the rendering placeholder and the two "
                            + "record-layout widths the column declarations and the persistence-time "
                            + "rule both read, so neither figure is written down twice")
                    .containsExactlyInAnyOrder("REDACTION_PLACEHOLDER", "CARD_NUM_WIDTH",
                            "CARD_ACCT_ID_WIDTH");
            assertThat(names).containsExactlyInAnyOrder("cardNum",
                    "cardAcctId",
                    "cardCvvCd",
                    "cardEmbossedName",
                    "cardExpirationDate",
                    "cardActiveStatus",
                    "version");
        }
    }

    // ================================================================================
    // Construction and accessors
    // ================================================================================

    @Nested
    @DisplayName("construction and accessors")
    class ConstructionAndAccessors {

        @Test
        @DisplayName("the constructor round-trips every business field of the first fixture record")
        void theConstructorRoundTripsEveryBusinessFieldOfTheFirstFixtureRecord() {
            final Card card = firstFixtureCard();

            assertThat(SensitiveValues.fingerprint(card.getCardNum())).isEqualTo(SensitiveValues.fingerprint(FIRST_CARD_NUM));
            assertThat(card.getCardAcctId()).isEqualTo(FIRST_CARD_ACCT_ID);
            assertThat(SensitiveValues.fingerprint(card.getCardCvvCd())).isEqualTo(SensitiveValues.fingerprint(FIRST_CARD_CVV_CD));
            assertThat(card.getCardEmbossedName()).isEqualTo(paddedEmbossedName());
            assertThat(card.getCardExpirationDate()).isEqualTo(FIRST_EXPIRATION_DATE);
            assertThat(card.getCardActiveStatus()).isEqualTo(FIRST_ACTIVE_STATUS);
        }

        @Test
        @DisplayName("the constructor assigns each argument to its own field, so no two positionally "
                + "adjacent arguments are transposed")
        void theConstructorAssignsEachArgumentToItsOwnField() {
            final Card card = new Card("num", "acct", "cvv", "embossed", "expiry", "status");

            assertThat(SensitiveValues.fingerprint(card.getCardNum())).isEqualTo(SensitiveValues.fingerprint("num"));
            assertThat(card.getCardAcctId()).isEqualTo("acct");
            assertThat(SensitiveValues.fingerprint(card.getCardCvvCd())).isEqualTo(SensitiveValues.fingerprint("cvv"));
            assertThat(card.getCardEmbossedName()).isEqualTo("embossed");
            assertThat(card.getCardExpirationDate()).isEqualTo("expiry");
            assertThat(card.getCardActiveStatus()).isEqualTo("status");
        }

        @Test
        @DisplayName("the six constructor arguments are distinguishable, so a transposition of any "
                + "pair would be observable")
        void theSixConstructorArgumentsAreDistinguishable() {
            final Card card = new Card("1", "2", "3", "4", "5", "6");

            final List<String> readBack = List.of(card.getCardNum(),
                    card.getCardAcctId(),
                    card.getCardCvvCd(),
                    card.getCardEmbossedName(),
                    card.getCardExpirationDate(),
                    card.getCardActiveStatus());

            assertThat(readBack).containsExactly("1", "2", "3", "4", "5", "6");
        }

        @Test
        @DisplayName("the no-argument constructor the persistence provider needs yields an entirely "
                + "unpopulated instance")
        void theNoArgumentConstructorYieldsAnEntirelyUnpopulatedInstance() {
            final Card card = new Card();

            assertThat(card.getCardNum()).isNull();
            assertThat(card.getCardAcctId()).isNull();
            assertThat(card.getCardCvvCd()).isNull();
            assertThat(card.getCardEmbossedName()).isNull();
            assertThat(card.getCardExpirationDate()).isNull();
            assertThat(card.getCardActiveStatus()).isNull();
            assertThat(card.getVersion()).isZero();
        }

        @Test
        @DisplayName("every business setter round-trips its value, each writing only its own field")
        void everyBusinessSetterRoundTripsItsValueWritingOnlyItsOwnField() {
            final Card card = new Card();

            card.setCardNum("A");
            assertThat(SensitiveValues.fingerprint(card.getCardNum())).isEqualTo(SensitiveValues.fingerprint("A"));
            assertThat(card.getCardAcctId()).isNull();

            card.setCardAcctId("B");
            assertThat(card.getCardAcctId()).isEqualTo("B");
            assertThat(card.getCardCvvCd()).isNull();

            card.setCardCvvCd("C");
            assertThat(SensitiveValues.fingerprint(card.getCardCvvCd())).isEqualTo(SensitiveValues.fingerprint("C"));
            assertThat(card.getCardEmbossedName()).isNull();

            card.setCardEmbossedName("D");
            assertThat(card.getCardEmbossedName()).isEqualTo("D");
            assertThat(card.getCardExpirationDate()).isNull();

            card.setCardExpirationDate("E");
            assertThat(card.getCardExpirationDate()).isEqualTo("E");
            assertThat(card.getCardActiveStatus()).isNull();

            card.setCardActiveStatus("F");
            assertThat(card.getCardActiveStatus()).isEqualTo("F");

            assertThat(SensitiveValues.fingerprint(card.getCardNum())).isEqualTo(SensitiveValues.fingerprint("A"));
            assertThat(card.getCardAcctId()).isEqualTo("B");
            assertThat(SensitiveValues.fingerprint(card.getCardCvvCd())).isEqualTo(SensitiveValues.fingerprint("C"));
            assertThat(card.getCardEmbossedName()).isEqualTo("D");
            assertThat(card.getCardExpirationDate()).isEqualTo("E");
        }

        @Test
        @DisplayName("a setter overwrites a constructed value without altering any other field")
        void aSetterOverwritesAConstructedValueWithoutAlteringAnyOtherField() {
            final Card card = firstFixtureCard();

            card.setCardActiveStatus("N");

            assertThat(card.getCardActiveStatus()).isEqualTo("N");
            assertThat(SensitiveValues.fingerprint(card.getCardNum())).isEqualTo(SensitiveValues.fingerprint(FIRST_CARD_NUM));
            assertThat(card.getCardAcctId()).isEqualTo(FIRST_CARD_ACCT_ID);
            assertThat(SensitiveValues.fingerprint(card.getCardCvvCd())).isEqualTo(SensitiveValues.fingerprint(FIRST_CARD_CVV_CD));
            assertThat(card.getCardEmbossedName()).isEqualTo(paddedEmbossedName());
            assertThat(card.getCardExpirationDate()).isEqualTo(FIRST_EXPIRATION_DATE);
        }

        @Test
        @DisplayName("a null business value is accepted and returned as null, because the entity is "
                + "a carrier and the mapper is what refuses an absent field")
        void aNullBusinessValueIsAcceptedAndReturnedAsNull() {
            final Card card = firstFixtureCard();

            card.setCardNum(null);
            card.setCardAcctId(null);
            card.setCardCvvCd(null);
            card.setCardEmbossedName(null);
            card.setCardExpirationDate(null);
            card.setCardActiveStatus(null);

            assertThat(card.getCardNum()).isNull();
            assertThat(card.getCardAcctId()).isNull();
            assertThat(card.getCardCvvCd()).isNull();
            assertThat(card.getCardEmbossedName()).isNull();
            assertThat(card.getCardExpirationDate()).isNull();
            assertThat(card.getCardActiveStatus()).isNull();
        }

        @Test
        @DisplayName("the constructor accepts a wholly null argument list, leaving the refusal of an "
                + "absent field to the fixed-width mapper")
        void theConstructorAcceptsAWhollyNullArgumentList() {
            final Card card = new Card(null, null, null, null, null, null);

            assertThat(card.getCardNum()).isNull();
            assertThat(card.getCardActiveStatus()).isNull();
            assertThat(card.getVersion()).isZero();
        }
    }

    // ================================================================================
    // Raw character fidelity
    // ================================================================================

    @Nested
    @DisplayName("raw character fidelity")
    class RawCharacterFidelity {

        @Test
        @DisplayName("the embossed name is stored at its full declared width of 50, trailing spaces "
                + "included, because the entity never trims")
        void theEmbossedNameIsStoredAtItsFullDeclaredWidth() {
            final Card card = firstFixtureCard();

            assertThat(card.getCardEmbossedName())
                    .hasSize(CARD_EMBOSSED_NAME_LENGTH)
                    .startsWith(FIRST_EMBOSSED_NAME)
                    .endsWith(" ");
            assertThat(card.getCardEmbossedName().stripTrailing())
                    .isEqualTo(FIRST_EMBOSSED_NAME);
        }

        @Test
        @DisplayName("a value that is entirely spaces is preserved rather than normalised to null "
                + "or to an empty string")
        void aValueThatIsEntirelySpacesIsPreserved() {
            final Card card = new Card();
            final String blank = " ".repeat(CARD_EMBOSSED_NAME_LENGTH);

            card.setCardEmbossedName(blank);

            assertThat(card.getCardEmbossedName())
                    .isEqualTo(blank)
                    .hasSize(CARD_EMBOSSED_NAME_LENGTH)
                    .isNotNull()
                    .isNotEmpty()
                    .isBlank();
        }

        @Test
        @DisplayName("the zero-filled account identifier keeps its leading zeros, because it is a "
                + "fixed-width character field and not a number")
        void theZeroFilledAccountIdentifierKeepsItsLeadingZeros() {
            final Card card = firstFixtureCard();

            assertThat(card.getCardAcctId())
                    .isEqualTo(FIRST_CARD_ACCT_ID)
                    .hasSize(CARD_ACCT_ID_LENGTH)
                    .startsWith("00000000")
                    .isNotEqualTo("50");
        }

        @Test
        @DisplayName("the card identifier keeps its leading zero, which a numeric type would lose")
        void theCardIdentifierKeepsItsLeadingZero() {
            final Card card = firstFixtureCard();

            assertThat(card.getCardNum())
                    .startsWith("0")
                    .hasSize(CARD_NUM_LENGTH)
                    .isEqualTo(FIRST_CARD_NUM);
            assertThat(Long.parseLong(FIRST_CARD_NUM))
                    .as("the same digits read as a number would drop the leading zero")
                    .isEqualTo(500024453765740L);
        }

        @Test
        @DisplayName("the three-digit verification code keeps a leading zero as well")
        void theThreeDigitVerificationCodeKeepsALeadingZero() {
            final Card card = new Card();

            card.setCardCvvCd("007");

            assertThat(SensitiveValues.fingerprint(card.getCardCvvCd())).isEqualTo(SensitiveValues.fingerprint("007"));
            assertThat(card.getCardCvvCd().length()).isEqualTo(CARD_CVV_CD_LENGTH);
        }

        @Test
        @DisplayName("every stored field of the first fixture record is representable in US-ASCII, "
                + "as a 7-bit fixed-width record requires")
        void everyStoredFieldOfTheFirstFixtureRecordIsRepresentableInUsAscii() {
            final Card card = firstFixtureCard();
            final List<String> values = List.of(card.getCardNum(),
                    card.getCardAcctId(),
                    card.getCardCvvCd(),
                    card.getCardEmbossedName(),
                    card.getCardExpirationDate(),
                    card.getCardActiveStatus());

            for (final String value : values) {
                assertThat(value.getBytes(StandardCharsets.US_ASCII))
                        .as("value %s must survive a US-ASCII round trip unchanged", value)
                        .hasSize(value.length());
                assertThat(new String(value.getBytes(StandardCharsets.US_ASCII),
                        StandardCharsets.US_ASCII)).isEqualTo(value);
            }
        }

        @Test
        @DisplayName("the expiration date is carried as its ten-character ISO-style text, not as a "
                + "temporal type, so the record image is reproducible")
        void theExpirationDateIsCarriedAsTenCharacterText() {
            final Card card = firstFixtureCard();

            assertThat(card.getCardExpirationDate())
                    .hasSize(CARD_EXPIRATION_DATE_LENGTH)
                    .isEqualTo("2023-03-09")
                    .matches("\\d{4}-\\d{2}-\\d{2}");
        }

        @Test
        @DisplayName("the single-character status is carried as a one-character string, not as an "
                + "enum, so an unexpected legacy value cannot be lost")
        void theSingleCharacterStatusIsCarriedAsAOneCharacterString() {
            final Card card = firstFixtureCard();
            assertThat(card.getCardActiveStatus()).hasSize(CARD_ACTIVE_STATUS_LENGTH);

            card.setCardActiveStatus("Q");
            assertThat(card.getCardActiveStatus())
                    .as("an unrecognised legacy status is carried rather than rejected")
                    .isEqualTo("Q");
        }
    }

    // ================================================================================
    // Business key identity
    // ================================================================================

    @Nested
    @DisplayName("business key identity")
    class BusinessKeyIdentity {

        @Test
        @DisplayName("an instance equals itself")
        void anInstanceEqualsItself() {
            final Card card = firstFixtureCard();

            assertThat(card).isEqualTo(card);
            assertThat(card.equals(card)).isTrue();
        }

        @Test
        @DisplayName("two instances sharing the card identifier are equal even when every other "
                + "field differs, because identity is the business key alone")
        void twoInstancesSharingTheCardIdentifierAreEqual() {
            final Card first = firstFixtureCard();
            final Card second = new Card(FIRST_CARD_NUM,
                    "99999999999", "000", "SOMEONE ELSE", "1999-01-01", "N");

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(first);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("two instances differing only in the card identifier are not equal")
        void twoInstancesDifferingOnlyInTheCardIdentifierAreNotEqual() {
            final Card first = firstFixtureCard();
            final Card second = new Card("0500024453765741",
                    FIRST_CARD_ACCT_ID,
                    FIRST_CARD_CVV_CD,
                    paddedEmbossedName(),
                    FIRST_EXPIRATION_DATE,
                    FIRST_ACTIVE_STATUS);

            assertThat(first).isNotEqualTo(second);
            assertThat(second).isNotEqualTo(first);
        }

        @Test
        @DisplayName("an instance is never equal to null")
        void anInstanceIsNeverEqualToNull() {
            final Card card = firstFixtureCard();

            assertThat(card).isNotEqualTo(null);
            assertThat(card.equals(null)).isFalse();
        }

        @Test
        @DisplayName("an instance is never equal to an object of an unrelated type")
        void anInstanceIsNeverEqualToAnObjectOfAnUnrelatedType() {
            final Card card = firstFixtureCard();

            assertThat(card).isNotEqualTo(FIRST_CARD_NUM);
            assertThat(card.equals(FIRST_CARD_NUM)).isFalse();
            assertThat(card.equals(Integer.valueOf(1))).isFalse();
        }

        @Test
        @DisplayName("two wholly unpopulated instances are equal, because both carry a null key")
        void twoWhollyUnpopulatedInstancesAreEqual() {
            final Card first = new Card();
            final Card second = new Card();

            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("an unpopulated instance is not equal to a populated one")
        void anUnpopulatedInstanceIsNotEqualToAPopulatedOne() {
            assertThat(new Card()).isNotEqualTo(firstFixtureCard());
            assertThat(firstFixtureCard()).isNotEqualTo(new Card());
        }

        @Test
        @DisplayName("the hash code is stable across repeated reads and unchanged by a non-key edit")
        void theHashCodeIsStableAcrossRepeatedReadsAndUnchangedByANonKeyEdit() {
            final Card card = firstFixtureCard();
            final int before = card.hashCode();

            assertThat(card.hashCode()).isEqualTo(before);

            card.setCardActiveStatus("N");
            card.setCardEmbossedName("RENAMED");

            assertThat(card.hashCode())
                    .as("only the business key participates in the hash")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("the hash code follows the key, so editing the key changes it")
        void theHashCodeFollowsTheKey() {
            final Card card = firstFixtureCard();
            final int before = card.hashCode();

            card.setCardNum("0500024453765799");

            assertThat(card.hashCode()).isNotEqualTo(before);
        }

        @Test
        @DisplayName("a set treats two instances with the same identifier as one element")
        void aSetTreatsTwoInstancesWithTheSameIdentifierAsOneElement() {
            final Set<Card> cards = new LinkedHashSet<>();

            cards.add(firstFixtureCard());
            cards.add(new Card(FIRST_CARD_NUM, "1", "2", "3", "4", "5"));
            cards.add(new Card("0500024453765741", "1", "2", "3", "4", "5"));

            assertThat(cards).hasSize(2);
        }
    }

    // ================================================================================
    // Version counter and diagnostics
    // ================================================================================

    @Nested
    @DisplayName("version counter and diagnostics")
    class VersionCounterAndDiagnostics {

        @Test
        @DisplayName("a newly constructed card carries version zero, the unsaved state")
        void aNewlyConstructedCardCarriesVersionZero() {
            assertThat(firstFixtureCard().getVersion()).isZero();
            assertThat(new Card().getVersion()).isZero();
        }

        @Test
        @DisplayName("the version counter is readable but has no setter, because the persistence "
                + "provider owns it")
        void theVersionCounterIsReadableButHasNoSetter() {
            final Set<String> methodNames = new LinkedHashSet<>();
            for (final var method : Card.class.getDeclaredMethods()) {
                methodNames.add(method.getName());
            }

            assertThat(methodNames).contains("getVersion").doesNotContain("setVersion");
        }

        @Test
        @DisplayName("the accessor reports whatever the provider has installed in the counter")
        void theAccessorReportsWhateverTheProviderHasInstalled()
                throws ReflectiveOperationException {
            final Card card = firstFixtureCard();

            installVersion(card, 7L);

            assertThat(card.getVersion()).isEqualTo(7L);
        }

        @Test
        @DisplayName("the version counter takes no part in identity, so two cards with the same key "
                + "and different versions remain equal")
        void theVersionCounterTakesNoPartInIdentity() throws ReflectiveOperationException {
            final Card first = firstFixtureCard();
            final Card second = firstFixtureCard();

            installVersion(second, 42L);

            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
            assertThat(first.getVersion()).isNotEqualTo(second.getVersion());
        }

        /**
         * The rendering names the class and the status, and withholds the identifier.
         *
         * <p>The card number is the primary account number, and an entity whose rendering reproduced it
         * would put it into every log line, exception message and debugger transcript that ever handled a
         * card - the highest-volume disclosure path the module has, and the one least likely to be
         * noticed. It is substituted for a fixed placeholder instead.</p>
         *
         * <p>What survives is what a reader needs to place the record without identifying anybody: the
         * class name and the active status. The status is a single character drawn from a two-value
         * vocabulary and identifies nothing on its own, so it is reported in the clear.</p>
         */
        @Test
        @DisplayName("toString names the class and the status and withholds the identifier, because the "
                + "card number is the primary account number")
        void toStringNamesTheClassAndWithholdsTheIdentifier() {
            final Card card = firstFixtureCard();

            assertThat(card.toString())
                    .isEqualTo("Card[cardNum=" + REDACTION_PLACEHOLDER + ", cardActiveStatus='"
                            + FIRST_ACTIVE_STATUS + "']")
                    .startsWith("Card[")
                    .endsWith("]")
                    .doesNotContain(FIRST_CARD_NUM);
        }

        @Test
        @DisplayName("toString does not expose the verification code, the embossed name or the "
                + "expiry, so a log line carries no more than the diagnostic minimum")
        void toStringDoesNotExposeTheVerificationCodeOrTheOtherFields() {
            final Card card = firstFixtureCard();

            assertThat(card.toString())
                    .doesNotContain(FIRST_CARD_CVV_CD)
                    .doesNotContain(FIRST_EMBOSSED_NAME)
                    .doesNotContain(FIRST_EXPIRATION_DATE)
                    .doesNotContain(FIRST_CARD_ACCT_ID);
        }

        @Test
        @DisplayName("toString tolerates a wholly unpopulated instance rather than failing")
        void toStringToleratesAWhollyUnpopulatedInstance() {
            assertThat(new Card().toString())
                    .isEqualTo("Card[cardNum=" + REDACTION_PLACEHOLDER + ", cardActiveStatus='null']");
        }

        /**
         * The status is reflected; the identifier is not, and cannot be made to be.
         *
         * <p>The second half of this test is the substantive one. Substituting the placeholder only when
         * the card number looks sensitive would be a control that a caller could defeat by supplying a
         * number that did not, so the substitution is unconditional: no value of the field changes the
         * rendering, which is the difference between a redaction and a filter.</p>
         */
        @Test
        @DisplayName("toString reflects an edit to the status, and no edit to the identifier can change "
                + "the rendering, because the substitution is unconditional")
        void toStringReflectsAnEditToTheStatusButNeverToTheIdentifier() {
            final Card card = firstFixtureCard();

            card.setCardActiveStatus("N");
            assertThat(card.toString()).contains("cardActiveStatus='N'");

            final String beforeEdit = card.toString();
            card.setCardNum("0500024453765799");
            assertThat(card.toString())
                    .isEqualTo(beforeEdit)
                    .doesNotContain("0500024453765799");

            card.setCardNum(null);
            assertThat(card.toString())
                    .as("an absent identifier renders as the placeholder too, so the rendering does not "
                            + "betray whether one was held")
                    .isEqualTo(beforeEdit);
        }
    }
}
