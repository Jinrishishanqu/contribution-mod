UPDATE contribution_account
SET total_income = GREATEST(0, total_income
    - COALESCE((SELECT SUM(income_delta) FROM contribution_transaction
                WHERE player_uuid = contribution_account.player_uuid
                  AND source = 'contribution:stock' AND type = 'STOCK_SELL'), 0)
    - COALESCE((SELECT SUM(income_delta) FROM contribution_transaction_archive
                WHERE player_uuid = contribution_account.player_uuid
                  AND source = 'contribution:stock' AND type = 'STOCK_SELL'), 0));

UPDATE contribution_transaction
SET income_delta = 0,
    type = CASE WHEN type = 'STOCK_BUY' THEN 'SPEND' ELSE 'STOCK' END
WHERE source = 'contribution:stock' AND type IN ('STOCK_BUY', 'STOCK_SELL');

UPDATE contribution_transaction_archive
SET income_delta = 0,
    type = CASE WHEN type = 'STOCK_BUY' THEN 'SPEND' ELSE 'STOCK' END
WHERE source = 'contribution:stock' AND type IN ('STOCK_BUY', 'STOCK_SELL');
