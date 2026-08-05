-- loom-code main-loop migration: rebuild agent_run with loom-code semantics
-- and invalidate v8 non-terminal runs (checkpoint schema v9).

-- Rebuild agent_run: replace step with tool_steps, add model_attempts, last_tool,
-- stop_reason, final_answer. Map legacy statuses.
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
    tool_steps INTEGER NOT NULL DEFAULT 0,
    model_attempts INTEGER NOT NULL DEFAULT 0,
    last_tool TEXT,
    stop_reason TEXT,
    final_answer TEXT,
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
    run_kind, depth, question, workspace, status, current_node,
    tool_steps, model_attempts, last_tool, stop_reason, final_answer,
    checkpoint_version, summary_json, blocked_reason, used_tokens,
    estimated_cost, create_time, update_time
)
SELECT
    run_id, parent_run_id, root_run_id, request_id, conversation_id,
    run_kind, depth, question, workspace,
    CASE status
        WHEN 'BUDGET_EXCEEDED' THEN 'FAILED'
        WHEN 'CANCELLED' THEN 'STOPPED'
        ELSE status
    END,
    current_node,
    COALESCE(step, 0) AS tool_steps,
    0 AS model_attempts,
    NULL AS last_tool,
    CASE status
        WHEN 'COMPLETED' THEN 'FINAL_ANSWER_RETURNED'
        WHEN 'CANCELLED' THEN 'USER_CANCELLED'
        WHEN 'BUDGET_EXCEEDED' THEN 'BUDGET_EXCEEDED'
        WHEN 'FAILED' THEN 'MODEL_ERROR'
        ELSE NULL
    END AS stop_reason,
    summary_json AS final_answer,
    checkpoint_version, summary_json, blocked_reason, used_tokens,
    estimated_cost, create_time, update_time
FROM agent_run;

-- Mark old v8 non-terminal runs as FAILED/RUNTIME_SCHEMA_MISMATCH.
UPDATE agent_run_new
SET status = 'FAILED',
    stop_reason = 'RUNTIME_SCHEMA_MISMATCH',
    blocked_reason = 'RUNTIME_SCHEMA_MISMATCH'
WHERE status IN ('RUNNING', 'WAITING_APPROVAL', 'WAITING_USER_INPUT');

DROP TABLE agent_run;
ALTER TABLE agent_run_new RENAME TO agent_run;

CREATE INDEX idx_agent_run_parent ON agent_run(parent_run_id);
CREATE INDEX idx_agent_run_root ON agent_run(root_run_id);
CREATE INDEX idx_agent_run_conversation ON agent_run(conversation_id);
CREATE INDEX idx_agent_run_status ON agent_run(status);

-- Clear old v8 checkpoints and pending approvals (new context schema v9
-- is incompatible; non-terminal runs are already invalidated above).
DELETE FROM agent_run_checkpoint;
DELETE FROM agent_pending_approval;