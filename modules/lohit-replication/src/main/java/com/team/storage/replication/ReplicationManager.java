package com.team.storage.replication;

import java.sql.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * JDBC-backed replication maintenance for the SQLStorageApp schema.
 *
 * The supplied SQL demo keeps text content in stored_files and represents physical placement in the
 * replicas table; actual byte transfer to remote machines remains outside this simulation's scope.
 * Every metadata/capacity change made by this class is transactional.
 */
public final class ReplicationManager {
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final Clock clock;
    private final ReplicaPlacementPolicy placementPolicy;

    public ReplicationManager(String jdbcUrl, String username, String password) {
        this(jdbcUrl, username, password, Clock.systemUTC(), new ReplicaPlacementPolicy());
    }

    ReplicationManager(String jdbcUrl, String username, String password, Clock clock,
                       ReplicaPlacementPolicy placementPolicy) {
        this.jdbcUrl = requireText(jdbcUrl, "jdbcUrl");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.placementPolicy = Objects.requireNonNull(placementPolicy, "placementPolicy");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET time_zone = '+00:00'");
        }
        return connection;
    }

    /** Creates the companion tables and adds last_heartbeat to existing storage_nodes schemas. */
    public void initializeSchema() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS storage_nodes (" +
                    "node_id VARCHAR(100) PRIMARY KEY, region VARCHAR(100) NOT NULL, " +
                    "capacity BIGINT NOT NULL, used_bytes BIGINT NOT NULL DEFAULT 0, " +
                    "is_up BOOLEAN NOT NULL DEFAULT TRUE, last_heartbeat TIMESTAMP(6) NULL DEFAULT CURRENT_TIMESTAMP(6), " +
                    "CHECK (capacity > 0), CHECK (used_bytes >= 0), CHECK (used_bytes <= capacity)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE IF NOT EXISTS stored_files (" +
                    "file_id CHAR(64) PRIMARY KEY, filename VARCHAR(255) NOT NULL, size_bytes BIGINT NOT NULL, " +
                    "sha256 CHAR(64) NOT NULL UNIQUE, content LONGTEXT NOT NULL, " +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, CHECK (size_bytes >= 0)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE IF NOT EXISTS replicas (" +
                    "file_id CHAR(64) NOT NULL, node_id VARCHAR(100) NOT NULL, " +
                    "PRIMARY KEY(file_id,node_id), " +
                    "CONSTRAINT fk_replica_file FOREIGN KEY(file_id) REFERENCES stored_files(file_id) ON DELETE CASCADE, " +
                    "CONSTRAINT fk_replica_node FOREIGN KEY(node_id) REFERENCES storage_nodes(node_id)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE IF NOT EXISTS audit_log (" +
                    "id BIGINT PRIMARY KEY AUTO_INCREMENT, event VARCHAR(80) NOT NULL, details TEXT NOT NULL, " +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB");
        }
        ensureHeartbeatColumn();
    }

    private void ensureHeartbeatColumn() throws SQLException {
        try (Connection connection = connect()) {
            boolean found;
            try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null,
                    "storage_nodes", "last_heartbeat")) {
                found = columns.next();
            }
            if (!found) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE storage_nodes ADD COLUMN last_heartbeat TIMESTAMP(6) NULL DEFAULT CURRENT_TIMESTAMP(6)");
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE storage_nodes SET last_heartbeat=CURRENT_TIMESTAMP(6) WHERE last_heartbeat IS NULL AND is_up=TRUE");
            }
        }
    }

    /** Runs one explicit maintenance cycle: mark timed-out nodes, repair, then move at most one replica. */
    public MaintenanceCycleResult runMaintenanceCycle(Duration heartbeatTimeout, int replicationFactor,
                                                       double imbalanceThreshold) throws SQLException {
        List<String> failedNodes = markTimedOutNodes(heartbeatTimeout);
        RepairReport repairReport = repairUnderReplicatedFiles(replicationFactor);
        RebalanceResult rebalanceResult = rebalanceOnce(imbalanceThreshold);
        return new MaintenanceCycleResult(failedNodes, repairReport, rebalanceResult);
    }

    /** Refreshes a known node's heartbeat and marks it available again. */
    public void recordHeartbeat(String nodeId) throws SQLException {
        requireText(nodeId, "nodeId");
        transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE storage_nodes SET is_up=TRUE, last_heartbeat=? WHERE node_id=?")) {
                statement.setTimestamp(1, Timestamp.from(clock.instant()));
                statement.setString(2, nodeId);
                if (statement.executeUpdate() != 1) throw new SQLException("Unknown storage node: " + nodeId);
            }
            audit(connection, "HEARTBEAT_RECEIVED", "node_id=" + nodeId);
            return null;
        });
    }

    /** Marks nodes with old heartbeats as down and returns their IDs in stable order. */
    public List<String> markTimedOutNodes(Duration timeout) throws SQLException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        Instant cutoff = clock.instant().minus(timeout);
        return transaction(connection -> {
            List<String> failed = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT node_id FROM storage_nodes WHERE is_up=TRUE AND (last_heartbeat IS NULL OR last_heartbeat < ?) ORDER BY node_id FOR UPDATE")) {
                select.setTimestamp(1, Timestamp.from(cutoff));
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) failed.add(rows.getString(1));
                }
            }
            for (String nodeId : failed) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE storage_nodes SET is_up=FALSE WHERE node_id=? AND is_up=TRUE")) {
                    update.setString(1, nodeId);
                    update.executeUpdate();
                }
                audit(connection, "NODE_TIMED_OUT", "node_id=" + nodeId + ", cutoff=" + cutoff);
            }
            return List.copyOf(failed);
        });
    }

    /**
     * Attempts to restore each file to replicationFactor healthy replicas. It never fabricates a
     * repair if no healthy replica can act as a source. An operation is committed per file.
     */
    public RepairReport repairUnderReplicatedFiles(int replicationFactor) throws SQLException {
        if (replicationFactor < 1) throw new IllegalArgumentException("replicationFactor must be at least 1");
        List<String> fileIds = readFileIds();
        int created = 0;
        List<String> underReplicated = new ArrayList<>();
        List<String> noSource = new ArrayList<>();
        for (String fileId : fileIds) {
            RepairOneResult result = repairOne(fileId, replicationFactor);
            created += result.created;
            if (result.underReplicated) underReplicated.add(fileId);
            if (result.noHealthySource) noSource.add(fileId);
        }
        return new RepairReport(fileIds.size(), created, underReplicated, noSource);
    }

    private List<String> readFileIds() throws SQLException {
        List<String> fileIds = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT file_id FROM stored_files ORDER BY file_id")) {
            while (rows.next()) fileIds.add(rows.getString(1));
        }
        return fileIds;
    }

    private RepairOneResult repairOne(String fileId, int targetFactor) throws SQLException {
        return transaction(connection -> {
            long size;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT size_bytes FROM stored_files WHERE file_id=? FOR UPDATE")) {
                statement.setString(1, fileId);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) return new RepairOneResult(0, false, false);
                    size = row.getLong(1);
                }
            }

            List<ReplicaRow> existing = readReplicaRows(connection, fileId);
            Set<String> occupied = new HashSet<>();
            Set<String> healthyRegions = new HashSet<>();
            int healthyCount = 0;
            for (ReplicaRow replica : existing) {
                occupied.add(replica.nodeId);
                if (replica.nodeUp) {
                    healthyCount++;
                    healthyRegions.add(replica.region);
                }
            }
            if (healthyCount >= targetFactor) return new RepairOneResult(0, false, false);
            if (healthyCount == 0) {
                audit(connection, "REPAIR_BLOCKED_NO_SOURCE", "file_id=" + fileId);
                return new RepairOneResult(0, true, true);
            }

            List<StorageNode> nodes = readNodes(connection, true);
            int created = 0;
            while (healthyCount < targetFactor) {
                List<StorageNode> candidates = placementPolicy.rankCandidates(nodes, occupied, healthyRegions, size);
                if (candidates.isEmpty()) break;
                StorageNode target = candidates.get(0);

                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE storage_nodes SET used_bytes=used_bytes+? " +
                        "WHERE node_id=? AND is_up=TRUE AND capacity-used_bytes>=?")) {
                    update.setLong(1, size);
                    update.setString(2, target.nodeId());
                    update.setLong(3, size);
                    if (update.executeUpdate() != 1) throw new SQLException("Target node capacity changed during repair: " + target.nodeId());
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO replicas(file_id,node_id) VALUES(?,?)")) {
                    insert.setString(1, fileId);
                    insert.setString(2, target.nodeId());
                    insert.executeUpdate();
                }
                audit(connection, "REPLICA_REPAIRED", "file_id=" + fileId + ", target_node=" + target.nodeId());
                occupied.add(target.nodeId());
                healthyRegions.add(target.region());
                healthyCount++;
                created++;
                replaceNode(nodes, target.withUsedBytes(target.usedBytes() + size));
            }
            boolean stillUnder = healthyCount < targetFactor;
            if (stillUnder) audit(connection, "FILE_UNDER_REPLICATED", "file_id=" + fileId + ", healthy=" + healthyCount + ", desired=" + targetFactor);
            return new RepairOneResult(created, stillUnder, false);
        });
    }

    /** Performs at most one replica move, and only when it strictly reduces utilization range. */
    public RebalanceResult rebalanceOnce(double threshold) throws SQLException {
        if (!Double.isFinite(threshold) || threshold < 0.0 || threshold > 1.0)
            throw new IllegalArgumentException("threshold must be between 0 and 1");
        return transaction(connection -> {
            List<StorageNode> nodes = readNodes(connection, true).stream().filter(StorageNode::up).toList();
            if (nodes.size() < 2) return RebalanceResult.noMove(0, "At least two healthy nodes are required");
            double before = imbalance(nodes);
            if (before <= threshold) return RebalanceResult.noMove(before, "Load is within the configured threshold");

            StorageNode source = nodes.stream().max(Comparator.comparingDouble(StorageNode::utilization)
                    .thenComparing(StorageNode::nodeId)).orElseThrow();
            StorageNode target = nodes.stream().min(Comparator.comparingDouble(StorageNode::utilization)
                    .thenComparing(StorageNode::nodeId)).orElseThrow();
            if (source.nodeId().equals(target.nodeId())) return RebalanceResult.noMove(before, "No distinct source and target");

            List<MoveCandidate> candidates = readMoveCandidates(connection, source.nodeId(), target.nodeId(), target.freeBytes());
            MoveCandidate best = null;
            double bestAfter = before;
            int bestRegionPenalty = Integer.MAX_VALUE;
            for (MoveCandidate candidate : candidates) {
                if (candidate.sizeBytes <= 0 || candidate.sizeBytes > target.freeBytes()) continue;
                double after = projectedImbalance(nodes, source.nodeId(), target.nodeId(), candidate.sizeBytes);
                if (after >= before - 1e-12) continue;
                int regionPenalty = candidate.targetRegionAlreadyRepresented ? 1 : 0;
                if (best == null || regionPenalty < bestRegionPenalty ||
                        (regionPenalty == bestRegionPenalty && after < bestAfter)) {
                    best = candidate;
                    bestAfter = after;
                    bestRegionPenalty = regionPenalty;
                }
            }
            if (best == null) return RebalanceResult.noMove(before, "No safe migration strictly improves load balance");

            try (PreparedStatement updateTarget = connection.prepareStatement(
                    "UPDATE storage_nodes SET used_bytes=used_bytes+? WHERE node_id=? AND is_up=TRUE AND capacity-used_bytes>=?")) {
                updateTarget.setLong(1, best.sizeBytes); updateTarget.setString(2, target.nodeId()); updateTarget.setLong(3, best.sizeBytes);
                if (updateTarget.executeUpdate() != 1) throw new SQLException("Target capacity changed during migration");
            }
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO replicas(file_id,node_id) VALUES(?,?)")) {
                insert.setString(1, best.fileId); insert.setString(2, target.nodeId()); insert.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM replicas WHERE file_id=? AND node_id=?")) {
                delete.setString(1, best.fileId); delete.setString(2, source.nodeId());
                if (delete.executeUpdate() != 1) throw new SQLException("Source replica disappeared during migration");
            }
            try (PreparedStatement updateSource = connection.prepareStatement(
                    "UPDATE storage_nodes SET used_bytes=used_bytes-? WHERE node_id=? AND used_bytes>=?")) {
                updateSource.setLong(1, best.sizeBytes); updateSource.setString(2, source.nodeId()); updateSource.setLong(3, best.sizeBytes);
                if (updateSource.executeUpdate() != 1) throw new SQLException("Source capacity accounting is inconsistent");
            }
            audit(connection, "REPLICA_MIGRATED", "file_id=" + best.fileId + ", from=" + source.nodeId() + ", to=" + target.nodeId());
            return new RebalanceResult(true, best.fileId, source.nodeId(), target.nodeId(), before, bestAfter,
                    "Moved one replica and reduced utilization imbalance");
        });
    }

    private List<MoveCandidate> readMoveCandidates(Connection connection, String sourceNode, String targetNode,
                                                   long targetFree) throws SQLException {
        List<MoveCandidate> result = new ArrayList<>();
        String sql = "SELECT f.file_id,f.size_bytes, " +
                "EXISTS(SELECT 1 FROM replicas rt JOIN storage_nodes nt ON nt.node_id=rt.node_id " +
                "WHERE rt.file_id=f.file_id AND nt.region=(SELECT region FROM storage_nodes WHERE node_id=?)) AS target_region_used " +
                "FROM stored_files f JOIN replicas rs ON rs.file_id=f.file_id " +
                "WHERE rs.node_id=? AND f.size_bytes<=? " +
                "AND NOT EXISTS (SELECT 1 FROM replicas rt WHERE rt.file_id=f.file_id AND rt.node_id=?) " +
                "ORDER BY f.size_bytes DESC, f.file_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, targetNode); statement.setString(2, sourceNode); statement.setLong(3, targetFree); statement.setString(4, targetNode);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new MoveCandidate(rows.getString(1), rows.getLong(2), rows.getBoolean(3)));
            }
        }
        return result;
    }

    private static double imbalance(List<StorageNode> nodes) {
        double min = nodes.stream().mapToDouble(StorageNode::utilization).min().orElse(0);
        double max = nodes.stream().mapToDouble(StorageNode::utilization).max().orElse(0);
        return max - min;
    }

    private static double projectedImbalance(List<StorageNode> nodes, String sourceId, String targetId, long bytes) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (StorageNode node : nodes) {
            long used = node.usedBytes();
            if (node.nodeId().equals(sourceId)) used -= bytes;
            if (node.nodeId().equals(targetId)) used += bytes;
            double utilization = (double) used / node.capacityBytes();
            min = Math.min(min, utilization);
            max = Math.max(max, utilization);
        }
        return max - min;
    }

    private static void replaceNode(List<StorageNode> nodes, StorageNode replacement) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).nodeId().equals(replacement.nodeId())) {
                nodes.set(i, replacement);
                return;
            }
        }
    }

    private static List<StorageNode> readNodes(Connection connection, boolean lock) throws SQLException {
        List<StorageNode> nodes = new ArrayList<>();
        String sql = "SELECT node_id,region,capacity,used_bytes,is_up,last_heartbeat FROM storage_nodes ORDER BY node_id" + (lock ? " FOR UPDATE" : "");
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                Timestamp heartbeat = rows.getTimestamp("last_heartbeat");
                nodes.add(new StorageNode(rows.getString("node_id"), rows.getString("region"), rows.getLong("capacity"),
                        rows.getLong("used_bytes"), rows.getBoolean("is_up"), heartbeat == null ? null : heartbeat.toInstant()));
            }
        }
        return nodes;
    }

    private static List<ReplicaRow> readReplicaRows(Connection connection, String fileId) throws SQLException {
        List<ReplicaRow> replicas = new ArrayList<>();
        String sql = "SELECT r.node_id,n.region,n.is_up FROM replicas r JOIN storage_nodes n ON n.node_id=r.node_id " +
                "WHERE r.file_id=? ORDER BY r.node_id FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, fileId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) replicas.add(new ReplicaRow(rows.getString(1), rows.getString(2), rows.getBoolean(3)));
            }
        }
        return replicas;
    }

    private interface SqlWork<T> { T run(Connection connection) throws Exception; }

    private <T> T transaction(SqlWork<T> work) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                if (exception instanceof SQLException sql) throw sql;
                if (exception instanceof RuntimeException runtime) throw runtime;
                throw new SQLException("Replication transaction failed", exception);
            }
        }
    }

    private static void audit(Connection connection, String event, String details) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO audit_log(event,details) VALUES(?,?)")) {
            statement.setString(1, event); statement.setString(2, details); statement.executeUpdate();
        }
    }

    private record ReplicaRow(String nodeId, String region, boolean nodeUp) { }
    private record RepairOneResult(int created, boolean underReplicated, boolean noHealthySource) { }
    private record MoveCandidate(String fileId, long sizeBytes, boolean targetRegionAlreadyRepresented) { }
}
