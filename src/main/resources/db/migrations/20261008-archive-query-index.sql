-- 기존 MySQL DB에 Archive 조회용 인덱스를 추가한다. 이미 존재하면 생성하지 않는다.
-- 자동 실행되지 않는다. schema-initial.sql로 새 DB를 구성한 경우 이 파일은 필요하지 않다.
-- 앱 배포 절차에서 DBA가 테이블 규모·잠금 영향을 확인하고 수동 적용한다.
SET @archive_index_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'dreams'
             AND index_name = 'idx_dreams_user_status_date_id'),
    'SELECT 1',
    'CREATE INDEX idx_dreams_user_status_date_id ON dreams(user_id, record_status, dreamed_at, id)'
);
PREPARE archive_index_statement FROM @archive_index_sql;
EXECUTE archive_index_statement;
DEALLOCATE PREPARE archive_index_statement;
