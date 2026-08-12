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
| [Technology stack](#technology-stack-exact-versions-do-not-bump) | check or defend a version |
| [Build](#build) | build, test, or produce the container image |
| [Run](#run) | bring up the stack, choose a profile, or configure a deployment |
| [Batch jobs](#batch-jobs) | launch a job or find its legacy antecedent |
| [Validation gates](#validation-gates) | execute a gate and produce its evidence |
| [Architecture and layering](#architecture-and-layering) | find your way around the packages |
| [Behavioural fidelity](#behavioural-fidelity-read-this-before-changing-anything) | **change anything** — read this first |
| [Directory layout](#directory-layout) | locate a file |
| [Documentation](#documentation) | find the decision log or the traceability matrix, or check what is still pending |
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
in the Provenance section of [`../docs/decision-log.md`](../docs/decision-log.md) and in the header of
[`../docs/traceability-matrix.md`](../docs/traceability-matrix.md), which is **published** and where
`GateVerificationTest` asserts both of them; see [Documentation](#documentation) for what is delivered and
what is still pending.

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
| CICS resource definition | 1 | 18 transactions, 18 programs, 17 mapsets, 8 files, 2 libraries, 1 transient data queue — counted mechanically from its own `DEFINE` statements |
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

Each of those 544 units is mapped to a named Java method in
[`../docs/traceability-matrix.md`](../docs/traceability-matrix.md), one row per unit, and
`e2e/GateVerificationTest` checks that page against the estate and against this module's own source:
the row count — asserted against the paragraph labels the members **actually declare**, member by member,
rather than against itself — each member's subtotal, both provenance anchors, and, for every row, that the
target class exists, declares the named method, and names a covering test that exists — so the mapping is an artifact you can open and an Coverage of the
assertion that fails if it drifts, rather than a stated obligation.
*estate* is exhaustive rather than sampled, which means two artifacts that a naive scope pass would
silently drop are handled explicitly:

- **`CBTRN01C`** is a complete 491-line, 18-paragraph batch program that **no JCL member, no
  cataloged procedure and no CICS definition invokes**. It is migrated anyway, as
  `DailyTransactionReadJobConfig` — a fully defined Spring Batch job that is deliberately **part of no
  default sequence**, yet **registered in `BatchJobCatalog` as one of the nine launchable jobs** and so
  startable by name through the administrator-only batch-control endpoint, as well as being exercised by
  tests. Both halves are deliberate: translating it honours completeness, and leaving it out of every
  sequence faithfully reproduces the legacy wiring. Launchable but unsequenced is not the same as
  test-only, and the distinction matters to anyone auditing either the pipeline or the job registry.
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

Three properties of that first invocation are worth stating, because all three are enforced rather
than assumed and each fails the build closed rather than continuing:

* **The digest is mandatory.** A missing or malformed `distributionSha256Sum` stops the launcher
  before it fetches anything, rather than downgrading to an unverified install. So is a mismatch.
* **The transport is fixed.** The distribution URL — and any `MVNW_REPOURL` override of it — must be
  `https` and must not embed credentials. `MVNW_USERNAME` and `MVNW_PASSWORD` are offered only to the
  host the URL names, and never over a redirect that leaves `https`.
* **The cache is validated, and installed under a lock.** A cached distribution is used only when its
  launcher, its `lib` directory and a marker naming that exact distribution are all present; an
  incomplete one is set aside and rebuilt. Concurrent first invocations serialise, so one build
  installing the distribution while another waits is safe rather than a race. Set
  `MVNW_LOCK_TIMEOUT_SECONDS` to change how long a waiter waits; the default is 300.

**Host requirement, by launcher.** `mvnw` needs a POSIX shell and, on a machine with neither `wget`
nor `curl`, the JDK it is about to build with — it downloads, digests and unpacks with the JDK rather
than asking for a tool to be installed. `mvnw.cmd` needs **Windows PowerShell 5.1**, which ships with
every supported version of Windows, or **PowerShell 7** as `pwsh.exe`; it resolves the interpreter
from `%SystemRoot%` before `PATH` and fails with a diagnostic that names the dependency when neither
is present. Neither launcher installs or downloads a JDK: the JDK comes from `JAVA_HOME` or `PATH`.

If your shell is non-interactive or non-login and `java` is not already on `PATH`, export `JAVA_HOME`
before invoking the wrapper — the wrapper resolves the JDK through `JAVA_HOME` first:

```bash
export JAVA_HOME=/path/to/jdk-25
cd carddemo-java && ./mvnw -version
```

---

## Technology stack — exact versions, do not bump

Every version below is a **measurement, not a preference**. Each was read back out of an executed
Maven resolution rather than recalled, and the module compiles clean under `-Xlint:all -Werror` as
`javac [debug parameters release 25]`. **No coordinate in [`pom.xml`](pom.xml) uses `latest`, `RELEASE`,
or an unpinned range.**

Reproduce the check yourself — but reach for the command that matches what you are checking, because
**no single command reports all four kinds of version**, and `dependency:list` in particular reports
neither the toolchain, nor the plugins, nor the server images:

```bash
cd carddemo-java
java -version && ./mvnw -v                              # toolchain: the JDK, and the Maven the wrapper provisions
./mvnw -B dependency:list                               # the resolved library graph, coordinate by coordinate
./mvnw -B help:effective-pom | grep -A2 artifactId      # plugin versions, as the build actually resolves them
docker compose config | grep image:                     # the server images, pinned by digest
```

Two figures are sometimes quoted for "how many artifacts" and they count different things, so neither
substitutes for the other. **231** is the resolved-artifact count of a one-off probe taken during analysis
against a different POM state; it is a dated datum and is not re-measured here.
The figure the build reports today is the **167 dependencies** the supply-chain scan enumerates across
the compile, runtime and test graph — one fewer than the 168 recorded before `commons-logging` was
excluded from the three AWS starters (DL-357) — recorded under Gate 8 in
[`../docs/gate-evidence.md`](../docs/gate-evidence.md). Read the current graph from `dependency:list`
rather than from either number.

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
| OpenTelemetry OTLP exporter | 1.56.0 (above the managed 1.49.0 — see DL-358) |
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

### Twelve versions sit deliberately above the managed floor as security remediation

[`pom.xml`](pom.xml) carries a delimited **Security remediation overrides** block, and every managed-version
property inside it is an override the supply-chain gate requires. There are **twelve**. Each one states, in
the comment above it, which coordinate it governs, how that coordinate reaches the classpath, and the
finding the value clears. Do **not** revert any of them to the managed value:

| Property | Pinned here | Governs |
|---|---|---|
| `tomcat.version` | 10.1.57 | the embedded servlet container, reached through the web starter |
| `netty.version` | 4.2.16.Final | the asynchronous transport of the object-storage client |
| `postgresql.version` | 42.7.13 | the database driver |
| `log4j2.version` | 2.26.1 | the logging bridge pulled in transitively |
| `opentelemetry-semconv.version` | 1.43.0 | the semantic-conventions artifact used by the tracing bridge |
| `commons-lang3.version` | 3.20.0 | a transitive utility library |
| `commons-compress.version` | 1.28.0 | the archive library, reached only in test scope |
| `jackson-bom.version` | 2.21.5 | the whole Jackson family, including the databind artifact |
| `tools-jackson.version` | 3.2.1 | the third-line Jackson the logging encoder is built against |
| `docker-java.version` | 3.7.1 | the container-engine client of the container testing library |
| `httpcomponents-core5.version` | 5.4.3 | the HTTP core of the non-shaded container transport |
| `immutables.version` | 2.10.1 | the annotation-only companion that transport requires |

Naming only the most visible of them, or writing the count down here by hand, is what does not survive the
block growing as the scan finds more: a hand-maintained count in a second
document is exactly the thing that does not grow with it. The count and the property list are asserted
against that block by `config/DocumentedSourceCountsTest`, so a thirteenth override either updates this
table or fails the build. Recorded in [`../docs/decision-log.md`](../docs/decision-log.md) DL-316.

Each override is a `<properties>` entry rather than a `<dependency>` version, so it applies uniformly
to every transitive path and disappears automatically when a future Spring Boot 3.x release raises its
own floor past it. The scan they satisfy covers the **whole build graph** including test scope — see
[Gate 8](#gate-8-integration-sign-off) for what is carried by written determination rather than fixed.

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
| Redis or any application-level cache | The legacy system has no caching layer. Adding one would change the latency and consistency characteristics that the performance baseline is meant to record. **This is about caching business data, and the readiness contributors' two-second freshness window is not that**: it bounds how often an *anonymous probe* can reach a cloud provider, holds no application record, and is read by no business path — see [Observability](#observability) and DL-344. |
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
| `dependency-check-maven` | Scans the **compile, runtime and test** dependency graph — `dependency-check.skipTestScope` is `false`, see [Gate 8](#gate-8-integration-sign-off) — and **fails the build** at CVSS 7.0, which catches every critical and high CVE in that scope |

The two test tiers are strictly complementary — the include and exclude sets are written so no test
class is collected twice and none falls through the gap between them. The `*E2ETest.java` and `e2e/`
patterns match **three classes**, all in `src/test/java/com/carddemo/e2e/`:

| End-to-end class | What it drives |
|---|---|
| `BatchPipelineE2ETest` | the committed 300-record daily input through the delivered pipeline on a Testcontainers PostgreSQL instance, comparing every artefact the run produced against its golden file |
| `OnlineTransactionE2ETest` | the sign-on contract and the batch-trigger contract over the real booted boundary and a real LocalStack FIFO queue |
| `GateVerificationTest` | the named Gate 4 artefacts, the Gate 6 audit, the 544-unit coverage invariant and the Gate 8 sign-off record |

#### The CVE scan covers everything the artifact ships

The scan reads the *resolved graph*, so its claim is only as wide as the graph is. A library can reach
`BOOT-INF/lib` without being in the graph at all — the repackaging plugin can copy one out of its own
`spring-boot-loader-tools` dependency, where `spring-boot-jarmode-tools` sits as an embedded resource, and
the container image then executes it with `-Djarmode=tools`. That library shipped, ran, and was not
scanned; 178 of 179 bundled libraries resolved and exactly one did not.

It is now declared at `runtime` scope with the plugin's own extraction turned off, so the graph is the
single source of the library and the shipped bytes are the scanned bytes — the two sources are digest-
identical, which `DeployableSupplyChainIT` asserts rather than assumes. **Coverage is 180 of 180 packaged
JARs.** Exactly one HIGH finding against that graph is **carried by a scoped determination rather than
fixed**, because no patched release of the affected library is published yet: it is represented by a single
rule in [`owasp-suppressions.xml`](owasp-suppressions.xml) with unused-suppression enforcement left on, so
the build fails the moment the rule stops matching. It is set out in full under
[Gate 8](#gate-8-integration-sign-off). Reproduce it:

```bash
./mvnw -B dependency-check:check   # writes target/dependency-check-report.{html,json,xml}
```

then compare the union of `dependencies[].fileName` and `dependencies[].relatedDependencies[].fileName` in
the JSON report against the `BOOT-INF/lib` listing of `target/carddemo-java-1.0.0.jar`. `dependency-check`
merges identical artifacts, so the related entries are part of the covered set and a top-level count alone
understates it. See [`../docs/decision-log.md`](../docs/decision-log.md) DL-228, which also records why the
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
[`../docs/gate-evidence.md`](../docs/gate-evidence.md), which carries the command and the artefact path
for every gate — but a figure recorded there belongs to the machine and the date beside it, so the only
trustworthy evidence for your tree is the run you perform yourself:

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
  `/actuator/health/liveness` and marks the container healthy on the first HTTP 200. The probe asks for
  the **liveness group, not the aggregate**, and the distinction is the whole point: liveness contains
  only this process's own state, so the probe makes no external call and its duration cannot become a
  function of four other services under a five-second timeout. The probe **observes** the transport
  rather than being configured with it: it attempts plaintext over bash's `/dev/tcp` and TLS through
  `openssl s_client`, and passes when **either** returns a 200 status line, so a TLS-enabled process is
  never reported unhealthy by a plaintext request. `SERVER_SSL_ENABLED` is honoured as a **hint** that
  orders the two attempts — set it and the TLS attempt goes first, costing one connection instead of
  two — and never as the switch that decides them. That distinction is the fix for a real defect:
  [`application-prod.yml`](src/main/resources/application-prod.yml) enables transport security as a
  literal in YAML and exports no variable, so a probe that branched on the variable alone fell through
  to plaintext and reported every prod container unhealthy for its whole life.
- The entrypoint is **exec form**, so the JVM is PID 1 and receives `SIGTERM` directly and graceful
  shutdown actually drains in-flight work. Spring allows 30 seconds per shutdown phase and Compose
  grants 35 seconds before SIGKILL, so the orchestrator cannot cut the drain short at Docker's
  ten-second default. The entrypoint also fixes `-Duser.timezone=UTC` and `-Dfile.encoding=UTF-8` —
  not tuning, but correctness: they keep timestamps and text in the fixed-width output files
  independent of the host locale and zone, which is a precondition for byte-for-byte comparison
  against the expected-output fixtures.
- `JAVA_TOOL_OPTIONS` is intentionally unset by both the image and the stack, so it remains available
  as the operator's own channel for JVM flags — including heap bounds while recording the performance
  baseline. **The Compose app service forwards it**, as a key with no right-hand side, which is
  Compose's "pass this through if it is set" form. That line is load-bearing rather than decorative:
  without it Compose consumed the variable during interpolation and never injected it, so
  `JAVA_TOOL_OPTIONS=… docker compose up` left the JVM on its container-default heap ceiling and wrote
  no GC log while appearing to have applied both. `ContainerLifecycleContractTest` asserts the
  forwarding, so this paragraph cannot become untrue without the build failing.
- `APP_VERSION` and `SOURCE_REVISION` are required inputs, not defaults copied into the Dockerfile.
  `SOURCE_DATE_EPOCH` is the checked-out commit timestamp (with the estate release date as the
  standalone fallback). The build reads `META-INF/build-info.properties` back before emitting the
  runtime stage and fails unless all three values match the packaged version, revision and build time.

---

## Run

### Bring up the local validation stack

```bash
cd carddemo-java

# Inspecting, starting the infrastructure, and tearing the stack down need NO exports: every
# variable in docker-compose.yml carries a default, so `docker compose config`, `ps`, `logs`,
# `stop` and `down` all work from a clean checkout.
docker compose config            # interpolates and validates the whole file
docker compose down              # tears the stack down again, at any time

# BUILDING the module image is the one operation that needs real provenance, and it is refused
# without it: SOURCE_REVISION defaults to the all-zero sentinel, which the Dockerfile rejects by
# name. Export the three values and the build stamps the image with the tree it was built from.
export APP_VERSION="$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)"
export SOURCE_REVISION="$(git rev-parse HEAD)"
export SOURCE_DATE_EPOCH="$(git log -1 --format=%ct)"
docker compose up -d --build     # build the module image and start all six services
docker compose ps                # every service should report (healthy)
docker compose logs -f app       # five migrations, then the listening port
docker compose down              # stop and remove the containers — VOLUMES SURVIVE
docker compose down -v           # …and delete the named volumes with them
```

**`down` and `down -v` are not interchangeable.** The stack declares **four** named volumes and a plain
`down` leaves all four in place, so the seeded rows, the applied migration history, the staged batch
generations, the scraped samples and any Grafana edit are still there on the next `up`. Local state
therefore persists across as many `up`/`down` cycles as you like; **only `down -v` discards it**, and that
is the command to reach for when you want a genuinely first-run database.

| Volume | Mounted by | What survives a plain `down` |
| --- | --- | --- |
| `postgres-data` | `postgres` | the seeded rows and the applied migration history |
| `batch-staging` | `app` | the local staging tree the file-producing jobs compose their generations in |
| `prometheus-data` | `prometheus` | the scraped samples, so a dashboard still has history after a restart |
| `grafana-data` | `grafana` | any dashboard or data-source edit made through the UI |

`batch-staging` is the one worth knowing about deliberately: a batch job writes its working file and its
completed generation there before publishing to the object store, so a plain `down` leaves the last run's
local generations behind, and `down -v` is what clears them.

[`docker-compose.yml`](docker-compose.yml) defines six services and **no Compose profiles**, so a
plain `up -d` starts everything. Every published port is overridable through an environment variable
so parallel stacks do not collide, and every one is **bound to `127.0.0.1`** — publishing the stack on a
routable address is unsupported, and
[Reaching the stack from another machine](#reaching-the-stack-from-another-machine) gives the two ways
that are.

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

#### One bundled dashboard plugin is switched off on purpose

`grafana` starts with `GF_PLUGINS_DISABLE_PLUGINS` set to `grafana-lokiexplore-app`, overridable through
`GRAFANA_DISABLED_PLUGINS`. Nothing in this stack explores logs — it ships one metrics data source and one
dashboard — and that bundled application declares a module dependency the page's own import map cannot
resolve, so it put four console errors and one `404 /react/jsx-runtime` on **every** Grafana page, including
the login form. Those entries were proven to have nothing to do with the provisioned data source or
dashboard, and leaving them in place would have taught anyone reading the console to ignore it. With the
plugin off, a console entry seen here is attributable to this project's own configuration, which is what
makes the console usable when a panel looks wrong. Nothing else about the service changes — same image
digest, same read-only root filesystem, same non-root principal, same provisioning, same panels. See
[`docs/decision-log.md`](../docs/decision-log.md) entry DL-361.

#### Container log growth is bounded

Every service declares the `json-file` logging driver **explicitly, with a ceiling**, instead of
inheriting the daemon default — which on a stock daemon is `json-file` with *no* rotation, meaning a
container log grows until the filesystem holding it is full. That is not a hypothetical here: this
stack is meant to be left running. The application writes a log line per request and per chunk — the
readable console format on the `local` profile this stack runs, JSON on the others — Prometheus scrapes
every fifteen seconds, and both the performance baseline and any overnight parity run are gathered while
nobody is watching.

```bash
# the defaults: 10 MiB per file, 3 files kept — 30 MiB per service, 180 MiB for all six
docker compose up -d --build

# raise the ceiling when a long run's history must be readable afterwards
CARDDEMO_LOG_MAX_SIZE=64m CARDDEMO_LOG_MAX_FILE=5 docker compose up -d --build

# confirm what a running container actually enforces
docker inspect "$(docker compose ps -q app)" --format '{{json .HostConfig.LogConfig}}'
# → {"Type":"json-file","Config":{"max-file":"3","max-size":"10m"}}
```

This is a **growth ceiling, not a retention policy**: it promises only that leaving the stack up cannot
fill your disk, and it makes no promise about how much history survives. Rotation never truncates a
file that a running `docker compose logs -f` is streaming, so following a long run is unaffected —
only history already written past the ceiling is discarded. `ContainerLifecycleContractTest` fails the
build if any service loses its bounded driver or hard-codes either ceiling.

#### Reaching the stack from another machine

**The local stack is reachable from this machine only, and there is no supported way to publish it
past that.** It is full of throwaway values on purpose: a database password readable in the Compose
file, a dashboard password of `admin`, and a token signing secret committed in `application-local.yml`
so that `spring-boot:run` works with no environment prepared. They are fixtures, and what makes them
fixtures is that nothing off this machine can reach the service that trusts them.

A widening procedure — a wildcard bind paired with generated values for `POSTGRES_PASSWORD`,
`GRAFANA_ADMIN_PASSWORD` and `CARDDEMO_JWT_SECRET` — is documented nowhere here, because
**such a procedure does not work.** The `app` service's `environment:` block forwards neither
`CARDDEMO_JWT_SECRET` nor `CARDDEMO_MANAGEMENT_TOKEN` into the container; `docker compose config` shows
the resolved environment and neither name is in it. An operator who followed it exported a generated
signing secret into their own shell while the container went on minting and accepting tokens signed
with the committed literal — the exposure was widened and nothing was paid for it. Correcting the
forwarding would not have rescued the procedure either: a published stack still answers over cleartext
HTTP, still accepts the ten seeded identities whose password the estate README documents, and still
exposes a LocalStack endpoint, a Prometheus and a Jaeger UI that authenticate nobody beside it. Four of
the six services have no credential to rotate.

**Two ways to reach it, both of which keep the listener where it is:**

```bash
# 1. Forward the port over an encrypted, authenticated channel. Run this on YOUR machine;
#    the stack stays loopback-bound on the host and SSH supplies both properties it lacks.
ssh -L 8080:127.0.0.1:8080 <host>        # then use http://localhost:8080 locally
ssh -L 3000:127.0.0.1:3000 <host>        # Grafana; 9090 Prometheus, 16686 Jaeger, likewise
```

2. **Deploy the `prod` profile.** A service that must genuinely answer other hosts is a production
   deployment: [`application-prod.yml`](src/main/resources/application-prod.yml) requires transport
   security and resolves all fourteen of its required values from the environment with **no fallback**,
   refusing to start without them. That is the posture for a routable address, and `local` is not it.

The `*_BIND_ADDRESS` overrides remain in the Compose mappings for one legitimate purpose: selecting a
different **loopback alias**, such as `127.0.0.2`, so two stacks can hold the same port number on one
host. Compose has no conditional and cannot refuse a routable value, so the boundary is asserted
instead — `LocalValidationStackExposureTest` fails the build if any mapping loses its loopback default,
if this file or any other runbook regains a non-loopback bind recipe, or if the `app` service starts
claiming to forward an application credential it does not forward.

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

**Every** profile publishes exactly `health`, `info`, `metrics` and `prometheus` — the local one included,
and `local`, `test` and `prod` each restate that same list so a widened baseline cannot reach them by
inheritance. No profile publishes `env`, `configprops`, `beans`, `flyway`, `mappings` or `loggers`;
loopback binding narrows reachability but is not a reason to expose resolved configuration or mutable
diagnostics. Anonymous
health checks receive aggregate status only. Component and detail data use
`show-details: when-authorized` and `show-components: when-authorized`.

The three health addresses stay reachable without a credential — an orchestrator has none to present —
and the cost of probing them is bounded rather than the anonymity withdrawn. Each AWS readiness
contributor answers from its own most recent result for two seconds and coalesces concurrent
evaluations onto one, so the object store, the queue and the topic are each asked at most once per
window however often the endpoint is probed; the aggregate endpoint caches its own answer for the same
window. Probing more often therefore costs nothing beyond the endpoint, and a resource that goes away
is still reported within one window. `docs/decision-log.md` DL-344 records the reasoning.

Sign-on uses the seeded sample identifiers — `ADMIN001` for the administrator role and `USER0001` for
the standard-user role. Their password is the single sample literal carried in the legacy
user-provisioning job's in-stream cards, and it is stored **only as a BCrypt hash** by the seed
migration. These identifiers exist in the local and test profiles only.

The REST surface publishes **20 operations over 19 paths**: 18 derived from the 17 screen transactions
— sign-on's two turns share one path, first entry with no communication area and a submitted turn — plus
two administrator-only batch-management operations. The batch routes have their own explicit
`/api/batch/**` administrative-authority gate.

**Every address the filter chain admits is named individually, and everything else beneath `/api` is
refused.** The eleven ordinary screen addresses each carry a rule requiring one of the two sign-on
authorities; the administrative and batch-control prefixes each carry a rule requiring the
administrative authority; sign-on is permitted; and a closing `denyAll` covers the rest of `/api`, so a
signed-on caller addressing a path this module does not serve is refused rather than authorized and then
answered as not-found. The `Access` column below is that decision, not a description of it — the
delivered-surface oracle requires the router's mappings, this table and the chain's ordinary roster to
agree, so a route added and left unclassified fails the build instead of becoming either unreachable or
open. `docs/decision-log.md` DL-345 records the reasoning.

This table is not the authority for the surface, and it is not allowed to drift from it either.
[`DeliveredApiSurfaceOracleTest`](src/test/java/com/carddemo/api/DeliveredApiSurfaceOracleTest.java)
holds one **independent literal** method-and-path oracle for all twenty operations — no controller
constant appears in it, and the test fails if one is introduced — and compares that oracle against the
router's own inventory, the served `/v3/api-docs` document, the entitlement each path is gated by, and
the rows of the table below. [`OpenApiRouteContractTest`](src/test/java/com/carddemo/config/OpenApiRouteContractTest.java)
additionally fetches the served document and pins each operation's typed request, typed success and
reachable error statuses:

| Method and path | Legacy transaction or purpose | Access |
|---|---|---|
| `GET /api/auth/signon` | CC00 sign-on, first entry — the turn the legacy transaction ran with no communication area, which over HTTP is a request with no body | Anonymous entry point |
| `POST /api/auth/signon` | CC00 sign-on, submitted turn | Anonymous entry point |
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
./mvnw spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments=--server.address=127.0.0.1
```

…or leave the container running and put your own process on another port:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments=--server.address=127.0.0.1,--server.port=18080
```

Or run the packaged artifact, with the same choice of port:

```bash
./mvnw -B clean package -DskipTests
SPRING_PROFILES_ACTIVE=local java -jar target/carddemo-java-1.0.0.jar \
  --server.address=127.0.0.1                                            # binds 127.0.0.1:8080
SPRING_PROFILES_ACTIVE=local java -jar target/carddemo-java-1.0.0.jar \
  --server.address=127.0.0.1 --server.port=18080
```

**Why every host-run command names the address.** `application-local.yml` defaults `server.address` to
`127.0.0.1`, so these commands are loopback-bound whether or not the argument is present — it is written
out because the argument is the thing a reader copies, and because the shared baseline declares no
address at all, which means the embedded server's own default is *every* interface. This profile carries a
committed signing secret, a committed operator credential, cleartext HTTP, anonymous metric scraping and
ten seeded sign-on identities; on a wildcard bind, all of that is published to anything that can route to
the machine. The container is the one place the bind is widened, and Compose does it there with
`SERVER_ADDRESS: 0.0.0.0` because a published port does not reach a container's loopback — while the host
side of that mapping stays `127.0.0.1`. See
[Reaching the stack from another machine](#reaching-the-stack-from-another-machine).

**Which observability services can still see a host-run process.** Jaeger can: the application exports
traces *outward* to the OTLP endpoint, so a host process reaches `localhost:4318` and its traces appear
in the Jaeger UI on 16686. Prometheus cannot, without help: it *pulls*, and
[`config/prometheus/prometheus.yml`](config/prometheus/prometheus.yml) targets the container name
`app:8080`, which resolves on the Compose network and not to your host JVM. So a host-run process is
traced but **not scraped**, and consequently the provisioned Grafana dashboard stays empty for it. If you
need scraped metrics — as the performance baseline under [Gate 3](#gate-3-performance-baseline) does —
run the application as the Compose `app` service and drive it over HTTP, or add a scrape target of your
own for the host process.

Sign-on uses the seeded sample identifiers — `ADMIN001` for the administrator role and `USER0001` for
the standard-user role. Their password is the single sample literal carried in the legacy
user-provisioning job's in-stream cards; the seed migration stores it **only as a BCrypt hash**, and this
README does not print it. These identifiers exist in the local and test profiles only — see
[where local and test values live](#where-local-and-test-values-actually-live) for exactly which files
carry which value.

The REST surface publishes **20 operations: 18 derived from the 17 screen transactions — sign-on has two
turns on one path — plus 2 batch-management operations** on
[`BatchJobController`](src/main/java/com/carddemo/api/BatchJobController.java).

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

#### Repeated sign-on is NOT bounded by this module, and that is a preservation decision

Legacy transaction `CC00` has no attempt counter, no lockout and no refusal period: it reads the
credential master once per submitted turn and answers. This module reproduces that, so **every submitted
sign-on reaches the credential read** and a caller may spend attempts at whatever rate it can drive.

Bounding it — an allowance counted per identity and per caller address, refused before the credential read,
with its state in a `sign_on_attempt` table — is not available. The
security reasoning is sound and the behaviour is still invented: the migration's scope is frozen at what
the estate does, so the whole family stays out. DL-352 records the removal, and DL-268, DL-342 and
DL-343 carry corrections marking what in them no longer describes delivered behaviour.

**What that means for a deployment, stated so it is not discovered later.** The sign-on surface
distinguishes an unknown identifier from a wrong secret — frozen contract text — and verifies a secret with
a deliberately expensive digest, so it is an enumeration and credential-stuffing surface with a processor
cost per attempt. Bounding it belongs to a control **outside** this module: an edge rate limit, a WAF, or a
network control in front of the deployment. Two properties that were part of the withdrawn work are kept,
because neither adds behaviour the legacy screen lacks: the not-found path performs the same digest
verification the wrong-secret path does, so the two cannot be told apart by timing, and
`server.forward-headers-strategy: native` still refuses a forwarded address from an unnamed peer so that a
request is attributed truthfully.

#### Launching a job over HTTP, and asking after it

```bash
# launch: six of the nine jobs read no parameter, so no body is needed at all
curl -sS -X POST "$BASE/api/batch/jobs/postTransactionJob/launch" -H "Authorization: $AUTH"
# → {"executionId":1,"jobName":"postTransactionJob"}

# one with parameters — they travel in a TYPED JSON BODY, not as query parameters
curl -sS -X POST -H "Authorization: $AUTH" -H 'Content-Type: application/json' \
     -d '{"reportStartDate":"2022-01-01","reportEndDate":"2022-07-06"}' \
     "$BASE/api/batch/jobs/transactionReportJob/launch"

# status: the identifier the launch answered with
curl -sS "$BASE/api/batch/jobs/executions/1" -H "Authorization: $AUTH"
# → the execution identifier, the job name, the batch status and the exit code, and nothing else
```

**Parameters are a typed body and a closed schema, and a query string is not read at all.** Appending
`?reportStartDate=…` to the launch URL does not fail loudly — the value is simply not part of the request,
so the job starts with no parameters and is then refused by its *own* validator, which reports a missing
parameter rather than a misplaced one. Only three of the nine jobs declare any parameter:
`interestCalculationJob` reads `interestParmDate`, `transactionReportJob` reads `reportStartDate` and
`reportEndDate`, and `fileProbeJob` reads `fileProbeMode`. The other six declare none, so naming anything
for them is refused with *“The request supplied a parameter this job does not declare.”* — as is naming a
parameter that belongs to one of the other two jobs.

Both answers are flat JSON objects; the launch carries `executionId` and `jobName`, and the status
carries those two plus `status` and `exitCode`. Member *order* is not part of the contract — read them by
name.

**A repeat launch runs again, and the run identity is the server's to mint.** Each launch is serialized
per job behind a database advisory lock, and the identifying `run.id` parameter is advanced *on the
server* from the framework's own metadata — a caller can neither supply it nor influence it, because the
only names a caller may write are the declared ones above. So submitting the same job with the same body
twice starts a second, distinct instance rather than being refused, which is what makes a second timing
run possible at all. A name outside the nine reads as absent, and so does an execution belonging to a job
this surface does not own.

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
variable **fails startup** rather than silently binding a placeholder. The list is **fourteen** entries and
is the complete set of no-fallback references in that file — check it against the file rather than trusting
the table, which `config/DocumentedSourceCountsTest` now does on every build:

```bash
cd carddemo-java
# 15 lines: the 14 below, plus ${VARIABLE} from an explanatory comment
grep -oE '\$\{[A-Z_0-9]+\}' src/main/resources/application-prod.yml | sort -u | wc -l
```

Note the scope of the claim: it is about *secrets*, not about every variable — six non-secret variables
further down deliberately do carry defaults:

| Variable | What it configures |
|---|---|
| `CARDDEMO_DB_URL` | JDBC URL of the PostgreSQL 16 instance |
| `CARDDEMO_DB_USERNAME` | Database user |
| `CARDDEMO_DB_PASSWORD` | Database password |
| `CARDDEMO_JWT_SECRET` | Signing secret for the session token that replaced the pseudo-conversational state area |
| `CARDDEMO_FIELD_ENCRYPTION_KEY` | Key for the field-level encryption applied to sensitive customer data |
| `CARDDEMO_MANAGEMENT_TOKEN` | Bearer credential the management filter chain requires for the `ROLE_MONITORING` rules that guard the Actuator path |
| `CARDDEMO_SQS_QUEUE` | FIFO queue name for the job-submission bridge; the required value is `JOBS.fifo` |
| `AWS_REGION` | Region for the S3, SQS and SNS clients |
| `CARDDEMO_AWS_ACCOUNT_ID` | Account the queue, topic and bucket must belong to; an outbound locator owned by any other account is refused rather than trusted |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | OTLP trace collector endpoint |
| `CARDDEMO_TLS_KEYSTORE` | Keystore location |
| `CARDDEMO_TLS_KEYSTORE_PASSWORD` | Keystore password |
| `CARDDEMO_TLS_KEYSTORE_TYPE` | Keystore type |
| `CARDDEMO_TLS_KEY_ALIAS` | Key alias inside the keystore |

Six further variables are non-secret and therefore *do* carry a default — `CARDDEMO_JWT_EXPIRATION`,
`CARDDEMO_S3_BUCKET`, `CARDDEMO_SNS_TOPIC`, `CARDDEMO_SQS_MESSAGE_GROUP_ID`,
`CARDDEMO_TRACING_SAMPLE_RATE` and `CARDDEMO_TRUSTED_PROXIES`. Each names a resource, a sampling decision
or a network boundary rather than a credential, so a default is a convenience rather than a hidden secret.
Counting five and omitting the proxy list is the drift this guards against; both figures are measured against
the profile by `config/DocumentedSourceCountsTest` rather than maintained by hand. AWS credentials in `prod` come from the standard AWS
provider chain rather than from configuration at all.

**No production value for any of the variables above appears in this repository**, and none is defaulted
in `application-prod.yml`: a missing one fails startup.

#### What the production AWS account must already carry

Configuration is only half of the requirement. `config/AwsResourceTrustVerifier` asks the three services
themselves, before any bean that could publish exists, and a `prod` start-up is refused unless the account
carries an **access posture** as well as the right resources. This application provisions none of it
deliberately — a check that provisioned would turn a misconfigured account into a silently corrected one —
so the infrastructure definition that creates the account is required to attach all five:

| # | Resource | Property the deployment refuses to start without |
|---|---|---|
| 1 | staging bucket | public access blocked through **all four** controls: `BlockPublicAcls`, `IgnorePublicAcls`, `BlockPublicPolicy`, `RestrictPublicBuckets` — two govern access-control lists and two govern policies, so three of four leaves one route open |
| 2 | staging bucket | a bucket policy that grants no principal unconditionally and denies every `s3` action when `aws:SecureTransport` is `false` |
| 3 | staging bucket | a default server-side encryption algorithm; no writer in this module names one per object, so the bucket default is the whole of the at-rest protection for every statement, report and rejected record |
| 4 | job queue | a queue policy on the same two terms, denying every `sqs` action over plain transport |
| 5 | notification topic | a topic policy on the same two terms, denying every `sns` action over plain transport. A topic left with **only** the default policy the notification service attaches fails this check: that default is not an open grant — it confines a wildcard principal to the owning account, which is accepted — but it carries no transport denial at all |

Two rules decide every one of the three policies, they are stated once in `util/AwsResourcePolicyRules`, and
they are structural: no statement may **allow every principal with no condition confining it**, and some
statement must **deny every principal every action of the resource's own service over plain transport**. No
effective permission is calculated anywhere — identity policies, permission boundaries and organisation
rules are invisible from a resource's own document — so the claim made is about the document and nothing
more.

[`localstack/init/01-create-aws-resources.sh`](localstack/init/01-create-aws-resources.sh) provisions exactly
this posture for the local stack and is the worked reference for the document shapes. It is not a
description: `config/LocalStackBootstrapIT` reads each document back out of a running emulator and judges it
with the very rule production applies, so the local stack and a production account cannot drift into two
postures. Recorded as `DL-346` in [`../docs/decision-log.md`](../docs/decision-log.md).

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

Flyway runs on start-up and replaces the ten legacy dataset-provisioning job streams. The five
migrations ship from **two sibling locations whose shared parent holds no script at all**, and the
*location list* — not the version ceiling — is the primary profile scoping mechanism:

| Migration | Applied in | Content |
|---|---|---|
| `V1__create_schema.sql` | every profile | **11 tables**, one per verified record layout |
| `V2__create_indexes.sql` | every profile | The three alternate-index equivalents as B-tree indexes, plus primary and **six** foreign keys. It **creates no table** — the migration says so at its head — and it records there which three relationships are deliberately left unconstrained and why |
| `V2_2__add_protected_value_invariants.sql` | every profile | Three named `CHECK` constraints: an `ENC1` envelope on each of the two regulated customer identifiers, and a structural BCrypt digest with a cost inside 10–31 on the stored credential. It **creates, alters and writes nothing**. DL-349 |
| `V3__seed_reference_data.sql` | `local`, `test` | Reference and sample data at the measured fixture counts |
| `V4__seed_user_security.sql` | `local`, `test` | The ten seed users — five administrator, five standard — stored as **BCrypt hashes** |

The three schema scripts sit in `db/migration/schema`, the two seeds in `db/migration/seed`, and the
shared parent `db/migration` holds neither. The baseline and `prod` resolve the schema location **alone**,
so the seeds appear in no migration state whatsoever under production — not applied, not pending, not
resolved. `local` and `test` resolve both. A **version ceiling sits beside** the location list rather than
in place of it: the baseline and `prod` pin `target: "2.2"`, the highest version the schema location
delivers, and the two seeding profiles lift it to `latest` in the same block where they add the seed
location. So **a production deployment migrates the schema, the indexes and the invariants
without inheriting sample data or seeded credentials**, and the seeds are excluded twice over — by their
directory and by their number, because every schema version sorts below both seed versions
(`2 < 2.2 < 3`). The pin is asserted against the versions the schema location actually delivers, so a
schema script added above it fails the build rather than being silently skipped. `clean` is disabled
outside the profiles whose database is disposable, and `validate-on-migrate` is on everywhere — which is
why a migration already applied is immutable and a change of intent arrives as a new version. DL-298 for
the location split, DL-334 for the ceiling, DL-343 for the numbering rule.

The third alternate-index equivalent is defined even though no online endpoint depends on it: the
report job's date-range filter would otherwise scan the whole transaction table.

**Counting tables in a running database will give you eighteen, not eleven, and all seven extras are
accounted for.** The eleven are the migrated record layouts — `account`, `card`, `card_cross_reference`,
`customer`, `daily_transaction`, `disclosure_group`, `transaction`, `transaction_category`,
`transaction_category_balance`, `transaction_type`, `user_security`. Beside them sit the **six** Spring
Batch metadata tables the framework's own PostgreSQL schema creates — `batch_job_instance`,
`batch_job_execution`, `batch_job_execution_params`, `batch_job_execution_context`,
`batch_step_execution`, `batch_step_execution_context`, plus its three sequences, which are sequences
rather than tables and so do not enter this count — and Flyway's own `flyway_schema_history`.

**Eleven of those eighteen are this module's, there is no `job_submission_outbox` and there is no durable
resume.** Persisting delivery progress in a `job_submission_outbox` table so that a partial card stream
could be completed by a later call is feature expansion and is not available: the estate defines eleven
record layouts, the legacy queue
definition carries `ERROROPTION(IGNORE)`, and the emitting program abandons a refused write rather than
deferring it — so there is no delivery state to persist. What the bridge actually does is reproduce that. It
publishes each of its own cards once, in list order, into a single first-in-first-out message group; it
**stops at the first refusal**; the refusal is non-fatal and is reported by a warning naming how many cards
were published against how many were requested; and it keeps no memory for a later attempt. A submission
that stopped part way therefore left a prefix of its cards on the queue, which is the legacy behaviour
rather than a gap in this one. The delivered coordination is a transaction-scoped PostgreSQL advisory lock
that persists nothing. `V2__create_indexes.sql` records the removal at its head, and the reasoning is in
[`../docs/decision-log.md`](../docs/decision-log.md) DL-148. Count for yourself:

```bash
docker compose exec -T postgres \
  psql -U carddemo -d carddemo -Atc \
  "select count(*) from information_schema.tables where table_schema='public'"   # 18
```

#### Erratum: the earliest migration headers describe the topology as it stood at their own version

The table above is the current topology. **The headers of `V1__create_schema.sql`,
`V2__create_indexes.sql`, `V3__seed_reference_data.sql` and `V4__seed_user_security.sql` are not, and they
are deliberately left as written.** Each of the four states, in its own explanatory comment, one or more of
the following, and every one of them was true when that script was authored:

- that the schema location carries **two** scripts, `V1` and `V2` — superseded: it now carries three, the
  protected-value invariants `V2_2` beside them;
- that the shared baseline and `prod` declare `spring.flyway.target: "2"` as the highest version the schema
  location delivers — superseded: the pin is `"2.2"`, which is what `application.yml`,
  `application-prod.yml` and `FlywayConfig.PRODUCTION_TARGET` actually carry;
- that a production migration resolves the set `{1, 2}` and applies V1 and V2 only — superseded: it applies
  all three schema versions, `1`, `2` and `2.2`;
- and, in `V4` alone, that the configuration guard "refuses a NUMERIC target under production" — superseded,
  and by its opposite: production **requires** the numeric pin `2.2` and refuses every other value in either
  direction.

**Why they are not corrected in place.** `validate-on-migrate` is `true` under every profile, and all four
are applied in every database this module has ever run against — the per-run test containers, and the local
Compose volume, whose `flyway_schema_history` carries their checksums. A migration script is immutable once
applied, comments included: editing one character of a header changes its Flyway checksum and makes an
already-migrated database fail validation on start-up. Two of the other headers say so themselves and rest
their own reasoning on it, so an edit here would not correct one inaccuracy but create two more. The
property those four headers were written to assert is untouched by any of this: **every schema version still
sorts below every seed version, so the seeds are still excluded by their number as well as by their
directory**.

**What holds this erratum honest.** The list above is not maintained by hand. `DocumentedSourceCountsTest`
derives the delivered inventory from the two migration directories and the pin from
`FlywayConfig.PRODUCTION_TARGET`, then requires this section, the architecture page, the profile documents
and the Compose file to state that inventory and that pin — and requires this erratum to name exactly those
delivered scripts whose header still states a superseded pin. A fifth schema script therefore fails the
build until every published summary, this erratum included, has been brought up to date. Recorded as
DL-351.

#### Note: the frozen headers are the one place a superseded design is narrated rather than stated

Comments elsewhere in this module state a prohibition rather than recounting what a previous revision did.
The five applied migration headers are the exception, and it is a mechanical one rather than a stylistic
choice: `V2__create_indexes.sql` explains why there is no twelfth table by describing the operational
outbox that was withdrawn as feature expansion, and it cites `docs/decision-log.md` DL-148 for the
decision. Rewriting that sentence would change the script's Flyway checksum and fail validation on every
already-migrated database, for exactly the reason the erratum above gives. The narration is therefore
confined to the five frozen scripts, is cited to a numbered decision in every case, and is corrected here
rather than in place.

#### Note: `V2_2`'s header reasons about a `V2_1` that no longer ships

`V2_2__add_protected_value_invariants.sql` states the delivered pin correctly, which is why the erratum above
deliberately says nothing about it. One statement in its header is nonetheless out of date: it explains its own
dotted number against an ordering of `2 < 2.1 < 2.2 < 3` and cites `V2_1`'s header for the rule that a further
schema script takes the next free dotted version. **`V2_1` was withdrawn together with the sign-on attempt
throttle it provisioned, so the delivered ordering is `2 < 2.2 < 3`.** Everything the statement was making
true still holds: every schema version sorts below every seed version, `V2_2` is still the highest version the
schema location delivers, and `FlywayConfig.PRODUCTION_TARGET` is therefore unchanged. The header is not
corrected in place for the same checksum reason the erratum gives — it is applied wherever this module has run
— and a new schema script still takes the next free dotted version below `3` rather than back-filling `2.1`.
`docs/decision-log.md` DL-352 records the withdrawal and the migration-history decision behind it.

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
and start and end **instants in UTC** — never parameters, execution context, exit descriptions,
exceptions, record data or credentials. The topic is verified rather than created on first use, and a
refused notification is logged without changing the job's own outcome.

**The dependency has two stages, and only the second is best effort.** *Provisioning and ownership are
mandatory.* A `prod` start-up is refused outright by `config/AwsResourceTrustVerifier` when the configured
topic cannot be resolved from a listing, is not owned by the account `CARDDEMO_AWS_ACCOUNT_ID` declares, or
does not answer an attribute read — checked before any bean that could publish exists. Locally the same
requirement is the emulator's health check, which does not report healthy until the topic exists, with the
application container waiting on that health rather than on the emulator merely having started. There is
therefore no supported deployment in which this channel's destination is absent or belongs to somebody
else. *Delivery of an individual notice is best effort afterwards.* A dropped, shed, refused or timed-out
notification leaves the job's status and exit code exactly as the framework recorded them, is counted on
`carddemo.job.completion.shed`, and does not take the instance out of service. The two are not in tension:
the first is about whether the destination is the one this deployment was configured for, the second about
whether one notice arrives. Recorded as `DL-339` in
[`../docs/decision-log.md`](../docs/decision-log.md).

The channel is permitted to lose notifications, so every notification it loses is counted on
`carddemo.job.completion.shed`, tagged `reason` with `QUEUE_FULL`, `NOTIFIER_CLOSED` or `SHUTDOWN`.
Read against the delivery count published by `carddemo.job.completion.publish`, that answers the only
question a shedding channel owes an operator: what fraction of what happened was announced.

**There is no retry, idempotency or deadline protocol on report submission**, because the queue it feeds
has none. `app/csd/CARDDEMO.CSD` defines `TDQUEUE(JOBS)` with `DISPOSITION(MOD)` — append — and
`ERROROPTION(IGNORE)`, and `CORPT00C` carries no token, no deduplication key and no window: a second
confirmed submission of the same period appends a second job stream and the reader runs the job twice.
Each confirmed submission therefore mints its own message-group identity from the two reporting dates
plus a per-request nonce, so each publishes its own complete stream. The only guard the legacy screen
has is its **confirmation gate**, and that is preserved as an explicit confirm flag. A caller that wants
at-most-once submission must not confirm twice — the same obligation the 3270 operator had. Recorded as
`DL-322` in [`../docs/decision-log.md`](../docs/decision-log.md).

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
bucket and the queue without creating them, and becomes `DOWN` while either is absent so a router can stop
assigning new work; the completion topic is checked and published as its own component but is deliberately
**not** in that group, for the reason set out under the two-stage contract above. Liveness checks only the
process state: restarting a healthy process cannot provision an external resource.

### Observability

| Endpoint | Access | What it gives you |
|---|---|---|
| `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | anonymous | Aggregate health; process-only liveness (the group the container probe reads); and readiness requiring the datasource plus the S3 staging bucket and the SQS job queue. **The SNS completion topic is deliberately not in the readiness group** — it carries a notice that a job has already finished, so its absence loses a notice and prevents no work, and taking an instance out of service for it would be the wrong remedy. The `awsSns` contributor is still published, so its state remains readable; what it no longer does is decide this instance's fitness to receive traffic. |
| `/actuator/prometheus` | anonymous | Micrometer timers on every REST endpoint and every batch step — the measurement surface the performance baseline is read from. Anonymous deliberately, so the collector can scrape it; closed in `prod`. |
| `/actuator/metrics`, `/actuator/info` | authenticated | Individual meters and build info. **These four endpoints — health, info, metrics, prometheus — are the whole published surface in every profile.** `env`, `configprops`, `beans`, `flyway`, `mappings` and `loggers` are exposed by no profile and answer not-found; exposure and authentication are different questions, and narrowing one is not widening the other |
| `/v3/api-docs` | open in `local`, **disabled in `prod`** | The machine-readable description of the 20 published operations. **There is no interactive viewer**, in any profile — see below |
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

# count what is actually published — 19 paths, 20 operations
curl -s http://localhost:8080/v3/api-docs | python3 -c \
  'import json,sys; d=json.load(sys.stdin); p=d["paths"]; \
   print(len(p), "paths,", sum(len(v) for v in p.values()), "operations")'
```

Structured JSON logging with correlation identifiers — [`logback-spring.xml`](src/main/resources/logback-spring.xml)
plus `logstash-logback-encoder` — replaces the **215 statement-initial `DISPLAY` lines** that were the
legacy system's only diagnostic channel. The specification's figure is 217; the counting method and
why 215 is the measured figure are recorded as DL-253 in
[`../docs/decision-log.md`](../docs/decision-log.md).

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
| `CreateStatementJobConfig` | `createStatementJob` | `app/jcl/CREASTMT.JCL` — **four steps with three condition-code gates**, reproduced as failure-*propagating* transitions: a gate still bypasses the steps behind it, and the job itself ends `FAILED` when any step it ran abended (DL-208) |
| `TransactionReportJobConfig` | `transactionReportJob` | `app/jcl/TRANREPT.jcl` + `app/proc/TRANREPT.prc` — date-range filtered, card-ordered, 133-byte report line |
| `BackupTransactionJobConfig` | `backupTransactionJob` | `app/jcl/TRANBKP.jcl` — one condition-code gated backup step, delivered at the strict-zero ceiling all four gates share (DL-145); the generation-data-group becomes a numbered generation key allocated **by the object store**, one above the highest already present beneath the base, never from the framework's execution identifier (DL-210) |
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
(`carddemo-batch-staging` on LocalStack). The two collaborators divide cleanly, and **only one of them
publishes**: `BatchStagingArea` resolves fixed logical inputs — it exposes the bucket name, whether an
object is present, and the object as a readable resource, and **no publication method at all** — while
`StagedGenerationStore` owns every write to the durable store, allocating the generation key and
publishing the completed artefact (DL-212). Staging readers prefer an existing S3 object and retain their
documented local or classpath fallback where the translated job requires one. Dataset-producing jobs may
write an execution-local file while Spring Batch needs file-backed restart state, but that file is a working
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
LocalStack **Community** edition. The consolidated evidence page is
[`../docs/gate-evidence.md`](../docs/gate-evidence.md): it carries, per gate, the command that produces the
evidence, the artefact the evidence lands in, and the part of the outcome that is a property of the code
rather than of the machine that ran it. This section documents each gate's *mechanism and current
coverage* — including, gate by gate, what is proven today and what is still outstanding. For anything that
is a per-run measurement, run the command and read the result rather than reading a status out of either
file. **One thing genuinely remains outstanding and is marked as such throughout: golden coverage of four
of the five reject reason codes.** The supplemental 40-byte category-balance report line — a fixed width
emitted beside the gate's four contractual ones rather than one of them — is **no longer among the gaps**:
it now carries a committed golden of its own,
`expected/category-balance-report.txt`, and that file is the verdict oracle of the job's own integration
test. Earlier revisions of this manual described that fixture as pending or absent; those statements were
true of an absent file and are false of a present one, so they are withdrawn rather than softened.

| Gate | What it proves | How to run it | Coverage today |
|---|---|---|---|
| 1 | Byte equivalence of the emitted records | `./mvnw -B verify` (fails on any golden-file mismatch) | **complete for the four contractual widths**, compared as byte arrays from one seeded pipeline pass, **and for the supplemental 40-byte width**, compared as a byte array against its own committed golden from a dedicated job run in `batch/CategoryBalanceReportJobConfigIT`. Four of the five reject reason codes are still uncovered by a golden record |
| 2 | Zero-warning build | `./mvnw -B clean verify` | **complete** — enforced by the compiler |
| 3 | Performance baseline **established** | `./mvnw -B verify`, then read `target/gate-evidence/gate3-*.md`; `/actuator/prometheus` corroborates | **complete** — **thirty** measured rows recorded in [`../docs/gate-evidence.md`](../docs/gate-evidence.md), each dated, attributed to a named machine and quoted with its fixture volumes. The count is derived from that table by `config/DocumentedSourceCountsTest`, so the next measured run updates this sentence or breaks the build. No row is attributed to a source revision, and earlier revisions of this manual claimed three were: a run cannot know the revision it is running, which is why the emitted evidence files carry a separate `Build provenance` line and the table does not (DL-340). Measurements, never thresholds |
| 4 | Named real-world validation artifacts | `./mvnw -B verify` (seeded and asserted) | **complete** — every named fixture measured and asserted, by name rather than by directory listing |
| 5 | Interface contract verification | `./mvnw -B verify` (against a real queue and a real port) | **complete** — the card image drained back out of a real queue, and the sign-on texts and routing asserted against a booted context on a random port |
| 6 | Unsafe and low-level code audit | the scoped grep list below | **complete** — mechanically re-runnable |
| 7 | Scope matching + coverage floor | `./mvnw -B verify` (JaCoCo check) | **complete** — a failing check, not a report |
| 8 | Integration sign-off | `./mvnw -B verify` + the traceability matrix | **complete** — every checklist row is computed from an artefact and written to `target/gate-evidence/gate8-sign-off.md`. That emitted table is an **interim** record and says so on a `Sign-off status:` line: it is written at `integration-test`, and two of its rows depend on artefacts a later phase writes, so it reads `PROVISIONAL` and names each outstanding row beside the artefact that discharges it. CI's *Reconcile the provisional sign-off into a final one* step reads those artefacts after `verify` and writes `target/gate-evidence/final-sign-off.md` reading `FINAL`; an undischargeable row fails the step and no final sign-off is written (DL-340). The matrix and the evidence page are both published |

### Gate 1 — end-to-end byte equivalence

The gate's requirement is that production-representative input be processed locally and the output
compared **byte for byte** against golden files, with **mocked I/O not satisfying it**. Here is exactly
how far that is carried today, named test by named test, because a gate described more broadly than it
is executed is worse than one described narrowly:

| Golden file | What executes it | How far it goes |
|---|---|---|
| `expected/daily-reject.txt` | `e2e/BatchPipelineE2ETest`, and `support/ExpectedOutputFixtureContractTest` | **End to end, from the pipeline run.** The committed 300-record input is staged, the delivered pipeline runs against a Testcontainers PostgreSQL instance and a real object store, and the reject dataset **that run produced** is compared byte for byte. The contract test re-emits the same records through the production writer independently. |
| `expected/transaction-report.txt` | `e2e/BatchPipelineE2ETest`, and `support/ExpectedOutputFixtureContractTest` | **End to end, from the same run.** The report the run produced is compared byte for byte. The contract test additionally classifies every one of the 519 committed records by its own structure and re-emits it through the production formatter that owns that record type; the classification is asserted total, so an unrecognised record fails rather than being skipped, and page and grand totals are checked against sums it computes for itself — including the legacy end-of-file double-count, pinned as the contract it is rather than "corrected". |
| `expected/statement.txt` | `e2e/BatchPipelineE2ETest`, and `support/ExpectedOutputFixtureContractTest` | **End to end, from the same run.** The text statement the run produced is compared byte for byte. The contract test independently re-emits all 1,262 records through the production templates and checks every statement's total expenditure against the sum of its own detail amounts. |
| `expected/statement-html.txt` | `e2e/BatchPipelineE2ETest`, and `support/ExpectedHtmlStatementFixtureContractTest` | **End to end, from the same run.** The HTML statement the run produced is compared byte for byte. The contract test independently asserts fifty concatenated documents in strict write order, including the malformed literals reproduced rather than repaired. |
| `expected/category-balance-report.txt` | `batch/CategoryBalanceReportJobConfigIT`, and `support/ExpectedHtmlStatementFixtureContractTest` for the oracle's own shape | **From a dedicated job run, not from the pipeline pass.** The category-balance job is the one emitter the primary pipeline does not drive, so its golden is compared from its own run: the integration test fixes the reported population completely — the fifty delivered category-balance rows plus three it adds and one it rewrites in place, 53 records in all — runs the delivered job against a real PostgreSQL server, and compares the bytes it wrote to a real local dataset against the committed file. The same test asserts the three-key ascending ordering and the edited-balance formatting; the fixture contract test holds the oracle to the same shape rules as the other four. |

In every case **the expected side is the committed fixture bytes and nothing else** — no snapshotting,
no regeneration, and no expected value produced by calling the code under test.

**What Gate 1 proves, and the one thing it still does not:**

`e2e/BatchPipelineE2ETest` stages the committed input and runs **six jobs in one pass**, in the launch
order the test asserts exactly rather than approximately — `postTransactionJob`, then
`interestCalculationJob`, then `backupTransactionJob`, then `combineTransactionsJob`, then
`transactionReportJob`, then `createStatementJob` — against a Testcontainers PostgreSQL instance seeded
from the fixtures and a real object store. It then compares **all four** of the artefacts that one run
produced against their goldens as byte arrays, writing the comparison to
`target/gate-evidence/gate1-byte-equivalence.md` — one row per contract, naming the input, the expected
file, the width, the expected and actual record and byte counts, and the match status. It also asserts
that no artefact carries a record separator and that the landing dataset is left exactly as staged.

**A seventh job runs, and it is deliberately not part of that sequence.** `dailyTransactionReadJob` — the
extract job no legacy stream invokes — is launched separately, after the pipeline, precisely because it is
not a pipeline member: `CBTRN01C` is a complete program that no job stream, procedure or CICS definition
calls, so it is delivered and exercised without being wired into the default pipeline. `./mvnw -B clean
verify` discharges all of that. The per-record tests in the table above remain, because they localise a
failure to a single record where the pipeline test localises it to a stream.

One gap remains, and it is not closed by the run above:

- **Four of the five reject reason codes.** `daily-reject.txt` carries 38 records and every one of them
  is the over-limit code `0102`; the other four codes are defined and unit-tested, but no golden record
  exercises them. Cases for them are **outstanding**, and the end-to-end suite measures that as a fact
  rather than assuming it.

**Four output widths are contractual** — that criterion is frozen — and a fifth fixed-width record is
emitted beside them. **Every one of the five now has a committed golden** whose bytes divide exactly by its
width. The four contractual ones are compared from the single pipeline pass; the supplemental 40-byte one is
compared from the emitting job's own run, because that job is not a pipeline member:

| Width | Standing | What it is | Golden file | Evidence |
|---|---|---|---|---|
| **80 bytes** | contractual | statement text record | `expected/statement.txt` (100,960 bytes = 1,262 records) | golden-file comparison, from the pipeline pass |
| **100 bytes** | contractual | statement HTML record | `expected/statement-html.txt` (663,200 bytes = 6,632 records) | golden-file comparison, from the pipeline pass |
| **133 bytes**, fixed-length blocked | contractual | transaction report line | `expected/transaction-report.txt` (69,027 bytes = 519 records) | golden-file comparison, from the pipeline pass |
| **430 bytes** | contractual | daily-transaction reject record — the 350-byte source image, then a 4-digit reason code, then a 76-character description | `expected/daily-reject.txt` (16,340 bytes = 38 records) | golden-file comparison, from the pipeline pass |
| **40 bytes** | **supplemental** | category-balance report line — account, type and category identifiers, an edited balance, then filler — declared by `CategoryBalanceReportJobConfig.REPORT_RECORD_LENGTH` | `expected/category-balance-report.txt` (2,120 bytes = 53 records) | golden-file comparison, from a dedicated run of the emitting job in `batch/CategoryBalanceReportJobConfigIT`, which also asserts the ordering and the edited-balance formatting |

The **Standing** column is the distinction that matters when this table is quoted: the four contractual
widths are the criterion Gate 1 was accepted against, and the supplemental row is evidence delivered beside
that criterion rather than an addition to it. Neither statement weakens the other — the golden behind the
supplemental row is compared exactly as strictly as the other four.

Measure all five yourself rather than trusting the table. **Count bytes, not lines**: none of the five
carries a record separator — that is itself an asserted property — so a line-oriented tool sees one
enormous line and tells you nothing. Divide by the declared width instead, and check that the remainder
is zero:

```bash
cd carddemo-java/src/test/resources/fixtures/expected
for spec in statement.txt:80 statement-html.txt:100 transaction-report.txt:133 \
            daily-reject.txt:430 category-balance-report.txt:40; do
  file=${spec%:*}; width=${spec#*:}; bytes=$(wc -c < "$file")
  printf '%-28s %7d bytes / %3d = %5d records, remainder %d\n' \
    "$file" "$bytes" "$width" "$((bytes / width))" "$((bytes % width))"
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
this, and the Compose app service forwards it into the container — a variable Compose interpolates but
does not forward reaches nothing, which is exactly how this recipe once ran as a silent no-op. Ask the
JVM to *report* while you are at it: the runtime image is a **JRE**, so it ships no
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
curl -sS -X POST -H "Authorization: $AUTH" -H 'Content-Type: application/json' \
     -d '{"interestParmDate":"2022071800"}' \
     "$BASE/api/batch/jobs/interestCalculationJob/launch"

# each answers {"executionId":…}; confirm each FINISHED before reading any timing
curl -sS "$BASE/api/batch/jobs/executions/1" -H "Authorization: $AUTH"
```

**A second measurement needs no ceremony, but it does need the input staged again.** The identifying
`run.id` is minted on the server for every launch, so repeating the *same* request starts a second
instance rather than being refused — repeat the identical command and read the new execution identifier
it answers with:

```bash
# the same request again: a second instance, a second set of timings
curl -sS -X POST "$BASE/api/batch/jobs/postTransactionJob/launch" -H "Authorization: $AUTH"
```

What a repeat does *not* do is guarantee the inputs are still there. The emulator runs with
`PERSISTENCE: 0` and holds its state on a `tmpfs`, so **every staged S3 object dies with the LocalStack
container** — recreating or restarting that one service is enough to lose a staged dataset, and the next
launch then fails in its reader rather than measuring anything. Re-run step 3 before the second launch if
the stack has been touched in between. Nothing a caller writes can influence `run.id` either: the only
names accepted are the declared parameters of the addressed job, so a home-made `runNote`-style
discriminator is refused rather than honoured.

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

`jcmd` is a JDK tool, so if it is not on `PATH` invoke it as `"$JAVA_HOME/bin/jcmd"` — the same variable
the build wrapper reads. The wrapper supplies **Maven** and nothing else: it downloads and verifies the
pinned Maven distribution, then selects an already-installed JDK through `JAVA_HOME`, or through whatever
`java` is on `PATH` when that is unset. It cannot provide a JDK, so a machine whose default `java` is
older than 25 needs `JAVA_HOME` pointed at a Java 25 JDK before the build, rather than `<release>` lowered.

The container's resident set size is deliberately **not** the figure to record: it counts page cache and
the JVM's own reservations, so it describes the process rather than the workload.

**Step 7 — write the figures down with their conditions.** Elapsed time, peak heap and records per second
mean nothing without the fixture volumes, the heap bounds from step 1, the profile, and the commit they
were taken at. [`../docs/gate-evidence.md`](../docs/gate-evidence.md) is the page they belong on, and its
Gate 3 section carries the measured-runs table to add a row to. **Thirty rows are recorded there**, each
dated and attributed to a named machine — so the row you add joins a baseline rather than starting one,
and the rows already present are the comparison you read yours against. They differ from each other by
nearly half again at the same volume on the same host, which is the first thing to know before quoting
any of them.

The measurement itself is not read off a dashboard panel, and that distinction matters enough to state
here. `support/RunScopedPerformanceRecorder`, driven from `InterestCalculationJobIT`, measures one run:
wall clock across the launch, the run's own record count, and the JVM's own per-pool peak heap after a
reset immediately before the launch. It writes the figures to `target/gate-evidence/`. The dashboard's
rolling-rate panel divides by the rate window rather than by the run, and its peak panel is a maximum over
scrapes, so both are labelled visualizations; `../docs/decision-log.md` DL-182 records why.

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
3. **The sign-on message and routing contract — discharged against a booted application.** The
   **seven** distinct message texts the sign-on transaction emits are reproduced character for
   character, because operators and downstream tooling match on them: the two field prompts, the
   wrong-password message, the user-not-found message, the unable-to-verify message, the thank-you
   message on the exit key and the invalid-key message. The two texts the shared message copybook
   publishes are compared at their **full padded fifty characters**, because the padding is part of a
   fixed-width field and trimming it would assert a narrower contract than the one that ships. The
   routing rule is equally contractual — an administrator user type routes to the administrative menu,
   any other type routes to the main menu — and the legacy branch is an `ELSE` rather than a second
   equality test, so the destination is asserted for **all ten** delivered identities rather than for
   one of each kind. `e2e/OnlineTransactionE2ETest` asserts all of it against a **booted context on a
   random port with a real datasource**, through the real security filter chain;
   `api/AuthControllerIT` covers the same contract on a booted context at the MVC boundary, and
   `api/AuthControllerTest` pins it at the controller boundary with mocked collaborators, where a
   failure is localised to the controller. Three boundaries, one contract.

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
would report **five raw-SQL "violations"** that are in fact the versioned schema definition this
design requires — one per delivered migration script. Test sources are excluded for the same kind of reason — assertion helpers legitimately
use casts that production code does not.

The zero-reflection count is **not hygiene, it is architecture**. It is what forbids any bean-mapping
or annotation-driven mapping library, and therefore what requires every record mapper to slice
fixed-width images with explicit `String.substring` offsets over the verified layouts. **The module
carries twelve `*RecordMapper` classes over eleven persisted record layouts**: one per layout, plus
`StatementWorkRecordMapper`, which maps the statement work area and has no table behind it — the
migration plan's count of eleven is a count of layouts, not of classes, and both figures are correct
about different things. It is also why no annotation processor appears in the dependency set at all.
Change the reflection budget and you have changed the mapper design.

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

For context on the achievable shape, the prior delivery recorded **888 tests — 729 unit plus 159
integration and end-to-end**. That is the only form of its breakdown published anywhere in this
documentation set: its own documents also carry a second breakdown that does not sum to its total, and
[`../docs/decision-log.md`](../docs/decision-log.md) DL-256 records the discrepancy rather than
reproducing it. Neither figure is a current measurement.

**Current test figures are published in exactly one place**, with the scope definition that makes them
readable: [`../docs/gate-evidence.md`](../docs/gate-evidence.md), under *How this document counts tests*.
This file deliberately does not restate them — a count copied into a second document cannot hear that the
first one changed, which is the failure mode recorded in
[`../docs/decision-log.md`](../docs/decision-log.md) DL-316.

### Gate 8 — integration sign-off

| Checklist item | Satisfying artifact | Check | Status |
|---|---|---|---|
| End-to-end verification | golden fixtures at the **four contractual widths** — 80, 100, 133 and 430 bytes, all four driven through one seeded pipeline pass — plus the **supplemental** 40-byte golden, driven through a dedicated run of the job that emits it | `e2e/BatchPipelineE2ETest`, plus `ExpectedOutputFixtureContractTest` and `ExpectedHtmlStatementFixtureContractTest` for the per-record re-emissions, and `batch/CategoryBalanceReportJobConfigIT` for the 40-byte golden | **met** — the six-job pipeline runs against a Testcontainers PostgreSQL instance seeded from the fixtures and all four of its goldens are compared as byte arrays from that one run, with the comparison written to `target/gate-evidence/gate1-byte-equivalence.md`; the supplemental golden is compared as a byte array against a real dataset the category-balance job wrote after reading a real server |
| Interface contract verification | 17-card image with four slots and the transmitted sentinel, against a real SQS FIFO queue | `service/JobSubmissionServiceIT`, and `e2e/OnlineTransactionE2ETest` driving the submission endpoint over HTTP and draining the queue | **met** for the queue contract |
| Interface contract verification | the seven sign-on literals and the admin/user routing rule | `e2e/OnlineTransactionE2ETest` — a booted context on a random port with a real datasource — backed by `api/AuthControllerIT` and `api/AuthControllerTest` at the narrower boundaries | **met** — the five direct texts compared character for character, the two shared texts at their full padded width, and the destination asserted for all ten delivered identities, because the legacy branch is an `ELSE` rather than a second equality test |
| Performance baseline | `support/RunScopedPerformanceRecorder`, driven from `batch/InterestCalculationJobIT` and `e2e/BatchPipelineE2ETest`, writing to `target/gate-evidence/`; Micrometer timers at `/actuator/prometheus` for corroboration | `./mvnw -B clean verify`, then the measured-runs table in [`../docs/gate-evidence.md`](../docs/gate-evidence.md) | **met** — **thirty** measured rows are recorded there, each dated, attributed to a named machine and quoted with the fixture volumes it was measured over. None is attributed to a source revision; the revision belongs to the emitted evidence file's `Build provenance` line, not to a row a run wrote about itself (DL-340). They are **measurements, not thresholds**: no service level exists anywhere in the estate to test against, so re-measure on your own hardware rather than quoting a row |
| Unsafe code audit | the scoped grep list above | re-run the list; it is mechanical | **met**; the counts are recorded in [`../docs/gate-evidence.md`](../docs/gate-evidence.md) under Gate 6 |
| Line coverage ≥ 80% | JaCoCo failing check rule | `./mvnw -B clean verify` | **met** — a failing check |
| Zero **unsuppressed** critical or high CVEs **across the whole build graph** | `dependency-check-maven` 12.1.3 bound to `verify`, threshold 7.0, test scope included, reading exactly one analyst determination from [`owasp-suppressions.xml`](owasp-suppressions.xml) | `./mvnw -B clean verify`; `GateVerificationTest` asserts the determination's scope and reads both halves of the report | **met as stated, and the statement is the narrower one** — zero *unsuppressed* qualifying findings, plus **one** scoped HIGH determination that is part of the audited result rather than a silence. Set out below. Not "zero findings" |
| Traceability 100% | [`../docs/traceability-matrix.md`](../docs/traceability-matrix.md), 544 rows | `e2e/GateVerificationTest` — the row count asserted against the paragraph labels the members actually declare rather than against itself, each member's subtotal against the estate, both provenance anchors, the marker distribution, and per row that the target class exists, declares the named method and names a covering test that exists | **met** — 544 rows published, one per procedure unit (528 program paragraphs + 14 + 2 from the two procedural copybooks) |

**The supply-chain result covers the whole build graph, and exactly one finding inside it is carried
rather than fixed.** The scan is bound to `verify` and actually executed, not merely declared; it fails
the build at a CVSS threshold of 7.0, which catches every critical and high finding **that no analyst
determination covers**; it emits HTML, JSON and XML reports that CI uploads as artifacts; and
`dependency-check.skipTestScope` is **`false`**, so the result covers the compile, runtime **and test**
graph — 167 dependencies. Neither a narrower scope nor an unfixable HIGH finding in an excluded test
graph may be claimed here: the shaded transport
that carried those findings was **replaced** by the visible Apache HTTP client 5 transport rather than
excluded, which is what made the full-scope claim enforceable, and the scope was widened to match.

**One vocabulary is used for this gate everywhere it is stated — here, in [`pom.xml`](pom.xml), in
[`owasp-suppressions.xml`](owasp-suppressions.xml), in the sign-off record `GateVerificationTest` emits,
and in [`../docs/gate-evidence.md`](../docs/gate-evidence.md) — because a gate described three ways is a
gate nobody can falsify.** A finding is in exactly one of three states, and the states are not
interchangeable:

| State | What it means | What the build does |
|---|---|---|
| **Fixed** | a patched version exists and the version moved to it | the finding is gone from the report; no rule is written |
| **Carried as a determination** | no patched artifact exists on any line, and the finding is demonstrably a false match against the bytes this module resolves | the finding is suppressed by **one** named, scoped, evidenced, self-expiring rule and is **disclosed** here, in the build file and in the decision log |
| **Reported below threshold** | the score is under 7.0 | the finding is printed on every run, no rule names it, and the build passes |

So the claim this gate makes is **zero unsuppressed critical or high findings, plus exactly one carried
determination** — never "zero findings", and never "zero suppressions". The stronger claim is true only
while no determination exists, and becomes an overstatement the moment one is configured, so neither this
section nor the comment in the build file may make it.
Overstating a gate is the same defect as softening one, so the wording is corrected here, in the build
file and on the evidence page, and **nothing in the configuration was relaxed** to make the wording true:
the threshold is still 7.0, the scope is still the whole graph, and `failBuildOnUnusedSuppressionRule` is
still `true`.

What remains is one carried HIGH and one reported MEDIUM, and neither is left implicit:

- **CVE-2026-66299, CVSS 7.5 HIGH, against the embedded Tomcat 10.1.57 jars — carried as a
  determination in [`owasp-suppressions.xml`](owasp-suppressions.xml), not fixed.** The advisory is
  against Tomcat's **WebSocket chat example**, and says in its own text that users who removed the
  examples web application are unaffected. An embedded container has no `webapps` directory to deploy an
  examples application from, and that is measured rather than assumed: the three embedded jars carry
  2,036 archive entries between them and **not one** is a `webapps/`, `examples/` or chat-example entry.
  The match is on the product-level CPE `cpe:2.3:a:apache:tomcat:10.1.57`, by version alone.
- **There is nothing to upgrade to.** The advisory names 10.1.58 and 11.0.25 as the fixed releases and
  **neither is published** — a direct request to Maven Central returns 404 for 10.1.58, 10.1.59, 10.1.60
  and 11.0.25, and the newest 10.1.x that resolves is the 10.1.57 this module already pins upward. A
  version bump is the preferred remediation and was the first one attempted; it does not exist yet.
- **The determination expires by itself, and that is checked.** The plugin runs with
  `failBuildOnUnusedSuppressionRule` set true, so the moment a patched release is adopted the rule stops
  matching and the build **fails on the unused rule**, demanding the entry's removal. That behaviour was
  verified by pointing the rule at a non-matching identifier and observing
  `Suppression Rule had zero matches` fail the build — it is a mechanism, not an intention.
- **The gate is still armed for everything else.** Also verified rather than asserted: re-running the
  scan with the threshold lowered to 5.0 fails the build on the MEDIUM below, proving the determination
  excludes one named identifier on named artifacts and nothing more.
- **The determination's scope is asserted by a test, so it cannot widen quietly, and neither can this
  page.** `BuildAndCiContractTest` pins the rule's shape — one rule, one identifier, one artifact scope,
  no wildcard platform record, no coordinate regex — and `GateVerificationTest` additionally reads **both
  halves** of the machine-readable report: the ordinary findings must carry nothing at or above 7.0, and
  every *suppressed* finding at or above 7.0 must be this one identifier on a `tomcat-embed` artifact.
  That second half is what closes the gap a suppression otherwise opens, because a widened rule empties
  the array a report check would normally read. The same test asserts that the withdrawn "zero findings"
  wording is absent from both the build file and this page, so the overstatement cannot come back by
  edit.
- **Two sub-threshold mediums, both CVSS 5.3, both reported and deliberately not suppressed.**
  `CVE-2026-41178` against `opentelemetry-semconv` describes baggage-header parsing in OpenTelemetry
  **Go**; the CPE carries `go` as its target software and has been matched to a Java artifact.
  `CVE-2026-64607` against `httpclient5`, which arrives transitively with the container testing
  transport, appeared between two recorded runs **with no dependency version changing** — the advisory
  feed moved, not this module. Both are left visible in the report because hiding a sub-threshold
  finding buys nothing and costs the next reader the chance to re-judge it, and the count is dated in
  [`../docs/gate-evidence.md`](../docs/gate-evidence.md) rather than presented as standing.
- **The rules for writing a determination are in the file itself**, and the first of them is that a fix
  outranks a determination. The header also records the case that went the other way: where vulnerable
  classes are physically *present*, this module does not suppress.
- **What ships is still enforced by a test, not by a promise.** `DeployableSupplyChainIT` opens the
  repackaged jar and fails if any test-scoped artifact appears among its bundled libraries, so the
  boundary between the build's surface and the product's surface stays checked on every run.

The traceability matrix is the largest single deliverable of this gate, and it is
**[published](../docs/traceability-matrix.md)**, and not pending. Its shape is exact — **544 rows**, one per procedure
unit, each naming the source member, the paragraph, the source line, the target Java class, the target
method and the covering test, with both provenance identifiers in its header. The row count is asserted by
`e2e/GateVerificationTest`, and asserted the only way that means anything: the 544 is checked against the
paragraph labels the members **actually declare**, member by member, rather than against itself, so a row
invented for a paragraph that does not exist fails the build. Thirty-eight of the 544 rows are marked
rather than plain — documented non-implementations and deliberately unrouted paragraphs rather than
translations — so that the count stays honest.

**A row count alone would not have been enough, and the gate does more than count.**
`e2e/GateVerificationTest` asserts the 544, each member's subtotal against the paragraphs that member
actually declares, both provenance anchors, the marker distribution, and — for every row — that the target
class exists under `src/main/java`, that it *declares* the named method, and that the covering test file
exists under `src/test/java`. Rows whose method no delivered call site reaches carry a stronger obligation
still: their covering test must name the method, and for a paragraph head must actually call it.

Marked rows keep the count honest, and the four markers sum to the total — 1 + 3 + 18 + 16 + 506 = 544:

1. `†` the fee-computation paragraph that is genuinely invoked but implements nothing;
2. `‡` three preserved source anomalies, including the duplicated exit paragraph in the account-view
   program where two identically-named paragraphs collapse to one method;
3. `§` the eighteen paragraphs of the orphaned extract program, which no job stream invokes;
4. `¶` sixteen paragraphs that no delivered call site reaches, across four members: three account-update
   edits the driver deliberately never routes to, the account-view long-text sender, both card-list
   diagnostic senders, the card-detail alternate-index read and its long-text sender, and the eight paired
   exits reachable only from those heads. Each head is exercised directly, by name, by the test its row
   names, and each exit is named there; the gate asserts the per-member split as well as the total, because
   a total cannot see a row moving between members.

A fourth artifact completes the audit trail without contributing a row: the unreferenced copybook,
recorded in the decision log as consciously excluded dead code and asserted absent from the matrix,
because a row for it would make the total 545.

### What has been demonstrated locally

The original design analysis could execute only compilation and dependency resolution. The delivered
module has since been run with Docker available, so that historical limitation is no longer the
validation status of this code:

| Gate area | Current evidence |
|---|---|
| Zero-warning build, byte equivalence, named fixtures, interface contracts and coverage | **Executed and passed** by `./mvnw -B clean verify`, including the unit and container-backed integration/end-to-end tiers and the enforced JaCoCo rule. |
| Dependency supply chain | **Executed and passed** across production and test scope. One HIGH is carried by the single scoped determination in `owasp-suppressions.xml`, with unused-suppression enforcement on so it expires by itself; the sub-threshold MEDIUM is left visible and unsuppressed. Nothing else at HIGH or CRITICAL is reported. |
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
| Service implementations | **37** | every concrete `*Service.java` in the `service` package, and the arithmetic closes: **26** translation-bearing services, one per program or program family and exactly the set the migration plan names, plus **11** focused support services — account and card concurrency tokens, credential digesting, field encryption, sign-on state, the three page-token services, batch launch, job-completion notification and field-error translation. The package holds **63** files in all: those 37, plus **26** service-owned records, commands, outcomes, ports and view types, which are not `*Service.java` and are not counted here |
| Batch job configurations | **9** | plus eight step components and the shared step template |
| Hand-written record mappers | **12** | every `*RecordMapper.java` in `util`: one per verified record layout plus the statement work-area mapper, all explicit offsets, no reflection |
| Request/response DTO files | **32** | every file in `api/dto`, derived from the 17 symbolic maps plus the shared transport types they need |

Count any of them yourself rather than trusting the table:

```bash
cd carddemo-java
ls src/main/java/com/carddemo/service/*Service.java | wc -l      # 37
ls src/main/java/com/carddemo/service/*.java | wc -l              # 63 — the package total
ls src/main/java/com/carddemo/util/*RecordMapper.java | wc -l     # 12
ls src/main/java/com/carddemo/api/dto/ | wc -l                    # 32
```

Optimistic locking is applied where the legacy code compared a before-image with an after-image, and it
takes **two** mechanisms because that comparison spanned two windows. `@Version` on the account and card
entities guards the **in-transaction** interval between this transaction's read and its write.
`AccountConcurrencyTokenService` and `CardConcurrencyTokenService` guard the **screen-to-confirm**
interval — the legacy carried the old image with the conversation and compared it under lock on the
confirming turn, which no single transaction spans. The estate's single rollback point becomes a
transactional rollback raising a conflict exception, and either mechanism can raise it.

The full layer diagram, the entity relationships and the batch pipeline ordering live in
[`../docs/architecture.md`](../docs/architecture.md) rather than being duplicated here — that page is
**published**, and the table above and `PackageLayeringTest` remain the enforced authority on the
dependency direction, because a diagram documents a rule and a test holds it.

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

- **No reflection-based or annotation-driven record mapping.** All **twelve** mappers use explicit
  offsets — one per persisted record layout, of which there are eleven, plus `StatementWorkRecordMapper`
  for the statement job's transient work record.
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
  line. They are not the same case, and the difference is recorded rather than smoothed over. The second
  address line is genuinely unedited — its label assignment is commented out in the source and no edit runs
  for it anywhere — so carrying no constraint is faithful. The middle name **is** edited in the legacy
  program: the optional alphabetic edit is performed for it, even though the screen-attribute comment above
  the field says otherwise. The migration directive forbids attaching a constraint to either field, so the
  directive governs and the middle name carries none; **that is a deliberate divergence from the source's
  behaviour and is logged as one.** Either way, do not add constraints here.

### The tie-break rule

**Where faithful translation and idiomatic Java conflict, faithful wins — and the divergence is
recorded in [`../docs/decision-log.md`](../docs/decision-log.md) rather than resolved by taste.** That
single rule is what decides every hard case above: truncating instead of rounding, preserving an empty
paragraph, reproducing a malformed literal, and declining to validate two fields the source decorates
but never checks.

### Source anomalies are documented, never propagated

The source anomaly register in the decision log carries **31** entries as it stands — the fourteen the
specification catalogued plus the ones found while translating — and it grows as reading the estate turns up
more, so read the register rather than a number quoted here. **None is propagated into new logic, and none is
silently corrected where correcting it would alter a record layout or an external contract.** Three
examples of how that resolves in practice:

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
│   ├── db/migration/schema/  V1__create_schema.sql   V2__create_indexes.sql
│   │                         V2_2__add_protected_value_invariants.sql
│   ├── db/migration/seed/    V3__seed_reference_data.sql   V4__seed_user_security.sql
│   └── lookup/     nanpa-area-codes.json  us-state-codes.json  state-zip-prefixes.json
├── src/test/java/com/carddemo/     unit (*Test), integration (*IT), support/ base classes
└── src/test/resources/
    ├── application-test.yml
    ├── fixtures/input/       the nine ASCII datasets, plus the user-security seed
    └── fixtures/expected/    golden output at 40, 80, 100, 133 and 430 bytes, all separator-free
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

**Every document referenced from this README is published — these you can open:**

| Document | What it is |
|---|---|
| [`../README.md`](../README.md) | The estate-level narrative: the mainframe application, its installation, its batch execution order and its screen inventory, and the migration summary that points here |
| [`../docs/onboarding-guide.md`](../docs/onboarding-guide.md) | The first-run walkthrough: prerequisites, the build commands and what each one proves, stack bring-up, sign-on, and running the eight gates |
| [`../docs/architecture.md`](../docs/architecture.md) | Layer map, package responsibilities, entity relationships and batch pipeline ordering — including why the batch tier carries nine job configurations rather than one per job step |
| [`../docs/traceability-matrix.md`](../docs/traceability-matrix.md) | **544 rows** — every procedure unit mapped to its Java class, method and covering test, citing both provenance identifiers |
| [`../docs/decision-log.md`](../docs/decision-log.md) | Every divergence between COBOL semantics and idiomatic Java, and the source anomaly register — 31 entries as it stands, of which the migration plan named the first fourteen plus a fifteenth observation — with both provenance identifiers in its own Provenance section |
| [`../docs/gate-evidence.md`](../docs/gate-evidence.md) | Per gate: the command that produces the evidence, the artefact it lands in, and the standing result. Carries the Gate 6 audit counts and the measured-runs table for the Gate 3 figures |
| [`../docs/presentation/index.html`](../docs/presentation/index.html) | The migration summary deck, in six slides: what was migrated, what each construct became, the shape of the target module, the load-bearing translation decisions, **the acceptance-criteria model — what each of the eight gates verifies and the obligation it discharges** — and where the detail lives. It deliberately does **not** carry gate results: every run outcome is deferred to [`../docs/gate-evidence.md`](../docs/gate-evidence.md), which the deck names as the single authority for them |
| [`../docs/project-guide.md`](../docs/project-guide.md) | The prior delivery's completion record — the authoritative source for the historical test and coverage figures quoted under Gate 7 |
| [`../docs/technical-specifications.md`](../docs/technical-specifications.md) | The migration's technical specification |

Seven of them are registered in [`../mkdocs.yml`](../mkdocs.yml)'s explicit `nav` list, which is what makes
them publish: a page on disk that the `nav` does not name is a page the documentation site does not build.
The two that are not — the estate-level `README.md` and the summary deck — are not `nav` pages by design:
the first is the repository landing page and the second is a standalone HTML deliverable.

**Not yet published: nothing.** This line is the ledger a reader checks before trusting the table above,
and it is kept even though it is empty, because an absent ledger and an empty one read the same and only
one of them is a statement. While a page was outstanding it was listed here; the two that were — the
first-run walkthrough and the summary deck — are delivered and appear in the table above.

Confirm that rather than trusting this table; it prints one line per referenced path:

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
2. reads the whole committed tree of `carddemo-java`, `docs` and `.github` with git's own
   whitespace diagnostic and **fails the run on any trailing whitespace**, before the toolchain is
   even installed. The legacy estate under `app/` and `samples/` is deliberately outside that
   pathspec because it must stay byte-identical, and the fixed-width fixtures are exempted by
   `.gitattributes` because their space padding is record content rather than a defect;
3. derives `APP_VERSION`, `SOURCE_REVISION` and `SOURCE_DATE_EPOCH` once and passes that same identity
   through Maven, the packaged build information, the OCI labels and the Compose build;
4. restores the Maven, dependency-check and Trivy data sets from cache;
5. runs **`./mvnw -B clean verify`**: zero-warning compilation, both test tiers, **JaCoCo enforcement
   at 80% line coverage**, and an executed OWASP scan of production **and test** scope. Testcontainers'
   shaded zerodep transport is excluded in favour of the visible Apache HTTP client 5 transport, with
   `httpcore5` and `httpcore5-h2` pinned to the remediated release; the test-scope compatibility
   adapter preserves the class name Testcontainers 1.21.4 instantiates;
6. re-executes the unit tier under the hostile `tr-TR` and `ar-EG` locales, so no assertion silently
   depends on the default locale of the machine that ran it;
7. lints the container-bootstrap scripts;
8. uploads the coverage, vulnerability and test reports, so they survive even if a later gate fails;
9. performs a second clean build with the same revision and epoch and requires the two jars to be
   byte-identical — **the reproducibility gate runs before the smoke test**, so that the jar the smoke
   test then starts is one already proved reproducible;
10. smoke-tests that executable jar;
11. checks and builds the Dockerfile, resolves and starts the hardened six-service Compose stack,
   waits for application health, verifies Grafana provisioning, executes a Prometheus query and
   checks the Jaeger API, with trap-based teardown of containers and volumes;
12. runs digest-pinned Trivy scans over **every image the stack ships at run time** — the application
   image, both Temurin base images, and every digest-pinned third-party Compose service image — and gates
   all of them on the same terms. A HIGH or CRITICAL in any of them fails the gate unless a **scoped,
   reviewed, expiring determination** in [`container-scan-determinations.txt`](container-scan-determinations.txt)
   covers that one identifier on that one image; an **expired** determination fails the build, and so does
   an **unused** one. The file ships carrying none, so nothing is accepted today. Nothing is filtered out
   of any scan or report: the scanner runs with `--exit-code 0` and no ignore list, and the archived JSON
   carries every finding whether a determination covers it or not. DL-185 and DL-350;
13. uploads the container-scan reports and the executable jar, and writes the linear gate summary.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `docker compose up` fails binding a port | Something already owns one of the eight published ports: 8080 (app), 5432 (PostgreSQL), 4566 (LocalStack), 9090 (Prometheus), 3000 (Grafana), or Jaeger's 16686 (UI), 4317 (OTLP gRPC) and 4318 (OTLP HTTP). The two OTLP ports are the ones most often already taken, because any other collector on the machine wants them too. Every port is overridable: `APP_PORT=18080 POSTGRES_PORT=15432 JAEGER_OTLP_GRPC_PORT=14317 JAEGER_OTLP_HTTP_PORT=14318 docker compose up -d`. |
| A colleague or another host cannot reach the stack | Working as intended, and it is a security control rather than an inconvenience: every port binds `127.0.0.1` and the profile behind them carries a committed signing secret, cleartext transport and ten seeded identities. Forward the port over SSH — `ssh -L 8080:127.0.0.1:8080 <host>` — or deploy the `prod` profile. See [Reaching the stack from another machine](#reaching-the-stack-from-another-machine); rotating a credential does **not** make a published local stack safe. |
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
[`../docs/decision-log.md`](../docs/decision-log.md) in the same pull request. A change that adds,
renames or moves a method that a matrix row names must update that row in
[`../docs/traceability-matrix.md`](../docs/traceability-matrix.md) in the same pull request too. Part of
that is mechanical and part is not, and the difference is worth knowing: `GateVerificationTest` checks the
row total, each member's row count against the paragraphs that member declares, both provenance anchors
and the class-and-method cells of the marked rows — so a row added, dropped or reattributed to the wrong
member is a **failing build**. It does not resolve all 544 target methods against the source tree, so a
plain rename of an unmarked row's method is a stale row that no test catches. That asymmetry is exactly
why the reformatting instruction above matters: a sweep would put rows out of date faster than review
could find them.

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
| 6 | **Versioned, forward-only schema evolution** | **Five** Flyway migrations from **two sibling locations whose shared parent holds no script**: the three schema scripts `V1`, `V2` and `V2_2` in `db/migration/schema`, which every profile resolves, and the two seeds `V3` and `V4` in `db/migration/seed`, which only `local` and `test` resolve. Production resolves the schema location **alone** and pins `target: "2.2"`, the highest version that location delivers, asserted against the delivered scripts by `FlywayConfigTest` so a new schema script cannot be silently skipped. `validate-on-migrate` is on everywhere. `clean` is **disabled by the shared baseline and by `prod`**, and deliberately re-enabled by the profiles whose database is disposable — `local`, so a developer can drop and re-apply a migration they are editing, and both copies of `test`, whose database is a per-run container. The concession is taken in those overlays rather than inherited, so it cannot reach production by omission. The inventory above is the summary; [Database migrations](#database-migrations) is the authority, and `DocumentedSourceCountsTest` holds this row to it. |
| 7 | **A test pyramid with an enforced floor** | Unit tests over mappers, validators and services; integration tests against real containers; end-to-end tests over the full pipeline. JaCoCo fails the build below 80% line coverage; branch coverage is reported, not gated. |
| 8 | **Supply-chain hygiene** | `dependency-check-maven` bound to `verify` and **executed**, failing at CVSS 7.0 on the **compile, runtime and test** graph (`skipTestScope` is `false`), with reports emitted in three formats and uploaded by CI. The enforced invariant is zero **unsuppressed** critical or high findings plus **one** scoped, evidenced, self-expiring determination, disclosed with its three-state vocabulary under [Gate 8](#gate-8-integration-sign-off) rather than left implicit. An earlier revision of this row described a compile-and-runtime-only scan and two HIGH findings in an excluded test graph; both statements are withdrawn — the transport that carried them was replaced, not excluded. |
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
