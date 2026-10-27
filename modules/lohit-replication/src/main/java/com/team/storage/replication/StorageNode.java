package com.team.storage.replication;

import java.time.Instant;
import java.util.Objects;

/** Immutable database view of a storage node used by placement and maintenance policies. */
public record StorageNode(
        String nodeId,
        String region,
        long capacityBytes,
        long usedBytes,
        boolean up,
        Instant lastHeartbeat) {

    public StorageNode {
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId is required");
        if (region == null || region.isBlank()) throw new IllegalArgumentException("region is required");
        if (capacityBytes <= 0) throw new IllegalArgumentException("capacityBytes must be positive");
        if (usedBytes < 0 || usedBytes > capacityBytes) throw new IllegalArgumentException("usedBytes must be within node capacity");
    }

    public long freeBytes() {
        return capacityBytes - usedBytes;
    }

    public double utilization() {
        return (double) usedBytes / (double) capacityBytes;
    }

    public StorageNode withUsedBytes(long newUsedBytes) {
        return new StorageNode(nodeId, region, capacityBytes, newUsedBytes, up, lastHeartbeat);
    }
}
