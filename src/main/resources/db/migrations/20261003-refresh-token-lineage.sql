-- 기존 인증 DB에서 서버 배포 전에 실행한다. 토큰 원문은 저장하지 않는다.
CREATE TABLE IF NOT EXISTS refresh_session_tokens (
    session_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY (session_id, token_hash),
    CONSTRAINT uk_refresh_session_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_session_tokens_session FOREIGN KEY (session_id)
        REFERENCES refresh_sessions (id) ON DELETE CASCADE
);

-- 배포 이전에 이미 교체된 해시는 복원할 수 없으므로 이력 없는 세션은 폐기한다.
-- 기존 사용자는 다시 로그인해야 한다. 새 코드가 생성한 세션은 재실행해도 유지된다.
DELETE FROM refresh_sessions
WHERE NOT EXISTS (
    SELECT 1 FROM refresh_session_tokens t WHERE t.session_id = refresh_sessions.id
);
