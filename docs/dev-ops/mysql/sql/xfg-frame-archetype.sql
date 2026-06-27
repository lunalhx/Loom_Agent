CREATE DATABASE IF NOT EXISTS loom_agent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE loom_agent;

CREATE TABLE IF NOT EXISTS agent_conversation (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    conversation_id VARCHAR(64) NOT NULL COMMENT '会话 ID',
    title VARCHAR(255) DEFAULT NULL COMMENT '会话标题',
    status VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '状态',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_id (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 会话表';

CREATE TABLE IF NOT EXISTS agent_message (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    message_id VARCHAR(64) NOT NULL COMMENT '消息 ID',
    conversation_id VARCHAR(64) NOT NULL COMMENT '会话 ID',
    role VARCHAR(32) NOT NULL COMMENT 'system/user/assistant/tool',
    content MEDIUMTEXT NOT NULL COMMENT '消息内容',
    model VARCHAR(64) DEFAULT NULL COMMENT '模型名称',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_id (message_id),
    KEY idx_conversation_id (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 消息表';

CREATE TABLE IF NOT EXISTS model_call_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    request_id VARCHAR(64) NOT NULL COMMENT '请求 ID',
    conversation_id VARCHAR(64) DEFAULT NULL COMMENT '会话 ID',
    provider VARCHAR(32) NOT NULL COMMENT '模型供应商',
    model VARCHAR(64) NOT NULL COMMENT '模型名称',
    status VARCHAR(32) NOT NULL COMMENT 'success/error',
    error_code VARCHAR(64) DEFAULT NULL COMMENT '错误码',
    prompt_tokens INT DEFAULT NULL COMMENT '输入 token',
    completion_tokens INT DEFAULT NULL COMMENT '输出 token',
    total_tokens INT DEFAULT NULL COMMENT '总 token',
    latency_ms BIGINT DEFAULT NULL COMMENT '耗时毫秒',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_request_id (request_id),
    KEY idx_conversation_id (conversation_id),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型调用日志表';

CREATE TABLE IF NOT EXISTS agent_run (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    run_id VARCHAR(64) NOT NULL COMMENT 'Agent run ID',
    parent_run_id VARCHAR(64) DEFAULT NULL COMMENT '父 Agent run ID',
    root_run_id VARCHAR(64) DEFAULT NULL COMMENT '根 Agent run ID',
    request_id VARCHAR(64) DEFAULT NULL COMMENT '请求 ID',
    conversation_id VARCHAR(64) DEFAULT NULL COMMENT '会话 ID',
    agent_role VARCHAR(32) DEFAULT NULL COMMENT 'ROOT/EXPLORER/EDITOR/REVIEWER',
    run_kind VARCHAR(32) NOT NULL DEFAULT 'ROOT' COMMENT 'ROOT/CHILD',
    depth INT NOT NULL DEFAULT 0 COMMENT '子 Agent 深度',
    child_ordinal INT DEFAULT NULL COMMENT '父 run 下的派生顺序',
    question VARCHAR(4000) NOT NULL COMMENT '用户任务',
    workspace VARCHAR(512) DEFAULT NULL COMMENT '工作区展示名',
    status VARCHAR(32) NOT NULL COMMENT 'RUNNING/WAITING_APPROVAL/WAITING_USER_INPUT/COMPLETED/FAILED/BUDGET_EXCEEDED',
    current_node VARCHAR(64) DEFAULT NULL COMMENT '当前节点',
    step INT NOT NULL DEFAULT 0 COMMENT '当前步骤',
    checkpoint_version BIGINT DEFAULT NULL COMMENT '最新 checkpoint 版本',
    summary_json MEDIUMTEXT DEFAULT NULL COMMENT '子 Agent 摘要 JSON',
    blocked_reason VARCHAR(1024) DEFAULT NULL COMMENT '预算或策略阻断原因',
    used_tokens BIGINT NOT NULL DEFAULT 0 COMMENT 'root run 累计模型 token',
    estimated_cost DECIMAL(18, 8) NOT NULL DEFAULT 0 COMMENT 'root run 估算模型成本',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_run_id (run_id),
    KEY idx_parent_run_id (parent_run_id),
    KEY idx_root_run_id (root_run_id),
    KEY idx_conversation_id (conversation_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 运行表';

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

CREATE TABLE IF NOT EXISTS agent_run_checkpoint (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    run_id VARCHAR(64) NOT NULL COMMENT 'Agent run ID',
    version BIGINT NOT NULL COMMENT 'checkpoint 版本',
    current_node VARCHAR(64) NOT NULL COMMENT '恢复节点',
    context_json LONGTEXT NOT NULL COMMENT 'AgentContextSnapshot JSON',
    plan_json MEDIUMTEXT DEFAULT NULL COMMENT 'AgentPlan JSON',
    last_tool_execution_json MEDIUMTEXT DEFAULT NULL COMMENT '最近工具调用快照',
    reason VARCHAR(255) DEFAULT NULL COMMENT '保存原因',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_run_version (run_id, version),
    KEY idx_run_id (run_id),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent Checkpoint 表';

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

CREATE TABLE IF NOT EXISTS agent_pending_approval (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    approval_id VARCHAR(64) NOT NULL COMMENT '审批 ID',
    run_id VARCHAR(64) DEFAULT NULL COMMENT 'Agent run ID',
    request_id VARCHAR(64) DEFAULT NULL COMMENT '请求 ID',
    conversation_id VARCHAR(64) DEFAULT NULL COMMENT '会话 ID',
    resolved_workspace VARCHAR(1024) DEFAULT NULL COMMENT '解析后的工作区',
    workspace_display_name VARCHAR(512) DEFAULT NULL COMMENT '工作区展示名',
    tool VARCHAR(128) NOT NULL COMMENT '工具名',
    input_json MEDIUMTEXT DEFAULT NULL COMMENT '工具输入摘要 JSON',
    permission_level VARCHAR(32) NOT NULL COMMENT '权限等级',
    risk_reason VARCHAR(512) DEFAULT NULL COMMENT '风险原因',
    operation_preview MEDIUMTEXT DEFAULT NULL COMMENT '操作预览',
    diff_json MEDIUMTEXT DEFAULT NULL COMMENT '审批 diff JSON',
    policy_fingerprint VARCHAR(64) DEFAULT NULL COMMENT '审批绑定的策略清单指纹',
    metadata_json MEDIUMTEXT DEFAULT NULL COMMENT '审批展示元数据 JSON',
    context_json LONGTEXT DEFAULT NULL COMMENT '审批暂停时的上下文快照',
    created_at DATETIME DEFAULT NULL COMMENT '审批创建时间',
    expires_at DATETIME DEFAULT NULL COMMENT '审批过期时间',
    consumed TINYINT NOT NULL DEFAULT 0 COMMENT '是否已消费',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_approval_id (approval_id),
    KEY idx_run_id (run_id),
    KEY idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 待审批表';
