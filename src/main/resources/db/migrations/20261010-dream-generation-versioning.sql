-- MySQL 8, 기존 앱과 AI 워커 중지 및 백업 후 실행한다.
-- 20261010-dream-story-versions.sql을 먼저 실행한다. 자동 마이그레이션 도구는 아직 미도입이다.
-- 컬럼별 INFORMATION_SCHEMA 확인으로 재실행할 수 있다.
DROP PROCEDURE IF EXISTS mongle_add_generation_column;
DELIMITER $$
CREATE PROCEDURE mongle_add_generation_column(IN table_name_arg VARCHAR(64), IN column_name_arg VARCHAR(64), IN definition_arg VARCHAR(255))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
                   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=table_name_arg AND COLUMN_NAME=column_name_arg) THEN
        SET @mongle_generation_ddl=CONCAT('ALTER TABLE `',table_name_arg,'` ADD COLUMN `',column_name_arg,'` ',definition_arg);
        PREPARE mongle_generation_stmt FROM @mongle_generation_ddl;
        EXECUTE mongle_generation_stmt;
        DEALLOCATE PREPARE mongle_generation_stmt;
    END IF;
END$$
DELIMITER ;
CALL mongle_add_generation_column('dream_analyses','result_revision','BIGINT NULL');
CALL mongle_add_generation_column('dream_analyses','source_text','TEXT NULL');
CALL mongle_add_generation_column('dream_analyses','source_emotions','VARCHAR(100) NULL');
CALL mongle_add_generation_column('dream_analyses','pending_result_json','TEXT NULL');
CALL mongle_add_generation_column('dream_analyses','regenerating','BOOLEAN NOT NULL DEFAULT FALSE');
CALL mongle_add_generation_column('dream_generation_jobs','regeneration','BOOLEAN NOT NULL DEFAULT FALSE');
CALL mongle_add_generation_column('dream_story_versions','source_text','TEXT NULL');
CALL mongle_add_generation_column('dream_story_versions','source_emotions','VARCHAR(100) NULL');
CALL mongle_add_generation_column('dream_story_versions','analysis_json','TEXT NULL');
CALL mongle_add_generation_column('dream_story_versions','analysis_prompt_version','VARCHAR(50) NULL');
DROP PROCEDURE mongle_add_generation_column;

-- 기존 성공 분석의 입력 버전만 보존한다. 수정 후 원문을 과거 생성 입력으로 추정하지 않는다.
UPDATE dream_analyses SET result_revision=observed_revision
WHERE status='COMPLETED' AND result_revision IS NULL;
-- legacy 이야기의 원문/감정/분석 출처는 알 수 없으므로 NULL을 유지한다.
