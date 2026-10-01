CREATE TABLE account_uuid_migration (
    migration_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_uuid BINARY(16) NOT NULL,
    target_uuid BINARY(16) NOT NULL,
    source_name VARCHAR(16) NOT NULL,
    target_name VARCHAR(16) NOT NULL,
    migrated_at DATETIME(6) NOT NULL,
    INDEX idx_account_migration_source (source_uuid),
    INDEX idx_account_migration_target (target_uuid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
