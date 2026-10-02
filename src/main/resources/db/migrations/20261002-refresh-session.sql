-- 기존 MySQL DB에 인증 세션 테이블을 추가한다. 자동 실행하지 않는다.
-- 재발급 토큰 원문은 저장하지 않고 SHA-256 해시만 저장한다.

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
