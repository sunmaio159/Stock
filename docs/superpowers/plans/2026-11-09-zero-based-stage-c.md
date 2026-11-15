# Source Ingestion and Governance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement licensed, recoverable single-source ingestion with measurable coverage, immutable content revisions, and race-safe processing.

**Architecture:** A versioned Adapter produces bounded pages. Each page transaction commits raw evidence, durable jobs, cursor CAS, and coverage facts; workers use leases/fencing and lifecycle versions. Fixtures are used until G02 authorizes a real source.

**Tech Stack:** Java 17, Spring Boot 3, MySQL 8, Flyway, Vue 3, TypeScript.

**Spec:** `docs/superpowers/specs/2026-11-09-zero-based-implementation-design.md`; roadmap: `docs/superpowers/plans/2026-11-09-zero-based-roadmap.md`; prerequisites: Stages A and B.

## Global Constraints

- Do not access any real source before G02 approval; distinguish fixture evidence from source-probe evidence.
- Do not declare `COMPLETE` unless Adapter evidence proves the entire interval.
- Preserve cursor/page/raw/job atomicity, fencing tokens, immutable revisions, and lifecycle commit barriers.
- Keep network and parsing outside DB lock transactions; tasks and queues are durable/bounded.
- Test DB behavior on MySQL 8 and use only the greenfield schema.
- Every commit includes required co-author trailer and only reviewed stage files.

## Review Focus

- Page N failure after earlier page commits remains PARTIAL and resumable; test page-level transaction interruption.
- Crash after DB commit but before in-memory notification cannot lose work; test durable scan recovery.
- Stale lease worker cannot commit; test fencing token expiry.
- R1 completing after R2 cannot move pointers or derived state backward; test forced worker order.
- Revocation/expiry during calculation rejects commit and cannot resurrect derived data; test lifecycle race.

---

### Task 1: Source Policy, Adapter, Coverage, Raw, and Durable Jobs

**Files:**
- Create: `aquila-server/src/main/java/com/aquila/sourcepolicy/SourcePolicy.java`
- Create: `aquila-server/src/main/java/com/aquila/crawl/SourceAdapter.java`
- Create: `aquila-server/src/main/java/com/aquila/crawl/FixtureSourceAdapter.java`
- Create: `aquila-server/src/main/java/com/aquila/crawl/CoverageService.java`
- Create: `aquila-server/src/main/java/com/aquila/crawl/CrawlApplicationService.java`
- Create: `aquila-server/src/main/java/com/aquila/crawl/ProcessingJobWorker.java`
- Create: `aquila-server/src/main/resources/db/migration/V3__source_coverage_raw.sql`
- Create: `aquila-server/src/test/java/com/aquila/crawl/CoverageServiceTest.java`
- Create: `aquila-server/src/test/java/com/aquila/crawl/CrawlRecoveryIntegrationTest.java`
- Create: `aquila-web/src/views/SourceOperationsView.vue`
- Modify: `docs/api/openapi.yaml`
- Modify: `docs/database/schema-v1.md`
- Modify: `docs/evidence/greenfield/README.md`

**Interfaces:**
- Consumes: Stage B securities identifiers and authenticated admin.
- Produces: `SourceAdapter.fetch(SourceFetchRequest): SourcePage`; `CoverageService.commitPage(PageCommit): CoverageState`; durable policy/source/run/page/cursor/raw/job and Adapter-evidence tables.

- [x] **Step 1: Write failing coverage and recovery tests**

Test complete, partial, unknown, unrecoverable; page-two failure after page-one success; transaction rollback preserving cursor; crash after commit before queue notification; repeated page idempotency; recent-N/untraceable source never COMPLETE.

- [x] **Step 2: Add V3 source/coverage/raw schema**

Add policy permissions/version, subscriptions/version/effective range, source lease/fencing, run/page evidence, cursor version, raw revision identity and coverage rows with exact business interval and Adapter contract version.

- [x] **Step 3: Implement SourcePolicy, Adapter, and transactional page pipeline**

Check ACCESS/STORE_RAW/STORE_NORMALIZED/ANALYZE/DISPLAY/EXPORT before each relevant operation. Fetch and parse outside transactions. Persist page raw, durable processing jobs, cursor CAS, and coverage state atomically.

- [x] **Step 4: Verify MySQL page failure and recovery**

Run: `mvn -f aquila-server/pom.xml -Pmysql-it -Dtest=CoverageServiceTest,CrawlRecoveryIntegrationTest test`

Expected: PASS; first committed page remains PARTIAL until all required pages/termination evidence are proven.

**Completed and accepted 2026-11-15.** The Step 4 command passed at implementation time (10/10), but that green result was explicitly not acceptance evidence: the first independent review of `ceae6fb` raised 2 Critical + 8 Important findings, and rounds 2 and 3 each returned `CHANGES_REQUIRED` again. The round-4 fix commit `0517dcc` was reviewed independently a fourth time over `03edf36..0517dcc` with verdict **APPROVED** (`.superpowers/sdd/2026-11-09-zero-based-stage-c/task-1-review-report-round4.md`): C1, C2, I1, I2 and F-1–F-5 all closed with falsifying tests, G-1 anchored on the persisted `page_terminal` flag, G-3–G-6 addressed. Round-4 verification: empty-schema gate 1/0, full `-Pmysql-it` suite 148/148 on real MySQL 8.4 (V3 replayed to v3), frontend four gates, same-origin clean jar. Commits `ceae6fb`, `c113601`, `03edf36`, `0517dcc` each carry the required co-author trailer and stage only Stage C Task 1 paths. Registry `C-T1-01` → `PASSED`. **Stage C Task 1: ACCEPTED / CLOSED.** G02 remains OPEN (fixture adapter only); the `m3_processing_job` naming and I3–I8 Task 1/Task 2 attribution were resolved by owner signature 2026-11-15 (A1/B1/C1, recorded in `docs/evidence/greenfield/README.md` §Stage C Task 1 — 1.x fix) and are no longer unsigned owner-governance decisions.

### Task 2: Revisions, Fencing, Mentions, and Lifecycle Barrier

**Files (as delivered 2026-11-15):**
- Create: `aquila-server/src/main/resources/db/migration/V4__document_processing.sql`
- Create: `com/aquila/governance/`: `RevisionService`, `LifecycleGuard`, `ObservedDocument`, `DocumentRevision`, `DocRef`, `LifecycleBlockedException`
- Create: `com/aquila/governance/` tests: `GovernanceFixture`, `RevisionOrderingIntegrationTest`, `LifecycleRaceIntegrationTest`, `GovernanceConstraintIntegrationTest`
- Modify: `aquila-server/src/main/java/com/aquila/crawl/ProcessingJobWorker.java`
- Modify: `docs/database/schema-v1.md`, `docs/evidence/greenfield/README.md`, this plan
- As-built spec (git-ignored, not staged): `.superpowers/sdd/2026-11-09-zero-based-stage-c/task-2-brief.md`

**Scope note (2026-11-15, owner confirmation pending):** the Stage A draft also listed
`aquila-web/src/views/PostsView.vue` and `docs/api/openapi.yaml` above. None of the four Task 2
Steps defines an HTTP endpoint or a UI action, and the owner's Task 2 spec (V4 DDL +
`com.aquila.governance` services + FT-02/FT-03) contains neither. `GET /api/sentiment/posts`
belongs to the Stage D aggregation/workbench design
(`落地文档设计/Copilot/阶段实施详设/D_情绪聚合与工作台.md`), which Task 2 explicitly defers to
`V5`/`V6`. These two files are therefore **not** counted as Task 2 acceptance gaps and are
deferred to Stage D; no product API or UI was invented here. The lines are kept until the owner
confirms the removal, so the discrepancy stays visible rather than silently disappearing.

**V4 boundary (owner decision 2026-11-15):** `V4__document_processing.sql` is the as-built home of the `m4_normalized_doc` / `m4_doc_revision` / mention / quality / lifecycle-version tables. The logical ownership map's `V5 — C` labels for these were a Stage A pre-allocation and are now annotated `as built; was V5`; the physical chain is V1–V2 B, V3–V4 C, V5–V6 D, V7 E, V8 F. Dead-letter is carried by the V3 `m3_processing_job` (no separate table). Stage D's sentiment/analysis tables are `V5`/`V6` (map labelled `V7 — D`), Stage E's alerts `V7` (map `V8 — E`), Stage F `V8`.

**As-built spec (owner-requested alignment D4, 2026-11-15):** the eight points where the original Task 2 spec was underspecified or physically impossible (composite-FK unique key, atomic job-state CHECK, job→revision carrier + orphan rule, current-read race seam, immutable-revision enforcement, dirty-flag/marker contract, marker idempotency, nullable cycle FK) are closed and recorded in `.superpowers/sdd/2026-11-09-zero-based-stage-c/task-2-brief.md` §4 and `docs/evidence/greenfield/README.md` (D4). `GovernanceConstraintIntegrationTest` is the falsifying suite for that alignment. Step 2 below should be read together with the brief; `V4__document_processing.sql` is the physical authority.

**Interfaces:**
- Produces: `RevisionService.receive(ObservedDocument): DocumentRevision`; `ProcessingJobWorker.process(JobLease): JobOutcome`; conditional latest-observed/processed pointers and mention records per immutable revision.

- [ ] **Step 1: Add failing ordering and lifecycle-race tests**

Force R1 to finish after R2; change securities mentions during revision; expire/revoke while worker computes; assert lower-order pointer update and stale lifecycle commit are rejected.

- [ ] **Step 2: Add immutable revision and processing schema (V4 boundary)**

Persist in `V4__document_processing.sql`, as built (the logical ownership map in `docs/database/schema-v1.md` pre-allocated these to V5 and now carries `as built; was V5` annotations): source identity, revision order, raw references, business/published/observed timestamps, parser versions, current pointers, mentions, quality, and lifecycle version. Reuse/extend the V3 `m3_processing_job` for durable jobs, retries, and dead letters — it already carries `DEAD_LETTER` state plus `retry_count` and `last_error`, so **no separate dead-letter table** is created (owner decision 2026-11-15). Do **not** build the full commit-ordered change log or aggregation windows here; those belong to Stage D (`V5`/`V6`). Keep all V4 tables in a single migration to avoid cross-migration FK ordering, referencing the already-existing `m3_raw_record` (+ partition date) and `m2_security` from V1–V3.

- [ ] **Step 3: Implement ordered revision receive and worker fencing**

Prefer reliable source revision sequence; otherwise assign local observation order transactionally. Commit pointer advancement and affected dirty marks together; require current fencing token and lifecycle version before derived writes.

- [ ] **Step 4: Verify FT-02/FT-03 and commit**

Run: `mvn -f aquila-server/pom.xml -Pmysql-it -Dtest=RevisionOrderingIntegrationTest,LifecycleRaceIntegrationTest test`

Expected: PASS with no pointer rollback or post-revocation resurrection. Run full backend tests, frontend tests/build, OpenAPI lint; update G02/COV-01/LIFE-01 evidence. Commit `feat: add immutable revisions and lifecycle guards` with required trailer.
