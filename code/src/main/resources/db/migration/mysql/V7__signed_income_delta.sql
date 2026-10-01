ALTER TABLE contribution_transaction
    DROP CHECK chk_contribution_transaction_income_delta,
    ADD CONSTRAINT chk_contribution_transaction_income_delta
        CHECK (income_delta BETWEEN -2147483648 AND 2147483647
            AND (income_delta = 0 OR income_delta = amount)
            AND (type <> 'REFUND' OR income_delta = 0));

ALTER TABLE contribution_transaction_archive
    DROP CHECK contribution_transaction_archive_chk_5,
    ADD CONSTRAINT chk_contribution_archive_income_delta
        CHECK (income_delta BETWEEN -2147483648 AND 2147483647
            AND (income_delta = 0 OR income_delta = amount)
            AND (type <> 'REFUND' OR income_delta = 0));
