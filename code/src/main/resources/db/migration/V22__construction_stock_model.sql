-- Preserve settled historical values; stock catch-up translates version 1 snapshots on read.
ALTER TABLE industry_daily ADD COLUMN prosperity_version INT NOT NULL DEFAULT 1;

-- Refresh only the current derived indicator, retaining construction totals and EMA history.
UPDATE industry_state SET prosperity = CASE WHEN long_ema IS NULL THEN 0 ELSE
    (0.7 * (short_ema - long_ema) + 0.3 * (
        COALESCE((SELECT daily_development FROM industry_daily d
            WHERE d.industry_id = industry_state.industry_id
              AND d.game_day = industry_state.last_settled_game_day), 0) - long_ema))
    / (long_ema + 1) END;
UPDATE stock_industry_streak SET bottom_streak = 0;

CREATE TABLE stock_industry_signal (
    industry_id VARCHAR(128) NOT NULL PRIMARY KEY,
    magnitude_ema DECIMAL(30,8) NOT NULL DEFAULT 0.5,
    last_signal_day BIGINT NULL,
    CONSTRAINT chk_stock_signal_magnitude CHECK (magnitude_ema >= 0)
);

ALTER TABLE stock_swan_event ADD COLUMN second_player_uuid BINARY(16) NULL;
ALTER TABLE stock_swan_event ADD COLUMN second_player_name VARCHAR(64) NULL;
ALTER TABLE stock_swan_event ADD COLUMN rule_version INT NOT NULL DEFAULT 1;
ALTER TABLE stock_swan_candidate ADD COLUMN baseline_second_development BIGINT NULL;
ALTER TABLE stock_swan_candidate ADD COLUMN second_target_stock_id BIGINT NULL;
