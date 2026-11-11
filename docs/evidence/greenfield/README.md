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
during this implementation; database acceptance remains pending.

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
accessed for this run. MySQL acceptance remains incomplete; the independent
Task 1 review has since been completed (see below).

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
Minor findings M-1..M-10 are registered as carried-forward items.

Resumption ruling: Stage B Task 2 may proceed on fixture/pure-logic work while
the MySQL 8 external gate is blocked. `STOCK_DB_ENABLED` and
`STOCK_FLYWAY_ENABLED` stay false; no schema is connected; V2 is authored but
not executed; G02–G05 remain active. The 12 database-backed acceptance cases
remain **OPEN / not passed**, and Task 1 must not be described as accepted.

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

The 12 skips are 7 `AuthFlowIntegrationTest`, 4 `SessionSecurityTest` and 1
`FlywayMySqlIntegrationTest`, all requiring the unprovisioned isolated MySQL 8
target. They are **not** acceptance passes. MySQL 8 acceptance remains OPEN;
G02–G05 remain active. No database was accessed.

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

The commit identifier for these review-fix changes is recorded in the Stage B
SDD ledger rather than here, so this file does not become stale after commit.
