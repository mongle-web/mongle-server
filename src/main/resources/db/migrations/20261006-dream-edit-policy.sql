-- MySQL 8. 인증/꿈 CRUD, 장면 분석, 꿈 서사화 변경 SQL까지 적용한 기존 DB용.
-- 신규 DB는 schema-initial.sql만 사용한다. 앱과 AI 호출을 중지한 후 백업하고 실행한다.
-- DDL은 자동 커밋된다. source_revision의 NULL만 초기화하므로 중단 후 재실행할 수 있다.
-- 기존 provenance는 변경하지 않는다. 과거 제목만 바뀌었는지는 추정하지 않는다.
DELIMITER $$
DROP PROCEDURE IF EXISTS migrate_dream_edit_policy$$
CREATE PROCEDURE migrate_dream_edit_policy()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'dreams'
          AND column_name = 'source_revision'
    ) THEN
        ALTER TABLE dreams ADD COLUMN source_revision BIGINT NULL DEFAULT NULL;
    END IF;

    UPDATE dreams SET source_revision = revision WHERE source_revision IS NULL;

    ALTER TABLE dreams MODIFY COLUMN source_revision BIGINT NOT NULL DEFAULT 0;
END$$
CALL migrate_dream_edit_policy()$$
DROP PROCEDURE migrate_dream_edit_policy$$
DELIMITER ;
