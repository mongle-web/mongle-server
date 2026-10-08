# 비동기 AI Gateway

`AiGateway.generate(request)`는 `CompletableFuture<AiGenerationResult>`를 반환한다.
HTTP 응답을 기다리는 `get()`/`join()`과 재시도 대기의 `Thread.sleep()`은 운영 구현에서 사용하지 않는다.
HTTP 비동기는 긴 응답 대기에 요청별 작업 스레드를 붙이지 않는 방법이며, 트랜잭션 분리와는 별개의 문제다.

## 실행 순서와 클래스 역할

```text
호출 스레드
  generate(request) → 동시 호출 슬롯 확보 → 요청 준비 작업 제출 → Future 반환

ai-response-* 스레드
  Call.prepare() → 키 검사·요청 JSON 생성 → Call.startAttempt()
  → LinerClient.complete(): HTTP 시작, 완료 콜백 등록
  → 작업 종료 (응답을 기다리지 않음)

HTTP 응답 도착 또는 시간 초과
  → 완료 콜백이 응답 처리 풀에 제출
  → Call.observe(): Mapper 검증, 시도 메타데이터 생성
  → recordAsync(): 저장 작업 제출 → 작업 종료

ai-log-* 스레드
  AiGenerationLogService.record() → 비용 계산
  → TransactionTemplate 안에서 DB 로그 저장·커밋
  → Call.afterLog(): 최종 결과 완료 또는 다음 시도 예약

ai-timer-* 스레드
  재시도 시각이 되면 응답 처리 풀에 다음 시도 준비를 제출
  / HTTP 본문 수신 기한이 되면 실패 완료와 원본 HTTP 취소
```

응답 성공 시 로그 저장 시도가 끝나면 결과 Future를 완료한다. 실패도 시도 로그 후 예외 완료한다.
저장 오류나 저장 작업 제출 거절은 경고를 남기고 원래 결과/AI 예외를 유지한다.
JPA 자체는 블로킹 작업이므로 HTTP 완료·타이머·공용 ForkJoinPool에서 실행하지 않는다.

읽는 순서:
1. `gateway/AiGateway.java`: 비동기 반환 계약과 취소 범위.
2. `liner/LinerAiGateway.java`: `generate()` → `Call.prepare()` → `startAttempt()` → `observe()` → `afterLog()`.
3. `liner/client/LinerClient.java`: 원본 HTTP Future와 반환 Future, 타임아웃·취소 연결.
4. `config/AiAsyncProperties.java`, `AiAsyncResources.java`, `AiAsyncConfig.java`: 한도·큐·타이머·Bean 종료.
5. `service/AiGenerationLogService.java`: 실제 저장 스레드에서 트랜잭션 시작.
6. `LinerAiGatewayIntegrationTest`와 `AiGenerationLoggingIntegrationTest`: 로컬 HTTP·H2 실행 예시.

## 동시성·작업 큐·설정

설정은 `mongle.ai.async` 아래에 있다. 서버 시작 시 허용 범위를 검증한다.

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| AI_MAX_CONCURRENT_CALLS | 16 | 서버 인스턴스별 동시 논리 호출 상한 |
| AI_RESPONSE_THREADS | 2 | 요청 준비·응답 변환 작업 스레드 수 |
| AI_RESPONSE_QUEUE_CAPACITY | 64 | 응답 처리 작업 큐의 상한 |
| AI_LOG_THREADS | 2 | JPA 로그 저장 작업 스레드 수 |
| AI_LOG_QUEUE_CAPACITY | 64 | 로그 저장 작업 큐의 상한 |

초기값은 처리 성능이나 LINER 계정의 허용량을 보장하는 수치가 아니다. 실제 응답 시간·계정 한도·DB 풀 사용량과
부하 측정을 기준으로 조정한다. 여러 인스턴스가 있으면 각 인스턴스에 한도가 적용되므로 전체 합산도 고려해야 한다.

- `Semaphore.tryAcquire()`로 비동기 처리 입장을 제한한다. 자리가 없으면 기다리거나 쌓지 않고 `AI_503_4`로 즉시 예외 완료한다.
- 슬롯은 재시도 대기와 로그 저장을 포함한 논리 호출 전체에 적용한다. 성공·실패·취소·종료 시 한 번만 반납한다.
- 요청 준비/응답 처리 큐가 찼을 때도 `AI_503_4`로 완료해 Future가 미완료 상태로 남지 않게 한다.
- 로그 큐 포화는 로그만 생략한다. 호출 스레드에서 대신 DB 저장을 수행하는 `CallerRunsPolicy`를 사용하지 않는다.
- 타이머는 한 스레드이며, 예약 수는 진행 중인 논리 호출 수로 제한된다. 취소한 예약은 큐에서 즉시 제거한다.
- Gateway 안에 사용자 작업을 보관하는 무제한 대기열은 없다. 지속 가능한 작업 접수·재실행은 도메인 기능에서 설계한다.

## 실패·재시도·취소·서버 종료

기존 HTTP 오류 분류, Retry-After, 최대 1회 재시도, 요청별/전체 시간 예산, 사용량·비용 규칙은 유지한다.
새 용량 초과 `CAPACITY_EXCEEDED=AI_503_4`는 이 서버의 처리 한도이고 제공자의 `RATE_LIMITED`와 구분한다.
둘 모두 사용자에게는 공통 한국어 오류를 전달하지만 로컬 용량 초과는 Gateway에서 자동 재시도하지 않는다.

외부 호출 실패는 Future를 `AiGatewayException`으로 예외 완료한다. `get()`은 ExecutionException,
`join()`은 CompletionException으로 원인을 감싸므로 완료 처리에서 원인 예외를 확인한다.
사용자 HTTP 응답 변환은 해당 Controller/도메인이 연결한다. 이 구현에 공개 AI Controller는 없다.

`cancel(true)`는 진행 중인 HTTP Future와 예약된 재시도를 취소하고 슬롯을 반납한다.
반환 Future는 CancellationException으로 종료되며 진행 중 시도는 가능한 범위에서 `AI_503_5`로 기록한다.
이미 관측한 응답이나 저장 중인 로그는 유지한다. 재시도 대기 중 취소에는 새 HTTP 시도 행을 만들지 않는다.
취소 Future는 로그 커밋을 기다리지 않는다. 로컬 취소는 제공자의 생성·과금 취소를 보장하지 않는다.

Client의 타임아웃은 완성된 본문을 받는 Future에 적용한다. 헤더만 수신하고 본문이 멈춰도 기한을 적용한다.
파생 Future 실패만으로 원본 HTTP가 정리되지는 않으므로 타임아웃/취소를 원본에 명시적으로 전달한다.
타임아웃과 전송 실패는 중복 생성·과금 가능성이 있어 자동 재시도하지 않는다.

서버 종료 시 `@PreDestroy`에서 새 호출을 거절하고 진행 중 결과를 `AI_503_3`으로 종료한다.
HTTP·재시도를 정리하고 마지막 시도 로그를 제출한 뒤 `drainLogs()`에서 새 로그 제출을 막고,
이미 제출된 작업을 최대 5초 기다린다. 로그 서비스·Repository·트랜잭션 매니저보다 Gateway가 먼저
파괴되므로 DB 자원이 살아 있는 Gateway 종료 메서드에서 기다린다. 응답 관측과 로그 제출도 같은
호출 상태 잠금으로 보호해 종료가 그 사이에 끼어들지 않게 한다.
저장이 먼저 끝나면 바로 종료한다. 시간 초과·종료 스레드 인터럽트에는 강제 정리를 요청하고 경고를 남긴다.
강제 정리는 작업의 즉시 중단을 보장하지 않는다. 프로세스 강제 종료·DB 장애·큐 포화 등에는 로그가 유실될 수 있다.
이 대기는 정상 종료 과정에만 있으며 일반 AI 호출이나 응답 대기에는 추가하지 않는다.
`LINER_TOTAL_TIMEOUT`은 HTTP/재시도 예산이다. 저장 큐·DB 저장까지 엄격히 중단하는 전체 결과 기한은 아니다.

## 이후 호출부에서 사용하기

개념 예시이며 아래 함수와 도메인 실행기는 꿈 기능에서 구현한다.

`thenAccept()`·`thenApply()` 같은 비-Async 콜백은 Gateway 결과를 완료하는 내부 스레드에서 실행될 수 있다.
이미 완료된 Future에 연결하면 등록 스레드에서 실행될 수도 있다. 기본 두 개의 `ai-log-*` 스레드에
도메인 DB 작업을 붙이면 다른 로그 저장과 결과 완료가 밀리므로 아래처럼 실행기를 명시한다.
Async 메서드에도 실행기를 생략하면 공용 풀을 사용하므로 DB 작업에는 도메인의 제한된 실행기를 지정한다.

```java
// 호출 전에 사용자·소유권을 확인하고 필요한 입력 데이터를 읽어 트랜잭션을 종료한다.
CompletableFuture<AiGenerationResult> pending = aiGateway.generate(request);

// 결과가 준비되면 도메인 실행기에서 검증·결과 저장을 수행한다. 저장 함수 안에서 새 트랜잭션을 시작한다.
CompletableFuture<Void> handled = pending.thenAcceptAsync(result -> {
    validateAndSaveDream(result.content());
}, dreamExecutor);

handled.exceptionally(error -> {
    // 별도의 제한된 실행기로 실패 상태 저장을 제출한다. 이 짧은 콜백에서 DB를 직접 기다리지 않는다.
    submitFailureStateUpdate(error);
    return null;
});
```

호출부도 `get()`/`join()`으로 기다리지 않아야 한다. 취소할 때는 파생된 `handled`만 취소하는 대신
실제 Gateway가 반환한 `pending.cancel(true)`를 호출한다. 반환 Future는 완료 처리 연결과 취소에만 사용하고
`complete()`/`completeExceptionally()`/`obtrudeValue()`/`orTimeout()`으로 Gateway의 진행 상태를 덮어쓰지 않는다.
별도 호출부 기한이 필요하면 원본 `pending` 취소와 연결해서 설계한다.

Spring의 요청 스레드 트랜잭션은 작업 스레드로 자동 전파되지 않는다. 로그는 자체 TransactionTemplate을 사용하며,
도메인 결과 저장에도 작업 실행 스레드에서 시작하는 트랜잭션 경계가 필요하다. 참조할 사용자는 먼저 커밋돼 있어야 한다.
꿈 접수 API·상태 전환·중복 분석 방지·결과 조회 API는 꿈 도메인의 책임이다.

## 검증

테스트에서만 결과를 제한 시간으로 기다리는 `AiGatewayTestAwait`를 사용한다. 운영 코드에는 해당 대기가 없다.
기존 요청/응답·오류·재시도·사용량·비용·영속성 검증과 함께 다음을 검증한다.

- 서버 본문을 latch로 멈춰도 generate()가 반환되고 결과 Future는 아직 미완료인 상태.
- 로그 저장 함수가 ai-log-* 스레드에서 실행되는 것.
- 한도 초과 시 추가 HTTP 없이 거절, 취소 후 슬롯 재사용과 중복 로그 방지.
- 재시도 대기 중 취소 후 두 번째 HTTP가 발생하지 않는 것.
- 로그 큐 포화 시 정상 결과와 원래 제공자 실패 유지.
- 응답 작업 큐 포화 시 결과 완료와 슬롯 반납.
- 명시적 취소·본문 타임아웃이 원본 HTTP Future까지 전달되는 것.
- 서버 종료 시 미완료 호출 종료와 신규 요청 거절.
- 실제 Spring 컨텍스트 종료에서 큐에 남은 실패 행이 H2에 커밋된 뒤 DB 자원이 닫히는 것.
- 저장이 지연되면 제한 시간 또는 인터럽트에 종료 대기를 끝내고 인터럽트 상태를 보존하는 것.

로컬 모의 HTTP 서버와 H2 검증이며 실제 LINER 유료 호출이나 운영 부하 시험은 수행하지 않는다.

## 참고

- [Java 21 HttpClient](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html)
- [Java 21 CompletableFuture](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html)
- [Java 예약 실행기](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ScheduledThreadPoolExecutor.html)
- [Spring 트랜잭션 실행 경계](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-decl-explained.html)
