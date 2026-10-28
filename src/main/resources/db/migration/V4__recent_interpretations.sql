-- V1 또는 baseline 1이 등록된 환경에 추가한다. 기존 테이블/데이터는 변경하지 않는다.
CREATE TABLE recent_interpretations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    ever_completed BIT NOT NULL,
    attempt_id VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    target_fingerprint VARCHAR(64) NULL,
    attempt_dream_ids TEXT NULL,
    generation_settings TEXT NULL,
    failure_code VARCHAR(50) NULL,
    body TEXT NULL,
    generated_at DATETIME(6) NULL,
    result_fingerprint VARCHAR(64) NULL,
    result_sources TEXT NULL,
    prompt_version VARCHAR(50) NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_recent_interpretation_user UNIQUE (user_id),
    CONSTRAINT fk_recent_interpretation_user FOREIGN KEY (user_id) REFERENCES users(id)
);
