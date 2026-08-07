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

Two notes on running these, both recorded so a reader is not surprised by them:

- **Do not scope a run and read its coverage.** The coverage gate is bound to `verify`, which is the
  phase a Failsafe run has to reach, so `-Dit.test=…` or `-Dtest=…` measures a fraction of the suite
  against the whole module's floor and fails for a reason unrelated to the tests being run. Add
  `-Pscoped-tests` for a diagnostic run; the profile relaxes only the two coverage properties and is
  reachable only by name, so the unscoped build above is unaffected. A green scoped run is not a green
  build — the figures on this page come only from a full `verify`. See DL-206.
- **The Compose database is a developer convenience, not the seed baseline.** Its schema does not
  drift and Flyway `validate` against it is meaningful, but its data does drift as soon as anything is
  exercised against it. Anything asserting seeded content migrates a fresh container, which is what
  the integration suite does. Amending a seed migration changes its checksum, at which point the
  shared server fails `validate` until `docker compose stop postgres && docker compose rm -f postgres
  && docker volume rm carddemo_postgres-data && docker compose up -d postgres` recreates it — that is
  the mechanism working, and it is the correct response rather than a Flyway repair. See DL-205.

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

### The bound a golden fixture is generated at, per record layout

A fixture author needs one thing this page can state and nothing else can: whether a given layout's
round trip is asserted over its **whole record image** or only over its **mapped data prefix**. The
answer is a property of the layout, not a choice, because `FILLER` with no `VALUE` clause is
uninitialised and the two halves of the shipped sample data disagree about what it holds — the four
master files carry space filler, the four reference-table files carry ASCII zero. **D-10 in
`decision-log.md` settles it and carries the full table and the reasoning**; the operative summary is:

- **Whole record, byte-identical:** account 300 B, card 150 B, customer 500 B, daily transaction 350 B,
  and the cross reference's 36-byte data record. Verified at 50 / 50 / 50 / 300 / 50 records.
- **Mapped prefix only:** transaction category balance `[0, 28)`, disclosure group `[0, 22)`,
  transaction type `[0, 52)`, transaction category `[0, 56)`. Their fixtures hold ASCII zero in the
  filler run and the writers emit spaces, so a whole-record comparison against them fails **on the
  filler bytes alone** — which is why each of the four mappers declares its emitted filler character
  as a named constant, states the bound in its Javadoc, and has that bound asserted in both
  directions rather than trimmed away.

**None of the four bounded layouts is one of the four gated output formats above.** The 430-, 80-,
100- and 133-byte records are assembled from mapped fields by `RejectRecordWriter`,
`StatementTextTemplates`, `StatementHtmlTemplates` and `ReportLineFormatter`, none of which places a
reference-layout filler byte. The one production path that emits a bounded layout's image at all is
the category-balance report job, whose 50-byte unload and sort records are internal intermediates of
that job — what it publishes is the report line, built from the mapped prefix. So no gated artefact
carries a filler byte, and a fixture generated at the bounds above cannot disagree with one.

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

### Credential-literal audit — the legacy sign-on value

| | |
| --- | --- |
| **Requirement** | The eight-character cleartext credential the legacy provisioning member carries for all ten sign-on identities must not be a stored or compared value anywhere in the module. |
| **Command** | The scan below, over `carddemo-java/src/main/java`, `carddemo-java/src/main/resources`, `carddemo-java/src/test`, `Dockerfile`, `docker-compose.yml`, `pom.xml`, both `README.md` files, `localstack/`, `config/`, `.github/` and `docs/` |
| **Evidence artefact** | This subsection |
| **Standing result** | **No column stores it and no code path compares it.** Every stored credential is a BCrypt digest, and verification goes through the encoder rather than an equality test. |

The value is recovered at run time, by offset, from the read-only reference tree — the credential window
of the ten fixed-width card images the provisioning member supplies in stream — and is never printed by
the scan:

```bash
cd "$(git rev-parse --show-toplevel)"
python3 - <<'PY'
import pathlib
cards = pathlib.Path('app/jcl/DUSRSECJ.jcl').read_text().splitlines()[34:44]
window = {c[48:56] for c in cards}          # the credential window, 1-based columns 49-56
assert len(window) == 1                     # all ten records share one value
secret = window.pop()
roots = ['carddemo-java/src/main/java', 'carddemo-java/src/main/resources', 'carddemo-java/src/test',
         'carddemo-java/docker-compose.yml', 'carddemo-java/pom.xml', 'carddemo-java/README.md',
         'carddemo-java/localstack', 'carddemo-java/config', 'README.md', 'docs', '.github']
for r in roots:
    p = pathlib.Path(r)
    for q in ([p] if p.is_file() else [f for f in p.rglob('*') if f.is_file()]):
        n = q.read_text(errors='replace').count(secret)
        if n:
            print(f'{n:4d}  {q}')            # the path and the count only, never the value
PY
```

**Why a raw hit count is the wrong measure here, and what was measured instead.** The legacy value is an
ordinary eight-letter English word. It is therefore also a substring of the framework's own vocabulary and
of this project's own identifiers — `spring.datasource.password`, `server.ssl.key-store-password`,
`POSTGRES_PASSWORD`, `CARDDEMO_DB_PASSWORD`, `GRAFANA_ADMIN_PASSWORD`, `FIELD_PASSWORD`,
`PASSWORD_LENGTH`, `PASSWORD_HASHING_STRENGTH`, and the sign-on decision constants `PASSWORD_MISSING`
and `WRONG_PASSWORD`. A case-insensitive scan returns well over a hundred such matches and none of them
is a credential. Each exact-case hit was therefore classified by hand, and every one falls into one of
four categories:

| Category | What it is | Why it is not an exposure |
| --- | --- | --- |
| Setting and variable names | `POSTGRES_PASSWORD`, `CARDDEMO_DB_PASSWORD`, `CARDDEMO_TLS_KEYSTORE_PASSWORD`, `spring.datasource.password` | A name, not a value. Production binds each to a bare environment reference with no fallback, which is what `application-prod.yml` and `ProductionConfigurationValidator` enforce. |
| Constant and enum names | `FIELD_PASSWORD`, `PASSWORD_LENGTH`, `PASSWORD_HASHING_STRENGTH`, `Decision.PASSWORD_MISSING`, `Decision.WRONG_PASSWORD` | A name, not a value. Removing the word would obscure the field and decision each identifies. |
| Negative assertions | The eight domain `*SecurityTest` classes, `GlobalExceptionHandlerTest`, `OpenApiConfigBaselineTest`, `ConfigurationProfileBaselineTest`, `SeedMigrationIT` | These hold the literal in order to assert it is **absent** from a rendering, a published interface description, a configuration file or a migrated row. Removing it would delete the assertion that protects the value. |
| Sign-on and administration input fixtures | `AuthenticationServiceTest`, `AuthControllerTest`, `UserCommandTest`, `UserContractAdapterTest`, `AdminUserControllerTest`, the `UserRequest`/`UserResponse` tests, the two `UserSecurityRecordMapper` tests | A submitted value on the way in, which is what a sign-on test must submit. None of them stores it: the digest service hashes before the persistence boundary, and the entity refuses any value that is not digest-shaped. |

**What the audit found in no category at all.** No `.sql` migration carries it — `V4__seed_user_security.sql`
inserts ten independently salted 60-character digests and no cleartext. No configuration file carries it as
a value. No production class carries it as a value. And no repository publishes a finder that could match on
it: a salted digest cannot be compared by equality, so `findBySecUsrIdAndSecUsrPwd`,
`existsBySecUsrIdAndSecUsrPwd` and `findBySecUsrPwd` do not exist and cannot be added by convention, which
`repository/UserSecurityRepositoryIT.java` asserts through its frozen-contract nest.

Two documentation files mention the value in prose — the repository README, which documents how to sign on
to the legacy mainframe estate, and `docs/project-guide.md`, which shows a sign-on request. Both describe a
sample login of a demonstration application whose ten identities are published in the upstream project, and
neither is a stored secret of this module.

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

### The three alternate-index equivalents, and where the third one's predicate lives

The `File I/O` row above names three alternate indexes. Two of them are reached by a repository finder
and one is not, which is a design decision rather than an omission and is worth naming here because a
reader auditing the row will look for three finders and find two:

| Alternate index | B-tree equivalent in `V2` | Reached by |
| --- | --- | --- |
| `CARDAIX` on `CARD-ACCT-ID` | `idx_card_card_acct_id` | `CardRepository.findByCardAcctId` |
| `CXACAIX` on `XREF-ACCT-ID` | `idx_card_cross_reference_xref_acct_id` | `CardCrossReferenceRepository.findByXrefAcctId` |
| The `TRANSACT` timestamp index on `TRAN-PROC-TS` | `idx_transaction_tran_proc_ts` | **no repository finder** — the reporting window is applied over the unloaded sequential file, which is where the legacy `INCLUDE COND` applied it. DL-199. |

The index is nevertheless created, is a non-unique B-tree, and is demonstrably indexable: on a
purpose-built selective set of 3,000 posted rows spread across 100 processing dates and `ANALYZE`d, the
window's **bare lower bound is taken as an index condition** and the ten-character **prefix upper bound
is applied as a filter** — `Bitmap Index Scan on idx_transaction_tran_proc_ts, Index Cond:
((tran_proc_ts)::text >= …), Filter: (SUBSTRING(tran_proc_ts FROM 1 FOR 10) <= …)`, then a sort on
`tran_card_num` alone. Whether the planner picks a plain or a bitmap index scan is a cost decision that
varies with the selectivity of the window; both take the index. The shape is what matters, and it is
the reason the upper bound is a prefix rather than a whole-column comparison: **a whole-column upper
bound silently drops an end-date row that carries a time** — reproduced deliberately, 31 rows selected
with the prefix bound against 0 with the naive one — and it is also why the lower bound is left bare,
since wrapping it would make the predicate unindexable. The twenty-six-blank "not yet processed"
sentinel is excluded by the lower bound alone. If a repository-level date-range finder is ever added
for the report job, it must keep exactly this shape.

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
