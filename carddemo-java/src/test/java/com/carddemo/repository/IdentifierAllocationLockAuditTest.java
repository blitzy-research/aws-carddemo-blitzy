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

package com.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Audits the production tree so that the transaction-identifier allocation contract cannot be broken by a
 * new caller, which is the one way this defect can come back.
 *
 * <h2>What the contract is, and why a per-service test cannot protect it</h2>
 *
 * <p>{@link TransactionRepository#findMaxId()} is the read half of the legacy identifier rule - the
 * highest stored key, incremented by the caller - and reading it in order to mint obliges the caller to
 * take {@link TransactionRepository#lockIdentifierAllocation(long)} first. Under {@code READ COMMITTED} an
 * uncommitted insert is invisible, so two allocators that share nothing but a transaction can both observe
 * the same maximum, derive the same successor and collide on the primary key: the loser is refused for a
 * reason that has nothing to do with its own work.
 *
 * <p>Each minting service tests its own locking, but no such test can see a <em>third</em> service that
 * arrives later, reads the maximum and forgets the lock - and that third service reopens the window for
 * every existing one, not only for itself. Serialisation is a property of the set of participants, so it
 * has to be asserted over the set. That is what this class does, and it is deliberately textual: it reads
 * the production sources rather than a context, because the failure it guards against is a call that was
 * never written.
 *
 * <p>Provenance: the rule is that of {@code app/cbl/COBIL00C.cbl} lines 212 to 219 and
 * {@code app/cbl/COTRN02C.cbl} lines 444 to 451, read as read-only reference at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.
 */
@DisplayName("Identifier allocation: every minting caller takes the lock, and only enrolled callers mint")
final class IdentifierAllocationLockAuditTest {

    /** The production source tree, relative to the module directory the build runs in. */
    private static final Path PRODUCTION_SOURCE_ROOT = Path.of("src", "main", "java");

    /**
     * A floor on how many production sources the walk must find, so that a walk started in the wrong
     * directory fails here instead of making every absence assertion below vacuously true.
     */
    private static final int MINIMUM_PRODUCTION_SOURCES = 100;

    /**
     * The two services licensed to mint a transaction identifier from the stored maximum, and the
     * statement each of them reaches the read through.
     *
     * <p>{@code BillPaymentService} translates the online bill-payment program, which browses backward to
     * the highest key; {@code TransactionAddService} translates the transaction-add program, which does
     * the same through a descending single-row page. Both take the allocation lock before that read and
     * both carry the bounded re-read the repository's contract obliges.
     *
     * <p><strong>The enrolled value is the statement that reaches the read from inside the method that
     * takes the lock, not the read statement itself.</strong> Both services perform the read from a
     * paragraph method of their own, and paragraph methods are laid out in the legacy source's paragraph
     * order, so the read's own text can appear earlier in the file than the lock while still executing
     * after it. Naming the call site inside the locking method is what makes a textual ordering assertion
     * mean what it says. Changing the shape of either service's allocation span therefore requires
     * changing the value here to the new call site, and leaving it stale makes this audit fail rather
     * than pass silently.
     *
     * <p><strong>Enrolling a third file here is a concurrency decision and must be recorded in
     * {@code docs/decision-log.md}</strong>, because a further minting caller changes the serialisation
     * guarantee of the two already here. Two other production files write to the transaction master and are
     * deliberately absent: the interest-calculation service composes its identifier from the parameter date
     * and a per-run counter, and the consolidation job carries identifiers that already exist - neither
     * reads the maximum, so neither has anything to serialise.
     */
    private static final Map<String, String> ENROLLED_MINTING_SOURCES = Map.of(
            "BillPaymentService.java", "mintTransactionRecord(state);",
            "TransactionAddService.java",
            "final Transaction pending = allocateTransactionRecord(state)");

    /** The call that takes the transaction-scoped allocation lock. */
    private static final String LOCK_CALL = "transactionRepository.lockIdentifierAllocation(";

    /** The constant every participant must lock on, spelled as the call sites spell it. */
    private static final String LOCK_KEY_REFERENCE =
            "TransactionRepository.IDENTIFIER_ALLOCATION_LOCK_KEY";

    /** The read half of the identifier rule, which is what obliges the lock. */
    private static final String MAXIMUM_READ_CALL = "transactionRepository.findMaxId()";

    /** The declared bound on re-allocation, which is the retry the repository's contract obliges. */
    private static final String RETRY_BOUND_DECLARATION = "IDENTIFIER_ALLOCATION_ATTEMPTS = 2";

    /** The explicit flush that keeps the insert inside the locked window and inside a local catch. */
    private static final String INSERT_FLUSH_CALL = "transactionRepository.insertAndFlush(";

    /** Creates the test class. */
    IdentifierAllocationLockAuditTest() {
    }

    @Test
    @DisplayName("the search strings this audit relies on really occur, so a typo in one of them cannot "
            + "turn every assertion below into a silent pass")
    void theSearchStringsAreCorrect() {
        final List<Map.Entry<Path, String>> sources = productionSources();

        assertThat(sourcesContaining(sources, LOCK_CALL))
                .as("the lock call must be found somewhere, or the audit is matching nothing")
                .isNotEmpty();
        assertThat(sourcesContaining(sources, LOCK_KEY_REFERENCE)).isNotEmpty();
        assertThat(sourcesContaining(sources, MAXIMUM_READ_CALL)).isNotEmpty();
        assertThat(sourcesContaining(sources, RETRY_BOUND_DECLARATION))
                .as("both enrolled services declare the bound, so two files must match")
                .hasSize(ENROLLED_MINTING_SOURCES.size());
        assertThat(sourcesContaining(sources, INSERT_FLUSH_CALL)).isNotEmpty();
        ENROLLED_MINTING_SOURCES.forEach((fileName, readCall) ->
                assertThat(textOf(sources, fileName))
                        .as("the statement this audit expects %s to reach its read through must be "
                                        + "present verbatim", fileName)
                        .contains(readCall));
    }

    @Test
    @DisplayName("every production file that reads the stored maximum is an enrolled minting service, so a "
            + "new reader fails here rather than at the first collision in production")
    void onlyEnrolledSourcesReadTheStoredMaximum() {
        assertThat(sourcesContaining(productionSources(), MAXIMUM_READ_CALL))
                .as("reading the maximum is what obliges the lock; a file that reads it without being "
                        + "enrolled has not been reviewed for serialisation")
                .isSubsetOf(ENROLLED_MINTING_SOURCES.keySet())
                .as("the bill-payment service is the caller that reads it through this method, so an "
                        + "empty result would mean the audit stopped matching anything")
                .contains("BillPaymentService.java");
    }

    @Test
    @DisplayName("every production file that takes the allocation lock is an enrolled minting service, and "
            + "every enrolled service takes it")
    void theLockIsTakenExactlyByTheEnrolledSources() {
        assertThat(sourcesContaining(productionSources(), LOCK_CALL))
                .containsExactlyInAnyOrderElementsOf(ENROLLED_MINTING_SOURCES.keySet());
    }

    @Test
    @DisplayName("each enrolled service takes the lock BEFORE its allocation read, because a lock taken "
            + "after the read serialises nothing at all")
    void theLockPrecedesTheAllocationReadInEverySource() {
        final List<Map.Entry<Path, String>> sources = productionSources();

        ENROLLED_MINTING_SOURCES.forEach((fileName, readCall) -> {
            final String text = textOf(sources, fileName);
            final int lockAt = text.indexOf(LOCK_CALL);
            final int readAt = text.indexOf(readCall);

            assertThat(lockAt).as("%s must take the lock", fileName).isNotNegative();
            assertThat(readAt)
                    .as("%s must reach its allocation read from inside the locking method", fileName)
                    .isNotNegative();
            assertThat(lockAt)
                    .as("in %s the lock must be taken before the maximum is read", fileName)
                    .isLessThan(readAt);
        });
    }

    @Test
    @DisplayName("each enrolled service locks on the repository's own constant, because a lock on any "
            + "other key serialises against nobody")
    void everySourceLocksOnTheSharedConstant() {
        final List<Map.Entry<Path, String>> sources = productionSources();

        ENROLLED_MINTING_SOURCES.keySet().forEach(fileName ->
                assertThat(textOf(sources, fileName))
                        .as("%s must name the shared lock key rather than a literal of its own", fileName)
                        .contains(LOCK_KEY_REFERENCE));
    }

    @Test
    @DisplayName("each enrolled service carries the bounded re-read and flushes its insert, which are the "
            + "two remaining obligations the repository's contract records")
    void everySourceCarriesTheBoundedRetryAndFlushesTheInsert() {
        final List<Map.Entry<Path, String>> sources = productionSources();

        ENROLLED_MINTING_SOURCES.keySet().forEach(fileName -> {
            final String text = textOf(sources, fileName);
            assertThat(text)
                    .as("%s must declare the bound on re-allocation, so the retry cannot become "
                            + "unbounded", fileName)
                    .contains(RETRY_BOUND_DECLARATION);
            assertThat(text)
                    .as("%s must flush its insert, so the row reaches the server while the lock is held "
                            + "and a refusal is classified by its own arms", fileName)
                    .contains(INSERT_FLUSH_CALL);
        });
    }

    /**
     * Returns the file names of every production source containing the supplied text.
     *
     * @param sources the production sources
     * @param needle  the text to look for, matched verbatim
     * @return the simple file names, in no particular order
     */
    private static List<String> sourcesContaining(final List<Map.Entry<Path, String>> sources,
            final String needle) {
        return sources.stream()
                .filter(entry -> entry.getValue().contains(needle))
                .map(entry -> entry.getKey().getFileName().toString())
                .toList();
    }

    /**
     * Returns the text of one production source, named by its simple file name.
     *
     * @param sources  the production sources
     * @param fileName the simple file name
     * @return the file's text
     */
    private static String textOf(final List<Map.Entry<Path, String>> sources, final String fileName) {
        return sources.stream()
                .filter(entry -> entry.getKey().getFileName().toString().equals(fileName))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the enrolled source " + fileName + " was not found under "
                                + PRODUCTION_SOURCE_ROOT));
    }

    /**
     * Reads every production source as a path-and-text pair.
     *
     * @return one entry per production {@code .java} file
     */
    private static List<Map.Entry<Path, String>> productionSources() {
        assertThat(PRODUCTION_SOURCE_ROOT)
                .as("the production source root must be readable from the test working directory, or "
                        + "every absence assertion here would be vacuous")
                .isDirectory();

        final List<Map.Entry<Path, String>> sources = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(PRODUCTION_SOURCE_ROOT)) {
            for (final Path path : tree.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                sources.add(Map.entry(path, readText(path)));
            }
        } catch (final IOException problem) {
            throw new UncheckedIOException("unable to walk " + PRODUCTION_SOURCE_ROOT, problem);
        }

        assertThat(sources.size())
                .as("far too few production sources were read for the assertions to mean anything; the "
                        + "walk must have started in the wrong directory")
                .isGreaterThanOrEqualTo(MINIMUM_PRODUCTION_SOURCES);
        return sources;
    }

    /**
     * Reads one source file as text.
     *
     * @param path the file to read
     * @return the file's content
     */
    private static String readText(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException problem) {
            throw new UncheckedIOException("unable to read " + path, problem);
        }
    }
}
