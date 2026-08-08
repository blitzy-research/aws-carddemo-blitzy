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
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The isolation seam five batch specifications now stage through, asserted so it cannot quietly go back to
 * a shared directory.
 *
 * <p>Every property below is one the specifications depend on rather than a description of the
 * implementation. The root has to be private to this process, or two runs and two sibling clones collide on
 * one absolute path. It has to be owner-only in the write sense, or the module's own trust predicate refuses
 * the job's output and the refusal reads as a defect in the job. It has to be stable across calls, or a
 * set-up callback and a clean-up callback name different directories and the cleanup misses what the set-up
 * created. And the discard has to be confined to this process's namespace, or it is the blanket sweep it
 * replaced with extra steps.
 */
@DisplayName("the per-process staging namespace the batch specifications stage within")
class IsolatedStagingRootTest {

    /** A label used by the assertions that need one of their own. */
    private static final String LABEL = "isolated-staging-root-test";

    /** Creates the specification. */
    IsolatedStagingRootTest() {
        // Intentionally empty.
    }

    @Nested
    @DisplayName("the root one specification receives")
    class TheRoot {

        /** Creates the nested specification. */
        TheRoot() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("exists, is a directory, and sits beneath this process's own namespace rather than "
                + "directly beneath the shared temporary directory")
        void existsBeneathThisProcessNamespace() {
            final Path root = IsolatedStagingRoot.forSpecification(LABEL);
            try {
                assertThat(root).isDirectory();
                assertThat(root.getParent())
                        .as("a root whose parent were the platform temporary directory would be the shared "
                                + "arrangement this class exists to replace")
                        .isEqualTo(IsolatedStagingRoot.processNamespace());
                assertThat(IsolatedStagingRoot.processNamespace().getFileName().toString())
                        .as("the namespace carries the process identifier, so no other run of the same "
                                + "specification resolves this path")
                        .contains(String.valueOf(ProcessHandle.current().pid()));
            } finally {
                IsolatedStagingRoot.discard(root);
            }
        }

        @Test
        @DisplayName("is owner-only, and the namespace above it is owner-only too, so the module's trust "
                + "predicate believes an artefact staged within it")
        void isOwnerOnlyAndTrustedByTheModule() throws IOException {
            final Path root = IsolatedStagingRoot.forSpecification(LABEL);
            try {
                // Asserted on the permissions directly rather than only through the predicate. The
                // predicate narrows to WRITE outside the owner (DL-269), so on a host with a restrictive
                // umask it would accept a root created by any means and this assertion would pass without
                // pinning anything. The mode is what the seam guarantees, so the mode is what is asserted;
                // it holds whatever the ambient umask is, because the directory is created with an
                // explicit permission attribute rather than by subtraction from a umask.
                assertThat(SecureStagedFiles.isOwnerOnly(root))
                        .as("a root readable or traversable outside its owner exposes staged account, card "
                                + "and customer records to every other account on the host")
                        .isTrue();
                assertThat(SecureStagedFiles.isOwnerOnly(IsolatedStagingRoot.processNamespace()))
                        .as("an owner-only root beneath a world-readable namespace still lets another "
                                + "account enumerate which specifications ran")
                        .isTrue();

                final Path staged = root.resolve("staged-generation.dat");
                Files.writeString(staged, "record", StandardCharsets.UTF_8);
                assertThat(SecureStagedFiles.isTrustedStagedArtifact(root, staged))
                        .as("a root the module refuses would make every batch specification's own output "
                                + "untrusted, and the refusal would be read as a defect in the job")
                        .isTrue();
            } finally {
                IsolatedStagingRoot.discard(root);
            }
        }

        @Test
        @DisplayName("is the same path on every call for one label, so a set-up callback and a clean-up "
                + "callback cannot name different directories")
        void isStableAcrossCalls() {
            final Path first = IsolatedStagingRoot.forSpecification(LABEL);
            try {
                assertThat(IsolatedStagingRoot.forSpecification(LABEL)).isEqualTo(first);
                assertThat(IsolatedStagingRoot.pathFor(LABEL)).isEqualTo(first.toString());
            } finally {
                IsolatedStagingRoot.discard(first);
            }
        }

        @Test
        @DisplayName("differs between labels, so two specifications in one process are as separate as two "
                + "processes are")
        void differsBetweenLabels() {
            final Path first = IsolatedStagingRoot.forSpecification(LABEL);
            final Path second = IsolatedStagingRoot.forSpecification(LABEL + "-sibling");
            try {
                assertThat(first).isNotEqualTo(second);
            } finally {
                IsolatedStagingRoot.discard(first);
                IsolatedStagingRoot.discard(second);
            }
        }

        @Test
        @DisplayName("refuses a label that would place the root somewhere other than where this class says "
                + "it is")
        void refusesALabelThatEscapesTheNamespace() {
            assertThatNullPointerException()
                    .isThrownBy(() -> IsolatedStagingRoot.forSpecification(null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> IsolatedStagingRoot.forSpecification("   "));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> IsolatedStagingRoot.forSpecification("nested/label"));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> IsolatedStagingRoot.forSpecification(".."));
        }
    }

    @Nested
    @DisplayName("the discard")
    class TheDiscard {

        /** Creates the nested specification. */
        TheDiscard() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("removes the root and everything beneath it, so nothing a previous run staged can be "
                + "mistaken for a later execution's generation")
        void removesTheRootAndItsContents() throws IOException {
            final Path root = IsolatedStagingRoot.forSpecification(LABEL);
            final Path staged = root.resolve("generation.dat");
            final Path nested = root.resolve("nested");
            Files.writeString(staged, "record", StandardCharsets.UTF_8);
            Files.createDirectories(nested);
            Files.writeString(nested.resolve("deeper.dat"), "record", StandardCharsets.UTF_8);

            IsolatedStagingRoot.discard(root);

            assertThat(root).doesNotExist();
        }

        @Test
        @DisplayName("is silent about a root that is already gone, which is the ordinary case for a "
                + "specification whose set-up never ran")
        void isSilentAboutAnAbsentRoot() {
            final Path root = IsolatedStagingRoot.forSpecification(LABEL);
            IsolatedStagingRoot.discard(root);

            IsolatedStagingRoot.discard(root);

            assertThat(root).doesNotExist();
        }

        @Test
        @DisplayName("REFUSES a path outside this process's namespace, because a cleanup that can be "
                + "pointed anywhere is the defect this class removes rather than a convenience")
        void refusesAPathOutsideThisProcessNamespace() throws IOException {
            final Path outside = Files.createTempDirectory("carddemo-outside-the-namespace");
            try {
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> IsolatedStagingRoot.discard(outside))
                        .withMessageContaining("namespace");
                assertThat(outside)
                        .as("and it must still be there, because a refusal that deleted first would be no "
                                + "refusal at all")
                        .isDirectory();
            } finally {
                Files.deleteIfExists(outside);
            }
            assertThatNullPointerException().isThrownBy(() -> IsolatedStagingRoot.discard(null));
        }
    }

    @Nested
    @DisplayName("the type itself")
    class TheType {

        /** Creates the nested specification. */
        TheType() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("holds no state and refuses to be instantiated")
        void refusesInstantiation() throws ReflectiveOperationException {
            final Constructor<IsolatedStagingRoot> constructor =
                    IsolatedStagingRoot.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }
    }
}
