CREATE TABLE shop_catalog_state (
    singleton_id INT NOT NULL PRIMARY KEY,
    seeded BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(6) NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
INSERT INTO shop_catalog_state(singleton_id, seeded, updated_at) VALUES (1, FALSE, CURRENT_TIMESTAMP(6));

CREATE TABLE shop_offer (
    offer_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    legacy_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    name VARCHAR(64) NOT NULL,
    item_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_count INT NOT NULL,
    price INT NOT NULL,
    description VARCHAR(512) NOT NULL DEFAULT '',
    listed BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_shop_offer_legacy (legacy_id),
    KEY idx_shop_offer_catalog (listed, sort_order, offer_id),
    CONSTRAINT chk_shop_offer_count CHECK (item_count BETWEEN 1 AND 64),
    CONSTRAINT chk_shop_offer_price CHECK (price BETWEEN 1 AND 2147483647)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE stock_swan_candidate ADD COLUMN target_stock_id BIGINT NULL;
ALTER TABLE stock_swan_candidate ADD COLUMN target_selected BOOLEAN NOT NULL DEFAULT FALSE;
