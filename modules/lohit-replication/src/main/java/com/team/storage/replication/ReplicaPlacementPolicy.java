package com.team.storage.replication;

import java.util.*;
import java.util.stream.Collectors;

/** Pure placement policy: prefer a new region, then the least utilized eligible node. */
public final class ReplicaPlacementPolicy {

    /** Returns eligible targets in preferred order. Existing replica nodes are never selected. */
    public List<StorageNode> rankCandidates(
            Collection<StorageNode> nodes,
            Set<String> occupiedNodeIds,
            Set<String> representedRegions,
            long requiredBytes) {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(occupiedNodeIds, "occupiedNodeIds");
        Objects.requireNonNull(representedRegions, "representedRegions");
        if (requiredBytes < 0) throw new IllegalArgumentException("requiredBytes cannot be negative");

        return nodes.stream()
                .filter(StorageNode::up)
                .filter(node -> !occupiedNodeIds.contains(node.nodeId()))
                .filter(node -> node.freeBytes() >= requiredBytes)
                .sorted(Comparator
                        .comparing((StorageNode node) -> representedRegions.contains(node.region()) ? 1 : 0)
                        .thenComparingDouble(StorageNode::utilization)
                        .thenComparing(StorageNode::nodeId))
                .collect(Collectors.toUnmodifiableList());
    }

    /** Picks up to requestedCount different nodes and spreads picks across regions where possible. */
    public List<StorageNode> selectTargets(
            Collection<StorageNode> nodes,
            Set<String> occupiedNodeIds,
            Set<String> initiallyRepresentedRegions,
            long requiredBytes,
            int requestedCount) {
        if (requestedCount < 0) throw new IllegalArgumentException("requestedCount cannot be negative");
        Set<String> occupied = new HashSet<>(Objects.requireNonNull(occupiedNodeIds, "occupiedNodeIds"));
        Set<String> regions = new HashSet<>(Objects.requireNonNull(initiallyRepresentedRegions, "initiallyRepresentedRegions"));
        List<StorageNode> candidates = new ArrayList<>(Objects.requireNonNull(nodes, "nodes"));
        List<StorageNode> selected = new ArrayList<>();

        while (selected.size() < requestedCount) {
            List<StorageNode> ranked = rankCandidates(candidates, occupied, regions, requiredBytes);
            if (ranked.isEmpty()) break;
            StorageNode chosen = ranked.get(0);
            selected.add(chosen);
            occupied.add(chosen.nodeId());
            regions.add(chosen.region());
        }
        return List.copyOf(selected);
    }
}
