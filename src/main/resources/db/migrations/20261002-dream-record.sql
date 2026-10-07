-- 기존 MySQL DB에만 수동 1회 적용한다. 로컬 H2에는 실행하지 않는다.
-- DELIMITER를 지원하는 MySQL 클라이언트에서 파일 전체를 실행한다.
-- 아래 사전 검증을 통과하지 못하면 기존 데이터의 날짜·중복·감정부터 정리한다.
-- MySQL DDL은 자동 커밋되므로 운영 DB 적용 전 백업하고 부분 적용 여부를 확인한다.
-- 사전 검증 실패로 남은 프로시저를 제거해 데이터 정리 후 다시 실행할 수 있게 한다.
DROP PROCEDURE IF EXISTS migrate_dream_record;
DELIMITER $$
CREATE PROCEDURE migrate_dream_record()
BEGIN
    IF EXISTS (SELECT 1 FROM dreams WHERE dreamed_at IS NULL OR CHAR_LENGTH(original_text) > 500
               OR TRIM(original_text) = '') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '꿈 날짜 또는 원문을 먼저 정리해주세요.';
    END IF;
    IF EXISTS (SELECT 1 FROM dreams GROUP BY user_id, dreamed_at HAVING COUNT(*) > 1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '같은 사용자와 날짜의 중복 꿈을 먼저 정리해주세요.';
    END IF;
    IF EXISTS (SELECT 1 FROM dreams WHERE representative_emotion IS NOT NULL
               AND representative_emotion NOT IN ('행복', '편안함', '설렘', '슬픔', '불안', '화남', '당황')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '기존 감정을 확정된 감정 목록으로 먼저 정리해주세요.';
    END IF;
    ALTER TABLE dreams
        MODIFY dreamed_at DATE NOT NULL,
        ADD COLUMN title VARCHAR(100) NULL,
        ADD COLUMN record_status VARCHAR(30) NOT NULL DEFAULT 'EMOTION_PENDING',
        ADD COLUMN is_edited BOOLEAN NOT NULL DEFAULT FALSE,
        ADD COLUMN revision BIGINT NOT NULL DEFAULT 0,
        ADD CONSTRAINT uk_dreams_user_date UNIQUE (user_id, dreamed_at);
    CREATE TABLE dream_emotions (
        dream_id BIGINT NOT NULL,
        emotion VARCHAR(20) NOT NULL,
        CONSTRAINT uk_dream_emotions UNIQUE (dream_id, emotion),
        CONSTRAINT fk_dream_emotions_dream FOREIGN KEY (dream_id) REFERENCES dreams (id)
    );
    INSERT INTO dream_emotions (dream_id, emotion)
    SELECT id, CASE representative_emotion
        WHEN '행복' THEN 'HAPPY' WHEN '편안함' THEN 'CALM' WHEN '설렘' THEN 'EXCITED'
        WHEN '슬픔' THEN 'SAD' WHEN '불안' THEN 'ANXIOUS' WHEN '화남' THEN 'ANGRY'
        WHEN '당황' THEN 'CONFUSED' END FROM dreams WHERE representative_emotion IS NOT NULL;
    UPDATE dreams SET record_status = 'COMPLETED' WHERE representative_emotion IS NOT NULL;
    ALTER TABLE dreams DROP COLUMN representative_emotion;
    ALTER TABLE dream_scenes ADD COLUMN user_id BIGINT NULL;
    UPDATE dream_scenes s JOIN dreams d ON d.id = s.dream_id SET s.user_id = d.user_id;
    ALTER TABLE dream_scenes MODIFY user_id BIGINT NOT NULL, MODIFY dream_id BIGINT NULL,
        ADD CONSTRAINT fk_dream_scenes_user FOREIGN KEY (user_id) REFERENCES users (id);
    ALTER TABLE dream_entities ADD COLUMN user_id BIGINT NULL;
    UPDATE dream_entities e JOIN dreams d ON d.id = e.dream_id SET e.user_id = d.user_id;
    ALTER TABLE dream_entities MODIFY user_id BIGINT NOT NULL, MODIFY dream_id BIGINT NULL,
        ADD CONSTRAINT fk_dream_entities_user FOREIGN KEY (user_id) REFERENCES users (id);
END$$
DELIMITER ;
CALL migrate_dream_record();
DROP PROCEDURE migrate_dream_record;
