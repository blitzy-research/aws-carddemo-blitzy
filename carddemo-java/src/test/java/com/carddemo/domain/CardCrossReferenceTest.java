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
 * carries only 36 bytes of information.
 *
 * <p>The legacy record is declared in copybook {@code app/cpy/CVACT03Y.cpy}, whose own header names a
 * record length of 50 and whose body declares four items in this order: a 16-byte card number, a
 * 9-digit customer identifier, an 11-digit account identifier and an unnamed 14-byte trailing filler.
 * Three of those four items carry information, so the entity declares exactly three attributes and the
 * filler is represented by no attribute, no column and no accessor. This row is the resolution point
 * for every card-to-account and card-to-customer hop in the estate, which is why ten programs include
 * the copybook.
 *
 * <p><strong>The geometry is corroborated three times over, independently of the copybook.</strong> The
 * cluster definition in {@code app/jcl/XREFFILE.jcl} declares {@code KEYS(16 0)} with
 * {@code RECORDSIZE(50 50)} on an indexed cluster, which fixes both the key width and the record
 * length; the {@code CXACAIX} alternate index defined in the same job stream declares
 * {@code KEYS(11 25)} with {@code NONUNIQUEKEY} and {@code UPGRADE}, which fixes the account
 * identifier's width at 11 and its offset at 25 without reference to any field declaration; and the two
 * validation artefacts measure 1,850 and 2,500 bytes for the same 50 rows, a difference that resolves
 * only under this layout. Every one of those figures was measured or read directly and then written
 * below as a constant.
 *
 * <p><strong>36 versus 50 is the sharpest trap in this record, and this suite deliberately refuses to
 * resolve it.</strong> The text artefact {@code app/data/ASCII/cardxref.txt} measures 1,850 bytes,
 * which is 50 rows of 36 data bytes plus one terminator each, because the text form omits the filler
 * altogether; the fixed-length dataset {@code app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS} measures
 * 2,500 bytes, which is the same 50 rows at the full 50-byte record length with the filler present.
 * Both forms are legitimate and neither is padded or trimmed into the other anywhere below. The reason
 * is that this entity is width-agnostic: it holds three text attributes and knows nothing of offsets,
 * strides or padding. Deciding which width an input carries belongs to the fixed-width mapper in the
 * utility layer, and that decision is verified by the mapper's own test; reproducing it here would make
 * the mapper this suite's oracle instead of the copybook. No assertion below builds a record image,
 * slices one, or converts either width into the other. The two measurements appear only as layout
 * evidence, in the arithmetic that shows what the 14-byte difference per row consists of. The stride
 * difference is a documented layout fact recorded in {@code docs/decision-log.md}, never a defect to
 * repair.
 *
 * <p><strong>Every expectation here is hand-derived.</strong> Widths, offsets, counts and row literals
 * come from the copybook, the cluster definition and a direct reading of the reference file, and each is
 * written below as a constant. No assertion calls a production method to produce the value it then
 * checks, nothing is snapshotted from an earlier run, and nothing compares a result with itself. The
 * suite is a pure unit test: it touches no container, application context, persistence context,
 * database, queue, network or file, and it uses no reflection of any kind, which is what keeps the
 * module's zero budget for low-level introspective access intact on the test side as well as the
 * production side.
 *
 * <p><strong>Two facts this suite records but deliberately does not assert.</strong> First, the
 * schema constrains all three of this table's columns: {@code V2__create_indexes.sql} creates six
 * foreign keys in total, of which three originate here - one from the card number towards the card
 * table, one from the account identifier towards the account table and one from the customer identifier
 * towards the customer table. That makes this the most heavily constrained table in the schema, and any
 * statement that it carries no foreign key is simply wrong. Those constraints are nonetheless database
 * behaviour rather than entity behaviour, and the entity declares no association in either direction,
 * so nothing below asserts on them; they are verified where they live, by inserting an orphan row
 * against a real database in the integration tier. Second, column names, column widths and nullability
 * are likewise unasserted here, because the provider runs in validate mode on every profile and any
 * divergence between a mapping and the migration fails start-up outright - a stronger check than an
 * annotation inspection, and one that needs no reflection to perform.
 *
 * <p>Provenance: the layout facts cited above were read from the estate at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, whose members carry the upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19; that stamp appears in the trailer of the
 * copybook this entity translates. No legacy source text is reproduced here - member names, field
 * names, widths, offsets and counts are cited only, and the legacy tree is read-only reference that is
 * never copied into this module.
 */
@DisplayName("CardCrossReference: the 50-byte CVACT03Y row that carries 36 data bytes")
class CardCrossReferenceTest {

    /** Width of the card number field, which is also the whole of the cluster key. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** Width of the customer identifier field. */
    private static final int CUSTOMER_ID_WIDTH = 9;

    /** Width of the account identifier field, which is also the alternate index key width. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** Width of the trailing filler, which no attribute, column or accessor represents. */
    private static final int FILLER_WIDTH = 14;

    /** Leading bytes that carry information: the record length less the filler. */
    private static final int DATA_WIDTH = 36;

    /** Record length the copybook header and the cluster definition both declare. */
    private static final int RECORD_WIDTH = 50;

    /** Offset of the card number, which is also the cluster key offset. */
    private static final int CARD_NUMBER_OFFSET = 0;

    /** Offset of the customer identifier. */
    private static final int CUSTOMER_ID_OFFSET = 16;

    /** Offset of the account identifier, which is also the alternate index key offset. */
    private static final int ACCOUNT_ID_OFFSET = 25;

    /** Offset at which the trailing filler begins, which is where the mapped data ends. */
    private static final int FILLER_OFFSET = 36;

    /** Rows the reference data carries, identical in the text and the fixed-length form. */
    private static final int SEEDED_ROW_COUNT = 50;

    /** Measured size of the text artefact, which writes only the mapped prefix of each row. */
    private static final int TEXT_ARTEFACT_BYTE_COUNT = 1850;

    /** Measured size of the fixed-length dataset holding the same rows at the full record length. */
    private static final int FIXED_LENGTH_DATASET_BYTE_COUNT = 2500;

    /** Bytes a text row spends on its terminator. */
    private static final int LINE_TERMINATOR_WIDTH = 1;

    /** Card number of the first reference row, read directly from the text artefact. */
    private static final String FIRST_CARD_NUMBER = "0500024453765740";

    /** Customer identifier of the first reference row, leading zeros included. */
    private static final String FIRST_CUSTOMER_ID = "000000050";

    /** Account identifier of the first reference row, leading zeros included. */
    private static final String FIRST_ACCOUNT_ID = "00000000050";

    /** Card number of the second reference row, read directly from the text artefact. */
    private static final String SECOND_CARD_NUMBER = "0683586198171516";

    /** Customer identifier of the second reference row, leading zeros included. */
    private static final String SECOND_CUSTOMER_ID = "000000027";

    /** Account identifier of the second reference row, leading zeros included. */
    private static final String SECOND_ACCOUNT_ID = "00000000027";

    /**
     * The significant digits both identifiers of the first row reduce to once their leading zeros are
     * discarded, which is the value a numeric attribute would have returned in place of either.
     */
    private static final String SIGNIFICANT_DIGITS_ONLY = "50";

    /**
     * Builds the first reference row as an entity.
     *
     * @return a cross reference carrying the three values of the first reference row
     */
    private static CardCrossReference firstRow() {
        return new CardCrossReference(FIRST_CARD_NUMBER, FIRST_CUSTOMER_ID, FIRST_ACCOUNT_ID);
    }

    /**
     * Builds the second reference row as an entity.
     *
     * @return a cross reference carrying the three values of the second reference row
     */
    private static CardCrossReference secondRow() {
        return new CardCrossReference(SECOND_CARD_NUMBER, SECOND_CUSTOMER_ID, SECOND_ACCOUNT_ID);
    }

    /**
     * Measures a value the way the record measures it, in encoded bytes rather than in characters.
     *
     * <p>Field widths in this layout are byte widths, so every width assertion below goes through this
     * method and none through a character count. The two agree for the digits and spaces these fields
     * actually carry, and that is exactly why the distinction has to be made deliberately: a character
     * count would keep agreeing right up to the first value that encodes to more bytes than it has
     * characters, and would then report a field as being the correct width while the record it belongs
     * to had overflowed.
     *
     * @param value the attribute value to measure, which must not be absent
     * @return the number of bytes the value occupies when encoded as single-byte characters
     */
    private static int asciiWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * The geometry the copybook, the cluster definition and the alternate index definition agree on.
     *
     * <p>These assertions carry no expectation about how a record is read. They state what the layout
     * is: four widths, four offsets, a mapped total and a declared total, with the difference between
     * the last two accounted for exactly once. They are factual layout evidence and nothing here is a
     * threshold, a budget or a performance figure.
     */
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

    /**
     * What the constructors carry, in the order the copybook declares.
     */
    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("the three-argument constructor takes the card number, then the customer "
                + "identifier, then the account identifier, in copybook declaration order")
        void theConstructorTakesItsArgumentsInCopybookOrder() {
            final CardCrossReference row = firstRow();

            assertThat(row.getXrefCardNum()).isEqualTo(FIRST_CARD_NUMBER);
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
            // The entity's no-argument constructor is protected, and this test reaches it by ordinary
            // Java package access: the test class is declared in com.carddemo.domain, the same package
            // as the entity, and a protected member is visible to its own package. This is explicitly
            // NOT reflection - no member is looked up by name, no accessibility flag is overridden, and
            // no introspective platform API takes any part in it. The module's audited budget for
            // low-level introspective access is zero, and a test that reached this constructor
            // reflectively would undermine that count on the very class it was meant to protect.
            final CardCrossReference empty = new CardCrossReference();

            assertThat(empty.getXrefCardNum()).isNull();
            assertThat(empty.getXrefCustId()).isNull();
            assertThat(empty.getXrefAcctId()).isNull();
        }
    }

    /**
     * What the mutators carry, and what they refuse to change on the way through.
     *
     * <p>Each mutator is a plain assignment. Nothing trims, strips, pads, folds case, normalises,
     * validates or scales, and these assertions exist to keep it that way: a mutator that silently
     * repaired a caller's value would hide the mistake behind a plausible key, and a mutator that
     * rejected one would refuse input the legacy system accepts.
     */
    @Nested
    @DisplayName("attribute carriage")
    class AttributeCarriage {

        @Test
        @DisplayName("the card number mutator round-trips the key field exactly and leaves both "
                + "resolved identifiers untouched")
        void theCardNumberMutatorRoundTripsTheKeyAlone() {
            final CardCrossReference row = firstRow();

            row.setXrefCardNum(SECOND_CARD_NUMBER);

            assertThat(row.getXrefCardNum()).isEqualTo(SECOND_CARD_NUMBER);
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
            assertThat(row.getXrefCardNum()).isEqualTo(FIRST_CARD_NUMBER);
            assertThat(row.getXrefAcctId()).isEqualTo(FIRST_ACCOUNT_ID);
        }

        @Test
        @DisplayName("the account identifier mutator round-trips its own field exactly and touches no "
                + "other, so the column the account-scoped finder queries is never normalised")
        void theAccountIdentifierMutatorRoundTripsItsOwnFieldAlone() {
            final CardCrossReference row = firstRow();

            row.setXrefAcctId(SECOND_ACCOUNT_ID);

            assertThat(row.getXrefAcctId()).isEqualTo(SECOND_ACCOUNT_ID);
            assertThat(row.getXrefCardNum()).isEqualTo(FIRST_CARD_NUMBER);
            assertThat(row.getXrefCustId()).isEqualTo(FIRST_CUSTOMER_ID);
        }

        @Test
        @DisplayName("a value carrying surrounding blanks is stored and returned untouched, because no "
                + "accessor trims, strips, pads or folds anything")
        void aValueCarryingBlanksIsStoredUntouched() {
            // Two leading blanks, ten digits and two trailing blanks: fourteen bytes, counted by hand
            // so the expected width owes nothing to the class under test.
            final String blankBearing = "  0500024453  ";
            final int blankBearingWidth = 14;

            final CardCrossReference row =
                    new CardCrossReference(blankBearing, blankBearing, blankBearing);

            assertThat(row.getXrefCardNum()).isEqualTo(blankBearing);
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

            assertThat(row.getXrefCardNum()).isEqualTo(mixedCase);
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

    /**
     * The external widths the record publishes, and the leading zeros that make them contractual.
     *
     * <p>Widths are asserted in encoded bytes throughout, never in characters, because the record's
     * field widths are byte widths.
     */
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

            assertThat(row.getXrefCardNum()).startsWith("0");
            assertThat(asciiWidth(row.getXrefCardNum())).isEqualTo(CARD_NUMBER_WIDTH);
        }
    }

    /**
     * Row identity, which is the card number and nothing else.
     *
     * <p>The key is the 16-byte card number the cluster declares at offset 0. The two resolved
     * identifiers are excluded from identity deliberately: they are mutable, so including them would
     * let an instance's equality and hash change while it sat in a hashed collection, and they are not
     * unique, since many cards resolve to one account and to one customer - which is precisely why the
     * alternate index over the account identifier is declared non-unique.
     */
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

            // Both non-key attributes are checked against the hand-derived row values rather than
            // against each other, so the premise of the test - that the two instances really do differ
            // in both of them - is stated independently of the class under test.
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

            // Both instances are checked against the same hand-derived row values, rather than against
            // each other, so the premise - that everything except the key matches - rests on the
            // reference data and not on the class under test.
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

            // Both comparisons call equals directly rather than going through an assertion's own
            // equality, so the absent-argument path and the type-check path are genuinely exercised.
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
     * Facts about this entity that are proved by what it does not declare.
     *
     * <p>Both tests below establish an absence, and both do it without introspection. Nothing here
     * enumerates a member, reads an annotation or looks anything up by name: an absence is proved at
     * compile time by the fact that no such member is referenced anywhere in this file, and behaviourally
     * by the consequence the absence has. A reflective probe would prove less and would spend a budget
     * this module holds at zero.
     */
    @Nested
    @DisplayName("documented absences")
    class DocumentedAbsences {

        @Test
        @DisplayName("no surrogate identifier exists: the 16-byte card number the cluster declares at "
                + "offset 0 is itself the identifier, with no generated value participating")
        void noSurrogateIdentifierExists() {
            // Compile-time part of the proof: this file names no generated-identifier accessor anywhere.
            // If one existed it could have been referenced here and the file would still compile, so the
            // absence of any such reference across the whole suite is itself the evidence. No reflection
            // is used to establish it.
            //
            // Behavioural part of the proof: were a machine-assigned identifier participating in
            // identity, two independently constructed instances carrying identical business data would
            // hold different surrogate values and would therefore not be equal. They are equal, and they
            // are equal to an instance built by the other available route as well, so identity is
            // determined entirely by caller-supplied business data.
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
        void noFillerPropertyExists() {
            // The three accessors exercised below are the complete set of business accessors this entity
            // publishes. The record's fourth declared item carries no information and is represented by
            // no attribute, no column and no accessor, which is why the mapped total stops at 36 while
            // the declared record length continues to 50. Established by exercising the accessors that
            // do exist rather than by enumerating members reflectively.
            final CardCrossReference row = firstRow();

            assertThat(row.getXrefCardNum()).isEqualTo(FIRST_CARD_NUMBER);
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
     * The diagnostic rendering, and the primary account number it withholds.
     *
     * <p><strong>A divergence between this suite's brief and the class it tests, recorded here rather
     * than left implicit.</strong> The contract summary this suite was written against anticipated that
     * the entity might publish no diagnostic rendering at all, and directed that none be assumed. The
     * class as actually written does publish one, and it is not incidental: it deliberately withholds
     * the card number, because that value is a primary account number and a rendering escapes into a
     * failed assertion message, a provider diagnostic or a log event without anybody choosing to
     * disclose it. The class is authoritative on what it declares, so the member is exercised here.
     *
     * <p>What is asserted is confined to the disclosure guarantee the class documents - that no card
     * number, whole or partial, reaches the rendering, and that both resolved identifiers do reach it
     * with their significant leading zeros intact. The exact wording, punctuation and stand-in text are
     * deliberately <em>not</em> pinned: they are a presentation choice that may change without changing
     * behaviour, and an assertion over them would break on a harmless edit while proving nothing about
     * the guarantee that matters. Nothing here is compared against a captured earlier output.
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
        @DisplayName("the rendering names both resolved identifiers, so a diagnostic can still say which "
                + "customer and which account a row points at")
        void theRenderingNamesBothResolvedIdentifiers() {
            final String rendered = secondRow().toString();

            assertThat(rendered)
                    .as("an internal customer key names no cardholder and reveals no instrument")
                    .contains(SECOND_CUSTOMER_ID);
            assertThat(rendered)
                    .as("neither does an internal account key")
                    .contains(SECOND_ACCOUNT_ID);
        }

        @Test
        @DisplayName("the resolved identifiers are rendered at their full 9 and 11 byte widths, so "
                + "significant leading zeros stay visible in a diagnostic")
        void theResolvedIdentifiersAreRenderedUntrimmed() {
            final String rendered = firstRow().toString();

            assertThat(rendered).contains(FIRST_CUSTOMER_ID);
            assertThat(rendered).contains(FIRST_ACCOUNT_ID);
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

    /**
     * The two leading rows of the reference data, carried end to end.
     *
     * <p>Both rows were read directly from the text artefact and sliced by hand at the copybook offsets
     * before being written into the table below, so the table is an independent statement of what the
     * reference data holds rather than a record of what any code produced.
     */
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

            assertThat(row.getXrefCardNum()).isEqualTo(cardNumber);
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
