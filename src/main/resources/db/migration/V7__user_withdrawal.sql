-- Expand only. Enable the feature after migration, key provisioning and worker configuration.
ALTER TABLE users ADD COLUMN withdrawn_at DATETIME(6) NULL;
ALTER TABLE users ADD COLUMN retention_until DATETIME(6) NULL;
CREATE INDEX idx_users_withdrawal_retention ON users(withdrawn_at,retention_until,id);
ALTER TABLE credit_grants ADD COLUMN forfeited BIGINT NOT NULL DEFAULT 0;
CREATE TABLE signup_reward_history (
 identity_hash VARCHAR(64) NOT NULL PRIMARY KEY,
 benefit VARCHAR(30) NOT NULL,
 granted_at DATETIME(6) NOT NULL
);
-- No FK to users: unlink and signup history survive removal of a non-paying account.
CREATE TABLE social_unlink_tasks (
 identity_hash VARCHAR(64) NOT NULL PRIMARY KEY,
 user_id BIGINT NOT NULL,
 encrypted_provider_id VARCHAR(1024) NULL,
 state VARCHAR(20) NOT NULL,
 attempt_count INT NOT NULL DEFAULT 0,
 claim_token VARCHAR(36) NULL,
 next_run_at DATETIME(6) NOT NULL,
 failure_code VARCHAR(40) NULL,
 version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_unlink_due ON social_unlink_tasks(state,next_run_at);
CREATE INDEX idx_unlink_user ON social_unlink_tasks(user_id);
