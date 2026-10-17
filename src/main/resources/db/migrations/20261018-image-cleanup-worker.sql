-- MySQL 8 수동 마이그레이션. 앱/이미지 워커 중지 및 백업 후 적용한다.
-- 20261014-image-cleanup-requests.sql을 먼저 적용한다. 운영 자동 실행하지 않는다.
-- 기존 요청만 PENDING으로 백필하며, 이미 진행/완료된 요청의 상태는 재실행으로 되돌리지 않는다.
DROP PROCEDURE IF EXISTS mongle_add_cleanup_column;
DELIMITER $$
CREATE PROCEDURE mongle_add_cleanup_column(IN column_arg VARCHAR(64), IN definition_arg VARCHAR(255))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
                   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='image_cleanup_requests' AND COLUMN_NAME=column_arg) THEN
        SET @mongle_cleanup_ddl=CONCAT('ALTER TABLE image_cleanup_requests ADD COLUMN `',column_arg,'` ',definition_arg);
        PREPARE mongle_cleanup_stmt FROM @mongle_cleanup_ddl;
        EXECUTE mongle_cleanup_stmt;
        DEALLOCATE PREPARE mongle_cleanup_stmt;
    END IF;
END$$
DELIMITER ;
CALL mongle_add_cleanup_column('status','VARCHAR(30) NOT NULL DEFAULT ''PENDING''');
CALL mongle_add_cleanup_column('attempt_count','INT NOT NULL DEFAULT 0');
CALL mongle_add_cleanup_column('claim_token','VARCHAR(36) NULL');
CALL mongle_add_cleanup_column('lease_until','DATETIME(6) NULL');
CALL mongle_add_cleanup_column('next_run_at','DATETIME(6) NULL');
CALL mongle_add_cleanup_column('completed_at','DATETIME(6) NULL');
CALL mongle_add_cleanup_column('deletion_started_at','DATETIME(6) NULL');
CALL mongle_add_cleanup_column('failure_code','VARCHAR(50) NULL');
CALL mongle_add_cleanup_column('requeue_requested','BOOLEAN NOT NULL DEFAULT FALSE');
CALL mongle_add_cleanup_column('version','BIGINT NOT NULL DEFAULT 0');
DROP PROCEDURE mongle_add_cleanup_column;
UPDATE image_cleanup_requests SET next_run_at=created_at WHERE status='PENDING' AND next_run_at IS NULL;

DROP PROCEDURE IF EXISTS mongle_add_cleanup_index;
DELIMITER $$
CREATE PROCEDURE mongle_add_cleanup_index(IN table_arg VARCHAR(64), IN index_arg VARCHAR(64), IN columns_arg VARCHAR(255))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
                   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=table_arg AND INDEX_NAME=index_arg) THEN
        SET @mongle_cleanup_ddl=CONCAT('CREATE INDEX `',index_arg,'` ON `',table_arg,'` (',columns_arg,')');
        PREPARE mongle_cleanup_stmt FROM @mongle_cleanup_ddl;
        EXECUTE mongle_cleanup_stmt;
        DEALLOCATE PREPARE mongle_cleanup_stmt;
    END IF;
END$$
DELIMITER ;
CALL mongle_add_cleanup_index('image_cleanup_requests','idx_cleanup_due','status,next_run_at,id');
CALL mongle_add_cleanup_index('image_cleanup_requests','idx_cleanup_lease','status,lease_until,id');
CALL mongle_add_cleanup_index('dream_images','idx_image_storage_key','storage_key');
DROP PROCEDURE mongle_add_cleanup_index;
