# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
#
# CardDemo Java module - multi-stage image.
#   stage 1 (build)   : Eclipse Temurin JDK 25 + committed Maven Wrapper -> executable jar
#   stage 2 (runtime) : Eclipse Temurin JRE 25, non-root, layered Spring Boot jar
#
# Build:  docker build -t carddemo-java:1.0.0 .
# Run  :  docker run --rm -p 8080:8080 -e SPRING_PROFILES_ACTIVE=local carddemo-java:1.0.0

# ------------------------------------------------------------------ build stage
FROM eclipse-temurin:25.0.3_9-jdk-noble AS build

WORKDIR /workspace

# unzip is REQUIRED by the Maven Wrapper, not optional convenience. When unzip is
# absent, mvnw rewrites the configured distributionUrl from -bin.zip to -bin.tar.gz
# and then still compares the download against distributionSha256Sum, which is the
# digest of the zip - so the build aborts with "distribution might be compromised"
# on a perfectly genuine archive. Installing unzip keeps the wrapper on the pinned
# .zip so checksum verification succeeds. Do not drop this layer: removing it
# re-breaks the container build, and neither the digest nor the URL in
# .mvn/wrapper/maven-wrapper.properties may be changed to compensate (both
# launchers reject any distributionUrl that does not end in -bin.zip).
RUN apt-get update \
 && apt-get install --no-install-recommends -y unzip \
 && rm -rf /var/lib/apt/lists/*

# Dependency layer first so source-only changes do not re-resolve the world.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

# Application sources.
COPY src/ src/

# Tests run in CI (and require Docker for Testcontainers), so the image build packages only.
RUN ./mvnw -B -ntp clean package -DskipTests

# ---------------------------------------------------------------- runtime stage
FROM eclipse-temurin:25.0.3_9-jre-noble AS runtime

LABEL org.opencontainers.image.title="carddemo-java" \
      org.opencontainers.image.description="AWS CardDemo mainframe estate migrated to Java 25 LTS / Spring Boot 3.5.x" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/Blitzy-Sandbox/blitzy-card-demo"

# Unprivileged runtime user.
RUN groupadd --system --gid 10001 carddemo \
 && useradd --system --uid 10001 --gid carddemo --home /app --shell /usr/sbin/nologin carddemo

WORKDIR /app
COPY --from=build --chown=carddemo:carddemo /workspace/target/carddemo-java-1.0.0.jar /app/app.jar

USER carddemo:carddemo
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport -Duser.timezone=UTC -Dfile.encoding=UTF-8" \
    SPRING_PROFILES_ACTIVE=local

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD ["/bin/sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q UP <&3"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
