CREATE TABLE stock_swan_schedule (
    singleton_id INT PRIMARY KEY,
    next_start_day BIGINT NOT NULL DEFAULT -1,
    active_event_id BINARY(16) NULL
);

INSERT INTO stock_swan_schedule (singleton_id, next_start_day, active_event_id) VALUES (1, -1, NULL);

CREATE TABLE stock_swan_event (
    event_id BINARY(16) PRIMARY KEY,
    player_uuid BINARY(16) NOT NULL,
    player_name VARCHAR(64) NOT NULL,
    started_clock BIGINT NOT NULL,
    deadline_clock BIGINT NOT NULL,
    completed_clock BIGINT NULL
);

CREATE TABLE stock_swan_candidate (
    event_id BINARY(16) NOT NULL,
    industry_id VARCHAR(128) NOT NULL,
    baseline_development BIGINT NOT NULL,
    result_kind VARCHAR(8) NULL,
    effect_day BIGINT NULL,
    applied_day BIGINT NULL,
    news_until_clock BIGINT NULL,
    PRIMARY KEY (event_id, industry_id),
    KEY idx_stock_swan_effect (effect_day, applied_day),
    CONSTRAINT fk_stock_swan_event FOREIGN KEY (event_id) REFERENCES stock_swan_event(event_id)
);
