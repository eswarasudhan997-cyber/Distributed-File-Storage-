# Replication and Recovery Design

## Policies

- Target replication factor: 3 for the team's demo.
- A timed-out node is excluded from placement until a heartbeat marks it available again.
- A replica repair requires at least one healthy replica for that file; otherwise the file is reported in `filesWithoutHealthySource` and not fabricated.
- Candidate target nodes must be up, not already associated with the file, and have capacity for the file.
- Region diversity is preferred before choosing the lowest-utilization target; if no new-region node qualifies, the policy may use an existing region.
- All replica-row and node-capacity changes from repair or migration are committed or rolled back together.
- A rebalance pass moves at most one replica and only if max-minus-min utilization strictly decreases.

## Maintenance API

- `recordHeartbeat(nodeId)`
- `markTimedOutNodes(timeout)`
- `repairUnderReplicatedFiles(replicationFactor)`
- `rebalanceOnce(threshold)`
- `runMaintenanceCycle(timeout, replicationFactor, threshold)`

## Important limitation

The current SQL demo models replica placement in metadata. It stores the text body in one `stored_files` row and does not transfer physical bytes between independent node processes. The team's abstract treats the physical byte-transfer protocol as out of scope, but the final integration must still agree on the normalized MySQL schema.
