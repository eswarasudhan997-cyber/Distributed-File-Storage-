package com.team.storage.replication;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ReplicaPlacementPolicyTest {
    private final ReplicaPlacementPolicy policy = new ReplicaPlacementPolicy();

    @Test
    void prefersNewRegionBeforeLowerUtilizationInExistingRegion() {
        List<StorageNode> nodes = List.of(
                new StorageNode("north-a", "north", 1000, 100, true, Instant.now()),
                new StorageNode("south-a", "south", 1000, 600, true, Instant.now()));

        List<StorageNode> ranked = policy.rankCandidates(nodes, Set.of(), Set.of("north"), 100);

        assertEquals("south-a", ranked.get(0).nodeId(), "Region diversity is preferred before load ranking");
    }

    @Test
    void filtersDownOccupiedAndInsufficientNodes() {
        List<StorageNode> nodes = List.of(
                new StorageNode("down", "east", 1000, 0, false, Instant.now()),
                new StorageNode("occupied", "west", 1000, 0, true, Instant.now()),
                new StorageNode("full", "north", 100, 90, true, Instant.now()),
                new StorageNode("valid", "south", 1000, 200, true, Instant.now()));

        List<StorageNode> ranked = policy.rankCandidates(nodes, Set.of("occupied"), Set.of(), 20);

        assertEquals(List.of("valid"), ranked.stream().map(StorageNode::nodeId).toList());
    }

    @Test
    void selectionReturnsDistinctNodesAndSpreadsRegionsWherePossible() {
        List<StorageNode> nodes = List.of(
                new StorageNode("n1", "north", 1000, 10, true, Instant.now()),
                new StorageNode("n2", "north", 1000, 20, true, Instant.now()),
                new StorageNode("s1", "south", 1000, 30, true, Instant.now()),
                new StorageNode("e1", "east", 1000, 40, true, Instant.now()));

        List<StorageNode> selected = policy.selectTargets(nodes, Set.of(), Set.of(), 50, 3);

        assertEquals(3, selected.size());
        assertEquals(3, selected.stream().map(StorageNode::nodeId).distinct().count());
        assertEquals(3, selected.stream().map(StorageNode::region).distinct().count());
    }

    @Test
    void validatesNegativeFileSizeAndRequestedCount() {
        assertThrows(IllegalArgumentException.class,
                () -> policy.rankCandidates(List.of(), Set.of(), Set.of(), -1));
        assertThrows(IllegalArgumentException.class,
                () -> policy.selectTargets(List.of(), Set.of(), Set.of(), 1, -1));
    }
}
