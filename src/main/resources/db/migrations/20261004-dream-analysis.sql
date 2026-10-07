-- MySQL 8, 인증/꿈 CRUD 및 refresh-token-lineage 변경 SQL 적용 후 실행.
-- 앱과 AI 호출을 중지한 유지보수 창에서 실행한다. DDL은 자동 커밋된다.
-- 기존 분석 결과가 있으면 진행하지 않는다. 분석의 출처 revision/삭제된 꿈 날짜를 임의 복원하지 않는다.
DELIMITER $$
DROP PROCEDURE IF EXISTS migrate_dream_analysis$$
CREATE PROCEDURE migrate_dream_analysis()
BEGIN
 IF EXISTS (SELECT 1 FROM dream_scenes) OR EXISTS (SELECT 1 FROM dream_entities) THEN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dream_scenes' AND column_name='analysis_id')
   OR NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dream_entities' AND column_name='analysis_id') THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Legacy AI output exists: explicit backfill is required';
  END IF;
  IF EXISTS(SELECT 1 FROM dream_scenes WHERE analysis_id IS NULL) OR EXISTS(SELECT 1 FROM dream_entities WHERE analysis_id IS NULL) THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Ungrouped AI output exists: explicit backfill is required';
  END IF;
 END IF;
 CREATE TABLE IF NOT EXISTS dream_analyses (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  dream_id BIGINT NULL, user_id BIGINT NOT NULL, source_dream_id BIGINT NOT NULL,
  dreamed_at DATE NOT NULL, source_revision BIGINT NOT NULL, observed_revision BIGINT NOT NULL,
  prompt_version VARCHAR(50) NOT NULL, status VARCHAR(30) NOT NULL,
  attempt_id VARCHAR(36) NULL, lease_until DATETIME(6) NULL, failure_code VARCHAR(50) NULL,
  version BIGINT NOT NULL DEFAULT 0, created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
  CONSTRAINT uk_dream_analysis_dream UNIQUE(dream_id),
  CONSTRAINT uk_dream_analysis_source UNIQUE(source_dream_id),
  CONSTRAINT fk_dream_analysis_user FOREIGN KEY(user_id) REFERENCES users(id),
  CONSTRAINT fk_dream_analysis_dream FOREIGN KEY(dream_id) REFERENCES dreams(id),
  INDEX idx_analysis_user_status_date(user_id,status,dreamed_at)
 );
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dream_scenes' AND column_name='analysis_id') THEN
  ALTER TABLE dream_scenes ADD COLUMN analysis_id BIGINT NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dream_entities' AND column_name='analysis_id') THEN
  ALTER TABLE dream_entities ADD COLUMN analysis_id BIGINT NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dream_entities' AND column_name='normalized_name') THEN
  ALTER TABLE dream_entities ADD COLUMN normalized_name VARCHAR(255) NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='dream_scenes' AND constraint_name='fk_dream_scenes_analysis') THEN
  ALTER TABLE dream_scenes ADD CONSTRAINT fk_dream_scenes_analysis FOREIGN KEY(analysis_id) REFERENCES dream_analyses(id);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='dream_entities' AND constraint_name='fk_dream_entities_analysis') THEN
  ALTER TABLE dream_entities ADD CONSTRAINT fk_dream_entities_analysis FOREIGN KEY(analysis_id) REFERENCES dream_analyses(id);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='dream_scenes' AND index_name='uk_analysis_scene_sequence') THEN
  ALTER TABLE dream_scenes ADD CONSTRAINT uk_analysis_scene_sequence UNIQUE(analysis_id,sequence_no);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='dream_entities' AND index_name='uk_analysis_entity_name') THEN
  ALTER TABLE dream_entities ADD CONSTRAINT uk_analysis_entity_name UNIQUE(analysis_id,entity_type,normalized_name);
 END IF;
END$$
CALL migrate_dream_analysis()$$
DROP PROCEDURE migrate_dream_analysis$$
DELIMITER ;
