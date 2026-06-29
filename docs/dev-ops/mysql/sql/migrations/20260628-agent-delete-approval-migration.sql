-- Historical migration for an existing database.
ALTER TABLE agent_pending_approval
    ADD COLUMN policy_fingerprint VARCHAR(64) DEFAULT NULL COMMENT '审批绑定的策略清单指纹' AFTER diff_json,
    ADD COLUMN metadata_json MEDIUMTEXT DEFAULT NULL COMMENT '审批展示元数据 JSON' AFTER policy_fingerprint;
