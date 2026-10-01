CREATE TABLE stock_batch_request (
    batch_id BINARY(16) NOT NULL PRIMARY KEY,
    player_uuid BINARY(16) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_stock_batch_player (player_uuid, created_at DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
