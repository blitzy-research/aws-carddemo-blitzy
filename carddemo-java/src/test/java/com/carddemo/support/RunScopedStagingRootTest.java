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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The staging root every batch integration test binds must belong to one run and to nothing else.
 *
 * <h2>What is actually at stake</h2>
 * Six batch integration tests and the pipeline specification each bind a staging directory into their
 * Spring context before any test method runs, and each then stages a dataset there, launches a job, lists
 * "the newest generation" and compares it with a committed golden. Until this mechanism existed they all
 * bound a fixed host-global expression. Two clones of this repository building on one host - which is how
 * this module is built - therefore resolved the same directory: one clone's generation was captured and
 * compared by the other, so a run could fail for a reason belonging to neither and, worse, could
 * <em>pass</em> on a neighbour's artefact.
 *
 * <p>So the two properties asserted here are not hygiene. Uniqueness is what makes a captured generation
 * provably the capturing run's own, and bounded deletion is what stops a teardown from removing a
 * neighbour's. Both are asserted by observation - two roots compared, a populated tree removed, a sibling
 * left standing - rather than by reading the implementation back.
 *
 * <p>Every directory this specification creates is removed in its own teardown, including the ones whose
 * removal is the subject under test, so the platform temporary directory is left as it was found.
 */
@DisplayName("a staging root belongs to one run, and deletion never reaches beyond it")
class RunScopedStagingRootTest {

    /** How many roots are created at once when uniqueness is being asserted. */
    private static final int ROOTS_COMPARED = 25;

    /** Roots this specification created, removed in the teardown whatever the outcome. */
    private final List<Path> created = new ArrayList<>();

    /** Creates the specification. */
    RunScopedStagingRootTest() {
        super();
    }

    /** Removes every root this specification created, so nothing is left in the platform temporary area. */
    @AfterEach
    void removeWhatThisSpecificationCreated() {
        for (final Path root : this.created) {
            RunScopedStagingRoot.deleteRecursively(root);
        }
        this.created.clear();
    }

    /**
     * Creates a root and remembers it for the teardown.
     *
     * @return the created root
     */
    private Path newRoot() {
        final Path root = RunScopedStagingRoot.createFor(RunScopedStagingRootTest.class);
        this.created.add(root);
        return root;
    }

    @Nested
    @DisplayName("creating one")
    class Creating {

        /** Creates the nested specification. */
        Creating() {
            // Intentionally empty: this group holds no state of its own.
        }

        @Test
        @DisplayName("produces a directory that exists, is empty, and sits under the platform "
                + "temporary area rather than anywhere the build writes")
        void theRootExistsIsEmptyAndSitsUnderThePlatformTemporaryArea() throws IOException {
            final Path root = newRoot();

            assertThat(Files.isDirectory(root))
                    .as("the directory must exist when creation returns, because the property that "
                            + "points a job configuration at it is bound before any test method runs "
                            + "and a path that does not exist cannot be the value bound")
                    .isTrue();
            try (var entries = Files.list(root)) {
                assertThat(entries.toList())
                        .as("and it must be empty, or a run would begin by capturing something it did "
                                + "not produce")
                        .isEmpty();
            }
            assertThat(root.getParent())
                    .as("it belongs under the platform temporary directory, which is the one location "
                            + "a build may write outside its own tree")
                    .isEqualTo(Path.of(System.getProperty("java.io.tmpdir")));
        }

        @Test
        @DisplayName("names the owning class in the directory, so a root left behind by a crashed run "
                + "can be traced to what created it")
        void theOwnerIsNamedInTheDirectory() {
            final Path root = newRoot();

            assertThat(root.getFileName().toString())
                    .as("the leaf name must carry the module prefix and the owner, folded to the "
                            + "characters a directory name may hold")
                    .startsWith("carddemo-")
                    .contains("runscopedstagi");
        }

        @Test
        @DisplayName("produces a different directory every time, which is the property that makes a "
                + "captured generation provably the capturing run's own")
        void everyRootIsDistinct() {
            final Set<Path> roots = new LinkedHashSet<>();
            for (int index = 0; index < ROOTS_COMPARED; index++) {
                roots.add(newRoot());
            }

            assertThat(roots)
                    .as("%d creations must yield %d distinct directories. A repeated name is exactly "
                            + "the defect this type exists to remove: two runs resolving one directory "
                            + "means each can list, capture and compare the other's generation",
                            Integer.valueOf(ROOTS_COMPARED), Integer.valueOf(ROOTS_COMPARED))
                    .hasSize(ROOTS_COMPARED);
        }

        @Test
        @DisplayName("refuses a null owner rather than creating an anonymous directory")
        void aNullOwnerIsRefused() {
            assertThatThrownBy(() -> RunScopedStagingRoot.createFor(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("owner");
        }
    }

    @Nested
    @DisplayName("removing one")
    class Removing {

        /** Creates the nested specification. */
        Removing() {
            // Intentionally empty: this group holds no state of its own.
        }

        @Test
        @DisplayName("removes a populated tree whole, files and nested directories together")
        void aPopulatedTreeIsRemovedWhole() throws IOException {
            final Path root = newRoot();
            final Path nested = Files.createDirectories(root.resolve("generations").resolve("g0001"));
            Files.writeString(root.resolve("AWS.M2.CARDDEMO.DALYTRAN.PS"), "landing",
                    StandardCharsets.US_ASCII);
            Files.writeString(nested.resolve("AWS.M2.CARDDEMO.TRANREPT.G0001V00"), "generation",
                    StandardCharsets.US_ASCII);

            RunScopedStagingRoot.deleteRecursively(root);

            assertThat(Files.exists(root))
                    .as("a teardown that left the root behind would leak one directory per run into the "
                            + "platform temporary area for the lifetime of the host")
                    .isFalse();
        }

        @Test
        @DisplayName("touches nothing outside the root it is given, so one run's teardown cannot reach "
                + "another run's staged generations")
        void nothingOutsideTheRootIsTouched() throws IOException {
            final Path mine = newRoot();
            final Path neighbour = newRoot();
            final Path neighbourGeneration = neighbour.resolve("AWS.M2.CARDDEMO.TRANREPT.G0001V00");
            Files.writeString(neighbourGeneration, "a neighbour's generation",
                    StandardCharsets.US_ASCII);
            Files.writeString(mine.resolve("AWS.M2.CARDDEMO.TRANREPT.G0001V00"), "my generation",
                    StandardCharsets.US_ASCII);

            RunScopedStagingRoot.deleteRecursively(mine);

            assertThat(Files.exists(mine)).as("the root given is removed").isFalse();
            assertThat(Files.readString(neighbourGeneration, StandardCharsets.US_ASCII))
                    .as("the sibling root and its generation must be untouched, byte for byte. This is "
                            + "the assertion that distinguishes a bounded delete from a pattern-matched "
                            + "one, and a pattern-matched delete over a shared temporary directory is "
                            + "how one clone destroys another clone's run")
                    .isEqualTo("a neighbour's generation");
            assertThat(Files.isDirectory(Path.of(System.getProperty("java.io.tmpdir"))))
                    .as("and the platform temporary directory itself is never the target")
                    .isTrue();
        }

        @Test
        @DisplayName("is silent when the root is already absent, because a teardown runs whatever the "
                + "outcome of the tests above it")
        void anAbsentRootIsNotAFailure() {
            final Path root = newRoot();
            RunScopedStagingRoot.deleteRecursively(root);

            RunScopedStagingRoot.deleteRecursively(root);

            assertThat(Files.exists(root)).isFalse();
        }
    }

    @Nested
    @DisplayName("emptying one")
    class Emptying {

        /** Creates the nested specification. */
        Emptying() {
            // Intentionally empty: this group holds no state of its own.
        }

        @Test
        @DisplayName("empties the tree but keeps the root, so a context that already bound the path "
                + "still has a valid one")
        void theRootSurvivesItsContents() throws IOException {
            final Path root = newRoot();
            final Path nested = Files.createDirectories(root.resolve("generations"));
            Files.writeString(nested.resolve("AWS.M2.CARDDEMO.STATEMNT.PS.G0001V00"), "statement",
                    StandardCharsets.US_ASCII);
            Files.writeString(root.resolve("AWS.M2.CARDDEMO.DALYTRAN.PS"), "landing",
                    StandardCharsets.US_ASCII);

            RunScopedStagingRoot.deleteContents(root);

            assertThat(Files.isDirectory(root))
                    .as("the bound path must remain valid: the context resolved it while it started and "
                            + "will not resolve it again")
                    .isTrue();
            try (var entries = Files.list(root)) {
                assertThat(entries.toList())
                        .as("and nothing may remain, or a later capture could find a generation an "
                                + "earlier phase of the same class produced")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("is silent when the path is not a directory, so a class whose root was removed "
                + "out from under it still tears down")
        void aMissingDirectoryIsNotAFailure() {
            final Path root = newRoot();
            RunScopedStagingRoot.deleteRecursively(root);

            RunScopedStagingRoot.deleteContents(root);

            assertThat(Files.exists(root)).isFalse();
        }

        @Test
        @DisplayName("refuses a null path rather than deciding for itself what to empty")
        void aNullPathIsRefused() {
            assertThatThrownBy(() -> RunScopedStagingRoot.deleteContents(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("root");
        }
    }
}
