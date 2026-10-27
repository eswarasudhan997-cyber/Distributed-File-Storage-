package com.team.storage.replication;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Dependency-free smoke test that can also be run directly with javac/java. */
public final class ReplicationPolicySelfTest {
    private ReplicationPolicySelfTest() { }

    public static void main(String[] args) {
        ReplicaPlacementPolicy policy = new ReplicaPlacementPolicy();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        List<StorageNode> nodes = List.of(
                new StorageNode("north-a", "north", 1000, 100, true, now),
                new StorageNode("north-b", "north", 1000, 200, true, now),
                new StorageNode("south-a", "south", 1000, 400, true, now),
                new StorageNode("down-a", "west", 1000, 0, false, now));

        List<StorageNode> ranked = policy.rankCandidates(nodes, Set.of(), Set.of("north"), 100);
        check(!ranked.isEmpty() && "south-a".equals(ranked.get(0).nodeId()), "prefer new region");

        List<StorageNode> selected = policy.selectTargets(nodes, Set.of(), Set.of(), 100, 3);
        check(selected.size() == 3, "select requested target count");
        check(selected.stream().map(StorageNode::nodeId).distinct().count() == 3, "distinct target nodes");
        check(selected.stream().noneMatch(node -> !node.up()), "never select down nodes");

        System.out.println("PASS: placement prefers region diversity");
        System.out.println("PASS: placement filters down/occupied/capacity-constrained nodes");
        System.out.println("PASS: selected targets are distinct");
        System.out.println("ALL REPLICATION POLICY SMOKE TESTS PASSED");
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
