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
                                            List.of("HAPPY", "CALM"))),
                            200);
            assertThat(completed.get("recordStatus").asString()).isEqualTo("COMPLETED");
            assertThat(completed.get("emotions").size()).isEqualTo(2);
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
    void deletesRawDreamEmotionsAndLegacyAnalysisGraph() throws Exception {
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
            assertThat(jdbc.queryForObject("select count(*) from dream_scenes where id=?",
                    Long.class, graph.getFirst())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from dream_entities where id=?",
                    Long.class, graph.getLast())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from dream_scene_entities where dream_scene_id=?",
                    Long.class, graph.getFirst())).isZero();
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
            for (String name : List.of("DreamEmotionsRequest", "DreamUpdateRequest")) {
                var emotions = document.path("components").path("schemas").path(name)
                        .path("properties").path("emotions");
                assertThat(emotions.path("minItems").asInt()).isEqualTo(1);
                assertThat(emotions.path("maxItems").asInt()).isEqualTo(2);
                assertThat(emotions.path("uniqueItems").asBoolean()).isTrue();
                assertThat(emotions.at("/items/enum").valueStream().map(JsonNode::asString).toList())
                        .containsExactlyInAnyOrder("HAPPY", "CALM", "EXCITED", "SAD", "ANXIOUS", "ANGRY", "CONFUSED");
            }
            // 읽기 응답은 기존 3개 감정도 표현해야 하므로 요청 제한을 적용하지 않는다.
            assertThat(document.at("/components/schemas/DreamResponse/properties/emotions/maxItems").isMissingNode())
                    .isTrue();
            assertThat(document.at("/paths/~1api~1v1~1dreams~1{dreamId}~1emotions/put/description").asString())
                    .contains("1~2개").doesNotContain("1~3개");
            assertThat(document.at("/paths/~1api~1v1~1dreams~1{dreamId}/patch/description").asString())
                    .contains("1~2개").doesNotContain("1~3개");
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
            var changed =
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
            assertThat(changed.statusCode()).isEqualTo(200);
            var after = data(send(client, token, "GET", path(current), null), 200);
            assertThat(after.get("originalText").asString()).isEqualTo("변경 원문");
            assertThat(after.at("/emotions/0").asString()).isEqualTo("SAD");
            assertThat(after.get("edited").asBoolean()).isTrue();
            assertThat(revision(after)).isGreaterThan(revision(current));
            assertThat(after.get("sourceRevision").asLong()).isEqualTo(source + 1);
            var noOp = data(send(client, token, "PATCH", path(after),
                    Map.of("revision", revision(after), "originalText", "변경 원문", "emotions", List.of("SAD"))), 200);
            assertThat(revision(noOp)).isEqualTo(revision(after));
            // 오래된 수정 요청은 새 감정과 원문을 덮어쓰지 못한다.
            assertThat(send(client, token, "PATCH", path(after),
                    Map.of("revision", revision(current), "emotions", List.of("HAPPY"))).statusCode()).isEqualTo(409);
        }
    }

    @Test
    void invalidEmotionRequestsDoNotCompleteOrPartiallyUpdateTheRecord() throws Exception {
        var token = login();
        List<Object> invalid = Arrays.asList(
                List.of(), List.of("HAPPY", "CALM", "EXCITED"),
                List.of("HAPPY", "CALM", "EXCITED", "SAD"),
                List.of("HAPPY", "HAPPY"), Arrays.asList("HAPPY", null),
                List.of("UNKNOWN"), null);
        try (var client = HttpClient.newHttpClient()) {
            var created = create(client, token);
            // 저장 직후 응답은 DB DATETIME(6)보다 정밀할 수 있다. 저장된 상태끼리 비교한다.
            var pending = data(send(client, token, "GET", path(created), null), 200);
            long id = pending.get("dreamId").asLong();
            for (Object emotions : invalid) {
                var body = new HashMap<String, Object>();
                body.put("revision", revision(pending));
                body.put("emotions", emotions);
                var response = send(client, token, "PUT", path(pending) + "/emotions", body);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
                if (emotions != null && !emotions.equals(List.of("UNKNOWN"))) {
                    assertThat(json.readTree(response.body()).path("code").asString())
                            .isEqualTo("DREAM_400_4");
                    assertThat(json.readTree(response.body()).path("message").asString())
                            .contains("1~2개");
                }
                assertThat(data(send(client, token, "GET", path(pending), null), 200))
                        .isEqualTo(pending);
                assertThat(jdbc.queryForObject(
                        "select count(*) from dream_generation_jobs where dream_id=?", Long.class, id))
                        .isZero();
            }
            var completed = data(send(client, token, "PUT", path(pending) + "/emotions",
                    Map.of("revision", revision(pending), "emotions", List.of("HAPPY", "CALM"))), 200);
            assertThat(completed.path("emotions").valueStream().map(JsonNode::asString).toList())
                    .containsExactlyInAnyOrder("HAPPY", "CALM");
            completed = data(send(client, token, "GET", path(completed), null), 200);
            var jobBefore = jdbc.queryForMap("select * from dream_generation_jobs where dream_id=?", id);
            for (Object emotions : invalid) {
                var body = new HashMap<String, Object>();
                body.put("revision", revision(completed));
                body.put("originalText", "저장되면 안 되는 원문");
                body.put("title", "저장되면 안 되는 제목");
                body.put("emotions", emotions);
                var response = send(client, token, "PATCH", path(completed), body);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
                if (!Objects.equals(emotions, List.of("UNKNOWN"))) {
                    assertThat(json.readTree(response.body()).path("code").asString())
                            .isEqualTo("DREAM_400_4");
                }
                assertThat(data(send(client, token, "GET", path(completed), null), 200))
                        .isEqualTo(completed);
                assertThat(jdbc.queryForMap("select * from dream_generation_jobs where dream_id=?", id))
                        .isEqualTo(jobBefore);
            }
            var reversed = data(send(client, token, "PATCH", path(completed),
                    Map.of("revision", revision(completed), "emotions", List.of("CALM", "HAPPY"))), 200);
            assertThat(reversed).isEqualTo(completed);
            var single = data(send(client, token, "PATCH", path(completed),
                    Map.of("revision", revision(completed), "emotions", List.of("SAD"))), 200);
            assertThat(single.path("emotions").valueStream().map(JsonNode::asString).toList())
                    .containsExactly("SAD");
            assertThat(single.path("sourceRevision").asLong())
                    .isEqualTo(completed.path("sourceRevision").asLong() + 1);
            var pair = data(send(client, token, "PATCH", path(single),
                    Map.of("revision", revision(single), "emotions", List.of("SAD", "CALM"))), 200);
            assertThat(pair.path("emotions").valueStream().map(JsonNode::asString).toList())
                    .containsExactlyInAnyOrder("SAD", "CALM");
        }
    }

    @Test
    void preservesLegacyThreeEmotionRecordsUnlessExplicitlyReplacingEmotions() throws Exception {
        var token = login();
        try (var client = HttpClient.newHttpClient()) {
            var completed = complete(client, token, create(client, token));
            long id = completed.get("dreamId").asLong();
            // 이전 정책으로 저장된 행을 재현한다. 운영 데이터 정리나 신규 검증 우회 경로가 아니다.
            for (String emotion : List.of("CALM", "EXCITED")) {
                jdbc.update("insert into dream_emotions(dream_id,emotion) values(?,?)", id, emotion);
            }
            var legacy = data(send(client, token, "GET", path(completed), null), 200);
            assertThat(legacy.path("emotions").valueStream().map(JsonNode::asString).toList())
                    .containsExactlyInAnyOrder("HAPPY", "CALM", "EXCITED");
            var archive = data(send(client, token, "GET", "/api/v1/archives/" + id, null), 200);
            assertThat(archive.path("emotions").valueStream().map(JsonNode::asString).toList())
                    .containsExactlyInAnyOrder("HAPPY", "CALM", "EXCITED");
            var titled = data(send(client, token, "PATCH", path(legacy),
                    Map.of("revision", revision(legacy), "title", "직접 붙인 제목")), 200);
            assertThat(titled.path("emotions")).isEqualTo(legacy.path("emotions"));
            assertThat(titled.path("sourceRevision")).isEqualTo(legacy.path("sourceRevision"));
            var edited = data(send(client, token, "PATCH", path(titled),
                    Map.of("revision", revision(titled), "originalText", "수정한 원문")), 200);
            assertThat(edited.path("emotions")).isEqualTo(legacy.path("emotions"));
            edited = data(send(client, token, "GET", path(edited), null), 200);
            var rejected = send(client, token, "PATCH", path(edited),
                    Map.of("revision", revision(edited), "emotions", List.of("HAPPY", "CALM", "EXCITED")));
            assertThat(rejected.statusCode()).isEqualTo(400);
            assertThat(json.readTree(rejected.body()).path("code").asString()).isEqualTo("DREAM_400_4");
            assertThat(data(send(client, token, "GET", path(edited), null), 200)).isEqualTo(edited);
            var reduced = data(send(client, token, "PATCH", path(edited),
                    Map.of("revision", revision(edited), "emotions", List.of("HAPPY", "CALM"))), 200);
            assertThat(reduced.path("emotions").valueStream().map(JsonNode::asString).toList())
                    .containsExactlyInAnyOrder("HAPPY", "CALM");
            assertThat(reduced.path("sourceRevision").asLong())
                    .isEqualTo(edited.path("sourceRevision").asLong() + 1);
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
