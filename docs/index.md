# blitzy-card-demo

AWS CardDemo COBOL mainframe application migrated to Java 25 + Spring Boot 3.x

This site is the documentation and evidence set for that migration. The Java delivery is a self-contained
Maven module at `carddemo-java/`, and no COBOL, JCL, BMS, copybook or CSD source is copied into it, so
traceability back to the legacy estate is by citation rather than by transcription — every citation is
anchored on checkout SHA `7756d895ffeb65f7ea72aaa609e356d9899afcec` and the upstream release stamp
`CardDemo_v1.0-15-g27d6c6f-68` (2022-07-19). The legacy estate under `app/` is unchanged and is read as
reference only: it is simultaneously the parity baseline every golden fixture derives from and the anchor
every traceability row cites, so it must stay byte-identical. These pages sit at the repository root
rather than inside the module because MkDocs resolves its implicit `docs_dir` at `docs/` and Backstage
TechDocs publishes from the repository root (`dir:.`).

## Start here

- [Onboarding Guide](onboarding-guide.md) — the first-run walkthrough: prerequisites, the build commands
  and what each one proves, bringing up the local stack, signing on, and running the eight gates.
- [Architecture](architecture.md) — the layer map, the package responsibilities, the entity model, and why
  the batch tier is shaped the way it is.

## Migration evidence

- [Traceability Matrix](traceability-matrix.md) — 544 rows, one per procedure unit (528 program paragraphs
  plus 14 and 2 from the two procedural copybooks), each naming its source member, source line, target
  class, target method and covering test.
- [Migration Decision Log](decision-log.md) — every judgement where faithful COBOL semantics and
  idiomatic Java diverged, plus the source-anomaly register.
- [Gate Evidence](gate-evidence.md) — the commands, artefacts and standing results for all eight
  validation gates, including the unsafe-code audit counts and the measured performance baseline.
- [Migration Summary Deck](presentation/index.html) — a static HTML deck covering the estate, the
  mapping, the load-bearing translation decisions and the gate outcomes, on six slides.

## Background

Prior-run reference material, kept for continuity and superseded by the pages above. Neither is current:
read them for how earlier decisions were reached, not for what the module does today.

- [Project Guide](project-guide.md) — the prior run's completion report.
- [Technical Specifications](technical-specifications.md) — the prior run's plan for this same migration.

## Repository

- [Java module README](../carddemo-java/README.md) — the module's own build, run and local validation
  instructions, in full detail.
- [Repository README](../README.md) — the legacy estate and the Java module, described side by side.
