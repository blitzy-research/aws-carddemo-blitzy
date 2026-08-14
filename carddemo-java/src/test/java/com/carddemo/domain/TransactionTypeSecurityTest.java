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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link TransactionType}, the Java realisation of the 60-byte
 * {@code TRAN-TYPE-RECORD} layout declared by copybook member {@code CVTRA03Y}.
 *
 * <h2>The smallest entity in the estate, and the one with the sharpest key hazard</h2>
 *
 * <p>This is a two-field reference row: a two-character type code that is the primary key, and a
 * fifty-character description. Precisely because it is small, the temptation to treat the key as a
 * number is strongest - the seeded codes are {@code "01"} through {@code "07"}. They are not numbers.
 * The key is a two-byte substring of the record image, so {@code "01"} and {@code "1"} are different
 * rows and must be different keys, and the leading zero is data rather than formatting. That is the
 * property the identity assertions below pin hardest.</p>
 *
 * <h2>Nothing on this type requires redaction, and asserting that is not vacuous</h2>
 *
 * <p>Neither field is a credential and neither is a monetary value, so the diagnostic rendering carries
 * both in full. The assertions therefore run in the opposite direction from the entities that do carry
 * sensitive state: they confirm both fields are <em>present</em>, and separately confirm the rendering
 * carries no credential or monetary marker, so a later edit that added such a field would have to
 * revisit this test rather than slipping a new value into an already-permissive rendering.</p>
 */
@DisplayName("TransactionType - the 60-byte CVTRA03Y transaction type record")
class TransactionTypeSecurityTest {

    private static final String TYPE_CODE = "01";
    private static final String DESCRIPTION = "PURCHASE";

    /** A fully populated transaction type row. */
    private static TransactionType type() {
        return new TransactionType(TYPE_CODE, DESCRIPTION);
    }

    @Nested
    @DisplayName("Construction from the record image")
    class Construction {

        @Test
        @DisplayName("the two-argument constructor assigns each field to its own accessor, so the two same-typed "
                + "arguments are not transposed")
        void eachFieldLandsOnItsOwnAccessor() {
            final TransactionType subject = type();
            assertThat(subject.getTranType()).isEqualTo(TYPE_CODE);
            assertThat(subject.getTranTypeDesc()).isEqualTo(DESCRIPTION);
        }

        @Test
        @DisplayName("the two fixtures are distinguishable, so a transposition would be caught rather than hidden")
        void theTwoFixturesAreDistinguishable() {
            assertThat(TYPE_CODE).isNotEqualTo(DESCRIPTION);
        }

        @Test
        @DisplayName("the provider constructor leaves both fields unset")
        void theProviderConstructorLeavesBothFieldsUnset() {
            final TransactionType subject = new TransactionType();
            assertThat(subject.getTranType()).isNull();
            assertThat(subject.getTranTypeDesc()).isNull();
        }

        @Test
        @DisplayName("both setters are individually effective")
        void bothSettersAreIndividuallyEffective() {
            final TransactionType subject = new TransactionType();
            subject.setTranType(TYPE_CODE);
            subject.setTranTypeDesc(DESCRIPTION);
            assertThat(subject.getTranType()).isEqualTo(TYPE_CODE);
            assertThat(subject.getTranTypeDesc()).isEqualTo(DESCRIPTION);
            assertThat(subject).isEqualTo(type());
        }

        @Test
        @DisplayName("a setter changes exactly its own field")
        void aSetterChangesExactlyItsOwnField() {
            final TransactionType subject = type();
            subject.setTranTypeDesc("RETURN");
            assertThat(subject.getTranTypeDesc()).isEqualTo("RETURN");
            assertThat(subject.getTranType()).isEqualTo(TYPE_CODE);
        }

        @Test
        @DisplayName("a setter accepts null, because nullability is enforced by the column")
        void aSetterAcceptsNull() {
            final TransactionType subject = type();
            subject.setTranTypeDesc(null);
            subject.setTranType(null);
            assertThat(subject.getTranTypeDesc()).isNull();
            assertThat(subject.getTranType()).isNull();
        }
    }

    @Nested
    @DisplayName("Field widths carried through from the record layout")
    class RecordLayoutWidths {

        @Test
        @DisplayName("the type code is 2 characters at offset 0, matching PIC X(2)")
        void theTypeCodeIsTwoCharacters() {
            assertThat(type().getTranType()).hasSize(2);
        }

        @Test
        @DisplayName("the type code keeps its leading zero, because the key is a substring of the record image "
                + "rather than a number")
        void theTypeCodeKeepsItsLeadingZero() {
            assertThat(type().getTranType()).isEqualTo("01").isNotEqualTo("1");
        }

        @Test
        @DisplayName("all seven seeded type codes are two characters with a leading zero preserved")
        void allSevenSeededTypeCodesAreTwoCharacters() {
            final Set<String> seeded =
                    Set.of("01", "02", "03", "04", "05", "06", "07");
            assertThat(seeded).hasSize(7).allSatisfy(code -> {
                assertThat(code).hasSize(2).startsWith("0");
                assertThat(new TransactionType(code, DESCRIPTION).getTranType()).isEqualTo(code);
            });
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "1", "  padded  ", "0000000000"})
        @DisplayName("the entity stores a value of any width verbatim, without trimming, because the column length "
                + "polices the width rather than the entity re-policing it")
        void theEntityStoresAValueOfAnyWidthVerbatim(final String candidate) {
            final TransactionType subject = new TransactionType();
            subject.setTranType(candidate);
            subject.setTranTypeDesc(candidate);
            assertThat(subject.getTranType()).isEqualTo(candidate);
            assertThat(subject.getTranTypeDesc()).isEqualTo(candidate);
        }

        @Test
        @DisplayName("the description keeps its blank padding, which is real data in a fixed-width record")
        void theDescriptionKeepsItsBlankPadding() {
            final TransactionType subject = new TransactionType(TYPE_CODE, "PURCHASE  ");
            assertThat(subject.getTranTypeDesc()).isEqualTo("PURCHASE  ").isNotEqualTo("PURCHASE");
        }
    }

    @Nested
    @DisplayName("Identity: the type code alone, compared exactly")
    class Identity {

        @Test
        @DisplayName("a row equals itself")
        void aRowEqualsItself() {
            final TransactionType subject = type();
            assertThat(subject).isEqualTo(subject);
        }

        @Test
        @DisplayName("two rows with the same code are equal even when their descriptions differ, because the "
                + "description is mutable state rather than identity")
        void theCodeAloneDeterminesEquality() {
            final TransactionType left = type();
            final TransactionType right = new TransactionType(TYPE_CODE, "SOMETHING ELSE");
            assertThat(left).isEqualTo(right);
            assertThat(right).isEqualTo(left);
            assertThat(left).hasSameHashCodeAs(right);
        }

        @Test
        @DisplayName("two rows with different codes are unequal even when their descriptions match")
        void aDifferentCodeMakesTwoRowsUnequal() {
            assertThat(type()).isNotEqualTo(new TransactionType("02", DESCRIPTION));
        }

        @Test
        @DisplayName("a zero-padded code is not equal to its numeric shorthand, which is the sharpest hazard on a "
                + "two-character key that happens to look like a number")
        void aZeroPaddedCodeIsNotEqualToItsShorthand() {
            assertThat(type()).isNotEqualTo(new TransactionType("1", DESCRIPTION));
            assertThat(type().hashCode()).isNotEqualTo(new TransactionType("1", DESCRIPTION).hashCode());
        }

        @Test
        @DisplayName("all seven seeded codes are mutually unequal, so no two reference rows collide")
        void allSevenSeededCodesAreMutuallyUnequal() {
            final Set<TransactionType> rows = new HashSet<>();
            for (final String code : Set.of("01", "02", "03", "04", "05", "06", "07")) {
                rows.add(new TransactionType(code, DESCRIPTION));
            }
            assertThat(rows).hasSize(7);
        }

        @Test
        @DisplayName("a row is not equal to null and not equal to a foreign type")
        void aRowIsNotEqualToNullOrAForeignType() {
            final TransactionType subject = type();
            assertThat(subject).isNotEqualTo(null);
            assertThat(subject.equals(TYPE_CODE)).isFalse();
            assertThat(subject.equals(new Object())).isFalse();
        }

        @Test
        @DisplayName("two unset rows are equal, and an unset row hashes without throwing because the hash is null "
                + "tolerant")
        void twoUnsetRowsAreEqualAndHashWithoutThrowing() {
            assertThat(new TransactionType()).isEqualTo(new TransactionType());
            assertThat(new TransactionType().hashCode()).isZero();
        }

        @Test
        @DisplayName("an unset row is not equal to a populated one, in both directions")
        void anUnsetRowIsNotEqualToAPopulatedOne() {
            assertThat(new TransactionType()).isNotEqualTo(type());
            assertThat(type()).isNotEqualTo(new TransactionType());
        }

        @Test
        @DisplayName("changing the description does not change the hash code, so a row already in a set cannot move")
        void changingTheDescriptionDoesNotChangeTheHashCode() {
            final TransactionType subject = type();
            final int before = subject.hashCode();
            subject.setTranTypeDesc("A COMPLETELY NEW DESCRIPTION");
            assertThat(subject.hashCode()).isEqualTo(before);
        }

        @Test
        @DisplayName("a row remains findable in a hash set and usable as a map key after its description has changed")
        void aRowRemainsFindableAfterItsDescriptionHasChanged() {
            final Set<TransactionType> rows = new HashSet<>();
            final Map<TransactionType, String> byRow = new HashMap<>();
            final TransactionType subject = type();
            rows.add(subject);
            byRow.put(subject, "seeded");
            subject.setTranTypeDesc("RENAMED");
            assertThat(rows).contains(subject);
            assertThat(byRow.get(type())).isEqualTo("seeded");
        }

        @Test
        @DisplayName("the hash code is stable across repeated invocations")
        void theHashCodeIsStableAcrossRepeatedInvocations() {
            final TransactionType subject = type();
            assertThat(subject.hashCode()).isEqualTo(subject.hashCode());
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering: both fields in full, neither requiring redaction")
    class DiagnosticRendering {

        @Test
        @DisplayName("the rendering carries both fields, because neither is a credential and neither is a monetary "
                + "value")
        void theRenderingCarriesBothFields() {
            assertThat(type().toString())
                    .isEqualTo("TransactionType[tranType=" + TYPE_CODE
                            + ", tranTypeDesc=" + DESCRIPTION + "]");
        }

        @Test
        @DisplayName("the description is rendered exactly as stored, blank padding included")
        void theDescriptionIsRenderedExactlyAsStored() {
            assertThat(new TransactionType(TYPE_CODE, "PURCHASE  ").toString())
                    .endsWith("tranTypeDesc=PURCHASE  ]");
        }

        @Test
        @DisplayName("the component list carries no credential or monetary marker, so a later edit that added such "
                + "a field would have to revisit this assertion rather than slipping into a permissive rendering")
        void theComponentListCarriesNothingSensitive() {
            final String rendering = type().toString();
            final String componentList =
                    rendering.substring(rendering.indexOf('[') + 1, rendering.length() - 1);
            assertThat(componentList.toUpperCase(Locale.ROOT))
                    .doesNotContain("PASSWORD", "SECRET", "SSN", "$2A$", "AMT=", "BAL=", "RATE=");
        }

        @Test
        @DisplayName("an unset row renders without throwing")
        void anUnsetRowRendersWithoutThrowing() {
            assertThat(new TransactionType().toString())
                    .isEqualTo("TransactionType[tranType=null, tranTypeDesc=null]");
        }
    }
}
