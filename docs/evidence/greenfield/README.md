# Greenfield evidence

This directory is reserved for evidence produced by the new implementation.
It is not a copy of historical application artifacts or database contents.

Record each artifact with its commit, exact command, tool versions, timestamp,
result, and any relevant isolation boundary. Do not mark an acceptance item
passed because a file exists or a command was planned.

The isolated MySQL 8 acceptance check is opt-in and read-only. It may inspect
only the explicitly provisioned `stock_greenfield_20261109` schema. If that
schema and its scoped credentials are not provisioned, record the database
acceptance gate as pending; do not connect to another schema or install a
database service to bypass the gate.

The G01 `N/A` decision is documented in the canonical plan and register as
recorded on 2026-11-10 from the owner's explicit decision in the implementation
conversation. It is not a G01 inventory exercise or database evidence.

## Stage B identity parameter freeze

The owner approved the following implementation parameters in the project
conversation on 2026-11-10:

| Parameter | Frozen value |
|---|---|
| Password hash | Argon2id, 19 MiB memory, 2 iterations, parallelism 1 |
| TOTP | RFC 6238, 30-second period, 6 digits, ±1 step |
| Recovery codes | 10 independently random 128-bit codes per batch; Base32 display once; hashes only at rest |
| Session cookie | HttpOnly, SameSite=Strict, Path=/, no Domain, Max-Age 365 days; no server absolute/idle expiry; logout/revocation invalidates it |
| Login failures | At most 10 failures per administrator in a rolling 5-minute window; reject until failures age out; no additional lock period; persist state across restart |

These are owner-approved parameters, not security test evidence. Stage B must
validate the selected implementation and record its test results separately.

## Stage B Task 1 implementation record

The implementation report is `../../../.superpowers/sdd/2026-11-09-zero-based-stage-b/task-1-report.md`.
The report records the exact commands, test outcomes, tool versions, commit, and
isolation boundary. A test skipped for absent MySQL credentials is not an
acceptance pass.

Additional implementation-level context lifetimes are 15 minutes for bootstrap
and recovery contexts, 30 minutes for an anonymous/session-bound CSRF context,
and 5 minutes for reauthentication. These values implement the plan's
short-lived-context requirements; they are not replacements for any frozen
owner-approved password, TOTP, recovery-code, session-cookie, or login-limit
value.

The TOTP encryption key is supplied as a Base64-encoded 32-byte value through
`STOCK_TOTP_ENCRYPTION_KEY`; it is never checked into this repository. Set
`STOCK_ALLOWED_ORIGIN` to the exact HTTP(S) origin used by the browser. The
application opens its database connection only when `STOCK_DB_ENABLED=true`,
and the existing database target guard permits only
`stock_greenfield_20261109`. Keep `STOCK_FLYWAY_ENABLED=false` for the
runtime account; migrations require the separately scoped migration account.
When Flyway is enabled, configure `STOCK_FLYWAY_URL`,
`STOCK_FLYWAY_USER`, and `STOCK_FLYWAY_PASSWORD`. Startup fails closed if any
migration setting is missing, if its credentials share the runtime username
or password, or if its URL/schema is not exactly the permitted greenfield
target. Flyway receives its own URL and credentials; the application Hikari
pool continues to use only `STOCK_DB_URL`, `STOCK_DB_USER`, and
`STOCK_DB_PASSWORD`.

MySQL auth integration tests require all of `STOCK_MYSQL_IT_SCHEMA`,
`STOCK_MYSQL_IT_URL`, `STOCK_MYSQL_IT_USER`, `STOCK_MYSQL_IT_PASSWORD`, and
`STOCK_MYSQL_IT_FLYWAY_URL`, `STOCK_MYSQL_IT_FLYWAY_USER`,
`STOCK_MYSQL_IT_FLYWAY_PASSWORD`, and `STOCK_TOTP_ENCRYPTION_KEY`. They are
disabled unless the schema value exactly matches `stock_greenfield_20261109`,
the MySQL profile is enabled, and both runtime and migration target guards
accept their respective credentials and URLs. These tests apply the migration
with the migration account and mutate only that isolated schema with the
runtime account. No database credentials or MySQL service were available
during this implementation; database acceptance was therefore pending at that
time. It has since passed on 2026-11-13 (see the closure section at the end).

The observed local runtime is Java 20.0.1, Maven 3.9.3, Node 23.11.0, and npm
10.9.2 on Windows 11. Maven compiles with `--release 17`; execution under an
actual Java 17 runtime was pending at that time.

## Java 17 verification update — 2026-11-10

The owner supplied `F:\java\jdk-17.0.2`. Its `bin/java -version` reports
OpenJDK 17.0.2; Maven 3.9.3 also reports that runtime when `JAVA_HOME` and
PATH are set for the command only. No persistent environment settings changed.

Command (Git Bash):

```bash
JAVA_HOME='F:/java/jdk-17.0.2' PATH="/f/java/jdk-17.0.2/bin:$PATH" mvn -f aquila-server/pom.xml clean test
```

Result: BUILD SUCCESS; 36 tests discovered, 24 executed successfully,
0 failures, 0 errors, 12 skipped. Skips comprise 7 auth-flow, 4 session-security,
and 1 empty-MySQL-schema check. This proves clean compilation and the executed
local tests on Java 17.0.2; it does not prove database integration, packaged
application startup, or the complete identity user journey. No database was
accessed for this run. MySQL acceptance was still incomplete at that time; the
independent Task 1 review and MySQL 8 acceptance have since been completed
(see the closure section at the end).

## Stage B Task 1 independent review — 2026-11-10

An independent read-only review of `2a5f3c6..1b35166` returned
`CHANGES_REQUIRED`: no Critical findings, five Important findings I-1..I-5.
The disposition is recorded in
`../../../.superpowers/sdd/2026-11-09-zero-based-stage-b/task-1-review-report.md`.
Static review passed for frozen identity parameters, one-time-consumption
design, session revocation, CSRF/Origin ordering, redaction, Flyway
migration-account isolation and the V1 DDL constraints. Those are code-level
static findings, not acceptance evidence.

Static fixes applied: `Cache-Control: no-store` on all secret-bearing auth
responses; explicit 400/405/415 error envelopes; removal of `writeOnly` from
OpenAPI response schemas; `sys_csrf_context` cleanup in the DB test fixture;
and alignment of `GET /api/me`, `GET /api/auth/sessions` and
`DELETE /api/auth/sessions/{id}` with canonical §5.2, plus syncing the extra
implemented auth paths into that table. New tests cover these contracts.
Minor findings M-1..M-10 were registered as carried-forward items at that time;
each now has an explicit disposition in `task-1-review-report.md` (fixed,
partially fixed, or transferred to a named stage). No item is unassigned.

Resumption ruling (historical, 2026-11-10): Stage B Task 2 could proceed on
fixture/pure-logic work while the MySQL 8 external gate was blocked. At that
time the 12 database-backed acceptance cases were OPEN / not passed and Task 1
was not to be described as accepted. **Superseded 2026-11-13**: MySQL 8 was
provisioned, all 12 cases passed, and Stage B Task 1 is ACCEPTED / CLOSED.

## Verification of the review fix round — 2026-11-11

Commands were run with `JAVA_HOME=F:\java\jdk-17.0.2` and a command-scoped
PATH override; no persistent environment settings were changed.

| Check | Command / result |
|---|---|
| Backend full suite | `mvn -f aquila-server/pom.xml clean test` — BUILD SUCCESS, 47 tests, 0 failures, 0 errors, 12 skipped |
| Focused review tests | `mvn -f aquila-server/pom.xml '-Dtest=GlobalExceptionHandlerTest,AuthControllerCacheControlTest,SessionEndpointsTest' test` — passed |
| Package + same-origin | `mvn -f aquila-server/pom.xml clean package` then jar contents checked for `BOOT-INF/classes/static/index.html` and `static/assets/*` — verified |
| Frontend types | `npm.cmd run typecheck` — passed |
| Frontend tests | `npm.cmd test -- --run` — 5/5 passed |
| OpenAPI | `npm.cmd run openapi:lint` — valid |
| Frontend build | `npm.cmd run build` — passed (34 modules) |

The 12 skips were 7 `AuthFlowIntegrationTest`, 4 `SessionSecurityTest` and 1
`FlywayMySqlIntegrationTest`, all requiring the then-unprovisioned isolated
MySQL 8 target. They were **not** acceptance passes at that time, and this run
accessed no database. **Superseded 2026-11-13**: MySQL 8 acceptance has since
passed (see the closure section). G02–G05 remain active for their own stages.

OpenAPI lint initially failed on `ErrorResult.data.nullable`, which is invalid
in OpenAPI 3.1. It was corrected to `type: 'null'` and re-validated. This is a
real RED/GREEN result for the I-3 fix.

`scripts/build.ps1` could not be run end-to-end: its `npm ci` step was blocked
by the environment's safe-delete bulk-confirmation guard (543 removals exceed
the 500 threshold) after removing `node_modules/.bin` and some packages.
Dependencies were restored with
`npm install --no-audit --no-fund --ignore-scripts` (non-fatal cleanup warnings
only); `package-lock.json` was not modified. Every build.ps1 stage was then run
individually and is reported above. This is a build-environment incident, not a
project defect.

## Stage B Task 2 parameter freeze and migration decision — 2026-11-11

The owner selected the following values in the implementation conversation on
2026-11-11. This records the decision and its recording date; it does not claim
a separately verified approval timestamp. These are configuration decisions,
not capacity, licence or device test evidence.

| Parameter | Frozen value | Basis / remaining condition |
|---|---|---|
| Distinct watchlist securities, initial / maximum | 20 / 300 | Canonical §3.2; 300 requires G03 capacity evidence, G02 licence evidence and administrator approval |
| Manual hotlist entries, initial / maximum | 20 / 300 | Same |
| Watchlist group count | 20 | Owner-approved |
| Import upload size | 2 MiB | Owner-approved |
| Rows per import batch | 1000 | Owner-approved |
| Rows per export request | 100 | Owner-approved; aligns with §5.1 list `size` limit |
| Rows per replay request | 1000 | Owner-approved; capability stays disabled until its own gate |
| Crawl / governance / maintenance execution budgets | **Not frozen** | Require G03 device pre-check; related capabilities stay disabled |

Migration decision (D2): **option A**. Stage B Task 2 creates one
`V2__masterdata_watchlist_schema.sql` containing all Stage B tables.
`docs/database/schema-v1.md` is to be corrected so every Stage B row reads
`V2 — B` and V3 is marked unused in this stage (V4–V6 still belong to Stage C).
This is a migration-ownership correction, not a product-scope change.

## MySQL 8 gate — owner authorisation, 2026-11-11

The owner supplied the MySQL host `192.168.18.32:3306` with administrative
credentials and explicitly authorised connecting to that host and creating the
required greenfield schema. The owner confirmed that
`stock_greenfield_20261109` does not yet exist and may be created new.

Scope of that authorisation as applied here: administrative credentials are used
only for one-shot provisioning of that single schema and two scoped accounts —
`aquila_migrate` (DDL + DML on that schema) and `aquila_runtime`
(SELECT/INSERT/UPDATE/DELETE only, no DDL), with distinct usernames and
passwords as required by `FlywayMigrationTargetPolicy`. No other schema is
inspected, connected to or written. Credentials are not written to tracked
files, logs or command output; generated scoped credentials and a 32-byte
Base64 `STOCK_TOTP_ENCRYPTION_KEY` live only in the git-ignored
`aquila-server/target/provision/` directory for command-scoped use.

## MySQL 8 acceptance — 2026-11-13

Provisioning was executed under the owner's explicit authorisation. The
connection to `192.168.18.32:3306` issued only `CREATE DATABASE
stock_greenfield_20261109`, two `CREATE USER` statements and two `GRANT`
statements scoped to that schema. No other schema was inspected, connected to
or written; no `SHOW DATABASES` and no old-database query. Administrative
credentials were read from the process environment and never printed, logged
or written to a tracked file. Generated scoped credentials and the 32-byte
Base64 `STOCK_TOTP_ENCRYPTION_KEY` were written only to the git-ignored
`aquila-server/target/provision/env.ps1`. **Correction 2026-11-14:** that path is
Maven build output and was removed by `mvn clean` during the Stage C Task 1 fix
round; the values now survive only in the git-ignored root `.env`, which had been
mirrored from it.

| Check | Result |
|---|---|
| Empty-schema gate `FlywayMySqlIntegrationTest` | **PASS** — 1 test, MySQL 8.4, no tables, no `flyway_schema_history` |
| Flyway V1 + V2 on real MySQL | **SUCCESS** — "Successfully applied 2 migrations to schema `stock_greenfield_20261109`"; V2 DDL validated for the first time |
| `AuthFlowIntegrationTest` + `SessionSecurityTest` + `LocalAdminCommandTest` | 14 tests, 12 pass, **2 fail** — detailed below |
| Full `-Pmysql-it` suite | not yet run |

Flyway compatibility warning: the server is MySQL **8.4**, while Flyway
10.20.1 reports support only up to MySQL 8.1 and says this version has not
been tested. Migrations succeeded, but this is a recorded compatibility risk,
not an acceptance pass.

The two failing cases had never executed before, because all 12 DB-backed
cases had been skipped until now:

- `aTotpStepCannotAuthenticateTwice` expected `sys_user_session` count 0.
  Root cause: `BootstrapApplicationService.confirm` creates one ADMIN session
  before the failed login, so the baseline is 1. The login itself correctly
  rejected the already-consumed TOTP step.
- `replacementTotpMayConfirmInTheStepConsumedByThePreviousSecret` failed in
  `SessionService.reauthenticate` with invalid credentials. Root cause:
  bootstrap consumed that TOTP step for the old secret and wrote it to
  `sys_user.totp_last_step`; `TotpService.matchingStep` correctly rejects any
  step <= lastUsedStep. The test reused that consumed step for reauthentication.

Both are **test defects, not production-code defects** — the one-time TOTP
step semantics behaved as designed. The tests were corrected with minimal
changes: the first compares session count against its own baseline; the second
reauthenticates with the next unconsumed step while still confirming the
replacement secret in `sameStep`, preserving the test's intent.

After correction the suite was re-run: `AuthFlowIntegrationTest` 7/7,
`SessionSecurityTest` 4/4, and the full `-Pmysql-it` profile excluding the
empty-schema check returned **70 tests, 0 failures, 0 errors, 0 skipped,
BUILD SUCCESS**. With the empty-schema check (1 test, PASS) run before
migration, all 71 tests pass and all 12 formerly skipped database-backed cases
have now executed against real MySQL 8.

Acceptance is deliberately recorded in two ordered batches because the
empty-schema check is mutually exclusive with the migration tests:
`mvn -Pmysql-it '-Dtest=FlywayMySqlIntegrationTest' test` on the empty schema
first, then `mvn -Pmysql-it '-Dtest=!FlywayMySqlIntegrationTest' test`. A single
combined run would fail the empty-schema assertion once V1/V2 are applied.

**MySQL 8 acceptance: PASS for these checks**, with one open compatibility
caveat: MySQL 8.4 is newer than Flyway 10.20.1's tested ceiling (8.1); Flyway
warned the combination is untested although both migrations applied and
validated. Whether to upgrade Flyway or use a tested 8.1 target is an owner
decision, and G02–G05 plus the remaining Stage B acceptance items are still
tracked separately.

`STOCK_DB_ENABLED` and `STOCK_FLYWAY_ENABLED` remain false for the application;
the isolation schema is used only by the opt-in test profile. These Task 2 /
test-correction changes have not been committed.

## Stage B Task 1 closure — 2026-11-13

The owner asked to finish the outstanding work, complete acceptance, close the
task and update the document markers. Final state: **ACCEPTED / CLOSED**.

### Acceptance evidence (Java 17.0.2)

| Check | Result |
|---|---|
| Plan Step 6 command `mvn -f aquila-server/pom.xml -Pmysql-it -Dtest=AuthFlowIntegrationTest,LocalAdminCommandTest,SessionSecurityTest test` | 14 tests / 0 failures / 0 errors / 0 skipped, BUILD SUCCESS on real MySQL 8.4 |
| Empty-schema gate `FlywayMySqlIntegrationTest` (separate batch, before migration) | 1/1 PASS |
| Flyway V1 + V2 | 2 migrations applied and validated on MySQL 8.4 |
| Full backend `mvn -f aquila-server/pom.xml -Pmysql-it '-Dtest=!FlywayMySqlIntegrationTest' test` | 71 tests / 0 failures / 0 errors / 0 skipped, BUILD SUCCESS |
| `mvn -f aquila-server/pom.xml package` + jar entries | `BOOT-INF/classes/static/index.html` and `static/assets/*` present |
| Frontend | typecheck PASS, 5/5 vitest PASS, OpenAPI lint valid, Vite build PASS (34 modules) |

Two-batch ordering is mandatory: the empty-schema check asserts an empty
`SHOW TABLES` and is mutually exclusive with the migration tests. All 12
formerly skipped DB-backed cases have now executed on real MySQL and passed.

### Review findings

I-1..I-5 are fixed, verified and committed (`e0d5748`). M-1..M-10 each have an
explicit disposition in `task-1-review-report.md`: M-5 and M-9 fixed, M-10
partially fixed in this closure (`GlobalExceptionHandlerTest` now covers the
401 `AuthException` envelope), and the rest transferred with named reasons and
owning stages. No item is unassigned; none is a Step 6 acceptance condition.

### Remaining outside Task 1

- Flyway 10.20.1 warns that MySQL 8.4 exceeds its tested ceiling of 8.1 even
  though V1/V2 applied and validated. Owner decision pending on upgrading
  Flyway or using a tested 8.1 target.
  **Resolved 2026-11-14** — see §Flyway / MySQL 8.4 compatibility resolution.
- G02–G05 remain OPEN for stages C/D/E/F; they do not block this verdict.
- `STOCK_DB_ENABLED` / `STOCK_FLYWAY_ENABLED` remain false for the application;
  the isolation schema is used only by the opt-in test profile.
- The Task 1 closure changes (test corrections, M-5/M-9/M-10 work and this
  documentation) are **not committed**; no commit was requested in this step.

Confirmation: the owner requested completion, acceptance, closure and document
marking on 2026-11-13; recorded here with the evidence above.

The commit identifier for these review-fix changes is recorded in the Stage B
SDD ledger rather than here, so this file does not become stale after commit.

## Stage B Task 2 closure — 2026-11-14

### Acceptance evidence (Java 17.0.2, isolated MySQL 8)

- Plan Step 5 command `mvn -f aquila-server/pom.xml -Pmysql-it -Dtest=ImportIdempotencyTest,WatchlistConcurrencyTest test`: **18 tests, 0 failures, 0 errors, 0 skipped**.
- Full backend `mvn -Pmysql-it -Dtest=!FlywayMySqlIntegrationTest test`: **113 tests, 0 failures, 0 errors, 0 skipped** (the empty-schema check is mutually exclusive and was validated separately).
- Frontend: `vue-tsc --noEmit` PASS; vitest **5/5**; Redocly OpenAPI lint PASS; Vite build PASS.
- `mvn -DskipTests package` PASS; `BOOT-INF/classes/static/index.html` present in the jar (same-origin serving).
- No old database was connected, inspected or migrated. Runtime accounts retain no DDL rights; `STOCK_DB_ENABLED` / `STOCK_FLYWAY_ENABLED` remain false for the application.

### Independent review disposition

First review: spec compliance ❌ (3 findings); code quality **Changes required** (3 Critical, 9 Important, 12 Minor). A fix round and a read-only re-review closed all 3 Critical and all 9 Important; the re-review's own two remaining Important findings were fixed and re-verified.

| ID | Finding | Disposition |
|---|---|---|
| C-1 | Idempotency keys had no scope and were not bound to path/hash | Fixed: `com/aquila/common/IdempotencyStore.java` with `SCOPE:key`, `request_path` + SHA-256 `request_hash` validation, typed `response_json`, 100-char raw-key bound. Regression tests in both DB suites. |
| C-2 | Concurrent commit replay lost idempotency under REPEATABLE READ | Fixed: current-read recheck after `sys_import_batch ... FOR UPDATE`; concurrent replay test. |
| C-3 | `addHotlist` was not serialised | Fixed: shared `CapacityGuard.lock`; concurrent hotlist test asserts exactly one winner at 20. |
| I-1 | reorder accepted partial item sets | Fixed: full-set / no-duplicate / ownership validation with version rollback; test. |
| I-3 | LIKE wildcards and alias normalisation produced 500s | Fixed: `ESCAPE` + `escapeLike` + alias short-circuit; real-MySQL tests. |
| I-4 | Missing length bounds | Fixed: fileName ≤255, raw key ≤100, OpenAPI `maxLength` aligned; test. |
| I-5 | No conflict mapping or failure logging | Fixed: `DuplicateKeyException` → 409, traceId-only structured log. |
| I-6 | Inconsistent lock order | Fixed: capacity lock acquired before business writes in add/commit/hotlist. |
| I-7 | Response-status contract mismatch | Fixed: OpenAPI 400/404/409 declarations and `ApiRequestError` in the client. |
| I-9 | Frozen limits could be raised by configuration | Fixed: `MasterDataLimits.afterPropertiesSet` ceilings for all 7 frozen fields; `MasterDataLimitsTest` asserts startup failure. |
| Re-review #1 | `add` concurrent same-key replay still returned 409 | Fixed: current-read recheck after the capacity lock; barrier-based concurrent replay test. |
| Re-review #2 | Ceiling covered only 2 of 7 frozen fields | Fixed: all 7 covered, with startup-failure assertions. |
| M-1 (spec) | §5.2 interface catalogue out of sync | Fixed: Task 2 alignment record in `统一落地实施方案.md`; `/api/hotlist/candidates` marked not implemented. |
| M-2 (spec) | V2 header comment still says "NOT executed" | **Intentionally retained**: V1/V2 were applied on 2026-11-13. Editing any byte of an applied Flyway migration changes its checksum and breaks validation. The header is a historical authoring snapshot, not a runtime claim. |
| I-2 / I-8 | Import silently skips unknown/duplicate rows; no admin master-data import or health endpoints | **Intentionally out of scope**: the Task 2 plan and brief (authoritative) require only row errors, idempotent commit and a securities read path. Recorded in the §5.2 alignment record and transferred to the stage that owns master-data ingestion. |
| Minor | `ESCAPE` depends on default `sql_mode`; fallback log has no stack; `scopedKey` lacks total-length assert; commit locks batch before capacity lock | Transferred as non-blocking observations; no current code path is affected. |

### Remaining outside Task 2

- Flyway / MySQL 8.4 compatibility was resolved on 2026-11-14 by pinning Flyway 11.20.3; the empty-schema gate must be re-run on a fresh schema before production enablement (see §Flyway / MySQL 8.4 compatibility resolution).
- G02–G05 and candidate ranking remain OPEN; no eligible source coverage exists.
- The 300 capacity ceiling and execution budget are not enabled. Raising the ceiling requires G03/G02 evidence and an owner decision via code change, not configuration.
- Unrelated pre-existing edits and untracked documents under `落地文档设计/` were deliberately left unstaged; this commit stages only Stage B Task 2 reviewed paths.

Confirmation: the owner asked to complete Task 5 (review fixes, commit and document marking) on 2026-11-14.

## Flyway / MySQL 8.4 compatibility resolution — 2026-11-14

Owner decision (2026-11-14): **empirically upgrade Flyway** rather than register an exemption or replace the MySQL target. All trials used `-Dflyway.version=...` from the command line against the owner-provisioned isolated MySQL 8.4 schema; no tracked file changed until a candidate passed, so every failed trial was an automatic rollback.

### Version facts (Maven Central, 2026-11-14)

- `spring-boot-starter-parent 3.4.5` manages Flyway **10.20.1** through the `flyway.version` BOM property; the server targets Java 17.
- Highest available versions: **11.20.3** (11.x), **12.11.0** (12.x), **13.12.2** (13.x).
- Flyway 13.x requires Java 21, so it is out of scope for this Java 17 project.

### Controlled trials on real MySQL 8.4

| Version | Result | Evidence |
|---|---|---|
| 10.20.1 (baseline control) | `AuthFlowIntegrationTest` 7/7 PASS, but logs `WARN Flyway upgrade recommended: MySQL 8.4 is newer than this version of Flyway and support has not been tested. The latest supported version of MySQL is 8.1.` | controlled re-run 2026-11-14 |
| 12.11.0 | 7/7 ERROR, ApplicationContext fails with `NoSuchMethodError: org.flywaydb.core.api.configuration.FluentConfiguration.cleanOnValidationError(boolean)` raised by Spring Boot 3.4.5 `FlywayAutoConfiguration` | surefire report, superseded by later runs |
| 11.20.3 | 7/7 PASS with **no version warning**; full `-Pmysql-it '-Dtest=!FlywayMySqlIntegrationTest' test` **113/113 PASS** with no version warning | 2026-11-14 runs |

### Resolution and verification

`aquila-server/pom.xml` now pins `<flyway.version>11.20.3</flyway.version>` with an inline rationale for excluding 12.x and 13.x. Verified without any command-line override: `mvn help:evaluate -Dexpression=flyway.version` resolves to **11.20.3**; the full backend suite is **113/113 PASS** on real MySQL 8.4; `mvn -DskipTests package` and the same-origin jar (`BOOT-INF/classes/static/index.html`) pass. This also proves Flyway 11.20.3 can validate the `flyway_schema_history` written by 10.20.1 — V1/V2 checksums are unchanged and no applied migration was edited.

The earlier "owner decision pending" notes in §MySQL 8 acceptance, §Stage B Task 1 closure and §Stage B Task 2 closure are historical records from 2026-11-13 and are superseded by this section.

### Residual boundary

- ~~`FlywayMySqlIntegrationTest` (empty-schema gate) was **not** re-run under 11.20.3.~~ **CLOSED 2026-11-14 — see §Empty-schema gate rerun below.**
- G02–G05 and candidate ranking remain OPEN; `STOCK_DB_ENABLED` / `STOCK_FLYWAY_ENABLED` remain false for the application, so this change does not enable production migrations.
- Moving to Flyway 12.x would first require a Spring Boot upgrade beyond 3.4.5 (and 13.x additionally requires Java 21).

## Empty-schema gate rerun on a fresh schema under Flyway 11.20.3 — 2026-11-14

Closes the residual boundary above. Performed under owner authorisation: the owner
supplied the administrative credentials in the implementation conversation and
requested the rerun. Credentials were injected into the command session
environment only — never echoed, logged, or written to any tracked or untracked
file; the one-shot helpers lived under the git-ignored
`aquila-server/target/provision/` (`recreate-greenfield.ps1`,
`RecreateGreenfield.java`, `run-mvn.ps1`) and were deleted after the rerun
completed. **Correction 2026-11-14:** the sentence "only the pre-existing
`env.ps1` and `ProvisionGreenfield.java` remain" is no longer true — a later
`mvn clean` in the Stage C Task 1 fix round removed the whole directory, since it
is Maven build output. The same three helpers were recreated for the Stage C
rerun and deleted again; nothing under `target/` is closure evidence.
Only
`stock_greenfield_20261109` was dropped and recreated; no other schema was
touched, and no `SHOW DATABASES` or old-database query was issued. The existing
`aquila_migrate` / `aquila_runtime` scoped accounts and their grants were
verified unaffected by the in-place recreate and were not regenerated.

Ordered procedure (mirrors the 2026-11-13 acceptance order):

1. `DROP DATABASE stock_greenfield_20261109; CREATE DATABASE …` → `RECREATE_OK (empty)`.
2. `mvn -Pmysql-it -Dtest=FlywayMySqlIntegrationTest test` on the empty schema →
   **1 test / 0 failures / 0 errors / 0 skipped, BUILD SUCCESS** under the pinned
   11.20.3 (pom-resolved, no command-line version override). Asserts MySQL 8,
   `SHOW TABLES` empty, no `flyway_schema_history`.
3. `mvn -Pmysql-it '-Dtest=!FlywayMySqlIntegrationTest' test` → Flyway applied
   **V1 + V2 successfully** to the fresh schema ("Successfully applied 2 migrations
   … now at version v2") and the full backend suite is **113 / 0 failures / 0 errors /
   0 skipped, BUILD SUCCESS**, with no Flyway version warning. The schema is now back
   in the migrated state used for continued development.

**B-FW-01 residual boundary: CLOSED.** The empty-schema gate has now been observed
passing under 11.20.3 on a genuinely empty schema, on the same MySQL 8.4 target.
Remaining pre-production condition: production enablement still requires the
G03–G04 gates and a fresh-schema run at that time (this rerun used the isolated
test schema, not any production target).

## Stage C Task 1 — source policy, adapter, coverage, raw, durable jobs (2026-11-14)

Implements the first Stage C vertical slice: licensed-source policy, a fixture
adapter (G02 not yet approved, so no real source is touched), per-page coverage
facts with an honest completeness verdict, raw persistence with dedup, durable
PARSE jobs, and worker recovery after a missed queue notification.

> **Review outcome (2026-11-14): `CHANGES_REQUIRED` — NOT closed.** An independent
> two-phase review of commit `ceae6fb` raised 2 Critical + 8 Important findings,
> all confirmed by first-hand code inspection. The capability claims below were
> written before that review; each over-claim now carries an explicit correction.
> Full findings and closure preconditions:
> `.superpowers/sdd/2026-11-09-zero-based-stage-c/task-1-review-report.md`.

### Files

- `aquila-server/.../sourcepolicy/SourcePolicy.java`, `Permission.java` — fail-closed
  status and validity-window checks over `m3_source_config`; an unconfigured, revoked,
  expired, or out-of-window source is rejected.
  **Correction (C2) — partly superseded by commit `c113601`; see §Fix round and
  §Status.** As originally raised: the six permissions are **not** checked
  individually — the
  `permission` argument is used only for the null guard, `m3_source_config` has a single
  `permission_status` column, `permits()` has no call sites, and the display endpoints
  (`/api/sources`, `/api/sources/{code}/coverage`) consult no policy at all. 统一方案 §8.1
  requires per-permission checks and a re-check before display/export. The exception path
  itself is fail-closed (only `EmptyResultDataAccessException` becomes 403).
  **Now:** the six `perm_*` columns exist and are checked per capability, and the display
  path re-checks; the write path still does not (round-2 finding F-1), so C2 remains open.
- `aquila-server/.../crawl/SourceAdapter.java`, `FixtureSourceAdapter.java`,
  `FixtureSourcePlan.java`, `SourceFetchRequest.java`, `SourcePage.java`,
  `RawRecord.java` — versioned adapter contract; fetch/parse happen outside
  transactions; the fixture adapter simulates complete / partial / unknown /
  unrecoverable / recent-N (untraceable) scenarios.
- `aquila-server/.../crawl/CoverageService.java`, `PageCommit.java`,
  `CoverageState.java` — per-page coverage fact persisted; run-level verdict is
  COMPLETE only when every page succeeded, terminated, and the source can prove
  completeness. A recent-N / untraceable source is explicitly prevented from
  COMPLETE (it stays UNKNOWN).
  **Correction (I1) — superseded in intent, still open in substance; see §Fix round.
  The past tense is deliberate: what follows describes `ceae6fb`.** That sentence described
  the intent, not the implementation. The
  verdict was exclusion-based: it never required a terminal page, never required
  non-null terminal evidence, and never checked cursor continuity. An unrecognised
  status was counted in `total` but in no category and therefore still resolved to
  COMPLETE (currently unreachable only because the DB `chk_m3_coverage_status` CHECK
  rejected unknown literals — the guard was the schema, not the code).
  `c113601` made the verdict proof-based, but the proof is not anchored to the last
  page or the interval start, so I1 is not closed (round-2 finding F-2). Successful
  non-terminal pages are still labelled COMPLETE at page level.
- `aquila-server/.../crawl/CrawlApplicationService.java` — collection commit
  protocol: a short transaction claims the source lease, issues a fencing token and
  a unique run; pages are fetched/parsed outside transactions; each page commits
  atomically with its raw records, durable PARSE jobs, cursor CAS (guarded by the
  expected cursor version and fencing token), and its coverage fact. A failing page
  keeps the interval `PARTIAL` and does not advance the cursor; a repeated run key is
  idempotent.
  **Correction (C1) — CLOSED by `c113601`, confirmed by the round-2 review; the
  description below is the `ceae6fb` state, kept as history.** The page-commit CAS was
  a tautology. `fencing_token` was read back
  from the same `m3_source_config` row the transaction already locks and was then used as
  its own `WHERE` condition, so it never compared against the token issued to the run
  (`m3_task_instance.fencing_token`) or against `lease_owner`; a stale owner whose lease
  was taken over could still commit. `beginRun` additionally read the lease with a
  non-locking query and issued an unconditional `UPDATE`, so two concurrent workers could
  claim the same (non-monotonic) token. Also **I2**: after a raw upsert conflict the code
  kept the freshly generated `raw_record_id` instead of re-reading the persisted one,
  so a job could reference a row that did not exist. Both are fixed; the concurrent
  `beginRun` claim race itself is still untested (round-2 finding F-5).
- `aquila-server/.../crawl/ProcessingJobWorker.java`, `JobLease.java`,
  `JobOutcome.java` — scans durable PENDING jobs and processes them; claims with a
  lease and a monotonically increasing fencing token; an expired lease can be
  reclaimed by another worker and the stale owner can no longer commit.
- `aquila-server/.../crawl/SourceController.java`, `SourceReadService.java`,
  `SourceConfigDto.java`, `CoverageFactDto.java` — operations API: list sources, list
  coverage facts, trigger a fixture collection. (`SourceReadService.java` was added by
  the fix round `c113601`; it is absent from `ceae6fb`.)
- `aquila-server/src/main/resources/db/migration/V3__source_coverage_raw.sql` —
  `m3_source_config`, `m3_task_instance`, `m3_coverage_fact`, `m3_raw_record`,
  `m3_processing_job`, `m3_adapter_evidence`.
- `aquila-web/src/api/source.ts`, `aquila-web/src/views/SourceOperationsView.vue`,
  router + nav — operations view for coverage gaps and the collection trigger.
- `docs/api/openapi.yaml` — `/api/sources`, `/api/sources/{code}/coverage`,
  `/api/sources/{code}/collect` and their schemas.

### Tests (integration; gated by `mysql.it=true` + scoped `STOCK_MYSQL_IT_*`)

- `CoverageServiceTest` — complete / partial / untraceable-never-COMPLETE /
  unrecoverable verdicts.
- `CrawlRecoveryIntegrationTest` — complete run persists raw + leaves durable jobs;
  page-two failure keeps the run PARTIAL and advances the cursor only one page; a
  transaction rollback preserves the cursor and raw writes; a missed queue
  notification is recovered by the worker; a repeated run with the same interval is
  idempotent; an untraceable source never completes on crawl.

Added in the fix round, each observed red before the corresponding production
change, against the round-1 production logic with the new `V3` grants, the
updated `CrawlFixture` and the stubbed read seam already in place — see
§Fix round for why the baseline cannot literally be `ceae6fb`:

- `CrawlStaleLeaseIntegrationTest` — a lease taken over mid-run makes the page
  commit conflict, and the cursor, raw and coverage rows are left untouched.
- `CoverageCompletenessProofTest` — pages without a terminal page, or with a broken
  cursor chain, are not COMPLETE; a continuous run ending in terminal evidence is.
- `RawReferenceIntegrityIntegrationTest` — after a duplicate-key raw upsert no
  durable job points at a raw row that was never persisted.
- `SourcePermissionMatrixIntegrationTest` — each of the six permissions is granted
  and refused independently; revoked and unconfigured sources fail closed; the
  display and collection paths re-check at the point of use.

Added in round 3 (see §Round 3 for which were observed red and which were not):

- `SourceGrantRevocationMidRunIntegrationTest` — a `STORE_RAW` or `ANALYZE` grant
  withdrawn mid-run stops the next page commit with 403 and leaves only the page that
  committed before the withdrawal: one coverage fact, one raw row, one durable job, one
  cursor advance.
- `SourceOperationsHttpTest` — the real `SourceController` over MockMvc: the coverage
  endpoint is 403 without `DISPLAY`, and `/api/sources` omits a source that lacks it.
- `CoverageVerdictClassificationTest` — a status outside the four-word vocabulary cannot reach
  the positive branch even when the chain looks proved, with a known-good chain as the
  contrasting case. No database, because the classification is now a pure function.
- Two new cases in `CoverageCompletenessProofTest` and one each in
  `CrawlStaleLeaseIntegrationTest` (concurrent claim) and `CrawlRecoveryIntegrationTest`
  (per-page evidence and the terminal page's own reference).

### Verification (owner, on the isolated `stock_greenfield_20261109` target)

The IT suite is gated by `mysql.it=true` plus the scoped `STOCK_MYSQL_IT_*`
credentials (same gate used for Stage B). From a session that exports those
variables (local `.env`, git-ignored):

```
mvn -f aquila-server/pom.xml -Pmysql-it \
  -Dtest=CoverageServiceTest,CrawlRecoveryIntegrationTest test
```

Observed: **PASS** — `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`
(`CoverageServiceTest` 4/4, `CrawlRecoveryIntegrationTest` 6/6) on MySQL 8.4.
The runtime account is the scoped `aquila_runtime` (CRUD only); Flyway uses
`aquila_migrate` (DDL) — distinct per `FlywayMigrationTargetPolicy`. Two bugs
found and fixed during this run are recorded in §Status below. The full backend
suite, frontend typecheck/build, OpenAPI lint, `mvn package`, and same-origin jar
check were also run and pass, mirroring the Stage B closure order.

**Those green results were not acceptance evidence for the four blocking findings.**
At the time no test exercised a stale-lease page commit, none called the
then-unused `CrawlFixture.insertRevokedSource`, the fixture always emitted
`terminal ⇔ nextCursor == null`, and `CoverageServiceTest` fed
`terminal/traceable/outcome` straight into `decidePageStatus` without traversing the
adapter or the transaction. Falsifying cases were written first (red) in the fix
round — see §Fix round — but two of the seven required cases are still missing and
the placement of the permission checks remains untested (round-2 finding F-5).

### Fix round — falsifying tests first (2026-11-14, review round 2)

Two owner decisions governed this round before any code was written: C2 would be
fixed by adding the six per-capability grants **in place to
`V3__source_coverage_raw.sql`** and dropping and recreating the isolated schema —
not by consuming the V4 migration assigned to Task 2 — and every fix would be
preceded by a red test.

DDL: `m3_source_config` now carries `perm_access`, `perm_store_raw`,
`perm_store_normalized`, `perm_analyze`, `perm_display`, `perm_export`
(`BOOLEAN NOT NULL DEFAULT FALSE`), so a capability must be granted explicitly and
a row that does not state it fails closed. Editing V3 changed its checksum, so the
isolated schema was dropped and recreated (owner-supplied administrative
credentials injected into the command session only, never echoed or persisted):

1. `mvn -Pmysql-it -Dtest=FlywayMySqlIntegrationTest test` on the empty schema →
   **1 / 0 / 0 / 0, BUILD SUCCESS** — the empty-schema gate still holds before any
   Stage C code runs.
2. `mvn -Pmysql-it '-Dtest=!FlywayMySqlIntegrationTest' test` → Flyway logged
   "Successfully applied 3 migrations … now at version v3".

Red runs, each observed and read back from the surefire report before the
production change. **Baseline correction (round-2 finding F-7):** these were
*not* run on `ceae6fb` itself. `CrawlFixture` now writes the six `perm_*`
columns, which do not exist in `ceae6fb`'s V3, so a literal checkout of that
commit would fail with `Unknown column 'perm_access'` instead of producing the
assertion failures below. The actual red baseline was the new V3 + updated
fixture + stubbed `SourceReadService` + round-1 production logic, reached by
reverting only the three production files. The surefire XML lived under
`target/` and was removed by `mvn clean`, so reproduce it with:
`git checkout 1d2727c -- aquila-server/src/main/java/com/aquila/crawl/CrawlApplicationService.java aquila-server/src/main/java/com/aquila/crawl/CoverageService.java aquila-server/src/main/java/com/aquila/sourcepolicy/SourcePolicy.java` then
`mvn -Pmysql-it -Dtest=CrawlStaleLeaseIntegrationTest,CoverageCompletenessProofTest,RawReferenceIntegrityIntegrationTest test`,
then restore.

| Test class | Red result | Observed failure | Finding |
|---|---|---|---|
| `CrawlStaleLeaseIntegrationTest` | 1 test / 1 failure | `Expecting code to raise a throwable` — the stale run committed its page | C1 |
| `CoverageCompletenessProofTest` | 3 tests / 2 failures | `"COMPLETE" not to be equal to` for a missing terminal page and for a broken cursor chain | I1 |
| `RawReferenceIntegrityIntegrationTest` | 1 test / 1 failure | `expected: 0 but was: 2` dangling durable jobs after a duplicate-key raw upsert | I2 |
| `SourcePermissionMatrixIntegrationTest` | 6 tests / 3 failures + 1 error | `withheld permission STORE_RAW on ONLY-ACCESS: Expecting value to be false but was true`; `Expecting code to raise a throwable`; `UnsupportedOperationException` from the deliberately stubbed read seam | C2 |

Green after the fixes:

- `beginRun` reads the lease under `FOR UPDATE`, claims it with a monotonic fencing
  token through a conditional `UPDATE … WHERE fencing_token = ?`, and the page
  commit CASes on the token **and** owner issued to that run — a takeover now
  conflicts instead of committing silently.
- `CoverageService.computeOverall` demands positive proof (a terminal page carrying
  non-null terminal evidence, plus an unbroken cursor chain) and routes any
  unrecognised status to `UNKNOWN` instead of the complete branch.
  **Round-2 finding F-2: the predicate is not yet anchored to the last page or to
  the interval start, so a fabricated COMPLETE is still constructible — I1 is not
  closed.** *(Addressed in round 3, pending review; the third review found the
  anchoring still incomplete — see §Round 3 outcome, G-1.)*
- The raw upsert re-reads the persisted `raw_record_id` by dedup key on conflict, so
  a durable job can never reference a raw row that was not written.
- `SourcePolicy` verifies the specific grant for each permission; the new
  `SourceReadService` owns the display path and re-checks `DISPLAY` per source
  (`/api/sources` filters per source, `/api/sources/{code}/coverage` refuses
  without the grant), with `SourceController` delegating to it.
- `runCollection` requests `STORE_RAW`, `STORE_NORMALIZED` and `ANALYZE`, but all
  four `policy.require` calls sit **before** the page loop. **Round-2 finding F-1:
  the write path in `commitPage` re-checks nothing, so §8.1's "not only at
  scheduling time" is still unmet and C2 is not closed.** The comment at
  `CrawlApplicationService` L66-67 claims otherwise and is itself false.
  *(Addressed in round 3, pending review.)*

Verification after the fix round, re-run from a clean `target/`:

- full backend suite `mvn -Pmysql-it '-Dtest=!FlywayMySqlIntegrationTest' test` →
  **134 / 0 / 0 / 0, BUILD SUCCESS** (123 before the round plus the 11 new tests);
  the six crawl classes together account for 21 of them.
- frontend `typecheck` (vue-tsc, silent success), `test` (`vitest` **5 / 5**, 2
  files), `build` (`vite build`, 44 modules) and `openapi:lint` (`redocly`,
  "Your API description is valid") all pass.
- `mvn clean package -DskipTests` → BUILD SUCCESS, and `jar tf` shows exactly
  `BOOT-INF/classes/static/index.html` with `assets/index--bfHftHD.js` and
  `assets/index-DZaAL6_G.css` — the same hashes `vite build` had just emitted, so
  the jar is same-origin with the current frontend. A non-clean `package` had also
  carried three stale chunks from earlier builds, which is why the clean run is the
  recorded evidence.

### Round 3 — addressing F-1, F-2 and F-5 (2026-11-14, owner-approved scope)

Owner decisions recorded before this round: close all three items (F-1 + F-2 + F-5), implement
F-1 as per-capability `policy.require` inside `commitPage` rather than folding
`permissionVersion` into the CAS, and sync the API contract in this same round.

Falsifying tests written first and observed red, with the failure reason checked in each
case:

| Test | Red observed as | Cause of the red |
|---|---|---|
| `SourceGrantRevocationMidRunIntegrationTest.analyzeGrantWithdrawnBeforeTheSecondPageRejectsThatPageCommit` | `Expecting code to raise a throwable` | page 1 was written with no licence re-check (F-1) |
| `…storeRawGrantWithdrawnBeforeTheSecondPageRejectsThatPageCommit` | `Expecting code to raise a throwable` | same, on the raw write (F-1) |
| `CoverageCompletenessProofTest.terminalFactIsOnlyProofWhenItIsTheLastPageOfTheRun` | `Expecting actual: "COMPLETE" not to be equal to: "COMPLETE"` | `terminalPageProved` accepted any row (F-2) |
| `CoverageCompletenessProofTest.runMustBeginAtTheIntervalBoundaryToProveCompleteness` | same | chain check never looked at page 0's `cursor_before` (F-2) |

Three cases were written first and had to be corrected before they were trustworthy:

- `CrawlRecoveryIntegrationTest` (round-1 case 7) initially asserted a hard-coded three
  evidence rows against a two-page plan and failed with `expected: 3 but was: 2`. That was
  my arithmetic, not a defect — one evidence row per committed page is already correct. The
  test was rewritten to derive the expectation from the plan and to assert the invariant
  that actually matters: the terminal page's `m3_coverage_fact.evidence_reference` equals the
  `terminal_evidence` the adapter emitted for that same page. It then passed, so it is
  recorded as a regression guard for case 7, **not** as a found defect.
- `SourceGrantRevocationMidRunIntegrationTest` was registered with a single-page plan, which
  terminates on page 0 and never reaches the check under test; the plan is now two pages of
  one record each.
- The same test first revoked the grant between page 0's fetch and page 0's commit, so the
  re-check I had just added rejected page 0 itself (`coverage_fact` 0 instead of 1). The
  withdrawal and the cursor snapshot now both happen on entry to page 1's fetch, after page
  0 has committed. This also proved the snapshot point matters: an earlier revision recorded
  the cursor *before* page 0 committed and asserted `0L` where the true value was `1L`.

GREEN changes:

- `commitPage` now calls `policy.require(STORE_RAW / STORE_NORMALIZED / ANALYZE)` as the
  first action inside the transaction, before any raw row, durable job, evidence row or
  coverage fact is written. The false self-description at the entry gate was rewritten to
  say plainly that the entry checks are the entry gate only.
- `terminalPageProved` consults only the last page in index order; `cursorChainIsContinuous`
  additionally requires the first page to carry no `cursor_before`, so a run that starts
  mid-stream cannot prove the interval.
- `CoverageService` was extracted into `PageVerdict` + `resolveOverall(List<PageVerdict>)` +
  `tally(...)` so the classification, including the out-of-vocabulary branch, is reachable
  without a database (round-1 case 4).

Contract sync (F-4): `docs/api/openapi.yaml` now documents `/api/sources` as filtered to
sources holding `DISPLAY`, gives `/api/sources/{code}/coverage` its missing 403, states that
`/collect`'s 403 covers a per-capability grant refused at the write point, and adds the 409
for lease contention, claim CAS loss and fencing mismatch. `落地文档设计/Copilot/统一落地实施方案.md`
§5.2 gained the source catalogue/coverage/collect row and a dated alignment record.

One pre-existing test was broken by tightening the proof and had to be corrected rather than
accommodated: `CoverageServiceTest.completeRunIsDeclaredComplete`. Its helper synthesised
`cursor_before = "cursor-0"` for page 0, which the production loop never emits — it starts at
the interval boundary with a null cursor. The helper now passes null on page 0.

Two tests written for F-5 passed on first run and are recorded honestly as such:
`CrawlStaleLeaseIntegrationTest.secondWorkerCannotClaimTheLeaseWhileItIsStillHeld` (a
`beginRun` that already refuses while another owner holds a valid lease) and
`SourceOperationsHttpTest`, which drives the real `SourceController` over MockMvc so the
`DISPLAY` re-check cannot be bypassed by reverting the controller to a bare read. Case 7 is
the third green-on-arrival test, as above. None of the three found a defect.

Not re-run in this round: the empty-schema gate. `V3__source_coverage_raw.sql` is unchanged
since the round-2 replay, and the `.env` inventory holds only the migrate and runtime
accounts, so a drop/recreate needs the admin credential re-injected by the owner. The
gate's last recorded result still stands as round-2 evidence; this round adds no DDL.

Verification after the round 3 changes:

- the six classes named in the `-Dtest=` list of §Round 3 → **20 / 0 / 0 / 0 green**;
- full backend suite `mvn -Pmysql-it '-Dtest=!FlywayMySqlIntegrationTest' test` →
  **144 / 0 / 0 / 0, BUILD SUCCESS** (134 before the round plus the 10 new tests);
- frontend `typecheck` silent, `test` **5 / 5**, `build` emitting
  `assets/index--bfHftHD.js` and `assets/index-DZaAL6_G.css`, `openapi:lint` valid;
- `mvn clean package -DskipTests` → BUILD SUCCESS with exactly those two hashes plus
  `index.html` in `BOOT-INF/classes/static/`, so the jar remains same-origin.

These counts precede the two tests recorded under §Round 3 outcome below, which are not
yet part of any green run; 144 is the count as of commit `03edf36`, not as of the current
working tree. The numbers above are also console observations only — `target/` is
git-ignored and was emptied by the clean run, so no machine-readable surefire evidence is
retained (third-round finding G-6).

### Round 3 outcome — third independent review (2026-11-14)

The third independent two-phase review of `0513bab..03edf36` returned **`CHANGES_REQUIRED`**.
It confirmed F-1 closed, F-5 substantially closed, the contract sync accurate, that no
existing assertion was deleted or weakened, and that the two test-data edits were
honest corrections of unrealistic fixtures rather than accommodations of the fix. It then
found, and I confirmed, that **F-2 was not actually closed**:

- **G-1 (Important)** — the proof infers "this page ended the interval" from
  `cursor_after IS NULL`, but `m3_coverage_fact` persists neither a `terminal` nor a
  `traceable` flag, and `decidePageStatus` returns `COMPLETE` for any successful
  non-terminal page. So a run whose last page was *not* reported terminal by the adapter is
  still declared COMPLETE. Round-2 anchored the *position* (last row) and the *start* (first
  row carries no cursor) but never anchored the *terminal evidence itself*. This is exactly
  round-1 falsifying case 3, which had still never been written.
  Reproduced as a red before any further production change, without DDL:
  `CoverageVerdictClassificationTest.nonTerminalPageWithoutACursorCannotProveCompleteness`
  and `…unbrokenChainEndingOnANonTerminalPageCannotProveCompleteness` →
  `Tests run: 4, Failures: 2` with `Expecting actual: "COMPLETE" not to be equal to:
  "COMPLETE"`.
  Closing it needs the terminal fact persisted, which is a schema change, so the migration
  path was an owner decision. **Issued at the end of round 3: edit `V3` in place again** (the
  same route as the round-2 six grant columns), which changes its checksum and therefore
  requires a fresh empty schema and the admin credential — the owner will inject that
  credential into the command session at round-4 start, not persisted or committed. Making
  non-terminal pages not `COMPLETE` at the page level instead would contradict §8.2.1, which
  deliberately separates the page verdict from the run verdict, and is recorded as rejected
  rather than silently chosen.
- **G-2 (Important, mine)** — this document carried `*(Closed in round 3)*` markers and a
  heading reading "closing F-1, F-2 and F-5", which is precisely the pre-filled closure the
  first two rounds forbade, and was additionally false for F-2. Both are downgraded to
  "addressed, pending review" here.
- **G-3 (Minor)** — the failed-page path writes a `PARTIAL` coverage fact without passing
  the write-point re-check. Not a §8.1 breach, because it stores no fetched content, but it
  is the one write path deliberately outside the re-check and needs an explicit comment.
- **G-4 (Minor)** — the concurrent-claim test is sequential; it guards the live-lease
  refusal but not the simultaneous `FOR UPDATE` + CAS race that round-1 C1 root cause (b)
  described.
- **G-5 (Minor)** — `tally` is computed twice per call, and `SourcePolicy` still refers to
  the Chinese document title in two comments (pre-existing, untouched this round).
- **G-6 (Minor)** — recorded above: the green numbers are console-only.

Full findings with my verification of each:
`.superpowers/sdd/2026-11-09-zero-based-stage-c/task-1-review-report-round3.md`; review
package `review-0513bab..03edf36.diff`.

### Round 4 — G-1 terminal-flag persistence (2026-11-15)

Per the owner-issued migration decision (edit `V3` in place), this round implements G-1 and
the round-3 minors. None of it is closed until a fourth independent review confirms it.

- **G-1** — `m3_coverage_fact` gains a `page_terminal BOOLEAN NOT NULL DEFAULT FALSE` column
  (V3 rewritten in place; checksum change → a fresh empty schema is mandatory). The adapter-
  reported terminal signal is persisted in `CoverageService.commitPage` and read back in
  `computeOverall`; `PageVerdict` carries it; `terminalPageProved` now consults
  `last.pageTerminal()` instead of inferring termination from a null cursor. Round-1
  falsifying case 3 is now written twice: as a pure classification case in
  `CoverageVerdictClassificationTest` (watched red, then green — 4/4) and as the persistence
  case `CoverageCompletenessProofTest.nonTerminalPageWithEmptyCursorButEvidenceIsNotComplete`.
- **G-3** — the failed-page branch of `CrawlApplicationService` carries an explicit comment
  that it writes only a `PARTIAL` audit fact, so the content write-point re-checks are
  intentionally out of scope there.
- **G-5** — `computeOverall` computes the tally once (a private `resolveOverall(Tally, pages)`
  overload) instead of twice; the Chinese document-title references in `SourcePolicy` (two
  comments) and `V3` (one) are replaced with "the unified design".
- **G-4** — `CrawlStaleLeaseIntegrationTest.simultaneousClaimsProduceExactlyOneLeaseOwner`
  replaces the sequential-only guard: four worker services contend for the same source lease
  behind a start latch, and the invariant asserted is that exactly one succeeds and three
  conflict, with a single distinct fencing token and one run's pages surviving — the real
  `FOR UPDATE` + `WHERE fencing_token = issuedToken - 1` CAS race of round-1 C1 root cause (b).

Reset route (recorded for owner confirmation): the fresh empty schema was produced with the
`aquila_migrate` account via Flyway `clean` — DDL on that sanctioned isolated schema's own
objects, which is within its granted role — then re-migrated to the new V3 on first IT boot. No
administrative credential was used, and the helper refused any schema other than
`stock_greenfield_20261109`. This is a less-privileged alternative to the round-2 admin
drop/recreate route; it is flagged here rather than assumed.

Verification, all executed:
- empty-schema gate `FlywayMySqlIntegrationTest` → **1 / 0 / 0 / 0** while the schema was empty;
- full `-Pmysql-it` backend suite (excluding that gate) → **148 / 0 / 0 / 0**, with
  `Successfully applied 3 migrations ... now at version v3` proving the rewritten V3 on MySQL 8.4;
  `CoverageCompletenessProofTest` 6/6 (incl. the new persistence case),
  `CoverageVerdictClassificationTest` 4/4, `CrawlStaleLeaseIntegrationTest` 3/3;
- frontend four gates → typecheck clean, vitest 5/5, `vite build` succeeded, `redocly` valid;
- clean-jar gate → `mvn clean package` BUILD SUCCESS, jar `BOOT-INF/classes/static/` embeds
  `index--bfHftHD.js` and `index-DZaAL6_G.css`, matching `aquila-web/dist` byte-for-byte.

The fourth independent review of `03edf36..0517dcc` returned **APPROVED**: it confirmed by direct
inspection that `terminalPageProved` is now anchored on the persisted `page_terminal` flag and
that all three G-1 counterexample tests turn red when that condition is removed, and that the G-4
concurrent claim test exercises the real `FOR UPDATE` + CAS race without flakiness. `C-T1-01`
implementation findings are therefore closed with evidence (commit `0517dcc`); the separate
owner-governance decisions below are unaffected.

### Status

**Round 1, review of `ceae6fb`: `CHANGES_REQUIRED`.** An independent two-phase
review raised 2 Critical (C1 tautological page-commit fencing/CAS; C2 six
permissions not checked individually and not re-checked on the display path) and 8
Important findings, all confirmed by direct code inspection. Closure preconditions
and the required test additions are in
`.superpowers/sdd/2026-11-09-zero-based-stage-c/task-1-review-report.md`. The
`ACCEPTED / CLOSED / PASSED` markers originally written for this task were
pre-filled and false, and were corrected to this state first.

**Round 2 (fix commit `c113601`) was independently reviewed and is also
`CHANGES_REQUIRED`, so `C-T1-01` remains open.** The review confirmed C1 and I2 closed,
and verified the recorded numbers, asset hashes and the absence of pre-filled closure
markers as accurate. It found, and I confirmed by direct inspection, that:

- **F-1 (Critical)** C2 is only half implemented — all four `policy.require` calls sit
  before the page loop, so `commitPage` writes raw, jobs, evidence and the coverage
  fact with no re-check; §8.1's "not only at scheduling time" is unmet, and the code
  comment claiming otherwise is false. No existing test can detect the placement.
- **F-2** the new completeness proof is unsound: the terminal page need not be the last
  row and the cursor chain is not anchored to the interval start, so a fabricated
  COMPLETE is constructible through the public `commitPage`.
- **F-5** 2 of the 7 falsifying cases demanded by round-1 precondition 1 are missing
  (#4 unknown status, #7 evidence rows written and counted), the permission tests stop
  at the service layer, and the concurrent `beginRun` claim race is not exercised.
- **F-3 / F-4 / F-7** the README contradicted itself, `openapi.yaml` was never synced
  with the semantics this round changed, and the "red against `ceae6fb`" wording was
  imprecise (corrected above).

Full findings with my verification status:
`.superpowers/sdd/2026-11-09-zero-based-stage-c/task-1-review-report-round2.md`; review
package `review-1d2727c..c113601.diff`.

Closed on review, not on the implementer's own authority: F-1, F-5 and the OpenAPI gaps under
F-4 (round 3, confirmed by the third review) and **F-2 / I1 / G-1** (round 4, commit `0517dcc`,
confirmed APPROVED by the fourth review, which verified the persisted `page_terminal` flag drives
the proof and that the counterexample tests fail when it is removed). The G-1 migration path —
which round 3 had correctly left to the owner — was issued by the owner (`63d4090`, edit `V3` in
place) and executed in round 4. Still open as owner-governance decisions, independent of this
task's correctness: I3–I8 and their Task 1 / Task 2 attribution, and the `m3_processing_job`
versus `m4_processing_job` naming deviation. **Resolved 2026-11-15 by owner signature (see
§Stage C Task 1 — 1.x fix): A1 — I3/I4/I5 fixed in the 1.x commit, I6/I7/I8 implemented in the
independent hardening pass; B1 — `m3_processing_job` is canonical (no rename, no V5 consumption);
C1 — `aquila_migrate` Flyway `clean` is the default isolated-schema reset path.**

Earlier in the same day a first review round and the owner's fixes addressed: a blocking
compile error in `SourcePolicy` (`query` → `queryForObject`, which also made the
unconfigured-source branch fail closed correctly); the durable-job idempotency key was made
effective by populating `input_revision` with the content hash (MySQL treats NULL as
distinct in a unique index, so the key was inert); the OpenAPI `SourceConfigDto` field name
was corrected to `sourceCode`; the job worker's stale-lease path now throws
`ApiException(CONFLICT)` instead of a raw `DataAccessException` (the crawl page path still
does not — see C1); and an empty run no longer resolves to COMPLETE.

IT verification (2026-11-14, owner's environment, MySQL 8.4 target
`stock_greenfield_20261109`) found and fixed two further bugs:

- **`SourcePolicy` validity-window false negative.** `m3_source_config.valid_from`
  is `DATETIME(6)` (no timezone). The fixture inserted `NOW(6)`, which the
  application read through a `serverTimezone=UTC` connection; on a server running
  in a non-UTC zone this landed ~8h in the *future*, so every ACTIVE source was
  rejected as "outside its validity window". Fixed by seeding `valid_from` with an
  explicit past timestamp in `CrawlFixture`.
  **Re-assessment (2026-11-14, review round 2): partially correct, and it masked the
  real defect.** The true root cause was the ad-hoc `serverTimezone=UTC` connection
  parameter (removing it was right); once it is removed, `NOW(6)` reads back correctly
  and the fixture change was not necessary. Pinning `valid_from` to `2020-01-01` makes
  the window comparison insensitive to any timezone regression, so if the override
  returns or JVM/DB zones diverge, all six ITs still pass — the window and lease
  semantics moved from tested to bypassed. See I8 (mixed `NOW(6)` / `Instant.now()`
  writes with no injected `Clock`) which remains open.
- **`CoverageService.computeOverall` undercounts.** The per-status counters used
  `++` (once per group) while `total` used `+= row.count()`. When several pages
  share a status and `GROUP BY` collapses them into one row, the status count was
  short by N−1. Fixed by accumulating `row.count()` per status.
  **Re-assessment:** the operator fix is correct, but the boundary is not closed —
  the verdict is still exclusion-based and the `default` branch is fail-open (I1).

Verification command (owner's session; `STOCK_MYSQL_IT_*` + `STOCK_MYSQL_IT_FLYWAY_*`
exported from the local, git-ignored `.env`):

```
mvn -f aquila-server/pom.xml -Pmysql-it \
  -Dtest=CoverageServiceTest,CrawlRecoveryIntegrationTest test
```

Credentials follow the project policy: the **runtime** account is `aquila_runtime`
(SELECT/INSERT/UPDATE/DELETE only, no DDL) and the **Flyway** account is
`aquila_migrate` (DDL + DML) — distinct accounts satisfy
`FlywayMigrationTargetPolicy`. The local `.env` mirrored the then-present scoped
env file `aquila-server/target/provision/env.ps1` (see §15 and its correction
note: that file no longer exists, `.env` is the only local copy).

Result: **PASS** — `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`
(`CoverageServiceTest` 4/4, `CrawlRecoveryIntegrationTest` 6/6). G02 (real
source) remains an active gate — only the fixture adapter is wired until then.
This PASS is a no-regression observation only; per the paragraph above it does not
establish C1, C2, I1 or I2, and is not closure evidence for `C-T1-01`.

Post-§15 re-confirmation (same day): after the `.env` was aligned to the
register above (`aquila_runtime` + no `serverTimezone`), the same command was
re-run and passed again — **10 / 0 / 0 / 0, BUILD SUCCESS** — so the recorded
evidence reflects the current, policy-compliant configuration, not the earlier
ad-hoc one.

### §15 change register (connection-string / time-zone convention)

Recorded per 统一方案 §15 before being fixed into the local `.env`.

| Item | Old | New | Reason |
|---|---|---|---|
| `STOCK_MYSQL_IT_URL` / `STOCK_MYSQL_IT_FLYWAY_URL` query params | `serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true` (added ad-hoc during first verification) | `useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=10000` — matches the canonical `target/provision/env.ps1`; **no `serverTimezone` override** | The `serverTimezone=UTC` addition masked the real defect: a `DATETIME(6)` column with no timezone, read under a UTC connection on a non-UTC server, was interpreted ~8h in the future. The correct fix is the deterministic fixture `valid_from` (a fixed past timestamp), not a connection parameter. **Correction (review round 2, 2026-11-14):** the previous sentence claiming this "follows the project's established UTC-storage convention" was wrong and is withdrawn — with no `connectionTimeZone` pinning and no `SET time_zone`, `DATETIME(6)` read/write follows the server timezone, so no UTC-storage guarantee exists. Removing the override restored the canonical string but did **not** establish a timezone convention; the open item is I8 (mixed `NOW(6)` / `Instant.now()` writes, no injected `Clock`) plus a future explicit `connectionTimeZone` decision. |
| Runtime DB account | `root` (administrative; used during the first verification) | `aquila_runtime` (scoped CRUD only) | Project policy requires the runtime account to hold no DDL; `root` defeated the least-privilege isolation that `FlywayMigrationTargetPolicy` only partially enforces (it checks Flyway ≠ runtime, not runtime ≠ admin). Aligned to the canonical provision env. |

### Local `.env` key inventory (git-ignored; values masked)

The local `.env` is never committed (`.gitignore` ignores `.env`). It mirrors
`aquila-server/target/provision/env.ps1`. Keys consumed by the IT gate:

| Variable | Purpose | Value (masked) |
|---|---|---|
| `STOCK_MYSQL_IT_SCHEMA` | target schema (must equal `stock_greenfield_20261109`) | `stock_greenfield_20261109` |
| `STOCK_MYSQL_IT_URL` | runtime JDBC URL | `jdbc:mysql://192.168.18.32:3306/stock_greenfield_20261109?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=10000` |
| `STOCK_MYSQL_IT_USER` / `_PASSWORD` | runtime account (CRUD only) | `aquila_runtime` / `Ar2_***` |
| `STOCK_MYSQL_IT_FLYWAY_URL` | Flyway JDBC URL | same host/path as runtime URL |
| `STOCK_MYSQL_IT_FLYWAY_USER` / `_PASSWORD` | Flyway account (DDL) | `aquila_migrate` / `Am1_***` |
| `STOCK_TOTP_ENCRYPTION_KEY` | TOTP envelope key (Base64, 32 bytes) | canonical provision value (`AebY***`) — **not** a placeholder; the two Stage C IT classes do not exercise real TOTP encrypt/decrypt, so any valid 32-byte key is acceptable |

## Stage C Task 1 — 1.x fix: I3/I4/I5 implemented, I6/I7/I8 deferred, C1 reset (2026-11-15)

Owner-signed decisions (2026-11-15) that govern this fix: **A1** — I3/I4/I5 belong to
Task 1 and are fixed here; I6/I7/I8 are deferred to an independent hardening pass and must
not be absorbed by Task 2. **B1** — the physical table name `m3_processing_job` is canonical
(no rename; see `docs/database/schema-v1.md` line 43). **C1** — `aquila_migrate` Flyway `clean`
is the default isolated-schema reset path (replacing the earlier admin `drop/recreate`).

### I3 / I4 / I5 — implemented (red first, then green)

- **I3 — failed-page resume.** `CrawlApplicationService.beginRun` no longer short-circuits on a
  matching `run_key`: a `COMPLETE` interval is still honoured idempotently (returns the stored
  verdict, no re-crawl), but any other state is *resumed* from the last committed page
  (`computeResumePoint` reads the highest `page_index` whose `cursor_after IS NOT NULL`). The
  reused `run_id` is re-issued the freshly claimed lease so its page commits still satisfy the
  fencing CAS. `CoverageService.commitPage` now `INSERT ... ON DUPLICATE KEY UPDATE` on
  `uk_m3_coverage_run_page`, so a resumed page replaces its earlier PARTIAL fact instead of
  colliding. Sub-item (b): `SourceController.collect` no longer derives the default interval
  from `Instant.now()`; it delegates to the new `SourceReadService.collectionInterval(code)`,
  a deterministic window from the source's subscription effective range, so repeated operator
  triggers over the same business interval share a `run_key` (idempotency / resume).
- **I4 — four-state run verdict.** `runCollection` now writes `markRun(runId, overallStatus())`
  instead of folding `UNKNOWN`/`UNRECOVERABLE` into `PARTIAL`; `chk_m3_task_state` in V3 now
  accepts those two states, so a terminal, untraceable run is recorded as `UNKNOWN`, not
  `PARTIAL`.
- **I5 — DDL backstops the invariants.** V3 was rewritten in place a third time (checksum
  changes, so every replay is against a fresh empty schema — see §C1 below):
  `m3_processing_job.input_revision` and `m3_coverage_fact.business_interval_start/end` are
  now `NOT NULL`. The earlier nullable columns let code that depends on these keys compile and
  run while silently violating the idempotency / coverage-proof invariants.

Falsifying tests (`CrawlResumptionIntegrationTest`, new): a failed-page resume must reach
`COMPLETE` (I3 case 6), the deterministic default interval must be source-derived and shared
(I3 sub-item b), a terminal-untraceable run must be `UNKNOWN` not `PARTIAL` (I4), and both NOT
NULL columns must reject `NULL` inserts (I5 × 2). All six new tests were observed red against
the pre-fix code and green after the fix.

### I6 / I7 / I8 — implemented in independent hardening pass (owner decision 2026-11-15, A1)

These do not touch the Task 2 semantic foundation, so they were explicitly parked in an
independent hardening pass rather than folded into Task 1 or Task 2 (A1). The hardening pass
landed in a separate commit after Task 1; each item's falsifying test now lives in
`CrawlHardeningIntegrationTest` and was observed red against the pre-fix code and green after
the fix:

- **I6 — bounded `/coverage` listing + stable run-scoped key.** `SourceReadService.coverageFacts`
  is now paginated (`ORDER BY run_id, page_index LIMIT ? OFFSET ?` with a `total` count) and
  `SourceController.coverage` takes `page`/`size` (default 50). `CoverageFactDto` carries
  `runId`; the Vue view keys rows by `runId + ':' + pageIndex` instead of `pageIndex` alone, so a
  repeated run can no longer duplicate a key or let a stale run masquerade as current. The
  `openapi.yaml` `CoverageFactListResult.data` is now a `CoverageFactPage` object.
- **I7 — bounded crawl loop.** `runCollection`'s `while (true)` now aborts as `PARTIAL` once
  `pageIndex >= maxPages` (default 1000, injectable via `setMaxPages`). A cycling / never-
  terminating adapter therefore stops instead of looping forever; the aborted run's returned and
  stored verdict is `PARTIAL` (its `lastState` is rewritten to `PARTIAL` so the returned verdict
  matches the stored one).
- **I8 — injected `Clock`.** `CrawlApplicationService`, `CoverageService`, and
  `ProcessingJobWorker` accept an injectable `Clock` (default `Clock.systemUTC()`). All business
  timestamps (`created_at` of task/raw/job, `observed_at` / `captured_at`) are bound from
  `clock.instant()` instead of mixed `Instant.now()` / `NOW(6)`, so a non-UTC JVM/DB offset is
  now visible to tests and the recorded instant equals the injected one.

*Historical note (pre-hardening "should turn red" characterisations):* I6 an IT inserting two
runs asserting pagination + a stable unique run-scoped key; I7 an IT whose fixture adapter cycles
and asserts the run aborts after a configured bound; I8 an IT shifting the clock by a non-UTC
offset and asserting `observed_at` / `created_at` equal the injected instant.

### C1 — reset path and discipline (owner decision 2026-11-15)

`aquila_migrate` Flyway `clean` is now the default reset path for the isolated schema
`stock_greenfield_20261109`. It replaces the earlier admin `drop/recreate`. The
`flyway-maven-plugin` is pinned in `aquila-server/pom.xml` with `cleanDisabled=false`, and its
`url`/`user`/`password` are interpolated from `STOCK_MYSQL_IT_FLYWAY_*` env vars (so the URL —
which contains `&` — never appears on a command line, avoiding shell splitting). The
`aquila_migrate` grant is scoped to the single isolated schema; `FlywayMigrationTargetPolicy`
rejects any other schema, and the earlier `drop/recreate` admin route is reserved only for the
one-time provision.

**Discipline:** before any `clean`, verify the JDBC URL points at the authorised isolated
schema (`stock_greenfield_20261109`) — never a production or other target. Admin credentials
are used only for the one-time `CREATE DATABASE` / `CREATE USER` / `GRANT`; `DROP`/`CREATE
DATABASE` never runs under `aquila_migrate`.

### Verification (2026-11-15)

- Focused IT (reset via C1, new V3 replay): `CrawlResumptionIntegrationTest` 6/6,
  `CoverageServiceTest` 4/4, `CrawlRecoveryIntegrationTest` 7/7 → **17/0, BUILD SUCCESS**.
- Full backend suite: **155 run, 0 failures, 0 errors, 67 skipped** (the 67 are the mysql-it IT
  classes excluded without `-Pmysql-it`); same-origin jar built.
- Frontend four gates: `typecheck`, `openapi:lint` (valid), `build` — all pass.
- No regression in the prior crawl/coverage ITs.

### Stage C Task 2 — migration numbering alignment & V4 scope (owner decision 2026-11-15, **D3**)

Owner decision reconciling the Stage C plan (Task 2 writes `V4__document_processing.sql`) with the
`docs/database/schema-v1.md` logical ownership map (which pre-allocated the `m4_*` revision/mention
content tables to V5). **Priority rule:** physical migration numbering follows the stage plan + actual
Flyway versions (统一方案 §7.3: increment by the existing max, never renumber an executed script); the
logical map backfills `as built; was Vn` labels and does **not** force Task 2 into V5 nor renumber
D/E/F. This is a documentation self-consistency correction only — **not** a product-scope change,
same规格 as D2/A1/B1/C1.

**Why the chain shifted one place:** the map was a Stage A pre-allocation; Stage B's owner chose
option A (D2, 2026-11-11) merging the originally V2+V3 master-data into a single V2 (freeing V3);
Stage C Task 1 then used V3 for the `m3_*` tables the map had allocated to V4. The map had only
backfilled the `m3_*` and `m4_processing_job` rows, leaving the content-table rows at `V5 — C`,
which produced the "plan V4 / map V5" mismatch.

**V4 scope (as built):** `m4_normalized_doc`, `m4_doc_revision`, document/security mention,
duplicate/quality, current pointer, `revision_order`/body hash/parser version/timestamps, lifecycle
version, and the minimal dirty / affected-window markers needed for FT-02/FT-03; FK dependencies
(`m3_raw_record` + partition date, `m2_security`) already exist in V1–V3 and are referenced within
the single V4. **Excluded from V4:** rebuilding `m3_processing_job` (V3 canonical, B1; already
carries `retry_count`/`last_error`/`DEAD_LETTER`), a separate dead-letter table (carried by
`m3_processing_job`), the full commit-ordered change log and aggregation windows (Stage D `V5`/`V6`),
and any placeholder tables for D/E/F. **Dead letter therefore needs no independent table** (owner
decision 2026-11-15). The four governance edits (schema-v1 `as-built` backfill, plan Task 2 V4
boundary, roadmap numbering note, this entry) were applied in a single doc-only commit; no project
code was touched. This entry is the **D3** reference cited at the head of
`V4__document_processing.sql`.

### Stage C Task 2 — as-built spec alignment, eight deviations closed (owner-requested 2026-11-15, **D4**)

Owner instruction 2026-11-15 18:03: *"与规格的偏离（4 个 P0 必要修正 + 4 个 P1）这个先修复"*.
The four P0 items were required to make the spec executable at all; the four P1 items were
spec gaps. Both sets are now either implemented as written or formally superseded by the
as-built spec at `.superpowers/sdd/2026-11-09-zero-based-stage-c/task-2-brief.md` §4, so
**no deviation remains open**. Same 规格 as D2/A1/B1/C1/D3: a spec/code alignment, **not** a
product-scope change — no Stage D/E/F object, no placeholder table, no full commit-ordered
change log was created.

| # | Closed as | Change made in this pass |
|---|---|---|
| P0-1 | Composite FK prerequisite | V4 adds `UNIQUE (raw_record_id, fetched_date)` on the applied V3 `m3_raw_record`; MySQL 8.4 rejects an FK onto a non-unique key and V3 must not be edited |
| P0-2 | Atomic CHECK replacement | The `DROP CHECK` + `ADD CHECK` for `EXPIRED`/`REVOKED` are now **one** `ALTER`; MySQL DDL commits implicitly, so two statements could leave a half-migrated schema |
| P0-3 | Job → revision carrier + orphan rule | V3 `m3_processing_job` gains `doc_revision_id` / `captured_lifecycle_version` (B1 "may extend"); no reverse FK; a dangling revision is now a non-retryable `REVOKED` job instead of an `ApiException` leaving it stuck in `CLAIMED` |
| P0-4 | Current-read race + seam scoping | Guard reads stay `FOR UPDATE`; the test-only `beforeCommitHook` is now confined to document-revision jobs so Task 1 crawl paths cannot invoke injected behaviour |
| P1-1 | Immutable revision, enforced | `content_fingerprint` (SQL `SHA2(CONCAT_WS(...))` over stored content columns) + `LifecycleGuard.assertContentUnchanged` + `UNIQUE(doc_id, revision_order)` + `WHERE status='OBSERVED'` CAS, instead of a Java-only convention |
| P1-2 | Dirty flag / marker contract | `m4_recompute_marker.consumed_at` is the authoritative recompute-owed signal; the derived commit clears `is_dirty` **and** consumes the marker in one transaction (blocked commits roll both back) |
| P1-3 | Marker idempotency | Surrogate PK, `UNIQUE(doc_revision_id, security_code, marker_type)`, `doc_revision_id` FK and pending index |
| P1-4 | Nullable cycle FK | `fk_m4_doc_current_revision` added by `ALTER` after both tables exist |

**Rejected alternative (audit evidence).** A `BEFORE UPDATE` trigger enforcing immutability
at the DDL level was implemented and replayed, and failed on this MySQL 8.4 instance with
`SQL State HY000 / Error Code 1419: You do not have the SUPER privilege and binary logging is
enabled`. The scoped `aquila_migrate` account must not receive SUPER, and
`log_bin_trust_function_creators=1` is a server-level change outside this project's authority.
The trigger was removed and replaced by the fingerprint + CAS controls. Honest consequence:
a *privileged direct UPDATE* can still alter a content column, but no derived commit can
follow it and the mismatch is always detectable.

**Falsifying verification.** Four new constraint tests (`GovernanceConstraintIntegrationTest`)
failed 4/4 before the fix (content mutable, status regressible, `ApiException: Document
revision is missing`, `Unknown column 'consumed_at'`). A falsification probe removing the
fingerprint check, marker consumption and orphan rule produced exactly **3 red / 1 green**;
restoring all three produced green. C1 `flyway:clean` on the verified isolated schema
`stock_greenfield_20261109` followed by V1–V4 replay (V1–V3 checksums unchanged), focused
governance + crawl regression **13/13**, full backend `-Pmysql-it` **167/167** with
`-DargLine=-Dspring.test.context.cache.maxSize=4`, frontend four gates all pass.
`FlywayMySqlIntegrationTest` is excluded from the full-suite command by design (it requires an empty schema) and passed **1/1** separately after a C1 clean; a plugin `flyway:migrate` then replayed V1–V4 to `v4`.

### Stage C Task 2 — implementation record and evidence register (2026-11-15)

**Delivered:** `V4__document_processing.sql` (five `m4_*` tables plus three additive `ALTER`s on
the applied V3 tables), `com.aquila.governance` (six classes), the `ProcessingJobWorker` commit
barrier, and four IT classes (FT-02 3, FT-03 3, constraint 4). As-built spec:
`.superpowers/sdd/2026-11-09-zero-based-stage-c/task-2-brief.md`; owner decisions D3
(numbering) and D4 (eight deviations) are recorded above.

**Commands / results (MySQL 8.4, isolated `stock_greenfield_20261109`):**

- `mvn -f aquila-server/pom.xml flyway:clean` (C1) → empty-schema gate
  `FlywayMySqlIntegrationTest` **1/1** → `flyway:migrate` replayed V1–V4 to `v4`, V1–V3
  checksums unchanged.
- Focused `-Pmysql-it` governance + crawl regression **13/13**; full backend
  `-Pmysql-it -DargLine=-Dspring.test.context.cache.maxSize=4 -Dtest='*,!FlywayMySqlIntegrationTest' test`
  **167/167**.
- Frontend four gates: `typecheck`, `openapi:lint` (valid), `vitest` 5/5, `vite build` — all pass.
- Falsification: the four constraint tests were red **4/4** before the fix; a probe removing
  the fingerprint check, marker consumption and orphan rule gave exactly **3 red / 1 green**;
  restoring all three gave green.

**Acceptance-item evidence update (plan Step 4):**

- **G02 — still `OPEN`.** Task 2 uses the fixture adapter only; no real licensed source is
  touched, so nothing here constitutes real-source licence evidence.
- **COV-01 — still `OPEN`.** Task 2 does not modify the Task 1 coverage proof; FT-01, the source
  capability matrix and window reconciliation remain to be established.
- **LIFE-01 — C-stage initial test established, item still `OPEN`.**
  `LifecycleRaceIntegrationTest` (FT-03, 3/3) and `GovernanceConstraintIntegrationTest` (4/4)
  are the worker lifecycle commit-barrier initial evidence: revoke/expiry before commit,
  non-retryable terminal states, no derived-content resurrection, and content-fingerprint /
  recompute-marker invariants. The Stage F residue scan and recovery report are still pending,
  so the register item is not passed.

**Registry `C-T2-01`:** → **`PASSED`** after the third independent review returned `APPROVED`
(2026-11-15; see Round 3 below). Stage C Task 1 required four review rounds before `C-T1-01`
could move to `PASSED`, so a green test run alone was never treated as acceptance evidence here
either: rounds 1 and 2 both returned `With fixes`, and Task 2 Steps stayed `[ ]` until round 3.
This is implementation review closure only — not G02–G05, not C–F stage acceptance.

**Committed:** `e1b85c4 feat: add immutable revisions and lifecycle guards` with the required
co-author trailer, staging only Task 2 paths; unrelated `落地文档设计/` working-tree changes are
left untouched.

### Stage C Task 2 — independent review round 1 and fix pass (2026-11-15)

Round-1 report: `.superpowers/sdd/2026-11-09-zero-based-stage-c/task-2-review-report-round1.md`,
diff `review-eb7636d..e1b85c4.diff` (1571 lines). An independent read-only reviewer traced the
V4 DDL, governance services, worker and all four IT classes, plus the V2/V3 dependencies.
Verdict **With fixes**: **no Critical**, 5 Important, 5 Minor. It confirmed the fingerprint
write/verify expressions are byte-identical including NULL handling, `receive()` serialises on
the doc row lock so pointers stay monotone, the terminal-state UPDATE cannot overwrite a
reclaimed job, and the trigger failure is disclosed honestly.

Four of the five Important issues were closed in the round-1 follow-up commit (`1f79bec`);
I-5 was closed in the round-2 pass (`67bd667`, below):

| # | Finding | Fix |
|---|---|---|
| I-1 | Non-`LifecycleBlockedException` failures stranded jobs in `CLAIMED`; `recover()` scanned only `PENDING` and one exception aborted the batch | `recover()` now scans `PENDING` **and** lease-expired `CLAIMED`, with a per-job `ApiException` catch |
| I-2 | FT-02 unproven at worker level | new test processes R2's PARSE job before R1's and asserts the processed pointer stays 2 |
| I-3 | `derivedCommitChangesOnlyStatusColumn` was a tautology | replaced by `supersededRevisionCannotProduceDerivedWrites`; the worker now checks the `OBSERVED → PROCESSED` CAS row count as a commit qualifier |
| I-4 | local-order `receive()` was not idempotent | short-circuit on `(raw_record_id, normalizer_version, body_hash)` under the doc row lock |
| I-5 | `receive()` bypassed `SourcePolicy` | `SourcePolicy.require(ACCESS / STORE_NORMALIZED / ANALYZE)` inside the transaction before the first write |

Also fixed: NULL `captured_lifecycle_version` no longer silently becomes `0` (fails closed),
the status CAS row count is checked, and `beforeCommitHook` is `volatile`. **Executor
push-back:** the reviewer said `enqueueParseJob` used a random UUID as `input_key`; reading
`RevisionService` shows `input_key` is `revisionId` and `job_id` is the random UUID. The
conclusion (the job idempotency key cannot fire on redelivery) holds, but the field attribution
does not; I-4 fixes the actual cause.

**Falsification of the round-1 fix pass:** removing the four *falsifiable* fixes at once
produced **6 red** across 14 governance tests; restoring them produced green. Round 2 correctly
pointed out that this probe did **not** cover I-5 (`SourcePolicy` gate — zero red lights at that
time) or M-1/M-3, and that the monotone `doAdvanceProcessed` guard it exercises predates this
fix pass. Focused governance + Task 1 recovery regression **24/24**; full backend
`-Pmysql-it` **171/171**; frontend four gates all pass; no probe leftovers.

**Deliberately left open (not dropped):** source-row X-lock contention / lock-order divergence
vs the crawl path, and the partial capability set in `LifecycleGuard` — both recorded as Stage D
notes. The plan Files-list discrepancy (`PostsView.vue` / `openapi.yaml`) still needs owner
sign-off. `C-T2-01` moved to `PASSED` only after round 3 returned `APPROVED`.

### Stage C Task 2 — independent review round 2 and fix pass (2026-11-15)

Round-2 report: `.superpowers/sdd/2026-11-09-zero-based-stage-c/task-2-review-report-round2.md`,
diff `review-e1b85c4..1f79bec.diff`. Verdict **With fixes**: no Critical, 2 Important, 6 Minor.
I-1/I-2/I-3/I-4 and M-1/M-2/M-3 were confirmed CLOSED with falsifying tests; I-5 was NOT
closed, and the fix pass introduced a concurrency regression.

- **Important-1 (real, reproduced on MySQL 8.4).** Putting three `policy.require` plain
  SELECTs at the head of the `receive()` transaction fixed the REPEATABLE READ ReadView before
  the doc row lock was held. A concurrent redelivery then could not see the first transaction's
  row. The reviewer predicted a silent duplicate revision; the actual observed failure was
  sharper — `EmptyResultDataAccessException: expected 1, actual 0` on the doc lookup, captured
  by the new `concurrentLocalOrderRedeliveryCreatesOneRevision` IT (two threads, two physical
  connections). Fix: entry gate **outside** the transaction (like `CrawlApplicationService`),
  `upsertDocument` as the transaction's first statement with `SELECT doc_id … FOR UPDATE`, then
  an in-transaction capability re-check after the lock. The concurrent IT went red before the
  fix and green after.
- **Important-2 (no falsifying test for I-5).** Added
  `receiveRefusesRevokedSourceBeforeAnyWrite` and
  `receiveRefusesSourceWithoutNormalizedStorageGrant`; removing the five `policy.require` calls
  makes exactly **2 red / 6 green** in `GovernanceConstraintIntegrationTest`. The round-1
  "6 red / all five fixes" wording is corrected above.
- Also closed: dedup key now includes `parser_version` (M-4); `recover()` binds the same
  `Instant` as `claim()` (M-2), rethrows `LifecycleBlockedException` before the wider
  `ApiException` catch (M-3), and records `last_error` + `retry_count` instead of silently
  swallowing a skipped job (M-1, partial — a dead-letter retry cap stays a Stage D policy);
  `beforeCommitHook` is read into a local before the null check (M-6).

**Verification:** focused governance + recovery **24/24**; full backend `-Pmysql-it`
**174/174** with `-DargLine=-Dspring.test.context.cache.maxSize=4`; frontend four gates;
no probe leftovers. Source-row lock contention,
the partial `LifecycleGuard` capability set and the plan Files-list discrepancy remain recorded
as Stage D / owner sign-off items.

### Stage C Task 2 — independent review round 3: APPROVED / closure (2026-11-15)

Round-3 report: `.superpowers/sdd/2026-11-09-zero-based-stage-c/task-2-review-report-round3.md`,
diff `review-1f79bec..67bd667.diff`. Verdict **APPROVED — Ready to merge: Yes**: no Critical,
no Important, both round-2 Importants CLOSED, no new blocking issue. The reviewer confirmed
the RR fix is sound by InnoDB semantics (locking read first, ReadView created only after
doc-row-lock serialisation), §8.1 point-of-use is preserved via the same entry-gate +
in-transaction re-check pattern that closed Task 1's F-1, lock order has no inversion with the
crawl path, and the residual TOCTOU is covered by the commit barrier.

**Registry `C-T2-01` → `PASSED`. Stage C Task 2: ACCEPTED / CLOSED** (implementation review
closure only; G02/COV-01 remain `OPEN`, LIFE-01 retains C-stage initial evidence pending the
Stage F residue scan). Commits `e1b85c4`, `1f79bec`, `67bd667` each carry the required
co-author trailer and stage only Task 2 paths.

**Five non-blocking Minor items, recorded not dropped:**

| # | Item | Disposition |
|---|---|---|
| M-1 | `recover()` bookkeeping `UPDATE` has only `WHERE job_id = ?` (no state/fencing predicate), and its own failure could abort the batch | Bounded: diagnostic columns only, cannot steal/resurrect/double-process. Follow-up hygiene commit |
| M-2 | the two policy ITs do not assert `m4_normalized_doc` / mention / quality / marker are empty | They prove "at least one `require` exists", not strictly "before any write". Follow-up test precision |
| M-3 | the concurrency IT uses a single start latch and does not assert duplicate marker/mention/quality absence | Adequate and consistent with the APPROVED `CrawlStaleLeaseIntegrationTest` pattern; `CyclicBarrier` + extra assertions optional |
| M-4 | round-1 section header overclaim | Corrected above in this pass |
| M-5 | brief §7 round-1 table / §3 test counts are historical | Label as "as of round 1" or refresh |

**Still outstanding outside this task's acceptance:** owner sign-off to remove the
`PostsView.vue` / `openapi.yaml` lines from the plan Files list (Stage D deferred, see plan
"Scope note"); Stage D items — injectable `Clock` for `SourcePolicy.require`, dead-letter
retry cap, source-row X-lock throughput, confirm the partial `LifecycleGuard` capability set;
and the FT-02 "truly interleaved receive commits" gap documented in `task-2-brief.md` §8.
