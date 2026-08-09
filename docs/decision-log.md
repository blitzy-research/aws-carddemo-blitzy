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
| :--------- | :---- |
| Analysed commit (SHA) | `7756d895ffeb65f7ea72aaa609e356d9899afcec` |
| Upstream release stamp | `CardDemo_v1.0-15-g27d6c6f-68`, dated 2022-07-19 |
| Members carrying that stamp | **78** legacy members under `app/` — 25 programs, 25 copybooks, 26 job members, 2 cataloged procedures |
| Legacy estate location | `app/` — read-only reference throughout |
| Target module | `carddemo-java/` — physically disjoint, self-contained |

The upstream release stamp is the trailer comment embedded in the legacy members of the estate, and
the count above is a measurement rather than a description: 78 members under `app/` carry exactly that
stamp. The two identifiers together are the only durable link between the target module and the
source it was derived from, because no COBOL, JCL, BMS, copybook or CSD text is copied into the
target. Traceability is by citation, never by transcription.

The stamp is not perfectly uniform, and the non-uniformity is itself recorded rather than smoothed
over: a small number of members under `app/` carry later stamps than the 78 — one copybook most
visibly, which is row 26 of the anomaly register — and the generated screen-map members carry a
different stamp again. The 78 is therefore the count of members carrying *this* stamp, not a claim
that the estate is stamped once.

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
the code can see what changed and why. Corrected entries place the current reading above the retained
historical text; an “Original entry” or superseded decision below that correction is context, not the
delivered state.

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

This is the flagship entry of the log, and it is a **deliberate, documented parity exception** — the one
place where the prohibition on hardcoded and cleartext credentials openly outranks byte-for-byte
behavioural faithfulness. Reproducing the cleartext comparison would have satisfied parity and violated
the credential requirement; the two could not both be honoured, so the tie-break rule does not apply and
a named exception is recorded instead of a silent departure.

**What does not change:** the sign-on outcome for every valid and invalid credential, the routing
split between the administrative and main menus driven by the user-type byte, and all seven
message literals the sign-on screen emits. No value from the legacy credential record is restated
anywhere in the module — the seed migration stores hashes, never the literal it hashed.

**How far the exception reaches.** The estate's provisioning job carries **ten** sign-on identities in
clear — five administrative and five standard, every one of them sharing a single password literal of the
same eight-character width. All ten are seeded, with their identifiers, names and user types reproduced
exactly, and every credential stored as a **BCrypt hash**. The seeding migration reaches the local and
test profiles only, so a production deployment migrates schema and indexes without inheriting a seeded
credential of any kind. **The delivered mechanism is a version ceiling, not a location split**: all four
migrations sit flat in `classpath:db/migration`, local and test migrate through V4, and production stops at
target 2 and additionally refuses a database whose history or contents show a seed was applied — recorded as
row D-47 of the conflict table in section 9. DL-127 reached the same guarantee through profile-scoped
locations; that mechanism was an intermediate implementation, is superseded, and its entry carries the
correction at its head. Cite D-47 for what the module does and DL-127 only for how the question was
previously answered.

**Nothing on this page reproduces a credential.** The field width and the fact that one literal is shared
across all ten identities are recorded because they are what make hashing necessary; the literal itself
is not written here, in the module, or in any committed configuration. The demonstration credentials that
appear in the repository's root README describe the **legacy** system and are deliberately not restated.

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

### D-12 — Credential storage: BCrypt digest format and sign-on path both delivered — CORRECTED

**Correction.** This entry was written while the sign-on path was outstanding, and its heading and body
said so. Both are now delivered and the entry's original text below overstated the gap; the original
wording is retained after this correction because it records what the boundary was, and the entry is
cited from source files that describe the same boundary. What is true now:

`service/AuthenticationService` performs the verifying comparison against the stored digest, reproducing
`app/cbl/COSGN00C.cbl` including its seven message texts and its administrator-or-user routing;
`api/AuthController` maps the single route reachable without a credential; `util/SessionTokenIssuer`
and `config/JwtTokenProvider` mint the bearer grant that carries the outcome. So requests **are**
authenticated against the credential column, and the obligation this entry placed on "whatever component
fills that gap" — write only a digest, never store or compare a cleartext credential — is discharged by
that service rather than merely stated here.

Two later decisions extend this one and are the current reading of the credential column. DL-146 records
that one hashing strength governs both the security configuration's encoder and the digest service, ending
a period in which the two produced different work factors. DL-147 records that the stored digest is one of
three fields folded into the fingerprint a bearer grant carries, so replacing a credential revokes every
session already issued to that identity at the next request rather than at the grant's expiry.

**Original entry, retained for the record:**

`SEC-USR-PWD PIC X(08)` in `app/cpy/CSUSR01Y.cpy` holds the credential as eight cleartext characters
at offset 48, and `app/cbl/COSGN00C.cbl` compares it directly against the entered value.
Reproducing that would satisfy parity and breach the no-cleartext-credential constraint at the same
time. **Decision:** the credential column is sized 60 to hold a BCrypt digest — never the legacy
width of 8, and never a cleartext value — and no component may store or compare a cleartext
credential.
**Status at the time of writing: the storage format, the encoder and the seed are delivered; the sign-on
path is not.** (Superseded by the correction above - the sign-on path is delivered.)
`service/CredentialDigestService` is delivered and is the only component that turns a credential into
a stored value: it wraps `BCryptPasswordEncoder`, produces a 60-character digest, exposes a verifying
comparison, and refuses at the persistence boundary any value that is not digest-shaped.
`config/SecurityConfig` is delivered and carries the filter chain and the authentication manager, and
`config/JwtTokenProvider` is delivered. `V4__seed_user_security.sql` delivers the ten local and test
identities, each credential an independently salted 60-character BCrypt digest at cost 12, so the
column holds digests and no cleartext value anywhere.

What was **not** delivered when this was written is the sign-on path that would consume them:
`service/AuthenticationService`, the translation of `COSGN00C`, and `api/AuthController` did not exist, so
no request was authenticated against the credential column. All three now exist - see the correction
above. This entry therefore records a partially met requirement, and the
boundary has moved since it was first written: the storage format, the encoder and the seed are
delivered controls, while the authenticating comparison is still absent. Whatever component fills that
gap inherits the obligation stated above — write only a digest, never store or compare a cleartext
credential — and `CredentialDigestService.requireDigest` already exists to enforce the first half of it.
*Embodied in:* `src/main/resources/db/migration/V1__create_schema.sql` (column shape),
`src/main/resources/db/migration/V4__seed_user_security.sql` (digests),
`service/CredentialDigestService.java` (encoder, verifying comparison and persistence guard),
`config/SecurityConfig.java`, `domain/UserSecurity.java`, `api/dto/SignOnRequest.java`.

*Also recorded as:* DL-001, DL-002, DL-003 and DL-004, which record the same position from the
other side. The digest-format invariant is delivered and enforced by the credential entity, and the
encoder, the verifying comparison and the persistence-boundary guard are delivered in
`service/CredentialDigestService`. What still belongs to the authentication and user-management
services is the sign-on path that calls them. The two framings — "not yet delivered" here and "a
scope boundary" in DL-003 — stated one fact about that milestone: no component yet authenticated a request
against the credential column, and the stored-shape invariant plus the persistence guard stood in for one
until it did. That has since happened, and the invariant and the guard remain in force alongside the
delivered path rather than being retired by it.

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
evaluated strictly left to right and is what decides which transactions are rejected — it is the
basis for reject reason **102**, so a reordering changes which transactions are rejected rather than
merely a reported figure.

Both are reproduced operand for operand. No expression in the module is simplified, reassociated
or factored. The corroborating fact is that the report program contains no `COMPUTE` statement at
all, so the reporting path introduces no arithmetic and must not acquire any.

*Cited by:* `util/ZonedDecimalCodecTest.java`.

*Also recorded as:* D-03 — the same decision, recorded independently under the other identifier
scheme. Both identifiers are cited from the module and both resolve here.

### DL-015 — There is no packed-decimal decoder, because no packed field is ever written

The construct mapping anticipated packed decimal. Every persisted monetary and rate field in this
estate is zoned decimal under display usage instead, so the target implements a zoned-decimal codec
handling the overpunched trailing-byte sign convention and implements no binary-coded-decimal decoder
at all.

Writing one would have been dead code, and its absence is recorded so that it does not read as an
omission.

**A specification claim is corrected here rather than repeated.** The plan states that packed usage
"appears exactly once", on a screen work field. A direct scan of the estate at this checkout returns
**nine** packed declaration sites in the program tree and **none at all** in the copybook tree. All
nine are working-storage scratch fields — counters, an index-style length field, three intermediate
arithmetic fields, an absolute-time field, a group item and one screen work field — and not one of
them participates in a persisted record. The token itself appears ten times, the tenth being a
comment rather than a declaration, which is the likeliest origin of an undercount in either
direction.

The claim that is actually true, and actually load-bearing, is the narrower one: **no persisted
monetary or rate field uses packed decimal.** That is what makes the decoder unnecessary. The count
does not change the decision; it is corrected so that a reader who greps the estate and finds nine
does not conclude the analysis missed eight persisted fields. `architecture.md` records the same
nine.

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
Truncation makes arithmetic non-associative, so operand order is contractual. Two expressions in the
estate carry that weight, and both are described here rather than transcribed.

The monthly-interest computation in `app/cbl/CBACT04C.cbl` (line 464) multiplies the category-balance
field by the disclosure-rate field **and only then** divides the product by 1200, storing into a
`PIC S9(09)V99` receiving field. Dividing the rate by 1200 first is algebraically identical in exact
arithmetic and numerically different, because it moves the truncation point.

The overlimit basis in `app/cbl/CBTRN02C.cbl` (line 403) evaluates the cycle-credit field minus the
cycle-debit field plus the daily-transaction amount **strictly left to right** into a `PIC S9(09)V99`
receiving field, and that value is what the overlimit test compares. It is the basis for reject
reason **102**, so a reordering changes *which transactions are rejected* — not merely a reported
figure.

**Decision:** no `COMPUTE` expression is algebraically rearranged, reassociated, factored or
simplified anywhere in the module. Corroborating fact: `app/cbl/CBTRN03C.cbl` contains no `COMPUTE`
statement at all, so the reporting path introduces no arithmetic and must not acquire any.
*Embodied in:* `service/InterestCalculationService.java`, `service/TransactionPostingService.java`,
`batch/step/InterestCalculationProcessor.java`, `batch/step/TransactionValidationProcessor.java`.

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

**Settlement, for the author of a golden fixture.** The consequence of the paragraph above is that
record-image parity has two different bounds depending on the layout, and which bound applies is a
property of the layout rather than a choice the fixture author makes. Stating it once, exhaustively,
so no golden fixture is generated against the wrong bound:

| Layout | Fixture | Filler in the fixture | Parity bound a round trip is asserted at |
| :----- | :------ | :-------------------- | :--------------------------------------- |
| Account, 300 B | `acctdata.txt` | 178 spaces | **whole record** — byte-identical, 50/50 |
| Card, 150 B | `carddata.txt` | 59 spaces | **whole record** — byte-identical, 50/50 |
| Customer, 500 B | `custdata.txt` | 168 spaces | **whole record** — byte-identical, 50/50 |
| Daily transaction, 350 B | `dailytran.txt` | 20 spaces | **whole record** — byte-identical, 300/300 |
| Card cross reference | `cardxref.txt` | none — the text stride is 36, not 50 | **whole 36-byte data record** — byte-identical, 50/50 (DL-193) |
| Transaction category balance, 50 B | `tcatbal.txt` | 22 × ASCII zero | **mapped prefix `[0, 28)`** |
| Disclosure group, 50 B | `discgrp.txt` | 28 × ASCII zero | **mapped prefix `[0, 22)`** |
| Transaction type, 60 B | `trantype.txt` | 8 × ASCII zero | **mapped prefix `[0, 52)`** |
| Transaction category, 60 B | `trancatg.txt` | 4 × ASCII zero | **mapped prefix `[0, 56)`** |
| Transaction, 350 B | — no fixture; the table is seeded empty | n/a | whole record against a constructed image |

**Why the four bounded layouts are bounded rather than changed.** Emitting ASCII zero for them would
make every one of the nine bounds a whole-record bound, which is superficially tidier and is not what
the estate says. Three reasons, in order of weight. First, the byte is genuinely undefined: `FILLER`
with no `VALUE` clause is uninitialised storage, and the two halves of the shipped sample data
disagree with each other, so "reproduce the fixture" is not a single instruction — it is one
instruction for the master files and the opposite one for the reference files. Second, the estate
never *creates* a record of any of the four bounded layouts: they are read by `CBACT03C`, `CBTRN02C`
and `CBTRN03C`, and the only write anywhere is the category-balance `REWRITE` in `CBACT04C`, which
carries the filler back out exactly as it read it in — a behaviour a constant emitted by a writer
cannot reproduce and a mapped-field entity cannot carry, since the filler is deliberately not a
column. Third and decisively, **none of the four bounded layouts is a gated output format.** Gate 1
gates four widths — the 430-byte reject record, the 80-byte statement record, the 100-byte HTML
statement record and the 133-byte report line — and all four are assembled from mapped fields by
`RejectRecordWriter`, `StatementTextTemplates`, `StatementHtmlTemplates` and `ReportLineFormatter`,
none of which places a reference-layout filler byte. The one production path that does emit a bounded
layout's image is the category-balance report job, whose 50-byte unload and sort records are
*internal* intermediates of that job: the artefact it publishes is the report line, built from the
mapped prefix. So the filler byte reaches no external contract, and a golden fixture generated at the
bounds in the table above cannot be wrong about one.

**What makes the divergence safe rather than merely tolerated.** It is asserted in both directions
rather than worked around. Each of the four mappers declares its emitted filler character as a named
constant, states the bound in its Javadoc together with the warning that a whole-record comparison
against its fixture will fail, and its specification asserts both that the mapped prefix is
byte-identical to the fixture and that the emitted filler character is *not* the fixture's. A future
edit that silently normalised either side therefore fails a test whose name says what it is
protecting. What is deliberately absent is a comparison that trims, masks or ignores the filler run:
that would hide the divergence permanently, which is the one outcome worse than having it.

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

### DL-028 — Two account-update fields carry no validation constraint: one a preserved anomaly, one a directive-driven divergence

The account-update program decorates thirty-nine fields for error display, and its comments state for
two of them — the middle name and the second address line — that no edits are coded. **The comment is
true for one field and false for the other, and the distinction was measured rather than assumed.**

- **The second address line is genuinely unedited.** Its edit-variable label assignment is commented out
  at `app/cbl/COACTUPC.cbl` L1614 and no edit is performed for it anywhere in the program. Carrying no
  constraint is therefore exactly what the legacy does — a faithful translation and a preserved anomaly.
- **The middle name is edited.** At `app/cbl/COACTUPC.cbl` L1568-L1574 the program moves the field into
  the alphabetic work area, sets the length to 25 and performs `1235-EDIT-ALPHA-OPT` through its exit,
  storing the resulting flags. The legacy would therefore reject a middle name carrying a digit. The
  screen-attribute comment at L3345 that says otherwise is itself a source defect, catalogued as such in
  the anomaly register.

The Java request DTO attaches **no validation constraints** to either field. For the second address line
that is parity. For the middle name it is **a divergence from the source's behaviour**, and it is recorded
as one rather than presented as parity: the migration directive is explicit and repeated that no
constraint of any kind may attach to either field, so the directive governs. A reader deciding whether to
add a constraint later needs to know which of the two situations they are in, because for the middle name
adding one would restore legacy behaviour rather than break it — and would still violate the directive.

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
A `REPLACING`-form inspection substitutes zero for every space in the option field, and because the
field is declared `PIC X(02)` with right justification a single-digit entry arrives already
right-aligned — so the substitution turns it into a zero-filled two-digit value. **Decision:**
reproduced as a named primitive rather than as an incidental `String.format`, so the behaviour is
separately testable.
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

### DL-207 — The interest run's end-of-file control break is unreachable, so the last account's balance is never posted — PRESERVED DEFECT

`app/cbl/CBACT04C.cbl` invokes `1050-UPDATE-ACCOUNT` from two places. The reachable one is the
key-change control break at line 196. The other is the `ELSE` arm at lines 219 to 221, which sits
inside the driver's read loop — a `PERFORM UNTIL` construct testing the end-of-file flag, and therefore
a test-*before* loop whose flag the read paragraph raises
itself at line 340, so the loop terminates before the arm can be taken. `END-OF-FILE` is declared
`PIC X(01) VALUE 'N'` at line 137 and is set nowhere else.

The consequence is asymmetric and is the whole point of this entry. `1300-COMPUTE-INTEREST` and
`1300-B-WRITE-TX` run *per row*, at lines 215 and 468, inside the loop; they owe nothing to the
control break. So for the run's final account in key order the interest **is** computed, the
synthesized transaction **is** written to the interest generation and the group's running total
**does** include it — while the account's `ACCT-CURR-BAL` never receives that total and neither
`ACCT-CURR-CYC-CREDIT` nor `ACCT-CURR-CYC-DEBIT` is zeroed.

**Decision:** reproduced exactly. The final group closes with
`InterestCalculationService.AccountControlBreak.WITHHELD_AT_END_OF_FILE`, which accrues the group,
writes every record it synthesizes, reports its total, and does not perform
`1050-UPDATE-ACCOUNT`. The withheld amount is logged so that an unmoved balance beside written
records is explainable rather than mysterious.

**Why this is not "correcting a data-loss defect".** An earlier delivery honoured the unreachable arm
on the reasoning that reproducing it would silently drop the last account's accrued interest. That
reasoning is rejected here for four independent reasons. The plan's tie-break (§0.10.5) states that
where faithful translation and idiomatic Java conflict, faithful wins and the divergence is recorded
here rather than resolved by taste; it names "preserving a no-op paragraph" as exactly this class of
case, and §0.7.6 item 14 sets the precedent by requiring the empty fee paragraph to stay empty even
though it is genuinely invoked. The plan's preservation boundary (§0.8.1) requires zero behavioural
regressions and forbids feature expansion, and posting a balance the legacy never posted is a
behavioural change in the money column. The interest is not in fact lost — the record exists in the
generation, so the accrual is recoverable and observable. And the module's own expected-output
fixtures already encode the unposted figure: `src/test/resources/fixtures/expected/statement.txt`
line 10 reads `Current Balance    :000001945.87` for account `00000000050` and the HTML statement
agrees at record index 36, so honouring the arm made production disagree with its own golden file.

*Embodied in:* `service/InterestCalculationService.java` (`AccountControlBreak`,
`withholdAccountRewrite`, the driver loop and the group boundary),
`batch/step/InterestCalculationProcessor.java` (`afterStep` closes the final group with the withheld
break, and the both-accumulators confirmation applies only to a group that was rewritten).
*Asserted by:* `service/InterestCalculationServiceTest` (nest
`1050-UPDATE-ACCOUNT`), `batch/InterestCalculationJobConfigIT`
(`postingThenOneAccrualPassTruncatesAndSkipsInTheSameRun`, which reads the amounts back from a real
PostgreSQL after a real posting-then-accrual chain).

### DL-247 — The control-flow census, measured at this checkout, and what each figure obliges

The entries above decide *how* each construct is translated. This entry records the measurements that
bound how much of each there is, because a reviewer checking whether the translation is exhaustive
needs the denominator, and because two of these figures are easy to mis-measure in a way that hides a
gap.

| Construct | Measured | What the figure obliges |
| :-------- | :------- | :---------------------- |
| `EVALUATE` statements | **111** | Each becomes a `switch` with clause order intact — DL-021 |
| `GO TO` statements | **134** total | Every one resolved against an in-program label; none dangles |
| — forward, to an exit label | **125** | Each becomes an early `return` |
| — backward, forming a loop | **9** | Each becomes an explicit loop; **6 of the 9** belong to the statement generator — DL-020 |
| `INSPECT` statements | **11** | Three distinct forms, two of them traps — DL-022, DL-023 |
| — `CONVERTING` | **7** | The alphabetic-or-space predicate and the ASCII-only fold |
| — `REPLACING` | **3** | Menu-option zero fill and the card-list selection bitmap |
| — `TALLYING` | **1** | The more-than-one-action-per-page count |
| `UNSTRING` statements | **0** | Nothing to translate; no delimiter or pointer semantics exist to reproduce |
| Internal `SORT` statements | **0** | All ordering is external, so comparators belong to jobs, not to programs |
| Internal `MERGE` statements | **0** | Same |
| Macro-copybook expansions | **39** | Collapse to 39 calls on one method — DL-032's structural sibling, recorded at D-32 |

**Two of these are easy to get wrong, and both would hide real work.** A naive token count of
`EVALUATE` returns 220 rather than 111, because the scope terminator carries the same word and a word
boundary sits either side of the hyphen; a reviewer who accepts 220 concludes the translation covers
half of what it does. And a naive token count of `REPLACING` returns 42 rather than 3, because 39 of
those occurrences are the macro copybook's substitution clause rather than a string operation — the
same 39 that D-32 collapses into one method. The two counts are therefore recorded here together, so
that the 42 is understood as 3 plus 39 rather than as a contradiction.

**Method.** Fixed-format comment lines are excluded by the column-7 indicator; only columns 8 to 72 are
read; the scope terminator is excluded from the `EVALUATE` count by an explicit negative lookbehind; and
every `GO TO` target is resolved against the Area-A labels of its own program, which is what allows the
forward and backward split to be stated rather than estimated.

### DL-248 — Screen dispatch becomes a route constant in the response body, never a server-side forward

**Decision.** Every program-to-program transfer in the online tier becomes a route constant the response
carries, and the client makes the next call. Nothing is forwarded inside the server.

**Legacy behaviour, measured.** The estate performs **25** transfer-control dispatches and re-arms the
next transaction on **19** of its **26** pseudo-conversational returns. It contains **zero** link
invocations — the verb that would have meant "call and come back" — so there is no legacy behaviour of
returning to a caller for the translation to preserve. Transfer of control in this estate is genuinely a
hand-off, not a call.

**Why the faithful answer is also the testable one.** Because the legacy transfer never returns, a route
constant in the response body reproduces its semantics exactly: control leaves the program, and the next
program starts fresh from the state it was handed. Server-side forwarding would be the *less* faithful
option as well as the harder one to test, since it would couple two endpoints into one request and make
neither independently exercisable.

**Consequence a reader must know.** An endpoint that returns a route has not performed the next step. A
test that asserts a route is asserting the hand-off; a test that wants the destination's behaviour must
make the second call, exactly as a terminal operator would have pressed the next key.

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

### D-34 — The middle name and the second address line carry no validation constraint
`app/cbl/COACTUPC.cbl` decorates both fields for error display and its comments say no edit is coded for
either. That is accurate for the second address line, whose label assignment is commented out at L1614
and which no paragraph edits; it is **not** accurate for the middle name, which is edited at L1568-L1574
by `1235-EDIT-ALPHA-OPT`. **Decision:** no validation constraint is attached to either field, in a DTO or
as a check constraint, because the migration directive prohibits one for both. For the second address line
that is parity; for the middle name it is a recorded divergence from legacy behaviour, not a claim that the
legacy accepts anything. DL-028 carries the measured evidence.
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

### DL-249 — One repository, one physically disjoint module, and a corrected base package

**Decision.** The target is a self-contained Maven module inside the existing repository, not a second
repository.

**The constraint being honoured.** COBOL source is not copied into the target. That constraint is about
*text*, not about repositories: it is satisfied by a module that contains no program, copybook, screen
map, job stream or resource-definition text of any kind, which is exactly what `carddemo-java/` is. The
module carries its own build manifest, its own wrapper, its own container definitions, its own
configuration, its own migrations and its own README, so it builds and validates without reference to any
mainframe asset.

**Why not a second repository.** A second repository would satisfy the same text constraint while
fragmenting the history and — the decisive cost — severing the traceability anchor. The whole
paragraph-level mapping is verifiable only because the estate and its translation sit at one checkout,
under one commit identifier, where a reviewer can read a citation and then read the member it cites. Two
repositories would make every row of that mapping a cross-repository claim resolvable only by
coordinating two revisions.

**A corrected base package, recorded because the misspelling is in circulation.** The base package is
**`com.carddemo`**. The prior delivery's documentation carries a misspelled variant with a dropped
letter. The build manifest's group coordinate and every source file use the correct spelling; the
misspelling is noted here so that a search against the prior documentation's spelling returning nothing
is understood as the documentation's defect rather than a missing package.

### DL-250 — Two deliverables sit outside the module, and neither placement is a design choice

**Decision.** Everything the target needs lives inside `carddemo-java/`, with exactly two categories of
exception, each forced by a platform that resolves files from a fixed location.

**The continuous-integration definition** lives at `.github/workflows/carddemo-java-ci.yml` because the
CI platform resolves workflow definitions only from that directory at the repository root. It scopes
itself back into the module with a working-directory default, so every command it runs is a command a
developer runs in the module.

**This documentation set** lives at the repository's documentation directory because the documentation
generator resolves its content directory there by convention and the service catalog publishes
documentation from the repository root. A documentation page inside the module would simply not be
published.

**Why the distinction matters rather than being pedantic.** "Self-contained module" is an acceptance
property, and two files outside the module boundary look like a violation of it. They are not: neither
file is *depended on* by the module — the module builds, tests and packages with both absent. They are
consumed by tooling that cannot look inside a subdirectory. Recording the reason here means the boundary
can be audited without the two exceptions reading as drift.

---

## 7. External interface contracts

The estate has three external contracts — fixed-width file output, the message-queue bridge, and
the batch-trigger card image. All three are reproduced; the entries below record where a security
requirement forced a controlled divergence inside them.

### DL-037 — Variable text in HTML statement output is escaped *(parity exception)* — **SUPERSEDED BY DL-209**

> **Superseded.** This parity exception was withdrawn. The claim below that escaping is "the identity
> function on the entire legitimate domain" is false for this estate: the seeded data carries twelve
> apostrophes, so escaping changed emitted bytes for real records and broke byte parity against the
> module's own hundred-byte oracle. The entry is retained unedited so the reversal is visible; the
> decision now in force is DL-209.


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

### DL-038 — The raw-markup line composer was removed, not merely documented — **PARTLY SUPERSEDED BY DL-209**

> **Still in force:** the single anything-goes helper is gone and the three named composers, one per
> legitimate emission site, are what production uses. **No longer true:** those composers no longer
> escape anything. Their remaining job is structural — each wraps its value in the paragraph tags its
> record is defined to carry — and the census that keeps the bare fitter out of the composing path is
> unchanged. See DL-209.


A single helper previously accepted a caller-supplied string and placed it into the output line as
raw markup. Documenting it as dangerous would have left the hazard in place, because the next
caller would still find and use it. It was therefore **removed** and replaced by three composers,
one per legitimate emission site, each of which escapes its own variable segments.

Removing a public helper is a larger change than annotating one, and it is recorded here because
that was the point: an unsafe sink that remains callable is an unsafe sink.

*Cited by:* `util/StatementHtmlTemplates.java`.

### DL-039 — Truncation to the record width never emits a partial character reference — **SUPERSEDED BY DL-209**

> **Superseded.** With nothing escaped there is no character reference for a boundary to split, so the
> rule has nothing to apply to. The blank-the-trailing-fragment refinement was itself a divergence — the
> legacy `MOVE` cuts at the record boundary and blanks nothing — and has been removed with the escaping
> it existed to repair. See DL-209.


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

### DL-260 — The online-to-batch bridge becomes a first-in-first-out queue, preserving three declared attributes

**Decision.** The estate's sole online-to-batch bridge becomes a publish to a first-in-first-out managed
queue, and three attributes the CICS resource definition declares are each mapped to a specific property
of that publish rather than approximated by it.

**Legacy behaviour, measured.** There is exactly **one** transient-data queue write in the entire estate
— the reporting program's job-submission path. It is the only mechanism by which the online tier causes
batch work to happen, which is why its properties are contract rather than implementation detail.

| Declared attribute | What it means | How the target preserves it |
| :----------------- | :------------ | :-------------------------- |
| `RECORDSIZE(80)` with `RECORDFORMAT(FIXED)` | fixed eighty-byte unblocked records | one message per card, each an eighty-character fixed-width payload; the width is asserted, not assumed |
| `DISPOSITION(MOD)` | append, so writes accumulate in order | a single message group, which is what makes ordering a guarantee rather than a probability |
| `ERROROPTION(IGNORE)` | a failed write is ignored, not raised | a **non-blocking publish whose failure logs and continues**; the caller is not aborted, and the exception type says so — see DL-044 |

**Why the third attribute is the one that needs recording.** The first two are shapes and a reviewer will
see them in the payload. The third is a *failure* semantic, and the idiomatic Java answer — propagate the
exception — is the wrong one here: the legacy screen reported that the write had failed and carried on,
so a caller that aborted would change observable behaviour on a path the estate deliberately made
survivable. Preserving it is faithful; it is also the only one of the three that a passing test suite
would not notice was missing.

**Consequence.** A publish failure is visible in the log and nowhere else. A submission is therefore
confirmed by draining the queue, not by the absence of an exception — which is how the contract is
exercised rather than self-certified.

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

### D-49 — Variable text in statement HTML is escaped, and the raw-markup sink was removed — CORRECTED, then **SUPERSEDED BY DL-209**

> **Superseded.** This entry corrected an earlier reading that recorded statement HTML as emitted
> unescaped. That earlier reading was right about the bytes, and DL-209 restores it on evidence: the
> module's own expected-output oracle carries the markup-significant characters raw, so escaping made
> production disagree with its own golden file. The removal of the raw-markup sink recorded here still
> stands; the escaping does not.


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

### DL-048 — Four endpoints are published and the same four in every profile, because a profile-varying management surface cannot be tested

The delivered exposure list is `health,info,metrics,prometheus` — **four** endpoints — and it is that
same list in the shared baseline and in each of the local, test and production overlays. Each overlay
restates it rather than inheriting it, which is deliberate: a future edit that widens the baseline then
cannot reach production by inheritance, and a test that resolves a management path resolves the one
production would.

Two of the four are resolved literally by another file in the module, so dropping either would break a
cross-file contract: the scrape endpoint is the target named in the metrics scrape configuration and read
by every dashboard panel, and the health endpoint is the container health-check target. The meter-name
endpoint is published alongside them because a scrape target without the ability to enumerate what is
being scraped makes a metric absence indistinguishable from a metric that was never registered.

**What is excluded is everything else, and it is excluded in every profile.** `env`, `configprops`,
`beans`, `flyway`, `mappings` and `loggers` are published by no profile and answer not-found. Exposure and
authentication are separate questions and this entry settles only the first: the four published endpoints
are authenticated as their profile requires, and narrowing authentication is not the same as widening
exposure.

> **Superseded reasoning.** This entry previously recorded that three endpoints were kept and the
> meter-name endpoint was dropped, available only through the local overlay. That is not the delivered
> configuration and was not accurate when written against these files: all four profiles include
> `metrics`, and `application-test.yml` says so in its own comment beside the list. The cross-file
> argument for the health and scrape endpoints is unchanged and is retained above; only the count and the
> claim of a per-profile difference are corrected.

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
| :--- | :------- | :--------- |
| D-43 | The same generation-data-group base is declared `LIMIT(5)` in `app/jcl/DEFGDGB.jcl` and `LIMIT(10)` in `app/jcl/REPTFILE.jcl` | Ten for the transaction-report base, as the later and more specific declaration; five remains the measured default for the other generation groups. `StagedGenerationStore` applies those depths to completed objects in the staging bucket, so the figures bound durable retained generations rather than local working files. |
| D-44 | One DD is declared `LRECL=80` in one `app/jcl/CREASTMT.JCL` step and `LRECL=100` in the next | One hundred, matching the `PIC X(100)` record the emitting program declares for the HTML stream. |
| D-45 | `app/jcl/TRANFILE.jcl` and `app/jcl/TRANIDX.jcl` both define an alternate index of the same name, over the same cluster, with the same key width and offset | One logical index described in two members, emitted exactly once. Emitting it twice fails on a duplicate name; renaming the second copy would leave a permanent redundant index behind. |
| D-46 | Duplicate step names — `STEP05R` twice in `app/jcl/TRANREPT.jcl`, `STEP05` twice in `app/jcl/DEFCUST.jcl` | Distinct target step names, with the original names recorded here so the mapping stays findable. |
| D-47 | Sample rows and sign-on identities must reach local and test but never production | All four migrations remain flat in `classpath:db/migration`. Local and test migrate through V4; production is fixed at target V2 and additionally refuses a database whose history or contents show that either seed was applied. |
| D-48 | Prior project documentation names test-plugin versions that the resolved build supersedes | The resolved versions govern. A version is recorded only after being read back out of an executed resolution. |

---

## 10. The source anomaly register

Thirty-one defects and oddities were identified in the legacy source. Every one is **documented, never
propagated, and never silently corrected where correcting it would alter a record layout or an external
contract**. The numbering is the one the module cites: eight source and test files refer to these rows by
number, so a row is never renumbered.

Where an anomaly participates in a record layout, the layout is preserved byte for byte and only
the Java-side spelling is corrected.

The migration plan named **fourteen** anomalies plus a fifteenth observation. All fifteen are present
below as rows 1 to 15 in the plan's own order, and rows 16 to 31 are the further defects this analysis
found at the same checkout. Row 15 is the fifteenth observation and is a **comment defect rather than a
code defect** — see the note following the table.

| # | Anomaly | Handling |
| :-- | :---------- | :--------- |
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
| 14 | `1400-COMPUTE-FEES` in `app/cbl/CBACT04C.cbl` (declared at line 518) contains a single comment marking the logic as not yet implemented, followed by an exit — yet it is genuinely invoked from the interest driver at line 216 | Preserved as an explicitly documented no-op: the Java method exists, is called, and does nothing. **No fee logic may be invented to fill it**: doing so would be feature expansion and would change interest-run output |
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
| 31 | The page-break test in `app/cbl/CBTRN03C.cbl` (line 282) takes the line counter modulo the page size and fires only when the counter lands exactly on a multiple of the page size. The write routine `1111-WRITE-REPORT-REC` never increments the counter; each caller does — a header block adds four, a page-total block two, an account-total block two, a detail record one, and the grand-total record nothing. An account-total block's increment of two can therefore step the counter straight over a multiple of the page size, and that page break is missed entirely | Reproduced faithfully, never repaired: the modulus is taken over every written record and the per-caller increments are kept as found, so a skipped break stays skipped. The counter, the accumulations and the break decision belong to the report service; `util/ReportLineFormatter` holds no state and publishes only the page-size constant. Related: account totals do not roll into the grand total (only page totals do) and no rule record follows the grand total |

**Row 15 is a comment defect, not a code defect,** and it is called out separately because the
handling differs in kind from every other row. In the account-update program the comments labelling
two adjacent macro expansions — the primary-cardholder expansion and the EFT-account-identifier
expansion — are transposed relative to the code they describe. Nothing in the code is wrong: the
token substitutions are correct and the prose beside them is swapped. The translation therefore
**follows the token substitutions and not the adjacent comments**, and a generating or reviewing
agent that trusts the comments over the code at that site will mis-map two fields. No comment text is
reproduced here; the defect is described.

### DL-055 — Two file-status codes are declared but unexercised, contradicting the prior specification

A prior specification asserts two particular status codes for file-not-found and duplicate-key
handling — `'35'` and `'22'`. A full literal census of the estate at this checkout returns **zero**
occurrences of `'22'` and **zero** occurrences of `'35'`: neither is compared anywhere in the legacy
source, in status-test context or in any other.

The vocabulary that *is* present, counted across all 28 programs and all 28 copybooks, is exactly nine
codes: `'00'`, `'01'`, `'02'`, `'04'`, `'05'`, `'10'`, `'12'`, `'23'` and `'31'`. That measured list is
what the raw enumeration is built from, which is why it is small and honest.

Both documented codes are declared so the documented vocabulary stays discoverable, but **no code path
may depend on, branch on or special-case either one**, and no test asserts behaviour for them. The
discrepancy between the prior specification and the source is recorded here rather than resolved by
adopting the specification's claim — and it is a genuine discrepancy rather than a counting difference,
because the measurement is zero and not merely lower.

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

`app/cpy/UNUSED1Y.cpy` has **zero** inclusion references anywhere in the estate — measured across all
28 programs and all 28 copybooks at this checkout. Migrating it would create dead Java code. It is
recorded here as a decision rather than left to look like an omission.

**This is the traceability matrix's one deliberate non-row.** The copybook contributes no procedure
paragraphs, so it produces no row to mark as an unimplemented translation the way a paragraph-level
non-implementation does. It appears in the matrix's accounting only by reference to this entry, and it
appears here as consciously excluded dead code. Cross-referenced from `traceability-matrix.md`, so a
reader auditing the row count against the estate finds the one artefact that is absent by intent rather
than by oversight.

### DL-058 — The unwired batch program is migrated but excluded from the default pipeline

`app/cbl/CBTRN01C.cbl` is a complete program — **491 lines** carrying **18** procedure-division
paragraphs — and it is invoked by no job member, no cataloged procedure and no CICS definition. It is
nonetheless translated, because the mandate is to migrate every program, and its job is defined but
excluded from the default pipeline and exercised only by tests. Both halves are deliberate:
translating it honours completeness, and leaving it unwired honours the estate's actual
orchestration.

**Why both halves matter to a reviewer.** Someone auditing coverage will find a job that no pipeline
runs and read it as dead configuration; someone auditing the pipeline will find a program the estate
never scheduled and read its absence as a missed translation. Only one of those readings can be
right, and neither is: the program is migrated *and* unwired, on purpose, and this entry is the reason
neither audit needs to guess.

The paragraph count is stated because it is the figure the matrix carries. The member declares 19
Area-A labels, one of which belongs to the environment division rather than the procedure division;
18 is the count of translatable units.

### DL-059 — The empty fee-computation paragraph remains an empty method

One paragraph in the interest program contains only a comment stating that it is to be implemented,
yet it is genuinely invoked from the interest-calculation driver.

It is preserved as an explicitly documented no-op. **No fee logic may be invented to fill it**:
doing so would be feature expansion and would change interest-run output. This is the sharpest test
of the no-feature-expansion boundary in the estate, because the vacuum is so obviously inviting.

### DL-060 — The file-availability and catalog-utility job members have no runtime equivalent

Three job members are intentionally unmigrated: `app/jcl/CLOSEFIL.jcl` and `app/jcl/OPENFIL.jcl`, which
disable and re-enable CICS file availability, and `app/jcl/CBADMCDJ.jcl`, which drives the CICS
resource-definition utility. Once the underlying data store is replaced these have no runtime
equivalent at all — connection availability becomes a pooled-datasource concern with no job to toggle,
and there is no resource-definition catalogue to update. They are documented as intentionally
unmigrated rather than dropped silently.

### DL-061 — The commented-out alternative menu label stays inactive

One user-menu option carries a commented-out alternative label in the legacy source. It remains
inactive in the target. Activating it would change a screen literal that no requirement asks to
change.

Three further categories are not migrated, and are recorded here for the same reason.

| Artifact | Reason |
| :------- | :----- |
| The non-application `EXEC PGM` steps across the estate — **70** of the 79, measured at this checkout | Dataset definition, deletion, repro and CICS file toggling. Absorbed by schema migrations, container service definitions and test fixtures rather than becoming job steps. The specification's figure is 69; the discrepancy is resolved in DL-251 |
| The `Product` / `Fee` / `Feature` page of `diagrams/CARDDEMO-DataModel.drawio` | Illustrative future-state entities with **no corresponding copybook, program or dataset** anywhere in the estate. Implementing them would be feature expansion, and the data-model diagram is read as reference for the eleven layouts that *do* have copybooks. Nothing on that page is modelled, migrated or seeded |
| The two packaged emulator runtimes under `samples/` | Opaque vendor binaries with no source content to translate |

---

## 12. Toolchain and dependency decisions

### DL-062 — The framework line is pinned to the constrained major version, not the newest release

**Decision.** `spring-boot-starter-parent` **3.5.16**, read back out of the module's own build manifest.

**The tension.** The requirement names "Spring Boot 3.x (latest stable)" while **4.1.0** is the latest
release overall. Two readings of that phrase are available and they select different artifacts.

**Why the narrower term governs.** The `3.x` qualifier is explicit and narrower than the general phrase
"latest stable", so it is the operative constraint and the general phrase describes position *within*
it. Read that way, 3.5.16 is the newest generally-available release on the 3.x line and 4.1.0 would
breach a stated ceiling rather than satisfy a stated preference. A ceiling that a plausible reading can
step over is worth recording, which is why this entry exists at all.

**Consequence.** Anything the 4.x line introduced is out of reach by construction, and no future upgrade
past 3.x may be taken as routine maintenance — it is a change of contract.

### DL-063 — Maven with a committed wrapper, rather than the permitted alternative build tool

**Decision.** Maven, with the Maven Wrapper committed and pinned to **3.9.16**.

The requirement permitted "Maven 3.9+ or Gradle 8.x", so this is a tie-break between two admissible
answers rather than a correction of a wrong one. Maven was chosen because the acceptance criterion is a
**zero-warning deployable artefact from a clean checkout**, and that criterion is discharged most
directly by exhaustive version pinning plus a build tool the project distributes itself: the wrapper
means a clean checkout needs no preinstalled Maven, and the pinned distribution means two machines run
the same build rather than two builds that happen to agree. The wrapper's distribution URL and its
SHA-256 are the sole declaration of the version, so no second copy of it can drift.

### DL-064 — No annotation processor and no code-generation library

The estate contains no annotation processor of any kind, and none is introduced. Two reasons
compound: under a warnings-as-errors build, processor-generated code is a live source of
build-failing warnings; and the unsafe-code audit commits to a reflection count of zero, which
DL-034 depends on. Boilerplate is written explicitly instead.

**Named exclusions, so the absence reads as a decision rather than an oversight.** **Lombok**,
**MapStruct**, **Immutables' processor** and **AutoValue** appear nowhere in the build. This is the
constraint that obliges all eleven fixed-width record mappers to be written by hand with explicit
offsets — a mapping framework would reintroduce reflection, and the audit budget for reflection is
zero, not small.

**One distinction is worth stating precisely, because a reader scanning the manifest will meet it.** An
annotation-**only** companion artefact *is* declared, because the non-shaded Docker transport publishes
signatures that cannot be read under `-Xlint:all` without it. The **processor-bearing** artefact of the
same family is deliberately not added. An annotation library that generates nothing is not a code
generator, and the line this entry draws is at code generation rather than at the word "annotation".

### DL-065 — Test container library stays at the version the framework manages

**Decision.** Testcontainers **1.21.4** — the version the Spring Boot 3.5.16 dependency management pins.

Testcontainers **2.0.5** is published, so this is a deliberate hold rather than a stale dependency.
Adopting it would mean overriding a managed coordinate across a major-version boundary, and the
override would then sit outside the one bill of materials that keeps every other test-scope version
consistent. Remaining managed keeps a single source of truth for the whole test tier; the cost is
foregoing 2.x features, and nothing in the eight acceptance criteria needs one.

### DL-066 — Two transitive libraries are pinned forward past published advisories

Two managed transitive libraries carried published advisories at the versions the framework
resolved. Both are pinned forward by overriding the framework's own version properties rather than
by declaring a direct dependency, so the override travels with the dependency management instead of
sitting beside it. The vulnerability scan is bound to the build and its report is a gate artifact.

### DL-067 — The vulnerability gate scans every dependency scope and carries no suppressions

> **Reconciliation note — this entry's scope decision stands; its no-suppression claim does not.** Every
> sentence below about *scope* is current: the scan still covers compile, runtime and test, still fails at
> CVSS 7.0, and still closes the two HTTP Core findings by replacing the shaded transport rather than by
> excluding it. What has changed since this entry was written is that a suppression file now exists. A
> vulnerability-database refresh produced `CVE-2026-66299` at 7.5 against the embedded servlet container,
> naming fixed releases that are published on no line, so there was nothing to upgrade to; the finding is
> carried as one narrowly scoped, evidenced, self-expiring determination in
> `carddemo-java/owasp-suppressions.xml`. Read the phrases below that say there is no suppression file, and
> that the unused-suppression setting is kept although nothing exercises it, as the position **at the time
> this entry was written**. The disposition in force is the one recorded in DL-159, with its sibling
> DL-244. The consequence for the claim this entry's heading makes: the gate's invariant is **zero
> unsuppressed** HIGH or CRITICAL findings plus that one determination — not zero findings, and not zero
> suppressions. `GateVerificationTest` asserts the determination's scope and reads both halves of the
> report, so a suppressed HIGH outside it fails a test rather than passing quietly.

**Status. The former scope exception is withdrawn.** The scan once excluded test dependencies because
the Testcontainers transport embedded a relocated HTTP Core copy that Maven could not manage. That
left code executing with Docker-daemon access on developer and CI hosts outside the gate, which is a
weaker supply-chain claim than the acceptance criteria permit.

**Decision.** Dependency-check includes compile, runtime and test scope and fails at CVSS 7.0. There
is no suppression file and no accepted HIGH or CRITICAL finding. The shaded
`docker-java-transport-zerodep` artifact is excluded. Testcontainers 1.21.4 hardcodes that transport's
binary class name, so a test-scope compatibility adapter preserves the expected builder API while
delegating every request to `docker-java-transport-httpclient5`. Its ordinary `httpcore5` and
`httpcore5-h2` dependencies are visible to Maven and pinned at 5.4.3, which closes CVE-2026-54399 and
CVE-2026-54428 instead of hiding them by scope.

**Why the adapter is not a fork.** It contains no protocol implementation and no request logic. It
wraps the maintained Apache transport, forwards the Docker host, TLS, connection-count and timeout
settings unchanged, and implements only the binary surface Testcontainers directly constructs. It is
test-scoped and `DeployableSupplyChainIT` proves that neither the removed shaded transport nor the
replacement transport enters the application jar.

**Verification.** The complete container-backed integration path runs through the adapter against
real Docker, PostgreSQL, LocalStack and Prometheus. The full-scope dependency report contains zero
HIGH or CRITICAL findings and zero analysis exceptions. One 5.3 semantic-conventions match remains
reported without suppression; its disposition is below the enforced threshold, not absent evidence.

**One setting is deliberately kept although nothing currently exercises it.** Failing the build on an
unused suppression rule stays enabled even with no suppression file present. It costs nothing while
there are no suppressions, and it means that if anyone ever adds one that stops matching, the build
reports a stale exemption instead of carrying it silently.

*Cited by:* `pom.xml`, at the vulnerability scan configuration and at the scope property;
`src/test/java/com/github/dockerjava/zerodep/ZerodepDockerHttpClient.java`;
`src/test/java/com/carddemo/support/DeployableSupplyChainIT.java`. The runtime transport pin is
recorded separately in DL-115.

### DL-068 — Continuous-integration actions are pinned to immutable commits

Every workflow action reference is pinned to a full commit identifier with the human-readable
version retained as a comment, so a moving tag cannot change what the pipeline executes.

### DL-069 — Goal ordering, not goal presence, was the supply-chain defect

Goals bound to the same build phase execute in plugin declaration order. The vulnerability scan was
declared after the coverage check, so a coverage shortfall ended the build before the scan ran and
the scan's evidence was never produced. The scan is now declared first. Both goals now execute in
one lifecycle, which is verifiable from the goal order in the build log rather than asserted.

### DL-258 — The AWS emulator is the community edition, and no entitlement token exists to hold

**Decision.** The local validation stack uses the **community** edition of the AWS emulator, not the
commercial one.

**Why it suffices rather than merely being cheaper.** The whole AWS surface this migration needs is object
storage for staged batch output, a first-in-first-out queue for the job-submission bridge, and a
notification topic for job completion. All three are community features. There is no service in the
target's design that the commercial edition would unlock, so the choice costs nothing in coverage.

**Why it is worth an entry.** The commercial edition was the **only non-open component that could have
entered this stack**, and it would have entered it as a licensing dependency on a build that is otherwise
reproducible from public artefacts alone. Excluding it keeps a clean-checkout build genuinely
self-sufficient.

**A stale reference is corrected rather than inherited.** Prior-run documentation refers to an
entitlement token for the commercial edition. **No such token is required, configured, or present
anywhere.** That reference is drift, and it is named here because a reader who goes looking for a missing
credential will not find one and should not conclude that the stack is misconfigured. There is no
credential to find.

### DL-259 — No application-level cache, because the legacy system has none

**Decision.** No cache library, no cache abstraction and no cached repository path anywhere in the
module.

**Legacy behaviour.** The estate has no application-level cache of any kind. Every read reaches the data
store, and the consistency a program observes is whatever the store gave it on that read.

**Why adding one would be a divergence rather than an optimisation.** A cache changes two observable
properties at once. It changes *consistency*, because a caller can be served a value that no longer
reflects the store — which in a tier that reads a balance, decides on it and writes it back is a
correctness change, not a speed change. And it changes the *timing profile* that the performance work
measures, which would corrupt a baseline being established for the first time rather than improve a
figure being compared against a target.

**Consequence.** Where a value is read repeatedly within one unit of work, the module scopes it to that
unit of work explicitly rather than caching it globally, so nothing outlives the run that computed it.

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

**Path convention in this index.** A citing file is named relative to the module's own source roots, so
`service/X.java` is `carddemo-java/src/main/java/com/carddemo/service/X.java`, `resources/…` is
`carddemo-java/src/main/resources/…`, and a test path is under `carddemo-java/src/test/`. Prose entries
outside this index spell a resource path in full — `src/main/resources/db/migration/V1__create_schema.sql`
— because there the shorthand has no surrounding table to establish it. Two rows below name artefacts of a
withdrawn layout, `resources/db/migration/.gitkeep` and the `schema`/`seed` subdirectories, which DL-127's
correction removed; they are retained as the citation history of that entry and do not describe files that
exist today.

This index lists *citations*, not entries. An entry with no row here is not unindexed — it is simply not
yet cited from the module's own source, which is the ordinary state of an entry that records a
documentation-level decision. Sections 16 to 19 are wholly of that kind: their entries carry stable
identifiers so that `traceability-matrix.md`, `gate-evidence.md`, `architecture.md` and
`onboarding-guide.md` can cite them by name, and they appear here only if and when module source cites
them too. `config/DecisionLogIdentifierContractTest` enforces the property that actually matters in both
directions — every heading is a well-formed, unique identifier, and every identifier the module cites
resolves to exactly one entry.

### 14.1 `DL-nnn` citations

| Citing file | Entry |
| :---------- | :---- |
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
| `service/SeededIdentifierSealingCallback.java` | DL-041, DL-110 |
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
| `util/StatementHtmlTemplates.java` | DL-041, DL-209 (superseding DL-037, DL-039; partly DL-038) |
| `batch/CreateStatementJobConfig.java` | DL-208, DL-213 |
| `batch/BatchStagingArea.java` | DL-212 |
| `batch/step/StagedGenerationStore.java` | DL-210, DL-211, DL-212, DL-214 |
| `batch/step/FixedWidthFlatFileReaderFactory.java` | DL-213 |
| `batch/CombineTransactionsJobConfig.java` | DL-212, DL-213, DL-214 |
| `batch/CategoryBalanceReportJobConfig.java` | DL-212, DL-213 |
| `batch/TransactionReportJobConfig.java` | DL-212, DL-213 |
| `batch/InterestCalculationJobConfig.java` | DL-207, DL-212, DL-214 |
| `batch/PostTransactionJobConfig.java` | DL-215 |
| `service/TransactionPostingService.java` | DL-215 |
| `batch/DailyTransactionReadJobConfig.java` | DL-216 |
| `config/ObservabilityConfig.java` | DL-217 |
| `batch/BatchLaunchCoordinator.java` | DL-218 |
| `batch/BackupTransactionJobConfig.java` | DL-210 |
| `config/BatchConfig.java` | DL-211 |
| `service/InterestCalculationService.java` | DL-207 |
| `batch/step/InterestCalculationProcessor.java` | DL-207 |
| `resources/application.yml` | DL-001, DL-005, DL-008, DL-047, DL-048, DL-088, DL-127 |
| `resources/application-local.yml` | DL-045, DL-047, DL-088, DL-127, anomaly register (7) |
| `resources/application-prod.yml` | DL-088, DL-127 |
| `pom.xml` | DL-088 |
| `resources/db/migration/V1__create_schema.sql` | DL-005, DL-007, DL-010, DL-012, DL-036, DL-127, anomaly register (1) |
| `resources/db/migration/V2__create_indexes.sql` | DL-127 |
| `resources/db/migration/V3__seed_reference_data.sql` | DL-103, DL-127 |
| `resources/db/migration/V4__seed_user_security.sql` | DL-127 |
| `api/dto/SignOnRequestTest.java` | DL-001, DL-003, DL-004 |
| `domain/enums/AccountStatusTest.java` | DL-024, DL-026 |
| `exception/OptimisticLockConflictExceptionTest.java` | DL-012 |
| `util/StatementHtmlTemplatesTest.java` | DL-037 |
| `service/AccountConcurrencyTokenServiceTest.java` | DL-074, DL-076, DL-077 |
| `config/FlywayConfigTest.java` | DL-041, DL-127 |
| `config/FlywayConfig.java` | DL-102, DL-110, DL-127 |
| `service/SeededIdentifierSealingCallbackTest.java` | DL-041, DL-104 |
| `service/FieldErrorTranslationServiceTest.java` | DL-080 |
| `util/CobolStringUtilsTest.java` | DL-078 |
| `util/ZonedDecimalCodecTest.java` | DL-013, DL-014, DL-015, DL-016, DL-041, DL-079 |
| `config/ConfigurationProfileBaselineTest.java` | DL-088 |
| `api/dto/PageMetadataTest.java` | DL-081 |
| `api/dto/ScreenWorkAreaSecurityTest.java` | DL-082 |
| `api/dto/NavigationContextSecurityTest.java` | DL-087 |
| `repository/TransactionCategoryBalanceRepositoryIT.java` | DL-013, DL-192 |
| `src/test/resources/fixtures/expected/*.txt` | DL-213, DL-219 |
| `support/ExpectedOutputFixtureContractTest.java` | DL-213, DL-219 |
| `support/ExpectedHtmlStatementFixtureContractTest.java` | DL-213, DL-219 |
| `e2e/BatchPipelineE2ETest.java` | DL-213, DL-219, DL-220, DL-221 |
| `e2e/OnlineTransactionE2ETest.java` | DL-220 |
| `e2e/GateVerificationTest.java` | DL-219, DL-220 |
| `config/grafana/dashboards/carddemo-overview.json` | DL-152 (superseded panel reasoning), DL-242 |
| `batch/CategoryBalanceReportJobConfig.java` | DL-243 |
| `pom.xml` | DL-159, DL-244 |
| `docker-compose.yml` | DL-246 |
| `config/ContainerHardeningContractTest.java` | DL-246 |
| `config/DecisionLogIdentifierContractTest.java` | DL-245 |

### 14.2 `D-nn` and anomaly-register citations

| Citing file | Entry |
| :---------- | :---- |
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
| `repository/TransactionCategoryRepositoryIT.java` | D-37, D-38 |
| `resources/db/migration/V1__create_schema.sql` | D-12, D-14 |
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
| `repository/TransactionCategoryBalanceRepositoryIT.java` | D-02, D-37, D-38, D-39 |

### 14.3 Concordance — the same decision under both schemes

| `D-nn` | `DL-nnn` |
| :----- | :------ |
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
| D-49 | DL-037, DL-038, DL-039 — all three now superseded or partly superseded by DL-209 |

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

`service/FieldErrorMarks` now accumulates `MarkedField(field, bmsFieldId, flagState)` - the field,
the screen label and the legacy flag state, and nothing else. The service layer owns that neutral
triple, so no validation service names a transport type. `api/dto/FieldErrorDecorator` remains where
the Agent Action Plan places the published wire shape, as a component-for-component twin rather than
as the object a service builds.

The conversions are placed where their dependencies are legal. `api/ScreenStateAdapter` maps the
wire decorator to and from the service-owned marks and can project those marks directly into
`ErrorResponse.FieldError` entries for a response. `service/FieldErrorTranslationService` maps the
same marks into `ValidationException`, and `api/GlobalExceptionHandler` maps that failure into the
same error response. No conversion is written at a call site, and
`FieldErrorTranslationServiceTest.TheSeamEndToEnd` compares the runtime failure path with the API
adapter's independent projection entry for entry, so changing either side alone fails.

This supersedes the earlier clause that allowed `FieldErrorTranslationService` to import
`api.dto.FieldErrorDecorator`. The plan fixes the transport type's location; it does not require the
service to depend on that type. Keeping the wire record in `api.dto` while giving the service its own
neutral carrier satisfies both requirements, leaves `api.dto` dependent only on `domain.enums`, and
closes the upward edge instead of licensing it. Neither representation adds text or state, so what a
client observes is unchanged.

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

**Suppression was rejected, and no rule names this coordinate.** Suppressing an advisory records a judgement that it does not apply, which is exactly the judgement that could not be established above. The scan does read one determination — recorded in DL-159, against a different identifier on different artifacts, for a finding with no published fix on any line — and it is scoped tightly enough that it cannot reach this coordinate: no entry for this advisory was written, and none would match it, which also means a future advisory against this coordinate cannot be silently inherited by an existing entry. An earlier revision of this paragraph said the scan ran with no suppression input at all; that was the position when it was written, and the rejection recorded here is unchanged by the file's later acquisition of that single unrelated determination.

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


### DL-092 - The job-submission queue is named `JOBS.fifo`, which is the one AWS resource name the plan prescribes; the namespaced `carddemo-jobs.fifo` is withdrawn

**Context.** The legacy estate's entire online-to-batch bridge is a CICS transient-data queue named `JOBS`, written from exactly one site. This entry has now been decided three times, and the third decision is a correction of the second. The queue was originally configured as `carddemo-jobs.fifo`. A revision renamed it to `JOBS.fifo`. A later revision renamed it back to `carddemo-jobs.fifo`, on the stated ground that the plan mandates all four AWS resource names byte-identically. A review then found that value to disagree with the plan, and re-reading the plan settles it.

**Decision.** The queue is named **`JOBS.fifo`**. The value is standardised across the shared configuration, the local overlay, both copies of the test configuration, the container composition, the emulator bootstrap script, the continuous-integration workflow, the publishing service and every test that names the canonical destination. The production profile continues to resolve it from the environment with no fallback.

**What the plan actually names, checked resource by resource.** The plan names the queue, and only the queue. It calls for the SQS FIFO queue `JOBS`, and it does so wherever the resource appears: in the module layout for the bootstrap script, in the transformation mapping for the container and bootstrap artefacts, in the external-reference table for that script, and in the adapter that replaces the transient-data write. For the other three it asks for "an S3 staging bucket", for "message-group ordering" and for "an SNS topic", naming none of them. So `carddemo-batch-staging`, `carddemo-job-submission` and `carddemo-job-notifications` are this module's own choices and stand unchanged, while the queue is the one value that was fixed for us. With the suffix its service demands, the prescribed name is `JOBS.fifo`.

**Why the second revision's reasoning does not survive.** It rested on a premise that does not hold - that the plan states four names and requires all four byte-identically. It states one. The premise also inverted the direction of authority: the emulator bootstrap script that provisions the queue is an artefact this module ships and the plan governs, so the module takes the name from the plan and the environment follows the module. Aligning the module to a name the environment happened to hold made the environment the authority over the one value the plan had already fixed. Uniformity across the four names is not itself an argument, because three of the four were never prescribed; the set is not being made inconsistent, it is being read correctly.

**The operator-visible text is untouched by any of the three decisions.** The failure message names the transient-data queue verbatim as `JOBS`, without a suffix and without a namespace, and is asserted character for character. It is frozen text rather than a reference to the resource, so nothing composes one from the other and neither the suffix nor a namespace can reach it. This is why every rename in this entry's history has been invisible to the byte-compared contract.

**What survives from the withdrawn revision, and is retained.** Its warning was correct and is the reason the name is stated once per place rather than composed anywhere: a name that is well formed but names nothing is the exact input that the publishing template's default behaviour resolves by creating a queue - see DL-093. A disagreement between the configured name and the provisioned name therefore makes a silent failure reachable, in which a submission reports complete while the cards sit in a queue nothing consumes. The remedy is agreement on the prescribed value plus the fail-fast strategy of DL-093, not a change of value. The collision concern that namespacing answered survives as a deployment concern, answered by the environment override without changing what the module ships.

**Consequence for the tests.** Every test constant carrying the canonical queue name is `JOBS.fifo`, including the fixtures that assert the queue name does not leak into a response body - those assertions remain true, because the frozen message contains `JOBS)` and not the resource name. The rejection cases for the mandatory suffix are re-expressed against the prescribed name - `JOBS`, `JOBS.FIFO`, `JOBS.fif`, a suffix-plus-more variant and a differently-shaped name - and the bare `JOBS` remains among them, because it is unsuffixed and must continue to be refused. A configuration test asserts that every document fixing the queue resolves it to `JOBS.fifo` and that the production profile fixes no default at all, and the LocalStack-backed integration tier resolves the queue by name against a queue the bootstrap script actually created.

**Relationship to DL-045.** DL-045 decided that the queue keeps the legacy resource name rather than a conventional one, which is what this entry restores; the two now agree. One aside in DL-045 does not survive: it says the legacy name is used as the message group identifier too, and it is not - the group identifier is `carddemo-job-submission`, for the reason given above that the plan leaves that name to this module.

### DL-222 - The job-submission queue is named `JOBS.fifo`, because that is the name the plan prescribes

> **Formerly recorded under `DL-092`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-092` is the one the module's own source cites. See entry DL-245.

**Context.** The legacy estate's entire online-to-batch bridge is a CICS transient-data queue named `JOBS`, written from exactly one site. This entry has been decided three times, and the record of the reversals is kept deliberately so the argument is not re-made a fourth time. The queue was first configured as `carddemo-jobs.fifo`, on the reasoning that all four AWS resource names should be namespaced after this module. A revision renamed it to `JOBS.fifo`. A second revision renamed it back to `carddemo-jobs.fifo` and asserted that the plan mandated that form byte-identically. It does not, and that assertion was the defect: the six repetitions it cited as evidence were six comments in this module's own configuration documents, each of which had been edited in the same pass - a document agreeing with its own binder proves nothing about either.

**Decision.** The queue is named **`JOBS.fifo`**. The plan names this resource twice and names it `JOBS` both times: once in the deliverable list, which describes `localstack/init/01-create-aws-resources.sh` as creating an "S3 staging bucket + SQS FIFO 'JOBS' + SNS topic", and once in the external-reference table, which describes the same script as creating "the S3 staging bucket, the SQS FIFO `JOBS` queue, and the SNS topic". It names no other value for this resource anywhere. The `.fifo` suffix is added because the queue service refuses a first-in-first-out queue whose name omits it - a start-up failure rather than a style preference - and is therefore a derivation from the service's own rule rather than a naming choice. The value is standardised across the shared configuration, the local overlay, both copies of the test configuration, the container composition, the emulator bootstrap, the continuous-integration workflow and the publishing service. The production profile continues to resolve it from the environment with no fallback.

**Why the set is not uniform, and why that is correct rather than an exception.** The plan names a value for exactly one of the four resources. For the other three it says "S3 staging bucket", "message group" and "SNS topic" and stops, so those three are this module's to name and are namespaced after it: `carddemo-batch-staging`, `carddemo-job-submission`, `carddemo-job-notifications`. The queue is the one resource with both a named legacy antecedent and a prescribed target name, so it is the one resource carrying a legacy name. Uniformity across the set was the original motivation for namespacing the queue, and it is not a reason that outranks a name the plan states: aligning code to the plan is the tie-break this migration applies everywhere else, and it applies here.

**What survives from the withdrawn reasoning, and is retained.** Its warning was correct and is the reason the name is stated once per place rather than composed anywhere: a name that is well formed but names nothing is the exact input that the publishing template's default behaviour resolves by creating a queue - see DL-093. A disagreement between the configured name and the provisioned name therefore makes a silent failure reachable, in which a submission reports complete while the cards sit in a queue nothing consumes. The remedy is agreement on the prescribed value plus the fail-fast strategy of DL-093. The collision concern that namespacing answered survives as a deployment concern, answered by the environment override without changing what the module ships.

**The operator-visible text is untouched by any of the three decisions.** The failure message names the transient-data queue verbatim as `JOBS`, without a suffix and without a namespace, and is asserted character for character. It is frozen text rather than a reference to the resource, so nothing composes one from the other and neither the suffix nor a namespace can reach it. The resource name and the message text agree by construction under this decision, and they agreed by construction under the withdrawn one too - which is why the message text is evidence for neither.

**Consequence for the tests.** Every test constant carrying the queue name is `JOBS.fifo`, including the fixtures that assert the queue name does not leak into a response body - those assertions remain true, because the frozen message contains `JOBS)` as part of a sentence and not as a resource reference. The rejection cases for the mandatory suffix are expressed against unsuffixed and mis-suffixed variants of the prescribed name, and the bare `JOBS` is retained among them because it is unsuffixed and must continue to be refused. A configuration test asserts that every document fixing the queue resolves it to `JOBS.fifo` and that the production profile fixes no default at all, and the LocalStack-backed integration tier resolves the queue by name against a queue the bootstrap script actually created.

*Cited by:* `application.yml`, `application-local.yml`, `application-test.yml`, `application-prod.yml`, `docker-compose.yml`, `localstack/init/01-create-aws-resources.sh`, `config/AwsProperties.java`, `service/JobSubmissionService.java`.


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

**Superseded in part again: the endpoint inventory has now arrived.** Request-mapped controllers are
delivered, so the sentence saying the interface document carries no endpoint inventory is no longer
true and has been withdrawn from `OpenApiConfig`, the local profile comments and their boundary test.
The underlying design still holds: `OpenApiConfig` maintains metadata and reusable schemas, while
springdoc derives operation paths from controller annotations when it builds the served document. The
replacement assertion therefore checks the boundary appropriate to the delivered state — the metadata
bean itself declares no path literal, controller-presence tests prove there is a scanned operation
surface, and the published description must say that springdoc augments the model rather than calling
the inventory empty.

*Cited by:* `application.yml`, `application-local.yml`, `config/OpenApiConfig.java`.

*The migration-location half is superseded by DL-102, and the empty-endpoint half is superseded by the
delivered controllers. The surviving principle is that documentation states the delivered state and a
test holds each claim to the artefact that makes it true.*


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
`db/migration/V3__seed_reference_data.sql`, `db/migration/V4__seed_user_security.sql`,
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
they agree, so they bind **one shared non-production fixture key**: a single fixed synthetic 32-byte value,
carried in Base64, sufficient for AES-256-GCM and used for nothing but the committed sample envelopes. **Its
value is not written on this page** — it is readable in the three non-production profiles that bind it, which
is the only place it belongs, and restating it here would put key material in the migration record for no
gain. Production is untouched: it binds the same property as an environment reference with no fallback, and
its version ceiling means it never applies `V3` at all, so no production row is ever sealed under a fixture
key.

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
digits - shape only, because a migration holds no key. `service/SeededIdentifierSealingCallback` then
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

*Cited by:* `db/migration/V1__create_schema.sql`, `db/migration/V3__seed_reference_data.sql`,
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
context refreshes - and the comment now says so. **Instrumentation itself stays switched on in both
copies.** An intermediate revision of the suite overlay disabled it outright, on the reasoning that a
suite asserts on no span; that reasoning overlooked the diagnostic context. `ObservabilityConfig` records
that the test profile neutralises export *while leaving the tracing instrumentation switched on*, so the
bridge still places the trace and span identifiers in the diagnostic context and the two correlation
fields `logback-spring.xml` publishes through its allow list still resolve. Disabling instrumentation
empties those fields for every test in the suite, and it leaves the packaged copy - the one a deployed
`test`-profile run loads - exercising a posture no suite run ever sees. Both copies therefore carry the
same three settings: instrumentation on, root sampling at zero, export off.

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
because it is the clearest case; the data-source, key-store, region and queue blocks each record what would
have happened without the guard and how it would have been misreported, and the trace-collector block
records the measured ordering set out in the addendum below, which is the one key the guard never reports. The header's
count of required values - which said six where the list below it named eight - is removed rather than
corrected, for the DL-104 reason.

**Measured addendum - the guard reports eleven of the twelve, and the twelfth is an ordering fact.** A
runtime reproduction of the production profile with every variable supplied except one found that
`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` never reaches the guard's summary. Actuator's own trace
autoconfiguration carries a property condition on `management.otlp.tracing.endpoint`, and conditions are
evaluated by `ConfigurationClassPostProcessor`, a bean-**definition**-registry post-processor. The
container runs every post-processor of that kind to completion before it runs any plain
`BeanFactoryPostProcessor`, which is what the guard is published as. The condition resolves the key
strictly one phase earlier, so start-up aborts with

```text
org.springframework.util.PlaceholderResolutionException: Could not resolve placeholder
'OTEL_EXPORTER_OTLP_TRACES_ENDPOINT' in value "${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT}"
Wrapped by: java.lang.IllegalStateException: Error processing condition on
org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingConfigurations
    $ConnectionDetails.otlpTracingConnectionDetails
```

This is behaviour 2 above, and it is the only one of the twelve keys it applies to, because it is the only
one a framework condition reads. **The outcome is the required one** - the deployment stops, and the
message names the exact variable a deployer must set - so nothing about the setting is corrected. What
differs is the wording: this fault arrives as the framework's placeholder message rather than as a line in
the guard's twelve-setting summary, so a deployment missing this variable *and* others learns about it in
two messages instead of one.

**Why the bare reference stays.** The only change that would let the guard speak first about this key is
to give the reference a fallback, so that lenient resolution succeeds during condition evaluation and the
guard then rejects the value it bound. That is precisely what the no-fallback discipline exists to
prevent, and a defaulted collector address is the exposure it prevents: production spans quietly leaving
for whatever host the default named. Republishing the guard as an `EnvironmentPostProcessor` would also
reorder it ahead of condition evaluation, but that restructures a security control - one whose ordering
guarantee, profile confinement and three-fault vocabulary are asserted by their own suites - to change
which of two correct messages a deployer reads. Neither trade is worth making, and the profile document
now states the ordering at the setting itself so the next reader is not surprised by it. Recorded rather
than repaired, per the same reasoning as the earlier paragraphs of this entry.

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

**Decision.** An after-migrate callback holds both protected columns to two invariants. It **seals** any unsealed value through the module's single field-encryption service, each value bound to the column it is being stored in so that an envelope written for one column cannot later be read as another's. It then **opens** every stored value under the key the running process actually holds, and fails start-up on the first value that will not open. It is registered for the local and test profiles alone, because those profiles migrate through V4 and receive the seeded rows. Production resolves the same flat migration location but is fixed at target V2, so it receives no seed row and should not carry a component that would put a table-wide read and update on its migration path for no purpose.

**Why the second invariant is the one that still bites.** Once DL-103 sealed the fifty seeded values, the sealing half converts nothing on a delivered database and stands only as defence in depth against a future edit to the seed. The opening half does not become redundant, because it answers a question no shape check can: an envelope sealed under some *other* key still carries the `ENC1` marker, and the seeded envelopes are fixed literals that nothing can re-key, so a process configured with the wrong key holds fifty unreadable rows that every marker-based check passes. That was reachable by configuration until the two seeding profiles stopped declaring their fixture key as an environment-variable default; DL-103 records that change. The check authenticates rather than reading through the column binding, because the seeded form is deliberately unbound and the bound reading would refuse all fifty - the binding remains a separate invariant, enforced where a value is read into the domain.

**On the transaction boundary, stated precisely because the opposite was once recorded here.** The after-migrate event is raised *after* migration execution completes and the migration's own transaction has committed, so the callback's work is a transaction of its own and is **not** atomic with the seeds it inspects. Electing to run in a transaction still matters - it makes the pass all-or-nothing within itself, so it cannot leave some identifiers sealed and some readable - but no return value can extend that to the migration. The residual exposure is bounded and is why the event is nonetheless the right one: a failure propagates out of the migrate operation and aborts the start-up, so no application ever reads a database whose identity columns are unsealed or unreadable, while the committed migration and the recorded history stay consistent with each other. Both halves being idempotent means the corrected start-up simply runs them again. An earlier revision of this entry, of the callback's own documentation, and of the seed header all claimed atomicity with the seed; a reader relying on that would have believed in a rollback that does not exist, so the claim is withdrawn here rather than softened.

**Why not a fifth versioned script.** One would work, and it would be wrong twice over. It would push the delivered migration set past a version no shipped script reaches - a claim the profile documents make and a test asserts against the delivered scripts - so the ledger would have to be weakened to accommodate the fix. And it would record a one-time application in the schema history, when what is wanted is an invariant that holds after *every* migration of a seed-bearing profile, including one that applied nothing because the seeds were already present.

**Idempotence as a property rather than a precaution.** Each value is examined before it is converted, and one that already carries the envelope marker is left exactly as it is. A second start updates nothing, and an envelope is never wrapped inside another envelope - which matters concretely, because the encryption service refuses to protect an already-protected value and would otherwise turn the second start of a local stack into a failure. The diagnostic records counts only: no readable identifier, no envelope and no customer key is ever written to a log, which is the rule DL-041 applies to every rejection diagnostic in the module.

**What this is, and is not.** Protecting these two fields at rest is a deliberate divergence from the legacy design rather than a translation of it - the legacy record holds both in the clear inside the customer record - and it is recorded here as an improvement on the baseline, not as a behaviour the source exhibits. No second encryption mechanism is introduced and no key is handled by the callback; it holds the one service and calls it.

*Cited by:* `service/SeededIdentifierSealingCallback.java`, `config/FlywayConfig.java`, `service/SeededIdentifierSealingCallbackTest.java`.


### DL-111 - Every migration ships from one location and production is held to the schema by a version pin, not by a directory split

**Context.** The migration plan names `db/migration` as the location of all four migrations, `V1` through `V4`, and the delivery boundary quoted for the sign-on seed at the time this entry was written named a `db/migration/seed/` child, which is not the delivered path - the delivered script is `src/main/resources/db/migration/V4__seed_user_security.sql`, flat beside the other three, as DL-127's correction records. An earlier revision instead split the two seeds into a second location, `db/seed`, and withheld them from production by omitting that location from the production overlay's location list. The split was defensible on its own terms - an omitted location cannot be reached by a flag left in the wrong position - but it put two of the delivered migrations somewhere no plan named, and it made the delivered tree disagree with the plan about where a migration lives.

**Decision.** The second location is withdrawn. `V3__seed_reference_data.sql` and `V4__seed_user_security.sql` sit beside `V1`, `V1_1` and `V2` in `db/migration`, every profile lists that one location, and production is held to the schema by `spring.flyway.target: 2`. `V1`, `V1_1` and `V2` are applied; `V3` and `V4` are resolved, reported above the target and never executed. The behaviour was verified against PostgreSQL 16 before the change was made: a migration pinned to `2` applies exactly two scripts, reports the two seeds as above-target, and still validates successfully, so a pending above-target migration is not an error a deployment has to suppress.

**Why a pin is not a weaker control than a missing location.** A location list and a target are both configuration, and either can be edited; the question is what each failure mode costs. An overlay that copied the schema location and forgot to keep the pin would seed a production database - which is exactly the hazard the split was chosen to remove. So the pin is applied twice and the second application is not configuration at all: `com.carddemo.config.FlywayConfig` publishes a migration customiser under the production profile alone, and that customiser sets the target after the resolved configuration has been bound, overriding an inherited value, an edited overlay, a merged property source or a command-line override alike. Re-enabling the seeds in production therefore requires removing the production profile itself, which a configuration edit cannot do quietly. The shared baseline also carries the pin rather than the convenience, so an overlay silent about migrations inherits the production posture; only the local profile and the test profile lift it, and each lifts it explicitly.

**Why the pin is `2` and how that stays correct.** `2` is the last schema migration, so the pin is the boundary between schema and seed rather than an arbitrary ceiling. That is asserted against the delivered scripts rather than restated: the highest schema migration must be at or below the pin and the lowest seed migration strictly above it, read from the file names on the class path. Adding a schema migration above the pin, or renumbering a seed below it, fails the build instead of silently changing what production applies. The published constant `FlywayConfig.SCHEMA_ONLY_TARGET` is compared with the configured value in both directions, so neither control can drift into naming a version the other does not.

**What the change cost the test suite, and what was done about it.** `AbstractPostgresIT` previously reached the head of a location list that carried no seeds; with one location that would now seed six hundred and thirty-six rows into the schema every integration test shares, changing what each of them observes. It is therefore pinned to the same schema-only target, so every integration test keeps the production-shaped, row-free baseline it was written against and inserts exactly the rows it means to. `SeedMigrationIT` migrates schemas of its own on the same server - one at the head, one at the pin, one pinned and then lifted - which is possible only because no migration script qualifies its own object names. That third schema is what makes the claim falsifiable rather than merely consistent: the same location and the same scripts, differing only in target, produce ten sign-on identities or none.

**What is not done.** No seed is rewritten, no row is dropped and no credential handling changes: the seeds still store every credential as an independently salted digest and are still confined to the local and test profiles. The withdrawn location is not deleted from the assertions - it is named in a guard that fails if it returns, as a document reference or as a delivered script, so the exclusion cannot quietly revert to a directory split.

*Relationship.* Restates and extends DL-102, which is the first record of this decision; the source files listed below cite DL-102.

*Cited by:* `application.yml`, `application-local.yml`, `application-prod.yml`, `application-test.yml`, `config/FlywayConfig.java`, `db/migration/V3__seed_reference_data.sql`, `db/migration/V4__seed_user_security.sql`, `support/AbstractPostgresIT.java`.

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

```text
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

*Cited by:* `application.yml`, `application-local.yml`, `application-test.yml`, `application-prod.yml`, `db/migration/V1__create_schema.sql`, `db/migration/V2__create_indexes.sql`, `db/migration/V3__seed_reference_data.sql`, `db/migration/V4__seed_user_security.sql`, `domain/UserSecurity.java`, `.dockerignore`.

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
`src/test/resources/application-test.yml` and the four migrations, which are delivered flat as
`db/migration/V1__create_schema.sql` through `db/migration/V4__seed_user_security.sql`. The
`db/migration/.gitkeep`, `db/migration/seed/V3__seed_reference_data.sql` and
`db/migration/seed/V4__seed_user_security.sql` paths this entry once named belonged to the split it records
being withdrawn, and are not delivered. The applied-state
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

### DL-127 - The seed migrations move into a profile-scoped location, because the reason the split failed twice was the shared parent and not the mechanism — CORRECTED

**Correction — integrated state, 2026-08-05.** The profile-scoped directory decision below was an
intermediate implementation and is superseded. The delivered migration inventory is exactly four
scripts, all directly under `carddemo-java/src/main/resources/db/migration` with no schema or seed
subdirectory: `V1__create_schema.sql`, `V2__create_indexes.sql`,
`V3__seed_reference_data.sql` and `V4__seed_user_security.sql`. Every profile resolves
`classpath:db/migration`; local and test migrate through V4, while the shared production posture and
the production overlay stop at target V2. Production also refuses to start against a database whose
migration history, reserved sign-on identities or reference-row volumes show that seed data was
already applied. The full PostgreSQL-backed verification applies all four scripts in non-production
contexts and exercises the production refusal independently.

The original profile-scoped-location decision is retained below because it explains the rejected
alternative and the recursive-location reasoning that led to it. It no longer describes the
delivered file layout or profile configuration.

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
`db/migration/V1__create_schema.sql`, `db/migration/V2__create_indexes.sql`,
`db/migration/V3__seed_reference_data.sql`, `db/migration/V4__seed_user_security.sql`
(the `db/migration/schema/` and `db/migration/seed/` paths this entry's superseded half describes, and the
`db/migration/.gitkeep` that held its empty parent, are not delivered - the correction above records the
flat layout that is), `domain/TransactionType.java`,
`support/AbstractPostgresIT.java`, `config/FlywayConfigTest.java`,
`config/FlywayConfigCoverageTest.java`, `config/ConfigurationProfileBaselineTest.java`,
`config/ApplicationProfileStartupTest.java`, `config/SeedMigrationIT.java`,
`service/SeededIdentifierSealingIT.java`, `config/ProductionSeedRejectionCallbackIT.java`. The
applied-state control that stands behind both configuration controls is the production rejection
callback recorded beside the sealing decision in DL-110.

**Five paths in that list name files the withdrawn layout would have had, and no longer resolve:**
`db/migration/.gitkeep`, the two `db/migration/schema/` scripts and the two `db/migration/seed/` scripts.
The delivered inventory is the four flat scripts named in the correction at the head of this entry —
`src/main/resources/db/migration/V1__create_schema.sql` through `V4__seed_user_security.sql`. The list is
left as written because it is this entry's own citation history; it is not a path index for the module as it
stands.


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

**Revision, 2026-08-06.** Two later findings touch this entry and neither overturns its core. The two
ordered-first finders it mandates were removed from the delivered module and replaced by list-plus-minimum
selection in seven services; DL-164 restores them and records what the regression cost. And the paged card
overload this entry retained for "genuine browse consumers" never acquired one, so DL-165 removes it. The
determinism argument, the rejection of a single-valued unbounded derived query, and the decision to leave
`idx_card_cross_reference_xref_acct_id` and `idx_card_card_acct_id` un-widened all stand as written. This
entry also appears twice in this document, identically; the duplication is a documentation defect recorded
here rather than silently repaired, because a citation may point at either copy.

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

**What the source actually does.** Every failure site performs the send paragraph. The send paragraph ends
by jumping unconditionally to the `RETURN-TO-CICS` paragraph, which issues a terminal `EXEC CICS RETURN`.
So the task **ends** at the first failure: the paragraph that performed the send never resumes, and
everything sequenced after that `PERFORM` is unreachable. In the operator-supplied arm that is a great
deal of work - the numeric normalisation of all six date parts, the five range tests after the first
failing one, the assembly of both ten-character dates, both subprogram calls, the four substitution slots, the
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

> **This identifier is a duplicate record. The single current record of this decision is DL-224.**
> The identifier split described in DL-245 - `DL-127` had been carried by more than one distinct
> decision, so the scrape-target decision was reissued under `DL-224` - left the original copy of the
> reasoning in place here under `DL-129`. Both copies carried the same title, so the log recorded one
> decision twice. Nothing outside this entry references `DL-129`, and section 14 lists neither identifier,
> because that index records the identifiers module source names and no module source names either of
> these two - which is the ordinary state described at the head of section 14. The heading is retained
> rather than deleted so that an identifier which was once published still resolves to an explanation
> instead of to nothing, and the superseded body is replaced rather than kept so that the log does not
> hold two divergent accounts of one decision.

**Why the body was replaced rather than left as written.** The copy that stood here was written before an
application service existed in the compose file, and it said so in the present tense - that the compose
file defined five services and no application service, and that the container address could therefore
never resolve. It then stated in its own next paragraph that the delivered target *was* the compose
service address, which contradicted the paragraph above it, and it closed by asserting that a
host-gateway mapping remained available on the Prometheus service. That mapping does not exist:
`docker-compose.yml` carries no `extra_hosts` entry on any service. Three statements about the delivered
topology were therefore wrong or self-contradicting in a document whose only purpose is to be relied on,
which is why the copy is not preserved verbatim even as history. The reasoning it contained survives in
full in DL-224, including the superseded five-service account, which is marked there as superseded.

**The delivered state, so that a reader who lands here is not misled.** The compose file defines **six**
services - postgres, localstack, jaeger, **app**, prometheus and grafana. The application scrape job
carries exactly one target and that target is the compose service address **`app:8080`**. The Prometheus
service carries no `extra_hosts` entry, the loopback address remains excluded, and the job name remains
`carddemo-app` because every provisioned dashboard panel selects on it. The rule the decision
establishes is unchanged by any of this: one target per job, chosen by editing the file, never a
candidate list for Prometheus to try.

*Cited by:* nothing. See DL-224 for the decision, its verification, and its citations.

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

### DL-223 - The reporting period travels as the three screen selectors it is entered on, in both directions, and the single resolved enumeration is kept beside them rather than replaced by them

> **Formerly recorded under `DL-130`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-130` is the one the module's own source cites. See entry DL-245.

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

### DL-131 - The menu option catalog occupies the AAP-mandated configuration package without creating an upward service dependency

**Context.** A direct `MenuService` import of `config.MenuOptionCatalog` created a two-package cycle,
because the configuration package legitimately imports services as the composition root. An earlier
repair removed that cycle by moving the concrete catalog into `service`, but the frozen AAP names the
target as `config/MenuOptionCatalog.java`. Preserving the cycle fix by contradicting the explicit target
structure is not an acceptable final state.

**Decision.** The concrete `MenuOptionCatalog` returns to `com.carddemo.config` as a scanned
`@Component`, exactly where the AAP places it. Dependency direction is preserved through inversion:
`MenuService` owns the small `MenuOptionSource`, `UserMenuOption` and `AdminMenuOption` views that its
logic consumes; the configuration catalog implements those views and therefore depends downward on
the service contract. `MenuService` imports no configuration type. The catalog retains its public
strongly typed records and immutable lists, so its copybook-parity tests and every API-boundary oracle
continue to exercise the same values and validation rules.

**Why this is not a layering exception.** No service-to-configuration edge is licensed or hidden.
`PackageLayeringTest` still requires that edge count to be zero and still rejects every two-package
cycle. The service-owned port is the normal dependency-inversion boundary between behaviour and a
composition-root data component, not a package-wide exemption.

*Cited by:* `config/MenuOptionCatalog.java`, `service/MenuService.java`,
`config/MenuOptionCatalogTest.java`, `PackageLayeringTest`.

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

### DL-134 - The transport records stay where the plan put them, services own the values they use, and the former exemption is closed rather than licensed

**Status.** This entry supersedes its earlier decision to retain exact upward-edge licences. That
decision treated the Agent Action Plan's placement of a transport record as though it also required a
service to import the record. It does not. The plan freezes the public wire types in
`com.carddemo.api.dto`; strict package direction separately requires the service layer to own its own
inputs, outputs and carried state.

**Decision.** The transport records remain in `api.dto`, byte-for-byte compatible, while the service
layer owns component-for-component carriers and command/result records:
`ScreenNavigationState`, `ScreenInputState`, `BrowseWindow`, `FieldErrorMarks`,
`AccountUpdateCommand`, `AccountUpdateOutcome`, `UserCommand`, `UserOutcome` and
`StatementLineSummary`. `ScreenStateAdapter`, `AccountUpdateContractAdapter` and
`UserContractAdapter` perform the transport conversion in the API layer. No statement-summary adapter
exists because no controller publishes a statement line; the batch tier is its only consumer and
`batch -> service` is already the permitted direction.

The batch control surface is closed on the same principle. `BatchJobController` no longer imports
nine batch configuration classes for their names. `BatchJobCatalog` owns the nine stable identifiers
in the service layer, every job configuration points its `JOB_NAME` at that catalog, and
`BatchJobLaunchService` owns launch and status operations for the controller.

**Enforcement.** `PackageLayeringTest` has no licence table. Its service-to-API upward-edge budget is
zero, `com.carddemo.api` has no permission to depend on `com.carddemo.batch`, and twelve named
services are asserted never to regain an upward edge. Source floors and existence checks keep those
absence assertions non-vacuous. A temporary divergence of the private statement redaction constant
proved the twin agreement guard, and a temporary upward import proved the package guard: both failed
on the exact change and were reverted byte-for-byte.

This is the same direction of repair DL-132 established for menu, navigation and report contracts,
applied to every remaining service and to the batch-launch boundary. No field name, message literal,
fixed width, HTTP status or output byte changed.

*Cited by:* `PackageLayeringTest`, `api/ScreenStateAdapter.java`,
`api/AccountUpdateContractAdapter.java`, `api/UserContractAdapter.java`,
`service/BatchJobCatalog.java`, `service/BatchJobLaunchService.java`,
`service/FieldErrorTranslationService.java`, `service/StatementLineSummary.java`.

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

**Decision, the session.** `util/SessionTokenIssuer` declares the one capability the boundary
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

*Cited by:* `util/SessionTokenIssuer.java`, `config/JwtTokenProvider.java`,
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

**Four documentation deliverables the plan names were also absent when this entry was written**, and
they are listed here for the same reason: `gate-evidence.md`, `traceability-matrix.md` with its five
hundred and forty-four rows, `architecture.md` and `onboarding-guide.md`. None of them was referenced from
the documentation site's navigation, so nothing was broken by their absence, but one comment in the
metrics scrape configuration already points at the gate-evidence file as the place measured figures are
written up. That pointer is left standing rather than removed, because the file is intended to exist and
the comment states where its content belongs.

*Currency note.* Three of the four have since been delivered — `gate-evidence.md`,
`traceability-matrix.md` at its five hundred and forty-four rows, and `architecture.md`.
**`onboarding-guide.md` is still absent**, so every reference to it is a statement of what the page is for
rather than a citable source, and the module's own `carddemo-java/README.md` carries the first-run
walkthrough in the meantime. Navigation is a separate question from delivery and is currently narrower than
delivery: `mkdocs.yml` registers this log and `gate-evidence.md`, and registers **neither**
`architecture.md` **nor** `traceability-matrix.md`, both of which are therefore reachable in the repository
but not published by the documentation site. Registering them is outstanding work. The paragraph above is
kept as written because it records the state the decision was taken in; it is not the current inventory.

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

### DL-224 - One scrape target per deployment, because a candidate list makes a dead target permanent and a live pair doubles the baseline

> **Formerly recorded under `DL-127`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-127` is the one the module's own source cites. See entry DL-245.

**Context.** The application scrape job listed two targets in one static configuration: the compose
service name for an application running as a container beside the stack, and the host-gateway address
for one running directly on the host. The intent was convenience - whichever address happens to be live
gets scraped, and every series carries the same job label either way.

**What that actually produces, in both of its two states.** Whichever of the two addresses does not reach a
process is a permanently failed target: the scrape-health view is permanently red, which trains a reader to
ignore the one view that says whether the performance gate has data at all. And if both addresses reach a
process, both series sets carry the same job label and differ only by instance - so every sum-by
aggregation the provisioned dashboard is built from counts that process twice. Those aggregations are
request rate, batch throughput, heap used and heap committed: throughput and memory are exactly the two
figures the performance gate records. A doubled baseline is worse than a missing one, because it looks
credible.

**Decision.** The application job carries exactly one target, and which address it is a deployment choice
made by editing this file rather than a list for Prometheus to try.

**The delivered topology, which is the current state of this decision.** The compose file defines **six**
services - postgres, localstack, jaeger, **app**, prometheus and grafana - so the application runs as a
container beside the stack, and the single delivered target is the compose service address **`app:8080`**.
The Prometheus service carries no `extra_hosts` entry, and the loopback address remains excluded for the
reason below. Running the application on the host instead means *replacing* that target with a
host-gateway address and restoring that mapping, not adding to it, and the instruction to do so is written
beside the target.

> **Superseded reasoning, retained because it is why the rule exists.** When this decision was taken the
> compose file defined five services and no application service, so the container address could not resolve
> and the delivered target was the host-gateway address behind an `extra_hosts` mapping. The application
> service was added afterwards, which inverted which of the two addresses is live. Nothing in the rule
> changed: one target, chosen by editing, never a candidate list.

**Two things deliberately not changed.** The job name stays `carddemo-app`, because every panel in the
provisioned dashboard selects on it and renaming it empties all of them while the stack still reports
healthy. And the loopback address remains explicitly excluded, since inside the Prometheus container it
resolves to Prometheus itself - a scrape that succeeds at the transport level and yields nothing, which
surfaces only as an empty gate write-up.

**Verified by running it, not by reading it.** A throwaway Prometheus was started on a
clone-index-derived port against this file, and the shared stack was left untouched. Its target API
reported exactly one target in the application job, up; `count(up{job="carddemo-app"})` and
`count(count by (instance) (up{job="carddemo-app"}))` both returned one, which is the property that makes
the job-level sums single-counted; and the heap and per-endpoint aggregations the dashboard uses each
resolved to a single credible figure. Both throwaway containers were removed afterwards. That verification
was performed against the host-run arrangement of the day; the invariant it establishes - one target, one
instance per job label - is what the delivered `app:8080` target preserves.

*Cited by:* `config/prometheus/prometheus.yml`. The job selector it preserves is
`config/grafana/dashboards/carddemo-overview.json`; the service it now addresses by name is the `app`
service in `docker-compose.yml`.

---

### DL-142 - The screen services keep the communication-area semantics in a service-owned carrier, so parity no longer requires a wire-record edge

**Context.** Three guards state the same boundary from three angles: `PackageLayeringTest` forbids an
import edge from the service package to the transport package, `ConversationStateAdapterTest` forbids any
source outside the API and configuration packages from naming the wire record at all, and a third
assertion inside it forbids deciding from an echoed member. DL-132 and DL-133 established the five-field
conversation state for services that need routing only. The account, card, transaction and
user-maintenance screen operations do not fit inside those five, and the reason is in the estate rather
than in the Java.

**What the estate does.** The CICS screen programs read carried communication-area members as their own
input. `COACTVWC` moves `CDEMO-ACCT-ID` into the read key at line 691 and `CDEMO-CUST-ID` at line 708.
`COCRDSLC` moves `CDEMO-ACCT-ID` and `CDEMO-CARD-NUM` into its work area at lines 342 and 343, tests both
for zero at lines 462 and 468, and branches on `CDEMO-LAST-MAPSET` at lines 505 and 527 to decide whether
the turn arrived from the card list. `COBIL00C` takes its nominated account from a carried field at line
118. A five-field conversation state models the two transaction identifiers, the two program names and
the entry mode, and none of those members. A service handed only a five-field state therefore cannot
reproduce the programs, so the choice was between the boundary and the parity mandate.

**The decision.** Parity still wins, but no deviation is needed. `ScreenNavigationState` is introduced
in the service package with the same sixteen components and helper semantics as the wire
`NavigationContext`; `ScreenStateAdapter` performs the one component-for-component conversion in the API
layer. The ten screen services read the service-owned carrier, while menu, navigation and report-request
services continue to take the five-field `ConversationState`.

`SCREEN_STATE_CONSUMER_FILE_NAMES` names the ten full-carriage consumers as a trust-boundary census,
not as a package licence. A separate unconditional assertion permits the wire record only under
`api` and `config`, with no service exception. The service-carrier census is also closed: outside the
API boundary, only its declaration, four opaque command/outcome holders and the ten measured screen
services may name it.

**Why this is not a hole.** Every census entry is asserted to name the service carrier, so a service
later reduced to five-field state cannot leave stale coverage behind. The four command/outcome records
are proven to hold the carrier whole and never dereference it. The two identity members may be
*carried* by the ten screen services but never *compared*, asserted line by line against a list of
comparison tokens with a floor on the number of reads found. The echoed-claim accessor still has no
production call site anywhere.

**What is not claimed.** The sixteen-component semantics were not narrowed or moved into server-side
session state. The client still echoes them and the screen services still reproduce the same reads; only
the Java package that owns the in-process carrier changed. No batch, repository, domain or utility type
names either transport state.

*Cited by:* `api/ScreenStateAdapter.java`, `service/ScreenNavigationState.java`,
`PackageLayeringTest`, `ConversationStateAdapterTest`.

---

### DL-143 - The account-update screen reads the stored regulated values because the program it reproduces displays them, and the residual exposure is recorded rather than masked away

**Superseded in part by DL-225.** The licence this entry grants still stands, and so does every
reason given for it. Its "residual exposure" paragraph does not: the composed response is now
published through the gate at the route, so the cleartext it describes no longer reaches an
unauthorized caller. Read this entry for why the reads are licensed, and DL-225 for what happens to
what they produce. (DL-225 was recorded under `DL-145` until that identifier was de-duplicated; see
entry DL-245.)

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

### DL-144 - The account view screen's assembler is the REST boundary, it holds no licence to read the stored regulated values, and the mask therefore applies to that screen

**Superseded in part by DL-225, and the supersession was only completed by DL-271.** The assembler
licence and the never-read-a-regulated-column arrangement this entry records still stand. The fixed
authority it presents does not: the boundary derives the authority from the authenticated principal
instead of passing `unprivileged(ACCOUNT_VIEW, null)`, so an administrator sees what the legacy screen
showed and every other operator still sees masks. The two-screen asymmetry described near the end is
likewise gone. (DL-225 was recorded under `DL-145` until that identifier was de-duplicated; see entry
DL-245.)

> **Read the paragraph above as of DL-271 and not as of DL-225.** DL-225 decided that both screens
> would derive the authority from the principal and it delivered that for the update screen only. This
> screen went on presenting the fixed `unprivileged(ACCOUNT_VIEW, null)` authority the entry below
> describes, which reversed the asymmetry rather than removing it: an administrator was masked here and
> revealed on the update screen. DL-271 is what replaced the constant with the derivation both screens
> now read, so the sentence above became true then.

**Context.** DL-135 made the four regulated components of the two account screens masked by default and
revealable only under a named purpose plus an authorization. DL-140 added the guard requiring any source
that assembles either account response to name that gate. DL-143 licensed
`service/AccountUpdateService` as the update screen's assembler, recorded the cleartext exposure that
follows from the program it reproduces, and left the view screen with no assembler at all - saying in
terms that the role-sensitive mask "begins to apply at the boundary that holds the authenticated
principal". `api/AccountController` is that boundary, and this entry records what its arrival settles.

**What the estate does.** `COACTVWC` composes the stored national identifier into one dashed
twelve-character screen item at lines 496 to 504, and moves the birth date, the government-issued
identifier and the electronic-funds account identifier onto the map at lines 507, 519 and 520, each with
no authorization test of any kind. The transaction is reachable by any signed-on operator:
`app/csd/CARDDEMO.CSD` L317 binds `CAVW` to the program with no role restriction, which is why the route
is answered by the filter chain's closing authenticated rule rather than by an administrative one.

**The decision.** `api/AccountController` is named in `RESPONSE_ASSEMBLER_BY_TYPE` as the single licensed
assembler of `AccountViewResponse`, and it is deliberately **not** added to the entitlement table of
sources permitted to read a stored regulated value. It reads none of the four accessors. All four
components come from `api/AccountProtectedDataAdapter.revealForView`, called once per turn and only when
the transaction resolved a customer row.

**Why the authorization it presents asks for a mask.** The controller passes
`RevealAuthorization.unprivileged(ACCOUNT_VIEW, null)`. Revealing needs either the administrative role or
an ownership determination the caller established for itself, and this boundary can assert neither
honestly. There is no ownership to establish: the user-security record carries no account linkage and no
program in the estate checks one, which DL-135 already recorded. The echoed communication area does carry
a user type, and reading it would be precisely the authorize-from-an-echoed-value that DL-133 and the
echoed-member audit exist to prevent - the same trap DL-143 declined to walk into. So the truthful
request is the one that asks for nothing, and the gate answers it with masks at the widths the revealed
values would have occupied, which leaves the screen's layout unchanged for every client.

**The consequence, stated plainly.** The view screen shows less than the legacy screen did: a national
identifier reduced to its final four digits, and three values fully masked. That is the DL-135 departure
taking effect on the screen DL-135 was written for, not a new one, and the reveal branch of the gate
remains live for any future caller that can establish authority. The two account screens are therefore
deliberately asymmetric - the update screen publishes cleartext because parity compels the reads it makes
and it holds no principal, the view screen publishes masks because it makes no such reads. The asymmetry
is the arrangement working: the one source that may read the stored values holds no principal, and the
one source that holds a principal reads no stored value.

**What the guard now asserts, and it is stronger than before.** The forward half of DL-140's guard has
become present-tense for both responses: each response type names the one file that may build it, so a
second assembler of either fails at the audit rather than at the first request that leaks. Nothing was
relaxed to accommodate the new assembler - the sealed-read binding check, the entitlement table and the
name-the-gate requirement all still hold unchanged, and the new controller satisfies the third by naming
the gate and the first two by never touching a regulated column.

*Cited by:* `AccountProtectedDataAdapterTest`, `api/AccountController.java`,
`api/dto/AccountViewResponse.java`.

---

### DL-225 - Both account screens derive their disclosure authority from the authenticated principal, which closes the update screen's cleartext exposure and restores the administrator's view

> **Formerly recorded under `DL-145`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-145` is the one the module's own source cites. See entry DL-245.

**Context.** DL-135 made the four regulated components of the two account screens masked by default
and revealable only under a named purpose plus an authorization. DL-143 then licensed
`service/AccountUpdateService` to read the stored values and to assemble the update response, and
recorded plainly that the update screen "publishes the four values in the clear to any caller
entitled to run the transaction", adding that the role-sensitive mask "begins to apply at the
boundary that holds the authenticated principal". DL-144 named `api/AccountController` as that
boundary for the view screen, but had it present `RevealAuthorization.unprivileged(ACCOUNT_VIEW,
null)` - a fixed authority that masks for every caller, an administrator included - and left the
update screen ungated. Review found both halves: authenticated-only access to a route that returns
full regulated identifiers, and a route that accepts no principal from which any authority could be
derived. This entry supersedes the residual-exposure paragraph of DL-143 and the fixed-authority
paragraph of DL-144.

**What the estate does.** Neither account transaction is administrative: `app/csd/CARDDEMO.CSD` L306
and L317 bind `CAUP` and `CAVW` with no role restriction, so any signed-on operator reaches both,
and both programs move the stored identifiers onto their maps with no authorization test at all.
Reproducing the route classification is not optional - an administrator-only account screen would be
a behaviour this estate does not have - so the repair cannot be a filter-chain rule.

**The decision.** Reaching a screen and being shown its regulated values are separated into two
decisions. The filter chain answers the first — with its closing authenticated rule when this entry was
written, and with the named two-authority allow-list DL-270 installed in its place; either way the first
decision is not this entry's subject. The second is taken per turn at the boundary, from the authenticated
principal alone: `AccountController.revealAuthorityFor(purpose, authentication)` pairs the administrator
type with `RevealAuthorization.administrator(purpose)` and every other type — including an unresolved one
and an absent identity — with `RevealAuthorization.unprivileged(purpose, userType)`, which withholds. The
type is read through `api/ScreenStateAdapter.authenticatedUserType`, which resolves it from the granted
authority. The echoed communication area is still never consulted, so DL-133 and the echoed-member audit
are honoured rather than traded away.

**How the update screen is closed without moving the reads.** DL-143's licence stands: the composing
service must read the stored values, for the before-image comparison at `COACTUPC` line 4171, for
the concurrency token, and because an authorized operator must see exactly what the legacy screen
showed. What changes is that the composed screen is no longer the published screen.
`AccountController.gatedForUpdate`, through `AccountProtectedDataAdapter.gateForUpdate`, withholds the
eight positions from the composed response at the route, so the values the service assembles reach a client
only under an authority the service itself could not have built. The gate lives at the boundary because
that is where the principal is, and because the service layer may not depend on the API layer - injecting
the adapter into the service would have been a layering violation as well as a second copy of the policy.

**The one rule that is not simply "mask it".** A screen turn on the update transaction submits the
whole map, and the confirming turn resubmits what the previous answer carried. A blanket mask would
therefore have made the transaction impossible to complete for every non-administrator, even one
supplying the regulated values from a source of its own: the masked echo fails the numeric edits at
`COACTUPC` lines 2068 onward, so no confirmation could ever be reached. The rule is consequently the
narrowest one that closes the disclosure - publish a regulated position only when it is
character-for-character the value *this request* carried, and mask it otherwise. Because the test
reads the request and never the record, it discloses nothing about what is stored: the caller learns
only whether the transaction echoed its own entry, which the messages on the same answer already
tell it. A value the caller supplied cannot be a disclosure to that caller, and a value the
transaction resolved from the record is never published to an unauthorized one.

**One masking policy, not two.** The gate masks by length, so a withheld value occupies its screen
position exactly as a revealed one would and no client re-lays out a masked screen; and it retains
the final four digits of the national identifier, exactly as `revealForView` and the masked arm of
`revealForUpdate` already did. The two account screens are therefore no longer asymmetric in what
they disclose - the asymmetry DL-144 described was a consequence of the update screen being ungated,
and it is gone. They remain asymmetric only in *where* the withholding happens, which follows from
one transaction handing back a record and the other composing its own screen.

**What is asserted rather than asserted-to-be-true.** That the route names the gate, derives the
authority from the established identity, and names both purposes; that no source but the two
licensed assemblers builds either response, with a record's own copy method excluded because a copy
of an already-assembled instance can introduce no value the wire contract did not already hold; that
an administrator receives the values and every other operator receives masks, on both screens; that
an administrative claim placed on the echoed communication area reveals nothing; and that a
submitted value round-trips while a resolved one does not.

> **Corrected, and two of the corrections are behaviour rather than wording. See DL-271.** This entry
> named three constructs that the delivered module does not have — a `RevealAuthorization.ofSignedOnUser`
> factory, a `gateProtectedValues` method, and `api/ConversationStateAdapter.signedOnUserType` as the
> principal reader. The delivered names are `AccountController.revealAuthorityFor` over the
> `administrator`/`unprivileged` factories, `AccountController.gatedForUpdate` over
> `AccountProtectedDataAdapter.gateForUpdate`, and `api/ScreenStateAdapter.authenticatedUserType`.
> `api/ConversationStateAdapter` exists but is not a collaborator of `api/AccountController` at all — it
> serves the menu and report boundaries — so the citation naming it pointed a reader at the wrong class,
> and it has been removed from the list below. Those are wording corrections and the names above are now
> the delivered ones.
>
> The behaviour correction is this: the claim that "an administrator receives the values and every other
> operator receives masks, **on both screens**" was true of the update screen only. The view route
> continued to present a constant `unprivileged(ACCOUNT_VIEW, null)` authority, so an administrator was
> masked there while being revealed on the update screen — the same four values of the same record,
> answered two ways. The decision recorded above is the right decision; half of it had not been
> installed. DL-271 records the single derivation that both routes now read, which is what makes the
> sentence true, and the assertion that fails if a constant ever returns.

*Cited by:* `api/AccountController.java`, `api/AccountProtectedDataAdapter.java`,
`api/ScreenStateAdapter.java`, `api/dto/AccountUpdateResponse.java`,
`service/AccountUpdateService.java`, `config/SecurityConfig.java`, and the tests
`AccountControllerTest`, `AccountProtectedDataAdapterTest` and `AccountControllerIT`.

---

### DL-226 - The archive object is fixed-length blocked with no separator, and its length is asserted before it is published

> **Formerly recorded under `DL-145`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-145` is the one the module's own source cites. See entry DL-245.

> **Integrated-state note.** This note was published under a second `DL-145` heading of its own, which made the identifier ambiguous for every citation that names it. The words are unchanged; only the duplicate heading is gone, and the note now sits inside the entry it annotates. See entry DL-222.
>
> **Correction — integrated state, 2026-08-05.** The fixed-length framing decision remains current, and
> the consumer obligation recorded below is now implemented. `CombineTransactionsJobConfig` reads the
> archive through the fixed-width transaction reader, so it advances in exact 350-byte strides and never
> looks for a line separator. The original statement that no in-module consumer existed is retained as
> the state at the time this decision was first written.


**Context.** The legacy archive dataset is allocated fixed-length blocked at the transaction record width.
A fixed-length blocked dataset carries no separator between two records at all: its record boundaries *are*
the width, which is why the width is declared once for the dataset rather than marked in the data. The
first revision of the archive step nonetheless wrote a line feed after every record image, and said so in
its own words - the reason it gave was that the module's line-oriented fixed-width reader could then
consume the object without a second convention.

> **Generalised by DL-213.** This entry states the rule for one dataset, and a later audit found that
> seven other artefacts of this module still carried the separator this entry removed - including a second
> producer of *this very dataset*, whose line-fed generation the consolidation job could not read. DL-213
> restates the rule for every logical dataset the module produces and moves every consumer with its
> producer. Nothing here is withdrawn; it is the special case of the general rule.

That reason inverted the contract. Every row became one byte wider than the format the object claims, and
the whole object became one byte per record longer than the record count multiplied by the width. A
consumer framing the object by its declared width - which is the only framing a fixed-length blocked
dataset offers - would have read the first record correctly and every later one shifted by its ordinal.

**Decision.** Nothing is written between two records. The object is the concatenation of exact record
images and nothing else, so its length is always the record count multiplied by the record width with no
remainder. That identity is asserted in the step, against a count of records actually *written*, before
the object is handed to the staging store: a wrongly composed archive is therefore never published rather
than published and later found.

**Why this is the same framing two other artefacts already used.** The interest run's generation is written
separator-free and its own documentation states that a newline between records would break the multiple.
The reject generation's writer declares an empty record separator for the same reason. All three artefacts
this module stages as fixed-length datasets are now framed alike, and the one that differed was the one
that was wrong.

**What a consumer must therefore do, stated because it cannot be inferred from the object.** A consumer
frames this object by width, never by line. The module's line-oriented reader is right for the shipped
sample datasets - those genuinely are newline-terminated, and one of them is not even at its mapper's full
width because its trailing filler is absent from the shipped file - and wrong for this one. No consumer of
the archive exists inside the module today; the obligation is recorded here so that the first one is
written correctly rather than written against the defect.

*Cited by:* `batch/BackupTransactionJobConfig.java`. The width it composes at is
`util/TransactionRecordMapper.java`; the separator-free precedents are
`batch/InterestCalculationJobConfig.java` and `batch/step/RejectRecordWriter.java`.

---

### DL-146 - Every staged dataset is an object addressed by a validated relative name, and the staging directory is withdrawn

> **Integrated-state note.** This note was published under a second `DL-146` heading of its own, which made the identifier ambiguous for every citation that names it. The words are unchanged; only the duplicate heading is gone, and the note now sits inside the entry it annotates. See entry DL-222.
>
> **Correction — integrated state, 2026-08-05.** The durable-boundary part of this decision survives;
> the staging-directory withdrawal does not. The eight job configurations that consume or produce staged
> datasets use the configured S3 bucket through `BatchStagingArea` and/or `StagedGenerationStore`.
> Execution-local paths remain for file-backed Spring Batch restart state, atomic `.part` completion and
> fixed-name local views, with `carddemo.batch.staging-directory` as their shared root. They are working
> buffers, not the durable staging contract. Completed generations are published to S3, and
> `StagedGenerationStore` applies the measured retention depths there. `FileProbeJobConfig` is the ninth
> job and produces no staged dataset. The positive name-validation rule remains active through
> `StagedResourceNames` and the staging-store key contracts.
> The original single-service/no-directory design is retained below as the intermediate implementation
> that was superseded when durable generation publication and restart-safe local buffers were combined.


**Context.** The migration plan replaces sequential-dataset and generation-group staging with object
storage. Only the archive step had made that move. Five job configurations still resolved a configured
logical name against a configured `staging-directory` whose default was the process temporary directory,
and then created directories, truncated files, read files and *deleted* files under it. The configured
names were checked for blankness and for nothing else.

Blankness is not the hazard. An absolute name replaces the root it is resolved against outright, and a name
carrying a parent segment escapes it - on steps that create, truncate and delete what they resolve. Two of
those steps delete. So a configuration value was a delete of an arbitrary path, and a job parameter on the
sixth consumer was a resource *locator* handed to a resource loader, which resolves schemes by design.

**Decision.** One staging service owns the whole of it. A logical name is admitted only if it is a safe
relative object key - letters, digits, `.`, `_`, `-` and the segment separator - and is rejected if it is
empty, over-long, absolute, container-naming, or carries an empty, current or parent segment. Every read,
write, stream, existence probe, length read and delete goes through that service, and every one of them
applies the rule before it addresses anything. The `staging-directory` key is withdrawn from all six
consumers: the staging area is the configured bucket, and the logical dataset name is the object key.

**Why one positive rule rather than a list of prohibitions.** Restricting the admissible character set is
what closes the whole class rather than the instances of it. A colon cannot appear, so no scheme -
`file:`, `classpath:`, `http:` - is expressible. A backslash cannot appear, so no platform path is. A
control byte cannot appear, so a name cannot forge a line in the log record that reports it. Three
defects stop being reachable as a consequence of one rule, rather than each being separately forbidden and
separately forgettable.

**Where the rule is applied, and when.** A configured name is validated when the configuration is bound, so
an unusable deployment stops the context rather than failing a job part-way through a run. A
parameter-supplied name is validated when the parameter is read, before any step opens anything. The
service applies it again on every call, so no path into the store can bypass it.

**What each staged artefact's contract now says.** A write replaces the object in full rather than
appending, so re-running one execution rewrites that execution's own dataset instead of doubling it -
which is what the legacy allocate-new disposition gave. A read of a name nothing wrote fails on open
rather than yielding an empty stream, because an absent input means the previous step did not run and an
empty run would be a silently wrong result. An empty input produces an empty output rather than a failure,
which is what the legacy read of an empty dataset produced. Nothing is created before a write: an object
store has no container to make, and an object comes into existence by being written.

**One consequence for diagnostics.** The store's own description of a resource renders a location, and on a
caller-influenced path a location is a value a caller supplied. No diagnostic renders it. What is logged is
the logical key and, for a failure, a bounded failure chain.

*Cited by:* `service/BatchStagingService.java`, and the seven consumers
`batch/PostTransactionJobConfig.java`, `batch/InterestCalculationJobConfig.java`,
`batch/CreateStatementJobConfig.java`, `batch/CategoryBalanceReportJobConfig.java`,
`batch/TransactionReportJobConfig.java`, `batch/CombineTransactionsJobConfig.java` and
`batch/DailyTransactionReadJobConfig.java`.

---

### DL-147 - Every outbound object-store call is observed where the call is made, because a boundary is traced once or not at all

**Context.** The archive step's upload was the module's only object-store call and it carried no
observation, so the trace ended at the boundary and an outbound failure set no error attribute on any
span. The gate that requires this offers two ways to satisfy it: a manual observation with fixed tags, or
supported instrumentation for the vendor's own client.

**Decision.** The manual observation, placed inside the staging service, which is the one place in the
module that addresses the object store. Every operation the service exposes wraps its store call in an
observation named for the staging boundary, tagged with a fixed store name and a fixed operation word from
a closed vocabulary of six, and carrying the object key as a high-cardinality attribute. A failure is
recorded on the span that made the call and then rethrown unchanged.

**Why manual rather than vendor instrumentation.** The instrumentation would be a dependency addition, and
the plan's dependency inventory is pinned and justified artefact by artefact. The observation API is
already on the path through the actuator and the tracing bridge, so the manual form adds nothing to the
graph - which also keeps the vulnerability gate's surface unchanged. The tags are then this module's own
fixed words rather than whatever a library chooses, which is what makes them safe to aggregate on.

**One honest limitation.** Two operations return an open stream. Their observation covers the *resolution*
of the handle, not the byte transfer, because the transfer happens when the caller writes and closes. The
whole-image write observes the transfer itself, which is why the archive path uses it. The limitation is
inherent to a streaming API and is stated rather than papered over.

*Cited by:* `service/BatchStagingService.java`. The registry it observes against is configured by
`config/ObservabilityConfig.java`.

---

### DL-148 - A new request mints a nonce-backed submission identity, and only a true retry reuses it

**Context.** The first queue bridge derived its submission identity solely from the requested start and
end dates. Each card's FIFO deduplication identifier was then that identity plus the card ordinal. That
made two legitimate requests for the same reporting period indistinguishable during the queue's
deduplication interval: all seventeen cards in the second request could be accepted by the client call and
silently discarded by the queue as duplicates of the first request.

**Decision.** Every new submission call mints an opaque UUID identity before it builds the per-card
deduplication identifiers. The submission result also carries the identity so a caller can name the same
attempt explicitly. Identity-bearing overloads accept it back only for a true retry of that same logical
attempt; calling the ordinary overload again always means a new request and therefore mints a new identity.
The report-screen transport records are unchanged: retry identity is an internal bridge contract, not a new
field invented for the legacy screen contract.

**The identity is call-local and nothing about it is persisted.** An intermediate revision of this entry
carried a PostgreSQL outbox row per logical submission so that a later call could resume an older partial
stream; that behaviour, its classes and its table are removed, for the three reasons set out under
*Revision* below. The delivered arrangement is the transaction-scoped advisory lock of DL-227, which
serialises whole card streams across replicas without persisting delivery state of any kind. The paragraphs
that described draining, replay and ordinal advancement are gone rather than annotated, because a withdrawn
mechanism written in the present tense reads as the current one.

**Why dates, timestamps and card bodies are not identities.** Dates describe requested work and can repeat.
A process timestamp can collide across replicas or after clock correction. A card body deliberately
repeats inside one image. A random logical identity has none of those semantic aliases, while appending the
one-based card ordinal still makes all seventeen deduplication identifiers distinct inside one
submission.

**Revision - the persistent outbox this entry once described has been removed, and its removal is the
decision.** An earlier revision persisted each logical submission, its exact publishable card image, a
fingerprint of the supplied stream and its next unsent ordinal in an operational `job_submission_outbox`
table, so that a later call first drained the oldest incomplete stream. That was wrong on three counts.
It was feature expansion: the estate defines eleven record layouts and the migration created a twelfth
table for behaviour no legacy artifact asks for. It inverted the queue contract: a caller's own cards
could be preceded by an unrelated submission's remainder, and the count handed back described a stream the
call had not published, because the reported total closed over the requesting call's card list while the
drain could be publishing an older one. And it contradicted the source directly - the emitting loop at
`[app/cbl/CORPT00C.cbl:L498-L509]` walks only the cards the running task holds, and
`ERROROPTION(IGNORE)` at `[app/csd/CARDDEMO.CSD:L501]` says a refused write is reported and abandoned,
never deferred. `JobSubmissionOutbox`, `PostgresJobSubmissionOutbox` and the table are therefore deleted.

**What a submission does now, and what failure means.** One call publishes its own cards, once each, from
the first through the transmitted end-of-stream card, and stops at its own first refusal with the
remaining cards unsent. Nothing is remembered afterwards. The result reports how many of *this call's*
cards reached the queue and returns the identity even on failure. The FIFO deduplication identifier still
makes a genuine retry of the same identity idempotent for cards the queue already accepted, which is the
one durability property the target technology supplies for free and the only one claimed. There is no
retry policy, no backoff, no dead-letter redirect and no cross-request replay, because the legacy has
none and each would add timing behaviour the migrated system must not inherit.

*Cited by:* `service/JobSubmissionService.java` and `service/ReportRequestService.java`. The
distinct-new-request and same-identity-retry contracts are exercised by
`service/JobSubmissionServiceSecurityTest.java`, `service/JobSubmissionServiceIT.java` and
`service/ReportRequestServiceTest.java`.

---

### DL-227 - Whole job streams are serialized across replicas by a PostgreSQL transaction-scoped advisory lock

> **Formerly recorded under `DL-149`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-149` is the one the module's own source cites. See entry DL-245.

**Context.** One JVM-local fair lock kept two threads in one process from interleaving their card streams,
but the deployment can run several application replicas. Every card uses the same FIFO message group, so
two replicas publishing concurrently could alternate cards from two different jobs even though each
replica's own loop was locally ordered. A process-local lock therefore proved less than the queue contract
requires.

**Decision.** A deployment-wide coordinator owns the entire first-card-through-sentinel critical section.
Its production implementation opens a new PostgreSQL transaction and acquires one parameterized,
transaction-scoped advisory lock under module-owned `CARD` / `JOBS` integer keys before invoking the
publication supplier. The supplier publishes the call's own cards inside that boundary, so the whole
first-card-through-sentinel run and the release of the global guard are one ordered operation. Commit and
rollback both release the lock. The coordinator exposes no separate unlock operation, so returning or
throwing cannot leak ownership. An infrastructure failure is translated back into the legacy queue
bridge's non-fatal failed result; it is logged with a bounded failure chain and no card is attempted.

**No delivery state is persisted inside the boundary.** The lock is the whole mechanism. An earlier
revision also wrote an outbox row per logical submission so a later call could resume an older partial
stream; that behaviour and its table are removed as feature expansion - see DL-148.

**Why PostgreSQL rather than a new lock service or a lock table.** Every replica already shares the
configured PostgreSQL database, so the advisory lock adds no dependency, schema migration, cleanup row or
lease-renewal process. `PROPAGATION_REQUIRES_NEW` prevents an unrelated caller transaction from extending
the critical section beyond the card stream, and the database owns crash cleanup. The keys are constants
bound through a prepared statement rather than request data or assembled SQL.

**How the cross-replica claim is tested.** The integration test constructs two independent coordinators
over one PostgreSQL server, blocks the first replica inside its publication supplier, and proves the second
cannot enter its own supplier until the first transaction releases the advisory lock. It then proves both
seventeen-card streams remain contiguous.

*Cited by:* `service/JobSubmissionCoordinator.java`,
`service/PostgresJobSubmissionCoordinator.java` and `service/JobSubmissionService.java`. The real
shared-database proof is `service/PostgresJobSubmissionCoordinatorIT.java`.

---

### DL-150 - SQS instrumentation is enabled at the integration layer and the actual publish call has a stable application observation

**Context.** The queue integration's observation switch was absent from every shipped profile, and the
application made its outbound `SqsOperations.send` call without an observation of its own. The surrounding
report request could be timed while the actual queue boundary - including the card ordinal that failed -
remained invisible and carried no error on an application-owned span.

**Decision.** Every shipped profile explicitly enables the integration's SQS observation support. The
queue bridge also wraps each actual send in an application observation named
`carddemo.job.submission.publish`. Its low-cardinality vocabulary is fixed to `system=sqs` and
`operation=send`; queue name, logical submission identity and card ordinal are high-cardinality details.
A send exception is recorded on that observation before the existing non-fatal bridge result is returned.

**Why both layers are retained.** Framework instrumentation describes the vendor integration and its
transport internals. The manual observation states this application's stable boundary contract and names
which card in which logical submission failed. Removing either leaves a different blind spot, while
keeping boundary detail out of low-cardinality tags prevents an unbounded metric series.

**No behavioural change is hidden inside observability.** Observation neither retries nor converts a
failure into an exception visible to the caller. The queue definition's ignore-on-error behaviour remains
unchanged: the failed send is recorded, the remaining cards are not emitted, and control returns normally
with a failed submission result.

*Cited by:* `service/JobSubmissionService.java`, `application.yml`,
`application-local.yml`, `application-test.yml`, `application-prod.yml` and the packaged test-profile
copy. The tag and error contracts are exercised by
`service/JobSubmissionServiceObservationTest.java` and `config/SqsObservationConfigurationTest.java`.

---

### DL-151 - Every terminal batch execution emits a bounded, parameter-free completion event through SNS

**Context.** The target provisions an SNS topic for job notifications, but provisioning alone is not an
integration: no producer addressed the topic. The shared batch boundary listener already observes every
one of the nine job configurations, which makes that boundary the one place where a terminal outcome can
be emitted without giving individual jobs different notification semantics.

**Decision.** After writing the terminal job diagnostic, the shared listener publishes an in-process
completion snapshot. A dedicated SNS listener maps that snapshot onto a version-one JSON event and sends
it to the configured topic. The payload carries only the sanitized job name, job-instance and execution
identifiers, terminal status, a closed-vocabulary exit code, step count, and start and end times. It is
limited to 512 US-ASCII bytes and deliberately carries no job parameter, execution context, exit
description or failure exception.

**Why the in-process event sits between Spring Batch and SNS.** The batch listener owns lifecycle timing,
while the notification service owns the external contract, topic validation, payload bound and
observation. Keeping those responsibilities separate means a transport concern does not enter each job
configuration, and it lets the listener refuse an application-event publication without changing the
already-established job result. The SNS listener applies the same non-fatal rule to a transport failure:
it records and logs a bounded failure diagnostic and returns without throwing into the job lifecycle.

**How unbounded framework text is prevented from becoming telemetry.** A job name is filtered to the
portable token alphabet and capped at one hundred characters. An exit code is not truncated: only the six
framework terminal words and a four-digit numeric code pass through, and every other value becomes
`OTHER`. That closed vocabulary prevents a caller-influenced exit description from being smuggled into a
field that looks operationally safe.

**How the outbound boundary is observed and proved.** The actual SNS send runs inside
`carddemo.job.completion.publish`. Its low-cardinality tags are the fixed words `system=sns`,
`operation=publish` and `eventType=carddemo.batch.job-completion`; topic, job and execution identifier are
high-cardinality details. A LocalStack integration test creates a real topic, subscribes a real queue,
publishes both completed and failed outcomes, and drains the queue to assert the exact raw payload.

*Cited by:* `config/BatchConfig.java`, `service/JobCompletionEvent.java` and
`service/JobCompletionNotificationService.java`. The lifecycle, payload, failure and observation
contracts are exercised by `config/BatchConfigTest.java`,
`service/JobCompletionNotificationServiceTest.java` and
`service/JobCompletionNotificationServiceIT.java`.

---

### DL-152 - Diagnostic fidelity preserves outcomes, not protected record images or provider failures

**Context.** Several legacy console displays rendered the whole record being processed, while three
translated services added similarly convenient structured diagnostics: a daily transaction including
its full card number, amount, merchant data, description and timestamps; an interest row pairing an
account identifier with its balance; and a report anomaly pairing a transaction identifier with its
amount. Administrative repository failures were also handed to the logging framework as throwables,
which lets provider SQL, paths, identifiers and exception messages escape the application's bounded
diagnostic policy.

**Decision.** A diagnostic preserves the fact and outcome of the legacy display without becoming another
copy of the business record. The daily-transaction pass emits one successful-read status per record and
no field value. Interest diagnostics retain only non-identifying reference codes. Transaction-report
progress and its deliberately preserved end-of-file anomaly carry no transaction or card identifier and
no amount. Administrative repository refusals retain the fixed transaction code, safe browse
anchor/offset where applicable, and a bounded failure-type chain; no throwable is attached to the log
event.

**Why debug level is not an exception.** The local profile intentionally enables application DEBUG.
Treating that setting as permission to disclose protected data makes confidentiality depend on who last
changed a deployment property, precisely when diagnostics are most likely to be exported or shared.
The safety property therefore belongs to each statement and is asserted with the detailed level enabled.

**What remains available for diagnosis.** Record counts and outcomes remain in service results and batch
metrics, raw file statuses remain in I/O diagnostics, and reference type/category codes remain where
they identify a decision-table branch. On the user-administration paths covered here, the original
provider cause still drives the existing response and transaction behaviour but is not attached to the
general application-log event.

*Cited by:* `service/DailyTransactionReadService.java`,
`service/InterestCalculationService.java`, `service/TransactionReportService.java`,
`service/UserManagementService.java`, `application-local.yml` and `logback-spring.xml`.

---

### DL-153 - Named controller timers retain exceptional turns under a fixed failure outcome

**Context.** The named online timers were stopped only after a service returned normally. Framework HTTP
metrics still saw an exception, but the migration-specific series omitted it, biasing both latency and
outcome distributions toward successful turns.

**Decision.** Every controller sample is stopped from `finally`. A bounded failure label is installed
before the service or projection runs and replaced only after a complete response has been composed.
Business rejections remain their existing normal outcomes; an unexpected exception is the distinct fixed
outcome `failed` or `FAILED`. Routes, presentations, confirmation state and report period use fixed
unresolved values where no result exists.

**Why the framework timer is not enough.** The framework series groups requests by HTTP attributes. The
named timers preserve the legacy transaction identity and its bounded outcome vocabulary, which are the
series the migration dashboard and gate evidence interpret. Both must include failures to describe the
same population.

*Cited by:* `api/AccountController.java`, `api/AdminUserController.java`,
`api/BillPaymentController.java`, `api/CardController.java`, `api/MenuController.java`,
`api/ReportController.java` and `api/TransactionController.java`. Every exceptional path is exercised by
`api/ControllerTimerFailurePathTest.java`.

---

### DL-154 - Gate 3 record throughput is read from exact application counters, not item-reader call timers

**Context.** The dashboard labelled the count of Spring Batch item-read timer observations as records and
grouped it by labels the exporter does not publish. A successful chunk reader also makes a terminal call
that returns end of input, and the interest job is tasklet-based, so the panel was neither exact nor
complete.

**Decision.** Gate 3 panels query the application-owned posting, interest-row, report, statement and file
probe counters with explicit rate or selected-range windows. The interest-row counter is included
directly. A separate diagnostic panel retains the framework item-reader call timer under its actual
sanitized labels:
`spring_batch_item_read_job_name`, `spring_batch_item_read_step_name` and
`spring_batch_item_read_status`; its title and description state that it counts calls, including the
terminal end-of-input call, and is not the Gate 3 record source.

*Cited by:* `config/grafana/dashboards/carddemo-overview.json` and
`config/GrafanaDashboardMetricsContractTest.java`.

---

### DL-155 - Required AWS resources gate readiness through non-creating existence checks, not liveness

**Context.** Database health was the only external readiness signal. The process could report ready while
the S3 staging bucket, SQS job queue or SNS completion topic was absent. The queue publish path must still
preserve the legacy ignore-on-error response for an individual report request.

**Decision.** Three health contributors check the configured resources without provisioning them. S3 uses
the bucket existence operation; SQS resolves queue attributes with the not-found strategy fixed to
`FAIL`; SNS resolves a name by listing topics and then verifies the ARN. Readiness includes the three
contributors plus the datasource and framework readiness state. Liveness contains only the process
liveness state.

**Why non-fatal publication and failed readiness coexist.** Parity determines the response to work already
accepted: a refused queue write is reported and does not raise through the screen transaction. Readiness
determines whether new work should be assigned. Marking an instance unready while a destination is absent
prevents further loss without changing the legacy turn that encountered the failure, while keeping
liveness independent avoids restart loops that cannot repair external infrastructure.

*Cited by:* `config/AwsResourceHealthConfig.java`, `application.yml` and `carddemo-java/README.md`.
The read-only, bounded and group-membership contracts are exercised by
`config/AwsResourceHealthConfigTest.java`.

---

### DL-156 - Unused annotation aspects are removed; instrumentation stays with the owning boundary

**Context.** `TimedAspect` and `ObservedAspect` were registered even though production source declared no
`@Timed` or `@Observed` method. The beans were inert while the configuration documentation claimed an
annotation-driven capability the application did not use.

**Decision.** Both aspect beans and their claims are removed. Controller timers, batch counters and AWS
observations remain explicit in the code path that owns the outcome and tag vocabulary. Framework
annotation support remains available if a future change deliberately introduces annotations and enables
the framework property; this configuration no longer pre-empts that choice.

*Cited by:* `config/ObservabilityConfig.java` and `config/ObservabilityConfigTest.java`.

---

### DL-157 - Compose grants a shutdown grace period longer than the application's drain phase

**Context.** The application uses graceful shutdown and permits one shutdown phase to run for thirty
seconds. Its container entrypoint is already in exec form, so the JVM receives `SIGTERM` directly, but
the Compose service declared no stop grace period. Docker's default ten-second wait could therefore
escalate to `SIGKILL` while a batch step was writing a fixed-width generation, while an object was being
uploaded, or between two cards of the seventeen-card job-submission stream.

**Decision.** The application service declares `stop_grace_period: 35s`. The value is deliberately greater
than, not merely equal to, the thirty-second Spring phase timeout: the five-second margin lets the
framework finish the phase and lets the process exit after the framework returns, rather than making
Docker's escalation deadline and Spring's own deadline the same instant.

**What the value does and does not promise.** It is a termination boundary, not a new performance target.
Normal shutdown still completes as soon as the application exits; Compose waits up to thirty-five
seconds only when work is still draining. The application timeout remains the authority over how long
one Spring phase may run. Other orchestrators are not configured by this file and must preserve the same
strict greater-than relationship in their own termination-grace setting.

*Cited by:* `docker-compose.yml`, `application.yml`, `Dockerfile` and `README.md`. The numeric relationship
is exercised by `config/ContainerLifecycleContractTest.java`.

---

### DL-158 - The trace collector is an optional destination, not an application start-up dependency

**Context.** Trace export is intentionally non-fatal and the observability tests prove that an absent or
unreachable collector does not fail application work. Compose nevertheless placed Jaeger in the
application's `depends_on` set with `service_started`. That condition is weaker than a health gate but it
is still a gate: if the collector container cannot start, Compose withholds the application even though
the application is designed to run without it.

**Decision.** The dependency edge is removed. The Jaeger service remains in the default local stack, and
the application's OTLP endpoint still addresses `http://jaeger:4318/v1/traces`, so traces are exported
when the collector is available. The application waits only for the two resources required to serve
correctly at start-up: PostgreSQL after its health check and LocalStack after its resource-provisioning
health check.

**Why the service is not moved behind a profile.** A profile would also make the collector optional, but it
would change the documented plain `docker compose up` experience and require the Prometheus/Grafana/
Jaeger validation stack to be selected explicitly. Removing the false dependency is the smaller change:
all six services still start by default, while a collector failure no longer blocks the application.

*Cited by:* `docker-compose.yml` and `README.md`. The Compose dependency graph is exercised by
`config/ContainerLifecycleContractTest.java`; collector-absence behaviour remains exercised by
`config/ObservabilityConfigTest.java`.

---

### DL-145 - All four condition-code step gates are the one strict form the plan freezes, and the fourth member's divergent literal is recorded here instead of implemented

**Context.** The estate carries six `COND=` occurrences, of which four are step gates: three on STEP020,
STEP030 and STEP040 of `app/jcl/CREASTMT.JCL` at lines 56, 66 and 79, and one on STEP10 of
`app/jcl/TRANBKP.jcl:51`. The remaining two, at `app/jcl/TRANREPT.jcl:47` and `app/proc/TRANREPT.prc:45`,
select records inside an ordering step and are not step gates at all. The migration plan states that all
four step gates demand a prior return code of exactly zero. Read on its own, the fourth member's literal
is spelled differently from the other three and would admit a prior warning.

**What was implemented before this entry, and why it was wrong.** An earlier revision treated that
reading as a correction to the plan. `config/BatchConfig.ConditionCodeGate` carried **two** constants
with two ceilings - zero for the statement job and four for the backup job - `batch/BackupTransactionJobConfig`
selected the looser one, and three test suites asserted the tolerance as behaviour: that a second gate
exists, that its ceiling is four, that a step completing with a stated code of four is admitted, and that
the backup configuration's source text must *not* name the strict constant. That is the failure mode a
migration is least able to recover from on its own. The plan is the frozen, agreed contract for how the
estate translates; a test written against a departure from it does not expose the departure, it protects
it, and every later reader is then told by a green suite that the looser rule is correct.

**Decision.** One ceiling, zero, for all four gates. `ConditionCodeGate` now declares the single constant
`ALL_PRIOR_STEPS_ZERO`; the backup job routes its gate through that constant, permitting the reset only
when every earlier step of that execution returned zero and ending - not failing - the flow otherwise. No
second ceiling may be introduced beside it. The divergent literal is retained as *metadata* about the
estate: it is stated in the documentation of the configuration, of the backup job and of the two test
suites, and it is recorded here, but no code path and no assertion expresses a tolerance for it.

**Why the decider is kept rather than replaced by a bare failure transition.** The plan maps the gates to
fail-or-end step transitions, and a transition on the framework's failure status alone would be a weaker
rule than the one the plan states: a step that *completed* while stating a nonzero completion code of its
own has not failed, so such a step would pass a bare transition and would be running behind a gate that
demands zero. The decider closes that hole. It derives a numeric code per accumulated step execution -
digits at face value, nothing from a step that has not ended, the error code from a step that did not
complete, zero from a step that completed silently or with one of the framework's own exit codes, and a
remark code from a step that completed while saying anything else - and gates on the highest. With the
ceiling at zero, a stated four, a stated twelve, an unparseable over-long digit run and a free-text remark
are all refused, which is the strict rule stated completely rather than partially.

**What deliberately did not change.** `service/TransactionPostingService.RETURN_CODE_REJECTS_PRESENT`
stays four. That value is the posting program's own, set at `app/cbl/CBTRN02C.cbl` lines 229 to 230, and
it is reported on the posting job's own step for an operator to read. It was previously asserted by
equality against the tolerant gate's ceiling, which made two unrelated facts look like one contract; the
assertion now cites the program and additionally records that no step gate admits the code. A gate reads
only the step executions of the job execution it sits inside, so a code raised by one job was never
something another job's gate could have admitted.

*Cited by:* `config/BatchConfig.java` and its gate enumeration; `batch/BackupTransactionJobConfig.java`,
which selects the ceiling; `batch/CreateStatementJobConfig.java`, whose three gates are the same rule
expressed as three transitions; and `batch/PostTransactionJobConfig.java`, which raises the completion
code that is no longer read as a ceiling. The covering suites are `config/BatchConfigTest.java`,
`batch/BackupTransactionJobConfigTest.java` and `batch/PostTransactionJobConfigTest.java`.
### DL-228 - The jarmode tools library is declared so the vulnerability gate can see it, the plugin's own copy is turned off so only one writer remains, and the loader's redundant copy is accepted because excluding it would recreate the very gap being closed

> **Formerly recorded under `DL-145`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-145` is the one the module's own source cites. See entry DL-245.

**Context.** The supply-chain gate scans the resolved dependency graph and fails the build on a critical
or high finding. `DeployableSupplyChainIT` already asserted the other half of that claim's honesty -
that nothing the gate deliberately skips, meaning the test graph, reaches the deployable archive. Neither
mechanism addressed the opposite direction: a library that reaches the archive without ever having been
in the graph at all. Review found one, and it is not an oversight in a declaration.

**What was actually happening.** `spring-boot-jarmode-tools-3.5.16.jar` shipped inside `BOOT-INF/lib`
and the container image went on to **execute** it - `Dockerfile` runs `java -Djarmode=tools -jar <jar>
extract --layers --launcher` to split the archive into image layers. It was nevertheless invisible to the
scan, because it is not a resolved artefact: it is an ordinary **resource embedded inside the build
tool**. `spring-boot-loader-tools` carries it at `META-INF/jarmode/spring-boot-jarmode-tools.jar`, and
with `includeTools` at its default the repackaging step copies that resource out of itself into the
archive. Nothing in this project's graph ever names it, so no amount of reading the graph could have
found it. Measured before the change: of the 179 bundled libraries, 178 resolved from the repository and
exactly one did not.

**Why a bounded, shipped-and-executed library is worth this much attention.** The exposure is small in
size and ordinary in kind, which is exactly why it is the interesting case. A gate that reports "no
critical or high findings across the dependency graph" is trusted as a statement about the product, and
the product contained a library the statement did not cover. The defect is in the *scope of the claim*,
not in the library, and a claim whose scope is wrong by one is wrong in the same way as a claim whose
scope is wrong by fifty.

**The decision, in three parts.** First, the artefact is **declared** in `pom.xml` at `runtime` scope,
version inherited from the parent, which puts it in the graph the gate scans. Second, the plugin's own
extraction is turned **off** with `<includeTools>false</includeTools>`, so the graph is the single writer
of the library. Third, the `spring-boot-loader` copy that arrives as its transitive dependency is
**accepted** rather than excluded.

**Why the scope is `runtime` and not `provided`.** `provided` was tried first and is wrong twice over.
The repackaging step includes provided-scope libraries as well, so the two writers still collided; and
describing a library the image genuinely runs as "provided" misstates how it reaches the runtime.
`runtime` is the narrowest scope that both ships the library and puts it in the scanned graph, and
nothing compiles against it, so `compile` would widen the compile classpath for no benefit.

**Why the extraction is turned off rather than the graph copy excluded.** With the artefact declared and
the extraction left on, the plugin refuses outright: *Duplicate library
spring-boot-jarmode-tools-3.5.16.jar*. That refusal is correct behaviour and is what forced the choice -
a silent duplicate inside the archive would have been a worse defect than the gap. The plugin's
`<excludes>` filter was tried on the graph copy and did not prevent the collision, so the remaining
correct arrangement is to leave exactly one writer. **Nothing about the artefact changes:** the jar
resolved from the repository and the resource embedded in the build tool are the same bytes, sha256
`d05beb46a7eac0f1a06733c75828b190a1cb250076ce03be2213aca4a5c0f03f`, so only the provenance moved.
`DeployableSupplyChainIT` asserts that digest rather than assuming it, because if the two sources ever
stop matching, a change of provenance has become a change of content and must not pass silently.

**Why the loader's redundant copy is accepted, which is the least obvious part of this entry.**
Declaring the tools library brings its own runtime dependency, `spring-boot-loader`, onto the graph, so
the archive now carries `spring-boot-loader-3.5.16.jar` as a bundled library - about 200 kB in a 92 MB
artefact - **in addition** to the loader's 99 classes that the repackaging step already writes unpacked
at the archive root, because that is how a repackaged jar boots. The obvious tidy-up is to exclude the
transitive dependency. It was rejected, and for the same reason this entry exists at all: the unpacked
classes come from a resource embedded in the build tool, by the identical route that hid the tools
library. Verified byte for byte - the classes at the archive root and the classes inside the resolved
`spring-boot-loader` jar have matching digests, 99 classes on each side. So excluding the coordinate
would save 200 kB and put 99 shipped classes outside the scan, reintroducing the defect for a different
artefact. The redundancy is the price of the coverage and it is the cheaper of the two. The trade is
asserted, not just described: the test requires the loader coordinate to be on the graph and requires the
unpacked classes to still be written, so if either premise stops holding the trade is revisited rather
than silently inherited.

**Coverage after the change, measured rather than asserted.** `dependency-check:check` scans 128
top-level entries plus 69 merged related entries, a union of 197 artefact names. Every one of the 180
libraries bundled inside the deployable jar appears in that union: **packaged-JAR coverage is 180 of 180,
with zero critical or high findings and one medium.** Reproduce it by running `./mvnw -B
dependency-check:check` and comparing `target/dependency-check-report.json` - the union of `dependencies`
and their `relatedDependencies` file names - against the `BOOT-INF/lib` listing of
`target/carddemo-java-1.0.0.jar`.

**What the new guard asserts, and why it asserts resolvability rather than reading the report.**
`DeployableSupplyChainIT` requires every bundled library to exist as a resolved artefact in the
repository the running build used - the location is handed to the integration tier by the build itself
through `carddemo.maven.repository`, rather than guessed, so the check stays correct under a custom
repository. It deliberately does not read the scan report: the integration tier runs in the
`integration-test` phase and the scan is bound to `verify`, so the report does not exist yet when the
test executes, and a check written against a file that is usually absent is a check that usually passes
for the wrong reason. Resolvability is the property the gate's coverage actually rests on, it is available
in every build including a fully offline one, and it fails for any future library arriving by the
embedded-resource route rather than only for the one already found.

*Cited by:* `pom.xml` - the dependency declaration and the `includeTools` note - and
`src/test/java/com/carddemo/support/DeployableSupplyChainIT.java`.

---

### DL-229 - One hashing strength, owned by the digest service and read by the security configuration, because two encoders quietly produced two different work factors

> **Formerly recorded under `DL-146`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-146` is the one the module's own source cites. See entry DL-245.

**Context.** Two components constructed a BCrypt encoder. `config/SecurityConfig` declared a strength of
12 and used it for the encoder bean the authentication manager consumes; `service/CredentialDigestService`
called the library constructor with no argument, which is cost 10. Both were correct in isolation and the
module's own documentation described a single hashing policy, so the divergence was invisible: a credential
written through one path was four times cheaper to attack than one written through the other, and nothing
compared the two.

**The decision.** The strength is a single published constant, `CredentialDigestService.HASHING_STRENGTH`,
and it is 12. The digest service constructs its encoder with it, and `SecurityConfig.PASSWORD_HASHING_STRENGTH`
reads it rather than restating it - which is legal in this direction because the configuration package sits
above the service package, and illegal in the other. The digest service was therefore raised from the library
default rather than the configuration lowered to meet it: the cost of a slower hash is borne once per sign-on
and once per credential write, and the module's own tests absorbed it without a measurable change to the suite.

**What is asserted rather than described.** `SecurityConfigTest` reads the cost factor back out of digests
produced by both encoders and requires them equal, so a future component constructing its own encoder is
caught by a comparison rather than by a reading of two files. The two javadoc paragraphs that were about to
become lies - one claiming the cost was left at the encoder's own default, the other instructing components to
obtain digests from the service rather than construct a second encoder - were rewritten in the same change.

**One floor that is deliberately separate and lower.** `domain/UserSecurity` enforces a structural minimum
cost of 10 on any digest presented for persistence. That is not the policy figure and must not be raised to
match it: it exists to refuse a value that is not a credible digest at all, including one produced by an older
policy, and a persistence guard that rejected every historically valid digest would refuse data it is meant to
protect. The policy figure governs what this module writes; the floor governs what it will accept.

*Cited by:* `service/CredentialDigestService.java`, `config/SecurityConfig.java`,
`config/SecurityConfigTest.java`.

---

### DL-230 - A bearer session is established only while it still names its own record, so a demotion, a deletion or a credential change revokes it at the next request rather than at its expiry

> **Formerly recorded under `DL-147`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-147` is the one the module's own source cites. See entry DL-245.

**Context.** The issued grant carried issuer, subject, issue time, expiry and role, and the bearer filter
established authority from the role claim alone with no reference to current state. Every claim was true when
minted and none was re-checked, so an operator demoted from administrator, deleted outright, or given a new
credential kept the authority the grant recorded for the remainder of its lifetime - up to thirty minutes of
administrative access to the user-maintenance and batch-control surfaces after the change intended to remove it.

**The decision.** The grant carries one further claim, `authstate`, and the filter establishes an identity only
while that claim still matches the authoritative record. `service/SignOnStateService` computes it as a SHA-256
digest over a version marker and three length-prefixed fields - the identifier, the raw user-type code and the
stored credential digest - and comparison is constant-time. Each of the three revocation cases changes at least
one covered field, so each invalidates every grant already issued to that identity at the next request. Nothing
is bumped or written: the value is DERIVED from state that already changes, which is why no migration, no column
and no write-path edit was needed, and why a revocation cannot be forgotten at a write site.

**Why nothing is stored.** No revocation list, no session table and no cache. The migration plan excludes an
application-level cache, and a store would have to be authoritative to be trusted, which makes it a second
source of truth for the same fact. The cost is one primary-key read of `user_security` per authenticated
request, on the table's own key, which is the same read the sign-on turn already performs.

**Two consequences stated plainly.** First, issuing refuses rather than guesses: if the record is absent, or its
type disagrees with the role about to be minted, `JwtTokenProvider.issue` raises rather than producing a grant
whose claim would be stale from birth, and the boundary answers a neutral failure. That path is reachable only
by a race between verification and issuing. Second, the claim leaks one bit of metadata: a party holding two
grants for one identity can tell whether a covered field changed between them. That party already holds two of
that identity's credentials, so the disclosure is accepted and recorded rather than mitigated. Bumping the
version marker in the fingerprint scheme is, deliberately, a way to revoke every live session at once.

*Cited by:* `service/SignOnStateService.java`, `config/JwtTokenProvider.java`, `config/SecurityConfig.java`,
`util/SessionTokenIssuer.java`, `api/AuthController.java`.

---

### DL-231 - The batch-control surface is administrator-only, and its job parameters are an allowlist rather than a map, because the framework's untyped parameter path can name a class

> **Formerly recorded under `DL-148`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-148` is the one the module's own source cites. See entry DL-245.

**Context.** The batch-control endpoints launch the nine wired jobs and report an execution. Two things about
them had no legacy counterpart and therefore no parity answer: who may reach them, and what a caller may pass.
The estate's route from an operator to a batch job was a fixed-width queue write read by a scheduler, not a
transaction, so the resource definitions classify nothing here.

**The decision on authority.** The endpoints are gated to the administrator authority. The reasoning is by
analogy rather than by inheritance: the legacy queue write was reachable only from the reporting transaction,
whose own submission gate the estate placed behind a confirmation, and every operator-facing maintenance
transaction the estate does classify is administrative. Launching a posting or interest run is at least as
consequential as editing a sign-on record. The gate is implemented as a path region - the controller publishes
its prefix and the security configuration reads that constant - so the rule and the mapping cannot disagree by
being written twice, and `DeliveredRouteSecurityStateTest` asserts both batch operations stay inside it.

**The decision on parameters.** The untyped launch path was removed. A caller previously supplied a map that
was converted to properties and handed to the framework's conversion, which accepts a type-bearing syntax and
will resolve a named class - a caller-controlled type name is not an acceptable input to a control surface.
Each job now declares an explicit allowlist of parameter names and each accepted value is converted to a typed
parameter by this module, so an unknown name, a value bearing a separator or a control character, and an
attempt to override an identifying parameter are all refused before the framework sees anything.

**A second constraint that follows from the same reasoning.** The five job configurations resolve staged file
names against a staging root. Those names were checked only for being non-blank before being resolved, so an
absolute path or one containing a parent reference escaped the root and could be created, truncated or deleted.
`util/StagedResourceNames.requireSimpleName` now refuses anything but a simple name for a dataset, while the
staging root itself stays legitimately multi-segment. The distinction is the point: the root is configuration,
the names are data.

*Cited by:* `api/BatchJobController.java`, `config/SecurityConfig.java`, `util/StagedResourceNames.java`,
`batch/JobParameterValidators.java`.

---

### DL-149 - The two transaction-identifier allocators take one advisory lock before reading the highest key, and the estate's browse-and-add-one is preserved rather than replaced by a sequence — CORRECTED

**Correction — integrated state, 2026-08-05.** The lock and insert operations live on the
`TransactionInsertRepository` fragment, which `TransactionRepository` inherits. This preserves the
frozen custom declaration on `TransactionRepository` itself: `findMaxId` and
`findByProcessingDateRange`, with no added lock or insert declaration there. Both allocators take the
fragment's transaction-scoped advisory lock before reading the highest key and create through
`insertAndFlush`, whose `EntityManager.persist` semantics make a duplicate observable instead of
merging over an existing row. Their bounded allocate-and-write retry remains service-owned.

The original wording below is retained because it records the race and why a sequence was rejected;
references to the lock as a custom method declared directly by `TransactionRepository` are superseded
by the fragment placement above.

**Context.** Both allocators reproduce the estate's method of minting a transaction identifier: read the
highest existing key and add one. `service/BillPaymentService` read it with a maximum-value query and
`service/TransactionAddService` with a descending browse. `repository/TransactionRepository` already published
an advisory lock for exactly this purpose and its documentation obliged a caller to take it before the read and
to retry on a duplicate key. Neither allocator did either, so two concurrent turns could read the same maximum
and mint the same identifier.

**The decision.** Both allocators take `lockIdentifierAllocation` on one shared key immediately before the read
and hold it through the insert inside the same transaction, and both carry a bounded retry that re-reads under
the lock when a duplicate key is nevertheless reported. A database sequence was not used and is excluded by the
migration plan: a sequence diverges permanently from the highest-key-plus-one rule after the first gap, and a
gap is guaranteed by the first rolled-back turn.

**A second defect found while fixing the first.** An assigned-identifier save resolves to a merge, so a
colliding insert would have silently overwritten an existing transaction row rather than failing. Both
allocators now probe for the identifier and flush explicitly, which is what makes the retry reachable at all -
without it the duplicate-key condition the retry exists for could not occur.

**How a third allocator is prevented from quietly getting this wrong.** `IdentifierAllocationLockAuditTest`
names the two minting sources and requires the lock to precede the read in each. Adding a third minting caller
fails that audit, and the correct response is to enrol it deliberately with a note here rather than to widen the
audit - because a new source of identifiers is a concurrency decision, not a formatting one.

*Cited by:* `service/BillPaymentService.java`, `service/TransactionAddService.java`,
`repository/TransactionRepository.java`, `repository/IdentifierAllocationLockAuditTest.java`,
`repository/TransactionRepositoryIT.java`.

---

### DL-232 - A submission identity is minted per request and reused only on an explicit retry, and each card carries a reassembly envelope, because a date-derived identity silently discarded a legitimate second submission — CORRECTED

> **Formerly recorded under `DL-150`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-150` is the one the module's own source cites. See entry DL-245.

**Correction — integrated state, 2026-08-06.** The nonce-backed identity and per-message reassembly
envelope remain current, and so does the supersession of the process-local-only interleaving decision
below: the production submission coordinator opens a new PostgreSQL transaction and takes a
transaction-scoped advisory lock, so one whole card stream is published as the only such publication in
the deployment. What is NOT current is the persistent delivery state a previous revision of this note
described. `JobSubmissionOutbox` and its table are removed; a call publishes only its own cards, once
each, and stops at its own first refusal - see DL-148 for the reasoning. The message attributes therefore
carry their original weight again: they let a consumer reassemble a stream whatever order it arrives in,
alongside the cross-replica exclusion the advisory lock supplies.

The original paragraph that excludes distributed coordination is retained as the intermediate design that
preceded the database-backed coordinator.

**Context.** The report-request turn publishes a seventeen-card job image to a first-in-first-out queue, one
card per message, reproducing the estate's queue write. The deduplication identity was derived from the
requested date range alone, and the class documentation claimed it carried a nonce - it did not. Two
consequences followed, in opposite directions. A legitimate second request for the same period inside the
queue's five-minute deduplication window was discarded by the broker and reported to the caller as success. A
delayed partial retry after that window duplicated the prefix that had already landed.

**The decision on identity.** The identity is minted per request as the range plus a thirty-two-character
random nonce, bounded at minting so it cannot exceed the broker's limit, and it is returned on the submission
result so that a retry is possible at all. Reuse is available only through the explicit entry point that takes
an identity, which is what a retry is. A same-period second request is therefore a second submission - which is
also the closer reading of the estate, whose queue write appended unconditionally.

**The decision on interleaving, and what it deliberately does not add.** The publishing lock is process-local,
so two instances can interleave two seventeen-card streams in one message group. A distributed lock or a single
active publisher would need a coordination service, and the migration plan excludes adding one. Instead every
message carries three attributes - the submission identity, the one-based card ordinal and the card count - so a
consumer reassembles a submission atomically regardless of interleaving. The eighty-byte card payload, the
one-card-per-message shape, the single message group, the append ordering and the non-fatal failure semantics
the estate's errors-ignored queue specified are all unchanged; only the envelope is new, and it is a reviewed
widening of what crosses the bridge, enrolled by name in the two guard tests that police that boundary.

**Six tests that encoded the defect were rewritten rather than relaxed,** each carrying a note of what it used
to assert and why that reasoning failed - the clearest being a test that asserted two identical requests
produced one identity, which was the discarded-submission bug expressed as an expectation.

*Cited by:* `service/JobSubmissionService.java`, `service/ReportRequestService.java`,
`service/JobSubmissionServiceTest.java`, `service/JobSubmissionServiceIT.java`.

---

### DL-233 - A diagnostic publishes a classified failure chain and never a throwable, and never a primary account number, because the console the estate wrote to and the log this module writes to are not the same surface

> **Formerly recorded under `DL-151`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-151` is the one the module's own source cites. See entry DL-245.

**Context.** The estate's only diagnostic channel was its console display statements — **215** of them,
counted as statement-initial lines; DL-253 records why the specification's 217 is two too many and how the
count was taken — read by an operator inside the same security boundary as the data. This module's diagnostics are structured events that are aggregated, forwarded
and retained outside that boundary. Two habits carried across from the estate and stopped being safe in the
crossing.

**The decision on throwables.** Thirty-four diagnostics handed a caught throwable to the logger, which renders
its message and frames - the message being where a data layer quotes the connection string it failed on and a
driver quotes the statement and its bound parameters. All thirty-four now report authored context plus
`util/FailureDiagnostics.failureChainOf`, which composes the chain of failure TYPES and reads no message, no
frame and no suppressed throwable. The legacy diagnostic constant remains the leading token of each message so
operator log-matching still works. The review named twenty of the thirty-four; the remainder were found by
scanning for the pattern rather than working the list, and fixing only the named ones would have made the guard
below worthless.

**The decision on the primary account number.** Four diagnostics wrote a card number in full. Each now emits the
module's existing fixed stand-in. A partial mask was rejected, as it has been four times before in this module: a
fragment of a sixteen-character numeric key is recoverable by enumeration, so a truncated primary account number
is still cardholder data. What is deliberately NOT withheld is the daily-transaction, account and customer
identifier that accompany them - they are the keys that make a rejected transaction traceable, the estate
protects none of them, and withholding them would leave the batch tier undiagnosable in exchange for nothing.
The card number likewise stays in the 133-byte report record, which is a file record under a byte-parity
obligation rather than a diagnostic.

**Why a guard and not a review.** Both defects are properties of a call site, introduced by writing one
ordinary-looking line, and every one of them executes only on a failure path whose log no behavioural test
inspects - which is why a large suite had caught none of them. `DiagnosticConfidentialityAuditTest` scans the
sources, requires both counts to be zero, requires the sanctioned reporting form to be genuinely in use so the
rule cannot be satisfied by deleting diagnostics, and carries self-checks that plant each violation and require
the detector to fire. One argument is enrolled by name: a negated presence predicate over a browse key, whose
value is a boolean.

*Cited by:* `util/FailureDiagnostics.java`, `DiagnosticConfidentialityAuditTest.java`,
`service/DailyTransactionReadService.java`, `service/TransactionReportService.java`.

---

### DL-234 - The local stack binds every published port to the loopback interface and pins every image it does not build, because the throwaway credentials and the network exposure are one decision

> **Formerly recorded under `DL-152`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-152` is the one the module's own source cites. See entry DL-245.

**Context.** The local validation stack publishes eight ports and is deliberately full of readable values - a
database password written into the file, a dashboard credential left at the tool's own documented default, and
a token signing secret committed in the local profile - so that a developer can bring it up and sign on with
nothing prepared. None of those values is reproduced on this page: this log names the *fact* of a readable
credential and never the credential. Every mapping omitted the host
address, which binds every interface on the machine. On that binding the fixtures stop being fixtures: the
database answers any peer that can route to the host, the dashboard admits anyone who has read the repository,
and - the one that is authority rather than information - the application signs bearer grants with a published
secret, so a peer can mint an administrator grant and reach the batch-control surface DL-148 had just gated.

**The decision.** Every mapping is written `${SERVICE_BIND_ADDRESS:-127.0.0.1}:${SERVICE_PORT:-n}:n`. Loopback
is what an unqualified bring-up produces; widening is per-service, so widening one leaves the rest closed; and
the obligation that comes with widening - generate the database password, the dashboard password and above all
the signing secret - is stated at the point of the decision and in the module README, because Compose has no
conditional and cannot enforce a pairing. `LocalValidationStackExposureTest` fails the build if any mapping
loses its default. Verified at runtime, not only in the file: the application answers on the loopback address
and the host's own routable address refuses the connection.

**The diagnostic services are gated by reachability, not by start-up.** Each takes its own bind address, so
reaching Jaeger, Prometheus or Grafana from elsewhere is an opt-in exactly as it is for the database. They are
deliberately NOT put behind a Compose profile that would stop them starting: the performance baseline is read
from the meter set Prometheus scrapes, so a default bring-up producing no scrape would make a delivered gate
unobtainable by the documented command.

**Image pinning, and the one image that cannot be pinned.** The five external images now carry a digest
alongside the tag, matching the form the two build stages and the continuous-integration actions already use, so
an upstream republish of a tag cannot change what the gates were validated against without appearing here as a
diff. The application image is built from this module's own Dockerfile inside the same stack, so its digest does
not exist until the build that produces it has run; its inputs are pinned instead, and it is enrolled by exact
text in the guard so that a different unpinned image still fails.

*Cited by:* `docker-compose.yml`, `src/main/resources/application-local.yml`,
`LocalValidationStackExposureTest.java`, `README.md`.

---

### DL-235 - Caller-selected record identifiers require a declared CardDemo operator authority

> **Formerly recorded under `DL-145`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-145` is the one the module's own source cites. See entry DL-245.

**Context.** The account view and update, card list/detail/update, transaction list/view/add and bill
payment surfaces all accept a business identifier from the caller. Their services resolve that
identifier directly against the corresponding repository. The user-security record identifies a
person and a one-character user type; it carries no customer, account, card or transaction identifier,
and no program in the estate joins a sign-on identity to one. Treating a caller-supplied identifier as
proof of ownership would therefore be the vulnerability, while inventing an ownership table would be a
new business rule with no legacy authority.

**Decision.** The four controller roots are an explicit online-data operator surface. The filter chain
admits exactly the two primary authorities derived from the two user types the estate declares:
`ROLE_ADMIN` and `ROLE_USER`. It does not rely on bare authentication for those roots. Both legacy user
types retain the access they had, including the administrator path licensed by the protected-data
adapter, while an unrelated authenticated principal such as a future audit or service identity receives
forbidden rather than inheriting record-wide access from the closing catch-all.

**Why no third user type or client-carried entitlement is introduced.** A synthetic operator type would
alter the fixed one-character user-security layout, and a request flag would merely let the caller grant
itself access. The authority set is server-owned, immutable and assembled from the existing primary
authorities. Controller mappings remain the one home of their path constants; the security rule imports
those constants and matches both each root and every descendant, so a new operation under an existing
data controller is protected before its handler is written.

**What is verified.** The real filter chain is driven with an identity supplied directly rather than minted
from a token, because the chain's own bearer filter can only ever establish one of the two declared
authorities. Tokens and identities for both declared user types receive success on an ordinary data route;
no credential receives unauthorized; an identity carrying only an unrelated authority — and an identity
carrying no authority at all — receives forbidden. The narrower rules ahead of the allow-list are driven in
the same pass, so the ordering that keeps the sign-on route anonymous and the administrative and
batch-control prefixes administrator-only is asserted rather than assumed. The controller contracts also
publish the forbidden outcome, so the machine-readable interface does not claim that authentication alone
is enough.

> **Corrected, and the correction is a behaviour change rather than a rewording. See DL-270.** The
> decision recorded above is the decision that was taken; it was not the rule that had been installed. The
> chain closed with `anyRequest().authenticated()` and named no authority for any business route, so the
> four data roots this entry describes were in fact answered by the closing catch-all — the very outcome
> the third paragraph says the module "does not rely on". An unrelated authenticated principal would
> therefore have inherited record-wide access, and the paragraph beginning "What is verified" described a
> verification that no test performed. The rule now exists, and it is **broader** than this entry
> describes: rather than four rules over four controller roots, one rule governs the API root and every
> descendant, so the four data roots are covered along with every other business surface, present and
> future. The reasoning above — why the authority set is server-owned, why no third user type is minted,
> why a client-carried entitlement would be self-granted — is unchanged and is the reasoning DL-270 acts
> on. The "What is verified" paragraph above has been rewritten to state what the delivered tests
> actually drive.

*Cited by:* `config/SecurityConfig.java`, `api/AccountController.java`,
`api/CardController.java`, `api/TransactionController.java`,
`api/BillPaymentController.java`, `config/SecurityConfigTest.java`.

---

### DL-236 - The three card turns carry complete screen state in bounded bodies, reconcile identity from authentication, and seal the update before-image on the server

> **Formerly recorded under `DL-146`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-146` is the one the module's own source cites. See entry DL-245.

**Context.** The three card routes were individually callable, but their pseudo-conversational
contracts were not complete. The list route discarded three values from the program's retained
paging area and consequently treated every request as the first page. The detail route put a full
card number and the echoed communication area in a GET request target. The update route echoed a
client-provided concurrency token without opening it and handed no change action or before-image to
the service. All three routes also passed the communication area's user identifier and user type
straight through, even though those two members came from the client. Each defect had the same root:
state that CICS kept outside the operator's control had been translated as ordinary client-authored
input without recreating the original trust boundary.

**The list decision.** The CCLI paging area is five values, not merely two cursor keys and a
direction. The request now carries the cursor pair and direction in `PageCursorRequest`, plus the
three-character retained page number, the last-page-already-shown flag and the next-page-indicated
flag. The boundary converts the page text to the integer the service counts with and otherwise
passes every value through unchanged. An absent, blank or unreadable page value becomes page one,
which is the same state the program initializes on first entry; no clamping or page arithmetic is
performed at the boundary. The service returns the last-page flag beside `PageMetadata`, and the
response publishes it so a second forward press at the end can produce the distinct no-more-pages
outcome instead of repeating the first end-of-data response.

**The list header decision.** COCRDLIC writes both title lines, the transaction and program names,
and the current date and time on every send. Those six values therefore belong to the service
result, not to a transport mapper guessing what the screen probably showed. `CardListService` takes
an injected `Clock`, reads it once per send, and assembles `MM/DD/YY` and `HH:MM:SS` with the
module's locale-neutral fixed-width primitive. The controller publishes the returned header
component for component. This brings the list screen into the same model as the detail and update
screens and prevents a timestamp from being fabricated at a layer that cannot be tested against the
turn that produced it.

**The detail decision.** CCDL remains idempotent in effect, but it is submitted with POST rather
than fetched with GET. Its card filter is a primary account number, and its navigation record
contains further cardholder identifiers; putting either in the request target exposes it to proxy
and gateway access logs, browser history and referrer handling before the application's own safe
logging policy can act. `CardDetailRequest` carries the two optional filters, the key and the
navigation record in the body. The filters remain optional because the program has distinct
outcomes for no account, no card and no input. Their eleven- and sixteen-character map widths are
declared as validation bounds, so an impossible terminal value is rejected rather than silently
truncated by the service's defensive receive logic. The former GET surface is not retained as an
alias.

**The update decision.** The change action and `CarriedCardImage` are one continuation and are
sealed together under a binding and scheme distinct from the older record-concurrency proof. The
image is written in its record declaration order: owning account, card number, verification code,
embossed name, expiry year, expiry month, expiry day and active status. A literal count is used in
production because the unsafe-code budget forbids reflection there; a test reflects over the record
to pin that literal to eight and inspects the protected payload to pin the field order. An absent
token is the genuine first-entry state, details-not-fetched with an empty image. Any present token
that fails authentication, carries another binding or scheme, has the wrong part count, names no
declared action or contains an unreadable field marker is a 409 conflict before the update service
runs. Every successful turn seals a fresh continuation from the action and image the service
settled on; the caller's token is never echoed as the next token.

**The identity decision.** Every card route reconciles a supplied `NavigationContext` before the
service sees it. Routing, selected-card and screen members cross unchanged, while `userId` and
`userType` are replaced from the established `Authentication` through the single readers on
`ConversationStateAdapter`. An absent record stays absent because the programs distinguish a
zero-length communication area from an empty one. A forged administrative type in the body
therefore changes no authority and cannot survive into a service as though the server had asserted
it.

**What is asserted.** Tests pin the five list paging values in both directions, the six header
values under a fixed clock, the detail route's body-only surface and exact width rejections, the
continuation's eight-field count and seal order, every continuation refusal arm, token rotation,
and identity reconciliation on list, detail and update. The controller tests additionally prove
that an untrusted continuation produces 409 before the service is invoked and that GET no longer
reaches the detail operation.

*Cited by:* `api/CardController.java`, `api/ConversationStateAdapter.java`,
`api/dto/CardDetailRequest.java`, `api/dto/CardListRequest.java`,
`api/dto/CardListResponse.java`, `service/CardConcurrencyTokenService.java`,
`service/CardListService.java`, and their card controller, DTO, service and continuation tests.

---

### DL-237 - Transaction turns carry one bounded list continuation, derive identity from authentication, and keep entered and persisted amounts distinct

> **Formerly recorded under `DL-147`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-147` is the one the module's own source cites. See entry DL-245.

**Context.** The transaction-list boundary divided one pseudo-conversational screen turn between a
JSON body and three query parameters. The body carried the cursor pair and direction, while the
request target carried the displayed identifiers, an integer page number and the next-page flag.
That split allowed state from different turns to be combined, changed the eight-character page image
to a different wire type, and left the displayed-identifier collection unbounded. The list, view and
add routes also passed the communication area's user identifier and user type through unchanged.
Finally, the add response sourced its amount only from a record that had been written, so validation
and confirmation turns lost the twelve-character amount still visible on the screen.

**The list-continuation decision.** `TransactionListRequest.ScreenContinuation` is the one inbound
shape for every value needed to resume a CT00 page: the first and last transaction keys, direction,
the eight-character page image, the look-ahead result and the identifiers displayed in row order.
The continuation is part of the request body; CT00 accepts no paging query parameter. Each key and
displayed identifier is bounded to sixteen characters, the identifier list is bounded and
constructor-enforced at ten entries, and the page image is bounded to eight characters while
retaining its text form and space padding. The controller converts that validated page image to the
service's arithmetic counter only at the service boundary. `TransactionListResponse` publishes the
same continuation type, built exclusively from the page metadata and rows the service returned, so
the next request can echo one coherent server-produced turn.

**Why the response still carries `PageMetadata`.** `PageMetadata` is the presentation description of
the page just produced: page size, both boundary keys, direction, the independent previous/next
indicators and the displayed image. The continuation is the narrower next-turn input, including the
ten displayed identifiers that positional row selection requires and excluding page size and any
aggregate. Keeping both is not two authorities: the controller derives the continuation from that
same metadata and row list in one method, and tests pin every derived field.

**The identity decision.** CT00, CT01 and CT02 all reconcile an echoed `NavigationContext` through
`ConversationStateAdapter.reconcile` before invoking a service. Routing and screen state remain as
echoed, while `userId` and `userType` are replaced from the established `Authentication`. An absent
communication area remains absent because each legacy program distinguishes that condition. A
caller can therefore echo navigation state but cannot choose the identity or role a transaction
service receives.

**The amount decision.** `TransactionAddResponse.amountEntered` is the twelve-character screen image
from `TransactionAddService.ScreenFields.amount()` and is published on every turn, including the
confirmation prompt and every validation rejection. The existing `amount` remains the exact
two-decimal `BigDecimal` from the persisted transaction projection and remains absent until a write
succeeds. `newTransactionId` follows the same successful-write rule. No boundary reparses the screen
text to manufacture a decimal, so the service remains the sole authority for amount validation and
conversion while the transport faithfully redisplays what the operator entered.

**What is asserted.** Tests require the CT00 handler to expose only the body plus
`Authentication`, require the complete continuation to reach the service and return in the response,
refuse an eleventh displayed identifier, report a seventeenth character at its list index, preserve
the fixed-width page text, and redact every transaction key from diagnostics. Controller tests pass
a forged administrative identity in each transaction route and require the service input to carry
the authenticated standard-user identity instead. CT02 tests require `amountEntered` on both written
and unwritten turns while the persisted amount and generated identifier remain successful-write
only.

*Cited by:* `api/TransactionController.java`, `api/ConversationStateAdapter.java`,
`api/dto/TransactionListRequest.java`, `api/dto/TransactionListResponse.java`,
`api/dto/TransactionAddResponse.java`, and their transaction controller, DTO and service tests.

---

### DL-238 - Account and bill-payment turns derive identity from authentication, and payment account selection comes only from the screen field

> **Formerly recorded under `DL-148`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-148` is the one the module's own source cites. See entry DL-245.

**Context.** The account-view, account-update and bill-payment boundaries accepted a
client-echoed `NavigationContext` and passed it to their services unchanged. That record contains
the signed-on user identifier and user type, so a caller could make a service observe an identity
the authentication chain had never established. Bill payment carried a second trust-boundary
defect: on first entry it nominated the account to read from the account identifier inside that
same echoed record, even though the screen already supplies a separately bounded eleven-character
account field. CICS owned the communication area; an HTTP client does not, so neither identity nor
row authority can be inherited from it.

**The account decision.** Both account routes reconcile navigation state through
`ConversationStateAdapter.reconcile` before invoking a service. The view route passes the
reconciled record directly. The update route uses
`AccountUpdateRequest.withNavigationContext` to make a mechanical copy of all forty-six request
components while replacing only navigation state, so the controller cannot accidentally alter a
screen field, attention key or concurrency token while establishing identity. An absent
communication area remains absent. When one is supplied, every routing, selected-record and screen
member remains unchanged while `userId` and `userType` come from the established
`Authentication`.

**The bill-payment decision.** `BillPaymentController` performs the same identity reconciliation
before constructing `BillPaymentScreenInput`. `BillPaymentService` nominates an account only from
that input's explicit `accountId` field. The service still reloads the nominated account from
`AccountRepository` before any balance or write decision, but an account identifier present only
in navigation state cannot cause a lookup. The navigation account member remains available as
retained screen state; it is simply not an authorization or row-selection source.

**What is asserted.** Account controller tests submit forged administrative identities on both
routes and require the service inputs to carry the authenticated standard-user identity while
retaining the non-identity fields. Bill-payment controller tests drive the real servlet binding
with an authenticated principal and require the same replacement. Bill-payment service tests
require the explicit screen account to win over a different echoed account and require an echoed
account by itself to produce no repository access.

*Cited by:* `api/AccountController.java`, `api/BillPaymentController.java`,
`api/ConversationStateAdapter.java`, `api/dto/AccountUpdateRequest.java`,
`service/BillPaymentService.java`, and their account and bill-payment controller, DTO and service
tests.

---

### DL-239 - The HTTP boundary denies browser origins by default and applies one finite request budget in every profile

> **Formerly recorded under `DL-149`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-149` is the one the module's own source cites. See entry DL-245.

**Context.** The delivered API had field-level bounds but no coherent middleware budget. No CORS
policy was registered, the security chain did not process one, and no shipped configuration stated
limits for the request line, headers, body, form parser, parameter count or rejected-body discard.
That left browser admission implicit and let transport work grow independently of the bounded DTO
that eventually received it.

**The CORS decision.** Every application operation is beneath `/api/**`, so that is the one CORS
mapping. `carddemo.web.cors.allowed-origins` is an empty value in the shared baseline and therefore
an explicit deny-all exact-origin list in every shipped profile. A deployment may supply up to
sixteen comma-separated HTTP or HTTPS origins. Each must contain only scheme, host and an optional
valid port; wildcards, the opaque `null` origin, patterns, paths, queries, fragments and user
information are refused at start-up. The admitted methods are GET and POST, the admitted request
headers are Accept, Authorization and Content-Type, the Authorization response header is exposed,
and credentials remain false. `SecurityConfig` enables its CORS integration against the same
configuration source so a permitted preflight is handled before bearer authentication rather than
being rejected by the authenticated catch-all.

**The request-budget decision.** The shared baseline, inherited unchanged by local, test and
production, caps the combined request line and header block at 8 KiB, form content at 64 KiB,
parsed query-plus-form parameters at 64, rejected-body swallowing at 64 KiB, and request-line
delivery at ten seconds. Multipart parsing is disabled because no upload operation exists. A
separate 64 KiB request-body filter is necessary because the embedded container's form limit does
not govern JSON. `RequestBodyLimitFilter` runs before security and MVC, reads at most one byte beyond
that ceiling, covers bodies with either a declared length or chunked transfer, returns a bounded
repeatable servlet request, and answers 413 before controller invocation when the ceiling is crossed.
The configurable body ceiling is itself restricted to the range from one byte through 16 MiB so a
misconfiguration cannot turn the bounded copy into an unbounded allocation.

**What is asserted.** Configuration tests resolve the same limits under all three runtime profiles
and prove that neither packaged nor suite-only overlays widen them. MVC tests inspect the installed
deny-all and exact-origin policies, reject unsafe origin forms and unsafe body-size settings, cover
declared-length and chunked excess bodies, and drive an oversized JSON body through the real servlet
pipeline to a 413 response with zero controller invocations. Security tests prove that the
shipped empty origin list refuses a preflight and that one exact configured origin receives its
preflight without a bearer token.

*Cited by:* `config/WebMvcConfig.java`, `config/SecurityConfig.java`,
`src/main/resources/application.yml`, and their MVC, security, profile-baseline and profile-startup
tests.

---

### DL-240 - The served OpenAPI document derives nineteen operations and one shared typed error vocabulary

> **Formerly recorded under `DL-150`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-150` is the one the module's own source cites. See entry DL-245.

**Context.** The standalone OpenAPI metadata bean still described an empty milestone after all
seventeen screen-derived operations and two batch-control operations had been delivered. Most
controllers declared response descriptions without a machine-readable success schema, the sign-on
response mentioned its bearer header only in prose, and controller errors did not consistently bind
their status codes to `ErrorResponse`. Tests inspected the standalone bean but never fetched the
document after Springdoc merged the annotated controllers, so a stale description and an empty or
partially typed route inventory could pass together.

**The component decision.** `OpenApiConfig` continues to hand-maintain no path. It now states the
delivered split of nineteen operations and registers all thirty-two top-level request and response
types as derived schemas. It also registers one `BearerAuthorization` response-header component and
six reusable responses for 400, 401, 403, 404, 409 and 500. Every reusable response carries
`application/json` content whose schema is the single `ErrorResponse` component. No credential or
specimen token is published, and examples were removed from the batch response DTOs so adding those
types to the complete schema roster did not create a credential-like sample value.

**The operation decision.** Every controller applies the same six reusable errors at its type
boundary, so every one of its operations publishes the complete safe error vocabulary. Every
successful operation either asks Springdoc to derive its schema from the Java return type or, for
the two batch operations, names its typed response explicitly. Spring request-body and parameter
types remain the source of the input schemas. The sign-on success response references the shared
Authorization header and explicitly clears the document-wide bearer requirement; every protected
operation inherits that requirement. The bearer-scheme prose names both administrator-only regions,
`/api/admin` and `/api/batch`, from the enforcing security constants rather than repeated literals.

**What is asserted.** A real MVC slice starts all nine controller classes with mocked business
collaborators, fetches `/v3/api-docs`, and requires the exact set of nineteen method-and-path pairs.
For every operation it requires a typed 200 response, typed request body or parameters, and all six
error response references. It resolves each shared response through
`#/components/schemas/ErrorResponse`, requires the sign-on Authorization-header reference, and
requires the sign-on operation's security array to be empty. Standalone tests independently hold
the description, thirty-two-family schema roster, shared components, bearer scheme and credential
containment.

*Cited by:* `config/OpenApiConfig.java`, every `api/*Controller.java`,
`api/dto/BatchJobExecutionResponse.java`, `api/dto/BatchJobLaunchResponse.java`,
`config/OpenApiRouteContractTest.java`, the OpenAPI configuration tests, and
`api/dto/WireVocabularyContractTest.java`.

---

### DL-241 - Authentication and abend diagnostics publish fixed outcomes, never caller-carried identities or program text

> **Formerly recorded under `DL-151`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-151` is the one the module's own source cites. See entry DL-245.

**Context.** The sign-on service and controller wrote the operator identifier into rejection,
admission and session-issuance records. The identifier was bounded to the eight-character screen
field but could still contain a control or format character, so those records exposed identity data
and allowed one request value to change the shape of a log event. The REST abend handler had the
same structural problem: it logged the caller-carried culprit program field even though the
navigation layer deliberately treated that field as untrusted text.

**The authentication decision.** Every credential outcome now has a fixed diagnostic vocabulary.
Missing input, unknown identifiers, unclassifiable stored roles and failed secret comparisons log
only a stable rule code. Admission may log the resolved user type and route because both are
server-derived enumerated values, but it never logs the operator identifier. Session issuance logs
only `outcome=issued`; neither the identifier nor the bearer token is included. The identifier
field rejects Unicode control and format categories before service invocation, closing the
line-shaping path while preserving the legacy blank, width and case-handling cascade. The password
remains write-only and retains its existing screen semantics; it is never logged.

**The abend decision.** `GlobalExceptionHandler` omits `AbendException.culprit()` completely. The
bounded culprit is not made safe merely by its width and may still contain a line break. The event
retains the module-defined abend code, reason, message and sanitised failure-type chain, which are
sufficient for diagnosis without reintroducing caller-carried program text.

**What is asserted.** Service tests exercise every credential outcome through a Logback list
appender and require the fixed rule or outcome while excluding the identifier and line controls.
Controller tests prove that a control-bearing identifier is rejected before repository lookup and
that an issued session logs neither identity nor token. Handler tests send an abend whose culprit
contains a line break and require one error event containing the code and reason but no culprit or
control character. DTO tests cover control and format characters independently from the existing
eight-character bound and JSON write-only credential contract.

*Cited by:* `service/AuthenticationService.java`, `api/AuthController.java`,
`api/GlobalExceptionHandler.java`, `api/dto/SignOnRequest.java`, and their authentication,
controller, exception-handler, DTO-boundary and JSON-contract tests.

### DL-242 - The active-job panel reads the exported name, and the meter-collision warning beside it is two framework instrumentation paths colliding, not a defect to suppress

> **Formerly recorded under `DL-152`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-152` is the one the module's own source cites. See entry DL-245.

**Context.** The "Active job executions" panel of the provisioned dashboard queried
`spring_batch_job_active_seconds_active_count`. It returned nothing under every condition, so the panel
read "No data" permanently, and the existing dashboard test could not see it because that test compared
panel names against a hard-coded set of counter names rather than against an exposition.

**What was measured, against the resolved Micrometer 1.15.12 this module builds on.** A `LongTaskTimer`
is exported by the Prometheus registry as a **summary** family - `_count`, `_sum` and `_max`, and nothing
else. There is no `_active_count` suffix at any Micrometer version this module can resolve. For a
long-task timer the `_count` **is** the number of currently active tasks: it reads 1 while one task is
running and 0 once it stops, which is precisely what the panel wanted. The exported tag key is
`spring_batch_job_active_name` - the meter name is folded into it - and not `spring_batch_job_name`, so
the panel's legend format had nothing to interpolate either.

**Decision.** The panel queries `sum by (spring_batch_job_active_name) (spring_batch_job_active_seconds_count{job="$job"})`
with a matching legend format, and its axis label and unit - which had been transposed with the
neighbouring item-read panel - now describe active executions. Two tests hold it, deliberately at
different levels, because each catches what the other cannot. `GrafanaDashboardMetricsContractTest`
asserts **every** metric name in **every** panel target and template query against one real
`PrometheusMeterRegistry.scrape()`, so a name that the exporter does not publish fails the build in
either direction. `MonitoringQueriesIT` then executes the shipped expression against a real Prometheus
server and requires one populated series per job name, each carrying the label the legend interpolates -
because a grouping label that drifts by one character still returns a series, just an unlabelled one that
renders as a blank legend entry, and no name-comparison test can see that.

**The warning that appears beside it, and why nothing suppresses it.** Launching any job logs one
Micrometer warning:

```text
The meter (MeterId{name='spring.batch.job.active', tags=[application, spring.batch.job.name,
spring.batch.job.status]}) registration has failed: Prometheus requires that all meters with the same
name have the same set of tag keys. There is already an existing meter named
'spring_batch_job_active_seconds' containing tag keys [application, spring_batch_job_active_name].
```

Two framework instrumentation paths address one meter name with different tag keys: Spring Batch's own
long-task timer for an active job, and the observation handler's `<observation>.active` timer derived from
the batch observation convention. Neither is application code. The **first** registration is the one that
wins, and it is the one the panel reads - the nine live series prove it - so the collision costs nothing
but the line, and Micrometer itself de-escalates the message to debug after the first occurrence.

It is left alone rather than filtered. The only suppression available is a meter filter denying a meter
that differs from the exported one **only in its tag keys**, and a filter written one character wide of
that distinction removes the exported series instead - which is exactly the class of mistake that made
this entry necessary. A cosmetic log line is not worth reintroducing the defect. The zero-warning
commitment this module holds itself to is a compiler contract enforced by `-Xlint:all -Werror`, and it
excepts framework-generated code; a runtime diagnostic emitted by a framework component is outside it.

> **PARTLY SUPERSEDED by DL-217.** (This entry was recorded under `DL-152` until that identifier was
> de-duplicated; see entry DL-245.) The panel reasoning above still holds and the framework's meter still
> keeps its exported name, so the shipped expression is unchanged. What no longer holds is the conclusion
> that the collision must be tolerated. The objection recorded here was that the only available
> suppression discriminates on tag keys and therefore risks removing the exported series - and that
> objection is sound. DL-217 does not take that route: it keys on the **presence of the observation's own
> tag**, which the framework's meter does not carry, and it **renames** rather than denies, so no meter is
> dropped and no statistic is re-bucketed. The consequence is that the second registration now succeeds
> under a sibling name, the warning no longer appears on any launch, and the job-name-and-status dimension
> that this entry recorded as permanently lost exists after all. The reasoning here is retained because it
> is the reason the fix is shaped the way it is.

*Cited by:* `config/grafana/dashboards/carddemo-overview.json`, and the dashboard metric-name and
executed-query suites that hold it.

---

### DL-159 - A high-severity finding whose fix has not been published is carried as a measured determination, because the two alternatives are a disarmed gate or a red build

**Context.** The supply-chain gate is bound to `verify` and fails at CVSS 7.0. It had been passing with
two sub-threshold findings. A vulnerability-database refresh then produced `CVE-2026-66299` at CVSS 7.5
against the embedded servlet container, and every online `./mvnw verify` began failing:

```text
[ERROR] One or more dependencies were identified with vulnerabilities that have a CVSS score
        greater than or equal to '7.0':
[ERROR] tomcat-embed-core-10.1.57.jar (pkg:maven/org.apache.tomcat.embed/tomcat-embed-core@10.1.57,
        cpe:2.3:a:apache:tomcat:10.1.57:*): CVE-2026-66299(7.5)
```

Nothing in the module caused it. The finding arrived from outside and had to be answered anyway, because
a gate that fails is not a gate that can be left failing.

**The preferred remediation was attempted first and does not exist.** This module already pins
`tomcat.version` **upward**, above the framework's managed floor, as earlier CVE remediation - so the
obvious move was one more property bump. The advisory names 10.1.58 as the fixed release on the 10.1
line (`versionEndExcluding` 10.1.58 in the record) and 11.0.25 on the 11 line. Neither is published:

```console
$ for v in 10.1.58 10.1.59 10.1.60 11.0.25; do curl -sI -o /dev/null -w "$v -> %{http_code}\n" \
    https://repo1.maven.org/maven2/org/apache/tomcat/embed/tomcat-embed-core/$v/tomcat-embed-core-$v.jar; done
10.1.58 -> 404
10.1.59 -> 404
10.1.60 -> 404
11.0.25 -> 404
```

The newest 10.1.x that resolves is the 10.1.57 already pinned. The advisory has been published ahead of
the artifact, which is a state a build has to survive rather than a state it can fix.

**The finding does not describe code this module carries, and that is measured.** The vulnerability is
uncontrolled resource consumption in Tomcat's **WebSocket chat example**, part of the examples web
application shipped in the full server distribution under `webapps/examples`. The advisory says so in
its own text: users who followed the guidance to remove the examples web application are not affected. A
Spring Boot application embeds the container as a library and has no `webapps` directory from which an
examples application could be deployed. The archive listings confirm there is nothing there to exploit:

```text
tomcat-embed-core-10.1.57.jar        1681 entries,  0 matching webapps/|examples/|websocket/chat
tomcat-embed-websocket-10.1.57.jar    191 entries,  0
tomcat-embed-el-10.1.57.jar           164 entries,  0
```

The match is on the product-level platform record `cpe:2.3:a:apache:tomcat:10.1.57`, which addresses the
Tomcat product at a version rather than any file inside it. The report carries no evidence tying the
match to the examples application, because the artifact contains none to find.

**Decision.** One narrowly scoped rule in `carddemo-java/owasp-suppressions.xml` records that
determination - the first rule the file has ever carried - and nothing else about the gate changes. The
threshold stays at 7.0, the test graph stays in scope, the scan stays bound to `verify`, and the rule
names one identifier on three named artifacts of one library at one version.

**Why the rule names three jars when one was flagged.** Because the CPE assignment is not stable between
scans, and relying on it would leave the gate failing again for a determination already made. Two runs
six minutes apart over the same graph and the same pinned version attributed the finding differently:
the first named `tomcat-embed-core` only; the second, after `core` was covered, named
`tomcat-embed-websocket`, which had carried the identical pair of Tomcat CPEs all along; a third named
`core` again. `tomcat-embed-el` currently carries no Tomcat CPE at all. Naming all three is admissible
only because the evidence above was taken for each jar **individually**, which is the condition the
suppression file's own rules impose on a multi-artifact entry.

**Why not each of the alternatives.**

- *Lower `failBuildOnCVSS`.* Disarms the gate for every future finding, in order to answer one. It trades
  a bounded, documented exception for an unbounded, undocumented one.
- *Skip the scan, or narrow it back out of the test graph.* "A gate that is skipped by default is not a
  gate", as the property's own comment in `pom.xml` puts it. Narrowing would also hide more than this
  finding, and the scope was widened deliberately once the shaded transport was replaced.
- *Substitute a different embedded container.* Contradicts the plan's pinned dependency inventory, which
  names this coordinate, and rewrites the servlet layer to answer a finding in an examples application
  the module does not deploy.
- *Wait for the upstream release.* Leaves every online build red in the meantime, including CI, which
  turns a real gate into noise everyone learns to step over.

**Two mechanisms keep this entry honest, and both were verified rather than asserted.** The plugin runs
with `failBuildOnUnusedSuppressionRule` set true - a flag that was enabled before any rule existed for it
to police, precisely for a moment like this one. Pointing the rule at a non-matching identifier makes the
build fail:

```text
[ERROR] Suppression Rule had zero matches: SuppressionRule{packageUrl=...tomcat-embed-(core|websocket|el)...}
[ERROR] There are 1 unused suppression rule(s): check logs.
[INFO] BUILD FAILURE
```

So when a patched release is adopted the rule stops matching and the build demands the entry's removal.
That is a sharper trigger than a review date, which would fire on the calendar whether or not anything
had changed, and it is why no entry in that file carries one. Second, the gate remains armed for
everything else: re-running the scan with the threshold lowered to 5.0 on the command line fails the
build on the sub-threshold finding below, proving the determination excludes one named identifier on
named artifacts and nothing more.

**The remaining finding is left visible on purpose.** `CVE-2026-41178` at CVSS 5.3 MEDIUM is reported
against `opentelemetry-semconv` and is not suppressed. It describes baggage-header parsing in
OpenTelemetry **Go**; its CPE carries `go` as the target software and has been matched to a Java
artifact. It sits below the threshold, so it does not gate anything, and hiding a sub-threshold finding
would buy nothing while costing the next reader the chance to re-judge it.

**A stale claim was corrected while writing this.** The Gate 8 section of the module README described a
narrower scan scope - test scope excluded - and two unfixable HIGH findings inside that excluded graph.
Both statements had been superseded: the shaded transport carrying those findings was replaced rather
than excluded, and `dependency-check.skipTestScope` is now `false`, as the property's own comment in
`pom.xml` and `BuildAndCiContractTest` both state. The section now describes the full-scope scan, the one
carried determination and the one sub-threshold finding, so that a suppression is never the quiet part of
a document that claims a clean gate elsewhere.

*Cited by:* `carddemo-java/owasp-suppressions.xml`, the `dependency-check-maven` configuration in
`carddemo-java/pom.xml`, the Gate 8 section of `carddemo-java/README.md`, and
`BuildAndCiContractTest`, which holds the shape of all three.

---

### DL-243 - The category-balance report's edit mask prints every digit position it declares, so a zero balance is nine zeros and not a blank field

> **Formerly recorded under `DL-159`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-159` is the one the module's own source cites. See entry DL-245.

**Context.** The report step of `app/jcl/PRTCATBL.jcl` reprojects the balance under
`EDIT=(TTTTTTTTT.TT)` at lines 53 to 56. The first translation rendered that mask the way the module's
other amount masks render theirs: leading integer zeros suppressed to blanks, and a value of exactly
zero blanking the whole twelve-character field.

**The decision, and why the earlier reading was wrong.** In the external sort's edit vocabulary the two
digit selectors are not interchangeable. `T` is a digit position that is always printed; `I` is the
selector that replaces a leading zero with a blank. This specification is written entirely from `T` and
uses `I` nowhere, so all nine integer positions and both fractional positions carry a digit for every
value and a balance of exactly zero renders `000000000.00`. The mask is now emitted that way.

**Why the defect was invisible.** Every balance in `app/data/ASCII/tcatbal.txt` and in the seeded
reference data is exactly zero, so aggregate figures, record counts, key ordering and record widths were
all correct under either reading - the only observable difference was up to nine bytes per line of a
fixed-width external dataset. That is precisely the class of defect a byte comparison catches and nothing
else does, and it is why the fifth contractual output width needs golden bytes rather than a width
assertion. `CategoryBalanceReportJobConfigIT` now asserts the exact twelve characters for a 900.00
balance, for a 0.00 balance and for a 0.05 balance, and additionally that no line carries a blank
anywhere inside the mask.

**What is unchanged.** The 40-byte record length, the eight-byte trailing filler resolved against the
reprojection's own nine-blank declaration, the absence of a sign character, and the zoned-decimal key
ordering are all separate decisions and none of them moved.

*Cited by:* `batch/CategoryBalanceReportJobConfig.java`. Proven by
`batch/CategoryBalanceReportJobConfigIT.java` against a real database.

---

### DL-160 - The COSTM01 statement-work projection has exactly one offset authority, because two agreeing copies are the defect

**Context.** `util/StatementWorkRecordMapper` declares itself the sole authority for the 328-of-350-byte
reprojection that `app/jcl/CREASTMT.JCL` specifies. `util/TransactionRecordMapper` nevertheless carried a
complete second implementation of the same projection: fourteen `STATEMENT_WORK_*` constants, a
projector, two parsers, a key reader and its own geometry self-check. The statement job and the tests
used both, interchangeably.

**Why agreeing copies are worse than disagreeing ones.** The two implementations produced identical
bytes, so nothing failed and nothing looked wrong. That is the hazard: either copy could have been
changed alone - a width corrected, an offset tidied - and the build would still have compiled and the
tests that happened to use the other copy would still have passed. A duplicated record layout has no
mechanism that forces the copies to stay equal.

**The decision.** The statement-work surface is removed from `TransactionRecordMapper` entirely, which
now publishes only the canonical 350-byte layout the projection reads *from*. The two members
`StatementWorkRecordMapper` was missing - the buffer-with-offset decode overload and the named
processing-timestamp truncation width - were added there, and the geometry self-check moved with the
layout it describes. Production and tests now route through `StatementWorkRecordMapper` exclusively.

**How a re-introduction is caught.** `util/StatementWorkRecordMapperTest` asserts that no method or
constant of `TransactionRecordMapper` names the statement-work layout. That assertion needs reflection,
which is why it lives in a test: the unsafe-code audit scopes reflection counting to `src/main/java/**`
precisely because asserting the *absence* of a member is not expressible any other way.

*Cited by:* `util/StatementWorkRecordMapper.java`, `util/TransactionRecordMapper.java` and
`batch/CreateStatementJobConfig.java`. Proven by `util/StatementWorkRecordMapperTest.java`.

---

### DL-161 - The transaction-category-balance composite key is one codec, because four private copies of a key layout cannot be held equal

**Context.** The `TCATBALF` cluster is keyed on a contiguous 17-byte run at offset 0 - account
identifier 11, type code 2, category code 4. Every keyset cursor over the cluster carries that whole run
as one value and has to split it back into three parts to resume. Four call sites needed exactly that,
and each had grown its own private pair of helpers plus its own pair of key-width constants: the interest
service, the interest job, the file-maintenance service and the category-balance report job.

**The decision.** One authority, `util/TransactionCategoryBalanceKeyCodec`, renders a row or three parts
as a key image and slices an image back into parts. It takes its widths from
`util/TranCatBalRecordMapper`, so the key layout is still derived from the record layout rather than
restated, and it publishes the low-value cursor as a named constant so that four call sites no longer
each write a bare `""`. The eight duplicated width constants and eight duplicated helper methods are
deleted.

**Two properties that are decisions rather than conveniences.** A short image is legal and each accessor
returns an empty part for a part the image does not reach, because that is exactly what an initial cursor
position means and padding would invent a lower bound the caller never stated. And the category part is
deliberately *not* truncated to its declared width: a stored category code is a zoned-decimal field whose
final byte may carry an overpunched sign, and the after-key predicate compares whatever three parts it is
given, so cutting the tail here could drop a byte the store produced.

*Cited by:* `util/TransactionCategoryBalanceKeyCodec.java`,
`service/InterestCalculationService.java`, `service/FileMaintenanceService.java`,
`batch/InterestCalculationJobConfig.java` and `batch/CategoryBalanceReportJobConfig.java`. Proven by
`util/TransactionCategoryBalanceKeyCodecTest.java`.

---

### DL-162 - The credential repository is closed again, and the administrative list reads a projection that has no credential column in it

**Context.** `repository/UserSecurityRepository` had regressed to an empty
`JpaRepository<UserSecurity, String>`. Two consequences followed from that one line. Every row the
administrative list displayed arrived as a full entity, so ten BCrypt digests were hydrated per page for
a screen that shows an identifier, two names and a one-character type - digests that then live in the
heap, in any dump taken from it, and in anything that serialises an entity by reflection. And the whole
`JpaRepository` surface became reachable on a table of credentials: an unbounded `findAll()`, a
`deleteAll()`, a `saveAll()`, a lazy `getReferenceById` and the entire query-by-example API, none of
which any caller in this module uses and none of which the legacy tier has a counterpart for.

**The decision.** The interface extends the marker `Repository` and publishes exactly nine operations:
two entity-returning keyed reads for the paths that genuinely need a digest - the plain `findById` for
sign-on verification and the update that carries an unchanged credential forward, and `findByIdForUpdate`,
which takes the same read under the hold both maintenance transactions issue - and seven operations that
never select the credential column. The list projection `AdminEntry` is closed: four getters, no credential accessor, and
therefore a generated select that does not name `sec_usr_pwd` at all. The entity keeps its digest behind
`credentialDigest()` rather than a bean-property getter, which is what stops a projection from binding it
by accident.

**Why a closed interface rather than a convention.** "No caller uses `deleteAll`" is a claim a reviewer
has to check. "`deleteAll` does not exist" is a compilation failure at the call site. The narrower
surface moves the guarantee from review to the compiler, which is the only place it holds without
vigilance.

*Cited by:* `repository/UserSecurityRepository.java` and `service/UserManagementService.java`. Proven by
`repository/UserSecurityRepositoryIT.java`, whose frozen-contract nest asserts the exact nine published
operation names, the single nested type, and the four projected accessors.

---

### DL-163 - Every retained-key browse reads by key, because an offset page is a correctness defect and not merely a slower one

**Context.** Three screens browse a keyed cluster: the card list at seven rows, the transaction list at
ten and the administrative user list at ten. Each legacy program positions on a business key it retained
- the first or last value it displayed - reads forward or backward one record per verb, and stops when a
read runs off the end. All three Java counterparts had been expressed with page indexes or absolute
offsets instead, walking the ordering from its beginning and discarding every row before the cursor.

**Why that is a defect and not a trade-off.** An offset describes a position that a concurrent insert or
delete moves. Between two turns of a pseudo-conversation - and a pseudo-conversation is precisely a gap
between two turns - a row added before the window shifts everything after it, so the next page repeats a
row the operator has already seen or skips one they have not. The legacy browse cannot do that: a row
added at its own key simply appears there, or does not. Cost is the secondary point, though it is real:
paging deeper cost more the further it went, and the user list additionally rescanned offset pages from
page zero on every turn merely to recover its page counter.

**The decision.** Each browse opens with an inclusive read in its own direction - which is what a
browse-start command performs, greater-or-equal forwards and less-or-equal backwards - and continues with
a read bounded strictly past the last key it handed out. Windows are one screen plus one row, which is
also exactly what the legacy reads per page: the page, and one further read to learn whether another page
follows. A full page therefore costs one query rather than one per row, and no query can return more than
eleven rows however large the table becomes. The card list's inclusive open is the inherited keyed read
on its own primary key; the user list's is a projected primary-key seek that avoids hydrating a digest to
answer a boundary question; the transaction list's is a declared less-or-equal or greater-or-equal read.
The user list's page counter is now one range count on the anchor's own key.

**Three properties preserved exactly.** The backward walk still reads descending and still fills its
bottom screen slot first, so the assembled page ascends like a forward page - the descending read order
*is* the reversal, and there is no separate reversing step to get wrong. The forward and backward pagers
still discard the boundary row before filling, and the enter key still discards nothing, so a supplied
filter key is included in its own page. And the three-arm browse response model is untouched: end of
sequence emits its own text and leaves the error flag clear, while only a store refusal raises it.

**One assumption removed rather than added.** The offset forms compared keys in Java against rows the
store had ordered, so they depended on the two orderings agreeing. The keyset forms let the store apply
both the bound and the order, so no key comparison happens in the services at all.

*Cited by:* `service/CardListService.java`, `service/TransactionListService.java`,
`service/UserManagementService.java`, `repository/CardRepository.java`,
`repository/TransactionScanRepository.java` and `repository/UserSecurityRepository.java`. Proven by
`repository/CardBrowseRepositoryIT.java`, `repository/TransactionRepositoryIT.java`,
`repository/UserSecurityRepositoryIT.java` and the three service test classes.

---

### DL-164 - The lowest-base-key rule of a non-unique alternate index is restored to the repository, undoing a regression away from DL-121

**Context.** DL-121 settled this question already: a keyed `READ` of a duplicate-bearing VSAM alternate
index returns the first record in ascending *base*-key order, that is deterministic in the legacy and
must be deterministic here, and both paths therefore publish a bounded, explicitly base-key-ordered
finder. Those finders were present in the delivered module and were then removed, and seven services -
five over `CXACAIX` and two over `CARDAIX` - each grew a private replacement: materialise every row of
the account, then select the minimum, under an identical twenty-line helper with identical Javadoc.

**Why the regression matters, restated from DL-121 and sharpened by what it produced.** DL-121's
argument stands unchanged: an unordered list whose head a caller takes makes the result depend on plan
shape, insertion history and whether a vacuum has run, which is non-determinism in exactly the case where
determinism is the contract. The seven copies added two further faults. The rule could be corrected in six
places and missed in the seventh with nothing failing. And each copy paid for rows it discarded - an
account with several cards fetched all of them to use one - over an index whose entire purpose is to
represent that an account may have several.

**The decision.** `CardCrossReferenceRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc` and
`CardRepository.findFirstByCardAcctIdOrderByCardNumAsc` are restored, bounded to one row and ordered
explicitly, and the seven private helpers are deleted. The list-returning finders remain beside them,
because they answer a different question - which rows does this account carry - and the non-unique index
exists precisely because the two questions differ. A caller reproducing a keyed read uses the
ordered-first form; a caller that genuinely wants every row uses the list.

**What moved and what did not.** The selection rule moved back; its content never changed. The ordering
is ascending on the base key, applied to the raw sixteen-character value with no trim and no numeric
conversion, and an empty result is the legacy not-found condition that each service reports with its own
screen's text.

*Cited by:* `repository/CardCrossReferenceRepository.java`, `repository/CardRepository.java`,
`service/AccountUpdateService.java`, `service/AccountViewService.java`, `service/BillPaymentService.java`,
`service/InterestCalculationService.java`, `service/TransactionAddService.java`,
`service/CardDetailService.java` and `service/CardUpdateService.java`. Proven against real SQL by
`repository/CardBrowseRepositoryIT.java`, which asserts that the ordered-first read and the minimum of
the list agree while both finders remain available.

---

### DL-165 - Two repository methods with no production caller were removed rather than left as competing contracts

**Context.** `TransactionRepository.findByProcessingDateRange` returned an unbounded `List` for the
report's date-range selection, and `CardRepository.findByCardAcctId(String, Pageable)` returned an offset
page of an account's cards. Neither had a production caller. The report filtered its range through its own
reader, and the card screens read either the base cluster by card number or the account path as a list.

**Why an unused method is not harmless here.** Both described a selection that production performs
somewhere else, with different boundedness in one case and different ordering in the other. A later reader
comparing the two would have no way to tell which was authoritative, and the obvious "tidy-up" - routing
the report through the declared query - would have replaced a bounded reader with an unbounded list, on
the one table in the schema with no upper bound on its row count.

**The decision.** Both are deleted, and the interface Javadoc says so and says why, so that the absence is
a recorded decision rather than an omission an author might helpfully repair. The tests that exercised
them are replaced by tests of the paths production actually uses: the bounded keyset reads of the
transaction scan view, and the ordered-first account finder of the card master.

**This supersedes one paragraph of DL-121.** DL-121 retained `Page<Card> findByCardAcctId(String,
Pageable)` on the reasoning that it was the browse translation the migration plan names literally, kept
for genuine browse consumers. No genuine browse consumer materialised: the card-list screen browses the
*base* cluster by card number and filters by account after the read, exactly as DL-121 itself records, and
that browse is now served by the keyset finders of DL-163. An offset page retained for a caller that never
arrived is the competing contract this entry exists to remove. Everything else in DL-121 stands, including
its decision to leave the two supporting indexes un-widened.

*Cited by:* `repository/TransactionRepository.java` and `repository/CardRepository.java`. Proven by
`repository/TransactionRepositoryIT.java` and `repository/CardBrowseRepositoryIT.java`.

---

### DL-166 - The bill-payment insert and the account rewrite share one unit of work, because the legacy held the record from its read to its rewrite

**Context.** `app/cbl/COBIL00C.cbl` writes the transaction master at L233 and rewrites the account master
at L235, with the balance computation at L234 between them and **no flag tested between the write and the
rewrite**. Both files are defined to the region with `READINTEG(UNCOMMITTED)`, `RECOVERY(NONE)` and
`JOURNAL(NO)` (`app/csd/CARDDEMO.CSD`), so the region logs neither and backs neither out. The account read
at L343 does take `UPDATE`, and the file is defined `UPDATEMODEL(LOCKING)`.

**The defect this entry records, and it is the second one on this path.** An earlier revision carried one
`@Transactional` over the whole turn, which coupled the two writes in ways the legacy has no analogue for;
the repair split them into two independent units. That repair was right about the unrecoverable files and
wrong about concurrency. Two units meant the insert **committed before the rewrite was attempted**, so a
version mismatch on the rewrite could no longer remove it: four concurrent confirmations of one account
answered `200, 409, 409, 409` and left **four** transaction rows totalling four times the balance while the
balance itself was debited **once**. Reproduced at runtime on three accounts with two, three and four
submissions in flight, each leaving one row per submission. The sequential double submit was correct
throughout, which is what identifies the fault as concurrency-specific rather than a broken arm.

**What the legacy actually did here, and why it never showed this.** `READ ... UPDATE` under
`UPDATEMODEL(LOCKING)` holds the account record exclusively from L343 until the rewrite at L235 releases it.
A second task confirming the same account therefore waited at its own read, and when it proceeded it read
the *settled* balance and took the nothing-to-pay arm at L197-L206. The legacy posted exactly **one**
transaction however many times the payment was submitted. The hold is a lock rather than a log - it excludes
a concurrent writer and undoes nothing - and it is that exclusion, not recoverability, that kept the
transaction master free of duplicates.

**The decision.** L212-L235 run inside **one** `OnlineTransactionBoundary` unit: the allocation lock, the
backward read of the maximum, the increment, the insert, the balance computation and the version-checked
account rewrite. A version that no longer matches the one the read observed rolls the whole unit back, so a
payment answered `409` leaves **no** transaction behind - which is what AAP §0.3.3 prescribes for the
estate's single online rollback, and what the legacy's record hold achieved by other means. The service
still declares no transaction of its own and stays `final`, exactly as `OnlineTransactionBoundary`
documents that a screen service must; every response arm is still applied *outside* the unit, after it has
completed, so a translated arm is the turn's actual outcome rather than a message competing with a rollback.

**One arm keeps the two-unit shape, and it is the arm where nothing was stored.** The source performs L234
and L235 whether or not the write at L233 succeeded, and both files are unrecoverable, so a write the insert
paragraph *handles* - the duplicate arm at L533-L534 or the catch-all at L540-L546 - is still followed by a
rewrite that commits and the account is still settled. A handled refusal stores nothing, so the shared unit
has nothing to protect; that arm applies the refusal's message and then performs the computation and the
rewrite in a unit of their own, which is the only way an insert that has already rolled back can be followed
by a rewrite that commits. The rewrite therefore exists in two forms - one that opens its own unit for that
arm, one that runs inside the shared unit otherwise - over a single shared store, so the re-read, the
version comparison and the flushed versioned update are written once.

**A rewrite failure that is not a conflict now discards the insert, and that is stated rather than implied.**
Inside the shared unit a non-conflict rewrite failure records the source's catch-all arm at L396-L402 and
leaves the unit through a private marker, so both writes roll back and the turn reports *Unable to Update
Account...* rather than a success naming an identifier no row carries. The legacy, with unrecoverable files,
would have kept that transaction row. This is a deliberate divergence in the direction of integrity: the
operator is told the account could not be updated, and nothing partial is left behind. It is reachable only
through a genuine store failure during the rewrite, not through any input.

**The identifier rule is unaffected, and the lock is why.** `TransactionRepository.lockIdentifierAllocation`
is still taken as the shared unit's first statement, before the maximum is read, and is transaction-scoped.
Highest-key-plus-one, the `0000000000000001` seed on an empty table, and the bounded re-allocation are all
unchanged; no sequence, no generated value. What changed is the length of the hold: the lock is now released
after the account rewrite rather than before it, so the serialised section is one keyed read and one update
longer. That cost is accepted and named - AAP §0.2.2 excludes performance tuning from scope - and it buys a
second property worth having: because the lock admits one allocator at a time, the loser of a concurrent
payment reaches its version check *after* the winner has committed, so the conflict is detected
deterministically instead of raced for.

**Two consequences worth naming.** First, only a write's own failure is translated: a failure of the
advisory lock or of the existence probe is not a response to a write, has no arm in the source, and still
propagates, distinguished by a flag raised immediately before the store is called. Second, a failure raised
when the unit *commits* still reaches a response arm, which remains observable because the unit completes
outside the service rather than at the end of the turn.

**A related correctness repair inside the rewrite, retained.** The account is read at L343 outside any unit,
so the instance the turn carries is detached, and handing a detached versioned instance to a save makes the
provider *merge* it - which, for a row deleted in the meantime, inserts it again instead of reporting the
invalid-key condition. The rewrite therefore re-reads the row inside whichever unit it runs in: an absent row
is the source's own not-found arm at L390-L395, a version that no longer matches is the conflict, and the
versioned update the flush issues closes the window between the two. The select costs nothing, because the
merge would have issued the same one.

*Cited by:* `service/BillPaymentService.java`. Proven by the `WriteOrderingAndUnitsOfWork` nest of
`service/BillPaymentServiceTest.java`.

---

### DL-167 - The two administrative maintenance transactions read the record under a write lock and write it in the same unit

**Context.** `app/cbl/COUSR02C.cbl` L322-L331 and `app/cbl/COUSR03C.cbl` L269-L278 both issue
`EXEC CICS READ ... UPDATE`, and each then writes in the same task: the rewrite at COUSR02C L360 and the
delete at COUSR03C L307. **That delete names no record identifier at all**, so the only record it can
remove is the one the read is holding - which is the proof that the hold is load-bearing rather than
incidental.

**The defect this entry records.** The service read the identity in one unit of work, compared the four
editable fields or showed the operator the record, and then wrote in a *later* unit. Between the two,
another administrator could change or remove the same identity; both statements would still succeed, and
the operator would never be told. On the update path that is a lost update of whichever fields the other
administrator had changed; on the delete path it is the removal of a record the operator confirmed in a
form it no longer had.

**The decision.** `UserSecurityRepository` gains a ninth operation, `findByIdForUpdate`, carrying
`LockModeType.PESSIMISTIC_WRITE` - the relational form of the legacy read-for-update. Each maintenance
path now runs its read, its decision and its write inside **one** `OnlineTransactionBoundary` unit, so the
row is held from the read to the write and released when that unit ends. The response arms stay outside
the unit, so a failure raised when the unit commits reports the failure arm instead of leaving a success
text and a raised success flag behind.

**A lock, not a version column.** No version attribute is added to `UserSecurity`. The legacy *prevented*
the interleaving with a hold rather than detecting it afterwards, and a version column would introduce a
conflict outcome that none of these four screens has a message for - so reproducing the hold keeps the
observable behaviour identical while a version column would not.

**Why the display paths keep the unheld read.** The source performs the same paragraph from two places
with one statement, so the enter-key load also takes the hold - but that hold is released when the task
returns at the end of the turn, long before the operator presses the saving key, so nothing about the
screen's behaviour depends on it. Reproducing it would lock a row for the length of a display and
serialise every other reader for nothing. The two call sites therefore read through two forms that share
one arm evaluation, and the tests assert which path uses which.

**Attribution of a failure inside a two-statement unit.** The read and the write of a maintenance step
report through *different* texts, so a failure that escapes the unit is attributed by a flag raised
immediately before the store is called: before it, the read's lookup-failure text; at or after it,
including at commit, the write's own failure text.

**The held read refuses to run outside a unit of work**, rather than silently reading without the lock -
verified against a real server, where it raises `jakarta.persistence.TransactionRequiredException`. That is
the failure direction to prefer, because a silent downgrade restores exactly the lost update this entry
removes.

*Cited by:* `repository/UserSecurityRepository.java` and `service/UserManagementService.java`. Proven by
the `MaintenanceUnitOfWork` nest of `service/UserManagementServiceTest.java` and the `HeldKeyedRead` nest
of `repository/UserSecurityRepositoryIT.java`, whose last test holds the row against a second holder and
then observes it released.

---

### DL-168 - The account-update transaction's three read paragraphs have a third arm, and it is not a variant of the second

**Context.** `app/cbl/COACTUPC.cbl` evaluates each of its three read paragraphs over three arms:
9200-GETCARDXREF-BYACCT at L3664-L3696, 9300-GETACCTDATA-BYACCT at L3714-L3746 and
9400-GETCUSTDATA-BYCUST at L3763-L3795. The catch-all arm of each raises the input error, raises its
filter flag, moves `'READ'` into `ERROR-OPNAME` and the resource literal into `ERROR-FILE`, and moves the
composed `WS-FILE-ERROR-MESSAGE` into `WS-RETURN-MSG`. The write range's two read-for-update statements at
L3894 and L3917 each test their response code for equality with the normal condition value and route
everything else to a single `ELSE` arm, so **every** non-normal
response reaches the could-not-lock arm and leaves the range.

**The defect this entry records.** None of those five arms existed. A repository failure on any of the
three reads, or on either hold, propagated out of the turn - abending a transaction that the legacy leaves
on the screen with a message. A read that *fails* is not a read that finds nothing, and the source is
explicit about the difference: the not-found arms compose a business text through the message gate, while
the catch-all arms name the operation and the resource and move their text **ungated**, overwriting
whatever an earlier edit had claimed.

**The decision.** Each of the three reads now catches `DataAccessException` and takes its own catch-all
arm, raising the filter flag its paragraph raises - the account filter for the cross-reference and the
account master, the customer filter for the customer master, which is the only structural difference
between them. Each hold maps every non-normal outcome, absent row and raised failure alike, onto the
could-not-lock arm it already had for the absent row. The asymmetric account-versus-customer rollback
below is untouched.

**The two response slots are left blank rather than filled with an invented pair.** `ERROR-RESP` and
`ERROR-RESP2` hold a CICS response and reason code, and a relational store reports neither. Their declared
widths and blank initial values are what the composition carries, which is the same convention the
read-only twin of this transaction already uses - so an operator sees one shape of file-error text across
the account screens rather than two. The eight composed segments sum to exactly 12 + 8 + 4 + 9 + 15 + 10 +
7 + 10 = 75, the declared width of `WS-RETURN-MSG` at L479, so the five-character trailing filler at
L407-L408 falls outside the field by construction and nothing is truncated.

**Where the flow stops, and where the source walks on.** The caller's guard after the cross-reference read
tests the account filter flag, which the catch-all arm raises, so a failing cross-reference read stops the
read range exactly as the legacy does. After the other two reads the legacy tests condition names that its
own not-found arms leave unset - the commented-out `SET` at L3719 - so it walks on; that is reproduced,
and the range still refuses to build a screen because neither record was fetched. A second read that fails
overwrites the first one's text, which is precisely what an ungated move does.

*Cited by:* `service/AccountUpdateService.java`. Proven by the `ReadFailureArms` nest of
`service/AccountUpdateServiceTest.java`.

---

### DL-169 - The identifier-allocation audit measures the call site inside the locking method, and why that had to be restated

**Context.** `repository/IdentifierAllocationLockAuditTest` scans the production sources and asserts, per
enrolled minting service, that the allocation lock is taken textually *before* the statement that reaches
the read. The value it compares against for the bill-payment service was the invocation of that service's
allocate-and-write span.

**Why the fix of DL-166 invalidated the proxy rather than the property.** Moving the lock inside the span,
so that it is taken inside the unit of work the insert runs in, swapped the textual order of the span's
invocation and the span's body: the invocation now appears earlier in the file than the lock, while still
executing after it. The audit failed on a file whose serialisation had strictly improved.

**The decision.** The enrolled value for the bill-payment service becomes the mint invocation *inside* the
locking method, which is the same shape the transaction-add service's entry already had, and the constant's
documentation now states the rule explicitly: the value is the statement that reaches the read from inside
the method that takes the lock, never the read statement itself. Paragraph methods are laid out in the
legacy source's paragraph order, so a read's own text can appear earlier in the file than the lock while
executing after it - which is exactly why the audit cannot compare against the read directly. The audit
was not weakened: it still fails on a stale value rather than passing silently.

*Cited by:* `repository/IdentifierAllocationLockAuditTest.java`.

---

### DL-170 - The posting rewrite establishes the invalid-key answer under a write lock before it rewrites, instead of asking afterwards whether the row exists

**Context.** `CBTRN02C` paragraph `2800-UPDATE-ACCOUNT-REC` (`app/cbl/CBTRN02C.cbl` L545-L560) adds the
posted amount onto three balances and then rewrites the account record in place. Its `INVALID KEY` arm sets reject
code 109 and does nothing else - no status test, no diagnostic, no abend - so 109 is inert and the mainline
still writes the transaction and counts the record posted. The file is `ORGANIZATION IS INDEXED, ACCESS MODE
IS RANDOM` (L51-L53), so the rewrite is keyed rather than positional, and the cluster is defined
`READINTEG(UNCOMMITTED) RECOVERY(NONE) JOURNAL(NO)` (`app/csd/CARDDEMO.CSD`).

**The problem with the relational translation as first delivered.** The rewrite is a version-predicated
update whose affected-row count carries the rewrite status. A count of zero has two causes that reach
opposite outcomes here - the row is gone, which is the legacy invalid-key condition and inert 109; or the
row is present at a version other than the one validation read, which the legacy could not observe and which
must refuse the record. The delivered code told them apart with a second, unprotected existence probe issued
*after* the rewrite. That probe is a time-of-check/time-of-use window: a writer committing between the
rewrite and the probe turns either answer into the other, and the paragraph cannot tell that it did. The
consequences are not symmetric nuisances - a false 109 lets a transaction post against a balance that was
never rewritten, and a false conflict discards a record the legacy would have posted.

**The decision.** The answer is obtained *before* the rewrite, from a keyed read that holds the row for the
remainder of the record's unit of work: `AccountRepository.findByIdForUpdate`, a `PESSIMISTIC_WRITE` query.
From the moment it returns, nothing else can delete the row or change its version, so an absence observed
there is still an absence when the rewrite runs, and a presence observed there leaves a zero count with only
one remaining explanation. The classification is therefore complete and cannot be wrong for a timing reason.
The unprotected probe is removed.

**What deliberately did not change.** Reject 109 remains inert, with the same code, the same description
text and the same distinctness from 101. The three balance moves keep their legacy sign semantics, including
a negative amount accumulated *unchanged* into the cycle debit. The computed balances are still carried on
the result image on the invalid-key path, because lines 547 to 552 mutate the record area before the rewrite
is attempted. The version predicate stays on the rewrite even though the caller now holds the row: it is
defence in depth, so the statement's safety does not depend on every future call site remembering to lock
first, and it is what makes a zero count under a held row mean "changed" unambiguously.

**Divergence recorded, not presented as parity.** A row hold is stronger than the legacy baseline, which
held nothing and read uncommitted. §0.7.4 of the plan sanctions exactly this class of strengthening -
read-committed isolation plus a version check being strictly stronger than a file with no recovery and no
journaling - on the condition that it is documented rather than passed off as faithful, which is what this
entry does. The hold is scoped to one record of one batch step, is released by that record's unit of work,
and is not used by any online path: the online tier against this table continues to rely on the version
check alone, because replacing an optimistic model with mutual exclusion there would introduce waiting the
legacy system never had. Lock ordering is uniform across the batch tier - the category-balance row is
written before the account row in both the posting and the interest-accrual paths - so no cycle exists.

*Cited by:* `service/TransactionPostingService.java`, `repository/AccountRepository.java`.

---

### DL-171 - The interest run writes each synthesized transaction record while its group is still open, instead of rendering the group's records after the group has committed

**Context.** `CBACT04C` synthesizes one transaction record per accruing category-balance row and writes it
immediately: `1300-COMPUTE-INTEREST` adds the row's interest to the running total and then performs
`1300-B-WRITE-TX`, whose write of the transaction record is at `app/cbl/CBACT04C.cbl` L500, inside the read loop.
The account is rewritten only at the control break, `1050-UPDATE-ACCOUNT` at L350-L370, reached from L196 on
a key change and from L220 at end of file. So in the legacy **every** record of a group reaches its dataset
before that group's balance is rewritten, and the write-error arm at L508-L512 abends with the balance
untouched.

**The problem with the relational translation as first delivered.** The group operation collected its
synthesized records, committed the account rewrite in the group's own transaction, and returned the records
for the batch step to render afterwards. The order was inverted. A generation write that failed - a full
volume, a permission change, a closed handle - abended after the balance had already hardened, leaving an
accrued balance with no record of what accrued it, and a reconciliation between the SYSTRAN generation and
the account master that could not be closed. The legacy has no such state, because it cannot reach the
control break without having written first.

**The decision.** The caller supplies the writer, as a required parameter of the group operation, and the
service invokes it at the position L500 occupies - inside `1300-B-WRITE-TX`, inside the group's unit of work,
before `updateAccount` is reached. The batch step binds its guarded generation write as that writer once the
generation is open and before the first row is read; the service's own whole-file driver binds the run
result's record list, which is that driver's equivalent of a dataset. A failure the writer raises therefore
propagates out through the group's transaction boundary and the account is not rewritten, which is the legacy
outcome at L508-L512.

**Why the writer is required rather than defaulted.** A discarding default would let a caller that forgot to
supply one post every balance in the run and produce an empty generation - precisely the failure the ordering
exists to prevent, and silent. The service refuses a null writer, and the batch stage refuses to synthesize
a record while none is bound, naming the resource and the binding point in the message.

**What deliberately did not change.** The records are still reported in the group result, because the batch
stage counts them, checks their identifiers against the suffix it threaded, and renders its per-row outcomes
from them; reporting them is not writing them, and only the writer writes. Their order is unchanged. The
zero-rate gate still suppresses the computation, the fee paragraph and therefore the record, so a group of
only skipped rows writes nothing and still rewrites its account. The commit remains at the control break,
one transaction per closed group. Nothing is inserted into the live transaction master - the generation is
still loaded later by COMBTRAN.

**One property the file medium cannot give, stated plainly.** The generation is a file, so a record written
before a group's transaction later fails is not withdrawn. That is the legacy's behaviour too: the dataset is
sequential and unrecoverable and the account cluster is defined `RECOVERY(NONE)`, so the legacy's guarantee
was ordering rather than atomicity. This entry claims ordering, and claims nothing more.

*Cited by:* `service/InterestCalculationService.java`, `batch/step/InterestCalculationProcessor.java`,
`batch/InterestCalculationJobConfig.java`.

---

### DL-172 - The category-balance report reads its cluster once, and no longer abends because two passes counted differently

**Context.** Step `STEP05R` of `app/jcl/PRTCATBL.jcl` invokes the cataloged wrapper `app/proc/REPROC.prc`,
whose control member `app/ctl/REPROCT.ctl` holds a single statement: `REPRO INFILE(FILEIN)
OUTFILE(FILEOUT)`. One statement, one traversal, input to output. There is no validating pass ahead of it,
no second read, and nothing that compares one traversal's record count against another's.

**What the first translation did instead.** The unload step ran the service's whole sequential pass over the
cluster - open, read to end of file, close, report the count - and then opened a <em>second</em> cursor over
the same cluster to produce the output. Two traversals for one utility invocation. It then compared the
records it had written against the count the first traversal reported and abended when they differed.

**Why the comparison was worse than redundant.** The two traversals are taken at different times against a
live table. A commit landing between them - a posting run or an interest run touching one category-balance
row, both of which reach this cluster transactionally - makes the counts differ legitimately, and the step
then abended. The utility being translated has no notion of that condition and the step has no arm for it,
so the abend was an invention: a failure the migration introduced, raised on correct data, on a report whose
inputs are written by two other jobs in the same estate.

**The decision.** The cluster is traversed once. The step opens its output, then hands the shared sequential
pass that output as the destination of its read, and the pass delivers each record to it as it reads it -
read then write, the order the copy statement has. The pass keeps everything it already owned: the open, the
composite-key ordering, the two-level status model in which end of file is normal, the record redaction and
the emit-then-abend ordering on a failure. The step keeps the guarded write of each record it is handed. The
count the closing diagnostic reports is the count of the one traversal that produced the output, so it cannot
disagree with itself, and the reconciliation is gone along with the second pass.

**Consequences that are deliberate.** The destination is a required parameter of the pass: a pass standing for
a copy statement has a destination by definition, and a defaulted one would silently reproduce the collect-
then-write shape this entry removes. A caller that genuinely wants only the count and the terminal status
passes a sink that does nothing, which states that intent rather than omitting it. The step has no read loop
of its own - the whole effect of the utility completes in one call, exactly as the allocation step of the same
job has no read loop - and it therefore holds no repository: its only persistence access is that pass.

**What deliberately did not change.** The 40-byte report line, the trailing filler, the edit mask that prints
every digit position it declares (DL-159), the absence of a sign character, the zoned-decimal key ordering,
the sort specification applied to an already-ordered unload, and the generation staging and retention are all
separate decisions and none of them moved.

*Cited by:* `batch/CategoryBalanceReportJobConfig.java`, `service/FileMaintenanceService.java`.

### DL-173 - A member is opened by its own first bounded page, not by counting the cluster

**Context.** All five sequential passes in `service/FileMaintenanceService.java` translate the same COBOL
skeleton: `OPEN INPUT <dd>`, test the file status, then a read loop, then `CLOSE`. `CBACT01C` states it at
`app/cbl/CBACT01C.cbl` L90-L114 - status `'00'` becomes `APPL-RESULT` 0, `'10'` becomes 16, anything else 12
- and `CBACT02C`, `CBACT03C`, `CBCUS01C` and the category-balance unload repeat it verbatim with their own
DD names and their own display literals.

**What the first translation did instead.** The open issued `repository.count()` as its probe: an aggregate
over every row of the cluster, executed for no reason other than to decide whether the resource was
reachable, and then discarded. The reader went on to perform its real work through a separate bounded
keyset retrieval, so the count was pure overhead - `SELECT count(*)` over `transaction`, `account`, `card`,
`customer` and `tran_cat_bal` before a single record was read. It also answered a question the source never
asks. `OPEN INPUT` reports whether the dataset can be read; it does not report how large it is, and the
record count the reader publishes at the end is the count of the rows it actually read, taken from its own
loop.

**The decision.** The open performs the first bounded page of the cursor's own key-ordered retrieval and
reports whether a first record is present, without consuming it. That page is the page the read loop then
takes, so establishing reachability costs nothing beyond work the first read would have done anyway, and no
aggregate is issued at any point. The probe runs inside the same guard the open already had, so a failure
still becomes the permanent-error status, still runs `logDataAccessFailure` before the status is normalised,
and still reaches the member's own open literal through `abendOnFailedOperation`.

**How the two arms still divide.** On the mainframe an open and a first read are two operations against a
dataset and can fail independently. Against a relational store there is one failure mode - the query fails -
so which arm reports it is a translation decision. It is resolved by position: a failure on the first page is
an open failure and carries the member's open literal, and a failure on any later page is a read failure and
carries its read literal. Both arms remain reachable, and both are exercised - the read arm because the
bounded iterator issues one further query after the last non-empty page has been consumed, which is where a
mid-stream failure lands.

**What deliberately did not change.** The two-level status model, end of file as a normal completion rather
than an error, the pre-operation sentinel that every other value is measured against, each member's own open,
read and close literals, the emit-then-abend ordering, the record redaction, and the composite-key ordering
of the category-balance pass. The opening diagnostic now reports `recordsPresent` rather than
`recordsAvailable`, because a boolean is what the open now knows and a cardinality is what it deliberately no
longer asks for.

*Cited by:* `service/FileMaintenanceService.java`.

---

### DL-174 - The statement file handler walks the cross-reference once and reports every failure as a file status

**Context.** `app/cbl/CBSTM03B.CBL` declares four files. Two are `ACCESS MODE SEQUENTIAL` - the transaction
work resource at L33 and the cross-reference cluster at L39 - and two are `ACCESS MODE RANDOM`, the customer
file at L45 and the account file at L51. Each of its four handlers offers `OPEN INPUT`, one read, and
`CLOSE`, and each publishes the file's own two-character status into the return-code field of the shared
parameter area before returning. The subprogram cannot raise anything: setting a status and returning is the
whole of its failure vocabulary, and its caller's selection at `app/cbl/CBSTM03A.CBL` L837-L847 is what turns
a status it does not accept into a display naming the operation and the raw code, followed by an abend.

**Three defects of the first translation.** The cross-reference read requested one offset-addressed page per
record: a paged query re-scans and re-discards every preceding row on every read and counts the whole table
besides, so one sequential pass over the cluster became a quadratic one and two queries per record instead of
none. The open reported success unconditionally without touching any store, so a cluster that could not be
read at all was not discovered by the open but by the first read - and reported under the wrong paragraph's
literal. And no repository call was guarded, so a technical failure of the store propagated out as an
exception: the caller's catch-all arm never ran, the operator never saw the DD name, the operation or the raw
status, and the abend carried a Java message instead of the member's own reason.

**The decision.** The cross-reference file is walked once, by a bounded forward cursor a run acquires for
itself. The cursor retains one page and one key, never an offset and never a count, and it is created per run
rather than held by the service, because a file position is state and the handler is a shared singleton. A
second single-method source interface joins the transaction one: between them they cover exactly the two
files the source declares sequential, which is why one is a parameter of the entry point and the other is
too, and why the two random files have neither.

**Why one source is passed in and the other is opened here.** The transaction work resource is a snapshot -
the statement job's sort and projection steps produce it, so it is materialised and frozen before generation
begins and must be handed in rather than re-queried. The cross-reference cluster has no snapshot: the legacy
member reads it live, one record at a time, in key order. The run therefore asks the file handler to open a
walk of it, and the handler owns the repository that backs it, exactly as the subprogram owns the file.

**The open is now the first bounded page.** Opening a key-sequenced cluster for input establishes that it
exists and can be read; its relational equivalent is one indexed range scan bounded to a page. The page the
open loads is the page the first read consumes - the cursor re-serves the position it most recently served
rather than consuming it - so proving the cluster reachable costs nothing beyond the first read's own work.
The two randomly accessed files have no first page to load, so their opens read one bounded row in key order
instead. An empty cluster opens successfully in every case: an open says whether, and the caller discovers
emptiness on its first read as the at-end status, which is the sequence the caller already follows.

**Every failure is a status, and the status distinguishes the two conditions.** A `DataAccessException` from
any of the guarded calls becomes raw status `'31'` in the response, which is the code this module already
uses for a technical data-access failure in its batch readers. `'31'` is not `'23'`: the record-not-found code
states that the cluster was read and held no such record, which is a data condition, while `'31'` states that
the read did not complete. Both reach the caller's catch-all arm, and the arm displays whichever code it was
given, so an operator can tell the two apart. Exhaustion stays `'10'` and remains a normal outcome.

**What deliberately did not change.** The unguarded consecutive-check shape of all four handlers, so that an
operation a handler does not test still performs nothing and returns the caller's own status untouched. The
publication of a status on every path out of a handler, including that one. The two different key paddings the
two random files' picture clauses demand. The record images, their widths, and the caller's obligation to
blank the payload before every read. The statelessness of the handler itself: it holds no cursor, no status
and no payload, so two runs sharing it still cannot observe one another.

*Cited by:* `service/StatementDataAccessService.java`, `service/StatementCrossReferenceSource.java`,
`service/StatementGenerationService.java`.

---

### DL-175 - The transaction report resolves each distinct reference once, and a failed reference read reports 31

**Context.** `app/cbl/CBTRN03C.cbl` resolves three references while reporting. Its unnamed driving body at
L160-L217 performs `1500-B-LOOKUP-TRANTYPE` at L494 and `1500-C-LOOKUP-TRANCATG` at L504 for **every**
transaction record, and `1500-A-LOOKUP-XREF` at L484 on every card-number break. All three are random reads
of small key-sequenced reference clusters, and all three have exactly one failure arm: `INVALID KEY` displays
the paragraph's own literal, moves 23 into the status field, performs `9910-DISPLAY-IO-STATUS` and then
performs `9999-ABEND-PROGRAM`, in that order.

**What the first translation did.** It issued one `findById` per record for the type and the category, and one
per card break for the cross-reference. On the mainframe a random read of a cluster the step holds open is an
index probe in the address space; its relational equivalent issued per record is a separate round trip per
record. Over the seeded daily-transaction input that is several hundred round trips to resolve **seven**
distinct transaction types and **eighteen** distinct categories - the reference cardinalities the project's
own specification records. Separately, none of the three reads was guarded, so a technical failure of the
store escaped as an exception and none of the four steps of the paragraph's failure arm ran.

**The decision on the reads.** Each run memoizes the references it resolves, keyed by the reference key the
paragraph presents - the card number, the two-character type code, and the composite type-and-category key.
The number of reference queries a run issues is then bounded by the cardinality of the reference clusters and
is independent of how many transaction records the run reports, which is the whole of the defect. A capped
eager prefetch was considered and rejected: it would need a cap the source does not state and a fallback path
for exceeding it, and the fallback would be an untested branch. Memoization needs neither and is bounded by
strictly less - what the run actually required.

**Absence is deliberately not memoized.** An absent reference abends on the record that first presents it, so
the run does not continue and there is nothing to remember. The first missing reference is therefore still the
one that fails, with its own literal, its own status, its own operation and its own resource, on the same
record as before.

**The memo is per run and never outlives one.** A cache held by the service would make one run's reference
data visible to the next, which is a snapshot the legacy step never had. Within a run the memo is a snapshot
of references read at first use rather than at every use, which is weaker isolation than a single read would
give and stronger than the `READINTEG(UNCOMMITTED)` baseline of `app/csd/CARDDEMO.CSD`; no program of the
estate writes the two reference clusters while a report runs.

**The decision on the failures.** A `DataAccessException` from any of the three reads now takes the
paragraph's own sequence - literal display, status display, abend - carrying raw status `'31'` rather than
`'23'`. The distinction is the point. `'23'` says the cluster was read and held no such record, which is a
data condition an operator fixes in the reference data; `'31'` says the read did not complete, which is an
operational one. Reporting one as the other would send an operator to the wrong place. The store's own
failure is reduced to its failure-type chain before it reaches a diagnostic and is never handed to a logger
whole, because a data-access failure's narrative is where a statement and its bound parameters appear.

**What deliberately did not change.** The 133-byte report line and its two amount masks. The page and account
break placement and the accumulation chain in which an amount reaches the grand total only through a page
total. Both pinned legacy defects - the `NEXT SENTENCE` arm that leaves the whole driving loop and the at-end
path that re-adds the last record's stale amount. The absence of arithmetic: the member contains no `COMPUTE`
and this change introduces none. The card number is still withheld from every diagnostic, and the module's
single sanctioned stand-in is still the only form in which it is referred to.

*Cited by:* `service/TransactionReportService.java`.

---

### DL-176 — A dataset is streamed through the batch tier, not held in it

**What was there.** Nine places in the batch and service tiers assembled a whole dataset in the heap
before doing anything with it. The transaction archive composed its generation into a
`ByteArrayOutputStream` and published the byte array. The combined-transactions generation held every
merged record in a `List<Transaction>` and handed back its `content()` as one array. The statement job's
transient work resource held every projected record in a `TreeMap` and duplicated the whole map three
times over — once for its records, once for its keys and once more for the frozen snapshot generation
read from. The transaction-report emitter drained its whole filtered generation into a `List<Transaction>`
and copied it, and the report itself accumulated every 133-byte record in a `List<String>` that the
result then copied again. The daily-transaction extract read its whole staged dataset into a
`List<DailyTransaction>` before the pass started, and accumulated one verification outcome per record for
the whole run. The interest run held one entry per account group and every synthesized transaction for
the whole master, and the accrual stage copied each group's rows a second time on the way out.

**Why that is wrong rather than merely wasteful.** Every one of those inputs and outputs is a sequential
disk dataset on the mainframe, read and written a record at a time. A member's working storage holds one
record area, not the dataset. Sizing a translated run by the number of records the dataset happens to
contain replaces a constant cost with one that grows without bound, and it does so silently: the seeded
fixtures are small enough that nothing shows. The estate's own numbers make the point — `dailytran.txt`
is 300 records because that is what the sample holds, not because 300 is a limit anything enforces.

**What it is now.** Each site retains one record, or the one group the source requires, and nothing more.

*The two published generations* are composed into a staged working file, sealed by an atomic move and
published by path. `StagedGenerationStore.publishBytes` is gone and `publishFile` replaces it: it streams
from the completed file, reports the file's own length, and refuses a path that is still a working file.
The archive writes each 350-byte record as it arrives and deletes its local copy once the object store
holds it.

*The combined generation* is a sequential file with two roles in sequence. As the writer it appends each
record image and a single `'\n'` — stated as a byte rather than taken from the platform, because a
writer's own line separator would be two bytes on one platform and would change every record's external
length. It is sealed on close, published once, then served back a record at a time as the reader, and the
local copy is removed when it has been served.

*The statement job's transient cluster* is a sequential file minted per execution under the staging root
and scratched when the job-scoped bean is destroyed — which is the second half of the lifetime the
absorbed definition step declares, and something the heap map never reproduced. Records are appended as
they are loaded and served through one forward walk. Ordering is now *proved* rather than imposed: every
key must strictly exceed the key last admitted, which is the rule a keyed load into an empty cluster
enforces, where the sorted map silently repaired a disagreement and hid a defect in the ordering step. A
duplicate key still reports the message it always did; a key out of sequence reports its own. Reading
freezes the resource, so a record presented afterwards is refused rather than changing what the
generation step has already been given.

*The report* takes its destination as an argument. `ReportTransactionInput` carries a
`Consumer<String>`, `TransactionReportService` offers each composed record to it as the record is
composed, and `TransactionReportResult` reports how many it offered instead of carrying them. The
processor's width proof moved from a finished list to a decorator in front of the destination, so the
earliest offending record still stops the run and a record that fails the proof never reaches a file.
The emitter's own destination is the writer it already had.

*The report's input* is one forward walk over the open reader, routed through the step's read gate so a
technical failure on any record — not only on the first — is still normalised into the step's read status
under the input data definition.

*The daily-transaction extract* pulls its staged records through a one-record-deep cursor while the pass
runs, and offers each verification outcome to a destination as it is produced. `DailyTransactionReadResult`
keeps its counts and carries no per-record list; `verificationPasses` was already the count of the list it
used to hold.

*The interest run* offers each closed group to a destination at its control break and each synthesized
transaction to a destination inside its group's unit of work, and reports counts. The accrual stage hands
its closing group's rows over and installs a fresh buffer rather than copying the rows and emptying the
old one, so one group's rows exist once rather than twice.

**Where retention is deliberate and permitted.** The rows of the group currently filling, that group's
per-row outcomes and that group's synthesized transactions are all still held, because the control break
at `app/cbl/CBACT04C.cbl:L194` cannot be decided without them: a group is the unit the member itself
works in. So is the bounded 51-by-10 card table of `app/cbl/CBSTM03A.CBL`, whose two dimensions are the
member's own `OCCURS` clauses and which is not part of this change. So are the two statement output
allocations the scratch step enrols, of which there are exactly two.

**A behavioural consequence worth stating.** The daily-transaction extract used to read its whole staged
dataset before the pass began, so a malformed record failed before the translated member ran. It is now
read as the pass pulls it, which is where `app/cbl/CBTRN01C.cbl` reads it — inside the read loop. The
diagnostic, its raw status and the abend that follows are unchanged and still in that order; what changed
is that the failure now arises at the point in the pass the member would have reached, which is the more
faithful position.

**What deliberately did not change.** Every contractual record width: 430 for a reject, 80 for a
statement record, 100 for an HTML statement record, 133 for a report line, 40 for a category-balance
report line, 350 for a transaction and an archive record. Every reject reason code. Every ordering,
including the two-key character ordering of the statement work resource and the zoned-decimal card-number
ordering of the report. Every diagnostic and its position relative to the abend that follows it. The
absence of arithmetic in the report path.

*Cited by:* `batch/BackupTransactionJobConfig.java`, `batch/CombineTransactionsJobConfig.java`,
`batch/CreateStatementJobConfig.java`, `batch/TransactionReportJobConfig.java`,
`batch/DailyTransactionReadJobConfig.java`, `batch/step/InterestCalculationProcessor.java`,
`batch/step/StagedGenerationStore.java`, `service/TransactionReportService.java`,
`service/DailyTransactionReadService.java`, `service/InterestCalculationService.java`,
`service/ReportTransactionInput.java`.

---

### DL-177 — A diagnostic names a record by a redacted reference, and renders a caller's bytes inert

**Context.** The migrated estate's only diagnostic channel is the console display statement, and there
are **215** of them, counted as statement-initial lines — see DL-253, which corrects the specification's 217
and states the counting method. A display statement writes a literal and a named field, so the legacy programs did name
identifiers in their diagnostics — `CBTRN02C` displays the daily-transaction identifier beside a reject
reason, and `CBACT04C` displays the account it has just closed a control break for. Reproducing that
literally on this stack reproduces something the legacy channel was not: a spooled JES output dataset is
read by whoever holds authority over that job's output, whereas a structured log record is shipped to
centralised storage that is searchable by considerably more people than the operator who ran the job, is
retained for considerably longer, and is correlated with everything else that storage holds.

Two properties of these identifiers make the difference material. They identify a cardholder's
transaction or account, so a log record naming one is a disclosure. And their **bytes are not this
module's text**: they are read out of a 350-byte or 50-byte fixed-width image, or off a job parameter
card, so a producer that writes a line terminator, a carriage return, an escape byte or a zero-width code
point into one has chosen what a log reader sees. A line terminator ends the record early and makes the
remainder read as a record of its own; an escape byte begins a sequence a terminal obeys; a zero-width
code point hides the difference between two values a reader is comparing by eye.

**The decision.** Every diagnostic on the four record paths — `batch/step/TransactionValidationProcessor`,
`batch/step/CombineTransactionsProcessor`, `batch/step/InterestCalculationProcessor` and
`service/InterestCalculationService` — names a record by a **redacted reference** and never by its
identifier. The reference comes from `util/SensitiveLogRedactor.redact`, which withholds the value and
returns `[REDACTED] ref=<token>` where the token is a truncated HMAC under a process-local key. Two
properties of that token are what make the substitution acceptable rather than merely safer: it is
**stable for a given value within a run**, so the several messages one failing record produces still tie
to one another and a record re-presented item by item after a chunk failure is still findable; and it is
**lower-case ASCII hexadecimal only**, so routing a value through it neutralises every injectable byte in
the same step that withholds it.

Redaction is applied at a **single composer per identifier kind** rather than at each message. The
postconditions on the interest stage state fourteen properties of one synthesized transaction and every
one of them opened with the same clause; the posted-record fidelity check states seven and did the same.
Each family now opens through one method — `postedRecordDiagnostic`, `interestTransactionDiagnostic`,
`accountDiagnostic`, `rowDiagnostic` — so the identifier is withheld once and a message added later cannot
reintroduce it by forgetting to. `rowDiagnostic` additionally decomposes the composite
transaction-category-balance key rather than rendering it, because that key's own `toString` renders the
account identifier as its first component while its two-character type code and four-character category
code are reference codes from the estate's own tables and are what a reader needs to locate the row.

Where a diagnostic must show the value itself — a job parameter that is not a date, a field that is not
its declared width, a character found where a digit belongs — withholding it would leave the reader with
nothing to act on. Those values instead go through `util/FailureDiagnostics.printableForm`, which keeps
printable ASCII exactly and renders everything else as `U+XXXX`. The rendering is reversible by a reader,
which is what a diagnostic needs, and inert, which is what a log record needs. It is bounded at 200 source
characters so that one oversized value cannot set the size of the record, and it is locale-free, because a
diagnostic that changes with the locale is not a diagnostic.

**Divergence from the legacy behaviour, stated plainly.** The legacy programs displayed the identifier and
this module does not. A reader of a Java log therefore cannot read an account number out of it, where a
reader of the legacy job output could. That is a deliberate reduction in what the diagnostic channel
publishes and not an oversight; the diagnostic retains everything it needs to be actionable — which
program, which step, which reason code, which field, which position, and which record, by reference.

*Cited by:* `batch/step/TransactionValidationProcessor.java`,
`batch/step/CombineTransactionsProcessor.java`, `batch/step/InterestCalculationProcessor.java`,
`service/InterestCalculationService.java`, `util/FailureDiagnostics.java`.

---

### DL-178 — A staged generation is created readable by its owner and by nobody else

**Context.** Nine jobs write a local file before publishing it to object storage, and what those files
hold is the whole of what the legacy datasets held, in the same fixed-width images: the 430-byte reject
records, the 350-byte archive and combined generations with their card numbers, the 80-byte and 100-byte
statement generations with a cardholder's name and address and every transaction on their account, the
133-byte report generation, and the 40-byte category-balance listing.

On z/OS a sequential dataset is a catalogued object whose access is decided by an external security
product and not by the program that writes it, so **no COBOL member in the migrated estate expresses a
permission at all**. Reproducing that silence on a filesystem does not reproduce the access control; it
reproduces the process umask. At the conventional container umask of `0022` every generation was created
`rw-r--r--`, readable by every account on the host for the whole of the job's run and for as long as the
generation was retained afterwards. Nothing else in the module could see it: the job succeeded, the object
published, and every byte-parity assertion passed.

**The decision.** All staged file and directory creation goes through one new utility,
`util/SecureStagedFiles`, which guarantees four properties.

*Owner-only from the first byte.* The mode is supplied as a **creation attribute**, not applied afterwards.
Creating first and tightening second leaves a window in which the file exists at the umask's mode, and a
descriptor obtained inside that window keeps its access after the mode changes. Because neither
`Files.newBufferedWriter` nor `Files.newOutputStream` accepts a creation attribute, creation and opening
are two steps: `Files.createFile` with the attribute, then an open of what exists.

*Never through a link, and never onto a directory.* The target is examined with `NOFOLLOW_LINKS` before
anything is written, and the open itself carries `NOFOLLOW_LINKS` so that a link substituted in the
interval between creating and opening makes the open fail rather than redirect it. A link is **refused
rather than deleted**, because deleting it is a second thing whoever planted it could have wanted.

*Never silently reused.* A file is created with `CREATE_NEW`. The legacy allocate-new disposition is
honoured by **removing** what a previous run left and creating afresh, rather than by truncating in place —
truncation keeps the previous run's mode and its owner.

*Directories the module creates are owner-only too*, because a readable staging directory discloses the
generation names and those names carry the execution identifiers the object keys are built from.

**What this deliberately does not do, and why.** It does not change the mode of a directory that already
exists. The default staging root resolved to `${java.io.tmpdir}`, which on every Unix host is
world-writable and sticky by design and shared with the rest of the system; tightening it would be
vandalism on a path the module does not own. The root's default therefore **moves to a named subdirectory**,
`${java.io.tmpdir}/carddemo-batch-staging`, which the module does create and can therefore make owner-only.
Overriding `CARDDEMO_BATCH_STAGING_DIRECTORY` to a shared directory gives up the guarantee for the
directory listing; the generations within it stay owner-only either way. The utility does check, on a
pre-existing directory, that it is a directory and is not a link, because both are how a staging path gets
redirected.

It does not encrypt. A staged file is short-lived local scratch on the way to object storage, where
durability and encryption belong; a second key-management surface here would add a secret to protect
without removing the exposure this addresses.

Two paths that already made a weaker attempt were brought under the same policy. `util/ExternalStringSorter`
created its work area and its spill files and tightened them afterwards, which is the window described
above. `batch/step/StagedGenerationStore`'s fixed-name alias is produced by `Files.copy`, which without
`COPY_ATTRIBUTES` creates its target at the umask rather than at the source's mode — so the alias, a
byte-for-byte duplicate of the completed generation, was the one readable copy of it.

**Divergence from the legacy behaviour, stated plainly.** This is stronger than the baseline, and the
baseline had nothing to say. It is recorded here so that a reviewer does not read the tightening as a
behavioural regression, and so that an operator who overrides the staging root understands exactly which
of the four guarantees that override affects.

*Cited by:* `util/SecureStagedFiles.java`, `util/ExternalStringSorter.java`,
`batch/step/StagedGenerationStore.java`, `batch/BackupTransactionJobConfig.java`,
`batch/CategoryBalanceReportJobConfig.java`, `batch/CombineTransactionsJobConfig.java`,
`batch/CreateStatementJobConfig.java`, `batch/InterestCalculationJobConfig.java`,
`batch/PostTransactionJobConfig.java`, `batch/TransactionReportJobConfig.java`,
`src/main/resources/application.yml`.

---

### DL-179 — A release attempts everything it holds, keeps the first failure and carries the rest beneath it

**Context.** The combine-transactions ordering stream holds three things: a reader over the ordered work
file, that file, and the per-execution directory minted to hold it. Its release closed the reader and, if
that close failed, **threw immediately** — so the work file, which holds the ordered concatenation of every
posted transaction from both inputs, stayed on the host indefinitely with no diagnostic naming it. The same
release ran from the preparation path's own failure handler, where a cleanup failure would additionally
have **replaced** the preparation failure that was the actual diagnosis.

**The decision.** Releasing is one method that always attempts all three, in the order a filesystem
requires — descriptor, then file, then directory, because a directory cannot be removed until it is empty.
Every handle is cleared whether its own release succeeded or not, so a second call cannot attempt the same
release twice, which matters because the framework may close a stream it has already closed.

Failures are **collected, not thrown as they occur**. The public close reports the first as the cause of one
`ItemStreamException` and attaches every later one with `addSuppressed`. The preparation path reports the
**preparation** failure as the cause and attaches the cleanup failures beneath it: a handle that could not
be released is worth knowing about and is never the reason the step failed.

The preparation path also now releases on **any** runtime failure and not only on an I/O failure. An input
that cannot be allocated is a `IllegalStateException`, which the previous handler did not catch, so that
path minted a work area and left it behind. Such a failure is rethrown as itself — neither its type nor its
message changes — with the cleanup failures suppressed beneath it. What must not differ by failure type is
the release, because leaving the work area behind on one path and not on another is how a leak survives
every test written against the other path.

*Cited by:* `batch/CombineTransactionsJobConfig.java`.

---

### DL-180 — A publication has one commit point, and the irreversible step sits outside it

**Context.** Publishing a completed job's artifacts was a four-part sequence: upload every registered
generation, advance every fixed-name local alias, enforce every generation base's retention depth, and clear
the registry. Only the **first** part was compensated. A failure in the alias replacement — the third
artifact's alias, say — propagated to the job-boundary listener, which marked the execution FAILED, while the
two objects already uploaded stayed in the bucket and the two aliases already advanced kept naming
generations belonging to a job that had failed. A retention failure did the same thing one step later, and
additionally left the registry uncleared. In both cases the terminal verdict said nothing was published and
the outside world could see that something had been.

**The decision.** The sequence is split at one commit point, and every step is placed on the side of it that
matches whether the step can be undone.

*Before the commit point,* and compensated as a single unit: the uploads and the alias replacements. If any
of them fails, every alias this publication advanced is put back to what it named before and every object it
uploaded is deleted, then the failure is rethrown for the listener to turn into the verdict. Restoring an
alias is possible because the previous content is copied aside — by copy, not by move, so a failure before
the atomic replacement leaves the alias itself untouched — and the copy is discarded once the publication
commits. An alias the publication *created* is removed rather than restored, because not existing is what it
named before. **A FAILED job therefore leaves nothing externally visible, durable or local.**

*After the commit point,* and unable to fail the job: clearing the registry, and enforcing retention.
Retention deletes rolled-off objects, which cannot be undone, so it cannot participate in any compensation
and must not be attempted while compensation is still possible. Raising from there would leave only bad
choices — mark a job FAILED whose artifacts are correctly published and visible, or compensate by deleting
artifacts that are correct. A base left one generation over its depth is smaller than either and it is
self-correcting: the next successful publication of that base measures depth from the state it inherits and
prunes what this pass could not. The failure is logged, naming the base and the depth, and the publication
stands.

*Cited by:* `batch/step/StagedGenerationStore.java`, `config/BatchConfig.java`.

---

### DL-181 — Publication is serialized by generation base, because two differently named jobs share one

**Context.** Retention is a read-decide-delete pass: list the objects beneath a base, sort them by execution
identifier, delete everything past the declared depth. It ran with no mutual exclusion of any kind, so the
decision was taken against a set another publication could still be changing — a time-of-check-to-time-of-use
defect (CWE-367) with a concrete outcome. Two publications that each upload a generation and each then list a
set that does not yet contain the other's upload both conclude that nothing has rolled off, and the base is
left **permanently** one generation deeper than its limit, because the next publication measures depth from
the state it inherits.

This was reachable rather than theoretical. `BatchLaunchCoordinator` serializes launches, but its advisory
lock is keyed on the **job name**, and the transaction-backup base `AWS.M2.CARDDEMO.TRANSACT.BKUP` is
published by two differently named jobs: the backup job's archive step, eagerly at step close, and the
transaction-report job's unload step, at the job boundary. A per-job lock leaves exactly the pair that shares
a base free to run concurrently.

**The decision.** The unit of exclusion is the thing being counted, so it is the base and not the job.
`GenerationPublicationLock` holds every base a publication touches for the whole of upload-then-retention,
which removes the window rather than narrowing it: the second publication cannot begin until the first has
both uploaded and pruned, so it always measures depth against a settled set.

The implementation is a PostgreSQL transaction-scoped advisory lock, the same primitive and namespace pattern
the launch coordinator already uses, for three reasons. It is indifferent to how many replicas exist, which an
in-process lock is not. It is also indifferent to how many store instances exist inside one JVM, which
matters because `BackupTransactionJobConfig` constructs its own `StagedGenerationStore` rather than injecting
the shared bean, so an instance-field lock would not be the same lock. And transaction scope cannot leak a
held lock back into the connection pool, which a session-scoped lock released by hand can — and a leaked lock
would stop every later publication of that base rather than one.

Three details are deliberate. Acquisition is **blocking** rather than the coordinator's `try` form, because a
busy base means "wait your turn", not "skip retention and leave the base over-depth"; the wait is bounded
through the JDBC statement timeout, so a stuck holder surfaces as a failed job rather than as a batch tier
that never finishes. Bases are **sorted** before acquisition, so two publications naming an overlapping pair
cannot deadlock by taking them in opposite orders — the transaction-report job names three bases in one pass,
which makes overlap real. And a **failure to acquire fails the publication**: publishing unserialized is the
defect being closed, so it is never the fallback.

The shared base is now declared once. `TransactionReportJobConfig` reads its default from
`BackupTransactionJobConfig.ARCHIVE_DATASET_BASE` instead of spelling the name out a second time, because two
spellings of one base would be two retention groups that only looked like one. The two retention depths remain
two constants, because they record two independent measurements of the same `LIMIT(5)` declaration, and their
agreement is asserted by test so that a future divergence in the source has to be resolved deliberately
rather than inherited silently.

*Cited by:* `batch/step/GenerationPublicationLock.java`,
`batch/step/AdvisoryGenerationPublicationLock.java`, `batch/step/StagedGenerationStore.java`,
`batch/BackupTransactionJobConfig.java`, `batch/TransactionReportJobConfig.java`.

---

### DL-182 — A dashboard panel visualizes a Gate 3 figure; it does not produce one

**Context.** Gate 3 names three figures — elapsed time, peak memory, records per second — and the dashboard
announced itself as the source of all three, with panel titles reading `GATE 3 RECORDS PER SECOND`,
`GATE 3 PEAK MEMORY`, `GATE 3 ELAPSED TIME`, and a row header stating that the peak was "read from this
row". Two of the three could not be what they were called.

*Records per second* was `rate(counter[$__rate_interval])`. A rolling rate divides by the **rate window**,
not by the run's elapsed time. A job that processes three hundred records in four seconds inside a
one-minute rate window reads as five records per second rather than seventy-five. The query is not wrong as
a rate; it is simply not the quotient the gate asks for, and the difference is a factor of fifteen in that
example.

*Peak memory* was `max_over_time(sum(jvm_memory_used_bytes)[$__range:])` — the largest occupancy Prometheus
**happened to observe**. Occupancy between two scrapes is not sampled, so a peak that rises and falls
inside one scrape interval is invisible, and a batch run short enough to fit between two scrapes is
invisible entirely. Against the seeded fixtures the interest job completes in tens of milliseconds, so this
was the normal case rather than the edge case.

The guidance panel compounded it by pointing the reader at a panel called *Records read per second*, which
no panel on the dashboard was titled — so a reader following the gate's own instructions arrived nowhere —
and `gate-evidence.md`, which the same panel named as where figures are written up, did not yet exist.

**The decision.** The dashboard measures and visualizes; it does not certify. Every panel adjacent to a
Gate 3 figure is now titled as a visualization and its description states what its divisor or its sampling
actually is, so the honest reading is the first reading rather than one available only to someone who
opens the query. `GATE 3` no longer appears in any panel title, including the elapsed-time panel, which
reads a genuine per-execution timer and is therefore sound — but treating it differently from its two
neighbours would leave a reader to work out which of three identically-branded panels could be trusted.
It is labelled corroboration.

The quotable figures are produced by a **run-scoped measurement** in the test estate,
`support/RunScopedPerformanceRecorder`, driven from `InterestCalculationJobIT`. Elapsed time is wall clock
across the launch. Peak memory is read from `MemoryPoolMXBean.getPeakUsage()` summed across heap pools
after `resetPeakUsage()` immediately before the launch, so it is this run's peak and depends on no sampling
interval. Records come from the run's own step execution. Records per second is the quotient of the first
and the third, both from the same run.

Three constraints on that measurement are deliberate. It asserts only that a figure is **well formed** — a
positive elapsed time, the fixture's own record count, a peak the platform reported — and never that a
figure is fast enough, because no numeric performance figure exists anywhere in the legacy estate to
compare against and inventing one is expressly forbidden. It **refuses a measurement with no fixture
volumes named beside it**, because a number without them is not a baseline. And it writes to
`target/gate-evidence/` rather than into this documentation tree, because a recorded baseline belongs to a
machine and a date that a person supplies; a test that edited the documentation tree would make this
repository's content depend on the hardware of whoever last ran the suite.

*Cited by:* `config/grafana/dashboards/carddemo-overview.json`,
`src/test/java/com/carddemo/support/RunScopedPerformanceRecorder.java`,
`src/test/java/com/carddemo/batch/InterestCalculationJobIT.java`, and `gate-evidence.md` in this
documentation set.

### DL-183 — A CI gate reads the same health group the container probe reads, not the aggregate

The jar smoke gate started a database, launched the `local` profile and then required the **aggregate**
health endpoint to report UP. The aggregate consults every registered contributor, and the gate
deliberately starts no AWS emulator, so the object-store, queue and topic contributors could not be UP and
the endpoint answered `{"status":"DOWN"}` with status 503. Under `curl -fsS` a 503 is a failure, so the
poll never received a body to match and the gate failed on every run regardless of the artifact under test.
Measured directly, with the database up and the emulator absent: the aggregate returns `DOWN` 503 while
`/actuator/health/liveness` returns `{"status":"UP"}` 200.

The gate now reads the liveness group. That is the same group `Dockerfile`'s HEALTHCHECK reads (DL-176), so
the step's long-standing claim — that a jar satisfying it satisfies the image too — became true rather than
aspirational. **Nothing about the database is given up by not naming its contributor.** Flyway runs inside
context refresh, so an unreachable database fails the `flywayInitializer` bean, cancels the refresh and ends
the process; liveness never turns UP at all and the poll reports the exit instead. Measured, pointed at a
closed port: the process exits during start-up having reported nothing. The alternative resolution — start
the emulator for this step too — was rejected because it makes an artifact gate depend on the whole stack
that the following gate exists to exercise.

*Cited by:* `.github/workflows/carddemo-java-ci.yml`,
`src/test/java/com/carddemo/config/BuildAndCiContractTest.java`.

---

### DL-184 — A dashboard assertion must select the panels whose contract it asserts

The container gate fetched the provisioned dashboard from Grafana and required panels 11 and 12 to carry the
Spring Batch item-reader label names. Those label names are panels 31 and 32's contract. Panels 11 and 12
read the application's own `carddemo_batch_*_total` counters and carry six targets each, so the filter failed
twice over: its `length == 2` test was wrong against twelve targets, and every `contains` was false. The
`length == 2` is itself the evidence of how it happened — two is the target count of panels 31 and 32, so the
assertion was written for that pair and the selector was left naming the other.

Both contracts are now asserted, each against the panels that hold it, with the exact target counts stated so
that adding a counter has to be a deliberate edit rather than a silent widening. Two further clauses carry
DL-182's honesty rule onto the **served** document rather than the file: no panel may re-brand itself as the
Gate 3 figure, and panels 11, 12 and 17 must each say what they are. Asserting this in CI is not a duplicate
of the unit test that reads the file — only the CI check can prove that provisioning delivered *that* file.

*Cited by:* `.github/workflows/carddemo-java-ci.yml`,
`config/grafana/dashboards/carddemo-overview.json`,
`src/test/java/com/carddemo/config/BuildAndCiContractTest.java`.

---

### DL-185 — A vulnerability gate that hides findings from its own report is not a gate

The container scans passed `--ignore-unfixed`. That flag does not merely soften a verdict: it removes the
matching findings from the JSON output as well, so the archived report — the durable record a reviewer signs
the gate off from, and the only one, because these reports are untracked — could not show what had been
dropped. A gate describing itself as a strict zero-HIGH/CRITICAL gate while a flag deleted an unbounded
subset from both the verdict and the evidence was neither strict nor auditable.

Every HIGH and CRITICAL is now retained whether or not a fix exists upstream, and the verdict is decided in
the workflow from the retained report rather than by the scanner's exit code. The reason is concrete:
`--exit-code 1` ends the scanner before the breakdown can be printed, so a failing run reported *that* it had
failed and not *what* it had found. Scanning with `--exit-code 0` and then judging lets the same run
enumerate every finding it is failing on, with the fix status of each, and removes the possibility of the
printed count and the verdict disagreeing — previously one came from the report and the other from the
scanner.

The strict/inventory split is unchanged and is a different decision: the application image and the two
digest-pinned Dockerfile bases are gated strictly, while third-party Compose images are inventoried, because
this repository cannot patch inside someone else's binary. **There is deliberately no ignore file.** When a
strictly gated image fails on a finding with no fix available there are exactly two honest actions — move the
digest pin to a rebuilt upstream image, or record a reviewed acceptance for that identifier — and neither is
pre-empted by a flag.

*Cited by:* `.github/workflows/carddemo-java-ci.yml`,
`src/test/java/com/carddemo/config/BuildAndCiContractTest.java`.

---

### DL-186 — One gate inventory, named identically in all three places that name it

The workflow carried three disagreeing counts of its own gates: a header saying five, two step banners saying
"of 4", three saying "of 5", and a summary listing six. A reviewer signing the gates off could not tell which
was authoritative. Six is the count and the summary was the accurate list; the header undercounted because the
container gate was the only failing step with no banner at all, so anyone counting banners counted one short
of the steps that can fail.

The header now states six, enumerates them, and records that three properties are carried by one gate — the
Maven build gate, because they are bound to its verify phase — rather than by three steps. A previous revision
said "the first three properties" were carried there, which was also wrong: the three are the zero-warning
build, the coverage floor and the supply chain scan, and locale hardening sits between them in the list while
being a gate of its own. The banners are renumbered "of 6", the container gate has one, and the summary names
the same six in the same order. A contract test reads all three places and requires them to agree.

*Cited by:* `.github/workflows/carddemo-java-ci.yml`,
`src/test/java/com/carddemo/config/BuildAndCiContractTest.java`.

---

### DL-187 — `String.formatted` is banned outright, because it cannot be given a locale

A page of user identifiers was built with `"USER%04d".formatted(index)`. `String.formatted` has no overload
accepting a `Locale` — it resolves `Locale.getDefault(Locale.Category.FORMAT)` and offers the caller no way to
say otherwise — so under the `ar-EG` locale that the workflow's locale gate re-executes the unit tier in, CLDR
selected the `arab` numbering system and the identifier was rendered `USER٠٠٠١`. Eight characters still, but
not the eight ASCII bytes the fixed-width field holds, so every assertion in that class compared against a
string it could never match. Reproduced before fixing: `Optional[USER٠٠٠١]` did not contain `"USER0001"`.

A module-wide sweep found the reviewed site was one of **four**, not one. The other three are in integration
tests, which the locale gate does not re-execute: a sixteen-digit transaction identifier, a port number
rendered into a Prometheus configuration file, and a sample value rendered into an exposition body that a real
Prometheus scrapes. The sweep also confirmed what was already right — all forty-nine `String.format` calls
already named a locale, every `DateTimeFormatter.ofPattern` already passed one, and no `toUpperCase()`,
`DecimalFormat` or `SimpleDateFormat` existed anywhere. The rule was known; what had never been held to it was
the code that builds the fixtures the locale-invariance suites run against.

The shorthand is now **absent from the module** rather than permitted where its conversions happen to be
locale-independent. A per-call-site rule would require every author and reviewer to classify conversions
correctly forever and would still be wrong the first time a `%s` is handed a `Formattable` or a `%S` is
written. `LocaleDeterminismAuditTest` enforces the absence by reading every source file in both trees, which
is how the integration tier — never re-executed under a hostile locale — is covered at unit-tier cost with no
container. The locale gate and the audit are complementary: the gate observes real behaviour in one tier, the
audit forbids the construct everywhere.

*Cited by:* `src/test/java/com/carddemo/LocaleDeterminismAuditTest.java`,
`src/test/java/com/carddemo/service/UserListPageTokenServiceTest.java`,
`src/test/java/com/carddemo/repository/TransactionRepositoryIT.java`,
`src/test/java/com/carddemo/config/MonitoringQueriesIT.java`,
`.github/workflows/carddemo-java-ci.yml`.


### DL-244 - A newly published high-severity finding against the servlet container has no released fix on any line, so the pin stays at the newest published release and the finding is carried by one narrowly scoped suppression rule that fails the build the moment it stops matching

> **Formerly recorded under `DL-159`.** That identifier was carried by more than one distinct decision, so a citation naming it could not be resolved to a single entry. This decision now has an identifier of its own and nothing in the reasoning below is changed. The entry that keeps `DL-159` is the one the module's own source cites. See entry DL-245.

> **Integrated-state note.** This note was published under a second `DL-159` heading of its own, which made the identifier ambiguous for every citation that names it. The words are unchanged; only the duplicate heading is gone, and the note now sits inside the entry it annotates. See entry DL-222.
>
> **Reconciliation note.** This entry and the `DL-159` entry above were written independently about the
> same finding. Both measurements stand and neither is withdrawn: no released artifact clears the finding
> on any line, and the practical exposure of an embedded container that ships none of the example
> applications is low. The delivered build takes the determination recorded above - one narrowly scoped
> suppression rule, named against the three embed artifacts and the single identifier, with
> `failBuildOnUnusedSuppressionRule` left `true` so the rule fails the build the moment it stops matching
> and a patched release can no longer be adopted quietly. Read the sentences below that say the gate keeps
> reporting the finding and that no suppression file exists as the position at the time this entry was
> written; the evidence in them is unchanged, the disposition is the one above.


**Why this entry exists.** `pom.xml` carries `<tomcat.version>` as a security override, documented as
clearing "a cluster of high severity findings against the container shipped by default on this
framework line". A reader who now runs the supply-chain gate sees it fail on that same coordinate and
is entitled to know whether the pin was neglected, whether a fix was declined, or whether something
else is true. Something else is true, and this entry records it with its evidence.

**What the executed scan reports.** `dependency-check-maven` 12.1.3, running as the `verify`-bound gate
against the vulnerability database in the build user's home directory, reports `CVE-2026-66299` at base
score 7.5 against `tomcat-embed-core-10.1.57.jar`. The gate fails at 7.0, so the build fails. The
database's own affected ranges are `10.1.24` up to but excluding `10.1.58`, `9.0.89` up to but excluding
`9.0.121`, and `11.0.1` up to but excluding `11.0.25`.

**The upgrade was attempted, and it cannot be taken yet.** Raising the property to `10.1.58` was tried
first, because that is what every other override in the file did when a finding crossed the threshold.
It fails: `10.1.58` is not published. Maven Central's version metadata for the coordinate lists
`10.1.57` as the newest final release of the 10.1 line, and resolution of `10.1.58` fails identically
for the core, websocket and expression-language artifacts. The neighbouring line offers no route out
either - the fix named there is `11.0.25` and Central's newest 11.0 release is `11.0.24`, itself inside
the affected range - and that line implements a later servlet specification than this framework line
manages, so moving to it would be a framework change rather than a patch. **No released artifact clears
this finding on any line as of this checkout.** The advisory has been published ahead of the artifacts,
which is an ordinary and temporary state.

**What was decided, and how the finding is carried.** The value stays at `10.1.57`, the newest published
release, because a pin that cannot resolve fails the build for every reader and protects none of them. The
finding is then carried by **one narrowly scoped suppression rule** in `owasp-suppressions.xml`, named
against the three embed artifacts and that single identifier and nothing else, with
`failBuildOnUnusedSuppressionRule` left `true`. That last setting is what makes the entry a determination
rather than silence: the moment a patched release is adopted the rule stops matching and **the build fails
on the unused rule**, demanding its removal. The remedy when `10.1.58` publishes is a single-digit change to
one line plus deleting the rule - the fix is identified, not undecided.

> **The position at the time this entry was written, retained as history.** This entry was originally
> written to record that **no suppression file was added and none had ever existed**, on the grounds that
> DL-090 and DL-115 both answered findings by upgrading rather than by argument and that a suppression would
> convert a reported finding into a silent one. Every measurement in that reasoning stands, and the
> disposition does not: the sentences below that describe the gate continuing to fail visibly on this
> finding describe that earlier state. The delivered build carries the scoped rule described above. Read
> this entry's evidence as current and its original disposition as superseded.

**This finding is not attributable to any change in this module.** No dependency declaration, no
managed version and no exclusion moved. The finding was published after the pin's value was chosen and
entered the vulnerability database the gate reads. A supply-chain gate that is genuinely executed rather
than declared will fail from time to time with no local change; that is the gate working.

**Exposure while it stands, measured rather than assumed.** The finding's text describes uncontrolled
resource consumption in the container's WebSocket chat *example* application, and states that
deployments which removed the examples web application are unaffected. This module embeds the container
and deploys none of its examples. That was verified by inspecting the artifacts rather than by
reasoning about them: across `tomcat-embed-core`, `tomcat-embed-websocket` and `tomcat-embed-el` at
10.1.57 - 2,036 entries in total - there is not one entry whose name contains `example` or `chat`, and
not one `.jsp`, `.war` or `webapps/` entry of any kind. The vulnerable component is absent from the
classpath, so practical exposure for this deployment is low. That lowers urgency and settles nothing
else: the match is at product level, the gate compares versions, and the finding is carried by the scoped
rule rather than dismissed.

**How the rest of the build is evidenced while this stands.** With the rule wired, `./mvnw -B clean verify`
runs the scan in the same pass as the compilation, test, coverage and determinism gates and completes, so no
split-pass workaround is needed and the scan's own report is still written for the evidence page. Before the
rule existed the gates were run with the scan explicitly skipped for that pass and the scan was then run on
its own so its output was recorded rather than folded into a single pass/fail; that arrangement is historical.
Either way both results belong in the evidence: the module's own gates pass, and the supply chain carries one
unremediable high finding named here.

**One neighbouring finding is deliberately left standing, and it is a different case.** The same scan
reports `CVE-2026-41178` at 5.3 against `opentelemetry-semconv-1.43.0.jar`. It sits below the gate
threshold, and non-applicability rests on **product and target-software identity**: both of the
vulnerability's configuration rows carry the target-software component `go`, and the described defect is in
the Go implementation's baggage-header parsing. The Java artifact of a similar name is not the affected
product, so there is no version to move to.

> **Correction.** An earlier revision of this paragraph also argued that "the database's own affected range
> ends *below* the version installed here." That statement is false, and it is worth stating why rather than
> deleting it, because the reasoning it used is the reasoning to avoid. The local vulnerability data carries
> **two** configuration rows for this identifier, not one: a range row with `versionEndExcluding = 1.42.0`,
> which is the one that earlier claim described, and a **second row naming version `1.43.0` exactly**, with
> no range bounds at all and marked vulnerable. The exact-version row is what the installed artifact matches,
> and it is why the scan reports the finding at all — had the range row been the only one, the version here
> would have fallen outside it and nothing would have been reported. A claim contradicted by the very output
> it was written to explain should not have survived. The `go` target-software component appears on **both**
> rows, so the identity argument is unaffected by this correction; only the range argument is withdrawn.

That version is itself a security override, recorded in `pom.xml` as clearing two findings at 7.3 and 7.0,
so moving it on the strength of an advisory against a different product would trade two applicable findings
for one that does not apply. It is reported, visible, and not suppressed.

*Cited by:* `pom.xml`.

---

### DL-188 — The confirmation turn re-runs the edits, because the screen that made them redundant no longer exists

**What the legacy does.** `1200-EDIT-MAP-INPUTS` leaves before its twenty-four edits when the conversation
is already in the awaiting-confirmation state, at `app/cbl/COACTUPC.cbl` L1464. That is safe on the
mainframe for a reason that is not visible in the edit driver at all: the confirmation screen protects
every field. The attribute paragraph at L2986-L3006 selects the clause that leaves each field exactly as
L3441-L3496 set it, and the attribute those lines set is *protected with the modified-data tag on* - so the
terminal re-transmits the image the edits already passed, byte for byte, and the operator cannot alter a
character of it. Re-editing it would have been redundant work on values that could not have changed.

**Why the translation cannot inherit that.** This port is stateless. The awaiting-confirmation state is
derived from the arriving request rather than read out of a communication area, and the image is supplied
by the caller rather than by a protected screen. Both halves of the legacy's guarantee are therefore
absent: nothing establishes that the submitted image is the one that was validated, and nothing prevents a
caller from substituting a value the edits would have refused. Carried across literally, the skip made the
entire edit cascade advisory - the credit-score range, the two permitted status codes and the date cascade
were all reachable only on the turn *before* the one that wrote.

**Decision.** The edits run on the confirmation turn as well. On a faithful echo they pass, L1671-L1675
restores the awaiting-confirmation state and the write proceeds exactly as it did before, so an honest
caller sees no difference at all; on an altered echo they refuse, which is what the legacy would have done
had the operator been able to type the value on the detail screen. The validating turn and the confirming
turn now report identically for the same image - same summary text, same field-error ordering - which is
the property that makes the cascade enforceable rather than advisory.

**One state the legacy could not be in, and what is done with it.** A derived confirmation state can arrive
carrying an image that turns out to hold no change at all. The legacy could never reach that combination:
the awaiting-confirmation state is reached only from a turn on which a difference was found, at L2585-L2591,
and the save key is refused in every other state by the validity test at L905-L916. Rather than invent a
behaviour for it, the derived state is demoted to the detail screen - the state the legacy would have been
in - and the no-change arm at L2585 then leaves it there. Nothing is written and the information message is
the prompt for changes, which is exactly what the legacy answers a submission that changed nothing. Before
this, such a turn entered the write range: it reported "Changes committed to database" beside the
no-change text and rewrote the customer row, which re-sealed a protected column under a fresh initialisation
vector and left the caller's before-image stale for the turn that followed.

**What does not change.** The two early exits the legacy actually reaches are untouched: a turn before any
detail has been fetched still edits only the search key, and a turn whose predecessor completed still leaves
without editing. Field flags are still cleared on the way out of the no-change exit, and a confirmation turn
that passes its edits still carries no decoration, because a passed edit leaves the flag in the same cleared
state the skip used to leave it in.

*Cited by:* `service/AccountUpdateService`.

---

### DL-189 — A committed rewrite re-mints the before-image, because the response carries it instead of a communication area

`9500-STORE-FETCHED-DATA` copies both fetched records into the communication area so the next turn can ask
whether anybody else moved them, and `9700-CHECK-CHANGE-IN-REC` compares against that copy. This module
seals the same question into a token the response carries and the caller echoes, and it deliberately does
not re-mint when a token arrives: minting over records read moments earlier would compare them with
themselves, which always agrees and would make the check unreachable.

That left one point uncovered. When the write range commits, the records the caller was shown a moment ago
are no longer the records on file. The legacy did not have to say anything about it, because its completed
state at L2625-L2632 resets the conversation and the following turn re-fetches; a derived state has no such
reset, so the caller continues with the token it was last given. Echoing the pre-write token presented an
image and a before-image that disagreed, and the very next genuine change was refused as somebody else's -
a conflict the turn had caused itself, escapable only by re-fetching.

**Decision.** The before-image is re-minted from the rewritten records at the success point of the write
range, and nowhere else. The two mint sites cannot overlap: the fetch paragraph mints only when no image
arrived, and this one only after an image has been superseded. Conflict detection is not weakened by it -
a token minted before an *unrelated* actor's change still fails to verify, which was confirmed by exercising
both arms: the pre-write image is still refused, and the re-minted one is accepted.

*Cited by:* `service/AccountUpdateService`.

---

### DL-190 — Two widths the screen owns, restated where the wire had lost them

**The postal code, presented at the map item's width.** `CUST-ADDR-ZIP` is ten characters and the map item
it is shown in is five, and the presentation move at `app/cbl/COACTUPC.cbl` L2843 crosses that boundary the
way a COBOL `MOVE` into a narrower alphanumeric item always does: it keeps the leading characters and
discards the rest. The update screen had been publishing the stored width instead. That broke a round trip
rather than merely showing more than the screen could: the value exceeded the width the response contract
declares for the component and was refused by the request contract when the caller echoed it back, so the
thirty seeded customers whose postcode carries the wider form could not complete the screen at all - and the
view turn, which had always cut the value, disagreed with the update turn about the same stored value. The
move is now reproduced, through a helper that states the semantics once, and the two turns agree.

**The account identifier, bounded at the field's own width.** A 3270 field cannot transmit more characters
than it declares, so a longer value has no counterpart on the original screen. The view turn took its
search key from an unbounded query parameter, and a longer value was not refused but silently narrowed
further down: the turn then answered, with no error, for a *different* account than the one asked for,
because every layer below saw a well-formed eleven-digit key. The parameter now declares the screen field's
width, which the update screen's body-bound component already declared, so the two turns agree on what the
field can hold. Nothing reachable was lost: an absent parameter still reaches the outcome that asks for one
and an empty one still reaches the no-input answer, both of which are screens the legacy composed.

*Cited by:* `service/AccountUpdateService`, `api/AccountController`.

---

### DL-191 — The credential is folded to upper case before it is hashed, because the terminal folded it before the program saw it

**The two halves of a credential's life have to agree.** The sign-on program folds *both* submitted values
at `app/cbl/COSGN00C.cbl` L132-L136 and compares the folded secret at L223, which DL-138 records: a
lower-case secret authenticates on the mainframe, and it has to authenticate here. The maintenance programs
store what they are handed - `app/cbl/COUSR01C.cbl` L157 and `app/cbl/COUSR02C.cbl` L227-L228 - and on the
mainframe what they are handed is already folded, because a credential never reaches the program in any
other form. The fold is not a rule those programs apply; it is a property of every value that arrives.

**What went wrong without it.** There is no terminal in front of this module. The administrative screens
were hashing the value exactly as submitted while the sign-on path verified the folded value, so every
identity created or maintained with a lower-case character was locked out at its first sign-on - and the
administrative screen reported success, because nothing on that path ever verifies what it just wrote. The
defect was invisible against the seeded estate, whose credential literal is already upper case.

**Decision.** The submitted credential is folded at the single point it enters the transaction, so the two
write paths and the change detector all work on one form of it, and that form is the one the sign-on path
verifies. The fold is the estate's own ASCII table substitution rather than the platform method, for the
reason recorded against every other fold in this module - D-18 and DL-023 - and it changes no length, so the
width the request contract asserts still holds and the emptiness cascade reports exactly what it did before.
An unsupplied credential stays unsupplied: the update screen distinguishes a credential that was not
submitted from one submitted empty, and folding absence into emptiness would turn a name-only update into a
rejected turn.

**What this is not.** It is not a credential rule. No strength, expiry, history or lockout is introduced,
because the legacy tests only that the field is non-blank and adding a rule would reject input it accepted.
It does not weaken verification either: a genuinely different credential is still refused, and a re-keyed
credential differing only in case is correctly reported as no change rather than re-hashed - which is the
same answer the mainframe gave, since the difference could not have been transmitted.

*Cited by:* `service/UserManagementService`.

### DL-192 — The category-balance contract is asserted as the estate wrote it: one shared key-group name kept apart, one prefix left uneven, a miss that is not a failure, and a balance that truncates

**Why this table needed an integration test of its own.** It holds the widest composite key in the
schema — 17 bytes in three components, `KEYS(17 0)` over a 50-byte record in
`app/jcl/TCATBALF.jcl` — and three of the four decisions below are the kind that compile cleanly, read
plausibly and still produce the wrong bytes. A repository test is where each of them stops being a
comment and becomes a failing assertion.

**One: the shared key-group name stays apart, and is proven apart from the server's own catalogue.**
D-37 records that `app/cpy/CVTRA01Y.cpy` and `app/cpy/CVTRA04Y.cpy` name their key groups identically
over two unrelated keys, and that they are modelled as two unrelated types. What was missing was
anything that would *fail* if a later refactor merged them. The test now reads both primary keys out of
the catalogue and asserts that this one has three columns leading with the account identifier, that the
reference table's has two and contains no account identifier at all, that their leading columns are
unequal, and that 17 minus 6 is exactly the account identifier's own 11 bytes. The assertion is made on
column names rather than on Java types, and the test deliberately never mentions the other key's
identifier class, so the two remain unrelated in the test as well as in the model.

**Two: the uneven column prefix is preserved and asserted, not tidied.** Inside this one table the
three key columns carry `trancat_` while the balance carries `tran_cat_` — an inconsistency that is the
copybook's own, and the same kind of unevenness D-39 records between the two 350-byte layouts.
**Decision:** transcribed as found, for D-39's reason: a column name is part of a mapping validated
against the migrated schema at start-up, so regularising one name would fail validation rather than
quietly work. The test asserts the four column names in record order, so the asymmetry is now held by an
assertion instead of by discipline.

**Three: a key that resolves to nothing is an ordinary outcome, never an error.** The posting program
opens this file for input-output, keeps a one-character flag, and at `app/cbl/CBTRN02C.cbl` L481 treats
the success status and the record-not-found status **alike** before branching at L495 to either a create
at L503 or an update at L526. Nothing about a missing row is exceptional there. **Decision:** both
branches are the inherited `findById` plus the inherited `save`, and merge semantics supply the rest —
no upsert, insert or create method is declared, and no bulk write exists anywhere. The test proves the
two branches separately, by row count rather than by inspection: a store against an absent key raises
the count by exactly one, a store against an existing key changes the balance and leaves the count
alone, and a full read confirms one row under that key rather than two. A third assertion states the
premise the whole contract rests on — an absent key yields an empty result and **throws nothing**.

**Four: the balance truncates, and the seed cannot prove it, so a fixture does.** DL-013 and D-02
record why every store into a two-decimal field truncates toward zero: the rounding keyword occurs
nowhere in the estate, so `RoundingMode.DOWN` is the faithful policy and the conventional
nearest-neighbour choice would differ by one hundredth on roughly half of all interest computations.
This table is the interest computation's own left operand, so it is where that policy matters most — and
it is also where the seed is least able to demonstrate it. Every one of the fifty seeded balances is
zero: measured across the whole of `app/data/ASCII/tcatbal.txt`, 2,550 bytes of 50-byte records, the
11-byte balance field holds exactly **one distinct image and it represents zero**. A zero opening
balance is correct for a pre-posting state and wrong for a parity proof, because it has no sign and no
magnitude to lose. **Decision:** the seed is asserted for what it is — all fifty read back as zero at
the declared scale, and exactly one distinct value exists — and every signed or large-magnitude claim is
made against a fixture built for the purpose. A four-fraction-digit negative amount is asserted to store
as its truncated form and explicitly **not** as its nearest neighbour, so the policy cannot be satisfied
by the wrong one; a negative balance is asserted to keep its sign, its magnitude and its scale; and the
widest storable value is asserted to survive all nine integer digits and to render without an exponent,
because the 11-byte field of a fixed-width record image is written from the plain text form.

**What this entry does not introduce.** No access path. This table is batch-only, it is absent from the
resources registered to the online transaction manager, and it has no alternate index in the estate, so
the test asserts that **no secondary index exists** rather than exercising one, and the repository gains
no derived finder. No association either: the account foreign key is asserted by name and by
enforcement — a balance naming an account no account row carries is refused, and the refusal leaves the
row count untouched — while the entity keeps no association attribute at all, per D-38.

**Determinism, and why the reads are not transactional.** The shared server outlives every class, so a
test asserting an absolute row count establishes its own starting point through the support base's reset
rather than inheriting whatever ran before it; this matters in practice, because one batch integration
test inserts rows into this table and does not remove them. Test methods are deliberately not
transactional: a transactional method would let a read answer out of the same persistence context's
identity map, so a round-trip assertion would compare an object with itself and prove nothing about the
stored row. Each inherited call therefore commits on its own, as the per-record legacy posting does, and
only the class's own reserved keys are removed afterwards. The context is scoped to a named
configuration rather than booting the whole application, because an unscoped component scan reaches the
configuration classes nested in other test classes and two of them contribute the same repository bean
definition.

*Cited by:* `repository/TransactionCategoryBalanceRepositoryIT`.

---

### DL-193 — The cross reference keeps its fourteen filler bytes out of the schema and its three foreign keys out of the mapping, and both omissions are asserted rather than assumed

**Two claims about `card_cross_reference` are easy to mistake for defects, and neither is one.** Both are
settled here because the table is the estate's universal card-to-account and card-to-customer resolution
point - ten programs take the layout of `app/cpy/CVACT03Y.cpy` - so a reviewer meeting either claim for the
first time is likely to be reading the busiest table in the schema.

**Claim one: the record is fifty bytes and the schema holds thirty-six of them.** The layout declares a
16-byte card number at offset 0, a 9-byte customer identifier at offset 16 and an 11-byte account
identifier at offset 25, and then fourteen bytes of filler at offset 36. Sixteen plus nine plus eleven is
thirty-six; thirty-six plus fourteen is the fifty the cluster definition in `app/jcl/XREFFILE.jcl` declares.
The filler is deliberately neither an attribute of the entity nor a column of the table, because it carries
nothing and a column for it would invite a writer to put something there.

**Why that matters beyond tidiness: it is what reconciles two file sizes that otherwise look like data
loss.** `app/data/ASCII/cardxref.txt` measures 1,850 bytes and
`app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS` measures 2,500, and both hold the same fifty records. Fifty
rows of thirty-six mapped bytes plus one line terminator each is 1,850; fifty rows at the full record length
is 2,500. The two files differ by 650 bytes, the record payloads differ by the 700 filler bytes, and the
fifty line terminators of the text form account for the remaining fifty. A loader that expected fifty data
bytes per text row would misparse every record, which is precisely the mistake the arithmetic above prevents.
Every one of those figures is asserted, not merely written down: see
`repository/CardCrossReferenceRepositoryIT`, which measures the three stored widths on a seeded row, asks
the server to measure them independently, and checks all four arithmetic relations.

**Claim two: three foreign keys originate here and the entity declares no association.** Of the six foreign
keys `V2__create_indexes.sql` creates for the whole schema, three are declared on this one table -
`fk_card_xref_card`, `fk_card_xref_account` and `fk_card_xref_customer` - which is more than any other table
declares and is why `V3__seed_reference_data.sql` loads this table last of the four related ones. They are
constraints of the database and of nothing else. The entity holds three plain text attributes and declares
no collection, no owning or inverse side, no join-column declaration and no fetch plan, on the same ground
DL-032 records for the reference tables: this row exists so that a lookup can go from one key to another
*without loading anything*, and an association would defeat exactly that. Under validate-only schema
checking each one would also add a start-up failure mode restating a column name the entity already
declares.

**Faithful beats idiomatic, and the absence is therefore tested.** An omission that nothing exercises is
indistinguishable from an oversight, so the integration test proves all three keys three ways: that they are
declared under those names against those parent tables, that they hold for all fifty seeded rows, and that
each is enforced independently - three refused inserts, each naming an absent parent in one column and a
present parent in the other two, so no refusal can be attributed to the wrong constraint. Isolating the
account and customer keys requires the offered card number to exist first, which is the seeding order
demonstrating itself.

**What this entry does not re-decide.** The account path returning a list rather than a single row, and the
first-match rule's home, are DL-121 and DL-164 and are unchanged. The card primary account number carrying
no encryption, tokenisation or masking remains the open gap D-14: the legacy design applies none, inventing
one would be feature expansion, and this entry settles only that no test or diagnostic of this table widens
that gap by rendering the value.

*Cited by:* `domain/CardCrossReference`, `repository/CardCrossReferenceRepository`,
`repository/CardCrossReferenceRepositoryIT`, `src/main/resources/db/migration/V1__create_schema.sql`,
`src/main/resources/db/migration/V2__create_indexes.sql`.

---

### DL-194 — The sign-on role code is stored unconstrained, because the legacy split has no third branch

**Context.** `user_security.sec_usr_type` is the single character that decides whether an operator reaches
the administrative surface. Only two codes are declared anywhere in the estate — the communication-area
copybook `app/cpy/COCOM01Y.cpy` names exactly two condition names on that field at L27 and L28 — so the
obvious translation is an enumerated attribute, a check constraint, or a Bean Validation pattern. Any of the
three would reject a third code at the persistence boundary.

**Why all three are wrong.** The legacy sign-on path at `app/cbl/COSGN00C.cbl` moves the stored character
into the communication area at L227, tests **only** the administrative condition at L230, and reaches the
main menu through an **unconditional** alternative at L235, the construct closing at L240. There is no third
branch and no error path for a code the estate never declared. Every non-administrative value — including one
nobody anticipated — therefore routes to the main menu without raising anything. A constraint would refuse to
store a row the legacy system stored and routed, which is a behavioural regression rather than a hardening.
Three mechanical objections point the same way and each is independently sufficient: the column is
`VARCHAR(1)`, so persisting an enumerated constant's name would not fit and would fail schema validation or
truncate; persisting its ordinal would need an integer column and would fail validation too; and an attribute
converter would place translation logic inside `domain`, breaching the one-way layer boundary.

**Decision.** The attribute is a raw `String`. No `@Enumerated`, no `@Convert`, no check constraint in
`V1__create_schema.sql` and no Bean Validation pattern is applied to it. The enumerated form
`domain/enums/UserType` exists and is used at the **service** layer, where an unrecognised code resolves to
the standard role rather than throwing — which is the same answer the unconditional alternative gave.
Interpretation is a service concern; storage is tolerant.

**Why this is asserted rather than trusted.** Tolerance that nothing exercises is tolerance that a later
change can remove silently, because every seeded row carries one of the two declared codes and no seeded
assertion would notice a new constraint. `repository/UserSecurityRepositoryIT.java` therefore writes a
purpose-built row whose role code is neither declared value and asserts that it persists and loads intact,
and its seed nest separately asserts that all ten seeded rows nonetheless carry only the two declared codes.
The two assertions together say the column *accepts* a third code while the delivered data *contains* none.

*Cited by:* `domain/UserSecurity.java`, `config/SecurityConfig.java`. Proven by
`repository/UserSecurityRepositoryIT.java` and `service/UserSecurityCredentialIT.java`.

---

### DL-195 — The customer table's column names are not uniformly prefixed, and the asymmetry is asserted rather than tidied away

**Every field of the record carries the entity prefix; only three columns keep it.** The five-hundred byte
customer layout of `app/cpy/CVCUS01Y.cpy` names all eighteen of its persisted fields with the same
entity prefix, and `V1__create_schema.sql` keeps that prefix on exactly three of the columns it creates -
`cust_id`, `cust_ssn` and `cust_dob` - while the other fifteen drop it. Where a field name is multi-part the
column separates its trailing digit with an underscore: three address lines and two telephone numbers. There
is no rule behind either choice that would let a reader derive one column name from its field name.

**Why it is not corrected.** The migration owns the table and Hibernate *validates* the mapping against it
rather than generating it, so a column renamed in the entity to look consistent does not produce a tidier
schema - it produces a start-up failure. Renaming in the migration instead would be a schema change on a
delivered artefact, and every seed script, every mapper offset and every catalogue assertion would have to
move with it for no behavioural gain. Faithful beats idiomatic, and here faithful costs nothing but the
temptation to regularise.

**Decision.** The asymmetry is transcribed once, in the mapping table the entity declares and the
integration test asserts, and it is *asserted positively* rather than left to be noticed: the test reads the
live catalogue and requires that precisely those three columns keep the prefix, that the remaining fifteen
drop it, and that no column name attaches a digit directly to a letter. The last of those is written as a
pattern the whole column list must satisfy rather than as a list of misspellings to look for, so a future
column that collapsed the separator fails without anyone having had to predict the exact wrong name.

**What this is not.** It is not a naming convention for new columns - none are being added - and it is not a
claim that the legacy names are correct. `app/cpy/CVACT01Y.cpy` L10 misspells its expiry field and the
customer record's own alternate view in `app/cpy/CUSTREC.cpy` spells its date-of-birth field differently
again; those are recorded where they arise. This entry is only about not repairing a naming inconsistency
that the schema depends on.

*Cited by:* `domain/Customer`, `repository/CustomerRepositoryIT`.

---

### DL-196 — No credit-score range is enforced at the persistence layer, because twenty-one of the fifty delivered rows would fail it

**The 300-to-850 range is screen-level edit validation and belongs to one path only.** The account-update
transaction applies it as one clause of its edit cascade; nothing in the record layout, the provisioning job
or the read paths applies it at all. The score is three characters of an alphanumeric record field, carried
as bounded text with its leading zeros intact, and the module introduces no numeric type for it.

**The measurement that settles it.** Counted directly against the delivered reference fixture and again
against the migrated table: **twenty-one of the fifty customer rows carry a score below the screen's lower
bound, the lowest being the three-character value `001`**. A check constraint in `V1__create_schema.sql`, or a
Bean Validation annotation on the entity, would therefore refuse forty-two per cent of the data the legacy
system stores - and it would refuse it during the seed migration itself, so the failure would arrive as a
broken build rather than as a rejected screen.

**Decision.** `fico_credit_score` carries no range, digit or pattern constraint, the entity declares no
validation annotation, and the integration test asserts that absence from three directions: the table's only
check constraint is the one over the business key, the lowest-scoring delivered row loads through the
repository and returns its three characters unchanged, and a purpose-built row scoring outside the range at
either end persists and reloads. Asserting the absence is the point - a constraint added later in good faith
would otherwise pass review and fail the seed.

**What this is not.** It is not permission to accept anything anywhere. The range is still enforced where the
legacy enforced it, on the update surface, and this entry does not move or weaken that. It is also not an
argument against constraints in general: the same table carries a check constraint pinning its key to nine
digits, because that one is a property of the record image rather than of a screen.

*Cited by:* `domain/Customer`, `service/AccountUpdateService`, `repository/CustomerRepositoryIT`.

---

### DL-197 — An integration test's staging directory is named for the process that owns it, because a fixed child of the platform temporary directory is shared by every build on the host

**A destructive cleanup over a shared path is not isolation.** The interest job's integration test stages its
generation on disk and clears that directory before each of its methods, deleting every file it finds there.
Under a fixed name below the platform temporary directory that directory is not the test's own: any second
build on the same host resolves the same path. Two builds then clear each other's staging area, and the one
that loses the race sees a step that succeeded and a job that failed at its terminal publication, because the
completed artefact it registered is no longer a regular file by the time the publication reads it. The verdict
is honest - DL-180 records why a failed publication means nothing was published - but the cause is entirely
outside the run that reports it, which is what makes such a failure so expensive to diagnose.

**Decision.** The directory is named for the owning process, so the sharing is removed rather than timed
around, and the name is computed once and published through a dynamic property because an annotation cannot
compute one. The cleanup helper reads the same constant, and its documentation now states the precondition it
depends on: an unconditional deletion is safe only over a directory this process alone can write. The combine
job's test already took this approach with a per-run directory; this brings the interest job's test into line
with it rather than inventing a second arrangement.

**The failure is now self-diagnosing.** The terminal listener logs the failure *type* and deliberately never
the throwable, so a bare status mismatch told a reader nothing about which of the publication's several
`IllegalStateException` sources had fired. The assertion now reports the execution's own recorded failure
reasons, so a recurrence names itself instead of sending the next reader to the logs.

**What this is not.** It is not a change to the publication contract, the retention policy, the lock or the
object-store edge - DL-178 through DL-181 stand unchanged - and it is not a timeout, retry or tolerance. No
production path reads a staging directory chosen by a test; the fix is confined to how one test names the
directory it owns.

*Cited by:* `batch/InterestCalculationJobIT`.
### DL-198 — No foreign key originates from the account table, and the group identifier could not carry one

**The candidate that looks like a foreign key and is not.** The account row's ten-character group
identifier names a disclosure group, and a reader coming to the schema fresh reads that as a foreign key
waiting to be declared. It cannot be one, for two independent reasons and either alone is decisive.

**Reason one - it is a partial, non-unique key.** The disclosure-group key is composite: the ten-character
group identifier, then a two-character transaction type code, then a four-digit category code. The group
identifier is only its leading portion, and it recurs seventeen times per group across the fifty-one seeded
disclosure rows, so it is not a key of that table and no constraint can point at it. The interest program
composes the whole key at run time instead, and falls back to a documented default group when no row
matches - behaviour referential integrity cannot express, because a fallback is precisely what a constraint
exists to prevent.

**Reason two - the measured seed would be rejected outright.** Every one of the fifty seeded accounts
carries a group identifier of exactly ten spaces, which matches none of the three seeded group identifiers.
A constraint would therefore refuse the entire reference seed on the first row. That uniformity is
load-bearing rather than accidental: it is why every seeded account exercises the default-group fallback
rather than a direct rate lookup, which is what makes that arm of the interest calculation reachable from
delivered data at all.

**Decision.** `account` originates no foreign key of any kind - not to the disclosure group, not to
anything. Every foreign key that touches this table points AT it, from the card, cross-reference and
category-balance rows. The absence is asserted, not merely intended: the repository integration test reads
the catalogue back and fails if a constraint ever appears on the outbound side, and separately watches a
child insert be refused so that the inbound keys are proven enforced rather than merely declared.

**Relationship to neighbouring entries.** D-38 decided the converse case - that no transaction,
daily-transaction or category-balance row references a reference table - and DL-035 decided that a padded
key and a trimmed key are different keys everywhere, which is what makes the ten-space value a value rather
than an absence. This entry is the account side of the same stance, recorded separately because the group
identifier is the one column in the schema a reviewer is most likely to propose constraining.

*Embodied in:* `V1__create_schema.sql`, `V2__create_indexes.sql` (decision B), `domain/Account.java`,
`repository/AccountRepository.java`.
*Cited by:* `src/test/java/com/carddemo/repository/AccountRepositoryIT.java`.

---

### DL-199 — The processing-timestamp window keeps no repository query, and its character semantics are pinned by the schema tier instead

**DL-122 describes a query this module no longer has.** That entry reasoned about the asymmetric
predicate the reporting window needs — a bare column on the lower bound, a ten-character `SUBSTRING`
on the upper — and then refined the method that carried it into a slice-returning form with a
redundant pre-bound. The method itself has since been removed from `repository/TransactionRepository`:
the reporting job reads its input through the bounded ordered scan on
`repository/TransactionScanRepository` and applies the window itself, in character comparisons, in
`service/TransactionReportService`. Nothing in `src/main/java` contains a `SUBSTRING`, a slice, or any
processing-date finder, and no production caller wants one. A second contract for a selection that
already happens elsewhere is dead code, so it was removed rather than kept.

**The reasoning had to outlive the query, because the trap it guards against is still live.** The
legacy sort addresses the processing date as ten bytes at one-based 305 inside a twenty-six character
field, and admits a record inclusively between two ten-character bounds. Compare the *whole* column
against a ten-character end bound and every record processed *on* the end date disappears, because a
longer string whose prefix equals a shorter one sorts above it. Delete the query and that hazard does
not go away — it moves to whoever next writes a comparison against that column, in Java or in SQL.

**Decision.** The half of the contract the schema owns is asserted directly against the server, in
`repository/TransactionRepositoryIT`, so the reasoning is pinned by an executing test rather than by
this paragraph alone. Six assertions carry it: a record whose timestamp is the end date followed by a
real time component is admitted by the prefix comparison and *dropped* by the whole-column comparison
— shown three independent ways, through the contract predicate, through the naive predicate, and
through the raw character comparison; both bounds are inclusive and the days either side are excluded;
an unprocessed record carrying twenty-six blanks is excluded by the **lower bound alone**, with no
emptiness test anywhere, because blanks sort below the digits of any date; a window over a quiet
period returns an empty result rather than nothing at all; and the plan is inspected to confirm the
**bare** lower bound is the predicate that reaches `idx_transaction_tran_proc_ts` while the prefix
upper bound is carried as a filter — which is precisely why only the upper bound is wrapped. The plan
is taken with sequential scanning disabled for that one transaction, because the table holds only the
rows a test just wrote and at that size the engine would read every row whatever indexes exist; only
the *shape* of the plan is asserted, never a cost, a row estimate or a duration.

**What this is not.** It is not a reinstatement. No method was added to the repository interface, and
nothing here gives the window a second home. It is also not a contradiction of DL-122: that entry's
predicate reasoning is upheld in full and is the reason the assertions above are written as they are.
What changed is only where the predicate lives.

**Superseded on that last point, and only that point, by DL-276.** The selection is now a declared
repository query again and the report job calls it. The predicate reasoning recorded here is unchanged
and is exactly the predicate that query carries; what DL-276 changes is where the selection is
performed and why.

*Cited by:* `repository/TransactionRepositoryIT`, `repository/TransactionRepository`.
*Supersedes the location, not the reasoning, of:* DL-122.
*Superseded on location, not reasoning, by:* DL-276.

---

### DL-200 — No aggregate over the transaction master beyond the single maximum

**Context.** `repository/TransactionRepository` declares two queries - the processing-date window
selection of DL-276, and `MAX` over the
identifier column. The temptation is to add a total: a report that prints account and page totals
looks like it wants `SUM`.

**Decision.** No `SUM`, `AVG`, `COUNT` over amounts, or any other aggregate is declared, and none is
asserted to exist. The report program the reporting service reproduces, `app/cbl/CBTRN03C.cbl`,
contains no arithmetic statement at all: it accumulates its page and account breaks line by line and
renders them at the fixed 133-character width through the utility-layer formatter. A set-based total
would relocate that accumulation into the database and, because the estate declares no rounding
anywhere and every store into a two-decimal field truncates toward zero, it would also change where
truncation happens and therefore what the report prints. The one aggregate that *is* declared earns
its place by reproducing a specific legacy read — the backward browse that finds the highest existing
key — and is documented under DL-018 and DL-149.

*Cited by:* `repository/TransactionRepository`, `repository/TransactionRepositoryIT`,
`service/TransactionReportService`, `util/ReportLineFormatter`.

---

### DL-201 — Both transaction timestamps stay bounded character data, and one ascending card-number ordering serves two differently-typed legacy sorts

**The timestamps are character columns and are never converted.** `tran_orig_ts` and `tran_proc_ts`
are declared as bounded character data of width twenty-six and are read, compared, stored and asserted
as text from end to end. No temporal type and no conversion is applied to either column anywhere in
the module, and an unprocessed record carries twenty-six blanks rather than an absent value — which is
a value a temporal type could not hold at all, and which is what lets the window's lower bound exclude
it without an emptiness test. Converting either column would import a calendar's opinion about a field
the legacy addressed only by byte position, and would make the twenty-six blanks unrepresentable.

**One ordering, two typings.** The reporting procedure types the sixteen card-number bytes at
one-based 263 as zoned decimal, while the statement job types the very same bytes as character. For an
unsigned, zero-padded sixteen-digit lexeme those two orderings are identical, so a single ascending
order is faithful to both jobs rather than a compromise between them — which is why no second,
differently-typed ordering exists.

**Decision.** The window assertion in `repository/TransactionRepositoryIT` orders by card number
ascending and by nothing else, and it gives each admitted record a *distinct* seeded card number so
that the ordering is total without a tie-breaking second key. That matters: the card number is not
unique across transactions in general, and DL-122 records that a reader advancing through slices needs
a refining second term to avoid returning or skipping a row. Distinct fixtures obtain a deterministic
expectation without asserting a second ordering term that the legacy sort does not declare. The
fixtures are deliberately arranged so their card order differs from both their identifier order and
their date order, so the assertion can only pass on a card-number ordering; the test asserts that too.

*Cited by:* `domain/Transaction`, `repository/TransactionRepositoryIT`,
`V1__create_schema.sql`.

---

### DL-202 — A repository integration test names its own Spring configuration, because a discovered one sweeps the test tree into the context

**Context.** The identifier rule this module reproduces is a read-modify-write: read the highest key,
add one, insert, all inside one boundary. Asserting it — and asserting that a rolled-back allocation
*reissues* the value it consumed, which is the property that distinguishes the rule from a generated
key under DL-018 and DL-149 — needs a real transaction manager and genuine commit and rollback. A
sliced persistence test cannot supply that: it substitutes an in-memory engine for the migrated schema
unless told otherwise, and it wraps every method in a rollback-only transaction, which would defeat
both assertions at once.

**Why the obvious form does not work here.** A test that lets the annotation discover the
application's own configuration component-scans the base package — and on the test class path that
package also holds the nested configuration classes of the other repository tests, several of which
enable JPA repositories in their own right. The scan then registers the same repository twice and the
context fails to refresh with a bean-definition override. This was measured, not predicted: the plain
form fails on `userSecurityRepository`, contributed by two different test classes' nested
configurations. It is the same hazard that obliges the application's own start-up test to install a
type-exclude filter over the test tree.

**Decision.** The test names a nested configuration of its own, which suppresses both the search and
the scan. What that configuration imports is exactly what the assertions need and nothing else: the
real data source pointing at the shared server the container base publishes, the JPA
auto-configuration that validates the shipped mapping against the migrated schema, the transaction
auto-configuration that supplies the real manager and template, and plain template access for
catalogue and plan inspection. It remains a Spring Boot test against real PostgreSQL — no in-memory
engine, no mocked repository, no rollback wrapper — and it declares no container, no container
lifecycle annotation, no data-source property and no context-discarding annotation of its own, all of
which belong to the shared container base.

**A consequence worth stating.** Because the mapping is validated at start-up rather than generated, a
clean refresh is itself an assertion — and the assertion it makes most usefully is about the four
merchant columns, which are unprefixed on this table and prefixed on the byte-identical landing table
under D-39. Regularising either set produces an identifier the migration never declares, and the
context stops instead of quietly creating a second column.

*Cited by:* `repository/TransactionRepositoryIT`, `support/AbstractPostgresIT`.
### DL-203 — The landing table's absent constraints are read from the constraint catalogue, and its absent processing dates are stated as a limitation rather than tested around

**Four decisions about the landing table were reasoned but unwitnessed.** DL-032 records why the
daily-transaction table models no association and DL-033 why it stays a separate entity; D-39 records that
its four merchant columns keep the prefix the posted table's four drop; DL-013 and DL-014 record truncation
and literal operand order; DL-113 records that the fixture cannot exercise a date window. Every one of
those was a decision with a rationale and none of them had a witness at the table itself. A repository
specification for the table now supplies the missing four, and the shape each witness takes was not
obvious.

**Decision one - absence is read from the constraint catalogue, not from the information schema.** The
claim is that this table carries no foreign key, no check constraint, no unique constraint and no secondary
index, and that nothing anywhere references it. Read through `information_schema.table_constraints` that
claim is unassertable: the server projects each not-null column as a check constraint of its own, so the
table reports thirteen checks while declaring none, and a count taken there would be about the wrong thing
entirely - it would pass a table that had acquired a real check and fail one that had not. The assertion
therefore reads `pg_constraint`, joined to the class and namespace catalogues so the table is named by a
bound parameter rather than by a cast, and asserts the whole constraint vocabulary of the table is the
primary key alone. Indexes are read from `pg_indexes` the same way. Both statements are fixed literals with
bound parameters; nothing is assembled.

**Decision two - the proof that the reject codes stay reachable is a successful insert, not an argument.**
DL-032's rationale is that a referential constraint would make the posting program's reject-with-reason-code
paths unreachable. The witness for that is a row naming a card no card master holds, resolved by no
cross-reference, against an account no account master holds - written, flushed, and asserted to have
*landed*. A catalogue read alone would not do: it proves the constraint is absent today, while the insert
proves the consequence the absence exists for. The two together are why a maintainer who "corrects" the
missing constraint fails a test whose name says what it is protecting.

**Decision three - a column assertion is derived from the layout, and the bare column name is never
written.** The two 350-byte layouts differ only in that prefix (D-39), which makes a hand-restated list of
thirteen column names the single likeliest defect in the package: twelve correct entries and one borrowed
from the other table would read as a typing slip and behave as a mapping failure. Each expected column name
is therefore *derived* from the record layout's own field name by one rule applied thirteen times, and the
bare, unprefixed form of each name is additionally asserted absent - produced at run time by removing the
prefix, so that the spelling belonging to the posted table appears nowhere in the landing table's
specification. Field widths are looked up by layout field name for the same reason. The coarsest witness is
the context refresh itself: schema validation is active, so a borrowed column name prevents the context from
starting.

**Decision four - the source column is asserted as the seed stores it, and is never trimmed.** The legacy
field is ten characters space-padded, the enumerated contract carries that padding, and - as of DL-204 - the
seed stores that same padded form, because the column is a coded field drawn from a closed vocabulary rather
than display text. An assertion derived from the layout width is therefore the correct assertion, and the
tempting repair that was *not* taken - trimming inside the assertion - would have hidden any future
divergence permanently and would have contradicted DL-035, which holds for keys and says nothing about this
column. The assertion carries what the seed actually writes, additionally asserts the stored value occupies
the *whole* layout field, and additionally resolves every stored value through the enumeration that models
the column, so the seed and that enumeration cannot drift apart without a test failing.

> **Superseded in part by DL-204.** This paragraph originally recorded the opposite property: that the seed
> stored the right-trimmed eight-character spelling and that the assertion carried a value *shorter* than
> its layout field. That reading treated a coded field as display text; DL-204 states why the padded form is
> the correct one and what the trimmed form actually broke. The rest of DL-203 is unaffected.

**What this is not.** No constraint, index, association, validation annotation or generated identifier is
added to the table or its entity, and no method is added to its repository - the specification exercises the
inherited operations and the one bounded cursor already declared. No processing-date-window test is written
on this table: DL-113's measurement stands, every processing timestamp is blank and every origination stamp
is the same value, so a window here would filter nothing while passing, and date-range behaviour belongs to
the posted table's specification over rows built for it. Nothing asserts a duration, a rate or a volume,
because the performance gate establishes a baseline rather than testing a threshold. The primary account
number keeps the absence of masking recorded as a gap in DL-010: it is compared against a value the fixture
holds and is never placed in a name, a message or a log line.

*Cited by:* `repository/DailyTransactionRepositoryIT`. Witnesses DL-032, DL-033, D-39, DL-013, DL-014 and
DL-113; relies on the truncating scale policy of DL-013 and the verbatim fixed-width comparison of DL-035.

---

### DL-204 — A coded field is seeded at its layout width; only display text is right-trimmed, and `dalytran_source` was on the wrong side of that line

**Context.** `V3__seed_reference_data.sql` carries one rule for character data: *display text is
right-trimmed for relational storage, because the fixed-width writers pad on output, and trailing blanks are
preserved only where they are behaviourally significant.* The rule is right. Its application to
`daily_transaction.dalytran_source` was not. The column was seeded `'POS TERM'` and `'OPERATOR'` — eight
characters — while three other shipped artefacts describe the same field at ten:

| Artefact | What it declares |
| :------- | :--------------- |
| `app/cpy/CVTRA06Y.cpy` → `V1__create_schema.sql` | `DALYTRAN-SOURCE PIC X(10)` → `dalytran_source VARCHAR(10)` |
| `util/DailyTransactionRecordMapper` | reads record bytes `[22:32)` **untrimmed**, at width 10 |
| `domain/enums/TransactionSourceType` | `POS_TERM("POS TERM  ")`, `OPERATOR("OPERATOR  ")`, `SYSTEM("System    ")`, `VALUE_LENGTH = 10`, and `fromValue` is an exact match |

The consequence was measured, not inferred: **`TransactionSourceType.fromValue` resolved 0 of the 300
seeded rows**, and the one column held two different representations depending on which path wrote it —
eight characters from the seed, ten from the record mapper. Nothing failed at the time, because the
enumeration had no production consumer yet and every unit test built its own padded fixture; the seed and the
enumeration were each internally consistent and mutually contradictory.

**Decision — the seed is what changes, and the rule gains the distinction it was missing.** All 300 values
are seeded at the full ten characters. The rule in the V3 header now names the distinction explicitly, so the
next column does not have to be guessed at:

- **display text** — free-form prose written for a human: a name, an address line, a description, a merchant
  name or city. Nothing compares it to a fixed vocabulary, so its padding carries no information and the
  fixed-width writer re-applies it on output. Right-trimmed.
- **coded field** — a value drawn from a closed vocabulary, or one a key or a lookup is built from. Its
  padding is part of the value, because the vocabulary is declared at the legacy field's width and an
  equality test against a trimmed value matches nothing. Preserved, at the exact layout width.

Applied across every coded column the seeds write: `dalytran_source` and `dis_acct_group_id` are ten
characters and are seeded padded; `acct_group_id` is ten blanks, which is itself the value the fixture holds
(anomaly 1); `dalytran_proc_ts` is twenty-six blanks, which is how the record says "not yet processed"
(anomaly 5); `acct_active_status`, `card_active_status`, `pri_card_holder_ind` and V4's `sec_usr_type` are
single characters and so cannot be trimmed at all. `tran_source` is the posted twin of `dalytran_source` and
is equally coded, but no seed writes a `transaction` row — the posting job does, moving the ten characters
across at the same width, which is a same-width COBOL `MOVE` and therefore needs no padding step.

**Why not the other repair.** Trimming the enumeration instead — carrying `"POS TERM"` and comparing on the
trimmed form — was rejected. That enumeration exists to model a *record-image* field, and the record image is
untrimmed at width ten; a trimmed enumeration could not classify a value read from a file without first
undoing the mapper's own faithfulness, it would falsify `VALUE_LENGTH`, and it would push a normalising trim
into every future consumer. Per the tie-break, faithful beats convenient: the artefact that disagrees with
the record layout is the one that moves.

**What keeps it from happening again.** Three assertions, deliberately layered so that each catches what the
others cannot:

1. V3's own verification block counts the two padded literals **without `btrim` on either side**, so a
   re-shortened seed fails the migration rather than the tests.
2. The same block asserts `length(dalytran_source) = 10` on all 300 rows and asserts every row is inside the
   declared vocabulary — the counts alone would still pass if two other ten-character spellings were
   substituted.
3. `repository/DailyTransactionRepositoryIT` resolves every stored value through `TransactionSourceType`
   itself rather than against a constant restated in the test, which is the only assertion that can fail when
   the seed and the enumeration disagree while both remain internally consistent.

**What deliberately did not change.** No column type, no entity, no record mapper, no enumeration constant
and no width. `TransactionPostingService` still copies the source verbatim with no padding, because a
same-width move is what the legacy program performs and adding a pad would mask a width violation instead of
surfacing it. The right-trim treatment of genuine display text — customer and merchant names, address lines,
descriptions, `card_embossed_name`, the two reference-table descriptions — is untouched, and the record-image
round trips that prove it lossless (account 300 B, card 150 B, cross-reference 36 B, daily transaction 350 B,
customer 500 B) are unaffected because the writers pad on output either way.

*Embodied in:* `src/main/resources/db/migration/V3__seed_reference_data.sql`.
*Cited by:* `repository/DailyTransactionRepositoryIT`, `config/SeedMigrationIT`. Amends DL-203 decision four;
consistent with DL-035 and with the truncating-scale policy of DL-013.

---

### DL-205 — Two things a reader of the runtime is entitled to be told rather than surprised by: the pessimistic lock renders as `FOR NO KEY UPDATE`, and the shared developer database is not the seed baseline

Neither of these is a defect and neither changes any code. Both are recorded because each looks like a
defect to someone reading the evidence for the first time, and an unrecorded surprise costs more than
a paragraph.

**One — `@Lock(PESSIMISTIC_WRITE)` renders as PostgreSQL `FOR NO KEY UPDATE`, not `FOR UPDATE`.**
`AccountRepository.findByIdForUpdate` and `UserSecurityRepository.findByIdForUpdate` both declare
`PESSIMISTIC_WRITE`, and the SQL Hibernate emits for it on PostgreSQL ends `for no key update`. A
reader who expected the stronger `for update` will wonder whether the lock is doing anything. It is:
the two modes differ only in their conflict with `FOR KEY SHARE`, which is taken by a foreign-key
reference check and by nothing else in this module. Measured, not argued: two sessions contending for
the same account row serialise, the second blocking until the first commits and then failing with
SQLState `55P03` under a lock timeout. Nothing is changed to force `FOR UPDATE` — that would take a
strictly stronger lock than the operation needs, and the weaker mode is what lets a concurrent
foreign-key check against the same parent proceed instead of queueing behind an unrelated update.

**Two — the long-running Compose database is a developer convenience, not the deterministic seed
baseline.** The Compose stack keeps `postgres` in a named volume so a developer does not re-migrate on
every restart. That is the point of it, and it is also why its *data* drifts as soon as anything is
exercised against it: optimistic-locking versions advance, and every sealed identifier is re-sealed
under a fresh initialisation vector whenever the encryption path runs, so a row-by-row comparison
against a freshly migrated server will differ while remaining entirely correct. Its *schema* does not
drift, and Flyway `validate` against it is meaningful. **Anything asserting seeded content must
migrate a fresh container**, which is exactly what `AbstractPostgresIT` does — one Testcontainers
server per run, migrated to head, never reused, and the integration suite consequently reproduces a
byte-identical seed state on every run. Two corollaries follow and are worth stating plainly: a
developer diagnosing seed behaviour against the shared server is reading the wrong database; and
because the seed migrations are the artefacts that carry reference content, **amending one changes its
checksum, at which point the shared server fails `validate` until its volume is recreated** — that is
the mechanism working, not a fault, and recreating the volume is the correct response rather than
`flyway repair`, which would leave the drifted data behind.

*Embodied in:* `repository/AccountRepository.java`, `repository/UserSecurityRepository.java`,
`docker-compose.yml`, `support/AbstractPostgresIT.java`.

---

### DL-206 — A deliberately scoped test run gets a named profile, because the coverage gate is bound to the phase such a run has to reach

**Context.** The JaCoCo `check` goal binds to `verify`, and `verify` is the phase a Failsafe run must
reach for `failsafe:verify` to assert its results. A deliberately scoped run — `-Dit.test=SomeIT` or
`-Dtest=SomeTest`, which is the first thing anyone does when diagnosing a single failure — therefore
measures the coverage produced by a fraction of the suite against the whole module's floor, and fails.
The failure is real: coverage genuinely is below the floor for that execution. It is also entirely
misattributed, and misattribution during diagnosis is expensive, because the reader has to rule out a
coverage regression before believing the test result in front of them.

**Decision — bundle the two overrides behind a name, and change nothing else.** `pom.xml` gains its
first and only profile, `scoped-tests`, which sets `jacoco.line.coverage.minimum` to zero and
`jacoco.wholly.untested.classes.maximum` to `Integer.MAX_VALUE`. Both are already properties precisely
so that the enforced numbers are visible without reading plugin configuration, so the profile adds no
new mechanism — it names an override that was always available and always required two things to be
remembered at once.

**What it deliberately does not do.** It has no activation block: no default, no property trigger, no
file trigger, no operating-system trigger. It is reachable only by `-Pscoped-tests`, so `mvn verify`,
`mvn install`, the release build and the CI workflow are byte-for-byte unaffected and continue to
enforce 0.80 line coverage and zero wholly untested classes — verified by evaluating both properties
with and without the flag. It relaxes only those two properties: the compiler's `-Werror`, the
supply-chain gate, every test, and `failsafe:verify` itself all still run exactly as they do without
it. And it adds no plugin, no dependency, no goal binding and no source change.

**The corollary is the part that matters, and it is stated at the profile itself.** A green scoped run
is **not** a green build. Coverage is a property of the whole suite and is only ever established by a
full, unscoped `verify`. The profile exists so that a scoped run fails for the reason the reader is
investigating and for no other reason — not so that a subset can be presented as a passing build.

*Embodied in:* `carddemo-java/pom.xml`.

### DL-208 — A condition-code gate bypasses the steps behind it *and* propagates the abend, because a submission's completion code is its highest step code

**Context.** `app/jcl/CREASTMT.JCL` gates three of its four steps with `COND=(0,NE)` — run this step
only if every earlier step returned zero. The migrated job wired each gate as a transition on the
framework's `FAILED` exit status routed to a plain end of flow. That reproduced half of the construct
and inverted the other half. The bypass was right: no step behind a failed one ran. The reporting was
wrong: a flow that *ends* completes, so a run whose very first step abended was recorded as
`COMPLETED` with `ExitStatus` `COMPLETED`.

`COND=` does not suppress a return code. It suppresses the *execution of a step*. The job's own
completion code is the highest code any step that did run returned, so a stream whose first step
abended with a non-zero code is an abended stream — visibly, in the job log, in the completion
notification, and to anything that reads the code and decides what to do next. A translation that
reports zero for that run does not merely lose a diagnostic; it tells a downstream consumer to
proceed.

**Decision.** Each of the three gates is a failure-*propagating* transition rather than a
failure-ending one: `.on(GATE_FAILURE_OUTCOME).fail()`. The bypass behaviour is unchanged and is
still asserted in both directions — a gate still admits its guarded step after a clean predecessor and
still refuses it after a failed one — and the job now ends `FAILED` with exit code `FAILED` whenever
any step it ran abended. Nothing else moved: no step gained a retry, no gate gained tolerance, and the
strict form of the rule — bypass on anything other than zero — is untouched. That strict form is the
delivered behaviour of **all four** gates in the estate, the sibling backup job's single gate included:
its source literal would have tolerated a code up to four, and DL-145 records that literal rather than
implementing it, so no gate anywhere in the module admits a non-zero code.

**Why not a listener that reports separately.** Deriving a job-level verdict outside the flow would
put two authorities on the same fact and let them disagree; the flow is where the gate lives, so the
flow is where the verdict belongs.

*Embodied in:* `batch/CreateStatementJobConfig.java` (`GATE_FAILURE_OUTCOME`,
`CONDITION_CODE_GATE_COUNT`, the three transitions in `createStatementJob`).
*Asserted by:* `batch/CreateStatementJobConfigTest`
(`exactlyThreeFailurePropagatingTransitionsAreDeclared`, which also fails if a failure-ending
transition reappears), `batch/CreateStatementJobConfigIT`
(`aFailedOrderingStepIsRefusedByEveryGate` and `aFailedScratchStepIsRefusedByTheThirdGate`, each of
which now asserts the bypass *and* the propagated `FAILED` status and exit code).

### DL-209 — Statement HTML encodes nothing: every value is emitted byte for byte, and hardening is refusal rather than rewriting — REVERSES DL-037, DL-039 and D-49

**Context.** `app/cbl/CBSTM03A.CBL` composes each hundred-byte HTML record by wrapping paragraph
literals around display fields with a `STRING` statement and writing the result. There is no encoding
step anywhere in the estate. An earlier delivery added one: the five markup-significant characters
were replaced by character references in every substituted value, recorded as a narrow parity
exception on the reasoning that escaping is the identity function over the legitimate domain of these
fields and diverges only for input that would inject markup.

**Why that reasoning does not hold here.** It is empirically false for this estate. The seeded data
carries twelve apostrophes — one customer surname and eleven transaction descriptions — so escaping
changed emitted bytes for records the pipeline actually produces. Worse, because a reference is longer
than the character it replaces and the record width is fixed, every byte after a substitution was
displaced and the record tail was pushed off the end. The module's own expected-output oracle records
the correct bytes: `src/test/resources/fixtures/expected/statement-html.txt` carries
`Zulauf-O'Keefe` raw and no character reference anywhere, and the 80-byte oracle agrees character for
character. Escaping therefore made production disagree with its own golden file on precisely the
records that make byte parity observable — which is a Gate 1 defect, not a hardening.

**Decision — remove the encoding, keep and widen the refusal.** No published path escapes, encodes,
masks or repairs a byte. The five character-reference constants and the escape routine are gone. The
width fit is a plain cut at the record boundary, and the refinement that blanked a trailing partial
reference is gone with the references it existed to repair — the legacy `MOVE` cuts at the boundary
and blanks nothing.

What replaces escaping is a control that cannot alter a byte: every path validates its input and
**refuses** what it cannot carry. Anything outside printable US-ASCII is rejected, which closes the
hazards that actually threaten a fixed-width data artefact — an embedded line feed or carriage return
that would reframe records, an escape byte that a terminal would obey, a tab, a NUL, and any byte
above 0x7E that would have become a question mark under encoding. The account-number slot is narrower
still: digits and the pad space only, because the legacy moves a numeric display item there. A refusal
is not a rewrite, so parity is unaffected by it.

**The boundary this leaves open, stated rather than glossed.** A legitimately-stored angle bracket in a
name or description now reaches the file, exactly as it reached the mainframe file. The statement
artefact is fixed-width data; a consumer that renders it as a live document is responsible for
encoding at its own serving boundary, which is where a rendering concern belongs and where the
context needed to escape correctly actually exists. This is the same division the estate's other
output artefacts already rely on.

**Where the control-byte refusal moved to, and what stayed here.** This entry made the composers the
*only* thing refusing a control byte, which meant a value accepted at the request boundary was met for
the first time during a batch run — see DL-267, which states the same refusal at the boundary where the
value arrives and explains why it is placed on the mapper's text reader rather than on a field. The
composers' own refusals below are kept rather than withdrawn, because a stored value predating that
rule, a fixture-placed value, and a value arriving by any other route are all still possible. Nothing
about the rendering half of the boundary above changes: DL-267 closes the control-byte half only.

**Precedence, since this reverses a recorded decision.** The AAP's preservation boundary (§0.8.1)
requires zero behavioural regressions in external interface contracts, and the statement file is one
of the estate's three; its tie-break (§0.10.5) resolves faithful-versus-idiomatic in favour of
faithful and requires the divergence to be recorded here rather than settled by taste; and Gate 1
requires byte equivalence against the documented baseline, which the golden files are. All three point
the same way.

*Embodied in:* `util/StatementHtmlTemplates.java` (the escape routine and its five constants removed,
the five composers substituting values verbatim, `fitToWidth` a plain boundary cut, the
printable-US-ASCII and digits-or-spaces refusals retained).
*Asserted by:* `util/StatementHtmlTemplatesTest`
(`everyPublishedPathMovesMarkupSignificantBytesThroughUntouched`,
`noValueIsWidenedSoTheCutIsOnlyEverAtTheRecordBoundary`), `util/StatementHtmlTemplatesSecurityTest`
(the verbatim-transfer suite plus the refusal suite), `util/StatementHtmlWorkLineExposureTest` (the
production census that keeps the bare fitter out of the composing path),
`support/ExpectedHtmlStatementFixtureContractTest` (every composed record of the committed oracle
re-emitted and compared byte for byte, with no divergence permitted, and no character reference
anywhere in the oracle).
*Reverses:* DL-037, DL-039 and D-49. *Partly supersedes:* DL-038, whose removal of the anything-goes
sink still stands.

### DL-210 — The durable generation number is allocated against the object store, not taken from the framework's execution identifier

**Context.** Every published object was keyed `<base>/G<execution-id>V00`. The execution identifier is
the batch metadata store's, and the metadata store is not the durable store. Re-create the metadata
store — a fresh database, a wiped schema, a new environment pointed at the same bucket — and execution
identifiers restart at one while the bucket keeps every object it ever received. The next run therefore
PUTs onto a key an earlier run already published. This was observed rather than reasoned about:
`AWS.M2.CARDDEMO.TRANSACT.BKUP/G0000000005V00` went from 109,512 bytes to a zero-byte latest version
across two fresh-database runs. Bucket versioning preserved the history, so nothing was destroyed, but
`IsLatest` silently changed, and every consumer reads the latest.

**Decision.** The generation number is allocated by the store, at publication time, as one more than the
highest generation the base already holds — which is what a relative `(+1)` allocation against a
catalogued generation group does, and where the legacy system got its generation numbers from too. The
allocation runs inside the per-base publication lock, which is already held across upload and retention,
so two concurrent publications to one base cannot choose the same number. A base holding nothing starts
at generation one.

**What deliberately did not change.** The framework's execution identifier still names the *local*
per-execution file. Two concurrent executions must not compose over each other, and the identifier is
exactly the right thing for that: it is unique within the metadata store that hands it out, which is the
scope the local file lives in. The two namings are now independent, which is the point — a local file's
name is a concurrency concern and a durable key is a catalogue concern.

**Consequences a reader should expect.** A key can no longer be predicted before publication, so the
transaction-backup job reads back the key its publication returns rather than composing one when it
opens its output; the reported key is now the key that was written. A publication that cannot list its
base fails and uploads nothing, because a generation cannot be named without risking an overwrite — and
that is a pre-commit failure, fully covered by the existing compensation. Retention is unaffected: it
already sorted by the numeric token, and the token remains numeric and monotonic per base.

*Embodied in:* `batch/step/StagedGenerationStore.java` (`allocateGeneration`,
`highestDurableGeneration`, `upload`, `publishFile`; the public `objectKey(String, long)` is gone
because no caller can predict a key any more), `batch/BackupTransactionJobConfig.java` (the key is read
from the publication).
*Asserted by:* `batch/step/StagedGenerationStoreTest` (nest `the durable generation number is the
store's own`, including an execution identifier of one against a base already holding three
generations), `batch/BackupTransactionJobConfigTest`
(`advancesTheGenerationForEveryPublication`, whose third run carries an identifier lower than both
predecessors — the re-created-metadata-store case — over a listing double that reflects what it has
accepted).

### DL-211 — A submission that does not complete discards the local artefacts it allocated

**Context.** Publication is skipped for a job that did not complete, which is correct and is what keeps a
failed submission's output out of the bucket. What was missing is the other half. The local files such a
job created were left where they were: sealed generations that nothing would ever publish, read or
prune, and working `.part` files from steps that failed mid-composition. Observed after a gate-failed
statement run as `AWS.M2.CARDDEMO.STATEMNT.PS.G0000000005V00.part` and its markup sibling, and after a
sequence of failed runs as one zero-byte local generation per failed execution.

**Decision.** The job-boundary listener sweeps the staging root when a job ends without completing,
removing the regular files whose names carry *that execution's* generation token — the sealed
generations and the working files alike. This is the abnormal disposition of the legacy allocation:
`DISP=(NEW,CATLG,DELETE)` catalogues a newly allocated dataset when the step ends normally and
**deletes** it when it does not, and a submission that did not complete is exactly that case.

**Why selection is by token and not by suffix or by age.** Every local file the store hands out carries
the generation token, and the token is unique to one execution, so the sweep cannot reach a file a
concurrently running job is still composing. A suffix rule would have caught another execution's working
file; an age rule would have depended on wall-clock timing. Directories are skipped, because the store
hands out no directory. The sweep never raises: the job has already failed for its own reason, and that
reason is the one an operator must read, so a failure to remove a file is reported and nothing more.

> **Superseded in part by DL-269.** The disposition this entry records still stands: a job that does not
> complete discards what it allocated. The *selection rule* described in the paragraph immediately above
> does not. Matching a filename that merely **contains** the execution token made the deletion aimable —
> the token is derived from a monotonically increasing framework identifier, so its value is guessable,
> and a file named to contain it was removed on the next failed run whether or not this store had ever
> allocated it. Selection is now from the execution's own registry of exact absolute paths. Read the
> paragraph above as the reasoning that ruled out suffix and age rules, which still holds, and DL-269 for
> the rule that replaced the token match.

*Embodied in:* `batch/step/StagedGenerationStore.java` (`discardLocalArtifactsOf`),
`config/BatchConfig.java` (the boundary listener sweeps when the status is not `COMPLETED`, and takes
the shared staging root for that purpose).
*Asserted by:* `batch/step/StagedGenerationStoreTest` (nest `an execution that did not complete discards
its own local artifacts`, including that another execution's generation and an unrelated staged input
both survive), `batch/CombineTransactionsJobConfigIT`
(`equalIdentifiersRetainConcatenationOrder`, where the load step fails and the combined generation is
gone afterwards).

### DL-212 — One publisher, one canonical key: the in-step uploads are removed and the staging area no longer publishes at all

**Context.** Two publication paths existed at once. `StagedGenerationStore` published every *registered*
artefact after its job completed, under `<base>/G…V00`. Separately, four job steps — the interest accrual
step, both category-balance report steps and the transaction-report emit step — also called
`BatchStagingArea.publish(Path)` on the very same sealed file, and that form used the *local file name*
as the object key, which is the dot form `AWS.M2.CARDDEMO.SYSTRAN.G0000000002V00`. Every generation was
therefore in the bucket twice, at the same size, both marked latest, under two different key shapes. The
combined-transactions generation added a third shape of its own: `combineTransactionsJob/combined/<id>`,
which is not a generation key at all.

Two consequences, both observed. **Retention could only prune one family**, because the retention scan
lists with the prefix `<base>/` and the dot form does not match it: seven interest runs against a depth
of five left seven dot-form objects and five slash-form ones. And **a failed job left output behind**,
because an in-step upload has already happened by the time a later step fails — including a zero-byte
`combineTransactionsJob/combined/4` from a submission that failed before composing anything, which is
not even faithful to the legacy disposition it was standing in for.

**Decision.** Every step registers and none publishes. The four in-step calls are gone; the combined
generation is registered under the legacy generation-group base
`AWS.M2.CARDDEMO.TRANSACT.COMBINED`, which the measured job stream names in its own data definitions,
so it joins the one key shape and the one retention pass with everything else. And the publication forms
are **removed from `BatchStagingArea`** rather than documented as discouraged: that component is now a
read boundary with no upload method on it at all, so the single-publisher invariant is structural and a
future caller cannot reintroduce a second key shape by reaching for the convenient method.

**The divergence this accepts, stated rather than glossed.** `DISP=(NEW,CATLG,DELETE)` catalogues a
generation at the end of the step that wrote it, so under the legacy stream a generation written by an
early step survived a later step's abend and could be rerun from. Post-job publication does not: a
submission that fails anywhere publishes nothing. That is deliberate. In an object store the key *is*
the contract, and a catalogued intermediate from a failed submission is indistinguishable from a good
one to every consumer that reads the base; the invariant that a failed job leaves nothing externally
visible is worth more than the rerun convenience, and it is the invariant the post-job publisher was
built to uphold. The local sealed generation is still there for a rerun until the abnormal-disposition
sweep of DL-211 removes it. The transaction-backup job's archive step keeps its immediate publication,
because its generation is its whole output rather than an intermediate, and it already published under
the canonical key.

**A consequence in the combined generation's lifecycle.** Its load-side close used to delete the local
file once every record had been served. It no longer does: the registration names that file and the
publication has not run yet, so deleting it left the publication with nothing to upload. Accumulation is
handled where it belongs — a completed submission's generation is a generation like any other, and a
failed one is swept by DL-211.

*Embodied in:* `batch/BatchStagingArea.java` (three publication forms and the publishing-writer
decorator removed), `batch/InterestCalculationJobConfig.java`,
`batch/CategoryBalanceReportJobConfig.java`, `batch/TransactionReportJobConfig.java` (the in-step
uploads removed and the staging-area parameter with them),
`batch/CombineTransactionsJobConfig.java` (`COMBINED_DATASET_BASE`, registration on close, the local
generation named in the legacy generation form and no longer discarded by the reader).
*Asserted by:* `batch/BatchStagingAreaTest` (`nothingOnThisBoundaryCanUpload`, a census over the
declared methods rather than a list of spellings), `batch/InterestCalculationJobConfigTest`
(the step registers exactly one artefact), `batch/CombineTransactionsJobConfigTest`
(`closingRegistersTheExecutionGenerationOnce`, `theSealedGenerationSurvivesBeingServed`),
`batch/CombineTransactionsJobConfigIT` and `batch/CombineTransactionsJobIT` (the sealed generation is
read from the legacy base rather than from an intercepted publication).

### DL-213 — One framing rule per logical dataset, taken from the DD: fixed-length means no separator

**Context.** Nine of the module's own artefacts were written with a trailing line feed after every
fixed-length record while two were written without one, and the inconsistency was not cosmetic. The
transaction-backup dataset has two producers — the archive step of the backup job and the unload step of
the report job — and they disagreed: 262 records came out as 91,700 bytes from one and 91,962 bytes from
the other. The consolidation job frames that dataset by width, so it read the first record of the
line-fed generation correctly and every later one shifted by its ordinal, failing at record 2 with
`zoned decimal field 'TRAN-AMT': expected an ASCII digit ... but found 0x20`. Both producers are governed
by the *same* data-definition statement, `DCB=(LRECL=350,RECFM=FB,BLKSIZE=0)`, declared at
`app/jcl/TRANBKP.jcl:31` and again at `app/proc/TRANREPT.prc:29`.

**Decision.** The record format on the DD decides the framing, and every DD in the estate declares
`RECFM=F` or `RECFM=FB`. Neither carries a record separator: on the platform being migrated from, the
record boundary is the access method's, established by the declared length, and no byte is written
between two records. Every producer in this module therefore writes a fixed-length dataset with
**nothing between two records**, and every consumer frames such a dataset **by width and never by line**.
The rule is applied to every logical dataset the module produces rather than to the three the earlier
entries happened to name:

| Logical dataset | Record | Producer |
| :-------------- | :----- | :------- |
| `AWS.M2.CARDDEMO.TRANSACT.BKUP` | 350 | archive step (already separator-free) **and** report unload step |
| `AWS.M2.CARDDEMO.TRANSACT.DALY` | 350 | report filter-and-order step |
| `AWS.M2.CARDDEMO.TRANREPT` | 133 | report emit step |
| `AWS.M2.CARDDEMO.TCATBALF.BKUP` | 50 | category-balance unload step |
| `AWS.M2.CARDDEMO.TCATBALF.REPT` | 40 | category-balance report step |
| `AWS.M2.CARDDEMO.TRXFL.SEQ` | 350 | statement order-and-reproject step |
| `AWS.M2.CARDDEMO.STATEMNT.PS` | 80 | statement emit step |
| `AWS.M2.CARDDEMO.STATEMNT.HTML` | 100 | statement emit step |
| `AWS.M2.CARDDEMO.TRANSACT.COMBINED` | 350 | consolidation load step |
| `AWS.M2.CARDDEMO.DALYREJS` | 430 | reject writer (already separator-free) |
| `AWS.M2.CARDDEMO.SYSTRAN` | 350 | interest accrual step (already separator-free) |

**Consumers moved with their producers, in the same change.** A framing change that moved only the
writers would have produced exactly the defect it set out to remove, so each reader of a changed dataset
was re-pointed in the same edit: the report job's two transaction readers and its filtered-generation
walk, the category-balance report's unload reader, all three of the statement job's reads, and the
consolidation job's served read of its own combined generation. `FixedWidthFlatFileReaderFactory` gained
`fixedWidthReader(Path,int)` — the primitive its own fixed-stride item reader already used, published so
that a step reading through a plain `BufferedReader` frames identically — and
`fixedTransactionCategoryBalanceReader`, the fixed-unblocked sibling of the line-oriented reader.

**What deliberately did not change.** The nine sample data files under `app/data/ASCII` are
newline-delimited, and they stay that way: they are the repository's readable rendering of the EBCDIC
sequential datasets, not datasets this module produces. Their readers keep the line-oriented flavour, and
the two flavours are named separately in the factory so that restart metadata can never cross between
them. The external sorter's spill format is length-prefixed and private to the sorter; it is not a
dataset and is untouched.

*Embodied in:* `batch/step/FixedWidthFlatFileReaderFactory.java` (`fixedWidthReader`,
`fixedTransactionCategoryBalanceReader`), `batch/TransactionReportJobConfig.java`,
`batch/CategoryBalanceReportJobConfig.java`, `batch/CreateStatementJobConfig.java`,
`batch/CombineTransactionsJobConfig.java` (each producer's separator write removed, its record-separator
constant deleted, and every reader of a changed dataset re-pointed).
*Asserted by:* the job integration tests measure each artefact's length as an exact multiple of its
record width and assert it carries no line feed; `e2e/BatchPipelineE2ETest` compares the four terminal
artefacts byte for byte against their golden fixtures.

> **Supersession note.** This entry generalises the three-dataset statement of DL-226 - recorded under
> `DL-145` until that identifier was de-duplicated, see entry DL-245 - to every logical
> dataset the module produces. Where an earlier entry describes a staged dataset as line-oriented, or
> justifies a literal `"\n"` as "matching the estate's own sample data", this entry governs: the sample
> data is an input rendering and never the authority for an output format.

---

### DL-214 — A generation base is resolved to its current generation, reproducing the legacy relative generation (0)

**Context.** `app/jcl/COMBTRAN.jcl` names its two inputs as `AWS.M2.CARDDEMO.TRANSACT.BKUP(0)` and
`AWS.M2.CARDDEMO.SYSTRAN(0)`: two dataset names the member declares itself, each at relative generation
zero, resolved by the catalog at submission. The migrated job instead required a configured location and
refused to substitute any default — which is right about defaults in general and wrong here in
particular, because it turned "the DSN the member declares" into knowledge the *caller* had to supply,
and the only spelling it accepted was one absolute generation. Since a generation name embedded the
producing job's execution identifier, running the fourth link of the chain meant predicting the third
link's execution id before the consuming context started. The chain could not be run without that
out-of-band knowledge.

**Decision.** The two properties default to the two dataset bases the legacy member declares, and a
configured base is resolved to its current generation. The durable store is the catalog: the highest
generation number beneath the base is what `(0)` named. The resolution order is
exact durable key → current durable generation of the base → exact local staged name → current *local*
generation of the base → application resource loader. The local rungs matter because that is precisely
the state the five-link chain is in while it runs: each link has sealed its generation locally and the
terminal publication for the link now being consumed may not have run. The suffix `(0)` is accepted
verbatim so that an operator may write what the job stream writes; no other relative generation is
accepted, because the member reads `(0)` on both inputs and writes `(+1)` on its output.

**Why this does not weaken the refusal it replaces.** Three properties of the original refusal are
preserved exactly. No caller-supplied path is honoured — resolution runs from a whitelisted logical base
or an already-validated simple name, never from a path composed here. An explicitly blanked property is
still a hard refusal, reported as a deployment error before any step runs. And a base that holds no
generation still fails rather than reading one input instead of two, because a consolidation that read
half its input would produce a perfectly well-formed result missing half its records — the outcome that
must never be allowed to resemble success. What changed is only the *identity* of the default: not a
conventional path invented by this module, but the dataset name the legacy member itself declares.

*Embodied in:* `batch/step/StagedGenerationStore.java` (`currentGenerationKey`,
`currentLocalGeneration`), `batch/CombineTransactionsJobConfig.java` (`DEFAULT_BACKUP_DATASET_BASE`,
`DEFAULT_SYNTHESIZED_DATASET_BASE`, `CURRENT_GENERATION_SUFFIX`, the resolution cascade in
`resolveInput` and `stripCurrentGeneration`), `batch/InterestCalculationJobConfig.java`
(`DEFAULT_TRANSACT_DATASET_BASE` published so one constant names one dataset).
*Asserted by:* `batch/step/StagedGenerationStoreTest` (the current generation of an empty base, of a
base holding several, and the exclusion of working files and foreign keys),
`batch/CombineTransactionsJobConfigTest` (the suffix is stripped, another suffix is refused),
`batch/CombineTransactionsJobConfigIT` (a run with neither property configured resolves both inputs),
and `e2e/BatchPipelineE2ETest` (the five-link chain runs with no per-execution resource property).

---

### DL-215 — An input the framework cannot open still reports the program's own open-failure arm

**Context.** Paragraph `0000-DALYTRAN-OPEN` of `app/cbl/CBTRN02C.cbl`, declared at line 236 and performed
from line 195, mandates three things in order when the sequential input cannot be opened: a console
diagnostic naming the failed input, at line 247; then `9910-DISPLAY-IO-STATUS`, which renders the raw
two-byte file status as its four-character `NNNN` image; then `9999-ABEND-PROGRAM` with abend code 999. The
module implements that sequence everywhere the acquisition is its own — the orphan extract job, the
file-probe job, and this same program's own write path all emit it. It did not implement it for the posting
job's *input*, because that acquisition is the framework's: the reader is opened around the step, so a
missing landing file produced `ItemStreamException: Failed to initialize the reader` and `Input resource
must exist (reader is in 'strict' mode)`. Searching the run for the program's own open-failure diagnostic
returned nothing. The job did end FAILED, so nothing was at risk except the diagnostic — and the diagnostic
is a mapped paragraph.

**Decision.** The acquisition stays delegated and the diagnostic returns to the program.
`TransactionPostingService` publishes `dailyTransactionOpenFailure(Throwable)`, which is the existing
private arm made reachable: it emits the literal, the `NNNN` status image and the abend, and *returns*
the `AbendException` so a caller writes `throw service.dailyTransactionOpenFailure(failure)` and keeps
its own control flow definite. The job configuration wraps the factory-built reader in
`DiagnosingDailyTransactionReader`, whose `open` translates any runtime failure into that arm.

**Only the open is decorated, and that is deliberate.** Read, update and close pass straight through.
The driving loop's own read-failure arm already belongs to the translated program and is raised from
there, so decorating the read as well would emit one diagnostic twice. An `AbendException` the delegate
itself raises is re-thrown unchanged rather than re-wrapped, so a failure that already carries the
program's verdict never acquires a second one. The decorator is declared as the stream-reader interface,
which keeps the step-scoped proxy interface-based and the module's reflection budget intact.

*Embodied in:* `service/TransactionPostingService.java` (`dailyTransactionOpenFailure`),
`batch/PostTransactionJobConfig.java` (`DiagnosingDailyTransactionReader`).
*Asserted by:* the posting job's integration test deletes the landing file and asserts all three legacy
lines, and asserts the job still ends FAILED.

---

### DL-216 — A logical dataset name resolves against the staging root before the resource loader, and a run that reads nothing says so

**Context.** Two defects in the orphan extract job, sharing one cause: its resolution cascade had only two
rungs where every other staged-dataset job of the package has three. A location that names one logical
dataset — `AWS.M2.CARDDEMO.DALYTRAN.PS`, which is a dataset name and not a URI — went straight to the
application resource loader, which resolves a scheme-less name as a *class-path* resource. The result was
`Input resource must exist (reader is in 'strict' mode): class path resource
[AWS.M2.CARDDEMO.DALYTRAN.PS]` for a name that resolves correctly for every sibling job. Separately, when
nothing was staged at all the run resolved its input from the relational source — which is the deliberate
design — but said so only at `DEBUG`, so at the default level an unconfigured run that read nothing was
indistinguishable from a successful one with no work to do.

**Decision.** Three rungs, matching the sibling jobs: an exact durable object, then the local staging root
for an already-validated simple name, then the resource loader for an explicit `file:` or `classpath:`
location. A new `staging-directory` property names the root, defaulting to the shared staging property and
then to the platform temporary directory, exactly as the consolidation job's does. No path is composed
from a caller's value; the local rung accepts only a simple name and resolves it beneath the one
configured root.

**And the vacuous run is made audible rather than refused.** The unstaged case is promoted to `INFO` and
names the property that would stage a dataset, and a run that read *zero* records with nothing staged adds
a `WARN` saying so in as many words. Refusing to start would have been the other half of the finding's
suggested fix, and it is deliberately not taken: reading the ordered input from the relational source when
no dataset is staged is this job's documented design, and refusing it would remove a working path to fix a
reporting problem. Zero records is not an error either — the legacy member reaches end of file immediately
on an empty input and stops cleanly — it simply must not be silent.

*Embodied in:* `batch/DailyTransactionReadJobConfig.java` (`STAGING_DIRECTORY_PROPERTY`,
`stagedResource`, `isSimpleLocation`, the two diagnostics in `readPass`).
*Asserted by:* the job's integration test resolves a simple dataset name from the staging root, and
asserts the two diagnostics on an unconfigured run over an empty relational source.

---

### DL-217 — The observation-derived active-job meter is renamed so that it stops being dropped

**Context.** On *every* job launch the Prometheus registry logged
`The meter (MeterId{name='spring.batch.job.active', tags=[application, spring.batch.job.name,
spring.batch.job.status]}) registration has failed: Prometheus requires that all meters with the same name
have the same set of tag keys. There is already an existing meter named 'spring_batch_job_active_seconds'
containing tag keys [application, spring_batch_job_active_name].` Two independent mechanisms publish under
one name: the framework's own long-task timer for a running job, tagged `spring.batch.job.active.name`,
and the observation registry's automatic active-observation meter for the long-running
`spring.batch.job` observation, carrying that observation's `spring.batch.job.name` and
`spring.batch.job.status`. The second registration was refused every time, so the job-name-and-status
dimension of the active-job measurement never existed.

**Decision.** One `MeterFilter` maps the observation-derived identifier to
`carddemo.batch.job.observed.active`, keyed on the presence of the observation's own tag so the
framework's meter is never touched. Both then register and both are exported.

**Why the newcomer is the one renamed, and why a filter is acceptable here at all.** The dashboard
provisioned with this module reads `spring_batch_job_active_seconds` and `spring_batch_job_active_name`,
which belong to the framework's meter; renaming that would empty a panel. This module's observability
configuration otherwise contributes no filter on the stated ground that dropping or re-bucketing a series
would starve the very baseline it exists to establish. This filter is the opposite of that: without it a
series is dropped, and the filter is what recovers it. Only the identifier is mapped — no meter is denied,
no distribution statistic is configured, and no value is transformed — so nothing here can alter a
measurement. The chosen name is a deliberate sibling rather than a variation, so an alert or a panel
cannot match it by accident when it meant the framework's series.

**What this supersedes.** The collision itself was already recorded, beside the dashboard panel that
survived it, as two framework instrumentation paths colliding rather than a defect to fix - and the reason
given for leaving it was that the only suppression then in view discriminated on *tag keys*, one character
away from deleting the exported series. That reason is why this filter discriminates on something else
entirely. See DL-242, the active-job panel entry - recorded under `DL-152` until that identifier was
de-duplicated, see entry DL-245 - now annotated as partly superseded: its panel
reasoning stands, its resignation does not.

*Embodied in:* `config/ObservabilityConfig.java` (`batchActiveJobMeterNameFilter` and the four names it
is stated with).
*Asserted by:* `config/ObservabilityConfigTest` maps both identifier shapes through the filter, and the
batch integration tests assert no meter-registration warning is logged across a launch.

---

### DL-218 — A repeated transient store conflict on a launch reservation is a refusal, not an internal error

**Context.** Two identical launches arriving together produced one completed execution and, for the other
caller, `CannotAcquireLockException PreparedStatementCallback; SQL [INSERT INTO BATCH_JOB_INSTANCE…];
ERROR: could not serialize access due to read/write dependencies among transactions … Hint: The
transaction might succeed if retried.` The metadata was intact — exactly two executions, no duplicate or
partial instance — so the outcome was correct and only its reporting was wrong: a driver-level message
reached the caller as though the service had malfunctioned. The framework creates its instance and
execution rows in its own transaction at serializable isolation, so two launches that arrive together can
be cancelled as a serialization pivot even though neither did anything wrong.

**Decision.** The reservation is attempted twice. A `TransientDataAccessException` — the precise family
whose contract is that retrying may succeed, and the one PostgreSQL's own hint describes — is logged and
retried once; the advisory lock is released by the rollback before the retry begins, and a reservation
this short means the competing one has finished. A second transient conflict becomes
`LaunchRejectedException(ACTIVE_EXECUTION)`, which is the same answer the caller already receives when the
advisory lock is busy or the framework reports an execution already running: "not now", expressed in the
closed reason code the HTTP boundary already knows how to translate. A failure that is *not* transient is
still an internal error and is still reported as `IllegalStateException`, because retrying would not help
and pretending it was a refusal would hide it.

*Embodied in:* `batch/BatchLaunchCoordinator.java` (`RESERVATION_ATTEMPTS`, the retry loop in
`reserveWithDatabaseLock`).
*Asserted by:* `batch/BatchLaunchCoordinatorTest` drives a transient failure on the first attempt and
asserts the second succeeds, and a repeated transient failure and asserts the closed refusal reason.

---

### DL-219 — A golden output file is a dataset image and carries no separator; the one delimited fixture is an input rendering, and the asymmetry is deliberate

**Context.** The four committed goldens were framed with one line feed per record — 38 x 431, 519 x 134,
1262 x 81 and 6632 x 101 — while the writers that produce those datasets emit no separator at all, because
every member that allocates them declares a fixed length: the reject dataset at `RECFM=F,LRECL=430`, the
report at `LRECL=133,RECFM=FB`, the statement at `LRECL=80`, the HTML statement at 100. A whole-file
comparison against a separator-framed oracle is therefore impossible in principle: the production side
cannot produce the extra byte, and the assertion would fail for a reason that has nothing to do with the
records. Nothing compared them, so the mismatch survived.

**Decision.** The goldens are de-framed to their exact legacy widths — 16,340, 69,027, 100,960 and 663,200
bytes — and the framing absence is asserted twice for each: once as an exact multiple of the record width,
and once as the total absence of a line feed or a carriage return anywhere in the file.

**Why de-framed rather than regenerated.** The de-framing removes one byte per record and touches no
record content, so the goldens remain what they were: an oracle authored independently of the code. That
independence is not decorative — it is the reason three separate production defects were caught. The
committed statement oracle already carried the *unflushed* closing balance, so the accrual defect showed up
as the oracle disagreeing with production rather than as production agreeing with itself; the committed HTML
oracle already carried `Zulauf-O'Keefe` unescaped, so the escaping defect showed up the same way. Had the
goldens been regenerated from a run, both defects would have been baked into the expectation and both
suites would have gone green on wrong bytes. Regeneration is the one thing that must not be done to a
golden file.

**The one exception, and why it is not an inconsistency.** `fixtures/input/dailytran.txt` keeps its 300
line feeds and must. It is not a dataset image; it is the estate's own newline-delimited ASCII *rendering*
of the sequential input, and it is staged as a landing file that the reader frames by width while ignoring
the terminator. Because one helper had been serving both kinds, the de-framing broke a test that walked the
input with the goldens' stride — which is the useful failure: the two framings now have two helpers,
`records` for a dataset image and `delimitedTextRecords` for a rendering, so a golden can no longer regain
a separator without an assertion noticing.

*Embodied in:* `src/test/resources/fixtures/expected/daily-reject.txt`, `transaction-report.txt`,
`statement.txt`, `statement-html.txt`; the empty and unreferenced `transaction-archive.txt` is deleted
rather than left to invite a vacuous assertion, and only the encoded `transaction-archive.b64` the archive
suite actually reads remains.
*Asserted by:* `support/ExpectedOutputFixtureContractTest` and
`support/ExpectedHtmlStatementFixtureContractTest` (framing, both directions),
`e2e/BatchPipelineE2ETest` (whole-file byte equality against a real run), and
`e2e/GateVerificationTest` (the four names, widths and counts as a named-artefact inventory).
*Generalises:* DL-213, which removed the separator from the produced side.

---

### DL-220 — The three gate classes assert what nothing else asserts, and deliberately restate nothing

**Context.** The plan names three end-to-end classes and none existed. The consequence was specific rather
than general: the module's four golden files had two consumers, and both re-emitted one record at a time
through the very formatter that owns that record's layout. A formatter proved against its own output proves
that it is self-consistent. It cannot see a wrong value arriving from a service, a value transformed on its
way into a cell, or a framing the dataset does not allow — which is why a fully green suite reported nothing
while all three were shipped.

**Decision.** Three classes are added, and each is scoped by what it is the *only* possible holder of.

`e2e/BatchPipelineE2ETest` holds Gate 1. It stages the committed sample input, launches all six jobs of the
plan's pipeline against a real PostgreSQL server and a real object store, and compares each of the four
produced artefacts to its golden as a byte array. Nothing is mocked; the graph is assembled explicitly,
which narrows the *bean set* and substitutes no boundary.

`e2e/OnlineTransactionE2ETest` holds the one Gate 5 contract that had no end-to-end assertion: the sign-on
program's operator-visible texts and its two routing outcomes, over a real HTTP boundary on a real port,
through the shipped filter chain, against the ten identities the seed migration applied to a real server.

`e2e/GateVerificationTest` holds the gate-level inventory: the named artefacts at their measured sizes, the
goldens at their legacy widths, the applied credential seed's ten identities across two roles with no
credential in clear, the three shipped lookup resources at 490 = 410 + 80, 56 and 240 read out of the
resource files rather than out of the service that also loads them, the unsafe-code audit counts over the
production tree, and the build settings Gates 2, 7 and 8 rest on.

**Why each class states what it does *not* assert.** Two assertions of one fact drift apart and the weaker
one wins, so each class names the narrower suite that owns each neighbouring fact instead of restating it:
the input fixtures' digests and layouts belong to `support/FixtureContractTest`; the record-level
reproduction belongs to the two fixture-contract suites; the lookup sets' semantics belong to
`service/ValidationLookupServiceTest`; and Gate 5's batch-trigger contract — seventeen eighty-byte cards,
four substituted date slots, the transmitted end-of-stream sentinel, the single message group, the per-card
deduplication identity — is already drained out of a real first-in-first-out queue by
`service/JobSubmissionServiceIT` and is not touched here.

**One expectation was wrong and the source settled it.** The sign-on prompts were first written as "Please
enter your User ID ..." and "Please enter your Password ...". The emitting program's literals carry no
"your". The expectations were corrected to the program's own text, not the other way round — which is the
whole reason the texts are asserted verbatim rather than by meaning: a paraphrase reads correctly and is not
the contract.

*Embodied in:* `e2e/BatchPipelineE2ETest.java`, `e2e/OnlineTransactionE2ETest.java`,
`e2e/GateVerificationTest.java`. The integration tier already included `**/*E2ETest.java` and
`**/e2e/**/*Test.java`, so all three execute in an ordinary `verify` without a build change.

---

### DL-221 — Reproducing a golden needs a pinned instant and the whole pipeline, and both for reasons a reader would not guess

**Context.** Two independent things had to be discovered by running the comparison the wrong way first, and
both are recorded here because either one silently produces an artefact that is short rather than wrong.

**The clock.** Every timestamp the accrual run writes into a synthesised interest transaction is read from
the clock, so an unpinned run writes a different image on every execution and no byte comparison is
possible at all. Worse, the processing date those transactions carry is what the reporting window filters
on — so under a system clock all fifty of them fall outside the window, the report comes out sixty-eight
records short, and nothing fails. The instant is therefore pinned at `2022-07-06T12:00:00Z`, which is the
instant the committed goldens were produced under and which falls inside the window the golden's own header
states.

**The pipeline order.** The accrual run does not write its synthesised transactions into the master. It
writes them to its own sequential dataset, exactly as the member it translates does, and the *consolidation*
run is what merges that dataset into the master. A report or a statement produced before the consolidation
is missing every interest transaction: 451 report records instead of 519, and 1,212 statement records
instead of 1,262. Both artefacts are internally consistent and both are wrong. The end-to-end class
therefore drives the plan's own order — post, accrue, archive, consolidate, then report and statement — and
asserts the master's row count after each of the three stages that change it, so a future reordering fails
on the count rather than on a mysterious short file.

*Embodied in:* `e2e/BatchPipelineE2ETest.java` (the pinned clock bean, the launch order, and the three
row-count assertions that pin the order in place).
*Asserted by:* the same class; all four goldens match byte for byte under this recipe and under no other.

---


---

### DL-245 — Every decision identifier is unique, because an ambiguous citation is worse than no citation

**Context.** Two hundred and seventy-one `### DL-` headings carried only two hundred and twenty-one
distinct identifiers. Nineteen identifiers were shared, and one - `DL-145` - was carried by six headings
covering six unrelated decisions: an account-screen disclosure rule, an archive framing rule, a
condition-code gate rule, a build-tooling declaration, an operator-authority rule, and a correction note.
Nine places in the module's own source cite `DL-145`. A reader following any of them arrived at six
candidate entries with no way to tell which was meant, and a citation that cannot be resolved is worse
than an absent one: it looks like evidence while supplying none.

**Decision.** The collision is removed in three passes, in this order, and each pass is the least
destructive one that could resolve its own cases.

*Verbatim republication is deleted.* Twenty-four heading blocks were byte-identical to an earlier block
under the same identifier, or differed from one only by a trailing separator. The later copy of each is
removed. Nothing is lost, because nothing differed.

*A note that was given a heading is folded into the entry it annotates.* Five headings carried no decision
at all - they were integrated-state corrections and one reconciliation note, published under the
identifier of the entry immediately beneath them. Each is now a block quote inside that entry, at the top,
where a reader meets it before the reasoning it qualifies. Every word is retained; only the duplicate
heading is gone. Two further pairs differed only in that the earlier copy carried such a note and the
later one did not; the annotated copy is the one kept.

*A genuinely distinct decision receives a genuinely distinct identifier.* Twenty-three remained: separate
decisions that happened to share a number. Each is renumbered into the DL-222 to DL-244 range and carries
a "formerly recorded under" note naming its old identifier, so a reader who arrives with an old citation
in hand is told where they are and why.

**Which occurrence keeps the shared identifier, and why that question was not answered by ordering.** The
keeper is decided by *what the module's source actually cites*, read citation by citation rather than by
taking the first occurrence. That mattered in three places and would have been got wrong by any mechanical
rule:

- `DL-145` is kept by the condition-code gate entry. All nine citations - in `config/BatchConfig`,
  `batch/BackupTransactionJobConfig`, `batch/step/CombineTransactionsProcessor` and their three suites -
  speak of "all four gates as the strict form" and of a divergent literal recorded rather than
  implemented. The archive-framing entry, which a reader might reasonably have guessed at, is cited by
  none of them and becomes DL-226.
- `DL-152` is kept by the diagnostic-fidelity entry, which `logback-spring.xml` and
  `application-local.yml` both cite alongside DL-100. The Grafana dashboard cites the *active-job panel*
  entry instead, which becomes DL-242, and that one citation is repointed.
- `DL-159` is kept by the supply-chain determination the build file's primary reference means. Its
  sibling CVE entry becomes DL-244 - the build file already described it as "the sibling DL-159 entry",
  which is the defect in one phrase - and the category-balance edit-mask entry, cited by
  `batch/CategoryBalanceReportJobConfig`, becomes DL-243.

Three in-log cross-references pointed at a shared identifier as well - two supersession notes naming
`DL-145` when they meant the account-screen entry, and one naming `DL-152` when it meant the panel entry -
and each now names the renumbered entry and says where the old number went.

**What is deliberately not done.** No identifier already unique is renumbered, so the seventy-six
citations of `DL-041`, the thirty-four of `DL-127`, the twenty-three of `DL-102` and every other resolved
citation are untouched. No reasoning is edited, shortened or merged: this entry moves headings and adds
notes, and changes not one sentence of a decision.

*Embodied in:* this log (every heading carrying a distinct identifier), and the three repointed
citations in `pom.xml`, `config/grafana/dashboards/carddemo-overview.json` and
`batch/CategoryBalanceReportJobConfig.java`.
*Asserted by:* `DecisionLogIdentifierContractTest`, which parses every heading and fails the build if any
identifier appears twice or if any identifier cited by the module's own source does not resolve to exactly
one entry.

---

### DL-246 — Compose defaults every variable it interpolates, because a teardown cannot require the provenance of the image it removes

**Context.** `docker-compose.yml` declared two build arguments as required variables,
`${APP_VERSION:?...}` and `${SOURCE_REVISION:?...}`. Compose interpolates the whole file for *every*
subcommand, so with neither exported, `docker compose config`, `ps`, `logs`, `stop` and `down` all failed
with an interpolation error naming the variable but not what a correct value would be. The documented
local validation stack could therefore not be brought up *or torn down* from a clean checkout without
three exports, and the README's own three-line preamble was load-bearing rather than convenient.

**Decision.** Both arguments carry a default. `APP_VERSION` defaults to this module's own Maven version.
`SOURCE_REVISION` defaults to the all-zero forty-character sentinel.

**Why this weakens no guard.** The guards were never in Compose. The `Dockerfile` refuses `APP_VERSION`
unless it matches the Maven version grammar *and* equals the `build.version` the packaged artefact
carries, and refuses `SOURCE_REVISION` unless it is forty lowercase hexadecimal characters *and* is not
the all-zero sentinel. So `docker compose up --build` without exports still stops - at the build, with the
Dockerfile's own message naming the forty-character requirement, which is a better diagnostic than
Compose's - while every read-only subcommand now works. An unlabelled image still cannot be produced by
accident; the refusal simply moved to the place that can say what a correct value looks like.

**Why a `.env` file was rejected.** Shipping one was the other available fix and is the wrong one here.
`.gitignore` excludes `.env` and `.env.*` for a stated reason: Compose reads that file from this
directory and interpolates the database password and the AWS keys from it, which makes a module-level
`.env` the most likely place for a real credential to be committed by accident. Un-ignoring it to hold a
version string would trade a documented security posture for a convenience that a default provides
anyway.

**Where the invariant now lives.** `config/ContainerHardeningContractTest` reads this module's Maven
version out of `pom.xml` and asserts the Compose default equals it, asserts the revision default is
exactly the sentinel, asserts neither is a required-variable reference, and asserts the Dockerfile still
carries all three refusals. A drift between the Compose default and the project version fails the build,
which the `:?` form could never have detected.

*Embodied in:* `docker-compose.yml` (the two defaults and the note explaining them), `README.md` (the
bring-up section now separates what needs no exports from what does).
*Asserted by:* `config/ContainerHardeningContractTest`.

---

### DL-264 - The report submission identity names the authenticated operator, because a deduplication namespace shared between callers is one caller able to suppress another's submission

**Context.** The report-request turn publishes a seventeen-card job image to a first-in-first-out queue,
and that queue deduplicates: the bridge composes each card's deduplication identifier from a submission
identity plus the card's one-based ordinal (DL-148, DL-232). A caller may supply the logical-request token
that identity is derived from, over the `Idempotency-Key` header, so that an interrupted submission can be
completed rather than doubled. The identity was the two date slots plus a digest of that token, and nothing
in it named the caller.

**The defect this entry records.** A token is a value a caller chooses, and the whole point of the header is
that a caller chooses something stable and meaningful to itself - a period name, a run label - so two
operators can arrive at the same token without either knowing about the other. Composed without the caller,
the identity made those two submissions *the same submission*: the queue collapsed the second operator's
seventeen cards as duplicates of the first's, and the second operator was answered with the submission
acknowledgement having published nothing at all. Reproduced against a live first-in-first-out queue by
observing its depth: purge to zero, an administrator submits with a key and the depth reaches seventeen, a
standard user submits the *same period* with the *same* key and is told the report was submitted while the
depth stays at seventeen. The suppression was symmetric - either caller could take the other's key first -
and a submission with no key, whose token is minted server-side, never collided.

**The decision.** The identity is scoped to the authenticated operator. The operator and the token are folded
into a single SHA-256 digest, separated by a unit separator that cannot occur in either value, so no pair can
be rearranged into another pair with the same digest. The identity therefore keeps the shape it always had -
the two readable date slots plus one sixty-four character digest - and keeps its length, which matters because
the bridge bounds a composed deduplication identifier at 128 characters. A retry by the same operator with the
same token and the same window still reissues exactly the identifiers of its first attempt, which is the
property the header exists for; the same token in a different operator's hands is a different submission and
publishes its own cards.

**Where the operator comes from, and where it must not come from.** It is the principal the security chain
established, handed to the service by the controller alongside the token. It is emphatically **not** the
navigation state the client echoes back: that is a value a caller can write anything into, and taking the
operator from it would let a caller choose whose namespace to publish in - the same defect reopened from the
other side. A turn driven with no security context carries no operator and is identified as unauthenticated,
one namespace of its own; the delivered route authenticates, so that case is not reachable over the shipped
boundary.

**What deliberately did not change.** The eighty-character card images, the seventeen-card stream with its
transmitted end-of-stream sentinel, the single message group that preserves the append order the legacy
queue's disposition guaranteed, and the ignore-on-error posture that reports a refused write and abandons the
remainder. The operator's identifier never reaches the queue, the bridge's diagnostics or the response: only
the digest travels, and the `Idempotency-Key` echoed back to a caller is still only the value that caller
supplied.

*Embodied in:* `service/ReportRequestService.java` (the identity and its digest), `api/ReportController.java`
(the principal handed across). *Asserted by:* the cross-operator, same-operator-retry and no-operator cases in
`service/ReportRequestServiceTest.java`.

---

### DL-265 - A presented token must carry an expiry claim at all, because the library's window check judges expiry only when one is present

**Context.** Every token this module mints carries an issue instant and an expiry, and the class that mints
them documents that verification requires signature, expiry and issuer. Verification chained the framework's
timestamp validator, fed with the module's own clock, behind the fixed signature algorithm and the issuer
requirement.

**The defect this entry records.** That validator judges the expiry and the not-before claim *if the token
carries them*. A token carrying neither has no window, so nothing about time can refuse it: it verifies for
as long as the signing key stands. Demonstrated at runtime by re-signing a legitimate token's claim set with
the expiry removed - every other claim, and the signature, unchanged - which authenticated on all eleven
authenticated operations and was correctly refused, at its own scope, on all seven administrative ones. The
class's own documentation asserted the opposite, so this was a gap between a stated contract and an enforced
one rather than a deliberate posture.

**The decision.** A validator that requires the claim to be *present* runs first in the chain, ahead of the
window check, so the window check only ever judges a claim that exists. A token with no expiry is refused
with the standard invalid-token code, and the refusal is indistinguishable from every other verification
failure at the boundary, because the caller's next action is the same in all of them.

**Two limits, stated so neither reads as an omission.** The not-before claim is deliberately *not* required:
nothing in this module mints one, so requiring it would refuse every token the module issues, while a
presented token that does carry one is still judged against it. And the fix does not narrow the tolerance the
timestamp validator keeps for clocks that disagree - that boundary is unchanged and is asserted where it was
already asserted, because moving it would be a different decision from this one.

**Why this is hardening rather than parity.** The legacy had no bearer credential at all: the terminal manager
handed a communication area from one program to the next and its trustworthiness was a property of the region.
There is no legacy behaviour to be faithful to here, so the tie-break rule does not apply; the requirement
comes from outside the estate, which is the class of exception this log records rather than resolves.

*Embodied in:* `config/JwtTokenProvider.java` (the presence validator, chained ahead of the window check).
*Asserted by:* the missing-expiry, present-expiry control and neither-bound cases in
`config/JwtTokenProviderTest.java`.

---

### DL-266 - One framework category is pinned above its emitting level, because it prints a rejected cookie header in full and a bearer token can be inside it

**Context.** The logging configuration names each category whose output the migrated estate depends on, and
keeps the two persistence categories above the level at which they would print statements or bound values.
The embedded servlet container's own categories were left to the root level, which the developer-facing
profiles set at informational.

**The defect this entry records.** The container's cookie parser reports a header it could not parse by
printing the header, in full, at informational level and then at debug level for subsequent occurrences. A
client that puts its bearer credential in a `Cookie` header instead of an `Authorization` header - which this
module correctly refuses to authenticate - therefore had its complete, still-valid token written to the
application log in clear text. Confirmed by replaying the token read back out of the log, which authenticated.
The production profile's root level is above informational, so the record was not emitted there; every other
profile emitted it.

**The decision.** That one category is pinned above the level it emits at, in the shared category block rather
than per profile, so raising the root level in any profile cannot reach it. It is pinned rather than switched
off: the pin closes both the informational and the debug forms of the record, while leaving a genuinely more
serious report from the same class audible. No application code is involved, and nothing else about the
container's diagnostics is changed - the category named is the one that prints the header, and only it.

**Why the narrowest possible pin.** A broader silence over the container's parsing categories would also hide
protocol diagnostics that explain a malformed request, which is exactly the kind of evidence a local gate run
depends on. The finding named one emitter; one emitter is pinned.

*Embodied in:* `logback-spring.xml`. *Asserted by:* `config/ObservabilityConfigTest`, which owns this
document's category assertions.

---

### DL-267 - A transport control character is refused where the value arrives, so the fixed-length record writer is no longer the first thing that meets it — EXTENDS DL-209

**Context.** DL-209 removed encoding from the statement artefact and replaced it with refusal: every
composer rejects anything outside printable US-ASCII, because a refusal cannot alter a byte and
therefore cannot disturb byte parity. That decision was about the *emitter*. It said nothing about
where such a byte comes from, and the answer turned out to be the request boundary.

**The defect this entry records.** Every screen component on the online surface is external text of a
declared width, and the width was the only property checked. A body carrying `VALID`, a line feed and
`POISON` in a transaction description or an address line therefore bound, satisfied every field edit,
and was written to the store — a line feed being a perfectly ordinary character to a width check. The
byte was met for the first time much later and somewhere else: the statement generator moves the
stored field into a hundred-byte record and refuses it there, because one embedded line feed splits
one fixed-length record into two. So a value accepted online failed a batch run afterwards, at a point
where the only remedy is to correct stored data. Two components made it worse rather than better: the
middle name and the second address line carry no edits at all, by requirement, so for those two the
emitter's refusal was the *only* control anywhere in the chain.

**The decision.** The refusal is stated once more at the boundary, on the mapper's text reader, for
every text value of every request body: the C0 range, the delete character and the C1 range are
refused; everything printable binds. It is raised as an ordinary mapping failure against the resolved
property path, so the module's existing error boundary answers `400` naming the offending declared
property with state `INVALID` and echoes no part of the rejected value.

**Why the reader and not a field constraint.** D-34 requires the middle name and the second address
line to carry no constraint, not even a width, because the program they reproduce codes no edit for
either and any constraint would refuse input the original accepted. A guard written as a per-field
annotation could therefore not cover the two components with no other control, and one that skipped
them would leave the exposure open on exactly those two. Placing the rule on the reader covers every
text value uniformly, adds no constraint to any published contract type, and leaves the published
schema unchanged.

**Why this refuses nothing the estate accepted.** The legacy input device cannot transmit one of these
codes. A 3270 field carries displayable characters; the control codes are the datastream's own framing
rather than field content. The rule is therefore a statement about the transport rather than a new
business edit, which is what makes it compatible with the no-feature-expansion boundary and with the
two unconstrained components at the same time.

**What is deliberately still open, restated so it is not read as closed.** Printable markup remains
storable and is still emitted byte for byte, because escaping it would shift every byte after the
substitution and fail the Gate 1 byte comparison. DL-209's division stands unchanged: the statement
file is fixed-width *data*, and a consumer that renders it as a live document is responsible for
encoding at its own serving boundary. What this entry closes is the control-byte half of the exposure —
the half that corrupts a fixed-length record and fails a batch run — and not the rendering half.

**Why the emitter's refusals are kept rather than withdrawn.** A value stored before this rule existed,
a value placed by a fixture, and a value arriving by any route other than the request boundary are all
still possible. The emitter's guard is the last line and remains the last line.

*Embodied in:* `config/WebMvcConfig.java` (`controlCharacterRefusingTextCustomizer` and its reader),
`util/StatementHtmlTemplates.java` (the note recording that its guards are no longer the first line).
*Asserted by:* `api/TransportControlCharacterRefusalTest` — every C0 code, the delete character and
every C1 code refused; both unconstrained components covered; the refusal answered `400` with the
property named, the value absent and the handler never entered; printable markup, an absent property,
an explicit null, an empty value and a blank value all bound unchanged; the four load-bearing
serialisation settings still in force; and the emitter still refusing the same value while emitting
printable markup byte for byte at the declared width.

---

### DL-268 - The sign-on surface keeps its two distinct refusal messages and bounds how many attempts a caller may spend, and the not-found path now does the same work as the wrong-secret path

**Context.** Transaction `CC00` is the module's one anonymous route. It reproduces the legacy screen
faithfully, and two of the properties it reproduces are harmless on a 3270 terminal and are not harmless
on an open network.

**The two properties, and why neither can simply be withdrawn.** The program answers an identifier that
is not on file with one message and a secret that does not match with a different one, at
`app/cbl/COSGN00C.cbl` L249 and L242-L243. Those literals are part of the external interface contract
and Gate 5 verifies them character for character, so flattening them into one message is not available:
it would be a behavioural regression in a contract the preservation boundary (AAP §0.8.1) freezes. The
second property is that verification is deliberately expensive - the credential is BCrypt at the
module's own cost factor - which is a security property and not a defect.

**The defect this entry records.** Together, and with no limit on attempts, those two properties handed
an unauthenticated caller three things. An **explicit enumeration oracle**, from the differing messages.
An **implicit one** that no message change would have closed: the not-found path returned before doing
any digest work at all, so it completed in a fraction of the time the wrong-secret path took and the two
outcomes were distinguishable by timing alone. And an **unbounded channel** - for credential stuffing
against any identifier found, and for spending the server's processor budget on verifications certain to
fail.

**Decision, in three parts.**

1. *The messages stay exactly as they are.* This is the recorded parity exception: the estate's explicit
   distinction between "not on file" and "does not match" is **preserved**, not closed, because it is
   frozen contract text. What changes is that the distinction is no longer free to harvest at scale.
2. *The not-found path performs a digest verification too*, against a digest of a literal that is not a
   credential of anything and that no identity can hold - the record key is eight characters and the
   literal is longer. Both outcomes therefore do the same work and the timing oracle is closed. The
   digest is derived on first use rather than at construction, so the first not-found turn of a process
   does a derivation *as well as* a verification: a not-found turn is never *cheaper* than a
   wrong-secret turn, which is the whole property being bought.
3. *An attempt allowance bounds the surface.* Failures are counted under two independent subjects,
   because the two abuses have different shapes: a stuffing run repeats one identifier and is caught by
   the identity subject, while a sweep never repeats an identifier at all and can only be caught by the
   source subject. Either subject reaching its allowance refuses further attempts for a finite period,
   and a refused attempt reads no record and verifies nothing - it is the work, not the answer, that the
   abuse was spending.

**What a refused caller is told, and why it is not a new answer.** The screen's existing
`UNABLE_TO_VERIFY` outcome, with its existing frozen literal. Two reasons agree. No new message text may
be minted, because the seven literals are the contract. And a distinct "you are being throttled" answer -
whether as text, as a status code or as a header - is itself an oracle: it tells a sweeping caller
exactly when to pause and from where to resume. It is also an accurate statement of what happened, since
the service declined to verify. Operators learn about it from the counters and the log records, which is
where that information belongs.

**Four deliberate non-behaviours, each of which would be a defect.** A refusal already in force is not
extended by further attempts, or a caller could hold someone else's identifier refused indefinitely. A
store failure is not counted, or an outage would lock every operator out for the outage plus the refusal
period. A turn that supplied no identifier or no secret is not counted, because it presented no
credential to be wrong about. And no diagnostic and no metric tag ever names the identifier, the caller
address or the presented secret - an enumerated identifier as a metric label would be both a disclosure
and unbounded cardinality.

**Every figure is configuration and none is a service level.** The allowance, the window, the refusal
period and the tracked-subject ceiling are abuse-resistance thresholds, not latency, throughput or
capacity targets: the estate documents no service level and none is invented here (see DL-073). Each
carries a default so a fresh clone runs with the protection on, and each is overridable because what
counts as abusive depends on how a deployment is reached. The tracked-subject ceiling exists because the
subject of a failure is partly caller-supplied, so an unbounded map would be a second denial-of-service
channel; reaching the ceiling is counted rather than silent, and a refusing entry is never evicted to
make room.

*Embodied in:* `service/SignOnAttemptGovernor.java`, `service/AuthenticationService.java` (the governor
consulted before any read, the inert verification on the not-found path, and the outcomes that are and
are not counted), `api/AuthController.java` (the caller address attributed, read from the connection
rather than from a caller-supplied forwarding header).
*Asserted by:* `service/SignOnAbuseResistanceTest` - the allowance spent and engaged, a sweep caught by
the source subject where an identity-only counter would never refuse it, a stuffing run caught by the
identity subject, case folding making two spellings one subject, the refusal lifting by itself and not
being extended, failures outside the window not accumulating, an admission clearing the count, the
tracked-subject bound with its counter, a refusing entry surviving subject pressure, the per-namespace
counters, the unattributed-source subject, the disabled posture, every configured figure validated, the
not-found path performing a verification, the two outcomes still carrying different decisions, an
exhausted allowance reading and verifying nothing, a store failure not counted, an incomplete submission
not counted, and no subject appearing in any tag.

---

### DL-269 - A staged path is believed only after it is checked, and a cleanup deletes only what the execution registered — SUPERSEDES IN PART DL-211

**Context.** Two places in the batch tier turned a *filename* into an action. `currentLocalGeneration`
resolves the current generation of a base by taking the highest generation token it finds in the staging
root, because that is what the catalogue's relative generation `(0)` resolved to. `discardLocalArtifactsOf`
removed a failed execution's dead artefacts by matching its generation token in a filename. Both are
correct as descriptions of intent and both were wrong as implementations, because on a shared host a
filename is something an actor other than this deployment can choose.

**The two defects.**

1. *A planted higher generation was read as the job's input.* The resolver asked
   `Files.isRegularFile(entry)` at its default, which **follows a symbolic link and answers about the
   target**. A link named `<base>.G9999999999V00` pointing anywhere at all was therefore reported as a
   regular file, outranked every genuine generation on token order, and the next job to consume that base
   read the attacker's records. No ownership was examined, no permission on the root was examined, and the
   entry's parent was never compared with the root, so a name that resolved elsewhere was equally
   acceptable.
2. *A cleanup could be aimed by choosing a filename.* The sweep deleted every regular file whose name
   merely **contained** the execution token. The token is `G` plus a zero-padded framework execution
   identifier, which increases monotonically and is therefore guessable, so `something-else.G0000000004V00`,
   `<base>.G0000000004V00.keep-this` and `audit-G0000000004V00-report.txt` were all removed after execution
   four failed — as was any unrelated file that happened to contain the string.

**Decision — two controls, each stated once and used at both sites.**

*One trust predicate.* `util/SecureStagedFiles.isTrustedStagedArtifact(root, candidate)` answers whether a
path may be believed as a staged artefact of this deployment, and requires four properties **together**:
the candidate is a real regular file inspected with `NOFOLLOW_LINKS`; its normalised parent is the
normalised root; its owner is the root's owner; and neither it nor the root grants **write** permission
outside that owner. Read and execute permission outside the owner is deliberately not examined — a staging
root a deployment lets an operator or a collector read is a legitimate arrangement, and refusing it would
refuse the job's own output for a reason unrelated to whether the output is genuine. The predicate answers
`false` rather than raising when an attribute cannot be read, because a candidate whose trustworthiness
cannot be established is not trustworthy. It is asked immediately before the path is used, never
remembered from creation time, because the whole exposure is the interval between the two.

*One anchored grammar.* A generation name is a candidate only when it is exactly what this store would
have written for that number, decided by **round-tripping the parsed value back through the writer**. That
refuses every non-canonical spelling of the same value at once — over-padded, under-padded, a wrong
version suffix, the working suffix, a wrong separator — without enumerating them, and it cannot drift from
the writer because it calls the writer.

*And the cleanup selects from the registry.* Every completed local generation is already registered on the
job execution's own context with its exact absolute path. `discardLocalArtifactsOf` now deletes those paths
and, for each, the one working sibling the store itself names from it — so no name is ever matched, and a
third party's choice of filename reaches nothing. Each path is re-checked against the trust predicate
immediately before the delete, so a registered name whose file has since been replaced by a link or by
another account's file is left alone rather than followed. A file allocated but never completed carries no
registration and is therefore not removed; that omission falls in the safe direction, because an
unregistered working file is a nuisance whereas deleting by guessed name is a vulnerability.

**A candidate that parses but is not trusted is reported, not silently skipped.** A well-named file in the
staging root that this deployment does not own is exactly the event an operator needs to see. The scan then
continues, so a planted higher generation cannot mask the genuine generation below it. The diagnostic names
the filename only: the content is never read and no attribute is echoed.

**One configuration consequence, stated so it is not met as a surprise.** A deployment that points the
staging directory at a shared world-writable location — `/tmp` itself, rather than the module's own
subdirectory of it — will now have its staged artefacts refused, with the reason logged. That is the
correct answer rather than a regression: such a root cannot distinguish this deployment's output from
anybody else's. The shipped default is a module-owned subdirectory created owner-only, so the default
configuration is unaffected.

*Embodied in:* `util/SecureStagedFiles.java` (`isTrustedStagedArtifact` and its write-permission helper),
`batch/step/StagedGenerationStore.java` (`currentLocalGeneration` no-follow and trust-checked, the
round-trip grammar anchor, `discardLocalArtifactsOf` registry-driven with a per-path re-check),
`batch/CombineTransactionsJobConfig.java` (the exact-local-name rung trust-checked rather than tested for
mere existence), `config/BatchConfig.java` (the boundary listener's contract restated).
*Asserted by:* `util/SecureStagedFilesTest` (nest `a candidate is trusted only when four independent
properties all hold` — the control case plus one negative per property, including the symbolic link and
both writable cases, and the deliberate acceptance of a readable-but-not-writable root),
`batch/step/StagedGenerationStoreTest` (nests `the current local generation is resolved by name, so the
name is not enough` — the planted link, the planted directory, the writable candidate and the five
non-canonical spellings each losing to the genuine generation — and `an execution that did not complete
discards its own local artifacts`, including the three planted decoy filenames and the registered path
replaced by a link).

---

### DL-270 - The business surface names the two authorities that may reach it, the response headers are stated by tests rather than inherited silently, and a refusal is one status and not either of two — CORRECTS DL-235

**Context.** Three separate observations about the same boundary, all of the same kind: the delivered
posture was stronger than a reader would guess in one place, weaker than the record claimed in another, and
in the third the tests accepted two answers where the system gives one. None of them is a missing feature.
All three are cases where what is actually enforced and what is actually asserted had drifted apart.

**1. The chain admitted "any identity" where it meant "either sign-on identity".** The application chain
closed with `anyRequest().authenticated()` and named no authority for any business route. That rule asks
whether an identity was established; it does not ask whose. The estate declares exactly two user types and
both are sign-on records, so the question the estate asks cannot be expressed by that rule at all — and the
gap is not hypothetical, because this same process already mints a third authority for the management
surface. That authority is refused on a business route today only because the management chain is matched
first and this chain never sees it, which is an accident of ordering rather than an answer. Any authority
introduced on this chain later would have inherited every account, card, transaction, statement and
bill-payment route by default.

*Decision.* One rule, over the API root and every descendant, requiring either `ROLE_ADMIN` or `ROLE_USER`
by name. It is **positive rather than a denial of the operator authority that happens to exist now**: an
authority added later is refused because nothing admitted it, not admitted because nobody remembered to
exclude it. That is the whole difference between an allow-list and a deny-list, and it is the direction a
security rule must fail in.

*Why the root and not the ten addresses.* Gating the root means a business route added later is inside the
allow-list from the moment it exists. The pattern is deliberately broader than the ordinary transactions
alone — it spans the sign-on route and both administrator-only prefixes — and the breadth is harmless
because rule order decides: the anonymous permit, the administrative loop and the two batch rules are all
registered ahead of it, so it only ever answers what none of them claimed. Registered the other way round
it would make the sign-on route unreachable, which is why the ordering is asserted by driving real
responses rather than by inspecting the configuration.

*Why the rule is written in the chain rather than read out of the route table.* The anonymous and
administrative rules are read from the table, because those entitlements belong to named transactions and
reclassifying a transaction should change the rule. This one must not work that way. Were the last ordinary
transaction ever reclassified, a table-driven rule would withdraw itself and the closing catch-all would
widen the surface again — a widening produced by an edit that reads as a narrowing. So the rule is
unconditional and the entitlement merely *names* the region, which is what lets a reader of the entitlement
learn what enforces it.

*And the closing rule stays.* It now answers only what lies outside the API root: the container's error path
reached as an ordinary client request, and any address this module maps nothing to. Requiring an identity of
those is the right default — it fails closed for an unmapped path without turning the framework's own
not-found answer into a forbidden one — and it is no longer what protects a business route.

**2. The response headers were the framework's defaults and nothing said so.** This configuration calls no
header customizer, so content-type sniffing refusal, the framing policy, the no-store cache directives and
the transport-security directive all arrive from the framework. That is a perfectly good posture and it was
entirely unstated: an edit that disabled the writers, narrowed the transport-security window or relaxed the
frame policy would have changed the delivered posture without changing a line any reviewer would notice, and
no test would have failed.

*Decision.* State the posture as assertions over real responses rather than as prose. Each header is read
back from an actual response — on a served request, on a refusal, on the anonymous route, and on the
management chain, which is a separate chain and would otherwise be a separate posture nobody had stated.
Transport security is asserted on a secure request, which is when the directive can be honoured, and its
absence on a plain request is asserted too, with the reason: a client that reached the response over plain
transport has no secure origin to pin it to, and the module's own answer to plain transport is the redirect
its transport rule already performs. The values are pinned by their security-bearing directives — the
max-age, the subdomain inclusion, no-store and no-cache — rather than by exact header text, because
whitespace in a framework's formatting is not a security property and pinning it would produce a test that
fails for a reason nobody cares about.

**3. Two refusal assertions accepted either status.** An end-to-end assertion allowed *forbidden or
unauthorized* for a caller presenting a session the module had issued a moment earlier, and allowed either
for a caller presenting nothing at all. Both are ambiguous in the direction that hides a defect. In the
first case an unauthorized answer would mean the credential had not been accepted at all — a broken
session, a different defect entirely, and one the alternative would have concealed. In the second an
established identity found wanting on entitlement and no identity at all would have become
interchangeable, and that distinction is the one the estate itself drew: a wrong password and an
insufficient user type were never the same answer.

*Decision.* Exactly forbidden for the valid-but-unentitled session; exactly unauthorized for the absent
credential. Stated as single statuses, with the reason recorded at the assertion so a future reader does not
loosen it back.

**What makes the first control provable rather than merely written.** The chain's bearer filter derives its
granted authority from the sign-on record's user type, and the estate declares two — so no token can carry a
third authority and no token-driven test can ask what the rule does with one. The verification therefore
presents an identity to the chain directly, which is precisely the condition the rule defends against.
Reverting the rule to bare authentication turns three of its assertions from forbidden to a served
two-hundred, which is the over-grant itself, observed as a response.

*Embodied in:* `util/ApiRoutePaths.java` (`API_PATH_PREFIX`, with the three existing prefixes now spelled
from it so they cannot drift onto different roots), `config/SecurityConfig.java` (the published root
constant, `BUSINESS_SURFACE_PATTERN`, the `Gating.AUTHENTICATED` entitlement now naming the rule that
enforces it, the chain rule, and the start-up line that describes the delivered chain).
*Asserted by:* `config/SecurityConfigTest` (nests `the business surface is an allow-list of the two sign-on
authorities, not merely a check that some identity exists` and `the response headers the chain writes,
pinned because nothing else states them`), `config/SecurityConfigRouteTableTest` (every entitlement names a
rule; the three patterns and their containment order), `e2e/OnlineTransactionE2ETest` (the two exact
statuses).

---

### DL-271 - Both account screens read one derivation of the disclosure authority, so the same four values of the same record cannot be answered two ways — COMPLETES DL-225, CORRECTS DL-144

**Context.** DL-135 made four customer values of the two account screens masked by default and revealable
only under a named purpose plus an authority. DL-143 licensed the update service to read them. DL-144 named
the REST boundary as the view screen's assembler and had it present a *constant* authority —
`unprivileged(ACCOUNT_VIEW, null)`, permanently unprivileged and carrying no user type at all. DL-225 then
decided that **both** screens would derive the authority from the authenticated principal, and stated in its
closing paragraph that "an administrator receives the values and every other operator receives masks, on
both screens".

**What was actually delivered.** Half of it. The update turn derived its authority from the principal
exactly as decided. The view turn kept the constant. The consequence was not a residual exposure but a
reversed one: an administrator received **masks on the view screen and cleartext on the update screen**, for
the same four values of the same customer record, in the same session. Two entries recorded the asymmetry as
closed, and a third — DL-144's own supersession note — described the derivation as installed. None of it was
observable, because the view-screen assertions were written against the constant and passed.

**Decision.** One derivation, `AccountController.revealAuthorityFor(purpose, authentication)`, read by both
turns. It maps the administrator type to `RevealAuthorization.administrator(purpose)` and every other type —
an unresolved one and an absent identity included — to `RevealAuthorization.unprivileged(purpose, userType)`.
The purpose is the only thing the two call sites differ in, which is the whole point: the *policy* is one
expression and only the operation being served is a parameter, so the two screens cannot come to disagree by
an edit to one of them. Two constants, one per screen, could always drift; a derivation with a purpose
parameter cannot.

**One detail about the gate, recorded because it is easy to read the wrong way.** What decides a reveal is the
user type carried on the authority, not which factory built it: `RevealAuthorization.permitsReveal()` reads
the type, so `unprivileged(purpose, ADMIN)` permits a reveal exactly as `administrator(purpose)` does. The
factory names the caller's standing rather than deciding it. The constant that was replaced therefore masked
everything because it passed a **null** type, not because it named the unprivileged factory — and a
regression that kept the derivation but dropped the type would mask an administrator again while reading as
correct. That is why the assertions are written against a revealed *value* rather than against which factory
was called: substituting the pre-fix constant makes the view route report a birth date of ten asterisks where
the stored date was expected, which is the defect stated as a value rather than as a call.

**What is asserted, and what deliberately is not.** An administrator reveals, from the sign-on split at
`app/cbl/COSGN00C.cbl:L227-L236`. Ownership is **not** asserted: the estate's sign-on record carries no
account linkage and no program in the estate checks one, so this boundary has no honest way to establish
that a caller owns the account it named, and asking for a mask is the truthful request when that is the case.
An account identifier stays a request field rather than a property of the caller, exactly as the estate had
it — what a caller may *name* is unchanged and only what it may *see* is decided here.

**The authority comes from the established identity and from nothing else.** Not from the echoed
communication area: that is a client-supplied value, an authorization decided by request content is not an
authorization, and a caller that wrote an administrative user type into the echoed record would otherwise
have granted itself the reveal. This is asserted directly rather than left to the reading of the code — a
turn is driven with an ordinary session and a well-formed echoed record claiming the administrative type and
an administrative identifier, and it still receives stand-ins.

**The divergence from the estate is unchanged and is restated so it is not read as new.** The legacy maps
showed all four values to any signed-on operator. This module withholds them from a non-administrator on
both screens. That is a deliberate, documented divergence on the same footing as the credential-hashing
exception, and it can only disclose *less* than the estate did, never more. What DL-271 changes is that an
administrator is no longer withheld from too — which was a divergence nobody had decided.

*Embodied in:* `api/AccountController.java` (`revealAuthorityFor`, read by `toViewResponse` and
`gatedForUpdate`; the constant `VIEW_REVEAL_AUTHORIZATION` removed).
*Asserted by:* `api/AccountControllerTest` (masked for an ordinary caller, revealed for an administrator,
nothing revealed for an absent identity — all three on the view route), `api/AccountControllerIT` (the same
three against a real database and a real sealed column, plus the forged-echoed-identity turn, plus the
standing assertion that no sealed envelope is ever published on either the revealing or the withholding
path).

---

### DL-272 - The report bridge's security properties are proved over the real filter chain, because a handed-in principal can only prove that the boundary used the identity it was given

**Context.** Gate 5 requires that every external interface be verified by a local test exercising the real
contract, and states that self-certification is not acceptable. The queue half of that was already
discharged honestly: the card contract — seventeen images, eighty characters each, the four substituted
slots, the transmitted sentinel, the message group, the ordering, the tolerated write failure — is asserted
against a real queue in a real emulator, by draining and counting.

**What was not covered, and why the gap was invisible.** That specification drives the boundary through a
standalone servlet harness and hands it a `Principal` directly. For its own subject that is the right
shape: the cards a submission carries do not depend on who asked for them, and installing a filter chain
would add machinery to a specification about bytes. But a handed-in principal is an **assumption**, and it
is the assumption under test everywhere the question is a security question. It establishes that the
boundary uses the identity it is given. It cannot establish that the delivered route establishes one at all,
that a caller presenting nothing is refused *before* the queue is touched rather than merely refused in the
reply, or that two callers are kept apart. The last of those matters most, because the queue deduplicates:
the submission identity is a pure function of the date range, the retry token and the operator, and a
namespace shared between callers is one caller able to suppress another's submission. With only a handed-in
principal both submissions carry the same subject, so the very condition that would expose a shared
namespace could not be created.

**Decision.** A second specification over the same graph and the same emulator, with the real chain in
front of it: `api/ReportSubmissionAuthorizationIT`. The security half is not stubbed anywhere — the chain,
the token provider, the sign-on state reader and the refusal renderer are the shipped beans, and every
identity is established by verifying a credential the shipped provider minted from a seeded record. Nothing
of the card contract is re-asserted; that would produce two places to change when one contract changes. This
specification counts cards and compares namespaces.

**The five properties it establishes, each of which needs the chain to run.**

1. *A request presenting no credential publishes nothing.* Asserted by draining the queue afterwards and
   finding it empty. A refusal that still enqueued would be a refusal in the reply and a submission in the
   estate, and a reply-only assertion could never tell the two apart.
2. *An unverifiable credential is refused on the same terms and likewise publishes nothing.*
3. *A verified credential is served and publishes exactly one complete stream* — bounded above the expected
   count, so a doubled stream is observable rather than truncated to what was expected.
4. *Two operators presenting the same retry token for the same period each publish a complete stream.* A
   caller chooses a token meaningful to itself — a period name, a run label — so two operators arriving at
   the same one is the ordinary case rather than an adversarial one.
5. *The same operator repeating a token does not double its stream, and the same operator submitting the
   same period with no token does.* The two halves of the idempotency contract: a retry reissues the
   earlier identifiers so the queue completes the stream rather than doubling it behind itself, while the
   absence of a token reaches the legacy behaviour, where a second request for the same period was appended
   and the job ran again.
6. *An echoed navigation identity decides nothing.* One caller submits while claiming, in the record it
   echoes back, to be the other operator and to hold the administrative type. Both streams still arrive —
   so the echoed value neither borrowed the other operator's namespace nor suppressed it. And an echoed
   identifier presented *without* a credential is still unauthorized, so it cannot become an identity of
   its own. Over a standalone harness neither of these can be tested at all, because there is no
   established identity for an echoed one to be preferred over.

**Why the assertions are load-bearing, demonstrated rather than asserted.** Dropping the operator from the
submission identity — the exact earlier defect — makes two of them fail with *expected size 34 but was 17*:
the second operator's seventeen cards collapsed as duplicates of the first's while the second operator was
told its request had been submitted. That is the defect stated as a count of messages actually drained from
a queue.

**One environmental property that had to be handled rather than tolerated.** The queue deduplicates by
identifier over a five-minute window, and emptying the queue between tests removes the *messages* without
clearing that window. Two tests presenting the same token for the same period as the same operator would
therefore be one logical submission as far as the queue is concerned, and the second would drain nothing and
report a defect that does not exist. Every test names its own token, and a per-run nonce keeps those names
distinct from a previous run's as well. This is recorded because it is exactly the kind of shared-emulator
coupling that produces an intermittent failure nobody can reproduce.

*Embodied in:* `api/ReportSubmissionAuthorizationIT` (new), with `api/AwsIntegrationIT`'s own account of
what it deliberately does not cover amended to name it, so a reader of the standalone specification finds
the integrated one rather than concluding the questions were never asked.

---

### DL-273 - An integration specification stages inside a directory private to its own process, and removes only that tree, because a shared staging root is read by the next run as its own input

**Context.** Five batch specifications each staged into a fixed directory beneath the platform temporary
directory — `${java.io.tmpdir}/carddemo-<something>-it` — and each cleaned up by sweeping every regular file
beneath that root in a lifecycle callback. Two of them fell back to the same shared configuration key, so
they resolved the *same* directory as each other.

**Why that was a defect rather than untidiness.** Three properties compound.

The directory outlives the run while the framework's execution identifiers restart. A staged generation is
named from a batch execution identifier, and those begin again from a low number against a fresh metadata
schema. A generation left behind by an earlier run therefore reappears as though it belonged to a *later*
execution, and the resolver that selects the highest generation of a base selects it. This is not
hypothetical: after the conversion described here, the pre-conversion root
`/tmp/carddemo-create-statement-it` was still on the host holding ten staged generation files from an
earlier run, any of which a subsequent run of the unconverted specification would have ranked against its
own output.

The directory is shared between specifications and between clones. This repository is worked on by several
clones in parallel on one host, every one of them resolving the same absolute path from the same system
property. Two executions staging a generation of the same logical base at the same time write to one
filename.

The cleanup is a blanket sweep. Each callback deleted every regular file under the root — which is every
file *anything* put there, including a file a concurrently running sibling was still composing. A sweep
that removes another execution's input fails a specification that has nothing wrong with it, and attributes
the failure to the wrong subject. This is the same class of error DL-269 removed from the production
cleanup path; leaving it in the test tier would have meant the tests asserting the production contract were
themselves built on the behaviour that contract forbids.

**Decision.** `support/IsolatedStagingRoot` mints one namespace per operating-system process — the clone
index where the environment publishes one, the process identifier, and a random component so two processes
reusing an identifier after a restart still differ — and gives each specification a directory of its own
beneath it, keyed by a label. The five specifications resolve their root through it and discard only that
root.

**Two details are load-bearing and must not be simplified.**

The root is created through `util/SecureStagedFiles.prepareDirectory`, the production helper the module
itself stages with, rather than through a second implementation in the test tier. The module refuses to
believe a staged artefact whose root grants write permission outside its owner (DL-269), so a test root
created any other way risks having its own job's output refused — and that refusal would present as a defect
in the job rather than in the harness. Using the same helper makes the test root satisfy the same predicate
by construction, and `prepareDirectory` sets mode 0700 through an explicit permission attribute rather than
by subtraction from a umask, so the guarantee does not depend on how the host is configured.

The root must be resolved *before* the application context is created, which means from a
`@DynamicPropertySource` method rather than a `@TestPropertySource` literal: a staging directory is bound
while the context starts, so a value produced later arrives too late to be read.

**What is verified.** `support/IsolatedStagingRootTest` asserts the properties the five specifications
depend on rather than the implementation: the root sits beneath this process's own namespace and not
directly beneath the platform temporary directory; the namespace name carries the process identifier; the
root and the namespace above it are both owner-only; an artefact staged within it is trusted by
`SecureStagedFiles.isTrustedStagedArtifact`; one label yields one stable path across calls, so a set-up
callback and a clean-up callback cannot name different directories; two labels differ; a label that would
escape the namespace is refused; the discard removes the tree, is silent about a root already gone, and
**refuses** a path outside this process's namespace rather than deleting it.

The owner-only assertion is made against the permissions directly and not only through the trust predicate.
That is deliberate, and it was established by mutation rather than by reasoning: replacing
`prepareDirectory` with `Files.createDirectories` and re-running left the predicate satisfied and the
specification green, because the predicate narrows to *write* outside the owner (DL-269) and this host's
umask of 0022 yields mode 0755 — readable and traversable by every other account, but not writable. The
assertion as it now stands fails that mutation on the permission comparison, with the message naming what
the exposure would be. Asserting only through the predicate would have pinned nothing on a host with a
restrictive umask.

**What this does not claim.** Two further specifications, `batch/CombineTransactionsJobIT` and
`batch/InterestCalculationJobIT`, already namespace their roots by process identifier and clean up after
themselves; they are left as they are. `batch/DailyTransactionReadJobConfigIT` names the platform temporary
directory but never writes within it — the value is passed so that a dataset name is not resolved as a
class-path resource (DL-216) — so it is not a staging root at all. `batch/step/BatchAwsIntegrationIT` did
hand the shared temporary directory to the job-boundary listener; because the listener deletes within that
root on any non-completed outcome, the argument was changed to the per-test directory the class already
declares, rather than left safe only by virtue of the one execution status that specification happens to
drive.

### DL-274 - A sensitive value is pinned by a projection of itself, because an assertion library reports what it compared and a build log is read by more people than the data is

**Context.** An assertion library prints its operands when it fails. `assertThat(token).isNotBlank()` prints
the token. `assertThat(digests).doesNotHaveDuplicates()` prints every digest it holds.
`assertThat(body).doesNotContain(token)` prints the needle *and* the haystack, so the assertion whose whole
purpose is to prove a session token is not disclosed becomes the thing that discloses it. And it does so
exactly when something is already going wrong, into an artefact that is retained, is usually readable by
more people than the running system's data is, and is frequently shipped to a third party.

The suite did this with live session tokens, stored credential digests, fixture passwords, card numbers and
card verification codes.

**Why "the values are synthetic" is not the answer.** It is a reason the exposure is low severity. It is not
a reason the shape is acceptable. These assertion shapes are what a team copies into a suite that runs
against something real, and a card number and a verification code are regulated by their shape rather than
by their provenance. The delivered posture should not depend on the fixtures staying synthetic.

**Decision.** `support/SensitiveValues` provides projections, and the call site asserts on the projection
with the ordinary assertion library:

- `fingerprint(value)` - `sha256:` and twelve hex characters. **This costs no strength.** Two values with
  one SHA-256 digest are one value, so an assertion comparing fingerprints fails on exactly the inputs the
  equality assertion failed on; only what it prints changes.
- `describe(value)` - length and fingerprint, for a description that has to identify a value.
- `fingerprints(list)` - one fingerprint per member, in order, so an ordered comparison still proves order.
- `distinctCount(list)` - replaces duplicate-freeness, which prints every member.
- `ascending(list)` - sortedness as a predicate, because a digest does not preserve order and fingerprints
  therefore cannot be asserted to be sorted.
- `absentFrom(text, value)` - replaces `doesNotContain`, which prints both operands.
- `hasSignedTokenShape(token)` - replaces a pattern match, which prints its subject.

**Projections rather than an assertion wrapper.** A helper that raised the failure itself would take the
description, the soft-assertion grouping and the chaining away from the call site, and would make every
sensitive assertion read differently from every other assertion in the suite. Reading differently is how a
convention stops being followed.

**What is verified.** `support/SensitiveValuesTest` asserts the load-bearing property first - equal values
fingerprint equally and values one character apart do not - because if that failed, every converted
assertion would have been weakened rather than made safe. It then asserts that no substring of a value
appears in its own fingerprint, that an absent value stays distinguishable from an empty one, that a
collection's rendering carries no member, and that the absence predicate answers what `doesNotContain`
answers. Beyond the helper, the conversion was checked by mutation at a call site: swapping one expected
credential for another produced a failure reporting `expected: "sha256:07a68263b5ce"` and no credential
value - the assertion still bites, and the failure output is safe.

**Where the line was drawn, and why it is not "everything".**

Converted: values of the five kinds the review named - session token, stored credential digest, fixture
password, card number, card verification code - plus the government-issued identifier and the national
identifier on the account screens, which are identity documents and fall under the same standard even though
the review filed them under the privacy-policy finding instead.

Deliberately not converted, in each case because obscuring would cost the diagnostic everything and protect
nothing:

- Account identifiers, customer identifiers, names, statuses, dates, widths, offsets, field-name constants
  and message literals. These are printed as they always were.
- The stored birth date on the account screens. It is not one of the named kinds, and a failure there needs
  to say which date was returned to be actionable at all.
- Whole-record equality assertions on request DTOs. Projecting an entire record to a digest would leave a
  failure unable to say which field differed, which is the only thing such an assertion is for. What such a
  record renders is the record's own concern, addressed by the redaction its `toString` already applies.
- The synthetic fixture constants themselves, in `support/TestDataFactory` and in the specifications. A test
  that posts a card update has to send a card number, so the value must exist in the test tier. The
  requirement is that an assertion not print it, not that the fixtures stop existing.

### DL-275 - Three delivered claims described a system other than the delivered one, and the corrections are recorded rather than quietly applied

**Context.** A security review found three statements in the delivered evidence that the delivered code
contradicts. None of them was a coding defect; all three were worse in one specific way, which is that a
reader with no access to the source would have believed them. Documentation that overstates is how a gap
reaches production unnoticed, because the next person to look does not look.

They are recorded here rather than silently edited because the same three arguments are the ones that will
be made again.

---

**One. "No credential ... appears in any committed configuration file" was false.**

`docs/architecture.md` claimed absolutely that no credential, password, token, signing secret or access-key
value appears in the document, the source, or any committed configuration file.
`src/main/resources/application-local.yml` commits four: a default database password, LocalStack access and
secret keys, a JWT signing secret and a management token.

The claim was not merely imprecise, it was the *convenient* claim - "no secrets are committed" is what a
reader wants to hear, and it is what an auditor would have checked first and found wrong first.

The correction states the boundary instead of an absolute. Production carries no secret value and no
fallback for one: each required key is written `${VARIABLE}` and never `${VARIABLE:something}`, enforced by
`config/ProductionConfigurationValidator` as a `BeanFactoryPostProcessor` that runs before the data source
is created, before the migration runner opens a connection and before a key store is read - so a missing
variable stops start-up without infrastructure ever holding a placeholder. The local and test profiles
*do* commit fixture values; each is overridable by the same variable production uses, each is
self-labelling as unusable elsewhere, and each addresses a loopback-published service. They are safe
because their profile is not production, not because they are absent.

Two limits of the enforcement are now stated rather than implied, because a reader would otherwise infer
more than the mechanism delivers: the validator judges **usability** - present, non-blank, no residual
placeholder syntax - and deliberately not **shape**; and a variable exported as an *empty* string resolves
to an empty string on every path, so an operator can defeat the check by setting a variable to nothing.

---

**Two. The delivered documentation described a table, and a durability property, that were removed before
delivery.**

`carddemo-java/README.md` stated that `V2__create_indexes.sql` creates `job_submission_outbox`, that a
running database holds nineteen tables, and that the outbox holds the online-to-batch bridge's delivery
progress so one submission's cards cannot be interleaved with another's after a retry.

None of it is true of the delivered module. No migration creates such a table - `V2__create_indexes.sql`
says so at its own head - and no production source file mentions one; `GateVerificationTest` asserts
**eleven** application tables by name. The true total is **eighteen**: eleven record layouts, the six Spring
Batch metadata tables, and Flyway's own history table. The removal itself was already decided and recorded
in DL-148; what survived was the description of the thing that had been removed.

Worse, the decision log itself carried **four orphaned fragments** left by earlier incomplete edits, each
sitting immediately after its own replacement text, each beginning mid-sentence, and each describing the
outbox in the **present tense** - including two `Cited by:` lists naming three classes that do not exist
(`JobSubmissionOutbox`, `PostgresJobSubmissionOutbox`, `PostgresJobSubmissionOutboxIT`) and a migration
path that does not exist (`db/migration/schema/V2__create_indexes.sql`). A correction that replaces a
prefix and leaves the tail reads as a contradiction of itself, and one that leaves a citation behind sends
the next reader to a file that is not there.

All four are removed. Finding the fourth required a second sweep: the first looked for a paragraph
beginning with a lowercase letter directly after a blank line and found three, but the fourth began with a
backtick-quoted class name and so did not match. A broader sweep - a continuation-shaped line directly
after a completed sentence - surfaced it along with eleven legitimate paragraphs that genuinely open with a
quoted identifier, each of which was read and kept. Both sweeps now return no orphan.

One near-miss is recorded because it looked like the same defect and was not. `DL-127` still describes a
`db/migration/schema` and `db/migration/seed` split that the delivered module does not have - every profile
resolves one flat `classpath:db/migration` and `config/FlywayConfig` says in terms that there is no such
child directory. That entry is *already* headed by a dated correction stating the split was an intermediate
implementation and is superseded, so its body is properly framed as history rather than asserted as fact,
and it was left alone.

The behaviour the documentation now describes is the behaviour the code has, which is the legacy behaviour:
one call publishes its own cards once each, in order, into a single first-in-first-out message group; it
**stops at the first refusal**; the refusal is non-fatal and warns with the published count against the
requested count; and nothing is remembered for a later attempt. A submission that stopped part way left a
prefix of its cards on the queue. That is `ERROROPTION(IGNORE)` faithfully, not a shortfall.

---

**Three. A vulnerability was dismissed partly on a range that does not exclude the installed version.**

The dependency-evidence entry argued that `CVE-2026-41178` does not apply to `opentelemetry-semconv-1.43.0`
partly because "the database's own affected range ends *below* the version installed here."

The local vulnerability data was queried directly, and it carries **two** configuration rows for that
identifier: one range row with `versionEndExcluding = 1.42.0`, and a **second row naming `1.43.0` exactly**,
with no range bounds and marked vulnerable. The exact-version row is the one the installed artifact
matches, and it is why the scan reports the finding at all - had the range row been the only one, this
version would have fallen outside it and there would have been nothing to explain away. **A claim
contradicted by the very output it was written to explain should not have survived.**

The range argument is withdrawn. Non-applicability now rests only on product and target-software identity,
which the same query supports on **both** rows: each carries the target-software component `go`, and the
described defect is in the Go implementation's baggage-header parsing, so the similarly-named Java artifact
is not the affected product. The conclusion is unchanged and the reason for it is now the only reason
given.

---

**Why all three are corrections in place rather than deletions.** A deleted false claim leaves no trace
that it was ever believed, and the next author re-derives it. Each of these is now recorded with what was
claimed, what the code actually does, and how the discrepancy was established - the committed
configuration read line by line, the migration and the table-count assertion read against the README, and
the vulnerability database queried rather than characterised.

## 16. Discrepancies between the specification and the source

Section 9 records conflicts *inside* the legacy estate. This section records conflicts between the
migration specification — or the prior delivery's own documentation — and what the source actually
contains.

The handling is the same in every case and is stated once here: **the measured value is recorded, the
claim it corrects is named, and neither side is silently adopted.** A figure is never quietly replaced,
because a reader who greps the estate and gets a different number needs to find out here that the
difference is known rather than conclude the analysis was careless. Equally, a specification figure is
never quietly preserved, because a document that repeats a measurement it cannot reproduce is not
evidence of anything.

None of these discrepancies changes a translation decision. Every one of them is a counting or drafting
error in a document, and the load-bearing claim each figure was supporting survives the correction —
which is worth saying plainly, because a list of seven corrections can otherwise read as though the
plan were unsound.

| # | Claim in the specification or prior documentation | Measured at this checkout | Entry |
| :-- | :---------------------------------- | :------------------------ | :---- |
| 1 | Status `'35'` for file-not-found and `'22'` for duplicate-key handling are part of the model | **Neither literal is compared anywhere** — 0 occurrences of each | DL-055, D-22 |
| 2 | Packed decimal "appears exactly once" | **Nine** declaration sites, all working-storage, none persisted | DL-015 |
| 3 | 78 program-execution steps, 9 application and 69 utility | **79** steps, 9 application and **70** utility | DL-251 |
| 4 | 1,285 non-comment lines in the validation-lookup copybook | **1,285** non-comment lines, of which **1,283** carry text | DL-252 |
| 5 | 217 `DISPLAY` statements | **215** statement-initial `DISPLAY` lines | DL-253 |
| 6 | `APPL-RESULT` referenced 223 times | **223 lines**, **229 occurrences** | DL-254 |
| 7 | Status `'23'` compared exactly once | **Three** status-test lines, **one** decision point | DL-255 |
| 8 | 888 tests = 729 unit + 134 integration + 33 end-to-end | That breakdown sums to **896**; the self-consistent form is 729 + 159 | DL-256 |
| 9 | A master batch-pipeline orchestrator class exists | **Nine job configurations and no orchestrator**; the order is an operational convention | DL-257 |

### DL-251 — The program-execution step count is 79, not 78, and the utility remainder is 70, not 69

**Decision.** The measured figures are recorded here, and **every current-facing document publishes them**:
`architecture.md` states 79 total, 9 application and 70 utility, and the module README states 79. Both point
here for the correction, and both keep the specification's 78-and-69 framing only where it is explicitly
labelled as the prior specification's rather than as a current fact. An earlier revision of this entry said
the two documents kept the specification's framing throughout; that was a description of one of them and was
never true of the README, which has published 79 since it was written.

**Measured at this checkout.** **79** program-execution steps — **76** across the 29 job members and
**3** across the 2 cataloged procedures. **Nine** invoke an application program: the report program
twice, and the posting, statement, customer-extract, interest, category-balance, card-extract and
account-extract programs once each. The remaining **70** invoke system utilities: 52 dataset-utility
steps, 8 spool-display steps, 5 sort steps, 3 no-operation steps, 1 generator step and 1 CICS
resource-definition step.

**Why the specification's figure cannot be right on its own terms.** The utility breakdown the
specification itself tabulates is 52 + 8 + 5 + 3 + 1 + 1, which sums to **70**. Its stated remainder is
69, and its stated total is 78. The document therefore disagrees with its own arithmetic by one step,
independently of any measurement — which is what makes this a drafting error rather than a difference of
counting method.

**The load-bearing claim is unaffected under either count**, and that is the point of recording it here
rather than treating it as a defect. Whether the remainder is 69 or 70, the finding that shapes the whole
batch tier is unchanged: only nine steps invoke application code, so the target carries **nine job
configuration classes** rather than one class per step, and the other seventy are absorbed by schema
migrations, container service definitions and test fixtures.

### DL-252 — The validation-lookup copybook line count differs only in whether blank lines are counted

**Measured.** The member carries **1,318** lines in total. Excluding fixed-format comment lines leaves
**1,285**. Excluding blank lines as well leaves **1,283**, because exactly two of the non-comment lines
are blank.

**Both published figures are therefore correct**, under two different and equally reasonable definitions
of "non-comment line". Neither is a mistake, and this entry exists so that a reader meeting 1,283 in one
document and 1,285 in another stops looking for an error that is not there. The definition is stated
rather than the number asserted: 1,285 non-comment lines, 1,283 of which carry text.

**The figures that actually matter are the cardinalities, and they match exactly.** The copybook declares
its permitted values as condition-name lists, and the target loads them as immutable sets whose sizes are
themselves assertions: **490** telephone area codes, being an exact partition of **410** general-purpose
plus **80** easily-recognisable codes; **56** state codes; and **240** state-and-postal-prefix
combinations. All five were counted from the value lists and all five agree with the specification. A
line count is a measure of how the data is laid out; a cardinality is a measure of what the data *is*,
and only the second can be wrong in a way that changes behaviour.

### DL-253 — The diagnostic-statement count is 215, not 217

**Measured.** **215** lines begin a `DISPLAY` statement across the 28 programs, counting only code lines
and reading only columns 8 to 72. The bare token appears 306 times, because a statement's operand list
continues across lines and because the verb also occurs inside paragraph names.

Neither figure is the specification's 217, and the gap is small enough to be a drafting slip rather than
a different method. **Either figure supports the point the number was there to make**: console display
was the estate's *only* diagnostic channel — no log framework, no severity level, no structured field, no
destination other than the job output an operator read inside the same session. That is the observation
that justifies replacing all of them with structured logging, and it does not turn on two statements.

### DL-254 — The normalised-result reference count reconciles at two figures, and both are true

**Measured.** The normalised file-status result is referenced on **223 lines** of the program tree, and
**229** times in total: six lines reference it twice.

The specification's 223 and a naive occurrence count of 229 are therefore the same measurement expressed
per line and per reference. Recorded because the two numbers look like a contradiction and are not.

**The point the figure supports is untouched.** The programs do not branch on the raw two-byte status;
they normalise it first and branch on the normalised result, which is why the target carries two levels
rather than one enumeration — see DL-056 and D-21. Whether that normalised value is read on 223 lines or
229 times, it is what the estate actually tests, and collapsing the model would still erase the
end-of-file-versus-error distinction every batch read loop depends on.

### DL-255 — Status `'23'` occupies three status-test lines and one decision point

**Measured.** The literal appears in status-test context on **three** lines across **two** members: the
disclosure-group lookup accepts either success or `'23'` and then branches on `'23'` to take the
default-group fallback, and the category-balance read likewise accepts either.

**One decision point, three lines.** The specification's "exactly once" describes the branch — there is
precisely one place where `'23'` selects a different path — while the line count is three, because two of
the sites merely admit the code alongside success rather than discriminating on it. Both readings are
accurate and they answer different questions, so both are recorded.

**Why the distinction is worth the entry.** The single decision point is the one that matters
behaviourally: it is the default-group fallback, and it is the reason the interest run is provably
exercisable on both branches from seeded data. The two accepting sites matter differently — they are why
`'23'` cannot be classified as an error outcome in the raw status vocabulary, because two operations
treat it as an ordinary result. A model built from the branch alone would misclassify it.

### DL-256 — The prior delivery's test breakdown is published only in the form that sums

**Attribution first: these are the prior delivery's measured results, not this delivery's.** They are
recorded because the prior completion report is cited as evidence of what was previously achieved, and a
cited figure that does not add up undermines the citation.

The prior completion report states **888** tests passing and breaks them down as **729** unit plus
**159** integration and end-to-end, which sums correctly. The specification restates the same breakdown
as 729 unit plus 134 integration plus 33 end-to-end, which sums to **896** rather than 888.

**Only the self-consistent form is published anywhere in this documentation set**: 729 + 159 = 888. The
eight-test overstatement is recorded here rather than propagated, and no figure from either source is
presented as a result of the current work. The gated acceptance criterion is the enforced line-coverage
floor, which the build checks on every run; a historical test count is context, not evidence.

### DL-257 — There is no master pipeline orchestrator, and the named class is prior-run drift

**The claim.** The specification names a batch-pipeline orchestrator class.

**The design.** There are **nine job configurations and no orchestrator class at all.** The pipeline
order is an **operational convention** — the sequence an operator submits, not a sequence any artefact
encodes.

**Independently re-proven at this checkout,** because "no orchestrator exists" is a claim about absence
and absence is exactly what a stale document gets wrong. Three measurements support it. There are **zero**
internal-reader submissions in any job member or cataloged procedure — the sole reference to the internal
reader anywhere under `app/` is in the online reporting program, which is the online-to-batch bridge and
not a job scheduling another job. There are **zero** job-to-job invocations across all 29 job members and
both procedures. And the only procedure-invocation target in the whole estate is a single cataloged
procedure that performs a dataset copy, invoked from three members — a shared utility step, not a
pipeline driver.

**Consequence.** Nothing in the target may be built to encode the pipeline order as though it were a
contract, because the estate never encoded it. The order is documented in `architecture.md` as the
convention it is, and each of the nine jobs is independently launchable — which is also what makes each
one independently testable.

---

## 17. Catalog metadata: the inaccuracies are recorded here rather than corrected

### DL-261 — The stale service-catalog tags are retained, not removed

**Decision.** The repository's service-catalog descriptor is updated by **addition only**. Its component
type moves from a website to a service and its tag list **gains** the Java stack. Its name, its
project-slug annotation and its declared system are **deliberately left untouched.**

**What is inaccurate and stays inaccurate.** The descriptor carries `python`, `typescript` and `web-app`
tags, none of which describes this repository, and it declares a system named for a TypeScript estate.
None of that is true of a COBOL-to-Java migration.

**Why it is not fixed.** The name, the project slug and the system are the catalog's identity and the
publication path its documentation is served from. Changing any of them would break the catalog entry
and the documentation publication rather than improve the description — a cosmetic accuracy gain paid for
with a broken integration. The authorised change is therefore addition only, and the inaccuracy is
**recorded here instead of corrected there.**

**Consequence a reader must know.** Anyone auditing the descriptor against this repository will find
tags that do not fit and a system that names the wrong stack. That mismatch is known, is deliberate, and
is not evidence that the migration was mis-catalogued. If the identity fields ever become safe to change,
this entry is the record of why they were not.

---

## 18. Prior-run open items, and what closes each

### DL-262 — The five open items the prior delivery left, and the artefact that closes each

The prior delivery recorded five open items. They are listed here **as the prior delivery's open
items**, each against the artefact that closes it, so that a reader comparing the two deliveries can see
what moved rather than inferring it.

| # | The prior delivery's open item | What closes it |
| :-- | :--------------------------- | :------------- |
| 1 | Continuous integration not started | `.github/workflows/carddemo-java-ci.yml`, scoped into the module with a working-directory default |
| 2 | Vulnerability scan pending, execution unconfirmed | `dependency-check-maven` **12.1.3** bound to `verify`, failing the build at CVSS 7.0, with its report as a gate artefact |
| 3 | Signing secret hardcoded | Eliminated completely: the production profile resolves every secret from the environment with **no fallback value**, so a missing secret fails start-up instead of binding a placeholder |
| 4 | No production profile | `application-prod.yml` |
| 5 | No transport security configured | Configured in the production profile, which consumes externally supplied keystore material |

Two of the five deserve a note, because "closed" means something stronger than "present" in both cases.

Item 2 is closed by **execution**, not by declaration. A scanner that is declared but not bound to a
phase satisfies nothing, and a scanner bound to a phase but tolerant of findings satisfies little more;
the plugin here runs in the ordinary build, scans test scope as well as compile scope, and fails on any
finding at or above the threshold. `gate-evidence.md` publishes the executed result.

Item 3 is closed by **absence of a default**, which is the part most easily got wrong. A defaulted
secret violates the no-hardcoded-credentials constraint exactly as surely as a literal one does, because
both mean a deployment can start without anyone having supplied the value. Failing closed at start-up is
the only form of this item that is genuinely closed.

Executed evidence for all eight acceptance criteria is published in `gate-evidence.md`; this section
records only the decisions and their closing artefacts.

---

## 19. User-specified rules: a verified absence, and the standards adopted instead

### DL-263 — No user-specified rules exist, and the bar is held by enterprise standards instead

**No user-specified rules were provided for this engagement.** The project's rules document was read in
full and reports that none was supplied. This was confirmed by repeated reads rather than inferred from
one, so it is a **verified absence** and not an incomplete read.

The absence is **not** licence to lower the standard of the work. With no rules to honour, the migration
is held to enterprise-standard best practice, and that is recorded here as a conscious position rather
than left as a silence. Two consequences follow and are stated so that neither reads as an omission: no
file enters scope because a rule demands it — the rule-mandated category is deliberately empty — and no
rule conflicts exist to resolve, because there is no rule.

Two of those adopted standards are discharged by documentation rather than by code, and this log is where
both land:

- **Full auditability of translation decisions.** This log, together with `traceability-matrix.md`, *is*
  that standard. It is discharged nowhere else in the repository. Every judgement where faithful
  translation and idiomatic Java diverged appears here, with both provenance identifiers cited at the
  top of the page.
- **A stated tie-break rule.** Stated explicitly under *The tie-break rule* near the top of this page:
  where faithful translation and idiomatic Java conflict, **faithful wins**, and the divergence is
  recorded here rather than resolved by taste. It is the standard that makes the others coherent,
  because it is the one that decides the hard cases.

A third is discharged partly here: **secrets never in source and never defaulted.** No credential,
password, token, signing secret or key value appears anywhere on this page, including the legacy
cleartext credential — the field width and the fact of a shared literal are described in DL-001, and the
value is not — and including the non-production field-encryption fixture key, whose purpose, size and
single-value-shared-across-three-profiles property are described in DL-103 while the value itself is left
in the profiles that bind it. The legacy demonstration credentials that appear in the repository's root README describe
the **legacy** system and are deliberately not restated here.

### One attribution boundary, stated because getting it wrong sends a reader to the wrong place

Two bodies of mandatory content could be mistaken for rules, and neither is:

- The construct-mapping table that pairs each COBOL construct with its Java equivalent and its
  preservation requirement is a **requirement** of the refactoring.
- The eight validation gates with their evidence obligations are **acceptance criteria**, and their
  executed evidence is published in `gate-evidence.md`.

Both are binding. **Neither originates in the rules document**, so neither should be sought there: a
reader who goes looking for them among the user-specified rules will find the statement that none was
provided and reasonably conclude this documentation is wrong. They are recorded in the migration
specification and in `architecture.md`, which is where they belong.

---

### DL-276 — The processing-timestamp window is a declared repository query again, and the report job calls it

> **Formerly recorded under `DL-267`.** That identifier is carried by the transport-control-character refusal entry earlier in
> this log, and an identifier a reader cannot resolve is worse than an absent one, so this decision was
> renumbered to `DL-276`. Nothing in the reasoning below changed. The renumbering follows the rule
> DL-245 sets out: the newer of two colliding entries takes a fresh identifier and says where it came
> from.

**Context.** DL-122 declared a processing-date range query on `repository/TransactionRepository`; DL-199
removed it, on the reasoning that the reporting job already applied the window itself and a second
contract for one selection is dead code. Both entries reasoned correctly about the *predicate*. Neither
weighed what removing the query did to the access path.

**What removing it cost.** The reporting stream has three steps. The first copies the whole master to a
backup generation, reproducing the legacy copy utility. The second reproduced the legacy external sort by
reading that generation back, discarding every record outside the window and ordering what survived. So
the selection was a **full scan of every stored transaction**, performed to emit one window's worth — which
is precisely the cost `idx_transaction_tran_proc_ts` was defined to avoid. The index had no reader at all:
it was created by `V2__create_indexes.sql`, asserted present by tests, and executed by nothing. The
migration plan is explicit that this alternate index becomes a query for the report job and that the index
exists because the inclusion condition would otherwise scan the whole table, so the removal was a departure
from the plan and not merely a relocation.

**Decision.** `findByProcessingDateWindowOrderedByCardNumber(String, String)` is declared on
`repository/TransactionRepository` and is the record source of the report job's second step. Its predicate
is exactly the one DL-122 derived and DL-199 preserved: a **bare** lower bound, so it reaches the index, and
a ten-character `SUBSTRING` upper bound, so a record processed on the end date is admitted rather than
dropped. Its ordering is the card number ascending, with the identifier as a second key so the result is
stable rather than dependent on the plan the server chose.

**Three properties are held deliberately, because each is a way this could have gone wrong.**

- **The unload step is untouched.** It still copies the whole master, because that generation is the legacy
  copy step's own output and is retained for the archive; it is simply no longer the second step's input.
- **The shared window predicate remains the authority.** Every record the query returns is still tested by
  `JobParameterValidators.ReportDateWindow.includes`, and a record that failed it would abort the run rather
  than be emitted. The query cannot quietly widen the window.
- **The step's own zoned-decimal comparator still decides the emitted order.** The query's ordering makes
  the result stable; the comparator — private to the report job precisely so the statement job cannot share
  it — is what the emitted sequence is. A card number that is not a zoned-decimal image is still refused.

**The excluded figure keeps its meaning.** The legacy sort reported how many records it discarded. Records
outside the window are no longer read one at a time, so the figure is taken as the master's row count less
the records emitted, which is the same number for the same run.

**Evidence.** `repository/TransactionRepositoryIT` now calls the production method for all six window
assertions rather than a statement it wrote itself, and additionally asserts that the statement whose
access plan it inspects selects exactly what the production query selects, in the same order — so the plan
is evidence about the shipped predicate. The four goldens still compare byte for byte in
`e2e/BatchPipelineE2ETest`, which is what establishes that moving the selection changed no emitted byte of
the 133-byte report.

*Embodied in:* `repository/TransactionRepository.java`, `batch/TransactionReportJobConfig.java`.
*Cited by:* `src/test/java/com/carddemo/repository/TransactionRepositoryIT.java`,
`src/test/java/com/carddemo/batch/TransactionReportJobConfigTest.java`,
`src/test/java/com/carddemo/batch/TransactionReportJobConfigIT.java`.
*Supersedes on location, not reasoning:* DL-199, DL-122.

### DL-277 — The confirmed bill payment holds the account row exclusively, and the transaction insert is durable on its own

> **Formerly recorded under `DL-268`.** That identifier is carried by the sign-on refusal-message and attempt-bound entry earlier in
> this log, and an identifier a reader cannot resolve is worse than an absent one, so this decision was
> renumbered to `DL-277`. Nothing in the reasoning below changed. The renumbering follows the rule
> DL-245 sets out: the newer of two colliding entries takes a fresh identifier and says where it came
> from.

**Context.** Two properties of the legacy bill-payment span are stated by the file definitions rather than by
the program text, and both were lost in the first relational translation.

`app/csd/CARDDEMO.CSD` defines every application file `READINTEG(UNCOMMITTED) RECOVERY(NONE) JOURNAL(NO)`
with `UPDATEMODEL(LOCKING)`. `app/cbl/COBIL00C.cbl` reads the account at line 343 with `UPDATE` and rewrites
it at line 235, and between those two points the record is held exclusively. That gives the span two
guarantees:

1. **Two operators confirming the same account cannot interleave.** The second task waited at its own
   `READ ... UPDATE`; when the first released the record the second read a settled balance and took the
   nothing-to-pay arm at lines 197 to 206. The legacy therefore posted exactly *one* transaction however many
   times the payment was submitted — and it did so by never minting a second one, not by rolling one back.
2. **A `REWRITE` that fails does not undo a `WRITE` that succeeded.** With no recovery and no journal a
   completed record write is durable at once, and the source performs lines 234 and 235 whether or not the
   write at line 233 succeeded, testing no flag between them. So a failed rewrite leaves the transaction
   stored, the account unsettled, and the operator told the account could not be updated.

**What the first translation did instead, and what it cost.** Both writes were placed in one unit of work and
the account was read unlocked, with a version comparison standing in for the hold. The consequences were not
equivalent:

- A concurrent submission was answered with `OptimisticLockConflictException` — a failure the legacy has no
  arm for, in place of the legacy's own "You have nothing to pay..." message.
- A rewrite that failed for any reason rolled the unit back and **discarded the transaction the same turn had
  inserted**. No legacy mechanism does that; the transaction file cannot be rolled back at all.
- The window between the unlocked read and the versioned write was real: two turns could both read a payable
  balance, and the loser's whole unit was abandoned rather than its payment being declined.

**Decision.** Reproduce the hold, and separate the durability.

- The confirmed span opens one unit of work whose **first statement** re-reads the account row
  `FOR UPDATE`. Everything the writes depend on — the amount, the balance the settled figure is computed
  from, and the row the rewrite stores — is read under the lock. The balance test of lines 198 and 199 is
  re-evaluated on what the lock granted, so a second operator reaches the nothing-to-pay arm exactly as the
  legacy's second task did.
- The row is read twice, and that is a consequence of the platform rather than a deviation. A relational read
  outside a unit of work holds nothing, so the display read that serves a non-confirming turn cannot carry
  the exclusion; the exclusion is obtained inside the unit that performs the writes. The account-read
  paragraph's arms are extracted so that both performances resolve identically, and a row that has gone
  between the two reaches that paragraph's not-found arm.
- **The transaction insert runs in a unit of its own**, nested inside the unit holding the row. It is
  therefore committed before the rewrite is attempted and survives a rewrite that rolls back — which is the
  legacy behaviour, not a relaxation of it. Its refusal is classified in place, so the outer unit stays
  healthy and the unconditional lines 234 and 235 still run.
- The version comparison is gone from the rewrite. It is not a weakening: the row is locked exclusively from
  the unit's first statement, so a version test could only ever pass. `@Version` still increments on the
  store, which is what a reader outside the unit observes, and a provider-reported conflict is still
  translated — a translation that exists only while it is reachable is one that stops existing quietly.
- The rewrite paragraph's own not-found arm at lines 390 to 395 is preserved and is unreachable, for the same
  reason the legacy's was: while the record is held it cannot be deleted. A vanished row is now detected at
  the held read, before anything is minted, which is strictly better than detecting it at the rewrite.

**The allocation lock is transaction-mandatory.** `pg_advisory_xact_lock` is released at the end of the
transaction that took it. Taken in autocommit it is released as the statement completes and serialises
nothing while appearing to succeed — the one failure mode this operation cannot be allowed to have, because
it produces no error of its own. `lockIdentifierAllocation` therefore declares
`Propagation.MANDATORY` *and* checks for an active transaction in the method body. The two are not
redundant: the propagation is enforced by the repository proxy, the check by the method, so a caller that
obtained the fragment directly, or any future wiring in which the proxy is absent, is refused as well.

**Evidence, and why it is behavioural.** `service/BillPaymentConcurrencyIT` runs the shipped service from two
threads against a real PostgreSQL 16 server and observes eleven facts, among them: two concurrent confirmed
turns against one account post exactly one transaction and apply exactly one debit, with the loser receiving
the legacy's own nothing-to-pay text; a second caller cannot complete while the row is held, and completes
once it is released; two turns against different accounts both complete, because the hold is on the row and
not the table; a contender acquires the allocation lock only after the holder's unit released it; a
rolled-back unit releases the lock and consumes no identifier; two concurrent allocators mint distinct
successors of the same stored maximum with neither refused; a real turn blocks on the lock another
participant holds; the lock is refused outside a transaction, at the proxy and at the fragment; and a forced
rewrite failure leaves the transaction row committed with the account unsettled.

`repository/IdentifierAllocationLockAuditTest` is retained but retargeted to the one thing no behavioural
test can establish — that no *unenrolled* production file reads the stored maximum, and that each enrolled
one carries the bounded re-read, the flush and the unit of work. A test cannot exercise a call that was never
written.

*Embodied in:* `service/BillPaymentService.java`, `repository/TransactionInsertRepository.java`,
`repository/TransactionInsertRepositoryImpl.java`.
*Cited by:* `src/test/java/com/carddemo/service/BillPaymentConcurrencyIT.java`,
`src/test/java/com/carddemo/service/BillPaymentServiceTest.java`,
`src/test/java/com/carddemo/repository/IdentifierAllocationLockAuditTest.java`.
*Supersedes:* the shared-unit reasoning previously recorded for this span.

---

### DL-278 — A declared character bound is not a byte bound, so every fixed-width text column carries an encoding constraint

> **Formerly recorded under `DL-269`.** That identifier is carried by the staged-path trust and scoped-cleanup entry earlier in
> this log, and an identifier a reader cannot resolve is worse than an absent one, so this decision was
> renumbered to `DL-278`. Nothing in the reasoning below changed. The renumbering follows the rule
> DL-245 sets out: the newer of two colliding entries takes a fresh identifier and says where it came
> from.

**Context.** Eleven tables hold the relational form of eleven fixed-width record layouts, and 74 of their
columns are text fields of those images. Their widths are contractual: the four delivered output formats at
80, 100, 133 and 430 bytes are compared byte for byte, `app/jcl/TRANREPT.jcl` addresses `TRAN-CARD-NUM` at
1-based position 263 and `TRAN-PROC-DT` at 305, and `util/FixedWidthFieldReader` measures and encodes every
field as **US-ASCII**, refusing a character US-ASCII cannot represent rather than substituting one.

`VARCHAR(n)` does not express that. Neither does `information_schema.character_maximum_length`, and neither
does `char_length`. All three count **characters**, and under UTF8 one character occupies up to four bytes.
So `ACME` and `ACMÉ` both satisfy `VARCHAR(50)` and both report a length of 4, while the record images they
belong to are 4 and 5 bytes wide.

**What that cost, before this decision.** The schema admitted a value the emitting layer refuses. A
multibyte value passed the declared bound, passed every character-count assertion in the repository
integration tests, and was then **unwritable** by the fixed-width layer that has to emit it — so the failure
surfaced at output time, on a row accepted long before, in a job whose output is a contractual artefact.
Three integration tests additionally described character metadata as byte evidence, which is what let the
gap look closed.

**Decision.** One named constraint per table, `ck_<table>_single_byte_text`, requiring
`octet_length(c) = char_length(c)` for every text column `c`.

- **The predicate is an equality, not a bound.** `octet_length(c) <= n` would bound the bytes while still
  admitting a value whose character count and byte count disagree — and disagreement is the thing that
  breaks offset arithmetic. The equality is exactly the US-ASCII repertoire rule the fixed-width layer
  enforces, and it makes the declared character bound a byte bound as a consequence: n characters that each
  encode to one byte is n bytes.
- **It lives in `V1`, not a new migration.** Production pins `spring.flyway.target: 2`, so a `V5` would
  never be applied there. The constraint belongs to the table definition in any case.
- **The three widened columns are included, not exempted.** `customer.cust_ssn` and
  `customer.govt_issued_id` hold an ENC1 envelope whose payload is Base64, and `user_security.sec_usr_pwd`
  holds a BCrypt digest; all three alphabets are US-ASCII by construction. The constraint bounds no length,
  so their widened `VARCHAR` declarations are untouched. `cust_ssn` stays nullable because a `CHECK` passes
  on NULL.
- **`daily_transaction` is included, and that is not an exception to its deliberate absence of foreign
  keys.** Those are withheld so that invalid **content** reaches application validation and produces the
  contractual 430-byte reject record. This constraint is about the record **image**: a reject record is the
  350-byte source image plus an 80-byte trailer, so a landed row that cannot be re-encoded to 350 bytes
  could not be rejected either — the reject file would fail rather than the posting. Refusing such a row on
  arrival is what keeps every landed row rejectable, and it removes no reject case.
- **`customer` keeps no content edit.** The FICO 300-to-850 range remains screen-level validation belonging
  to the account-update path, and `middle_name` and `addr_line_2` remain unvalidated as the legacy leaves
  them. A repertoire rule is not a content edit, and the tests that forbid content edits on those two tables
  now say so explicitly rather than counting constraints.

**One test could no longer be written, and its property moved rather than being dropped.**
`api/CardControllerIT` discriminated the ASCII table fold from a library case mapping by **storing**
`MARÉA` and submitting `maréa`: a table fold leaves the stored `É` alone so the comparison sees a change,
while a library mapping would fold both to the same string and report no change. That discrimination
requires a persisted non-ASCII embossed name — a row the record image cannot carry and which the schema now
refuses. The property is asserted instead in `util/CobolStringUtilsTest`, over `asciiUpperFold` itself,
across the whole ASCII range plus the sharp s, the dotless i, the n with tilde and the e with acute, with
the table's result compared against the library method's for each. That is a stronger proof than the
controller test was, and it is a proof about the fold rather than about a row nothing can store. The
controller specification now asserts what remains reachable: that the value is refused by the schema, naming
the constraint, and that a submitted non-ASCII name is faulted by the alphabetic edit.

**Named CHECK constraints now have negative proof, which they previously did not.** Nine key-shape
constraints were declared in `V1`, and the entities enforce the same rules before a write — so every
specification that went through the entity layer passed whether or not the database guard existed, and
deleting a guard would have broken nothing observable. `repository/SchemaConstraintNegativeProofIT` exercises
all nine through **raw JDBC**, which is the shape of the writer they exist to catch: a bulk load, a migration
script, or a future writer that never constructs a record image. Each assertion checks SQLSTATE `23514` and
the **exact constraint name** PostgreSQL reports, because asserting only that something was thrown would
pass with the constraint deleted and a foreign key refusing the row instead.

**Evidence.** `repository/SchemaConstraintNegativeProofIT`: 38 tests. Eleven fixtures insert cleanly and are
then each perturbed with one two-byte character at the same character count — so the declared bound and every
character-count check still pass — and the server refuses each, naming that table's own constraint. The
coverage is closed over the **catalogue** rather than over a list: every character column of every
application table is read from `information_schema` and required to appear on both sides of an octet-length
equality in its table's constraint definition, with the count asserted at 74, so a column added later cannot
escape silently. The seeded rows are asserted to satisfy the rule, so the constraint describes the delivered
data rather than contradicting it. And the reconciliation is asserted directly: the value the schema refuses
is exactly the value `FixedWidthFieldReader.encodedLength` refuses to measure.

*Embodied in:* `src/main/resources/db/migration/V1__create_schema.sql`.
*Cited by:* `src/test/java/com/carddemo/repository/SchemaConstraintNegativeProofIT.java`,
`src/test/java/com/carddemo/repository/DailyTransactionRepositoryIT.java`,
`src/test/java/com/carddemo/repository/CardCrossReferenceRepositoryIT.java`,
`src/test/java/com/carddemo/repository/CustomerRepositoryIT.java`,
`src/test/java/com/carddemo/api/CardControllerIT.java`,
`src/test/java/com/carddemo/util/CobolStringUtilsTest.java`.

---

---

### DL-279 — A reject expectation may not be read out of the enumeration it checks, and two of the five reject arms are reachable only through a seam

> **Formerly recorded under `DL-270`.** That identifier is carried by the business-surface authority allow-list entry earlier in
> this log, and an identifier a reader cannot resolve is worse than an absent one, so this decision was
> renumbered to `DL-279`. Nothing in the reasoning below changed. The renumbering follows the rule
> DL-245 sets out: the newer of two colliding entries takes a fresh identifier and says where it came
> from.

The daily posting program refuses a record by writing a four-hundred-and-thirty byte reject record:
the three-hundred-and-fifty byte source image copied verbatim, then a four-digit numeric reason code,
then a seventy-six character description. A downstream consumer reads those two fields
**positionally**, so their values are an external contract in exactly the sense the preservation
boundary uses the term.

Every expectation about those two fields used to be produced by reading
`com.carddemo.domain.enums.RejectReason` — `getReasonCode()` for the digits and `getDescription()`
for the text. That made each comparison a comparison of the implementation against itself. Had the
enumeration recorded `104` where the legacy source sets `103`, the expectation would have said `104`
too, the byte comparison would have passed, and the delivered file would have carried a reason code
no consumer knows how to read.

**A comparison of sets is not sufficient either, and that was measured rather than assumed.** Two of
the five reasons carry the *identical* description under *different* codes: the account read failing
and the account rewrite failing both move `ACCOUNT RECORD NOT FOUND`, and only the four digits in
front distinguish them. Transposing those two codes in the enumeration was tried against a
set-and-description oracle: the set of codes was unchanged, both descriptions were unchanged, every
by-code lookup stayed internally consistent, and every assertion passed. The defect is not cosmetic —
the two reasons are set at different sites and routed differently, one to a reject record and one to a
posted record — so the transposition would have refused the wrong record and emitted the wrong digits.

`LegacyRejectReasons` is therefore an **oracle**: five literal pairs, each transcribed by hand from
the statement that sets it, each citing its source line, importing nothing from `com.carddemo.domain`
and nothing from `com.carddemo.batch`. Each entry additionally names the shipped constant that must
carry it. Naming the constant is what closes the transposition hole, and it does not compromise the
table's independence: no expected *value* crosses the boundary, only the mapping from a role to a
name, which is the same statement the traceability matrix makes and the statement a value-only
comparison cannot make. `RejectReasonOracleTest` asserts the enumeration against that table — in that
direction, because that is the direction that proves something — and was confirmed to fail under three
distinct mutations: the transposition above, a description drift, and a code drift.

**The two arms no persisted state can reach.** Reject code `0101` arises when the account read reports
no row. In the migrated schema a cross-reference cannot name an account that does not exist, because
`V2__create_indexes.sql` puts a foreign key there, so the dangling row that reason needs cannot be
inserted. That reasoning about the *state* is correct, and it does not follow that the *code path* is
unreachable — a read can report nothing for reasons other than an absent row. The distinction had been
lost, and with it the evidence: `TestDataFactory.dailyTransactionRejectedBy` handed back a record
naming an orphaned card and instructed the caller to register a cross-reference the server refuses, so
no caller could follow the recipe and no test ever saw those four digits leave the system. Reject code
`0109` was in the same position for a different reason: it is inert by design, and its inertness was
asserted only by *absence* — no reject record in some other run carried it — which is equally
consistent with the arm never being entered at all, which is what was happening.

Both are now entered positively, through the narrowest seam that reaches them without inventing
persisted state the record image cannot carry: `RejectReasonArmsIT` spies the account repository and
makes one method report what the legacy file's `INVALID KEY` arm reported. The schema, the foreign key
and the row all stay intact; the reader, the validation cascade, the posting mainline, the reject
writer and the generation store are the shipped article on a real PostgreSQL server. `0101` produces a
real four-hundred-and-thirty byte reject record and posts nothing; `0109` produces **no** reject record
and the transaction posts anyway, and the run counts it under the `POSTED` outcome — which is the
whole of its inertness, stated positively in both halves.

`dailyTransactionRejectedBy` now **refuses** those two reasons with a message naming the foreign key
and the seam, rather than returning a fixture that cannot do what its name says. A refusal is
discovered at the call site; a mis-shaped fixture is discovered only by a test that never goes red.

**What is deliberately left alone.** The unit tests of the enumeration itself
(`RejectReasonTest`, `RejectReasonBaselineTest`, `RejectReasonSecurityTest`,
`ValueSelectorEnumsTest`) and of the writer's padding behaviour
(`RejectRecordWriterTest`, `RejectRecordWriterContractTest`, `TransactionValidationProcessorTest`)
read the enumeration as the *subject* and compare it against hand-typed literals. That is the correct
direction and is not the defect above. The writer's tests establish that it zero-fills four digits and
blank-pads seventy-six characters *whatever* the reason carries; three independent places now tie the
production assembler's whole trailer to the transcription for all five reasons, which is what composes
those two statements into the byte contract.

**The byte-parity gate is not weakened to carry the extra arms.** Gate 1 processes the delivered input
**unmodified**, and the delivered input reaches exactly one reason. No synthetic path is injected into
it. Instead the gate's own trap now derives the four absent codes from the transcription rather than
from four hand-typed literals — a bare `doesNotContain` over literals passes vacuously the moment a
code drifts, because the drifted code is not among the literals — and additionally measures the trailer
contract for all five through the production assembler, so a drift in any of the five fails the gate
rather than only the one in use.

*Embodied in:* `src/test/java/com/carddemo/support/LegacyRejectReasons.java`,
`src/test/java/com/carddemo/batch/RejectReasonArmsIT.java`,
`src/test/java/com/carddemo/domain/enums/RejectReasonOracleTest.java`,
`src/test/java/com/carddemo/support/TestDataFactory.java`.
*Cited by:* `src/test/java/com/carddemo/batch/PostTransactionJobConfigIT.java`,
`src/test/java/com/carddemo/repository/DailyTransactionRepositoryIT.java`,
`src/test/java/com/carddemo/e2e/BatchPipelineE2ETest.java`,
`src/test/java/com/carddemo/support/ExpectedOutputFixtureContractTest.java`.

---

### DL-280 — The credential fixture carries the delivered value, because acceptance is the only property that proves a shipped digest is the right one

> **Formerly recorded under `DL-271`.** That identifier is carried by the single disclosure-authority derivation entry earlier in
> this log, and an identifier a reader cannot resolve is worse than an absent one, so this decision was
> renumbered to `DL-280`. Nothing in the reasoning below changed. The renumbering follows the rule
> DL-245 sets out: the newer of two colliding entries takes a fresh identifier and says where it came
> from.

`V4__seed_user_security.sql` ships ten BCrypt digests. Their correctness is a single claim: each accepts
the credential the delivered provisioning records carry. Before this change no committed test made that
claim, and the reason was a deliberate design decision that turned out to be self-defeating.

The decision was that a module which digests a credential should not hold the credential, so the
class-path fixture's eight-byte credential window carried a **synthetic** stand-in instead of the
delivered value. Only two questions were then answerable about a seeded digest — its **shape** and its
**refusal** of a value it was not derived from — and the code documented that limitation honestly and at
length.

**The limitation was not benign, and its cost was measured rather than argued.** Shape, refusal,
distinctness and "not equal to any visible field" are *every one of them* satisfied by a digest of any
value whatsoever. A wrong literal in the seed produces sixty characters, a recognised version marker,
the module's cost factor, ten independent salts, and a refusal of the probe — and admits nobody. To
confirm this rather than assert it, the credential was perturbed by a **single character** and the gate
suite re-run: the acceptance assertion failed, and every other assertion in the same nest passed. The
seeded sign-on would have stopped working silently, with a green build.

The damage extended into the end-to-end tier, where it was worse. Because no delivered digest could
accept anything the module held, `OnlineTransactionE2ETest.admitSignOn` **installed a digest of its own
onto the delivered record** before sending the request. Every admitted sign-on in that class was
therefore authenticating against a value the test had just written, so the property under discussion was
not merely unasserted — it was actively bypassed by the one test best placed to observe it.

**The resolution: the fixture carries what the delivered records carry.** This is what the plan already
specifies — the input fixtures are *derived from* the delivered reference data, and the credential seed
is designated as reproducing the ten in-stream provisioning records. A fabricated window made a Gate 4
named artefact silently disagree with the provenance it documents, which is a fidelity defect in its own
right, quite apart from the evidence it destroyed. The fixture was rebuilt by reading the window out of
the provisioning member's own layout position, and now reproduces all fifty-seven significant columns of
each delivered record.

Nor is this the hardcoded credential the constraint forbids. That constraint governs the production
configuration, which resolves every secret from the environment with no fallback; the credential seed is
scoped to the local and test profiles only; and the value is the published sample credential of a public
demonstration estate.

**What did not change is the handling discipline.** The value appears in no Java source in this module —
not in a literal, a constant, a comment, a method name, a display name or a diagnostic. It lives in one
test resource, is read by offset at run time into a character array, is used, and is overwritten. Where
a specification needs an exact pin on the window it compares a **SHA-256 fingerprint**, which is exact,
cannot be inverted, and is safe to print on failure; where it needs a per-record comparison it asserts a
**boolean**, because a failing equality renders both operands and one of them is the credential.

**Three consequences had to be handled, and each is a real behavioural detail rather than a mechanical
edit.**

- *Fold invariance became load-bearing.* The sign-on transaction upper-cases a submitted credential
  before comparing anything, through a twenty-six character ASCII substitution table. The delivered
  value contains no lower-case character, so folding it is the identity and matching it directly is
  matching exactly what the boundary compares. That is now asserted rather than assumed, because a
  window that folded to something else would make every acceptance check quietly weaker than it reads.
- *The fold's unconditionality can no longer be demonstrated with the credential.* It was demonstrated
  by storing a digest of the credential's **unfolded** form and observing that submitting that exact
  value was refused — which worked only because the synthetic window was mixed-case. For an all-upper
  value the folded and unfolded digests are the same digest, so that identity would now be **admitted**
  and the demonstration would have inverted into its opposite while still looking like a passing test.
  It is now demonstrated on a mixed-case probe that is not a credential, as a **pair** of identities
  differing in nothing but which form of the probe their digest was made from: one submission admits one
  and refuses the other, so the refusal is attributable to the fold and to nothing else.
- *One redaction test was refusing the redaction rather than the disclosure.* `SignOnRequest.toString()`
  renders `password=***REDACTED***`, and the delivered credential's lower-cased form is the same eight
  letters that spell the name of the property being redacted — so a case-insensitive
  `doesNotContain(credential)` probe reported the **label** as a leak. The whole rendering is now pinned
  by equality, which is strictly stronger (it catches the value in any position, a leaked prefix or
  length, and the placeholder itself being changed) and is immune to the collision.

*Embodied in:* `src/test/resources/fixtures/input/usrsec.txt`,
`src/test/java/com/carddemo/support/TestDataFactory.java`.
*Cited by:* `src/test/java/com/carddemo/e2e/GateVerificationTest.java`,
`src/test/java/com/carddemo/e2e/OnlineTransactionE2ETest.java`,
`src/test/java/com/carddemo/repository/UserSecurityRepositoryIT.java`,
`src/test/java/com/carddemo/api/AuthControllerIT.java`,
`src/test/java/com/carddemo/support/FixtureContractTest.java`.

---

### DL-281 — A pipeline run is a fixture, not a test, because a test that produces state its neighbours consume is not a test of anything on its own

> **Formerly recorded under `DL-272`.** That identifier is carried by the report-bridge filter-chain proof entry earlier in
> this log, and an identifier a reader cannot resolve is worse than an absent one, so this decision was
> renumbered to `DL-281`. Nothing in the reasoning below changed. The renumbering follows the rule
> DL-245 sets out: the newer of two colliding entries takes a fresh identifier and says where it came
> from.

The Gate 1, 3 and 4 end-to-end specification drives the delivered batch pipeline once and then makes
forty-two separate claims about what it produced. Before this change the run itself was **one of
those forty-two**: an ordered method executed the six jobs and stored the produced artefacts, the job
executions and the comparison rows in **static mutable collections**, and the remaining forty-one
read them back. The class therefore only meant anything when executed whole, in declaration order, in
one process.

That is a real cost rather than a stylistic one, and it has three distinct parts.

- *No individual claim could be investigated.* Selecting one failing comparison to iterate on
  produced not that comparison's verdict but `the pipeline test must have run first and captured
  AWS.M2.CARDDEMO.STATEMNT.HTML` — a failure about the harness, from a test naming a parity property.
  This was confirmed against the pre-change file rather than argued: the identical single-method
  selection that now passes returned one failure carrying exactly that message.
- *A pass could be conditional on a sibling and still read as unconditional.* Any assertion consuming
  accumulated state is really asserting a conjunction of itself and every method that fed it, and
  nothing in the assertion says so.
- *Evidence publication was selection-dependent.* The Gate 1, 3 and 4 evidence is emitted from the
  accumulated state at teardown, so a partial selection published a partial record that still looked
  complete.

**A second ordering dependency existed that the review did not name, and it was the more instructive
one.** The comparison-report assertion required the accumulated comparison list to hold four rows — a
condition satisfied not by the run but by *the four golden comparison tests having already
executed*. So the class did not have one producer and forty-one consumers; it had a chain in which
consumers were themselves producers for later consumers. Fixing only the reported dependency would
have left the class still order-bound, and still green, which is precisely how this class of defect
survives review. The four comparison rows are now built by the fixture from the declared contract
table, and the report assertion holds when it is the only method selected.

**The resolution: the run becomes fixture setup that publishes one immutable snapshot.** The class
holds its instance across methods, drives the pipeline once in instance setup, and hands every method
the same snapshot value object. The forty-two claims are unchanged in substance, and every one of
them is now independently selectable.

Four details of that resolution are load-bearing.

- *The snapshot is a class and deliberately not a record.* Its most important component is a map of
  produced artefacts to their bytes. A record's generated accessor returns the component, so callers
  would receive the map and — because an unmodifiable map does not copy the arrays inside it — the
  `byte[]` values would remain writable by any method that asked for them. One test mutating a
  captured artefact would corrupt every later comparison, which is the same shared-mutable-state
  defect being removed, merely relocated. The map is never exposed; artefacts are reached only
  through an accessor that returns a `clone()`.
- *Setup observes and records; tests assert.* Not one assertion was moved into setup, though doing so
  would have been shorter. An assertion that fails during setup is reported as an initialisation
  error against the whole class, so a single wrong byte in one golden would present as forty-two
  errors and name none of the behaviours. Keeping every claim in its own method keeps the failure
  attributable to the property it belongs to.
- *Three claims are about state that no longer exists when the tests run, and they are why the
  snapshot records inputs as well as outputs.* The migrated schema, the eight seeded reference
  artefacts and the seeded category balances all describe the server **as the pipeline found it**.
  Moving the run into setup makes the live server show the *finished* state, so asserting these live
  would assert the outcome while claiming to describe the starting point — an inversion that passes.
  Confirmed rather than predicted: the first run after the restructuring failed exactly these, before
  they were pointed at values the fixture captured **before** the first job launched.
- *Method ordering is retained, and now decides nothing.* The ordering annotation stays because it
  makes a whole-class report read in pipeline sequence. No assertion depends on it, which is the
  property that was missing, and is what makes the ordering a presentational choice rather than a
  correctness precondition.

**Removing the accumulator broke something the accumulator had been quietly holding together, and this
is the part worth carrying forward.** The class launches a seventh job — the orphan extract, which no
job stream invokes and which this tree therefore launches by name or nothing does. Its cleanup goes
through the framework metadata store, which every integration test in the run shares, and this class
deletes **only the instance identifiers it recorded**, by design, so that a row another specification
created is never touched. The old accumulator recorded every launch as a side effect, including that
one. Once the pipeline's launches were recorded deliberately on the snapshot instead, the probe's
launch — still sitting in a test method — was recorded nowhere, so its metadata row survived teardown
and the specification asserting the orphan has never been launched failed against it.

The failure is worth reading precisely, because the diagnosis is not the obvious one: nothing about
the pipeline changed, no assertion about the pipeline was wrong, and the test that broke belongs to a
different class. What changed is that a launch stopped being accounted for. The fix is not to delete
by job name — that would reach into rows this class does not own, which is exactly what the deletion
rule exists to prevent — but to record the probe on the snapshot alongside the pipeline and clean up
everything the class launched. The probe is held apart from the six, because one assertion counts the
pipeline and the probe is not part of it; and the orphan test now asserts from the snapshot, so it too
is independently selectable and launches nothing of its own. Confirmed by running the two classes
together in **both** orders, and the orphan assertion alone.

*Verified by:* the full class passing whole; a late-ordered method that formerly required four
siblings passing alone; a single golden comparison passing alone where the pre-change file fails the
same selection; and a three-method selection spanning pre-run and post-run claims, chosen out of
declaration order, passing together.

*Embodied in:* `src/test/java/com/carddemo/e2e/BatchPipelineE2ETest.java`.

---

### DL-282 — A forwarded header may set the caller address only from a proxy the deployment names, because the abuse governor counts by it

The sign-on governor keeps two allowances. One is per identity and catches a run of secrets driven at a
single identifier. The other is per **caller address**, and it is the only one that can catch an
enumeration sweep: a sweep never repeats an identifier, so the per-identity allowance never accumulates.
The caller address is therefore a security-relevant input, and the boundary reads it from the servlet
request rather than from any header for exactly that reason.

**That was not sufficient, and the reason is a setting rather than a line of code.** The production
profile selected `server.forward-headers-strategy: framework`, which registers a filter that rewrites the
request's own address from `X-Forwarded-For` or `Forwarded` **unconditionally** — the value the caller
sends becomes the value `getRemoteAddr()` returns. Transport security terminates in this process, so a
client can reach it directly, and a direct client that rotates one header value per attempt presents every
attempt as a new source. Combined with one fresh identifier per attempt, *neither* allowance ever
accumulates: the sweep runs unbounded while both protections read as configured. The controller cannot
detect this, because by the time it runs there is nothing left to distinguish a rewritten address from a
real one.

**The resolution: the trusted-proxy-aware form of the same feature.** Production now selects
`server.forward-headers-strategy: native`, which uses the container's own remote-address valve. That valve
consults the **immediate peer** before it believes anything: a peer matching the configured allow-list may
speak for a client, and a peer that does not is treated as the client itself and its headers are ignored
for address attribution. Behind a named balancer, per-client attribution and generated URLs work exactly as
they did; reached directly, a forwarded header is inert.

Three properties of the resolution are load-bearing.

- *Withdrawing forwarded-header support was rejected.* It would have satisfied the security requirement and
  introduced an availability defect: behind a balancer every request arrives from the balancer's address, so
  ignoring its headers collapses every client into one source subject and one abusive client spends the
  allowance for everyone behind it. The trusted-peer specification exists to fail if this is ever done.
- *The allow-list defaults to loopback only, and that default is fail-closed rather than convenient.* The
  framework's own default is a broad private-range pattern, which would trust a forwarded header from
  anything inside a private network — including, in a deployment behind NAT or a service mesh, from ordinary
  clients. The narrower default means a deployment that terminates TLS at a balancer **must** name that
  balancer's range in `CARDDEMO_TRUSTED_PROXIES`. Until it does, every request is attributed to the
  balancer: sign-on stays correct and stays protected, but the source allowance is shared by everyone behind
  it. That trade-off is stated in the profile's own commentary rather than left to be discovered, and it is
  the safe direction of the two — a shared allowance degrades availability for one *misconfigured*
  deployment, whereas a caller-chosen key removes the protection for *every* deployment.
- *The allow-list carries a default while every secret in that profile still carries none.* A proxy range is
  not a credential; defaulting it does not put a usable secret in the repository, and the guard that refuses
  to start on an unsupplied secret is unaffected because it watches bare references only. This is a
  deliberate, narrow exception to the no-fallback rule and is the reason it is written down here.

**Why the proof needed a bound port.** The difference between the two strategies is invisible to a unit
test, to the controller and to a mock servlet environment: all three see whatever address they are handed.
It is observable only over a real connection to a real container, and only through a value that varies with
the address. Both specifications therefore drive the published sign-on screen and read the message it
answers with, because the governor's whole observable effect on a caller is that a further attempt is
refused *without a credential read*: an unknown identifier answers the not-found text while its allowance
holds and the cannot-verify text once the allowance is spent. Every attempt uses a different unknown
identifier, so each identity allowance stays at one failure and the source allowance is the only thing that
can change the answer.

*Verified by:* `api.SignOnSourceAttributionUntrustedProxyIT`, in which three attempts from the peer — each
claiming a different forwarded client and each naming a different unknown identifier — spend the allowance,
and a fourth attempt claiming a fourth forwarded client, plus a fifth sending no forwarded header at all,
are both refused; `api.SignOnSourceAttributionTrustedProxyIT`, in which the peer *is* named and two
different forwarded clients are counted separately while the exhausted one stays refused; and
`config.ConfigurationProfileBaselineTest`, which holds the shipped production document to both halves of the
mechanism — the strategy and the allow-list — so the document and the runtime proof cannot drift apart.

*Embodied in:* `src/main/resources/application-prod.yml` and the source-attribution comment in
`src/main/java/com/carddemo/api/AuthController.java`.

---

### DL-283 — The unwired-paragraph marker covers sixteen rows across four members, not six in one, and the split is asserted per member

The traceability matrix marks a row `¶` when the paragraph is translated, its Java method exists, and **no
delivered call site reaches it**. The marker exists because a covering test cannot reach such a method by
driving an entry point, so the row carries an extra obligation: its test must name the method, and for a
paragraph head must actually call it.

**The marker was declared for six rows and the property held for sixteen.** The six were the account-update
member's three character-class edits and their exits. Ten further rows had exactly the same property and no
marker, and a census of every `PERFORM` and every Java call site in their own classes is what found them:

| Member | Rows | Paragraphs |
| --- | :-: | --- |
| `COACTVWC` | 2 | `SEND-LONG-TEXT` at 896 and its exit at 907. Its three would-be call sites, at 768, 818 and 867, are commented out in the member, as is the statement that would have filled the field it transmits. |
| `COCRDLIC` | 4 | `SEND-PLAIN-TEXT` at 1422 and `SEND-LONG-TEXT` at 1441 with their exits. The member's own comments at 1420 and at 1438 to 1439 mark both as diagnostic and not for production use. |
| `COCRDSLC` | 4 | `9150-GETCARD-BYACCT` at 779 and its exit at 810 — the account-keyed read over the non-unique alternate index, which the read driver at 726 does not perform — and `SEND-LONG-TEXT` at 820 with its exit at 831. |

Each of those decisions was already recorded on the Java method itself, in the words of the member that
declares the paragraph. What was missing was the marker, and therefore the obligation: the ten rows named
covering tests that **called their heads but named none of their exits**, so a reader following an exit row
arrived at a suite that never mentioned it. Marking them is what turns the decision into a checked claim.

**Two rows in each pair remain deliberately different, and this is the part that is easy to get wrong.** A
head must be *invoked* by its covering test; an exit must only be *named* by it. An exit paragraph is the
`EXIT.` statement of its range and is reachable only from the head above it, which calls it on every arm, so
proving the arms proves the exit. Adding an invocation of an empty terminator would be the suite calling a
method it had chosen to expose rather than evidence about the delivered code — fabricated coverage, which is
the opposite of what the marker is for.

**Why the per-member split is asserted and not just the total.** A total of sixteen is satisfied by any
distribution of sixteen rows. The defect being closed here was precisely a distribution error — the marker
believed to belong to one member while three others carried it — so the gate holds the population to the
exact per-member figures above. Wiring one of these paragraphs in, dropping one, or discovering a further
one fails the gate and is re-decided here, rather than silently changing the marker distribution. The three
sibling rows that are *not* marked make the same point from the other side: the plain-text senders in the
account-view and card-detail members **are** performed, and marking them would be as wrong as leaving their
long-text siblings unmarked.

*Verified by:* `e2e.GateVerificationTest`, which asserts the marker total, the per-member split, that every
`¶` row's covering test names its method, and that every head is actually invoked; and the four covering
tests themselves — `service.AccountUpdateServiceTest`, `service.AccountViewServiceTest`,
`service.CardListServiceTest` and `service.CardDetailServiceTest`.

*Embodied in:* `docs/traceability-matrix.md` — the sixteen marked rows, the legend, the arithmetic
`1 + 3 + 18 + 16 + 506 = 544`, the per-member table in *Why the unwired-paragraph marker exists*, and the
per-member notes in the four member sections — and `carddemo-java/README.md`, whose Gate 8 narrative states
the same arithmetic.

---

### DL-284 — A scoped test run writes its coverage evidence somewhere else, so the canonical report always means a full run

The `scoped-tests` profile exists so that one test class can be run without the whole-module coverage floor
failing for a reason unrelated to it. It relaxes the line-coverage minimum to zero and lifts the
wholly-untested-classes limit, which is correct: a subset of the suite cannot reach a floor defined over the
module.

**The relaxation was safe; where it wrote was not.** The coverage agent's unit destination was already
overridable, for the locale determinism gate, but the integration destination, the merged destination and all
three report directories were literals. A scoped run reaches `verify`, so it produced its own
`jacoco-it.exec`, its own `jacoco-merged.exec` and all three reports **over the canonical paths**. What it
left at `target/site/jacoco-merged` was then a report of a fraction of the suite, produced under relaxed
thresholds, sitting at exactly the path gate evidence is read from — and nothing about the artefact said
which run produced it. A reviewer, or an evidence page, quoting that report would have quoted a scoped
figure as the gate figure with no way to notice.

**The fix routes every destination through two roots.** `jacoco.exec.dir` and `jacoco.report.root` default to
the build directory and the reporting directory, so an ordinary build is byte-for-byte unchanged; the profile
redirects both to `target/scoped` and `target/scoped-site`, and all five artefacts follow. The canonical exec
files and the canonical `target/site/jacoco*` reports are consequently reserved for an unscoped
`./mvnw -B clean verify`, and no other invocation can occupy them.

**Two properties of the fix are load-bearing.** The merge step still includes the two fixed file *names*
within whichever directory the root resolves to, unchanged, because the locale determinism gate overrides the
unit destination to a *different name in the same directory* specifically so its execution stays out of the
coverage figure. And the alternative of switching the agent off for scoped runs with `-Djacoco.skip` was
rejected for the same reason it was rejected for the locale gate: a flag that disables a gate mechanism is
indistinguishable, in a log, from a gate that was never wired.

**What this obliges of anyone quoting a coverage figure.** The report and the command that produced it are
one artefact. A figure offered as gate evidence carries its provenance — the exact command, the date, which
suites ran, and which report directory it was read from — because the same file name means different things
depending on which suites ran. Selecting a subset means selecting a tier: `-Dtest=` selects unit classes and
`-Dit.test=` selects integration classes, and a run that names one leaves the other's counters absent rather
than zero.

*Verified by:* property resolution on the shipped manifest — the default build resolves
`target/site/jacoco-merged` and `target/jacoco-it.exec`, and `-Pscoped-tests` resolves
`target/scoped-site/jacoco-merged` and `target/scoped/jacoco-it.exec` — and by the continuous integration
workflow, which uploads the canonical paths and never activates the profile.

*Embodied in:* `carddemo-java/pom.xml`, the coverage-evidence section of `docs/traceability-matrix.md`, and
the Gate 7 evidence artefact in `docs/gate-evidence.md`.

---

### DL-285 — The customer rewrite is a compare-and-set over the whole before-image, because the record carries no version and the legacy compared the record itself

`Account` and `Card` carry a provider-managed `@Version`; `Customer` carries none, and that is the plan's
assignment rather than an omission — the legacy customer record has no such field, and inventing one would
add a column the layout does not have. `DL-075` records that division for the sealed token; this entry
records what the customer *write* does about it, which the token does not cover.

**The window the version column would have covered, and what covers it here.** The account-update program
holds both records for update, compares each freshly held record against the image it carried, and only then
rewrites them — `9700-CHECK-CHANGE-IN-REC` at legacy lines 4109 to 4192, immediately before the two rewrites
in `9600-WRITE-PROCESSING` at 3888 to 4103. Two different windows are involved and neither check replaces the
other. The token, minted when the screen was presented and verified on confirmation, covers *screen to
confirm*. The provider's version check covers *hold to flush*, and the account rewrite gets it for free.
The customer rewrite gets nothing for free, because there is no version attribute to check: a repository
`save` merges by identifier and would silently overwrite a customer-only change committed after the token was
verified — inside the very interval the legacy comparison exists to catch.

**So the predicate is the record.** `CustomerRepository.compareAndSet(before, after)` issues one JPQL update
whose `WHERE` clause names the key and **every one of the seventeen compared columns** at its held value, and
returns the affected-row count. One means the row still matched and was rewritten; zero means it moved, and
`AccountUpdateService` turns that zero into `CustomerRecordChangedException`, which rolls the already-flushed
account rewrite back inside the same transaction and returns the legacy data-changed message. That is the
faithful reading: the legacy compares the record, so the target compares the record. A synthetic version
column would have been the idiomatic answer and the wrong one.

**Four properties of the implementation are load-bearing.**

- **The key may not change.** The default method refuses a replacement carrying a different identifier before
  any statement is issued, because the source rewrites the record addressed by the key it read and never
  moves a new key into it.
- **One column is nullable, and its predicate says so.** The national identifier is the only nullable column
  in the comparison, so its arm is written `(customer.custSsn = :oldCustSsn OR (customer.custSsn IS NULL AND
  :oldCustSsn IS NULL))`. Plain equality would have made every held-null image match nothing and turned an
  unchanged row into a phantom conflict. Every other compared column is not-null in the shipped schema and
  uses ordinary equality.
- **The write flushes before and clears after.** `flushAutomatically = true` guarantees the account work
  already done in this transaction reaches the provider before this statement executes;
  `clearAutomatically = true` prevents the managed customer read earlier in the turn from being auto-flushed
  over the compare-and-set result. Without the clear, the stale managed copy would win and the whole check
  would be decorative.
- **Nothing is assembled or reflected.** The public method is a readable façade over a fully parameterised
  query. There is no string concatenation, no expression-language property access, no native SQL and no
  reflection, so the module-wide audit counts stay at zero and the thirty-five parameters are the price of
  that — deliberately paid rather than avoided with a dynamic predicate.

**Why this is not the improvement `DL-012` describes.** That entry records read-committed isolation plus a
version check as strictly stronger than the legacy's uncommitted-read, no-recovery baseline. This mechanism is
the *same* strength as the legacy's, deliberately: a whole-record before-image comparison is what the program
does, and the target reproduces it rather than substituting something stronger that would reject interleavings
the legacy accepted.

*Verified by:* `repository.CustomerRepositoryIT.TheCompareAndSet`, which goes through the real repository
proxy against the migrated schema — a matching row is rewritten exactly once with the absent national
identifier participating in the comparison, a row that moved is left alone and reports zero, and a
replacement carrying a different key is refused before any statement is issued; and
`service.AccountUpdateServiceTest`, which pins the service's use of it — the customer rewrite is ordered after
the account rewrite, a zero return rolls the account rewrite back and shows the detail again with the legacy
data-changed message rather than reporting a failure, and the rewrite is not attempted at all on the arms that
leave the write range earlier.

*Embodied in:* `src/main/java/com/carddemo/repository/CustomerRepository.java` and the write range of
`src/main/java/com/carddemo/service/AccountUpdateService.java`.

---

*This log is authored alongside the target module and is never edited by the code that cites it. A
citation is a pointer into this document; the reasoning lives here in one place so that it cannot
drift between the files that depend on it.*
