# Identity and Master Data Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the single-admin authentication/recovery journey and securities, watchlist, hotlist, and import features on the new schema.

**Architecture:** Application use cases own authorization and transactions; MySQL 8 enforces one-time consumption, capacity, uniqueness, ordering, and idempotency. Vue screens use the shared typed API client.

**Tech Stack:** Java 17, Spring Boot 3, Maven, Vue 3, TypeScript, npm, MySQL 8, Flyway.

**Spec:** `docs/superpowers/specs/2026-11-09-zero-based-implementation-design.md`; roadmap: `docs/superpowers/plans/2026-11-09-zero-based-roadmap.md`; prerequisite: Stage A.

## Global Constraints

- Never connect to or migrate an old database; use only the isolated MySQL 8 schema from Stage A.
- Preserve API envelope, Cookie sessions, CSRF/Origin enforcement, stable security identifiers, and OpenAPI parity.
- Keep secrets out of logs, command history, database plaintext, and frontend storage.
- Freeze authentication/security parameters with PO before implementation and record evidence.
- Runtime DB credentials have no DDL privileges; use separate migration credentials.
- All commits stage only reviewed stage files and include the required Copilot co-author trailer.

## Review Focus

- Bootstrap must be single-use even under concurrent requests; test transaction race.
- TOTP step and recovery code cannot be replayed; test concurrent reuse.
- Session revocation takes effect after process restart and on each request; test persistence.
- CSRF protects login and ordinary writes; test anonymous flow and rejected Origin.
- Concurrent watchlist adds cannot exceed configured distinct-security capacity; test MySQL transaction lock.

---

### Task 1: Authentication, Sessions, and Security Controls

**Files:**
- Create: `aquila-server/src/main/java/com/aquila/auth/AuthController.java`
- Create: `aquila-server/src/main/java/com/aquila/auth/BootstrapApplicationService.java`
- Create: `aquila-server/src/main/java/com/aquila/auth/LocalAdminCommand.java`
- Create: `aquila-server/src/main/java/com/aquila/auth/TotpService.java`
- Create: `aquila-server/src/main/java/com/aquila/auth/RecoveryCodeService.java`
- Create: `aquila-server/src/main/java/com/aquila/auth/SessionService.java`
- Create: `aquila-server/src/main/java/com/aquila/auth/CsrfFilter.java`
- Create: `aquila-server/src/main/java/com/aquila/config/SecurityConfiguration.java`
- Create: `aquila-server/src/main/resources/db/migration/V1__identity_schema.sql`
- Create: `aquila-server/src/test/java/com/aquila/auth/AuthFlowIntegrationTest.java`
- Create: `aquila-server/src/test/java/com/aquila/auth/LocalAdminCommandTest.java`
- Create: `aquila-server/src/test/java/com/aquila/auth/SessionSecurityTest.java`
- Create: `aquila-web/src/views/LoginView.vue`
- Create: `aquila-web/src/views/SecuritySettingsView.vue`
- Modify: `docs/api/openapi.yaml`
- Modify: `docs/database/schema-v1.md`
- Modify: `docs/evidence/greenfield/README.md`

**Interfaces:**
- Consumes: Stage A `Result<T>`, `DatabaseTargetPolicy`, API client, OpenAPI.
- Produces: `BootstrapApplicationService.confirm(BootstrapConfirmation): AdminSession`; `SessionService.login(LoginRequest): AdminSession`; `SessionService.revoke(String sessionId): void`; persisted admin/session/recovery/bootstrap/reauth/audit tables.

- [x] **Step 1: Record the owner-approved identity parameter freeze**

The owner approved these values on 2026-11-10; record them in the greenfield evidence README before coding and use them verbatim:

- Argon2id password hashing: 19 MiB memory, 2 iterations, parallelism 1.
- RFC 6238 TOTP: 30-second period, 6 digits, ±1 step.
- Recovery codes: 10 independently random 128-bit codes per batch, Base32 display, shown once, hashes only at rest.
- Persistent session cookie: HttpOnly, SameSite=Strict, Path=/, no Domain, Max-Age 365 days; no server-side absolute or idle expiry; logout/revocation invalidates it. Follow the unified plan's transport-specific Secure setting.
- Login failure limit: at most 10 failed attempts per administrator in a rolling 5-minute window; after reaching the limit, reject attempts until failures age out. No additional lock period; persist state across restart.

Do not silently substitute framework defaults or change these choices. Any later change requires the plan's documented decision/change process.

- [x] **Step 2: Add failing auth and security tests**

Cover first bootstrap, duplicate bootstrap, TOTP replay, recovery single-use race, session restart/revoke, pre-login CSRF, missing/wrong Origin, rate limits, and CLI secret redaction.

- [x] **Step 3: Add V1 identity migration**

Create `sys_user`, session token hashes, recovery-code batches, bootstrap/reauth context, rate-limit state, and audit structures with unique keys and expiry fields. Use encrypted TOTP secret storage and never store plaintext recovery codes.

- [x] **Step 4: Implement auth flows**

Implement local bootstrap command, restricted bootstrap/recovery contexts, login/logout, TOTP setup/confirm, code rotation, persistent session checking/revoke, CSRF and reauthentication; one-time state changes commit atomically.

- [x] **Step 5: Implement frontend and OpenAPI**

Build login and security settings states; keep session token inaccessible to JS and handle loading, invalid credentials, locked/rate-limited, recovery, and rebind outcomes explicitly.

- [x] **Step 6: Verify and commit**

Run: `mvn -f aquila-server/pom.xml -Pmysql-it -Dtest=AuthFlowIntegrationTest,LocalAdminCommandTest,SessionSecurityTest test`

Expected: PASS on MySQL 8, including concurrent one-time consumption. Run full backend tests, frontend tests/build, and OpenAPI lint. Commit `feat: add single-admin authentication` with required trailer.

**Completed and accepted 2026-11-13.** Step 6 command on real MySQL 8.4: 14 tests / 0 failures / 0 errors / 0 skipped, BUILD SUCCESS. Full backend (`-Pmysql-it`, excluding the mutually exclusive empty-schema check): 71 tests / 0 failures / 0 errors / 0 skipped. The empty-schema check ran first as a separate batch (1/1 PASS). Frontend typecheck, 5/5 vitest, OpenAPI lint, Vite build, `mvn package` and the same-origin jar check all passed. Commits `1fa0a68`, `1b35166`, `e0d5748` carry the required co-author trailer. The independent review is closed with I-1..I-5 fixed and M-1..M-10 individually dispositioned. **Stage B Task 1: ACCEPTED / CLOSED.** Evidence: `docs/evidence/greenfield/README.md` and `.superpowers/sdd/2026-11-09-zero-based-stage-b/task-1-review-report.md`.

### Task 2: Securities, Watchlists, Hotlists, and Import

**Files:**
- Create: `aquila-server/src/main/java/com/aquila/masterdata/SecurityController.java`
- Create: `aquila-server/src/main/java/com/aquila/masterdata/SecurityService.java`
- Create: `aquila-server/src/main/java/com/aquila/masterdata/ImportController.java`
- Create: `aquila-server/src/main/java/com/aquila/masterdata/ImportService.java`
- Create: `aquila-server/src/main/java/com/aquila/watchlist/WatchlistController.java`
- Create: `aquila-server/src/main/java/com/aquila/watchlist/WatchlistService.java`
- Create: `aquila-server/src/main/resources/db/migration/V2__masterdata_watchlist_schema.sql`
- Create: `aquila-server/src/test/java/com/aquila/masterdata/ImportIdempotencyTest.java`
- Create: `aquila-server/src/test/java/com/aquila/watchlist/WatchlistConcurrencyTest.java`
- Create: `aquila-web/src/views/WatchlistView.vue`
- Create: `aquila-web/src/views/HotlistView.vue`
- Create: `aquila-web/src/views/ImportPreviewView.vue`
- Modify: `docs/api/openapi.yaml`
- Modify: `docs/database/schema-v1.md`

**Interfaces:**
- Consumes: authenticated principal and session/CSRF services from Task 1.
- Produces: `SecurityService.search(String keyword, int page, int size): Page<SecurityDto>`; `WatchlistService.add(String groupId, String securityCode, String idempotencyKey): WatchlistItem`; `ImportService.commit(String batchId, String idempotencyKey): ImportResult`.

- [x] **Step 1: Add failing data and concurrency tests**

Test canonical `SH:600000`/`SZ:000001` parsing, alias resolution, invalid market/code, distinct-security capacity across groups, duplicate item conflict, sort expectedVersion conflict, hotlist/candidate separation, import row errors, idempotent commit, and concurrent capacity race.

- [x] **Step 2: Add V2 master-data migration**

Create securities/aliases/calendar, watchlist groups/items/preferences, hotlist, capacity lock, import batches/rows and idempotency tables with unique keys and pagination/sort indexes. Use the approved table map.

- [x] **Step 3: Implement APIs and application services**

Implement search, group/item CRUD, reorder with expected version, manual hotlist separate from candidates, import preview and atomic watchlist commit. Candidate ranking remains unavailable until eligible source coverage exists.

- [x] **Step 4: Implement screens and validate contract**

Add search, watchlist, hotlist, and import preview/commit/error states; update OpenAPI and client DTOs.

- [x] **Step 5: Verify and commit**

Run: `mvn -f aquila-server/pom.xml -Pmysql-it -Dtest=ImportIdempotencyTest,WatchlistConcurrencyTest test`

Expected: PASS. Run full backend tests, frontend tests/build, and OpenAPI lint. Commit `feat: add securities and watchlists` with required trailer.

Acceptance 2026-11-14: Step 5 command and full backend suite 113/113 PASS on real MySQL 8; frontend typecheck, vitest 5/5, OpenAPI lint, Vite build, `mvn package` and same-origin jar check PASS. Two-stage independent review closed 3 Critical and 9 Important findings with a clean re-review; dispositions recorded in `docs/evidence/greenfield/README.md` §Stage B Task 2 closure. Committed as `feat: add securities and watchlists` with the required Copilot co-author trailer.

Stage B closure 2026-11-14: the Flyway / MySQL 8.4 compatibility caveat is resolved. The owner chose an empirical upgrade; Flyway 12.11.0 proved incompatible with Spring Boot 3.4.5, while 11.20.3 is now pinned in `aquila-server/pom.xml`. The full backend suite is 113/113 PASS on real MySQL 8.4 with no untested-version warning, verified without a command-line override. See `docs/evidence/greenfield/README.md` §Flyway / MySQL 8.4 compatibility resolution.
