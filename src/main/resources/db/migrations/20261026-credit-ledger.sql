
-- 크레딧 기반. 금액이 아닌 정수 크레딧 수량이며 앱 시작 시 자동 적용하지 않는다.
CREATE TABLE IF NOT EXISTS credit_accounts (
 user_id BIGINT NOT NULL PRIMARY KEY,
 available BIGINT NOT NULL,
 reserved BIGINT NOT NULL,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 CONSTRAINT fk_credit_account_user FOREIGN KEY (user_id) REFERENCES users(id),
 CONSTRAINT ck_credit_account_balance CHECK (available >= 0 AND reserved >= 0)
);
CREATE TABLE IF NOT EXISTS credit_grants (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 source_type VARCHAR(20) NOT NULL,
 source_key VARCHAR(128) NOT NULL,
 source_hash VARCHAR(64) NOT NULL,
 amount BIGINT NOT NULL,
 available BIGINT NOT NULL,
 reserved BIGINT NOT NULL,
 consumed BIGINT NOT NULL,
 created_at DATETIME NOT NULL,
 INDEX idx_credit_grant_available (user_id,id),
 CONSTRAINT uk_credit_grant_source UNIQUE (source_type, source_hash),
 CONSTRAINT fk_credit_grant_user FOREIGN KEY (user_id) REFERENCES users(id),
 CONSTRAINT ck_credit_grant_balance CHECK (amount > 0 AND available >= 0 AND reserved >= 0 AND consumed >= 0)
);
CREATE TABLE IF NOT EXISTS credit_ledger (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 entry_key VARCHAR(80) NOT NULL,
 kind VARCHAR(20) NOT NULL,
 source_type VARCHAR(20) NULL,
 usage VARCHAR(40) NULL,
 grant_id BIGINT NULL,
 reservation_id BIGINT NULL,
 available_delta BIGINT NOT NULL,
 reserved_delta BIGINT NOT NULL,
 available_after BIGINT NOT NULL,
 reserved_after BIGINT NOT NULL,
 created_at DATETIME NOT NULL,
 INDEX idx_credit_ledger_user_id (user_id,id),
 CONSTRAINT uk_credit_ledger_entry UNIQUE (entry_key),
 CONSTRAINT fk_credit_ledger_user FOREIGN KEY (user_id) REFERENCES users(id),
 CONSTRAINT fk_credit_ledger_grant FOREIGN KEY (grant_id) REFERENCES credit_grants(id),
 CONSTRAINT ck_credit_ledger_balance CHECK (available_after >= 0 AND reserved_after >= 0)
);
