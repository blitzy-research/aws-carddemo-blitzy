# Onboarding Guide

How to build, run and validate the migrated Java module on your own machine, from a clean checkout.

This is the first-run walkthrough. It tells you which command to run, what each one proves, and what to
do when one of them fails. It does not restate the design — that is [Architecture](architecture.md) — and
it does not restate why any translation decision was made — that is
[Migration Decision Log](decision-log.md). The module's own
[`carddemo-java/README.md`](../carddemo-java/README.md) is the exhaustive reference behind this page; when
the two overlap, this page is the short path and the README is the detail.

Everything here runs locally. **No gate requires a production environment, a staging environment or a
running COBOL system.** Every check runs on a developer machine through Docker Compose, Testcontainers and
the LocalStack **Community** edition, so no AWS account and no cloud credential is involved at any point.

The legacy estate under `app/` is **read-only reference**. Nothing in this walkthrough modifies it,
nothing copies it into the module, and it must stay byte-identical: it is simultaneously the parity
baseline every golden fixture derives from and the anchor every traceability row cites. The provenance
those citations use is checkout SHA `7756d895ffeb65f7ea72aaa609e356d9899afcec` and upstream release stamp
`CardDemo_v1.0-15-g27d6c6f-68`, dated 2022-07-19.

---

## 1. Prerequisites

Two, and only two.

| Prerequisite | What it must be | Why it is needed |
| :----------- | :-------------- | :--------------- |
| **A JDK 25 installation** | Eclipse Temurin **25.0.3+9** is the build this module is verified against | The module compiles at `<release>25</release>`. An older JDK fails immediately, at the compiler rather than at a test |
| **Docker, with Compose** | The `docker compose` v2 subcommand, not the legacy `docker-compose` script | The local stack is a Compose project, and every integration and end-to-end test starts its own containers through Testcontainers |

**No preinstalled Maven is required.** The Maven Wrapper is committed inside the module — `mvnw` for
POSIX shells, `mvnw.cmd` for Windows — and `.mvn/wrapper/maven-wrapper.properties` pins the distribution
and verifies it against a recorded SHA-256 digest. That pin is the module's business, not yours: you
never install, choose or bump a Maven version, you just run `./mvnw`. The wrapper is declared
`distributionType=only-script`, so there is no `maven-wrapper.jar` in the tree; the first invocation
downloads the pinned distribution, checks its digest and caches it, and every later invocation reuses the
cache.

That short list is the whole point of Standard 1, reproducible hermetic builds: a clean checkout must
build with a JDK and Docker alone, which is exactly why the wrapper, the `Dockerfile` and the
`docker-compose.yml` all live inside `carddemo-java/` rather than in an operations repository. There is no
global Maven settings file to write, no private registry to configure and no credential to obtain — every
dependency resolves from public Maven Central.

Confirm both prerequisites before you build anything:

```bash
java -version           # expect: openjdk 25.0.3 … Temurin-25.0.3+9
docker compose version  # expect: Docker Compose version v2 or later
```

If your shell is non-interactive or non-login and `java` is not already on `PATH`, export `JAVA_HOME`
first — the wrapper resolves the JDK through `JAVA_HOME` before it looks at `PATH`:

```bash
export JAVA_HOME=/path/to/jdk-25
```

---

## 2. Build

Every command in this section runs from the module directory:

```bash
cd carddemo-java
./mvnw -B clean verify
```

### Why `verify` and not `package`

`verify` is the phase the gates are bound to. Four plugins do their work there or on the way to it, and
`package` reaches none of the last three:

| Plugin | Version | What it contributes to `verify` |
| :----- | :------ | :------------------------------ |
| `maven-surefire-plugin` | 3.5.6 | The unit tier. Includes `**/*Test.java` and **excludes** `**/*IT.java`, `**/*E2ETest.java` and `**/e2e/**/*.java` |
| `maven-failsafe-plugin` | 3.5.6 | The integration and end-to-end tier. Includes `**/*IT.java`, `**/*E2ETest.java` **and** `**/e2e/**/*Test.java` |
| `jacoco-maven-plugin` | 0.8.15 | The coverage check — a **build-failing** rule at **≥ 80% line coverage**, evaluated on unit and integration data merged together |
| `dependency-check-maven` | 12.1.3 | The CVE scan, bound to `verify` and actually executed, failing the build at a CVSS threshold of 7.0 |

The failsafe include list needs those extra two patterns because the three end-to-end classes are named
`…Test` rather than `…IT`, yet are genuinely end-to-end and must not run in the unit tier:
`BatchPipelineE2ETest`, `OnlineTransactionE2ETest` and `GateVerificationTest`, all under
`src/test/java/com/carddemo/e2e/`. Surefire's exclusions are the mirror image of those includes, so no
class runs twice and none is silently skipped.

So `./mvnw package` builds a jar and proves nothing about any gate. `./mvnw -B clean verify` is the
command every gate is quoted against. Expect it to take several minutes on a warm machine and
considerably longer the first time, because the CVE scan builds its local vulnerability data once before
it can evaluate anything — a long first run is the scan working, not the build hanging.

### The build is zero-warning by construction

`maven-compiler-plugin` 3.14.1 is configured with `<release>25</release>` and the compiler arguments
`-Xlint:all` and `-Werror`. **Any warning is a build failure.** If you see one, you have found a bug, not
a nuisance — fix the cause rather than the setting. This is Standard 2 in mechanical form: warnings cannot
accumulate quietly because they stop the build, which is also what makes Gate 2 a check rather than a
claim.

### Narrower runs, and what each one gives up

These are alternatives rather than a sequence — pick the one that matches what you are checking:

```bash
cd carddemo-java
./mvnw -B clean test-compile          # compiles main and test sources; the fastest honest -Werror check
./mvnw -B clean test                  # the unit tier only; needs no container, so it runs without Docker
./mvnw -B clean verify -DskipITs      # all but the container-backed tier; the coverage rule still runs
./mvnw -B clean package -DskipTests   # a jar, and no gate evidence whatsoever
```

Three further switches narrow a run, and none of them may be used to declare a gate met:

```bash
cd carddemo-java
./mvnw -B verify -Ddependency-check.skip=true                        # skip only the CVE scan
./mvnw -B test -Pscoped-tests -Dtest=ZonedDecimalCodecTest           # one unit class
./mvnw -B verify -Pscoped-tests -Dit.test=OnlineTransactionE2ETest   # one integration or E2E class
```

The `scoped-tests` profile exists because the coverage floor is a whole-module figure and a scoped run
cannot satisfy it: without the profile, a narrowed run that reaches `verify` measures a fraction of the
suite against the whole module's floor and fails for a reason unrelated to the tests you asked for. The
profile relaxes **only** the two coverage properties — the compiler stays at `-Werror` and the CVE
threshold is untouched — and it is reachable only by name, so an unscoped build is unaffected. A coverage
figure from a scoped run is not a coverage figure, and a green scoped run is not a green build.

The failsafe tier needs **Docker running**, because it is Testcontainers-backed. Without a reachable
daemon those tests fail at container startup rather than on an assertion, which is a different problem
with a different fix.

---

## 3. Bring up the local stack

The Compose project provisions everything the running application talks to:

```bash
cd carddemo-java
docker compose up -d
docker compose ps
```

| Service | Role | Host port |
| :------ | :--- | --------: |
| `postgres` | PostgreSQL 16 — the relational store the eleven migrated tables live in | 5432 |
| `localstack` | LocalStack Community — the S3 staging bucket, the SQS FIFO job queue and the SNS topic | 4566 |
| `jaeger` | OTLP trace collection and its query interface | 16686 |
| `prometheus` | Scrapes `/actuator/prometheus` | 9090 |
| `grafana` | Provisioned datasource and the `carddemo-overview` dashboard | 3000 |
| `app` | The module itself, on the `local` profile | 8080 |

Jaeger also publishes the two OTLP receiver ports, 4317 for gRPC and 4318 for HTTP. Every image is pinned
by digest, and every published port binds to the loopback interface by default. The observability
configuration the last three services mount lives under `carddemo-java/config/` — `config/prometheus/`
and `config/grafana/`, including `config/grafana/dashboards/carddemo-overview.json`.

`carddemo-java/localstack/init/01-create-aws-resources.sh` runs at LocalStack startup and creates the
three resources the application expects: the **S3 staging bucket** with versioning enabled, the **SQS FIFO
queue** that replaced the legacy transient data queue, and the **SNS topic** for job notification. It then
reads each one back rather than assuming the create succeeded, which is why the container's health check
only reports healthy once all three exist.

**LocalStack Community is sufficient and is what the stack uses.** S3, SQS and SNS are the full extent of
the AWS surface this module needs, so there is no licence token to supply and no Pro subscription to buy.
Nothing in the module reads one.

Starting, inspecting and tearing the stack down need no exports at all, because every variable in
`docker-compose.yml` carries a default. **Building** the application image is the one operation that
needs real provenance, and it is refused without it — the `Dockerfile` rejects the all-zero revision
sentinel by name:

```bash
cd carddemo-java
export APP_VERSION="$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)"
export SOURCE_REVISION="$(git rev-parse HEAD)"
export SOURCE_DATE_EPOCH="$(git log -1 --format=%ct)"
docker compose up -d --build
```

### Schema evolution

Flyway migrates the database forward only — there is no rollback script and `clean` is disabled — from
four scripts under `carddemo-java/src/main/resources/db/migration/`:

| Migration | What it creates |
| :-------- | :-------------- |
| `V1__create_schema.sql` | The eleven tables derived from the eleven verified record layouts |
| `V2__create_indexes.sql` | The three alternate-index equivalents as B-tree indexes, plus the primary and foreign keys |
| `V3__seed_reference_data.sql` | The sample reference and transaction data |
| `V4__seed_user_security.sql` | The ten seeded identities, stored as BCrypt hashes |

**`V3` and `V4` apply under the `local` and `test` profiles only.** Both profiles raise the Flyway target
to `latest`; the shared configuration and the `prod` profile pin it to version `2`. A production migration
therefore gets the schema and the indexes and inherits neither the sample data nor a seeded credential —
which is Standard 6 doing its job, and the reason the seed scripts can be as generous as they are.

Teardown and inspection, again as alternatives. Reach for the second form when the database has drifted:
a stale volume is the most common local failure, because Flyway validates the checksum of every migration
it has already applied, and a volume that outlived an edited script will refuse to start:

```bash
cd carddemo-java
docker compose down                            # stop, keep the volumes
docker compose down -v                         # stop and DELETE the volumes, forcing a clean Flyway run
docker compose logs -f postgres localstack     # follow what the stack is actually doing
```

---

## 4. Run the application

The `local` profile talks to the Compose endpoints and needs no secret at all — every value it reads
carries a default — so the module runs straight from the build:

```bash
cd carddemo-java
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

…or from the packaged artefact, whose name comes from the module coordinate
`com.carddemo:carddemo-java:1.0.0`:

```bash
cd carddemo-java
java -jar target/carddemo-java-1.0.0.jar --spring.profiles.active=local
```

Either way, if the Compose stack is already up, free the port first with `docker compose stop app` —
otherwise the second process cannot bind 8080. Leaving the container running and putting your own process
on another port with `--server.port=18080` works just as well.

### The three profiles

| Profile | Binds to | What to know |
| :------ | :------- | :----------- |
| `local` | The Docker Compose endpoints | Every value is defaulted, so no secret is needed; Flyway runs `V1`–`V4`; the OpenAPI description is published |
| `test` | Testcontainers-provided endpoints | Activated by the failsafe tier; container lifecycle belongs to the shared support base classes, never to an individual test; Flyway runs `V1`–`V4` |
| `prod` | Externally provided endpoints | **Every secret comes from an environment variable with no fallback default**; Flyway stops at `V2` |

### The `prod` profile has no defaulted secrets

This is Standard 5, and it is enforced by absence rather than by convention: the profile resolves each of
the following from the environment with **no fallback value**, so a missing one **fails startup** instead
of silently binding a placeholder. Thirteen variables are required, and none of them is needed to build,
test or run locally. Names only — no value for any of these appears anywhere in this repository, and none
should ever be written into a file:

| Variable | What it configures |
| :------- | :----------------- |
| `CARDDEMO_DB_URL` | The JDBC URL of the PostgreSQL instance |
| `CARDDEMO_DB_USERNAME` | The database user the application connects as |
| `CARDDEMO_DB_PASSWORD` | That user's credential |
| `CARDDEMO_JWT_SECRET` | The signing key for issued tokens |
| `CARDDEMO_MANAGEMENT_TOKEN` | The operator credential the protected management endpoints require |
| `CARDDEMO_FIELD_ENCRYPTION_KEY` | The key behind field-level encryption at rest |
| `CARDDEMO_SQS_QUEUE` | The FIFO queue the job-submission bridge publishes to |
| `AWS_REGION` | The region the S3, SQS and SNS clients resolve against |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | Where traces are exported |
| `CARDDEMO_TLS_KEYSTORE` | The server keystore location |
| `CARDDEMO_TLS_KEYSTORE_PASSWORD` | The keystore credential |
| `CARDDEMO_TLS_KEYSTORE_TYPE` | The keystore format |
| `CARDDEMO_TLS_KEY_ALIAS` | The key entry to use from that keystore |

Everything else the profile reads carries a default, and every one of those is an operational value rather
than a secret — a bucket name, a topic name, a message group, a token lifetime, a sampling rate.

### Signing on

Sign-on is the entry point, and it carries the attention-key field the 3270 contract required: omit
`keyAction` and the service answers with the legacy invalid-key message, which is faithful behaviour
rather than a validation bug. Supply the seeded credential yourself — read it into the shell so it is
never echoed, never in your history and never in a file:

```bash
read -rs SEED_CREDENTIAL

curl -i -X POST http://localhost:8080/api/auth/signon \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":\"ADMIN001\",\"password\":\"$SEED_CREDENTIAL\",\"keyAction\":\"ENTER\"}"
```

A successful sign-on answers `200` and returns the token in the `Authorization` **response** header; the
body carries the next route the legacy screen would have transferred to, which is the admin menu for an
administrator identity and the main menu for a standard one. `ADMIN001` is an administrator identity and
`USER0001` a standard one; the ten seeded identities and their types are listed in
[Gate Evidence](gate-evidence.md) under Gate 4.

### Endpoints worth knowing

| Endpoint | What it gives you | Open without a credential |
| :------- | :---------------- | :------------------------ |
| `/actuator/health` | Aggregate health | Yes |
| `/actuator/health/liveness`, `/actuator/health/readiness` | The two probe groups; the container health check uses liveness | Yes |
| `/actuator/prometheus` | Micrometer exposition, including the per-endpoint and per-step timers | Yes — this is Prometheus's scrape target |
| `/actuator/info`, `/actuator/metrics` | Build identity and the individual meters | No — these require the operator credential |
| `/v3/api-docs` | The OpenAPI description of the migrated screen contract, 19 paths | Yes, and **only on the `local` profile** |

There is **no browser interface, by design**. The legacy presentation layer is a 3270 terminal contract,
and the faithful translation of a terminal contract under a no-feature-expansion constraint is a machine
contract. Swagger UI is disabled on every profile; the description at `/v3/api-docs` is the contract, and
the tests are its consumer.

---

## 5. Run the gates

Eight gates, all of them local. To repeat the constraint because it is the one people assume away: **no
gate requires a production environment, a staging environment or a running COBOL system.** One command
executes almost all of them; what differs is which artefact you then read.

```bash
cd carddemo-java
./mvnw -B clean verify
```

| Gate | What it proves | How to run it |
| ---: | :------------- | :------------ |
| **1** — End-to-end boundary | Byte-equivalent output at the four contractual widths — 80, 100, 133 and 430 bytes — compared as byte arrays against committed goldens | `./mvnw -B clean verify`, or `./mvnw -B verify -Pscoped-tests -Dit.test=BatchPipelineE2ETest` for the pipeline alone. Docker required |
| **2** — Zero-warning build | A clean compile under `-Xlint:all -Werror` at `<release>25</release>` | `./mvnw -B clean verify` — the compiler is the gate |
| **3** — Performance baseline | Elapsed time, peak memory and records per second, taken from the Micrometer batch-step timers | `./mvnw -B clean verify`, then read `target/gate-evidence/gate3-*.md`; `/actuator/prometheus` and the Grafana `carddemo-overview` dashboard corroborate |
| **4** — Named real-world artefacts | The nine ASCII fixtures and twelve encoded datasets by name, the ten seeded identities, and the five validation-lookup cardinalities | `BatchPipelineE2ETest` and `GateVerificationTest`, both inside `./mvnw -B clean verify` |
| **5** — Interface contracts | The sign-on message texts, the routing each delivered user type produces, and the 17-card job image drained back out of a real FIFO queue | `./mvnw -B clean verify`, or `./mvnw -B verify -Pscoped-tests -Dit.test=OnlineTransactionE2ETest` |
| **6** — Unsafe and low-level audit | Counts of raw SQL concatenation, `Runtime.exec`, reflection, unchecked casts and suppressed warnings | The scoped grep below |
| **7** — Scope tier | **Line** coverage at or above 80%, enforced as a build-failing JaCoCo rule over merged unit and integration data | `./mvnw -B clean verify`; report at `target/site/jacoco-merged/index.html` |
| **8** — Integration sign-off | All of the above, plus zero unsuppressed critical or high CVEs and the full traceability row count | `./mvnw -B clean verify`; consolidated in [Gate Evidence](gate-evidence.md) |

The two scoped forms above are diagnostics for one contract at a time. They carry `-Pscoped-tests` for the
reason given in §2, they still run the whole unit tier unless you narrow `-Dtest` as well, and a scoped run
is never the evidence for a gate — the authoritative run is the unscoped `./mvnw -B clean verify`.

### Gate 6: the audit, and why its scope is the whole answer

```bash
cd carddemo-java
grep -rnE 'Runtime\.getRuntime|ProcessBuilder|java\.lang\.reflect|Class\.forName|createNativeQuery|@SuppressWarnings' src/main/java/
```

**The audit is scoped to `src/main/java/` — everything beneath it and nothing else — and that scoping is
load-bearing rather than cosmetic.** The Flyway files under `src/main/resources/db/migration/` are `.sql`
schema artefacts, not application code assembling a query out of strings; an unscoped grep would report
**four phantom raw-SQL violations** that are in fact the versioned schema definition the design requires.
Test sources are excluded for a related reason: assertion helpers legitimately do things production code
does not.

The committed counts are a **design constraint rather than a measurement**, because the module is
greenfield and every one of them was decided before the code was written:

| Category | Committed target |
| :------- | ---------------: |
| Raw SQL string concatenation | 0 |
| `Runtime.exec` / `ProcessBuilder` | 0 |
| Reflection | 0 |
| Unchecked casts | ≤ 5 |
| Suppressed warnings | ≤ 3 |

Each suppression, if one ever appears, carries an inline justification naming the framework construct that
forces it. The **reflection target of zero is the load-bearing one**: it is why all eleven record mappers
are hand-written with explicit offsets instead of being generated, and why no annotation processor appears
anywhere in the dependency set. The measured counts, with the raw command output beside them, are
published in [Gate Evidence](gate-evidence.md).

### Reading the coverage result

The JaCoCo floor is **80% line coverage, enforced as a build-failing check** — a `check` goal bound to
`verify` with `haltOnFailure`, evaluated over unit and integration execution data merged together, and
accompanied by a second rule that permits **zero wholly-untested classes** so a healthy average cannot
hide an absent test suite. **Branch, method and instruction coverage are produced in the reports for
information and are deliberately not gated**; read them as description, never as a threshold.

Three reports land after a full run, and only the first is the one the gate evaluates:

| Report | Covers |
| :----- | :----- |
| `target/site/jacoco-merged/index.html` | Unit and integration execution merged — **the gated figure** |
| `target/site/jacoco/index.html` | The unit tier alone |
| `target/site/jacoco-it/index.html` | The integration and end-to-end tier alone |

### Where the durable evidence lives

`carddemo-java/.gitignore` excludes `/target/`, which is correct — a build directory is not a deliverable —
and it means none of the local reports is committed. On your machine they survive until the next `clean`.
The durable record is what the **CardDemo Java CI** workflow uploads:
`.github/workflows/carddemo-java-ci.yml` scopes itself into the module with
`defaults.run.working-directory: carddemo-java`, sets up Eclipse Temurin 25.0.3+9, runs
`./mvnw -B … clean verify`, and uploads `jacoco-coverage-reports`, `owasp-dependency-check-report`,
`test-reports` and `container-vulnerability-scan-reports` as build artefacts. Fetch those from the
workflow run rather than quoting a figure from any document, and see [Gate Evidence](gate-evidence.md) for
the standing per-gate record.

Two notes on running the gates that will otherwise surprise you:

- **The first CVE scan on a cold machine is slow, and the rest are not.** `dependency-check` builds a
  local copy of the vulnerability data before it can evaluate anything, and reuses it afterwards. Read
  `target/dependency-check-report.html` from your own run rather than quoting a count from a document: a
  vulnerability count has a shelf life measured in days, because the database changes without the code
  changing.
- **The Compose database is a developer convenience, not the seed baseline.** Its data drifts as soon as
  anything is exercised against it, so anything that asserts seeded content migrates a fresh container
  instead — which is precisely what the integration tier does.

### Gate 3 establishes a baseline; it does not test one

⚠ **There is no performance target here, and there must not be.** No latency, capacity or availability
figure exists anywhere in the legacy estate, so there is nothing to compare against and nothing in the
suite asserts that any figure is good enough. Gate 3 records the **first Java baseline**: run the pipeline,
read the batch-step timers, and publish the numbers beside the fixture volumes and the machine they were
taken on. Read the rows already in [Gate Evidence](gate-evidence.md) as measurements from one named
machine, never as thresholds — and when you add your own, name your machine too.

---

## 6. Contribution conventions

These come from [`CONTRIBUTING.md`](../CONTRIBUTING.md) at the repository root, which is the authority;
the summary below is the module-specific reading of it.

> **Security issues never go in a public GitHub issue.** If you discover a potential security problem,
> notify AWS/Amazon Security through their
> [vulnerability reporting page](http://aws.amazon.com/security/vulnerability-reporting/) instead. This is
> the one convention here whose consequences a follow-up commit cannot undo.

**Before you write anything.** Work against the latest source on the **main** branch. Check the existing
open and recently merged pull requests, so you do not spend an afternoon on something already addressed.
For anything significant, open an issue and discuss it first — the guide is explicit that it would rather
talk than see your time wasted. If you are looking for a place to start, the repository uses the default
GitHub labels, and `help wanted` is the useful one.

**Filing a bug.** Use the GitHub issue tracker, and check the existing open and recently closed issues
first in case it is already reported. A report is much easier to act on when it carries a reproducible
test case or a series of steps, the version of the code you were using, any local modifications relevant
to the failure, and anything unusual about your environment or deployment.

**Opening a pull request.** Fork the repository and work on your fork. Keep the change focused on what you
are actually contributing: reformatting unrelated code makes the real change hard to find, and hard to
review. Ensure local tests pass before you send it — for this module that means a green
`./mvnw -B clean verify` from `carddemo-java/`, with Docker running so the container-backed tier actually
executes. Commit with clear messages, answer the default questions in the pull request interface, then pay
attention to the automated CI failures reported on the pull request and stay involved in the conversation.

**Conduct and licensing.** The project has adopted the Amazon Open Source Code of Conduct; see
[`CODE_OF_CONDUCT.md`](../CODE_OF_CONDUCT.md). The project is licensed under **Apache-2.0** — see
[`LICENSE`](../LICENSE), with [`NOTICE`](../NOTICE) attributing Amazon.com, Inc. or its affiliates — and
you will be asked to confirm the licensing of your contribution. Every generated source file in the module
carries the same Apache-2.0 header the legacy members carry, so keep it on anything new.

---

## 7. Troubleshooting

| Symptom | Cause | What to do |
| :------ | :---- | :--------- |
| The failsafe tier fails at container startup rather than on an assertion | Docker is not running, or the daemon is unreachable | Start Docker. The unit tier still runs without it: `./mvnw -B clean test` |
| `warnings found and -Werror specified` | A new compiler warning | Fix the warning. Do not relax the compiler and do not reach for a suppression — the zero-warning build is Gate 2, and the suppression budget is three with an inline justification each |
| The build fails on the coverage rule after your change | Merged line coverage fell below the floor, or a class arrived with no test at all | Add the missing tests. The floor and the zero-untested-classes rule are both failing checks by design |
| Startup fails with `Validate failed: Migrations have failed validation` and a checksum mismatch for a migration version | The Postgres volume still holds a database migrated by the previous content of that script | `docker compose down -v`, then bring the stack back up so the migrations replay from empty. That is validation working, not a reason to repair the history |
| The application will not start on the `prod` profile | A required variable is unset | Supply it. There is no fallback default by design, and startup failing is the intended outcome |
| Port 8080 is already in use | The Compose `app` service already has it | `docker compose stop app`, or start your process on another port with `--server.port=18080` |
| `./mvnw: Permission denied` | The wrapper script lost its executable bit | `chmod +x carddemo-java/mvnw` |
| Dependency resolution fails on a first build | No network, or an empty local repository | The build needs Maven Central once; after that the local repository serves it |

---

## 8. Where to look next

| Question | Document |
| :------- | :------- |
| How is the module structured, and why is the batch tier shaped like that? | [Architecture](architecture.md) |
| Which Java method corresponds to a given COBOL paragraph? | [Traceability Matrix](traceability-matrix.md) |
| Why does this code truncate instead of rounding? Why is that method deliberately empty? | [Migration Decision Log](decision-log.md) |
| What does each gate prove today, and what did the recorded run measure? | [Gate Evidence](gate-evidence.md) |
| How do I configure, extend or operate the module in full detail? | [`carddemo-java/README.md`](../carddemo-java/README.md) |
| What did the mainframe application actually do? | [`README.md`](../README.md) |

Two pieces of vocabulary, because they are easy to mislabel. The eight gates are **acceptance criteria**,
and the COBOL-to-Java construct mapping with its preservation requirements is a **requirement** of the
migration. Neither is a project rule — no rules document defines either, and none was supplied for this
work — so read the gates here and in [Gate Evidence](gate-evidence.md), and read how each construct was
translated in [Migration Decision Log](decision-log.md) and
[Traceability Matrix](traceability-matrix.md).
