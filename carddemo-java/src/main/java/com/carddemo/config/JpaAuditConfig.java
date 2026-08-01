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
 * time constructor-injects. It replaces the {@code WS-DATE-TIME} work area that {@code CSDAT01Y}
 * duplicated textually into all seventeen online programs, and it is what makes a timestamp assertion
 * possible at all, because a pinned clock is the only way a fixed-width timestamp image can be
 * compared against an expected value. Obtaining the current moment by any other means &mdash; the
 * no-argument instant and local-date-time factories, the legacy mutable date type, the millisecond
 * counter on {@code java.lang.System} &mdash; is prohibited module-wide, because a value read that way
 * cannot be pinned and makes every timestamp assertion either impossible or flaky.
 *
 * <h2>Provenance</h2>
 * Translated from the AWS CardDemo z/OS mainframe application at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The two legacy authorities are
 * {@code app/cpy/CSDAT01Y.cpy} for the online date and time work fields and
 * {@code app/cbl/CBTRN02C.cbl} for the batch timestamp layout. Neither is read at run time and no
 * COBOL statement is reproduced anywhere below: only member names, field names, declared widths and
 * line numbers cross over.
 *
 * <h2>What this replaces: one clock for seventeen programs</h2>
 * {@code app/cpy/CSDAT01Y.cpy} declares the group item {@code WS-DATE-TIME} on line 17 and is
 * included textually by <strong>all seventeen</strong> online programs of the estate. Counted
 * together with its three sibling wide-fan-out copybooks &mdash; the communication area, the screen
 * title and the common message catalog, each likewise included by all seventeen &mdash; that family
 * accounts for <strong>sixty-eight</strong> textual inclusions. Every one of those copies carried
 * its own storage, and every including program filled it from the language's current-date intrinsic.
 * The group holds five views of the same moment:
 * <ul>
 *   <li>{@code WS-CURDATE} (lines 19-22): {@code WS-CURDATE-YEAR} of four digits,
 *       {@code WS-CURDATE-MONTH} and {@code WS-CURDATE-DAY} of two digits each, eight digits in
 *       total, redefined on line 23 as the single eight-digit numeric item
 *       {@code WS-CURDATE-N};</li>
 *   <li>{@code WS-CURTIME} (lines 24-28): hours, minutes, seconds and
 *       {@code WS-CURTIME-MILSEC}, two digits each, again eight digits, redefined on line 29
 *       as the single eight-digit numeric item {@code WS-CURTIME-N};</li>
 *   <li>{@code WS-CURDATE-MM-DD-YY} (lines 30-35): the eight-character screen date, two-digit
 *       month, day and year separated by a solidus;</li>
 *   <li>{@code WS-CURTIME-HH-MM-SS} (lines 36-41): the eight-character screen time, two-digit
 *       hours, minutes and seconds separated by a colon;</li>
 *   <li>{@code WS-TIMESTAMP} (lines 42-55): the twenty-six-character online timestamp described
 *       below.</li>
 * </ul>
 * All five collapse into this one injected clock. The formatting of each view belongs to whichever
 * component emits it, for the reason given two sections further down.
 *
 * <h2>Two twenty-six-character timestamp images, deliberately not unified</h2>
 * The estate carries two timestamp layouts. Both occupy exactly twenty-six characters and they are
 * <strong>not interchangeable</strong>; emitting one where the other is expected produces a value of
 * the right length that is wrong byte for byte, which is the hardest kind of parity defect to
 * notice.
 * <ul>
 *   <li><strong>Online</strong>, from {@code WS-TIMESTAMP} at {@code app/cpy/CSDAT01Y.cpy} lines
 *       42-55, shaped {@code YYYY-MM-DD HH:MM:SS.mmmmmm}: a <strong>space</strong> between the date
 *       and the time, <strong>colons</strong> between the time parts, and a fraction of
 *       <strong>six genuine digits</strong>, because the trailing item
 *       {@code WS-TIMESTAMP-TM-MS6} on line 55 is declared six digits wide.</li>
 *   <li><strong>Batch</strong>, from the twenty-six-byte alphanumeric item {@code DB2-FORMAT-TS}
 *       declared at {@code app/cbl/CBTRN02C.cbl} line 159 and given its field-by-field shape by the
 *       redefinition on lines 160-174, shaped {@code YYYY-MM-DD-HH.MM.SS.mm0000}: a
 *       <strong>hyphen</strong> where the online form has a space, <strong>dots</strong> between the
 *       time parts, and a fraction of only <strong>two significant digits</strong> followed by a
 *       four-character constant tail. It is built by the paragraph
 *       {@code Z-GET-DB2-FORMAT-TIMESTAMP} at lines 692-705 of that program, which appears
 *       identically in {@code app/cbl/CBACT04C.cbl}.</li>
 * </ul>
 *
 * <h3>Why the batch fraction ends in a constant</h3>
 * The batch layout does not lose precision by accident and it must not be "improved". The intrinsic
 * that feeds it reports hundredths of a second, and the receiving item it is moved through,
 * {@code COB-MIL} of the {@code COBOL-TS} group at {@code app/cbl/CBTRN02C.cbl} line 157, is only
 * two bytes wide &mdash; exactly as {@code WS-CURTIME-MILSEC} is only two digits wide on
 * the online side. Two digits of hundredths are therefore all the estate ever had, and line 701 of
 * that paragraph pads them out to a six-place fraction by moving a <strong>literal</strong>
 * {@code 0000} into the remaining four characters. An implementation that emitted real microseconds
 * in those four positions would produce a twenty-six-character value that looks correct and fails
 * byte comparison on every record. That padding is a constant, and a test can only prove it is a
 * constant rather than an accident of when the test happened to run if the clock behind it can be
 * pinned &mdash; which is the whole reason this bean exists instead of scattered calls to the
 * platform clock.
 *
 * <h3>Both images discard the zone offset</h3>
 * The current-date intrinsic returns a trailing offset from Greenwich, and both layouts throw it
 * away: on the batch side the {@code COBOL-TS} group ends in the five-byte alphanumeric item
 * {@code COB-REST} ({@code app/cbl/CBTRN02C.cbl} line 158), which absorbs the offset and is never
 * moved anywhere. A
 * persisted timestamp in this estate is consequently a bounded twenty-six-character text field
 * rather than an instant: {@code V1__create_schema.sql} declares the four affected columns
 * {@code VARCHAR(26)}, and {@code application.yml} pins the persistence layer's own time zone to
 * UTC so that such a field round-trips identically no matter which host wrote it.
 *
 * <p><strong>No formatter is defined here.</strong> This class supplies the time source and nothing
 * else; the two images are rendered by private or nested helpers inside whichever component owns the
 * records they appear on, so that the two shapes are never one careless import apart. The batch image
 * is already rendered from the injected clock by the batch step template; the online image is the
 * obligation of each online component that stamps a record, none of which is delivered yet. The
 * layouts are documented here, in one place, so every owner has one authoritative description to
 * implement against.
 *
 * <p><strong>The clock is UTC, and the zone is part of the output contract.</strong> A clock in any
 * other zone would make a written timestamp disagree with the value read back, and would make the same
 * job emit different bytes on two hosts whose regional settings differ; byte-equivalent output has to
 * be reproducible from the input alone. A component needing a civil-time view derives it from this
 * clock rather than substituting another one. Recorded as decision log entry D-35.
 *
 * <p><strong>JPA auditing is deliberately not enabled.</strong> {@code V1__create_schema.sql} defines
 * no creation or modification timestamp and no principal column on any of the eleven tables, no
 * persistent type in this module carries an auditing annotation, and {@code application.yml} runs the
 * schema check in validate mode, so mapping a property to a column the migrations do not create aborts
 * start-up rather than degrading gracefully. There is no audited property for auditing to act upon, and
 * adding audit columns is out of the question because this migration adds no field the estate did not
 * have. No auditing date-time provider and no auditor supplier are declared for the same reason.
 *
 * <p>The prohibition extends to construction. No type in this module may offer a convenience
 * constructor or factory that manufactures a clock for a caller that did not supply one, because such
 * a path silently reintroduces the host clock behind an argument list that looks safe. Where a
 * component needs the current moment, the clock is a required constructor parameter on its only
 * construction path &mdash; as it is on the batch step template, whose sole constructor takes it.</p>
 *
 * <h2>One zone, and where civil time comes from</h2>
 * Some legacy images are civil dates and times rather than instants, because the intrinsic that fed
 * them returned civil time. Those images are still derived from <strong>this</strong> clock, rendered
 * in <strong>its</strong> zone; the module has exactly one configured zone and no component may
 * introduce a second one, whether from the platform default, from a host setting or from a property
 * of its own. Changing the business zone therefore means changing this one bean, and every consumer
 * follows. That is what keeps an emitted timestamp a function of the input alone rather than of the
 * machine the job happened to run on.
 *
 * <h2>Why JPA auditing is not enabled</h2>
 * Framework-managed auditing is deliberately <strong>not</strong> switched on, and this is a recorded
 * decision rather than an omission. It rests on three verified facts:
 * <ol>
 *   <li>{@code V1__create_schema.sql} creates the eleven application tables and defines
 *       <strong>no</strong> creation-timestamp, modification-timestamp, creating-principal or
 *       modifying-principal column on any of them. Every column of every table maps to a field of
 *       the fixed-width record layout that table was derived from; the only columns without a
 *       legacy counterpart are the optimistic-locking version columns on the account and card
 *       tables.</li>
 *   <li>No persistent type in this module carries an auditing annotation, and none may acquire one.
 *       The entities own their own mappings and there is no shared audited supertype.</li>
 *   <li>{@code application.yml} runs the persistence provider's schema check in validate mode.
 *       Mapping a property to a column the migrations do not create therefore does not degrade
 *       gracefully &mdash; it fails the schema check and aborts application start-up.</li>
 * </ol>
 * Taken together there is no audited property for auditing to act upon, so enabling it would
 * register infrastructure into every application context, including the sliced contexts of tests
 * that do not involve persistence at all, in exchange for no behaviour whatsoever. Adding the audit
 * columns instead is out of the question: this migration reproduces the legacy record layouts and
 * adds no field the estate did not have. For the same reason no auditing date-time provider and no
 * auditor supplier are declared here, because both are consulted only by the auditing infrastructure
 * that is intentionally absent, and a bean nothing consumes is dead configuration.
 *
 * <p>Stateless and immutable: final, no fields, no static mutable state, no collaborators. The
 * published clock is itself immutable and safe for unsynchronised concurrent use, so the one instance
 * is shared by every injection point.
 */
@Configuration(proxyBeanMethods = false)
public final class JpaAuditConfig {

    /**
     * Creates the configuration singleton.
     *
     * <p>Declared explicitly rather than left implicit so that the absence of collaborators is a
     * visible property of the class: a time source that itself depended on another bean could not be
     * the first thing every other bean is built against.</p>
     */
    public JpaAuditConfig() {
        // No collaborators to inject: the published clock is created from a platform factory and this
        // class holds no state of its own.
    }

    /**
     * The one time source for the whole module, fixed to UTC.
     *
     * <p>Every component needing the current date or time constructor-injects this clock rather than
     * reaching the host clock directly, which is what lets a test pin the moment and assert an emitted
     * timestamp image character for character &mdash; including the constant tail of the batch layout
     * described in the class documentation. The zone is UTC because it is part of the output contract:
     * it matches the time zone the persistence layer is configured with, so a bounded
     * twenty-six-character timestamp column reads back exactly as written and the same job emits the
     * same bytes regardless of the host's regional settings. A test replaces this bean with a fixed
     * clock and nothing else about the wiring changes.</p>
     *
     * @return an immutable UTC clock, never {@code null}
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
