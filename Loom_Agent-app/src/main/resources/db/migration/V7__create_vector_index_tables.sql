CREATE TABLE agent_memory_vector_ref (
    memory_rowid INTEGER PRIMARY KEY AUTOINCREMENT,
    memory_id TEXT NOT NULL,
    workspace_key TEXT NOT NULL,
    content_hash TEXT NOT NULL DEFAULT '',
    embedding_model TEXT NOT NULL DEFAULT '',
    embedding_dimension INTEGER NOT NULL DEFAULT 0,
    embedded_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE UNIQUE INDEX idx_vector_ref_memory_id ON agent_memory_vector_ref(memory_id);

CREATE TABLE agent_memory_embedding_job (
    job_id TEXT NOT NULL PRIMARY KEY,
    memory_id TEXT NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('UPSERT', 'DELETE')),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    retry_count INTEGER NOT NULL DEFAULT 0,
    not_before TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    error_message TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_embed_job_status_not_before ON agent_memory_embedding_job(status, not_before);
CREATE INDEX idx_embed_job_memory_id ON agent_memory_embedding_job(memory_id);
