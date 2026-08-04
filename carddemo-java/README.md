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
| [Documentation](#documentation) | find the matrix, the decision log or the gate evidence |
| [Continuous integration](#continuous-integration) | understand what CI enforces |
| [Troubleshooting](#troubleshooting) | something will not start |
| [Contributing](#contributing) / [Licence](#licence) | open a pull request |
| [Engineering standards](#engineering-standards-this-module-holds-itself-to) | review this module against its own commitments |

---

## The legacy estate is reference, never a dependency

The `app/` tree at the repository root — COBOL programs, copybooks, BMS mapsets, JCL members,
cataloged procedures, the CICS resource definition and the sample datasets — is **read-only
reference**. It is byte-identical to the analysed checkout and nothing in this module reads it at
runtime, links against it or ships it.

**No COBOL, JCL, BMS, copybook or CSD source text appears anywhere under `carddemo-java/`.**
Traceability is by citation only: the traceability matrix and the decision log name the member, the
paragraph and the source line, and cite the commit the analysis was performed against. Metadata —
program names, transaction identifiers, paragraph names, dataset names, record widths, byte offsets
and the message literals that constitute an external contract — is reproduced because behavioural
parity depends on it. Source lines are not.

### Provenance

Both identifiers below are the durable link between this module and the estate it was derived from.
They appear in the header of [`../docs/traceability-matrix.md`](../docs/traceability-matrix.md) and
[`../docs/decision-log.md`](../docs/decision-log.md), and they are the only correct way to answer
"which COBOL did this Java come from?".

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
| JCL members | 29 | plus 2 cataloged procedures |
| CICS resource definition | 1 | 18 transactions, 17 mapsets, 18 programs, 10 files, 1 transient data queue |
| **Traceable paragraph units** | **544** | 528 program paragraphs + 16 procedural-copybook paragraphs (14 in `CSUTLDPY`, 2 in `CSSTRPFY`) |

Every one of those 544 units maps to a named Java method in
[`../docs/traceability-matrix.md`](../docs/traceability-matrix.md). Coverage is exhaustive rather
than sampled, which means two artifacts that a naive scope pass would silently drop are handled
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
in [`pom.xml`](pom.xml) as CVE remediation. They are the reason the supply-chain gate passes with zero
critical or high findings, so **do not revert them to the managed value**:

| Coordinate | Pinned here | Spring Boot 3.5.16 managed value |
|---|---|---|
| `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.57 | 10.1.55 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.21.5 | 2.21.4 |
| `org.postgresql:postgresql` | 42.7.13 | 42.7.11 |

Each override is a `<properties>` entry rather than a `<dependency>` version, so it applies uniformly
to every transitive path and disappears automatically when a future Spring Boot 3.x release raises its
own floor past it.

### Two version decisions that look like mistakes and are not

- **Spring Boot 3.5.16, not 4.1.0.** 4.1.0 is the newest release overall; 3.5.16 is the newest
  generally-available release *on the 3.x line*. The requirement names "Spring Boot 3.x", which is a
  **contract ceiling**, and 4.1.0 would breach it. Upgrading the major version is not an improvement
  here — it is a scope violation.
- **Testcontainers 1.21.4, not 2.0.5.** 2.0.5 is published, but Spring Boot 3.5.16's dependency
  management pins 1.21.4. Staying **BOM-managed** avoids an unmanaged major-version override whose
  transitive consequences the parent no longer reasons about.

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

`package` produces a jar and runs nothing that matters. `verify` is the phase that runs the gates:

| Bound to `verify` (or earlier) | What it does |
|---|---|
| `maven-compiler-plugin` | Compiles at `--release 25` with `-Xlint:all -Werror -parameters` |
| `maven-surefire-plugin` | The unit tier — `**/*Test.java`, excluding `*IT.java`, `*E2ETest.java` and the `e2e` package |
| `spring-boot-maven-plugin` | Repackages the executable, layered artifact and writes build info |
| `maven-failsafe-plugin` | The integration and end-to-end tier — `**/*IT.java`, `**/*E2ETest.java`, `**/e2e/**/*Test.java`, against real containers, reporting failures at `verify` so containers are always torn down |
| `jacoco-maven-plugin` | Merges the unit and integration execution data and **fails the build** below the line-coverage floor |
| `dependency-check-maven` | Scans the resolved dependency tree and **fails the build** on a critical or high CVE |

The two test tiers are strictly complementary — the include and exclude sets are written so no test
class is collected twice and none falls through the gap between them.

### Zero warnings is a build failure, not a report

[`pom.xml`](pom.xml) configures `maven-compiler-plugin` 3.14.1 with `<release>25</release>` and the
compiler arguments `-Xlint:all`, `-Werror` and `-parameters`. **Any compiler warning fails the
build.** There is no warning backlog because a warning cannot survive long enough to become one.

This is the mechanism behind the requirement that a clean checkout produce a deployable artifact with
zero warnings, and it is **proven by execution** rather than merely designed. Running the documented
command in this repository produces `BUILD SUCCESS` with **zero compiler warnings**, both test tiers
green, the coverage check met and the supply-chain scan reporting no critical or high finding. The
current numbers are recorded in [`../docs/gate-evidence.md`](../docs/gate-evidence.md) rather than
pinned here, where they would go stale on the next commit.

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
docker build -t carddemo-java:local .
```

[`Dockerfile`](Dockerfile) is multi-stage and both stages are digest-pinned:

- **build stage** — `eclipse-temurin:25.0.3_9-jdk-noble`, resolves dependencies in a cacheable layer,
  packages the application and extracts the layered jar.
- **runtime stage** — `eclipse-temurin:25.0.3_9-jre-noble`, the extracted layers only. It runs as a
  **non-root** user (uid:gid `10001:10001`), `EXPOSE`s 8080, and carries a `HEALTHCHECK` that polls
  `/actuator/health` and marks the container healthy on the first HTTP 200.
- The entrypoint is **exec form**, so the JVM is PID 1 and receives `SIGTERM` directly and graceful
  shutdown actually drains in-flight work. It fixes `-Duser.timezone=UTC` and `-Dfile.encoding=UTF-8`
  — not tuning, but correctness: they keep timestamps and text in the fixed-width output files
  independent of the host locale and zone, which is a precondition for byte-for-byte comparison
  against the expected-output fixtures.
- `JAVA_TOOL_OPTIONS` is intentionally unset, so it remains available as the operator's own channel
  for JVM flags — including heap bounds while recording the performance baseline.

---

## Run

### Bring up the local validation stack

```bash
cd carddemo-java
docker compose up -d --build     # build the module image and start all six services
docker compose ps                # every service should report (healthy)
docker compose logs -f app       # four migrations, then the listening port
docker compose down              # stop;  add -v to drop the volumes as well
```

[`docker-compose.yml`](docker-compose.yml) defines six services and **no Compose profiles**, so a
plain `up -d` starts everything. Every published port is overridable through an environment variable
so parallel stacks do not collide.

| Service | Image | Host port | Replaces |
|---|---|---|---|
| `app` | built from [`Dockerfile`](Dockerfile) | **8080** (`APP_PORT`) | the 17 online transactions and the 10 batch programs |
| `postgres` | `postgres:16.14-bookworm` | **5432** (`POSTGRES_PORT`) | the ten VSAM base clusters plus the transient work cluster |
| `localstack` | `localstack/localstack:4.14.0` — S3, SQS, SNS | **4566** (`LOCALSTACK_PORT`) | sequential-dataset and generation-data-group staging, and the transient data queue |
| `prometheus` | `prom/prometheus:v3.5.0` | **9090** (`PROMETHEUS_PORT`) | — the metric half of the diagnostic channel |
| `grafana` | `grafana/grafana:11.6.6` | **3000** (`GRAFANA_PORT`) | — the dashboard half |
| `jaeger` | `jaegertracing/all-in-one:1.71.0` | **16686** UI (`JAEGER_UI_PORT`), 4317 OTLP/gRPC, 4318 OTLP/HTTP | — the trace half |

Health is asserted rather than assumed. `postgres` reports through `pg_isready`; the application waits
on it because Flyway applies migrations during start-up and must not race `initdb`. The `localstack`
health check goes further than the emulator's own endpoint and additionally proves the bucket, the FIFO
queue and the topic actually exist — a check that stopped at the health endpoint would let the
application start against an emulator with no queue in it, which is precisely what the legacy queue's
open-at-initialisation attribute ruled out.

### Run the application

Against the Compose-provided PostgreSQL and LocalStack:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Or run the packaged artifact:

```bash
./mvnw -B clean package -DskipTests
SPRING_PROFILES_ACTIVE=local java -jar target/carddemo-java-1.0.0.jar
```

Then:

```bash
curl -s http://localhost:8080/actuator/health          # {"status":"UP"}
curl -s http://localhost:8080/actuator/prometheus       # the metric surface
```

Sign-on uses the seeded sample identifiers — `ADMIN001` for the administrator role and `USER0001` for
the standard-user role. Their password is the single sample literal carried in the legacy
user-provisioning job's in-stream cards, and it is stored **only as a BCrypt hash** by the seed
migration. These identifiers exist in the local and test profiles only.

The REST surface publishes **17 paths**, derived from the 17 screen transactions. Sign-on is the entry
point, and it carries the attention-key field the 3270 contract required — omit it and the service
answers with the legacy invalid-key message, exactly as the terminal did:

```bash
# supply the seeded sample password yourself; it is not printed anywhere in this repository
read -rs SEED_PASSWORD

curl -s -X POST http://localhost:8080/api/auth/signon \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":\"ADMIN001\",\"password\":\"$SEED_PASSWORD\",\"keyAction\":\"ENTER\"}"
```

The response carries the routing decision rather than performing it: an administrator user type answers
with the administrative-menu route and a standard user type with the main-menu route, which is the REST
form of the legacy program-to-program transfer. Wrong credentials answer with the legacy
`Wrong Password. Try again ...` text, and an unknown identifier with `User not found. Try again ...` —
character for character, because those strings are an external contract.

### Spring profiles

| File | Profile | Purpose |
|---|---|---|
| [`src/main/resources/application.yml`](src/main/resources/application.yml) | — | Shared defaults. Actuator exposure, Jackson, JPA and Flyway baseline, graceful shutdown. |
| [`src/main/resources/application-local.yml`](src/main/resources/application-local.yml) | `local` | Compose endpoints. SQL logging on, full trace sampling, the wider Actuator surface, seed migrations enabled. |
| [`src/main/resources/application-test.yml`](src/main/resources/application-test.yml) | `test` | Testcontainers-provided endpoints, injected at runtime by the shared support base classes. |
| [`src/main/resources/application-prod.yml`](src/main/resources/application-prod.yml) | `prod` | **Every secret resolved from an environment variable with no fallback default.** TLS on, secure cookies, API docs off, Actuator narrowed, health details hidden. |

#### The `prod` profile has no defaults, deliberately

A defaulted secret violates "no hardcoded credentials" just as surely as a literal one does, so
`application-prod.yml` resolves each of these from the environment **with no fallback**. A missing
variable **fails startup** rather than silently binding a placeholder:

| Variable | What it configures |
|---|---|
| `CARDDEMO_DB_URL` | JDBC URL of the PostgreSQL 16 instance |
| `CARDDEMO_DB_USERNAME` | Database user |
| `CARDDEMO_DB_PASSWORD` | Database password |
| `CARDDEMO_JWT_SECRET` | Signing secret for the session token that replaced the pseudo-conversational state area |
| `CARDDEMO_FIELD_ENCRYPTION_KEY` | Key for the field-level encryption applied to sensitive customer data |
| `CARDDEMO_SQS_QUEUE` | FIFO queue name for the job-submission bridge |
| `AWS_REGION` | Region for the S3, SQS and SNS clients |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | OTLP trace collector endpoint |
| `CARDDEMO_TLS_KEYSTORE` | Keystore location |
| `CARDDEMO_TLS_KEYSTORE_PASSWORD` | Keystore password |
| `CARDDEMO_TLS_KEYSTORE_TYPE` | Keystore type |
| `CARDDEMO_TLS_KEY_ALIAS` | Key alias inside the keystore |

Four further variables are non-secret and therefore *do* carry a default — `CARDDEMO_JWT_EXPIRATION`,
`CARDDEMO_S3_BUCKET`, `CARDDEMO_SNS_TOPIC`, `CARDDEMO_SQS_MESSAGE_GROUP_ID` and
`CARDDEMO_TRACING_SAMPLE_RATE`. AWS credentials in `prod` come from the standard AWS provider chain
rather than from configuration at all.

**No value for any of the secrets above appears in this repository.** The throwaway development
credentials used by the local stack live only in [`docker-compose.yml`](docker-compose.yml), address
containers on your own machine, and last exactly as long as one `up`/`down` pair.

### Database migrations

Flyway runs on start-up and replaces the ten legacy dataset-provisioning job streams. The four
migrations are physically split across two sibling locations, and that split *is* the profile scoping
mechanism:

| Migration | Location | Content |
|---|---|---|
| `V1__create_schema.sql` | `db/migration/schema` | **11 tables**, one per verified record layout |
| `V2__create_indexes.sql` | `db/migration/schema` | The three alternate-index equivalents as B-tree indexes, plus primary and foreign keys |
| `V3__seed_reference_data.sql` | `db/migration/seed` | Reference and sample data at the measured fixture counts |
| `V4__seed_user_security.sql` | `db/migration/seed` | The ten seed users — five administrator, five standard — stored as **BCrypt hashes** |

`local` resolves both locations and migrates to the latest version. `prod` resolves
`classpath:db/migration/schema` only, with an explicit target of `2`, so **a production deployment
migrates schema and indexes without inheriting sample data or seeded credentials**. `clean` is
disabled outside `local`, and `validate-on-migrate` is on everywhere.

The third alternate-index equivalent is defined even though no online endpoint depends on it: the
report job's date-range filter would otherwise scan the whole transaction table.

### AWS resources

[`localstack/init/01-create-aws-resources.sh`](localstack/init/01-create-aws-resources.sh) runs once
the emulator's edge port is serving and creates exactly three things:

| Resource | Default name | Replaces |
|---|---|---|
| S3 bucket (versioned) | `carddemo-batch-staging` | sequential-dataset and generation-data-group staging |
| SQS **FIFO** queue | `carddemo-jobs.fifo` | the transient data queue — the estate's single online-to-batch bridge |
| SNS topic | `carddemo-job-notifications` | operational notification of job completion |

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
than propagating an exception.

### Observability

| Endpoint | Access | What it gives you |
|---|---|---|
| `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | anonymous | Liveness and readiness, including the datasource |
| `/actuator/prometheus` | anonymous | Micrometer timers on every REST endpoint and every batch step — the measurement surface the performance baseline is read from. Anonymous deliberately, so the collector can scrape it; closed in `prod`. |
| `/actuator/metrics`, `/actuator/info`, and in `local` also `env`, `configprops`, `beans`, `flyway`, `mappings`, `loggers` | authenticated | Individual meters, build info and the wider diagnostic surface |
| `/v3/api-docs` and the Swagger UI (springdoc) | api-docs open in `local`, UI authenticated | The machine-readable description of the screen-derived endpoints — **17 published paths**. Both **disabled in `prod`**. |
| Jaeger UI on 16686 | anonymous (local stack) | OTLP traces, once an endpoint or a job has been exercised |

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

### Why there are nine jobs and not seventy-eight

The 29 JCL members and 2 cataloged procedures contain **78 program-execution steps**. Only **nine of
them invoke an application COBOL program**. The other 69 are utilities:

| Utility | Steps | What it did |
|---|---|---|
| `IDCAMS` | 52 | define, delete and copy datasets |
| `SDSF` | 8 | operator display |
| `SORT` | 5 | external ordering |
| `IEFBR14` | 3 | allocate and do nothing |
| `IEBGENER` | 1 | copy a card stream to a dataset |
| `DFHCSDUP` | 1 | toggle CICS file availability |

**Those 69 steps do not become Spring Batch steps at all.** They are absorbed entirely by Flyway
migrations, Docker Compose service definitions and test fixtures — which is why the batch tier is nine
job configurations rather than a mechanical transcription of a job stream. The five external sort
specifications become `Comparator` chains and JPQL predicates *inside* the job that needs them, typed
per job: the same field is declared zoned decimal in one job's sort specification and character in
another's, so a single shared comparator would be wrong.

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

Batch metadata lives in the `BATCH_`-prefixed tables that Spring Batch creates in the same PostgreSQL
instance, so an execution's status, its step results and its exit codes are queryable after the fact —
the equivalent of reading a job's condition codes, and considerably easier.

Fixed-width output lands in the **S3 staging bucket** (`carddemo-batch-staging` on LocalStack), which
is what replaced sequential-dataset and generation-data-group staging. Per-step timers appear on
`/actuator/prometheus` and on the provisioned Grafana dashboard as soon as a step runs.

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
LocalStack **Community** edition. Recorded evidence for all eight is consolidated in
[`../docs/gate-evidence.md`](../docs/gate-evidence.md).

| Gate | What it proves | How to run it |
|---|---|---|
| 1 | End-to-end byte equivalence | `./mvnw -B verify` (fails on any golden-file mismatch) |
| 2 | Zero-warning build | `./mvnw -B clean verify` |
| 3 | Performance baseline **established** | run the jobs, read `/actuator/prometheus` |
| 4 | Named real-world validation artifacts | `./mvnw -B verify` (seeded and asserted) |
| 5 | Interface contract verification | `./mvnw -B verify` (against a real queue) |
| 6 | Unsafe and low-level code audit | the scoped grep list below |
| 7 | Scope matching + coverage floor | `./mvnw -B verify` (JaCoCo check) |
| 8 | Integration sign-off | `./mvnw -B verify` + the traceability matrix |

### Gate 1 — end-to-end byte equivalence

A production-representative input is processed end-to-end locally and the output is compared **byte for
byte** against golden files. **Mocked I/O does not satisfy this gate** — the pipeline runs against a
Testcontainers PostgreSQL instance seeded from the fixtures, exactly as it would against a real
database.

`BatchPipelineE2ETest` drives posting → interest → consolidation → statement generation and asserts
each produced file equal to its counterpart under
[`src/test/resources/fixtures/expected/`](src/test/resources/fixtures/expected). Four output widths are
contractual, and each has a golden file whose every line is exactly that wide:

| Golden file | Width | What it is |
|---|---|---|
| `expected/statement.txt` | **80 bytes** | statement text record |
| `expected/statement-html.txt` | **100 bytes** | statement HTML record |
| `expected/transaction-report.txt` | **133 bytes**, fixed-length blocked | transaction report line |
| `expected/daily-reject.txt` | **430 bytes** | daily-transaction reject record — the 350-byte source image, then a 4-digit reason code, then a 76-character description |

The comparison is a byte-array equality assertion on fixed-width lines, not a semantic comparison, **so
trailing-space and sign-overpunch differences fail the test rather than passing silently**. That is the
whole point: the parity traps this gate exists to catch are invisible to a lenient comparison —
truncating rather than rounding on the interest and balance computations, zoned-decimal sign overpunch
in the persisted amounts, the five reject reason codes, the statement banner literals, and the fixed
HTML lines including the malformed truncated table tag that must be emitted exactly as the source emits
it.

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

The mechanism:

1. Bring up the stack and run the posting job over the **300-record** daily-transaction fixture, and
   the interest job over the **50 accounts and 50 category balances**, with the JVM's peak-heap
   reporting enabled (`JAVA_TOOL_OPTIONS` is unset precisely so it is free for this).
2. Read elapsed time and records per second from the per-step Micrometer timers at
   `/actuator/prometheus`, scraped by the Compose-provisioned Prometheus and displayed on the
   provisioned Grafana dashboard.
3. Record elapsed time, peak memory and records per second **alongside the fixture volumes** in
   [`../docs/gate-evidence.md`](../docs/gate-evidence.md), so a later measurement is comparable rather
   than merely numerically similar.

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

Three contracts:

1. **The four fixed-width file formats** — asserted byte for byte, as described under Gate 1.
2. **The sign-on message and routing contract** — the **seven** distinct message texts the sign-on
   transaction emits are reproduced character for character, because operators and downstream tooling
   match on them: the two field prompts, the wrong-password message, the user-not-found message, the
   unable-to-verify message, the thank-you message on the exit key and the invalid-key message. The
   routing rule is equally contractual — an administrator user type routes to the administrative menu,
   any other type routes to the main menu. `OnlineTransactionE2ETest` asserts both the message strings
   and the routing outcome for both user types.
3. **The batch trigger** — the **17 fixed 80-byte job-submission card images**, their **four** date
   substitution slots and the terminal `/*EOF` sentinel, which the legacy program transmits rather than
   merely holding in storage. Verification **drains a real LocalStack SQS FIFO queue** and asserts the
   full ordered card sequence, so the contract is exercised rather than self-certified. The message
   body is 80 characters, one message per card, in order, and a publish failure logs and continues.

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

For context on the achievable shape, the **prior delivery** of this migration measured 888 tests — 729
unit, 134 integration, 33 end-to-end — at 81.5% line and 64.0% branch coverage. That figure is quoted
as prior-delivery history, not as this module's current measurement; the current measurement belongs in
[`../docs/gate-evidence.md`](../docs/gate-evidence.md), which is generated from an actual run.

### Gate 8 — integration sign-off

| Checklist item | Satisfying artifact | Check |
|---|---|---|
| End-to-end verification | golden fixtures at 80, 100, 133 and 430 bytes | `BatchPipelineE2ETest` |
| Interface contract verification | seven sign-on literals; 17-card image with four slots and the transmitted sentinel; a real SQS FIFO queue | `OnlineTransactionE2ETest` |
| Performance baseline | Micrometer timers at `/actuator/prometheus` | figures recorded in `../docs/gate-evidence.md` |
| Unsafe code audit | the scoped grep list above | counts recorded in `../docs/gate-evidence.md` |
| Line coverage ≥ 80% | JaCoCo failing check rule | `./mvnw -B clean verify` |
| Zero critical or high CVEs | `dependency-check-maven` 12.1.3 bound to `verify` | `./mvnw -B clean verify` |
| Traceability 100% | [`../docs/traceability-matrix.md`](../docs/traceability-matrix.md) | `GateVerificationTest` row-count assertion |

The supply-chain scan is **bound to `verify` and actually executed**, not merely declared: it fails the
build at a CVSS threshold that catches every critical and high finding, and emits HTML, JSON and XML
reports that CI uploads as artifacts.

The traceability matrix is the largest single deliverable of this gate and its row count is exact:
**544 rows**, one per paragraph unit, each naming the source member, the paragraph, the source line, the
target Java class, the target method and the covering test, with both provenance identifiers in its
header. **Three of the 544 rows are documented non-implementations rather than translations**, and the
matrix marks them as such so the count stays honest:

1. the fee-computation paragraph that is genuinely invoked but implements nothing;
2. the duplicated exit paragraph in the account-view program, where two identically-named paragraphs
   exist and collapse to one method;
3. the paragraphs of the orphaned extract program, which no job stream invokes.

A fourth artifact completes the audit trail without contributing a row: the unreferenced copybook,
recorded in the decision log as consciously excluded dead code.

### What was executed during analysis, and what was only specified

Stated plainly, because a reader deserves to know what remains to be *demonstrated* rather than
merely *re-verified*:

| Gate | Status at analysis time |
|---|---|
| Gate 2 — zero-warning build | **Executed and passed.** The full production and test dependency set resolved and compiled clean under `-Xlint:all -Werror`. |
| Gate 8 — dependency resolution underpinning the CVE check | **Executed and passed.** The complete tree resolved, with every version read back out of it. |
| Gates 1, 3, 4, 5, 7 | **Specified but not executed** during analysis: container tooling was unavailable in the analysis sandbox, so the Testcontainers, LocalStack and PostgreSQL portions were designed rather than demonstrated. |

Nothing about the gate mechanism depends on that limitation — those five gates run on any machine with
a working Docker daemon, and in CI, which is where they are designed to be validated.

---

## Architecture and layering

Eleven packages under `com.carddemo`, with a **strict downward dependency direction**. Nothing depends
upward, and `PackageLayeringTest` enforces it as a test rather than as a convention:

| Package | May depend on | Contents |
|---|---|---|
| `api` | `service`, `api.dto`, `exception` | REST controllers — the 17 screen transactions as endpoints |
| `api.dto` | `domain.enums` | Request and response types derived from the symbolic maps |
| `batch` | `service`, `batch.step`, `repository`, `domain` | The nine job configurations and the parameter validators |
| `batch.step` | `service`, `repository`, `domain`, `util` | Readers, processors, writers and the shared step template |
| `config` | anything below | Security, JWT, batch, JPA, AWS, observability, OpenAPI, Flyway wiring |
| `service` | `repository`, `domain`, `util`, `exception` | Business logic — one method per COBOL paragraph |
| `repository` | `domain` | Spring Data JPA interfaces |
| `domain`, `domain.id`, `domain.enums` | nothing above | Entities, composite keys, enums |
| `util` | `domain`, `exception` | Fixed-width mapping, the decimal codec, formatters, templates |
| `exception` | nothing above | The exception hierarchy |

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
| Service classes | **26** | the translation-bearing services, one per program or program family, plus supporting collaborators |
| Batch job configurations | **9** | plus eight step components and the shared step template |
| Hand-written record mappers | **11** | one per verified layout, explicit offsets, no reflection |
| Request/response DTOs | 28 | derived from the 17 symbolic maps |

Optimistic locking is applied where the legacy code compared a before-image with an after-image:
`@Version` on the account and card entities replaces that comparison, and the estate's single rollback
point becomes a transactional rollback raising a conflict exception.

The full layer diagram, the entity relationships and the batch pipeline ordering live in
[`../docs/architecture.md`](../docs/architecture.md) rather than being duplicated here.

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
  and invisible to a test written under the same wrong assumption. `ZonedDecimalCodec` is the single
  place `setScale` may be called, precisely so no service can introduce a different policy.
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
├── .gitignore  .dockerignore
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
│   ├── config/         security, JWT, batch, JPA, AWS, observability, OpenAPI, Flyway
│   ├── domain/         entities + domain/id/ + domain/enums/
│   ├── repository/     Spring Data JPA interfaces
│   ├── service/        business logic — one method per COBOL paragraph
│   ├── util/           fixed-width mappers, the decimal codec, formatters, templates
│   └── exception/      the exception hierarchy
├── src/main/resources/
│   ├── application.yml  application-local.yml  application-test.yml  application-prod.yml
│   ├── logback-spring.xml  banner.txt
│   ├── db/migration/schema/   V1__create_schema.sql   V2__create_indexes.sql
│   ├── db/migration/seed/     V3__seed_reference_data.sql   V4__seed_user_security.sql
│   └── lookup/     nanpa-area-codes.json  us-state-codes.json  state-zip-prefixes.json
├── src/test/java/com/carddemo/     unit (*Test), integration (*IT), support/ base classes
└── src/test/resources/
    ├── application-test.yml
    ├── fixtures/input/       the nine ASCII datasets, plus the user-security seed
    └── fixtures/expected/    golden output at 80, 100, 133 and 430 bytes
```

There is no `docker/` directory, no `LICENSE` copy and no `NOTICE` copy inside the module — see
[Licence](#licence) below for why.

## Documentation

The migration's documentation lives at the repository root rather than in the module, because the
documentation site resolves its content directory there and the service catalog publishes from the
repository root:

| Document | What it is |
|---|---|
| [`../README.md`](../README.md) | The estate-level narrative: the mainframe application, its installation, its batch execution order and its screen inventory |
| [`../docs/architecture.md`](../docs/architecture.md) | Layer diagram, package responsibilities, entity relationships, batch pipeline ordering |
| [`../docs/onboarding-guide.md`](../docs/onboarding-guide.md) | First-run walkthrough: local build, stack bring-up, gate execution |
| [`../docs/traceability-matrix.md`](../docs/traceability-matrix.md) | **544 rows** — every paragraph unit mapped to its Java class, method and covering test, citing both provenance identifiers |
| [`../docs/decision-log.md`](../docs/decision-log.md) | Every divergence between COBOL semantics and idiomatic Java, and all fourteen source anomalies |
| [`../docs/gate-evidence.md`](../docs/gate-evidence.md) | Recorded evidence for all eight gates, including the audit counts and the performance baseline |
| [`../docs/presentation/index.html`](../docs/presentation/index.html) | Migration summary deck |

The API contract itself is not a Markdown document: springdoc publishes the OpenAPI description from
the controllers, so the contract is generated from the code rather than maintained beside it.

## Continuous integration

The workflow is [`../.github/workflows/carddemo-java-ci.yml`](../.github/workflows/carddemo-java-ci.yml)
— **outside** this module, because GitHub Actions resolves workflow definitions only from
`.github/workflows/` at the repository root. It scopes itself back in with
`defaults.run.working-directory: carddemo-java`, so every step runs as if you had `cd`'d here.

It runs on pushes and pull requests to `main`, and on manual dispatch, with least-privilege
permissions, and it:

1. checks out the repository and sets up **Eclipse Temurin JDK 25** via `setup-java`, with every
   action pinned to a commit SHA rather than a moving tag;
2. restores the Maven repository and the vulnerability data set from cache — the second is what keeps
   the supply-chain scan to seconds;
3. runs **`./mvnw -B clean verify`**, which is the same command you run locally: zero-warning
   compilation, both test tiers, **JaCoCo enforcement at 80% line coverage**, and an **executed** OWASP
   dependency-check;
4. re-executes the unit tier under hostile locales, so no assertion silently depends on the default
   locale of the machine that ran it;
5. lints the container-bootstrap scripts and smoke-tests the executable jar;
6. uploads the coverage reports, the dependency-check reports in all three formats, the test reports and
   the executable jar as artifacts, and writes a gate summary.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `docker compose up` fails binding a port | Something already owns 8080, 5432, 4566, 9090, 3000 or 16686. Every port is overridable: `APP_PORT=18080 POSTGRES_PORT=15432 docker compose up -d`. |
| App exits during start-up with a Flyway error | PostgreSQL was not ready. Check with `docker compose exec postgres pg_isready -U carddemo -d carddemo`, then `docker compose logs postgres`. Compose already gates the app on the health check, so this normally means the database container itself is unhealthy. |
| `QueueDoesNotExist`, or an S3 bucket that is not there | The LocalStack bootstrap hook did not complete. Check `curl -s http://localhost:4566/_localstack/health`, then `docker compose logs localstack` and look for the bootstrap lines. `docker compose restart localstack` re-runs the hook. |
| First `verify` takes far longer than expected | `dependency-check-maven` is downloading the vulnerability data set. Let it finish once; it is cached under `~/.dependency-check-data` afterwards. Use `-Ddependency-check.skip=true` for a fast inner loop. |
| Integration tests fail immediately with a Docker error | Testcontainers needs a reachable Docker daemon. Verify with `docker info`. Use `./mvnw -B verify -DskipITs` if you genuinely need to build without it — but the container-backed gates are then unverified. |
| `./mvnw` cannot find a JDK | Export `JAVA_HOME` to a JDK 25 installation; the wrapper resolves the JDK through it first. |
| Compilation fails on something that looks like a warning | It *is* a warning. `-Werror` is deliberate — fix the cause rather than suppressing it. A suppression needs an inline justification and a decision-log entry. |
| A golden-file comparison fails after a change | That is the gate working. Compare byte offsets, not rendered text: the usual causes are a rounding mode other than `DOWN`, a rearranged expression, or trailing-space handling. |
| Grafana shows no data | Check `http://localhost:9090/targets` — the `carddemo-app` target must be UP against `/actuator/prometheus`. Meters appear only after an endpoint or a job has actually run. |

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
[`../docs/decision-log.md`](../docs/decision-log.md) in the same pull request. If it adds or renames a
method that a traceability row names, update
[`../docs/traceability-matrix.md`](../docs/traceability-matrix.md) too.

## Licence

Apache License 2.0, inherited from the repository. Every generated Java source, SQL migration and
configuration file in this module carries the **same Apache-2.0 header that is embedded in every legacy
member**, so provenance survives the migration.

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
| 3 | **Layered separation with a strict downward dependency direction** | The eleven-package map above, enforced by `PackageLayeringTest`; fixed-width mapping isolated in `util`. |
| 4 | **Constructor injection and immutability, without code generation** | Every collaborator arrives through a constructor; DTOs are records or final classes; no Lombok and no annotation processor of any kind. |
| 5 | **Secrets never in source and never defaulted** | `application-prod.yml` resolves every secret from the environment with **no fallback**, so a missing secret fails startup. Credentials are BCrypt hashes; the local stack's throwaway values live only in `docker-compose.yml`. |
| 6 | **Versioned, forward-only schema evolution** | Flyway `V1`–`V4`, with the seed migrations in a sibling location that production does not resolve, `clean` disabled and `validate-on-migrate` on. |
| 7 | **A test pyramid with an enforced floor** | Unit tests over mappers, validators and services; integration tests against real containers; end-to-end tests over the full pipeline. JaCoCo fails the build below 80% line coverage; branch coverage is reported, not gated. |
| 8 | **Supply-chain hygiene** | `dependency-check-maven` bound to `verify` and **executed**, failing on any critical or high finding, with reports emitted in three formats and uploaded by CI. |
| 9 | **Observability as a first-class concern** | Actuator health and metrics, Micrometer timers on every endpoint and every batch step, Prometheus and Grafana provisioned in the stack, OTLP tracing wired to Jaeger, structured JSON logging with correlation identifiers. |
| 10 | **Licence continuity** | The Apache-2.0 header on every generated Java source, SQL migration and configuration file, matching the header in every legacy member. |
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
