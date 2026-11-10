CREATE TABLE sys_user (
    user_id CHAR(36) NOT NULL,
    singleton_key TINYINT NOT NULL DEFAULT 1,
    username VARCHAR(80) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    totp_secret_encrypted VARBINARY(256) NOT NULL,
    totp_pending_secret_encrypted VARBINARY(256) NULL,
    totp_pending_expires_at DATETIME(6) NULL,
    totp_last_step BIGINT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    UNIQUE KEY uq_sys_user_singleton (singleton_key),
    UNIQUE KEY uq_sys_user_username (username),
    CONSTRAINT chk_sys_user_singleton CHECK (singleton_key = 1)
) ENGINE = InnoDB;

CREATE TABLE sys_user_session (
    session_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    token_hash BINARY(32) NOT NULL,
    purpose ENUM('ADMIN', 'RECOVERY') NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (session_id),
    UNIQUE KEY uq_sys_user_session_token_hash (token_hash),
    KEY ix_sys_user_session_user_revoked (user_id, revoked_at),
    KEY ix_sys_user_session_expiry (expires_at),
    CONSTRAINT fk_sys_user_session_user
        FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT chk_sys_user_session_expiry CHECK (
        (purpose = 'ADMIN' AND expires_at IS NULL)
        OR (purpose = 'RECOVERY' AND expires_at IS NOT NULL)
    )
) ENGINE = InnoDB;

CREATE TABLE sys_recovery_code_batch (
    batch_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (batch_id),
    KEY ix_sys_recovery_batch_user (user_id, revoked_at),
    CONSTRAINT fk_sys_recovery_batch_user
        FOREIGN KEY (user_id) REFERENCES sys_user (user_id)
) ENGINE = InnoDB;

CREATE TABLE sys_recovery_code (
    recovery_code_id CHAR(36) NOT NULL,
    batch_id CHAR(36) NOT NULL,
    code_hash BINARY(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (recovery_code_id),
    UNIQUE KEY uq_sys_recovery_code_hash (code_hash),
    KEY ix_sys_recovery_code_batch_state (batch_id, used_at, revoked_at),
    CONSTRAINT fk_sys_recovery_code_batch
        FOREIGN KEY (batch_id) REFERENCES sys_recovery_code_batch (batch_id)
) ENGINE = InnoDB;

CREATE TABLE sys_bootstrap_context (
    context_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    token_hash BINARY(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    PRIMARY KEY (context_id),
    UNIQUE KEY uq_sys_bootstrap_token_hash (token_hash),
    KEY ix_sys_bootstrap_user_state (user_id, consumed_at, expires_at),
    CONSTRAINT fk_sys_bootstrap_user
        FOREIGN KEY (user_id) REFERENCES sys_user (user_id)
) ENGINE = InnoDB;

CREATE TABLE sys_reauth_context (
    context_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    token_hash BINARY(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    PRIMARY KEY (context_id),
    UNIQUE KEY uq_sys_reauth_token_hash (token_hash),
    KEY ix_sys_reauth_session_state (session_id, consumed_at, expires_at),
    CONSTRAINT fk_sys_reauth_user
        FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_sys_reauth_session
        FOREIGN KEY (session_id) REFERENCES sys_user_session (session_id)
) ENGINE = InnoDB;

CREATE TABLE sys_csrf_context (
    context_id CHAR(36) NOT NULL,
    context_hash BINARY(32) NOT NULL,
    csrf_hash BINARY(32) NOT NULL,
    session_id CHAR(36) NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (context_id),
    UNIQUE KEY uq_sys_csrf_context_hash (context_hash),
    KEY ix_sys_csrf_expiry (expires_at),
    KEY ix_sys_csrf_session (session_id),
    CONSTRAINT fk_sys_csrf_session
        FOREIGN KEY (session_id) REFERENCES sys_user_session (session_id)
) ENGINE = InnoDB;

CREATE TABLE sys_login_failure (
    failure_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    failed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (failure_id),
    KEY ix_sys_login_failure_window (user_id, failed_at),
    CONSTRAINT fk_sys_login_failure_user
        FOREIGN KEY (user_id) REFERENCES sys_user (user_id)
) ENGINE = InnoDB;

CREATE TABLE sys_audit_log (
    log_id CHAR(36) NOT NULL,
    actor_user_id CHAR(36) NULL,
    action VARCHAR(80) NOT NULL,
    metadata_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (log_id),
    KEY ix_sys_audit_actor_time (actor_user_id, created_at),
    KEY ix_sys_audit_action_time (action, created_at),
    CONSTRAINT fk_sys_audit_actor
        FOREIGN KEY (actor_user_id) REFERENCES sys_user (user_id)
) ENGINE = InnoDB;
