-- MySQL 8. #13의 20261004-dream-analysis.sql까지 적용한 기존 DB에서 실행한다.
-- 신규 DB는 schema-initial.sql만 적용한다. 두 파일을 같은 DB에 중복 적용하지 않는다.
-- 앱/AI 호출을 중지한 유지보수 창에서 실행한다. DDL은 자동 커밋된다.
-- 테이블이 이미 존재하면 중단한다. 기존 데이터 삭제·임의 백필은 수행하지 않는다.

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
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_story_analysis UNIQUE (analysis_id),
    CONSTRAINT fk_story_analysis FOREIGN KEY (analysis_id) REFERENCES dream_analyses (id),
    CONSTRAINT fk_story_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_story_user_status ON dream_stories (user_id, status);
