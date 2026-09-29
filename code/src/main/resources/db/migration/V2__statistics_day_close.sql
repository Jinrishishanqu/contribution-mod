CREATE TABLE statistics_day_close (
    server_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    game_day BIGINT NOT NULL,
    config_hash BINARY(32) NOT NULL,
    closed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (server_id, game_day),
    KEY idx_statistics_day_close_day (game_day, server_id),
    CONSTRAINT chk_statistics_day_close_game_day CHECK (game_day >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_cs;
