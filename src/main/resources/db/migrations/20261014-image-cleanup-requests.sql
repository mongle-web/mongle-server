-- 꿈 삭제 커밋 이후 처리할 내부 파일 정리 대상. 삭제된 원본으로 향하는 FK는 두지 않는다.
-- 워커/실제 S3 삭제는 후속 구현이며, 행 존재만으로 파일 삭제 완료를 뜻하지 않는다.
CREATE TABLE IF NOT EXISTS image_cleanup_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    dream_id BIGINT NULL,
    image_id BIGINT NOT NULL,
    storage_key VARCHAR(255) NOT NULL,
    reason VARCHAR(30) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_image_cleanup_key UNIQUE (storage_key)
);
