package com.team.storage.replication;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ReplicationManagerValidationTest {
    private final ReplicationManager manager = new ReplicationManager(
            "jdbc:mysql://localhost:3306/distributed_storage", "user", "password");

    @Test
    void rejectsZeroOrNegativeHeartbeatTimeoutBeforeConnecting() {
        assertThrows(IllegalArgumentException.class, () -> manager.markTimedOutNodes(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> manager.markTimedOutNodes(Duration.ofSeconds(-1)));
    }

    @Test
    void rejectsInvalidReplicationFactorBeforeConnecting() {
        assertThrows(IllegalArgumentException.class, () -> manager.repairUnderReplicatedFiles(0));
    }

    @Test
    void rejectsInvalidLoadThresholdBeforeConnecting() {
        assertThrows(IllegalArgumentException.class, () -> manager.rebalanceOnce(-0.1));
        assertThrows(IllegalArgumentException.class, () -> manager.rebalanceOnce(1.1));
        assertThrows(IllegalArgumentException.class, () -> manager.rebalanceOnce(Double.NaN));
    }
}
