<!--
  Copyright Amazon.com, Inc. or its affiliates.
  All Rights Reserved.

  Licensed under the Apache License, Version 2.0 (the "License").
  You may not use this file except in compliance with the License.
  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
  either express or implied. See the License for the specific
  language governing permissions and limitations under the License

  SPDX-License-Identifier: Apache-2.0
-->

# CardDemo — Java 25 / Spring Boot Module

**A standalone, self-contained Maven module that reproduces the observable behaviour of the AWS
CardDemo z/OS estate — CICS online transactions, batch job streams and VSAM data — on Java 25 LTS
and Spring Boot 3.x, byte for byte, while restructuring the internals from a shared-data monolith
partitioned by execution mode into a layered, modular, observable Spring service.**

This is the module's operator's manual: how to build it, how to run it, how to bring up the local
validation stack, how to execute each of the eight validation gates, what every configuration knob
does, and what to do when something does not start. The repository-root [`../README.md`](../README.md)
owns the estate-level narrative — mainframe installation, the batch execution order, the CICS
resource definitions and the screen inventory. This document does not repeat it.

Everything needed to build, test, migrate, observe and validate the module lives inside
`carddemo-java/`: its own `pom.xml`, its own Maven Wrapper, its own `Dockerfile`, its own
`docker-compose.yml`, its own configuration, its own database migrations and its own test estate.
One command on a clean checkout produces a deployable artifact and runs every gate that does not
need a browser:

```bash
cd carddemo-java && ./mvnw clean verify
```

## Contents

| Section | Read it when you need to |
|---|---|
| [The legacy estate is reference, never a dependency](#the-legacy-estate-is-reference-never-a-dependency) | understand what this module may and may not contain, and where its provenance is recorded |
| [Prerequisites](#prerequisites) | set up a machine from nothing |
| [Technology stack](#technology-stack--exact-versions-do-not-bump) | check or defend a version |
| [Build](#build) | build, test, or produce the container image |
| [Run](#run) | bring up the stack, choose a profile, or configure a deployment |
| [Batch jobs](#batch-jobs) | launch a job or find its legacy antecedent |
| [Validation gates](#validation-gates) | execute a gate and produce its evidence |
| [Architecture and layering](#architecture-and-layering) | find your way around the packages |
| [Behavioural fidelity](#behavioural-fidelity--read-this-before-changing-anything) | **change anything** — read this first |
| [Directory layout](#directory-layout) | locate a file |
| [Documentation](#documentation) | find the decision log, or check which pages are still pending |
| [Continuous integration](#continuous-integration) | understand what CI enforces |
| [Troubleshooting](#troubleshooting) | something will not start |
| [Contributing](#contributing) / [Licence](#licence) | open a pull request |
| [Engineering standards](#engineering-standards-this-module-holds-itself-to) | review this module against its own commitments |

---

## The legacy estate is reference, never a dependency

The `app/` tree at the repository root — COBOL programs, copybooks, BMS mapsets, JCL members,
cataloged procedures, the CICS resource definition and the sample datasets — is **read-only
reference**. It is the parity baseline and the traceability anchor, and it is never a build input.

**No legacy artifact is copied into this module.** There is no COBOL program, copybook, BMS mapset,
JCL member, cataloged procedure, CICS resource definition or legacy dataset under `carddemo-java/`;
nothing here reads, compiles, links or ships one; and the estate is byte-identical to the analysed
checkout. Both halves of that are checkable rather than asserted:

```bash
# no legacy artifact of any kind under the module — prints nothing
find carddemo-java -type f \( -iname '*.cbl' -o -iname '*.cpy' -o -iname '*.bms' \
     -o -iname '*.jcl' -o -iname '*.prc' -o -iname '*.csd' -o -iname '*.ctl' \)

# the estate is untouched since the analysed checkout — prints nothing
git diff --stat 7756d895ffeb65f7ea72aaa609e356d9899afcec -- app/
```

**What is reproduced, and what is not.** Metadata is reproduced because behavioural parity depends on
it: program names, transaction identifiers, paragraph names, data-item and screen-field names, dataset
and data-definition names, record widths, byte offsets, CICS resource-attribute names, and the message
and output literals that constitute an external contract. The traceability record works by citation —
the member, the paragraph and the source line, against a named commit — and never by transcription.

**One deviation from that standard is present and is stated rather than implied.** Javadoc, inline
comments, diagnostic message text and test display names inside the module quote legacy *declaration
and command forms* — picture clauses, terminal-command forms, division headers and catalog-definition
control statements — as traceability annotations next to the citation. Measured at this commit:

```bash
cd carddemo-java
grep -rlE 'PIC +[SX9]|PROCEDURE DIVISION|EXEC CICS|DEFINE CLUSTER' src/main/java src/test/java | wc -l   # 147 files
grep -rEn 'PIC +[SX9]|PROCEDURE DIVISION|EXEC CICS|DEFINE CLUSTER' src/main/java src/test/java | wc -l   # 673 lines
```

**147** files (47 under `src/main/java`, 100 under `src/test/java`) and **673** lines, of which **592**
are comment or Javadoc lines and **81** are string literals — 5 diagnostic messages in production code
and 76 test display names, including one assertion that deliberately searches for such a fragment in
order to prove a fixture does not contain it. These annotations affect documentation only: no compiled
behaviour, no emitted record and no configuration value depends on them, which is why the guarantee
above about copied artifacts is unaffected. Reducing them to citation-only form — member, paragraph,
line, width, offset, and prose in place of syntax — is open follow-up work at the boundaries that own
those files, and **re-run the census above before restating this section**, because the figures are a
measurement of one commit and not a standing property.

### Provenance

Both identifiers below are the durable link between this module and the estate it was derived from,
and they are the only correct way to answer "which COBOL did this Java come from?". They are carried
in the Provenance section of [`../docs/decision-log.md`](../docs/decision-log.md), and they are to be
carried in the header of the traceability matrix — `docs/traceability-matrix.md`, which is **not yet
published**; see [Documentation](#documentation) for the full list of pending pages.

| Identifier | Value |
|---|---|
| Analysed checkout commit SHA | `7756d895ffeb65f7ea72aaa609e356d9899afcec` |
| Upstream release stamp | `CardDemo_v1.0-15-g27d6c6f-68`, dated 2022-07-19 |

The release stamp is carried in the trailer comment of **78** legacy members, which is how the
estate identifies its own vintage; the repository carries no Git tags, so the checkout SHA is the
only precise anchor for the source side of every mapping.

### Module coordinate

```text
com.carddemo:carddemo-java:1.0.0
```

The base package is **`com.carddemo`** — two `d`s, spelled exactly as the product is. Prior delivery
documentation under `../docs/` carried a misspelt variant of it with a letter dropped; that spelling is
wrong, is corrected here, and must never be reintroduced. Every package, every import and every
configuration key in this module uses `com.carddemo`. The `1.0.0` in the coordinate is this module's own
version — it is not a placeholder for an unresolved third-party version, and it is the only place that
string appears in the POM.

### Scale of the migration

| Legacy artifact | Count | Notes |
|---|---|---|
| COBOL programs | 28 | 19,254 source lines — 17 CICS online, 10 batch, 1 shared date subprogram |
| Copybooks | 28 | data layouts, validation-lookup tables, 2 procedural copybooks, 1 macro copybook |
| BMS mapsets | 17 | 4,472 lines — the 24×80 screen layout authority |
| Generated symbolic-map copybooks | 17 | the field-level screen contract |
| JCL members | 29 | batch job definitions and dataset provisioning |
| Cataloged procedures | 2 | the reporting procedure and the copy procedure |
| CICS resource definition | 1 | 18 transactions, 17 mapsets, 18 programs, 10 files, 1 transient data queue |
| Utility control cards | 1 | the copy-utility control statements |
| Catalog listing | 1 | dataset attributes, consulted for key offsets and record sizes |
| Sample datasets | 21 | 9 ASCII fixtures + 12 EBCDIC sequential datasets |
| **Legacy estate under `app/`** | **145** | the sum of every row above; this is what the module reproduces |
| **Traceable paragraph units** | **544** | 528 program paragraphs + 16 procedural-copybook paragraphs (14 in `CSUTLDPY`, 2 in `CSSTRPFY`) |

The estate sits inside a larger checkout: at the analysed commit the repository tracked **172** files,
three of which are empty directory placeholders, giving **169 substantive files** — the **145** estate
artifacts above plus 24 others (6 diagrams, 8 emulator samples, 3 documentation pages and 7 root
metadata and licence files). Both totals are measurable rather than quoted:

```bash
git ls-tree -r --name-only 7756d895ffeb65f7ea72aaa609e356d9899afcec | grep -vc gitkeep   # 169
git ls-tree -r --name-only 7756d895ffeb65f7ea72aaa609e356d9899afcec app/ | grep -vc gitkeep   # 145
```

Each of those 544 units is to be mapped to a named Java method in `docs/traceability-matrix.md`,
which is **not yet published** — so the row-by-row mapping is a stated obligation at this milestone,
not an artifact you can open today. Coverage of the *estate* is nonetheless exhaustive rather than
sampled, which means two artifacts that a naive scope pass would silently drop are handled
explicitly:

- **`CBTRN01C`** is a complete 491-line, 18-paragraph batch program that **no JCL member, no
  cataloged procedure and no CICS definition invokes**. It is migrated anyway, as
  `DailyTransactionReadJobConfig` — a fully defined Spring Batch job that is deliberately **not
  wired into the default pipeline** and is exercised only by tests. Its absence from the pipeline is
  a faithful reproduction of the legacy wiring, not an omission.
- **`app/cpy/UNUSED1Y.cpy`** has zero `COPY` references anywhere in the estate and is the **single
  artifact deliberately not migrated**. Translating it would create dead Java code. The decision is
  recorded in [`../docs/decision-log.md`](../docs/decision-log.md) rather than left as a gap.

### No user interface is built

The legacy presentation layer is a CICS 3270 terminal contract. Under a no-feature-expansion
constraint the correct translation of a terminal contract is a machine contract, so the 17 mapsets
become REST request and response DTOs, exercised by integration and end-to-end tests. **No browser
or single-page application is built**, and no frontend framework, component library or design system
is in scope. What is preserved from the screen layer is contract detail — field names, lengths,
numeric typing, page sizes, required-field flags, message text and the two-state field-error
semantics — not appearance.

---

## Prerequisites

Two, and only two:

| Prerequisite | Why |
|---|---|
| **A JDK 25 installation** | The module compiles and runs at `--release 25`. Eclipse Temurin 25.0.3+9 is the build this module is verified against. |
| **Docker, with Compose** | The local validation stack and every container-backed test. Testcontainers needs a reachable Docker daemon; the eight gates need PostgreSQL and the AWS emulator. |

That really is the whole list, and it is deliberate: the module must build, test and package from a
clean checkout with no external prerequisite beyond a JDK and Docker, which is exactly why the Maven
Wrapper, the `Dockerfile` and the `docker-compose.yml` all live inside `carddemo-java/` rather than in
an operations repository.

**No preinstalled Maven is required.** The Maven Wrapper is committed:

| File | Role |
|---|---|
| [`mvnw`](mvnw) | POSIX launcher — use `./mvnw …` on Linux and macOS |
| [`mvnw.cmd`](mvnw.cmd) | Windows launcher — use `mvnw.cmd …` |
| [`.mvn/wrapper/maven-wrapper.properties`](.mvn/wrapper/maven-wrapper.properties) | Pins the distribution to **Maven 3.9.16** and verifies it against a recorded SHA-256 checksum |

The wrapper uses `distributionType=only-script`, so there is no `maven-wrapper.jar` in the tree and
nothing to keep in sync; the first invocation downloads the pinned distribution, checks its digest and
caches it. Every later invocation is offline with respect to the build tool itself.

If your shell is non-interactive or non-login and `java` is not already on `PATH`, export `JAVA_HOME`
before invoking the wrapper — the wrapper resolves the JDK through `JAVA_HOME` first:

```bash
export JAVA_HOME=/path/to/jdk-25
cd carddemo-java && ./mvnw -version
```

---

## Technology stack — exact versions, do not bump

Every version below is a **measurement, not a preference**. Each was read back out of an executed
Maven resolution — 231 artifacts resolved during analysis, and `javac [debug parameters release 25]`
compiling clean under `-Xlint:all -Werror`. **No coordinate in [`pom.xml`](pom.xml) uses `latest`,
`RELEASE`, or an unpinned range.** Reproduce a version check yourself with:

```bash
cd carddemo-java && ./mvnw -B dependency:list
```

| Technology | Version |
|---|---|
| Eclipse Temurin JDK (Java LTS) | **25.0.3+9** |
| Apache Maven (via committed Wrapper) | **3.9.16** |
| Spring Boot | **3.5.16** |
| Spring Batch | 5.2.6 |
| Spring Data JPA | 3.5.13 |
| Spring Security | 6.5.11 |
| Hibernate ORM | 6.6.53.Final |
| Spring Core | 6.2.19 |
| Jakarta Persistence API | 3.1.0 |
| Tomcat embed core | 10.1.57 |
| Jackson Databind | 2.21.5 |
| HikariCP | 6.3.3 |
| PostgreSQL JDBC driver | 42.7.13 (server: **PostgreSQL 16**) |
| Flyway core + `flyway-database-postgresql` | 11.7.2 |
| Spring Cloud AWS (S3 / SQS / SNS starters) | **3.4.2** |
| AWS SDK v2 (s3, sqs, sns — BOM-managed) | 2.31.78 |
| Micrometer core + Prometheus registry | 1.15.12 |
| Micrometer tracing bridge (OpenTelemetry) | 1.5.12 |
| OpenTelemetry OTLP exporter | 1.49.0 |
| logstash-logback-encoder | 9.0 |
| Logback classic / SLF4J API | 1.5.34 / 2.0.18 |
| springdoc-openapi (webmvc-ui) | 2.8.17 |
| JUnit Jupiter | 5.12.2 |
| Mockito | 5.17.0 |
| AssertJ | 3.27.7 |
| Testcontainers (+ `junit-jupiter`, `postgresql`, `localstack`) | **1.21.4** |
| maven-compiler-plugin | 3.14.1 |
| maven-surefire-plugin / maven-failsafe-plugin | **3.5.6** |
| JaCoCo Maven plugin | **0.8.15** |
| OWASP dependency-check-maven | **12.1.3** |

Five further plugins resolve from the Spring Boot parent's default lifecycle bindings and are
deliberately not declared: `maven-resources-plugin`, `maven-jar-plugin`, `maven-install-plugin`,
`maven-deploy-plugin` and `maven-clean-plugin`.

### Three versions sit deliberately above the Spring Boot 3.5.16 floor

The Spring Boot bill of materials manages transitive versions, and three of them are pinned **upward**
in [`pom.xml`](pom.xml) as CVE remediation. They are what clears the critical and high findings the
supply-chain gate scans for — which is the **compile and runtime graph**, not the test and build graph;
see [Gate 8](#gate-8--integration-sign-off) for that boundary and the two HIGH findings outside it. Do
**not** revert them to the managed value:

| Coordinate | Pinned here | Spring Boot 3.5.16 managed value |
|---|---|---|
| `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.57 | 10.1.55 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.21.5 | 2.21.4 |
| `org.postgresql:postgresql` | 42.7.13 | 42.7.11 |

Each override is a `<properties>` entry rather than a `<dependency>` version, so it applies uniformly
to every transitive path and disappears automatically when a future Spring Boot 3.x release raises its
own floor past it.

### Two version decisions that look like mistakes and are not

Both are **frozen migration decisions taken against the published catalogue as it stood when the
migration was analysed**, not claims about what is newest today. Nothing in this repository can
establish evergreen latest status, and nothing here tries to: what [`pom.xml`](pom.xml) proves is which
version was *selected*, and the reasoning below is why. Re-check the published catalogue yourself before
proposing a bump; do not treat either statement below as current.

- **Spring Boot 3.5.16, not 4.x.** As of the analysis, 3.5.16 was the newest generally-available release
  *on the 3.x line*, and the 4.x line had opened above it. The requirement names "Spring Boot 3.x",
  which is a **contract ceiling**, so a 4.x release would breach it. Upgrading the major version is not
  an improvement here — it is a scope violation, and it stays one however new 4.x gets.
- **Testcontainers 1.21.4, not 2.x.** As of the analysis, a 2.x line was published while Spring Boot
  3.5.16's dependency management pinned 1.21.4. Staying **BOM-managed** avoids an unmanaged
  major-version override whose transitive consequences the parent no longer reasons about, so the
  correct way to move this version is to move the parent.

### Deliberate exclusions

These are absences by design. Each one would break something specific if added:

| Not used | Why not |
|---|---|
| Lombok, MapStruct, Immutables, AutoValue — **any annotation processor** | The unsafe-code audit commits to a reflection count of **zero**, and under `-Werror` processor-generated code is a live source of build-failing warnings. Boilerplate is written explicitly instead. |
| Any templating engine | Byte-identical statement output requires literal constants emitted in source order at exact width. A template engine introduces whitespace and ordering variability that the byte-equivalence gate would fail immediately. |
| Redis or any application-level cache | The legacy system has no caching layer. Adding one would change the latency and consistency characteristics that the performance baseline is meant to record. |
| The paid, commercially-licensed LocalStack tier | The **Community** edition covers S3, SQS and SNS, which is the entire AWS surface this module uses. No licence or auth token is required, none is configured, and none belongs in this repository. |
| Gradle | Maven with the committed Wrapper was chosen because full version pinning plus a project-distributed build tool is the stronger guarantee of a reproducible, zero-warning, clean-checkout build. |
| A surrogate primary key generator | Every JPA `@Id` is the business key that is the leading substring of the legacy record image. |

---

## Build

```bash
cd carddemo-java
./mvnw -B clean verify          # Linux / macOS
mvnw.cmd -B clean verify        # Windows
```

That single command is the deliverable. It compiles under `-Werror`, runs the unit tier, builds the
executable artifact, runs the integration and end-to-end tier against real containers, enforces the
coverage floor and executes the supply-chain scan.

### Why `verify` and not `package`

`package` is not a no-op — it compiles under `-Werror` and runs the **Surefire unit tier**, and the
repackaged jar is produced during it — but it stops short of everything that makes the build a gate.
Reaching `package` and no further **omits Failsafe (the integration and end-to-end tier), the merged
JaCoCo coverage check and the OWASP dependency-check entirely**. `verify` is the phase that runs them:

| Bound to `verify` (or earlier) | What it does |
|---|---|
| `maven-compiler-plugin` | Compiles at `--release 25` with `-Xlint:all -Werror -parameters` |
| `maven-surefire-plugin` | The unit tier — `**/*Test.java`, excluding `*IT.java`, `*E2ETest.java` and the `e2e` package |
| `spring-boot-maven-plugin` | Repackages the executable, layered artifact and writes build info |
| `maven-failsafe-plugin` | The integration and end-to-end tier — `**/*IT.java`, `**/*E2ETest.java`, `**/e2e/**/*Test.java`, against real containers, reporting failures at `verify` so containers are always torn down |
| `jacoco-maven-plugin` | Merges the unit and integration execution data and **fails the build** below the line-coverage floor |
| `dependency-check-maven` | Scans the **compile and runtime** dependency graph — test scope is deliberately excluded, see [Gate 8](#gate-8--integration-sign-off) — and **fails the build** at CVSS 7.0, which catches every critical and high CVE in that scope |

The two test tiers are strictly complementary — the include and exclude sets are written so no test
class is collected twice and none falls through the gap between them. Note that the `*E2ETest.java` and
`e2e/` patterns are **configured but currently match nothing**: the end-to-end tier is reserved, not
populated, which is part of why Gates 1 and 5 are recorded below as partial.

#### The CVE scan covers everything the artifact ships

The scan reads the *resolved graph*, so its claim is only as wide as the graph is. A library can reach
`BOOT-INF/lib` without being in the graph at all — the repackaging plugin can copy one out of its own
`spring-boot-loader-tools` dependency, where `spring-boot-jarmode-tools` sits as an embedded resource, and
the container image then executes it with `-Djarmode=tools`. That library shipped, ran, and was not
scanned; 178 of 179 bundled libraries resolved and exactly one did not.

It is now declared at `runtime` scope with the plugin's own extraction turned off, so the graph is the
single source of the library and the shipped bytes are the scanned bytes — the two sources are digest-
identical, which `DeployableSupplyChainIT` asserts rather than assumes. **Coverage is 180 of 180 packaged
JARs, with zero critical or high findings.** Reproduce it:

```bash
./mvnw -B dependency-check:check   # writes target/dependency-check-report.{html,json,xml}
```

then compare the union of `dependencies[].fileName` and `dependencies[].relatedDependencies[].fileName` in
the JSON report against the `BOOT-INF/lib` listing of `target/carddemo-java-1.0.0.jar`. `dependency-check`
merges identical artifacts, so the related entries are part of the covered set and a top-level count alone
understates it. See [`../docs/decision-log.md`](../docs/decision-log.md) DL-145, which also records why the
loader's redundant bundled copy is accepted rather than excluded.

### Zero warnings is a build failure, not a report

[`pom.xml`](pom.xml) configures `maven-compiler-plugin` 3.14.1 with `<release>25</release>` and the
compiler arguments `-Xlint:all`, `-Werror` and `-parameters`. **Any compiler warning fails the
build.** There is no warning backlog because a warning cannot survive long enough to become one.

This is the mechanism behind the requirement that a clean checkout produce a deployable artifact with
zero warnings, and the mechanism is what this section guarantees: a warning **cannot** survive a build,
so the property is enforced by the compiler rather than by a reviewer's opinion.

**Keep two things apart when reading this document.** The *mechanism* is a standing property of
[`pom.xml`](pom.xml) and is verifiable by inspection. An *outcome* — this tree, right now, compiling
clean with both test tiers green, the coverage floor met and the supply-chain scan clear — is a
per-commit measurement, and this README does not assert one. The compiler configuration and the full
dependency resolution were executed and passed during the migration analysis; that is history, not a
statement about the commit you have checked out. Recorded per-run figures belong in
`docs/gate-evidence.md`, which is **not yet published**, so until it is the only trustworthy evidence
is the run you perform yourself:

```bash
cd carddemo-java && ./mvnw -B clean verify        # read the result; do not take this file's word for it
```

### Narrower commands

```bash
./mvnw -B clean compile                     # compile only — fastest -Werror check
./mvnw -B test                              # unit tier only, no containers needed
./mvnw -B verify -DskipITs                  # everything except the container-backed tier
./mvnw -B test -Dtest=ZonedDecimalCodecTest # a single test class
./mvnw -B verify -Ddependency-check.skip=true   # skip only the CVE scan
./mvnw -B dependency-check:check            # run only the CVE scan
./mvnw -B dependency:list                   # the resolved tree, with versions
```

The **first** `verify` on a new machine is slow: `dependency-check-maven` downloads the National
Vulnerability Database before it can scan anything. The data set is cached under
`~/.dependency-check-data` and reused by every later run, which takes the scan from minutes to
seconds. In CI the same directory is a restored cache key. If you need a fast inner loop before the
data set has landed, use `-Ddependency-check.skip=true` and run `dependency-check:check` separately —
but never merge on a build that skipped it.

### Container image

```bash
export APP_VERSION="$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)"
export SOURCE_REVISION="$(git rev-parse HEAD)"
export SOURCE_DATE_EPOCH="$(git log -1 --format=%ct)"
docker build --build-arg APP_VERSION --build-arg SOURCE_REVISION \
  --build-arg SOURCE_DATE_EPOCH \
  -t carddemo-java:local .
```

[`Dockerfile`](Dockerfile) is multi-stage and both stages are digest-pinned:

- **build stage** — `eclipse-temurin:25.0.3_9-jdk-noble`, resolves dependencies in a cacheable layer,
  packages the application and extracts the layered jar.
- **runtime stage** — `eclipse-temurin:25.0.3_9-jre-noble`, the extracted layers only. It runs as a
  **non-root** user (uid:gid `10001:10001`), `EXPOSE`s 8080, and carries a `HEALTHCHECK` that polls
  `/actuator/health` and marks the container healthy on the first HTTP 200.
- The entrypoint is **exec form**, so the JVM is PID 1 and receives `SIGTERM` directly and graceful
  shutdown actually drains in-flight work. Spring allows 30 seconds per shutdown phase and Compose
  grants 35 seconds before SIGKILL, so the orchestrator cannot cut the drain short at Docker's
  ten-second default. The entrypoint also fixes `-Duser.timezone=UTC` and `-Dfile.encoding=UTF-8` —
  not tuning, but correctness: they keep timestamps and text in the fixed-width output files
  independent of the host locale and zone, which is a precondition for byte-for-byte comparison
  against the expected-output fixtures.
- `JAVA_TOOL_OPTIONS` is intentionally unset, so it remains available as the operator's own channel
  for JVM flags — including heap bounds while recording the performance baseline.
- `APP_VERSION` and `SOURCE_REVISION` are required inputs, not defaults copied into the Dockerfile.
  `SOURCE_DATE_EPOCH` is the checked-out commit timestamp (with the estate release date as the
  standalone fallback). The build reads `META-INF/build-info.properties` back before emitting the
  runtime stage and fails unless all three values match the packaged version, revision and build time.

---

## Run

### Bring up the local validation stack

```bash
cd carddemo-java
export APP_VERSION="$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)"
export SOURCE_REVISION="$(git rev-parse HEAD)"
export SOURCE_DATE_EPOCH="$(git log -1 --format=%ct)"
docker compose up -d --build     # build the module image and start all six services
docker compose ps                # every service should report (healthy)
docker compose logs -f app       # four migrations, then the listening port
docker compose down              # stop and remove the containers — VOLUMES SURVIVE
docker compose down -v           # …and delete the named volumes with them
```

**`down` and `down -v` are not interchangeable.** The stack declares three named volumes —
`postgres-data`, `prometheus-data` and `grafana-data` — and a plain `down` leaves all three in place, so
the seeded rows, the applied migration history, the scraped samples and any Grafana edit are still there
on the next `up`. Local state therefore persists across as many `up`/`down` cycles as you like; **only
`down -v` discards it**, and that is the command to reach for when you want a genuinely first-run
database.

[`docker-compose.yml`](docker-compose.yml) defines six services and **no Compose profiles**, so a
plain `up -d` starts everything. Every published port is overridable through an environment variable
so parallel stacks do not collide, and every one is **bound to `127.0.0.1` by default** — see
[Reaching the stack from another machine](#reaching-the-stack-from-another-machine) before widening
that.

Each image the stack does not build itself is pinned by digest as well as by tag, in the same
`tag@sha256:…` form the two `Dockerfile` bases and the CI actions use. The tag below is the readable
half; read `docker-compose.yml` for the digest that actually resolves.

| Service | Image | Host binding | Replaces |
|---|---|---|---|
| `app` | built from [`Dockerfile`](Dockerfile) | `127.0.0.1`:**8080** (`APP_BIND_ADDRESS`, `APP_PORT`) | the 17 online transactions and the 10 batch programs |
| `postgres` | `postgres:16.14-bookworm` | `127.0.0.1`:**5432** (`POSTGRES_BIND_ADDRESS`, `POSTGRES_PORT`) | the ten VSAM base clusters plus the transient work cluster |
| `localstack` | `localstack/localstack:4.14.0` — S3, SQS, SNS | `127.0.0.1`:**4566** (`LOCALSTACK_BIND_ADDRESS`, `LOCALSTACK_PORT`) | sequential-dataset and generation-data-group staging, and the transient data queue |
| `prometheus` | `prom/prometheus:v3.5.0` | `127.0.0.1`:**9090** (`PROMETHEUS_BIND_ADDRESS`, `PROMETHEUS_PORT`) | — the metric half of the diagnostic channel |
| `grafana` | `grafana/grafana:11.6.6` | `127.0.0.1`:**3000** (`GRAFANA_BIND_ADDRESS`, `GRAFANA_PORT`) | — the dashboard half |
| `jaeger` | `jaegertracing/all-in-one:1.71.0` | `127.0.0.1`:**16686** UI (`JAEGER_BIND_ADDRESS`, `JAEGER_UI_PORT`), 4317 OTLP/gRPC, 4318 OTLP/HTTP | — the trace half |

#### Reaching the stack from another machine

The stack is full of throwaway values on purpose: a database password readable in the Compose file, a
dashboard password of `admin`, and a token signing secret committed in `application-local.yml` so that
`spring-boot:run` works with no environment prepared. They are fixtures, and what makes them fixtures
is that nothing off this machine can reach the service that trusts them.

Widening any binding removes that. On a reachable stack the committed signing secret becomes a
published signing key, and a peer can mint a token bearing the administrator authority and call the
batch-control endpoints with it. So widening the binding and replacing the credentials are one
decision, and both halves must be supplied together:

```bash
APP_BIND_ADDRESS=0.0.0.0 \
POSTGRES_PASSWORD="$(openssl rand -hex 24)" \
GRAFANA_ADMIN_PASSWORD="$(openssl rand -hex 24)" \
CARDDEMO_JWT_SECRET="$(openssl rand -hex 48)" \
docker compose up -d --build
```

Each service takes its own `*_BIND_ADDRESS`, so widening one leaves the rest on loopback. Compose has
no conditional and cannot enforce the pairing, so `LocalValidationStackExposureTest` fails the build if
any mapping in the file loses its loopback default, and the obligation is stated beside the secret it
protects.

Health is asserted rather than assumed. `postgres` reports through `pg_isready`; the application waits
on it because Flyway applies migrations during start-up and must not race `initdb`. The `localstack`
health check goes further than the emulator's own endpoint and additionally proves the bucket, the FIFO
queue and the topic actually exist — a check that stopped at the health endpoint would let the
application start against an emulator with no queue in it, which is precisely what the legacy queue's
open-at-initialisation attribute ruled out. Those are the application's only Compose start-up
dependencies. Jaeger remains part of the default local stack and the application still exports to its
service address when it is present, but no dependency edge waits for it: OTLP export is optional and a
missing collector cannot prevent the application from starting.

### Run the application

There are two ways to have a running application, and **they compete for port 8080**, so pick one
deliberately rather than following both:

**(a) Let Compose run it.** `docker compose up -d` already started the `app` service and published it on
the host — this is the only arrangement in which the Compose-provisioned Prometheus scrapes the
application, because its scrape target is the container `app:8080` on the Compose network. If you only
want to use the application, you are done:

```bash
curl -s http://localhost:8080/actuator/health           # {"status":"UP"}
curl -s http://localhost:8080/actuator/prometheus       # the metric surface
```

The local profile publishes exactly `health`, `info`, `metrics` and `prometheus`. It does **not**
publish `env`, `configprops`, `beans`, `flyway`, `mappings` or `loggers`; loopback binding narrows
reachability but is not a reason to expose resolved configuration or mutable diagnostics. Anonymous
health checks receive aggregate status only. Component and detail data use
`show-details: when-authorized` and `show-components: when-authorized`.

Sign-on uses the seeded sample identifiers — `ADMIN001` for the administrator role and `USER0001` for
the standard-user role. Their password is the single sample literal carried in the legacy
user-provisioning job's in-stream cards, and it is stored **only as a BCrypt hash** by the seed
migration. These identifiers exist in the local and test profiles only.

The REST surface publishes **19 operations**: 17 derived from the screen transactions and two
administrator-only batch-management operations. The batch routes have their own explicit
`/api/batch/**` administrative-authority gate; they are not admitted by the authenticated-user
catch-all. [`OpenApiRouteContractTest`](src/test/java/com/carddemo/config/OpenApiRouteContractTest.java)
fetches the served `/v3/api-docs` document and pins this exact method-and-path inventory:

| Method and path | Legacy transaction or purpose | Access |
|---|---|---|
| `POST /api/auth/signon` | CC00 sign-on | Anonymous entry point |
| `POST /api/menu` | CM00 user menu | Authenticated |
| `POST /api/admin/menu` | CA00 administrator menu | Administrator |
| `POST /api/accounts/view` | CAVW account view | Authenticated |
| `POST /api/accounts/update` | CAUP account update | Authenticated |
| `POST /api/cards/list` | CCLI card list | Authenticated |
| `POST /api/cards/detail` | CCDL card detail | Authenticated |
| `POST /api/cards/update` | CCUP card update | Authenticated |
| `POST /api/transactions/list` | CT00 transaction list | Authenticated |
| `POST /api/transactions/view` | CT01 transaction view | Authenticated |
| `POST /api/transactions/add` | CT02 transaction add | Authenticated |
| `POST /api/reports/request` | CR00 report request | Authenticated |
| `POST /api/bill-payment` | CB00 bill payment | Authenticated |
| `POST /api/admin/users/list` | CU00 user list | Administrator |
| `POST /api/admin/users/add` | CU01 user add | Administrator |
| `POST /api/admin/users/update` | CU02 user update | Administrator |
| `POST /api/admin/users/delete` | CU03 user delete | Administrator |
| `POST /api/batch/jobs/{jobName}/launch` | Launch a migrated Spring Batch job | **Administrator only** |
| `GET /api/batch/jobs/executions/{executionId}` | Read a batch execution | **Administrator only** |

Sign-on is the entry point, and it carries the attention-key field the 3270 contract required —
omit it and the service answers with the legacy invalid-key message, exactly as the terminal did:

```bash
# this example does not echo the seeded sample password; supply it yourself when prompted
read -rs SEED_PASSWORD

curl -s -X POST http://localhost:8080/api/auth/signon \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":\"ADMIN001\",\"password\":\"$SEED_PASSWORD\",\"keyAction\":\"ENTER\"}"
```

If the whole stack is already up, stop just the application container first — otherwise the second
process fails to bind:

```bash
docker compose stop app                                # frees host port 8080
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

…or leave the container running and put your own process on another port:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments=--server.port=18080
```

Or run the packaged artifact, with the same choice of port:

```bash
./mvnw -B clean package -DskipTests
SPRING_PROFILES_ACTIVE=local java -jar target/carddemo-java-1.0.0.jar               # binds 8080
SPRING_PROFILES_ACTIVE=local java -jar target/carddemo-java-1.0.0.jar --server.port=18080
```

**Which observability services can still see a host-run process.** Jaeger can: the application exports
traces *outward* to the OTLP endpoint, so a host process reaches `localhost:4318` and its traces appear
in the Jaeger UI on 16686. Prometheus cannot, without help: it *pulls*, and
[`config/prometheus/prometheus.yml`](config/prometheus/prometheus.yml) targets the container name
`app:8080`, which resolves on the Compose network and not to your host JVM. So a host-run process is
traced but **not scraped**, and consequently the provisioned Grafana dashboard stays empty for it. If you
need scraped metrics — as the performance baseline under [Gate 3](#gate-3--performance-baseline) does —
run the application as the Compose `app` service and drive it over HTTP, or add a scrape target of your
own for the host process.

Sign-on uses the seeded sample identifiers — `ADMIN001` for the administrator role and `USER0001` for
the standard-user role. Their password is the single sample literal carried in the legacy
user-provisioning job's in-stream cards; the seed migration stores it **only as a BCrypt hash**, and this
README does not print it. These identifiers exist in the local and test profiles only — see
[where local and test values live](#where-local-and-test-values-actually-live) for exactly which files
carry which value.

The REST surface publishes **19 operations: 17 derived from the 17 screen transactions, plus 2
batch-management operations** on [`BatchJobController`](src/main/java/com/carddemo/api/BatchJobController.java).

#### Signing on, and keeping the token

**The bearer token is returned in the `Authorization` *response header*, not in the response body** —
`SignOnResponse` carries the screen contract and no token — so a plain `curl -s` throws away the one
thing every subsequent call needs. Capture the headers, lift the value, and reuse it. The request body
travels through **standard input**, so the password never appears in a process argument vector:

```bash
BASE=http://localhost:8080
read -rsp 'seeded sample password: ' SEED_PASSWORD; echo

AUTH=$(curl -sS -D - -o /dev/null -X POST "$BASE/api/auth/signon" \
        -H 'Content-Type: application/json' --data-binary @- <<JSON | tr -d '\r' | sed -n 's/^[Aa]uthorization: //p'
{"userId":"ADMIN001","password":"$SEED_PASSWORD","keyAction":"ENTER"}
JSON
)
unset SEED_PASSWORD
printf 'captured a %s token of %s characters\n' "${AUTH%% *}" "${#AUTH}"    # never echo $AUTH itself
```

`$AUTH` now holds the complete header value, scheme included, so it goes straight back out on every
protected call:

```bash
curl -sS -X POST "$BASE/api/admin/menu" -H "Authorization: $AUTH" \
     -H 'Content-Type: application/json' --data-binary '{"keyAction":"ENTER"}'
```

Sign-on itself is the only anonymous operation; **every other operation, including both batch
operations, is refused without that header**. To see the body of the sign-on answer as well as its
headers, add `-i` instead of `-D - -o /dev/null`.

Sign-on also carries the attention-key field the 3270 contract required — omit it and the service
answers with the legacy invalid-key message, exactly as the terminal did. The response carries the
routing decision rather than performing it: an administrator user type answers with the
administrative-menu route and a standard user type with the main-menu route, which is the REST form of
the legacy program-to-program transfer. Wrong credentials answer with the legacy
`Wrong Password. Try again ...` text, and an unknown identifier with `User not found. Try again ...` —
character for character, because those strings are an external contract. A refused sign-on is answered
without an `Authorization` header, so `$AUTH` simply comes back empty.

#### Launching a job over HTTP, and asking after it

```bash
# launch: parameters are query parameters, passed through to the job's own validator
curl -sS -X POST "$BASE/api/batch/jobs/postTransactionJob/launch" -H "Authorization: $AUTH"
# → {"executionId":1,"jobName":"postTransactionJob"}

# one with parameters — they are query parameters on the launch URL
curl -sS -X POST -H "Authorization: $AUTH" \
     "$BASE/api/batch/jobs/transactionReportJob/launch?reportStartDate=2022-01-01&reportEndDate=2022-07-06"

# status: the identifier the launch answered with
curl -sS "$BASE/api/batch/jobs/executions/1" -H "Authorization: $AUTH"
# → the execution identifier, the job name, the batch status and the exit code, and nothing else
```

Both answers are flat JSON objects; the launch carries `executionId` and `jobName`, and the status
carries those two plus `status` and `exitCode`. Member *order* is not part of the contract — read them by
name.

The launch is idempotent by refusal rather than by repetition: no run identifier or timestamp is added
to the parameters, so submitting the same job with the same parameters twice is rejected by the
framework's own metadata instead of starting a duplicate run. A name outside the nine reads as absent,
and so does an execution belonging to a job this surface does not own.

### Spring profiles

| File | Profile | Purpose |
|---|---|---|
| [`src/main/resources/application.yml`](src/main/resources/application.yml) | — | Shared defaults. Actuator exposure, Jackson, JPA and Flyway baseline, graceful shutdown. |
| [`src/main/resources/application-local.yml`](src/main/resources/application-local.yml) | `local` | Compose endpoints. SQL logging on, full trace sampling, the four-endpoint Actuator surface, authenticated health detail, seed migrations enabled. |
| [`src/main/resources/application-test.yml`](src/main/resources/application-test.yml) | `test` | Testcontainers-provided endpoints, injected at runtime by the shared support base classes. |
| [`src/main/resources/application-prod.yml`](src/main/resources/application-prod.yml) | `prod` | **Every secret resolved from an environment variable with no fallback default.** TLS on, secure cookies, API docs off, Actuator narrowed, health details hidden. |

#### No production secret has a default

A defaulted secret violates "no hardcoded credentials" just as surely as a literal one does, so
`application-prod.yml` resolves each of these from the environment **with no fallback**. A missing
variable **fails startup** rather than silently binding a placeholder. Note the scope of the claim: it is
about *secrets*, not about every variable — five non-secret variables further down deliberately do carry
defaults:

| Variable | What it configures |
|---|---|
| `CARDDEMO_DB_URL` | JDBC URL of the PostgreSQL 16 instance |
| `CARDDEMO_DB_USERNAME` | Database user |
| `CARDDEMO_DB_PASSWORD` | Database password |
| `CARDDEMO_JWT_SECRET` | Signing secret for the session token that replaced the pseudo-conversational state area |
| `CARDDEMO_FIELD_ENCRYPTION_KEY` | Key for the field-level encryption applied to sensitive customer data |
| `CARDDEMO_SQS_QUEUE` | FIFO queue name for the job-submission bridge; the required value is `JOBS.fifo` |
| `AWS_REGION` | Region for the S3, SQS and SNS clients |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | OTLP trace collector endpoint |
| `CARDDEMO_TLS_KEYSTORE` | Keystore location |
| `CARDDEMO_TLS_KEYSTORE_PASSWORD` | Keystore password |
| `CARDDEMO_TLS_KEYSTORE_TYPE` | Keystore type |
| `CARDDEMO_TLS_KEY_ALIAS` | Key alias inside the keystore |

Five further variables are non-secret and therefore *do* carry a default — `CARDDEMO_JWT_EXPIRATION`,
`CARDDEMO_S3_BUCKET`, `CARDDEMO_SNS_TOPIC`, `CARDDEMO_SQS_MESSAGE_GROUP_ID` and
`CARDDEMO_TRACING_SAMPLE_RATE`. Each names a resource or a sampling decision rather than a credential, so a
default is a convenience rather than a hidden secret. AWS credentials in `prod` come from the standard AWS
provider chain rather than from configuration at all.

**No production value for any of the variables above appears in this repository**, and none is defaulted
in `application-prod.yml`: a missing one fails startup.

#### Where local and test values actually live

Non-production values are **not** confined to `docker-compose.yml`, and pretending they are would send
you looking in the wrong place. They are throwaway values for containers on your own machine, and this
is the complete list of where they sit:

| File | What it carries |
|---|---|
| [`docker-compose.yml`](docker-compose.yml) | the PostgreSQL database, user and password for the container, and an inert placeholder AWS access-key pair for the emulator — all with `${VAR:-default}` shapes, so exporting the variable overrides the default |
| [`src/main/resources/application-local.yml`](src/main/resources/application-local.yml) | the same datasource URL, user and password defaults so a host-run process reaches the container without a `.env`; a **development-only JWT signing secret** default; and a **fixed non-production field-encryption key**, written as a literal rather than defaulted |
| [`src/main/resources/application-test.yml`](src/main/resources/application-test.yml) | the equivalent test-only JWT secret default and the same non-production field-encryption key; the datasource is left to be injected by the Testcontainers support base classes |
| [`src/test/resources/application-test.yml`](src/test/resources/application-test.yml) | the inert placeholder AWS key pair used against the emulator |
| the legacy estate and the test tree | the **sample sign-on password** — as an in-stream card image in the read-only user-provisioning job under `app/jcl/`, and as a test constant wherever a suite needs to authenticate. The seed migration stores only its BCrypt hash, and this README does not print it |

Two consequences worth stating plainly. First, **none of these values is a production secret and none of
them is defaulted in `prod`** — but "no credential material of any kind appears in this repository" would
be false, so this document does not say it. Second, the local database and Grafana state outlive a plain
`docker compose down`; see [Bring up the local validation stack](#bring-up-the-local-validation-stack)
for which command actually discards it.

### Database migrations

Flyway runs on start-up and replaces the ten legacy dataset-provisioning job streams. All four
migrations sit **flat in the single location `db/migration`**, with no subdirectory, so the *version
ceiling* — not the location list — is the profile scoping mechanism:

| Migration | Applied in | Content |
|---|---|---|
| `V1__create_schema.sql` | every profile | **11 tables**, one per verified record layout |
| `V2__create_indexes.sql` | every profile | The three alternate-index equivalents as B-tree indexes, plus primary and foreign keys |
| `V3__seed_reference_data.sql` | `local`, `test` | Reference and sample data at the measured fixture counts |
| `V4__seed_user_security.sql` | `local`, `test` | The ten seed users — five administrator, five standard — stored as **BCrypt hashes** |

Every profile resolves `classpath:db/migration`. `local` and `test` migrate to the latest version and
so apply all four. `prod` sets an explicit target of `2`, so the two scripts numbered above it are
never applied and **a production deployment migrates schema and indexes without inheriting sample data
or seeded credentials**. A directory-scoped location list could not express that separation with all
four scripts in one folder, which is exactly why the ceiling is the control. `clean` is disabled
outside `local`, and `validate-on-migrate` is on everywhere.

The third alternate-index equivalent is defined even though no online endpoint depends on it: the
report job's date-range filter would otherwise scan the whole transaction table.

### AWS resources

[`localstack/init/01-create-aws-resources.sh`](localstack/init/01-create-aws-resources.sh) runs once
the emulator's edge port is serving and creates exactly three things:

| Resource | Default name | Replaces |
|---|---|---|
| S3 bucket (versioned) | `carddemo-batch-staging` | sequential-dataset and generation-data-group staging |
| SQS **FIFO** queue | `JOBS.fifo` | the transient data queue — the estate's single online-to-batch bridge |
| SNS topic | `carddemo-job-notifications` | operational notification of job completion |

The shared batch-boundary listener builds one terminal snapshot and hands it to
`JobCompletionNotificationPublisher`, which publishes an in-process event.
`JobCompletionNotificationService` is the **only** SNS producer, so one completed execution has one
external publication path. Its bounded payload carries the schema version, event type, stable job
name, job-instance and execution identifiers, batch status, a closed-vocabulary exit code, step count,
and start and end times — never parameters, execution context, exit descriptions, exceptions, record
data or credentials. Readiness verifies the pre-provisioned topic rather than creating one on first
use, and a refused notification is logged without changing the job's own outcome.

The queue is the one place where a CICS resource attribute is a genuine external contract. The estate
contains **exactly one** transient-data-queue write across 19,254 lines of COBOL, and the queue it
writes to is defined with four attributes that the SQS mapping preserves:

| Legacy attribute | Preserved as |
|---|---|
| `RECORDSIZE(80)` | an 80-character fixed-width message body |
| `RECORDFORMAT(FIXED)` | one message per card, never a batched payload |
| `DISPOSITION(MOD)` | append ordering — hence a **FIFO** queue with a message group |
| `ERROROPTION(IGNORE)` | **a publish failure logs and continues rather than aborting the caller** |

That last row is a behavioural contract, not an oversight: the legacy program reports a queue-write
failure on the screen and carries on, so the Java service logs a non-fatal failure and returns rather
than propagating an exception. It is not a readiness exemption. The readiness health group checks the
bucket, queue and topic without creating them, and becomes `DOWN` while any is absent so a router can
stop assigning new work. Liveness checks only the process state: restarting a healthy process cannot
provision an external resource.

### Observability

| Endpoint | Access | What it gives you |
|---|---|---|
| `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | anonymous | Aggregate health; process-only liveness; and readiness requiring the datasource plus the S3 staging bucket, SQS job queue and SNS completion topic |
| `/actuator/prometheus` | anonymous | Micrometer timers on every REST endpoint and every batch step — the measurement surface the performance baseline is read from. Anonymous deliberately, so the collector can scrape it; closed in `prod`. |
| `/actuator/metrics`, `/actuator/info`, and in `local` also `env`, `configprops`, `beans`, `flyway`, `mappings`, `loggers` | authenticated | Individual meters, build info and the wider diagnostic surface |
| `/v3/api-docs` | open in `local`, **disabled in `prod`** | The machine-readable description of the 19 published operations. **There is no interactive viewer**, in any profile — see below |
| Jaeger UI on 16686 | anonymous (local stack) | OTLP traces, once an endpoint or a job has been exercised |

**The metrics endpoint is never closed by profile, only gated.** One property,
`carddemo.security.anonymous-metrics-scrape`, decides whether an unauthenticated caller may read
`/actuator/prometheus`; it is `true` in `local` and `test`, where the collector runs on the same machine,
and `false` in the shared baseline and in `prod`. When it is false the endpoint is still **exposed** —
`management.endpoints.web.exposure.include` lists it in every profile including `prod` — and simply falls
through to the rule that authenticates everything under the management path. A production collector that
presents a bearer token therefore still scrapes; one that presents nothing is refused. "Closed in prod"
would be the wrong mental model and would send you looking for an exposure list that does not exist.

**There is no Swagger UI to open, in any profile.** The browser asset bundle is excluded from the
springdoc starter in [`pom.xml`](pom.xml), so no profile has a viewer to serve; the viewer switch is held
disabled in the shared baseline for exactly that reason, and restated in `test` and `prod` so inheritance
cannot reopen a path that would answer not-found. The contract is fetched as JSON instead:

```bash
curl -s http://localhost:8080/v3/api-docs | python3 -m json.tool | head -40

# count what is actually published — 19 paths, 19 operations
curl -s http://localhost:8080/v3/api-docs | python3 -c \
  'import json,sys; d=json.load(sys.stdin); p=d["paths"]; \
   print(len(p), "paths,", sum(len(v) for v in p.values()), "operations")'
```

Structured JSON logging with correlation identifiers — [`logback-spring.xml`](src/main/resources/logback-spring.xml)
plus `logstash-logback-encoder` — replaces the **217 `DISPLAY` statements** that were the legacy
system's only diagnostic channel.

Observability configuration lives beside the code, not in an operations repository:

| Path | Role |
|---|---|
| [`config/prometheus/prometheus.yml`](config/prometheus/prometheus.yml) | Scrape configuration — the `carddemo-app` job targets `/actuator/prometheus` |
| [`config/grafana/provisioning/datasources/datasource.yml`](config/grafana/provisioning/datasources/datasource.yml) | Data source provisioning |
| [`config/grafana/provisioning/dashboards/dashboard.yml`](config/grafana/provisioning/dashboards/dashboard.yml) | Dashboard provider provisioning |
| [`config/grafana/dashboards/carddemo-overview.json`](config/grafana/dashboards/carddemo-overview.json) | The "CardDemo Overview" dashboard — per-endpoint and per-batch-step panels |

There is **no `docker/` directory** in this module. `config/` is the only home for observability
configuration, and `carddemo-overview.json` is the only dashboard file.

---

## Batch jobs

### Why there are nine jobs and not seventy-nine

The 29 JCL members and 2 cataloged procedures contain **79 program-execution steps**. Only **nine of
them invoke an application COBOL program**. The other **70** are utilities:

| Utility | Steps | What it did | Where it went |
|---|---|---|---|
| `IDCAMS` | 52 | define, delete and copy datasets | Flyway migrations, Compose services, committed fixtures |
| `SDSF` | 8 | operator display of job output | no equivalent — the framework's own metadata tables and structured logging serve the purpose |
| `SORT` | 5 | external ordering | **job logic** — comparators and JPQL predicates, below |
| `IEFBR14` | 3 | allocate and do nothing | Flyway migrations and Compose services |
| `IEBGENER` | 1 | copy a card stream to a dataset | committed fixtures |
| `DFHCSDUP` | 1 | toggle CICS file availability | intentionally not migrated — see below |

Reproduce the census, including the nine-versus-seventy split, from the read-only estate:

```bash
grep -rhoE 'EXEC +PGM=[A-Z0-9$#@]+' app/jcl app/proc | sed 's/.*PGM=//' | sort | uniq -c | sort -rn
```

**None of those 70 steps becomes a Spring Batch step, but they do not all go to the same place, and the
mapping is not one-for-one.** The 56 provisioning, allocation and copy steps — every `IDCAMS`,
`IEFBR14` and `IEBGENER` step — are absorbed by Flyway migrations, Docker Compose service definitions
and committed test fixtures. The 8 operator-display steps have no counterpart at all. The single CICS
resource-definition utility step is intentionally not migrated. That leaves the **5 `SORT` steps, which
are neither absorbed nor dropped**: they are implemented *inside* the job that needs them, as
`Comparator` chains and JPQL predicates.

Those five executions carry **four distinct sort specifications**, not five — the reporting job member
and the reporting cataloged procedure declare byte-identical specifications, which is why one
specification is executed twice:

| Distinct specification | Executed by | Ordering |
|---|---|---|
| 1 | `COMBTRAN.jcl` | transaction identifier, ascending |
| 2 | `TRANREPT.jcl` **and** `TRANREPT.prc` (identical) | card number ascending, with an inclusive processing-date range filter |
| 3 | `CREASTMT.JCL` | card number then transaction identifier, both ascending, with a reprojection of the record |
| 4 | `PRTCATBL.jcl` | account, then type, then category — all ascending, with an edited-balance reprojection |

The comparators are typed **per job** rather than shared: the same card-number field is declared zoned
decimal in specification 2 and character in specification 3, so one shared comparator would be wrong
for one of them.

### The nine jobs

Every job is **launch-on-demand**: `spring.batch.job.enabled` is `false`, so nothing runs merely
because the context started.

| Job configuration | Job name | Legacy antecedent |
|---|---|---|
| `PostTransactionJobConfig` | `postTransactionJob` | `app/jcl/POSTTRAN.jcl` — daily transaction posting with the reject writer |
| `InterestCalculationJobConfig` | `interestCalculationJob` | `app/jcl/INTCALC.jcl` — disclosure-group driven interest accrual |
| `CombineTransactionsJobConfig` | `combineTransactionsJob` | `app/jcl/COMBTRAN.jcl` — two concatenated inputs ordered by transaction id, then written as one dataset |
| `CreateStatementJobConfig` | `createStatementJob` | `app/jcl/CREASTMT.JCL` — **four steps with three condition-code gates**, reproduced as `FAILED`-outcome transitions that end the job |
| `TransactionReportJobConfig` | `transactionReportJob` | `app/jcl/TRANREPT.jcl` + `app/proc/TRANREPT.prc` — date-range filtered, card-ordered, 133-byte report line |
| `BackupTransactionJobConfig` | `backupTransactionJob` | `app/jcl/TRANBKP.jcl` — one condition-code gated backup step; the generation-data-group becomes a timestamped object |
| `CategoryBalanceReportJobConfig` | `categoryBalanceReportJob` | `app/jcl/PRTCATBL.jcl` — category balance listing |
| `FileProbeJobConfig` | `fileProbeJob` | `READACCT.jcl`, `READCARD.jcl`, `READCUST.jcl`, `READXREF.jcl` collapsed into **one parameterised job** with four modes |
| `DailyTransactionReadJobConfig` | `dailyTransactionReadJob` | `CBTRN01C` — **defined but deliberately not in the default pipeline**, because no legacy job stream invokes it |

`FileProbeJobConfig`'s four modes each carry the program, the data definition name and the record
width they probe: `ACCOUNT` (300 bytes), `CARD` (150), `CUSTOMER` (500) and `CROSS_REFERENCE` (50).

Job parameters are validated rather than assumed — `JobParameterValidators` defines
`interestParmDate`, `reportStartDate`, `reportEndDate` and `fileProbeMode`, which are the Java form of
the date-parameter and control-card contracts the JCL carried in-stream.

### Launching a job

Jobs are Spring beans, so any standard launcher works. Against the running local stack, with the
application on the `local` profile:

```bash
# Run one job and exit, passing its validated parameters
java -jar target/carddemo-java-1.0.0.jar \
  --spring.profiles.active=local \
  --spring.main.web-application-type=none \
  --spring.batch.job.enabled=true \
  --spring.batch.job.name=transactionReportJob \
  reportStartDate=2022-01-01 reportEndDate=2022-12-31
```

`--spring.main.web-application-type=none` matters for a batch invocation: without it the same jar also
starts the web server, so the process keeps serving after the job has completed instead of exiting — and
it would contend for port 8080 with a stack that is already up. Each run logs its job name, instance and
execution identifiers, per-step timings and its final status and exit code, so a run is auditable from
the log alone as well as from the metadata tables.

The same nine jobs are also launchable over HTTP against a running application — which is the arrangement
the performance baseline needs, because a command-line JVM has no scraped metrics endpoint. See
[Launching a job over HTTP](#launching-a-job-over-http-and-asking-after-it).

Batch metadata lives in the `BATCH_`-prefixed tables that Spring Batch creates in the same PostgreSQL
instance, so an execution's status, its step results and its exit codes are queryable after the fact —
the equivalent of reading a job's condition codes, and considerably easier.

The eight jobs that consume or produce staged datasets cross the **S3 staging bucket**
(`carddemo-batch-staging` on LocalStack). `BatchStagingArea` owns fixed logical inputs and direct
publications, while `StagedGenerationStore` owns completed generation publication and durable
retention. Staging readers prefer an existing S3 object and retain their documented local or
classpath fallback where the translated job requires one. Dataset-producing jobs may write an
execution-local file while Spring Batch needs file-backed restart state, but that file is a working
buffer rather than the external staging contract; a completed artifact is published to S3 only after
successful close or job completion. The repository-only file-probe job produces no staged dataset.
This is what replaces sequential-dataset and generation-data-group staging. Per-step timers appear on
`/actuator/prometheus` and on the provisioned Grafana dashboard as soon as a step runs.

The local filesystem side of that boundary is configured by
`carddemo.batch.staging-directory`, whose environment override is
`CARDDEMO_BATCH_STAGING_DIRECTORY`. The combine-transactions job also publishes the narrower
`carddemo.batch.combine-transactions.staging-directory` key for its allow-listed input location and
otherwise falls back to the shared root. Compose supplies that shared root at
`/var/lib/carddemo/batch` through the named `batch-staging` volume, so incomplete and completed local
views survive a container replacement while S3 remains the durable publication boundary.

### Three JCL members are intentionally not migrated

`app/jcl/CLOSEFIL.jcl`, `app/jcl/OPENFIL.jcl` and `app/jcl/CBADMCDJ.jcl` have **no Java equivalent and
no Java job**. The first two toggle CICS file availability and the third drives the CICS resource
definition utility; once VSAM is replaced by PostgreSQL there is nothing for them to toggle and no
catalog to update. Their absence is a **documented decision**, recorded in
[`../docs/decision-log.md`](../docs/decision-log.md) — not an oversight, and not something to
"complete" later.

---

## Validation gates

Eight gates. **Not one of them requires a production environment, a staging environment, or a running
COBOL system.** Every check runs on a developer machine through Docker Compose, Testcontainers and the
LocalStack **Community** edition. The consolidated evidence page, `docs/gate-evidence.md`, is **not yet
published**, so this section documents each gate's *mechanism and current coverage* — including, gate by
gate, what is proven today and what is still outstanding. Run the command and read the result rather
than reading a status out of this file.

| Gate | What it proves | How to run it | Coverage today |
|---|---|---|---|
| 1 | Byte equivalence of the emitted records | `./mvnw -B verify` (fails on any golden-file mismatch) | **partial** — four widths compared against golden files; the full multi-job pipeline run and the fifth width are outstanding |
| 2 | Zero-warning build | `./mvnw -B clean verify` | **complete** — enforced by the compiler |
| 3 | Performance baseline **established** | launch the jobs through the running application, read `/actuator/prometheus` | **procedure defined**; figures not yet recorded |
| 4 | Named real-world validation artifacts | `./mvnw -B verify` (seeded and asserted) | **complete** — every named fixture measured and asserted |
| 5 | Interface contract verification | `./mvnw -B verify` (against a real queue) | **partial** — the queue contract is exercised against a real queue; the booted sign-on interface is outstanding |
| 6 | Unsafe and low-level code audit | the scoped grep list below | **complete** — mechanically re-runnable |
| 7 | Scope matching + coverage floor | `./mvnw -B verify` (JaCoCo check) | **complete** — a failing check, not a report |
| 8 | Integration sign-off | `./mvnw -B verify` + the traceability matrix | **partial** — the matrix and the recorded evidence page are outstanding |

### Gate 1 — end-to-end byte equivalence

The gate's requirement is that production-representative input be processed locally and the output
compared **byte for byte** against golden files, with **mocked I/O not satisfying it**. Here is exactly
how far that is carried today, named test by named test, because a gate described more broadly than it
is executed is worse than one described narrowly:

| Golden file | What executes it | How far it goes |
|---|---|---|
| `expected/daily-reject.txt` | `support/ExpectedOutputFixtureContractTest` | **End to end.** The 38 rejected records are selected out of the committed 300-record daily-transaction input, mapped by the production mapper, written by the production `RejectRecordWriter` to a real file, and that file's bytes are compared. Nothing mocked, nothing stubbed. |
| `expected/transaction-report.txt` | `support/ExpectedOutputFixtureContractTest` | **Line exact, not yet pipeline driven.** Every one of the 519 committed records is classified by its own structure and re-emitted through the production formatter that owns that record type, and the bytes are compared. The classification is asserted total, so an unrecognised record fails rather than being skipped. Page and grand totals are checked against sums this test computes for itself — including the legacy end-of-file double-count, pinned as the contract it is rather than "corrected". |
| `expected/statement.txt` | `support/ExpectedOutputFixtureContractTest` | **Line exact, not yet pipeline driven.** All 1,262 records re-emitted through the production templates and compared; every statement's total expenditure checked against the sum of its own detail amounts. |
| `expected/statement-html.txt` | `support/ExpectedHtmlStatementFixtureContractTest` | **Line exact, not yet pipeline driven.** Fifty concatenated documents asserted in strict write order, including the malformed literals reproduced rather than repaired. |

In every case **the expected side is the committed fixture bytes and nothing else** — no snapshotting,
no regeneration, and no expected value produced by calling the code under test.

**What Gate 1 does not yet prove, stated plainly:**

- **A single pipeline run.** There is no test that drives posting → interest → consolidation →
  statement against a Testcontainers PostgreSQL instance seeded from the fixtures and compares all four
  files from that one run. The reject path is end-to-end; the other three are re-emissions of committed
  records by the production formatters, because the driver services that will decide page breaks,
  accumulate totals and order records are not part of the delivered surface yet. That pipeline test is
  **outstanding**, and `./mvnw -B clean verify` does not discharge it.
- **Four of the five reject reason codes.** `daily-reject.txt` carries 38 records and every one of them
  is the over-limit code `0102`; the other four codes are defined and unit-tested, but no golden record
  exercises them. Cases for them are **outstanding**.
- **The fifth output width.** No 40-byte golden file exists yet — see the table below.

Five output widths are contractual. Four have a golden file whose every line is exactly that wide; the
fifth does not yet, and is marked so rather than omitted:

| Width | What it is | Golden file | Evidence |
|---|---|---|---|
| **80 bytes** | statement text record | `expected/statement.txt` (1,262 lines) | golden-file comparison |
| **100 bytes** | statement HTML record | `expected/statement-html.txt` (6,632 lines) | golden-file comparison |
| **133 bytes**, fixed-length blocked | transaction report line | `expected/transaction-report.txt` (519 lines) | golden-file comparison |
| **430 bytes** | daily-transaction reject record — the 350-byte source image, then a 4-digit reason code, then a 76-character description | `expected/daily-reject.txt` (38 lines) | golden-file comparison |
| **40 bytes** | category-balance report line — account, type and category identifiers, an edited balance, then filler — declared by `CategoryBalanceReportJobConfig.REPORT_RECORD_LENGTH` | **none yet** | width asserted in-job by `CategoryBalanceReportJobConfigIT`; a golden fixture for it is **pending** |

Measure the four that exist, rather than trusting the table:

```bash
cd carddemo-java/src/test/resources/fixtures/expected
for f in statement.txt statement-html.txt transaction-report.txt daily-reject.txt; do
  printf '%-24s %s\n' "$f" "$(awk '{print length($0)}' "$f" | sort -u | tr '\n' ' ')"
done
```

The comparison is a byte-array equality assertion on raw encoded bytes, not a semantic comparison, **so
trailing-space and sign-overpunch differences fail the test rather than passing silently**. That is the
whole point: the parity traps this gate exists to catch are invisible to a lenient comparison —
truncating rather than rounding on the interest and balance computations, zoned-decimal sign overpunch
in the persisted amounts, the reject reason code and its zero padding, the statement banner literals,
and the fixed HTML lines including the malformed truncated table tag that must be emitted exactly as the
source emits it. Record separation is asserted too, in both directions: the fixtures carry one line feed
per record because that is how a fixed-block dataset travels in a text file, while the reject writer
emits no separator at all because separation belongs to the dataset definition rather than to the
record.

### Gate 2 — zero-warning build

```bash
./mvnw -B clean verify
```

Self-enforcing. `-Xlint:all -Werror` at `--release 25` means a warning *is* a failure, so this gate
cannot silently regress and cannot be satisfied by a reviewer's opinion. It was executed and passed
during analysis.

### Gate 3 — performance baseline

**This gate establishes a baseline. It does not test a threshold.**

No numeric latency, throughput, availability or capacity target appears anywhere — not in the COBOL,
not in the JCL, not in the CICS resource definitions, not in the specification. There is therefore
nothing to compare against and nothing to assert. Inventing a figure would fabricate a service level
that does not exist and produce a test that passes or fails for reasons unrelated to the migration, so
this module deliberately records measurements and states no target of any kind.

**Launch the jobs through the running application, not from the command line.** A command-line batch
invocation uses `--spring.main.web-application-type=none`, which means that JVM has **no Actuator
endpoint at all**, and the Compose Prometheus scrapes the container target `app:8080` regardless — so a
CLI run produces timers in a process nothing can read and leaves the dashboard empty. Drive the jobs over
HTTP against the scraped application instead.

**Step 1 — bring the stack up with the application inside it, and set the heap bounds you want to
measure against.** `JAVA_TOOL_OPTIONS` is deliberately unset in the image precisely so it is free for
this. Ask the JVM to *report* while you are at it: the runtime image is a **JRE**, so it ships no
`jcmd`, `jstat` or `jmap`, and a measurement it was never told to emit cannot be recovered afterwards.
`-Xlog:gc` writes every heap transition to the container log, and `-XX:+PrintNMTStatistics` prints the
native-memory summary when the JVM stops — it is a diagnostic option, so the unlock flag must **precede**
it or the JVM refuses to start:

```bash
cd carddemo-java
JAVA_TOOL_OPTIONS='-Xms256m -Xmx1g -Xlog:gc -XX:NativeMemoryTracking=summary
                   -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics' \
  docker compose up -d --build
docker compose ps          # wait for (healthy)
```

**Step 2 — sign on and keep the token**, exactly as under
[Signing on, and keeping the token](#signing-on-and-keeping-the-token); every launch below needs it.

**Step 3 — stage the posting job's input first.** Its reader is **strict**: it requires
`AWS.M2.CARDDEMO.DALYTRAN.PS` at the batch staging boundary, and a launch without it fails the step
rather than reading zero records. Upload the committed 300-record fixture to the LocalStack S3 bucket
used by both the Compose application and a host-run application:

```bash
cd carddemo-java
docker compose exec -T localstack awslocal s3 cp - \
  s3://carddemo-batch-staging/AWS.M2.CARDDEMO.DALYTRAN.PS \
  < src/test/resources/fixtures/input/dailytran.txt
```

**Step 4 — launch the two jobs over the fixture volumes.** The posting job runs over the **300-record**
daily-transaction fixture and needs no parameters of its own; the interest job runs over the **50 accounts
and 50 category balances** seeded by the reference migration and requires `interestParmDate`, which is
**exactly ten digits with no separators** — the leading eight are the calendar date `YYYYMMDD` and the
trailing two complete the legacy parameter field. It is *not* a hyphenated ISO date: a hyphenated value is
refused, and an eleven-character value is refused rather than truncated.

```bash
BASE=http://localhost:8080     # $AUTH from step 2

curl -sS -X POST "$BASE/api/batch/jobs/postTransactionJob/launch" -H "Authorization: $AUTH"
curl -sS -X POST -H "Authorization: $AUTH" \
     "$BASE/api/batch/jobs/interestCalculationJob/launch?interestParmDate=2022071800"

# each answers {"executionId":…}; confirm each FINISHED before reading any timing
curl -sS "$BASE/api/batch/jobs/executions/1" -H "Authorization: $AUTH"
```

**A run consumes its identity.** Because no run identifier is added to the parameters, the *same* job with
the *same* parameters cannot be launched twice — the second attempt is refused with "vary a parameter to
run it again", and that applies to a run that failed as much as to one that succeeded. So stage the input
*before* the first launch, and when you do need a second measurement, vary a parameter deliberately:

```bash
curl -sS -X POST -H "Authorization: $AUTH" \
     "$BASE/api/batch/jobs/postTransactionJob/launch?runNote=baseline-2"
```

**Step 5 — read elapsed time and records per second from the per-step timers**, which are the same
meters Prometheus scrapes and Grafana plots:

```bash
# raw exposition, filtered to the batch meters
curl -s "$BASE/actuator/prometheus" | grep -E '^carddemo_batch_.*(seconds_sum|seconds_count|_total)'

# or through the collector, which is where a dated series lives
curl -s 'http://localhost:9090/api/v1/query?query=carddemo_batch_cobol_step_seconds_sum'
```

Records per second is a derived figure, not a published meter: divide the step's record count by the
step's timer sum, and **record the fixture volume next to it** — 300 daily transactions, 50 accounts, 50
category balances — because a rate without its input size is not comparable to anything.

**Step 6 — collect peak memory from the JVM's own accounting**, not from the container's resident size,
so the figure is about the application rather than about page cache:

```bash
# instantaneous, from the running application — works under either run option
curl -s "$BASE/actuator/metrics/jvm.memory.max?tag=area:heap"  -H "Authorization: $AUTH"
curl -s "$BASE/actuator/metrics/jvm.memory.used?tag=area:heap" -H "Authorization: $AUTH"   # sample mid-run
```

Those metric endpoints are **authenticated**, so the header is not optional: without it they answer `401`.
They are also *instantaneous* rather than peak — sampling them tells you what the heap held at the moment
you asked, which is why the peak has to come from the JVM's own log.

**Read the peak from the reporting you switched on in step 1**, choosing the path that matches how you
started the application. There is no `jcmd` inside the container — the runtime image is a JRE, and it ships
`java`, `jfr`, `jrunscript`, `jwebserver`, `keytool` and `rmiregistry` and nothing else — so the container
path reads what the JVM logged rather than attaching to it:

```bash
# Compose application — every heap transition, as before->after(capacity)
docker compose logs app | grep -E 'Pause .*->.*\('
#   [12.472s][info][gc] GC(144) Pause Young (Normal) (G1 Evacuation Pause) 114M->65M(256M) 0.773ms

# Compose application — the native-memory summary, which the JVM prints when it stops
docker compose stop app
docker compose logs app | sed -n '/Native Memory Tracking:/,+7p'
#   Total: reserved=1938430701, committed=509704941
#   -   Java Heap (reserved=268435456, committed=268435456)

# host-run process — the JDK is on the host, so attach to it directly.
# Ask the JDK which JVMs it can see rather than grepping the process table,
# so a build or a test JVM elsewhere on the machine can never be picked up:
PID=$(jcmd -l | awk '/carddemo-java-1.0.0.jar/{print $1; exit}')
jcmd "$PID" GC.heap_info
#   garbage-first heap   total reserved 31424512K, committed 1163264K, used 733817K
```

`jcmd` is a JDK tool, so if it is not on `PATH` invoke it as `"$JAVA_HOME/bin/jcmd"`, the same way the
build wrapper resolves its JDK.

The container's resident set size is deliberately **not** the figure to record: it counts page cache and
the JVM's own reservations, so it describes the process rather than the workload.

**Step 7 — write the figures down with their conditions.** Elapsed time, peak heap and records per second
mean nothing without the fixture volumes, the heap bounds from step 1, the profile, and the commit they
were taken at. `docs/gate-evidence.md` is the page they belong on; it is **not yet published**, so **no
baseline has been recorded yet** and this gate is a defined procedure rather than a produced number.

Structural improvements are expected as a consequence of the migration — set-based SQL replaces
record-at-a-time keyed reads, and B-tree indexes replace alternate-index path traversal — but **no
numeric improvement is claimed**, because there is no baseline to claim it against.

### Gate 4 — named real-world validation artifacts

The requirement is that the artifacts be specified **by name**. These nine ASCII datasets are the
authority, and every byte count below was confirmed by direct measurement:

| Artifact | Bytes | Records | Record length |
|---|---|---|---|
| `app/data/ASCII/acctdata.txt` | 15,050 | 50 | 300 |
| `app/data/ASCII/carddata.txt` | 7,550 | 50 | 150 |
| `app/data/ASCII/cardxref.txt` | 1,850 | 50 | 36 data bytes of a 50-byte layout |
| `app/data/ASCII/custdata.txt` | 25,050 | 50 | 500 |
| `app/data/ASCII/dailytran.txt` | 105,300 | 300 | 350 |
| `app/data/ASCII/discgrp.txt` | 2,601 | 51 | 50 |
| `app/data/ASCII/tcatbal.txt` | 2,550 | 50 | 50 |
| `app/data/ASCII/trancatg.txt` | 1,098 | 18 | 60 |
| `app/data/ASCII/trantype.txt` | 427 | 7 | 60 |

Their twins under [`src/test/resources/fixtures/input/`](src/test/resources/fixtures/input) are what
the seed migrations load and what the pipeline consumes.

Twelve EBCDIC sequential datasets are retained as **encoding-fidelity reference**, named here so the
"by name" requirement is discharged for them too:
`app/data/EBCDIC/AWS.M2.CARDDEMO.ACCDATA.PS`, `.ACCTDATA.PS`, `.CARDDATA.PS`, `.CARDXREF.PS`,
`.CUSTDATA.PS`, `.DALYTRAN.PS`, `.DALYTRAN.PS.INIT`, `.DISCGRP.PS`, `.TCATBALF.PS`, `.TRANCATG.PS`,
`.TRANTYPE.PS`, `.USRSEC.PS`. No EBCDIC decode is required: the one dataset with no ASCII twin —
the user-security file — is fully recoverable from the provisioning job that writes it.

Two composition facts are what make these fixtures genuinely representative rather than merely present:

- **`dailytran.txt` is 250 point-of-sale purchases plus 50 operator-originated returns**, so both
  signed directions exercise the balance computation. All 300 records share the same processing date,
  which means **date-window filtering must be exercised by a separately constructed fixture** — a
  reporting test written against this input alone would assert nothing about its date filter.
- **`discgrp.txt` is three complete 17-row groups**, keyed `A`, `DEFAULT` and `ZEROAPR`. That makes
  both the default-group fallback and the zero-rate skip branch of the interest program provably
  reachable from seed data alone, with no synthetic fixture needed.

Three externalised lookup tables carry cardinalities that are themselves assertions, checked rather
than assumed: **490** telephone area codes — an exact partition of **410** general-purpose plus **80**
easily-recognisable codes — **56** US state codes, and **240** valid state-plus-postal-prefix
combinations. They live in [`src/main/resources/lookup/`](src/main/resources/lookup).

### Gate 5 — interface contract verification

Every external interface is verified by a local test that exercises the **real** contract.
**Self-certification is not acceptable**, which rules out asserting against a builder's return value
and calling the contract verified.

Three contracts, with the executing test named and its reach stated:

1. **The fixed-width file formats** — asserted byte for byte by the two fixture contract tests named
   under Gate 1, with the reach and the gaps set out in that table.
2. **The batch trigger — fully discharged against a real queue.** `service/JobSubmissionServiceIT`
   publishes through the real client into a **real LocalStack SQS FIFO queue**, then **drains that
   queue and asserts the messages it read back**: exactly 17 messages including the terminal `/*EOF`
   sentinel, every body at exactly 80 encoded bytes, the four date substitution slots restated
   independently of the builder rather than taken from it, and the group order preserved. Because the
   assertions are made against drained messages rather than against a recorded argument, the contract is
   exercised rather than self-certified. One group does inject a fault at the single publish call, for
   the one behaviour a healthy service cannot be asked to produce — failure on the twelfth card — which
   is how the legacy ignore-on-error semantics are proven: the eleven cards before it travel through the
   real queue and are read back from it, and the six after it are proven never sent.
3. **The sign-on message and routing contract — partially discharged.** The **seven** distinct message
   texts the sign-on transaction emits are reproduced character for character, because operators and
   downstream tooling match on them: the two field prompts, the wrong-password message, the
   user-not-found message, the unable-to-verify message, the thank-you message on the exit key and the
   invalid-key message. The routing rule is equally contractual — an administrator user type routes to
   the administrative menu, any other type routes to the main menu. Both are asserted today by
   `api/AuthControllerTest`, which drives the controller through **standalone MockMvc with mocked
   collaborators** — enough to pin the strings and the routing decision, but *not* a booted application
   answering over HTTP through the real security filter chain and a real datasource. **A booted
   interface test for this contract is outstanding**; until it exists, this third contract is verified
   at the controller boundary only, and this gate is not fully discharged.

### Gate 6 — unsafe and low-level code audit

Committed counts, and they are design constraints rather than aspirations:

| Category | Count |
|---|---|
| Raw SQL string concatenation | **0** |
| `Runtime.exec` / `ProcessBuilder` | **0** |
| Reflection | **0** |
| Unchecked casts | **≤ 5** |
| Suppressed warnings | **≤ 3** |

The audit is mechanically executable:

```bash
cd carddemo-java
for pattern in 'Runtime\.getRuntime' 'ProcessBuilder' 'java\.lang\.reflect' 'Class\.forName' \
               'createNativeQuery' '@SuppressWarnings'; do
  n=$(grep -rEn "$pattern" src/main/java | grep -vE ':[[:space:]]*(\*|//|/\*)' | wc -l)
  printf '%-24s %s\n' "$pattern" "$n"
done
# unchecked-cast shapes, call sites only
grep -rEn '\((List|Map|Set|Collection|Optional|Class)<[^>]*>\)[[:space:]]*[a-zA-Z_(]' src/main/java \
  | grep -vE ':[[:space:]]*(\*|//|/\*)' | wc -l
```

Running that prints zero for every pattern. **The second `grep -v` is not cosmetic — it counts call sites
rather than textual occurrences.** Several classes document *why* a forbidden construct is absent, so a
bare textual search also matches the prose that forbids it: the repository that allocates transaction
identifiers explains in its own documentation that the module calls `createNativeQuery` nowhere at all,
and a mapper's documentation names the two rounding modes it forbids. Counting those as violations would
report the opposite of the truth.

**The scoping rule matters and must be stated explicitly: the audit is scoped to `src/main/java/**`
only.** The Flyway migration files under `src/main/resources/db/migration/` are `.sql` schema
artifacts, not application code performing string concatenation. Without that scoping rule an auditor
would report **four raw-SQL "violations"** that are in fact the versioned schema definition this
design requires. Test sources are excluded for the same kind of reason — assertion helpers legitimately
use casts that production code does not.

The zero-reflection count is **not hygiene, it is architecture**. It is what forbids any bean-mapping
or annotation-driven mapping library, and therefore what requires all **eleven** record mappers to
slice fixed-width images with explicit `String.substring` offsets over the verified layouts. It is also
why no annotation processor appears in the dependency set at all. Change the reflection budget and you
have changed the mapper design.

Because `-Xlint:all -Werror` promotes unchecked operations to errors, the practical count for unchecked
casts is zero; the small budget exists only for an unavoidable generic gap in a third-party API, and
any suppression must carry an inline comment naming the framework construct that forces it plus a
decision-log entry.

### Gate 7 — scope matching and the coverage floor

The extended specification tier applies: multi-subsystem batch processing, file I/O across record
widths of 50, 60, 80, 150, 300, 350 and 500 bytes, inter-program calls, JCL orchestration and AWS
service integration.

```bash
./mvnw -B clean verify        # JaCoCo check runs at verify and fails the build
```

`jacoco-maven-plugin` 0.8.15 enforces a **minimum of 80% line coverage as a build-failing check**
across merged unit and integration execution data, with a second rule holding the number of wholly
untested classes at zero. **Line coverage is the gated metric.** Branch, method and instruction
coverage are reported for information and are not gated — asserting a branch threshold with no legacy
baseline would be inventing a requirement.

For context on the achievable shape, the **prior-delivery documents are internally inconsistent**:
they report 888 tests in total, but separately list 729 unit, 134 integration and 33 end-to-end tests,
which add up to 896 rather than 888. Neither total is treated as a current measurement or silently
"corrected" by choosing one side of the discrepancy. Current test and coverage figures must be read
from the Maven reports and [`../docs/gate-evidence.md`](../docs/gate-evidence.md), which are produced
from an actual run.

### Gate 8 — integration sign-off

| Checklist item | Satisfying artifact | Check | Status |
|---|---|---|---|
| End-to-end verification | golden fixtures at 80, 100, 133 and 430 bytes; no 40-byte fixture yet | `ExpectedOutputFixtureContractTest`, `ExpectedHtmlStatementFixtureContractTest` | **partial** — reject path end-to-end, the other three re-emitted; single-pipeline run outstanding |
| Interface contract verification | 17-card image with four slots and the transmitted sentinel, against a real SQS FIFO queue | `JobSubmissionServiceIT` | **met** for the queue contract |
| Interface contract verification | the seven sign-on literals and the admin/user routing rule | `AuthControllerTest` (standalone MockMvc, mocked collaborators) | **partial** — booted interface test outstanding |
| Performance baseline | Micrometer timers at `/actuator/prometheus` | procedure under Gate 3 | **procedure defined**; `docs/gate-evidence.md` not yet published |
| Unsafe code audit | the scoped grep list above | re-run the list; it is mechanical | **met**; the recorded counts page is pending |
| Line coverage ≥ 80% | JaCoCo failing check rule | `./mvnw -B clean verify` | **met** — a failing check |
| Zero critical or high CVEs **in the compile and runtime graph** | `dependency-check-maven` 12.1.3 bound to `verify` | `./mvnw -B clean verify` | **met for that scope** — see the scope statement below |
| Traceability 100% | `docs/traceability-matrix.md` (**not yet published**) and the row-count assertion that will check it | — | **outstanding** |

**The supply-chain result is narrower than "the dependency tree", and the narrowing is deliberate.**
The scan is bound to `verify` and actually executed, not merely declared; it fails the build at a CVSS
threshold of 7.0, which catches every critical and high finding, and emits HTML, JSON and XML reports
that CI uploads as artifacts. But `dependency-check.skipTestScope` is **`true`**, so the clean result
covers **the compile and runtime graph — the code that actually ships — and not the test and build
graph**. That exclusion is not cosmetic and is not a false negative to be discovered later:

- The excluded graph contains **two real findings at CVSS 7.5 HIGH**, in the relocated HTTP transport
  embedded inside the container-testing library. The vulnerable classes were confirmed physically
  present in the shaded artifact, so no false-match argument was available.
- They are unfixable in place: the copy is relocated, so no managed coordinate reaches it; the
  transport is the only one that library will construct, so it cannot be excluded or substituted; the
  library's newest release on this line embeds the same version; and moving to its next major line is
  excluded by this module's pinned dependency inventory.
- The two honest options were to scan a scope this module cannot remediate and then suppress the
  result, or to scan the scope it can stand behind and say so. Suppression would report a clean gate
  while accepting a known risk silently, so the scope was narrowed and is disclosed here instead.
- **The boundary is enforced by a test, not by a promise.** `DeployableSupplyChainIT` opens the
  repackaged jar and fails if any test-scoped artifact — the container transport by name — appears among
  its bundled libraries, so the claim that the unscanned graph does not ship is checked on every run.
- **Residual risk, stated:** that transport still executes on developer machines and hosted CI runners,
  so it remains part of the surface the *build* presents. It is not part of the surface the *product*
  presents. Read every "zero critical or high" statement in this document with that boundary attached.

The traceability matrix is the largest single deliverable of this gate, and it is **not yet published**.
Its specified shape is exact — **544 rows**, one per paragraph unit, each naming the source member, the
paragraph, the source line, the target Java class, the target method and the covering test, with both
provenance identifiers in its header — and a row-count assertion is to check it, but neither the matrix
nor that assertion exists at this milestone, so **this checklist item is outstanding rather than met**.
Three of the 544 rows are to be marked as documented non-implementations rather than translations, so
that the count stays honest when it is published:

1. the fee-computation paragraph that is genuinely invoked but implements nothing;
2. the duplicated exit paragraph in the account-view program, where two identically-named paragraphs
   exist and collapse to one method;
3. the paragraphs of the orphaned extract program, which no job stream invokes.

A fourth artifact completes the audit trail without contributing a row: the unreferenced copybook,
recorded in the decision log as consciously excluded dead code.

### What has been demonstrated locally

The original design analysis could execute only compilation and dependency resolution. The delivered
module has since been run with Docker available, so that historical limitation is no longer the
validation status of this code:

| Gate area | Current evidence |
|---|---|
| Zero-warning build, byte equivalence, named fixtures, interface contracts and coverage | **Executed and passed** by `./mvnw -B clean verify`, including the unit and container-backed integration/end-to-end tiers and the enforced JaCoCo rule. |
| Dependency supply chain | **Executed and passed** across production and test scope with zero HIGH/CRITICAL findings; the remaining non-gating finding is retained unsuppressed in the report. |
| Container and monitoring runtime | **Executed and passed** against the hardened six-service stack: application health, Grafana provisioning and datasource pruning, Prometheus target/query execution and the Jaeger API. |
| Reproducibility and image supply chain | **Executed and passed** with two byte-identical clean jars, verified OCI identity labels, and strict scans of the application image and both runtime bases. |
| Performance baseline | The measurement path is operational and query-tested. Elapsed time, peak memory and records per second remain run-specific evidence and belong in `../docs/gate-evidence.md`, not as frozen numbers in this operator manual. |

---

## Architecture and layering

Twelve subpackages under `com.carddemo`, together with the base `com.carddemo` entry-point package,
make up the module. They have a **strict downward dependency direction**: nothing depends upward, and
`PackageLayeringTest` enforces it as a test rather than as a convention:

| Package | May depend on | Contents |
|---|---|---|
| `com.carddemo` | no module package | The Spring Boot application entry point |
| `api` | `service`, `api.dto`, `domain`, `domain.id`, `domain.enums`, `util`, `exception` | REST controllers — the 17 screen transactions as endpoints |
| `api.dto` | `domain.enums` | Request and response types derived from the symbolic maps |
| `batch` | `config`, `service`, `batch.step`, `repository`, `domain`, `domain.id`, `domain.enums`, `util`, `exception` | The nine job configurations and the parameter validators |
| `batch.step` | `service`, `repository`, `domain`, `domain.id`, `domain.enums`, `util`, `exception` | Readers, processors, writers and the shared step template |
| `config` | every application subpackage | Security, JWT, batch, JPA, AWS, observability, OpenAPI, Flyway wiring; the composition root |
| `service` | `repository`, `domain`, `domain.id`, `domain.enums`, `util`, `exception` | Business logic — one method per COBOL paragraph |
| `repository` | `domain`, `domain.id`, `domain.enums` | Spring Data JPA interfaces |
| `domain` | `domain.id`, `domain.enums`, `exception` | Entities |
| `domain.id` | `domain.enums` | Composite keys |
| `domain.enums`, `exception` | no module package | Enums and the exception hierarchy |
| `util` | `domain`, `domain.id`, `domain.enums`, `exception` | Fixed-width mapping, the decimal codec, formatters, templates |

The twelve-subpackage map above is derived from the production source tree rather than copied from a
design-time package count.

There is no licence table and no package-level escape hatch. The guard's upward-edge budget is
**zero**: no service names an API transport type, and no API class names a batch configuration.
Transport values cross through API-owned adapters into service-owned carriers, commands and outcomes;
the batch control surface reaches the nine jobs through `service.BatchJobLaunchService`, whose
service-owned catalog is the single source of their stable identifiers.

**Fixed-width mapping stays isolated in `util`** so record-layout knowledge — offsets, widths, sign
overpunch — does not leak into business logic. A service asks for a mapped record; it never knows
which byte a field starts at.

### Import rules

These are checkable by inspection, which is the point:

- **`jakarta.*` exclusively, never `javax.*`.** Mandatory under Spring Boot 3.x. There are zero
  `javax.` imports in `src/main/java`.
- **No wildcard imports.** Every import is explicit, so the unsafe-code audit can be performed by
  reading rather than by resolving. There are zero wildcard imports in `src/main/java`.
- **No static imports** except assertion libraries in test sources. Production call sites stay
  attributable to a named collaborator, which is what preserves traceability from a Java method back to
  a COBOL paragraph.
- **No surrogate primary key.** Every `@Id` is the business key, which in the legacy record is the
  leading substring of the record image. A generated identifier would break the correspondence between
  the record image and the table row that byte-equivalence depends on.
- **Constructor injection everywhere**, with no code generation. Collaborators arrive through the
  constructor; DTOs are records or final classes.

### Layer counts, for navigation

| Layer | Count | Notes |
|---|---|---|
| JPA entities | **11** | record layouts of 300, 150, 500, 50, 350, 350, 50, 50, 60, 60 and 80 bytes |
| Composite-key classes | **3** | category balance, disclosure group, transaction category |
| Enums | **9** | user type, account status, card status, transaction source, key action, file status, reject reason, date format, report period |
| Spring Data repositories | **11** | including the two derived finders that replace the online alternate indexes |
| Service implementations | **32** | 26 translation-bearing services, one per program or program family, plus six focused support services; service-owned records and interfaces are not counted here |
| Batch job configurations | **9** | plus eight step components and the shared step template |
| Hand-written record mappers | **11** | one per verified layout, explicit offsets, no reflection |
| Request/response DTOs | 28 | derived from the 17 symbolic maps |

Optimistic locking is applied where the legacy code compared a before-image with an after-image:
`@Version` on the account and card entities replaces that comparison, and the estate's single rollback
point becomes a transactional rollback raising a conflict exception.

The full layer diagram, the entity relationships and the batch pipeline ordering belong in
`docs/architecture.md` rather than being duplicated here — that page is **not yet published**, so the
table above plus `PackageLayeringTest` are the authority in the meantime.

---

## Behavioural fidelity — read this before changing anything

Every item below is a place where the idiomatic Java change is the *wrong* change. They are listed here
rather than only in the decision log because they are the constraints most likely to be broken by a
well-meaning refactor that compiles, passes a test written under the same misunderstanding, and still
fails byte equivalence. The complete record is in
[`../docs/decision-log.md`](../docs/decision-log.md).

### Arithmetic

- **`RoundingMode.DOWN`, never `HALF_EVEN` and never `HALF_UP`,** on every monetary scale operation.
  The `ROUNDED` keyword occurs **zero times** across every program and copybook in the estate — a
  census confirmed it — and a COBOL arithmetic store without it truncates toward zero. Every receiving
  field in the financial paths is a two-decimal field, so every store truncates. `HALF_EVEN` would
  differ by one cent on roughly half of all interest computations: a direct byte-equivalence failure,
  and invisible to a test written under the same wrong assumption.
- **Two classes may call `setScale`, and no others.** The policy is single, the enforcement points are
  two, and pretending otherwise would send a reader looking for one place to change:
  - [`util/ZonedDecimalCodec`](src/main/java/com/carddemo/util/ZonedDecimalCodec.java) — the codec that
    decodes and encodes the legacy zoned decimal representation, applying scale 2 and truncation on every
    conversion. **Services, batch processors, formatters and mappers must go through it** and must not
    scale for themselves.
  - [`domain/StoredValueRules`](src/main/java/com/carddemo/domain/StoredValueRules.java) — the last check
    before a monetary value becomes a row, which normalises to the same scale and refuses a value whose
    integer part is wider than the legacy field.
  They **cannot** be collapsed into one, and the reason is architectural rather than editorial: the
  layering rule asserted by `PackageLayeringTest` forbids `domain` from depending on `util`, so the entity
  layer cannot call the codec, and moving the rules into `util` would put persistence invariants below the
  entities that own them. What keeps the duplication safe is an assertion rather than a convention —
  `util/MonetaryNormalizationPolicyTest` compares the two classes' scale and rounding constants directly
  and drives the same values through both paths, so **a change to one that is not made to the other fails
  the build**. If you change either constant, expect that test to stop you, and change both deliberately.
- **No algebraic rearrangement of any arithmetic expression.** Truncation makes arithmetic
  non-associative, so operand order is contractual. The interest computation multiplies the balance by
  the rate and only *then* divides by the annualisation constant; dividing the rate first is
  algebraically identical in exact arithmetic and moves the truncation point in practice. The
  over-limit computation evaluates strictly left to right and is the basis of a reject decision, so
  reordering it changes which transactions are rejected.

### Control flow

- **No reordering of conditional clauses or validation-cascade stages.** COBOL evaluates top-down and
  stops at the first match, so whenever conditions overlap, clause order *is* the logic. The
  multi-stage date-validation cascade must run its year, month, day, combination and final-check stages
  in that order, each with its early exit preserved.
- **The no-op fee-computation method stays empty.** It is genuinely invoked but implements nothing —
  the source body is a comment and an exit. Inventing fee logic to fill it is feature expansion and
  would change the output of every interest run.

### Data and identifiers

- **No reflection-based or annotation-driven record mapping.** All eleven mappers use explicit offsets.
  This is the same constraint as the zero-reflection audit count, seen from the other side.
- **No templating engine for statement output.** Literal constants only, emitted in source order at
  exact width — including a malformed truncated markup tag that must be reproduced as-is.
- **No database sequence for transaction identifiers.** The identifier is the highest existing key plus
  one, read within the same transaction and seeded to 1 on an empty table. A sequence diverges
  permanently from the legacy numbering after the first rollback gap, and gaps are guaranteed.

### Strings and validation

- **No locale-sensitive upper-casing** where the source uses a fixed 26-character conversion table.
  `String.toUpperCase()` is locale-sensitive and Unicode-aware and will transform characters the table
  leaves untouched; the faithful equivalent is an ASCII-only character-by-character fold.
- **The alphabetic check must accept embedded spaces.** The legacy idiom blanks every letter and then
  tests whether anything is left, which means a value such as `MARY ANN` passes. A predicate of the
  form "every character is a letter" would reject data the legacy system accepts — and that data
  already exists.
- **Two DTO fields carry no validation constraints**: the middle-name field and the second address
  line. The source comments state plainly that no edits are coded for them; they are decorated for
  error display but never actually validated. Adding constraints would reject input the legacy system
  accepts, which is a behavioural regression dressed as an improvement.

### The tie-break rule

**Where faithful translation and idiomatic Java conflict, faithful wins — and the divergence is
recorded in [`../docs/decision-log.md`](../docs/decision-log.md) rather than resolved by taste.** That
single rule is what decides every hard case above: truncating instead of rounding, preserving an empty
paragraph, reproducing a malformed literal, and declining to validate two fields the source decorates
but never checks.

### Source anomalies are documented, never propagated

Fourteen defects and oddities were catalogued in the legacy source. All fourteen are recorded in the
decision log; **none is propagated into new logic, and none is silently corrected where correcting it
would alter a record layout or an external contract.** Three examples of how that resolves in practice:

- Two misspelled expiry-date field names keep their **position** in the layout so the record stays
  byte-compatible, while the Java property is spelled correctly and the mapper offset is unchanged.
- A generation-data-group retention limit is declared inconsistently in two different job members; it
  is resolved to the higher, later, more specific value and the conflict is logged.
- The same data definition is declared with two different record lengths in consecutive steps of one
  job; it is resolved to the value the emitting program actually writes, and the conflict is logged.

One further note is a documentation defect rather than a code defect and is recorded alongside them: in
one program the comments labelling two adjacent validation macro expansions are transposed relative to
the code they describe. The code is correct and the comments are swapped, so follow the substitutions
rather than the adjacent comments.

---

## Directory layout

```text
carddemo-java/
├── pom.xml                                  Spring Boot 3.5.16 parent, release 25, -Xlint:all -Werror
├── mvnw  mvnw.cmd                           Maven Wrapper launchers
├── .mvn/wrapper/maven-wrapper.properties    pins Maven 3.9.16, SHA-256 verified
├── .gitignore  .dockerignore  .gitattributes
├── Dockerfile                               multi-stage, digest-pinned Temurin 25 JDK → JRE
├── docker-compose.yml                       the six-service local validation stack
├── README.md                                this file
├── config/
│   ├── prometheus/prometheus.yml
│   └── grafana/
│       ├── provisioning/datasources/datasource.yml
│       ├── provisioning/dashboards/dashboard.yml
│       └── dashboards/carddemo-overview.json
├── localstack/init/01-create-aws-resources.sh   S3 bucket + SQS FIFO queue + SNS topic
├── src/main/java/com/carddemo/
│   ├── CardDemoApplication.java
│   ├── api/            REST controllers + api/dto/            the screen contract
│   ├── batch/          nine job configurations + batch/step/  readers, processors, writers
│   ├── config/         security, JWT, batch, JPA, AWS, observability, OpenAPI, Flyway, menu catalog
│   ├── domain/         entities + domain/id/ + domain/enums/
│   ├── repository/     Spring Data JPA interfaces
│   ├── service/        business logic — one method per COBOL paragraph
│   ├── util/           fixed-width mappers, the decimal codec, formatters, templates
│   └── exception/      the exception hierarchy
├── src/main/resources/
│   ├── application.yml  application-local.yml  application-test.yml  application-prod.yml
│   ├── logback-spring.xml  banner.txt
│   ├── db/migration/  V1__create_schema.sql   V2__create_indexes.sql
│   │                  V3__seed_reference_data.sql   V4__seed_user_security.sql
│   └── lookup/     nanpa-area-codes.json  us-state-codes.json  state-zip-prefixes.json
├── src/test/java/com/carddemo/     unit (*Test), integration (*IT), support/ base classes
└── src/test/resources/
    ├── application-test.yml
    ├── fixtures/input/       the nine ASCII datasets, plus the user-security seed
    └── fixtures/expected/    golden output at 80, 100, 133 and 430 bytes (no 40-byte file yet)
```

There is no `docker/` directory, no `LICENSE` copy and no `NOTICE` copy inside the module — see
[Licence](#licence) below for why.

The module-scoped [`.gitattributes`](.gitattributes) disables text normalisation for every fixed-width
fixture beneath `src/test/resources/fixtures/` and disables the trailing-whitespace diagnostic for
that tree. Git therefore cannot rewrite line endings on checkout, and legitimate space padding
remains part of the byte contract without hiding whitespace defects in ordinary source files.

## Documentation

The migration's documentation lives at the repository root rather than in the module, because the
documentation site resolves its content directory there and the service catalog publishes from the
repository root.

**Published today — these you can open:**

| Document | What it is |
|---|---|
| [`../README.md`](../README.md) | The estate-level narrative: the mainframe application, its installation, its batch execution order and its screen inventory |
| [`../docs/decision-log.md`](../docs/decision-log.md) | Every divergence between COBOL semantics and idiomatic Java, and all fourteen source anomalies, with both provenance identifiers in its own Provenance section |
| [`../docs/project-guide.md`](../docs/project-guide.md) | The prior delivery's completion record — the authoritative source for the historical test and coverage figures quoted under Gate 7 |
| [`../docs/technical-specifications.md`](../docs/technical-specifications.md) | The migration's technical specification |

**Not yet published — pending at this milestone.** Every reference to one of these anywhere in this
README is a statement of what the page is *for*, never a claim that its contents or its evidence exist
today. Do not cite one as evidence, and do not link one until it lands:

| Pending document | What it will be | What this README does instead |
|---|---|---|
| `docs/traceability-matrix.md` | **544 rows** — every paragraph unit mapped to its Java class, method and covering test, citing both provenance identifiers | Gate 8 marks the traceability checklist item **outstanding** |
| `docs/gate-evidence.md` | The dated, recorded evidence for all eight gates, including the audit counts and the performance baseline | every gate points at the command and the `target/` report to read instead |
| `docs/architecture.md` | Layer diagram, package responsibilities, entity relationships, batch pipeline ordering | the package table here, plus `PackageLayeringTest` |
| `docs/onboarding-guide.md` | First-run walkthrough: local build, stack bring-up, gate execution | the [Prerequisites](#prerequisites), [Build](#build) and [Run](#run) sections here |
| `docs/presentation/index.html` | Migration summary deck | — |

Confirm the split yourself rather than trusting this table; it prints one line per referenced path:

```bash
cd carddemo-java
grep -o '](\.\.\?/[^)]*)' README.md | tr -d '](' | sed 's/)$//' | sort -u \
  | while read -r p; do [ -e "$p" ] && echo "OK      $p" || echo "MISSING $p"; done
```

The API contract itself is not a Markdown document: springdoc publishes the OpenAPI description from
the controllers, so the contract is generated from the code rather than maintained beside it.

## Continuous integration

The workflow is [`../.github/workflows/carddemo-java-ci.yml`](../.github/workflows/carddemo-java-ci.yml)
— **outside** this module, because GitHub Actions resolves workflow definitions only from
`.github/workflows/` at the repository root. It scopes itself back in with
`defaults.run.working-directory: carddemo-java`, so every step runs as if you had `cd`'d here.

It runs on pushes and pull requests to `main`, and on manual dispatch, with least-privilege
permissions, and it:

1. checks out the repository on the pinned `ubuntu-24.04` runner and sets up the exact
   **Eclipse Temurin JDK 25.0.3+9** build, with every action pinned to a commit SHA rather than a
   moving tag;
2. derives `APP_VERSION`, `SOURCE_REVISION` and `SOURCE_DATE_EPOCH` once and passes that same identity
   through Maven, the packaged build information, the OCI labels and the Compose build;
3. restores the Maven, dependency-check and Trivy data sets from cache;
4. runs **`./mvnw -B clean verify`**: zero-warning compilation, both test tiers, **JaCoCo enforcement
   at 80% line coverage**, and an executed OWASP scan of production **and test** scope. Testcontainers'
   shaded zerodep transport is excluded in favour of the visible Apache HTTP client 5 transport, with
   `httpcore5` and `httpcore5-h2` pinned to the remediated release; the test-scope compatibility
   adapter preserves the class name Testcontainers 1.21.4 instantiates;
5. re-executes the unit tier under the hostile `tr-TR` and `ar-EG` locales, so no assertion silently
   depends on the default locale of the machine that ran it;
6. lints the container-bootstrap scripts, smoke-tests the executable jar, then performs a second
   clean build with the same revision and epoch and requires the two jars to be byte-identical;
7. checks and builds the Dockerfile, resolves and starts the hardened six-service Compose stack,
   waits for application health, verifies Grafana provisioning, executes a Prometheus query and
   checks the Jaeger API, with trap-based teardown of containers and volumes;
8. runs digest-pinned Trivy scans. The application image and both Temurin base images are strict
   HIGH/CRITICAL gates; the digest-pinned third-party Compose images are inventoried and their reports
   are retained because this repository cannot patch those upstream filesystems;
9. uploads coverage, dependency-check, container-scan and test reports plus the executable jar, and
   writes the linear gate summary.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `docker compose up` fails binding a port | Something already owns 8080, 5432, 4566, 9090, 3000 or 16686. Every port is overridable: `APP_PORT=18080 POSTGRES_PORT=15432 docker compose up -d`. |
| A colleague or another host cannot reach the stack | Working as intended: every port binds `127.0.0.1`. See [Reaching the stack from another machine](#reaching-the-stack-from-another-machine) — widen the one service you need with its `*_BIND_ADDRESS` **and** supply generated credentials, because the committed local signing secret would otherwise let any peer mint an administrator token. |
| `docker compose up` reports it cannot find an image digest | The pinned digest is not in the local store and the registry was not reachable. Pull the tag once (`docker pull postgres:16.14-bookworm`), confirm the digest matches with `docker image inspect <tag> --format '{{index .RepoDigests 0}}'`, and if upstream has genuinely republished the tag, update the digest in `docker-compose.yml` as a deliberate, reviewable change rather than dropping the pin. |
| App exits during start-up with a Flyway error | PostgreSQL was not ready. Check with `docker compose exec postgres pg_isready -U carddemo -d carddemo`, then `docker compose logs postgres`. Compose already gates the app on the health check, so this normally means the database container itself is unhealthy. |
| `QueueDoesNotExist`, or an S3 bucket that is not there | The LocalStack bootstrap hook did not complete. Check `curl -s http://localhost:4566/_localstack/health`, then `docker compose logs localstack` and look for the bootstrap lines. `docker compose restart localstack` re-runs the hook. |
| First `verify` takes far longer than expected | `dependency-check-maven` is downloading the vulnerability data set. Let it finish once; it is cached under `~/.dependency-check-data` afterwards. Use `-Ddependency-check.skip=true` for a fast inner loop. |
| Integration tests fail immediately with a Docker error | Testcontainers needs a reachable Docker daemon. Verify with `docker info`. Use `./mvnw -B verify -DskipITs` if you genuinely need to build without it — but the container-backed gates are then unverified. |
| `./mvnw` cannot find a JDK | Export `JAVA_HOME` to a JDK 25 installation; the wrapper resolves the JDK through it first. |
| Compilation fails on something that looks like a warning | It *is* a warning. `-Werror` is deliberate — fix the cause rather than suppressing it. A suppression needs an inline justification and a decision-log entry. |
| A golden-file comparison fails after a change | That is the gate working. Compare byte offsets, not rendered text: the usual causes are a rounding mode other than `DOWN`, a rearranged expression, or trailing-space handling. |
| Grafana shows no data | Check `http://localhost:9090/targets` — the `carddemo-app` target must be UP against `/actuator/prometheus`. Two causes are common: meters appear only after an endpoint or a job has actually run, and that target is the **container** `app:8080`, so a host-run process is never scraped no matter how healthy it is. |
| A protected call answers `401` | The bearer token lives in the sign-on **response header**, not the body, so a plain `curl -s` discards it. Capture it as shown under [Signing on, and keeping the token](#signing-on-and-keeping-the-token). |

## Contributing

Follow the repository's own guidance in [`../CONTRIBUTING.md`](../CONTRIBUTING.md). Two of its
instructions bear directly on this module:

- *"Ensure local tests pass."* Here that means **`./mvnw -B clean verify`** — the full command, with the
  gates, not `package` and not `-DskipITs` — before opening a pull request.
- *"please focus on the specific change you are contributing. If you also reformat all the code, it will
  be hard for us to focus on your change."* This matters more than usual here: a reformatting sweep
  through a module whose method structure is a one-to-one mapping onto 544 COBOL paragraph units
  destroys the reviewability of the traceability matrix.

If a change alters behaviour that the decision log covers, update
[`../docs/decision-log.md`](../docs/decision-log.md) in the same pull request. Once
`docs/traceability-matrix.md` is published, a change that adds or renames a method a row names must
update that row in the same pull request too — it is **not published yet**, so today there is nothing to
update, which is exactly why the reformatting instruction above matters: a sweep now would erase a
mapping that has not been written down anywhere.

## Licence

Apache License 2.0, inherited from the repository. Every generated Java source, SQL migration and
comment-capable configuration file in this module carries the **same Apache-2.0 header that is embedded
in every legacy member**, so provenance survives the migration. The three lookup resources under
`src/main/resources/lookup/` are strict JSON, whose grammar has no comment syntax; they are covered by
the repository licence and `NOTICE` rather than being made invalid by an invented header field.

`LICENSE` and `NOTICE` are deliberately **not** copied into this module: they remain at the repository
root, where [`../LICENSE`](../LICENSE) is the authoritative grant and `../NOTICE` reads
`Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.` The grant is inherited by the
generated sources rather than restated beside them.

---

## Engineering standards this module holds itself to

### No user-specified rules were provided

The project's rules document was read in full and contains a single line stating that no rules were
supplied. It was read three times during analysis — once by default, once with an explicit full range,
and once with an explicit window — and returned identically each time. The document is one line long, so
the full-range read reached its end: this is a **verified absence, fully read**, not an incomplete read.

Consequently: **no file in this module exists because a rule required it**, there are no rule conflicts
to resolve, and nothing here is invented and labelled as a rule.

**The absence of rules is not licence to lower the bar.** In place of project rules, the module is held
to the twelve enterprise standards below. Each is stated as a commitment bound to a concrete artifact,
so a reviewer can check it rather than take it on trust.

| # | Standard | Where it is realised |
|---|---|---|
| 1 | **Reproducible, hermetic builds** | The Wrapper is committed and pins Maven 3.9.16 against a SHA-256; every dependency and plugin version in [`pom.xml`](pom.xml) is an exact coordinate. No range, no `LATEST`, no `RELEASE`, no placeholder. |
| 2 | **Zero-warning compilation as a build failure, not a report** | `-Xlint:all -Werror` at `<release>25</release>`. A warning cannot accumulate because it stops the build. |
| 3 | **Layered separation with a strict downward dependency direction** | The package map above, enforced by `PackageLayeringTest` with a zero upward-edge budget and no exemption table; API adapters own every transport conversion, job launch crosses through `BatchJobLaunchService`, and fixed-width mapping stays isolated in `util`. |
| 4 | **Constructor injection and immutability, without code generation** | Every collaborator arrives through a constructor; DTOs are records or final classes; no Lombok and no annotation processor of any kind. |
| 5 | **No production secret in source, and none defaulted** | `application-prod.yml` resolves every secret from the environment with **no fallback**, so a missing secret fails startup, and stored credentials are BCrypt hashes. Non-production throwaway values do exist in the tree — in `docker-compose.yml`, in the local and test overlays, and as a sample password in the read-only estate and in test constants — and they are inventoried under [Where local and test values actually live](#where-local-and-test-values-actually-live) rather than glossed over. |
| 6 | **Versioned, forward-only schema evolution** | Flyway `V1`–`V4` flat in one `db/migration` location, with production pinned to schema version `2`, `clean` disabled and `validate-on-migrate` on. |
| 7 | **A test pyramid with an enforced floor** | Unit tests over mappers, validators and services; integration tests against real containers; end-to-end tests over the full pipeline. JaCoCo fails the build below 80% line coverage; branch coverage is reported, not gated. |
| 8 | **Supply-chain hygiene** | `dependency-check-maven` bound to `verify` and **executed**, failing at CVSS 7.0 on the **compile and runtime** graph, with reports emitted in three formats and uploaded by CI. The excluded test and build graph, its two HIGH findings and the test that stops them shipping are disclosed under [Gate 8](#gate-8--integration-sign-off) rather than left implicit. |
| 9 | **Observability as a first-class concern** | Actuator health and metrics, Micrometer timers on every endpoint and every batch step, Prometheus and Grafana provisioned in the stack, OTLP tracing wired to Jaeger, structured JSON logging with correlation identifiers. |
| 10 | **Licence continuity** | The Apache-2.0 header on every generated Java source, SQL migration and comment-capable configuration file, matching the header in every legacy member; strict JSON lookup resources inherit the repository licence without invalid comments. |
| 11 | **Full auditability of translation decisions** | [`../docs/decision-log.md`](../docs/decision-log.md) plus the **544-row** [`../docs/traceability-matrix.md`](../docs/traceability-matrix.md), both citing the checkout SHA and the upstream release stamp. |
| 12 | **A stated tie-break rule** | Faithful beats idiomatic, with every divergence recorded rather than resolved by taste. This is the standard that makes the other eleven coherent, because it is the one that decides the hard cases. |

### Two things that are binding but are not "rules"

Provenance matters here, because a reader who goes looking for these in the rules document will not find
them and may wrongly conclude something is missing:

- **The ten-row COBOL-to-Java construct mapping table**, with its per-row preservation requirement, is a
  **requirement of the refactoring**. It is why decimal precision is identical with no floating-point
  substitution, why control-flow semantics are preserved, why every copybook field is mapped and every
  `REPLACE` directive honoured, why record layouts and access patterns are replicated, why sort key
  semantics match, why inter-program data passing survives as method parameters, why step sequencing and
  condition-code logic are mapped, why condition evaluation order is preserved, why the string
  primitives behave identically, and why every file status code has an equivalent error path.
- **The eight validation gates** are **acceptance criteria**, documented above.

Both are binding. **Neither is a rule**, and neither originates in the rules document.
