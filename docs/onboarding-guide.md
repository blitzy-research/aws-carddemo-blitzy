# Onboarding Guide

How to build, run and validate the migrated Java module on your own machine, from a clean checkout.

This page is the first-run walkthrough. It does not restate the architecture — that is
[`architecture.md`](architecture.md) — and it does not restate why any translation decision was made —
that is [`decision-log.md`](decision-log.md). It tells you which commands to run, what each one proves,
and what to do when one of them fails.

Everything here runs locally. **No gate requires a production environment, a staging environment or a
running mainframe**, and none requires an AWS account: object storage, queueing and notification are
served by LocalStack Community, and PostgreSQL by a container.

Provenance of the source estate this module was migrated from: checkout SHA
`7756d895ffeb65f7ea72aaa609e356d9899afcec`, upstream release stamp `CardDemo_v1.0-15-g27d6c6f-68`
dated 2022-07-19.

---

## 1. Prerequisites

| Tool | Version | Why exactly this |
| --- | --- | --- |
| JDK | **Eclipse Temurin 25** (25.0.3+9 verified) | The module compiles with `<release>25</release>`; an older JDK cannot compile it and a different vendor build has not been verified against the zero-warning gate |
| Docker | Engine 28+ with the Compose plugin | Every integration and end-to-end test starts containers through Testcontainers, and the local stack is a Compose project. `docker compose`, not the legacy `docker-compose` script |
| Maven | **none needed** | The module ships the Maven Wrapper pinned to 3.9.16. Always use `./mvnw` |

Nothing else is required. There is no global Maven settings file, no private registry and no credential
to configure: every dependency resolves from public Maven Central.

```bash
java -version          # expect: openjdk 25.0.3 ... Temurin-25.0.3+9
docker info | head -5  # expect a running engine, not "Cannot connect"
```

If `JAVA_HOME` is not set in your shell, set it before building — the wrapper itself does not need it,
but some tools do:

```bash
export JAVA_HOME=/usr/lib/jvm/temurin-25
```

---

## 2. Build

Every command below runs from the module directory:

```bash
cd carddemo-java
```

| Command | What it does | What it proves |
| --- | --- | --- |
| `./mvnw -B clean test-compile` | compiles main and test sources | **Gate 2.** `-Xlint:all -Werror` is active, so any warning fails the build. This is the fastest honest check that the tree is clean |
| `./mvnw -B test` | the unit tier only | No container is started, so this runs without Docker |
| `./mvnw -B verify -DskipITs` | everything except the container-backed tier | Useful when Docker is unavailable |
| `./mvnw -B clean verify` | **the full gate** | Both test tiers, JaCoCo's failing line-coverage check, and the OWASP dependency scan |

`./mvnw -B clean verify` is the command every gate is quoted against. Expect it to take several minutes
on a warm cache and considerably longer on the first run, because the OWASP scan downloads its
vulnerability database.

Two switches exist for narrowing a run, and neither may be used to declare a gate met:

```bash
./mvnw -B verify -Ddependency-check.skip=true          # skip only the CVE scan
./mvnw -B test -Pscoped-tests -Dtest=ClassName         # one unit class
./mvnw -B verify -Pscoped-tests -Dit.test=ClassNameIT  # one integration class
```

The `scoped-tests` profile relaxes the two JaCoCo coverage properties, because a scoped run cannot
satisfy a whole-module coverage floor. **It relaxes nothing else** — the compiler stays at `-Werror` and
the CVE threshold is untouched — and a coverage figure from a scoped run is not a coverage figure.

### When the build fails

| Symptom | Cause | What to do |
| --- | --- | --- |
| `warnings found and -Werror specified` | a new warning | Fix the warning. Do not relax the compiler; the zero-warning build is Gate 2 |
| `Rule violated for bundle carddemo-java: lines covered ratio is 0.7x` | coverage below the floor | Add the missing tests. The floor is a failing check by design |
| `Could not resolve dependencies` on a first run | no network, or an empty local repository | The build needs Maven Central once; afterwards `-o` (offline) works |
| `Cannot connect to the Docker daemon` | Docker not running | Start Docker. The unit tier still runs without it |
| Container startup timeouts | slow first image pull | Pre-pull the images listed under §3, then retry |

---

## 3. Bring up the local stack

The Compose project provisions everything the running application talks to:

```bash
cd carddemo-java
docker compose up -d
docker compose ps          # expect every service healthy
```

| Service | Port | What it stands in for |
| --- | --- | --- |
| `postgres` | 5432 | the ten VSAM base clusters and their three alternate indexes |
| `localstack` | 4566 | S3 batch staging, the SQS FIFO queue that replaced the transient data queue, and SNS |
| `prometheus` | 9090 | scrapes `/actuator/prometheus` |
| `grafana` | 3000 | the provisioned per-endpoint and per-step dashboard |
| `jaeger` | 16686 | OTLP trace collection |
| `app` | 8080 | the module itself, on the `local` profile |

Building the application image requires build provenance, because the `Dockerfile` refuses the
all-zero sentinel:

```bash
export APP_VERSION="$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)"
export SOURCE_REVISION="$(git rev-parse HEAD)"
export SOURCE_DATE_EPOCH="$(git log -1 --format=%ct)"
docker compose up -d --build
```

`docker compose down` stops the stack and keeps its volumes. `docker compose down -v` also discards the
database, which is the correct way to return to a clean seeded state.

---

## 4. Run and sign on

The `local` profile needs **no secret at all** — every value is defaulted — so the module runs straight
from the jar:

```bash
SPRING_PROFILES_ACTIVE=local java -jar target/carddemo-java-1.0.0.jar
```

Stop the Compose `app` service first (`docker compose stop app`) so port 8080 is free.

Sign on with a seeded identity. The JWT comes back in the `Authorization` **response** header:

```bash
curl -i -X POST http://localhost:8080/api/auth/signon \
  -H 'Content-Type: application/json' \
  -d '{"userId":"ADMIN001","password":"PASSWORD","keyAction":"ENTER"}'
```

`ADMIN001` is an administrator identity and `USER0001` a standard one; the ten seeded identities and
their roles are listed in [`gate-evidence.md`](gate-evidence.md) under Gate 4. `keyAction` is
**mandatory** — omitting it returns the legacy "Invalid key pressed" reply, which is the faithful
behaviour and not a validation bug.

| Endpoint | What it gives you |
| --- | --- |
| `/actuator/health`, `/health/liveness`, `/health/readiness` | liveness and readiness |
| `/actuator/metrics`, `/actuator/prometheus` | the Micrometer registry, including the per-endpoint and per-step timers |
| `/v3/api-docs` | the OpenAPI description, published from the controllers |

There is **no browser interface, by design**: the legacy presentation layer is a 3270 terminal contract,
and the faithful translation of a terminal contract under a no-feature-expansion constraint is a machine
contract. Swagger UI is deliberately disabled; the description at `/v3/api-docs` is the contract.

The `prod` profile is the opposite posture: it resolves every secret from an environment variable with
**no fallback**, so a missing secret fails startup rather than silently binding a placeholder. It is not
needed for building, testing or running locally.

---

## 5. Run the gates

All eight gates are executed by one command; what differs is which artefact you then read.
[`gate-evidence.md`](gate-evidence.md) is the consolidated record.

```bash
cd carddemo-java
./mvnw -B clean verify
```

| Gate | What it establishes | Where the evidence lands |
| --- | --- | --- |
| 1 | byte equivalence of the emitted records | `target/gate-evidence/gate1-byte-equivalence.md`, one row per contract with expected and actual byte counts |
| 2 | zero-warning build | the build itself — `-Werror` makes it mechanical |
| 3 | a **measured** performance baseline | `target/gate-evidence/gate3-pipeline-baseline.md` and `gate3-interest-calculation.md` |
| 4 | the named validation artefacts | `target/failsafe-reports/`; every fixture is asserted by name and by measured geometry |
| 5 | the external interface contracts | `target/failsafe-reports/`; the queue contract is drained from a real queue |
| 6 | the unsafe-code audit | the scoped grep list, recorded in `gate-evidence.md` |
| 7 | scope matching and the coverage floor | `target/site/jacoco/`; the check fails the build below 80% line coverage |
| 8 | the integration sign-off | `target/gate-evidence/gate8-sign-off.md`, one row per checklist item, each derived from an artefact |

### Recording a performance baseline

Gate 3 **establishes** a baseline; it does not test one. No latency, throughput, availability or
capacity figure exists anywhere in the legacy estate, so there is nothing to compare against, and
**nothing in the suite asserts that a figure is fast enough**.

To take a baseline of your own, run the two measuring tests and copy their output:

```bash
./mvnw -B verify -Pscoped-tests \
  -Dit.test='BatchPipelineE2ETest,InterestCalculationJobIT' -Ddependency-check.skip=true
cat target/gate-evidence/gate3-pipeline-baseline.md
cat target/gate-evidence/gate3-interest-calculation.md
```

Then add the date and the machine beside the figures in the measured-runs table in
[`gate-evidence.md`](gate-evidence.md). The recorder deliberately does not write into `docs/`: a
baseline is published by a person who can name the hardware it was taken on, and a test that edited the
documentation tree would make the repository's content depend on whoever last ran the suite. Read the
rows already there as measurements from one named machine, not as targets.

### Reading the CVE result

A vulnerability count has a shelf life measured in days, because the database changes without the code
changing. The scan is bound to `verify` and actually executed, and it fails the build at a CVSS
threshold of 7.0. Read `target/dependency-check-report.html` after your own run rather than quoting a
figure from any document.

---

## 6. Where to look next

| Question | Document |
| --- | --- |
| How is the module structured, and why is the batch tier shaped like this? | [`architecture.md`](architecture.md) |
| Which Java method corresponds to a given COBOL paragraph? | [`traceability-matrix.md`](traceability-matrix.md) — 544 rows, one per procedure unit |
| Why does this code truncate instead of rounding? Why is that paragraph empty? | [`decision-log.md`](decision-log.md) |
| What exactly does each gate prove today? | [`gate-evidence.md`](gate-evidence.md) |
| How do I build, run and configure the module in detail? | [`../carddemo-java/README.md`](../carddemo-java/README.md) |
| What did the mainframe application do? | [`../README.md`](../README.md) |

The legacy estate under `app/` is **read-only reference**. It is never modified, never copied into the
module, and it must stay byte-identical: it is the parity baseline every golden fixture is derived from
and the anchor every traceability row cites.
