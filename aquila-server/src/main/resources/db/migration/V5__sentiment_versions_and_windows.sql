-- Stage D Task 1: versioned sentiment inputs, per-revision results and version-keyed windows.
-- V1-V4 are applied and must NOT be edited (统一方案 §7.3); this migration only creates
-- new objects. V6 (Task 2) owns the commit-ordered change log, version lifecycle,
-- backfill/catch-up watermarks, active pointer and the bounded publication barrier.
--
-- PO parameters are NOT frozen yet (unified plan §9.1/§9.2): label/score mapping, the
-- labelled evaluation set, confidence interpretation, low-confidence threshold, title/body
-- weights, multi-security eligibility defaults and aggregation weights/decay all remain
-- owner decisions. Accordingly this migration inserts NO production dictionary, algorithm
-- or policy rows; those values arrive as explicit versioned snapshots at runtime. See
-- .superpowers/sdd/2026-11-09-zero-based-stage-d/task-1-brief.md.
--
-- Frozen facts already fixed by the spec and reused here: score in [-100,100],
-- confidence in [0,1], unknown as NULL (never neutral 0), rolling 15m/1h/24h windows
-- aligned to 15-minute Asia/Shanghai boundaries, and one analysis version per query.

-- Immutable dictionary snapshot header. entry_count is maintained by the publishing
-- service; a published version is never updated (append-only snapshots).
CREATE TABLE m5_dict_version (
    dict_version       VARCHAR(32)  NOT NULL,
    content_hash       CHAR(64)     NOT NULL,
    entry_count        INT          NOT NULL DEFAULT 0,
    calibration_state  ENUM('EXPERIMENTAL', 'CALIBRATED') NOT NULL DEFAULT 'EXPERIMENTAL',
    published_at       DATETIME(6)  NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (dict_version),
    UNIQUE KEY uq_m5_dict_hash (content_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- UNIQUE (word, dict_type, dict_version) is expressed by this primary key. An entry is
-- insert-only within an immutable snapshot; no UPDATE path is provided by the service.
CREATE TABLE m5_dict_entry (
    dict_version  VARCHAR(32)  NOT NULL,
    dict_type     VARCHAR(24)  NOT NULL,
    word          VARCHAR(64)  NOT NULL,
    score         DECIMAL(6,2) NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (dict_version, dict_type, word),
    CONSTRAINT chk_m5_dict_entry_score CHECK (score BETWEEN -100 AND 100),
    CONSTRAINT fk_m5_dict_entry_version
        FOREIGN KEY (dict_version) REFERENCES m5_dict_version (dict_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE m5_algorithm_version (
    algo_version  VARCHAR(32)  NOT NULL,
    ruleset_hash  CHAR(64)     NOT NULL,
    description   VARCHAR(255) NULL,
    published_at  DATETIME(6)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (algo_version),
    KEY idx_m5_algo_hash (ruleset_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- weights_json stays NULL until the PO freezes calibrated weights. An uncalibrated policy
-- can only produce EXPERIMENTAL window evidence (unified plan §9.2).
CREATE TABLE m5_aggregation_policy_version (
    policy_version     VARCHAR(32) NOT NULL,
    weights_json       JSON         NULL,
    window_types       VARCHAR(64) NOT NULL,
    calibration_state  ENUM('EXPERIMENTAL', 'CALIBRATED') NOT NULL DEFAULT 'EXPERIMENTAL',
    published_at       DATETIME(6) NOT NULL,
    created_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (policy_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- One document-level result per (revision, engine_level, algorithm, dictionary).
-- score/confidence are both NULL for unknown; unknown_reason then names NO_HIT or
-- LOW_CONFIDENCE. Eligibility and exclusion_reason persist the ambiguous multi-security
-- decision so aggregation cannot silently re-interpret it.
CREATE TABLE m5_sentiment_result (
    result_id          VARCHAR(36)  NOT NULL,
    doc_revision_id    VARCHAR(36)  NOT NULL,
    engine_level       VARCHAR(16)  NOT NULL,
    algo_version       VARCHAR(32)  NOT NULL,
    dict_version       VARCHAR(32)  NOT NULL,
    score              DECIMAL(6,2) NULL,
    confidence         DECIMAL(5,4) NULL,
    unknown_reason     VARCHAR(64)  NULL,
    hit_evidence       JSON         NULL,
    eligibility        ENUM('ELIGIBLE', 'AMBIGUOUS_MULTI_SECURITY', 'EXCLUDED') NOT NULL,
    exclusion_reason   VARCHAR(255) NULL,
    is_opposite_stance BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (result_id),
    UNIQUE KEY uq_m5_result_revision_versions
        (doc_revision_id, engine_level, algo_version, dict_version),
    KEY idx_m5_result_dict (dict_version),
    CONSTRAINT chk_m5_result_score_range
        CHECK (score IS NULL OR score BETWEEN -100 AND 100),
    CONSTRAINT chk_m5_result_confidence_range
        CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    CONSTRAINT chk_m5_result_unknown
        CHECK ((score IS NULL AND confidence IS NULL AND unknown_reason IS NOT NULL)
            OR (score IS NOT NULL AND confidence IS NOT NULL AND unknown_reason IS NULL)),
    CONSTRAINT chk_m5_result_exclusion
        CHECK ((eligibility = 'ELIGIBLE' AND exclusion_reason IS NULL)
            OR (eligibility <> 'ELIGIBLE' AND exclusion_reason IS NOT NULL)),
    CONSTRAINT fk_m5_result_revision
        FOREIGN KEY (doc_revision_id) REFERENCES m4_doc_revision (doc_revision_id),
    CONSTRAINT fk_m5_result_algo
        FOREIGN KEY (algo_version) REFERENCES m5_algorithm_version (algo_version),
    CONSTRAINT fk_m5_result_dict
        FOREIGN KEY (dict_version) REFERENCES m5_dict_version (dict_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Unified version-keyed window. The three counters are separate on purpose:
-- eligible directional samples, retained mentions, and ambiguous exclusions.
CREATE TABLE m5_sentiment_window (
    window_id                  VARCHAR(36)  NOT NULL,
    security_code              VARCHAR(16)  NOT NULL,
    window_type                ENUM('15m', '1h', '24h') NOT NULL,
    window_end                 DATETIME(6)  NOT NULL,
    analysis_version           VARCHAR(32)  NOT NULL,
    aggregation_policy_version VARCHAR(32)  NOT NULL,
    score                      DECIMAL(6,2) NULL,
    eligible_sample_count      INT          NOT NULL DEFAULT 0,
    mention_count              INT          NOT NULL DEFAULT 0,
    excluded_ambiguous_count   INT          NOT NULL DEFAULT 0,
    evidence_state             ENUM('EXPERIMENTAL', 'COMPLETE', 'PARTIAL', 'EVIDENCE_EXPIRED')
                               NOT NULL DEFAULT 'EXPERIMENTAL',
    created_at                 DATETIME(6)  NOT NULL,
    PRIMARY KEY (window_id),
    UNIQUE KEY uq_m5_window_key
        (security_code, window_type, window_end, analysis_version, aggregation_policy_version),
    KEY idx_m5_window_lookup (security_code, window_type, window_end),
    CONSTRAINT chk_m5_window_score
        CHECK (score IS NULL OR score BETWEEN -100 AND 100),
    CONSTRAINT chk_m5_window_counts
        CHECK (eligible_sample_count >= 0 AND mention_count >= 0
            AND excluded_ambiguous_count >= 0),
    CONSTRAINT fk_m5_window_security
        FOREIGN KEY (security_code) REFERENCES m2_security (security_code),
    CONSTRAINT fk_m5_window_policy
        FOREIGN KEY (aggregation_policy_version)
        REFERENCES m5_aggregation_policy_version (policy_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Dirty-window generation: a recompute that finishes must compare the generation so a
-- dirty mark written while it was running is not cleared (unified plan §9.2).
CREATE TABLE m5_dirty_window (
    dirty_id       VARCHAR(36)  NOT NULL,
    security_code  VARCHAR(16)  NOT NULL,
    window_type    ENUM('15m', '1h', '24h') NOT NULL,
    window_end     DATETIME(6)  NOT NULL,
    generation     BIGINT       NOT NULL DEFAULT 0,
    state          ENUM('PENDING', 'CLAIMED', 'COMPLETE') NOT NULL DEFAULT 'PENDING',
    consumed_at    DATETIME(6)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (dirty_id),
    UNIQUE KEY uq_m5_dirty_key (security_code, window_type, window_end, generation),
    KEY idx_m5_dirty_pending (state, security_code, window_type, window_end),
    CONSTRAINT fk_m5_dirty_security
        FOREIGN KEY (security_code) REFERENCES m2_security (security_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
