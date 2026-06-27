-- Git Ghost Snapshot Per-Run Undo: persistence tables
-- Run this migration against the loom-agent database.

CREATE TABLE IF NOT EXISTS agent_undo_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_id VARCHAR(64) NOT NULL UNIQUE,
    run_id VARCHAR(64) NOT NULL UNIQUE,
    conversation_id VARCHAR(64) DEFAULT NULL,
    workspace VARCHAR(1024) DEFAULT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    before_head VARCHAR(64) DEFAULT NULL,
    after_head VARCHAR(64) DEFAULT NULL,
    branch VARCHAR(256) DEFAULT NULL,
    before_worktree_oid VARCHAR(64) DEFAULT NULL,
    before_index_oid VARCHAR(64) DEFAULT NULL,
    after_worktree_oid VARCHAR(64) DEFAULT NULL,
    after_index_oid VARCHAR(64) DEFAULT NULL,
    changed_paths_json MEDIUMTEXT DEFAULT NULL,
    changed_file_count INT DEFAULT 0,
    changed_byte_count BIGINT DEFAULT 0,
    unavailability_reason VARCHAR(512) DEFAULT NULL,
    error_info TEXT DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finalized_at DATETIME DEFAULT NULL,
    undone_at DATETIME DEFAULT NULL,
    expires_at DATETIME DEFAULT NULL,
    KEY idx_run_id (run_id),
    KEY idx_workspace (workspace(255)),
    KEY idx_conversation_id (conversation_id),
    KEY idx_status (status),
    KEY idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_workspace_undo_lock (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_id VARCHAR(64) NOT NULL UNIQUE,
    workspace VARCHAR(1024) NOT NULL UNIQUE,
    holder_run_id VARCHAR(64) DEFAULT NULL,
    acquired_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NOT NULL,
    KEY idx_workspace (workspace(255)),
    KEY idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
