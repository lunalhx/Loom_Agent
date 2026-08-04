-- loom-code runtime migration: drop removed subsystems and rebuild agent_run schema.
-- V1-V5 are unchanged.

-- Context artifacts (context_recall / ContextArtifactService) removed.
DROP TABLE IF EXISTS agent_context_artifact;

-- Undo subsystem removed.
DROP TABLE IF EXISTS agent_undo_snapshot;
DROP TABLE IF EXISTS agent_workspace_undo_lock;

-- Long-term memory / embedding subsystem removed.
DROP TABLE IF EXISTS agent_memory;
DROP TABLE IF EXISTS agent_memory_revision;
DROP TABLE IF EXISTS agent_memory_generation_job;
DROP TABLE IF EXISTS agent_memory_vector_ref;
DROP TABLE IF EXISTS agent_memory_embedding_job;

-- Background shell task subsystem removed.
DROP TABLE IF EXISTS agent_background_shell_task;

-- Rebuild agent_run: keep parent/root/depth for delegate, drop agent_role and child_ordinal.
CREATE TABLE agent_run_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id TEXT NOT NULL UNIQUE,
    parent_run_id TEXT,
    root_run_id TEXT,
    request_id TEXT,
    conversation_id TEXT,
    run_kind TEXT NOT NULL DEFAULT 'ROOT',
    depth INTEGER NOT NULL DEFAULT 0,
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

INSERT INTO agent_run_new (
    run_id, parent_run_id, root_run_id, request_id, conversation_id,
    run_kind, depth, question, workspace, status, current_node, step,
    checkpoint_version, summary_json, blocked_reason, used_tokens,
    estimated_cost, create_time, update_time
)
SELECT
    run_id, parent_run_id, root_run_id, request_id, conversation_id,
    run_kind, depth, question, workspace, status, current_node, step,
    checkpoint_version, summary_json, blocked_reason, used_tokens,
    estimated_cost, create_time, update_time
FROM agent_run;

DROP TABLE agent_run;
ALTER TABLE agent_run_new RENAME TO agent_run;

CREATE INDEX idx_agent_run_parent ON agent_run(parent_run_id);
CREATE INDEX idx_agent_run_root ON agent_run(root_run_id);
CREATE INDEX idx_agent_run_conversation ON agent_run(conversation_id);
CREATE INDEX idx_agent_run_status ON agent_run(status);

-- Clear old checkpoints (new context schema incompatible) and pending approvals.
DELETE FROM agent_run_checkpoint;
DELETE FROM agent_pending_approval;
