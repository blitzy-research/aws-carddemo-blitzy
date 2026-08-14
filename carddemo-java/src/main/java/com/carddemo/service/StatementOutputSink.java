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

/**
 * Where one statement-generation run emits its output, one item at a time, as it produces it.
 *
 * <p>The destination half of {@link StatementGenerationService}. The service owns the record content and
 * the order it is produced in; this interface owns nothing but the act of receiving one item. Between them
 * they replace the two write statements of {@code [app/cbl/CBSTM03A.CBL]} - the 80-byte plain record of
 * {@code L45} and the 100-byte markup record of {@code L47} - with a call per record rather than a list
 * per run.
 *
 * <p><strong>Why a sink rather than a returned collection.</strong> A legacy write hands one record to an
 * open dataset and forgets it, so the program's working storage never holds the file. One run emits one
 * statement per cross-reference record it consumes {@code [app/cbl/CBSTM03A.CBL:L317-L329]}, a count
 * bounded only by the input, so returning both streams would hold the whole output of an unbounded input
 * on the heap and copy it once more into the result. This interface keeps the translated program's working
 * set bounded by one record, exactly as the legacy program's was.
 *
 * <p><strong>Every method is called during the run, never after it.</strong> A caller therefore observes
 * emission order directly, and a caller writing to a destination has written every record before the run
 * returns. One consequence is deliberate: a postcondition that can only be evaluated on a completed run -
 * the count of dispatcher entries, or the consistency of the run's own tallies - is evaluated
 * <em>after</em> records have already reached the sink. A destination whose content must not survive a
 * failed run is therefore obliged to stage its output and seal it only once the run has completed, which
 * is what the statement job does with its two working files.
 *
 * <p><strong>Nothing here is optional and nothing here may be discarded silently.</strong> An
 * implementation that swallows an item is indistinguishable from a run that never produced it, which is
 * the one failure a fixed-width dataset contract cannot tolerate. An implementation that cannot accept an
 * item must throw; the exception propagates through the run and is reported as a failed generation.
 *
 * <p><strong>Not thread-safe by contract, and it does not need to be.</strong> One run holds one sink and
 * emits from a single thread, so an implementation may keep unsynchronised position or counter state - the
 * same reasoning that keeps a file position out of a shared singleton; see
 * {@link StatementCrossReferenceSource}.
 *
 * <p>Recorded as DL-293 in {@code docs/decision-log.md}, which also records how the one consequence of
 * streaming is contained.
 *
 * @see StatementGenerationService
 * @see StatementLineSummary
 * @since 1.0.0
 */
public interface StatementOutputSink {

    /**
     * Receives one plain statement record: one call per legacy write to the plain statement file.
     *
     * @param record the complete record at its declared width, never {@code null} and never carrying a
     *               line terminator - record framing on a destination belongs to the destination
     */
    void statementRecord(String record);

    /**
     * Receives one markup statement record: one call per legacy write to the markup statement file.
     *
     * @param record the complete record at its declared width, never {@code null} and never carrying a
     *               line terminator
     */
    void htmlRecord(String record);

    /**
     * Receives one per-transaction summary of a line the run has just emitted.
     *
     * <p>The legacy program writes no such summary to any dataset: this is the tabulated transaction the
     * detail line was composed from, surfaced so a caller can observe what the run charged to a card
     * without parsing the fixed-width record back apart. A destination with no use for it counts and drops
     * it.
     *
     * @param summary the summary of the line just emitted, never {@code null}
     */
    void transactionSummary(StatementLineSummary summary);

    /**
     * Receives the phase selector the run is about to branch on, once per dispatcher entry.
     *
     * <p>This is the observable form of {@code WS-FL-DD} at each entry to the dispatcher of
     * {@code [app/cbl/CBSTM03A.CBL:L296]}, and it is what makes the mandatory re-entry after every state
     * change verifiable from outside the service. The count of entries is fixed by the state machine and
     * is small, so a caller may retain the whole sequence; the two record streams are not, and must not
     * be retained.
     *
     * @param phase the selector value the dispatcher is about to branch on, never {@code null}
     */
    void dispatchedPhase(String phase);
}
