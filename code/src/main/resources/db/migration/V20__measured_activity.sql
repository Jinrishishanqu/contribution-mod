CREATE TABLE measurement_remainder (
    scope_id VARCHAR(64) NOT NULL,
    actor_uuid BINARY(16) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    remainder_amount BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (scope_id, actor_uuid, event_id)
);

CREATE TABLE event_activity_day (
    server_id VARCHAR(64) NOT NULL,
    game_day BIGINT NOT NULL,
    actor_uuid BINARY(16) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    raw_amount BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (server_id, game_day, actor_uuid, event_id)
);
