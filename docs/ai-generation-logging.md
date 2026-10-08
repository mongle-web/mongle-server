# AI 호출 시도 로그와 비용 계산

29번 이슈는 LINER 호출 시도를 자동 기록하고 `AiGateway.generate()`를 비동기 계약으로 바꿨다.
호출부는 `generate(AiGenerationRequest)`로 `CompletableFuture<AiGenerationResult>`를 받고 완료 처리를 연결한다.
실행 자원·취소·용량 제한은 [비동기 Gateway 문서](ai-gateway-async.md)를 참고한다. 로그 조회 API, 비용 대시보드,
꿈 프롬프트·JSON Schema·도메인 결과 저장은 이번 범위에 포함하지 않는다.

## 구현 흐름과 읽는 순서

```text
도메인 → AiGateway.generate(request)
         └─ LinerAiGateway
            1. callId(UUID) 발급, 키 검사, 요청 JSON 생성
            2. LinerClient.complete(): 비동기 HTTP 시도·완료 콜백
            3. LinerPayloadMapper.result()/failure(): 결과 또는 공통 오류
            4. recordAsync(): 저장 풀에 AiGenerationAttempt 제출
               └─ AiGenerationLogService.record()
                  ├─ AiGenerationCostCalculator.calculate()
                  │  └─ AiPricingProperties: 응답 모델별 단가
                  └─ REQUIRES_NEW 트랜잭션
                     └─ AiGenerationLog.create() → Repository.saveAndFlush()
            5. 성공 반환 / 오류 전달 / 다음 시도
```

1. `liner/LinerAiGateway.java`: `Call.observe()` → `recordOutcome()` → `recordAsync()` → `afterLog()`에서 기록 시점을 확인한다.
2. `dto/logging/AiGenerationAttempt.java`: 무엇을 기록하고 무엇을 저장하지 않는지 확인한다.
3. `liner/mapper/LinerPayloadMapper.java`: `metadata()`는 실패한 200 응답에서도 유효한 메타데이터를 추출한다.
4. `service/AiGenerationCostCalculator.java`: 캐시·추론 토큰의 중복 계산을 막는 수식을 확인한다.
5. `config/AiPricingProperties.java`와 `application.yaml`: 가격표와 설정 검증을 확인한다.
6. `service/AiGenerationLogService.java`: 독립 트랜잭션과 저장 오류 보호 범위를 확인한다.
7. `entity/AiGenerationLog.java`와 `entity/AiCostStatus.java`: DB 컬럼·미확정 상태를 확인한다.
8. `AiGenerationLoggingIntegrationTest`: 실제 로컬 HTTP와 H2를 연결한 예시를 확인한다.

엔티티의 builder는 private이고 생성 진입점은 public 정적 팩터리다.
기존 초기 세팅의 `create(...)`도 유지하며, 그 계약으로 생성한 행은 `LEGACY`로 표시한다.

## 행 하나는 시도 하나

429 후 성공하는 호출은 다음 두 행을 남긴다. 논리 요청 ID는 같고 제공자 요청 ID는 다르다.

| call_id | attempt_no | http_status | success | error_code | request_id |
| --- | --- | --- | --- | --- | --- |
| 동일 UUID | 1 | 429 | false | AI_503_2 | 첫 응답 헤더 |
| 동일 UUID | 2 | 200 | true | null | 두 번째 응답 헤더 |

`(call_id, attempt_no)`에는 UNIQUE 제약을 둔다. 행의 PK는 기존 AUTO_INCREMENT를 유지한다.
사용자별 시간 조회를 위해 `(user_id, created_at)` 인덱스를 둔다.

- `requested_model`은 설정 모델명, `model_name`은 응답 모델명이다. 서로 대체하지 않는다.
  응답 모델명이 LINER 내부의 실제 라우팅 모델을 공개한다는 뜻은 아니다.
- `request_id`는 `x-request-id` 헤더다. 본문의 completion `id`로 대체하지 않는다.
- `success`는 Gateway가 공통 결과를 반환했는지다. 이후 도메인의 업무 검증·저장 성공과 별개다.
- 로그의 `latency_ms`는 해당 시도의 통신·응답 처리 큐 대기·응답 검증 시간이며 DB 저장과 재시도 대기는 제외한다.
  `AiGenerationResult.latencyMs`는 최초 호출부터 반환 직전까지의 전체 시간이다.
- 타임아웃은 완성된 응답이 없으므로 `http_status`, 응답 모델, 사용량 등이 null이다.
  `http_attempted=true`로 통신 시도는 표시하지만 제공자가 과금하지 않았다고 판단하지 않는다.
- 키 누락/요청 직렬화 실패는 `http_attempted=false`인 첫 번째 실패 행으로 남긴다.
- 재시도 대기 중 취소나 첫 실패 후 시간 예산 소진은 새 HTTP 시도가 아니므로 행을 추가하지 않는다.
  로그는 **논리 호출의 최종 상태가 아닌 시도 이력**이다. 이 경우 마지막 시도 오류와 최종 예외가 다를 수 있다.
- HTTP 진행 중 취소는 `AI_503_5`, 서버 종료는 `AI_503_3`의 실패 로그를 가능한 범위에서 남긴다.
  이미 관측한 응답은 보존하며, 재시도 대기 중 취소에는 새 행을 만들지 않는다.
- 입장 한도/요청 준비 큐 초과로 거절한 요청은 아직 HTTP 시도가 없어 로그 행을 생성하지 않는다.

HTTP 200에서도 잘린 응답, 비어 있는 content, 유효하지 않은 JSON이면 `success=false`다.
이때 유효한 모델·사용량·종료 사유·추적 헤더는 독립적으로 읽어 남긴다.
모순된 토큰 수량은 사용량 전체를 미확정으로 남겨 잘못된 비용 계산을 막는다.
사용자 메시지, 프롬프트 본문, 생성 원문, API 키, 제공자 오류 본문은 저장하지 않는다.

## 비용 정책

비용은 **USD 추정액**이다. 기존 컬럼명 `actual_cost`를 유지했지만 제공자 청구서의 확정 금액은 아니다.
응답 모델명과 가격표 키가 정확히 일치할 때만 가격을 적용하며, 요청 모델명·별칭·라우팅 추측으로 대체하지 않는다.
현재 기본 가격표는 `liner-mark-1.1` 하나다. `LINER_MODEL`을 바꾸면 해당 응답 모델의 가격표도 등록해야 한다.

공식 가격표(2026-10-08 확인)는 입력 $1.00, 출력 $5.00, 캐시 입력 $0.10 / 100만 토큰이다.
[LINER 공식 가격표](https://liner.com/developers/docs/pricing)

```text
일반 입력 = inputTokens - cachedInputTokens
USD = (일반 입력 × 입력 단가
     + cachedInputTokens × 캐시 단가
     + outputTokens × 출력 단가) / 1,000,000
```

입력 1,000, 출력 200, 캐시 입력 250, 추론 50이면:
`(750 × 1 + 250 × 0.10 + 200 × 5) / 1,000,000 = $0.00177500`이다.
추론 50은 출력 200에 포함된 부분이므로 다시 더하지 않는다.
`BigDecimal`로 계산하고 최종 금액만 소수점 8자리 `HALF_UP`으로 반올림한다.

| cost_status | actual_cost | 의미 |
| --- | --- | --- |
| CALCULATED | 숫자(0 포함) | 모델 단가와 입력·출력·캐시 입력이 확인됨 |
| USAGE_MISSING | null | 단가는 있지만 필요한 수량 일부가 누락됨 |
| PRICE_MISSING | null | 응답 모델이 없거나 해당 모델 가격표가 없음 |
| LEGACY | 기존 값 유지 | 이전 형식의 기록. 새 계산 규칙으로 재계산하지 않음 |

`total_tokens`와 `reasoning_tokens`가 없어도 수식에 필요한 세 수량이 있으면 계산할 수 있다.
캐시 수량 누락을 0으로 추정하지 않는다. 생성 실패에도 유효한 사용량이 있으면 비용을 계산한다.
비용을 합산할 때는 미확정 행 수와 상태도 함께 확인해야 한다. null을 0으로 바꾸면 실제 비용을 과소 집계할 수 있다.

각 행에 통화, 가격표 모델·버전, 입력·출력·캐시 단가를 함께 저장한다.
설정 변경은 이후 행에 적용되며 과거 행의 단가와 금액을 바꾸지 않는다.

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| LINER_PRICING_VERSION | 2026-10-08 | 가격표 버전, 단가 변경 시 함께 갱신 |
| LINER_INPUT_USD_PER_MILLION | 1.00 | 일반 입력 100만 토큰당 USD |
| LINER_OUTPUT_USD_PER_MILLION | 5.00 | 출력 100만 토큰당 USD |
| LINER_CACHED_INPUT_USD_PER_MILLION | 0.10 | 캐시 입력 100만 토큰당 USD |

추가 모델은 `mongle.ai.pricing.models`에 등록한다. 점이 포함된 Map 키는 대괄호로 보존한다.

```yaml
mongle:
  ai:
    pricing:
      models:
        "[liner-mark-1.1]":
          version: "2026-10-08"
          input-usd-per-million: 1.00
          output-usd-per-million: 5.00
          cached-input-usd-per-million: 0.10
```

## 트랜잭션과 실패 처리

로그는 HTTP가 끝난 뒤 전용 저장 풀에 제출한다. JPA 작업 자체는 동기식이지만 요청·HTTP
완료 스레드는 DB 저장을 기다리지 않는다. `TransactionTemplate`의 `REQUIRES_NEW`를
실제 저장 스레드 안에서 실행해 로그만 독립적으로 커밋/롤백한다. 호출 스레드의 트랜잭션은 전파하지 않는다.
`record()`의 catch는 템플릿 바깥에 있어 계산·flush·커밋 오류까지 처리한다.
로그 저장 실패는 안전한 호출 ID·시도 번호·오류 타입으로 서버 경고를 남기고 생성 결과/원래 AI 예외를 유지한다.
저장 실패 때문에 생성 요청을 다시 보내지 않는다. 저장 큐가 가득 차거나 종료됐을 때도
경고 후 로그를 생략하고 원래 결과/AI 오류를 유지한다. 저장을 호출 스레드에서 대신 실행하지 않는다.
정상·외부 실패 Future는 저장 시도가 끝나거나 생략된 뒤 완료된다. 취소·서버 종료 Future는
즉시 종료하며 가능한 범위에서 시도 로그를 남긴다. 정상 서버 종료에서는 마지막 로그 제출 뒤,
DB 자원이 닫히기 전에 저장 풀을 최대 5초 기다린다. 시간 초과·종료 스레드 인터럽트에는 강제 정리하고
경고를 남긴다. 프로세스 강제 종료·DB 장애·큐 포화의 로그 유실까지 보장하는 재처리 큐는 이번 범위에 없다.

로그에 참조할 사용자는 이미 커밋된 사용자여야 한다. 호출부는 인증/소유권을 검증해야 한다.
새 사용자를 생성한 미커밋 트랜잭션 안에서 바로 LLM을 호출하면 독립 로그 트랜잭션이 해당 사용자를 볼 수 없다.
도메인에서는 사용자/입력 조회 → 트랜잭션 종료 → 외부 호출 → 별도 결과 저장으로 경계를 나눈다.

로그 SQL 트랜잭션 제한은 3초다. 풀 연결 획득 대기는 datasource의 `connection-timeout`을 따른다.
저장 큐 대기·저장 시간은 전체 결과 완료 지연에 추가되고, 재시도 전에 사용한 시간은 다음 HTTP 시도의 예산에서 차감된다.
`LINER_TOTAL_TIMEOUT`은 HTTP/재시도 예산이며 마지막 로그 저장까지 엄격하게 중단하는 전체 응답 기한은 아니다.
호출부의 결과 저장도 별도 작업 스레드에서 트랜잭션을 시작해야 한다. 이미 커밋된 사용자만 참조하고,
DB 풀 크기는 로그 저장 스레드와 다른 서비스의 사용량을 함께 고려해 정한다.
[Spring REQUIRES_NEW](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)

## DB 적용과 검증

- 로컬/테스트 H2는 새 엔티티 또는 초기 SQL로 테이블을 만든다.
- 신규 MySQL에는 최신 `db/schema-initial.sql`을 적용한다.
- 기존 MySQL에는 `db/migrations/20261008-ai-generation-attempt-logging.sql`을 검토 후 한 번 적용한다.
  앱은 이 파일을 자동 실행하지 않는다. MySQL 프로필의 `ddl-auto=validate`도 테이블을 변경하지 않는다.
- 기존 행의 비용/비교 비용/수량은 유지하고 `LEGACY`로 구분한다. 과거에 미확정 값을 0으로 저장했다면 원래 값은 복구할 수 없다.

`AiGenerationLoggingIntegrationTest`는 재시도 행 연결, 가격표 바인딩·금액·단가 스냅샷,
실패한 200 응답의 사용량, 부분 사용량/모델 누락/미등록 단가/실제 0, 타임아웃/키 누락,
실제 FK 저장 실패 시 반환값·원래 예외·외부 트랜잭션 유지, 외부 롤백 후 로그 보존을 검증한다.
`InitialSchemaValidationTest`와 이 테스트는 최신 초기 SQL로 생성한 H2 스키마에 JPA `validate`를 실행한다.

모의 HTTP와 H2 검증이며 실제 LINER 권한·과금·가용성 또는 MySQL 수동 마이그레이션 실행 결과를 증명하지 않는다.
