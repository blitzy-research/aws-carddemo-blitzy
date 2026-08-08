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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Creates the batch tier's staged files so that only the account the job runs as can read them.
 *
 * <h2>What is staged, and why its permissions are a control rather than a detail</h2>
 *
 * <p>Nine jobs write a local file before publishing it to object storage. What those files hold is the
 * whole of what the legacy datasets held, in the same fixed-width images: the 430-byte reject records
 * carrying every refused daily transaction, the 350-byte archive and combined generations carrying
 * every posted transaction with its card number, the 80-byte and 100-byte statement generations
 * carrying a cardholder's name, address and every transaction on their account, the 133-byte report
 * generation, and the 40-byte category-balance listing. On the mainframe those datasets were catalogued
 * objects under an external access-control product; here they are ordinary files in a directory, and the
 * only thing standing between them and every other process on the host is their mode.
 *
 * <p>A file created through {@link Files#newBufferedWriter} takes its mode from the process umask. A
 * container image conventionally runs with {@code 0022}, which yields {@code rw-r--r--}: every account
 * on the host can read a statement generation while it is staged, and the window is the whole of the
 * job's run plus however long the file is retained afterwards. Nothing in the module notices, because
 * the job works perfectly.
 *
 * <h2>What this class guarantees</h2>
 *
 * <ol>
 *   <li><strong>Owner-only from the first byte.</strong> Every file is created with
 *       {@code rw-------} supplied as a creation attribute, not applied afterwards. Creating first and
 *       tightening second leaves a window - short, but a window - in which the file exists at the
 *       umask's mode and can be opened by anyone who is watching the directory. A descriptor obtained
 *       inside that window keeps its access after the mode changes.</li>
 *   <li><strong>Never through a link, and never onto a directory.</strong> The target is examined
 *       without following links before anything is written. A symbolic link planted where a generation
 *       is about to be written would otherwise let whoever planted it choose the file the job appends
 *       cardholder data to.</li>
 *   <li><strong>Never silently reused.</strong> A file is created with {@link
 *       StandardOpenOption#CREATE_NEW}, so an existing file cannot be opened and written through. The
 *       legacy allocate-new disposition is preserved by <em>removing</em> what a previous run left and
 *       then creating afresh, which is what the disposition means, rather than by truncating in place -
 *       truncating in place keeps the previous run's mode and its owner.</li>
 *   <li><strong>Directories the job creates are owner-only too.</strong> A staging directory readable by
 *       others discloses the generation names, and generation names carry the execution identifiers the
 *       object keys are built from.</li>
 * </ol>
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It does not change the mode of a directory that already exists. The staging root is configurable
 * and its default resolves under the platform temporary directory, which on every Unix host is
 * world-writable and sticky by design and is shared with the rest of the system. Tightening a
 * pre-existing directory would either be a no-operation or an act of vandalism on a path the module
 * does not own; the module's own leaf directory is created by the module and therefore is owner-only,
 * which is the part it can honestly guarantee. What it does check on a pre-existing directory is that it
 * is a directory and is not a link, because both of those are how a staging path gets redirected.
 *
 * <p>It does not encrypt. The staged file is short-lived local scratch on the way to object storage,
 * where durability and encryption belong; a second key-management surface here would add a secret to
 * protect without removing the one this class addresses.
 *
 * <h2>Non-POSIX filesystems</h2>
 *
 * <p>The permission attribute is supplied only where the filesystem supports POSIX views. Where it does
 * not - a Windows development host, or a container volume mounted from one - the file is created without
 * it and inherits the platform's own default, which for a per-user temporary location is already private.
 * The check is made against the filesystem rather than against an operating-system name, so a POSIX
 * volume mounted on a non-POSIX host is still protected.
 *
 * <h2>Layering</h2>
 *
 * <p>It sits in the utility layer because nine job configurations across the batch layer need the same
 * policy and one shared policy is the only way the nine cannot drift apart. It holds no state, reads no
 * configuration, declares no logger and depends on nothing above it.
 *
 * <h2>Provenance</h2>
 *
 * <p>This class has no legacy antecedent, and its absence in the legacy estate is itself the reason it
 * exists: a sequential dataset on z/OS is a catalogued object whose access is decided by an external
 * security product and not by the program that writes it, so no COBOL member in the migrated estate
 * expresses a permission at all. Reproducing that silence on a filesystem reproduces the umask instead
 * of the access control. Legacy estate read at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} (2022-07-19). See {@code docs/decision-log.md} DL-177.
 */
public final class SecureStagedFiles {

    /** Mode every staged file is created with: readable and writable by its owner and by nobody else. */
    public static final Set<PosixFilePermission> OWNER_ONLY_FILE =
            Set.copyOf(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

    /**
     * Mode every staging directory this module creates is created with.
     *
     * <p>Carries the owner execute bit as well, because a directory without it cannot be traversed and a
     * file within it cannot be opened by name.
     */
    public static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
            Set.copyOf(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));

    /** Name of the filesystem attribute view POSIX permissions are expressed through. */
    private static final String POSIX_VIEW = "posix";

    /** Creation attributes for a staged file, or none where the filesystem has no POSIX view. */
    private static final FileAttribute<?>[] NO_ATTRIBUTES = new FileAttribute<?>[0];

    /**
     * Refuses instantiation.
     *
     * <p>Every member is static and the type holds no state, so an instance would carry nothing and
     * would only invite a reader to look for the state it does not have.
     *
     * @throws AssertionError always
     */
    private SecureStagedFiles() {
        throw new AssertionError("SecureStagedFiles is a utility holder and must not be instantiated");
    }

    /**
     * Makes a staging directory usable, creating what is missing with an owner-only mode.
     *
     * <p>Every segment this call creates is created owner-only. A segment that was already there is left
     * exactly as it is - see the class contract for why - but the leaf is checked: it must be a directory
     * and it must not be a symbolic link, because a link in that position redirects every generation the
     * job is about to write.
     *
     * @param  directory   the directory to prepare; must not be {@code null}
     * @return             the same path, so a caller can prepare and use in one expression
     * @throws IOException if a segment cannot be created
     * @throws FileAlreadyExistsException if the leaf is a symbolic link, or exists and is not a
     *                                    directory
     * @throws NullPointerException if {@code directory} is {@code null}
     */
    public static Path prepareDirectory(final Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        final BasicFileAttributes existing = attributesWithoutFollowing(directory);
        if (existing == null) {
            Files.createDirectories(directory, directoryAttributes(directory));
            return directory;
        }
        if (existing.isSymbolicLink()) {
            throw new FileAlreadyExistsException(directory.toString(), null,
                    "the staging directory is a symbolic link; a link in that position redirects every"
                            + " generation written within it, so it is refused rather than followed");
        }
        if (!existing.isDirectory()) {
            throw new FileAlreadyExistsException(directory.toString(), null,
                    "the staging path exists and is not a directory, so no generation can be written"
                            + " within it");
        }
        return directory;
    }

    /**
     * Makes the directory a staged file resolves within usable, if the path names one.
     *
     * <p>A relative path with a single segment has no parent, which is not a failure: the file resolves
     * against the working directory, which exists by definition.
     *
     * @param  target      the staged file whose container is being prepared; must not be {@code null}
     * @throws IOException if the container cannot be prepared
     * @throws NullPointerException if {@code target} is {@code null}
     */
    public static void prepareContainerOf(final Path target) throws IOException {
        Objects.requireNonNull(target, "target must not be null");
        final Path container = target.getParent();
        if (container != null) {
            prepareDirectory(container);
        }
    }

    /**
     * Opens a staged file for writing, owner-only, replacing whatever a previous run left behind.
     *
     * @param  target      the file to write; must not be {@code null}
     * @param  charset     the encoding the records are written in; must not be {@code null}
     * @return             a writer over a newly created owner-only file
     * @throws IOException if the container cannot be prepared or the file cannot be created
     * @throws FileAlreadyExistsException if the target is a symbolic link or a directory
     * @throws NullPointerException if either argument is {@code null}
     */
    public static BufferedWriter newWriter(final Path target, final Charset charset)
            throws IOException {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(charset, "charset must not be null");
        createOwnerOnlyFile(target);
        // Created above and opened here, in two steps rather than one, because neither of the JDK's
        // opening calls takes a creation attribute: a file they create takes the process umask, and no
        // later change of mode can close the window in which it was wider. NOFOLLOW_LINKS closes what
        // remains of the interval between the two - a link substituted for the file in it makes the open
        // fail rather than redirect it - and TRUNCATE_EXISTING, a no-operation on a file created empty a
        // moment ago, means this can never append to content whatever put content there.
        return Files.newBufferedWriter(target, charset, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Opens a staged file for writing bytes, owner-only, replacing whatever a previous run left behind.
     *
     * @param  target      the file to write; must not be {@code null}
     * @return             a stream over a newly created owner-only file
     * @throws IOException if the container cannot be prepared or the file cannot be created
     * @throws FileAlreadyExistsException if the target is a symbolic link or a directory
     * @throws NullPointerException if {@code target} is {@code null}
     */
    public static OutputStream newOutputStream(final Path target) throws IOException {
        Objects.requireNonNull(target, "target must not be null");
        createOwnerOnlyFile(target);
        // Two steps for the same reason as the writer above: creation carries the mode, the open does
        // not follow a link, and the truncation cannot append.
        return Files.newOutputStream(target, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Mints a uniquely named owner-only file within a staging directory.
     *
     * <p>Used where an artefact belongs to one execution and a unique name is what makes that true
     * without a job parameter. The name is chosen by the platform, so two executions cannot collide and
     * neither can predict the other's.
     *
     * @param  directory   the directory to mint within; must not be {@code null}
     * @param  prefix      the name prefix; must not be {@code null}
     * @param  suffix      the name suffix; must not be {@code null}
     * @return             the minted path
     * @throws IOException if the directory cannot be prepared or the file cannot be created
     * @throws NullPointerException if any argument is {@code null}
     */
    public static Path newTemporaryFile(final Path directory, final String prefix, final String suffix)
            throws IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(prefix, "prefix must not be null");
        Objects.requireNonNull(suffix, "suffix must not be null");
        prepareDirectory(directory);
        return Files.createTempFile(directory, prefix, suffix, fileAttributes(directory));
    }

    /**
     * Mints a uniquely named owner-only directory under the platform temporary location.
     *
     * @param  prefix      the name prefix; must not be {@code null}
     * @return             the minted directory
     * @throws IOException if it cannot be created
     * @throws NullPointerException if {@code prefix} is {@code null}
     */
    public static Path newTemporaryDirectory(final String prefix) throws IOException {
        Objects.requireNonNull(prefix, "prefix must not be null");
        final Path platformTemporary = Path.of(System.getProperty("java.io.tmpdir"));
        return Files.createTempDirectory(prefix, directoryAttributes(platformTemporary));
    }

    /**
     * Restores the owner-only mode on a file that has just been moved or renamed.
     *
     * <p>An atomic move carries the source file's mode with it, so a working file created through this
     * class is already owner-only when it lands. This exists for the case where the destination existed
     * beforehand with a wider mode, and for a filesystem whose move is a copy.
     *
     * @param  target      the file to secure; must not be {@code null}
     * @throws IOException if the mode cannot be applied
     * @throws NullPointerException if {@code target} is {@code null}
     */
    public static void applyOwnerOnly(final Path target) throws IOException {
        Objects.requireNonNull(target, "target must not be null");
        if (!supportsPosixPermissions(target)) {
            return;
        }
        try {
            Files.setPosixFilePermissions(target, OWNER_ONLY_FILE);
        } catch (final NoSuchFileException absent) {
            throw new IOException("the staged file " + target + " could not be secured because it is"
                    + " no longer present", absent);
        } catch (final UnsupportedOperationException unsupported) {
            throw new IOException("the filesystem holding " + target + " reported a POSIX view and then"
                    + " refused one", unsupported);
        }
    }

    /**
     * Creates an empty owner-only file at a target path, clearing whatever a previous run left.
     *
     * @param  target      the file to create
     * @return             the same path
     * @throws IOException if the container cannot be prepared or an existing file cannot be removed
     * @throws FileAlreadyExistsException if the target is a symbolic link or a directory, or if a file
     *                                    appears between the clearing and the creation
     */
    private static Path createOwnerOnlyFile(final Path target) throws IOException {
        prepareForCreation(target);
        // Atomic: creates or fails, and carries the mode as a creation attribute rather than applying it
        // afterwards. A file that exists at this point has appeared since the path was cleared a moment
        // ago, which is a race and not a leftover, so it is a failure and not something to overwrite.
        return Files.createFile(target, fileAttributes(target));
    }

    /**
     * Clears the way for a fresh owner-only file at a target path.
     *
     * <p>Prepares the container, then examines the target itself without following links. A link is
     * refused rather than replaced, so a caller cannot be tricked into deleting the link's destination.
     * A directory is refused. A regular file is removed, which is how the legacy allocate-new
     * disposition is honoured - and it is honoured by removal rather than by truncation, because
     * truncating in place keeps the previous run's mode and its owner.
     *
     * @param  target      the file about to be created
     * @throws IOException if the container cannot be prepared or an existing file cannot be removed
     * @throws FileAlreadyExistsException if the target is a symbolic link or a directory
     */
    private static void prepareForCreation(final Path target) throws IOException {
        prepareContainerOf(target);
        final BasicFileAttributes existing = attributesWithoutFollowing(target);
        if (existing == null) {
            return;
        }
        if (existing.isSymbolicLink()) {
            throw new FileAlreadyExistsException(target.toString(), null,
                    "the staged file is a symbolic link; writing through it would append records to"
                            + " whatever it points at, so it is refused rather than followed");
        }
        if (existing.isDirectory()) {
            throw new FileAlreadyExistsException(target.toString(), null,
                    "the staged path exists and is a directory, so no records can be written to it");
        }
        Files.delete(target);
    }

    /**
     * Reads a path's attributes without following a link at its final segment.
     *
     * @param  path        the path to examine
     * @return             its attributes, or {@code null} when nothing is there
     * @throws IOException if the path exists and its attributes cannot be read
     */
    private static BasicFileAttributes attributesWithoutFollowing(final Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (final NoSuchFileException absent) {
            return null;
        }
    }

    /**
     * Creation attributes for a staged file on the filesystem holding a reference path.
     *
     * @param  reference a path on the filesystem the file will be created on
     * @return           the owner-only attribute, or none where the filesystem has no POSIX view
     */
    private static FileAttribute<?>[] fileAttributes(final Path reference) {
        return supportsPosixPermissions(reference)
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE)}
                : NO_ATTRIBUTES;
    }

    /**
     * Creation attributes for a staging directory on the filesystem holding a reference path.
     *
     * @param  reference a path on the filesystem the directory will be created on
     * @return           the owner-only attribute, or none where the filesystem has no POSIX view
     */
    private static FileAttribute<?>[] directoryAttributes(final Path reference) {
        return supportsPosixPermissions(reference)
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY)}
                : NO_ATTRIBUTES;
    }

    /**
     * Whether the filesystem a path resolves on expresses POSIX permissions.
     *
     * <p>Asked of the filesystem rather than of the operating system, so a POSIX volume mounted on a
     * host that is not itself POSIX is still protected, and a volume that is not POSIX on a host that is
     * does not cause a creation to fail.
     *
     * @param  reference a path on the filesystem in question
     * @return           {@code true} when the owner-only attribute can be supplied
     */
    private static boolean supportsPosixPermissions(final Path reference) {
        return reference.getFileSystem().supportedFileAttributeViews().contains(POSIX_VIEW);
    }

    /**
     * Whether a staged file is currently readable only by its owner.
     *
     * <p>Present so that a job, a test or an operator can assert the property rather than assume it. A
     * filesystem with no POSIX view reports {@code true}, because there is no wider mode there to report.
     *
     * @param  target      the file to examine; must not be {@code null}
     * @return             {@code true} when no permission outside the owner's is granted
     * @throws IOException if the file's attributes cannot be read
     * @throws NullPointerException if {@code target} is {@code null}
     */
    public static boolean isOwnerOnly(final Path target) throws IOException {
        Objects.requireNonNull(target, "target must not be null");
        if (!supportsPosixPermissions(target)) {
            return true;
        }
        final Set<PosixFilePermission> granted = Files.getPosixFilePermissions(target,
                LinkOption.NOFOLLOW_LINKS);
        return OWNER_ONLY_DIRECTORY.containsAll(granted);
    }

    /**
     * Whether one candidate may be trusted as a staged artefact of one staging root.
     *
     * <h2>What this exists to refuse</h2>
     *
     * <p>Writing a staged file owner-only says nothing about whether a file found <em>later</em> under
     * the same root is the one that was written. A local actor able to write the staging root can put
     * something else there under a name the resolver will accept, and the resolver then reads the
     * planted content as though the job had produced it. This is the predicate that stands between the
     * two, and it is asked immediately before the path is used rather than remembered from creation
     * time.
     *
     * <h2>The four properties, and why each is separately necessary</h2>
     * <ol>
     *   <li><strong>The candidate is a real regular file, inspected without following links.</strong>
     *       {@link Files#isRegularFile(Path, LinkOption...)} at its default follows a symbolic link and
     *       answers about the <em>target</em>, so a link named like a staged generation and pointing
     *       anywhere at all is reported as a regular file. Inspected with
     *       {@link LinkOption#NOFOLLOW_LINKS} the link is what is examined, and a link is not a regular
     *       file.</li>
     *   <li><strong>Its normalised parent is the normalised root.</strong> Without this a name that
     *       resolved out of the root - or a root reached through a link - would be accepted because the
     *       file at the far end happens to be well formed.</li>
     *   <li><strong>The candidate and the root have the same owner.</strong> The root is provisioned by
     *       the deployment and owned by the account the module runs as, so a candidate owned by anybody
     *       else is a file this deployment did not write, whatever its name and whatever its mode. The
     *       comparison is between two filesystem principals rather than against a system property,
     *       because a property is a weaker statement about the same thing and is absent on some
     *       platforms.</li>
     *   <li><strong>Neither the root nor the candidate grants WRITE permission outside its owner's.</strong>
     *       A group- or world-writable root is a root somebody else can create, rename and delete
     *       entries in, so ownership of the artefact found today is no evidence about the artefact found
     *       tomorrow; and a writable file inside a traversable root can be rewritten in place after it
     *       was checked. Read and execute permission is deliberately <em>not</em> examined: a staging
     *       root a deployment lets an operator or a monitoring account read is a legitimate arrangement,
     *       and refusing it would refuse the job's own output for a reason that has nothing to do with
     *       whether the output is genuine. This is the one place where narrowing the rule beyond what
     *       {@link #isOwnerOnly(Path)} asserts is correct: that method states the mode this module
     *       <em>creates</em> with, while this one states the minimum a path must satisfy to be
     *       <em>believed</em>.</li>
     * </ol>
     *
     * <p>All four are required together and any one of them alone is insufficient, which is why this is
     * one predicate rather than four call sites that might each be given a different subset.
     *
     * <h2>Filesystems with no POSIX view</h2>
     *
     * <p>Where the filesystem exposes no POSIX view there is no mode and no owner to read, so the two
     * attribute properties are vacuously satisfied and the two structural ones still apply. That is the
     * same accommodation {@link #isOwnerOnly(Path)} makes, for the same reason: a volume that cannot
     * express the property must not be reported as violating it.
     *
     * <p>Answers {@code false} rather than raising when an attribute cannot be read. A candidate whose
     * trustworthiness cannot be established is not trustworthy, and a resolver that had to catch an
     * exception to learn that would be one refactoring away from treating the failure as a pass.
     *
     * @param  stagingRoot the directory the candidate must be a direct child of; must not be
     *                     {@code null}
     * @param  candidate   the path to examine; must not be {@code null}
     * @return {@code true} only when all four properties hold
     * @throws NullPointerException if either argument is {@code null}
     */
    public static boolean isTrustedStagedArtifact(final Path stagingRoot, final Path candidate) {
        Objects.requireNonNull(stagingRoot, "stagingRoot must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        final Path root = stagingRoot.toAbsolutePath().normalize();
        final Path resolved = candidate.toAbsolutePath().normalize();
        if (!root.equals(resolved.getParent())) {
            return false;
        }
        try {
            final BasicFileAttributes attributes = Files.readAttributes(resolved,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                return false;
            }
            if (!supportsPosixPermissions(resolved)) {
                return true;
            }
            if (grantsWriteOutsideOwner(resolved) || grantsWriteOutsideOwner(root)) {
                return false;
            }
            final UserPrincipal owner = Files.getOwner(resolved, LinkOption.NOFOLLOW_LINKS);
            return owner != null
                    && owner.equals(Files.getOwner(root, LinkOption.NOFOLLOW_LINKS));
        } catch (final IOException | UnsupportedOperationException unreadable) {
            return false;
        }
    }

    /**
     * Whether one path lets anybody other than its owner write to it.
     *
     * @param  target      the path to examine
     * @return             {@code true} when group or other write permission is granted
     * @throws IOException if the permissions cannot be read
     */
    private static boolean grantsWriteOutsideOwner(final Path target) throws IOException {
        final Set<PosixFilePermission> granted =
                Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS);
        return granted.contains(PosixFilePermission.GROUP_WRITE)
                || granted.contains(PosixFilePermission.OTHERS_WRITE);
    }

}
