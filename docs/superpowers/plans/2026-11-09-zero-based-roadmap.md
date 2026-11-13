# Zero-Based Stock Full Rebuild Roadmap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement the stage plans task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the complete Stock/Aquila frontend, backend, and business database from scratch against an isolated MySQL 8 database, delivering the unified V1 product without restoring historical source or touching an old database.

**Architecture:** A Java 17 / Spring Boot 3 modular monolith serves the Vue 3 / TypeScript application and `/api` from one origin. Flyway migrations build the complete schema in a new database; each stage is a separately testable vertical slice. Real-source, production-backup, and deployment capabilities remain disabled until their gates pass.

**Tech Stack:** Java 17, Spring Boot 3, Maven, Vue 3, TypeScript, npm, MySQL 8, Flyway.

**Spec:** `docs/superpowers/specs/2026-11-09-zero-based-implementation-design.md`; authoritative product/acceptance baseline to update before implementation: `落地文档设计/Copilot/统一落地实施方案.md` and `落地文档设计/Copilot/运行与验收登记表.md`.

## Global Constraints

- Implement new source in the current worktree; never restore historical application files.
- Create and use only a newly provisioned, explicitly identified MySQL 8 database/schema; do not connect to, inspect, migrate, or write to an old database.
- Record G01 as `N/A` only for this greenfield scope, with the user's decision and rationale; G02–G05 and all data-correctness gates remain active.
- Use Java 17, Spring Boot 3, Vue 3, TypeScript, and MySQL 8; do not add Docker/Compose, Redis, message brokers, microservices, or external models.
- Preserve `/api`, `Result<T>{code,message,data,traceId}`, Cookie sessions, decimal-string BIGINT IDs, Asia/Shanghai business times, and OpenAPI/DTO/test parity.
- Use versioned Flyway SQL; never edit an applied migration; verify DDL, transactions, locks, and indexes on MySQL 8, not H2/mock alone.
- For initial local/test migrations, use only the explicitly provisioned `stock_greenfield_20261109` schema with scoped credentials; use separate migration/runtime users, with no DDL privilege for runtime.
- Do not access a real source before G02 permits it; use clearly identified fixtures before then. Do not enable formal backups/releases before G04 and all applicable release gates pass.
- Do not claim a gate, test, source capability, deployment target, or recovery behavior passed without real evidence and named confirmation.
- Every commit includes `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>` and stages only that stage's reviewed files.

## Review Focus

- G01 scope exclusion must not authorize old-database access; stage A rejects unset/unsafe targets before opening a connection.
- TOTP/recovery/session one-time operations remain atomic under concurrency; stage B tests concurrency and restart.
- Partial pagination, cursor commit, fencing-token expiry, and coverage completeness cannot produce false complete windows; stage C injects failures.
- Older revisions and ambiguous multi-security sentiment cannot roll back current content or directional aggregation; stages C/D test ordering and eligibility.
- Retention/revocation and version publication races cannot resurrect deleted content or publish mixed/stale results; stages C/D/F test worker races and bounded barrier failure.

---

## Stage plans and dependencies

Execute sequentially; do not start a later stage until its predecessor's tests pass and its gate disposition is recorded. A blocked external gate may leave its related production capability disabled while fixture-based work proceeds.

1. [Stage A — Greenfield baseline and database](./2026-11-09-zero-based-stage-a.md): resolve G01 as `N/A` for this new project, define the full logical schema map, and establish same-origin app plus isolated empty MySQL 8.
2. [Stage B — Identity and master data](./2026-11-09-zero-based-stage-b.md): authentication/recovery, securities, watchlists, import/export foundations.
3. [Stage C — Source ingestion and governance](./2026-11-09-zero-based-stage-c.md): policy, coverage, raw/revisions, durable jobs, fencing, lifecycle.
4. [Stage D — Sentiment and workbench](./2026-11-09-zero-based-stage-d.md): versioned analysis, aggregation eligibility, catch-up, publication.
5. [Stage E — Alerts and operations](./2026-11-09-zero-based-stage-e.md): coverage-aware alerts, silence, corrections, replay/dead letters.
6. [Stage F — Retention and release](./2026-11-09-zero-based-stage-f.md): cleanup, export safety, native deployment, backup/restore, final disposition.

Each stage plan identifies its files, interfaces, tests, and commit. Database physical design is recorded in `docs/database/schema-v1.md`; V1–V8 migrations are introduced with their owning stage, not as placeholder tables. G02 source approval, G03 target-device validation, G04 backup decision/recovery, and G05 alert-quality parameters remain active stage gates.

## Execution and Review

Use the `superpowers:subagent-driven-development` workflow: one implementation agent per task, independent review before proceeding, and no parallel edits to shared API, migration, or configuration files. After Stage F, perform one whole-project review and run the complete backend, frontend, OpenAPI, MySQL 8 integration, and selected-device acceptance suites.
