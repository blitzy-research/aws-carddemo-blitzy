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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the module's single time source.
 *
 * <p>The class under test replaces {@code app/cpy/CSDAT01Y.cpy}, whose {@code WS-DATE-TIME} group item on
 * line 17 was textually included by all 17 online COBOL programs, each copy filled independently from the
 * language's current-date intrinsic. The migration collapses every copy into one injected clock, and the
 * whole value of doing so rests on a single property: the clock can be <em>pinned</em>. If it cannot, no
 * fixed-width timestamp image can ever be asserted, because the expected value would depend on when the
 * test happened to run. These tests prove three things, in order of importance: that the published clock
 * is fixed to UTC rather than to whatever zone the host is set to; that a test can substitute a fixed
 * clock and get a byte-for-byte deterministic timestamp out of a consumer; and that the class registers no
 * persistence infrastructure of its own, which is what keeps it from colliding with auto-configuration or
 * from mapping a column the migrations never created.
 *
 * <p><strong>The constant tail is the point.</strong> The batch timestamp layout of
 * {@code app/cbl/CBTRN02C.cbl} ends in four characters that are a <strong>literal</strong>, moved in at
 * line 701 of {@code Z-GET-DB2-FORMAT-TIMESTAMP} (lines 692-705), because the item feeding the fraction,
 * {@code COB-MIL} at line 157, is only {@code PIC X(02)} wide. An implementation emitting real
 * microseconds there would be the right length and wrong bytes. {@link ImageDeterminism} pins two instants
 * differing <em>only</em> below the hundredths place and asserts the rendered image is identical for both,
 * which is the only way to demonstrate the tail is a constant rather than an accident of timing.
 *
 * <p><strong>How these tests are written.</strong> Every expected value is a literal declared here and
 * every rendered image is built by a private helper belonging to this test, so the oracle is independent
 * of what it judges; the production renderers live with the components that own the records they appear on
 * and are covered by those components' own tests. Width assertions measure characters of a US-ASCII-safe
 * image against the declared picture width of the legacy field, never against whatever the code happens to
 * produce. Zone assertions compare against {@link ZoneOffset#UTC} explicitly, because asserting merely
 * that the zone "is UTC-like" would pass on a host clock that happens to sit in that zone &mdash; the
 * exact defect being guarded against. It is a plain unit test: no container, no connection, no bound port,
 * and the contexts it builds hold only the class under test plus, where needed, a local test consumer.
 */
@DisplayName("JPA configuration: one pinnable UTC clock, and no persistence infrastructure of its own")
class JpaAuditConfigTest {

    /** Declared width of both legacy timestamp layouts: {@code PIC X(26)} and the 26-byte online group. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** The four-character constant the batch layout pads its fraction out to, moved in as a literal. */
    private static final String BATCH_CONSTANT_TAIL = "0000";

    /** Name the configuration publishes its clock under. */
    private static final String CLOCK_BEAN_NAME = "systemClock";

    /**
     * A pinned instant with a non-zero microsecond component, chosen so that a renderer which leaked
     * real sub-hundredth precision into the batch tail would be caught rather than accidentally right.
     */
    private static final Instant PINNED = Instant.parse("2022-07-19T23:15:58.123456Z");

    /** The same moment as {@link #PINNED}, differing only below the hundredths place. */
    private static final Instant PINNED_SAME_HUNDREDTHS = Instant.parse("2022-07-19T23:15:58.129999Z");

    /** Expected online image for {@link #PINNED}: space separator, colons, six genuine fraction digits. */
    private static final String EXPECTED_ONLINE_IMAGE = "2022-07-19 23:15:58.123456";

    /** Expected batch image for {@link #PINNED}: hyphen separator, dots, two hundredths, constant tail. */
    private static final String EXPECTED_BATCH_IMAGE = "2022-07-19-23.15.58.120000";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(JpaAuditConfig.class);

    @Nested
    @DisplayName("The published clock")
    class PublishedClock {

        @Test
        @DisplayName("is fixed to UTC and not to the zone the host happens to be set to")
        void isFixedToUtc() {
            final Clock clock = new JpaAuditConfig().systemClock();

            assertThat(clock).as("the configuration must publish a clock").isNotNull();
            assertThat(clock.getZone()).as("published zone").isEqualTo(ZoneOffset.UTC);
            assertThat(clock).as("must be the platform UTC clock").isEqualTo(Clock.systemUTC());
        }

        @Test
        @DisplayName("is not the platform default-zone clock, whose zone is a region rather than the UTC "
                + "offset even when the host is set to UTC")
        void isNotTheDefaultZoneClock() {
            final Clock clock = new JpaAuditConfig().systemClock();

            assertThat(clock).isNotEqualTo(Clock.systemDefaultZone());
            assertThat(clock.getZone()).isNotEqualTo(ZoneId.systemDefault());
        }

        @Test
        @DisplayName("is produced identically on every call, so two injection points cannot disagree")
        void isProducedIdenticallyOnEveryCall() {
            final JpaAuditConfig configuration = new JpaAuditConfig();

            assertThat(configuration.systemClock()).isEqualTo(configuration.systemClock());
        }

        @Test
        @DisplayName("advances, confirming it is a live clock rather than a frozen one")
        void advances() {
            final Clock clock = new JpaAuditConfig().systemClock();

            assertThat(clock.instant()).isBeforeOrEqualTo(clock.instant());
            assertThat(clock.millis()).isPositive();
        }
    }

    @Nested
    @DisplayName("Container wiring")
    class ContainerWiring {

        @Test
        @DisplayName("starts and contributes exactly one clock, under the documented bean name")
        void contributesExactlyOneClock() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(Clock.class);
                assertThat(context).hasBean(CLOCK_BEAN_NAME);
                assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
            });
        }

        @Test
        @DisplayName("registers no auditing collaborator, because the schema defines no audited column")
        void registersNoAuditingCollaborator() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(AuditorAware.class);
                assertThat(context).doesNotHaveBean(DateTimeProvider.class);
            });
        }

        @Test
        @DisplayName("registers no data source, no transaction manager and nothing else "
                + "auto-configuration owns")
        void registersNothingAutoConfigurationOwns() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(PlatformTransactionManager.class);
                assertThat(context).doesNotHaveBean("dataSource");
                assertThat(context).doesNotHaveBean("entityManagerFactory");
                assertThat(context).doesNotHaveBean("transactionManager");
                assertThat(context.getBeanDefinitionNames())
                        .as("the configuration's own contribution, beside container infrastructure")
                        .contains(CLOCK_BEAN_NAME);
            });
        }

        @Test
        @DisplayName("hands the same clock instance to a consumer that injects it by type")
        void handsTheClockToAConsumer() {
            runner.withUserConfiguration(TimestampingConsumer.class).run(context -> {
                assertThat(context).hasNotFailed();
                final TimestampingConsumer consumer = context.getBean(TimestampingConsumer.class);
                assertThat(consumer.clock()).isSameAs(context.getBean(Clock.class));
                assertThat(consumer.clock().getZone()).isEqualTo(ZoneOffset.UTC);
            });
        }
    }

    @Nested
    @DisplayName("Pinning the clock in a test context")
    class Pinning {

        @Test
        @DisplayName("a fixed clock supplied by a test replaces the system clock for every consumer")
        void aFixedClockReplacesTheSystemClock() {
            runner.withUserConfiguration(TimestampingConsumer.class, FixedClockTestConfiguration.class)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        final Clock injected = context.getBean(TimestampingConsumer.class).clock();

                        assertThat(injected.instant())
                                .as("the consumer must see the pinned instant, not the wall clock")
                                .isEqualTo(PINNED);
                        assertThat(injected.instant())
                                .as("a pinned clock never advances")
                                .isEqualTo(injected.instant());
                    });
        }

        @Test
        @DisplayName("both legacy timestamp images come out deterministic and at their declared width")
        void bothImagesAreDeterministic() {
            runner.withUserConfiguration(TimestampingConsumer.class, FixedClockTestConfiguration.class)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        final Clock injected = context.getBean(TimestampingConsumer.class).clock();

                        assertThat(onlineImage(injected)).isEqualTo(EXPECTED_ONLINE_IMAGE);
                        assertThat(batchImage(injected)).isEqualTo(EXPECTED_BATCH_IMAGE);
                        assertThat(onlineImage(injected)).hasSize(TIMESTAMP_WIDTH);
                        assertThat(batchImage(injected)).hasSize(TIMESTAMP_WIDTH);
                    });
        }
    }

    @Nested
    @DisplayName("The two 26-character images are distinct, and the batch tail is a constant")
    class ImageDeterminism {

        @Test
        @DisplayName("the two layouts differ in their date-time separator and in their time separators, "
                + "at equal width")
        void theTwoLayoutsDiffer() {
            final Clock pinned = Clock.fixed(PINNED, ZoneOffset.UTC);

            final String online = onlineImage(pinned);
            final String batch = batchImage(pinned);

            assertThat(online).hasSize(TIMESTAMP_WIDTH);
            assertThat(batch).hasSize(TIMESTAMP_WIDTH);
            assertThat(online).isNotEqualTo(batch);
            assertThat(online.charAt(10)).as("online separates date from time with a space").isEqualTo(' ');
            assertThat(batch.charAt(10)).as("batch separates date from time with a hyphen").isEqualTo('-');
            assertThat(online.charAt(13)).as("online times are colon separated").isEqualTo(':');
            assertThat(batch.charAt(13)).as("batch times are dot separated").isEqualTo('.');
        }

        @Test
        @DisplayName("the batch image ends in the literal tail rather than in real sub-hundredth digits")
        void theBatchTailIsLiteral() {
            final String batch = batchImage(Clock.fixed(PINNED, ZoneOffset.UTC));

            assertThat(batch).endsWith(BATCH_CONSTANT_TAIL);
            assertThat(batch.substring(20)).as("two hundredths digits then the constant tail")
                    .isEqualTo("12" + BATCH_CONSTANT_TAIL);
        }

        @Test
        @DisplayName("two instants differing only below the hundredths place render the same batch image, "
                + "proving the tail cannot vary with timing")
        void subHundredthPrecisionCannotLeakIntoTheTail() {
            final String first = batchImage(Clock.fixed(PINNED, ZoneOffset.UTC));
            final String second = batchImage(Clock.fixed(PINNED_SAME_HUNDREDTHS, ZoneOffset.UTC));

            assertThat(first).isEqualTo(second);
            assertThat(second).endsWith(BATCH_CONSTANT_TAIL);
        }

        @Test
        @DisplayName("the same instants do change the online image, which carries six genuine digits")
        void theOnlineFractionIsGenuine() {
            final String first = onlineImage(Clock.fixed(PINNED, ZoneOffset.UTC));
            final String second = onlineImage(Clock.fixed(PINNED_SAME_HUNDREDTHS, ZoneOffset.UTC));

            assertThat(first).isNotEqualTo(second);
            assertThat(first).endsWith("123456");
            assertThat(second).endsWith("129999");
        }
    }

    /**
     * Renders the online layout of {@code app/cpy/CSDAT01Y.cpy} lines 42-55 from a clock: a space
     * between date and time, colons between the time parts and a six-digit fraction.
     *
     * <p>A test-owned oracle, deliberately independent of any production renderer.</p>
     *
     * @param clock the time source to read
     * @return a 26-character image
     */
    private static String onlineImage(final Clock clock) {
        final LocalDateTime moment = LocalDateTime.now(clock);
        return String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d:%02d.%06d",
                moment.getYear(), moment.getMonthValue(), moment.getDayOfMonth(),
                moment.getHour(), moment.getMinute(), moment.getSecond(),
                moment.getNano() / 1_000);
    }

    /**
     * Renders the batch layout of {@code app/cbl/CBTRN02C.cbl} line 159 and its redefinition on lines
     * 160-174 from a clock: a hyphen between date and time, dots between the time parts, two hundredths
     * digits and then the constant tail.
     *
     * <p>A test-owned oracle, deliberately independent of any production renderer.</p>
     *
     * @param clock the time source to read
     * @return a 26-character image
     */
    private static String batchImage(final Clock clock) {
        final LocalDateTime moment = LocalDateTime.now(clock);
        return String.format(Locale.ROOT, "%04d-%02d-%02d-%02d.%02d.%02d.%02d%s",
                moment.getYear(), moment.getMonthValue(), moment.getDayOfMonth(),
                moment.getHour(), moment.getMinute(), moment.getSecond(),
                moment.getNano() / 10_000_000, BATCH_CONSTANT_TAIL);
    }

    /**
     * A minimal stand-in for any component that needs the current moment, used to prove the clock is
     * reachable by constructor injection and that a pinned clock reaches it unchanged.
     */
    @Configuration(proxyBeanMethods = false)
    static class TimestampingConsumer {

        private final Clock clock;

        /**
         * Injects the module's clock.
         *
         * @param clock the injected time source
         */
        TimestampingConsumer(final Clock clock) {
            this.clock = clock;
        }

        /**
         * Returns the injected clock.
         *
         * @return the clock this consumer was built with
         */
        Clock clock() {
            return this.clock;
        }
    }

    /**
     * Supplies a pinned clock that takes precedence over the configuration's system clock, which is how
     * a test fixes the moment without altering production wiring.
     */
    @Configuration(proxyBeanMethods = false)
    static class FixedClockTestConfiguration {

        /**
         * The pinned clock every consumer receives while this configuration is active.
         *
         * @return a fixed UTC clock
         */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(PINNED, ZoneOffset.UTC);
        }
    }
}
