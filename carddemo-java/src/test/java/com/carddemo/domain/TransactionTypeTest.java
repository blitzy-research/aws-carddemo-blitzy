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

import com.carddemo.support.SchemaColumnCatalog;
import com.carddemo.support.SeededRecordFixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link TransactionType}, the sixty-byte transaction-type reference record.
 *
 * <p><strong>The layout being preserved.</strong> {@code app/cpy/CVTRA03Y.cpy} declares a sixty-byte
 * record in three parts: a two-byte type code, a fifty-byte description and an eight-byte filler. The
 * cluster definition at {@code app/jcl/TRANTYPE.jcl} confirms the geometry independently with
 * {@code KEYS(2 0)} and {@code RECORDSIZE(60 60)}, so the whole key is the two-byte code at the front of
 * the record and nothing else.
 *
 * <p><strong>Why the two-byte code has to stay a character field.</strong> Every seeded code carries a
 * leading zero — the seven of them run from {@code 01} to {@code 07} — and the type code travels into the
 * transaction record as a two-character field. Stored as an integer, the first of those codes would come
 * back as a one, the stored key would no longer be the two bytes the record image carries, and a
 * transaction stamped {@code 01} would fail to resolve. The suite proves the leading zero survives rather
 * than assuming it.
 *
 * <p><strong>Why the seeded contents are the oracle.</strong> The seven code-and-description pairs are
 * transcribed from the estate's own reference file, so this suite compares the classpath fixture against
 * a transcription of the legacy data rather than against anything the entity itself reports. The pairing
 * matters beyond bookkeeping: the interest run stamps its synthesised transactions with type {@code 01},
 * and that code has to be one the reference file declares or the posted interest would carry a type no
 * lookup could resolve.
 *
 * <p><strong>Deliberately not asserted.</strong> Nothing here reads the reference table through a
 * repository or joins it to a transaction; that belongs to the repository integration tests. This suite
 * establishes only that the record those tests read is shaped and seeded the way they require.
 */
@DisplayName("TransactionType — the sixty-byte transaction-type reference record")
class TransactionTypeTest {

    /** Relational table the entity maps to. */
    private static final String TABLE = "transaction_type";

    /** {@code RECORDSIZE(60 60)} in the cluster definition. */
    private static final int RECORD_WIDTH = 60;

    /** {@code KEYS(2 0)} — key length. */
    private static final int KEY_WIDTH = 2;

    /** The three copybook widths, in declaration order. */
    private static final List<Integer> COPYBOOK_WIDTHS = List.of(2, 50, 8);

    /** Zero-based offset of the type code. */
    private static final int OFFSET_CODE = 0;

    /** Zero-based offset of the description. */
    private static final int OFFSET_DESCRIPTION = 2;

    /** Zero-based offset of the filler. */
    private static final int OFFSET_FILLER = 52;

    /** Width of the unmapped trailing filler. */
    private static final int FILLER_WIDTH = 8;

    /** Seeded records. */
    private static final int SEEDED_RECORDS = 7;

    /** The type code the interest run stamps on every transaction it synthesises. */
    private static final String INTEREST_TYPE_CODE = "01";

    /** The migration's transaction-type table, parsed once. */
    private static final SchemaColumnCatalog SCHEMA = SchemaColumnCatalog.load();

    /** The seeded reference file, loaded once at its declared width. */
    private static final SeededRecordFixture SEED =
            SeededRecordFixture.load("trantype.txt", RECORD_WIDTH);

    /**
     * The seven seeded code-and-description pairs, transcribed from the estate's reference data in file
     * order.
     */
    private static final Map<String, String> SEEDED_TYPES = seededTypes();

    /**
     * Transcribes the seven seeded reference rows.
     *
     * <p>File order is itself asserted, so the map is wrapped rather than copied into a hash-ordered
     * immutable map.
     *
     * @return an ordered, unmodifiable view of the seeded type codes and their descriptions
     */
    private static Map<String, String> seededTypes() {
        final Map<String, String> types = new LinkedHashMap<>();
        types.put("01", "Purchase");
        types.put("02", "Payment");
        types.put("03", "Credit");
        types.put("04", "Authorization");
        types.put("05", "Refund");
        types.put("06", "Reversal");
        types.put("07", "Adjustment");
        return Collections.unmodifiableMap(types);
    }

    /**
     * Reads one seeded record's type code.
     *
     * @param ordinal the one-based record ordinal
     * @return the two-byte type code
     */
    private static String seededCode(final int ordinal) {
        return SEED.field(ordinal, OFFSET_CODE, KEY_WIDTH);
    }

    /**
     * Reads one seeded record's description, at its blank-filled fifty-byte width.
     *
     * @param ordinal the one-based record ordinal
     * @return the fifty-byte description
     */
    private static String seededDescription(final int ordinal) {
        return SEED.field(ordinal, OFFSET_DESCRIPTION, COPYBOOK_WIDTHS.get(1));
    }

    /**
     * Builds the entity one seeded record describes.
     *
     * @param ordinal the one-based record ordinal
     * @return the reference row the record describes
     */
    private static TransactionType typeFromSeed(final int ordinal) {
        return new TransactionType(seededCode(ordinal), seededDescription(ordinal));
    }

    // RECORD LAYOUT

    /**
     * Verifies the copybook geometry the entity has to honour.
     */
    @Nested
    @DisplayName("record layout")
    class RecordLayout {

        @Test
        @DisplayName("the three copybook widths sum to the sixty bytes the cluster declares")
        void theWidthsSumToTheRecordSize() {
            assertThat(COPYBOOK_WIDTHS).hasSize(3);
            assertThat(COPYBOOK_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("each field begins where the preceding widths leave off")
        void eachFieldBeginsWhereThePrecedingWidthsLeaveOff() {
            final List<Integer> offsets = List.of(OFFSET_CODE, OFFSET_DESCRIPTION, OFFSET_FILLER);

            int running = 0;
            for (int index = 0; index < COPYBOOK_WIDTHS.size(); index++) {
                assertThat(offsets.get(index))
                        .as("offset of field %d", index)
                        .isEqualTo(running);
                running += COPYBOOK_WIDTHS.get(index);
            }

            assertThat(running).isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("the whole key is the leading two bytes, so a type code alone locates a record")
        void theWholeKeyIsTheLeadingTwoBytes() {
            assertThat(KEY_WIDTH).isEqualTo(COPYBOOK_WIDTHS.get(0));
            assertThat(OFFSET_DESCRIPTION)
                    .as("the key ends where the description begins")
                    .isEqualTo(KEY_WIDTH);
        }

        @Test
        @DisplayName("the trailing eight bytes are filler and are mapped to no column")
        void theTrailingBytesAreFillerAndUnmapped() {
            assertThat(COPYBOOK_WIDTHS.get(2)).isEqualTo(FILLER_WIDTH);
            assertThat(OFFSET_FILLER + FILLER_WIDTH).isEqualTo(RECORD_WIDTH);
            assertThat(SCHEMA.columnNames(TABLE)).hasSize(COPYBOOK_WIDTHS.size() - 1);
        }
    }

    // SCHEMA AGREEMENT

    /**
     * Verifies that the deployed migration describes the layout the copybook does.
     */
    @Nested
    @DisplayName("schema agreement")
    class SchemaAgreement {

        @Test
        @DisplayName("the table declares the two mapped columns in copybook order")
        void theTableDeclaresTheMappedColumnsInCopybookOrder() {
            assertThat(SCHEMA.columnNames(TABLE)).containsExactly("tran_type", "tran_type_desc");
        }

        @Test
        @DisplayName("both mapped columns match their copybook widths")
        void bothMappedColumnsMatchTheirCopybookWidths() {
            final List<String> columns = SCHEMA.columnNames(TABLE);

            for (int index = 0; index < columns.size(); index++) {
                assertThat(SCHEMA.declaredWidth(TABLE, columns.get(index)))
                        .as("declared width of %s", columns.get(index))
                        .isEqualTo(COPYBOOK_WIDTHS.get(index));
            }
        }

        @Test
        @DisplayName("the code column is character rather than integer, so a code of 01 keeps its leading "
                + "zero and the stored key still matches the record image")
        void theCodeColumnIsCharacterSoTheLeadingZeroSurvives() {
            assertThat(SCHEMA.declaredType(TABLE, "tran_type")).isEqualTo("VARCHAR(2)");

            assertThat(seededCode(1)).startsWith("0").hasSize(KEY_WIDTH);
            assertThat(Integer.toString(Integer.parseInt(seededCode(1))))
                    .as("an integer column would have stored this code without its leading zero")
                    .isNotEqualTo(seededCode(1));
        }

        @Test
        @DisplayName("the primary key is the code alone, and no surrogate or version column exists")
        void thePrimaryKeyIsTheCodeAlone() {
            assertThat(SCHEMA.primaryKeyColumns(TABLE)).containsExactly("tran_type");
            assertThat(SCHEMA.columnNames(TABLE))
                    .doesNotContain("id", "transaction_type_id", "version");
        }

        @Test
        @DisplayName("both columns are declared not null, so no row can carry an absent description")
        void bothColumnsAreDeclaredNotNull() {
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
        @DisplayName("both constructor arguments reach their own accessors")
        void bothConstructorArgumentsReachTheirAccessors() {
            final TransactionType type = new TransactionType("01", "Purchase");

            assertThat(type.getTranType()).isEqualTo("01");
            assertThat(type.getTranTypeDesc()).isEqualTo("Purchase");
        }

        @Test
        @DisplayName("the code and the description do not swap, which their very different widths make "
                + "detectable")
        void theCodeAndDescriptionDoNotSwap() {
            final TransactionType type = typeFromSeed(1);

            assertThat(type.getTranType()).hasSize(KEY_WIDTH);
            assertThat(type.getTranTypeDesc()).hasSize(COPYBOOK_WIDTHS.get(1));
            assertThat(type.getTranTypeDesc()).contains(SEEDED_TYPES.get(seededCode(1)));
        }

        @Test
        @DisplayName("both mutators replace exactly the field they name")
        void bothMutatorsReplaceTheFieldTheyName() {
            final TransactionType type = typeFromSeed(1);

            type.setTranType("07");
            type.setTranTypeDesc("Adjustment");

            assertThat(type.getTranType()).isEqualTo("07");
            assertThat(type.getTranTypeDesc()).isEqualTo("Adjustment");
        }

        @Test
        @DisplayName("the persistence constructor leaves both fields absent")
        void thePersistenceConstructorLeavesBothFieldsAbsent() {
            final TransactionType type = new TransactionType();

            assertThat(type.getTranType()).isNull();
            assertThat(type.getTranTypeDesc()).isNull();
        }

        @Test
        @DisplayName("a description at the full fifty-byte width survives intact")
        void aFullWidthDescriptionSurvivesIntact() {
            final String widest = "D".repeat(COPYBOOK_WIDTHS.get(1));

            assertThat(new TransactionType("01", widest).getTranTypeDesc())
                    .isEqualTo(widest)
                    .hasSize(COPYBOOK_WIDTHS.get(1));
        }
    }

    // SEEDED REFERENCE DATA

    /**
     * Verifies the seven seeded reference rows against a transcription of the estate's own data.
     */
    @Nested
    @DisplayName("seeded reference data")
    class SeededReferenceData {

        @Test
        @DisplayName("the seed carries seven records at the declared sixty-byte width")
        void theSeedCarriesSevenRecords() {
            assertThat(SEED.recordCount()).isEqualTo(SEEDED_RECORDS);
            assertThat(SEED.recordWidth()).isEqualTo(RECORD_WIDTH);
            assertThat(SEED.impliedByteCount()).isEqualTo(SEEDED_RECORDS * (RECORD_WIDTH + 1));
        }

        @Test
        @DisplayName("every seeded code and description matches the transcription of the estate's "
                + "reference data, in file order")
        void everySeededRowMatchesTheTranscription() {
            final Map<String, String> observed = new LinkedHashMap<>();
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                observed.put(seededCode(ordinal), seededDescription(ordinal).strip());
            }

            assertThat(observed).containsExactlyEntriesOf(SEEDED_TYPES);
        }

        @Test
        @DisplayName("the seven codes are contiguous from 01, so no code in the range is missing")
        void theSevenCodesAreContiguousFromOne() {
            final List<String> codes = new ArrayList<>();
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                codes.add(seededCode(ordinal));
            }

            assertThat(codes).hasSize(SEEDED_RECORDS);
            for (int index = 0; index < codes.size(); index++) {
                assertThat(Integer.parseInt(codes.get(index)))
                        .as("code at position %d", index)
                        .isEqualTo(index + 1);
            }
        }

        @Test
        @DisplayName("every code is distinct and every description is distinct, so the seed carries no "
                + "duplicate reference row")
        void everyCodeAndDescriptionIsDistinct() {
            final List<String> codes = new ArrayList<>();
            final List<String> descriptions = new ArrayList<>();
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                codes.add(seededCode(ordinal));
                descriptions.add(seededDescription(ordinal));
            }

            assertThat(codes.stream().distinct().toList()).hasSize(SEEDED_RECORDS);
            assertThat(descriptions.stream().distinct().toList()).hasSize(SEEDED_RECORDS);
        }

        @Test
        @DisplayName("every description is blank-filled on the right to fifty bytes rather than trimmed "
                + "in the record")
        void everyDescriptionIsBlankFilledToFiftyBytes() {
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                final String description = seededDescription(ordinal);

                assertThat(description)
                        .as("description of record %d", ordinal)
                        .hasSize(COPYBOOK_WIDTHS.get(1))
                        .doesNotStartWith(" ")
                        .endsWith(" ");
                assertThat(description.strip()).isNotEmpty();
            }
        }

        @Test
        @DisplayName("every seeded record's trailing filler is numeric zeros rather than blanks, and is "
                + "carried by no field of the entity")
        void theSeededFillerIsNumericZeros() {
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                assertThat(SEED.field(ordinal, OFFSET_FILLER, FILLER_WIDTH))
                        .as("filler of record %d", ordinal)
                        .isEqualTo("0".repeat(FILLER_WIDTH));
            }
        }

        @Test
        @DisplayName("the type code the interest run stamps on its synthesised transactions is one the "
                + "reference file declares")
        void theInterestRunsTypeCodeIsDeclared() {
            final List<String> codes = new ArrayList<>();
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                codes.add(seededCode(ordinal));
            }

            assertThat(codes).contains(INTEREST_TYPE_CODE);
            assertThat(INTEREST_TYPE_CODE).hasSize(KEY_WIDTH);
            assertThat(SEEDED_TYPES).containsKey(INTEREST_TYPE_CODE);
        }
    }

    // BUSINESS-KEY IDENTITY

    /**
     * Verifies that identity is the type code and nothing else.
     */
    @Nested
    @DisplayName("business-key identity")
    class BusinessKeyIdentity {

        @Test
        @DisplayName("a row equals itself")
        void aRowEqualsItself() {
            final TransactionType type = typeFromSeed(1);

            assertThat(type).isEqualTo(type);
            assertThat(type.hashCode()).isEqualTo(type.hashCode());
        }

        @Test
        @DisplayName("two rows with the same code are equal even when their descriptions differ, because "
                + "the description is not part of identity")
        void sameCodeMeansEqualEvenWithADifferentDescription() {
            final TransactionType left = new TransactionType("01", "Purchase");
            final TransactionType right = new TransactionType("01", "Something else entirely");

            assertThat(left).isEqualTo(right);
            assertThat(right).isEqualTo(left);
            assertThat(left).hasSameHashCodeAs(right);
            assertThat(left.getTranTypeDesc()).isNotEqualTo(right.getTranTypeDesc());
        }

        @Test
        @DisplayName("two rows with different codes are unequal even when their descriptions match")
        void differentCodeMeansUnequal() {
            assertThat(new TransactionType("01", "Purchase"))
                    .isNotEqualTo(new TransactionType("02", "Purchase"));
        }

        @Test
        @DisplayName("the seven seeded rows produce seven distinct identities")
        void theSeededRowsProduceDistinctIdentities() {
            final List<TransactionType> rows = new ArrayList<>();
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                rows.add(typeFromSeed(ordinal));
            }

            assertThat(rows.stream().distinct().toList()).hasSize(SEEDED_RECORDS);
        }

        @Test
        @DisplayName("a row is unequal to null and to an unrelated type")
        void aRowIsUnequalToNullAndToAnotherType() {
            final TransactionType type = typeFromSeed(1);

            assertThat(type).isNotEqualTo(null);
            assertThat(type.equals("01")).isFalse();
            assertThat(type).isNotEqualTo(new Object());
        }

        @Test
        @DisplayName("two rows with an absent code are equal, because both keys are absent rather than "
                + "generated")
        void twoUnkeyedRowsAreEqual() {
            assertThat(new TransactionType()).isEqualTo(new TransactionType());
            assertThat(new TransactionType()).hasSameHashCodeAs(new TransactionType());
        }
    }

    // DIAGNOSTIC REPRESENTATION

    /**
     * Verifies the diagnostic string.
     */
    @Nested
    @DisplayName("diagnostic representation")
    class DiagnosticRepresentation {

        @Test
        @DisplayName("the diagnostic string names the type and both fields")
        void theDiagnosticStringNamesTheTypeAndBothFields() {
            assertThat(new TransactionType("01", "Purchase").toString())
                    .isEqualTo("TransactionType[tranType=01, tranTypeDesc=Purchase]");
        }

        @Test
        @DisplayName("the diagnostic string carries the description, so two rows that are equal can "
                + "still render differently")
        void equalRowsCanRenderDifferently() {
            final TransactionType left = new TransactionType("01", "Purchase");
            final TransactionType right = new TransactionType("01", "Something else entirely");

            assertThat(left).isEqualTo(right);
            assertThat(left.toString()).isNotEqualTo(right.toString());
        }

        @Test
        @DisplayName("an unkeyed row renders without failing")
        void anUnkeyedRowRendersWithoutFailing() {
            assertThat(new TransactionType().toString())
                    .isEqualTo("TransactionType[tranType=null, tranTypeDesc=null]");
        }
    }
}
