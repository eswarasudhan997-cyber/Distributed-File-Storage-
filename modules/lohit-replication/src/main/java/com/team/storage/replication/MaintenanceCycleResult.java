package com.team.storage.replication;

import java.util.List;

/** Combined result of one explicit heartbeat/failure, repair, and rebalance maintenance cycle. */
public record MaintenanceCycleResult(
        List<String> failedNodes,
        RepairReport repairReport,
        RebalanceResult rebalanceResult) {
    public MaintenanceCycleResult {
        failedNodes = List.copyOf(failedNodes);
    }
}
