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
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link CardCrossReference}, the entity form of the 50-byte cross-reference row that
 * carries information in only 36 bytes.
 *
 * <p>The geometry assertions are the point of this suite: the three mapped widths sum to 36, the
 * declared record length is 50, and the 14-byte difference is what reconciles the 1,850-byte text
 * fixture with the 2,500-byte fixed-length dataset. An off-by-one in any offset would still compile
 * and would still round-trip through the entity, so it is pinned here rather than assumed.
 *
 * <p>Identity is asserted behaviourally: two independently constructed rows carrying the same
 * business data are equal, and a row assembled through the mutators equals one built through the
 * constructor, so nothing per-instance takes part in identity. No introspection is used anywhere in
 * this file, which keeps the module's reflection budget at zero.
 */
@DisplayName("CardCrossReference: the 50-byte CVACT03Y row that carries 36 data bytes")
class CardCrossReferenceTest {
    private static final int CARD_NUMBER_WIDTH = 16;

    /** The stand-in the entity prints in place of any populated identifier. */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    private static final int CUSTOMER_ID_WIDTH = 9;

    private static final int ACCOUNT_ID_WIDTH = 11;

    private static final int FILLER_WIDTH = 14;

    private static final int DATA_WIDTH = 36;

    private static final int RECORD_WIDTH = 50;

    private static final int CARD_NUMBER_OFFSET = 0;

    private static final int CUSTOMER_ID_OFFSET = 16;

    private static final int ACCOUNT_ID_OFFSET = 25;

    private static final int FILLER_OFFSET = 36;

    private static final int SEEDED_ROW_COUNT = 50;

    private static final int TEXT_ARTEFACT_BYTE_COUNT = 1850;

    private static final int FIXED_LENGTH_DATASET_BYTE_COUNT = 2500;

    private static final int LINE_TERMINATOR_WIDTH = 1;

    private static final String FIRST_CARD_NUMBER = "0500024453765740";

    private static final String FIRST_CUSTOMER_ID = "000000050";

    private static final String FIRST_ACCOUNT_ID = "00000000050";

    private static final String SECOND_CARD_NUMBER = "0683586198171516";

    private static final String SECOND_CUSTOMER_ID = "000000027";

    private static final String SECOND_ACCOUNT_ID = "00000000027";

    private static final String SIGNIFICANT_DIGITS_ONLY = "50";

    private static CardCrossReference firstRow() {
        return new CardCrossReference(FIRST_CARD_NUMBER, FIRST_CUSTOMER_ID, FIRST_ACCOUNT_ID);
    }

    private static CardCrossReference secondRow() {
        return new CardCrossReference(SECOND_CARD_NUMBER, SECOND_CUSTOMER_ID, SECOND_ACCOUNT_ID);
    }

    private static int asciiWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    @Nested
    @DisplayName("record geometry")
    class RecordGeometry {
        @Test
        @DisplayName("the copybook's four field widths of 16, 9, 11 and 14 sum to the 50-byte record "
                + "length it declares")
        void fourFieldWidthsSumToTheDeclaredRecordLength() {
            assertThat(CARD_NUMBER_WIDTH + CUSTOMER_ID_WIDTH + ACCOUNT_ID_WIDTH + FILLER_WIDTH)
                    .as("the four declared items account for the whole record and nothing is missing")
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the three mapped widths of 16, 9 and 11 sum to 36 data bytes")
        void threeMappedWidthsSumToThirtySix() {
            assertThat(CARD_NUMBER_WIDTH + CUSTOMER_ID_WIDTH + ACCOUNT_ID_WIDTH)
                    .as("the three items this entity maps carry 36 bytes between them")
                    .isEqualTo(DATA_WIDTH);
        }

        @Test
        @DisplayName("36 mapped data bytes is not the 50-byte declared record length, and the "
                + "difference is exactly the 14 filler bytes this entity does not persist")
        void thirtySixIsNotFiftyAndTheDifferenceIsTheUnpersistedFiller() {
            assertThat(DATA_WIDTH)
                    .as("the mapped width and the declared record length are different numbers, and "
                            + "conflating them is what misparses this record")
                    .isNotEqualTo(RECORD_WIDTH);
            assertThat(RECORD_WIDTH - DATA_WIDTH)
                    .as("the whole of the difference is the trailing filler, which no attribute, "
                            + "column or accessor represents")
                    .isEqualTo(FILLER_WIDTH);
        }

        @Test
        @DisplayName("each field begins where the preceding one ends, so the key sits at offset 0 and "
                + "the filler begins at offset 36 where the mapped data ends")
        void eachFieldBeginsWhereThePrecedingOneEnds() {
            assertThat(CARD_NUMBER_OFFSET).isZero();
            assertThat(CARD_NUMBER_OFFSET + CARD_NUMBER_WIDTH).isEqualTo(CUSTOMER_ID_OFFSET);
            assertThat(CUSTOMER_ID_OFFSET + CUSTOMER_ID_WIDTH).isEqualTo(ACCOUNT_ID_OFFSET);
            assertThat(ACCOUNT_ID_OFFSET + ACCOUNT_ID_WIDTH).isEqualTo(FILLER_OFFSET);
            assertThat(FILLER_OFFSET + FILLER_WIDTH).isEqualTo(RECORD_WIDTH);
            assertThat(FILLER_OFFSET)
                    .as("the filler starts precisely where the mapped data stops")
                    .isEqualTo(DATA_WIDTH);
        }

        @Test
        @DisplayName("the cluster's 16-byte key at offset 0 is the card number field and nothing more, "
                + "which is why the identifier is the business key itself")
        void theClusterKeyIsExactlyTheCardNumberField() {
            assertThat(CARD_NUMBER_OFFSET)
                    .as("a key at offset 0 is the leading substring of the stored image")
                    .isZero();
            assertThat(asciiWidth(FIRST_CARD_NUMBER)).isEqualTo(CARD_NUMBER_WIDTH);
            assertThat(asciiWidth(SECOND_CARD_NUMBER)).isEqualTo(CARD_NUMBER_WIDTH);
        }

        @Test
        @DisplayName("the alternate index's 11-byte key at offset 25 is the account identifier field, "
                + "which is why that column and no other carries the equivalent index")
        void theAlternateIndexKeyIsExactlyTheAccountIdentifierField() {
            assertThat(ACCOUNT_ID_OFFSET).isEqualTo(25);
            assertThat(ACCOUNT_ID_WIDTH).isEqualTo(11);
            assertThat(asciiWidth(FIRST_ACCOUNT_ID)).isEqualTo(ACCOUNT_ID_WIDTH);
            assertThat(asciiWidth(SECOND_ACCOUNT_ID)).isEqualTo(ACCOUNT_ID_WIDTH);
        }

        @Test
        @DisplayName("the two validation artefacts hold the same 50 rows, one at 36 mapped bytes plus a "
                + "terminator and one at the full 50-byte record length")
        void bothValidationArtefactsHoldTheSameFiftyRows() {
            assertThat(SEEDED_ROW_COUNT * (DATA_WIDTH + LINE_TERMINATOR_WIDTH))
                    .as("the text artefact writes only the mapped prefix of each row")
                    .isEqualTo(TEXT_ARTEFACT_BYTE_COUNT);
            assertThat(SEEDED_ROW_COUNT * RECORD_WIDTH)
                    .as("the fixed-length dataset writes the filler as well")
                    .isEqualTo(FIXED_LENGTH_DATASET_BYTE_COUNT);
            assertThat(FIXED_LENGTH_DATASET_BYTE_COUNT - TEXT_ARTEFACT_BYTE_COUNT)
                    .as("the two sizes differ by the filler each row omits, less the terminator each "
                            + "text row adds, so both describe the same 50 rows")
                    .isEqualTo(SEEDED_ROW_COUNT * (FILLER_WIDTH - LINE_TERMINATOR_WIDTH));
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {
        @Test
        @DisplayName("the three-argument constructor takes the card number, then the customer "
                + "identifier, then the account identifier, in copybook declaration order")
        void theConstructorTakesItsArgumentsInCopybookOrder() {
            final CardCrossReference row = firstRow();

            assertThat(SensitiveValues.fingerprint(row.getXrefCardNum())).isEqualTo(SensitiveValues.fingerprint(FIRST_CARD_NUMBER));
            assertThat(row.getXrefCustId()).isEqualTo(FIRST_CUSTOMER_ID);
            assertThat(row.getXrefAcctId()).isEqualTo(FIRST_ACCOUNT_ID);
        }

        @Test
        @DisplayName("the customer and account arguments are not transposed, which their different "
                + "declared widths of 9 and 11 make visible")
        void theCustomerAndAccountArgumentsAreNotTransposed() {
            final CardCrossReference row = firstRow();

            assertThat(asciiWidth(row.getXrefCustId())).isEqualTo(CUSTOMER_ID_WIDTH);
            assertThat(asciiWidth(row.getXrefAcctId())).isEqualTo(ACCOUNT_ID_WIDTH);
            assertThat(row.getXrefCustId())
                    .as("three same-typed arguments make a transposition compile silently, so the "
                            + "widths are what catch it")
                    .isNotEqualTo(row.getXrefAcctId());
        }

        @Test
        @DisplayName("the no-argument constructor a persistence provider needs exists and yields an "
                + "instance with none of the three attributes set")
        void theNoArgumentConstructorYieldsAnInstanceWithNothingSet() {
            final CardCrossReference empty = new CardCrossReference();

            assertThat(empty.getXrefCardNum()).isNull();
            assertThat(empty.getXrefCustId()).isNull();
            assertThat(empty.getXrefAcctId()).isNull();
        }
    }

    @Nested
    @DisplayName("attribute carriage")
    class AttributeCarriage {

        @ParameterizedTest(name = "a control character at U+{0} is refused")
        @ValueSource(ints = {0x00, 0x07, 0x09, 0x0A, 0x0D, 0x1B, 0x7F, 0x85, 0x9B})
        @DisplayName("every mutator and the constructor refuse an identifier carrying a control "
                + "character, because such a value can forge a line in any diagnostic that "
                + "interpolates it and no legitimate fixed-width record image holds one")
        void everyEntryPointRefusesAControlCharacter(final int codePoint) {
            final String hostile = "0000" + (char) codePoint + "0050";
            final CardCrossReference row = firstRow();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> row.setXrefCardNum(hostile));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> row.setXrefCustId(hostile));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> row.setXrefAcctId(hostile));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the constructor is the preferred entry point, so it must not be the "
                            + "unguarded one")
                    .isThrownBy(() ->
                            new CardCrossReference(hostile, FIRST_CUSTOMER_ID, FIRST_ACCOUNT_ID));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            new CardCrossReference(FIRST_CARD_NUMBER, hostile, FIRST_ACCOUNT_ID));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            new CardCrossReference(FIRST_CARD_NUMBER, FIRST_CUSTOMER_ID, hostile));
        }

        @Test
        @DisplayName("the refusal names the attribute and the offending position but never the "
                + "offending value, so a diagnostic raised over a forged value does not reproduce it")
        void theRefusalNamesNoOffendingValue() {
            final String hostile = "0000\r\nFORGED 000";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> firstRow().setXrefAcctId(hostile))
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .contains("xrefAcctId")
                            .doesNotContain("FORGED")
                            .doesNotContain("\r")
                            .doesNotContain("\n"));
        }

        @ParameterizedTest(name = "[{0}] is accepted")
        @ValueSource(strings = {
            "000000050", "00000000050", "0000000000000050", "         ", "0000 0050", "ABCdef.-/"
        })
        @DisplayName("every printable value is still accepted verbatim, including one with embedded "
                + "spaces and one of the wrong width, because the legacy tier stored whatever the "
                + "record image held and enforcing a width here would reject data it accepted")
        void everyPrintableValueIsStillAcceptedVerbatim(final String printable) {
            final CardCrossReference row = firstRow();

            row.setXrefAcctId(printable);

            assertThat(row.getXrefAcctId())
                    .as("no trimming, no padding, no case folding and no width enforcement")
                    .isEqualTo(printable);
        }

        @Test
        @DisplayName("a null is still accepted at every entry point, because the provider assigns "
                + "attributes after construction and the schema's own not-null constraints are what "
                + "require a value")
        void aNullIsStillAcceptedAtEveryEntryPoint() {
            final CardCrossReference row = firstRow();

            row.setXrefCardNum(null);
            row.setXrefCustId(null);
            row.setXrefAcctId(null);

            assertThat(row.getXrefCardNum()).isNull();
            assertThat(row.getXrefCustId()).isNull();
            assertThat(row.getXrefAcctId()).isNull();
            assertThat(new CardCrossReference(null, null, null).getXrefCardNum()).isNull();
        }

        @Test
        @DisplayName("the card number mutator round-trips the key field exactly and leaves both "
                + "resolved identifiers untouched")
        void theCardNumberMutatorRoundTripsTheKeyAlone() {
            final CardCrossReference row = firstRow();

            row.setXrefCardNum(SECOND_CARD_NUMBER);

            assertThat(SensitiveValues.fingerprint(row.getXrefCardNum())).isEqualTo(SensitiveValues.fingerprint(SECOND_CARD_NUMBER));
            assertThat(row.getXrefCustId()).isEqualTo(FIRST_CUSTOMER_ID);
            assertThat(row.getXrefAcctId()).isEqualTo(FIRST_ACCOUNT_ID);
        }

        @Test
        @DisplayName("the customer identifier mutator round-trips its own field exactly and touches no "
                + "other")
        void theCustomerIdentifierMutatorRoundTripsItsOwnFieldAlone() {
            final CardCrossReference row = firstRow();

            row.setXrefCustId(SECOND_CUSTOMER_ID);

            assertThat(row.getXrefCustId()).isEqualTo(SECOND_CUSTOMER_ID);
            assertThat(SensitiveValues.fingerprint(row.getXrefCardNum())).isEqualTo(SensitiveValues.fingerprint(FIRST_CARD_NUMBER));
            assertThat(row.getXrefAcctId()).isEqualTo(FIRST_ACCOUNT_ID);
        }

        @Test
        @DisplayName("the account identifier mutator round-trips its own field exactly and touches no "
                + "other, so the column the account-scoped finder queries is never normalised")
        void theAccountIdentifierMutatorRoundTripsItsOwnFieldAlone() {
            final CardCrossReference row = firstRow();

            row.setXrefAcctId(SECOND_ACCOUNT_ID);

            assertThat(row.getXrefAcctId()).isEqualTo(SECOND_ACCOUNT_ID);
            assertThat(SensitiveValues.fingerprint(row.getXrefCardNum())).isEqualTo(SensitiveValues.fingerprint(FIRST_CARD_NUMBER));
            assertThat(row.getXrefCustId()).isEqualTo(FIRST_CUSTOMER_ID);
        }

        @Test
        @DisplayName("a value carrying surrounding blanks is stored and returned untouched, because no "
                + "accessor trims, strips, pads or folds anything")
        void aValueCarryingBlanksIsStoredUntouched() {
            final String blankBearing = "  0500024453  ";
            final int blankBearingWidth = 14;

            final CardCrossReference row =
                    new CardCrossReference(blankBearing, blankBearing, blankBearing);

            assertThat(SensitiveValues.fingerprint(row.getXrefCardNum())).isEqualTo(SensitiveValues.fingerprint(blankBearing));
            assertThat(row.getXrefCustId()).isEqualTo(blankBearing);
            assertThat(row.getXrefAcctId()).isEqualTo(blankBearing);
            assertThat(asciiWidth(row.getXrefCardNum()))
                    .as("the surrounding blanks survive, so nothing was trimmed on the way through")
                    .isEqualTo(blankBearingWidth);
        }

        @Test
        @DisplayName("a lower-case value is returned in the case it was supplied, because no accessor "
                + "folds case the way the legacy embossing edit does")
        void aLowerCaseValueIsReturnedInTheCaseSupplied() {
            final CardCrossReference row = firstRow();
            final String mixedCase = "abcDEF0123456789";

            row.setXrefCardNum(mixedCase);

            assertThat(SensitiveValues.fingerprint(row.getXrefCardNum())).isEqualTo(SensitiveValues.fingerprint(mixedCase));
            assertThat(asciiWidth(row.getXrefCardNum())).isEqualTo(CARD_NUMBER_WIDTH);
        }

        @Test
        @DisplayName("every mutator accepts an absent value, so the entity applies no validation of its "
                + "own and leaves the mandatory columns to the schema")
        void everyMutatorAcceptsAnAbsentValue() {
            final CardCrossReference row = firstRow();

            row.setXrefCardNum(null);
            row.setXrefCustId(null);
            row.setXrefAcctId(null);

            assertThat(row.getXrefCardNum()).isNull();
            assertThat(row.getXrefCustId()).isNull();
            assertThat(row.getXrefAcctId()).isNull();
        }
    }

    @Nested
    @DisplayName("external field widths and leading zeros")
    class ExternalWidthsAndLeadingZeros {
        @Test
        @DisplayName("each identifier occupies exactly the 16, 9 and 11 encoded bytes its copybook "
                + "field fixes")
        void eachIdentifierOccupiesItsDeclaredByteWidth() {
            final CardCrossReference row = firstRow();

            assertThat(row.getXrefCardNum().getBytes(StandardCharsets.US_ASCII).length)
                    .isEqualTo(CARD_NUMBER_WIDTH);
            assertThat(row.getXrefCustId().getBytes(StandardCharsets.US_ASCII).length)
                    .isEqualTo(CUSTOMER_ID_WIDTH);
            assertThat(row.getXrefAcctId().getBytes(StandardCharsets.US_ASCII).length)
                    .isEqualTo(ACCOUNT_ID_WIDTH);
        }

        @Test
        @DisplayName("the three mapped values occupy 36 encoded bytes between them, which is the whole "
                + "of the record's information and leaves the 14 filler bytes to no attribute")
        void theThreeMappedValuesOccupyThirtySixEncodedBytes() {
            final CardCrossReference row = firstRow();

            final int mapped = asciiWidth(row.getXrefCardNum())
                    + asciiWidth(row.getXrefCustId())
                    + asciiWidth(row.getXrefAcctId());

            assertThat(mapped)
                    .as("the widths are summed as numbers rather than concatenated, because assembling "
                            + "a record image is the fixed-width mapper's concern and not this "
                            + "entity's")
                    .isEqualTo(DATA_WIDTH);
            assertThat(RECORD_WIDTH - mapped).isEqualTo(FILLER_WIDTH);
        }

        @Test
        @DisplayName("the 9-digit customer identifier keeps all nine of its bytes, so a value that is "
                + "almost entirely leading zeros is not reduced to its significant digits")
        void theCustomerIdentifierKeepsAllNineOfItsBytes() {
            final CardCrossReference row = firstRow();

            assertThat(row.getXrefCustId()).isEqualTo(FIRST_CUSTOMER_ID);
            assertThat(asciiWidth(row.getXrefCustId())).isEqualTo(CUSTOMER_ID_WIDTH);
            assertThat(row.getXrefCustId())
                    .as("held as a number this value would come back as its significant digits alone, "
                            + "and the stored key would no longer be the bytes the record publishes")
                    .isNotEqualTo(SIGNIFICANT_DIGITS_ONLY);
        }

        @Test
        @DisplayName("the 11-digit account identifier keeps all eleven of its bytes, which is the width "
                + "the alternate index key is declared at")
        void theAccountIdentifierKeepsAllElevenOfItsBytes() {
            final CardCrossReference row = firstRow();

            assertThat(row.getXrefAcctId()).isEqualTo(FIRST_ACCOUNT_ID);
            assertThat(asciiWidth(row.getXrefAcctId())).isEqualTo(ACCOUNT_ID_WIDTH);
            assertThat(row.getXrefAcctId()).isNotEqualTo(SIGNIFICANT_DIGITS_ONLY);
        }

        @Test
        @DisplayName("the customer and account identifiers of one row are different values even though "
                + "their significant digits agree, because their declared widths differ")
        void theTwoIdentifiersDifferByWidthAloneAndAreStillDifferentValues() {
            final CardCrossReference row = firstRow();

            assertThat(row.getXrefCustId())
                    .as("a numeric attribute would have collapsed both fields onto one value and lost "
                            + "the distinction the widths carry")
                    .isNotEqualTo(row.getXrefAcctId());
            assertThat(asciiWidth(row.getXrefAcctId()) - asciiWidth(row.getXrefCustId()))
                    .isEqualTo(ACCOUNT_ID_WIDTH - CUSTOMER_ID_WIDTH);
        }

        @Test
        @DisplayName("the card number keeps the leading zero its first reference row begins with, so "
                + "the 16-byte key survives a round trip whole")
        void theCardNumberKeepsItsLeadingZero() {
            final CardCrossReference row = firstRow();

            assertThat(row.getXrefCardNum().startsWith("0"))
                    .as("the stored key keeps its leading zero, which a numeric round trip would drop")
                    .isTrue();
            assertThat(asciiWidth(row.getXrefCardNum())).isEqualTo(CARD_NUMBER_WIDTH);
        }
    }

    @Nested
    @DisplayName("identity")
    class Identity {
        @Test
        @DisplayName("an instance equals itself and hashes consistently with itself")
        void anInstanceEqualsItself() {
            final CardCrossReference row = firstRow();

            assertThat(row.equals(row))
                    .as("the reflexive case is called directly so the short-circuit branch is exercised")
                    .isTrue();
            assertThat(row).hasSameHashCodeAs(row);
        }

        @Test
        @DisplayName("two rows sharing a card number are equal and hash alike even when both resolved "
                + "identifiers differ, because the key alone determines identity")
        void sameCardNumberMeansEqualWhateverElseDiffers() {
            final CardCrossReference one = firstRow();
            final CardCrossReference other = new CardCrossReference(
                    FIRST_CARD_NUMBER, SECOND_CUSTOMER_ID, SECOND_ACCOUNT_ID);

            assertThat(one).isEqualTo(other);
            assertThat(other)
                    .as("equality is symmetric")
                    .isEqualTo(one);
            assertThat(one).hasSameHashCodeAs(other);

            assertThat(one.getXrefCustId()).isEqualTo(FIRST_CUSTOMER_ID);
            assertThat(other.getXrefCustId()).isEqualTo(SECOND_CUSTOMER_ID);
            assertThat(one.getXrefAcctId()).isEqualTo(FIRST_ACCOUNT_ID);
            assertThat(other.getXrefAcctId()).isEqualTo(SECOND_ACCOUNT_ID);
        }

        @Test
        @DisplayName("two rows with different card numbers are unequal even when both resolved "
                + "identifiers match, which is the non-unique case the alternate index exists to serve")
        void differentCardNumbersMeanUnequalEvenWhenTheRestMatches() {
            final CardCrossReference one = firstRow();
            final CardCrossReference other = new CardCrossReference(
                    SECOND_CARD_NUMBER, FIRST_CUSTOMER_ID, FIRST_ACCOUNT_ID);

            assertThat(one).isNotEqualTo(other);

            assertThat(one.getXrefCustId()).isEqualTo(FIRST_CUSTOMER_ID);
            assertThat(other.getXrefCustId()).isEqualTo(FIRST_CUSTOMER_ID);
            assertThat(one.getXrefAcctId()).isEqualTo(FIRST_ACCOUNT_ID);
            assertThat(other.getXrefAcctId()).isEqualTo(FIRST_ACCOUNT_ID);
        }

        @Test
        @DisplayName("a key written without its leading zero is not the same key, so comparison is "
                + "exact and applies no padding adjustment")
        void aKeyWithoutItsLeadingZeroIsNotTheSameKey() {
            final CardCrossReference row = firstRow();
            final CardCrossReference shortened = new CardCrossReference(
                    "500024453765740", FIRST_CUSTOMER_ID, FIRST_ACCOUNT_ID);

            assertThat(row).isNotEqualTo(shortened);
            assertThat(asciiWidth(shortened.getXrefCardNum()))
                    .as("the shortened key is one byte narrower than the field the record publishes")
                    .isEqualTo(CARD_NUMBER_WIDTH - 1);
        }

        @Test
        @DisplayName("neither an absent reference nor a value of another type is equal to a cross "
                + "reference, even when that value is the card number itself")
        void neitherNullNorAForeignTypeIsEqual() {
            final CardCrossReference row = firstRow();

            assertThat(row.equals(null)).isFalse();
            assertThat(row.equals(FIRST_CARD_NUMBER))
                    .as("a bare key string is not a row, however identical the key")
                    .isFalse();
        }

        @Test
        @DisplayName("the hash code is derived from the key alone, so it survives a change to either "
                + "resolved identifier and an instance stays safe in a hashed collection")
        void theHashCodeSurvivesAChangeToEitherNonKeyAttribute() {
            final CardCrossReference row = firstRow();
            final int beforeMutation = row.hashCode();

            row.setXrefCustId(SECOND_CUSTOMER_ID);
            row.setXrefAcctId(SECOND_ACCOUNT_ID);

            assertThat(row.hashCode()).isEqualTo(beforeMutation);
        }

        @Test
        @DisplayName("two unpopulated instances are equal and hash to zero, which is what lets a "
                + "provider hold one before the key is assigned")
        void twoUnpopulatedInstancesAreEqualAndHashToZero() {
            final CardCrossReference first = new CardCrossReference();
            final CardCrossReference second = new CardCrossReference();

            assertThat(first).isEqualTo(second);
            assertThat(first.hashCode())
                    .as("an absent key hashes to zero, so an unpopulated instance never fails to hash")
                    .isZero();
        }
    }

    /**
     * Consequences of what this entity does not declare, asserted through behaviour rather than
     * through introspection.
     */
    @Nested
    @DisplayName("documented absences")
    class DocumentedAbsences {
        @Test
        @DisplayName("no surrogate identifier exists: the 16-byte card number the cluster declares at "
                + "offset 0 is itself the identifier, with no generated value participating")
        // Were a machine-assigned identifier participating in identity, two independently constructed
                // instances carrying identical business data would hold different values and would not be
                // equal, and neither would agree with an instance assembled through the mutators. All three
                // agree, so identity is determined entirely by caller-supplied business data. This asserts
                // that consequence; it does not assert what the class declares.
        void noSurrogateIdentifierExists() {
            final CardCrossReference constructed = firstRow();
            final CardCrossReference separatelyConstructed = firstRow();

            final CardCrossReference assembled = new CardCrossReference();
            assembled.setXrefCardNum(FIRST_CARD_NUMBER);
            assembled.setXrefCustId(FIRST_CUSTOMER_ID);
            assembled.setXrefAcctId(FIRST_ACCOUNT_ID);

            assertThat(constructed).isEqualTo(separatelyConstructed);
            assertThat(constructed).hasSameHashCodeAs(separatelyConstructed);
            assertThat(constructed)
                    .as("an instance assembled through the mutators is the same row as one built "
                            + "through the constructor, so nothing per-instance takes part in identity")
                    .isEqualTo(assembled);
            assertThat(constructed).hasSameHashCodeAs(assembled);
            assertThat(assembled.getXrefCardNum())
                    .as("the identifier is the business key the record publishes, unchanged")
                    .isEqualTo(FIRST_CARD_NUMBER);
        }

        @Test
        @DisplayName("no filler property exists: the record's fourth item, 14 bytes at offset 36, is "
                + "deliberately unmapped, so exactly three accessors reach the whole of the row")
        // The three accessors exercised here reach the whole of the mapped row, which is why the mapped
                // total stops at 36 while the declared record length continues to 50: the fourth declared item
                // carries no information and has no attribute, column or accessor. Asserted by exercising the
                // accessors that exist.
        void noFillerPropertyExists() {
            final CardCrossReference row = firstRow();

            assertThat(SensitiveValues.fingerprint(row.getXrefCardNum())).isEqualTo(SensitiveValues.fingerprint(FIRST_CARD_NUMBER));
            assertThat(row.getXrefCustId()).isEqualTo(FIRST_CUSTOMER_ID);
            assertThat(row.getXrefAcctId()).isEqualTo(FIRST_ACCOUNT_ID);

            final int reachableThroughAccessors = asciiWidth(row.getXrefCardNum())
                    + asciiWidth(row.getXrefCustId())
                    + asciiWidth(row.getXrefAcctId());

            assertThat(reachableThroughAccessors)
                    .as("three accessors reach 36 of the record's 50 declared bytes")
                    .isEqualTo(DATA_WIDTH);
            assertThat(RECORD_WIDTH - reachableThroughAccessors)
                    .as("the 14 bytes no accessor reaches are the filler, and they are unmapped by "
                            + "intent rather than by omission")
                    .isEqualTo(FILLER_WIDTH);
        }
    }

    /**
     * The diagnostic rendering, and the three identifiers it withholds.
     *
     * <p>The entity publishes a diagnostic rendering, and that rendering is not incidental: it withholds
     * the card number, because that value is a primary account number and a rendering escapes into a
     * failed assertion message, a provider diagnostic or a log event without anybody choosing to disclose
     * it. These assertions are confined to the disclosure guarantee the class documents - that NONE of the
     * three identifiers reaches the rendering, whole or in fragment, while each attribute's population
     * state does.
     *
     * <p>The customer and account identifiers are withheld as well, for two reasons that apply to this row
     * specifically. This row exists to LINK the two, so a rendering carrying both publishes the
     * association rather than either key on its own; and the module's own {@code AccountViewResponse}
     * redacts both, so disclosing them here would make the entity the wider of two inconsistent contracts.
     *
     * <p>The exact wording and punctuation are deliberately <em>not</em> pinned: they are a presentation
     * choice that may change without changing behaviour, and an assertion over them would break on a
     * harmless edit while proving nothing about the guarantee that matters. The stand-in text IS pinned,
     * because it is the same literal the card entity and the account-view response use and one grep over
     * it is how a reviewer finds every redaction in the module. No assertion here compares against a
     * captured rendering.
     */
    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {
        @Test
        @DisplayName("the rendering discloses no card number, whole or partial, even though the card "
                + "number is this row's identity")
        void theRenderingDisclosesNoCardNumber() {
            final String rendered = firstRow().toString();

            assertThat(rendered).isNotBlank();
            assertThat(rendered)
                    .as("the whole key must not appear")
                    .doesNotContain(FIRST_CARD_NUMBER);
            assertThat(rendered)
                    .as("a leading fragment of a card number is still card data")
                    .doesNotContain(FIRST_CARD_NUMBER.substring(0, 6));
            assertThat(rendered)
                    .as("so is a trailing fragment, and the bound is taken from the declared 16-byte "
                            + "field width rather than from a character count")
                    .doesNotContain(FIRST_CARD_NUMBER.substring(CARD_NUMBER_WIDTH - 4));
        }

        @Test
        @DisplayName("the rendering discloses NEITHER resolved identifier, because this row exists to "
                + "link them and a rendering carrying both publishes the association itself")
        void theRenderingDisclosesNeitherResolvedIdentifier() {
            final String rendered = secondRow().toString();

            assertThat(rendered)
                    .as("a customer identifier is what the module's own account-view response redacts; "
                            + "an entity that printed it in full would be the wider of the two holes, "
                            + "because an entity is what reaches a provider diagnostic")
                    .doesNotContain(SECOND_CUSTOMER_ID);
            assertThat(rendered)
                    .as("and the account identifier is redacted in that same response, for the same "
                            + "reason")
                    .doesNotContain(SECOND_ACCOUNT_ID);
        }

        @ParameterizedTest(name = "no fragment of [{0}] appears")
        @ValueSource(strings = {FIRST_CUSTOMER_ID, FIRST_ACCOUNT_ID})
        @DisplayName("no fragment of either resolved identifier appears either, because a fragment is "
                + "still that identifier's data and a rendered length still narrows the candidate set")
        void noFragmentOfEitherResolvedIdentifierAppears(final String identifier) {
            final String rendered = firstRow().toString();

            assertThat(rendered)
                    .doesNotContain(identifier)
                    .doesNotContain(identifier.substring(0, 4))
                    .doesNotContain(identifier.substring(identifier.length() - 4));
        }

        @Test
        @DisplayName("the rendering reports each attribute's population state, so a partially populated "
                + "instance stays diagnosable without any value being disclosed")
        void theRenderingReportsEachAttributesPopulationState() {
            final String populated = firstRow().toString();
            final CardCrossReference partial = new CardCrossReference();
            partial.setXrefAcctId(FIRST_ACCOUNT_ID);

            assertThat(populated)
                    .as("three populated attributes render as three placeholders")
                    .containsOnlyOnce("xrefCardNum=" + REDACTION_PLACEHOLDER)
                    .containsOnlyOnce("xrefCustId=" + REDACTION_PLACEHOLDER)
                    .containsOnlyOnce("xrefAcctId=" + REDACTION_PLACEHOLDER);
            assertThat(partial.toString())
                    .as("and an unassigned attribute is visibly unassigned, which is the one fact a "
                            + "diagnostic needs from this type and the only one it now carries")
                    .contains("xrefCardNum=null")
                    .contains("xrefCustId=null")
                    .contains("xrefAcctId=" + REDACTION_PLACEHOLDER);
        }

        @Test
        @DisplayName("the accessors still return both identifiers untouched at their full 9 and 11 byte "
                + "widths, so withholding them from the rendering costs no caller anything")
        void theAccessorsStillReturnBothIdentifiersUntouched() {
            final CardCrossReference row = firstRow();

            assertThat(row.getXrefCustId()).isEqualTo(FIRST_CUSTOMER_ID);
            assertThat(row.getXrefAcctId()).isEqualTo(FIRST_ACCOUNT_ID);
            assertThat(asciiWidth(FIRST_CUSTOMER_ID)).isEqualTo(CUSTOMER_ID_WIDTH);
            assertThat(asciiWidth(FIRST_ACCOUNT_ID)).isEqualTo(ACCOUNT_ID_WIDTH);
        }

        @Test
        @DisplayName("an unpopulated instance renders without failing, so a provider diagnostic taken "
                + "before the key is assigned cannot itself throw")
        void anUnpopulatedInstanceRendersWithoutFailing() {
            final String rendered = new CardCrossReference().toString();

            assertThat(rendered).isNotBlank();
        }
    }

    @Nested
    @DisplayName("seeded reference rows")
    class SeededReferenceRows {
        @ParameterizedTest(name = "the row keyed {0} resolves to customer {1} and account {2}")
        @CsvSource({
            "0500024453765740,000000050,00000000050",
            "0683586198171516,000000027,00000000027",
        })
        @DisplayName("each leading reference row round-trips its 16-byte key and both resolved "
                + "identifiers at the 9 and 11 byte widths the copybook declares")
        void eachLeadingRowRoundTripsAtItsDeclaredWidths(final String cardNumber,
                final String customerId, final String accountId) {
            final CardCrossReference row =
                    new CardCrossReference(cardNumber, customerId, accountId);

            assertThat(SensitiveValues.fingerprint(row.getXrefCardNum())).isEqualTo(SensitiveValues.fingerprint(cardNumber));
            assertThat(row.getXrefCustId()).isEqualTo(customerId);
            assertThat(row.getXrefAcctId()).isEqualTo(accountId);
            assertThat(asciiWidth(row.getXrefCardNum())).isEqualTo(CARD_NUMBER_WIDTH);
            assertThat(asciiWidth(row.getXrefCustId())).isEqualTo(CUSTOMER_ID_WIDTH);
            assertThat(asciiWidth(row.getXrefAcctId())).isEqualTo(ACCOUNT_ID_WIDTH);
        }

        @Test
        @DisplayName("the two leading rows are distinct rows resolving to distinct customers and "
                + "distinct accounts")
        void theTwoLeadingRowsAreDistinct() {
            final CardCrossReference first = firstRow();
            final CardCrossReference second = secondRow();

            assertThat(first).isNotEqualTo(second);
            assertThat(first.getXrefCustId()).isNotEqualTo(second.getXrefCustId());
            assertThat(first.getXrefAcctId()).isNotEqualTo(second.getXrefAcctId());
        }

        @Test
        @DisplayName("each leading row's customer and account identifiers keep their own widths, so the "
                + "9 and 11 byte fields are never conflated across rows either")
        void eachLeadingRowKeepsBothDeclaredWidths() {
            final CardCrossReference second = secondRow();

            assertThat(asciiWidth(second.getXrefCustId())).isEqualTo(CUSTOMER_ID_WIDTH);
            assertThat(asciiWidth(second.getXrefAcctId())).isEqualTo(ACCOUNT_ID_WIDTH);
            assertThat(second.getXrefCustId()).isNotEqualTo(second.getXrefAcctId());
        }
    }
}
