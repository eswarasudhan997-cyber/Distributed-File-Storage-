-- For a pre-existing storage_nodes table, check INFORMATION_SCHEMA before running this once.
-- If the column is missing, run:
ALTER TABLE storage_nodes
  ADD COLUMN last_heartbeat TIMESTAMP(6) NULL DEFAULT CURRENT_TIMESTAMP(6);

UPDATE storage_nodes
SET last_heartbeat = CURRENT_TIMESTAMP(6)
WHERE last_heartbeat IS NULL AND is_up = TRUE;
