package com.mongle.backend.domain.world.folder;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.auth.dto.TokenResponse;
import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.dream.entity.*;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.repository.DreamRepository;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.error.BusinessException;
import com.mongle.backend.global.error.ErrorCode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:world-folders;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@ActiveProfiles("test")
class WorldFolderIntegrationTest {
    @Autowired WorldFolderService folders;
    @Autowired UserRepository users;
    @Autowired DreamRepository dreams;
    @Autowired DreamService dreamService;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired EntityManagerFactory emf;
    @Value("${local.server.port}") int port;
    final JsonMapper json = JsonMapper.builder().build();
    Long user;

    @BeforeEach
    void setup() { user = newUser(); }

    @Test
    void httpLifecycleAndPagingPreserveEmptyFolderAndSourceRecords() throws Exception {
        var token = tokens.login(user).response();
        var a = data(send(token, "POST", "/api/v1/worlds", Map.of("name", "바다")), 201);
        long id = a.get("worldId").asLong();
        assertThat(a.get("revision").asLong()).isZero();
        assertThat(a.get("selectedDreamCount").asLong()).isZero();
        var created = send(token, "POST", "/api/v1/worlds", Map.of("name", "바다"));
        assertThat(created.headers().firstValue("Location")).hasValue("/api/v1/worlds/" + data(created, 201).get("worldId").asLong());
        assertThat(created.headers().firstValue("Cache-Control")).hasValue("no-store");
        var page = data(send(token, "GET", "/api/v1/worlds?size=1", null), 200);
        assertThat(page.get("hasNext").asBoolean()).isTrue();
        var next = data(send(token, "GET", "/api/v1/worlds?size=1&beforeId=" + page.get("nextBeforeId").asLong(), null), 200);
        assertThat(next.at("/items/0/worldId").asLong()).isEqualTo(id);
        assertThat(next.get("hasNext").asBoolean()).isFalse();
        var renamed = data(send(token, "PATCH", path(id), Map.of("name", "새 폴더", "revision", 0)), 200);
        assertThat(renamed.get("revision").asLong()).isEqualTo(1);
        var dream = completed(user, 0);
        var selected = data(send(token, "PUT", path(id) + "/dreams/" + dream, Map.of("revision", 1)), 200);
        assertThat(selected.get("selectedDreamCount").asLong()).isEqualTo(1);
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", " ", "\t\n", "\u00a0\u3000"})
    void rejectsMissingOrUnicodeBlankName(String name) {
        error(() -> folders.create(user, name), WorldFolderErrorCode.INVALID_NAME);
    }

    @Test
    void countsSupplementaryUnicodeCharactersAndPreservesInput() {
        var name = "🌙".repeat(100);
        assertThat(folders.create(user, name).name()).isEqualTo(name);
        error(() -> folders.create(user, name + "🌙"), WorldFolderErrorCode.INVALID_NAME);
        assertThat(folders.create(user, "  밤의 꿈  ").name()).isEqualTo("  밤의 꿈  ");
    }

    @Test
    void noOpMutationsPreserveVersionButOldRevisionAlwaysConflicts() {
        var folder = folders.create(user, "꿈");
        var dream = completed(user, 0);
        var selected = folders.select(user, folder.worldId(), dream, 0L);
        var before = folders.get(user, folder.worldId());
        assertThat(folders.select(user, folder.worldId(), dream, selected.revision())).isEqualTo(before);
        assertThat(folders.rename(user, folder.worldId(), new WorldFolderUpdateRequest("꿈", selected.revision()))).isEqualTo(before);
        error(() -> folders.select(user, folder.worldId(), dream, 0L), WorldFolderErrorCode.VERSION_CONFLICT);
        error(() -> folders.rename(user, folder.worldId(), new WorldFolderUpdateRequest("꿈", 0L)), WorldFolderErrorCode.VERSION_CONFLICT);
    }

    @Test
    void hidesForeignFoldersAndDreamsAndRejectsIncompleteRecords() {
        var folder = folders.create(user, "내 폴더");
        var other = newUser();
        for (long id : List.of(folder.worldId(), Long.MAX_VALUE)) {
            error(() -> folders.get(other, id), WorldFolderErrorCode.NOT_FOUND);
            error(() -> folders.rename(other, id, new WorldFolderUpdateRequest("변경", 0L)), WorldFolderErrorCode.NOT_FOUND);
            error(() -> folders.select(other, id, 1L, 0L), WorldFolderErrorCode.NOT_FOUND);
        }
        var foreign = completed(other, 0);
        for (long dream : List.of(foreign, Long.MAX_VALUE)) {
            error(() -> folders.select(user, folder.worldId(), dream, 0L), DreamErrorCode.NOT_FOUND);
        }
        for (boolean draft : List.of(true, false)) {
            var incomplete = transactions.execute(s -> dreams.saveAndFlush(draft
                    ? Dream.draft(users.findById(user).orElseThrow(), "", LocalDate.of(2020, 1, draft ? 1 : 2))
                    : Dream.create(users.findById(user).orElseThrow(), "원문", LocalDate.of(2020, 1, 2))).getId());
            error(() -> folders.select(user, folder.worldId(), incomplete, 0L), WorldFolderErrorCode.DREAM_NOT_COMPLETED);
        }
        assertThat(folders.get(user, folder.worldId()).selectedDreamCount()).isZero();
        assertThat(folders.list(other, null, 20).items()).isEmpty();
    }

    @Test
    void httpRejectsUnauthenticatedUnonboardedInvalidIdsAndMissingRevisions() throws Exception {
        var token = tokens.login(user).response();
        var id = folders.create(user, "폴더").worldId();
        assertThat(send(null, "GET", "/api/v1/worlds", null).statusCode()).isEqualTo(401);
        var newcomer = tokens.login(users.saveAndFlush(User.register(UUID.randomUUID() + "@test.com")).getId()).response();
        assertThat(send(newcomer, "GET", "/api/v1/worlds", null).statusCode()).isEqualTo(403);
        assertThat(send(token, "GET", path(-1L), null).statusCode()).isEqualTo(400);
        assertThat(send(token, "PATCH", path(id), Map.of("name", "이름")).statusCode()).isEqualTo(400);
        assertThat(send(token, "PUT", path(id) + "/dreams/1", Map.of()).statusCode()).isEqualTo(400);
        for (String query : List.of("size=0", "size=51", "beforeId=0", "size=x"))
            assertThat(send(token, "GET", "/api/v1/worlds?" + query, null).statusCode()).isEqualTo(400);
    }




    @Test
    void deletingDreamUpdatesOnlyRelatedFoldersAndInvalidatesCursor() {
        long a = completed(user, 1), b = completed(user, 0);
        var first = folders.create(user, "A");
        var second = folders.create(user, "B");
        var untouched = folders.create(user, "빈 폴더");
        folders.select(user, first.worldId(), a, 0L);
        folders.select(user, first.worldId(), b, 1L);
        folders.select(user, second.worldId(), a, 0L);
        dreamService.delete(user, a, 0L);
        assertThat(folders.get(user, first.worldId()).selectedDreamCount()).isEqualTo(1);
        assertThat(folders.get(user, first.worldId()).revision()).isEqualTo(3);
        assertThat(folders.get(user, second.worldId()).selectedDreamCount()).isZero();
        assertThat(folders.get(user, second.worldId()).revision()).isEqualTo(2);
        assertThat(folders.get(user, untouched.worldId()).revision()).isZero();
        assertThat(dreams.existsById(b)).isTrue();
    }

    @Test
    void rollbackRestoresDreamAndSelectionsCountsAndRevisions() {
        long dream = completed(user, 0);
        var folder = folders.create(user, "보존");
        folders.select(user, folder.worldId(), dream, 0L);
        var before = folders.get(user, folder.worldId());
        transactions.executeWithoutResult(status -> {
            dreamService.delete(user, dream, 0L);
            assertThat(folders.get(user, folder.worldId()).selectedDreamCount()).isZero();
            status.setRollbackOnly();
        });
        assertThat(folders.get(user, folder.worldId())).isEqualTo(before);
        assertThat(dreams.existsById(dream)).isTrue();
    }

    @Test
    void selectionInsertFailureRollsBackCountAndRevision() {
        var folder = folders.create(user, "실패");
        long dream = completed(user, 0);
        jdbc.execute("alter table world_selections add constraint reject_world_selection check(dream_id<>" + dream + ")");
        try {
            assertThatThrownBy(() -> folders.select(user, folder.worldId(), dream, 0L)).isInstanceOf(RuntimeException.class);
            assertThat(folders.get(user, folder.worldId()).selectedDreamCount()).isZero();
            assertThat(folders.get(user, folder.worldId()).revision()).isZero();
        } finally { jdbc.execute("alter table world_selections drop constraint reject_world_selection"); }
    }

    @Test
    void concurrentDuplicateSelectionHasOneSuccessAndOneRevisionConflict() throws Exception {
        var folder = folders.create(user, "경합");
        var dream = completed(user, 0);
        var outcomes = race(() -> folders.select(user, folder.worldId(), dream, 0L),
                () -> folders.select(user, folder.worldId(), dream, 0L));
        assertThat(outcomes).containsExactlyInAnyOrder("OK", WorldFolderErrorCode.VERSION_CONFLICT.getCode());
        assertThat(folders.get(user, folder.worldId()).selectedDreamCount()).isEqualTo(1);
    }

    @Test
    void concurrentRenameAndSelectionCannotOverwriteEachOther() throws Exception {
        var folder = folders.create(user, "경합");
        var dream = completed(user, 0);
        var outcomes = race(() -> folders.rename(user, folder.worldId(), new WorldFolderUpdateRequest("새 이름", 0L)),
                () -> folders.select(user, folder.worldId(), dream, 0L));
        assertThat(outcomes).containsExactlyInAnyOrder("OK", WorldFolderErrorCode.VERSION_CONFLICT.getCode());
        var result = folders.get(user, folder.worldId());
        assertThat(result.revision()).isEqualTo(1);
        assertThat(result.name().equals("새 이름") ? result.selectedDreamCount() : 1L - result.selectedDreamCount()).isZero();
    }

    @Test
    void concurrentDreamDeleteAndSelectionNeverLeaveDanglingRelation() throws Exception {
        var folder = folders.create(user, "경합");
        var dream = completed(user, 0);
        var outcomes = race(() -> dreamService.delete(user, dream, 0L),
                () -> folders.select(user, folder.worldId(), dream, 0L));
        assertThat(outcomes).allMatch(s -> s.equals("OK") || s.equals(DreamErrorCode.NOT_FOUND.getCode()));
        assertThat(dreams.existsById(dream)).isFalse();
        assertThat(folders.get(user, folder.worldId()).selectedDreamCount()).isZero();
    }



    Long newUser() { return users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "폴더검증")).getId(); }

    Long completed(Long owner, int daysAgo) {
        return transactions.execute(s -> {
            var dream = Dream.create(users.findById(owner).orElseThrow(), "바다의 꿈", LocalDate.now().minusDays(daysAgo));
            dream.complete(List.of(DreamEmotion.HAPPY));
            return dreams.saveAndFlush(dream).getId();
        });
    }

    void error(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    List<String> race(Runnable a, Runnable b) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Future<String>>();
            for (var action : List.of(a, b)) tasks.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("경합 시작 실패");
                try { action.run(); return "OK"; }
                catch (BusinessException e) { return e.getErrorCode().getCode(); }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(tasks.getFirst().get(15, TimeUnit.SECONDS), tasks.getLast().get(15, TimeUnit.SECONDS));
        }
    }

    String path(Long id) { return "/api/v1/worlds/" + id; }

    JsonNode data(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return json.readTree(response.body()).get("data");
    }

    HttpResponse<String> send(TokenResponse token, String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token.accessToken());
        if (body != null) request.header("Content-Type", "application/json");
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
