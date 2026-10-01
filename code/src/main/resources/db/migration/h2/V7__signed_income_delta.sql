ALTER TABLE contribution_transaction DROP CONSTRAINT chk_contribution_transaction_income_delta;
ALTER TABLE contribution_transaction ADD CONSTRAINT chk_contribution_transaction_income_delta
    CHECK (income_delta BETWEEN -2147483648 AND 2147483647
        AND (income_delta = 0 OR income_delta = amount)
        AND (type <> 'REFUND' OR income_delta = 0));
