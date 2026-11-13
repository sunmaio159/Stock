-- Stage B Task 2: master data, watchlists, hotlists, import and idempotency.
-- Migration option A (owner selection, 2026-11-11): one physical migration owns
-- every Stage B table. V3 is unused in this stage.
-- This file is authored but NOT executed until the isolated MySQL 8 schema
-- stock_greenfield_20261109 and its scoped migration/runtime accounts exist.

CREATE TABLE m2_security (
    security_code VARCHAR(16) NOT NULL,
    exchange ENUM('SH', 'SZ') NOT NULL,
    code CHAR(6) NOT NULL,
    security_name VARCHAR(120) NOT NULL,
    security_type VARCHAR(24) NOT NULL,
    status ENUM('ACTIVE', 'SUSPENDED', 'DELISTED') NOT NULL,
    source_as_of DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (security_code),
    UNIQUE KEY uq_m2_security_exchange_code (exchange, code),
    KEY ix_m2_security_name (security_name),
    KEY ix_m2_security_status (status, exchange)
) ENGINE = InnoDB;

CREATE TABLE m2_security_alias (
    alias_id CHAR(36) NOT NULL,
    security_code VARCHAR(16) NOT NULL,
    alias_norm VARCHAR(128) NOT NULL,
    alias_type ENUM('NAME', 'SHORT', 'PINYIN', 'LEGACY') NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (alias_id),
    UNIQUE KEY uq_m2_alias_norm_security (alias_norm, security_code),
    KEY ix_m2_alias_lookup (alias_norm, security_code),
    CONSTRAINT fk_m2_alias_security
        FOREIGN KEY (security_code) REFERENCES m2_security (security_code)
) ENGINE = InnoDB;

CREATE TABLE m2_trade_calendar (
    market VARCHAR(8) NOT NULL,
    trade_date DATE NOT NULL,
    is_trading BOOLEAN NOT NULL,
    note VARCHAR(120) NULL,
    PRIMARY KEY (market, trade_date)
) ENGINE = InnoDB;

CREATE TABLE sys_import_batch (
    batch_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    kind ENUM('MASTER_DATA', 'WATCHLIST') NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    status ENUM('PREVIEW', 'COMMITTED', 'EXPIRED', 'FAILED') NOT NULL,
    row_count INT NOT NULL DEFAULT 0,
    error_count INT NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(128) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NULL,
    committed_at DATETIME(6) NULL,
    PRIMARY KEY (batch_id),
    UNIQUE KEY uq_sys_import_batch_idempotency (user_id, idempotency_key),
    KEY ix_sys_import_batch_status (status, created_at),
    CONSTRAINT fk_sys_import_batch_user
        FOREIGN KEY (user_id) REFERENCES sys_user (user_id)
) ENGINE = InnoDB;

CREATE TABLE sys_import_row (
    row_id CHAR(36) NOT NULL,
    batch_id CHAR(36) NOT NULL,
    row_no INT NOT NULL,
    raw_text VARCHAR(512) NOT NULL,
    normalized_code VARCHAR(16) NULL,
    error_code VARCHAR(64) NULL,
    PRIMARY KEY (row_id),
    UNIQUE KEY uq_sys_import_row_number (batch_id, row_no),
    KEY ix_sys_import_row_error (batch_id, error_code),
    CONSTRAINT fk_sys_import_row_batch
        FOREIGN KEY (batch_id) REFERENCES sys_import_batch (batch_id)
) ENGINE = InnoDB;

-- response_json MUST NOT contain credentials, tokens, recovery codes or TOTP
-- material. Authentication endpoints use their own one-time consumption state.
CREATE TABLE sys_idempotency_record (
    user_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    request_path VARCHAR(255) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    response_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, idempotency_key),
    KEY ix_sys_idempotency_created (created_at),
    CONSTRAINT fk_sys_idempotency_user
        FOREIGN KEY (user_id) REFERENCES sys_user (user_id)
) ENGINE = InnoDB;

CREATE TABLE m1_watchlist_group (
    group_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    group_name VARCHAR(80) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (group_id),
    UNIQUE KEY uq_m1_group_user_name (user_id, group_name),
    KEY ix_m1_group_sort (user_id, sort_order),
    CONSTRAINT fk_m1_group_user
        FOREIGN KEY (user_id) REFERENCES sys_user (user_id)
) ENGINE = InnoDB;

CREATE TABLE m1_watchlist_item (
    item_id CHAR(36) NOT NULL,
    group_id CHAR(36) NOT NULL,
    security_code VARCHAR(16) NOT NULL,
    note VARCHAR(255) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (item_id),
    UNIQUE KEY uq_m1_item_group_security (group_id, security_code),
    KEY ix_m1_item_security (security_code),
    KEY ix_m1_item_sort (group_id, sort_order),
    CONSTRAINT fk_m1_item_group
        FOREIGN KEY (group_id) REFERENCES m1_watchlist_group (group_id),
    CONSTRAINT fk_m1_item_security
        FOREIGN KEY (security_code) REFERENCES m2_security (security_code)
) ENGINE = InnoDB;

CREATE TABLE m1_watchlist_preference (
    user_id CHAR(36) NOT NULL,
    security_code VARCHAR(16) NOT NULL,
    preference_json JSON NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, security_code),
    CONSTRAINT fk_m1_preference_user
        FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_m1_preference_security
        FOREIGN KEY (security_code) REFERENCES m2_security (security_code)
) ENGINE = InnoDB;

CREATE TABLE m1_hotlist_item (
    item_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    security_code VARCHAR(16) NOT NULL,
    source ENUM('MANUAL') NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (item_id),
    UNIQUE KEY uq_m1_hotlist_user_security (user_id, security_code),
    KEY ix_m1_hotlist_sort (user_id, sort_order),
    CONSTRAINT fk_m1_hotlist_user
        FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_m1_hotlist_security
        FOREIGN KEY (security_code) REFERENCES m2_security (security_code)
) ENGINE = InnoDB;

-- One lock row per administrator. Capacity values come from the frozen
-- application configuration, not from a hard-coded DDL default.
CREATE TABLE m1_capacity_lock (
    user_id CHAR(36) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_m1_capacity_user
        FOREIGN KEY (user_id) REFERENCES sys_user (user_id)
) ENGINE = InnoDB;
