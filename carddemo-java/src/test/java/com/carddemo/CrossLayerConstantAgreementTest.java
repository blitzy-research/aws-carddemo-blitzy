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
package com.carddemo;

import com.carddemo.api.dto.AccountUpdateResponse;
import com.carddemo.api.dto.PageMetadata;
import com.carddemo.api.dto.StatementSummary;
import com.carddemo.api.dto.UserRequest;
import com.carddemo.api.dto.UserResponse;
import com.carddemo.service.AccountUpdateOutcome;
import com.carddemo.service.BrowseWindow;
import com.carddemo.service.StatementLineSummary;
import com.carddemo.service.UserCommand;
import com.carddemo.service.UserOutcome;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every constant that is declared on both sides of a wire/service twin pair, asserted to be identical.
 *
 * <p><strong>Why this test exists.</strong> Closing the module's upward package edges meant giving several
 * wire records a service-owned counterpart: the layering runs one way, so a service may not name an
 * {@code api.dto} type, and {@code api.dto} may name nothing but {@code domain.enums}. Where a constant is
 * genuinely needed on both sides - a screen message the service emits and the response publishes, a page
 * size the browse honours and the paging record declares - neither declaration can reference the other
 * without recreating exactly the edge that was removed. Both therefore declare, and this class is what
 * makes the duplication safe: a divergence is a build failure here rather than a parity defect several
 * layers away from where it was introduced.
 *
 * <p><strong>Why the values are read reflectively.</strong> Naming all fifty-odd pairs by hand would let a
 * newly added constant escape the guard silently, which is the exact failure mode the duplication creates.
 * Reading each declaring class's own constants and pairing them by name means a constant added to one
 * side and forgotten on the other is reported as missing rather than passing unnoticed. Private
 * constants are included deliberately: the statement twins both keep their redaction placeholder
 * private because it is an implementation detail, but a divergence would still make the two diagnostic
 * renderings disclose different information.
 *
 * <p><strong>Gate 6.</strong> The module's unsafe-code audit commits to zero reflection, and it scopes
 * itself to {@code src/main/java/**} for exactly this reason: a test that verifies a structural property of
 * the production sources is not production code, and the same scoping already governs the audit's other
 * counters. No production class here uses reflection, and this class is not shipped.
 *
 * <p>Provenance: the message texts pair back to {@code app/cbl/COACTUPC.cbl} and the four
 * {@code app/cbl/COUSR0*C.cbl} programs, and the page sizes to {@code app/cbl/COCRDLIC.cbl},
 * {@code app/cbl/COTRN00C.cbl} and {@code app/cbl/COUSR00C.cbl}, all read as read-only reference at
 * checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("cross-layer constants :: every twinned declaration agrees character for character")
class CrossLayerConstantAgreementTest {

    /**
     * One twinned declaration pair: the two classes and the names they are expected to share.
     *
     * @param description how a failure should name the pair
     * @param wireType the declaring class in {@code api.dto}
     * @param serviceType the declaring class in {@code com.carddemo.service}
     * @param namePrefixes the constant-name prefixes this pair covers; a constant whose name starts with
     *     one of them must exist on both sides with the same value
     */
    private record TwinnedPair(String description, Class<?> wireType, Class<?> serviceType,
                               List<String> namePrefixes) {
    }

    /**
     * The five pairs that duplicate a constant, each with the prefixes it is responsible for.
     *
     * <p>Prefixes rather than names, so a constant added under an existing prefix is picked up without this
     * list changing, and a constant added under a new one is a deliberate act that has to be recorded here.
     *
     * @return the pairs under test
     */
    private static List<TwinnedPair> twinnedPairs() {
        return List.of(
                new TwinnedPair("the account-update screen messages and money bounds",
                        AccountUpdateResponse.class, AccountUpdateOutcome.class,
                        List.of("SUFFIX_", "MSG_INVALID_ZIP_FOR_STATE", "MONEY_")),
                new TwinnedPair("the four user-administration screens' messages",
                        UserResponse.class, UserOutcome.class, List.of("MSG_")),
                new TwinnedPair("the user-list selection cardinality",
                        UserRequest.class, UserCommand.class, List.of("ROW_SELECTION_COUNT")),
                new TwinnedPair("the three page sizes and the two browse widths",
                        PageMetadata.class, BrowseWindow.class,
                        List.of("CARD_LIST_PAGE_SIZE", "TRANSACTION_LIST_PAGE_SIZE",
                                "USER_LIST_PAGE_SIZE", "LARGEST_SCREEN_PAGE_SIZE",
                                "CURSOR_KEY_MAX_LENGTH", "DISPLAYED_PAGE_NUMBER_MAX_LENGTH")),
                new TwinnedPair("the statement-line diagnostic redaction placeholder",
                        StatementSummary.class, StatementLineSummary.class,
                        List.of("REDACTION_PLACEHOLDER")));
    }

    /**
     * Reads every static final constant a class declares whose name starts with one of the prefixes.
     *
     * <p>Non-public fields are made accessible only inside this test. The production module's Gate 6
     * reflection count remains zero because the audit is scoped to {@code src/main/java/**}, while this
     * structural test must be able to compare a deliberately private redaction policy without exposing
     * that policy as production API.
     *
     * @param type the declaring class
     * @param namePrefixes the prefixes of interest
     * @return the constants by name, in name order
     */
    private static Map<String, Object> constantsOf(final Class<?> type,
                                                   final List<String> namePrefixes) {
        final Map<String, Object> constants = new TreeMap<>();
        for (final Field field : type.getDeclaredFields()) {
            final int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)) {
                continue;
            }
            if (namePrefixes.stream().noneMatch(prefix -> field.getName().startsWith(prefix))) {
                continue;
            }
            if (!field.canAccess(null) && !field.trySetAccessible()) {
                throw new AssertionError("a non-public constant of " + type.getName()
                        + " could not be made accessible: " + field.getName());
            }
            try {
                constants.put(field.getName(), field.get(null));
            } catch (final IllegalAccessException unreachable) {
                throw new AssertionError("a constant of " + type.getName()
                        + " could not be read: " + field.getName(), unreachable);
            }
        }
        return constants;
    }

    @Nested
    @DisplayName("the reader itself is sound, so a misread cannot pass as agreement")
    final class TheReaderIsSound {

        @Test
        @DisplayName("every pair really finds constants on both sides, so an empty read cannot be mistaken "
                + "for agreement")
        void everyPairFindsConstantsOnBothSides() {
            for (final TwinnedPair pair : twinnedPairs()) {
                assertThat(constantsOf(pair.wireType(), pair.namePrefixes()))
                        .as("%s: the wire side of %s declared nothing the prefixes match, so either the "
                                + "prefixes or the class is wrong", pair.description(),
                                pair.wireType().getSimpleName())
                        .isNotEmpty();
                assertThat(constantsOf(pair.serviceType(), pair.namePrefixes()))
                        .as("%s: the service side of %s declared nothing the prefixes match",
                                pair.description(), pair.serviceType().getSimpleName())
                        .isNotEmpty();
            }
        }

        @Test
        @DisplayName("the pairs together cover at least the fifty constants the duplication is known to "
                + "involve, so a silently emptied list cannot pass")
        void thePairsCoverTheKnownVolume() {
            int total = 0;
            for (final TwinnedPair pair : twinnedPairs()) {
                total += constantsOf(pair.serviceType(), pair.namePrefixes()).size();
            }

            assertThat(total)
                    .as("the known screen messages, bounds, selection cardinality and paging figures "
                            + "still exceed the fifty-constant floor, and the private statement "
                            + "redaction placeholder adds one more independently checked policy")
                    .isGreaterThanOrEqualTo(50);
        }
    }

    @Nested
    @DisplayName("the twinned declarations agree")
    final class TheTwinnedDeclarationsAgree {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.CrossLayerConstantAgreementTest#twinnedPairs")
        @DisplayName("every constant the wire side declares exists on the service side with the same value")
        void everyWireConstantIsMatchedByTheServiceSide(final TwinnedPair pair) {
            final Map<String, Object> wire = constantsOf(pair.wireType(), pair.namePrefixes());
            final Map<String, Object> service = constantsOf(pair.serviceType(), pair.namePrefixes());

            final List<String> missing = new ArrayList<>();
            final List<String> divergent = new ArrayList<>();
            wire.forEach((name, value) -> {
                if (!service.containsKey(name)) {
                    missing.add(name);
                } else if (!value.equals(service.get(name))) {
                    divergent.add(name);
                }
            });

            assertThat(missing)
                    .as("%s: the wire record declares these and the service-owned type does not, so the "
                            + "service cannot emit what the contract publishes", pair.description())
                    .isEmpty();
            assertThat(divergent)
                    .as("%s: these are declared on both sides with different values, which is a parity "
                            + "defect the moment the service emits one and a client matches the other",
                            pair.description())
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.carddemo.CrossLayerConstantAgreementTest#twinnedPairs")
        @DisplayName("the service side declares nothing under these prefixes that the wire side does not, "
                + "so neither declaration can grow alone")
        void theServiceSideDeclaresNothingExtra(final TwinnedPair pair) {
            final Map<String, Object> wire = constantsOf(pair.wireType(), pair.namePrefixes());
            final Map<String, Object> service = constantsOf(pair.serviceType(), pair.namePrefixes());

            assertThat(service.keySet())
                    .as("%s: a constant added to the service-owned type alone would be emitted by a "
                            + "screen and absent from the published contract", pair.description())
                    .isSubsetOf(wire.keySet());
        }
    }
}
