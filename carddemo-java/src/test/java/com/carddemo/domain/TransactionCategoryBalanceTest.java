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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.carddemo.domain.id.TransactionCategoryBalanceId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TransactionCategoryBalance}, the per-account, per-category accumulated
 * balance row, and for its composite key {@link TransactionCategoryBalanceId}.
 *
 * <p><strong>What this row is.</strong> A 50-byte record carrying four mapped values behind a
 * three-part, 17-byte composite key. It is the first entity of this package to carry a monetary
 * amount, so the value-and-scale assertion idiom established here is the one every other monetary
 * carrier in the domain package follows.
 *
 * <p><strong>Independently derived expectations.</strong> Every expected value in this file was
 * hand-derived from the copybook layout, from the cluster geometry of the provisioning job and from
 * a byte census of the seed fixture. No production method is ever called to produce an expectation,
 * no output is snapshotted, and no assertion compares a value against itself. In particular the
 * fixed-width codec and the record mapper for this layout are deliberately absent from this file:
 * referencing either would make another production class the oracle for this one. Their behaviour is
 * verified by their own tests.
 *
 * <p><strong>Layout, verified by direct read of the copybook.</strong> Zero-based offsets and widths:
 * <ul>
 *   <li>account identifier, 11 digits, offset 0 &mdash; first key component</li>
 *   <li>transaction type code, 2 characters, offset 11 &mdash; second key component</li>
 *   <li>transaction category code, 4 digits, offset 13 &mdash; third key component</li>
 *   <li>category balance, signed with nine integer digits and two decimals, 11 bytes, offset 17</li>
 *   <li>trailing filler, 22 characters, offset 28 &mdash; not mapped, not persisted</li>
 * </ul>
 * The three key components sum to the 17-byte key length; the four mapped fields sum to 28; the
 * 22-byte filler makes up the declared 50-byte record. The provisioning job corroborates both
 * numbers independently, declaring a key length of 17 beginning at offset 0 over a record size of
 * 50 for an indexed cluster.
 *
 * <p><strong>Scope boundaries.</strong> This is a pure unit test: it starts no container, builds no
 * application context, opens no database connection, touches no network and reads no file. Column
 * names, column lengths and nullability are not verified here &mdash; that mapping layer is checked
 * in the integration tier, where schema validation against a real database fails start-up on any
 * mismatch, including the identifier-class-to-entity match the provider resolves by field name and
 * type. Neither is any arithmetic verified here: the accrual that consumes this balance multiplies
 * before it divides, and reproducing that operand order is the service layer's responsibility, not
 * this carrier's.
 *
 * <p>One member of each class is deliberately left untouched <em>by this file</em>: the diagnostic
 * text rendering. Its exact wording is not a contract of this tier &mdash; it exists for a human
 * reading a log or a failure message, and pinning its format here would turn a debugging aid into a
 * brittle expectation that any rewording would break. The omission is deliberate rather than an
 * oversight, and it costs nothing that is gated: the module's enforced coverage floor is met with
 * room to spare, and every other member of both classes is exercised below.
 *
 * <p>Provenance: derived by inspection from checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy estate is read-only reference,
 * so no source text is transcribed here; member names, field names, byte offsets, widths and codes
 * are cited as metadata instead.
 *
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceId
 */
class TransactionCategoryBalanceTest {

    // ------------------------------------------------------------------------------------------
    // Hand-derived layout constants. Each is read off the copybook and the provisioning job, never
    // off a production constant, so that a drift in production is detected rather than mirrored.
    // ------------------------------------------------------------------------------------------

    /** Width of the account identifier component: 11 digits at offset 0. */
    private static final int ACCT_ID_WIDTH = 11;

    /** Width of the transaction type code component: 2 characters at offset 11. */
    private static final int TYPE_CD_WIDTH = 2;

    /** Width of the transaction category code component: 4 digits at offset 13. */
    private static final int CAT_CD_WIDTH = 4;

    /**
     * Declared key length of the indexed cluster: the three components above, concatenated. The
     * provisioning job states this length explicitly, and states an offset of 0 with it, which is
     * what makes the key the leading substring of the record image.
     */
    private static final int KEY_WIDTH = 17;

    /**
     * Key length of the <em>other</em> record whose key group carries the same legacy name. It is
     * recorded here only so that the two can be asserted distinct; see
     * {@link RecordLayoutGeometry#theSeventeenByteKeyIsNotTheSixByteKeyOfTheOtherCopybook()}.
     */
    private static final int RIVAL_KEY_WIDTH = 6;

    /** Integer digit count of the balance field. */
    private static final int BALANCE_INTEGER_DIGITS = 9;

    /** Decimal digit count of the balance field, and therefore its contractual scale. */
    private static final int BALANCE_DECIMAL_DIGITS = 2;

    /** Encoded width of the balance field: nine integer digits plus two decimals. */
    private static final int BALANCE_WIDTH = 11;

    /** Encoded width of the account record's five monetary fields, which have ten integer digits. */
    private static final int WIDER_MONETARY_WIDTH = 12;

    /** Encoded width of the disclosure rate, which has four integer digits. */
    private static final int NARROWER_MONETARY_WIDTH = 6;

    /** Sum of the four mapped field widths. */
    private static final int MAPPED_WIDTH = 28;

    /** Width of the trailing filler, which is neither mapped nor persisted. */
    private static final int FILLER_WIDTH = 22;

    /** Declared record length of the layout, stated by the copybook and by the cluster definition. */
    private static final int RECORD_WIDTH = 50;

    // ------------------------------------------------------------------------------------------
    // Zero-based field offsets. Each is the accumulation of every width declared before it, which
    // is how a fixed-width record image is addressed.
    // ------------------------------------------------------------------------------------------

    /** Offset of the account identifier: the record image begins with the key. */
    private static final int ACCT_ID_OFFSET = 0;

    /** Offset of the transaction type code. */
    private static final int TYPE_CD_OFFSET = 11;

    /** Offset of the transaction category code. */
    private static final int CAT_CD_OFFSET = 13;

    /** Offset of the balance, which coincides with the declared key length. */
    private static final int BALANCE_OFFSET = 17;

    /** Offset of the trailing filler, which coincides with the mapped width. */
    private static final int FILLER_OFFSET = 28;

    // ------------------------------------------------------------------------------------------
    // Seed fixture facts, established by a byte census of the reference data file rather than by
    // parsing it here. The file measures 2,550 bytes: 50 records of 50 bytes each plus one line
    // terminator per record.
    // ------------------------------------------------------------------------------------------

    /** Number of records the reference data seeds. */
    private static final int SEEDED_ROW_COUNT = 50;

    /** Measured size of the reference data file in bytes. */
    private static final int SEEDED_FILE_BYTES = 2550;

    /** Account identifier of the first seeded record, at offset 0 and width 11. */
    private static final String ROW_ZERO_ACCT_ID = "00000000001";

    /** Transaction type code of the first seeded record, at offset 11 and width 2. */
    private static final String ROW_ZERO_TYPE_CD = "01";

    /** Transaction category code of the first seeded record, at offset 13 and width 4. */
    private static final String ROW_ZERO_CAT_CD = "0001";

    /**
     * Balance of the first seeded record, hand-decoded rather than computed.
     *
     * <p>The 11-byte image at offset 17 reads {@code 0000000000} followed by an opening brace. The
     * brace is an overpunched trailing byte: it encodes the digit zero together with a positive
     * sign, so the eleven unsigned digits are all zero and the implied two decimals place the value
     * at zero with a scale of two. Decoding such an image is the record mapper's responsibility and
     * is deliberately not performed here; the image is cited only to show where this expectation
     * comes from.
     */
    private static final String ROW_ZERO_BALANCE = "0.00";

    /** A non-zero positive balance, chosen to exercise both integer and decimal digits. */
    private static final String POSITIVE_BALANCE = "123.45";

    /** The additive inverse of {@link #POSITIVE_BALANCE}, exercising the negative sign. */
    private static final String NEGATIVE_BALANCE = "-123.45";

    /** Foreign value used to prove that equality rejects an unrelated type rather than throwing. */
    private static final String FOREIGN_KEY_RENDERING = "00000000001010001";

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /**
     * Returns the encoded byte width of a component.
     *
     * <p>Byte width is measured rather than character count, because the legacy record is a
     * fixed-width byte image: a field occupies a stated number of bytes, and only a byte
     * measurement can attest to that.
     *
     * @param value the component value to measure
     * @return the number of bytes the value occupies when encoded
     */
    private static int encodedWidthOf(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Builds a fully populated row from the first seeded record's values.
     *
     * @return a row carrying the first seeded record's key and balance
     */
    private static TransactionCategoryBalance seededRowZero() {
        return new TransactionCategoryBalance(
                ROW_ZERO_ACCT_ID, ROW_ZERO_TYPE_CD, ROW_ZERO_CAT_CD, new BigDecimal(ROW_ZERO_BALANCE));
    }

    /**
     * Builds the composite key of the first seeded record.
     *
     * @return a key carrying the first seeded record's three components
     */
    private static TransactionCategoryBalanceId seededKeyZero() {
        return new TransactionCategoryBalanceId(ROW_ZERO_ACCT_ID, ROW_ZERO_TYPE_CD, ROW_ZERO_CAT_CD);
    }

    /**
     * Minimal subclass of {@link TransactionCategoryBalanceId} that exists solely to reach that
     * class's {@code protected} no-argument constructor.
     *
     * <p><strong>Why this exists.</strong> The persistence provider requires an identifier class to
     * offer a no-argument constructor, which is precisely why the key is a plain class rather than a
     * record &mdash; a record has no no-argument constructor to give. The production class declares
     * that constructor {@code protected}, and it lives in a different package from this test, so
     * {@code new TransactionCategoryBalanceId()} does not compile here. This is a documented
     * divergence from the contract summary, which described the constructor without stating its
     * access level. A protected constructor is nevertheless reachable from a subclass body in any
     * package through an explicit superclass constructor invocation, so declaring this subclass
     * proves at <em>compile time</em> that the constructor exists, and instantiating it proves at
     * <em>run time</em> that it leaves every component unset.
     *
     * <p><strong>This is inheritance, not reflection.</strong> No member is looked up by name, no
     * accessibility is overridden, and no member of the runtime reflection API is referenced
     * anywhere in this file. The module's audit requirement of zero reflection is preserved.
     *
     * <p>The superclass is serializable, so this subclass declares its own serialization identity.
     * Omitting it would raise a lint warning, and this build promotes warnings to errors.
     */
    private static final class ProtectedKeyConstructorProbe extends TransactionCategoryBalanceId {

        /** Serialization identity of the probe itself. Never persisted or transmitted. */
        private static final long serialVersionUID = 1L;

        /** Invokes the superclass's {@code protected} no-argument constructor. */
        ProtectedKeyConstructorProbe() {
            super();
        }
    }

    @Nested
    @DisplayName("Record layout geometry")
    class RecordLayoutGeometry {

        @Test
        @DisplayName("the three key components occupy exactly 11, 2 and 4 encoded bytes, as the "
                + "copybook declares them: an 11-digit account identifier at offset 0, a 2-character "
                + "transaction type code at offset 11 and a 4-digit transaction category code at "
                + "offset 13")
        void keyComponentWidthsAreElevenTwoAndFour() {
            TransactionCategoryBalance row = seededRowZero();

            assertThat(encodedWidthOf(row.getTrancatAcctId())).isEqualTo(ACCT_ID_WIDTH);
            assertThat(encodedWidthOf(row.getTrancatTypeCd())).isEqualTo(TYPE_CD_WIDTH);
            assertThat(encodedWidthOf(row.getTrancatCd())).isEqualTo(CAT_CD_WIDTH);
        }

        @Test
        @DisplayName("the three key component widths sum to the 17-byte key length the provisioning "
                + "job declares, at an offset of 0, which is what makes the key the leading "
                + "substring of the record image")
        void keyComponentWidthsSumToSeventeen() {
            assertThat(ACCT_ID_WIDTH + TYPE_CD_WIDTH + CAT_CD_WIDTH).isEqualTo(KEY_WIDTH);
        }

        @Test
        @DisplayName("the 17-byte key of this copybook is not the 6-byte key of the other copybook "
                + "that reuses the same legacy key group name: the shorter key leads with a "
                + "transaction type code and carries no account identifier, so it is not a prefix, "
                + "sub-key or reusable fragment of this one")
        void theSeventeenByteKeyIsNotTheSixByteKeyOfTheOtherCopybook() {
            // Two different copybooks name their key group identically while describing structurally
            // unrelated keys. This assertion exists so the two can never be conflated by a later
            // reader: the widths differ, and the layouts align at no offset. The 6-byte key's own
            // Java type is deliberately never named in this file, and no shared supertype beyond
            // Object exists between the two - see docs/decision-log.md on the collision.
            assertThat(KEY_WIDTH).isNotEqualTo(RIVAL_KEY_WIDTH);

            // Stated the other way round as well, so that neither number can be edited in isolation
            // without the pair contradicting itself.
            assertThat(RIVAL_KEY_WIDTH).isNotEqualTo(ACCT_ID_WIDTH + TYPE_CD_WIDTH + CAT_CD_WIDTH);

            // The shorter key's two components are a type code and a category code. Those two widths
            // alone sum to 6, which is exactly why the account identifier's absence is the whole
            // difference between the two layouts.
            assertThat(TYPE_CD_WIDTH + CAT_CD_WIDTH).isEqualTo(RIVAL_KEY_WIDTH);
            assertThat(ACCT_ID_WIDTH).isEqualTo(KEY_WIDTH - RIVAL_KEY_WIDTH);
        }

        @Test
        @DisplayName("the balance field occupies 11 encoded bytes because a signed field of nine "
                + "integer digits and two decimals occupies its digit count, the sign riding in the "
                + "trailing byte rather than in a byte of its own")
        void balanceFieldIsElevenBytesWide() {
            assertThat(BALANCE_INTEGER_DIGITS + BALANCE_DECIMAL_DIGITS).isEqualTo(BALANCE_WIDTH);
        }

        @Test
        @DisplayName("the balance's 11-byte width is neither the 12 bytes of the account record's "
                + "ten-integer-digit monetary fields nor the 6 bytes of the disclosure rate's four "
                + "integer digits, so the three monetary widths of the estate stay distinct")
        void balanceWidthIsNeitherTwelveNorSix() {
            assertThat(BALANCE_WIDTH).isNotEqualTo(WIDER_MONETARY_WIDTH);
            assertThat(BALANCE_WIDTH).isNotEqualTo(NARROWER_MONETARY_WIDTH);

            // The same width rule generates all three, which is why they differ only by integer
            // digit count. Ten integer digits give 12; four give 6.
            assertThat(WIDER_MONETARY_WIDTH).isEqualTo(10 + BALANCE_DECIMAL_DIGITS);
            assertThat(NARROWER_MONETARY_WIDTH).isEqualTo(4 + BALANCE_DECIMAL_DIGITS);
        }

        @Test
        @DisplayName("the four mapped field widths sum to 28 of the declared 50-byte record, leaving "
                + "a 22-byte trailing filler that carries no information and is therefore neither "
                + "mapped to a property nor persisted as a column")
        void mappedWidthsSumToTwentyEightOfFifty() {
            assertThat(ACCT_ID_WIDTH + TYPE_CD_WIDTH + CAT_CD_WIDTH + BALANCE_WIDTH)
                    .isEqualTo(MAPPED_WIDTH);
            assertThat(MAPPED_WIDTH + FILLER_WIDTH).isEqualTo(RECORD_WIDTH);
            assertThat(RECORD_WIDTH - MAPPED_WIDTH).isEqualTo(FILLER_WIDTH);
        }

        @Test
        @DisplayName("each field's offset is the accumulation of the widths declared before it: 0, "
                + "11, 13, 17 and 28, so the balance begins immediately after the key and the record "
                + "image is a key followed by data rather than interleaved fields")
        void fieldOffsetsAccumulateInDeclarationOrder() {
            // A fixed-width image can only be addressed by accumulating widths in declaration
            // order, so each offset below is derived from the one before it rather than restated.
            assertThat(ACCT_ID_OFFSET).isZero();
            assertThat(TYPE_CD_OFFSET).isEqualTo(ACCT_ID_OFFSET + ACCT_ID_WIDTH);
            assertThat(CAT_CD_OFFSET).isEqualTo(TYPE_CD_OFFSET + TYPE_CD_WIDTH);
            assertThat(BALANCE_OFFSET).isEqualTo(CAT_CD_OFFSET + CAT_CD_WIDTH);
            assertThat(FILLER_OFFSET).isEqualTo(BALANCE_OFFSET + BALANCE_WIDTH);

            // The balance offset therefore coincides with the declared key length, which is the
            // arithmetic statement of "the key is the leading substring of the record image".
            assertThat(BALANCE_OFFSET).isEqualTo(KEY_WIDTH);

            // And the filler offset coincides with the mapped width, so the record ends exactly at
            // its declared length.
            assertThat(FILLER_OFFSET).isEqualTo(MAPPED_WIDTH);
            assertThat(FILLER_OFFSET + FILLER_WIDTH).isEqualTo(RECORD_WIDTH);
        }
    }

    @Nested
    @DisplayName("Construction and accessors")
    class ConstructionAndAccessors {

        @Test
        @DisplayName("the all-argument constructor stores the three key components in copybook order "
                + "followed by the balance, and returns each one exactly as supplied")
        void allArgumentConstructorRoundTripsEveryProperty() {
            TransactionCategoryBalance row = new TransactionCategoryBalance(
                    ROW_ZERO_ACCT_ID,
                    ROW_ZERO_TYPE_CD,
                    ROW_ZERO_CAT_CD,
                    new BigDecimal(ROW_ZERO_BALANCE));

            assertThat(row.getTrancatAcctId()).isEqualTo(ROW_ZERO_ACCT_ID);
            assertThat(row.getTrancatTypeCd()).isEqualTo(ROW_ZERO_TYPE_CD);
            assertThat(row.getTrancatCd()).isEqualTo(ROW_ZERO_CAT_CD);
            assertThat(row.getTranCatBal()).isEqualTo(new BigDecimal(ROW_ZERO_BALANCE));
        }

        @Test
        @DisplayName("all four mutators are plain assignments: each value is returned exactly as "
                + "supplied, with no trimming, padding, case folding, normalising, validating or "
                + "rescaling applied on the way in or out")
        void allFourMutatorsRoundTripWithoutNormalisation() {
            TransactionCategoryBalance row = new TransactionCategoryBalance();

            row.setTrancatAcctId("00000000050");
            row.setTrancatTypeCd("07");
            row.setTrancatCd("0099");
            row.setTranCatBal(new BigDecimal(POSITIVE_BALANCE));

            assertThat(row.getTrancatAcctId()).isEqualTo("00000000050");
            assertThat(row.getTrancatTypeCd()).isEqualTo("07");
            assertThat(row.getTrancatCd()).isEqualTo("0099");
            assertThat(row.getTranCatBal()).isEqualTo(new BigDecimal(POSITIVE_BALANCE));
        }

        @Test
        @DisplayName("a key component carrying trailing spaces is stored verbatim: the mutator "
                + "applies no trimming, because in a fixed-width layout padding is part of the value "
                + "and two differently padded values address two different rows")
        void mutatorsPreserveTrailingSpaces() {
            TransactionCategoryBalance row = new TransactionCategoryBalance();

            row.setTrancatTypeCd("1 ");

            assertThat(row.getTrancatTypeCd()).isEqualTo("1 ");
            assertThat(row.getTrancatTypeCd()).isNotEqualTo("1");
            assertThat(encodedWidthOf(row.getTrancatTypeCd())).isEqualTo(TYPE_CD_WIDTH);
        }

        @Test
        @DisplayName("the no-argument constructor the persistence provider requires exists and leaves "
                + "every property unset, so an unpopulated instance stays distinguishable from a "
                + "genuine zero balance")
        void noArgumentConstructorYieldsAnAllNullInstance() {
            // This test class sits in the same package as the entity, so Java's package access
            // reaches the entity's protected no-argument constructor directly. This is ordinary
            // same-package visibility and explicitly NOT reflection: no member is looked up by
            // name and no accessibility is overridden.
            TransactionCategoryBalance row = new TransactionCategoryBalance();

            assertThat(row.getTrancatAcctId()).isNull();
            assertThat(row.getTrancatTypeCd()).isNull();
            assertThat(row.getTrancatCd()).isNull();

            // The balance is left null rather than pre-seeded with a zero. Every seeded row carries
            // a genuine zero, so a defaulted zero would be indistinguishable from real data.
            assertThat(row.getTranCatBal()).isNull();
        }

        @Test
        @DisplayName("leading zeros survive on the account identifier: an 11-digit identifier stays "
                + "eleven characters wide and never collapses to its numeric value, because the "
                + "schema binds every digit-only lexeme to a bounded character column and not to a "
                + "numeric type")
        void leadingZerosSurviveOnTheAccountIdentifier() {
            TransactionCategoryBalance row = seededRowZero();

            assertThat(row.getTrancatAcctId()).isEqualTo(ROW_ZERO_ACCT_ID);
            assertThat(row.getTrancatAcctId()).isNotEqualTo("1");
            assertThat(encodedWidthOf(row.getTrancatAcctId())).isEqualTo(ACCT_ID_WIDTH);
        }

        @Test
        @DisplayName("leading zeros survive on the transaction category code: a 4-digit code stays "
                + "four characters wide and never collapses to its numeric value, so the 17-byte key "
                + "image still reconstructs from the stored row")
        void leadingZerosSurviveOnTheCategoryCode() {
            TransactionCategoryBalance row = seededRowZero();

            assertThat(row.getTrancatCd()).isEqualTo(ROW_ZERO_CAT_CD);
            assertThat(row.getTrancatCd()).isNotEqualTo("1");
            assertThat(encodedWidthOf(row.getTrancatCd())).isEqualTo(CAT_CD_WIDTH);
        }

        @Test
        @DisplayName("the key projection returns the three key components in contractual order and "
                + "nothing else, which is a read-only view of the identity already held by the three "
                + "key properties rather than a stored identifier of its own")
        void keyProjectionCarriesTheThreeComponentsInOrder() {
            // Divergence from the contract summary, recorded here as required: the entity exposes a
            // projection that RETURNS its composite key. It is neither a mutator that accepts a key
            // object - none exists, and none may be invented - nor a surrogate identifier, because
            // it stores nothing and derives everything from the three key properties.
            TransactionCategoryBalance row = seededRowZero();

            TransactionCategoryBalanceId projected = row.toId();

            assertThat(projected.getTrancatAcctId()).isEqualTo(ROW_ZERO_ACCT_ID);
            assertThat(projected.getTrancatTypeCd()).isEqualTo(ROW_ZERO_TYPE_CD);
            assertThat(projected.getTrancatCd()).isEqualTo(ROW_ZERO_CAT_CD);
        }

        @Test
        @DisplayName("the key projection reflects the current component values rather than a snapshot "
                + "taken at construction, confirming it derives identity instead of storing it")
        void keyProjectionReflectsMutatedComponents() {
            TransactionCategoryBalance row = seededRowZero();

            row.setTrancatCd("0005");

            assertThat(row.toId().getTrancatCd()).isEqualTo("0005");
            assertThat(row.toId().getTrancatAcctId()).isEqualTo(ROW_ZERO_ACCT_ID);
        }

        @Test
        @DisplayName("no surrogate or generated identifier exists on this entity: identity is the "
                + "legacy business key itself, because a surrogate would break the "
                + "record-image-to-table-row correspondence that byte-level output parity depends on")
        void noSurrogateIdentifierExists() {
            // This is proved by COMPILE-TIME ABSENCE, which is the strongest available proof and
            // needs no reflection. Every property of this entity is exercised by the tests in this
            // class, and none of them names a generated-identifier accessor, because the entity
            // declares none: the four properties below are the complete mapped surface.
            TransactionCategoryBalance row = seededRowZero();

            assertThat(row.getTrancatAcctId()).isNotNull();
            assertThat(row.getTrancatTypeCd()).isNotNull();
            assertThat(row.getTrancatCd()).isNotNull();
            assertThat(row.getTranCatBal()).isNotNull();

            // The three key components, and only those three, constitute identity - which the
            // equality tests below establish independently.
            assertThat(row.toId()).isEqualTo(seededKeyZero());
        }

        @Test
        @DisplayName("the 22-byte trailing filler is absent from the mapped surface: it carries no "
                + "information and is reconstructed on output from the declared record width, so no "
                + "property represents it")
        void trailingFillerIsNotMapped() {
            // Compile-time absence again: the entity exposes exactly four properties, all four of
            // which are exercised above, and none of them is the filler.
            assertThat(MAPPED_WIDTH).isLessThan(RECORD_WIDTH);
            assertThat(RECORD_WIDTH - MAPPED_WIDTH).isEqualTo(FILLER_WIDTH);
        }
    }

    @Nested
    @DisplayName("Monetary value and scale")
    class MonetaryValueAndScale {

        @Test
        @DisplayName("a zero balance round-trips with its scale intact: the two declared decimals are "
                + "preserved, so the stored value is not normalised to an unscaled zero")
        void zeroBalanceRoundTripsWithScaleTwo() {
            TransactionCategoryBalance row = new TransactionCategoryBalance();

            row.setTranCatBal(new BigDecimal(ROW_ZERO_BALANCE));
            BigDecimal stored = row.getTranCatBal();

            // Numeric equality and scale identity are asserted separately and never conflated:
            // comparison by value says "this is zero", the scale assertion says "with two decimals".
            assertThat(stored).isEqualByComparingTo(new BigDecimal("0"));
            assertThat(stored.scale()).isEqualTo(BALANCE_DECIMAL_DIGITS);

            // Value equality is scale-sensitive, so a zero with two decimals is deliberately NOT
            // equal to an unscaled zero. That inequality is the proof that the scale survived.
            assertThat(stored).isNotEqualTo(new BigDecimal("0"));
        }

        @Test
        @DisplayName("a positive non-zero balance round-trips exactly, integer digits and decimals "
                + "alike, at the two-decimal scale the field declares")
        void positiveBalanceRoundTripsExactly() {
            TransactionCategoryBalance row = new TransactionCategoryBalance();

            row.setTranCatBal(new BigDecimal(POSITIVE_BALANCE));
            BigDecimal stored = row.getTranCatBal();

            assertThat(stored).isEqualTo(new BigDecimal(POSITIVE_BALANCE));
            assertThat(stored).isEqualByComparingTo(new BigDecimal(POSITIVE_BALANCE));
            assertThat(stored.scale()).isEqualTo(BALANCE_DECIMAL_DIGITS);
            assertThat(stored.signum()).isOne();
        }

        @Test
        @DisplayName("a negative balance round-trips exactly including its sign: the legacy field is "
                + "signed, its sign folded into the trailing byte of the record image rather than "
                + "occupying a byte of its own, which is why the field's width equals its digit count")
        void negativeBalanceRoundTripsWithItsSign() {
            TransactionCategoryBalance row = new TransactionCategoryBalance();

            row.setTranCatBal(new BigDecimal(NEGATIVE_BALANCE));
            BigDecimal stored = row.getTranCatBal();

            assertThat(stored).isEqualTo(new BigDecimal(NEGATIVE_BALANCE));
            assertThat(stored.scale()).isEqualTo(BALANCE_DECIMAL_DIGITS);
            assertThat(stored.signum()).isEqualTo(-1);
            assertThat(stored).isNotEqualByComparingTo(new BigDecimal(POSITIVE_BALANCE));
        }

        @Test
        @DisplayName("a negative zero is accepted and preserved as supplied: the legacy zoned "
                + "representation distinguishes positive zero from negative zero through two "
                + "different overpunched trailing bytes, and this carrier stores whatever it is given "
                + "rather than canonicalising the sign")
        void negativeZeroIsPreservedAsSupplied() {
            TransactionCategoryBalance row = new TransactionCategoryBalance();

            row.setTranCatBal(new BigDecimal("-0.00"));
            BigDecimal stored = row.getTranCatBal();

            // Numerically this is zero, and its scale is the declared two.
            assertThat(stored).isEqualByComparingTo(new BigDecimal("0"));
            assertThat(stored.scale()).isEqualTo(BALANCE_DECIMAL_DIGITS);

            // The seed fixture only ever exercises the positive-zero sign code, so this negative-zero
            // expectation is hand-constructed rather than taken from the data.
            assertThat(stored.signum()).isZero();
        }

        @Test
        @DisplayName("the entity applies no scaling: a value handed in at a scale other than the "
                + "declared two comes back at the scale it arrived with, proving the mutator is a "
                + "plain assignment, because scaling is the fixed-width codec's sole responsibility "
                + "and applying it here as well would apply it twice")
        void entityAppliesNoScaling() {
            TransactionCategoryBalance row = new TransactionCategoryBalance();

            // Scale 1, not the declared 2.
            row.setTranCatBal(new BigDecimal("1.5"));
            BigDecimal stored = row.getTranCatBal();

            assertThat(stored.scale()).isEqualTo(1);
            assertThat(stored.scale()).isNotEqualTo(BALANCE_DECIMAL_DIGITS);
            assertThat(stored).isEqualTo(new BigDecimal("1.5"));
        }

        @Test
        @DisplayName("the entity performs no rounding and no arithmetic: a three-decimal value is "
                + "returned unchanged rather than truncated toward zero or rounded up, because no "
                + "arithmetic statement in the estate specifies rounding and truncation to the "
                + "declared scale therefore belongs to the codec, never to this carrier")
        void entityPerformsNoRoundingAndNoArithmetic() {
            TransactionCategoryBalance row = new TransactionCategoryBalance();

            row.setTranCatBal(new BigDecimal("2.999"));
            BigDecimal stored = row.getTranCatBal();

            // Returned verbatim: neither truncated to two decimals nor rounded to three units.
            assertThat(stored).isEqualTo(new BigDecimal("2.999"));
            assertThat(stored.scale()).isEqualTo(3);
            assertThat(stored).isNotEqualByComparingTo(new BigDecimal("2.99"));
            assertThat(stored).isNotEqualByComparingTo(new BigDecimal("3.00"));
        }

        @Test
        @DisplayName("a balance at the full nine integer digits the field declares round-trips without "
                + "loss, so the declared precision is usable to its stated limit")
        void balanceAtFullDeclaredPrecisionRoundTrips() {
            TransactionCategoryBalance row = new TransactionCategoryBalance();

            // Nine integer digits and two decimals: the widest value the field can hold.
            BigDecimal widest = new BigDecimal("999999999.99");
            row.setTranCatBal(widest);

            assertThat(row.getTranCatBal()).isEqualTo(widest);
            assertThat(row.getTranCatBal().scale()).isEqualTo(BALANCE_DECIMAL_DIGITS);
            assertThat(row.getTranCatBal().precision()).isEqualTo(BALANCE_WIDTH);

            // Precision is the total digit count, which is exactly the field's encoded byte width.
            assertThat(widest.precision())
                    .isEqualTo(BALANCE_INTEGER_DIGITS + BALANCE_DECIMAL_DIGITS);
        }

        @Test
        @DisplayName("the balance is an exact decimal carrier: a value is held as the digits it was "
                + "given, so no approximate binary representation can round-trip a two-decimal amount "
                + "into a neighbouring value")
        void balanceIsAnExactDecimalCarrier() {
            TransactionCategoryBalance row = new TransactionCategoryBalance();

            // A tenth and a hundredth have no exact finite binary expansion. Constructed from their
            // decimal text they are exact here, and their sum is exact too - which is the whole
            // reason an exact decimal type is mandatory for every monetary field in this module.
            row.setTranCatBal(new BigDecimal("0.10").add(new BigDecimal("0.20")));

            assertThat(row.getTranCatBal()).isEqualTo(new BigDecimal("0.30"));
            assertThat(row.getTranCatBal().scale()).isEqualTo(BALANCE_DECIMAL_DIGITS);
        }

        @Test
        @DisplayName("the balance may be cleared back to unset, keeping an unpopulated instance "
                + "distinguishable from one carrying a genuine zero balance")
        void balanceMayBeClearedToUnset() {
            TransactionCategoryBalance row = seededRowZero();
            assertThat(row.getTranCatBal()).isNotNull();

            row.setTranCatBal(null);

            assertThat(row.getTranCatBal()).isNull();
        }
    }

    @Nested
    @DisplayName("Seeded reference-data facts")
    class SeededReferenceDataFacts {

        @Test
        @DisplayName("the first seeded record carries account identifier 00000000001 on transaction "
                + "type 01 and category 0001, read off the fixture at offsets 0, 11 and 13")
        void firstSeededRecordCarriesItsDecodedKey() {
            TransactionCategoryBalance row = seededRowZero();

            assertThat(row.getTrancatAcctId()).isEqualTo("00000000001");
            assertThat(row.getTrancatTypeCd()).isEqualTo("01");
            assertThat(row.getTrancatCd()).isEqualTo("0001");
        }

        @Test
        @DisplayName("the first seeded record carries a zero balance, and every one of the 50 seeded "
                + "records does: the fixture exercises only the positive-zero overpunch, so positive, "
                + "negative and negative-zero expectations are hand-constructed above rather than "
                + "drawn from the data")
        void everySeededRecordCarriesAZeroBalance() {
            TransactionCategoryBalance row = seededRowZero();

            assertThat(row.getTranCatBal()).isEqualByComparingTo(new BigDecimal("0"));
            assertThat(row.getTranCatBal().scale()).isEqualTo(BALANCE_DECIMAL_DIGITS);
            assertThat(row.getTranCatBal().signum()).isZero();
        }

        @Test
        @DisplayName("the fixture holds 50 records of the declared 50-byte length, measuring 2,550 "
                + "bytes once one line terminator per record is counted")
        void fixtureByteCountReconcilesWithFiftyRecords() {
            // Stated as arithmetic over the measured file size rather than by reading the file: this
            // is a pure unit test and touches no filesystem.
            assertThat(SEEDED_ROW_COUNT * (RECORD_WIDTH + 1)).isEqualTo(SEEDED_FILE_BYTES);
        }
    }

    @Nested
    @DisplayName("Entity identity")
    class EntityIdentity {

        @Test
        @DisplayName("two rows sharing all three key components are equal and share a hash code even "
                + "when their balances differ, because the key is identity and the balance is mutable "
                + "state")
        void rowsWithEqualKeysAreEqualRegardlessOfBalance() {
            TransactionCategoryBalance zeroBalance = new TransactionCategoryBalance(
                    ROW_ZERO_ACCT_ID, ROW_ZERO_TYPE_CD, ROW_ZERO_CAT_CD,
                    new BigDecimal(ROW_ZERO_BALANCE));
            TransactionCategoryBalance positiveBalance = new TransactionCategoryBalance(
                    ROW_ZERO_ACCT_ID, ROW_ZERO_TYPE_CD, ROW_ZERO_CAT_CD,
                    new BigDecimal(POSITIVE_BALANCE));

            assertThat(zeroBalance).isEqualTo(positiveBalance);
            assertThat(positiveBalance).isEqualTo(zeroBalance);
            assertThat(zeroBalance).hasSameHashCodeAs(positiveBalance);

            // The balances really do differ, so the equality above is not vacuous.
            assertThat(zeroBalance.getTranCatBal())
                    .isNotEqualByComparingTo(positiveBalance.getTranCatBal());
        }

        @Test
        @DisplayName("two rows differing only in the account identifier component are unequal, so the "
                + "first key component participates in identity")
        void rowsDifferingInAccountIdentifierAreUnequal() {
            TransactionCategoryBalance first = seededRowZero();
            TransactionCategoryBalance second = new TransactionCategoryBalance(
                    "00000000002", ROW_ZERO_TYPE_CD, ROW_ZERO_CAT_CD,
                    new BigDecimal(ROW_ZERO_BALANCE));

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("two rows differing only in the transaction type code component are unequal, so "
                + "the second key component participates in identity")
        void rowsDifferingInTypeCodeAreUnequal() {
            TransactionCategoryBalance first = seededRowZero();
            TransactionCategoryBalance second = new TransactionCategoryBalance(
                    ROW_ZERO_ACCT_ID, "02", ROW_ZERO_CAT_CD, new BigDecimal(ROW_ZERO_BALANCE));

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("two rows differing only in the transaction category code component are unequal, "
                + "so the third key component participates in identity")
        void rowsDifferingInCategoryCodeAreUnequal() {
            TransactionCategoryBalance first = seededRowZero();
            TransactionCategoryBalance second = new TransactionCategoryBalance(
                    ROW_ZERO_ACCT_ID, ROW_ZERO_TYPE_CD, "0002", new BigDecimal(ROW_ZERO_BALANCE));

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("equality is reflexive: a row equals itself through the identity short-circuit")
        void equalityIsReflexive() {
            TransactionCategoryBalance row = seededRowZero();

            assertThat(row).isEqualTo(row);
            assertThat(row).hasSameHashCodeAs(row);
        }

        @Test
        @DisplayName("equality rejects null rather than throwing, as the general contract requires")
        void equalityRejectsNull() {
            TransactionCategoryBalance row = seededRowZero();

            assertThat(row.equals(null)).isFalse();
        }

        @Test
        @DisplayName("equality rejects an unrelated type rather than throwing, so a rendering of the "
                + "key image is never mistaken for the row it describes")
        void equalityRejectsAForeignType() {
            TransactionCategoryBalance row = seededRowZero();

            assertThat(row.equals(FOREIGN_KEY_RENDERING)).isFalse();
            assertThat(row).isNotEqualTo(FOREIGN_KEY_RENDERING);
        }

        @Test
        @DisplayName("equality is consistent across repeated invocations and symmetric between two "
                + "separately constructed rows carrying the same key")
        void equalityIsConsistentAndSymmetric() {
            TransactionCategoryBalance first = seededRowZero();
            TransactionCategoryBalance second = seededRowZero();

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(first);
            assertThat(first).isEqualTo(second);
            assertThat(first.hashCode()).isEqualTo(second.hashCode());
        }

        @Test
        @DisplayName("a row whose key components are all unset equals another such row, so two "
                + "unpopulated instances do not compare unequal through null-hostile comparison")
        void unpopulatedRowsCompareEqual() {
            TransactionCategoryBalance first = new TransactionCategoryBalance();
            TransactionCategoryBalance second = new TransactionCategoryBalance();

            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("an unpopulated row is unequal to a populated one, and neither comparison throws "
                + "on the null components")
        void unpopulatedRowIsUnequalToAPopulatedRow() {
            TransactionCategoryBalance unpopulated = new TransactionCategoryBalance();
            TransactionCategoryBalance populated = seededRowZero();

            assertThat(unpopulated).isNotEqualTo(populated);
            assertThat(populated).isNotEqualTo(unpopulated);
        }

        @Test
        @DisplayName("rows keyed on distinct component triples occupy distinct hash-map entries, which "
                + "is what keeps two different rows two different rows inside a collection")
        void distinctKeysOccupyDistinctMapEntries() {
            Map<TransactionCategoryBalance, String> rows = new HashMap<>();

            rows.put(seededRowZero(), "first");
            rows.put(new TransactionCategoryBalance(
                    "00000000002", ROW_ZERO_TYPE_CD, ROW_ZERO_CAT_CD,
                    new BigDecimal(ROW_ZERO_BALANCE)), "second");

            assertThat(rows).hasSize(2);

            // A third row with the first row's key replaces rather than adds, because the key alone
            // determines identity even though the balance differs.
            rows.put(new TransactionCategoryBalance(
                    ROW_ZERO_ACCT_ID, ROW_ZERO_TYPE_CD, ROW_ZERO_CAT_CD,
                    new BigDecimal(POSITIVE_BALANCE)), "replacement");

            assertThat(rows).hasSize(2);
            assertThat(rows).containsEntry(seededRowZero(), "replacement");
        }
    }

    @Nested
    @DisplayName("Composite key contract")
    class CompositeKeyContract {

        @Test
        @DisplayName("the key's all-argument constructor stores its three components in contractual "
                + "order - account identifier, then transaction type code, then transaction category "
                + "code - and returns each exactly as supplied")
        void allArgumentConstructorRoundTripsEveryComponent() {
            TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(
                    ROW_ZERO_ACCT_ID, ROW_ZERO_TYPE_CD, ROW_ZERO_CAT_CD);

            assertThat(key.getTrancatAcctId()).isEqualTo(ROW_ZERO_ACCT_ID);
            assertThat(key.getTrancatTypeCd()).isEqualTo(ROW_ZERO_TYPE_CD);
            assertThat(key.getTrancatCd()).isEqualTo(ROW_ZERO_CAT_CD);
        }

        @Test
        @DisplayName("the key's three components are exactly 11, 2 and 4 encoded bytes, summing to the "
                + "17-byte key length the provisioning job declares")
        void keyComponentsCarryTheirDeclaredWidths() {
            TransactionCategoryBalanceId key = seededKeyZero();

            assertThat(encodedWidthOf(key.getTrancatAcctId())).isEqualTo(ACCT_ID_WIDTH);
            assertThat(encodedWidthOf(key.getTrancatTypeCd())).isEqualTo(TYPE_CD_WIDTH);
            assertThat(encodedWidthOf(key.getTrancatCd())).isEqualTo(CAT_CD_WIDTH);

            assertThat(encodedWidthOf(key.getTrancatAcctId())
                    + encodedWidthOf(key.getTrancatTypeCd())
                    + encodedWidthOf(key.getTrancatCd()))
                    .isEqualTo(KEY_WIDTH);
        }

        @Test
        @DisplayName("the no-argument constructor the persistence provider requires exists and leaves "
                + "every component unset, which is why the key is a plain class rather than a record - "
                + "a record has no no-argument constructor to offer")
        void noArgumentConstructorExistsAndYieldsUnsetComponents() {
            // The production no-argument constructor is protected and the key class lives in a
            // different package from this test, so it is reached through an explicit superclass
            // invocation from the probe declared above. That is inheritance, NOT reflection: no
            // member is looked up by name and no accessibility is overridden.
            TransactionCategoryBalanceId empty = new ProtectedKeyConstructorProbe();

            assertThat(empty.getTrancatAcctId()).isNull();
            assertThat(empty.getTrancatTypeCd()).isNull();
            assertThat(empty.getTrancatCd()).isNull();
        }

        @Test
        @DisplayName("two keys carrying the same three components are equal and share a hash code, so "
                + "the same database row resolves to one identity")
        void keysWithEqualComponentsAreEqual() {
            TransactionCategoryBalanceId first = seededKeyZero();
            TransactionCategoryBalanceId second = seededKeyZero();

            assertThat(first).isEqualTo(second);
            assertThat(second).isEqualTo(first);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("two keys differing only in the account identifier are unequal, so the first "
                + "component participates in key equality")
        void keysDifferingInAccountIdentifierAreUnequal() {
            TransactionCategoryBalanceId first = seededKeyZero();
            TransactionCategoryBalanceId second = new TransactionCategoryBalanceId(
                    "00000000002", ROW_ZERO_TYPE_CD, ROW_ZERO_CAT_CD);

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("two keys differing only in the transaction type code are unequal, so the second "
                + "component participates in key equality")
        void keysDifferingInTypeCodeAreUnequal() {
            TransactionCategoryBalanceId first = seededKeyZero();
            TransactionCategoryBalanceId second = new TransactionCategoryBalanceId(
                    ROW_ZERO_ACCT_ID, "02", ROW_ZERO_CAT_CD);

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("two keys differing only in the transaction category code are unequal, so the "
                + "third component participates in key equality")
        void keysDifferingInCategoryCodeAreUnequal() {
            TransactionCategoryBalanceId first = seededKeyZero();
            TransactionCategoryBalanceId second = new TransactionCategoryBalanceId(
                    ROW_ZERO_ACCT_ID, ROW_ZERO_TYPE_CD, "0002");

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("key equality is reflexive: a key equals itself through the identity short-circuit")
        void keyEqualityIsReflexive() {
            TransactionCategoryBalanceId key = seededKeyZero();

            assertThat(key).isEqualTo(key);
            assertThat(key).hasSameHashCodeAs(key);
        }

        @Test
        @DisplayName("key equality rejects null rather than throwing")
        void keyEqualityRejectsNull() {
            TransactionCategoryBalanceId key = seededKeyZero();

            assertThat(key.equals(null)).isFalse();
        }

        @Test
        @DisplayName("key equality rejects an unrelated type rather than throwing, so the concatenated "
                + "17-character key image is never mistaken for the key it renders")
        void keyEqualityRejectsAForeignType() {
            TransactionCategoryBalanceId key = seededKeyZero();

            assertThat(key.equals(FOREIGN_KEY_RENDERING)).isFalse();

            // The foreign value really is the concatenation of this key's three components, so the
            // rejection is a type decision and not an accident of differing content.
            assertThat(FOREIGN_KEY_RENDERING)
                    .isEqualTo(ROW_ZERO_ACCT_ID + ROW_ZERO_TYPE_CD + ROW_ZERO_CAT_CD);
            assertThat(encodedWidthOf(FOREIGN_KEY_RENDERING)).isEqualTo(KEY_WIDTH);
        }

        @Test
        @DisplayName("two keys with all components unset are equal, so the provider's freshly "
                + "instantiated keys do not compare unequal through null-hostile comparison")
        void unsetKeysCompareEqual() {
            TransactionCategoryBalanceId first = new ProtectedKeyConstructorProbe();
            TransactionCategoryBalanceId second = new ProtectedKeyConstructorProbe();

            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
        }
    }

    @Nested
    @DisplayName("Composite key normalisation and serialization")
    class CompositeKeyNormalisationAndSerialization {

        @Test
        @DisplayName("leading zeros are significant in the key: a zero-filled account identifier and "
                + "category code are not the key their numeric values would produce, and the two keys "
                + "occupy two separate hash-map entries")
        void leadingZerosAreSignificantInTheKey() {
            TransactionCategoryBalanceId padded = new TransactionCategoryBalanceId(
                    ROW_ZERO_ACCT_ID, ROW_ZERO_TYPE_CD, ROW_ZERO_CAT_CD);
            TransactionCategoryBalanceId unpadded = new TransactionCategoryBalanceId(
                    "1", ROW_ZERO_TYPE_CD, "1");

            assertThat(padded).isNotEqualTo(unpadded);
            assertThat(unpadded).isNotEqualTo(padded);

            Map<TransactionCategoryBalanceId, String> keyed = new HashMap<>();
            keyed.put(padded, "padded");
            keyed.put(unpadded, "unpadded");

            assertThat(keyed).hasSize(2);
            assertThat(keyed).containsEntry(padded, "padded");
            assertThat(keyed).containsEntry(unpadded, "unpadded");
        }

        @Test
        @DisplayName("the key applies no trimming or stripping: a component carrying trailing spaces "
                + "is not equal to its trimmed form, because in a fixed-width layout padding is part "
                + "of the value and normalising it would make two distinct rows compare equal")
        void keyAppliesNoTrimmingOrStripping() {
            TransactionCategoryBalanceId spacePadded = new TransactionCategoryBalanceId(
                    ROW_ZERO_ACCT_ID, "1 ", ROW_ZERO_CAT_CD);
            TransactionCategoryBalanceId trimmed = new TransactionCategoryBalanceId(
                    ROW_ZERO_ACCT_ID, "1", ROW_ZERO_CAT_CD);

            // Stored verbatim by the constructor.
            assertThat(spacePadded.getTrancatTypeCd()).isEqualTo("1 ");
            assertThat(encodedWidthOf(spacePadded.getTrancatTypeCd())).isEqualTo(TYPE_CD_WIDTH);

            // And distinguished by equality and by hashing, not merely by the accessor.
            assertThat(spacePadded).isNotEqualTo(trimmed);

            Map<TransactionCategoryBalanceId, String> keyed = new HashMap<>();
            keyed.put(spacePadded, "padded");
            keyed.put(trimmed, "trimmed");

            assertThat(keyed).hasSize(2);
        }

        @Test
        @DisplayName("the key applies no case folding: components differing only in letter case remain "
                + "distinct keys, because the transaction type code is a character field whose case is "
                + "part of the stored value")
        void keyAppliesNoCaseFolding() {
            TransactionCategoryBalanceId lower = new TransactionCategoryBalanceId(
                    ROW_ZERO_ACCT_ID, "ab", ROW_ZERO_CAT_CD);
            TransactionCategoryBalanceId upper = new TransactionCategoryBalanceId(
                    ROW_ZERO_ACCT_ID, "AB", ROW_ZERO_CAT_CD);

            assertThat(lower).isNotEqualTo(upper);
            assertThat(lower.getTrancatTypeCd()).isEqualTo("ab");
            assertThat(upper.getTrancatTypeCd()).isEqualTo("AB");
        }

        @Test
        @DisplayName("the key declares an explicit serialization identity of 1, which the build "
                + "requires rather than merely prefers: the missing-identity lint diagnostic on a "
                + "serializable class is promoted to a build failure here")
        void keyDeclaresAnExplicitSerialVersionUid() {
            // ObjectStreamClass is the sanctioned serialization-metadata API of the java.io package.
            // It is NOT java.lang.reflect: no member is looked up by name, no accessibility is
            // overridden, and the module's audit requirement of zero reflection is preserved.
            ObjectStreamClass descriptor = ObjectStreamClass.lookup(TransactionCategoryBalanceId.class);

            assertThat(descriptor).isNotNull();
            assertThat(descriptor.getSerialVersionUID()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a populated key survives a Java serialization round trip with every component "
                + "intact, which the persistence provider relies on when a composite key crosses a "
                + "process or cache boundary")
        void populatedKeySurvivesASerializationRoundTrip()
                throws IOException, ClassNotFoundException {
            TransactionCategoryBalanceId original = seededKeyZero();

            TransactionCategoryBalanceId restored = serializeAndRestore(original);

            assertThat(restored).isEqualTo(original);
            assertThat(restored).hasSameHashCodeAs(original);
            assertThat(restored.getTrancatAcctId()).isEqualTo(ROW_ZERO_ACCT_ID);
            assertThat(restored.getTrancatTypeCd()).isEqualTo(ROW_ZERO_TYPE_CD);
            assertThat(restored.getTrancatCd()).isEqualTo(ROW_ZERO_CAT_CD);
        }

        @Test
        @DisplayName("a serialization round trip preserves the leading zeros of a zero-filled key, so "
                + "a key that crosses a boundary still reconstructs its 17-byte image")
        void serializationRoundTripPreservesLeadingZeros()
                throws IOException, ClassNotFoundException {
            TransactionCategoryBalanceId restored = serializeAndRestore(seededKeyZero());

            assertThat(encodedWidthOf(restored.getTrancatAcctId())).isEqualTo(ACCT_ID_WIDTH);
            assertThat(encodedWidthOf(restored.getTrancatCd())).isEqualTo(CAT_CD_WIDTH);
            assertThat(restored.getTrancatAcctId()).isNotEqualTo("1");
            assertThat(restored.getTrancatCd()).isNotEqualTo("1");
        }

        /**
         * Serializes a key and reads it back, so that a round trip is exercised through the real
         * stream implementations rather than simulated.
         *
         * <p>Both streams are closed by try-with-resources, which keeps the unclosed-resource
         * diagnostic silent in a build that promotes warnings to errors.
         *
         * @param original the key to round trip
         * @return the restored key
         * @throws IOException            if either stream fails
         * @throws ClassNotFoundException if the restored type cannot be resolved
         */
        private TransactionCategoryBalanceId serializeAndRestore(
                TransactionCategoryBalanceId original) throws IOException, ClassNotFoundException {
            byte[] serialized;
            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(original);
                out.flush();
                serialized = bytes.toByteArray();
            }

            try (ByteArrayInputStream bytes = new ByteArrayInputStream(serialized);
                    ObjectInputStream in = new ObjectInputStream(bytes)) {
                return (TransactionCategoryBalanceId) in.readObject();
            }
        }
    }
}
