package com.mongle.backend.domain.dream;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.auth.dto.TokenResponse;
import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.dream.entity.*;
import com.mongle.backend.domain.dream.repository.DreamRepository;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties =
                "spring.datasource.url=jdbc:h2:mem:mongle-dream-http;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
class DreamApiIntegrationTest {
    @Value("${local.server.port}")
    private int port;

    @Autowired private TokenService tokens;
    @Autowired private UserRepository users;
    @Autowired private DreamRepository dreams;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Test
    void restoresTextBeforeNextAndCompletesWithEqualEmotions() throws Exception {
        var token = login();
        try (var client = HttpClient.newHttpClient()) {
            var empty =
                    data(
                            send(
                                    client,
                                    token,
                                    "PUT",
                                    draftPath(today),
                                    Map.of("originalText", "")),
                            200);
            assertThat(empty.get("recordStatus").asString()).isEqualTo("DRAFT");
            assertThat(
                            send(
                                            client,
                                            token,
                                            "POST",
                                            path(empty) + "/submit",
                                            Map.of("revision", revision(empty)))
                                    .statusCode())
                    .isEqualTo(400);
            var saved =
                    data(
                            send(
                                    client,
                                    token,
                                    "PUT",
                                    draftPath(today),
                                    draftBody(empty, "하늘을 나는 꿈")),
                            200);
            var recovered =
                    data(send(client, token, "GET", "/api/v1/dreams/incomplete", null), 200);
            assertThat(recovered.at("/items/0/originalText").asString()).isEqualTo("하늘을 나는 꿈");
            assertThat(recovered.at("/items/0/dreamedAt").asString()).isEqualTo(today.toString());
            assertThat(recovered.get("hasNext").asBoolean()).isFalse();
            var pending =
                    data(
                            send(
                                    client,
                                    token,
                                    "POST",
                                    path(saved) + "/submit",
                                    Map.of("revision", revision(saved))),
                            200);
            assertThat(pending.get("recordStatus").asString()).isEqualTo("EMOTION_PENDING");
            // 감정 선택 전에 돌아가 원문을 고쳐도 단계는 유지한다.
            pending =
                    data(
                            send(
                                    client,
                                    token,
                                    "PUT",
                                    draftPath(today),
                                    draftBody(pending, "바다 위를 나는 꿈")),
                            200);
            var completed =
                    data(
                            send(
                                    client,
                                    token,
                                    "PUT",
                                    path(pending) + "/emotions",
                                    Map.of(
                                            "revision",
                                            revision(pending),
                                            "emotions",
                                            List.of("HAPPY", "CALM", "EXCITED"))),
                            200);
            assertThat(completed.get("recordStatus").asString()).isEqualTo("COMPLETED");
            assertThat(completed.get("emotions").size()).isEqualTo(3);
            assertThat(completed.get("title").isNull()).isTrue();
            assertThat(completed.get("edited").asBoolean()).isFalse();
            assertThat(
                            data(send(client, token, "GET", "/api/v1/dreams/incomplete", null), 200)
                                    .get("items")
                                    .size())
                    .isZero();
            assertThat(
                            send(
                                            client,
                                            token,
                                            "PUT",
                                            draftPath(today),
                                            draftBody(completed, "늦은 저장"))
                                    .statusCode())
                    .isEqualTo(409);
        }
    }

    @Test
    void countsEmojiAsOneCharacterAndRejectsInvalidDatesAndText() throws Exception {
        var token = login();
        try (var client = HttpClient.newHttpClient()) {
            assertThat(
                            send(
                                            client,
                                            token,
                                            "POST",
                                            "/api/v1/dreams",
                                            Map.of(
                                                    "dreamedAt",
                                                    today.toString(),
                                                    "originalText",
                                                    "😀".repeat(501)))
                                    .statusCode())
                    .isEqualTo(400);
            assertThat(
                            send(
                                            client,
                                            token,
                                            "POST",
                                            "/api/v1/dreams",
                                            Map.of(
                                                    "dreamedAt",
                                                    today.toString(),
                                                    "originalText",
                                                    "　 \n"))
                                    .statusCode())
                    .isEqualTo(400);
            assertThat(
                            send(
                                            client,
                                            token,
                                            "POST",
                                            "/api/v1/dreams",
                                            Map.of(
                                                    "dreamedAt",
                                                    today.plusDays(1).toString(),
                                                    "originalText",
                                                    "꿈"))
                                    .statusCode())
                    .isEqualTo(400);
            assertThat(
                            send(
                                            client,
                                            token,
                                            "POST",
                                            "/api/v1/dreams",
                                            Map.of("originalText", "꿈"))
                                    .statusCode())
                    .isEqualTo(400);
            var result =
                    data(
                            send(
                                    client,
                                    token,
                                    "POST",
                                    "/api/v1/dreams",
                                    Map.of(
                                            "dreamedAt",
                                            today.toString(),
                                            "originalText",
                                            "😀".repeat(500))),
                            201);
            assertThat(
                            result.get("originalText")
                                    .asString()
                                    .codePointCount(
                                            0, result.get("originalText").asString().length()))
                    .isEqualTo(500);
            assertThat(result.get("analysisStatus").asString()).isEqualTo("PENDING");
            assertThat(
                            send(
                                            client,
                                            token,
                                            "POST",
                                            "/api/v1/dreams",
                                            Map.of(
                                                    "dreamedAt",
                                                    today.toString(),
                                                    "originalText",
                                                    "중복"))
                                    .statusCode())
                    .isEqualTo(409);
            assertThat(
                            send(
                                            client,
                                            token,
                                            "PUT",
                                            draftPath(today.minusDays(1)),
                                            Map.of("originalText", "과거 꿈"))
                                    .statusCode())
                    .isEqualTo(200);
        }
    }

    @Test
    void rejectsInvalidEmotionsAndKeepsPendingRecordRecoverable() throws Exception {
        var token = login();
        try (var client = HttpClient.newHttpClient()) {
            var dream = create(client, token);
            for (var values :
                    List.of(
                            List.of(),
                            List.of("HAPPY", "HAPPY"),
                            List.of("HAPPY", "CALM", "SAD", "ANGRY"),
                            List.of("UNKNOWN"))) {
                assertThat(
                                send(
                                                client,
                                                token,
                                                "PUT",
                                                path(dream) + "/emotions",
                                                Map.of(
                                                        "revision",
                                                        revision(dream),
                                                        "emotions",
                                                        values))
                                        .statusCode())
                        .isEqualTo(400);
            }
            assertThat(
                            send(
                                            client,
                                            token,
                                            "PUT",
                                            path(dream) + "/emotions",
                                            Map.of(
                                                    "revision",
                                                    revision(dream),
                                                    "emotions",
                                                    Arrays.asList("HAPPY", null)))
                                    .statusCode())
                    .isEqualTo(400);
            assertThat(
                            data(send(client, token, "GET", "/api/v1/dreams/incomplete", null), 200)
                                    .at("/items/0/recordStatus")
                                    .asString())
                    .isEqualTo("EMOTION_PENDING");
        }
    }

    @Test
    void checksAuthenticationOnboardingOwnershipAndVersion() throws Exception {
        var token = login();
        var other = login();
        var unready =
                tokens.login(
                                users.saveAndFlush(
                                                User.register(UUID.randomUUID() + "@example.com"))
                                        .getId())
                        .response();
        try (var client = HttpClient.newHttpClient()) {
            var dream = create(client, token);
            assertThat(send(client, null, "GET", path(dream), null).statusCode()).isEqualTo(401);
            assertThat(send(client, unready, "GET", path(dream), null).statusCode()).isEqualTo(403);
            assertThat(send(client, other, "GET", path(dream), null).statusCode()).isEqualTo(404);
            assertThat(
                            send(
                                            client,
                                            other,
                                            "PATCH",
                                            path(dream),
                                            Map.of("revision", revision(dream), "title", "탈취"))
                                    .statusCode())
                    .isEqualTo(404);
            assertThat(
                            send(
                                            client,
                                            other,
                                            "PUT",
                                            path(dream) + "/emotions",
                                            Map.of(
                                                    "revision",
                                                    revision(dream),
                                                    "emotions",
                                                    List.of("HAPPY")))
                                    .statusCode())
                    .isEqualTo(404);
            assertThat(
                            send(
                                            client,
                                            other,
                                            "DELETE",
                                            path(dream) + "?revision=" + revision(dream),
                                            null)
                                    .statusCode())
                    .isEqualTo(404);
            assertThat(
                            send(client, token, "GET", "/api/v1/dreams/9223372036854775807", null)
                                    .statusCode())
                    .isEqualTo(404);
            assertThat(
                            send(client, token, "DELETE", path(dream) + "?revision=99", null)
                                    .statusCode())
                    .isEqualTo(409);
            assertThat(
                            send(client, token, "GET", "/api/v1/dreams/incomplete?size=51", null)
                                    .statusCode())
                    .isEqualTo(400);
        }
    }

    @Test
    void modifiesOnlyProvidedFieldsAndRetainsAnalysisAndEditedFlag() throws Exception {
        var token = login();
        try (var client = HttpClient.newHttpClient()) {
            var dream = complete(client, token, create(client, token));
            jdbc.update(
                    "update dreams set title = ?, analysis_status = 'COMPLETED' where id = ?",
                    "AI 제목",
                    dream.get("dreamId").asLong());
            var updated =
                    data(
                            send(
                                    client,
                                    token,
                                    "PATCH",
                                    path(dream),
                                    Map.of("revision", revision(dream), "originalText", "새 원문")),
                            200);
            assertThat(updated.get("title").asString()).isEqualTo("AI 제목");
            assertThat(updated.get("analysisStatus").asString()).isEqualTo("COMPLETED");
            assertThat(updated.get("emotions").get(0).asString()).isEqualTo("HAPPY");
            assertThat(updated.get("edited").asBoolean()).isTrue();
            assertThat(
                            send(
                                            client,
                                            token,
                                            "PATCH",
                                            path(dream),
                                            Map.of("revision", revision(dream), "title", "이전 버전"))
                                    .statusCode())
                    .isEqualTo(409);
            var clear = new HashMap<String, Object>();
            clear.put("revision", revision(updated));
            clear.put("title", null);
            var cleared = data(send(client, token, "PATCH", path(dream), clear), 200);
            assertThat(cleared.get("title").isNull()).isTrue();
            assertThat(cleared.get("originalText").asString()).isEqualTo("새 원문");
            assertThat(
                            send(
                                            client,
                                            token,
                                            "PATCH",
                                            path(dream),
                                            Map.of(
                                                    "revision",
                                                    revision(cleared),
                                                    "dreamedAt",
                                                    today.minusDays(1).toString()))
                                    .statusCode())
                    .isEqualTo(400);
            assertThat(
                            send(
                                            client,
                                            token,
                                            "PATCH",
                                            path(dream),
                                            Map.of("revision", revision(cleared)))
                                    .statusCode())
                    .isEqualTo(400);
            assertThat(
                            send(
                                            client,
                                            token,
                                            "PATCH",
                                            path(dream),
                                            Map.of(
                                                    "revision",
                                                    revision(cleared),
                                                    "originalText",
                                                    ""))
                                    .statusCode())
                    .isEqualTo(400);
            assertThat(
                            send(
                                            client,
                                            token,
                                            "PATCH",
                                            path(dream),
                                            Map.of(
                                                    "revision",
                                                    revision(cleared),
                                                    "title",
                                                    "가".repeat(21)))
                                    .statusCode())
                    .isEqualTo(400);
            var nullText = new HashMap<String, Object>();
            nullText.put("revision", revision(cleared));
            nullText.put("originalText", null);
            assertThat(send(client, token, "PATCH", path(dream), nullText).statusCode())
                    .isEqualTo(400);
            assertThat(
                            data(send(client, token, "GET", path(dream), null), 200)
                                    .get("originalText")
                                    .asString())
                    .isEqualTo("새 원문");
        }
    }

    @Test
    void doesNotMarkAnUnchangedCompletedRecordAsEdited() throws Exception {
        var token = login();
        try (var client = HttpClient.newHttpClient()) {
            var dream = complete(client, token, create(client, token));
            var unchanged =
                    data(
                            send(
                                    client,
                                    token,
                                    "PATCH",
                                    path(dream),
                                    Map.of(
                                            "revision",
                                            revision(dream),
                                            "originalText",
                                            "원문",
                                            "emotions",
                                            List.of("HAPPY"))),
                            200);
            assertThat(unchanged.get("edited").asBoolean()).isFalse();
        }
    }

    @Test
    void deletesRawDreamAndEmotionsButPreservesOwnedAnalysisGraph() throws Exception {
        var token = login();
        try (var client = HttpClient.newHttpClient()) {
            var dream = complete(client, token, create(client, token));
            long id = dream.get("dreamId").asLong();
            var graph =
                    transactions.execute(
                            status -> {
                                var entity = dreams.findById(id).orElseThrow();
                                var scene = DreamScene.create(entity, 1, "분석된 장면", false);
                                var symbol =
                                        DreamEntity.create(
                                                entity, DreamEntityType.SYMBOL, "바다", "분석된 요소");
                                entityManager.persist(scene);
                                entityManager.persist(symbol);
                                entityManager.flush();
                                entityManager.persist(DreamSceneEntity.link(scene, symbol));
                                return List.of(scene.getId(), symbol.getId());
                            });
            assertThat(
                            send(
                                            client,
                                            token,
                                            "DELETE",
                                            path(dream) + "?revision=" + revision(dream),
                                            null)
                                    .statusCode())
                    .isEqualTo(200);
            assertThat(send(client, token, "GET", path(dream), null).statusCode()).isEqualTo(404);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from dreams where id = ?", Long.class, id))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from dream_emotions where dream_id = ?",
                                    Long.class,
                                    id))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select content from dream_scenes where id = ?",
                                    String.class,
                                    graph.getFirst()))
                    .isEqualTo("분석된 장면");
            assertThat(
                            jdbc.queryForObject(
                                    "select dream_id from dream_scenes where id = ?",
                                    Long.class,
                                    graph.getFirst()))
                    .isNull();
            assertThat(
                            jdbc.queryForObject(
                                    "select user_id from dream_scenes where id = ?",
                                    Long.class,
                                    graph.getFirst()))
                    .isEqualTo(token.user().userId());
            assertThat(
                            jdbc.queryForObject(
                                    "select dream_id from dream_entities where id = ?",
                                    Long.class,
                                    graph.getLast()))
                    .isNull();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from dream_scene_entities where dream_scene_id"
                                        + " = ? and dream_entity_id = ?",
                                    Long.class,
                                    graph.getFirst(),
                                    graph.getLast()))
                    .isEqualTo(1);
            assertThat(
                            send(
                                            client,
                                            token,
                                            "POST",
                                            "/api/v1/dreams",
                                            Map.of(
                                                    "dreamedAt",
                                                    today.toString(),
                                                    "originalText",
                                                    "새 꿈"))
                                    .statusCode())
                    .isEqualTo(201);
        }
    }

    @Test
    void permitsOnlyOneConcurrentCreateForTheSameDate() throws Exception {
        var token = login();
        try (var client = HttpClient.newHttpClient();
                var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> request =
                    () -> {
                        start.await();
                        return send(
                                        client,
                                        token,
                                        "POST",
                                        "/api/v1/dreams",
                                        Map.of(
                                                "dreamedAt",
                                                today.toString(),
                                                "originalText",
                                                "동시 등록"))
                                .statusCode();
                    };
            var first = executor.submit(request);
            var second = executor.submit(request);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from dreams where user_id = ? and dreamed_at ="
                                        + " ?",
                                    Long.class,
                                    token.user().userId(),
                                    today))
                    .isEqualTo(1);
        }
    }

    @Test
    void rejectsStaleAutosavesEvenAfterDeleteAndRecreateAtSameDate() throws Exception {
        var token = login();
        try (var client = HttpClient.newHttpClient();
                var executor = Executors.newFixedThreadPool(2)) {
            var initial =
                    data(
                            send(
                                    client,
                                    token,
                                    "PUT",
                                    draftPath(today),
                                    Map.of("originalText", "첫 입력")),
                            200);
            var start = new CountDownLatch(1);
            Callable<Integer> firstRequest =
                    () -> {
                        start.await();
                        return send(
                                        client,
                                        token,
                                        "PUT",
                                        draftPath(today),
                                        draftBody(initial, "수정 A"))
                                .statusCode();
                    };
            Callable<Integer> secondRequest =
                    () -> {
                        start.await();
                        return send(
                                        client,
                                        token,
                                        "PUT",
                                        draftPath(today),
                                        draftBody(initial, "수정 B"))
                                .statusCode();
                    };
            var first = executor.submit(firstRequest);
            var second = executor.submit(secondRequest);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
            var latest = data(send(client, token, "GET", path(initial), null), 200);
            assertThat(
                            send(
                                            client,
                                            token,
                                            "DELETE",
                                            path(latest) + "?revision=" + revision(latest),
                                            null)
                                    .statusCode())
                    .isEqualTo(200);
            assertThat(
                            send(
                                            client,
                                            token,
                                            "PUT",
                                            draftPath(today),
                                            draftBody(initial, "삭제 뒤 지연 저장"))
                                    .statusCode())
                    .isEqualTo(409);
            var replacement =
                    data(
                            send(
                                    client,
                                    token,
                                    "PUT",
                                    draftPath(today),
                                    Map.of("originalText", "새 초안")),
                            200);
            assertThat(revision(replacement)).isEqualTo(revision(initial));
            assertThat(
                            send(
                                            client,
                                            token,
                                            "PUT",
                                            draftPath(today),
                                            draftBody(initial, "기존 초안 지연 저장"))
                                    .statusCode())
                    .isEqualTo(409);
            assertThat(
                            data(send(client, token, "GET", path(replacement), null), 200)
                                    .get("originalText")
                                    .asString())
                    .isEqualTo("새 초안");
        }
    }

    @Test
    void documentsTheDraftRecoveryAndPatchNullContract() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var result = send(client, null, "GET", "/v3/api-docs", null);
            assertThat(result.statusCode()).isEqualTo(200);
            var document = json.readTree(result.body());
            assertThat(document.at("/paths/~1api~1v1~1dreams~1incomplete/get/summary").asString())
                    .isEqualTo("미완성 꿈 복구 목록");
            assertThat(
                            document.at("/paths/~1api~1v1~1dreams~1{dreamId}/patch/description")
                                    .asString())
                    .contains("title:null", "최대 20자");
        }
    }

    @Test
    void titleEditsUseHttpNullBlankAndCodePointContract() throws Exception {
        var token = login();
        try (var client = HttpClient.newHttpClient()) {
            var current = complete(client, token, create(client, token));
            long source = current.get("sourceRevision").asLong();
            for (String blank : List.of("", "\u00a0\u2007\u202f")) {
                current =
                        data(
                                send(
                                        client,
                                        token,
                                        "PATCH",
                                        path(current),
                                        Map.of(
                                                "revision",
                                                revision(current),
                                                "title",
                                                "🌙".repeat(20))),
                                200);
                current =
                        data(
                                send(
                                        client,
                                        token,
                                        "PATCH",
                                        path(current),
                                        Map.of("revision", revision(current), "title", blank)),
                                200);
                assertThat(current.get("title").isNull()).isTrue();
                assertThat(current.get("sourceRevision").asLong()).isEqualTo(source);
            }
            var rejected =
                    send(
                            client,
                            token,
                            "PATCH",
                            path(current),
                            Map.of(
                                    "revision",
                                    revision(current),
                                    "originalText",
                                    "변경 원문",
                                    "emotions",
                                    List.of("SAD")));
            assertThat(rejected.statusCode()).isEqualTo(400);
            assertThat(rejected.body()).contains("DREAM_400_5");
            var after = data(send(client, token, "GET", path(current), null), 200);
            assertThat(after.get("originalText").asString()).isEqualTo("원문");
            assertThat(revision(after)).isEqualTo(revision(current));
            assertThat(after.get("sourceRevision").asLong()).isEqualTo(source);
        }
    }

    private TokenResponse login() {
        return tokens.login(
                        users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "테스트"))
                                .getId())
                .response();
    }

    private JsonNode create(HttpClient client, TokenResponse token) throws Exception {
        return data(
                send(
                        client,
                        token,
                        "POST",
                        "/api/v1/dreams",
                        Map.of("dreamedAt", today.toString(), "originalText", "원문")),
                201);
    }

    private JsonNode complete(HttpClient client, TokenResponse token, JsonNode dream)
            throws Exception {
        return data(
                send(
                        client,
                        token,
                        "PUT",
                        path(dream) + "/emotions",
                        Map.of("revision", revision(dream), "emotions", List.of("HAPPY"))),
                200);
    }

    private Map<String, Object> draftBody(JsonNode dream, String text) {
        return Map.of(
                "originalText",
                text,
                "dreamId",
                dream.get("dreamId").asLong(),
                "revision",
                revision(dream));
    }

    private String draftPath(LocalDate date) {
        return "/api/v1/dreams/drafts/" + date;
    }

    private String path(JsonNode dream) {
        return "/api/v1/dreams/" + dream.get("dreamId").asLong();
    }

    private long revision(JsonNode dream) {
        return dream.get("revision").asLong();
    }

    private JsonNode data(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return json.readTree(response.body()).get("data");
    }

    private HttpResponse<String> send(
            HttpClient client, TokenResponse token, String method, String path, Object body)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token.accessToken());
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(
                request.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(
                                                json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
