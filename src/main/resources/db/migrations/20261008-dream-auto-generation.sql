-- 기존 DB에서 배포 전에 1회 적용한다. 기존 꿈을 일괄 생성하지 않는다.

-- 감정 선택 완료와 같은 트랜잭션에서 예약하는 자동 생성 작업. 원문·감정은 복제하지 않는다.
CREATE TABLE dream_generation_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    dream_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    source_revision BIGINT NOT NULL,
    stage VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    claim_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    next_run_at DATETIME(6) NOT NULL,
    recovering BOOLEAN NOT NULL DEFAULT FALSE,
    failure_code VARCHAR(50) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_generation_job_dream UNIQUE (dream_id)
);
CREATE INDEX idx_generation_job_due ON dream_generation_jobs(status,next_run_at,id);
CREATE INDEX idx_generation_job_user ON dream_generation_jobs(user_id,status,lease_until);
