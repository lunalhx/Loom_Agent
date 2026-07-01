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
