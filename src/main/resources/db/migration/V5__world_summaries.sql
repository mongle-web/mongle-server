-- 기존 완성본·이미지·크레딧과 적용된 마이그레이션은 변경하지 않는다.
CREATE TABLE world_summaries (
    world_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    requested_revision BIGINT NOT NULL,
    attempt_id VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    attempt_fingerprint VARCHAR(64) NULL,
    attempt_settings TEXT NULL,
    failure_code VARCHAR(50) NULL,
    result_json TEXT NULL,
    result_revision BIGINT NULL,
    result_fingerprint VARCHAR(64) NULL,
    result_settings TEXT NULL,
    prompt_version VARCHAR(50) NULL,
    generated_at DATETIME(6) NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY(world_id),
    CONSTRAINT fk_world_summary_folder FOREIGN KEY(world_id) REFERENCES world_folders(id),
    CONSTRAINT fk_world_summary_user FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE INDEX idx_world_summary_work ON world_summaries(status,world_id);
CREATE TABLE world_summary_sources (
    id BIGINT NOT NULL AUTO_INCREMENT,
    world_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    dream_id BIGINT NOT NULL,
    story_version_id BIGINT NOT NULL,
    source_revision BIGINT NOT NULL,
    observed_revision BIGINT NOT NULL,
    position BIGINT NOT NULL,
    bridge_id BIGINT NULL,
    candidate BIT NOT NULL,
    PRIMARY KEY(id),
    CONSTRAINT uk_world_summary_source UNIQUE(world_id,candidate,dream_id),
    CONSTRAINT fk_world_summary_source_world FOREIGN KEY(world_id) REFERENCES world_summaries(world_id),
    CONSTRAINT fk_world_summary_source_user FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE INDEX idx_world_summary_source_dream ON world_summary_sources(user_id,dream_id,world_id);
