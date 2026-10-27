# Manual MySQL integration checklist

Use only a dedicated development database. Never point destructive tests at production data.

## Setup

1. Start MySQL Server 8.x.
2. Run `schema.sql` to create `distributed_storage` and the four tables used by this SQL demo.
3. Set `DB_URL`, `DB_USER`, and `DB_PASSWORD` in the same terminal used to start the application.
4. Run `mvn test`, then `mvn compile exec:java`.

## Exercise replication

1. Add at least four nodes with differing capacities and at least two regions.
2. Upload a text file with replica count `3`; verify the result in menu item `7`.
3. Make one node stale by setting its `last_heartbeat` to an old timestamp in a dev database, or wait beyond a small timeout.
4. Run menu item `10` with a timeout in seconds.
5. Run menu item `11` to repair under-replicated files.
6. Query `replicas` and `storage_nodes` and confirm that the number of healthy placements is three when enough healthy nodes and capacity exist.
7. If all replica nodes are down, confirm that repair reports no healthy source rather than claiming success.
8. To test recovery, run menu item `9` for a known node and confirm `is_up=TRUE` and `last_heartbeat` is refreshed.
9. To test rebalancing, create unequal node utilization, run menu item `12` with a threshold such as `0.10`, and verify a migration occurs only when the utilization gap decreases.
10. Query `audit_log` to confirm timeout, repair, and migration events are recorded.

## Queries for verification

```sql
SELECT node_id, region, capacity, used_bytes, is_up, last_heartbeat
FROM storage_nodes ORDER BY node_id;

SELECT r.file_id, r.node_id, n.region, n.is_up
FROM replicas r JOIN storage_nodes n ON n.node_id = r.node_id
ORDER BY r.file_id, r.node_id;

SELECT id, event, details, created_at FROM audit_log ORDER BY id DESC LIMIT 50;
```

## Expected limits

This is a metadata simulation: `stored_files.content` holds text centrally, while `replicas` tracks planned placements. It does not open remote node processes or copy byte streams. The abstract treats physical transfer as out of scope; document that limitation in demonstrations.
