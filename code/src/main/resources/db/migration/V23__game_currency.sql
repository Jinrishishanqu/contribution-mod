-- Isolated low-value currency for the speculation gameplay. Balances are stored in
-- thousandths (milli-GC) so an adjustable exchange rate stays exact.
CREATE TABLE game_currency_account (
    player_uuid BINARY(16) NOT NULL,
    player_name VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    player_name_normalized VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    balance_milli BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (player_uuid),
    UNIQUE KEY uq_game_currency_account_name (player_name_normalized),
    CONSTRAINT chk_game_currency_account_balance CHECK (balance_milli >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_cs;

CREATE TABLE game_currency_transaction (
    record_no BIGINT NOT NULL AUTO_INCREMENT,
    transaction_id BINARY(16) NOT NULL,
    idempotency_id BINARY(16) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    player_uuid BINARY(16) NOT NULL,
    player_name VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount_milli BIGINT NOT NULL,
    balance_before_milli BIGINT NOT NULL,
    balance_after_milli BIGINT NOT NULL,
    type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(64) NOT NULL,
    operator VARCHAR(128) NOT NULL,
    server_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    note VARCHAR(64) NULL,
    PRIMARY KEY (record_no),
    UNIQUE KEY uq_game_currency_transaction_id (transaction_id),
    UNIQUE KEY uq_game_currency_transaction_idempotency (idempotency_id),
    KEY idx_game_currency_transaction_player (player_uuid, created_at DESC, record_no DESC),
    KEY idx_game_currency_transaction_created (created_at DESC, record_no DESC),
    KEY idx_game_currency_transaction_type (type, created_at DESC, record_no DESC),
    CONSTRAINT chk_game_currency_transaction_amount CHECK (amount_milli <> 0),
    CONSTRAINT chk_game_currency_transaction_balance_before CHECK (balance_before_milli >= 0),
    CONSTRAINT chk_game_currency_transaction_balance_after CHECK (balance_after_milli >= 0),
    CONSTRAINT chk_game_currency_transaction_balance_change CHECK (
        balance_after_milli = balance_before_milli + amount_milli
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_cs;

-- Every player known to the CP ledger starts with an empty GC wallet; CP is never
-- converted automatically, only through an explicit exchange.
INSERT INTO game_currency_account
    (player_uuid, player_name, player_name_normalized, balance_milli, created_at, updated_at)
SELECT player_uuid, player_name, player_name_normalized, 0, CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6)
FROM contribution_account;

-- New stock rows settle in GC (milli); historical rows keep their whole-CP values and
-- are translated on read, so past cost bases, fees and prices stay unchanged.
ALTER TABLE stock_trade ADD COLUMN currency VARCHAR(16) NOT NULL DEFAULT 'CP';
ALTER TABLE stock_trade MODIFY COLUMN account_delta BIGINT NOT NULL;

-- Retirement refunds migrate 1 CP : 1 GC, then into the milli representation.
UPDATE stock_refund SET amount = amount * 1000, claimed = claimed * 1000;