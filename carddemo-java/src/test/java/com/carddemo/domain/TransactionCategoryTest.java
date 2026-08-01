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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.carddemo.domain.id.TransactionCategoryId;
import com.carddemo.support.SchemaColumnCatalog;
import com.carddemo.support.SeededRecordFixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link TransactionCategory}, the sixty-byte transaction-category reference record.
 *
 * <p><strong>The layout being preserved.</strong> {@code app/cpy/CVTRA04Y.cpy} declares a sixty-byte
 * record in four parts, the first two of which the copybook groups under a single key name: a two-byte
 * transaction type, a four-byte category, a fifty-byte description and a four-byte filler. The cluster
 * definition at {@code app/jcl/TRANCATG.jcl} confirms the arithmetic independently with
 * {@code KEYS(6 0)} and {@code RECORDSIZE(60 60)}: two plus four is the six-byte key, and the key starts
 * at the front of the record. A category is therefore identified only in the context of a type, and the
 * seeded data bears that out — the same category number appears under several types with a different
 * meaning each time.
 *
 * <p><strong>Why the four-byte category has to stay a character field.</strong> The copybook types the
 * category as numeric and every seeded value carries leading zeros, running from {@code 0001} to
 * {@code 0005}. Stored as an integer, the first would come back as a one, the stored key would no longer
 * be the six bytes the record image carries, and the composite lookup would miss. The suite proves the
 * leading zeros survive.
 *
 * <p><strong>Why the seeded pairing is load-bearing rather than decorative.</strong> The interest run
 * stamps its synthesised transactions with a type of {@code 01} and a category literal of {@code 05},
 * moved into a four-digit numeric field, which right-justifies and zero-fills it to {@code 0005}. That
 * pair has to resolve in this reference file or the posted interest would carry a category no lookup could
 * explain — and it does resolve, to the row the estate seeds for exactly that purpose. The suite asserts
 * the zero-fill and the resolution, and additionally asserts that every type code appearing here is one
 * the transaction-type reference file declares, which is a cross-file check neither file can satisfy
 * alone.
 *
 * <p><strong>Deliberately not asserted.</strong> Nothing here reads the reference table through a
 * repository or joins it to a transaction; that belongs to the repository integration tests.
 */
@DisplayName("TransactionCategory — the sixty-byte transaction-category reference record")
class TransactionCategoryTest {

    /** Relational table the entity maps to. */
    private static final String TABLE = "transaction_category";

    /** {@code RECORDSIZE(60 60)} in the cluster definition. */
    private static final int RECORD_WIDTH = 60;

    /** {@code KEYS(6 0)} — key length. */
    private static final int KEY_WIDTH = 6;

    /** The four copybook widths, in declaration order. */
    private static final List<Integer> COPYBOOK_WIDTHS = List.of(2, 4, 50, 4);

    /** Zero-based offset of the transaction type. */
    private static final int OFFSET_TYPE = 0;

    /** Zero-based offset of the category. */
    private static final int OFFSET_CATEGORY = 2;

    /** Zero-based offset of the description. */
    private static final int OFFSET_DESCRIPTION = 6;

    /** Zero-based offset of the filler. */
    private static final int OFFSET_FILLER = 56;

    /** Width of the unmapped trailing filler. */
    private static final int FILLER_WIDTH = 4;

    /** Seeded records. */
    private static final int SEEDED_RECORDS = 18;

    /** Distinct type codes the seed uses. */
    private static final int SEEDED_TYPE_CODES = 7;

    /** Distinct category codes the seed uses. */
    private static final int SEEDED_CATEGORY_CODES = 5;

    /** The type code the interest run stamps on every transaction it synthesises. */
    private static final String INTEREST_TYPE_CODE = "01";

    /** The category literal the interest run moves into a four-digit numeric field. */
    private static final String INTEREST_CATEGORY_LITERAL = "05";

    /** The migration's transaction-category table, parsed once. */
    private static final SchemaColumnCatalog SCHEMA = SchemaColumnCatalog.load();

    /** The seeded category reference file, loaded once at its declared width. */
    private static final SeededRecordFixture SEED =
            SeededRecordFixture.load("trancatg.txt", RECORD_WIDTH);

    /** The seeded type reference file, loaded for the cross-file check only. */
    private static final SeededRecordFixture TYPE_SEED =
            SeededRecordFixture.load("trantype.txt", RECORD_WIDTH);

    /**
     * The eighteen seeded rows, transcribed from the estate's reference data in file order as
     * type, category and description.
     */
    private static final List<String[]> SEEDED_CATEGORIES = List.of(
            new String[] {"01", "0001", "Regular Sales Draft"},
            new String[] {"01", "0002", "Regular Cash Advance"},
            new String[] {"01", "0003", "Convenience Check Debit"},
            new String[] {"01", "0004", "ATM Cash Advance"},
            new String[] {"01", "0005", "Interest Amount"},
            new String[] {"02", "0001", "Cash payment"},
            new String[] {"02", "0002", "Electronic payment"},
            new String[] {"02", "0003", "Check payment"},
            new String[] {"03", "0001", "Credit to Account"},
            new String[] {"03", "0002", "Credit to Purchase balance"},
            new String[] {"03", "0003", "Credit to Cash balance"},
            new String[] {"04", "0001", "Zero dollar authorization"},
            new String[] {"04", "0002", "Online purchase authorization"},
            new String[] {"04", "0003", "Travel booking authorization"},
            new String[] {"05", "0001", "Refund credit"},
            new String[] {"06", "0001", "Fraud reversal"},
            new String[] {"06", "0002", "Non-fraud reversal"},
            new String[] {"07", "0001", "Sales draft credit adjustment"});

    /**
     * Reads one seeded record's transaction type.
     *
     * @param ordinal the one-based record ordinal
     * @return the two-byte transaction type
     */
    private static String seededType(final int ordinal) {
        return SEED.field(ordinal, OFFSET_TYPE, COPYBOOK_WIDTHS.get(0));
    }

    /**
     * Reads one seeded record's category.
     *
     * @param ordinal the one-based record ordinal
     * @return the four-byte category
     */
    private static String seededCategory(final int ordinal) {
        return SEED.field(ordinal, OFFSET_CATEGORY, COPYBOOK_WIDTHS.get(1));
    }

    /**
     * Reads one seeded record's description, at its blank-filled fifty-byte width.
     *
     * @param ordinal the one-based record ordinal
     * @return the fifty-byte description
     */
    private static String seededDescription(final int ordinal) {
        return SEED.field(ordinal, OFFSET_DESCRIPTION, COPYBOOK_WIDTHS.get(2));
    }

    /**
     * Builds the entity one seeded record describes.
     *
     * @param ordinal the one-based record ordinal
     * @return the reference row the record describes
     */
    private static TransactionCategory categoryFromSeed(final int ordinal) {
        return new TransactionCategory(
                seededType(ordinal), seededCategory(ordinal), seededDescription(ordinal));
    }

    /**
     * Returns the description the seed carries for one type-and-category pair.
     *
     * @param type     the two-byte transaction type
     * @param category the four-byte category
     * @return the trimmed description, or {@code null} when the pair is not seeded
     */
    private static String seededDescriptionFor(final String type, final String category) {
        for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
            if (seededType(ordinal).equals(type) && seededCategory(ordinal).equals(category)) {
                return seededDescription(ordinal).strip();
            }
        }
        return null;
    }

    /**
     * Returns the four-digit category the interest run's two-character literal becomes when it is moved
     * into a four-digit numeric field, which right-justifies the literal and zero-fills the left.
     *
     * @return the zero-filled four-character category
     */
    private static String zeroFilledInterestCategory() {
        return "0".repeat(COPYBOOK_WIDTHS.get(1) - INTEREST_CATEGORY_LITERAL.length())
                + INTEREST_CATEGORY_LITERAL;
    }

    // RECORD LAYOUT

    /**
     * Verifies the copybook geometry the entity has to honour.
     */
    @Nested
    @DisplayName("record layout")
    class RecordLayout {

        @Test
        @DisplayName("the four copybook widths sum to the sixty bytes the cluster declares")
        void theWidthsSumToTheRecordSize() {
            assertThat(COPYBOOK_WIDTHS).hasSize(4);
            assertThat(COPYBOOK_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("each field begins where the preceding widths leave off")
        void eachFieldBeginsWhereThePrecedingWidthsLeaveOff() {
            final List<Integer> offsets =
                    List.of(OFFSET_TYPE, OFFSET_CATEGORY, OFFSET_DESCRIPTION, OFFSET_FILLER);

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
        @DisplayName("the first two fields form the six-byte key the cluster declares, so a category is "
                + "identified only in the context of a type")
        void theFirstTwoFieldsFormTheKey() {
            assertThat(COPYBOOK_WIDTHS.get(0) + COPYBOOK_WIDTHS.get(1)).isEqualTo(KEY_WIDTH);
            assertThat(OFFSET_DESCRIPTION)
                    .as("the key runs from the front of the record to where the description begins")
                    .isEqualTo(KEY_WIDTH);
        }

        @Test
        @DisplayName("the trailing four bytes are filler and are mapped to no column")
        void theTrailingBytesAreFillerAndUnmapped() {
            assertThat(COPYBOOK_WIDTHS.get(3)).isEqualTo(FILLER_WIDTH);
            assertThat(OFFSET_FILLER + FILLER_WIDTH).isEqualTo(RECORD_WIDTH);
            assertThat(SCHEMA.columnNames(TABLE)).hasSize(COPYBOOK_WIDTHS.size() - 1);
        }

        @Test
        @DisplayName("the filler is the same width as the category, so a mapper that mistook one for the "
                + "other would still measure sixty bytes and has to be caught by offset instead")
        void theFillerAndCategoryShareAWidth() {
            assertThat(FILLER_WIDTH).isEqualTo(COPYBOOK_WIDTHS.get(1));
            assertThat(OFFSET_FILLER).isNotEqualTo(OFFSET_CATEGORY);
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
        @DisplayName("the table declares the three mapped columns in copybook order")
        void theTableDeclaresTheMappedColumnsInCopybookOrder() {
            assertThat(SCHEMA.columnNames(TABLE))
                    .containsExactly("tran_type_cd", "tran_cat_cd", "tran_cat_type_desc");
        }

        @Test
        @DisplayName("every mapped column matches its copybook width")
        void everyMappedColumnMatchesItsCopybookWidth() {
            final List<String> columns = SCHEMA.columnNames(TABLE);

            for (int index = 0; index < columns.size(); index++) {
                assertThat(SCHEMA.declaredWidth(TABLE, columns.get(index)))
                        .as("declared width of %s", columns.get(index))
                        .isEqualTo(COPYBOOK_WIDTHS.get(index));
            }
        }

        @Test
        @DisplayName("the category column is character rather than integer, so a code of 0001 keeps its "
                + "leading zeros and the stored key still matches the record image")
        void theCategoryColumnIsCharacterSoLeadingZerosSurvive() {
            assertThat(SCHEMA.declaredType(TABLE, "tran_cat_cd")).isEqualTo("VARCHAR(4)");

            assertThat(seededCategory(1)).startsWith("000").hasSize(COPYBOOK_WIDTHS.get(1));
            assertThat(Integer.toString(Integer.parseInt(seededCategory(1))))
                    .as("an integer column would have stored this code without its leading zeros")
                    .isNotEqualTo(seededCategory(1));
        }

        @Test
        @DisplayName("the primary key is both key components in copybook order, and no surrogate or "
                + "version column exists")
        void thePrimaryKeyIsBothComponentsInOrder() {
            assertThat(SCHEMA.primaryKeyColumns(TABLE))
                    .containsExactly("tran_type_cd", "tran_cat_cd");
            assertThat(SCHEMA.columnNames(TABLE))
                    .doesNotContain("id", "transaction_category_id", "version");
        }

        @Test
        @DisplayName("every column is declared not null, so no row can carry an absent description")
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
        @DisplayName("every constructor argument reaches its own accessor")
        void everyConstructorArgumentReachesItsAccessor() {
            final TransactionCategory category =
                    new TransactionCategory("01", "0005", "Interest Amount");

            assertThat(category.getTranTypeCd()).isEqualTo("01");
            assertThat(category.getTranCatCd()).isEqualTo("0005");
            assertThat(category.getTranCatTypeDesc()).isEqualTo("Interest Amount");
        }

        @Test
        @DisplayName("the type and the category do not swap, which their shared leading zero could "
                + "otherwise conceal")
        void theTypeAndCategoryDoNotSwap() {
            final TransactionCategory category = categoryFromSeed(1);

            assertThat(category.getTranTypeCd()).hasSize(COPYBOOK_WIDTHS.get(0));
            assertThat(category.getTranCatCd()).hasSize(COPYBOOK_WIDTHS.get(1));
            assertThat(category.getTranTypeCd()).isNotEqualTo(category.getTranCatCd());
        }

        @Test
        @DisplayName("every mutator replaces exactly the field it names")
        void everyMutatorReplacesTheFieldItNames() {
            final TransactionCategory category = categoryFromSeed(1);

            category.setTranTypeCd("07");
            category.setTranCatCd("0001");
            category.setTranCatTypeDesc("Sales draft credit adjustment");

            assertThat(category.getTranTypeCd()).isEqualTo("07");
            assertThat(category.getTranCatCd()).isEqualTo("0001");
            assertThat(category.getTranCatTypeDesc()).isEqualTo("Sales draft credit adjustment");
        }

        @Test
        @DisplayName("the persistence constructor leaves every field absent")
        void thePersistenceConstructorLeavesEveryFieldAbsent() {
            final TransactionCategory category = new TransactionCategory();

            assertThat(category.getTranTypeCd()).isNull();
            assertThat(category.getTranCatCd()).isNull();
            assertThat(category.getTranCatTypeDesc()).isNull();
        }

        @Test
        @DisplayName("a description at the full fifty-byte width survives intact")
        void aFullWidthDescriptionSurvivesIntact() {
            final String widest = "D".repeat(COPYBOOK_WIDTHS.get(2));

            assertThat(new TransactionCategory("01", "0001", widest).getTranCatTypeDesc())
                    .isEqualTo(widest)
                    .hasSize(COPYBOOK_WIDTHS.get(2));
        }
    }

    // SEEDED REFERENCE DATA

    /**
     * Verifies the eighteen seeded reference rows against a transcription of the estate's own data.
     */
    @Nested
    @DisplayName("seeded reference data")
    class SeededReferenceData {

        @Test
        @DisplayName("the seed carries eighteen records at the declared sixty-byte width")
        void theSeedCarriesEighteenRecords() {
            assertThat(SEED.recordCount()).isEqualTo(SEEDED_RECORDS);
            assertThat(SEED.recordWidth()).isEqualTo(RECORD_WIDTH);
            assertThat(SEED.impliedByteCount()).isEqualTo(SEEDED_RECORDS * (RECORD_WIDTH + 1));
        }

        @Test
        @DisplayName("every seeded row matches the transcription of the estate's reference data, in file "
                + "order")
        void everySeededRowMatchesTheTranscription() {
            assertThat(SEEDED_CATEGORIES).hasSize(SEEDED_RECORDS);

            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                final String[] expected = SEEDED_CATEGORIES.get(ordinal - 1);

                assertThat(seededType(ordinal)).as("type of record %d", ordinal)
                        .isEqualTo(expected[0]);
                assertThat(seededCategory(ordinal)).as("category of record %d", ordinal)
                        .isEqualTo(expected[1]);
                assertThat(seededDescription(ordinal).strip())
                        .as("description of record %d", ordinal)
                        .isEqualTo(expected[2]);
            }
        }

        @Test
        @DisplayName("the seed uses seven type codes and five category codes, so the eighteen rows are "
                + "far fewer than every combination of the two")
        void theSeedUsesSevenTypesAndFiveCategories() {
            final List<String> types = new ArrayList<>();
            final List<String> categories = new ArrayList<>();
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                types.add(seededType(ordinal));
                categories.add(seededCategory(ordinal));
            }

            assertThat(types.stream().distinct().toList()).hasSize(SEEDED_TYPE_CODES);
            assertThat(categories.stream().distinct().toList()).hasSize(SEEDED_CATEGORY_CODES);
            assertThat(SEEDED_RECORDS)
                    .isLessThan(SEEDED_TYPE_CODES * SEEDED_CATEGORY_CODES);
        }

        @Test
        @DisplayName("each type's categories are contiguous from 0001, so no category in a type's range "
                + "is missing")
        void eachTypesCategoriesAreContiguousFromOne() {
            final Map<String, List<String>> byType = new LinkedHashMap<>();
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                byType.computeIfAbsent(seededType(ordinal), key -> new ArrayList<>())
                        .add(seededCategory(ordinal));
            }

            assertThat(byType).hasSize(SEEDED_TYPE_CODES);
            for (final Map.Entry<String, List<String>> entry : byType.entrySet()) {
                final List<String> categories = entry.getValue();
                for (int index = 0; index < categories.size(); index++) {
                    assertThat(Integer.parseInt(categories.get(index)))
                            .as("category %d of type %s", index, entry.getKey())
                            .isEqualTo(index + 1);
                }
            }
        }

        @Test
        @DisplayName("the same category number appears under several types with a different meaning each "
                + "time, which is why the key needs both components")
        void theSameCategoryNumberMeansDifferentThingsUnderDifferentTypes() {
            final List<String> meanings = new ArrayList<>();
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                if ("0001".equals(seededCategory(ordinal))) {
                    meanings.add(seededDescription(ordinal).strip());
                }
            }

            assertThat(meanings).hasSize(SEEDED_TYPE_CODES);
            assertThat(meanings.stream().distinct().toList())
                    .as("every type gives category 0001 its own meaning")
                    .hasSize(SEEDED_TYPE_CODES);
        }

        @Test
        @DisplayName("every description is distinct, so the eighteen rows carry eighteen meanings")
        void everyDescriptionIsDistinct() {
            final List<String> descriptions = new ArrayList<>();
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                descriptions.add(seededDescription(ordinal));
            }

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
                        .hasSize(COPYBOOK_WIDTHS.get(2))
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
        @DisplayName("every type code this file uses is one the transaction-type reference file declares, "
                + "a cross-file agreement neither file can establish alone")
        void everyTypeCodeIsDeclaredByTheTypeReferenceFile() {
            final List<String> declaredTypes = new ArrayList<>();
            for (int ordinal = 1; ordinal <= TYPE_SEED.recordCount(); ordinal++) {
                declaredTypes.add(TYPE_SEED.field(ordinal, 0, COPYBOOK_WIDTHS.get(0)));
            }

            assertThat(declaredTypes).hasSize(SEEDED_TYPE_CODES);
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                assertThat(declaredTypes)
                        .as("type of category record %d", ordinal)
                        .contains(seededType(ordinal));
            }
        }
    }

    // THE INTEREST RUN'S SYNTHESISED PAIR

    /**
     * Verifies the one type-and-category pair the interest run stamps rather than reads.
     */
    @Nested
    @DisplayName("the interest run's synthesised pair")
    class InterestRunPair {

        @Test
        @DisplayName("the two-character category literal becomes four digits by right-justified zero "
                + "fill, which is what moving it into a four-digit numeric field does")
        void theCategoryLiteralZeroFillsToFourDigits() {
            assertThat(INTEREST_CATEGORY_LITERAL).hasSize(2);
            assertThat(zeroFilledInterestCategory())
                    .isEqualTo("0005")
                    .hasSize(COPYBOOK_WIDTHS.get(1))
                    .endsWith(INTEREST_CATEGORY_LITERAL);
        }

        @Test
        @DisplayName("the resulting pair resolves in the reference file, so posted interest carries a "
                + "category a lookup can explain")
        void theResultingPairResolvesInTheReferenceFile() {
            assertThat(seededDescriptionFor(INTEREST_TYPE_CODE, zeroFilledInterestCategory()))
                    .isEqualTo("Interest Amount");
        }

        @Test
        @DisplayName("the pair the interest run stamps builds an entity whose key is exactly six bytes")
        void thePairBuildsASixByteKey() {
            final TransactionCategory row = new TransactionCategory(
                    INTEREST_TYPE_CODE, zeroFilledInterestCategory(), "Interest Amount");

            assertThat(row.getTranTypeCd().length() + row.getTranCatCd().length())
                    .isEqualTo(KEY_WIDTH);
        }

        @Test
        @DisplayName("the unfilled literal is not the same key, so omitting the zero fill would miss the "
                + "reference row entirely")
        void theUnfilledLiteralIsNotTheSameKey() {
            assertThat(seededDescriptionFor(INTEREST_TYPE_CODE, INTEREST_CATEGORY_LITERAL))
                    .as("the two-character literal is not a category the file declares")
                    .isNull();
        }
    }

    // COMPOSITE-KEY IDENTITY

    /**
     * Verifies that identity is the two-part key and nothing else.
     */
    @Nested
    @DisplayName("composite-key identity")
    class CompositeKeyIdentity {

        @Test
        @DisplayName("a row equals itself")
        void aRowEqualsItself() {
            final TransactionCategory category = categoryFromSeed(1);

            assertThat(category).isEqualTo(category);
            assertThat(category.hashCode()).isEqualTo(category.hashCode());
        }

        @Test
        @DisplayName("two rows with the same key are equal even when their descriptions differ, because "
                + "the description is not part of identity")
        void sameKeyMeansEqualEvenWithADifferentDescription() {
            final TransactionCategory left =
                    new TransactionCategory("01", "0005", "Interest Amount");
            final TransactionCategory right =
                    new TransactionCategory("01", "0005", "Something else entirely");

            assertThat(left).isEqualTo(right);
            assertThat(right).isEqualTo(left);
            assertThat(left).hasSameHashCodeAs(right);
            assertThat(left.getTranCatTypeDesc()).isNotEqualTo(right.getTranCatTypeDesc());
        }

        @Test
        @DisplayName("changing either key component makes two rows unequal")
        void changingEitherKeyComponentMakesRowsUnequal() {
            final TransactionCategory base =
                    new TransactionCategory("01", "0001", "Regular Sales Draft");

            assertThat(base).isNotEqualTo(
                    new TransactionCategory("02", "0001", "Regular Sales Draft"));
            assertThat(base).isNotEqualTo(
                    new TransactionCategory("01", "0002", "Regular Sales Draft"));
        }

        @Test
        @DisplayName("the eighteen seeded rows produce eighteen distinct keys, so no two rows collide")
        void theSeededRowsProduceDistinctKeys() {
            final List<TransactionCategoryId> ids = new ArrayList<>();
            for (int ordinal = 1; ordinal <= SEED.recordCount(); ordinal++) {
                ids.add(categoryFromSeed(ordinal).toId());
            }

            assertThat(ids).hasSize(SEEDED_RECORDS);
            assertThat(ids.stream().distinct().toList()).hasSize(SEEDED_RECORDS);
        }

        @Test
        @DisplayName("a row is unequal to null and to an unrelated type")
        void aRowIsUnequalToNullAndToAnotherType() {
            final TransactionCategory category = categoryFromSeed(1);

            assertThat(category).isNotEqualTo(null);
            assertThat(category.equals("010001")).isFalse();
            assertThat(category).isNotEqualTo(new Object());
        }

        @Test
        @DisplayName("two rows with an absent key are equal, because both keys are absent rather than "
                + "generated")
        void twoUnkeyedRowsAreEqual() {
            assertThat(new TransactionCategory()).isEqualTo(new TransactionCategory());
            assertThat(new TransactionCategory()).hasSameHashCodeAs(new TransactionCategory());
        }
    }

    // THE EXTRACTED KEY

    /**
     * Verifies that the entity hands out the same two-part key it is identified by.
     */
    @Nested
    @DisplayName("the extracted key")
    class ExtractedKey {

        @Test
        @DisplayName("the extracted key carries the two components in copybook order")
        void theExtractedKeyCarriesTheComponentsInCopybookOrder() {
            final TransactionCategoryId id = categoryFromSeed(1).toId();

            assertThat(id.getTranTypeCd()).isEqualTo(seededType(1));
            assertThat(id.getTranCatCd()).isEqualTo(seededCategory(1));
        }

        @Test
        @DisplayName("the extracted key round-trips: rebuilding a row from it yields an equal row")
        void theExtractedKeyRoundTrips() {
            final TransactionCategory original = categoryFromSeed(1);
            final TransactionCategoryId id = original.toId();
            final TransactionCategory rebuilt =
                    new TransactionCategory(id.getTranTypeCd(), id.getTranCatCd(), "");

            assertThat(rebuilt).isEqualTo(original);
            assertThat(rebuilt.toId()).isEqualTo(id);
        }

        @Test
        @DisplayName("a key whose components are swapped is a different key, so component order is part "
                + "of the contract")
        void aSwappedKeyIsADifferentKey() {
            final TransactionCategoryId ordered = new TransactionCategoryId("AB", "CD");
            final TransactionCategoryId swapped = new TransactionCategoryId("CD", "AB");

            assertThat(ordered).isNotEqualTo(swapped);
        }

        @Test
        @DisplayName("an unkeyed row hands out a key whose components are both absent")
        void anUnkeyedRowHandsOutAnEmptyKey() {
            final TransactionCategoryId id = new TransactionCategory().toId();

            assertThat(id.getTranTypeCd()).isNull();
            assertThat(id.getTranCatCd()).isNull();
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
        @DisplayName("the diagnostic string names the type and all three fields")
        void theDiagnosticStringNamesTheTypeAndAllThreeFields() {
            assertThat(new TransactionCategory("01", "0005", "Interest Amount").toString())
                    .isEqualTo("TransactionCategory[tranTypeCd=01, tranCatCd=0005, "
                            + "tranCatTypeDesc=Interest Amount]");
        }

        @Test
        @DisplayName("the diagnostic string carries the description, so two rows that are equal can "
                + "still render differently")
        void equalRowsCanRenderDifferently() {
            final TransactionCategory left =
                    new TransactionCategory("01", "0005", "Interest Amount");
            final TransactionCategory right =
                    new TransactionCategory("01", "0005", "Something else entirely");

            assertThat(left).isEqualTo(right);
            assertThat(left.toString()).isNotEqualTo(right.toString());
        }

        @Test
        @DisplayName("an unkeyed row renders without failing")
        void anUnkeyedRowRendersWithoutFailing() {
            assertThat(new TransactionCategory().toString()).isEqualTo(
                    "TransactionCategory[tranTypeCd=null, tranCatCd=null, tranCatTypeDesc=null]");
        }
    }
}
