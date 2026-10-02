ALTER TABLE stock_market_state ADD COLUMN observed_day BIGINT NOT NULL DEFAULT -1;
ALTER TABLE stock_market_state ADD COLUMN observed_time INT NOT NULL DEFAULT 0;
ALTER TABLE stock_market_state ADD COLUMN observed_at DATETIME(6) NULL;
ALTER TABLE stock_market_state ADD COLUMN clock_server_id VARCHAR(64) NULL;
