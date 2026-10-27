package com.team.storage.replication;

import java.util.List;

/** Summary of one repair pass. Files in underReplicatedFileIds still need operator attention. */
public record RepairReport(int filesInspected, int replicasCreated, List<String> underReplicatedFileIds,
                           List<String> filesWithoutHealthySource) {
    public RepairReport {
        underReplicatedFileIds = List.copyOf(underReplicatedFileIds);
        filesWithoutHealthySource = List.copyOf(filesWithoutHealthySource);
    }
}
