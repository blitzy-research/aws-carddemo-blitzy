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
 * time constructor-injects. It replaces the {@code WS-DATE-TIME} work area that
 * {@code app/cpy/CSDAT01Y.cpy} duplicated textually into all seventeen online programs, collapsing its
 * five views of one moment into one injected source. Obtaining the current moment by any other means
 * &mdash; the no-argument instant and local-date-time factories, the legacy mutable date type, the
 * millisecond counter on {@code java.lang.System} &mdash; is prohibited module-wide, because a value
 * read that way cannot be pinned and makes every timestamp assertion either impossible or flaky. The
 * prohibition extends to construction: no type may offer a convenience constructor or factory that
 * manufactures a clock for a caller that did not supply one, because that silently reintroduces the host
 * clock behind an argument list that looks safe.
 *
 * <h2>Two twenty-six-character timestamp images, deliberately not unified</h2>
 * Both occupy exactly twenty-six characters and they are <strong>not interchangeable</strong>; emitting
 * one where the other is expected produces a value of the right length that is wrong byte for byte,
 * which is the hardest kind of parity defect to notice.
 * <ul>
 *   <li><strong>Online</strong>, from {@code WS-TIMESTAMP} at {@code app/cpy/CSDAT01Y.cpy} lines 42-55,
 *       shaped {@code YYYY-MM-DD HH:MM:SS.mmmmmm}: a <strong>space</strong> between date and time,
 *       <strong>colons</strong> between the time parts, and a fraction of <strong>six genuine
 *       digits</strong>, because the trailing item on line 55 is declared six digits wide.</li>
 *   <li><strong>Batch</strong>, from the twenty-six-byte item {@code DB2-FORMAT-TS} at
 *       {@code app/cbl/CBTRN02C.cbl} line 159 shaped by the redefinition on lines 160-174, giving
 *       {@code YYYY-MM-DD-HH.MM.SS.mm0000}: a <strong>hyphen</strong> where the online form has a space,
 *       <strong>dots</strong> between the time parts, and a fraction of only <strong>two significant
 *       digits</strong> followed by a four-character constant tail. Built by
 *       {@code Z-GET-DB2-FORMAT-TIMESTAMP} at lines 692-705, which appears identically in
 *       {@code app/cbl/CBACT04C.cbl}.</li>
 * </ul>
 *
 * <p><strong>The batch fraction ends in a constant, and must not be "improved".</strong> The intrinsic
 * feeding it reports hundredths, and the receiving item {@code COB-MIL} at
 * {@code app/cbl/CBTRN02C.cbl} line 157 is two bytes wide, so two digits are all the estate ever had;
 * line 701 pads them to a six-place fraction by moving a <strong>literal</strong> {@code 0000} into the
 * remaining four characters. An implementation emitting real microseconds there would produce a
 * twenty-six-character value that looks correct and fails byte comparison on every record. A test can
 * only prove that padding is a constant rather than an accident of when it ran if the clock behind it
 * can be pinned, which is why this bean exists instead of scattered platform-clock calls.
 *
 * <p><strong>Both images discard the zone offset</strong> &mdash; on the batch side into the five-byte
 * item {@code COB-REST} ({@code app/cbl/CBTRN02C.cbl} line 158), which is never moved anywhere. A
 * persisted timestamp is consequently bounded twenty-six-character text rather than an instant:
 * {@code V1__create_schema.sql} declares the four affected columns {@code VARCHAR(26)}, and
 * {@code application.yml} pins the persistence layer's time zone to UTC so such a field round-trips
 * identically no matter which host wrote it.
 *
 * <p><strong>The clock is UTC, and the zone is part of the output contract.</strong> Any other zone
 * would make a written timestamp disagree with the value read back, and would make one job emit
 * different bytes on two hosts whose regional settings differ; byte-equivalent output has to be
 * reproducible from the input alone. The module has exactly one configured zone and no component may
 * introduce a second, whether from the platform default, a host setting or a property of its own, so a
 * civil-time view is derived from <strong>this</strong> clock in <strong>its</strong> zone. Recorded as
 * decision log entry D-35.
 *
 * <p><strong>No formatter is defined here.</strong> This class supplies the time source only; each image
 * is rendered by a helper inside whichever component owns the records it appears on, so the two shapes
 * are never one careless import apart. The layouts are documented here, in one place, so every owner has
 * one authoritative description to implement against.
 *
 * <p><strong>JPA auditing is deliberately not enabled</strong>, and that is a recorded decision resting
 * on three verified facts. {@code V1__create_schema.sql} defines no creation-timestamp,
 * modification-timestamp or principal column on any of the eleven tables &mdash; every column maps to a
 * field of the fixed-width layout its table was derived from, the only exceptions being the
 * optimistic-locking version columns on account and card. No persistent type carries an auditing
 * annotation, and none may acquire one. And {@code application.yml} runs the schema check in validate
 * mode, so mapping a property to a column the migrations do not create aborts start-up rather than
 * degrading gracefully. There is therefore no audited property for auditing to act upon, and adding
 * audit columns is out of the question because this migration adds no field the estate did not have. No
 * auditing date-time provider and no auditor supplier are declared for the same reason: both are
 * consulted only by infrastructure that is intentionally absent, and a bean nothing consumes is dead
 * configuration.
 *
 * <p>Stateless and immutable: final, no fields, no static mutable state, no collaborators. The published
 * clock is itself immutable and safe for unsynchronised concurrent use, so one instance is shared by
 * every injection point.
 *
 * <p>Each fact above is stated once. An earlier revision gave the auditing decision and the zone
 * rationale twice over and enumerated the five legacy views of the date work area field by field, for
 * views this class does not implement; see {@code docs/decision-log.md} DL-089.
 */
@Configuration(proxyBeanMethods = false)
public final class JpaAuditConfig {

    /**
     * Creates the configuration singleton.
     *
     * <p>Declared explicitly so that the absence of collaborators is visible: a time source that itself
     * depended on another bean could not be the first thing every other bean is built against.</p>
     */
    public JpaAuditConfig() {
    }

    /**
     * The one time source for the whole module, fixed to UTC.
     *
     * <p>A test replaces this bean with a fixed clock and nothing else about the wiring changes, which
     * is what lets it assert an emitted timestamp image character for character. The zone rationale and
     * the two image shapes are in the class documentation.</p>
     *
     * @return an immutable UTC clock, never {@code null}
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
