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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;

/**
 * The one line every generated gate-evidence file carries, saying which build and which run produced
 * it.
 *
 * <p><strong>Why this exists.</strong> Three gates emit a run-scoped file into the build directory: the
 * Gate 1 byte-equivalence comparison, the Gate 3 measured baselines and the Gate 8 sign-off checklist.
 * Each of them named the <em>legacy</em> estate it was migrated from - a checkout SHA and an upstream
 * release stamp that are the same in every file this module will ever produce - and none of them named
 * the build that produced the file. Two bundles from two commits were therefore textually
 * indistinguishable, and a bundle that arrived without its context could not be attributed to the code
 * it measured. A figure whose origin cannot be established is a figure a reader has to take on trust,
 * which is precisely what the evidence page's own rules forbid.
 *
 * <p><strong>What is stamped, and where each part comes from.</strong> The build revision is the commit
 * the build was told it was packaging, read from the {@code carddemo.build.revision} system property
 * that {@code pom.xml} hands to the integration tier from its own {@code build.revision} property - the
 * same value the generated build information carries, so the evidence and the artefact name one commit.
 * A plain local build leaves that property at its declared default and the stamp says so rather than
 * inventing a revision. The run identity comes from the continuous-integration environment
 * ({@code GITHUB_REPOSITORY}, {@code GITHUB_RUN_ID}, {@code GITHUB_RUN_ATTEMPT} and {@code GITHUB_SHA}),
 * and reads {@code local} when those are absent, which is what a developer machine is. The instant is
 * UTC and truncated to the second, because a gate-evidence file is not a high-resolution trace and a
 * varying sub-second field would make two otherwise identical bundles differ.
 *
 * <p><strong>Why the environment is a parameter and not only a lookup.</strong> The rendering has to be
 * assertable, and neither the ambient environment nor the wall clock can be set from a test. The
 * package-private overload therefore takes the revision, the environment, the instant and the host
 * explicitly, and the public entry point supplies the real ones. Every rule about the rendered text is
 * asserted against the overload, so the format is held to something rather than merely produced.
 *
 * <p><strong>What is deliberately absent.</strong> No user name, no working-directory path, no branch
 * name and no environment dump. A provenance line travels with a published artefact, so it carries the
 * few identifiers that let a reader find the run and nothing that describes the machine's owner. The
 * host name is included because a Gate 3 figure is meaningless without the machine it was measured on,
 * and the evidence page already records one per row.
 *
 * <p>Decision log DL-315 records why the evidence is uploaded before the reproducibility clean and why
 * it is stamped.
 *
 * <p>Provenance of the estate itself, unchanged and separate from this: checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @since 1.0.0
 */
public final class GateEvidenceProvenance {

    /**
     * System property the build hands the integration tier, carrying the commit being packaged.
     *
     * <p>Named here rather than repeated, because {@code pom.xml} declares it and a contract test
     * asserts that the two agree.
     */
    public static final String BUILD_REVISION_PROPERTY = "carddemo.build.revision";

    /**
     * What the build declares when it has no trustworthy commit context, which is the state of a plain
     * local build. Matches the default {@code pom.xml} declares for {@code build.revision}.
     */
    public static final String UNSUPPLIED_REVISION = "not-supplied";

    /** What the run identity reads when the build is not running in continuous integration. */
    public static final String LOCAL_RUN = "local";

    /** Environment variable naming the repository the workflow is running in. */
    private static final String REPOSITORY_VARIABLE = "GITHUB_REPOSITORY";

    /** Environment variable naming the workflow run. */
    private static final String RUN_ID_VARIABLE = "GITHUB_RUN_ID";

    /** Environment variable naming which attempt of that run this is. */
    private static final String RUN_ATTEMPT_VARIABLE = "GITHUB_RUN_ATTEMPT";

    /** Environment variable naming the commit the workflow checked out. */
    private static final String COMMIT_VARIABLE = "GITHUB_SHA";

    /** What the host reads when no host name can be resolved. */
    private static final String UNKNOWN_HOST = "unknown-host";

    /**
     * Not instantiable: this type renders one line and holds nothing.
     */
    private GateEvidenceProvenance() {
        throw new AssertionError("GateEvidenceProvenance is a rendering utility and holds no state");
    }

    /**
     * Renders the provenance line for the running build.
     *
     * @return one line, terminated by no newline, naming the build revision, the run and the moment
     */
    public static String stamp() {
        return stamp(System.getProperty(BUILD_REVISION_PROPERTY, UNSUPPLIED_REVISION),
                System.getenv(), Instant.now(), resolvedHostName());
    }

    /**
     * Renders the provenance line from explicitly supplied inputs.
     *
     * @param buildRevision the commit the build was told it was packaging; blank or {@code null} reads
     *                      as unsupplied
     * @param environment   the environment to read the run identity from; never {@code null}
     * @param at            the moment to record; never {@code null}
     * @param host          the machine to record; blank or {@code null} reads as unknown
     * @return the rendered line
     */
    static String stamp(final String buildRevision, final Map<String, String> environment,
            final Instant at, final String host) {
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(at, "at must not be null");
        return "Build provenance: revision " + presentOr(buildRevision, UNSUPPLIED_REVISION)
                + ", run " + runIdentity(environment)
                + ", recorded " + at.truncatedTo(ChronoUnit.SECONDS)
                + " on " + presentOr(host, UNKNOWN_HOST) + ".";
    }

    /**
     * Names the workflow run, or says the build is local.
     *
     * <p>The commit the workflow checked out is stated alongside the run because the two answer
     * different questions: the run says where to find the logs and the artefacts, and the commit says
     * what was measured. They are normally the same commit the build revision names, and when they are
     * not - a merge-commit checkout, for instance - a reader can see both rather than one.
     *
     * @param environment the environment to read
     * @return the run identity
     */
    private static String runIdentity(final Map<String, String> environment) {
        final String runId = environment.get(RUN_ID_VARIABLE);
        if (runId == null || runId.isBlank()) {
            return LOCAL_RUN;
        }
        final StringBuilder identity = new StringBuilder(64);
        identity.append(presentOr(environment.get(REPOSITORY_VARIABLE), "unnamed-repository"))
                .append('#').append(runId.strip());
        final String attempt = environment.get(RUN_ATTEMPT_VARIABLE);
        if (attempt != null && !attempt.isBlank()) {
            identity.append(" attempt ").append(attempt.strip());
        }
        final String commit = environment.get(COMMIT_VARIABLE);
        if (commit != null && !commit.isBlank()) {
            identity.append(" on commit ").append(commit.strip());
        }
        return identity.toString();
    }

    /**
     * Returns a present value, or the supplied stand-in when it is absent or blank.
     *
     * @param value    the value
     * @param fallback what to read instead when the value says nothing
     * @return the value or the stand-in
     */
    private static String presentOr(final String value, final String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /**
     * Resolves the host name without a network lookup that could block.
     *
     * <p>Reads the environment's own idea of the machine name rather than resolving an address. A
     * reverse lookup can wait on a name server, and a gate-evidence file is not worth a stalled build;
     * the name is a label for a Gate 3 figure and not an address anything connects to.
     *
     * @return the host name, or a stated stand-in
     */
    private static String resolvedHostName() {
        final String hostName = System.getenv("HOSTNAME");
        if (hostName != null && !hostName.isBlank()) {
            return hostName.strip();
        }
        final String computerName = System.getenv("COMPUTERNAME");
        return computerName == null || computerName.isBlank() ? UNKNOWN_HOST : computerName.strip();
    }
}
