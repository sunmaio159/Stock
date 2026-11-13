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

- [ ] **Step 1: Write failing coverage and recovery tests**

Test complete, partial, unknown, unrecoverable; page-two failure after page-one success; transaction rollback preserving cursor; crash after commit before queue notification; repeated page idempotency; recent-N/untraceable source never COMPLETE.

- [ ] **Step 2: Add V3 source/coverage/raw schema**

Add policy permissions/version, subscriptions/version/effective range, source lease/fencing, run/page evidence, cursor version, raw revision identity and coverage rows with exact business interval and Adapter contract version.

- [ ] **Step 3: Implement SourcePolicy, Adapter, and transactional page pipeline**

Check ACCESS/STORE_RAW/STORE_NORMALIZED/ANALYZE/DISPLAY/EXPORT before each relevant operation. Fetch and parse outside transactions. Persist page raw, durable processing jobs, cursor CAS, and coverage state atomically.

- [ ] **Step 4: Verify MySQL page failure and recovery**

Run: `mvn -f aquila-server/pom.xml -Pmysql-it -Dtest=CoverageServiceTest,CrawlRecoveryIntegrationTest test`

Expected: PASS; first committed page remains PARTIAL until all required pages/termination evidence are proven.

### Task 2: Revisions, Fencing, Mentions, and Lifecycle Barrier

**Files:**
- Create: `aquila-server/src/main/java/com/aquila/governance/RevisionService.java`
- Create: `aquila-server/src/main/java/com/aquila/governance/LifecycleGuard.java`
- Create: `aquila-server/src/test/java/com/aquila/governance/RevisionOrderingIntegrationTest.java`
- Create: `aquila-server/src/test/java/com/aquila/governance/LifecycleRaceIntegrationTest.java`
- Create: `aquila-server/src/main/resources/db/migration/V4__document_processing.sql`
- Create: `aquila-web/src/views/PostsView.vue`
- Modify: `docs/api/openapi.yaml`
- Modify: `docs/database/schema-v1.md`
- Modify: `docs/evidence/greenfield/README.md`

**Interfaces:**
- Produces: `RevisionService.receive(ObservedDocument): DocumentRevision`; `ProcessingJobWorker.process(JobLease): JobOutcome`; conditional latest-observed/processed pointers and mention records per immutable revision.

- [ ] **Step 1: Add failing ordering and lifecycle-race tests**

Force R1 to finish after R2; change securities mentions during revision; expire/revoke while worker computes; assert lower-order pointer update and stale lifecycle commit are rejected.

- [ ] **Step 2: Add immutable revision and processing schema**

Persist source identity, revision order, raw references, business/published/observed timestamps, parser versions, current pointers, mentions, quality, durable jobs, retries/dead letters, change log, and lifecycle version.

- [ ] **Step 3: Implement ordered revision receive and worker fencing**

Prefer reliable source revision sequence; otherwise assign local observation order transactionally. Commit pointer advancement and affected dirty marks together; require current fencing token and lifecycle version before derived writes.

- [ ] **Step 4: Verify FT-02/FT-03 and commit**

Run: `mvn -f aquila-server/pom.xml -Pmysql-it -Dtest=RevisionOrderingIntegrationTest,LifecycleRaceIntegrationTest test`

Expected: PASS with no pointer rollback or post-revocation resurrection. Run full backend tests, frontend tests/build, OpenAPI lint; update G02/COV-01/LIFE-01 evidence. Commit `feat: add immutable revisions and lifecycle guards` with required trailer.
