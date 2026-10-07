-- S0/S1 초기 스키마. MySQL DB에 한 번 적용한 뒤 mysql 프로필로 validate한다.
-- 원본: https://www.erdcloud.com/d/kTau4h255aRWuyfst (2026-10-01)
-- ERD에 없는 AUTO_INCREMENT와 ai_generation_logs.user_id FK를 명시한다.
-- 앱 실행 시 자동 적용하지 않는다.

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    nickname VARCHAR(100) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE social_accounts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    provider_user_id VARBINARY(255) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_social_accounts_provider_user_id UNIQUE (provider, provider_user_id),
    CONSTRAINT fk_social_accounts_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_social_accounts_user_id ON social_accounts (user_id);

CREATE TABLE refresh_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refresh_sessions_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_sessions_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_refresh_sessions_user_id ON refresh_sessions (user_id);

CREATE TABLE refresh_session_tokens (
    session_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY (session_id, token_hash),
    CONSTRAINT uk_refresh_session_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_session_tokens_session FOREIGN KEY (session_id)
        REFERENCES refresh_sessions (id) ON DELETE CASCADE
);

CREATE TABLE dreams (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    original_text TEXT NOT NULL,
    dreamed_at DATE NULL,
    representative_emotion VARCHAR(50) NULL,
    analysis_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_dreams_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE dream_scenes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    dream_id BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    content TEXT NOT NULL,
    is_disconnected_from_previous BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_dream_scenes_dream FOREIGN KEY (dream_id) REFERENCES dreams (id)
);

CREATE TABLE dream_entities (
    id BIGINT NOT NULL AUTO_INCREMENT,
    dream_id BIGINT NOT NULL,
    entity_type VARCHAR(30) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_dream_entities_dream FOREIGN KEY (dream_id) REFERENCES dreams (id)
);

CREATE TABLE dream_scene_entities (
    dream_scene_id BIGINT NOT NULL,
    dream_entity_id BIGINT NOT NULL,
    PRIMARY KEY (dream_scene_id, dream_entity_id),
    CONSTRAINT fk_dream_scene_entities_scene FOREIGN KEY (dream_scene_id) REFERENCES dream_scenes (id),
    CONSTRAINT fk_dream_scene_entities_entity FOREIGN KEY (dream_entity_id) REFERENCES dream_entities (id)
);

-- 호출 시도별 로그. null은 미확정 값이며, 실패 응답에도 알려진 사용량은 보존한다.
CREATE TABLE ai_generation_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    task_type VARCHAR(50) NOT NULL,
    call_id VARCHAR(36) NULL,
    attempt_no INT NULL,
    provider VARCHAR(30) NULL,
    requested_model VARCHAR(100) NULL,
    model_name VARCHAR(100) NULL,
    prompt_version VARCHAR(255) NULL,
    request_id VARCHAR(255) NULL,
    http_status INT NULL,
    http_attempted BOOLEAN NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    cached_input_tokens INT NULL,
    total_tokens INT NULL,
    reasoning_tokens INT NULL,
    finish_reason VARCHAR(100) NULL,
    error_code VARCHAR(50) NULL,
    actual_cost DECIMAL(20, 8) NULL,
    cost_status VARCHAR(30) NOT NULL DEFAULT 'LEGACY',
    cost_currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    pricing_model VARCHAR(100) NULL,
    pricing_version VARCHAR(100) NULL,
    input_price DECIMAL(12, 8) NULL,
    output_price DECIMAL(12, 8) NULL,
    cached_input_price DECIMAL(12, 8) NULL,
    baseline_model VARCHAR(100) NULL,
    baseline_cost DECIMAL(12, 8) NULL,
    latency_ms BIGINT NULL,
    success BOOLEAN NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ai_generation_logs_call_attempt UNIQUE (call_id, attempt_no),
    CONSTRAINT fk_ai_generation_logs_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_ai_generation_logs_user_created ON ai_generation_logs (user_id, created_at);
