-- 기존 MySQL DB용 변경 SQL. 새 버전의 앱을 실행하기 전에 수동으로 한 번 적용한다.
-- 기존 DB에는 schema-initial.sql을 실행하지 않는다.
-- 기존 닉네임은 유지한다. 적용 전에 기존 데이터와 테스트 데이터의 닉네임을 확인한다.
-- 변경된 모델에서는 닉네임이 NULL이 아니면 온보딩이 완료된 것으로 판단한다.
-- 기존 이메일만으로 소셜 계정을 만들거나 연결하지 않는다.
-- 기존 데이터와의 호환성을 위해 컬럼 길이는 100으로 유지하고, 새 입력은 2~10자로 제한한다.
ALTER TABLE users MODIFY COLUMN nickname VARCHAR(100) NULL;

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
