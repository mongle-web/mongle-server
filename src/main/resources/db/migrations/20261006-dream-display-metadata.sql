-- MySQL 8. #17까지 적용된 기존 DB용. 신규 DB는 schema-initial.sql만 사용한다.
-- 앱/AI 호출 중지 후 백업하고 실행한다. DDL 자동 커밋이며 재실행해도 기존 결과를 변경하지 않는다.
-- 기존 scene-v1 분석은 generated_title=NULL, 키워드 없음. 임의로 재생성하거나 추정하지 않는다.
DELIMITER $$
DROP PROCEDURE IF EXISTS migrate_dream_display_metadata$$
CREATE PROCEDURE migrate_dream_display_metadata()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'dream_analyses'
          AND column_name = 'generated_title'
    ) THEN
        ALTER TABLE dream_analyses ADD COLUMN generated_title VARCHAR(40) NULL;
    END IF;
    ALTER TABLE dream_analyses MODIFY COLUMN generated_title VARCHAR(40) NULL;
END$$
CALL migrate_dream_display_metadata()$$
DROP PROCEDURE migrate_dream_display_metadata$$
DELIMITER ;

CREATE TABLE IF NOT EXISTS dream_analysis_display_keywords (
    analysis_id BIGINT NOT NULL,
    keyword_order INT NOT NULL,
    keyword VARCHAR(40) NOT NULL,
    PRIMARY KEY (analysis_id, keyword_order),
    CONSTRAINT fk_analysis_display_keywords FOREIGN KEY (analysis_id)
        REFERENCES dream_analyses(id) ON DELETE CASCADE
);

ALTER TABLE dream_analysis_display_keywords MODIFY COLUMN keyword VARCHAR(40) NOT NULL;
