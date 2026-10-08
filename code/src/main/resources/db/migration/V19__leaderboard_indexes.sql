CREATE INDEX idx_account_balance_rank ON contribution_account (balance DESC, player_uuid ASC);
CREATE INDEX idx_account_income_rank ON contribution_account (total_income DESC, player_uuid ASC);
