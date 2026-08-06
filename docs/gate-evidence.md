# Gate Evidence

Recorded evidence for the eight validation gates of the CardDemo COBOL-to-Java migration.

**Provenance.** Legacy estate checkout SHA `7756d895ffeb65f7ea72aaa609e356d9899afcec`; upstream release
stamp `CardDemo_v1.0-15-g27d6c6f-68`, dated 2022-07-19, which is the designated provenance identifier for
this migration. Traceability is by citation — path and line number — and no legacy source text is
reproduced in the Java module or on this page.

## How to read this page

Each gate below names **the command that produces its evidence**, **the artefact the evidence lands in**,
and **the standing result** — the part of the outcome that is a property of the code rather than of the
machine that ran it. A zero warning count is a property of the code. A records-per-second figure is not:
it belongs to one run on one machine, so those are recorded in the *measured runs* tables with the date
and the machine named beside them, and a reader is expected to re-measure rather than to trust a number
recorded here.

**No gate on this page requires a production environment, a staging environment, or a running COBOL
system.** Every command runs against the local Docker Compose stack or against Testcontainers.

Two conventions are load-bearing and are stated once here rather than repeated per gate.

- **A measurement is never a threshold.** No numeric latency, throughput, availability or capacity figure
  exists anywhere in the legacy estate — not in the COBOL, not in the job streams, not in the CICS
  resource definitions. Gate 3 therefore *establishes* the first Java baseline; it does not test one. No
  figure on this page may become an assertion, and none is.
- **An absent figure is recorded as absent.** Where a gate's evidence is a procedure that has not been run
  on a given machine, this page says so rather than carrying a number of unknown origin.

## Commands

```bash
cd carddemo-java
export JAVA_HOME=/opt/java/current

# Gates 1, 2, 5, 7 and the coverage floor, in one pass.
./mvnw -B clean verify

# Gate 8's CVE scan, separated because it downloads and caches a vulnerability database.
./mvnw -B dependency-check:check

# Gates 3 and 4 additionally need the local stack for the container-backed runs.
docker compose --profile observability up -d
```

---

## Gate 1 — End-to-end boundary verification

| | |
| --- | --- |
| **Requirement** | At least one production-representative input processed end to end locally, producing byte-equivalent output against the documented COBOL baseline. Mocked I/O does not satisfy this gate. |
| **Command** | `./mvnw -B clean verify` |
| **Evidence artefact** | `target/failsafe-reports/`, and the golden fixtures under `src/test/resources/fixtures/expected/` |
| **Standing result** | The four contractual output widths are asserted byte for byte against golden fixtures, by tests that read what the code actually wrote rather than what it was asked to write. |

The four widths, each with a golden fixture in the tree:

| Output | Width | Golden fixture |
| --- | ---: | --- |
| Daily-transaction reject record | 430 bytes — the 350-byte source image plus a 4-digit reason code and a 76-character description | `fixtures/expected/daily-reject.txt` |
| Statement, plain text | 80 bytes | `fixtures/expected/statement.txt` |
| Statement, HTML | 100 bytes | `fixtures/expected/statement-html.txt` |
| Transaction report line | 133 bytes, `RECFM=FB` | `fixtures/expected/transaction-report.txt` |
| Transaction archive | fixed record stride, asserted whole | `fixtures/expected/transaction-archive.txt` |

The parity traps these comparisons exist to catch are recorded in `decision-log.md` rather than restated
here: truncating arithmetic with no `ROUNDED` clause anywhere in the estate, zoned-decimal sign overpunch,
the five reject reason codes, the statement banner literals, and the literal HTML constants including the
malformed table tag that must be emitted exactly as the source emits it.

---

## Gate 2 — Zero-warning build

| | |
| --- | --- |
| **Requirement** | Zero warnings and zero suppressed warnings from a clean checkout, framework-generated code excepted. |
| **Command** | `./mvnw -B clean verify` |
| **Evidence artefact** | The build log; the gate is self-enforcing rather than reported. |
| **Standing result** | **PASS, mechanically.** `maven-compiler-plugin` runs with `<release>25</release>`, `-Xlint:all` and `-Werror`, so any warning fails the build rather than accumulating in a log nobody reads. |

Two things make this a property of the code rather than of a reviewer's diligence. The wrapper is
committed, so a clean checkout needs no preinstalled Maven and builds the same way everywhere. And
`-Werror` means the count cannot drift: there is no state in which the build succeeds and a warning
exists.

Suppressed warnings are counted under Gate 6, where the budget is three and the measured count is zero.

---

## Gate 3 — Performance baseline

| | |
| --- | --- |
| **Requirement** | Benchmark locally and document elapsed time, peak memory and records per second, comparing against a documented COBOL baseline where available and otherwise establishing the Java baseline. |
| **Command** | `./mvnw -B clean verify` (the measurement runs inside the integration tier) |
| **Evidence artefact** | `target/gate-evidence/gate3-interest-calculation.md`, written by the run itself |
| **Standing result** | **There is no COBOL baseline to compare against**, so this gate establishes the first Java baseline. The measurement mechanism is a property of the code; the figures are properties of a run. |

### Where each figure comes from, and where it does not

This is the part of Gate 3 that is easiest to get wrong, so it is stated explicitly. The Grafana
dashboard shows all three figures, and until the measurement described below existed, two of the three
were read from queries that computed something adjacent to what the gate asks for:

| Figure | Source of the quotable figure | What the dashboard shows, and why it is not the same |
| --- | --- | --- |
| Elapsed time | The measured run's own wall clock, taken across the launch | *Batch job elapsed time* reads the framework's genuine per-execution timer and is sound corroboration, but as a series over a display window |
| Peak memory | `MemoryPoolMXBean.getPeakUsage()` summed across heap pools, after `resetPeakUsage()` immediately before the run | *Peak heap across scraped samples* is a maximum over **scrapes** — a peak that rises and falls between two scrapes is invisible, and a short run can fall between two scrapes entirely |
| Records per second | The run's own record count divided by that run's own elapsed time | *Application records per second (rolling rate)* divides by the **rate window**, so a job processing 300 records in 4 seconds inside a 1-minute window reads as 5 per second rather than 75 |

The measurement lives in `support/RunScopedPerformanceRecorder` and is driven from
`InterestCalculationJobIT`. It asserts only that a figure is **well formed** — a positive elapsed time,
the fixture's own record count, a peak the platform reported — and never that a figure is fast enough.
Adding such an assertion would invent a service level this migration is expressly forbidden from
inventing. Every dashboard panel adjacent to a Gate 3 figure is labelled a visualization, and
`decision-log.md` DL-182 records why.

### Measured runs

Figures are quoted with the fixture volumes they were measured over, because a number without them is not
a baseline. Re-measure on your own machine rather than trusting a row here.

| Date | Machine | Run | Records | Elapsed (ms) | Peak heap (bytes) | Records/second |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| _not yet recorded_ | _record the host, CPU and JVM_ | `interestCalculationJob` | — | — | — | — |

To fill a row: run `./mvnw -B clean verify`, then copy the table from
`target/gate-evidence/gate3-interest-calculation.md` and add the date and the machine. The generated file
carries the fixture volumes the run was measured over in its own footnote.

The volumes the interest measurement is taken over: three transaction-category-balance rows forming two
account groups, resolved against the seeded default disclosure group, over a reference seed of 50
accounts, 50 category balances and 51 disclosure rows.

---

## Gate 4 — Named real-world validation artefacts

| | |
| --- | --- |
| **Requirement** | Production-representative data files processed locally through the primary batch pipeline, with the artefacts specified **by name**. |
| **Command** | `./mvnw -B clean verify` |
| **Evidence artefact** | `target/failsafe-reports/`; the seeds are applied by Flyway `V3` and `V4` |
| **Standing result** | All nine ASCII fixtures are present at the byte counts below and are the source of the reference seed. Every filename appears in the test resources, so the "by name" requirement is discharged in code and not only in prose. |

| Artefact | Bytes | Records | Record length | Consumed by |
| --- | ---: | ---: | ---: | --- |
| `app/data/ASCII/acctdata.txt` | 15,050 | 50 | 300 | account seed; posting, interest, statements |
| `app/data/ASCII/carddata.txt` | 7,550 | 50 | 150 | card seed; card list and detail |
| `app/data/ASCII/cardxref.txt` | 1,850 | 50 | 36 data bytes of a 50-byte layout | cross-reference seed; every card-to-account resolution |
| `app/data/ASCII/custdata.txt` | 25,050 | 50 | 500 | customer seed; statements, account view |
| `app/data/ASCII/dailytran.txt` | 105,300 | 300 | 350 | primary posting input |
| `app/data/ASCII/discgrp.txt` | 2,601 | 51 | 50 | interest-rate lookup |
| `app/data/ASCII/tcatbal.txt` | 2,550 | 50 | 50 | category-balance seed for interest |
| `app/data/ASCII/trancatg.txt` | 1,098 | 18 | 60 | transaction-category reference |
| `app/data/ASCII/trantype.txt` | 427 | 7 | 60 | transaction-type reference |

Two composition facts make these fixtures genuinely representative rather than merely present. The 300
daily transactions are 250 point-of-sale purchases and 50 operator-originated returns, so both signed
directions of the balance computation are exercised; all 300 carry the same processing date, so
date-window filtering has to be exercised by a separately constructed fixture rather than by this input.
The 51 disclosure-group records form three complete seventeen-row groups, one of them the default group,
which is what makes both the direct-hit and the default-fallback branch of the interest lookup reachable
from seeded data alone.

The twelve EBCDIC datasets under `app/data/EBCDIC/` are retained as encoding-fidelity reference. Two
carry findings recorded in `decision-log.md`: the account dataset exists in duplicate under two names with
byte-identical content, one of which no job references, and the user-security dataset has no ASCII
counterpart — its content is fully recoverable from the provisioning job's in-stream card images, so no
EBCDIC decode is required.

---

## Gate 5 — API and interface contract verification

| | |
| --- | --- |
| **Requirement** | Every external interface verified by a local test that exercises the real contract. Self-certification is not acceptable. |
| **Command** | `./mvnw -B clean verify` |
| **Evidence artefact** | `target/failsafe-reports/` |
| **Standing result** | All three external contracts are exercised against real endpoints — golden files, a real queue, and real HTTP — rather than against a builder's return value. |

| Contract | How it is exercised |
| --- | --- |
| The four fixed-width file formats | Byte-equality assertions against the golden fixtures listed under Gate 1 |
| The sign-on message and routing contract | Real HTTP requests asserting the message strings and the administrator/user routing outcome |
| The batch trigger | The report-submission endpoint publishes to a **real** SQS FIFO queue on LocalStack; the test drains the queue and asserts the ordered card sequence, the four substituted date slots and the terminal `/*EOF` sentinel |

The queue's contractual attributes are preserved rather than approximated: 80-character fixed-width
records become one message body per card, append disposition becomes message-group ordering, and
ignore-on-error becomes a logged non-fatal failure that does not abort the caller.

---

## Gate 6 — Unsafe and low-level code audit

| | |
| --- | --- |
| **Requirement** | Documented counts of raw SQL string concatenation, `Runtime.exec` usage, reflection, unchecked casts and suppressed warnings. Any count above 50 requires per-site justification. |
| **Command** | The grep list below, scoped to `carddemo-java/src/main/java/**` |
| **Evidence artefact** | This table |
| **Standing result** | **Every count is zero.** No per-site justification is required, because there are no sites. |

```bash
cd carddemo-java
for pattern in 'java\.lang\.reflect' 'Class\.forName' 'Runtime\.getRuntime' \
               'ProcessBuilder' 'createNativeQuery' '@SuppressWarnings' 'import .*\.\*;'; do
  printf '%-24s %s\n' "$pattern" "$(grep -rn "$pattern" src/main/java --include='*.java' | wc -l)"
done
```

| Category | Budget | Measured | Note |
| --- | ---: | ---: | --- |
| Raw SQL string concatenation | 0 | **0** | All access through Spring Data derived queries or parameterized JPQL. No `createNativeQuery` anywhere. The one advisory-lock statement is a compile-time constant with its only variable bound as a parameter. |
| `Runtime.exec` / `ProcessBuilder` | 0 | **0** | The one construct that could have justified process invocation — legacy job submission — is an SQS publish. |
| Reflection (`java.lang.reflect`, `Class.forName`) | 0 | **0** | This is a design constraint, not hygiene: it is why all eleven record mappers are hand-written with explicit offsets and why no annotation processor appears in the dependency set. |
| Unchecked casts | ≤ 5 | **0** | `-Xlint:all -Werror` promotes an unchecked operation to a build failure, so the practical count cannot exceed zero. |
| Suppressed warnings | ≤ 3 | **0** | Same mechanism. Where an unchecked generic interaction with a framework API arose, the type was carried through a typed helper rather than suppressed. |
| Wildcard imports | 0 | **0** | Every import is explicit, so this audit can be performed by inspection rather than by resolution. |
| `javax.*` imports | 0 | **0** | Every persistence, validation, servlet and transaction annotation imports from `jakarta.*`. Fully-qualified uses of the JDK's own `javax.crypto` exist and are not imports; that package was never part of the Jakarta rename. |

**Scoping rule, stated because it changes the answer.** The audit is scoped to `src/main/java/**`. The
Flyway migrations under `src/main/resources/db/migration/` are `.sql` schema artefacts, and an unscoped
grep for SQL text would count the versioned schema definition as raw concatenation and inflate the figure.
Test sources are likewise excluded, since assertion helpers legitimately use constructs production code
does not.

---

## Gate 7 — Scope matching

| | |
| --- | --- |
| **Requirement** | The extended specification tier: multi-subsystem batch processing, file I/O, inter-program calls, JCL orchestration and AWS service integration, with at least 80% line coverage. |
| **Command** | `./mvnw -B clean verify` |
| **Evidence artefact** | `target/site/jacoco/index.html`, and the JaCoCo check that fails the build |
| **Standing result** | The coverage floor is enforced rather than reported: JaCoCo is configured as a failing check at 80% line coverage, so the build cannot succeed below it. Line coverage is the gated metric; branch, method and instruction coverage are reported for information. |

Coverage dimensions the scope tier requires, and where each is exercised:

| Dimension | Coverage |
| --- | --- |
| Multi-subsystem batch processing | Ten batch programs across five subsystems, plus the two-program statement pair that communicates through a shared linkage area |
| File I/O | Eleven record layouts at widths 50, 60, 80, 150, 300, 350 and 500 bytes; sequential, indexed and dynamic access; ten base clusters plus three alternate indexes plus one in-job transient cluster |
| Inter-program calls | Twenty-seven static call sites become injected collaborators; twenty-five transfer-control transitions and nineteen pseudo-conversational return points become route constants |
| JCL orchestration | Twenty-nine job members and two procedures; condition-code dependencies on exactly four steps; three distinct sort specifications; six generation-data-group bases |
| AWS service integration | Object storage, an SQS FIFO queue and SNS, all exercised against LocalStack Community |

---

## Gate 8 — Integration sign-off checklist

| | |
| --- | --- |
| **Requirement** | End-to-end verification, interface contract verification, performance baseline, unsafe-code audit, ≥80% line coverage, an OWASP dependency check with zero critical or high CVEs, and a traceability matrix covering 100% of COBOL paragraphs. |
| **Command** | `./mvnw -B clean verify` and `./mvnw -B dependency-check:check` |
| **Evidence artefact** | `target/dependency-check-report.html`, `target/site/jacoco/`, and `traceability-matrix.md` |

| Checklist item | Satisfying artefact | Standing result |
| --- | --- | --- |
| End-to-end verification | Golden fixtures at 80, 100, 133 and 430 bytes | Gate 1 above |
| Interface contract verification | Sign-on messages; the job-submission card image; a real SQS FIFO queue | Gate 5 above |
| Performance baseline | `support/RunScopedPerformanceRecorder`, figures in this page's Gate 3 tables | mechanism in place; figures are per-run |
| Unsafe code audit | The scoped grep list | every count zero |
| Line coverage ≥ 80% | JaCoCo failing check | enforced by the build |
| Zero critical/high CVEs | `dependency-check-maven` | **re-run before sign-off**; a CVE result ages, so a figure recorded here would be stale by the time it was read |
| Traceability 100% | `traceability-matrix.md` | one row per paragraph unit, citing the SHA and the release stamp |

### Dependency vulnerability scan

A CVE count is the one figure on this page with a shelf life measured in days: the same dependency set
scans clean one week and does not the next, because the database changed rather than the code. So the
result is **not** recorded here as a number. Run the scan, read
`target/dependency-check-report.html`, and record the date, the database version and the outcome in your
own release record.

```bash
cd carddemo-java && ./mvnw -B dependency-check:check
```

The gate is zero **critical or high**. Findings below that severity are reported and do not fail it.

---

## What this page deliberately does not contain

- **No threshold, limit or target of any kind.** Explained under Gate 3 and true throughout.
- **No frozen performance number presented as a property of the code.** Per-run figures live in the
  measured-runs tables with a date and a machine, or they are absent.
- **No CVE count.** It would be stale on arrival; the command and the report path are given instead.
- **No legacy source text.** Traceability is by citation, and `traceability-matrix.md` carries the
  citations.
