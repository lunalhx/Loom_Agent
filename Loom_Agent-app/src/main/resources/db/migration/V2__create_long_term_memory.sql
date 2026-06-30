CREATE TABLE agent_memory (
    memory_id TEXT NOT NULL PRIMARY KEY,
    workspace_key TEXT NOT NULL,
    workspace_path TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('PREFERENCE', 'WORKFLOW', 'PROJECT', 'REFERENCE', 'PITFALL')),
    title TEXT NOT NULL,
    summary TEXT NOT NULL DEFAULT '',
    body TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETED')),
    pinned INTEGER NOT NULL DEFAULT 0,
    importance INTEGER NOT NULL DEFAULT 50 CHECK (importance >= 0 AND importance <= 100),
    source_type TEXT NOT NULL CHECK (source_type IN ('EXPLICIT_SAVE', 'AUTO_EXTRACTION', 'MANUAL_API')),
    source_run_id TEXT,
    content_hash TEXT NOT NULL DEFAULT '',
    version INTEGER NOT NULL DEFAULT 1,
    usage_count INTEGER NOT NULL DEFAULT 0,
    last_used_at TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_agent_memory_workspace_status_type ON agent_memory(workspace_key, status, type);
CREATE INDEX idx_agent_memory_workspace_hash ON agent_memory(workspace_key, content_hash);
CREATE INDEX idx_agent_memory_pinned_last_used ON agent_memory(pinned, last_used_at);
CREATE INDEX idx_agent_memory_last_used ON agent_memory(last_used_at);

CREATE TABLE agent_memory_revision (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    memory_id TEXT NOT NULL,
    version INTEGER NOT NULL,
    snapshot_json TEXT NOT NULL,
    source_type TEXT NOT NULL,
    source_run_id TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    FOREIGN KEY (memory_id) REFERENCES agent_memory(memory_id),
    UNIQUE(memory_id, version)
);

CREATE INDEX idx_agent_memory_revision_memory ON agent_memory_revision(memory_id);

CREATE TABLE agent_memory_generation_job (
    job_id TEXT NOT NULL PRIMARY KEY,
    source_run_id TEXT NOT NULL UNIQUE,
    workspace_key TEXT NOT NULL,
    conversation_summary_json TEXT NOT NULL DEFAULT '{}',
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'SKIPPED', 'FAILED')),
    not_before TEXT NOT NULL,
    locked_by TEXT,
    lock_expires_at TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_agent_memory_job_status_not_before ON agent_memory_generation_job(status, not_before);
CREATE INDEX idx_agent_memory_job_lock_expires ON agent_memory_generation_job(lock_expires_at);
CREATE INDEX idx_agent_memory_job_workspace ON agent_memory_generation_job(workspace_key);
