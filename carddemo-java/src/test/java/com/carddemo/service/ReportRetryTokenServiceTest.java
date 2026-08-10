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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.exception.ValidationException;
import com.carddemo.util.ReportRetryTokens;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit test for the boundary that decides whether a presented report retry token may still be honoured.
 *
 * <h2>What is under test</h2>
 *
 * <p>The report request's promise - repeat the token and an interrupted submission is completed rather than
 * doubled - rests entirely on the queue service collapsing a repeated deduplication identifier, and the
 * queue service forgets an identifier after {@link ReportRetryTokens#VALIDITY}. This class is what keeps
 * the promise and the mechanism the same size: a token is minted here with the instant it was minted, and a
 * presented one is honoured only while that instant is inside the window and only when this deployment
 * minted it.
 *
 * <p>Four properties, one nest each. A token this service minted round-trips. A token that has aged out is
 * refused, counted and recorded. A token this deployment never issued is refused the same way but counted
 * separately, because the two are operationally different. And the refusal reveals nothing the caller sent,
 * because a refused token is caller-supplied text and a value shaped like a credential is the last thing
 * that belongs in a log line.
 *
 * <h2>Harness</h2>
 *
 * <p>A surefire unit test: no context, no container, no network. The clock is fixed, so the window is
 * deterministic and a case that needs time to have passed builds a second service over a moved clock rather
 * than sleeping. The registry is a real {@link SimpleMeterRegistry} rather than a mock, so a counter is read
 * back the way an operator's scrape would read it.
 */
@DisplayName("ReportRetryTokenService - the enforced retry window behind the Idempotency-Key header")
class ReportRetryTokenServiceTest {

    /** Deployment signing material the key is derived from. */
    private static final String SECRET = "report-retry-token-unit-test-secret-0123456789abcdef";

    /** A second deployment's material. */
    private static final String OTHER_SECRET = "another-deployments-secret-fedcba98765432100";

    /** When every token in this class is minted. */
    private static final Instant NOW = Instant.parse("2022-07-19T14:11:12Z");

    /** The pinned clock. */
    private static final Clock PINNED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Where refusals are counted. */
    private MeterRegistry meterRegistry;

    /** The service under test. */
    private ReportRetryTokenService subject;

    /** The service's own logger, so a recorded refusal can be read. */
    private Logger serviceLogger;

    /** The level the logger had before this test raised it. */
    private Level previousLevel;

    /** Captured events. */
    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        subject = new ReportRetryTokenService(SECRET, PINNED_CLOCK, meterRegistry);
        serviceLogger = (Logger) LoggerFactory.getLogger(ReportRetryTokenService.class);
        previousLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.TRACE);
        logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger.addAppender(logCapture);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logCapture);
        serviceLogger.setLevel(previousLevel);
        logCapture.stop();
    }

    @Nested
    @DisplayName("The constructor")
    class TheConstructor {

        @Test
        @DisplayName("refuses an absent clock and an absent registry, because one decides the window and "
                + "the other is how a deployment sees the window being enforced")
        void refusesAnAbsentCollaborator() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportRetryTokenService(SECRET, null, meterRegistry));
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReportRetryTokenService(SECRET, PINNED_CLOCK, null));
        }

        @Test
        @DisplayName("accepts an absent signing secret and says so, because a context that configured none "
                + "has no deployment-wide authority for a token to carry")
        void acceptsAnAbsentSecretAndSaysSo() {
            final ReportRetryTokenService unconfigured =
                    new ReportRetryTokenService("  ", PINNED_CLOCK, new SimpleMeterRegistry());

            assertThat(unconfigured.accept(unconfigured.mint()))
                    .as("a token minted under process-scoped material is still honoured in that process")
                    .isNotBlank();
            assertThat(warnings())
                    .as("silence here would hide that a token will not survive a restart or reach a peer")
                    .anySatisfy(message -> assertThat(message)
                            .contains("carddemo.security.jwt.secret")
                            .contains("only as long as this process"));
        }
    }

    @Nested
    @DisplayName("A token this service minted, inside the window")
    class AMintedTokenIsHonoured {

        @Test
        @DisplayName("is returned unchanged, so the submission identity it feeds is the identity the first "
                + "attempt used")
        void isReturnedUnchanged() {
            final String minted = subject.mint();

            assertThat(subject.accept(minted)).isEqualTo(minted);
        }

        @Test
        @DisplayName("is distinct per submission, so two requests in the same second are two submissions")
        void isDistinctPerSubmission() {
            assertThat(subject.mint()).isNotEqualTo(subject.mint());
        }

        @Test
        @DisplayName("is honoured at the far edge of the window, so the boundary second is not refused "
                + "while the queue would still collapse it")
        void isHonouredAtTheEdgeOfTheWindow() {
            final String minted = subject.mint();
            final ReportRetryTokenService atTheEdge = serviceAt(NOW.plus(ReportRetryTokens.VALIDITY));

            assertThat(atTheEdge.accept(minted)).isEqualTo(minted);
        }

        @Test
        @DisplayName("is honoured by a second instance of the same deployment, so a retry may land on "
                + "another replica")
        void isHonouredByAnotherInstance() {
            final String minted = subject.mint();
            final ReportRetryTokenService peer =
                    new ReportRetryTokenService(SECRET, PINNED_CLOCK, new SimpleMeterRegistry());

            assertThat(peer.accept(minted)).isEqualTo(minted);
        }

        @Test
        @DisplayName("counts no refusal and records nothing, so the quiet path stays quiet")
        void countsNoRefusal() {
            subject.accept(subject.mint());

            assertThat(meterRegistry.find(ReportRetryTokenService.METRIC_RETRY_TOKEN_REFUSED).counters())
                    .isEmpty();
            assertThat(warnings()).isEmpty();
        }

        @Test
        @DisplayName("refuses a null rather than treating it as a new submission, because an absent token "
                + "is minted by the caller of this method and never reaches it")
        void refusesANull() {
            assertThatNullPointerException().isThrownBy(() -> subject.accept(null));
        }
    }

    @Nested
    @DisplayName("A token older than the window")
    class AnExpiredTokenIsRefused {

        @Test
        @DisplayName("is refused one second past the window, which is where the queue stops collapsing it")
        void isRefusedOneSecondPastTheWindow() {
            final String minted = subject.mint();
            final ReportRetryTokenService later =
                    serviceAt(NOW.plus(ReportRetryTokens.VALIDITY).plusSeconds(1L));

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> later.accept(minted))
                    .withMessage(ReportRetryTokenService.REFUSAL_MESSAGE);
        }

        @Test
        @DisplayName("is counted under its own reason, because a client retrying too late and a client "
                + "sending something never issued are different operational events")
        void isCountedUnderItsOwnReason() {
            final String minted = subject.mint();
            final MeterRegistry laterMeters = new SimpleMeterRegistry();
            final ReportRetryTokenService later = new ReportRetryTokenService(SECRET,
                    Clock.fixed(NOW.plus(ReportRetryTokens.VALIDITY).plusSeconds(1L), ZoneOffset.UTC),
                    laterMeters);

            assertThatExceptionOfType(ValidationException.class).isThrownBy(() -> later.accept(minted));

            assertThat(laterMeters.get(ReportRetryTokenService.METRIC_RETRY_TOKEN_REFUSED)
                            .tag(ReportRetryTokenService.TAG_REASON,
                                    ReportRetryTokenService.REASON_EXPIRED)
                            .counter().count())
                    .isEqualTo(1.0d);
            assertThat(laterMeters.find(ReportRetryTokenService.METRIC_RETRY_TOKEN_REFUSED)
                            .tag(ReportRetryTokenService.TAG_REASON,
                                    ReportRetryTokenService.REASON_NOT_ISSUED_HERE)
                            .counter())
                    .as("the two reasons are not folded together")
                    .isNull();
        }

        @Test
        @DisplayName("carries the enforced window in the message, so a caller can tell how stale its token "
                + "was without being told what it sent")
        void carriesTheWindowInTheMessage() {
            final String minted = subject.mint();
            final ReportRetryTokenService later =
                    serviceAt(NOW.plus(ReportRetryTokens.VALIDITY).plusSeconds(1L));

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> later.accept(minted))
                    .withMessageContaining("Idempotency-Key")
                    .withMessageContaining(String.valueOf(ReportRetryTokens.VALIDITY_MINUTES))
                    .withMessageContaining("omit the header");
        }
    }

    @Nested
    @DisplayName("A token this deployment never issued")
    class AForeignTokenIsRefused {

        @Test
        @DisplayName("is refused rather than ignored, because ignoring it would publish a new submission "
                + "the caller believed was a retry")
        void isRefusedRatherThanIgnored() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> subject.accept("report-request-retry-001"))
                    .withMessage(ReportRetryTokenService.REFUSAL_MESSAGE);
            assertThat(meterRegistry.get(ReportRetryTokenService.METRIC_RETRY_TOKEN_REFUSED)
                            .tag(ReportRetryTokenService.TAG_REASON,
                                    ReportRetryTokenService.REASON_NOT_ISSUED_HERE)
                            .counter().count())
                    .isEqualTo(1.0d);
        }

        @Test
        @DisplayName("includes a token another deployment minted, so a token does not travel between "
                + "deployments")
        void includesAnotherDeploymentsToken() {
            final String foreign =
                    new ReportRetryTokenService(OTHER_SECRET, PINNED_CLOCK, new SimpleMeterRegistry())
                            .mint();

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> subject.accept(foreign));
        }

        @Test
        @DisplayName("includes a token of this deployment whose instant has been moved at all, even to one "
                + "still inside the window, because it is the authentication and not the arithmetic that "
                + "refuses it")
        void includesAReDatedToken() {
            // Moved BACKWARD by one second, deliberately. That instant is comfortably inside the window, so
            // the age check would admit it and the only thing that can refuse it is the authentication code
            // over the instant. Were the code not checked, a caller could move the instant forward instead
            // and hold the window open for as long as it liked - which is the whole reason the code exists,
            // and this is the case that proves it is consulted.
            final String minted = subject.mint();
            final String[] fields = minted.split("\\.");
            final String stillInsideTheWindow = new ReportRetryTokenService(SECRET,
                    Clock.fixed(NOW.minusSeconds(1L), ZoneOffset.UTC), new SimpleMeterRegistry())
                    .mint().split("\\.")[1];
            final String forged =
                    fields[0] + "." + stillInsideTheWindow + "." + fields[2] + "." + fields[3];

            assertThatExceptionOfType(ValidationException.class)
                    .as("the instant is authenticated, so moving it invalidates the token that carried it")
                    .isThrownBy(() -> subject.accept(forged));
            assertThat(meterRegistry.get(ReportRetryTokenService.METRIC_RETRY_TOKEN_REFUSED)
                            .tag(ReportRetryTokenService.TAG_REASON,
                                    ReportRetryTokenService.REASON_NOT_ISSUED_HERE)
                            .counter().count())
                    .as("a tampered token is not a stale one; it was never issued in that form")
                    .isEqualTo(1.0d);
        }

        @Test
        @DisplayName("is recorded without any part of the value presented, because a refused token is "
                + "caller-supplied text")
        void isRecordedWithoutTheValuePresented() {
            final String presented = "a-token-carrying-something-private-0451";

            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> subject.accept(presented))
                    .withMessageNotContaining(presented);

            assertThat(warnings())
                    .hasSize(1)
                    .allSatisfy(message -> assertThat(message)
                            .doesNotContain(presented)
                            .contains("retry-token-" + ReportRetryTokenService.REASON_NOT_ISSUED_HERE));
        }

        @Test
        @DisplayName("is refused for every unusable shape, so no near-miss is admitted on a technicality")
        void isRefusedForEveryUnusableShape() {
            final String minted = subject.mint();
            final List<String> unusable = List.of(
                    "",
                    "   ",
                    "crt2" + minted.substring(ReportRetryTokens.SCHEME.length()),
                    minted + ".extra",
                    minted.substring(0, minted.lastIndexOf('.')),
                    minted.substring(0, minted.lastIndexOf('.') + 4));

            for (final String candidate : unusable) {
                assertThatExceptionOfType(ValidationException.class)
                        .as("refused: %s", candidate)
                        .isThrownBy(() -> subject.accept(candidate));
            }
        }
    }

    /**
     * Builds a second service reading the same key at a moved instant.
     *
     * @param  instant when the second service believes it is
     * @return a service that will honour this one's tokens only while the window allows
     */
    private ReportRetryTokenService serviceAt(final Instant instant) {
        return new ReportRetryTokenService(SECRET, Clock.fixed(instant, ZoneOffset.UTC),
                new SimpleMeterRegistry());
    }

    /**
     * The formatted warning messages the service recorded.
     *
     * @return every captured warning, in order
     */
    private List<String> warnings() {
        return logCapture.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
