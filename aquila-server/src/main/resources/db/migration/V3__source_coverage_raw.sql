-- Stage C Task 1: source policy, coverage, raw, and durable jobs.
-- Owning migration for m3_* objects per docs/database/schema-v1.md.
-- V3 has been rewritten in place three times (round-2 permission columns, round-4
-- page_terminal, 1.x I4/I5 state-check + NOT NULL), so its checksum changes each time:
-- every replay must run against a fresh empty schema.

CREATE TABLE m3_source_config (
    source_code               VARCHAR(32)  NOT NULL,
    source_name               VARCHAR(128) NOT NULL,
    permission_version        INT          NOT NULL DEFAULT 1,
    permission_status         VARCHAR(16)  NOT NULL,
    -- Per-capability grants (the unified design §8.1): each of the six permissions is checked
    -- individually at its point of use; permission_status only gates the whole source.
    perm_access               BOOLEAN      NOT NULL DEFAULT FALSE,
    perm_store_raw            BOOLEAN      NOT NULL DEFAULT FALSE,
    perm_store_normalized     BOOLEAN      NOT NULL DEFAULT FALSE,
    perm_analyze              BOOLEAN      NOT NULL DEFAULT FALSE,
    perm_display              BOOLEAN      NOT NULL DEFAULT FALSE,
    perm_export               BOOLEAN      NOT NULL DEFAULT FALSE,
    valid_from                DATETIME(6)  NOT NULL,
    valid_to                  DATETIME(6)  NULL,
    rate_limit                INT          NOT NULL DEFAULT 0,
    subscription_scope        VARCHAR(64)  NOT NULL DEFAULT 'DEFAULT',
    subscription_version      INT          NOT NULL DEFAULT 1,
    subscription_effective_from DATETIME(6) NOT NULL,
    subscription_effective_to   DATETIME(6) NULL,
    license_version           INT          NOT NULL DEFAULT 1,
    cursor_version            BIGINT       NOT NULL DEFAULT 0,
    lease_owner               VARCHAR(64)  NULL,
    lease_until               DATETIME(6)  NULL,
    fencing_token             BIGINT       NOT NULL DEFAULT 0,
    adapter_contract_version  VARCHAR(32)  NOT NULL,
    created_at                DATETIME(6)  NOT NULL,
    updated_at                DATETIME(6)  NOT NULL,
    PRIMARY KEY (source_code),
    CONSTRAINT chk_m3_source_status CHECK (permission_status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    INDEX idx_m3_source_lease (lease_owner, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE m3_task_instance (
    run_id          VARCHAR(36)  NOT NULL,
    source_code     VARCHAR(32)  NOT NULL,
    run_key         VARCHAR(128) NOT NULL,
    attempt         INT          NOT NULL DEFAULT 1,
    state           VARCHAR(16)  NOT NULL,
    cursor_before   VARCHAR(256) NULL,
    cursor_after    VARCHAR(256) NULL,
    fencing_token   BIGINT       NOT NULL,
    lease_owner     VARCHAR(64)  NULL,
    lease_until     DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (run_id),
    UNIQUE KEY uk_m3_task_run_key (source_code, run_key),
    CONSTRAINT chk_m3_task_state CHECK (state IN ('CREATED', 'RUNNING', 'PARTIAL', 'COMPLETE', 'FAILED', 'UNKNOWN', 'UNRECOVERABLE')),
    INDEX idx_m3_task_source_state (source_code, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE m3_coverage_fact (
    coverage_id              VARCHAR(36)  NOT NULL,
    source_code              VARCHAR(32)  NOT NULL,
    run_id                   VARCHAR(36)  NOT NULL,
    subscription_scope       VARCHAR(64)  NOT NULL,
    subscription_version     INT          NOT NULL,
    business_interval_start  DATETIME(6)  NOT NULL,
    business_interval_end    DATETIME(6)  NOT NULL,
    cursor_before            VARCHAR(256) NULL,
    cursor_after             VARCHAR(256) NULL,
    coverage_status          VARCHAR(16)  NOT NULL,
    reason                   VARCHAR(512) NULL,
    adapter_contract_version VARCHAR(32)  NOT NULL,
    evidence_reference       VARCHAR(256) NULL,
    observed_at              DATETIME(6)  NOT NULL,
    page_index               INT          NOT NULL DEFAULT 0,
    page_terminal            BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at               DATETIME(6)  NOT NULL,
    PRIMARY KEY (coverage_id),
    UNIQUE KEY uk_m3_coverage_run_page (run_id, page_index),
    CONSTRAINT chk_m3_coverage_status CHECK (coverage_status IN ('COMPLETE', 'PARTIAL', 'UNKNOWN', 'UNRECOVERABLE')),
    INDEX idx_m3_coverage_source_status (source_code, coverage_status),
    INDEX idx_m3_coverage_interval (source_code, business_interval_start, business_interval_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE m3_raw_record (
    raw_record_id   VARCHAR(36)  NOT NULL,
    source_code     VARCHAR(32)  NOT NULL,
    outer_id        VARCHAR(128) NOT NULL,
    fetched_date    DATE         NOT NULL,
    payload_hash    VARCHAR(64)  NOT NULL,
    license_version INT          NOT NULL,
    parse_version   VARCHAR(32)  NOT NULL,
    payload         MEDIUMTEXT   NOT NULL,
    received_at     DATETIME(6)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (raw_record_id),
    UNIQUE KEY uk_m3_raw_dedup (source_code, outer_id, fetched_date, payload_hash),
    INDEX idx_m3_raw_source_outer (source_code, outer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE m3_processing_job (
    job_id              VARCHAR(36)  NOT NULL,
    input_type          VARCHAR(32)  NOT NULL,
    input_key           VARCHAR(256) NOT NULL,
    source_code         VARCHAR(32)  NOT NULL,
    raw_record_id       VARCHAR(36)  NULL,
    fetched_date        DATE         NULL,
    stage               VARCHAR(32)  NOT NULL,
    processor_version   VARCHAR(32)  NOT NULL,
    input_revision      VARCHAR(64)  NOT NULL,
    state               VARCHAR(16)  NOT NULL,
    lease_owner         VARCHAR(64)  NULL,
    lease_until         DATETIME(6)  NULL,
    fencing_token       BIGINT       NOT NULL DEFAULT 0,
    retry_count         INT          NOT NULL DEFAULT 0,
    output_reference    VARCHAR(256) NULL,
    last_error          VARCHAR(512) NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (job_id),
    UNIQUE KEY uk_m3_job_idempotency (input_type, input_key, stage, processor_version, input_revision),
    CONSTRAINT chk_m3_job_state CHECK (state IN ('PENDING', 'CLAIMED', 'COMPLETE', 'DEAD_LETTER')),
    INDEX idx_m3_job_pending (state, stage, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE m3_adapter_evidence (
    evidence_id              VARCHAR(36)  NOT NULL,
    run_id                   VARCHAR(36)  NOT NULL,
    source_code              VARCHAR(32)  NOT NULL,
    page_index               INT          NOT NULL,
    http_status             INT          NULL,
    content_type             VARCHAR(128) NULL,
    byte_size                BIGINT       NULL,
    terminal_evidence        VARCHAR(512) NULL,
    adapter_contract_version VARCHAR(32)  NOT NULL,
    captured_at              DATETIME(6)  NOT NULL,
    PRIMARY KEY (evidence_id),
    INDEX idx_m3_evidence_run (run_id, page_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
