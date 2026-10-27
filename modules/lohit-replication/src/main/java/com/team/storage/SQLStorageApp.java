package com.team.storage;

import com.team.storage.replication.ReplicationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;

/** Lohit's SQL / MySQL integration contribution for the distributed storage project. */
public class SQLStorageApp {
    private final String url, user, password;

    public SQLStorageApp(String url, String user, String password) {
        this.url = Objects.requireNonNull(url);
        this.user = Objects.requireNonNull(user);
        this.password = Objects.requireNonNull(password);
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url, user, password);
        try (Statement st = c.createStatement()) { st.execute("SET time_zone = '+00:00'"); }
        return c;
    }

    public void initializeSchema() throws SQLException {
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS storage_nodes (node_id VARCHAR(100) PRIMARY KEY, region VARCHAR(100) NOT NULL, capacity BIGINT NOT NULL, used_bytes BIGINT NOT NULL DEFAULT 0, is_up BOOLEAN NOT NULL DEFAULT TRUE, last_heartbeat TIMESTAMP(6) NULL DEFAULT CURRENT_TIMESTAMP(6), CHECK (capacity > 0), CHECK (used_bytes >= 0), CHECK (used_bytes <= capacity)) ENGINE=InnoDB",
            "CREATE TABLE IF NOT EXISTS stored_files (file_id CHAR(64) PRIMARY KEY, filename VARCHAR(255) NOT NULL, size_bytes BIGINT NOT NULL, sha256 CHAR(64) NOT NULL UNIQUE, content LONGTEXT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, CHECK (size_bytes >= 0)) ENGINE=InnoDB",
            "CREATE TABLE IF NOT EXISTS replicas (file_id CHAR(64) NOT NULL, node_id VARCHAR(100) NOT NULL, PRIMARY KEY(file_id,node_id), CONSTRAINT fk_replica_file FOREIGN KEY(file_id) REFERENCES stored_files(file_id) ON DELETE CASCADE, CONSTRAINT fk_replica_node FOREIGN KEY(node_id) REFERENCES storage_nodes(node_id)) ENGINE=InnoDB",
            "CREATE TABLE IF NOT EXISTS audit_log (id BIGINT PRIMARY KEY AUTO_INCREMENT, event VARCHAR(80) NOT NULL, details TEXT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB"
        };
        try (Connection c = connect(); Statement st = c.createStatement()) {
            for (String sql : ddl) st.execute(sql);
        }
    }

    public void addNode(String nodeId, String region, long capacity) throws SQLException {
        if (nodeId == null || nodeId.isBlank() || region == null || region.isBlank() || capacity <= 0)
            throw new IllegalArgumentException("Node ID and region are required; capacity must be positive.");
        transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO storage_nodes(node_id,region,capacity) VALUES(?,?,?)")) {
                ps.setString(1, nodeId); ps.setString(2, region); ps.setLong(3, capacity); ps.executeUpdate();
            }
            audit(c, "NODE_ADDED", "node_id=" + nodeId + ", capacity=" + capacity);
            return null;
        });
    }

    /** Stores text and creates up to the requested number of replicas on healthy nodes. */
    public String uploadText(String filename, String content, int replicaCount) throws SQLException {
        if (filename == null || filename.isBlank()) throw new IllegalArgumentException("Filename cannot be blank.");
        if (content == null) throw new IllegalArgumentException("Content cannot be null.");
        if (replicaCount < 1) throw new IllegalArgumentException("Replica count must be at least one.");
        String digest = sha256(content);
        long size = content.getBytes(StandardCharsets.UTF_8).length;
        return transaction(c -> {
            try (PreparedStatement find = c.prepareStatement("SELECT file_id FROM stored_files WHERE sha256=?")) {
                find.setString(1, digest);
                try (ResultSet rs = find.executeQuery()) { if (rs.next()) return rs.getString(1); }
            }
            List<String> nodes = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT node_id FROM storage_nodes WHERE is_up=TRUE AND capacity-used_bytes>=? ORDER BY (used_bytes / capacity), node_id FOR UPDATE")) {
                ps.setLong(1, size);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next() && nodes.size() < replicaCount) nodes.add(rs.getString(1)); }
            }
            if (nodes.size() < replicaCount) throw new SQLException("Need " + replicaCount + " healthy storage nodes with enough capacity.");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO stored_files(file_id,filename,size_bytes,sha256,content) VALUES(?,?,?,?,?)")) {
                ps.setString(1, digest); ps.setString(2, filename); ps.setLong(3, size); ps.setString(4, digest); ps.setString(5, content); ps.executeUpdate();
            }
            for (String node : nodes) {
                try (PreparedStatement update = c.prepareStatement("UPDATE storage_nodes SET used_bytes=used_bytes+? WHERE node_id=?")) {
                    update.setLong(1, size); update.setString(2, node); update.executeUpdate();
                }
                try (PreparedStatement insert = c.prepareStatement("INSERT INTO replicas(file_id,node_id) VALUES(?,?)")) {
                    insert.setString(1, digest); insert.setString(2, node); insert.executeUpdate();
                }
            }
            audit(c, "FILE_UPLOADED", "file_id=" + digest + ", replicas=" + String.join(",", nodes));
            return digest;
        });
    }

    public String download(String fileId) throws SQLException {
        String sql = "SELECT f.content FROM stored_files f JOIN replicas r ON r.file_id=f.file_id JOIN storage_nodes n ON n.node_id=r.node_id WHERE f.file_id=? AND n.is_up=TRUE LIMIT 1";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, fileId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getString(1); }
        }
        throw new SQLException("File not found or no healthy replica is available.");
    }

    public boolean deleteFile(String fileId) throws SQLException {
        return transaction(c -> {
            Long size = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT size_bytes FROM stored_files WHERE file_id=? FOR UPDATE")) {
                ps.setString(1, fileId); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) size = rs.getLong(1); }
            }
            if (size == null) return false;
            try (PreparedStatement ps = c.prepareStatement("UPDATE storage_nodes n JOIN replicas r ON r.node_id=n.node_id SET n.used_bytes=GREATEST(0,n.used_bytes-?) WHERE r.file_id=?")) {
                ps.setLong(1, size); ps.setString(2, fileId); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM stored_files WHERE file_id=?")) { ps.setString(1, fileId); ps.executeUpdate(); }
            audit(c, "FILE_DELETED", "file_id=" + fileId);
            return true;
        });
    }

    public List<String> listNodes() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT node_id,region,capacity,used_bytes,is_up FROM storage_nodes ORDER BY node_id")) {
            while (rs.next()) rows.add(String.format("%s | region=%s | capacity=%d | used=%d | up=%s", rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getBoolean(5)));
        }
        return rows;
    }

    public List<String> listFiles() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT file_id,filename,size_bytes,sha256 FROM stored_files ORDER BY filename")) {
            while (rs.next()) rows.add(String.format("%s | %s | %d bytes | SHA-256=%s", rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4)));
        }
        return rows;
    }

    public List<String> listReplicas(String fileId) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("SELECT node_id FROM replicas WHERE file_id=? ORDER BY node_id")) {
            ps.setString(1, fileId); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) rows.add(rs.getString(1)); }
        }
        return rows;
    }

    public List<String> auditLog(int limit) throws SQLException {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Limit must be from 1 to 1000.");
        List<String> rows = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("SELECT id,event,details,created_at FROM audit_log ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) rows.add(String.format("%d | %s | %s | %s", rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4))); }
        }
        return rows;
    }

    private interface SqlWork<T> { T run(Connection c) throws Exception; }
    private <T> T transaction(SqlWork<T> work) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try { T result = work.run(c); c.commit(); return result; }
            catch (Exception ex) {
                try { c.rollback(); } catch (SQLException rollbackError) { ex.addSuppressed(rollbackError); }
                if (ex instanceof SQLException sql) throw sql;
                if (ex instanceof RuntimeException runtime) throw runtime;
                throw new SQLException("Transaction failed", ex);
            }
        }
    }
    private static void audit(Connection c, String event, String details) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO audit_log(event,details) VALUES(?,?)")) {
            ps.setString(1, event); ps.setString(2, details); ps.executeUpdate();
        }
    }
    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : hash) out.append(String.format("%02x", b)); return out.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    public static void main(String[] args) {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:mysql://localhost:3306/distributed_storage?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        String user = System.getenv().getOrDefault("DB_USER", "root");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "");
        SQLStorageApp app = new SQLStorageApp(url, user, password);
        try {
            app.initializeSchema();
            ReplicationManager replication = new ReplicationManager(url, user, password);
            replication.initializeSchema();
            try (Scanner sc = new Scanner(System.in)) {
                while (true) {
                    System.out.println("\nSQL / MYSQL INTEGRATION + REPLICATION MAINTENANCE");
                    System.out.println("1 Add node | 2 List nodes | 3 Upload text | 4 List files | 5 Download | 6 Delete | 7 Replicas | 8 Audit log");
                    System.out.println("9 Record heartbeat | 10 Detect failed nodes | 11 Repair under-replicated files | 12 Rebalance once | 0 Exit");
                    System.out.print("Choice: "); String choice = sc.nextLine().trim();
                    try {
                        switch (choice) {
                            case "1" -> { System.out.print("Node ID: "); String id=sc.nextLine(); System.out.print("Region: "); String region=sc.nextLine(); System.out.print("Capacity bytes: "); long cap=Long.parseLong(sc.nextLine()); app.addNode(id,region,cap); System.out.println("Node added."); }
                            case "2" -> app.listNodes().forEach(System.out::println);
                            case "3" -> { System.out.print("Filename: "); String name=sc.nextLine(); System.out.print("Text content: "); String content=sc.nextLine(); System.out.print("Replica count [3]: "); String n=sc.nextLine().trim(); int count=n.isEmpty()?3:Integer.parseInt(n); System.out.println("File ID: "+app.uploadText(name,content,count)); }
                            case "4" -> app.listFiles().forEach(System.out::println);
                            case "5" -> { System.out.print("File ID: "); System.out.println(app.download(sc.nextLine().trim())); }
                            case "6" -> { System.out.print("File ID: "); System.out.println(app.deleteFile(sc.nextLine().trim())?"Deleted.":"Not found."); }
                            case "7" -> { System.out.print("File ID: "); app.listReplicas(sc.nextLine().trim()).forEach(System.out::println); }
                            case "8" -> app.auditLog(50).forEach(System.out::println);
                            case "9" -> { System.out.print("Node ID: "); replication.recordHeartbeat(sc.nextLine().trim()); System.out.println("Heartbeat recorded."); }
                            case "10" -> {
                                System.out.print("Heartbeat timeout seconds [30]: ");
                                String value = sc.nextLine().trim();
                                long seconds = value.isEmpty() ? 30L : Long.parseLong(value);
                                System.out.println("Nodes marked down: " + replication.markTimedOutNodes(java.time.Duration.ofSeconds(seconds)));
                            }
                            case "11" -> System.out.println(replication.repairUnderReplicatedFiles(3));
                            case "12" -> {
                                System.out.print("Minimum utilization gap [0.20]: ");
                                String value = sc.nextLine().trim();
                                double threshold = value.isEmpty() ? 0.20 : Double.parseDouble(value);
                                System.out.println(replication.rebalanceOnce(threshold));
                            }
                            case "0" -> { return; }
                            default -> System.out.println("Invalid choice.");
                        }
                    } catch (Exception e) { System.out.println("Error: " + e.getMessage()); }
                }
            }
        } catch (Exception e) { System.err.println("Startup failed: " + e.getMessage()); System.err.println("Check MySQL is running and DB_URL, DB_USER, DB_PASSWORD are correct."); }
    }
}
