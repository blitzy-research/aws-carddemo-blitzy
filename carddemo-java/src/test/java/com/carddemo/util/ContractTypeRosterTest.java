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
package com.carddemo.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.api.PublishedContractTypeRoster;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The {@link ContractTypeRoster} contract, exercised through the interface rather than through its
 * implementation.
 *
 * <h2>Why a test that goes through the interface earns its place</h2>
 *
 * <p>The interface exists to hold one direction of dependency open. The families it lists are transport
 * types owned by the boundary package, and the layering rule forbids the configuration package from
 * importing the boundary; naming them in the configuration would invert that direction, and naming them
 * nowhere would leave the published interface description covering only what a scanned controller signature
 * happened to mention. The interface resolves it: the boundary declares the roster, the configuration reads
 * it through a contract the base layer owns, and neither package imports the other.
 *
 * <p>That architecture is only real if the contract is genuinely usable through the interface type. Until
 * this class existed, every test naming the roster named the concrete
 * {@link PublishedContractTypeRoster} - so the behaviour was covered and the <em>abstraction</em> was not.
 * Nothing demonstrated that a second implementation could satisfy the contract, and nothing held the
 * documented guarantees - never null, never empty, unmodifiable - against anything but the one class that
 * happens to return a {@code List.of(...)}. Every reference below is declared as
 * {@code ContractTypeRoster}, which is what makes this a test of the contract.
 *
 * <h2>What is asserted, and against what</h2>
 *
 * <p>The three documented guarantees are asserted twice: once against the delivered implementation, and once
 * against a minimal local one declared here. The local one is what distinguishes a contract from a
 * description of one class's behaviour - if the guarantees were only reachable through the delivered class,
 * a consumer written against the interface would have nothing to rely on.
 */
@DisplayName("ContractTypeRoster: the roster contract, exercised through the interface it declares")
class ContractTypeRosterTest {

    /** Creates the specification. */
    ContractTypeRosterTest() {
        // Intentionally empty: every reference below is created inside the test that uses it.
    }

    /**
     * A minimal implementation, declared here so the contract is proven implementable by more than one type.
     *
     * <p>It returns two of the families the delivered roster also carries, through
     * {@link List#copyOf(java.util.Collection)}, which is how an implementation satisfies the unmodifiable
     * guarantee without depending on the literal factory the delivered one happens to use.
     */
    private static final class TwoFamilyRoster implements ContractTypeRoster {

        /** Creates the roster. */
        TwoFamilyRoster() {
            // Intentionally empty: the roster is a constant.
        }

        @Override
        public List<Class<?>> publishedContractTypes() {
            final List<Class<?>> families = new ArrayList<>(2);
            families.add(com.carddemo.api.dto.SignOnRequest.class);
            families.add(com.carddemo.api.dto.SignOnResponse.class);
            return List.copyOf(families);
        }
    }

    @Nested
    @DisplayName("the three documented guarantees hold for every implementation of the contract")
    class TheDocumentedGuaranteesHold {

        /** Creates the nest. */
        TheDocumentedGuaranteesHold() {
            // Intentionally empty: this nest contributes tests, not state.
        }


        @Test
        @DisplayName("the delivered roster, read through the interface, returns a non-null, non-empty, "
                + "unmodifiable list of types")
        void theDeliveredRosterHonoursTheContractThroughTheInterface() {
            final ContractTypeRoster roster = new PublishedContractTypeRoster();

            final List<Class<?>> published = roster.publishedContractTypes();

            assertThat(published)
                    .as("the contract states the list is never null and never empty, because a roster that "
                            + "may be empty leaves the published description silently incomplete")
                    .isNotNull()
                    .isNotEmpty()
                    .doesNotContainNull();
            assertThatThrownBy(() -> published.add(String.class))
                    .as("the contract states the list is unmodifiable: a consumer that could add to it "
                            + "would be able to publish a schema the boundary never declared")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("and so does a second, independent implementation, which is what makes this a contract "
                + "rather than a description of one class")
        void aSecondImplementationHonoursTheSameContract() {
            final ContractTypeRoster roster = new TwoFamilyRoster();

            final List<Class<?>> published = roster.publishedContractTypes();

            assertThat(published)
                    .as("an implementation the delivered configuration has never seen satisfies the same "
                            + "three guarantees, so a consumer may hold the interface and nothing more")
                    .isNotNull()
                    .isNotEmpty()
                    .doesNotContainNull()
                    .hasSize(2);
            assertThatThrownBy(() -> published.remove(0))
                    .as("including the unmodifiable guarantee, reached through a different factory than "
                            + "the delivered roster uses")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("two calls on one roster return equal contents, so a reader of the published "
                + "description cannot be handed a different roster on a second pass")
        void theRosterIsStableAcrossCalls() {
            final ContractTypeRoster roster = new PublishedContractTypeRoster();

            assertThat(roster.publishedContractTypes())
                    .as("the description is produced by more than one pass over the roster, and a roster "
                            + "that answered differently each time would publish differently each time")
                    .containsExactlyElementsOf(roster.publishedContractTypes());
        }
    }

    @Nested
    @DisplayName("the contract is a neutral interface, which is the whole reason it exists")
    class TheContractIsNeutral {

        /** Creates the nest. */
        TheContractIsNeutral() {
            // Intentionally empty: this nest contributes tests, not state.
        }

        @Test
        @DisplayName("it is an interface with exactly one abstract method, so it constrains its "
                + "implementors to supplying types and nothing else")
        void theContractDeclaresOneAbstractMethodAndNothingElse() {
            final Class<ContractTypeRoster> contract = ContractTypeRoster.class;

            assertThat(contract.isInterface())
                    .as("a class here would force its implementors to inherit an implementation, and the "
                            + "roster's whole purpose is to let the boundary own the list")
                    .isTrue();
            assertThat(contract.getDeclaredMethods())
                    .as("one method: an implementation supplies types, never schemas. Every property, "
                            + "width, format and access mode in the published document is derived from the "
                            + "annotations on the type itself")
                    .singleElement()
                    .satisfies(method -> {
                        assertThat(method.getName()).isEqualTo("publishedContractTypes");
                        assertThat(Modifier.isAbstract(method.getModifiers())).isTrue();
                        assertThat(method.getParameterCount()).isZero();
                        assertThat(method.getReturnType()).isEqualTo(List.class);
                    });
        }

        @Test
        @DisplayName("it is declared in the base layer and its delivered implementation in the boundary, "
                + "which is the dependency direction it exists to preserve")
        void theContractSitsBelowTheBoundaryThatImplementsIt() {
            assertThat(ContractTypeRoster.class.getPackageName())
                    .as("the contract is owned by the layer both the boundary and the configuration may "
                            + "depend on")
                    .isEqualTo("com.carddemo.util");
            assertThat(PublishedContractTypeRoster.class.getPackageName())
                    .as("and the delivered roster is owned by the boundary, so the transport types it "
                            + "names are named in the package that owns them")
                    .isEqualTo("com.carddemo.api");
            assertThat(ContractTypeRoster.class)
                    .as("the delivered roster implements the contract rather than being reached "
                            + "concretely, which is what lets the configuration read it without importing "
                            + "the boundary")
                    .isAssignableFrom(PublishedContractTypeRoster.class);
        }
    }
}
