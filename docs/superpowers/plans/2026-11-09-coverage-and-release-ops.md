# Coverage and Release Operations Documentation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the v1.2 coverage and analysis-version protocols operationally observable and verifiable without turning the unified plan into a second detailed implementation manual.

**Architecture:** Keep `统一落地实施方案.md` as the sole normative implementation baseline and add concise operating rules plus a link to a new tracking register. Put source capability evidence, failure-injection scenarios, and decision/acceptance tracking in that register, whose entries reference the baseline rather than overriding it.

**Tech Stack:** Markdown documentation only; no code or dependency changes.

**Spec:** `落地文档设计/Copilot/统一落地实施方案.md` v1.2 and the user's 2026-11-09 review requirements.

## Global Constraints

- Preserve the confirmed product scope, alert thresholds, retention periods, release gates, and architecture in v1.2.
- Do not infer adapter completeness when a source cannot prove coverage; display “已采集声量” for unsupported complete-window claims.
- Do not assign arbitrary barrier timeout or queue-cap numbers; mark these as parameters to measure and freeze in the stated stage.
- On barrier timeout, queue overflow, or failed validation, do not switch the active version; release the barrier and retain the prior active version.
- The new register is traceability/supporting evidence, not a parallel construction contract.
- Preserve existing user/worktree changes; do not stage or commit files.

## Review Focus

- A failed/unknown source window must not look like “no alert”; cover with the source-window operation rules and failure scenario.
- Barrier timeout or backlog overflow must not strand input processing or publish a stale candidate; cover with release failure scenario.
- Late-arriving data must not silently rewrite an issued alert; cover with alert correction semantics.
- Per-source constraints must not become universal adapter rules; cover with capability-matrix provenance/version fields.
- An unapproved parameter or missing evidence must remain visibly blocking; cover with register status/owner/evidence fields.

---

### Task 1: Clarify Runtime Operations in the Unified Baseline

**Files:**
- Modify: `落地文档设计/Copilot/统一落地实施方案.md`
- Modify: `落地文档设计/Copilot/README.md`

**Interfaces:**
- Consumes: Existing coverage states, alert window rules, analysis-version state machine, release stages, and v1.1 document hierarchy.
- Produces: Normative operation semantics for coverage gaps, bounded release barriers, alerts, and a link to the register.

- [x] **Step 1: Specify coverage-state meanings and operations**

  Define `UNKNOWN` as completeness not currently provable and `UNRECOVERABLE` as a known gap outside source recovery capability/retention. Require admin-visible gap interval, source/subscription, reason, last evidence, and recovery action. State that unresolved/incomplete windows block alert evaluation and are shown as “无法评估/覆盖不完整,” not as no alert. Unprovable late/backfill coverage is limited to “已采集声量.”

- [x] **Step 2: Specify bounded release-barrier behavior**

  Require D-stage measured/frozen values for maximum barrier duration and queued input limit. At timeout, queue limit, or validation failure: do not switch active version; release the barrier; preserve the prior active version; resume accepted input safely or apply explicit backpressure/rejection without dropping committed facts; record a failure reason and retry eligibility. Do not invent numeric limits.

- [x] **Step 3: Specify alert silence and recovery semantics**

  Define incomplete coverage as an evaluation-suspended/unknown condition; define manual silence with actor, scope, reason, start/end, audit, and explicit UI state. On source recovery, recompute statistics only by default and do not backfill old notifications. Preserve issued event evidence; later data corrections must be an auditable amendment/revision, not a silent rewrite or withdrawal.

- [x] **Step 4: Link the supporting register and update version metadata**

  Add a clearly scoped link in the unified plan and README; retain the unified plan as the sole normative source. Bump the unified plan and README version labels consistently.

- [x] **Step 5: Review the changed documents**

  Confirm no change to user-confirmed thresholds, retention limits, security exceptions, or production gates; confirm all new runtime parameters remain explicitly unapproved until their named freeze stage.

### Task 2: Create the Source Capability and Evidence Register

**Files:**
- Create: `落地文档设计/Copilot/运行与验收登记表.md`
- Modify: `落地文档设计/Copilot/README.md`

**Interfaces:**
- Consumes: Normative semantics and references from Task 1.
- Produces: A single supporting register for source-specific capability evidence, fault scenarios, and decisions/acceptance IDs.

- [x] **Step 1: Add a source capability matrix**

  Include source code/name, Adapter contract version, capability/limitation, business-time field semantics, pagination termination evidence, cursor continuity/recovery range, late/backfill behavior, coverage eligibility, measurement date, evidence reference, owner, and status. Mark unmeasured capabilities `UNVERIFIED`; distinguish source-specific observations from common rules.

- [x] **Step 2: Add prioritized end-to-end fault scenarios**

  For each scenario record initial state/data, injection point, invariant, recovery action, and evidence artifact:
  1. Crash after page persistence/commit boundary.
  2. Older revision finishes after a newer revision.
  3. Retention/revocation races with worker commit.
  4. New inputs continue during backfill/catch-up.
  5. Release barrier times out or reaches queue cap.

- [x] **Step 3: Add traceable decision and acceptance rows**

  Define IDs for existing gates and new operational requirements. Each row has requirement/reference, classification (decision/gate/acceptance), accountable owner, freeze stage/date, evidence location, status (`OPEN`, `IN_PROGRESS`, `PASSED`, `BLOCKED`, `N/A`), and blocking effect. Seed rows for source capability/coverage, barrier limits, alert baseline parameters, restore evidence, and platform validation. Missing evidence remains open; do not claim passed.

- [x] **Step 4: State register governance**

  Clarify the register records decisions/evidence only; the unified plan controls behavior if wording conflicts. Link the register from README and the unified plan.

### Task 3: Verify Cross-Document Consistency

**Files:**
- Verify: `落地文档设计/Copilot/统一落地实施方案.md`
- Verify: `落地文档设计/Copilot/运行与验收登记表.md`
- Verify: `落地文档设计/Copilot/README.md`

**Interfaces:**
- Consumes: Task 1 normative edits and Task 2 register.
- Produces: Consistent versions, links, IDs, status vocabulary, and a clean documentation diff.

- [x] **Step 1: Check all new links and references**

  Verify README → unified plan/register and unified plan → register references resolve; verify register entries link to the correct numbered sections in the unified plan.

- [x] **Step 2: Check terminology and scope**

  Verify the same coverage states, alert behavior, barrier failure behavior, and register status terms are used consistently; verify the register does not introduce contradictory normative rules.

- [x] **Step 3: Inspect final documents and worktree status**

  Use `git diff --check`, read the three target files, and inspect `git status --short --untracked-files=all`. Because the target documents are untracked in this worktree, do not claim `git diff` represents their content. Do not stage or commit.
