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
    dreamed_at DATE NOT NULL,
    title VARCHAR(100) NULL,
    record_status VARCHAR(30) NOT NULL DEFAULT 'EMOTION_PENDING',
    is_edited BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 0,
    source_revision BIGINT NOT NULL DEFAULT 0,
    analysis_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_dreams_user_date UNIQUE (user_id, dreamed_at),
    CONSTRAINT fk_dreams_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_dreams_user_status_date_id ON dreams(user_id, record_status, dreamed_at, id);

CREATE TABLE dream_emotions (
    dream_id BIGINT NOT NULL,
    emotion VARCHAR(20) NOT NULL,
    CONSTRAINT uk_dream_emotions UNIQUE (dream_id, emotion),
    CONSTRAINT fk_dream_emotions_dream FOREIGN KEY (dream_id) REFERENCES dreams (id)
);

CREATE TABLE dream_analyses (
 id BIGINT NOT NULL AUTO_INCREMENT,
 dream_id BIGINT NULL,
 user_id BIGINT NOT NULL,
 source_dream_id BIGINT NOT NULL,
 dreamed_at DATE NOT NULL,
 source_revision BIGINT NOT NULL,
 observed_revision BIGINT NOT NULL,
 result_revision BIGINT NULL,
 source_text TEXT NULL,
 source_emotions VARCHAR(100) NULL,
 generation_settings TEXT NULL,
 pending_result_json TEXT NULL,
 regenerating BOOLEAN NOT NULL DEFAULT FALSE,
 generated_title VARCHAR(40) NULL,
 prompt_version VARCHAR(50) NOT NULL,
 status VARCHAR(30) NOT NULL,
 attempt_id VARCHAR(36) NULL,
 lease_until DATETIME(6) NULL,
 failure_code VARCHAR(50) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 PRIMARY KEY (id),
 CONSTRAINT uk_dream_analysis_dream UNIQUE (dream_id),
 CONSTRAINT uk_dream_analysis_source UNIQUE (source_dream_id),
 CONSTRAINT fk_dream_analysis_user FOREIGN KEY (user_id) REFERENCES users(id),
 CONSTRAINT fk_dream_analysis_dream FOREIGN KEY (dream_id) REFERENCES dreams(id)
);
CREATE INDEX idx_analysis_user_status_date ON dream_analyses(user_id,status,dreamed_at);

CREATE TABLE dream_analysis_display_keywords (
    analysis_id BIGINT NOT NULL,
    keyword_order INT NOT NULL,
    keyword VARCHAR(40) NOT NULL,
    PRIMARY KEY (analysis_id, keyword_order),
    CONSTRAINT fk_analysis_display_keywords FOREIGN KEY (analysis_id)
        REFERENCES dream_analyses(id) ON DELETE CASCADE
);

CREATE TABLE dream_scenes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    dream_id BIGINT NULL,
    analysis_id BIGINT NULL,
    user_id BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    content TEXT NOT NULL,
    is_disconnected_from_previous BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_analysis_scene_sequence UNIQUE (analysis_id, sequence_no),
    CONSTRAINT fk_dream_scenes_analysis FOREIGN KEY (analysis_id) REFERENCES dream_analyses(id),
    CONSTRAINT fk_dream_scenes_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_dream_scenes_dream FOREIGN KEY (dream_id) REFERENCES dreams (id)
);

CREATE TABLE dream_entities (
    id BIGINT NOT NULL AUTO_INCREMENT,
    dream_id BIGINT NULL,
    analysis_id BIGINT NULL,
    user_id BIGINT NOT NULL,
    entity_type VARCHAR(30) NOT NULL,
    name VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255) NULL,
    description TEXT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_analysis_entity_name UNIQUE (analysis_id, entity_type, normalized_name),
    CONSTRAINT fk_dream_entities_analysis FOREIGN KEY (analysis_id) REFERENCES dream_analyses(id),
    CONSTRAINT fk_dream_entities_user FOREIGN KEY (user_id) REFERENCES users (id),
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

CREATE TABLE dream_stories (
    id BIGINT NOT NULL AUTO_INCREMENT,
    analysis_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    source_revision BIGINT NOT NULL,
    prompt_version VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL,
    lease_until DATETIME(6) NULL,
    failure_code VARCHAR(50) NULL,
    result_json TEXT NULL,
    result_revision BIGINT NULL,
    result_prompt_version VARCHAR(50) NULL,
    generation_settings TEXT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_story_analysis UNIQUE (analysis_id),
    CONSTRAINT fk_story_analysis FOREIGN KEY (analysis_id) REFERENCES dream_analyses (id),
    CONSTRAINT fk_story_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_story_user_status ON dream_stories (user_id, status);

CREATE TABLE dream_story_versions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    story_id BIGINT NOT NULL,
    generation_key VARCHAR(36) NOT NULL,
    source_revision BIGINT NOT NULL,
    prompt_version VARCHAR(50) NOT NULL,
    result_json TEXT NOT NULL,
    source_text TEXT NULL,
    source_emotions VARCHAR(100) NULL,
    analysis_json TEXT NULL,
    analysis_prompt_version VARCHAR(50) NULL,
    analysis_settings TEXT NULL,
    story_settings TEXT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_story_version_generation UNIQUE (story_id, generation_key),
    INDEX idx_story_version_story_id (story_id, id),
    CONSTRAINT fk_story_version_story FOREIGN KEY (story_id) REFERENCES dream_stories (id)
);

CREATE TABLE dream_images (
    id BIGINT NOT NULL AUTO_INCREMENT,
    analysis_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    source_revision BIGINT NOT NULL,
    story_hash VARCHAR(64) NOT NULL,
    style VARCHAR(50) NOT NULL,
    mood VARCHAR(50) NULL,
    status VARCHAR(30) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL,
    credit_reservation_id BIGINT NULL,
    lease_until DATETIME(6) NULL,
    failure_code VARCHAR(50) NULL,
    storage_key VARCHAR(255) NULL,
    content_type VARCHAR(30) NULL,
    width INT NULL,
    height INT NULL,
    result_revision BIGINT NULL,
    result_story_hash VARCHAR(64) NULL,
    result_style VARCHAR(50) NULL,
    result_mood VARCHAR(50) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_image_analysis UNIQUE (analysis_id),
    CONSTRAINT fk_image_analysis FOREIGN KEY (analysis_id) REFERENCES dream_analyses (id),
    CONSTRAINT fk_image_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- 감정 선택 완료와 같은 트랜잭션에서 예약하는 자동 생성 작업. 원문·감정은 복제하지 않는다.
CREATE TABLE dream_generation_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    dream_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    source_revision BIGINT NOT NULL,
    stage VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    claim_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    next_run_at DATETIME(6) NOT NULL,
    recovering BOOLEAN NOT NULL DEFAULT FALSE,
    regeneration BOOLEAN NOT NULL DEFAULT FALSE,
    failure_code VARCHAR(50) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_generation_job_dream UNIQUE (dream_id)
);
CREATE INDEX idx_generation_job_due ON dream_generation_jobs(status,next_run_at,id);
CREATE INDEX idx_generation_job_user ON dream_generation_jobs(user_id,status,lease_until);

CREATE TABLE world_bridge_results (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    before_dream_id BIGINT NOT NULL,
    after_dream_id BIGINT NOT NULL,
    before_version_id BIGINT NOT NULL,
    after_version_id BIGINT NOT NULL,
    prompt_version VARCHAR(50) NOT NULL,
    settings_json TEXT NOT NULL,
    settings_hash VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL,
    lease_until DATETIME(6) NULL,
    failure_code VARCHAR(50) NULL,
    content TEXT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_bridge_inputs UNIQUE (user_id, before_version_id, after_version_id, prompt_version, settings_hash),
    INDEX idx_bridge_before_dream (before_dream_id),
    INDEX idx_bridge_after_dream (after_dream_id),
    CONSTRAINT fk_bridge_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_bridge_before_dream FOREIGN KEY (before_dream_id) REFERENCES dreams (id),
    CONSTRAINT fk_bridge_after_dream FOREIGN KEY (after_dream_id) REFERENCES dreams (id),
    CONSTRAINT fk_bridge_before_version FOREIGN KEY (before_version_id) REFERENCES dream_story_versions (id),
    CONSTRAINT fk_bridge_after_version FOREIGN KEY (after_version_id) REFERENCES dream_story_versions (id)
);

-- 꿈 삭제 커밋 이후 처리할 내부 파일 정리 대상. 삭제된 원본으로 향하는 FK는 두지 않는다.
-- 정리 워커는 기본 비활성이다. 상태 COMPLETED만 멱등 삭제 성공을 뜻한다.
CREATE TABLE IF NOT EXISTS image_cleanup_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    dream_id BIGINT NULL,
    image_id BIGINT NOT NULL,
    storage_key VARCHAR(255) NOT NULL,
    reason VARCHAR(30) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    claim_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    next_run_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    deletion_started_at DATETIME(6) NULL,
    failure_code VARCHAR(50) NULL,
    requeue_requested BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_image_cleanup_key UNIQUE (storage_key)
);
CREATE INDEX idx_cleanup_due ON image_cleanup_requests(status,next_run_at,id);
CREATE INDEX idx_cleanup_lease ON image_cleanup_requests(status,lease_until,id);
CREATE INDEX idx_image_storage_key ON dream_images(storage_key);
CREATE INDEX idx_image_attempt_expiry ON dream_images(status,lease_until,id);

-- 기존 MySQL DB에 수동 적용. 적용 전 백업 및 대상 DB를 확인한다.
-- 신규 DB에는 schema-initial.sql을 사용한다. 회원·꿈·AI 결과를 변경하지 않는다.
-- MySQL 연결·DB 문자 집합은 utf8mb4여야 이모지를 100 코드포인트까지 저장할 수 있다.
CREATE TABLE IF NOT EXISTS world_folders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    selected_dream_count BIGINT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_world_folder_selection_count CHECK (selected_dream_count >= 0),
    INDEX idx_world_folder_user_id (user_id, id),
    CONSTRAINT fk_world_folder_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS world_selections (
    id BIGINT NOT NULL AUTO_INCREMENT,
    world_id BIGINT NOT NULL,
    dream_id BIGINT NOT NULL,
    dreamed_at DATE NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_world_selection_dream UNIQUE (world_id, dream_id),
    INDEX idx_world_selection_date (world_id, dreamed_at, dream_id),
    INDEX idx_world_selection_dream (dream_id),
    CONSTRAINT fk_world_selection_folder FOREIGN KEY (world_id) REFERENCES world_folders(id),
    CONSTRAINT fk_world_selection_dream FOREIGN KEY (dream_id) REFERENCES dreams(id)
);

-- 크레딧 기반. 금액이 아닌 정수 크레딧 수량이며 앱 시작 시 자동 적용하지 않는다.
CREATE TABLE IF NOT EXISTS credit_accounts (
 user_id BIGINT NOT NULL PRIMARY KEY,
 available BIGINT NOT NULL,
 reserved BIGINT NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 CONSTRAINT fk_credit_account_user FOREIGN KEY (user_id) REFERENCES users(id),
 CONSTRAINT ck_credit_account_balance CHECK (available >= 0 AND reserved >= 0)
);
CREATE TABLE IF NOT EXISTS credit_grants (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 source_type VARCHAR(20) NOT NULL,
 source_key VARCHAR(128) NOT NULL,
 source_hash VARCHAR(64) NOT NULL,
 amount BIGINT NOT NULL,
 available BIGINT NOT NULL,
 reserved BIGINT NOT NULL,
 consumed BIGINT NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL,
 INDEX idx_credit_grant_available (user_id,id),
 CONSTRAINT uk_credit_grant_source UNIQUE (source_type, source_hash),
 CONSTRAINT fk_credit_grant_user FOREIGN KEY (user_id) REFERENCES users(id),
 CONSTRAINT ck_credit_grant_balance CHECK (amount > 0 AND available >= 0 AND reserved >= 0 AND consumed >= 0)
);
CREATE TABLE IF NOT EXISTS credit_reservations (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 request_key VARCHAR(128) NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 `usage` VARCHAR(40) NOT NULL,
 target_key VARCHAR(128) NOT NULL,
 target_hash VARCHAR(64) NOT NULL,
 amount BIGINT NOT NULL,
 state VARCHAR(20) NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 result_key VARCHAR(128) NULL,
 release_reason VARCHAR(20) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 INDEX idx_credit_reservation_expiry (state,expires_at,id),
 CONSTRAINT uk_credit_reservation_request UNIQUE (user_id,request_hash),
 CONSTRAINT uk_credit_reservation_target UNIQUE (user_id,`usage`,target_hash),
 CONSTRAINT fk_credit_reservation_user FOREIGN KEY (user_id) REFERENCES users(id),
 CONSTRAINT ck_credit_reservation_amount CHECK (amount > 0)
);
CREATE TABLE IF NOT EXISTS credit_allocations (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 reservation_id BIGINT NOT NULL,
 grant_id BIGINT NOT NULL,
 amount BIGINT NOT NULL,
 created_at DATETIME NOT NULL,
 INDEX idx_credit_allocation_grant (grant_id),
 CONSTRAINT uk_credit_allocation_source UNIQUE (reservation_id,grant_id),
 CONSTRAINT fk_credit_allocation_reservation FOREIGN KEY (reservation_id) REFERENCES credit_reservations(id),
 CONSTRAINT fk_credit_allocation_grant FOREIGN KEY (grant_id) REFERENCES credit_grants(id),
 CONSTRAINT ck_credit_allocation_amount CHECK (amount > 0)
);
CREATE TABLE IF NOT EXISTS credit_ledger (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 entry_key VARCHAR(80) NOT NULL,
 kind VARCHAR(20) NOT NULL,
 source_type VARCHAR(20) NULL,
 `usage` VARCHAR(40) NULL,
 grant_id BIGINT NULL,
 reservation_id BIGINT NULL,
 available_delta BIGINT NOT NULL,
 reserved_delta BIGINT NOT NULL,
 available_after BIGINT NOT NULL,
 reserved_after BIGINT NOT NULL,
 created_at DATETIME NOT NULL,
 INDEX idx_credit_ledger_user_id (user_id,id),
 CONSTRAINT uk_credit_ledger_entry UNIQUE (entry_key),
 CONSTRAINT fk_credit_ledger_user FOREIGN KEY (user_id) REFERENCES users(id),
 CONSTRAINT fk_credit_ledger_reservation FOREIGN KEY (reservation_id) REFERENCES credit_reservations(id),
 CONSTRAINT fk_credit_ledger_grant FOREIGN KEY (grant_id) REFERENCES credit_grants(id),
 CONSTRAINT ck_credit_ledger_balance CHECK (available_after >= 0 AND reserved_after >= 0)
);

-- 페이앱 크레딧 충전 주문
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

-- Existing DB: stop app/workers and back up before applying. Requires world folders and successful story versions.
CREATE TABLE IF NOT EXISTS world_compositions (
 world_id BIGINT NOT NULL PRIMARY KEY,
 user_id BIGINT NOT NULL,
 composition_revision BIGINT NOT NULL DEFAULT 0,
 has_result BOOLEAN NOT NULL DEFAULT FALSE,
 status VARCHAR(30) NOT NULL,
 attempt_id VARCHAR(36) NULL,
 folder_revision BIGINT NOT NULL DEFAULT 0,
 base_revision BIGINT NOT NULL DEFAULT 0,
 request_type VARCHAR(10) NULL,
 target_dream_id BIGINT NULL,
 target_version_id BIGINT NULL,
 settings_json TEXT NULL,
 prompt_version VARCHAR(50) NULL,
 retrying BOOLEAN NOT NULL DEFAULT FALSE,
 lease_until DATETIME(6) NULL,
 claim_token VARCHAR(36) NULL,
 claim_until DATETIME(6) NULL,
 failure_code VARCHAR(50) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 INDEX idx_composition_work(status,world_id),
 CONSTRAINT fk_composition_world FOREIGN KEY(world_id) REFERENCES world_folders(id),
 CONSTRAINT fk_composition_user FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE TABLE IF NOT EXISTS world_composition_entries (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 world_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 candidate BOOLEAN NOT NULL,
 position BIGINT NOT NULL,
 dream_id BIGINT NOT NULL,
 story_version_id BIGINT NOT NULL,
 bridge_id BIGINT NULL,
 needs_bridge BOOLEAN NOT NULL,
 created_at DATETIME NOT NULL,
 CONSTRAINT uk_composition_position UNIQUE(world_id,candidate,position),
 INDEX idx_composition_dream(dream_id,world_id),
 CONSTRAINT fk_composition_entry_world FOREIGN KEY(world_id) REFERENCES world_compositions(world_id)
);

-- Apply after world-composition and image-credit migrations, with app/workers stopped and a backup.
CREATE TABLE IF NOT EXISTS world_images (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 world_id BIGINT NOT NULL,
 composition_revision BIGINT NOT NULL,
 style VARCHAR(50) NOT NULL,
 mood VARCHAR(50) NULL,
 status VARCHAR(30) NOT NULL,
 attempt_id VARCHAR(36) NOT NULL,
 credit_reservation_id BIGINT NULL,
 lease_until DATETIME(6) NULL,
 failure_code VARCHAR(50) NULL,
 storage_key VARCHAR(255) NULL,
 content_type VARCHAR(30) NULL,
 width INT NULL,
 height INT NULL,
 result_composition_revision BIGINT NULL,
 result_source_deleted BOOLEAN NOT NULL DEFAULT FALSE,
 result_style VARCHAR(50) NULL,
 result_mood VARCHAR(50) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 CONSTRAINT uk_world_image_world UNIQUE(world_id),
 INDEX idx_world_image_expiry(status,lease_until,id),
 INDEX idx_world_image_key(storage_key),
 CONSTRAINT fk_world_image_world FOREIGN KEY(world_id) REFERENCES world_folders(id),
 CONSTRAINT fk_world_image_user FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE TABLE IF NOT EXISTS world_image_sources (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 image_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 dream_id BIGINT NOT NULL,
 story_version_id BIGINT NOT NULL,
 source_revision BIGINT NOT NULL,
 bridge_id BIGINT NULL,
 candidate BOOLEAN NOT NULL,
 CONSTRAINT uk_world_image_source UNIQUE(image_id,candidate,dream_id),
 INDEX idx_world_image_source_dream(user_id,dream_id,image_id),
 CONSTRAINT fk_world_image_source_image FOREIGN KEY(image_id) REFERENCES world_images(id)
);
