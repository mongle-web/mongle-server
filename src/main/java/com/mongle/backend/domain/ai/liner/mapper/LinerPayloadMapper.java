package com.mongle.backend.domain.ai.liner.mapper;

import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.dto.response.AiFinishReason;
import com.mongle.backend.domain.ai.dto.response.AiGenerationResult;
import com.mongle.backend.domain.ai.dto.response.AiTokenUsage;
import com.mongle.backend.domain.ai.error.AiGatewayErrorCode;
import com.mongle.backend.domain.ai.error.AiGatewayException;
import com.mongle.backend.domain.ai.liner.config.LinerProperties;
import com.mongle.backend.domain.ai.liner.dto.LinerResponseMetadata;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * LINER JSON 형식과 프로젝트의 공통 계약 사이를 변환한다.
 * 알려지지 않은 추가 필드는 무시하되, 사용하는 필드의 잘못된 타입을 0·빈 문자열로
 * 바꾸지 않는다. 사용량의 누락과 실제 0을 구별해야 후속 비용 집계가 정확해진다.
 *
 * <p>requestBody()는 공통 요청 → LINER JSON 문자열, result()는 정상 응답 → 공통 결과,
 * failure()는 실패 응답 → 공통 예외로 변환한다. failure()가 반환한 예외는 Gateway가 실제로 던진다.
 * JsonNode는 JSON을 필드별로 탐색하는 트리이며, text()·integer()·detail()·usage()가 값을 읽는다.
 * require()는 구조 검증, retryAfter()는 대기 지시 해석, requestId()는 추적 헤더 조회를 담당한다.</p>
 */
@Component
// 공통 요청을 LINER JSON으로, LINER 응답을 공통 결과·예외로 바꾸는 클래스다.
public class LinerPayloadMapper {
    // 요청 JSON 생성과 직렬화에 사용할 공용 변환 도구를 보관한다.
    private final ObjectMapper json;
    // JSON 뒤의 불필요한 추가 데이터까지 검사할 읽기 전용 도구를 보관한다.
    private final ObjectReader strictJson;
    // 모델·키·시간 제한 등 설정을 보관한다.
    private final LinerProperties properties;

    // Spring이 JSON 도구와 LINER 설정 Bean을 생성자에 주입한다.
    public LinerPayloadMapper(ObjectMapper json, LinerProperties properties) {
        // Spring이 주입한 JSON 변환 도구를 보관한다.
        this.json = json;
        // JSON 뒤에 붙은 설명이나 두 번째 JSON을 첫 번째 결과만 읽고 성공 처리하지 않는다.
        // 첫 JSON 뒤에 설명이나 두 번째 JSON이 있으면 실패시키는 Reader를 만든다. 공용 Mapper의 설정은 바꾸지 않는다.
        this.strictJson = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        // 생성자로 주입받은 설정 객체를 필드에 저장해 다른 메서드에서도 사용한다.
        this.properties = properties;
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }

    // 공통 AI 요청을 LINER에 전송할 JSON 문자열로 만드는 함수다.
    public String requestBody(AiGenerationRequest request) {
        // 최상위 JSON 객체를 만든다. 아래에서 각 필드를 여기에 추가한다.
        var body = json.createObjectNode();
        // 설정된 공개 모델명을 JSON의 model 필드에 넣는다.
        body.put("model", properties.model());
        // SSE 조각 대신 완성된 응답 하나를 받도록 비스트리밍을 명시한다.
        body.put("stream", false);
        // 출력 토큰 상한을 LINER 필드명에 맞춰 넣는다.
        body.put("max_completion_tokens", properties.maxCompletionTokens());
        // 설정한 추론 강도를 요청에 넣는다.
        body.put("reasoning_effort", properties.reasoningEffort());
        // 본문 안에 messages 배열을 생성하고 배열을 수정할 참조를 받는다.
        var messages = body.putArray("messages");
        // 공통 메시지를 순서대로 순회하면서 messages 배열에 JSON 객체 하나씩 추가한다. -> 뒤가 각 메시지의 처리다.
        request.messages().forEach(message -> messages.addObject()
                // SYSTEM 같은 enum 이름을 system으로 바꿔 role 필드에 넣는다. Locale.ROOT는 서버 언어에 따른 변환 차이를 막는다.
                .put("role", message.role().name().toLowerCase(Locale.ROOT))
                // 메시지 원문을 content에 넣는다. trim하지 않아 공백·줄바꿈을 보존한다.
                .put("content", message.content()));
        // LINER가 어떤 형태로 생성해야 하는지 지정할 response_format 객체를 만든다.
        var format = body.putObject("response_format");
        // 호출부가 JSON Schema를 지정하지 않았는지 확인한다.
        if (request.outputSchema() == null) {
            // 스키마 없는 요청은 일반 텍스트 출력으로 보낸다.
            format.put("type", "text");
        // 출력 스키마가 전달된 경우의 요청 형식을 구성한다.
        } else {
            // 공통 스키마 객체를 지역 변수로 받아 이름·본문·strict 옵션을 읽는다.
            var schema = request.outputSchema();
            // LINER에 스키마를 따르는 JSON 출력을 요청한다.
            format.put("type", "json_schema");
            // response_format 안에 실제 스키마 설정을 담을 json_schema 객체를 만든다.
            var definition = format.putObject("json_schema");
            // 호출부가 지정한 스키마 이름을 넣는다.
            definition.put("name", schema.name());
            // 스키마 준수를 요청하는 strict 옵션을 그대로 넣는다. 도메인 업무 검증을 대신하지 않는다.
            definition.put("strict", schema.strict());
            // 스키마 JSON 트리 자체를 넣는다. set은 문자열화하지 않고 JSON 구조를 유지한다.
            definition.set("schema", schema.schema());
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
        // userId·taskType·promptVersion은 몽글의 호출 맥락이다. LINER가 사용하지 않는
        // 사용자 정보·메타데이터를 전달하거나 도메인의 프롬프트를 자동 추가하지 않는다.
        // 완성된 JSON 트리를 HTTP 본문으로 쓸 문자열로 직렬화한다.
        return json.writeValueAsString(body);
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }

    // 정상 HTTP 응답을 읽는다. expectsJson은 JSON 출력 요청 여부, startedNanos는 전체 소요 시간 계산 기준이다.
    public AiGenerationResult result(HttpResponse<String> response, boolean expectsJson, long startedNanos) {
        // 헤더에서 외부 요청 추적 ID를 읽는다. 결과와 파싱 실패 예외 모두 이 ID를 사용할 수 있다.
        String requestId = requestId(response);
        // 실패할 수 있는 작업을 시작한다. 실패 유형에 따라 아래 catch에서 처리한다.
        try {
            // HTTP 응답 본문 전체를 JSON 트리로 해석한다. 잘못된 JSON이면 예외가 발생한다.
            JsonNode root = strictJson.readTree(response.body());
            // 본문이 JSON 객체이고 error 필드를 포함하지 않는지 검사한다. 200이어도 오류 본문이면 성공이 아니다.
            require(root != null && root.isObject() && !root.hasNonNull("error"));
            // 생성 선택지 배열을 읽는다. path는 필드가 없을 때 null 대신 MissingNode를 반환한다.
            JsonNode choices = root.path("choices");
            // 이 계약은 결과 하나만 반환한다. 여러 선택지를 임의로 골라 버리지 않는다.
            // 선택지가 배열이며 정확히 하나인지 확인한다. 없는 결과나 여러 결과를 임의 처리하지 않는다.
            require(choices.isArray() && choices.size() == 1);
            // 선택지 배열의 첫 번째이자 유일한 원소를 가져온다. 배열 인덱스는 0부터 시작한다.
            JsonNode choice = choices.get(0);
            // 선택지 원소가 JSON 객체인지 검사한다.
            require(choice.isObject());
            // 선택지 안의 생성 메시지 객체를 가져온다.
            JsonNode message = choice.path("message");
            // 메시지 구조와 역할을 확인한다. 사용자 메시지 등 잘못된 응답은 거절한다.
            require(message.isObject() && "assistant".equals(text(message, "role")));
            // 종료 사유 원문을 공통 분류 객체로 감싼다. 값이 없으면 UNKNOWN으로 보존된다.
            var finish = new AiFinishReason(text(choice, "finish_reason"));
            // 도구 호출 필드가 있고 값도 null이 아닌지 확인한다.
            if (message.hasNonNull("tool_calls")) {
                // tool_calls가 제공됐다면 배열 타입인지 검사한다.
                require(message.path("tool_calls").isArray());
            // 위에서 시작한 코드 블록의 범위를 끝낸다.
            }
            // 출력 제한으로 생성이 잘려 끝난 경우인지 확인한다. 아래 조건 중 하나라도 맞으면 미완성 응답이다.
            if (finish.type() == AiFinishReason.Type.OUTPUT_LIMIT
                    // 도구 실행을 요구하며 끝난 경우도 이 텍스트 생성 계약에서는 완료 결과로 받지 않는다.
                    || finish.type() == AiFinishReason.Type.TOOL_CALLS
                    // 내용 제한으로 중단된 응답인지 확인한다.
                    || finish.type() == AiFinishReason.Type.CONTENT_FILTER
                    // 거절 내용 또는 구형 함수 호출 데이터가 제공됐는지 함께 검사한다.
                    || message.hasNonNull("refusal") || message.hasNonNull("function_call")
                    // 도구 호출 배열이 실제로 비어 있지 않은지도 검사한다. 종료 사유만 보고 도구 호출을 놓치지 않는다.
                    || (message.hasNonNull("tool_calls") && !message.path("tool_calls").isEmpty())) {
                // 미완성 응답으로 분류하고 추적 ID를 유지해 던진다. 같은 입력을 자동 재호출하지 않는다.
                throw new AiGatewayException(AiGatewayErrorCode.INCOMPLETE_RESPONSE, requestId, false, null, null);
            // 위에서 시작한 코드 블록의 범위를 끝낸다.
            }
            // 생성 원문 문자열을 읽는다. text 함수가 문자열 타입과 공백 여부를 검사한다.
            String content = text(message, "content");
            // 생성 원문 자체가 누락되지 않았는지 확인한다.
            require(content != null);
            // 호출부가 JSON Schema 출력을 요청한 경우에만 생성 원문의 JSON 문법을 추가 검사한다.
            if (expectsJson) {
                // 통신 계층에서는 JSON 문법만 확인한다. 원문을 재작성하지 않으며 스키마 전체의
                // 준수·도메인 DTO 변환·업무 규칙 검증은 호출하는 도메인 서비스에서 수행한다.
                // 생성 원문이 유효한 JSON 하나인지 검사한다. 스키마의 필드·업무 규칙 전체를 검사하는 코드는 아니다.
                require(strictJson.readTree(content) != null);
            // 위에서 시작한 코드 블록의 범위를 끝낸다.
            }
            // 생성 원문·응답 모델명·사용량으로 공통 결과를 만든다. 빠진 모델명이나 수량은 추정하지 않는다.
            return new AiGenerationResult(content, text(root, "model"), usage(root.get("usage")),
                    // 종료 사유·추적 ID·최초 호출부터 변환까지 걸린 시간을 밀리초 단위로 추가해 결과를 반환한다.
                    finish, requestId, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
        // 위에서 이미 공통 AI 예외로 분류한 실패가 들어오는 경로다.
        } catch (AiGatewayException exception) {
            // 이미 공통 AI 예외로 분류된 실패는 분류를 바꾸지 않고 그대로 호출부로 전달한다.
            throw exception;
        // JSON 문법·필드 타입·DTO 검증에서 발생한 그 밖의 실행 중 예외를 처리한다.
        } catch (RuntimeException exception) {
            // JSON 파싱 예외의 원본 본문에는 사용자 꿈이나 외부 오류 내용이 포함될 수 있다.
            // 원문과 파싱 예외는 공개 응답 및 공통 예외 로그로 전달하지 않는다.
            // 잘못된 외부 응답으로 통일한다. 원본 본문이 담길 수 있는 파싱 예외는 원인으로 첨부하지 않는다.
            throw new AiGatewayException(AiGatewayErrorCode.INVALID_RESPONSE, requestId, false, null, null);
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }

    /**
     * 생성 결과 검증과 별도로 관측 정보를 읽는다. 잘린 출력·잘못된 content도 과금될 수 있다.
     * 각 필드를 독립적으로 검사해 잘못된 사용량 때문에 유효한 모델명까지 버리지 않는다.
     * 생성 성공 여부는 result()가 판단하며, 이 함수는 그 판단을 완화하지 않는다.
     */
    public LinerResponseMetadata metadata(HttpResponse<String> response) {
        String requestId = requestId(response);
        JsonNode root;
        try {
            root = strictJson.readTree(response.body());
            if (root == null || !root.isObject()) {
                return new LinerResponseMetadata(null, AiTokenUsage.unknown(), null, requestId);
            }
        } catch (RuntimeException ignored) {
            // JSON 본문을 읽지 못해도 수신한 추적 헤더는 보존한다. 예외 본문을 로그로 전파하지 않는다.
            return new LinerResponseMetadata(null, AiTokenUsage.unknown(), null, requestId);
        }
        String model = null;
        String finish = null;
        AiTokenUsage tokens = AiTokenUsage.unknown();
        try { model = text(root, "model"); } catch (RuntimeException ignored) { /* 잘못된 모델 필드는 미확정이다. */ }
        try { tokens = usage(root.get("usage")); } catch (RuntimeException ignored) { /* 모순된 수량으로 비용을 계산하지 않는다. */ }
        try {
            var choices = root.path("choices");
            if (choices.isArray() && choices.size() == 1 && choices.get(0).isObject()) {
                finish = text(choices.get(0), "finish_reason");
            }
        } catch (RuntimeException ignored) { /* 잘못된 종료 사유만 미확정으로 남긴다. */ }
        return new LinerResponseMetadata(model, tokens, finish, requestId);
    }

    // 응답의 usage 객체를 프로젝트의 토큰 사용량 DTO로 바꾸는 함수다.
    private AiTokenUsage usage(JsonNode usage) {
        // 필드 누락인 Java null과 JSON의 명시적인 null을 모두 미제공으로 처리한다.
        if (usage == null || usage.isNull()) {
            // 사용량 객체는 반환하되 각 수량은 null로 둔다. 실제 사용량 0으로 취급하지 않는다.
            return AiTokenUsage.unknown();
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
        // usage가 제공됐다면 JSON 객체여야 한다.
        require(usage.isObject());
        // 입력·출력 토큰을 읽어 공통 DTO를 만든다. DTO 생성자에서도 음수·수량 관계를 검증한다.
        return new AiTokenUsage(integer(usage, "prompt_tokens"), integer(usage, "completion_tokens"),
                // 보고된 전체 합계와 입력 세부 정보의 캐시 토큰을 읽는다. 합계를 직접 계산하지 않는다.
                integer(usage, "total_tokens"), detail(usage, "prompt_tokens_details", "cached_tokens"),
                // 출력 세부 정보의 추론 토큰도 읽어 DTO를 반환한다. 추론 토큰을 전체 출력에 다시 더하지 않는다.
                detail(usage, "completion_tokens_details", "reasoning_tokens"));
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }

    // 세부 객체 field 안에서 수량 필드 count를 읽는 공통 함수다.
    private Integer detail(JsonNode usage, String field, String count) {
        // 예를 들어 prompt_tokens_details 객체를 가져온다. get은 필드가 없으면 Java null이다.
        JsonNode details = usage.get(field);
        // 세부 정보가 생략되거나 JSON null인지 확인한다.
        if (details == null || details.isNull()) {
            // 값을 알 수 없다는 뜻으로 null을 반환한다. 0 또는 성공으로 추정하지 않는다.
            return null;
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
        // 세부 정보가 제공됐다면 객체 타입인지 확인한다.
        require(details.isObject());
        // 검사한 세부 객체 안의 cached_tokens 같은 정수 필드를 읽어 반환한다.
        return integer(details, count);
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }

    // 선택적인 정수 필드를 안전하게 읽는 함수다. Integer라서 미제공을 null로 표현할 수 있다.
    private Integer integer(JsonNode object, String field) {
        // 지정한 필드의 JSON 값을 가져온다. 필드가 없으면 Java null이다.
        JsonNode value = object.get(field);
        // 필드가 없거나 JSON null이면 아래에서 미제공을 반환한다.
        if (value == null || value.isNull()) {
            // 값을 알 수 없다는 뜻으로 null을 반환한다. 0 또는 성공으로 추정하지 않는다.
            return null;
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
        // 소수·숫자 문자열·int 범위 초과 수를 거절한다. 기본 0으로 조용히 변환하지 않는다.
        require(value.isIntegralNumber() && value.canConvertToInt());
        // 검사한 JSON 정수 값을 Java int로 읽고 Integer 반환값으로 전달한다.
        return value.intValue();
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }

    // 선택적인 문자열 필드를 읽는다. 없는 값은 null, 있는 값은 비어 있지 않은 문자열이어야 한다.
    private String text(JsonNode object, String field) {
        // 지정한 필드의 JSON 값을 가져온다. 필드가 없으면 Java null이다.
        JsonNode value = object.get(field);
        // 필드가 없거나 JSON null이면 아래에서 미제공을 반환한다.
        if (value == null || value.isNull()) {
            // 값을 알 수 없다는 뜻으로 null을 반환한다. 0 또는 성공으로 추정하지 않는다.
            return null;
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
        // 숫자·객체 등 문자열이 아닌 값과 공백뿐인 문자열을 거절한다.
        require(value.isString() && !value.asString().isBlank());
        // 검사한 문자열을 원문 그대로 반환한다. trim하지 않는다.
        return value.asString();
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }

    // 응답 형식 검사를 반복해서 사용할 보조 함수다. condition은 통과해야 할 조건이다.
    private void require(boolean condition) {
        // 전달된 조건이 거짓이면 형식 오류를 발생시킨다.
        if (!condition) {
            // result의 catch에서 INVALID_RESPONSE로 변환할 내부 검증 예외를 던진다.
            throw new IllegalArgumentException("LINER 응답 형식이 올바르지 않습니다.");
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }

    /** HTTP 상태와 제공자의 재시도 지시를 함께 확인한다. 외부 message는 전달하지 않는다. */
    // 실패한 HTTP 응답을 공통 AI 예외 객체로 바꾼다. 실제 throw와 재시도 결정은 Gateway에서 한다.
    public AiGatewayException failure(HttpResponse<String> response) {
        // HTTP 상태별로 공통 오류 enum을 선택하는 switch 표현식을 시작한다.
        var code = switch (response.statusCode()) {
            // 잘못된 요청·주소·본문 크기·검증 실패를 서버가 보낸 AI 요청의 오류로 분류한다.
            case 400, 404, 413, 422 -> AiGatewayErrorCode.INVALID_REQUEST;
            // 제공자의 API 인증·권한 거절을 AI 서비스 인증 실패로 분류한다. 사용자 로그인 401과는 별개다.
            case 401, 403 -> AiGatewayErrorCode.AUTHENTICATION_FAILED;
            // 제공자 크레딧 부족을 분류한다. 같은 요청을 다시 보내도 해결되지 않는다.
            case 402 -> AiGatewayErrorCode.INSUFFICIENT_CREDIT;
            // 제공자 요청 제한 초과로 분류한다. 아래에서 실제 재시도 지시를 추가 확인한다.
            case 429 -> AiGatewayErrorCode.RATE_LIMITED;
            // 제공자 측 시간 초과 응답을 공통 시간 초과로 분류한다.
            case 408, 504 -> AiGatewayErrorCode.TIMEOUT;
            // 제공자·상위 서비스의 일시적 실패를 서비스 사용 불가로 분류한다.
            case 500, 502, 503 -> AiGatewayErrorCode.PROVIDER_UNAVAILABLE;
            // 명시적으로 다루지 않는 상태나 리다이렉트는 정상 결과로 취급하지 않는다.
            default -> AiGatewayErrorCode.INVALID_RESPONSE;
        // switch 표현식을 마치고 선택된 값을 앞의 변수에 대입한다.
        };
        // 공통 오류 enum이 정한 기본 재시도 후보 여부를 읽는다. true여도 아래 조건에 따라 금지될 수 있다.
        boolean retryable = code.isRetryCandidate();
        // 실패할 수 있는 작업을 시작한다. 실패 유형에 따라 아래 catch에서 처리한다.
        try {
            // HTTP 응답 본문 전체를 JSON 트리로 해석한다. 잘못된 JSON이면 예외가 발생한다.
            JsonNode root = strictJson.readTree(response.body());
            // 오류 본문에서 error.retryable을 읽는다. 본문이 없으면 지시도 없는 것으로 처리한다.
            JsonNode directive = root == null ? null : root.path("error").get("retryable");
            // 제공자가 재시도 필드를 실제로 넣은 경우에만 기본 정책을 좁힌다.
            if (directive != null) {
                // false가 있으면 상태 코드보다 우선한다. 잘못된 타입도 재시도를 허용하지 않는다.
                // 기본 후보이고 제공자 값도 boolean true일 때만 허용한다. false·잘못된 타입은 금지한다.
                retryable = retryable && directive.isBoolean() && directive.booleanValue();
            // 위에서 시작한 코드 블록의 범위를 끝낸다.
            }
        // JSON이 아닌 본문·잘못된 헤더 값 등의 해석 실패를 잡는다. 아래 각 함수의 대체 처리를 사용한다.
        } catch (RuntimeException ignored) {
            // 프록시의 HTML 오류 등 JSON을 읽지 못하면 명세에 있는 HTTP 상태의 정책을 사용한다.
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
        // 재시도 전에 기다릴 시간을 알려 주는 첫 헤더 값을 읽고, 없으면 null로 둔다.
        String header = response.headers().firstValue("Retry-After").orElse(null);
        // 초 숫자 또는 HTTP 날짜를 Duration으로 해석한다. 읽을 수 없으면 null이다.
        Duration retryAfter = retryAfter(header);
        // 헤더는 있었는데 해석에 실패했는지 구분한다. 헤더가 없는 경우와 정책이 다르다.
        if (header != null && retryAfter == null) {
            // 해석할 수 없는 대기 지시를 무시하고 일찍 재전송하지 않는다.
            // 대기 지시를 모르는 상태에서 일찍 재전송하지 않도록 재시도를 금지한다.
            retryable = false;
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
        // 공통 코드·추적 ID·재시도 여부·대기 지시를 담는다. 제공자의 민감한 오류 본문은 넣지 않는다.
        return new AiGatewayException(code, requestId(response), retryable, retryAfter, null);
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }

    // Retry-After 문자열을 실제 대기 시간으로 바꾸는 보조 함수다.
    private Duration retryAfter(String header) {
        // 헤더가 아예 없으면 해석할 대기 지시가 없다.
        if (header == null) {
            // 값을 알 수 없다는 뜻으로 null을 반환한다. 0 또는 성공으로 추정하지 않는다.
            return null;
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
        // 실패할 수 있는 작업을 시작한다. 실패 유형에 따라 아래 catch에서 처리한다.
        try {
            // 헤더 앞뒤의 공백을 제거해 숫자·날짜 파싱을 준비한다.
            String value = header.strip();
            // 문자열 전체가 숫자로만 이루어졌는지 확인한다. 숫자면 초 단위 대기다.
            if (value.matches("[0-9]+")) {
                // 초 숫자를 long으로 읽고 해당 초만큼의 Duration을 반환한다.
                return Duration.ofSeconds(Long.parseLong(value));
            // 위에서 시작한 코드 블록의 범위를 끝낸다.
            }
            // 현재 시각부터 제공자가 지정한 날짜까지의 시간 차이를 계산하기 시작한다.
            Duration remaining = Duration.between(Instant.now(),
                    // HTTP 날짜 형식으로 문자열을 읽고 Instant로 변환해 시간 차이 계산을 마친다.
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            // 지정한 날짜가 이미 지났으면 0, 미래라면 남은 시간을 반환한다.
            return remaining.isNegative() ? Duration.ZERO : remaining;
        // JSON이 아닌 본문·잘못된 헤더 값 등의 해석 실패를 잡는다. 아래 각 함수의 대체 처리를 사용한다.
        } catch (RuntimeException ignored) {
            // 값을 알 수 없다는 뜻으로 null을 반환한다. 0 또는 성공으로 추정하지 않는다.
            return null;
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }

    /** 추적 ID는 공식 x-request-id 헤더를 사용한다. 본문의 chat completion id로 대체하지 않는다. */
    // 추적용 헤더만 읽는 함수다. 본문의 completion id와 혼동하지 않는다.
    private String requestId(HttpResponse<String> response) {
        // 첫 추적 헤더를 읽고 공백뿐인 값은 버린다. 유효한 값이 없으면 null을 반환한다.
        return response.headers().firstValue("x-request-id").filter(value -> !value.isBlank()).orElse(null);
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }
// 위에서 시작한 코드 블록의 범위를 끝낸다.
}
