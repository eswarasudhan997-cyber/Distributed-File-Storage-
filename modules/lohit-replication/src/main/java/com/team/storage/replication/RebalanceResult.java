package com.team.storage.replication;

/** Result of one bounded rebalance action. One successful call moves at most one replica. */
public record RebalanceResult(
        boolean moved,
        String fileId,
        String sourceNodeId,
        String targetNodeId,
        double imbalanceBefore,
        double imbalanceAfter,
        String message) {

    public static RebalanceResult noMove(double gap, String message) {
        return new RebalanceResult(false, null, null, null, gap, gap, message);
    }
}
