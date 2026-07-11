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
CREATE INDEX idx_agent_artifact_kind_create_time ON agent_context_artifact(kind, create_time);

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
    update_time TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    state TEXT NOT NULL DEFAULT 'PENDING',
    decision TEXT,
    decision_reason TEXT
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
CREATE INDEX idx_agent_memory_archive ON agent_memory(workspace_key, status, pinned, importance, COALESCE(last_used_at, created_at));

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

CREATE TABLE agent_background_shell_task (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id TEXT NOT NULL UNIQUE,
    run_id TEXT NOT NULL,
    conversation_id TEXT,
    workspace TEXT,
    command TEXT NOT NULL,
    cwd TEXT,
    launch_mode TEXT NOT NULL DEFAULT 'EXPLICIT',
    timeout_ms INTEGER NOT NULL DEFAULT 120000,
    pid INTEGER,
    status TEXT NOT NULL DEFAULT 'STARTING',
    exit_code INTEGER,
    error_code TEXT,
    error_message TEXT,
    stdout_file TEXT,
    stderr_file TEXT,
    stdout_bytes INTEGER NOT NULL DEFAULT 0,
    stderr_bytes INTEGER NOT NULL DEFAULT 0,
    started_at TEXT,
    completed_at TEXT,
    completion_notified INTEGER NOT NULL DEFAULT 0,
    create_time TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    update_time TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_bg_task_run ON agent_background_shell_task(run_id);
CREATE INDEX idx_bg_task_conversation ON agent_background_shell_task(conversation_id);
CREATE INDEX idx_bg_task_status ON agent_background_shell_task(status);

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
