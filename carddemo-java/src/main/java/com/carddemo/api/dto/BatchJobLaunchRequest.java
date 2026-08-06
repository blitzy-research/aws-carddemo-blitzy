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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The closed, typed parameter surface of a batch job launch: one named component per parameter any of
 * the nine registered jobs declares, and not one component more.
 *
 * <p><strong>Why a record and not a map.</strong> A job identity in the batch framework is the job's
 * name plus its identifying parameters, so every key that reaches the launch mints part of that
 * identity. A launch surface that forwarded whatever keys arrived would let a caller add a key the job
 * never declares, produce a job identity the framework has not seen, and start a second run of work the
 * framework would otherwise have refused as already done. The parameter surface is therefore a fixed
 * set of named components: a key with no component here cannot bind, and
 * {@link JsonIgnoreProperties @JsonIgnoreProperties(ignoreUnknown = false)} makes an attempt to send one
 * a rejected request rather than a silently dropped value.
 *
 * <p><strong>Which job may carry which component is a second, separate rule.</strong> This record
 * declares the union of every parameter across the nine jobs; the launch operation additionally holds an
 * exact per-job allow-list and refuses a component the addressed job does not declare. Both rules are
 * needed: the union bounds what can be transmitted at all, and the per-job list bounds what each job
 * accepts. Neither restates the <em>content</em> rules - what a date must look like, which probe modes
 * exist - because those belong to {@code com.carddemo.batch.JobParameterValidators} and to the job
 * configurations, and run inside the framework's own launch.
 *
 * <p><strong>Every value is transmitted byte for byte.</strong> Nothing here trims, pads, upper-folds,
 * parses or reformats: the interest parameter's ten characters become the leading characters of every
 * transaction identifier that run synthesises, and the report window is read as a fixed-width layout, so
 * an altered value would change output that is compared byte for byte. The bounds below are width and
 * character-class bounds only - they reject a value that could not be legitimate, and they decide
 * nothing about a value that could.
 *
 * <p>An entirely absent body is the launch of a job that declares no parameter, which is four of the
 * nine, so every component is optional and an all-absent instance is valid.
 *
 * <p>The wire name of each component is the job parameter key it becomes, so a caller reads one name in
 * the published document and in the framework's own metadata. This record deliberately does not restate
 * those keys as constants: the launch operation pairs each accessor with the batch tier's own key
 * constant, which keeps a single spelling in a single place, and
 * {@code BatchJobLaunchRequestTest} asserts that the wire names and those constants agree.
 *
 * <p>Provenance: the program parameter at {@code app/jcl/INTCALC.jcl}, the sort symbols of
 * {@code app/jcl/TRANREPT.jcl}, the four collapsed read jobs {@code app/jcl/READACCT.jcl},
 * {@code READCARD.jcl}, {@code READCUST.jcl} and {@code READXREF.jcl}, and the two concatenated inputs
 * of {@code app/jcl/COMBTRAN.jcl}; read as read-only reference at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No job control statement text is transcribed.
 *
 * @param interestParmDate the interest run's ten-character parameter value, or {@code null} when the
 *        addressed job does not take one
 * @param reportStartDate the inclusive lower bound of the transaction-report window in hyphenated ISO
 *        form, or {@code null}
 * @param reportEndDate the inclusive upper bound of the same window, or {@code null}
 * @param fileProbeMode the symbolic name of the file the probe job should read, or {@code null}
 * @since 1.0.0
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "Typed, closed parameter surface of a batch job launch. Only the parameters the "
        + "addressed job declares may be supplied; an unknown property and a parameter the addressed "
        + "job does not declare are both refused.")
public record BatchJobLaunchRequest(

        @Schema(description = "Interest-run parameter value, exactly ten digits. Its characters become "
                + "the leading characters of every transaction identifier the run synthesises, so it is "
                + "transmitted unaltered.")
        @Size(max = BatchJobLaunchRequest.INTEREST_PARM_DATE_WIDTH)
        @Pattern(regexp = BatchJobLaunchRequest.DIGITS_ONLY)
        String interestParmDate,

        @Schema(description = "Inclusive lower bound of the transaction-report window, hyphenated ISO "
                + "form. Compared as characters, exactly as the legacy sort filter compares it.")
        @Size(max = BatchJobLaunchRequest.ISO_DATE_WIDTH)
        @Pattern(regexp = BatchJobLaunchRequest.ISO_DATE)
        String reportStartDate,

        @Schema(description = "Inclusive upper bound of the transaction-report window, hyphenated ISO "
                + "form.")
        @Size(max = BatchJobLaunchRequest.ISO_DATE_WIDTH)
        @Pattern(regexp = BatchJobLaunchRequest.ISO_DATE)
        String reportEndDate,

        @Schema(description = "Symbolic name of the file the probe job should read. The legal names "
                + "belong to the probe job configuration and are checked by the job's own validator.")
        @Size(max = BatchJobLaunchRequest.SYMBOLIC_NAME_WIDTH)
        @Pattern(regexp = BatchJobLaunchRequest.SYMBOLIC_NAME)
        String fileProbeMode) {

    /**
     * Width of the interest run's parameter value: ten characters, the declared width of the field the
     * interest program receives it into.
     */
    public static final int INTEREST_PARM_DATE_WIDTH = 10;

    /** Width of a hyphenated ISO date, the declared width of both report sort symbols. */
    public static final int ISO_DATE_WIDTH = 10;

    /**
     * Width bound on a symbolic launch value such as the probe mode. Generous relative to the four legal
     * names, because this is a transport bound and the enumeration itself is checked by the job.
     */
    public static final int SYMBOLIC_NAME_WIDTH = 32;

    /** Digits and nothing else. */
    public static final String DIGITS_ONLY = "^[0-9]*$";

    /**
     * Hyphenated ISO date shape. A shape check only - whether the date exists in the calendar, and
     * whether the window is ordered, is decided by the report job's own validator.
     */
    public static final String ISO_DATE = "^$|^[0-9]{4}-[0-9]{2}-[0-9]{2}$";

    /** Letters and digits, which is what every symbolic launch value in this module is made of. */
    public static final String SYMBOLIC_NAME = "^[A-Za-z0-9]*$";

    /** The empty request: the launch of a job that declares no parameter. */
    private static final BatchJobLaunchRequest EMPTY =
            new BatchJobLaunchRequest(null, null, null, null);

    /**
     * The request a launch that carried no body at all is read as.
     *
     * <p>A shared instance, safe to publish because the record is immutable, so a launch with no body
     * costs no allocation and needs no {@code null} check downstream.
     *
     * @return a request supplying no parameter, never {@code null}
     */
    public static BatchJobLaunchRequest empty() {
        return EMPTY;
    }
}
