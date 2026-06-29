-- Historical migration for an existing database.
ALTER TABLE agent_run
    ADD COLUMN parent_run_id VARCHAR(64) DEFAULT NULL COMMENT '父 Agent run ID' AFTER run_id,
    ADD COLUMN root_run_id VARCHAR(64) DEFAULT NULL COMMENT '根 Agent run ID' AFTER parent_run_id,
    ADD COLUMN agent_role VARCHAR(32) DEFAULT NULL COMMENT 'ROOT/EXPLORER/EDITOR/REVIEWER' AFTER conversation_id,
    ADD COLUMN run_kind VARCHAR(32) NOT NULL DEFAULT 'ROOT' COMMENT 'ROOT/CHILD' AFTER agent_role,
    ADD COLUMN depth INT NOT NULL DEFAULT 0 COMMENT '子 Agent 深度' AFTER run_kind,
    ADD COLUMN child_ordinal INT DEFAULT NULL COMMENT '父 run 下的派生顺序' AFTER depth,
    ADD COLUMN summary_json MEDIUMTEXT DEFAULT NULL COMMENT '子 Agent 摘要 JSON' AFTER checkpoint_version,
    ADD KEY idx_parent_run_id (parent_run_id),
    ADD KEY idx_root_run_id (root_run_id);

UPDATE agent_run
SET root_run_id = run_id
WHERE root_run_id IS NULL;
