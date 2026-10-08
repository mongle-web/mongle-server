package com.mongle.backend.domain.dream.generation;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;
import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.dream.analysis.*;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.entity.DreamRecordStatus;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.gateway.DreamAiProperties;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.dream.story.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:mongle-generation;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
@Import(DreamGenerationIntegrationTest.Config.class)
class DreamGenerationIntegrationTest {
    private static final String STRUCTURE = """
            {"generatedTitle":"바다 위를 날다","displayKeywords":["바다"],"elements":[],
             "scenes":[{"sequence":1,"content":"바다 위를 날았다",
                        "disconnectedFromPrevious":false,"elementKeys":[]}]}
            """;
    private static final String STORY = """
            {"sections":[{"sequence":1,"kind":"SCENE","sceneSequence":1,
                          "content":"바다 위를 날았다."}]}
            """;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeStructure fakeStructure() { return new FakeStructure(); }

        @Bean
        @Primary
        FakeStory fakeStory() { return new FakeStory(); }
    }

    static class FakeStructure implements StructureGenerator {
        final AtomicInteger calls = new AtomicInteger();
        volatile CompletableFuture<String> result;
        volatile boolean enabled = true;

        @Override
        public boolean available() { return enabled; }

        @Override
        public CompletableFuture<String> generate(Input input) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.incrementAndGet();
            return result;
        }
    }

    static class FakeStory implements StoryGenerator {
        final AtomicInteger calls = new AtomicInteger();
        volatile CompletableFuture<String> result;

        @Override
        public CompletableFuture<String> generate(Input input) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(input.scenes()).hasSize(1);
            calls.incrementAndGet();
            return result;
        }
    }

    @Autowired DreamService dreams;
    @Autowired DreamGenerationTransactions transactions;
    @Autowired DreamGenerationJobRepository jobs;
    @Autowired DreamStructureService analysis;
    @Autowired AnalysisTransactions analysisTransactions;
    @Autowired DreamStoryService story;
    @Autowired UserRepository users;
    @Autowired FakeStructure structureGenerator;
    @Autowired FakeStory storyGenerator;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokens;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Clock authClock;
    @Value("${local.server.port}") int port;

    private Long user;
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private DreamGenerationWorker worker;

    @BeforeEach
    void setUp() {
        jobs.deleteAll();
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "테스터")).getId();
        structureGenerator.calls.set(0);
        structureGenerator.enabled = true;
        structureGenerator.result = CompletableFuture.completedFuture(STRUCTURE);
        storyGenerator.calls.set(0);
        storyGenerator.result = CompletableFuture.completedFuture(STORY);
        worker = newWorker();
    }

    @AfterEach
    void tearDown() throws Exception {
        structureGenerator.result.complete(STRUCTURE);
        storyGenerator.result.complete(STORY);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (processingResults() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        worker.close();
        jobs.deleteAll();
    }

    private long processingResults() {
        return jdbc.queryForObject("select count(*) from dream_analyses where status='PROCESSING'", Long.class)
                + jdbc.queryForObject("select count(*) from dream_stories where status='PROCESSING'", Long.class);
    }

    private DreamGenerationWorker newWorker() {
        return new DreamGenerationWorker(
                transactions, analysis, story,
                new DreamGenerationProperties(false, Duration.ofSeconds(2)),
                new DreamAiProperties(4), authClock);
    }

    private DreamResponse completed(Long owner, LocalDate date) {
        var dream = dreams.create(owner, new DreamCreateRequest(date, "바다 위를 날았다"));
        return dreams.complete(owner, dream.dreamId(),
                new DreamEmotionsRequest(dream.revision(), List.of(DreamEmotion.HAPPY)));
    }

    private DreamResponse completed() { return completed(user, today); }

    private DreamGenerationResponse state(DreamResponse dream) {
        return transactions.get(user, dream.dreamId());
    }

    private void pumpUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            worker.poll();
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).as("자동 생성 상태가 제한 시간 내 전환되어야 한다").isTrue();
    }

    private DreamGenerationTransactions.Candidate candidate(DreamResponse dream) {
        var job = jobs.findByDreamIdAndUserId(dream.dreamId(), user).orElseThrow();
        return new DreamGenerationTransactions.Candidate(job.getId(), user);
    }

    private void expireJob(DreamResponse dream) {
        jdbc.update("update dream_generation_jobs set lease_until=TIMESTAMP '2000-01-01 00:00:00' where dream_id=?", dream.dreamId());
    }

    private void dueNow(DreamResponse dream) {
        jdbc.update("update dream_generation_jobs set next_run_at=TIMESTAMP '2000-01-01 00:00:00' where dream_id=?", dream.dreamId());
    }

    @Test
    void savesJobAtomicallyAndNeverCallsAiBeforeCommit() {
        var template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            var dream = completed();
            assertThat(state(dream).status()).isEqualTo(DreamGenerationJob.Status.QUEUED);
            assertThat(structureGenerator.calls).hasValue(0);
            status.setRollbackOnly();
        });
        assertThat(jobs.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from dreams where user_id=?", Long.class, user)).isZero();
        worker.poll();
        assertThat(structureGenerator.calls).hasValue(0);
    }

    @Test
    void storesDraftAndEmotionPendingWithoutScheduling() {
        dreams.saveDraft(user, today, new DreamDraftRequest("초안", null, null));
        assertThat(jobs.count()).isZero();
        worker.poll();
        assertThat(structureGenerator.calls).hasValue(0);
    }

    @Test
    void returnsWhileAiIsPendingAndAdvancesAfterTitleEditWithoutSourceChange() throws Exception {
        structureGenerator.result = new CompletableFuture<>();
        var dream = completed();
        assertThat(structureGenerator.calls).hasValue(0);
        worker.poll();
        assertThat(structureGenerator.calls).hasValue(1);
        assertThat(state(dream).status()).isEqualTo(DreamGenerationJob.Status.PROCESSING);
        assertThat(structureGenerator.result).isNotDone();
        var edit = new DreamUpdateRequest();
        edit.setRevision(dream.revision());
        edit.setTitle("내 제목");
        dreams.update(user, dream.dreamId(), edit);
        structureGenerator.result.complete(STRUCTURE);
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(storyGenerator.calls).hasValue(1);
        assertThat(dreams.get(user, dream.dreamId()).title()).isEqualTo("내 제목");
        assertThat(state(dream).sourceChanged()).isFalse();
        assertThat(state(dream).analysisId()).isNotNull();
        assertThat(state(dream).storyId()).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from dream_images where user_id=?", Long.class, user)).isZero();
    }

    @Test
    void otherUserCanStartWhileFirstAiIsPendingButSameUserWaits() {
        structureGenerator.result = new CompletableFuture<>();
        var first = completed();
        var second = completed(user, today.minusDays(1));
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "타인")).getId();
        completed(other, today);
        worker.poll();
        assertThat(structureGenerator.calls).hasValue(2);
        assertThat(state(first).status()).isEqualTo(DreamGenerationJob.Status.PROCESSING);
        assertThat(state(second).status()).isEqualTo(DreamGenerationJob.Status.QUEUED);
    }

    @Test
    void duplicateClaimsAndRetryCannotStartSameAttemptAgain() throws Exception {
        structureGenerator.result = new CompletableFuture<>();
        var dream = completed();
        worker.poll();
        try (var otherWorker = newWorker()) {
            otherWorker.poll();
        }
        assertThat(transactions.claim(candidate(dream))).isEmpty();
        transactions.retry(user, dream.dreamId(), dream.revision());
        worker.poll();
        assertThat(structureGenerator.calls).hasValue(1);
        structureGenerator.result.complete(STRUCTURE);
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        transactions.retry(user, dream.dreamId(), dreams.get(user, dream.dreamId()).revision());
        worker.poll();
        assertThat(structureGenerator.calls).hasValue(1);
        assertThat(storyGenerator.calls).hasValue(1);
    }

    @Test
    void analysisFailureKeepsSavedDreamAndRequiresExplicitRetry() throws Exception {
        structureGenerator.result = CompletableFuture.failedFuture(new IllegalStateException("테스트 실패"));
        var dream = completed();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.FAILED);
        assertThat(state(dream).stage()).isEqualTo(DreamGenerationJob.Stage.ANALYSIS);
        assertThat(dreams.get(user, dream.dreamId()).recordStatus()).isEqualTo(DreamRecordStatus.COMPLETED);
        worker.poll();
        assertThat(structureGenerator.calls).hasValue(1);
        assertThat(storyGenerator.calls).hasValue(0);
        structureGenerator.result = CompletableFuture.completedFuture(STRUCTURE);
        transactions.retry(user, dream.dreamId(), dreams.get(user, dream.dreamId()).revision());
        transactions.retry(user, dream.dreamId(), dreams.get(user, dream.dreamId()).revision());
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(structureGenerator.calls).hasValue(2);
        assertThat(storyGenerator.calls).hasValue(1);
    }

    @Test
    void storyRetryReusesCompletedAnalysis() throws Exception {
        storyGenerator.result = CompletableFuture.completedFuture("{}");
        var dream = completed();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.FAILED);
        assertThat(state(dream).stage()).isEqualTo(DreamGenerationJob.Stage.STORY);
        assertThat(state(dream).failureCode()).isEqualTo("INVALID_OUTPUT");
        var analysisId = state(dream).analysisId();
        storyGenerator.result = CompletableFuture.completedFuture(STORY);
        transactions.retry(user, dream.dreamId(), dreams.get(user, dream.dreamId()).revision());
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(state(dream).analysisId()).isEqualTo(analysisId);
        assertThat(structureGenerator.calls).hasValue(1);
        assertThat(storyGenerator.calls).hasValue(2);
    }

    @Test
    void unavailableGatewayFailsJobWithoutUndoingSavedDream() throws Exception {
        structureGenerator.enabled = false;
        var dream = completed();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.FAILED);
        assertThat(state(dream).failureCode()).isEqualTo("UNAVAILABLE");
        assertThat(structureGenerator.calls).hasValue(0);
        assertThat(state(dream).analysisId()).isNull();
        assertThat(dreams.get(user, dream.dreamId()).recordStatus()).isEqualTo(DreamRecordStatus.COMPLETED);
    }

    @Test
    void sourceEditBeforeStartStopsJobAndDoesNotScheduleReanalysis() {
        var dream = completed();
        var edit = new DreamUpdateRequest();
        edit.setRevision(dream.revision());
        edit.setOriginalText("숲을 걷는 꿈");
        var changed = dreams.update(user, dream.dreamId(), edit);
        worker.poll();
        assertThat(state(dream).failureCode()).isEqualTo("SOURCE_CHANGED");
        assertThat(state(dream).sourceChanged()).isTrue();
        assertThat(structureGenerator.calls).hasValue(0);
        assertThatThrownBy(() -> transactions.retry(user, dream.dreamId(), changed.revision()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(DreamErrorCode.VERSION_CONFLICT));
    }

    @Test
    void editDuringAnalysisDiscardsOutputAndDoesNotStartStory() throws Exception {
        structureGenerator.result = new CompletableFuture<>();
        var dream = completed();
        worker.poll();
        var edit = new DreamUpdateRequest();
        edit.setRevision(dream.revision());
        edit.setOriginalText("숲을 걷는 꿈");
        dreams.update(user, dream.dreamId(), edit);
        structureGenerator.result.complete(STRUCTURE);
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.FAILED);
        assertThat(state(dream).failureCode()).isEqualTo("SOURCE_CHANGED");
        assertThat(storyGenerator.calls).hasValue(0);
        assertThat(analysisTransactions.get(user, state(dream).analysisId()).scenes()).isEmpty();
    }

    @Test
    void deletionDuringAnalysisRemovesJobAndLateCallbackCannotRecreateIt() throws Exception {
        structureGenerator.result = new CompletableFuture<>();
        var dream = completed();
        worker.poll();
        var analysisId = state(dream).analysisId();
        dreams.delete(user, dream.dreamId(), dreams.get(user, dream.dreamId()).revision());
        structureGenerator.result.complete(STRUCTURE);
        pumpUntil(() -> analysisTransactions.get(user, analysisId).status() == GenerationStatus.FAILED);
        assertThat(jobs.findByDreamIdAndUserId(dream.dreamId(), user)).isEmpty();
        assertThat(storyGenerator.calls).hasValue(0);
    }

    @Test
    void recoveredClaimWithNoAnalysisStartsSafelyAndRejectsOldCallback() throws Exception {
        var dream = completed();
        var old = transactions.claim(candidate(dream)).orElseThrow();
        expireJob(dream);
        var replacement = transactions.claim(candidate(dream)).orElseThrow();
        transactions.result(old, GenerationStatus.COMPLETED, null);
        assertThat(state(dream).stage()).isEqualTo(DreamGenerationJob.Stage.ANALYSIS);
        assertThat(state(dream).status()).isEqualTo(DreamGenerationJob.Status.PROCESSING);
        expireJob(dream);
        worker.close();
        worker = newWorker();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(replacement.recovering()).isTrue();
        assertThat(structureGenerator.calls).hasValue(1);
    }

    @Test
    void recoveryUsesAlreadyStoredAnalysisWithoutCallingItAgain() throws Exception {
        var dream = completed();
        transactions.claim(candidate(dream)).orElseThrow();
        await(analysis.analyze(user, dream.dreamId(), dream.revision()));
        expireJob(dream);
        worker.close();
        worker = newWorker();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(structureGenerator.calls).hasValue(1);
        assertThat(storyGenerator.calls).hasValue(1);
    }

    @Test
    void recoveryUsesAlreadyStoredStoryWithoutRegeneratingEitherStage() throws Exception {
        var dream = completed();
        var analysisClaim = transactions.claim(candidate(dream)).orElseThrow();
        await(analysis.analyze(user, dream.dreamId(), dream.revision()));
        transactions.result(analysisClaim, GenerationStatus.COMPLETED, null);
        transactions.claim(candidate(dream)).orElseThrow();
        await(story.generate(user, dream.dreamId(),
                new StoryRequest(dreams.get(user, dream.dreamId()).revision(), false, null)));
        expireJob(dream);
        worker.close();
        worker = newWorker();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(structureGenerator.calls).hasValue(1);
        assertThat(storyGenerator.calls).hasValue(1);
    }

    @Test
    void recoveredFailedAnalysisDoesNotAutomaticallyRepeatProviderCall() throws Exception {
        var dream = completed();
        transactions.claim(candidate(dream)).orElseThrow();
        var reserved = analysisTransactions.begin(user, dream.dreamId(), dream.revision(), true);
        analysisTransactions.fail(reserved.input(), "CALL_FAILED");
        expireJob(dream);
        worker.close();
        worker = newWorker();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.FAILED);
        assertThat(state(dream).failureCode()).isEqualTo("CALL_FAILED");
        assertThat(structureGenerator.calls).hasValue(0);
        assertThat(storyGenerator.calls).hasValue(0);
    }

    @Test
    void pendingManualAnalysisIsObservedWithoutDuplicateCall() throws Exception {
        structureGenerator.result = new CompletableFuture<>();
        var dream = completed();
        var manual = analysis.analyze(user, dream.dreamId(), dream.revision());
        worker.poll();
        assertThat(state(dream).status()).isEqualTo(DreamGenerationJob.Status.QUEUED);
        assertThat(structureGenerator.calls).hasValue(1);
        structureGenerator.result.complete(STRUCTURE);
        await(manual);
        dueNow(dream);
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(structureGenerator.calls).hasValue(1);
    }

    @Test
    void unknownExpiredProviderAttemptRequiresExplicitRetryAfterRestart() throws Exception {
        var dream = completed();
        transactions.claim(candidate(dream)).orElseThrow();
        var reserved = analysisTransactions.begin(user, dream.dreamId(), dream.revision(), true);
        expireJob(dream);
        jdbc.update("update dream_analyses set lease_until=TIMESTAMP '2000-01-01 00:00:00' where id=?", reserved.response().analysisId());
        worker.close();
        worker = newWorker();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.FAILED);
        assertThat(state(dream).failureCode()).isEqualTo("RECOVERY_REQUIRED");
        assertThat(structureGenerator.calls).hasValue(0);
        transactions.retry(user, dream.dreamId(), dreams.get(user, dream.dreamId()).revision());
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        assertThat(structureGenerator.calls).hasValue(1);
    }

    @Test
    void completedJobReportsStaleSourceAfterEditWithoutNewCalls() throws Exception {
        var dream = completed();
        pumpUntil(() -> state(dream).status() == DreamGenerationJob.Status.COMPLETED);
        var edit = new DreamUpdateRequest();
        edit.setRevision(dreams.get(user, dream.dreamId()).revision());
        edit.setOriginalText("숲을 걷는 꿈");
        dreams.update(user, dream.dreamId(), edit);
        worker.poll();
        assertThat(state(dream).status()).isEqualTo(DreamGenerationJob.Status.COMPLETED);
        assertThat(state(dream).sourceChanged()).isTrue();
        assertThat(structureGenerator.calls).hasValue(1);
        assertThat(storyGenerator.calls).hasValue(1);
    }

    @Test
    void apiChecksAuthOwnershipRevisionAndNoStore() throws Exception {
        var dream = completed();
        var token = tokens.login(user).response().accessToken();
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "타인")).getId();
        var stranger = tokens.login(other).response().accessToken();
        try (var client = HttpClient.newHttpClient()) {
            assertStatus(send(client, dream.dreamId(), null, null), 401);
            assertStatus(send(client, dream.dreamId(), stranger, null), 404);
            var found = send(client, dream.dreamId(), token, null);
            assertStatus(found, 200);
            assertThat(found.headers().firstValue("Cache-Control")).contains("no-store");
            for (String invalid : List.of("{}", "{\"revision\":-1}")) {
                assertStatus(send(client, dream.dreamId(), token, invalid), 400);
            }
            assertStatus(send(client, dream.dreamId(), stranger, "{\"revision\":" + dream.revision() + "}"), 404);
            assertStatus(send(client, dream.dreamId(), token, "{\"revision\":9999}"), 409);
            var retried = send(client, dream.dreamId(), token, "{\"revision\":" + dream.revision() + "}");
            assertStatus(retried, 202);
            assertThat(retried.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(structureGenerator.calls).hasValue(0);
        }
    }

    private HttpResponse<String> send(HttpClient client, Long id, String token, String body) throws Exception {
        String path = "/api/v1/dreams/" + id + "/generation" + (body == null ? "" : "/retry");
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertStatus(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
    }
}
