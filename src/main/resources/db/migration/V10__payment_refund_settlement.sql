-- Keep historical rejected/withdrawn receipts and allow a fresh request for the purchase.
-- Intake and resolution serialize on the same users row.
CREATE INDEX idx_refund_order ON payment_refund_requests(order_id);
CREATE INDEX idx_refund_grant ON payment_refund_requests(grant_id);
ALTER TABLE payment_refund_requests DROP INDEX uk_refund_order;
ALTER TABLE payment_refund_requests DROP INDEX uk_refund_grant;

CREATE TABLE payment_refund_resolutions (
    refund_id BIGINT NOT NULL PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    refunded_amount BIGINT NOT NULL,
    revoked_credits BIGINT NOT NULL,
    released_payment_hold BOOLEAN NOT NULL,
    basis VARCHAR(12) NOT NULL,
    operator_reference VARCHAR(80) NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL,
    resolved_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_refund_resolution_request FOREIGN KEY (refund_id) REFERENCES payment_refund_requests(id),
    CONSTRAINT ck_refund_resolution CHECK (
        (status = 'COMPLETED' AND refunded_amount > 0 AND revoked_credits >= 0)
        OR (status IN ('REJECTED', 'WITHDRAWN') AND refunded_amount = 0 AND revoked_credits = 0
            AND released_payment_hold = FALSE)
    )
);

CREATE TABLE payment_refund_evidence (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    refund_id BIGINT NOT NULL,
    provider_transaction VARCHAR(40) NOT NULL,
    amount BIGINT NOT NULL,
    full_cancellation BOOLEAN NOT NULL,
    CONSTRAINT uk_refund_evidence_transaction UNIQUE (provider_transaction),
    CONSTRAINT fk_refund_evidence_resolution FOREIGN KEY (refund_id) REFERENCES payment_refund_resolutions(refund_id),
    CONSTRAINT ck_refund_evidence_amount CHECK (amount > 0)
);
