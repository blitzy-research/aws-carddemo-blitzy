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

import com.carddemo.batch.JobParameterValidators;
import com.carddemo.service.BatchJobCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit specification for {@link BatchJobLaunchRequest}, the closed typed parameter surface of a batch job
 * launch.
 *
 * <p>Four properties are asserted here because each of them is what makes the launch surface bounded
 * rather than open: an unknown property is refused rather than dropped, every component is bounded in
 * width and character class, each component's wire name is the job parameter key it becomes, and the set
 * of components is exactly the set of names the job catalog accepts - no wider and no narrower.

 * <p>That last one is a defect that shipped. The record once published two generation names that no job
 * accepted, so a request carrying either was refused by the per-job rule while the published document
 * invited it. Asserting the record against the catalog rather than against a written-down count is what
 * makes a future divergence a failure here instead of a surprise at runtime.
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

    /**
     * The wire names the record publishes, written out because a record component's name is not readable
     * without reflection and this module's reflection budget is zero.
     *
     * <p>Kept in one place so the component-count assertion and the catalog-agreement assertion are the
     * same statement made twice rather than two figures that can drift.
     */
    private static final List<String> PUBLISHED_PROPERTY_NAMES = List.of(
            "interestParmDate", "reportStartDate", "reportEndDate", "fileProbeMode");

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
                     "fileProbeMode":"account"}
                    """, BatchJobLaunchRequest.class);

            assertThat(bound.interestParmDate()).isEqualTo("2022071900");
            assertThat(bound.reportStartDate()).isEqualTo("2022-07-01");
            assertThat(bound.reportEndDate()).isEqualTo("2022-07-31");
            assertThat(bound.fileProbeMode()).isEqualTo("account");

            // The names above are the batch tier's own constants. Asserting the constants rather than
            // repeating the strings is what makes a rename in either place a failure here.
            assertThat(JobParameterValidators.INTEREST_PARM_DATE_KEY).isEqualTo("interestParmDate");
            assertThat(JobParameterValidators.REPORT_START_DATE_KEY).isEqualTo("reportStartDate");
            assertThat(JobParameterValidators.REPORT_END_DATE_KEY).isEqualTo("reportEndDate");
            assertThat(JobParameterValidators.FILE_PROBE_MODE_KEY).isEqualTo("fileProbeMode");
        }

        @Test
        @DisplayName("declares one component per parameter some registered job actually accepts, and not "
                + "one component more, so no component can be transmitted that no job would read")
        void declaresOnlyComponentsSomeRegisteredJobAccepts() {
            final Set<String> declared = Arrays.stream(BatchJobLaunchRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .collect(Collectors.toUnmodifiableSet());
            final Set<String> accepted = BatchJobCatalog.parameterNamesByJob().values().stream()
                    .flatMap(Set::stream)
                    .collect(Collectors.toUnmodifiableSet());

            assertThat(declared)
                    .as("every transmittable component is a parameter some job in the inventory declares")
                    .isEqualTo(accepted);
            assertThat(accepted)
                    .as("THE DEFECT THIS PINS. Two generation names were once published here and accepted "
                            + "by no job, so every request carrying one was refused while the published "
                            + "document invited it. A name published without a job to take it is an "
                            + "unusable input, and a name a job takes without being published cannot be "
                            + "supplied at all")
                    .containsExactlyInAnyOrderElementsOf(PUBLISHED_PROPERTY_NAMES);
        }

        @ParameterizedTest
        @ValueSource(strings = {"transactionBackupCurrentGeneration",
                "synthesizedTransactionCurrentGeneration", "outputDatasetName", "inputLocation"})
        @DisplayName("refuses a generation or dataset name outright, because the consolidation job reads "
                + "its two inputs from the store rather than from the caller, so naming one is not a "
                + "parameter this surface has")
        void refusesAGenerationOrDatasetName(final String phantom) {
            assertThatThrownBy(() -> mapper.readValue(
                    "{\"" + phantom + "\":\"TRANBKP.G0007V00\"}", BatchJobLaunchRequest.class))
                    .as("%s is not a launch parameter of any registered job", phantom)
                    .isInstanceOf(UnrecognizedPropertyException.class);

            assertThat(Arrays.stream(BatchJobLaunchRequest.class.getRecordComponents())
                    .map(RecordComponent::getName))
                    .as("and it is absent from the published surface, not merely refused on the wire")
                    .doesNotContain(phantom);
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
        @DisplayName("declares exactly four components, one per parameter the nine jobs declare between "
                + "them, so a fifth cannot arrive without being declared")
        void declaresExactlyFourComponents() {
            assertThat(BatchJobLaunchRequest.class.getRecordComponents()).hasSize(4);
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
                    "2022-07-31", "crossReference"))).isEmpty();
            assertThat(validator.validate(BatchJobLaunchRequest.empty()))
                    .as("every component is optional, because four of the nine jobs take none")
                    .isEmpty();
        }

        @Test
        @DisplayName("refuses an interest parameter that is over width or not all digits, because its "
                + "characters become the leading characters of every identifier the run synthesises")
        void refusesAMisshapenInterestParameter() {
            assertThat(validator.validate(new BatchJobLaunchRequest("20220719000", null, null, null)))
                    .isNotEmpty();
            assertThat(validator.validate(new BatchJobLaunchRequest("2022-07-19", null, null, null)))
                    .isNotEmpty();
        }

        @Test
        @DisplayName("refuses a report bound that is not a hyphenated ISO date, because the window is "
                + "read as a fixed-width layout and compared as characters")
        void refusesAMisshapenReportBound() {
            assertThat(validator.validate(new BatchJobLaunchRequest(null, "20220701", null, null)))
                    .isNotEmpty();
            assertThat(validator.validate(new BatchJobLaunchRequest(null, null, "2022-7-1", null)))
                    .isNotEmpty();
        }

        @Test
        @DisplayName("refuses a scheme, a separator or a traversal segment in every component it "
                + "declares, so no launch value can name a location rather than a value")
        void refusesALocationInEveryComponentItDeclares() {
            for (final String location : List.of("file:/etc/passwd", "classpath:application.yml",
                    "http://elsewhere/x", "../secrets", "sub/dir", "sub\\dir")) {
                assertThat(validator.validate(
                        new BatchJobLaunchRequest(location, null, null, null)))
                        .as("%s is a location, not an interest parameter", location)
                        .isNotEmpty();
                assertThat(validator.validate(
                        new BatchJobLaunchRequest(null, location, null, null)))
                        .as("%s is a location, not a report bound", location)
                        .isNotEmpty();
                assertThat(validator.validate(
                        new BatchJobLaunchRequest(null, null, location, null)))
                        .as("%s is a location on the upper bound too", location)
                        .isNotEmpty();
                assertThat(validator.validate(
                        new BatchJobLaunchRequest(null, null, null, location)))
                        .as("%s is a location, not a probe mode", location)
                        .isNotEmpty();
            }
        }

        @Test
        @DisplayName("refuses a probe mode carrying anything but letters and digits, and one over width, "
                + "so the value the job's own validator sees is already bounded")
        void refusesAMisshapenProbeMode() {
            assertThat(validator.validate(
                    new BatchJobLaunchRequest(null, null, null, "cross-reference"))).isNotEmpty();
            assertThat(validator.validate(new BatchJobLaunchRequest(null, null, null,
                    "a".repeat(BatchJobLaunchRequest.SYMBOLIC_NAME_WIDTH + 1)))).isNotEmpty();
        }
    }
}
