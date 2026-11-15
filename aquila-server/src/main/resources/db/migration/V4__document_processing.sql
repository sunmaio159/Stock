-- Stage C Task 2: immutable document revisions, mentions, quality, and lifecycle barrier.
-- Owner decision 2026-11-15 (D3): V4 is the owning migration for the m4_* content
-- objects; the Stage A logical ownership map pre-allocated them to V5 and now carries
-- "as built; was V5" annotations. Physical chain: V1-V2 B, V3-V4 C, V5-V6 D, V7 E, V8 F.
-- Owner decision 2026-11-15 (D4): the eight as-built adjustments to the original Task 2
-- spec are recorded in .superpowers/sdd/2026-11-09-zero-based-stage-c/task-2-brief.md
-- and docs/evidence/greenfield/README.md. Every P0-/P1- annotated block below is one of
-- those signed adjustments, not an ad-hoc implementation liberty.
--
-- V1-V3 are executed and must not be edited (统一方案 §7.3: increment by the existing
-- max version, never change an applied script). Every alteration of a V3 table below
-- is therefore additive DDL issued from V4, leaving their Flyway checksums intact.
--
-- Immutability scope (spec P1-1): the content columns of m4_doc_revision
-- (revision_order, body_hash, raw reference, timestamps, versions) are never updated
-- after insert. `status` is a monotone lifecycle bit only, advanced
-- OBSERVED -> PROCESSED -> SUPERSEDED; it carries no content. D4 upgrades this from a
-- Java-only convention to an auditable DB-side fingerprint checked at every derived
-- commit (see content_fingerprint below and LifecycleGuard.assertContentUnchanged).

-- P0-1: the spec's composite FK (raw_record_id, fetched_date) -> m3_raw_record needs
-- a unique key on exactly that column pair. V3 has only PRIMARY KEY (raw_record_id)
-- and uk_m3_raw_dedup (source_code, outer_id, fetched_date, payload_hash), and MySQL 8.4
-- with restrict_fk_on_non_standard_key rejects an FK onto a non-unique key.
-- raw_record_id is already unique, so this key cannot conflict.
ALTER TABLE m3_raw_record
    ADD UNIQUE KEY uq_m3_raw_id_date (raw_record_id, fetched_date);

-- P0-2: lifecycle-blocked jobs become non-retryable terminal states. V3's
-- chk_m3_job_state admits only PENDING/CLAIMED/COMPLETE/DEAD_LETTER.
-- D4: DROP and ADD are combined into ONE ALTER. MySQL DDL commits implicitly, so two
-- statements would leave the schema in a half-migrated state if the ADD failed after
-- the DROP had already committed (Flyway would then mark V4 failed on a dirty schema).
ALTER TABLE m3_processing_job
    DROP CHECK chk_m3_job_state,
    ADD CONSTRAINT chk_m3_job_state CHECK (state IN
        ('PENDING', 'CLAIMED', 'COMPLETE', 'DEAD_LETTER', 'EXPIRED', 'REVOKED'));

-- P0-3 / owner decision B1 ("Task 2 may extend this table"): a PARSE job enqueued for
-- a document revision carries the revision and the lifecycle version captured when it
-- was enqueued. Both stay NULL for Stage C Task 1 crawl jobs, which keep their existing
-- behaviour. Deliberately no FK from the V3 table back to a V4 table: that would invert
-- the migration dependency direction and complicate Flyway clean. The index supports
-- the worker's lookup path; a dangling doc_revision_id is handled as a non-retryable
-- terminal outcome by ProcessingJobWorker (D4).
ALTER TABLE m3_processing_job
    ADD COLUMN doc_revision_id VARCHAR(36) NULL,
    ADD COLUMN captured_lifecycle_version BIGINT NULL,
    ADD INDEX idx_m3_job_doc_revision (doc_revision_id);

CREATE TABLE m4_normalized_doc (
    doc_id                           VARCHAR(36)  NOT NULL,
    source_code                      VARCHAR(32)  NOT NULL,
    outer_id                         VARCHAR(128) NOT NULL,
    current_doc_revision_id          VARCHAR(36)  NULL,
    latest_observed_revision_order   BIGINT       NOT NULL DEFAULT 0,
    latest_processed_revision_order  BIGINT       NOT NULL DEFAULT 0,
    lifecycle_status                 ENUM('ACTIVE', 'EXPIRING', 'REVOKED') NOT NULL DEFAULT 'ACTIVE',
    lifecycle_version                BIGINT       NOT NULL DEFAULT 0,
    created_at                       DATETIME(6)  NOT NULL,
    updated_at                       DATETIME(6)  NOT NULL,
    PRIMARY KEY (doc_id),
    UNIQUE KEY uq_m4_doc_source_outer (source_code, outer_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE m4_doc_revision (
    doc_revision_id     VARCHAR(36)  NOT NULL,
    doc_id              VARCHAR(36)  NOT NULL,
    revision_order      BIGINT       NOT NULL,
    body_hash           VARCHAR(64)  NOT NULL,
    raw_record_id       VARCHAR(36)  NOT NULL,
    fetched_date        DATE         NOT NULL,
    published_at        DATETIME(6)  NULL,
    observed_at         DATETIME(6)  NOT NULL,
    normalizer_version  VARCHAR(32)  NOT NULL,
    parser_version      VARCHAR(32)  NOT NULL,
    status              ENUM('OBSERVED', 'PROCESSED', 'SUPERSEDED') NOT NULL DEFAULT 'OBSERVED',
    -- P1-1 / D4: SHA-256 over the content columns. Insert writes an empty placeholder,
    -- then a second statement in the SAME receive() transaction computes it purely from
    -- the stored column values; LifecycleGuard re-evaluates that exact expression before
    -- any derived commit. A direct UPDATE of a content column breaks the fingerprint.
    content_fingerprint CHAR(64)     NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (doc_revision_id),
    UNIQUE KEY uq_m4_revision_doc_order (doc_id, revision_order),
    KEY idx_m4_revision_status (doc_id, status),
    CONSTRAINT fk_m4_revision_doc
        FOREIGN KEY (doc_id) REFERENCES m4_normalized_doc (doc_id),
    CONSTRAINT fk_m4_revision_raw
        FOREIGN KEY (raw_record_id, fetched_date)
        REFERENCES m3_raw_record (raw_record_id, fetched_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- P1-4: current_doc_revision_id closes a cycle (doc -> revision -> doc). MySQL permits
-- it when the referencing column is nullable; add the FK only after both tables exist.
ALTER TABLE m4_normalized_doc
    ADD CONSTRAINT fk_m4_doc_current_revision
    FOREIGN KEY (current_doc_revision_id) REFERENCES m4_doc_revision (doc_revision_id);

-- P1-1 / D4: a BEFORE UPDATE trigger was evaluated and REJECTED. On this MySQL 8.4
-- instance CREATE TRIGGER fails with error 1419 ("You do not have the SUPER privilege
-- and binary logging is enabled"); the scoped aquila_migrate account must not be granted
-- SUPER, and log_bin_trust_function_creators=1 is a server-level change outside this
-- project's authority. Instead, immutability is enforced by:
--   (1) content_fingerprint, written once by RevisionService using the same SQL
--       expression LifecycleGuard re-evaluates before a derived commit (no Java/DB
--       timestamp formatting drift, because both sides are pure SQL over stored rows);
--   (2) uq_m4_revision_doc_order, so an order cannot be reassigned;
--   (3) the worker's `WHERE status = 'OBSERVED'` CAS, so status only moves forward.
-- This is a detective + preventive control, not a DDL prohibition: a privileged
-- direct UPDATE can still change a content column, but no derived commit can follow it.

CREATE TABLE m4_entity_mention (
    mention_id        VARCHAR(36)  NOT NULL,
    doc_revision_id   VARCHAR(36)  NOT NULL,
    security_code     VARCHAR(16)  NOT NULL,
    mention_count     INT          NOT NULL DEFAULT 1,
    -- P1-2: a handoff flag only. The authoritative recompute signal is the unconsumed
    -- m4_recompute_marker; D4 requires the derived-write commit to consume the marker
    -- in the same transaction that clears this flag, so the two never disagree.
    is_dirty          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (mention_id),
    UNIQUE KEY uq_m4_mention_revision_security (doc_revision_id, security_code),
    KEY idx_m4_mention_security_reverse (security_code, doc_revision_id),
    CONSTRAINT fk_m4_mention_revision
        FOREIGN KEY (doc_revision_id) REFERENCES m4_doc_revision (doc_revision_id),
    CONSTRAINT fk_m4_mention_security
        FOREIGN KEY (security_code) REFERENCES m2_security (security_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE m4_doc_quality (
    doc_revision_id            VARCHAR(36)  NOT NULL,
    is_duplicate               BOOLEAN      NOT NULL DEFAULT FALSE,
    duplicate_of_revision_id   VARCHAR(36)  NULL,
    quality_reason             VARCHAR(512) NULL,
    created_at                 DATETIME(6)  NOT NULL,
    PRIMARY KEY (doc_revision_id),
    CONSTRAINT fk_m4_quality_revision
        FOREIGN KEY (doc_revision_id) REFERENCES m4_doc_revision (doc_revision_id),
    CONSTRAINT fk_m4_quality_duplicate
        FOREIGN KEY (duplicate_of_revision_id) REFERENCES m4_doc_revision (doc_revision_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- P1-3: the spec lists only (security_code, marker_type, created_at). A marker with no
-- key cannot be made idempotent, so add a surrogate PK plus a per-revision unique key.
-- doc_revision_id is added for traceability and idempotency of the receive() write.
-- P1-2 / D4: consumed_at is the consumption half of the is_dirty handoff; a NULL means
-- the affected-window recompute is still owed. Stage D reads unconsumed markers.
CREATE TABLE m4_recompute_marker (
    marker_id         VARCHAR(36)  NOT NULL,
    security_code     VARCHAR(16)  NOT NULL,
    marker_type       VARCHAR(32)  NOT NULL,
    doc_revision_id   VARCHAR(36)  NOT NULL,
    consumed_at       DATETIME(6)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (marker_id),
    UNIQUE KEY uq_m4_marker_revision_security_type
        (doc_revision_id, security_code, marker_type),
    KEY idx_m4_marker_pending (security_code, marker_type, consumed_at, created_at),
    CONSTRAINT fk_m4_marker_security
        FOREIGN KEY (security_code) REFERENCES m2_security (security_code),
    CONSTRAINT fk_m4_marker_revision
        FOREIGN KEY (doc_revision_id) REFERENCES m4_doc_revision (doc_revision_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Owner decision 2026-11-15: no independent dead-letter table. V3 m3_processing_job
-- already carries state DEAD_LETTER, retry_count and last_error; Task 2 adds EXPIRED /
-- REVOKED above as the non-retryable lifecycle-blocked outcomes.
