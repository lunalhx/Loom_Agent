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
