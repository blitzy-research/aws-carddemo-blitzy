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
package com.carddemo.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A staging root that belongs to exactly one class in exactly one run, and to nothing else.
 *
 * <h2>The defect this exists to remove</h2>
 * A batch job configuration resolves its staging area from a property, and an integration test of such a
 * configuration has to bind that property <em>before</em> the Spring context starts - which rules out
 * {@code @TempDir}, because a parameter-injected temporary directory does not exist until a test method
 * is entered. The obvious way out is a fixed expression such as
 * {@code ${java.io.tmpdir}/carddemo-<something>}, and every batch integration test in this module took
 * it.
 *
 * <p>That expression names a <strong>host-global</strong> location. This module is built in parallel
 * clones on one host, so two clones running the same class at the same time resolve the same directory:
 * one clone's run stages a generation the other clone's run then lists, captures and compares against a
 * golden. The failure that follows belongs to neither run, reproduces on neither in isolation, and is
 * indistinguishable from a real parity defect. Worse, a run that <em>passed</em> may have passed on a
 * neighbour's artefact.
 *
 * <p>The same expression is also unsafe within one clone across time: a crashed or interrupted run leaves
 * its generations behind, and a later run of the same class that lists "the newest generation" can find
 * one it did not produce.
 *
 * <h2>What this provides instead</h2>
 * {@link #createFor(Class)} creates a directory whose name is unique on the host, and does so in a static
 * initialiser's worth of work - a single filesystem call - so a caller can hold it in a
 * {@code static final} field and register it from a {@code @DynamicPropertySource} method before the
 * context is built. Uniqueness comes from {@link Files#createTempDirectory(Path, String, java.nio.file.attribute.FileAttribute...)}
 * itself, which is atomic against a concurrent creator; the clone token in the prefix is there so a human
 * looking at {@code /tmp} can tell whose directory is whose, and is not what makes the name unique.
 *
 * <p>{@link #deleteRecursively(Path)} removes it again. Deletion is bounded to the directory the caller
 * was given and is depth-first, so it cannot reach a sibling clone's root, the platform temporary
 * directory itself, or anything above either.
 *
 * <h2>Why this is not simply {@code @TempDir}</h2>
 * JUnit's {@code @TempDir} is the right tool whenever the directory can be created after the test
 * instance exists. It is the wrong tool here for one specific reason: the property that points a job
 * configuration at its staging area is resolved while the application context is refreshed, and a
 * context is refreshed before any test method - and, under context caching, possibly during a different
 * class's execution. A directory that does not yet exist at that moment cannot be the value bound. This
 * type closes exactly that gap and nothing else.
 */
public final class RunScopedStagingRoot {

    /** Environment variable naming the parallel clone, used only to make a directory name readable. */
    private static final String CLONE_INDEX_VARIABLE = "CLONE_INDEX";

    /** Token used in place of a clone index when the variable is absent, as it is on a developer host. */
    private static final String UNIDENTIFIED_CLONE = "local";

    /** Prefix every root created here carries, so the module's roots are recognisable in one listing. */
    private static final String PREFIX = "carddemo-";

    /** Separator between the prefix, the owner, the clone token and the unique suffix. */
    private static final String SEPARATOR = "-";

    /** Characters admitted into the clone token; anything else is replaced. */
    private static final String ADMITTED_TOKEN_CHARACTERS = "abcdefghijklmnopqrstuvwxyz0123456789";

    /** Replacement for a character the token may not carry. */
    private static final char TOKEN_REPLACEMENT = '_';

    /** Longest clone token carried into a directory name, so an unbounded value cannot bloat the path. */
    private static final int MAXIMUM_TOKEN_LENGTH = 16;

    /** Utility holder; never instantiated. */
    private RunScopedStagingRoot() {
        throw new AssertionError("RunScopedStagingRoot is a factory and is never instantiated");
    }

    /**
     * Creates a staging root that belongs to one owning class in one run.
     *
     * <p>The directory exists when this returns, is empty, and is named uniquely on the host. It is the
     * caller's to delete; see {@link #deleteRecursively(Path)}.
     *
     * @param  owner the class the root belongs to, named in the directory so a stray root is traceable
     * @return the created root
     * @throws UncheckedIOException if the platform temporary directory will not accept a new directory
     */
    public static Path createFor(final Class<?> owner) {
        Objects.requireNonNull(owner, "owner must not be null");
        final String prefix = PREFIX + sanitised(owner.getSimpleName()) + SEPARATOR
                + sanitised(cloneToken()) + SEPARATOR;
        try {
            return Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), prefix);
        } catch (final IOException refused) {
            throw new UncheckedIOException("no run-scoped staging root could be created for "
                    + owner.getName() + " beneath the platform temporary directory", refused);
        }
    }

    /**
     * Deletes one root and everything beneath it, depth-first.
     *
     * <p>Silent when the root is already absent, because a teardown runs whatever the outcome of the
     * tests above it and a run that never created its root must not fail in its own cleanup.
     *
     * @param  root the root to delete; nothing outside it is touched
     * @throws UncheckedIOException if an entry beneath it cannot be removed
     */
    public static void deleteRecursively(final Path root) {
        Objects.requireNonNull(root, "root must not be null");
        if (!Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path directory, final IOException failure)
                        throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException failure) {
            throw new UncheckedIOException("the run-scoped staging root at " + root
                    + " could not be removed", failure);
        }
    }

    /**
     * Empties one root without removing it, so a context that already bound the path keeps a valid one.
     *
     * <p>Used between phases of a single class. Ordered deepest-first so a directory is removed only
     * after its contents are.
     *
     * @param  root the root to empty; nothing outside it is touched
     * @throws UncheckedIOException if an entry beneath it cannot be removed
     */
    public static void deleteContents(final Path root) {
        Objects.requireNonNull(root, "root must not be null");
        if (!Files.isDirectory(root)) {
            return;
        }
        try {
            final List<Path> entries;
            try (Stream<Path> walked = Files.walk(root)) {
                entries = walked.filter(path -> !path.equals(root))
                        .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                        .toList();
            }
            for (final Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        } catch (final IOException failure) {
            throw new UncheckedIOException("the run-scoped staging root at " + root
                    + " could not be emptied", failure);
        }
    }

    /**
     * Reads the clone token from the environment, or reports that there is none.
     *
     * @return the clone token
     */
    private static String cloneToken() {
        final String configured = System.getenv(CLONE_INDEX_VARIABLE);
        return configured == null || configured.isBlank() ? UNIDENTIFIED_CLONE : configured.trim();
    }

    /**
     * Reduces one name to characters a directory name may carry, bounded in length.
     *
     * <p>Folded with an explicit root locale, because the ambient default decides nothing anywhere in
     * this module and a Turkish default would otherwise change the folding of a capital I.
     *
     * @param  value the value to reduce
     * @return the reduced value
     */
    private static String sanitised(final String value) {
        final String folded = value.toLowerCase(Locale.ROOT);
        final int length = Math.min(folded.length(), MAXIMUM_TOKEN_LENGTH);
        final StringBuilder reduced = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            final char character = folded.charAt(index);
            reduced.append(ADMITTED_TOKEN_CHARACTERS.indexOf(character) < 0
                    ? TOKEN_REPLACEMENT : character);
        }
        return reduced.toString();
    }
}
