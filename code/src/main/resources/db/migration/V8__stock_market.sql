CREATE TABLE stock_market_state (
    singleton_id INT NOT NULL PRIMARY KEY,
    game_day BIGINT NOT NULL,
    day_time INT NOT NULL,
    updated_at DATETIME(6) NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO stock_market_state (singleton_id, game_day, day_time, updated_at)
VALUES (1, -1, 0, CURRENT_TIMESTAMP(6));

CREATE TABLE stock_listing (
    stock_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    item_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_name VARCHAR(64) NOT NULL,
    industry_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listed_day BIGINT NOT NULL,
    initial_price INT NOT NULL,
    price INT NOT NULL,
    high_price INT NOT NULL,
    low_price INT NOT NULL,
    wave_base DECIMAL(20, 8) NOT NULL,
    ou_noise DECIMAL(20, 8) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    retirement_day BIGINT NULL,
    last_price_day BIGINT NOT NULL,
    delisted_day BIGINT NULL,
    INDEX idx_stock_active (status, industry_id),
    INDEX idx_stock_item (item_id, status),
    INDEX idx_stock_recent_delisted (delisted_day),
    CONSTRAINT chk_stock_price CHECK (initial_price > 0 AND price > 0 AND high_price > 0 AND low_price > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE stock_daily_price (
    stock_id BIGINT NOT NULL,
    game_day BIGINT NOT NULL,
    price INT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (stock_id, game_day),
    INDEX idx_stock_daily_day (game_day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE stock_position (
    player_uuid BINARY(16) NOT NULL,
    stock_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    bought_day BIGINT NOT NULL,
    bought_quantity INT NOT NULL,
    PRIMARY KEY (player_uuid, stock_id),
    CONSTRAINT chk_stock_position_quantity CHECK (quantity BETWEEN 0 AND 10000),
    CONSTRAINT chk_stock_position_bought CHECK (bought_quantity BETWEEN 0 AND 10000)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE stock_trade (
    trade_id BINARY(16) NOT NULL PRIMARY KEY,
    idempotency_id BINARY(16) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    player_uuid BINARY(16) NOT NULL,
    stock_id BIGINT NOT NULL,
    game_day BIGINT NOT NULL,
    side VARCHAR(4) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quantity INT NOT NULL,
    price INT NOT NULL,
    gross BIGINT NOT NULL,
    fee BIGINT NOT NULL,
    account_delta INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_stock_trade_idempotency (idempotency_id),
    UNIQUE KEY uq_stock_trade_daily_action (player_uuid, stock_id, game_day, side),
    INDEX idx_stock_trade_player (player_uuid, created_at DESC),
    INDEX idx_stock_trade_stock (stock_id, game_day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE stock_industry_streak (
    industry_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    bottom_streak INT NOT NULL,
    last_bottom_retirement_day BIGINT NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE stock_refund (
    player_uuid BINARY(16) NOT NULL,
    stock_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    claimed BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (player_uuid, stock_id),
    CONSTRAINT chk_stock_refund_amount CHECK (amount >= 0 AND claimed >= 0 AND claimed <= amount)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
