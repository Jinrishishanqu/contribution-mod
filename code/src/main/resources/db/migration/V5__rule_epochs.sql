CREATE TABLE contribution_rule_epoch (
    game_day BIGINT NOT NULL,
    config_hash BINARY(32) NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    PRIMARY KEY (game_day),
    CONSTRAINT chk_rule_epoch_day CHECK (game_day >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
