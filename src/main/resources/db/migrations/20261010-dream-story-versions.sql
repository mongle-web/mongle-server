-- 앱/AI 작업 중지 및 백업 후 실행. 과거에 이미 덮어쓴 결과는 복구할 수 없다.
CREATE TABLE IF NOT EXISTS dream_story_versions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    story_id BIGINT NOT NULL,
    generation_key VARCHAR(36) NOT NULL,
    source_revision BIGINT NOT NULL,
    prompt_version VARCHAR(50) NOT NULL,
    result_json TEXT NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_story_version_generation UNIQUE (story_id, generation_key),
    INDEX idx_story_version_story_id (story_id, id),
    CONSTRAINT fk_story_version_story FOREIGN KEY (story_id) REFERENCES dream_stories (id)
);

-- 완료 상태뿐 아니라 처리/실패 상태에 남은 마지막 성공 결과도 보존한다.
-- 재실행해도 legacy 버전을 덮어쓰지 않는다.
INSERT INTO dream_story_versions
    (story_id, generation_key, source_revision, prompt_version, result_json, created_at)
SELECT s.id, 'legacy', s.result_revision, s.result_prompt_version, s.result_json, CURRENT_TIMESTAMP
FROM dream_stories s
WHERE s.result_json IS NOT NULL
  AND s.result_revision IS NOT NULL AND s.result_prompt_version IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM dream_story_versions v WHERE v.story_id = s.id
  );
