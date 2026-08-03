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

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CardCrossReference}, the entity form of the 50-byte cross-reference row that
 * carries only 36 bytes of data.
 *
 * <p>Copybook {@code app/cpy/CVACT03Y.cpy} declares a 50-byte record in four parts: a 16-byte card
 * number, a 9-digit customer identifier, an 11-digit account identifier and a 14-byte trailing filler.
 * The cluster definition in {@code app/jcl/XREFFILE.jcl} corroborates that geometry independently -
 * {@code KEYS(16 0)} with {@code RECORDSIZE(50 50)} - and its {@code CXACAIX} alternate index,
 * {@code KEYS(11 25)} with {@code NONUNIQUEKEY} and {@code UPGRADE}, independently corroborates the
 * account identifier's width and offset. The filler carries no information and is mapped by no
 * attribute, leaving exactly three mapped properties.
 *
 * <p>Every width, offset, count and literal asserted below was derived by hand from the copybook, from
 * the cluster definition and from the reference file {@code app/data/ASCII/cardxref.txt}, then written
 * here as a constant. Nothing is read back from the class under test to produce an expectation and
 * nothing is read from disk or the classpath: a pure unit test touching no container, application
 * context, database, network or file.
 *
 * <p><strong>The stride arithmetic is itself part of the evidence, and it is why this suite has the
 * shape it does.</strong> The text fixture measures 1,850 bytes, which is 50 rows of 36 data bytes plus
 * one line terminator each, because the text form writes only the mapped prefix. The fixed-length
 * dataset holding the same 50 rows measures 2,500 bytes, which is 50 rows at the full 50-byte record
 * length. Both figures are asserted below, and so is the consequence a reader has to survive: advancing
 * through the text form at the record length rather than at the data length lands 14 bytes late on the
 * second row and on every row after it, producing plausible-looking wrong values rather than an error.
 * That is demonstrated on a synthetic two-row image built in memory from the two leading fixture rows,
 * so the demonstration needs no file and cannot go stale.
 *
 * <p>Why all three identifiers have to stay text: the customer and account identifiers are declared as
 * digit-only fields, and in the fixture they are almost entirely leading zeros - a nine-character
 * customer value and an eleven-character account value whose significant digits are the last two. Held
 * as numbers they would come back two characters wide, the stored keys would no longer be the bytes the
 * record publishes, and the foreign keys towards the account and customer tables would resolve against
 * nothing. The assertions prove every leading zero survives a round trip and that a value written
 * without its leading zeros is not the same value.
 *
 * <p>Why no relationship is asserted: the migration constrains all three columns of this table, yet the
 * entity models no association in either direction. This suite therefore asserts what the entity does
 * carry - three independent scalar keys, each stored and returned untouched - and asserts that the two
 * non-key identifiers take no part in identity, which is the behavioural consequence of their being
 * non-unique. Many cards resolve to one account and to one customer, which is exactly why the alternate
 * index over the account identifier is declared non-unique.
 *
 * <p>Provenance: layout facts are cited from the estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, whose members carry the upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced here;
 * member names, field names, widths, offsets and counts are cited only.
 */
@DisplayName("CardCrossReference: the 50-byte CVACT03Y row carrying 36 data bytes")
final class CardCrossReferenceTest {

    /** Width of the card number field, and of the whole cluster key. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** Width of the customer identifier field. */
    private static final int CUSTOMER_ID_WIDTH = 9;

    /** Width of the account identifier field, and of the alternate index key. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** Width of the trailing filler, which no attribute and no column represents. */
    private static final int FILLER_WIDTH = 14;

    /** Physical record length the copybook header and the cluster definition both declare. */
    private static final int RECORD_WIDTH = 50;

    /** Leading bytes that carry information, which is the record length less the filler. */
    private static final int DATA_WIDTH = 36;

    /** Offset of the card number, which is also the cluster key offset. */
    private static final int CARD_NUMBER_OFFSET = 0;

    /** Offset of the customer identifier. */
    private static final int CUSTOMER_ID_OFFSET = 16;

    /** Offset of the account identifier, which is also the alternate index key offset. */
    private static final int ACCOUNT_ID_OFFSET = 25;

    /** Offset at which the trailing filler begins. */
    private static final int FILLER_OFFSET = 36;

    /** Rows the reference data carries, in both the text and the fixed-length form. */
    private static final int SEEDED_ROW_COUNT = 50;

    /** Measured size of the text reference file, which writes only the mapped prefix per row. */
    private static final int TEXT_FIXTURE_BYTE_COUNT = 1850;

    /** Measured size of the fixed-length dataset holding the same rows at the full record length. */
    private static final int FIXED_LENGTH_DATASET_BYTE_COUNT = 2500;

    /** Bytes a text row spends on its terminator. */
    private static final int LINE_TERMINATOR_WIDTH = 1;

    /** Card number of the first fixture row. */
    private static final String FIRST_CARD_NUMBER = "0500024453765740";

    /** Customer identifier of the first fixture row. */
    private static final String FIRST_CUSTOMER_ID = "000000050";

    /** Account identifier of the first fixture row. */
    private static final String FIRST_ACCOUNT_ID = "00000000050";

    /** Card number of the second fixture row, used to demonstrate the stride hazard. */
    private static final String SECOND_CARD_NUMBER = "0683586198171516";

    /** Customer identifier of the second fixture row. */
    private static final String SECOND_CUSTOMER_ID = "000000027";

    /** Account identifier of the second fixture row. */
    private static final String SECOND_ACCOUNT_ID = "00000000027";

    /** Card number of the third fixture row, present so a 50-byte stride has somewhere to drift to. */
    private static final String THIRD_CARD_NUMBER = "0923877193247330";

    /** Customer identifier of the third fixture row. */
    private static final String THIRD_CUSTOMER_ID = "000000002";

    /** Account identifier of the third fixture row. */
    private static final String THIRD_ACCOUNT_ID = "00000000002";

    /** The stand-in the diagnostic rendering prints in place of the card number. */
    private static final String REDACTION = "***REDACTED***";

    /**
     * Builds the first fixture row as an entity.
     *
     * @return a cross reference carrying the three values of the first reference row
     */
    private static CardCrossReference firstRow() {
        return new CardCrossReference(FIRST_CARD_NUMBER, FIRST_CUSTOMER_ID, FIRST_ACCOUNT_ID);
    }

    /**
     * Builds the second fixture row as an entity.
     *
     * @return a cross reference carrying the three values of the second reference row
     */
    private static CardCrossReference secondRow() {
        return new CardCrossReference(SECOND_CARD_NUMBER, SECOND_CUSTOMER_ID, SECOND_ACCOUNT_ID);
    }

    /**
     * Renders one row exactly as the text form writes it: the 36 mapped bytes and nothing else.
     *
     * @param cardNumber the 16-byte card number
     * @param customerId the 9-digit customer identifier
     * @param accountId  the 11-digit account identifier
     * @return the 36-byte data prefix of that row
     */
    private static String dataPrefix(final String cardNumber, final String customerId,
            final String accountId) {
        return cardNumber + customerId + accountId;
    }

    /** The geometry the copybook and the cluster definition agree on. */
    @Nested
    @DisplayName("record geometry")
    class RecordGeometry {

        @Test
        @DisplayName("the copybook's four field widths of 16, 9, 11 and 14 sum to the 50-byte record "
                + "length")
        void fourFieldWidthsSumToTheRecordLength() {
            assertThat(CARD_NUMBER_WIDTH + CUSTOMER_ID_WIDTH + ACCOUNT_ID_WIDTH + FILLER_WIDTH)
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the three mapped widths sum to 36, the record length less the 14 filler bytes")
        void threeMappedWidthsSumToTheDataWidth() {
            assertThat(CARD_NUMBER_WIDTH + CUSTOMER_ID_WIDTH + ACCOUNT_ID_WIDTH)
                    .isEqualTo(DATA_WIDTH);
            assertThat(RECORD_WIDTH - FILLER_WIDTH).isEqualTo(DATA_WIDTH);
        }

        @Test
        @DisplayName("each field starts where the preceding one ends, so the key sits at offset 0 and "
                + "the filler at offset 36")
        void eachFieldStartsWhereThePrecedingOneEnds() {
            assertThat(CARD_NUMBER_OFFSET).isZero();
            assertThat(CARD_NUMBER_OFFSET + CARD_NUMBER_WIDTH).isEqualTo(CUSTOMER_ID_OFFSET);
            assertThat(CUSTOMER_ID_OFFSET + CUSTOMER_ID_WIDTH).isEqualTo(ACCOUNT_ID_OFFSET);
            assertThat(ACCOUNT_ID_OFFSET + ACCOUNT_ID_WIDTH).isEqualTo(FILLER_OFFSET);
            assertThat(FILLER_OFFSET + FILLER_WIDTH).isEqualTo(RECORD_WIDTH);
            assertThat(FILLER_OFFSET).isEqualTo(DATA_WIDTH);
        }

        @Test
        @DisplayName("the 16-byte key at offset 0 that the cluster declares is the card number field "
                + "and nothing more")
        void theClusterKeyIsTheCardNumberField() {
            assertThat(CARD_NUMBER_OFFSET).isZero();
            assertThat(FIRST_CARD_NUMBER).hasSize(CARD_NUMBER_WIDTH);
            assertThat(SECOND_CARD_NUMBER).hasSize(CARD_NUMBER_WIDTH);
        }

        @Test
        @DisplayName("the 11-byte alternate index key at offset 25 is the account identifier field and "
                + "nothing more")
        void theAlternateIndexKeyIsTheAccountIdentifierField() {
            assertThat(ACCOUNT_ID_OFFSET).isEqualTo(25);
            assertThat(FIRST_ACCOUNT_ID).hasSize(ACCOUNT_ID_WIDTH);
            assertThat(SECOND_ACCOUNT_ID).hasSize(ACCOUNT_ID_WIDTH);
        }
    }

    /** The two byte counts the validation artefacts publish, and the hazard between them. */
    @Nested
    @DisplayName("text stride versus record length")
    class TextStrideVersusRecordLength {

        @Test
        @DisplayName("the text fixture's 1,850 bytes are exactly 50 rows of 36 data bytes plus one "
                + "terminator each")
        void theTextFixtureIsFiftyRowsOfThirtySixDataBytes() {
            assertThat(SEEDED_ROW_COUNT * (DATA_WIDTH + LINE_TERMINATOR_WIDTH))
                    .isEqualTo(TEXT_FIXTURE_BYTE_COUNT);
        }

        @Test
        @DisplayName("the fixed-length dataset's 2,500 bytes are the same 50 rows at the full 50-byte "
                + "record length")
        void theFixedLengthDatasetIsFiftyRowsOfFiftyBytes() {
            assertThat(SEEDED_ROW_COUNT * RECORD_WIDTH).isEqualTo(FIXED_LENGTH_DATASET_BYTE_COUNT);
        }

        @Test
        @DisplayName("the two forms differ by exactly the filler the entity does not persist, less the "
                + "terminators the text form adds")
        void theTwoFormsDifferByTheFillerLessTheTerminators() {
            assertThat(FIXED_LENGTH_DATASET_BYTE_COUNT - TEXT_FIXTURE_BYTE_COUNT)
                    .isEqualTo(SEEDED_ROW_COUNT * (FILLER_WIDTH - LINE_TERMINATOR_WIDTH));
        }

        @Test
        @DisplayName("a 36-byte stride reads the second row of a text image exactly")
        void aThirtySixByteStrideReadsTheSecondRowExactly() {
            final String image = threeRowImage();

            final CardCrossReference read = sliceAt(image, DATA_WIDTH);

            assertThat(read.getXrefCardNum()).isEqualTo(SECOND_CARD_NUMBER);
            assertThat(read.getXrefCustId()).isEqualTo(SECOND_CUSTOMER_ID);
            assertThat(read.getXrefAcctId()).isEqualTo(SECOND_ACCOUNT_ID);
            assertThat(read).isEqualTo(secondRow());
        }

        /**
         * The whole point of the filler note on the entity: a reader that advances at the record length
         * rather than at the data length lands 14 bytes into the second row and reads a key spliced out
         * of the middle of it. It does not fail; it succeeds with the wrong answer, which is why the
         * fact is documented rather than left to be discovered.
         */
        @Test
        @DisplayName("a 50-byte stride lands 14 bytes into the second row and yields a card number "
                + "belonging to no row at all")
        void aFiftyByteStrideMisparsesTheSecondRow() {
            final String image = threeRowImage();

            final CardCrossReference misread = sliceAt(image, RECORD_WIDTH);

            assertThat(misread.getXrefCardNum())
                    .as("the misread key is spliced out of the middle of the second row")
                    .hasSize(CARD_NUMBER_WIDTH)
                    .isNotEqualTo(FIRST_CARD_NUMBER)
                    .isNotEqualTo(SECOND_CARD_NUMBER)
                    .isNotEqualTo(THIRD_CARD_NUMBER);
            assertThat(misread).isNotEqualTo(secondRow());
            assertThat(RECORD_WIDTH - DATA_WIDTH)
                    .as("the drift per row is exactly the filler this entity does not persist")
                    .isEqualTo(FILLER_WIDTH);
        }

        /**
         * Builds the text form of the three leading fixture rows: 36 data bytes each, back to back,
         * with no filler between them. Terminators are omitted so the arithmetic under test is the
         * field stride alone, and three rows rather than two so that a drifting reader still has bytes
         * to read and therefore fails by returning the wrong answer rather than by overrunning.
         *
         * @return a 108-character image holding the three rows at a 36-byte stride
         */
        private String threeRowImage() {
            final String image =
                    dataPrefix(FIRST_CARD_NUMBER, FIRST_CUSTOMER_ID, FIRST_ACCOUNT_ID)
                            + dataPrefix(SECOND_CARD_NUMBER, SECOND_CUSTOMER_ID, SECOND_ACCOUNT_ID)
                            + dataPrefix(THIRD_CARD_NUMBER, THIRD_CUSTOMER_ID, THIRD_ACCOUNT_ID);

            assertThat(image).hasSize(3 * DATA_WIDTH);
            return image;
        }

        /**
         * Reads the row that begins at the given stride, slicing the three fields at their copybook
         * offsets exactly as a fixed-width reader would.
         *
         * @param image  the two-row image
         * @param stride the offset at which the second row is assumed to begin
         * @return the entity that slicing produces, correct or otherwise
         */
        private CardCrossReference sliceAt(final String image, final int stride) {
            return new CardCrossReference(
                    image.substring(stride + CARD_NUMBER_OFFSET,
                            stride + CARD_NUMBER_OFFSET + CARD_NUMBER_WIDTH),
                    image.substring(stride + CUSTOMER_ID_OFFSET,
                            stride + CUSTOMER_ID_OFFSET + CUSTOMER_ID_WIDTH),
                    image.substring(stride + ACCOUNT_ID_OFFSET,
                            stride + ACCOUNT_ID_OFFSET + ACCOUNT_ID_WIDTH));
        }
    }

    /** What the constructors and mutators carry, and what they refuse to change. */
    @Nested
    @DisplayName("construction and attribute carriage")
    class ConstructionAndAttributeCarriage {

        @Test
        @DisplayName("the three-argument constructor takes the card number, then the customer "
                + "identifier, then the account identifier, in copybook order")
        void theConstructorTakesItsArgumentsInCopybookOrder() {
            final CardCrossReference row = firstRow();

            assertThat(row.getXrefCardNum()).isEqualTo(FIRST_CARD_NUMBER);
            assertThat(row.getXrefCustId()).isEqualTo(FIRST_CUSTOMER_ID);
            assertThat(row.getXrefAcctId()).isEqualTo(FIRST_ACCOUNT_ID);
        }

        @Test
        @DisplayName("the customer and account arguments are not transposed, which their different "
                + "field widths of 9 and 11 make visible")
        void theCustomerAndAccountArgumentsAreNotTransposed() {
            final CardCrossReference row = firstRow();

            assertThat(row.getXrefCustId()).hasSize(CUSTOMER_ID_WIDTH);
            assertThat(row.getXrefAcctId()).hasSize(ACCOUNT_ID_WIDTH);
            assertThat(row.getXrefCustId()).isNotEqualTo(row.getXrefAcctId());
        }

        @Test
        @DisplayName("the no-argument constructor a persistence provider needs exists and yields an "
                + "instance with no attribute set")
        void theNoArgumentConstructorYieldsAnEmptyInstance() {
            final CardCrossReference empty = new CardCrossReference();

            assertThat(empty.getXrefCardNum()).isNull();
            assertThat(empty.getXrefCustId()).isNull();
            assertThat(empty.getXrefAcctId()).isNull();
        }

        @Test
        @DisplayName("the card number mutator replaces the key field and leaves both resolved "
                + "identifiers untouched")
        void theCardNumberMutatorTouchesOnlyTheKey() {
            final CardCrossReference row = firstRow();

            row.setXrefCardNum(SECOND_CARD_NUMBER);

            assertThat(row.getXrefCardNum()).isEqualTo(SECOND_CARD_NUMBER);
            assertThat(row.getXrefCustId()).isEqualTo(FIRST_CUSTOMER_ID);
            assertThat(row.getXrefAcctId()).isEqualTo(FIRST_ACCOUNT_ID);
        }

        @Test
        @DisplayName("the customer identifier mutator replaces only that field")
        void theCustomerIdentifierMutatorTouchesOnlyItsOwnField() {
            final CardCrossReference row = firstRow();

            row.setXrefCustId(SECOND_CUSTOMER_ID);

            assertThat(row.getXrefCustId()).isEqualTo(SECOND_CUSTOMER_ID);
            assertThat(row.getXrefCardNum()).isEqualTo(FIRST_CARD_NUMBER);
            assertThat(row.getXrefAcctId()).isEqualTo(FIRST_ACCOUNT_ID);
        }

        @Test
        @DisplayName("the account identifier mutator replaces only that field")
        void theAccountIdentifierMutatorTouchesOnlyItsOwnField() {
            final CardCrossReference row = firstRow();

            row.setXrefAcctId(SECOND_ACCOUNT_ID);

            assertThat(row.getXrefAcctId()).isEqualTo(SECOND_ACCOUNT_ID);
            assertThat(row.getXrefCardNum()).isEqualTo(FIRST_CARD_NUMBER);
            assertThat(row.getXrefCustId()).isEqualTo(FIRST_CUSTOMER_ID);
        }

        @Test
        @DisplayName("every mutator accepts an absent value, so the entity applies no validation of "
                + "its own and leaves mandatory columns to the schema")
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

    /** The external widths the record publishes, and the leading zeros that make them contractual. */
    @Nested
    @DisplayName("external field widths and leading zeros")
    class ExternalFieldWidths {

        @Test
        @DisplayName("each identifier occupies exactly the byte width its copybook field fixes")
        void eachIdentifierOccupiesItsDeclaredByteWidth() {
            final CardCrossReference row = firstRow();

            assertThat(row.getXrefCardNum().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(CARD_NUMBER_WIDTH);
            assertThat(row.getXrefCustId().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(CUSTOMER_ID_WIDTH);
            assertThat(row.getXrefAcctId().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(ACCOUNT_ID_WIDTH);
        }

        @Test
        @DisplayName("the three mapped values concatenate to exactly the 36 data bytes of the record, "
                + "with no room left for the filler")
        void theThreeValuesConcatenateToTheDataWidth() {
            final CardCrossReference row = firstRow();

            final String prefix = row.getXrefCardNum() + row.getXrefCustId() + row.getXrefAcctId();

            assertThat(prefix).hasSize(DATA_WIDTH);
            assertThat(prefix.getBytes(StandardCharsets.US_ASCII)).hasSize(DATA_WIDTH);
        }

        @Test
        @DisplayName("every leading zero survives a round trip, so the stored keys are still the bytes "
                + "the record publishes")
        void everyLeadingZeroSurvivesARoundTrip() {
            final CardCrossReference row = firstRow();

            assertThat(row.getXrefCardNum()).startsWith("0");
            assertThat(row.getXrefCustId()).startsWith("000000");
            assertThat(row.getXrefAcctId()).startsWith("00000000");
        }

        @Test
        @DisplayName("an identifier written without its leading zeros is a different value, which is "
                + "why a numeric type could not carry these fields")
        void anIdentifierWithoutLeadingZerosIsADifferentValue() {
            final CardCrossReference row = firstRow();

            assertThat(row.getXrefCustId()).isNotEqualTo("50");
            assertThat(row.getXrefAcctId()).isNotEqualTo("50");
            assertThat(row.getXrefCustId()).isNotEqualTo(row.getXrefAcctId());
            assertThat(Integer.parseInt(row.getXrefCustId()))
                    .as("both identifiers carry the same significant digits and differ only in width")
                    .isEqualTo(Integer.parseInt(row.getXrefAcctId()));
        }

        @Test
        @DisplayName("a value carrying significant padding is stored and returned untouched, because "
                + "no accessor trims, strips, pads or folds anything")
        void aPaddedValueIsStoredUntouched() {
            final String padded = "  0500024453  ";
            final CardCrossReference row = new CardCrossReference(padded, padded, padded);

            assertThat(row.getXrefCardNum()).isEqualTo(padded).hasSize(padded.length());
            assertThat(row.getXrefCustId()).isEqualTo(padded);
            assertThat(row.getXrefAcctId()).isEqualTo(padded);
        }
    }

    /** Row identity, which is the card number and nothing else. */
    @Nested
    @DisplayName("identity")
    class Identity {

        @Test
        @DisplayName("an instance equals itself and hashes consistently")
        void anInstanceEqualsItself() {
            final CardCrossReference row = firstRow();

            assertThat(row).isEqualTo(row);
            assertThat(row).hasSameHashCodeAs(row);
        }

        @Test
        @DisplayName("two rows with the same card number are equal even when both resolved identifiers "
                + "differ, because the key alone determines identity")
        void sameCardNumberMeansEqualWhateverElseDiffers() {
            final CardCrossReference one = firstRow();
            final CardCrossReference other = new CardCrossReference(
                    FIRST_CARD_NUMBER, SECOND_CUSTOMER_ID, SECOND_ACCOUNT_ID);

            assertThat(one).isEqualTo(other);
            assertThat(other).isEqualTo(one);
            assertThat(one).hasSameHashCodeAs(other);
        }

        @Test
        @DisplayName("two rows with different card numbers are unequal even when both resolved "
                + "identifiers match, which is the non-unique case the alternate index exists for")
        void differentCardNumbersMeanUnequalEvenWhenTheRestMatches() {
            final CardCrossReference one = firstRow();
            final CardCrossReference other = new CardCrossReference(
                    SECOND_CARD_NUMBER, FIRST_CUSTOMER_ID, FIRST_ACCOUNT_ID);

            assertThat(one).isNotEqualTo(other);
            assertThat(other.getXrefCustId()).isEqualTo(one.getXrefCustId());
            assertThat(other.getXrefAcctId()).isEqualTo(one.getXrefAcctId());
        }

        @Test
        @DisplayName("a key written without its leading zero is not the same key")
        void aKeyWithoutItsLeadingZeroIsNotTheSameKey() {
            final CardCrossReference row = firstRow();
            final CardCrossReference shortened = new CardCrossReference(
                    FIRST_CARD_NUMBER.substring(1), FIRST_CUSTOMER_ID, FIRST_ACCOUNT_ID);

            assertThat(row).isNotEqualTo(shortened);
        }

        @Test
        @DisplayName("nothing of another type is equal to a cross reference, and neither is an absent "
                + "reference")
        void nothingOfAnotherTypeIsEqual() {
            final CardCrossReference row = firstRow();

            // Called directly rather than through the assertion's own equality so that the type check
            // and the absent-argument path are both genuinely exercised.
            assertThat(row.equals(null)).isFalse();
            assertThat(row.equals(FIRST_CARD_NUMBER)).isFalse();
        }

        @Test
        @DisplayName("the hash code is derived from the key alone, so it survives a change to either "
                + "resolved identifier")
        void theHashCodeSurvivesAChangeToANonKeyAttribute() {
            final CardCrossReference row = firstRow();
            final int before = row.hashCode();

            row.setXrefCustId(SECOND_CUSTOMER_ID);
            row.setXrefAcctId(SECOND_ACCOUNT_ID);

            assertThat(row.hashCode()).isEqualTo(before);
        }

        @Test
        @DisplayName("an unpopulated instance hashes without failing, which is what lets a provider "
                + "hold one before the key is assigned")
        void anUnpopulatedInstanceHashesWithoutFailing() {
            assertThat(new CardCrossReference().hashCode()).isZero();
            assertThat(new CardCrossReference()).isEqualTo(new CardCrossReference());
        }
    }

    /** The diagnostic rendering, and the primary account number it withholds. */
    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("the rendering discloses no card number, whole or partial")
        void theRenderingDisclosesNoCardNumber() {
            final String rendered = firstRow().toString();

            assertThat(rendered).doesNotContain(FIRST_CARD_NUMBER);
            assertThat(rendered).doesNotContain(FIRST_CARD_NUMBER.substring(0, 6));
            assertThat(rendered).doesNotContain(
                    FIRST_CARD_NUMBER.substring(FIRST_CARD_NUMBER.length() - 4));
            assertThat(rendered).contains(REDACTION);
        }

        @Test
        @DisplayName("the rendering names the entity and both resolved identifiers, so a diagnostic "
                + "still says which account and which customer a row points at")
        void theRenderingNamesBothResolvedIdentifiers() {
            final String rendered = firstRow().toString();

            assertThat(rendered).startsWith("CardCrossReference[");
            assertThat(rendered).endsWith("]");
            assertThat(rendered).contains(FIRST_CUSTOMER_ID);
            assertThat(rendered).contains(FIRST_ACCOUNT_ID);
        }

        @Test
        @DisplayName("the resolved identifiers are rendered untrimmed, so significant leading zeros "
                + "stay visible")
        void theResolvedIdentifiersAreRenderedUntrimmed() {
            final String rendered = secondRow().toString();

            assertThat(rendered).contains("'" + SECOND_CUSTOMER_ID + "'");
            assertThat(rendered).contains("'" + SECOND_ACCOUNT_ID + "'");
        }

        @Test
        @DisplayName("an unpopulated instance renders without failing")
        void anUnpopulatedInstanceRendersWithoutFailing() {
            final String rendered = new CardCrossReference().toString();

            assertThat(rendered).startsWith("CardCrossReference[").contains(REDACTION);
        }
    }

    /** The two leading rows of the reference data, carried end to end. */
    @Nested
    @DisplayName("seeded reference rows")
    class SeededReferenceRows {

        @ParameterizedTest(name = "the row keyed {0} resolves to customer {1} and account {2}")
        @CsvSource({
            "0500024453765740,000000050,00000000050",
            "0683586198171516,000000027,00000000027",
        })
        @DisplayName("each leading reference row round-trips its 16-byte key and both resolved "
                + "identifiers at their declared widths")
        void eachLeadingRowRoundTripsAtItsDeclaredWidths(final String cardNumber,
                final String customerId, final String accountId) {
            final CardCrossReference row =
                    new CardCrossReference(cardNumber, customerId, accountId);

            assertThat(row.getXrefCardNum()).isEqualTo(cardNumber).hasSize(CARD_NUMBER_WIDTH);
            assertThat(row.getXrefCustId()).isEqualTo(customerId).hasSize(CUSTOMER_ID_WIDTH);
            assertThat(row.getXrefAcctId()).isEqualTo(accountId).hasSize(ACCOUNT_ID_WIDTH);
            assertThat(dataPrefix(cardNumber, customerId, accountId)).hasSize(DATA_WIDTH);
        }

        @Test
        @DisplayName("the two leading rows are distinct rows resolving to distinct accounts")
        void theTwoLeadingRowsAreDistinct() {
            assertThat(firstRow()).isNotEqualTo(secondRow());
            assertThat(firstRow().getXrefAcctId()).isNotEqualTo(secondRow().getXrefAcctId());
            assertThat(firstRow().getXrefCustId()).isNotEqualTo(secondRow().getXrefCustId());
        }

        @Test
        @DisplayName("the reference data's 50 rows are the count both forms of the file agree on")
        void theReferenceDataCarriesFiftyRows() {
            assertThat(TEXT_FIXTURE_BYTE_COUNT / (DATA_WIDTH + LINE_TERMINATOR_WIDTH))
                    .isEqualTo(SEEDED_ROW_COUNT);
            assertThat(FIXED_LENGTH_DATASET_BYTE_COUNT / RECORD_WIDTH).isEqualTo(SEEDED_ROW_COUNT);
        }
    }
}
