# blitzy-card-demo

AWS CardDemo COBOL mainframe application migrated to Java 25 + Spring Boot 3.x

This site is the documentation and evidence set for that migration. The Java delivery is a self-contained
Maven module at `carddemo-java/`.

**No legacy source text is reproduced in that module for traceability purposes** — not a program, not a
paragraph, not a statement, and not in a comment. Correspondence with the legacy estate is carried **by
citation**: member names, paragraph names, line numbers, record widths, byte offsets and picture-clause
shapes are interface metadata and appear freely, while source lines do not appear at all. Every citation is
anchored on checkout SHA `7756d895ffeb65f7ea72aaa609e356d9899afcec` and the upstream release stamp
`CardDemo_v1.0-15-g27d6c6f-68` (2022-07-19).

One category of legacy text does appear in the module, and naming it is what makes the certification above
worth reading: where a preservation requirement obliges the module to *emit* legacy text byte for byte, that
text is present as **contract data** rather than as a transcription — the seventeen fixed 80-column
job-submission card images and their sentinel, the 80- and 100-byte statement template literals, and the
seven sign-on message texts. Emitting them is what "external interfaces maintain identical contracts"
requires, and the card images are verified by draining them back out of a real queue.

The legacy estate under `app/` is unchanged and is read as reference only: it is simultaneously the parity
baseline every golden fixture derives from and the anchor every traceability row cites, so it must stay
byte-identical. These pages sit at the repository root rather than inside the module because MkDocs resolves
its implicit `docs_dir` at `docs/` and Backstage TechDocs publishes from the repository root (`dir:.`).

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
  validation gates, including the unsafe-code audit counts and the measured performance baseline. It is the
  single authority for what any given run recorded.
- [Migration Summary Deck](presentation/index.html) — a static HTML deck, six slides: what was migrated,
  what each construct became, the shape of the target module, the load-bearing translation decisions, the
  **acceptance-criteria model** — what each of the eight gates verifies and the obligation it discharges —
  and where the detail lives. It deliberately carries no gate results, deferring every run outcome to Gate
  Evidence above.

## Also in this site, and not linked from here on purpose

Two further pages are published in the navigation and are deliberately **not** linked from this page:
`project-guide.md`, the prior run's completion report, and `technical-specifications.md`, the prior run's
plan for this same migration. Both are superseded by the six pages above and neither describes what the
module does today, so a landing page that offered them beside current evidence would invite a reader to
quote a stale figure. Reach them from the navigation when you want to know how an earlier decision was
arrived at.

The module's own `carddemo-java/README.md` is likewise reached from the repository rather than from here: it
is a file inside the delivered module, not a page of this site, and this page links only what this site
publishes.
