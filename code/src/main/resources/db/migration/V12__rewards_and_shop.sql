CREATE TABLE player_development_reward (
    player_uuid BINARY(16) NOT NULL PRIMARY KEY,
    paid_contribution BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT chk_player_development_reward_paid CHECK (paid_contribution >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE reward_configuration (
    singleton_id INT NOT NULL PRIMARY KEY,
    config_hash BINARY(32) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT chk_reward_configuration_singleton CHECK (singleton_id = 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE checkin_daily (
    player_uuid BINARY(16) NOT NULL,
    calendar_day DATE NOT NULL,
    online_seconds INT NOT NULL DEFAULT 0,
    claimed_at DATETIME(6) NULL,
    PRIMARY KEY (player_uuid, calendar_day),
    CONSTRAINT chk_checkin_daily_seconds CHECK (online_seconds BETWEEN 0 AND 86400)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE checkin_player (
    player_uuid BINARY(16) NOT NULL PRIMARY KEY,
    last_claim_day DATE NULL,
    cycle_day INT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT chk_checkin_player_cycle CHECK (cycle_day >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE checkin_event (
    event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    title VARCHAR(64) NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    contribution_amount INT NOT NULL DEFAULT 0,
    item_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    item_count INT NOT NULL DEFAULT 0,
    reward_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reward_data VARCHAR(512) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT chk_checkin_event_dates CHECK (ends_on >= starts_on),
    CONSTRAINT chk_checkin_event_amount CHECK (contribution_amount BETWEEN 0 AND 2147483647),
    CONSTRAINT chk_checkin_event_item CHECK ((item_id IS NULL AND item_count = 0) OR (item_id IS NOT NULL AND item_count BETWEEN 1 AND 2304))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE checkin_event_claim (
    event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    player_uuid BINARY(16) NOT NULL,
    claimed_on DATE NOT NULL,
    PRIMARY KEY (event_id, player_uuid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE reward_delivery (
    delivery_id BINARY(16) NOT NULL PRIMARY KEY,
    player_uuid BINARY(16) NOT NULL,
    item_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_count INT NOT NULL,
    source VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    delivered_at DATETIME(6) NULL,
    KEY idx_reward_delivery_player (player_uuid, status, created_at),
    CONSTRAINT chk_reward_delivery_count CHECK (item_count BETWEEN 1 AND 2304),
    CONSTRAINT chk_reward_delivery_status CHECK (status IN ('PENDING', 'CLAIMING', 'DELIVERED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE shop_order (
    order_id BINARY(16) NOT NULL PRIMARY KEY,
    player_uuid BINARY(16) NOT NULL,
    offer_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quantity INT NOT NULL,
    total_price INT NOT NULL,
    delivery_id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    KEY idx_shop_order_player (player_uuid, created_at),
    CONSTRAINT chk_shop_order_quantity CHECK (quantity BETWEEN 1 AND 64),
    CONSTRAINT chk_shop_order_price CHECK (total_price BETWEEN 1 AND 2147483647)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
