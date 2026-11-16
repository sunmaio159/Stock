# Versioned Sentiment and Workbench Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build deterministic document sentiment, eligible stock aggregation, online analysis-version catch-up, and a version-consistent workbench.

**Architecture:** Immutable algorithm/dictionary/aggregation snapshots produce document-revision results and version-keyed windows. A persisted, commit-ordered input log drives backfill and catch-up; active version switches only after validation and a bounded CAS barrier.

**Tech Stack:** Java 17, Spring Boot 3, MySQL 8, Flyway, Vue 3, TypeScript.

**Spec:** `docs/superpowers/specs/2026-11-09-zero-based-implementation-design.md`; roadmap: `docs/superpowers/plans/2026-11-09-zero-based-roadmap.md`; prerequisites: Stages A–C.

## Global Constraints

- Unknown sentiment is NULL, never silently neutral; score range is [-100,100], confidence [0,1].
- Document-level sentiment is not per-security stance; persist eligibility and exclusion reason for ambiguous multi-security content.
- Queries read exactly one active analysis version and expose comparable range/evidence metadata.
- Input change watermarks are commit-ordered and continuous; do not treat max auto-increment ID as a committed watermark.
- Bound release barrier duration and queue; failure releases barrier and retains prior active version.
- Use only MySQL 8 greenfield schema and include required co-author trailer on commits.

## Review Focus

- Unknown terms/low confidence remain distinguishable from neutral; test NULL vs zero.
- Opposite-stance multi-security articles are not directionally counted for each symbol; test persisted eligibility.
- Concurrent/reordered input commits cannot be skipped by catch-up; test commit-ordered watermark.
- Failed/timeout publication cannot move active pointer; test state and rollback.
- Versioned history cannot be silently spliced; test query metadata and one-version reads.

---

### Task 1: Versioned Sentiment and Aggregation

**Files:**
- Create: `aquila-server/src/main/java/com/aquila/sentiment/SentimentEngine.java`
- Create: `aquila-server/src/main/java/com/aquila/sentiment/AggregationService.java`
- Create: `aquila-server/src/main/resources/db/migration/V5__sentiment_versions_and_windows.sql`
- Create: `aquila-server/src/test/java/com/aquila/sentiment/SentimentEngineTest.java`
- Create: `aquila-server/src/test/java/com/aquila/sentiment/AggregationEligibilityTest.java`
- Create: `aquila-web/src/views/DictionaryView.vue`
- Modify: `docs/api/openapi.yaml`
- Modify: `docs/database/schema-v1.md`
- Modify: `docs/evidence/greenfield/README.md`

**Interfaces:**
- Consumes: Stage C immutable document revisions, mentions, lifecycle and coverage evidence.
- Produces: `SentimentEngine.evaluate(DocumentRevision, DictionarySnapshot): SentimentResult`; `AggregationService.rebuild(WindowKey, AnalysisVersion): WindowResult`.

**Scope note (2026-11-15, mechanism layer):** Step 1 is **not signed** — unified plan
§9.1/§9.2 make the labelled evaluation set, label/score mapping, confidence interpretation,
low-confidence threshold, multi-security eligibility rules and aggregation weights/decay
PO decisions. The current pass delivers only the mechanism that consumes those values as
explicit versioned inputs, plus `V5__sentiment_versions_and_windows.sql` and three test
classes. No business dictionary, threshold or weight was invented, no production version
rows were inserted, no online publication was enabled, and Steps 1–5 stay `[ ]`.
`DictionaryView.vue` and `docs/api/openapi.yaml` remain listed above but are **deferred**
(no Step defines an endpoint/UI action and no frozen dictionary content exists yet) — same
treatment as Stage C's `PostsView.vue`; no product API/UI was invented. Interface deviation
(`DocumentRevision` carries `body_hash` + raw reference, not text) and the full as-built
record: `.superpowers/sdd/2026-11-09-zero-based-stage-d/task-1-brief.md`.

**Scope note addendum (2026-11-15, later pass):** owner then asked to widen only the
parameter-independent persistence/constraint coverage and leave the parameter decisions for
last. That pass added `com.aquila.sentiment.SentimentStore` (parameter-free persistence of
per-revision results and version-keyed windows, so the brief's "逐文结果持久化服务" gap is
closed) plus `SentimentPersistenceIntegrationTest`, and widened
`SentimentSchemaIntegrationTest` from 6 to 12 cases. No PO value, table or migration changed;
Step 1 is still unsigned and Steps 1–5 stay `[ ]`. Details: brief §6.

**Scope note addendum 2 (2026-11-15, review-fix pass):** the independent review of
`5f1d10f..86b0638` returned "Ready to merge: No" with 3 Critical / 8 Important / 3 Minor.
All codeable findings were fixed in one pass (commits listed in the evidence register):
title/body weights, the confidence interpretation and aggregation weighting/decay became
explicit versioned inputs (`AlgorithmSnapshot` + `ConfidenceStrategy` + `SampleWeighting` +
`DecayPolicy`) instead of hidden 1:1/linear/arithmetic choices (C-1); window membership
[E-duration, E) and canonical-group dedup are enforced by `AggregationService` and window
ends must align to the 15-minute grid (C-2); the only production entry point for a document
result is now `SentimentAnalysisService`, which runs the full Stage C lifecycle/content/
revision-status barrier in the same transaction, and the store's `persistResult` is
package-private (C-3); CJK terms match continuous Chinese text while ASCII terms keep word
boundaries (I-1); version-identity columns are binary-collated via
`V6__sentiment_identity_hardening.sql` (I-2, V5 stays untouched); numeric payloads are
normalised to column precision (I-3); the duplicate-key readback is a current read (I-4);
a zero-eligible window cannot carry any score (I-5, V6 CHECK); uncalibrated policies are
unconditionally EXPERIMENTAL (I-6); analysis versions cannot mix policy versions (I-7);
dictionary snapshots are deeply immutable (I-8); EXCLUDED is no longer miscounted as
ambiguous (M-1); duplicate security codes are rejected (M-2); the upsert uses row aliases
(M-3). Step 1 is still unsigned and Steps 1–5 stay `[ ]`; `DictionaryView.vue` /
`openapi.yaml` remain deferred; the as-built contract (results require PROCESSED revisions)
and falsification evidence: brief §7.

- [ ] **Step 1: Freeze analysis and aggregation parameters**

PO approves label/score mapping, confidence interpretation, multi-security eligibility rules and aggregation policy version; record a fixed labeled evaluation set.

- [ ] **Step 2: Add failing engine/aggregation tests**

Test score bounds [-100,100], confidence [0,1], unknown NULL vs neutral zero, deterministic dictionary version, repeatable output, and opposite-stance/ambiguous documents retained as mentions but excluded from directional stock aggregation with reason.

- [ ] **Step 3: Add V5 sentiment schema**

Create immutable dictionary/algorithm/aggregation versions, document-revision sentiment result, eligibility/exclusion reason, unified version-keyed windows, dirty-window generation, and required access-path indexes.

- [ ] **Step 4: Implement engine and aggregation**

Use explicit versioned inputs and no external model. Persist one document-level result per revision; include eligible sample, mention count, and excluded ambiguous count distinctly.

- [ ] **Step 5: Run tests and commit**

Run: `mvn -f aquila-server/pom.xml -Pmysql-it -Dtest=SentimentEngineTest,AggregationEligibilityTest test`

Expected: PASS on MySQL 8. Run full backend tests, frontend tests/build, and OpenAPI lint. Commit `feat: add versioned sentiment aggregation` with required trailer.

### Task 2: Analysis Version Backfill and Workbench

**Files:**
- Create: `aquila-server/src/main/java/com/aquila/sentiment/AnalysisVersionService.java`
- Create: `aquila-server/src/main/java/com/aquila/sentiment/BackfillWorker.java`
- Create: `aquila-server/src/main/java/com/aquila/workbench/WorkbenchController.java`
- Create: `aquila-server/src/main/java/com/aquila/workbench/WorkbenchService.java`
- Create: `aquila-server/src/main/resources/db/migration/V8__analysis_publication.sql` (renumbered twice, 2026-11-15/16: the applied sequence advanced to V6 with `V6__sentiment_identity_hardening.sql`, then V7 with `V7__sentiment_window_check_hardening.sql`, both Stage D review-fix migrations; physical numbering follows the applied sequence, decision D3 pattern)
- Create: `aquila-server/src/test/java/com/aquila/sentiment/AnalysisPublicationIntegrationTest.java`
- Create: `aquila-web/src/views/WorkbenchView.vue`
- Create: `aquila-web/src/views/SecurityDetailView.vue`
- Modify: `docs/api/openapi.yaml`
- Modify: `docs/database/schema-v1.md`
- Modify: `docs/evidence/greenfield/README.md`

**Interfaces:**
- Produces: `AnalysisVersionService.publish(String versionId): PublishResult`; version-consistent workbench/detail queries with `analysisVersion`, `comparableFrom/To`, and `evidenceState`.

- [ ] **Step 1: Write failing online catch-up tests**

Continuously insert/revise/delete input while candidate backfill runs; simulate transaction commit reorder; verify no omitted changes and no mixed-version read. Inject validation failure, barrier timeout, queue overflow, and operator cancellation.

- [ ] **Step 2: Freeze barrier limits from measured test load**

Record VER-01 maximum duration, queue limit, backpressure, and evidence thresholds from MySQL 8 pressure runs before enabling publication.

- [ ] **Step 3: Add V8 change-log and publication schema**

Add commit-ordered change-log head, version lifecycle/checkpoints, backfill/catch-up watermarks, validation results, active pointer, bounded barrier, and publication audit.

- [ ] **Step 4: Implement catch-up, validate, and atomic publish**

Use states `DRAFT → BACKFILLING → CATCHING_UP → VALIDATING → ACTIVE`; failures become FAILED/CANCELLED, preserve prior active version, and release locks/barrier.

- [ ] **Step 5: Implement workbench and historical comparison UI**

Show DataCard supply state separately from sentiment quality; return one version per query and label comparable interval, evidence state, sample counts, and version boundaries.

- [ ] **Step 6: Run FT-04/05, VER-01, and commit**

Run: `mvn -f aquila-server/pom.xml -Pmysql-it -Dtest=AnalysisPublicationIntegrationTest test`

Expected: PASS for continuous changes, commit watermark, barrier timeout/queue limit, no mixed queries, and rollback. Run frontend/build/OpenAPI checks; update evidence and commit `feat: add online analysis version publishing` with required trailer.
