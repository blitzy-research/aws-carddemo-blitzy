<a name="carddemo----mainframe-card-demo-application"></a>

## CardDemo -- Mainframe CardDemo Application

- [CardDemo -- Mainframe CardDemo Application](#carddemo----mainframe-card-demo-application)
- [Description](#description)
- [The Java 25 migration of this application](#the-java-25-migration-of-this-application)
  - [Provenance and the not-copied constraint](#provenance-and-the-not-copied-constraint)
  - [Build and run the Java module](#build-and-run-the-java-module)
  - [The local validation stack](#the-local-validation-stack)
  - [Validation gates](#validation-gates)
  - [Continuous integration](#continuous-integration)
  - [Migration documentation](#migration-documentation)
- [Technologies used](#technologies-used)
  - [Exact versions of the target stack](#exact-versions-of-the-target-stack)
- [Installation on the mainframe](#installation-on-the-mainframe)
- [Application Details](#application-details)
  - [User Functions](#user-functions)
  - [Admin Functions](#admin-functions)
  - [Application Inventory](#application-inventory)
    - [**Online**](#online)
    - [**Batch**](#batch)
  - [Application Screens](#application-screens)
    - [**Signon Screen**](#signon-screen)
    - [**Main Menu**](#main-menu)
    - [**Admin Menu**](#admin-menu)
- [Support](#support)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)
- [Project status](#project-status)

<br/>

## Description
CardDemo is a Mainframe application designed and developed to test and showcase AWS and partner technology for mainframe migration and modernization use-cases such as discovery, migration, modernization, performance test, augmentation, service enablement, service extraction, test creation, test harness, etc.

Note that the intent of this application is to provide mainframe coding scenarios to excercise analysis, transformation and migration tooling. So, the coding style is not uniform across the application

<br/>

## The Java 25 migration of this application

**This repository now carries two implementations of CardDemo side by side.** Everything else in this
README describes the original z/OS application: COBOL under `app/cbl`, copybooks under `app/cpy`, BMS
mapsets under `app/bms`, job streams under `app/jcl`, and the CICS resource definitions under `app/csd`.
That tree is **unchanged and read-only** — it is simultaneously the parity baseline every golden fixture
derives from and the anchor every traceability row cites, so it must stay byte-identical.

Beside it, [`carddemo-java/`](./carddemo-java/) is a **self-contained Maven module** that reproduces the same
application on **Java 25 LTS and Spring Boot 3.5.16** over **PostgreSQL 16**, with **Spring Batch** in
place of the job streams and **S3, SQS and SNS** in place of sequential-dataset staging and the transient
data queue. No COBOL, JCL, BMS, copybook or CSD text is copied into it; the correspondence is carried
entirely by citation.

| | |
| --- | --- |
| **Scope migrated** | all 28 COBOL programs (19,254 lines); 28 copybooks accounted for, of which **27 are translated and 1 is a deliberate exclusion** — `UNUSED1Y.cpy` has no `COPY` reference anywhere in the estate, so migrating it would have created dead Java code and it is recorded as a decision rather than an omission; 17 BMS mapsets with their 17 generated symbolic-map copybooks; 29 job members; 2 cataloged procedures; and the single CICS CSD |
| **Traceability** | **544** procedure units — 528 program paragraphs plus 14 and 2 from the two procedural copybooks — each mapped to its Java class, method and covering test |
| **Delivered surface** | 20 HTTP operations over 19 paths, and 9 independently launchable batch jobs |
| **Data** | 11 tables from the 11 verified record layouts, evolved by 4 Flyway migrations |
| **Preserved to the byte** | five fixed output widths — 40, 80, 100, 133 and 430 bytes — compared as byte arrays, never semantically |
| **No feature expansion** | **no business functionality was added** that the COBOL did not already do, including an empty-but-invoked fee paragraph that survives as a documented no-op. Technical and operational mechanisms the platform requires *are* added deliberately — credential hashing, a production profile with no defaulted secrets, transport security, observability and a test estate — and each is recorded as a labelled exception in [the decision log](./docs/decision-log.md) rather than presented as parity |

There is **no browser interface, by design**. The legacy presentation layer is a 3270 terminal contract,
and the faithful translation of a terminal contract is a machine contract: the 17 mapsets became REST
request and response types, exercised by tests rather than by a page. No frontend framework, component
library or design system is in scope.

### Provenance and the not-copied constraint

The migration was given one constraint that shapes the whole delivery:

> COBOL source files are NOT copied into the target repository. Traceability matrix and decision log MUST
> reference the original COBOL repository by commit SHA.

So **no legacy source text is reproduced anywhere in the module for traceability purposes** — not a
program, not a paragraph, not a statement, and not in a comment. Correspondence between the two estates is
carried **by citation only**: member names, paragraph names, line numbers, record widths, byte offsets, key
lengths and picture-clause shapes are interface metadata and appear freely; source lines do not appear at
all. The claim is mechanically checkable rather than asserted — a grep over
`carddemo-java/src/**/*.java` comments for a COBOL or CICS verb carrying one of the legacy program's own
identifiers, literals or argument lists returns nothing.

**One category of legacy text does appear, and naming it is the point of a certification.** Where the
preservation requirement obliges the module to *emit* legacy text byte for byte, that text is present as
contract data rather than as a transcription: the **17 fixed 80-column job-submission card images** and
their `/*EOF` sentinel in `util/JclCardImageBuilder`, the **80- and 100-byte statement template literals**,
and the **seven sign-on message texts**. Reproducing them is what
*"external system interfaces MUST maintain identical contracts"* requires, and Gate 5 verifies the card
images by draining them back out of a real queue. Every citation is anchored on these two identifiers:

| Anchor | Value |
| :------------------------ | :--------------------------------------------------- |
| Checkout commit SHA       | `7756d895ffeb65f7ea72aaa609e356d9899afcec`           |
| Upstream release stamp    | `CardDemo_v1.0-15-g27d6c6f-68`, dated `2022-07-19`   |

The stamp is the release marker carried in the trailer comment of 78 legacy members, so a reviewer can
confirm it without leaving the `app/` tree. Both identifiers appear in the header of
[the traceability matrix](./docs/traceability-matrix.md) and [the decision log](./docs/decision-log.md).

Two conventions follow from the same constraint and are worth stating up front, because they explain
choices in the Java code that would otherwise read as mistakes:

* **Faithful beats idiomatic.** Wherever legacy semantics and the natural Java answer diverge, the legacy
  semantics win and the divergence is recorded in [the decision log](./docs/decision-log.md) rather than
  resolved by taste. That single tie-break is why monetary arithmetic truncates instead of rounding, and why
  an empty-but-invoked fee paragraph survives as a documented no-op.
* **Two account-update fields carry no validation, for two different reasons.** The **second address line**
  is genuinely never validated by the legacy program — it is decorated for error display and nothing more —
  so attaching no constraint to it is *faithful translation*. The **middle name** is different: the legacy
  program does validate it, through the optional-alphabetic edit at `app/cbl/COACTUPC.cbl:L1568-L1574`, and
  its not-OK flag is tested when the cursor is placed at line 3110. The source comment beside its
  decoration macro reads "no edits coded" and is wrong about its own code. Java attaches no constraint to it
  regardless, because the migration was directed not to — so this one is a **directive-driven divergence**,
  recorded as such in [the decision log](./docs/decision-log.md), and not a parity statement.
* **Licence continuity.** Every generated source, migration and comment-capable configuration file in
  `carddemo-java/` carries the same Apache-2.0 header that is embedded in every legacy member, consistent
  with the existing [`LICENSE`](./LICENSE) and `NOTICE`. Formats that admit no comment — the JSON lookup
  resources and the dashboard definition — carry none, which is a property of the format rather than an
  omission. Provenance survives the migration at file level.

### Build and run the Java module

**Prerequisites are a JDK 25 installation and Docker with Compose — and nothing else.** In particular
**no preinstalled Maven is required**: the Maven Wrapper is committed at
[`carddemo-java/mvnw`](./carddemo-java/mvnw) and pins Apache Maven 3.9.16, which is what makes the build
hermetic from a clean checkout. Every dependency and plugin version in
[`carddemo-java/pom.xml`](./carddemo-java/pom.xml) is pinned to an exact coordinate; no `latest`, no
`RELEASE` and no unpinned range appears anywhere, so two checkouts of the same commit resolve the same
graph.

```shell
cd carddemo-java
./mvnw -B clean verify
```

**`verify` — not `package` — is the meaningful command.** `package` is not worthless: it compiles under the
zero-warning settings and runs the unit tier under Surefire, so it does establish Gate 2 and the unit
results. What it stops short of is everything bound at `verify` — the integration and end-to-end tiers under
Failsafe, the JaCoCo coverage check, and the OWASP dependency-check — which is three of the four things the
delivery is judged on. `package` therefore produces a jar and two gates' worth of evidence, not sign-off.

**The build is zero-warning, and that is enforced rather than reported.** `maven-compiler-plugin` is
configured with `<release>25</release>` and the compiler arguments `-Xlint:all -Werror`, so **any compiler
warning fails the build** — warnings cannot silently accumulate. The requirement it discharges is that a
*"clean checkout MUST produce deployable artifact with ZERO warnings"*, and it was verified by execution
rather than asserted. A probe of this dependency set during analysis resolved 231 artifacts and compiled
clean; every build of the module since has compiled both source trees as
`javac [debug parameters release 25]` with no warning and no error. The compiled source counts and the
quoted compiler lines are recorded once, under Gate 2 in [`docs/gate-evidence.md`](docs/gate-evidence.md),
so no second copy of them can go stale here.

To run the application against the local stack, activate the `local` profile — either through the plugin:

```shell
./mvnw spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments=--server.address=127.0.0.1
```

or from the artifact the build just produced:

```shell
SPRING_PROFILES_ACTIVE=local java -jar target/carddemo-java-1.0.0.jar --server.address=127.0.0.1
```

**The `local` profile must stay loopback-only, and the bind above is why the flag is written out.**
`application-local.yml` already defaults `server.address` to `127.0.0.1`, so these commands are
loopback-bound with or without the argument; it is stated anyway because this profile is the one that
carries a committed signing secret, a committed operator credential, cleartext HTTP, anonymous metric
scraping and the ten seeded sign-on identities below — and every one of those is reasoned about on the
premise that nothing off this machine can reach the process. To reach it from another machine, forward
the port over an encrypted, authenticated channel — `ssh -L 8080:127.0.0.1:8080 <host>` — or deploy the
`prod` profile, which requires transport security and resolves every secret from the environment.
Publishing the `local` profile on a routable address is unsupported.

Either way the sign-on endpoint answers with a JWT. The demo identities it accepts are the ones seeded by
the `V4` migration, which reaches `local` and `test` only and can never be applied to a production database.
Read the seeded sample credential in rather than typing it into the command, so it never reaches your shell
history — the same pattern [the module README](./carddemo-java/README.md) uses:

```shell
read -rsp 'seeded sample password: ' SEED_PASSWORD; echo
curl -i -X POST http://localhost:8080/api/auth/signon \
  -H 'Content-Type: application/json' \
  --data-binary @- <<JSON
{"userId":"ADMIN001","password":"$SEED_PASSWORD","keyAction":"ENTER"}
JSON
unset SEED_PASSWORD
```

`keyAction` is mandatory: omitting it produces the legacy invalid-key reply rather than a sign-on attempt.
`ADMIN001` routes to the administrative menu and `USER0001` to the main menu, which is the routing rule the
legacy user-type byte drives.

Three Spring profiles exist, and the difference between them is entirely about where configuration comes
from:

| Profile | Resolves its endpoints from | Secrets |
| :------- | :----------------------------------------------- | :------------------------------------------------------------------------------ |
| `local`  | the Docker Compose stack below                   | none needed — every value carries a default                                     |
| `test`   | the endpoints Testcontainers publishes per run   | none needed — containers are provisioned by the test run itself                  |
| `prod`   | the deployment environment                       | **every secret from an environment variable with no fallback default** — a missing secret fails startup rather than silently binding a placeholder |

That asymmetry is deliberate. A defaulted secret is as much a hardcoded credential as a literal one, so the
production profile has no defaults to fall back on, and this README documents no production credential of
any kind.

For a container image, [`carddemo-java/Dockerfile`](./carddemo-java/Dockerfile) is multi-stage: a Temurin 25
JDK layer builds, and a **Temurin 25 JRE** layer runs, so the shipped image carries no compiler and no build
tooling.

### The local validation stack

[`carddemo-java/docker-compose.yml`](./carddemo-java/docker-compose.yml) brings up the whole validation
environment. On a clean checkout the application image does not exist yet, so the **first** bring-up is
the one that builds it — and building it needs the three build arguments the `Dockerfile` refuses to
default, because an image stamped with the all-zero revision sentinel is an unlabelled image:

```shell
cd carddemo-java
export APP_VERSION="$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)"
export SOURCE_REVISION="$(git rev-parse HEAD)"
export SOURCE_DATE_EPOCH="$(git log -1 --format=%ct)"
docker compose up -d --build
```

Afterwards, and for every start that does not rebuild, `docker compose up -d` on its own is enough —
every other variable in the file carries a default, so starting, inspecting and tearing the stack down
need no exports at all:

```shell
cd carddemo-java && docker compose up -d
```

If you only want the dependencies — because you intend to run the application from a host JVM or from the
Maven plugin, as above — start them by name instead, and no exports are needed because every other variable
in the file carries a default:

```shell
cd carddemo-java && docker compose up -d postgres localstack jaeger prometheus grafana
```

That provisions **PostgreSQL 16**, **LocalStack Community** for S3, SQS and SNS, **Prometheus**, **Grafana**
and **Jaeger**. Three things happen on the way up:

* **AWS resources are bootstrapped** by
  [`carddemo-java/localstack/init/01-create-aws-resources.sh`](./carddemo-java/localstack/init/01-create-aws-resources.sh),
  which creates the S3 staging bucket that replaces sequential-dataset and GDG staging, the **SQS FIFO
  queue `JOBS.fifo`** that replaces the CICS `TDQUEUE(JOBS)` transient data queue, and the SNS topic used
  for job-completion notification. The legacy queue's logical name is `JOBS`; the physical queue is named
  `JOBS.fifo`, because Amazon SQS requires the `.fifo` suffix on a first-in-first-out queue. The suffix is
  part of the name the bootstrap script creates and the name the application configuration resolves, not a
  decoration on it.
* **The schema is evolved by Flyway**, whose four migrations `V1__create_schema.sql` through
  `V4__seed_user_security.sql` replace the ten legacy `DEFINE CLUSTER` provisioning jobs. They ship in two
  sibling locations — the two schema scripts in `db/migration/schema/` and the two seeds in
  `db/migration/seed/` — and what separates the environments is the **profile-scoped location list**. The
  baseline and `prod` configurations declare `classpath:db/migration/schema` alone, so a production
  migration does not resolve the two seeds at all and **can never reach them** — a production deployment
  inherits neither the sample reference data of `V3` nor the seeded credentials of `V4`. Only `local` and
  `test` add the seed location. Every profile declares `target: latest`, so a future schema migration is
  applied rather than silently skipped.
* **Observability comes up with the application**, not after it. Actuator exposes health, info, metrics and
  a Micrometer Prometheus registry at `/actuator/prometheus`, which the Compose-provisioned Prometheus
  scrapes and the provisioned Grafana dashboard displays; traces export over OTLP to Jaeger; and SLF4J
  logging with correlation identifiers replaces the legacy `DISPLAY` diagnostics. The **encoder** is the
  one thing that differs by profile: [`logback-spring.xml`](./carddemo-java/src/main/resources/logback-spring.xml)
  selects the readable console appender for `local` and `test`, because a developer and a test log are read
  by a person, and the structured JSON appender everywhere else, because a collector is not a person.

**No gate requires a production environment, a staging environment or a running COBOL system.** All eight
run on a developer machine through Docker Compose, Testcontainers and LocalStack **Community** — the paid,
commercially-licensed LocalStack tier is neither used nor required, because Community covers the entire
S3/SQS/SNS surface this module touches. No AWS account and no cloud credential is involved at any point.

### Validation gates

Eight gates are the migration's **acceptance criteria**, and the ten-row COBOL-to-Java construct mapping
they sit beside is a **requirement** of it. The recorded evidence for each — the command, the artefact and
the result — is in [`docs/gate-evidence.md`](./docs/gate-evidence.md); this table is only the map.

| Gate | What it verifies | How it is discharged |
| ---: | :--------------------------------- | :--------------------------------------------------------------------------------------------- |
| 1 | End-to-end byte-equivalent output | `BatchPipelineE2ETest` compares produced files against golden fixtures as **byte arrays, never semantically**, at the legacy widths of **40, 80, 100, 133 and 430 bytes**. Four are compared from one seeded pipeline pass; the 40-byte category-balance listing is compared against its own golden at its emitting job |
| 2 | Zero-warning build | `-Xlint:all -Werror` under `<release>25</release>`; any warning fails `./mvnw -B clean verify` |
| 3 | Performance baseline | The quotable figures — elapsed time, peak heap and records per second — are produced by `support/RunScopedPerformanceRecorder`, which wall-clocks each job, reads peak heap from the JVM's own memory beans and divides records by elapsed time, writing `target/gate-evidence/gate3-*.md`. Micrometer timers per endpoint and per batch step at `/actuator/prometheus`, and the Grafana dashboard over them, **corroborate** rather than supply those figures. **There is no COBOL baseline to compare against, so this gate establishes the first Java baseline rather than asserting a threshold** — no latency, throughput or availability target is claimed anywhere, because none exists in the legacy estate |
| 4 | Named real-world validation artefacts | The **nine** ASCII fixtures under `app/data/ASCII/` and the **twelve** EBCDIC datasets under `app/data/EBCDIC/`, named one by one in the gate evidence and driven through the pipeline rather than mocked — mocked I/O does not discharge this gate. The dataset-to-copybook mapping for most of them is already tabulated under [Installation on the mainframe](#installation-on-the-mainframe) |
| 5 | Interface contract verification | `OnlineTransactionE2ETest` exercises the fixed-width file formats, the sign-on message literals, and the job-submission card image **drained back out of a real SQS FIFO queue** rather than read from a builder's return value |
| 6 | Unsafe and low-level code audit | A fixed grep list scoped to `carddemo-java/src/main/java/**`, targeting 0 raw SQL string concatenation, 0 `Runtime.exec`, **0 reflection** — which is what forces all **twelve** record mappers to be hand-written, one per persisted record layout plus one for the statement job's transient work record — with a budget of ≤5 unchecked casts. The **warning-suppression** count is the one figure measured over `src/main/java/**` **and** `src/test/java/**`, budgeted at ≤3 whole-source and measured at **0**, because Gate 2 forbids a suppressed warning wherever it is written and both trees are compiled by the same compiler under `-Xlint:all -Werror` |
| 7 | Scope matching | JaCoCo **≥80% line coverage enforced as a build-failing check**, measured over merged unit and integration data. Branch, method and instruction coverage are **reported for information and are not gated** |
| 8 | Integration sign-off | OWASP dependency-check bound to `verify` and actually executed, failing the build at CVSS **7.0**. The exact recorded result, rather than a round claim: **zero unsuppressed critical**, **zero unsuppressed high**, **one high finding covered by a written analyst determination** (`CVE-2026-66299`, CVSS 7.5, matched on `tomcat-embed-core`, scoped to that single identifier and self-expiring because `failBuildOnUnusedSuppressionRule` fails the build the moment it stops matching), and **one visible below-threshold medium** (`CVE-2026-41178`, CVSS 5.3, on `opentelemetry-semconv`, reported and not suppressed). Both are disclosed with their dated report in [`docs/gate-evidence.md`](docs/gate-evidence.md); a CVE result ages, so re-run the scan before any sign-off. Plus the **544-row** traceability matrix covering every procedure unit |

### Continuous integration

CI is defined in [`.github/workflows/carddemo-java-ci.yml`](./.github/workflows/carddemo-java-ci.yml). It
installs Temurin 25 with `setup-java`, scopes itself into the module with
`defaults.run.working-directory: carddemo-java`, runs `./mvnw -B clean verify`, enforces the JaCoCo 80% line
floor, executes the OWASP dependency-check, and uploads the coverage and CVE reports as build artefacts.

It is the one deliverable that cannot live inside the module, and for a platform reason rather than a design
one: **GitHub Actions resolves workflow definitions only from `.github/workflows/` at the repository root.**
The documentation pages sit at `docs/` for the equivalent reason — MkDocs resolves its implicit `docs_dir`
there and Backstage TechDocs publishes from the repository root.

### Migration documentation

| Document | What it answers |
| :--------------------------------------------------------------- | :------------------------------------------------------------------------------- |
| [`docs/onboarding-guide.md`](./docs/onboarding-guide.md) | first-run walkthrough: prerequisites, build commands, stack bring-up, gate execution |
| [`docs/architecture.md`](./docs/architecture.md) | the layer map, package responsibilities, entity model and batch pipeline ordering |
| [`docs/traceability-matrix.md`](./docs/traceability-matrix.md) | which Java method corresponds to a given COBOL paragraph — **544 rows**, one per procedure unit, each naming the source member, the paragraph, the source line, the target class, the target method and the covering test, under the SHA and stamp cited above |
| [`docs/decision-log.md`](./docs/decision-log.md) | every divergence between faithful COBOL semantics and idiomatic Java, plus the source-anomaly register — **31 entries** as it stands, of which the migration plan named the first fourteen plus a fifteenth observation and the remaining sixteen were found at the same checkout during translation |
| [`docs/gate-evidence.md`](./docs/gate-evidence.md) | per gate: the command, the artefact and the standing result, including the measured performance baseline |
| [`docs/presentation/index.html`](./docs/presentation/index.html) | the migration summary deck |
| [`carddemo-java/README.md`](./carddemo-java/README.md) | the module's own build, configuration, package map and per-gate detail |

<br/>

## Technologies used

**The original mainframe application:**

1. COBOL
2. CICS
3. VSAM
4. JCL
5. RACF

**The migrated Java module** (see [`carddemo-java/`](./carddemo-java/)):

1. Java 25 LTS (Eclipse Temurin) and Maven 3.9 via the committed wrapper
2. Spring Boot 3.5.16 — Web, Data JPA, Batch, Security, Validation, Actuator
3. PostgreSQL 16, with schema evolution by Flyway
4. AWS S3, SQS and SNS, emulated locally by LocalStack Community
5. JUnit 5, Mockito, AssertJ and Testcontainers

### Exact versions of the target stack

Every version below is a **measurement rather than a preference**: each was read back out of an executed
Maven resolution against [`carddemo-java/pom.xml`](./carddemo-java/pom.xml), not recalled. Please do not
round, guess or "helpfully" bump them — the pins are load-bearing.

Reproduce the check yourself. **No single command reports all three kinds of version**, so use the one that
matches what you are checking:

```shell
cd carddemo-java
java -version && ./mvnw -v                     # toolchain: the JDK, and the Maven the wrapper provisions
./mvnw -B dependency:list                      # the resolved dependency graph, library by library
./mvnw -B help:effective-pom | grep -A2 artifactId   # plugin versions, as the build actually resolves them
docker compose config | grep image:            # the server images, pinned by digest
```

The **PostgreSQL 16** server version is a property of the Compose image and of whatever server a deployment
points at, not of the JDBC driver line in the table.

| Technology | Version |
| :--------------------------------------------------------- | :---------------------------------------------------------------- |
| Eclipse Temurin JDK (Java LTS) | `25.0.3+9` |
| Apache Maven, via the committed Wrapper | `3.9.16` |
| Spring Boot | `3.5.16` |
| Spring Batch | `5.2.6` |
| Spring Data JPA | `3.5.13` |
| Spring Security | `6.5.11` |
| Spring Core | `6.2.19` |
| Hibernate ORM | `6.6.53.Final` |
| Jakarta Persistence API | `3.1.0` |
| HikariCP | `6.3.3` |
| PostgreSQL JDBC driver (server: **PostgreSQL 16**) | `42.7.13` — pinned one patch above the `42.7.11` the Spring Boot 3.5.16 bill of materials manages, as CVE remediation; do not revert it |
| Flyway core and `flyway-database-postgresql` | `11.7.2` |
| Spring Cloud AWS — S3, SQS and SNS starters | `3.4.2` |
| AWS SDK v2 — s3, sqs, sns (managed by the Spring Cloud AWS BOM) | `2.31.78` |
| Micrometer core and Prometheus registry | `1.15.12` |
| Micrometer tracing bridge (OpenTelemetry) | `1.5.12` |
| OpenTelemetry OTLP exporter | `1.49.0` |
| `logstash-logback-encoder` | `9.0` |
| Logback classic / SLF4J API | `1.5.34` / `2.0.18` |
| springdoc-openapi (`webmvc-ui`) | `2.8.17` |
| JUnit Jupiter | `5.12.2` |
| Mockito | `5.17.0` |
| AssertJ | `3.27.7` |
| Testcontainers, with `junit-jupiter`, `postgresql` and `localstack` | `1.21.4` |
| `maven-compiler-plugin` | `3.14.1` |
| `maven-surefire-plugin` / `maven-failsafe-plugin` | `3.5.6` |
| JaCoCo Maven plugin | `0.8.15` |
| OWASP `dependency-check-maven` | `12.1.3` |

Two of those look like oversights and are not. Both were decided against the published catalogue as it stood
when the migration was analysed, so re-check the catalogue yourself before proposing a bump rather than
treating either as a claim about what is newest today:

* **Spring Boot 3.5.16, not 4.x.** A 4.x line exists above it, but the requirement names "Spring Boot 3.x",
  which is a **contract ceiling** — and 3.5.16 was the newest generally-available release *on the 3.x line*.
  Moving to 4.x would not be an upgrade here; it would be a scope violation.
* **Testcontainers 1.21.4, not 2.x.** A 2.x line is published, but Spring Boot 3.5.16's dependency
  management pins 1.21.4. Staying **BOM-managed** avoids an unmanaged major-version override whose
  transitive consequences the parent no longer reasons about, so the way to move this version is to move
  the parent.

No annotation processor appears anywhere in the dependency set — no Lombok, MapStruct, Immutables or
AutoValue. That is a consequence of two constraints meeting: the unsafe-code audit commits to a reflection
count of zero, and under `-Werror` processor-generated code is a live source of build-failing warnings. The
boilerplate is written out explicitly instead.

<br/>

## Installation on the mainframe 

To install this repository on the mainframe please follow the following steps

1. Clone this repository to your local development environment

2. Create datasets on the mainframe  hold the code
   * It is recommended to group them under a High Level Qualifier (HLQ)for all your datasets. 
   * Upload the following application source folders from the main branch of git repository on to your mainframe
      using $INDFILE or your preferred upload tool.
   * If you have used AWS.M2 as your HLQ, you should end up with the below code structure on the mainframe
   
      | HLQ    | Name          | Format | Length |
      | :----- | :------------ | :----- | -----: |
      | AWS.M2 | CARDDEMO.JCL  | FB     |     80 |
      | AWS.M2 | CARDDEMO.PROC | FB     |     80 |
      | AWS.M2 | CARDDEMO.CBL  | FB     |     80 |
      | AWS.M2 | CARDDEMO.CPY  | FB     |     80 |
      | AWS.M2 | CARDDEMO.BMS  | FB     |     80 |
      
3. Use data for testing using either of the below approaches

   ** Use the supplied sample data**
   
      * Upload the sample data provided in the main/-/data/EBCDIC/ folder to the mainframe. Ensure that you use transfer mode binary

         | Dataset name                      | Name                                             | Copybook (Layout) | Format | Length | Name of equivalent ascii file |
         | :---------------------------------| :----------------------------------------------- | :-----            | :----- | -----: | :---------------------------- |
         | AWS.M2.CARDDEMO.USRSEC.PS         | User Security file                               | CSUSR01Y          | FB     |     80 | See DEFUSR01.jcl (inline)     |
         | AWS.M2.CARDDEMO.ACCTDATA.PS       | Account Data                                     | CVACT01Y          | FB     |    300 | acctdata.txt                  |
         | AWS.M2.CARDDEMO.CARDDATA.PS       | Card Data                                        | CVACT02Y          | FB     |    150 | carddata.txt                  |
         | AWS.M2.CARDDEMO.CUSTDATA.PS       | Customer Data                                    | CVCUS01Y          | FB     |    500 | custdata.txt                  |
         | AWS.M2.CARDDEMO.CARDXREF.PS       | Customer Account Card Cross reference            | CVACT03Y          | FB     |     50 | cardxref.txt                  |
         | AWS.M2.CARDDEMO.DALYTRAN.PS.INIT  | Transaction database initialization record       | CVTRA06Y          | FB     |    350 | 1 record (low-values ending with 00000100)|
         | AWS.M2.CARDDEMO.DALYTRAN.PS       | Transaction data which has to go through posting | CVTRA06Y          | FB     |    350 | dailytran.txt                 |
         | AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS| Transaction data entered online                  | CVTRA05Y          | FB     |    350 | not applicable                |
         | AWS.M2.CARDDEMO.DISCGRP.PS        | Disclosure Groups                                | CVTRA02Y          | FB     |     50 | discgrp.txt                   |
         | AWS.M2.CARDDEMO.TRANCATG.PS       | Transaction Category Types                       | CVTRA04Y          | FB     |     60 | trancatg.txt                  |
         | AWS.M2.CARDDEMO.TRANTYPE.PS       | Transaction Types                                | CVTRA03Y          | FB     |     60 | trantype.txt                  |
         | AWS.M2.CARDDEMO.TCATBALF.PS       | Transaction Category Balance                     | CVTRA01Y          | FB     |     50 | tcatbal.txt                   |

      * Execute the following JCLs in order

         | Jobname  | What it does                                        |
         | :------- | :-------------------------------------------------- |
         | DUSRSECJ | Sets up user security vsam file                     |
         | CLOSEFIL | Closes files opened by CICS                         |
         | ACCTFILE | Loads Account database using sample data            |
         | CARDFILE | Loads Card database with credit card sample data    |
         | CUSTFILE | Creates customer database                           |
         | XREFFILE | Loads Customer Card account cross reference to VSAM |
         | TRANFILE | Copies initial Trasaction file  to VSAM             |
         | DISCGRP  | Copies initial Disclosure Group file  to VSAM       |
         | TCATBALF | Copies initial TCATBALF file  to VSAM               |
         | TRANCATG | Copies initial transaction category file  to VSAM   |
         | TRANTYPE | Copies initial transaction type file                |
         | OPENFIL  | Makes files available to CICS                       |
         | DEFGDGB  | Defines GDG Base                                    |


4. Compile the Programs. 
   
   You should use the compile process followed by your mainframe shopfloor
   
   We have however provided some sample JCLs in the samples folder in git to help you craft the JCL   

5. Create resources in the CARDDEMO group in CICS
   
   You have 2 options
   
   Be sure to edit the HLQs in the below documents as required before you do the definition
   
   * (Preferred) . Use the DFHCSDUP JCL that the resources required by the application

      The resources required are in the CSD file provided in the CSD folder
       
      * Group CARDDEMO
      * Mapsets
      * Transactions
      * Maps
      * Files
      
   * Use the CEDA transaction to execute the commands in the above listing
   
      * Define group 
         ```shell
         DEFINE LIBRARY(COM2DOLL) GROUP(CARDDEMO) DSNAME01(&HLQ..LOADLIB)
         ```
      * Define Mapsets, Maps , Programs and Files
      
         Sample CEDA commands
         
         ```shell
         DEF PROGRAM(COCRDLIC) GROUP(CARDDEMO)
         DEF MAPSET(COCRDLI) GROUP(CARDDEMO)
         DEFINE PROGRAM(COSGN00C) GROUP(CARDDEMO) DA(ANY) TRANSID(CC00) DESCRIPTION(LOGIN)
         DEFINE TRANSACTION(CC00) GROUP(CARDDEMO) PROGRAM(COSGN00C) TASKDATAL(ANY)
         ```

   * Install /Load the online resources to your CICS region

      ```shell
      CEDA INSTALL TRANS(CCLI) GROUP(CARDDEMO)
      CEDA INSTALL FILE(CARDDAT) GROUP(CARDDEMO)
      CECI LOAD PROG(COCRDUP)
      CECI LOAD PROG(COCRDUPC)
      ```

   * Execute a NEWCOPY of mapsets and maps
      ```shell
      CEMT SET PROG(COCRDUP) NEWCOPY
      CEMT SET PROG(COCRDUPC) NEWCOPY  
      ```
6. Enjoy the demo

   * For online functions : Start the CardDemo application using the CC00 transaction
     - Enter userid ADMIN001 and the initially configured password PASSWORD to manage users
     - Enter userid USER0001 and the initially configured password PASSWORD to access back office functions
   * For batch            : See the instructions for running full batch below.

## Running full batch 
   
  * Execute the following JCLs in order

    | Jobname  | What it does                                        |
    | :------- | :-------------------------------------------------- |
    | CLOSEFIL | Closes files opened by CICS                         |
    | ACCTFILE | Loads Account database using sample data            |
    | CARDFILE | Loads Card database with credit card sample data    |
    | XREFFILE | Loads Customer Card account cross reference to VSAM |
    | CUSTFILE | Creates customer database                           |
    | TRANBKP  | Creates Transaction database                        |
    | DISCGRP  | Copies initial disclosure Group file  to VSAM       |
    | TCATBALF | Copies initial TCATBALF file  to VSAM               |
    | TRANTYPE | Copies initial transaction type file                |
    | DUSRSECJ | Sets up user security vsam file                     |
    | POSTTRAN | Core processing job                                 |
    | INTCALC  | Run interest calculations                           |
    | TRANBKP  | Backup Transaction database                         |
    | COMBTRAN | Combine system transactions with daily ones         |
    | CREASTMT | Produce transaction statement                       | 	
    | TRANIDX  | Define alternate index on transaction file          |
    | OPENFIL  | Makes files available to CICS                       |
<br/>

## Application Details 
The CardDemo is a Credit Card management application, built primarily using COBOL programming language. The application has various functions that allows users to manage Account, Credit card, Transaction and Bill payment. 

There are 2 types of users:
* Regular User
* Admin User

The Regular user can perform the user functions and the Admin users can only perform Admin functions.

<br/>

### User Functions

![Alt text](./diagrams/Application-Flow-User.png?raw=true "User Flow")

<br/>

### Admin Functions

![Alt text](./diagrams/Application-Flow-Admin.png?raw=true "Admin Flow")

<br/>

### Application Inventory

#### **Online**

| Transaction |      | BMS Map | Program  | Function            |
| :---------- | :--- | :------ | :------- | :------------------ |
| CC00        |      | COSGN00 | COSGN00C | Signon Screen       |
| CM00        |      | COMEN01 | COMEN01C | Main Menu           |
|             | CAVW | COACTVW | COACTVWC | Account View        |
|             | CAUP | COACTUP | COACTUPC | Account Update      |
|             | CCLI | COCRDLI | COCRDLIC | Credit Card List    |
|             | CCDL | COCRDSL | COCRDSLC | Credit Card View    |
|             | CCUP | COCRDUP | COCRDUPC | Credit Card Update  |
|             | CT00 | COTRN00 | COTRN00C | Transaction List    |
|             | CT01 | COTRN01 | COTRN01C | Transaction View    |
|             | CT02 | COTRN02 | COTRN02C | Transaction Add     |
|             | CR00 | CORPT00 | CORPT00C | Transaction Reports |
|             | CB00 | COBIL00 | COBIL00C | Bill Payment        |
| CA00        |      | COADM01 | COADM01C | Admin Menu          |
|             | CU00 | COUSR00 | COUSR00C | List Users          |
|             | CU01 | COUSR01 | COUSR01C | Add User            |
|             | CU02 | COUSR02 | COUSR02C | Update User         |
|             | CU03 | COUSR03 | COUSR03C | Delete User         |

#### **Batch**

| Job      | Program  | Function                                   |
| :------- | :------- | :----------------------------------------- |
| DUSRSECJ | IEBGENER | Initial Load of User security file         |
| DEFGDGB  | IDCAMS   | Setup GDG Bases                            | 
| ACCTFILE | IDCAMS   | Refresh Account Master                     |
| CARDFILE | IDCAMS   | Refresh Card Master                        |
| CUSTFILE | IDCAMS   | Refresh Customer Master                    |
| DISCGRP  | IDCAMS   | Load Disclosure Group File                 |
| TRANFILE | IDCAMS   | Load Transaction Master file               |
| TRANCATG | IDCAMS   | Load Transaction category types            |
| TRANTYPE | IDCAMS   | Load Transaction type file                 |
| XREFFILE | IDCAMS   | Account, Card and Customer cross reference |
| CLOSEFIL | IEFBR14  | Close VSAM files in CICS                   |
| TCATBALF | IDCAMS   | Refresh Transaction Category Balance       |
| TRANBKP  | IDCAMS   | Refresh Transaction Master                 |
| POSTTRAN | CBTRN02C | Transaction processing job                 |
| TRANIDX  | IDCAMS   | Define AIX for transaction file            |
| OPENFIL  | IEFBR14  | Open files in CICS                         |
| INTCALC  | CBACT04C | Run interest calculations                  |
| COMBTRAN | SORT     | Combine transaction files                  |
| CREASTMT | CBSTM03A | Produce transaction statement              |

<br/>

### Application Screens

#### **Signon Screen**

![Alt text](./diagrams/Signon-Screen.png?raw=true "Signon Screen")


#### **Main Menu**

![Alt text](./diagrams/Main-Menu.png?raw=true "Main Menu")

#### **Admin Menu**

![Alt text](./diagrams/Admin-Menu.png?raw=true "Admin Menu")

<br/>

## Support

If you have questions or requests for improvement please raise an issue in the repository.

<br/>

## Roadmap

The following features are planned for upcoming releases

1. More database types

   1. Relational Database usage : Db2 
   
   2. Hierachical database calls : IMS

2. Integration

   * ftp, sftp
   
   * Message queue integration
   
   * Exposure of transactions for distributed application integration

<br/>

## Contributing

We are looking forward to receiving contributions and enhancements to this initial codebase from the mainframe code base

Feel free to raise issues, create code and raise merge requests for enhancements so that we can build out this application as a resource for programmers wanting to understand and modernize their mainframes.

<br/>

## License

This is intended to be a community resource and it is released under the Apache 2.0 license.

<br/>

## Project status

We are planning a v2 of this application in Q1 2023.

Watch this space for updates

<br/>


