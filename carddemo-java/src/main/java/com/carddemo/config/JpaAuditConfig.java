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
 * <p>This class publishes exactly one bean: the {@link Clock} that every collaborator needing the
 * current date or time constructor-injects. It replaces a declaration the legacy estate duplicated
 * textually in every program that needed a timestamp, and it is what makes a timestamp assertion
 * possible at all, because a pinned clock is the only way a fixed-width timestamp image can be
 * compared against an expected value.</p>
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
 *   <li>{@code WS-CURDATE} (lines 19-22): {@code WS-CURDATE-YEAR PIC 9(04)},
 *       {@code WS-CURDATE-MONTH PIC 9(02)} and {@code WS-CURDATE-DAY PIC 9(02)}, eight digits in
 *       total, redefined on line 23 as the single numeric item {@code WS-CURDATE-N PIC 9(08)};</li>
 *   <li>{@code WS-CURTIME} (lines 24-28): hours, minutes, seconds and
 *       {@code WS-CURTIME-MILSEC}, each {@code PIC 9(02)}, again eight digits, redefined on line 29
 *       as {@code WS-CURTIME-N PIC 9(08)};</li>
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
 *       {@code WS-TIMESTAMP-TM-MS6} on line 55 is declared {@code PIC 9(06)}.</li>
 *   <li><strong>Batch</strong>, from {@code DB2-FORMAT-TS PIC X(26)} declared at
 *       {@code app/cbl/CBTRN02C.cbl} line 159 and given its field-by-field shape by the
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
 * {@code PIC X(02)} wide &mdash; exactly as {@code WS-CURTIME-MILSEC} is only {@code PIC 9(02)} on
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
 * away: on the batch side the {@code COBOL-TS} group ends in {@code COB-REST PIC X(05)}
 * ({@code app/cbl/CBTRN02C.cbl} line 158), which absorbs the offset and is never moved anywhere. A
 * persisted timestamp in this estate is consequently a bounded twenty-six-character text field
 * rather than an instant: {@code V1__create_schema.sql} declares the four affected columns
 * {@code VARCHAR(26)}, and {@code application.yml} pins the persistence layer's own time zone to
 * UTC so that such a field round-trips identically no matter which host wrote it.
 *
 * <h2>Why no formatter is defined here</h2>
 * This class supplies the time source and nothing else. There is deliberately <strong>no</strong>
 * top-level timestamp-formatter type anywhere in the module, and none may be added: the two images
 * above are produced by private or nested helpers inside the components that own the records they
 * appear on. The batch image belongs to the batch tier, where the step template already renders it
 * from its injected clock; the online image belongs to the online services that stamp records on
 * behalf of a screen, principally transaction posting and interest calculation on the batch side and
 * the transaction-add and bill-payment services on the online side. Hoisting either into a shared
 * utility would put the two shapes one careless import apart from each other, which is precisely the
 * substitution the previous section warns about. The layouts are documented here, in one place, so
 * that every owner has a single authoritative description to implement against.
 *
 * <h2>Why the clock is fixed to UTC</h2>
 * {@link Clock#systemUTC()} and never {@link Clock#systemDefaultZone()}. The zone of this bean is
 * part of the output contract, not a deployment detail: the persistence layer is configured for UTC
 * in {@code application.yml}, so a clock in any other zone would make a written timestamp disagree
 * with the value read back, and would make the same job emit different bytes on two hosts whose
 * regional settings differ. Byte-equivalent output has to be reproducible from the input alone,
 * which it cannot be if the answer depends on where the process happens to run. A component that
 * genuinely needs a civil-time view derives it from this clock rather than substituting another one.
 *
 * <h2>Why direct calls to the platform clock are prohibited</h2>
 * Across this module, obtaining the current moment by any means other than this bean is not
 * permitted. That rules out the no-argument current-instant and current-local-date-time factories,
 * the legacy mutable date type's default constructor, and the millisecond counter on
 * {@code java.lang.System}. Each of them reaches the host clock directly, and a value read that way
 * cannot be pinned, which makes every timestamp assertion in the test estate either impossible or
 * flaky. Every one has a clock-accepting counterpart, so the constraint costs nothing: inject this
 * bean and pass it. A test then supplies a fixed clock in its own context and asserts the emitted
 * image exactly.
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
 * <p>Should a future, separately reviewed migration introduce audit columns, the correct change is to
 * enable auditing here and route its date-time provider through <em>this same</em> clock, so that
 * audit timestamps and business timestamps can never come from two different sources.</p>
 *
 * <h2>What this class deliberately does not configure</h2>
 * Repository scanning, entity scanning, the entity manager factory, the data source, the vendor
 * adapter and the transaction manager are all left to auto-configuration, which already discovers
 * this module's persistence and repository packages from the application entry point. Declaring any
 * of them here would replace an auto-configured bean with a hand-rolled one and is a common cause of
 * duplicate-definition and ambiguous-bean start-up failures. Nothing already settled in
 * {@code application.yml} is restated in Java either &mdash; the schema-check mode, the entity
 * manager's request-scope binding, the provider time zone and the deliberate absence of a pinned
 * vendor dialect all stay in configuration, where a reader can see them in one place. Optimistic
 * locking is a property of the two entities that declare a version, not of this class. No
 * second-level or query cache is registered: the legacy system had no caching layer, and
 * introducing one would alter the very consistency behaviour this migration is measured against.
 *
 * <h2>Thread safety</h2>
 * Stateless and immutable. The class is final, holds no field, carries no static mutable state and
 * needs no collaborator. The clock it publishes is itself immutable and safe for unsynchronised
 * concurrent use, so the one instance is shared by every injection point.
 */
@Configuration(proxyBeanMethods = false)
public final class JpaAuditConfig {

    /**
     * Creates the configuration singleton.
     *
     * <p>This class sits at the base of the dependency graph and has no collaborators, so constructor
     * injection contributes no parameters. The constructor is declared explicitly rather than left
     * implicit so that the absence of collaborators is a visible, reviewable property of the class: a
     * time source that itself depended on another bean could not be the first thing every other bean
     * is built against.</p>
     */
    public JpaAuditConfig() {
        // No collaborators to inject: the published clock is created from a platform factory and this
        // class holds no state of its own.
    }

    /**
     * The one time source for the whole module, fixed to UTC.
     *
     * <p>Every component that needs the current date or time constructor-injects this clock and
     * derives its value from it, rather than reaching the host clock directly. That is what lets a
     * test pin the moment and assert an emitted timestamp image character for character, including
     * the constant tail of the batch layout described in the class documentation.</p>
     *
     * <p>The zone is UTC because it is part of the output contract: it matches the time zone the
     * persistence layer is configured with, so a bounded twenty-six-character timestamp column reads
     * back exactly as it was written, and the same job emits the same bytes regardless of the
     * regional settings of the host it runs on. The platform default zone is never used here, as
     * that would make output depend on where the process happens to run.</p>
     *
     * <p>A test replaces this bean with a fixed clock; nothing else about the wiring changes,
     * because every consumer depends on the abstraction rather than on this particular
     * implementation.</p>
     *
     * @return an immutable UTC clock, never {@code null}
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
