# blitzy-card-demo

AWS CardDemo COBOL mainframe application migrated to Java 25 + Spring Boot 3.x

## Migration documentation

- [Project Guide](project-guide.md) — what was delivered and how it is organised.
- [Technical Specifications](technical-specifications.md) — the agreed plan the migration was executed
  against.
- [Migration Decision Log](decision-log.md) — every judgement where faithful COBOL semantics and
  idiomatic Java diverged, and the anomaly register for the fourteen defects found in the legacy source.
- [Gate Evidence](gate-evidence.md) — the commands, artefacts and standing results for all eight
  validation gates, including the unsafe-code audit counts and the performance-baseline mechanism.

The Java module itself lives under `carddemo-java/`; its README carries the build, run and local
validation instructions. The legacy estate under `app/` is unchanged and is read as reference only.
