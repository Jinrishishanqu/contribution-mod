CREATE TABLE stock_notice (
    player_uuid BINARY(16) NOT NULL,
    stock_id BIGINT NOT NULL,
    game_day BIGINT NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message VARCHAR(160) NOT NULL,
    delivered_at DATETIME(6) NULL,
    PRIMARY KEY (player_uuid, stock_id, game_day, kind),
    INDEX idx_stock_notice_pending (delivered_at, player_uuid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
