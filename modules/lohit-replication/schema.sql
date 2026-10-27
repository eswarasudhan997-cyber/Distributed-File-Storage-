CREATE DATABASE IF NOT EXISTS distributed_storage
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE distributed_storage;

CREATE TABLE IF NOT EXISTS storage_nodes (
  node_id VARCHAR(100) PRIMARY KEY,
  region VARCHAR(100) NOT NULL,
  capacity BIGINT NOT NULL,
  used_bytes BIGINT NOT NULL DEFAULT 0,
  is_up BOOLEAN NOT NULL DEFAULT TRUE,
  last_heartbeat TIMESTAMP(6) NULL DEFAULT CURRENT_TIMESTAMP(6),
  CHECK (capacity > 0),
  CHECK (used_bytes >= 0),
  CHECK (used_bytes <= capacity)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS stored_files (
  file_id CHAR(64) PRIMARY KEY,
  filename VARCHAR(255) NOT NULL,
  size_bytes BIGINT NOT NULL,
  sha256 CHAR(64) NOT NULL UNIQUE,
  content LONGTEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (size_bytes >= 0)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS replicas (
  file_id CHAR(64) NOT NULL,
  node_id VARCHAR(100) NOT NULL,
  PRIMARY KEY (file_id, node_id),
  CONSTRAINT fk_replica_file FOREIGN KEY (file_id)
    REFERENCES stored_files(file_id) ON DELETE CASCADE,
  CONSTRAINT fk_replica_node FOREIGN KEY (node_id)
    REFERENCES storage_nodes(node_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS audit_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event VARCHAR(80) NOT NULL,
  details TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- For an existing database created by an older version, ReplicationManager.initializeSchema()
-- checks for and adds storage_nodes.last_heartbeat as a migration-safe step.
-- This SQL demo records replica placement in metadata; physical remote byte transfer is outside scope.
