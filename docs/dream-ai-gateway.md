# 꿈 장면 분석·서사화 실제 Gateway 연동

## 범위

#31은 기존 분석·서사화 API의 기본 생성기를 실제 `AiGateway`에 연결한다.
LINER HTTP 구현은 #28, 비동기 Gateway·호출 로그는 병합된 #32, 에러 코드 형식은 #27/#30을 사용한다.
DB 스키마, API URL·요청·응답 형식, 상태·중복 요청·수정/삭제 경합 정책은 유지한다.
프롬프트와 JSON Schema는 기존 `scene-v2-display`, `story-v1` 계약을 그대로 사용한다.

저장 완료 후 자동 생성, 원문 수정 후 명시적 재분석, 이미지 제공자·스토리지 연결,
서비스 토큰 과금은 후속 작업이다. AI 호출 로그·사용량·비용 저장은 #32의 Gateway가 담당하며,
이번 변경에서 `AiGenerationLog`나 그 SQL을 수정하지 않는다.

## 호출 흐름과 데이터

1. 기존 트랜잭션에서 사용자·원문·revision·기존 시도를 확인하고 입력 스냅샷을 만든다.
2. 트랜잭션이 종료된 후 실제 생성기가 `AiGenerationRequest`를 만든다.
3. 분석·서사화 공용 처리 슬롯을 즉시 확보하고 비동기 LINER Gateway를 호출한다.
4. HTTP 요청 스레드를 반환한다. 호출 완료/실패 후 전용 풀에서 기존 Validator로 검증한다.
5. 별도 저장 트랜잭션에서 현재 시도와 원문 수정/삭제 여부를 다시 확인한다.
   유효한 결과만 저장하며 실패 상태 기록도 별도 트랜잭션에서 처리한다.
6. 저장·실패 처리 종료 후 슬롯을 반납하고 기존 JSON 응답을 HTTP 비동기 디스패치로 반환한다.

| 생성기 | 작업 종류 | 시스템 메시지·스키마 | 사용자 데이터 |
| --- | --- | --- | --- |
| `AiGatewayStructureGenerator` | `DREAM_STRUCTURE` | `StructurePrompt`, `scene-v2-display` | originalText, emotions |
| `AiGatewayStoryGenerator` | `DREAM_NARRATIVE` | `StoryPrompt`, `story-v1` | originalText, emotions, scenes, elements |

시스템 메시지와 스키마는 생성기 초기화 때 읽고 이후 요청에서 재사용한다.
사용자 원문은 JSON으로 직렬화해 별도 USER 메시지로 전달하며 지시문에 이어 붙이지 않는다.
장면 순서는 유지하고 감정 목록은 enum 순서로 정렬한다.
userId·taskType·promptVersion은 내부 Gateway 맥락으로 전달하므로 #32의 시도별 로그·토큰 NULL/0·비용 계산에 사용한다.
LINER 본문에는 이 내부 맥락이나 분석/서사화 ID·시도 ID·revision을 넣지 않는다.
서사화 입력의 저장된 요소 참조 `e{DB ID}`도 `element_1` 등의 요청 전용 키로 바꾼다.
장면의 요소 참조를 함께 바꾸며 입력 스냅샷·저장된 분석은 수정하지 않는다.

JSON Schema의 `strict=true`는 서버 검증을 대신하지 않는다.
표시 제목·키워드·요소 참조와 서사화 문단 순서·연결부·길이는 기존 Validator가 검증한다.
원문 사실을 의미상 완벽하게 보존했는지는 실제 모델 출력에 대한 별도 평가가 필요하다.

## 실행 방식과 동시 호출 제한

생성기·서비스·생성 컨트롤러는 `CompletableFuture`로 연결한다.
운영 코드에서 `join()`, `get()`, `sleep()`으로 외부 응답을 기다리지 않는다.
Spring MVC 비동기 처리로 HTTP 요청 스레드를 반환하므로 가상 스레드 활성화에 의존하지 않는다.
HTTP 응답 JSON·상태 코드는 유지하며 진행 중 중복 요청만 기존대로 202를 반환한다.
외부 호출 중에는 DB 트랜잭션·EntityManager·행 잠금을 유지하지 않는다.

`DreamGenerationResources`는 서버 인스턴스당 분석·서사화 합계 4개를 기본으로 제한한다.
한 슬롯은 외부 호출 시작부터 검증·DB 저장 종료까지 유지한다. 한도 초과는 즉시 거절한다.
검증·JPA 저장은 `dream-result-*` 플랫폼 스레드 2개에서 처리한다.
한 작업의 후속 작업은 하나이며 큐 용량을 슬롯 수와 같게 제한한다.
Gateway의 `ai-response-*`·`ai-log-*`·타이머 스레드나 공용 ForkJoinPool에 JPA 작업을 보내지 않는다.
Gateway 자체의 전체 동시 호출 한도와 꿈 AI 한도 중 더 작은 한도가 적용된다.

진행 중 중복과 기존 완료 결과는 추가 슬롯을 사용하지 않는다.
한도 초과 시 외부 호출 없이 `FAILED / CALL_FAILED`를 기록하며 슬롯이 비면 재요청할 수 있다.
새 시도 예약 후 즉시 거절된 경우의 실패 기록은 요청 스레드에서 짧은 트랜잭션으로 처리한다.
외부 응답 후 검증·저장·실패 복구는 모두 결과 전용 풀에서 실행한다.
반환 Future가 취소돼도 이미 시작한 결과 저장과 슬롯 반납은 계속한다.
제공자 재시도 정책은 #32에 따르며 혼잡 실패를 도메인에서 자동 재예약하지 않는다.

종료 시 새 작업을 막고 진행 중 결과 처리를 최대 5초 기다린 뒤 풀을 종료한다.
DB 장애나 강제 종료로 실패 상태 저장도 완료되지 않으면 기존 PROCESSING 시도는
유효시간 만료 후 재시도한다. 즉시 종료가 모든 저장 완료를 보장하지는 않는다.
여러 인스턴스로 배포하면 전체 호출 한도는 인스턴스 수만큼 증가한다.

## 설정

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `LINER_API_KEY` | 빈 값 | 실제 호출용 키. 저장소나 로그에 넣지 않는다 |
| `MONGLE_DREAM_AI_MAX_CONCURRENT_CALLS` | 4 | 분석·서사화 합계 동시 호출 수, 1~64 |
| `spring.mvc.async.request-timeout` | 130s | HTTP 비동기 요청 수명. 외부 호출 예산보다 길게 설정 |
| `LINER_TOTAL_TIMEOUT` | 60s | 내부 재시도 포함 전체 통신 예산. 도메인 시도 유효시간 때문에 2분 미만만 허용 |

나머지 모델·endpoint·개별 timeout·재시도 설정은 [LINER 연동 문서](liner-gateway.md)를 따른다.
`.env`는 자동으로 읽지 않으므로 실행 환경에서 주입한다. 키가 없으면 서버는 기동하고,
신규 분석/서사화 요청은 도메인 `UNAVAILABLE`(503)로 거절하며 시도와 외부 호출을 만들지 않는다.
기존 결과 조회·재사용은 계속 가능하다.

설정된 키가 잘못되었거나 제공자 호출이 실패하면 `FAILED / CALL_FAILED`,
도메인 스키마·업무 규칙을 위반한 출력이면 `FAILED / INVALID_OUTPUT`이다.
이 경우 원문은 저장된 상태로 남는다. 진행 중 중복은 202, 생성 완료/실패는 기존대로 200이다.
외부 응답 원문·API 키·내부 오류 메시지를 사용자 응답에 노출하지 않는다.

## 검증

Java 21 환경에서 유료 API 키 없이 실행한다.

```sh
./gradlew test --tests '*DreamAi*' --tests '*AiGatewayStructureGeneratorTest' --tests '*AiGatewayStoryGeneratorTest'
./gradlew test
git diff --check
```

- 단위 테스트: 요청 맥락·프롬프트·스키마·입력 데이터, 내부 ID 제거, 공유 한도 초과,
  성공/실패 시 슬롯 반납, 키 미설정, 잘못된 한도·통신 예산.
- 모의 LINER HTTP + 실제 MVC/JPA 통합 테스트: 분석→서사화 저장·재사용,
  외부 실패/잘못된 출력과 재시도, 진행 중 중복, 원문 수정·삭제 경합, 기능 간 공유 한도.
  가상 스레드를 끈 환경에서 확인하며 외부 호출 시 트랜잭션·EntityManager 해제도 확인한다.
- 실행 자원 테스트: 미완료 Future의 즉시 반환, 전용 완료 스레드, 저장 종료 전 슬롯 유지,
  실패 후 슬롯 반납, HTTP 결과 취소 후에도 저장 유지.
- 키 미설정 통합 테스트: 분석/서사화 `UNAVAILABLE`, 새 시도 없음, 기존 원문/분석 유지.
- 기존 분석·서사화·이미지·인증·Swagger 테스트도 전체 회귀 확인에 포함한다.

2026-10-08 검증: develop `091f28e`(#32 병합) 기반 Java 21에서
전체 295개 테스트 통과(실패 0, 오류 0, 제외 0). `git diff --check`도 통과했다.
검증 환경에서 Mockito 자동 agent 연결이 제한되어 테스트 JVM에 Mockito agent를 명시적으로 주입했다.
이 환경 전용 설정은 프로젝트 빌드 파일에 추가하지 않았다.
실제 LINER 키와 유료 호출은 사용하지 않았으며 모델 출력 품질 평가는 별도로 필요하다.

실제 모델 출력 확인은 키를 실행 환경에 주입한 뒤 저장 완료된 꿈으로 수행한다.
분석 `POST /api/v1/dreams/{dreamId}/analysis`에 현재 꿈의 `revision`을 보낸다.
성공 후 꿈을 다시 조회해 최신 `revision`으로 서사화
`POST /api/v1/dreams/{dreamId}/story`를 요청한다.
두 요청의 기본 본문은 `{"revision":현재_꿈_revision}`이며 Bearer Access JWT가 필요하다.
생성 제목·키워드·장면과 이야기의 원문 사실·순서를 직접 대조한다.
이 수동 확인은 유료 호출일 수 있으며 자동 테스트에 실제 키를 사용하지 않는다.
