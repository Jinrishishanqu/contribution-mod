CREATE TABLE contribution_rule_snapshot (
    config_hash BINARY(32) NOT NULL PRIMARY KEY,
    snapshot_json LONGTEXT NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_cs;
INSERT IGNORE INTO contribution_rule_snapshot (config_hash, snapshot_json)
    SELECT config_hash, snapshot_json FROM contribution_rule_epoch;
ALTER TABLE contribution_rule_epoch DROP COLUMN snapshot_json;
