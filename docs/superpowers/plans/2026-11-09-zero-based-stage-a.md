# Greenfield Baseline and Database Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish new frontend/backend roots, document the full logical data model, and verify an explicitly isolated MySQL 8 schema without touching historical code or databases.

**Architecture:** Spring Boot 3 serves the Vue 3/TypeScript build and `/api` from one origin. Flyway will build domain tables in later stages; A verifies the isolated target and creates no placeholder business tables.

**Tech Stack:** Java 17, Spring Boot 3, Maven, Vue 3, TypeScript, npm, MySQL 8, Flyway.

**Spec:** `docs/superpowers/specs/2026-11-09-zero-based-implementation-design.md`; roadmap: `docs/superpowers/plans/2026-11-09-zero-based-roadmap.md`.

## Global Constraints

- Work in the current worktree; create new implementation, never restore old source.
- Do not inspect/connect/migrate/write an old database; use only the separately provisioned `stock_greenfield_20261109` schema.
- Mark G01 and A-DR-01 `N/A` only with the user's recorded decision; G02–G05 remain active.
- Preserve `/api`, `Result<T>{code,message,data,traceId}`, same-origin UI, and OpenAPI parity.
- Do not add Docker/Compose, Redis, message brokers, microservices, or external models.
- All commits stage only reviewed stage files and include the required Copilot co-author trailer.

## Review Focus

- Wrong DB schema or missing credentials must fail before connecting; test target-policy validation.
- The new backend error path must not expose stack details; test error envelope and trace ID.
- API client must not send requests to an alternate host or fake healthy state; test relative `/api` routing and errors.
- Packaged jar must include the built frontend; test jar content and `/api/health`.
- Initial database verification must be read-only and MySQL 8-specific; test empty schema and no Flyway history.

---

### Task 1: New Project Roots and Greenfield Scope

**Files:**
- Modify: `落地文档设计/Copilot/统一落地实施方案.md`
- Modify: `落地文档设计/Copilot/运行与验收登记表.md`
- Modify: `落地文档设计/Copilot/README.md`
- Create: `.gitignore`
- Create: `aquila-server/pom.xml`
- Create: `aquila-server/src/main/java/com/aquila/AquilaServerApplication.java`
- Create: `aquila-server/src/main/java/com/aquila/common/Result.java`
- Create: `aquila-server/src/main/java/com/aquila/common/TraceIdFilter.java`
- Create: `aquila-server/src/main/java/com/aquila/common/GlobalExceptionHandler.java`
- Create: `aquila-server/src/main/java/com/aquila/web/HealthController.java`
- Create: `aquila-server/src/main/java/com/aquila/config/DatabaseTargetPolicy.java`
- Create: `aquila-server/src/main/resources/application.yml`
- Create: `aquila-server/src/test/java/com/aquila/common/ResultTest.java`
- Create: `aquila-server/src/test/java/com/aquila/web/HealthControllerTest.java`
- Create: `aquila-server/src/test/java/com/aquila/common/GlobalExceptionHandlerTest.java`
- Create: `aquila-server/src/test/java/com/aquila/config/DatabaseTargetPolicyTest.java`
- Create: `aquila-server/src/test/java/com/aquila/db/FlywayMySqlIntegrationTest.java`
- Create: `aquila-server/src/test/java/com/aquila/web/SameOriginPackagingTest.java`
- Create: `aquila-web/package.json`
- Create: `aquila-web/package-lock.json`
- Create: `aquila-web/index.html`
- Create: `aquila-web/src/main.ts`
- Create: `aquila-web/src/App.vue`
- Create: `aquila-web/src/api/http.ts`
- Create: `aquila-web/src/api/http.test.ts`
- Create: `aquila-web/src/router/index.ts`
- Create: `aquila-web/src/env.d.ts`
- Create: `aquila-web/tsconfig.json`
- Create: `aquila-web/vite.config.ts`
- Create: `scripts/build.ps1`
- Create: `docs/api/openapi.yaml`
- Create: `docs/database/schema-v1.md`
- Create: `docs/evidence/greenfield/README.md`

**Interfaces:**
- Produces: `Result<T>(int code, String message, T data, String traceId)`; `GET /api/health`; frontend `request<T>(path: string, init?: RequestInit): Promise<Result<T>>`; `DatabaseTargetPolicy.permits(String schemaName): boolean`.

- [x] **Step 1: Record greenfield decision and update canonical baseline**

Update unified plan to v1.4: G01 is `N/A` only because the owner chose a new project and isolated DB; prohibit old database access; keep G02–G05 unchanged. Record approver/date/source and rationale; mark A-DR-01 `N/A` without claiming inventory or a completed G01 exercise; sync README to v1.4.

- [x] **Step 2: Map all domain tables before DDL**

Create `docs/database/schema-v1.md` from unified §7.1. For each table document ownership, key constraints, references, retention class, and owning migration V1–V8. Do not copy historical DDL or add placeholder tables.

- [x] **Step 3: Write failing contract tests and build manifests**

Create minimal Maven/npm test harnesses. Test success/error envelopes, server-generated `X-Trace-Id`, `GET /api/health`, rejection of non-greenfield schema names, same-origin API behavior, malformed response rejection, and packaged frontend availability.

- [x] **Step 4: Run tests red**

Run: `mvn -f aquila-server/pom.xml -Dtest=ResultTest,HealthControllerTest,GlobalExceptionHandlerTest,DatabaseTargetPolicyTest test`

Expected: test compilation fails on missing implementation classes.

Run: `npm --prefix aquila-web test -- --run src/api/http.test.ts`

Expected: FAIL because the API client is not implemented.

- [x] **Step 5: Implement minimal app, target guard, and build integration**

Pin compatible Spring Boot 3.x and frontend dependencies. Configure the Vite `/api` proxy and a PowerShell build that packages generated frontend assets into the jar without committing generated files. Require the exact new schema name and scoped DB credentials; do not embed credentials or fall back to H2.

- [x] **Step 6: Verify the new database and full baseline**

Run `mvn -f aquila-server/pom.xml -Pmysql-it -Dtest=FlywayMySqlIntegrationTest test` against only the manually provisioned `stock_greenfield_20261109` schema. Assert server is MySQL 8, schema has no tables/history, and test performs no migration.

Run: `powershell -NoProfile -File scripts/build.ps1`

Expected: PASS; jar includes frontend entry point and health endpoint.

Run: `mvn -f aquila-server/pom.xml test`, `npm --prefix aquila-web test -- --run`, and `npm --prefix aquila-web run openapi:lint`

Expected: all PASS.

- [x] **Step 7: Save evidence and commit**

> Backfill (2026-11-15): these steps were left unchecked by an earlier documentation miss,
> not by unfinished work. The Stage A ledger records Task 1 complete (commits
> `d10a72e..2a5f3c6`, review clean, one review fix `e186d00`); the environment gates that
> were OPEN at the time (isolated MySQL 8 schema, Java 17 runtime) were closed by later
> stages — every stage since Stage B runs the full `-Pmysql-it` suite on the provisioned
> `stock_greenfield_20261109` schema under JDK 17.

Record versions, commands/results, schema identity, G01 rationale, and known external gates. Review status to ensure no historical files were restored. Commit only stage A files as `feat: establish greenfield baseline` with the required trailer.
