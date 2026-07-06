ALTER TABLE agent_pending_approval
    ADD COLUMN state TEXT NOT NULL DEFAULT 'PENDING';

ALTER TABLE agent_pending_approval
    ADD COLUMN decision TEXT;

ALTER TABLE agent_pending_approval
    ADD COLUMN decision_reason TEXT;
