CREATE INDEX IF NOT EXISTS idx_agent_memory_archive
ON agent_memory(workspace_key, status, pinned, importance, COALESCE(last_used_at, created_at));
