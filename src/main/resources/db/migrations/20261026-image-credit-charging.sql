-- MySQL 8 수동 마이그레이션. #60/#62 적용, 앱/워커 중지 및 백업 후 실행한다.
-- 기존 성공 결과를 유지한다. 이전 버전의 진행 시도는 예약이 없으므로 복구 워커가 실패 처리한다.
DROP PROCEDURE IF EXISTS mongle_add_image_credit_schema;
DELIMITER $$
CREATE PROCEDURE mongle_add_image_credit_schema()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dream_images' AND COLUMN_NAME='credit_reservation_id') THEN
        ALTER TABLE dream_images ADD COLUMN credit_reservation_id BIGINT NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
        WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dream_images' AND INDEX_NAME='idx_image_attempt_expiry') THEN
        CREATE INDEX idx_image_attempt_expiry ON dream_images(status,lease_until,id);
    END IF;
END$$
DELIMITER ;
CALL mongle_add_image_credit_schema();
DROP PROCEDURE mongle_add_image_credit_schema;
