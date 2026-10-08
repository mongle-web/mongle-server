# LINER Gateway 연동

두 번째 AI Gateway 이슈의 구현이다. 도메인 서비스는 기존 `AiGateway`에 메시지와
선택적인 JSON Schema를 전달하고, `LinerAiGateway`가 실제 HTTP 통신을 수행한다.
운영 Bean으로 등록되므로 `AiGateway` 생성자 주입으로 사용할 수 있다.
현재 반환 계약은 `CompletableFuture<AiGenerationResult>`이며 완료 콜백으로 결과를 사용한다.
[비동기 실행·용량 제한·취소 문서](ai-gateway-async.md)를 함께 참고한다.

## 패키지와 읽는 순서

```text
domain.ai
├── gateway/AiGateway                 # 도메인이 사용하는 공통 호출 계약
├── dto/request, dto/response         # 공통 요청·응답
├── error                            # 공통 AI 오류
└── liner
    ├── LinerAiGateway               # 전체 시간 예산·재시도·호출 조율
    ├── config
    │   ├── LinerProperties          # 환경변수 바인딩·설정 검증
    │   └── LinerConfig              # 공유 HTTP 클라이언트 Bean
    ├── client/LinerClient           # HTTP 요청 한 번·본문 수신 제한
    └── mapper/LinerPayloadMapper    # LINER JSON과 공통 DTO 변환
```

1. `AiGateway`와 `LinerAiGateway.generate()`에서 전체 호출 흐름을 읽는다.
2. `LinerPayloadMapper.requestBody()`에서 공통 요청이 LINER JSON으로 바뀌는 과정을 읽는다.
3. `LinerClient.complete()`에서 헤더, HTTP POST, 본문 수신 타임아웃을 확인한다.
4. `LinerPayloadMapper.result()`와 `failure()`에서 정상·오류 응답 변환을 읽는다.
5. `LinerAiGateway.retryDelay()`와 `Call.afterLog()`에서 재시도 제한과 다음 시도의 예약 흐름을 확인한다.
6. `LinerProperties` → `LinerConfig` → `application.yaml`에서 설정을 확인한다.
7. `LinerAiGatewayIntegrationTest`에서 실제 로컬 HTTP 요청과 응답 예시를 확인한다.

## 설정과 실행

Spring의 `mongle.ai.liner` 설정을 사용한다. API 키는 저장소·요청 DTO에 넣지 않고
실행 환경에서 주입한다. `.env` 파일은 자동으로 읽지 않으므로 IDE 실행 설정이나
셸 환경변수를 사용한다. 키가 없어도 서버는 기동하고, 실제 Gateway 호출은
`AUTHENTICATION_FAILED` (`AI_502_2`)로 실패한다. 시작 시 외부 API를 호출하지 않는다.

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| `LINER_API_KEY` | 빈 값 | 발급받은 API 키 |
| `LINER_ENDPOINT` | `https://platform.liner.com/api/v1/chat/completions` | 전체 호출 URL |
| `LINER_MODEL` | `liner-mark-1.1` | 요청할 공개 모델 |
| `LINER_MAX_COMPLETION_TOKENS` | `4096` | 최대 생성 토큰 수 |
| `LINER_REASONING_EFFORT` | `medium` | `none`, `low`, `medium`, `high`, `max` |
| `LINER_CONNECT_TIMEOUT` | `3s` | 새 연결을 기다리는 시간 |
| `LINER_REQUEST_TIMEOUT` | `45s` | HTTP 시도 한 번의 제한 시간 |
| `LINER_TOTAL_TIMEOUT` | `60s` | 직렬화·호출·재시도 대기를 포함한 전체 예산 |
| `LINER_MAX_RETRIES` | `1` | 최초 호출 이후 재시도 횟수. 0 또는 1 |
| `LINER_RETRY_BACKOFF` | `300ms` | jitter 계산의 기준 대기 시간 |
| `LINER_MAX_RETRY_DELAY` | `5s` | 자동 재시도에서 허용할 최대 대기 시간 |

키를 실행 환경에 설정한 뒤 로컬 서버를 실행한다.

```sh
./gradlew bootRun --args='--spring.profiles.active=local'
```

Gateway는 내부 서비스 계약이다. 이번 이슈에서 공개 AI Controller를 추가하지 않았으므로
서버 실행만으로 LINER 호출이 발생하지 않는다. 실제 꿈 서비스에서 메시지를 구성해
`AiGateway.generate(request)`를 호출하는 부분은 해당 도메인 기능에서 추가한다.
4096 토큰은 초기 상한이며 도메인 출력 크기·추론 사용량을 관찰해 조정한다.

설정은 시작 시 검증한다. 연결 ≤ 시도 ≤ 전체 시간, 기본 대기 ≤ 최대 대기여야 한다.
API 키의 공백·제어 문자, 잘못된 추론 강도, 유효하지 않은 토큰·재시도 제한을 거절한다.
주소는 HTTPS를 사용하고, 모의 서버를 위한 localhost·127.0.0.1·IPv6 루프백만 HTTP를 허용한다.
자동 리다이렉트를 따르지 않으며 HTTP 클라이언트는 연결 풀을 공유하고 종료 시 정리한다.

## 요청과 응답의 실행 흐름

예를 들어 도메인이 `SYSTEM`, `USER` 메시지와 `AiJsonSchema`를 구성해 전달하면:

1. Gateway가 키를 확인하고 전체 시간 측정을 시작한다.
2. Mapper가 메시지 순서·원문을 유지한 LINER 요청을 만든다. `stream=false`를 명시하고
   스키마가 있으면 `response_format.json_schema`에 `name`, `schema`, `strict`를 넣는다.
   스키마가 없으면 `response_format.type=text`를 사용한다.
3. Client가 Bearer 인증과 JSON 헤더를 붙여 POST하고 Future를 반환한다. 본문 전체 수신까지
   타이머로 제한하고, 시간 초과 시 원본 HTTP Future를 취소한다. 호출 스레드는 응답을 기다리지 않는다.
4. HTTP 완료 콜백이 응답 처리 풀에 작업을 제출한다. 200 응답은 Mapper가 공통 결과로,
   HTTP 실패는 공통 AI 예외로 변환한다.
5. Gateway가 로그 저장 풀에 시도 메타데이터·사용량·비용 기록을 제출한다. 저장이 끝나거나
   생략되면 성공 Future를 완료하거나, 실패 유형·횟수·남은 시간에 따라 재시도를 예약한다.
6. Gateway의 결과 Future가 완료되면 도메인이 자신의 완료 처리에서 생성 원문을 DTO로 읽고
   업무 규칙을 검증한 뒤 저장한다. 해당 저장용 트랜잭션은 도메인의 작업 스레드에서 새로 시작한다.

`userId`, `taskType`, `promptVersion`은 몽글 내부의 호출 맥락이며 LINER에 전송하지 않는다.
도메인 프롬프트를 자동 추가하거나 수정하지 않는다. HTTP 통신 중 DB 트랜잭션을 열지 않으며,
시도가 끝난 뒤 로그 저장 서비스에서 독립 트랜잭션을 사용한다.

정상 결과는 선택지 하나의 `assistant` 메시지여야 한다. 생성 원문은 공백·줄바꿈을 유지한다.
모델명은 응답의 `model`을 사용하며 누락 시 요청 모델로 채우지 않는다. 이는 공개 모델명이며
LINER 내부에서 라우팅된 실제 모델명을 알아냈다는 뜻이 아니다.
추적 ID는 `x-request-id` 헤더만 사용한다. 본문의 completion `id`로 대신 채우지 않는다.

사용량은 `prompt_tokens`, `completion_tokens`, `total_tokens`,
`prompt_tokens_details.cached_tokens`, `completion_tokens_details.reasoning_tokens`를 읽는다.
미제공 값은 null, 제공된 0은 0으로 보존한다. 숫자가 아닌 값·음수·합계 불일치는 실패로 처리한다.
전체 소요 시간에는 모든 HTTP 시도와 대기, 응답 변환·로그 저장 시간이 포함된다.
마지막 로그 저장 시간은 HTTP/재시도 예산 외에 추가될 수 있다.

빈 본문·잘못된 JSON·잘못된 메타데이터는 `INVALID_RESPONSE`다.
`length`, `tool_calls`, `content_filter`, 실제 도구 호출·거절 메시지는 `INCOMPLETE_RESPONSE`다.
미제공 종료 사유는 `UNKNOWN`, 새 종료 사유는 원문을 가진 `OTHER`로 보존한다.
JSON Schema 요청의 결과는 JSON 문법과 뒤따르는 쓰레기 데이터 여부만 검증한다.
JSON Schema 전체 검증과 도메인의 필드·참조·순서 등 업무 검증은 호출부 책임이다.

## 실패와 재시도

| 제공자 상태/상황 | 공통 오류 | 자동 재시도 |
| --- | --- | --- |
| 400, 404, 413, 422 | `INVALID_REQUEST` | 없음 |
| 401, 403 / 키 미설정 | `AUTHENTICATION_FAILED` | 없음 |
| 402 | `INSUFFICIENT_CREDIT` | 없음 |
| 429 | `RATE_LIMITED` | 제한적 후보 |
| 500, 502, 503 | `PROVIDER_UNAVAILABLE` | 제한적 후보 |
| 408, 504 / 수신 제한 시간 초과 | `TIMEOUT` | 없음 |
| 연결 단절 등 전송 실패 / 작업 중단 | `PROVIDER_UNAVAILABLE` | 없음 |
| 리다이렉트 등 그 밖의 상태 / 잘못된 결과 | `INVALID_RESPONSE` | 없음 |

제공자의 `error.retryable=false`가 있으면 HTTP 상태가 후보여도 재시도하지 않는다.
필드가 없으면 Chat Completions 명세의 HTTP 상태 정책을 사용한다. 필드 타입이 잘못되면
재시도를 금지한다. `Retry-After`는 초 수 또는 HTTP 날짜로 읽으며 해석 실패 시 재시도하지 않는다.

대기는 스레드를 sleep시키지 않고 타이머에 예약한다. 기본 backoff의 절반~전체 사이에서 jitter를 주고 `Retry-After` 이상으로 기다린다.
최대 대기를 초과하거나 남은 전체 예산 안에 대기할 수 없으면 원래 실패를 반환한다.
제공자의 대기 시간을 잘라서 일찍 재시도하지 않는다. 두 번째 시도의 제한 시간도 남은 예산으로
줄이므로, 재시도할 때 전체 60초가 다시 시작되지 않는다. 재시도 소진 후에도 예외의
`retryable`은 제공자 실패의 힌트이며 이 Gateway의 추가 시도를 의미하지 않는다.

타임아웃·전송 실패는 이미 생성·과금이 처리되었는지 알 수 없으므로 자동 재호출하지 않는다.
취소는 로컬 대기를 끝내는 조치이며 제공자의 생성·과금 취소를 보장하지 않는다.
외부 오류 본문, 프롬프트, API 키 및 파싱 예외 원문을 공통 예외에 첨부하지 않는다.
사용자에게는 기존 `GlobalExceptionHandler`가 안전한 고정 한국어 메시지를 반환한다.

## 검증 범위

`LinerAiGatewayIntegrationTest`는 JDK 모의 HTTP 서버를 사용해 텍스트·JSON Schema 요청,
사용량 누락/0, 추적 ID, 오류 분류, 재시도 금지/소진, Retry-After 대기, 본문 수신 타임아웃,
재시도 전체 예산, 불완전한 결과, 리다이렉트 거절, 키 미설정 처리를 확인한다.
추가로 응답 전 Future 반환, 전용 저장 스레드, 호출 한도 초과, 원본 HTTP 취소,
재시도 예약 취소, 저장 큐 포화, 응답 처리 큐 포화, 서버 종료를 검증한다.
전체 Spring 테스트는 Bean 구성과 기존 인증·JPA·Swagger 기능도 함께 검증한다.

이 테스트는 실제 LINER 가용성·계정 권한·과금이나 생성 품질을 검증하지 않는다.
호출 시도 로그·사용량 저장·비용 계산은 세 번째 이슈에서 구현했으며
[로그와 비용 문서](ai-generation-logging.md)에서 행의 의미·계산 규칙·DB 적용 방법을 확인한다.

## 공식 문서

- [LINER Chat Completions](https://liner.com/developers/docs/liner-model-api-chat-completions)
- [LINER 오류와 재시도](https://liner.com/developers/docs/rate-limits)
- [Java 21 HttpClient](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html)
- [공통 Gateway 계약](ai-gateway-contract.md)
