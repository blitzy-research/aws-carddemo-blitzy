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
package com.carddemo.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that the batch tier's staged files are created readable by their owner and by nobody else.
 *
 * <h2>What is under test, and why it is worth a suite of its own</h2>
 *
 * <p>Nine jobs write a local file before publishing it to object storage, and what those files hold is
 * the whole of what the legacy datasets held: reject records, the transaction master, statement
 * generations carrying a cardholder's name and address, the transaction report. On z/OS those datasets
 * were catalogued objects under an external security product; here they are ordinary files, and their
 * mode is the only thing between them and every other account on the host.
 *
 * <p>The failure this guards against is silent in every other way. A generation created at the process
 * umask is a perfectly valid generation: the job succeeds, the object publishes, the byte-parity
 * assertions pass, and the file was world-readable for the whole of the run. Nothing but a direct
 * assertion on the mode can see it, which is why the assertions here are made on the filesystem rather
 * than on the code that talks to it.
 *
 * <h2>Portability</h2>
 *
 * <p>Every mode assertion goes through {@link SecureStagedFiles#isOwnerOnly(Path)}, which reports
 * {@code true} on a filesystem with no POSIX view because there is no wider mode there to report. The
 * suite therefore states the same property on every platform and asserts it wherever the platform can
 * express it, instead of being skipped on one and meaningless on the other.
 */
@DisplayName("SecureStagedFiles - a staged generation is readable by its owner and by nobody else")
class SecureStagedFilesTest {

    /** A record image standing in for a staged generation's content. */
    private static final String RECORD = "0000000000000001".repeat(4);

    /** Whether the filesystem under test expresses POSIX permissions at all. */
    private static boolean posixAware(final Path reference) {
        return reference.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    @Nested
    @DisplayName("a staged file is owner-only from the first byte")
    class OwnerOnlyCreation {

        @Test
        @DisplayName("a writer creates the file readable and writable by its owner and by nobody else")
        void aWriterCreatesAnOwnerOnlyFile(@TempDir final Path staging) throws IOException {
            final Path target = staging.resolve("generation.dat");

            try (BufferedWriter writer = SecureStagedFiles.newWriter(target,
                    StandardCharsets.US_ASCII)) {
                writer.write(RECORD);
            }

            assertThat(SecureStagedFiles.isOwnerOnly(target)).isTrue();
            assertThat(Files.readString(target, StandardCharsets.US_ASCII)).isEqualTo(RECORD);
        }

        @Test
        @DisplayName("and so does a byte stream, which is what the two binary generations use")
        void aStreamCreatesAnOwnerOnlyFile(@TempDir final Path staging) throws IOException {
            final Path target = staging.resolve("generation.bin");

            try (OutputStream stream = SecureStagedFiles.newOutputStream(target)) {
                stream.write(RECORD.getBytes(StandardCharsets.US_ASCII));
            }

            assertThat(SecureStagedFiles.isOwnerOnly(target)).isTrue();
            assertThat(Files.readAllBytes(target))
                    .isEqualTo(RECORD.getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("the mode is exactly read and write for the owner, so nothing is granted that was "
                + "not asked for")
        void theModeIsExactlyOwnerReadWrite(@TempDir final Path staging) throws IOException {
            final Path target = staging.resolve("exact-mode.dat");
            SecureStagedFiles.newWriter(target, StandardCharsets.US_ASCII).close();

            if (!posixAware(staging)) {
                assertThat(SecureStagedFiles.isOwnerOnly(target)).isTrue();
                return;
            }
            assertThat(Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS))
                    .containsExactlyInAnyOrderElementsOf(SecureStagedFiles.OWNER_ONLY_FILE);
        }

        @Test
        @DisplayName("a minted per-execution file is owner-only too, and so is the directory minted to "
                + "hold it")
        void aMintedFileAndItsDirectoryAreOwnerOnly() throws IOException {
            final Path area = SecureStagedFiles.newTemporaryDirectory("carddemo-secure-test-");
            try {
                final Path minted = SecureStagedFiles.newTemporaryFile(area, "work-", ".dat");

                assertThat(SecureStagedFiles.isOwnerOnly(minted)).isTrue();
                assertThat(SecureStagedFiles.isOwnerOnly(area)).isTrue();
                if (posixAware(area)) {
                    assertThat(Files.getPosixFilePermissions(area, LinkOption.NOFOLLOW_LINKS))
                            .containsExactlyInAnyOrderElementsOf(
                                    SecureStagedFiles.OWNER_ONLY_DIRECTORY);
                }
            } finally {
                try (var held = Files.list(area)) {
                    for (final Path entry : held.toList()) {
                        Files.deleteIfExists(entry);
                    }
                }
                Files.deleteIfExists(area);
            }
        }

        @Test
        @DisplayName("a directory the module creates is owner-only, including every segment it had to "
                + "create to reach it")
        void everyCreatedDirectorySegmentIsOwnerOnly(@TempDir final Path root) throws IOException {
            final Path leaf = root.resolve("a").resolve("b").resolve("c");

            SecureStagedFiles.prepareDirectory(leaf);

            assertThat(SecureStagedFiles.isOwnerOnly(leaf)).isTrue();
            assertThat(SecureStagedFiles.isOwnerOnly(root.resolve("a"))).isTrue();
            assertThat(SecureStagedFiles.isOwnerOnly(root.resolve("a").resolve("b"))).isTrue();
        }

        @Test
        @DisplayName("a directory that already exists is left exactly as it is, because the staging root "
                + "may be a path the module does not own")
        void aPreExistingDirectoryIsNotAltered(@TempDir final Path root) throws IOException {
            final Path shared = root.resolve("shared");
            Files.createDirectory(shared);
            if (!posixAware(root)) {
                assertThat(SecureStagedFiles.prepareDirectory(shared)).isEqualTo(shared);
                return;
            }
            final Set<PosixFilePermission> wide = PosixFilePermissions.fromString("rwxr-xr-x");
            Files.setPosixFilePermissions(shared, wide);

            SecureStagedFiles.prepareDirectory(shared);

            assertThat(Files.getPosixFilePermissions(shared, LinkOption.NOFOLLOW_LINKS))
                    .as("tightening the platform temporary directory, or a mount shared with another "
                            + "workload, is vandalism on a path this module does not own")
                    .containsExactlyInAnyOrderElementsOf(wide);
        }
    }

    @Nested
    @DisplayName("nothing is written through a link, onto a directory, or into a file a previous run "
            + "left")
    class RefusalsAndReplacement {

        @Test
        @DisplayName("a symbolic link planted where a generation is about to be written is refused "
                + "rather than followed")
        void aPlantedLinkIsRefused(@TempDir final Path staging) throws IOException {
            final Path elsewhere = staging.resolve("attacker-owned.dat");
            Files.writeString(elsewhere, "untouched", StandardCharsets.US_ASCII);
            final Path target = staging.resolve("generation.dat");
            try {
                Files.createSymbolicLink(target, elsewhere);
            } catch (final UnsupportedOperationException | IOException unsupported) {
                return;
            }

            assertThatExceptionOfType(FileAlreadyExistsException.class)
                    .isThrownBy(() -> SecureStagedFiles.newWriter(target, StandardCharsets.US_ASCII))
                    .withMessageContaining("symbolic link");
            assertThat(Files.readString(elsewhere, StandardCharsets.US_ASCII))
                    .as("following the link would append cardholder records to whatever it points at")
                    .isEqualTo("untouched");
            assertThat(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                    .as("the link is refused, not deleted: deleting it is a second thing the planter "
                            + "could have wanted")
                    .isTrue();
        }

        @Test
        @DisplayName("a linked staging directory is refused too, because a link there redirects every "
                + "generation written within it")
        void aLinkedStagingDirectoryIsRefused(@TempDir final Path root) throws IOException {
            final Path real = root.resolve("real");
            Files.createDirectory(real);
            final Path linked = root.resolve("linked");
            try {
                Files.createSymbolicLink(linked, real);
            } catch (final UnsupportedOperationException | IOException unsupported) {
                return;
            }

            assertThatExceptionOfType(FileAlreadyExistsException.class)
                    .isThrownBy(() -> SecureStagedFiles.prepareDirectory(linked))
                    .withMessageContaining("symbolic link");
        }

        @Test
        @DisplayName("a directory where a file belongs is refused, and a file where a directory belongs "
                + "is refused")
        void aMismatchedPathIsRefused(@TempDir final Path root) throws IOException {
            final Path occupiedByDirectory = root.resolve("occupied");
            Files.createDirectory(occupiedByDirectory);
            final Path occupiedByFile = root.resolve("file");
            Files.writeString(occupiedByFile, "content", StandardCharsets.US_ASCII);

            assertThatExceptionOfType(FileAlreadyExistsException.class)
                    .isThrownBy(() -> SecureStagedFiles.newOutputStream(occupiedByDirectory))
                    .withMessageContaining("is a directory");
            assertThatExceptionOfType(FileAlreadyExistsException.class)
                    .isThrownBy(() -> SecureStagedFiles.prepareDirectory(occupiedByFile))
                    .withMessageContaining("not a directory");
        }

        @Test
        @DisplayName("a file a previous run left is removed and created afresh, so the new generation "
                + "cannot inherit that run's mode or its owner")
        void aPreviousGenerationIsReplacedRatherThanTruncated(@TempDir final Path staging)
                throws IOException {
            final Path target = staging.resolve("generation.dat");
            Files.writeString(target, "a previous run's records", StandardCharsets.US_ASCII);
            if (posixAware(staging)) {
                Files.setPosixFilePermissions(target,
                        PosixFilePermissions.fromString("rw-rw-rw-"));
            }

            try (BufferedWriter writer = SecureStagedFiles.newWriter(target,
                    StandardCharsets.US_ASCII)) {
                writer.write(RECORD);
            }

            assertThat(Files.readString(target, StandardCharsets.US_ASCII))
                    .as("the legacy allocate-new disposition replaces the generation's content")
                    .isEqualTo(RECORD);
            assertThat(SecureStagedFiles.isOwnerOnly(target))
                    .as("truncating in place would have kept the wide mode the previous run left")
                    .isTrue();
        }

        @Test
        @DisplayName("a container that does not exist yet is created, and a path with no container is "
                + "not a failure")
        void aMissingContainerIsCreatedAndAnAbsentOneIsTolerated(@TempDir final Path root)
                throws IOException {
            final Path nested = root.resolve("not").resolve("yet").resolve("generation.dat");

            SecureStagedFiles.newWriter(nested, StandardCharsets.US_ASCII).close();

            assertThat(nested).exists();
            assertThat(SecureStagedFiles.isOwnerOnly(nested)).isTrue();
            SecureStagedFiles.prepareContainerOf(Path.of("no-container-segment.dat"));
        }
    }

    @Nested
    @DisplayName("securing a file that has just been moved or copied")
    class AfterAMove {

        @Test
        @DisplayName("an owner-only mode is restored on a file a copy created at the process umask")
        void aCopiedFileIsSecured(@TempDir final Path staging) throws IOException {
            final Path source = staging.resolve("source.dat");
            SecureStagedFiles.newWriter(source, StandardCharsets.US_ASCII).close();
            final Path duplicate = staging.resolve("duplicate.dat");
            Files.copy(source, duplicate);
            if (posixAware(staging)) {
                Files.setPosixFilePermissions(duplicate,
                        PosixFilePermissions.fromString("rw-r--r--"));
                assertThat(SecureStagedFiles.isOwnerOnly(duplicate)).isFalse();
            }

            SecureStagedFiles.applyOwnerOnly(duplicate);

            assertThat(SecureStagedFiles.isOwnerOnly(duplicate)).isTrue();
        }

        @Test
        @DisplayName("and securing a file that is no longer there is reported rather than ignored")
        void securingAnAbsentFileIsReported(@TempDir final Path staging) {
            final Path absent = staging.resolve("never-existed.dat");

            if (!posixAware(staging)) {
                return;
            }
            assertThatExceptionOfType(IOException.class)
                    .isThrownBy(() -> SecureStagedFiles.applyOwnerOnly(absent))
                    .withMessageContaining("no longer present");
        }
    }

    @Nested
    @DisplayName("the guards, and the refusal to be instantiated")
    class Guards {

        @Test
        @DisplayName("every entry point refuses an absent argument by name rather than dereferencing it")
        void everyEntryPointRefusesAnAbsentArgument(@TempDir final Path staging) {
            assertThatNullPointerException()
                    .isThrownBy(() -> SecureStagedFiles.prepareDirectory(null))
                    .withMessageContaining("directory");
            assertThatNullPointerException()
                    .isThrownBy(() -> SecureStagedFiles.prepareContainerOf(null))
                    .withMessageContaining("target");
            assertThatNullPointerException()
                    .isThrownBy(() -> SecureStagedFiles.newWriter(null, StandardCharsets.US_ASCII))
                    .withMessageContaining("target");
            assertThatNullPointerException()
                    .isThrownBy(() -> SecureStagedFiles.newWriter(staging.resolve("x"), null))
                    .withMessageContaining("charset");
            assertThatNullPointerException()
                    .isThrownBy(() -> SecureStagedFiles.newOutputStream(null))
                    .withMessageContaining("target");
            assertThatNullPointerException()
                    .isThrownBy(() -> SecureStagedFiles.newTemporaryFile(null, "p", ".s"))
                    .withMessageContaining("directory");
            assertThatNullPointerException()
                    .isThrownBy(() -> SecureStagedFiles.newTemporaryFile(staging, null, ".s"))
                    .withMessageContaining("prefix");
            assertThatNullPointerException()
                    .isThrownBy(() -> SecureStagedFiles.newTemporaryFile(staging, "p", null))
                    .withMessageContaining("suffix");
            assertThatNullPointerException()
                    .isThrownBy(() -> SecureStagedFiles.newTemporaryDirectory(null))
                    .withMessageContaining("prefix");
            assertThatNullPointerException()
                    .isThrownBy(() -> SecureStagedFiles.applyOwnerOnly(null))
                    .withMessageContaining("target");
            assertThatNullPointerException()
                    .isThrownBy(() -> SecureStagedFiles.isOwnerOnly(null))
                    .withMessageContaining("target");
        }

        @Test
        @DisplayName("the two published modes are unmodifiable, so no caller can widen the policy for "
                + "every job at once")
        void thePublishedModesAreUnmodifiable() {
            assertThat(SecureStagedFiles.OWNER_ONLY_FILE)
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE)
                    .isUnmodifiable();
            assertThat(SecureStagedFiles.OWNER_ONLY_DIRECTORY)
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    .isUnmodifiable();
        }

        @Test
        @DisplayName("the utility holder refuses instantiation, so no reader looks for state it does "
                + "not have")
        void theHolderRefusesInstantiation() throws Exception {
            final Constructor<SecureStagedFiles> declared =
                    SecureStagedFiles.class.getDeclaredConstructor();
            declared.setAccessible(true);

            assertThatExceptionOfType(java.lang.reflect.InvocationTargetException.class)
                    .isThrownBy(declared::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }
    }

    /**
     * Deciding whether a path found later is the staged artefact that was written earlier.
     *
     * <p>Creating a file owner-only says nothing about a file <em>found</em> under the same root
     * afterwards. A local actor able to write the staging root can put something else there under a name
     * a resolver will accept, and the resolver then reads the planted content as though the job had
     * produced it. Each specification below removes exactly one of the four properties and asserts that
     * the answer changes, which is what makes all four load-bearing rather than decorative.
     */
    @Nested
    @DisplayName("a candidate is trusted only when four independent properties all hold")
    class TheTrustPredicate {

        /** Creates the slice. */
        TheTrustPredicate() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("a regular file this account owns, directly in a root nothing else may write, is "
                + "trusted - which is the control case the four negatives are measured against")
        void aGenuineStagedArtifactIsTrusted(@TempDir final Path root) throws IOException {
            final Path staged = root.resolve("AWS.M2.CARDDEMO.TRANSACT.DALY.G0000000001V00");
            Files.writeString(staged, "records", StandardCharsets.US_ASCII);

            assertThat(SecureStagedFiles.isTrustedStagedArtifact(root, staged)).isTrue();
        }

        @Test
        @DisplayName("A SYMBOLIC LINK IS NOT TRUSTED, even one pointing at a file this account owns: the "
                + "inspection does not follow it, so the link itself is what is judged")
        void aSymbolicLinkIsNotTrusted(@TempDir final Path root) throws IOException {
            final Path target = root.resolve("attacker-content");
            Files.writeString(target, "records the attacker chose", StandardCharsets.US_ASCII);
            final Path link = root.resolve("AWS.M2.CARDDEMO.TRANSACT.DALY.G0000009999V00");
            try {
                Files.createSymbolicLink(link, target);
            } catch (final UnsupportedOperationException | IOException linksUnavailable) {
                return;
            }

            assertThat(SecureStagedFiles.isTrustedStagedArtifact(root, link))
                    .as("THE DEFECT THIS PINS. A default regular-file check follows the link and answers "
                            + "about its target, so a link named as a staged generation was accepted as "
                            + "one")
                    .isFalse();
        }

        @Test
        @DisplayName("a directory is not trusted, because a directory is not an artifact anything here "
                + "hands out")
        void aDirectoryIsNotTrusted(@TempDir final Path root) throws IOException {
            final Path directory = root.resolve("AWS.M2.CARDDEMO.TRANSACT.DALY.G0000000002V00");
            Files.createDirectory(directory);

            assertThat(SecureStagedFiles.isTrustedStagedArtifact(root, directory)).isFalse();
        }

        @Test
        @DisplayName("a path that is not a DIRECT child of the root is not trusted, so a name that "
                + "resolved out of the root cannot be believed on the strength of the file it reached")
        void aPathOutsideTheRootIsNotTrusted(@TempDir final Path root) throws IOException {
            final Path nested = Files.createDirectory(root.resolve("nested"));
            final Path deeper = nested.resolve("AWS.M2.CARDDEMO.TRANSACT.DALY.G0000000003V00");
            Files.writeString(deeper, "records", StandardCharsets.US_ASCII);

            assertThat(SecureStagedFiles.isTrustedStagedArtifact(root, deeper))
                    .as("a grandchild is not a child")
                    .isFalse();
            assertThat(SecureStagedFiles.isTrustedStagedArtifact(root,
                    root.resolve("nested/../nested/" + deeper.getFileName())))
                    .as("and normalising the traversal does not make it one either")
                    .isFalse();
        }

        @Test
        @DisplayName("a candidate anybody may write is not trusted, because it can be rewritten in place "
                + "after the check that accepted it")
        void aWritableCandidateIsNotTrusted(@TempDir final Path root) throws IOException {
            final Path staged = root.resolve("AWS.M2.CARDDEMO.TRANSACT.DALY.G0000000004V00");
            Files.writeString(staged, "records", StandardCharsets.US_ASCII);
            try {
                Files.setPosixFilePermissions(staged,
                        PosixFilePermissions.fromString("rw-rw-rw-"));
            } catch (final UnsupportedOperationException noPosixView) {
                return;
            }

            assertThat(SecureStagedFiles.isTrustedStagedArtifact(root, staged)).isFalse();
        }

        @Test
        @DisplayName("nothing inside a root anybody may write is trusted, because such a root is one "
                + "anybody may plant in - so today's ownership says nothing about tomorrow's file")
        void nothingInAWritableRootIsTrusted(@TempDir final Path root) throws IOException {
            final Path staged = root.resolve("AWS.M2.CARDDEMO.TRANSACT.DALY.G0000000005V00");
            Files.writeString(staged, "records", StandardCharsets.US_ASCII);
            try {
                Files.setPosixFilePermissions(root,
                        PosixFilePermissions.fromString("rwxrwxrwx"));
            } catch (final UnsupportedOperationException noPosixView) {
                return;
            }

            assertThat(SecureStagedFiles.isTrustedStagedArtifact(root, staged)).isFalse();
        }

        @Test
        @DisplayName("read and execute permission outside the owner is deliberately NOT refused, because "
                + "a staging root an operator may read is a legitimate arrangement")
        void readableButNotWritableIsStillTrusted(@TempDir final Path root) throws IOException {
            final Path staged = root.resolve("AWS.M2.CARDDEMO.TRANSACT.DALY.G0000000006V00");
            Files.writeString(staged, "records", StandardCharsets.US_ASCII);
            try {
                Files.setPosixFilePermissions(root,
                        PosixFilePermissions.fromString("rwxr-xr-x"));
                Files.setPosixFilePermissions(staged,
                        PosixFilePermissions.fromString("rw-r--r--"));
            } catch (final UnsupportedOperationException noPosixView) {
                return;
            }

            assertThat(SecureStagedFiles.isTrustedStagedArtifact(root, staged))
                    .as("refusing this would refuse the job's own output for a reason that has nothing "
                            + "to do with whether the output is genuine")
                    .isTrue();
        }

        @Test
        @DisplayName("an absent path is not trusted, and neither argument may be absent")
        void anAbsentPathIsNotTrustedAndNeitherArgumentMayBeNull(@TempDir final Path root) {
            assertThat(SecureStagedFiles.isTrustedStagedArtifact(root, root.resolve("never-written")))
                    .isFalse();
            assertThatNullPointerException().isThrownBy(() ->
                    SecureStagedFiles.isTrustedStagedArtifact(null, root.resolve("x")));
            assertThatNullPointerException().isThrownBy(() ->
                    SecureStagedFiles.isTrustedStagedArtifact(root, null));
        }
    }
}
