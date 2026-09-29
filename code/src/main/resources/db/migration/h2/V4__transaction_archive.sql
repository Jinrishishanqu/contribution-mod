CREATE TABLE contribution_transaction_archive AS
    SELECT * FROM contribution_transaction WHERE 1 = 0;
ALTER TABLE contribution_transaction_archive ALTER COLUMN record_no SET NOT NULL;
ALTER TABLE contribution_transaction_archive ADD PRIMARY KEY (record_no);
CREATE UNIQUE INDEX uq_contribution_transaction_archive_id
    ON contribution_transaction_archive (transaction_id);
CREATE UNIQUE INDEX uq_contribution_transaction_archive_idempotency
    ON contribution_transaction_archive (idempotency_id);
CREATE INDEX idx_contribution_transaction_archive_player
    ON contribution_transaction_archive (player_uuid, created_at DESC, record_no DESC);
CREATE INDEX idx_contribution_transaction_archive_created
    ON contribution_transaction_archive (created_at DESC, record_no DESC);
