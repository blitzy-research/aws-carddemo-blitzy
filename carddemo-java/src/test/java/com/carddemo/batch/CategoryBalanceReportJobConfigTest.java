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
package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.transaction.PlatformTransactionManager;

import com.carddemo.batch.step.FixedWidthFlatFileReaderFactory;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.AccountScanRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CardCrossReferenceScanRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CardScanRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.service.AbendService;
import com.carddemo.service.FileMaintenanceService;

/**
 * What this configuration publishes, and what its own documentation commits it to.
 *
 * <p>The subject is the translation of the legacy job stream {@code app/jcl/PRTCATBL.jcl} at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Three facts measured from that member are
 * asserted here because getting any of them wrong compiles cleanly and fails only at the byte level:
 * the member declares <strong>three</strong> steps, it declares <strong>no</strong> condition-code
 * dependency on any of them, and its output record length is <strong>forty</strong> bytes against a
 * reprojection whose own segments add up to forty-one.
 *
 * <p>The behaviour that needs a database - the ordering, the staged datasets and their widths - is
 * asserted by {@code CategoryBalanceReportJobConfigIT} against a real server. What is asserted here is
 * everything that can be decided without one.
 */
@DisplayName("category-balance report job: three steps, no gate, and a forty-byte output contract")
final class CategoryBalanceReportJobConfigTest {

    /** Widths and offsets the assertions below compare against, restated from the measured member. */
    private static final int MEASURED_RECORD_LENGTH = 40;

    /** Content bytes the measured reprojection emits before its filler run. */
    private static final int MEASURED_CONTENT_LENGTH = 32;

    /** Blanks the record carries, being the declared length less the content. */
    private static final int MEASURED_TRAILING_FILLER = 8;

    /** The width the reprojection's own filler run would have produced, and which is refused. */
    private static final int REFUSED_RECORD_LENGTH = 41;

    /** Creates the test class. */
    CategoryBalanceReportJobConfigTest() {
    }

    /**
     * The configuration under test, wired with the collaborators it declares. The metric registry and
     * the clock are real because they are cheap and because a stubbed clock would prove less; the
     * repositories are stubs because nothing here reaches a database.
     *
     * @return the configuration
     */
    private static CategoryBalanceReportJobConfig configuration() {
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        final FileMaintenanceService fileMaintenanceService = new FileMaintenanceService(
                mock(AccountRepository.class),
                mock(AccountScanRepository.class),
                mock(CardRepository.class),
                mock(CardScanRepository.class),
                mock(CardCrossReferenceRepository.class),
                mock(CardCrossReferenceScanRepository.class),
                mock(TransactionCategoryBalanceRepository.class),
                mock(CustomerRepository.class),
                new AbendService());

        return new CategoryBalanceReportJobConfig(
                mock(JobRepository.class),
                mock(PlatformTransactionManager.class),
                mock(JobExecutionListener.class),
                new RunIdIncrementer(),
                fileMaintenanceService,
                mock(TransactionCategoryBalanceRepository.class),
                new FixedWidthFlatFileReaderFactory(),
                meterRegistry,
                Clock.systemUTC(),
                "/tmp",
                "AWS.M2.CARDDEMO.TCATBALF.BKUP",
                "AWS.M2.CARDDEMO.TCATBALF.REPT");
    }

    @Nested
    @DisplayName("the measured widths, which are the dataset contract")
    final class MeasuredWidths {

        /** Creates the nest. */
        MeasuredWidths() {
        }

        @Test
        @DisplayName("the report record is forty bytes, being thirty-two content bytes and eight blanks, "
                + "and never the forty-one the reprojection's own filler run would produce")
        void theReportRecordIsFortyBytes() {
            assertThat(CategoryBalanceReportJobConfig.REPORT_RECORD_LENGTH)
                    .as("the declared record length is the contract every reader of that dataset is "
                            + "written against")
                    .isEqualTo(MEASURED_RECORD_LENGTH)
                    .isNotEqualTo(REFUSED_RECORD_LENGTH);
            assertThat(CategoryBalanceReportJobConfig.REPORT_CONTENT_LENGTH)
                    .isEqualTo(MEASURED_CONTENT_LENGTH);
            assertThat(CategoryBalanceReportJobConfig.REPORT_TRAILING_FILLER_LENGTH)
                    .as("eight blanks, not the nine the reprojection declares")
                    .isEqualTo(MEASURED_TRAILING_FILLER);
            assertThat(CategoryBalanceReportJobConfig.REPORT_CONTENT_LENGTH
                    + CategoryBalanceReportJobConfig.REPORT_TRAILING_FILLER_LENGTH)
                    .as("the segments must add up to the declared length exactly")
                    .isEqualTo(CategoryBalanceReportJobConfig.REPORT_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the edit mask is twelve characters: nine integer digit positions, a decimal point "
                + "and two fractional digit positions")
        void theEditMaskIsTwelveCharacters() {
            assertThat(CategoryBalanceReportJobConfig.BALANCE_INTEGER_DIGITS).isEqualTo(9);
            assertThat(CategoryBalanceReportJobConfig.BALANCE_DECIMAL_POINT_LENGTH).isOne();
            assertThat(CategoryBalanceReportJobConfig.BALANCE_MASK_WIDTH)
                    .isEqualTo(CategoryBalanceReportJobConfig.BALANCE_INTEGER_DIGITS
                            + CategoryBalanceReportJobConfig.BALANCE_DECIMAL_POINT_LENGTH + 2)
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("the unloaded record is the fifty-byte category-balance layout, which is a different "
                + "file from the equally fifty-byte card cross-reference")
        void theUnloadedRecordIsFiftyBytes() {
            assertThat(CategoryBalanceReportJobConfig.UNLOAD_RECORD_LENGTH).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("what the container receives")
    final class ContainerContribution {

        /** Creates the nest. */
        ContainerContribution() {
        }

        @Test
        @DisplayName("three steps with distinct, stable names, and a job name distinct from all of them")
        void theNamesAreDistinctAndStable() {
            final List<String> names = List.of(
                    CategoryBalanceReportJobConfig.CLEAR_PRIOR_REPORT_STEP_NAME,
                    CategoryBalanceReportJobConfig.UNLOAD_STEP_NAME,
                    CategoryBalanceReportJobConfig.SORT_AND_REPROJECT_STEP_NAME);

            assertThat(names).doesNotHaveDuplicates()
                    .hasSize(CategoryBalanceReportJobConfig.STEP_COUNT)
                    .doesNotContain(CategoryBalanceReportJobConfig.JOB_NAME)
                    .allSatisfy(name -> assertThat(name).isNotBlank());
            assertThat(CategoryBalanceReportJobConfig.STEP_COUNT)
                    .as("the legacy member declares three steps")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("no name in the launch surface mentions a date or a card number, because the legacy "
                + "comment that claimed both is contradicted by the control stream it describes")
        void noNameMentionsADateOrACardNumber() {
            final String surface = (CategoryBalanceReportJobConfig.JOB_NAME
                    + CategoryBalanceReportJobConfig.CLEAR_PRIOR_REPORT_STEP_NAME
                    + CategoryBalanceReportJobConfig.UNLOAD_STEP_NAME
                    + CategoryBalanceReportJobConfig.SORT_AND_REPROJECT_STEP_NAME)
                    .toLowerCase(java.util.Locale.ROOT);

            assertThat(surface).doesNotContain("date").doesNotContain("card");
        }

        @Test
        @DisplayName("each step bean is published under its own name")
        void eachStepIsPublishedUnderItsOwnName() {
            final CategoryBalanceReportJobConfig configuration = configuration();
            final BatchStagingArea stagingArea = mock(BatchStagingArea.class);

            final Step clear = configuration.categoryBalanceReportClearPriorReportStep();
            final Step unload = configuration.categoryBalanceReportUnloadStep(stagingArea);
            final Step sort =
                    configuration.categoryBalanceReportSortAndReprojectStep(stagingArea);

            assertThat(clear.getName())
                    .isEqualTo(CategoryBalanceReportJobConfig.CLEAR_PRIOR_REPORT_STEP_NAME);
            assertThat(unload.getName()).isEqualTo(CategoryBalanceReportJobConfig.UNLOAD_STEP_NAME);
            assertThat(sort.getName())
                    .isEqualTo(CategoryBalanceReportJobConfig.SORT_AND_REPROJECT_STEP_NAME);
        }

        @Test
        @DisplayName("the job is a plain sequential job carrying its three steps in the declared order, "
                + "so it holds no flow and therefore no failure-ending transition at all")
        void theJobIsPlainlySequential() {
            final CategoryBalanceReportJobConfig configuration = configuration();
            final BatchStagingArea stagingArea = mock(BatchStagingArea.class);

            final Job job = configuration.categoryBalanceReportJob(
                    configuration.categoryBalanceReportClearPriorReportStep(),
                    configuration.categoryBalanceReportUnloadStep(stagingArea),
                    configuration.categoryBalanceReportSortAndReprojectStep(stagingArea));

            assertThat(job.getName()).isEqualTo(CategoryBalanceReportJobConfig.JOB_NAME);
            assertThat(job)
                    .as("a flow job would be the shape a condition-code gate produces, and the measured "
                            + "member declares no gate on any step")
                    .isInstanceOf(SimpleJob.class);
            assertThat(((SimpleJob) job).getStepNames()).containsExactly(
                    CategoryBalanceReportJobConfig.CLEAR_PRIOR_REPORT_STEP_NAME,
                    CategoryBalanceReportJobConfig.UNLOAD_STEP_NAME,
                    CategoryBalanceReportJobConfig.SORT_AND_REPROJECT_STEP_NAME);
        }

        @Test
        @DisplayName("the job carries an incrementer, which is what lets it be advanced by name, and no "
                + "parameters validator that would demand a parameter it never takes")
        void theJobCanBeAdvancedByNameWithoutParameters() {
            final CategoryBalanceReportJobConfig configuration = configuration();
            final BatchStagingArea stagingArea = mock(BatchStagingArea.class);

            final Job job = configuration.categoryBalanceReportJob(
                    configuration.categoryBalanceReportClearPriorReportStep(),
                    configuration.categoryBalanceReportUnloadStep(stagingArea),
                    configuration.categoryBalanceReportSortAndReprojectStep(stagingArea));

            assertThat(job.getJobParametersIncrementer()).isNotNull();
            assertThat(job.getJobParametersValidator())
                    .as("a date-range validator here would demand the very parameter the control stream "
                            + "does not take")
                    .satisfiesAnyOf(
                            validator -> assertThat(validator).isNull(),
                            validator -> validator.validate(new org.springframework.batch.core
                                    .JobParameters()));
        }

        @Test
        @DisplayName("a blank logical resource name is refused, because it would resolve to the staging "
                + "directory itself rather than to a dataset")
        void aBlankLogicalNameIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    new CategoryBalanceReportJobConfig(
                            mock(JobRepository.class),
                            mock(PlatformTransactionManager.class),
                            mock(JobExecutionListener.class),
                            mock(JobParametersIncrementer.class),
                            new FileMaintenanceService(
                                    mock(AccountRepository.class),
                                    mock(AccountScanRepository.class),
                                    mock(CardRepository.class),
                                    mock(CardScanRepository.class),
                                    mock(CardCrossReferenceRepository.class),
                                    mock(CardCrossReferenceScanRepository.class),
                                    mock(TransactionCategoryBalanceRepository.class),
                                    mock(CustomerRepository.class),
                                    new AbendService()),
                            mock(TransactionCategoryBalanceRepository.class),
                            new FixedWidthFlatFileReaderFactory(),
                            new SimpleMeterRegistry(),
                            Clock.system(ZoneOffset.UTC),
                            "/tmp",
                            "AWS.M2.CARDDEMO.TCATBALF.BKUP",
                            "   "))
                    .withMessageContaining("reportDataset");
        }
    }

    @Nested
    @DisplayName("the sort specification stays inside this class")
    final class SortSpecificationIsJobLocal {

        /** Creates the nest. */
        SortSpecificationIsJobLocal() {
        }

        @Test
        @DisplayName("every comparator this class holds is private, because four external sort "
                + "specifications disagree about field typing and a shared comparator would silently "
                + "apply one job's typing to another job's data")
        void everyComparatorIsPrivate() throws NoSuchFieldException {
            for (final String name : List.of("SORT_KEY_ORDER", "SORT_SPECIFICATION")) {
                final Field field =
                        CategoryBalanceReportJobConfig.class.getDeclaredField(name);
                assertThat(Modifier.isPrivate(field.getModifiers()))
                        .as("%s must not be reachable from another job", name)
                        .isTrue();
                assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
                assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
            }

            assertThat(CategoryBalanceReportJobConfig.class.getDeclaredFields())
                    .filteredOn(field -> Comparator.class.isAssignableFrom(field.getType()))
                    .isNotEmpty()
                    .allSatisfy(field -> assertThat(Modifier.isPrivate(field.getModifiers())).isTrue());
            assertThat(CategoryBalanceReportJobConfig.class.getDeclaredMethods())
                    .filteredOn(method -> Comparator.class.isAssignableFrom(method.getReturnType()))
                    .allSatisfy(method ->
                            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue());
        }

        @Test
        @DisplayName("no member of the public surface hands a comparator out")
        void noPublicMemberExposesAComparator() {
            assertThat(CategoryBalanceReportJobConfig.class.getMethods())
                    .allSatisfy(method -> assertThat(
                            Comparator.class.isAssignableFrom(method.getReturnType())).isFalse());
        }
    }
}
