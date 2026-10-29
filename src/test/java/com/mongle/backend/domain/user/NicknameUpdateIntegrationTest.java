package com.mongle.backend.domain.user;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.auth.dto.TokenResponse;
import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.user.service.UserService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** 실제 HTTP·DB로 본인 정보만 변경하며 크레딧 활성화와 무관하게 동작하는지 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:nickname-update;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "mongle.credit.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"})
@ActiveProfiles("test")
class NicknameUpdateIntegrationTest {
    static final String PATH = "/api/v1/users/me/nickname";
    @Autowired UserRepository users;
    @Autowired UserService service;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;
    @Value("${local.server.port}") int port;
    final JsonMapper json = JsonMapper.builder().build();
    Long user;
    TokenResponse token;
    HttpClient client;

    @BeforeEach
    void setup() {
        user = users.saveAndFlush(User.create(UUID.randomUUID()+"@nickname.test", "이전닉네임")).getId();
        token = tokens.login(user).response();
        client = HttpClient.newHttpClient();
    }

    @AfterEach void close() { client.close(); }

    @Test
    void changesOnlyAuthenticatedNicknameAndKeepsSessionAndLegacyCreditsWithoutCreditFeature() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID()+"@nickname.test", "다른회원"));
        String email = service.getMe(user).email();
        var changed = send(token, "PATCH", PATH, Map.of("nickname", "새몽글101"));
        var result = data(changed, 200);
        assertThat(changed.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(result.get("userId").asLong()).isEqualTo(user);
        assertThat(result.get("nickname").asString()).isEqualTo("새몽글101");
        assertThat(result.get("email").asString()).isEqualTo(email);
        assertThat(result.get("onboardingCompleted").asBoolean()).isTrue();
        assertThat(data(send(token, "GET", "/api/v1/users/me", null), 200).get("nickname").asString())
                .isEqualTo("새몽글101");
        assertThat(service.getMe(other.getId()).nickname()).isEqualTo("다른회원");
        for (String table : List.of("credit_accounts", "credit_grants", "credit_ledger"))
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where user_id=?", Long.class, user)).isZero();
    }

    @Test
    void sameNicknameReturnsSuccessWithoutUpdatingUserOrTimestamp() throws Exception {
        var before = jdbc.queryForMap("select * from users where id=?", user);
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        assertThat(data(send(token, "PATCH", PATH, Map.of("nickname", "이전닉네임")), 200)
                .get("nickname").asString()).isEqualTo("이전닉네임");
        assertThat(stats.getEntityUpdateCount()).isZero();
        assertThat(jdbc.queryForMap("select * from users where id=?", user)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"몽글", "abcdefghij", "1234567890", "ㄱㄴ"})
    void acceptsExistingNicknameRulesAndDoesNotRequireUniqueness(String nickname) throws Exception {
        users.saveAndFlush(User.create(UUID.randomUUID()+"@nickname.test", nickname));
        assertThat(data(send(token, "PATCH", PATH, Map.of("nickname", nickname)), 200)
                .get("nickname").asString()).isEqualTo(nickname);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"가", "abcdefghijk", "몽글!", " 몽글", "몽글 ", "몽글😀", "ab\n"})
    void rejectsInvalidNicknameAndKeepsPersistedUser(String nickname) throws Exception {
        var body = new HashMap<String, Object>(); body.put("nickname", nickname);
        var before = jdbc.queryForMap("select * from users where id=?", user);
        var response = send(token, "PATCH", PATH, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(response.body()).contains("COMMON_400_2");
        assertThat(jdbc.queryForMap("select * from users where id=?", user)).isEqualTo(before);
    }

    @Test
    void rejectsMissingNicknameAndFieldsThatAttemptToChangeOtherUserOrOnboarding() throws Exception {
        var other = users.saveAndFlush(User.create(UUID.randomUUID()+"@nickname.test", "타인닉네임"));
        var before = jdbc.queryForMap("select * from users where id=?", user);
        var missing = send(token, "PATCH", PATH, Map.of());
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(missing.body()).contains("COMMON_400_2");
        for (String field : List.of("userId", "email", "onboardingCompleted")) {
            var response = send(token, "PATCH", PATH, Map.of("nickname", "새닉네임", field, other.getId()));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
            assertThat(response.body()).contains("COMMON_400_1");
        }
        assertThat(jdbc.queryForMap("select * from users where id=?", user)).isEqualTo(before);
        assertThat(service.getMe(other.getId()).nickname()).isEqualTo("타인닉네임");
    }

    @Test
    void rejectsAnonymousAndPendingOnboardingWithoutBypassingSignupBenefit() throws Exception {
        assertThat(send(null, "PATCH", PATH, Map.of("nickname", "새몽글")).statusCode()).isEqualTo(401);
        var pending = users.saveAndFlush(User.register(UUID.randomUUID()+"@nickname.test"));
        var pendingToken = tokens.login(pending.getId()).response();
        var response = send(pendingToken, "PATCH", PATH, Map.of("nickname", "새몽글"));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("USER_403_1");
        assertThat(service.getMe(pending.getId()).onboardingCompleted()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from credit_grants where user_id=?", Long.class, pending.getId())).isZero();
    }

    @Test
    void exposesNicknamePatchWithRequestSchemaAndBearerSecurityInOpenApi() throws Exception {
        var document = json.readTree(send(null, "GET", "/v3/api-docs", null).body());
        var operation = document.at("/paths/~1api~1v1~1users~1me~1nickname/patch");
        assertThat(operation.get("summary").asString()).isEqualTo("내 닉네임 변경");
        assertThat(operation.at("/security/0/bearerAuth").isArray()).isTrue();
        assertThat(operation.at("/requestBody/content/application~1json/schema/$ref").asString())
                .endsWith("/NicknameUpdateRequest");
        assertThat(operation.get("responses").has("403")).isTrue();
    }

    private HttpResponse<String> send(TokenResponse auth, String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(10));
        if (auth != null) request.header("Authorization", "Bearer " + auth.accessToken());
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return json.readTree(response.body()).get("data");
    }
}
