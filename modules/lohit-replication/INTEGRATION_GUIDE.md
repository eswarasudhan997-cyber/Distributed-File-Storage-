# Integration guide for the shared project

1. Keep the existing main `StorageSystem.java` and B-Tree implementation intact while reviewing this package.
2. Treat `ReplicationManager` as an independently testable proposal. It expects `stored_files`, `storage_nodes`, `replicas`, and `audit_log` tables.
3. The final abstract's required schema names are `files`, `blocks`, `replicas`, `nodes`, and `audit_log`. Before merging, agree on one schema and adapt repository queries to it; do not ship two authoritative storage models.
4. Wire maintenance operations into the final `ClusterHealthManager`/`TaskScheduler` only after the database schema and method interfaces are agreed.
5. Run the existing 48 core regression test groups after integration, then run MySQL integration tests against a dedicated test database.
6. Do not merge until transaction rollback, node timeout, no-source repair, target-capacity checks, and rebalancing are validated with a real MySQL server.

This package is an implementation proposal. Review it and make any changes required before it is treated as an accepted contribution.
