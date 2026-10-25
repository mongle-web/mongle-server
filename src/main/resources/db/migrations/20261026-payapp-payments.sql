-- 기존 #60까지 적용된 DB에 앱 중지/백업 후 수동 실행한다.
CREATE TABLE IF NOT EXISTS payment_orders (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 public_id VARCHAR(36) NOT NULL,
 user_id BIGINT NOT NULL,
 request_id VARCHAR(36) NOT NULL,
 input_hash VARCHAR(64) NOT NULL,
 notification_token VARCHAR(36) NOT NULL,
 product_id VARCHAR(50) NOT NULL,
 product_name VARCHAR(160) NOT NULL,
 price BIGINT NOT NULL,
 currency VARCHAR(3) NOT NULL,
 credits BIGINT NOT NULL,
 state VARCHAR(24) NOT NULL,
 provider_transaction VARCHAR(40) NULL,
 payment_url VARCHAR(2048) NULL,
 credit_grant_id BIGINT NULL,
 request_deadline DATETIME(6) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 CONSTRAINT uk_payment_order_public UNIQUE(public_id),
 CONSTRAINT uk_payment_order_request UNIQUE(user_id,request_id),
 INDEX idx_payment_order_user(user_id,id),
 CONSTRAINT fk_payment_order_user FOREIGN KEY(user_id) REFERENCES users(id),
 CONSTRAINT ck_payment_order_amount CHECK(price>=1000 AND credits>0)
);
CREATE TABLE IF NOT EXISTS payment_transaction_bindings (
 transaction_id VARCHAR(40) NOT NULL PRIMARY KEY,
 order_id BIGINT NOT NULL,
 INDEX idx_payment_binding_order(order_id),
 CONSTRAINT fk_payment_binding_order FOREIGN KEY(order_id) REFERENCES payment_orders(id)
);

CREATE TABLE IF NOT EXISTS payment_notifications (
 event_hash VARCHAR(64) NOT NULL PRIMARY KEY,
 order_id BIGINT NOT NULL,
 provider_transaction VARCHAR(40) NOT NULL,
 state_code INT NOT NULL,
 amount BIGINT NOT NULL,
 responded_at DATETIME(6) NOT NULL,
 created_at DATETIME NOT NULL,
 INDEX idx_payment_notification_order(order_id),
 CONSTRAINT fk_payment_notification_order FOREIGN KEY(order_id) REFERENCES payment_orders(id),
 CONSTRAINT ck_payment_notification_amount CHECK(amount>0)
);
CREATE TABLE IF NOT EXISTS credit_payment_holds (
 grant_id BIGINT NOT NULL PRIMARY KEY,
 user_id BIGINT NOT NULL,
 created_at DATETIME NOT NULL,
 INDEX idx_credit_payment_hold_user(user_id),
 CONSTRAINT fk_credit_payment_hold_user FOREIGN KEY(user_id) REFERENCES users(id)
);
