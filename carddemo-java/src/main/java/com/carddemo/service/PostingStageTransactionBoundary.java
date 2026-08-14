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

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens the durable unit of work for <strong>one posting stage</strong> of one daily-transaction record.
 *
 * <h2>Why a stage and not a record</h2>
 *
 * <p>The legacy posting member performs three stores in one order and one order only - the
 * category-balance update, the account rewrite and the transaction-file write, at lines 440 to 442 - and
 * each is an <em>independent</em> durable operation. All three files are defined {@code RECOVERY(NONE)}
 * with {@code JOURNAL(NO)} and the member contains no rollback site of any kind, the estate's only
 * explicit rollback belonging to the online account-update program. A store that completed therefore
 * stayed completed whatever the store after it did: a record whose transaction-file write is refused keeps
 * the category balance and the account rewrite that preceded it, and the member abends with those two
 * stores already in the dataset.
 *
 * <p>Making the three stores one atomic unit would invent an all-or-none property the source does not
 * have and would invert the observable state after a late failure, discarding two stores no legacy
 * mechanism discards. This collaborator therefore owns the boundary of <em>one stage</em>, and the posting
 * service calls it three times per posted record, in the source's order.
 *
 * <p><strong>Why a separate bean.</strong> {@link TransactionPostingService} reproduces the mainline loop
 * and the posting cascade in its own methods, so a transactional annotation on that class could only ever
 * apply to a call arriving from outside it - a self-invocation reaches the target directly and consults no
 * proxy, so the annotation would silently do nothing for every stage the cascade drives. Placing the
 * boundary on a different bean removes the possibility: the cascade necessarily calls through this proxy.
 *
 * <p><strong>Why {@link Propagation#REQUIRES_NEW}.</strong> The step driving the posting run is
 * chunk-oriented, so the framework has already opened a transaction around the reader, processor and
 * writer by the time a record reaches the cascade. {@code REQUIRED} would join it and all three stages
 * would commit or roll back together - precisely the atomicity the source does not have.
 * {@code REQUIRES_NEW} suspends whatever the caller opened and gives the stage a unit of its own, so a
 * stage that completed is durable before the next begins and a later failure cannot reach back and undo
 * it. A stage never wraps more than one store, so a unit is never held across another unit's work, and
 * the loop stays outside every one of them - which keeps input scanning, the reject sink and the
 * framework's own metadata the step's work rather than a stage's.
 *
 * <p>The callback carries no posting semantics of its own. Any unchecked failure that escapes it - an
 * abend, a file-status failure, or an optimistic-lock conflict on the account's version attribute -
 * leaves this method, so the framework rolls <em>that stage</em> back and control returns to the cascade
 * with the stages before it already committed. That is the state the legacy reached, and it is what makes
 * the reject dataset and the posted dataset describe the same events they always did.
 *
 * <h2>Why the two keyed validation reads take a unit as well</h2>
 *
 * <p>Validation reads the cross-reference and then the account, and those reads are the member's other
 * two I/O acts - a legacy read was an act, not a unit of work, and nothing held one open across them.
 * Reproducing that is what keeps the account rewrite honest. The rewrite carries its three computed
 * balances onto the account image before it runs, exactly as lines 547 to 552 do before line 554, and the
 * image it carries them onto is the one validation read. Were that read to have happened in a
 * <em>caller's</em> unit of work, the image would still be managed there after the rewrite committed, so
 * the caller's own commit would write those balances a second time - carrying the version the rewrite had
 * already advanced, which the store would refuse. Reading through this boundary hands the cascade a
 * detached image instead, so the values it carries are a report and never a second write.
 *
 * <p>Recorded as decision {@code DL-324} in {@code docs/decision-log.md}, which also records the restart
 * consequence. Provenance: {@code app/cbl/CBTRN02C.cbl}, the mainline of lines 208 to 230 and the three
 * posting stages of lines 440 to 442, read as read-only reference.
 *
 * @since 1.0.0
 */
@Service
public class PostingStageTransactionBoundary {

    /**
     * Executes one I/O act of one daily-transaction record in a durable unit of its own.
     *
     * @param operation one act of the translated member - the cross-reference read, the account read, the
     *                  category-balance update, the account rewrite, or the transaction-file write
     * @param <T>       the per-act result type
     * @return the operation's result once that act's transaction has committed
     * @throws NullPointerException if the operation is absent
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T execute(final Supplier<T> operation) {
        return Objects.requireNonNull(operation, "operation must not be null").get();
    }
}
