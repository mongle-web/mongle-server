# 꿈 서사화: 구현 및 연동 계약

## 범위와 선행 작업

`feat/10-scene-analysis`의 `6850bfe`를 기준으로 별도 `feat/dream-story` 브랜치에서 구현한다.
#13의 장면 분석 모델과 revision 수정에 의존하며 #14의 통계·최근 대상에는 의존하지 않는다.
Gateway #5의 실제 인터페이스·오류 계약은 확정 후 `StoryGenerator` 어댑터에서 연결한다.
현재 기본 생성기는 미연결 상태이며 가짜 생성기는 테스트에서만 등록한다.

## API

| API | 기능 |
|---|---|
| `POST /api/v1/dreams/{dreamId}/story` | 최초 생성, 완료 결과 재사용, 실패·만료 재시도, 명시적 재생성 |
| `GET /api/v1/dreams/{dreamId}/story` | 현재 꿈의 이야기 조회 |
| `GET /api/v1/stories/{storyId}` | 보존된 이야기 단건 조회 |

모든 API는 Access JWT의 사용자 ID로 소유권을 확인한다. 타인의 기록과 없는 기록은 모두 404이다.
모든 성공 응답에 `Cache-Control: no-store`를 설정한다.

최초 생성·일반 재조회·실패 재시도 요청:

```json
{"revision": 1}
```

성공 결과를 다시 만드는 요청:

```json
{"revision": 1, "regenerate": true, "storyVersion": 1}
```

위 번호는 예시이다. 실제 `revision`은 꿈 조회, `storyVersion`은 이야기 조회 응답에서 가져온다.
`regenerate`를 생략하거나 null로 전달하면 false이다. 재생성에 storyVersion이 없으면 400이다.
현재 꿈 revision 불일치, 완료 분석 부재·오래된 분석, 오래된 재생성 버전은 각각 409이다.
진행 중 중복은 202, 완료/실패 상태 반환은 200이다. 실패는 `status=FAILED`와 `failureCode`로 처리한다.
미연결 Gateway는 503이며 새 시도를 저장하지 않는다. DB 저장 실패는 실패 상태를 별도로 기록하도록 시도하고 502를 반환한다.
실패 상태 저장도 실패하면 기존 PROCESSING 상태는 작업 유효시간 만료까지 남을 수 있지만,
응답은 STORY_CALL_FAILED(502)를 유지하며 원래 저장 오류와 복구 오류를 함께 보존한다.

## 결과와 AI 보완 영역

`sections`는 `sequence`, `kind`, `sceneSequence`, `content`로 구성한다.
입력 장면마다 `SCENE` 문단을 정확히 한 번, 입력 순서대로 포함한다.
장면 사이의 선택적 연결부는 `AI_BRIDGE`로 구분하고 `sceneSequence=null`이다.
연결부는 첫/마지막에 둘 수 없으며 연속 연결부도 허용하지 않는다.
SCENE 역시 AI가 다듬은 문장이므로 원문 인용 또는 서버가 사실 검증을 마친 문장으로 표시하면 안 된다.

프롬프트는 원문 사실·인물·장소·사건·순서 보존, 새로운 사건·결말·대사·진단 추가 금지를 요구한다.
서버는 JSON 형식, 전체 장면 포함·순서·번호, 연결부 위치, 길이를 검증한다.
사실 보존의 의미까지 자동 증명하지는 않는다. 실제 Gateway 연결 후 원문 대조 평가가 필요하다.
문단당 최대 1500 코드포인트, 본문 전체 최대 6000 코드포인트, raw JSON 최대 32000 UTF-16 길이를 적용한다.

## 저장 모델과 상태

`DreamStory` / `dream_stories`는 완료 분석에 연결한다. `analysis_id` UNIQUE로 분석당 이야기 한 개를 보장한다.
소유자, 현재 시도의 AI 입력 sourceRevision·프롬프트 버전·상태·시도 ID·유효시간, 성공 결과 JSON·결과 revision·프롬프트 버전,
자체 `version`을 저장한다. 사용자 원문의 별도 스냅샷은 저장하지 않는다.
현재 결과 하나만 유지하며 전체 재생성 이력은 이번 범위에 포함하지 않는다.

`PROCESSING → COMPLETED / FAILED` 흐름을 사용한다. 재생성 시작은 이전 성공 결과를 지우지 않는다.
`hasPreviousResult=true`이면 sections는 현재 시도 대신 이전 성공 결과이다.
`resultRevision`, `resultPromptVersion`으로 표시 중인 성공 결과의 출처를 구분한다.
`sourceChanged`는 표시 중인 성공 결과(없으면 현재 시도)와 살아 있는 꿈의 sourceRevision 차이이다.
`sourceDeleted=true`이면 원문이 삭제되어 dreamId가 null이다.

작업 유효시간은 2분이다. 만료 뒤 새 요청은 시도 ID를 바꾼다.
이전 시도의 성공/실패 콜백은 현재 결과를 덮어쓰지 못한다.
새 시도가 없어도 만료된 응답은 `ATTEMPT_EXPIRED`로 실패 처리한다.
조회 자체는 상태를 변경하지 않으며 만료 작업의 새 시도는 POST 재요청에서 시작한다.

## 트랜잭션과 경합

`StoryTransactions.begin()`은 기존 CRUD·분석과 같은 사용자 행 잠금 순서를 사용한다.
소유권·작성 완료·현재 revision·완료 분석 및 AI 입력 sourceRevision을 확인한 뒤 작업을 예약한다.
진행 중 요청은 동일 시도를 반환하며, 완료 결과는 regenerate=false일 때 재사용한다.
완료 후 같은 재생성 요청을 재전송하면 오래된 storyVersion으로 409가 되어 불필요한 추가 AI 호출을 막는다.

`DreamStoryService.generate()`는 NOT_SUPPORTED로 실제 생성 호출·검증을 트랜잭션 밖에서 수행한다.
`finish()`는 REQUIRES_NEW에서 사용자 잠금 후 시도·유효시간·원문 존재·sourceRevision을 다시 검사한다.
실패 저장도 REQUIRES_NEW이며 성공 결과 갱신이 롤백되면 이전 결과가 유지된다.
이야기 처리 자체는 Dream 필드를 변경하지 않으므로 Dream.revision이 증가하지 않는다.

`DreamService.delete()`는 분석 FK 해제 전에 생성 중 이야기를 SOURCE_DELETED로 실패 처리한다.
완료 이야기는 분석과 함께 보존하며 원문 삭제 후 stories/{storyId}로 소유자가 조회할 수 있다.
같은 날짜의 새 꿈은 새 분석·새 이야기로 생성되어 예전 결과와 섞이지 않는다.

현재 #13은 완료 분석을 다시 생성하지 않는 정책이다. 원문 변경 후에는 분석이 오래된 상태이며
서사화 시작/재생성은 STORY_ANALYSIS_STALE(409)로 차단된다. 이미 저장한 이야기는 sourceChanged 표시로 조회할 수 있다.
제목 변경·비우기는 AI 입력을 바꾸지 않으므로 기존 분석·이야기를 유지한다.
완료 감정은 수정할 수 없다. 요청 충돌 검사는 계속 Dream.revision을 사용한다.
수정 원문 기반 재분석과 후속 이야기 갱신 정책은 별도 작업으로 정해야 한다.
세부 정책과 #17 DB 변경은 `dream-edit-policy.md`를 참고한다.

## Gateway 연결

`StoryGenerator.Input`은 userId, storyId, analysisId, attemptId, sourceRevision, originalText, emotions, scenes, elements를 제공한다.
어댑터는 기존 `AiTaskType.DREAM_NARRATIVE`와 `StoryPrompt.VERSION`, system(), schema()를 사용한다.
사용자 데이터는 명령과 분리하여 전달하며 DTO의 toString()에는 원문·장면·요소를 출력하지 않는다.
Gateway 실패는 호출부로 전달하며 공통 AI 로그의 소유권·토큰 NULL/0 처리는 #5 담당 범위에서 연결한다.
실제 호출 timeout은 도메인 작업 유효시간 2분보다 짧게 맞춘다.

## DB 적용

신규 DB는 갱신된 `db/schema-initial.sql`만 사용한다.
#13 변경 SQL까지 적용한 기존 MySQL 8 DB는 `db/migrations/20261005-dream-story.sql`을 수동 실행한다.
두 경로를 같은 DB에 중복 적용하지 않는다. 코드 적용 스크립트는 DB에 접속하거나 SQL을 실행하지 않는다.
DDL은 자동 커밋되므로 앱과 AI 호출을 중지한 유지보수 창에서 적용한다.
기존 테이블을 임의 삭제하거나 기존 데이터의 출처 정보를 백필하지 않는다.

## 검증 상태

- 추가한 StoryValidatorTest(5개), DreamStoryIntegrationTest(19개), DreamStoryServiceTest(2개): 26개 테스트 메서드.
- 검증 범위: 구조·순서·중복/여분 필드·Unicode/길이, 정상·재사용·재생성·실패 재시도,
  이전 성공 결과 유지, 외부 호출 트랜잭션 분리, 중복 요청·버전 재전송·만료·늦은 응답,
  수정·삭제 경합과 보존, DB UNIQUE/저장 롤백, 인증·소유권·Bean Validation·HTTP 202/503/no-store.
- 서비스 회귀 검증: 저장 실패 및 실패 상태 저장의 추가 실패에서도 STORY_CALL_FAILED와 원인·suppressed 예외 보존.
- 기존 InitialSchemaValidationTest는 dream_stories 매핑 검증을 추가하고,
  DreamPolicyTest는 삭제 서비스의 새 저장소 의존성에 맞춰 생성자를 갱신했다.
- Java 구문/서식, JSON 계약 파일/fixture 및 패치 검사를 수행했다.
- 2026-10-06 리뷰 보완: Java 21 독립 하네스에서 실패 상태 저장 성공/실패 두 경로의
  오류 코드·원인·suppressed 예외 보존을 확인했다. 의존성 대역을 사용한 서비스 예외 경로 검증이며,
  JUnit/Spring/JPA 테스트 실행을 대체하지 않는다.
- 새 JUnit 회귀 테스트와 전체 Spring 테스트는 이 환경의 플러그인 의존성 다운로드 네트워크 제한으로 미실행이다.
- 실제 LINER 호출과 MySQL 변경 SQL 실행은 아직 미검증이다.
- 적용 스크립트는 Mac에서 전체 ./gradlew test 성공 후에만 기능 커밋을 만든다.
