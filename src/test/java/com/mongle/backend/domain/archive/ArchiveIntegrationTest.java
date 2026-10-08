package com.mongle.backend.domain.archive;

import com.mongle.backend.domain.archive.service.ArchiveService;
import com.mongle.backend.domain.auth.dto.TokenResponse;
import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.dream.analysis.*;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.*;
import com.mongle.backend.domain.dream.image.*;
import com.mongle.backend.domain.dream.repository.DreamRepository;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.dream.story.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** 실제 HTTP 인증·JPA 저장·조회·페이지 경계와 기존 수정/삭제/이미지 URL API 연결을 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:mongle-archive;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"})
@ActiveProfiles("test")
@Import(ArchiveIntegrationTest.Config.class)
class ArchiveIntegrationTest {
    @TestConfiguration
    static class Config {
        @Bean @Primary FakeStore archiveFakeStore() { return new FakeStore(); }
    }

    /** 외부 저장소에 접근하지 않는 테스트 전용 URL 발급기. Archive 조회에서는 호출되지 않아야 한다. */
    static class FakeStore implements ImageAssetStore {
        final AtomicInteger calls = new AtomicInteger();
        public void put(String key, ImagePayload payload) { throw new UnsupportedOperationException(); }
        public void delete(String key) { throw new UnsupportedOperationException(); }
        public DownloadUrl temporaryUrl(String key, Duration lifetime) {
            calls.incrementAndGet();
            return new DownloadUrl("https://images.example.test/mock.png", Instant.now().plusSeconds(60));
        }
    }

    @Value("${local.server.port}") int port;
    @Autowired TokenService tokens;
    @Autowired UserRepository users;
    @Autowired DreamRepository dreamRows;
    @Autowired DreamService dreams;
    @Autowired AnalysisTransactions analyses;
    @Autowired StoredAnalysisRepository analysisRows;
    @Autowired DreamStoryRepository storyRows;
    @Autowired DreamImageRepository imageRows;
    @Autowired TransactionTemplate transactions;
    @Autowired ArchiveService archives;
    @Autowired EntityManagerFactory emf;
    @Autowired FakeStore store;
    final JsonMapper json = JsonMapper.builder().build();
    final LocalDate date = LocalDate.of(2026, 9, 16);
    Long userId;
    TokenResponse token;
    HttpClient client;

    @BeforeEach
    void setUp() {
        userId = users.saveAndFlush(User.create(UUID.randomUUID() + "@archive.test", "기록자")).getId();
        token = tokens.login(userId).response();
        client = HttpClient.newHttpClient();
        store.calls.set(0);
    }

    @AfterEach void closeClient() { client.close(); }

    private DreamResponse completed(LocalDate date) {
        var dream = dreams.create(userId, new DreamCreateRequest(date, "회의에 늦는 꿈 원문"));
        return dreams.complete(userId, dream.dreamId(), new DreamEmotionsRequest(dream.revision(), List.of(DreamEmotion.ANXIOUS)));
    }

    @Test
    void filtersCompletedOwnedRecordsAndPagesAfterDeletedAnchor() throws Exception {
        var newest = completed(date);
        var middle = completed(date.minusDays(1));
        var oldest = completed(date.minusDays(2));
        completed(LocalDate.of(2026, 8, 31));
        dreams.saveDraft(userId, date.plusDays(1), new DreamDraftRequest("초안", null, null));
        dreams.create(userId, new DreamCreateRequest(date.plusDays(2), "감정 선택 전"));
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@archive.test", "타인"));
        var otherDream = dreams.create(other.getId(), new DreamCreateRequest(date.plusDays(3), "타인 원문"));
        dreams.complete(other.getId(), otherDream.dreamId(), new DreamEmotionsRequest(otherDream.revision(), List.of(DreamEmotion.HAPPY)));

        var first = data(get("/api/v1/archives?month=2026-09&size=1"), 200);
        assertThat(first.at("/items/0/dreamId").asLong()).isEqualTo(newest.dreamId());
        assertThat(first.get("hasNext").asBoolean()).isTrue();
        assertThat(first.at("/items/0/analysis").isNull()).isTrue();
        assertThat(first.at("/items/0/image").isNull()).isTrue();
        String cursor = first.get("nextCursor").asString();
        // 이전 페이지 마지막 행이 삭제되어도 커서는 위치 값이므로 다음 기록을 정상 조회한다.
        dreams.delete(userId, newest.dreamId(), newest.revision());
        var next = data(get("/api/v1/archives?month=2026-09&size=2&cursor=" + cursor), 200);
        assertThat(next.get("items").size()).isEqualTo(2);
        assertThat(next.at("/items/0/dreamId").asLong()).isEqualTo(middle.dreamId());
        assertThat(next.at("/items/1/dreamId").asLong()).isEqualTo(oldest.dreamId());
        assertThat(next.get("hasNext").asBoolean()).isFalse();
        assertThat(next.get("nextCursor").isNull()).isTrue();
        assertThat(data(get("/api/v1/archives?date=2026-09-15"), 200).get("items").size()).isEqualTo(1);
        assertThat(data(get("/api/v1/archives?date=2026-09-16"), 200).get("items").isEmpty()).isTrue();
        // 필터를 생략하면 다른 달의 내 완성 기록도 포함한다.
        assertThat(data(get("/api/v1/archives"), 200).get("items").size()).isEqualTo(3);
    }

    @Test
    void leapMonthAndEmptyResultsUseDreamDateInsteadOfCreationTime() throws Exception {
        completed(LocalDate.of(2024, 2, 29));
        completed(LocalDate.of(2024, 3, 1));
        var february = data(get("/api/v1/archives?month=2024-02"), 200);
        assertThat(february.get("items").size()).isEqualTo(1);
        assertThat(february.at("/items/0/dreamedAt").asString()).isEqualTo("2024-02-29");
        var empty = data(get("/api/v1/archives?month=2024-01"), 200);
        assertThat(empty.get("items").isEmpty()).isTrue();
        assertThat(empty.get("hasNext").asBoolean()).isFalse();
        assertThat(empty.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void paginatesOldestFirstAfterDeletedAnchorAndAppliesDateFilters() throws Exception {
        var oldest = completed(date.minusDays(2));
        var middle = completed(date.minusDays(1));
        var newest = completed(date);
        var previousMonth = completed(LocalDate.of(2026, 8, 31));

        var first = data(get("/api/v1/archives?month=2026-09&sort=OLDEST&size=1"), 200);
        assertThat(first.at("/items/0/dreamId").asLong()).isEqualTo(oldest.dreamId());
        assertThat(first.get("hasNext").asBoolean()).isTrue();
        String cursor = first.get("nextCursor").asString();
        // ASC 조회에서도 실제 마지막 행을 다시 찾지 않고 경계보다 뒤의 기록부터 이어서 읽는다.
        dreams.delete(userId, oldest.dreamId(), oldest.revision());
        var second = data(get("/api/v1/archives?month=2026-09&sort=OLDEST&size=1&cursor=" + cursor), 200);
        assertThat(second.at("/items/0/dreamId").asLong()).isEqualTo(middle.dreamId());
        assertThat(second.get("hasNext").asBoolean()).isTrue();
        var last = data(get("/api/v1/archives?month=2026-09&sort=OLDEST&size=1&cursor="
                + second.get("nextCursor").asString()), 200);
        assertThat(last.at("/items/0/dreamId").asLong()).isEqualTo(newest.dreamId());
        assertThat(last.get("hasNext").asBoolean()).isFalse();
        assertThat(last.get("nextCursor").isNull()).isTrue();

        var all = data(get("/api/v1/archives?sort=OLDEST"), 200);
        assertThat(all.get("items").size()).isEqualTo(3);
        assertThat(all.at("/items/0/dreamId").asLong()).isEqualTo(previousMonth.dreamId());
        assertThat(data(get("/api/v1/archives?sort=LATEST"), 200).at("/items/0/dreamId").asLong())
                .isEqualTo(newest.dreamId());
        var day = data(get("/api/v1/archives?date=2026-09-16&sort=OLDEST"), 200);
        assertThat(day.get("items").size()).isEqualTo(1);
        assertThat(day.at("/items/0/dreamId").asLong()).isEqualTo(newest.dreamId());
    }

    @Test
    void rejectsInvalidFiltersAndCursorsIncludingChangedScope() throws Exception {
        var newest = completed(date);
        completed(date.minusDays(1));
        for (String query : List.of("month=2026-13", "month=2026-9", "date=2026-02-30", "month=2026-09&date=2026-09-16")) {
            var response = get("/api/v1/archives?" + query);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("ARCHIVE_400_1");
        }
        String cursor = data(get("/api/v1/archives?month=2026-09&size=1"), 200).get("nextCursor").asString();
        String oldestCursor = data(get("/api/v1/archives?month=2026-09&sort=OLDEST&size=1"), 200)
                .get("nextCursor").asString();
        for (String query : List.of("cursor=invalid", "month=2026-08&cursor=" + cursor, "cursor=",
                "month=2026-09&sort=OLDEST&cursor=" + cursor, "month=2026-09&cursor=" + oldestCursor)) {
            var response = get("/api/v1/archives?" + query);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("ARCHIVE_400_2");
        }
        assertThat(get("/api/v1/archives?size=51").statusCode()).isEqualTo(400);
        for (String sort : List.of("", "latest", "UNKNOWN")) {
            var response = get("/api/v1/archives?sort=" + sort);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("ARCHIVE_400_4");
        }
        // 배포 전에 발급된 v1 최신순 커서는 계속 사용 가능하지만 오래된순에는 사용하지 못한다.
        String legacy = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("v1|month:2026-09|2026-09-16|" + newest.dreamId()).getBytes(StandardCharsets.UTF_8));
        assertThat(data(get("/api/v1/archives?month=2026-09&cursor=" + legacy), 200).get("items").size())
                .isEqualTo(1);
        var incompatible = get("/api/v1/archives?month=2026-09&sort=OLDEST&cursor=" + legacy);
        assertThat(incompatible.statusCode()).isEqualTo(400);
        assertThat(incompatible.body()).contains("ARCHIVE_400_2");
    }

    @Test
    void detailRejectsAnonymousOtherDraftAndDeletedRecords() throws Exception {
        var dream = completed(date);
        var draft = dreams.saveDraft(userId, date.plusDays(1), new DreamDraftRequest("초안", null, null));
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@archive.test", "타인"));
        var foreign = dreams.create(other.getId(), new DreamCreateRequest(date, "비공개"));
        var otherDream = dreams.complete(other.getId(), foreign.dreamId(), new DreamEmotionsRequest(foreign.revision(), List.of(DreamEmotion.HAPPY)));
        assertThat(client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/archives"))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        for (Long id : List.of(draft.dreamId(), otherDream.dreamId(), Long.MAX_VALUE)) {
            var response = get("/api/v1/archives/" + id);
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).contains("ARCHIVE_404_1");
        }
        var detail = data(get("/api/v1/archives/" + dream.dreamId()), 200);
        assertThat(detail.get("originalText").asString()).isEqualTo("회의에 늦는 꿈 원문");
        assertThat(detail.at("/emotions/0").asString()).isEqualTo("ANXIOUS");
        dreams.delete(userId, dream.dreamId(), dream.revision());
        assertThat(get("/api/v1/archives/" + dream.dreamId()).statusCode()).isEqualTo(404);
    }

    @Test
    void showsPreservedGenerationResultsAndConnectsEditDeleteAndImageUrl() throws Exception {
        var dream = completed(date);
        var ids = generations(dream);
        // 실패한 재생성 시에도 이전 성공 결과의 이미지 ID로 URL을 받을 수 있다.
        transactions.executeWithoutResult(tx -> {
            var image = imageRows.findById(ids[2]).orElseThrow();
            image.start(image.getSourceRevision(), image.getStoryHash(), "soft", null, Instant.now());
            image.fail("CALL_FAILED");
        });
        var response = get("/api/v1/archives/" + dream.dreamId());
        var detail = data(response, 200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(detail.at("/dream/displayKeywords/0").asString()).isEqualTo("계단");
        assertThat(detail.at("/dream/image/hasResult").asBoolean()).isTrue();
        assertThat(detail.at("/dream/image/hasPreviousResult").asBoolean()).isTrue();
        assertThat(detail.at("/dream/image/status").asString()).isEqualTo("FAILED");
        assertThat(detail.at("/dream/image/storyChanged").asBoolean()).isFalse();
        assertThat(response.body()).doesNotContain("private-fixture", "storageKey", "resultJson");
        assertThat(store.calls).hasValue(0);
        assertThat(data(get("/api/v1/images/" + ids[2] + "/download-url"), 200).get("url").asString())
                .isEqualTo("https://images.example.test/mock.png");
        assertThat(store.calls).hasValue(1);

        // 원문이 같아도 실제 이야기 결과가 바뀌면 이전 이미지의 출처가 달라졌다고 표시한다.
        String changedStory = new StoryValidator().encode(new StoryResult(List.of(
                new StoryResult.Section(1, StoryResult.Kind.SCENE, 1, "다시 생성한 회의 이야기"))));
        transactions.executeWithoutResult(tx -> {
            var story = storyRows.findById(ids[1]).orElseThrow();
            story.start(story.getSourceRevision(), Instant.now(), Duration.ofMinutes(2));
            story.finish(changedStory);
        });
        var regenerated = data(get("/api/v1/archives/" + dream.dreamId()), 200);
        assertThat(regenerated.at("/dream/image/storyChanged").asBoolean()).isTrue();
        assertThat(regenerated.at("/dream/image/sourceChanged").asBoolean()).isFalse();

        var patched = send("PATCH", "/api/v1/dreams/" + dream.dreamId(), Map.of(
                "revision", detail.at("/dream/revision").asLong(), "title", "내 제목", "emotions", List.of("SAD")));
        assertThat(patched.statusCode()).isEqualTo(200);
        var edited = data(get("/api/v1/archives/" + dream.dreamId()), 200);
        assertThat(edited.at("/dream/title").asString()).isEqualTo("내 제목");
        assertThat(edited.at("/dream/edited").asBoolean()).isTrue();
        assertThat(edited.at("/dream/analysis/sourceChanged").asBoolean()).isTrue();
        assertThat(edited.at("/dream/story/sourceChanged").asBoolean()).isTrue();
        assertThat(edited.at("/dream/image/sourceChanged").asBoolean()).isTrue();
        assertThat(edited.at("/dream/displayKeywords/0").asString()).isEqualTo("계단");
        assertThat(data(get("/api/v1/archives?date=2026-09-16"), 200).at("/items/0/edited").asBoolean()).isTrue();
        // 읽은 최신 revision으로 기존 삭제 API를 호출하면 Archive에서 사라지고 분석 결과는 보존된다.
        assertThat(send("DELETE", "/api/v1/dreams/" + dream.dreamId() + "?revision=" + edited.at("/dream/revision").asLong(), null).statusCode()).isEqualTo(200);
        assertThat(data(get("/api/v1/archives"), 200).get("items").isEmpty()).isTrue();
        assertThat(data(get("/api/v1/analyses/" + ids[0]), 200).get("sourceDeleted").asBoolean()).isTrue();
    }

    @Test
    void numberOfQueriesStaysBoundedAsCardsIncrease() throws Exception {
        for (int i = 0; i < 3; i++) generations(completed(date.minusDays(i)));
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        archives.list(userId, "2026-09", null, null, null, 1);
        long single = stats.getPrepareStatementCount();
        stats.clear();
        var page = archives.list(userId, "2026-09", null, null, null, 20);
        assertThat(page.items()).hasSize(3);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(single).isLessThanOrEqualTo(4);
    }

    @Test
    void exposesArchiveEndpointsInOpenApi() throws Exception {
        var document = json.readTree(get("/v3/api-docs").body());
        assertThat(document.at("/paths/~1api~1v1~1archives/get/summary").asString()).isEqualTo("Archive 목록 조회");
        assertThat(document.at("/paths/~1api~1v1~1archives~1{dreamId}/get/summary").asString()).isEqualTo("Archive 상세 조회");
        var parameters = document.at("/paths/~1api~1v1~1archives/get/parameters");
        boolean found = false;
        for (int i = 0; i < parameters.size(); i++) {
            var parameter = parameters.get(i);
            if (!parameter.get("name").asString().equals("sort")) continue;
            found = true;
            assertThat(parameter.at("/schema/default").asString()).isEqualTo("LATEST");
            assertThat(parameter.at("/schema/enum/0").asString()).isEqualTo("LATEST");
            assertThat(parameter.at("/schema/enum/1").asString()).isEqualTo("OLDEST");
        }
        assertThat(found).isTrue();
    }

    /** 생성기를 호출하지 않고 이미 생성·저장된 결과를 준비한다. 실제 LINER·이미지 Provider는 사용하지 않는다. */
    private Long[] generations(DreamResponse dream) throws Exception {
        var reservation = analyses.begin(userId, dream.dreamId(), dream.revision(), true);
        var result = new StructureResult("분석 제목", List.of("계단", "회의"),
                List.of(new StructureResult.Element("e1", DreamEntityType.PLACE, "회사", "장소")),
                List.of(new StructureResult.Scene(1, "회의 장면", false, List.of("e1"))));
        analyses.finish(reservation.input(), result);
        String output = new StoryValidator().encode(new StoryResult(List.of(
                new StoryResult.Section(1, StoryResult.Kind.SCENE, 1, "회의 장면 이야기"))));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output.getBytes(StandardCharsets.UTF_8)));
        return transactions.execute(tx -> {
            var analysis = analysisRows.findById(reservation.response().analysisId()).orElseThrow();
            var current = dreamRows.findById(dream.dreamId()).orElseThrow();
            var story = DreamStory.create(analysis);
            story.start(current.getSourceRevision(), Instant.now(), Duration.ofMinutes(2));
            story.finish(output);
            storyRows.saveAndFlush(story);
            var image = DreamImage.create(analysis);
            image.start(current.getSourceRevision(), hash, "soft", null, Instant.now());
            image.finish("private-fixture-" + dream.dreamId(), new ImagePayload(new byte[]{1}, "image/png", 1, 1));
            imageRows.saveAndFlush(image);
            return new Long[]{analysis.getId(), story.getId(), image.getId()};
        });
    }

    private HttpResponse<String> get(String path) throws Exception { return send("GET", path, null); }

    private HttpResponse<String> send(String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token.accessToken());
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return json.readTree(response.body()).get("data");
    }
}
