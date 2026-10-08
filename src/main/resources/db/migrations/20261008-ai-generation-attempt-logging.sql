-- 기존 MySQL DB에 운영자가 한 번 적용할 수동 마이그레이션. 앱에서 자동 실행하지 않는다.
-- 신규 DB에는 최신 schema-initial.sql만 적용한다. 이 파일을 중복 적용하지 않는다.
-- MySQL DDL은 암묵적 커밋을 수행하므로 적용 전 백업과 변경 검토가 필요하다.
-- 기존 비용·비교 비용·토큰 수를 보존한다. 과거에 0으로 저장된 미확정 사용량은 복원할 수 없다.
-- 기존 행은 LEGACY로 표시하며 call_id/attempt_no 및 관측하지 않았던 새 정보는 null로 남긴다.
ALTER TABLE ai_generation_logs
    MODIFY COLUMN model_name VARCHAR(100) NULL,
    MODIFY COLUMN prompt_version VARCHAR(255) NULL,
    MODIFY COLUMN input_tokens INT NULL,
    MODIFY COLUMN output_tokens INT NULL,
    MODIFY COLUMN cached_input_tokens INT NULL DEFAULT NULL,
    MODIFY COLUMN actual_cost DECIMAL(20, 8) NULL,
    MODIFY COLUMN baseline_model VARCHAR(100) NULL,
    MODIFY COLUMN baseline_cost DECIMAL(12, 8) NULL,
    ADD COLUMN call_id VARCHAR(36) NULL,
    ADD COLUMN attempt_no INT NULL,
    ADD COLUMN provider VARCHAR(30) NULL,
    ADD COLUMN requested_model VARCHAR(100) NULL,
    ADD COLUMN request_id VARCHAR(255) NULL,
    ADD COLUMN http_status INT NULL,
    ADD COLUMN http_attempted BOOLEAN NULL,
    ADD COLUMN total_tokens INT NULL,
    ADD COLUMN reasoning_tokens INT NULL,
    ADD COLUMN finish_reason VARCHAR(100) NULL,
    ADD COLUMN error_code VARCHAR(50) NULL,
    ADD COLUMN cost_status VARCHAR(30) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN cost_currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    ADD COLUMN pricing_model VARCHAR(100) NULL,
    ADD COLUMN pricing_version VARCHAR(100) NULL,
    ADD COLUMN input_price DECIMAL(12, 8) NULL,
    ADD COLUMN output_price DECIMAL(12, 8) NULL,
    ADD COLUMN cached_input_price DECIMAL(12, 8) NULL,
    ADD CONSTRAINT uk_ai_generation_logs_call_attempt UNIQUE (call_id, attempt_no);
CREATE INDEX idx_ai_generation_logs_user_created ON ai_generation_logs (user_id, created_at);
