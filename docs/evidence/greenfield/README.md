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
Base64 `STOCK_TOTP_ENCRYPTION_KEY` live only in the git-ignored
`aquila-server/target/provision/env.ps1`.

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
file; the one-shot helpers live under the git-ignored `aquila-server/target/provision/`
(`recreate-greenfield.ps1`, `RecreateGreenfield.java`, `run-mvn.ps1`; all three
were one-shot helpers and have been deleted after the rerun completed — only the
pre-existing `env.ps1` and `ProvisionGreenfield.java` remain under
`target/provision/`). Only
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
  **Correction (C2):** the six permissions are **not** checked individually — the
  `permission` argument is used only for the null guard, `m3_source_config` has a single
  `permission_status` column, `permits()` has no call sites, and the display endpoints
  (`/api/sources`, `/api/sources/{code}/coverage`) consult no policy at all. 统一方案 §8.1
  requires per-permission checks and a re-check before display/export. The exception path
  itself is fail-closed (only `EmptyResultDataAccessException` becomes 403).
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
  **Correction (I1):** that sentence describes the intent, not the implementation. The
  verdict is exclusion-based: it never requires a terminal page, never requires
  non-null terminal evidence, and never checks cursor continuity. An unrecognised
  status is counted in `total` but in no category and therefore still resolves to
  COMPLETE (currently unreachable only because the DB `chk_m3_coverage_status` CHECK
  rejects unknown literals — the guard is the schema, not the code). Successful
  non-terminal pages are labelled COMPLETE at page level, and the collection loop can
  exit with `cursor == null` while `terminal == false` and still mark the run COMPLETE.
- `aquila-server/.../crawl/CrawlApplicationService.java` — collection commit
  protocol: a short transaction claims the source lease, issues a fencing token and
  a unique run; pages are fetched/parsed outside transactions; each page commits
  atomically with its raw records, durable PARSE jobs, cursor CAS (guarded by the
  expected cursor version and fencing token), and its coverage fact. A failing page
  keeps the interval `PARTIAL` and does not advance the cursor; a repeated run key is
  idempotent.
  **Correction (C1):** the page-commit CAS is a tautology. `fencing_token` is read back
  from the same `m3_source_config` row the transaction already locks and is then used as
  its own `WHERE` condition, so it never compares against the token issued to the run
  (`m3_task_instance.fencing_token`) or against `lease_owner`; a stale owner whose lease
  was taken over can still commit. `beginRun` additionally reads the lease with a
  non-locking query and issues an unconditional `UPDATE`, so two concurrent workers can
  claim the same (non-monotonic) token. Also **I2**: after a raw upsert conflict the code
  keeps the freshly generated `raw_record_id` instead of re-reading the persisted one,
  so a job can reference a row that does not exist.
- `aquila-server/.../crawl/ProcessingJobWorker.java`, `JobLease.java`,
  `JobOutcome.java` — scans durable PENDING jobs and processes them; claims with a
  lease and a monotonically increasing fencing token; an expired lease can be
  reclaimed by another worker and the stale owner can no longer commit.
- `aquila-server/.../crawl/SourceController.java`, `SourceConfigDto.java`,
  `CoverageFactDto.java` — operations API: list sources, list coverage facts,
  trigger a fixture collection.
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

**Those green results are not acceptance evidence for the four blocking findings.**
No test exercises a stale-lease page commit, no test calls the existing-but-unused
`CrawlFixture.insertRevokedSource`, the fixture always emits `terminal ⇔ nextCursor == null`,
and `CoverageServiceTest` feeds `terminal/traceable/outcome` straight into `decidePageStatus`
without traversing the adapter or the transaction. Falsifying cases are listed in the review
report and must be written first (red) when the fixes land.

### Status

**`CHANGES_REQUIRED` — not closed.** An independent two-phase review of `ceae6fb` raised
2 Critical (C1 tautological page-commit fencing/CAS; C2 six permissions not checked
individually and not re-checked on the display path) and 8 Important findings, all
confirmed by direct code inspection. Closure preconditions and the required test additions
are in `.superpowers/sdd/2026-11-09-zero-based-stage-c/task-1-review-report.md`; the
register row `C-T1-01` is set to `CHANGES_REQUIRED` accordingly.

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
`FlywayMigrationTargetPolicy`. The local `.env` mirrors the canonical scoped env
`aquila-server/target/provision/env.ps1` (see §15).

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
