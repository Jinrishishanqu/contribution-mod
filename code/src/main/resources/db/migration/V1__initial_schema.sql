CREATE TABLE contribution_account (
    player_uuid BINARY(16) NOT NULL,
    player_name VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    player_name_normalized VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    balance INT NOT NULL DEFAULT 0,
    total_income INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (player_uuid),
    UNIQUE KEY uq_contribution_account_player_name_normalized (player_name_normalized),
    CONSTRAINT chk_contribution_account_balance CHECK (balance BETWEEN 0 AND 2147483647),
    CONSTRAINT chk_contribution_account_total_income CHECK (total_income BETWEEN 0 AND 2147483647)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_cs;

CREATE TABLE contribution_transaction (
    record_no BIGINT NOT NULL AUTO_INCREMENT,
    transaction_id BINARY(16) NOT NULL,
    idempotency_id BINARY(16) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    player_uuid BINARY(16) NOT NULL,
    player_name VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount INT NOT NULL,
    income_delta INT NOT NULL DEFAULT 0,
    balance_before INT NOT NULL,
    balance_after INT NOT NULL,
    type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(64) NOT NULL,
    operator VARCHAR(128) NOT NULL,
    server_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    note VARCHAR(64) NULL,
    PRIMARY KEY (record_no),
    UNIQUE KEY uq_contribution_transaction_id (transaction_id),
    UNIQUE KEY uq_contribution_transaction_idempotency (idempotency_id),
    KEY idx_contribution_transaction_player (player_uuid, created_at DESC, record_no DESC),
    KEY idx_contribution_transaction_created (created_at DESC, record_no DESC),
    KEY idx_contribution_transaction_type (type, created_at DESC, record_no DESC),
    KEY idx_contribution_transaction_source (source, created_at DESC, record_no DESC),
    KEY idx_contribution_transaction_server (server_id, created_at DESC, record_no DESC),
    CONSTRAINT chk_contribution_transaction_amount CHECK (amount <> 0),
    CONSTRAINT chk_contribution_transaction_income_delta CHECK (
        income_delta BETWEEN 0 AND 2147483647
        AND income_delta = CASE WHEN amount > 0 AND type <> 'REFUND' THEN amount ELSE 0 END
    ),
    CONSTRAINT chk_contribution_transaction_balance_before CHECK (balance_before BETWEEN 0 AND 2147483647),
    CONSTRAINT chk_contribution_transaction_balance_after CHECK (balance_after BETWEEN 0 AND 2147483647),
    CONSTRAINT chk_contribution_transaction_balance_change CHECK (balance_after = balance_before + amount)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_cs;

CREATE TABLE statistics_batch (
    batch_id BINARY(16) NOT NULL,
    server_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    game_day BIGINT NOT NULL,
    config_version BIGINT NOT NULL,
    config_hash BINARY(32) NOT NULL,
    payload_hash BINARY(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    applied_at DATETIME(6) NOT NULL,
    PRIMARY KEY (batch_id),
    KEY idx_statistics_batch_day (game_day, server_id),
    CONSTRAINT chk_statistics_batch_game_day CHECK (game_day >= 0),
    CONSTRAINT chk_statistics_batch_config_version CHECK (config_version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_cs;

CREATE TABLE industry_day_accumulator (
    game_day BIGINT NOT NULL,
    industry_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    development BIGINT NOT NULL DEFAULT 0,
    config_version BIGINT NOT NULL,
    config_hash BINARY(32) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (game_day, industry_id),
    CONSTRAINT chk_industry_day_accumulator_game_day CHECK (game_day >= 0),
    CONSTRAINT chk_industry_day_accumulator_development CHECK (development >= 0),
    CONSTRAINT chk_industry_day_accumulator_config_version CHECK (config_version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_cs;

CREATE TABLE industry_state (
    industry_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    last_settled_game_day BIGINT NULL,
    total_development BIGINT NOT NULL DEFAULT 0,
    long_ema DECIMAL(30, 8) NULL,
    short_ema DECIMAL(30, 8) NULL,
    prosperity DECIMAL(30, 8) NOT NULL DEFAULT 0,
    config_version BIGINT NOT NULL,
    config_hash BINARY(32) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (industry_id),
    CONSTRAINT chk_industry_state_last_settled_day CHECK (
        last_settled_game_day IS NULL OR last_settled_game_day >= 0
    ),
    CONSTRAINT chk_industry_state_total_development CHECK (total_development >= 0),
    CONSTRAINT chk_industry_state_long_ema CHECK (long_ema IS NULL OR long_ema >= 0),
    CONSTRAINT chk_industry_state_short_ema CHECK (short_ema IS NULL OR short_ema >= 0),
    CONSTRAINT chk_industry_state_ema_pair CHECK ((long_ema IS NULL) = (short_ema IS NULL)),
    CONSTRAINT chk_industry_state_config_version CHECK (config_version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_cs;

CREATE TABLE industry_daily (
    game_day BIGINT NOT NULL,
    industry_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    daily_development BIGINT NOT NULL,
    total_development BIGINT NOT NULL,
    long_ema DECIMAL(30, 8) NULL,
    short_ema DECIMAL(30, 8) NULL,
    prosperity DECIMAL(30, 8) NOT NULL DEFAULT 0,
    config_version BIGINT NOT NULL,
    config_hash BINARY(32) NOT NULL,
    settled_at DATETIME(6) NOT NULL,
    PRIMARY KEY (game_day, industry_id),
    KEY idx_industry_daily_industry (industry_id, game_day DESC),
    CONSTRAINT chk_industry_daily_game_day CHECK (game_day >= 0),
    CONSTRAINT chk_industry_daily_development CHECK (daily_development >= 0),
    CONSTRAINT chk_industry_daily_total_development CHECK (total_development >= 0),
    CONSTRAINT chk_industry_daily_long_ema CHECK (long_ema IS NULL OR long_ema >= 0),
    CONSTRAINT chk_industry_daily_short_ema CHECK (short_ema IS NULL OR short_ema >= 0),
    CONSTRAINT chk_industry_daily_ema_pair CHECK ((long_ema IS NULL) = (short_ema IS NULL)),
    CONSTRAINT chk_industry_daily_config_version CHECK (config_version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_cs;

CREATE TABLE player_activity_stats (
    player_uuid BINARY(16) NOT NULL,
    player_name VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    player_name_normalized VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    total_placed BIGINT NOT NULL DEFAULT 0,
    total_mined BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (player_uuid),
    UNIQUE KEY uq_player_activity_name_normalized (player_name_normalized),
    CONSTRAINT chk_player_activity_total_placed CHECK (total_placed >= 0),
    CONSTRAINT chk_player_activity_total_mined CHECK (total_mined >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_cs;

CREATE TABLE player_industry_stats (
    player_uuid BINARY(16) NOT NULL,
    industry_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    development BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (player_uuid, industry_id),
    KEY idx_player_industry_industry (industry_id, development DESC),
    CONSTRAINT chk_player_industry_development CHECK (development >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_cs;

CREATE TABLE scheduled_task_run (
    task_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    game_day BIGINT NOT NULL,
    run_id BINARY(16) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count INT NOT NULL DEFAULT 1,
    started_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    lease_until DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (task_name, game_day),
    UNIQUE KEY uq_scheduled_task_run_id (run_id),
    CONSTRAINT chk_scheduled_task_run_game_day CHECK (game_day >= 0),
    CONSTRAINT chk_scheduled_task_run_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_scheduled_task_run_attempt_count CHECK (attempt_count >= 1),
    CONSTRAINT chk_scheduled_task_run_completion CHECK (
        (status = 'RUNNING' AND completed_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED') AND completed_at IS NOT NULL)
    ),
    CONSTRAINT chk_scheduled_task_run_lease CHECK (
        (status = 'RUNNING' AND lease_until IS NOT NULL)
        OR (status IN ('SUCCEEDED', 'FAILED') AND lease_until IS NULL)
    ),
    CONSTRAINT chk_scheduled_task_run_failure CHECK (
        (status = 'FAILED' AND failure_code IS NOT NULL)
        OR (status IN ('RUNNING', 'SUCCEEDED') AND failure_code IS NULL)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_cs;
