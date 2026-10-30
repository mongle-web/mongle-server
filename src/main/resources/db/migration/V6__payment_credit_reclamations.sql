-- Append-only evidence of an operator-confirmed, wholly unused purchase reclamation.
-- Original grant amount and consumed credits remain unchanged.
CREATE TABLE payment_credit_reclamations (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 order_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 grant_id BIGINT NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 operator_reference VARCHAR(80) NOT NULL,
 snapshot_hash VARCHAR(64) NOT NULL,
 amount BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_reclamation_order UNIQUE (order_id),
 CONSTRAINT uk_reclamation_grant UNIQUE (grant_id),
 CONSTRAINT uk_reclamation_request UNIQUE (request_hash),
 CONSTRAINT fk_reclamation_order FOREIGN KEY (order_id) REFERENCES payment_orders(id),
 CONSTRAINT fk_reclamation_user FOREIGN KEY (user_id) REFERENCES users(id),
 CONSTRAINT fk_reclamation_grant FOREIGN KEY (grant_id) REFERENCES credit_grants(id),
 CONSTRAINT ck_reclamation_amount CHECK (amount > 0)
);
