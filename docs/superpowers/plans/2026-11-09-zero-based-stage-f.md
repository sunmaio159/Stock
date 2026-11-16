# Retention, Native Deployment, and Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver legal retention/deletion, safe exports, native install/upgrade, formal backup/restore, and an evidence-based release disposition.

**Architecture:** Lifecycle changes fence workers before resumable batch deletion. Backup and restore are explicit operators with permission-aware retention and isolated validation. Platform claims are limited to hardware actually tested.

**Tech Stack:** Java 17, Spring Boot 3, MySQL 8, Flyway, Vue 3, TypeScript, PowerShell for Windows, shell for DSM.

**Spec:** `docs/superpowers/specs/2026-11-09-zero-based-implementation-design.md`; roadmap: `docs/superpowers/plans/2026-11-09-zero-based-roadmap.md`; prerequisites: Stages A–E and G03/G04 decisions.

## Global Constraints

- Retention follows the shorter of product limits and source permissions; deletion/revocation is enforced before worker output commit.
- Exports enforce field permissions, size/row limits, and spreadsheet formula safety.
- Formal backup remains disabled until G04 decisions and source retention conditions pass.
- Restore starts isolated with external scheduling disabled and reapplies revocation/deletion records.
- Never claim a platform, RPO/RTO, backup, or restore path validated without real evidence.
- Use only the greenfield database; migrations are forward-only; commits include required co-author trailer.

## Review Focus

- Worker running during expiry/revocation cannot recreate derived content; test lifecycle version race.
- Cleanup is resumable and final scan catches residual related content; test crash/restart.
- Restored backup cannot resurrect revoked content; test post-backup deletion ledger.
- Exported untrusted strings cannot become spreadsheet formulas; test leading formula characters.
- Unsupported device or backup choice remains blocked/restricted rather than falsely ready; test gate logic.

---

### Task 1: Retention, Revocation, and Safe Export

**Files:**
- Create: `aquila-server/src/main/java/com/aquila/maintenance/RetentionService.java`
- Create: `aquila-server/src/main/java/com/aquila/maintenance/ExportService.java`
- Create: `aquila-server/src/main/resources/db/migration/V10__retention_maintenance.sql` (renumbered 2026-11-16: V7 is the applied Stage D review-fix `V7__sentiment_window_check_hardening.sql`, V8 is reserved for Stage D Task 2 `V8__analysis_publication.sql`, V9 is Stage E; physical numbering follows the applied Flyway sequence, decision D3 pattern)
- Create: `aquila-server/src/test/java/com/aquila/maintenance/RetentionRaceIntegrationTest.java`
- Create: `aquila-server/src/test/java/com/aquila/maintenance/ExportSafetyTest.java`
- Modify: `aquila-server/src/main/java/com/aquila/governance/LifecycleGuard.java`
- Modify: `aquila-web/src/views/OperationsView.vue`
- Modify: `docs/api/openapi.yaml`
- Modify: `docs/database/schema-v1.md`

**Interfaces:**
- Consumes: lifecycle versions, source permission/retention, jobs, evidence and exports.
- Produces: `RetentionService.preview(RetentionRequest): RetentionPreview`; `RetentionService.execute(String runId): RetentionRun`; `ExportService.export(ExportRequest): ExportArtifact`.

- [ ] **Step 1: Add failing retention and export tests**

Test raw/normalized/aggregate retention boundaries, revoke/delete during worker computation, stale lifecycle commit rejection, resumable batch deletion, final residue scan, permission filtering, export bounds, and formula injection.

- [ ] **Step 2: Add V8 maintenance schema**

Add maintenance run/checkpoint, deletion/revocation ledger, backup manifest references, and retention metadata; keep deletion ledger limited to necessary non-content facts.

- [ ] **Step 3: Implement cleanup and export**

Mark entity EXPIRING/REVOKED and increment lifecycle version before bounded cleanup; require worker CAS at commit; resume batches and rescan before marking complete. Export only allowed fields and neutralize formula-leading cells.

- [ ] **Step 4: Verify and commit**

Run: `mvn -f aquila-server/pom.xml -Pmysql-it -Dtest=RetentionRaceIntegrationTest,ExportSafetyTest test`

Expected: PASS. Update LIFE-01 evidence and commit `feat: add retention and safe exports` with required trailer.

### Task 2: Native Install, Backup, Restore, and Release Decision

**Files:**
- Create: `aquila-server/src/main/java/com/aquila/maintenance/BackupRecoveryService.java`
- Create: `aquila-server/src/test/java/com/aquila/maintenance/RecoveryRevocationIntegrationTest.java`
- Create: `scripts/windows/install.ps1`
- Create: `scripts/windows/upgrade.ps1`
- Create: `scripts/windows/backup.ps1`
- Create: `scripts/windows/restore.ps1`
- Create: `scripts/dsm/install.sh`
- Create: `scripts/dsm/upgrade.sh`
- Create: `scripts/dsm/backup.sh`
- Create: `scripts/dsm/restore.sh`
- Create: `docs/deployment/install.md`
- Create: `docs/deployment/upgrade-and-recovery.md`
- Create: `docs/evidence/release/README.md`
- Modify: `aquila-web/src/views/OperationsView.vue`

**Interfaces:**
- Produces: `BackupRecoveryService.restoreToIsolatedTarget(RestoreRequest): RestoreReport`; native lifecycle scripts and explicit `READY` / `RESTRICTED` / `BLOCKED` evidence.

- [ ] **Step 1: Confirm G03/G04 and source-backup retention inputs**

Record chosen platform, actual device/OS/runtime/MySQL facts, backup encryption/key custody decision, and whether source terms permit backup retention. Without approvals, backup remains disabled and platform unsupported.

- [ ] **Step 2: Add failing restore and gate tests**

Test checksum/schema/count validation, external scheduling disabled, session revocation, post-backup deletion replay, and refusal to cut over before all checks pass.

- [ ] **Step 3: Implement native deployment scripts and isolated restore**

Provide Windows install/upgrade and DSM scripts only for verified target. Restore into isolated target, validate, apply deletion/revocation ledger, then require explicit operator cutover.

- [ ] **Step 4: Run final acceptance and record disposition**

Run the complete Maven suite, MySQL 8 integration tests, frontend tests/build, OpenAPI lint, and applicable §14.1 user journey/capacity/recovery checks on the selected device. Record build, migrations, tool versions, metrics, evidence, and known gaps.

Set `READY` only if all applicable gates pass and P0/P1 are zero; otherwise record `RESTRICTED`/`BLOCKED` and disabled capabilities.

- [ ] **Step 5: Commit release evidence**

Commit only reviewed deployment/release files as `feat: add native backup and release workflow` with required trailer.
