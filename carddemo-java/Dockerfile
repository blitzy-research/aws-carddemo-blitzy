# Copyright Amazon.com, Inc. or its affiliates.
# All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# You may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# either express or implied. See the License for the specific
# language governing permissions and limitations under the License
# =============================================================================
# carddemo-java - container image for the migrated CardDemo application.
#
# WHAT THIS FILE IS
#   A two-stage image definition. Stage one compiles and packages the module
#   with the committed Maven Wrapper on an Eclipse Temurin 25 JDK; stage two
#   ships the result on an Eclipse Temurin 25 JRE as an unprivileged process.
#   The build context is this directory (carddemo-java/), so the legacy z/OS
#   estate under app/ is outside the context by construction and no COBOL, JCL,
#   BMS, copybook or CSD text is - or can be - copied into the image.
#
#   This file has no legacy antecedent. The mainframe estate it replaces has no
#   container concept; its only lineage is the module it packages. The z/OS
#   compile-and-link job streams under samples/ are its conceptual ancestor
#   only in the sense that they, too, turned source into a deployable artifact.
#
# HOW TO USE IT
#   Build : APP_VERSION="$(./mvnw -q -DforceStdout help:evaluate \
#             -Dexpression=project.version)" \
#           SOURCE_REVISION="$(git rev-parse HEAD)" \
#           SOURCE_DATE_EPOCH="$(git log -1 --format=%ct)" \
#           docker build --build-arg APP_VERSION --build-arg SOURCE_REVISION \
#             --build-arg SOURCE_DATE_EPOCH \
#             -t carddemo-java:local .
#   Run   : docker run --rm -p 8080:8080 -e SPRING_PROFILES_ACTIVE=local \
#             ... (see docker-compose.yml for the full local wiring) \
#             carddemo-java:local
#   Local : docker compose up -d          (brings up PostgreSQL 16 + LocalStack
#                                          Community and supplies the profile)
#
# WHAT THIS IMAGE DELIBERATELY DOES NOT DO
#   * It carries no credential, no secret and no default for one. Every secret
#     is supplied at run time by the orchestrator; a missing one must fail
#     startup rather than silently bind a placeholder.
#   * It sets no heap size and no RAM percentage. Throughput and peak-memory
#     figures are measured against this image, not presupposed by it, so baking
#     a heap bound would fabricate the very baseline that measurement exists to
#     establish. JAVA_TOOL_OPTIONS is left free for that purpose (see below).
#   * It bakes no active Spring profile. The profile is a deployment decision;
#     docker-compose.yml sets "local" and a real deployment sets its own.
#   * It runs no test. See the packaging step for why, and for the sharp
#     distinction between skipping tests and weakening the compiler.
# =============================================================================

# The image identity has no safe default. A version copied into this file would drift from pom.xml,
# and a source revision copied here would describe the commit that edited the Dockerfile rather than
# the commit a later build packages. Callers must supply both; each stage redeclares them explicitly.
ARG APP_VERSION
ARG SOURCE_REVISION
ARG SOURCE_DATE_EPOCH=1658188800


# --------------------------------------------------------------- stage 1: build
# Eclipse Temurin 25 JDK - a JDK, not a JRE, because this stage compiles.
#
# Pinned twice on purpose. The tag names the exact Temurin build (25.0.3+9) so a
# human can read what this is; the digest makes the reference immutable so the
# same bytes are used on every machine and in every rebuild. A floating tag such
# as :latest, or even a moving :25-jdk, would silently change the toolchain
# underneath a build that is required to be reproducible.
FROM eclipse-temurin:25.0.3_9-jdk-noble@sha256:8a7eef57766680298c451d4c6158d6541cc4f4a17a3d216a23940ec1f24885ee AS build

ARG APP_VERSION
ARG SOURCE_REVISION
ARG SOURCE_DATE_EPOCH

WORKDIR /workspace

# Refuse missing, malformed or sentinel identity before any dependency resolution. The source
# revision is the full lowercase object name emitted by `git rev-parse HEAD`; accepting an
# abbreviation would make two repositories capable of producing the same label. The version grammar
# intentionally admits Maven's ordinary release and qualifier characters but no whitespace or shell
# metacharacter.
RUN set -eu; \
    case "$APP_VERSION" in \
      '' | *[!0-9A-Za-z._+-]*) \
        echo 'FATAL: APP_VERSION must be the exact Maven project version' >&2; exit 1 ;; \
    esac; \
    case "$SOURCE_REVISION" in \
      *[!0-9a-f]*) \
        echo 'FATAL: SOURCE_REVISION must be a 40-character lowercase Git SHA' >&2; exit 1 ;; \
    esac; \
    [ "${#SOURCE_REVISION}" -eq 40 ] \
      || { echo 'FATAL: SOURCE_REVISION must be a 40-character lowercase Git SHA' >&2; exit 1; }; \
    [ "$SOURCE_REVISION" != '0000000000000000000000000000000000000000' ] \
      || { echo 'FATAL: SOURCE_REVISION must not be the all-zero sentinel' >&2; exit 1; }; \
    case "$SOURCE_DATE_EPOCH" in \
      '' | *[!0-9]*) \
        echo 'FATAL: SOURCE_DATE_EPOCH must be a positive integer epoch second' >&2; exit 1 ;; \
    esac; \
    [ "$SOURCE_DATE_EPOCH" -gt 0 ] \
      || { echo 'FATAL: SOURCE_DATE_EPOCH must be a positive integer epoch second' >&2; exit 1; }

# Dependency layer first, sources second. Maven resolution is by far the most
# expensive step and depends only on pom.xml, so it gets a layer of its own that
# survives every source-only edit. Copying src/ before resolving would re-fetch
# the whole dependency tree on every single code change.
#
# Only the wrapper needs to come across for this step: .mvn/wrapper holds the
# single pinned declaration of the Maven version, its download URL and its
# SHA-256, and mvnw is the launcher that reads it. distributionType=only-script
# means there is no maven-wrapper.jar to copy - the launcher resolves, verifies
# and caches the distribution itself.
#
# No `maven:` base image, and no Maven installed from a distribution package:
# the build tool is the one committed here, at the one version this repository
# pins, so the image build and a developer's `./mvnw` are the same Maven. That
# is also why nothing is installed into this stage at all - the wrapper needs no
# unzip, no curl and no wget (it falls back to the JDK for download, digest and
# zip extraction when they are absent), so this stage never contacts a distro
# package repository and has exactly one network dependency: Maven Central.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# chmod is insurance, not ceremony: git records the executable bit, but a
# checkout made on a filesystem that cannot represent it would otherwise fail
# here with a bare "permission denied".
RUN chmod +x ./mvnw \
 && ./mvnw -B -ntp dependency:go-offline

COPY src/ src/

# Package the executable jar.
#
# TESTS ARE SKIPPED HERE, WARNINGS ARE NOT. Read that as two separate facts:
#
#   -DskipTests skips test EXECUTION only; test sources are still compiled, so
#   they remain subject to the same compiler strictness as main sources. Test
#   execution is skipped because the integration and end-to-end tiers stand up
#   real PostgreSQL 16 and LocalStack Community containers through
#   Testcontainers, and a Docker build cannot start containers. Those tiers are
#   discharged by `./mvnw -B clean verify` in CI and against the Compose stack,
#   which is where the end-to-end, contract, coverage and CVE gates are proven.
#   Reading -DskipTests as a shortcut would therefore be a misreading.
#
#   Nothing here weakens the compiler. pom.xml compiles with
#   <release>25</release> under -Xlint:all -Werror, and that configuration is
#   the whole mechanism by which "a clean checkout produces a deployable
#   artifact with zero warnings" is enforced by the compiler rather than
#   asserted by a reviewer. Not one property on this command line disables the
#   werror behaviour, downgrades the lint set, or silences a compiler
#   diagnostic, and none may ever be added: if the image build tolerated a
#   diagnostic that CI rejects, the image would not be the artifact CI
#   verified.
RUN ./mvnw -B -ntp clean package -DskipTests \
    -Dbuild.revision="$SOURCE_REVISION" \
    -Dsource.date.epoch="$SOURCE_DATE_EPOCH"

# The build-information file is the artifact's own statement of identity. Read it back before the
# image crosses the stage boundary and require both caller-supplied values to agree exactly. This is
# what prevents a valid-looking OCI label from being attached to a jar of another version or revision.
RUN set -eu; \
    info='target/classes/META-INF/build-info.properties'; \
    [ -f "$info" ] || { echo "FATAL: $info was not generated" >&2; exit 1; }; \
    [ "$(grep -c '^build.version=' "$info")" -eq 1 ] \
      || { echo 'FATAL: build-info must carry exactly one build.version' >&2; exit 1; }; \
    [ "$(grep -c '^build.source-revision=' "$info")" -eq 1 ] \
      || { echo 'FATAL: build-info must carry exactly one build.source-revision' >&2; exit 1; }; \
    [ "$(grep -c '^build.time=' "$info")" -eq 1 ] \
      || { echo 'FATAL: build-info must carry exactly one deterministic build.time' >&2; exit 1; }; \
    actual_version="$(sed -n 's/^build.version=//p' "$info")"; \
    actual_revision="$(sed -n 's/^build.source-revision=//p' "$info")"; \
    actual_time="$(sed -n 's/^build.time=//p' "$info" | tr -d '\\')"; \
    expected_time="$(date -u -d "@$SOURCE_DATE_EPOCH" '+%Y-%m-%dT%H:%M:%SZ')"; \
    [ "$actual_version" = "$APP_VERSION" ] \
      || { echo 'FATAL: APP_VERSION does not match the packaged Maven version' >&2; exit 1; }; \
    [ "$actual_revision" = "$SOURCE_REVISION" ] \
      || { echo 'FATAL: SOURCE_REVISION did not reach the packaged build identity' >&2; exit 1; }; \
    [ "$actual_time" = "$expected_time" ] \
      || { echo 'FATAL: SOURCE_DATE_EPOCH did not reach the packaged build time' >&2; exit 1; }

# Explode the layered jar so the runtime stage can copy it as four layers whose
# churn rates differ: third-party dependencies almost never change, application
# classes change on every commit. Docker then re-pushes and re-pulls only the
# small application layer instead of a single ~90 MB blob. It also leaves the
# dependency jars visible as individual files in the final image, so a container
# scanner sees the same artefacts the build-time dependency audit examined,
# rather than one opaque nested archive.
#
# The jar is located, never named. Its filename embeds the module version, so
# hardcoding it here would break this build the first time the version changes;
# instead exactly one packaged jar is required to exist and the build fails
# loudly - not silently, and not on some later layer - if that is not true.
# Candidates are collected by a glob rather than by `find` piped into a counter,
# for two reasons: a pipeline reports only its last stage's exit status, so a
# failing search would be indistinguishable from a successful count of zero, and
# `set -o pipefail` does not exist in the POSIX shell this RUN executes under.
# Every expansion below is quoted, so the step needs no lint exception and would
# still behave if an artefact name ever contained a space.
RUN set -eu; \
    jar=''; \
    for candidate in target/*.jar; do \
      case "$candidate" in *-sources.jar | *-javadoc.jar) continue ;; esac; \
      [ -f "$candidate" ] || continue; \
      [ -z "$jar" ] || { echo "FATAL: more than one packaged jar in target/: '$jar' and '$candidate'" >&2; exit 1; }; \
      jar="$candidate"; \
    done; \
    [ -n "$jar" ] || { echo 'FATAL: no packaged jar found in target/' >&2; exit 1; }; \
    echo "Extracting layers from $jar"; \
    java -Djarmode=tools -jar "$jar" extract --layers --launcher --destination extracted


# ------------------------------------------------------------- stage 2: runtime
# Eclipse Temurin 25 JRE - a JRE, not a JDK. It is materially smaller and, more
# importantly, it removes javac and the rest of the development toolchain from
# what ships, so the compiler is not part of the running attack surface. Pinned
# by tag and digest for the same reasons as the build stage.
FROM eclipse-temurin:25.0.3_9-jre-noble@sha256:0b32bb744e7ef5df10b4ca1a6fd24a34cb6298efe435d7abdef1b6a4e48e940f AS runtime

# Redeclared because global arguments are available to FROM interpolation but do not otherwise enter
# a stage. Both values were already validated against the packaged build-information file above.
ARG APP_VERSION
ARG SOURCE_REVISION

# image.revision records the source commit this artefact was produced from, and
# the legacy-estate label records the upstream mainframe release stamp carried
# in the trailer comment of the COBOL and JCL members. Together they are the
# container-level expression of the same provenance the traceability matrix and
# the decision log cite, so an image found in a registry can be traced back to
# both its Java source and the estate it reproduces.
LABEL org.opencontainers.image.title="carddemo-java" \
      org.opencontainers.image.description="AWS CardDemo mainframe estate migrated to Java 25 LTS and Spring Boot 3.5.16" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${SOURCE_REVISION}" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.vendor="Amazon.com, Inc. or its affiliates" \
      org.opencontainers.image.source="https://github.com/Blitzy-Sandbox/blitzy-card-demo" \
      org.opencontainers.image.base.name="eclipse-temurin:25.0.3_9-jre-noble" \
      org.opencontainers.image.base.digest="sha256:0b32bb744e7ef5df10b4ca1a6fd24a34cb6298efe435d7abdef1b6a4e48e940f" \
      com.carddemo.legacy-estate.release="CardDemo_v1.0-15-g27d6c6f-68 (2022-07-19)"

# Dedicated unprivileged account. Fixed uid/gid so the identity is stable across
# rebuilds and can be granted permissions on a volume without guessing. A home
# directory is created and owned by the account because $HOME must be writable
# if any library ever reaches for it - the application directory below must not
# be.
RUN groupadd --system --gid 10001 carddemo \
 && useradd  --system --uid 10001 --gid 10001 \
             --create-home --home-dir /home/carddemo \
             --shell /usr/sbin/nologin carddemo \
 && install --directory --owner=10001 --group=10001 --mode=0750 /var/lib/carddemo/batch

WORKDIR /app

# Only the packaged application crosses the stage boundary - the four exploded
# layers and nothing else. No sources, no target/ tree, no wrapper, no pom.xml,
# no Maven cache, and none of config/ or localstack/ (those are bind-mounted
# into the Prometheus, Grafana and LocalStack containers by Compose and have no
# business in the application image; .dockerignore keeps them out of the build
# context as well).
#
# Ordered coldest-first so the layers that rarely change sit underneath the ones
# that change every commit.
#
# Left owned by root and readable-but-not-writable by the runtime account, which
# is deliberate: the application logs only to stdout (there is no file appender
# anywhere in logback-spring.xml) and writes batch staging only under the separately owned and mounted
# /var/lib/carddemo/batch path, so a process that cannot modify its own bytecode loses no capability it
# actually uses.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

# Numeric, so an orchestrator enforcing "must not run as root" can verify it
# without resolving /etc/passwd.
USER 10001:10001

# Documentation of the port the embedded server listens on. server.port defaults
# to 8080 and Compose maps it through unchanged.
EXPOSE 8080

# Liveness at the container boundary, so a dependent waiting on
# `condition: service_healthy` is waiting on something real.
#
# The mechanism is chosen from what this image actually contains, verified
# rather than assumed: the Temurin JRE image ships neither curl nor wget, and
# /bin/sh is dash, which has no /dev/tcp. bash is present, so the check is a
# bash networking redirection - no HTTP client is installed, keeping the runtime
# stage free of a package manager, of a distro network dependency, and of the
# extra CVE surface an HTTP stack would add.
#
# It asserts the HTTP status line rather than grepping the body for "UP":
# Actuator answers 200 when the aggregate status is UP and 503 when it is not,
# so the status line cannot be fooled by a healthy component inside an unhealthy
# whole. The health endpoint aggregates the database check, which is exactly
# what a dependent service should wait for. APP_PORT is honoured so the check
# follows a relocated server port.
#
# The start period is generous because startup runs the Flyway migrations before
# the server accepts connections; failures inside it do not count against
# --retries, while a success inside it marks the container healthy immediately.
HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=3 \
  CMD ["/usr/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${APP_PORT:-8080} && printf 'GET /actuator/health HTTP/1.1\\r\\nHost: 127.0.0.1\\r\\nConnection: close\\r\\n\\r\\n' >&3 && head -n 1 <&3 | grep -q '^HTTP/1\\.[01] 200'"]

# Exec form, so the JVM is PID 1 and receives SIGTERM directly. Wrapping this in
# `sh -c` would put a shell at PID 1 that does not forward signals, and the
# application's graceful shutdown - configured to drain in-flight work for up to
# 30 s - would never be triggered; the container would be killed instead. The
# shipped Compose stack grants 35 s before SIGKILL, leaving a five-second margin
# over the application phase timeout. Other orchestrators must preserve the same
# greater-than relationship rather than inheriting Docker's ten-second default.
#
# The class named is the launcher of the exploded layered application; the
# working directory above is its classpath. Only the two determinism flags are
# fixed here, and both are correctness settings rather than tuning: they keep
# timestamps and text in the fixed-width output files independent of whatever
# locale and zone the host happens to have, which is a precondition for
# byte-for-byte comparison against the expected-output fixtures.
#
# JAVA_TOOL_OPTIONS is intentionally left unset. It is the supported channel for
# an operator's own JVM flags - including heap bounds while benchmarking - and
# the JVM reads it itself, so it still applies to this exec-form entrypoint. The
# image ships no such flag of its own.
ENTRYPOINT ["java", \
            "-Duser.timezone=UTC", \
            "-Dfile.encoding=UTF-8", \
            "org.springframework.boot.loader.launch.JarLauncher"]
