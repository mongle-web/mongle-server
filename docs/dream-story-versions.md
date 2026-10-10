# 이야기 성공 버전과 세계관 참조 계약

## 이번 구현 범위

확정한 세계관 정책의 선행 작업이다. #42는 develop(5274302, #41 포함)에서 분기하며 #39 이미지 비동기 변경에 의존하지 않는다.
성공한 이야기를 별도 테이블에 추가하고, 재생성으로 이전 성공 내용을 덮어쓰지 않는다.
기존 dream_stories는 현재 시도 상태와 마지막 성공 결과를 계속 제공한다.

최신 입력의 분석·서사 통합 재생성 API와 입력/설정 스냅샷을 구현했다. 프론트엔드 버튼·세계관 반영 팝업,
연결 문장 생성·부분 갱신, 확정한 전체 영구 삭제 연동과 크레딧 차감은 후속 작업이다.
기존 develop의 분석·이야기·이미지 삭제 보존 동작은 아직 유지한다. 다만 이번에 추가한 성공 버전과
원문/감정 스냅샷은 꿈 삭제 시 함께 제거한다. 전체 삭제 정책이 구현됐다고 안내해서는 안 된다.

## 버전 구분

| 응답 값 | 의미 | 사용처 |
| --- | --- | --- |
| Dream.revision | 꿈 수정 충돌 검사용 값 | 기존 꿈 수정/생성 요청 |
| sourceRevision/resultRevision | AI 입력 출처 | 수정 전 결과 표시 |
| storyVersion | 현재 이야기 작업 행의 optimistic version | 기존 이야기 재생성 요청 |
| resultVersionId/versionId | 성공 결과의 영구 식별자 | 세계관에 선택한 이야기 버전 고정 |

storyVersion을 세계관의 결과 식별자로 사용하지 않는다. 실패/처리 상태 변경으로 증가할 수 있다.
성공 버전 ID는 새로운 성공에만 발급되며 기존 ID의 이야기 내용·프롬프트·입력 버전은 수정하지 않는다.
같은 입력으로 사용자가 명시적으로 다시 생성해도 다른 성공 버전 ID가 생길 수 있다.

이야기 최초 생성 성공: resultVersionId=101 → 재생성 처리 중/실패: 101 유지 → 재생성 성공: 102.
세계관 폴더1이 101을 선택했다면 102가 생겨도 폴더1의 내용은 자동 변경되지 않는다.
세계관 구현에서는 선택한 versionId와 꿈 출처를 저장하고, 사용자가 반영을 요청할 때만 버전을 교체한다.

## 조회 API

기존 이야기 응답에 resultVersionId를 추가한다. 성공 결과가 없으면 null이고, 실패/진행 중이면 이전 성공 ID를 반환한다.

- GET /api/v1/stories/{storyId}/versions?limit=20&before={nextCursor}
  - 최신 ID부터 정렬하며 limit은 1~50, 기본 20이다.
  - items에는 versionId, sourceRevision, promptVersion, storedAt, sourceChanged, imported를 제공한다.
  - nextCursor가 null이면 마지막 페이지다. 목록에는 본문을 반환하지 않는다.
- GET /api/v1/stories/{storyId}/versions/{versionId}
  - 고정 버전의 sections와 출처를 반환한다.
  - 타인의 이야기/버전, 다른 이야기의 버전과 없는 버전은 모두 404다.
  - sourceChanged/sourceDeleted는 조회 시점의 원본 상태다. 본문 자체는 바뀌지 않는다.
  - imported=true이면 기존 DB의 마지막 성공 결과를 마이그레이션으로 옮긴 버전이다.
  - originalText/emotions/analysis/analysisPromptVersion은 생성 당시의 입력과 분석이다.
  - analysisSettings/storySettings는 요청 제공자·모델·출력 토큰 상한·추론 강도·출력 형식이다.
  - 기존 데이터의 모르는 출처는 null이며 sourceSnapshotAvailable=false다. 현재 원문으로 채우지 않는다.

모든 조회는 인증을 요구하고 Cache-Control: no-store를 사용한다. 조회·수정만으로 AI를 호출하지 않는다.
커서는 정렬 경계이며 현재 목록의 다른 ID를 전달해도 그보다 오래된 소유자 버전만 반환한다.

## 저장과 경합

외부 AI 호출은 기존처럼 DB 트랜잭션 밖에서 수행한다.
성공 결과 검증·소유자 잠금·시도 ID·원본 버전·lease 검사를 통과한 경우에만 버전과 마지막 성공 결과를 같은 트랜잭션에서 저장한다.
버전 저장 또는 현재 결과 저장 중 하나라도 실패하면 둘 다 롤백한다.
잘못된 출력, 원본 변경/삭제, 만료, 실패, 중복 완료, 교체된 시도의 늦은 응답은 성공 버전을 추가하지 않는다.
DB UNIQUE(story_id, generation_key)도 동일 생성 시도의 중복 저장을 막는다.

## 서사 형식

새 생성에는 story-v2를 사용한다. 한국어 1인칭 관찰 시점·과거형·차분한 서술체를 기준으로 한다.
원문에 없는 인물·사건·인과·감정은 추가하지 않는다. 제3자를 관찰한 꿈은 나를 참여자로 새로 넣지 않는다.
각 꿈은 독립적으로 완결하며 다른 꿈과 이어지는 내용을 만들지 않는다.
기존 SCENE/AI_BRIDGE JSON 구조는 유지한다. AI_BRIDGE는 한 꿈 안의 장면 전환이고, 꿈 사이의 연결 문장과 다르다.
기존 성공 결과는 프롬프트 변경만으로 재생성하지 않는다. 실제 모델의 문체 준수는 운영 샘플 검증이 별도로 필요하다.

## DB 적용

새 DB: schema-initial.sql. 기존 DB: 20261010-dream-story-versions.sql → 20261010-dream-generation-versioning.sql 순서.
앱과 AI 작업을 중지하고 백업한 뒤 SQL과 코드를 함께 적용한다. 스크립트는 DB를 자동 변경하지 않는다.

적용 전에 아래 쿼리가 0인지 확인한다. 0이 아니면 출처 정보가 없는 기존 결과를 먼저 확인하고 적용을 중단한다.

```sql
SELECT COUNT(*) FROM dream_stories
WHERE result_json IS NOT NULL
  AND (result_revision IS NULL OR result_prompt_version IS NULL);
```

완료뿐 아니라 실패/처리 중 행에 보존된 마지막 성공 결과도 옮긴다. 이미 덮어쓴 옛 결과는 복구하지 못한다.
storedAt은 보존 테이블 저장 시각이며, imported 결과의 실제 생성 시각으로 해석하지 않는다.
재실행 시 버전이 이미 있는 이야기는 건너뛰므로 새 버전이나 imported 버전을 중복 추가하지 않는다.
DDL은 자동 커밋된다. 코드 롤백 시 테이블/버전 데이터를 지우지 않고 보존할 수 있다.

## 후속 구현

원문 수정 후 최신 분석·서사 재생성 계약은 dream-generation-versioning.md를 참고한다.
세계관 관리에서는 선택한 꿈 목록과 완성된 세계관 이야기를 분리하고, 앞뒤 두 이야기의 versionId를 연결 문장 출처로 기록한다.
재생성 예약부터 완료/실패까지 꿈 수정·삭제를 차단한다. 세계관에서 사용 중인 꿈도 수정할 수 있도록
연결 여부 검사/잠금을 추가하지 않았다. 영구 삭제 연동·전체 세계관 이미지 유지 안내는 후속 작업이다.
