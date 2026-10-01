-- S0/S1 초기 스키마. MySQL DB에 한 번 적용한 뒤 mysql 프로필로 validate한다.
-- 원본: https://www.erdcloud.com/d/kTau4h255aRWuyfst (2026-10-01)
-- ERD에 없는 AUTO_INCREMENT와 ai_generation_logs.user_id FK를 명시한다.
-- 앱 실행 시 자동 적용하지 않는다.

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    nickname VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id)
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

CREATE TABLE ai_generation_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    task_type VARCHAR(50) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(50) NULL,
    input_tokens INT NOT NULL,
    output_tokens INT NOT NULL,
    cached_input_tokens INT NOT NULL DEFAULT 0,
    actual_cost DECIMAL(12, 8) NOT NULL,
    baseline_model VARCHAR(100) NOT NULL,
    baseline_cost DECIMAL(12, 8) NOT NULL,
    latency_ms BIGINT NULL,
    success BOOLEAN NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ai_generation_logs_user FOREIGN KEY (user_id) REFERENCES users (id)
);
