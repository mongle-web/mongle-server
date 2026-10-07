package com.mongle.backend.global.logging;

import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.logging.support.LogCapture;
import com.mongle.backend.global.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 서블릿 서버에서 보안 거절과 비동기 완료까지 추적한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties =
        "spring.datasource.url=jdbc:h2:mem:mongle-http-logging;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
@Import(HttpRequestLoggingIntegrationTest.AsyncController.class)
class HttpRequestLoggingIntegrationTest {
    @Value("${local.server.port}") private int port;
    @Autowired private UserRepository users;
    @Autowired private TokenService tokens;
    @Autowired private AsyncController controller;

    @Test
    void securityRejectionHasServerRequestIdAndDoesNotLogQueryOrAuthorization() throws Exception {
        try (var capture = new LogCapture(HttpRequestLoggingFilter.class); var client = HttpClient.newHttpClient()) {
            var response = client.send(request("/api/v1/users/me?token=query-secret")
                    .header("X-Request-Id", "untrusted-client-id").header("Authorization", "Bearer header-secret").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
            String id = response.headers().firstValue(HttpRequestLoggingFilter.HEADER).orElseThrow();
            assertThat(UUID.fromString(id)).isNotNull();
            awaitLog(capture, id);
            assertThat(capture.messages()).anySatisfy(message -> assertThat(message)
                    .contains("requestId=" + id, "path=/api/v1/users/me", "status=401")
                    .doesNotContain("query-secret", "header-secret", "untrusted-client-id"));
        }
    }

    @Test
    void asyncRequestIsLoggedOnlyOnceAfterFinalResponse() throws Exception {
        var user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "테스트"));
        String access = tokens.login(user.getId()).response().accessToken();
        try (var capture = new LogCapture(HttpRequestLoggingFilter.class); var client = HttpClient.newHttpClient()) {
            var response = client.sendAsync(request("/api/v1/log-test/async?token=async-secret")
                    .header("Authorization", "Bearer " + access).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(controller.arrived.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(response.isDone()).isFalse();
            assertThat(capture.messages()).noneMatch(message -> message.contains("path=/api/v1/log-test/async"));
            controller.pending.setResult(ApiResponse.success("response-secret"));
            var finished = response.get(5, TimeUnit.SECONDS);
            assertThat(finished.statusCode()).isEqualTo(200);
            String id = finished.headers().firstValue(HttpRequestLoggingFilter.HEADER).orElseThrow();
            // 컨테이너의 onComplete는 소켓 응답 직후 실행될 수 있어 로그 관측까지 짧게 기다린다.
            awaitLog(capture, id);
            var completed = capture.messages().stream().filter(m -> m.contains("requestId=" + id)).toList();
            assertThat(completed).hasSize(1);
            assertThat(completed.getFirst()).contains("status=200", "path=/api/v1/log-test/async")
                    .doesNotContain(access, "async-secret", "response-secret");
        }
    }

    private void awaitLog(LogCapture capture, String id) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (capture.messages().stream().noneMatch(m -> m.contains("requestId=" + id)) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(8)).GET();
    }

    @RestController
    static class AsyncController {
        private final CountDownLatch arrived = new CountDownLatch(1);
        private final DeferredResult<ApiResponse<String>> pending = new DeferredResult<>(5000L);

        @GetMapping("/api/v1/log-test/async")
        DeferredResult<ApiResponse<String>> get() {
            arrived.countDown();
            return pending;
        }
    }
}
