
-- 크레딧 기반. 금액이 아닌 정수 크레딧 수량이며 앱 시작 시 자동 적용하지 않는다.
CREATE TABLE IF NOT EXISTS credit_accounts (
 user_id BIGINT NOT NULL PRIMARY KEY,
 available BIGINT NOT NULL,
 reserved BIGINT NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
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
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL,
 INDEX idx_credit_grant_available (user_id,id),
 CONSTRAINT uk_credit_grant_source UNIQUE (source_type, source_hash),
 CONSTRAINT fk_credit_grant_user FOREIGN KEY (user_id) REFERENCES users(id),
 CONSTRAINT ck_credit_grant_balance CHECK (amount > 0 AND available >= 0 AND reserved >= 0 AND consumed >= 0)
);
CREATE TABLE IF NOT EXISTS credit_reservations (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 request_key VARCHAR(128) NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 usage VARCHAR(40) NOT NULL,
 target_key VARCHAR(128) NOT NULL,
 target_hash VARCHAR(64) NOT NULL,
 amount BIGINT NOT NULL,
 state VARCHAR(20) NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 result_key VARCHAR(128) NULL,
 release_reason VARCHAR(20) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 INDEX idx_credit_reservation_expiry (state,expires_at,id),
 CONSTRAINT uk_credit_reservation_request UNIQUE (user_id,request_hash),
 CONSTRAINT uk_credit_reservation_target UNIQUE (user_id,usage,target_hash),
 CONSTRAINT fk_credit_reservation_user FOREIGN KEY (user_id) REFERENCES users(id),
 CONSTRAINT ck_credit_reservation_amount CHECK (amount > 0)
);
CREATE TABLE IF NOT EXISTS credit_allocations (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 reservation_id BIGINT NOT NULL,
 grant_id BIGINT NOT NULL,
 amount BIGINT NOT NULL,
 created_at DATETIME NOT NULL,
 INDEX idx_credit_allocation_grant (grant_id),
 CONSTRAINT uk_credit_allocation_source UNIQUE (reservation_id,grant_id),
 CONSTRAINT fk_credit_allocation_reservation FOREIGN KEY (reservation_id) REFERENCES credit_reservations(id),
 CONSTRAINT fk_credit_allocation_grant FOREIGN KEY (grant_id) REFERENCES credit_grants(id),
 CONSTRAINT ck_credit_allocation_amount CHECK (amount > 0)
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
 CONSTRAINT fk_credit_ledger_reservation FOREIGN KEY (reservation_id) REFERENCES credit_reservations(id),
 CONSTRAINT fk_credit_ledger_grant FOREIGN KEY (grant_id) REFERENCES credit_grants(id),
 CONSTRAINT ck_credit_ledger_balance CHECK (available_after >= 0 AND reserved_after >= 0)
);
