# Stage A Task 1 — Completion Report

**Status:** Implemented and committed  
**Commit:** `66c3cd1cc0082bf6e3b51ffef453350b58d72fb1` — `feat: establish greenfield baseline`  
**Branch/worktree:** `main` (the explicitly selected current worktree; no worktree was created)  
**Recorded:** 2026-11-10

## Scope and boundaries

- Recreated only the Task 1 application paths with new greenfield content. No historical application source was restored, copied, or used as an implementation input.
- Disclosure: during diagnosis, one broad diff command inadvertently displayed a small portion of prior frontend-file contents. It was not used as implementation input; no further historical-source reads were performed. This was an accidental breach of the requested inspection boundary.
- No old database was inspected, connected to, migrated, or written. No MySQL was installed.
- G01 is `N/A` only for this explicitly approved greenfield scope. The canonical plan and register cite the user’s explicit decision in this conversation and record it on 2026-11-10; they do not claim a separately verified approval timestamp, a G01 inventory exercise, or fabricated evidence.
- G02–G05 remain active. A-DR-01 is marked `N/A`, not completed as a G01 exercise.
- `schema-v1.md` is a logical model/ownership map only. It has no DDL or placeholder tables; B–F own later domain migrations.
- Database integration is opt-in and permits only `stock_greenfield_20261109`. It requires explicit URL/schema and non-empty credentials, rejects other schemas and implicit database creation, performs read-only checks, and does not run Flyway migrations.

## Changed files (33)

- `.gitignore`
- `aquila-server/pom.xml`
- `aquila-server/src/main/java/com/aquila/AquilaServerApplication.java`
- `aquila-server/src/main/java/com/aquila/common/Result.java`
- `aquila-server/src/main/java/com/aquila/common/TraceIdFilter.java`
- `aquila-server/src/main/java/com/aquila/common/GlobalExceptionHandler.java`
- `aquila-server/src/main/java/com/aquila/web/HealthController.java`
- `aquila-server/src/main/java/com/aquila/config/DatabaseTargetPolicy.java`
- `aquila-server/src/main/resources/application.yml`
- `aquila-server/src/test/java/com/aquila/common/ResultTest.java`
- `aquila-server/src/test/java/com/aquila/web/HealthControllerTest.java`
- `aquila-server/src/test/java/com/aquila/common/GlobalExceptionHandlerTest.java`
- `aquila-server/src/test/java/com/aquila/config/DatabaseTargetPolicyTest.java`
- `aquila-server/src/test/java/com/aquila/db/FlywayMySqlIntegrationTest.java`
- `aquila-server/src/test/java/com/aquila/web/SameOriginPackagingTest.java`
- `aquila-web/package.json`
- `aquila-web/package-lock.json`
- `aquila-web/index.html`
- `aquila-web/src/main.ts`
- `aquila-web/src/App.vue`
- `aquila-web/src/api/http.ts`
- `aquila-web/src/api/http.test.ts`
- `aquila-web/src/router/index.ts`
- `aquila-web/src/env.d.ts`
- `aquila-web/tsconfig.json`
- `aquila-web/vite.config.ts`
- `scripts/build.ps1`
- `docs/api/openapi.yaml`
- `docs/database/schema-v1.md`
- `docs/evidence/greenfield/README.md`
- `落地文档设计/Copilot/统一落地实施方案.md`
- `落地文档设计/Copilot/运行与验收登记表.md`
- `落地文档设计/Copilot/README.md`

Only these Task 1 paths were staged and committed. Pre-existing unrelated deletions, modifications, and untracked files remain in the worktree.

## TDD evidence

### Backend contracts

- **RED:** `mvn -q -f aquila-server/pom.xml '-Dtest=ResultTest,HealthControllerTest,GlobalExceptionHandlerTest,DatabaseTargetPolicyTest' test` — exit 1 during test compilation because the new production classes were missing (`Result`, `TraceIdFilter`, `HealthController`, `GlobalExceptionHandler`, `DatabaseTargetPolicy`).
- **GREEN:** the same command after implementation — exit 0.

### Database target policy guard

- **RED:** `mvn -q -f aquila-server/pom.xml -Dtest=DatabaseTargetPolicyTest test` — exit 1 because the configuration guard constructor and validation method did not yet exist.
- **GREEN:** the same command after implementation — exit 0; all five policy tests passed, including wrong schema, missing credentials, wrong JDBC schema, and implicit database creation rejection.

### Frontend API contract

- **RED:** `npm.cmd --prefix .\aquila-web test -- --run src/api/http.test.ts` — exit 1 because `src/api/http.ts` was not yet implemented.
- **GREEN:** the same command after implementation — 3 tests passed.

## Validation results

- `mvn -q -f aquila-server/pom.xml test` — exit 0; full Maven tests passed (the opt-in MySQL test is disabled outside its profile).
- `npm.cmd --prefix .\aquila-web test -- --run` — 3 tests passed.
- `npm.cmd --prefix .\aquila-web run typecheck` — passed.
- `npm.cmd --prefix .\aquila-web run openapi:lint` — passed. The command intentionally skips Redocly’s `info-license` rule (no license was specified) and `operation-4xx-response` rule (the health GET has no client input); the health operation explicitly declares no authentication.
- `npm.cmd --prefix .\aquila-web run build` — passed.
- `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build.ps1` — passed end to end: frontend checks/build, Maven clean package, and verification of the nested Spring Boot jar’s frontend entry point and assets. The unmodified invocation without `-ExecutionPolicy Bypass` was blocked by the machine’s script execution policy; the process-local bypass did not change persistent policy.
- Packaged-jar smoke test — `GET /` returned 200 with title `Aquila Stock`; `GET /api/health` returned 200 with the expected `UP` envelope and server-generated `X-Trace-Id`. The smoke-test process was stopped afterward.
- Final Maven package output: 11 tests, 0 failures, 0 errors, 1 skipped (the opt-in MySQL acceptance check).
- `mvn -f aquila-server/pom.xml -Pmysql-it -Dtest=FlywayMySqlIntegrationTest test` — command succeeded with the test explicitly skipped and message: `DB acceptance pending: provision the isolated stock_greenfield_20261109 schema and set all STOCK_MYSQL_IT_* variables.`

## Environment and unresolved gates

- Java runtime: `20.0.1`; Maven: `3.9.3`; Node: `23.11.0`; npm: `10.9.2`.
- Maven compiled with `--release 17` under Java 20. Java 17 runtime validation was not performed and is not claimed.
- No `mysql`/`mysqld` CLI or MySQL service was discoverable. `STOCK_MYSQL_IT_URL`, `STOCK_MYSQL_IT_SCHEMA`, `STOCK_MYSQL_IT_USER`, and `STOCK_MYSQL_IT_PASSWORD` were all unset.
- **DB acceptance remains pending:** no isolated MySQL 8 instance/schema or scoped credentials are provisioned, so server version, schema emptiness, and absence of Flyway history could not be verified against a database. The test did not connect anywhere.
- G02–G05 remain open as recorded in the canonical register; this task did not claim their evidence or acceptance.
- npm emitted non-blocking dependency deprecation notices during installation.

## Self-review

Reviewed the new API envelope/trace behavior, target-policy fail-closed checks, same-origin client and packaging path, OpenAPI contract, logical schema ownership map, and canonical G01/G02–G05/A-DR-01 records. No subagents or external database were used, per instruction.

## Review round 1 fix — missing-resource status

**Finding:** Spring `NoResourceFoundException` was handled by the generic `Exception` handler and returned HTTP 500 for an unavailable resource.  
**Fix commit:** `e186d008528433f32207779c2fd9df5def39ba94` — `fix: return 404 for missing API resources`

Added a specific `NoResourceFoundException` handler returning HTTP 404 and the safe envelope (`code: 404`, `message: "Resource not found"`, null data, trace ID from the server filter). The existing catch-all still returns HTTP 500 for unexpected exceptions.

### TDD and validation

- **RED:** `mvn -f aquila-server/pom.xml '-Dtest=GlobalExceptionHandlerTest' test` — failed as expected: `noResourceFoundExceptionReturnsNotFoundEnvelopeWithServerGeneratedTraceId` expected HTTP 404 but received 500. Maven reported 2 tests, 1 failure, 0 errors.
- **GREEN focused:** `mvn -f aquila-server/pom.xml '-Dtest=GlobalExceptionHandlerTest' test` — `BUILD SUCCESS`; 2 tests, 0 failures, 0 errors. Covers the not-found envelope and server-generated trace header/body ID, while preserving the unexpected-exception 500 test.
- **Backend suite:** `mvn -f aquila-server/pom.xml test` — `BUILD SUCCESS`; 12 tests, 0 failures, 0 errors, 1 skipped (opt-in MySQL acceptance remains pending).
- **Relevant full build:** `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build.ps1` — `BUILD SUCCESS`; frontend typecheck, 3 frontend tests, OpenAPI lint, Vite build, Maven package, and packaged frontend entry/assets verification passed.

No unrelated files were changed. The MySQL acceptance gate remains pending under the previously documented environment limits.
