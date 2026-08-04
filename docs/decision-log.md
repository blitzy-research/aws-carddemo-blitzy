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
`db/migration/schema/V1__create_schema.sql`, `service/SensitiveFieldEncryptionService.java`.

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

*Cited by:* `db/migration/schema/V1__create_schema.sql`.

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

*Cited by:* `db/migration/schema/V1__create_schema.sql`.

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
`db/migration/schema/V1__create_schema.sql`, `exception/OptimisticLockConflictExceptionTest.java`.

*Also recorded as:* D-15 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### D-12 — Credential storage: BCrypt digest format delivered, sign-on path NOT YET DELIVERED
`SEC-USR-PWD PIC X(08)` in `app/cpy/CSUSR01Y.cpy` holds the credential as eight cleartext characters
at offset 48, and `app/cbl/COSGN00C.cbl` compares it directly against the entered value.
Reproducing that would satisfy parity and breach the no-cleartext-credential constraint at the same
time. **Decision:** the credential column is sized 60 to hold a BCrypt digest — never the legacy
width of 8, and never a cleartext value — and no component may store or compare a cleartext
credential.
**Status: the storage format, the encoder and the seed are delivered; the sign-on path is not.**
`service/CredentialDigestService` is delivered and is the only component that turns a credential into
a stored value: it wraps `BCryptPasswordEncoder`, produces a 60-character digest, exposes a verifying
comparison, and refuses at the persistence boundary any value that is not digest-shaped.
`config/SecurityConfig` is delivered and carries the filter chain and the authentication manager, and
`config/JwtTokenProvider` is delivered. `V4__seed_user_security.sql` delivers the ten local and test
identities, each credential an independently salted 60-character BCrypt digest at cost 12, so the
column holds digests and no cleartext value anywhere.

What is **not** delivered is the sign-on path that would consume them: `service/AuthenticationService`,
the translation of `COSGN00C`, and `api/AuthController` do not exist, so no request is authenticated
against the credential column yet. This entry therefore records a partially met requirement, and the
boundary has moved since it was first written: the storage format, the encoder and the seed are
delivered controls, while the authenticating comparison is still absent. Whatever component fills that
gap inherits the obligation stated above — write only a digest, never store or compare a cleartext
credential — and `CredentialDigestService.requireDigest` already exists to enforce the first half of it.
*Embodied in:* `src/main/resources/db/migration/schema/V1__create_schema.sql` (column shape),
`src/main/resources/db/migration/seed/V4__seed_user_security.sql` (digests),
`service/CredentialDigestService.java` (encoder, verifying comparison and persistence guard),
`config/SecurityConfig.java`, `domain/UserSecurity.java`, `api/dto/SignOnRequest.java`.

*Also recorded as:* DL-001, DL-002, DL-003 and DL-004, which record the same position from the
other side. The digest-format invariant is delivered and enforced by the credential entity, and the
encoder, the verifying comparison and the persistence-boundary guard are delivered in
`service/CredentialDigestService`. What still belongs to the authentication and user-management
services is the sign-on path that calls them. The two framings — "not yet delivered" here and "a
scope boundary" in DL-003 — state one fact: no component yet authenticates a request against the
credential column, and the stored-shape invariant plus the persistence guard are what stand in for
one until it does.

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
`domain/Customer.java`, `resources/db/migration/schema/V1__create_schema.sql`.

*Also recorded as:* DL-005, DL-006, DL-007, DL-008 and DL-009, which develop the same decision in
detail — the fail-closed entity guard, the equality-search capability that randomised encryption
costs, the per-profile key binding with no fallback anywhere, and the namespace rule the
cryptographic primitives are referenced under.

### D-14 — Card primary account number and verification code: gap UNCLOSED
The legacy design applies no field-level encryption, tokenisation or masking to either value, and no
requirement in scope introduces one. **Decision:** none is invented, because that would be feature
expansion. The gap is carried forward here as an explicit unclosed finding rather than silently
closed or silently ignored.
*Recorded against:* `src/main/resources/db/migration/schema/V1__create_schema.sql`.

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

The rule reaches three kinds of value, and the third is the one an implementation is most likely to
miss. A *rejected* value is obvious. A *developer-supplied label* — a field name or a record-layout
name woven into the message — is less obvious, because it reads as the message's own prose while in
fact arriving from a caller; such a label is sanitised rather than rejected, degrading to a
substitute that names the offending character's position and code point, because the guard sits on a
path that is already failing and a diagnostic must never itself fail. A *configured value* written
into a log statement is the least obvious of the three: parameter substitution escapes nothing, so a
queue name or a message group carrying a carriage return splits one log record into two just as
surely as an echoed field value would. All three are covered.

*Embodied in:* `util/ZonedDecimalCodec.java`, `util/FixedWidthFieldReader.java`,
`util/StatementTextTemplates.java`, `util/StatementHtmlTemplates.java`,
`util/ReportLineFormatter.java`, `service/JobSubmissionService.java`.

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

"Structurally prevents" is a claim about *where* the check sits, and it is only true if the check sits
at the **input boundary** — on each caller-supplied value as it enters a builder — rather than only on
the assembled record on its way out. An outbound-only check does reject the record, but it reports a
defect in an image the caller never handed over, so the diagnostic cannot name the parameter at fault;
and it leaves each newly added free-text builder unguarded until someone remembers to re-check the
record. Every free-text `String` parameter of every builder in the three classes below is therefore
validated on entry, and the assembled-record check is retained behind it as a second line that also
covers a record supplied whole by a caller.

*Embodied in:* `util/ReportLineFormatter.java`, `util/StatementTextTemplates.java`,
`util/StatementHtmlTemplates.java`. The diagnostic these guards emit is governed by D-16 / DL-041:
the line-terminator branch fires precisely when the value holds a carriage return or a line feed, so a
guard that echoed the value there would put a raw terminator into a log line by construction.

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

*Cited by:* `db/migration/schema/V1__create_schema.sql`.

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
the two properties that actually matter. By the same scoping, a literal the class states as its own
*expectation* — the `.fifo` suffix a queue name must carry, the code-point range a field admits — is
prose and not an echo, because it describes what was required rather than what was supplied.

The sink is any channel a human or a tool later reads, not merely an exception message. A log
statement is such a channel, and parameter substitution escapes nothing, so a value interpolated into
a log record is subject to this rule exactly as a value interpolated into a rejection message is.
Where a value is bound from configuration and then written into every log record about an operation,
the rule is satisfied at the boundary — the value is required to be printable US-ASCII when it is
bound — rather than at each of the log statements that consume it, so that adding a log statement
later cannot reopen the hole.

Two idioms discharge the rule, chosen by whether the guard is on a failing path already. A guard that
*rejects* throws, naming the parameter, the admissible range, and the offending character's
zero-based position and code point. A guard on a value that merely *labels* another failure
*degrades*, returning a substitute of the same shape, because throwing there would replace the
original diagnostic with a second, unrelated one and the real defect would be lost.

*Embodied in:* `util/ZonedDecimalCodec.java`, `util/FixedWidthFieldReader.java`,
`util/StatementTextTemplates.java`, `util/StatementHtmlTemplates.java`,
`util/ReportLineFormatter.java`, `service/JobSubmissionService.java`. Applying this decision to the one
value that arrives from *outside* the module - the queue client's own exception text, which reached the
service log until it was withdrawn - is DL-103. The queue payload boundary that
protects the published card itself is DL-042; this decision protects everything the module *says*
about a card, an identity or a field.

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
| 3 | `app/csd/CARDDEMO.CSD` defines `PROGRAM(COCRDSEC)` with no corresponding source member, and the developer transaction `DEFINE TRANSACTION(CDV1)` is bound to exactly that definition — so the transaction was never dispatchable either | No target is generated for either the program or the transaction; recorded as a dangling resource definition and its dangling binding. This is why the navigation vocabulary derives seventeen destinations from eighteen registered transactions rather than eighteen — see DL-104 |
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

### DL-067 — The vulnerability gate scans the deployable graph, carries no suppressions, and proves the boundary with an executed test

**Status. Both of this entry's original positions are withdrawn.** It previously recorded that
test-scope dependencies were scanned and that a suppression file entry remained, "scoped as narrowly
as the schema permits". The scan no longer covers test scope, the suppression file and its wiring are
deleted, and the gate now carries zero suppressions of any kind.

**What was wrong with the earlier arrangement.** The gate is required to tolerate zero critical and
zero high findings. Scanning a scope that cannot be remediated forces a choice between failing the
build forever and suppressing what the scan reports, and the earlier arrangement took the second.
That is the defect: in a report, a suppressed high finding is indistinguishable from a resolved one.
The gate went on passing while three high findings stood, which satisfies the letter of a zero
tolerance rule by editing the evidence rather than by changing the software.

**Decision.** The scan covers the compile and runtime graph, which is what ships, and says so. The
suppression file is deleted together with the configuration that referenced it. Every finding the
gate now reports is a real finding at its real severity, and nothing above the failure threshold
survives.

**What had been suppressed, stated so that narrowing is not mistaken for hiding.** Three high
findings. One scored 7.5 against the asynchronous transport that reaches this build at runtime scope
through the object-store starter; narrowing the scope would not have touched it, because it ships
inside the deployable jar, so it was resolved by moving the version forward instead, recorded in
DL-115. The other two scored 7.5 against a relocated copy of an HTTP core library embedded inside the
container-testing transport, and no version change can reach them: the vulnerable classes were
confirmed physically present inside the shaded artifact, so no managed coordinate addresses them;
that transport is the only one the container-testing library will construct, so it cannot be excluded
or substituted; the library's newest published release still embeds the same copy; and its next major
line is excluded by this module's dependency inventory.

**Why narrowing is not the same act as suppressing.** A suppression asserts that a finding is
acceptable. Narrowing asserts something different and stronger — that the finding is not present in
the artifact this project ships. Only the second claim is checkable, and it is now checked rather
than asserted.

**The boundary is held by an executed test, not by this paragraph.**
`DeployableSupplyChainIT` opens the repackaged jar and sweeps its bundled libraries for test-scope
artifacts, naming the container-testing transport in an assertion of its own. It carries two control
assertions so that the sweep cannot pass vacuously: one that the jar really is repackaged and bundles
libraries at all, and one that the libraries expected at runtime are present. A sweep that finds
nothing because it is looking at nothing would fail those controls.

**Residual exposure, stated rather than hidden.** The narrowed scan no longer reports findings that
exist only on the build surface. That surface is a developer machine and a continuous-integration
runner, not the deployed artifact, and the two findings it currently leaves unreported are
unreachable by any version change available to this module. If either becomes reachable — the
container-testing library ships a fixed copy, or its transport becomes substitutable — the remedy is
to take that fix, not to widen the scan and suppress the result again.

**One setting is deliberately kept although nothing currently exercises it.** Failing the build on an
unused suppression rule stays enabled even with no suppression file present. It costs nothing while
there are no suppressions, and it means that if anyone ever adds one that stops matching, the build
reports a stale exemption instead of carrying it silently.

**Two moderate findings remain and are deliberately not suppressed.** They score below the failure
threshold, so they are reported without gating, which is the disposition the threshold exists to
express.

*Cited by:* `pom.xml`, at the vulnerability scan configuration and at the scope property;
`src/test/java/com/carddemo/support/DeployableSupplyChainIT.java`. The forward-pinning remedy applied
to the third finding is DL-115.

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
| `api/dto/CardUpdateRequest.java` | DL-103 |
| `api/dto/CardUpdateResponse.java` | DL-103 |
| `api/dto/FieldErrorDecorator.java` | DL-080 |
| `api/dto/NavigationContext.java` | DL-087 |
| `api/dto/PageMetadata.java` | DL-081 |
| `api/dto/ScreenWorkArea.java` | DL-011, DL-030, DL-082 |
| `api/dto/SignOnRequest.java` | DL-001, DL-004 |
| `batch/step/AbstractCobolStep.java` | DL-084 |
| `config/FlywayConfig.java` | DL-041, DL-102, DL-110, DL-119 |
| `config/JpaAuditConfig.java` | DL-089 |
| `config/OpenApiConfig.java` | DL-088 |
| `config/SeededIdentifierSealingCallback.java` | DL-041, DL-110 |
| `config/FixedLocaleMessageInterpolator.java` | DL-118 |
| `config/ProductionSeedRejectionCallback.java` | DL-041, DL-110, DL-119 |
| `config/WebMvcConfig.java` | DL-089, DL-118 |
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
| `service/JobSubmissionService.java` | DL-041, DL-042, DL-043, DL-117 |
| `service/AccountConcurrencyTokenService.java` | DL-074, DL-075, DL-076, DL-077 |
| `service/CardConcurrencyTokenService.java` | DL-075, DL-103 |
| `service/FieldErrorTranslationService.java` | DL-080 |
| `service/SensitiveFieldEncryptionService.java` | DL-005, DL-008 |
| `util/AccountRecordMapper.java` | DL-013, DL-017, DL-034, DL-035, anomaly register (1) |
| `util/ReportLineFormatter.java` | DL-041, anomaly register |
| `util/SqsNamingRules.java` | DL-041, DL-042, DL-117 |
| `config/FixedLocaleMessageInterpolator.java` | DL-042, DL-118 |
| `util/CobolStringUtils.java` | DL-078 |
| `util/SensitiveFieldCodec.java` | DL-009 |
| `util/ZonedDecimalCodec.java` | DL-041, DL-079 |
| `util/FixedWidthFieldReader.java` | DL-041 |
| `util/StatementTextTemplates.java` | DL-041 |
| `util/StatementHtmlTemplates.java` | DL-037, DL-038, DL-039, DL-041 |
| `resources/application.yml` | DL-001, DL-005, DL-008, DL-047, DL-048, DL-088, DL-127 |
| `resources/application-local.yml` | DL-045, DL-047, DL-088, DL-127, anomaly register (7) |
| `resources/application-prod.yml` | DL-088, DL-127 |
| `pom.xml` | DL-088 |
| `resources/db/migration/schema/V1__create_schema.sql` | DL-005, DL-007, DL-010, DL-012, DL-036, DL-127, anomaly register (1) |
| `resources/db/migration/schema/V2__create_indexes.sql` | DL-127 |
| `resources/db/migration/seed/V3__seed_reference_data.sql` | DL-103, DL-127 |
| `resources/db/migration/seed/V4__seed_user_security.sql` | DL-127 |
| `resources/db/migration/.gitkeep` | DL-127 |
| `api/dto/SignOnRequestTest.java` | DL-001, DL-003, DL-004 |
| `domain/enums/AccountStatusTest.java` | DL-024, DL-026 |
| `exception/OptimisticLockConflictExceptionTest.java` | DL-012 |
| `util/StatementHtmlTemplatesTest.java` | DL-037 |
| `service/AccountConcurrencyTokenServiceTest.java` | DL-074, DL-076, DL-077 |
| `config/FlywayConfigTest.java` | DL-041, DL-127 |
| `config/FlywayConfig.java` | DL-102, DL-110, DL-127 |
| `config/SeededIdentifierSealingCallbackTest.java` | DL-041, DL-104 |
| `service/FieldErrorTranslationServiceTest.java` | DL-080 |
| `util/CobolStringUtilsTest.java` | DL-078 |
| `util/ZonedDecimalCodecTest.java` | DL-013, DL-014, DL-015, DL-016, DL-041, DL-079 |
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
| `domain/TransactionCategoryBalance.java` | D-37, D-38 |
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
| `resources/db/migration/schema/V1__create_schema.sql` | D-12, D-14 |
| `service/AbendService.java` | D-41 |
| `service/JobSubmissionService.java` | D-09, D-16, D-36 |
| `util/AccountRecordMapper.java` | D-02, D-04, D-08, D-10, D-11, D-26, D-29, D-30, D-42, anomaly 20 |
| `util/CobolStringUtils.java` | D-17, D-18, D-19, D-22, anomaly 18 |
| `util/CobolStringUtilsTest.java` | anomaly 18, 19 |
| `util/FixedWidthFieldReader.java` | D-06, D-10, D-11, D-16, D-42, anomaly 20 |
| `util/FixedWidthFieldReaderTest.java` | D-08, D-10, D-11, D-16, anomaly 20 |
| `util/JclCardImageBuilder.java` | D-27, D-36 |
| `util/JclCardImageBuilderTest.java` | D-06, D-08, D-11 |
| `util/PfKeyTranslator.java` | D-20, D-31 |
| `util/ReportLineFormatter.java` | D-01, D-05, D-06, D-09, D-11, D-16, D-27, D-30 |
| `util/ReportLineFormatterTest.java` | D-02 |
| `util/StatementHtmlTemplates.java` | D-09, D-16, D-27, D-44 |
| `util/StatementHtmlTemplatesTest.java` | D-27, D-30, D-49 |
| `util/StatementTextTemplates.java` | D-05, D-06, D-09, D-16, D-27, D-30 |
| `util/StatementTextTemplatesTest.java` | D-05, D-06, D-09, D-16, D-27, D-44 |
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

### DL-090 - The second-generation JSON library takes its security patch at 2.21.5; the revert to the inventory version is withdrawn

**Context.** This entry has been decided three times, and the third decision returns to where the first arrived by a better route. A supply-chain scan reported a moderate finding against the second-generation JSON library: under a vulnerable annotation combination, a property excluded by an ignore-properties declaration becomes writable again. The first revision cleared it by raising `jackson-bom.version` from the inventory's 2.21.4 to 2.21.5, and justified the raise in the build file with the claim that governing the family bill of materials' own property makes core, databind, annotations and every data-format and data-type module move together. A second revision found that justification false - the effective tree resolved core, databind and the format and type modules at 2.21.5 and the annotations module at 2.22 - and reverted the property to 2.21.4 on three grounds: the inventory is frozen, the finding scores below the threshold at which the scan fails a build, and the finding's precondition holds nowhere in this module because contextual case-insensitive binding is enabled nowhere and no ignore-properties exclusion is declared. A third review then identified the reverted pin as the finding itself, named the advisory as CVE-2026-54515 at 5.3 and network-reachable, required the upgrade with a repeated closure, compilation and scan, and stated that the advisory may not be suppressed unless applicability is formally disproved.

**Decision.** The property is **2.21.5**, and the revert is withdrawn. Each of the revert's three grounds fails, and it is worth being precise about how, because two of them are arguments that will be made again about some other coordinate.

*The frozen inventory freezes which library is used, not the right to take its security patches.* The inventory names `com.fasterxml.jackson.core:jackson-databind` and records the version an executed resolution produced when the plan was written. 2.21.5 is a patch release of that same coordinate on that same minor line: nothing is substituted, no artifact is added or removed, and no API contract moves. Reading the recorded version as a prohibition on patching it would make the inventory a mechanism for retaining known-vulnerable code, which is the opposite of what freezing it is for - and the same plan that records the version also requires the scan to be executed rather than declared, which is only worth doing if its output can change something.

*A finding below the gate threshold is still a reported finding.* The build fails on critical and high, which is a gate, not an acceptance standard. The advisory is reported in the scan output either way, and "reported but not gated" describes what the tool does, not whether the module should carry the defect. The revert treated the threshold as permission.

*Non-applicability was a statement about a snapshot of the source tree, not about the dependency.* The precondition argument was true when made and remains true today: nothing here enables contextual case-insensitive binding and nothing declares an ignore-properties exclusion. It is also not a disproof of applicability, because it is not a property of the classpath. The affected deserialiser serves the whole request and response surface, and whether some future data transfer object carries the triggering annotation combination is a decision the next author of one makes - not something this entry can settle on their behalf. Formal disproof would have to show the defect unreachable by construction; nothing available here shows that.

**Suppression was rejected, and no suppression file exists.** Suppressing an advisory records a judgement that it does not apply, which is exactly the judgement that could not be established above. The scan therefore runs with no suppression input at all, which also means a future advisory against this coordinate cannot be silently inherited by an existing entry.

**The annotations divergence is unchanged by the raise, and its explanation is the durable part of the earlier entry.** The 2.22 resolution was never attributable to this property. Neither bill of materials ties the annotations artifact to the patch version: the second-generation one versions it minor-only, and the third-generation one - which has no annotations module of its own and reuses the second-generation artifact - pins it to the newest 2.x release. The third-generation declaration wins, because `tools-jackson.version` is 3.2.1 for the structured logging encoder. So annotations resolves one minor ahead of the rest of the family by upstream design, both before and after this raise. That is safe, because the 2.x annotations contract is compatible across the line - precisely why both bills of materials version it separately and why the third generation reuses it rather than shipping a replacement.

**Declined alternatives.** Staying at 2.21.4 and documenting non-applicability more thoroughly was rejected: that is the defence the review examined and did not accept, and thoroughness does not convert a statement about today's sources into a property of the classpath. Moving the family to the 2.22 line was rejected as more than the fix requires: the patch release clears the advisory, and a minor move changes more surface for no additional benefit. Forcing the annotations artifact down to the rest of the family's version was rejected twice over, as before: no 2.21.5 annotations release exists, and pinning it back would fight the third-generation bill of materials the logging encoder depends on, trading a documented compatible one-minor lead for a real risk to a working component. Raising the property without correcting the build file's comment was rejected because the comment had been left asserting that the value restates the inventory and therefore pins rather than overrides, and a comment that misdescribes the declaration beneath it is the same class of defect as the one this entry is remediating.

**Consequence for the build, measured rather than assumed.** An executed resolution confirms the second-generation family - streaming core, databind, the JDK 8 and JSR-310 data types, the parameter-names module and the TOML and YAML data formats - all at 2.21.5, the annotations artifact at 2.22 on its own line, and the third-generation coordinates still at 3.2.1 reaching the class path only through the logging encoder. An executed rescan against vulnerability data checked the same day reports no finding of any kind against any Jackson coordinate. No test encoded the reverted literal, so nothing in the suite had to be edited to agree with this decision - which is itself worth recording, because it means the build file was the only place the value was stated.

**One advisory remains, and it is a different situation rather than an inconsistency with the above.** The rescan reports exactly one finding: CVE-2026-41178 at 5.3 against `io.opentelemetry.semconv:opentelemetry-semconv` 1.43.0, reached transitively through the tracing exporter. Its identifier names the **Go** implementation of OpenTelemetry - the platform component of the matched product identifier is Go, and the described defect is in that implementation's baggage-header parsing - so the match is a version-number coincidence with a Java artifact of a similar name. That is a disproof of applicability by product identity, which is the kind the Jackson advisory could not be given, and it needs no version to upgrade to because the Java artifact is not the affected product. It is nonetheless left reported and unsuppressed, for the reason given above.

*Cited by:* `pom.xml`.

### DL-091 - Build identity is generated, because the information endpoint was described as publishing something the artifact did not carry

**Context.** The shared configuration exposes the information endpoint and describes it as confirming build and runtime identity, with the environment contributor deliberately disabled so that no property whose name begins with the exposed prefix can be published. A review found the artifact carried neither generated build information nor version-control information, so the endpoint answered with process and runtime detail alone. The configuration was describing a surface the build did not produce.

**Decision.** The framework's `build-info` goal is bound as a second execution of the packaging plugin, so the generated properties file is written during resource generation and packaged into the artifact. The build-information contributor then has something real to publish, and the endpoint's description becomes true. Binding the goal is also the only way to make it true: the file records the build time, so it cannot be authored by hand without becoming stale the moment it is committed.

**The payload is deliberately minimal, and is asserted to be.** The generated file carries the group, artifact, name, version and build time - nothing else. That matters because the environment contributor is disabled precisely to keep configuration values out of the endpoint, and a generated file is a second route to the same surface. A test therefore asserts the exact key set rather than merely asserting that the file exists, so a future revision that adds environment-derived entries fails rather than quietly widening what the endpoint publishes.

**A second consequence, which is a benefit rather than the reason.** The API-documentation configuration already read the build-information bean when present and fell back to a compiled constant when absent. With the goal bound it reads a real value, so the published contract version tracks the artifact instead of a hand-maintained literal. The two mechanisms are independent - one writes a file, the other reads a bean bound from it - so their agreement is asserted rather than assumed.

**Declined alternatives.** Narrowing the configuration's description to match what was actually published was the cheaper option and was rejected: the endpoint is the module's build-identity surface, a deployment needs to know which build is running, and removing the claim would have removed the capability rather than corrected a statement about it. Adding version-control information as well was rejected as scope beyond the finding, which named build metadata; it would also introduce a build-time dependency on repository state that a source archive does not carry.

**Consequence for the tests.** The generated file is read off the class path and asserted for its coordinate values, a non-blank build time, and its exact key set; the published contract version is asserted to equal the stamped version. The absent-build-information and stamped-without-version paths stay asserted against a stub, because the fallback must keep working in a class tree this build did not assemble.

*Cited by:* `pom.xml`, `config/OpenApiConfig.java`.


### DL-092 - The job-submission queue is named `carddemo-jobs.fifo`, which the plan mandates; an intermediate decision to carry the legacy name is withdrawn

**Context.** The legacy estate's entire online-to-batch bridge is a CICS transient-data queue named `JOBS`, written from exactly one site. This entry has been decided twice. The queue was originally configured as `carddemo-jobs.fifo`. An intermediate revision renamed it to `JOBS.fifo`, reasoning that an infrastructure resource name and an operator-visible message are two different contracts, that the legacy resource name is itself part of the frozen inventory, that no exception authorises editing it, and that the module should therefore agree with the resource the environment provisions. A subsequent review found that the renamed value disagrees with the name the plan actually prescribes.

**Decision.** The queue is named **`carddemo-jobs.fifo`**, and the intermediate rename is withdrawn. The plan states the four AWS resource names under the heading that they are *mandated, not chosen*, requires them **byte-identically**, and repeats this one six times - in the canonical-name table, in the heading of the section provisioning the queue, in that section's own instruction, in the sibling-validation list, in a static validation check on the bootstrap script, and in the runtime check that lists the queues. The other three names - `carddemo-batch-staging`, `carddemo-job-submission`, `carddemo-job-notifications` - were never in doubt, so this restores uniformity across the set rather than creating an exception in it. The value is standardised across the shared configuration, the local overlay, both copies of the test configuration, the container composition, the emulator bootstrap, the continuous-integration workflow and the publishing service. The production profile continues to resolve it from the environment with no fallback.

**Why the intermediate reasoning does not survive, step by step.** It is a plausible argument and it fails three times over. *First*, "the resource name is part of the frozen inventory" identifies the wrong inventory: what is frozen for this resource is the target name the plan prescribes, not the legacy name of the construct it replaces. *Second*, the legacy name is not discarded by the rename's withdrawal - it is carried by the operator-visible failure message, which is the contract that actually is compared byte for byte. Reproducing an external interface means reproducing what the interface's consumers observe; a queue's consumers observe messages, and an operator observes the message text. *Third*, "the module should agree with the resource the environment provisions" inverts the dependency. The emulator bootstrap script that provisions the queue is itself a file this module ships and this plan governs, so the module decides the name and the environment follows it. Aligning the module to the environment made the environment the authority over a value the plan had already fixed.

**What survives from the intermediate decision, and is retained.** Its warning was correct and is the reason the name is stated once per place rather than composed anywhere: a name that is well formed but names nothing is the exact input that the publishing template's default behaviour resolves by creating a queue - see DL-093. A disagreement between the configured name and the provisioned name therefore makes a silent failure reachable, in which a submission reports complete while the cards sit in a queue nothing consumes. The remedy is agreement on the mandated value plus the fail-fast strategy of DL-093, not a change of value. The collision concern that namespacing answered also survives as a deployment concern, answered by the environment override without changing what the module ships.

**The operator-visible text is untouched by either decision.** The failure message names the transient-data queue verbatim as `JOBS`, without a suffix and without a namespace, and is asserted character for character. It is frozen text rather than a reference to the resource, so nothing composes one from the other and neither the suffix nor the prefix can reach it. This is why the rename is invisible to the byte-compared contract, and it is also why the intermediate argument's premise about two different contracts was true while its conclusion was not.

**Consequence for the tests.** Every test constant carrying the queue name is `carddemo-jobs.fifo`, including the fixtures that assert the queue name does not leak into a response body - those assertions remain true, because the frozen message contains `JOBS)` and not the resource name. The rejection cases for the mandatory suffix are re-expressed against the mandated name - `carddemo-jobs`, `carddemo-jobs.FIFO`, a trailing-space variant and a suffix-plus-more variant - and the bare legacy `JOBS` is retained among them, because it is unsuffixed and must continue to be refused. A configuration test asserts that every document fixing the queue resolves it to `carddemo-jobs.fifo` and that the production profile fixes no default at all, and the LocalStack-backed integration tier resolves the queue by name against a queue the bootstrap script actually created.

*Cited by:* `application.yml`, `application-local.yml`, `application-test.yml`, `application-prod.yml`, `docker-compose.yml`, `localstack/init/01-create-aws-resources.sh`, `service/JobSubmissionService.java`.


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

**Fail-fast, and on the operator's terms.** The settings bind through a validated record whose missing or blank values stop start-up naming the key that is missing, and whose lifetime must be positive. Validation of presence is expressed by annotations and validation of positivity by the constructor, which deliberately tolerates a null lifetime so that an absent value is reported as absent rather than as a failure raised before validation runs. The shared baseline defaults the signing value nowhere - it declares no secret key at all - and the production profile resolves it from the environment with no fallback beside it, because a defaulted production secret violates the no-hardcoded-credential constraint exactly as a literal one does. The local and test overlays deliberately do carry a fallback, and each fallback's own text states that it is non-production and must not be reused; a fallback is admissible precisely where the tokens it signs can never be presented to a production deployment. One mechanism note belongs with this, because a reader who has it backwards may remove the guard that works: configuration-properties binding resolves placeholders leniently, so an unset production variable binds the reference's own text and satisfies the presence constraint. A blank value is caught by that constraint; an absent one is caught by the production configuration check, which refuses a value still carrying its own placeholder text, and by the signing key-length floor, which that text cannot meet. The description the record renders redacts the secret and does not disclose its length.

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

**Context.** Several parts of this module are deliberately established before there is anything for them to hold. A seed migration surface was declared and listed by the two profiles that would need it, before any seed script existed. The interface description is contributed unconditionally, so the contract stays assertable under every profile. In both cases the arrangement was right and the accompanying text was wrong: it described reference rows the seed surface did not yet carry and a migration reaching a version the delivered scripts did not reach, and it introduced the interface description as the description of the endpoints derived from the legacy screens while the delivered tree contains no request-mapped controller and the document therefore carries no path at all. A reader was told a thing existed and handed nothing.

**Decision.** Where a surface is declared in advance, the declaration stays and the text states what is delivered. The migration comments name the delivered scripts and say how far a migration therefore reaches. The interface description says, in the served document as well as in the configuration comments, that it carries identity metadata, the reusable message shapes and the authentication rule, and no endpoint inventory. Neither correction weakens the arrangement it describes; the guarantee that production does not apply the seeds, and the closed-by-default posture of the description, are both unchanged and are stated as holding in advance rather than as awaiting the thing they protect.

**Why a corrected sentence is not enough on its own.** A statement about a state stops being true when the state changes, and the moment it changes is exactly the moment nobody is reading these comments. So each statement is asserted against the delivered artefacts rather than against a second copy of itself. The migration claim is compared with the highest version actually delivered, so adding any migration fails the build until the sentence is updated. The description's claim is compared with the paths the document actually carries, in both directions: an unlabelled empty document fails, and so does a labelled populated one. Both assertions were confirmed to discriminate by adding the missing artefact and observing the expected failures.

**What is not done.** No controller is added to populate the document. That belongs to work this milestone does not cover, and writing one to satisfy a comment would be feature expansion.

**Superseded in part.** The *mechanism* this entry described for keeping the seeds away from production — a second migration location that production does not list — was withdrawn once the seed scripts were actually delivered. See DL-102. The principle recorded here is unaffected: a surface declared in advance says what it carries, and an assertion holds the statement to the delivered state.

**Superseded in part, and recorded rather than rewritten.** The half of this entry that concerns the
seed *location* no longer describes the delivered arrangement, and the entry is corrected here rather
than edited into silence so that a reviewer who read the earlier text can see what changed and why.
The seed scripts are now delivered, and they sit in the same folder as the schema scripts: one flat
`classpath:db/migration` in every profile. The consequence is that a location list can no longer be
the exclusion mechanism, because one folder cannot be half-visible. Exclusion therefore moved from
*path* to *version*: the shared baseline and the production overlay stop a migration after V2, and the
two profiles that need seeded rows raise that ceiling themselves. The direction of the default is the
point — the restrictive value is the shared one, so seeding is opt-in and a forgotten override yields
a schema-only database that fails loudly on empty result sets rather than one that has quietly
acquired seeded credentials. DL-102 records what the seed may and may not contain once it exists. The
half of this entry that concerns the interface description is unaffected and still holds.

*Cited by:* `application.yml`, `application-local.yml`, `config/OpenApiConfig.java`.

*The migration half of this entry is superseded by DL-102: the second location it describes has been
withdrawn, and the guarantee it provided is now expressed as a version ceiling instead. The interface
description half stands unchanged.*


### DL-102 - One flat migration location, and a version ceiling rather than a second directory

**Context.** An earlier revision kept the schema migrations under `db/migration` and the two seed
migrations under a second location, `db/seed`, and gave production the first location only. The
structural guarantee was real - production could not resolve a script it never scanned - but it was
expressed as a consequence of four profiles' location lists differing from one another, and the plan
this module is built to names all four scripts flat under one location. Reviewing whether production
would receive a seed therefore meant diffing four files and reasoning about which one omitted what,
which is the least reviewable form a one-line decision can take. Separately, every profile declared
that the framework must not create its own job-repository tables, and no migration created them -
so the two environments able to reveal the gap had the framework quietly create them anyway, and
production was the single environment in which the gap was fatal.

**Decision.** All five migrations are flat in `classpath:db/migration`, every profile lists exactly
that one location, and production alone declares `spring.flyway.target: "2"`. The ceiling is what
excludes the seeds: `V1` and `V2` carry the schema and the indexes and sit at or below it, `V3` and
`V4` carry reference data and sign-on identities and sit above it. Production applies the first three
and stops; local and test apply all five. The job-repository tables are created by
`V1_1__create_batch_metadata.sql`, transcribed from `spring-batch-core`'s own
`schema-postgresql.sql`, so exactly one artefact owns them under every profile.

**Why 1.1 and not 5.** The framework schema is a prerequisite of running a job, not a consequence of
the application schema, so it belongs beside `V1` rather than after the seeds - and numbering it above
the ceiling would have withheld it from the one environment that needs it most. A repeatable
migration was considered and rejected: its statements are `CREATE TABLE IF NOT EXISTS`, so a framework
upgrade that changed the schema would be applied as a silent no-op.

**Why the arithmetic is asserted rather than trusted.** A ceiling of 2 protects nothing if a future
seed is numbered `V1_2`, or a required script is numbered `V5`. So the delivered set is compared with
the ceiling by version arithmetic: every script at or below 2 must be one of the three production
applies, and every script above it must be a seed. Adding either kind of script in the wrong place
fails the build rather than changing what production silently receives. The version comparison sorts
numerically and not by name, because `V1_1__` precedes `V1__` as text while a migration applies 1
before 1.1. The transcribed framework DDL is separately compared, statement by statement, with the
script inside the resolved `spring-batch-core` artefact, so raising the framework version and changing
its schema fails with the difference instead of a deployment failing later.

**One-time consequence.** A database already migrated past version 2 has no record of 1.1 and will
refuse to validate until it is recreated. That is stated in the migration's own header and in the
local profile, because the alternative - numbering the framework schema above everything already
applied - would have put a prerequisite after the thing that needs it.

*Cited by:* `application.yml`, `application-local.yml`, `application-test.yml`,
`application-prod.yml`, `db/migration/V1_1__create_batch_metadata.sql`,
`db/migration/seed/V3__seed_reference_data.sql`, `db/migration/seed/V4__seed_user_security.sql`,
`config/FlywayConfig.java` - which is where the ceiling stops being a declaration and becomes a
control: it refuses a production ceiling that reaches version 3 or beyond, refuses a production
location outside the one delivered directory, and lifts the inherited ceiling for the two seeding
profiles. Its class comment records this same reconciliation, so the two read against each other.

*Status.* **Superseded by DL-127.** The premise this entry rests on - that a Flyway location is
scanned recursively, so a directory cannot isolate a seed from a schema while either sits in the
shared parent - is retained and is quoted in the delivered comments. What is reversed is the
conclusion: all four scripts now sit one level down in two sibling locations, the shared parent holds
no script, and production resolves the schema location alone. The version ceiling this entry argues
for is retained behind that location list rather than in place of it. This entry is kept because it
records why the flat arrangement looked correct, and it looked correct three times.



### DL-103 - The seeded government-issued identifier is sealed, and the national identifier is still not carried at all

**Context.** Three parts of this module state that `customer.govt_issued_id` holds an authenticated
ciphertext envelope and never the twenty cleartext characters the legacy record carries at offset 288:
`V1__create_schema.sql` says so and widens the column to make room, `domain/Customer` refuses any value
that is not shaped like one in its constructor and both mutators, and
`service/SensitiveFieldEncryptionService` exists to produce and read exactly that shape. All three held,
and the seed still loaded fifty cleartext identifiers - with a comment explaining the divergence as a
deliberate exemption on the grounds that static SQL cannot produce an envelope without committing a key.
The reasoning was wrong on its own terms. Nothing requires the envelope to be produced *by* the SQL; and
the key it worried about committing was already committed, twice, as a profile default.

**Decision.** All fifty values are sealed. Each envelope was produced by
`service/SensitiveFieldEncryptionService` itself, over the twenty characters at offset 288 of
`app/data/ASCII/custdata.txt`, and embedded in `V3__seed_reference_data.sql` as a fixed 69-character
literal. The schema's contract, the entity's guard and the service's shape therefore all hold for a
seeded row rather than being three claims a seeded row falsifies.

Two properties make that acceptable in a checked-in artefact, and both are required. The value is
invented - a twenty-digit fixture identifier with no subject behind it. And the key is worthless: a
self-describing throwaway that seals nothing outside a database rebuilt from these migrations. No key
material is added to the repository by the change; what is embedded is ciphertext.

**Why the national identifier is still `NULL`.** `cust_ssn` remains unseeded, and the reason is now
stated correctly. The nine bytes the legacy record holds at offset 279 are shaped like real national
identifiers, and sealing them would not protect them: anything sealed under a committed fixture key is
recoverable by anyone holding the repository. The right handling of a value like that in a checked-in
artefact is not to carry it in any form. `cust_ssn` is consequently the one nullable column in `V1`, and
a test that needs a stored national identifier persists one through the application under its own key.

**Why the three non-production profiles now share one key.** An AES-GCM envelope opens under exactly one
key. The local profile and the two copies of the test profile previously bound three values of which two
were different, and every one of those profiles applies `V3`. A sealed seed is therefore only coherent if
they agree, so they bind one shared non-production fixture key,
`carddemo-nonprod-fixture-key!!!!` in Base64. Production is untouched: it binds the same property as an
environment reference with no fallback, and its version ceiling means it never applies `V3` at all, so no
production row is ever sealed under a fixture key.

**Why all three now state that key as a bare literal.** Agreeing on one value was not sufficient, because
two of the three still declared it as `${CARDDEMO_FIELD_ENCRYPTION_KEY:<literal>}`. That form advertises
an override the seed cannot honour: the fifty envelopes are literals in a committed script, nothing can
re-key a literal, and so exporting that variable did not rotate anything - it simply left fifty rows of
regulated data unreadable while every marker-based check still passed. The two packaged non-production
profiles therefore now declare the literal bare, exactly as the suite's own copy always did. The
environment reference survives in production alone, where it carries no fallback for the opposite reason:
production owns real data and must never run under a committed default. This is the same principle
DL-105 records - a bare `${VARIABLE}` states an intention and a guard is what enforces it - applied in the
direction that suits a fixture-bearing profile, where the intention being stated was one that could not be
met.

**Why the envelopes are fixed literals, and how a mis-keyed process is caught.** Each seal draws a fresh
96-bit vector, so the same cleartext seals differently every time and the literals cannot be regenerated
identically. A process configured with any other key therefore holds unreadable rows rather than rotated
ones, and that is not left to be discovered later. Four controls catch it, in the order a deployment meets
them. The profiles no longer offer the override, which removes the ordinary route. `V3`'s own self-check
refuses any row that is not envelope-shaped, the wrong width, a duplicate of another row, or a bare run of
digits - shape only, because a migration holds no key. `config/SeededIdentifierSealingCallback` then
**opens every stored value** under the key the running process actually holds, at the end of every
migration of a seed-bearing profile, and fails start-up on the first value that will not open; this is the
only control that can distinguish a readable envelope from an unreadable one, because an envelope sealed
under a foreign key still carries the `ENC1` marker and so satisfies every shape check. Finally
`SeededProtectedIdentifierIT` opens all fifty through the application service, under the key it reads from
the test profile rather than one written into the test, and compares each recovered value with the fixture
record it came from, so an edited literal or a re-ordered row fails the build as well. A unit-level
assertion additionally counts the complete envelope literals in the delivered script and requires that no
quoted run of twenty digits survives.

**Why that migration-time check authenticates rather than reading through the column binding.** The
seeded envelopes are deliberately *unbound* - their payload is the twenty characters and nothing else,
which is what makes a seeded envelope 69 characters wide and what keeps the seeded form distinguishable
from one the callback produces, which is bound to its column. Reading them through the binding refuses all
fifty, so the check is authenticated decryption, which succeeds for both forms and fails only when the key
is wrong or the bytes were altered. The column binding remains a separate invariant, satisfied by what the
callback writes, deliberately not satisfied by the seed, and enforced where a value is read into the
domain.

**What is not done.** Card numbers and verification codes are still seeded exactly as the fixture holds
them: the legacy design defines no masking, tokenization or encryption for either, and inventing one here
would be unrequested feature work. That gap is recorded rather than closed.

*Cited by:* `db/migration/schema/V1__create_schema.sql`, `db/migration/seed/V3__seed_reference_data.sql`,
`application-local.yml`, `application-test.yml`.


### DL-104 - A test can neither export a trace nor reach a real account, and the two overlays cannot drift apart

**Context.** Three claims the test profile made about itself were untrue, and each was untrue in the same
way: the file described a safety property in prose while the property itself rested on a mechanism that
does not deliver it.

The first was trace export. Both copies of `application-test.yml` set
`management.tracing.sampling.probability` to zero and stated that this switched export off. It does not.
Boot's effective sampler is parent-based over the ratio sampler, so the probability configures only the
ROOT decision: a request arriving with a W3C `traceparent` whose sampled flag is set is sampled whatever
the ratio says, its spans reach the span processor, and the exporter is invoked. A suite run has no
collector, so the failure mode was connection-refused noise on every context refresh - reachable through
any caller that propagates a sampled context, which is exactly what a contract test for an inbound
endpoint does.

The second was the AWS endpoint. The suite overlay declared none, on the reasoning that a support base
class supplies the emulator's address during a suite run. That is true only for a test that extends one of
those base classes, and only for a client that base class built itself. For anything else the SDK's own
resolution applies - and an absent endpoint does not fail closed the way an absent data-source address
does. It resolves the REGION'S REAL PUBLIC ENDPOINT and sends the request there on whatever credentials
the default chain finds, which on a developer machine or a build agent can be real ones. The outcome of
forgetting was therefore not an error but a silent, authenticated request against a real account.

The third was the relationship between the local and test overlays. The test overlay asserted that both
files carried "the same forty-five leaf property paths". They carried forty-three and forty-four, and the
number had already stopped being true of either.

**Decision - export is switched off at the key that switches it off.** Both copies now set
`management.otlp.tracing.export.enabled: false`. Boot resolves that key before
`management.tracing.enabled` when deciding whether to create the OTLP exporter, so no exporter bean
exists and there is nothing that could open a connection. The zero probability is retained as what it
actually is - an optimisation that suppresses root spans and so keeps recording cost off several hundred
context refreshes - and the comment now says so. The suite overlay additionally disables instrumentation
outright, which the packaged copy does not, because the packaged copy is what a deployed `test`-profile
run loads and a deployment may legitimately want a tracer wired while exporting nothing.

**Decision - a floor in the files, a lift from the base classes.** Both copies declare the emulator
endpoint for the global setting and for each of the three services. That is the floor: a client assembled
under this profile addresses an emulator address even if no test ever started a container, and the
per-service restatement means dropping any single key cannot leave one client pointing elsewhere.
`AbstractPostgresIT` and `AbstractLocalStackIT` then declare `@DynamicPropertySource` methods that raise
the floor to the ephemeral address of the container actually running, which is the only correct value and
is unknowable ahead of the run. Neither half is redundant: without the floor a context that forgot the
base class reaches real AWS, and without the lift a context that remembered it reaches the long-running
emulator on the fixed port, whose queues belong to other processes.

The data-source address is treated the OPPOSITE way, and the asymmetry is the point. No document declares
it, because a reachable default there would let a test that never started a container connect to the
developer's own database and pass. Absent, it fails during context refresh naming the missing property.
The lift supplies it for a test that did start one. A credential is the one thing declared on both sides:
the emulator accepts any non-empty pair and verifies neither, and declaring a throwaway pair is what keeps
the SDK's default credential chain out of the picture entirely.

**Why the precedence is demonstrated rather than asserted in prose.** The floor-and-lift design rests
entirely on a dynamic property source outranking a property file. `ContextInheritsContainerAddressesIT`
boots a real context under the test profile and observes the resolved environment: the data-source address
is present although no document declares it, which can only mean the registration was discovered; and the
AWS endpoint is the running container's ephemeral one rather than the fixed value the document declares,
which is the precedence itself. It is the only context-booting test in the module and is deliberately
loaded from one bare configuration class with no bean and no auto-configuration, because everything under
test happens before the bean factory. Two further tests assert the mapping from the other end, calling
each registration with a recording registry so that a renamed key fails immediately rather than at the
first context boot.

**Decision - the key-set agreement is asserted, not counted.** The prose count is replaced by a
comparison in `ConfigurationProfileBaselineTest` between the leaf key set of `application-local.yml` and
that of the packaged `application-test.yml`, permitting exactly three declared divergences: local's three
data-source keys, the test overlay's explicit closing of the interactive description surface, and the test
overlay's export switch. Any other addition or removal on either side fails the build. A number in a
comment cannot survive an edit to either file; a set comparison can only be satisfied by the files
agreeing.

*Cited by:* `application-test.yml` (both copies), `AbstractPostgresIT`, `AbstractLocalStackIT`.

### DL-105 - A bare `${VARIABLE}` states an intention; a guard is what enforces it

**Context.** `application-prod.yml` writes every required deployment value as a bare `${VARIABLE}` with
no fallback tail, and its own header claimed that an absent variable therefore aborted start-up "while the
context is being refreshed". Writing the value that way is necessary - a defaulted secret puts a usable
value in the repository exactly as a literal one does - but the claim about what follows from it was only
partly true, and the part that was false is the part that matters.

**What was measured, against the resolved Spring Boot 3.5.16 and Spring Framework 6.2.19 artifacts this
module builds against.** Three behaviours, not one:

1. **A setting bound as a configuration-properties object tolerates an unresolved reference.** The binder
   resolves placeholders leniently. With `CARDDEMO_JWT_SECRET` unset, `JwtProperties.secret()` binds as
   the sixteen-character literal `${CARDDEMO_JWT_SECRET}`. That text is not blank, so the `@NotBlank`
   constraint already declared on it records **zero** violations, `hasSecret()` returns **true**, the
   context refreshes, and the application signs bearer tokens with the text of its own placeholder -
   which anybody who read the repository could reproduce. The data-source location, the data-source user,
   the four key-store settings and the region all bind the same way and behave the same way.
2. **A setting read through a value expression does fail, but late and obscurely.** That path resolves
   strictly and raises a placeholder-resolution failure - at the moment the one bean that happens to read
   it is created, in words describing a placeholder rather than naming a variable a deployer must set,
   and only for whichever such bean the container reaches first. Nothing reports the other faults.
3. **An empty variable is silent on every path.** `CARDDEMO_DB_PASSWORD` exported as an empty string
   resolves to an empty string, binds as an empty string, and is offered to the database server as a
   credential. Whether that is refused is the server's decision, not this module's.

**Decision - a profile-scoped guard, published as a bean-factory post-processor.**
`com.carddemo.config.ProductionConfigurationValidator` is registered by the application's own component
scan, confined to the production profile by `@Profile`, and publishes its check from a `static` factory
method as a `BeanFactoryPostProcessor`. Post-processors of that kind are invoked while bean definitions
are still being processed, which is before the data source is created, before a migration opens a
connection, before the embedded server reads a key store and before any configuration-properties object
is bound. So an incomplete deployment stops before infrastructure is handed a placeholder.

The check rejects three forms for each guarded key: the property is not declared by any active profile,
the property is declared but the variable that supplies it is unset, and the value is blank - empty or
whitespace only. It reports **every** fault in one message rather than the first, because a deployment
missing four variables should learn that in one attempt and not in four, and each line names the property
key, the environment variable a deployer must set, and which of the three faults it is.

**Why the check reads the value the way it does.** An ordinary property read is strict about a nested
reference, so it would end the sweep at the first fault and would describe the placeholder instead of
naming the variable. The guard instead resolves an expression built from the key, which is the lenient
path: an unsatisfied reference survives into the returned text and can be reported against the setting
that carries it. An undeclared property is distinguished from an unset variable by testing for the key's
own placeholder first, because the text of an undeclared key also contains placeholder syntax and
reporting it as merely unresolved would send a deployer looking for a variable that nothing references.

**What the guard deliberately does NOT judge.** It judges usability, never shape. It does not parse a
JDBC location, decode the field-encryption key to thirty-two bytes, or check that a queue name carries
the first-in-first-out suffix. Three of the twelve settings already carry such a check in the component
that consumes them - `JwtProperties` constrains the signing secret, `SensitiveFieldEncryptionService`
decodes and length-checks the key, and `JobSubmissionService` refuses a queue name without the suffix -
and duplicating those would put one rule in two places and let them drift. What the guard adds to those
three is timing and vocabulary: each of them fires when its own bean is constructed, and each would have
reported an absent variable as a malformed value rather than as an absence.

It also does not judge whether the document was *written* without a fallback. That is a property of the
source text rather than of a running application, and `ConfigurationProfileBaselineTest` asserts it
directly against the document.

**Why the guarded set cannot drift from the profile.** `ProductionConfigurationValidatorTest` reads every
declaration in `application-prod.yml`, selects those whose raw text is a bare reference with no fallback
tail, and requires that set to equal the guard's list exactly - key for key and variable for variable. A
thirteenth bare reference added to the profile fails the build until it is guarded, and a guarded key
given a fallback tail fails it until one of the two is changed. No count of the set is written in prose
anywhere, in the profile document or in the test, for the reason recorded in DL-104: a number in a comment
cannot survive an edit to the thing it counts.

**Decision - the profile test now observes what a running application observes.** The superseded
assertion checked that the raw YAML text of three keys - later five - matched `\$\{[A-Z0-9_]+\}`. It
therefore asserted the shape of a document and said nothing about behaviour, and it silently omitted the
four key-store settings, the region and the trace-collector address. It is replaced by assertions driven
from the guard's own list, so the two cannot disagree, covering all twelve keys in three ways: the
declared text is the exact bare reference the guard watches; the value a real environment *resolves* to
while nothing is supplied is that same reference text, observed rather than assumed; and the guard refuses
that environment, naming every property and variable. A fourth assertion is the falsifiability control -
it binds `JwtProperties` from that same environment and records that the literal placeholder passes both
`hasSecret()` and `@NotBlank`. If a future framework revision ever made the bare form fail on its own,
that assertion breaks and the guard can be reconsidered on evidence.

**How the timing is proved rather than argued.** `ProductionInfrastructureIsUntouchedTest` starts a real
application context on the production profile with the delivered documents loaded, alongside an ordinary
eager singleton that counts its own construction and stands in for anything that would reach
infrastructure. For each required variable, omitted and then emptied and then set to whitespace, the
refresh fails with the guard's message and the stand-in's construction count is **zero** - which is
direct evidence that the failure preceded the singleton phase, not an argument that it should have. The
same class shows the complete environment starting with a count of one, so the zero is meaningful; shows
the check published as a `BeanFactoryPostProcessor` rather than an ordinary bean, which is what carries
the ordering guarantee; shows the guard absent from a local start, a test start and a profile-less start;
and shows that activating `local` alongside `prod` does not smuggle the local overlay's developer defaults
past it, because the production overlay is applied last.

**Two environments are excluded on purpose.** Every environment these tests build removes both
system-backed property sources, and every supplied value is derived from its own variable name. A build
agent that exports `AWS_REGION` - which agents hosted in that ecosystem routinely do - would otherwise
satisfy one of the twelve by accident and quietly turn a negative case positive; and deriving the values
keeps anything credential-shaped out of the repository, since the guard judges usability rather than shape.

**What was corrected in the profile document itself.** Every comment that credited the bare syntax with
enforcement now credits the guard and states the three framework behaviours above. The header's claim that
"an unresolvable placeholder is raised while the context is being refreshed" is replaced; the two required
lists now say what aborts the start; the signing-material block carries the `@NotBlank` illustration
because it is the clearest case; the data-source, key-store, region, trace-collector and queue blocks each
record what would have happened without the guard and how it would have been misreported. The header's
count of required values - which said six where the list below it named eight - is removed rather than
corrected, for the DL-104 reason.

*Cited by:* `application-prod.yml`, `ProductionConfigurationValidator`.

### DL-106 - Two record mappers are deferred to their own deliverable rather than written twice, and the entities that name them say so

**Context.** Eleven record layouts are mapped by eleven hand-written mappers, one per layout, because a
zero-reflection budget rules out any annotation-driven or convention-based mapping library. Seven of the
eleven are delivered here: the account, customer, daily-transaction, disclosure-group,
transaction-category, transaction-type and user-security layouts. Four are not, and two of those four -
the 150-byte card layout of `CVACT02Y` and the 50-byte category-balance layout of `CVTRA01Y` - were being
named in the present tense by the entities they serve. `Card` stated that fixed-width offset knowledge
lives exclusively in a card record mapper, and `TransactionCategoryBalance` said the same of a
category-balance record mapper. Both statements described a class that was not on disk.

**Decision.** The two mappers are deferred to their own deliverable rather than written here, and the
two entities are corrected to describe the arrangement honestly instead of describing an intended end
state as though it had arrived. Each entity keeps the citation, because naming where offset knowledge
belongs is the point of the paragraph and remains true, and each now adds that the mapper is a separate
deliverable of the record-mapper boundary and is not present at this checkpoint.

**Why deferring is correct here rather than merely convenient.** Both files are already assigned, with
their layouts, their round-trip bounds and their decision citations fully specified, and both
specifications direct that no test file accompany them. Writing either one here would produce a second,
divergent implementation of a file that is being authored elsewhere in the same pass, and writing tests
for them here would produce tests for an implementation that is about to be replaced. The deferral is
therefore the outcome that keeps one implementation per layout, which is the whole reason the mappers
are hand-written and one-per-layout in the first place.

**What the deferral does not excuse.** It does not reduce the eleven-layout target to nine, and it does
not leave the seven delivered mappers untested. Each of the seven now carries a direct unit test that
drives it from the shipped fixture record rather than from a hand-typed literal, so every offset is
proven against the authority layout rather than against a restatement of it. Two details that only a
direct test can catch are pinned there: the category-balance layout's key is seventeen bytes wide while
the transaction-category layout's identically-named key is six, and the six-byte key is not a prefix of
the seventeen-byte one, so the two must never be treated as interchangeable; and the user-security
mapper writes its credential window blank, which makes an emitted record deliberately not
byte-identical to the legacy record it came from and confines any round-trip claim to the two windows
that mapper publishes for the purpose.

**How it is held.** The two corrected paragraphs are plain code references rather than resolved links,
so neither compilation nor documentation generation depends on a class that is not yet present, and the
absence cannot masquerade as a build success that happens to tolerate a dangling link. When the two
mappers land, the present-tense wording becomes true and the qualifying sentence is what is removed.

*Cited by:* `domain/Card.java`, `domain/TransactionCategoryBalance.java`.

*Develops:* DL-034, which establishes hand-written offset mapping, one mapper per layout, as the
consequence of the zero-reflection budget.

---

### DL-107 - Three improvements are withdrawn, because an improvement that changes an observable outcome is a regression wearing better clothes

**Context.** A review found three places where the target had replaced a legacy behaviour with a better-
reasoned one and recorded the replacement in its own documentation as a deliberate divergence. Each
replacement was defensible read on its own terms, and each was wrong under this migration's governing
constraint, which is that business-logic semantics are preserved with zero behavioural regression. A
divergence that is documented is still a divergence; documenting it establishes that it was intentional,
not that it was authorised. Only an explicit exception can license one, and none of the three had it.

**Decision.** All three are withdrawn and the legacy behaviour is restored.

*The report-parameter record is truncated, not refused.* The legacy declares an eighty-byte record and
reads it into a twenty-one-byte group — ten characters of start date, one separator, ten of end date — at
`[app/cbl/CBTRN03C.cbl:L221]`. A group move of that shape fills the receiving group from the leading bytes
of the sending record area and discards the remainder without diagnostic. The target had instead measured
the record's significant width and refused anything that was not exactly twenty-one, which rejected input
the mainframe accepted. The width is now a minimum rather than an equality: a record shorter than the
group is still refused, because there is nothing to fill the group with, and everything beyond the leading
twenty-one bytes is ignored exactly as the move ignores it. The helper that stripped trailing padding
became unreachable and was removed with its two padding constants, rather than left as dead code
attesting to a rule no longer in force.

*An unresolvable transfer abends, it does not redirect.* A transfer-control statement naming a program the
region cannot resolve abends the task. The target had logged a warning and substituted the caller's own
default destination. The concern behind that choice was real — the name arrives echoed from a client and is
untrusted — but substitution is the wrong answer to it: a refused navigation that silently becomes a
different successful navigation is invisible, and invisible is worse than loud. The untrusted-input concern
is met instead by bounding the echoed name to the eight bytes the communication-area field reserves before
it reaches the diagnostic, which bounds what the abend *carries* without touching what the abend *decides*.
Without that bound an overlong name would fail the abend's own width validation and report a width problem
in place of the navigation problem. The blank-field path is untouched, because yielding the caller's default
for a field that nominates nothing is the legacy's own behaviour rather than a substitution for a failure.

*The date-validation subprogram is not a destination.* The resource definition file registers eighteen
transactions, and an earlier revision read that as eighteen destinations, supplying the shared
date-validation subprogram `CSUTLDTC` as the implementation of the eighteenth. That invents a destination
the estate does not have. `CSUTLDTC` is bound to no transaction anywhere in the file, is named by no
transfer-control statement and by no menu catalogue, and is reached only by static `CALL` from four sites:
it is an internal callee. The eighteenth transaction is `CDV1`, and it is bound to `PROGRAM(COCRDSEC)`, a
definition with no source member — anomaly 3 of the register above, which already recorded that no target
is generated for it. The vocabulary is therefore seventeen destinations and an anomaly, and the route
constant, the enumeration constant and the declared count are all corrected to say so. The same
eighteen-into-seventeen derivation appears in the published-interface configuration, which had justified
its correct count of seventeen with the same incorrect reason; the count stood, the reason is replaced.

**What this does not license.** Withdrawing a divergence is not licence to withdraw a *documented and
authorised* one. The parity exceptions this log records elsewhere — credential hashing, encryption at rest,
the escaped statement half — remain in force, because each is required by a constraint that outranks
byte-for-byte faithfulness and each is recorded as such. The distinction is whether an external constraint
compelled the departure or whether the departure was simply the better idea.

**How it is held.** Each restoration is pinned by tests that fail against the behaviour it replaced: the
parameter record's truncation is asserted by showing that a bare group, a padded area and an area carrying
stray content beyond the group all yield one identical window; the transfer failure is asserted as a raised
abend carrying the offending name, with a separate assertion that an overlong name is truncated in the
diagnostic rather than escaping as a width error; and the vocabulary is asserted as seventeen destinations
from which the subprogram, the dangling program and the dangling transaction are all absent by name and by
lookup. Both classes had no test at all before this work.

*Cited by:* `batch/JobParameterValidators.java`, `service/NavigationService.java`,
`config/OpenApiConfig.java`.

*Develops:* DL-093, which settles the same question for an unresolvable queue name and reaches the same
answer — refuse, rather than substitute something that makes a wrong name look like a success. Anomaly 3
of the register above supplies the dangling binding this entry relies on.

### DL-108 - The seed scripts keep the version numbers the plan gives them and move to a location production is refused, because one scanned directory cannot be both scoped and unscoped

**Context.** The plan describes the migration set twice and the two descriptions cannot both be delivered literally. Its file tree lists all four scripts in one directory - `V1__create_schema.sql`, `V2__create_indexes.sql`, `V3__seed_reference_data.sql` and `V4__seed_user_security.sql` under `db/migration` - while the paragraph that justifies the same tree requires that the seed scripts be resolved from profile-scoped locations, so that a production deployment migrates schema and indexes without inheriting sample data or seeded credentials. A migration tool scans a location and applies every script it finds in version order. Two seed scripts sitting beside the schema scripts are therefore reached by any profile that migrates at all, and the scoping the paragraph requires has nowhere to act.

**Decision.** The requirement is honoured and the listing is what gives way. The delivered tree splits the two sets by purpose - `db/migration` carries schema and indexes, `db/seed` carries the two seed scripts - and the location list is resolved per profile rather than declared once. Production refuses any location under the seed path. Local and test append it when it is absent, keeping the declared order and appending rather than replacing, because a seed location without the schema location would seed a database with no tables. A start with neither profile active migrates the schema alone. The version numbers and the file names are exactly the ones the plan names, so nothing a reader looks up by name has moved; only the directory differs, and it differs in order to make the plan's own scoping requirement expressible.

**Why a refusal rather than a filter.** Quietly dropping a seed location when production is active would also keep the seeds out of production, and it would hide the mistake that put them in reach. An operator who added the location would get a clean start and no seeded rows, and would then look for the fault in the data. A refusal names the misconfiguration at the point it was made, and it names what is at stake: those scripts insert fifty synthetic customer rows holding regulated identity data and ten known sign-on identities whose stored credentials are digests of one well-known value. The check runs against the merged, bound location list rather than against any single document, so an inherited value, an operator override and a copied overlay block are all covered by the same test.

**Why this is code and not three careful documents.** It was three careful documents, and that was the defect. The scoping was a convention held in prose by the shared baseline and the two overlays, each of which can be edited on its own, and the class the plan names to enforce it did not exist. A second gap followed from the same absence: nothing prevented production being activated *alongside* local or test, in which case the non-production overlay supplies repository-known signing and encryption material, an emulator endpoint, a relaxed transport rule and the seed location to a deployment that also reads the production overlay. That co-activation is now refused before any binding occurs, in either activation order, comparing profile names case-insensitively and ignoring surrounding whitespace because the list is commonly supplied as one comma-separated environment variable. Text is not a control.

*Status.* **Superseded twice - by DL-102, and again by DL-119.** The two-location arrangement recorded here was withdrawn: every migration ships flat from `classpath:db/migration` and production is held below the seeds by the version ceiling instead. It was then *reintroduced* by a later change and withdrawn a second time, which is why a second superseding entry exists; DL-119 records that round trip, the specification text that settles it, and the inverted guards that now make a third attempt fail a test rather than pass review. This entry is retained because it records why the alternative looked attractive - and it has now looked attractive twice, which is the most useful thing about it.

*Cited by:* no source file, by design - no source file implements this arrangement. `config/FlywayConfig.java` and `config/FlywayConfigTest.java` cite DL-102, the decision they actually implement, and the profile documents and migration scripts cite DL-119 for the withdrawal.


### DL-109 - The card-update transaction seals its fetched image into an opaque proof, and the two contract files that forbade any concurrency component are overruled

**Context.** Stale-update parity is a frozen requirement: the card-update program abandons a write when the record it locks no longer matches the image it fetched when the screen was built. The account arm of this module already resolved that requirement in favour of a sealed proof, for the reasons recorded at DL-074 through DL-077. The card arm did not, and two contract files said so in terms that left no room. The request stated that it deliberately carries no concurrency component - no version, no entity tag, no timestamp and no fetched-image snapshot - and the response stated that no such component exists here either, both on the reasoning that the legacy compared before and after images itself, that the migrated entity carries a version column, and that the whole matter is therefore a persistence concern which reaches the client only as a message.

**Decision.** That reasoning does not hold, and because it does not hold the prose is what changes. A version column cannot answer this question. It catches a change made between reading a record for update and writing it; the requirement is to catch a change made between *presenting* a screen and *confirming* it - the window the legacy work area existed to cover - and a version check cannot see into that window at all, because a confirming request that begins by loading the current row loads the current version with it and then agrees with itself. So the card arm gains the mechanism the account arm already has: a proof minted when the record is presented and verified when the change is confirmed. Both paragraphs are rewritten to describe what is now carried and why, rather than being left to forbid it. A per-file assurance does not narrow a frozen requirement; where the two disagree the requirement governs and the file is corrected.

**Why the proof is sealed rather than echoed.** In the legacy the fetched image was safe to carry because the communication area belongs to the transaction manager and the terminal never sees it. Anything a REST client echoes back is under the client's control, so an echoed version, an echoed entity tag or an echoed fetched image would let the client assert that nothing had changed - which is the check being performed - and the client would be authorising its own overwrite. The token is therefore opaque and tamper-evident: sealed inside the module's authenticated-encryption envelope under a binding of its own, so it can be returned and cannot be read, edited, fabricated or replayed against a different card. It carries its own scheme marker, checked after authentication, so a payload minted for the account arm cannot be presented here and one minted here cannot be presented there.

**What the digest covers, and what it deliberately does not.** One digest over the record: the two identifiers that bind the proof to a single row - the card number and the owning account identifier, neither of which the legacy compares because it holds them in the image it read by - followed by the six fields the change-detection paragraph compares, in the order it compares them and with the same folding rule, being the verification code, the upper-folded embossed name, the expiry year, the expiry month, the expiry day and the active status. The row version is deliberately not sealed in. It never needs to leave the server to do its job, and a value that never leaves cannot be echoed back wrongly; DL-075 records the same reasoning on the account arm. The two checks are kept side by side rather than merged, because they answer different questions and neither replaces the other.

**Why two protected screen values travel inside the proof.** The map protects the account identifier and the day portion of the expiry date, so the terminal could not alter either one. A request body has no protected fields, so a client can send whatever it likes for both. Rather than trust them or drop them, the confirming turn recovers them from the proof it has just verified: they are sealed inside the payload and returned as carried state, so the values the transaction proceeds on are the ones the server minted and not the ones the caller echoed.

**What is not done.** No version, entity tag or timestamp is exposed on either contract, and the client is given nothing it can read. The refusal still reaches the client as the single message the legacy reports, so the observable contract is unchanged by this decision even though the mechanism behind it is new.

*Cited by:* `service/CardConcurrencyTokenService.java`, `api/dto/CardUpdateRequest.java`, `api/dto/CardUpdateResponse.java`.


### DL-110 - Seeded regulated identifiers are sealed at rest after every migration of a seed-bearing profile, by a callback rather than a fifth script

**Context.** The schema script states the invariant plainly: the government-issued identifier column is not nullable, and both protected customer columns are to hold an envelope produced under the deployment's own key rather than a readable value. When this decision was taken the reference seed did not honour it - it wrote the fixture's identifiers as they stand, on the reasoning that static forward-only SQL cannot produce a keyed envelope without committing key material. DL-103 subsequently rejected that reasoning and sealed all fifty values inside the seed itself, so the seed now honours the invariant directly. This entry survives that change because the callback it records is still needed, and for reasons the original framing did not anticipate.

**Why nothing objected.** The customer entity refuses a non-envelope value in both its constructor and its setter, so on the face of it the invariant was enforced. Object-relational hydration assigns fields directly and consults neither. Fifty regulated identifiers therefore sat readable in every local and test database while the code that reads them was written as though they could not be. That is the shape of the defect worth naming: not a missing check, but a check that the only writer of those rows never passed through - and the same shape recurs in the key invariant below, where a check that inspects an envelope's marker passes a value the process cannot actually read.

**Decision.** An after-migrate callback holds both protected columns to two invariants. It **seals** any unsealed value through the module's single field-encryption service, each value bound to the column it is being stored in so that an envelope written for one column cannot later be read as another's. It then **opens** every stored value under the key the running process actually holds, and fails start-up on the first value that will not open. It is registered for the local and test profiles alone, because production lists no seed location, receives no row from either seed, and therefore should not carry a component that would put a table-wide read and update on its migration path for no purpose.

**Why the second invariant is the one that still bites.** Once DL-103 sealed the fifty seeded values, the sealing half converts nothing on a delivered database and stands only as defence in depth against a future edit to the seed. The opening half does not become redundant, because it answers a question no shape check can: an envelope sealed under some *other* key still carries the `ENC1` marker, and the seeded envelopes are fixed literals that nothing can re-key, so a process configured with the wrong key holds fifty unreadable rows that every marker-based check passes. That was reachable by configuration until the two seeding profiles stopped declaring their fixture key as an environment-variable default; DL-103 records that change. The check authenticates rather than reading through the column binding, because the seeded form is deliberately unbound and the bound reading would refuse all fifty - the binding remains a separate invariant, enforced where a value is read into the domain.

**On the transaction boundary, stated precisely because the opposite was once recorded here.** The after-migrate event is raised *after* migration execution completes and the migration's own transaction has committed, so the callback's work is a transaction of its own and is **not** atomic with the seeds it inspects. Electing to run in a transaction still matters - it makes the pass all-or-nothing within itself, so it cannot leave some identifiers sealed and some readable - but no return value can extend that to the migration. The residual exposure is bounded and is why the event is nonetheless the right one: a failure propagates out of the migrate operation and aborts the start-up, so no application ever reads a database whose identity columns are unsealed or unreadable, while the committed migration and the recorded history stay consistent with each other. Both halves being idempotent means the corrected start-up simply runs them again. An earlier revision of this entry, of the callback's own documentation, and of the seed header all claimed atomicity with the seed; a reader relying on that would have believed in a rollback that does not exist, so the claim is withdrawn here rather than softened.

**Why not a fifth versioned script.** One would work, and it would be wrong twice over. It would push the delivered migration set past a version no shipped script reaches - a claim the profile documents make and a test asserts against the delivered scripts - so the ledger would have to be weakened to accommodate the fix. And it would record a one-time application in the schema history, when what is wanted is an invariant that holds after *every* migration of a seed-bearing profile, including one that applied nothing because the seeds were already present.

**Idempotence as a property rather than a precaution.** Each value is examined before it is converted, and one that already carries the envelope marker is left exactly as it is. A second start updates nothing, and an envelope is never wrapped inside another envelope - which matters concretely, because the encryption service refuses to protect an already-protected value and would otherwise turn the second start of a local stack into a failure. The diagnostic records counts only: no readable identifier, no envelope and no customer key is ever written to a log, which is the rule DL-041 applies to every rejection diagnostic in the module.

**What this is, and is not.** Protecting these two fields at rest is a deliberate divergence from the legacy design rather than a translation of it - the legacy record holds both in the clear inside the customer record - and it is recorded here as an improvement on the baseline, not as a behaviour the source exhibits. No second encryption mechanism is introduced and no key is handled by the callback; it holds the one service and calls it.

*Cited by:* `config/SeededIdentifierSealingCallback.java`, `config/FlywayConfig.java`, `config/SeededIdentifierSealingCallbackTest.java`.


### DL-111 - Every migration ships from one location and production is held to the schema by a version pin, not by a directory split

**Context.** The migration plan names `db/migration` as the location of all four migrations, `V1` through `V4`, and the delivery boundary the platform resolves for the sign-on seed is `src/main/resources/db/migration/seed/V4__seed_user_security.sql`. An earlier revision instead split the two seeds into a second location, `db/seed`, and withheld them from production by omitting that location from the production overlay's location list. The split was defensible on its own terms - an omitted location cannot be reached by a flag left in the wrong position - but it put two of the delivered migrations somewhere no plan named, and it made the delivered tree disagree with the plan about where a migration lives.

**Decision.** The second location is withdrawn. `V3__seed_reference_data.sql` and `V4__seed_user_security.sql` sit beside `V1`, `V1_1` and `V2` in `db/migration`, every profile lists that one location, and production is held to the schema by `spring.flyway.target: 2`. `V1`, `V1_1` and `V2` are applied; `V3` and `V4` are resolved, reported above the target and never executed. The behaviour was verified against PostgreSQL 16 before the change was made: a migration pinned to `2` applies exactly two scripts, reports the two seeds as above-target, and still validates successfully, so a pending above-target migration is not an error a deployment has to suppress.

**Why a pin is not a weaker control than a missing location.** A location list and a target are both configuration, and either can be edited; the question is what each failure mode costs. An overlay that copied the schema location and forgot to keep the pin would seed a production database - which is exactly the hazard the split was chosen to remove. So the pin is applied twice and the second application is not configuration at all: `com.carddemo.config.FlywayConfig` publishes a migration customiser under the production profile alone, and that customiser sets the target after the resolved configuration has been bound, overriding an inherited value, an edited overlay, a merged property source or a command-line override alike. Re-enabling the seeds in production therefore requires removing the production profile itself, which a configuration edit cannot do quietly. The shared baseline also carries the pin rather than the convenience, so an overlay silent about migrations inherits the production posture; only the local profile and the test profile lift it, and each lifts it explicitly.

**Why the pin is `2` and how that stays correct.** `2` is the last schema migration, so the pin is the boundary between schema and seed rather than an arbitrary ceiling. That is asserted against the delivered scripts rather than restated: the highest schema migration must be at or below the pin and the lowest seed migration strictly above it, read from the file names on the class path. Adding a schema migration above the pin, or renumbering a seed below it, fails the build instead of silently changing what production applies. The published constant `FlywayConfig.SCHEMA_ONLY_TARGET` is compared with the configured value in both directions, so neither control can drift into naming a version the other does not.

**What the change cost the test suite, and what was done about it.** `AbstractPostgresIT` previously reached the head of a location list that carried no seeds; with one location that would now seed six hundred and thirty-six rows into the schema every integration test shares, changing what each of them observes. It is therefore pinned to the same schema-only target, so every integration test keeps the production-shaped, row-free baseline it was written against and inserts exactly the rows it means to. `SeedMigrationIT` migrates schemas of its own on the same server - one at the head, one at the pin, one pinned and then lifted - which is possible only because no migration script qualifies its own object names. That third schema is what makes the claim falsifiable rather than merely consistent: the same location and the same scripts, differing only in target, produce ten sign-on identities or none.

**What is not done.** No seed is rewritten, no row is dropped and no credential handling changes: the seeds still store every credential as an independently salted digest and are still confined to the local and test profiles. The withdrawn location is not deleted from the assertions - it is named in a guard that fails if it returns, as a document reference or as a delivered script, so the exclusion cannot quietly revert to a directory split.

*Relationship.* Restates and extends DL-102, which is the first record of this decision; the source files listed below cite DL-102.

*Cited by:* `application.yml`, `application-local.yml`, `application-prod.yml`, `application-test.yml`, `config/FlywayConfig.java`, `db/migration/seed/V3__seed_reference_data.sql`, `db/migration/seed/V4__seed_user_security.sql`, `support/AbstractPostgresIT.java`.

*Status.* **Superseded by DL-127.** The premise this entry rests on - that a Flyway location is
scanned recursively, so a directory cannot isolate a seed from a schema while either sits in the
shared parent - is retained and is quoted in the delivered comments. What is reversed is the
conclusion: all four scripts now sit one level down in two sibling locations, the shared parent holds
no script, and production resolves the schema location alone. The version ceiling this entry argues
for is retained behind that location list rather than in place of it. This entry is kept because it
records why the flat arrangement looked correct, and it looked correct three times.


### DL-112 - The publish-failure response and reason codes are derived from the failure's types, never from its description, and the raw failure is not handed to the logger

**Context.** DL-041 keeps a rejected value out of every diagnostic and scopes its sink honestly to "any channel a human or a tool later reads", naming a log statement as such a channel. `JobSubmissionService` honoured that scrupulously for every value a *caller* supplies - a submission identity, a card, a configured queue name - and then, on the one path where a value arrives from *outside* the module, did the opposite: the reason code recorded for a failed publish was the first line of the queue client's own exception message, and the raw failure was passed to the logger as its throwable, so the rendered stack trace carried `getMessage()` for every exception in the chain as well. A client that reports a signing failure by quoting the request it signed, or an endpoint failure by quoting the endpoint it was handed, therefore wrote deployment-supplied text - and any line terminator in it - straight into the service log.

**Decision.** Neither code is derived from a description. The response code is the sanitised simple name of the failure's own type, which is the analogue of the legacy numeric response the queue write reported. The reason code is the sanitised simple name of the deepest cause beneath it, which is the analogue of the legacy reason that *qualified* that response - a send failure rooted in a socket timeout is a different operational condition from one rooted in a missing queue, and the two stay distinguishable without either failure's description being repeated. Both are bounded at sixty-four characters, because the legacy codes were fixed-width display fields and an unbounded code has no legacy antecedent. Every character outside ASCII letters, ASCII digits and the two connectors becomes an underscore, so no whitespace and no control byte can reach the record and each code stays one unbroken token that a log reader will not split and a search will match whole. A type with no usable simple name - an anonymous subclass reports the empty string - yields `UnnamedType` rather than nothing, because an empty response code is the published signal that no code was reported.

**Why the rule is narrower than a Java identifier.** `Character.isJavaIdentifierPart` admits non-ASCII letters and several Unicode formatting and ignorable code points. A type may legally be named with them and a type name is read from a classfile rather than written by this module, so the admitted set is stated positively and narrowly instead of delegated.

**Why the raw failure is no longer logged, and what replaced it.** Suppressing the description while still handing the throwable to the logger would have achieved nothing: a rendered trace carries every message in the chain. So the throwable argument is gone. That costs a diagnosis something real, and the loss is repaid rather than accepted: the record carries `failureChain`, the sequence of sanitised type names from the outermost failure down, which is what the trace was actually useful for. The chain is bounded in depth and marked when cut, so a deep chain cannot lengthen a record without limit, and the marker means what it says - a chain that genuinely ends at the bound is not marked. The exception object still keeps the raw failure as its cause and still never escapes the method, so nothing is destroyed; it is simply not printed.

**Totality, which is not incidental here.** The walk down a cause chain is only safe if it terminates for every chain it can be handed. `Throwable.initCause` forbids self-causation but `getCause` is overridable and a pair of throwables can cause each other, so both shapes occur and both are bounded: a self-reference at the top of a chain takes the no-cause path, a self-reference part-way down stops the walk there, and a cycle or an over-deep chain is cut at the depth bound. One invariant ties the two derived fields together and is asserted rather than assumed - the reason code is empty exactly when the chain names a single type - so a reader who compares the fields is not misled by them drifting apart.

**What is not changed.** The failure is still not thrown. The queue is defined ignore-on-error, the legacy transaction completes after a failed write, and the outcome still travels back as data: `writeJobSubmissionQueue` returns `false` and `SubmissionResult.failed()` reports the partial submission. The public surface is identical, the operator-facing text is still the frozen legacy literal with no diagnostic detail appended, and `JobSubmissionException` is untouched - it carries whatever codes it is given, verbatim and uninterpreted, which is why supplying it a safe code is the caller's obligation and is discharged here.

*Cited by:* `service/JobSubmissionService.java`. Governed by DL-041, whose sink scope this completes; the payload boundary that protects the published card itself remains DL-042.

### DL-113 - The nine named fixtures are pinned by measurement, and three properties measured from them correct or sharpen what was previously recorded about them

**Context.** The nine sequential inputs are named individually, with byte counts and record counts, as the artefacts the batch pipeline is to be driven by. Those figures were carried in the test tree as literals in a table that opened no file, so every one of them would still have been reported correct with the fixtures deleted, altered, or replaced by files of another shape. The figures were also, in three places, either imprecise or an understatement of what the data actually guarantees. Both problems are addressed the same way: the fixtures are now measured, and what the measurement found is recorded here rather than left to be rediscovered.

**Decision.** Each fixture is opened and its byte count, record count, per-record width, line-feed termination and SHA-256 digest are asserted, and each is additionally compared byte for byte with the legacy dataset it was copied from. The nine digests are pinned as literals so the contract holds in a checkout that carries the module without the legacy tree; the comparison against the legacy tree is additional evidence taken when that tree is present, and it is all-or-nothing - a partially present legacy tree fails rather than quietly checking whichever half exists. The two layers answer different questions and neither substitutes for the other: a digest says the fixture is the file it was meant to be, and the comparison says the file it was meant to be is still what the legacy dataset holds.

**Correction one - the disclosure-group keys are ten bytes, not one and not seven.** The three groups have been recorded as keyed `A`, `DEFAULT` and `ZEROAPR`. Measured, the keys are the fixed-width field they actually occupy: `A000000000`, `DEFAULT   ` and `ZEROAPR   `, ten bytes each, seventeen records apiece and contiguous in the file. The short forms are readable shorthand and are not wrong about which groups exist, but they are not the keys, and a reader who took them literally would look for a one-byte key, fail to find it, and conclude the fixture was malformed. The measured keys are what the assertions carry, and the groups' contiguity is asserted too, because a keyed read that does not have to span the file is a property of this fixture rather than of the layout.

**Correction two - the amount sign is a stronger asset than was claimed, and is now relied upon deliberately.** The daily-transaction fixture has been described as exercising both signed directions because it holds two hundred and fifty point-of-sale purchases and fifty operator returns. Measured, the relationship is exact rather than incidental: the overpunched trailing byte of the amount partitions precisely along the source marker, every purchase carrying a non-negative overpunch and every return a negative one, with the two alphabets disjoint and no record on the wrong side. That makes the sign convention of DL-011 reachable in both directions from seeded data alone, with no constructed fixture, and it is asserted in that form - as a partition, not as a pair of counts - so a fixture edit that broke the correspondence would fail rather than merely shifting a tally.

**Correction three - the same fixture cannot exercise a date window, and that is now asserted rather than assumed.** All three hundred records carry a blank twenty-six-byte processing timestamp and a single shared origination timestamp. This was anticipated, and the anticipation was right, but it was recorded as a caveat about what a reporting test must supply for itself. It is now a positive assertion over the fixture: every processing timestamp is blank and the origination stamp is uniform. The value of stating it that way is that the limitation stops being a note someone has to remember and becomes a property the suite would report if it ever changed - which is the only circumstance in which the caveat would need revisiting.

**Why the geometry table it replaces was not simply deleted.** The literals were not wrong; they were unanchored. They are therefore kept and made load-bearing rather than discarded, so the same nine rows now fail when the files disagree with them. The suite also asserts that the committed directory holds exactly the nine named files and nothing else, and that the enumeration it drives from names nine distinct files - because a table and a directory that drift apart is precisely how a fixture stops being checked without anyone removing a check.

**One fixture had no reader at all.** `cardxref.txt` was opened by no test, because no cross-reference record mapper is delivered and nothing else needed it. It is a named artefact regardless, so it is measured and compared like the other eight, and the suite asserts that all nine are opened. The absent mapper is recorded as a delivery gap elsewhere and is not created here.

*Cited by:* `support/FixtureContractTest.java`. Relies on the zoned-decimal sign convention of DL-015 and the assert-as-bytes discipline of DL-046.

### DL-114 - The AWS bootstrap hook is verified both as a contract and by execution, because each tier can pass while the other fails

**Context.** The hook that provisions the object store, the queue and the topic for local and test running had no test of any kind. It carries several decisions that are contractual rather than incidental - the queue is first-in-first-out with content-based deduplication explicitly off, the bucket has object versioning enabled as the generation-data-group replacement, each resource is guarded so a hook that reruns on every container start does not recreate anything - and none of them was asserted anywhere.

**Decision.** Two tiers, deliberately. A contract tier reads the hook as text, strips its comments so a sentence describing a behaviour cannot stand in for the behaviour, and asserts the resource names, the attributes, the guards and the agreement between the hook and every one of the seven files that names these resources. A live tier starts an emulator, copies the actual hook into it, runs it, and reads the provisioned state back through the service clients rather than through the emulator's own command-line tool - then runs it a second time and asserts that nothing changed and that exactly one of each resource exists.

**Why both, when either looks sufficient.** They fail independently, and each failure is invisible to the other. A hook whose text is impeccable can still fail at run time - a flag the emulator rejects, an ordering that leaves versioning unapplied - and the contract tier would report success. A hook that provisions correctly can still have drifted out of agreement with the overlays that name the same resources, and the live tier would never notice, because it never reads an overlay. This was not reasoned about in the abstract: a single deliberate edit to the deduplication attribute was shown to be caught by both tiers, and a single transposition of the bucket name in one overlay was caught by the contract tier alone.

**What the emulator tier does not assume.** It does not rely on the hook having fired during container start-up, because a test that depends on a start-up side effect cannot distinguish the hook working from the image already containing the resources. The hook is invoked explicitly against a fresh emulator, so the create branch is the branch taken, and the already-present branch is reached only by the deliberate second run.

**An asymmetry in the production overlay, confirmed as intended.** The bucket, the message group and the topic all keep a canonical default; the queue name and the region do not, and a first reading of that looks like an omission. It is not: a queue name that resolves to a well-formed default names nothing in a real account, and a submission would then report complete while the cards sat unread. The overlay says so itself, in three places. The assertions were rewritten to capture the asymmetry rather than to flatten it, and they now pin both halves - three names defaulted, two deliberately not - so neither the convenience nor its absence can change unnoticed.

**A census scoped by value rather than by key.** The overlays bind an unrelated subsystem's collector address under the same property name as the client endpoints. A census keyed on the name alone therefore conflated two different things and reported an endpoint override in a profile that has none. The assertions judge each binding by what it points at, and the predicate that decides is shown to discriminate by being applied where an emulator *is* deliberately named.

*Cited by:* `config/LocalStackBootstrapContractTest.java`, `config/LocalStackBootstrapIT.java`. The deduplication attribute it pins is the resource-side half of DL-043, the queue's name is DL-045, and the ignore-on-error posture of the publisher itself remains DL-044.

### DL-115 - The transport library moved to the 4.2 line to clear a high severity finding, and the deprecated-method notice this entry tracked is gone with it

**Status. Superseded in disposition.** This entry originally recorded a runtime notice and decided to
track it rather than act on it. The notice is now absent, the version it was attached to has moved,
and the reasoning that held the version still is withdrawn as partly false. The original record is
kept rather than overwritten, because the distinction it drew is still the right way to read a notice
of this kind, and because an entry that quietly changes its mind teaches nothing.

**What was originally recorded.** Running the integration tier on the pinned Java 25 runtime emitted
four lines on the forked process's error stream, quoted here exactly as the build produced them at
the time:

```
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::allocateMemory has been called by io.netty.util.internal.PlatformDependent0$2
         (file:.../netty-common-4.1.136.Final.jar)
WARNING: Please consider reporting this to the maintainers of class io.netty.util.internal.PlatformDependent0$2
WARNING: sun.misc.Unsafe::allocateMemory will be removed in a future release
```

The caller was read from the message rather than inferred. Ten netty modules resolved into this build,
all at runtime scope, all by way of a single path: `io.awspring.cloud:spring-cloud-aws-starter-s3`,
which supplies the object-store transport client. The notice was triggered by the queue-bridge
integration tests, at the point the transport allocated its first direct buffer.

**What reopened it was none of the two events this entry predicted.** The entry named them precisely:
the transport client beginning to declare the 4.2 module layout, or the platform escalating the
terminal deprecation from a warning to a hard failure. Neither happened. A third event did. An
executed vulnerability scan reported 7.5 against the transport module on the 4.1 line, with the fix
range beginning at 4.2.16, and that module ships inside the deployable jar at runtime scope. The
supply-chain gate this build is held to tolerates zero high findings, so the version had to move
whatever this entry preferred, and the only live question left was whether the module-layout
objection that had kept it still was real.

**The module-layout objection was tested rather than accepted.** Its first half was correct and was
confirmed by resolving the tree: the 4.2 line does split the codec module into separate base,
compression, marshalling and protobuf artifacts, and the original codec coordinate does arrive as a
vestigial shell. The conclusion drawn from that did not follow, and it was disproved by execution
rather than by argument. The whole suite passes on 4.2.16, and the queue and object-storage tiers that
actually drive this transport pass against a real emulated endpoint in the integration tier rather
than against a mock. An objection that predicts a runtime failure is answered by exercising the
runtime, not by restating the layout.

**One claim in the earlier record is withdrawn as false.** This entry, and the comment beside the
version property in the build file, both asserted that the frozen dependency inventory names this
version and that it was therefore not ours to move. Checked against the plan rather than against the
comment, netty appears in no declaration list, in none of the artifacts the plan enumerates as
arriving transitively at parent-pinned versions that must not be redeclared, in no exclusion, and in
no drift blacklist. The claim also refuted itself in passing: it conceded that the framework bill of
materials would otherwise select 4.1.135.Final, which makes 4.1.136.Final an override chosen locally
for security rather than a measurement read out of the plan's executed resolution. The entry that
actually governs is DL-066, and it sanctions exactly the mechanism used here - pinning forward past a
published advisory by overriding the framework's own version property rather than by declaring a
direct dependency.

**Measured outcome.** The notice is gone: three occurrences under 4.1.136.Final, zero under
4.2.16.Final, counted on the same integration tier that produced the quotation above. The upgrade
therefore closed both the finding it was made for and the notice this entry was opened for.

**What survives, and is the reason the entry is kept.** The distinction it drew was correct. Those
four lines are written by the Java runtime to a forked process's error stream, never carry the build
tool's warning prefix, and are not compiler diagnostics, so the `-Xlint:all -Werror` configuration
that makes the zero-warning gate mechanical neither saw them nor could have acted on them. That
mattered then and it will matter again: the next runtime notice should be recorded and understood the
same way, rather than silenced by removing whatever produced it.

*Cited by:* `pom.xml`, at the netty version property. The forward-pinning mechanism is DL-066, the
constrained-framework-line principle it follows is DL-062, and the zero-warning-from-a-clean-checkout
guarantee this entry leaves undisturbed is DL-063. The scope and suppression posture of the scan that
reported the finding is DL-067.

### DL-116 - The seed migrations sit flat beside the schema migrations, and production is held below them by a version pin rather than by a directory

**Context.** Four migrations exist: `V1__create_schema.sql` and `V2__create_indexes.sql` build the schema and its integrity layer, and `V3__seed_reference_data.sql` and `V4__seed_user_security.sql` seed 626 reference rows and ten sign-on identities. The seeds must never be applied to a production database: the reference seed inserts fifty synthetic customer rows shaped like personal data, and the sign-on seed inserts ten identities whose credentials are all digests of one well-known fixture value, five of them administrative. Hashing removes the cleartext from the database; it does not make the credential unknown.

An interim arrangement placed the two seeds in a second class-path location, `db/seed`, and excluded them from production by having the production overlay list `db/migration` alone. That arrangement was withdrawn.

**Decision.** Every delivered migration is physically flat in one location, `classpath:db/migration`, which every profile lists and no profile extends. The production exclusion is the version pin `spring.flyway.target=2`, declared by the shared baseline so that a profile silent about migrations inherits the production posture, and re-asserted by `application-prod.yml`: production applies V1, V1_1 and V2 and stops. The local and test overlays raise the ceiling to `latest` explicitly, and so migrate to the head of the sequence.

**Why the directory split was the weaker control despite reading as the stronger one.** A directory boundary is not a boundary the migration tool enforces. A version number orders the whole migration history rather than one folder of it, so any deployment that resolved both entries — a merged location list, a wildcard location, a `filesystem:` location, or an operator running the migration tool directly against the packaged artefact — would apply V1 through V4 in ascending order whatever folder each script came from. The split therefore bought the *appearance* of isolation: it read as structural, so it invited exactly the confidence that would stop anyone from setting the pin. It also cost the property that makes a forward-only history auditable, namely one directory holding one ascending sequence with one answer to how far a migration has reached. The version pin, by contrast, is a property of the same ordering the tool already uses to decide what to apply, so it holds however the location list was assembled and however the artefact is scanned.

**What is given up, stated plainly rather than glossed.** The pin is one line in one document and there is no second control behind it. That is a real and deliberate reduction in defence depth compared with a claim of two controls, and it is the honest position: the previous arrangement did not actually provide two independent controls, because the first was defeated by any generic scan. One control that holds is preferable to two that are counted and one that works, and a single reviewable line is preferable to a guarantee spread across a directory layout. The trade is recorded here so that a later reader does not "restore" the folder as belt and braces and reintroduce the same false confidence.

**One migration consequence, measured rather than assumed.** Relocating the two seeds also meant rewriting the header narrative of all four scripts, which changes their Flyway checksums. A fresh database is unaffected, and that was verified twice: real Flyway against real PostgreSQL 16 applies V1 through V4 from the one location, and the packaged jar under the local profile boots and does the same. A database already migrated under the withdrawn two-location arrangement is a different case, and it was measured rather than reasoned about — running the new flat topology against such a database fails validation with `Migration checksum mismatch` on the edited versions. That is Flyway working correctly, not a defect: `validate-on-migrate` is `true` in every profile precisely so an edited script cannot diverge from what was applied. The sanctioned remedy is the one the profiles already provide: `clean-disabled` is `false` in the local and test profiles and only there, so such a database is disposable and is dropped and re-migrated. Two remedies are explicitly **not** sanctioned and must not be adopted — setting `validate-on-migrate: false`, which would suppress the check for every future divergence rather than this one, and running `flyway repair` against production, which cannot arise anyway because production is pinned at `target: 2` and never applied V3 or V4 to have a checksum for.

**How it is held.** `config/ConfigurationProfileBaselineTest` asserts all of it against the shipped documents and the delivered scripts, not against a restatement: the production overlay declares `target: 2` and still resolves pinned when layered over the shared baseline; the shared baseline and the local and test overlays declare no target and resolve to none by inheritance either; every document lists exactly one location with no comma and no `classpath:db/seed`; the one location delivers exactly V1, V2, V3 and V4; the withdrawn `db/seed` folder delivers nothing; and no shipped document mentions it. The highest-delivered-version helper still reads the withdrawn folder as well as the live one, so reintroducing a script there cannot make the delivered-version claim understate the head.

**Provenance.** The flat layout and the `spring.flyway.target=2` requirement are both stated by the migration specifications for V3 and V4, which require the four migrations to be flat in one directory, direct that no subdirectory be created, and name the version pin as the production control.

*Relationship.* Restates DL-102, which is the first record of this decision; the source files listed below cite DL-102.

*Cited by:* `application.yml`, `application-local.yml`, `application-test.yml`, `application-prod.yml`, `db/migration/schema/V1__create_schema.sql`, `db/migration/schema/V2__create_indexes.sql`, `db/migration/seed/V3__seed_reference_data.sql`, `db/migration/seed/V4__seed_user_security.sql`, `domain/UserSecurity.java`, `.dockerignore`.

*Status.* **Superseded by DL-127.** The premise this entry rests on - that a Flyway location is
scanned recursively, so a directory cannot isolate a seed from a schema while either sits in the
shared parent - is retained and is quoted in the delivered comments. What is reversed is the
conclusion: all four scripts now sit one level down in two sibling locations, the shared parent holds
no script, and production resolves the schema location alone. The version ceiling this entry argues
for is retained behind that location list rather than in place of it. This entry is kept because it
records why the flat arrangement looked correct, and it looked correct three times.



---

### DL-117 - The queue naming contract is stated once, in a typed holder both the producer and the bootstrap's assertions are held to

**Context.** Two components in this module name the same FIFO queue, and until now each stated the naming rules for itself. `localstack/init/01-create-aws-resources.sh` provisions the queue and enforced the real service contract: a name of at most eighty characters drawn from `[A-Za-z0-9._-]` and ending in `.fifo`, and a message group id of at most one hundred and twenty-eight characters from the same set. `service/JobSubmissionService` publishes to that queue and enforced something much weaker at construction — that the configured queue name was non-blank printable US-ASCII ending in `.fifo`, and that the message group id was non-blank printable US-ASCII, with no length bound and no character set at all.

The two statements therefore disagreed, and the disagreement was not symmetric. Values existed that the script would refuse to provision and the producer would happily accept: `carddemo jobs.fifo`, a name of eighty-five characters, a name carrying an accent, a slash or a colon, and a message group id of any length whatsoever.

**Why the gap was worse than an ordinary missing validation.** This class is deliberately non-fatal on a failed publish. It reproduces `ERROROPTION(IGNORE)` on the legacy transient data queue, so a write that fails is logged and reported as a partial or failed submission rather than raised to the caller — a decision recorded at DL-043 and one that remains correct. Combined with a permissive name check, that tolerance became a place for a misconfiguration to hide: an invalid name passed start-up, failed *every* publish for the lifetime of the process, and each failure was converted into the tolerated outcome the legacy contract requires. Nothing in the running system distinguished "the queue is temporarily unreachable", which must be tolerated, from "this deployment can never publish anything", which must not be. The fault therefore had to be caught at construction, because construction is the last point at which it can still be fatal.

**Decision.** The contract is stated once, in `util/SqsNamingRules`, and both sides are held to that statement. The holder carries the bounds and the permitted set as named constants, exposes `requireQueueDestination`, `requireQueueName` and `requireMessageGroupId`, and is called by `JobSubmissionService`'s constructor for both configured values. `service/JobSubmissionService` no longer carries a suffix constant or a printability check of its own.

**The destination is three forms, not one, and narrowing it to one would have been a regression.** A deployment may configure the queue as a bare name, as a queue URL, or as a queue ARN, and all three have always been accepted. A tightened rule that admitted only bare names would have refused a form the module already supports, so `requireQueueDestination` recognises each form, checks the envelope it is in — an ARN must have six colon-separated segments whose service segment is `sqs`; a URL must have a non-empty final path segment — extracts the queue name from it, and holds *that* to the one rule. The configured value is returned unchanged, so recognising a form never rewrites it. Each form is asserted against the canonical name, including the URL the emulator itself reports, which is the value an operator is most likely to copy.

**Why the shell was not changed to call the Java, or the reverse.** Neither can execute the other: the script runs inside the emulator's container before any JVM exists, and the producer runs without the script. A single *executable* source of truth is therefore not available, so the single source of truth is the stated one plus an assertion that the two statements agree. `config/LocalStackBootstrapContractTest` reads the two bounds out of the script text rather than restating them, and compares them to the constants; it asserts that every character the script's set admits is admitted by the Java predicate and that a sample of what the set excludes is refused by it, so agreement is proved in both directions rather than only on the permitted side. A one-sided edit to either language now fails the build. The script carries a comment at the rules naming the Java holder and this decision, so the obligation is discoverable from the side a maintainer is most likely to be editing.

**What a rejection may say, which is unchanged.** The refusals name the configuration key and this module's own literals and never repeat the operator-supplied value, holding to DL-041. Two derived facts are reported because they are diagnostically necessary and cannot carry hostile content: the *length* of an over-long value, and the zero-based position and code point of the first character outside the set. Both are integers. `SqsNamingRulesTest` asserts across four distinct rejection modes that no message repeats a marker planted in the input, and that no message carries a line terminator, which holds DL-042.

**One test changed its mechanism rather than its assertion.** `JobSubmissionServiceIT` previously reached the ignore-on-error path using a queue name with embedded spaces, because the messaging library's default for an unresolvable queue is to *create* it and a name the service refused outright was the shortest route to a failed write. That route is now closed at construction, which is the point of this decision. The refusal is obtained instead from the queue service, by publishing a well-formed but absent name through a template whose queue-not-found strategy is refusal — which is how the shipped configuration behaves. The assertions on the outcome are unchanged: nothing published, failed rather than partial, and the legacy operator message returned to the caller.

**Provenance.** The eighty- and one-hundred-and-twenty-eight-character bounds and the permitted set are the queue service's own published limits, already enforced by the bootstrap script this module ships. The non-fatal publish contract the gap hid behind derives from `ERROROPTION(IGNORE)` on `TDQUEUE(JOBS)` in `app/csd/CARDDEMO.CSD`, at checkout `7756d895ffeb65f7ea72aaa609e356d9899afcec`, release stamp `CardDemo_v1.0-15-g27d6c6f-68` dated 2022-07-19.

*Relationship.* Depends on DL-041 and DL-042 for what a rejection may disclose, and on DL-043 for the non-fatal publish contract that makes construction the only safe place for this check. Complements DL-092, which fixes *which* name is canonical; this decision fixes *what shape* any configured name must have.

*Cited by:* `util/SqsNamingRules.java`, `service/JobSubmissionService.java`, `localstack/init/01-create-aws-resources.sh`.


---

### DL-118 - Every formatter and every fold names its locale, and constraint messages are rendered in one pinned locale

**Context.** This module states its external contract in bytes: four fixed-width output formats, a set of reproduced operator messages, and a field-error payload. A Java formatter that is not given a locale uses the JVM default one, and two of the things it then does are visible in those bytes. `String.format("%04d", 2026)` renders `٢٠٢٦` under an Arabic-Indic numbering locale rather than `2026` - measured, not inferred, by running it. And `"I".toLowerCase()` yields the dotless `ı` under Turkish, which an ASCII-only fold cannot reverse.

The suite could not see either problem, because the build pins `-Duser.language=en -Duser.country=US` in `test.jvm.args`. Pinning the suite is right - a test should not fail because of the machine it runs on - but it makes the suite structurally unable to observe a locale defect, so the defect has to be looked for deliberately.

**Decision, in three parts.**

*Every formatter names its locale.* No `String.format(...)` and no `String.formatted(...)` anywhere in this module - main sources or tests - is called without an explicit locale. `Locale.ROOT` is the locale, because the values being rendered are fixed-width machine formats rather than text for a reader.

*Every fold names its locale, or is not a library fold at all.* Where the estate folds case as a 26-character table substitution, the fold is `CobolStringUtils.asciiUpperFold` and never a library method; that is D-18 and it is unchanged. Everywhere else a case change is required, it names `Locale.ROOT`.

*Constraint messages are rendered in one pinned locale.* This is the part that was a live defect rather than a latent one. A constraint failure reaches a client through `GlobalExceptionHandler`, which places `ConstraintViolation.getMessage()` into `ErrorResponse.FieldError.message()`. That text is not one of this module's literals - it is the validation provider's bundled message, resolved against `LocaleContextHolder`, which for a servlet request is the caller's `Accept-Language` header and otherwise the JVM default. The same artifact given the same input therefore emitted `size must be between 0 and 8` on one host and a translation of it on another, or on the same host to a different caller. `FixedLocaleMessageInterpolator` pins the rendering to `Locale.ROOT`, and `WebMvcConfig` installs it on the validator the application validates with.

**Why the interpolator discards the locale it is handed.** Both `interpolate` overloads ignore their locale argument. That is not carelessness: the framework wraps any supplied interpolator in one that passes `LocaleContextHolder.getLocale()`, so the argument is precisely the value whose influence is being removed. Accepting it would restore the defect.

**Why the pin also had to be applied in three tests, and why it is the same class rather than a second statement.** Sixty-nine test classes build their own provider with `Validation.buildDefaultValidatorFactory()`, bypassing the configured validator entirely, so the application-side fix could not reach them. Only three of those assert rendered message text, and those three now build their factory with `FixedLocaleMessageInterpolator` - the application's own statement of the rule. A test that pinned the locale its own way would pass while the application stayed non-deterministic, which is the disagreement using one class prevents. Which three needed it was determined by running the suite under a hostile locale rather than by reading, because the run is the only reliable discriminator.

**What is deliberately not claimed.** Pinning changes only how a message is *rendered*. Which constraints exist, which fields carry them, and the two-state MISSING versus INVALID decision are untouched - that decision reads the message *template*, before interpolation, so it never depended on a locale. And this is not internationalisation work in reverse: nothing in the estate being reproduced is multilingual, and no requirement asks for negotiated message text, so one language in equals one language out.

**One formatter is pinned even though it did not need to be.** Hexadecimal conversions are not localised the way decimal ones are - also measured. The two `%X` diagnostics in `ZonedDecimalCodec` are nonetheless given `Locale.ROOT`, so that "every formatter in this module names its locale" is a property a reviewer confirms by grep rather than by knowing which `Formatter` conversions localise. Those strings are also subject to DL-042's printable-US-ASCII requirement, which a pinned locale guarantees rather than leaves to be inferred.

**How it is held.** The unit tier is executed three times: once under the pinned `en-US`, once under `tr-TR` and once under `ar-EG`, and all three must pass. `WebMvcConfigBoundaryTest` additionally renders a constraint message while *asking for* `tr-TR` and asserts the bytes are unchanged, so the pin is verified by behaviour rather than by the presence of a bean.

*Relationship.* Extends D-18, which forbids a library fold for the one field the estate folds with a table, to a module-wide rule about locale in formatters and folds generally. Supports DL-042, whose printable-US-ASCII requirement a locale-dependent formatter could otherwise breach.

*Correction.* The continuous-integration workflow previously attributed the no-locale-sensitive-formatter rule to D-27. D-27 forbids a templating engine for statement output and says nothing about locale; the rule is this decision.

*Cited by:* `config/FixedLocaleMessageInterpolator.java`, `config/WebMvcConfig.java`, `util/ZonedDecimalCodec.java`, `.github/workflows/carddemo-java-ci.yml`.


### DL-119 - The migration directory split was introduced a second time and withdrawn a second time, and the round trip is recorded so it is not attempted a third

**Why this entry exists at all.** The delivered arrangement - four migrations flat in
`classpath:db/migration`, production held to the schema by `spring.flyway.target=2` - is already
recorded in DL-102, restated in DL-111 and argued at length in DL-116. This entry adds no new
arrangement. It records that the arrangement was *reversed and then restored*, because a reader who
finds three entries defending flatness and a fourth (DL-108) defending a split has no way to tell
which one the code follows, and that ambiguity is what caused the reversal.

**What happened.** A review finding reported that the profile documents described a five-script
topology including `V1_1__create_batch_metadata.sql`, and asked for alignment to the plan's exact
V1-V4 inventory "rather than exact V1-V4 profile scoping". Two changes were made in response. Deleting
`V1_1` and letting the batch framework own its own metadata tables was correct, and stands. Splitting
the four scripts across `db/migration/schema` and `db/migration/seed` was not, and has been withdrawn.

**Why the split was wrong, on authority rather than on preference.** The migration specifications for
`V3__seed_reference_data.sql` and `V4__seed_user_security.sql` each state that the four migrations are
physically flat in one `db/migration` directory, each direct in terms that no subdirectory be created,
and each name `spring.flyway.target=2` as the production control. Both give the same reason: a
directory-scoped location cannot isolate the seeds from the schema. Each specification also fixes the
delivered path of its own script, and the split moved all four scripts off those paths. So the phrase
"profile-scoped locations" in the plan's design narrative means a profile-scoped **ceiling**; reading
it as a profile-scoped **directory** contradicts the two documents that specify the scripts
themselves.

**Why the reason is a good one, independent of who said it.** Flyway scans a location recursively and
orders the whole resolved history by version. Any deployment that resolves both directories - a merged
location list, a wildcard location, a `filesystem:` location, or an operator running the migration tool
directly against the packaged artefact - applies V1 through V4 in ascending order whatever folder each
script came from. The split therefore bought the *appearance* of isolation. Its real cost was not the
weak guarantee but the confidence: a reader who sees a `seed` folder stops looking for the setting that
actually holds, which is precisely the failure DL-116 predicted in writing when it asked that nobody
"restore the folder as belt and braces".

**How the same finding is satisfied without the split.** The finding's substantive demand - that a
database seeded under local or test must not endanger a later production run - is met by
`ProductionSeedRejectionCallback`, which refuses a production start against a database whose migration
history records a seed version or whose tables still hold seeded rows. That control is
topology-independent, and under the flat layout it is the genuinely independent second control the
arrangement needs: the ceiling cannot help a database that was seeded before production was ever
pointed at it, and the applied-state check cannot stop a seed being applied for the first time. The
earlier pairing of a directory and a ceiling counted two controls and had one that worked.

**What was changed back, so the revert is auditable.** The four scripts returned to
`src/main/resources/db/migration`; both subdirectories were removed. All five profile documents returned
to the single scalar location. `FlywayConfig` lost `SEED_LOCATION`, `SEED_PATH` and `isSeedLocation`, and
`resolveLocations` again refuses any non-delivered location under production and completes the one
location for a seeding profile. The four script headers again carry the flat-layout and
`spring.flyway.target=2` statements their specifications require. `.dockerignore` re-includes the one
location.

**Three guards had to invert, and the inversions are the durable part.** Restoring a value is easy to
undo; a test that fails is not. A guard that reported a script sitting loose in the parent now reports a
script sitting in a subdirectory. A guard that forbade any document from declaring the parent now
requires every document to declare exactly it. And the integration test that asserted the location list
withheld the seeds with no ceiling - true under the split, false by design under the flat layout - was
replaced by its falsifying opposite: the same single location, the same four scripts, the ceiling
removed, and ten known sign-on identities land. That test is now the evidence that the pin is
load-bearing rather than decorative. One further trace was caught in passing: a contractual-key list
enumerated `spring.flyway.locations[0]` and `[1]`, indexed keys that exist only for a YAML sequence, and
a comment now says that the indexed pair is exactly what a returning split would leave behind. The two
withdrawn folder names are kept as named constants in an assertion that they deliver nothing, as DL-116
asked.

**Status of the neighbouring entries, stated so no reader has to infer it.** At the time this entry was
written, DL-102 was the governing record, DL-111 and DL-116 restated it, and DL-108 recorded the split
and remained superseded. **All of that is now superseded by DL-127**, which reinstates a directory split
in the one form the recursion argument below actually permits: all four scripts one level down, the
shared parent empty, and production resolving the schema location alone. DL-108 remains superseded even
so, because it moved the seeds outside the module's delivery pattern rather than inside it.

*Cited by:* `application.yml`, `application-local.yml`, `application-test.yml`, `application-prod.yml`,
`src/test/resources/application-test.yml`, `db/migration/.gitkeep`,
`db/migration/seed/V3__seed_reference_data.sql`, `db/migration/seed/V4__seed_user_security.sql`. The applied-state
control that stands behind the configuration controls is the production rejection callback recorded
beside the sealing decision in DL-110.

*Status.* **Superseded by DL-127.** The third attempt this entry was written to prevent is the one that
succeeded, and it succeeded by answering the recursion argument rather than by ignoring it: the argument
holds only while a script sits in the shared parent, and no script does any longer. The inverted guards
this entry installed are inverted back, each asserting the opposite statement, and DL-127 enumerates
them. This entry is retained in full because the round trip it records is the most useful thing in it -
the arrangement has now been argued in both directions three times, and DL-127 explains which premise
each argument shares and which conclusion was wrong.


### DL-120 - The continuous-integration workflow runs one linear sequence of gates, with no skip switch, no tolerated failure, and unconditional execution everywhere except artifact upload

**Context.** The workflow had grown a five-gate structure in which each gate carried
`continue-on-error: true`, several steps carried `if: always()`, a final step read
`steps.*.outcome` to compute a verdict, and two gates passed `-Ddependency-check.skip=true` and
`-Djacoco.skip=true` on the command line. The intent was benign - see every gate's result on one run
rather than stopping at the first failure - but the effect was that a failing gate did not fail the run,
and the switches meant the two gates most easily asserted were the two least often executed.

**What the specification actually requires, checked rather than assumed.** The workflow's own
specification mandates a single job whose build command is exactly `./mvnw -B clean verify`; carries an
explicit prohibition set naming `-Djacoco.skip`, `-Ddependency-check.skip`, `|| true`,
`continue-on-error` on the build step and `if: always()` used to mask a failure; permits `always()`
**only** on artifact-upload steps; and specifies a literal grep as the verification. It never mandated
the five-gate structure. So restoring the specified model meant *collapsing* to a linear one, not
elaborating the existing one.

**Decision.** One linear sequence. Each gate fails the run directly, so no verdict has to be computed
from step outcomes and the verdict step is gone. `if: always()` survives on exactly the three artifact
uploads and nowhere else. No step carries `continue-on-error`. No step reads `steps.*.outcome`.

**Both skip switches had a real reason, and each was answered rather than deleted.** The
vulnerability-scan skip existed because a separate step ran the scan; the plugin is bound to `verify`,
so that step was redundant and was deleted, which removed the reason. Confirmation came from the build
log rather than from the configuration: the goal announces itself and runs immediately before the
coverage check, preserving the ordering DL-069 records. The coverage skip existed because a second
invocation overwrote the first run's execution data. That was answered by parameterising the coverage
plugin's destination and report directory through two new build properties, mirroring the existing
pattern for test reports and JVM arguments, so a second invocation writes elsewhere instead of being
switched off. The fix was proved twice: an isolated probe showed the baseline execution file
byte-identical before and after, and both rewritten gates then ran for real with the recorded digests
intact.

**One switch turned out to be doing nothing at all.** The second gate passed
`-Ddependency-check.skip=true` to a `test`-phase command. The `test` phase never reaches `verify`, so the
scan was never going to run there. Recorded because a setting that appears to be a deliberate exemption
and is in fact inert is worse than either a real exemption or none: it invites a reader to believe a
decision was made.

**A consequence worth naming, because it caught this change out once.** The explanatory comments written
to justify the withdrawn switches named those switches, and the mandated grep does not distinguish a
prohibited setting from prose about a prohibited setting. The comments were reworded to describe the
behaviour without naming the flags, so the reasoning survives and the verification stays mechanical.

*Cited by:* `.github/workflows/carddemo-java-ci.yml`, `pom.xml` at the coverage-plugin destination
properties. The goal-ordering constraint this preserves is DL-069, and the locale determinism the second
gate exercises is DL-118.

### DL-127 - The seed migrations move into a profile-scoped location, because the reason the split failed twice was the shared parent and not the mechanism

**What the review found, and why it is right.** A checkpoint review scored the schema-evolution
arrangement as an AAP-compliance failure and named five files: `FlywayConfig.java`, the three profile
overlays and the shared baseline. The finding is that the plan's structural-decisions list requires
`FlywayConfig` to resolve `V3` and `V4` **from profile-scoped locations**, so that a production
deployment migrates schema and indexes without inheriting sample data or seeded credentials, and that
the delivered class instead carried Javadoc expressly rejecting that mechanism in favour of a shared
flat location plus `spring.flyway.target=2`. Its ruling on the substitution is quoted here because it
is the sentence that settles a question DL-102, DL-111, DL-116 and DL-119 each answered the other way:
equivalent intent cannot replace the specified mechanism.

**Why the two previous withdrawals were reasoned correctly and concluded wrongly.** Every one of those
entries rests on a single true observation: a Flyway location is scanned **recursively**, so while any
script sits directly in the shared parent `db/migration`, no location list can separate the seeds from
the schema, and a deployment that resolves the parent applies the whole ascending sequence whatever
folder each script came from. That observation is correct, and it was verified again here - resolving
`classpath:db/migration` against PostgreSQL 16 reports all four scripts, and reports them under
*different* script names (`schema/V1__create_schema.sql` rather than `V1__create_schema.sql`), so the
parent is not merely permissive but records a different history. What the observation actually supports,
though, is that the **schema** scripts must move down as well. Both previous attempts left `V1` and `V2`
in the parent and moved only the seeds, which is why the parent stayed reachable and the boundary stayed
notional. The conclusion drawn - abandon the location mechanism - does not follow from the premise.

**Decision.** All four scripts move one level down into two sibling locations, and the shared parent is
left holding no script at all:

- `classpath:db/migration/schema` - `V1__create_schema.sql`, `V2__create_indexes.sql`. Resolved by every
  profile, including production.
- `classpath:db/migration/seed` - `V3__seed_reference_data.sql`, `V4__seed_user_security.sql`. Resolved
  by the local and test profiles only. `FlywayConfig.resolveLocations` **refuses** it while the
  production profile is active, and refuses the shared parent on the same ground, since a location above
  the split reaches the seeds through the child directory.

With the parent empty, the recursion has nothing to cross. Verified against PostgreSQL 16 before the
change was recorded: a production-shaped location list resolves exactly `V1` and `V2`, and the seeds
appear in no state at all - not applied, not pending, not above target, not resolved. That is a stronger
statement than the previous arrangement could make, where both seeds were resolved and reported and
withheld only by a version comparison.

**The version ceiling is retained, and it is retained deliberately rather than left behind.** The
migration specifications for `V3` and `V4` each name `spring.flyway.target=2` as the production control
and each permit "equivalent version-aware or filename-aware filtering"; the shared baseline and the
production overlay both still declare it, and `FlywayConfig.resolveTarget` still refuses a production
ceiling that reaches version 3 or beyond. Both controls are therefore in force. They are not redundant,
because they are defeated by different mistakes: renumbering a seed at or below 2 defeats the ceiling
and not the location list, while a merged profile list or a command-line override that adds the seed
location defeats the location list and not the ceiling. What has changed is the order of precedence. A
location a profile never lists is not a value an operator can widen; a ceiling is exactly that. The
location is the mechanism and the ceiling is the belt behind it.

**What was reconciled, stated plainly because the tension is real.** The specifications for `V3` and
`V4` each say in terms that no subdirectory be created, and each fixes its script's delivered path in
the parent. Two things about the delivered change bear on that. The file **names** are unchanged, which
is the constraint both specifications state as load-bearing - each is a Flyway log token that a
compose bring-up check reads out of the history table, and Flyway records the script name relative to
its location, so a child listing reproduces exactly the names the flat layout produced. And both
directories remain inside the plan's own delivery pattern for this module, `db/migration/**.sql`, whose
`**` anticipates nesting. What could not be preserved is the subdirectory prohibition itself, because it
and the profile-scoped-location requirement cannot both hold: the prohibition exists only to explain why
the location mechanism was thought unimplementable, and moving the schema scripts down removes that
reason. The named structural decision in the plan governs over a path listing, and the review has ruled
on which reading is binding.

**The inverted guards are inverted back, which is most of the work.** DL-119 left behind assertions
whose purpose was to make a third attempt at the split *fail a test*: two withdrawn folder names kept as
constants asserted to deliver nothing, a guard reporting any script in a subdirectory, and per-document
assertions that every profile declares exactly the parent. Each is now an assertion of the opposite
statement, and each is stronger than what it replaced. The new guards require the schema location to
carry the schema scripts and nothing else, the seed location to carry the seeds and nothing else, the
shared parent to carry no script at all, the two locations to be siblings with neither inside the other,
the two non-seeding documents to declare the schema location alone and not the seed location, the two
seeding documents to declare both, and no document to resolve the parent in any spelling. The
integration tier gained the sharpest of them: removing the ceiling entirely from a production-shaped
location list still seeds nothing, where under the previous arrangement the same test proved that ten
known sign-on identities land.

**Status of the neighbouring entries.** DL-108 recorded a split and was superseded twice; the
arrangement it reached for is now delivered, but by a different topology - it moved the seeds to
`db/seed`, outside the plan's delivery pattern, while this entry keeps both halves inside
`db/migration` - so it remains superseded rather than reinstated. **DL-102, DL-111, DL-116 and DL-119
are superseded by this entry.** Their shared premise about recursion is retained and is quoted in the
delivered comments, because it is the reason the parent must stay empty; only their conclusion is
reversed.

*Cited by:* `config/FlywayConfig.java`, `application.yml`, `application-local.yml`,
`application-test.yml`, `application-prod.yml`, `src/test/resources/application-test.yml`,
`db/migration/.gitkeep`, `db/migration/schema/V1__create_schema.sql`,
`db/migration/schema/V2__create_indexes.sql`, `db/migration/seed/V3__seed_reference_data.sql`,
`db/migration/seed/V4__seed_user_security.sql`, `domain/TransactionType.java`,
`support/AbstractPostgresIT.java`, `config/FlywayConfigTest.java`,
`config/FlywayConfigCoverageTest.java`, `config/ConfigurationProfileBaselineTest.java`,
`config/ApplicationProfileStartupTest.java`, `config/SeedMigrationIT.java`,
`config/SeededIdentifierSealingIT.java`, `config/ProductionSeedRejectionCallbackIT.java`. The
applied-state control that stands behind both configuration controls is the production rejection
callback recorded beside the sealing decision in DL-110.


---

### DL-121 - A keyed read of a nonunique alternate index becomes a bounded, base-key-ordered finder, and the supporting indexes are deliberately left alone

**Context.** Two of the three legacy alternate indexes are declared `NONUNIQUEKEY` with `UPGRADE`:
`CARDAIX` over the card cluster on the account identifier at `KEYS(11 16)`, and `CXACAIX` over the
cross-reference cluster on the same identifier at `KEYS(11,25)`. Their Java counterparts were declared
as finders returning an unbounded, unordered list, on the reasoning that the alternate key admits
duplicates and that choosing one row out of several is a service-layer decision.

**What the source actually does, which settles it.** Every legacy consumer of either path issues a
single keyed `EXEC CICS READ`, never a browse: the bill-payment, transaction-add and account-view
programs against the cross-reference path, and the card-detail program against the card path. A keyed
read of a duplicate-bearing alternate index returns exactly one record, and which one is defined - the
first in ascending *base*-key order. So the legacy behaviour is not "every match, and the caller
decides"; it is "one match, and the structure decides which".

**Why leaving that to the service layer was wrong rather than merely lax.** Handing a caller an
unordered list to take the head of makes the result depend on plan shape, on insertion history and on
whether a vacuum has run. Nothing in a relational query guarantees first-row identity or any ordering
among duplicates without an `ORDER BY`. The legacy read is deterministic; the translation was not, in
exactly the case where determinism is the contract. It also duplicated the same head-of-list decision
into every future caller, where each one could get it wrong independently.

**Decision.** Both finders become bounded and explicitly base-key ordered, and their names say so:
`findFirstByCardAcctIdOrderByCardNumAsc` returning `Optional<Card>`, and
`findFirstByXrefAcctIdOrderByXrefCardNumAsc` returning `Optional<CardCrossReference>`. The empty
`Optional` is the analogue of the legacy not-found response, so no exception is raised at this layer.
A single-valued *unbounded* derived query was rejected: it raises an incorrect-result-size failure the
moment a second row exists, which is a failure the legacy system cannot produce.

**The card repository keeps its paged overload, and the cross-reference repository still has none.**
`Page<Card> findByCardAcctId(String, Pageable)` is the browse translation the migration plan names
literally, and it stays - unchanged in name, shape and caller-supplied size and sort - for genuine
browse consumers. The cross-reference path has no legacy browse at all, so it gains no paged form; the
card-list screen browses the *base* cluster and filters by account after the read, so it uses the
inherited paged `findAll` rather than either method.

**The two supporting indexes are deliberately not widened.** Making
`idx_card_cross_reference_xref_acct_id` a composite over the account identifier and the card number
would let the ordering be satisfied from the index instead of by sorting the handful of rows the
account owns. It was declined: the estate declares exactly three alternate indexes and the delivered
migration emits exactly three B-tree indexes to match, so changing one into a composite alters a
delivered migration for a plan-shape gain that no measured baseline asks for. The same reasoning
applies to `idx_card_card_acct_id`.

*Cited by:* `repository/CardRepository.java`, `repository/CardCrossReferenceRepository.java`. The index
inventory this preserves is `V2__create_indexes.sql`.

---

### DL-122 - The report range is read a slice at a time, and a redundant pre-bound is what lets the timestamp index constrain both ends

**Context.** The batch report's selection reproduces a sort specification that types the processing
*date* as ten characters at one-based offset 305 over a 26-character column, and filters inclusively
between two ten-character parameters. DL-era reasoning had already established the asymmetric
predicate that makes that faithful - a bare column on the lower bound, a ten-character `SUBSTRING` on
the upper - because comparing the full 26-character value against a ten-character end date would drop
every transaction processed *on* the end date. Two consequences of that shape were left unaddressed.

**First consequence: the range was materialised whole.** The bounds come from an operator-supplied job
parameter and the table is append-only - the posting run, the interest run and the online add path only
ever add rows. The number of rows a range selects is therefore unbounded in principle and grows for the
life of the deployment, so a `List` return made the reporting job's memory a function of accumulated
history and of how wide a range somebody typed.

**Decision on the first.** The method returns a `Slice` and takes a `Pageable`. A slice rather than a
page because a page carries a total count, which costs a second aggregate over the same range on every
fetch and which the report has no use for: it breaks its pages and its totals from the rows themselves,
line by line. The ordering gains a second term - the transaction identifier - because card number is
not unique across transactions and an ordering on it alone lets a row be returned twice or skipped as
the reader advances. That is faithful rather than additive: the legacy sort declares one key and no
`EQUALS` option, so it guarantees nothing about the relative order of records sharing a card number,
and any total order refining the declared key is admissible. The declared ordering sits in the query
text, so a sort carried on the pageable is appended after it and can only refine an already total
order.

**Second consequence: the upper bound could not reach the index.** A predicate over a *function* of a
column cannot bound an index built on the column, so the authoritative `SUBSTRING` comparison left the
index entered at the start date and read to the end of the table, with every later row fetched,
discarded and then sorted. The plan was measured rather than assumed: without a pre-bound the engine
chooses a sequential scan and carries both predicates as filters.

**Decision on the second.** A third, redundant predicate compares the bare column against the end date
concatenated with sixteen nines. Its right-hand side mentions no column, so it is evaluated once and
used as the index's upper bound; the measured plan becomes an index scan whose index condition carries
*both* ends, with the ten-character comparison retained as the filter that decides membership.

**The pre-bound is never the authority, and its safety is proved from the layout rather than assumed.**
It only has to be wide enough never to exclude a row the authoritative predicate keeps. A populated
processing timestamp is a ten-character date, a separating space, then a time of day, so its eleventh
character is a space; an unprocessed transaction is blank throughout. Under byte ordering the
comparison is decided at the first differing character: equal date prefixes hand the decision to the
eleventh character, and a space is below the digit nine. Under a language-aware collation, which weighs
digits ahead of spaces and punctuation, the pre-bound contributes the date's digits followed by sixteen
nines while a stored value contributes the same digits followed by the time's, of which the first is
the tens digit of an hour and so at most two. The pre-bound is the greater value either way. Both
orderings were checked because the delivered stack pins one of them and the test containers do not: the
compose database is initialised to byte ordering on purpose, so that sorted output can be compared byte
for byte against the legacy baselines.

**The invariant this rests on is written into the method, because a writer could break it silently.**
A stored processing timestamp is either blank throughout or carries a space in its eleventh character.
Widening the filler, or replacing it with a character a language-aware collation ignores, breaks the
second argument; shortening it below the sixteen characters that follow the date prefix breaks the
first. The neighbouring card-number invariant is recorded in the same place for the same reason: the
report's single ascending ordering is faithful to a sort that types those bytes as zoned decimal *only*
while every stored card number is sixteen zero-padded unsigned digits, and a shorter or signed value
would reorder the report without breaking anything a compiler or an unwitting test would notice.

**A deviation from this file's own generation brief, recorded rather than smoothed over.** The brief
for the transaction repository fixed the range query's return type as a list and prohibited a paged
overload. The migration plan fixes neither, and the review that reported both consequences above
governs the point, so the return type changed. The brief's *countable* constraint was honoured
literally: the interface still declares exactly two methods, because the pre-bound is derived inside
the query text instead of becoming a third parameter that every caller would have to compose
correctly.

*Cited by:* `repository/TransactionRepository.java`. The index it now bounds on both sides is
`idx_transaction_tran_proc_ts` in `V2__create_indexes.sql`.

---

### DL-123 - The first failed validation ends the report-request turn, and the decision to accumulate several is withdrawn

**Context.** The report-request translation collected more than one field failure in a turn. The
reasoning recorded at the time was that the six independent range tests are written as six separate
`IF` statements rather than as one evaluation, so each ought to be able to report its own field, and
that the two-state field contract - not supplied, versus supplied wrongly - needed several entries to
be worth having. A gate was kept between *stages* so that the date-validation subprogram was never
handed a date assembled from a part already faulted, and that gate was believed to be the whole of the
fidelity requirement.

**What the source actually does.** Every failure site performs the send paragraph. The send paragraph
ends with `GO TO RETURN-TO-CICS`. The return paragraph issues `EXEC CICS RETURN`. So the task **ends**
at the first failure: the paragraph that performed the send never resumes, and everything sequenced
after that `PERFORM` is unreachable. In the operator-supplied arm that is a great deal of work - the
numeric normalisation of all six date parts, the five range tests after the first failing one, the
assembly of both ten-character dates, both subprogram calls, the four substitution slots, the
report-name assignment and the submission attempt. The earlier reading had the reachability boundary in
the wrong place: it is not between stages, it is at the first failure.

**Why the difference is observable and not merely structural.** Three things changed for a caller.
The response could carry field errors the legacy screen never emitted together. The echoed input was
mutated by a normalisation the legacy never performed on a turn it had already faulted, so a client
redisplaying the echo would show values the operator never typed. And the end-date validator could be
called after the start date had already failed, producing a second, derived failure on top of the real
one.

**Decision.** The send raises a turn-ended marker, and every site that could otherwise continue tests
it and returns. The six range tests return after faulting; the end-date subprogram call is reached only
when the start date was accepted; the operator-supplied arm returns after each stage that could have
faulted; the acknowledgement block and the whole card-emitting path are gated on the same marker.

**Why a marker and not an exception.** A jump out of a call stack has no Java equivalent, and an
exception was rejected because ending a turn is the *ordinary* outcome here - the successful
acknowledgement send ends the turn too. Modelling it as a throw would make every normal turn look like
a fault to every caller, every logger and every error-handling boundary. The marker is deliberately
separate from the error flag for the same reason: the acknowledgement raises the marker without raising
the flag, so folding them together would have made success indistinguishable from failure.

**What survives from the withdrawn decision.** The two-state field contract is untouched: a field is
still reported as MISSING or INVALID, with its own byte-exact text and its own cursor position. There
is simply at most one such report per turn, which is the legacy's own cardinality. The latch that keeps
the summary message and the cursor position on the *first* failure also survives, and is now
structurally redundant rather than load-bearing - kept because it states the invariant at the point
where it could otherwise be broken.

*Cited by:* `service/ReportRequestService.java`, and its covering suite
`ReportRequestServiceTest.TheFirstFailedValidationEndsTheTurn`, whose assertions check both halves of
the contract: that the reported failure is the one the legacy would have shown, and that the work the
legacy never reached did not happen.

---

### DL-124 - The production migration state is one exact point, not an upper bound, and the guard now refuses falling short as well as reaching too far

**Context.** Two guards hold the production database to the delivered schema: one over the migration
location and one over the version ceiling. Both were written against a single threat - that a merged
environment, an operator override or a co-activated overlay would let the two seed scripts reach
production, seeding fifty synthetic customer rows carrying regulated identity data and ten known
sign-on identities. Read that way, "at most version two" and "a location beneath the delivered one" are
both perfectly safe, and both were accepted.

**The failure that reading admits.** Production is not an upper bound; it is an exact state - the four
delivered scripts, resolved from the one canonical location, applied up to and including version two.
A ceiling of one satisfies "at most two" and applies only `V1__create_schema.sql`, so the three
alternate-index equivalents and the six foreign keys in `V2__create_indexes.sql` are never created. An
absent location list, a nested sub-path, a file-system descriptor or a prefix-less spelling each
resolve fewer than the four delivered scripts, with the same effect.

**Why nothing downstream would have caught it.** Hibernate is fixed at schema *validation*, and
validation inspects tables and columns. It does not inspect indexes and it does not inspect
constraints. A production deployment migrated to version one would start, pass validation, report
healthy, serve every request - and run every access path the module was measured against as an
unindexed scan with no referential integrity behind any of it. There is no later gate: the seeded-database
refusal callback answers a different question, and the coverage and contract suites run against a
database migrated by the test profile.

**Decision.** Both guards now require an exact state under production. The ceiling must parse to
exactly version two: a higher ceiling, a lower one, the seeding marker, any predefined marker, an
unreadable value and an absent value are all refused. The location list, after blank and `null` entries
are discarded, must be exactly the one canonical descriptor: an empty or absent list, an additional
location beside it, a nested sub-path beneath it, a file-system descriptor addressing the same
directory and a prefix-less spelling are all refused.

**Two deliberate tolerances, so the guard refuses wrong configuration rather than untidy
configuration.** Blank and `null` list entries are ignored before the comparison, because a
comma-separated property list frequently produces one and an empty entry addresses nothing. The ceiling
is compared as a *parsed* version rather than as text, so a padded or differently spelled spelling of
the same version is accepted while a different version is not.

**The completion half is deliberately left permissive, and the asymmetry is the point.** For local and
test the resolver still appends the canonical location when the bound list does not already resolve it,
and it still recognises every spelling of the directory when deciding whether it is already there. That
predicate's job is to avoid appending a duplicate, not to constrain anything, so tightening it would
make a legitimate local configuration fail for no benefit. The two halves now answer two different
questions, which is why the strict comparison is written separately rather than by narrowing the
existing predicate.

**Three tests that asserted the old leniency were re-aimed rather than deleted.** They had encoded the
four near-miss location spellings as acceptable, a ceiling of `1.1` as acceptable, and an absent
location list under production as resolving to nothing. Each now asserts the refusal, and each carries
the reason in its own comment so the change is not mistaken for a tightening without cause. Two new
cases were added for the state that was previously reachable: a ceiling of one, and a list that
addresses nothing in each of its four forms.

*Cited by:* `config/FlywayConfig.java` and `FlywayConfigTest`. The one-location-plus-ceiling arrangement
this enforces is DL-102, restated in DL-111 and DL-116, and the directory-split round trip it replaces
is DL-119.

---

### DL-125 - The user-security fixture keeps its geometry and loses its credential, and the absence is asserted rather than trusted

**Context.** The provisioning job carries its ten sign-on identities in stream as fifty-seven-character
cards, and the record layout pads each to eighty. That content was reproduced into a committed
fixture - eight hundred bytes, ten records - byte for byte, including the one shared eight-character
password literal every card carries. The reasoning was fidelity: the fixture is derived from the job
and reproducing it exactly is what makes it evidence.

**Why fidelity was the wrong test to apply here.** The requirement that no credential is hardcoded is
not satisfied by a credential being *faithful*. Reproducing the literal put a working, reusable secret
into version control in the most directly extractable form there is: a fixed offset in a fixed-width
file, identical on all ten records. The seed migration was already correct - it stores BCrypt digests
and never the literal - so the fixture was the only artefact in the module from which the value could
be lifted, and it undid what the seed had been careful about.

**Decision.** The credential window carries a fixed structural placeholder of exactly the same width.
Everything else is unchanged: the eight hundred bytes, the ten records, the eighty-byte stride, the
absent line terminator, the ten identifiers in the order the job writes them, both name fields, the
five-and-five role split and the blank filler from character fifty-seven to eighty. So the fixture is
still the record-geometry evidence it was created to be, and the mapper still reads a full-width slice
where the layout says one is.

**Why substitution rather than deletion, which was the other option.** Deleting the fixture removes
today's copy of the literal and does nothing about tomorrow's. The substituted fixture carries two new
assertions instead: the credential window must equal the placeholder on every record, and the legacy
literal must not appear anywhere in the file in any case. Those turn the property into something the
build enforces, which deletion could not. Positively asserting the placeholder also matters more than it
looks: the previous assertions - non-blank, full width, identical across records - were all equally true
of the credential, so they could not have detected it.

**The literal is named once, in the test that forbids it.** Asserting an absence requires writing the
value down. It is declared as a single constant in the fixture suite with a comment stating that its
only purpose is to be forbidden, and nothing reads it as an authentication input. The seed's digests are
digests of the legacy literal, so the placeholder authenticates against nothing.

*Cited by:* `src/test/resources/fixtures/input/usrsec.txt` and
`FixtureContractTest.TheDerivedCredentialFixture`. The seeded digests this leaves untouched are
`V4__seed_user_security.sql`.

---

### DL-126 - A cross-reference row renders no value at all, because a partial redaction is an assurance rather than a control

**Context.** The cross-reference entity withheld its card number from the diagnostic rendering - that
value is a primary account number - while rendering the customer identifier and the account identifier
in full. The reasoning was that those two are internal keys naming no cardholder and revealing no
instrument, and that a diagnostic unable to say which account a row points at explains nothing.

**Why that reasoning does not hold for this entity in particular.** This row's entire purpose is to
resolve a card number to those two identifiers. A rendering that names them is therefore one join away
from the value it was careful not to print, and the join is against a table the same application
already has open. The withheld field and the rendered fields are not independent here; they are the two
sides of one mapping.

**The second problem is what a partial redaction communicates.** A rendering that visibly redacts one
field reads as a considered judgement that rendering the entity is safe - which is exactly the belief
that leads an author to put an instance into a log line, an exception message or an assertion. A
control that encourages the behaviour it exists to constrain is not a control.

**Decision.** All three fields render as the same placeholder. The type name and the field names are
kept, because identifying *what* reached a diagnostic is the one thing a rendering is legitimately for;
*which* row it was is not carried at all. Code that has to identify a specific row must name the value
it chose to disclose, deliberately, at that site. Every accessor still returns its value untouched, so
nothing stored, mapped or transmitted changes.

**The covering test asserted the opposite and was withdrawn with the exposure.** It positively required
both resolved identifiers to remain visible, so it would have failed this change and, left as it was,
would have held the exposure in place permanently. It now requires that no field value of either seeded
row appears in the rendering, in whole or in a six-character fragment, and adds the strongest available
statement of the property: two rows that agree on nothing must render identically, which cannot be true
of a rendering carrying anything row-specific.

**What this does not close.** The schema applies no field-level protection to the card number and the
migration introduces none, because the legacy design applies none and inventing one would be feature
expansion. That residual gap is decision D-14 and remains open. This entry settles only that an
unintended rendering is not the thing that widens it.

*Cited by:* `domain/CardCrossReference.java`, `CardCrossReferenceTest.DiagnosticRendering`. The
placeholder vocabulary is the one `domain/Card.java` and the DTO layer already use.

---

### DL-129 - One scrape target per deployment, because a candidate list makes a dead target permanent and a live pair doubles the baseline

**Context.** The application scrape job listed two targets in one static configuration: the compose
service name for an application running as a container beside the stack, and the host-gateway address
for one running directly on the host. The intent was convenience - whichever address happens to be live
gets scraped, and every series carries the same job label either way.

**What that actually produces, in both of its two states.** The compose file in this module defines
postgres, localstack, jaeger, prometheus and grafana, and no application service. The container address
can therefore never resolve here, so it is a permanently failed target: the scrape-health view is
permanently red, which trains a reader to ignore the one view that says whether the performance gate has
data at all. And if an application container were later added and both addresses reached a process, both
series sets would carry the same job label and differ only by instance - so every sum-by aggregation the
provisioned dashboard is built from would count a process twice. Those aggregations are request rate,
batch throughput, heap used and heap committed: throughput and memory are exactly the two figures the
performance gate records. A doubled baseline is worse than a missing one, because it looks credible.

**Decision.** The application job carries exactly one target, and which address it is a deployment
choice made by editing this file rather than a list for Prometheus to try. The delivered target is the
application's compose service name reached over the project network, because the compose stack in this
module publishes that service, so the address resolves without a host-gateway fallback. Collecting from
an application started on the host instead means *replacing* that target, not adding to it - and it
belongs to a job of its own, with its own name and its own evidence queries, because a second entry in
this job would double every series whenever both answered.

**Two things deliberately not changed.** The job name stays `carddemo-app`, because every panel in the
provisioned dashboard selects on it and renaming it empties all of them while the stack still reports
healthy. And the loopback address remains explicitly excluded, since inside the Prometheus container it
resolves to Prometheus itself - a scrape that succeeds at the transport level and yields nothing, which
surfaces only as an empty gate write-up.

**Verified by running it, not by reading it.** A throwaway Prometheus was started on a
clone-index-derived port against this file, with the application running on the host, and the shared
stack was left untouched. Its target API reported exactly one target in the application job, up;
`count(up{job="carddemo-app"})` and `count(count by (instance) (up{job="carddemo-app"}))` both returned
one, which is the property that makes the job-level sums single-counted; and the heap and per-endpoint
aggregations the dashboard uses each resolved to a single credible figure. Both throwaway containers were
removed afterwards.

*Cited by:* `config/prometheus/prometheus.yml`. The job selector it preserves is
`config/grafana/dashboards/carddemo-overview.json`; the service it addresses is the `app` service in
`docker-compose.yml`, and the host-gateway mapping on the Prometheus service remains available for the
separate job a host-mode collection would need.

---

### DL-128 - Eighteen artefacts published ahead of their processing position are absorbed and reviewed rather than reset, because the alternative destroys mandated deliverables

**Context.** A checkpoint review found that eighteen paths outside its declared processed range had been
modified by the same commit that delivered the range: sixteen test classes, the provisioned Grafana
dashboard, and this log. Its finding is correct as stated - those contents were not covered by that
review's file-by-file pass - and the resolution it suggested was to move them to their owning checkpoint
or reset them from the branch before publication.

**Two constraints decide what "resolving" it can mean here, and they point the same way.** The
publication contract this work runs under prohibits history-altering git operations outright - no
rebase, no reset, no force - in the repository and in every submodule, so a reset is not available. And
the migration plan mandates each of the eighteen by pattern: the test tree, the observability
configuration directory, and this document are all named as deliverables to create. So the only reset-like
action available - deleting them in a further commit - would delete mandated artefacts.

**What deleting them would actually cost, stated concretely rather than as a worry.** Fifteen of the
sixteen test classes are the covering suites of production files *inside* the reviewed range: the
exception handler, four screen contracts, the reject-record writer, the AWS properties holder, the
entity mapping inventory, the transaction entity, both concurrency-token services, the menu and
report-request services, the named fixtures, and two record mappers. They contribute 1,086 executing
assertions. Removing them would drop coverage that the enforced floor depends on, and would remove the
verification of work the same review passed. This log is cited by forty-seven production sources by
decision number, so deleting it would break every one of those citations - including the citations three
of this session's own fixes add. The dashboard is the artefact the metrics job's own topology decision is
verified against, and it is mounted by the compose stack.

**Decision.** The eighteen stay, and the review's underlying concern - unreviewed content on the branch
- is answered by reviewing them here rather than by removing them. Every one was read and audited in
this session: each Java file carries the exact fourteen-line licence header, none imports from the
pre-Jakarta namespace, none uses a wildcard or star import, none carries a warning suppression, none
contains a placeholder, a deferred-work marker or a hardcoded credential, and all sixteen execute in the
suite with no failure, no error and nothing skipped. Reflection appears in four of them and is
legitimate and in scope: it is used to assert record components, a handler's declared methods and the
constructors of static-only utility classes, and the unsafe-code audit is explicitly scoped to
production sources, where the count remains zero. The dashboard parses as JSON, declares eleven panels
and is wired by the provisioning provider the compose stack mounts.

**Two of the eighteen were changed again in this session, deliberately.** The report-request suite gained
the eight cases that hold the corrected turn-termination contract, and the named-fixture suite gained the
two that forbid the credential literal. Both are the covering suites of production files fixed in this
same pass, and a fix without its covering assertions would be the weaker outcome of the two available.

**What is not claimed.** This does not make the eighteen part of the range that review covered, and it
does not overturn the finding. It records that the artefacts are mandated, that the suggested remedy is
unavailable and its available approximation destructive, and that the content has now been reviewed and
is owned. A reviewer re-checking this should expect the paths to still be present.

*Cited by:* nothing in code - this entry exists so the disposition is on the record rather than inferred
from a diff.

### DL-130 - The reporting period travels as the three screen selectors it is entered on, in both directions, and the single resolved enumeration is kept beside them rather than replaced by them

**Context.** The report-request screen presents three independent one-character selectors -
`MONTHLYI`, `YEARLYI` and `CUSTOMI` of `app/cpy-bms/CORPT00.CPY` - and the operator marks one. The
request contract collapsed all three into a single enumerated `reportPeriod` component while the
service kept three raw selectors and its own ordered evaluation, so the two halves of one turn
disagreed about what a reporting period is.

**Why the collapse cannot be repaired by keeping the enumeration.** Three separate positions admit
states a single enumeration cannot name. Nothing on the screen prevents an operator marking two, or
marking one with a character other than the expected one, and the program has defined behaviour for
both: `app/cbl/CORPT00C.cbl` tests the three in order at lines 214, 240 and 256 and falls through to a
catch-all at line 437 that raises the select-a-type message against the monthly field. A multiply
marked screen therefore resolves to the *first* marked position, and a screen marked with an
unexpected character is still marked. Collapsing to an enumeration discards the distinction between
"unmarked" and "marked with something unexpected", and discards which position won, so the ordered
evaluation the program performs has nothing left to evaluate.

**Decision, inbound.** `ReportRequest` declares `monthlySelection`, `yearlySelection` and
`customSelection`, each bounded at one character and carrying no presence rule, no pattern and no
mutual-exclusivity rule. The vocabulary is *produced* by the service's ordered evaluation, not
*supplied* by the contract, which is what lets a multiply marked or oddly marked screen cross the
boundary and be resolved exactly as the program resolves it.

**Decision, outbound.** The same three selectors are added to `ReportResponse`, and the resolved
`reportPeriod` is *kept beside them*. They are not duplicates of each other. The three markers are
what the operator sees still standing in the fields they typed; the resolved period is what the
service concluded. `INITIALIZE-ALL-FIELDS` at `app/cbl/CORPT00C.cbl:L633-L646` blanks all three
selectors along with the six date parts and the confirmation flag, so a successful submission and a
declined confirmation both return a *cleared* screen, while every error path returns the transmitted
marks still standing. An error turn can therefore present three marks while no period was resolved at
all - a state that cannot be expressed if the response carries only one of the two.

**What this cost.** One hundred and eleven construction sites across seven test files were rewritten,
and eight test methods that positively asserted the collapsed shape were inverted to assert the
restored one. Those assertions were not wrong when written; they pinned a contract that the service
never agreed with.

*Cited by:* `api/dto/ReportRequest.java`, `api/dto/ReportResponse.java`,
`api/ReportContractAdapter.java`, `service/ReportRequestService.java`.

---

### DL-131 - The menu option catalog moves from the configuration package into the service package, because the cycle it created was real and the direction of the fix is not a matter of taste

**Context.** `MenuService` imported `config.MenuOptionCatalog` while `config.FlywayConfig` imported
`service.SensitiveFieldEncryptionService`, so the service and configuration packages depended on each
other. The module's layering permits configuration to depend on every layer beneath it and permits
nothing to depend on configuration, so exactly one of those two edges had to go.

**Why the catalog is the one that moves.** The catalog is not configuration. It holds the ten user
menu options of `app/cpy/COMEN02Y.cpy` and the four administrative options of
`app/cpy/COADM02Y.cpy` - the option numbers, their labels and the program each dispatches to. That is
estate data with behaviour attached, which is what the service layer is for, and two of its immediate
neighbours are already there: `ValidationLookupService` holds the externalised lookup tables and
`MessageCatalogService` holds the shared screen literals. `FlywayConfig`'s dependency, by contrast, is
genuinely configuration reaching downward to seal seeded values, which is the permitted direction.

**Decision.** `MenuOptionCatalog` moves to `com.carddemo.service` and is annotated `@Service`,
matching the two neighbours it now sits beside. Its 1243-line test moves with it. Fifteen textual
references were requalified. The `service → config` edge count is now zero, asserted by a guard test
rather than left to review.

*Cited by:* `service/MenuOptionCatalog.java`, `service/MenuService.java`, `PackageLayeringTest`.

---

### DL-132 - Services stop naming transport records and answer with results of their own, and the wire records they used to return are assembled by adapters at the boundary

**Context.** Three services named types from `com.carddemo.api.dto`: `MenuService` returned
`MenuResponse` outright, and `NavigationService` and `ReportRequestService` accepted and returned
`NavigationContext`. The layering forbids a service depending on the transport, and the practical
consequence was worse than the structural one: a service returning a wire record has to fill every
component of it, including the eleven that carry client-echoed identity and personal detail, so the
services were copying echoed values forward as a matter of course.

**Decision.** Three things, in the order they depend on each other.

First, `service/ConversationState` is introduced as the service-owned carried state. It models five
fields - the originating transaction and program, the nominated destination transaction and program,
and the entry mode - and none of the eleven echoed members. A service that needs carried routing takes
this; there is no longer a type in the service layer through which an echoed identity could arrive.

Second, each affected service gains result types of its own: `MenuService` declares `MenuKind`,
`MessageSeverity`, `MenuRow` and `MenuScreen`; `ReportRequestService` already had `ReportScreenInput`,
`ScreenFields` and `ReportRequestResult`. The three `withRouting` helpers that existed only to copy
personal detail from an inbound record to an outbound one are gone, because there is no longer an
inbound record to copy from.

Third, three adapters are added in `com.carddemo.api`: `ConversationStateAdapter`,
`MenuResponseAdapter` and `ReportContractAdapter`. The first is the trust boundary and is the subject
of DL-133.

**One consequence worth stating, because it is easy to read as an oversight.**
`NavigationContext.empty()` leaves `programContext` null, and `ConversationStateAdapter` always writes
it explicitly. The legacy field is `CDEMO-PGM-CONTEXT PIC 9(01)` with condition names valued zero and
one, so a single-digit numeric item cannot be absent - the estate cannot produce a third, unset state.
The flag gates field-level error decoration on the destination screen, so leaving it to be inferred
would make a screen's error display depend on an absence the mainframe never had. It is therefore
always written, and the normalisation of an absent entry mode to first entry follows the same
reasoning. That normalisation makes `empty().withFirstEntry()` the identity, which corrected a
`NavigationServiceTest` assertion that had depended on a nullable third state with no legacy
counterpart.

*Cited by:* `service/ConversationState.java`, `service/MenuService.java`,
`service/NavigationService.java`, `service/ReportRequestService.java`,
`api/ConversationStateAdapter.java`, `api/MenuResponseAdapter.java`, `api/ReportContractAdapter.java`.

---

### DL-133 - Identity is derived from the authenticated principal and the echoed copy is reconciled rather than trusted, and the client's carried state shrinks from sixteen components to five

**Context.** `CARDDEMO-COMMAREA` of `app/cpy/COCOM01Y.cpy` carried sixteen fields between
pseudo-conversational turns, and its Java counterpart is echoed by the client. Eleven of those
sixteen are identity or personal detail: the user identifier and type, the customer identifier and
three name parts, the account identifier and status, the card number, and the two map names. Services
were reading them and returning them, and the record's own `agreesWith` and `reconciledWith` helpers
were never invoked anywhere.

**Why an echoed value cannot be an authority.** On the mainframe the communication area was held by
the region and a terminal could not alter it. Over HTTP the client holds it, so every one of those
eleven is caller-controlled. Reading an identity from it and then selecting a record by that identity
is the whole of the vulnerability, and it does not require any single line to look wrong.

**Decision.** Inbound, `ConversationStateAdapter` drops all eleven and hands the service only the five
routing and mode fields. Outbound, the eleven survive unchanged - the screen contract declares them and
a client that echoed them gets them back - but the identity components are replaced by the
*authenticated principal's* values, never the echoed ones. The mismatch check `reconciledWith` is
invoked, and it **reports rather than refuses**: a disagreeing echo and an agreeing echo produce the
same outbound record, differing only in what is logged. Refusing would turn a stale browser tab into a
failed request, which the legacy system never did.

**What makes this checkable rather than asserted.** Two audits. No source outside the API and
configuration packages names `NavigationContext` at all, so no service, batch, repository, domain or
utility class has a receiver an echoed member could be read from. And among the sources that do name
it, only the boundary reads one. The second audit is scoped to files that name the record because a
member accessor name can be a homonym - `MenuOptionCatalog.UserMenuOption` declares its own
`userType()`, which is the option's required role from `app/cpy/COMEN02Y.cpy` and not an echoed claim,
and the menu authorization reads an explicit authenticated parameter instead.

*Cited by:* `api/ConversationStateAdapter.java`, `api/dto/NavigationContext.java`,
`ConversationStateAdapterTest`.

---

### DL-134 - The two transport records that a service still names stay where the plan put them, and the exemption is written into the layering guard as an exact pair

**Context.** The layering work of DL-132 removed every upward edge from the service layer except one:
`FieldErrorTranslationService` imports `api.dto.FieldErrorDecorator`. A review finding proposed
relocating both that type and `NavigationContext` out of the transport package.

**Why the relocation was declined, and on what authority.** The migration plan freezes the package of
both types: they are named in the target structure and in the file-by-file mapping as members of
`com.carddemo.api.dto`. The plan is the agreed source of truth and code is aligned to it rather than
the reverse. Separately, the platform's own instruction for the account-update service explicitly
licenses a service to import `com.carddemo.api.dto` types that it *returns*, which is exactly what
this edge is. The decision therefore rests on plan precedence and on an explicit licence, not on the
relocation being difficult - the move itself would be mechanical.

**Decision.** Both types stay in `api.dto`. The one remaining upward edge is written into
`PackageLayeringTest` as an exact class-and-type pair rather than as a package-level allowance, so a
second such edge fails even though the first is permitted, and the permitted one failing to exist also
fails. The guard holds the whole direction table, carries floors on the number of sources and edges it
must see so it cannot pass vacuously, and was proven non-vacuous by injecting four separate violations.

*Cited by:* `PackageLayeringTest`, `service/FieldErrorTranslationService.java`.

---

### DL-135 - The regulated components of the two account screens are masked by default and revealed only under a named purpose and an authorization, which is a documented departure from what the legacy showed

**Context.** The account view and update responses declare the operator's national identifier, date
of birth, government-issued identifier and electronic-funds account identifier - four components on the
view screen and eight on the update screen, where the identifier and the date arrive as separate
positions. The `Customer` entity accepts only sealed envelopes for two of those columns, so a direct
mapping from entity to response either throws or emits ciphertext, and there was no adapter between
them. Both records redact all four in `toString`.

**Why a redacting rendering is not a control.** The serializer writes the components, not the
rendering. A value placed on one of those components in the clear crosses to the client in the clear
however thoroughly the rendering hides it. The redaction protects a log line and nothing else, and it
is easy to mistake for protection precisely because it is thorough.

**What the legacy did, so the departure is visible.** Any signed-on operator saw the full identifier.
`app/cbl/COACTVWC.cbl:L495-L504` composes it from its three parts into the twelve-character
`ACSTSSNI` field of `app/cpy-bms/COACTVW.CPY:L132`, and the update screen presents the three parts
separately at `app/cpy-bms/COACTUP.CPY:L168`, `L174` and `L180`. There is no role test and no masking
anywhere on that path. Masking by default is therefore a *divergence*, and it is licensed on the same
grounds as credential hashing: a regulated-data constraint compels it. The estate's wider gap - that
the card primary account number and the verification code have no field-level protection at all - is
recorded and deliberately not closed, because nothing requires it and closing it would be unrequested
work.

**Decision.** `api/AccountProtectedDataAdapter` is the only permitted source of those values. It
names a purpose - and only the two account transactions the estate declares are nameable, so a caller
with no account operation has no constant to pass - and an authorization that permits a reveal for the
administrator role or for an established ownership determination. Anything else is masked, at the same
widths, from length alone and never from content, retaining only the final four digits. Both the view
mask and the update mask expose the same final four so that neither is a route around the other. Two
refusals guard the reveal: a stored value the cipher does not recognise as sealed is refused, because
it reached the column by a route that bypassed the seal; and a revealed value that still carries the
envelope shape is refused, because the cipher returned its input.

**Ownership is a caller-asserted flag, not an invented model.** `app/cpy/CSUSR01Y.cpy` carries no
account linkage and no program in the estate checks one, so there is no legacy ownership relation to
reproduce. Inventing one would be a new business rule. The flag is therefore supplied by the caller
that established it, and the authorization carries no identifier of its own, so it can neither be
transmitted nor built from a client-echoed value.

*Cited by:* `api/AccountProtectedDataAdapter.java`, `api/dto/AccountViewResponse.java`,
`api/dto/AccountUpdateResponse.java`, `domain/Customer.java`.

---

### DL-136 - A version conflict the persistence provider detects answers the same conflict the module's own detection answers, and three type families are named because no narrower cover exists

**Context.** The account and card entities carry a version attribute, and the provider enforces it at
flush time rather than module code doing so. Only the module's own conflict carrier was mapped to a
conflict status, so a provider-raised conflict fell through to the terminal handler and became a server
error carrying the abend literal. That told a client the server had broken when in fact its screen was
merely stale: wrong status, wrong text, and a condition the legacy system had a specific message for.

**Decision.** A single arm answers all three shapes with the conflict status and the verbatim legacy
record-changed text, so a client cannot tell which layer noticed the staleness. That is the point: the
legacy write paths detected it by re-reading the record and comparing it against the image they had
presented, and answered with one text that does not vary by entity.

**Why three declarations and not one.** The lattice was computed rather than assumed. None of the three
covers another. Their nearest common ancestor is the framework's general runtime failure type, which the
advice deliberately refuses to declare because a handler at that level would sit between the named
carriers and the terminal handler for no purpose. One supertype does cover two of the three - the
specification's general persistence failure - and it is rejected because it also covers a missing
entity, a rolled-back commit and an empty result, none of which is a conflict; declaring it would map
all of them onto the record-changed text. The third of the three descends from that supertype rather
than from the specification's optimistic type, so naming the other two does not reach it.

**The entity is named on the diagnostic channel only.** Each family carries it differently - as a
persistent class name, as an entity name, or as the entity instance - so each is asked in its own terms,
with a placeholder when a shape carries none. Nothing there is reflective: every accessor named is
declared on the type being asked. None of it reaches the response body, and the diagnostic naming is
total, so it can never change what a client sees.

**Pessimistic lock-acquisition failures are deliberately excluded.** They are a different condition
with a different legacy message, and no write path in this module produces one; mapping them
speculatively would put text on a response for a state the module cannot reach. The exclusion is
asserted so it cannot be quietly reversed.

**What makes the fix real rather than apparent.** Invoking the arm proves its body is right. Because a
terminal handler exists, every failure is handled by *something*, so only resolution proves the
container would reach this arm - which is the original defect restated. The test therefore emulates
most-specific-match resolution and requires all five concrete shapes to resolve here, and a separate
integration test drives a genuine version conflict against a real server and hands the *actually
thrown* exception to a real handler, because a unit test cannot establish that the provider raises one
of the declared types at all.

*Cited by:* `api/GlobalExceptionHandler.java`, `domain/Account.java`, `domain/Card.java`,
`ProviderOptimisticLockConflictIT`.

---

### DL-137 - The cryptography package that is not part of the Jakarta rename stays, and it is recorded here so a later audit does not correct it

**Context.** The module is held to Jakarta EE package names throughout, and an audit for the older
namespace returns twelve matches in the token provider and the field codec.

**Decision.** They stay, and nothing needs changing. Those twelve are fully qualified references to the
JDK's own cryptography and key-specification packages, which were never part of the Jakarta rename and
have no Jakarta equivalent. There are zero import statements in that namespace anywhere in the module,
so the rule as written is satisfied literally as well as in substance. This entry exists because the
raw count is alarming and the correct response to it is to do nothing.

*Cited by:* `config/JwtTokenProvider.java`, `util/SensitiveFieldCodec.java`.

---
### DL-138 - The sign-on transaction is delivered with its four subtle behaviours intact, and each one would have compiled cleanly if got wrong

**Context.** The sign-on screen is transaction `CC00`, `app/cbl/COSGN00C.cbl`, and it is the first
operation in the module to reach a repository. Four of its behaviours are not visible from the shape of
the program and each has a plausible wrong answer.

**Both fields are folded to upper case, not just the identifier.** Line 130 folds the identifier and
line 134 folds the *secret*, and the comparison at line 223 is against the folded secret. A lower-case
secret therefore authenticates on the mainframe, and it has to authenticate here. Folding uses the
estate's ASCII-only fold rather than the locale-sensitive intrinsic, for the reason recorded against
every other fold: the intrinsic transforms characters a fixed-width field cannot hold.

**The failed comparison leaves the error flag lowered.** Every other rejection moves the flag; the
wrong-secret branch at lines 240 to 245 does not, and neither does the exit key at lines 88 to 90. The
flag guards only whether the credential read is attempted, so omitting it changes nothing on that turn -
but it is externally visible state, and the response contract's own documentation already recorded both
`false` cases before this service existed. They are reproduced, and the flag is carried as an explicit
value rather than derived from whether a message is present.

**The catch-all arm is narrowed to the one cause that remains reachable.** The legacy arm covered every
response code other than success and not-found, most of which were transport-level failures that are now
raised as exceptions rather than returned as codes. What remains is a stored user type outside the
declared two, and admitting an operator whose role cannot be resolved would be the one genuinely unsafe
outcome. A malformed stored digest cannot reach it, because the digest verifier answers false for
anything that is not a digest and the entity refuses a non-digest at construction.

**Every outcome answers success, rejections included.** All seven are screens the legacy program
composed and sent; the transaction completed on the mainframe in every case, so it completes here and
the outcome is read from the body exactly as an operator read it from the screen. Answering a rejected
sign-on with a client or server error would change an externally observable contract, and it would also
leak which rejection occurred to anything that inspects only the status line. Input wider than the map's
own fields is a different matter and is refused before the service runs.

**The credential comparison is a digest verification.** The legacy record holds an eight-character
cleartext password and compares it directly. Storing a cleartext credential is prohibited, so the
comparison is delegated to the digest service. Which credentials are admitted, and what the operator is
told when one is not, are unchanged; only the stored representation differs.

*Cited by:* `service/AuthenticationService.java`, `api/SignOnContractAdapter.java`,
`api/AuthController.java`.

---

### DL-139 - The session-issuing capability is declared where the boundary can name it, and the route constant moves to the controller so the layering is not inverted to accommodate either

**Context.** Delivering the sign-on route needed two things from the configuration package: the
component that mints a session, and the path constant the security rules exempt from authentication.
The layering permits configuration to depend on the boundary and forbids the reverse, so the controller
could name neither.

**Decision, the session.** `service/SessionTokenIssuer` declares the one capability the boundary
actually needs, and the token provider implements it. Moving key material downward or inverting the
layering were both rejected. The interface is deliberately one method wide: verifying a presented token,
reading a claim from one and reporting the configured lifetime all belong to the filter chain and to
configuration, and nothing at the boundary has a use for them. Keeping the surface at exactly the
issuing operation means the sign-on route cannot reach the verification path by accident, and a test of
that route can supply a stub without standing up key material.

**Decision, the route constant.** Its declaring site moves to the controller and the security
configuration reads it from there. The constant's own documentation already required that the mapping
and the exemption name one authority - a controller mapped to any other path is caught by the catch-all
authentication rule and fails closed, which is the safe direction for that mistake - and the only
question was which of the two declares it. The permitted dependency direction settles it. The
configuration field stays published because the security rules and their tests are written in terms of
it.

**The session travels in a response header, not in the body.** The sign-on screen contract declares
fifteen components and none of them is a credential, so a token in the body would add a sixteenth to a
contract frozen against the symbolic map. It is issued as a standard bearer credential in the response
header instead, and only for an admitted turn - the service's own invariant guarantees that an admitted
turn names both the operator and the resolved role, and that a turn which did not admit names neither,
so the presence of the header follows from the outcome rather than from a separate decision that could
drift.

*Cited by:* `service/SessionTokenIssuer.java`, `config/JwtTokenProvider.java`,
`api/AuthController.java`, `config/SecurityConfig.java`.

---

### DL-140 - Two structural audits were widened by one entitlement each, and both entitlements were earned by a test rather than granted

**Context.** Two of the guards written during this work search production sources for an accessor name
and assert the result is empty. Both fired on a later, legitimate reader, because a name-based search
cannot see what the accessor was read *from*.

**The regulated-value audit.** Four production sources are entitled to read a stored regulated value,
each for a stated reason: the entity declares them; the protected-data adapter is the gate and reading
them is its job; the account concurrency service folds them into a sealed, opaque proof from which
nothing can be read back out; and the customer record mapper writes the five-hundred-byte fixed-width
image, which *is* the legacy file format and genuinely carries the cleartext, so refusing to write them
would break the byte parity the batch tier is measured on.

**The echoed-member audit.** The sign-on projection reads two of the eleven echoed names, and reads
them off the *service's* result rather than off a wire record. Those two values are the identity the
credential master yielded, which is the opposite of an echoed claim: sign-on is the turn that
establishes identity, so there is nothing to echo it from. This is the second homonym the audit has hit,
the first being the menu catalog's own `userType()`.

**Why these are not simply widenings.** An entitlement that only adds a name is a hole. Each was
therefore paired with a test that establishes the property the name is standing in for. The sign-on
projection is proven to *construct* a wire record and to accept one in no position at all - checked in
five positional forms - so it has no receiver an echoed member could be read from. The regulated-value
audit is paired with a non-vacuity test proving the gate really does read all four accessors, and with a
test that every entitled name matches a real file so a rename cannot silently widen the entitlement into
a name that matches nothing.

**A forward guard was added rather than a present-tense one.** No production source constructs either
account response yet, so the exposure the regulated-value finding describes is *latent*: the components
exist on the wire contract and nothing fills them. The guard requires that any source which builds
either response also names the gate, so the first controller to assemble one has to obtain those values
from it. Its companion assertion records that no source builds one today, and that companion is expected
to fail - by design - when the first account controller arrives, at which point it is replaced by the
requirement the first guard already states.

*Cited by:* `AccountProtectedDataAdapterTest`, `ConversationStateAdapterTest`,
`api/SignOnContractAdapter.java`.

---

### DL-141 - What remains unbuilt is recorded here, because a delivered first operation is easy to mistake for a delivered surface

**Context.** This work delivered the sign-on operation and the first repository-backed path. It did not
deliver the rest of the online tier, and the boundary of what exists should not have to be inferred from
what does not.

**What exists.** Two classes carry a controller annotation: the failure adapter and the sign-on
controller. One service names a repository: the authentication service, over the credential master. The
published document therefore describes exactly one operation.

**What does not, and what each needs.** Sixteen further screen groups have their service or their
contract but no route: the two menus, account view and update, card list, detail and update,
transaction list, view and add, the report request, bill payment, and the four administrative user
operations. Ten repositories are unwired - account, card, customer, cross-reference, transaction, daily
transaction, category balance, disclosure group, transaction type and transaction category. The nine
batch job configurations the plan names are likewise not built.

**Four documentation deliverables the plan names are also absent**, and they are listed here for the
same reason: `docs/gate-evidence.md`, `docs/traceability-matrix.md` with its five hundred and
forty-four rows, `docs/architecture.md` and `docs/onboarding-guide.md`. None of them is referenced from
the documentation site's navigation, so nothing is broken by their absence, but one comment in the
metrics scrape configuration already points at the gate-evidence file as the place measured figures are
written up. That pointer is left standing rather than removed, because the file is intended to exist and
the comment states where its content belongs.

**Two obligations attach to each one when it is built**, and both are already enforced rather than
merely written down. Any source assembling an account response must obtain the regulated components from
the protected-data gate, which the forward guard of DL-140 requires. And every operation that reads a
record by a caller-supplied key must establish that the caller is entitled to it. For sign-on that
second obligation holds by construction rather than by an ownership model - the key of the record read
*is* the identity being asserted, and it is admitted only if the secret stored under that same key
verifies - and it is asserted as an interaction, so a later unkeyed read such as a list, a projection or
a scan fails rather than quietly returning something the caller never proved a claim to. No other
operation can rely on that argument, because no other operation's key is the caller's own identity.

*Cited by:* `api/AuthController.java`, `service/AuthenticationService.java`,
`AccountProtectedDataAdapterTest`.

---

### DL-130 - The three cloud clients are aimed from this module's own namespace by customizing them, because publishing clients of our own would seize settings we have no position on

**Context.** Two of the six key paths the migration plan mandates under this module's own prefix - the
region and the optional endpoint redirection - were bound and validated and then read by nothing. The
configuration class that owns the settings type said so in its own words, claiming both belonged
exclusively to the cloud integration's namespace, while the settings type it registers bound both. One of
those two statements had to be wrong, and it was the configuration class: a key that is bound while
nothing consumes it advertises an adjustability that does not exist, which is the very fault two earlier
entries record for the withdrawn queue keys.

**Decision.** The configuration class takes the settings type as a constructor parameter and contributes
one customizer per client - object store, queue, notifications. Each applies the configured region
unconditionally and the configured endpoint redirection only when one is configured. Nothing else is
touched: no credential is read, no bucket, queue or topic is created, no addressing style is overridden,
and no attempt count, time-out, backoff interval, pool size or capacity figure is set.

**Why customizing rather than publishing clients.** The plan asks this class to register the three
clients and, in its next breath, to prefer the starters' auto-configured clients and customize them
rather than hand-build. The second reading is the one that survives contact with the code. Publishing
clients here would take over credential resolution and the object store's path-style addressing from the
integration's own settings - values every profile document already states - leaving two sources of truth
for one setting, which is the hazard the settings type's own reasoning warns about. It would also silence
the customizers, since the auto-configuration backs off once a client bean exists. Customizing changes
only what this module has a position on and leaves the rest exactly where the profile documents put it.

**Why a customizer is authoritative, established by reading the library rather than assuming.** The
integration's builder configurer applies, in order, the credentials provider, the region, the endpoint,
the defaults mode and the protocol flags, and only then the per-service customizers. A customizer is
therefore the last writer of every property it sets, so the region and redirection this module states are
the ones the built client uses. Both are set to one definite value, so the outcome cannot depend on the
order customizers happen to run in.

**Why the redirection is conditional and the region is not.** An absent redirection is not a fault and
must not be defaulted: a client with no redirection resolves its region's own real endpoint, which is
precisely what a deployment wants, and the production guard refuses the redirection keys outright, so the
redirecting branch is unreachable under that profile. The region has no such absent case - every client
must resolve somewhere - and every profile derives the integration's region setting from this same key, so
the two namespaces cannot name different regions. The decision "is a redirection configured" is taken
once, in one helper, rather than three times at three builders where getting it wrong once means one
client silently addressing a real account.

**Nothing is registered that could shorten a submission.** No publishing template is contributed here.
The auto-configured one already carries the library's message conversion and observation wiring, and the
publisher supplies the two per-message properties that matter: the stable message group that preserves
append order, and a per-card deduplication identifier derived from the card's ordinal. That second one is
the subtlest hazard in the bridge - several of the seventeen cards are comment or delimiter cards with
byte-identical bodies, so content-based deduplication would discard the duplicates and shorten the job
stream into something a reader would accept - and it is closed twice, by that identifier and by the
bootstrap creating the queue with content-based deduplication off.

**Verified by running it, not by reading it.** The packaged artefact was started under the local profile
on a clone-index-derived port and against a clone-index-derived database, leaving the shared stack
untouched. It reported healthy with no warning and no error, logged the five resource settings and a
boolean for whether a redirection was configured - never the redirection itself, per the diagnostics
rule - and emitted one debug line per client showing that all three customizers ran and each applied the
redirection, naming the key rather than its value. The throwaway database was dropped afterwards. Unit
assertions additionally require the region on all three builders, the redirection on all three when
configured, and none on any when the value is absent or blank, each paired with the region assertion so
the absent cases cannot pass vacuously.

*Cited by:* `config/AwsConfig.java`. The settings it consumes are `config/AwsProperties.java`; the
profile documents that supply them are `application.yml`, `application-local.yml`,
`application-test.yml` and `application-prod.yml`; the production refusal of the redirection keys is
`config/ProductionConfigurationValidator.java`; and the resources it addresses are provisioned by
`localstack/init/01-create-aws-resources.sh`.

---

### DL-121 - A keyed read of a nonunique alternate index becomes a bounded, base-key-ordered finder, and the supporting indexes are deliberately left alone

**Context.** Two of the three legacy alternate indexes are declared `NONUNIQUEKEY` with `UPGRADE`:
`CARDAIX` over the card cluster on the account identifier at `KEYS(11 16)`, and `CXACAIX` over the
cross-reference cluster on the same identifier at `KEYS(11,25)`. Their Java counterparts were declared
as finders returning an unbounded, unordered list, on the reasoning that the alternate key admits
duplicates and that choosing one row out of several is a service-layer decision.

**What the source actually does, which settles it.** Every legacy consumer of either path issues a
single keyed `EXEC CICS READ`, never a browse: the bill-payment, transaction-add and account-view
programs against the cross-reference path, and the card-detail program against the card path. A keyed
read of a duplicate-bearing alternate index returns exactly one record, and which one is defined - the
first in ascending *base*-key order. So the legacy behaviour is not "every match, and the caller
decides"; it is "one match, and the structure decides which".

**Why leaving that to the service layer was wrong rather than merely lax.** Handing a caller an
unordered list to take the head of makes the result depend on plan shape, on insertion history and on
whether a vacuum has run. Nothing in a relational query guarantees first-row identity or any ordering
among duplicates without an `ORDER BY`. The legacy read is deterministic; the translation was not, in
exactly the case where determinism is the contract. It also duplicated the same head-of-list decision
into every future caller, where each one could get it wrong independently.

**Decision.** Both finders become bounded and explicitly base-key ordered, and their names say so:
`findFirstByCardAcctIdOrderByCardNumAsc` returning `Optional<Card>`, and
`findFirstByXrefAcctIdOrderByXrefCardNumAsc` returning `Optional<CardCrossReference>`. The empty
`Optional` is the analogue of the legacy not-found response, so no exception is raised at this layer.
A single-valued *unbounded* derived query was rejected: it raises an incorrect-result-size failure the
moment a second row exists, which is a failure the legacy system cannot produce.

**The card repository keeps its paged overload, and the cross-reference repository still has none.**
`Page<Card> findByCardAcctId(String, Pageable)` is the browse translation the migration plan names
literally, and it stays - unchanged in name, shape and caller-supplied size and sort - for genuine
browse consumers. The cross-reference path has no legacy browse at all, so it gains no paged form; the
card-list screen browses the *base* cluster and filters by account after the read, so it uses the
inherited paged `findAll` rather than either method.

**The two supporting indexes are deliberately not widened.** Making
`idx_card_cross_reference_xref_acct_id` a composite over the account identifier and the card number
would let the ordering be satisfied from the index instead of by sorting the handful of rows the
account owns. It was declined: the estate declares exactly three alternate indexes and the delivered
migration emits exactly three B-tree indexes to match, so changing one into a composite alters a
delivered migration for a plan-shape gain that no measured baseline asks for. The same reasoning
applies to `idx_card_card_acct_id`.

*Cited by:* `repository/CardRepository.java`, `repository/CardCrossReferenceRepository.java`. The index
inventory this preserves is `V2__create_indexes.sql`.

---

### DL-122 - The report range is read a slice at a time, and a redundant pre-bound is what lets the timestamp index constrain both ends

**Context.** The batch report's selection reproduces a sort specification that types the processing
*date* as ten characters at one-based offset 305 over a 26-character column, and filters inclusively
between two ten-character parameters. DL-era reasoning had already established the asymmetric
predicate that makes that faithful - a bare column on the lower bound, a ten-character `SUBSTRING` on
the upper - because comparing the full 26-character value against a ten-character end date would drop
every transaction processed *on* the end date. Two consequences of that shape were left unaddressed.

**First consequence: the range was materialised whole.** The bounds come from an operator-supplied job
parameter and the table is append-only - the posting run, the interest run and the online add path only
ever add rows. The number of rows a range selects is therefore unbounded in principle and grows for the
life of the deployment, so a `List` return made the reporting job's memory a function of accumulated
history and of how wide a range somebody typed.

**Decision on the first.** The method returns a `Slice` and takes a `Pageable`. A slice rather than a
page because a page carries a total count, which costs a second aggregate over the same range on every
fetch and which the report has no use for: it breaks its pages and its totals from the rows themselves,
line by line. The ordering gains a second term - the transaction identifier - because card number is
not unique across transactions and an ordering on it alone lets a row be returned twice or skipped as
the reader advances. That is faithful rather than additive: the legacy sort declares one key and no
`EQUALS` option, so it guarantees nothing about the relative order of records sharing a card number,
and any total order refining the declared key is admissible. The declared ordering sits in the query
text, so a sort carried on the pageable is appended after it and can only refine an already total
order.

**Second consequence: the upper bound could not reach the index.** A predicate over a *function* of a
column cannot bound an index built on the column, so the authoritative `SUBSTRING` comparison left the
index entered at the start date and read to the end of the table, with every later row fetched,
discarded and then sorted. The plan was measured rather than assumed: without a pre-bound the engine
chooses a sequential scan and carries both predicates as filters.

**Decision on the second.** A third, redundant predicate compares the bare column against the end date
concatenated with sixteen nines. Its right-hand side mentions no column, so it is evaluated once and
used as the index's upper bound; the measured plan becomes an index scan whose index condition carries
*both* ends, with the ten-character comparison retained as the filter that decides membership.

**The pre-bound is never the authority, and its safety is proved from the layout rather than assumed.**
It only has to be wide enough never to exclude a row the authoritative predicate keeps. A populated
processing timestamp is a ten-character date, a separating space, then a time of day, so its eleventh
character is a space; an unprocessed transaction is blank throughout. Under byte ordering the
comparison is decided at the first differing character: equal date prefixes hand the decision to the
eleventh character, and a space is below the digit nine. Under a language-aware collation, which weighs
digits ahead of spaces and punctuation, the pre-bound contributes the date's digits followed by sixteen
nines while a stored value contributes the same digits followed by the time's, of which the first is
the tens digit of an hour and so at most two. The pre-bound is the greater value either way. Both
orderings were checked because the delivered stack pins one of them and the test containers do not: the
compose database is initialised to byte ordering on purpose, so that sorted output can be compared byte
for byte against the legacy baselines.

**The invariant this rests on is written into the method, because a writer could break it silently.**
A stored processing timestamp is either blank throughout or carries a space in its eleventh character.
Widening the filler, or replacing it with a character a language-aware collation ignores, breaks the
second argument; shortening it below the sixteen characters that follow the date prefix breaks the
first. The neighbouring card-number invariant is recorded in the same place for the same reason: the
report's single ascending ordering is faithful to a sort that types those bytes as zoned decimal *only*
while every stored card number is sixteen zero-padded unsigned digits, and a shorter or signed value
would reorder the report without breaking anything a compiler or an unwitting test would notice.

**A deviation from this file's own generation brief, recorded rather than smoothed over.** The brief
for the transaction repository fixed the range query's return type as a list and prohibited a paged
overload. The migration plan fixes neither, and the review that reported both consequences above
governs the point, so the return type changed. The brief's *countable* constraint was honoured
literally: the interface still declares exactly two methods, because the pre-bound is derived inside
the query text instead of becoming a third parameter that every caller would have to compose
correctly.

*Cited by:* `repository/TransactionRepository.java`. The index it now bounds on both sides is
`idx_transaction_tran_proc_ts` in `V2__create_indexes.sql`.

---

### DL-123 - The first failed validation ends the report-request turn, and the decision to accumulate several is withdrawn

**Context.** The report-request translation collected more than one field failure in a turn. The
reasoning recorded at the time was that the six independent range tests are written as six separate
`IF` statements rather than as one evaluation, so each ought to be able to report its own field, and
that the two-state field contract - not supplied, versus supplied wrongly - needed several entries to
be worth having. A gate was kept between *stages* so that the date-validation subprogram was never
handed a date assembled from a part already faulted, and that gate was believed to be the whole of the
fidelity requirement.

**What the source actually does.** Every failure site performs the send paragraph. The send paragraph
ends with `GO TO RETURN-TO-CICS`. The return paragraph issues `EXEC CICS RETURN`. So the task **ends**
at the first failure: the paragraph that performed the send never resumes, and everything sequenced
after that `PERFORM` is unreachable. In the operator-supplied arm that is a great deal of work - the
numeric normalisation of all six date parts, the five range tests after the first failing one, the
assembly of both ten-character dates, both subprogram calls, the four substitution slots, the
report-name assignment and the submission attempt. The earlier reading had the reachability boundary in
the wrong place: it is not between stages, it is at the first failure.

**Why the difference is observable and not merely structural.** Three things changed for a caller.
The response could carry field errors the legacy screen never emitted together. The echoed input was
mutated by a normalisation the legacy never performed on a turn it had already faulted, so a client
redisplaying the echo would show values the operator never typed. And the end-date validator could be
called after the start date had already failed, producing a second, derived failure on top of the real
one.

**Decision.** The send raises a turn-ended marker, and every site that could otherwise continue tests
it and returns. The six range tests return after faulting; the end-date subprogram call is reached only
when the start date was accepted; the operator-supplied arm returns after each stage that could have
faulted; the acknowledgement block and the whole card-emitting path are gated on the same marker.

**Why a marker and not an exception.** A jump out of a call stack has no Java equivalent, and an
exception was rejected because ending a turn is the *ordinary* outcome here - the successful
acknowledgement send ends the turn too. Modelling it as a throw would make every normal turn look like
a fault to every caller, every logger and every error-handling boundary. The marker is deliberately
separate from the error flag for the same reason: the acknowledgement raises the marker without raising
the flag, so folding them together would have made success indistinguishable from failure.

**What survives from the withdrawn decision.** The two-state field contract is untouched: a field is
still reported as MISSING or INVALID, with its own byte-exact text and its own cursor position. There
is simply at most one such report per turn, which is the legacy's own cardinality. The latch that keeps
the summary message and the cursor position on the *first* failure also survives, and is now
structurally redundant rather than load-bearing - kept because it states the invariant at the point
where it could otherwise be broken.

*Cited by:* `service/ReportRequestService.java`, and its covering suite
`ReportRequestServiceTest.TheFirstFailedValidationEndsTheTurn`, whose assertions check both halves of
the contract: that the reported failure is the one the legacy would have shown, and that the work the
legacy never reached did not happen.

---

### DL-124 - The production migration state is one exact point, not an upper bound, and the guard now refuses falling short as well as reaching too far

**Context.** Two guards hold the production database to the delivered schema: one over the migration
location and one over the version ceiling. Both were written against a single threat - that a merged
environment, an operator override or a co-activated overlay would let the two seed scripts reach
production, seeding fifty synthetic customer rows carrying regulated identity data and ten known
sign-on identities. Read that way, "at most version two" and "a location beneath the delivered one" are
both perfectly safe, and both were accepted.

**The failure that reading admits.** Production is not an upper bound; it is an exact state - the four
delivered scripts, resolved from the one canonical location, applied up to and including version two.
A ceiling of one satisfies "at most two" and applies only `V1__create_schema.sql`, so the three
alternate-index equivalents and the six foreign keys in `V2__create_indexes.sql` are never created. An
absent location list, a nested sub-path, a file-system descriptor or a prefix-less spelling each
resolve fewer than the four delivered scripts, with the same effect.

**Why nothing downstream would have caught it.** Hibernate is fixed at schema *validation*, and
validation inspects tables and columns. It does not inspect indexes and it does not inspect
constraints. A production deployment migrated to version one would start, pass validation, report
healthy, serve every request - and run every access path the module was measured against as an
unindexed scan with no referential integrity behind any of it. There is no later gate: the seeded-database
refusal callback answers a different question, and the coverage and contract suites run against a
database migrated by the test profile.

**Decision.** Both guards now require an exact state under production. The ceiling must parse to
exactly version two: a higher ceiling, a lower one, the seeding marker, any predefined marker, an
unreadable value and an absent value are all refused. The location list, after blank and `null` entries
are discarded, must be exactly the one canonical descriptor: an empty or absent list, an additional
location beside it, a nested sub-path beneath it, a file-system descriptor addressing the same
directory and a prefix-less spelling are all refused.

**Two deliberate tolerances, so the guard refuses wrong configuration rather than untidy
configuration.** Blank and `null` list entries are ignored before the comparison, because a
comma-separated property list frequently produces one and an empty entry addresses nothing. The ceiling
is compared as a *parsed* version rather than as text, so a padded or differently spelled spelling of
the same version is accepted while a different version is not.

**The completion half is deliberately left permissive, and the asymmetry is the point.** For local and
test the resolver still appends the canonical location when the bound list does not already resolve it,
and it still recognises every spelling of the directory when deciding whether it is already there. That
predicate's job is to avoid appending a duplicate, not to constrain anything, so tightening it would
make a legitimate local configuration fail for no benefit. The two halves now answer two different
questions, which is why the strict comparison is written separately rather than by narrowing the
existing predicate.

**Three tests that asserted the old leniency were re-aimed rather than deleted.** They had encoded the
four near-miss location spellings as acceptable, a ceiling of `1.1` as acceptable, and an absent
location list under production as resolving to nothing. Each now asserts the refusal, and each carries
the reason in its own comment so the change is not mistaken for a tightening without cause. Two new
cases were added for the state that was previously reachable: a ceiling of one, and a list that
addresses nothing in each of its four forms.

*Cited by:* `config/FlywayConfig.java` and `FlywayConfigTest`. The one-location-plus-ceiling arrangement
this enforces is DL-102, restated in DL-111 and DL-116, and the directory-split round trip it replaces
is DL-119.

---

### DL-125 - The user-security fixture keeps its geometry and loses its credential, and the absence is asserted rather than trusted

**Context.** The provisioning job carries its ten sign-on identities in stream as fifty-seven-character
cards, and the record layout pads each to eighty. That content was reproduced into a committed
fixture - eight hundred bytes, ten records - byte for byte, including the one shared eight-character
password literal every card carries. The reasoning was fidelity: the fixture is derived from the job
and reproducing it exactly is what makes it evidence.

**Why fidelity was the wrong test to apply here.** The requirement that no credential is hardcoded is
not satisfied by a credential being *faithful*. Reproducing the literal put a working, reusable secret
into version control in the most directly extractable form there is: a fixed offset in a fixed-width
file, identical on all ten records. The seed migration was already correct - it stores BCrypt digests
and never the literal - so the fixture was the only artefact in the module from which the value could
be lifted, and it undid what the seed had been careful about.

**Decision.** The credential window carries a fixed structural placeholder of exactly the same width.
Everything else is unchanged: the eight hundred bytes, the ten records, the eighty-byte stride, the
absent line terminator, the ten identifiers in the order the job writes them, both name fields, the
five-and-five role split and the blank filler from character fifty-seven to eighty. So the fixture is
still the record-geometry evidence it was created to be, and the mapper still reads a full-width slice
where the layout says one is.

**Why substitution rather than deletion, which was the other option.** Deleting the fixture removes
today's copy of the literal and does nothing about tomorrow's. The substituted fixture carries two new
assertions instead: the credential window must equal the placeholder on every record, and the legacy
literal must not appear anywhere in the file in any case. Those turn the property into something the
build enforces, which deletion could not. Positively asserting the placeholder also matters more than it
looks: the previous assertions - non-blank, full width, identical across records - were all equally true
of the credential, so they could not have detected it.

**The literal is named once, in the test that forbids it.** Asserting an absence requires writing the
value down. It is declared as a single constant in the fixture suite with a comment stating that its
only purpose is to be forbidden, and nothing reads it as an authentication input. The seed's digests are
digests of the legacy literal, so the placeholder authenticates against nothing.

*Cited by:* `src/test/resources/fixtures/input/usrsec.txt` and
`FixtureContractTest.TheDerivedCredentialFixture`. The seeded digests this leaves untouched are
`V4__seed_user_security.sql`.

---

### DL-126 - A cross-reference row renders no value at all, because a partial redaction is an assurance rather than a control

**Context.** The cross-reference entity withheld its card number from the diagnostic rendering - that
value is a primary account number - while rendering the customer identifier and the account identifier
in full. The reasoning was that those two are internal keys naming no cardholder and revealing no
instrument, and that a diagnostic unable to say which account a row points at explains nothing.

**Why that reasoning does not hold for this entity in particular.** This row's entire purpose is to
resolve a card number to those two identifiers. A rendering that names them is therefore one join away
from the value it was careful not to print, and the join is against a table the same application
already has open. The withheld field and the rendered fields are not independent here; they are the two
sides of one mapping.

**The second problem is what a partial redaction communicates.** A rendering that visibly redacts one
field reads as a considered judgement that rendering the entity is safe - which is exactly the belief
that leads an author to put an instance into a log line, an exception message or an assertion. A
control that encourages the behaviour it exists to constrain is not a control.

**Decision.** All three fields render as the same placeholder. The type name and the field names are
kept, because identifying *what* reached a diagnostic is the one thing a rendering is legitimately for;
*which* row it was is not carried at all. Code that has to identify a specific row must name the value
it chose to disclose, deliberately, at that site. Every accessor still returns its value untouched, so
nothing stored, mapped or transmitted changes.

**The covering test asserted the opposite and was withdrawn with the exposure.** It positively required
both resolved identifiers to remain visible, so it would have failed this change and, left as it was,
would have held the exposure in place permanently. It now requires that no field value of either seeded
row appears in the rendering, in whole or in a six-character fragment, and adds the strongest available
statement of the property: two rows that agree on nothing must render identically, which cannot be true
of a rendering carrying anything row-specific.

**What this does not close.** The schema applies no field-level protection to the card number and the
migration introduces none, because the legacy design applies none and inventing one would be feature
expansion. That residual gap is decision D-14 and remains open. This entry settles only that an
unintended rendering is not the thing that widens it.

*Cited by:* `domain/CardCrossReference.java`, `CardCrossReferenceTest.DiagnosticRendering`. The
placeholder vocabulary is the one `domain/Card.java` and the DTO layer already use.

---

### DL-127 - One scrape target per deployment, because a candidate list makes a dead target permanent and a live pair doubles the baseline

**Context.** The application scrape job listed two targets in one static configuration: the compose
service name for an application running as a container beside the stack, and the host-gateway address
for one running directly on the host. The intent was convenience - whichever address happens to be live
gets scraped, and every series carries the same job label either way.

**What that actually produces, in both of its two states.** The compose file in this module defines
postgres, localstack, jaeger, prometheus and grafana, and no application service. The container address
can therefore never resolve here, so it is a permanently failed target: the scrape-health view is
permanently red, which trains a reader to ignore the one view that says whether the performance gate has
data at all. And if an application container were later added and both addresses reached a process, both
series sets would carry the same job label and differ only by instance - so every sum-by aggregation the
provisioned dashboard is built from would count a process twice. Those aggregations are request rate,
batch throughput, heap used and heap committed: throughput and memory are exactly the two figures the
performance gate records. A doubled baseline is worse than a missing one, because it looks credible.

**Decision.** The application job carries exactly one target, and which address it is a deployment
choice made by editing this file rather than a list for Prometheus to try. The delivered target is the
host-gateway address, matching the run mode this module documents and the `extra_hosts` entry the
compose file maps onto the Prometheus service. Switching to a containerised application means
*replacing* that target, not adding to it, and the instruction to do so is written beside it.

**Two things deliberately not changed.** The job name stays `carddemo-app`, because every panel in the
provisioned dashboard selects on it and renaming it empties all of them while the stack still reports
healthy. And the loopback address remains explicitly excluded, since inside the Prometheus container it
resolves to Prometheus itself - a scrape that succeeds at the transport level and yields nothing, which
surfaces only as an empty gate write-up.

**Verified by running it, not by reading it.** A throwaway Prometheus was started on a
clone-index-derived port against this file, with the application running on the host, and the shared
stack was left untouched. Its target API reported exactly one target in the application job, up;
`count(up{job="carddemo-app"})` and `count(count by (instance) (up{job="carddemo-app"}))` both returned
one, which is the property that makes the job-level sums single-counted; and the heap and per-endpoint
aggregations the dashboard uses each resolved to a single credible figure. Both throwaway containers were
removed afterwards.

*Cited by:* `config/prometheus/prometheus.yml`. The job selector it preserves is
`config/grafana/dashboards/carddemo-overview.json`; the gateway mapping it relies on is the Prometheus
service's `extra_hosts` entry in `docker-compose.yml`.

---

### DL-128 - Eighteen artefacts published ahead of their processing position are absorbed and reviewed rather than reset, because the alternative destroys mandated deliverables

**Context.** A checkpoint review found that eighteen paths outside its declared processed range had been
modified by the same commit that delivered the range: sixteen test classes, the provisioned Grafana
dashboard, and this log. Its finding is correct as stated - those contents were not covered by that
review's file-by-file pass - and the resolution it suggested was to move them to their owning checkpoint
or reset them from the branch before publication.

**Two constraints decide what "resolving" it can mean here, and they point the same way.** The
publication contract this work runs under prohibits history-altering git operations outright - no
rebase, no reset, no force - in the repository and in every submodule, so a reset is not available. And
the migration plan mandates each of the eighteen by pattern: the test tree, the observability
configuration directory, and this document are all named as deliverables to create. So the only reset-like
action available - deleting them in a further commit - would delete mandated artefacts.

**What deleting them would actually cost, stated concretely rather than as a worry.** Fifteen of the
sixteen test classes are the covering suites of production files *inside* the reviewed range: the
exception handler, four screen contracts, the reject-record writer, the AWS properties holder, the
entity mapping inventory, the transaction entity, both concurrency-token services, the menu and
report-request services, the named fixtures, and two record mappers. They contribute 1,086 executing
assertions. Removing them would drop coverage that the enforced floor depends on, and would remove the
verification of work the same review passed. This log is cited by forty-seven production sources by
decision number, so deleting it would break every one of those citations - including the citations three
of this session's own fixes add. The dashboard is the artefact the metrics job's own topology decision is
verified against, and it is mounted by the compose stack.

**Decision.** The eighteen stay, and the review's underlying concern - unreviewed content on the branch
- is answered by reviewing them here rather than by removing them. Every one was read and audited in
this session: each Java file carries the exact fourteen-line licence header, none imports from the
pre-Jakarta namespace, none uses a wildcard or star import, none carries a warning suppression, none
contains a placeholder, a deferred-work marker or a hardcoded credential, and all sixteen execute in the
suite with no failure, no error and nothing skipped. Reflection appears in four of them and is
legitimate and in scope: it is used to assert record components, a handler's declared methods and the
constructors of static-only utility classes, and the unsafe-code audit is explicitly scoped to
production sources, where the count remains zero. The dashboard parses as JSON, declares eleven panels
and is wired by the provisioning provider the compose stack mounts.

**Two of the eighteen were changed again in this session, deliberately.** The report-request suite gained
the eight cases that hold the corrected turn-termination contract, and the named-fixture suite gained the
two that forbid the credential literal. Both are the covering suites of production files fixed in this
same pass, and a fix without its covering assertions would be the weaker outcome of the two available.

**What is not claimed.** This does not make the eighteen part of the range that review covered, and it
does not overturn the finding. It records that the artefacts are mandated, that the suggested remedy is
unavailable and its available approximation destructive, and that the content has now been reviewed and
is owned. A reviewer re-checking this should expect the paths to still be present.

*Cited by:* nothing in code - this entry exists so the disposition is on the record rather than inferred
from a diff.

---

### DL-142 - The screen services keep the communication-area record the estate hands them, and every one of the twenty-seven upward edges is named in the guard rather than tolerated by it

**Context.** Three guards state the same boundary from three angles: `PackageLayeringTest` forbids an
import edge from the service package to the transport package, `ConversationStateAdapterTest` forbids any
source outside the API and configuration packages from naming the wire record at all, and a third
assertion inside it forbids reading an echoed member. DL-132 and DL-133 established that boundary and
narrowed the carried state from sixteen components to five; DL-134 recorded the first exemption to it as
an exact pair. The account, card, transaction and user-maintenance screen operations delivered since then
do not fit inside the five, and the reason is in the estate rather than in the Java.

**What the estate does.** The CICS screen programs read carried communication-area members as their own
input. `COACTVWC` moves `CDEMO-ACCT-ID` into the read key at line 691 and `CDEMO-CUST-ID` at line 708.
`COCRDSLC` moves `CDEMO-ACCT-ID` and `CDEMO-CARD-NUM` into its work area at lines 342 and 343, tests both
for zero at lines 462 and 468, and branches on `CDEMO-LAST-MAPSET` at lines 505 and 527 to decide whether
the turn arrived from the card list. `COBIL00C` takes its nominated account from a carried field at line
118. A five-field conversation state models the two transaction identifiers, the two program names and
the entry mode, and none of those members. A service handed only a five-field state therefore cannot
reproduce the programs, so the choice was between the boundary and the parity mandate.

**The decision.** Parity wins, and the deviation is named rather than tolerated. `LICENSED_UPWARD_EDGES`
changes from one edge to a table of twenty-seven, spanning eleven classes and naming each edge as a
class-and-type pair, and `ENROLLED_SCREEN_SERVICE_FILE_NAMES` names the ten screen services entitled to
hold the wire record. One of the twenty-seven is a design choice rather than a recorded deviation - the
field-error translation service names the decorator because the decorator is the estate's own error
contract - and the other twenty-six exist because the operations that carry them exist.

**Why this is not a hole.** A table that only adds names would be one, so three properties were added
with it. The licensed set is asserted to be *exactly* the set the sources contain, so an unlisted edge
still fails and a listed edge that no longer exists fails too - a service later reduced to a five-field
state cannot leave a dead licence behind for an unrelated file to inherit. Every enrolled file is
asserted to really name the record, for the same reason. And the property that actually protects
authorization was tightened rather than relaxed: the two identity members may be *carried* but never
*compared*, which is asserted line by line across the ten services against a list of comparison tokens,
with a floor on the number of carried reads found so the assertion cannot pass by finding nothing. The
echoed-claim accessor still has no production call site anywhere, unchanged.

**What is not claimed.** This does not reinstate the sixteen-component state as the service tier's
input, and it does not make the wire record acceptable in the batch, repository, domain or utility
tiers, where the guards still admit nothing at all. A service that needs only routing still takes the
five-field state: of the thirty-three classes in the service package, ten are enrolled here, the menu,
navigation and report-request services take the five-field state and name no transport record, and the
remaining nineteen name neither.

*Cited by:* `PackageLayeringTest`, `ConversationStateAdapterTest`.

---

### DL-143 - The account-update screen reads the stored regulated values because the program it reproduces displays them, and the residual exposure is recorded rather than masked away

**Context.** DL-135 masked the regulated components of the two account screens by default and revealed
them only under a named purpose and an authorization, which it recorded as a documented departure from
what the legacy showed. DL-140 then added a forward guard requiring that any source assembling either
account response also names that gate, and said in terms that its companion assertion - that no source
assembles one yet - was expected to fail by design when the first assembler arrived. It has arrived.

**What the estate does.** `COACTUPC` reads the stored national identifier at line 3854, moves its three
positions onto the output map at lines 2829 to 2831 with no authorization test of any kind, and compares
the stored value again in the before-image check at line 4171 that decides whether anything changed. The
government-issued identifier, the birth date and the electronic-funds account identifier follow the same
two paths. The service reproducing it performs those same two reads and no others.

**Why the gate could not simply be called.** The gate needs an authorization naming the operation and
either the administrator role or an established ownership determination. The service has no authenticated
principal: the only user type reaching it is the client-echoed one, and authorizing from that is exactly
what DL-133 and the echoed-member audit exist to prevent. Constructing an authorization from an echoed
claim would have satisfied this guard by defeating another, which is worse than the exposure it would
have papered over.

**The decision.** The service is named in the entitlement table with its two read sites and their COBOL
citations, and named as the single licensed assembler of a gated response. Every other source must still
name the gate, so the account view screen - which has no assembler at all - keeps the forward guard
intact, and a second assembler of either response still fails.

**The residual exposure, stated plainly.** The update screen publishes the four values in the clear to
any caller entitled to run the transaction, exactly as the legacy screen did. That is parity, not
protection, and the role-sensitive mask DL-135 introduced begins to apply at the boundary that holds the
authenticated principal. Two properties limit the exposure now:
the values leave storage only through their own field binding, so no envelope reaches a payload and
neither identifier is revealed under the other's key; and the licensed assembler is asserted to really
assemble a response, so the licence cannot sit dead and shelter the next assembler. Both are checked by
a test rather than taken on the word of this entry.

*Cited by:* `AccountProtectedDataAdapterTest`, `service/AccountUpdateService.java`.

---

*This log is authored alongside the target module and is never edited by the code that cites it. A
citation is a pointer into this document; the reasoning lives here in one place so that it cannot
drift between the files that depend on it.*
