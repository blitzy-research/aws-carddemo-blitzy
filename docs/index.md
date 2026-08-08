# blitzy-card-demo

AWS CardDemo COBOL mainframe application migrated to Java 25 + Spring Boot 3.x

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
  idiomatic Java diverged, and the anomaly register for the fourteen defects found in the legacy source.
- [Gate Evidence](gate-evidence.md) — the commands, artefacts and standing results for all eight
  validation gates, including the unsafe-code audit counts and the measured performance baseline.
- [Migration Summary Deck](presentation/index.html) — the estate, the mapping, the load-bearing
  translation decisions and the gate outcomes, on six slides.

## Background

- [Project Guide](project-guide.md) — what was delivered and how it is organised.
- [Technical Specifications](technical-specifications.md) — the agreed plan the migration was executed
  against.

The Java module itself lives under `carddemo-java/`; its README carries the build, run and local
validation instructions in full detail. The legacy estate under `app/` is unchanged and is read as
reference only — it is simultaneously the parity baseline every golden fixture derives from and the anchor
every traceability row cites, so it must stay byte-identical.
