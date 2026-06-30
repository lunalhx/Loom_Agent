CREATE TABLE agent_run (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id TEXT NOT NULL UNIQUE,
    parent_run_id TEXT,
    root_run_id TEXT,
    request_id TEXT,
    conversation_id TEXT,
    agent_role TEXT,
    run_kind TEXT NOT NULL DEFAULT 'ROOT',
    depth INTEGER NOT NULL DEFAULT 0,
    child_ordinal INTEGER,
    question TEXT NOT NULL,
    workspace TEXT,
    status TEXT NOT NULL,
    current_node TEXT,
    step INTEGER NOT NULL DEFAULT 0,
    checkpoint_version INTEGER,
    summary_json TEXT,
    blocked_reason TEXT,
    used_tokens INTEGER NOT NULL DEFAULT 0,
    estimated_cost NUMERIC NOT NULL DEFAULT 0,
    create_time TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    update_time TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_agent_run_parent ON agent_run(parent_run_id);
CREATE INDEX idx_agent_run_root ON agent_run(root_run_id);
CREATE INDEX idx_agent_run_conversation ON agent_run(conversation_id);
CREATE INDEX idx_agent_run_status ON agent_run(status);

CREATE TABLE agent_trace_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    trace_id TEXT NOT NULL,
    root_run_id TEXT NOT NULL,
    run_id TEXT NOT NULL,
    parent_run_id TEXT,
    span_id TEXT,
    parent_span_id TEXT,
    sequence_no INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    node TEXT,
    status TEXT,
    duration_ms INTEGER,
    summary TEXT,
    error_code TEXT,
    error_message TEXT,
    token_usage_json TEXT NOT NULL,
    cost_json TEXT NOT NULL,
    metadata_json TEXT NOT NULL,
    replayable INTEGER NOT NULL DEFAULT 1,
    sensitive_redacted INTEGER NOT NULL DEFAULT 0,
    create_time TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    UNIQUE(run_id, sequence_no)
);

CREATE INDEX idx_agent_trace_trace ON agent_trace_event(trace_id);
CREATE INDEX idx_agent_trace_root ON agent_trace_event(root_run_id);
CREATE INDEX idx_agent_trace_run ON agent_trace_event(run_id);

CREATE TABLE agent_run_checkpoint (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id TEXT NOT NULL,
    version INTEGER NOT NULL,
    current_node TEXT NOT NULL,
    context_json TEXT NOT NULL,
    plan_json TEXT,
    last_tool_execution_json TEXT,
    reason TEXT,
    create_time TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    UNIQUE(run_id, version)
);

CREATE INDEX idx_agent_checkpoint_run ON agent_run_checkpoint(run_id);
CREATE INDEX idx_agent_checkpoint_create_time ON agent_run_checkpoint(create_time);

CREATE TABLE agent_context_artifact (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    artifact_id TEXT NOT NULL UNIQUE,
    run_id TEXT NOT NULL,
    root_run_id TEXT NOT NULL,
    conversation_id TEXT,
    kind TEXT NOT NULL,
    storage_uri TEXT NOT NULL,
    preview TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    original_chars INTEGER NOT NULL DEFAULT 0,
    retained_chars INTEGER NOT NULL DEFAULT 0,
    create_time TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_agent_artifact_root ON agent_context_artifact(root_run_id);
CREATE INDEX idx_agent_artifact_run ON agent_context_artifact(run_id);
CREATE INDEX idx_agent_artifact_conversation ON agent_context_artifact(conversation_id);

CREATE TABLE agent_pending_approval (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    approval_id TEXT NOT NULL UNIQUE,
    run_id TEXT,
    request_id TEXT,
    conversation_id TEXT,
    resolved_workspace TEXT,
    workspace_display_name TEXT,
    tool TEXT NOT NULL,
    input_json TEXT,
    permission_level TEXT NOT NULL,
    risk_reason TEXT,
    operation_preview TEXT,
    diff_json TEXT,
    policy_fingerprint TEXT,
    metadata_json TEXT,
    context_json TEXT,
    created_at TEXT,
    expires_at TEXT,
    consumed INTEGER NOT NULL DEFAULT 0,
    create_time TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    update_time TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_agent_approval_run ON agent_pending_approval(run_id);
CREATE INDEX idx_agent_approval_expires ON agent_pending_approval(expires_at);

CREATE TABLE agent_undo_snapshot (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    snapshot_id TEXT NOT NULL UNIQUE,
    run_id TEXT NOT NULL UNIQUE,
    conversation_id TEXT,
    workspace TEXT,
    status TEXT NOT NULL DEFAULT 'OPEN',
    before_head TEXT,
    after_head TEXT,
    branch TEXT,
    before_worktree_oid TEXT,
    before_index_oid TEXT,
    after_worktree_oid TEXT,
    after_index_oid TEXT,
    changed_paths_json TEXT,
    changed_file_count INTEGER DEFAULT 0,
    changed_byte_count INTEGER DEFAULT 0,
    unavailability_reason TEXT,
    error_info TEXT,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    finalized_at TEXT,
    undone_at TEXT,
    expires_at TEXT
);

CREATE INDEX idx_agent_undo_workspace ON agent_undo_snapshot(workspace);
CREATE INDEX idx_agent_undo_conversation ON agent_undo_snapshot(conversation_id);
CREATE INDEX idx_agent_undo_status ON agent_undo_snapshot(status);
CREATE INDEX idx_agent_undo_expires ON agent_undo_snapshot(expires_at);

CREATE TABLE agent_workspace_undo_lock (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    lock_id TEXT NOT NULL UNIQUE,
    workspace TEXT NOT NULL UNIQUE,
    holder_run_id TEXT,
    acquired_at TEXT NOT NULL,
    expires_at TEXT NOT NULL
);

CREATE INDEX idx_agent_undo_lock_expires ON agent_workspace_undo_lock(expires_at);
