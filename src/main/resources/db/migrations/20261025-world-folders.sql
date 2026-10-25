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
