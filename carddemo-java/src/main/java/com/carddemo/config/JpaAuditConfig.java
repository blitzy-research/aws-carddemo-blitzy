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
package com.carddemo.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The module's single source of "now", and the recorded reason JPA auditing is not switched on.
 *
 * <p>Publishes exactly one bean: the {@link Clock} that every collaborator needing the current date or
 * time constructor-injects, replacing the date-and-time work area {@code app/cpy/CSDAT01Y.cpy} duplicated
 * textually into all seventeen online programs. Obtaining the current moment by any other means &mdash;
 * the no-argument instant and local-date-time factories, the legacy mutable date type, the millisecond
 * counter on {@code java.lang.System} &mdash; is prohibited module-wide, because a value read that way
 * cannot be pinned and makes every timestamp assertion either impossible or flaky. The prohibition extends
 * to construction: no type may offer a convenience constructor or factory that manufactures a clock for a
 * caller that did not supply one, because that reintroduces the host clock behind an argument list that
 * looks safe.
 *
 * <p>Two timestamp images exist, both exactly twenty-six characters wide, deliberately not unified and
 * <strong>not interchangeable</strong>: emitting one where the other is expected yields a value of the
 * right length that is wrong byte for byte, the hardest kind of parity defect to notice. The online image
 * ({@code app/cpy/CSDAT01Y.cpy}) is {@code YYYY-MM-DD HH:MM:SS.mmmmmm} &mdash; space, colons, six genuine
 * digits. The batch image ({@code app/cbl/CBTRN02C.cbl}, identically {@code app/cbl/CBACT04C.cbl}) is
 * {@code YYYY-MM-DD-HH.MM.SS.mm0000} &mdash; hyphen where the online form has a space, dots between the
 * time parts, two significant digits, then a four-character constant tail.
 *
 * <p><strong>That constant tail must not be "improved".</strong> The intrinsic feeding it reports
 * hundredths into a two-character receiver, so two digits are all the estate ever had, and the remaining
 * four are filled by moving a literal {@code 0000} ({@code app/cbl/CBTRN02C.cbl:L701}). Emitting real
 * microseconds would produce a twenty-six-character value that looks correct and fails byte comparison on
 * every record; proving the padding is a constant rather than an accident of when a test ran requires a
 * clock that can be pinned, which is why this bean exists instead of scattered platform-clock calls.
 *
 * <p><strong>Both images discard the zone offset</strong>, so a persisted timestamp is bounded
 * twenty-six-character text rather than an instant: {@code V1__create_schema.sql} declares the four
 * affected columns {@code VARCHAR(26)} and {@code application.yml} pins the persistence layer to UTC, so
 * such a field round-trips identically whichever host wrote it. <strong>The clock is UTC because the zone
 * is part of the output contract</strong> &mdash; any other zone would make a written timestamp disagree
 * with the value read back and make one job emit different bytes on two hosts, whereas byte-equivalent
 * output has to be reproducible from the input alone. The module has exactly one configured zone and no
 * component may introduce a second from the platform default, a host setting or a property of its own, so
 * a civil-time view is derived from <strong>this</strong> clock in <strong>its</strong> zone. Decision log
 * entry D-35.
 *
 * <p><strong>No formatter is defined here.</strong> Each image is rendered by a helper inside whichever
 * component owns the records it appears on, so the two shapes are never one careless import apart; the
 * layouts are described here, once, as the authority those owners implement against.
 *
 * <p><strong>JPA auditing is deliberately not enabled</strong>, on three verified facts.
 * {@code V1__create_schema.sql} defines no creation-timestamp, modification-timestamp or principal column
 * on any of the eleven tables, every column mapping instead to a field of the fixed-width layout its table
 * was derived from, the only exceptions being the optimistic-locking version columns on account and card.
 * No persistent type carries an auditing annotation, and none may acquire one. And {@code application.yml}
 * runs the schema check in validate mode, so mapping a property to a column the migrations do not create
 * aborts start-up rather than degrading gracefully. There is therefore no audited property to act upon, and
 * adding audit columns is excluded because this migration adds no field the estate did not have; no
 * auditing date-time provider and no auditor supplier are declared for the same reason, both being
 * consulted only by infrastructure that is intentionally absent. See {@code docs/decision-log.md} DL-089.
 *
 * <p>Stateless and immutable: final, no fields, no static mutable state, no collaborators. The published
 * clock is itself immutable and safe for unsynchronised concurrent use, so one instance serves every
 * injection point.
 */
@Configuration(proxyBeanMethods = false)
public final class JpaAuditConfig {

    /**
     * Declared explicitly so that the absence of collaborators is visible: a time source that itself
     * depended on another bean could not be the first thing every other bean is built against.
     */
    public JpaAuditConfig() {
    }

    /**
     * The one time source for the whole module, fixed to UTC.
     *
     * <p>A test replaces this bean with a fixed clock and nothing else about the wiring changes, which is
     * what lets it assert an emitted timestamp image character for character.</p>
     *
     * @return an immutable UTC clock, never {@code null}
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
