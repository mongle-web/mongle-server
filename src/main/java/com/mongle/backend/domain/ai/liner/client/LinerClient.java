package com.mongle.backend.domain.ai.liner.client;

import com.mongle.backend.domain.ai.config.AiAsyncResources;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

/** HTTP 시도 하나를 비동기로 전송한다. 응답 검증·DB 저장·재시도는 Gateway가 수행한다. */
@Component
public class LinerClient {
    private final HttpClient http;              // 재사용할 HTTP 연결 풀.
    private final LinerProperties properties;   // 주소와 인증 설정.
    private final AiAsyncResources resources;   // 본문 전체 수신 제한을 예약할 타이머.

    public LinerClient(@Qualifier("linerHttpClient") HttpClient http, LinerProperties properties, AiAsyncResources resources) {
        this.http = http;
        this.properties = properties;
        this.resources = resources;
    }

    public CompletableFuture<HttpResponse<String>> complete(String payload, Duration timeout) {
        CompletableFuture<HttpResponse<String>> transport;
        try {
            var request = HttpRequest.newBuilder(properties.endpoint()).timeout(timeout)
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
            // 응답 본문을 끝까지 수신하면 완료된다. get()/join()으로 결과를 기다리지 않는다.
            transport = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            // 요청·키가 포함될 수 있는 원본 예외를 외부에 첨부하지 않는다.
            return CompletableFuture.failedFuture(failure(AiGatewayErrorCode.PROVIDER_UNAVAILABLE));
        }
        var completed = new CompletableFuture<HttpResponse<String>>();
        try {
            var deadline = resources.schedule(() -> {
                // HttpRequest.timeout 외에 본문 전체 수신도 제한한다. Future만 실패시키고 HTTP를 방치하지 않는다.
                if (completed.completeExceptionally(failure(AiGatewayErrorCode.TIMEOUT))) transport.cancel(true);
            }, timeout);
            completed.whenComplete((response, error) -> {
                // 정상·실패·취소 모두 예약을 제거한다. 완료된 요청의 타이머가 계속 누적되지 않는다.
                deadline.cancel(false);
                // 반환 Future와 원본 HTTP Future는 다르므로 취소를 명시적으로 원본에 전달한다.
                if (completed.isCancelled()) transport.cancel(true);
            });
            transport.whenComplete((response, error) -> {
                if (error == null) {
                    completed.complete(response);
                } else {
                    // 비동기 오류 포장만 벗겨 분류하고, 원문이 포함될 수 있는 원인은 보관하지 않는다.
                    while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
                    completed.completeExceptionally(failure(error instanceof HttpTimeoutException
                            ? AiGatewayErrorCode.TIMEOUT : AiGatewayErrorCode.PROVIDER_UNAVAILABLE));
                }
            });
        } catch (RejectedExecutionException exception) {
            // 종료된 타이머에 제출할 수 없으면 통신도 취소하고 결과를 실패로 완료한다.
            transport.cancel(true);
            completed.completeExceptionally(failure(AiGatewayErrorCode.PROVIDER_UNAVAILABLE));
        }
        return completed;
    }

    private AiGatewayException failure(AiGatewayErrorCode code) {
        return new AiGatewayException(code, null, false, null, null);
    }
}
