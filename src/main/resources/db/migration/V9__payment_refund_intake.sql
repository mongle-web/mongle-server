ALTER TABLE payment_orders ADD COLUMN approved_at DATETIME(6) NULL;

CREATE TABLE payment_refund_requests (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    receipt_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    grant_id BIGINT NOT NULL,
    reason VARCHAR(24) NOT NULL,
    initial_status VARCHAR(24) NOT NULL,
    available_at_request BIGINT NOT NULL,
    reserved_at_request BIGINT NOT NULL,
    used_at_request BIGINT NOT NULL,
    estimated_amount BIGINT NULL,
    fee_percent INT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_refund_receipt UNIQUE (receipt_id),
    CONSTRAINT uk_refund_user_request UNIQUE (user_id, request_id),
    CONSTRAINT uk_refund_order UNIQUE (order_id),
    CONSTRAINT uk_refund_grant UNIQUE (grant_id),
    CONSTRAINT fk_refund_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_refund_order FOREIGN KEY (order_id) REFERENCES payment_orders(id),
    CONSTRAINT fk_refund_grant FOREIGN KEY (grant_id) REFERENCES credit_grants(id)
);
CREATE INDEX idx_refund_user_page ON payment_refund_requests(user_id, id);
