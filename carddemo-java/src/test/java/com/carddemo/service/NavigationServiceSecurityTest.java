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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.api.dto.NavigationContext;
import com.carddemo.exception.AbendException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Guards {@link NavigationService} against log forging through a client-echoed program nomination.
 *
 * <p>The navigation context is round-tripped through the client. Its originating- and
 * destination-program fields are handed out in a response and returned by the caller unaltered, so
 * whatever comes back in them is attacker-chosen text that this service then resolves - and, when the
 * resolution fails, writes to a log record before abending. The recorded exploit is a nomination of
 * {@code A\r\nFORGED}, which ends the log record early and presents the remainder as a second entry that
 * the service never emitted.
 *
 * <p><strong>These tests assert against records actually emitted, not against an outcome.</strong>
 * A log-forging defect is invisible to any assertion made on what a method does: an unresolvable
 * nomination raises {@link AbendException}, reproducing the legacy transfer's own failure, and the
 * record is written on the way out. Asserting only that it raised would say nothing at all about the
 * record. The suite therefore attaches
 * a Logback recorder to the service's own logger, following the harness {@code AbendServiceTest}
 * established, and inspects the formatted records themselves. Asserting on the formatted message rather
 * than the format string is the whole point: the format string never contained the hostile text, and the
 * substitution is where the disclosure would occur.
 *
 * <p>Two properties are pinned together. No record may carry a control character or any fragment of the
 * caller's text; and the record must still identify the defect well enough to act on, because a
 * diagnostic emptied of all content would pass a forging test while making a routine misconfiguration
 * undiagnosable.
 */
@DisplayName("NavigationService :: a client-echoed nomination cannot forge a log record")
class NavigationServiceSecurityTest {

    /** A line feed: ends a log record, so text after it reads as a separate entry. */
    private static final char LINE_FEED = '\n';

    /** A carriage return: returns the cursor so the rest of the record overwrites it. */
    private static final char CARRIAGE_RETURN = '\r';

    /** A tab: a control character that is not a line terminator. */
    private static final char TAB = '\t';

    /** An escape: can reposition a terminal cursor and overwrite records already written. */
    private static final char ESCAPE = 0x1B;

    /** A delete: the one control code above the printable range rather than below it. */
    private static final char DELETE = 0x7F;

    /**
     * A NUL is deliberately absent from this set.
     *
     * <p>The service treats a field of spaces and NULs as blank in the legacy sense, reproducing the
     * {@code SPACES OR LOW-VALUES} test, so a nomination made only of NULs takes the empty-nomination
     * branch and never reaches the branch under test. A NUL <em>embedded</em> in otherwise printable
     * text does reach it, and is covered by its own test below.
     */
    private static final char[] CONTROL_CHARACTERS =
            {LINE_FEED, CARRIAGE_RETURN, TAB, ESCAPE, DELETE};

    /** Text a forged record would carry, so a leak is visible rather than inferred. */
    private static final String FORGED = "FORGEDAUDITENTRY";

    /** A legitimate legacy program name, and one the destination table actually knows. */
    private static final String KNOWN_PROGRAM = "COSGN00C";

    private NavigationService navigationService;
    private Logger logger;
    private ListAppender<ILoggingEvent> recorder;
    private Level originalLevel;

    @BeforeEach
    void attachRecorder() {
        this.navigationService = new NavigationService();
        this.logger = (Logger) LoggerFactory.getLogger(NavigationService.class);
        this.originalLevel = this.logger.getLevel();
        // Set the level explicitly rather than inheriting whatever the surrounding configuration
        // happens to provide, so this suite asserts the service's rendering and not the ambient setup.
        // DEBUG is required because the honoured-nomination record is emitted at that level.
        this.logger.setLevel(Level.DEBUG);
        this.recorder = new ListAppender<>();
        this.recorder.setContext(this.logger.getLoggerContext());
        this.recorder.start();
        this.logger.addAppender(this.recorder);
    }

    @AfterEach
    void detachRecorder() {
        this.logger.detachAppender(this.recorder);
        this.recorder.stop();
        this.logger.setLevel(this.originalLevel);
    }

    /**
     * Builds a context whose originating-program field carries the supplied nomination.
     *
     * @param fromProgram the nomination to place in the originating-program field
     * @return a context carrying that nomination and nothing else of interest
     */
    private static NavigationContext contextFrom(final String fromProgram) {
        return new NavigationContext("CC00", fromProgram, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * Builds a context whose destination-program field carries the supplied nomination.
     *
     * @param toProgram the nomination to place in the destination-program field
     * @return a context carrying that nomination and nothing else of interest
     */
    private static NavigationContext contextTo(final String toProgram) {
        return new NavigationContext("CC00", null, "CM00", toProgram, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    /** Asserts that every recorded message is a single safe line that does not echo the payload. */
    private void assertNoRecordWasForged() {
        assertThat(this.recorder.list)
                .as("the failure must still be recorded, or the diagnostic was lost entirely")
                .isNotEmpty();
        for (final ILoggingEvent event : this.recorder.list) {
            final String message = event.getFormattedMessage();
            assertThat(message).as("a record must carry a message").isNotNull();
            for (int index = 0; index < message.length(); index++) {
                assertThat(Character.isISOControl(message.charAt(index)))
                        .as("record must hold no control character, but position %d holds code point"
                                + " %d; record was [%s]", index,
                                (int) message.charAt(index), message)
                        .isFalse();
            }
            assertThat(message)
                    .as("a record must not echo the hostile payload")
                    .doesNotContain(FORGED);
        }
    }

    @Nested
    @DisplayName("back navigation, which resolves the originating-program field")
    class BackNavigation {

        @Test
        @DisplayName("records no control character for any of them, and echoes none of the caller's"
                + " text")
        void recordsNoControlCharacterForAnyOfThem() {
            for (final char control : CONTROL_CHARACTERS) {
                recorder.list.clear();
                final String hostile = "A" + control + FORGED;

                assertThatExceptionOfType(AbendException.class)
                        .as("an unresolvable nomination reproduces the legacy transfer's abend")
                        .isThrownBy(() -> navigationService.resolveBackNavigation(
                                contextFrom(hostile), NavigationService.Route.SIGN_ON));

                assertNoRecordWasForged();
            }
        }

        @Test
        @DisplayName("refuses the recorded exploit and leaves exactly one log record, not two")
        void refusesTheRecordedExploitAndLeavesExactlyOneRecord() {
            // The forging test in its strongest form. A record count of one is the direct assertion
            // that the log was not split: before the fix the single emitted record contained an
            // embedded terminator, so a reader of the log file saw two entries where the service
            // emitted one. Counting emitted events proves the service's side; scanning the formatted
            // text proves the reader's side. Both are needed, because the event count alone would
            // still pass if the one event carried a terminator.
            assertThatExceptionOfType(AbendException.class).isThrownBy(
                    () -> navigationService.resolveBackNavigation(
                            contextFrom("A" + CARRIAGE_RETURN + LINE_FEED + FORGED),
                            NavigationService.Route.SIGN_ON));

            assertThat(recorder.list).hasSize(1);
            final String message = recorder.list.getFirst().getFormattedMessage();
            assertThat(message)
                    .doesNotContain("\r")
                    .doesNotContain("\n")
                    .doesNotContain(FORGED);
            assertThat(message.lines().count())
                    .as("a forged record would present as more than one line: [%s]", message)
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("names the offending position and code point, so an invisible character is still"
                + " correctable")
        void namesTheOffendingPositionAndCodePoint() {
            assertThatExceptionOfType(AbendException.class).isThrownBy(
                    () -> navigationService.resolveBackNavigation(
                            contextFrom("AB" + LINE_FEED + FORGED),
                            NavigationService.Route.SIGN_ON));

            assertThat(recorder.list).hasSize(1);
            assertThat(recorder.list.getFirst().getFormattedMessage())
                    .contains("not printable US-ASCII")
                    .contains("zero-based position 2")
                    .contains("code point " + (int) LINE_FEED);
        }

        @Test
        @DisplayName("refuses an embedded NUL, which the blank test would otherwise have absorbed")
        void refusesAnEmbeddedNul() {
            // A field of nothing but NULs is blank in the legacy sense and takes a different branch.
            // A NUL surrounded by printable text is not blank, so it reaches the branch under test and
            // has to be described there.
            assertThatExceptionOfType(AbendException.class).isThrownBy(
                    () -> navigationService.resolveBackNavigation(
                            contextFrom("A\0" + FORGED), NavigationService.Route.SIGN_ON));

            assertThat(recorder.list).hasSize(1);
            assertThat(recorder.list.getFirst().getFormattedMessage())
                    .contains("code point 0")
                    .doesNotContain(FORGED);
        }

        @Test
        @DisplayName("publishes no caller text even when the nomination is entirely printable, because"
                + " an eight-character value is long enough to imitate a log field")
        void publishesNoCallerTextEvenWhenTheNominationIsEntirelyPrintable() {
            // The subtler half of the defect. A printable-only filter would let this through, and
            // "route=CA" fits inside the legacy eight-character program-name field - so a reader
            // parsing space-separated key=value pairs could be shown a route the service never chose.
            // Nothing the caller supplied is published, which closes that as well as the split-record
            // attack.
            assertThatExceptionOfType(AbendException.class).isThrownBy(
                    () -> navigationService.resolveBackNavigation(
                            contextFrom("route=CA"), NavigationService.Route.SIGN_ON));

            assertThat(recorder.list).hasSize(1);
            final String message = recorder.list.getFirst().getFormattedMessage();
            assertThat(message)
                    .as("no fragment of the caller's text may appear")
                    .doesNotContain("route=CA");
            assertThat(message)
                    .as("the record must still be actionable")
                    .contains("unrecognised")
                    .contains("length 8");
        }
    }

    @Nested
    @DisplayName("forward navigation, which resolves the destination-program field")
    class ForwardNavigation {

        @Test
        @DisplayName("applies the same guard, so the second nominating field is not left unprotected"
                + " by the first one's fix")
        void appliesTheSameGuard() {
            for (final char control : CONTROL_CHARACTERS) {
                recorder.list.clear();

                assertThatExceptionOfType(AbendException.class).isThrownBy(
                        () -> navigationService.resolveNominatedDestination(
                                contextTo("A" + control + FORGED),
                                NavigationService.Route.USER_MENU));

                assertNoRecordWasForged();
            }
        }
    }

    @Nested
    @DisplayName("a nomination that does resolve")
    class ANominationThatDoesResolve {

        @Test
        @DisplayName("records the canonical program name from the destination table rather than the"
                + " text the caller sent")
        void recordsTheCanonicalProgramNameRatherThanTheCallersText() {
            // Reaching the honoured branch means the nomination matched a table entry, so a canonical
            // form exists and is the better field to publish: it is a value the service owns. The
            // assertion is meaningful rather than cosmetic because the lookup tolerates fixed-width
            // padding, so the caller's text and the canonical name need not be identical.
            final NavigationService.Route resolved = navigationService.resolveBackNavigation(
                    contextFrom(KNOWN_PROGRAM), NavigationService.Route.USER_MENU);

            assertThat(resolved).isEqualTo(NavigationService.Route.SIGN_ON);
            assertThat(recorder.list).hasSize(1);
            assertThat(recorder.list.getFirst().getFormattedMessage())
                    .contains("nominatedProgram=" + KNOWN_PROGRAM)
                    .contains("route=" + NavigationService.Route.SIGN_ON.getRouteValue());
        }

        @Test
        @DisplayName("records the canonical name even when the caller padded it, proving the published"
                + " value comes from the table and not from the request")
        void recordsTheCanonicalNameEvenWhenTheCallerPaddedIt() {
            final NavigationService.Route resolved = navigationService.resolveBackNavigation(
                    contextFrom(KNOWN_PROGRAM + " "), NavigationService.Route.USER_MENU);

            assertThat(resolved)
                    .as("fixed-width padding must still resolve, as the legacy field is space padded")
                    .isEqualTo(NavigationService.Route.SIGN_ON);
            assertThat(recorder.list).hasSize(1);
            assertThat(recorder.list.getFirst().getFormattedMessage())
                    .as("the canonical name carries no trailing pad")
                    .contains("nominatedProgram=" + KNOWN_PROGRAM + " route=");
        }
    }

    @Nested
    @DisplayName("an empty nomination")
    class AnEmptyNomination {

        @Test
        @DisplayName("takes the blank branch and publishes no nomination field at all")
        void takesTheBlankBranchAndPublishesNoNominationField() {
            final NavigationService.Route resolved = navigationService.resolveBackNavigation(
                    contextFrom("   "), NavigationService.Route.USER_MENU);

            assertThat(resolved).isEqualTo(NavigationService.Route.USER_MENU);
            assertThat(recorder.list).hasSize(1);
            assertThat(recorder.list.getFirst().getFormattedMessage())
                    .as("the blank branch has no value worth naming")
                    .doesNotContain("nominatedProgram=");
        }
    }

    @Nested
    @DisplayName("the administrative-scope report is a description, not a control")
    class TheAdministrativeScopeReport {

        @Test
        @DisplayName("reports five administrative destinations, and each route value is a logical label"
                + " rather than a URL - which is why membership is not an access decision")
        void reportsFiveAdministrativeDestinationsAsLogicalLabels() {
            // This pins the fact behind the reworded documentation. The security configuration gates a
            // URL prefix; these values are labels such as "admin-menu" and carry no path at all, so
            // nothing connects the two automatically. Asserting it here keeps the class comment's
            // future-obligation statement honest: if a later change turned these values into paths
            // under the gated prefix, this test would fail and the comment would need revisiting.
            assertThat(navigationService.adminScopedRoutes()).hasSize(5);
            for (final NavigationService.Route route : navigationService.adminScopedRoutes()) {
                assertThat(route.isAdminScoped()).isTrue();
                assertThat(route.getRouteValue())
                        .as("a route value is a logical label, not a URL")
                        .doesNotStartWith("/")
                        .doesNotContain("/api");
            }
        }
    }
}
