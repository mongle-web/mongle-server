   # LLM Gateway 공통 호출 계약

첫 번째 Gateway 이슈에서 구현한 내부 서비스 계약이다. 도메인 서비스는 메시지와 원하는
출력 스키마를 전달하고, Gateway는 생성 내용과 호출 메타데이터를 반환한다.

첫 번째 이슈는 인터페이스, DTO, 공통 오류와 테스트용 Gateway를 구현했다.
두 번째 이슈에서 LINER HTTP 통신, API 키 설정, 모델 선택과 재시도를 구현했으며
설정·실행 방법은 [LINER 연동 문서](liner-gateway.md)를 참고한다. 호출 로그 저장과 비용 계산은
세 번째 이슈, 꿈 구조화·서사화의 프롬프트·실제 출력 스키마·업무 검증은 각 도메인 이슈에서 작성한다.

## 패키지 구성과 코드 읽는 순서

AI 기능은 기존 `domain.ai`에 모으고, 내부는 역할에 따라 나눈다.

```text
domain.ai
├── gateway
│   └── AiGateway
├── dto
│   ├── request
│   │   ├── AiGenerationRequest
│   │   ├── AiMessage
│   │   └── AiJsonSchema
│   └── response
│       ├── AiGenerationResult
│       ├── AiTokenUsage
│       └── AiFinishReason
├── error
│   ├── AiGatewayErrorCode
│   └── AiGatewayException
├── entity
│   ├── AiGenerationLog
│   └── AiTaskType
├── repository
│   └── AiGenerationLogRepository
└── liner                     # LinerAiGateway, config, client, mapper
```

다음 순서로 읽으면 호출 계약부터 실제 사용 예시까지 따라갈 수 있다.

1. [`AiGateway`](../src/main/java/com/mongle/backend/domain/ai/gateway/AiGateway.java):
   `generate(request)`가 무엇을 받고 무엇을 반환하는지 확인한다.
2. [`AiGenerationRequest`](../src/main/java/com/mongle/backend/domain/ai/dto/request/AiGenerationRequest.java):
   사용자, 작업, 버전, 메시지와 선택적인 스키마를 확인한다.
   같은 패키지의 `AiMessage`, `AiJsonSchema`를 읽어 요청의 세부 형식과 복사 이유를 확인한다.
3. [`AiGenerationResult`](../src/main/java/com/mongle/backend/domain/ai/dto/response/AiGenerationResult.java):
   생성 원문과 모델명·사용량·종료 사유·요청 ID·소요 시간의 구분을 확인한다.
   같은 패키지의 `AiTokenUsage`, `AiFinishReason`에서 누락 처리와 분류 규칙을 확인한다.
4. [`AiGatewayErrorCode`](../src/main/java/com/mongle/backend/domain/ai/error/AiGatewayErrorCode.java)와
   [`AiGatewayException`](../src/main/java/com/mongle/backend/domain/ai/error/AiGatewayException.java):
   실패 분류, 사용자에게 전달할 상태, 재시도 힌트, 기존 공통 예외 처리와의 연결을 확인한다.
5. [`StubAiGateway`](../src/test/java/com/mongle/backend/domain/ai/gateway/support/StubAiGateway.java):
   준비된 결과 또는 실패를 반환하고 요청을 기록하는 테스트용 구현을 확인한다.
6. [`AiGatewayContractTest`](../src/test/java/com/mongle/backend/domain/ai/gateway/AiGatewayContractTest.java):
   `callerReceivesContentAndMetadataThroughInterfaceWithoutApiKey()`부터 읽어
   요청 생성 → 인터페이스 호출 → 응답 사용의 실행 예시를 확인한다.

DTO 제약을 더 확인하려면 `src/test/java/com/mongle/backend/domain/ai/dto/AiGatewayDtoTest.java`를 읽는다.
로그 엔티티는 초기 세팅에 존재하지만 이번 호출 계약과 아직 연결되지 않았다.

## 호출 흐름과 책임

```text
도메인 서비스
  1. 사용자와 소유권 확인
  2. 작업별 프롬프트·메시지·선택적인 JSON Schema 구성
  3. AiGenerationRequest 생성
       ↓
  AiGateway.generate(request)
       ↓
실제 제공자 어댑터 LinerAiGateway / StubAiGateway (테스트)
       ↓
  AiGenerationResult 또는 AiGatewayException
       ↓
도메인 서비스
  4. 생성 본문을 도메인 DTO로 해석하고 업무 규칙 검증
  5. 엔티티 정적 팩터리로 생성한 뒤 저장
```

`AiGateway`는 동기식 비스트리밍 텍스트/JSON 생성 계약이다. 이 인터페이스를 구현한
제공자 어댑터를 나중에 교체해도 도메인 서비스의 호출 형식은 유지할 수 있다.
공통 DTO는 HTTP API나 LINER 원본 응답에 그대로 노출할 형식이 아니다.
외부 호출을 기다리는 동안 DB 트랜잭션을 유지하지 않도록 도메인 서비스에서 경계를 나눈다.

## 요청

| 필드 | 필수 여부 | 의미 |
| --- | --- | --- |
| `userId` | 필수 | 양수 사용자 ID. 서버에서 확인한 호출 주체이며 인증/소유권 검증을 대신하지 않는다. |
| `taskType` | 필수 | 기존 `AiTaskType`. 구조화·서사화 등 작업 구분 |
| `promptVersion` | 필수 | 도메인이 관리하는 비어 있지 않은 프롬프트 버전 |
| `messages` | 필수 | 비어 있지 않은 메시지 목록. `SYSTEM`, `USER`, `ASSISTANT` 역할 및 텍스트 |
| `outputSchema` | 선택 | `AiJsonSchema`. null이면 일반 텍스트 출력, 값이 있으면 해당 스키마 출력 요청 |

`AiMessage`는 역할과 비어 있지 않은 내용을 갖는다. 메시지의 공백과 줄바꿈을 유지하고
목록은 복사하여 외부 수정이 요청에 영향을 주지 않게 한다. 시스템 지시를 Gateway가
자동으로 추가하지 않으며, 실제 도메인 프롬프트는 호출부가 작성한다.

`AiJsonSchema`는 이름, JSON 객체 형태의 스키마 본문, `strict` 요청 옵션을 갖는다.
가변 JSON 트리는 생성 시와 조회 시 모두 깊은 복사로 보호한다. 객체 형태만 확인하며
JSON Schema 문법 전체, LINER 지원 범위, 실제 출력의 스키마 준수를 이 DTO가 보장하지 않는다.

잘못된 내부 요청은 생성자에서 `IllegalArgumentException` 또는 `NullPointerException`으로
즉시 거절한다. 이는 제공자 호출 실패와 구분되는 서버 코드의 계약 위반이다.
실제 사용자 HTTP 입력 검증은 각 도메인의 요청 DTO와 Controller에서 수행한다.
모델명·API 키·타임아웃·재시도 정책은 공통 요청에 넣지 않고 실제 어댑터 설정으로 관리한다.

## 응답

| 필드 | 필수 여부 | 의미 |
| --- | --- | --- |
| `content` | 필수 | 비어 있지 않은 생성 원문. Gateway 공통 DTO가 꿈 데이터로 변환하지 않는다. |
| `modelName` | 선택 | 제공자가 반환한 모델명. 미제공이면 null |
| `usage` | 필수 객체 | 토큰 수별 null을 허용하는 `AiTokenUsage` |
| `finishReason` | 필수 객체 | 원본 종료 사유와 공통 분류를 제공하는 `AiFinishReason` |
| `requestId` | 선택 | 제공자의 추적 ID. 미제공이면 null |
| `latencyMs` | 필수 | Gateway 시작부터 반환까지 걸린 0 이상 밀리초. 이후 재시도가 생기면 전체 소요 시간 |

제공자 어댑터는 누락된 모델명을 요청 모델명으로 채우지 않는다. LINER의 공개 모델명이
반환되어도 내부에서 실제 라우팅된 모델의 이름을 확인했다고 해석하지 않는다.
원본 응답의 `id`와 추적 헤더의 요청 ID는 서로 다를 수 있으므로 실제 어댑터에서
`requestId`의 출처를 정한다.

### 사용량의 0과 미제공

`AiTokenUsage`의 `inputTokens`, `outputTokens`, `totalTokens`, `cachedInputTokens`,
`reasoningTokens`는 모두 `Integer`이며, **null은 미제공, 0은 제공자가 보고한 실제 0**이다.
usage 전체가 없으면 `AiTokenUsage.unknown()`을 사용한다. 부분 제공도 그대로 유지하며
입력/출력 값으로 합계를 계산할 수 있더라도 미제공된 합계를 채우지 않는다.

음수는 거절한다. 전체 입력이 알려졌다면 캐시 입력이 이를 넘을 수 없고, 전체 출력이
알려졌다면 추론 토큰이 이를 넘을 수 없다. 입력·출력·합계가 모두 알려졌을 때는 합계가
일치해야 한다. 캐시와 추론 토큰은 부분 수량이므로 전체 토큰 수에 다시 더하지 않는다.

현재 `AiGenerationLog` 엔티티는 일부 토큰 수와 비용이 필수다. 이번 계약을 기존 엔티티에
직접 저장하지 않는다. 세 번째 이슈에서 미제공 사용량·비용을 보존하는 로그 모델과
개별 외부 호출 시도 추적 방식을 정해야 한다.

### 종료 사유

| 제공자 원본 값 | 공통 분류 |
| --- | --- |
| `stop` | `STOP` |
| `length` | `OUTPUT_LIMIT` |
| `tool_calls` | `TOOL_CALLS` |
| `content_filter` | `CONTENT_FILTER` |
| 그 밖의 값 | `OTHER` — 원본 문자열 보존 |
| 미제공 | `UNKNOWN` — 원본 null |

이 분류는 호환 계약이며 LINER가 모든 값을 반환한다는 의미는 아니다.
LINER 공식 명세에는 `choices[].finish_reason`의 `stop`과 `tool_calls` 예시가 있다.
미제공을 `stop`으로 추정하지 않는다. `STOP`도 생성이 끝났다는 정보일 뿐 꿈 분석 성공을
보장하지 않는다. 실제 어댑터는 빈 응답·잘린 응답·지원하지 않는 도구 호출 등을 공통 오류로
처리하고, 도메인은 정상 반환된 내용도 업무 규칙에 따라 검증한다.

## 오류 계약

`AiGatewayException`은 기존 `BusinessException`을 상속한다.
따라서 기존 `GlobalExceptionHandler`가 오류 코드에 정한 HTTP 상태와 공통 `ApiResponse`를
사용한다. 제공자 원본 상태/본문을 사용자에게 그대로 전달하지 않는다.

오류 코드 형식은 `AI_HTTP상태_순번`이다. 같은 HTTP 상태 안에서 순번을 부여하고,
이미 공개한 번호는 재정렬하거나 다른 오류에 재사용하지 않는다.

| 오류 | 응답 코드 | 몽글의 HTTP 상태 | 재시도 후보 |
| --- | --- | --- | --- |
| `INVALID_REQUEST` | `AI_502_1` | 502 | 아니오 |
| `AUTHENTICATION_FAILED` | `AI_502_2` | 502 | 아니오 |
| `INSUFFICIENT_CREDIT` | `AI_503_1` | 503 | 아니오 |
| `RATE_LIMITED` | `AI_503_2` | 503 | 예 |
| `PROVIDER_UNAVAILABLE` | `AI_503_3` | 503 | 예 |
| `TIMEOUT` | `AI_504_1` | 504 | 아니오 |
| `INVALID_RESPONSE` | `AI_502_3` | 502 | 아니오 |
| `INCOMPLETE_RESPONSE` | `AI_502_4` | 502 | 아니오 |

여기서 `INVALID_REQUEST`는 제공자로 보낸 서버 요청의 거절이다. 사용자의 HTTP 입력 오류는
기존 공통 400 검증과 별개다. 제공자 API 키 오류를 사용자의 로그인 오류(401)로 내보내지
않는다. 제공자 제한 초과도 몽글 사용자의 요청 횟수 제한과 구분하여 503으로 표현한다.
실제 LINER HTTP 상태와 본문의 오류를 이 분류로 변환하는 코드는 두 번째 이슈의 LinerPayloadMapper에 있다.

예외에는 요청 ID, 재시도 가능 여부, 선택적인 `Duration retryAfter`, 원인을 담는다.
재시도 후보 유형이면서 실제 응답 맥락도 허용해야 `retryable=true`가 된다. 이 값은 자동
재시도를 실행하지 않는다. 타임아웃은 제공자가 생성/과금을 이미 완료했을 가능성이 있어
LINER 어댑터도 무조건 다시 호출하지 않도록 후보에서 제외했다.

## 테스트에서 사용하기

아래는 실제 꿈 프롬프트/스키마가 아닌 공통 계약 사용 예시다.

```java
import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.dto.request.AiJsonSchema;
import com.mongle.backend.domain.ai.dto.request.AiMessage;
import com.mongle.backend.domain.ai.dto.response.AiFinishReason;
import com.mongle.backend.domain.ai.dto.response.AiGenerationResult;
import com.mongle.backend.domain.ai.dto.response.AiTokenUsage;
import com.mongle.backend.domain.ai.entity.AiTaskType;
import com.mongle.backend.domain.ai.gateway.AiGateway;
import com.mongle.backend.domain.ai.gateway.support.StubAiGateway;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

JsonMapper mapper = JsonMapper.builder().build();
AiJsonSchema schema = new AiJsonSchema("example_output", mapper.readTree("""
        {
          "type": "object",
          "properties": {"value": {"type": "string"}},
          "required": ["value"],
          "additionalProperties": false
        }
        """), true);

AiGenerationRequest request = new AiGenerationRequest(
        7L, AiTaskType.DREAM_STRUCTURE, "example-v1",
        List.of(new AiMessage(AiMessage.Role.USER, "공통 계약 테스트 입력")), schema);

AiGenerationResult fixture = new AiGenerationResult(
        "{\"value\":\"ok\"}", "fixture-model",
        new AiTokenUsage(100, 50, 150, 25, 30),
        new AiFinishReason("stop"), "fixture-request", 125L);

StubAiGateway stub = new StubAiGateway().enqueueResult(fixture);
AiGateway gateway = stub;
AiGenerationResult result = gateway.generate(request);

// 호출부가 내용 해석과 업무 검증을 맡는다. 모델명/사용량은 본문과 분리되어 있다.
JsonNode output = mapper.readTree(result.content());
String value = output.get("value").asString();
```

`StubAiGateway`는 `src/test`에 있으며 운영 Bean으로 등록되지 않는다. 도메인 테스트에서
직접 생성하거나 테스트 전용 설정에 등록한다. `enqueueResult`와 `enqueueFailure`로
응답을 순서대로 준비하고 `receivedRequests()`로 전달한 요청을 확인한다.
두 번째 이슈의 LinerAiGateway는 운영 Bean으로 등록되어 AiGateway 주입으로 사용할 수 있다.
HTTP Controller와 공개 AI 엔드포인트는 도메인 기능에서 추가한다.

계약 테스트는 키/외부 네트워크 없이 요청 목록·스키마 복사, 사용량 누락/0/부분 제공,
종료 사유 누락/새 값, 공통 예외 및 기존 핸들러 연결을 검증한다.
기존 H2 영속성·스키마·Swagger 테스트도 함께 실행한다. 모의 HTTP 통신 검증 범위는 LINER 연동 문서를 참고한다.

## 참고

- [LINER Chat Completions 명세](https://liner.com/developers/docs/liner-model-api-chat-completions)
- [도메인 초기 세팅](domain-setup.md)
