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

import com.carddemo.util.SecureStagedFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * One staging directory per specification per execution, private to that execution and owner-only.
 *
 * <h2>What this replaces, and why the arrangement it replaces was a defect rather than untidiness</h2>
 *
 * <p>Five batch specifications each named a fixed directory beneath the platform temporary directory -
 * {@code ${java.io.tmpdir}/carddemo-<something>-it} - and each swept that directory clean in a lifecycle
 * callback. Three properties of that arrangement compound into a real problem.
 *
 * <p><strong>The directory outlives the run while the framework's execution identifiers restart.</strong>
 * A staged generation is named from a batch execution identifier, and those begin again from a low number
 * in a fresh metadata schema. A generation left behind by an earlier run therefore reappears as though it
 * belonged to a later execution, and the resolver that picks the highest generation of a base picks it.
 * One of the specifications says so in its own comment; naming the hazard is not the same as closing it.
 *
 * <p><strong>The directory is shared between specifications and between clones.</strong> Two of the five
 * fall back to the same shared configuration key. This repository is also worked on by several clones in
 * parallel on one host, every one of them resolving the same absolute path from the same system property.
 * Two executions staging a generation of the same logical base at the same time write to the same filename.
 *
 * <p><strong>The cleanup is a blanket sweep.</strong> Each callback deletes every regular file under the
 * root, which is every file <em>anything</em> put there, including a file a concurrently running sibling is
 * still composing. A sweep that removes another execution's input produces a failure in a specification
 * that has nothing wrong with it, and the failure is attributed to the wrong subject.
 *
 * <h2>What this provides instead</h2>
 *
 * <p>A namespace unique to the operating-system process, which is the unit a Maven run's tests share and
 * the unit that never overlaps with a sibling clone: the clone index when the environment publishes one,
 * the process identifier, and a random component so that two processes reusing an identifier after a
 * restart still differ. Beneath that namespace each specification takes a directory of its own from its own
 * label, so two specifications in one process are as separate as two processes are.
 *
 * <p>The directory is created through {@link SecureStagedFiles#prepareDirectory(Path)} - the production
 * helper the module itself stages with - rather than through a second implementation here. That matters for
 * a specific reason: the module refuses to believe a staged artefact whose root grants write permission
 * outside its owner, so a test root created any other way would have its own job's output refused, and the
 * refusal would look like a defect in the job. Using the same helper makes the test root satisfy the same
 * predicate by construction.
 *
 * <h2>What a caller must still do</h2>
 *
 * <p>Resolve the root <em>before</em> the application context is created, which means from a
 * {@code @DynamicPropertySource} method rather than from a {@code @TestPropertySource} literal: a staging
 * directory is bound while the context starts, so a value produced later arrives too late. Then discard
 * only its own root, through {@link #discard(Path)}, which removes a tree this execution owns and touches
 * nothing else.
 */
public final class IsolatedStagingRoot {

    /** Prefix every namespace this class creates carries, so an orphan is identifiable by name. */
    private static final String NAMESPACE_PREFIX = "carddemo-it-";

    /** Environment variable the platform publishes a clone index in, when several clones share a host. */
    private static final String CLONE_INDEX_VARIABLE = "CLONE_INDEX";

    /** Stands in for the clone index where the environment publishes none, so the token is total. */
    private static final String CLONE_INDEX_ABSENT = "solo";

    /** Source of the random component; one instance, because a namespace is minted once per process. */
    private static final SecureRandom ENTROPY = new SecureRandom();

    /**
     * The namespace directory for this process, resolved once.
     *
     * <p>Held as a constant rather than recomputed because every specification in one process must land in
     * the <em>same</em> namespace: a per-call value would give one specification two roots across its own
     * lifecycle callbacks, and its cleanup would then miss what its set-up created.
     */
    private static final Path PROCESS_NAMESPACE =
            Path.of(System.getProperty("java.io.tmpdir")).resolve(NAMESPACE_PREFIX + namespaceToken());

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private IsolatedStagingRoot() {
        throw new AssertionError("IsolatedStagingRoot is a utility holder and must not be instantiated");
    }

    /**
     * The staging directory one specification owns for the duration of this process, created owner-only.
     *
     * <p>Idempotent: the same label yields the same path, and preparing a directory that already exists
     * leaves it as it is. That is what lets a set-up callback and a clean-up callback name one root without
     * passing it between them.
     *
     * @param  label a short name for the specification, used as the directory name; must not be
     *               {@code null} and must not be blank or contain a separator
     * @return the absolute, existing, owner-only directory
     * @throws NullPointerException if {@code label} is {@code null}
     * @throws IllegalArgumentException if {@code label} is blank or names more than one path segment
     * @throws UncheckedIOException if the directory cannot be created, because a caller resolving a root
     *                              from a property callback has nowhere to report a checked failure
     */
    public static Path forSpecification(final String label) {
        Objects.requireNonNull(label, "label must not be null");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (label.contains("/") || label.contains("\\") || label.contains("..")) {
            throw new IllegalArgumentException(
                    "label names one directory beneath the process namespace and must not carry a"
                            + " separator or a parent reference, but was " + label);
        }
        final Path root = PROCESS_NAMESPACE.resolve(label);
        try {
            SecureStagedFiles.prepareDirectory(PROCESS_NAMESPACE);
            return SecureStagedFiles.prepareDirectory(root);
        } catch (final IOException unavailable) {
            throw new UncheckedIOException(
                    "the isolated staging root " + root + " could not be prepared", unavailable);
        }
    }

    /**
     * The same directory as a configuration value.
     *
     * @param  label the specification's label
     * @return the absolute path in the form a staging-directory property is bound from
     */
    public static String pathFor(final String label) {
        return forSpecification(label).toString();
    }

    /**
     * Removes one root this process created, and everything beneath it.
     *
     * <p>Confined to a tree this process owns, so it is not a sweep of anything shared: the only paths it
     * can reach are the ones resolved from this process's own namespace. A path outside that namespace is
     * refused rather than deleted, because a cleanup that can be pointed anywhere is the defect this class
     * exists to remove rather than a convenience.
     *
     * <p>Reports nothing and raises nothing for a root that is already gone, which is the ordinary case
     * for a specification whose set-up never ran.
     *
     * @param  root the directory to remove, as {@link #forSpecification(String)} returned it
     * @throws NullPointerException if {@code root} is {@code null}
     * @throws IllegalArgumentException if {@code root} is not within this process's namespace
     * @throws UncheckedIOException if the tree cannot be removed
     */
    public static void discard(final Path root) {
        Objects.requireNonNull(root, "root must not be null");
        final Path resolved = root.toAbsolutePath().normalize();
        if (!resolved.startsWith(PROCESS_NAMESPACE)) {
            throw new IllegalArgumentException(
                    "only a root within this process's own namespace " + PROCESS_NAMESPACE
                            + " may be discarded, but was " + resolved);
        }
        if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(resolved)) {
            final List<Path> deepestFirst = tree.sorted(Comparator.reverseOrder()).toList();
            for (final Path entry : deepestFirst) {
                Files.deleteIfExists(entry);
            }
        } catch (final IOException failure) {
            throw new UncheckedIOException(
                    "the isolated staging root " + resolved + " could not be removed", failure);
        }
    }

    /**
     * The namespace this process's roots live beneath, published so a specification can assert isolation
     * rather than assume it.
     *
     * @return the namespace directory, which may not exist until a root is first requested
     */
    public static Path processNamespace() {
        return PROCESS_NAMESPACE;
    }

    /**
     * Builds the token that makes this process's namespace unique.
     *
     * @return the clone index or its stand-in, the process identifier and a random component
     */
    private static String namespaceToken() {
        final String cloneIndex = System.getenv(CLONE_INDEX_VARIABLE);
        final String clone = (cloneIndex == null || cloneIndex.isBlank())
                ? CLONE_INDEX_ABSENT
                : cloneIndex.strip();
        return String.format(Locale.ROOT, "%s-%d-%s", sanitized(clone),
                ProcessHandle.current().pid(),
                Long.toHexString(ENTROPY.nextLong() & Long.MAX_VALUE));
    }

    /**
     * Reduces an environment-supplied value to characters that are safe in a single path segment.
     *
     * <p>The clone index arrives from the environment, and a value carrying a separator would place the
     * namespace somewhere other than where this class says it is.
     *
     * @param  value the environment value
     * @return the value with every character outside the permitted set replaced
     */
    private static String sanitized(final String value) {
        final StringBuilder safe = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            final boolean permitted = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '-' || character == '_';
            safe.append(permitted ? character : '_');
        }
        return safe.toString();
    }
}
