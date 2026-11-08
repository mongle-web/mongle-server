CREATE TABLE payment_refund_follow_ups (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    refund_id BIGINT NOT NULL,
    additional_amount BIGINT NOT NULL,
    confirmed_total BIGINT NOT NULL,
    operator_reference VARCHAR(80) NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_refund_follow_up_resolution FOREIGN KEY (refund_id) REFERENCES payment_refund_resolutions(refund_id),
    CONSTRAINT uk_refund_follow_up_reference UNIQUE (refund_id, operator_reference),
    CONSTRAINT uk_refund_follow_up_id_refund UNIQUE (id, refund_id),
    CONSTRAINT ck_refund_follow_up_amount CHECK (additional_amount > 0 AND confirmed_total > additional_amount)
);

ALTER TABLE payment_refund_evidence ADD COLUMN follow_up_id BIGINT NULL;
ALTER TABLE payment_refund_evidence ADD CONSTRAINT fk_refund_evidence_follow_up
    FOREIGN KEY (follow_up_id, refund_id) REFERENCES payment_refund_follow_ups(id, refund_id);
