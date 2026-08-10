# Traceability Matrix

Every procedure unit in the AWS CardDemo mainframe estate, mapped to the Java class, the Java method
and the test that carries it. The page exists so that a reviewer can start from any COBOL paragraph and
reach the code that answers for it, or start from any Java method and reach the paragraph it replaced,
without reading a line of COBOL.

Together with [decision-log.md](decision-log.md) this page is the migration's audit trail. The
decision log records *why* a translation diverged where it diverged; this page records *where every
unit went*. Neither is discharged anywhere else in the repository.

## Provenance

| Anchor | Value |
| :--- | :--- |
| Checkout commit SHA | `7756d895ffeb65f7ea72aaa609e356d9899afcec` |
| Upstream release stamp | `CardDemo_v1.0-15-g27d6c6f-68`, dated 2022-07-19 |
| Where that stamp is carried | the trailer comment of **78** legacy members under `app/` |
| Git tags in this repository | **none** — which is why the commit SHA, and not a tag, is the anchor |
| Legacy tree status | read-only and byte-identical: nothing under `app/` is edited, moved, deleted or copied |

Traceability here is **by citation, never by copying**. The legacy estate stays exactly where it is and
exactly as it is, and this page points at it by member name, paragraph name and line number.

## Row count and its composition

The matrix carries **exactly 544 data rows**, one per procedure unit:

| Contribution | Rows |
| :--- | ---: |
| Paragraphs in the procedure divisions of the 28 COBOL programs | 528 |
| Paragraphs supplied by the procedural copybook `CSUTLDPY.cpy` | 14 |
| Paragraphs supplied by the procedural copybook `CSSTRPFY.cpy` | 2 |
| **Total** | **544** |

**528 + 14 + 2 = 544.** The count is a gate condition rather than a statistic: `GateVerificationTest`
asserts it, so a matrix of 543 or 545 rows fails the integration sign-off. Every row was produced by a
mechanical extraction over the checkout named above, and the per-member subtotals published in
[Census by member](#census-by-member) are what let a reviewer confirm the total without counting rows.

The two procedural copybooks are counted as units in their own right because they contribute
*executable paragraphs* to the members that include them — `CSUTLDPY.cpy` to the account-update member
alone and `CSSTRPFY.cpy` to five online members. Counted only against their including members, those
sixteen paragraphs would be invisible to the audit, and the 100% paragraph coverage the sign-off
requires could not be demonstrated.

## No COBOL source text appears on this page

Member names, paragraph names, source line numbers and per-member counts are **metadata**, and they are
required — a citation without them is not checkable. Everything else is withheld. **No source line, no
statement body, no comment text and no code excerpt is transcribed anywhere on this page**, including
from the paragraphs marked below as non-implementations. Where a row needs explanation it is explained
in prose written here.

The page lives beside its sibling documents in the documentation directory and is reached as
`traceability-matrix.md`. No matrix file exists at the repository root; an earlier reference to one is
superseded by this page.

## How to read a row

Every one of the 544 rows carries the same seven cells: the six the sign-off requires, plus a marker.

| Column | What it holds |
| :--- | :--- |
| Source member | The path of the legacy member, so the citation is unambiguous |
| Paragraph | The label **exactly as the source spells it**, misspellings included |
| Line | The 1-based source line of the label |
| Target class | The Java class, named relative to the `com.carddemo` base package |
| Target method | The Java method, named exactly as the module declares it |
| Covering test | The test class that **executes** that method — naming a test that merely mentions the method, or that reaches its enclosing class without reaching it, would not discharge the sign-off. Where a method has no production caller and is therefore reached by its test directly, the member's own section says so. |
| Notes | A marker, or blank when the row is an ordinary translation |

### What the Covering test column claims, and what it does not

This column is the weakest kind of evidence on the page, and saying so is part of making the page usable.
It names **the suite that exercises the paragraph's behaviour**, reached through the public entry point of
the class that owns the method — not a test that calls the named method by name.

That distinction is not a gap in the tests; it follows from the translation itself. A COBOL paragraph is an
*internal* step of a program, so its faithful counterpart is a private method, and **518 of the 544 target
methods are private**. A private method cannot be named in a test without either widening its visibility or
reaching it reflectively, and both were rejected: widening it to suit a test would misrepresent the
paragraph's role, and reflection is budgeted at zero for the whole module. So the entry points appear
literally in test source and the internal steps are reached through them.

What is therefore **mechanically checked** about every row, and fails the build when it is not true:

| Checked | By |
| :--- | :--- |
| The paragraph label exists, at the cited line, in the cited member | `e2e/GateVerificationTest`, reading the legacy member and matching Area A labels |
| The per-member row count equals the labels that member actually declares | the same, member by member — so a row invented for a paragraph that does not exist fails |
| The total is 544, and its three contributions sum to it | the same, as a gate condition rather than a statistic |
| The named target class and covering test class both exist | the module's own compilation, plus the per-member checks in the service suites |
| The three per-member subtotals agree — census table, section declaration and counted rows | `support/TraceabilityMatrixCensus`, which throws when the three disagree |

What is **not** proven by this page, stated plainly: that a *named private method* was dynamically entered
during a *named test*. A row is a mapping claim and a reachability claim; it is not an execution trace, and
reading it as one would overstate it.

The evidence for dynamic execution is the coverage report, which measures what actually ran. **The report
and the command that produced it are one artefact**, and a report offered without its command is not
evidence of anything: the same file name means different things depending on which suites ran.

```bash
cd carddemo-java
./mvnw -B clean verify                      # the full gate: unit, then integration, then the merge

# then open these in a browser. Paths, not a command, because the command differs by platform:
#   target/site/jacoco-merged/index.html    gate evidence - unit and integration combined
#   target/site/jacoco/index.html           the unit tier alone
#   target/site/jacoco-it/index.html        the integration tier alone
#
# On Linux:  xdg-open target/site/jacoco-merged/index.html
# On macOS:  open     target/site/jacoco-merged/index.html
# Headless:  serve the directory, or copy it out - the report is a self-contained static site
```

Two properties of the merged report are what make it load-bearing rather than informational. Line coverage
is enforced at **80% as a build-failing check**, and a second rule holds the number of **wholly untested
classes at zero** — so no target class named on this page can be entirely unexercised without failing the
build.

To settle a single row, run its covering test on its own and read that method's counters; if they are zero,
the row's reachability claim is wrong and belongs in a bug rather than in a document. Selecting a subset
means selecting a tier: `-Dtest=` selects unit classes and `-Dit.test=` selects integration classes, and a
run that names one leaves the other's counters absent rather than zero.

```bash
cd carddemo-java
./mvnw -B -Pscoped-tests test -Dtest=AccountViewServiceTest
# then open target/scoped-site/jacoco/index.html     - the unit tier of that scoped run

./mvnw -B -Pscoped-tests verify -Dit.test=CombineTransactionsJobConfigIT
# then open target/scoped-site/jacoco-it/index.html  - the integration tier of that scoped run
```

Both write beneath `target/scoped-site/`, never over the gate evidence.

**A scoped report is never full-gate evidence, and cannot be mistaken for it.** The `scoped-tests` profile
exists to make a single class runnable — it relaxes the coverage thresholds, because a subset of the suite
cannot reach a whole-module floor — and it therefore writes its execution data and its report under
`target/scoped-*` paths of their own. The canonical `target/jacoco*.exec` files and the
`target/site/jacoco*` reports are reserved for an unscoped `./mvnw -B clean verify`, so a diagnostic run
can no longer leave a relaxed report sitting at the path the gate evidence is read from; the reasoning is
[decision-log.md](decision-log.md) entry `DL-284`. Any coverage
figure quoted as gate evidence must therefore be recorded together with its provenance: the exact command,
the date it was run, which suites it ran, and which report directory it was read from. A figure without
those four is a number, not evidence — `docs/gate-evidence.md` is where they are recorded.

### Marker legend

| Marker | Meaning |
| :-: | :--- |
| *(blank)* | An ordinary translation. 506 of the 544 rows. |
| `†` | **Documented non-implementation.** The paragraph is invoked and implements nothing; the Java method exists, is called, and does nothing. 1 row. |
| `‡` | **Source anomaly.** A duplicated label or a misspelled label, preserved as found rather than corrected. 3 rows. |
| `§` | **Unwired member.** The program is complete but no job stream invokes it; its job is defined and exercised by tests, and excluded from the default pipeline. 18 rows. |
| `¶` | **Deliberately unwired paragraph.** The paragraph is translated and its method exists, but no delivered call site reaches it — by decision, recorded on the method itself. The row's covering test therefore exercises the method **directly**, by name, through package access rather than through the driver. 16 rows. |

1 + 3 + 18 + 16 + 506 = 544. Each marked row is cross-referenced to its entry in the source anomaly
register of [decision-log.md](decision-log.md), which is the authority for the reasoning; this page
links rather than restates it.

### Why the unwired-paragraph marker exists, and what it obliges

A covering test that cannot reach the method it is named against is a false entry, and a row-count check
cannot see one. Sixteen rows are in that position, across four members. The population is a decision
rather than a measurement, so it is enumerated here in full:

| Member | Rows | The paragraphs, and why no delivered call site reaches them |
| :--- | :-: | :--- |
| `COACTUPC` | 6 | The three edits the delivered driver never routes to — `1230-EDIT-ALPHANUM-REQD`, `1235-EDIT-ALPHA-OPT` and `1240-EDIT-ALPHANUM-OPT` — and their three paired exits. Two of the three heads have no call site in the legacy member either; the third does, at source lines 1568 to 1574 for the middle name, and the migration directive forbids attaching a constraint to that field, so the delivered driver must not route to it. |
| `COACTVWC` | 2 | `SEND-LONG-TEXT` at 896 and its exit at 907. All three statements that would have performed it — at 768, 818 and 867 — are commented out in the member, as is the statement that would have filled the field it transmits. Its plain-text sibling at 877 *is* performed and is therefore an ordinary row. |
| `COCRDLIC` | 4 | `SEND-PLAIN-TEXT` at 1422 and `SEND-LONG-TEXT` at 1441, with their exits at 1433 and 1452. The member's own comments at 1420 and at 1438 to 1439 say both are diagnostic and not for production use, and no performed or jumped-to label reaches either. |
| `COCRDSLC` | 4 | `9150-GETCARD-BYACCT` at 779 and its exit at 810 — the account-keyed read over the non-unique alternate index, which the read driver at 726 does not perform — and `SEND-LONG-TEXT` at 820 with its exit at 831. As in the account-view member, the plain-text sender at 838 is performed and is an ordinary row. |

Every one of those decisions is recorded on the Java method itself, which is the authority; this page
records the marker and the count, and [decision-log.md](decision-log.md) entry `DL-283` records why the
population is sixteen across four members rather than six in one.

The obligation the marker carries is specific and mechanically checked. Each row's covering test declares
the method by name, and calls each head directly — `AccountUpdateServiceTest.DeliberatelyUnwiredEdits`,
`AccountViewServiceTest`, `CardListServiceTest.DiagnosticSenders` and
`CardDetailServiceTest.AccountKeyedRead` — reaching them through ordinary package access rather than
reflectively, because the production tree is held to a reflection count of zero. Calling a head executes
its paired exit on every arm, which is why no exit carries a call of its own: an invocation of an empty
terminator would be fabricated coverage, so the obligation on an exit row is that its covering test names
it. `e2e.GateVerificationTest` asserts every half: that each row resolves to a class that exists and
declares the named method, that each `¶` row's covering test names that method, that each head is
actually invoked by it, and that the sixteen rows fall in exactly the per-member distribution above —
the checks a row count cannot make.

### Rows that share a target method

Several rows point at the same method, and that is correct rather than a loss of fidelity. Two
situations produce it. A paragraph and its paired terminator sometimes collapse into one method with an
early return — 142 of the estate's 158 `PERFORM … THRU …` ranges span at most one intermediate label,
which is exactly that idiom. And one Java class sometimes owns several members whose helper paragraphs
are genuinely identical, as with the four sequential readers and the four user-maintenance screens.
When it happens, every source row is still listed; none is dropped to make the mapping look tidier.

## Deliberate exclusions

**`app/cpy/UNUSED1Y.cpy` contributes no paragraphs and therefore appears in no row.** It is a data
copybook with **zero inclusion references anywhere in the estate**, measured across every program,
copybook, mapset, job member and resource definition under `app/`. It is deliberately not migrated,
because migrating it would create Java code that nothing could ever reach. It is recorded as row 13 of
the source anomaly register in [decision-log.md](decision-log.md) as **consciously excluded dead code —
a decision, not an omission from this matrix**. Adding a row for it would make the count 545 and fail
the sign-off.

Two further points of scope, so the audit trail is complete. Of the 28 copybooks in the estate only the
two procedural ones declare an Area A paragraph label at all; the other 26 — `UNUSED1Y.cpy` among them —
declare none, verified by applying the same detection to every member of that directory, so they
contribute no rows here. Their field-level mapping belongs to the entity and mapper classes and is
described in [architecture.md](architecture.md). And the job members that only toggled file
availability or drove a catalog utility have no runtime equivalent once the indexed files are replaced;
they are recorded as intentionally unmigrated in [decision-log.md](decision-log.md) rather than
dropped.

## Census by member

Thirty members contribute rows. The subtotal beside each is what the extraction measured, and the
per-member tables below reproduce it exactly.

| Member | Paragraphs | Primary target class | Primary covering test |
| :--- | ---: | :--- | :--- |
| `CBACT01C.cbl` | 6 | `service.FileMaintenanceService` | `service.FileMaintenanceServiceTest` |
| `CBACT02C.cbl` | 5 | `service.FileMaintenanceService` | `service.FileMaintenanceServiceTest` |
| `CBACT03C.cbl` | 5 | `service.FileMaintenanceService` | `service.FileMaintenanceServiceTest` |
| `CBACT04C.cbl` | 22 | `service.InterestCalculationService` | `service.InterestCalculationServiceTest` |
| `CBCUS01C.cbl` | 5 | `service.FileMaintenanceService` | `service.FileMaintenanceServiceTest` |
| `CBSTM03A.CBL` | 25 | `service.StatementGenerationService` | `service.StatementGenerationServiceTest` |
| `CBSTM03B.CBL` | 14 | `service.StatementDataAccessService` | `service.StatementDataAccessServiceTest` |
| `CBTRN01C.cbl` | 18 | `service.DailyTransactionReadService` | `service.DailyTransactionReadServiceTest` |
| `CBTRN02C.cbl` | 26 | `service.TransactionPostingService` | `service.TransactionPostingServiceTest` |
| `CBTRN03C.cbl` | 26 | `service.TransactionReportService` | `service.TransactionReportServiceTest` |
| `COACTUPC.cbl` | 85 | `service.AccountUpdateService` | `service.AccountUpdateServiceTest` |
| `COACTVWC.cbl` | 35 | `service.AccountViewService` | `service.AccountViewServiceTest` |
| `COADM01C.cbl` | 7 | `service.MenuService` | `service.MenuServiceTest` |
| `COBIL00C.cbl` | 16 | `service.BillPaymentService` | `service.BillPaymentServiceTest` |
| `COCRDLIC.cbl` | 39 | `service.CardListService` | `service.CardListServiceTest` |
| `COCRDSLC.cbl` | 34 | `service.CardDetailService` | `service.CardDetailServiceTest` |
| `COCRDUPC.cbl` | 45 | `service.CardUpdateService` | `service.CardUpdateServiceTest` |
| `COMEN01C.cbl` | 7 | `service.MenuService` | `service.MenuServiceTest` |
| `CORPT00C.cbl` | 10 | `service.ReportRequestService` | `service.ReportRequestServiceTest` |
| `COSGN00C.cbl` | 6 | `service.AuthenticationService` | `service.AuthenticationServiceTest` |
| `COTRN00C.cbl` | 16 | `service.TransactionListService` | `service.TransactionListServiceTest` |
| `COTRN01C.cbl` | 9 | `service.TransactionViewService` | `service.TransactionViewServiceTest` |
| `COTRN02C.cbl` | 18 | `service.TransactionAddService` | `service.TransactionAddServiceTest` |
| `COUSR00C.cbl` | 16 | `service.UserManagementService` | `service.UserManagementServiceTest` |
| `COUSR01C.cbl` | 9 | `service.UserManagementService` | `service.UserManagementServiceTest` |
| `COUSR02C.cbl` | 11 | `service.UserManagementService` | `service.UserManagementServiceTest` |
| `COUSR03C.cbl` | 11 | `service.UserManagementService` | `service.UserManagementServiceTest` |
| `CSUTLDTC.cbl` | 2 | `service.DateValidationService` | `service.DateValidationServiceTest` |
| `CSUTLDPY.cpy` | 14 | `service.DateValidationService` | `service.DateValidationServiceTest` |
| `CSSTRPFY.cpy` | 2 | `util.PfKeyTranslator` | `util.PfKeyTranslatorTest` |
| **Total** | **544** | 22 distinct classes | 22 distinct test classes |

The 28 programs contribute **528** and the two procedural copybooks contribute **14 + 2 = 16**, so
**528 + 14 + 2 = 544**.

The distribution is heavily skewed, which is useful orientation before reading further. `COACTUPC`
alone contributes **85** units — **16.1%** of the 528 program paragraphs and the largest single
translation in the estate — followed by `COCRDUPC` 45, `COCRDLIC` 39, `COACTVWC` 35, `COCRDSLC` 34,
`CBTRN02C` 26, `CBTRN03C` 26, `CBSTM03A` 25, `CBACT04C` 22, `CBTRN01C` 18, `COTRN02C` 18 and
`CBSTM03B` 14. The remaining eighteen members contribute eleven units or fewer each.

## CBACT01C

**6 paragraph units.** Legacy authority `app/cbl/CBACT01C.cbl`, 193 lines. Sequential read and print
of the account master.

Primary target class `service.FileMaintenanceService`, primary covering test
`service.FileMaintenanceServiceTest`. Also exercised by: `batch.FileProbeJobConfigIT`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/CBACT01C.cbl | `1000-ACCTFILE-GET-NEXT` | 92 | `service.FileMaintenanceService` | `acctFileGetNext` | `service.FileMaintenanceServiceTest` |  |
| app/cbl/CBACT01C.cbl | `1100-DISPLAY-ACCT-RECORD` | 118 | `service.FileMaintenanceService` | `displayAcctRecord` | `service.FileMaintenanceServiceTest` |  |
| app/cbl/CBACT01C.cbl | `0000-ACCTFILE-OPEN` | 133 | `service.FileMaintenanceService` | `openAcctFile` | `service.FileMaintenanceServiceTest` |  |
| app/cbl/CBACT01C.cbl | `9000-ACCTFILE-CLOSE` | 151 | `service.FileMaintenanceService` | `closeAcctFile` | `service.FileMaintenanceServiceTest` |  |
| app/cbl/CBACT01C.cbl | `9999-ABEND-PROGRAM` | 169 | `service.FileMaintenanceService` | `abendProgram` | `service.FileMaintenanceServiceTest` |  |
| app/cbl/CBACT01C.cbl | `9910-DISPLAY-IO-STATUS` | 176 | `service.FileMaintenanceService` | `displayIoStatus` | `service.FileMaintenanceServiceTest` |  |

## CBACT02C

**5 paragraph units.** Legacy authority `app/cbl/CBACT02C.cbl`, 178 lines. Sequential read and print
of the card master.

Primary target class `service.FileMaintenanceService`, primary covering test
`service.FileMaintenanceServiceTest`. Also exercised by: `batch.FileProbeJobConfigIT`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/CBACT02C.cbl | `1000-CARDFILE-GET-NEXT` | 92 | `service.FileMaintenanceService` | `cardFileGetNext` | `service.FileMaintenanceServiceTest` |  |
| app/cbl/CBACT02C.cbl | `0000-CARDFILE-OPEN` | 118 | `service.FileMaintenanceService` | `openCardFile` | `service.FileMaintenanceServiceTest` |  |
| app/cbl/CBACT02C.cbl | `9000-CARDFILE-CLOSE` | 136 | `service.FileMaintenanceService` | `closeCardFile` | `service.FileMaintenanceServiceTest` |  |
| app/cbl/CBACT02C.cbl | `9999-ABEND-PROGRAM` | 154 | `service.FileMaintenanceService` | `abendProgram` | `service.FileMaintenanceServiceTest` |  |
| app/cbl/CBACT02C.cbl | `9910-DISPLAY-IO-STATUS` | 161 | `service.FileMaintenanceService` | `displayIoStatus` | `service.FileMaintenanceServiceTest` |  |

## CBACT03C

**5 paragraph units.** Legacy authority `app/cbl/CBACT03C.cbl`, 178 lines. Sequential read and print
of the card cross-reference file; the job that drives it is a category-balance listing.

Primary target class `service.FileMaintenanceService`, primary covering test
`service.FileMaintenanceServiceTest`. Also exercised by: `batch.CategoryBalanceReportJobConfigIT`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/CBACT03C.cbl | `1000-XREFFILE-GET-NEXT` | 92 | `service.FileMaintenanceService` | `xrefFileGetNext` | `service.FileMaintenanceServiceTest` |  |
| app/cbl/CBACT03C.cbl | `0000-XREFFILE-OPEN` | 118 | `service.FileMaintenanceService` | `openXrefFile` | `service.FileMaintenanceServiceTest` |  |
| app/cbl/CBACT03C.cbl | `9000-XREFFILE-CLOSE` | 136 | `service.FileMaintenanceService` | `closeXrefFile` | `service.FileMaintenanceServiceTest` |  |
| app/cbl/CBACT03C.cbl | `9999-ABEND-PROGRAM` | 154 | `service.FileMaintenanceService` | `abendProgram` | `service.FileMaintenanceServiceTest` |  |
| app/cbl/CBACT03C.cbl | `9910-DISPLAY-IO-STATUS` | 161 | `service.FileMaintenanceService` | `displayIoStatus` | `service.FileMaintenanceServiceTest` |  |

## CBACT04C

**22 paragraph units.** Legacy authority `app/cbl/CBACT04C.cbl`, 652 lines. Interest accrual driven
by the disclosure-group rate lookup.

Primary target class `service.InterestCalculationService`, primary covering test
`service.InterestCalculationServiceTest`. Also exercised by:
`batch.step.InterestCalculationProcessorTest`, `batch.InterestCalculationJobConfigIT`,
`e2e.BatchPipelineE2ETest`.

The fee paragraph at line 518 is marked `†`. It is invoked and implements nothing; the Java method
exists, is called, and does nothing. **No fee logic may be invented for it** — that would be feature
expansion and would change what an interest run emits. Recorded as row 14 of the source anomaly
register in [decision-log.md](decision-log.md).

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/CBACT04C.cbl | `0000-TCATBALF-OPEN` | 234 | `service.InterestCalculationService` | `tcatbalfOpen` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `0100-XREFFILE-OPEN` | 252 | `service.InterestCalculationService` | `xreffileOpen` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `0200-DISCGRP-OPEN` | 270 | `service.InterestCalculationService` | `discgrpOpen` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `0300-ACCTFILE-OPEN` | 289 | `service.InterestCalculationService` | `acctfileOpen` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `0400-TRANFILE-OPEN` | 307 | `service.InterestCalculationService` | `tranfileOpen` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `1000-TCATBALF-GET-NEXT` | 325 | `service.InterestCalculationService` | `tcatbalfGetNext` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `1050-UPDATE-ACCOUNT` | 350 | `service.InterestCalculationService` | `updateAccount` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `1100-GET-ACCT-DATA` | 372 | `service.InterestCalculationService` | `getAcctData` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `1110-GET-XREF-DATA` | 393 | `service.InterestCalculationService` | `getXrefData` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `1200-GET-INTEREST-RATE` | 415 | `service.InterestCalculationService` | `getInterestRate` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `1200-A-GET-DEFAULT-INT-RATE` | 443 | `service.InterestCalculationService` | `getDefaultIntRate` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `1300-COMPUTE-INTEREST` | 462 | `service.InterestCalculationService` | `computeInterest` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `1300-B-WRITE-TX` | 473 | `service.InterestCalculationService` | `writeTx` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `1400-COMPUTE-FEES` | 518 | `service.InterestCalculationService` | `computeFees` | `service.InterestCalculationServiceTest` | † |
| app/cbl/CBACT04C.cbl | `9000-TCATBALF-CLOSE` | 522 | `service.InterestCalculationService` | `tcatbalfClose` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `9100-XREFFILE-CLOSE` | 541 | `service.InterestCalculationService` | `xreffileClose` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `9200-DISCGRP-CLOSE` | 559 | `service.InterestCalculationService` | `discgrpClose` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `9300-ACCTFILE-CLOSE` | 577 | `service.InterestCalculationService` | `acctfileClose` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `9400-TRANFILE-CLOSE` | 595 | `service.InterestCalculationService` | `tranfileClose` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `Z-GET-DB2-FORMAT-TIMESTAMP` | 613 | `service.InterestCalculationService` | `zGetDb2FormatTimestamp` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `9999-ABEND-PROGRAM` | 628 | `service.InterestCalculationService` | `abendProgram` | `service.InterestCalculationServiceTest` |  |
| app/cbl/CBACT04C.cbl | `9910-DISPLAY-IO-STATUS` | 635 | `service.InterestCalculationService` | `displayIoStatus` | `service.InterestCalculationServiceTest` |  |

## CBCUS01C

**5 paragraph units.** Legacy authority `app/cbl/CBCUS01C.cbl`, 178 lines. Sequential read and print
of the customer master.

Primary target class `service.FileMaintenanceService`, primary covering test
`service.FileMaintenanceServiceTest`. Also exercised by: `batch.FileProbeJobConfigIT`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/CBCUS01C.cbl | `1000-CUSTFILE-GET-NEXT` | 92 | `service.FileMaintenanceService` | `custFileGetNext` | `service.FileMaintenanceServiceTest` |  |
| app/cbl/CBCUS01C.cbl | `0000-CUSTFILE-OPEN` | 118 | `service.FileMaintenanceService` | `openCustFile` | `service.FileMaintenanceServiceTest` |  |
| app/cbl/CBCUS01C.cbl | `9000-CUSTFILE-CLOSE` | 136 | `service.FileMaintenanceService` | `closeCustFile` | `service.FileMaintenanceServiceTest` |  |
| app/cbl/CBCUS01C.cbl | `Z-ABEND-PROGRAM` | 154 | `service.FileMaintenanceService` | `abendProgram` | `service.FileMaintenanceServiceTest` |  |
| app/cbl/CBCUS01C.cbl | `Z-DISPLAY-IO-STATUS` | 161 | `service.FileMaintenanceService` | `displayIoStatus` | `service.FileMaintenanceServiceTest` |  |

## CBSTM03A

**25 paragraph units.** Legacy authority `app/cbl/CBSTM03A.CBL`, 924 lines. Statement generation.
Its dispatcher is a hand-rolled state machine, not a loop.

Primary target class `service.StatementGenerationService`, primary covering test
`service.StatementGenerationServiceTest`. Also exercised by: `batch.step.StatementProcessorTest`,
`batch.CreateStatementJobConfigIT`, `e2e.BatchPipelineE2ETest`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/CBSTM03A.CBL | `0000-START` | 296 | `service.StatementGenerationService` | `startDispatcher` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `1000-MAINLINE` | 316 | `service.StatementGenerationService` | `mainline` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `9999-GOBACK` | 341 | `service.StatementGenerationService` | `goBack` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `1000-XREFFILE-GET-NEXT` | 345 | `service.StatementGenerationService` | `crossReferenceFileGetNext` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `2000-CUSTFILE-GET` | 368 | `service.StatementGenerationService` | `customerFileGet` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `3000-ACCTFILE-GET` | 392 | `service.StatementGenerationService` | `accountFileGet` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `4000-TRNXFILE-GET` | 416 | `service.StatementGenerationService` | `transactionFileGet` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `5000-CREATE-STATEMENT` | 458 | `service.StatementGenerationService` | `createStatement` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `5100-WRITE-HTML-HEADER` | 506 | `service.StatementGenerationService` | `writeHtmlHeader` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `5100-EXIT` | 554 | `service.StatementGenerationService` | `writeHtmlHeaderExit` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `5200-WRITE-HTML-NMADBS` | 558 | `service.StatementGenerationService` | `writeHtmlNameAddressBasics` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `5200-EXIT` | 671 | `service.StatementGenerationService` | `writeHtmlNameAddressBasicsExit` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `6000-WRITE-TRANS` | 675 | `service.StatementGenerationService` | `writeTransaction` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `8100-FILE-OPEN` | 726 | `service.StatementGenerationService` | `fileOpen` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `8100-TRNXFILE-OPEN` | 730 | `service.StatementGenerationService` | `transactionFileOpen` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `8200-XREFFILE-OPEN` | 765 | `service.StatementGenerationService` | `crossReferenceFileOpen` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `8300-CUSTFILE-OPEN` | 783 | `service.StatementGenerationService` | `customerFileOpen` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `8400-ACCTFILE-OPEN` | 801 | `service.StatementGenerationService` | `accountFileOpen` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `8500-READTRNX-READ` | 818 | `service.StatementGenerationService` | `readTransactionPhase` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `8599-EXIT` | 849 | `service.StatementGenerationService` | `readTransactionPhaseExit` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `9100-TRNXFILE-CLOSE` | 856 | `service.StatementGenerationService` | `transactionFileClose` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `9200-XREFFILE-CLOSE` | 873 | `service.StatementGenerationService` | `crossReferenceFileClose` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `9300-CUSTFILE-CLOSE` | 889 | `service.StatementGenerationService` | `customerFileClose` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `9400-ACCTFILE-CLOSE` | 905 | `service.StatementGenerationService` | `accountFileClose` | `service.StatementGenerationServiceTest` |  |
| app/cbl/CBSTM03A.CBL | `9999-ABEND-PROGRAM` | 921 | `service.StatementGenerationService` | `abendProgram` | `service.StatementGenerationServiceTest` |  |

## CBSTM03B

**14 paragraph units.** Legacy authority `app/cbl/CBSTM03B.CBL`, 230 lines. The statement helper
subprogram, reached through a shared linkage area.

Primary target class `service.StatementDataAccessService`, primary covering test
`service.StatementDataAccessServiceTest`. Also exercised by: `batch.step.StatementProcessorTest`,
`batch.CreateStatementJobConfigIT`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/CBSTM03B.CBL | `0000-START` | 116 | `service.StatementDataAccessService` | `execute` | `service.StatementDataAccessServiceTest` |  |
| app/cbl/CBSTM03B.CBL | `9999-GOBACK` | 130 | `service.StatementDataAccessService` | `goBack` | `service.StatementDataAccessServiceTest` |  |
| app/cbl/CBSTM03B.CBL | `1000-TRNXFILE-PROC` | 133 | `service.StatementDataAccessService` | `transactionFileProc` | `service.StatementDataAccessServiceTest` |  |
| app/cbl/CBSTM03B.CBL | `1900-EXIT` | 151 | `service.StatementDataAccessService` | `transactionFileExit` | `service.StatementDataAccessServiceTest` |  |
| app/cbl/CBSTM03B.CBL | `1999-EXIT` | 154 | `service.StatementDataAccessService` | `transactionFileTerminalExit` | `service.StatementDataAccessServiceTest` |  |
| app/cbl/CBSTM03B.CBL | `2000-XREFFILE-PROC` | 157 | `service.StatementDataAccessService` | `crossReferenceFileProc` | `service.StatementDataAccessServiceTest` |  |
| app/cbl/CBSTM03B.CBL | `2900-EXIT` | 175 | `service.StatementDataAccessService` | `crossReferenceFileExit` | `service.StatementDataAccessServiceTest` |  |
| app/cbl/CBSTM03B.CBL | `2999-EXIT` | 178 | `service.StatementDataAccessService` | `crossReferenceFileTerminalExit` | `service.StatementDataAccessServiceTest` |  |
| app/cbl/CBSTM03B.CBL | `3000-CUSTFILE-PROC` | 181 | `service.StatementDataAccessService` | `customerFileProc` | `service.StatementDataAccessServiceTest` |  |
| app/cbl/CBSTM03B.CBL | `3900-EXIT` | 200 | `service.StatementDataAccessService` | `customerFileExit` | `service.StatementDataAccessServiceTest` |  |
| app/cbl/CBSTM03B.CBL | `3999-EXIT` | 203 | `service.StatementDataAccessService` | `customerFileTerminalExit` | `service.StatementDataAccessServiceTest` |  |
| app/cbl/CBSTM03B.CBL | `4000-ACCTFILE-PROC` | 206 | `service.StatementDataAccessService` | `accountFileProc` | `service.StatementDataAccessServiceTest` |  |
| app/cbl/CBSTM03B.CBL | `4900-EXIT` | 225 | `service.StatementDataAccessService` | `accountFileExit` | `service.StatementDataAccessServiceTest` |  |
| app/cbl/CBSTM03B.CBL | `4999-EXIT` | 228 | `service.StatementDataAccessService` | `accountFileTerminalExit` | `service.StatementDataAccessServiceTest` |  |

## CBTRN01C

**18 paragraph units.** Legacy authority `app/cbl/CBTRN01C.cbl`, 491 lines. Daily-transaction
extract pass. No job stream invokes this program.

Primary target class `service.DailyTransactionReadService`, primary covering test
`service.DailyTransactionReadServiceTest`. Also exercised by:
`batch.DailyTransactionReadJobConfigTest`, `batch.DailyTransactionReadJobConfigIT`,
`api.BatchJobControllerIT`.

Every row here is marked `§`: no job member, procedure or resource definition invokes this program,
so the job is defined and exercised by tests but excluded from the default pipeline. Recorded as row
12 of the source anomaly register in [decision-log.md](decision-log.md).

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/CBTRN01C.cbl | `MAIN-PARA` | 155 | `service.DailyTransactionReadService` | `mainPara` | `service.DailyTransactionReadServiceTest` | § |
| app/cbl/CBTRN01C.cbl | `1000-DALYTRAN-GET-NEXT` | 202 | `service.DailyTransactionReadService` | `dalytranGetNext` | `service.DailyTransactionReadServiceTest` | § |
| app/cbl/CBTRN01C.cbl | `2000-LOOKUP-XREF` | 227 | `service.DailyTransactionReadService` | `lookupXref` | `service.DailyTransactionReadServiceTest` | § |
| app/cbl/CBTRN01C.cbl | `3000-READ-ACCOUNT` | 241 | `service.DailyTransactionReadService` | `readAccount` | `service.DailyTransactionReadServiceTest` | § |
| app/cbl/CBTRN01C.cbl | `0000-DALYTRAN-OPEN` | 252 | `service.DailyTransactionReadService` | `dalytranOpen` | `service.DailyTransactionReadServiceTest` | § |
| app/cbl/CBTRN01C.cbl | `0100-CUSTFILE-OPEN` | 271 | `service.DailyTransactionReadService` | `custfileOpen` | `service.DailyTransactionReadServiceTest` | § |
| app/cbl/CBTRN01C.cbl | `0200-XREFFILE-OPEN` | 289 | `service.DailyTransactionReadService` | `xreffileOpen` | `service.DailyTransactionReadServiceTest` | § |
| app/cbl/CBTRN01C.cbl | `0300-CARDFILE-OPEN` | 307 | `service.DailyTransactionReadService` | `cardfileOpen` | `service.DailyTransactionReadServiceTest` | § |
| app/cbl/CBTRN01C.cbl | `0400-ACCTFILE-OPEN` | 325 | `service.DailyTransactionReadService` | `acctfileOpen` | `service.DailyTransactionReadServiceTest` | § |
| app/cbl/CBTRN01C.cbl | `0500-TRANFILE-OPEN` | 343 | `service.DailyTransactionReadService` | `tranfileOpen` | `service.DailyTransactionReadServiceTest` | § |
| app/cbl/CBTRN01C.cbl | `9000-DALYTRAN-CLOSE` | 361 | `service.DailyTransactionReadService` | `dalytranClose` | `service.DailyTransactionReadServiceTest` | § |
| app/cbl/CBTRN01C.cbl | `9100-CUSTFILE-CLOSE` | 379 | `service.DailyTransactionReadService` | `custfileClose` | `service.DailyTransactionReadServiceTest` | § |
| app/cbl/CBTRN01C.cbl | `9200-XREFFILE-CLOSE` | 397 | `service.DailyTransactionReadService` | `xreffileClose` | `service.DailyTransactionReadServiceTest` | § |
| app/cbl/CBTRN01C.cbl | `9300-CARDFILE-CLOSE` | 415 | `service.DailyTransactionReadService` | `cardfileClose` | `service.DailyTransactionReadServiceTest` | § |
| app/cbl/CBTRN01C.cbl | `9400-ACCTFILE-CLOSE` | 433 | `service.DailyTransactionReadService` | `acctfileClose` | `service.DailyTransactionReadServiceTest` | § |
| app/cbl/CBTRN01C.cbl | `9500-TRANFILE-CLOSE` | 451 | `service.DailyTransactionReadService` | `tranfileClose` | `service.DailyTransactionReadServiceTest` | § |
| app/cbl/CBTRN01C.cbl | `Z-ABEND-PROGRAM` | 469 | `service.DailyTransactionReadService` | `zAbendProgram` | `service.DailyTransactionReadServiceTest` | § |
| app/cbl/CBTRN01C.cbl | `Z-DISPLAY-IO-STATUS` | 476 | `service.DailyTransactionReadService` | `zDisplayIoStatus` | `service.DailyTransactionReadServiceTest` | § |

## CBTRN02C

**26 paragraph units.** Legacy authority `app/cbl/CBTRN02C.cbl`, 731 lines. Daily-transaction
posting, with the reject trailer and the five reason codes.

Primary target class `service.TransactionPostingService`, primary covering test
`service.TransactionPostingServiceTest`. Also exercised by:
`batch.step.TransactionValidationProcessorTest`, `batch.step.RejectRecordWriterTest`,
`batch.PostTransactionJobConfigIT`, `e2e.BatchPipelineE2ETest`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/CBTRN02C.cbl | `0000-DALYTRAN-OPEN` | 236 | `service.TransactionPostingService` | `openDailyTransactionInput` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `0100-TRANFILE-OPEN` | 254 | `service.TransactionPostingService` | `openTransactionOutput` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `0200-XREFFILE-OPEN` | 273 | `service.TransactionPostingService` | `openCrossReferenceInput` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `0300-DALYREJS-OPEN` | 291 | `service.TransactionPostingService` | `openRejectOutput` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `0400-ACCTFILE-OPEN` | 309 | `service.TransactionPostingService` | `openAccountUpdate` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `0500-TCATBALF-OPEN` | 327 | `service.TransactionPostingService` | `openCategoryBalanceUpdate` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `1000-DALYTRAN-GET-NEXT` | 345 | `service.TransactionPostingService` | `getNextDailyTransaction` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `1500-VALIDATE-TRAN` | 370 | `service.TransactionPostingService` | `validateTransaction` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `1500-A-LOOKUP-XREF` | 380 | `service.TransactionPostingService` | `lookupCrossReference` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `1500-B-LOOKUP-ACCT` | 393 | `service.TransactionPostingService` | `lookupAccount` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `2000-POST-TRANSACTION` | 424 | `service.TransactionPostingService` | `postTransaction` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `2500-WRITE-REJECT-REC` | 446 | `service.TransactionPostingService` | `writeRejectRecord` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `2700-UPDATE-TCATBAL` | 467 | `service.TransactionPostingService` | `updateCategoryBalance` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `2700-A-CREATE-TCATBAL-REC` | 503 | `service.TransactionPostingService` | `createCategoryBalanceRecord` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `2700-B-UPDATE-TCATBAL-REC` | 526 | `service.TransactionPostingService` | `updateCategoryBalanceRecord` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `2800-UPDATE-ACCOUNT-REC` | 545 | `service.TransactionPostingService` | `updateAccountRecord` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `2900-WRITE-TRANSACTION-FILE` | 562 | `service.TransactionPostingService` | `writeTransactionFile` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `9000-DALYTRAN-CLOSE` | 582 | `service.TransactionPostingService` | `closeDailyTransactionInput` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `9100-TRANFILE-CLOSE` | 600 | `service.TransactionPostingService` | `closeTransactionOutput` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `9200-XREFFILE-CLOSE` | 619 | `service.TransactionPostingService` | `closeCrossReferenceInput` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `9300-DALYREJS-CLOSE` | 637 | `service.TransactionPostingService` | `closeRejectOutput` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `9400-ACCTFILE-CLOSE` | 655 | `service.TransactionPostingService` | `closeAccountUpdate` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `9500-TCATBALF-CLOSE` | 674 | `service.TransactionPostingService` | `closeCategoryBalanceUpdate` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `Z-GET-DB2-FORMAT-TIMESTAMP` | 692 | `service.TransactionPostingService` | `getDb2FormatTimestamp` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `9999-ABEND-PROGRAM` | 707 | `service.TransactionPostingService` | `abendProgram` | `service.TransactionPostingServiceTest` |  |
| app/cbl/CBTRN02C.cbl | `9910-DISPLAY-IO-STATUS` | 714 | `service.TransactionPostingService` | `displayIoStatus` | `service.TransactionPostingServiceTest` |  |

## CBTRN03C

**26 paragraph units.** Legacy authority `app/cbl/CBTRN03C.cbl`, 649 lines. The transaction detail
report. The member contains no arithmetic statement.

Primary target class `service.TransactionReportService`, primary covering test
`service.TransactionReportServiceTest`. Also exercised by:
`batch.step.TransactionReportProcessorTest`, `batch.TransactionReportJobConfigIT`,
`e2e.BatchPipelineE2ETest`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/CBTRN03C.cbl | `0550-DATEPARM-READ` | 220 | `service.TransactionReportService` | `dateparmRead` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `1000-TRANFILE-GET-NEXT` | 248 | `service.TransactionReportService` | `tranfileGetNext` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `1100-WRITE-TRANSACTION-REPORT` | 274 | `service.TransactionReportService` | `writeTransactionReport` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `1110-WRITE-PAGE-TOTALS` | 293 | `service.TransactionReportService` | `writePageTotals` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `1120-WRITE-ACCOUNT-TOTALS` | 306 | `service.TransactionReportService` | `writeAccountTotals` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `1110-WRITE-GRAND-TOTALS` | 318 | `service.TransactionReportService` | `writeGrandTotals` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `1120-WRITE-HEADERS` | 324 | `service.TransactionReportService` | `writeHeaders` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `1111-WRITE-REPORT-REC` | 343 | `service.TransactionReportService` | `writeReportRec` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `1120-WRITE-DETAIL` | 361 | `service.TransactionReportService` | `writeDetail` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `0000-TRANFILE-OPEN` | 376 | `service.TransactionReportService` | `tranfileOpen` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `0100-REPTFILE-OPEN` | 394 | `service.TransactionReportService` | `reptfileOpen` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `0200-CARDXREF-OPEN` | 412 | `service.TransactionReportService` | `cardxrefOpen` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `0300-TRANTYPE-OPEN` | 430 | `service.TransactionReportService` | `trantypeOpen` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `0400-TRANCATG-OPEN` | 448 | `service.TransactionReportService` | `trancatgOpen` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `0500-DATEPARM-OPEN` | 466 | `service.TransactionReportService` | `dateparmOpen` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `1500-A-LOOKUP-XREF` | 484 | `service.TransactionReportService` | `lookupXref` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `1500-B-LOOKUP-TRANTYPE` | 494 | `service.TransactionReportService` | `lookupTrantype` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `1500-C-LOOKUP-TRANCATG` | 504 | `service.TransactionReportService` | `lookupTrancatg` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `9000-TRANFILE-CLOSE` | 514 | `service.TransactionReportService` | `tranfileClose` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `9100-REPTFILE-CLOSE` | 532 | `service.TransactionReportService` | `reptfileClose` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `9200-CARDXREF-CLOSE` | 551 | `service.TransactionReportService` | `cardxrefClose` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `9300-TRANTYPE-CLOSE` | 569 | `service.TransactionReportService` | `trantypeClose` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `9400-TRANCATG-CLOSE` | 587 | `service.TransactionReportService` | `trancatgClose` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `9500-DATEPARM-CLOSE` | 605 | `service.TransactionReportService` | `dateparmClose` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `9999-ABEND-PROGRAM` | 626 | `service.TransactionReportService` | `abendProgram` | `service.TransactionReportServiceTest` |  |
| app/cbl/CBTRN03C.cbl | `9910-DISPLAY-IO-STATUS` | 633 | `service.TransactionReportService` | `displayIoStatus` | `service.TransactionReportServiceTest` |  |

## COACTUPC

**85 paragraph units.** Legacy authority `app/cbl/COACTUPC.cbl`, 4236 lines. Account update. The
largest single member in the estate.

Primary target class `service.AccountUpdateService`, primary covering test
`service.AccountUpdateServiceTest`. Also exercised by: `api.AccountControllerIT`.

Six of the 85 rows below carry the `¶` marker. Their methods are translated, are declared on the primary
target class, and are reached by no delivered call site; the primary covering test exercises each of the
three heads directly, by name, in its `DeliberatelyUnwiredEdits` group, and each head executes its paired
exit on every arm. See
[why the marker exists and what it obliges](#why-the-unwired-paragraph-marker-exists-and-what-it-obliges).

**Which six, and why each is there** — stated here rather than left to be discovered. They are
`1230-EDIT-ALPHANUM-REQD` and its exit, `1235-EDIT-ALPHA-OPT` and its exit, and `1240-EDIT-ALPHANUM-OPT`
and its exit — the three character-class edits whose Java methods exist, are translated in full, and are
called by no production path. Two different reasons put them there. The two alphanumeric edits have **no
call site in the source**: the driver reaches the required alphabetic, the optional alphabetic, the required
numeric, the mandatory and the signed edits, and never those two. The optional alphabetic edit does have a
live source call site, on the middle name, but the migration directive forbids attaching any constraint to
that field — the screen-attribute block describes it as carrying no edits — so wiring it would reject input
the legacy is documented to accept. That conflict, and the fact that the comment describing the middle name
as unedited is itself a source defect, are recorded in [decision-log.md](decision-log.md).

These six rows are **not** non-implementations, which is why they carry the `¶` marker and not the `†` or
`‡` one: the methods are complete translations, and the marker denotes *unrouted* rather than
*unimplemented*. The named covering test **executes all six**, in the nested specification *the three
character-class edits the member translates but never reaches*. It reaches them directly — that being the
only way to run them without the wiring the directive forbids — and asserts the blank arm, the character
class, the composed message suffix and the embedded-space idiom of each, plus that no production call site
has appeared. Coverage measurement confirms the execution rather than the citation asserting it: all six
methods report **zero missed instructions and zero missed lines**. The distinction that matters for the
sign-off is between a row whose test *names* it and a row whose test *runs* it, and every row on this page
is the second kind.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/COACTUPC.cbl | `0000-MAIN` | 859 | `service.AccountUpdateService` | `handle` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `COMMON-RETURN` | 1007 | `service.AccountUpdateService` | `commonReturn` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `0000-MAIN-EXIT` | 1021 | `service.AccountUpdateService` | `mainExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1000-PROCESS-INPUTS` | 1025 | `service.AccountUpdateService` | `processInputs` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1000-PROCESS-INPUTS-EXIT` | 1036 | `service.AccountUpdateService` | `processInputsExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1100-RECEIVE-MAP` | 1039 | `service.AccountUpdateService` | `receiveMap` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1100-RECEIVE-MAP-EXIT` | 1426 | `service.AccountUpdateService` | `receiveMapExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1200-EDIT-MAP-INPUTS` | 1429 | `service.AccountUpdateService` | `editMapInputs` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1200-EDIT-MAP-INPUTS-EXIT` | 1678 | `service.AccountUpdateService` | `editMapInputsExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1205-COMPARE-OLD-NEW` | 1681 | `service.AccountUpdateService` | `compareOldNew` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1205-COMPARE-OLD-NEW-EXIT` | 1777 | `service.AccountUpdateService` | `compareOldNewExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1210-EDIT-ACCOUNT` | 1783 | `service.AccountUpdateService` | `editAccount` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1210-EDIT-ACCOUNT-EXIT` | 1820 | `service.AccountUpdateService` | `editAccountExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1215-EDIT-MANDATORY` | 1824 | `service.AccountUpdateService` | `editMandatory` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1215-EDIT-MANDATORY-EXIT` | 1852 | `service.AccountUpdateService` | `editMandatoryExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1220-EDIT-YESNO` | 1856 | `service.AccountUpdateService` | `editYesNo` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1220-EDIT-YESNO-EXIT` | 1894 | `service.AccountUpdateService` | `editYesNoExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1225-EDIT-ALPHA-REQD` | 1898 | `service.AccountUpdateService` | `editAlphaRequired` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1225-EDIT-ALPHA-REQD-EXIT` | 1951 | `service.AccountUpdateService` | `editAlphaRequiredExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1230-EDIT-ALPHANUM-REQD` | 1955 | `service.AccountUpdateService` | `editAlphanumericRequired` | `service.AccountUpdateServiceTest` | ¶ |
| app/cbl/COACTUPC.cbl | `1230-EDIT-ALPHANUM-REQD-EXIT` | 2009 | `service.AccountUpdateService` | `editAlphanumericRequiredExit` | `service.AccountUpdateServiceTest` | ¶ |
| app/cbl/COACTUPC.cbl | `1235-EDIT-ALPHA-OPT` | 2012 | `service.AccountUpdateService` | `editAlphaOptional` | `service.AccountUpdateServiceTest` | ¶ |
| app/cbl/COACTUPC.cbl | `1235-EDIT-ALPHA-OPT-EXIT` | 2057 | `service.AccountUpdateService` | `editAlphaOptionalExit` | `service.AccountUpdateServiceTest` | ¶ |
| app/cbl/COACTUPC.cbl | `1240-EDIT-ALPHANUM-OPT` | 2061 | `service.AccountUpdateService` | `editAlphanumericOptional` | `service.AccountUpdateServiceTest` | ¶ |
| app/cbl/COACTUPC.cbl | `1240-EDIT-ALPHANUM-OPT-EXIT` | 2105 | `service.AccountUpdateService` | `editAlphanumericOptionalExit` | `service.AccountUpdateServiceTest` | ¶ |
| app/cbl/COACTUPC.cbl | `1245-EDIT-NUM-REQD` | 2109 | `service.AccountUpdateService` | `editNumericRequired` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1245-EDIT-NUM-REQD-EXIT` | 2176 | `service.AccountUpdateService` | `editNumericRequiredExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1250-EDIT-SIGNED-9V2` | 2180 | `service.AccountUpdateService` | `editSigned9v2` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1250-EDIT-SIGNED-9V2-EXIT` | 2221 | `service.AccountUpdateService` | `editSigned9v2Exit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1260-EDIT-US-PHONE-NUM` | 2225 | `service.AccountUpdateService` | `editUsPhoneNumber` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `EDIT-AREA-CODE` | 2246 | `service.AccountUpdateService` | `editAreaCode` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `EDIT-US-PHONE-PREFIX` | 2316 | `service.AccountUpdateService` | `editUsPhonePrefix` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `EDIT-US-PHONE-LINENUM` | 2370 | `service.AccountUpdateService` | `editUsPhoneLineNumber` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `EDIT-US-PHONE-EXIT` | 2424 | `service.AccountUpdateService` | `editUsPhoneExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1260-EDIT-US-PHONE-NUM-EXIT` | 2427 | `service.AccountUpdateService` | `editUsPhoneNumberExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1265-EDIT-US-SSN` | 2431 | `service.AccountUpdateService` | `editUsSsn` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1265-EDIT-US-SSN-EXIT` | 2489 | `service.AccountUpdateService` | `editUsSsnExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1270-EDIT-US-STATE-CD` | 2493 | `service.AccountUpdateService` | `editUsStateCode` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1270-EDIT-US-STATE-CD-EXIT` | 2511 | `service.AccountUpdateService` | `editUsStateCodeExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1275-EDIT-FICO-SCORE` | 2514 | `service.AccountUpdateService` | `editFicoScore` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1275-EDIT-FICO-SCORE-EXIT` | 2531 | `service.AccountUpdateService` | `editFicoScoreExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1280-EDIT-US-STATE-ZIP-CD` | 2536 | `service.AccountUpdateService` | `editUsStateZipCode` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `1280-EDIT-US-STATE-ZIP-CD-EXIT` | 2558 | `service.AccountUpdateService` | `editUsStateZipCodeExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `2000-DECIDE-ACTION` | 2562 | `service.AccountUpdateService` | `decideAction` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `2000-DECIDE-ACTION-EXIT` | 2643 | `service.AccountUpdateService` | `decideActionExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3000-SEND-MAP` | 2649 | `service.AccountUpdateService` | `sendMap` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3000-SEND-MAP-EXIT` | 2664 | `service.AccountUpdateService` | `sendMapExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3100-SCREEN-INIT` | 2668 | `service.AccountUpdateService` | `screenInit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3100-SCREEN-INIT-EXIT` | 2694 | `service.AccountUpdateService` | `screenInitExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3200-SETUP-SCREEN-VARS` | 2698 | `service.AccountUpdateService` | `setupScreenVars` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3200-SETUP-SCREEN-VARS-EXIT` | 2727 | `service.AccountUpdateService` | `setupScreenVarsExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3201-SHOW-INITIAL-VALUES` | 2731 | `service.AccountUpdateService` | `showInitialValues` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3201-SHOW-INITIAL-VALUES-EXIT` | 2783 | `service.AccountUpdateService` | `showInitialValuesExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3202-SHOW-ORIGINAL-VALUES` | 2787 | `service.AccountUpdateService` | `showOriginalValues` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3202-SHOW-ORIGINAL-VALUES-EXIT` | 2867 | `service.AccountUpdateService` | `showOriginalValuesExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3203-SHOW-UPDATED-VALUES` | 2870 | `service.AccountUpdateService` | `showUpdatedValues` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3203-SHOW-UPDATED-VALUES-EXIT` | 2951 | `service.AccountUpdateService` | `showUpdatedValuesExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3250-SETUP-INFOMSG` | 2955 | `service.AccountUpdateService` | `setupInfoMessage` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3250-SETUP-INFOMSG-EXIT` | 2983 | `service.AccountUpdateService` | `setupInfoMessageExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3300-SETUP-SCREEN-ATTRS` | 2986 | `service.AccountUpdateService` | `setupScreenAttributes` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3300-SETUP-SCREEN-ATTRS-EXIT` | 3437 | `service.AccountUpdateService` | `setupScreenAttributesExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3310-PROTECT-ALL-ATTRS` | 3441 | `service.AccountUpdateService` | `protectAllAttributes` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3310-PROTECT-ALL-ATTRS-EXIT` | 3496 | `service.AccountUpdateService` | `protectAllAttributesExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3320-UNPROTECT-FEW-ATTRS` | 3500 | `service.AccountUpdateService` | `unprotectFewAttributes` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3320-UNPROTECT-FEW-ATTRS-EXIT` | 3562 | `service.AccountUpdateService` | `unprotectFewAttributesExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3390-SETUP-INFOMSG-ATTRS` | 3566 | `service.AccountUpdateService` | `setupInfoMessageAttributes` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3390-SETUP-INFOMSG-ATTRS-EXIT` | 3584 | `service.AccountUpdateService` | `setupInfoMessageAttributesExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3400-SEND-SCREEN` | 3589 | `service.AccountUpdateService` | `sendScreen` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `3400-SEND-SCREEN-EXIT` | 3603 | `service.AccountUpdateService` | `sendScreenExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `9000-READ-ACCT` | 3608 | `service.AccountUpdateService` | `readAccount` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `9000-READ-ACCT-EXIT` | 3647 | `service.AccountUpdateService` | `readAccountExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `9200-GETCARDXREF-BYACCT` | 3650 | `service.AccountUpdateService` | `getCardXrefByAccount` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `9200-GETCARDXREF-BYACCT-EXIT` | 3698 | `service.AccountUpdateService` | `getCardXrefByAccountExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `9300-GETACCTDATA-BYACCT` | 3701 | `service.AccountUpdateService` | `getAccountDataByAccount` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `9300-GETACCTDATA-BYACCT-EXIT` | 3748 | `service.AccountUpdateService` | `getAccountDataByAccountExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `9400-GETCUSTDATA-BYCUST` | 3752 | `service.AccountUpdateService` | `getCustomerDataByCustomer` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `9400-GETCUSTDATA-BYCUST-EXIT` | 3797 | `service.AccountUpdateService` | `getCustomerDataByCustomerExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `9500-STORE-FETCHED-DATA` | 3801 | `service.AccountUpdateService` | `storeFetchedData` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `9500-STORE-FETCHED-DATA-EXIT` | 3885 | `service.AccountUpdateService` | `storeFetchedDataExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `9600-WRITE-PROCESSING` | 3888 | `service.AccountUpdateService` | `writeProcessing` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `9600-WRITE-PROCESSING-EXIT` | 4105 | `service.AccountUpdateService` | `writeProcessingExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `9700-CHECK-CHANGE-IN-REC` | 4109 | `service.AccountUpdateService` | `checkChangeInRecord` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `9700-CHECK-CHANGE-IN-REC-EXIT` | 4193 | `service.AccountUpdateService` | `checkChangeInRecordExit` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `ABEND-ROUTINE` | 4203 | `service.AccountUpdateService` | `abendRoutine` | `service.AccountUpdateServiceTest` |  |
| app/cbl/COACTUPC.cbl | `ABEND-ROUTINE-EXIT` | 4226 | `service.AccountUpdateService` | `abendRoutineExit` | `service.AccountUpdateServiceTest` |  |

## COACTVWC

**35 paragraph units.** Legacy authority `app/cbl/COACTVWC.cbl`, 941 lines. Account view. Read-only:
three keyed reads, no write of any kind.

Primary target class `service.AccountViewService`, primary covering test
`service.AccountViewServiceTest`. Also exercised by: `api.AccountControllerIT`.

`0000-MAIN-EXIT` is declared twice, at line 408 and again at line 411. Both declarations are carried
as rows, marked `‡`, and both point at the one collapsed method, so the count stays honest rather
than quietly losing a unit. Recorded as row 2 of the source anomaly register in
[decision-log.md](decision-log.md).

Two of the 35 rows below carry the `¶` marker: `SEND-LONG-TEXT` at line 896 and its exit at 907. All
three statements that would have performed the paragraph — at 768, 818 and 867 — are commented out in the
member, as is the statement that would have filled the 500-character field it transmits, so no delivered
call site reaches it. Its plain-text sibling at 877 **is** performed, from the invalid-key arm, and is
therefore an ordinary row. `service.AccountViewServiceTest` calls `sendLongText` by name and names
`sendLongTextExit`, which that method calls on its only arm. See
[why the marker exists and what it obliges](#why-the-unwired-paragraph-marker-exists-and-what-it-obliges).

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/COACTVWC.cbl | `0000-MAIN` | 262 | `service.AccountViewService` | `viewAccount` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `COMMON-RETURN` | 394 | `service.AccountViewService` | `commonReturn` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `0000-MAIN-EXIT` | 408 | `service.AccountViewService` | `mainExit` | `service.AccountViewServiceTest` | ‡ |
| app/cbl/COACTVWC.cbl | `0000-MAIN-EXIT` | 411 | `service.AccountViewService` | `mainExit` | `service.AccountViewServiceTest` | ‡ |
| app/cbl/COACTVWC.cbl | `1000-SEND-MAP` | 416 | `service.AccountViewService` | `sendMap` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `1000-SEND-MAP-EXIT` | 427 | `service.AccountViewService` | `sendMapExit` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `1100-SCREEN-INIT` | 431 | `service.AccountViewService` | `screenInit` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `1100-SCREEN-INIT-EXIT` | 457 | `service.AccountViewService` | `screenInitExit` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `1200-SETUP-SCREEN-VARS` | 460 | `service.AccountViewService` | `setupScreenVars` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `1200-SETUP-SCREEN-VARS-EXIT` | 537 | `service.AccountViewService` | `setupScreenVarsExit` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `1300-SETUP-SCREEN-ATTRS` | 541 | `service.AccountViewService` | `setupScreenAttrs` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `1300-SETUP-SCREEN-ATTRS-EXIT` | 574 | `service.AccountViewService` | `setupScreenAttrsExit` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `1400-SEND-SCREEN` | 577 | `service.AccountViewService` | `sendScreen` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `1400-SEND-SCREEN-EXIT` | 592 | `service.AccountViewService` | `sendScreenExit` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `2000-PROCESS-INPUTS` | 596 | `service.AccountViewService` | `processInputs` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `2000-PROCESS-INPUTS-EXIT` | 607 | `service.AccountViewService` | `processInputsExit` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `2100-RECEIVE-MAP` | 610 | `service.AccountViewService` | `receiveMap` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `2100-RECEIVE-MAP-EXIT` | 619 | `service.AccountViewService` | `receiveMapExit` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `2200-EDIT-MAP-INPUTS` | 622 | `service.AccountViewService` | `editMapInputs` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `2200-EDIT-MAP-INPUTS-EXIT` | 645 | `service.AccountViewService` | `editMapInputsExit` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `2210-EDIT-ACCOUNT` | 649 | `service.AccountViewService` | `editAccount` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `2210-EDIT-ACCOUNT-EXIT` | 683 | `service.AccountViewService` | `editAccountExit` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `9000-READ-ACCT` | 687 | `service.AccountViewService` | `readAcct` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `9000-READ-ACCT-EXIT` | 720 | `service.AccountViewService` | `readAcctExit` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `9200-GETCARDXREF-BYACCT` | 723 | `service.AccountViewService` | `getCardXrefByAcct` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `9200-GETCARDXREF-BYACCT-EXIT` | 771 | `service.AccountViewService` | `getCardXrefByAcctExit` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `9300-GETACCTDATA-BYACCT` | 774 | `service.AccountViewService` | `getAcctDataByAcct` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `9300-GETACCTDATA-BYACCT-EXIT` | 821 | `service.AccountViewService` | `getAcctDataByAcctExit` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `9400-GETCUSTDATA-BYCUST` | 825 | `service.AccountViewService` | `getCustDataByCust` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `9400-GETCUSTDATA-BYCUST-EXIT` | 870 | `service.AccountViewService` | `getCustDataByCustExit` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `SEND-PLAIN-TEXT` | 877 | `service.AccountViewService` | `sendPlainText` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `SEND-PLAIN-TEXT-EXIT` | 888 | `service.AccountViewService` | `sendPlainTextExit` | `service.AccountViewServiceTest` |  |
| app/cbl/COACTVWC.cbl | `SEND-LONG-TEXT` | 896 | `service.AccountViewService` | `sendLongText` | `service.AccountViewServiceTest` | ¶ |
| app/cbl/COACTVWC.cbl | `SEND-LONG-TEXT-EXIT` | 907 | `service.AccountViewService` | `sendLongTextExit` | `service.AccountViewServiceTest` | ¶ |
| app/cbl/COACTVWC.cbl | `ABEND-ROUTINE` | 916 | `service.AccountViewService` | `abendRoutine` | `service.AccountViewServiceTest` |  |

## COADM01C

**7 paragraph units.** Legacy authority `app/cbl/COADM01C.cbl`, 268 lines. The administrator menu.

Primary target class `service.MenuService`, primary covering test `service.MenuServiceTest`. Also
exercised by: `api.MenuControllerIT`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/COADM01C.cbl | `MAIN-PARA` | 75 | `service.MenuService` | `adminMenu` | `service.MenuServiceTest` |  |
| app/cbl/COADM01C.cbl | `PROCESS-ENTER-KEY` | 115 | `service.MenuService` | `processAdminMenuEnterKey` | `service.MenuServiceTest` |  |
| app/cbl/COADM01C.cbl | `RETURN-TO-SIGNON-SCREEN` | 160 | `service.MenuService` | `returnToSignOnScreen` | `service.MenuServiceTest` |  |
| app/cbl/COADM01C.cbl | `SEND-MENU-SCREEN` | 172 | `service.MenuService` | `sendAdminMenuScreen` | `service.MenuServiceTest` |  |
| app/cbl/COADM01C.cbl | `RECEIVE-MENU-SCREEN` | 189 | `service.MenuService` | `receiveAdminMenuScreen` | `service.MenuServiceTest` |  |
| app/cbl/COADM01C.cbl | `POPULATE-HEADER-INFO` | 202 | `service.MenuService` | `populateHeaderInfo` | `service.MenuServiceTest` |  |
| app/cbl/COADM01C.cbl | `BUILD-MENU-OPTIONS` | 226 | `service.MenuService` | `buildAdminMenuOptions` | `service.MenuServiceTest` |  |

## COBIL00C

**16 paragraph units.** Legacy authority `app/cbl/COBIL00C.cbl`, 572 lines. Bill payment, including
highest-key-plus-one identifier allocation.

Primary target class `service.BillPaymentService`, primary covering test
`service.BillPaymentServiceTest`. Also exercised by: `api.BillPaymentControllerIT`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/COBIL00C.cbl | `MAIN-PARA` | 99 | `service.BillPaymentService` | `mainPara` | `service.BillPaymentServiceTest` |  |
| app/cbl/COBIL00C.cbl | `PROCESS-ENTER-KEY` | 154 | `service.BillPaymentService` | `processEnterKey` | `service.BillPaymentServiceTest` |  |
| app/cbl/COBIL00C.cbl | `GET-CURRENT-TIMESTAMP` | 249 | `service.BillPaymentService` | `getCurrentTimestamp` | `service.BillPaymentServiceTest` |  |
| app/cbl/COBIL00C.cbl | `RETURN-TO-PREV-SCREEN` | 273 | `service.BillPaymentService` | `returnToPrevScreen` | `service.BillPaymentServiceTest` |  |
| app/cbl/COBIL00C.cbl | `SEND-BILLPAY-SCREEN` | 289 | `service.BillPaymentService` | `sendBillpayScreen` | `service.BillPaymentServiceTest` |  |
| app/cbl/COBIL00C.cbl | `RECEIVE-BILLPAY-SCREEN` | 306 | `service.BillPaymentService` | `receiveBillpayScreen` | `service.BillPaymentServiceTest` |  |
| app/cbl/COBIL00C.cbl | `POPULATE-HEADER-INFO` | 319 | `service.BillPaymentService` | `populateHeaderInfo` | `service.BillPaymentServiceTest` |  |
| app/cbl/COBIL00C.cbl | `READ-ACCTDAT-FILE` | 343 | `service.BillPaymentService` | `readAcctdatFile` | `service.BillPaymentServiceTest` |  |
| app/cbl/COBIL00C.cbl | `UPDATE-ACCTDAT-FILE` | 377 | `service.BillPaymentService` | `updateAcctdatFile` | `service.BillPaymentServiceTest` |  |
| app/cbl/COBIL00C.cbl | `READ-CXACAIX-FILE` | 408 | `service.BillPaymentService` | `readCxacaixFile` | `service.BillPaymentServiceTest` |  |
| app/cbl/COBIL00C.cbl | `STARTBR-TRANSACT-FILE` | 441 | `service.BillPaymentService` | `startbrTransactFile` | `service.BillPaymentServiceTest` |  |
| app/cbl/COBIL00C.cbl | `READPREV-TRANSACT-FILE` | 472 | `service.BillPaymentService` | `readprevTransactFile` | `service.BillPaymentServiceTest` |  |
| app/cbl/COBIL00C.cbl | `ENDBR-TRANSACT-FILE` | 501 | `service.BillPaymentService` | `endbrTransactFile` | `service.BillPaymentServiceTest` |  |
| app/cbl/COBIL00C.cbl | `WRITE-TRANSACT-FILE` | 510 | `service.BillPaymentService` | `resolveWriteResponse` | `service.BillPaymentServiceTest` |  |
| app/cbl/COBIL00C.cbl | `CLEAR-CURRENT-SCREEN` | 552 | `service.BillPaymentService` | `clearCurrentScreen` | `service.BillPaymentServiceTest` |  |
| app/cbl/COBIL00C.cbl | `INITIALIZE-ALL-FIELDS` | 560 | `service.BillPaymentService` | `initializeAllFields` | `service.BillPaymentServiceTest` |  |

## COCRDLIC

**39 paragraph units.** Legacy authority `app/cbl/COCRDLIC.cbl`, 1459 lines. The paginated card
list.

Primary target class `service.CardListService`, primary covering test `service.CardListServiceTest`.
Also exercised by: `api.CardControllerIT`.

Four of the 39 rows below carry the `¶` marker: `SEND-PLAIN-TEXT` at line 1422 and `SEND-LONG-TEXT` at
1441, with their exits at 1433 and 1452. The member's own comments — at 1420 for the first and at 1438 to
1439 for the second — state that both are diagnostic and not for production use, and no performed or
jumped-to label in the member reaches either. `service.CardListServiceTest` calls both heads by name in its
`DiagnosticSenders` group and names both exits, each of which its head calls on its only arm. See
[why the marker exists and what it obliges](#why-the-unwired-paragraph-marker-exists-and-what-it-obliges).

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/COCRDLIC.cbl | `0000-MAIN` | 298 | `service.CardListService` | `mainPara` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `COMMON-RETURN` | 604 | `service.CardListService` | `commonReturn` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `0000-MAIN-EXIT` | 621 | `service.CardListService` | `mainParaExit` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `1000-SEND-MAP` | 624 | `service.CardListService` | `sendMap` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `1000-SEND-MAP-EXIT` | 639 | `service.CardListService` | `sendMapExit` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `1100-SCREEN-INIT` | 642 | `service.CardListService` | `screenInit` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `1100-SCREEN-INIT-EXIT` | 674 | `service.CardListService` | `screenInitExit` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `1200-SCREEN-ARRAY-INIT` | 678 | `service.CardListService` | `screenArrayInit` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `1200-SCREEN-ARRAY-INIT-EXIT` | 745 | `service.CardListService` | `screenArrayInitExit` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `1250-SETUP-ARRAY-ATTRIBS` | 748 | `service.CardListService` | `setupArrayAttribs` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `1250-SETUP-ARRAY-ATTRIBS-EXIT` | 834 | `service.CardListService` | `setupArrayAttribsExit` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `1300-SETUP-SCREEN-ATTRS` | 837 | `service.CardListService` | `setupScreenAttrs` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `1300-SETUP-SCREEN-ATTRS-EXIT` | 890 | `service.CardListService` | `setupScreenAttrsExit` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `1400-SETUP-MESSAGE` | 895 | `service.CardListService` | `setupMessage` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `1400-SETUP-MESSAGE-EXIT` | 933 | `service.CardListService` | `setupMessageExit` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `1500-SEND-SCREEN` | 938 | `service.CardListService` | `sendScreen` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `1500-SEND-SCREEN-EXIT` | 948 | `service.CardListService` | `sendScreenExit` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `2000-RECEIVE-MAP` | 951 | `service.CardListService` | `receiveMap` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `2000-RECEIVE-MAP-EXIT` | 959 | `service.CardListService` | `receiveMapExit` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `2100-RECEIVE-SCREEN` | 962 | `service.CardListService` | `receiveScreen` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `2100-RECEIVE-SCREEN-EXIT` | 981 | `service.CardListService` | `receiveScreenExit` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `2200-EDIT-INPUTS` | 985 | `service.CardListService` | `editInputs` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `2200-EDIT-INPUTS-EXIT` | 999 | `service.CardListService` | `editInputsExit` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `2210-EDIT-ACCOUNT` | 1003 | `service.CardListService` | `editAccount` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `2210-EDIT-ACCOUNT-EXIT` | 1032 | `service.CardListService` | `editAccountExit` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `2220-EDIT-CARD` | 1036 | `service.CardListService` | `editCard` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `2220-EDIT-CARD-EXIT` | 1069 | `service.CardListService` | `editCardExit` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `2250-EDIT-ARRAY` | 1073 | `service.CardListService` | `editArray` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `2250-EDIT-ARRAY-EXIT` | 1119 | `service.CardListService` | `editArrayExit` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `9000-READ-FORWARD` | 1123 | `service.CardListService` | `readForward` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `9000-READ-FORWARD-EXIT` | 1261 | `service.CardListService` | `readForwardExit` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `9100-READ-BACKWARDS` | 1264 | `service.CardListService` | `readBackwards` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `9100-READ-BACKWARDS-EXIT` | 1374 | `service.CardListService` | `readBackwardsExit` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `9500-FILTER-RECORDS` | 1382 | `service.CardListService` | `filterRecords` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `9500-FILTER-RECORDS-EXIT` | 1409 | `service.CardListService` | `filterRecordsExit` | `service.CardListServiceTest` |  |
| app/cbl/COCRDLIC.cbl | `SEND-PLAIN-TEXT` | 1422 | `service.CardListService` | `sendPlainText` | `service.CardListServiceTest` | ¶ |
| app/cbl/COCRDLIC.cbl | `SEND-PLAIN-TEXT-EXIT` | 1433 | `service.CardListService` | `sendPlainTextExit` | `service.CardListServiceTest` | ¶ |
| app/cbl/COCRDLIC.cbl | `SEND-LONG-TEXT` | 1441 | `service.CardListService` | `sendLongText` | `service.CardListServiceTest` | ¶ |
| app/cbl/COCRDLIC.cbl | `SEND-LONG-TEXT-EXIT` | 1452 | `service.CardListService` | `sendLongTextExit` | `service.CardListServiceTest` | ¶ |

## COCRDSLC

**34 paragraph units.** Legacy authority `app/cbl/COCRDSLC.cbl`, 887 lines. Card detail.

Primary target class `service.CardDetailService`, primary covering test
`service.CardDetailServiceTest`. Also exercised by: `api.CardControllerIT`.

Four of the 34 rows below carry the `¶` marker. `9150-GETCARD-BYACCT` at line 779 and its exit at 810 are
the account-keyed read over the non-unique alternate index: a census of every `PERFORM` in the member finds
the paragraph name only in its own two labels, and the read driver at 726 performs the card-number read
alone, so wiring it would add a flow the legacy does not have. `SEND-LONG-TEXT` at 820 and its exit at 831
are unreached for the same reason as their counterparts in the card-list member; the plain-text sender at
838 is performed and is an ordinary row. `service.CardDetailServiceTest` calls both heads by name in its
`AccountKeyedRead` group and names both exits — the read's exit is executed on all three of its arms. See
[why the marker exists and what it obliges](#why-the-unwired-paragraph-marker-exists-and-what-it-obliges).

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/COCRDSLC.cbl | `0000-MAIN` | 248 | `service.CardDetailService` | `mainPara` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `COMMON-RETURN` | 394 | `service.CardDetailService` | `commonReturn` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `0000-MAIN-EXIT` | 408 | `service.CardDetailService` | `mainParaExit` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `1000-SEND-MAP` | 412 | `service.CardDetailService` | `sendMap` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `1000-SEND-MAP-EXIT` | 423 | `service.CardDetailService` | `sendMapExit` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `1100-SCREEN-INIT` | 427 | `service.CardDetailService` | `screenInit` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `1100-SCREEN-INIT-EXIT` | 453 | `service.CardDetailService` | `screenInitExit` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `1200-SETUP-SCREEN-VARS` | 457 | `service.CardDetailService` | `setupScreenVars` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `1200-SETUP-SCREEN-VARS-EXIT` | 499 | `service.CardDetailService` | `setupScreenVarsExit` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `1300-SETUP-SCREEN-ATTRS` | 502 | `service.CardDetailService` | `setupScreenAttrs` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `1300-SETUP-SCREEN-ATTRS-EXIT` | 559 | `service.CardDetailService` | `setupScreenAttrsExit` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `1400-SEND-SCREEN` | 563 | `service.CardDetailService` | `sendScreen` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `1400-SEND-SCREEN-EXIT` | 578 | `service.CardDetailService` | `sendScreenExit` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `2000-PROCESS-INPUTS` | 582 | `service.CardDetailService` | `processInputs` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `2000-PROCESS-INPUTS-EXIT` | 593 | `service.CardDetailService` | `processInputsExit` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `2100-RECEIVE-MAP` | 596 | `service.CardDetailService` | `receiveMap` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `2100-RECEIVE-MAP-EXIT` | 605 | `service.CardDetailService` | `receiveMapExit` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `2200-EDIT-MAP-INPUTS` | 608 | `service.CardDetailService` | `editMapInputs` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `2200-EDIT-MAP-INPUTS-EXIT` | 643 | `service.CardDetailService` | `editMapInputsExit` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `2210-EDIT-ACCOUNT` | 647 | `service.CardDetailService` | `editAccount` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `2210-EDIT-ACCOUNT-EXIT` | 681 | `service.CardDetailService` | `editAccountExit` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `2220-EDIT-CARD` | 685 | `service.CardDetailService` | `editCard` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `2220-EDIT-CARD-EXIT` | 722 | `service.CardDetailService` | `editCardExit` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `9000-READ-DATA` | 726 | `service.CardDetailService` | `readData` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `9000-READ-DATA-EXIT` | 732 | `service.CardDetailService` | `readDataExit` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `9100-GETCARD-BYACCTCARD` | 736 | `service.CardDetailService` | `getCardByAcctCard` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `9100-GETCARD-BYACCTCARD-EXIT` | 775 | `service.CardDetailService` | `getCardByAcctCardExit` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `9150-GETCARD-BYACCT` | 779 | `service.CardDetailService` | `getCardByAcct` | `service.CardDetailServiceTest` | ¶ |
| app/cbl/COCRDSLC.cbl | `9150-GETCARD-BYACCT-EXIT` | 810 | `service.CardDetailService` | `getCardByAcctExit` | `service.CardDetailServiceTest` | ¶ |
| app/cbl/COCRDSLC.cbl | `SEND-LONG-TEXT` | 820 | `service.CardDetailService` | `sendLongText` | `service.CardDetailServiceTest` | ¶ |
| app/cbl/COCRDSLC.cbl | `SEND-LONG-TEXT-EXIT` | 831 | `service.CardDetailService` | `sendLongTextExit` | `service.CardDetailServiceTest` | ¶ |
| app/cbl/COCRDSLC.cbl | `SEND-PLAIN-TEXT` | 838 | `service.CardDetailService` | `sendPlainText` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `SEND-PLAIN-TEXT-EXIT` | 849 | `service.CardDetailService` | `sendPlainTextExit` | `service.CardDetailServiceTest` |  |
| app/cbl/COCRDSLC.cbl | `ABEND-ROUTINE` | 857 | `service.CardDetailService` | `abendRoutine` | `service.CardDetailServiceTest` |  |

## COCRDUPC

**45 paragraph units.** Legacy authority `app/cbl/COCRDUPC.cbl`, 1560 lines. Card update.

Primary target class `service.CardUpdateService`, primary covering test
`service.CardUpdateServiceTest`. Also exercised by: `api.CardControllerIT`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/COCRDUPC.cbl | `0000-MAIN` | 367 | `service.CardUpdateService` | `mainPara` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `COMMON-RETURN` | 546 | `service.CardUpdateService` | `commonReturn` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `0000-MAIN-EXIT` | 560 | `service.CardUpdateService` | `mainParaExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `1000-PROCESS-INPUTS` | 564 | `service.CardUpdateService` | `processInputs` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `1000-PROCESS-INPUTS-EXIT` | 575 | `service.CardUpdateService` | `processInputsExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `1100-RECEIVE-MAP` | 578 | `service.CardUpdateService` | `receiveMap` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `1100-RECEIVE-MAP-EXIT` | 638 | `service.CardUpdateService` | `receiveMapExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `1200-EDIT-MAP-INPUTS` | 641 | `service.CardUpdateService` | `editMapInputs` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `1200-EDIT-MAP-INPUTS-EXIT` | 717 | `service.CardUpdateService` | `editMapInputsExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `1210-EDIT-ACCOUNT` | 721 | `service.CardUpdateService` | `editAccount` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `1210-EDIT-ACCOUNT-EXIT` | 758 | `service.CardUpdateService` | `editAccountExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `1220-EDIT-CARD` | 762 | `service.CardUpdateService` | `editCard` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `1220-EDIT-CARD-EXIT` | 802 | `service.CardUpdateService` | `editCardExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `1230-EDIT-NAME` | 806 | `service.CardUpdateService` | `editName` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `1230-EDIT-NAME-EXIT` | 841 | `service.CardUpdateService` | `editNameExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `1240-EDIT-CARDSTATUS` | 845 | `service.CardUpdateService` | `editCardStatus` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `1240-EDIT-CARDSTATUS-EXIT` | 874 | `service.CardUpdateService` | `editCardStatusExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `1250-EDIT-EXPIRY-MON` | 877 | `service.CardUpdateService` | `editExpiryMonth` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `1250-EDIT-EXPIRY-MON-EXIT` | 910 | `service.CardUpdateService` | `editExpiryMonthExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `1260-EDIT-EXPIRY-YEAR` | 913 | `service.CardUpdateService` | `editExpiryYear` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `1260-EDIT-EXPIRY-YEAR-EXIT` | 945 | `service.CardUpdateService` | `editExpiryYearExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `2000-DECIDE-ACTION` | 948 | `service.CardUpdateService` | `decideAction` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `2000-DECIDE-ACTION-EXIT` | 1029 | `service.CardUpdateService` | `decideActionExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `3000-SEND-MAP` | 1035 | `service.CardUpdateService` | `sendMap` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `3000-SEND-MAP-EXIT` | 1048 | `service.CardUpdateService` | `sendMapExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `3100-SCREEN-INIT` | 1052 | `service.CardUpdateService` | `screenInit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `3100-SCREEN-INIT-EXIT` | 1078 | `service.CardUpdateService` | `screenInitExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `3200-SETUP-SCREEN-VARS` | 1082 | `service.CardUpdateService` | `setupScreenVars` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `3200-SETUP-SCREEN-VARS-EXIT` | 1135 | `service.CardUpdateService` | `setupScreenVarsExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `3250-SETUP-INFOMSG` | 1138 | `service.CardUpdateService` | `setupInfoMsg` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `3250-SETUP-INFOMSG-EXIT` | 1165 | `service.CardUpdateService` | `setupInfoMsgExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `3300-SETUP-SCREEN-ATTRS` | 1168 | `service.CardUpdateService` | `setupScreenAttrs` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `3300-SETUP-SCREEN-ATTRS-EXIT` | 1319 | `service.CardUpdateService` | `setupScreenAttrsExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `3400-SEND-SCREEN` | 1324 | `service.CardUpdateService` | `sendScreen` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `3400-SEND-SCREEN-EXIT` | 1338 | `service.CardUpdateService` | `sendScreenExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `9000-READ-DATA` | 1343 | `service.CardUpdateService` | `readData` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `9000-READ-DATA-EXIT` | 1372 | `service.CardUpdateService` | `readDataExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `9100-GETCARD-BYACCTCARD` | 1376 | `service.CardUpdateService` | `getCardByAcctCard` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `9100-GETCARD-BYACCTCARD-EXIT` | 1415 | `service.CardUpdateService` | `getCardByAcctCardExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `9200-WRITE-PROCESSING` | 1420 | `service.CardUpdateService` | `writeProcessing` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `9200-WRITE-PROCESSING-EXIT` | 1494 | `service.CardUpdateService` | `writeProcessingExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `9300-CHECK-CHANGE-IN-REC` | 1498 | `service.CardUpdateService` | `checkChangeInRec` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `9300-CHECK-CHANGE-IN-REC-EXIT` | 1521 | `service.CardUpdateService` | `checkChangeInRecExit` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `ABEND-ROUTINE` | 1531 | `service.CardUpdateService` | `abendRoutine` | `service.CardUpdateServiceTest` |  |
| app/cbl/COCRDUPC.cbl | `ABEND-ROUTINE-EXIT` | 1554 | `service.CardUpdateService` | `abendRoutineExit` | `service.CardUpdateServiceTest` |  |

## COMEN01C

**7 paragraph units.** Legacy authority `app/cbl/COMEN01C.cbl`, 282 lines. The main user menu.

Primary target class `service.MenuService`, primary covering test `service.MenuServiceTest`. Also
exercised by: `api.MenuControllerIT`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/COMEN01C.cbl | `MAIN-PARA` | 75 | `service.MenuService` | `userMenu` | `service.MenuServiceTest` |  |
| app/cbl/COMEN01C.cbl | `PROCESS-ENTER-KEY` | 115 | `service.MenuService` | `processUserMenuEnterKey` | `service.MenuServiceTest` |  |
| app/cbl/COMEN01C.cbl | `RETURN-TO-SIGNON-SCREEN` | 170 | `service.MenuService` | `returnToSignOnScreen` | `service.MenuServiceTest` |  |
| app/cbl/COMEN01C.cbl | `SEND-MENU-SCREEN` | 182 | `service.MenuService` | `sendUserMenuScreen` | `service.MenuServiceTest` |  |
| app/cbl/COMEN01C.cbl | `RECEIVE-MENU-SCREEN` | 199 | `service.MenuService` | `receiveUserMenuScreen` | `service.MenuServiceTest` |  |
| app/cbl/COMEN01C.cbl | `POPULATE-HEADER-INFO` | 212 | `service.MenuService` | `populateHeaderInfo` | `service.MenuServiceTest` |  |
| app/cbl/COMEN01C.cbl | `BUILD-MENU-OPTIONS` | 236 | `service.MenuService` | `buildUserMenuOptions` | `service.MenuServiceTest` |  |

## CORPT00C

**10 paragraph units.** Legacy authority `app/cbl/CORPT00C.cbl`, 649 lines. The report request
screen and the job-submission bridge.

Primary target class `service.ReportRequestService`, primary covering test
`service.ReportRequestServiceTest`. Also exercised by: `api.ReportControllerIT`,
`service.JobSubmissionServiceIT`, `e2e.OnlineTransactionE2ETest`.

The paragraph at line 515 is genuinely spelled `WIRTE-JOBSUB-TDQ` in the source — a transposition of
WRITE. The source column preserves that spelling so the mapping stays findable; the Java method is
spelled correctly. Marked `‡` and recorded as row 6 of the source anomaly register in
[decision-log.md](decision-log.md).

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/CORPT00C.cbl | `MAIN-PARA` | 163 | `service.ReportRequestService` | `mainPara` | `service.ReportRequestServiceTest` |  |
| app/cbl/CORPT00C.cbl | `PROCESS-ENTER-KEY` | 208 | `service.ReportRequestService` | `processEnterKey` | `service.ReportRequestServiceTest` |  |
| app/cbl/CORPT00C.cbl | `SUBMIT-JOB-TO-INTRDR` | 462 | `service.ReportRequestService` | `submitJobToIntrdr` | `service.ReportRequestServiceTest` |  |
| app/cbl/CORPT00C.cbl | `WIRTE-JOBSUB-TDQ` | 515 | `service.ReportRequestService` | `writeJobSubmissionTdq` | `service.ReportRequestServiceTest` | ‡ |
| app/cbl/CORPT00C.cbl | `RETURN-TO-PREV-SCREEN` | 540 | `service.ReportRequestService` | `returnToPrevScreen` | `service.ReportRequestServiceTest` |  |
| app/cbl/CORPT00C.cbl | `SEND-TRNRPT-SCREEN` | 556 | `service.ReportRequestService` | `sendTrnrptScreen` | `service.ReportRequestServiceTest` |  |
| app/cbl/CORPT00C.cbl | `RETURN-TO-CICS` | 585 | `service.ReportRequestService` | `returnToCics` | `service.ReportRequestServiceTest` |  |
| app/cbl/CORPT00C.cbl | `RECEIVE-TRNRPT-SCREEN` | 596 | `service.ReportRequestService` | `receiveTrnrptScreen` | `service.ReportRequestServiceTest` |  |
| app/cbl/CORPT00C.cbl | `POPULATE-HEADER-INFO` | 609 | `service.ReportRequestService` | `populateHeaderInfo` | `service.ReportRequestServiceTest` |  |
| app/cbl/CORPT00C.cbl | `INITIALIZE-ALL-FIELDS` | 633 | `service.ReportRequestService` | `initializeAllFields` | `service.ReportRequestServiceTest` |  |

## COSGN00C

**6 paragraph units.** Legacy authority `app/cbl/COSGN00C.cbl`, 260 lines. Sign-on and the
administrator-versus-user routing split.

Primary target class `service.AuthenticationService`, primary covering test
`service.AuthenticationServiceTest`. Also exercised by: `api.AuthControllerIT`,
`e2e.OnlineTransactionE2ETest`.

The two terminal-write paragraphs point at one method: what they wrote is the result that method
returns, so there is no separate write step to name.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/COSGN00C.cbl | `MAIN-PARA` | 73 | `service.AuthenticationService` | `handle` | `service.AuthenticationServiceTest` |  |
| app/cbl/COSGN00C.cbl | `PROCESS-ENTER-KEY` | 108 | `service.AuthenticationService` | `signOn` | `service.AuthenticationServiceTest` |  |
| app/cbl/COSGN00C.cbl | `SEND-SIGNON-SCREEN` | 145 | `service.AuthenticationService` | `terminalTurn` | `service.AuthenticationServiceTest` |  |
| app/cbl/COSGN00C.cbl | `SEND-PLAIN-TEXT` | 162 | `service.AuthenticationService` | `terminalTurn` | `service.AuthenticationServiceTest` |  |
| app/cbl/COSGN00C.cbl | `POPULATE-HEADER-INFO` | 177 | `service.AuthenticationService` | `screenHeader` | `service.AuthenticationServiceTest` |  |
| app/cbl/COSGN00C.cbl | `READ-USER-SEC-FILE` | 209 | `service.AuthenticationService` | `verifyCredential` | `service.AuthenticationServiceTest` |  |

## COTRN00C

**16 paragraph units.** Legacy authority `app/cbl/COTRN00C.cbl`, 699 lines. The paginated
transaction list, forward and backward.

Primary target class `service.TransactionListService`, primary covering test
`service.TransactionListServiceTest`. Also exercised by: `api.TransactionControllerIT`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/COTRN00C.cbl | `MAIN-PARA` | 95 | `service.TransactionListService` | `listTransactions` | `service.TransactionListServiceTest` |  |
| app/cbl/COTRN00C.cbl | `PROCESS-ENTER-KEY` | 146 | `service.TransactionListService` | `processEnterKey` | `service.TransactionListServiceTest` |  |
| app/cbl/COTRN00C.cbl | `PROCESS-PF7-KEY` | 234 | `service.TransactionListService` | `processPf7Key` | `service.TransactionListServiceTest` |  |
| app/cbl/COTRN00C.cbl | `PROCESS-PF8-KEY` | 257 | `service.TransactionListService` | `processPf8Key` | `service.TransactionListServiceTest` |  |
| app/cbl/COTRN00C.cbl | `PROCESS-PAGE-FORWARD` | 279 | `service.TransactionListService` | `processPageForward` | `service.TransactionListServiceTest` |  |
| app/cbl/COTRN00C.cbl | `PROCESS-PAGE-BACKWARD` | 333 | `service.TransactionListService` | `processPageBackward` | `service.TransactionListServiceTest` |  |
| app/cbl/COTRN00C.cbl | `POPULATE-TRAN-DATA` | 381 | `service.TransactionListService` | `populateTranData` | `service.TransactionListServiceTest` |  |
| app/cbl/COTRN00C.cbl | `INITIALIZE-TRAN-DATA` | 450 | `service.TransactionListService` | `initializeTranData` | `service.TransactionListServiceTest` |  |
| app/cbl/COTRN00C.cbl | `RETURN-TO-PREV-SCREEN` | 510 | `service.TransactionListService` | `returnToPrevScreen` | `service.TransactionListServiceTest` |  |
| app/cbl/COTRN00C.cbl | `SEND-TRNLST-SCREEN` | 527 | `service.TransactionListService` | `sendTrnlstScreen` | `service.TransactionListServiceTest` |  |
| app/cbl/COTRN00C.cbl | `RECEIVE-TRNLST-SCREEN` | 554 | `service.TransactionListService` | `receiveTrnlstScreen` | `service.TransactionListServiceTest` |  |
| app/cbl/COTRN00C.cbl | `POPULATE-HEADER-INFO` | 567 | `service.TransactionListService` | `populateHeaderInfo` | `service.TransactionListServiceTest` |  |
| app/cbl/COTRN00C.cbl | `STARTBR-TRANSACT-FILE` | 591 | `service.TransactionListService` | `startbrTransactFile` | `service.TransactionListServiceTest` |  |
| app/cbl/COTRN00C.cbl | `READNEXT-TRANSACT-FILE` | 624 | `service.TransactionListService` | `readnextTransactFile` | `service.TransactionListServiceTest` |  |
| app/cbl/COTRN00C.cbl | `READPREV-TRANSACT-FILE` | 658 | `service.TransactionListService` | `readprevTransactFile` | `service.TransactionListServiceTest` |  |
| app/cbl/COTRN00C.cbl | `ENDBR-TRANSACT-FILE` | 692 | `service.TransactionListService` | `endbrTransactFile` | `service.TransactionListServiceTest` |  |

## COTRN01C

**9 paragraph units.** Legacy authority `app/cbl/COTRN01C.cbl`, 330 lines. Transaction view.

Primary target class `service.TransactionViewService`, primary covering test
`service.TransactionViewServiceTest`. Also exercised by: `api.TransactionControllerIT`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/COTRN01C.cbl | `MAIN-PARA` | 86 | `service.TransactionViewService` | `mainPara` | `service.TransactionViewServiceTest` |  |
| app/cbl/COTRN01C.cbl | `PROCESS-ENTER-KEY` | 144 | `service.TransactionViewService` | `processEnterKey` | `service.TransactionViewServiceTest` |  |
| app/cbl/COTRN01C.cbl | `RETURN-TO-PREV-SCREEN` | 197 | `service.TransactionViewService` | `returnToPrevScreen` | `service.TransactionViewServiceTest` |  |
| app/cbl/COTRN01C.cbl | `SEND-TRNVIEW-SCREEN` | 213 | `service.TransactionViewService` | `sendTrnviewScreen` | `service.TransactionViewServiceTest` |  |
| app/cbl/COTRN01C.cbl | `RECEIVE-TRNVIEW-SCREEN` | 230 | `service.TransactionViewService` | `receiveTrnviewScreen` | `service.TransactionViewServiceTest` |  |
| app/cbl/COTRN01C.cbl | `POPULATE-HEADER-INFO` | 243 | `service.TransactionViewService` | `populateHeaderInfo` | `service.TransactionViewServiceTest` |  |
| app/cbl/COTRN01C.cbl | `READ-TRANSACT-FILE` | 267 | `service.TransactionViewService` | `readTransactFile` | `service.TransactionViewServiceTest` |  |
| app/cbl/COTRN01C.cbl | `CLEAR-CURRENT-SCREEN` | 301 | `service.TransactionViewService` | `clearCurrentScreen` | `service.TransactionViewServiceTest` |  |
| app/cbl/COTRN01C.cbl | `INITIALIZE-ALL-FIELDS` | 309 | `service.TransactionViewService` | `initializeAllFields` | `service.TransactionViewServiceTest` |  |

## COTRN02C

**18 paragraph units.** Legacy authority `app/cbl/COTRN02C.cbl`, 783 lines. Transaction add.

Primary target class `service.TransactionAddService`, primary covering test
`service.TransactionAddServiceTest`. Also exercised by: `api.TransactionControllerIT`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/COTRN02C.cbl | `MAIN-PARA` | 107 | `service.TransactionAddService` | `mainPara` | `service.TransactionAddServiceTest` |  |
| app/cbl/COTRN02C.cbl | `PROCESS-ENTER-KEY` | 164 | `service.TransactionAddService` | `processEnterKey` | `service.TransactionAddServiceTest` |  |
| app/cbl/COTRN02C.cbl | `VALIDATE-INPUT-KEY-FIELDS` | 193 | `service.TransactionAddService` | `validateInputKeyFields` | `service.TransactionAddServiceTest` |  |
| app/cbl/COTRN02C.cbl | `VALIDATE-INPUT-DATA-FIELDS` | 235 | `service.TransactionAddService` | `validateInputDataFields` | `service.TransactionAddServiceTest` |  |
| app/cbl/COTRN02C.cbl | `ADD-TRANSACTION` | 442 | `service.TransactionAddService` | `addTransaction` | `service.TransactionAddServiceTest` |  |
| app/cbl/COTRN02C.cbl | `COPY-LAST-TRAN-DATA` | 471 | `service.TransactionAddService` | `copyLastTranData` | `service.TransactionAddServiceTest` |  |
| app/cbl/COTRN02C.cbl | `RETURN-TO-PREV-SCREEN` | 500 | `service.TransactionAddService` | `returnToPrevScreen` | `service.TransactionAddServiceTest` |  |
| app/cbl/COTRN02C.cbl | `SEND-TRNADD-SCREEN` | 516 | `service.TransactionAddService` | `sendTrnaddScreen` | `service.TransactionAddServiceTest` |  |
| app/cbl/COTRN02C.cbl | `RECEIVE-TRNADD-SCREEN` | 539 | `service.TransactionAddService` | `receiveTrnaddScreen` | `service.TransactionAddServiceTest` |  |
| app/cbl/COTRN02C.cbl | `POPULATE-HEADER-INFO` | 552 | `service.TransactionAddService` | `populateHeaderInfo` | `service.TransactionAddServiceTest` |  |
| app/cbl/COTRN02C.cbl | `READ-CXACAIX-FILE` | 576 | `service.TransactionAddService` | `readCxacaixFile` | `service.TransactionAddServiceTest` |  |
| app/cbl/COTRN02C.cbl | `READ-CCXREF-FILE` | 609 | `service.TransactionAddService` | `readCcxrefFile` | `service.TransactionAddServiceTest` |  |
| app/cbl/COTRN02C.cbl | `STARTBR-TRANSACT-FILE` | 642 | `service.TransactionAddService` | `startbrTransactFile` | `service.TransactionAddServiceTest` |  |
| app/cbl/COTRN02C.cbl | `READPREV-TRANSACT-FILE` | 673 | `service.TransactionAddService` | `readprevTransactFile` | `service.TransactionAddServiceTest` |  |
| app/cbl/COTRN02C.cbl | `ENDBR-TRANSACT-FILE` | 702 | `service.TransactionAddService` | `endbrTransactFile` | `service.TransactionAddServiceTest` |  |
| app/cbl/COTRN02C.cbl | `WRITE-TRANSACT-FILE` | 711 | `service.TransactionAddService` | `writeTransactFile` | `service.TransactionAddServiceTest` |  |
| app/cbl/COTRN02C.cbl | `CLEAR-CURRENT-SCREEN` | 754 | `service.TransactionAddService` | `clearCurrentScreen` | `service.TransactionAddServiceTest` |  |
| app/cbl/COTRN02C.cbl | `INITIALIZE-ALL-FIELDS` | 762 | `service.TransactionAddService` | `initializeAllFields` | `service.TransactionAddServiceTest` |  |

## COUSR00C

**16 paragraph units.** Legacy authority `app/cbl/COUSR00C.cbl`, 695 lines. The paginated user list.

Primary target class `service.UserManagementService`, primary covering test
`service.UserManagementServiceTest`. Also exercised by: `api.AdminUserControllerIT`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/COUSR00C.cbl | `MAIN-PARA` | 98 | `service.UserManagementService` | `listUsers` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR00C.cbl | `PROCESS-ENTER-KEY` | 149 | `service.UserManagementService` | `processListEnterKey` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR00C.cbl | `PROCESS-PF7-KEY` | 237 | `service.UserManagementService` | `processPf7Key` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR00C.cbl | `PROCESS-PF8-KEY` | 260 | `service.UserManagementService` | `processPf8Key` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR00C.cbl | `PROCESS-PAGE-FORWARD` | 282 | `service.UserManagementService` | `processPageForward` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR00C.cbl | `PROCESS-PAGE-BACKWARD` | 336 | `service.UserManagementService` | `processPageBackward` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR00C.cbl | `POPULATE-USER-DATA` | 384 | `service.UserManagementService` | `populateUserData` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR00C.cbl | `INITIALIZE-USER-DATA` | 446 | `service.UserManagementService` | `initializeUserData` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR00C.cbl | `RETURN-TO-PREV-SCREEN` | 506 | `service.UserManagementService` | `returnToPrevScreen` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR00C.cbl | `SEND-USRLST-SCREEN` | 522 | `service.UserManagementService` | `sendUsrlstScreen` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR00C.cbl | `RECEIVE-USRLST-SCREEN` | 549 | `service.UserManagementService` | `receiveUsrlstScreen` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR00C.cbl | `POPULATE-HEADER-INFO` | 562 | `service.UserManagementService` | `populateHeaderInfo` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR00C.cbl | `STARTBR-USER-SEC-FILE` | 586 | `service.UserManagementService` | `startbrUserSecFile` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR00C.cbl | `READNEXT-USER-SEC-FILE` | 619 | `service.UserManagementService` | `readnextUserSecFile` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR00C.cbl | `READPREV-USER-SEC-FILE` | 653 | `service.UserManagementService` | `readprevUserSecFile` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR00C.cbl | `ENDBR-USER-SEC-FILE` | 687 | `service.UserManagementService` | `endbrUserSecFile` | `service.UserManagementServiceTest` |  |

## COUSR01C

**9 paragraph units.** Legacy authority `app/cbl/COUSR01C.cbl`, 299 lines. Add a user.

Primary target class `service.UserManagementService`, primary covering test
`service.UserManagementServiceTest`. Also exercised by: `api.AdminUserControllerIT`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/COUSR01C.cbl | `MAIN-PARA` | 71 | `service.UserManagementService` | `addUser` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR01C.cbl | `PROCESS-ENTER-KEY` | 115 | `service.UserManagementService` | `processAddEnterKey` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR01C.cbl | `RETURN-TO-PREV-SCREEN` | 165 | `service.UserManagementService` | `returnToPrevScreen` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR01C.cbl | `SEND-USRADD-SCREEN` | 184 | `service.UserManagementService` | `sendUsraddScreen` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR01C.cbl | `RECEIVE-USRADD-SCREEN` | 201 | `service.UserManagementService` | `receiveUsraddScreen` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR01C.cbl | `POPULATE-HEADER-INFO` | 214 | `service.UserManagementService` | `populateHeaderInfo` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR01C.cbl | `WRITE-USER-SEC-FILE` | 238 | `service.UserManagementService` | `writeUserSecFile` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR01C.cbl | `CLEAR-CURRENT-SCREEN` | 279 | `service.UserManagementService` | `clearAddScreen` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR01C.cbl | `INITIALIZE-ALL-FIELDS` | 287 | `service.UserManagementService` | `initializeAddFields` | `service.UserManagementServiceTest` |  |

## COUSR02C

**11 paragraph units.** Legacy authority `app/cbl/COUSR02C.cbl`, 414 lines. Update a user.

Primary target class `service.UserManagementService`, primary covering test
`service.UserManagementServiceTest`. Also exercised by: `api.AdminUserControllerIT`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/COUSR02C.cbl | `MAIN-PARA` | 82 | `service.UserManagementService` | `updateUser` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR02C.cbl | `PROCESS-ENTER-KEY` | 143 | `service.UserManagementService` | `processUpdateEnterKey` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR02C.cbl | `UPDATE-USER-INFO` | 177 | `service.UserManagementService` | `updateUserInfo` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR02C.cbl | `RETURN-TO-PREV-SCREEN` | 250 | `service.UserManagementService` | `returnToPrevScreen` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR02C.cbl | `SEND-USRUPD-SCREEN` | 266 | `service.UserManagementService` | `sendUsrupdScreen` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR02C.cbl | `RECEIVE-USRUPD-SCREEN` | 283 | `service.UserManagementService` | `receiveUsrupdScreen` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR02C.cbl | `POPULATE-HEADER-INFO` | 296 | `service.UserManagementService` | `populateHeaderInfo` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR02C.cbl | `READ-USER-SEC-FILE` | 320 | `service.UserManagementService` | `readUserSecFileForUpdate` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR02C.cbl | `UPDATE-USER-SEC-FILE` | 358 | `service.UserManagementService` | `updateUserSecFile` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR02C.cbl | `CLEAR-CURRENT-SCREEN` | 395 | `service.UserManagementService` | `clearUpdateScreen` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR02C.cbl | `INITIALIZE-ALL-FIELDS` | 403 | `service.UserManagementService` | `initializeUpdateFields` | `service.UserManagementServiceTest` |  |

## COUSR03C

**11 paragraph units.** Legacy authority `app/cbl/COUSR03C.cbl`, 359 lines. Delete a user.

Primary target class `service.UserManagementService`, primary covering test
`service.UserManagementServiceTest`. Also exercised by: `api.AdminUserControllerIT`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/COUSR03C.cbl | `MAIN-PARA` | 82 | `service.UserManagementService` | `deleteUser` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR03C.cbl | `PROCESS-ENTER-KEY` | 142 | `service.UserManagementService` | `processDeleteEnterKey` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR03C.cbl | `DELETE-USER-INFO` | 174 | `service.UserManagementService` | `deleteUserInfo` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR03C.cbl | `RETURN-TO-PREV-SCREEN` | 197 | `service.UserManagementService` | `returnToPrevScreen` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR03C.cbl | `SEND-USRDEL-SCREEN` | 213 | `service.UserManagementService` | `sendUsrdelScreen` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR03C.cbl | `RECEIVE-USRDEL-SCREEN` | 230 | `service.UserManagementService` | `receiveUsrdelScreen` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR03C.cbl | `POPULATE-HEADER-INFO` | 243 | `service.UserManagementService` | `populateHeaderInfo` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR03C.cbl | `READ-USER-SEC-FILE` | 267 | `service.UserManagementService` | `readUserSecFileForDelete` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR03C.cbl | `DELETE-USER-SEC-FILE` | 305 | `service.UserManagementService` | `deleteUserSecFile` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR03C.cbl | `CLEAR-CURRENT-SCREEN` | 341 | `service.UserManagementService` | `clearDeleteScreen` | `service.UserManagementServiceTest` |  |
| app/cbl/COUSR03C.cbl | `INITIALIZE-ALL-FIELDS` | 349 | `service.UserManagementService` | `initializeDeleteFields` | `service.UserManagementServiceTest` |  |

## CSUTLDTC

**2 paragraph units.** Legacy authority `app/cbl/CSUTLDTC.cbl`, 157 lines. The shared
date-validation subprogram, callable from both tiers.

Primary target class `service.DateValidationService`, primary covering test
`service.DateValidationServiceTest`. Also exercised by: `service.DateValidationServiceParityTest`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cbl/CSUTLDTC.cbl | `A000-MAIN` | 103 | `service.DateValidationService` | `a000Main` | `service.DateValidationServiceTest` |  |
| app/cbl/CSUTLDTC.cbl | `A000-MAIN-EXIT` | 152 | `service.DateValidationService` | `a000MainExit` | `service.DateValidationServiceTest` |  |

## CSUTLDPY

**14 paragraph units.** Legacy authority `app/cpy/CSUTLDPY.cpy`, 375 lines. Procedural copybook.
Supplies the date-edit cascade to `COACTUPC`, its only including member.

Primary target class `service.DateValidationService`, primary covering test
`service.DateValidationServiceTest`. Also exercised by: `service.DateValidationServiceBaselineTest`,
`service.DateValidationServiceBoundaryTest`.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cpy/CSUTLDPY.cpy | `EDIT-DATE-CCYYMMDD` | 18 | `service.DateValidationService` | `editDateCcyymmdd` | `service.DateValidationServiceTest` |  |
| app/cpy/CSUTLDPY.cpy | `EDIT-YEAR-CCYY` | 25 | `service.DateValidationService` | `editYearCcyy` | `service.DateValidationServiceTest` |  |
| app/cpy/CSUTLDPY.cpy | `EDIT-YEAR-CCYY-EXIT` | 88 | `service.DateValidationService` | `editYearCcyyExit` | `service.DateValidationServiceTest` |  |
| app/cpy/CSUTLDPY.cpy | `EDIT-MONTH` | 91 | `service.DateValidationService` | `editMonth` | `service.DateValidationServiceTest` |  |
| app/cpy/CSUTLDPY.cpy | `EDIT-MONTH-EXIT` | 145 | `service.DateValidationService` | `editMonthExit` | `service.DateValidationServiceTest` |  |
| app/cpy/CSUTLDPY.cpy | `EDIT-DAY` | 150 | `service.DateValidationService` | `editDay` | `service.DateValidationServiceTest` |  |
| app/cpy/CSUTLDPY.cpy | `EDIT-DAY-EXIT` | 205 | `service.DateValidationService` | `editDayExit` | `service.DateValidationServiceTest` |  |
| app/cpy/CSUTLDPY.cpy | `EDIT-DAY-MONTH-YEAR` | 209 | `service.DateValidationService` | `editDayMonthYear` | `service.DateValidationServiceTest` |  |
| app/cpy/CSUTLDPY.cpy | `EDIT-DAY-MONTH-YEAR-EXIT` | 280 | `service.DateValidationService` | `editDayMonthYearExit` | `service.DateValidationServiceTest` |  |
| app/cpy/CSUTLDPY.cpy | `EDIT-DATE-LE` | 284 | `service.DateValidationService` | `editDateLe` | `service.DateValidationServiceTest` |  |
| app/cpy/CSUTLDPY.cpy | `EDIT-DATE-LE-EXIT` | 323 | `service.DateValidationService` | `editDateLeExit` | `service.DateValidationServiceTest` |  |
| app/cpy/CSUTLDPY.cpy | `EDIT-DATE-CCYYMMDD-EXIT` | 329 | `service.DateValidationService` | `editDateCcyymmddExit` | `service.DateValidationServiceTest` |  |
| app/cpy/CSUTLDPY.cpy | `EDIT-DATE-OF-BIRTH` | 341 | `service.DateValidationService` | `editDateOfBirth` | `service.DateValidationServiceTest` |  |
| app/cpy/CSUTLDPY.cpy | `EDIT-DATE-OF-BIRTH-EXIT` | 370 | `service.DateValidationService` | `editDateOfBirthExit` | `service.DateValidationServiceTest` |  |

## CSSTRPFY

**2 paragraph units.** Legacy authority `app/cpy/CSSTRPFY.cpy`, 85 lines. Procedural copybook.
Supplies the attention-key mapping to five including members — `COACTUPC`, `COACTVWC`, `COCRDLIC`,
`COCRDSLC` and `COCRDUPC`.

Primary target class `util.PfKeyTranslator`, primary covering test `util.PfKeyTranslatorTest`. Also
exercised by: `util.PfKeyTranslatorParityTest`, `util.PfKeyTranslatorBoundaryTest`.

Both units point at one method. The legacy invocation range spans only its own exit label, so it
collapses to a single method with a plain return.

| Source member | Paragraph | Line | Target class | Target method | Covering test | Notes |
| :--- | :--- | ---: | :--- | :--- | :--- | :-: |
| app/cpy/CSSTRPFY.cpy | `YYYY-STORE-PFKEY` | 17 | `util.PfKeyTranslator` | `translate` | `util.PfKeyTranslatorTest` |  |
| app/cpy/CSSTRPFY.cpy | `YYYY-STORE-PFKEY-EXIT` | 80 | `util.PfKeyTranslator` | `translate` | `util.PfKeyTranslatorTest` |  |

## Integrity checks

The count is verified mechanically rather than by eye. A data row is any line beginning with a pipe
followed by a legacy member path, which is true of the 544 rows above and of nothing else on the page —
the census table names members in backticks precisely so that it cannot be counted as data.

```bash
awk 'BEGIN{n=0} /^\| *(app\/cbl|app\/cpy)\//{n++} END{print "rows:", n}' \
  docs/traceability-matrix.md
```

That reports `rows: 544`. **A row count is necessary and is not sufficient**, because a page can carry
544 correctly counted rows and still name a test that cannot reach the method on its row. Five further
properties therefore hold, each a way the page could otherwise go silently wrong, and each asserted by
`e2e.GateVerificationTest` rather than by inspection:

- **Every cell is populated.** No row is missing a target class, a target method or a covering test.
- **Every citation resolves.** All 22 target classes exist under the module's main source tree, every
  method named is declared on the class it is attributed to, and all 22 covering tests plus every
  supplementary suite named in a member lead line exist under the module's test tree.
- **Every covering test can reach the method it is named against.** Rows whose method the delivered
  driver never routes to are the ones a row count cannot vouch for, so they are marked `¶` and carry a
  stronger obligation: the named test must declare the method by name. There are **sixteen** such rows,
  spread across **four** members — `COACTUPC` 6, `COCRDLIC` 4, `COCRDSLC` 4 and `COACTVWC` 2 — and every
  one of the sixteen is asserted individually. An earlier reading of this list recorded six rows and
  attributed all of them to `COACTUPC`, which was the largest member's subtotal mistaken for the whole
  census; the marker legend above has carried sixteen throughout. Count them yourself:

  ```bash
  grep -E '^\| *app/(cbl|cpy)/' docs/traceability-matrix.md | grep -c '¶'      # 16
  grep -E '^\| *app/(cbl|cpy)/' docs/traceability-matrix.md | grep '¶' | cut -d'|' -f2 | sort | uniq -c
  ```
- **Per-member subtotals match the census.** Member by member, including `COACTUPC` 85, `COCRDUPC` 45,
  `COCRDLIC` 39, `COACTVWC` 35, `COCRDSLC` 34, `CSUTLDTC` 2, `CSUTLDPY` 14 and `CSSTRPFY` 2.
- **The estate contains no `SECTION`s.** Every one of the 544 units is a paragraph, so no row is a
  section masquerading as one.

### How the row set was derived

A label was taken to be a paragraph when three conditions held together: the line was not a comment or
continuation, it fell after the procedure-division header of its member, and its label began in Area A
and was a bare name terminated by a period. The two procedural copybooks carry no division header, so
the whole member was scanned under the same Area A rule. Applying that to the checkout reproduced the
per-member census above with no adjustment, which is the evidence that the detection is right; had a
single member disagreed, the detection would have been corrected rather than the number.

### Structural facts that make a unit-to-method mapping verifiable

Three measurements over the same checkout explain why a one-row-per-paragraph mapping is achievable at
all, and each was taken from the source rather than assumed:

- Of **158** `PERFORM … THRU …` ranges in the 28 programs, **142** span at most one intermediate label.
  Those are the paired label-and-terminator idiom and become a method with an early return. The one
  in-member range that genuinely spans further is the US-phone range in `COACTUPC`, invoked at two
  sites, enclosing four intermediate labels; it becomes an explicit ordered cascade.
- The date range in `CSUTLDPY.cpy`, from line 18 to line 329, encloses **ten** intermediate labels and
  likewise becomes an ordered cascade. Translating only its head label would skip every year, month,
  day, combination and calendar check — date validation would appear to work and would validate
  nothing, which is why all fourteen of that copybook's units are listed individually above.
- Of **134** `GO TO` statements, **125** jump forward to an exit label and become a return, and **9**
  jump backward and form loops. Six of the nine are in `CBSTM03A`, and together they form the
  dispatcher that makes that member a state machine rather than a nest of calls.

## Related pages

- [decision-log.md](decision-log.md) — the reasoning behind every divergence, the source anomaly
  register that the `†`, `‡` and `§` markers point into, the decision the `¶` marker rests on, and the
  record of what was deliberately not migrated.
- [architecture.md](architecture.md) — the layers, the package responsibilities and the record layouts
  that the data copybooks map onto.
- [gate-evidence.md](gate-evidence.md) — the recorded evidence for the eight validation gates, of which
  this page is the traceability artefact.
