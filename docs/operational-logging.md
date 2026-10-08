# 운영 로그 사용법

`@Slf4j`의 서버 로그는 요청 추적용이다. `ai_generation_logs`의 사용량·비용 행과는 별개이며,
현재 로그 출력만으로 대시보드나 알림이 자동 생성되지는 않는다. 운영 로그 수집기에 표준 출력을 연결해 사용한다.

## 식별자로 흐름 따라가기

- `requestId`: HTTP 요청마다 서버가 생성하는 UUID. 응답의 `X-Request-Id` 헤더에도 반환한다.
- `callId`: Gateway 논리 호출마다 생성하는 UUID. 재시도와 DB 시도 행이 같은 값을 공유한다.
- `providerRequestId`: LINER가 반환한 추적 ID. 위 두 서버 식별자와 구분한다.

HTTP 필터는 Spring Security보다 먼저 실행한다. 요청 스레드의 MDC에 `requestId`를 넣어
기존 예외 처리·인증 로그를 연결하고, 디스패치가 끝나면 이전 MDC 값을 복구한다.
ASYNC 재디스패치도 같은 ID를 사용하며, 비동기 요청 완료는 AsyncListener에서 한 번만 기록한다.
첫 디스패치 반환을 요청 완료로 처리하지 않는다. 처리하지 못하고 필터 밖으로 전파되는 동기 예외는
최소 500 상태로 요약한다. 컨테이너의 후속 오류 처리까지 기다리는 비동기 요청과 구분한다.

Gateway는 최초 호출 시 MDC의 ID를 복사해 콜백 로그에 직접 넣는다.
수동 ThreadPoolExecutor에 MDC 전파를 가정하거나 공용 스레드에 MDC를 남겨두지 않는다.
HTTP 밖에서 직접 Gateway를 호출하면 `requestId=unknown`이고 `callId`로 추적한다.

아래는 예시이며 실제 호출 결과가 아니다.

```text
INFO AI 호출 접수: requestId=http-uuid, callId=ai-uuid, userId=7, taskType=DREAM_STRUCTURE, requestedModel=liner-mark-1.1
WARN AI 재시도 예약: requestId=http-uuid, callId=ai-uuid, attemptNo=2, delayMs=1000, code=AI_429_1
INFO AI 호출 완료: requestId=http-uuid, callId=ai-uuid, outcome=success, attemptNo=2, model=liner-mark-1.1, elapsedMs=3200, code=null
INFO HTTP 요청 완료: requestId=http-uuid, method=POST, path=/api/v1/..., status=200, elapsedMs=3300, errorType=none
```

공개 AI Controller는 아직 없다. 이후 접수 API가 먼저 응답하도록 구현하면 HTTP 완료 로그가
AI 완료보다 먼저 남을 수 있다. `requestId`와 `callId`로 시간 순서를 연결해 읽는다.
응답의 모델명은 제공자가 보고한 공개 모델명이며 내부 라우팅 모델로 추정하지 않는다.

## 기록 위치와 레벨

| 위치 | 사건 | 레벨 |
| --- | --- | --- |
| HttpRequestLoggingFilter | 요청 완료, 메서드·경로·상태·처리 시간 | 2xx/3xx INFO, 4xx WARN, 5xx ERROR |
| LinerAiGateway.generate | 호출 접수 | INFO |
| generate/dispatch/recordAsync | 한도 초과·큐 포화·서버 종료로 작업 거절 | WARN |
| Call.startAttempt | HTTP 전송 시작과 제한 시간 | DEBUG |
| Call.recordOutcome | 시도 결과와 보고된 토큰 수 | 성공 DEBUG, 실패 WARN |
| Call.afterLog | 재시도 예약과 지연 시간 | WARN |
| Call.finish / cancel | 최종 결과와 전체 소요 시간 | 성공·취소 INFO, 실패 WARN |
| AiGenerationLogService.record | 로그 DB 커밋 완료와 추정 비용 | DEBUG |
| 로그 저장 실패 | 저장 누락을 진단할 ID와 오류 종류 | WARN |
| TokenService | 세션 발급·로그아웃 커밋 완료 | INFO |
| TokenService | 토큰 갱신 커밋 완료 | DEBUG |
| TokenService | 리프레시 토큰 재사용 탐지·세션 폐기 완료 | WARN |
| SocialLoginService / OAuthLoginHandlers | 가입·로그인 완료 / 로그인 실패 | INFO / WARN |
| UserService | 최초 온보딩 커밋 완료 | INFO |

예상된 AI 오류는 코드와 시도 정보를 기록한다. 생성 원문이 섞일 수 있는 제공자 예외 스택이나 메시지를
새 로그에 첨부하지 않는다. 예상하지 못한 공통 서버 오류의 스택은 기존 GlobalExceptionHandler가 담당한다.
Future의 실패가 HTTP 핸들러에 도달하지 않아도 Gateway 최종 실패 로그는 남는다.

`CommittedLog.afterCommit`은 인증·가입·온보딩 변경의 실제 커밋 뒤에 로그만 실행한다.
바깥 트랜잭션이 롤백되면 성공 로그가 없다. 동일 닉네임으로 온보딩을 다시 요청해도 새 완료 로그를 만들지 않는다.
재사용 탐지는 즉시 기록하고, 세션 폐기 완료는 커밋 후 기록해 탐지와 조치를 구분한다.

## 환경변수

기본은 INFO 텍스트 로그이며 `requestId`가 로그 레벨 옆에 표시된다.

```sh
# 개별 AI 전송·토큰·DB 저장 정보를 조사할 때만 설정한다.
export AI_LOG_LEVEL=DEBUG

# JSON 로그를 받는 수집기에 연결할 경우 설정한다. 생략하면 기본 텍스트 출력이다.
export LOG_FORMAT_CONSOLE=logstash
```

JSON 출력에서는 식별자를 포함한 현재 메시지와 요청 스레드의 MDC 필드를 확인할 수 있다.
메시지 속 key=value가 전부 독립 JSON 필드로 자동 변환되는 것은 아니다.
현재 파일 저장·보관 기간·외부 수집기·메트릭·경보 설정은 별도로 구축하지 않았다.

## 기록하지 않는 값

새 로그에는 HTTP 쿼리·본문·Authorization·쿠키, API 키·JWT·리프레시 토큰·토큰 해시,
이메일·닉네임·소셜 사용자 원본 ID, 꿈 원문·프롬프트·생성 원문·제공자 오류 본문을 담지 않는다.
HTTP 경로는 가능한 경우 MVC 경로 패턴을 사용하며, 외부 메타데이터는 최대 256자로 제한하고 제어 문자를 치환한다.
내부 userId/sessionId는 운영 접근 제어가 적용된 로그 수집기에서 다룬다.

## 비동기 인증과 검증

실제 서버 테스트에서 기존 NullSecurityContextRepository 설정은 인증된 DeferredResult 요청의
ASYNC 재디스패치에서 인증을 읽지 못해 401로 끝났다. RequestAttributeSecurityContextRepository로
변경해 같은 요청 안에서 인증을 유지한다. HTTP 세션에 인증을 저장하지 않으며 다음 요청은 별도 인증이 필요하다.

추가 테스트는 실제 HTTP 서버의 인증 거절·비동기 완료, 커밋/롤백에 따른 성공 로그,
AI 재시도의 ID 연결·원문 비노출·외부 메타데이터 줄바꿈 치환을 검증한다.
AI 검증은 로컬 모의 제공자이며 유료 호출을 사용하지 않는다.

## 읽는 순서

1. `global/config/LoggingConfig`: 필터 등록 순서와 디스패치 범위.
2. `global/logging/HttpRequestLoggingFilter`: ID·MDC·비동기 완료 처리.
3. `domain/ai/liner/LinerAiGateway`: 접수·시도·재시도·완료 로그.
4. `global/logging/CommittedLog`와 인증/사용자 서비스: 커밋 후 로그.

참고: [Lombok 로그 어노테이션](https://projectlombok.org/features/log),
[Spring Boot 로깅](https://docs.spring.io/spring-boot/reference/features/logging.html),
[Spring 인증 컨텍스트 저장](https://docs.spring.io/spring-security/reference/servlet/authentication/persistence.html).
