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
package com.carddemo.api.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.CombineTransactionsJobConfig;
import com.carddemo.batch.JobParameterValidators;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit specification for {@link BatchJobLaunchRequest}, the closed typed parameter surface of a batch job
 * launch.
 *
 * <p>Three properties are asserted here because each of them is what makes the launch surface bounded
 * rather than open: an unknown property is refused rather than dropped, every component is bounded in
 * width and character class, and each component's wire name is the job parameter key it becomes.
 *
 * <p>The last of those is the reason this test exists at all. The record declares its component names as
 * literals - a record component's name is not readable without reflection, and this module's reflection
 * budget is zero - so the agreement between those literals and the batch tier's own key constants has to
 * be checked rather than arranged. Reading the constants here is what turns a repeated spelling into a
 * checked one.
 *
 * @since 1.0.0
 */
@DisplayName("BatchJobLaunchRequest - a closed, typed and bounded launch parameter surface")
class BatchJobLaunchRequestTest {

    /** Deserialises a body exactly as the web layer's own mapper would. */
    private final ObjectMapper mapper = new ObjectMapper();

    /** Checks the declarative bounds exactly as the request-binding validation would. */
    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Nested
    @DisplayName("The wire contract")
    class TheWireContract {

        @Test
        @DisplayName("names each component after the job parameter key it becomes, so one name appears in "
                + "the published document, in the request and in the framework's own metadata")
        void namesEachComponentAfterTheJobParameterKey() throws Exception {
            final BatchJobLaunchRequest bound = mapper.readValue("""
                    {"interestParmDate":"2022071900",
                     "reportStartDate":"2022-07-01",
                     "reportEndDate":"2022-07-31",
                     "fileProbeMode":"account",
                     "transactionBackupCurrentGeneration":"TRANBKP.G0007V00",
                     "synthesizedTransactionCurrentGeneration":"SYNTH.G0007V00"}
                    """, BatchJobLaunchRequest.class);

            assertThat(bound.interestParmDate()).isEqualTo("2022071900");
            assertThat(bound.reportStartDate()).isEqualTo("2022-07-01");
            assertThat(bound.reportEndDate()).isEqualTo("2022-07-31");
            assertThat(bound.fileProbeMode()).isEqualTo("account");
            assertThat(bound.transactionBackupCurrentGeneration()).isEqualTo("TRANBKP.G0007V00");
            assertThat(bound.synthesizedTransactionCurrentGeneration()).isEqualTo("SYNTH.G0007V00");

            // The names above are the batch tier's own constants. Asserting the constants rather than
            // repeating the strings is what makes a rename in either place a failure here.
            assertThat(JobParameterValidators.INTEREST_PARM_DATE_KEY).isEqualTo("interestParmDate");
            assertThat(JobParameterValidators.REPORT_START_DATE_KEY).isEqualTo("reportStartDate");
            assertThat(JobParameterValidators.REPORT_END_DATE_KEY).isEqualTo("reportEndDate");
            assertThat(JobParameterValidators.FILE_PROBE_MODE_KEY).isEqualTo("fileProbeMode");
            assertThat(CombineTransactionsJobConfig.BACKUP_INPUT_NAME)
                    .isEqualTo("transactionBackupCurrentGeneration");
            assertThat(CombineTransactionsJobConfig.SYNTHESIZED_INPUT_NAME)
                    .isEqualTo("synthesizedTransactionCurrentGeneration");
        }

        @Test
        @DisplayName("refuses an unknown property rather than dropping it, because a dropped parameter "
                + "starts a run the caller believes was parameterised")
        void refusesAnUnknownProperty() {
            assertThatThrownBy(() -> mapper.readValue(
                    "{\"runIdentifier\":\"1\"}", BatchJobLaunchRequest.class))
                    .isInstanceOf(UnrecognizedPropertyException.class);
        }

        @Test
        @DisplayName("declares exactly six components, one per parameter the nine jobs declare between "
                + "them, so a seventh cannot arrive without being declared")
        void declaresExactlySixComponents() {
            assertThat(BatchJobLaunchRequest.class.getRecordComponents()).hasSize(6);
        }

        @Test
        @DisplayName("reads an absent body as the empty request, which is the launch of a job that "
                + "declares no parameter")
        void readsAnAbsentBodyAsTheEmptyRequest() {
            final BatchJobLaunchRequest empty = BatchJobLaunchRequest.empty();

            assertThat(empty.interestParmDate()).isNull();
            assertThat(empty.reportStartDate()).isNull();
            assertThat(empty.reportEndDate()).isNull();
            assertThat(empty.fileProbeMode()).isNull();
            assertThat(empty.transactionBackupCurrentGeneration()).isNull();
            assertThat(empty.synthesizedTransactionCurrentGeneration()).isNull();
            assertThat(BatchJobLaunchRequest.empty())
                    .as("immutable, so one shared instance serves every body-less launch")
                    .isSameAs(empty);
        }
    }

    @Nested
    @DisplayName("The declarative bounds")
    class TheDeclarativeBounds {

        @Test
        @DisplayName("accepts every well-formed value, so no legitimate launch is refused by a bound")
        void acceptsEveryWellFormedValue() {
            assertThat(validator.validate(new BatchJobLaunchRequest("2022071900", "2022-07-01",
                    "2022-07-31", "crossReference", "TRANBKP.G0007V00", "SYNTH.G0007V00"))).isEmpty();
            assertThat(validator.validate(BatchJobLaunchRequest.empty()))
                    .as("every component is optional, because four of the nine jobs take none")
                    .isEmpty();
        }

        @Test
        @DisplayName("refuses an interest parameter that is over width or not all digits, because its "
                + "characters become the leading characters of every identifier the run synthesises")
        void refusesAMisshapenInterestParameter() {
            assertThat(validator.validate(new BatchJobLaunchRequest("20220719000", null, null, null,
                    null, null))).isNotEmpty();
            assertThat(validator.validate(new BatchJobLaunchRequest("2022-07-19", null, null, null,
                    null, null))).isNotEmpty();
        }

        @Test
        @DisplayName("refuses a report bound that is not a hyphenated ISO date, because the window is "
                + "read as a fixed-width layout and compared as characters")
        void refusesAMisshapenReportBound() {
            assertThat(validator.validate(new BatchJobLaunchRequest(null, "20220701", null, null,
                    null, null))).isNotEmpty();
            assertThat(validator.validate(new BatchJobLaunchRequest(null, null, "2022-7-1", null,
                    null, null))).isNotEmpty();
        }

        @Test
        @DisplayName("refuses a dataset name carrying a scheme, a separator or a traversal segment, so a "
                + "launch names an object and never chooses a location")
        void refusesADatasetNameThatIsReallyALocation() {
            for (final String location : List.of("file:/etc/passwd", "classpath:application.yml",
                    "http://elsewhere/x", "../secrets", "sub/dir", "sub\\dir")) {
                assertThat(validator.validate(new BatchJobLaunchRequest(null, null, null, null,
                        location, null)))
                        .as("%s is a location, not an object name", location)
                        .isNotEmpty();
                assertThat(validator.validate(new BatchJobLaunchRequest(null, null, null, null, null,
                        location)))
                        .as("%s is a location on the second input too", location)
                        .isNotEmpty();
            }
        }

        @Test
        @DisplayName("refuses a dataset name longer than the declared bound, so an unbounded string "
                + "never reaches a resolution call")
        void refusesAnOverLongDatasetName() {
            final String tooLong = "A".repeat(BatchJobLaunchRequest.DATASET_NAME_WIDTH + 1);

            assertThat(validator.validate(new BatchJobLaunchRequest(null, null, null, null, tooLong,
                    null))).isNotEmpty();
        }

        @Test
        @DisplayName("refuses a probe mode carrying anything but letters and digits, and one over width, "
                + "so the value the job's own validator sees is already bounded")
        void refusesAMisshapenProbeMode() {
            assertThat(validator.validate(new BatchJobLaunchRequest(null, null, null, "cross-reference",
                    null, null))).isNotEmpty();
            assertThat(validator.validate(new BatchJobLaunchRequest(null, null, null,
                    "a".repeat(BatchJobLaunchRequest.SYMBOLIC_NAME_WIDTH + 1), null, null)))
                    .isNotEmpty();
        }
    }
}
