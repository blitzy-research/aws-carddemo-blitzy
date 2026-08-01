# CardDemo Migration Decision Log

This log records every decision taken while translating the AWS CardDemo mainframe estate to the
`carddemo-java` Spring Boot module where the faithful reproduction of legacy behaviour and the
idiomatic Java answer diverge, together with every place where a requirement outside the estate —
a security constraint, a platform constraint, or a defect in the source itself — forced a
deliberate departure from byte-for-byte fidelity.

It exists so that no divergence in the target is left as an unexplained difference. A reviewer who
finds the Java behaving unlike the COBOL should be able to locate the reason here, decide whether
it was intentional, and see what it cost.

## Provenance

Every claim in this log was verified against the legacy estate at a fixed point, and the estate
itself was never modified by the migration.

| Identifier | Value |
|---|---|
| Analysed commit (SHA) | `7756d895ffeb65f7ea72aaa609e356d9899afcec` |
| Upstream release stamp | `CardDemo_v1.0-15-g27d6c6f-68`, dated 2022-07-19 |
| Legacy estate location | `app/` — read-only reference throughout |
| Target module | `carddemo-java/` — physically disjoint, self-contained |

The upstream release stamp is the trailer comment embedded in every COBOL and JCL member of the
estate. The two identifiers together are the only durable link between the target module and the
source it was derived from, because no COBOL, JCL, BMS, copybook or CSD text is copied into the
target. Traceability is by citation, never by transcription.

## The tie-break rule

One rule decides every entry in this log:

> **Where faithful translation and idiomatic Java conflict, faithful wins, and the divergence is
> recorded here rather than resolved by taste.**

This is what makes the rest of the log coherent. It is why arithmetic truncates instead of
rounding, why an empty paragraph survives as an empty method, why a malformed output literal is
reproduced malformed, and why two fields that the legacy screen decorates but never validates
acquire no validation constraints in the Java DTO.

The rule has exactly one class of exception, and every member of that class appears in section 1:
a requirement from outside the estate that outranks fidelity. In every such case the entry states
what the divergence is, why it was accepted, and — importantly — what observable behaviour it does
**not** change.

## How to read this log

Entries are numbered `DL-nnn` and are stable. Source code, migrations and configuration in the
target module cite this file by path; section 14 indexes every one of those citations back to the
entry that answers it, so a citation can be checked for a real answer rather than assumed to have
one.

Three labels recur:

- **Parity exception** — the target deliberately behaves differently from the legacy system.
- **Preserved anomaly** — the legacy system has a defect; the target reproduces it rather than
  silently correcting it, because correcting it would change observable behaviour.
- **Documented gap** — a weakness inherited from the legacy design that no requirement in scope
  asks to close, and which is therefore recorded rather than closed by unrequested work.

**Two identifier schemes, both live.** Entries carry either a `DL-nnn` or a `D-nn` identifier.
Both schemes are stable and both are cited from the module: the record-layout, byte-parity,
control-flow, enumeration and platform entries are cited as `D-nn` from 52 source and test files,
while the security, external-interface, configuration and toolchain entries are cited as `DL-nnn`.
Neither scheme was renumbered when this log was consolidated, because renumbering would have
broken every citation already written into the code. Where the same decision was recorded
independently under both schemes, the entry carries an *Also recorded as* line naming its twin,
and both identifiers resolve to the same reasoning.

**When an entry is added, and when it is corrected.** An entry is added when — and only when — a
divergence, a conflict or a preserved defect is actually embodied in delivered code or in a
delivered configuration artifact. An entry is never added on the strength of an intention, and an
entry that records an unmet requirement says so in its own text rather than describing the
requirement as though it were satisfied. A comment in the code may cite an entry here; it may not
approve a divergence on its own authority. A new entry takes the next unused identifier in its own
scheme and is filed under the section it belongs to, so identifiers stay stable and every citation
already in the code keeps resolving; identifiers are therefore not strictly ascending within a
section. Where a later delivery makes an earlier entry untrue, the entry is **corrected in place
with the correction labelled** rather than quietly deleted, so a reviewer holding an older copy of
the code can see what changed and why. Three entries carry such a correction: D-13, D-49 and
anomaly 22.

**Delivery status.** The module is delivered incrementally. Each entry names the artifact that
embodies it; an entry whose requirement has no delivered artifact yet says so in its own text.

---

## 1. Security and privacy parity exceptions

Every entry in this section is a deliberate departure from legacy behaviour, accepted because a
requirement outside the estate outranks fidelity. Each states what it does not change.

### DL-001 — Passwords are BCrypt hashes, not eight cleartext characters *(parity exception)*

The legacy user-security record holds an eight-character password in clear text, and the sign-on
program compares the entered value against it directly. The target stores a BCrypt hash and
verifies against that hash.

This is the flagship entry of the log, and it is the one place where the prohibition on
hardcoded and cleartext credentials openly outranks byte-for-byte behavioural faithfulness.
Reproducing the cleartext comparison would have satisfied parity and violated the credential
requirement; the two could not both be honoured.

**What does not change:** the sign-on outcome for every valid and invalid credential, the routing
split between the administrative and main menus driven by the user-type byte, and all seven
message literals the sign-on screen emits. No value from the legacy credential record is restated
anywhere in the module — the seed migration stores hashes, never the literal it hashed.

*Cited by:* `domain/UserSecurity.java`, `api/dto/SignOnRequest.java`, `application.yml`,
`api/dto/SignOnRequestTest.java`.

### DL-002 — The credential column accepts only a well-formed BCrypt digest *(parity exception)*

DL-001 establishes that the stored value is a hash. That is worth nothing if the entity will also
accept a cleartext string, so the credential entity refuses any value that is not shaped like a
BCrypt digest at the declared cost, and there is no public accessor that writes or reads a raw
password.

A guard was chosen over a silently-hashing setter deliberately: a setter that hashed whatever it
was given would make a caller that passed cleartext *look* correct, and the defect would surface
only as an unverifiable credential much later. Rejecting the value names the caller's mistake at
the point the mistake is made.

The accessor renames this required are safe because the persistence annotations sit on the fields
rather than on the accessors, so the provider uses field access and no mapping depends on an
accessor name.

**What does not change:** no record image, no field offset and no output byte.

*Cited by:* `domain/UserSecurity.java`.

### DL-003 — Password hashing itself lives outside this module's scope

The controlled create-and-update path that turns a submitted password into a digest belongs to the
authentication and user-management services, which the migration plan assigns elsewhere. This
module therefore publishes the *invariant* — only a well-formed digest may be stored — and leaves
the encoder wiring to the owning services. This is a scope boundary, not an unresolved item: a
DTO or an entity is the wrong place to own a password encoder.

*Cited by:* `api/dto/SignOnRequestTest.java`.

### DL-004 — The submitted password is inbound-only and never serialised *(parity exception)*

The sign-on request carries a password inbound. It is excluded from serialisation, so no response
body, no log line and no diagnostic rendering of the request can echo the credential back. The
legacy 3270 contract had no serialised representation of the request at all, so there is no legacy
behaviour to preserve here — the divergence is that the REST contract *could* have echoed it and
deliberately does not.

*Cited by:* `api/dto/SignOnRequest.java`, `api/dto/SignOnRequestTest.java`.

### DL-005 — The national identifier and the government-issued identifier are encrypted at rest *(parity exception)*

The legacy customer record carries two regulated identity values in clear text: a nine-digit
national identifier and a government-issued identifier. Neither is stored in that form in the
target. Both are sealed into an authenticated, versioned envelope — AES-256 in Galois/Counter
Mode — before they reach a column.

Encrypting the national identifier is an explicit requirement of the migration plan. The
government-issued identifier was brought under the same protection because it is the same class of
value stored the same way, and protecting one while leaving the other verbatim would have been an
arbitrary line. Doing so required widening its column from the legacy field width to accommodate
the envelope; the *record image* the mapper reads is unchanged, only the database column.

**What does not change:** no record image, no field offset and no output byte. The envelope exists
only between the application and the database.

*Cited by:* `domain/Customer.java`, `application.yml`,
`db/migration/V1__create_schema.sql`, `service/SensitiveFieldEncryptionService.java`.

### DL-006 — The customer entity fails closed on cleartext rather than converting it

Two designs could have kept cleartext out of the protected columns: an attribute converter applied
by the persistence provider, or a guard in the entity that rejects anything not already sealed.
The guard was chosen.

An auto-applied converter over `String` would have captured every string attribute on every
entity, which is far too broad. A per-field converter would have needed the encryption key at
converter construction time, which puts secret material into the provider's object graph. The
guard keeps the key in one injectable service and makes the failure mode loud: a value that is not
already sealed is refused, so cleartext cannot reach the database *through application code*.

The limit of that guarantee is stated honestly in DL-071.

*Cited by:* `domain/Customer.java`.

### DL-007 — Encryption is randomised, so the protected columns cannot be searched by equality

Authenticated encryption with a fresh nonce per value means two encryptions of the same input
differ. Equality search over either protected column is therefore impossible.

This costs the estate nothing, and that is a finding rather than an assumption: the legacy design
defines no alternate index, no browse and no screen lookup over either identifier, so no access
path is lost. Had one existed, this decision would have had to be revisited rather than accepted.

*Cited by:* `db/migration/V1__create_schema.sql`.

### DL-008 — The encryption key is bound per profile with no fallback anywhere

No key is declared in the shared configuration and none is defaulted — not an empty value, not a
placeholder. The overlays bind it: a throwaway value locally and in tests, and a no-fallback
environment reference in production. A missing key therefore aborts start-up instead of quietly
sealing data under something accidental.

A consequence worth stating plainly: a deployment that changes the key can no longer read what the
previous key sealed. That is a property of authenticated encryption rather than a defect, and it is
the reason the value belongs in managed configuration rather than in a file in the repository.

*Cited by:* `service/SensitiveFieldEncryptionService.java`, `application.yml`.

### DL-009 — The cryptographic primitives are referenced without importing the legacy namespace

The module holds a hard constraint of zero imports from the pre-Jakarta `javax` namespace. The
Java cryptography primitives this module needs still live under that namespace. The two were
reconciled by naming those types fully qualified at their use sites and catching only the
`java.security` exception supertype, so the codec compiles with no import from that namespace at
all and the module-wide constraint survives literally rather than by exception.

This is a technique note rather than a behavioural divergence, recorded because it looks unusual on
first reading and would otherwise invite an "improving" edit that reintroduces the import.

*Cited by:* `util/SensitiveFieldCodec.java`.

### DL-010 — The primary account number and the verification code are **not** protected *(documented gap)*

The legacy design applies no field-level encryption, tokenisation or masking to either the primary
account number or the card verification code, and no requirement in scope introduces one. None is
invented here, because that would be feature expansion beyond the estate.

The gap is carried forward as an explicit finding rather than silently closed or silently ignored.
It is stated in this log precisely so that a future reviewer does not mistake its absence from the
code for an oversight, and so that a decision to close it is taken deliberately.

*Cited by:* `db/migration/V1__create_schema.sql`.

*Also recorded as:* D-14 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-011 — Diagnostic renderings withhold identity and monetary values *(parity exception)*

Four request and context types render themselves for diagnostics. Each withholds the components
that would turn a log line into a disclosure: account identifiers, card numbers, customer
identifiers, customer names, monetary amounts, postal codes and dates. The legacy system had no
equivalent rendering — its only diagnostic channel was a screen display — so again there is no
legacy behaviour being broken, only a REST-era capability deliberately not exercised.

Two design points are deliberate. First, the withheld components are replaced by a **constant**
placeholder rather than a partial mask or a length-preserving digest, so a rendering leaks neither
the value nor its shape; two records differing only in a withheld component render identically.
Second, the retained components are chosen to keep the rendering useful — a navigation context
still shows its transaction and program identifiers and the account *status*, because those are
what a support engineer actually needs and none of them identifies a person.

A direct consequence, asserted in the tests: a rendering can never be used as an equality proxy,
because two unequal records may render identically.

*Cited by:* `api/dto/ScreenWorkArea.java`, `api/dto/AccountUpdateRequest.java`.

*Also recorded as:* D-16 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-012 — Relational isolation plus optimistic locking is **stronger** than the legacy baseline *(improvement, recorded so it is not misread)*

Every application file in the legacy CICS definition is declared with uncommitted read integrity,
no recovery and no journalling; correctness rests entirely on a locking update model plus each
program's own before-and-after image comparison. The target uses PostgreSQL read-committed
isolation plus a JPA version check.

This is recorded explicitly because it is an *improvement*, and an improvement is exactly the kind
of change a reviewer can mistake for a behavioural regression. Concurrent updates that the legacy
system would have silently interleaved are now rejected with a conflict. The estate's single
rollback point is preserved as a transactional rollback raising that conflict.

*Cited by:* `domain/Account.java`, `exception/OptimisticLockConflictException.java`,
`db/migration/V1__create_schema.sql`, `exception/OptimisticLockConflictExceptionTest.java`.

*Also recorded as:* D-15 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-12 — Credential storage: BCrypt digest format, verifier NOT YET DELIVERED
`SEC-USR-PWD PIC X(08)` in `app/cpy/CSUSR01Y.cpy` holds the credential as eight cleartext characters
at offset 48, and `app/cbl/COSGN00C.cbl` compares it directly against the entered value.
Reproducing that would satisfy parity and breach the no-cleartext-credential constraint at the same
time. **Decision:** the credential column is sized 60 to hold a BCrypt digest — never the legacy
width of 8, and never a cleartext value — and no component may store or compare a cleartext
credential.
**Status: the format is fixed; the control is not implemented.** No password encoder, authentication
service, security configuration or sign-on path exists in the module yet, and no seed script exists.
Whatever component later reads the credential table inherits the obligation stated above. This entry
records an unmet requirement, not a delivered control.
*Embodied in:* `src/main/resources/db/migration/V1__create_schema.sql` (column shape only),
`domain/UserSecurity.java`, `api/dto/SignOnRequest.java`.

*Also recorded as:* DL-001, DL-002, DL-003 and DL-004, which record the same position from the
other side. The digest-format invariant is delivered and enforced by the credential entity; the
encoder and the verifying comparison belong to the authentication and user-management services
that own the create-and-update path. The two framings — "not yet delivered" here and "a scope
boundary" in DL-003 — state one fact: no password encoder and no verifying comparison exists
anywhere in the module as delivered, and the stored-shape invariant is what stands in for one
until it does.

### D-13 — Customer national identifier: encrypted at rest — CORRECTED

**Correction.** An earlier reading of this entry recorded encryption at rest for the national
identifier as a requirement this module did not yet satisfy. That is no longer true. The entry is
corrected here rather than deleted, so the change is visible to a reviewer who read the earlier
text.

The legacy customer record carries a nine-digit national identifier and a government-issued
identifier in clear text. Both are now sealed before they reach a column — AES-256 in
Galois/Counter Mode, in an authenticated and versioned envelope — and the customer entity refuses a
value that is not already sealed rather than sealing it silently on the way past. The
government-issued identifier was brought under the same protection because it is the same class of
value stored the same way, and protecting one while leaving the other verbatim would have been an
arbitrary line.

**What does not change:** no record image, no field offset and no output byte. The envelope exists
only between the application and the database. A transport record that carries either value inbound
— the account-update screen contract does, because the legacy screen does — carries it unsealed on
the wire, never logs it, and never persists it from there.

*Embodied in:* `service/SensitiveFieldEncryptionService.java`, `util/SensitiveFieldCodec.java`,
`domain/Customer.java`, `resources/db/migration/V1__create_schema.sql`.

*Also recorded as:* DL-005, DL-006, DL-007, DL-008 and DL-009, which develop the same decision in
detail — the fail-closed entity guard, the equality-search capability that randomised encryption
costs, the per-profile key binding with no fallback anywhere, and the namespace rule the
cryptographic primitives are referenced under.

### D-14 — Card primary account number and verification code: gap UNCLOSED
The legacy design applies no field-level encryption, tokenisation or masking to either value, and no
requirement in scope introduces one. **Decision:** none is invented, because that would be feature
expansion. The gap is carried forward here as an explicit unclosed finding rather than silently
closed or silently ignored.
*Recorded against:* `src/main/resources/db/migration/V1__create_schema.sql`.

*Also recorded as:* DL-010 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-15 — Isolation and optimistic locking: a strict improvement
Every application file definition in `app/csd/CARDDEMO.CSD` specifies `READINTEG(UNCOMMITTED)`,
`RECOVERY(NONE)` and `JOURNAL(NO)`, with correctness resting solely on `UPDATEMODEL(LOCKING)` plus
each program's own comparison of the before image against the after image. **Decision:** PostgreSQL
READ COMMITTED, declarative foreign keys and a JPA `@Version` column on the account and card
entities are together strictly stronger than that baseline. This is recorded explicitly so a
reviewer reads the stronger guarantee as the improvement it is and does not mistake it for a
behavioural change; the migrated business logic is unchanged.
*Embodied in:* `V1__create_schema.sql`, `V2__create_indexes.sql`, `domain/Account.java`,
`exception/OptimisticLockConflictException.java`.

*Also recorded as:* DL-012 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-16 — A diagnostic never carries the value it rejected
**Decision:** rejection diagnostics report the digit count required and the width available, the
offset and length, or the field name — never the value itself — so that a rejection cannot leak
account data into a log.
*Embodied in:* `util/ZonedDecimalCodec.java`, `util/FixedWidthFieldReader.java`.

*Also recorded as:* DL-011 and DL-041 — the same decision, recorded independently under the other
identifier scheme. Both identifiers are cited from the module and both resolve here.

---

## 2. Arithmetic, representation and rounding

### DL-013 — Monetary arithmetic truncates; it never rounds

A search for the `ROUNDED` keyword across every program and copybook in the estate returns zero
occurrences. Not one arithmetic statement specifies rounding, and a COBOL store without that
keyword truncates toward zero. Every receiving field in the financial paths holds two decimals.

The target therefore applies `RoundingMode.DOWN` uniformly at scale two, and never the
conventional `HALF_EVEN`. The difference is not cosmetic: half-even would differ by one cent on
roughly half of all interest computations, which is a byte-parity failure — and one that is
invisible to a test suite written under the same wrong assumption. This is why the decision is
recorded as a constraint rather than left to a translator's judgement.

*Cited by:* `util/ZonedDecimalCodecTest.java`.

*Also recorded as:* D-02 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-014 — No `COMPUTE` expression is algebraically rearranged

Truncation makes arithmetic non-associative, so operand order is contractual. The monthly-interest
computation multiplies the balance by the rate and only then divides; dividing the rate first is
algebraically identical in exact arithmetic and moves the truncation point. The overlimit basis is
evaluated strictly left to right and is what decides which transactions are rejected.

Both are reproduced operand for operand. No expression in the module is simplified, reassociated
or factored.

*Cited by:* `util/ZonedDecimalCodecTest.java`.

*Also recorded as:* D-03 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-015 — There is no packed-decimal decoder, because no packed field is ever written

The construct mapping anticipated packed decimal. In this estate the packed usage appears exactly
once, on a screen work field that is never persisted; every persisted monetary and rate field is
zoned decimal under display usage. The target therefore implements a zoned-decimal codec handling
the overpunched trailing-byte sign convention and implements no binary-coded-decimal decoder at
all.

Writing one would have been dead code, and its absence is recorded so that it does not read as an
omission.

*Cited by:* `util/ZonedDecimalCodecTest.java`.

*Also recorded as:* D-01 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-016 — A malformed numeric image is rejected rather than coerced

A zoned image that does not conform raises rather than silently yielding zero or a best-effort
value. Coercion would let a corrupt input post as a real amount, which no legacy path does: the
legacy programs abend on an unexpected file status rather than continuing with a guessed value.
Rejecting is therefore both the safe answer and the faithful one.

*Cited by:* `util/ZonedDecimalCodecTest.java`.

### DL-017 — Business keys are the primary keys; no surrogate identifier is introduced

Each legacy record declares its key as the leading substring of the record image, and the cluster
definitions state the key length and offset explicitly. The target uses that same business key as
the primary key, and three composite keys become composite identifier classes.

A generated surrogate key would have broken the correspondence between the record image and the
table row on which byte-level output parity depends.

*Also recorded as:* D-29 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-018 — Transaction identifiers are highest-existing-key plus one, not a database sequence

The bill-payment program does not use a sequence. It browses backward from the high value to the
highest existing identifier, adds one, and seeds to one when the file is empty. The target
reproduces that within the same transaction.

A database sequence would diverge permanently after any gap, and gaps are guaranteed the first
time a transaction rolls back.

*Also recorded as:* D-28 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-01 — Zoned decimal, not packed
Every persisted monetary and rate field in the estate is zoned decimal under `USAGE DISPLAY`:
`ACCT-CURR-BAL`, `ACCT-CREDIT-LIMIT`, `ACCT-CASH-CREDIT-LIMIT`, `ACCT-CURR-CYC-CREDIT` and
`ACCT-CURR-CYC-DEBIT` at `PIC S9(10)V99` in `app/cpy/CVACT01Y.cpy`; `TRAN-AMT` and `DALYTRAN-AMT` at
`PIC S9(09)V99`; `TRAN-CAT-BAL` at `PIC S9(09)V99`; `DIS-INT-RATE` at `PIC S9(04)V99`. `COMP-3`
occurs zero times in the copybook tree, and its nine declaration sites in the program tree are all
working-storage counters, indexes, intermediate arithmetic fields and one screen work field — none is
part of a persisted record. **Decision:** no packed-decimal decoder is built. The single conversion point is
an overpunched-sign zoned-decimal codec, in which the trailing byte carries both the low-order digit
and the sign.
*Embodied in:* `util/ZonedDecimalCodec.java`.

*Also recorded as:* DL-015 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-02 — Truncation, not rounding
A search for `ROUNDED` across all 28 programs and all 28 copybooks returns zero occurrences. A COBOL
arithmetic store without `ROUNDED` truncates toward zero. **Decision:** every scale operation uses
`RoundingMode.DOWN`. The conventional Java choice, `RoundingMode.HALF_EVEN`, would differ by one
cent on roughly half of all interest computations, and the difference is invisible to a test suite
written under the same wrong assumption. No service performs its own scaling; the codec is the only
place scale is applied, so an inconsistent policy cannot creep in.
*Embodied in:* `util/ZonedDecimalCodec.java`.

*Also recorded as:* DL-013 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-03 — Expression order preserved literally
Truncation makes arithmetic non-associative, so operand order is contractual.
`WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200` in `app/cbl/CBACT04C.cbl` multiplies first
and divides second; dividing the rate by 1200 first is algebraically identical in exact arithmetic
and moves the truncation point. `WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT +
DALYTRAN-AMT` in `app/cbl/CBTRN02C.cbl` evaluates strictly left to right and is the basis for the
overlimit reject, so a reordering changes which transactions are rejected. **Decision:** no
`COMPUTE` expression is algebraically rearranged.
*Binding on future work;* the two computing programs are not yet delivered.

*Also recorded as:* DL-014 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-04 — Negative zero collapses to positive zero
The byte image distinguishes a trailing `}` (negative, low-order digit zero) from `{` (positive,
low-order digit zero), but `BigDecimal` has no negative zero. **Decision:** both images decode to
zero at the requested scale, and encoding a zero value always emits `{`. The asymmetry is observable
only for a field whose *every* digit is zero, where the arithmetic value is identical either way. It
is not observable for an ordinary negative amount whose cent digit happens to be zero: such a value
ends in `}` and re-encodes to `}`.
*Embodied in:* `util/ZonedDecimalCodec.java`.

### D-05 — A wrong scale is rejected, never re-scaled — DIVERGENCE
The legacy `MOVE` into a `V99` field silently re-scales. **Decision:** a value arriving at any scale
other than the field's declared scale is rejected. A formatter or codec that re-scaled would
introduce a second rounding policy alongside D-02, and a truncation-policy violation could then hide
inside a formatter. Valid data cannot produce the situation, so the rejection is unreachable for
correct callers.
*Embodied in:* `util/ZonedDecimalCodec.java`, `util/ReportLineFormatter.java`,
`util/StatementTextTemplates.java`.

---

## 3. Record-boundary and `MOVE` divergences

Each entry in this section is a place where the module deliberately fails where the legacy silently
continued. The shared reason is stated once: a legacy field could not have held the offending value,
so silently trimming it to fit produces a plausible wrong output — a wrong amount in a financial
statement, a wrong date in a submitted job card — where failing produces a diagnosable defect. None
of these conditions is reachable from valid data.

### D-06 — An over-length value is rejected, never truncated — DIVERGENCE
Legacy `MOVE` truncates on the right for alphanumeric fields and on the left for numeric fields.
**Decision:** rejection with a diagnostic naming the legacy field, its width and the length
supplied.
*Embodied in:* `exception/AbendException.java` (context values bounded to `ABEND-MSG` and the other
`CSMSG02Y` field widths), `util/ZonedDecimalCodec.java` (an integer part too large for the field),
`util/ReportLineFormatter.java` and `util/StatementTextTemplates.java` (an integer part exceeding
nine digits in a numeric-edited mask), `util/JclCardImageBuilder.java` (a date substitution slot
wider than its ten-character frame).

### D-07 — `null` becomes the empty string — DIVERGENCE
Legacy working-storage fields are space-initialised and therefore never absent, so the source has no
concept corresponding to `null`. **Decision:** a `null` context value becomes the empty string
rather than raising, because the absence of an optional diagnostic value must not itself become a
failure on the abend path. This applies to diagnostic context only. Printed report text is the
opposite case: a `null` description there is rejected, because a missing description in a printed
report is a defect worth surfacing.
*Embodied in:* `exception/AbendException.java`, `util/ReportLineFormatter.java`.

### D-08 — A short or long record is rejected, never padded — DIVERGENCE
VSAM and QSAM records are fixed length by construction, so a record of the wrong length has no
legacy antecedent at all. **Decision:** the condition is a programming defect in the caller and is
reported as `IllegalArgumentException`, naming the artefact, the expected encoded width and the
actual encoded byte length. Input is never silently padded, never silently truncated, never
partially returned and never returned as `null`.
*Embodied in:* `util/FixedWidthFieldReader.java`.

### D-09 — Non-printable input is rejected in printed output — DIVERGENCE
**Decision:** characters outside the printable US-ASCII range are rejected in report and statement
text. This keeps one encoded byte per character, which the fixed widths of 80, 100, 133 and 430
bytes depend on, and structurally prevents a control character or a line terminator from entering a
record.
*Embodied in:* `util/ReportLineFormatter.java`, `util/StatementTextTemplates.java`,
`util/StatementHtmlTemplates.java`.

### D-10 — The filler byte is not uniform in the estate; the module emits space filler
See anomaly 20. `COBOL FILLER X(n)` with no `VALUE` clause is uninitialised, and the sample data
shows exactly the divergence that implies: the four master files carry space filler (178 bytes per
account row, 59 per card row, 168 per customer row, 20 per daily-transaction row) while the four
reference-table files carry ASCII-zero filler (28 per disclosure-group row, 22 per category-balance
row, 8 per transaction-type row, 4 per transaction-category row). The cross-reference file carries no
filler at all, because its text stride is 36 rather than the 50-byte cluster record length.
**Decision:** neither byte value is canonical, so the module-wide default is space filler — a
builder buffer is space-initialised before any placement, so every byte a mapper does not write
emerges as a space — and round-trip assertions compare only the mapped data prefix. A mapper that
must reproduce a different filler byte states that byte explicitly at the call site where the filler
run belongs, so the default is a default and not a normalisation the mapper cannot escape.
*Embodied in:* `util/FixedWidthFieldReader.java`.

### D-11 — Malformed input uses `IllegalArgumentException`, not a module exception type
**Decision:** none of the module's own exception types models "the caller handed me 297 bytes instead
of 300". The abend exception is the terminal abend path; the file-status exception carries a raw
legacy two-byte status; the record-not-found exception is the legacy not-found status; the validation
exception is business field validation carrying screen field identifiers; the optimistic-lock
exception is the image-comparison conflict; the job-submission exception is the queue-write failure.
A malformed record is a caller defect, and `IllegalArgumentException` is its idiomatic signal.
*Embodied in:* `util/FixedWidthFieldReader.java`.

### D-30 — A fixed-width image carries no line terminator
The legacy source files have CRLF endings and the sample data files are newline-terminated, so a
text fixture's stride is the record width plus one. **Decision:** the image a formatter or mapper
produces is exactly the record width, with no terminator; record separation is the writer's concern
in the batch layer. This is what makes the stride arithmetic of the sample files a property of the
file rather than of the record.
*Embodied in:* `util/ReportLineFormatter.java`, `util/StatementTextTemplates.java`,
`util/StatementHtmlTemplates.java`, `util/FixedWidthFieldReader.java`.

---

## 4. Control flow, string and lookup semantics

### DL-019 — A range that spans intermediate paragraphs becomes an ordered cascade, not one call

Most `PERFORM ... THRU` ranges in the estate span at most one intermediate label and are the
conventional paired name-and-exit idiom, which becomes a private method with an early return.
Three ranges genuinely span intermediate paragraphs and are reproduced as explicit ordered
cascades.

The most consequential is the date-validation range, which spans eleven intermediate paragraphs
covering the year, month, day, combination and runtime checks. Translating only the head paragraph
— the obvious reading for anyone who has not measured the span — would silently skip every one of
those checks. Date validation would appear to work and would validate nothing.

### DL-020 — The statement generator is a state machine, not a nested call chain

The statement program is a hand-rolled dispatcher: a work field holds a name that selects the next
phase, and control returns to the dispatcher by a backward jump after each phase, whereupon the
dispatcher re-branches on the new value. Six of the estate's nine backward jumps belong to it.

The target uses an explicit state enum driven by a loop over a switch. Nested method calls cannot
reproduce re-entry into a dispatcher after a state change, which makes this the single most
consequential structural decision in the batch tier.

### DL-021 — Clause evaluation order is preserved verbatim

Multi-way selections become switches with clause order preserved, because the legacy construct
evaluates top-down and stops at the first match; reordering clauses changes behaviour whenever
conditions overlap. Validation cascades likewise run in source order, and a failure
short-circuits at the first rejection exactly as the legacy does.

### DL-022 — The alphabetic check accepts embedded spaces

The legacy alphabetic-only check blanks every letter and then tests whether anything remains, which
means **embedded spaces pass**. The faithful predicate is therefore "every character is a letter or
a space", not "every character is a letter".

The idiomatic Java one-liner would reject a value such as a two-part given name that the legacy
system accepts, breaking existing data. Recorded because the idiomatic answer is both shorter and
wrong.

*Also recorded as:* D-17 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-023 — Upper-casing uses an ASCII-only fold, not the platform method

The embossed-name upper-casing is a strict twenty-six-character table substitution. The platform
upper-case method is locale-sensitive and Unicode-aware and transforms characters that table leaves
untouched. The target folds character by character over ASCII only.

*Also recorded as:* D-18 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-024 — An unrecognised attention key yields an absent result, never a synthetic constant

The key-action vocabulary defines no synthetic `UNKNOWN`, `NONE`, `OTHER`, `INVALID`, `UNMAPPED` or
`DEFAULT` constant. Such a constant would manufacture a state the legacy system cannot produce, and
would discard the retained value the legacy system relies on: when the key is unrecognised the
legacy caller keeps whatever action it was already holding.

Absence is modelled explicitly by an empty optional result, which leaves the caller free to do
exactly that. Note that this is a *distinct* outcome from a null argument, which is a caller defect
and raises; collapsing the two would let a programming error masquerade as a retained stale action.

*Cited by:* `domain/enums/KeyAction.java`.

*Also recorded as:* D-20 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-025 — Program-function keys 13 through 24 are not distinct actions

The legacy key-handling copybook folds the upper twelve function keys back onto the same twelve
flags as the lower twelve. The target reproduces the fold, so those keys are deliberately not
separate actions. An implementation that gave them distinct meanings would invent twelve
behaviours the 3270 contract never had.

*Also recorded as:* D-20 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-026 — Two validation-flag characters are excluded from the account-status vocabulary

The legacy condition-name group lists two validation-flag characters alongside the two real status
codes. The target's status vocabulary carries only the two real codes, because the flag characters
are screen-edit state rather than persisted status values, and admitting them would let a
transient edit flag be stored as an account status.

Consistently with DL-024, an unmapped code yields an empty result rather than the exception an
idiomatic lookup would raise.

*Cited by:* `domain/enums/AccountStatusTest.java`.

*Also recorded as:* D-24 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-027 — Two reject reasons carry identical description text under distinct codes *(preserved anomaly)*

Two of the five reject reason codes carry the same description text. Every tempting cleanup —
mapping one code to the other, having one delegate to the other, or hiding a single constant behind
two names — would compile, would pass a naive test, and would silently stop emitting one of the
five codes the 430-byte reject trailer is contractually required to carry. Neither description may
be reworded to remove the duplication either, because the text is contractual output.

One consequence follows directly: any index over these constants must be keyed by code, never by
description.

*Cited by:* `domain/enums/RejectReason.java`.

*Also recorded as:* D-23 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-028 — Two account-update fields are decorated for error display but never validated *(preserved anomaly)*

The account-update program decorates thirty-nine fields for error display. For two of them — the
middle name and the second address line — the source states in terms that no edits are coded, and
none are.

The Java request DTO therefore attaches **no validation constraints** to those two fields. Adding
them would be an unrequested behaviour change that rejects input the legacy system accepts. This is
the clearest small case of the tie-break rule: the idiomatic instinct is to validate every field,
and following it would break parity.

*Cited by:* `api/dto/AccountUpdateRequest.java`.

*Also recorded as:* D-34 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-029 — The telephone all-blank shortcut tests the wrong sub-field *(preserved anomaly)*

The telephone validation cascade contains a genuine defect: its all-blank shortcut tests the
area-code sub-field where it should test the line-number sub-field. A submission with a blank area
code, a blank prefix and a populated line number is therefore silently treated as "no telephone
supplied".

The defect is reproduced rather than corrected, because correcting it would reject submissions the
legacy system accepts. It is recorded here so that the behaviour is understood as deliberate.

*Cited by:* `api/dto/AccountUpdateRequest.java`.

*Also recorded as:* D-25 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-030 — Four abandoned work-area members are not modelled at all

A mechanical scan of every program in the estate finds zero references to four members of the
screen work area, and zero references to any of their condition names, which corroborates that they
were abandoned rather than merely left unused. The target declares none of them, and in particular
declares no on/off state for the abandoned return flag.

Inventing a boolean that nothing would ever set would fabricate a state transition the 3270
contract never had. Absence is the faithful answer.

*Cited by:* `api/dto/ScreenWorkArea.java`.

*Also recorded as:* D-40 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-031 — The paragraph count for the largest program is a counting-convention artefact

The migration plan records eighty-five procedure paragraphs for the account-update program while an
execution brief records eighty-eight. A strict count of Area-A labels below the procedure division
header reproduces the plan's eighty-five, so the difference is a counting convention rather than
missing work. Recorded so the discrepancy is not read as three untranslated paragraphs.

*Cited by:* `api/dto/AccountUpdateRequest.java`.

### D-17 — The alphabetic check admits embedded spaces
The source idiom blanks every letter with `INSPECT ... CONVERTING` and then tests that the trimmed
remainder is empty, so a value containing a space passes. **Decision:** the predicate is "every
character is a letter or a space", not `chars().allMatch(Character::isLetter)`. A naive translation
would reject a value such as a two-part given name that the legacy system accepts, breaking existing
data.
*Embodied in:* `util/CobolStringUtils.java`.

*Also recorded as:* DL-022 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-18 — Upper-casing is an ASCII-only 26-character fold
The embossed-name fold in `app/cbl/COCRDUPC.cbl` is a strict 26-character table substitution.
**Decision:** `String.toUpperCase()` is forbidden here. It is locale-sensitive and Unicode-aware, can
transform characters the table leaves untouched, and can change the length of the result — which
matters because the folded value is written back into a fixed 50-byte field.
*Embodied in:* `util/CobolStringUtils.java`.

*Also recorded as:* DL-023 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-19 — The menu option lexeme is right-justified and zero-filled
`INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'` over a `PIC X(02) JUST RIGHT` field turns a
single-digit entry into a zero-filled two-digit value. **Decision:** reproduced as a named primitive
rather than as an incidental `String.format`, so the behaviour is separately testable.
*Embodied in:* `util/CobolStringUtils.java`.

### D-20 — Attention keys 13 to 24 fold onto 1 to 12; an unrecognised key yields no action
`app/cpy/CSSTRPFY.cpy` maps `DFHPF13` through `DFHPF24` onto the same twelve flags as `DFHPF1`
through `DFHPF12`, so 28 recognised inputs yield 16 distinct actions, and the copybook has no
fallback clause — an unrecognised attention key left the action field unchanged. **Decision:** the
fold is reproduced and the high keys receive no constants of their own; an unrecognised key produces
an empty `Optional`. No synthetic default constant is introduced, no `null` is returned and no
exception is raised. A `switch` `default` clause is permitted; a `DEFAULT` enum constant is not. The
fold is written as twelve explicit arms rather than computed arithmetically, so it is auditable by
eye and cannot silently accept a key the source does not recognise.
*Embodied in:* `util/PfKeyTranslator.java`, `domain/enums/KeyAction.java`.

*Also recorded as:* DL-024 and DL-025 — the same decision, recorded independently under the other
identifier scheme. Both identifiers are cited from the module and both resolve here.

### D-23 — Reject reasons 101 and 109 share description text and stay distinct codes
`app/cbl/CBTRN02C.cbl` raises 101 when the account read finds no match and 109 when the account
rewrite finds no match at the end of posting, carrying the same description text under two different
codes. **Decision:** the two are never folded together. The four-digit code is what the 430-byte
reject record actually carries, and a reader of that record distinguishes a failed read from a
failed rewrite by the code alone.
*Embodied in:* `domain/enums/RejectReason.java`, `V2__create_indexes.sql` (decision A).

*Also recorded as:* DL-027 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-24 — An enumeration carries only the record's real codes, and an unmapped code yields no value
The account-status validation flag in `app/cbl/COACTUPC.cbl` declares its level-88 group as
`VALUES 'Y', 'N'` for the valid state alongside `'0'` for not-OK and `'B'` for blank, so the flag
field's own group mixes two record codes with two validation-flag states. **Decision:** the
enumeration carries the two record codes only; the two flag characters belong to the field-error
contract (D-33) and are not status values. Resolving an unmapped code yields an empty result rather
than the exception an idiomatic lookup would raise, because the legacy comparison simply failed to
match and carried on.
*Embodied in:* `domain/enums/AccountStatus.java` and its siblings.

*Also recorded as:* DL-026 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-25 — The telephone all-blank shortcut's defect is preserved — PRESERVED DEFECT
The "not mandatory to enter a phone number" shortcut in the telephone cascade of
`app/cbl/COACTUPC.cbl` tests three sub-fields, and its third clause tests the *area-code* sub-field
where its two siblings each test their own. A submission carrying a blank area code, a blank prefix
and a populated line number therefore satisfies the shortcut and is silently treated as no telephone
supplied. **Decision:** reproduced exactly. It is contract, not a defect to correct, so the request
type must deliver that combination to the service intact rather than pattern-matching it away first.
*Embodied in:* `api/dto/AccountUpdateRequest.java`. Recorded as anomaly 27.

*Also recorded as:* DL-029 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

---

## 5. Data model and schema

### DL-032 — The reference tables model no JPA association whatsoever

The transaction-type and transaction-category reference entities declare no collection attribute,
no owning or inverse side, no join column and no cascade. The reference lookup path is a plain
repository lookup by identifier, which is all any consumer needs and all any consumer does.

The reason is behavioural rather than stylistic. The daily-transaction table is the *unvalidated
landing area* for inbound work. A referential constraint there would reject a bad record at insert
time and thereby make the posting program's reject-with-reason-code paths unreachable — the very
paths whose five codes the 430-byte reject trailer is contractually required to carry. Adding the
association that an ORM tutorial would add is therefore a behavioural regression, and a subtle one,
because the schema would look more correct while the reject file went empty.

*Cited by:* `domain/TransactionType.java`.

*Also recorded as:* D-38 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-033 — The daily-transaction record is a separate entity despite an identical layout

The daily-transaction layout is byte-for-byte identical to the posted-transaction layout, differing
only in field-name prefix. The two are nonetheless modelled as separate entities, because they are
distinct datasets with distinct lifecycles: one is unvalidated inbound work that may be rejected,
the other is posted history. Folding them into one entity would erase that distinction and, given
DL-032, would put the reject paths at risk.

### DL-034 — Record mappers are hand-written with explicit offsets, and use no reflection

All eleven fixed-width record mappers slice the record image by explicit offset arithmetic. No
annotation-driven mapping library, bean mapper or annotation processor is used anywhere in the
module.

This is a consequence of the unsafe-code audit committing to a reflection count of zero, and the
commitment is worth keeping rather than relaxing: any convention-based mapper reintroduces
reflection, and under a warnings-as-errors build an annotation processor is also a live source of
build-failing warnings. The cost is more code; the benefit is that the audit result is a design
property rather than a measurement that drifts.

*Also recorded as:* D-26 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-035 — A padded key and a trimmed key are different keys, everywhere

Fixed-width keys are compared verbatim. A ten-character group identifier padded with spaces is not
equal to its trimmed form; a two-character type code is not equal to its single-digit form. No
accessor trims, pads or normalises a fixed-width value.

This matters most for the interest program's default-group fallback: collapsing padded and trimmed
forms would make the fallback appear to resolve keys that it does not, which changes which accounts
accrue interest at which rate.

### DL-036 — The one nullable protected column is nullable for a reason

The national-identifier column is the single intentional nullable field in the initial schema. The
reference-data seed leaves it null in static SQL rather than embedding raw national identifiers, or
a hardcoded encryption key, in a migration file. Both alternatives were worse than a nullable
column.

*Cited by:* `db/migration/V1__create_schema.sql`.

### D-28 — The online transaction identifier is highest-key-plus-one, not a sequence
`app/cbl/COBIL00C.cbl` moves `HIGH-VALUES` into the key, browses backward to the highest existing
identifier, adds one, and seeds to one when the file is empty. **Decision:** no database sequence or
other number-issuing object is created; the identifier is computed inside the posting transaction. A
database-issued number diverges permanently after the first gap, and a rollback guarantees a gap.
*Embodied in:* `V1__create_schema.sql` (global rule 4). The bill-payment service is not yet
delivered.

*Also recorded as:* DL-018 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-29 — Primary keys are the natural business keys; no surrogate key
Each `DEFINE CLUSTER` states its key as a width and an offset within the record image, and each `FD`
record splits the key from the remainder. **Decision:** the JPA identifier is that same business key
everywhere, and no surrogate identifier is introduced. A surrogate would break the correspondence
between the record image and the table row on which byte parity depends. Three keys are composite and
become identifier classes.
*Embodied in:* `V1__create_schema.sql`, `domain/id/*.java`.

*Also recorded as:* DL-017 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-37 — Two identically named key groups are never unified
`app/cpy/CVTRA04Y.cpy` and `app/cpy/CVTRA01Y.cpy` name their key groups identically, yet the keys
differ: the transaction-category key is two components and 6 bytes at offset 0 (`KEYS(6 0)`), while
the category-balance key is three components and 17 bytes at offset 0 (`KEYS(17 0)`) and leads with
an account identifier the other does not contain at all, so the two layouts align at no offset.
**Decision:** two separate identifier classes, never interchangeable, with no shared supertype or
helper introduced to "reuse" the overlapping components. The two copybooks also spell their
components differently — one separates the words, the other runs them together — and **both
spellings are transcribed exactly rather than regularised**, because a column name is part of the
mapping that is validated against the migrated schema at start-up.
*Embodied in:* `domain/TransactionCategory.java`, `domain/id/TransactionCategoryId.java`,
`domain/id/TransactionCategoryBalanceId.java`, `V1__create_schema.sql`.

### D-38 — Reference tables carry no association and no inbound foreign key
**Decision:** the reference entities model no association whatsoever — no collection attribute, no
owning or inverse side, no join column, no cascade — and the schema defines no foreign key from a
transaction, daily-transaction or category-balance row to a reference table. The source treats type
and category codes as classification lexemes carried on the record, validated where at all by program
logic, and the reference tables exist to supply a description for display and reporting rather than to
gate what may be stored. Constraining them would change which rows the system accepts, and on the
unvalidated landing table it would additionally make the posting program's reject-with-reason-code
paths unreachable (D-23).
*Embodied in:* `domain/TransactionType.java`, `domain/TransactionCategory.java`,
`domain/DisclosureGroup.java`, `V2__create_indexes.sql` (decision C).

*Also recorded as:* DL-032 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-39 — The daily-transaction merchant block keeps its prefix, and the posted block keeps none
The two 350-byte layouts are byte-for-byte the same shape, but the four merchant columns carry the
daily-transaction prefix on one and are unprefixed on the other. **Decision:** transcribed as found
and not regularised in either direction, for the same reason as D-37 — the column names are validated
against the migrated schema at start-up.
*Embodied in:* `domain/DailyTransaction.java`, `V1__create_schema.sql`.

---

## 6. Structural and platform decisions

Each entry here is a structural prohibition or a shape decision that no single class owns: it
constrains how the whole module is built rather than what one component computes.

### D-26 — Fixed-width mapping is hand-written; reflection budget is zero
**Decision:** no annotation-driven, convention-based or bean-mapping framework and no annotation
processor is used, and no `java.lang.reflect` or `Class.forName` call appears in the module. Record
images are sliced with explicit offsets over the verified layouts. This is also why no code-generation
dependency is declared: under `-Xlint:all -Werror`, processor-generated code is a live source of
build-failing warnings.
*Embodied in:* `util/FixedWidthFieldReader.java` and the module's dependency set.

*Also recorded as:* DL-034 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-27 — Statement output uses literal template constants; a templating engine is forbidden
`app/cbl/CBSTM03A.CBL` emits HTML from `PIC X(100)` literal constants declared as level-88 values.
**Decision:** byte-identical output requires the same literals, in the same order, at the same width.
A templating engine introduces whitespace and ordering variability that a byte-parity comparison
would immediately fail.
*Embodied in:* `util/StatementTextTemplates.java`, `util/StatementHtmlTemplates.java`.

### D-31 — A procedural copybook's inclusion becomes a method call, not an import
`app/cpy/CSSTRPFY.cpy` and `app/cpy/CSUTLDPY.cpy` declare no data; they contribute paragraphs.
**Decision:** their inclusion sites become invocations — five quoted copy sites of the attention-key
copybook collapse into one call — rather than imports of a data type.
*Embodied in:* `util/PfKeyTranslator.java`, `service/DateValidationService.java`.

### D-32 — The macro copybook's 39 expansions become one call
`app/cpy/CSSETATY.cpy` is a parameterised `PROCEDURE DIVISION` macro with three substitution tokens,
expanded 39 times in `app/cbl/COACTUPC.cbl` — always against the same map, with 39 distinct
validation flags and 39 distinct screen field names. **Decision:** the 39 expansions of that
ten-line macro body collapse into one marking method invoked 39 times. Honouring the `REPLACING`
directive means reproducing the substitution behaviour, not producing a DTO per expansion.
*Embodied in:* `api/dto/FieldErrorDecorator.java`.

### D-33 — The field error contract is two-state, and fires only on re-submission
The macro colours a field when its validation flag is not-OK and *additionally* writes a marker when
the flag is specifically blank, and it does so only when the re-entry flag is set. **Decision:** the
error contract carries per-field states for "missing" and "invalid" rather than a single boolean, and
is populated only on re-submission. A single boolean would erase a distinction the screen made.
*Embodied in:* `api/dto/ErrorResponse.java`, `api/dto/FieldErrorDecorator.java`.

*Also recorded as:* DL-051 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-34 — The middle name and the second address line are never validated
`app/cbl/COACTUPC.cbl` decorates both fields for error display while coding no edit for either, as
its own comments state. **Decision:** no validation constraint is attached to either field, in a DTO
or as a check constraint. Adding one would reject input the legacy system accepts.
*Embodied in:* `api/dto/AccountUpdateRequest.java`, `V1__create_schema.sql`.

*Also recorded as:* DL-028 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-35 — One clock, and it is UTC
`app/cpy/CSDAT01Y.cpy` is included by all 17 online programs, and the target requirement is that
those inclusions collapse into auditing plus a single clock abstraction. **Decision:** the module has
exactly one time source and it is UTC. No component constructs a system-default-zone clock, and the
persistence layer's JDBC time zone is UTC, so a persisted timestamp and an audited timestamp cannot
disagree because of where the process happens to run. A caller that needs a fixed instant for a test
injects a clock explicitly.
*Embodied in:* `config/JpaAuditConfig.java`, `batch/step/AbstractCobolStep.java`,
`src/main/resources/application.yml`.

### D-40 — Abandoned work-area members are not declared, and "off" is absence rather than a sentinel
Four members of the screen work-area copybook, and their four condition names, are referenced by no
program in the estate; a mechanical scan finds zero references to any of them. **Decision:** none of
the four is declared in the transport type, and no on/off state is declared for the dead flag.
Inventing a component that nothing would ever set would fabricate a state transition the 3270
contract never had. Separately, the live return-message field carries a condition name meaning "off"
as a low-values field; **that is modelled as an absent component** — no low-values sentinel is
encoded and no null character is ever produced — which is the one place this transport type departs
from the legacy byte-level representation.
*Embodied in:* `api/dto/ScreenWorkArea.java`. Recorded as anomaly 29.

*Also recorded as:* DL-030 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-41 — One Java name for a routine the source names two ways
The paragraph that displays a file status before continuing or abending is named one way in six batch
programs and another way in two, and the ninth program has no such paragraph because it inspects a
subprogram return code rather than a file status. The two variants are otherwise identical routines.
**Decision:** one Java method name, with both source spellings recorded here so neither is lost. The routine stays separate from the abend
entry points because the legacy separated them: the status display runs on its own wherever the
program intends to continue, and immediately before the abend paragraph wherever it does not.
*Embodied in:* `service/AbendService.java`. Recorded as anomaly 28.

### D-42 — The citation locator, not the physical line, is the cross-reference key
Some legacy citations carry a locator that differs by a small offset from the artefact's physical line
number, because the locator is the key used consistently across this module's citations.
**Decision:** the locator form is retained and the offset is recorded here rather than silently
reconciled, so that two citations of the same declaration from two different files cannot drift
apart. Where a member carries COBOL sequence numbers in columns 1 through 6, it is cited by field
name only and never by number (anomaly 16).
*Embodied in:* `util/FixedWidthFieldReader.java`.

---

## 7. External interface contracts

The estate has three external contracts — fixed-width file output, the message-queue bridge, and
the batch-trigger card image. All three are reproduced; the entries below record where a security
requirement forced a controlled divergence inside them.

### DL-037 — Variable text in HTML statement output is escaped *(parity exception)*

The statement generator emits HTML assembled from fixed literal constants at a fixed
hundred-byte record width. Variable text — names, addresses, descriptions — was interpolated into
that markup verbatim by the legacy program, which means a value containing markup characters could
alter the structure of the generated document.

The target escapes the markup-significant characters in every variable text segment. This is a
deliberate parity deviation and is recorded as such rather than left as an unexplained difference.

**The size of the deviation is worth stating precisely:** escaping is the *identity function on the
entire legitimate domain* of these fields. Names, street addresses, city names and transaction
descriptions in the seeded estate contain no markup-significant character, so for every input the
legacy system was designed to carry, the target's output is byte-identical to the legacy output.
The two differ only for input that would have injected markup — which is exactly the input the
change exists to neutralise.

Two implementation notes that are easy to get wrong and are therefore fixed here. The escape builds
its result in a single pass rather than by chained replacement, which makes the ampersand-ordering
hazard — escaping `&` after having introduced ampersands of one's own — structurally impossible.
And the apostrophe uses the numeric character reference rather than the named one, because the
numeric form is defined in every HTML version.

*Cited by:* `util/StatementHtmlTemplates.java`, `util/StatementHtmlTemplatesTest.java`.

### DL-038 — The raw-markup line composer was removed, not merely documented

A single helper previously accepted a caller-supplied string and placed it into the output line as
raw markup. Documenting it as dangerous would have left the hazard in place, because the next
caller would still find and use it. It was therefore **removed** and replaced by three composers,
one per legitimate emission site, each of which escapes its own variable segments.

Removing a public helper is a larger change than annotating one, and it is recorded here because
that was the point: an unsafe sink that remains callable is an unsafe sink.

*Cited by:* `util/StatementHtmlTemplates.java`.

### DL-039 — Truncation to the record width never emits a partial character reference

Escaping can lengthen a value, and the record width is fixed at one hundred bytes. A character
reference cut by that boundary would emit a fragment such as an ampersand followed by two letters,
which is malformed markup produced by the very mechanism meant to prevent malformed markup.

The width fit therefore blanks a trailing partial reference with spaces rather than emitting it. A
reference that completes within the boundary is kept intact. The record width remains exactly one
hundred bytes in every case.

*Cited by:* `util/StatementHtmlTemplates.java`.

### DL-040 — Date slots on the batch-trigger card are validated by shape **and** calendar *(parity exception)*

The batch-trigger card image carries four ten-character date slots. Two of them sit *inside a sort
character constant*, which means a value containing the constant's delimiter could terminate that
constant early and let the remainder of the value be read as further sort control language. The
legacy program validated width only.

The target validates each slot three ways: a positional allowlist admitting digits only at the
digit positions and the separator only at the two separator positions; a strict-calendar parse that
rejects a well-shaped but non-existent date; and the unchanged ten-character width.

Three details are deliberate. The allowlist is a **positive** allowlist rather than a denylist of
dangerous characters, because a denylist is an invitation to miss one. The calendar check *rejects
but does not convert* — the parsed value is discarded and the caller's own bytes are placed on the
card, so the card remains byte-identical to what the caller supplied. And the year field uses the
era-independent year pattern under strict resolution, because the conventional pattern is
year-of-era and would demand an era field the slot does not carry.

### DL-041 — No rejection diagnostic echoes the offending value, or the separator

A validation failure on a date slot names the field and the expected shape. It does not echo the
rejected value, and it does not quote the class's own separator literal either — a diagnostic that
reflects hostile input into a log is a smaller version of the problem the validation exists to
solve.

The rule is scoped honestly: ordinary English punctuation in a message's own prose is not an echo.
An earlier, over-broad version of this rule banned commas in diagnostics and had to be narrowed to
the two properties that actually matter.

*Also recorded as:* D-16 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-042 — The queue payload boundary admits printable US-ASCII only

Each card published to the queue is checked for printable US-ASCII, which rejects carriage returns,
line feeds, nulls and the delete character. This is the outer guard behind DL-040: a control byte
does not change a record's width, so a width check alone admits it, and a smuggled line feed in an
eighty-column card image is a new card.

### DL-043 — Queue deduplication uses a per-submission nonce, not a content hash *(parity exception)*

The queue bridge reproduces a CICS transient-data queue whose disposition is *append*. Append
semantics mean a genuine re-submission of the same report request is a legitimate, expected event —
the legacy system would have written the cards again.

Content-derived or otherwise deterministic deduplication is therefore wrong here: it would silently
suppress a legitimate second submission and the caller would see success while nothing was queued.
The target derives a unique nonce per submission instead.

Two boundaries keep this from being a licence to duplicate. The nonce is applied per *submission*,
not per card, so a genuine double-publish of the same card *within one submission* is still caught.
And the message group carries no nonce, because uniqueness and ordering are independent properties
and the group is what preserves the card order the contract requires.

*Cited by:* `service/JobSubmissionService.java`.

### DL-044 — A queue failure logs and continues, and the exception type says so

The legacy queue definition specifies that errors are ignored: a write failure produced a screen
message and the program continued. The target reproduces this as a non-fatal, logged failure rather
than an abort of the caller. The dedicated exception type exists to carry that semantic explicitly
rather than leaving a bare catch to imply it.

*Cited by:* `exception/JobSubmissionException.java`.

*Also recorded as:* D-36 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-045 — The queue keeps the legacy resource name rather than a conventional one

Conventional names for the queue and its message group were considered and rejected. The queue
reproduces a transient-data queue whose legacy resource name is a specific four-letter identifier,
and the specification names it as such, so the target keeps that name and uses it as the group
identifier too. Faithful over invented, consistent with the way every other legacy resource name is
carried across — and renaming it would additionally have required simultaneous changes in six
configuration sites and in the already-provisioned local emulator.

*Cited by:* `application-local.yml`.

### DL-046 — Fixed output widths are contractual and are asserted as bytes

Four output widths are contractual — eighty bytes for statement text, one hundred for statement
HTML, one hundred and thirty-three for the transaction report, and four hundred and thirty for the
reject record, being the three-hundred-and-fifty-byte source image plus an eighty-byte trailer.
Comparisons against expected output are byte-array equalities on fixed-width lines rather than
semantic comparisons, so a trailing-space or sign-overpunch difference fails rather than passing
silently.

### D-36 — A queue-write failure is logged and execution continues
`app/csd/CARDDEMO.CSD` defines the job-submission queue with `RECORDSIZE(80)`,
`RECORDFORMAT(FIXED)`, `DISPOSITION(MOD)`, `TYPEFILE(OUTPUT)`, `OPENTIME(INITIAL)` and
`ERROROPTION(IGNORE)`, and the single write site in `app/cbl/CORPT00C.cbl` surfaced a message and
carried on rather than abandoning the caller. **Decision:** one 80-character message per card,
message-group ordering to preserve append order, and a publish failure that is logged as a
non-fatal condition rather than raised. Ordering is part of the contract, which is why the queue is
FIFO and the group identifier is a single stable value.
*Embodied in:* `service/JobSubmissionService.java`, `exception/JobSubmissionException.java`,
`util/JclCardImageBuilder.java`, `src/main/resources/application.yml`.

*Also recorded as:* DL-044 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-49 — Variable text in statement HTML is escaped, and the raw-markup sink was removed — CORRECTED

**Correction.** An earlier reading of this entry recorded statement HTML as emitted unescaped, with
safe rendering left to the consumer as a deliberate parity position. That is no longer true. The
entry is corrected here rather than deleted, so the change is visible to a reviewer who read the
earlier text.

Caller-supplied text placed into an HTML statement record is escaped before composition, and the
line composer that accepted raw markup was removed outright rather than documented as discouraged,
so an unescaped-data path is unreachable rather than merely deprecated.

Escaping applies to caller-supplied values only. Every fixed literal the legacy program emits is
still emitted byte for byte, including the table tag that carries two consecutive space characters
(anomaly 21): a fixed literal is not caller-supplied data and is not escaped. Truncation to the
hundred-byte record width never splits a character reference either — a reference that would not fit
whole is blanked rather than cut, so no partial entity can reach a record.

*Embodied in:* `util/StatementHtmlTemplates.java`.

*Also recorded as:* DL-037, DL-038 and DL-039, which record the escaping parity exception, the
removal of the raw-markup composer, and the whole-reference truncation rule respectively.

---

## 8. Configuration and error-surface posture

### DL-047 — The shared configuration baseline is fail-closed, and each convenience is widened by the profile that needs it

The shared configuration previously declared the permissive setting and relied on the production
overlay to narrow it. That posture is inverted: the shared baseline now declares the **closed**
setting, and each relaxation is widened by the profile that actually needs it.

The reasoning is a single sentence: *a deployment can forget to select a profile, but it cannot
forget to inherit the shared file.* Under the old posture a profile-less or mis-profiled start was
permissive; under the new one it is closed.

Five settings changed hands: the management endpoint exposure list, health detail visibility, health
component visibility, both API-documentation switches, and the transport requirement. The developer
conveniences moved into the local overlay, and the test overlay relaxes only the transport
requirement.

The production overlay deliberately **restates** the secure values rather than narrowing them. That
is not redundancy: it means production's posture is legible in one file, and a test asserts that
production *equals* the baseline rather than merely being no worse than it, so a future widening of
the baseline cannot silently widen production.

*Cited by:* `application.yml`, `application-local.yml`.

### DL-048 — One metrics endpoint was dropped and three were kept, for cross-file reasons

The endpoint exposure list keeps health, info and the metrics-scrape endpoint, and drops the
endpoint that enumerates every registered meter name.

The three retained endpoints are each resolved literally by another file in the module: the scrape
endpoint is the target named in the metrics scrape configuration and read by every dashboard panel,
and the health endpoint is the container health-check target. Dropping any of them would break a
cross-file contract. The dropped endpoint, by contrast, only enumerates meter names — useful in
development, and available there through the local overlay.

The API-documentation *paths* remain declared in the closed baseline even though both switches are
off there, and no overlay redeclares them. Keeping the paths in one place means enabling
documentation in a profile is a one-line switch rather than a path that can drift between overlays.

### DL-049 — No profile-guard bean was added, because three no-fallback bindings already make a profile-less start impossible

An explicit startup failure on a missing profile was considered and rejected.

Three values are declared in no baseline and defaulted nowhere: the datasource URL, the token
signing secret, and the field-encryption key. A profile-less start therefore already fails, on the
first of those three, without any additional mechanism.

Adding an environment-variable-based guard would have made every sibling integration test depend on
an externally set profile variable — a real cost paid for a property already held. The general
principle behind the choice: a build-time assertion is stronger protection than a start-up
exception, and a test that asserts the three bindings are undefaulted is exactly that assertion.

### DL-050 — A catch-all error handler exists, but it honours the framework's own status *(parity exception)*

A handler of last resort was previously and deliberately absent, so an unanticipated failure could
surface with framework-default detail. One now exists, but flattening every unhandled failure to a
server error would have been wrong: framework client-faults would have been reported as server
faults.

The handler therefore honours the status the framework exception itself declares, and falls back to
a server error only when there is none. Stated as a rule: **the status is honoured; the body never
is.** No message, no parser detail and no rejected value from the underlying exception reaches the
response.

Two properties keep the handler safe. It is bound to this module's own controllers by package, so it
cannot intercept another module's dispatch. And it cannot shadow the eleven specific handlers,
because a more specific handler always wins.

### DL-051 — Declarative validation failures answer in the legacy two-state field contract

The legacy screen decoration distinguished two field states: a field that is missing and a field
that is present but invalid. A declarative validation rejection must answer in that same
vocabulary.

Presence constraints map to *missing* and everything else maps to *invalid*. The mapping is by
**containment** rather than equality, deliberately: a bound-field failure reports a bare constraint
name while a constraint violation reports the fully qualified message template for the same
constraint, so an equality test would classify the same logical failure two different ways
depending on which validation path raised it.

Neither declarative handler offers a focus hint naming the screen field to place the cursor on,
because neither has one available. Honest code that omits a capability it lacks is preferable to
code that implies one.

*Also recorded as:* D-33 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-052 — No message, rejected value or invalid value is ever read from a validation exception

Two accessors are never called anywhere in the error boundary: the one that renders a binding
result — which includes each rejected value — and the one that returns a constraint's invalid value.
The field name reported is the *leaf* of the property path, not the whole path, so a nested path
cannot expose an internal structure.

The three places where an exception's own message *is* passed through are the three carriers whose
contract is to convey operator text: the abend, the service-raised validation failure, and the
conflict.

### DL-053 — Authentication and authorisation entry points are split, and the outer half is owned elsewhere

Two arms exist for an unauthenticated or forbidden request: the filter-chain arm, which answers
before a controller is reached, and the inside-dispatch arm, which answers when the failure is
raised after a principal is established.

The inside-dispatch arm is implemented here. The filter-chain arm belongs to the security
configuration class, which the migration plan assigns elsewhere. This is a scope boundary recorded
as such, not an unresolved gap.

### DL-054 — The authentication failure body does not distinguish an unknown identifier from a wrong credential *(parity exception)*

The legacy sign-on screen emitted two distinct literals — one for a user that does not exist and one
for a wrong password — and that pair is a user-enumeration oracle.

The error boundary returns a single indistinguishable body for both, and a test asserts the two are
byte-identical. This is a deliberate divergence from the legacy screen contract in favour of not
publishing an enumeration oracle over HTTP. Note the scope: the *sign-on screen's* seven message
literals are still reproduced verbatim where the sign-on flow itself renders them, per DL-001; it is
the generic error boundary that does not differentiate.

---

## 9. Conflicts between legacy artifacts, and how each was resolved

| # | Conflict | Resolution |
|---|---|---|
| D-43 | The same generation-data-group base is declared `LIMIT(5)` in `app/jcl/DEFGDGB.jcl` and `LIMIT(10)` in `app/jcl/REPTFILE.jcl` | Ten, as the later and more specific declaration. Generation retention becomes object versioning on the staging bucket rather than a generation limit, so the number bounds nothing at runtime; the conflict is recorded so a reader does not treat either member as authoritative on its own. |
| D-44 | One DD is declared `LRECL=80` in one `app/jcl/CREASTMT.JCL` step and `LRECL=100` in the next | One hundred, matching the `PIC X(100)` record the emitting program declares for the HTML stream. |
| D-45 | `app/jcl/TRANFILE.jcl` and `app/jcl/TRANIDX.jcl` both define an alternate index of the same name, over the same cluster, with the same key width and offset | One logical index described in two members, emitted exactly once. Emitting it twice fails on a duplicate name; renaming the second copy would leave a permanent redundant index behind. |
| D-46 | Duplicate step names — `STEP05R` twice in `app/jcl/TRANREPT.jcl`, `STEP05` twice in `app/jcl/DEFCUST.jcl` | Distinct target step names, with the original names recorded here so the mapping stays findable. |
| D-47 | Sample rows and sign-on identities must reach local and test but never production | Seed scripts live in a separate migration location that the production profile does not list, rather than behind a flag, so the guarantee is structural instead of conditional. |
| D-48 | Prior project documentation names test-plugin versions that the resolved build supersedes | The resolved versions govern. A version is recorded only after being read back out of an executed resolution. |

---

## 10. The source anomaly register

Thirty-one defects and oddities were identified in the legacy source. None is propagated into new
logic, and none is silently corrected where correcting it would alter a record layout or an
external contract. The numbering is the one the module cites: eight source and test files refer to
these rows by number, so a row is never renumbered.

Where an anomaly participates in a record layout, the layout is preserved byte for byte and only
the Java-side spelling is corrected.

| # | Anomaly | Handling |
|---|---|---|
| 1 | `ACCT-EXPIRAION-DATE` in `app/cpy/CVACT01Y.cpy` and `CARD-EXPIRAION-DATE` in `app/cpy/CVACT02Y.cpy` are misspelled — a letter is dropped from EXPIRATION | Correct spelling in the Java property and the SQL column; mapper offset and width unchanged, so the record image stays byte-compatible |
| 2 | Two identically named `0000-MAIN-EXIT` paragraphs in `app/cbl/COACTVWC.cbl` | Collapsed to a single method when that program is translated; recorded now so the paragraph count is not read as an error |
| 3 | `app/csd/CARDDEMO.CSD` defines `PROGRAM(COCRDSEC)` with no corresponding source member | No target is generated; recorded as a dangling resource definition |
| 4 | `app/jcl/DEFCUST.jcl` carries a duplicate `STEP05` and a cluster-name mismatch | Provisioning intent is served by the schema migration; the defect is not reproduced |
| 5 | `app/jcl/READCUST.jcl` carries `NOTIFY=&SYUID`, a misspelling of the system-user symbol | No equivalent in the target; recorded only |
| 6 | The paragraph that writes the job-submission queue in `app/cbl/CORPT00C.cbl` is named `WIRTE-JOBSUB-TDQ` | The Java method is named correctly; the original spelling is recorded here so the mapping remains findable |
| 7 | Conflicting generation limits for one base | See D-43 |
| 8 | Garbled overtyped text on one line of `app/jcl/CREASTMT.JCL` | Step intent recovered from the surrounding DD statements; the corruption is not reproduced |
| 9 | Duplicate step name in `app/jcl/TRANREPT.jcl` | See D-46 |
| 10 | Conflicting record lengths for one DD in `app/jcl/CREASTMT.JCL` | See D-44 |
| 11 | `AWS.M2.CARDDEMO.ACCDATA.PS` is byte-identical to `AWS.M2.CARDDEMO.ACCTDATA.PS` and is referenced by no job member | Retained as reference evidence; not seeded twice |
| 12 | `app/cbl/CBTRN01C.cbl` is a complete 491-line program that no JCL member, procedure or resource definition invokes | Migrated as a job that is defined and exercised by tests but excluded from the default pipeline; not dropped |
| 13 | `app/cpy/UNUSED1Y.cpy` has zero `COPY` references estate-wide | Deliberately not migrated. Recorded as a decision, not an omission: migrating it would create dead Java code |
| 14 | `1400-COMPUTE-FEES` in `app/cbl/CBACT04C.cbl` contains only a "to be implemented" comment and an exit, yet is genuinely invoked from the interest driver | Preserved as an explicitly documented no-op. **No fee logic may be invented to fill it**: doing so would be feature expansion and would change interest-run output |
| 15 | The comments labelling two macro expansions in `app/cbl/COACTUPC.cbl` are transposed relative to the code they describe | The code governs. Token substitutions are followed, not the adjacent comments |
| 16 | `app/cpy/CVCRD01Y.cpy` carries COBOL sequence numbers in columns 1 through 6, and the sequence number `004800` appears twice, at its physical lines 40 and 42 | Every citation of that member is by field name and never by line number, because a line number quoted from it would really be a sequence number. The duplication has no effect on the field layout |
| 17 | The header comment of `app/cpy/CSMSG02Y.cpy` names the member `CABENDD.CPY`, which disagrees with the member name that exists | Not corrected — the legacy tree is the parity baseline and must remain byte-identical |
| 18 | A comment in `app/cbl/COACTUPC.cbl` describes a field as alphabetic-only, while the statement immediately below it converts with the 62-character alphanumeric table | The code governs and the comment is stale; that site is covered by the alphanumeric predicate, not the alphabetic one |
| 19 | Validation flag condition names in `app/cbl/COACTUPC.cbl` are misspelled `ALPHNANUM` | Recorded only. Flag state is a field-error-decoration concern; nothing is generated from the misspelling |
| 20 | Filler bytes are not uniform across the sample data | See D-10 |
| 21 | `app/cbl/CBSTM03A.CBL` emits a table tag containing two consecutive space characters, verified by a raw byte read with control characters exposed | Emitted verbatim. Not collapsed, not normalised and not treated as a typographical error: the comparison is on bytes |
| 22 | An earlier reading held that, in the same program, one composed HTML line closes its paragraph tag while the customer-name line does not | **Corrected, not carried forward.** The emitting paragraph composes the open tag, the name up to its first double space, a two-space separator and the closing tag — the same composition the three address lines use — and the declared widths make overflow truncation unreachable, so the closing tag is always emitted: 26 bytes of open tag, at most 50 bytes of name, 2 bytes of separator and 4 bytes of closing tag total 82 against a 100-byte record. The composer reproduces that composition. See D-49, DL-037 and DL-039 |
| 23 | `app/cbl/CBTRN03C.cbl` reuses paragraph-number prefixes — one prefix appears twice and another three times | The Java methods are distinctly named and the original paragraph names are recorded here |
| 24 | `app/cpy/COMEN02Y.cpy` carries, immediately above the live label for user option 8, a commented-out alternative label that would describe the option as administrator-only | Stays inactive: published neither as a value nor as a constant nor as a conditional alternative, and the option is not role-gated. Activating it would be feature expansion |
| 25 | Line 2 of `app/cpy/COMEN02Y.cpy` and line 2 of `app/cpy/COADM02Y.cpy` carry the same administrator-menu title comment, yet the first declares the main-menu data item and holds the ten user options | The data item is correct and the comment is a copy-and-paste defect, so the catalogue follows the data item |
| 26 | The trailer of `app/cpy/COADM02Y.cpy` records release stamp `CardDemo_v1.0-26-g42273c1-79` dated 2022-07-20, whereas the rest of the estate carries `CardDemo_v1.0-15-g27d6c6f-68` dated 2022-07-19 | That copybook is a later revision than its siblings. Its content is migrated as found; recorded so a reviewer comparing stamps does not read it as a transcription error |
| 27 | The third clause of the all-blank telephone shortcut in `app/cbl/COACTUPC.cbl` tests the area-code sub-field where its two siblings each test their own | Preserved exactly. See D-25 |
| 28 | The file-status display routine is named one way in six batch programs and another way in two | One Java name; both spellings recorded here. See D-41 |
| 29 | Four members of `app/cpy/CVCRD01Y.cpy`, and their four condition names, are referenced by no program in the estate | Not declared in the target. See D-40 |
| 30 | The paragraph closing the daily-rejects file in `app/cbl/CBTRN02C.cbl` correctly tests its own status to decide the close failed, then displays the cross-reference file's status instead (line 649). Its three sibling close paragraphs each display their own | Not reproduced. The status travels from the failing operation to the diagnostic as a parameter rather than through a shared display field, so reporting another resource's status is structurally impossible. Recorded because there is no line of target code to point at |
| 31 | The page-break test in `app/cbl/CBTRN03C.cbl` is `FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0` (line 282), and it fires only when the counter lands exactly on a multiple of the page size. The write routine `1111-WRITE-REPORT-REC` never increments the counter; each caller does — a header block adds four, a page-total block two, an account-total block two, a detail record one, and the grand-total record nothing. An account-total block's increment of two can therefore step the counter straight over a multiple of the page size, and that page break is missed entirely | Reproduced faithfully, never repaired: the modulus is taken over every written record and the per-caller increments are kept as found, so a skipped break stays skipped. The counter, the accumulations and the break decision belong to the report service; `util/ReportLineFormatter` holds no state and publishes only the page-size constant. Related: account totals do not roll into the grand total (only page totals do) and no rule record follows the grand total |

A fifteenth observation is a documentation defect rather than a code defect: in the account-update
program the comments labelling two adjacent macro expansions are transposed relative to the code
they describe. The code is correct and the comments are swapped, so the translation follows the
token substitutions rather than the adjacent comments.

### DL-055 — Two file-status codes are declared but unexercised, contradicting the prior specification

A prior specification asserts two particular status codes for file-not-found and duplicate-key
handling. A census of the estate returns **zero** occurrences of either literal: neither is compared
anywhere in the legacy source.

Both are declared so the documented vocabulary stays discoverable, but no behaviour in the module
depends on, branches on or special-cases either one. The discrepancy between the prior
specification and the source is recorded here rather than resolved by adopting the specification's
claim.

*Cited by:* `domain/enums/FileStatus.java`.

### D-22 — Status codes documented elsewhere but never compared in the source
In status-test context the source compares `'00'` on 73 lines, `'10'` on 7 and `'23'` on 3 — the
disclosure-group lookup in `app/cbl/CBACT04C.cbl` accepts `'00'` or `'23'` and then branches on
`'23'` to take the default-group fallback, and the category-balance read in `app/cbl/CBTRN02C.cbl`
likewise accepts `'00'` or `'23'`. The full literal vocabulary appearing anywhere in the source
is `'00'`, `'01'`, `'02'`, `'04'`, `'05'`, `'10'`, `'12'`, `'23'` and `'31'`. Prior project
documentation additionally asserts `'35'` for file-not-found and `'22'` for duplicate-key handling;
neither literal is compared anywhere in the estate. **Decision:** such codes may be *defined* for
completeness but no code path may depend on them, and no test asserts behaviour for them. The
discrepancy with the prior documentation is recorded here rather than resolved silently in either
direction.
*Embodied in:* `domain/enums/FileStatus.java`.

*Also recorded as:* DL-055, immediately above, which records the same census result — neither
literal is compared anywhere in the estate — and the same handling.

### DL-056 — File status is modelled at two levels, not one

No legacy batch program branches on the raw two-byte status. Each normalises it first — success to
zero, end of file to sixteen, anything else to twelve — and then branches on the two condition names
declared over that normalised value.

The target therefore carries both levels: a raw status vocabulary and a tri-state outcome mirroring
the normalised value, plus the terminal abend path. Collapsing them into a single enumeration would
erase the end-of-file-versus-error distinction on which every batch read loop depends, and a
sequential job would abend at the moment it finished reading.

The sharper half of the model is that end of file is normal for a *read* and for nothing else. A
close that reports end of file is a genuine failure, which is why the operation vocabulary carries a
per-operation flag and the same raw code classifies differently depending on which operation
reported it.

### D-21 — File status needs two levels, not one enum
The batch programs do not branch on the raw two-byte status. They normalise it first — `'00'` to 0,
`'10'` to 16, anything else to 12 — and then branch on the level-88 names `APPL-AOK` and `APPL-EOF`.
`APPL-RESULT` appears on 223 lines of `app/cbl` (229 occurrences). **Decision:** the target carries a raw status
enum *and* a tri-state outcome mirroring `APPL-RESULT`, plus the terminal abend path. Collapsing
them into one enum would erase the end-of-file-versus-error distinction on which every batch read
loop depends.
*Embodied in:* `domain/enums/FileStatus.java`, `exception/FileStatusException.java`,
`exception/AbendException.java`.

*Also recorded as:* DL-056, immediately above, which records the same two-level model and the
per-operation distinction that makes end of file normal for a read and a failure for a close.

---

## 11. Intentionally unmigrated artifacts

### DL-057 — The unreferenced copybook is not migrated

One copybook has zero inclusion references anywhere in the estate. Migrating it would create dead
Java code. It is recorded here as a decision rather than left to look like an omission.

### DL-058 — The unwired batch program is migrated but excluded from the default pipeline

One complete batch program is invoked by no job member, procedure or CICS definition. It is
nonetheless translated, because the mandate is to migrate every program, and its job is defined but
excluded from the default pipeline and exercised only by tests. Both halves are deliberate:
translating it honours completeness, and leaving it unwired honours the estate's actual
orchestration.

### DL-059 — The empty fee-computation paragraph remains an empty method

One paragraph in the interest program contains only a comment stating that it is to be implemented,
yet it is genuinely invoked from the interest-calculation driver.

It is preserved as an explicitly documented no-op. **No fee logic may be invented to fill it**:
doing so would be feature expansion and would change interest-run output. This is the sharpest test
of the no-feature-expansion boundary in the estate, because the vacuum is so obviously inviting.

### DL-060 — The file-availability and catalog-utility job members have no runtime equivalent

Three job members toggle CICS file availability and drive the CICS catalog utility. Once the
underlying data store is replaced these have no runtime equivalent at all. They are documented as
intentionally unmigrated rather than dropped silently.

### DL-061 — The commented-out alternative menu label stays inactive

One user-menu option carries a commented-out alternative label in the legacy source. It remains
inactive in the target. Activating it would change a screen literal that no requirement asks to
change.

Three further categories are not migrated, and are recorded here for the same reason.

| Artifact | Reason |
|---|---|
| The 69 non-application `EXEC PGM` steps across the estate | Dataset definition, deletion, repro and CICS file toggling. Absorbed by schema migrations, container service definitions and test fixtures rather than becoming job steps |
| The unimplemented future-state entities on one page of the data-model diagram | No corresponding copybook, program or dataset exists; implementing them would be feature expansion |
| The two packaged emulator runtimes under `samples/` | Opaque vendor binaries with no source content to translate |

---

## 12. Toolchain and dependency decisions

### DL-062 — The framework line is pinned to the constrained major version, not the newest release

The requirement names the 3.x framework line while a 4.x line exists. The narrower, explicit
qualifier governs, so the newest generally-available 3.x release is used. The 4.x line would breach
the stated ceiling.

### DL-063 — Maven with a committed wrapper, rather than the permitted alternative build tool

Either build tool was permitted. Maven was chosen because full version pinning plus a
project-distributed build tool is the stronger guarantee of a reproducible, zero-warning build from
a clean checkout with no preinstalled toolchain.

### DL-064 — No annotation processor and no code-generation library

The estate contains no annotation processor of any kind, and none is introduced. Two reasons
compound: under a warnings-as-errors build, processor-generated code is a live source of
build-failing warnings; and the unsafe-code audit commits to a reflection count of zero, which
DL-034 depends on. Boilerplate is written explicitly instead.

### DL-065 — Test container library stays at the version the framework manages

A newer major version of the container test library is published, but the framework's dependency
management pins an earlier one. Remaining managed avoids an unmanaged major-version override.

### DL-066 — Two transitive libraries are pinned forward past published advisories

Two managed transitive libraries carried published advisories at the versions the framework
resolved. Both are pinned forward by overriding the framework's own version properties rather than
by declaring a direct dependency, so the override travels with the dependency management instead of
sitting beside it. The vulnerability scan is bound to the build and its report is a gate artifact.

### DL-067 — Test-scope dependencies are scanned, and one suppression is narrowly justified

Vulnerability scanning previously excluded test-scope dependencies. It no longer does. One
suppression file entry remains, scoped as narrowly as the schema permits and carrying its
justification inline; the schema allows exactly one identifying element per entry, which is why the
entry is written the way it is.

### DL-068 — Continuous-integration actions are pinned to immutable commits

Every workflow action reference is pinned to a full commit identifier with the human-readable
version retained as a comment, so a moving tag cannot change what the pipeline executes.

### DL-069 — Goal ordering, not goal presence, was the supply-chain defect

Goals bound to the same build phase execute in plugin declaration order. The vulnerability scan was
declared after the coverage check, so a coverage shortfall ended the build before the scan ran and
the scan's evidence was never produced. The scan is now declared first. Both goals now execute in
one lifecycle, which is verifiable from the goal order in the build log rather than asserted.

---

## 13. Open items and coordination risks

These are not decisions but consequences of decisions, recorded so they are not discovered later as
surprises.

### DL-070 — Seed migrations must not insert cleartext into the protected columns

DL-006 keeps cleartext out of the protected columns *through application code*. A migration that
inserted a cleartext value with raw SQL would bypass the entity guard entirely, because the
persistence provider reads existing rows by field access and the guard runs on the application-side
write path.

The reference-data seed therefore must leave those columns null or insert an already-sealed value.
DL-036 records that the initial schema makes the national-identifier column nullable precisely to
make the null option available.

### DL-071 — Coverage headroom is measured against the sources that exist today

The enforced line-coverage floor is met with headroom measured over the module's current source
set. As further classes land, the denominator grows and the merged ratio moves even if nothing
already written regresses. Each new class is expected to carry its own tests, which is what keeps
the floor met; a later ratio below the floor should be read against the classes added since, not as
a regression in the work recorded here.

### DL-072 — The date-validation cascade is the highest-risk construct and warrants the deepest testing

DL-019 records that the eleven-paragraph date-validation cascade is the construct where a plausible
mistranslation is least visible — validation that appears to work and validates nothing. It is named
here as the standing highest-risk item in the estate so that any future change to it is treated
accordingly.

### DL-073 — No performance target exists, so the baseline is established rather than asserted

No numeric latency, throughput, availability or capacity figure appears anywhere in the estate, in
the job control, or in the CICS definitions. Performance measurement therefore *establishes* the
first baseline; it does not test against a threshold. Recorded explicitly so that no future work
invents a service level and then tests against the invention.

---

## 14. Citation index

Every reference to this log from the target module, mapped to the entry that answers it. The
index is in two parts because the module cites two identifier schemes; the concordance at the
end pairs the entries that record the same decision under both.

### 14.1 `DL-nnn` citations

| Citing file | Entry |
|---|---|
| `api/GlobalExceptionHandler.java` | DL-080, DL-083, DL-084, DL-085 |
| `api/dto/AccountUpdateRequest.java` | DL-011, DL-028, DL-029, DL-031, DL-074, DL-078 |
| `api/dto/FieldErrorDecorator.java` | DL-080 |
| `api/dto/NavigationContext.java` | DL-087 |
| `api/dto/PageMetadata.java` | DL-081 |
| `api/dto/ScreenWorkArea.java` | DL-011, DL-030, DL-082 |
| `api/dto/SignOnRequest.java` | DL-001, DL-004 |
| `batch/step/AbstractCobolStep.java` | DL-084 |
| `config/JpaAuditConfig.java` | DL-089 |
| `config/OpenApiConfig.java` | DL-088 |
| `config/WebMvcConfig.java` | DL-089 |
| `domain/Account.java` | DL-012, anomaly register (1) |
| `domain/Customer.java` | DL-005, DL-006 |
| `domain/TransactionType.java` | DL-032 |
| `domain/UserSecurity.java` | DL-001, DL-002 |
| `domain/enums/FileStatus.java` | DL-055, DL-056 |
| `domain/enums/KeyAction.java` | DL-024, DL-025 |
| `domain/enums/RejectReason.java` | DL-027 |
| `exception/JobSubmissionException.java` | DL-044, anomaly register (6) |
| `exception/OptimisticLockConflictException.java` | DL-012, DL-083 |
| `exception/ValidationException.java` | DL-080, DL-086 |
| `service/JobSubmissionService.java` | DL-043 |
| `service/AccountConcurrencyTokenService.java` | DL-074, DL-075, DL-076, DL-077 |
| `service/FieldErrorTranslationService.java` | DL-080 |
| `service/SensitiveFieldEncryptionService.java` | DL-005, DL-008 |
| `util/ReportLineFormatter.java` | anomaly register |
| `util/CobolStringUtils.java` | DL-078 |
| `util/SensitiveFieldCodec.java` | DL-009 |
| `util/ZonedDecimalCodec.java` | DL-079 |
| `util/StatementHtmlTemplates.java` | DL-037, DL-038, DL-039 |
| `resources/application.yml` | DL-001, DL-005, DL-008, DL-047, DL-048, DL-088 |
| `resources/application-local.yml` | DL-045, DL-047, DL-088, anomaly register (7) |
| `resources/application-prod.yml` | DL-088 |
| `pom.xml` | DL-088 |
| `resources/db/migration/V1__create_schema.sql` | DL-005, DL-007, DL-010, DL-012, DL-036, anomaly register (1) |
| `api/dto/SignOnRequestTest.java` | DL-001, DL-003, DL-004 |
| `domain/enums/AccountStatusTest.java` | DL-024, DL-026 |
| `exception/OptimisticLockConflictExceptionTest.java` | DL-012 |
| `util/StatementHtmlTemplatesTest.java` | DL-037 |
| `service/AccountConcurrencyTokenServiceTest.java` | DL-074, DL-076, DL-077 |
| `service/FieldErrorTranslationServiceTest.java` | DL-080 |
| `util/CobolStringUtilsTest.java` | DL-078 |
| `util/ZonedDecimalCodecTest.java` | DL-013, DL-014, DL-015, DL-016, DL-079 |
| `config/ConfigurationProfileBaselineTest.java` | DL-088 |
| `api/dto/PageMetadataTest.java` | DL-081 |
| `api/dto/ScreenWorkAreaSecurityTest.java` | DL-082 |
| `api/dto/NavigationContextSecurityTest.java` | DL-087 |

### 14.2 `D-nn` and anomaly-register citations

| Citing file | Entry |
|---|---|
| `api/GlobalExceptionHandler.java` | D-14 |
| `api/dto/AccountUpdateRequest.java` | D-02, D-13, D-17, D-33, D-34 |
| `api/dto/ErrorResponse.java` | D-33, D-34 |
| `api/dto/FieldErrorDecorator.java` | D-33, D-34 |
| `api/dto/NavigationContext.java` | D-33 |
| `api/dto/ScreenWorkArea.java` | D-20, D-40 |
| `api/dto/SignOnRequest.java` | D-12 |
| `api/dto/SignOnRequestTest.java` | D-12 |
| `api/dto/StatementSummary.java` | D-02 |
| `batch/step/AbstractCobolStep.java` | D-21, D-22, D-41, anomaly 30 |
| `config/JpaAuditConfig.java` | D-35 |
| `config/MenuOptionCatalog.java` | anomaly 24 |
| `config/MenuOptionCatalogTest.java` | anomaly 24, 25, 26 |
| `domain/Account.java` | D-15 |
| `domain/DailyTransaction.java` | D-39 |
| `domain/TransactionCategory.java` | D-37, D-38 |
| `domain/TransactionType.java` | D-38 |
| `domain/UserSecurity.java` | D-12 |
| `domain/enums/FileStatus.java` | D-21, D-22 |
| `domain/enums/KeyAction.java` | D-20 |
| `domain/enums/RejectReason.java` | D-23 |
| `domain/id/TransactionCategoryBalanceId.java` | D-37 |
| `exception/AbendException.java` | D-06, D-07, anomaly 17 |
| `exception/AbendExceptionTest.java` | D-06, D-07 |
| `exception/FileStatusException.java` | D-21, D-22 |
| `exception/FileStatusExceptionTest.java` | D-21, D-22 |
| `exception/JobSubmissionException.java` | D-36 |
| `exception/JobSubmissionExceptionTest.java` | D-36 |
| `exception/OptimisticLockConflictException.java` | D-15 |
| `exception/OptimisticLockConflictExceptionTest.java` | D-15 |
| `exception/RecordNotFoundException.java` | D-21 |
| `exception/RecordNotFoundExceptionTest.java` | D-21 |
| `exception/ValidationException.java` | D-16, D-33, D-34 |
| `exception/ValidationExceptionTest.java` | D-33, D-34 |
| `resources/db/migration/V1__create_schema.sql` | D-12, D-14 |
| `service/AbendService.java` | D-41 |
| `service/JobSubmissionService.java` | D-36 |
| `util/CobolStringUtils.java` | D-17, D-18, D-19, D-22, anomaly 18 |
| `util/CobolStringUtilsTest.java` | anomaly 18, 19 |
| `util/FixedWidthFieldReader.java` | D-06, D-10, D-11, D-42, anomaly 20 |
| `util/FixedWidthFieldReaderTest.java` | D-08, D-10, D-11, anomaly 20 |
| `util/JclCardImageBuilder.java` | D-27, D-36 |
| `util/JclCardImageBuilderTest.java` | D-06, D-08, D-11 |
| `util/PfKeyTranslator.java` | D-20, D-31 |
| `util/ReportLineFormatter.java` | D-01, D-05, D-06, D-11, D-27, D-30 |
| `util/ReportLineFormatterTest.java` | D-02 |
| `util/StatementHtmlTemplates.java` | D-27, D-44 |
| `util/StatementHtmlTemplatesTest.java` | D-27, D-30, D-49 |
| `util/StatementTextTemplates.java` | D-05, D-06, D-27, D-30 |
| `util/StatementTextTemplatesTest.java` | D-05, D-06, D-27, D-44 |
| `util/ZonedDecimalCodec.java` | D-01, D-02, D-03, D-06, D-16 |
| `util/ZonedDecimalCodecTest.java` | D-01, D-02, D-11, D-16 |

### 14.3 Concordance — the same decision under both schemes

| `D-nn` | `DL-nnn` |
|---|---|
| D-01 | DL-015 |
| D-02 | DL-013 |
| D-03 | DL-014 |
| D-12 | DL-001, DL-002, DL-003, DL-004 |
| D-13 | DL-005 … DL-009 |
| D-14 | DL-010 |
| D-15 | DL-012 |
| D-16 | DL-011, DL-041 |
| D-17 | DL-022 |
| D-18 | DL-023 |
| D-20 | DL-024, DL-025 |
| D-21 | DL-056 |
| D-22 | DL-055 |
| D-23 | DL-027 |
| D-24 | DL-026 |
| D-25 | DL-029 |
| D-26 | DL-034 |
| D-28 | DL-018 |
| D-29 | DL-017 |
| D-33 | DL-051 |
| D-34 | DL-028 |
| D-36 | DL-044 |
| D-38 | DL-032 |
| D-40 | DL-030 |
| D-49 | DL-037, DL-038, DL-039 |

---

## 15. Decisions taken while resolving code review findings

Every entry below was authored while closing a finding raised against the delivered module. Each one
records a point where the finding's substance could be satisfied only by diverging from the legacy
program, from the finding's own suggested resolution, or from the idiomatic Java answer, and says
which way the divergence errs.

### DL-074 - The account-update turn carries a sealed old-image token, because REST has no commarea

The legacy transaction does not re-read and trust the screen. It keeps the account and customer
records as they were read for display in an extension of its own commarea
(`app/cbl/COACTUPC.cbl` L652 declares it, L669 onward populates it, L1010-L1018 return it with
the screen and L888-L892 slice it back off on re-entry), and paragraph
`9700-CHECK-CHANGE-IN-REC` compares that old image field by field against a fresh read taken under
lock at L3894 onward. The terminal never sees the extension and cannot alter it.

Split across REST turns, the client is the only thing that persists between the view and the
confirmation, so any state echoed in the clear is state the client controls - and a client that can
assert "nothing changed" has authorised its own overwrite. The equivalent is therefore an opaque,
authenticated token: `service/AccountConcurrencyTokenService` seals a payload of two SHA-256 digests,
one per canonical record image, under the module's authenticated-encryption service bound to
`account_update.concurrency_token`. A token that is absent, blank, unauthenticated, sealed for
another binding, carrying an unrecognised scheme, or no longer describing the records all refuse the
write with the one verbatim legacy text, `Record changed by some one else. Please review`.

Digests rather than the images themselves, because the token crosses the wire: a digest pair answers
"did either record move" without carrying a single field value, so the token discloses nothing about
the account or the customer even to the operator holding it.

### DL-075 - The row version is deliberately not sealed into the token

`Account` carries a provider-managed `@Version` and `Customer` carries no version attribute at all,
by design, because the legacy customer record has no such field. The two mechanisms guard different
windows and neither replaces the other: the provider's check covers the interval between the
read-for-update and the flush, while the token covers the interval between presenting the screen and
confirming it. Sealing the version into the token would add nothing the record digest does not
already detect, and - since the attribute has no setter, being provider-owned - a unit test could
only move it reflectively, against an audited reflection budget of zero.

### DL-076 - The account digest covers the postal code that the legacy comparison omits

`9700-CHECK-CHANGE-IN-REC` compares ten account data fields and does not compare `ACCT-ADDR-ZIP`, so
the legacy program silently accepts an overwrite of a concurrent change to a field its own update
screen can edit. The digest covers it. The divergence errs towards refusing a write the legacy would
have allowed, which is the safe direction for a check whose entire purpose is to refuse stale writes.

### DL-077 - Both regulated identifiers are digested as stored rather than as cleartext

`CUST-SSN` and `CUST-GOVT-ISSUED-ID` are held as `ENC1:` envelopes, and each seal draws a fresh
vector, so identical cleartext is stored as different bytes. Digesting the stored form therefore
reports a change when a value has merely been re-sealed, and also reports a case-only change to the
government-issued identifier that the legacy program folds away. Both readings refuse the write, so
both err in the safe direction. Revealing the two values inside the check would place regulated
cleartext in memory for a comparison that does not need it, which is the worse trade.

### DL-078 - The five monetary screen components are raw lexemes, not pre-parsed decimals

`1250-EDIT-SIGNED-9V2` edits each amount as a 15-character screen image -
`WS-EDIT-SIGNED-NUMBER-9V2-X PIC X(15)` at `app/cbl/COACTUPC.cbl` L55, with the three flag
states at L56-L59 - and is invoked at L1485, L1498, L1510, L1517 and L1524. It distinguishes three
outcomes: not supplied, present but not a number, and valid. Typing the request components as
`BigDecimal` made the middle state unrepresentable: a malformed present value was refused by
deserialisation, so the service never saw it, the offending field's identity was lost, and the
operator received a generic unreadable-body response instead of that field marked invalid.

The components are `@Size(max = 15) String`, matching the map field width
(`ACRDLIMI PIC X(15)`, `app/cpy-bms/COACTUP.CPY` L90), and three primitives in
`util/CobolStringUtils` - `isUnsuppliedNumericLexeme`, `isNumericLexeme` and
`plainDecimalOfNumericLexeme` - reproduce the `TEST-NUMVAL-C` and `NUMVAL-C` grammar the paragraph
relies on. The estate declares no `SPECIAL-NAMES` paragraph anywhere, so the language defaults hold
and the accepted currency sign is `$` with `.` as the decimal point. `isUnsuppliedNumericLexeme` is
the single documented exception to the utility class's reject-null rule, because an absent component
*is* the not-supplied state that `LOW-VALUES` represents, and answering it with an exception would
force every caller to write the null test the primitive exists to perform.

### DL-079 - One seam converts a screen lexeme to a stored amount, and magnitude is judged later

`ZonedDecimalCodec.fromNumericLexeme` is the only route from an edited screen lexeme to a
`BigDecimal`, so the estate's truncating policy cannot be bypassed by a caller doing its own parsing:
`1.239` is stored as `1.23`. One divergence is deliberate. A legacy `MOVE` into a `S9(10)V99` field
silently discards high-order digits, so an over-long amount becomes a different, plausible number.
The seam instead returns the operator's value intact and leaves `encodeMonetary` to refuse it at the
persistence width, so an amount too large to store is reported rather than quietly altered.

### DL-080 - The field decoration accumulates a neutral model, and the two conversions sit at the two boundaries

`app/cpy/CSSETATY.cpy` is one macro, expanded 39 times in `app/cbl/COACTUPC.cbl` between L3208 and
L3432, and in the legacy program the flag it tests, the screen it decorates and the decision not to
write all sit in the same place. A REST module has three places instead: the accumulation a service
builds, the failure it throws, and the body a client receives. Three representations therefore exist,
and the risk is not that one is missing but that two of them drift and an operator is told to supply
a value already supplied.

`api/dto/FieldErrorDecorator` now accumulates `MarkedField(field, bmsFieldId, flagState)` - the
field, the screen label and the legacy flag state, and nothing else. That triple names neither the
response contract nor the failure carrier, which is what makes it usable from either tier. Each
boundary then has exactly one converter, placed where its dependency is legal:
`service/FieldErrorTranslationService` inbound, turning an accumulation into `ValidationException`,
and `api/GlobalExceptionHandler` outbound, turning that failure into `ErrorResponse`. Neither
conversion is written at a call site, so a service decorates and throws without assembling entries by
hand. `FieldErrorDecorator.fieldErrors()` survives as a projection of the accumulation rather than as
a second store, so it cannot disagree with what was marked;
`FieldErrorTranslationServiceTest.TheSeamEndToEnd` asserts that the response a client receives equals
that projection entry for entry, which is what fails if either converter is changed alone.

Two divergences are recorded rather than hidden. The finding that prompted this asked for the
decorator to be moved into a service or shared package; it stays at `api/dto/FieldErrorDecorator`
because the Agent Action Plan places it there in §0.3.1, mandates in §0.3.3 that
`AccountUpdateService` invoke it, and fixes the package at exactly its listed files - moving the file
would leave that list short and break the plan's own mapping from the copybook to its target. The
substance of the request is met instead by putting the *conversion* in the service layer, which is
where the finding wanted it. Second, the decorator does not itself produce a `ValidationException`,
because `api/dto` must not reference `com.carddemo.exception`: collapsing that deliberate duplication
would make the response contract depend on the failure carrier, which is the layer inversion the
plan's §0.5.2 direction forbids. Both divergences err towards keeping the dependency direction
one-way, and neither changes what a client observes.

### DL-081 - A browse cursor crosses the wire in full and is withheld from every diagnostic rendering

`PageMetadata` carries the two boundary keys the legacy screens retain across a
pseudo-conversational turn, and on the card list those keys are not opaque tokens the module minted.
`app/cbl/COCRDLIC.cbl` L230 to L235 declares `WS-CA-LAST-CARDKEY` and `WS-CA-FIRST-CARDKEY` as a
16-character card number followed by an 11-character account identifier, so each cursor is a primary
account number concatenated with an account identifier: 27 characters of regulated data. The client
nevertheless has to receive both, because a cursor-based browse cannot be resumed without them and
the legacy computes no offset to substitute.

The two obligations are separated by scope rather than traded off. `previousCursorKey()`,
`nextCursorKey()`, the JSON wire form, `equals` and `hashCode` carry and compare both cursors byte for
byte. `toString()` replaces both with the module's fixed redaction placeholder. Without an explicit
rendering a record emits every component, so the leak was not something the type did but something it
omitted to prevent - and page metadata accompanies every page of every browse, which is the widest
exposure surface in the paging contract.

The redaction is unconditional, including when a cursor is absent, so the rendering discloses nothing
about either value - not even whether one is present, which `hasMorePages()` and `hasPreviousPages()`
already publish as data. A partial mask was rejected for the reason recorded on the placeholder: the
leading 16 characters of a card cursor are a card number in full, the trailing 11 are an account
identifier, and a digest of a 27-character numeric key is reversible by enumeration. The five
remaining components - the row count, the direction, the two availability flags and the displayed page
indicator - are retained, because none of them names a record and together they are what a paging
diagnosis actually asks for.

### DL-082 - The two screen message slots are withheld from diagnostics, because the legacy assembles them dynamically

`ScreenWorkArea` already withheld its three business keys from `toString()`. Its two message slots
were retained on the reasoning that they carry catalogue text written for a terminal operator. That
reasoning does not survive the source. `CCARD-ERROR-MSG` is loaded from `WS-RETURN-MSG PIC X(75)`,
declared at `app/cbl/COACTUPC.cbl` L479 and moved into the slot at L1008 and L1030, and that one
program assembles the field with 32 `STRING` statements. Three of them concatenate a business key into
the text - an 11-character account identifier at L3674 to L3683 and again at L3723 to L3732, and a
9-character customer identifier at L3773 to L3782 - each followed by the raw CICS response and reason
codes. Three more, at L3693, L3743 and L3792, move in `WS-FILE-ERROR-MESSAGE` from L389, which names
the failing operation and the internal VSAM resource, `CUSTDAT` and `CXACAIX` among them, alongside
those same codes.

A slot that can hold a cardholder identifier or an internal resource name cannot be rendered on the
strength of the cases where it holds a sentence, and nothing distinguishes the two at run time. Being
a boundary type, this record additionally accepts whatever a client echoes back into either slot, so
the content is not even guaranteed to have originated in the module. Both slots are therefore replaced
by the fixed placeholder, unconditionally and for the same reasons as the three keys.

The alternative the finding offered - modelling catalogue-safe and dynamic messages as separate
components - was declined and is recorded here rather than left implicit. `ScreenWorkArea` reproduces
`app/cpy/CVCRD01Y.cpy` field for field, and the copybook declares one error slot and one return slot;
splitting either would put state in the work area that no legacy turn carried, and the module would
then have to decide per message which slot to use, a classification the source never makes. What is
withheld from the rendering is withheld from the rendering only: `errorMessage()`, `returnMessage()`,
the wire form and equality still carry every byte, because the operator-facing message is part of the
screen contract and must reach the terminal unaltered.

Four routing components remain in the rendering - the resolved attention key and the declared next
program, mapset and map. Each draws on a fixed vocabulary of enumerated keys and eight- or
seven-character program, mapset and map names, so none can carry an identifier, and together they
answer what a screen-turn diagnosis asks: which key was pressed and where the flow was headed.

### DL-083 - A conflict carrier accepts only the text its own arm resolves to, and the boundary renders the conflict from the arm

**Context.** `OptimisticLockConflictException` publishes four verbatim legacy operator texts and classifies every conflict into one of three arms. Its schema-mandated five-argument constructor accepted any detail message alongside any arm, so an account-record conflict could be constructed carrying the customer lock text, or carrying text belonging to no arm at all. `GlobalExceptionHandler.handleOptimisticLockConflict` then published `getMessage()` as the only discriminator a client receives, which made the unvalidated message the effective contract and the validated arm a bystander.

**Legacy authority.** The legacy write path never composes operator text at all. At `app/cbl/COACTUPC.cbl` lines 3912, 3939, 4079, 4098, 4143 and 4189 it sets a condition name - `COULD-NOT-LOCK-ACCT-FOR-UPDATE`, `COULD-NOT-LOCK-CUST-FOR-UPDATE`, `LOCKED-BUT-UPDATE-FAILED` or `DATA-WAS-CHANGED-BEFORE-UPDATE` - and the wording is a level-88 `VALUE` bound to that condition name at lines 517 to 524. The classification owns the text there; nothing in the program can pair one flag with another flag's wording.

**Decision.** The canonical constructor now requires `message` to equal `conflictKind.defaultMessage(entityName)` and rejects anything else with `IllegalArgumentException`. The constructor is retained rather than removed, because the five-argument form is mandated by the target file schema; what is removed is its freedom, not its existence. The null-arm check runs first, so a caller who omits the arm is told the arm is missing rather than being told its text does not match an arm that was never supplied. The rejection diagnostic does not echo the refused text, so a value arriving from outside the module cannot ride an exception message into a log. `handleOptimisticLockConflict` now renders the 409 body from `conflictKind().defaultMessage(entityName())`, which is provably the same bytes as `getMessage()` and removes the possibility of the two disagreeing.

**Why equality and not membership.** Membership of the four published literals was the weaker option and was declined. It would still admit an account-entity conflict carrying the customer lock text, which reads as a fifth legacy state that no legacy path can reach: the customer read at `app/cbl/COACTUPC.cbl` line 3921 is entered only once the account read at line 3894 has already succeeded, so the customer lock flag can never be set while the account record is the one being reported.

**Consequence for the tests.** Three tests asserted the defect as their premise - two in `OptimisticLockConflictExceptionTest` and one in `GlobalExceptionHandlerBaselineTest` - and were inverted into rejection tests rather than accommodated, because a test that pins a contradiction in place is itself the finding.

*Cited by:* `exception/OptimisticLockConflictException.java`, `api/GlobalExceptionHandler.java`.

### DL-084 - Only the online abend code carries its text outward; every other abend answers with the legacy default

**Context.** `GlobalExceptionHandler.handleAbend` returned `AbendException.getMessage()` for every abend. `AbstractCobolStep` composes that message for the batch tier as `ERROR <gerund> <resource> - FILE STATUS IS: <rawStatus>`, so any endpoint that let a batch abend propagate would publish an internal data set name and a raw two-character COBOL status to an end user.

**Legacy authority - two channels, one of them not facing a person.** The online routine at `app/cbl/COACTUPC.cbl` lines 4203 to 4224 sends the whole abend area to the terminal with `EXEC CICS SEND` and then abends under code `9999`, substituting `UNEXPECTED ABEND OCCURRED.` when no message was set. Text carried under that code was therefore written to be read by an operator. The batch routine is different in kind: the nine `CALL 'CEE3ABD'` sites - `app/cbl/CBACT01C.cbl` line 173 among them - pass the abort routine a code and no message whatever, and the diagnostic that preceded them went to `DISPLAY`. `app/cbl/CBACT01C.cbl` lines 110 to 113 are the pattern: display which file failed, move the raw status into the I/O status field, display that, then abend. None of it reached a terminal; all of it reached the job log.

**Decision.** The handler classifies by abend code. An abend under `AbendException.ONLINE_ABEND_CODE` publishes its message unchanged, because that is the channel the legacy pointed at a person. Every other code answers with `AbendException.DEFAULT_MESSAGE` - the legacy's own substitute literal - and the withheld message is written to the error log in full alongside the code, culprit and reason. Nothing is lost; it moves to the channel the legacy used for it.

**Why an allow-list and not a deny-list.** Excluding only `BATCH_ABEND_CODE` would let an abend raised under any unrecognised code publish whatever text it happened to carry. Admitting only the one operator-facing code fails closed, which is why an abend under a code the estate does not use is treated as internal rather than assumed safe.

*Cited by:* `api/GlobalExceptionHandler.java`.

### DL-085 - A framework fault is summarised by what it was, not reported as an unreadable body

**Context.** The terminal handler answered every self-classifying framework fault with the unreadable-body summary. The status was preserved and the diagnostic was false: a caller who used an unsupported method, sent an unsupported media type or asked for an unavailable representation was told its request body could not be read, and sent looking for a payload defect that did not exist. The summary was also redundant, because the exception that genuinely means an unreadable body has its own handler.

**Decision.** The neutral summary is chosen from the declared status. Method-not-allowed, not-acceptable, unsupported-media-type and route-not-found each get their own accurate neutral text; any other caller-caused status gets a summary that is unspecific rather than wrong; a server-side status gets the same terminal literal every other terminal failure uses, so a framework fault that is genuinely ours does not imply the caller did something. Each text names no method, no media type, no header and no path: the allowed-method set already travels in the `Allow` header the framework populates, and repeating it in the body would turn an error summary into an inventory of the routing table.

**Why route-not-found does not reuse the record-not-found literal.** `Record not found` is a verbatim legacy text meaning a keyed read resolved to nothing. A request that matched no route never reached a read, so borrowing that text would report a data outcome for a routing outcome.

**Why a numeric comparison rather than a switch over the status enum.** `HttpStatusCode` is an interface, and a framework fault may declare a status that resolves to no enum constant, so comparing values avoids a nullable intermediate.

*Cited by:* `api/GlobalExceptionHandler.java`.

### DL-086 - A missing field error is a defect to raise, not a hole to close quietly

**Context.** `ValidationException` filtered `null` elements out of a supplied field-error list. The two sibling carriers on the same path - `FieldErrorDecorator` and `ErrorResponse` - both reject them through `List.copyOf`, so the three disagreed about the same input.

**Decision.** A `null` list still means no per-field detail, because that is a caller legitimately saying it has none and is exactly what the summary-only constructors produce. A `null` element is now rejected. The two cases are not the same case: an element that is `null` is a producer that believed it had detail for a field and did not, and dropping it silently would let one of the account-update screen's 39 decorated fields disappear between the service that failed it and the boundary that reports it, leaving the caller a shorter list with no indication that anything was lost. Failing at construction is the only outcome that preserves the count the producer intended, and it brings all three carriers into agreement.

*Cited by:* `exception/ValidationException.java`.


### DL-087 - The echoed identity is untrusted input, so the predicate over it is named for the byte and reconciliation is part of the type's surface

**Context.** `NavigationContext` models the sixteen fields of `app/cpy/COCOM01Y.cpy` field for field, as the target design requires, and two of them are identity: `CDEMO-USER-ID` and `CDEMO-USER-TYPE`. The type exposed a predicate `administrator()` over the echoed type byte. Nothing about the name said the byte came from the client, and in a REST module the whole record round-trips through the caller, so a consumer reading `context.administrator()` had an authorization-shaped answer derived from input.

**What changed between the legacy and the migration, and it is a change of trust rather than of logic.** The legacy area lived in CICS-managed storage. A program authored it, CICS carried it to the next turn, and the 3270 terminal had no way to reach it. The identity bytes were server-authored from an authenticated read: `app/cbl/COSGN00C.cbl` line 226 writes the identifier and line 227 moves `SEC-USR-TYPE` out of the `USRSEC` record the program had just read, and only then does line 230 test `CDEMO-USRTYP-ADMIN` to choose between the administrative menu and the main menu. The byte the legacy branched on had already been proved against the user-security table. Echoing the same area through a REST client removes that proof entirely.

**Decision.** Three changes, none of which touches the sixteen modelled components.

The predicate is renamed `echoesAdministratorCode()`. It is named for what it reads rather than for the person it might be mistaken to describe, and its documentation states outright that it is not an authorization check. It is retained rather than removed, because the legacy tested exactly this condition on exactly this byte and a faithful translation has to be able to read it.

`agreesWith(String, UserType)` is the reject remedy: it reports whether the echoed identifier and role both match the authenticated principal's, so a caller that would rather refuse a tampered turn can detect one. Both comparisons are exact - the identifier byte for byte with no trim and no case fold, because the component is documented as travelling exactly as received and the user-security key is fixed-width, and folding here would let two distinct echoed identifiers reconcile against one principal. An undeclared or absent echoed code never matches a declared principal role; it disagrees, which is the safe direction.

`reconciledWith(String, UserType)` is the overwrite remedy, and it is the one that reproduces the legacy arrangement most closely: it returns a copy whose identifier and type byte come from the principal, restoring the property that those two bytes are server-authored. The role is written back as its declared one-character code so the reconciled instance still carries a raw byte and still round-trips like any other, and an absent principal role clears the byte rather than inventing one. The other fourteen components cross byte for byte, because correcting identity is not licence to rewrite echoed navigation state.

**Declined alternatives.** Removing or privatising the predicate was rejected: the legacy condition would then be unreadable from the type that carries the byte, and a consumer would re-derive it inline where no documentation could reach it. Constraining the component to the two declared characters was also rejected, and for the reason already recorded on the component itself - `app/cbl/COSGN00C.cbl` closes at line 240 with an unconditional alternative and no third branch, so the legacy routes an undeclared value rather than rejecting it.

**Consequence for the tests.** Three display names asserted the defect in prose - one describing the predicate's input as "an authorization decision's input" and two describing the echoed byte as granting or reporting administrative authority - and were rewritten as factual statements rather than deleted, because a name that misdescribes what a test proves is itself a finding.

*Cited by:* `api/dto/NavigationContext.java`.


### DL-088 - The published contract stays; the interactive viewer is not advertised, because the build cannot carry one

**Context.** The shipped configuration described an interactive viewer for the generated interface document: `application.yml` declared its address, its display options and a bundle version written as the Maven filtering token `@swagger-ui.version@`, and `application-local.yml` reopened it with a comment explaining that the viewer was how a request is tried against a running compose stack. `OpenApiConfig` repeated the claim in its class documentation, naming "the pinned viewer bundle version the build file substitutes into it".

**None of that was true, for two independent reasons.** The build declares no `swagger-ui.version` property and no resource-filtering block at all, so the token was never substituted and shipped into the artefact verbatim as the value. And the browser asset bundle that renders the page - `org.webjars:swagger-ui` - is deliberately excluded from the starter as a vulnerability remediation, so the address had no assets to serve under any profile and could only ever answer not-found. Four files told a story that a fifth contradicted.

**Decision.** Stop advertising the viewer; keep publishing the contract. The unresolvable token, the viewer address and both display options are removed. `springdoc.api-docs.path` and the local overlay's `springdoc.api-docs.enabled` are untouched, because the machine-readable document is what the interface-contract acceptance criterion reads and it is served correctly. The local overlay now inherits the disabled viewer switch instead of restating it, which is deliberately the opposite of the pattern it uses for every genuine concession: a switch a profile restates reads as a choice that profile could reverse, and this one cannot be reversed by configuration at all.

**One thing is kept that looks redundant and is not.** `springdoc.swagger-ui.enabled: false` stays declared in the shared baseline. The library's own default for that switch is true, so leaving it unstated would have the library advertise and route an address whose assets were excluded from the build. The switch is therefore load-bearing rather than a posture statement, and the production overlay restates it for the same inheritance reason it restates the management block.

**Declined alternatives.** Adding a pinned viewer bundle back was rejected on two grounds: the exclusion is a remediation of a reported vulnerability found by an executed scan, and the dependency manifest is required to carry no front end coordinate. Switching to the API-only starter was also rejected, although it is the cleaner-looking option: the target design names `org.springdoc:springdoc-openapi-starter-webmvc-ui` by exact coordinate, and this starter's version locator is deliberately retained so that its configuration classes load exactly as they do with the bundle present. Changing the artefact would deviate from the design to achieve an outcome that removing four keys achieves without deviating at all.

**Consequence for the tests.** Two assertions encoded the false claim - one requiring the local overlay to reopen the viewer, one requiring the baseline to state the viewer address - and were inverted rather than deleted. A general assertion was added in their place: no shipped value may be a `@name@` build token, in any document. That is the assertion the original defect would have failed, and an environment reference of the `${NAME}` form is untouched by it, because those are legitimate and deliberate throughout these files.

*Cited by:* `config/OpenApiConfig.java`, `application.yml`, `application-local.yml`, `application-prod.yml`, `pom.xml`.


### DL-089 - Comment volume was reduced where the content was redundant, and retained where it is the parity contract

**Context.** A review measured the module's production Java at roughly 72% comment lines against roughly 13% in the neighbouring COBOL, and asked that essays, repeated provenance, option catalogues and line-by-line narration be condensed while durable rationale, parity traps, external constraints and security trade-offs be retained. Every category named was measured rather than estimated, and the measurements decided what happened to each.

**What was redundant, and was removed.** `JpaAuditConfig` stated the decision not to enable framework auditing twice in full, once as a paragraph and once as a headed section with the same three facts, and gave the reason the clock is UTC three times over; it also enumerated all five views of the legacy date work area field by field with widths and line numbers, for a class that implements none of them. `WebMvcConfig` carried a bean-semantics essay restating what its own annotation and its absence of fields already say, and said "nothing is injected" in three places - a class paragraph, a constructor paragraph and a constructor body comment. A statement-template test opened with a paragraph that restated three of the five list items immediately below it. Across the test tier, 481 standalone comment lines consisted only of dashes, equals signs or hashes: banner rulers carrying no information, whose label lines were kept. Those removals total roughly 600 comment lines and lose nothing, because every fact removed was still stated once elsewhere or was never a fact.

**What was measured and deliberately retained.** A repeated-sentence analysis over the fourteen highest-volume files found only 36 redundant sentence occurrences, and most were `@param` tags that Javadoc requires once per overload rather than prose a reader meets twice. The accessor documentation - 1,045 lines, five per cent of the total - carries the declared field width of each component and, on the middle-name and second-address-line setters, the recorded prohibition against validating them; deleting it would remove the only place in the type where a maintainer meets the constraint that adding validation there rejects input the legacy system accepts. The per-member documentation in the date-validation service documents 116 private methods that are the paragraph-for-paragraph translation the traceability matrix maps, each carrying the legacy paragraph and line it descends from. All of that is the retained category, not the condensed one.

**Repeated provenance was assessed and left alone, on evidence.** The checkout identifier appears in 15 production and 77 test files. Removing it looked like the largest available reduction and is not: it is 189 lines, under one per cent of the volume, spread across fourteen distinct phrasings in which the identifier is interleaved with file-specific legacy authorities. A mechanical transform would orphan headings and break sentences mid-flow across 92 files for a change too small to measure, and a careful one would consume exactly the effort that produced the 600 lines above.

**The applied migration cannot be edited at all, and that outranks comment density.** `V2__create_indexes.sql` is 263 comment lines over nine statements and was named as an example to condense. It cannot be: it declares in its own header that it is immutable once applied, `application.yml` sets `validate-on-migrate: true`, and the local database records V1 and V2 as applied and successful with their checksums stored. Editing a single comment character changes the checksum and fails the next migration of any database that already has it - and because the same configuration sets `clean-disabled: true`, a developer could not recover through the migration tool. A comment-density preference does not justify breaking a versioned migration's checksum. Any future condensation of that text must arrive as a new version, exactly as the file says.

**One structural fact belongs on the record, because it explains the ratio better than verbosity does.** The production tier is presently data-transfer objects, entities, enumerations, utilities, exceptions and configuration: file kinds whose executable content is small by nature, where a record of sixteen components is sixteen documented parameters over almost no statements. The comment share is a ratio whose denominator is small for that reason, and it falls as the service and controller tiers land without a further line being deleted.

*Cited by:* `config/JpaAuditConfig.java`, `config/WebMvcConfig.java`.

### DL-090 - The second-generation JSON library returns to the frozen inventory version, and the annotations divergence is explained rather than papered over

**Context.** A supply-chain scan reported a moderate finding against the second-generation JSON library: properties excluded by an ignore-properties declaration are restored when contextual case-insensitive binding is in force. A previous revision cleared it by raising `jackson-bom.version` from the inventory's 2.21.4 to 2.21.5, and justified the raise in the build file with the claim that governing the family bill of materials' own property makes core, databind, annotations and every data-format and data-type module move together. A later review found that the raise contradicted its own justification: the effective tree resolved the streaming core, databind and the format and type modules at 2.21.5, and the annotations module at 2.22.

**Decision.** The property returns to **2.21.4**, the version the frozen dependency inventory records. Three findings decided it. The inventory is frozen and no exception authorises editing it, so a version that departs from it needs an authority that does not exist here. The finding's severity sits below the threshold at which the scan fails a build, so nothing was gated on the raise. And the finding's precondition does not hold in this module at all: contextual case-insensitive property binding is enabled nowhere, and no ignore-properties exclusion is declared anywhere in the production tree, so there is no excluded property for the defect to restore. The raise bought no protection this module needed and cost agreement with the inventory.

**The annotations divergence has a different cause, and survives the revert.** The 2.22 resolution was never attributable to this property. Neither bill of materials ties the annotations artifact to the patch version: the second-generation one versions it minor-only, and the third-generation one - which has no annotations module of its own and reuses the second-generation artifact - pins it to the newest 2.x release. The third-generation declaration is the one that wins, because `tools-jackson.version` is set to 3.2.1 for the structured logging encoder. So annotations resolves one minor ahead of the rest of the family by upstream design, and still does after the revert. That is safe, because the 2.x annotations contract is compatible across the line - which is precisely why both bills of materials version it separately and why the third generation reuses it instead of shipping a replacement - and the inventory names the databind coordinate, which this value fixes exactly. The build file's comment now says this rather than claiming a uniformity the resolution does not have.

**Declined alternatives.** Forcing the annotations artifact to the rest of the family's version was rejected twice over: no 2.21.4 annotations release exists, and pinning it back to 2.21 would fight the third-generation bill of materials that the logging encoder depends on, trading a documented and compatible one-minor lead for a real risk to a working component. Suppressing the moderate finding was rejected as unnecessary, since it scores below the failure threshold and is therefore reported without gating. Leaving the raise in place and merely correcting the comment was rejected because the departure from the frozen inventory, not the comment, was the finding.

**Consequence for the tests.** The effective dependency tree is asserted rather than assumed: the whole second-generation family resolves at 2.21.4 with the annotations artifact at its own line, and the third-generation coordinates remain at 3.2.1 and reach the classpath only through the logging encoder.

*Cited by:* `pom.xml`.

### DL-091 - Build identity is generated, because the information endpoint was described as publishing something the artifact did not carry

**Context.** The shared configuration exposes the information endpoint and describes it as confirming build and runtime identity, with the environment contributor deliberately disabled so that no property whose name begins with the exposed prefix can be published. A review found the artifact carried neither generated build information nor version-control information, so the endpoint answered with process and runtime detail alone. The configuration was describing a surface the build did not produce.

**Decision.** The framework's `build-info` goal is bound as a second execution of the packaging plugin, so the generated properties file is written during resource generation and packaged into the artifact. The build-information contributor then has something real to publish, and the endpoint's description becomes true. Binding the goal is also the only way to make it true: the file records the build time, so it cannot be authored by hand without becoming stale the moment it is committed.

**The payload is deliberately minimal, and is asserted to be.** The generated file carries the group, artifact, name, version and build time - nothing else. That matters because the environment contributor is disabled precisely to keep configuration values out of the endpoint, and a generated file is a second route to the same surface. A test therefore asserts the exact key set rather than merely asserting that the file exists, so a future revision that adds environment-derived entries fails rather than quietly widening what the endpoint publishes.

**A second consequence, which is a benefit rather than the reason.** The API-documentation configuration already read the build-information bean when present and fell back to a compiled constant when absent. With the goal bound it reads a real value, so the published contract version tracks the artifact instead of a hand-maintained literal. The two mechanisms are independent - one writes a file, the other reads a bean bound from it - so their agreement is asserted rather than assumed.

**Declined alternatives.** Narrowing the configuration's description to match what was actually published was the cheaper option and was rejected: the endpoint is the module's build-identity surface, a deployment needs to know which build is running, and removing the claim would have removed the capability rather than corrected a statement about it. Adding version-control information as well was rejected as scope beyond the finding, which named build metadata; it would also introduce a build-time dependency on repository state that a source archive does not carry.

**Consequence for the tests.** The generated file is read off the class path and asserted for its coordinate values, a non-blank build time, and its exact key set; the published contract version is asserted to equal the stamped version. The absent-build-information and stamped-without-version paths stay asserted against a stub, because the fallback must keep working in a class tree this build did not assemble.

*Cited by:* `pom.xml`, `config/OpenApiConfig.java`.


### DL-092 - The job-submission queue keeps its legacy resource name, reversing an earlier decision to namespace it

**Context.** The legacy estate's entire online-to-batch bridge is a CICS transient-data queue named `JOBS`, written from exactly one site. The target's queue was configured as `carddemo-jobs.fifo`, on the reasoning that an infrastructure resource name and an operator-visible message are two different contracts, that only the second is compared byte for byte, and that an unqualified four-character name could collide with an unrelated queue in an account hosting other workloads. A review found the value disagreed with the resource the environment actually provisions, which is `JOBS.fifo`.

**Decision.** The queue is named `JOBS.fifo`: the legacy resource name plus the single suffix the queue service requires of a first-in-first-out queue, and no other transformation. The value is standardised across the shared configuration, the local overlay, the test configuration, the container composition and the emulator bootstrap. The production profile continues to resolve it from the environment with no fallback.

**Why the earlier reasoning does not survive.** The distinction it draws is real, but the conclusion drawn from it is not. The resource name is itself part of the frozen inventory and no exception authorises editing it, so "only the message is byte-compared" does not license renaming the resource - it explains only why the message is unaffected by the suffix, which it is. The collision concern is a deployment concern that the existing environment override already answers without changing what the module ships. Uniformity with the namespaced bucket and topic was the weakest of the three arguments: those two replace sequential datasets and a screen message and have no legacy resource name to carry, so consistency was deciding a case that something else already decided. The queue is the only external interface this module publishes to, and an external interface's contract is reproduced rather than renegotiated.

**The disagreement was not cosmetic.** A name that is well formed but names nothing is the exact input that the publishing template's default behaviour resolves by creating a queue - see DL-093. The namespaced value and the create-on-absence default together made a silent failure reachable: a submission would report complete, and the cards would sit in a queue nothing consumes. Correcting the name and fixing the strategy are two halves of one correction.

**The operator-visible text is untouched.** The failure message names the transient-data queue verbatim as `JOBS`, without a suffix, and is asserted character for character. It is frozen text rather than a reference to the resource, so nothing composes one from the other and the suffix cannot reach it.

**Consequence for the tests.** Every test constant carrying the queue name moves to `JOBS.fifo`, including the fixtures that assert the queue name does not leak into a response body - those assertions remain true, because the frozen message contains `JOBS)` and not `JOBS.fifo`. The rejection cases for the mandatory suffix are re-expressed against the new name and still include the unsuffixed `JOBS`, which must continue to be refused. A configuration test asserts that every document fixing the queue resolves it to `JOBS.fifo` and that the production profile fixes no default at all.

*Cited by:* `application.yml`, `application-local.yml`, `application-test.yml`, `docker-compose.yml`, `localstack/init/01-create-aws-resources.sh`, `service/JobSubmissionService.java`.


### DL-093 - An unresolvable queue is refused rather than created, because the library default makes a wrong name look like a successful submission

**Context.** The messaging library leaves its queue-not-found strategy unset, and the publishing template it builds then defaults to creating a queue whose name it cannot resolve. Inspection of the library confirmed this is reached by omission rather than by declaration: the properties object initialises no default for the strategy, the auto-configuration forwards it only when present, and the template's own options initialise it to creation. A review found the shipped configuration declared nothing, so the effective behaviour was to create.

**Decision.** The shared configuration fixes `spring.cloud.aws.sqs.queue-not-found-strategy` to `FAIL`. A queue that cannot be resolved is refused, and the submission fails through the publisher's existing non-fatal path.

**Why creation is wrong here specifically.** The legacy queue was defined to be open at initialisation, so it existed before first use and the application never created it; provisioning is the environment's responsibility, and the emulator bootstrap and container composition are where it lives. More sharply, creation converts a configuration error into a silent data-loss path: a name that is well formed but wrong is created on the spot, every card publishes successfully into it, the submission reports complete, and nothing ever consumes the queue. No error surfaces at start-up or at publish. This is fail-fast rather than hardening - it does not defend against an attacker, it makes a mistake audible.

**Declined alternatives.** Leaving the default and relying on the environment to provision correctly was rejected because it makes correctness depend on two files agreeing with no mechanism that notices when they do not. Creating the queue from application start-up code was rejected as the same fault in a different place, and because it would give the application an authority the legacy program never had.

**Consequence for the tests.** Two levels are asserted, because one cannot stand in for the other. Over the configuration documents, a test requires the strategy to be declared in the shared baseline and, using the framework's own property sources layered in profile order, requires it to still resolve to refusal with the local overlay on top - the overlay declares an endpoint under the same parent node, and the assertion establishes that a sibling key cannot displace it. At runtime, an integration test publishes to a well-formed name for a queue that does not exist and requires that the submission fails, that the operator sees the frozen literal, and that no queue comes into being. An existing integration test's comment, which described the create-on-absence default as the behaviour the application publishes with, is corrected: that default is what a template built inside a test carries, and it is no longer what the application carries.

*Cited by:* `application.yml`, `service/JobSubmissionService.java`.


### DL-094 - The withdrawn record-width and failure-tolerance keys, which read as configuration while nothing bound them

**Context.** The configuration declared `carddemo.aws.sqs.record-length` as `80` and `carddemo.aws.sqs.fail-on-error` as `false`, in the shared baseline, the local overlay and the test configuration. A review found no Java consumer bound either one. Both values were inert: the publisher enforced the width against its own constant and was non-fatal by construction, and editing either key changed nothing.

**Decision.** Both keys are removed from every document. The two facts remain where they are enforced - the width as the constant the publisher checks each card's encoded byte count against, the tolerance as the publisher catching a failed write and reporting it through its return value rather than rethrowing - and the legacy provenance of both, the queue definition's record size and its error option, is recorded as prose against the queue-contract block and in the traceability matrix.

**Why removal rather than binding.** The review offered either. Binding them was rejected because neither is a deployment choice: a different width is a different record format and a different tolerance is a different failure contract, so neither describes this queue. A validator admitting only `80` and only `false` would relocate the pretence rather than remove it, leaving a switch with one position; and for the tolerance it would be worse than inert, because a value the validator rejects at start-up can never be observed, so any branch on the bound value would be unreachable code that no test could cover. Configuration should express what a deployment may decide. These two express what the legacy definition already decided.

**Consequence for the tests.** A configuration test asserts both keys are absent from all four documents, so the pseudo-configurable surface cannot return unnoticed. The width and the non-fatal path keep their existing direct assertions against the publisher, which is where the behaviour actually lives.

*Cited by:* `application.yml`, `application-local.yml`, `application-test.yml`.


### DL-095 - One card write is one attempt, because the client's retry strategy would reissue a write the legacy program performed once

**Context.** The legacy reporting program writes one card with one `WRITEQ TD` and inspects that write's response immediately; there is no retry around it. A review found the module published through the auto-configured client, whose standard retry strategy classifies a refused transport as transient and reissues, so one logical card write could become several on the wire.

**Decision.** An AAP-sanctioned configuration class publishes a client customizer that installs the no-retry strategy on the queue client builder. One publish is one attempt. No client is built here, no region, endpoint or credential is resolved here, and no timeout, attempt count, backoff interval or pool size is set - removing the retry is the absence of a figure rather than the choice of one, which is what makes it a parity statement rather than a tuning decision.

**Why the divergence mattered.** A reissue that eventually succeeded would turn a failure the legacy program reports into a success it never had, changing which cards reach the queue and therefore whether the submitted job stream is complete; and it would deliver a card after the publisher had already been told the write failed, which the append-ordered contract cannot absorb.

**The implementation detail that is load-bearing.** The customizer extends the builder's existing override configuration rather than replacing it. The convenience form that accepts a consumer of a configuration builder reads as though it amends and does not: it constructs a fresh configuration, applies the consumer to that, and replaces the builder's configuration wholesale. Because the messaging auto-configuration installs its own override configuration before customizers run, that form would discard the library's client-identification option while still satisfying an attempt-count assertion. The configuration is therefore copied and one setting added to it.

**Declined alternatives.** A separately named client and template dedicated to this publisher would isolate it more literally, change nothing observable, and oblige the publisher to select its collaborator by name rather than by type. This module has exactly one queue interaction - it publishes and consumes nothing - so there is nothing to select between and the single client is configured instead. Retry counts expressed as configuration were rejected for the reasons in DL-094.

**Consequence for the tests.** Three properties are asserted. A unit test requires the customized builder to report a single attempt. A second requires a seeded advanced option to survive the customizer, and it fails if the implementation is ever simplified to the replacing form - verified by making that substitution and observing that this test alone failed while the attempt-count assertions still passed, which is precisely the silent-discard hazard. An integration test counts attempts on the wire against a socket that answers every request with a retryable server error: the customized client issues one request, and an otherwise identical client without the customizer issues more than one, so the comparison is evidence of cause. The uncustomized assertion is deliberately "more than one" rather than an exact figure, because the library's default count is the library's to change and what must not change is that this module does not inherit it.

*Cited by:* `config/AwsConfig.java`, `service/JobSubmissionService.java`.


### DL-096 - Request authorization is stated as one explicit chain, because the framework's defaults answered a question the configuration had already answered differently

**Context.** The module carried the security starter and declared no filter chain of its own. Two consequences followed, and a review found both. The shared configuration described the metrics scrape endpoint as an unauthenticated surface and one profile reopened the interface description, while the framework's own defaults permit the health probe and authenticate everything else - so the collector would have been refused and the description unreachable, and the configuration's description of reachability was simply untrue. Separately the framework supplied an interactive login backed by a generated in-memory user whose password is written to the log at start-up, which is the authentication mechanism the module would actually have run.

**Decision.** One chain is declared explicitly and closes by default. Its final rule authenticates any request no earlier rule named, so a route reachable without a credential is reachable because a rule says so. The health probe and the scrape endpoint are permitted individually; the interface description is permitted only in a profile that publishes it, decided by binding the same switch that decides whether it is served; the sign-on route is permitted because it is the route that issues credentials; every remaining management endpoint is required to be authenticated by a rule that precedes the administrative and catch-all rules, so a profile that widens its exposure list widens what a credential reaches and never what anonymity reaches; the administrative region requires the administrative entitlement; everything else requires a credential.

**Why the order is the contract.** These rules are first-match-wins. Moving the management rule after a broader permit, or the catch-all before the administrative gate, changes behaviour without changing any single rule's text. The order is therefore asserted rather than described - by issuing requests and reading statuses, never by inspecting the configuration that produced them, because an inspection would agree with a chain assembled wrongly.

**A matcher chosen rather than defaulted.** Rules compare path patterns with the same parser this framework generation uses to map a request to a handler, which is what makes a rule and a request mapping unable to disagree: a request the administrative rule does not match is a request the administrative handler does not receive. The available alternative resolves each rule through the dispatcher's own handler registry and reaches the same conclusion, but only inside a context where that registry has been published - so the boundary's behaviour would depend on surrounding configuration and could not be exercised on its own. The explicit choice keeps the rules a property of the chain, which is what makes the status assertions possible at all. Patterns are expressed relative to the dispatching servlet, as a request mapping is, and the servlet's path is bound rather than assumed: were it moved beneath a prefix while the rules stayed put, the administrative gate would stop matching the administrative routes while the catch-all kept refusing anonymous callers, so the only visible change would be that an administrator's entitlement was no longer required.

**The generated user is withdrawn, not merely unused.** Declaring an authentication manager is what causes the framework's user-details auto-configuration to stand down, and with it the generated credential and the log line that discloses it. The manager this module declares refuses every credential presented to it, because the module authenticates by token and has no credential-verifying provider; accepting anything there is how an accidental authentication happens. That the withdrawal is real is proved by a control: the same surroundings, without this module's manager, do supply the generated user, so the assertion that it stood down discriminates rather than passing because nothing had offered one.

**Refusals leave the boundary in the module's own shape.** A refusal raised inside a filter is never seen by the dispatch-level exception advice, so without handlers here the two paths would answer the same condition with two different bodies - one of them a container error page. A custom entry point and access-denied handler render the module's error record, and the summary text has a single home shared with the advice rather than a copy in each. A missing credential is answered as unauthorized and names the expected scheme; an insufficient entitlement is answered as forbidden and deliberately does not, because presenting a different credential is not what that caller should do. Neither answer names the rule that refused or the entitlement that would have satisfied it.

**One guard was removed after being proved unreachable.** The refusal writer initially skipped a committed response, on the reasoning that appending to one would corrupt it. Both handlers are invoked only by the chain's exception-translation filter, which checks for a committed response first and raises instead of calling them - so the guard could never execute. It was removed rather than kept: a check in a place it cannot be reached from reads as though the case were possible and can never be shown to work. The framework's behaviour is asserted directly instead, by a route that commits its answer and only then fails authorization.

**Consequence for the tests.** Every assertion is a response status, header or body obtained by driving a real chain from a real context. Both directions are covered for each rule, so a permit is distinguished from a missing handler by reading the body, and a refusal is attributed to the rule that produced it. That the suite discriminates was established by mutation rather than assumed: withdrawing the scrape permit failed exactly the scrape assertion, and downgrading the administrative gate to bare authentication failed exactly the entitlement assertions, with the remainder still passing.

*Cited by:* `config/SecurityConfig.java`, `api/GlobalExceptionHandler.java`, `application.yml`, `application-local.yml`.


### DL-097 - Bearer tokens replace the communication area, using the signing primitive already on the classpath

**Context.** The legacy conversation carries its state in a communication area that the terminal manager hands back on the next turn, including the signed-on user's type. A stateless service has no such carrier. The configuration already declared a token issuer and a token lifetime, and nothing bound, minted or verified anything.

**Decision.** A symmetric-keyed token carries the subject and the user type, and is minted and verified by one component using the library already resolved on the compile classpath. No dependency is added: the resource-server starter that would supply a ready-made bearer filter is deliberately absent from the inventory, and the inventory is frozen, so the chain carries a small filter of its own instead. The algorithm is fixed in code rather than configured, because a configurable algorithm is a configurable way to weaken verification, and the same reasoning keeps the minimum key length out of configuration.

**Fail-fast, and on the operator's terms.** The settings bind through a validated record whose absent or blank values stop start-up naming the key that is missing, and whose lifetime must be positive. Validation of presence is expressed by annotations and validation of positivity by the constructor, which deliberately tolerates a null lifetime so that an absent value is reported as absent rather than as a failure raised before validation runs. The signing value has no default anywhere, in any profile, including the shared baseline - a defaulted secret violates the no-hardcoded-credential constraint exactly as a literal one does. The description the record renders redacts the secret and does not disclose its length.

**Verification is closed by default.** A presented token must carry the expected issuer and must be within its window; the window is judged against an injected clock, which is what makes expiry assertable without waiting. Signature failure, issuer mismatch, expiry, malformation and absence are answered identically, so nothing is learned from the difference. A verification failure logs the failure's type and never its message, because such a message can quote the offending token.

**A trap confirmed by execution rather than by reading.** The issuer this module publishes is a service label and not a locator. The token accessor that reads an issuer is typed as a locator and would fail on such a value, while the raw claim reads back as text - so the claim is read as text and the typed accessor is never called. This was established by minting and verifying a token before the component was written, not inferred from the type signature.

**A deliberate parity exception, recorded as such.** The legacy record holds an eight-character password compared literally. The module hashes credentials with an adaptive function at a cost above the library default. This diverges from the legacy behaviour knowingly, in the direction the credential constraint requires, and is the reason the hasher is published here as the module's single hashing policy rather than chosen per component.

*Cited by:* `config/JwtProperties.java`, `config/JwtTokenProvider.java`, `config/SecurityConfig.java`.


### DL-098 - Cross-site request protection is disabled because there is no ambient authority to protect, and transport security is enforced rather than described

**Context.** A stateless token-authenticated service and a session-authenticated one need opposite defaults, and the difference is easy to get wrong in either direction. Separately, the configuration declared that transport security was mandatory while nothing read the setting.

**Decision on cross-site requests.** The protection is disabled deliberately and the reason is recorded at the point of the decision. It defends a request that carries authority the browser attaches on its own - a session cookie. This chain creates no session, sets no cookie, and establishes an identity only from a credential the caller must place in a header deliberately, which a cross-site form post cannot do. With no ambient authority there is nothing for such a request to borrow, and a token that must be added by script is already a stronger check than the token the protection would add. That the premise holds is asserted rather than assumed: an authenticated request establishes no session, a refused one sets no cookie, and no identity survives the response.

**Decision on transport.** The setting is bound and enforced. Every insecure request is redirected rather than served, and the rule is left at its default scope - which is every request - because narrowing it would exempt some route from transport security without recording which one. The supported form of the rule is used: the older channel-security configurer expresses the same thing, is deprecated in this framework generation, and a deprecated call is a build failure here rather than a warning to be carried. The two profiles that run on loopback clear the requirement, and that relaxation is load-bearing rather than decorative, since under the shared value a plain request to either would be answered with a redirect to a port nothing is listening on.

**Consequence for the tests.** The transport rule is asserted in both postures and across several paths, including the surfaces that need no credential, so an exemption cannot be introduced unnoticed. Every profile is required to state the setting explicitly, because the chain reads it with no default of its own.

*Cited by:* `config/SecurityConfig.java`, `application.yml`, `application-local.yml`, `application-prod.yml`.


### DL-099 - The published contract names its exemptions from the chain's own constants

**Context.** The interface description applied a document-wide credential requirement while the runtime used the framework's generated-user login, so the document described a protection nothing implemented. A future sign-on operation would additionally have inherited that requirement, advertising a credential as necessary on the one operation that exists to issue credentials.

**Decision.** The requirement stays and becomes true, because the chain now exists and closes by default. The exemption is stated in the same declaration and is built from the chain's own published route constants rather than restated, so editing the rule edits the document. Those constants are the first route addresses this module publishes, and they are published precisely so that a future controller, the rule that exempts or gates it, and the sentence that describes it all bind to one value.

**What the document does not claim.** The health probe, the scrape endpoint and the description itself carry no operation in the document at all, so the requirement cannot be read as applying to them. The description attributes enforcement to the chain and states that nothing in the document grants access. No credential, token or example authorization value appears anywhere in it.

**Why agreement is asserted mechanically.** A prose exemption and a rule are two statements of one fact and can drift. Tests read the addresses back out of the chain's constants, so a document describing a route the chain no longer exempts cannot pass. What catches an accidental permit remains the chain's own status assertions, not this document.

*Cited by:* `config/OpenApiConfig.java`, `config/SecurityConfig.java`.


### DL-100 - A diagnostic log records the outcome of validating a submitted value and never the value itself

**Context.** The legacy estate's only diagnostic channel was the console-display statement, and the migration replaces those with structured logging. The date-validation service reproduces a callable subprogram and an eleven-stage copybook cascade, and its diagnostics were written in the spirit of the original: they echoed the candidate that had been submitted, and on one path they echoed the parser's own failure text as well. That reads as helpful and is the natural thing to write.

**Why it is not acceptable here.** The candidate is external, fixed-width text carried in a single-byte character set, so it may contain any byte that set admits - including a line separator. A log record built by appending such a value can be split into what looks like two records, and the second can be given the shape of a record the surrounding system would trust. That is not a hypothetical exposure in this module: the local profile raises this package to its most detailed level deliberately, so the statements concerned are the ones that actually run while a developer is watching. Two distinct weaknesses are present in the same line - a record that carries submitted content, and a record whose structure that content can alter.

**Decision.** A diagnostic in the validation path records only values the module itself owns: the outcome, the identifier of the format mask, the severity, and the module's own message number. It records no candidate, no subject and no third-party exception message. The parser's message is the more dangerous of the two omissions, because it quotes the offending text back verbatim - logging it would reintroduce the candidate by a route that reads as a library detail rather than as external input.

**One value needed a distinction rather than a rule.** A format mask that resolved to a known format is safe to write, because the value written is the module's own enumeration literal rather than what the caller sent. A mask that resolved to nothing is exactly the value an attacker chooses, so that path records its length and not its content. The two cases are separated at the point of logging rather than handled by one blanket rule, because a blanket rule would also have suppressed the identifier that makes the record useful.

**Why the level is not the control.** It would be possible to make the disclosure conditional by lowering the level, and that was rejected. A level is a deployment setting and can be raised by anyone diagnosing a problem, which is precisely when these records are read. The property is instead a property of the statements themselves, so raising the level cannot turn a diagnostic into a disclosure.

**How it is held.** The recorded output is captured and asserted: no submitted candidate appears in any record of this service on any branch, including the branch that accepts a value the subprogram flagged, and no record carries a line feed, a carriage return or any other control byte. The assertions were confirmed to discriminate by restoring the original statements and observing that most of them fail.

*Cited by:* `application-local.yml`, `service/DateValidationService.java`.


### DL-101 - A surface that is declared before it carries anything says so, and an assertion holds the statement to the delivered state

**Context.** Several parts of this module are deliberately established before there is anything for them to hold. The seed migration location is declared and listed by the two profiles that will need it, so that the first seed script is written into a place production already cannot see. The interface description is contributed unconditionally, so the contract stays assertable under every profile. In both cases the arrangement is right and the accompanying text was wrong: it described reference rows the seed location did not yet carry and a migration reaching a version the delivered scripts do not reach, and it introduced the interface description as the description of the endpoints derived from the legacy screens while the delivered tree contains no request-mapped controller and the document therefore carries no path at all. A reader was told a thing existed and handed nothing.

**Decision.** Where a surface is declared in advance, the declaration stays and the text states what is delivered. The migration comments name the two delivered scripts, say that the seed location carries none yet, and say how far a migration therefore reaches. The interface description says, in the served document as well as in the configuration comments, that it carries identity metadata, the reusable message shapes and the authentication rule, and no endpoint inventory. Neither correction weakens the arrangement it describes; the structural guarantee that production cannot reach the seeds, and the closed-by-default posture of the description, are both unchanged and are stated as holding in advance rather than as awaiting the thing they protect.

**Why a corrected sentence is not enough on its own.** A statement about a state stops being true when the state changes, and the moment it changes is exactly the moment nobody is reading these comments. So each statement is asserted against the delivered artefacts rather than against a second copy of itself. The migration claim is compared with the highest version actually present under either location, so adding any migration fails the build until the sentence is updated. The description's claim is compared with the paths the document actually carries, in both directions: an unlabelled empty document fails, and so does a labelled populated one. Both assertions were confirmed to discriminate by adding the missing artefact and observing the expected failures.

**What is not done.** No migration is invented to make the earlier sentence true, and no controller is added to populate the document. Both belong to work this milestone does not cover, and writing either to satisfy a comment would be feature expansion.

*Cited by:* `application.yml`, `application-local.yml`, `config/OpenApiConfig.java`.


---

*This log is authored alongside the target module and is never edited by the code that cites it. A
citation is a pointer into this document; the reasoning lives here in one place so that it cannot
drift between the files that depend on it.*
