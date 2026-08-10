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
package com.carddemo.batch.step;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.batch.BackupTransactionJobConfig;
import com.carddemo.support.AbstractPostgresIT;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Proves against a real PostgreSQL server that two concurrent publications of one generation base are
 * genuinely serialized, which is the property no mock can demonstrate.
 *
 * <h2>Why this test exists in this shape</h2>
 *
 * <p>The reviewed defect was a read-decide-delete retention pass performed without mutual exclusion, and
 * the concrete way it bit was two <em>differently named</em> jobs publishing to one shared base: the
 * transaction-backup base is written both by the backup job's archive step and by the transaction-report
 * job's unload step. The launch coordinator serializes launches per job name, so it is precisely that pair
 * it leaves concurrent. A test that drove one publisher could never observe the failure, so each test here
 * drives two, from two threads, over two real database sessions.
 *
 * <p>The publications are represented by bodies that record their own entry and exit rather than by real
 * uploads. That is deliberate: what is under test is whether one body can begin before the other has
 * finished, and an object store would add its own concurrency to the observation without changing the
 * question. The store's own obligation - to name every base it touches and to upload only inside the lock
 * - is asserted in {@code StagedGenerationStoreTest}.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("advisory publication lock: two concurrent publishers of one base are serialized")
class AdvisoryGenerationPublicationLockIT extends AbstractPostgresIT {

    /** The base both the backup job and the report job's unload step publish to. */
    private static final String SHARED_BASE = BackupTransactionJobConfig.ARCHIVE_DATASET_BASE;

    /** A second, unrelated base used to show that exclusion is per base and not global. */
    private static final String UNRELATED_BASE = "AWS.M2.CARDDEMO.TRANREPT";

    /** How long a waiting publication is given before the test calls it stuck. */
    private static final Duration PATIENCE = Duration.ofSeconds(20);

    /**
     * How many times the two opposite-order callers contend for the same pair. Enough passes that they
     * are repeatedly mid-acquisition together, few enough that the whole test stays well inside
     * {@link #PATIENCE}.
     */
    private static final int OVERLAPPING_PASSES = 40;

    @Test
    @DisplayName("a second publication of the same base cannot begin until the first has finished")
    void oneBaseAdmitsOnePublicationAtATime() throws Exception {
        final GenerationPublicationLock lock = realLock();
        final ConcurrencyWitness witness = new ConcurrencyWitness();
        final CountDownLatch firstHasEntered = new CountDownLatch(1);
        final CountDownLatch firstMayLeave = new CountDownLatch(1);

        final ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            final Future<?> first = threads.submit(() -> lock.whileHolding(List.of(SHARED_BASE), () -> {
                witness.enter();
                firstHasEntered.countDown();
                awaitQuietly(firstMayLeave);
                witness.leave();
            }));
            assertThat(firstHasEntered.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();

            // The second publisher now asks for a base the first is holding. It must not enter.
            final Future<?> second = threads.submit(() -> lock.whileHolding(List.of(SHARED_BASE), () -> {
                witness.enter();
                witness.leave();
            }));

            // THE SECOND PUBLISHER IS OBSERVED WAITING ON THE SERVER, not merely given time to get it
            // wrong. A sleep followed by "still unfinished" is satisfied by a thread the scheduler has not
            // run, so it would pass on a loaded machine with the lock deleted - which is the whole defect
            // this class exists to detect. PostgreSQL publishes the wait itself: a session blocked on an
            // advisory lock holds an entry in pg_locks that is not granted, so the query below observes
            // the contender at the exclusion rather than inferring it from elapsed time.
            awaitAContenderBlockedOnAnAdvisoryLock();
            assertThat(witness.peakConcurrency()).as("both publications were inside the base at once")
                    .isEqualTo(1);
            assertThat(second.isDone()).as("the second publication entered while the base was held")
                    .isFalse();

            firstMayLeave.countDown();
            first.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
            second.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);

            assertThat(witness.completed()).isEqualTo(2);
            assertThat(witness.peakConcurrency())
                    .as("the base was never held by two publications at once")
                    .isEqualTo(1);
        } finally {
            firstMayLeave.countDown();
            shutDown(threads);
        }
    }

    @Test
    @DisplayName("without the lock the same two publications DO overlap, which is the defect")
    void theWitnessDetectsAnUnserializedPair() throws Exception {
        // The negative control, kept as a test so the harness above cannot quietly stop detecting
        // anything. This runs the identical two-publisher scenario through a lock that only runs the
        // body, and asserts the outcome the real lock prevents: both publications inside the base at
        // once, each about to run its own list-sort-delete against a set the other is still changing.
        // Latches force the overlap rather than hoping for it, so the observation is deterministic.
        final GenerationPublicationLock unserialized = (bases, publication) -> publication.run();
        final ConcurrencyWitness witness = new ConcurrencyWitness();
        final CountDownLatch bothInside = new CountDownLatch(2);
        final ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            final List<Future<?>> both = new ArrayList<>();
            for (int publisher = 0; publisher < 2; publisher++) {
                both.add(threads.submit(() -> unserialized.whileHolding(List.of(SHARED_BASE), () -> {
                    witness.enter();
                    bothInside.countDown();
                    awaitQuietly(bothInside);
                    witness.leave();
                })));
            }
            for (final Future<?> publication : both) {
                publication.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
            }

            assertThat(witness.peakConcurrency())
                    .as("an unserialized pair must be observed overlapping, or the witness proves nothing")
                    .isEqualTo(2);
        } finally {
            shutDown(threads);
        }
    }

    @Test
    @DisplayName("two publications of different bases do not wait for each other")
    void differentBasesArePublishedConcurrently() throws Exception {
        final GenerationPublicationLock lock = realLock();
        final CountDownLatch bothInside = new CountDownLatch(2);
        final ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            final Future<?> shared = threads.submit(() -> lock.whileHolding(List.of(SHARED_BASE), () -> {
                bothInside.countDown();
                awaitQuietly(bothInside);
            }));
            final Future<?> unrelated =
                    threads.submit(() -> lock.whileHolding(List.of(UNRELATED_BASE), () -> {
                        bothInside.countDown();
                        awaitQuietly(bothInside);
                    }));

            // Neither can finish until both are inside, so completing at all proves they overlapped.
            // Exclusion is a property of the base, not a queue in front of the object store.
            shared.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
            unrelated.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
            assertThat(bothInside.getCount()).isZero();
        } finally {
            shutDown(threads);
        }
    }

    @Test
    @DisplayName("an overlapping pair requested in opposite orders completes rather than deadlocking")
    void anOverlappingPairCannotDeadlock() throws Exception {
        final GenerationPublicationLock lock = realLock();
        final Set<String> finished = new ConcurrentSkipListSet<>();
        final ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            // One caller names the pair one way round and the other names it the other way round, many
            // times over, so the two are repeatedly mid-acquisition together. The lock sorts before
            // acquiring, so both take the pair in the same order and no cycle can form. Acquisition in
            // the caller's order would let one hold the base the other needed next, and this loop would
            // hang rather than finish - which is why the assertion is that it finishes at all.
            final Future<?> forwards = threads.submit(() -> {
                for (int pass = 0; pass < OVERLAPPING_PASSES; pass++) {
                    lock.whileHolding(List.of(SHARED_BASE, UNRELATED_BASE),
                            () -> finished.add("forwards"));
                }
            });
            final Future<?> backwards = threads.submit(() -> {
                for (int pass = 0; pass < OVERLAPPING_PASSES; pass++) {
                    lock.whileHolding(List.of(UNRELATED_BASE, SHARED_BASE),
                            () -> finished.add("backwards"));
                }
            });

            forwards.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
            backwards.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
            assertThat(finished).containsExactlyInAnyOrder("forwards", "backwards");
        } finally {
            shutDown(threads);
        }
    }

    @Test
    @DisplayName("a failed publication releases the base, so the next publication is not blocked")
    void aFailedPublicationDoesNotStrandTheBase() throws Exception {
        final GenerationPublicationLock lock = realLock();
        final List<String> outcomes = new ArrayList<>();

        try {
            lock.whileHolding(List.of(SHARED_BASE), () -> {
                throw new IllegalStateException("the artifact could not be uploaded");
            });
        } catch (final IllegalStateException expected) {
            outcomes.add("first failed");
        }

        // Acquired from this thread again, which can only succeed if the rolled-back transaction released
        // the advisory lock. A stranded lock would leave the base unpublishable for the process lifetime.
        lock.whileHolding(List.of(SHARED_BASE), () -> outcomes.add("second published"));

        assertThat(outcomes).containsExactly("first failed", "second published");
    }

    /**
     * Builds the production lock over a real connection source against the migrated server.
     *
     * <p>A driver-managed source rather than a pool, because each publication needs its own session for
     * the exclusion to be observable at all, and a pool of one would serialize the test rather than the
     * lock.</p>
     *
     * @return the production lock, addressing the shared PostgreSQL server
     */
    private static GenerationPublicationLock realLock() {
        final DriverManagerDataSource dataSource = new DriverManagerDataSource(
                jdbcUrl(), databaseUser(), databasePassword());
        dataSource.setDriverClassName(driverClassName());
        return new AdvisoryGenerationPublicationLock(new JdbcTemplate(dataSource));
    }

    /**
     * Waits until the server reports a session blocked on an advisory lock, and fails if none appears.
     *
     * <p><strong>Why this replaces a sleep.</strong> The property under test is that a second publisher
     * cannot enter a held base. Establishing it needs two facts: the contender reached the exclusion, and
     * it did not get through. A sleep establishes only the second, and only in the weak sense that nothing
     * has happened yet - which is equally true of a thread that has not been scheduled. This method
     * establishes the first from the server's own bookkeeping. {@code pg_advisory_xact_lock} registers a
     * row in {@code pg_locks} for the waiting session with {@code granted} false, so the condition is a
     * state the contender is in rather than an interval the test hopes was long enough.
     *
     * <p>The condition is monotone until the holder releases, so polling for it is deterministic in
     * outcome: what varies between machines is how quickly it is seen, never whether it becomes true. A
     * host so loaded that the contender never reaches the server fails here, with a message saying exactly
     * that, instead of passing as though exclusion had been observed.
     *
     * @throws InterruptedException if the wait is interrupted
     */
    private static void awaitAContenderBlockedOnAnAdvisoryLock() throws InterruptedException {
        final JdbcTemplate observer = observerTemplate();
        final long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (blockedAdvisoryWaiters(observer) == 0) {
            assertThat(System.nanoTime() < deadline)
                    .as("no session reached the advisory lock within %d seconds, so nothing about "
                            + "exclusion can be concluded from what follows", PATIENCE.toSeconds())
                    .isTrue();
            TimeUnit.MILLISECONDS.sleep(20L);
        }
    }

    /**
     * Counts the sessions currently waiting for an advisory lock on this server.
     *
     * @param  observer a template over a connection of its own, so the reading is the server's and not a
     *                  participant's
     * @return how many advisory-lock requests are outstanding
     */
    private static int blockedAdvisoryWaiters(final JdbcTemplate observer) {
        final Integer waiting = observer.queryForObject(
                "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted",
                Integer.class);
        return waiting == null ? 0 : waiting.intValue();
    }

    /**
     * Builds a template on a connection of its own, used only to read the server's lock table.
     *
     * @return the observer template
     */
    private static JdbcTemplate observerTemplate() {
        final DriverManagerDataSource dataSource = new DriverManagerDataSource(
                jdbcUrl(), databaseUser(), databasePassword());
        dataSource.setDriverClassName(driverClassName());
        return new JdbcTemplate(dataSource);
    }

    /** Waits without letting an interruption be mistaken for the latch having opened. */
    private static void awaitQuietly(final CountDownLatch latch) {
        try {
            if (!latch.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("a publication waited longer than the test's patience");
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("a publication was interrupted while waiting", interrupted);
        }
    }

    /** Stops the test's threads without leaving one running past the test that created it. */
    private static void shutDown(final ExecutorService threads) throws InterruptedException {
        threads.shutdownNow();
        assertThat(threads.awaitTermination(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();
    }

    /** Counts how many publications are inside a base at once, and how deep that ever got. */
    private static final class ConcurrencyWitness {

        private final AtomicInteger inside = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private final AtomicInteger completed = new AtomicInteger();

        private void enter() {
            this.peak.accumulateAndGet(this.inside.incrementAndGet(), Math::max);
        }

        private void leave() {
            this.inside.decrementAndGet();
            this.completed.incrementAndGet();
        }

        private int peakConcurrency() {
            return this.peak.get();
        }

        private int completed() {
            return this.completed.get();
        }
    }
}
