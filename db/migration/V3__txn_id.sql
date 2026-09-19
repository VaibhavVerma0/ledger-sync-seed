-- The ledger had no uniqueness guarantee, so ingesting the same corpus twice
-- wrote every transaction twice. Identity is derived from what the transaction
-- IS (account, time, direction, amount), never from a message id, because a
-- message id identifies an upload and the same SMS is re-uploaded with a new one.
--
-- Legacy rows written before this column existed keep a NULL txn_id. H2 treats
-- NULLs as distinct in a unique index, so they are untouched here. Deduplicating
-- them is the backfill's job.
ALTER TABLE ledger ADD COLUMN IF NOT EXISTS txn_id VARCHAR(120);

CREATE UNIQUE INDEX IF NOT EXISTS ux_ledger_txn_id ON ledger(txn_id);