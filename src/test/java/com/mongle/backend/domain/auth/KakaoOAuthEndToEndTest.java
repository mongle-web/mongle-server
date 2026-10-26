package com.mongle.backend.domain.auth;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.auth.repository.SocialAccountRepository;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "KAKAO_CLIENT_ID=test-kakao",
            "KAKAO_CLIENT_SECRET=test-secret",
            "OAUTH_COOKIE_SECURE=false",
            "MONGLE_AUTH_ALLOWED_ORIGINS=https://mongle.test",
            "MONGLE_OAUTH_SUCCESS_URL=https://mongle.test/auth/callback",
            "MONGLE_OAUTH_FAILURE_URL=https://mongle.test/login",
            "spring.datasource.url=jdbc:h2:mem:kakao-e2e;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
        })
@ActiveProfiles({"test", "oauth"})
@Import(KakaoOAuthEndToEndTest.Provider.class)
class KakaoOAuthEndToEndTest {
    static HttpServer provider;
    static final AtomicInteger tokenCalls = new AtomicInteger();
    static final AtomicInteger invalidClientAuthentication = new AtomicInteger();

    @Value("${local.server.port}")
    int port;

    @Autowired SocialAccountRepository accounts;
    final JsonMapper json = JsonMapper.builder().build();

    @TestConfiguration(proxyBeanMethods = false)
    static class Provider {
        @Bean
        @Primary
        ClientRegistrationRepository localKakao() throws Exception {
            provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            provider.createContext(
                    "/token",
                    exchange -> {
                        tokenCalls.incrementAndGet();
                        var form =
                                query(
                                        new String(
                                                exchange.getRequestBody().readAllBytes(),
                                                StandardCharsets.UTF_8));
                        if (!"test-kakao".equals(form.get("client_id"))
                                || !"test-secret".equals(form.get("client_secret"))) {
                            invalidClientAuthentication.incrementAndGet();
                        }
                        String code = form.get("code");
                        String body =
                                "{\"access_token\":\"provider-"
                                        + code
                                        + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}";
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            provider.createContext(
                    "/me",
                    exchange -> {
                        String authorization =
                                exchange.getRequestHeaders().getFirst("Authorization");
                        String body;
                        int status = 200;
                        if ("Bearer provider-noemail".equals(authorization)) {
                            body = "{\"id\":7002,\"kakao_account\":{}}";
                        } else if ("Bearer provider-unverified".equals(authorization)) {
                            body =
                                    "{\"id\":7003,\"kakao_account\":{\"email\":\"unverified@example.test\",\"is_email_valid\":true,\"is_email_verified\":false}}";
                        } else if ("Bearer provider-broken".equals(authorization)) {
                            body = "{\"id\":\"malformed\",\"kakao_account\":{}}";
                        } else if ("Bearer provider-outage".equals(authorization)) {
                            status = 503;
                            body = "{\"error\":\"private-provider-detail\"}";
                        } else {
                            body =
                                    "{\"id\":7001,\"kakao_account\":{\"email\":\"kakao-e2e@example.test\",\"is_email_valid\":true,\"is_email_verified\":true}}";
                        }
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(status, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            provider.start();
            String base = "http://127.0.0.1:" + provider.getAddress().getPort();
            return new InMemoryClientRegistrationRepository(
                    ClientRegistration.withRegistrationId("kakao")
                            .clientId("test-kakao")
                            .clientSecret("test-secret")
                            .clientAuthenticationMethod(
                                    ClientAuthenticationMethod.CLIENT_SECRET_POST)
                            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                            .redirectUri("{baseUrl}/login/oauth2/code/kakao")
                            .scope("account_email")
                            .authorizationUri(base + "/authorize")
                            .tokenUri(base + "/token")
                            .userInfoUri(base + "/me")
                            .userNameAttributeName("id")
                            .clientName("Kakao test")
                            .build());
        }
    }

    @AfterAll
    static void stopProvider() {
        if (provider != null) provider.stop(0);
    }

    @Test
    void logsInThroughRealFiltersAndHttpThenRefreshesAndLogsOutWithoutDuplicateSignup()
            throws Exception {
        try (var client = browser()) {
            String state = begin(client);
            var callback = get(client, "/login/oauth2/code/kakao?code=ok&state=" + enc(state));
            assertSuccess(callback);
            assertThat(callback.headers().allValues("Set-Cookie").toString())
                    .contains("MONGLE_REFRESH=", "HttpOnly", "Path=/api/v1/auth", "SameSite=Lax");
            long accountsAfterFirst = accounts.count();
            int exchanges = tokenCalls.get();
            var replay = get(client, "/login/oauth2/code/kakao?code=ok&state=" + enc(state));
            assertThat(replay.headers().firstValue("Location"))
                    .contains("https://mongle.test/login?code=AUTH_401_6");
            assertThat(replay.headers().allValues("Set-Cookie").toString())
                    .doesNotContain("MONGLE_REFRESH=");
            assertThat(tokenCalls).hasValue(exchanges);
            // No CSRF token means no refresh, even with the valid HttpOnly refresh cookie.
            assertThat(send(client, "/api/v1/auth/refresh", "POST", null, null).statusCode())
                    .isEqualTo(403);
            var csrf = json.readTree(get(client, "/api/v1/auth/csrf").body()).get("data");
            String header = csrf.get("headerName").asString();
            String token = csrf.get("token").asString();
            var refreshed = send(client, "/api/v1/auth/refresh", "POST", header, token);
            assertThat(refreshed.statusCode()).isEqualTo(200);
            var data = json.readTree(refreshed.body()).get("data");
            assertThat(data.get("user").get("onboardingCompleted").asBoolean()).isFalse();
            String access = data.get("accessToken").asString();
            assertThat(access).doesNotStartWith("provider-");
            assertThat(
                            send(
                                            client,
                                            "/api/v1/users/me",
                                            "GET",
                                            "Authorization",
                                            "Bearer " + access)
                                    .statusCode())
                    .isEqualTo(200);
            assertThat(send(client, "/api/v1/auth/logout", "POST", header, token).statusCode())
                    .isEqualTo(200);
            assertThat(
                            send(
                                            client,
                                            "/api/v1/users/me",
                                            "GET",
                                            "Authorization",
                                            "Bearer " + access)
                                    .statusCode())
                    .isEqualTo(401);
            assertSuccess(login(client, "ok"));
            assertThat(accounts.count()).isEqualTo(accountsAfterFirst);
            assertThat(invalidClientAuthentication).hasValue(0);
        }
    }

    @Test
    void missingUnverifiedMalformedAndUnavailableProviderResponsesReturnOnlyServiceCodes()
            throws Exception {
        for (var scenario :
                Map.of(
                                "noemail",
                                "AUTH_401_2",
                                "unverified",
                                "AUTH_401_3",
                                "broken",
                                "AUTH_401_1",
                                "outage",
                                "AUTH_401_6")
                        .entrySet()) {
            try (var client = browser()) {
                long before = accounts.count();
                var response = login(client, scenario.getKey());
                assertThat(response.statusCode()).isEqualTo(303);
                assertThat(response.headers().firstValue("Location"))
                        .contains("https://mongle.test/login?code=" + scenario.getValue());
                assertThat(response.body()).isEmpty();
                assertThat(response.headers().toString())
                        .doesNotContain("private-provider-detail", "provider-" + scenario.getKey());
                assertThat(accounts.count()).isEqualTo(before);
            }
        }
    }

    @Test
    void cancellationInvalidStateMissingCookieAndReplayedCallbackNeverIssueTokens()
            throws Exception {
        int calls = tokenCalls.get();
        try (var client = browser()) {
            String state = begin(client);
            var canceled =
                    get(
                            client,
                            "/login/oauth2/code/kakao?error=access_denied&error_description=private-detail&state="
                                    + enc(state));
            assertThat(canceled.statusCode()).isEqualTo(303);
            assertThat(canceled.headers().firstValue("Location"))
                    .contains("https://mongle.test/login?code=AUTH_401_6");
            assertThat(canceled.headers().toString())
                    .doesNotContain("private-detail", "MONGLE_REFRESH=");
            // The OAuth session was consumed and cleared.
            assertThat(
                            get(client, "/login/oauth2/code/kakao?code=ok&state=" + enc(state))
                                    .statusCode())
                    .isEqualTo(303);
            state = begin(client);
            assertThat(get(client, "/login/oauth2/code/kakao?code=ok&state=incorrect").statusCode())
                    .isEqualTo(303);
            try (var withoutCookie = browser()) {
                assertThat(
                                get(
                                                withoutCookie,
                                                "/login/oauth2/code/kakao?code=ok&state="
                                                        + enc(state))
                                        .statusCode())
                        .isEqualTo(303);
            }
            assertThat(tokenCalls).hasValue(calls);
        }
    }

    @Test
    void credentialedRefreshPreflightAllowsOnlyConfiguredFrontendOrigin() throws Exception {
        try (var client = browser()) {
            for (String origin : new String[] {"https://mongle.test", "https://evil.test"}) {
                var request =
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + port
                                                        + "/api/v1/auth/refresh"))
                                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                                .header("Origin", origin)
                                .header("Access-Control-Request-Method", "POST")
                                .header(
                                        "Access-Control-Request-Headers",
                                        "X-XSRF-TOKEN,Content-Type")
                                .build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (origin.equals("https://mongle.test")) {
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
                            .contains(origin);
                    assertThat(response.headers().firstValue("Access-Control-Allow-Credentials"))
                            .contains("true");
                } else {
                    assertThat(response.statusCode()).isEqualTo(403);
                    assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
                            .isEmpty();
                }
            }
        }
    }

    private void assertSuccess(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(303);
        assertThat(response.headers().firstValue("Location"))
                .contains("https://mongle.test/auth/callback");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(response.body()).isEmpty();
    }

    private HttpClient browser() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
    }

    private String begin(HttpClient client) throws Exception {
        var response =
                get(
                        client,
                        "/oauth2/authorization/kakao?redirect_uri=https://evil.test&next=https://evil.test");
        assertThat(response.statusCode()).isEqualTo(302);
        var values =
                query(
                        URI.create(response.headers().firstValue("Location").orElseThrow())
                                .getRawQuery());
        assertThat(values.get("redirect_uri"))
                .isEqualTo("http://127.0.0.1:" + port + "/login/oauth2/code/kakao");
        return values.get("state");
    }

    private HttpResponse<String> login(HttpClient client, String code) throws Exception {
        return get(
                client, "/login/oauth2/code/kakao?code=" + code + "&state=" + enc(begin(client)));
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return send(client, path, "GET", null, null);
    }

    private HttpResponse<String> send(
            HttpClient client, String path, String method, String header, String value)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .method(method, HttpRequest.BodyPublishers.noBody());
        if (header != null) request.header(header, value);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Map<String, String> query(String value) {
        return Arrays.stream(value.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(
                        Collectors.toMap(
                                pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                                pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }
}
