CREATE TABLE payment_notification_recoveries (
    order_id BIGINT NOT NULL PRIMARY KEY,
    event_hash VARCHAR(64) NOT NULL,
    operator_reference VARCHAR(80) NOT NULL,
    evidence_hash VARCHAR(64) NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL,
    recovered_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_payment_recovery_event UNIQUE (event_hash),
    CONSTRAINT fk_payment_recovery_order FOREIGN KEY (order_id) REFERENCES payment_orders(id),
    CONSTRAINT fk_payment_recovery_notification FOREIGN KEY (event_hash) REFERENCES payment_notifications(event_hash)
);
