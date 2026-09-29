CREATE TABLE player_distance_remainder (
    player_uuid BINARY(16) NOT NULL,
    movement_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    remainder_micro BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (player_uuid, movement_type),
    CONSTRAINT chk_player_distance_remainder CHECK (remainder_micro >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_cs;
