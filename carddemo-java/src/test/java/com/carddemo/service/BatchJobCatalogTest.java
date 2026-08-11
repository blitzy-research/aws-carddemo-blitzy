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
package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Specification for {@link BatchJobCatalog}, the one authority two layers read the batch inventory from.
 *
 * <h2>What is actually being specified here</h2>
 *
 * <p>This class holds no logic worth exercising for its own sake - it is nine names, four parameter names
 * and a map. What is worth specifying is the set of <strong>properties</strong> other code depends on, each
 * of which would fail silently if it broke:
 *
 * <ul>
 *   <li><em>The inventory is exactly nine, and closed.</em> The control surface answers a name outside it as
 *       an absent resource and reaches no framework call, so a tenth entry appearing here silently widens
 *       what a request can start.</li>
 *   <li><em>Every name is distinct.</em> Two jobs sharing a name would have one shadow the other in the
 *       framework's registry, and a launch would run the wrong work under the right name.</li>
 *   <li><em>A parameterless job maps to an empty set, never to an absent entry.</em> The two are
 *       distinguishable on purpose: absent means "not launchable" and empty means "launchable, accepts
 *       nothing", and collapsing them would turn a refusal into a launch.</li>
 *   <li><em>Only three jobs accept a parameter at all.</em> Five job members carry no execution parameter
 *       and name every dataset themselves, and that is exactly why their parameter sets are empty - a set
 *       that grew an entry would let a caller mint a fresh job identity and repeat financial work behind a
 *       launch-once contract.</li>
 *   <li><em>Nothing published here is mutable.</em> A caller holding the inventory must not be able to add
 *       to it, because the inventory being closed is the security property.</li>
 * </ul>
 *
 * <p>No job control statement is transcribed.
 */
@DisplayName("BatchJobCatalog - nine jobs, closed, with a closed parameter set each")
final class BatchJobCatalogTest {

    /** How many jobs the estate's job members and the recorded orphan amount to between them. */
    private static final int JOB_COUNT = 9;

    /** How many of those nine carry an execution parameter of any kind. */
    private static final int PARAMETERISED_JOB_COUNT = 3;

    // ----------------------------------------------------------------------------------------
    // The inventory
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the inventory is exactly the nine jobs, and it is closed")
    final class TheInventoryIsClosed {

        @Test
        @DisplayName("nine names are launchable, one per job configuration the module registers")
        void nineNamesAreLaunchable() {
            assertThat(BatchJobCatalog.launchableJobNames())
                    .as("a tenth entry silently widens what a request can start")
                    .hasSize(JOB_COUNT)
                    .containsExactlyInAnyOrder(
                            BatchJobCatalog.POST_TRANSACTION_JOB,
                            BatchJobCatalog.INTEREST_CALCULATION_JOB,
                            BatchJobCatalog.COMBINE_TRANSACTIONS_JOB,
                            BatchJobCatalog.CREATE_STATEMENT_JOB,
                            BatchJobCatalog.TRANSACTION_REPORT_JOB,
                            BatchJobCatalog.BACKUP_TRANSACTION_JOB,
                            BatchJobCatalog.CATEGORY_BALANCE_REPORT_JOB,
                            BatchJobCatalog.FILE_PROBE_JOB,
                            BatchJobCatalog.DAILY_TRANSACTION_READ_JOB);
        }

        @Test
        @DisplayName("every name is distinct, so no job can shadow another in the framework's registry")
        void everyNameIsDistinct() {
            assertThat(BatchJobCatalog.launchableJobNames())
                    .doesNotHaveDuplicates()
                    .allSatisfy(name -> assertThat(name).isNotBlank());
        }

        @Test
        @DisplayName("each name is exactly the literal the job configuration registers under, so a "
                + "launchable name and a registered name are the same string")
        void eachNameIsTheLiteralTheConfigurationRegistersUnder() {
            assertThat(BatchJobCatalog.POST_TRANSACTION_JOB).isEqualTo("postTransactionJob");
            assertThat(BatchJobCatalog.INTEREST_CALCULATION_JOB).isEqualTo("interestCalculationJob");
            assertThat(BatchJobCatalog.COMBINE_TRANSACTIONS_JOB).isEqualTo("combineTransactionsJob");
            assertThat(BatchJobCatalog.CREATE_STATEMENT_JOB).isEqualTo("createStatementJob");
            assertThat(BatchJobCatalog.TRANSACTION_REPORT_JOB).isEqualTo("transactionReportJob");
            assertThat(BatchJobCatalog.BACKUP_TRANSACTION_JOB).isEqualTo("backupTransactionJob");
            assertThat(BatchJobCatalog.CATEGORY_BALANCE_REPORT_JOB)
                    .isEqualTo("categoryBalanceReportJob");
            assertThat(BatchJobCatalog.FILE_PROBE_JOB).isEqualTo("fileProbeJob");
            assertThat(BatchJobCatalog.DAILY_TRANSACTION_READ_JOB).isEqualTo("dailyTransactionReadJob");
        }

        @Test
        @DisplayName("the published inventory cannot be added to, because being closed is the security "
                + "property rather than a convention")
        void thePublishedInventoryCannotBeAddedTo() {
            final Set<String> names = BatchJobCatalog.launchableJobNames();

            assertThatThrownBy(() -> names.add("someOtherJob"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a name outside the nine is not launchable, and neither is a missing one")
        void aNameOutsideTheNineIsNotLaunchable() {
            assertThat(BatchJobCatalog.isLaunchable(BatchJobCatalog.POST_TRANSACTION_JOB)).isTrue();
            assertThat(BatchJobCatalog.isLaunchable("someOtherJob")).isFalse();
            assertThat(BatchJobCatalog.isLaunchable("")).isFalse();
            assertThat(BatchJobCatalog.isLaunchable(null))
                    .as("an absent name must answer false rather than fail, because the control surface "
                            + "asks before it validates")
                    .isFalse();
        }

        @Test
        @DisplayName("a name differing only in case is not launchable, because the registry matches "
                + "exactly and a case-folding lookup here would disagree with it")
        void aNameDifferingOnlyInCaseIsNotLaunchable() {
            assertThat(BatchJobCatalog.isLaunchable(
                    BatchJobCatalog.POST_TRANSACTION_JOB.toUpperCase(Locale.ROOT))).isFalse();
        }
    }

    // ----------------------------------------------------------------------------------------
    // The parameter sets
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("each job declares the exact parameter names it accepts, and no others")
    final class EachJobDeclaresItsOwnParameterNames {

        @Test
        @DisplayName("the accrual run accepts its ten-character run date and nothing else")
        void theAccrualRunAcceptsItsRunDate() {
            assertThat(BatchJobCatalog.parameterNamesFor(BatchJobCatalog.INTEREST_CALCULATION_JOB))
                    .contains(Set.of(BatchJobCatalog.INTEREST_PARM_DATE_PARAMETER));
        }

        @Test
        @DisplayName("the report accepts both bounds of its inclusive window and nothing else")
        void theReportAcceptsBothBoundsOfItsWindow() {
            assertThat(BatchJobCatalog.parameterNamesFor(BatchJobCatalog.TRANSACTION_REPORT_JOB))
                    .contains(Set.of(BatchJobCatalog.REPORT_START_DATE_PARAMETER,
                            BatchJobCatalog.REPORT_END_DATE_PARAMETER));
        }

        @Test
        @DisplayName("the file probe accepts the selector that says which cluster it reads")
        void theFileProbeAcceptsItsSelector() {
            assertThat(BatchJobCatalog.parameterNamesFor(BatchJobCatalog.FILE_PROBE_JOB))
                    .contains(Set.of(BatchJobCatalog.FILE_PROBE_MODE_PARAMETER));
        }

        @Test
        @DisplayName("the six job members that carry no execution parameter accept none, and answer an "
                + "empty set rather than nothing at all")
        void theJobsThatCarryNoExecutionParameterAcceptNone() {
            for (final String parameterless : Set.of(
                    BatchJobCatalog.POST_TRANSACTION_JOB,
                    BatchJobCatalog.COMBINE_TRANSACTIONS_JOB,
                    BatchJobCatalog.CREATE_STATEMENT_JOB,
                    BatchJobCatalog.BACKUP_TRANSACTION_JOB,
                    BatchJobCatalog.CATEGORY_BALANCE_REPORT_JOB,
                    BatchJobCatalog.DAILY_TRANSACTION_READ_JOB)) {
                final Optional<Set<String>> accepted =
                        BatchJobCatalog.parameterNamesFor(parameterless);

                assertThat(accepted)
                        .as("%s is launchable and accepts nothing, which is not the same as being "
                                + "unlaunchable", parameterless)
                        .isPresent();
                assertThat(accepted.orElseThrow())
                        .as("%s declares no parameter name at all", parameterless)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("exactly three of the nine accept a parameter at all, so the parameter surface "
                + "cannot grow unnoticed")
        void exactlyThreeOfTheNineAcceptAParameter() {
            final long parameterised = BatchJobCatalog.launchableJobNames().stream()
                    .map(BatchJobCatalog::parameterNamesFor)
                    .flatMap(Optional::stream)
                    .filter(names -> !names.isEmpty())
                    .count();

            assertThat(parameterised)
                    .as("a fourth parameterised job means a caller gained a way to vary a job identity")
                    .isEqualTo(PARAMETERISED_JOB_COUNT);
        }

        @Test
        @DisplayName("every declared parameter name is distinct across the whole inventory, so a name "
                + "cannot mean one thing on one job and another on the next")
        void everyDeclaredParameterNameIsDistinct() {
            final List<String> declared = BatchJobCatalog.launchableJobNames().stream()
                    .map(BatchJobCatalog::parameterNamesFor)
                    .flatMap(Optional::stream)
                    .flatMap(Set::stream)
                    .toList();

            assertThat(declared).doesNotHaveDuplicates();
            assertThat(declared).containsExactlyInAnyOrder(
                    BatchJobCatalog.INTEREST_PARM_DATE_PARAMETER,
                    BatchJobCatalog.REPORT_START_DATE_PARAMETER,
                    BatchJobCatalog.REPORT_END_DATE_PARAMETER,
                    BatchJobCatalog.FILE_PROBE_MODE_PARAMETER);
        }

        @Test
        @DisplayName("a parameter set cannot be added to either, so a caller holding one cannot widen "
                + "the names its job accepts")
        void aParameterSetCannotBeAddedTo() {
            final Set<String> accepted = BatchJobCatalog
                    .parameterNamesFor(BatchJobCatalog.INTEREST_CALCULATION_JOB).orElseThrow();

            assertThatThrownBy(() -> accepted.add("someOtherParameter"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a name outside the inventory has no parameter set, which is how the control "
                + "surface tells 'not launchable' from 'accepts nothing'")
        void aNameOutsideTheInventoryHasNoParameterSet() {
            assertThat(BatchJobCatalog.parameterNamesFor("someOtherJob")).isEmpty();
            assertThat(BatchJobCatalog.parameterNamesFor(null)).isEmpty();
        }
    }

    // ----------------------------------------------------------------------------------------
    // The type itself
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the catalogue is a constant contract, not an object")
    final class TheCatalogueIsAConstantContract {

        @Test
        @DisplayName("it cannot be instantiated, so there is no instance to hold state the inventory "
                + "does not")
        void itCannotBeInstantiated() throws ReflectiveOperationException {
            final Constructor<BatchJobCatalog> constructor =
                    BatchJobCatalog.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatThrownBy(constructor::newInstance)
                    .cause()
                    .isInstanceOf(AssertionError.class);
        }
    }
}
