package com.mongle.backend.domain.ai.liner;

import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.dto.response.AiGenerationResult;
import com.mongle.backend.domain.ai.error.AiGatewayErrorCode;
import com.mongle.backend.domain.ai.error.AiGatewayException;
import com.mongle.backend.domain.ai.gateway.AiGateway;
import com.mongle.backend.domain.ai.liner.client.LinerClient;
import com.mongle.backend.domain.ai.liner.config.LinerProperties;
import com.mongle.backend.domain.ai.liner.mapper.LinerPayloadMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 공통 Gateway의 LINER 구현체. DB 트랜잭션 없이 통신·재시도·변환을 조율한다.
 * 생성 결과 저장이나 꿈 분석 성공 판정은 이 클래스의 책임에 포함하지 않는다.
 *
 * <p>읽는 순서: generate()에서 키 검사 → 요청 JSON 변환 → HTTP 호출 → 응답 변환을 따라간다.
 * 실패하면 재시도 여부를 확인하고, remaining()으로 남은 예산을 계산한 뒤 retryDelay()만큼 기다린다.
 * properties는 설정, client는 통신, mapper는 JSON 변환을 맡아 각 역할을 나눈다.</p>
 */
@Component
// 공통 AiGateway 계약의 LINER 구현이다. 호출부는 이 클래스 이름 대신 AiGateway에 의존할 수 있다.
public class LinerAiGateway implements AiGateway {
    // 모델·키·시간 제한 등 설정을 보관한다.
    private final LinerProperties properties;
    // HTTP 통신 담당 객체를 보관한다. Gateway는 이 객체에 실제 전송을 맡긴다.
    private final LinerClient client;
    // 요청·응답 변환 담당 객체를 보관한다.
    private final LinerPayloadMapper mapper;

    // Spring이 설정·통신·변환 담당 Bean을 생성자에 주입한다.
    public LinerAiGateway(LinerProperties properties, LinerClient client, LinerPayloadMapper mapper) {
        // 생성자로 주입받은 설정 객체를 필드에 저장해 다른 메서드에서도 사용한다.
        this.properties = properties;
        // 주입받은 HTTP 호출 담당 객체를 이 Gateway에 보관한다.
        this.client = client;
        // 주입받은 JSON 변환 담당 객체를 이 Gateway에 보관한다.
        this.mapper = mapper;
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }

    // 상위 인터페이스 또는 부모 타입에 정의된 메서드를 구현·재정의한다는 표시다.
    @Override
    // 공통 요청 하나를 받아 생성 결과를 반환하는 진입점이다. 외부 실패는 공통 AI 예외로 던진다.
    public AiGenerationResult generate(AiGenerationRequest request) {
        // request가 null이면 즉시 예외를 던진다. 제공자 통신 전에 내부 호출 계약을 확인한다.
        Objects.requireNonNull(request, "AI 생성 요청은 필수입니다.");
        // 설정된 키가 비어 있는지 확인한다. 키 없이 서버를 시작할 수 있지만 실제 호출은 허용하지 않는다.
        if (properties.apiKey().isBlank()) {
            // 인증 실패를 던진다. 뒤의 값은 요청 ID 없음·재시도 금지·대기 지시 없음·원인 예외 없음이다.
            throw new AiGatewayException(AiGatewayErrorCode.AUTHENTICATION_FAILED, null, false, null, null);
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
        // nanoTime은 시계 보정의 영향을 받지 않아 소요 시간과 전체 호출 예산 측정에 적합하다.
        // 호출 시작 시점을 나노초로 보관한다. 현재 날짜가 아니라 경과 시간을 재기 위한 값이다.
        long started = System.nanoTime();
        // 공통 요청을 LINER에 보낼 JSON 문자열로 변환한다.
        String payload = mapper.requestBody(request);
        // 최초 시도는 0이다. 매 재시도마다 1 증가하며 성공 return 또는 실패 throw로 반복을 끝낸다.
        for (int attempt = 0; ; attempt++) {
            // 처음 정한 전체 시간에서 지금까지 사용한 시간을 빼 남은 예산을 계산한다.
            Duration remaining = remaining(started);
            // 전체 예산이 이미 소진됐는지 검사한다.
            if (remaining.isNegative() || remaining.isZero()) {
                // 추가 HTTP 요청 없이 시간 초과로 끝낸다. 재시도는 금지한다.
                throw new AiGatewayException(AiGatewayErrorCode.TIMEOUT, null, false, null, null);
            // 위에서 시작한 코드 블록의 범위를 끝낸다.
            }
            // 남은 전체 예산이 한 번의 호출 제한보다 작은지 비교한다.
            Duration timeout = remaining.compareTo(properties.requestTimeout()) < 0
                    // 삼항 연산자다. 조건이 참이면 남은 예산, 거짓이면 한 번의 호출 제한을 선택한다.
                    ? remaining : properties.requestTimeout();
            // JSON과 이번 시도의 제한 시간을 넘겨 실제 HTTP 호출을 수행한다. var의 타입은 컴파일러가 추론한다.
            var response = client.complete(payload, timeout);
            // 제공자가 정상 HTTP 상태 200을 반환했는지 확인한다.
            if (response.statusCode() == 200) {
                // 응답을 공통 DTO로 읽고 즉시 반환한다. 스키마 존재 여부는 JSON 문법 검사에, started는 전체 소요 시간 계산에 쓴다.
                return mapper.result(response, request.outputSchema() != null, started);
            // 위에서 시작한 코드 블록의 범위를 끝낸다.
            }
            // 실패한 HTTP 상태·본문·헤더를 공통 오류와 재시도 힌트로 변환한다.
            var failure = mapper.failure(response);
            // 재시도 금지 또는 허용 횟수 소진이면 반복을 더 진행하지 않는다.
            if (!failure.isRetryable() || attempt >= properties.maxRetries()) {
                // 앞에서 만든 공통 AI 예외를 호출부로 던지고 현재 메서드를 끝낸다.
                throw failure;
            // 위에서 시작한 코드 블록의 범위를 끝낸다.
            }
            // 기본 대기·무작위 분산·제공자 Retry-After를 반영해 다음 시도 전 대기 시간을 정한다.
            Duration delay = retryDelay(failure);
            // Retry-After를 잘라서 일찍 재전송하지 않는다. 너무 긴 대기나 예산 부족이면 원래 실패를 반환한다.
            // 전체 예산에 대기와 다음 HTTP 호출을 모두 넣는다. 다음 시도는 남은 예산으로 제한된다.
            // 대기가 최대 허용 대기보다 길거나 남은 예산을 모두 소진하면 재시도를 포기한다.
            if (delay.compareTo(properties.maxRetryDelay()) > 0 || delay.compareTo(remaining(started)) >= 0) {
                // 앞에서 만든 공통 AI 예외를 호출부로 던지고 현재 메서드를 끝낸다.
                throw failure;
            // 위에서 시작한 코드 블록의 범위를 끝낸다.
            }
            // 실패할 수 있는 작업을 시작한다. 실패 유형에 따라 아래 catch에서 처리한다.
            try {
                // 현재 호출 스레드를 정해진 시간 동안 기다리게 한 뒤 다음 반복에서 재호출한다.
                Thread.sleep(delay);
            // 서버 종료 등의 이유로 대기 중인 스레드가 중단된 경우를 처리한다.
            } catch (InterruptedException exception) {
                // 잡은 중단 신호를 현재 스레드에 다시 표시해 상위 실행 코드도 중단을 알 수 있게 한다.
                Thread.currentThread().interrupt();
                // 중단된 작업을 외부 서비스 사용 불가로 분류해 호출부로 전달한다.
                throw new AiGatewayException(AiGatewayErrorCode.PROVIDER_UNAVAILABLE,
                        // 이전 실패의 추적 ID는 유지한다. 중단 이후 재시도는 금지하고 대기·원인 정보는 비운다.
                        failure.getRequestId(), false, null, null);
            // 위에서 시작한 코드 블록의 범위를 끝낸다.
            }
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }

    // 전체 호출 예산을 재시도마다 초기화하지 않고 최초 시작 시점 기준으로 계산하는 함수다.
    private Duration remaining(long started) {
        // 현재 시점에서 시작 시점을 뺀 경과 나노초를 전체 시간 제한에서 빼 반환한다.
        return properties.totalTimeout().minusNanos(System.nanoTime() - started);
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }

    // 재시도 전에 기다릴 시간을 계산하는 함수다. failure에서 제공자의 대기 지시를 읽는다.
    private Duration retryDelay(AiGatewayException failure) {
        // 최대 재시도가 한 번이므로 첫 backoff만 필요하다. 동시 요청이 같은 시점에 몰리지
        // 않도록 기본 대기 시간의 절반~전체 사이에 jitter를 준다. Retry-After보다 짧게 기다리지 않는다.
        // 설정된 기본 대기 시간을 난수 계산에 사용할 나노초 숫자로 바꾼다.
        long base = properties.retryBackoff().toNanos();
        // 기본 시간의 절반부터 전체까지 난수를 뽑아 대기로 만든다. 상한은 제외되므로 +1을 사용한다.
        Duration delay = Duration.ofNanos(ThreadLocalRandom.current().nextLong(base / 2, base + 1));
        // 제공자가 지정한 대기 시간이 있고, 계산한 무작위 대기보다 더 긴지 확인한다.
        return failure.getRetryAfter() != null && failure.getRetryAfter().compareTo(delay) > 0
                // 더 긴 제공자 대기가 있으면 그 값을, 없으면 계산한 대기를 반환한다. 제공자 지시보다 일찍 보내지 않는다.
                ? failure.getRetryAfter() : delay;
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }
// 위에서 시작한 코드 블록의 범위를 끝낸다.
}
