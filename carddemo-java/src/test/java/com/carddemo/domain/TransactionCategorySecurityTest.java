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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.carddemo.domain.id.TransactionCategoryId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link TransactionCategory}, the Java realisation of the 60-byte
 * {@code TRAN-CAT-RECORD} layout declared by copybook member {@code CVTRA04Y}.
 *
 * <h2>Composite key, in one specific order</h2>
 *
 * <p>This is one of the three tables whose primary key spans more than one column, and the order of
 * the two components is not arbitrary: the type code precedes the category code, matching the offsets
 * in the record image and the {@code KEYS} clause of the cluster definition. A test that only asserted
 * "both components must match" would pass against a transposed identifier, so the assertions below
 * check the components <em>individually</em> by holding one constant while varying the other, and check
 * the produced identifier component by component rather than as a whole.</p>
 *
 * <h2>Exact comparison, because every character of the key is significant</h2>
 *
 * <p>The category code is four characters wide and is not a number. A code of {@code "0005"} and one of
 * {@code "5"} are different rows in the database and must be different keys in Java, so no accessor and
 * no comparison trims, pads or folds.</p>
 */
@DisplayName("TransactionCategory - the 60-byte CVTRA04Y transaction category record")
class TransactionCategorySecurityTest {

    private static final String TYPE_CODE = "01";
    private static final String CATEGORY_CODE = "0005";
    private static final String DESCRIPTION = "REGULAR SALES DRAFT";

    /** A fully populated transaction category row. */
    private static TransactionCategory category() {
        return new TransactionCategory(TYPE_CODE, CATEGORY_CODE, DESCRIPTION);
    }

    @Nested
    @DisplayName("Construction from the record image")
    class Construction {

        @Test
        @DisplayName("the three-argument constructor assigns every field to its own accessor, so no two of the "
                + "three same-typed arguments is transposed")
        void everyFieldLandsOnItsOwnAccessor() {
            final TransactionCategory subject = category();
            assertThat(subject.getTranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(subject.getTranCatCd()).isEqualTo(CATEGORY_CODE);
            assertThat(subject.getTranCatTypeDesc()).isEqualTo(DESCRIPTION);
        }

        @Test
        @DisplayName("the three fixtures are distinguishable, so a transposition would be caught")
        void theThreeFixturesAreDistinguishable() {
            assertThat(Set.of(TYPE_CODE, CATEGORY_CODE, DESCRIPTION)).hasSize(3);
        }

        @Test
        @DisplayName("the provider constructor leaves every field unset")
        void theProviderConstructorLeavesEveryFieldUnset() {
            final TransactionCategory subject = new TransactionCategory();
            assertThat(subject.getTranTypeCd()).isNull();
            assertThat(subject.getTranCatCd()).isNull();
            assertThat(subject.getTranCatTypeDesc()).isNull();
        }

        @Test
        @DisplayName("all three setters are individually effective")
        void allThreeSettersAreIndividuallyEffective() {
            final TransactionCategory subject = new TransactionCategory();
            subject.setTranTypeCd(TYPE_CODE);
            subject.setTranCatCd(CATEGORY_CODE);
            subject.setTranCatTypeDesc(DESCRIPTION);
            assertThat(subject).isEqualTo(category());
            assertThat(subject.getTranCatTypeDesc()).isEqualTo(DESCRIPTION);
        }

        @Test
        @DisplayName("a setter accepts null, because nullability is enforced by the column")
        void aSetterAcceptsNull() {
            final TransactionCategory subject = category();
            subject.setTranCatTypeDesc(null);
            assertThat(subject.getTranCatTypeDesc()).isNull();
        }
    }

    @Nested
    @DisplayName("Field widths carried through from the record layout")
    class RecordLayoutWidths {

        @Test
        @DisplayName("the type code is 2 characters and the category code is 4, matching PIC X(2) and PIC X(4)")
        void theKeyComponentsCarryTheirDeclaredWidths() {
            assertThat(category().getTranTypeCd()).hasSize(2);
            assertThat(category().getTranCatCd()).hasSize(4);
        }

        @Test
        @DisplayName("the category code keeps its leading zeros, because the key is a substring of the record image "
                + "rather than a number")
        void theCategoryCodeKeepsItsLeadingZeros() {
            assertThat(category().getTranCatCd()).isEqualTo("0005").isNotEqualTo("5");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "5", "  padded  "})
        @DisplayName("the entity stores a value of any width verbatim, without trimming, because the column length "
                + "polices the width")
        void theEntityStoresAValueOfAnyWidthVerbatim(final String candidate) {
            final TransactionCategory subject = new TransactionCategory();
            subject.setTranCatCd(candidate);
            subject.setTranCatTypeDesc(candidate);
            assertThat(subject.getTranCatCd()).isEqualTo(candidate);
            assertThat(subject.getTranCatTypeDesc()).isEqualTo(candidate);
        }
    }

    @Nested
    @DisplayName("The composite identifier this row produces")
    class CompositeIdentifier {

        @Test
        @DisplayName("the identifier carries the two key components in declaration order, type code first")
        void theIdentifierCarriesTheTwoKeyComponentsInOrder() {
            final TransactionCategoryId id = category().toId();
            assertThat(id.getTranTypeCd()).isEqualTo(TYPE_CODE);
            assertThat(id.getTranCatCd()).isEqualTo(CATEGORY_CODE);
        }

        @Test
        @DisplayName("the identifier equals one assembled by hand from the same two components in the same order")
        void theIdentifierEqualsOneAssembledByHand() {
            assertThat(category().toId())
                    .isEqualTo(new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE));
        }

        @Test
        @DisplayName("the identifier does NOT equal one assembled with the components transposed, which is the "
                + "assertion that actually pins the component order")
        void theIdentifierDoesNotEqualATransposedOne() {
            assertThat(category().toId())
                    .isNotEqualTo(new TransactionCategoryId(CATEGORY_CODE, TYPE_CODE));
        }

        @Test
        @DisplayName("a new identifier is produced on every call, so nothing is cached or shared between rows")
        void aNewIdentifierIsProducedOnEveryCall() {
            final TransactionCategory subject = category();
            final TransactionCategoryId first = subject.toId();
            final TransactionCategoryId second = subject.toId();
            assertThat(first).isEqualTo(second).isNotSameAs(second);
        }

        @Test
        @DisplayName("the identifier follows a mutated key component, so it reflects current state rather than "
                + "construction-time state")
        void theIdentifierFollowsAMutatedKeyComponent() {
            final TransactionCategory subject = category();
            subject.setTranCatCd("0001");
            assertThat(subject.toId()).isEqualTo(new TransactionCategoryId(TYPE_CODE, "0001"));
        }

        @Test
        @DisplayName("the identifier of an unset row carries two nulls rather than throwing")
        void theIdentifierOfAnUnsetRowCarriesTwoNulls() {
            final TransactionCategoryId id = new TransactionCategory().toId();
            assertThat(id.getTranTypeCd()).isNull();
            assertThat(id.getTranCatCd()).isNull();
        }
    }

    @Nested
    @DisplayName("Identity: the two key components, exactly, and never the description")
    class Identity {

        @Test
        @DisplayName("a row equals itself")
        void aRowEqualsItself() {
            final TransactionCategory subject = category();
            assertThat(subject).isEqualTo(subject);
        }

        @Test
        @DisplayName("two rows with the same key are equal even when their descriptions differ, because the "
                + "description is mutable state rather than identity")
        void theKeyAloneDeterminesEquality() {
            final TransactionCategory left = category();
            final TransactionCategory right = new TransactionCategory(TYPE_CODE, CATEGORY_CODE,
                    "SOMETHING ENTIRELY DIFFERENT");
            assertThat(left).isEqualTo(right);
            assertThat(left).hasSameHashCodeAs(right);
        }

        @Test
        @DisplayName("the type code participates individually")
        void theTypeCodeParticipatesIndividually() {
            assertThat(category())
                    .isNotEqualTo(new TransactionCategory("02", CATEGORY_CODE, DESCRIPTION));
        }

        @Test
        @DisplayName("the category code participates individually")
        void theCategoryCodeParticipatesIndividually() {
            assertThat(category())
                    .isNotEqualTo(new TransactionCategory(TYPE_CODE, "0001", DESCRIPTION));
        }

        @Test
        @DisplayName("a zero-padded category code is not equal to its numeric shorthand, because every character of "
                + "a fixed-width key is significant")
        void aZeroPaddedCategoryCodeIsNotEqualToItsShorthand() {
            assertThat(category())
                    .isNotEqualTo(new TransactionCategory(TYPE_CODE, "5", DESCRIPTION));
        }

        @Test
        @DisplayName("a row is not equal to null and not equal to a foreign type - notably not to its own composite "
                + "identifier, which carries the same two components")
        void aRowIsNotEqualToNullOrAForeignType() {
            final TransactionCategory subject = category();
            assertThat(subject).isNotEqualTo(null);
            assertThat(subject.equals(subject.toId())).isFalse();
            assertThat(subject.equals(new Object())).isFalse();
        }

        @Test
        @DisplayName("two unset rows are equal, because both key components are null on both")
        void twoUnsetRowsAreEqual() {
            assertThat(new TransactionCategory()).isEqualTo(new TransactionCategory());
        }

        @Test
        @DisplayName("changing the description does not change the hash code, so a row already in a set cannot move")
        void changingTheDescriptionDoesNotChangeTheHashCode() {
            final TransactionCategory subject = category();
            final int before = subject.hashCode();
            subject.setTranCatTypeDesc("A COMPLETELY NEW DESCRIPTION");
            assertThat(subject.hashCode()).isEqualTo(before);
        }

        @Test
        @DisplayName("a row remains findable in a hash set and usable as a map key after its description has changed")
        void aRowRemainsFindableAfterItsDescriptionHasChanged() {
            final Set<TransactionCategory> rows = new HashSet<>();
            final Map<TransactionCategory, String> byRow = new HashMap<>();
            final TransactionCategory subject = category();
            rows.add(subject);
            byRow.put(subject, "seeded");
            subject.setTranCatTypeDesc("RENAMED");
            assertThat(rows).contains(subject);
            assertThat(byRow.get(category())).isEqualTo("seeded");
        }

        @Test
        @DisplayName("the hash code is stable across repeated invocations")
        void theHashCodeIsStableAcrossRepeatedInvocations() {
            final TransactionCategory subject = category();
            assertThat(subject.hashCode()).isEqualTo(subject.hashCode());
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering: all three mapped fields, none of which requires redaction")
    class DiagnosticRendering {

        @Test
        @DisplayName("the rendering carries all three mapped fields, because none is a credential or a monetary "
                + "value")
        void theRenderingCarriesAllThreeMappedFields() {
            assertThat(category().toString())
                    .isEqualTo("TransactionCategory[tranTypeCd=" + TYPE_CODE
                            + ", tranCatCd=" + CATEGORY_CODE
                            + ", tranCatTypeDesc=" + DESCRIPTION + "]");
        }

        @Test
        @DisplayName("the description is reproduced as stored, padding included")
        void theDescriptionIsReproducedAsStored() {
            final TransactionCategory subject = new TransactionCategory(TYPE_CODE, CATEGORY_CODE,
                    "  padded  ");
            assertThat(subject.toString()).contains("tranCatTypeDesc=  padded  ]");
        }

        @Test
        @DisplayName("the component list carries no credential or monetary marker, so this reference row is safe to "
                + "log in full")
        void theComponentListCarriesNothingSensitive() {
            final String rendering = category().toString();
            final String componentList =
                    rendering.substring(rendering.indexOf('[') + 1, rendering.length() - 1);
            assertThat(componentList.toUpperCase(java.util.Locale.ROOT))
                    .doesNotContain("PASSWORD", "SECRET", "SSN", "$2A$", "AMT=", "BAL=");
        }

        @Test
        @DisplayName("an unset row renders without throwing")
        void anUnsetRowRendersWithoutThrowing() {
            assertThat(new TransactionCategory().toString())
                    .startsWith("TransactionCategory[")
                    .contains("null")
                    .endsWith("]");
        }
    }
}
