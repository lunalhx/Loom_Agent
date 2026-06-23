ALTER TABLE agent_run
    ADD COLUMN blocked_reason VARCHAR(1024) DEFAULT NULL COMMENT '预算或策略阻断原因' AFTER summary_json,
    ADD COLUMN used_tokens BIGINT NOT NULL DEFAULT 0 COMMENT 'root run 累计模型 token' AFTER blocked_reason,
    ADD COLUMN estimated_cost DECIMAL(18, 8) NOT NULL DEFAULT 0 COMMENT 'root run 估算模型成本' AFTER used_tokens;

CREATE TABLE IF NOT EXISTS agent_trace_event (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    trace_id VARCHAR(64) NOT NULL COMMENT 'Trace ID',
    root_run_id VARCHAR(64) NOT NULL COMMENT '根 Agent run ID',
    run_id VARCHAR(64) NOT NULL COMMENT 'Agent run ID',
    parent_run_id VARCHAR(64) DEFAULT NULL COMMENT '父 Agent run ID',
    span_id VARCHAR(64) DEFAULT NULL COMMENT 'Span ID',
    parent_span_id VARCHAR(64) DEFAULT NULL COMMENT '父 Span ID',
    sequence_no BIGINT NOT NULL COMMENT 'run 内单调序号',
    event_type VARCHAR(64) NOT NULL COMMENT 'node_start/node_end/stop/model_usage',
    node VARCHAR(64) DEFAULT NULL COMMENT '节点名',
    status VARCHAR(64) DEFAULT NULL COMMENT '事件状态',
    duration_ms BIGINT DEFAULT NULL COMMENT '耗时毫秒',
    summary VARCHAR(2000) DEFAULT NULL COMMENT '摘要',
    error_code VARCHAR(64) DEFAULT NULL COMMENT '错误码',
    error_message VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
    token_usage_json TEXT NOT NULL COMMENT '模型 token usage JSON',
    cost_json TEXT NOT NULL COMMENT '模型成本 JSON',
    metadata_json TEXT NOT NULL COMMENT '事件元数据 JSON',
    replayable TINYINT NOT NULL DEFAULT 1 COMMENT '是否可用于回放',
    sensitive_redacted TINYINT NOT NULL DEFAULT 0 COMMENT '是否已脱敏或截断',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_run_sequence (run_id, sequence_no),
    KEY idx_trace_id (trace_id),
    KEY idx_root_run_id (root_run_id),
    KEY idx_run_id (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent Trace Event 表';
