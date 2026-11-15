# Schema v1 logical model and ownership map

This is a logical model and migration-ownership map only. It contains no DDL,
placeholder tables, or physical column definitions. The A-stage map follows
the target objects and invariants in the unified plan §7.1–7.2; B–F own the
physical schema, field/nullability choices, indexes, retention durations, and
versioned migrations. A row is not permission to create a table before its
owning stage.

The names below are logical object names where §7.1 names them explicitly.
Where it describes an object by purpose instead, the owning stage freezes its
physical name before creating its migration. Retention classes describe the
data category, not an invented number of days.

| Logical object | Ownership and key constraints to preserve | References / relationship | Retention class | Owning migration |
|---|---|---|---|---|
| `sys_user` | Single-admin singleton key; username unique; password hash and AES-GCM-encrypted TOTP secret are never plaintext; last consumed TOTP step prevents replay | None | Identity and security lifecycle | V1 — B |
| `sys_user_session` | Unique `token_hash`; purpose, creation, and revocation are explicit; ADMIN sessions have no server expiry; recovery context expires | `sys_user` | Revocable session lifecycle | V1 — B |
| `sys_recovery_code_batch` | Each batch is independently revocable and tied to one administrator | `sys_user` | Authentication recovery lifecycle | V1 — B |
| `sys_recovery_code` | Unique SHA-256 hash only; one-time `used_at` and revocation are atomic | `sys_recovery_code_batch` | Authentication recovery lifecycle | V1 — B |
| `sys_bootstrap_context` | Unique bootstrap-token hash; short expiry and atomic consumption | `sys_user` | Short-lived bootstrap context | V1 — B |
| `sys_reauth_context` | Unique reauthentication-token hash; bound to user and session; short expiry and atomic consumption | `sys_user`, `sys_user_session` | Short-lived sensitive-action context | V1 — B |
| `sys_csrf_context` | Context and CSRF token hashes only; short expiry; optionally bound to a live session | `sys_user_session` | CSRF lifecycle | V1 — B |
| `sys_login_failure` | Persistent per-administrator failed-attempt timestamps support the rolling five-minute limit | `sys_user` | Authentication rate-limit state | V1 — B |
| `sys_idempotency_record` | B owns request idempotency; key scope and uniqueness are frozen with the API operation | Owning operation / administrator | Idempotency lifecycle | V2 — B |
| `sys_audit_log` | Append-oriented security facts; credentials, tokens, recovery codes, and source content are excluded | Actor references `sys_user` where applicable | Security and operational audit | V1 — B |
| `m1_watchlist_group` | User-owned groups; ordering is versioned | `sys_user` | User configuration | V2 — B |
| `m1_watchlist_item` | User/security membership and ordering are explicit; exact uniqueness is frozen in B | `m1_watchlist_group`, `m2_security`, `sys_user` | User configuration | V2 — B |
| `m1_watchlist_preference` | One effective preference per agreed user/security scope; B freezes the key | `sys_user`, `m2_security` | User configuration | V2 — B |
| Manual hot-list item | User plus security is unique; manual entries remain distinct from computed candidates | `sys_user`, `m2_security` | User configuration | V2 — B |
| `m2_security` | Canonical `(exchange, code)` key; aliases do not replace the canonical key | None | Reference data | V2 — B |
| `m2_security_alias` | Alias identity and normalization rules are frozen in B | `m2_security` | Reference data | V2 — B |
| `m2_trade_calendar` | One canonical trading-day fact per agreed market/date key | Market/calendar scope | Reference data | V2 — B |
| `sys_import_batch` and import rows | Batch and row-level validation outcomes are traceable and idempotent; B freezes row key | `sys_user`; rows belong to a batch and may reference `m2_security` | Import evidence | V2 — B |
| `m3_source_config` | Source permission version, validity, rate limit, cursor version, lease, and fencing token are distinct state; each of the six capabilities is granted separately by a `perm_* BOOLEAN NOT NULL DEFAULT FALSE` column, so an `ACTIVE` source without a specific grant is still refused for that operation (V3 as built, Stage C Task 1 fix round) | Source/adapter identity | Licensed-source configuration | V3 — C (as built; was V4) |
| `m3_task_instance` | `run_key`, attempt, state, and before/after cursor are recorded; lease owner and fencing token guard commits | `m3_source_config` | Task execution lifecycle | V3 — C (as built; was V4) |
| Collection coverage fact (`m3_coverage_fact`) | Records source, subscription scope/version, business interval, cursors, run, state, adapter contract version, evidence reference, observation time, and a per-page `page_terminal BOOLEAN NOT NULL DEFAULT FALSE` flag set from the adapter-reported terminal signal (V3 as built, Stage C Task 1 round-4 finding G-1) so the completeness proof reads a persisted flag rather than inferring termination from an empty cursor | `m3_source_config`, `m3_task_instance` | Coverage evidence | V3 — C (as built; was V4) |
| `m3_raw_record` | Network duplicates do not create new facts; real revisions remain distinguishable. Partitioned references carry both `raw_record_id` and `fetched_date` | `m3_source_config`; referenced by jobs/revisions together with partition date | Licensed raw content | V3 — C (as built; was V4) |
| `m4_normalized_doc` | Business key `(source_code, outer_id)`; current pointers are separate from immutable revision history | Source identity; current revision | Licensed normalized content | V4 — C (as built; was V5) |
| `m4_doc_revision` | Immutable revision with body hash and monotonically ordered `revision_order`; processing an older revision cannot move the current pointer backwards | `m4_normalized_doc`, `m3_raw_record` plus partition date | Licensed revision content | V4 — C (as built; was V5) |
| Document/security mention | Unique `(revision, security)`; association changes dirty affected analysis windows | `m4_doc_revision`, `m2_security` | Derived content association | V4 — C (as built; was V5) |
| Duplicate relation and quality reason | Duplicate evidence and quality reasons are retained without overwriting the canonical source facts | `m4_normalized_doc` / revision | Derived content metadata | V4 — C (as built; was V5) |
| `m4_processing_job` | Idempotency identity derives from input type/key, stage, processor version, and input revision; lease/fencing gates output commit | Raw record plus partition date, document/revision, or other typed input | Processing lifecycle | **Built as `m3_processing_job` in V3 by Stage C Task 1. Owner decision 2026-11-15 (B1): the physical name `m3_processing_job` is canonical — the `m4_*` label is a planning-stage placeholder, not a pending rename. No rename migration is issued. Task 2 may extend this table or add a sibling table in V4 as its design needs dictate.** |
| Dead-letter record | Safe summary and input reference only; never duplicate sensitive source content. Carried by V3 `m3_processing_job` (`DEAD_LETTER` state + `last_error`); no separate table (owner decision 2026-11-15). | `m3_processing_job` and typed input reference | Processing failure evidence | V4 — C (as built; was V6) |
| `m5_sentiment_dict` and version | Unique `(word, dict_type, dict_version)`; published snapshots are immutable | Dictionary version | Versioned derived configuration | V5 — D (as built; was V7) |
| `m5_sentiment_result` | Unique `(doc_revision_id, engine_level, algo_version, dict_version)`; score is `DECIMAL(6,2)` in `[-100,100]`, confidence is `DECIMAL(5,4)` | `m4_doc_revision`, sentiment dictionary/version | Derived analysis | V5 — D (as built; was V7) |
| `m5_sentiment_window` | Unique `(security_code, window_type, window_end, analysis_version, aggregation_policy_version)`; no parallel duplicate aggregate fact | `m2_security`, analysis version | Derived analysis | V5 — D (as built; was V7) |
| Dirty-window state, input-change log, analysis-version state/pointer | Change sequence is commit-ordered and recoverable; active pointer changes only after catch-up and validation | Revisions, security associations, sentiment windows and version | Recalculation/audit state | V6 — D (as built; was V7) |
| `m8_alert_rule` | Rule version and approved thresholds are explicit; G05 remains open until frozen | `m2_security` where scoped | Alert configuration | V7 — E (as built; was V8) |
| Alert assessment state and silence record | Coverage and insufficient-baseline states remain distinguishable from zero alerts; silence records actor, scope, reason, and interval | Rule, security, `sys_user` | Alert operations | V7 — E (as built; was V8) |
| `m8_alert_event` and event correction | Original evidence is immutable; late-data corrections append versioned facts | Rule/version, window, security and sample-policy snapshot | Alert evidence | V7 — E (as built; was V8) |
| Rule/security cooldown state | Unique `(rule_id, security_code)` | `m8_alert_rule`, `m2_security` | Alert operations | V7 — E (as built; was V8) |
| `m8_alert_delivery` | Unique `(event_id, channel)` | `m8_alert_event` | Delivery history | V7 — E (as built; was V8) |
| `sys_maintenance_run` and revocation/deletion register | Cleanup is resumable and auditable; deletion register keeps only necessary non-content facts | Affected source/document/security and actor as applicable | Maintenance and deletion evidence | V8 — F |

## Migration boundaries

- V1–V2 are owned by Stage B (V1 by Task 1, V2 by Task 2). **V3 is unused in Stage B**: the owner selected migration option A on 2026-11-11, so Stage B Task 2 creates a single `V2__masterdata_watchlist_schema.sql` containing all Stage B tables listed above. This removes the cross-migration foreign-key ordering problem between `m1_watchlist_item` and `m2_security`. **V3 is introduced by Stage C Task 1** (`V3__source_coverage_raw.sql`), creating `m3_source_config`, `m3_task_instance`, `m3_coverage_fact`, `m3_raw_record`, `m3_processing_job`, and `m3_adapter_evidence`, and establishing the lease/fencing, cursor-CAS, coverage-fact, raw-dedup, and durable-job foundations. V3–V4 by Stage C; V5–V6 by Stage D; V7 by Stage E; V8 by Stage F. **Numbering alignment note:** the logical ownership map above was drawn at Stage A as a pre-allocation; the physical migration chain shifted forward by one when Stage B's owner chose option A (D2, 2026-11-11) merging the originally V2+V3 master-data into a single V2 (freeing V3), and Stage C Task 1 then used V3 for the `m3_*` tables the map had allocated to V4. The map's owning-migration labels therefore carry `as built; was Vn` annotations and follow the actual Flyway versions, not the pre-allocation. This is a documentation self-consistency correction only — not a product-scope change — and preserves the roadmap `V1–V8` numbering (owner decision 2026-11-15, same规格 as D2/A1/B1/C1).
- The option A selection is a migration-ownership correction recorded in `docs/evidence/greenfield/README.md`; it is not a product-scope change.
- **V4 additive DDL (Stage C Task 2, owner decision D4, 2026-11-15):** V1–V3 are applied and must not be edited, so V4 issues three additive `ALTER`s against V3 tables — `UNIQUE (raw_record_id, fetched_date)` on `m3_raw_record` for the composite FK, a single-statement replacement of `chk_m3_job_state` admitting `EXPIRED`/`REVOKED`, and two context columns on `m3_processing_job` (`doc_revision_id`, `captured_lifecycle_version`, no reverse FK). V4 also adds `content_fingerprint` on `m4_doc_revision` (SQL SHA-256 over the content columns, re-verified before every derived commit) and `consumed_at` on `m4_recompute_marker`. A `BEFORE UPDATE` immutability trigger was evaluated and rejected on this MySQL 8.4 instance (error 1419: no SUPER privilege with binary logging enabled). This table remains a **logical ownership map**; physical DDL detail lives in `V4__document_processing.sql` and `.superpowers/sdd/2026-11-09-zero-based-stage-c/task-2-brief.md`.
- No migration or DDL is created by Stage A. No historical DDL, database, or Flyway history is an input.
- All business migrations target only the explicitly provisioned new schema
  `stock_greenfield_20261109`. If it is not provisioned, MySQL acceptance is
  pending and no database connection is attempted.
- Exact retention durations, final physical names for purpose-only objects,
  field definitions, indexes, and additional references remain with each
  owning stage and its evidence-backed decisions.
