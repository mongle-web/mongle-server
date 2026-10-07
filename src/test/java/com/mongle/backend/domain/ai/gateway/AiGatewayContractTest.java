package com.mongle.backend.domain.ai.gateway;

import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.dto.request.AiJsonSchema;
import com.mongle.backend.domain.ai.dto.request.AiMessage;
import com.mongle.backend.domain.ai.dto.response.AiFinishReason;
import com.mongle.backend.domain.ai.dto.response.AiGenerationResult;
import com.mongle.backend.domain.ai.dto.response.AiTokenUsage;
import com.mongle.backend.domain.ai.error.AiGatewayErrorCode;
import com.mongle.backend.domain.ai.error.AiGatewayException;
import com.mongle.backend.domain.ai.entity.AiTaskType;
import com.mongle.backend.domain.ai.gateway.support.StubAiGateway;
import com.mongle.backend.global.error.BusinessException;
import com.mongle.backend.global.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static com.mongle.backend.domain.ai.support.AiGatewayTestAwait.await;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 호출부가 실제 제공자 대신 인터페이스와 스텁만으로 성공/실패를 다룰 수 있는지 확인한다.
 * 테스트 출력은 일반적인 value 필드로만 구성하여 꿈 도메인의 프롬프트나 스키마를 확정하지 않는다.
 */
class AiGatewayContractTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void callerReceivesContentAndMetadataThroughInterfaceWithoutApiKey() {
        AiGenerationRequest request = request();
        AiGenerationResult fixture = fixtureResult("fixture-request-1");
        StubAiGateway stub = new StubAiGateway().enqueueResult(fixture);
        AiGateway gateway = stub;

        AiGenerationResult result = await(gateway.generate(request));

        // 도메인은 자신이 정의한 스키마로 응답 본문을 해석한다. Gateway는 본문을 문자열로 전달한다.
        assertThat(mapper.readTree(result.content()).get("value").asString()).isEqualTo("ok");
        assertThat(result.modelName()).isEqualTo("fixture-model");
        assertThat(result.usage()).isEqualTo(new AiTokenUsage(100, 50, 150, 25, 30));
        assertThat(result.finishReason().type()).isEqualTo(AiFinishReason.Type.STOP);
        assertThat(result.requestId()).isEqualTo("fixture-request-1");
        assertThat(result.latencyMs()).isEqualTo(125L);
        assertThat(stub.receivedRequests()).containsExactly(request);
        assertThat(stub.receivedRequests().getFirst().outputSchema().schema().at("/properties/value/type").asString())
                .isEqualTo("string");
    }

    @Test
    void callerCanInspectFailureAndNoRetryHappensAutomatically() {
        AiGatewayException failure = new AiGatewayException(
                AiGatewayErrorCode.RATE_LIMITED, "fixture-failed-request", true, Duration.ofSeconds(2), null);
        StubAiGateway stub = new StubAiGateway()
                .enqueueFailure(failure)
                .enqueueResult(fixtureResult("fixture-request-2"));
        AiGateway gateway = stub;

        var failed = gateway.generate(request());
        assertThat(failed).isCompletedExceptionally();
        assertThatThrownBy(() -> await(failed)).isSameAs(failure);
        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(failure.getErrorCode()).isEqualTo(AiGatewayErrorCode.RATE_LIMITED);
        assertThat(failure.getRequestId()).isEqualTo("fixture-failed-request");
        assertThat(failure.isRetryable()).isTrue();
        assertThat(failure.getRetryAfter()).isEqualTo(Duration.ofSeconds(2));
        assertThat(stub.receivedRequests()).hasSize(1);

        // 두 번째 결과는 호출부가 명시적으로 다음 요청을 해야만 소비한다. 스텁은 정책을 실행하지 않는다.
        assertThat(await(gateway.generate(request())).requestId()).isEqualTo("fixture-request-2");
        assertThat(stub.receivedRequests()).hasSize(2);
    }

    @Test
    void retryHintCannotEnableUnsafeRetryForTimeoutOrPermanentFailures() {
        for (AiGatewayErrorCode code : List.of(
                AiGatewayErrorCode.TIMEOUT, AiGatewayErrorCode.AUTHENTICATION_FAILED,
                AiGatewayErrorCode.INSUFFICIENT_CREDIT, AiGatewayErrorCode.INVALID_REQUEST,
                AiGatewayErrorCode.INVALID_RESPONSE, AiGatewayErrorCode.INCOMPLETE_RESPONSE)) {
            AiGatewayException failure = new AiGatewayException(code, null, true, null, null);
            assertThat(failure.isRetryable()).as("retryable for %s", code).isFalse();
        }
        AiGatewayException rejectedByProvider = new AiGatewayException(
                AiGatewayErrorCode.PROVIDER_UNAVAILABLE, null, false, null, null);
        assertThat(rejectedByProvider.isRetryable()).isFalse();
    }

    @Test
    void providerAuthenticationFailureUsesExistingHandlerAndIsNotUserUnauthorized() {
        AiGatewayException failure = new AiGatewayException(
                AiGatewayErrorCode.AUTHENTICATION_FAILED, "fixture-auth-request", false, null, null);
        var response = new GlobalExceptionHandler().handleBusinessException(
                failure, new MockHttpServletRequest("POST", "/test-only-ai-caller"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().code()).isEqualTo("AI_502_2");
        assertThat(response.getBody().message()).isEqualTo(AiGatewayErrorCode.AUTHENTICATION_FAILED.getMessage());
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().errors()).isEmpty();
    }

    @Test
    void rejectsInvalidFailureMetadataAndDoesNotInventMissingFields() {
        AiGatewayException failure = new AiGatewayException(
                AiGatewayErrorCode.PROVIDER_UNAVAILABLE, null, false, null, null);
        assertThat(failure.getRequestId()).isNull();
        assertThat(failure.getRetryAfter()).isNull();
        assertThatThrownBy(() -> new AiGatewayException(
                AiGatewayErrorCode.RATE_LIMITED, " ", true, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("외부 요청 ID");
        assertThatThrownBy(() -> new AiGatewayException(
                AiGatewayErrorCode.RATE_LIMITED, null, true, Duration.ofMillis(-1), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("재시도 대기 시간");
    }

    @Test
    void stubRejectsUnpreparedCallsAndProtectsRecordedRequests() {
        StubAiGateway stub = new StubAiGateway().enqueueResult(fixtureResult("fixture-request"));
        await(stub.generate(request()));
        assertThatThrownBy(() -> stub.receivedRequests().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> await(stub.generate(request())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("준비된 응답이나 예외가 없습니다.");
    }

    private AiGenerationRequest request() {
        // 실제 도메인 프롬프트가 아닌 공통 계약 테스트용 입력/스키마다.
        AiJsonSchema schema = new AiJsonSchema("fixture_output", mapper.readTree("""
                {
                  "type":"object",
                  "properties":{"value":{"type":"string"}},
                  "required":["value"],
                  "additionalProperties":false
                }
                """), true);
        return new AiGenerationRequest(7L, AiTaskType.DREAM_STRUCTURE, "fixture-v1",
                List.of(new AiMessage(AiMessage.Role.USER, "공통 계약 테스트 입력")), schema);
    }

    private AiGenerationResult fixtureResult(String requestId) {
        return new AiGenerationResult("{\"value\":\"ok\"}", "fixture-model",
                new AiTokenUsage(100, 50, 150, 25, 30), new AiFinishReason("stop"), requestId, 125L);
    }
}
