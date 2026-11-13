# Coverage-Aware Alerts and Operations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver audited, coverage-aware on-site volume alerts with silence, recovery, late correction, replay, and dead-letter operations.

**Architecture:** Alert evaluation consumes only complete, eligible, versioned windows. Event creation/cooldown is transactional and idempotent; suspension, silence, and corrections are explicit states and audit records.

**Tech Stack:** Java 17, Spring Boot 3, MySQL 8, Flyway, Vue 3, TypeScript.

**Spec:** `docs/superpowers/specs/2026-11-09-zero-based-implementation-design.md`; roadmap: `docs/superpowers/plans/2026-11-09-zero-based-roadmap.md`; prerequisites: Stages A–D.

## Global Constraints

- Do not evaluate incomplete coverage as zero or “no alert”; use `EVALUATION_SUSPENDED`.
- Preserve original event trigger evidence; late corrections append rather than overwrite.
- Manual silence is scoped, time-bounded, reasoned, and audited; it does not stop ingestion/aggregation.
- Recovery recomputes statistics but does not automatically send expired historical notifications.
- Replay defaults to no alert delivery; delivery and cooldown are concurrency-safe.
- Use MySQL 8 greenfield schema and required commit trailer.

## Review Focus

- Full coverage and true zero is distinct from incomplete coverage; test both.
- Concurrent evaluators create no duplicate event or cooldown transition; test transaction race.
- Silence suppresses only matching scope/time and is auditable; test boundary conditions.
- Late correction preserves original event; test immutable evidence.
- Replay/recovery does not send stale notifications; test delivery count.

---

### Task 1: Rules, Evaluation, Events, and Silence

**Files:**
- Create: `aquila-server/src/main/java/com/aquila/alert/AlertEvaluationService.java`
- Create: `aquila-server/src/main/java/com/aquila/alert/AlertEventService.java`
- Create: `aquila-server/src/main/java/com/aquila/alert/AlertController.java`
- Create: `aquila-server/src/main/java/com/aquila/alert/AlertDeliveryWorker.java`
- Create: `aquila-server/src/main/resources/db/migration/V7__alerts_and_audit.sql`
- Create: `aquila-server/src/test/java/com/aquila/alert/AlertThresholdTest.java`
- Create: `aquila-server/src/test/java/com/aquila/alert/AlertCoverageIntegrationTest.java`
- Create: `aquila-server/src/test/java/com/aquila/alert/AlertCooldownConcurrencyTest.java`
- Create: `aquila-web/src/views/AlertRulesView.vue`
- Create: `aquila-web/src/views/AlertEventsView.vue`
- Modify: `docs/api/openapi.yaml`
- Modify: `docs/database/schema-v1.md`
- Modify: `docs/evidence/greenfield/README.md`

**Interfaces:**
- Consumes: Stage C coverage state and Stage D versioned windows.
- Produces: `AlertEvaluationService.evaluate(AlertWindow): EvaluationResult`; `AlertEventService.acknowledge(String eventId): AlertEvent`; `AlertEventService.silence(SilenceRequest): SilenceRecord`.

- [ ] **Step 1: Freeze alert parameters**

PO freezes thresholds, minimum sample history, evaluation cutoff, cooling period and rule defaults; save the parameter snapshot before acceptance tests.

- [ ] **Step 2: Add failing threshold/state/concurrency tests**

Test the frozen equivalents of C=9/10, exactly 2B, B=0; insufficient history; complete zero vs incomplete coverage; suspend/resume; concurrent cooldown; silence scope/expiry/audit; and late data correction.

- [ ] **Step 3: Add V7 alert schema**

Create rule/version, event/evidence, evaluation state, unique rule/security cooldown, delivery idempotency, silence/audit, append-only correction, and dead-letter/replay references.

- [ ] **Step 4: Implement evaluation and state transitions**

Evaluate only eligible complete windows. Mark incomplete windows suspended with reason. Keep trigger evidence immutable, append corrections, and suppress expired-window notification on recovery.

- [ ] **Step 5: Implement alert UI and OpenAPI**

Show suspended vs no alert, event evidence, ack/dismiss, silence range/reason/expiry, late correction history, and version/source state.

- [ ] **Step 6: Verify and commit**

Run: `mvn -f aquila-server/pom.xml -Pmysql-it -Dtest=AlertThresholdTest,AlertCoverageIntegrationTest,AlertCooldownConcurrencyTest test`

Expected: PASS. Run all backend/frontend/OpenAPI checks, update ALERT-01 evidence, and commit `feat: add coverage-aware alerting` with required trailer.

### Task 2: Replay, Dead Letters, and Operations

**Files:**
- Create: `aquila-server/src/main/java/com/aquila/operations/ReplayService.java`
- Create: `aquila-server/src/main/java/com/aquila/operations/DeadLetterService.java`
- Create: `aquila-server/src/test/java/com/aquila/operations/ReplayNoDeliveryTest.java`
- Create: `aquila-web/src/views/ReplayView.vue`
- Create: `aquila-web/src/views/DeadLetterView.vue`
- Create: `aquila-web/src/views/OperationsView.vue`
- Modify: `docs/api/openapi.yaml`

**Interfaces:**
- Produces: bounded replay submit/status/cancel and dead-letter retry; retry creates a new task referencing immutable input, default `sendAlerts=false`.

- [ ] **Step 1: Add failing replay/dead-letter tests**

Test row/time/range limits, idempotent replay, cancellation, retry input identity, audit logging, no notification on historical replay, and policy recheck before processing.

- [ ] **Step 2: Implement constrained operations**

Require authorization and parameter bounds; persist replay/dead-letter tasks and display status without exposing secrets or restricted raw payloads.

- [ ] **Step 3: Verify and commit**

Run: `mvn -f aquila-server/pom.xml -Pmysql-it -Dtest=ReplayNoDeliveryTest test`

Expected: PASS; run frontend/build/OpenAPI checks and commit `feat: add controlled replay operations` with required trailer.
