# Lohit — SQL Integration, Replication Repair, and Rebalancing (Java)

This is a proposed implementation package based on the supplied `Lohit_SQL_MySQL_Integration_Java.zip`. It retains the SQL storage demo and adds a JDBC-backed maintenance manager for heartbeats, failure detection, under-replication repair, and load rebalancing.

**Review status:** the Java source has been compiled with JDK 21, and the database-independent placement-policy smoke tests pass. Live JDBC operations have not been verified in this environment. Review and test this package before merging it into the team repository. Its contents do not by themselves establish which contributor authored each change.

## Components

- `SQLStorageApp`: SQL demo for registering storage nodes, uploading/deleting text files, viewing replicas, and inspecting audit events.
- `ReplicationManager`: updates heartbeat status, detects timed-out nodes, repairs files that have fewer than the requested number of healthy replicas, and performs at most one safe replica migration per rebalance call.
- `ReplicaPlacementPolicy`: pure placement policy that filters unavailable/capacity-constrained nodes and prefers region diversity before utilization.
- `RepairReport`, `RebalanceResult`, `MaintenanceCycleResult`: explicit results for maintenance operations.

## Requirements

- JDK 17 or newer
- Maven 3.9 or newer
- MySQL 8.x

## Configure MySQL

1. Run `schema.sql` in MySQL Workbench or the MySQL client.
2. Set connection details in your terminal. Do not commit real passwords:

```powershell
$env:DB_URL = "jdbc:mysql://localhost:3306/distributed_storage?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
$env:DB_USER = "your_local_mysql_user"
$env:DB_PASSWORD = Read-Host "MySQL password"
```

Maven resolves MySQL Connector/J from `pom.xml`; do not download or commit the driver JAR into this repository.

## Build and test

From this folder:

```powershell
mvn test
```

The JUnit tests exercise placement selection and input validation without connecting to a database. The SQL/JDBC operations require a live MySQL integration run.

Start the interactive SQL demo:

```powershell
mvn compile exec:java
```

The menu includes:
- `9` record a node heartbeat
- `10` mark nodes with expired heartbeats as unavailable
- `11` attempt repairs to the requested replication factor of 3
- `12` attempt one replica migration if the load gap exceeds a threshold

## Maintenance behavior

### Heartbeat / failure detection

`recordHeartbeat(nodeId)` marks a known node available and refreshes `last_heartbeat`. `markTimedOutNodes(timeout)` marks currently-up nodes whose last heartbeat is older than the cutoff as unavailable and audits each timeout.

### Replica repair

`repairUnderReplicatedFiles(3)` inspects stored files and counts healthy placements. It only creates a replacement placement when at least one healthy replica exists as a source. It prefers targets in regions not already represented, then lower-utilization eligible nodes. If no healthy source or capacity-eligible target exists, the report lists the affected file rather than claiming success.

### Rebalancing

`rebalanceOnce(threshold)` selects the most- and least-utilized healthy nodes, evaluates candidate files, and commits at most one move only if the resulting maximum-minus-minimum utilization gap strictly decreases. Capacity and replica metadata changes share a transaction.

## Architecture caveats to resolve before merging

This package follows the supplied SQL demo schema (`stored_files`, `storage_nodes`, `replicas`, `audit_log`). The project abstract describes a normalized `files`, `blocks`, `replicas`, `nodes`, and `audit_log` schema, and its B-Tree indexes must be rebuilt from the database source of truth. Before merging this module with the main `StorageSystem`, adapt the repository queries and models to the agreed final schema instead of maintaining two competing schemas.

The SQL demo stores text content in `stored_files`; replica placements are metadata simulation. Actual remote byte transfer remains outside this module's scope. A periodic scheduler is not started automatically; call `runMaintenanceCycle(...)` from a scheduler or invoke maintenance commands from the demo.

## Suggested Git workflow

Use the team's shared repository, create `feature/replication-repair`, keep only actual intended changes on the branch, run the tests, and submit a pull request. Commit attribution should reflect the person who actually performed the changes; review this generated/proposed implementation and adjust it before claiming authorship.
