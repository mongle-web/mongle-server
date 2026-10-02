package com.mongle.backend.domain.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "GOOGLE_CLIENT_ID=test-google", "GOOGLE_CLIENT_SECRET=test-secret",
        "KAKAO_CLIENT_ID=test-kakao", "KAKAO_CLIENT_SECRET=test-secret",
        "spring.datasource.url=jdbc:h2:mem:oauth-redirect;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles({"test", "oauth"})
class OAuthRedirectIntegrationTest {
    @Value("${local.server.port}") private int port;

    @Test
    void generatesProviderRedirectsWithStateAndRejectsUnknownStateWithoutProviderHttpCalls() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            for (String provider : new String[]{"google", "kakao"}) {
                var response = get(client, "/oauth2/authorization/" + provider);
                assertThat(response.statusCode()).isEqualTo(302);
                assertThat(response.headers().allValues("Set-Cookie").toString())
                        .contains("JSESSIONID", "Secure", "HttpOnly");
                var target = URI.create(response.headers().firstValue("Location").orElseThrow());
                assertThat(target.getHost()).isEqualTo(provider.equals("google")
                        ? "accounts.google.com" : "kauth.kakao.com");
                Map<String, String> query = query(target);
                assertThat(query.get("state")).isNotBlank();
                assertThat(query.get("redirect_uri")).isEqualTo(
                        "http://localhost:8080/login/oauth2/code/" + provider);
                assertThat(query.get("scope")).contains(provider.equals("google") ? "openid" : "account_email");
                assertThat(query.get("scope")).doesNotContain("profile_nickname", "profile_image");
            }
            var failure = get(client, "/login/oauth2/code/kakao?code=fake&state=unknown");
            assertThat(failure.statusCode()).isEqualTo(401);
            assertThat(failure.body()).contains("AUTH_LOGIN_FAILED").doesNotContain("fake", "unknown");
        }
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&")).map(value -> value.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair ->
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }
}
