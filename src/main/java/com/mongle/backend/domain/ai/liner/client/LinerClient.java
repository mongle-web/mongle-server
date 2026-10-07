package com.mongle.backend.domain.ai.liner.client;

import com.mongle.backend.domain.ai.error.AiGatewayErrorCode;
import com.mongle.backend.domain.ai.error.AiGatewayException;
import com.mongle.backend.domain.ai.liner.config.LinerProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * HTTP 요청 한 번만 담당한다. 응답 해석과 재시도 여부 판단은 상위 Gateway가 맡는다.
 * 내부에서는 비동기 클라이언트를 사용하되 호출부에는 동기식 계약을 제공한다.
 *
 * <p>complete()의 payload는 전송할 JSON 문자열이고 timeout은 이번 시도에 쓸 제한 시간이다.
 * sendAsync()로 작업을 시작하면 CompletableFuture를 받는다. 이는 나중에 도착할 결과를
 * 기다릴 수 있는 객체다. get()에서 제한 시간 동안 기다려 실제 HttpResponse를 반환한다.
 * 통신 실패를 공통 예외로 바꾸지만 HTTP 상태별 분류와 재시도는 다른 클래스가 담당한다.</p>
 */
// Spring이 이 클래스의 객체를 Bean으로 등록해 다른 클래스에 주입할 수 있게 한다.
@Component
// HTTP 시도 한 번을 담당한다. 재시도 반복이나 JSON 응답 해석은 여기서 하지 않는다.
public class LinerClient {
    // 연결 풀을 재사용할 HTTP 클라이언트를 보관한다.
    private final HttpClient http;
    // 모델·키·시간 제한 등 설정을 보관한다. final은 이 참조를 다시 대입하지 않는다는 뜻이다.
    private final LinerProperties properties;

    // 이름이 linerHttpClient인 Bean을 명시적으로 선택해 설정 Bean과 함께 주입받는다.
    public LinerClient(@Qualifier("linerHttpClient") HttpClient http, LinerProperties properties) {
        // 주입받은 HTTP 클라이언트를 보관한다. 호출마다 새 클라이언트를 만들지 않는다.
        this.http = http;
        // 생성자로 주입받은 설정 객체를 필드에 저장해 다른 메서드에서도 사용한다.
        this.properties = properties;
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }

    // 전송할 JSON과 이번 시도의 제한 시간을 받아 문자열 본문을 담은 HTTP 응답을 반환한다.
    public HttpResponse<String> complete(String payload, Duration timeout) {
        // 설정 주소로 보낼 HTTP 요청 Builder를 만든다. 요청 객체를 완성할 때까지 아래 옵션을 연결한다.
        var request = HttpRequest.newBuilder(properties.endpoint())
                // 이번 HTTP 요청에 시간 제한을 설정한다. 전체 예산이 부족하면 Gateway가 줄여서 전달한다.
                .timeout(timeout)
                // API 키를 Bearer 인증 헤더에 넣는다. JSON 본문이나 URL에는 키를 넣지 않는다.
                .header("Authorization", "Bearer " + properties.apiKey())
                // 전송할 본문이 JSON이라는 정보를 제공자에게 알려준다.
                .header("Content-Type", "application/json")
                // 응답도 JSON 형식으로 받기를 원한다고 알린다.
                .header("Accept", "application/json")
                // JSON 문자열을 UTF-8 본문으로 만들어 POST 방식의 요청을 완성한다.
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
        // 비동기로 요청을 시작하고 Future를 받는다. 응답 본문은 UTF-8 문자열로 끝까지 모은다.
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        // 실패할 수 있는 작업을 시작한다. 실패 유형에 따라 아래 catch에서 처리한다.
        try {
            // 헤더 수신만이 아니라 본문 전체를 받는 Future에도 제한을 걸어, 본문 전송 중 멈춘
            // 서버를 무기한 기다리지 않는다. Gateway가 전달한 남은 전체 예산도 함께 적용된다.
            // Future가 본문 전체를 받을 때까지 지정한 나노초 동안 기다린다. 완료되면 HTTP 응답을 반환한다.
            return pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        // 위 get()의 대기 시간이 초과한 경우다. 아래에서 취소를 요청하고 TIMEOUT으로 변환한다.
        } catch (TimeoutException exception) {
            // 진행 중인 HTTP 작업에 취소를 요청한다. 외부 제공자의 생성·과금 취소까지 보장하지는 않는다.
            pending.cancel(true);
            // 대기 시간 초과를 공통 AI 시간 초과 예외로 만들어 던진다.
            throw failure(AiGatewayErrorCode.TIMEOUT);
        // 응답을 기다리던 현재 스레드가 중단된 경우다.
        } catch (InterruptedException exception) {
            // 진행 중인 HTTP 작업에 취소를 요청한다. 외부 제공자의 생성·과금 취소까지 보장하지는 않는다.
            pending.cancel(true);
            // 잡은 중단 신호를 현재 스레드에 다시 표시해 상위 실행 코드도 중단을 알 수 있게 한다.
            Thread.currentThread().interrupt();
            // 중단된 호출을 서비스 사용 불가로 끝낸다. 자동 재시도하지 않는다.
            throw failure(AiGatewayErrorCode.PROVIDER_UNAVAILABLE);
        // 비동기 HTTP 작업 내부에서 발생한 연결 실패 등의 원인을 전달받은 경우다.
        } catch (ExecutionException exception) {
            // 연결 단절도 외부에서 처리·과금을 완료했는지 알 수 없으므로 자동 재시도하지 않는다.
            // 원본 요청·본문이 예외 로그에 섞이지 않게 고정 공통 오류만 전달한다.
            // 내부 원인이 HTTP 클라이언트의 시간 초과인지 검사한다. get() 자체의 시간 초과와 별도 경로다.
            throw failure(exception.getCause() instanceof HttpTimeoutException
                    // 시간 초과면 TIMEOUT, 그 밖의 전송 실패면 PROVIDER_UNAVAILABLE로 분류해 던진다.
                    ? AiGatewayErrorCode.TIMEOUT : AiGatewayErrorCode.PROVIDER_UNAVAILABLE);
        // 위에서 시작한 코드 블록의 범위를 끝낸다.
        }
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }

    // 통신 실패에 사용할 공통 AI 예외를 생성하는 보조 함수다.
    private AiGatewayException failure(AiGatewayErrorCode code) {
        // 헤더를 받지 못했으므로 추적 ID를 비우고, 재시도 금지·대기 없음·원인 원문 없음으로 반환한다.
        return new AiGatewayException(code, null, false, null, null);
    // 위에서 시작한 코드 블록의 범위를 끝낸다.
    }
// 위에서 시작한 코드 블록의 범위를 끝낸다.
}
