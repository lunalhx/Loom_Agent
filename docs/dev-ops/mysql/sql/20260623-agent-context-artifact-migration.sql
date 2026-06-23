CREATE TABLE IF NOT EXISTS agent_context_artifact (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    artifact_id VARCHAR(64) NOT NULL COMMENT 'Artifact ID',
    run_id VARCHAR(64) NOT NULL COMMENT 'Agent run ID',
    root_run_id VARCHAR(64) NOT NULL COMMENT '根 Agent run ID',
    conversation_id VARCHAR(64) DEFAULT NULL COMMENT '会话 ID',
    kind VARCHAR(32) NOT NULL COMMENT 'TOOL_RESULT/TRANSCRIPT',
    storage_uri VARCHAR(1024) NOT NULL COMMENT '正文存储 URI',
    preview TEXT NOT NULL COMMENT '预览文本',
    sha256 VARCHAR(64) NOT NULL COMMENT '正文 SHA-256',
    original_chars INT NOT NULL DEFAULT 0 COMMENT '原始字符数',
    retained_chars INT NOT NULL DEFAULT 0 COMMENT '保留在活跃上下文的字符数',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_artifact_id (artifact_id),
    KEY idx_root_run_id (root_run_id),
    KEY idx_run_id (run_id),
    KEY idx_conversation_id (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent Context Artifact 表';
