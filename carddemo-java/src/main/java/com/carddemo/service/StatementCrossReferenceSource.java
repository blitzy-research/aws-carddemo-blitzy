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

import java.util.Optional;

import com.carddemo.domain.CardCrossReference;

/**
 * Sequential cross-reference input consumed by one statement-generation run.
 *
 * <p>The companion of {@link StatementTransactionSource}. Between them they cover the two files that
 * {@code [app/cbl/CBSTM03B.CBL]} declares {@code ACCESS MODE SEQUENTIAL} - the transaction work resource
 * at {@code [app/cbl/CBSTM03B.CBL:L33]} and the cross-reference cluster at
 * {@code [app/cbl/CBSTM03B.CBL:L39]}. The other two files are {@code ACCESS MODE RANDOM} and are read by
 * key, so they need no source.
 *
 * <p>A source stands for the file position the COBOL runtime holds on the program's behalf, which is
 * state, and state may not live in a shared singleton. One source therefore belongs to exactly one run:
 * two runs sharing the same statement services cannot observe one another's position, because neither
 * holds one.
 *
 * <p><strong>Why the position is not an offset.</strong> An implementation is required to walk the
 * cluster once, in key order, retaining one bounded page. It must not translate a position into a
 * database offset: offset access re-scans and re-discards every preceding row on every read, which turns
 * one sequential pass into a quadratic one, and a paged query additionally counts the whole table to
 * populate a page descriptor no caller reads. The contract below is deliberately narrow enough that only
 * a forward cursor can satisfy it.
 *
 * <p>The reasoning behind this interface, the per-run cursor that satisfies it and the file-status
 * translation that accompanies it is recorded as DL-174 in {@code docs/decision-log.md}.
 *
 * @see StatementTransactionSource
 */
@FunctionalInterface
public interface StatementCrossReferenceSource {

    /**
     * Reads the cross-reference record at one sequential position.
     *
     * <p>Positions are zero-based and must be requested in ascending order with no gaps. The position
     * most recently served may be re-requested and yields the same record without a further retrieval,
     * which is what lets the file's open probe its first record and the first read then consume it. Any
     * other position - one already passed, or one beyond the next - is a programming error rather than a
     * runtime condition, because the legacy read advances the position by exactly one and its open resets
     * it to the first record.
     *
     * <p>Exhaustion is an empty result and stays distinct from a technical failure: a technical failure
     * surfaces as the runtime exception the underlying retrieval raises, which the file handler translates
     * into a raw two-character file status.
     *
     * @param position zero-based sequential position
     * @return the record at that position, or empty at end of file
     * @throws IllegalArgumentException if {@code position} is negative
     * @throws IllegalStateException    if {@code position} neither repeats the position most recently
     *                                  served nor immediately follows it
     */
    Optional<CardCrossReference> readAt(int position);
}
