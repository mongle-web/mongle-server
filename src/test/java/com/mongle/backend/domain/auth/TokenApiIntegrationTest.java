package com.mongle.backend.domain.auth;

import com.mongle.backend.domain.auth.dto.TokenResponse;
import com.mongle.backend.domain.auth.exception.AuthErrorCode;
import com.mongle.backend.domain.auth.repository.RefreshSessionRepository;
import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.error.BusinessException;
import com.mongle.backend.global.response.ApiResponse;
import com.mongle.backend.global.security.AuthCookies;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties =
        "spring.datasource.url=jdbc:h2:mem:mongle-token-http;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
@Import(TokenApiIntegrationTest.BusinessController.class)
class TokenApiIntegrationTest {
    @Value("${local.server.port}") private int port;
    @Autowired private TokenService tokens;
    @Autowired private UserRepository users;
    @Autowired private RefreshSessionRepository sessions;
    @Autowired private JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void authenticatesActualUserAndUpdatesOnboardingWithoutReplacingAccessToken() throws Exception {
        var user = users.saveAndFlush(User.register(UUID.randomUUID() + "@example.com"));
        var issued = tokens.login(user.getId());
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThat(send(client, "GET", "/api/v1/users/me", null, null).statusCode()).isEqualTo(401);
            var me = send(client, "GET", "/api/v1/users/me", issued.response(), null);
            assertThat(me.statusCode()).isEqualTo(200);
            assertThat(json.readTree(me.body()).at("/data/userId").asLong()).isEqualTo(user.getId());
            assertThat(json.readTree(me.body()).at("/data/onboardingCompleted").asBoolean()).isFalse();
            var blocked = send(client, "GET", "/api/v1/auth-test", issued.response(), null);
            assertThat(blocked.statusCode()).isEqualTo(403);
            assertThat(code(blocked)).isEqualTo("USER_ONBOARDING_REQUIRED");
            assertThat(send(client, "POST", "/api/v1/users/me/onboarding", issued.response(),
                    "{\"nickname\":\"a!\"}").statusCode()).isEqualTo(400);
            assertThat(send(client, "POST", "/api/v1/users/me/onboarding", issued.response(),
                    "{\"nickname\":\"몽글12\",\"userId\":999}").statusCode()).isEqualTo(200);
            assertThat(send(client, "GET", "/api/v1/auth-test", issued.response(), null).statusCode()).isEqualTo(200);
            assertThat(send(client, "POST", "/api/v1/users/me/onboarding", issued.response(),
                    "{\"nickname\":\"몽글12\"}").statusCode()).isEqualTo(200);
            assertThat(send(client, "POST", "/api/v1/users/me/onboarding", issued.response(),
                    "{\"nickname\":\"다른닉네임\"}").statusCode()).isEqualTo(409);
        }
        assertThat(users.findById(user.getId()).orElseThrow().getNickname()).isEqualTo("몽글12");
    }

    @Test
    void rotatesCookieWithCsrfAndLogoutRevokesOnlyCurrentSession() throws Exception {
        var user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "테스트"));
        var first = tokens.login(user.getId());
        var otherDevice = tokens.login(user.getId());
        var cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build()) {
            var csrf = csrf(client);
            var cookie = new HttpCookie(AuthCookies.REFRESH, first.refreshToken());
            // Java 쿠키 저장소가 localhost에 부여한 도메인과 맞춰 중복 쿠키를 만들지 않는다.
            cookie.setDomain(cookieManager.getCookieStore().getCookies().getFirst().getDomain());
            cookie.setPath(AuthCookies.PATH);
            cookie.setVersion(0);
            cookieManager.getCookieStore().add(uri("/"), cookie);
            var withoutCsrf = send(client, "POST", "/api/v1/auth/refresh", null, "");
            assertThat(withoutCsrf.statusCode()).isEqualTo(403);
            assertThat(code(withoutCsrf)).isEqualTo("COMMON_FORBIDDEN");
            var refreshed = postWithCsrf(client, "/api/v1/auth/refresh", csrf);
            assertThat(refreshed.statusCode()).isEqualTo(200);
            assertThat(cookieManager.getCookieStore().getCookies().stream()
                    .filter(c -> c.getName().equals(AuthCookies.REFRESH)).count()).isEqualTo(1);
            assertThat(refreshed.body()).doesNotContain(first.refreshToken(), "refreshToken");
            assertThat(refreshed.headers().allValues("Set-Cookie").toString())
                    .contains("MONGLE_REFRESH", "HttpOnly", "SameSite=Lax", "Path=/api/v1/auth");
            assertThat(postWithCsrf(client, "/api/v1/auth/logout", csrf).statusCode()).isEqualTo(200);
            assertThatThrownBy(() -> tokens.refresh(first.refreshToken())).isInstanceOf(BusinessException.class);
            assertThat(send(client, "GET", "/api/v1/users/me", first.response(), null).statusCode()).isEqualTo(401);
            assertThat(send(client, "GET", "/api/v1/users/me", otherDevice.response(), null).statusCode()).isEqualTo(200);
            assertThat(postWithCsrf(client, "/api/v1/auth/logout", csrf).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void rejectsExpiredSessionsMissingCookiesAndTamperedAccessTokens() throws Exception {
        var user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "테스트"));
        var issued = tokens.login(user.getId());
        var parts = issued.response().accessToken().split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        var altered = new TokenResponse(String.join(".", parts), "Bearer", 900, issued.response().user());
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            assertThat(send(client, "GET", "/api/v1/users/me", altered, null).statusCode()).isEqualTo(401);
            assertThat(postWithCsrf(client, "/api/v1/auth/refresh", csrf(client)).statusCode()).isEqualTo(401);
            jdbc.update("update refresh_sessions set expires_at = ? where user_id = ?",
                    java.time.LocalDateTime.of(2000, 1, 1, 0, 0), user.getId());
            assertThat(send(client, "GET", "/api/v1/users/me", issued.response(), null).statusCode()).isEqualTo(401);
            assertThatThrownBy(() -> tokens.refresh(issued.refreshToken())).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void storesOnlyHashAndRevokesSessionAfterConcurrentRefreshReuse() throws Exception {
        var user = users.saveAndFlush(User.register(UUID.randomUUID() + "@example.com"));
        var issued = tokens.login(user.getId());
        assertThat(jdbc.queryForList("select token_hash from refresh_sessions where user_id = ?", String.class, user.getId()))
                .allSatisfy(hash -> assertThat(hash).hasSize(64).isNotEqualTo(issued.refreshToken()));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> request = () -> {
                start.await();
                try {
                    tokens.refresh(issued.refreshToken());
                    return true;
                } catch (BusinessException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);
                    return false;
                }
            };
            var a = executor.submit(request);
            var b = executor.submit(request);
            start.countDown();
            assertThat(java.util.List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("select count(*) from refresh_sessions where user_id = ?", Long.class, user.getId()))
                .isZero();
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThat(send(client, "GET", "/api/v1/users/me", issued.response(), null).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void replayOfAnyPreviousTokenCommitsRevocationAndRejectsAttackersTokens() throws Exception {
        var user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "테스트"));
        var original = tokens.login(user.getId());
        var otherDevice = tokens.login(user.getId());
        var rotated = tokens.refresh(original.refreshToken());
        var latest = tokens.refresh(rotated.refreshToken());
        Long sessionId = jdbc.queryForObject("select id from refresh_sessions where user_id = ? order by id limit 1",
                Long.class, user.getId());
        assertThat(jdbc.queryForList("select token_hash from refresh_session_tokens where session_id = ?", String.class, sessionId))
                .hasSize(3).allSatisfy(hash -> assertThat(hash).hasSize(64)
                        .isNotEqualTo(original.refreshToken()).isNotEqualTo(rotated.refreshToken()).isNotEqualTo(latest.refreshToken()));
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThat(send(client, "GET", "/api/v1/users/me", latest.response(), null).statusCode()).isEqualTo(200);
            assertThatThrownBy(() -> tokens.refresh(original.refreshToken())).isInstanceOfSatisfying(
                    BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
            // HTTP 401로 끝나는 재사용 탐지에서도 삭제가 실제 커밋되어야 한다.
            assertThat(sessions.findById(sessionId)).isEmpty();
            assertThat(jdbc.queryForObject("select count(*) from refresh_session_tokens where session_id = ?", Long.class, sessionId))
                    .isZero();
            for (var issued : java.util.List.of(original, rotated, latest)) {
                assertThat(send(client, "GET", "/api/v1/users/me", issued.response(), null).statusCode()).isEqualTo(401);
                assertThatThrownBy(() -> tokens.refresh(issued.refreshToken())).isInstanceOf(BusinessException.class);
            }
            assertThat(send(client, "GET", "/api/v1/users/me", otherDevice.response(), null).statusCode()).isEqualTo(200);
            assertThatCode(() -> tokens.refresh(otherDevice.refreshToken())).doesNotThrowAnyException();
        }
    }

    @Test
    void unknownTokenDoesNotRevokeSessionsAndLogoutAcceptsKnownPreviousToken() {
        var user = users.saveAndFlush(User.register(UUID.randomUUID() + "@example.com"));
        var original = tokens.login(user.getId());
        assertThatThrownBy(() -> tokens.refresh("A".repeat(43))).isInstanceOf(BusinessException.class);
        var rotated = tokens.refresh(original.refreshToken());
        tokens.logout(original.refreshToken());
        assertThatThrownBy(() -> tokens.refresh(rotated.refreshToken())).isInstanceOf(BusinessException.class);
    }

    @Test
    void lineageMigrationRevokesUntrackedLegacySessionsAndCanBeRepeated() {
        var user = users.saveAndFlush(User.register(UUID.randomUUID() + "@example.com"));
        var original = tokens.login(user.getId());
        var trackedUser = users.saveAndFlush(User.register(UUID.randomUUID() + "@example.com"));
        var tracked = tokens.login(trackedUser.getId());
        Long sessionId = jdbc.queryForObject("select id from refresh_sessions where user_id = ?", Long.class, user.getId());
        jdbc.update("delete from refresh_session_tokens where session_id = ?", sessionId);
        var script = new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/migrations/20261003-refresh-token-lineage.sql"));
        script.execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        script.execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        assertThat(jdbc.queryForObject("select count(*) from refresh_session_tokens where session_id = ?", Long.class, sessionId))
                .isZero();
        assertThat(sessions.findById(sessionId)).isEmpty();
        assertThatThrownBy(() -> tokens.refresh(original.refreshToken())).isInstanceOf(BusinessException.class);
        assertThatCode(() -> tokens.refresh(tracked.refreshToken())).doesNotThrowAnyException();
    }

    @Test
    void permitsOnlyConfiguredFrontendOriginsAndDocumentsNewApis() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var allowed = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/refresh"))
                    .header("Origin", "http://localhost:3000")
                    .header("Access-Control-Request-Method", "POST").method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(allowed.statusCode()).isEqualTo(200);
            assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3000");
            var denied = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf"))
                    .header("Origin", "https://unknown.example.com").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(code(denied)).isEqualTo("COMMON_FORBIDDEN");
            var document = json.readTree(send(client, "GET", "/v3/api-docs", null, null).body());
            assertThat(document.at("/paths/~1api~1v1~1users~1me/get/summary").asString()).isEqualTo("내 정보 조회");
            assertThat(document.at("/paths/~1api~1v1~1auth~1refresh/post/summary").asString()).isEqualTo("인증 토큰 재발급");
        }
    }

    private JsonNode csrf(HttpClient client) throws Exception {
        var result = send(client, "GET", "/api/v1/auth/csrf", null, null);
        assertThat(result.statusCode()).isEqualTo(200);
        return json.readTree(result.body()).get("data");
    }

    private HttpResponse<String> postWithCsrf(HttpClient client, String path, JsonNode csrf) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
                .header(csrf.get("headerName").asString(), csrf.get("token").asString())
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, TokenResponse token, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token.accessToken());
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
    private String code(HttpResponse<String> response) { return json.readTree(response.body()).get("code").asString(); }

    // 온보딩 접근 제어를 검증하기 위한 테스트 전용 업무 API다.
    @RestController
    static class BusinessController {
        @GetMapping("/api/v1/auth-test")
        ApiResponse<String> get() { return ApiResponse.success("ok"); }
    }
}
