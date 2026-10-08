CREATE TABLE player_development_total (
    player_uuid BINARY(16) NOT NULL,
    development DECIMAL(30,0) NOT NULL DEFAULT 0,
    PRIMARY KEY (player_uuid),
    KEY idx_development_total_rank (development DESC, player_uuid ASC),
    CONSTRAINT chk_development_total CHECK (development >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_as_cs;

INSERT INTO player_development_total (player_uuid, development)
SELECT player_uuid, SUM(CAST(development AS DECIMAL(30,0)))
FROM player_industry_stats
WHERE industry_id IN (
    'contribution:construction_landscaping', 'contribution:mining_metallurgy',
    'contribution:energy_chemical', 'contribution:processing_manufacturing',
    'contribution:technology_magic', 'contribution:agriculture_forestry_livestock_fishery',
    'contribution:military_food_medicine', 'contribution:circulation_services',
    'contribution:culture_education_livelihood'
)
GROUP BY player_uuid;
