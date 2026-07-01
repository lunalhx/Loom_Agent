CREATE TABLE agent_conversation_deletion (
    conversation_id TEXT NOT NULL PRIMARY KEY,
    status TEXT NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED', 'WAITING_FOR_RUNS', 'PURGING', 'COMPLETED', 'FAILED')),
    requested_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    completed_at TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    locked_by TEXT,
    lock_expires_at TEXT
);

CREATE INDEX idx_agent_conv_del_status ON agent_conversation_deletion(status);
CREATE INDEX idx_agent_conv_del_lock_expires ON agent_conversation_deletion(lock_expires_at);
